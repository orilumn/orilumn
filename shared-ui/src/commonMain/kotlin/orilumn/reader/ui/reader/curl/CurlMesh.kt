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

/** 纸背内容压到的透明度（参考实现 `BACKFACE_ALPHA = 0.9`，即纸色盖上去 10%）。 */
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

    // 扫线：0 → −πR 等分，外加四条角点扫线（角点精确落在网格上）。
    // **只保留掀起侧的材料（x' ≤ 0）**：没掀起的那半页由调用方整页画（[drawPage]），
    // 再让网格盖一遍就是同一张纸画两遍（文字重影、明暗与压淡铺到没掀的纸上）。
    // 卷起来之后网格在**屏幕**上会压到露出页上——那是纸自己盖住新页，不是重复绘制。
    val n = splits.coerceIn(2, MAX_SPLITS)
    val raw = ArrayList<Float>(n + 6)
    raw.add(0f)
    for (i in 1 until n) raw.add(-halfTurn * i / (n - 1))
    for (i in 0 until 4) raw.add(cornerX[i])
    raw.sortDescending()
    val rows = ArrayList<Float>(raw.size)
    for (x in raw) {
        if (x > ROW_EPSILON) continue
        if (x < xMin - ROW_EPSILON) continue
        if (rows.isNotEmpty() && rows.last() - x < ROW_EPSILON) continue
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
        if (hi - lo <= 0.01f) return null
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
internal fun CurlMesh.bandOutline(band: CurlMesh.Band): List<Offset> {
    val pts = ArrayList<Offset>((band.lastRow - band.firstRow + 1) * 2)
    fun p(v: Int) = Offset(positions[v * 2], positions[v * 2 + 1])
    for (row in band.firstRow..band.lastRow) pts.add(p(row * 2))
    for (row in band.lastRow downTo band.firstRow) pts.add(p(row * 2 + 1))
    return pts
}

/** 掀起侧的最远弧长（= `−x'` 最小值），供调用方算卷起来的高度。 */
internal fun CurlMesh.maxArc(): Float = arc.maxOrNull() ?: 0f

private fun emptyMesh() = CurlMesh(
    positions = FloatArray(0),
    texCoords = FloatArray(0),
    arc = FloatArray(0),
    indices = ShortArray(0),
    indexCount = 0,
    bands = emptyList(),
)
