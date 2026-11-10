package orilumn.reader.engine.revision

/**
 * 行内标记语法：选区结构 ↔ 输入卡里的标记文本（规格见 `docs/正文修改-行内标记语法.md`）。
 *
 * 边界（铁律，别在这里越界）：
 * - **标记只活在输入框里**。提交产物是「纯文本 + 样式区间」：纯文本交给 `TextEditPlanner`，
 *   样式区间随修订落库、由 `LeafTextEditor` 在重放时套回元素；正文与坐标永不包含标记字符。
 * - [show] / [parse] 是一对**互逆**的纯函数。幂等靠「先少转义试一遍、解析回来结构不等就整体转义」
 *   兜底，因此 `show` 的输出一定满足 `parse(show(x))` 与 `x` 同结构。
 * - 口径逐条对应规格文档 §2（语法）、§3（显示）、§4.3（属性补回）、§5（校验），改动必须同步两边。
 * - 归排版层（上）：不碰 Android、不碰排版、不碰数据库。
 */
object InlineMarkup {

    /** 语法 / 解析 / 落地规则的版本：进 `contentRev`，规则一改旧分页表按章作废。 */
    const val VERSION = 1

    /** 允许出现的行内标签（规格 §2.2）。新增必须同步文档。 */
    val ALLOWED_TAGS: Set<String> = setOf(
        "em", "i", "strong", "b", "code", "kbd", "samp", "var", "span", "a", "sup", "sub",
        "u", "s", "strike", "del", "ins", "small", "big", "mark", "q", "cite", "abbr", "dfn",
        "ruby", "rt", "rp", "bdi", "bdo", "br", "wbr",
    )

    /** 有语法糖的标签（其余用标签形式）。 */
    val SUGAR_TAGS: Set<String> = setOf("em", "strong", "code", "sup", "sub", "s", "a")

    /** 允许保留的属性名（规格 §5.2）。`id` 只在原位保留时使用。 */
    val ALLOWED_ATTRS: Set<String> = setOf(
        "class", "style", "id", "title", "lang", "dir", "href", "target", "datetime",
    )

    private val STYLE_KEEP = listOf(
        "color", "background-color", "font-", "text-decoration", "text-transform",
        "letter-spacing", "word-spacing", "vertical-align", "border", "outline",
        "padding", "margin-left", "margin-right", "text-shadow", "opacity",
    )
    private val STYLE_DROP = listOf(
        "position", "float", "display", "visibility", "transform", "animation",
        "transition", "filter", "width", "height", "overflow", "z-index", "content",
        "white-space",
    )

    /** 行内元素签名：标签 + 允许的属性（顺序无关）。 */
    data class StyleSig(val tag: String, val attrs: Map<String, String> = emptyMap()) {
        /** 结构比较用的键：有语法糖的标签只看标签（属性在回写时补回，不影响前后缀对齐）。 */
        val shapeKey: String get() = if (tag in SUGAR_TAGS) tag else tag + attrs.toSortedMap()
    }

    /** 一段行内样式：作用于纯文本 `[start, endExclusive)`，可嵌套。 */
    data class StyleSpan(val start: Int, val endExclusive: Int, val sig: StyleSig)

    /** 转成数据层的落库形态（`TextRevision.styles`）。 */
    fun toStored(spans: List<StyleSpan>): List<orilumn.reader.data.revision.RevisionStyleSpan> =
        spans.map {
            orilumn.reader.data.revision.RevisionStyleSpan(
                it.start,
                it.endExclusive,
                orilumn.reader.data.revision.RevisionStyle(it.sig.tag, it.sig.attrs),
            )
        }

    /** 从落库形态取回（`TextRevision.styles` → 排版层区间）。 */
    fun fromStored(
        spans: List<orilumn.reader.data.revision.RevisionStyleSpan>,
    ): List<StyleSpan> = spans.map {
        StyleSpan(it.start, it.endExclusive, StyleSig(it.style.tag, it.style.attrs))
    }

    /** 解析结果：纯文本、样式区间、错误。[errors] 非空 ⇒ 拒绝提交（卡片就地提示）。 */
    data class Parsed(
        val text: String,
        val spans: List<StyleSpan>,
        val errors: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    // ---------------------------------------------------------------- 序列化

    /**
     * 纯文本 + 样式区间 → 标记文本（规格 §3）。
     *
     * 有语法糖的标签一律用语法糖（属性在 [restore] 里补回），其余用标签形式；
     * 文字里的标记字符**只在会被误解析时转义**：先按最少转义出一版，解析回来结构不等就整体转义。
     */
    fun show(text: String, spans: List<StyleSpan>): String {
        val clean = sanitize(text, spans)
        val expected = clean.map { it.start to it.endExclusive to it.sig.shapeKey }
        val naive = render(text, clean, escapeMarkerChars = false)
        val naiveParsed = parse(naive)
        if (naiveParsed.ok && sameShape(naiveParsed, text, expected)) return naive
        return render(text, clean, escapeMarkerChars = true)
    }

    /**
     * 提交前的属性补回（规格 §4.3）：按「同标签 + 同嵌套顺序」把解析出的区间对回原选区的区间，
     * 补回原元素的属性（读者写出的属性优先；新增元素丢 `id`，原位保留的保留 `id`）。
     */
    fun restore(original: List<StyleSpan>, parsed: List<StyleSpan>): List<StyleSpan> {
        if (parsed.isEmpty()) return emptyList()
        val used = BooleanArray(original.size)
        val out = arrayOfNulls<StyleSig>(parsed.size)
        // 第一轮：结构与属性都对得上的（标签形式原样没改）。
        for ((i, p) in parsed.withIndex()) {
            val j = original.indices.firstOrNull {
                !used[it] && original[it].sig.shapeKey == p.sig.shapeKey
            } ?: continue
            used[j] = true
            out[i] = merge(original[j].sig, p.sig)
        }
        // 第二轮：同标签、同顺序（语法糖形式，属性在上一轮对不上）。
        for ((i, p) in parsed.withIndex()) {
            if (out[i] != null) continue
            val j = original.indices.firstOrNull { !used[it] && original[it].sig.tag == p.sig.tag }
            out[i] = if (j != null) {
                used[j] = true
                merge(original[j].sig, p.sig)
            } else {
                // 读者新写的标记：属性照用，但 id 会撞号，一律不要。
                StyleSig(p.sig.tag, p.sig.attrs - "id")
            }
        }
        return parsed.mapIndexed { i, p -> StyleSpan(p.start, p.endExclusive, out[i]!!) }
    }

    /** 原位保留：补回原属性，读者写出的覆盖原值；原 id 保留（`id` 不在读者写法里就沿用原值）。 */
    private fun merge(src: StyleSig, parsed: StyleSig): StyleSig =
        StyleSig(src.tag, src.attrs + parsed.attrs)

    // ---------------------------------------------------------------- 解析

    private class Tag(val name: String, val attrs: Map<String, String>, val closing: Boolean)

    private sealed interface Tok {
        class Ch(val c: Char) : Tok
        class TagTok(val tag: Tag, val at: Int, val end: Int) : Tok
        class Marker(
            val marker: Char,
            val len: Int,
            val canOpen: Boolean,
            val canClose: Boolean,
            val at: Int,
        ) : Tok

        class Code(val text: String, val at: Int, val end: Int) : Tok
        class Link(val inner: String, val href: String, val at: Int, val end: Int) : Tok
    }

    /**
     * 解析标记文本（规格 §2 / §5）。
     *
     * 配对口径：markdown 标记**必须成对**，不成对按字面（CommonMark；`3 * 4 * 5`、`1~2` 因此安全）；
     * **标签**可以不配对——未闭合的开标签延伸到这段文字末尾、孤立的闭标签从这段文字开头起
     * （「元素在选区外继续」，规格 §2.4）。
     */
    fun parse(markup: String): Parsed {
        val errors = ArrayList<String>()
        val toks = tokenize(markup, errors)

        // 1) markdown 标记配对（同标记、同长度；就近配对）。
        val openers = ArrayList<Int>()
        val closeOf = HashMap<Int, Int>()
        val closerSet = HashSet<Int>()
        for (i in toks.indices) {
            val t = toks[i] as? Tok.Marker ?: continue
            if (t.canClose) {
                val k = openers.indexOfLast { j ->
                    val o = toks[j] as Tok.Marker
                    o.marker == t.marker && o.len == t.len
                }
                if (k >= 0) {
                    val o = openers.removeAt(k)
                    closeOf[o] = i
                    closerSet.add(i)
                    continue
                }
            }
            if (t.canOpen) openers.add(i)
        }

        // 2) 落地成文字与区间。
        val sb = StringBuilder(markup.length)
        val spans = ArrayList<StyleSpan>()
        val openTags = ArrayList<Pair<Tag, Int>>()
        val openSugar = ArrayList<Triple<Int, List<String>, Int>>()

        fun closeSugarUntil(tokIndex: Int) {
            while (openSugar.isNotEmpty() && openSugar.last().first <= tokIndex) {
                val (_, tags, start) = openSugar.removeAt(openSugar.size - 1)
                val end = sb.length
                for (tag in tags.asReversed()) {
                    if (end > start) spans.add(StyleSpan(start, end, StyleSig(tag)))
                }
            }
        }

        for (i in toks.indices) {
            closeSugarUntil(i)
            when (val t = toks[i]) {
                is Tok.Ch -> sb.append(t.c)

                is Tok.Code -> {
                    val start = sb.length
                    sb.append(t.text)
                    if (sb.length > start) spans.add(StyleSpan(start, sb.length, StyleSig("code")))
                }

                is Tok.Link -> {
                    val start = sb.length
                    val sub = parse(t.inner)
                    errors.addAll(sub.errors)
                    sb.append(sub.text)
                    for (s in sub.spans) {
                        spans.add(StyleSpan(s.start + start, s.endExclusive + start, s.sig))
                    }
                    if (sb.length > start) {
                        spans.add(StyleSpan(start, sb.length, StyleSig("a", mapOf("href" to t.href))))
                    }
                }

                is Tok.TagTok -> {
                    val tag = t.tag
                    when {
                        tag.name == "br" -> sb.append('\n')
                        tag.name == "wbr" -> Unit
                        tag.name !in ALLOWED_TAGS -> errors.add("「<${tag.name}>」不是支持的行内标记")
                        !tag.closing -> openTags.add(tag to sb.length)
                        tag.name == "br" || tag.name == "wbr" -> Unit
                        else -> {
                            val idx = openTags.indexOfLast { it.first.name == tag.name }
                            if (idx >= 0) {
                                // 更内层没闭合的标签夹在这里（允许不配对，§2.4）。
                                for (k in openTags.indices.reversed()) {
                                    if (k < idx) break
                                    val (openTag, start) = openTags.removeAt(k)
                                    val end = sb.length
                                    if (end > start) {
                                        spans.add(
                                            StyleSpan(start, end, StyleSig(openTag.name, filtered(openTag.attrs))),
                                        )
                                    }
                                }
                            } else if (sb.isNotEmpty()) {
                                // 孤立的闭标签 = 元素在选区外继续：从这段文字开头起。
                                spans.add(StyleSpan(0, sb.length, StyleSig(tag.name, filtered(tag.attrs))))
                            }
                        }
                    }
                }

                is Tok.Marker -> {
                    val endTok = closeOf[i]
                    when {
                        endTok != null -> openSugar.add(Triple(endTok, sugarTags(t.marker, t.len), sb.length))
                        i in closerSet -> Unit
                        else -> repeat(t.len) { sb.append(t.marker) }
                    }
                }
            }
        }
        closeSugarUntil(toks.size)

        // 3) 未闭合的标签：延伸到这段文字末尾。
        for (k in openTags.indices.reversed()) {
            val (tag, start) = openTags[k]
            val end = sb.length
            if (end > start) spans.add(StyleSpan(start, end, StyleSig(tag.name, filtered(tag.attrs))))
        }

        val invalid = validate(spans)
        errors.addAll(invalid)
        return Parsed(sb.toString(), nest(normalize(spans)), errors.distinct())
    }

    /**
     * 标记文本里**属于语法**的字符区间（只给输入框把标记淡显用，不参与解析）。
     *
     * 口径与 [parse] 一致：成对的 markdown 标记、反引号、链接的括号、`<...>` 标签算语法；
     * 不成对的标记按字面（读者写的就是这些字），不淡化。区间升序、互不重叠。
     */
    fun markerRanges(markup: String): List<IntRange> {
        if (markup.isEmpty()) return emptyList()
        val errors = ArrayList<String>()
        val toks = tokenize(markup, errors)
        val openers = ArrayList<Int>()
        val paired = HashSet<Int>()
        for (i in toks.indices) {
            val t = toks[i] as? Tok.Marker ?: continue
            if (t.canClose) {
                val k = openers.indexOfLast { j ->
                    val o = toks[j] as Tok.Marker
                    o.marker == t.marker && o.len == t.len
                }
                if (k >= 0) {
                    paired.add(openers.removeAt(k))
                    paired.add(i)
                    continue
                }
            }
            if (t.canOpen) openers.add(i)
        }
        val out = ArrayList<IntRange>()
        for ((i, t) in toks.withIndex()) {
            when (t) {
                is Tok.Marker -> if (i in paired) out.add(t.at until t.at + t.len)
                is Tok.Code -> {
                    out.add(t.at until t.at + 1)
                    out.add(t.end - 1 until t.end)
                }
                is Tok.TagTok -> out.add(t.at until t.end)
                is Tok.Link -> {
                    out.add(t.at until t.at + 1)
                    val close = t.at + 1 + t.inner.length
                    out.add(close until close + 2)
                    out.add(t.end - 1 until t.end)
                }
                is Tok.Ch -> Unit
            }
        }
        // 相邻 / 重叠的合并成一段，少几条 SpanStyle。
        val merged = ArrayList<IntRange>(out.size)
        for (r in out.sortedBy { it.first }) {
            val last = merged.lastOrNull()
            if (last != null && r.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
            } else {
                merged.add(r)
            }
        }
        return merged
    }

    // ---------------------------------------------------------------- 工具

    private fun sugarTags(c: Char, run: Int): List<String> = when (c) {
        '*' -> when (run) {
            1 -> listOf("em")
            2 -> listOf("strong")
            else -> listOf("strong", "em")
        }
        '~' -> if (run == 1) listOf("sub") else listOf("s")
        else -> listOf("sup")
    }

    private fun tokenize(s: String, errors: MutableList<String>): List<Tok> {
        val out = ArrayList<Tok>(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> {
                    if (i + 1 < s.length) {
                        out.add(Tok.Ch(s[i + 1])); i += 2
                    } else {
                        out.add(Tok.Ch('\\')); i++
                    }
                }

                c == '&' -> {
                    val ent = readEntity(s, i)
                    if (ent != null) {
                        out.add(Tok.Ch(ent.first)); i = ent.second
                    } else {
                        out.add(Tok.Ch('&')); i++
                    }
                }

                c == '<' -> {
                    val tag = readTag(s, i)
                    if (tag == null) {
                        out.add(Tok.Ch('<')); i++
                    } else {
                        out.add(Tok.TagTok(tag.first, i, tag.second)); i = tag.second
                    }
                }

                c == '`' -> {
                    val close = findUnescaped(s, '`', i + 1)
                    if (close < 0) {
                        out.add(Tok.Ch('`')); i++
                    } else {
                        out.add(Tok.Code(s.substring(i + 1, close), i, close + 1)); i = close + 1
                    }
                }

                c == '*' || c == '~' || c == '^' -> {
                    var run = 1
                    while (i + run < s.length && s[i + run] == c) run++
                    val maxRun = if (c == '*') 3 else if (c == '~') 2 else 1
                    if (run > maxRun) {
                        repeat(run) { out.add(Tok.Ch(c)) }
                    } else {
                        val canClose = i > 0 && !s[i - 1].isWhitespace() && s[i - 1] != c
                        val canOpen = i + run < s.length && !s[i + run].isWhitespace() && s[i + run] != c
                        out.add(Tok.Marker(c, run, canOpen, canClose, i))
                    }
                    i += run
                }

                c == '[' -> {
                    val link = readLink(s, i)
                    if (link == null) {
                        out.add(Tok.Ch('[')); i++
                    } else {
                        out.add(Tok.Link(link.first, link.second, i, link.third)); i = link.third
                    }
                }

                else -> {
                    out.add(Tok.Ch(c)); i++
                }
            }
        }
        return out
    }

    private fun readEntity(s: String, at: Int): Pair<Char, Int>? {
        val map = mapOf(
            "&amp;" to '&', "&lt;" to '<', "&gt;" to '>', "&quot;" to '"', "&#39;" to '\'',
        )
        for ((k, v) in map) if (s.startsWith(k, at)) return v to at + k.length
        return null
    }

    private fun findUnescaped(s: String, ch: Char, from: Int): Int {
        var i = from
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == ch) return i
            if (c == '\n') return -1
            i++
        }
        return -1
    }

    /** `<tag a="b" c>` → (Tag, next)；不是完整标签返回 null（按字面）。 */
    private fun readTag(s: String, at: Int): Pair<Tag, Int>? {
        var i = at + 1
        val closing = i < s.length && s[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < s.length && (s[i].isLetterOrDigit() || s[i] == ':' || s[i] == '-')) i++
        if (i == nameStart) return null
        val name = s.substring(nameStart, i).lowercase()
        val attrs = LinkedHashMap<String, String>()
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) return null
            if (s[i] == '>') { i++; return Tag(name, attrs, closing) to i }
            if (s[i] == '/' && i + 1 < s.length && s[i + 1] == '>') { i += 2; return Tag(name, attrs, closing) to i }
            val ns = i
            while (i < s.length && s[i] != '=' && s[i] != '>' && !s[i].isWhitespace() && s[i] != '/') i++
            if (i == ns) return null
            val key = s.substring(ns, i).lowercase()
            while (i < s.length && s[i].isWhitespace()) i++
            if (i < s.length && s[i] == '=') {
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) return null
                val quote = s[i]
                val value = if (quote == '"' || quote == '\'') {
                    val end = s.indexOf(quote, i + 1)
                    if (end < 0) return null
                    val v = s.substring(i + 1, end)
                    i = end + 1
                    v
                } else {
                    val vs = i
                    while (i < s.length && !s[i].isWhitespace() && s[i] != '>') i++
                    s.substring(vs, i)
                }
                attrs[key] = value.replace("&quot;", "\"").replace("&amp;", "&")
            } else {
                attrs[key] = ""
            }
        }
        return null
    }

    /** `[文字](url)` → (inner, href, next)；不是链接返回 null。 */
    private fun readLink(s: String, at: Int): Triple<String, String, Int>? {
        var depth = 0
        var i = at
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') { i += 2; continue }
            if (c == '[') depth++
            if (c == ']') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        if (i >= s.length) return null
        val inner = s.substring(at + 1, i)
        if (i + 1 >= s.length || s[i + 1] != '(') return null
        var j = i + 2
        val href = StringBuilder()
        while (j < s.length) {
            val c = s[j]
            if (c == '\\' && j + 1 < s.length) { href.append(s[j + 1]); j += 2; continue }
            if (c == ')') return Triple(inner, href.toString(), j + 1)
            if (c == '\n') return null
            href.append(c); j++
        }
        return null
    }

    /** 属性白名单 + `style` 声明过滤（规格 §5.2）。 */
    private fun filtered(attrs: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k, v) in attrs) {
            if (k !in ALLOWED_ATTRS) continue
            if (k == "style") {
                val kept = filterStyle(v)
                if (kept.isNotEmpty()) out[k] = kept
            } else {
                out[k] = v
            }
        }
        return out
    }

    /** `style` 只留装饰类声明：影响版面的一律剔除（规格 §5.2）。 */
    internal fun filterStyle(css: String): String =
        css.split(';').mapNotNull { decl ->
            val i = decl.indexOf(':')
            if (i <= 0) return@mapNotNull null
            val name = decl.substring(0, i).trim().lowercase()
            val value = decl.substring(i + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return@mapNotNull null
            if (STYLE_DROP.any { name == it || name.startsWith("$it-") }) return@mapNotNull null
            if (STYLE_KEEP.none { name == it.replace("-", "") || name.startsWith(it) }) return@mapNotNull null
            "$name:$value"
        }.joinToString(";")

    private fun validate(spans: List<StyleSpan>): List<String> {
        val errs = ArrayList<String>()
        for (s in spans) {
            if (s.sig.tag != "a") continue
            val href = s.sig.attrs["href"] ?: continue
            val ok = href.isBlank() ||
                href.startsWith("http://") || href.startsWith("https://") ||
                href.startsWith("mailto:") || href.startsWith("tel:") ||
                href.startsWith("/") || href.startsWith("./") || href.startsWith("../") ||
                !href.contains(':')
            if (!ok) errs.add("链接地址只能用 http / https / mailto / tel 或相对路径：$href")
        }
        return errs
    }

    private fun normalize(spans: List<StyleSpan>): List<StyleSpan> =
        spans.filter { it.endExclusive > it.start }
            .sortedWith(compareBy({ it.start }, { -it.endExclusive }))

    /** 交叉的区间没有合法的嵌套表示：把后开的那个夹到外层末尾（树来源的区间本来就不交叉）。 */
    private fun nest(spans: List<StyleSpan>): List<StyleSpan> {
        val out = ArrayList<StyleSpan>(spans.size)
        for (s in spans) {
            val crossed = out.lastOrNull {
                it.start < s.start && it.endExclusive > s.start && it.endExclusive < s.endExclusive
            }
            out.add(if (crossed != null) StyleSpan(s.start, crossed.endExclusive, s.sig) else s)
        }
        return normalize(out)
    }

    private fun sanitize(text: String, spans: List<StyleSpan>): List<StyleSpan> =
        normalize(
            spans.filter { it.start >= 0 && it.endExclusive <= text.length && it.sig.tag in ALLOWED_TAGS }
                .map { it.copy(sig = StyleSig(it.sig.tag, filtered(it.sig.attrs))) },
        )

    private fun sameShape(
        parsed: Parsed,
        text: String,
        expected: List<Pair<Pair<Int, Int>, String>>,
    ): Boolean {
        if (parsed.text != text) return false
        val got = parsed.spans.map { (it.start to it.endExclusive) to it.sig.shapeKey }
        return got == expected
    }

    private class Marker(val tag: String, val open: String, val close: String)

    private fun markerOf(sig: StyleSig, content: String): Marker? = when (sig.tag) {
        "em" -> Marker("em", "*", "*")
        "strong" -> Marker("strong", "**", "**")
        "code" -> if (content.contains('`') || content.contains('\n')) null else Marker("code", "`", "`")
        "sup" -> Marker("sup", "^", "^")
        "sub" -> Marker("sub", "~", "~")
        "s" -> Marker("s", "~~", "~~")
        "a" -> Marker("a", "[", "](${sig.attrs["href"].orEmpty()})")
        else -> null
    }

    private fun tagMarkup(sig: StyleSig, closing: Boolean): String {
        if (closing) return "</${sig.tag}>"
        val sb = StringBuilder("<").append(sig.tag)
        for ((k, v) in sig.attrs.toSortedMap()) {
            sb.append(' ').append(k).append("=\"").append(v.replace("\"", "&quot;")).append('"')
        }
        return sb.append('>').toString()
    }

    private fun render(text: String, spans: List<StyleSpan>, escapeMarkerChars: Boolean): String {
        val sorted = nest(spans)
        val sb = StringBuilder(text.length + 16)
        val open = ArrayList<StyleSpan>()
        val markers = ArrayList<Marker?>()
        for (i in 0..text.length) {
            while (open.isNotEmpty() && open.last().endExclusive <= i) {
                val s = open.removeAt(open.size - 1)
                val m = markers.removeAt(markers.size - 1)
                sb.append(m?.close ?: tagMarkup(s.sig, true))
            }
            if (i < text.length) {
                for (s in sorted) if (s.start == i) {
                    val m = markerOf(s.sig, text.substring(s.start, s.endExclusive))
                    sb.append(m?.open ?: tagMarkup(s.sig, false))
                    open.add(s)
                    markers.add(m)
                }
                val inCode = markers.lastOrNull()?.tag == "code"
                sb.append(if (inCode) text[i].toString() else escapeChar(text[i], escapeMarkerChars))
            }
        }
        return sb.toString()
    }

    private fun escapeChar(c: Char, escapeMarkerChars: Boolean): String = when (c) {
        '\\' -> "\\\\"
        '<' -> "\\<"
        '&' -> "&amp;"
        // 反引号只在整体转义时才转义：成对的 `` 会被当成代码，单打独斗的不会。
        '`' -> if (escapeMarkerChars) "\\`" else "`"
        else -> if (escapeMarkerChars && c in "*~^[]()") "\\$c" else c.toString()
    }
}
