package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 卷曲的**几何**（用户层纯函数，页坐标系，单位 px）。
 *
 * ## 与折页的关系
 *
 * 折页是「曲率 0」的特例：纸绕折痕刚性翻过去，屏幕上只有三块多边形。卷曲是同一张纸
 * 绕过折痕的**圆柱**，屏幕上的点由「纸面到折痕的弧长 s」唯一决定：
 *
 * ```
 *   0 ≤ s ≤ πR :  x = 折痕 − R·sinθ      （先越过折痕压到正面页那一侧）
 *                 z = R·(1 − cosθ)        （离纸面的高度，0 → 2R）；θ = s / R
 *   s > πR     :  x = 折痕 − (s − πR)     （绕满半圈后平铺回来，z = 2R）
 * ```
 *
 * 屏幕位置只由 s 决定 ⇒ 每条等 s 的扫描线在屏幕上仍是直线（沿折痕方向平移），
 * 三角形网格是一张「行 × 2」的规则网格：行 = 扫描线，两个顶点 = 该扫描线与页矩形的交点。
 *
 * ## 三段可见面（画序即 z 序）
 *
 * | 段 | s 范围 | 看得见的那一面 | 内容 |
 * | --- | --- | --- | --- |
 * | [Surface.Recto] | 0..πR/2 | 纸正面 | 页栅格原样 |
 * | [Surface.Verso] | πR/2..πR | 纸背面（鼓出来的那一半） | 同一张图，压淡 |
 * | [Surface.Back] | πR..sMax | 绕回来平铺在正面页上方 | 同一张图，压淡 |
 *
 * 分界不是美术选择：纸往上卷，正面（原本朝上）成了卷的**内**侧，背面成了外侧；
 * 观察者在正上方，法线朝观察者的一面才可见，而法线 z 分量在 θ = π/2 变号。
 *
 * ## 纸背内容取「材料自己的位置」
 *
 * 纸背在屏幕 x 处显示的是**材料**在弧长 s 处的正面内容（同一张纸的背面透过来），
 * 镜像由几何自然产生（屏幕 x 随 s 反向）。折页那边要显式镜射，是因为折页的纸背是
 * 「露出区的镜像」而不是材料本身；卷曲一旦有了法线朝向，就不需要第二套变换。
 */
internal class CurlMesh(
    /** 每顶点 (x, y)，页坐标。 */
    val positions: FloatArray,
    /** 每顶点 (u, v)，**材料坐标**（页坐标，未折叠）：着色器按它采样纹理。 */
    val texCoords: FloatArray,
    /** 每顶点「到折痕的弧长 s」（px）：着色器按它算圆柱法线。 */
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
    )

    internal enum class Surface {
        /** 正面（θ < π/2）：页栅格原样。 */
        Recto,

        /** 背面（π/2 ≤ θ ≤ π）：鼓出来的那一半，压淡。 */
        Verso,

        /** 绕满半圈后平铺回来的那一段，压淡。 */
        Back,
    }

    val vertexCount: Int get() = arc.size
}

/** 弧段行数上限（Short 索引的安全上限内）。 */
private const val MAX_SPLITS = 96

/** 纸背内容压到的透明度（与折页同一口径，旧版 `alpha = 100/255`）。 */
internal const val CURL_BACK_ALPHA: Float = 0.39f

/**
 * 由一帧折痕几何 + 卷曲半径生成网格。
 *
 * @param radius 卷曲半径（px）。`πR` 是「绕满半圈」所需弧长：半径越大卷得越缓、
 *   纸背平铺段越晚出现。调用方按页宽给（[CURL3D_RADIUS_RATIO]）。
 * @param splits 弧段行数（越大越圆；三角形数 ≈ 行数 × 2）。
 */
internal fun buildCurlMesh(
    shape: CurlShape,
    radius: Float,
    splits: Int = 40,
    eyeZ: Float = 0f,
): CurlMesh {
    val w = shape.pageW
    val h = shape.pageH
    val r = radius.coerceAtLeast(1f)

    // 折痕：过 (topX,0) 与 (bottomX,h) 的直线；u 沿折痕向下，n 指向被掀起的一侧（右）。
    val t0 = Offset(shape.topX, 0f)
    val t1 = Offset(shape.bottomX, h)
    val foldLen = hypot(t1.x - t0.x, t1.y - t0.y).coerceAtLeast(1e-3f)
    val ux = (t1.x - t0.x) / foldLen
    val uy = (t1.y - t0.y) / foldLen
    val nx = uy
    val ny = -ux

    fun arcOf(p: Offset): Float = (p.x - t0.x) * nx + (p.y - t0.y) * ny

    var sMax = 0f
    for (c in arrayOf(Offset(0f, 0f), Offset(w, 0f), Offset(w, h), Offset(0f, h))) {
        val s = arcOf(c)
        if (s > sMax) sMax = s
    }
    if (sMax <= 0.5f) return emptyMesh()

    val halfTurn = (PI.toFloat() * r).coerceAtLeast(1e-3f)
    val quarter = halfTurn / 2f
    val arcEnd = minOf(sMax, halfTurn)

    // 行：0 → arcEnd 均分；必须精确落在 πR/2（可见面换边）与 πR（开始平铺）上。
    val rows = ArrayList<Float>(splits + 10)
    val n = splits.coerceIn(2, MAX_SPLITS)
    fun addArc(from: Float, to: Float, count: Int) {
        if (to - from < 1e-3f) return
        for (i in (if (rows.isEmpty() || from > rows.last() + 1e-3f) 0 else 1)..count) {
            rows.add(from + (to - from) * i / count)
        }
    }
    // 按 sMax（纸边最远弧长）决定铺到哪一段，不能用 arcEnd —— arcEnd 已被
    // min(sMax, πR) 截断，拿它判断就永远走不到平铺段（曾经漏掉整块纸背）。
    if (sMax <= quarter) {
        addArc(0f, sMax, n)
    } else if (sMax <= halfTurn) {
        addArc(0f, quarter, (n / 2).coerceAtLeast(2))
        addArc(quarter, sMax, (n / 2).coerceAtLeast(2))
    } else {
        addArc(0f, quarter, (n / 3).coerceAtLeast(2))
        addArc(quarter, halfTurn, (n / 3).coerceAtLeast(2))
        // 平铺段是直线，行间距只影响顶点数，不影响形状。
        addArc(halfTurn, sMax, 8)
    }

    val pos = ArrayList<Float>(rows.size * 4)
    val uv = ArrayList<Float>(rows.size * 4)
    val arc = ArrayList<Float>(rows.size * 2)
    val rowS = ArrayList<Float>(rows.size)
    var vCount = 0

    /** 直线 P(λ) = 折痕点 + n·s + u·λ 与页矩形四条边的交点，返回 λ 的 min/max。 */
    fun edgeSpan(s: Float): FloatArray? {
        val px = t0.x + nx * s
        val py = t0.y + ny * s
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        var hits = 0
        if (ux <= -1e-4f || ux >= 1e-4f) {
            for (ex in floatArrayOf(0f, w)) {
                val lambda = (ex - px) / ux
                val y = py + uy * lambda
                if (y >= -0.01f && y <= h + 0.01f) {
                    if (lambda < lo) lo = lambda
                    if (lambda > hi) hi = lambda
                    hits++
                }
            }
        }
        if (uy <= -1e-4f || uy >= 1e-4f) {
            for (ey in floatArrayOf(0f, h)) {
                val lambda = (ey - py) / uy
                val x = px + ux * lambda
                if (x >= -0.01f && x <= w + 0.01f) {
                    if (lambda < lo) lo = lambda
                    if (lambda > hi) hi = lambda
                    hits++
                }
            }
        }
        return if (hits >= 2 && hi - lo > 0.5f) floatArrayOf(lo, hi) else null
    }

    val cx = w / 2f
    val cy = h / 2f
    val emitted = ArrayList<Float>(rows.size)
    for (s in rows) {
        val span = edgeSpan(s) ?: continue
        // 屏幕位置：沿折痕方向不变，法向按圆柱/平铺映射。
        // 纸卷必须落在**掀起那一侧**（法向 n 方向），纸背落回另一侧 —— 这正是
        // moon+ / duokan 的做法（`CurlMesh.java:341` 的 `R*sin(θ)` 与
        // `-（x+πR)` 分处折痕两侧）。两侧都放在同一侧时，平铺段（z=2R）会盖住
        // 纸卷（z≤2R），卷永远看不见 —— 之前就是这么错的。
        val offset = if (s <= halfTurn) r * sin(s / r) else -(s - halfTurn)
        val z = if (s <= halfTurn) r * (1f - cos(s / r)) else 2f * r
        // 透视：观察者在纸面正上方 eyeZ 处。z 越大离观察者越近、投影越大——
        // 正交投影下卷起来那一段只会「横向压缩」，读不出立体感，这是唯一补得回来的地方。
        val k = if (eyeZ > 1f) eyeZ / (eyeZ - z).coerceAtLeast(1f) else 1f
        val bx = t0.x + nx * offset
        val by = t0.y + ny * offset
        // 平铺段的纹理坐标按「镜射回折痕另一侧」取（与折页同一口径）：屏幕 x 处显示的是
        // 折痕另一侧同样距离那条材料 —— 直接用材料自身的弧长会取到页右边缘的空白页边，
        // 整条纸背看起来就是一片空纸。
        val uvArc = if (s <= halfTurn) s else (s - halfTurn)
        for (lambda in span) {
            pos.add(cx + (bx + ux * lambda - cx) * k)
            pos.add(cy + (by + uy * lambda - cy) * k)
            uv.add(t0.x + nx * uvArc + ux * lambda)
            uv.add(t0.y + ny * uvArc + uy * lambda)
            arc.add(s)
            vCount++
        }
        rowS.add(s)
    }
    if (rowS.size < 2) return emptyMesh()

    // 三角形：相邻两行连成两个三角；按行所属可见面分段，画序 = Recto → Verso → Back。
    data class Tri(val surface: CurlMesh.Surface, val a: Short, val b: Short, val c: Short)

    val tris = ArrayList<Tri>((rowS.size - 1) * 2)
    for (row in 0 until rowS.size - 1) {
        val mid = (rowS[row] + rowS[row + 1]) / 2f
        val surface = when {
            mid < quarter -> CurlMesh.Surface.Recto
            mid <= halfTurn -> CurlMesh.Surface.Verso
            else -> CurlMesh.Surface.Back
        }
        val a0 = (row * 2).toShort()
        val a1 = (row * 2 + 1).toShort()
        val b0 = ((row + 1) * 2).toShort()
        val b1 = ((row + 1) * 2 + 1).toShort()
        tris.add(Tri(surface, a0, a1, b0))
        tris.add(Tri(surface, a1, b1, b0))
    }

    val order = listOf(CurlMesh.Surface.Recto, CurlMesh.Surface.Verso, CurlMesh.Surface.Back)
    val idx = ShortArray(tris.size * 3)
    val bands = ArrayList<CurlMesh.Band>(3)
    var iCount = 0
    for (s in order) {
        val start = iCount
        for (t in tris) {
            if (t.surface != s) continue
            idx[iCount++] = t.a
            idx[iCount++] = t.b
            idx[iCount++] = t.c
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

private fun emptyMesh() = CurlMesh(
    positions = FloatArray(0),
    texCoords = FloatArray(0),
    arc = FloatArray(0),
    indices = ShortArray(0),
    indexCount = 0,
    bands = emptyList(),
)
