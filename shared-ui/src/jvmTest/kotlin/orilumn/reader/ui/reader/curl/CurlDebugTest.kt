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

    private fun render(
        progress: Float,
        direction: Int,
        tilt: Float = 0f,
        name: String,
        big: Boolean = false,
        flat: Boolean = false,
        w: Int = if (big) 840 else 420,
        h: Int = if (big) 1200 else 600,
    ) {
        val effect = RuntimeEffect.makeForShader(CURL_SKSL)
        val front = if (flat) fakePage(w, h, 0xFFCC2222.toInt(), 0xFFCC2222.toInt(), 1f)
        else fakePage(w, h, 0xFFFAF6EC.toInt(), 0xFF2A2A2A.toInt(), 1f)
        val back = if (flat) fakePage(w, h, 0xFF2222CC.toInt(), 0xFF2222CC.toInt(), 0.45f)
        else fakePage(w, h, 0xFFFAF6EC.toInt(), 0xFF2A2A2A.toInt(), 0.45f)
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

    /** 分支染色图：只看「哪一块是谁画的」，不锁观感。 */
    @Test
    fun regionMap() {
        var src = CURL_SKSL
        src = src.replace("return half4(half3(base * (0.5 + 0.5 * nz)), 1.0);", "return half4(0.0, 0.8, 0.0, 1.0);") // 纸卷·纸背分支
        src = src.replace("return half4(c.rgb * (0.5 + 0.5 * nz), c.a);", "return half4(0.8, 0.0, 0.8, 1.0);") // 纸卷·正面分支
        src = src.replace("return half4(bg.rgb * (1.0 - 0.48 * shadow * k * k), bg.a);", "return half4(0.0, 0.0, 0.9, 1.0);") // 露出页+落影
        src = src.replace("return half4(mix(paper, content.rgb, 0.39), 1.0);", "return half4(0.8, 0.7, 0.0, 1.0);") // 纸背平铺段
        src = src.replace("return half4(fg.rgb * (1.0 - 0.14 * shadow * k * k), fg.a);", "return half4(0.5, 0.1, 0.1, 1.0);") // 正面页+接触影
        for (c in listOf("0.0, 0.8, 0.0", "0.8, 0.0, 0.8", "0.0, 0.0, 0.9", "0.8, 0.7, 0.0", "0.5, 0.1, 0.1")) {
            require(src.contains(c)) { "着色器分支改过了：regionMap 的染色锚点没命中（$c），去改上面的 replace" }
        }
        val effect = RuntimeEffect.makeForShader(src)
        val w = 1840
        val h = 2800
        val page = fakePage(w, h, 0xFFFAF6EC.toInt(), 0xFF2A2A2A.toInt(), 1f)
        for ((progress, tilt) in listOf(0.07984f to 0.362f, 0.20f to 0.362f, 0.35f to 0.362f, 0.22f to 0.5f)) {
            val frame = curlFrame(
                pageW = w.toFloat(), pageH = h.toFloat(),
                progress = progress, direction = 1, tilt = tilt,
                paperArgb = 0xFFF1EEE6.toInt(),
            )
            val shader: Shader = effect.makeShader(
                Data.makeFromBytes(bytes(frame.uniforms(false, 0f, 0f, w.toFloat(), h.toFloat()))),
                arrayOf<Shader?>(
                    page.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null),
                    page.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null),
                ),
                Matrix33.makeTranslate(0f, 0f),
            )
            val surface = Surface.makeRasterN32Premul(w, h)
            surface.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), Paint().apply { this.shader = shader })
            java.io.File("build/curl-regions-p" + (progress * 1000).toInt() + "-t" + (tilt * 100).toInt() + ".png")
                .writeBytes(surface.makeImageSnapshot().encodeToData()!!.bytes)
        }
    }

    @Test
    fun dumpAll() {
        render(0.15f, 1, name = "next-015")
        render(0.35f, 1, name = "next-035")
        render(0.50f, 1, name = "next-050")
        render(0.75f, 1, name = "next-075")
        render(0.35f, 1, tilt = 0.5f, name = "next-035-tilt")
        render(0.50f, -1, name = "prev-050")
        render(0.35f, 1, tilt = 0.5f, name = "next-035-tilt-big", big = true)
        render(0.45f, 1, tilt = 0.2f, name = "next-045-tilt02-big", big = true)
        render(0.45f, 1, tilt = 0.2f, name = "regions-tilt02", big = true, flat = true)
        render(0.35f, 1, tilt = 0.5f, name = "regions-tilt05", big = true, flat = true)
        // 平板实测那一帧的几何（日志 `Orilumn.Curl draw` 抄下来的），用来离线对形状。
        render(0.07984f, 1, tilt = 0.362f, name = "dev-f0798", w = 1840, h = 2800)
        render(0.07984f, 1, tilt = 0.362f, name = "dev-flat-f0798", flat = true, w = 1840, h = 2800)
        render(0.35f, 1, tilt = 0.36f, name = "dev-flat-p350", flat = true, w = 1840, h = 2800)
        // 平板实测那一帧（强倾角、折痕上端落在页宽外）：折页页角的直角边 + 卷筒截断。
        render(0.06667f, 1, tilt = 0.625f, name = "dev-t625", w = 1840, h = 2800)
        render(0.06667f, 1, tilt = 0.625f, name = "dev-flat-t625", flat = true, w = 1840, h = 2800)
        for ((pp, tt) in listOf(0.15f to 0.7f, 0.22f to 0.5f, 0.30f to 0.45f)) {
            render(pp, 1, tilt = tt, name = "dev-sweep-" + (pp * 100).toInt() + "-" + (tt * 100).toInt(), w = 1840, h = 2800)
        }
        for (pp in listOf(0.20f, 0.233f, 0.28f, 0.35f)) {
            render(pp, 1, tilt = 0.36f, name = "dev-p" + (pp * 1000).toInt(), w = 1840, h = 2800)
        }
    }
}
