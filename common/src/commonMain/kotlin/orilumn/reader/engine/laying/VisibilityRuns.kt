package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement

/**
 * `visibility: hidden` 覆盖区间单源（pure JVM，叶文本坐标系）。
 *
 * ## 浏览器语义（本表的依据）
 *
 * `visibility` 是**继承**属性、且**保留占位**（与 `display:none` 去盒子的语义相反）：隐藏元素的
 * 子树不落墨，但字符仍占宽、行照排、块照占位。关键两点：
 *  - 后代可以**翻回显形**（`visibility: visible` 写在隐藏祖先的子树里，浏览器会把它画出来）；
 *  - 因此「隐藏」不是一个整叶布尔，而是**逐字符区间**——与着色/字体 run 同构。
 *
 * 本函数即该区间的唯一产出点：与叶文本**同一归一化段**（[styledSegments]，下标恒对齐），
 * 重/轻两路共用（量画同源）。
 *
 * ## 与 `display:none` 的分工
 *
 * [isExcluded]（`display:none`）子树**根本不进段表**（不占宽、不占位）；本表只覆盖**留在段表里**
 * 的字符，由绘制侧「跳过落墨、保留占位」。两者不可复用同一谓词。
 *
 * @param root 叶子块元素（文本叶；合成匿名 `#text` 亦可）。
 * @param styles 该叶文本构建所用的同一份样式表（合成匿名叶不在表中时回父级）。
 * @param baseHidden 叶块自身的 `visibilityHidden`（= 根元素计算值；匿名叶取容器）。
 * @param isExcluded / @param isBlock 跳过规则（须与塑形侧**逐分支同构**，否则下标错位）。
 * @param rootWs 根块回退 `white-space`（匿名叶等表外节点的归一化依据）。
 * @param precomputed 预计算段表（同参复用；null 即现算）。
 * @param styleOf 样式回退（默认读 [styles] 表；轻路径可喂懒级联）。
 * @return 隐藏区间（叶文本坐标系，按文档序、相邻已合并）；空表 = 全叶可见。
 */
fun collectVisibilityHiddenRuns(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    baseHidden: Boolean,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    genOf: GenOf = EmptyGen,
    precomputed: List<StyledSegment>? = null,
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): List<IntRange> {
    if (root.tag == "table" || root.tag == "img") return emptyList()
    val resolve: (MarkupElement) -> ComputedStyle? = styleOf ?: { styles[it] }
    // 合成匿名叶：整段按容器（父级）可见性一次性判定（与塑形/行内盒同式）。
    if (root.isText) {
        val hidden = baseHidden || (root.parent?.let { resolve(it)?.visibilityHidden } ?: false)
        if (!hidden) return emptyList()
        val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
        val t = normalizeAnonymousRun(root.text, leafWs)
        return if (t.isEmpty()) emptyList() else listOf(0 until t.length)
    }
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    val segs = precomputed ?: styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, resolve).segments
    if (segs.isEmpty()) return emptyList()
    // 逐节点继承一次算好（与 `styledSegments` 同一跳过规则），段折叠时直接取用。
    val hiddenByNode = HashMap<MarkupElement, Boolean>()
    fun hiddenOf(node: MarkupElement, inherited: Boolean): Boolean =
        resolve(node)?.visibilityHidden ?: inherited
    fun fill(node: MarkupElement, inherited: Boolean) {
        for (c in node.children) {
            when {
                isExcluded(c) -> Unit
                // 顺序与 `styledSegments` 同式：`isBlock` = 本节点不吸收（块边界），必须排在 `c.isText` 之前。
                isBlock(c) -> Unit
                c.isText -> hiddenByNode[c] = hiddenOf(c, inherited)
                c.tag == "br" -> Unit
                c.tag == "img" -> hiddenByNode[c] = hiddenOf(c, inherited)
                else -> {
                    val h = hiddenOf(c, inherited)
                    hiddenByNode[c] = h
                    fill(c, h)
                }
            }
        }
    }
    hiddenByNode[root] = baseHidden
    fill(root, baseHidden)
    val out = ArrayList<IntRange>()
    var pos = 0
    for (seg in segs) {
        val n = seg.text.length
        if (n <= 0) continue
        // 生成/引号段优先伪元素可见性（`::before` 继承原发元素，浏览器同式）；无伪样式回原发节点。
        val hidden = seg.genStyle?.visibilityHidden
            ?: (seg.node?.let { hiddenByNode[it] } ?: baseHidden)
        if (hidden) {
            val last = out.lastOrNull()
            if (last != null && last.last + 1 == pos) out[out.lastIndex] = last.first..(pos + n - 1)
            else out.add(pos until pos + n)
        }
        pos += n
    }
    return out
}
