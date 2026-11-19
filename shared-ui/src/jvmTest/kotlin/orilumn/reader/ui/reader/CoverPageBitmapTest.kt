package orilumn.reader.ui.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 封面烤图的判据（见 [bakeCoverPageBitmap]）：
 *
 * 1) 图**恒为页大小**——卷曲层的纹理坐标是页坐标，烤成版心大小会让封面四周多出一圈纸边；
 * 2) 拉伸（默认）铺满整页，与封面页的 `FillBounds` 同一像素；
 * 3) 等比按短边缩放后居中，四周露底色，与封面页的 `Fit` 同一像素。
 */
class CoverPageBitmapTest {

    private val bg = 0xFFF1EEE6.toInt()
    private val ink = 0xFF3366CC.toInt()

    private fun solid(w: Int, h: Int, argb: Int): ImageBitmap {
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color(argb))
        }
        return img
    }

    @Test
    fun bakedBitmapIsPageSized() {
        val out = bakeCoverPageBitmap(solid(40, 60, ink), 200, 300, proportional = false, bgArgb = bg)
        assertEquals("宽度必须是页宽", 200, out.width)
        assertEquals("高度必须是页高", 300, out.height)
    }

    @Test
    fun stretchedCoverFillsTheWholePage() {
        val px = bakeCoverPageBitmap(solid(40, 60, ink), 200, 300, proportional = false, bgArgb = bg).toPixelMap()
        for (x in listOf(0, 3, 100, 196, 199)) {
            for (y in listOf(0, 3, 150, 296, 299)) {
                assertEquals("拉伸应当铺满整页（$x,$y 不该露底色）", ink, px[x, y].toArgb())
            }
        }
    }

    @Test
    fun proportionalCoverKeepsAspectAndPadsWithBackground() {
        val px = bakeCoverPageBitmap(solid(100, 100, ink), 200, 400, proportional = true, bgArgb = bg).toPixelMap()
        assertEquals("等比时上下应当露底色", bg, px[100, 2].toArgb())
        assertEquals("同比时上下应当露底色", bg, px[100, 397].toArgb())
        assertEquals("等比按短边缩放后左右贴边", ink, px[1, 200].toArgb())
        assertEquals("等比按短边缩放后左右贴边", ink, px[198, 200].toArgb())
        assertEquals("中间是封面", ink, px[100, 200].toArgb())
        // 整幅不透明：周围补的是底色，不是透明像素（否则卷起来会透出下一张纸）。
        var transparent = 0
        for (x in 0 until 200 step 7) {
            for (y in 0 until 400 step 11) {
                if ((px[x, y].toArgb() ushr 24) != 0xFF) transparent++
            }
        }
        assertEquals("烤图里不该有透明像素", 0, transparent)
    }
}
