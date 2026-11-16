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
 * 卷曲的**目视自查**（不是断言，随时可删）：走**生产路径** [drawCurl3DFrame] 出图到
 * `shared-ui/build/`，把几档进度 / 半径 / 观察高度各写一张。
 */
class CurlMeshPreviewTest {

    private val pageW = 900
    private val pageH = 1300

    private fun fakePage(ink: Int, bar: Float, paperArgb: Long = 0xFFFAF6EC): ImageBitmap {
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(pageW.toFloat(), pageH.toFloat())) {
            drawRect(Color(paperArgb))
            val inkColor = Color(ink)
            var y = pageH * 0.10f
            var line = 0
            while (y < pageH * 0.92f) {
                val len = pageW * (0.80f - ((line * 37) % 17) / 100f)
                drawRect(inkColor, topLeft = Offset(pageW * 0.10f, y), size = Size(len, pageH * 0.012f))
                y += pageH * 0.035f
                line++
            }
            drawRect(inkColor, topLeft = Offset(pageW * 0.10f, pageH * 0.04f), size = Size(pageW * 0.45f * bar, pageH * 0.02f))
            // 左缘一道竖条：一眼看出镜像方向。
            drawRect(Color(0xFFB03A2E.toInt()), topLeft = Offset(pageW * 0.02f, pageH * 0.05f), size = Size(pageW * 0.02f, pageH * 0.9f))
        }
        return img
    }

    private fun dump(name: String, progress: Float, tilt: Float = 0f, radiusRatio: Float = CURL3D_RADIUS_RATIO) {
        val shape = curlShape(pageW.toFloat(), pageH.toFloat(), progress, 1, tilt, false)
        val front = CurlPageImages().apply { page = fakePage(0xFF2A2A2A.toInt(), 1f) }
        val back = CurlPageImages().apply { page = fakePage(0xFF2A2A2A.toInt(), 0.45f) }
        val img = ImageBitmap(pageW, pageH, ImageBitmapConfig.Argb8888, hasAlpha = true)
        val r = pageW * radiusRatio
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(pageW.toFloat(), pageH.toFloat())) {
            drawRect(Color(0xFFEFECE3.toInt()))
            drawCurl3DFrame(shape, front, back, 0xFFF1EEE6.toInt(), 0f, 0f, radiusRatio)
        }
        val out = File("build/curlm-$name.png")
        out.parentFile?.mkdirs()
        val data = Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG) ?: error("encode failed")
        out.writeBytes(data.bytes)
        println("wrote ${out.absolutePath} fold=${shape.topX} radius=${r}")
    }

    /** 平板上的一帧：页 1840x2800，页栅格是版心 1585x2400，放在 (128,200)。 */
    @Test
    fun dumpDeviceFrame() {
        val w = 1840
        val h = 2800
        val tw = 1585
        val th = 2400
        val tx = (w - tw) / 2f
        val ty = (h - th) / 2f
        val front = CurlPageImages().apply { page = fakePageSized(tw, th, 0xFF2A2A2A.toInt()) }
        val back = CurlPageImages().apply { page = fakePageSized(tw, th, 0xFF7A3A2E.toInt()) }
        for (dir in listOf(1, -1)) {
            for (p in listOf(0.10f, 0.20f, 0.35f, 0.45f, 0.70f, 0.90f)) {
                val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, 0f, false)
                val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
                    drawRect(Color(0xFFEFECE3.toInt()))
                    drawCurl3DFrame(shape, front, back, 0xFFF1EEE6.toInt(), tx, ty)
                }
                val name = "build/dev-${if (dir > 0) "fwd" else "bwd"}-p${(p * 100).toInt()}.png"
                val out = File(name)
                val data = Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
                out.writeBytes(data.bytes)
                val r = curlRadius(shape)
                val exposed = if (dir > 0) shape.pageW - shape.foldX - r else shape.foldX - r
                println("wrote ${out.absolutePath} fold=${shape.topX} r=$r 新页露出=${exposed / shape.pageW}")
            }
        }
        for ((p, tilt) in listOf(0.2f to 0.35f, 0.45f to -0.35f, 0.7f to 0.35f)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), p, 1, tilt, false)
            val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
                drawRect(Color(0xFFEFECE3.toInt()))
                drawCurl3DFrame(shape, front, back, 0xFFF1EEE6.toInt(), tx, ty)
            }
            val out = File("build/dev-tilt-p${(p * 100).toInt()}-t${(tilt * 100).toInt()}.png")
            val data = Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
            out.writeBytes(data.bytes)
            println("wrote ${out.absolutePath} fold=(${shape.topX},${shape.bottomX}) r=${curlRadius(shape)}")
        }
    }

    private fun fakePageSized(w: Int, h: Int, ink: Int): ImageBitmap {
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color(0xFFFFFFFF.toInt()))
            val inkColor = Color(ink)
            var y = h * 0.05f
            var line = 0
            // 行末**顶到版心右缘**（中文正文两端对齐就是这样）：版心最右一列因此带墨，
            // 越界采样一旦抹开，纸背的页边距上就会出现几条短横线。
            while (y < h * 0.95f) {
                val len = w * (0.93f - ((line * 37) % 17) / 100f)
                drawRect(inkColor, topLeft = Offset(w * 0.07f, y), size = Size(len, h * 0.008f))
                y += h * 0.022f
                line++
            }
            // 方向标记：左缘红条、页顶蓝条——卷曲后一眼看出是哪一段被压扁 / 镜像。
            drawRect(Color(0xFFC0392B.toInt()), topLeft = Offset(0f, 0f), size = Size(w * 0.02f, h.toFloat()))
            drawRect(Color(0xFF2E86C1.toInt()), topLeft = Offset(0f, 0f), size = Size(w.toFloat(), h * 0.012f))
        }
        return img
    }

    /** 真机实拍的那一帧：p=0.026、折痕 (2873,0)-(710,2800)（= tilt 拉到极限）。 */
    @Test
    fun dumpDeviceTilt() {
        val w = 1840
        val h = 2800
        val tw = 1585
        val th = 2400
        val tx = (w - tw) / 2f
        val ty = (h - th) / 2f
        val front = CurlPageImages().apply { page = fakePageSized(tw, th, 0xFF2A2A2A.toInt()) }
        val back = CurlPageImages().apply { page = fakePageSized(tw, th, 0xFF2A2A2A.toInt()) }
        for ((p, tilt) in listOf(0.026f to 0.653f, 0.026f to 0f, 0.4f to 0.653f, 0.4f to 0f)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), p, 1, tilt, false)
            println("shape p=$p tilt=$tilt top=${shape.topX} bot=${shape.bottomX} lean=${shape.lean}")
            val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
                drawRect(Color(0xFFEFECE3.toInt()))
                drawCurl3DFrame(shape, front, back, 0xFFF1EEE6.toInt(), tx, ty)
            }
            val out = File("build/devt-p${(p * 100).toInt()}-t${(tilt * 100).toInt()}.png")
            val data = Image.makeFromBitmap(img.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)!!
            out.writeBytes(data.bytes)
        }
    }

    @Test
    fun dumpFrames() {
        for (p in listOf(0.12f, 0.30f, 0.50f, 0.70f, 0.88f)) {
            dump("p${(p * 100).toInt()}", p)
        }
        dump("p50-tilt", 0.50f, tilt = -0.35f)
        dump("p50-tilt2", 0.50f, tilt = 0.35f)
    }
}
