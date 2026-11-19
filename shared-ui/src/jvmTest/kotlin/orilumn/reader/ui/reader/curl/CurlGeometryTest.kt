package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折页几何的冒烟锁（**不是像素基准**）：只锁几件会静默坏掉的事。
 *
 * 折痕扫过的方向、倾斜朝哪边、结算头尾怎么收——这几处写反了页面照样能翻，
 * 只是翻得不对（折痕从书脊往页边扫、倾斜与手指垂直变平行、末尾跳一下），
 * 肉眼要盯很多帧才看得出，正是最该用单测兜住的一类。
 */
class CurlGeometryTest {

    @Test
    fun foldSweepsFromRightToLeftForNextPage() {
        val w = 400f
        val exit = w * CURL3D_RADIUS_RATIO
        assertEquals("起手：折痕贴右缘", w, curlShape(w, 600f, 0f, 1).foldX, 0.01f)
        assertEquals(200f, curlShape(w, 600f, 0.5f, 1).foldX, 0.01f)
        assertEquals("退场窗口起点仍按进度走", exit, curlShape(w, 600f, 1f - CURL3D_RADIUS_RATIO, 1).foldX, 0.01f)
        assertEquals("翻完：折痕越过书脊一个筒径，纸卷整根出页", -exit, curlShape(w, 600f, 1f, 1).foldX, 0.01f)
    }

    @Test
    fun foldSweepsFromLeftToRightForPrevPage() {
        val w = 400f
        val exit = w * CURL3D_RADIUS_RATIO
        assertEquals("起手：折痕贴左缘", 0f, curlShape(w, 600f, 0f, -1).foldX, 0.01f)
        assertEquals(200f, curlShape(w, 600f, 0.5f, -1).foldX, 0.01f)
        assertEquals("翻完：折痕越过右缘一个筒径，纸卷整根出页", w + exit, curlShape(w, 600f, 1f, -1).foldX, 0.01f)
    }

    /**
     * 纸卷退场：折痕贴到「纸少的那一侧」页边后再走一个筒径，纸卷整根滚出页边。
     *
     * 两个方向都要有（上一页起手 = 纸卷滚进来，下一页收尾 = 纸卷滚出去），且必须与
     * 线性段**接得上**：在 `折痕 = R` 那里跳一格，读者看到的就是筒忽然挪一下。
     */
    @Test
    fun rollExitsPastTheSpineContinuously() {
        val w = 400f
        val exit = w * CURL3D_RADIUS_RATIO
        val start = 1f - CURL3D_RADIUS_RATIO
        for (dir in listOf(1, -1)) {
            val sign = if (dir > 0) -1f else 1f
            assertEquals("dir=$dir 窗口起点接得上线性段", if (dir > 0) exit else w - exit, curlShape(w, 600f, start, dir).foldX, 0.01f)
            assertEquals("dir=$dir 半程正好骑在页边", if (dir > 0) 0f else w, curlShape(w, 600f, start + (1f - start) / 2f, dir).foldX, 0.01f)
            var prev = curlShape(w, 600f, start, dir).foldX
            for (i in 1..10) {
                val x = curlShape(w, 600f, start + (1f - start) * i / 10f, dir).foldX
                assertTrue("dir=$dir 退场方向反了", (x - prev) * sign > 0f)
                assertTrue("dir=$dir 退场不许跳格：$prev → $x", kotlin.math.abs(x - prev) < w * 0.03f)
                prev = x
            }
            assertEquals("dir=$dir 末帧整根出页", if (dir > 0) -exit else w + exit, prev, 0.01f)
        }
    }

    @Test
    fun tiltMirrorsWithDirection() {
        val w = 400f
        val h = 600f
        val next = curlShape(w, h, 0.5f, 1, tilt = 0.5f)
        assertTrue("向后翻：倾斜让上端左移", next.topX < next.foldX)
        assertTrue("向后翻：倾斜让下端右移", next.bottomX > next.foldX)
        val prev = curlShape(w, h, 0.5f, -1, tilt = 0.5f)
        assertTrue("向前翻镜像：上端右移", prev.topX > prev.foldX)
        assertTrue("向前翻镜像：下端左移", prev.bottomX < prev.foldX)
    }

    /** 折痕两端都夹在 `[−R, 2·页宽]`：右缘之外可以滑出去，书脊一侧最多越过一个筒径（纸卷退场）。 */
    @Test
    fun creaseEndsStayWithinSaneRange() {
        val w = 400f
        val exit = w * CURL3D_RADIUS_RATIO
        val s = curlShape(w, 600f, 0.02f, 1, tilt = -0.9f)
        assertTrue("上端最多越过书脊一个筒径：${s.topX}", s.topX >= -exit)
        assertTrue("两端不该滑出右缘两倍页宽之外", s.bottomX <= w * 2f)
        // 退场末帧：折痕整根在页外，端点要跟着出去——夹回 0 会把折痕的角度掰回来。
        val gone = curlShape(w, 600f, 1f, 1)
        assertTrue("退场末帧折痕应当整根在页外：${gone.topX}", gone.topX < 0f && gone.bottomX < 0f)
    }

    /**
     * 头尾扶正（旧版 `CurlView` 的 tilt fade）对**跟手与结算同口径**：
     * 进度两端各 10% 里把倾斜量线性淡到 0，折角面积因此连续收到零。
     * 早先只在结算里淡，手指退回起手点时折角会在一帧里整块消失。
     *
     * 末段另有更强的约束：折痕贴到书脊（进度 90% 之后就在退场窗口里）时，倾斜必须收到
     * 竖直，否则折痕端点会从书脊穿出去（见 `creaseNeverCrossesTheSpine`），所以 95% 的
     * 倾斜是 0 而不是淡出值。
     */
    @Test
    fun tiltFadesAtBothEnds() {
        val w = 400f
        val h = 600f
        assertEquals("进度 5% 倾斜量淡到一半", 0.25f, curlShape(w, h, 0.05f, 1, 0.5f).lean, 1e-4f)
        assertEquals("中段（进度 50%）不受影响", 0.5f, curlShape(w, h, 0.5f, 1, 0.5f).lean, 1e-4f)
        assertEquals("进度 85% 折痕已靠近书脊，倾斜随位置收小", 60f / 360f, curlShape(w, h, 0.85f, 1, 0.5f).lean, 1e-4f)
        assertEquals("收尾（进度 100%）折痕扶正", 0f, curlShape(w, h, 1f, 1, 0.5f).lean, 1e-4f)
        assertEquals("起手（进度 0%）折痕扶正", 0f, curlShape(w, h, 0f, -1, 0.5f).lean, 1e-4f)
        // 两端折角面积必须连续收到零：进度 0 / 1 的折痕竖直贴边，掀起的纸宽度为 0。
        val head = curlShape(w, h, 0f, 1, 0.9f)
        assertEquals("进度 0 折痕竖直", head.topX, head.bottomX, 1e-4f)
        val tail = curlShape(w, h, 1f, 1, 0.9f)
        assertEquals("进度 1 折痕竖直", tail.topX, tail.bottomX, 1e-4f)
    }

    /**
     * 折痕端点**永远不越过装订边**：左装书的折痕只能收在左缘（或贴着左缘退场一个筒径），
     * 没掀起的那半页始终连在书脊上。
     *
     * 越过书脊时，整条上边（或下边）都被掀起、书脊中段却还连着，版面上就是把这页从书脊上
     * 撕下来。倾斜越大越容易越界，所以斜拖 + 折痕靠近书脊的所有组合都要扫一遍。
     */
    @Test
    fun creaseNeverCrossesTheSpine() {
        val w = 400f
        val h = 600f
        val exit = w * CURL3D_RADIUS_RATIO
        for (p in listOf(0.05f, 0.2f, 0.5f, 0.8f, 0.9f, 0.95f)) {
            for (tilt in listOf(-1f, -0.9f, -0.5f, 0f, 0.5f, 0.9f, 1f)) {
                val s = curlShape(w, h, p, 1, tilt)
                assertTrue(
                    "p=$p tilt=$tilt 折痕上端越过书脊：${s.topX}（折痕 ${s.foldX}，倾斜 ${s.lean}）",
                    s.topX >= -exit,
                )
                assertTrue(
                    "p=$p tilt=$tilt 折痕下端越过书脊：${s.bottomX}",
                    s.bottomX >= -exit,
                )
            }
        }
        // 折痕还在页内时，端点必须真的落在页内（退场窗口的 −R 余量不算）。
        for (p in listOf(0.2f, 0.5f, 0.8f)) {
            for (tilt in listOf(-1f, -0.5f, 0.5f, 1f)) {
                val s = curlShape(w, h, p, 1, tilt)
                assertTrue("p=$p tilt=$tilt 折痕上端出页：${s.topX}", s.topX >= -1e-3f)
                assertTrue("p=$p tilt=$tilt 折痕下端出页：${s.bottomX}", s.bottomX >= -1e-3f)
            }
        }
    }

    @Test
    fun shadowFadesAtBothSettleEnds() {
        assertEquals("动画中段阴影满强度", 1f, tailFade(0.5f), 1e-4f)
        assertEquals("收尾时阴影淡到 0", 0f, tailFade(1f), 1e-4f)
        assertEquals("起手时阴影淡到 0", 0f, tailFade(0f), 1e-4f)
        assertEquals("超出进度不出现负值", 0f, tailFade(1.9f), 1e-4f)
    }

    /** 镜射：竖折痕 x = 100 时，x = 130 的点应当落到 x = 70。 */
    @Test
    fun reflectionMapsAcrossVerticalCrease() {
        val r = Reflection.about(Offset(100f, 0f), Offset(100f, 600f))
        val p = r.map(Offset(130f, 50f))
        assertEquals(70f, p.x, 1e-3f)
        assertEquals(50f, p.y, 1e-3f)
    }

    /** 斜折痕：镜射两次必须回到原点（纸背 = 沿折痕翻过去再翻回来）。 */
    @Test
    fun reflectionIsItsOwnInverse() {
        val r = Reflection.about(Offset(300f, 0f), Offset(180f, 600f))
        val p = Offset(120f, 420f)
        val back = r.map(r.map(p))
        assertEquals(p.x, back.x, 1e-2f)
        assertEquals(p.y, back.y, 1e-2f)
    }

    /** 露出区：折痕在页内时是四边形（折痕上端 → 右上角 → 右下角 → 折痕下端）。 */
    @Test
    fun revealIsQuadWhenCreaseIsInsidePage() {
        val s = curlShape(400f, 600f, 0.5f, 1)
        val pts = revealCorners(s)
        assertEquals(4, pts.size)
        assertEquals(Offset(200f, 0f), pts[0])
        assertEquals(Offset(400f, 0f), pts[1])
        assertEquals(Offset(400f, 600f), pts[2])
        assertEquals(Offset(200f, 600f), pts[3])
    }

    /**
     * 折痕整体滑到右缘之外（下一页面往回拖过头）：露出区退化成零面积。
     * 这时画面上不该出现露出页，只剩当前页 + 折痕旁那道影。
     */
    @Test
    fun revealDegeneratesWhenCreaseLeavesRightEdge() {
        val s = curlShape(400f, 600f, -0.2f, 1)
        assertTrue("折痕应当已经在右缘之外", s.foldX > 400f)
        val pts = revealCorners(s)
        // 两个端点都在右缘之外 ⇒ 只靠「折痕与右缘的交点」凑点，面积为零。
        val area = kotlin.math.abs(
            pts.indices.sumOf { i ->
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                (a.x * b.y - b.x * a.y).toDouble()
            } / 2.0,
        )
        assertEquals("露出区应当退化成零面积", 0.0, area, 1e-3)
    }

    // ---- 跟手锚点（掀起的纸边压在手指上）----
    //
    // 判据只用**真正的网格**：从 `buildCurlMesh` 里取材料坐标 x = 页宽 的那两个顶点
    // （纸的自由边），看它落在屏幕哪。拿几何函数自己的中间量来断言会变成自证。


    /**
     * 纸自由边（材料坐标 = 页宽）在屏幕上的两个顶点。
     *
     * 只取「材料坐标最接近页宽」的那一行：网格行距最小可到 0.37px，用一个固定容差
     * （0.5px）会把紧挨着的上一行一起捞进来，量到的是「纸边往里 0.37px」的位置。
     */
    private fun freeEdgeOnScreen(shape: CurlShape): List<Offset> {
        val mesh = buildCurlMesh(shape, radius = curlRadius(shape))
        var best = Float.MAX_VALUE
        for (i in 0 until mesh.vertexCount) {
            val dx = kotlin.math.abs(mesh.texCoords[i * 2] - shape.pageW)
            if (dx < best) best = dx
        }
        assertTrue("网格里没有自由边（材料坐标 x = 页宽）", best < 0.05f)
        val out = ArrayList<Offset>(2)
        for (i in 0 until mesh.vertexCount) {
            if (kotlin.math.abs(mesh.texCoords[i * 2] - shape.pageW) <= best + 1e-3f) {
                out.add(Offset(mesh.positions[i * 2], mesh.positions[i * 2 + 1]))
            }
        }
        return out
    }

    /**
     * 手指往里拖多少，纸边就贴在手指上多少——这是「跟手」的全部定义。
     *
     * 容差 1.2% 页宽：`D < πR` 段用的是参考实现的闭式近似（实测最大偏差 0.97% 页宽），
     * 半圈以后是精确解（见下一条）。
     */
    @Test
    fun anchorKeepsFreeEdgeUnderFinger() {
        val w = 400f
        val h = 600f
        for (offset in listOf(2f, 10f, 40f, 80f, 120f, 200f, 300f, 380f)) {
            val fx = w - offset
            val shape = curlShape(w, h, progress = offset / w, direction = 1, pull = dragTo(fx, h / 2f))
            val edges = freeEdgeOnScreen(shape)
            assertTrue("offset=$offset 应当有自由边", edges.isNotEmpty())
            for (e in edges) {
                assertEquals("纸边应压在手指上 offset=$offset", fx, e.x, w * 0.012f)
            }
        }
    }

    /** 越过半圈（纸绕满筒又平铺回来）以后是精确解：纸边与手指重合到亚像素。 */
    @Test
    fun anchorIsExactPastHalfTurn() {
        val w = 400f
        val h = 600f
        val halfTurn = kotlin.math.PI.toFloat() * w * CURL3D_RADIUS_RATIO
        for (offset in listOf(halfTurn + 5f, halfTurn + 60f, halfTurn + 150f, halfTurn + 250f)) {
            if (offset >= w) continue
            val fx = w - offset
            val shape = curlShape(w, h, progress = offset / w, direction = 1, pull = dragTo(fx, h / 2f))
            for (e in freeEdgeOnScreen(shape)) {
                assertEquals("平铺段应当精确压在手指上 offset=$offset", fx, e.x, 0.05f)
            }
        }
    }

    /** 手指在页边时折痕就在页边：起手不凭空多出一块折角。 */
    @Test
    fun anchorAtPageEdgeLeavesCreaseAtPageEdge() {
        val s = curlShape(400f, 600f, 0f, 1, pull = dragTo(400f, 300f))
        assertEquals(400f, s.foldX, 0.01f)
    }

    /** 两段解在 `D = πR` 处接得上（纸刚绕满半圈那一刻几何不许跳）。 */
    @Test
    fun anchorIsContinuousAcrossHalfTurn() {
        val w = 400f
        val h = 600f
        val halfTurn = kotlin.math.PI.toFloat() * w * CURL3D_RADIUS_RATIO
        fun foldAt(offset: Float) =
            curlShape(w, h, progress = offset / w, direction = 1, pull = dragTo(w - offset, h / 2f)).foldX
        val before = foldAt(halfTurn - 2f)
        val after = foldAt(halfTurn + 2f)
        assertTrue("半圈处折痕不许回跳（$before → $after）", before - after in 0f..4f)
    }

    /** 单调：手指一路往左，折痕一路往左，不回头（回头 = 画面抖一下）。 */
    @Test
    fun anchorFoldIsMonotone() {
        val w = 400f
        val h = 600f
        var prev = w
        for (i in 1..200) {
            val fx = w - i * 2f
            val foldX = curlShape(w, h, progress = (i * 2f) / w, direction = 1, pull = dragTo(fx, h / 2f)).foldX
            assertTrue("offset=${i * 2f} 折痕回跳了（$prev → $foldX）", foldX <= prev + 1e-3f)
            prev = foldX
        }
    }

    /**
     * 松手交接：`anchoredProgress` 给出的进度，在**不跟手**的几何里必须落回同一个折痕。
     *
     * 否则结算动画第一帧折痕会从手指位置跳回「按位移算的位置」再走（旧版
     * `startCommitAnim` 用可见折痕当起点就是为这件事）。
     */
    @Test
    fun anchoredProgressHandsOverToSameFold() {
        val w = 400f
        val h = 600f
        for (offset in listOf(20f, 60f, 100f, 160f, 240f)) {
            val anchor = Offset(w - offset, h / 2f)
            val progress = offset / w
            val visible = anchoredProgress(dragTo(anchor.x, anchor.y), w, h, tilt = 0f, progress = progress)
            val anchoredFold = curlShape(w, h, progress, 1, pull = dragTo(anchor.x, anchor.y)).foldX
            val handedFold = curlShape(w, h, visible, 1).foldX
            assertEquals("offset=$offset 交接点应当重合", anchoredFold, handedFold, 0.5f)
        }
    }

    // ---- 锚点模式：起手位置定一次，与翻页方向无关 ----

    /** 折痕锚点：折痕真的穿过手指（在手指所在高度），不是「拿手指 x 当折痕 x」的近似。 */
    @Test
    fun creaseAnchorPassesThroughTheFinger() {
        val w = 400f
        val h = 600f
        for ((fx, fy) in listOf(60f to 80f, 150f to 300f, 300f to 520f, 380f to 20f)) {
            val pull = CurlPull(from = Offset(60f, 300f), to = Offset(fx, fy), anchor = CurlAnchor.Crease)
            val s = curlShape(w, h, 0.3f, 1, tilt = 0f, pull = pull)
            val atFinger = s.topX + (s.bottomX - s.topX) * (fy / h)
            assertEquals("折痕应当穿过手指 ($fx, $fy)", fx, atFinger, 0.01f)
        }
    }

    /** 折痕锚点下倾斜再大，折痕也不许从手指上挪开：能收的只有斜率（贴书脊时不越界）。 */
    @Test
    fun creaseAnchorKeepsFingerWhileStayingOffTheSpine() {
        val w = 400f
        val h = 600f
        val pull = CurlPull(from = Offset(300f, 560f), to = Offset(30f, 560f), anchor = CurlAnchor.Crease)
        val s = curlShape(w, h, 0.02f, 1, tilt = 0.9f, pull = pull)
        assertTrue("折痕端点跑出书脊：topX=${s.topX}", s.topX >= -0.01f)
        val atFinger = s.topX + (s.bottomX - s.topX) * (560f / h)
        assertEquals("折痕仍应穿过手指", 30f, atFinger, 0.5f)
    }

    /**
     * 上一页也跟手：同一根手指、进度从正变负（越过起手点），折痕不许动。
     *
     * 读者报的就是这一条。旧实现对 `direction < 0` 传 `pull = null`，折痕 = 页宽 × 累计位移，
     * 手指一退回起手点折痕必然回到书脊、整块折角收成零。
     */
    @Test
    fun prevPageFoldFollowsTheFingerNotTheProgress() {
        val w = 400f
        val h = 600f
        val finger = Offset(300f, 300f)
        val pull = CurlPull(from = Offset(120f, 300f), to = finger, anchor = CurlAnchor.Crease)
        val ahead = curlFrame(w, h, 0.45f, -1, tilt = 0f, pull = pull).shape
        val back = curlFrame(w, h, -0.02f, -1, tilt = 0f, pull = pull).shape
        assertEquals("折痕应当只认手指", finger.x, ahead.foldX, 0.01f)
        assertEquals("进度退回起手点折痕不许跳", ahead.foldX, back.foldX, 0.01f)
        assertTrue("这一帧仍是跟手帧", back.anchored)
    }

    /**
     * 起手接手：`blend = 0` 那一帧就是静止位置（下一页折痕在自由边、上一页在书脊外一个筒径），
     * `blend = 1` 才完全按锚点。中间单调过渡——起手不再一帧跳到锚点。
     *
     * 读者报的就是这两种起手：屏幕右侧往右滑翻上一页（页边锚点，折痕一上来就在页中偏右）、
     * 屏幕左侧往左滑翻下一页（折痕锚点，折痕一上来就贴着手指），目标页当场展开大半。
     */
    @Test
    fun pullEngageStartsAtRestAndEndsAnchored() {
        val w = 400f
        val h = 600f
        val exit = w * CURL3D_RADIUS_RATIO
        // 屏幕右侧往右滑翻上一页：页边锚点，离静止位置最远的那一种。
        val prevPull = CurlPull(from = Offset(w, 300f), to = Offset(340f, 300f))
        val prevRest = curlFrame(w, h, 0.1f, -1, 0f, prevPull.copy(blend = 0f)).shape.foldX
        val prevDone = curlFrame(w, h, 0.1f, -1, 0f, prevPull.copy(blend = 1f)).shape.foldX
        assertEquals("上一页静止位置：纸卷整根在书脊外", -exit, prevRest, 0.01f)
        assertTrue("接到手指后上一页已经掀进来一大块（$prevDone）", prevDone > w * 0.5f)
        var last = prevRest
        for (i in 1..10) {
            val f = curlFrame(w, h, 0.1f, -1, 0f, prevPull.copy(blend = i / 10f)).shape.foldX
            assertTrue("blend=${i / 10f} 折痕应当单调外移（$last → $f）", f >= last - 0.01f)
            last = f
        }
        assertEquals("blend=1 正好落在锚点上", prevDone, last, 0.01f)

        // 屏幕左侧往左滑翻下一页：折痕锚点，静止位置在自由边。
        val nextPull = CurlPull(
            from = Offset(120f, 300f),
            to = Offset(100f, 300f),
            anchor = CurlAnchor.Crease,
        )
        assertEquals(
            "下一页静止位置：折痕在自由边",
            w,
            curlFrame(w, h, 0.01f, 1, 0f, nextPull.copy(blend = 0f)).shape.foldX,
            0.01f,
        )
        val nextDone = curlFrame(w, h, 0.01f, 1, 0f, nextPull.copy(blend = 1f)).shape.foldX
        assertEquals("下一页接到手指后折痕穿手指", 100f, nextDone, 0.01f)
    }

    /** 起手缓动：两端精确、单调、两端都收速度（缓入缓出，半程正好走到一半）。 */
    @Test
    fun pullEngageRampsSmoothly() {
        assertEquals("t=0 还没接到手指", 0f, pullEngage(0f), 1e-4f)
        assertEquals("t=1 完全接到手指", 1f, pullEngage(1f), 1e-4f)
        assertEquals("越界按端点算", 1f, pullEngage(3f), 1e-4f)
        var prev = -1f
        for (i in 0..20) {
            val v = pullEngage(i / 20f)
            assertTrue("缓动应当单调（i=$i）", v >= prev)
            prev = v
        }
        assertEquals("缓入缓出：半程正好走到一半", 0.5f, pullEngage(0.5f), 1e-4f)
        assertTrue("起步要缓（不许一上来就冲）", pullEngage(0.15f) < 0.1f)
        assertTrue("收尾要缓（交还给手指时不带速度）", 1f - pullEngage(0.85f) < 0.1f)
    }

    /** 上一页松手交接：可见折痕换算出来的进度，在不跟手的几何里必须落回同一个折痕。 */
    @Test
    fun prevPageAnchoredProgressHandsOverToSameFold() {
        val w = 400f
        val h = 600f
        for (fx in listOf(60f, 140f, 220f, 300f, 360f)) {
            val pull = CurlPull(from = Offset(40f, 300f), to = Offset(fx, 300f), anchor = CurlAnchor.Crease)
            val visible = anchoredProgress(pull, w, h, tilt = 0f, progress = 0.5f, direction = -1)
            val anchoredFold = curlFrame(w, h, 0.5f, -1, pull = pull).shape.foldX
            val handedFold = curlFrame(w, h, visible, -1).shape.foldX
            assertEquals("fx=$fx 交接点应当重合", anchoredFold, handedFold, 0.5f)
        }
    }

    /** 越界与极端倾斜：不许出 NaN、不许把折痕摆到页边之外（会被画成一块凭空出现的纸）。 */
    @Test
    fun anchorIsClampedAndFinite() {
        val w = 400f
        val h = 600f
        val exit = w * CURL3D_RADIUS_RATIO
        for (tilt in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (fx in listOf(-300f, -10f, 0f, 120f, 399f, 400f, 460f)) {
                val s = curlShape(w, h, 0.3f, 1, tilt = tilt, pull = dragTo(fx, 300f))
                assertTrue("tilt=$tilt fx=$fx foldX 非法", s.foldX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 端点非法", s.topX.isFinite() && s.bottomX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 折痕越界 ${s.foldX}", s.foldX in -exit - 0.01f..w + 0.01f)
            }
        }
    }

    /** 折痕锚点同样不许出 NaN / 不许把折痕摆到页外（下边界由纸卷退场的余量兜住）。 */
    @Test
    fun creaseAnchorIsClampedAndFinite() {
        val w = 400f
        val h = 600f
        val exit = w * CURL3D_RADIUS_RATIO
        for (tilt in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (fx in listOf(0f, 10f, 120f, 300f, 399f, 400f)) {
                val pull = CurlPull(from = Offset(0f, 20f), to = Offset(fx, 590f), anchor = CurlAnchor.Crease)
                val s = curlShape(w, h, 0.3f, 1, tilt = tilt, pull = pull)
                assertTrue("tilt=$tilt fx=$fx foldX 非法", s.foldX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 端点非法", s.topX.isFinite() && s.bottomX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 折痕越界 ${s.foldX}", s.foldX in -exit - 0.01f..w + 0.01f)
                assertTrue("tilt=$tilt fx=$fx 折痕越过书脊 ${s.topX}", s.topX >= -0.01f)
            }
        }
    }

    /**
     * 斜着拖也一样跟手：被抓的那条材料边（起手贴在页边上的点）必须落在手指上。
     *
     * 判据只取网格：找材料坐标最接近抓点的顶点，看它的屏幕位置。参考实现的
     * `updateCurlPos` 也是按「手指相对抓点的位移」反解折痕的，斜折痕下不能拿页角反解。
     */
    @Test
    fun anchorPutsGrabbedEdgePointUnderFinger() {
        val w = 400f
        val h = 600f
        // 只用**折痕端点没被书脊挡住**的拖法：再斜的拖法会把折痕顶到书脊上，
        // 纸边就必然离开手指（物理上限，见 `anchorPinsCreaseToSpineWhenDragWouldTearPage`）。
        for ((downY, fx, fy) in listOf(
            Triple(300f, 150f, 300f),   // 水平
            Triple(300f, 150f, 220f),   // 左上斜拖
            Triple(300f, 150f, 380f),   // 左下斜拖
            Triple(350f, 200f, 250f),   // 陡一点的左上斜拖
            Triple(250f, 200f, 350f),   // 陡一点的左下斜拖
        )) {
            val pull = CurlPull(from = Offset(w, downY), to = Offset(fx, fy))
            val shape = curlShape(w, h, 1f - fx / w, 1, tilt = leanOf(pull, w, h), pull = pull)
            val img = freeEdgePointAt(shape, pull.from.y)
            assertTrue("网格里没有抓点那条材料边 pull=$pull", img != null)
            assertEquals("斜拖时抓点应当压在手指上（x） pull=$pull", fx, img!!.x, w * 0.02f)
            assertEquals("斜拖时抓点应当压在手指上（y） pull=$pull", fy, img.y, h * 0.02f)
        }
    }

    /**
     * 再斜的拖法：折痕本该越过书脊时，端点停在页角，折痕自己收直一点。
     *
     * 读者手上那张纸是**连在书脊上**的，折痕不可能从书脊穿出去——这是「纸边严格跟手」
     * 的物理上限：越靠近书脊，允许的倾斜越小，到书脊正好竖直。代价是这一步里纸边不再
     * 严丝合缝压在手指上（拖得越狠差得越多），换来的是不会把书页「从书脊上撕下来」
     * （折痕从书脊中段穿出、整条上边被掀起）。
     */
    @Test
    fun anchorPinsCreaseToSpineWhenDragWouldTearPage() {
        val w = 400f
        val h = 600f
        // 同样的水平位移，向下斜拖会把上端推到页外（未约束时 topX < 0）。
        val pull = CurlPull(from = Offset(w, 150f), to = Offset(150f, 380f))
        val shape = curlShape(w, h, 1f - 150f / w, 1, tilt = leanOf(pull, w, h), pull = pull)
        assertTrue("折痕上端应当被书脊挡住：${shape.topX}", shape.topX >= -1e-3f)
        assertTrue("约束生效时折痕上端应当就停在页角：${shape.topX}", shape.topX <= w * 0.02f)
        assertTrue("折痕下端仍该在页内：${shape.bottomX}", shape.bottomX >= 0f)
        assertTrue("折痕位置仍在页内：${shape.foldX}", shape.foldX in 0f..w)
        assertTrue("被约束后倾斜量应当比手势要求的更小", kotlin.math.abs(shape.lean) < kotlin.math.abs(leanOf(pull, w, h)))
    }

    /**
     * 手指退回起手点：折角面积**连续**收到零，不许「斜出一大块，再一帧整块消失」。
     *
     * 折痕方向取自「页边抓点 → 手指」这条整段位移，倾斜量正比于 `u_y / u_x`。横向分量
     * 重新趋零时这个比率必然爆掉，光靠斜率夹取只会把折痕顶到最大倾斜：折痕中点还贴着
     * 手指，折痕本身却从页角斜切走大半个页——读者原话「接近之前的 x 坐标时，阴影逐渐
     * 消失，然后整个折起的页面瞬间消失」。所以逐像素扫过起手点锁三件事：
     *
     * 1. 指尖正好回到起手点：折痕竖直贴边，一页空纸都没掀起来；
     * 2. 相邻一像素之间折痕端点不许跳（读者看到的是「整块消失」，不是角度变化）；
     * 3. 起手点附近掀起的纸只许跟着横向行程收小（不允许斜切出一大块三角）。
     */
    @Test
    fun returningToTheStartCollapsesTheFlapWithoutAJump() {
        val w = 1840f
        val h = 2800f
        val grabY = 1200f
        // 纵向抖动就是「手指没那么直」的那几像素——比率爆掉靠它，所以三种都扫。
        for (dy in listOf(-40f, 10f, 40f)) {
            var prev: CurlShape? = null
            var dx = -80f
            while (dx <= 40f) {
                val pull = CurlPull(from = Offset(w, grabY), to = Offset(w + dx, grabY + dy))
                val s = curlShape(
                    w, h,
                    progress = (-dx / w).coerceIn(-0.9f, 1.9f),
                    direction = 1,
                    tilt = leanOf(pull, w, h),
                    pull = pull,
                )
                if (dx == 0f) {
                    assertEquals("指尖回到起手点：折痕贴边 dy=$dy", w, s.foldX, 0.01f)
                    assertEquals("指尖回到起手点：端点也贴边 dy=$dy", w, s.topX, 0.01f)
                    assertEquals("指尖回到起手点：端点也贴边 dy=$dy", w, s.bottomX, 0.01f)
                }
                if (kotlin.math.abs(dx) <= 60f) {
                    // 掀起来的纸离自由边最远多远：折痕两端与折痕中点都算。
                    val reach = w - minOf(s.topX, s.bottomX, s.foldX)
                    assertTrue(
                        "dx=$dx dy=$dy 起手点附近掀起的纸只许跟横向行程成正比，实际 $reach",
                        reach <= 7f * kotlin.math.abs(dx) + 60f,
                    )
                }
                if (prev != null) {
                    val p = prev!!
                    assertTrue(
                        "dx=$dx dy=$dy 折痕上端跳了：${p.topX} → ${s.topX}",
                        kotlin.math.abs(s.topX - p.topX) <= 80f,
                    )
                    assertTrue(
                        "dx=$dx dy=$dy 折痕下端跳了：${p.bottomX} → ${s.bottomX}",
                        kotlin.math.abs(s.bottomX - p.bottomX) <= 80f,
                    )
                    assertTrue(
                        "dx=$dx dy=$dy 折痕中点跳了：${p.foldX} → ${s.foldX}",
                        kotlin.math.abs(s.foldX - p.foldX) <= 80f,
                    )
                }
                prev = s
                dx += 1f
            }
        }
    }

    /**
     * 跟手时「一页都没掀 / 已经翻完」这两个收尾判据**不许看进度**（见 [noLiftYet]）。
     *
     * 折痕由手指反解，进度只是「手指相对起手点的累计位移」：读者向左滑出去、再向右滑回
     * 起手点，进度会先掉到 0 以下，折角却还在。按进度收尾就是整块折角一帧消失
     * （读者：「接近之前的 x 坐标时，阴影逐渐消失，然后整个折起的页面瞬间消失，动画结束」）。
     */
    @Test
    fun anchoredFramesDoNotTakeTheProgressEnds() {
        val w = 1840f
        val h = 2800f
        val mid = h / 2f
        val pull = CurlPull(from = Offset(w, mid), to = Offset(1600f, mid))
        // 手指越过按下点之后，累计进度是负的；折痕位置与进度无关（同一根手指）。
        val crossed = curlShape(w, h, progress = -0.02f, direction = 1, tilt = 0f, pull = pull)
        assertTrue("跟手帧必须标成 anchored", crossed.anchored)
        assertFalse("跟手时进度负了也不许收成整页平铺", crossed.noLiftYet())
        assertFalse("跟手时进度负了也不许收成整页平铺", crossed.fullyTurned())
        assertEquals(
            "越过起手点的那一帧折痕仍在页内（还折着六百多像素）",
            curlShape(w, h, 0.02f, 1, 0f, pull).foldX,
            crossed.foldX,
            1e-3f,
        )

        // 手指退回自由边：折角自己收到零，这时候才轮到渲染层只画当前页。
        val atEdge = curlShape(w, h, -0.02f, 1, 0f, CurlPull(from = Offset(w, mid), to = Offset(w, mid)))
        assertEquals("手指回到页边，折痕贴边", w, atEdge.foldX, 0.01f)

        // 没有手指的路径（结算 / 点按 / 上一页）照旧按进度收两端。
        val settled = curlShape(w, h, 0f, 1)
        assertFalse("没有手指就不是 anchored", settled.anchored)
        assertTrue("进度 0 收成整页平铺", settled.noLiftYet())
        assertTrue("进度 1 收成目标页", curlShape(w, h, 1f, 1).fullyTurned())
        assertFalse("中段本来就不该收", curlShape(w, h, 0.5f, 1).noLiftYet())
    }

    /**
     * 倾斜只许跟着**横向行程**长大（见 `travelLeanCap`）：纵向抖得再狠，横向没走两步，
     * 折痕也得是直的；横向行程够长时斜率照旧说了算（斜拖的垂直承诺不能打折）。
     */
    @Test
    fun leanIsCappedByTheHorizontalTravelNearTheStart() {
        val w = 1840f
        val h = 2800f
        // 几乎竖直的拖动：斜率早被夹到上限（2w/h），只靠行程上限才压得住倾斜。
        // 上限按 3·|dx|/tiltW 算（tiltW = 0.9 页宽）：2 / 10 / 30 / 120 px ⇒ 0.4% / 1.8% / 5.4% / 21.7%。
        var last = 0f
        for ((dx, ceil) in listOf(2f to 0.006f, 10f to 0.025f, 30f to 0.07f, 120f to 0.25f)) {
            val pull = CurlPull(from = Offset(w, 1200f), to = Offset(w - dx, 1240f))
            val lean = kotlin.math.abs(leanOf(pull, w, h))
            assertTrue("横向行程 $dx：倾斜 $lean 该被行程上限压到 $ceil 以内", lean <= ceil)
            assertTrue("横向行程越长，倾斜应当越大：$last → $lean", lean > last)
            last = lean
        }
        // 真正的斜拖（横向 400px）远没到上限：折痕仍严格垂直于手指轨迹。
        val diagonal = CurlPull(from = Offset(w, 1400f), to = Offset(w - 400f, 1100f))
        val want = 0.75f * h / (2f * w * CURL_TILT_WIDTH)
        assertEquals("斜拖的倾斜不该被行程上限削弱", -want, leanOf(diagonal, w, h), 1e-3f)
    }

    /**
     * 纸自由边（材料坐标 x = 页宽）上、材料纵坐标 = [texY] 那一点落在屏幕的哪。
     *
     * 网格顶点只落在页角与扫线交点上，抓点一般在两点之间，按材料坐标线性插值取。
     */
    private fun freeEdgePointAt(shape: CurlShape, texY: Float): Offset? {
        val mesh = buildCurlMesh(shape, radius = curlRadius(shape))
        val pts = ArrayList<Pair<Float, Offset>>()
        for (i in 0 until mesh.vertexCount) {
            if (kotlin.math.abs(mesh.texCoords[i * 2] - shape.pageW) < 0.05f) {
                pts.add(
                    mesh.texCoords[i * 2 + 1] to
                        Offset(mesh.positions[i * 2], mesh.positions[i * 2 + 1]),
                )
            }
        }
        if (pts.isEmpty()) return null
        pts.sortBy { it.first }
        for (i in 0 until pts.size - 1) {
            val (y0, p0) = pts[i]
            val (y1, p1) = pts[i + 1]
            if (texY in y0..y1 && y1 > y0) {
                val t = (texY - y0) / (y1 - y0)
                return Offset(p0.x + (p1.x - p0.x) * t, p0.y + (p1.y - p0.y) * t)
            }
        }
        return null
    }

    /** 一次从右缘（`y` 高度）拖到 `(fx, y)` 的拖动；竖直折痕的跟手锚定测试用它。 */
    private fun dragTo(fx: Float, y: Float, w: Float = 400f): CurlPull =
        CurlPull(from = Offset(w, y), to = Offset(fx, y))
}
