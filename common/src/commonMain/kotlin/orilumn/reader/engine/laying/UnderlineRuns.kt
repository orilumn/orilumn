package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement

/**
 * 下划线单源（pure JVM）：把已级联 `text-decoration: underline` 投影成叶文本坐标系的
 * [UnderlineRun]，与叶文本同一归一化段构建（下标恒对齐）。无下划线叶回空表（绘制零开销）。
 *
 * 语义（CSS 浏览器标准）：装饰不继承、但向后代传播——声明元素的整段发出区间（含后代
 * 文本）全部带下划线。实现为子树预扫：`own || inherited` 向下传播，段折叠时直接取用。
 */
data class UnderlineRun(
    val start: Int,
    val endExclusive: Int,
)

fun collectUnderlineRuns(
    root: MarkupElement,
    styles: Map<MarkupElement, ComputedStyle>,
    isExcluded: (MarkupElement) -> Boolean,
    isBlock: (MarkupElement) -> Boolean,
    rootWs: WhiteSpace = WhiteSpace.NORMAL,
    /** P3-c 生成内容查找（空即无，旧路径）。 */
    genOf: GenOf = EmptyGen,
    /** P3-c 样式回退（默认读 [styles] 表）。 */
    styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
): List<UnderlineRun> {
    if (root.tag == "table" || root.tag == "img") return emptyList()
    if (root.isText) return emptyList()
    val resolve: (MarkupElement) -> ComputedStyle? = styleOf ?: { styles[it] }
    val wsOf: (MarkupElement) -> WhiteSpace = { wsOfNode(it, styles, root, rootWs) }
    val leafWs = styles[root]?.whiteSpace ?: root.parent?.let { styles[it]?.whiteSpace } ?: rootWs
    val segs = styledSegments(root, wsOf, isExcluded, isBlock, leafWs, genOf, resolve).segments
    // 逐节点下划线一次算好（声明元素的后代全继承），段折叠时直接取用。
    val ulByNode = HashMap<MarkupElement, Boolean>()
    fun fill(node: MarkupElement, inherited: Boolean) {
        for (c in node.children) {
            when {
                isExcluded(c) -> Unit
                c.isText -> ulByNode[c] = (resolve(c)?.underline == true) || inherited
                c.tag == "br" -> Unit
                isBlock(c) -> Unit
                c.tag == "img" -> Unit
                else -> {
                    val u = (resolve(c)?.underline == true) || inherited
                    ulByNode[c] = u
                    fill(c, u)
                }
            }
        }
    }
    val rootUl = resolve(root)?.underline == true
    ulByNode[root] = rootUl
    fill(root, rootUl)
    val out = ArrayList<UnderlineRun>()
    var pos = 0
    for (seg in segs) {
        val n = seg.text.length
        if (n <= 0) continue
        val node = seg.node
        val underlined = if (node == null || !node.isText) {
            seg.genStyle?.underline == true || rootUl
        } else {
            ulByNode[node] == true
        }
        if (underlined) {
            val last = out.lastOrNull()
            if (last != null && last.endExclusive == pos) {
                out[out.lastIndex] = last.copy(endExclusive = pos + n)
            } else {
                out.add(UnderlineRun(pos, pos + n))
            }
        }
        pos += n
    }
    return out
}
