package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Box-model **nesting** + the background/border drawing model ([BoxDrawer]): nested block boxes
 * carry their own geometry, and every box with a background/border color resolves to paint rects —
 * all pure JVM.
 */
class BoxNestedLayoutTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    /** Deterministic breaker: exactly `charsPerLine` chars per line (width-independent). */
    private class FixedWidthBreaker(private val charsPerLine: Int) : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + charsPerLine).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private fun styles(root: MarkupElement) =
        StyleComputer(10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(""))).compute(root)

    private fun layout(root: MarkupElement): BoxLayoutResult =
        BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styles(root))

    @Test
    fun `嵌套块产生容器与叶子并保持行序`() {
        // div (container) wrapping two paragraphs (leaves)
        val outer = node("div", children = listOf(
            node("p", children = listOf(text("abcdef"))),
            node("p", children = listOf(text("ghijklm"))),
        ))
        val root = node("body", children = listOf(outer))

        val result = layout(root)
        // div→2 paragraphs: "abcdef"(6) at 3/line → 2 lines; "ghijklm"(7) → 3 lines; total 5.
        assertEquals(5, result.lines.size)
        assertEquals(13, result.lines.last().charEnd)

        val bodyBox = result.boxes.single()
        assertEquals(true, bodyBox.isContainer)          // body wraps the div
        val outerBox = bodyBox.childBoxes.single()       // the div container
        assertEquals(true, outerBox.isContainer)
        assertEquals(2, outerBox.childBoxes.size)
        // container geometry spans its children
        assertEquals(true, outerBox.contentBottom >= outerBox.contentTop)
    }

    @Test
    fun `行内元素被吸收进所在文本块而非成盒`() {
        // <p>abc<b>def</b>ghi</p>: b is inline → absorbed, still a single leaf box, one 9-char run.
        val b = node("b", children = listOf(text("def")))
        val p = node("p", children = listOf(text("abc"), b, text("ghi")))
        val root = node("body", children = listOf(p))

        val result = layout(root)
        val leaves = flattenLeaves(result.boxes)
        assertEquals(1, leaves.size)
        assertEquals(9, leaves[0].textLength)
        assertEquals(3, result.lines.size) // 9 chars / 3 per line
    }

    @Test
    fun `背景与边框解析为绘制矩形`() {
        val block = node("p", mapOf("style" to "background-color: #eeeeee; border: 2px solid #ff0000; padding: 5px"), listOf(text("abcdef")))
        val root = node("body", children = listOf(block))

        val result = layout(root)
        val rects = BoxDrawer.draw(result.boxes)

        // 1 background fill + 4 border bands
        val bg = rects.filter { it.kind == DrawKind.BACKGROUND }
        val borders = rects.filter { it.kind == DrawKind.BORDER }
        assertEquals(1, bg.size)
        assertEquals(4, borders.size)
        assertEquals("#ffeeeeee", bg[0].colorHex)
        assertEquals("#ffff0000", borders[0].colorHex)

        // background (border-box) must fully contain the content line top and bottom
        val line = result.lines.first()
        assertEquals(true, bg[0].top <= line.yTop && bg[0].bottom >= line.yBottom)
    }

    @Test
    fun `contentLeft 沿容器左边缘逐层累积 padding`() {
        // body > ul(padding-left:10px) > li > text: the li leaf's border-box left must bake in the ul padding.
        val liEl = node("li", children = listOf(text("abcdef")))
        val ulEl = node("ul", mapOf("style" to "padding-left: 10px"), children = listOf(liEl))
        val root = node("body", children = listOf(ulEl))

        val result = layout(root)
        val bodyBox = result.boxes.single()
        val ulBox = bodyBox.childBoxes.single()
        assertEquals(true, ulBox.isContainer)
        val liLeaf = ulBox.childBoxes.single()
        assertEquals(false, liLeaf.isContainer)
        assertEquals(10, liLeaf.contentLeft)
        // A sibling paragraph with no padding stays at 0.
        val pRoot = node("body", children = listOf(node("p", children = listOf(text("x")))))
        assertEquals(0, layout(pRoot).boxes.single().childBoxes.single().contentLeft)
    }

    /**
     * CSS 2.1 §10.3.3：块级 auto 宽 = 包含块内容宽 − **自身横向外边距** ⇒ 右缘不越包含块。
     *
     * 为什么是硬要求：分页阅读器页宽固定，多出来的那一截**被页面裁掉**（丢内容，不是不好看）。
     * 变异验证：把 `buildBoxTree` 的 `innerW − marginL − marginR` 还原成 `innerW`（只右移不缩宽）
     * ⇒ 本用例立即红，实测 `expected:<22> but was:<30>`，即 div 右缘 34 越出版心右 30 共 4px。
     */
    @Test
    fun `块级横向外边距收缩可用宽且右缘不越包含块`() {
        // body > div(margin: 0 4px) > p：div 的 border-box 宽 = 版心(30) − 左右外边距(8)。
        val inner = node("p", children = listOf(text("abcdef")))
        val div = node("div", mapOf("style" to "margin: 0 4px"), children = listOf(inner))
        val root = node("body", children = listOf(div))

        val bodyBox = layout(root).boxes.single()
        val divBox = bodyBox.childBoxes.single()
        assertEquals(true, divBox.isContainer)
        assertEquals(4, divBox.contentLeft)
        assertEquals(22, divBox.contentWidth)
        // 右缘 = 版心右 − 自身右外边距（不是版心右）⇒ 绝不越界。
        assertEquals(26, divBox.contentLeft + divBox.contentWidth)

        // 嵌套逐层扣：再嵌一层 margin: 0 3px 的容器，叶子右缘仍落在版心内。
        val deepLeaf = node("p", children = listOf(text("abcdef")))
        val deep = node("blockquote", mapOf("style" to "margin-left: 3px"), children = listOf(deepLeaf))
        val wrap = node("div", mapOf("style" to "margin-right: 5px"), children = listOf(deep))
        val deepRoot = node("body", children = listOf(wrap))
        val wrapBox = layout(deepRoot).boxes.single().childBoxes.single()
        val deepBox = wrapBox.childBoxes.single()
        val leaf = deepBox.childBoxes.single()
        assertEquals(0, wrapBox.contentLeft)
        assertEquals(25, wrapBox.contentWidth)
        assertEquals(3, deepBox.contentLeft)
        assertEquals(22, deepBox.contentWidth)
        assertEquals(22, leaf.contentWidth)
        assertEquals(25, leaf.contentLeft + leaf.contentWidth)

        // 无横向外边距的块逐值不变（防「一律缩宽」）。
        val plainRoot = node("body", children = listOf(node("p", children = listOf(text("x")))))
        val plain = layout(plainRoot).boxes.single().childBoxes.single()
        assertEquals(0, plain.contentLeft)
        assertEquals(30, plain.contentWidth)
    }

    @Test
    fun `嵌套容器为其子盒子提供独立背景绘制区域`() {
        // outer div with a gray background contains an inner paragraph with a red border.
        val para = node("p", mapOf("style" to "border: 1px solid #ff0000"), listOf(text("abcdef")))
        val outer = node("div", mapOf("style" to "background-color: #dddddd"), children = listOf(para))
        val root = node("body", children = listOf(outer))

        val result = layout(root)
        val rects = BoxDrawer.draw(result.boxes)

        val bg = rects.filter { it.kind == DrawKind.BACKGROUND }
        val borders = rects.filter { it.kind == DrawKind.BORDER }
        assertEquals(1, bg.size) // only the outer div background
        assertEquals("#ffdddddd", bg[0].colorHex)
        assertEquals(4, borders.size) // inner paragraph border
        // the paragraph's border box lies fully within the outer background
        val pb = bg[0]
        val anyBorderWithin = borders.all { it.top >= pb.top && it.bottom <= pb.bottom }
        assertEquals(true, anyBorderWithin)
    }

    private fun flattenLeaves(boxes: List<LayoutBox>): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        for (b in boxes) if (b.isContainer) out.addAll(flattenLeaves(b.childBoxes)) else out.add(b)
        return out
    }
}