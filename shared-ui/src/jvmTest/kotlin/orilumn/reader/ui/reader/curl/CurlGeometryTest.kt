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
     * 进度两端各 10% 之外的倾斜原样保留：书脊不再收倾斜（端点可以卷过书脊一个筒径），
     * 所以 85% 处仍是手指给的 0.5。
     */
    @Test
    fun tiltFadesAtBothEnds() {
        val w = 400f
        val h = 600f
        assertEquals("进度 5% 倾斜量淡到一半", 0.25f, curlShape(w, h, 0.05f, 1, 0.5f).lean, 1e-4f)
        assertEquals("中段（进度 50%）不受影响", 0.5f, curlShape(w, h, 0.5f, 1, 0.5f).lean, 1e-4f)
        assertEquals("进度 85% 倾斜原样保留（书脊不再收倾斜）", 0.5f, curlShape(w, h, 0.85f, 1, 0.5f).lean, 1e-4f)
        assertEquals("收尾（进度 100%）折痕扶正", 0f, curlShape(w, h, 1f, 1, 0.5f).lean, 1e-4f)
        assertEquals("起手（进度 0%）折痕扶正", 0f, curlShape(w, h, 0f, -1, 0.5f).lean, 1e-4f)
        // 两端折角面积必须连续收到零：进度 0 / 1 的折痕竖直贴边，掀起的纸宽度为 0。
        val head = curlShape(w, h, 0f, 1, 0.9f)
        assertEquals("进度 0 折痕竖直", head.topX, head.bottomX, 1e-4f)
        val tail = curlShape(w, h, 1f, 1, 0.9f)
        assertEquals("进度 1 折痕竖直", tail.topX, tail.bottomX, 1e-4f)
    }

    /**
     * 折痕端点可以卷过书脊（读者 2026-11-21：「上下书脊应该是相同的！你见过哪本书是
     * 翻不到书脊的？」）：两个方向、两端一视同仁，唯一边界是纸卷退场的一个筒径。
     *
     * 这里扫「多个进度 × 多个倾斜」：任何组合都不许越过一个筒径，也不许把端点甩到页宽之外。
     */
    @Test
    fun creaseEndsMayCrossTheSpineByOneRadius() {
        val w = 400f
        val h = 600f
        val exit = w * CURL3D_RADIUS_RATIO
        for (p in listOf(0.05f, 0.2f, 0.5f, 0.8f, 0.9f, 0.95f)) {
            for (tilt in listOf(-1f, -0.9f, -0.5f, 0f, 0.5f, 0.9f, 1f)) {
                val s = curlShape(w, h, p, 1, tilt)
                assertTrue(
                    "p=$p tilt=$tilt 折痕上端越过书脊太多：${s.topX}（折痕 ${s.foldX}，倾斜 ${s.lean}）",
                    s.topX >= -exit - 1e-3f,
                )
                assertTrue(
                    "p=$p tilt=$tilt 折痕下端越过书脊太多：${s.bottomX}",
                    s.bottomX >= -exit - 1e-3f,
                )
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

    /**
     * 左半屏起手（折痕锚点）**手指往上滑**：折痕仍穿过手指，不反向跑。
     *
     * 读者原话：「左半屏起手（折痕锚点）时手指向上，折痕反向运行并脱离手指。」倾斜量按竖向
     * 行程算，往上滑时是大负数，折痕下端被甩到页左边好几页宽以外；上一版在 [curlShape] 里
     * **单独夹端点**，下端被夹住、上端照旧甩出去 —— 折痕这条线被掰弯，手指还在原处，折痕
     * 在手指那一行已经跑掉 1500px 以上（实测：按下 (460,1400)，手指上移 200/400/600px 时
     * 折痕落在 x = 2211/2131/1965）。现在收的是**倾斜**（`leanWithinPageEnds`），折痕位置
     * 仍由手指反解，所以「折痕穿过手指」必须原样成立，端点也不许越界。
     */
    @Test
    fun creaseAnchorStaysUnderTheFingerWhenDraggedUpward() {
        val w = 1840f
        val h = 2800f
        val exit = w * CURL3D_RADIUS_RATIO
        val press = Offset(460f, 1400f)
        for (dy in listOf(-200f, -400f, -600f, 200f, 400f, 600f)) {
            val to = Offset(press.x, press.y + dy)
            val pull = CurlPull(from = press, to = to, anchor = CurlAnchor.Crease)
            val tilt = leanOf(pull, w, h)
            val s = curlShape(w, h, 0.3f, 1, tilt = tilt, pull = pull)
            val atFinger = s.topX + (s.bottomX - s.topX) * (to.y / h)
            assertEquals("dy=$dy 折痕应当仍穿过手指", to.x, atFinger, 0.01f)
            assertTrue("dy=$dy 折痕上端越界：topX=${s.topX}", s.topX >= -exit - 0.01f)
            assertTrue("dy=$dy 折痕下端越界：bottomX=${s.bottomX}", s.bottomX >= -exit - 0.01f)
            // 收的是倾斜、不是端点：倾斜确实被收过（否则这一帧不成立），但没被收成竖直。
            assertTrue("dy=$dy 倾斜不该被收平：$tilt", kotlin.math.abs(tilt) > 0.05f)
            assertEquals("dy=$dy 形状里的倾斜必须就是收过的那个", tilt, s.lean, 1e-4f)
        }
    }

    /**
     * 读者口径（2026-11-21）：跟手帧里**左下角一律可以卷到纸卷右侧**，左右半边起手一样。
     *
     * 手指从下半屏往左上拖，纸角向上转（`lean < 0`），折痕下端因此越过左下角——它落在纸卷
     * （筒）的右侧，屏幕上就是左下角被卷进去了。上限仍是卷筒退场的一个半径。
     */
    @Test
    fun followMayCurlTheBottomLeftCornerFromEitherHalf() {
        val w = 1840f
        val h = 2800f
        val exit = w * CURL3D_RADIUS_RATIO
        // 大行程：折痕被手指推到书脊附近，下端才有机会越过左下角（两半屏都要能过去）。
        val fromLeft = CurlPull(from = Offset(600f, 2400f), to = Offset(200f, 600f), anchor = CurlAnchor.Crease)
        val fromRight = CurlPull(from = Offset(w, 2400f), to = Offset(400f, 600f), anchor = CurlAnchor.Edge)
        for ((where, pull) in listOf("左半屏" to fromLeft, "右半屏" to fromRight)) {
            val s = curlFrame(w, h, 0.35f, 1, leanOf(pull, w, h), pull).shape
            assertTrue(
                "$where：折痕上端越界（最多让出一个筒径）：topX=${s.topX}",
                s.topX >= -exit - 0.01f,
            )
            assertTrue(
                "$where：左下角应当卷到纸卷右侧（折痕下端越过左下角）：bottomX=${s.bottomX}",
                s.bottomX < -1f,
            )
            assertTrue(
                "$where：越过量不超过卷筒退场的一个半径：bottomX=${s.bottomX}",
                s.bottomX >= -exit - 0.01f,
            )
        }
    }

    /**
     * 松手交接：跟手帧已经越过的左下角，结算第一帧不许被弹回去。
     *
     * 结算没有手指，几何由进度反解；若那边仍按「两端都夹」，越过的那一个筒径会在松手那一帧
     * 被拽回页角——屏幕上就是「左下角刚卷过去又弹回来」。
     */
    @Test
    fun releaseKeepsTheSweptBottomCorner() {
        val w = 1840f
        val h = 2800f
        val pull = CurlPull(from = Offset(w, 2400f), to = Offset(400f, 600f), anchor = CurlAnchor.Edge)
        val tilt = leanOf(pull, w, h)
        val follow = curlFrame(w, h, 0.35f, 1, tilt, pull).shape
        assertTrue("这一帧本来就没越过左下角，判据空了：bottomX=${follow.bottomX}", follow.bottomX < -1f)
        val settle = curlFrame(w, h, anchoredProgress(pull, w, h, tilt, 0.35f), 1, tilt, null).shape
        assertEquals("松手后下端弹回去了", follow.bottomX, settle.bottomX, 2f)
        assertEquals("松手后上端对不上了", follow.topX, settle.topX, 2f)
    }

    /**
     * 读者 2026-11-21 报的两条，必须在同一条判据上同时成立：
     *
     * 1. 松手后**不许先把折角扶平、再让折痕横扫**（读者原话「先是回平再播放，非常不协调」）：
     *    倾斜在起手那一小段里就归零时，被掀起的那块面积会先缩一下再长回来——屏幕上就是折角
     *    先原地落平，然后折痕才开始走。
     * 2. 倾斜也不许留到末段才一口气掰平（更早那一条「页角先向上冲一下再退回」）。
     *
     * 判据取**被掀起的面积**（页矩形在被折的那一侧的面积占比）：翻页途中它只许增不许减，
     * 相邻两帧之间不许出现可见的回缩。倾斜口径换成 [settleLeanFade] 的「整段行程平滑退回」。
     */
    @Test
    fun releasedFlapKeepsGrowingInsteadOfFlatteningFirst() {
        val w = 1840f
        val h = 2800f
        // 读者那一手：横向 200px / 竖向 120px 的松手交接，可见进度约 -0.072、倾斜约 -0.507。
        val lean = -0.507f
        val from = -0.072f

        /** 折痕把页面切成两半，被掀起那一半（+法向侧）占整页的面积比例。 */
        fun foldedArea(s: CurlShape): Float {
            val ox = (s.topX + s.bottomX) * 0.5f
            val oy = s.pageH * 0.5f
            val dx = s.bottomX - s.topX
            val dy = s.pageH
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
            val nx = dy / len
            val ny = -dx / len
            // 页矩形按半平面裁剪（Sutherland–Hodgman），再取多边形面积。
            val rect = listOf(
                Offset(0f, 0f), Offset(s.pageW, 0f), Offset(s.pageW, s.pageH), Offset(0f, s.pageH),
            )
            fun side(p: Offset): Float = (p.x - ox) * nx + (p.y - oy) * ny
            val out = ArrayList<Offset>(8)
            for (i in rect.indices) {
                val a = rect[i]
                val b = rect[(i + 1) % rect.size]
                val sa = side(a)
                val sb = side(b)
                if (sa >= 0f) out.add(a)
                if ((sa > 0f && sb < 0f) || (sa < 0f && sb > 0f)) {
                    val t = sa / (sa - sb)
                    out.add(Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
                }
            }
            var twice = 0f
            for (i in out.indices) {
                val p = out[i]
                val q = out[(i + 1) % out.size]
                twice += p.x * q.y - q.x * p.y
            }
            return kotlin.math.abs(twice) * 0.5f / (s.pageW * s.pageH)
        }

        fun trace(leanFade: (Float) -> Float): List<Float> = (0..200).map { i ->
            val v = from + (1f - from) * i / 200f
            foldedArea(curlFrame(w, h, v, 1, lean, leanFade = leanFade(v)).shape)
        }

        // 旧口径（起手 15% 行程就扶平）确实会「先缩一下再长回来」——判据不能空。
        val oldSpan = (1f - from) * 0.15f
        val old = trace { v -> (1f - (v - from) / oldSpan).coerceIn(0f, 1f) }
        // 「回缩」按**峰值回落**算：折角先缩一下再长回来，单帧差很小但累计回撤看得见。
        var peak = old[0]
        var worstOld = 0f
        for (a in old) {
            peak = maxOf(peak, a)
            worstOld = maxOf(worstOld, peak - a)
        }
        assertTrue("旧口径应当复现『折角先缩一下』：峰值回撤 ${worstOld * 100}%", worstOld > 0.02f)

        val fixed = trace { v -> settleLeanFade(from, 1f, v) }
        assertEquals("松手第一帧必须与跟手帧同形", old.first(), fixed.first(), 1e-4f)
        var fixedPeak = fixed[0]
        for (i in fixed.indices) {
            assertTrue(
                "被掀起的面积回缩了（折角先扶平再走）：峰值 ${fixedPeak * 100}% → ${fixed[i] * 100}%（第 $i 帧）",
                fixed[i] >= fixedPeak - 1e-4f,
            )
            fixedPeak = maxOf(fixedPeak, fixed[i])
        }
        assertEquals("翻完应当整页都在被折的那一侧", 1f, fixed.last(), 1e-3f)
        // 末段倾斜已经很小：不许拖到最后才掰平（那就是「页角先上冲再退回」）。
        assertTrue("末段倾斜还不收：${settleLeanFade(from, 1f, from + (1f - from) * 0.9f)}", settleLeanFade(from, 1f, from + (1f - from) * 0.9f) < 0.05f)
    }

    /**
     * 倾斜扶正系数：松手那一帧 1，起手先**保持住**手指摆出的角度，再随行程平滑退回 0。
     *
     * 两端斜率都是 0（`1 − smoothstep`）：起手不出速度台阶，收尾也不忽然掰一下。与「按行程
     * 线性淡」相比，前半段退得更慢（先保持、接着松手前的方向走），后半段退得更快（收得干净）。
     */
    @Test
    fun settleLeanFadeHoldsTheReleasedTiltThenFlattensOverTheWholeTravel() {
        val from = -0.2f
        val to = 1f
        assertEquals("松手那一帧必须从跟手形状接手", 1f, settleLeanFade(from, to, from), 1e-6f)
        assertEquals("走完全程必须竖直", 0f, settleLeanFade(from, to, to), 1e-6f)
        assertEquals("行程一半退回一半", 0.5f, settleLeanFade(from, to, from + (to - from) * 0.5f), 1e-5f)
        // 起手 15% 行程里几乎原样保持（旧口径在这里已经归零——正是「先回平再播放」）。
        assertTrue(
            "起手就明显转过了：15% 行程处 ${settleLeanFade(from, to, from + (to - from) * 0.15f)}",
            settleLeanFade(from, to, from + (to - from) * 0.15f) > 0.9f,
        )
        var prev = 1f
        for (i in 0..100) {
            val s = i / 100f
            val f = settleLeanFade(from, to, from + (to - from) * s)
            assertTrue("扶正系数不许回头：$prev → $f", f <= prev + 1e-6f)
            if (s < 0.5f) {
                assertTrue("前半段应当比线性退回更慢（先保持住）：s=$s f=$f", f >= 1f - s - 1e-6f)
            } else if (s < 1f) {
                assertTrue("后半段应当比线性退回更快（收干净）：s=$s f=$f", f <= 1f - s + 1e-6f)
            }
            prev = f
        }
    }

    /** 回弹的倾斜系数：松手那一帧 1，之后跟着「还剩多少行程」线性退到 0。 */
    @Test
    fun settleLeanRewindKeepsTheTiltInStepWithTheRemainingTravel() {
        val from = -0.6257217f
        val to = 0f
        assertEquals("松手那一帧必须从跟手形状接手", 1f, settleLeanRewind(from, to, from), 1e-6f)
        assertEquals("落回起手页时折痕必须竖直", 0f, settleLeanRewind(from, to, to), 1e-6f)
        assertEquals("走了一半行程就该退掉一半", 0.5f, settleLeanRewind(from, to, from / 2f), 1e-6f)
        var prev = 1f
        for (i in 0..100) {
            val f = settleLeanRewind(from, to, from + (to - from) * i / 100f)
            assertTrue("扶正系数不许回头：$prev → $f", f <= prev + 1e-6f)
            prev = f
        }
    }

    /**
     * 读者 2026-11-21 真机复现的那一手：左滑起手、手指向上带一点、松手**弹回**（不是翻页）。
     *
     * 真机落盘：`from = −0.626`、`lean = −2.5`（竖向行程顶到上限）、`commit = false`。
     * 折痕中点被推到自由边之外，屏幕上那块折角全靠倾斜撑着。旧口径「折痕一边往回扫、倾斜
     * 一边把纸角放大着抬上去」实测露出页面积先涨 5 帧再跌 9 帧——读者原话「折角/纸边在颤」。
     *
     * 生产口径（[settleFoldProgress] + [settleLeanRewind]）：折痕中点钉在松手位置不动，倾斜
     * 匀速塌回平面。判据：中点全程不变，折痕两端各自只朝中点走（整根线单调转回竖直，折角
     * 那块楔形单调收窄），末帧回到渲染层的「一页都没掀」闸口。
     */
    @Test
    fun rollbackCornerDoesNotRushUpBeforeItLands() {
        val w = 1840f
        val h = 2800f
        val lean = -2.5f
        val from = -0.6257217f
        val target = 0f
        // 生产口径：屏幕折痕走 settleFoldProgress，倾斜走 settleLeanRewind（与 FlipController.settle 一致）。
        val frames = (0..200).map { i ->
            val v = from + (target - from) * i / 200f
            val fold = settleFoldProgress(from, target, v)
            val leanFade = settleLeanRewind(from, target, v)
            curlFrame(w, h, fold, 1, tilt = lean, leanFade = leanFade).shape
        }

        // 松手第一帧必须与跟手帧同形（钉住进度 + 倾斜系数 1 = 原样接手）。
        val follow = curlFrame(w, h, from, 1, tilt = lean, fade = 1f, leanFade = 1f).shape
        assertEquals("松手第一帧折痕不许动", follow.foldX, frames.first().foldX, 1e-3f)
        assertEquals("松手第一帧上端不许动", follow.topX, frames.first().topX, 1e-3f)
        assertEquals("松手第一帧下端不许动", follow.bottomX, frames.first().bottomX, 1e-3f)
        // 折痕中点钉住 + 两端单调转回竖直 ⇒ 折角楔形只收不放（旧口径「扶正 + 回扫」在这里来回摆）。
        for (i in 1 until frames.size) {
            val prev = frames[i - 1]
            val cur = frames[i]
            assertEquals("第 $i 帧折痕中点动了（钉住口径失效）", frames.first().foldX, cur.foldX, 1e-3f)
            assertTrue(
                "折痕上端回头了（折角又外翻）：${prev.topX} → ${cur.topX}（第 $i 帧）",
                cur.topX <= prev.topX + 1e-2f,
            )
            assertTrue(
                "折痕下端回头了（折角又外翻）：${prev.bottomX} → ${cur.bottomX}（第 $i 帧）",
                cur.bottomX >= prev.bottomX - 1e-2f,
            )
        }
        // 末帧倾斜归零、折痕在自由边之外 ⇒ 渲染层判「一页都没掀」（收尾把进度拨回 0 才看不见）。
        val last = frames.last()
        assertEquals("落回起手页时倾斜必须归零", 0f, last.lean, 1e-4f)
        assertTrue("折痕应当留在自由边之外：foldX=${last.foldX}", last.foldX > w)
        assertTrue("末帧应当已是平页（noLiftYet）", last.noLiftYet())
    }

    /**
     * 钉住折痕的口径：只有「回弹 + 折痕已在自由边之外」钉住，其余照旧跟着动画走。
     *
     * 翻页、以及「翻了一小半又反悔」（`from >= 0`）时折角是一块真的页角，必须跟着折痕扫回来；
     * 钉住只服务于「折痕中点被推到页外、折角全靠倾斜撑着」那一段。
     */
    @Test
    fun settleFoldProgressOnlyPinsTheFoldWhenItIsAlreadyOutsideTheFreeEdge() {
        // 读者那一手：回弹、折痕在页外 ⇒ 整段钉在松手位置。
        for (i in 0..100) {
            val v = -0.6257217f * (1f - i / 100f)
            assertEquals("回弹时折痕不许动（第 $i 帧）", -0.6257217f, settleFoldProgress(-0.6257217f, 0f, v), 1e-6f)
        }
        // 松手第一帧：无论哪条路径都必须等于起点（形状从跟手帧原样接手）。
        assertEquals(-0.2f, settleFoldProgress(-0.2f, 1f, -0.2f), 1e-6f)
        assertEquals(0.3f, settleFoldProgress(0.3f, 0f, 0.3f), 1e-6f)
        // 折痕还在页内的回弹：跟着动画扫回。
        for (i in 0..100) {
            val v = 0.3f * (1f - i / 100f)
            assertEquals("页内折痕必须跟着回扫（第 $i 帧）", v, settleFoldProgress(0.3f, 0f, v), 1e-6f)
        }
        // 翻页（target > from）：照旧跟着动画走。
        for (i in 0..100) {
            val v = -0.2f + 1.2f * i / 100f
            assertEquals("翻页不该钉住（第 $i 帧）", v, settleFoldProgress(-0.2f, 1f, v), 1e-6f)
        }
    }

    /**
     * 自由边那两个角（右上 / 右下）没有书脊规则：折痕端点可以滑出右缘，把角卷过纸卷。
     *
     * 右缘这一侧是纸的自由边，唯一边界是 [CURL_ENDPOINT_BOUND] 的 NaN 兜底；装订边那一侧
     * 最多越过书脊一个筒径（纸卷退场余量）。
     */
    @Test
    fun freeEdgeCornersMaySlidePastTheRightEdge() {
        val w = 1840f
        val h = 2800f
        val exit = w * CURL3D_RADIUS_RATIO
        // 折痕仍在页中、倾斜不大：自由边上端滑出右缘，书脊那一端还留在页内。
        val upFromRight = CurlPull(from = Offset(w, 1200f), to = Offset(w - 410f, 960f), anchor = CurlAnchor.Edge)
        val s = curlFrame(w, h, 0.35f, 1, leanOf(upFromRight, w, h), upFromRight).shape
        assertTrue("右上角应当能被卷过去（折痕上端滑出右缘）：topX=${s.topX}", s.topX > w)
        assertTrue("自由边也不许无限滑出去：topX=${s.topX}", s.topX <= w * CURL_ENDPOINT_BOUND)
        assertTrue("书脊那一端最多让出一个筒径：bottomX=${s.bottomX}", s.bottomX >= -exit - 1e-3f)
    }

    /**
     * 向下拖：**左上角同样可以卷过纸卷**（读者 2026-11-21「上下书脊应该是相同的」），
     * 与左下角同一条边界——最多让出一个筒径。
     */
    @Test
    fun followMayCurlTheTopLeftCorner() {
        val w = 400f
        val h = 600f
        val exit = w * CURL3D_RADIUS_RATIO
        val pull = CurlPull(from = Offset(300f, 150f), to = Offset(100f, 500f), anchor = CurlAnchor.Crease)
        val s = curlFrame(w, h, 0.35f, 1, leanOf(pull, w, h), pull).shape
        assertTrue("左上角应当能卷过纸卷：topX=${s.topX}", s.topX < -1f)
        assertTrue("越过量不超过退场的一个筒径：topX=${s.topX}", s.topX >= -exit - 0.01f)
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

    /** 越界与极端倾斜：不许出 NaN、折痕中点只受 [CURL_FOLD_BOUND] 兜底、端点最多越过书脊一个筒径。 */
    @Test
    fun anchorIsClampedAndFinite() {
        val w = 400f
        val h = 600f
        val foldBound = w * CURL_FOLD_BOUND
        val exit = w * CURL3D_RADIUS_RATIO
        for (tilt in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (fx in listOf(-300f, -10f, 0f, 120f, 399f, 400f, 460f)) {
                val s = curlShape(w, h, 0.3f, 1, tilt = tilt, pull = dragTo(fx, 300f))
                assertTrue("tilt=$tilt fx=$fx foldX 非法", s.foldX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 端点非法", s.topX.isFinite() && s.bottomX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 折痕越界 ${s.foldX}", s.foldX in -foldBound - 0.01f..foldBound + 0.01f)
                assertTrue("tilt=$tilt fx=$fx 折痕上端越过书脊太多 ${s.topX}", s.topX >= -exit - 0.01f)
                assertTrue("tilt=$tilt fx=$fx 折痕下端越过书脊太多 ${s.bottomX}", s.bottomX >= -exit - 0.01f)
            }
        }
    }

    /** 折痕锚点同样不许出 NaN / 端点只受 NaN 兜底（书脊不夹）。 */
    @Test
    fun creaseAnchorIsClampedAndFinite() {
        val w = 400f
        val h = 600f
        val foldBound = w * CURL_FOLD_BOUND
        val endpointBound = w * CURL_ENDPOINT_BOUND
        for (tilt in listOf(-1f, -0.5f, 0f, 0.5f, 1f)) {
            for (fx in listOf(0f, 10f, 120f, 300f, 399f, 400f)) {
                val pull = CurlPull(from = Offset(0f, 20f), to = Offset(fx, 590f), anchor = CurlAnchor.Crease)
                val s = curlShape(w, h, 0.3f, 1, tilt = tilt, pull = pull)
                assertTrue("tilt=$tilt fx=$fx foldX 非法", s.foldX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 端点非法", s.topX.isFinite() && s.bottomX.isFinite())
                assertTrue("tilt=$tilt fx=$fx 折痕越界 ${s.foldX}", s.foldX in -foldBound - 0.01f..foldBound + 0.01f)
                assertTrue("tilt=$tilt fx=$fx 端点越界 ${s.topX}", s.topX in -endpointBound..endpointBound)
                assertTrue("tilt=$tilt fx=$fx 端点越界 ${s.bottomX}", s.bottomX in -endpointBound..endpointBound)
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
        // 纸边就必然离开手指（物理上限，见 `steepDragMayTakeTheCreasePastTheSpine`）。
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
     * 再斜的拖法也一样：折痕可以卷过书脊（读者 2026-11-21「上下书脊应该是相同的」），
     * 倾斜按手指给的走，不再为了「不撕开书脊」收小；唯一边界是退场的一个筒径。
     */
    @Test
    fun steepDragMayTakeTheCreasePastTheSpine() {
        val w = 400f
        val h = 600f
        val exit = w * CURL3D_RADIUS_RATIO
        val pull = CurlPull(from = Offset(w, 150f), to = Offset(150f, 380f))
        val wanted = leanOf(pull, w, h)
        val shape = curlShape(w, h, 1f - 150f / w, 1, tilt = wanted, pull = pull)
        assertEquals("倾斜应当原样跟手（不再为书脊收小）", wanted, shape.lean, 1e-6f)
        assertTrue("折痕上端越界：${shape.topX}", shape.topX >= -exit - 1e-3f)
        assertTrue("折痕下端越界：${shape.bottomX}", shape.bottomX >= -exit - 1e-3f)
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
     * 3. 起手点附近掀起的纸只许跟着**拖动行程**收小（不允许斜切出一大块三角）。
     *
     * 注意「回到起手点」是**两个方向都归零**：竖直还差着几十像素时手指并不在起手点上，
     * 折痕本来就该让开那几十像素（倾斜上限按竖向行程算，见 [verticalLeanCap]）。
     */
    @Test
    fun returningToTheStartCollapsesTheFlapWithoutAJump() {
        val w = 1840f
        val h = 2800f
        val grabY = 1200f
        // 指尖正好落回起手点：折痕竖直贴边，一页空纸都没掀起来。
        val back = CurlPull(from = Offset(w, grabY), to = Offset(w, grabY))
        val backShape = curlShape(w, h, 0f, 1, tilt = leanOf(back, w, h), pull = back)
        assertEquals("指尖回到起手点：折痕贴边", w, backShape.foldX, 0.01f)
        assertEquals("指尖回到起手点：端点也贴边", w, backShape.topX, 0.01f)
        assertEquals("指尖回到起手点：端点也贴边", w, backShape.bottomX, 0.01f)
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
                if (dx == 0f && dy == 0f) {
                    assertEquals("指尖回到起手点：折痕贴边 dy=$dy", w, s.foldX, 0.01f)
                    assertEquals("指尖回到起手点：端点也贴边 dy=$dy", w, s.topX, 0.01f)
                    assertEquals("指尖回到起手点：端点也贴边 dy=$dy", w, s.bottomX, 0.01f)
                }
                if (kotlin.math.abs(dx) <= 60f) {
                    // 掀起来的纸离自由边最远多远：折痕两端与折痕中点都算。
                    val reach = w - minOf(s.topX, s.bottomX, s.foldX)
                    assertTrue(
                        "dx=$dx dy=$dy 起手点附近掀起的纸只许跟拖动行程成正比，实际 $reach",
                        reach <= 7f * kotlin.math.hypot(dx, dy) + 60f,
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
     * 倾斜只许跟着**竖向行程**长大（见 [verticalLeanCap]）：手指几乎没离开起手点时折痕得是
     * 直的，竖向行程越大倾斜才越大；斜率照旧说了算（斜拖的垂直承诺不能打折）。
     *
     * 上限按竖向行程算、不按横向分量：起手点在页角上时，斜着拖出去那一段的横向分量本来就
     * 很小，按横向算会把折痕压成竖直——折痕与自由边的交点于是扫过整个页高，折角看着就是
     * 「忽然向上跳一下」（读者 2026-11-21）。
     */
    @Test
    fun leanIsCappedByTheDragDistanceNearTheStart() {
        val w = 1840f
        val h = 2800f
        // 几乎竖直的拖动（横向只 2px）：斜率早被夹到上限，只靠竖向行程上限才压得住倾斜。
        // 上限 = 2.5·(竖向行程/(0.115·tiltW))²（tiltW = 0.9 页宽）：4 / 40 / 400 px
        // ⇒ 0.11% / 11% / 硬顶 2.5。
        var last = 0f
        for ((dy, ceil) in listOf(4f to 0.01f, 40f to 0.12f, 400f to CURL_LEAN_MAX)) {
            val pull = CurlPull(from = Offset(w, 1200f), to = Offset(w - 2f, 1200f - dy))
            val lean = kotlin.math.abs(leanOf(pull, w, h))
            assertTrue("行程 $dy：倾斜 $lean 该被竖向行程上限压到 $ceil 以内", lean <= ceil)
            assertTrue("行程越长，倾斜应当越大：$last → $lean", lean > last)
            last = lean
        }
        // 手指没离开起手点：方向退化，折痕竖直。
        val still = CurlPull(from = Offset(w, 1200f), to = Offset(w, 1200f))
        assertEquals("手指没离开起手点，折痕必须竖直", 0f, leanOf(still, w, h), 0f)
        // 从页角斜拖出去：横向只有几十像素，倾斜也必须留着（不许被横向分量压成竖直）。
        val corner = CurlPull(from = Offset(w, h - 10f), to = Offset(w - 80f, h - 430f))
        assertTrue(
            "从页角斜拖出去：倾斜 ${leanOf(corner, w, h)} 不许被压成竖直",
            kotlin.math.abs(leanOf(corner, w, h)) > 0.5f,
        )
        // 真正的斜拖（横向 400px）远没到上限：折痕仍严格垂直于手指轨迹。
        val diagonal = CurlPull(from = Offset(w, 1400f), to = Offset(w - 400f, 1100f))
        val want = 0.75f * h / (2f * w * CURL_TILT_WIDTH)
        assertEquals("斜拖的倾斜不该被竖向行程上限削弱", -want, leanOf(diagonal, w, h), 1e-3f)
    }

    /**
     * 读者 2026-11-21 报的那一条：从右下角起手、斜着拖出一小块折角之后，手指左右移动，
     * 页角**忽然向上跳一下**。
     *
     * 判据取屏幕上看得见的那条边界：折痕与自由边（`x = 页宽`）的交点 `apex`。竖直行程
     * 已有两三百像素时，横向再扫过几十像素，这条交点只许跟着手指微动、停在页角附近
     * （页底那一段），不许扫过整个页高（早先它从半页高处一路爬到页顶，折角随即整块消失）。
     */
    @Test
    fun cornerFlapDoesNotClimbWhenTheFingerSlidesSideways() {
        val w = 1840f
        val h = 2800f
        val tiltW = w * CURL_TILT_WIDTH
        fun apex(s: CurlShape): Float {
            val d = s.lean * tiltW
            assertTrue("跟手时折痕不许塌成竖直（lean=${s.lean}）", kotlin.math.abs(d) > 1f)
            return h / 2f * (1f + (w - s.foldX) / d)
        }
        val from = Offset(w, h - 10f)
        for (dy in listOf(-190f, -420f)) {
            var prev: CurlShape? = null
            var prevApex = 0f
            var x = w - 200f
            while (x <= w + 10f) {
                val pull = CurlPull(from = from, to = Offset(x, h - 10f + dy))
                val s = curlFrame(w, h, 0.35f, 1, leanOf(pull, w, h), pull).shape
                val a = apex(s)
                assertTrue(
                    "dy=$dy x=$x：折角上沿跑到 y=$a（只许停在页角附近）",
                    a >= h * 0.70f && a <= h * 0.95f,
                )
                val p = prev
                if (p != null) {
                    assertTrue(
                        "dy=$dy x=$x：折角上沿跳了 $prevApex → $a",
                        kotlin.math.abs(a - prevApex) <= 60f,
                    )
                    // 端点只在**页内可见**时才谈「跳没跳」：页角折起来时折痕上端远在页外
                    // （页内只看到它裁进页面的那一段），那条可见边界由 apex 与下端管着。
                    if (p.topX in 0f..w && s.topX in 0f..w) {
                        assertTrue(
                            "dy=$dy x=$x：折痕上端跳了 ${p.topX} → ${s.topX}",
                            kotlin.math.abs(s.topX - p.topX) <= 60f,
                        )
                    }
                    if (p.bottomX in 0f..w && s.bottomX in 0f..w) {
                        assertTrue(
                            "dy=$dy x=$x：折痕下端跳了 ${p.bottomX} → ${s.bottomX}",
                            // 折痕越来越斜时下端会沿着页底快速滑出去（页角在「滚」），
                            // 只要连续、没有一帧整块翻面即可；可见的跳动由 apex 管着。
                            kotlin.math.abs(s.bottomX - p.bottomX) <= 100f,
                        )
                    }
                }
                prev = s
                prevApex = a
                x += 5f
            }
        }
    }

    /**
     * 四种起手（左右半边 × 两个翻页方向）里，折痕都必须**垂直于手指轨迹**：
     * 折痕斜率 `(bottomX − topX) / h` 与拖动方向 `(u_x, u_y)` 互为负倒数。
     *
     * 每个起手都走该方向自然的那一手：下一页向左拖、上一页向右拖。竖向只取 80px——
     * 拖得更陡时倾斜会被 [verticalLeanCap] 或书脊侧的端点上限收小（那是另外两道约束），
     * 这里只锁「方向对不对」。
     *
     * 页边锚点的 `from` 是页右缘（`u_x < 0` 恒成立）；折痕锚点的 `from` 是按下点，横向符号
     * 由起手横滑方向（[CurlPull.direction]）锁住。早先两种锚点都用 `u_y/|u_x|`，左半边起手
     * 翻上一页（起手 `u_x > 0`）时折痕被镜像成与手指平行——读者 2026-11-26 报的
     * 「屏幕左侧起手，折痕方向没有与手指运行方向垂直，而是相反的」。
     */
    @Test
    fun theCreaseStaysPerpendicularToTheDragFromEveryStart() {
        val w = 1840f
        val h = 2800f
        val mid = h / 2f
        for (crease in listOf(false, true)) {
            for (direction in listOf(1, -1)) {
                val press = if (crease) 800f else w * 0.8f
                val pull = CurlPull(
                    // 生产口径：页边锚点的 from 是页右缘，折痕锚点的 from 是按下点。
                    from = if (crease) Offset(press, mid) else Offset(w, mid),
                    to = Offset(press + if (direction > 0) -200f else 200f, mid - 80f),
                    anchor = if (crease) CurlAnchor.Crease else CurlAnchor.Edge,
                    direction = direction,
                )
                val where = (if (crease) "左半边（折痕锚点）" else "右半边（页边锚点）") +
                    (if (direction > 0) "\u00b7下一页" else "\u00b7上一页")
                val u = pull.unit()!!
                val want = -u.y / u.x
                val s = curlFrame(w, h, 0.45f, direction, leanOf(pull, w, h), pull).shape
                val got = (s.bottomX - s.topX) / h
                assertEquals("$where\u00b7折痕必须垂直于手指轨迹（lean=${s.lean}）", want, got, 0.02f)
            }
        }
    }

    /**
     * 手指滑过按下点、回到另一侧之后再上下移动，折痕**不许镜像**。
     *
     * 读者原话：「屏幕左侧左滑，手指滑回到右侧，这时上下移动，手势还是反向的」。起手在左半边
     * ⇒ 折痕锚点，方向向量的起点就是按下点；手指从左边扫回右边时横向分量换号，早先
     * `u_y/u_x` 也跟着换号，整根折痕当场镜像。现在横向符号取**起手那一刻**的
     * [CurlPull.direction]，整个手势不变，所以按下点两侧一路扫过去，同一个竖向动作只有一个
     * 转向；按下点正上方横向分量虽然为 0，竖直行程还在，折痕照旧朝手指动的方向转，且与两侧
     * 连续（不许塌回竖直再弹出去）——倾斜上限按竖向行程算（见 [verticalLeanCap]）。
     */
    @Test
    fun crossingBackOverThePressPointDoesNotMirrorTheTilt() {
        val w = 1840f
        val h = 2800f
        val mid = h / 2f
        val press = 800f
        // 从左到右扫过按下点：负 = 还在左边，正 = 已经滑回右边（读者报的那一段）。
        // direction 锁的是**起手**那一侧：下一页起手在左（dx<0），上一页起手在右（dx>0）。
        for (direction in listOf(1, -1)) {
            for (dx in listOf(-400f, -200f, -40f, -10f, 10f, 40f, 200f, 400f)) {
                val up = CurlPull(
                    from = Offset(press, mid),
                    to = Offset(press + dx, mid - 240f),
                    anchor = CurlAnchor.Crease,
                    direction = direction,
                )
                val down = up.copy(to = Offset(press + dx, mid + 240f))
                val sUp = curlFrame(w, h, 0.35f, direction, leanOf(up, w, h), up).shape
                val sDown = curlFrame(w, h, 0.35f, direction, leanOf(down, w, h), down).shape
                // 手指向上：下一页（direction=+1）折痕上端在右，上一页（-1）在左。
                if (direction > 0) {
                    assertTrue(
                        "dir=$direction dx=$dx 手指向上：折痕上端应当落在下端右边（lean=${sUp.lean}）",
                        sUp.topX > sUp.bottomX,
                    )
                    assertTrue(
                        "dir=$direction dx=$dx 手指向下：折痕上端应当落在下端左边（lean=${sDown.lean}）",
                        sDown.topX < sDown.bottomX,
                    )
                } else {
                    assertTrue(
                        "dir=$direction dx=$dx 手指向上：折痕上端应当落在下端左边（lean=${sUp.lean}）",
                        sUp.topX < sUp.bottomX,
                    )
                    assertTrue(
                        "dir=$direction dx=$dx 手指向下：折痕上端应当落在下端右边（lean=${sDown.lean}）",
                        sDown.topX > sDown.bottomX,
                    )
                }
            }
        }
        for (direction in listOf(1, -1)) {
            val at = CurlPull(
                from = Offset(press, mid),
                to = Offset(press, mid - 240f),
                anchor = CurlAnchor.Crease,
                direction = direction,
            )
            // 正上方：横向分量为 0，竖直行程 240px 还在 ⇒ 折痕仍朝手指动的方向转，且与两侧连续。
            val lean = leanOf(at, w, h)
            assertTrue(
                "dir=$direction 按下点正上方：折痕仍朝手指方向转（lean=$lean）",
                if (direction > 0) lean < -0.1f else lean > 0.1f,
            )
            for (dx in listOf(-10f, 10f)) {
                val side = leanOf(at.copy(to = Offset(press + dx, mid - 240f)), w, h)
                assertEquals("dir=$direction 跨过按下点倾斜不许跳（dx=$dx）", side, lean, 1e-2f)
            }
            // 竖向不动（横向 240px）才是竖直折痕。
            val sideways = at.copy(to = Offset(press - 240f, mid))
            assertEquals("dir=$direction 横向拖动 ⇒ 竖直折痕", 0f, leanOf(sideways, w, h), 1e-3f)
        }
    }

    /**
     * 横向收回时折痕不许跟着转正（读者 2026-11-26：「在屏幕左侧开始翻下一页，然后手指往右
     * 移动时，折痕会自动转正，平行于屏幕竖边。这是个多余的动作」）。
     *
     * 折痕锚点的横向分母锁在**外向行程的最大值**上（[CurlPull.spanX]）：收回多少、越过
     * 按下点多远，倾斜都保持，折痕只平移；竖向移动仍照常改倾斜（2026-11-19 的口径）。
     * 早先用此刻的 `|u_x|`，手指往右一收 `|u_x|` 变大，倾斜一路收到 0。
     */
    @Test
    fun retractingSidewaysDoesNotRotateTheCrease() {
        val w = 1840f
        val h = 2800f
        val press = Offset(700f, 1600f)
        val span = 400f
        fun leanAt(x: Float, y: Float = 1500f) = leanOf(
            CurlPull(
                from = press,
                to = Offset(x, y),
                anchor = CurlAnchor.Crease,
                direction = 1,
                spanX = span,
            ),
            w,
            h,
        )
        val held = leanAt(press.x - span)
        assertTrue("这一手应当有倾斜（lean=$held）", kotlin.math.abs(held) > 0.1f)
        for (x in listOf(
            press.x - 300f,
            press.x - 100f,
            press.x,
            press.x + 200f,
            press.x + 600f,
            press.x + 1000f,
        )) {
            assertEquals("x=$x 收回时折痕角度不许变（held=$held）", held, leanAt(x), 1e-3f)
        }
        // 竖向仍照常改倾斜：同一个横向位置，手指再往上 200px，倾斜必须跟着变大。
        val deeper = leanAt(press.x + 600f, 1300f)
        assertTrue(
            "竖向移动仍要改倾斜（$held → $deeper）",
            kotlin.math.abs(deeper - held) > 0.05f,
        )
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
