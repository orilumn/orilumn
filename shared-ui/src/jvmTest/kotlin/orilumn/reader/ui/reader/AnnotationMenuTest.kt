package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

/** §3.2 浮动菜单定位：贴顶翻到下方，贴边不出内容区。 */
class AnnotationMenuTest {

    private val bounds = Rect(10f, 20f, 400f, 600f)
    private val size = IntSize(200, 40)

    @Test
    fun `menu sits above the selection when there is room`() {
        val offset = annotationMenuOffset(Rect(150f, 300f, 260f, 340f), bounds, size, gap = 8)
        assertEquals(105, offset.x)
        assertEquals(252, offset.y)
    }

    @Test
    fun `menu flips below when the selection is near the top`() {
        val offset = annotationMenuOffset(Rect(150f, 30f, 260f, 60f), bounds, size, gap = 8)
        assertEquals(68, offset.y)
    }

    @Test
    fun `menu is clamped inside the content bounds`() {
        val left = annotationMenuOffset(Rect(10f, 300f, 40f, 340f), bounds, size, gap = 8)
        assertEquals(10, left.x)
        val right = annotationMenuOffset(Rect(380f, 300f, 400f, 340f), bounds, size, gap = 8)
        assertEquals(200, right.x)
    }
}
