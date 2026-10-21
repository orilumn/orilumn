package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 放大镜几何锁：圆心优先在手指上方、贴边内移、贴顶翻到下方。
 *
 * 这些不是像素细节，而是「手指把字挡住」与「圆被屏幕裁掉一半」两类只能靠真机发现的问题；
 * 抽成纯函数后在本机即可锁死。
 */
class SelectionMagnifierTest {

    private val bounds = Rect(0f, 0f, 1000f, 800f)
    private val radius = 48f
    private val offset = 56f
    private val inset = 8f

    @Test
    fun `center sits above the finger when there is room`() {
        val c = magnifierCenter(Offset(500f, 400f), bounds, radius, offset, inset)
        assertEquals(500f, c.x, 0.001f)
        assertEquals(344f, c.y, 0.001f)
    }

    @Test
    fun `left and right edges pull the circle inside`() {
        val left = magnifierCenter(Offset(2f, 400f), bounds, radius, offset, inset)
        val right = magnifierCenter(Offset(998f, 400f), bounds, radius, offset, inset)
        assertEquals(56f, left.x, 0.001f)
        assertEquals(944f, right.x, 0.001f)
    }

    @Test
    fun `near the top the circle flips below the finger`() {
        val c = magnifierCenter(Offset(500f, 20f), bounds, radius, offset, inset)
        assertEquals(76f, c.y, 0.001f)
    }

    @Test
    fun `near the bottom the circle stays inside`() {
        val c = magnifierCenter(Offset(500f, 850f), bounds, radius, offset, inset)
        assertEquals(744f, c.y, 0.001f)
    }

    @Test
    fun `bounds smaller than the circle center the circle`() {
        val small = Rect(0f, 0f, 50f, 40f)
        val c = magnifierCenter(Offset(10f, 10f), small, radius, offset, inset)
        assertEquals(25f, c.x, 0.001f)
        assertEquals(20f, c.y, 0.001f)
    }

    @Test
    fun `translation maps the finger point to the circle center`() {
        val center = Offset(500f, 300f)
        val finger = Offset(420f, 610f)
        val t = magnifierTranslation(center, finger, MAGNIFIER_ZOOM)
        assertEquals(center.x, MAGNIFIER_ZOOM * finger.x + t.x, 0.001f)
        assertEquals(center.y, MAGNIFIER_ZOOM * finger.y + t.y, 0.001f)
    }
}
