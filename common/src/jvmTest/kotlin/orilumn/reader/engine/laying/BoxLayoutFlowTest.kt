package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxLayoutFlowTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    /** Deterministic breaker: exactly `charsPerLine` chars per line. */
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

    /** One char per line (capacity derived from width/fontSize must be >=1). */
    private fun styles(root: MarkupElement) = StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(""))).compute(root)

    @Test
    fun `行流几何与段落边界`() {
        val p1 = node("p", children = listOf(text("abcdef")))
        val p2 = node("p", children = listOf(text("ghijk")))
        val root = node("body", children = listOf(p1, p2))
        // fontSize 10 → 3 chars/line; lineHeight = round(10 * 1.5) = 15
        val layout: BookLayout = BoxLayouter(10f, FixedWidthBreaker(3)).layoutWith(root, widthPx = 30, styleMap = styles(root))

        assertEquals(4, layout.lineCount)                    // p1:2 lines, p2:2 lines
        assertEquals(11, layout.length)                      // "abcdef"(6) + "ghijk"(5)

        // p1 first line is a paragraph start; second line of p1 is not; p2 first line is.
        assertEquals(true, layout.isParagraphBoundaryLine(0))
        assertEquals(false, layout.isParagraphBoundaryLine(1))
        assertEquals(true, layout.isParagraphBoundaryLine(2))
        assertEquals(false, layout.isParagraphBoundaryLine(3))

        // y progression: each line 15px high, starting from content top 0
        assertEquals(0, layout.getLineTop(0)); assertEquals(15, layout.getLineBottom(0))
        assertEquals(15, layout.getLineTop(1)); assertEquals(30, layout.getLineBottom(1))
        assertEquals(30, layout.getLineTop(2)); assertEquals(45, layout.getLineBottom(2))
        assertEquals(45, layout.getLineTop(3)); assertEquals(60, layout.getLineBottom(3))

        // char offsets span the whole stream contiguously
        assertEquals(0, layout.getLineStart(0)); assertEquals(3, layout.getLineEnd(0))
        assertEquals(3, layout.getLineStart(1)); assertEquals(6, layout.getLineEnd(1))
        assertEquals(6, layout.getLineStart(2)); assertEquals(9, layout.getLineEnd(2))
        assertEquals(9, layout.getLineStart(3)); assertEquals(11, layout.getLineEnd(3))
    }

    @Test
    fun `字体越大行高越大`() {
        val p = node("p", mapOf("style" to "font-size: 20px; line-height: 1.0"), listOf(text("abcdef")))
        val root = node("body", children = listOf(p))
        val layout = BoxLayouter(10f, FixedWidthBreaker(3)).layoutWith(root, widthPx = 30, styleMap = styles(root))
        // one char-per line not applied here (width/font signature) — but line height = round(20*1.0) = 20
        assertEquals(20, layout.getLineBottom(0))
        // 20px tall each line
        assertEquals(20, layout.getLineTop(1) - layout.getLineTop(0))
    }

    @Test
    fun `空树与无文本块不产生行`() {
        val root = node("body")
        val layout = BoxLayouter(10f, FixedWidthBreaker(3)).layoutWith(root, widthPx = 30, styleMap = styles(root))
        assertEquals(0, layout.lineCount)
        assertEquals(0, layout.length)
    }

    @Test
    fun `嵌套行内文本被吸收进块`() {
        val b = node("b", children = listOf(text("def")))
        val p = node("p", children = listOf(text("abc"), b, text("ghi")))
        val root = node("body", children = listOf(p))
        val layout = BoxLayouter(10f, FixedWidthBreaker(3)).layoutWith(root, widthPx = 30, styleMap = styles(root))
        // "abcdefghi" (9 chars) → 3 lines
        assertEquals(3, layout.lineCount)
        assertEquals(9, layout.length)
    }
}