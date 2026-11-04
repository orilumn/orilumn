package orilumn.reader.ui.reader

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.skia.DrawLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D-8 / 3.3：选区拖到页边自动翻页的纯几何（触发带 + 新页靠边字接力）。 */
class SelectionAutoFlipTest {

    private fun line(
        text: String,
        range: IntRange,
        yTop: Int,
        yBottom: Int = yTop + 40,
        charBase: Int,
    ): DrawLine = DrawLine(
        text = text,
        range = range,
        yTop = yTop,
        yBottom = yBottom,
        alignment = TextAlign.LEFT,
        fontSizePx = 18f,
        lineHeightRatio = 1.5f,
        tag = null,
        families = emptyList(),
        weight = 400,
        italic = false,
        monospace = false,
        letterSpacingEm = 0f,
        lineWidthPx = 300,
        charBase = charBase,
    )

    @Test
    fun `flip band starts past the text column not at the screen edge`() {
        // 正文 100..900，触发带从正文缘再往外 16px（左右各一条），屏幕保底带 24px。
        val zone = selectionFlipZone(
            widthPx = 1000f,
            contentLeftPx = 100f,
            contentRightPx = 900f,
            pastTextPx = 16f,
            edgePx = 24f,
        )
        assertEquals(-1, zone.directionOf(0f))
        assertEquals(-1, zone.directionOf(84f))
        assertEquals(0, zone.directionOf(85f))
        assertEquals(0, zone.directionOf(500f))
        assertEquals(0, zone.directionOf(915f))
        assertEquals(1, zone.directionOf(916f))
        assertEquals(1, zone.directionOf(1000f))
    }

    @Test
    fun `line end stays out of the band with default margins`() {
        // 默认边距 50dp、屏宽 1200px、密度 2 ⇒ 正文右缘在 1100px；行末的字不该落进触发带。
        val zone = selectionFlipZone(
            widthPx = 1200f,
            contentLeftPx = 100f,
            contentRightPx = 1100f,
            pastTextPx = 8f * 2f,
            edgePx = 12f * 2f,
        )
        assertEquals(0, zone.directionOf(1100f))
        assertEquals(0, zone.directionOf(1090f))
        assertEquals(1, zone.directionOf(1116f))
    }

    @Test
    fun `zero margins fall back to a screen edge band`() {
        val zone = selectionFlipZone(
            widthPx = 1000f,
            contentLeftPx = 0f,
            contentRightPx = 1000f,
            pastTextPx = 16f,
            edgePx = 24f,
        )
        assertEquals(24f, zone.left)
        assertEquals(976f, zone.right)
        assertEquals(-1, zone.directionOf(10f))
        assertEquals(1, zone.directionOf(990f))
    }

    @Test
    fun `re-arming needs the finger back inside the text column`() {
        val zone = selectionFlipZone(1000f, 100f, 900f, 16f, 24f)
        // 带里（含边界）不算退回正文区。
        assertEquals(false, zone.hasLeftBand(84f, 6f))
        assertEquals(false, zone.hasLeftBand(90f, 6f))
        assertEquals(true, zone.hasLeftBand(91f, 6f))
        assertEquals(true, zone.hasLeftBand(909f, 6f))
        assertEquals(false, zone.hasLeftBand(910f, 6f))
    }

    @Test
    fun `side offset picks first or last visible char of the nearest line`() {
        val lines = listOf(
            line("Hello", 0..4, yTop = 100, charBase = 10),
            line("world", 0..4, yTop = 200, charBase = 20),
        )
        // 手指在页边距里（y 比首行还高）也吸附到最近的首行。
        assertEquals(10, selectionSideOffset(lines, shift = 0, yPx = 50f, direction = -1))
        assertEquals(14, selectionSideOffset(lines, shift = 0, yPx = 50f, direction = 1))
        // 落在两行之间时取距离更近的第二行。
        assertEquals(20, selectionSideOffset(lines, shift = 0, yPx = 190f, direction = -1))
        assertEquals(24, selectionSideOffset(lines, shift = 0, yPx = 190f, direction = 1))
        // shift 是章节绝对 Y → 屏幕 Y 的平移，必须参与最近行判定。
        assertEquals(10, selectionSideOffset(lines, shift = 100, yPx = 50f, direction = -1))
        assertEquals(24, selectionSideOffset(lines, shift = 100, yPx = 150f, direction = 1))
    }

    @Test
    fun `chapter boundary stops forward at end and backward at start`() {
        assertEquals(false, selectionAtChapterBoundary(0, 700, 2000, 1))
        assertEquals(true, selectionAtChapterBoundary(1300, 2000, 2000, 1))
        assertEquals(true, selectionAtChapterBoundary(0, 700, 2000, -1))
        assertEquals(false, selectionAtChapterBoundary(700, 1400, 2000, -1))
    }

    @Test
    fun `empty lines never produce an offset`() {
        assertNull(selectionSideOffset(emptyList(), 0, 100f, 1))
        val empty = line("", IntRange.EMPTY, yTop = 100, charBase = 5)
        assertNull(selectionSideOffset(listOf(empty), 0, 100f, 1))
    }
}
