package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import kotlin.math.hypot

/**
 * 一帧折页几何（**用户层纯函数**，页坐标系：原点左上、y 向下、单位 px）。
 *
 * 逐行对位旧版 `orilum.bak` 的 `CurlView.drawBody`：
 *
 * ```
 *   foldX = 向后翻 w·(1-p) / 向前翻 w·p          （折痕扫过整页）
 *   tiltW = 0.9 · 页宽                            （倾斜外扩的固定幅度）
 *   lean  = ±倾斜量                               （向前翻取反，否则折痕与手势平行）
 *   topC  = (foldX − lean·tiltW, 0)               （折痕与页上边的交点）
 *   botC  = (foldX + lean·tiltW, 页高)            （折痕与页下边的交点）
 * ```
 *
 * 两个端点都夹在 `[0, 2·页宽]`：折痕可以整体滑到右缘之外（翻页收尾时卷曲区滑出去），
 * 但左缘（书脊）不越过 0。
 *
 * ## 末段扶正（修「松手后临近结束忽然跳变」）
 *
 * 斜折痕贴着屏幕边缘收尾时，屏幕上会留下一块「还没翻过去的三角」和折痕旁的一道影；
 * 动画一结束、卷曲层一撤，这两样在一帧内消失 ⇒ 观感就是「快完成了忽然跳一下」。
 *
 * 旧版的做法（注释原文「否则覆盖层撤掉时停一下再跳」）是在**结算动画的头尾 10%**里
 * 把倾斜量线性淡到 0（`tilt fade`），折痕因此扶成竖直、正好贴边。折痕旁的影同时按
 * 同一个系数淡出。手指还按在屏幕上时**不淡**——那是读者自己在控制形状。
 *
 * 与旧版的唯一差别：旧版对「收在右缘」的那两端是把进度**冲过 1**（`1 + 0.9·|tilt|`）
 * 让整块滑出屏幕；我们的进度归一在 `[0, 1]`（与滑动层共用），所以两端都改用扶正来
 * 收干净——末帧折痕竖直贴边、卷曲区面积为零，画面恰好等于目标页。
 */
internal data class CurlShape(
    val pageW: Float,
    val pageH: Float,
    /** 折痕与页上边的交点 x。 */
    val topX: Float,
    /** 折痕与页下边的交点 x。 */
    val bottomX: Float,
    /** 折痕位置（两端点中点的 x，仅日志/测试用）。 */
    val foldX: Float,
    /** 折痕倾斜量（已按方向镜像、已做末段扶正）。 */
    val lean: Float,
    /** 阴影随进度的淡出系数 0..1（见文件头「末段扶正」）。 */
    val shadowFade: Float,
    /** 归一化进度（0 = 没翻，1 = 翻完；拖动时可略越界）。 */
    val progress: Float,
    /** +1 = 向后翻（下一页），-1 = 向前翻（上一页）。 */
    val direction: Int,
)

/** 倾斜外扩的固定幅度（0.9 页宽），与旧版 `tiltW = w * 0.9f` 同一个数。 */
internal const val CURL_TILT_WIDTH: Float = 0.9f

/** 阴影带宽（5% 页宽），与旧版 `SHADOW_BAND` 同一个数。 */
internal const val CURL_SHADOW_BAND: Float = 0.05f

/** 结算动画头尾这一段（占整段的比例）里扶正折痕、淡出阴影，见文件头。 */
internal const val CURL_TAIL_FADE_SPAN: Float = 0.1f

/** 进度小于它就算「还没翻」：只画读者正在看的那一页（旧版同口径 0.011）。 */
internal const val CURL_START_EPSILON: Float = 0.011f

/**
 * 进度大于 `1 − 它` 就算「已经翻完」：整帧只剩目标页。
 *
 * 收尾时纸卷半径收敛到 0，网格本来就会自己消失；留这一段是为了「撤层那一帧不闪」：
 * 最后一帧与静止时的目标页逐像素一致，动画结束撤掉翻页层时画面不跳。
 */
internal const val CURL_END_EPSILON: Float = 0.002f

/**
 * 由进度算一帧折痕几何。
 *
 * @param direction +1 向后翻 / -1 向前翻（来自 [orilumn.reader.ui.reader.FlipSession.direction]）
 * @param tilt 手指位移归一化后的上下分量（-1..1），0 = 竖折痕；旧版 `CurlView.frozenTilt`
 * @param settling 是否在结算动画里（不是手指跟手阶段）。只有结算才做末段扶正：
 *   手指还按着的时候形状必须听手指的，不能自己扶正。
 */
internal fun curlShape(
    pageW: Float,
    pageH: Float,
    progress: Float,
    direction: Int,
    tilt: Float = 0f,
    settling: Boolean = false,
): CurlShape {
    val w = pageW.coerceAtLeast(1f)
    val h = pageH.coerceAtLeast(1f)
    val forward = direction >= 0
    val p = progress.coerceIn(-0.9f, 1.9f)

    // 折痕位置：向后翻从右缘扫到左缘，向前翻从左缘扫到右缘（旧版同款）。
    val foldX = if (forward) w * (1f - p) else w * p

    // 倾斜方向与手势方向垂直：向后翻正倾斜 ⇒ 上端左移、下端右移；向前翻取反。
    val lean0 = (if (forward) tilt else -tilt).coerceIn(-1f, 1f)

    // 结算动画头尾 10%：倾斜与阴影一起淡出（见文件头「末段扶正」）。
    val fade = if (settling) tailFade(p) else 1f
    val lean = lean0 * fade

    val tiltW = w * CURL_TILT_WIDTH
    fun clampX(x: Float) = x.coerceIn(0f, w * 2f)
    val topX = clampX(foldX - lean * tiltW)
    val bottomX = clampX(foldX + lean * tiltW)

    return CurlShape(
        pageW = w,
        pageH = h,
        topX = topX,
        bottomX = bottomX,
        foldX = foldX,
        lean = lean,
        shadowFade = fade,
        progress = p,
        direction = direction,
    )
}

/** 结算动画头尾的淡出系数：中间恒 1，两端线性到 0。 */
internal fun tailFade(progress: Float): Float = when {
    progress > 1f - CURL_TAIL_FADE_SPAN -> ((1f - progress) / CURL_TAIL_FADE_SPAN).coerceIn(0f, 1f)
    progress < CURL_TAIL_FADE_SPAN -> (progress / CURL_TAIL_FADE_SPAN).coerceIn(0f, 1f)
    else -> 1f
}

/**
 * 沿直线 `A→B` 的镜射（纸背 = 正面沿折痕翻过来的那一面）。
 *
 * 与旧版 `CurlView.reflectMatrix` 同一个闭式解：设直线单位方向 `u`、直线上一点 `A`，
 * 镜射矩阵 `M = 2uuᵀ − I`，平移项 `2A − 2(A·u)u`。
 *
 * 这里只算标量，负责纸背多边形（[map]）；**画布变换**不从这里取矩阵——Compose 的
 * `Matrix` 数组在「点映射」与「画布连接」两处的行列约定不一致，踩一次就会整块画到别处。
 * 画布那边改成「平移 → 旋转 → 上下镜像 → 转回来 → 平移回去」的分解式，见
 * [orilumn.reader.ui.reader.curl.drawCurlFrame]。
 */
internal class Reflection(
    val m00: Float,
    val m01: Float,
    val m02: Float,
    val m10: Float,
    val m11: Float,
    val m12: Float,
) {
    fun mapX(x: Float, y: Float): Float = m00 * x + m01 * y + m02
    fun mapY(x: Float, y: Float): Float = m10 * x + m11 * y + m12

    fun map(p: Offset): Offset = Offset(mapX(p.x, p.y), mapY(p.x, p.y))

    companion object {
        fun about(a: Offset, b: Offset): Reflection {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy).coerceAtLeast(1e-3f)
            val ux = dx / len
            val uy = dy / len
            val c = a.x * ux + a.y * uy
            return Reflection(
                m00 = 2f * ux * ux - 1f,
                m01 = 2f * ux * uy,
                m02 = 2f * a.x - 2f * ux * c,
                m10 = 2f * ux * uy,
                m11 = 2f * uy * uy - 1f,
                m12 = 2f * a.y - 2f * uy * c,
            )
        }
    }
}

/**
 * 两条直线（各自过一对点）的交点；平行时返回 null。
 * 与旧版 `CurlView.lineCross` 同式，用来让折痕在右缘上的截点落准。
 */
internal fun lineCross(p1: Offset, p2: Offset, p3: Offset, p4: Offset): Offset? {
    val d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
    if (kotlin.math.abs(d) < 1e-3f) return null
    val a = p1.x * p2.y - p1.y * p2.x
    val b = p3.x * p4.y - p3.y * p4.x
    return Offset(
        (a * (p3.x - p4.x) - (p1.x - p2.x) * b) / d,
        (a * (p3.y - p4.y) - (p1.y - p2.y) * b) / d,
    )
}

/**
 * 露出区多边形的四个角（折痕**没掀起**那一侧的那一块：向后翻在右、向前翻在左）。
 *
 * 依次是：折痕上端 → 露出侧上角 → 露出侧下角 → 折痕下端。
 * 某端已经滑到页边之外（收尾时整块滑出去）时，改用「折痕与页边的交点」两次，
 * 多边形退化成零面积——照旧画一遍不会出错，只是什么也画不出来。
 */
internal fun revealCorners(shape: CurlShape): List<Offset> {
    val w = shape.pageW
    val h = shape.pageH
    val topC = Offset(shape.topX, 0f)
    val botC = Offset(shape.bottomX, h)
    // 露出页永远在折痕「还没掀起来」的那一侧。
    val onLeft = shape.direction < 0
    val edgeX = if (onLeft) 0f else w
    fun inside(x: Float): Boolean = if (onLeft) x > 0f else x < w
    val pts = ArrayList<Offset>(4)
    fun endSide() {
        val p = lineCross(topC, botC, Offset(edgeX, 0f), Offset(edgeX, h))
        if (p != null) {
            pts.add(p)
            pts.add(p)
        }
    }
    if (inside(topC.x)) {
        pts.add(topC)
        pts.add(Offset(edgeX, 0f))
    } else {
        endSide()
    }
    if (inside(botC.x)) {
        pts.add(Offset(edgeX, h))
        pts.add(botC)
    } else {
        endSide()
    }
    return pts
}

/**
 * 卷曲半径：**折痕到「还没掀起来那一侧页边」的距离**，封顶 [CURL3D_RADIUS_RATIO]（5% 页宽）。
 *
 * 式子的形状取自参考实现 `NewCurl3D.updateCurlPos` 单页分支
 * （`dMax = clamp(pointer.x − pageRect.left, 0, dWidth)`，手指就是折痕）：纸卷有多粗由**剩下的
 * 那部分纸有多宽**决定，而不是由翻了多少决定；上限为什么取 5% 而不是参考实现的 ≈0.108 页宽，
 * 见 [CURL3D_RADIUS_RATIO]。
 *
 * 两种口径的差别是决定性的：
 * - 按进度算（旧口径）：进度 0.2 时半径只有 0.2 页，而掀起部分的长度也是 0.2 页，
 *   纸一离开折痕就绕满 1 弧度，于是「翻了两成」看起来几乎没动；
 * - 按剩下的纸算（本式）：前半程半径恒定（细筒往前滚，新页跟着手的节奏露出来），
 *   到最后剩下的纸比上限还窄时，半径跟着收细、纸卷自然消失。
 */
internal fun curlRadius(shape: CurlShape, radiusRatio: Float = CURL3D_RADIUS_RATIO): Float {
    val w = shape.pageW.coerceAtLeast(1f)
    val unturned = if (shape.direction >= 0) shape.foldX else w - shape.foldX
    return unturned.coerceIn(w * CURL3D_MIN_RADIUS_RATIO, w * radiusRatio.coerceIn(0.002f, 1.5f))
}
