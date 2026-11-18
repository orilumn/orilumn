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
        // 这里直接量低层几何：两个方向都传 front = 当前页，起手帧都画 front。
        // 翻页层实际翻上一页时会把「被翻的那张」换成目标页（见 [previousPageTurnsTheTargetSheet]），
        // 低层镜像几何只用来对照，不再直接上屏。
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
     * 翻上一页时**书脊仍在左**：上一页不是下一页的左右镜像，而是同一套「左边平铺、卷筒朝右」
     * 的几何倒着走。镜像几何会让纸挂到右缘、看起来右边成了书脊（读者：「左边是书脊，不会因为
     * 要翻到上一页，右边就变成书脊了」）。
     *
     * 折痕位置与斜率必须和上一页原来的手势一致：进度取 `1-p` 后折痕仍在 `w·p`，倾斜取反
     * 抵消几何侧的镜像。只有「被翻的那张纸」和「露出页」换位。
     */
    @Test
    fun previousPageKeepsTheSpineOnTheLeft() {
        val w = 400f
        val h = 600f
        val tilt = 0.35f
        for (p in listOf(0.1f, 0.45f, 0.9f)) {
            val next = curlFrame(w, h, p, direction = 1, tilt = tilt)
            assertFalse("下一页翻的是当前页", next.turningIsTarget)
            assertEquals("下一页的几何", 1, next.shape.direction)
            assertEquals(p, next.shape.progress, 1e-6f)

            val prev = curlFrame(w, h, p, direction = -1, tilt = tilt)
            assertTrue("上一页翻的是上一页那张纸", prev.turningIsTarget)
            assertEquals("上一页的卷曲几何也必须朝左书脊那一侧", 1, prev.shape.direction)
            assertEquals("上一页是同一套几何倒着走", 1f - p, prev.shape.progress, 1e-6f)
            assertEquals("折痕仍按手势扫到 w·p", w * p, prev.shape.foldX, 1e-3f)

            // 折痕与倾斜和原来的上一页几何逐点一致：只换卷曲侧，不镜像手势。
            val old = curlShape(w, h, p, direction = -1, tilt = tilt)
            assertEquals(old.topX, prev.shape.topX, 1e-3f)
            assertEquals(old.bottomX, prev.shape.bottomX, 1e-3f)
            assertEquals(old.lean, prev.shape.lean, 1e-6f)
            // 末段扶正对称：1-p 后的淡出系数与 p 相同。
            assertEquals(
                tailFade(p),
                curlFrame(w, h, p, direction = -1, tilt = tilt, settling = true).shape.shadowFade,
                1e-6f,
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
     * 正是读者说的「折起的页角直角两条边」。两条直角边必须落影，少了任何一条，纸背就像
     * 一张剪贴上去的白纸（原话「直角两条边为什么没画阴影」）；自由边挨着纸卷，纸压着纸，
     * 反而**不许**落影（见 [freeEdgeOfTheTurnedBackIsNeitherShadowedNorOutlined]）。
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

    private fun luma(c: Color): Float = (c.red + c.green + c.blue) / 3f
}
