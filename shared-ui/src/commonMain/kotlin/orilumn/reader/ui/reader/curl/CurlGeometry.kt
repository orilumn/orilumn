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
 * 两个端点都夹在 `[−R, 2·页宽]`（R = 卷曲半径，见 [CURL3D_RADIUS_RATIO]）：折痕可以整体
 * 滑到右缘之外，也可以越过书脊一个半径——后者是纸卷的**退场余量**，见下。
 *
 * ## 纸卷退场（修「纸张滚到书脊就卷不成筒」）
 *
 * 折痕贴到**纸少的那一侧**页边时（下一页收尾、上一页起手，都是 `折痕 → 书脊`），筒身还有
 * 整整一个半径宽，纸也还有一整页那么多；此时把半径按「折痕到页边的距离」收细，等于宣布
 * 「纸张滚到书脊就没地方卷了」，卷筒在最后一成进度里被生生压扁。
 *
 * 纸卷不该被压扁，它该**滚出页边**：最后 `R/页宽` 的行程里折痕多走一个半径、越过书脊，
 * 纸卷整根从左边出去（上一页起手则是整根从左边进来），末帧正好一根筒都不剩。
 * 这一段的折痕因此走得比手指快一倍——「纸卷滚出去」本来就是把最后一小段纸卷完，
 * 与末段扶正（[CURL_TAIL_FADE_SPAN]）取同一个窗口，收尾时折痕已扶成竖直、直着出页。
 *
 * ## 头尾扶正（修「进度两端折角一帧消失」与「松手后临近结束忽然跳变」）
 *
 * 斜折痕贴在页边时，屏幕上会有一块「已经掀过去的大三角」和折痕旁的一道影。进度两端这两块
 * 都没有理由继续存在：进度 0 是「一页都没掀」，进度 1 是「整页都翻完」。但几何本身并不在
 * 两端自然收成零——折痕一斜，贴着页边也能圈出一大块折角。
 *
 * 所以进度**头尾各 10%** 里把倾斜量线性淡到 0（`tilt fade`，旧版注释原文「否则覆盖层
 * 撤掉时停一下再跳」）：折痕被扶成竖直、正好贴边，折角面积连续收到零；折痕旁的影按同一
 * 系数淡出。**跟手与结算同口径**——只压倾斜，不压读者自己给的进度。早先只在结算里做，
 * 于是手指退回起手点（`dx` 归零）时折角会在一帧里整块消失，读者：「像那儿有一条屏幕边线」。
 *
 * 与旧版的唯一差别：旧版对「收在右缘」的那两端是把进度**冲过 1**（`1 + 0.9·|tilt|`）
 * 让整块滑出屏幕；我们的进度归一在 `[0, 1]`（与滑动层共用），右缘那一端改用扶正来收干净
 * ——末帧折痕竖直贴边、卷曲区面积为零；书脊那一端另有纸卷退场（见上），末帧连筒一起出页。
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
    /** 折痕倾斜量（已按方向镜像、已做头尾扶正）。 */
    val lean: Float,
    /** 阴影随进度的淡出系数 0..1（见文件头「头尾扶正」）。 */
    val shadowFade: Float,
    /** 归一化进度（0 = 没翻，1 = 翻完；拖动时可略越界）。 */
    val progress: Float,
    /**
     * 几何朝哪一侧卷：+1 = 卷右半（没掀起的那半在左，书脊在左），-1 = 左右镜像。
     *
     * 翻页层**不按翻页方向取 -1**：上一页也走 +1 的几何（见 [curlFrame]），-1 只留给
     * 低层几何函数自身与镜像相关的测试。
     */
    val direction: Int,
)

/** 倾斜外扩的固定幅度（0.9 页宽），与旧版 `tiltW = w * 0.9f` 同一个数。 */
internal const val CURL_TILT_WIDTH: Float = 0.9f

/**
 * 折痕倾斜的平滑系数：每个指针事件朝目标值走这么多（见 `FlipController.updateCurlTilt`）。
 *
 * 倾斜一个单位 = `0.9 × 页宽` 的折痕端点位移，手指抖动必须压下去；但也不能压太狠，
 * 否则倾斜跟不上手。0.22 在 60–120Hz 的采样下是 30–60ms 的时间常数。
 */
internal const val CURL_TILT_SMOOTHING: Float = 0.22f

/**
 * 判方向的**最小位移**（px）：`dy / hypot(dx, dy)` 在位移接近 0 时会被噪声定死，
 * 起手前几十像素内不更新倾斜（保持 0，折痕竖直），走出这段再跟手指。
 */
internal const val CURL_TILT_MIN_DISPLACEMENT: Float = 24f

/** 阴影带宽（5% 页宽），与旧版 `SHADOW_BAND` 同一个数。 */
internal const val CURL_SHADOW_BAND: Float = 0.05f

/** 进度头尾这一段（占整段的比例）里扶正折痕、淡出阴影，见文件头。 */
internal const val CURL_TAIL_FADE_SPAN: Float = 0.1f

/**
 * 由进度算一帧折痕几何。
 *
 * @param direction +1 向后翻 / -1 向前翻（来自 [orilumn.reader.ui.reader.FlipSession.direction]）
 * @param tilt 手指位移归一化后的上下分量（-1..1），0 = 竖折痕；旧版 `CurlView.frozenTilt`
 */
internal fun curlShape(
    pageW: Float,
    pageH: Float,
    progress: Float,
    direction: Int,
    tilt: Float = 0f,
): CurlShape {
    val w = pageW.coerceAtLeast(1f)
    val h = pageH.coerceAtLeast(1f)
    val forward = direction >= 0
    val p = progress.coerceIn(-0.9f, 1.9f)

    // 折痕位置：向后翻从右缘扫到左缘，向前翻从左缘扫到右缘（旧版同款）。
    val base = if (forward) w * (1f - p) else w * p

    // 纸卷退场（见文件头）：折痕贴到「纸少的那一侧」页边后，再用一个半径的行程把筒滚出页边。
    // 这一段只有 R/w 那么长，所以折痕在这里走得比手指快一倍；不这么做，筒要么被压扁
    // （按剩余纸宽收细），要么在末帧整根消失（撤层时跳一下）。
    val exit = w * CURL3D_RADIUS_RATIO
    val foldX = if (forward) {
        if (base < exit) 2f * base - exit else base
    } else {
        if (w - base < exit) 2f * base - (w - exit) else base
    }

    // 倾斜方向与手势方向垂直：向后翻正倾斜 ⇒ 上端左移、下端右移；向前翻取反。
    val lean0 = (if (forward) tilt else -tilt).coerceIn(-1f, 1f)

    // 进度头尾 10%：倾斜与阴影一起淡出（见文件头「头尾扶正」）。
    val fade = tailFade(p)
    val lean = lean0 * fade

    val tiltW = w * CURL_TILT_WIDTH
    // 端点比折痕多留一个半径的余量：退场时折痕整根移出页边，端点要跟着出去；夹回 0 会把
    // 折痕的角度掰回来，纸卷跟着变形。
    fun clampX(x: Float) = x.coerceIn(-exit, w * 2f)
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

/**
 * 一帧翻页在屏幕上的**最终翻法**：书脊恒在左边。
 *
 * [CurlShape.direction] 只描述几何朝哪一侧卷，不描述翻页方向。左装书无论翻下一页还是
 * 上一页，纸都挂在左边的书脊上：没掀起的那一半永远在左、纸卷朝右鼓、露出页在右。
 *
 * - **下一页**：当前页是卷起来的那张，目标页在右边露出来；
 * - **上一页**：上一页（目标页）是卷回来的那张，当前页留在右边被它盖住。
 *
 * 上一页不是把下一页的几何左右镜像（那样书脊会跑到右边），而是**同一套几何倒着走**：
 * 进度取 `1-p`，折痕因此仍在 `w·p`；倾斜取反抵消 `direction` 的镜像，折痕斜率与
 * 原来的上一页手势一致。再由 [turningIsTarget] 把正面/露出页两张图换位。
 */
internal data class CurlFrame(
    val shape: CurlShape,
    /** 被翻的那张纸是不是目标页（上一页）。 */
    val turningIsTarget: Boolean,
)

/** 由手势进度算一帧「书脊恒在左」的翻页几何，见 [CurlFrame]。 */
internal fun curlFrame(
    pageW: Float,
    pageH: Float,
    progress: Float,
    direction: Int,
    tilt: Float = 0f,
): CurlFrame {
    val back = direction < 0
    return CurlFrame(
        shape = curlShape(
            pageW = pageW,
            pageH = pageH,
            progress = if (back) 1f - progress else progress,
            direction = 1,
            tilt = if (back) -tilt else tilt,
        ),
        turningIsTarget = back,
    )
}

/** 进度头尾的淡出系数：中间恒 1，两端线性到 0。 */
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
 * 翻页层把上一页映射成 `direction = +1` 的同一套几何（[curlFrame]），所以实际翻上一页时
 * 露出区也在右——露出的是当前页，纸从左边盖回来。
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
 * 凸多边形与轴对齐矩形求交（Sutherland–Hodgman，按矩形四条边逐条切）。
 *
 * 用来把「露出页」那块梯形裁到页栅格的材料矩形里：页栅格只有版心那么大，
 * 直接拿 shader 填整个梯形会让页边距里的采样把版心最边一列拉成横线（`Clamp` 的锅）；
 * 裁到材料矩形之后 shader 只画到版心边上，页边距留纸色（见 `drawRevealPage`）。
 *
 * @return 交点多边形（可能为空：整块都在矩形外）。顶点顺序保持输入的方向。
 */
internal fun clipPolygonToRect(
    pts: List<Offset>,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
): List<Offset> {
    if (pts.size < 3) return emptyList()
    var poly = pts
    // 四条边，每条是「法向 + 门槛 c」：nx·x + ny·y ≥ c 的一侧保留。
    val nxArr = floatArrayOf(1f, -1f, 0f, 0f)
    val nyArr = floatArrayOf(0f, 0f, 1f, -1f)
    val cs = floatArrayOf(left, -right, top, -bottom)
    for (e in 0 until 4) {
        if (poly.isEmpty()) return emptyList()
        val nx = nxArr[e]
        val ny = nyArr[e]
        val c = cs[e]
        fun inside(p: Offset) = nx * p.x + ny * p.y - c >= -1e-3f
        val out = ArrayList<Offset>(poly.size + 2)
        for (i in poly.indices) {
            val cur = poly[i]
            val prev = poly[(i + poly.size - 1) % poly.size]
            val curIn = inside(cur)
            val prevIn = inside(prev)
            if (curIn) {
                if (!prevIn) out.add(crossAt(prev, cur, nx, ny, c))
                out.add(cur)
            } else if (prevIn) {
                out.add(crossAt(prev, cur, nx, ny, c))
            }
        }
        poly = out
    }
    return poly
}

/** 线段 `a→b` 与直线 `nx·x + ny·y = c` 的交点；退化时返回 `a`。 */
private fun crossAt(a: Offset, b: Offset, nx: Float, ny: Float, c: Float): Offset {
    val da = nx * a.x + ny * a.y - c
    val db = nx * b.x + ny * b.y - c
    val d = da - db
    if (kotlin.math.abs(d) < 1e-6f) return a
    val t = (da / d).coerceIn(0f, 1f)
    return Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
}

/**
 * 卷曲半径：**整段翻页固定一个筒径**（[CURL3D_RADIUS_RATIO]，定稿 0.10 页宽）。
 *
 * 半径与进度无关，也与折痕到页边的距离无关——筒是同一根筒在滚，粗细则在整段翻页里不变。
 *
 * ## 为什么不再按「剩下的纸」收细
 *
 * 早先的式子取自参考实现的 `dMax = clamp(pointer.x − pageLeft, 0, dWidth)`：半径不超过
 * 折痕到「还没掀起那一侧」页边的距离。折痕贴到书脊时这个距离是 0，卷筒被压成一条线 ——
 * 读者原话「纸张滚动到书脊处，无法画卷筒了」。可那时纸还整整有一页，缺的不是纸，
 * 是「让筒滚出页边」的那段行程：退场交给 [curlShape] 的折痕余量（折痕越过书脊一个半径、
 * 纸卷整根出页），半径只管筒本身多粗。
 *
 * ## 代价：起手那一段没有平铺的纸背
 *
 * 纸卷绕半圈要用掉 `πR` 那么长的纸。筒径固定之后，掀起长度不足 `πR` 时掀起的那部分纸
 * **整段都在筒上**、一段平铺的纸背都没有（读者看到的是一页字被切断、切断处什么也没有）。
 * 这是「筒径不许变」换来的，**有意为之**：早先为保住纸背而按 `πR ≤ 掀起长度 × 0.7` 收细筒径，
 * 正是筒径十倍变化的来源。真要纸背更早出现，只能把筒径调小 —— 那是另一个方向的取舍。
 */
internal fun curlRadius(shape: CurlShape, radiusRatio: Float = CURL3D_RADIUS_RATIO): Float {
    val w = shape.pageW.coerceAtLeast(1f)
    return w * radiusRatio.coerceIn(0.002f, 1.5f)
}
