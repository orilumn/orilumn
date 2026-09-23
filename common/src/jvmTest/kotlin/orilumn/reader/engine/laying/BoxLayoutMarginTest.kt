package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Box-model geometry: vertical margin collapsing, padding/border offsets, horizontal edge trimming
 * of the line-breaking width, and text-align parsing. Runs against [NormalFlowLayout] through
 * [BoxLayouter] in pure JVM.
 */
class BoxLayoutMarginTest {

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

    /** Width-driven breaker: chars-per-line from the available width (approximates 1 char per pxPerChar). */
    private class WidthBreaker(private val pxPerChar: Int) : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val cpl = (widthPx / pxPerChar).coerceAtLeast(1)
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + cpl).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private fun styles(root: MarkupElement): Map<MarkupElement, ComputedStyle> =
        StyleComputer(10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(""))).compute(root)

    private fun layout(root: MarkupElement, breaker: ParagraphBreaker, widthPx: Int = 30): BookLayout =
        BoxLayouter(10f, breaker).layoutWith(root, widthPx = widthPx, styleMap = styles(root))

    @Test
    fun `相邻块竖向外边距折叠为较大值`() {
        val p1 = node("p", mapOf("style" to "margin-bottom: 20px"), listOf(text("abc")))
        val p2 = node("p", mapOf("style" to "margin-top: 30px"), listOf(text("def")))
        val root = node("body", children = listOf(p1, p2))
        // fontSize 10, line-height 1.5 → lh 15. Collapse max(20,30)=30 between the two lines.
        val layout = layout(root, FixedWidthBreaker(3))
        assertEquals(2, layout.lineCount)
        assertEquals(0, layout.getLineTop(0)); assertEquals(15, layout.getLineBottom(0))
        assertEquals(45, layout.getLineTop(1)); assertEquals(60, layout.getLineBottom(1)) // 15 + 30
    }

    @Test
    fun `异号相邻外边距折叠为两者之和`() {
        val p1 = node("p", mapOf("style" to "margin-bottom: 20px"), listOf(text("abc")))
        val p2 = node("p", mapOf("style" to "margin-top: -20px"), listOf(text("def")))
        val root = node("body", children = listOf(p1, p2))
        // lh 15. collapse(20, -20) = 0 → p2 starts right after p1's line (15..30), not at 15+20.
        val layout = layout(root, FixedWidthBreaker(3))
        assertEquals(2, layout.lineCount)
        assertEquals(15, layout.getLineTop(1))
        assertEquals(30, layout.getLineBottom(1))
    }

    @Test
    fun `margin_border_padding叠加决定内容起始行`() {
        val p = node("p", mapOf("style" to "margin-top: 5px; border-top: 5px; padding-top: 10px"), listOf(text("abc")))
        val root = node("body", children = listOf(p))
        val layout = layout(root, FixedWidthBreaker(3))
        // contentY = margin(5) + border(5) + padding(10) = 20 → first line occupies 20..35
        assertEquals(20, layout.getLineTop(0))
        assertEquals(35, layout.getLineBottom(0))
    }

    @Test
    fun `水平padding与border收窄断行宽度`() {
        val a = node("p", mapOf("style" to "padding-left: 10px; padding-right: 10px; border-left: 5px; border-right: 5px"), listOf(text("abcdef")))
        val b = node("p", children = listOf(text("ghijklm")))
        val root = node("body", children = listOf(a, b))
        // width 30. a contentW = 30 - (10+10+5+5)=0 → clamp 1 → 1 char/line → 6 lines.
        // b contentW = 30 → 3 chars/line → 7 chars → 3 lines. Total 9.
        val layout = layout(root, WidthBreaker(10))
        assertEquals(9, layout.lineCount)
        assertEquals(6 + 7, layout.length)
        // a's 6 lines are 15px tall each and its bottom margin is 0 → b starts right after.
        assertEquals(90, layout.getLineTop(6))
    }

    @Test
    fun `text-align解析进入计算样式`() {
        val p = node("p", mapOf("style" to "text-align: center"))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root)
        assertEquals(TextAlign.CENTER, styleMap[p]?.textAlign)
        // shorthand + individual edge resolution
        val q = node("p", mapOf("style" to "padding: 4px 8px 12px 16px; margin-top: 3px"))
        val root2 = node("body", children = listOf(q))
        val m2 = styles(root2)
        val margin = m2[q]?.margin
        assertEquals(3f, margin?.top)
        assertEquals(0f, margin?.right)
        val pad = m2[q]?.padding
        assertEquals(4f, pad?.top); assertEquals(8f, pad?.right); assertEquals(12f, pad?.bottom); assertEquals(16f, pad?.left)
    }

    @Test
    fun `h1 与块级 sec-num 的首子外边距折叠为较大值而非相加`() {
        // CSS 2.1 §8.3.1: a block box's top margin collapses with its first in-flow block-level child's
        // top margin when it has no top border/padding. h1{margin-top:10px} + .sec-num{display:block;
        // margin-top:10px} must collapse to 10px — the old behavior stacked 10+10=20, the visible
        // "章标题顶部多一块" defect.
        val css = ".sec-num { display: block; margin-top: 10px; margin-bottom: 30px }"
        val root = node("body", children = listOf(
            node("h1", mapOf("style" to "margin-top: 10px; margin-bottom: 30px; font-size: 10px; line-height: 1.5"),
                children = listOf(node("span", mapOf("class" to "sec-num"), listOf(text("第 4 章"))), text("认识所有权"))),
            node("p", mapOf("style" to "margin-top: 5px"), children = listOf(text("def"))),
        ))
        // .sec-num display:block → treated as a block leaf, so h1 is a container with two block children.
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(css))).compute(root)
        // 100 chars/line keeps each block a single 15px line, so line indexes are predictable.
        val layout = BoxLayouter(10f, FixedWidthBreaker(100)).layoutWith(root, widthPx = 300, styleMap = styleMap)
        // The whole parent↔first-child chain collapses to max(body 0, h1 10, sec-num 10) = 10px.
        assertEquals(10, layout.getLineTop(0))          // "第 4 章" — NOT 20
        // sec-num bottom 30px then collapses with the anonymous sibling "认识所有权" (0) → next line at 55.
        assertEquals(55, layout.getLineTop(1))
        // h1.bottom 30px collapses with p's 5px → p at 100.
        assertEquals(100, layout.getLineTop(2))
    }

    @Test
    fun `父容器有上内边距时阻断父首子折叠`() {
        // With any top border/padding the parent's top margin is no longer adjoining the first child's
        // (CSS 2.1 §8.3.1 requires "no top border, no top padding"), so they revert to stacking:
        // h1 margin-top 10 + padding-top 1 + sec-num margin-top 10 = 21 before the first line.
        val css = ".sec-num { display: block; margin-top: 10px }"
        val root = node("body", children = listOf(
            node("h1", mapOf("style" to "margin-top: 10px; padding-top: 1px; font-size: 10px; line-height: 1.5"),
                children = listOf(node("span", mapOf("class" to "sec-num"), listOf(text("标题"))))),
        ))
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(css))).compute(root)
        val layout = BoxLayouter(10f, FixedWidthBreaker(100)).layoutWith(root, widthPx = 300, styleMap = styleMap)
        assertEquals(10 + 1 + 10, layout.getLineTop(0))
    }

    @Test
    fun `父容器 bottom margin 与末子 bottom margin 折叠为较大值而非相加`() {
        // CSS 2.1 §8.3.1 (bottom): a bottom-edge-free, auto-height div's margin-bottom is adjoining the
        // last in-flow block child's, so div{mb:10} + p{mb:30} collapse to max(10,30)=30 → the following
        // p starts 30px after the first line. The old behavior stacked them to 40 (10+30) — the "末子
        // 弹开下一段" defect.
        val root = node("body", children = listOf(
            node("div", mapOf("style" to "margin-bottom: 10px"),
                children = listOf(node("p", mapOf("style" to "margin-bottom: 30px"), listOf(text("aaa"))))),
            node("p", children = listOf(text("bbb"))),
        ))
        // 100 chars/line keeps each block a single 15px line (fontSize 10 × lh 1.5).
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val layout = BoxLayouter(10f, FixedWidthBreaker(100)).layoutWith(root, widthPx = 300, styleMap = styleMap)
        assertEquals(0, layout.getLineTop(0))             // "aaa" — div has no top margin
        assertEquals(45, layout.getLineTop(1))            // 15 + collapse(10, 30) = 45 — NOT 15+10=25, NOT 15+40=55
    }

    @Test
    fun `父容器有下内边距时阻断父末子折叠`() {
        // With any bottom border/padding the parent's bottom margin is no longer adjoining the last
        // child's (§8.3.1 requires "no bottom border, no bottom padding"): the child's 30 is trapped
        // inside the parent's padding and the sibling collapses only with the parent's own 10.
        val root = node("body", children = listOf(
            node("div", mapOf("style" to "margin-bottom: 10px; padding-bottom: 1px"),
                children = listOf(node("p", mapOf("style" to "margin-bottom: 30px"), listOf(text("aaa"))))),
            node("p", children = listOf(text("bbb"))),
        ))
        val styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val layout = BoxLayouter(10f, FixedWidthBreaker(100)).layoutWith(root, widthPx = 300, styleMap = styleMap)
        assertEquals(0, layout.getLineTop(0))             // "aaa"
        assertEquals(15 + 1 + 10, layout.getLineTop(1))   // 15 + padding 1 + collapse(10, 0) = 26 — NOT 45
    }

    private fun leavesOf(box: LayoutBox): List<LayoutBox> =
        if (box.isContainer) box.childBoxes.flatMap { leavesOf(it) } else listOf(box)

    @Test
    fun `contentLeft 逐层累积块的左边缘`() {
        // body > ul(padding-left 20) > li(margin-left 5) > #text
        val textEl = MarkupElement("#text", text = "x")
        val liEl = MarkupElement("li", children = listOf(textEl))
        val ulEl = MarkupElement("ul", children = listOf(liEl))
        val bodyEl = MarkupElement("body", children = listOf(ulEl))
        textEl.parent = liEl; liEl.parent = ulEl; ulEl.parent = bodyEl
        fun styleOf(e: MarkupElement): ComputedStyle = when (e) {
            ulEl -> ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, padding = Edges(left = 20f))
            liEl -> ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, margin = Edges(left = 5f))
            else -> ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f)
        }
        val left = NormalFlowLayout.descendContentLeft(textEl, 0,
            leftEdgesOf = { e -> (styleOf(e).border.left + styleOf(e).padding.left).roundToInt() },
            marginLeftOf = { e -> styleOf(e).margin.left.roundToInt() },
        )
        // ul padding-left 20 + li margin-left 5 = 25 for the li's text leaf; #text margin is 0.
        assertEquals(25, left)
    }

    @Test
    fun `font-family monospace 解析进计算样式`() {
        // A block declaring the generic `monospace` face resolves to monospace.
        val pre = node("pre", mapOf("style" to "font-family: monospace"), children = listOf(text("code")))
        val root = node("body", children = listOf(pre))
        val styleMap = styles(root)
        assertEquals(true, styleMap[pre]?.monospace)
        // A monospace lookalike family also counts.
        val q = node("pre", mapOf("style" to "font-family: \"Courier New\""), children = listOf(text("x")))
        assertEquals(true, styles(node("body", children = listOf(q)))[q]?.monospace)
        // Non-monospace stays default false.
        val p = node("p", mapOf("style" to "font-family: serif"), children = listOf(text("x")))
        assertEquals(false, styles(node("body", children = listOf(p)))[p]?.monospace)
    }
}