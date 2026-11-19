package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 卷曲的**几何**（用户层纯函数，页坐标系，单位 px）。
 *
 * ## 模型：旋转坐标系 + 按页面矩形裁剪的扫线（照搬 `NewCurl3D.Mesh3D.curl`）
 *
 * 上一版是「半平面对折」：折痕一侧整个半平面被掀起来，网格边界永远平行于折痕。
 * 那个模型下页面角点不参与几何，于是角永远是斜切的；斜折痕时掀起区退化成三角形，
 * 上半截没有纸。这里换成参考实现的坐标系：
 *
 * 1. **折痕坐标系**：原点 = 折痕与页面水平中线的交点；`t` 沿折痕向下，`n` 是折痕法向
 *    （指向 +x 一侧）。页面四角只在原点平移 + 旋转，所以直角天然存在。
 * 2. **扫线**：`x' = 0`、`x' = −πR·i/(splits−1)`，再加上**页面四角各自的 x'**——
 *    角点扫线让页面轮廓的折点精确落在网格上（角没被削掉）。
 * 3. 每条扫线与**页面矩形**求交（参数化裁剪），得到该线在页面内的 `λ` 区间。
 *    这就是「按页面矩形裁剪」，上一版缺的就是这一步。
 * 4. 端点按 `x'` 变换（R = 卷曲半径）：
 *
 * ```
 *   x' ≥ 0        : 不卷，原地（纸平铺在页面上）
 *   −πR ≤ x' < 0  : θ = x'/R，屏幕偏移 = R·sinθ，高度 z = R(1−cosθ)   （圆柱）
 *   x' < −πR      : 屏幕偏移 = −(πR + x')，z = 2R                    （绕满半圈后平铺回来）
 * ```
 *
 * 5. 相邻两条扫线的端点连成四边形（两个三角）。扫线按 `x'` 从大到小排列，
 *    所以四边形总是「同一张纸上相邻两段」。
 *
 * ## 掀的是哪一半、卷在哪一侧
 *
 * `x' < 0` 是「已经掀起来、离开页面的那一半」，它在折痕的哪一侧由**翻页方向**决定：
 * 向后翻掀右半边（露出页在右），向前翻掀左半边（露出页在左）。这一侧统一用
 * [CurlShape.direction] 算出的 `side` 表示，几何本身只有一套式子。
 *
 * 卷起来的那一半**朝掀起侧鼓出去**、绕满半圈后的平铺段**落回没掀起的那一侧**
 * （参考实现 `Mesh3D.curl`：局部 +x 是拖动方向，`x = R·sin(x'/R)` 把掀起侧的材料
 * 卷到拖动方向的另一侧）。两条都别接反：接反时纸卷压在没掀起的那半页上（同一段文字
 * 叠两遍），平铺段又会盖住露出页（翻到九成还像没翻动）。
 *
 * ## 三段可见面（画序即 z 序）
 *
 * | 段 | 位置 | 看得见的那一面 |
 * | --- | --- | --- |
 * | [Surface.Recto] | `z < R`（`x' ≥ −πR/2`） | 纸正面 |
 * | [Surface.Verso] | `z ≥ R`（`−πR ≤ x' < −πR/2`） | 纸背面（卷筒外圈） |
 * | [Surface.Back] | `x' < −πR`（平铺段） | 绕满半圈后翻回来的那一面 |
 *
 * ## 纹理坐标 = 材料坐标
 *
 * `texCoords` 给的是**未卷曲时**该点所在的页面坐标（参考实现里页矩形角点自带 tex 坐标、
 * 求交时线性插值，等价）。着色器按它采样，纸背的镜像由几何自然产生；**字不会被镜像**，
 * 因为纹理坐标是材料坐标，跟屏幕位置无关。
 */
internal class CurlMesh(
    /** 每顶点 (x, y)，页坐标。 */
    val positions: FloatArray,
    /** 每顶点 (u, v)，**材料坐标**（页坐标，未折叠）：着色器按它采样纹理。 */
    val texCoords: FloatArray,
    /** 每顶点「到折痕的弧长」（px）：平铺侧为 0，掀起侧 = `R·|θ|`。 */
    val arc: FloatArray,
    /** 索引（各段按 [bands] 顺序排列）。 */
    val indices: ShortArray,
    val indexCount: Int,
    val bands: List<Band>,
) {
    /** 一段可见面：一段索引区间 + 该段是哪一面。 */
    internal class Band(
        val surface: Surface,
        val indexStart: Int,
        val indexCount: Int,
        /** 入纸深度（0..1）：平铺段一定程度压在正面页上方，用于决定它排在哪一段之后。 */
        val depth: Float,
        /**
         * 本段覆盖的扫线行区间（闭区间）。每行贡献两个顶点（`2r` 与 `2r+1`），
         * 所以 `firstRow..lastRow` 也唯一确定了这一段的外轮廓（见 [bandOutline]）。
         * 压淡 / 明暗要贴着这个轮廓走——另画一条矩形带会漏成横贯折痕的半透明条。
         */
        val firstRow: Int,
        val lastRow: Int,
    )

    internal enum class Surface {
        /** 正面（z < R）：页栅格原样。 */
        Recto,

        /** 背面（z ≥ R）：鼓出来的那半圈，压淡。 */
        Verso,

        /** 绕满半圈后平铺回来的那一段（落在**没掀起**的那一侧，盖在正面页上），压淡。 */
        Back,
    }

    val vertexCount: Int get() = arc.size
}

/** 弧段行数上限（Short 索引的安全上限内）。 */
private const val MAX_SPLITS = 96

/** 两条扫线的最小间距（px）：比它更近的扫线合并（角点扫线可能和等分线重合）。 */
private const val ROW_EPSILON = 0.5f

/**
 * 纸背内容压淡：盖上去的**纸色不透明度**。
 *
 * 参考实现是 0.1（`BACKFACE_ALPHA = 0.9`，只压一成）：纸背是**一比一的镜像文字**，
 * 内容留九成，读者才认得出「这是同一页纸翻过来的反面」。压到 0.8 以上只剩一成透印时，
 * 它退化成一张空白条，读者反而判「纸背没画」。
 *
 * 纸背跟旧页同纸同色，光靠内容还不够：外沿轮廓与纸面明暗由 [drawCurl3DFrame] 另画。
 */
internal const val CURL_BACK_WASH_ALPHA: Float = 0.10f

/**
 * 由一帧折痕几何 + 卷曲半径生成网格。
 *
 * @param radius 卷曲半径（px）。**是「折痕到还没掀起来那一侧页边的距离」**，不是进度本身
 *   （见 [curlRadius] / `NewCurl3D.updateCurlPos` 的 `dMax`：手指离页边多远就卷多粗）。
 * @param splits 圆柱段的扫线数（越大越圆；三角形数 ≈ 扫线数 × 2）。
 */
internal fun buildCurlMesh(
    shape: CurlShape,
    radius: Float,
    splits: Int = CURL3D_SPLITS,
): CurlMesh {
    val w = shape.pageW
    val h = shape.pageH
    val r = radius.coerceAtLeast(1e-3f)
    val halfTurn = PI.toFloat() * r

    // 折痕坐标系：原点 = 折痕与页面水平中线的交点；t = 沿折痕向下，n = 法向（指向 +x 一侧）。
    val top = Offset(shape.topX, 0f)
    val bottom = Offset(shape.bottomX, h)
    val foldLen = hypot(bottom.x - top.x, h).coerceAtLeast(1e-3f)
    val tx = (bottom.x - top.x) / foldLen
    val ty = h / foldLen
    val nx = ty
    val ny = -tx
    val ox = (top.x + bottom.x) * 0.5f
    val oy = h * 0.5f

    // 掀起来的是哪一半：向后翻掀 +n 侧，向前翻掀 −n 侧。
    val side = if (shape.direction >= 0) 1f else -1f

    /** 该点沿折痕法向的坐标（材料坐标）。 */
    fun normalOf(x: Float, y: Float): Float = (x - ox) * nx + (y - oy) * ny

    /** 该点对应的扫线参数：0 = 折痕，负 = 沿纸面离折痕 `|x'|` 那么远。 */
    fun scanX(x: Float, y: Float): Float = -side * normalOf(x, y)

    // 页面四角在折痕坐标系里的 x'：最负的那个是「掀起的纸最远端」。
    val cx = floatArrayOf(0f, w, w, 0f)
    val cy = floatArrayOf(0f, 0f, h, h)
    val cornerX = FloatArray(4) { scanX(cx[it], cy[it]) }
    var xMin = cornerX[0]
    for (i in 1 until 4) if (cornerX[i] < xMin) xMin = cornerX[i]
    // 折痕落在页面外、这一侧没有可掀起的材料：这一帧不该由网格画。
    if (xMin > -1f) return emptyMesh()

    // 扫线：0 → −πR 等分（卷起来的筒），−πR → xMin 等分（翻过去后平铺的纸背），
    // 外加四条角点扫线（角点精确落在网格上）。
    // **只保留掀起侧的材料（x' ≤ 0）**：没掀起的那半页由调用方整页画（[drawPage]），
    // 再让网格盖一遍就是同一张纸画两遍（文字重影、明暗与压淡铺到没掀的纸上）。
    // 卷起来之后网格在**屏幕**上会压到露出页上——那是纸自己盖住新页，不是重复绘制。
    //
    // 平铺段那一段扫线**不能省**：只放筒的扫线时，「平铺段」整段只剩「筒末一条 → 页角一条」
    // 那一个大四边形（折痕竖直时正好一条四边形，折痕一斜连这条四边形都没有），
    // 纸背要么被拉成一片空白、要么根本不存在 —— 读者那句「页面反面从来没画过」就是它。
    val n = splits.coerceIn(2, MAX_SPLITS)
    val raw = ArrayList<Float>(n * 2 + 6)
    raw.add(0f)
    for (i in 1 until n) raw.add(-halfTurn * i / (n - 1))
    val flap = (-halfTurn - xMin).coerceAtLeast(0f)
    if (flap > ROW_EPSILON) {
        for (i in 1..n) raw.add(-halfTurn - flap * i / n)
    }
    for (i in 0 until 4) raw.add(cornerX[i])
    raw.sortDescending()
    // 页角那几条扫线**不许被合并掉**：它们与等分扫线只差零点几像素时照样保留。
    // 合并掉就会让两条页边停在离页角几个像素的地方（角附近两条页边夹角很小，差半像素的
    // 扫线能拉出好几像素长的弦）：纸带末端是一条凭空截断的斜边，纸角那块扇形影更因为
    // 「两条页边没在角上碰头」整个不画 —— 读者报「页角正好位于卷筒左侧边时扇形影会消失」。
    val cornerRows = HashSet<Float>(4)
    for (c in cornerX) if (c <= ROW_EPSILON && c >= xMin - ROW_EPSILON) cornerRows.add(c)
    val rows = ArrayList<Float>(raw.size)
    for (x in raw) {
        if (x > ROW_EPSILON) continue
        if (x < xMin - ROW_EPSILON) continue
        if (rows.isNotEmpty() && rows.last() - x < ROW_EPSILON) {
            // 完全重合的照样只留一条；只是「贴得近」的页角扫线要留下。
            if (x !in cornerRows || rows.last() == x) continue
        }
        rows.add(x)
    }

    /** 扫线 `x'` 与页面矩形的交（局部沿折痕的 `λ` 区间，按「下端点、上端点」输出）。 */
    fun spanAt(x: Float): FloatArray? {
        // 页坐标里的直线：P(λ) = 原点 + n·(材料偏移) + t·λ，λ 就是沿折痕的坐标。
        val a = -side * x
        val px = ox + nx * a
        val py = oy + ny * a
        var lo = -1e7f
        var hi = 1e7f
        if (abs(tx) < 1e-6f) {
            if (px < -0.01f || px > w + 0.01f) return null
        } else {
            val a1 = -px / tx
            val b1 = (w - px) / tx
            lo = maxOf(lo, minOf(a1, b1))
            hi = minOf(hi, maxOf(a1, b1))
        }
        if (abs(ty) < 1e-6f) {
            if (py < -0.01f || py > h + 0.01f) return null
        } else {
            val a1 = -py / ty
            val b1 = (h - py) / ty
            lo = maxOf(lo, minOf(a1, b1))
            hi = minOf(hi, maxOf(a1, b1))
        }
        // 退化成一个点也要留：角点扫线与页面只在角上相切，正是它把纸带的收尖钉在页角上。
        // 直接丢掉的话平铺段永远到不了页角，纸带末端是一条凭空截断的直边。
        // 容差按 λ 的量级给：扫线接近竖直或水平时 |tx| / |ty| 很小，上面那几个除法把
        // 几像素的浮点误差放大成零点几的 λ 误差 —— 正切在页角上的那条扫线会被判成
        // 「与页面不相交」整行丢掉，而它正是把两条页边的端点钉在纸角上的那一行：
        // 丢与不丢只差一点点浮点抖动，影带就一帧有一帧没有，看着是闪。
        val tol = 2e-3f * maxOf(1f, abs(lo), abs(hi))
        if (hi - lo < -tol) return null
        if (hi < lo) {
            val mid = (hi + lo) * 0.5f
            return floatArrayOf(mid, mid)
        }
        return floatArrayOf(hi, lo)
    }

    fun curled(x: Float): Float = when {
        x >= 0f -> x
        x >= -halfTurn -> r * sin(x / r)
        else -> -(halfTurn + x)
    }

    fun curlZ(x: Float): Float = when {
        x >= 0f -> 0f
        x >= -halfTurn -> r * (1f - cos(x / r))
        else -> 2f * r
    }

    val pos = ArrayList<Float>(rows.size * 4)
    val uv = ArrayList<Float>(rows.size * 4)
    val arc = ArrayList<Float>(rows.size * 2)
    val rowX = ArrayList<Float>(rows.size)
    for (x in rows) {
        val span = spanAt(x) ?: continue
        // 屏幕上沿法向的偏移：卷曲朝**掀起的那一侧**鼓出去（参考实现里局部 +x 是拖动方向，
        // 卷曲量取正），绕满半圈后的平铺段落回**没掀起**的那一侧。所以屏幕偏移取负号：
        // 向后翻纸卷在折痕右边、平铺段在左边，向前翻整体镜像——同一个式子，靠 `side` 定侧。
        val sOff = -side * curled(x)
        val sx = ox + nx * sOff
        val sy = oy + ny * sOff
        // 材料位置：沿法向离折痕 side×|x'| 那么远。
        val a = -side * x
        val mx = ox + nx * a
        val my = oy + ny * a
        val arcLen = -x
        for (lambda in span) {
            pos.add(sx + tx * lambda)
            pos.add(sy + ty * lambda)
            uv.add(mx + tx * lambda)
            uv.add(my + ty * lambda)
            arc.add(arcLen)
        }
        rowX.add(x)
    }
    if (rowX.size < 2) return emptyMesh()

    // 三角形：相邻两条扫线连成两个三角；按行所属可见面分段，画序 = Recto → Verso → Back。
    val tris = ArrayList<Short>(rowX.size * 6)
    val segSurface = arrayOfNulls<CurlMesh.Surface>(rowX.size - 1)
    for (row in 0 until rowX.size - 1) {
        val mid = (rowX[row] + rowX[row + 1]) * 0.5f
        val surface = when {
            mid >= -halfTurn -> if (curlZ(mid) >= r) CurlMesh.Surface.Verso else CurlMesh.Surface.Recto
            else -> CurlMesh.Surface.Back
        }
        segSurface[row] = surface
        val a0 = (row * 2).toShort()
        val a1 = (row * 2 + 1).toShort()
        val b0 = ((row + 1) * 2).toShort()
        val b1 = ((row + 1) * 2 + 1).toShort()
        tris.add(a0); tris.add(a1); tris.add(b0)
        tris.add(a1); tris.add(b1); tris.add(b0)
    }

    val order = listOf(CurlMesh.Surface.Recto, CurlMesh.Surface.Verso, CurlMesh.Surface.Back)
    val idx = ShortArray(tris.size)
    val bands = ArrayList<CurlMesh.Band>(3)
    var iCount = 0
    for (s in order) {
        val start = iCount
        var firstRow = Int.MAX_VALUE
        var lastRow = -1
        for (row in 0 until rowX.size - 1) {
            if (segSurface[row] != s) continue
            if (row < firstRow) firstRow = row
            if (row + 1 > lastRow) lastRow = row + 1
            val a0 = (row * 2).toShort()
            val a1 = (row * 2 + 1).toShort()
            val b0 = ((row + 1) * 2).toShort()
            val b1 = ((row + 1) * 2 + 1).toShort()
            idx[iCount++] = a0
            idx[iCount++] = a1
            idx[iCount++] = b0
            idx[iCount++] = a1
            idx[iCount++] = b1
            idx[iCount++] = b0
        }
        if (iCount > start) {
            bands.add(
                CurlMesh.Band(
                    surface = s,
                    indexStart = start,
                    indexCount = iCount - start,
                    depth = when (s) {
                        CurlMesh.Surface.Recto -> 0f
                        CurlMesh.Surface.Verso -> 0.5f
                        CurlMesh.Surface.Back -> 1f
                    },
                    firstRow = firstRow,
                    lastRow = lastRow,
                ),
            )
        }
    }

    return CurlMesh(
        positions = pos.toFloatArray(),
        texCoords = uv.toFloatArray(),
        arc = arc.toFloatArray(),
        indices = idx,
        indexCount = iCount,
        bands = bands,
    )
}

/**
 * 一段可见面的外轮廓（页坐标多边形）。
 *
 * 每一行的两个端点在屏幕上是「沿折痕方向的一段直线」，相邻行只沿法向平移，所以整段四边形条带
 * 的外轮廓就是：一侧长边 → 末行线段 → 另一侧长边 → 首行线段。压淡与明暗都照着这个轮廓铺，
 * 形状跟着纸走，不会画到纸外面去。
 */
internal fun CurlMesh.bandOutline(band: CurlMesh.Band): List<Offset> =
    bandOutline(band.firstRow, band.lastRow)

/**
 * 扫线区间 `firstRow..lastRow` 的外轮廓（页坐标多边形）。
 *
 * 相邻两段（如「正面那半圈 + 纸背那半圈」）合成一张纸的轮廓时用这个重载：两段扫线是连续的，
 * 合起来的轮廓就是纸卷在屏幕上的全部面积（给纸卷垫纸色用）。
 */
internal fun CurlMesh.bandOutline(firstRow: Int, lastRow: Int): List<Offset> {
    val pts = ArrayList<Offset>((lastRow - firstRow + 1) * 2)
    fun p(v: Int) = Offset(positions[v * 2], positions[v * 2 + 1])
    for (row in firstRow..lastRow) pts.add(p(row * 2))
    for (row in lastRow downTo firstRow) pts.add(p(row * 2 + 1))
    return pts
}

/** 掀起侧的最远弧长（= `−x'` 最小值），供调用方算卷起来的高度。 */
internal fun CurlMesh.maxArc(): Float = arc.maxOrNull() ?: 0f

/**
 * 整张纸的**两条页边**（`折痕端 → 直角顶点` 折线，页坐标）。
 *
 * 每行的两个端点就是纸的两条页边：`row*2` 是沿折痕 `λ` 较大的那条，`row*2+1` 是另一条。
 * 顺序按扫线走（`x'` 从 0 递减），所以：
 *
 * - **首点**在折痕上，就是页边与屏幕边缘的交点（折痕斜时在上下页边、竖直时在左右页边）；
 * - **末点**是最远的页角，也就是纸背那个直角顶点。
 *
 * 折线**跨全部扫线行**（正面段、纸背段都算），所以它天然分成两截：贴着折痕那截是卷起来的
 * 花瓣弧（曲线），平铺段那截是直线。沿边铺影时「页边是直线就画直线、是弧就画曲线」全靠它，
 * 宽度从末点（直角顶点）均匀线性收到**卷筒俯视图投影线那一行**（弧长 πR/2，见 [firstRowAtArc]）。
 */
internal fun CurlMesh.pageEdges(): List<List<Offset>>? {
    val rows = arc.size / 2
    if (rows < 2) return null
    val bottom = ArrayList<Offset>(rows)
    val top = ArrayList<Offset>(rows)
    for (row in 0 until rows) {
        bottom.add(Offset(positions[row * 4], positions[row * 4 + 1]))
        top.add(Offset(positions[row * 4 + 2], positions[row * 4 + 3]))
    }
    // 折痕正好竖直（或水平）时，最远那条扫线不是纸角而是**整条纸边**：两条纸边各自在自己那一端
    // 收尾，而斜一点点的时候两条带子都是从这一端走到「斜向另一边」的那个纸角 —— 竖直这一帧少走
    // 了那一段，影带就比前后几帧短一整截（一帧有、一帧没有，看着是闪）。把上边那条带子的末点
    // 挪到那个纸角上，跟斜一点点的那一帧画成一样。
    if ((bottom[rows - 1] - top[rows - 1]).getDistance() > ROW_EPSILON) top[rows - 1] = bottom[rows - 1]
    return listOf(bottom, top)
}

/**
 * 弧长 ≥ [arcLen] 的**第一行**（行按弧长升序），没有就返回 `null`。
 *
 * 纸边的接触影「收到哪儿为止」由调用方给弧长：卷筒的俯视图投影线（纸从正面翻成反面的
 * 最外沿）是 `πR/2`，平铺纸背与卷筒的交界是 `πR`。行与行之间最近只差 [ROW_EPSILON]，
 * 所以带同量容差；角点扫线恰好挤掉那一行时也不会跳过去。
 */
internal fun CurlMesh.firstRowAtArc(arcLen: Float): Int? {
    val rows = arc.size / 2
    for (row in 0 until rows) {
        if (arc[row * 2] >= arcLen - ROW_EPSILON) return row
    }
    return null
}

/**
 * 一段扫线落在**页栅格材料范围**里的那截三角带（`drawVertices` 直接吃）。
 *
 * 页栅格只有版心那么大（平板 1585×2400 放在 1840×2800 里），页边距里根本没有像素。
 * 早先用 `clipPath` 把纹理裁在材料形状里，越界采样就不会画出来；但折痕一斜，那条裁剪路径
 * 就不是矩形，抗锯齿裁切会让 Skia 为它开一张全页大小的图层（见 `drawRevealPage` 的 KDoc）。
 *
 * 现在**在几何里裁**：材料坐标沿一条扫线是线性的，所以「落在版心矩形里」只是把这一行的
 * 参数区间裁掉两头、端点线性插值出来 —— 纹理本来就画到版心边为止，画布不用裁。
 *
 * 行按材料坐标单调推移，能落在版心内的行是一段连续区间（两端跨出去的行丢掉），
 * 所以剩下的是一个仍然连续的三角带。
 *
 * @param texLeft/texTop 页栅格在页坐标里的左上角；`texWidth/texHeight` 是页栅格自己的像素尺寸。
 */
internal class CurlTextureStrip(
    /** 每顶点 (x, y)，页坐标。 */
    val positions: FloatArray,
    /** 每顶点 (u, v)，**页栅格自己的像素坐标**（`ImageShader` 的坐标）。 */
    val texCoords: FloatArray,
    val vertexCount: Int,
)

/** 一行（两顶点）里，材料值沿扫线线性变化，落在 `lo..hi` 内的一段参数区间。 */
private fun paramSpan(va: Float, vb: Float, lo: Float, hi: Float): FloatArray? {
    if (abs(vb - va) < 1e-4f) {
        return if (va < lo || va > hi) null else floatArrayOf(0f, 1f)
    }
    val ta = (lo - va) / (vb - va)
    val tb = (hi - va) / (vb - va)
    val s = maxOf(0f, minOf(ta, tb))
    val e = minOf(1f, maxOf(ta, tb))
    return if (e - s <= 1e-4f) null else floatArrayOf(s, e)
}

/**
 * 某一行扫线落在页栅格材料矩形里的参数区间（`0..1` 里的 `[t0, t1]`）；整行都在外面时为 `null`。
 * `u`、`v` 两条边要同时满足。
 */
private fun CurlMesh.rowSpan(
    row: Int,
    texLeft: Float,
    texTop: Float,
    right: Float,
    bottom: Float,
): FloatArray? {
    val a = row * 2
    val b = row * 2 + 1
    val su = paramSpan(texCoords[a * 2], texCoords[b * 2], texLeft, right) ?: return null
    val sv = paramSpan(texCoords[a * 2 + 1], texCoords[b * 2 + 1], texTop, bottom) ?: return null
    val t0 = maxOf(su[0], sv[0])
    val t1 = minOf(su[1], sv[1])
    return if (t1 - t0 <= 1e-4f) null else floatArrayOf(t0, t1)
}

/**
 * 把 `first..last` 这些行各取参数区间 `[t0, t1]` 那一小段，缝成一条三角带。
 *
 * @param spanAt 行 → 参数区间；区间里**任何一行**给不出区间，整条就作废（返回 `null`）。
 */
private fun CurlMesh.stripOf(
    first: Int,
    last: Int,
    spanAt: (Int) -> FloatArray?,
    texLeft: Float,
    texTop: Float,
): CurlTextureStrip? {
    if (last <= first) return null
    val rows = last - first + 1
    val pos = FloatArray(rows * 4)
    val uv = FloatArray(rows * 4)
    var w = 0
    for (row in first..last) {
        val ts = spanAt(row) ?: return null
        val a = row * 2
        val b = row * 2 + 1
        for (k in 0..1) {
            val t = ts[k]
            pos[w] = positions[a * 2] + (positions[b * 2] - positions[a * 2]) * t
            pos[w + 1] = positions[a * 2 + 1] + (positions[b * 2 + 1] - positions[a * 2 + 1]) * t
            uv[w] = texCoords[a * 2] + (texCoords[b * 2] - texCoords[a * 2]) * t - texLeft
            uv[w + 1] = texCoords[a * 2 + 1] + (texCoords[b * 2 + 1] - texCoords[a * 2 + 1]) * t - texTop
            w += 2
        }
    }
    return CurlTextureStrip(positions = pos, texCoords = uv, vertexCount = rows * 2)
}

/** 见 [CurlTextureStrip]。整段都不在版心里时返回 `null`。 */
internal fun CurlMesh.textureStrip(
    band: CurlMesh.Band,
    texLeft: Float,
    texTop: Float,
    texWidth: Float,
    texHeight: Float,
): CurlTextureStrip? {
    val right = texLeft + texWidth
    val bottom = texTop + texHeight
    fun spanAt(row: Int) = rowSpan(row, texLeft, texTop, right, bottom)
    // 材料矩形是凸的、行按材料坐标单调推移：能落在版心内的行是一段连续区间，
    // 断掉之后不会又回来，所以找到首行之后一断就收尾。
    var first = -1
    var last = -1
    for (row in band.firstRow..band.lastRow) {
        if (spanAt(row) == null) {
            if (first >= 0) break
            continue
        }
        if (first < 0) first = row
        last = row
    }
    if (first < 0) return null
    return stripOf(first, last, ::spanAt, texLeft, texTop)
}

/**
 * **页边距**那部分三角带（材料落在页栅格之外），0～2 条：版心一侧一条、另一侧一条。
 *
 * 纸的每一段都要垫一层纸色，好让「版心里没有像素的那一截」（页边距）露出纸色，
 * 而不是透出底下的另一页。但纸色**只能垫在版心之外**：页边距本来就是纸，版心那一块由纹理
 * 自己铺满；两处都铺的话，纹理的抗锯齿边就压在纸色上 —— 交界那一列像素拿到「半列纸色 +
 * 半列纹理」，比两边都亮。读者报的「卷筒与纸正面交界处有一条多余的接缝线」正是它，
 * 反面同一条（段与段共用的那行扫线也一样）。
 *
 * 行按材料坐标单调推移，所以两截各自都是连续的一段：版心之前的那一截一定在段首，
 * 版心之后的那一截一定在段尾，两截都可能不存在（整段都在版心里 ⇒ 纸色一条都不用垫）。
 */
internal fun CurlMesh.marginStrips(
    band: CurlMesh.Band,
    texLeft: Float,
    texTop: Float,
    texWidth: Float,
    texHeight: Float,
): List<CurlTextureStrip> {
    val right = texLeft + texWidth
    val bottom = texTop + texHeight
    fun inside(row: Int) = rowSpan(row, texLeft, texTop, right, bottom)
    /** 版心之前那一截：`[0, t0]`（整行都在版心外就是整行）；整行都在版心里时为空。 */
    fun head(row: Int): FloatArray? {
        val s = inside(row) ?: return floatArrayOf(0f, 1f)
        return if (s[0] > 1e-4f) floatArrayOf(0f, s[0]) else null
    }
    /**
     * 版心之后那一截：`[t1, 1]`。整行都在版心里时退化成零宽（`[1, 1]`，画不出三角形）；
     * 整行都在版心外时也退化 —— 那一整行由 [head] 全包，这里再来一条就画两遍。
     */
    fun tail(row: Int): FloatArray? {
        val s = inside(row) ?: return floatArrayOf(1f, 1f)
        return floatArrayOf(s[1], 1f)
    }
    var headEnd = -1
    var tailStart = -1
    for (row in band.firstRow..band.lastRow) {
        if (head(row) != null) headEnd = row
        val s = inside(row)
        if (tailStart < 0 && s != null && s[1] < 1f - 1e-4f) tailStart = row
    }
    val out = ArrayList<CurlTextureStrip>(2)
    if (headEnd >= band.firstRow) {
        // `head` 在这里必然连续（整行落在版心里的行之后不会再出现版心之前那一截）。
        stripOf(band.firstRow, headEnd, ::head, texLeft, texTop)?.let { out.add(it) }
    }
    if (tailStart >= 0) {
        stripOf(tailStart, band.lastRow, ::tail, texLeft, texTop)?.let { out.add(it) }
    }
    return out
}

/** 三角带的**外轮廓**（页坐标多边形）：先沿一侧页边走到底，再沿另一侧走回来。纸色打底用。 */
internal fun CurlTextureStrip.outlinePath(): List<Offset> {
    val rows = vertexCount / 2
    val pts = ArrayList<Offset>(rows * 2)
    for (i in 0 until rows) pts.add(Offset(positions[i * 4], positions[i * 4 + 1]))
    for (i in rows - 1 downTo 0) pts.add(Offset(positions[i * 4 + 2], positions[i * 4 + 3]))
    return pts
}

private fun emptyMesh() = CurlMesh(
    positions = FloatArray(0),
    texCoords = FloatArray(0),
    arc = FloatArray(0),
    indices = ShortArray(0),
    indexCount = 0,
    bands = emptyList(),
)
