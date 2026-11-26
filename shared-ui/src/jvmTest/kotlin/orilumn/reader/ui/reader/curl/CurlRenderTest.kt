package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
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
import orilumn.reader.ui.reader.RasterTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折页绘制的冒烟锁（**不是像素基准，只锁几件会静默坏掉的事**）：
 *
 * 1) 三块分区没接反——折痕左边是纸背、右边是露出页；
 * 2) 纸背是 **1:1 刚性镜像**（放大了就是纸被拉伸，压缩了就是纸背凭空变宽）；
 * 3) 结算两端（进度 0 / 1）的整帧**等于它落回的那一页**（0 = 当前页，1 = 目标页）：
 *    末帧不许留折痕影、纸背或露出区的边；
 * 4) 整幅不留透明像素（算出 NaN / 忘了铺底时会露出黑洞）。
 *
 * 观感（阴影浓淡、纸色深浅）不在这里锁，那要真机跟手看。
 */
class CurlRenderTest {

    private val red = 0xFFCC2222.toInt()
    private val blue = 0xFF2222CC.toInt()
    private val paper = 0xFFF1EEE6.toInt()

    private fun solid(w: Int, h: Int, argb: Int): ImageBitmap {
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color(argb))
        }
        return img
    }

    /** 白底 + 一条黑色竖条（用来量纸背的采样比例）。 */
    private fun striped(w: Int, h: Int, stripeX: Int, stripeW: Int): ImageBitmap {
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color(0xFFFFFFFF.toInt()))
            drawRect(
                color = Color(0xFF000000.toInt()),
                topLeft = androidx.compose.ui.geometry.Offset(stripeX.toFloat(), 0f),
                size = Size(stripeW.toFloat(), h.toFloat()),
            )
        }
        return img
    }

    private fun images(page: ImageBitmap, overlay: ImageBitmap? = null) = CurlPageImages().apply {
        this.page = RasterTexture(page)
        this.overlay = overlay?.let { RasterTexture(it) }
    }

    private fun render(
        shape: CurlShape,
        front: CurlPageImages,
        back: CurlPageImages,
        frontTex: Offset = Offset.Zero,
        backTex: Offset = Offset.Zero,
    ): ImageBitmap {
        val w = shape.pageW.toInt()
        val h = shape.pageH.toInt()
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            drawCurlFrame(shape, front, back, paper, frontTex, backTex)
        }
        return img
    }

    /**
     * 向后翻、进度 0.25（折痕 x = 300）：左边 200px 还是正面页，中间 100px 是纸背，
     * 右边 100px 是露出页。三块的底色必须各不相同。
     */
    @Test
    fun nextPageKeepsFrontLeftBackMiddleAndRevealRight() {
        val w = 400
        val h = 600
        val shape = curlShape(w.toFloat(), h.toFloat(), 0.25f)
        val px = render(shape, images(solid(w, h, red)), images(solid(w, h, blue))).toPixelMap()

        val left = px[4, h / 2].toArgb()
        val middle = px[250, h / 2].toArgb()
        val right = px[w - 5, h / 2].toArgb()

        assertEquals("折痕左侧应当是正面页", red, left)
        assertEquals("折痕右侧应当是露出页", blue, right)
        val r = (middle shr 16) and 0xFF
        val g = (middle shr 8) and 0xFF
        val b = middle and 0xFF
        assertTrue("纸背应当是「纸色 + 39% 正面内容」，实测 ${middle.toUInt().toString(16)}", r in 0x80..0xF0 && g in 0x40..0xC0 && b in 0x40..0xC0)
        assertTrue("纸背不该与正面页同色", middle != red)
    }

    /**
     * 纸背是 1:1 刚性镜像：材质 x = 350 的竖条（页右缘往里 50px）沿折痕 x = 200 翻过来，
     * 应当落在 x = 50。放大了会跑到别处（旧版把纸料压进屏幕带宽，放大约 1.45 倍）。
     */
    @Test
    fun backStripKeepsMaterialScale() {
        val w = 400
        val h = 600
        val shape = curlShape(w.toFloat(), h.toFloat(), 0.5f)
        val px = render(shape, images(striped(w, h, stripeX = 348, stripeW = 4)), images(solid(w, h, blue))).toPixelMap()

        var darkestX = -1
        var darkest = 0xFF
        for (x in 0..199) {
            val v = (px[x, h / 2].toArgb() shr 16) and 0xFF
            if (v < darkest) {
                darkest = v
                darkestX = x
            }
        }
        assertTrue("纸背应当把材质 1:1 镜像过来（竖条应落在 ~50，实测 $darkestX）", kotlin.math.abs(darkestX - 50) <= 3)
        assertTrue("竖条应当清晰可辨（纸色混 39% 黑），got $darkest", darkest < 0xD0)
    }

    /**
     * 结算两端：进度 0 停在读者正在看的那一页（`front`），进度 1 落到目标页（`back`）。
     * 整帧必须**逐像素等于那一页**，不许留折痕影、纸背或露出区的边——动画结束撤掉这一层时
     * 画面才不会跳一下。
     */
    @Test
    fun settleEndFramesEqualThePageTheyLandOn() {
        val w = 400
        val h = 600
        val start = render(
            curlShape(w.toFloat(), h.toFloat(), 0f, tilt = 0.55f),
            images(solid(w, h, red)),
            images(solid(w, h, blue)),
        ).toPixelMap()
        val end = render(
            curlShape(w.toFloat(), h.toFloat(), 1f, tilt = 0.55f),
            images(solid(w, h, red)),
            images(solid(w, h, blue)),
        ).toPixelMap()
        for (x in 0 until w step 7) {
            for (y in 0 until h step 13) {
                assertEquals("起手那一帧应当只剩当前页（front）", red, start[x, y].toArgb())
                assertEquals("收尾那一帧应当只剩目标页（back）", blue, end[x, y].toArgb())
            }
        }
    }

    /**
     * 收尾最后一帧（3D 网格退化成空、退回折页）不许在自由边留一条灰竖带。
     *
     * 上一页收尾时折痕扫到右缘，纸背只剩一条几像素的窄条，它的外缘影却按满宽铺在右侧留白上；
     * 这一帧恰好是 3D 网格交不出网格、退回折页的那一帧，读者看到的就是「动画结束时右侧闪一下」。
     */
    @Test
    fun fallbackTailFrameDoesNotLeaveAShadowBandOnTheFreeEdge() {
        val w = 400
        val h = 600
        val shape = curlFrame(w.toFloat(), h.toFloat(), 0.999f, direction = -1).shape
        val white = solid(w, h, 0xFFFFFFFF.toInt())
        val px = render(shape, images(white), images(white)).toPixelMap()
        for (x in w - 40 until w - 4) {
            for (y in h / 5 until h * 4 / 5 step 5) {
                val c = px[x, y]
                val v = ((c.red * 255).toInt() + (c.green * 255).toInt() + (c.blue * 255).toInt()) / 3
                assertTrue("收尾最后一帧右侧留白被影压暗了（x=$x y=$y v=$v）", v >= 245)
            }
        }
    }

    /** 还没开始翻（进度 0 且不在结算里）：整帧就是读者正在看的那一页（`front`）。 */
    @Test
    fun untouchedFrameIsThePageBeingRead() {
        val w = 400
        val h = 600
        // front = 正在看的那一页：翻页层不按方向换位，图像不换位。
        val px = render(
            curlShape(w.toFloat(), h.toFloat(), 0f),
            images(solid(w, h, red)),
            images(solid(w, h, blue)),
        ).toPixelMap()
        assertEquals("起手时画面上是当前页，不是目标页", red, px[200, 300].toArgb())
    }

    /**
     * 覆盖层（旁注 / 页码）跟着纸一起走：正面页上是原样，纸背上是**同一份的镜像**
     * （＝同一张纸的背面朝着读者，读者写的字透过来）。
     */
    @Test
    fun overlayRidesTheWholeSheet() {
        val w = 400
        val h = 600
        val shape = curlShape(w.toFloat(), h.toFloat(), 0.25f)
        // 覆盖层：整页大小的一块绿（模拟旁注框）
        val overlay = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(overlay), Size(w.toFloat(), h.toFloat())) {
            drawRect(Color(0xFF00AA00.toInt()), topLeft = androidx.compose.ui.geometry.Offset(0f, 250f), size = Size(w.toFloat(), 100f))
        }
        val withOverlay = render(shape, images(solid(w, h, red), overlay), images(solid(w, h, blue))).toPixelMap()
        val plain = render(shape, images(solid(w, h, red)), images(solid(w, h, blue))).toPixelMap()
        assertEquals("正面页上的旁注要画出来", 0xFF00AA00.toInt(), withOverlay[50, 300].toArgb())
        // 纸背（折痕左侧那一半）与「没有覆盖层」的那一帧相比要有变化，而且不许被刷成白色。
        var backDiff = 0
        for (y in 0 until h step 3) {
            for (x in 0 until w / 2 step 3) {
                val argb = withOverlay[x, y].toArgb()
                if (argb != plain[x, y].toArgb()) backDiff++
                assertTrue(
                    "纸背上的透明覆盖层被抬成了白色（x=$x y=$y）",
                    !((argb shr 16) and 0xFF > 250 && (argb shr 8) and 0xFF > 250 && (argb and 0xFF) > 250),
                )
            }
        }
        assertTrue("纸背是同一张纸的反面：旁注要透过来（实测 $backDiff 个像素不同）", backDiff > 20)
    }

    @Test
    fun frameHasNoTransparentPixels() {
        val w = 400
        val h = 600
        val shape = curlShape(w.toFloat(), h.toFloat(), 0.35f, tilt = 0.36f)
        val px = render(shape, images(solid(w, h, red)), images(solid(w, h, blue))).toPixelMap()
        var transparent = 0
        for (y in 0 until h step 11) {
            for (x in 0 until w step 11) {
                if ((px[x, y].toArgb() ushr 24) != 0xFF) transparent++
            }
        }
        assertEquals("透明像素通常意味着哪一块忘了铺纸色", 0, transparent)
    }

    /** 页栅格比页小（版心）：四周留白必须是纸色，不能是透明或黑边。 */
    @Test
    fun pageRasterIsPlacedAtContentRect() {
        val w = 400
        val h = 600
        val texLeft = 30
        val texTop = 20
        val shape = curlShape(w.toFloat(), h.toFloat(), 0.25f)
        val px = render(
            shape,
            images(solid(340, 560, red)),
            images(solid(340, 560, blue)),
            frontTex = Offset(texLeft.toFloat(), texTop.toFloat()),
            backTex = Offset(texLeft.toFloat(), texTop.toFloat()),
        ).toPixelMap()
        assertEquals("版心外的页边距应当是纸色", paper, px[4, 4].toArgb())
        assertEquals("版心里的正面页是红的", red, px[40, 300].toArgb())
    }
}
