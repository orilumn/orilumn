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
 * 卷曲片元的冒烟锁（**不是像素基准，只锁几件会静默坏掉的事**）：
 * 1) SkSL 能编译（语法/类型写错时桌面上是黑屏，不是崩溃）；
 * 2) 逆映射的左右分区没接反——向后翻时「折痕左边是正面页、折痕右边是露出页」，
 *    折痕两侧紧邻的两条带分别是纸背平铺段与纸卷；
 * 3) 纸背平铺段是 **1:1 刚性镜像**（旧版把纸料压进屏幕带宽，放大了约 1.45 倍）；
 * 4) 整幅不留透明像素（着色器算飞了会出 NaN，表现出来是黑洞/透明）。
 *
 * 观感（阴影浓淡、卷边粗细）不在这里锁，那要真机跟手看。
 */
class CurlShaderTest {

    private fun solid(w: Int, h: Int, argb: Int): Image {
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(argb)
        return surface.makeImageSnapshot()
    }

    /** 白底 + 指定列上的黑色竖条（用来量纸背的采样比例）。 */
    private fun striped(w: Int, h: Int, stripeX: Int, stripeW: Int): Image {
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.clear(0xFFFFFFFF.toInt())
        surface.canvas.drawRect(
            Rect.makeXYWH(stripeX.toFloat(), 0f, stripeW.toFloat(), h.toFloat()),
            Paint().apply { color = 0xFF000000.toInt() },
        )
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

    private fun render(w: Int, h: Int, frame: CurlFrame, front: Image? = null): org.jetbrains.skia.Pixmap {
        val effect = RuntimeEffect.makeForShader(CURL_SKSL)
        // 纹理 1:1（不挂 local matrix）：与两端后端同一约定，见 [CURL_SKSL] 文件头。
        val shader: Shader = effect.makeShader(
            Data.makeFromBytes(bytes(frame.uniforms(false, 0f, 0f, w.toFloat(), h.toFloat()))),
            arrayOf<Shader?>(
                (front ?: solid(w, h, 0xFFCC2222.toInt())).makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null,
                ),
                solid(w, h, 0xFF2222CC.toInt()).makeShader(
                    FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null,
                ),
            ),
            Matrix33.makeTranslate(0f, 0f),
        )
        val surface = Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawRect(Rect.makeWH(w.toFloat(), h.toFloat()), Paint().apply { this.shader = shader })
        return surface.makeImageSnapshot().peekPixels() ?: error("peekPixels failed")
    }

    /**
     * p = 0.5、页宽 400：折痕 x = 200、D = 200、R = min(40, 60) = 40、arc ≈ 125.7。
     * ⇒ 正面页 [0, 125.7] / 纸背平铺 [125.7, 200] / 纸卷 [200, 240] / 露出页 [240, 400]。
     */
    @Test
    fun nextPageKeepsFrontLeftAndRevealRight() {
        val w = 400
        val h = 600
        val px = render(w, h, curlFrame(pageW = w.toFloat(), pageH = h.toFloat(), progress = 0.5f, direction = 1))
        val left = px.getColor(4, h / 2)
        val right = px.getColor(w - 5, h / 2)
        assertTrue("left should stay front page, got ${left.toString(16)}", (left shr 16 and 0xFF) > 0xA0)
        assertTrue("right should be reveal page, got ${right.toString(16)}", (right and 0xFF) > 0xA0)
        // 纸卷（折痕右侧紧邻一条）：纸色打底 + 正面页镜像，红蓝都不该压满。
        val roll = px.getColor(215, h / 2)
        assertTrue("roll should be paper-tinted, got ${roll.toString(16)}", (roll and 0xFF) in 0x60..0xF8)
        assertTrue("roll should keep some front content, got ${roll.toString(16)}", (roll shr 16 and 0xFF) in 0x60..0xF8)
        // 纸背平铺段（折痕左侧紧邻一条）：同样是纸色 + 镜像内容。
        val flat = px.getColor(190, h / 2)
        assertTrue("flat back should be paper-tinted, got ${flat.toString(16)}", (flat and 0xFF) in 0x60..0xF8)
    }

    @Test
    fun prevPageKeepsSameLayoutAndJustSwapsPages() {
        val w = 400
        val h = 600
        // 向前翻：折痕从左缘往右扫，露出区仍**在折痕右侧**（旧版同布局），只是那里的图换成了
        // 调用方给的 back（当前页），折痕左侧是已被翻过去的纸背 + 落定的 front（上一页）。
        // p = 0.75 → 折痕在 x = 300，D = 100，纸卷 [300, 330]，左边是干净的正面页。
        val px = render(w, h, curlFrame(pageW = w.toFloat(), pageH = h.toFloat(), progress = 0.75f, direction = -1))
        val left = px.getColor(4, h / 2)
        val right = px.getColor(w - 5, h / 2)
        assertTrue("left should be front page, got ${left.toString(16)}", (left shr 16 and 0xFF) > 0xA0)
        assertTrue("right should be reveal page, got ${right.toString(16)}", (right and 0xFF) > 0xA0)
    }

    /**
     * 纸背平铺段必须 1:1：材质 x = 350 的竖条（页面右缘往里 50px）镜像回来，
     * 屏幕位置必须满足「屏幕位移 = 材质位移 - 弧长」——即 x = 200 - (150 - 125.66) ≈ 175.7。
     * 旧版按 (D-arc)/(D-B) 压缩，同一条竖条会跑到 x ≈ 72（放大 1.45 倍）。
     */
    @Test
    fun backFlatPartKeepsMaterialScale() {
        val w = 400
        val h = 600
        val frame = curlFrame(pageW = w.toFloat(), pageH = h.toFloat(), progress = 0.5f, direction = 1)
        val px = render(w, h, frame, striped(w, h, stripeX = 348, stripeW = 4))
        var darkestX = -1
        var darkest = 0xFF
        for (x in 126..199) {
            val v = px.getColor(x, h / 2) shr 16 and 0xFF
            if (v < darkest) { darkest = v; darkestX = x }
        }
        assertTrue("纸背平铺段应把材质 1:1 镜像过来（竖条应落在 ~176，实测 $darkestX）", kotlin.math.abs(darkestX - 176) <= 3)
        assertTrue("竖条应当清晰可辨（纸色混 39% 黑），got $darkest", darkest < 0xB0)
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
