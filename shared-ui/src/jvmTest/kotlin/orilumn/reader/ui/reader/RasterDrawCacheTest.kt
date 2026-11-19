package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * [RasterDrawCache] 的失效判据：**实例相同 ≠ 像素相同**。
 *
 * 覆盖层（旁注 / 页码）那张整页位图在尺寸不变时是原地重画的（见 `PageOverlayLayer`），
 * 桌面贴图缓存早先只看位图实例 ⇒ 一直贴第一版的 skia 快照。症状：静态页看不到批注，
 * 动画里每页刷的都是同一页的批注。这里把「代数变了必须换快照」锁住。
 */
class RasterDrawCacheTest {

    private fun bitmap(w: Int = 32, h: Int = 48) =
        ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)

    /** 在真实的 [CanvasDrawScope] 里走一遍桌面 actual（与翻页帧同一条绘制路径）。 */
    private fun draw(cache: RasterDrawCache, texture: RasterTexture) {
        val canvas = Canvas(texture.image)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = canvas,
            size = Size(texture.image.width.toFloat(), texture.image.height.toFloat()),
        ) {
            drawRasterCached(cache, texture, Offset.Zero, 1f)
        }
    }

    @Test
    fun `同一张位图同一代数复用画柄`() {
        val cache = RasterDrawCache()
        val texture = RasterTexture(bitmap())
        draw(cache, texture)
        val first = cache.handle
        draw(cache, RasterTexture(texture.image, 0))
        assertSame("像素没换就该复用同一份 skia 快照", first, cache.handle)
    }

    @Test
    fun `同一张位图换了代数必须重做快照——批注原地重画就靠这条`() {
        val cache = RasterDrawCache()
        val image = bitmap()
        draw(cache, RasterTexture(image, 0))
        val first = cache.handle
        draw(cache, RasterTexture(image, 1))
        assertSame("判废依据是实例，实例本来就该是同一张", image, cache.source)
        assertNotSame("代数变了还复用快照 ⇒ 屏幕上是上一版批注", first, cache.handle)
        assertEquals(1, cache.generation)
    }

    @Test
    fun `换了位图实例即使代数相同也重做快照`() {
        val cache = RasterDrawCache()
        draw(cache, RasterTexture(bitmap()))
        val first = cache.handle
        draw(cache, RasterTexture(bitmap()))
        assertNotSame(first, cache.handle)
    }

    /**
     * 覆盖层代数的生产者一侧：键不变不重画（代数不动），键变了在**同一张位图**上重画
     * （代数必须 +1），且出口是「图 + 代数」的强捆绑。后者正是上面那条失效判据要接住的情形。
     */
    @Test
    fun `覆盖层原地重画后代数递增`() {
        val layer = PageOverlayLayer()
        val style = PageOverlayStyle(
            fgArgb = 0xFF202020.toInt(),
            pageBgArgb = 0xFFFFFFFF.toInt(),
            baseStyle = TextStyle.Default,
            fontFamily = null,
            fontWeight = null,
            showBorder = true,
            showBackground = true,
            textSameColor = false,
        )
        val content = PageOverlayContent(notes = emptyList(), style = style, pageNumber = null)
        val key = listOf(content, 32, 48, 1f)

        val first = layer.obtain(key, 32, 48, Density(1f), LayoutDirection.Ltr) {}
        assertEquals(1, first?.generation)
        val again = layer.obtain(key, 32, 48, Density(1f), LayoutDirection.Ltr) {}
        assertSame("键不变必须命中缓存，不重画", first?.image, again?.image)
        assertEquals("没重画就不该动代数", 1, again?.generation)

        val redrawn = layer.obtain(listOf(content, 32, 48, 2f), 32, 48, Density(1f), LayoutDirection.Ltr) {}
        assertSame("尺寸不变要复用同一张位图原地重画（整页 20MB 级，不能每步新分配）", first?.image, redrawn?.image)
        assertEquals("像素已经换了，代数必须 +1", 2, redrawn?.generation)
    }
}
