package orilumn.reader.ui.reader.curl

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import orilumn.reader.ui.reader.RasterTexture
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 卷曲纹理画笔（`ImageShader`）的失效判据。
 *
 * `ImageShader` 在创建时就把位图像素拷进一份快照，所以「画笔还留着」等于「贴的还是那一版像素」。
 * 覆盖层位图会原地重画（见 `PageOverlayLayer`），只看实例会让读者在**每一页的背面**看到
 * 第一次建 shader 那一页的批注 —— 这是读者报过的「固定批注跟着纸跑」。
 */
class CurlTextureCacheTest {

    private fun bitmap() = ImageBitmap(8, 8, ImageBitmapConfig.Argb8888, hasAlpha = true)

    @Test
    fun `同一版像素复用纹理画笔`() {
        val cache = CurlTextureCache()
        val texture = RasterTexture(bitmap(), 0)
        val first = cache.overlayPaint(texture)
        assertSame(first, cache.overlayPaint(RasterTexture(texture.image, 0)))
        val page = cache.pagePaint(RasterTexture(texture.image, 0))
        assertSame(page, cache.pagePaint(RasterTexture(texture.image, 0)))
    }

    @Test
    fun `覆盖层原地重画后纹理画笔必须重建`() {
        val cache = CurlTextureCache()
        val image = bitmap()
        val first = cache.overlayPaint(RasterTexture(image, 0))
        val second = cache.overlayPaint(RasterTexture(image, 1))
        assertNotSame("同一张位图、像素换了，还复用旧 shader 就是上一页的批注", first, second)
    }

    @Test
    fun `页栅格换了实例必须重建纹理画笔`() {
        val cache = CurlTextureCache()
        val first = cache.pagePaint(RasterTexture(bitmap(), 0))
        val second = cache.pagePaint(RasterTexture(bitmap(), 0))
        assertNotSame(first, second)
    }
}
