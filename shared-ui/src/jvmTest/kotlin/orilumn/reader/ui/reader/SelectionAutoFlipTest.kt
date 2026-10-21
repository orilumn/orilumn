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
    fun `edge band is 24dp on both sides`() {
        val edge = 24f
        assertEquals(-1, selectionAutoFlipDirection(0f, 1000f, edge))
        assertEquals(-1, selectionAutoFlipDirection(24f, 1000f, edge))
        assertEquals(0, selectionAutoFlipDirection(25f, 1000f, edge))
        assertEquals(0, selectionAutoFlipDirection(500f, 1000f, edge))
        assertEquals(0, selectionAutoFlipDirection(975f, 1000f, edge))
        assertEquals(1, selectionAutoFlipDirection(976f, 1000f, edge))
        assertEquals(1, selectionAutoFlipDirection(1000f, 1000f, edge))
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
