package orilumn.reader.ui.reader.curl

import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Data
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.junit.Test

/**
 * 视觉调试（不锁行为）：拿「像真书页」的纹理渲染几帧，落盘到 build/ 肉眼看曲率/明暗/镜像是否对。
 * 不是基准测试，随时可删。
 */
class CurlDebugTest {

    private fun bytes(u: FloatArray): ByteArray {
        val out = ByteArray(u.size * 4)
        for (i in u.indices) {
            val bits = u[i].toBits()
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    /** 假书页：米色底 + 一行行「字」 + 页边距 + 页码条，方便看压缩/拉伸。 */
    private fun fakePage(w: Int, h: Int, bg: Int, ink: Int, tag: Float): Image {
        val surface = Surface.makeRasterN32Premul(w, h)
        val c: Canvas = surface.canvas
        c.clear(bg)
        val p = Paint().apply { color = ink }
        var y = (h * 0.08f)
        var line = 0
        while (y < h * 0.92f) {
            val inset = w * 0.10f
            // 每段的长度抖一下，卷曲压缩时更容易看出来
            val len = w * (0.80f - ((line * 37) % 17) / 100f)
            c.drawRect(Rect.makeXYWH(inset, y, len, h * 0.012f), p)
            line++
            y += h * 0.035f
        }
        // 顶部标题条 + 底部页码，用来认「这是哪一页 / 镜像是否反了」
        c.drawRect(Rect.makeXYWH(w * 0.10f, h * 0.04f, w * 0.45f * tag, h * 0.02f), p)
        c.drawRect(Rect.makeXYWH(w * 0.10f, h * 0.955f, w * 0.12f, h * 0.018f), p)
        return surface.makeImageSnapshot()
    }

    private fun render(progress: Float, direction: Int, tilt: Float = 0f, name: String) {
        val w = 420
        val h = 600
        val effect = RuntimeEffect.makeForShader(CURL_SKSL)
        val front = fakePage(w, h, 0xFFFAF6EC.toInt(), 0xFF2A2A2A.toInt(), 1f)
        val back = fakePage(w, h, 0xFFFAF6EC.toInt(), 0xFF2A2A2A.toInt(), 0.45f)
        val frame = curlFrame(
            pageW = w.toFloat(), pageH = h.toFloat(),
            progress = progress, direction = direction, tilt = tilt,
            paperArgb = 0xFFF1EEE6.toInt(),
        )
        println("=== $name p=$progress dir=$direction top=${frame.topX}/${frame.bottomX} n=(${frame.normalX},${frame.normalY})")
        // 纹理 1:1（不挂 local matrix）：与两端后端同一约定，见 [CURL_SKSL] 文件头。
        val shader: Shader = effect.makeShader(
            Data.makeFromBytes(bytes(frame.uniforms(false, 0f, 0f, w.toFloat(), h.toFloat()))),
            arrayOf<Shader?>(
                front.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null),
                back.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null),
            ),
            Matrix33.makeTranslate(0f, 0f),
        )
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), Paint().apply { this.shader = shader })
        val shot = surface.makeImageSnapshot()
        java.io.File("build/curl-$name.png").writeBytes(shot.encodeToData()!!.bytes)
    }

    @Test
    fun dumpAll() {
        render(0.15f, 1, name = "next-015")
        render(0.35f, 1, name = "next-035")
        render(0.50f, 1, name = "next-050")
        render(0.75f, 1, name = "next-075")
        render(0.35f, 1, tilt = 0.5f, name = "next-035-tilt")
        render(0.50f, -1, name = "prev-050")
    }
}
