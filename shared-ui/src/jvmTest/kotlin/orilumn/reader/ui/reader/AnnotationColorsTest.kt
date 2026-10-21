package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
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
    fun `last used line style falls back to straight`() {
        assertEquals(AnnotationLineStyle.Straight, annotationLastLineStyleOf(ReaderSettings.DEFAULT))
        listOf(
            AnnotationLineStyle.Wavy,
            AnnotationLineStyle.Dashed,
            AnnotationLineStyle.Strike,
        ).forEach { style ->
            assertEquals(
                style,
                annotationLastLineStyleOf(ReaderSettings.DEFAULT.copy(annotationLastLineStyle = style.wire)),
            )
        }
        // 未知值（老存档缺键 / 手改配置）按实线。
        assertEquals(
            AnnotationLineStyle.Straight,
            annotationLastLineStyleOf(ReaderSettings.DEFAULT.copy(annotationLastLineStyle = "dotted")),
        )
    }

    @Test
    fun `last used kind falls back to highlight`() {
        assertEquals(AnnotationKind.Highlight, annotationLastKindOf(ReaderSettings.DEFAULT))
        assertEquals(
            AnnotationKind.Underline,
            annotationLastKindOf(ReaderSettings.DEFAULT.copy(annotationLastKind = "underline")),
        )
        // 未知值（老存档缺键 / 手改配置 / 批注类）一律按高亮，菜单不会停在没有色槽的类别上。
        assertEquals(
            AnnotationKind.Highlight,
            annotationLastKindOf(ReaderSettings.DEFAULT.copy(annotationLastKind = "note")),
        )
    }

    @Test
    fun `swatch of each kind ignores the other kind`() {
        val highlight = ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS
        val underline = ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS
        // 换另一类的色槽与默认格，本类样张纹丝不动。
        assertEquals(
            highlight[0],
            annotationSwatchColor(highlight, 0, underline, 3, AnnotationKind.Highlight, null),
        )
        assertEquals(
            underline[3],
            annotationSwatchColor(highlight, 4, underline, 3, AnnotationKind.Underline, null),
        )
    }

    @Test
    fun `swatch follows only a record of the same kind`() {
        val highlight = ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS
        val underline = ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS
        val mark = Annotation(
            bookId = 1,
            chapter = 0,
            charStart = 0,
            charEnd = 3,
            kind = AnnotationKind.Highlight,
            color = 0x66FF0000,
        )
        // 编辑高亮：高亮样张跟着它；划线样张仍是划线默认色（不能借用高亮的底色）。
        assertEquals(0x66FF0000, annotationSwatchColor(highlight, 0, underline, 1, AnnotationKind.Highlight, mark))
        assertEquals(underline[1], annotationSwatchColor(highlight, 0, underline, 1, AnnotationKind.Underline, mark))
    }

    @Test
    fun `highlight to underline conversion never carries the highlight color`() {
        val s = ReaderSettings.DEFAULT.copy(underlineDefaultSlot = 2)
        val highlight = Annotation(
            bookId = 1,
            chapter = 0,
            charStart = 0,
            charEnd = 3,
            kind = AnnotationKind.Highlight,
            color = 0x73FFD54F,
        )
        val underline = highlight.copy(kind = AnnotationKind.Underline, color = 0xFF1E88E5.toInt())
        assertEquals(annotationDefaultColor(s, AnnotationKind.Underline), annotationUnderlineInkColor(highlight, s))
        assertEquals(0xFF1E88E5.toInt(), annotationUnderlineInkColor(underline, s))
        assertEquals(annotationDefaultColor(s, AnnotationKind.Underline), annotationUnderlineInkColor(null, s))
    }

    @Test
    fun `line style is only stored on underlines`() {
        assertEquals(AnnotationLineStyle.Wavy, annotationLineStyleFor(AnnotationKind.Underline, AnnotationLineStyle.Wavy))
        // 高亮不画线：存进去也没意义，还会污染划线菜单的初始值。
        assertEquals(AnnotationLineStyle.Straight, annotationLineStyleFor(AnnotationKind.Highlight, AnnotationLineStyle.Wavy))
        assertEquals(AnnotationLineStyle.Straight, annotationLineStyleFor(AnnotationKind.Note, AnnotationLineStyle.Dashed))
    }

    @Test
    fun `picking a line style also remembers underline as last kind`() {
        val s = ReaderSettings.DEFAULT.copy(annotationLastKind = "highlight")
        val next = withAnnotationLastLineStyle(s, AnnotationLineStyle.Dashed)
        assertEquals(AnnotationKind.Underline, annotationLastKindOf(next))
        assertEquals(AnnotationLineStyle.Dashed, annotationLastLineStyleOf(next))
        // 点线型不该动颜色槽与默认格。
        assertEquals(s.highlightDefaultSlot, next.highlightDefaultSlot)
        assertEquals(s.underlineDefaultSlot, next.underlineDefaultSlot)
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
    fun `menu swatch keeps highlight and underline slots independent`() {
        val highlight = listOf(0x33FF0000.toInt(), 0x3300FF00, 0x330000FF, 0x33FFFF00, 0x33FF00FF.toInt())
        val underline = listOf(0xFF112233.toInt(), 0xFF445566.toInt(), 0xFF778899.toInt())
        // 刚在高亮行点了第 5 格（高亮默认槽 = 4），划线默认槽仍是第 2 格。
        assertEquals(
            underline[1],
            annotationSwatchColor(highlight, 4, underline, 1, AnnotationKind.Underline, null),
        )
        assertEquals(
            highlight[4],
            annotationSwatchColor(highlight, 4, underline, 1, AnnotationKind.Highlight, null),
        )
    }

    @Test
    fun `editing one kind never paints the other kind's swatch`() {
        val highlight = listOf(0x33FF0000.toInt(), 0x3300FF00)
        val underline = listOf(0xFF112233.toInt(), 0xFF445566.toInt())
        val editingHighlight = Annotation(
            bookId = 1,
            chapter = 0,
            charStart = 0,
            charEnd = 4,
            kind = AnnotationKind.Highlight,
            color = 0x3300FF00,
        )
        // 正在编辑高亮：划线样张仍取划线默认槽，不跟着高亮的半透明底色走。
        assertEquals(
            underline[0],
            annotationSwatchColor(highlight, 1, underline, 0, AnnotationKind.Underline, editingHighlight),
        )
        // 编辑本类别时跟着记录自己的颜色。
        assertEquals(
            0x3300FF00,
            annotationSwatchColor(highlight, 1, underline, 0, AnnotationKind.Highlight, editingHighlight),
        )
        // 编辑划线时同理，且越界槽位钳到有效范围。
        val editingUnderline = editingHighlight.copy(kind = AnnotationKind.Underline, color = underline[1])
        assertEquals(
            underline[1],
            annotationSwatchColor(highlight, 9, underline, 9, AnnotationKind.Underline, editingUnderline),
        )
        assertEquals(
            highlight[1],
            annotationSwatchColor(highlight, 9, underline, 9, AnnotationKind.Highlight, editingUnderline),
        )
    }

    @Test
    fun `preset colors are opaque and unique`() {
        assertEquals(10, ANNOTATION_PRESET_COLORS.size)
        assertEquals(ANNOTATION_PRESET_COLORS.size, ANNOTATION_PRESET_COLORS.distinct().size)
        assertTrue(ANNOTATION_PRESET_COLORS.all { annotationAlpha(it) == 0xFF })
    }
}
