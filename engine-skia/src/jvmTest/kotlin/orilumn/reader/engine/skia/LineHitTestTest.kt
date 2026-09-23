package orilumn.reader.engine.skia

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.NormalFlowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-c2 hit: `DrawLine.charBase` 章内连续 + 行内点按命中（量画同源）。
 */
class LineHitTestTest {

    private fun build(html: String): Map<Int, DrawLine> {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        return DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
    }

    @Test
    fun `charBase is contiguous across leaves`() {
        val map = build("<html><body><p>alpha</p><p>beta gamma delta epsilon zeta eta theta iota</p></body></html>")
        assertTrue(map.isNotEmpty())
        // 首叶 text[0] 即章首。
        assertEquals(0, map.values.minOf { it.charBase })
        // 同叶各行 charBase 一致；次叶起点 = 首叶起点 + 首叶全文长。
        val byBase = map.values.groupBy { it.charBase }
        assertEquals("two leaves → two bases", 2, byBase.size)
        val sorted = byBase.keys.sorted()
        val firstText = byBase.getValue(sorted[0]).first().text
        assertEquals(sorted[0] + firstText.length, sorted[1])
        // text[k] ↔ charBase + k：行区间起点换算回基址恒等。
        for (line in map.values) {
            assertTrue(line.range.first >= 0)
        }
    }

    @Test
    fun `hit maps x to glyph offsets monotonically`() {
        val map = build("<html><body><p>beta gamma delta epsilon zeta eta theta iota kappa lambda</p></body></html>")
        val line = map.values.first()
        val yMid = (line.yTop + line.yBottom) / 2f - line.yTop
        // 行首 x=0 命中区间首字。
        assertEquals(line.range.first, LineHitTest.hit(line, 0f, yMid))
        // 行左外侧 miss。
        assertNull(LineHitTest.hit(line, -5f, yMid))
        // 行尾空区 miss（远超行宽）。
        assertNull(LineHitTest.hit(line, 100000f, yMid))
        // 单调：x 递增 → 命中偏移不减（null 只出现在尾部空区，滤掉不断调性）。
        val xs = listOf(0f, 20f, 60f, 120f, 200f, 300f)
        val hits = xs.map { LineHitTest.hit(line, it, yMid) }.filterNotNull()
        assertTrue("need mid-line hits for monotonicity", hits.size >= 3)
        assertEquals(hits.sorted(), hits)
        assertTrue("all hits stay inside the line range", hits.all { it in line.range })
    }

    @Test
    fun `empty line never hits`() {
        val line = DrawLine(
            text = "",
            range = 0..-1,
            yTop = 0,
            yBottom = 10,
            alignment = orilumn.reader.engine.css.TextAlign.LEFT,
            fontSizePx = 16f,
            lineHeightRatio = 1.5f,
            tag = "p",
            families = emptyList(),
            weight = 400,
            italic = false,
            monospace = false,
            letterSpacingEm = 0f,
            lineWidthPx = 600,
        )
        assertNull(LineHitTest.hit(line, 10f, 5f))
    }
}
