package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Test
import java.io.File

/**
 * 折页的**目视自查**（不是断言，随时可删）：把几帧写成 PNG 到 `shared-ui/build/`，
 * 人眼确认形状对不对（纸背镜像有没有反、阴影是不是顺着纸的轮廓、收尾那几帧连不连续）。
 */
class CurlPreviewTest {

    private val pageW = 900
    private val pageH = 1300

    /** 假书页：米色底 + 一行行「字」 + 标题条，方便看镜像方向与 1:1 比例。 */
    private fun fakePage(ink: Int, bar: Float): ImageBitmap {
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(pageW.toFloat(), pageH.toFloat())) {
            drawRect(Color(0xFFFAF6EC.toInt()))
            val inkColor = Color(ink)
            var y = pageH * 0.10f
            var line = 0
            while (y < pageH * 0.92f) {
                val len = pageW * (0.80f - ((line * 37) % 17) / 100f)
                drawRect(inkColor, topLeft = Offset(pageW * 0.10f, y), size = Size(len, pageH * 0.012f))
                y += pageH * 0.035f
                line++
            }
            // 顶部标题条的长度随页不同：一眼看出「这是哪一页」。
            drawRect(inkColor, topLeft = Offset(pageW * 0.10f, pageH * 0.04f), size = Size(pageW * 0.45f * bar, pageH * 0.02f))
        }
        return img
    }

    private fun images(page: ImageBitmap) = CurlPageImages().apply { this.page = page }

    private fun dump(name: String, progress: Float, direction: Int, tilt: Float = 0f) {
        val shape = curlShape(pageW.toFloat(), pageH.toFloat(), progress, direction, tilt)
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(pageW.toFloat(), pageH.toFloat())) {
            drawCurlFrame(
                shape = shape,
                front = images(fakePage(0xFF2A2A2A.toInt(), 1f)),
                back = images(fakePage(0xFF2A2A2A.toInt(), 0.45f)),
                paperArgb = 0xFFF1EEE6.toInt(),
                frontTex = Offset.Zero,
                backTex = Offset.Zero,
            )
        }
        val out = File("build/curl2d-$name.png")
        out.parentFile?.mkdirs()
        val data = Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
            ?: error("encode failed")
        out.writeBytes(data.bytes)
        println("wrote ${out.absolutePath}  fold=${shape.topX}..${shape.bottomX}")
    }

    @Test
    fun dumpFrames() {
        dump("next-015", 0.15f, 1)
        dump("next-035", 0.35f, 1)
        dump("next-050", 0.50f, 1)
        dump("next-075", 0.75f, 1)
        dump("next-035-tilt", 0.35f, 1, tilt = 0.5f)
        dump("next-035-tilt-neg", 0.35f, 1, tilt = -0.5f)
        dump("prev-050", 0.50f, -1)
        dump("prev-050-tilt", 0.50f, -1, tilt = 0.5f)
        // 收尾那几帧（结算里扶正 + 阴影淡出）：相邻两帧之间不该有跳变，p = 1 等于目标页。
        for (p in listOf(0.85f, 0.92f, 0.96f, 0.99f, 1f)) {
            dump("tail-" + (p * 100).toInt(), p, 1, tilt = 0.55f)
        }
        // 上一页面起手（p → 0，折痕同样贴在书脊）。
        dump("head-prev", 0.02f, -1, tilt = 0.5f)
    }
}
