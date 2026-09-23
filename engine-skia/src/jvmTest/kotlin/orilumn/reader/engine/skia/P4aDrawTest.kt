package orilumn.reader.engine.skia

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.layout.ListMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-a2h: 悬浮环绕的绘制投影——前导行按收缩宽/x 偏移出 DrawLine，余行原样。
 */
class P4aDrawTest {

    private fun build(html: String, widthPx: Int = 300): Triple<Map<Int, DrawLine>, Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>, MarkupElement> {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, widthPx, styleMap, classify)
        return Triple(DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f), styleMap, root)
    }

    @Test
    fun `wrapped leading lines carry narrowed width and x offset`() {
        val xs = "x".repeat(200)
        val (map, styleMap, root) = build(
            "<html><body><img width=\"100\" height=\"60\" src=\"a.png\" style=\"float: left\"/>" +
                "<p>$xs</p></body></html>",
        )
        val p = root.children.first { it.tag == "p" }
        val pStyle = styleMap[p]!!
        val gap = ListMarkers.markerGapPx(pStyle.fontSizePx)
        val paraLines = map.values.filter { it.text.length == 200 }.sortedBy { it.range.first }
        assertTrue("p must break into several lines", paraLines.size > 2)
        // 前导行收窄右移（与塑形同宽），余行原宽原位；整体字符连续。
        val narrowed = paraLines.takeWhile { it.lineWidthPx == 300 - 100 - gap }
        assertTrue("need wrapped leading lines", narrowed.isNotEmpty())
        assertTrue(narrowed.all { it.xLeft == 100 + gap })
        val rest = paraLines.drop(narrowed.size)
        assertTrue("need full-width trailing lines", rest.isNotEmpty())
        assertTrue(rest.all { it.lineWidthPx == 300 && it.xLeft == 0 })
        var cursor = 0
        for (l in paraLines) {
            assertEquals(cursor, l.range.first)
            cursor = l.range.last + 1
        }
        assertEquals(200, cursor)
    }

    @Test
    fun `no float means uniform width and zero x`() {
        val xs = "x".repeat(200)
        val (map, _, _) = build("<html><body><p>$xs</p></body></html>")
        val paraLines = map.values.filter { it.text.length == 200 }
        assertTrue(paraLines.isNotEmpty())
        assertTrue(paraLines.all { it.lineWidthPx == 300 && it.xLeft == 0 })
    }
}
