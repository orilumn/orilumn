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
 * 5. 纸卷是个看得见的筒（半径上限 10% 页宽），不是「筒径等于整页」，也不至于细成一条白边。
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
    fun radiusNeverFollowsTheProgress() {
        // 读者判「卷筒有时候直径会变化，有时候被压扁」：筒径**不跟进度走**，整段翻页只有一根筒。
        // 收尾也不收细——纸卷靠「滚出页边」消失，不靠把筒压扁（见 rollRollsOutOfThePageInsteadOfBeingSquashed）。
        for (dir in listOf(1, -1)) {
            for (p in listOf(0.03f, 0.1f, 0.2f, 0.35f, 0.5f, 0.75f, 0.85f, 0.9f, 0.95f, 0.97f, 0.99f, 1f)) {
                assertEquals("dir=$dir p=$p 筒径变了", cap, curlRadius(curlShape(w, h, p, dir)), 0.5f)
            }
        }
    }

    /**
     * 收尾：纸卷**滚出页边**，不是被页边压扁。
     *
     * 读者原话「纸张滚动到书脊处，无法画卷筒了」：折痕贴到书脊时纸还有整整一页，筒也还有
     * 整整一个半径宽；早先按「折痕到页边的距离」把半径收细，卷筒在最后一段被生生压平。
     * 现在筒身粗细不动，只是整根往页外滚——页内能看见的部分越来越窄，末帧正好一根不剩。
     */
    @Test
    fun rollRollsOutOfThePageInsteadOfBeingSquashed() {
        fun visible(p: Float) = (curlShape(w, h, p, 1).foldX + cap).coerceIn(0f, cap)
        assertEquals("窗口起点：整根筒都在页内", cap, visible(1f - CURL3D_RADIUS_RATIO), 1f)
        assertEquals("筒刚骑到页边：整根还在页内", cap, visible(1f - CURL3D_RADIUS_RATIO / 2f), 1f)
        assertEquals("退场半程：筒滚出去一半", cap / 2f, visible(1f - CURL3D_RADIUS_RATIO * 0.25f), 1f)
        assertEquals("末帧：整根筒出页", 0f, visible(1f), 0.5f)
        // 筒本身从没被压扁过：整段（含退场窗口）都是同一个粗细。
        for (p in listOf(0.3f, 0.6f, 0.9f, 0.95f, 0.99f)) {
            assertEquals("p=$p 筒身不该收细", cap, rollWidth(p), 2f)
        }
    }

    /**
     * 起手那一段：掀起长度不足 `πR` 时，翻起来的纸整段贴在筒上、还没有平铺的纸背。
     *
     * 这是「筒径整段不变」的**已知代价**（早先靠收细筒径保住纸背，筒径因此十倍变化）。
     * 这条只是把现状锁住：过了 `πR` 之后平铺段必须自己出来，别让它一直不出现。
     */
    @Test
    fun flatBackAppearsOnceThePaperIsLongEnough() {
        val radius = curlRadius(curlShape(w, h, 0.5f, 1))
        val enough = PI.toFloat() * radius / w
        for (dir in listOf(1, -1)) {
            for (p in listOf(0.5f, 0.75f, 0.9f)) {
                val mesh = meshAt(p, direction = dir)
                assertTrue(
                    "dir=$dir p=$p（掀起 ${w * p} ≥ πR=${PI.toFloat() * radius}）必须有平铺段",
                    mesh.bands.any { it.surface == CurlMesh.Surface.Back },
                )
            }
            for (p in listOf(0.05f, 0.1f, 0.2f)) {
                if (w * p > PI.toFloat() * radius) continue
                val shape = curlShape(w, h, p, dir)
                assertEquals("dir=$dir p=$p 筒径不该为纸背让路", cap, curlRadius(shape), 0.5f)
            }
            assertTrue("掀起长度门槛算错了：enough=$enough", enough in 0.2f..0.4f)
        }
    }

    @Test
    fun rollDiameterIsATubeNotTheWholePage() {
        // 读者报过「筒径几乎等于整页」：半径上限必须是筒，不是半页宽；但也不能细成一条白边。
        assertTrue("半径上限应当是页宽的 10%：$cap / $w", abs(cap / w - 0.10f) <= 1e-4f)
        for (p in listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f)) {
            assertTrue("p=$p 纸卷宽度不该接近整页：${rollWidth(p)}", rollWidth(p) <= cap + 1f)
        }
    }

    @Test
    fun rollCoversOnlyTheTargetMarginAndASliverOfText() {
        // 纸卷压在露出页靠折痕的那一条上，被盖住的宽度就是半径。平板左边距 128/1840 ≈ 7%，
        // 半径 10% ⇒ 会压到每行开头约 3% 页宽。这是「看得见卷筒」的代价，但不能再多。
        val margin = 128f / 1840f
        assertTrue("半径可以吃掉边距，压到正文不许超过 3% 页宽：${cap / w} vs $margin", cap / w <= margin + 0.031f)
        // 露出页的宽度要跟翻过的比例基本一致，最多只差一个半径。
        for (p in listOf(0.2f, 0.35f, 0.5f, 0.7f)) {
            val exposed = p * w - rollWidth(p)
            assertTrue("p=$p 新页露出 ${exposed / w}，应当跟着进度走", exposed >= (p - CURL3D_RADIUS_RATIO) * w - 1f)
        }
    }

    @Test
    fun rollKeepsItsThicknessAllTheWayThrough() {
        // 主程：半径不随进度变 ⇒ 纸卷是一个恒定粗细的筒往前滚。
        // （起手那一段由「纸背至少占三成」的上限接管，见 turnedBackAlwaysKeepsSomeFlatPaper。）
        for (p in listOf(0.5f, 0.7f, 0.9f, 0.95f, 0.99f)) {
            assertEquals("p=$p 纸卷应保持恒定粗细", cap, rollWidth(p), 2f)
        }
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
        // 筒径固定（0.10 页宽）之后，纸卷绕半圈要吃掉 πR 的纸：掀起长度不到 πR/2 时连纸背
        // 都还没绕出来，不到 πR 时没有平铺段。这是「筒径整段不变」的**已知代价**（起手那
        // 几下翻起来只是一根细筒），过了门槛三段就齐了；画序就是 z 序：正面 → 纸背 → 平铺段。
        val radius = cap
        val versoFrom = PI.toFloat() * radius / 2f / w
        val backFrom = PI.toFloat() * radius / w
        assertTrue(
            "门槛算错了：versoFrom=$versoFrom backFrom=$backFrom",
            versoFrom in 0.1f..0.2f && backFrom in 0.25f..0.35f,
        )
        assertTrue(
            "掀起不足半圈不该有纸背",
            meshAt(versoFrom - 0.05f).bands.none { it.surface == CurlMesh.Surface.Verso },
        )
        assertTrue(
            "掀起过 πR/2 就该露出纸背",
            meshAt(versoFrom + 0.05f).bands.any { it.surface == CurlMesh.Surface.Verso },
        )
        assertTrue(
            "纸卷还没绕满半圈不该有平铺段",
            meshAt(backFrom - 0.05f).bands.none { it.surface == CurlMesh.Surface.Back },
        )
        assertTrue(
            "掀起过 πR 就该有平铺段",
            meshAt(backFrom + 0.05f).bands.any { it.surface == CurlMesh.Surface.Back },
        )
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
