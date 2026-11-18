package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

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

/** 阴影带宽（5% 页宽），与旧版 `SHADOW_BAND` 同一个数。 */
internal const val CURL_SHADOW_BAND: Float = 0.05f

/** 进度头尾这一段（占整段的比例）里扶正折痕、淡出阴影，见文件头。 */
internal const val CURL_TAIL_FADE_SPAN: Float = 0.1f

/**
 * 一次拖动：`from` = 起手时**贴在页边上**的那个点，`to` = 当前手指位置（都是页坐标）。
 *
 * 参考实现 Moon+ `NewCurl3D.simulateOnTouchLock` 在 DOWN 时把起手点贴到页边
 * （`mDragStartPos = (pageRect.right, 触屏 y)`），`updateCurlPos` 里
 * `mCurlDir = 手指 − mDragStartPos`，**折痕恒与 `mCurlDir` 垂直**（`Mesh3D.curl` 先把
 * 整页旋转到该方向再切）。所以斜着拖时折痕是斜的，斜率由**拖动方向整条**决定，
 * 而不是由一个平滑过的 `dy/|d|` 决定。
 *
 * 拖动方向（从起手点指向手指）本身是位移的积分，天然平滑，**不需要再做平滑**：
 * 旧版按每个事件算 `dy/|d|` 再取指数平均，等于在跟手链路上串了一个低通，斜拖时折痕
 * 角度永远追不上手指——这正是「斜着拖时纸边差得远」的来源。
 */
data class CurlPull(val from: Offset, val to: Offset) {
    /** 拖动单位方向；位移太小（方向是噪声）时返回 null。 */
    fun unit(): Offset? {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val len = hypot(dx, dy)
        if (len < 1f || !len.isFinite()) return null
        return Offset(dx / len, dy / len)
    }
}

/**
 * 拖动方向 ⇒ 折痕倾斜量（[CurlShape.lean] 的输入）。
 *
 * 折痕与拖动方向垂直：掀起侧的**单位法向** `n = −ǘ`（`ǘ` = 手指相对页边的拖动方向），
 * 而 [curlShape] 里折痕两端是 `(ox − lean·tiltW, 0)`、`(ox + lean·tiltW, h)`，其法向
 * 正比于 `(h, −2·lean·tiltW)`。两者相等即
 *
 * ```
 *   lean = −(u_y / u_x) · h / (2·tiltW)
 * ```
 *
 * 斜率夹取：折痕两端不许被端点夹取掰弯，等价于**整个页高最多横移两个页宽**
 * （`|u_y / u_x| ≤ 2w / h`，参考实现在更陡的角度上才夹到页角）。指尖从小幅上下抖动
 * 会让比率爆掉，不夹就是折痕一顿一顿地躺平。
 *
 * 拖回页边之外（`u_x` 换向）时比率仍可算，交给 [curlShape] 的端点夹取兜住；
 * 纯上下拖（`u_x ≈ 0`）方向退化，折痕保持竖直。
 */
internal fun leanOf(pull: CurlPull, pageW: Float, pageH: Float): Float {
    val u = pull.unit() ?: return 0f
    val w = pageW.coerceAtLeast(1f)
    val h = pageH.coerceAtLeast(1f)
    val tiltW = w * CURL_TILT_WIDTH
    if (kotlin.math.abs(u.x) < 1e-4f) return 0f
    // 折痕横移上限 = 两个页宽（参考实现在角度再陡时才夹到页角，见 KDoc）。
    val maxSlope = 2f * w / h
    val slope = (u.y / u.x).coerceIn(-maxSlope, maxSlope)
    return -slope * h / (2f * tiltW)
}

/**
 * 由进度算一帧折痕几何。
 *
 * @param direction +1 向后翻 / -1 向前翻（来自 [orilumn.reader.ui.reader.FlipSession.direction]）
 * @param tilt 折痕倾斜量（0 = 竖直折痕）。拖动中由 [leanOf] 从拖动方向算出——**是斜率**，
 *   不是 `dy/|d|` 的正弦，所以斜拖时折痕才真的垂直于手指的轨迹；结算期间保持松手那一刻的值。
 *   量级上限 `1 / (2·0.9) ≈ 0.56` 以内不被端点夹取影响，再陡由 [leanOf] 的斜率夹取兜住。
 * @param pull 本次拖动（起手页边点 + 此刻手指），非 null 时折痕位置改由它反解（见
 *   [anchoredFoldX]）——读者手上那张纸的边就压在手指上。**只认下一页**：上一页在
 *   [curlFrame] 里就传 null，与旧版「上一页不跟手」同一口径。
 */
internal fun curlShape(
    pageW: Float,
    pageH: Float,
    progress: Float,
    direction: Int,
    tilt: Float = 0f,
    pull: CurlPull? = null,
): CurlShape {
    val w = pageW.coerceAtLeast(1f)
    val h = pageH.coerceAtLeast(1f)
    val forward = direction >= 0
    val p = progress.coerceIn(-0.9f, 1.9f)

    // 倾斜方向与手势方向垂直：向后翻正倾斜 ⇒ 上端左移、下端右移；向前翻取反。
    val lean0 = (if (forward) tilt else -tilt).coerceIn(-1f, 1f)

    // 进度头尾 10%：倾斜与阴影一起淡出（见文件头「头尾扶正」）。
    // **跟手时不淡倾斜**：起手时折痕就由手指反解、可能已经掀了一大块，再扶正只会让
    // 折痕在开头几帧歪回竖直（斜拖时纸边跟着偏）；末段那一次扶正由松手结算接手。
    val fade = tailFade(p)
    val lean = if (pull != null && forward) lean0 else lean0 * fade

    // 纸卷退场（见文件头）：折痕贴到「纸少的那一侧」页边后，再用一个半径的行程把筒滚出页边。
    // 这一段只有 R/w 那么长，所以折痕在这里走得比手指快一倍；不这么做，筒要么被压扁
    // （按剩余纸宽收细），要么在末帧整根消失（撤层时跳一下）。
    val exit = w * CURL3D_RADIUS_RATIO
    val tiltW = w * CURL_TILT_WIDTH
    // 折痕位置：向后翻从右缘扫到左缘，向前翻从左缘扫到右缘（旧版同款）。
    // 跟手时折痕不再听进度，改由「掀起的纸边贴在手指上」反解（见 [anchoredFoldX]）。
    val foldX = if (pull != null && forward) {
        anchoredFoldX(pull, w, h, lean, tiltW).coerceIn(-exit, w)
    } else {
        val base = if (forward) w * (1f - p) else w * p
        if (forward) {
            if (base < exit) 2f * base - exit else base
        } else {
            if (w - base < exit) 2f * base - (w - exit) else base
        }
    }

    // 端点比折痕多留一个半径的余量：退场时折痕整根移出页边，端点要跟着出去；夹回 0 会把
    // 折痕的角度掰回来，纸卷跟着变形。
    fun clampX(x: Float) = x.coerceIn(-w * 2f, w * 3f)
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
    pull: CurlPull? = null,
): CurlFrame {
    val back = direction < 0
    return CurlFrame(
        shape = curlShape(
            pageW = pageW,
            pageH = pageH,
            progress = if (back) 1f - progress else progress,
            direction = 1,
            tilt = if (back) -tilt else tilt,
            // 上一页不跟手（旧版同款）：它的纸是从左边进来的，手指抓的不是它的边。
            pull = if (back) null else pull,
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
 * 跟手锚点 ⇒ 折痕中点（页高一半处）的横坐标：**让掀起来那张纸的自由边正好压在手指上**。
 *
 * ## 贴的是「纸自己的那条边」，不是折痕
 *
 * 旧版 `orilum.bak` 的 `CurlView.anchoredFoldX` 注释原文：「掀起的书页边线从手指触屏点穿过」。
 * 说的是**翻起来那张纸的一条边**——纸沿折痕翻过去之后，原来贴在页边的那条边落在哪，
 * 折痕就摆到哪。硬纸对折（平面镜射）时它就是 `(手指x + 页宽) / 2`：折痕走手指的一半，
 * 纸边走手指的一倍。
 *
 * 参考实现 Moon+ 的 `NewCurl3D.updateCurlPos`（`sources/com/flyersoft/views/NewCurl3D.java`）
 * 是同一个约束的**卷筒版**，而且这是它唯一的交互定义：手指拖多远，纸边就跟到哪。所以
 * 「折痕位置」不是进度，也不是手指位置，而是**手指离纸边的距离反解出来的**。
 *
 * ## 卷筒上「纸边在手指」是什么方程
 *
 * 网格把「离折痕 `s` 远」的材料放到屏幕法向 `off(s)` 处（见 [CurlMesh.buildCurlMesh]）：
 *
 * ```
 *   off(s) = R·sin(s/R)      s ≤ πR   （卷在筒上，纸还朝掀起侧鼓着）
 *          = πR − s          s > πR   （绕满半圈，平铺回另一侧）
 * ```
 *
 * 手指抓着的是**起手那一点**所在的那条材料边（[CurlPull.from]，页边上的材料点 `G`）。
 * 它在离折痕 `L` 远的材料处，被映射到「折痕 + `off(L)`」。要求它落在手指：
 * `手指 = 折痕 + off(L)·n`。把折痕写成过 `(ox, h/2)`、两端点
 * `(ox − lean·tiltW, 0)`、`(ox + lean·tiltW, h)`（与 [curlShape] 同一套）的直线，
 * 则 `手指 − 折痕` 沿法向的距离是 `D = |手指 − G|`，只剩一元的
 *
 * ```
 *   L − off(L) = D      D = 手指离抓点多远（= 参考实现的 `d = |mCurlDir|`）
 * ```
 *
 * 两段解都直接给出 `L`：
 *
 * - `D ≥ πR`（纸已经绕满半圈）：`off(L) = πR − L` ⇒ **`L = (D + πR)/2`**，精确；
 * - `D < πR`：`L − R·sin(L/R) = D` 单调可解。这里用参考实现的闭式近似
 *   **`L = D + R·sin(π·√(D/πR))`**——两端（`D = 0`、`D = πR`）与精确解重合，中段误差
 *   在 1% 页宽以内（纸边与手指的偏差 ≤ 8px @ 页宽 800），省掉二分。
 *
 * 折痕于是是过 `G·n − L` 那条等值线：`ox = (G·n − L − (h/2)·n_y) / n_x`。
 *
 * **反解的是「抓点」，不是页角。** 竖折痕时两者等价（整条页边法向坐标一样）；斜折痕下
 * 页角离手指很远，按页角反解等于把整张纸绕着手指转出去——斜拖「差之十万八千里」就是这么来的。
 *
 * ## 「起手一记跳变」是有意为之
 *
 * `D = 0`（手指正在页边）时 `L = 0`、折痕就在页边；手指一往里，`L` 立刻涨上来——折痕会
 * 比手指快好几倍。这不是缺陷，是**跟手的定义**：屏幕上被跟踪的是**纸边**（读者眼睛盯的
 * 也是它），纸边与手指 1:1，折痕只能跑得更快。反过来，任何「折痕 1:1 跟手」的写法都会
 * 让纸边钉在页边不动（`off(s) ≈ s`，`s` 小时的位移是二阶的），那才是「还不如不改」。
 *
 * @return 折痕中点横坐标（未夹取；调用方 [curlShape] 负责夹进 `[−R, 页宽]`）。
 */
internal fun anchoredFoldX(pull: CurlPull, w: Float, h: Float, lean: Float, tiltW: Float): Float {
    val r = (w * CURL3D_RADIUS_RATIO).coerceAtLeast(1e-3f)
    val halfTurn = PI.toFloat() * r
    // 折痕两端 (topX,0)-(bottomX,h) 的斜率：横向位移 sl = 2·lean·tiltW，法向指向 +x 一侧。
    val sl = 2f * lean * tiltW
    val len = hypot(h, sl).coerceAtLeast(1e-3f)
    val nx = h / len
    val ny = -sl / len

    // 被抓着的那一点：起手时贴在页边上的材料点（参考实现的 `mDragStartPos`）。
    // **不是页角**：斜折痕下页角离手指很远，拿它反解会让整块纸偏出去（读者：「斜着拖差之
    // 十万八千里」）。手指拖的始终是它抓住的那一点。
    val grabbed = pull.from
    val finger = pull.to
    // 手指与抓点的**法向**距离：折痕垂直于拖动时它就等于两点距离，角度被夹住时也不能
    // 拿斜边当分量用（那会让纸边越过手指）。负数（手指退到抓点另一侧）按 0 处理。
    val dist = ((grabbed.x - finger.x) * nx + (grabbed.y - finger.y) * ny).coerceAtLeast(0f)

    // 折痕到「被抓点」的材料长度（见 KDoc 的两段解）：g − off(g) = dist。
    val material = when {
        dist <= 0f -> 0f
        dist >= halfTurn -> (dist + halfTurn) * 0.5f
        else -> dist + r * sin(PI.toFloat() * sqrt(dist / halfTurn))
    }
    // 折痕是一条直线 `X·n = 被抓点·n − g`；取它与页高中线（y = h/2）的交点。
    val gn = grabbed.x * nx + grabbed.y * ny
    return (gn - material - (h * 0.5f) * ny) / nx
}

/**
 * 跟手时**屏幕上真实折痕位置**对应的进度（0 = 没掀，1 = 翻开）。
 *
 * 松手结算要从这个值起步，否则动画会先跳回「按位移算出来的进度」再走——旧版
 * `startCommitAnim` 的 `start` 就是同一件事（`(w − fx) / w`）。
 */
internal fun anchoredProgress(pull: CurlPull, pageW: Float, pageH: Float, tilt: Float, progress: Float): Float {
    val w = pageW.coerceAtLeast(1f)
    val shape = curlShape(
        pageW = w,
        pageH = pageH.coerceAtLeast(1f),
        progress = progress,
        direction = 1,
        tilt = tilt,
        pull = pull,
    )
    return (1f - shape.foldX / w).coerceIn(0f, 1f)
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
