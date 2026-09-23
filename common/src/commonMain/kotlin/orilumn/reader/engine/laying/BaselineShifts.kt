package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.VerticalAlign
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement

/**
 * P1-2: 行内基线偏移单源（pure JVM）。
 *
 * 把已级联 `vertical-align` 投影成叶文本坐标系的 [BaselineShift]，与叶文本同一
 * 归一化段构建（下标恒对齐）。只发非 BASELINE 段；纯基线叶回空表（绘制零开销）。
 *
 * 偏移语义（em，相对叶基底字号；正 = 上移）：
 *  - SUB −0.20em / SUPER +0.33em（浏览器上下标经验值）；
 *  - MIDDLE +0.15em（x-height 一半近似）；
 *  - TOP +0.30em / BOTTOM −0.30em（行盒顶/底近似；完整行内布局是后续工作）。
 * 嵌套时内层覆盖外层（CSS 基线上下文组成在此简化为覆盖，EPUB 实测罕见嵌套）。
 */
data class BaselineShift(
    val start: Int,
    val endExclusive: Int,
    /** 基线偏移（em，相对叶基底字号；正 = 上移）。 */
    val shiftEm: Float,
)

fun VerticalAlign.shiftEm(): Float = when (this) {
    VerticalAlign.BASELINE -> 0f
    VerticalAlign.SUB -> -0.20f
    VerticalAlign.SUPER -> 0.33f
    VerticalAlign.MIDDLE -> 0.15f
    VerticalAlign.TOP -> 0.30f
    VerticalAlign.BOTTOM -> -0.30f
}

/**
 * 收集 [root] 叶内的基线偏移区间。
 *
 * @param rootWs 根块回退 `white-space`（匿名叶等表外节点的归一化依据；默认 NORMAL）。
 */
fun collectBaselineShifts(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    /** P3-c 生成内容查找（空即无，旧路径）。 */
    genOf: GenOf = EmptyGen,
    /** P3-c 样式回退（变换/引号依据；默认读 [styles] 表）。 */
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): List<BaselineShift> {
    if (root.tag == "table" || root.tag == "img") return emptyList()
    fun shiftOf(node: MarkupElement): Float? =
        styles[node]?.verticalAlign?.let { if (it == VerticalAlign.BASELINE) null else it.shiftEm() }
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    if (root.isText) return emptyList() // 合成匿名叶无子树、无偏移
    val segs = styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, styleOf ?: { styles[it] }).segments
    // 逐节点偏移一次算好（内层覆盖外层），段折叠时直接取用。
    val shiftByNode = HashMap<MarkupElement, Float?>()
    fun fill(node: MarkupElement, inherited: Float?) {
        for (c in node.children) {
            when {
                isExcluded(c) -> Unit
                c.isText -> shiftByNode[c] = shiftOf(c) ?: inherited
                c.tag == "br" -> Unit
                isBlock(c) -> Unit
                c.tag == "img" -> Unit
                else -> {
                    val s = shiftOf(c) ?: inherited
                    shiftByNode[c] = s
                    fill(c, s)
                }
            }
        }
    }
    val rootShift = shiftOf(root)
    shiftByNode[root] = rootShift
    fill(root, rootShift)
    val out = ArrayList<BaselineShift>()
    var pos = 0
    fun emit(seg: StyledSegment, shift: Float?) {
        val n = seg.text.length
        if (n <= 0) return
        if (shift != null && shift != 0f) {
            val last = out.lastOrNull()
            if (last != null && last.shiftEm == shift && last.endExclusive == pos) {
                out[out.lastIndex] = last.copy(endExclusive = pos + n)
            } else {
                out.add(BaselineShift(pos, pos + n, shift))
            }
        }
        pos += n
    }
    for (seg in segs) {
        val node = seg.node
        // 标记段（br/img）永不偏移。P3-c: 生成/引号段走伪元素位移。
        val shift = if (node == null || !node.isText) {
            seg.genStyle?.verticalAlign?.let { if (it == VerticalAlign.BASELINE) null else it.shiftEm() }
        } else shiftByNode[node]
        emit(seg, shift)
    }
    return out
}
