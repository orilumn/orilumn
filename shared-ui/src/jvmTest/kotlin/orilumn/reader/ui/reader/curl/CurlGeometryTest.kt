package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
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
     */
    @Test
    fun tiltFadesAtBothEnds() {
        val w = 400f
        val h = 600f
        assertEquals("进度 95% 倾斜量淡到一半", 0.25f, curlShape(w, h, 0.95f, 1, 0.5f).lean, 1e-4f)
        assertEquals("进度 5% 倾斜量也淡到一半", 0.25f, curlShape(w, h, 0.05f, 1, 0.5f).lean, 1e-4f)
        assertEquals("中段（进度 50%）不受影响", 0.5f, curlShape(w, h, 0.5f, 1, 0.5f).lean, 1e-4f)
        assertEquals("收尾（进度 100%）折痕扶正", 0f, curlShape(w, h, 1f, 1, 0.5f).lean, 1e-4f)
        assertEquals("起手（进度 0%）折痕扶正", 0f, curlShape(w, h, 0f, -1, 0.5f).lean, 1e-4f)
        // 两端折角面积必须连续收到零：进度 0 / 1 的折痕竖直贴边，掀起的纸宽度为 0。
        val head = curlShape(w, h, 0f, 1, 0.9f)
        assertEquals("进度 0 折痕竖直", head.topX, head.bottomX, 1e-4f)
        val tail = curlShape(w, h, 1f, 1, 0.9f)
        assertEquals("进度 1 折痕竖直", tail.topX, tail.bottomX, 1e-4f)
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

    /** 上一页不跟手（旧版同款）：锚点对 `curlFrame(-1)` 的折痕毫无影响。 */
    @Test
    fun prevPageIgnoresAnchor() {
        val w = 400f
        val h = 600f
        val p = 0.4f
        val plain = curlFrame(w, h, p, -1).shape.foldX
        val anchored = curlFrame(w, h, p, -1, pull = dragTo(w - 80f, h / 2f)).shape.foldX
        assertEquals(plain, anchored, 0.01f)
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
        for ((downY, fx, fy) in listOf(
            Triple(300f, 150f, 300f),   // 水平
            Triple(450f, 150f, 220f),   // 左上斜拖
            Triple(150f, 150f, 380f),   // 左下斜拖
            Triple(420f, 250f, 260f),   // 轻微斜拖
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
