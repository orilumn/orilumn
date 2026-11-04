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

    // ---- 键盘视口里页面上下滚动的惯性 ----

    @Test
    fun `fling velocity is positive when the finger moved up`() {
        // 100ms 内手指从 y=500 移到 y=200（往上）⇒ 页面上滚，速度 +3000px/s。
        val v = pageFlingVelocityPxPerSec(listOf(0L to 500f, 100L to 200f))
        assertEquals(3000f, v, 0.5f)
    }

    @Test
    fun `fling velocity is negative when the finger moved down`() {
        val v = pageFlingVelocityPxPerSec(listOf(0L to 200f, 100L to 500f))
        assertEquals(-3000f, v, 0.5f)
    }

    @Test
    fun `fling velocity needs two samples with a real time span`() {
        assertEquals(0f, pageFlingVelocityPxPerSec(emptyList()), 0f)
        assertEquals(0f, pageFlingVelocityPxPerSec(listOf(120L to 400f)), 0f)
        // 时差为零（同一帧采到两次）不能除法爆炸。
        assertEquals(0f, pageFlingVelocityPxPerSec(listOf(120L to 400f, 120L to 200f)), 0f)
    }

    @Test
    fun `fling decays and keeps its direction`() {
        // 一帧（16ms）后速度按 e^-4.2*0.016 ≈ 0.935 衰减，方向不变。
        val after = pageFlingDecay(2000f, 0.016f)
        assertEquals(1870f, after, 1f)
        assertEquals(-1870f, pageFlingDecay(-2000f, 0.016f), 1f)
        // 0.5s 后还剩约 12%（2.1 个时间常数），1.5s 后基本停住，不会一直滑。
        assertEquals(245f, pageFlingDecay(2000f, 0.5f), 2f)
        assertEquals(0f, pageFlingDecay(2000f, 1.5f), 5f)
    }
}
