package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageImage
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * P0a `drawPageContent` 的像素级回归锁（用户层·渲染缝）。
 *
 * 这条路径两端共用，改它等于改屏幕像素，但它没有任何编译期保护——插图从
 * 「Compose 层直画」下沉到「进整页位图」时，最容易静默丢的东西是：
 *  1. **占位语义**：图还没解出来时必须画灰色块，不能静默空白（首帧常态）；
 *  2. **几何口径**：`dstLeft = contentLeft + xLeft`、Y 用页坐标——错一个就是图文错位；
 *  3. **绘制顺序**：插图在文字之后，盒背景在文字之前（顺序反了就是图被字压住/底被字盖）；
 *  4. **底色打底**：卷曲时页背不能透明，所以 `clear(pageBg)` 必须真执行。
 *
 * 断言方式：skia 软件 surface 画完直接读像素（RGBA_8888），不依赖任何真机。
 */
class PageRasterContentTest {

    private val w = 200
    private val h = 120
    private val pageBg = 0xFF204060.toInt()

    /** 造一个纯色小图（DecodedImage 走真实构造：Image.makeFromEncoded 一条太重，直接用 raster）。 */
    private fun solidImage(color: Int, width: Int, height: Int): orilumn.reader.engine.skia.DecodedImage {
        val s = Surface.makeRasterN32Premul(width, height)
        s.canvas.clear(color)
        val snap = s.makeImageSnapshot()
        s.close()
        return orilumn.reader.engine.skia.DecodedImage(snap)
    }

    private fun raster(
        pageBg: Int = this.pageBg,
        backgrounds: List<orilumn.reader.engine.skia.PageBackground> = emptyList(),
        images: List<PageImageSlot> = emptyList(),
    ): Array<IntArray> {
        val s = Surface.makeRasterN32Premul(w, h)
        drawPageContent(
            canvas = s.canvas,
            contentLeft = 0f,
            lines = emptyList(),
            backgrounds = backgrounds,
            bgImages = emptyMap(),
            images = images,
            pageBg = pageBg,
            w = w,
            h = h,
            drawer = LineWindowDrawer(),
        )
        val snap = s.makeImageSnapshot()
        val bmp = Bitmap()
        bmp.allocN32Pixels(w, h, true)
        snap.readPixels(bmp)
        val info = ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        val bytes = bmp.readPixels(info, w * 4, 0, 0)!!
        val px = Array(h) { y -> IntArray(w) { x -> readArgb(bytes, (y * w + x) * 4) } }
        snap.close()
        s.close()
        return px
    }

    private fun readArgb(bytes: ByteArray, off: Int): Int {
        val r = bytes[off].toInt() and 0xFF
        val g = bytes[off + 1].toInt() and 0xFF
        val b = bytes[off + 2].toInt() and 0xFF
        val a = bytes[off + 3].toInt() and 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun argbNear(actual: Int, expect: Int, tol: Int = 8): Boolean {
        fun d(x: Int, y: Int) = abs(((x ushr 24) and 0xFF) - ((y ushr 24) and 0xFF))
        fun dr(x: Int, y: Int) = abs(((x ushr 16) and 0xFF) - ((y ushr 16) and 0xFF))
        fun dg(x: Int, y: Int) = abs(((x ushr 8) and 0xFF) - ((y ushr 8) and 0xFF))
        fun db(x: Int, y: Int) = abs((x and 0xFF) - (y and 0xFF))
        return d(actual, expect) <= tol && dr(actual, expect) <= tol && dg(actual, expect) <= tol && db(actual, expect) <= tol
    }

    private fun img(xLeft: Int, yTop: Int, width: Int, height: Int) =
        PageImage(
            src = "img$xLeft-$yTop.png",
            chapterHref = "ch.xhtml",
            xLeft = xLeft,
            yTop = yTop,
            yBottom = yTop + height,
            widthPx = width,
            heightPx = height,
        )

    @Test
    fun `打底执行——没有行也没有图时整页是 pageBg`() {
        val px = raster()
        for (y in 0 until h) for (x in 0 until w) {
            assertTrue("(${x},${y}) 未打底", argbNear(px[y][x], pageBg))
        }
    }

    @Test
    fun `缺图槽位画灰色占位而不是静默空白`() {
        val slot = PageImageSlot(img(20, 30, 60, 40), decoded = null)
        val px = raster(images = listOf(slot))
        // 占位区内：灰色
        for (y in 34 until 66) for (x in 24 until 76) {
            assertTrue("(${x},${y}) 缺图占位未画灰", argbNear(px[y][x], 0xFFDDDDDD.toInt()))
        }
        // 占位区外：仍是底色（占位不许溢出）
        assertTrue("占位溢出到区外", argbNear(px[10][10], pageBg))
    }

    @Test
    fun `已解出槽位按 contentLeft 加 xLeft 落位`() {
        val red = solidImage(0xFFFF0000.toInt(), 8, 8)
        val slot = PageImageSlot(img(20, 30, 60, 40), decoded = red)
        val px = raster(images = listOf(slot))
        // 左上角应落在 (xLeft, yTop) = (20, 30)（contentLeft=0）
        assertTrue("图未落在 (20,30)：${Integer.toHexString(px[30][20])}", argbNear(px[30][20], 0xFFFF0000.toInt()))
        assertTrue("图心不是红：${Integer.toHexString(px[50][50])}", argbNear(px[50][50], 0xFFFF0000.toInt()))
        // 右下角（xLeft+width-1, yTop+height-1）之后应恢复底色
        assertTrue("图越界溢出", argbNear(px[50][85], pageBg))
        assertTrue("图下沿溢出", argbNear(px[75][50], pageBg))
    }

    @Test
    fun `两张图的槽位互不覆盖且各自落位`() {
        val red = solidImage(0xFFFF0000.toInt(), 8, 8)
        val green = solidImage(0xFF00FF00.toInt(), 8, 8)
        val px = raster(
            images = listOf(
                PageImageSlot(img(10, 10, 30, 30), red),
                PageImageSlot(img(100, 60, 40, 40), green),
            )
        )
        assertTrue("图一位置错", argbNear(px[20][20], 0xFFFF0000.toInt()))
        assertTrue("图二位置错：${Integer.toHexString(px[80][120])}", argbNear(px[80][120], 0xFF00FF00.toInt()))
    }

    @Test
    fun `混合缺口与已解出——缺口仍是灰块不被跳过`() {
        val red = solidImage(0xFFFF0000.toInt(), 8, 8)
        val px = raster(
            images = listOf(
                PageImageSlot(img(10, 10, 30, 30), red),
                PageImageSlot(img(100, 60, 40, 40), null),
            )
        )
        assertTrue("已解出那张丢了", argbNear(px[20][20], 0xFFFF0000.toInt()))
        assertTrue("未解出那张没画占位：${Integer.toHexString(px[80][120])}", argbNear(px[80][120], 0xFFDDDDDD.toInt()))
    }
}