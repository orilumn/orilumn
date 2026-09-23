package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.ContentItem
import orilumn.reader.engine.css.DEFAULT_QUOTES
import orilumn.reader.engine.css.TextTransform
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.layout.ListMarkers

/**
 * P3-c: 生成内容求值（`::before/::after` + `content` + `counter-*` + `quotes`，pure JVM）。
 *
 * 字符流级改造：产出的字符串进叶文本吸收（[styledSegments] 拼接，`globalCharStarts`
 * 同步 + `LAYOUT_VERSION` bump），绝不做纯绘制覆盖（否则列表编号/脚注定位错位）。
 *
 * 两档机制（章节全局开关，性质不同）：
 * - **phase-1 文档序求值**（[resolveStrings]）：`content` 字符串/attr()/counter() 与
 *   引号关键字的完整求值。计数器值依赖文档序（reset/increment 作用域），必须整树一遍；
 *   章节含生成 CSS（[needsPhase]）时重/轻两路各跑一次（同输入同输出，双路一致）。
 * - **行内 `q` 引号**（无生成 CSS 的章节）：`styledSegments` 内按祖先 `q` 计数就地配对，
 *   零级联开销、懒路径友好；`quotes` 属性经样式回退读取，无则 UA 默认。
 *
 * 范围与近似（文档即契约）：
 * - `counter-set` 不支持（reset/increment 覆盖常见书）；`display: list-item` 的非 `li`
 *   标签不触发 `list-item` 自动行为（只认 `li`/`ol`/`ul` 标签）。
 * - `display: none` 元素仍累计计数器（CSS 2.1 §12.4 同式），但不产出字符串。
 * - 容器 stray 文本（`flowChildren` 合成匿名叶）不进生成/变换消费（重轻两路同跳，双路一致；
 *   罕见 `div.note::before` 跨块容器场景）。
 * - `small-caps` 缩放因子恒 0.8（浏览器按字重取，近似）；`capitalize` 按空白分词、
 *   首个有大小写字符大写（titlecase 近似为 uppercase）。
 */
object GeneratedContent {

    /** 一段生成文本（已求值＋已做原发元素 `text-transform`）与伪元素计算样式（run 着色/字体依据）。 */
    data class GenText(val text: String, val style: ComputedStyle)

    /** 一元素的两端生成内容（null 成员 = 无）。 */
    data class GenPair(val before: GenText?, val after: GenText?)

    /**
     * 章节是否需要 phase-1（解析后样式表含伪元素 content 声明或计数器声明）。
     * fail-open 方向：误伤只多跑一次求值（无匹配即空），漏判不存在（消费点同源）。
     */
    fun needsPhase(sheets: List<orilumn.reader.engine.css.StyleSheet>): Boolean = sheets.any { sheet ->
        sheet.rules.any { rule ->
            rule.declarations.any { d -> d.property == "counter-reset" || d.property == "counter-increment" } ||
                (rule.declarations.any { d -> d.property == "content" } &&
                    rule.selectors.any { s -> s.contains(":before") || s.contains(":after") })
        }
    }

    /** 文本版门控（调用方只有原文时用；与解析版同向 fail-open）。 */
    fun needsPhaseTexts(cssTexts: List<String>): Boolean = cssTexts.any { t ->
        t.contains("counter-") ||
            (CONTENT_RE.containsMatchIn(t) &&
                (t.contains("::before") || t.contains("::after") || t.contains(":before") || t.contains(":after")))
    }

    private val CONTENT_RE = Regex("""content\s*:""")

    /**
     * 文档序一遍求值：计数器作用域（reset 压帧/increment 累加）＋引号深度＋伪元素 content，
     * 只产出字符串（排版无关，跨字号/主题恒有效，可进磁盘无关的结构缓存）。
     *
     * @param styleOf 任一元素的计算样式（重路径喂整章表，轻路径喂懒级联；null 即无声明）。
     * @param pseudoOf 伪元素计算样式（`StyleComputer.pseudoStyle` 同式；null 即无伪规则，
     *   该端无字符串）。
     * @param isHidden `display: none` 判定（隐藏元素累计计数器但不产出字符串）。
     * @return 每非隐藏元素的两端字符串（含双空条目——吸收侧以"有条目"判定跳过行内 `q` 补丁）。
     */
    fun resolveStrings(
        root: MarkupElement,
        styleOf: (MarkupElement) -> ComputedStyle?,
        pseudoOf: (MarkupElement, String) -> ComputedStyle?,
        isHidden: (MarkupElement) -> Boolean,
    ): Map<MarkupElement, Pair<String?, String?>> {
        val out = HashMap<MarkupElement, Pair<String?, String?>>()
        val frames = ArrayDeque<MutableMap<String, Int>>()
        frames.addLast(HashMap())
        var quoteDepth = 0
        fun current(name: String): Int {
            for (i in frames.indices.reversed()) {
                frames[i][name]?.let { return it }
            }
            return 0
        }
        fun stack(name: String): List<Int> {
            val vals = ArrayList<Int>()
            for (f in frames) f[name]?.let { vals.add(it) }
            return vals
        }
        fun walk(el: MarkupElement) {
            val st = styleOf(el)
            val hidden = isHidden(el)
            // reset 先压帧（同名外层被遮蔽），再 increment（作用域内最近；缺失即顶帧建 0 再加）。
            // 隐藏元素照累计（CSS 2.1 §12.4），字符串不产出。
            var pushed = false
            val reset = st?.counterReset
            if (reset != null && reset.isNotEmpty()) {
                val frame = HashMap<String, Int>()
                for ((k, v) in reset) frame[k] = v
                frames.addLast(frame)
                pushed = true
            }
            val top = frames.last()
            fun bump(name: String, by: Int) {
                for (i in frames.indices.reversed()) {
                    if (frames[i].containsKey(name)) {
                        frames[i][name] = frames[i][name]!! + by
                        return
                    }
                }
                top[name] = by // 未声明即 0 起加（顶帧建）
            }
            val inc = st?.counterIncrement
            if (inc != null) {
                for ((k, v) in inc) bump(k, v)
            }
            // ol/ul 自动圈定 list-item 作用域；li 自动 +1（显式声明优先）。
            if (el.tag == "ol" || el.tag == "ul") {
                if (reset == null || !reset.containsKey("list-item")) {
                    if (!pushed) {
                        frames.addLast(HashMap())
                        pushed = true
                    }
                    frames.last()["list-item"] = 0
                }
            }
            if (el.tag == "li") {
                if (inc == null || !inc.containsKey("list-item")) bump("list-item", 1)
            }
            if (!hidden) {
                val before = evalPseudo(el, "before", pseudoOf, ::current, ::stack, { quoteDepth }, { quoteDepth = it })
                for (c in el.children) walk(c)
                val after = evalPseudo(el, "after", pseudoOf, ::current, ::stack, { quoteDepth }, { quoteDepth = it })
                quoteDepth = quoteDepth.coerceAtLeast(0)
                // 双空也记条目（吸收侧以"有条目"跳过行内 q 补丁，避免双引号）。
                out[el] = before to after
            } else {
                // 隐藏子树：计数器照累计（上面 reset/increment 已做），字符串不产出。
                for (c in el.children) walk(c)
            }
            if (pushed) frames.removeLast()
        }
        walk(root)
        return out
    }

    /**
     * 把 phase-1 字符串拼成吸收侧查找：伪元素样式按需懒解（同 prepare 内新鲜级联，
     * 只 touch 有字符串的元素； styles 永不进结构缓存，无跨字号/主题过期）。
     *
     * @param pseudoOf 伪元素计算样式（null 即无伪规则；有字符串而无样式时该端丢弃）。
     */
    fun genOf(
        strings: Map<MarkupElement, Pair<String?, String?>>,
        pseudoOf: (MarkupElement, String) -> ComputedStyle?,
    ): GenOf {
        // 全空即等价无（行内 q 补丁照常生效；phase 门控本就只在有声明时开）。
        if (strings.isEmpty() || strings.values.all { it.first == null && it.second == null }) return EmptyGen
        val styleCache = HashMap<Pair<MarkupElement, String>, ComputedStyle?>()
        fun styledFor(el: MarkupElement, pseudo: String): ComputedStyle? =
            styleCache.getOrPut(el to pseudo) { pseudoOf(el, pseudo) }
        val lookup: GenOf = gen@{ el ->
            val s = strings[el] ?: return@gen null
            if (s.first == null && s.second == null) GenPair(null, null)
            else GenPair(
                s.first?.let { t -> styledFor(el, "before")?.let { GenText(t, it) } },
                s.second?.let { t -> styledFor(el, "after")?.let { GenText(t, it) } },
            )
        }
        return lookup
    }

    private fun evalPseudo(
        el: MarkupElement,
        pseudo: String,
        pseudoOf: (MarkupElement, String) -> ComputedStyle?,
        current: (String) -> Int,
        stack: (String) -> List<Int>,
        getDepth: () -> Int,
        setDepth: (Int) -> Unit,
    ): String? {
        val pstyle = pseudoOf(el, pseudo) ?: return null
        val tokens = pstyle.content ?: return null
        if (tokens.isEmpty()) return null
        val sb = StringBuilder()
        var depth = getDepth()
        val quotes = pstyle.quotes?.takeIf { it.size >= 2 } ?: DEFAULT_QUOTES
        for (tok in tokens) {
            when (tok) {
                is ContentItem.Str -> sb.append(tok.text)
                is ContentItem.Attr -> sb.append(el.attrs[tok.name] ?: "")
                is ContentItem.Counter -> sb.append(formatCounter(current(tok.name), tok.style))
                is ContentItem.Counters -> sb.append(stack(tok.name).joinToString(tok.sep) { formatCounter(it, tok.style) })
                ContentItem.OpenQuote -> {
                    sb.append(openAt(depth, quotes))
                    depth++
                }
                ContentItem.CloseQuote -> {
                    depth = (depth - 1).coerceAtLeast(0)
                    sb.append(closeAt(depth, quotes))
                }
                ContentItem.NoOpenQuote -> depth++
                ContentItem.NoCloseQuote -> depth = (depth - 1).coerceAtLeast(0)
            }
        }
        setDepth(depth.coerceAtLeast(0))
        if (sb.isEmpty()) return null
        val transformed = applyTransform(sb.toString(), pstyle.textTransform)
        return transformed.takeIf { it.isNotEmpty() }
    }

    /** depth 层开引号（超对数复用最后一对，CSS 2.1 §12.3 同式）。 */
    fun openAt(depth: Int, quotes: List<String>): String {
        val pairs = quotes.size / 2
        if (pairs <= 0) return ""
        return quotes[(depth.coerceAtLeast(0).coerceAtMost(pairs - 1)) * 2]
    }

    /** depth 层闭引号。 */
    fun closeAt(depth: Int, quotes: List<String>): String {
        val pairs = quotes.size / 2
        if (pairs <= 0) return ""
        return quotes[(depth.coerceAtLeast(0).coerceAtMost(pairs - 1)) * 2 + 1]
    }

    /** 计数器值按式样格式化（`none` 即空；未知式样回十进制）。 */
    fun formatCounter(value: Int, style: String): String {
        val kind = when (style.trim().lowercase()) {
            "decimal-leading-zero" -> ListMarkers.Kind.DECIMAL_LEADING_ZERO
            "lower-roman" -> ListMarkers.Kind.LOWER_ROMAN
            "upper-roman" -> ListMarkers.Kind.UPPER_ROMAN
            "lower-alpha", "lower-latin" -> ListMarkers.Kind.LOWER_ALPHA
            "upper-alpha", "upper-latin" -> ListMarkers.Kind.UPPER_ALPHA
            "none" -> return ""
            else -> ListMarkers.Kind.DECIMAL
        }
        return ListMarkers.markerText(kind, value)
    }

    /**
     * P3-c `text-transform` 消费（字符数不变 1:1；大小写映射与 locale 无关）。
     * 归一化之后调用（词界干净，capitalize 按空白分词）。逐字映射（`ß` 不展开成 `SS`，
     * 字符流下标恒对齐——路线图"大小写 1:1"即此保证）。
     */
    fun applyTransform(text: String, tt: TextTransform): String {
        if (text.isEmpty()) return text
        return when (tt) {
            TextTransform.NONE -> text
            TextTransform.UPPERCASE -> buildString(text.length) {
                for (c in text) append(c.uppercaseChar())
            }
            TextTransform.LOWERCASE -> buildString(text.length) {
                for (c in text) append(c.lowercaseChar())
            }
            TextTransform.CAPITALIZE -> capitalizeWords(text)
        }
    }

    /** `small-caps` 字形合成缩放因子（浏览器按字取，0.8 近似）。 */
    const val SMALL_CAPS_SCALE = 0.8f

    /**
     * P3-c `small-caps` 求值：恒大写（1:1 逐字）＋原小写掩码（true 即小字号 run）。
     * 调用方按掩码切段（[StyledSegment.smallCaps]），字体收集单列成段。
     */
    fun applySmallCaps(normalized: String): Pair<String, BooleanArray> {
        val sb = StringBuilder(normalized.length)
        val mask = BooleanArray(normalized.length)
        for (i in normalized.indices) {
            val c = normalized[i]
            sb.append(c.uppercaseChar())
            mask[i] = c.isLowerCase()
        }
        return sb.toString() to mask
    }

    /** 每词首个有大小写字符大写，其余不动（CSS capitalize 同式）。 */
    private fun capitalizeWords(text: String): String {
        var dirty = false
        for (c in text) {
            if (c.isLowerCase()) {
                dirty = true
                break
            }
        }
        if (!dirty) return text
        val sb = StringBuilder(text.length)
        var boundary = true
        for (c in text) {
            if (c.isWhitespace()) {
                boundary = true
                sb.append(c)
            } else if (boundary && (c.isLowerCase() || c.isUpperCase())) {
                sb.append(c.uppercaseChar())
                boundary = false
            } else {
                sb.append(c)
                // 词内首个有大小写字符之后即非界（数字/符号不占位，直接过）。
                if (c.isLetter()) boundary = false
            }
        }
        return sb.toString()
    }
}

/** 生成内容查找（元素 → 两端；null 条目/空查找 = 无生成内容旧路径）。 */
typealias GenOf = (MarkupElement) -> GeneratedContent.GenPair?

/** 空查找（无生成 CSS 章节与全部存量调用方的零行为默认值）。 */
val EmptyGen: GenOf = { null }

/** 祖先链（根→父，nearest last；伪元素级联与整章 compute 同序）。 */
fun ancestorsOf(el: MarkupElement): List<MarkupElement> {
    val out = ArrayList<MarkupElement>()
    var n = el.parent
    while (n != null) {
        out.add(n)
        n = n.parent
    }
    out.reverse()
    return out
}
