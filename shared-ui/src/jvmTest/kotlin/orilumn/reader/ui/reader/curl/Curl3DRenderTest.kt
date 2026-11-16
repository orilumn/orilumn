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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
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

    private fun render(
        shape: CurlShape,
        front: ImageBitmap,
        back: ImageBitmap,
        texLeft: Float = 0f,
        texTop: Float = 0f,
    ): ImageBitmap {
        val w = shape.pageW.toInt()
        val h = shape.pageH.toInt()
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            val drawn = drawCurl3DFrame(shape, images(front), images(back), paper, texLeft, texTop)
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

    /**
     * 纸背的自由边（与折痕平行、挨着纸卷的那条）**既不落影也不描边**。
     *
     * 那条边是纸自己压在自己身上：一边是平铺的纸背、一边是卷起来的同一张纸，中间是平滑切线。
     * 早先在这条边外面铺一道影、再描一条实线，读者看到的就是「卷筒左侧平白多出一条痕迹 +
     * 一道影」。纸背的可辨识度只靠镜像内容与折角两条直角边的接触影（见
     * [foldedCornerSideEdgesCastShadows]）。
     */
    @Test
    fun freeEdgeOfTheTurnedBackIsNeitherShadowedNorOutlined() {
        val w = 400
        val h = 600
        val mid = h / 2
        for (dir in listOf(1, -1)) {
            val p = 0.45f
            val shape = curlShape(w.toFloat(), h.toFloat(), p, dir)
            val radius = curlRadius(shape)
            val px = render(shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
            // 纸背自由边 = 折痕往「没掀起」那一侧退（掀起长度 − πR）。
            val back = w * p - PI.toFloat() * radius
            assertTrue("dir=$dir 这个进度下应当有平铺的纸背", back > radius)
            val outward = if (dir >= 0) -1f else 1f
            val edge = shape.foldX + outward * back
            fun luma(x: Int): Float {
                val c = px[x, mid]
                return (c.red + c.green + c.blue) / 3f
            }
            val shadowWidth = radius * CURL3D_BACK_SHADOW_WIDTH
            val besideEdge = luma((edge + outward * shadowWidth * 0.35f).toInt())
            val plainPaper = luma((edge + outward * shadowWidth * 3f).toInt())
            assertTrue(
                "dir=$dir 自由边外面又铺了影：边旁 $besideEdge / 空白处 $plainPaper（那里是纸压纸，没有影）",
                abs(besideEdge - plainPaper) < 0.01f,
            )
            // 也不许有一条实线：边上那一两个像素不能比纸背内侧、外侧都暗。
            val line = ((edge - 2).toInt()..(edge + 2).toInt()).minOf { luma(it) }
            val inside = luma((edge - outward * 6f).toInt())
            val outside = luma((edge + outward * 6f).toInt())
            assertTrue(
                "dir=$dir 自由边被描了边：边上 $line / 纸背内侧 $inside / 外侧 $outside",
                line >= minOf(inside, outside) - 0.01f,
            )
        }
    }

    /**
     * 纸背的三条**纸边**都要认出来：远端自由边 + 两条直角边。
     *
     * 自由边跟着折痕一起斜、两条直角边垂直于折痕：斜折痕时它们会在页面中间相交成直角，
     * 正是读者说的「折起的页角直角两条边」。三条边都必须落影，少了任何一条，纸背就像
     * 一张剪贴上去的白纸（原话「直角两条边为什么没画阴影」）。
     */
    @Test
    fun backHasThreePaperEdgesToShade() {
        val w = 400f
        val h = 600f
        for (dir in listOf(1, -1)) {
            // 竖折痕：自由边是贯通纸面的一条边，离折痕「掀起长度 − πR」。
            run {
                val p = 0.45f
                val shape = curlShape(w, h, p, dir)
                val mesh = buildCurlMesh(shape, curlRadius(shape))
                val corners = mesh.backCorners()
                assertTrue("dir=$dir 这个进度下应当有平铺的纸背", corners != null)
                val (near0, far0, far1, near1) = corners!!
                val t0 = Offset(shape.topX, 0f)
                val t1 = Offset(shape.bottomX, h)
                val foldLen = hypot(t1.x - t0.x, h)
                val side = if (shape.direction >= 0) 1f else -1f
                val up = Offset(h / foldLen * side, -(t1.x - t0.x) / foldLen * side)
                fun alongUp(pt: Offset) = (pt.x - t0.x) * up.x + (pt.y - t0.y) * up.y
                val flap = w * p - PI.toFloat() * curlRadius(shape)
                assertEquals("dir=$dir 自由边不在纸背外沿", -flap, alongUp(far0), 3f)
                assertEquals("dir=$dir 自由边两头不在同一条边上", alongUp(far0), alongUp(far1), 3f)
                assertEquals("dir=$dir 近折痕那头不贴折痕", 0f, alongUp(near0), 3f)
                // 自由边是末行扫线，长度 = 页面高度那条边。
                assertEquals(
                    "dir=$dir 自由边没有横穿纸面",
                    h.toFloat(),
                    hypot(far1.x - far0.x, far1.y - far0.y),
                    h * 0.05f,
                )
                // 两条直角边 = 纸的上下边转过来的那两条，长度 = 平铺段宽度（掀起长度 − πR）。
                assertEquals(
                    "dir=$dir 直角边长度不对",
                    flap,
                    hypot(far0.x - near0.x, far0.y - near0.y),
                    3f,
                )
                assertEquals(
                    "dir=$dir 直角边长度不对",
                    flap,
                    hypot(far1.x - near1.x, far1.y - near1.y),
                    3f,
                )
            }
            // 斜折痕：自由边收成一个页角，两条直角边仍在页面中间相交成直角。
            run {
                val shape = curlShape(w, h, 0.45f, dir, tilt = 0.35f)
                val mesh = buildCurlMesh(shape, curlRadius(shape))
                val (near0, far0, far1, near1) = mesh.backCorners()!!
                val e1x = far0.x - near0.x
                val e1y = far0.y - near0.y
                val e2x = far1.x - near1.x
                val e2y = far1.y - near1.y
                val cos = abs(e1x * e2x + e1y * e2y) /
                    (hypot(e1x, e1y) * hypot(e2x, e2y)).coerceAtLeast(1e-3f)
                assertTrue("dir=$dir 斜折痕下两条直角边不再垂直：cos=$cos", cos < 0.2f)
                assertTrue(
                    "dir=$dir 斜折痕下自由边没有收窄（应当只剩页角附近）",
                    hypot(far1.x - far0.x, far1.y - far0.y) < h * 0.5f,
                )
            }
        }
    }

    /**
     * 折角的**两条直角边**必须落影。
     *
     * 纸背是平铺的一整页纸盖在旧页上，它的三条纸边都要有接触影；只给远端那条落影时，
     * 读者看到折角的两条直角边是「干边」——纸背看起来像一张剪贴上去的白纸。
     */
    @Test
    fun foldedCornerSideEdgesCastShadows() {
        val w = 400
        val h = 600
        for (dir in listOf(1, -1)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), 0.45f, dir, tilt = 0.35f)
            val radius = curlRadius(shape)
            val mesh = buildCurlMesh(shape, radius)
            val corners = mesh.backCorners()
            assertTrue("dir=$dir 斜折痕下应当有纸背", corners != null)
            val (near0, far0, far1, near1) = corners!!
            val center = corners.reduce { acc, p -> acc + p } / corners.size.toFloat()
            val px = render(shape, solid(w, h, paper), solid(w, h, paper)).toPixelMap()
            val width = radius * CURL3D_BACK_SHADOW_WIDTH
            fun lumaAt(pt: Offset): Float {
                val c = px[pt.x.toInt().coerceIn(0, w - 1), pt.y.toInt().coerceIn(0, h - 1)]
                return (c.red + c.green + c.blue) / 3f
            }
            var checked = 0
            for ((a, b) in listOf(far0 to near0, far1 to near1)) {
                val mid = (a + b) * 0.5f
                val ex = b.x - a.x
                val ey = b.y - a.y
                val len = hypot(ex, ey)
                assertTrue("dir=$dir 直角边长度为 0", len > 10f)
                var outward = Offset(ey / len, -ex / len)
                if ((mid.x - center.x) * outward.x + (mid.y - center.y) * outward.y < 0f) {
                    outward = Offset(-outward.x, -outward.y)
                }
                val near = mid + outward * (width * 0.5f)
                val far = mid + outward * (width * 3f)
                if (near.x < 6f || near.x > w - 6f || near.y < 6f || near.y > h - 6f) continue
                if (far.x < 2f || far.x > w - 2f || far.y < 2f || far.y > h - 2f) continue
                checked++
                assertTrue(
                    "dir=$dir 折角的一条直角边没有落影：边旁 ${lumaAt(near)} / 更外侧 ${lumaAt(far)}",
                    lumaAt(near) < lumaAt(far) - 0.02f,
                )
            }
            assertTrue("dir=$dir 两条直角边都没能采样，测试没测到东西", checked > 0)
        }
    }

    /**
     * 反面**不许整体比正面暗**：它就是同一张纸的另一面。
     *
     * 早先给纸背铺满一层 0.14 的黑，读者一眼就问「反面为什么整体比正面暗」。现在只在外沿
     * 那一条留一点接触暗，主体必须和正面同亮。
     */
    @Test
    fun backIsNotDarkerThanTheFrontPage() {
        val w = 400
        val h = 600
        val mid = h / 2
        for (dir in listOf(1, -1)) {
            val p = 0.45f
            val shape = curlShape(w.toFloat(), h.toFloat(), p, dir)
            val radius = curlRadius(shape)
            val px = render(shape, solid(w, h, paper), solid(w, h, paper)).toPixelMap()
            val flap = w * p - PI.toFloat() * radius
            val outward = if (dir >= 0) -1f else 1f
            fun luma(x: Int): Float {
                val c = px[x, mid]
                return (c.red + c.green + c.blue) / 3f
            }
            // 靠纸卷那一头：整片纸背都压暗时这里和正面差得最多。
            val back = luma((shape.foldX + outward * flap * 0.20f).toInt())
            val front = luma((shape.foldX + outward * flap * 1.70f).toInt())
            assertTrue(
                "dir=$dir 反面比正面暗了 ${front - back}（反面就是同一张纸的另一面）",
                front - back < 0.042f,
            )
        }
    }

    /**
     * 纸卷的筒底（纸从筒上绕回平铺段的切线）**不许压一层暗**。
     *
     * 纸是白的、底下的页也是白的，靠叠白确实画不出圆柱 —— 但那一层压暗正好落在纸卷的左侧：
     * 一边是平铺的纸背、一边是同色的卷面，中间留一道硬边，读者看到的就是「筒的左侧平白多出
     * 一条折痕」。减半、铺成缓坡都只是把线变浅，线还在，所以现在一律不画。
     * 用纯白页量：跨过切线不许出现任何台阶（渐变可以有，跳变不行）。
     */
    @Test
    fun rollBaseIsFlatNoCreaseLine() {
        val w = 400
        val h = 600
        val mid = h / 2
        for (dir in listOf(1, -1)) {
            val p = 0.45f
            val shape = curlShape(w.toFloat(), h.toFloat(), p, dir)
            val white = 0xFFFFFFFF.toInt()
            val px = render(shape, solid(w, h, white), solid(w, h, white)).toPixelMap()
            fun luma(x: Int): Float {
                val c = px[x, mid]
                return (c.red + c.green + c.blue) / 3f
            }
            val fold = shape.foldX.toInt()
            var worst = 0f
            for (x in fold - 8 until fold + 8) {
                worst = maxOf(worst, abs(luma(x) - luma(x + 1)))
            }
            assertTrue("dir=$dir 筒底出现台阶：跨过切线相邻像素跳了 $worst", worst < 0.02f)
            // 筒身也不许被压暗：纸卷那一侧和纸背的量值必须一样（都只有纸色压淡那一层）。
            val onRoll = luma(fold + if (dir >= 0) 6 else -6)
            val onBack = luma(fold - if (dir >= 0) 6 else -6)
            assertTrue("dir=$dir 筒身被压暗了：筒上 $onRoll / 纸背 $onBack", abs(onRoll - onBack) < 0.02f)
        }
    }

    /**
     * 纸背的**页边距**不许把版心的边列/边行抹成条纹。
     *
     * 页栅格只有版心那么大（平板 1585×2400 放在 1840×2800 里，右侧页边距 127px），
     * 纸背那一段扫线正好落在**页边距**里：纹理坐标越出版心时，采样会用版心的边列去补
     * （`TileMode.Clamp`），于是版心最右一列的墨点被拉成一条横贯页边距的短线。
     * 读者看到的就是「反面左边距、离底边六分之一处有好几条短横线」。
     */
    @Test
    fun backMarginDoesNotRepeatTheBodyEdgeColumn() {
        val w = 400
        val h = 600
        val margin = 40
        val ink = 0xFF202020.toInt()
        // 版心：纸色 + 右缘一列墨、下缘一行墨（模拟行末贴住版心边界的笔画）。
        val tw = w - margin * 2
        val th = h - margin * 2
        val body = ImageBitmap(tw, th, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(body), Size(tw.toFloat(), th.toFloat())) {
            drawRect(Color(paper))
            drawRect(Color(ink), topLeft = Offset(tw - 2f, th * 0.40f), size = Size(2f, th * 0.20f))
            drawRect(Color(ink), topLeft = Offset(tw * 0.40f, th - 2f), size = Size(tw * 0.20f, 2f))
        }
        val paperLuma = luma(Color(paper))
        for (dir in listOf(1, -1)) {
            val p = 0.45f
            val shape = curlShape(w.toFloat(), h.toFloat(), p, dir)
            val radius = curlRadius(shape)
            val px = render(shape, body, body, texLeft = margin.toFloat(), texTop = margin.toFloat()).toPixelMap()
            val inward = if (dir >= 0) -1f else 1f
            val flap = w * p - PI.toFloat() * radius
            // 纸背 = 折痕往「没掀起」一侧退（掀起长度 − πR）；页边距那一段在它外沿往里 margin。
            val bandStart = shape.foldX + inward * flap
            val marginStart = bandStart - inward * margin
            var checked = 0
            for (k in 1..3) {
                val x = (bandStart + (marginStart - bandStart) * k / 4f).toInt()
                // 右缘那一列墨会在整条页边距上抹出横线（y 跟着版心那一列走）；下缘那一行墨在底部。
                for (y in listOf((h * 0.45f).toInt(), (h * 0.55f).toInt(), h - margin / 2)) {
                    val got = luma(px[x, y])
                    assertTrue(
                        "dir=$dir 纸背页边距 ($x,$y) 出现 $got，纸面应为 $paperLuma —— 版心边列被抹开了",
                        abs(got - paperLuma) < 0.02f,
                    )
                    checked++
                }
            }
            assertTrue("dir=$dir 页边距没采样到", checked > 0)
        }
    }

    /**
     * 同上，但折痕**斜着**（纸往页角掀）。
     *
     * 折痕一斜，一条扫线上的材料 x 也在变（平板上一行能横跨大半个页宽）：只按行首的材料 x
     * 判断「这一行在版心里」，另一半已经出界的行照样整行画，`TileMode.Clamp` 就把版心边列
     * 抹成横贯页边距的短线。读者报「往左上角翻页时，反面左边距里有好几条短横线」就是它。
     *
     * 版心只放两条 2px 宽的竖线（贴住左右缘），所以整幅图里**任何**墨迹都不该连成一条横线：
     * 横着连成十几像素，就说明边列被抹开了。
     */
    @Test
    fun tiltedBackMarginDoesNotRepeatTheBodyEdgeColumn() {
        val w = 920
        val h = 1400
        val margin = 64
        val ink = 0xFF202020.toInt()
        val tw = w - margin * 2
        val th = h - margin * 2
        val body = ImageBitmap(tw, th, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(body), Size(tw.toFloat(), th.toFloat())) {
            drawRect(Color(paper))
            drawRect(Color(ink), topLeft = Offset(0f, 0f), size = Size(2f, th.toFloat()))
            drawRect(Color(ink), topLeft = Offset(tw - 2f, 0f), size = Size(2f, th.toFloat()))
        }
        for ((p, tilt) in listOf(
            0.016f to -0.6139f, // 真机日志里的起手帧
            0.12f to -0.6139f,
            0.40f to 0.653f,
        )) {
            for (dir in listOf(1, -1)) {
                val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt, false)
                val px = render(shape, body, body, texLeft = margin.toFloat(), texTop = margin.toFloat())
                    .toPixelMap()
                var longest = 0
                var where = ""
                for (y in 0 until h) {
                    var run = 0
                    for (x in 0 until w) {
                        val c = px[x, y]
                        if ((c.red + c.green + c.blue) / 3f < 0.4f) {
                            run++
                            if (run > longest) {
                                longest = run
                                where = "(${x - run + 1}..$x, $y)"
                            }
                        } else {
                            run = 0
                        }
                    }
                }
                assertTrue(
                    "dir=$dir p=$p tilt=$tilt 版心边列被抹成了横线：$where 连了 $longest px",
                    longest <= 24,
                )
            }
        }
    }

    /**
     * 纸卷是**不透明的一张纸**：版心之外没有纹理的那一段必须是纸色。
     *
     * 页栅格只到版心，纸卷卷到页边距时纹理坐标出界（见 [backMarginDoesNotRepeatTheBodyEdgeColumn]）。
     * 若只把越界采样按透明处理而不垫纸色，纸卷靠折痕那一段会透出**下面那一页的内容**——
     * 纸卷看起来是半透明的。
     */
    @Test
    fun rollStaysOpaqueWhereTheBodyRasterHasNoTexture() {
        val w = 400
        val h = 600
        val mid = h / 2
        val redBody = solid(200, 300, red)
        val blueBody = solid(200, 300, blue)
        for (dir in listOf(1, -1)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), 0.45f, dir)
            val radius = curlRadius(shape)
            val px = render(shape, redBody, blueBody, texLeft = 100f, texTop = 150f).toPixelMap()
            val inward = if (dir >= 0) 1f else -1f
            var checked = 0
            for (k in 1..3) {
                val x = (shape.foldX + inward * radius * (1f - k / 4f)).toInt()
                val c = px[x, mid]
                val spread = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
                assertTrue(
                    "dir=$dir 纸卷 ($x,$mid) 透出了底下的内容（$c）—— 版心之外那一段没有垫纸色",
                    spread < 0.06f,
                )
                checked++
            }
            assertTrue("dir=$dir 纸卷没采样到", checked > 0)
        }
    }

    private fun luma(c: Color): Float = (c.red + c.green + c.blue) / 3f
}
