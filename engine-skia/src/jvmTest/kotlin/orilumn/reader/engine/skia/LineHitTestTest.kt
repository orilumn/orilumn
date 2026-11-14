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
        val result = BoxLayouter(16f, bodyParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
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

    /**
     * 接缝上被吃成零宽的分隔空白**不可选**（真机 2026-11-10：「拖动选区手柄可以选到单独的空白字符」）。
     *
     * 触发形态是真书里最常见的那一个：`判断出 <code>customers</code>`。行内盒的左内边距记在
     * 「空格 → `c`」之间，于是两者的 x 之间多出一段 6px 没有墨的区间；旧实现在那里二分成空格，
     * 选区就出现一段纯空白的高亮（手柄也能停在上面）。
     */
    @Test
    fun `eaten seam space is never a hit target`() {
        val root = HtmlTreeConverter().convert("<html><body><p>判断出 <code>customers</code> 的类型为</p></body></html>")!!
        val engine = StyleComputer(
            16f,
            LightCssParser().parse("code { padding: 0 6px; background: #f5f5f5 }"),
            emptyList(),
        )
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, bodyParagraphBreaker(0f, 0.25f))
            .layoutBoxes(root, 600, styleMap, classify)
        val line = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f).values.first()
        val text = line.text
        val space = text.indexOf(' ')
        assertTrue("fixture must have the seam space: <$text>", space > 0)
        val yMid = (line.yTop + line.yBottom) / 2f - line.yTop
        // 逐 0.5px 扫过整行，记录每个 x 命中哪个字。
        val byX = ArrayList<Pair<Float, Int>>()
        var x = 0f
        while (x <= 900f) {
            LineHitTest.hit(line, x, yMid)?.let { byX += x to it }
            x += 0.5f
        }
        assertTrue("need hits around the seam", byX.size > 20)
        assertTrue(
            "接缝空白不可选，实际命中：${byX.filter { it.second == space }.take(5)}",
            byX.none { it.second == space },
        )
        // 空白两侧确实隔开了一段没有墨的 x 区间（框内边距）——否则这条用例根本没覆盖到症状：
        // 内边距记在「空格 → `c`」之间 ⇒ `c` 的命中跨度 = 字宽 + 6px（无框时只有字宽）。
        val firstAfter = byX.first { it.second == space + 1 }.first
        val nextAfter = byX.firstOrNull { it.second == space + 2 }?.first
        assertTrue("fixture must have the box padding", line.inlineBoxRuns.any { it.padding.left > 0f })
        assertTrue("需要 c 之后的命中点", nextAfter != null)
        assertTrue(
            "c 的命中跨度 ${nextAfter!! - firstAfter} 太窄，用例没覆盖到框内边距那一段",
            nextAfter - firstAfter > 10f,
        )
        // 触点落在这一段里 = 指着盒子里的字（旧实现返回那个空白）。
        assertEquals(space + 1, LineHitTest.hit(line, firstAfter + 2f, yMid))
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
