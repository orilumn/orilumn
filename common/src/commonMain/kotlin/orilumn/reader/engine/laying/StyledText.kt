package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FontVariant
import orilumn.reader.engine.css.TextTransform
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.css.WhiteSpaceNormalize
import orilumn.reader.engine.html.MarkupElement

/**
 * P1-2: 样式化叶文本单源（pure JVM）。
 *
 * 盒流 `absorbedText` / Android `ParagraphShapes.emitPlainText` / 着色·字体·基线 run
 * 收集 / 轻路径字符计数必须从同一归一化字符串取同一字符，否则 run 下标与字符错位、
 * 重轻两路 `globalCharStarts` 漂移（路线图 §6 inv.1）。
 *
 * 语义：
 *  - 每个文本节点按其继承的 `white-space` 归一（[WhiteSpaceNormalize.normalizeNode]）；
 *  - 归一后按节点 `text-transform` 变大小写（P3-c；1:1 字符数不变），`small-caps`
 *    额外 uppercase 并把原小写 run 切成小字号段（[StyledSegment.smallCaps]）；
 *  - 生成内容（P3-c [genOf]）：`::before/::after` 字符串按文档序拼进首尾
 *    （[GeneratedContent.resolveStrings] 预求值，字符流同步）；无生成 CSS 章节的裸 `q`
 *    就地按祖先深度配引号（零级联开销）；
 *  - 叶级收尾按叶块的 `white-space` 去首尾（[WhiteSpaceNormalize.finishLeaf]），
 *    首尾裁剪量同步扣减首尾文本节点的贡献长度（run 坐标系恒对齐）。
 *  - `<br>` → `\n`、`img` → U+FFFC（任何模式不断，调用方分段语义不变）；
 *    块/隐藏子树无贡献。
 *
 * @property segments 归一化后的有序段（文本节点段 `node != null`；`<br>`/`<img>` 标记段
 *   `node == null`）。段文本长度即该段在叶文本中的字符数。
 * @property text 叶文本（`segments` 拼接，已做叶级收尾）。
 */
class StyledSegments(val segments: List<StyledSegment>) {
    val text: String = segments.joinToString("") { it.text }

    /** 每个文本节点的归一化后贡献长度（run 收集的下标依据；零长度节点不产出区间）。 */
    fun lengths(): Map<MarkupElement, Int> {
        val out = HashMap<MarkupElement, Int>()
        for (s in segments) {
            val n = s.node
            if (n == null || !n.isText) continue
            out[n] = (out[n] ?: 0) + s.text.length
        }
        return out
    }
}

/**
 * 一段归一化文本（P3-c 扩展字段全默认即旧行为）。
 *
 * @property genStyle 生成/引号段的伪元素（或 `q`）计算样式：run 收集优先用它
 *   取色/字体/位移（`::before` 继承原发元素，浏览器同式）；作者文本段恒 null。
 * @property smallCaps `small-caps` 原小写 run（0.8 字号，字体收集单列成段）。
 */
class StyledSegment(
    val node: MarkupElement?,
    val text: String,
    val genStyle: ComputedStyle? = null,
    val smallCaps: Boolean = false,
)

/**
 * 构建 [root] 叶内的样式化段。
 *
 * @param wsOf 任一节点（含文本节点）的有效 `white-space`；缺省回退链由调用方决定
 *   （重路径：整章样式表＋父级回退；轻路径：懒级联；匿名叶：容器样式）。
 * @param leafWs 叶块自身的 `white-space`（叶级收尾依据）。
 * @param genOf P3-c 生成内容查找（`::before/::after` 预求值；空即无，旧路径）。
 * @param styleOf P3-c 样式回退（`text-transform`/`small-caps`/`quotes`/`q` 着色依据；
 *   null 即全按旧行为：无变换、默认引号）。
 *
 * 标记段（`<br>`/`<img>`）同样携带其节点：着色沿用旧语义（img 槽位带其继承色，
 * br 永空），字体/基线恒空。
 */
fun styledSegments(
    root: MarkupElement,
    wsOf: (MarkupElement) -> WhiteSpace,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    leafWs: WhiteSpace,
    genOf: GenOf = EmptyGen,
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): StyledSegments {
    if (root.tag == "table" || root.tag == "img") return StyledSegments(emptyList())
    if (root.isText) {
        val t0 = WhiteSpaceNormalize.normalizeNode(root.text, wsOf(root))
        if (t0.isEmpty()) return StyledSegments(emptyList())
        val out = ArrayList<StyledSegment>()
        addTextSeg(out, root, t0, styleOf)
        return StyledSegments(out)
    }
    val segs = ArrayList<StyledSegment>()
    val rootQuotes = styleOf?.invoke(root)?.quotes ?: orilumn.reader.engine.css.DEFAULT_QUOTES
    fun walk(node: MarkupElement, qDepth: Int, quotes: List<String>) {
        // P3-c: ::before（phase-1 预求值字符串；空即无）。
        genOf(node)?.before?.let { g ->
            if (g.text.isNotEmpty()) segs.add(StyledSegment(node, g.text, genStyle = g.style))
        }
        for (c in node.children) {
            when {
                isExcluded(c) -> Unit
                c.isText -> {
                    val t0 = WhiteSpaceNormalize.normalizeNode(c.text, wsOf(c))
                    if (t0.isNotEmpty()) addTextSeg(segs, c, t0, styleOf)
                }
                c.tag == "br" -> segs.add(StyledSegment(c, "\n"))
                isBlock(c) -> Unit
                c.tag == "img" -> segs.add(StyledSegment(c, "￼"))
                // P3-c: 行内裸 q（无生成 CSS 章节的零开销路径；phase 开时 gen 条目已含引号）。
                c.tag == "q" && genOf(c) == null -> {
                    val qStyle = styleOf?.invoke(c)
                    val qQuotes = qStyle?.quotes ?: quotes
                    val open = GeneratedContent.openAt(qDepth, qQuotes)
                    if (open.isNotEmpty()) segs.add(StyledSegment(c, open, genStyle = qStyle))
                    walk(c, qDepth + 1, qQuotes)
                    val close = GeneratedContent.closeAt(qDepth, qQuotes)
                    if (close.isNotEmpty()) segs.add(StyledSegment(c, close, genStyle = qStyle))
                }
                else -> walk(c, qDepth, quotes)
            }
        }
        // P3-c: ::after。
        genOf(node)?.after?.let { g ->
            if (g.text.isNotEmpty()) segs.add(StyledSegment(node, g.text, genStyle = g.style))
        }
    }
    walk(root, 0, rootQuotes)
    mergeBoundarySpaces(segs, leafWs)
    return finishSegments(segs, leafWs)
}

/** 文本节点段：归一后做 `text-transform`（1:1），`small-caps` 按原大小写切小字号段。 */
private fun addTextSeg(
    segs: MutableList<StyledSegment>,
    node: MarkupElement,
    normalized: String,
    styleOf: ((MarkupElement) -> ComputedStyle?)?,
) {
    val st = styleOf?.invoke(node)
    val tt = st?.textTransform ?: TextTransform.NONE
    if ((st?.fontVariant ?: FontVariant.NORMAL) != FontVariant.SMALL_CAPS) {
        val t = if (tt == TextTransform.NONE) normalized else GeneratedContent.applyTransform(normalized, tt)
        if (t.isNotEmpty()) segs.add(StyledSegment(node, t))
        return
    }
    // small-caps 恒大写（1:1 逐字），原小写 run 标小字号。
    val (upper, mask) = GeneratedContent.applySmallCaps(normalized)
    if (upper.isEmpty()) return
    var i = 0
    while (i < upper.length) {
        var j = i + 1
        while (j < upper.length && mask[j] == mask[i]) j++
        segs.add(StyledSegment(node, upper.substring(i, j), smallCaps = mask[i]))
        i = j
    }
}

/**
 * 段间边界空格合并（CSS 空白折叠跨行内元素边界生效）：`a⬛`+`⬛b` → `a⬛b`。
 * 仅 NORMAL/NOWRAP/PRE_LINE；PRE 系原样。
 * 另按 CSS 丢弃 `<br>` 强制换行相邻空格（旧口径保留行首缩进，此处与浏览器对齐；
 * 字符流变更由 LAYOUT_VERSION 覆盖）。
 */
fun mergeBoundarySpaces(segs: ArrayList<StyledSegment>, leafWs: WhiteSpace) {
    if (leafWs != WhiteSpace.NORMAL && leafWs != WhiteSpace.NOWRAP && leafWs != WhiteSpace.PRE_LINE) return
    fun isTextSeg(s: StyledSegment) = s.node?.isText == true
    // 1) 段间双空格合并。
    var prevEndsSpace = false
    var i = 0
    while (i < segs.size) {
        val s = segs[i]
        if (isTextSeg(s) && s.text.startsWith(' ') && prevEndsSpace) {
            val stripped = s.text.drop(1)
            if (stripped.isEmpty()) {
                segs.removeAt(i)
                continue
            }
            segs[i] = StyledSegment(s.node, stripped)
        }
        prevEndsSpace = segs[i].text.endsWith(' ')
        i++
    }
    // 2) `<br>` 相邻空格剥离（NORMAL/NOWRAP 的换行只来自 br 标记段；倒序处理，
    // 使 removeAt 不影响待处理下标）。
    if (leafWs == WhiteSpace.NORMAL || leafWs == WhiteSpace.NOWRAP) {
        val markers = ArrayList<Int>()
        for (m in segs.indices) if (segs[m].node?.tag == "br") markers.add(m)
        for (m in markers.asReversed()) {
            if (m >= segs.size || segs[m].node?.tag != "br") continue
            // 前空格：marker 前一位若是文本段，剥其尾空格。
            val p = m - 1
            var markerAt = m
            if (p >= 0 && isTextSeg(segs[p]) && segs[p].text.endsWith(' ')) {
                val t = segs[p].text.dropLast(1)
                if (t.isEmpty()) {
                    segs.removeAt(p)
                    markerAt = m - 1
                } else {
                    segs[p] = StyledSegment(segs[p].node, t)
                }
            }
            // 后空格：marker 后一位若是文本段，剥其首空格。
            val q = markerAt + 1
            if (q < segs.size && isTextSeg(segs[q]) && segs[q].text.startsWith(' ')) {
                val t = segs[q].text.drop(1)
                if (t.isEmpty()) segs.removeAt(q) else segs[q] = StyledSegment(segs[q].node, t)
            }
        }
    } else {
        // PRE_LINE: 段内换行相邻空格剥离（换行只保留本身）。
        for (k in segs.indices) {
            val s = segs[k]
            if (!isTextSeg(s)) continue
            var t = s.text
            if (t.indexOf(" \n") >= 0) t = t.replace(" \n", "\n")
            if (t.indexOf("\n ") >= 0) t = t.replace("\n ", "\n")
            if (t != s.text) segs[k] = StyledSegment(s.node, t)
        }
    }
}

/**
 * 叶级收尾：NORMAL 系去首尾空格（块边界空白按 CSS 丢弃），PRE 系原样。
 * 首尾裁剪量只扣减文本节点段（`\n`/U+FFFC 不在裁剪字符集里，不会误伤标记段）。
 */
fun finishSegments(segs: List<StyledSegment>, leafWs: WhiteSpace): StyledSegments {
    val full = segs.joinToString("") { it.text }
    val trimmed = WhiteSpaceNormalize.finishLeaf(full, leafWs)
    if (trimmed.length == full.length) return StyledSegments(segs)
    var headCut = full.length - trimmed.length
    var tailCut = 0
    if (trimmed.isNotEmpty()) {
        headCut = full.indexOf(trimmed)
        if (headCut < 0) headCut = 0
        tailCut = full.length - headCut - trimmed.length
    }
    if (headCut <= 0 && tailCut <= 0) return StyledSegments(segs)
    val headEaten = IntArray(segs.size)
    val tailEaten = IntArray(segs.size)
    fun isTextSeg(k: Int) = segs[k].node?.isText == true
    var rem = headCut
    for (k in segs.indices) {
        if (rem <= 0) break
        if (isTextSeg(k)) {
            val eat = minOf(rem, segs[k].text.length)
            headEaten[k] = eat
            rem -= eat
        }
    }
    rem = tailCut
    for (k in segs.indices.reversed()) {
        if (rem <= 0) break
        if (isTextSeg(k)) {
            val eat = minOf(rem, segs[k].text.length - headEaten[k])
            tailEaten[k] = eat
            rem -= eat
        }
    }
    val out = ArrayList<StyledSegment>(segs.size)
    for (k in segs.indices) {
        val s = segs[k]
        // 标记段（br/img）不在裁剪字符集里，原样保留。
        if (!isTextSeg(k)) {
            out.add(s)
            continue
        }
        val keep = s.text.drop(headEaten[k]).dropLast(tailEaten[k])
        if (keep.isNotEmpty()) out.add(StyledSegment(s.node, keep))
    }
    return StyledSegments(out)
}

/**
 * 匿名 inline-run 整段归一（容器 stray 文本合成 `#text` 叶）：整段按容器 `white-space`
 * 一次归一＋收尾。重路径塑形与轻路径计数共用，恒一致（段内多节点混合 white-space
 * 按容器近似，两路同近似）。
 */
fun normalizeAnonymousRun(raw: String, ws: WhiteSpace): String =
    WhiteSpaceNormalize.finishLeaf(WhiteSpaceNormalize.normalizeNode(raw, ws), ws)
