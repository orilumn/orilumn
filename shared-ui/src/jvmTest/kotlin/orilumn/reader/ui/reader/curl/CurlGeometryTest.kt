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
     * 末段扶正只在**结算动画**里做（旧版 `CurlView` 的 tilt fade）：
     * 手指还按着的时候，折痕形状必须听手指的，不能自己扶正。
     */
    @Test
    fun tiltFadesOnlyWhileSettling() {
        val w = 400f
        val h = 600f
        assertEquals("跟手中（进度 95%）倾斜量原样保留", 0.5f, curlShape(w, h, 0.95f, 1, 0.5f, settling = false).lean, 1e-4f)
        assertEquals("结算中（进度 95%）倾斜量淡到一半", 0.25f, curlShape(w, h, 0.95f, 1, 0.5f, settling = true).lean, 1e-4f)
        assertEquals("结算中（进度 50%）不受影响", 0.5f, curlShape(w, h, 0.5f, 1, 0.5f, settling = true).lean, 1e-4f)
        assertEquals("结算收尾（进度 100%）折痕扶正", 0f, curlShape(w, h, 1f, 1, 0.5f, settling = true).lean, 1e-4f)
        assertEquals("结算起手（进度 0%）折痕扶正", 0f, curlShape(w, h, 0f, -1, 0.5f, settling = true).lean, 1e-4f)
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
}
