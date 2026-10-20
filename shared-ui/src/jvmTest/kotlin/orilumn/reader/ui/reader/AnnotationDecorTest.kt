package orilumn.reader.ui.reader

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.engine.skia.DecorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 标注 → 渲染装饰的用户层映射：类型、颜色快照、批注跳过、区间不改。 */
class AnnotationDecorTest {

    private fun annotation(
        kind: AnnotationKind,
        color: Int? = null,
        start: Int = 3,
        end: Int = 7,
    ) = Annotation(
        bookId = 1,
        chapter = 2,
        charStart = start,
        charEnd = end,
        kind = kind,
        color = color,
    )

    @Test
    fun `highlight and underline map to their decoration kinds`() {
        val ranges = annotationDecorRanges(
            listOf(
                annotation(AnnotationKind.Highlight, 0x80FF0000.toInt()),
                annotation(AnnotationKind.Underline, 0xFF0000FF.toInt()),
            ),
        )
        assertEquals(2, ranges.size)
        assertEquals(DecorKind.BACKGROUND, ranges[0].kind)
        assertEquals(0x80FF0000.toInt(), ranges[0].argb)
        assertEquals(3, ranges[0].start)
        assertEquals(7, ranges[0].endExclusive)
        assertEquals(DecorKind.UNDERLINE, ranges[1].kind)
        assertEquals(0xFF0000FF.toInt(), ranges[1].argb)
    }

    @Test
    fun `missing color falls back to the default slot`() {
        val ranges = annotationDecorRanges(
            listOf(
                annotation(AnnotationKind.Highlight),
                annotation(AnnotationKind.Underline),
            ),
        )
        assertEquals(DEFAULT_HIGHLIGHT_ARGB, ranges[0].argb)
        assertEquals(DEFAULT_UNDERLINE_ARGB, ranges[1].argb)
        // 默认高亮必须带透明度，否则会盖住文字。
        assertTrue((DEFAULT_HIGHLIGHT_ARGB ushr 24) in 1..254)
    }

    @Test
    fun `note is not drawn in v1`() {
        assertTrue(annotationDecorRanges(listOf(annotation(AnnotationKind.Note))).isEmpty())
    }

    @Test
    fun `annotation without color is still drawable`() {
        val a = annotation(AnnotationKind.Highlight)
        assertNull(a.color)
        assertEquals(1, annotationDecorRanges(listOf(a)).size)
    }
}
