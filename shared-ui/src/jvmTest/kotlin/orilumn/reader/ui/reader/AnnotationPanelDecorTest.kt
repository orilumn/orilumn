package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.toArgb
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 笔记清单里的摘录装饰必须与正文那一处**同一份投影**：同样的类别（含删除线换落墨位置）、
 * 同样的颜色快照（含缺色回退）、批注同样不落墨。面板自己另判一套口径就会两边不一致。
 */
class AnnotationPanelDecorTest {

    private fun annotation(
        kind: AnnotationKind,
        color: Int? = null,
        lineStyle: AnnotationLineStyle = AnnotationLineStyle.Straight,
    ) = Annotation(
        bookId = 1,
        chapter = 0,
        charStart = 0,
        charEnd = 4,
        kind = kind,
        color = color,
        lineStyle = lineStyle,
    )

    @Test
    fun `panel decoration matches the body projection`() {
        val cases = listOf(
            annotation(AnnotationKind.Highlight, 0x80FF0000.toInt()),
            annotation(AnnotationKind.Highlight),
            annotation(AnnotationKind.Underline, 0xFF112233.toInt()),
            annotation(AnnotationKind.Underline, 0xFF112233.toInt(), AnnotationLineStyle.Wavy),
            annotation(AnnotationKind.Underline, 0xFF112233.toInt(), AnnotationLineStyle.Dashed),
            annotation(AnnotationKind.Underline, 0xFF112233.toInt(), AnnotationLineStyle.Strike),
            annotation(AnnotationKind.Note, 0xFF112233.toInt(), AnnotationLineStyle.Wavy),
        )
        for (a in cases) {
            val body = annotationDecorRanges(listOf(a)).firstOrNull()
            val panel = quoteDecorOf(a)
            assertEquals("kind: $a", body?.kind, panel?.kind)
            assertEquals("stroke: $a", body?.stroke, panel?.stroke)
            assertEquals("color: $a", body?.argb, panel?.color?.toArgb())
        }
    }

    @Test
    fun `note has no decoration in panel`() {
        assertNull(quoteDecorOf(annotation(AnnotationKind.Note)))
    }
}
