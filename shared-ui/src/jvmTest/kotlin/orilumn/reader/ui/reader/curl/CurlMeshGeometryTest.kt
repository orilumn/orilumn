package orilumn.reader.ui.reader.curl

import kotlin.math.PI
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卷曲网格的**几何**验收（不碰绘制）。
 *
 * 盯模型本身：旋转坐标系 + 按页面矩形裁剪的扫线（`NewCurl3D.Mesh3D.curl`）。
 * 上一版是「半平面对折」，这几件都做不到：
 *
 * 1. 扫线端点来自**页面矩形**的交点 ⇒ 纸带从页顶贯通到页底，页面角点也在网格里；
 * 2. 屏幕位置只由「到折痕的 x'」决定 ⇒ 页面上的直角仍然是直角；
 * 3. 网格**只盖掀起的那一半材料**：没掀的半页由调用方整页画，盖两遍会重影；
 * 4. 纸卷**朝掀起侧鼓出去**、绕满半圈的平铺段落回**没掀起**的那一侧（接反就是
 *    「三段文字」＋「翻到九成还像没翻动」）；
 * 5. 纸卷是个细筒（半径上限 5% 页宽），不是「筒径等于整页」，也不宽到咬住新页的正文。
 */
class CurlMeshGeometryTest {

    private val w = 900f
    private val h = 1300f
    private val cap = w * CURL3D_RADIUS_RATIO

    private fun meshAt(progress: Float, tilt: Float = 0f, direction: Int = 1): CurlMesh {
        val shape = curlShape(w, h, progress, direction, tilt, false)
        return buildCurlMesh(shape, radius = curlRadius(shape), splits = CURL3D_SPLITS)
    }

    private fun xs(mesh: CurlMesh): List<Float> =
        (0 until mesh.vertexCount).map { mesh.positions[it * 2] }

    /** 纸卷在屏幕上占的宽度：掀起侧最远端离折痕的距离。 */
    private fun rollWidth(progress: Float, direction: Int = 1): Float {
        val shape = curlShape(w, h, progress, direction, 0f, false)
        val mesh = meshAt(progress, direction = direction)
        var far = shape.foldX
        for (band in mesh.bands) {
            if (band.surface == CurlMesh.Surface.Back) continue
            for (pt in mesh.bandOutline(band)) {
                if (direction >= 0) {
                    if (pt.x > far) far = pt.x
                } else {
                    if (pt.x < far) far = pt.x
                }
            }
        }
        return abs(far - shape.foldX)
    }

    @Test
    fun radiusFollowsTheUnturnedPaper() {
        // 参考实现 `updateCurlPos`：半径 = 折痕到「还没掀起来那一侧页边」的距离，封顶 [CURL3D_RADIUS_RATIO]。
        // 半径一旦到顶就是恒定粗细的筒往前滚（筒径不跟进度走，见 CURL3D_RADIUS_RATIO 的注释）。
        val backCap = w * CURL3D_BACK_SHARE / PI.toFloat()
        assertEquals("起手时纸背至少占一半 ⇒ 半径按掀起长度算", backCap * 0.1f, curlRadius(curlShape(w, h, 0.1f, 1)), 1f)
        assertEquals("过了三成由 5% 页宽封顶", cap, curlRadius(curlShape(w, h, 0.35f, 1)), 1f)
        assertEquals(cap, curlRadius(curlShape(w, h, 0.9f, 1)), 1f)
        // 收尾：剩下的纸比上限还窄 ⇒ 半径跟着剩下的纸收细（纸卷自然消失）。
        assertEquals(w * 0.03f, curlRadius(curlShape(w, h, 0.97f, 1)), 1f)
        assertEquals(w * 0.01f, curlRadius(curlShape(w, h, 0.99f, 1)), 1f)
        // 向前翻是镜像口径：剩下的纸在右边。
        assertEquals(cap, curlRadius(curlShape(w, h, 0.7f, -1)), 1f)
        assertEquals(w * 0.03f, curlRadius(curlShape(w, h, 0.97f, -1)), 1f)
    }

    /**
     * 翻起来的纸里**至少一半是平铺的纸背**（不是全卷在筒上）。
     *
     * 读者报过「滚筒左侧的页面反面为什么不画」：纸卷绕半圈要吃掉 `πR` 的纸，半径一旦超过
     * `掀起长度/π`，翻起来那部分纸就整段贴在筒上，纸背一段都不剩。这条锁住 [CURL3D_BACK_SHARE]。
     */
    @Test
    fun turnedBackIsAlwaysAtLeastHalfOfTheLiftedPaper() {
        for (dir in listOf(1, -1)) {
            for (p in listOf(0.02f, 0.05f, 0.1f, 0.2f, 0.35f, 0.5f, 0.75f, 0.95f)) {
                val shape = curlShape(w, h, p, dir)
                val radius = curlRadius(shape)
                val lifted = w * p
                assertTrue(
                    "dir=$dir p=$p 纸背只剩 ${lifted - PI.toFloat() * radius}：纸卷吃掉了全部掀起材料",
                    lifted - PI.toFloat() * radius >= lifted * CURL3D_BACK_SHARE - 1f,
                )
                val mesh = meshAt(p, direction = dir)
                assertTrue("dir=$dir p=$p 必须有平铺段", mesh.bands.any { it.surface == CurlMesh.Surface.Back })
            }
        }
    }

    @Test
    fun rollDiameterIsATubeNotTheWholePage() {
        // 读者报过「筒径几乎等于整页」：半径上限必须是细筒，不是半页宽。
        assertTrue("半径上限应当只占 5% 页宽：$cap / $w", cap <= w * 0.05f + 0.01f)
        for (p in listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)) {
            assertTrue("p=$p 纸卷宽度不该接近整页：${rollWidth(p)}", rollWidth(p) <= cap + 1f)
        }
    }

    @Test
    fun rollHidesNoMoreThanTheTargetMargin() {
        // 纸卷压在露出页靠折痕的那一条上，被盖住的宽度就是半径；半径大过左边距就会啃新页的正文。
        // 平板：页宽 1840、版心左边距 128px（≈7%）⇒ 半径必须落在边距里。
        // 参考实现的 ≈0.108 页宽 = 200px 比边距还宽，读者看到的就是「新页每行左边缺一截」。
        val margin = 128f / 1840f
        assertTrue("半径应落进新页左边距：${cap / w} vs $margin", cap / w <= margin)
        // 露出页的宽度要跟翻过的比例基本一致，不能整段被纸卷吃掉。
        for (p in listOf(0.2f, 0.35f, 0.5f, 0.7f)) {
            val exposed = p * w - rollWidth(p)
            assertTrue("p=$p 新页露出 ${exposed / w}，应当接近进度", exposed >= (p - 0.06f) * w)
        }
    }

    @Test
    fun rollKeepsItsThicknessThenTapers() {
        // 主程：半径不随进度变 ⇒ 纸卷是一个恒定粗细的筒往前滚。
        // （起手那一段由「纸背至少占一半」的上限接管，见 turnedBackIsAlwaysAtLeastHalfOfTheLiftedPaper。）
        for (p in listOf(0.35f, 0.5f, 0.7f, 0.9f)) {
            assertEquals("p=$p 纸卷应保持恒定粗细", cap, rollWidth(p), 2f)
        }
        // 剩下的纸比上限还窄之后才收细，翻完时纸卷应当基本收掉。
        assertTrue("p=0.97 开始收卷：${rollWidth(0.97f)}", rollWidth(0.97f) < cap * 0.8f)
        assertTrue("p=0.99 收卷：${rollWidth(0.99f)}", rollWidth(0.99f) < w * 0.02f)
    }

    @Test
    fun rollBulgesTowardTheLiftedSide() {
        // 掀起的材料在折痕右侧 ⇒ 纸卷必须鼓在**右边**（露出页那一侧），
        // 绕满半圈后的平铺段落回左边（盖在还没翻走的纸上）。两侧接反就是「三段文字」。
        for (p in listOf(0.2f, 0.5f, 0.9f)) {
            val shape = curlShape(w, h, p, 1, 0f, false)
            val mesh = meshAt(p)
            for (band in mesh.bands) {
                val pts = mesh.bandOutline(band)
                if (band.surface == CurlMesh.Surface.Back) {
                    assertTrue(
                        "p=$p 平铺段应当落回没掀起的那一侧：${pts.maxOf { it.x }} / ${shape.foldX}",
                        pts.all { it.x <= shape.foldX + 1f },
                    )
                } else {
                    assertTrue(
                        "p=$p ${band.surface} 应当鼓在掀起侧：${pts.minOf { it.x }} / ${shape.foldX}",
                        pts.all { it.x >= shape.foldX - 1f },
                    )
                }
            }
            // 向前翻整体镜像：纸卷在左边、平铺段在右边。
            val backShape = curlShape(w, h, p, -1, 0f, false)
            val backMesh = meshAt(p, direction = -1)
            for (band in backMesh.bands) {
                val pts = backMesh.bandOutline(band)
                if (band.surface == CurlMesh.Surface.Back) {
                    assertTrue(
                        "向前翻 p=$p 平铺段应当在折痕右边：${pts.minOf { it.x }} / ${backShape.foldX}",
                        pts.all { it.x >= backShape.foldX - 1f },
                    )
                } else {
                    assertTrue(
                        "向前翻 p=$p ${band.surface} 应当鼓在掀起侧：${pts.maxOf { it.x }} / ${backShape.foldX}",
                        pts.all { it.x <= backShape.foldX + 1f },
                    )
                }
            }
        }
    }

    @Test
    fun meshCoversOnlyTheLiftedMaterial() {
        // 网格只铺掀起的**材料**：材料坐标必须全在折痕掀起的那一侧。
        // 连没掀的半页一起铺，就是同一张纸画两遍（文字重影），明暗与压淡也会铺到没掀的纸上。
        for (p in listOf(0.2f, 0.5f, 0.9f)) {
            for (dir in listOf(1, -1)) {
                val shape = curlShape(w, h, p, dir, 0f, false)
                val mesh = meshAt(p, direction = dir)
                for (v in 0 until mesh.vertexCount) {
                    val u = mesh.texCoords[v * 2]
                    if (dir >= 0) {
                        assertTrue("p=$p 材料 $u 跑到折痕左边（没掀的半页）", u >= shape.foldX - 1f)
                    } else {
                        assertTrue("p=$p 材料 $u 跑到折痕右边（没掀的半页）", u <= shape.foldX + 1f)
                    }
                }
            }
        }
    }

    @Test
    fun everyScanlineSpansTheFullPageHeight() {
        // 竖折痕时每条扫线都与页上下边相交 ⇒ 纸带从页顶贯通到页底，不会「上半截没有纸」。
        for (p in listOf(0.2f, 0.4f, 0.7f, 0.9f)) {
            val mesh = meshAt(p)
            assertTrue("p=$p 必须铺出网格", mesh.vertexCount >= 4)
            // 每条扫线两个端点：按 (hi, lo) 成对输出。
            var i = 0
            while (i < mesh.vertexCount) {
                val y0 = mesh.positions[i * 2 + 1]
                val y1 = mesh.positions[(i + 1) * 2 + 1]
                assertTrue("p=$p 第 ${i / 2} 条扫线上端应在页顶：$y0", abs(y0 - h) <= 1f)
                assertTrue("p=$p 第 ${i / 2} 条扫线下端应在页底：$y1", abs(y1) <= 1f)
                i += 2
            }
        }
    }

    @Test
    fun liftedPageCornersAreInTheMesh() {
        // 掀起的那个页角必须作为顶点存在（用材料坐标认），且它的两条边仍是相互垂直的直线：
        // 竖折痕下页右边仍竖直、上下边仍水平 ⇒ 角是直角，没被斜切成一条平行于折痕的边。
        val mesh = meshAt(0.4f)
        val liftedCorners = listOf(w to 0f, w to h)
        val found = HashMap<Int, Int>()
        for ((ci, c) in liftedCorners.withIndex()) {
            for (v in 0 until mesh.vertexCount) {
                val u = mesh.texCoords[v * 2]
                val t = mesh.texCoords[v * 2 + 1]
                if (abs(u - c.first) <= 1f && abs(t - c.second) <= 1f) {
                    found[ci] = v
                    break
                }
            }
        }
        assertEquals("掀起的两个页角都应出现在网格顶点里，缺了哪一个就是角被削掉了", 2, found.size)

        val topRight = found[0]!!
        val bottomRight = found[1]!!
        val sx0 = mesh.positions[topRight * 2]
        val sx1 = mesh.positions[bottomRight * 2]
        assertTrue("卷起来的页右边仍是一条竖直线：$sx0 vs $sx1", abs(sx0 - sx1) <= 1f)
        assertTrue("右上角应随纸一起被卷起来（不会还贴在页右缘）：$sx0", sx0 < w - 1f)
    }

    @Test
    fun backwardFlipMirrorsTheRoll() {
        // 向前翻是向后翻的左右镜像：同一套式子、一个 `side` 因子。
        for (p in listOf(0.3f, 0.7f)) {
            val forward = meshAt(p)
            val backward = meshAt(p, direction = -1)
            assertEquals("镜像后顶点数一致", forward.vertexCount, backward.vertexCount)
            assertEquals("纸卷粗细一致", rollWidth(p), rollWidth(p, -1), 1f)
            for (v in 0 until forward.vertexCount) {
                assertEquals(
                    "第 $v 个顶点的材料 x 应当镜像",
                    w - forward.texCoords[v * 2],
                    backward.texCoords[v * 2],
                    1f,
                )
                assertEquals(
                    "第 $v 个顶点的屏幕 x 应当镜像",
                    w - forward.positions[v * 2],
                    backward.positions[v * 2],
                    1f,
                )
                assertEquals(
                    "第 $v 个顶点的 y 不变",
                    forward.positions[v * 2 + 1],
                    backward.positions[v * 2 + 1],
                    1f,
                )
            }
        }
    }

    @Test
    fun verticesStayInsideThePage() {
        for (p in listOf(0.2f, 0.4f, 0.7f, 0.9f)) {
            val shape = curlShape(w, h, p, 1, 0f, false)
            val mesh = meshAt(p)
            assertEquals("顶点数组必须一一对应", mesh.vertexCount, mesh.positions.size / 2)
            assertEquals("弧长数组必须与顶点一一对应", mesh.vertexCount, mesh.arc.size)
            assertEquals("纹理坐标数组必须与顶点一一对应", mesh.vertexCount, mesh.texCoords.size / 2)
            var maxIndex = -1
            for (k in 0 until mesh.indexCount) {
                val v = mesh.indices[k].toInt()
                if (v > maxIndex) maxIndex = v
            }
            assertTrue("索引必须落在顶点范围内：$maxIndex / ${mesh.vertexCount}", maxIndex < mesh.vertexCount)
            for (v in 0 until mesh.vertexCount) {
                // 材料坐标永远在页面矩形里（网格是页面的子集，不凭空造纸）。
                val u = mesh.texCoords[v * 2]
                val t = mesh.texCoords[v * 2 + 1]
                assertTrue("p=$p 材料 u 越出页面：$u", u >= -1f && u <= w + 1f)
                assertTrue("p=$p 材料 v 越出页面：$t", t >= -1f && t <= h + 1f)
            }
            // 纸卷（正面 / 纸背）与平铺段：纸卷允许探出页边最多一个半径（画布那头裁到页内），
            // 平铺段整段贴在没掀起的那一侧，可能整截滑到页面外（同样由画布裁）。
            val radius = curlRadius(shape)
            for (band in mesh.bands) {
                for (pt in mesh.bandOutline(band)) {
                    val x = pt.x
                    val y = pt.y
                    if (band.surface == CurlMesh.Surface.Back) {
                        assertTrue("p=$p 平铺段应当落在没掀起的那一侧：$x", x <= shape.foldX + 1f)
                    } else {
                        assertTrue("p=$p ${band.surface} 顶点 x 越出页面太远：$x", x >= -radius - 1f && x <= w + radius + 1f)
                    }
                    assertTrue("p=$p 顶点 y 越出页面：$y", y >= -radius - 1f && y <= h + radius + 1f)
                }
            }
        }
    }

    @Test
    fun bandsCoverEveryTriangleExactly() {
        for (p in listOf(0.2f, 0.5f, 0.7f, 0.9f)) {
            val mesh = meshAt(p)
            var sum = 0
            var prevEnd = 0
            for (b in mesh.bands) {
                assertEquals("各段必须首尾相接", prevEnd, b.indexStart)
                assertEquals("索引数必须是 3 的倍数", 0, b.indexCount % 3)
                prevEnd = b.indexStart + b.indexCount
                sum += b.indexCount
                // 段的外轮廓要盖住整段（压淡 / 明暗都照着它铺）。
                val outline = mesh.bandOutline(b)
                assertEquals("轮廓顶点数 = 两侧长边各一行一点", (b.lastRow - b.firstRow + 1) * 2, outline.size)
            }
            assertEquals("各段索引数之和必须等于总索引数", mesh.indexCount, sum)
        }
    }

    @Test
    fun threeSurfacesAppearInOrder() {
        // 三段在一开始就都在：半径按「纸背至少占一半」收细之后，掀起的那点纸也够绕一个半圈
        // （掀起长度 = 2πR），所以起手时「正面 + 纸背 + 平铺段」同时存在——读者的纸背从第一下就有。
        // 画序就是 z 序：正面 → 纸背 → 平铺段。
        assertTrue("起手就要有纸背（Verso）", meshAt(0.02f).bands.any { it.surface == CurlMesh.Surface.Verso })
        assertTrue("起手就要有平铺的纸背（Back）", meshAt(0.02f).bands.any { it.surface == CurlMesh.Surface.Back })
        assertTrue("过半圈后出现纸背", meshAt(0.3f).bands.any { it.surface == CurlMesh.Surface.Verso })
        assertTrue("绕满半圈出现平铺段", meshAt(0.5f).bands.any { it.surface == CurlMesh.Surface.Back })
        for (p in listOf(0.5f, 0.7f, 0.9f)) {
            val order = meshAt(p).bands.map { it.surface }
            assertEquals("画序必须是正面 → 纸背 → 平铺段", order.sortedBy { it.ordinal }, order)
        }
    }

    @Test
    fun arcFollowsTheMaterial() {
        // 弧长 = 到折痕的纸面距离，必须单调跟着扫线走，折痕上为 0。
        val mesh = meshAt(0.9f)
        assertEquals("折痕上弧长为 0", 0f, mesh.arc[0], 0.5f)
        assertTrue("掀起侧最远弧长应接近整页被卷起的材料量", mesh.arc.max() > w * 0.8f)
        // 明暗按弧长算卷曲高度：p=0.9 时材料远超半圈，最高处就是 2R。
        val radius = curlRadius(curlShape(w, h, 0.9f, 1))
        val theta = (mesh.maxArc() / radius).coerceAtMost(PI.toFloat())
        assertEquals("p=0.9 绕满半圈", PI.toFloat(), theta, 1e-3f)
        assertTrue("投影宽度不超过半径", rollWidth(0.9f) <= radius + 1f)
    }

    @Test
    fun degenerateInputGivesEmptyMesh() {
        // 折痕在页面右缘之外：没有可掀起的材料。
        val outside = CurlShape(
            pageW = w, pageH = h, topX = w + 50f, bottomX = w + 50f, foldX = w + 50f,
            lean = 0f, shadowFade = 1f, progress = 0f, direction = 1,
        )
        assertEquals(0, buildCurlMesh(outside, curlRadius(outside), 16).indexCount)

        // 零半径不能除零炸掉（网格退化，但结构自洽）。
        val mid = curlShape(w, h, 0.5f, 1, 0f, false)
        val zero = buildCurlMesh(mid, 0f, 16)
        assertEquals(0, zero.indexCount % 3)
        assertEquals(zero.vertexCount, zero.positions.size / 2)
    }

    @Test
    fun tiltKeepsTheStripContinuous() {
        // 斜折痕：扫线仍与页面矩形求交，每条扫线都必须拿到上下两个端点（不许出现半条）。
        val mesh = meshAt(0.4f, tilt = 0.6f)
        assertTrue("斜折痕也要铺出网格", mesh.vertexCount >= 4)
        assertEquals("每对端点成组出现", 0, mesh.vertexCount % 2)
        // 斜折痕下 y 不再覆盖全高；纸边被卷起来后允许探出页面最多一个半径（纸卷本来就能压到页边
        // 之外，画布那头已经裁到页面内），但不该成片跑出去——越过一个半径就说明扫线裁剪算错了。
        val radius = curlRadius(curlShape(w, h, 0.4f, 1, 0.6f, false))
        for (v in 0 until mesh.vertexCount) {
            val x = mesh.positions[v * 2]
            val y = mesh.positions[v * 2 + 1]
            assertTrue("tilt 顶点 x 越界：$x", x >= -radius - 1f && x <= w + radius + 1f)
            assertTrue("tilt 顶点 y 越界：$y", y >= -radius - 1f && y <= h + radius + 1f)
        }
    }
}
