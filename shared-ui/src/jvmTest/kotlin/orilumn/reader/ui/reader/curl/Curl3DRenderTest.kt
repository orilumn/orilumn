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
import kotlin.math.roundToInt
import kotlin.math.sqrt
import orilumn.reader.ui.reader.RasterTexture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun images(page: ImageBitmap) = CurlPageImages().apply { this.page = RasterTexture(page) }

    private fun render(
        shape: CurlShape,
        front: ImageBitmap,
        back: ImageBitmap,
        frontTex: Offset = Offset.Zero,
        backTex: Offset = Offset.Zero,
        arriving: Boolean = false,
    ): ImageBitmap {
        val w = shape.pageW.toInt()
        val h = shape.pageH.toInt()
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            val drawn = drawCurl3DFrame(
                shape, images(front), images(back), paper, frontTex, backTex, arriving = arriving,
            )
            assertTrue("这一帧必须由网格画出来", drawn)
        }
        return img
    }

    /**
     * 生产路径的**完整**画法：3D 交不出网格时退回 2D 折页（与 `FlipCurlLayer` 同序）。
     *
     * 起手那一帧（折痕还在静止位置）3D 没有可掀起的材料，走的就是这条退路——判据要看
     * 「读者实际看到的那一帧」，不能只锁 3D 分支。
     */
    private fun renderLikeLayer(
        shape: CurlShape,
        front: ImageBitmap,
        back: ImageBitmap,
        frontTex: Offset = Offset.Zero,
        backTex: Offset = Offset.Zero,
    ): ImageBitmap {
        val w = shape.pageW.toInt()
        val h = shape.pageH.toInt()
        val img = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = true)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w.toFloat(), h.toFloat())) {
            if (!drawCurl3DFrame(shape, images(front), images(back), paper, frontTex, backTex)) {
                drawCurlFrame(shape, images(front), images(back), paper, frontTex, backTex)
            }
        }
        return img
    }

    @Test
    fun untouchedFrameIsTheCurrentPage() {
        val w = 400
        val h = 600
        // 这里直接量低层几何：两个方向都传 front = 当前页，起手帧都画 front。
        // 翻页层实际翻上一页时会把「被翻的那张」换成目标页（见 [previousPageTurnsTheTargetSheet]），
        // 低层镜像几何只用来对照，不再直接上屏。
        for (dir in listOf(1, -1)) {
            val px = render(curlShape(w.toFloat(), h.toFloat(), 0f, dir), solid(w, h, red), solid(w, h, blue))
                .toPixelMap()
            assertEquals("dir=$dir 起手时画面上是当前页", red, px[200, 300].toArgb())
        }
    }

    /**
     * 两张纸的纹理**各按自己的偏移**落位：正文页栅格只在版心（偏移 = 页边距），封面是整页
     * 烤图（偏移 0,0）。共用一个偏移会让其中一张整体错位 —— 封面被缩进版心、或多出一圈纸边。
     */
    @Test
    fun eachSheetKeepsItsOwnTextureOffset() {
        val w = 400
        val h = 600
        val margin = 30
        // 正文页栅格只有版心那么大；封面烤图是整页。
        val body = solid(w - margin * 2, h - margin * 2, red)
        val cover = solid(w, h, blue)
        val bodyTex = Offset(margin.toFloat(), margin.toFloat())
        // 起手帧只画正面那张（被翻走的那张 = 正文）。
        val startPx = render(
            curlShape(w.toFloat(), h.toFloat(), 0f, 1),
            body,
            cover,
            frontTex = bodyTex,
            backTex = Offset.Zero,
        ).toPixelMap()
        assertEquals("正文页栅格贴在版心", red, startPx[margin + 4, h / 2].toArgb())
        assertEquals("版心外应当是纸色", paper, startPx[4, 4].toArgb())
        // 翻完那一帧只画背面那张（目标页 = 封面）：整页图贴页角，不缩进版心。
        val endPx = render(
            curlShape(w.toFloat(), h.toFloat(), 1f, 1),
            body,
            cover,
            frontTex = bodyTex,
            backTex = Offset.Zero,
        ).toPixelMap()
        assertEquals("封面烤图贴页左上角", blue, endPx[4, 4].toArgb())
        assertEquals("封面烤图铺到页右下角", blue, endPx[w - 4, h - 4].toArgb())
    }

    @Test
    fun settledFrameIsTheTargetPage() {
        val w = 400
        val h = 600
        for (dir in listOf(1, -1)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), 1f, dir, tilt = 0.55f)
            val px = render(shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
            for (x in 0 until w step 7) {
                for (y in 0 until h step 13) {
                    assertEquals("dir=$dir ($x,$y) 翻完那一帧应当只剩目标页", blue, px[x, y].toArgb())
                }
            }
        }
    }

    /**
     * 起手那一帧先停在**静止页**上（整页平铺的当前页），不许一上来就按锚点展开一大块。
     *
     * 读者报的两种起手：屏幕右侧往右滑翻上一页（页边锚点）、屏幕左侧往左滑翻下一页
     * （折痕锚点）——锚点位置离静止位置差着大半个页宽，一帧到位就是「目标页突然展开大半」。
     * 现在拖动开头先在 [PULL_INTRO_MS] 里把折痕滑过去（[CurlPull.blend]），这一帧 blend = 0。
     */
    @Test
    fun pullIntroFrameIsStillTheRestingPage() {
        val w = 400
        val h = 600
        val mid = h / 2
        // 屏幕右侧往右滑翻上一页：页边锚点，静止位置是「纸卷整根在书脊外」。
        val prevPull = CurlPull(
            from = Offset(w.toFloat(), mid.toFloat()),
            to = Offset(w - 60f, mid.toFloat()),
            blend = 0f,
        )
        val prev = curlFrame(w.toFloat(), h.toFloat(), 0.1f, -1, 0f, prevPull)
        val prevPx = renderLikeLayer(prev.shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
        // 贴书脊那几十像素是纸卷的落影（纸卷整根在页外，影还压进来一点），不算「露出上一页」。
        for (x in 40 until w - 40 step 20) {
            assertEquals("上一页起手帧在 x=$x 应当还是当前页", blue, prevPx[x, mid].toArgb())
        }

        // 屏幕左侧往左滑翻下一页：折痕锚点，静止位置是「折痕在自由边」。
        val nextPull = CurlPull(
            from = Offset(120f, mid.toFloat()),
            to = Offset(100f, mid.toFloat()),
            anchor = CurlAnchor.Crease,
            blend = 0f,
        )
        val next = curlFrame(w.toFloat(), h.toFloat(), 0.05f, 1, 0f, nextPull)
        val nextPx = renderLikeLayer(next.shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
        for (x in 40 until w - 40 step 20) {
            assertEquals("下一页起手帧在 x=$x 应当还是当前页", red, nextPx[x, mid].toArgb())
        }
    }

    /**
     * 手指滑出去再滑回起手点（越过按下点）时，画面**不许整块变样**。
     *
     * 真机日志（页 1840×2800，按下点 x=1619）：手指回到 1621 时归一进度 −0.0009、跟手折痕
     * 仍在 1221（折角 619px），下一帧 1617 进度 +0.0008 却照常出折角 —— 两帧只差 2px，
     * 画面却在「一大块折角」与「整页平铺」之间来回跳。根因是渲染层拿累计进度当「翻到哪了」
     * 的总闸（曾经的 `progress <= 0` 只画当前页），而跟手时折痕由手指反解（见 [noLiftYet]）。
     *
     * 锁法：同一根手指、同一帧几何，只让累计进度从正变负，两张成图必须逐像素一致；
     * 且这一帧的折痕右侧仍得露出目标页（还是「翻到一半」，不是整页平铺的当前页）。
     */
    @Test
    fun returningPastTheStartPointKeepsTheFoldedSheet() {
        val w = 400
        val h = 600
        val mid = h / 2
        val pull = CurlPull(from = Offset(w.toFloat(), mid.toFloat()), to = Offset(260f, mid.toFloat()))
        val ahead = curlShape(w.toFloat(), h.toFloat(), 0.02f, 1, 0f, pull)
        val crossed = curlShape(w.toFloat(), h.toFloat(), -0.02f, 1, 0f, pull)
        assertEquals("折痕只听手指，不听累计进度", ahead.foldX, crossed.foldX, 1e-3f)
        assertTrue(
            "这一帧折角还在（折痕 ${crossed.foldX} / 页宽 $w），不该退化成整页平铺",
            crossed.foldX < w * 0.75f,
        )

        val a = render(ahead, solid(w, h, red), solid(w, h, blue)).toPixelMap()
        val b = render(crossed, solid(w, h, red), solid(w, h, blue)).toPixelMap()
        var diff = 0
        for (y in 0 until h step 5) {
            for (x in 0 until w step 5) if (a[x, y] != b[x, y]) diff++
        }
        assertEquals("越过起手点的那一帧整页变了样（$diff 个采样点不同）", 0, diff)
        assertEquals("折痕右侧应当露出目标页", blue, b[w - 5, mid].toArgb())
        assertEquals("折痕左侧仍是当前页", red, b[5, mid].toArgb())
    }

    /**
     * 上一页同样「同一根手指 ⇒ 同一帧」：手指退回起手点，折角照样留在那儿。
     *
     * 旧实现对上一页传 `pull = null`，折痕 = 页宽 × 累计位移——手指一回到起手点，折痕必然
     * 退回书脊、整块折角收成零，读者报的「翻上页有同样的问题」就是它。现在起手在左半边
     * 抓的是**折痕**（[CurlAnchor.Crease]）：折痕穿过手指，与累计进度无关。
     */
    @Test
    fun previousPageFoldAlsoStaysUnderTheFinger() {
        val w = 400
        val h = 600
        val mid = h / 2
        val pull = CurlPull(
            from = Offset(90f, mid.toFloat()),
            to = Offset(300f, mid.toFloat()),
            anchor = CurlAnchor.Crease,
        )
        val ahead = curlFrame(w.toFloat(), h.toFloat(), 0.55f, -1, 0f, pull).shape
        val crossed = curlFrame(w.toFloat(), h.toFloat(), -0.02f, -1, 0f, pull).shape
        assertEquals("折痕只听手指，不听累计进度", ahead.foldX, crossed.foldX, 1e-3f)
        assertEquals("折痕就在手指处", 300f, crossed.foldX, 0.01f)

        val a = render(ahead, solid(w, h, red), solid(w, h, blue)).toPixelMap()
        val b = render(crossed, solid(w, h, red), solid(w, h, blue)).toPixelMap()
        var diff = 0
        for (y in 0 until h step 5) {
            for (x in 0 until w step 5) if (a[x, y] != b[x, y]) diff++
        }
        assertEquals("上一页越过起手点的那一帧整页变了样（$diff 个采样点不同）", 0, diff)
        assertEquals("折痕左侧是翻回来的上一页", red, b[5, mid].toArgb())
        assertEquals("折痕右侧仍是当前页", blue, b[w - 5, mid].toArgb())
    }

    /**
     * 翻上一页时**书脊仍在左**：上一页不是下一页的左右镜像，而是同一套「左边平铺、卷筒朝右」
     * 的几何倒着走。镜像几何会让纸挂到右缘、看起来右边成了书脊（读者：「左边是书脊，不会因为
     * 要翻到上一页，右边就变成书脊了」）。
     *
     * 折痕本身**与翻页方向无关**：同一个手指轨迹、同一个折痕位置，两个方向的折痕是同一根线
     * （都垂直于手指轨迹）。只有「被翻的那张纸」和「露出页」换位。早先这里对上一页取反倾斜，
     * 读者实测「同样在右侧翻下页和翻上页，页边旋转方向相反，翻上页不顺手」——那正是取反的锅。
     */
    @Test
    fun previousPageKeepsTheSpineOnTheLeft() {
        val w = 400f
        val h = 600f
        val tilt = 0.35f
        for (p in listOf(0.1f, 0.2f, 0.45f, 0.9f)) {
            val next = curlFrame(w, h, p, direction = 1, tilt = tilt)
            assertFalse("下一页翻的是当前页", next.turningIsTarget)
            assertEquals("下一页的几何", 1, next.shape.direction)
            assertEquals(p, next.shape.progress, 1e-6f)

            // 同一个折痕位置对应的上一页手势进度是 `1-p`（折痕在 `w·(1-p)`，两方向相同）。
            val prev = curlFrame(w, h, 1f - p, direction = -1, tilt = tilt)
            assertTrue("上一页翻的是上一页那张纸", prev.turningIsTarget)
            assertEquals("上一页的卷曲几何也必须朝左书脊那一侧", 1, prev.shape.direction)
            assertEquals("上一页是同一套几何倒着走", p, prev.shape.progress, 1e-6f)

            // 折痕这条线两个方向逐点相同：位置、斜率、两端横坐标、影的浓淡都不许跟方向走。
            assertEquals("折痕位置", next.shape.foldX, prev.shape.foldX, 1e-3f)
            assertEquals("折痕斜率", next.shape.lean, prev.shape.lean, 1e-6f)
            assertEquals("折痕上端", next.shape.topX, prev.shape.topX, 1e-3f)
            assertEquals("折痕下端", next.shape.bottomX, prev.shape.bottomX, 1e-3f)
            assertEquals("影的浓淡", next.shape.shadowFade, prev.shape.shadowFade, 1e-6f)
            assertTrue(
                "上一页几何的折痕也不许越过左书脊：${prev.shape.topX} / ${prev.shape.bottomX}",
                prev.shape.topX >= 0f && prev.shape.bottomX >= 0f,
            )
        }
    }

    /**
     * 上一页的纸卷属于**上一页**那张纸（目标页），不是当前页。
     *
     * 旧实现把当前页当被翻的纸、只把露出的上一页画在左边，纸的平铺部分留在右缘——
     * 读者看到的就是「右侧成了书脊」。现在卷筒鼓在折痕右侧（掀起侧），筒面纹理来自上一页，
     * 左边平铺的是上一页，右边露出的才是当前页。
     */
    @Test
    fun previousPageTurnsTheTargetSheet() {
        val w = 400
        val h = 600
        val mid = h / 2
        val frame = curlFrame(w.toFloat(), h.toFloat(), 0.45f, direction = -1)
        val radius = curlRadius(frame.shape)
        val px = render(frame.shape, solid(w, h, blue), solid(w, h, red)).toPixelMap()

        // 卷筒在折痕右侧（掀起侧），筒面是上一页的镜像纹理（蓝），不是当前页（红）。
        val rollX = (frame.shape.foldX + radius * 0.5f).toInt()
        val roll = px[rollX, mid]
        assertTrue(
            "上一页的卷筒应当由上一页（蓝）卷成，实测 r=${roll.red} b=${roll.blue}",
            roll.blue > roll.red,
        )
        assertEquals("左边平铺的是上一页", blue, px[5, mid].toArgb())
        assertEquals("右边露出的才是当前页", red, px[w - 5, mid].toArgb())

        // 起手帧仍是当前页、翻完帧是上一页：撤层与交接不会闪。
        val start = render(
            curlFrame(w.toFloat(), h.toFloat(), 0f, direction = -1).shape,
            solid(w, h, blue),
            solid(w, h, red),
        ).toPixelMap()
        assertEquals("上一页起手时还是当前页", red, start[200, mid].toArgb())
        val end = render(
            curlFrame(w.toFloat(), h.toFloat(), 1f, direction = -1).shape,
            solid(w, h, blue),
            solid(w, h, red),
        ).toPixelMap()
        assertEquals("上一页翻完只剩上一页", blue, end[200, mid].toArgb())
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
     * 自由边上那道影**一直在、也不忽有忽无**，而且只铺在纸外那一侧（纸背上不许有灰带）。
     *
     * 这里原先锁的是「自由边外面根本没有影」。那条断言只在折痕**正好竖直**那一帧成立：
     * 竖直时最远那条扫线是整条纸边，两条影带各自只走到自己那一端，搭在自由边上的那一段恰巧
     * 没被画出来。折痕只要斜一点点，两条带子就都要连到「斜向另一边」的那个纸角，那一段就
     * 铺在自由边上了 —— 读者看到的影一直是这样，不是后来改的。所以这条改成锁**影本身**：
     *
     * 1. 竖直那一帧也要有（读者的判断：「完全竖直的时候，根本没画阴影」）；
     * 2. 竖直、略往两边斜，浓淡一致 —— 一帧有、一帧没有就是闪；
     * 3. 影只在纸外那一侧，纸背（平铺的那张纸）身上依然干干净净。
     */
    @Test
    fun freeEdgeOfTheTurnedBackIsShadowedOutsideOnlyAndDoesNotBlink() {
        val w = 400
        val h = 600
        val mid = h / 2
        for (dir in listOf(1, -1)) {
            val p = 0.45f
            val base = curlShape(w.toFloat(), h.toFloat(), p, dir)
            val radius = curlRadius(base)
            // 纸背自由边 = 折痕往「没掀起」那一侧退（掀起长度 − πR）。
            val back = w * p - PI.toFloat() * radius
            assertTrue("dir=$dir 这个进度下应当有平铺的纸背", back > radius)
            val outward = if (dir >= 0) -1f else 1f
            val edge = base.foldX + outward * back
            val shadowWidth = radius * CURL3D_BACK_SHADOW_WIDTH
            var seen = Float.NaN
            for (tilt in listOf(0f, 0.002f, -0.002f)) {
                val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt = tilt)
                val px = render(shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
                fun luma(x: Int): Float {
                    val c = px[x, mid]
                    return (c.red + c.green + c.blue) / 3f
                }
                fun at(along: Float) = luma((edge + outward * along).toInt())
                val besideEdge = at(shadowWidth * 0.35f)
                val plainPaper = at(shadowWidth * 3f)
                assertTrue(
                    "dir=$dir tilt=$tilt 自由边外没有影：边旁 $besideEdge / 空白处 $plainPaper",
                    plainPaper - besideEdge > 0.03f,
                )
                if (!seen.isNaN()) {
                    assertTrue(
                        "dir=$dir tilt=$tilt 自由边外的影忽浓忽淡：$besideEdge / 竖直那一帧 $seen",
                        abs(besideEdge - seen) < 0.01f,
                    )
                }
                seen = besideEdge
                // 纸背（影的内侧）不许有灰带：挨着边与往里一段一样亮。
                assertTrue(
                    "dir=$dir tilt=$tilt 影漏到纸背上了：边内 ${at(-shadowWidth * 0.35f)} / 再往里 ${at(-shadowWidth)}",
                    abs(at(-shadowWidth * 0.35f) - at(-shadowWidth)) < 0.01f,
                )
            }
        }
    }

    /**
     * 折痕在竖直附近来回摆时，影**不许一帧有一帧没有**。
     *
     * 竖直那一刻最远的扫线是整条纸边（不是一个纸角），影带会少掉自由边上的那一整段 ——
     * 读者判「在竖直状态摇摆时会闪烁」。锁法：竖直、略往两边斜这三帧，影的覆盖面积要一样。
     */
    @Test
    fun nearVerticalFoldKeepsTheShadowSteady() {
        val w = 400
        val h = 600
        for (p in listOf(0.3f, 0.45f)) {
            var minCover = Int.MAX_VALUE
            var maxCover = 0
            for (tilt in listOf(-0.002f, 0f, 0.002f)) {
                val shape = curlShape(w.toFloat(), h.toFloat(), p, 1, tilt = tilt)
                val on = render(shape, solid(w, h, red), solid(w, h, blue)).toPixelMap()
                val off = render(shape.copy(shadowFade = 0f), solid(w, h, red), solid(w, h, blue)).toPixelMap()
                var cover = 0
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val a = off[x, y]
                        val b = on[x, y]
                        val d = ((a.red - b.red) + (a.green - b.green) + (a.blue - b.blue)) / 3f
                        if (d > 3f / 255f) cover++
                    }
                }
                minCover = minOf(minCover, cover)
                maxCover = maxOf(maxCover, cover)
            }
            assertTrue("p=$p 影的覆盖量在竖直附近跳了：$minCover..$maxCover", maxCover - minCover < maxCover / 50)
        }
    }

    /**
     * 纸背的三条**纸边**都要认出来：远端自由边 + 两条直角边。
     *
     * 自由边跟着折痕一起斜、两条直角边垂直于折痕：斜折痕时它们会在页面中间相交成直角，
     * 正是读者说的「折起的页角直角两条边」。两条直角边必须落影，少了任何一条，纸背就像
     * 一张剪贴上去的白纸（原话「直角两条边为什么没画阴影」）；自由边那道影只在纸外一侧、
     * 而且竖直时不兴忽有忽无（见 [freeEdgeOfTheTurnedBackIsShadowedOutsideOnlyAndDoesNotBlink]）。
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
            val back = mesh.bands.firstOrNull { it.surface == CurlMesh.Surface.Back }
            assertTrue("dir=$dir 斜折痕下应当有纸背", back != null)
            val edges = mesh.pageEdges()!!
            val px = render(shape, solid(w, h, paper), solid(w, h, paper)).toPixelMap()
            val width = radius * CURL3D_BACK_SHADOW_WIDTH
            fun lumaAt(pt: Offset): Float {
                val c = px[pt.x.toInt().coerceIn(0, w - 1), pt.y.toInt().coerceIn(0, h - 1)]
                return (c.red + c.green + c.blue) / 3f
            }
            // 纸在哪一侧按同一扫线行的另一条页边判（不按重心：纸卷起来时重心会落到纸外）。
            var checked = 0
            val rows = listOf(
                (back!!.firstRow + back.lastRow) / 2,
                back.firstRow + (back.lastRow - back.firstRow) * 3 / 4,
            )
            for (k in 0..1) {
                for (row in rows) {
                    val p = edges[k][row]
                    val inward = edges[1 - k][row] - p
                    val len = hypot(inward.x, inward.y)
                    if (len < 10f) continue
                    val outward = Offset(-inward.x / len, -inward.y / len)
                    val near = p + outward * (width * 0.5f)
                    val far = p + outward * (width * 3f)
                    if (near.x < 6f || near.x > w - 6f || near.y < 6f || near.y > h - 6f) continue
                    if (far.x < 2f || far.x > w - 2f || far.y < 2f || far.y > h - 2f) continue
                    checked++
                    assertTrue(
                        "dir=$dir row=$row 折角的一条直角边没有落影：边旁 ${lumaAt(near)} / 更外侧 ${lumaAt(far)}",
                        lumaAt(near) < lumaAt(far) - 0.02f,
                    )
                }
            }
            assertTrue("dir=$dir 两条直角边都没能采样，测试没测到东西", checked > 0)
        }
    }

    /**
     * 扫线必须**落到页角上**：末行两个端点的材料坐标就是页面四角之一。
     *
     * 扫线按「与上一条差不足半像素就合并」收行，页角那条扫线常常正好落在半像素以内被并掉。
     * 角附近两条页边夹角很小，差半像素的扫线能拉出好几像素长的弦，于是两条页边停在离页角
     * 几个像素的地方：纸带末端是一条凭空截断的斜边，纸角那块扇形影更因为「两条页边没在角上
     * 碰头」整个不画 —— 读者报「页角正好位于卷筒左侧边时阴影扇形会消失」。
     */
    @Test
    fun pageEdgesReachThePageCorner() {
        val w = 400f
        val h = 600f
        var checked = 0
        for (p in listOf(0.19f, 0.27f, 0.3f, 0.45f, 0.7f)) {
            for (tilt in listOf(0.05f, 0.15f, 0.35f)) {
                for (dir in listOf(1, -1)) {
                    val shape = curlShape(w, h, p, dir, tilt = tilt)
                    val mesh = buildCurlMesh(shape, curlRadius(shape))
                    val rows = mesh.arc.size / 2
                    if (rows < 2) continue
                    for (k in 0..1) {
                        val at = (((rows - 1) * 2) + k) * 2
                        val u = mesh.texCoords[at]
                        val v = mesh.texCoords[at + 1]
                        val onX = abs(u) < 0.6f || abs(u - w) < 0.6f
                        val onY = abs(v) < 0.6f || abs(v - h) < 0.6f
                        assertTrue(
                            "p=$p tilt=$tilt dir=$dir 末行第 ${k + 1} 个端点 ($u,$v) 不在页角上：" +
                                "扫线没走到页角，纸边和纸角的扇形影都会在角前截断",
                            onX && onY,
                        )
                    }
                    checked++
                }
            }
        }
        assertTrue("一帧都没量到，测试没测到东西", checked > 0)
    }

    /**
     * 纸角的**转角影**要连成一片：两条直角边共用一个纸角时（自由边退化成一点），
     * 角外的等分线方向也必须比远处暗 —— 早先两条影带各停一条垂线，角上正好空一个楔形缺口，
     * 读者看到的是「直角处两条阴影带没有融合在一起」。
     */
    @Test
    fun foldedCornerShadowsMeetAtTheSharedCorner() {
        val w = 400
        val h = 600
        // 后面的 `0.27f to 0.05f` 是读者报的那一帧：页角正好落在卷筒左侧边（平铺段长度≈0）时，
        // 页角扫线曾被「差不足半像素就合并」并掉，两条页边停在离角 1.8px 处，扇形整个不画。
        for ((p, tilt) in listOf(0.3f to 0.25f, 0.27f to 0.05f)) for (dir in listOf(1, -1)) {
            val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt = tilt)
            val radius = curlRadius(shape)
            val mesh = buildCurlMesh(shape, radius)
            val corners = mesh.backCorners()
            assertTrue("p=$p tilt=$tilt dir=$dir 斜折痕下应当有纸背", corners != null)
            val (near0, far0, far1, near1) = corners!!
            // 这个几何下自由边退化成一点，两条直角边共用 far0。
            assertTrue("p=$p tilt=$tilt dir=$dir 自由边没有退化，测不到转角", (far0 - far1).getDistance() < 0.5f)
            val center = corners.reduce { acc, p -> acc + p } / corners.size.toFloat()
            fun outward(to: Offset): Offset {
                val dx = to.x - far0.x
                val dy = to.y - far0.y
                val len = hypot(dx, dy)
                var n = Offset(dy / len, -dx / len)
                val mx = (far0.x + to.x) * 0.5f - center.x
                val my = (far0.y + to.y) * 0.5f - center.y
                if (mx * n.x + my * n.y < 0f) n = Offset(-n.x, -n.y)
                return n
            }
            val bisector = outward(near0) + outward(near1)
            val bisLen = hypot(bisector.x, bisector.y)
            assertTrue("p=$p tilt=$tilt dir=$dir 两条直角边不在纸角上相交", bisLen > 0.5f)
            val unit = Offset(bisector.x / bisLen, bisector.y / bisLen)
            val width = radius * CURL3D_BACK_SHADOW_WIDTH
            val px = render(shape, solid(w, h, paper), solid(w, h, paper)).toPixelMap()
            fun lumaAt(pt: Offset): Float {
                val x = pt.x.toInt().coerceIn(0, w - 1)
                val y = pt.y.toInt().coerceIn(0, h - 1)
                val c = px[x, y]
                return (c.red + c.green + c.blue) / 3f
            }
            val near = far0 + unit * (width * 0.35f)
            val far = far0 + unit * (width * 2.5f)
            if (near.x < 6f || near.x > w - 6f || near.y < 6f || near.y > h - 6f) continue
            if (far.x < 2f || far.x > w - 2f || far.y < 2f || far.y > h - 2f) continue
            assertTrue(
                "p=$p tilt=$tilt dir=$dir 纸角转角处没有影（两条影带中间空着）：角外 ${lumaAt(near)} / 更远处 ${lumaAt(far)}",
                lumaAt(near) < lumaAt(far) - 0.02f,
            )
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
     * 纸卷的筒底（纸从筒上绕回平铺段的切线，也就是折痕那条线）**不许出现折痕线**。
     *
     * 早先这里铺过一层「筒底贴页处最深的淡暗」，边界就钉在切线上：一边是平铺的纸背、
     * 一边是同色的卷面，中间一道硬边，读者看到的是「筒的左侧平白多出一条折痕」。
     * 现在筒身只有「反光」那一层明暗、筒内侧那层暗已退役：沿折痕中段量，相邻像素不许跳。
     */
    @Test
    fun rollBaseHasNoCreaseLine() {
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
            val step = if (dir >= 0) 1 else -1
            var worst = 0f
            var at = 0
            // 从纸卷这一侧扫到纸背那一侧，跨过切线（只在筒根这一小段量，再远就是落影自己的边）。
            for (k in -10..10) {
                val x = fold + k * step
                val d = abs(luma(x) - luma(x + step))
                if (d > worst) {
                    worst = d
                    at = x
                }
            }
            assertTrue("dir=$dir 筒底出现台阶：x=$at 处相邻像素跳了 $worst", worst < 0.02f)
        }
    }

    /** 点在多边形内（射线法）：纸卷各段的轮廓都是简单多边形，够用。 */
    private fun inside(ring: List<Offset>, p: Offset): Boolean {
        var hit = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.y > p.y) != (b.y > p.y) &&
                p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
            ) {
                hit = !hit
            }
            j = i
        }
        return hit
    }

    /**
     * 纸背上**不许有影**：沿页边那道影只落在旧页上，不许铺到纸面（读者圈的「纸背右边那条灰带」）。
     *
     * 影带**只铺页边朝外那一侧**，纸背那一侧压根不画；早先两侧都铺、想靠「纸画在影之后」把
     * 内侧那半条盖掉，但影一旦挪到纸段之后（否则卷起段会被卷筒盖住），盖不掉的那半条就成了
     * 纸背右边那条灰带。
     *
     * 只量**平铺段**（Back）那几行：折痕端接着纸卷，纸卷自己的落影与两段纸的接缝都在那里，
     * 与「影带铺到纸背上」不是一回事。纸背还没长出来（`Back` 段为空）的进度不量。
     * 取点沿**页边的法向**往里量：页边与扫线并不垂直，顺着扫线量会贴着页边滑出去。
     */
    @Test
    fun pageBackCarriesNoEdgeShadow() {
        val w = 400
        val h = 600
        val plain = luma(Color(paper))
        for ((p, tilt) in listOf(0.3f to 0.25f, 0.45f to 0.35f, 0.6f to 0f)) {
            for (dir in listOf(1, -1)) {
                val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt)
                val mesh = buildCurlMesh(shape, curlRadius(shape))
                val edges = mesh.pageEdges() ?: continue
                val back = mesh.bands.firstOrNull { it.surface == CurlMesh.Surface.Back } ?: continue
                if (back.lastRow - back.firstRow < 2) continue
                val px = render(shape, solid(w, h, paper), solid(w, h, paper)).toPixelMap()
                fun lumaAt(x: Int, y: Int): Float {
                    val c = px[x, y]
                    return (c.red + c.green + c.blue) / 3f
                }
                var checked = 0
                for (k in 0..1) {
                    val edge = edges[k]
                    val other = edges[1 - k]
                    // 两头各留 3 行：折痕端接着纸卷，纸角端距纸的自由边不到 1px，
                    // 沿法向量的 3px 会落到纸外（那是旧页上的影，本来就该有）。
                    var i = back.firstRow + 3
                    while (i <= back.lastRow - 3) {
                        val at = edge[i]
                        val across = other[i] - at
                        // 页边切线取前后两点的差，法向再与之垂直；纸在 `other` 那一侧。
                        val before = edge[maxOf(i - 1, 0)]
                        val after = edge[minOf(i + 1, edge.size - 1)]
                        val tx = after.x - before.x
                        val ty = after.y - before.y
                        val tl = hypot(tx, ty)
                        if (tl < 1e-3f) {
                            i += 5
                            continue
                        }
                        var nx = -ty / tl
                        var ny = tx / tl
                        val len = hypot(across.x, across.y)
                        // 纸角附近纸太窄（扫线几乎与页边平行），法向判不准，量不到「纸面里侧」。
                        if (len < 60f || abs(nx * across.x + ny * across.y) / len < 0.3f) {
                            i += 5
                            continue
                        }
                        if (nx * across.x + ny * across.y < 0f) {
                            nx = -nx
                            ny = -ny
                        }
                        for (d in listOf(3f, 6f, 12f)) {
                            val x = (at.x + nx * d).roundToInt()
                            val y = (at.y + ny * d).roundToInt()
                            if (x < 2 || x > w - 3 || y < 2 || y > h - 3) continue
                            checked++
                            assertTrue(
                                "p=$p dir=$dir 页边内侧 ${d}px ($x,$y) 比纸面暗了 " +
                                    "${plain - lumaAt(x, y)} —— 影铺到纸背上了",
                                lumaAt(x, y) >= plain - 0.02f,
                            )
                        }
                        i += 5
                    }
                }
                assertTrue("p=$p dir=$dir 没量到纸背，测试没测到东西", checked > 0)
            }
        }
    }

    /**
     * 页边那道影**只铺到卷筒俯视图投影线为止**（纸从正面翻成反面的那条最外沿，弧长 πR/2），
     * 越过它再往折痕那侧的钩子不画。
     *
     * 判法两条：几何上，收尾那一行的页边离折痕正好一个筒径；成图上，收尾之前朝外与
     * 「关掉影」的出图一致（没有影），收尾之后（卷筒曲线段与平铺段）才有影。
     */
    @Test
    fun edgeShadowStopsAtTheRollOutline() {
        val w = 400
        val h = 600
        for ((p, tilt) in listOf(0.3f to 0.25f, 0.45f to 0.35f)) {
            for (dir in listOf(1, -1)) {
                val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt)
                val radius = curlRadius(shape)
                val mesh = buildCurlMesh(shape, radius)
                val edges = mesh.pageEdges() ?: continue
                val back = mesh.bands.firstOrNull { it.surface == CurlMesh.Surface.Back } ?: continue
                if (back.lastRow - back.firstRow < 2) continue
                val taperFrom = mesh.firstRowAtArc(PI.toFloat() * radius * 0.5f)
                assertTrue("p=$p dir=$dir 纸背已经露出来了，却找不到收尾那一行", taperFrom != null)
                val top = Offset(shape.topX, 0f)
                val foldLen = hypot(shape.bottomX - shape.topX, h.toFloat())
                val ux = (shape.bottomX - shape.topX) / foldLen
                val uy = h.toFloat() / foldLen
                for (k in 0..1) {
                    val pt = edges[k][taperFrom!!]
                    val d = abs((pt.x - top.x) * uy - (pt.y - top.y) * ux)
                    assertTrue("p=$p dir=$dir 收尾处离折痕 ${d}px，筒径 $radius", abs(d - radius) < 2.5f)
                }
                val on = render(shape, solid(w, h, paper), solid(w, h, paper)).toPixelMap()
                val off = render(shape.copy(shadowFade = 0f), solid(w, h, paper), solid(w, h, paper)).toPixelMap()
                fun luma(map: androidx.compose.ui.graphics.PixelMap, x: Int, y: Int): Float {
                    val c = map[x, y]
                    return (c.red + c.green + c.blue) / 3f
                }
                var hookChecked = 0
                var keptChecked = 0
                for (k in 0..1) {
                    val edge = edges[k]
                    val outward = outwardNormals(edge, edges[1 - k], back.firstRow..back.lastRow) ?: continue
                    // 收尾之前（绕筒退回折痕的钩子）：不许有影。
                    var i = 4
                    while (i <= taperFrom!! - 3) {
                        val at = edge[i]
                        val o = outward[i]
                        val x = (at.x + o.x * 6f).roundToInt()
                        val y = (at.y + o.y * 6f).roundToInt()
                        if (x < 2 || x > w - 3 || y < 2 || y > h - 3 || luma(off, x, y) < 0.9f) {
                            i += 4
                            continue
                        }
                        hookChecked++
                        assertTrue(
                            "p=$p dir=$dir 收尾之前的钩子 (${at.x.toInt()},${at.y.toInt()}) 还在铺影",
                            abs(luma(on, x, y) - luma(off, x, y)) < 0.01f,
                        )
                        i += 4
                    }
                    // 收尾之后（卷筒曲线段 + 平铺段）：影要在，宽度按收尾起算的归一化弧长。
                    var cumT = 0f
                    val tAt = FloatArray(edge.size)
                    for (i in taperFrom + 1 until edge.size) {
                        cumT += (edge[i] - edge[i - 1]).getDistance()
                        tAt[i] = cumT
                    }
                    if (cumT < 1f) continue
                    for (i in taperFrom + 1 until edge.size) tAt[i] /= cumT
                    var j = taperFrom + 4
                    while (j <= edge.size - 4) {
                        val at = edge[j]
                        val o = outward[j]
                        val x = (at.x + o.x * 3f).roundToInt()
                        val y = (at.y + o.y * 3f).roundToInt()
                        if (x < 2 || x > w - 3 || y < 2 || y > h - 3 ||
                            radius * CURL3D_BACK_SHADOW_WIDTH * tAt[j] < 10f
                        ) {
                            j += 4
                            continue
                        }
                        keptChecked++
                        assertTrue(
                            "p=$p dir=$dir 收尾之后的页边 (${at.x.toInt()},${at.y.toInt()}) 没有影",
                            luma(on, x, y) < luma(off, x, y) - 0.008f,
                        )
                        j += 4
                    }
                }
                assertTrue("p=$p dir=$dir 没量到钩子，测试没测到东西", hookChecked > 0)
                assertTrue("p=$p dir=$dir 没量到收尾之后的影，测试没测到东西", keptChecked > 0)
            }
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
            val px = render(
                shape,
                body,
                body,
                frontTex = Offset(margin.toFloat(), margin.toFloat()),
                backTex = Offset(margin.toFloat(), margin.toFloat()),
            ).toPixelMap()
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
                val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt)
                val px = render(
                    shape,
                    body,
                    body,
                    frontTex = Offset(margin.toFloat(), margin.toFloat()),
                    backTex = Offset(margin.toFloat(), margin.toFloat()),
                ).toPixelMap()
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
            val px = render(shape, redBody, blueBody, frontTex = Offset(100f, 150f), backTex = Offset(100f, 150f))
                .toPixelMap()
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

    /**
     * 卷筒与纸正面交界处**不能有一条纸色接缝线**。
     *
     * 两层各自抗锯齿叠在同一条边上时，交界那一列像素拿到「半列纸色 + 半列纹理」，比两侧都亮 ——
     * 读者看到的就是折痕上一条虚线似的亮缝（原话「卷筒与纸正面交界处有一条多余的接缝线；
     * 背面也有」）。这条按「折痕那一列不许比左右两列的均值亮」来锁。
     *
     * ## 只判折痕落在**正文**里的那几行
     *
     * 折痕扫过页边距时，那一列本来就是**纸**（页边距是白纸），纸边压在折痕上是内容而不是接缝；
     * 把那些行也算进来，判据只会变成「页边距不许出现在折痕上」。所以一列里只要折痕自己或
     * 左右任一邻列已经是纸色，这一行就跳过 —— 剩下的才是「折痕切开正文」、必须干净的行。
     *
     * 正文用**深墨**而不是中灰：纸色叠在深墨上差 0.17（中灰只有 0.09），缝一出现就看得见。
     */
    @Test
    fun foldHasNoPaperSeamLine() {
        val w = 900
        val h = 1300
        // 正文页栅格 = 版心：平板实测 1590×2400 放在 1840×2800 里，这里按同一比例缩。
        val body = solid(778, 1114, 0xFF202020.toInt())
        val bodyTex = Offset(61f, 93f)
        for (p in listOf(0.25f, 0.40f, 0.55f)) {
            for (dir in listOf(1, -1)) {
                for (tilt in listOf(0f, 0.2f, 0.35f)) {
                    val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt)
                    val pm = render(shape, body, body, frontTex = bodyTex, backTex = bodyTex)
                        .toPixelMap()
                    fun lum(xx: Int, yy: Int): Float {
                        val c = pm[xx, yy]
                        return (c.red + c.green + c.blue) / 3f
                    }
                    var worst = 0f
                    var where = ""
                    var judged = 0
                    for (y in 200 until h - 200 step 2) {
                        val x = (shape.topX + (shape.bottomX - shape.topX) * y.toFloat() / h).roundToInt()
                        if (x < 2 || x > w - 3) continue
                        val l = lum(x - 1, y)
                        val m = lum(x, y)
                        val r = lum(x + 1, y)
                        // 折痕自己或任一侧已经是纸色：这一行是页边距压着折痕，跳过。
                        // 两侧明暗差得远（一侧纸、一侧正文）说明这一行是**内容边界**，缝判不出来。
                        if (m >= 0.9f || l >= 0.9f || r >= 0.9f) continue
                        if (abs(l - r) > 0.2f) continue
                        judged++
                        val gap = m - (l + r) / 2f
                        if (gap > worst) {
                            worst = gap
                            where = "($x,$y) 折痕=${"%.3f".format(m)} 左=${"%.3f".format(l)} " +
                                "右=${"%.3f".format(r)}"
                        }
                    }
                    if (tilt != 0f) {
                        assertTrue("p=$p dir=$dir tilt=$tilt 折痕没有一行落在正文里，判据是空的", judged > 0)
                    }
                    assertTrue(
                        "p=$p dir=$dir tilt=$tilt 折痕上有一条比两侧都亮的接缝线：$where",
                        worst <= 0.08f,
                    )
                }
            }
        }
    }

    /**
     * 卷筒落影必须**贴着纸卷的轮廓**铺，不能跟折痕平行着走。
     *
     * 只管「影的形状」（[CurlMesh.rollShadowStrip] 的轮廓），不看浓度 —— 浓度是另一码事。
     * 影的内侧边界取成与折痕平行的直线时，纸的切边在两端是弯的，直线与弯边之间会裂出一条
     * 没影的窄带（读者截图里圈出来的「中间奇怪的无阴影区」）。
     * 这条锁两件事：影沿轮廓每一段都贴着纸边（内侧不留缝），且纸被页边切断处影跟着到边。
     */
    @Test
    fun rollShadowHugsTheRollOutlineAndReachesCutEdges() {
        val w = 920f
        val h = 1400f
        for ((p, tilt) in listOf(
            0.5f to 0.3f,
            0.5f to -0.3f,
            0.7f to -0.36f,
            0.7f to 0.36f,
        )) {
            for (dir in listOf(1, -1)) {
                val shape = curlShape(w, h, p, dir, tilt)
                val radius = curlRadius(shape)
                val mesh = buildCurlMesh(shape, radius = radius, splits = CURL3D_SPLITS)
                val foldLen = hypot(shape.bottomX - shape.topX, h).coerceAtLeast(1e-3f)
                val up = Offset(h / foldLen, -(shape.bottomX - shape.topX) / foldLen) *
                    (if (dir >= 0) 1f else -1f)
                val strip = mesh.rollShadowStrip(radius * 0.5f, up)
                assertTrue("dir=$dir p=$p tilt=$tilt 没有落影", strip != null)

                val pw = w.toInt()
                val ph = h.toInt()
                val img = ImageBitmap(pw, ph, ImageBitmapConfig.Argb8888, hasAlpha = true)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(img), Size(w, h)) {
                    drawPath(strip!!, Color.Black)
                }
                val px = img.toPixelMap()
                fun dark(x: Int, y: Int): Boolean = px[x, y].alpha > 0.5f
                // 纸的轮廓（纸卷在屏幕上的那块）：影必须从它边上起，不能留缝。
                val ring = mesh.bandOutline(mesh.bands.first { it.surface == CurlMesh.Surface.Recto })
                var hugged = 0
                for (k in ring.indices) {
                    val p = ring[k]
                    if (p.x < 1f || p.y < 1f || p.x > w - 2f || p.y > h - 2f) continue
                    // 轮廓上这点沿法向挪 2px（朝外的那侧由「描出来的影」自己判）
                    var near = false
                    for (d in listOf(-2f, 2f)) {
                        val x = (p.x + up.x * d).toInt()
                        val y = (p.y + up.y * d).toInt()
                        if (x in 0 until pw && y in 0 until ph && dark(x, y)) near = true
                    }
                    assertTrue("dir=$dir p=$p tilt=$tilt 纸边 (${p.x}, ${p.y}) 旁边没有影", near)
                    hugged++
                }
                assertTrue("dir=$dir p=$p tilt=$tilt 没量到纸边", hugged > 8)
                // 纸被上下页边切断时，影跟着到边。
                if (shape.topX > 1f && shape.topX < w - 1f) {
                    assertTrue(
                        "dir=$dir p=$p tilt=$tilt 影没顶到屏幕上边（折痕上端在页内）",
                        (0 until pw).any { dark(it, 0) },
                    )
                }
                if (shape.bottomX > 1f && shape.bottomX < w - 1f) {
                    assertTrue(
                        "dir=$dir p=$p tilt=$tilt 影没顶到屏幕下边（折痕下端在页内）",
                        (0 until pw).any { dark(it, ph - 1) },
                    )
                }
            }
        }
    }

    /**
     * 落影在折痕贴近**自由边**时收掉：中段全浓，贴边归零。
     *
     * 读者报的是「卷曲动画翻上一页收尾，右侧留白闪过一条灰竖带」：折痕扫到自由边附近时筒已经
     * 没纸可卷，影却照旧铺在露出页仅剩的留白上。修法是在影的浓度上再乘一道按折痕位置的系数
     * （见 `freeEdgeShadowFade`），这里只锁这条系数的三点行为——中段不动、贴边归零、越靠边越淡。
     *
     * 不锁具体像素：影的深浅本来就要真机跟手看（见文件头）。
     */
    @Test
    fun rollShadowFadesNearTheFreeEdge() {
        val w = 920f
        val h = 1400f
        val r = w * CURL3D_RADIUS_RATIO
        // 中段（折痕离自由边远超 2.5R）：影全浓。
        assertEquals("中段不该动影", 1f, freeEdgeShadowFade(curlShape(w, h, 0.5f, 1), r), 1e-6f)
        // 折痕贴在自由边：影归零（进度 0 时折痕就在右缘）。
        assertEquals("贴自由边时影要归零", 0f, freeEdgeShadowFade(curlShape(w, h, 0f, 1), r), 1e-6f)
        // 镜像几何的自由边在左缘，同样贴边归零。
        assertEquals("镜像几何贴自由边时影要归零", 0f, freeEdgeShadowFade(curlShape(w, h, 0f, -1), r), 1e-6f)
        // 越靠近自由边越淡，且连续。
        var prev = -1f
        for (p in listOf(0f, 0.02f, 0.05f, 0.1f, 0.15f, 0.2f, 0.25f, 0.3f)) {
            val fade = freeEdgeShadowFade(curlShape(w, h, p, 1), r)
            assertTrue("p=$p 影的淡出系数不该倒退", fade >= prev)
            prev = fade
        }
        assertEquals("过了 2.5R 应当已经全浓", 1f, prev, 1e-6f)
    }

    /**
     * 那条淡出**只管自动播放的「翻上一页」收尾**，不许碰到下一页起手，也不许碰到跟手。
     *
     * 读者报「折痕右侧现在没有阴影」：折痕贴近自由边在两种翻法里都会发生 —— 上一页是**收尾**
     * （纸几乎盖平，影压在仅剩的留白上，该收），下一页是**起手**（筒刚离页，影正该在纸卷脚下）。
     * 同一组 `(pageW, foldX)` 两边完全一样，所以只能按「是不是上一页落位 + 是否跟手」判。
     */
    @Test
    fun freeEdgeFadeOnlyAppliesToTheArrivingPreviousPage() {
        val w = 900f
        val h = 1300f
        val r = w * CURL3D_RADIUS_RATIO
        // 折痕贴在自由边的同一帧。
        val atFreeEdge = curlShape(w, h, 0.1f, 1)
        assertEquals(
            "下一页起手不许淡影",
            1f,
            freeEdgeShadowFadeIfArriving(atFreeEdge, r, arriving = false),
            1e-6f,
        )
        assertTrue(
            "上一页收尾要靠它把影收掉",
            freeEdgeShadowFadeIfArriving(atFreeEdge, r, arriving = true) < 0.3f,
        )
        // 跟手那一帧哪怕正被当成上一页落位也不淡：浓淡交给几何（见 curlShape）。
        assertEquals(
            "跟手时不许淡影",
            1f,
            freeEdgeShadowFadeIfArriving(atFreeEdge.copy(anchored = true), r, arriving = true),
            1e-6f,
        )
        // 中段两边都没有区别。
        assertEquals(
            "中段不该动影",
            1f,
            freeEdgeShadowFadeIfArriving(curlShape(w, h, 0.5f, 1), r, arriving = true),
            1e-6f,
        )
    }

    /** 起手那一帧的落影，真的随那个参数出现/消失（锁住绘制层的接线，不只是纯函数）。 */
    @Test
    fun nextPageStartKeepsTheRollShadowOnScreen() {
        val w = 600
        val h = 900
        val shape = curlShape(w.toFloat(), h.toFloat(), 0.06f, 1, tilt = 0.4f)
        fun cover(arriving: Boolean): Int {
            val on = render(shape, solid(w, h, red), solid(w, h, blue), arriving = arriving).toPixelMap()
            val off = render(shape.copy(shadowFade = 0f), solid(w, h, red), solid(w, h, blue)).toPixelMap()
            var n = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val a = off[x, y]
                    val b = on[x, y]
                    val d = ((a.red - b.red) + (a.green - b.green) + (a.blue - b.blue)) / 3f
                    if (d > 3f / 255f) n++
                }
            }
            return n
        }
        val start = cover(arriving = false)
        val arriving = cover(arriving = true)
        assertTrue("下一页起手的落影必须画出来（覆盖 $start 像素）", start > 400)
        assertTrue("上一页收尾的落影应当收掉（$arriving vs $start）", arriving * 4 < start)
    }

    /**
     * 折痕**基本竖直**时，影带与纸角扇形不许随倾斜微动在页边两侧来回切。
     *
     * 读者原话：「在页面折起基本平行于屏幕左右边框时，有时会在竖直的页边右侧多出一条阴影带，
     * 闪烁明显；不是多出，是把该画在左侧的阴影带画在了右侧，但稍稍移动又回到左侧，就是会来回切换」。
     *
     * 纸角上那几条扫线在屏幕上差不到一像素、折线还在这儿拐直角：逐点比点乘会把 90° 拐弯
     * 当成掉头，`|0|` 的噪声就定了符号 —— 倾斜千分之一就翻一次。这里沿倾斜扫过去，只要影真画
     * 得出来（宽度 ≥ 6px，影的渐入起点见 `ramp`，宽度不到那个数的几帧本来就要收到零），
     * 两条页边末端的**朝外法向方向**（= 纸角扇形的两条边）必须始终在同一侧。
     *
     * 不锁具体角度：折痕从竖直微微倾斜时，扇形本来就会跟着歪一点，锁的只是不翻边。
     */
    @Test
    fun nearVerticalFoldKeepsTheShadowOnOneSide() {
        val w = 400
        val h = 600
        var total = 0
        for (dir in listOf(1, -1)) {
            for (p in listOf(0.15f, 0.18f, 0.2f, 0.25f, 0.3f, 0.31f, 0.35f, 0.45f, 0.55f, 0.65f, 0.75f, 0.85f)) {
                var prev: Int? = null
                var prevTilt = 0f
                var tilt = 0f
                while (tilt <= 0.0201f) {
                    val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt = tilt)
                    val radius = curlRadius(shape)
                    val mesh = buildCurlMesh(shape, radius)
                    val edges = mesh.pageEdges() ?: break
                    val seed = mesh.shadowSeedRows()
                    val flatTurn = PI.toFloat() * radius
                    val ramp = ((mesh.maxArc() - flatTurn * 0.5f) / flatTurn).coerceIn(0f, 1f)
                    val width = radius * CURL3D_BACK_SHADOW_WIDTH * ramp
                    val last = edges[0].size - 1
                    // 只量「两条页边在纸角上相交」的帧：那是扇形在场、也是翻边最刺眼的形状。
                    val corner = seed != null && (edges[0][last] - edges[1][last]).getDistance() < 0.5f
                    if (corner && width >= 6f) {
                        val n0 = outwardNormals(edges[0], edges[1], seed!!)
                        val n1 = outwardNormals(edges[1], edges[0], seed)
                        if (n0 != null && n1 != null) {
                            // 扇形两条边的叉积符号 = 铺向折角外的那一楔在纸边的哪一侧。
                            val cross = n0[last].x * n1[last].y - n0[last].y * n1[last].x
                            val side = if (cross >= 0f) 1 else -1
                            if (prev != null) {
                                assertTrue(
                                    "dir=$dir p=$p tilt=$prevTilt→$tilt 影带翻到了页边另一侧" +
                                        "（影宽 ${width.toInt()}px）：读者看到的就是「阴影带左右来回切换」",
                                    side == prev,
                                )
                            }
                            prev = side
                            prevTilt = tilt
                            total++
                        }
                    }
                    tilt += 0.0005f
                }
            }
        }
        assertTrue("一帧都没量到，测试没测到东西", total > 100)
    }

    /**
     * 折痕在竖直附近**来回摇摆**时，影宽在屏幕上下两端要渐变，不许整段对调。
     *
     * 读者的原话：「现在在页边接近垂直的位置来回摇摆时，两头的阴影宽度是突然切换，很不自然，
     * 请改成根据倾斜角度逐渐转换」。根由是扫线从哪个页角出界：倾斜一换正负，出界的角就换到
     * 另一头，斜坡满宽的那一端从上端搬到下端，一帧之间整段对调。
     *
     * 锁法：沿倾斜扫过去，同一张影带在屏幕最上、最下那一点的宽度，相邻两帧（倾斜差 0.001）
     * 不许跳。折痕竖直时两端一样宽，斜出去才慢慢收成一头满宽、一头为 0。
     */
    @Test
    fun nearVerticalFoldTurnsTheShadowWidthGradually() {
        val w = 400
        val h = 600
        for (dir in listOf(1, -1)) {
            for (p in listOf(0.3f, 0.45f)) {
                // 两条影带的「屏幕最上/最下那一点」的宽度：一条满宽在上、一条满宽在下。
                val prevTop = floatArrayOf(Float.NaN, Float.NaN)
                val prevBottom = floatArrayOf(Float.NaN, Float.NaN)
                var minWidth = Float.MAX_VALUE
                var maxWidthSeen = 0f
                var checked = 0
                var tilt = -0.07f
                while (tilt <= 0.0701f) {
                    val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt = tilt)
                    val radius = curlRadius(shape)
                    val mesh = buildCurlMesh(shape, radius)
                    val edges = mesh.pageEdges()
                    val taperFrom = mesh.firstRowAtArc(PI.toFloat() * radius * 0.5f)
                    val flatTurn = PI.toFloat() * radius
                    val ramp = ((mesh.maxArc() - flatTurn * 0.5f) / flatTurn).coerceIn(0f, 1f)
                    val fullWidth = radius * CURL3D_BACK_SHADOW_WIDTH * ramp
                    if (edges != null && taperFrom != null && fullWidth >= 6f) {
                        val bias = shadowTaperBias(shape.lean, shape.shadowFade)
                        // 每条影带都在屏幕最上、最下那一点量宽度（影宽 = 满宽 × 斜坡）。
                        for (k in 0..1) {
                            val edge = edges[k]
                            val n = edge.size - taperFrom
                            if (n < 2) continue
                            val tAt = FloatArray(n)
                            var cum = 0f
                            for (i in 1 until n) {
                                cum += (edge[taperFrom + i] - edge[taperFrom + i - 1]).getDistance()
                                tAt[i] = cum
                            }
                            if (cum < 1f) continue
                            for (i in 0 until n) tAt[i] /= cum
                            var topI = 0
                            var botI = 0
                            for (i in 0 until n) {
                                if (edge[taperFrom + i].y < edge[taperFrom + topI].y) topI = i
                                if (edge[taperFrom + i].y > edge[taperFrom + botI].y) botI = i
                            }
                            val top = fullWidth * shadowWidthProfile(tAt[topI], bias)
                            val bottom = fullWidth * shadowWidthProfile(tAt[botI], bias)
                            if (!prevTop[k].isNaN()) {
                                assertTrue(
                                    "dir=$dir p=$p tilt=${(tilt * 1000).toInt()} 边$k 屏幕上端的影宽从 " +
                                        "${prevTop[k]} 跳到 $top —— 读者看到的就是「两头的宽度突然切换」",
                                    abs(top - prevTop[k]) <= 6f,
                                )
                                assertTrue(
                                    "dir=$dir p=$p tilt=${(tilt * 1000).toInt()} 边$k 屏幕下端的影宽从 " +
                                        "${prevBottom[k]} 跳到 $bottom —— 读者看到的就是「两头的宽度突然切换」",
                                    abs(bottom - prevBottom[k]) <= 6f,
                                )
                            }
                            prevTop[k] = top
                            prevBottom[k] = bottom
                            minWidth = minOf(minWidth, minOf(top, bottom))
                            maxWidthSeen = maxOf(maxWidthSeen, maxOf(top, bottom))
                            checked++
                        }
                    }
                    tilt += 0.001f
                }
                assertTrue("dir=$dir p=$p 一帧都没量到，测试没测到东西", checked > 20)
                // 真的从「两端一样宽」转到了「一头满宽、一头为 0」，不是整段都没动。
                assertTrue(
                    "dir=$dir p=$p 影宽整段没有随倾斜变化（$minWidth..$maxWidthSeen），测了个寂寞",
                    maxWidthSeen > 6f && minWidth < maxWidthSeen * 0.25f,
                )
            }
        }
    }

    /**
     * 露出页**不许把颜色渗到折痕那一列**（读者报的「纸正面与卷筒左侧的接缝」的正面对应那条）。
     *
     * 露出页是垫在纸底下的一张纸，可它的边也从折痕起笔：抗锯齿的**半列**压在折痕上，把新页的
     * 颜色混进正面内容。露出页在折痕附近是纸色时最明显 —— 新页栅格还没就绪的那一帧整块都是纸，
     * 折痕扫过页边距时也是纸：深色正面页上看是一条白缝、浅色页面上看是一条灰缝，与折痕平行、
     * 一直在。
     *
     * 这里摆最不利的配色：正面整页深灰（封面口径，整页烤图）+ 露出页整页纸色，折痕一列只要
     * 混进半列纸色就比两侧亮一截。判据取「本列相对左右两列平均值的偏离」：平滑的明暗渐变在
     * 中间列上≈0，只有一像素线才判得出来。
     */
    @Test
    fun revealPageDoesNotBleedOntoTheFold() {
        val w = 900
        val h = 1300
        val cover = solid(w, h, 0xFF808080.toInt())
        // 露出页整页纸色：新页栅格没就绪的那一帧（以及折痕压在页边距上的那几行）就是这样。
        val paperPage = solid(w, h, paper)
        for (p in listOf(0.10f, 0.25f, 0.40f)) {
            for (dir in listOf(1, -1)) {
                for (tilt in listOf(0.25f, -0.25f)) {
                    val shape = curlShape(w.toFloat(), h.toFloat(), p, dir, tilt)
                    val pm = render(shape, cover, paperPage).toPixelMap()
                    // 沿折痕法向取剖面、按行平均：逐行的内容噪声被抹平，只剩「折痕那一列」的线。
                    val slope = (shape.bottomX - shape.topX) / h
                    val norm = sqrt(1f + slope * slope)
                    val sums = DoubleArray(FOLD_PROFILE_PX)
                    val counts = IntArray(FOLD_PROFILE_PX)
                    for (y in 260 until h - 260) {
                        val x0 = shape.topX + slope * y
                        for (off in -FOLD_PROFILE_HALF..FOLD_PROFILE_HALF) {
                            val xx = (x0 + off / norm).roundToInt()
                            val yy = (y - off * slope / norm).roundToInt()
                            if (xx in 0 until w && yy in 0 until h) {
                                sums[off + FOLD_PROFILE_HALF] += luma(pm[xx, yy])
                                counts[off + FOLD_PROFILE_HALF]++
                            }
                        }
                    }
                    val v = DoubleArray(FOLD_PROFILE_PX) {
                        if (counts[it] == 0) 0.0 else sums[it] / counts[it]
                    }
                    var worst = 0.0
                    var where = ""
                    for (off in -1..1) {
                        val i = off + FOLD_PROFILE_HALF
                        val d = abs(v[i] - (v[i - 1] + v[i + 1]) / 2.0)
                        if (d > worst) {
                            worst = d
                            where = "偏移 $off（0 = 折痕列）剖面 " +
                                v.joinToString(" ") { "%.3f".format(it) }
                        }
                    }
                    assertTrue(
                        "p=$p dir=$dir tilt=$tilt 折痕上有一条接缝线：偏离 ${"%.3f".format(worst)} $where",
                        worst <= 0.03,
                    )
                }
            }
        }
    }

    private companion object {
        /** 折痕剖面的半宽与总点数：±6 像素足够看出「一条一像素线」。 */
        const val FOLD_PROFILE_HALF = 6
        const val FOLD_PROFILE_PX = FOLD_PROFILE_HALF * 2 + 1
    }

    private fun luma(c: Color): Float = (c.red + c.green + c.blue) / 3f
}
