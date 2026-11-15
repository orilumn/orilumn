package orilumn.reader.ui.reader.curl

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3D 卷曲的**冒烟锁**（走生产路径 [drawCurl3DFrame]，不是像素基准）。
 *
 * 锁的是这几件会静默坏掉、而且读者一眼就看见的事：
 *
 * 1. 起手那一帧就是当前页（`front`），翻完那一帧就是目标页（`back`）——撤层不闪；
 * 2. 纸卷鼓在**掀起侧**（露出页那一侧），纸卷与落影之外才露出目标页；
 *    接反的话同一段文字会在旧页上叠两遍，翻到九成又反过来盖住新页；
 * 3. 翻到九成时目标页必须占屏幕大部分——「几乎没翻动」就是这条坏了。
 *
 * 明暗浓淡、落影深浅不在这里锁，那要真机跟手看。
 */
class Curl3DRenderTest {

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

    private fun images(page: ImageBitmap) = CurlPageImages().apply { this.page = page }

    private fun render(shape: CurlShape, front: ImageBitmap, back: ImageBitmap): ImageBitmap {
        val w = shape.pageW.toInt()
        val h = shape.pageH.toInt()
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            val drawn = drawCurl3DFrame(shape, images(front), images(back), paper, 0f, 0f)
            assertTrue("这一帧必须由网格画出来", drawn)
        }
        return img
    }

    @Test
    fun untouchedFrameIsTheCurrentPage() {
        val w = 400
        val h = 600
        // 两个方向都是「front = 正在看的那一页」：翻页层不再按方向换位，图像不换位。
        for (dir in listOf(1, -1)) {
            val px = render(curlShape(w.toFloat(), h.toFloat(), 0f, dir), solid(w, h, red), solid(w, h, blue))
                .toPixelMap()
            assertEquals("dir=$dir 起手时画面上是当前页", red, px[200, 300].toArgb())
        }
    }

    @Test
    fun settledFrameIsTheTargetPage() {
        val w = 400
        val h = 600
        for (dir in listOf(1, -1)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), 1f, dir, tilt = 0.55f, settling = true)
            val px = render(shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
            for (x in 0 until w step 7) {
                for (y in 0 until h step 13) {
                    assertEquals("dir=$dir ($x,$y) 翻完那一帧应当只剩目标页", blue, px[x, y].toArgb())
                }
            }
        }
    }

    /**
     * 纸卷鼓在掀起侧、平铺段落回另一侧：折痕往里一点是纸卷（不是目标页），
     * 越过纸卷与落影之后才是目标页；折痕另一侧仍是当前页。两个方向互为镜像。
     */
    @Test
    fun rollSitsOnTheLiftedSide() {
        val w = 400
        val h = 600
        val mid = h / 2
        for (dir in listOf(1, -1)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), 0.5f, dir)
            val radius = curlRadius(shape)
            val px = render(shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
            val fold = shape.foldX
            val inward = if (dir >= 0) 1f else -1f
            // 远离折痕的两头：没掀起的那半页还是当前页，露出页那一侧已经是目标页。
            val unturnedEdge = if (dir >= 0) 5 else w - 5
            val revealedEdge = if (dir >= 0) w - 5 else 5
            assertEquals("dir=$dir 没掀起的那半页应当是当前页", red, px[unturnedEdge, mid].toArgb())
            assertEquals("dir=$dir 露出页那一侧的页边应当是目标页", blue, px[revealedEdge, mid].toArgb())
            // 折痕往里 5px 被纸卷盖住，不能直接看到目标页。
            val nearCrease = (fold + inward * 5f).toInt()
            assertTrue(
                "dir=$dir 折痕旁边应当是纸卷，不该直接露出目标页（实测 ${px[nearCrease, mid].toArgb().toUInt().toString(16)}）",
                px[nearCrease, mid].toArgb() != blue,
            )
            // 纸卷半径 + 落影宽度（≈ 2R）之外，目标页完整可见。
            val beyondShadow = (fold + inward * (radius * 3f + 8f)).toInt()
            assertEquals("dir=$dir 纸卷与落影之外应当露出目标页", blue, px[beyondShadow, mid].toArgb())
        }
    }

    /** 翻到九成时目标页必须占屏幕大部分（「翻了两成看起来几乎没动」那一类回归）。 */
    @Test
    fun lateProgressMostlyShowsTheTargetPage() {
        val w = 400
        val h = 600
        val mid = h / 2
        for (dir in listOf(1, -1)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), 0.9f, dir)
            val px = render(shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
            var target = 0
            for (x in 0 until w) if (px[x, mid].toArgb() == blue) target++
            assertTrue(
                "dir=$dir p=0.9 时目标页只占 ${target * 100 / w}% —— 纸卷/平铺段接反了",
                target >= w * 65 / 100,
            )
        }
    }
}
