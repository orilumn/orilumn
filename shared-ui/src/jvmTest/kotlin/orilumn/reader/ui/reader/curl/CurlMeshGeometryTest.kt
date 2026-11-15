package orilumn.reader.ui.reader.curl

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷曲网格的**几何**验收（不碰绘制）。
 *
 * 盯三件事，都是「看得见的错」会先在这里断的那种：
 *
 * 1. 纸卷在折痕的**掀起侧**、纸背落回另一侧 —— 两侧同侧时纸卷被平铺段整块盖住，
 *    屏幕上看不出卷（曾经就是这样，被当成「3D 画不出来」）；
 * 2. 屏幕最外沿正好在折痕 + 半径处：露出区从那里开始，偏了就是「手指和画面对不上」；
 * 3. 缺材料/退化输入给出空网格，而不是画一块乱七八糟的三角形。
 */
class CurlMeshGeometryTest {

    private val w = 900f
    private val h = 1300f
    private val radius = w * CURL3D_RADIUS_RATIO

    private fun meshAt(progress: Float, eyeZ: Float = 0f): CurlMesh {
        val shape = curlShape(w, h, progress, 1, 0f, false)
        return buildCurlMesh(shape, radius = radius, splits = 32, eyeZ = eyeZ)
    }

    private fun xs(mesh: CurlMesh): List<Float> =
        (0 until mesh.positions.size / 2).map { mesh.positions[it * 2] }

    @Test
    fun rollSitsOnLiftedSideAndBackOnTheOther() {
        val shape = curlShape(w, h, 0.5f, 1, 0f, false)
        val fold = shape.foldX
        val mesh = meshAt(0.5f)
        assertTrue("中段必须铺出网格", mesh.indexCount > 0)

        // 折痕在 fold，掀起侧在右：最外沿 = fold + R（纸卷的轮廓）。
        val maxX = xs(mesh).max()
        assertTrue(
            "纸卷轮廓应在折痕 +R：fold=$fold maxX=$maxX R=$radius",
            abs(maxX - (fold + radius)) < radius * 0.05f,
        )

        // 纸背落在折痕左侧：最小 x 明显小于折痕，且不会越过页面左缘。
        val minX = xs(mesh).min()
        assertTrue("纸背必须落在折痕另一侧：minX=$minX fold=$fold", minX < fold - radius)
        assertTrue("纸背不该越过页面左缘：minX=$minX", minX >= -1f)

        // 三段都在，且平铺段确实存在（sMax > πR 才有）。
        val surfaces = mesh.bands.map { it.surface }.toSet()
        assertEquals(setOf(CurlMesh.Surface.Recto, CurlMesh.Surface.Verso, CurlMesh.Surface.Back), surfaces)
    }

    @Test
    fun earlyProgressHasNoFlatBackYet() {
        // 掀起的那一段还不够绕满半圈 ⇒ 只有正面 + 卷背，没有平铺回来的纸背。
        val p = 0.1f
        val shape = curlShape(w, h, p, 1, 0f, false)
        val sMax = w - shape.foldX
        assertTrue("这一档的前提：材料不足半圈", sMax < PI.toFloat() * radius)
        val surfaces = meshAt(p).bands.map { it.surface }.toSet()
        assertTrue("材料不足半圈时不该有平铺段", CurlMesh.Surface.Back !in surfaces)
        assertTrue(CurlMesh.Surface.Recto in surfaces)
    }

    @Test
    fun perspectiveMakesTheRollWider() {
        // 卷起来那一段离观察者更近 ⇒ 透视后比正交投影更宽。这是「读得出立体」的唯一来源。
        val flat = meshAt(0.5f, eyeZ = 0f)
        val perspective = meshAt(0.5f, eyeZ = h * CURL3D_EYE_RATIO)
        val flatSpan = xs(flat).max() - xs(flat).min()
        val perspSpan = xs(perspective).max() - xs(perspective).min()
        assertTrue("透视必须放大卷起来那一段：$perspSpan vs $flatSpan", perspSpan > flatSpan)
    }

    @Test
    fun degenerateInputGivesEmptyMesh() {
        // 折痕在页面右缘外：没有可掀起的材料。
        val outside = CurlShape(
            pageW = w, pageH = h, topX = w + 50f, bottomX = w + 50f, foldX = w + 50f,
            lean = 0f, shadowFade = 1f, progress = 0f, direction = 1,
        )
        assertEquals(0, buildCurlMesh(outside, radius, 16, 0f).indexCount)

        // 零半径也不能除零炸掉。
        val mid = curlShape(w, h, 0.5f, 1, 0f, false)
        assertTrue(buildCurlMesh(mid, 0f, 16, 0f).indexCount > 0)
    }

    @Test
    fun textureCoordsFollowTheMaterial() {
        // 纹理坐标是**材料**坐标（纸上的位置），不是屏幕坐标：纸背把材料镜射回另一侧，
        // 所以纸背那一段的 uv 必须仍然落在页面矩形里。
        val mesh = meshAt(0.5f)
        for (i in 0 until mesh.texCoords.size / 2) {
            val u = mesh.texCoords[i * 2]
            val v = mesh.texCoords[i * 2 + 1]
            assertTrue("uv.x 越界：$u", u >= -1f && u <= w + 1f)
            assertTrue("uv.y 越界：$v", v >= -1f && v <= h + 1f)
        }
    }
}
