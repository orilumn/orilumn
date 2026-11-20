package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import orilumn.reader.ui.reader.RasterTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖层（旁注 / 页码）在卷曲**生产路径**上的换代：读者报过「每一页的纸背都带着同一张批注」。
 *
 * 根因是网格纹理的 `ImageShader` 只认位图实例（建 shader 时像素已被拷成快照），而覆盖层
 * 位图会在原地重画。这里直接走 [drawCurl3DFrame]，把同一张位图从红色整页改成蓝色整页、
 * 代数 +1，再断言整帧里**一个红像素都不剩**——纸背也用的是新版。
 */
class Curl3DOverlayTest {

    private val paper = 0xFFF1EEE6.toInt()
    private val pageW = 400
    private val pageH = 600

    private fun bitmap(argb: Int = 0xFFFFFFFF.toInt()): ImageBitmap {
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        fill(img, argb)
        return img
    }

    /** 原地重画（与 `PageOverlayLayer` 同口径：先清成透明，再画新内容）。 */
    private fun fill(img: ImageBitmap, argb: Int) {
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(img),
            size = Size(img.width.toFloat(), img.height.toFloat()),
        ) {
            drawRect(Color.Transparent, size = size, blendMode = BlendMode.Clear)
            drawRect(Color(argb), size = size)
        }
    }

    private fun render(front: CurlPageImages, back: CurlPageImages): ImageBitmap {
        val shape = curlShape(pageW.toFloat(), pageH.toFloat(), progress = 0.5f, direction = 1)
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(img),
            size = Size(pageW.toFloat(), pageH.toFloat()),
        ) {
            drawCurl3DFrame(shape, front, back, paper, Offset.Zero, Offset.Zero)
        }
        return img
    }

    private fun redPixels(px: androidx.compose.ui.graphics.PixelMap): Int {
        var n = 0
        for (y in 0 until pageH step 2) {
            for (x in 0 until pageW step 2) {
                val argb = px[x, y].toArgb()
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r > 180 && g < 120 && b < 120) n++
            }
        }
        return n
    }

    private fun bluePixels(px: androidx.compose.ui.graphics.PixelMap): Int {
        var n = 0
        for (y in 0 until pageH step 2) {
            for (x in 0 until pageW step 2) {
                val argb = px[x, y].toArgb()
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (b > 180 && r < 120 && g < 120) n++
            }
        }
        return n
    }

    @Test
    fun `覆盖层原地重画后纸背也换版——不许再贴上一页的批注`() {
        val front = CurlPageImages().apply { page = RasterTexture(bitmap()) }
        val back = CurlPageImages().apply { page = RasterTexture(bitmap()) }
        val overlay = bitmap(0xFFFF0000.toInt())
        front.overlay = RasterTexture(overlay, generation = 0)

        val first = render(front, back).toPixelMap()
        val redBefore = redPixels(first)
        assertTrue("前提：这一帧的纸背/正面应当看得到红色覆盖层，实测 $redBefore", redBefore > 500)

        // 同一张位图原地重画成蓝色，代数 +1。
        fill(overlay, 0xFF0000FF.toInt())
        front.overlay = RasterTexture(overlay, generation = 1)

        val second = render(front, back).toPixelMap()
        assertEquals(
            "代数变了还留着旧纹理画笔 ⇒ 纸背仍是上一版的红色批注",
            0,
            redPixels(second),
        )
        assertTrue("新版覆盖层应当已经贴上", bluePixels(second) > 500)
    }

    /**
     * 覆盖层**透明底**不许把卷起来的纸刷白（读者原话「反面没有文字，只有批注」）。
     *
     * 曾经覆盖层是网格上的第二遍 `drawVertices`：桌面端会把透明纹素抬成顶点色（白），
     * 于是整张卷起来的纸变成纯白，只剩旁注还看得见。现在覆盖层与页栅格先合成一张整页图。
     */
    @Test
    fun `透明覆盖层不许把纸刷白——纸背仍有镜像正文`() {
        val front = CurlPageImages().apply { page = RasterTexture(bitmap(0xFFCC2222.toInt())) }
        val back = CurlPageImages().apply { page = RasterTexture(bitmap(0xFF2222CC.toInt())) }
        // 透明底 + 右留白里一小块旁注（纸背会把它镜像到左边）。
        val overlay = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(overlay),
            size = Size(pageW.toFloat(), pageH.toFloat()),
        ) {
            drawRect(Color(0xFF1E88E5.toInt()), topLeft = Offset(pageW - 40f, 100f), size = Size(30f, 80f))
        }
        front.overlay = RasterTexture(overlay)

        val px = render(front, back).toPixelMap()
        var white = 0
        var redOnBack = 0
        var noteOnBack = 0
        for (y in 0 until pageH step 2) {
            for (x in 0 until pageW step 2) {
                val argb = px[x, y].toArgb()
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                if (r > 250 && g > 250 && b > 250) white++
                // 纸背在折痕左边：正文是压了一成纸色的红（约 d0..），旁注是那块蓝。
                if (x < pageW / 2 && r > 150 && g < 120 && b < 120) redOnBack++
                if (x < pageW / 2 && b > 180 && r < 120) noteOnBack++
            }
        }
        assertEquals("覆盖层的透明像素把纸刷成了白色", 0, white)
        assertTrue("纸背上应当看得到镜像正文，实测 $redOnBack 个像素", redOnBack > 200)
        assertTrue("纸背上应当看得到镜像旁注，实测 $noteOnBack 个像素", noteOnBack > 5)
    }
}
