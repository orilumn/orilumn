package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.settings.ReaderSettings

/**
 * 标注色槽辅助函数的锁：默认槽、越界回退、只换 RGB / alpha 时另一通道不被破坏。
 *
 * 颜色是创建当时的快照，所以这里尤其要锁「改槽不回溯」所依赖的纯函数语义；
 * 面板与浮动菜单都只调这些函数，不各自再写一套位运算。
 */
class AnnotationColorsTest {

    @Test
    fun `default color follows configured slot`() {
        val s = ReaderSettings.DEFAULT.copy(highlightDefaultSlot = 2, underlineDefaultSlot = 4)
        assertEquals(
            ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS[2],
            annotationDefaultColor(s, AnnotationKind.Highlight),
        )
        assertEquals(
            ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS[4],
            annotationDefaultColor(s, AnnotationKind.Underline),
        )
    }

    @Test
    fun `invalid default slot is clamped`() {
        val s = ReaderSettings.DEFAULT.copy(highlightDefaultSlot = 99, underlineDefaultSlot = -7)
        assertEquals(
            ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS.last(),
            annotationDefaultColor(s, AnnotationKind.Highlight),
        )
        assertEquals(
            ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS.first(),
            annotationDefaultColor(s, AnnotationKind.Underline),
        )
    }

    @Test
    fun `empty slots fall back to factory colors`() {
        val s = ReaderSettings.DEFAULT.copy(
            highlightColorSlots = emptyList(),
            underlineColorSlots = emptyList(),
        )
        assertEquals(DEFAULT_HIGHLIGHT_ARGB, annotationDefaultColor(s, AnnotationKind.Highlight))
        assertEquals(DEFAULT_UNDERLINE_ARGB, annotationDefaultColor(s, AnnotationKind.Underline))
    }

    @Test
    fun `with slot changes only the target slot`() {
        val before = ReaderSettings.DEFAULT
        val after = withAnnotationColorSlot(before, AnnotationKind.Highlight, 3, 0x80112233.toInt())
        assertEquals(0x80112233.toInt(), after.highlightColorSlots[3])
        assertEquals(before.highlightColorSlots[0], after.highlightColorSlots[0])
        assertEquals(before.underlineColorSlots, after.underlineColorSlots)
        assertEquals(before.highlightDefaultSlot, after.highlightDefaultSlot)
    }

    @Test
    fun `with default slot clamps and leaves colors untouched`() {
        val before = ReaderSettings.DEFAULT
        val after = withAnnotationDefaultSlot(before, AnnotationKind.Underline, 99)
        assertEquals(before.underlineColorSlots.lastIndex, after.underlineDefaultSlot)
        assertEquals(before.underlineColorSlots, after.underlineColorSlots)
    }

    @Test
    fun `rgb edit preserves alpha and alpha edit preserves rgb`() {
        val original = 0x73FFD54F
        val rgb = 0xFF1E88E5.toInt()
        val rgbChanged = annotationWithRgb(original, rgb)
        assertEquals(0x73, annotationAlpha(rgbChanged))
        assertEquals(0x1E88E5, rgbChanged and 0x00FFFFFF)

        val alphaChanged = annotationWithAlpha(original, 0xFF)
        assertEquals(0xFF, annotationAlpha(alphaChanged))
        assertEquals(original and 0x00FFFFFF, alphaChanged and 0x00FFFFFF)
    }

    @Test
    fun `single channel edit keeps the other channels and alpha`() {
        val original = 0x73112233
        val red = annotationWithChannel(original, 16, 0xAA)
        assertEquals(0xAA, annotationRed(red))
        assertEquals(0x22, annotationGreen(red))
        assertEquals(0x33, annotationBlue(red))
        assertEquals(0x73, annotationAlpha(red))
        val green = annotationWithChannel(original, 8, 0xBB)
        assertEquals(0x11, annotationRed(green))
        assertEquals(0xBB, annotationGreen(green))
        assertEquals(0x33, annotationBlue(green))
        val blue = annotationWithChannel(original, 0, 0xCC)
        assertEquals(0x11, annotationRed(blue))
        assertEquals(0x22, annotationGreen(blue))
        assertEquals(0xCC, annotationBlue(blue))
    }

    @Test
    fun `alpha percent conversion clamps and round-trips common values`() {
        assertEquals(0, annotationAlphaFromPercent(-10f))
        assertEquals(255, annotationAlphaFromPercent(120f))
        for (percent in listOf(0f, 25f, 50f, 75f, 100f)) {
            val alpha = annotationAlphaFromPercent(percent)
            assertTrue(alpha in 0..255)
            assertEquals(percent, annotationAlphaPercent(annotationWithAlpha(0x00000000, alpha)), 0.51f)
        }
    }

    @Test
    fun `preset colors are opaque and unique`() {
        assertEquals(10, ANNOTATION_PRESET_COLORS.size)
        assertEquals(ANNOTATION_PRESET_COLORS.size, ANNOTATION_PRESET_COLORS.distinct().size)
        assertTrue(ANNOTATION_PRESET_COLORS.all { annotationAlpha(it) == 0xFF })
    }
}
