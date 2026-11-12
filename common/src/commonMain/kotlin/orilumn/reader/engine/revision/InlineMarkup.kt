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

    /**
     * 空元素（void）：**没有结束标签**，也就没有内容（HTML 规范）。
     *
     * `</br>` / `</wbr>` 因此是无效写法：浏览器容错地当 `<br>` 处理，作者却以为自己
     * 「闭合了某个东西」。卡片里一律拒绝并提示，别让读者打的那几个字悄悄消失。
     */
    private val VOID_TAGS: Set<String> = setOf("br", "wbr")

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
        /**
         * 结构比较用的键：有语法糖的标签只看标签（属性在回写时补回，不影响前后缀对齐）。
         *
         * **`id` 不参与**：它是身份不是形状。解析侧一律剥掉读者手写的 `id`（[parse]），
         * 原位保留的那条由 [restore] 从原文补回；若把 `id` 算进键，带 id 的元素每次都被判成
         * 「结构变了」，回验失败后卡片退化成全标签形式。
         */
        val shapeKey: String
            get() = if (tag in SUGAR_TAGS) tag else tag + (attrs - "id").toSortedMap()
    }

    /** 一段行内样式：作用于纯文本 `[start, endExclusive)`，可嵌套。 */
    data class StyleSpan(val start: Int, val endExclusive: Int, val sig: StyleSig)

    /**
     * 输入区里的一块：文字 + 块形态（`# 标题`、`> 引用`、`- 列表`、代码块…）。
     *
     * [text] 里的 `\n` 是**书里真正的换行**（代码块内部换行，或叶内 `<br>`），不是块边界：
     * 块边界只在 [Segment] 与 [Segment] 之间。
     */
    data class Segment(val text: String, val block: BlockKind? = null)

    /** 若干块拼成输入区全文（块之间一个 `\n`，与修订文字的段界一一对应）。 */
    fun joinSegments(segments: List<Segment>): String =
        segments.joinToString("\n") { it.text }

    /** 保留空行的 `\n` 切分（`String.split` 的尾部空串行为在跨平台实现里不够直观）。 */
    fun splitLines(text: String): List<String> {
        val out = ArrayList<String>()
        var from = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                out.add(text.substring(from, i))
                from = i + 1
            }
        }
        out.add(text.substring(from))
        return out
    }

    /** 每个块在拼接文字里的起点（块之间占一个 `\n`）。 */
    fun segmentStarts(segments: List<Segment>): IntArray {
        val out = IntArray(segments.size)
        var cursor = 0
        for (i in segments.indices) {
            out[i] = cursor
            cursor += segments[i].text.length + 1
        }
        return out
    }

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
        /** 每块的块形态与文字（块与块之间用 `\n` 拼成 [text]）。 */
        val segments: List<Segment> = listOf(Segment(text)),
    ) {
        val ok: Boolean get() = errors.isEmpty()
    }

    // ---------------------------------------------------------------- 序列化

    /**
     * 纯文本 + 样式区间 → 标记文本（规格 §3）。
     *
     * 有语法糖的标签一律用语法糖（属性在 [restore] 里补回），其余用标签形式；
     * 文字里的标记字符**只在会被误解析时转义**：先按最少转义出一版，解析回来结构不等就整体转义。
     *
     * **每一版都要解析回验**（规格 §6：`显示 → 解析` 必须逐字逐元素等价）。只验第一版是不够的：
     * 全转义版遇到极端内容仍可能不等（`<em>` 里只有一个 `*`、链接地址自带 `)`），那时确认就会
     * 悄悄改掉正文。所以第三版把**所有元素都写成标签形式**（标签不参与 markdown 配对，最稳）；
     * 它再不等只能说明 `sanitize` 之外还有 bug，返回它仍是当下最接近原样的一版。
     */
    fun show(text: String, spans: List<StyleSpan>): String =
        show(listOf(Segment(text)), spans)

    /**
     * 多块版本：每块按自己的形态写块标记，块内的换行落成硬断行（`\` + 换行），
     * 代码块落成围栏。块的顺序与 [Segment] 一一对应。
     */
    fun show(segments: List<Segment>, spans: List<StyleSpan>): String {
        val joined = joinSegments(segments)
        val starts = segmentStarts(segments)
        val clean = clampToSegments(sanitize(joined, spans), starts, segments)
        val expected = clean.map { it.start to it.endExclusive to it.sig.shapeKey }
        fun ok(markup: String): Boolean {
            val p = parse(markup)
            return p.ok && sameShape(p, segments, expected)
        }
        val naive = renderSegments(segments, clean, escapeMarkerChars = false, forceTags = false)
        if (ok(naive)) return naive
        val escaped = renderSegments(segments, clean, escapeMarkerChars = true, forceTags = false)
        if (ok(escaped)) return escaped
        return renderSegments(segments, clean, escapeMarkerChars = true, forceTags = true)
    }

    /**
     * 区间按块切开：跨块的那条在每块里各留一段（块与块之间不共享行内元素）。
     *
     * 返回值仍是**拼接后文字**（[joinSegments]）的坐标，只是夹到各块之内 —— [sameShape] 要拿它
     * 跟 [parse] 出来的区间逐条比。块内坐标由 [renderSegments] 自己换算，别在这里提前减。
     */
    private fun clampToSegments(
        spans: List<StyleSpan>,
        starts: IntArray,
        segments: List<Segment>,
    ): List<StyleSpan> {
        if (spans.isEmpty()) return spans
        val out = ArrayList<StyleSpan>(spans.size + 2)
        for ((i, seg) in segments.withIndex()) {
            val from = starts[i]
            val to = from + seg.text.length
            for (s in spans) {
                val s0 = maxOf(s.start, from)
                val s1 = minOf(s.endExclusive, to)
                if (s1 > s0) out.add(StyleSpan(s0, s1, s.sig))
            }
        }
        return normalize(out)
    }

    /**
     * 提交前的属性补回（规格 §4.3）：按「同标签 + 同嵌套顺序」把解析出的区间对回原选区的区间，
     * 补回原元素的属性（读者写出的属性优先；新增元素丢 `id`，原位保留的保留 `id`）。
     *
     * [templates] 是**同段**的行内结构（可以比选区更宽）：读者新写的裸标记（`*新*`）从同标签的
     * 模板元素抄属性，免得新写的斜体跟同段其它斜体长得不一样（规格 §4.3「读者新写的标记」）。
     * 只补读者一个属性都没写的那些——写了属性就是显式意图，模板不许改。
     */
    fun restore(
        original: List<StyleSpan>,
        parsed: List<StyleSpan>,
        templates: List<StyleSpan> = emptyList(),
    ): List<StyleSpan> {
        if (parsed.isEmpty()) return emptyList()
        val used = BooleanArray(original.size)
        val out = arrayOfNulls<StyleSig>(parsed.size)
        val isNew = BooleanArray(parsed.size) { true }
        // 第一轮：结构与属性都对得上的（标签形式原样没改）。
        for ((i, p) in parsed.withIndex()) {
            val j = original.indices.firstOrNull {
                !used[it] && original[it].sig.shapeKey == p.sig.shapeKey
            } ?: continue
            used[j] = true
            out[i] = merge(original[j].sig, p.sig)
            isNew[i] = false
        }
        // 第二轮：同标签、同顺序（语法糖形式，属性在上一轮对不上）。
        for ((i, p) in parsed.withIndex()) {
            if (out[i] != null) continue
            val j = original.indices.firstOrNull { !used[it] && original[it].sig.tag == p.sig.tag }
            out[i] = if (j != null) {
                used[j] = true
                isNew[i] = false
                merge(original[j].sig, p.sig)
            } else {
                // 读者新写的标记：属性照用，但 id 会撞号，一律不要。
                StyleSig(p.sig.tag, p.sig.attrs - "id")
            }
        }
        val restored = parsed.mapIndexed { i, p -> StyleSpan(p.start, p.endExclusive, out[i]!!) }
        if (templates.isEmpty()) return restored
        // 只有**新写**的区间按模板补空属性：原位保留的那份以原文为准（原文没有的 class 不该凭空长出来）。
        val templated = inheritTemplateAttrs(restored, templates)
        return restored.mapIndexed { i, s -> if (isNew[i]) templated[i] else s }
    }

    /** 原位保留：补回原属性，读者写出的覆盖原值；原 id 保留（`id` 不在读者写法里就沿用原值）。 */
    private fun merge(src: StyleSig, parsed: StyleSig): StyleSig =
        StyleSig(src.tag, src.attrs + parsed.attrs)

    /**
     * 读者**新写**的标记按模板继承属性（规格 §4.3）：模板优先级是「同段已有的同标签元素」。
     *
     * 只补**读者一个属性都没写**的那些区间（`*新*` 解析出来是裸 `<em>`）：同段里有一个
     * `<em class="a" id="x">`，读者写的 `*新*` 就该长得跟它一样，而不是一个没有 class 的裸 em。
     * `id` 一律不带过去——新元素带原 id 会撞号（摘录锚点、脚注锚点都靠 id）。
     *
     * 读者写了属性的（`<span class="b">`、`[字](新地址)`）一个字都不动：模板只补空位，不改显式意图。
     */
    fun inheritTemplateAttrs(
        spans: List<StyleSpan>,
        templates: List<StyleSpan>,
    ): List<StyleSpan> {
        if (spans.isEmpty() || templates.isEmpty()) return spans
        // 同标签取第一个模板：模板列表已按 (起点升序、外层在前) 排好，同段的「第一个」就是它。
        val byTag = LinkedHashMap<String, Map<String, String>>()
        for (t in templates) {
            val attrs = t.sig.attrs - "id"
            if (attrs.isEmpty()) continue
            if (!byTag.containsKey(t.sig.tag)) byTag[t.sig.tag] = attrs
        }
        if (byTag.isEmpty()) return spans
        return spans.map { s ->
            if (s.sig.attrs.isNotEmpty()) return@map s
            val attrs = byTag[s.sig.tag] ?: return@map s
            s.copy(sig = s.sig.copy(attrs = attrs))
        }
    }

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
     * 解析标记文本（规格 §2 / §5）：先按块切（[BlockMarkup]：行首块标记、代码块围栏、硬断行），
     * 每块再走行内解析（块与块之间不共享行内元素）。
     *
     * 块级的错误（围栏没收尾）也进 [Parsed.errors]：卡片就地提示，正文不动。
     */
    fun parse(markup: String): Parsed {
        val split = BlockMarkup.split(markup)
        val errors = ArrayList<String>(2)
        if (split.unterminatedFence) {
            errors.add("代码块没有收尾：结尾再写一行 ``` 把这段代码关上")
        }
        val segments = ArrayList<Segment>(split.blocks.size)
        val spans = ArrayList<StyleSpan>()
        val sb = StringBuilder(markup.length)
        for ((i, block) in split.blocks.withIndex()) {
            if (i > 0) sb.append('\n')
            val at = sb.length
            if (block.kind == BlockKind.Pre) {
                // 代码块内容原样：不解析行内标记、不转义（反引号 / 星号都是代码本身）。
                sb.append(block.content)
                segments.add(Segment(block.content, BlockKind.Pre))
            } else {
                val p = parseInline(block.content)
                errors.addAll(p.errors)
                for (s in p.spans) spans.add(StyleSpan(s.start + at, s.endExclusive + at, s.sig))
                sb.append(p.text)
                segments.add(Segment(p.text, block.kind))
            }
        }
        return Parsed(sb.toString(), spans, errors.distinct(), segments)
    }

    /**
     * 行内解析（不含块层）：未配对的标签 / 成对标记 / 链接 / 转义在这里落地。
     *
     * 配对口径：markdown 标记**必须成对**，不成对按字面（CommonMark；`3 * 4 * 5`、`1~2` 因此安全）；
     * **标签**可以不配对——未闭合的开标签延伸到这段文字末尾、孤立的闭标签从这段文字开头起
     * （「元素在选区外继续」，规格 §2.4）。
     */
    private fun parseInline(markup: String): Parsed {
        val errors = ArrayList<String>()
        val toks = tokenize(markup, errors)

        // 1) markdown 标记配对（同标记、同长度；就近配对，**不跨行**：规格 §2.3）。
        // 行号按字符偏移预算一次，配对时只比两个标记所在行。
        val lineOf = IntArray(markup.length + 1)
        var ln = 0
        for (idx in markup.indices) {
            lineOf[idx] = ln
            if (markup[idx] == '\n') ln++
        }
        lineOf[markup.length] = ln
        val openers = ArrayList<Int>()
        val closeOf = HashMap<Int, Int>()
        val closerSet = HashSet<Int>()
        for (i in toks.indices) {
            val t = toks[i] as? Tok.Marker ?: continue
            if (t.canClose) {
                val k = openers.indexOfLast { j ->
                    val o = toks[j] as Tok.Marker
                    o.marker == t.marker && o.len == t.len && lineOf[o.at] == lineOf[t.at]
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
                        // 空元素没有结束标签（HTML 规范）：`</br>`、`</wbr>` 是无效写法。
                        // 不能悄悄吃掉——读者打了这几个字，确认后凭空消失（或凭空多一个换行）
                        // 都是「正文被偷偷改掉」，宁可拒绝并告诉他正确写法。
                        tag.closing && tag.name in VOID_TAGS ->
                            errors.add(
                                "「</${tag.name}>」不合法：${tag.name} 是没有内容的空元素、" +
                                    "不写结束标签" + if (tag.name == "br") "，断行直接写 `<br>`" else "",
                            )
                        tag.name == "br" -> sb.append('\n')
                        tag.name == "wbr" -> Unit
                        tag.name !in ALLOWED_TAGS -> errors.add("「<${tag.name}>」不是支持的行内标记")
                        !tag.closing -> openTags.add(tag to sb.length)
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
        return Parsed(sb.toString(), stripIds(nest(outerFirst(normalize(spans)))), errors.distinct())
    }

    /**
     * 同一段文字上「区间起止完全相同」的严格嵌套，按**外层在前**排。
     *
     * 解析是先收内层再收外层（`[`代码`](网址)` 的 `code` 先于 `a` 落地），而
     * [InlineSpans.ofLeaf] 与写回时都约定外层在前：不翻这一步，`show` 的回验会判成
     * 「结构变了」，链接里包着行内元素的行就白白退化成标签形式（`<a><code>…</code></a>`），
     * 读者在卡片里也看不到 `[文字](地址)` 这种写法。
     *
     * 只翻转起止完全相同的那几段：区间不同时先后由位置决定（[normalize] 已排好）。
     */
    private fun outerFirst(spans: List<StyleSpan>): List<StyleSpan> {
        if (spans.size < 2) return spans
        val out = ArrayList<StyleSpan>(spans.size)
        var i = 0
        while (i < spans.size) {
            var j = i + 1
            while (
                j < spans.size &&
                spans[j].start == spans[i].start &&
                spans[j].endExclusive == spans[i].endExclusive
            ) {
                j++
            }
            for (k in (i until j).reversed()) out.add(spans[k])
            i = j
        }
        return out
    }

    /**
     * 读者手写的 `id` 一律丢弃（规格 §5.2：`id` 只用于**原位保留**）。
     *
     * 原位保留的那条走 [restore]：它按原区间补回属性，`id` 从原文那一侧带回来。这里先统一剥掉，
     * 于是「拿不到原结构、不经过 restore」的路径（宿主给不出行内结构时）也不会把读者抄来的
     * 重复 `id` 写进正文，破坏锚点。
     */
    private fun stripIds(spans: List<StyleSpan>): List<StyleSpan> =
        spans.map { s ->
            if (s.sig.attrs.containsKey("id")) {
                s.copy(sig = s.sig.copy(attrs = s.sig.attrs - "id"))
            } else {
                s
            }
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
        val paired = pairedMarkers(markup, toks)
        val out = ArrayList<IntRange>(BlockMarkup.split(markup).markerRanges)
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

    /**
     * 输入区里**写了、但不会生效**的 markdown 标记（`*a` 里那个孤零零的 `*`）。
     *
     * [parse] 把没配上对的标记当**普通文字**落进正文，可卡片里它和真标记长得一样，
     * 读者分不出「这写的是斜体」还是「这就是一个星号」。所以输入框把它们染成另一种颜色
     * （规格 §3.6）：真标记淡化，字面标记保持正常墨色、另加一条虚下划线。
     */
    fun unpairedMarkerRanges(markup: String): List<IntRange> {
        if (markup.isEmpty()) return emptyList()
        val errors = ArrayList<String>()
        val toks = tokenize(markup, errors)
        val paired = pairedMarkers(markup, toks)
        val blockMarkers = BlockMarkup.split(markup).markerRanges
        val out = ArrayList<IntRange>()
        for ((i, t) in toks.withIndex()) {
            if (t !is Tok.Marker || i in paired) continue
            val r = t.at until t.at + t.len
            // 行首的块标记（`* 列表项`、`- `）不是「没生效的行内标记」：它已经生效了，只是不是行内。
            if (blockMarkers.any { it.first <= r.first && it.last >= r.last }) continue
            out.add(r)
        }
        return out
    }

    /**
     * 与 [parse] 同一套配对口径：同标记、同长度、就近配对，**不跨行**（规格 §2.3）。
     * 返回成对标记在 token 表里的下标。
     */
    private fun pairedMarkers(markup: String, toks: List<Tok>): Set<Int> {
        val lineOf = IntArray(markup.length + 1)
        var ln = 0
        for (idx in markup.indices) {
            lineOf[idx] = ln
            if (markup[idx] == '\n') ln++
        }
        lineOf[markup.length] = ln
        val openers = ArrayList<Int>()
        val paired = HashSet<Int>()
        for (i in toks.indices) {
            val t = toks[i] as? Tok.Marker ?: continue
            if (t.canClose) {
                val k = openers.indexOfLast { j ->
                    val o = toks[j] as Tok.Marker
                    o.marker == t.marker && o.len == t.len && lineOf[o.at] == lineOf[t.at]
                }
                if (k >= 0) {
                    paired.add(openers.removeAt(k))
                    paired.add(i)
                    continue
                }
            }
            if (t.canOpen) openers.add(i)
        }
        return paired
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

    /**
     * `style` 只留装饰类声明：影响版面的一律剔除（规格 §5.2）。
     *
     * 按**声明**切分而不是裸切 `;`：`font-family:"a;b"` 里的分号属于值，切开会落库半截非法 CSS
     * （`font-family:"a`）。引号内、`url(...)` 的括号内都不切。
     */
    internal fun filterStyle(css: String): String {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote = ' '
        var depth = 0
        fun flush() {
            val raw = cur.toString()
            cur.clear()
            val i = raw.indexOf(':')
            if (i <= 0) return
            val name = raw.substring(0, i).trim().lowercase()
            val value = raw.substring(i + 1).trim()
            if (name.isEmpty() || value.isEmpty()) return
            if (STYLE_DROP.any { name == it || name.startsWith("$it-") }) return
            if (STYLE_KEEP.none { name == it || name.startsWith(it) }) return
            out.add("$name:$value")
        }
        for (c in css) {
            when {
                quote != ' ' -> {
                    cur.append(c)
                    if (c == quote) quote = ' '
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    cur.append(c)
                }
                c == '(' -> {
                    depth++
                    cur.append(c)
                }
                c == ')' -> {
                    if (depth > 0) depth--
                    cur.append(c)
                }
                c == ';' && depth == 0 -> flush()
                else -> cur.append(c)
            }
        }
        flush()
        return out.joinToString(";")
    }

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
        segments: List<Segment>,
        expected: List<Pair<Pair<Int, Int>, String>>,
    ): Boolean {
        if (parsed.text != joinSegments(segments)) return false
        if (parsed.segments.size != segments.size) return false
        for (i in segments.indices) {
            if (!sameBlock(parsed.segments[i].block, segments[i].block)) return false
        }
        val got = parsed.spans.map { (it.start to it.endExclusive) to it.sig.shapeKey }
        return got == expected
    }

    /**
     * 回验时块形态的等价口径：**普通段落**与「没有块标记」是同一件事。
     *
     * [BlockKind.Paragraph] 的标记就是空串（见 `BlockKind.marker`），写出来什么都没有；
     * [parse] 见到空标记自然只能记成 `null`。早先这里按 `!=` 逐字比，于是**任何普通段落**里的
     * 行内结构（代码、斜体、链接）都会判成「结构变了」，整段退化成 HTML 标签形式
     * （真书实测：`<a href><code>println()</code></a>` 在修改卡里显示成一串 `<a href=…>`）。
     */
    private fun sameBlock(parsed: BlockKind?, wanted: BlockKind?): Boolean =
        parsed == wanted ||
            (parsed == null && wanted == BlockKind.Paragraph) ||
            (parsed == BlockKind.Paragraph && wanted == null)

    private class Marker(val tag: String, val open: String, val close: String)

    /**
     * 这条行内元素在卡片里的写法：开标记 / 闭标记（`*` / `**` / `` ` `` / `[` + `](地址)`）。
     *
     * 审阅视图的**格式痕迹**用它：字一个没改、只删掉 / 加上了行内标记时，把标记本身排进版面
     * （见 `FormatTraces`）。与 [show] 同一套写法，读者在卡片里看到什么，痕迹就长什么样。
     *
     * 有语法糖的标签给糖；写不回去的（代码里带反引号、链接地址自带 `)`）与没有语法糖的标签
     * 退回标签形式 `<tag …>` / `</tag>`（`id` 不算形状，写出来时剥掉，与 [parse] 同一口径）。
     *
     * @param content 这段文字本身（`code` 带反引号时写不回语法糖，要退回标签形式）。
     */
    fun markerPair(sig: StyleSig, content: String): Pair<String, String> {
        val m = markerOf(sig, content)
        if (m != null) return m.open to m.close
        val clean = if ("id" in sig.attrs) sig.copy(attrs = sig.attrs - "id") else sig
        return tagMarkup(clean, closing = false) to tagMarkup(clean, closing = true)
    }

    private fun markerOf(sig: StyleSig, content: String): Marker? = when (sig.tag) {
        "em" -> Marker("em", "*", "*")
        "strong" -> Marker("strong", "**", "**")
        "code" -> if (content.contains('`') || content.contains('\n')) null else Marker("code", "`", "`")
        "sup" -> Marker("sup", "^", "^")
        "sub" -> Marker("sub", "~", "~")
        "s" -> Marker("s", "~~", "~~")
        // 链接地址自带 `)` 时语法糖写不回去（`](${href})` 会在第一个 `)` 截断），直接走标签形式。
        "a" -> sig.attrs["href"].orEmpty().let { href ->
            if (href.contains(')')) null else Marker("a", "[", "]($href)")
        }
        else -> null
    }

    private fun tagMarkup(sig: StyleSig, closing: Boolean): String {
        if (closing) return "</${sig.tag}>"
        val sb = StringBuilder("<").append(sig.tag)
        for ((k, v) in sig.attrs.toSortedMap()) {
            sb.append(' ').append(k).append("=\"").append(escapeAttr(v)).append('"')
        }
        return sb.append('>').toString()
    }

    /**
     * 属性值写出：与 [readTag] 的解码严格对称（`&` 必须第一个换，否则会把后面换出来的实体再解一层）。
     * 只转这四个就够：`"` 是定界符，`&` / `<` / `>` 是解析侧会解码的实体。
     */
    private fun escapeAttr(v: String): String {
        if (v.none { it == '&' || it == '<' || it == '>' || it == '"' }) return v
        val sb = StringBuilder(v.length + 8)
        for (c in v) when (c) {
            '&' -> sb.append("&amp;")
            '<' -> sb.append("&lt;")
            '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;")
            else -> sb.append(c)
        }
        return sb.toString()
    }

    private fun renderSegments(
        segments: List<Segment>,
        spans: List<StyleSpan>,
        escapeMarkerChars: Boolean,
        forceTags: Boolean,
    ): String {
        val starts = segmentStarts(segments)
        val out = StringBuilder(joinSegments(segments).length + 16)
        for ((i, seg) in segments.withIndex()) {
            if (i > 0) out.append('\n')
            val from = starts[i]
            val to = from + seg.text.length
            val local = spans.filter { it.start >= from && it.endExclusive <= to }
                .map { StyleSpan(it.start - from, it.endExclusive - from, it.sig) }
            if (seg.block == BlockKind.Pre) {
                // 围栏长度比内容里最长的行首反引号还长一位，内容随便写也不会把围栏提前关上。
                val fence = "`".repeat(fenceFor(seg.text))
                out.append(fence).append('\n').append(seg.text).append('\n').append(fence)
                continue
            }
            // 段内换行（`<br>`）写成硬断行：行尾一个 `\`，解析时并回同一块。
            val body = renderText(seg.text, local, escapeMarkerChars, forceTags)
                .replace("\n", "\\\n")
            val marker = seg.block?.marker
            out.append(
                when {
                    marker != null -> marker + body
                    else -> escapeLineStart(body)
                },
            )
        }
        return out.toString()
    }

    /** 代码块围栏长度：内容里任何一行开头的反引号串都不能把围栏提前关上。 */
    private fun fenceFor(text: String): Int {
        var max = 0
        for (line in text.split('\n')) {
            var n = 0
            while (n < line.length && line[n] == '`') n++
            if (n > max) max = n
        }
        return maxOf(3, max + 1)
    }

    /**
     * 没有块标记的文字，行首若长得像块标记（`# `、`> `、`- `、`1. `、```` ``` ````）就转义，
     * 免得读者确认后那段字被当成标题 / 列表 / 代码块。
     *
     * 围栏要逐个反引号转义：只转第一个的话，剩下的 ` `` ` 会自己配成一段行内代码。
     */
    private fun escapeLineStart(body: String): String {
        if (body.isEmpty()) return body
        val lineEnd = body.indexOf('\n').let { if (it < 0) body.length else it }
        val line = body.substring(0, lineEnd)
        if (!BlockMarkup.startsLikeMarker(line)) return body
        val ticks = BlockMarkup.fenceOpen(line)
        if (ticks > 0) {
            val sb = StringBuilder(body.length + ticks)
            repeat(ticks) { sb.append('\\').append('`') }
            return sb.append(body, ticks, body.length).toString()
        }
        return "\\" + body
    }

    private fun renderText(
        text: String,
        spans: List<StyleSpan>,
        escapeMarkerChars: Boolean,
        forceTags: Boolean,
    ): String {
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
                    val m = if (forceTags) null else markerOf(s.sig, text.substring(s.start, s.endExclusive))
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
        // `(` `)` 自身不是语法（链接必须由 `[` 起头），整体转义时不加反斜杠：
        // `函数()` 不必写成 `函数\(\)`，读者看着干净。`]` 仍要转义——它会截断链接名里的括号配对。
        else -> if (escapeMarkerChars && c in "*~^[]") "\\$c" else c.toString()
    }
}
