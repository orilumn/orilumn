package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 划线标注自身的落墨矩形（旁注箭头「进得行盒、但不得触及下划线」的几何来源）。
 *
 * 断言只谈位置关系：线在字墨迹下方、仍在行盒以内；波浪线比实线占的纵向更多。
 */
class AnnotationLineRectsTest {

    private fun line(
        text: String,
        range: IntRange,
        charBase: Int = 0,
        yTop: Int = 0,
        yBottom: Int = 50,
    ) = DrawLine(
        text = text,
        range = range,
        yTop = yTop,
        yBottom = yBottom,
        alignment = TextAlign.LEFT,
        fontSizePx = 20f,
        lineHeightRatio = 2.5f,
        tag = "p",
        families = emptyList(),
        weight = 400,
        italic = false,
        monospace = false,
        letterSpacingEm = 0f,
        lineWidthPx = 300,
        charBase = charBase,
    )

    private fun bands(
        vararg lines: DrawLine,
        range: IntRange,
        kind: DecorKind = DecorKind.UNDERLINE,
        stroke: DecorStroke = DecorStroke.Solid,
    ): List<org.jetbrains.skia.Rect> =
        LineWindowDrawer().annotationLineRects(lines.toList(), chapter = 0, range = range, kind, stroke)

    @Test
    fun `underline band sits below the ink and inside the line box`() {
        val l = line("国国", 0..1)
        val drawer = LineWindowDrawer()
        val ink = drawer.inkRects(listOf(l), chapter = 0, range = 0..1)
        val band = drawer.annotationLineRects(listOf(l), 0, 0..1, DecorKind.UNDERLINE, DecorStroke.Solid)
        assertEquals("一行一条线", 1, band.size)
        assertTrue("线要在字墨迹下方：${band[0]} vs $ink", band[0].top >= ink.maxOf { it.bottom } - 1f)
        assertTrue("线仍要在行盒以内：${band[0]}", band[0].bottom <= l.yBottom.toFloat())
        assertTrue("线不能跑到行盒上方：${band[0]}", band[0].top >= l.yTop.toFloat())
    }

    @Test
    fun `wavy band is taller than solid`() {
        val l = line("国国", 0..1)
        val solid = bands(l, range = 0..1, stroke = DecorStroke.Solid).single()
        val wavy = bands(l, range = 0..1, stroke = DecorStroke.Wavy).single()
        assertTrue("波浪线的波形半高要算进去：solid=$solid wavy=$wavy", wavy.height > solid.height)
        assertEquals("线心不变", (solid.top + solid.bottom) / 2f, (wavy.top + wavy.bottom) / 2f, 0.01f)
    }

    @Test
    fun `each line gets its own band`() {
        val first = line("一二", 0..1, charBase = 0, yTop = 0, yBottom = 50)
        val second = line("三四", 0..1, charBase = 2, yTop = 50, yBottom = 100)
        val out = bands(first, second, range = 0..3)
        assertEquals(2, out.size)
        assertTrue("第一行在上：$out", (out[0].top + out[0].bottom) < (out[1].top + out[1].bottom))
    }

    @Test
    fun `highlight kind has no line`() {
        val l = line("国国", 0..1)
        assertEquals(emptyList<org.jetbrains.skia.Rect>(), bands(l, range = 0..1, kind = DecorKind.BACKGROUND))
    }
}
