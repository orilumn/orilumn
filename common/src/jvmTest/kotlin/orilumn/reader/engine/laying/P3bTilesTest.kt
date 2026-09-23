package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BackgroundPosition
import orilumn.reader.engine.css.BackgroundRepeat
import orilumn.reader.engine.css.ComputedStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-b 平铺几何 guard：repeat 四模式贴图位置、定位锚点、越界/退化守卫，
 * 以及 [BoxDrawer] 背景图携带（透明承载＋未裁剪锚盒）。
 */
class P3bTilesTest {

    @Test
    fun `no-repeat 左上锚定`() {
        val ts = BackgroundTiles.tiles(0, 0, 100, 40, 10, 10, BackgroundRepeat.NO_REPEAT)
        assertEquals(listOf(BackgroundTiles.Tile(0, 0, 10, 10)), ts)
    }

    @Test
    fun `no-repeat 居中定位`() {
        val ts = BackgroundTiles.tiles(
            0, 0, 100, 40, 10, 10, BackgroundRepeat.NO_REPEAT,
            BackgroundPosition(0.5f, 0.5f),
        )
        assertEquals(listOf(BackgroundTiles.Tile(45, 15, 55, 25)), ts)
    }

    @Test
    fun `repeat 覆盖整盒`() {
        val ts = BackgroundTiles.tiles(0, 0, 20, 20, 10, 10, BackgroundRepeat.REPEAT)
        // 原点 -10 起步：3x3（含负区，调用方裁剪）。
        assertEquals(9, ts.size)
        assertEquals(BackgroundTiles.Tile(-10, -10, 0, 0), ts.first())
        assertEquals(BackgroundTiles.Tile(0, 0, 10, 10), ts[4])
    }

    @Test
    fun `repeat-x 单行定位`() {
        val ts = BackgroundTiles.tiles(
            0, 0, 25, 30, 10, 10, BackgroundRepeat.REPEAT_X,
            BackgroundPosition(0f, 1f),
        )
        assertTrue(ts.isNotEmpty())
        assertTrue("同行", ts.all { it.top == 20 && it.bottom == 30 })
        assertEquals(-10, ts.first().left)
    }

    @Test
    fun `repeat-y 单列定位`() {
        val ts = BackgroundTiles.tiles(
            0, 0, 30, 25, 10, 10, BackgroundRepeat.REPEAT_Y,
            BackgroundPosition(1f, 0f),
        )
        assertTrue(ts.isNotEmpty())
        assertTrue("同列", ts.all { it.left == 20 && it.right == 30 })
    }

    @Test
    fun `退化守卫空表`() {
        assertTrue(BackgroundTiles.tiles(0, 0, 0, 40, 10, 10, BackgroundRepeat.REPEAT).isEmpty())
        assertTrue(BackgroundTiles.tiles(0, 0, 100, 40, 0, 10, BackgroundRepeat.REPEAT).isEmpty())
    }

    @Test
    fun `BoxDrawer 无图旧路径`() {
        val style = ComputedStyle(fontSizePx = 16f, lineHeightRatio = 1.5f, backgroundColorHex = "#ffffffff")
        val box = LayoutBox(null, style, contentLeft = 0, contentWidth = 100).also {
            it.contentTop = 0
            it.contentBottom = 40
        }
        val rs = BoxDrawer.draw(listOf(box)).filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertNull(rs[0].bgImage)
    }

    @Test
    fun `BoxDrawer 有图透明承载与锚盒`() {
        val style = ComputedStyle(
            fontSizePx = 16f, lineHeightRatio = 1.5f,
            backgroundImageUrl = "t.png",
            backgroundRepeat = BackgroundRepeat.NO_REPEAT,
            backgroundPosition = BackgroundPosition(0.5f, 0.5f),
        )
        val box = LayoutBox(null, style, contentLeft = 5, contentWidth = 100).also {
            it.contentTop = 10
            it.contentBottom = 50
        }
        val rs = BoxDrawer.draw(listOf(box)).filter { it.kind == DrawKind.BACKGROUND }
        assertEquals(1, rs.size)
        assertEquals("#00000000", rs[0].colorHex)
        assertEquals(BackgroundImage("t.png", BackgroundRepeat.NO_REPEAT, BackgroundPosition(0.5f, 0.5f)), rs[0].bgImage)
        assertEquals(10, rs[0].bgBoxTop)
        assertEquals(50, rs[0].bgBoxBottom)
        assertTrue(style.hasPaintedSlab())
    }
}
