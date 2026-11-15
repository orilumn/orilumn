package orilumn.reader.ui.reader.curl

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
import java.io.File

/**
 * 两遍合成（页栅格 + 覆盖层）的目视自查：把卷曲一帧渲染成 PNG，人眼看
 * 「旁注在正面页上、纸背没有旁注、露出页有目标页的旁注」。
 */
class CurlCompositePreviewTest {

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

    /** 假页栅格：白底 + 深灰横线（模拟正文行）。 */
    private fun fakePage(w: Int, h: Int, ink: Int): Image {
        val s = Surface.makeRasterN32Premul(w, h)
        s.canvas.clear(0xFFFFFFFF.toInt())
        val p = Paint().apply { color = ink }
        var y = 20f
        while (y < h - 10) {
            s.canvas.drawRect(Rect.makeXYWH(10f, y, w - 20f, 6f), p)
            y += 26f
        }
        return s.makeImageSnapshot()
    }

    /** 假覆盖层：透明底 + 一块半透明标底色（模拟旁注框）+ 底部页码点。 */
    private fun fakeOverlay(w: Int, h: Int, tint: Int): Image {
        val s = Surface.makeRasterN32Premul(w, h)
        s.canvas.clear(0x00000000)
        val box = Paint().apply { color = (tint and 0x00FFFFFF) or 0x60000000 }
        s.canvas.drawRect(Rect.makeXYWH(w - 120f, 60f, 90f, 70f), box)
        val dot = Paint().apply { color = 0x80000000.toInt() }
        s.canvas.drawRect(Rect.makeXYWH(w - 90f, h - 30f, 60f, 10f), dot)
        return s.makeImageSnapshot()
    }

    private fun pass(
        effect: RuntimeEffect,
        frame: CurlFrame,
        front: Image,
        back: Image,
        overlay: Boolean,
        texX: Float,
        texY: Float,
        texW: Float,
        texH: Float,
    ): Paint {
        val shader: Shader = effect.makeShader(
            Data.makeFromBytes(bytes(frame.uniforms(overlay, texX, texY, texW, texH))),
            arrayOf<Shader?>(
                front.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null),
                back.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null),
            ),
            Matrix33.makeTranslate(0f, 0f),
        )
        return Paint().apply { this.shader = shader }
    }

    @Test
    fun renderComposite() {
        val w = 400
        val h = 600
        val cl = 30f
        val ct = 20f
        val cr = 370f
        val cb = 560f
        val frame = curlFrame(pageW = w.toFloat(), pageH = h.toFloat(), progress = 0.35f, direction = 1)
        val effect = RuntimeEffect.makeForShader(CURL_SKSL)

        val frontPage = fakePage((cr - cl).toInt(), (cb - ct).toInt(), 0xFF303030.toInt())
        val backPage = fakePage((cr - cl).toInt(), (cb - ct).toInt(), 0xFF803030.toInt())
        val frontOverlay = fakeOverlay(w, h, 0xFFFFE082.toInt())
        val backOverlay = fakeOverlay(w, h, 0xFF80D8FF.toInt())

        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0xFFF5F0E6.toInt())
        // 第一遍：页栅格（版心大小，放在 (cl, ct)）。
        surface.canvas.drawRect(
            Rect.makeWH(w.toFloat(), h.toFloat()),
            pass(effect, frame, frontPage, backPage, false, cl, ct, cr - cl, cb - ct),
        )
        // 第二遍：覆盖层（整页，放在 (0,0)）。
        surface.canvas.drawRect(
            Rect.makeWH(w.toFloat(), h.toFloat()),
            pass(effect, frame, frontOverlay, backOverlay, true, 0f, 0f, w.toFloat(), h.toFloat()),
        )
        val out = File("build/curl-composite.png")
        out.parentFile?.mkdirs()
        val data = surface.makeImageSnapshot().encodeToData() ?: error("encode failed")
        out.writeBytes(data.bytes)
        println("wrote ${out.absolutePath}")
    }
}
