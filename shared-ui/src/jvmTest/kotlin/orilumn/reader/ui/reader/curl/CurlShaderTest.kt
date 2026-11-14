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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷曲片元的冒烟锁（**不是像素基准，只锁三件会静默坏掉的事**）：
 * 1) SkSL 能编译（语法/类型写错时桌面上是黑屏，不是崩溃）；
 * 2) 逆映射的左右分区没接反——向后翻时「折痕左边是正面页（被翻走的那一页）、
 *    折痕右边是露出的目标页」，且中间那条纸卷不是纯正页也不是纯目标页；
 * 3) 整幅不留透明像素（着色器算飞了会出 NaN，表现出来是黑洞/透明）。
 *
 * 观感（阴影浓淡、曲率）不在这里锁，那要真机跟手看。
 */
class CurlShaderTest {

    private fun solid(w: Int, h: Int, argb: Int): Image {
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(argb)
        return surface.makeImageSnapshot()
    }

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

    private fun render(w: Int, h: Int, frame: CurlFrame): org.jetbrains.skia.Pixmap {
        val effect = RuntimeEffect.makeForShader(CURL_SKSL)
        val uv = Matrix33.makeScale(1f / w, 1f / h)
        val shader: Shader = effect.makeShader(
            Data.makeFromBytes(bytes(frame.uniforms())),
            arrayOf<Shader?>(
                solid(w, h, 0xFFCC2222.toInt()).makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, uv,
                ),
                solid(w, h, 0xFF2222CC.toInt()).makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, uv,
                ),
            ),
            Matrix33.makeTranslate(0f, 0f),
        )
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), Paint().apply { this.shader = shader })
        return surface.makeImageSnapshot().peekPixels() ?: error("peekPixels failed")
    }

    @Test
    fun nextPageKeepsFrontLeftAndRevealRight() {
        val w = 400
        val h = 600
        // p = 0.25 → 折痕在 x = 300，纸卷占 [200, 300]，两边都留出干净的样本点。
        val px = render(w, h, curlFrame(pageW = w.toFloat(), pageH = h.toFloat(), progress = 0.25f, direction = 1))
        val left = px.getColor(4, h / 2)
        val right = px.getColor(w - 5, h / 2)
        assertTrue("left should stay front page, got ${left.toString(16)}", (left shr 16 and 0xFF) > 0xA0)
        assertTrue("right should be reveal page, got ${right.toString(16)}", (right and 0xFF) > 0xA0)
        // 纸卷区：纸色打底 + 正面页镜像，红蓝都不该压满
        val strip = px.getColor(250, h / 2)
        assertTrue("strip should be paper-tinted, got ${strip.toString(16)}", (strip and 0xFF) in 0x60..0xF8)
        assertTrue("strip should keep some front content, got ${strip.toString(16)}", (strip shr 16 and 0xFF) in 0x60..0xF8)
    }

    @Test
    fun prevPageKeepsSameLayoutAndJustSwapsPages() {
        val w = 400
        val h = 600
        // 向前翻：折痕从左缘往右扫，露出区仍**在折痕右侧**（旧版同布局），只是那里的图换成了
        // 调用方给的 back（当前页），折痕左侧是已被翻过去的纸背 + 落定的 front（上一页）。
        // p = 0.75 → 折痕在 x = 300，纸卷只占 [200, 300]，左边是干净的 front。
        val px = render(w, h, curlFrame(pageW = w.toFloat(), pageH = h.toFloat(), progress = 0.75f, direction = -1))
        val left = px.getColor(4, h / 2)
        val right = px.getColor(w - 5, h / 2)
        assertTrue("left should be front page, got ${left.toString(16)}", (left shr 16 and 0xFF) > 0xA0)
        assertTrue("right should be reveal page, got ${right.toString(16)}", (right and 0xFF) > 0xA0)
    }

    @Test
    fun frameHasNoTransparentPixels() {
        val w = 120
        val h = 200
        val px = render(w, h, curlFrame(pageW = w.toFloat(), pageH = h.toFloat(), progress = 0.4f, direction = 1))
        var transparent = 0
        for (y in 0 until h step 7) {
            for (x in 0 until w step 7) {
                if ((px.getColor(x, y) ushr 24) != 0xFF) transparent++
            }
        }
        assertEquals("透明像素通常意味着片元算出了 NaN", 0, transparent)
    }

    @Test
    fun geometrySweepsCreaseAcrossPage() {
        assertEquals(400f, curlFrame(400f, 600f, 0f, 1).topX, 0.01f)
        assertEquals(0f, curlFrame(400f, 600f, 1f, 1).topX, 0.01f)
        assertEquals(0f, curlFrame(400f, 600f, 0f, -1).topX, 0.01f)
        assertEquals(400f, curlFrame(400f, 600f, 1f, -1).topX, 0.01f)
        // 法向两个方向都朝右（露出区恒在折痕右侧，见 CurlFrame 注释）。
        assertTrue(curlFrame(400f, 600f, 0.5f, 1).normalX > 0f)
        assertTrue(curlFrame(400f, 600f, 0.5f, -1).normalX > 0f)
    }
}
