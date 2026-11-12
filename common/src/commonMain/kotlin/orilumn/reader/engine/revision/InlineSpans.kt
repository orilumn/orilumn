package orilumn.reader.engine.revision

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.StyledSegments

/**
 * 从排版叶取「行内结构」：把选区文字上覆盖的行内元素变成 [InlineMarkup.StyleSpan]
 * （规格 §3.1「取结构」）。
 *
 * 与排版、字符投影**同源**：逐字归属直接复用 [LeafTextEditor.ownersOf]（同一份
 * `StyledSegments` + 同一组 `wsOf` / `styleOf`），因此屏幕上的字和这里的字一一对应，
 * 不会出现「选区七十字、标记只覆盖六十九个」的错位。
 *
 * 输出按 (起点升序、外层在前) 排好，可直接交给 [InlineMarkup.show]；也可以原样当「原选区结构」
 * 传给 [InlineMarkup.restore] 做属性补回。
 *
 * 属于排版层（上）：纯逻辑，可 JVM 单测。
 */
object InlineSpans {

    /**
     * @param from / [to] 选区在叶文本里的区间（默认整叶）；返回的区间相对 [from]。
     * @return 覆盖选区的行内元素区间；未知标签（不在 [InlineMarkup.ALLOWED_TAGS] 里）跳过
     *   （写不回去的标记不显示，免得读者改完反而丢结构）。
     */
    fun ofLeaf(
        leaf: MarkupElement,
        segments: StyledSegments,
        wsOf: (MarkupElement) -> WhiteSpace,
        styleOf: ((MarkupElement) -> ComputedStyle?)? = null,
        from: Int = 0,
        to: Int = segments.text.length,
    ): List<InlineMarkup.StyleSpan> {
        if (to <= from || from < 0 || to > segments.text.length) return emptyList()
        val owners = LeafTextEditor.ownersOf(segments, leaf, wsOf, styleOf)
        val ranges = LinkedHashMap<MarkupElement, IntArray>()
        for (i in from until to) {
            val owner = owners[i]
            var node: MarkupElement? = when (owner) {
                // 文本节点本身不是元素：样式来自它的祖先；br / img 也不是行内装饰。
                is LeafTextEditor.Owner.Text -> owner.node.parent
                is LeafTextEditor.Owner.Marker -> owner.node.parent
                // 生成内容（::before/::after、q 引号）由属主元素自己产生，属主算在内。
                is LeafTextEditor.Owner.Generated -> owner.node
            }
            // 先由内向外收集，再反向记账：同一段区间上「外层先、内层后」，与标注顺序一致。
            val chain = ArrayList<MarkupElement>(4)
            while (node != null && node !== leaf) {
                if (node.tag in InlineMarkup.ALLOWED_TAGS) chain.add(node)
                node = node.parent
            }
            for (el in chain.asReversed()) {
                val r = ranges.getOrPut(el) { intArrayOf(i, i) }
                if (i < r[0]) r[0] = i
                if (i > r[1]) r[1] = i
            }
        }
        return ranges.entries
            .map { (el, r) ->
                InlineMarkup.StyleSpan(r[0] - from, r[1] + 1 - from, sigOf(el))
            }
            .filter { it.endExclusive > it.start }
            .sortedWith(compareBy({ it.start }, { -it.endExclusive }))
    }

    /**
     * 把「本叶内、相对 [localFrom]」的区间搬到**选区起点**坐标系（正文修改卡要的那把尺）。
     *
     * [ofLeaf] 返回的区间是相对传进去的 `from`（= 本叶的 [localFrom]）算的：
     * 叶内绝对位置 `p` 变成 `p - localFrom`。卡片要的是相对**选区起点** `selectionStart`，
     * 也就是 `leafStart + localFrom + p'`（`p'` 是 ofLeaf 给的值）再减去 `selectionStart`。
     *
     * 两段抵消关系不同，**不能只看叶首**：
     * - 第一叶：选区就在这一叶里（或从这里起），`localFrom = selectionStart - leafStart`，位移为 0；
     * - 第二叶起：选区从上一叶开始，`localFrom` 被夹到 0，位移是 `leafStart - selectionStart`。
     * 早先按 `leafStart - selectionStart` 一刀切，第一叶只要不是从叶首起选就会把区间**往前挪**
     * （读者复报的「第二段的标记挪到第一段上」就是这条）。
     */
    fun toSelectionSpace(
        spans: List<InlineMarkup.StyleSpan>,
        leafStart: Int,
        localFrom: Int,
        selectionStart: Int,
    ): List<InlineMarkup.StyleSpan> {
        val shift = leafStart + localFrom - selectionStart
        if (shift == 0) return spans
        return spans.map {
            it.copy(start = it.start + shift, endExclusive = it.endExclusive + shift)
        }
    }

    internal fun sigOf(el: MarkupElement): InlineMarkup.StyleSig {
        val attrs = LinkedHashMap<String, String>()
        for ((k, v) in el.attrs) {
            if (k !in InlineMarkup.ALLOWED_ATTRS) continue
            if (k == "style") {
                val kept = InlineMarkup.filterStyle(v)
                if (kept.isNotEmpty()) attrs[k] = kept
            } else {
                attrs[k] = v
            }
        }
        return InlineMarkup.StyleSig(el.tag, attrs)
    }
}
