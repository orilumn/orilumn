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
 * **折痕与书脊：两端一视同仁，都可以卷过书脊**（读者 2026-11-21 口径：「上下书脊应该是
 * 相同的！你见过哪本书是翻不到书脊的？」）。纸角卷过书脊就是翻页本来的样子，所以书脊这一侧
 * **不压倾斜**：折痕端点按手指给的倾斜走，装订边那一侧只留纸卷**退场**的一个筒径（`R`），
 * 自由边那一侧只受 [CURL_ENDPOINT_BOUND] 的 NaN 兜底。左半边、右半边、两个翻页方向、
 * 跟手与结算同一套；两种锚点只在「抓的是什么」上不同（纸边压在手指 / 折痕穿过手指）。
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
 * 与末段淡出（[CURL_TAIL_FADE_SPAN]）取同一个窗口，收尾时折痕已扶成竖直（跟手交接的翻页
 * 在整段行程里扶正，见下）、直着出页。
 *
 * ## 头尾扶正（修「进度两端折角一帧消失」与「松手后临近结束忽然跳变」）
 *
 * 斜折痕贴在页边时，屏幕上会有一块「已经掀过去的大三角」和折痕旁的一道影。进度两端这两块
 * 都没有理由继续存在：进度 0 是「一页都没掀」，进度 1 是「整页都翻完」。但几何本身并不在
 * 两端自然收成零——折痕一斜，贴着页边也能圈出一大块折角。
 *
 * 所以进度**头尾各 10%** 里把阴影线性淡到 0（`tail fade`，旧版注释原文「否则覆盖层
 * 撤掉时停一下再跳」）。**只压阴影，不压读者自己给的进度**。早先只在结算里做，
 * 于是手指退回起手点（`dx` 归零）时折角会在一帧里整块消失，读者：「像那儿有一条屏幕边线」。
 *
 * 倾斜的扶正分三种：进度两端照旧淡（点按 / 程序翻页，[tailFade]）；**松手交接的翻页**把松手那一刻的
 * 倾斜**先保持住、再用整段行程平滑退回**（[settleLeanFade]）；**松手交接的回弹**按「还剩多少行程」
 * 匀速退回（[settleLeanRewind]）。翻页那条要的是「接着松手前的方向走」：松手那一帧折痕是什么角度
 * 就从那个角度接着转，倾斜按整段行程缓缓塌下去，而不是一松手就先扶平、再让折痕横扫（读者口径：
 * 「先是回平再播放，非常不协调」）。倾斜与阴影因此是两个系数，各自淡各自的。
 *
 * 跟手阶段**不扶正、也不淡影**：那时折角的大小不听进度（折痕由手指反解），扶正会把形状
 * 掰歪、淡影会在折角还很大时先把影淡没。倾斜改由「竖向行程上限」约束（见下），
 * 两端收尾改由几何自己达（见 [CurlShape.noLiftYet]）。
 *
 * 与旧版的唯一差别：旧版对「收在右缘」的那两端是把进度**冲过 1**（`1 + 0.9·|tilt|`）
 * 让整块滑出屏幕；我们的进度归一在 `[0, 1]`（与滑动层共用），右缘那一端改用扶正来收干净
 * ——末帧折痕竖直贴边、卷曲区面积为零；书脊那一端另有纸卷退场（见上），末帧连筒一起出页。
 *
 * ## 跟手时的竖向行程上限（修「退回起手点整页折角一帧消失」与「页角横向晃一下就爬向页顶」）
 *
 * 跟手阶段不淡倾斜（形状必须听手指），但倾斜还受**手指竖向行程**约束（见 [verticalLeanCap]）：
 * 竖向行程趋零时拖动方向退化，倾斜不许跟着 `u_y/u_x` 炸到最大——否则手指退回起手点的
 * 那几帧里，折痕斜切着一大块三角，指尖一到就整块消失。
 *
 * 上限只跟**竖向**行程走，不跟横向：起手点就在页角上时横向分量天然很小，按横向算会把倾斜
 * 压到近 0，折痕与自由边的交点随之扫过整个页高（读者 2026-11-21：「页角忽然向上跳一下」）。
 * 竖向行程正是读者口径「手指向上、纸角就向上转」的那个量；短行程保守、长行程放开，
 * 页角折起来之后横向晃手指，折痕角度只跟着微动。
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
    /**
     * 折痕倾斜量（正 = 上端 `foldX − lean·tiltW`、下端 `foldX + lean·tiltW`）。
     *
     * 跟手时由 [leanOf] 从拖动方向算出（折痕垂直于手指轨迹；折痕锚点的横向符号由起手
     * 横滑方向锁住，见 [CurlPull.direction]），也已做扶正（结算跟手交接时翻页用
     * [settleLeanFade]、回弹用 [settleLeanRewind]，点按用 [tailFade]）/ 跟手时的竖向行程收小。
     */
    val lean: Float,
    /**
     * 阴影随进度的淡出系数 0..1（见文件头「头尾扶正」）。
     *
     * **只管阴影**：倾斜另有 [lean] 自己的扶正系数（[settleLeanFade] / [settleLeanRewind]），
     * 两者不再共用一个数。
     * 跟手帧（[anchored]）恒为 1：那时折角的大小不听进度，影的浓淡交给几何（纸抬多高、
     * 影多宽），按进度淡会在折角还很大时把影先淡没（见 [noLiftYet]）。
     */
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
    /**
     * 折痕位置是不是**跟手反解**出来的（拖动中，两种锚点见 [CurlAnchor]），而不是由
     * [progress] 推出来的。
     *
     * 这两件事必须分开：进度是「手指相对起手点的累计位移」，跟手时折痕位置是「手指离自由边
     * 多远」反解出来的。手指一路滑出去再滑回来、越过起手点的那一帧，进度会先掉到 0 以下，
     * 折角却还有半个页宽 —— 此时若按进度收尾，整块折角会在一帧里消失（[noLiftYet] 的 KDoc）。
     */
    val anchored: Boolean = false,
)

/** 倾斜外扩的固定幅度（0.9 页宽），与旧版 `tiltW = w * 0.9f` 同一个数。 */
internal const val CURL_TILT_WIDTH: Float = 0.9f

/**
 * 跟手时倾斜**放开到满**所需的竖向行程（以 `0.9 页宽` 为 1）。
 *
 * 上限按 `CURL_LEAN_MAX · (竖向行程 / 这个比例)²` 算（见 [verticalLeanCap]）：竖向行程
 * 40px（0.024）时只允许 0.11，到 0.115（约 0.10 页宽）就放开到满 [CURL_LEAN_MAX]。
 *
 * 取平方而不是线性：短行程压得比线性更紧（不重演「一按就掀两三成页宽」），中等行程
 * 很快就放开（页角折起来之后折痕要真的垂直于手指轨迹，纸边才咬得住手指）。线性版本
 * 要么短行程太松（一按就斜），要么长行程太紧（纸边咬不住手指），两头不可兼得。
 */
internal const val CURL_LEAN_FULL_TRAVEL: Float = 0.115f

/**
 * 跟手时倾斜上限的**硬顶**：折痕最多斜到约 68°（`atan(2.5·0.9) ≈ 66°`）。
 *
 * 再斜折痕就成横的：它不再从纸角斜切出去，而是横跨整页——整条页底被掀起、页角不再是
 * 页角（读者 2026-11-21 口径里的「页角折起成一个小三角形」就不成立了）。实测 1840×2800
 * 的页、从右下角往上拖 420px：倾斜 2.5 时折痕下端还在页内（页角三角形），3.0 就已经
 * 越到书脊外、整条页底跟着掀。
 */
internal const val CURL_LEAN_MAX: Float = 2.5f

/**
 * 折痕中点离页面的兜底范围（页宽的倍数）。
 *
 * 跟手反解与结算交接都可能把折痕推到页外：页角折起来时折痕的**页高中点**常常在自由边
 * 之外（页内只看得到它裁进页面的那一小段）。这不是越界，是几何本来的样子，所以**两端
 * 一视同仁**，这里只防 NaN / 无限滑出去。
 */
internal const val CURL_FOLD_BOUND: Float = 8f

/**
 * 折痕端点离页面的兜底范围（页宽的倍数）：中点 ± `CURL_LEAN_MAX · 0.9` 页宽，再留余量。
 *
 * **自由边那一侧**的夹取只许是 NaN 兜底：它一旦真的夹住，折痕就被掰弯、纸边立刻离开手指
 * ——这正是页角跟手时「纸边完全脱离手指锚点」的来源之一。装订边那一侧另有纸卷退场的
 * 一个筒径（见 [curlShape]）。
 */
internal const val CURL_ENDPOINT_BOUND: Float = 12f

/** 阴影带宽（5% 页宽），与旧版 `SHADOW_BAND` 同一个数。 */
internal const val CURL_SHADOW_BAND: Float = 0.05f

/** 进度头尾这一段（占整段的比例）里淡出阴影，见文件头。 */
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
/**
 * 跟手时**手指抓的是什么**（起手那一刻定一次，拖动中不再改判）。
 *
 * 读者定的口径：起手在屏幕**右半边** ⇒ 抓的是页边，掀起那张纸的自由边压在手背上过；
 * 起手在**左半边** ⇒ 抓的是折痕本身，折痕直接穿过手指坐标。
 */
enum class CurlAnchor {
    /** 页边锚点：纸的自由边压在手指上（见 [anchoredFoldX]）。 */
    Edge,

    /** 折痕锚点：折痕穿手指（见 [creaseFoldX]）。 */
    Crease,
}

data class CurlPull(
    val from: Offset,
    val to: Offset,
    /** 起手那一刻定的锚点，见 [CurlAnchor]。 */
    val anchor: CurlAnchor = CurlAnchor.Edge,
    /**
     * 起手接手的程度（0 = 折痕还在静止位置，1 = 完全听手指）。
     *
     * 起手一按就按锚点摆位置会**跳过一大块行程**：屏幕右侧往右滑翻上一页（页边锚点，折痕
     * 一上来就在页中偏右）、屏幕左侧往左滑翻下一页（折痕锚点，折痕一上来就在手指处），
     * 目标页当场展开大半。所以拖动开头先播一小段「折痕从静止位置滑到锚点」的动画
     * （[pullEngage]，见 `FlipController.updateCurlPull`），到位之后 [blend] = 1，完全交给手。
     */
    val blend: Float = 1f,
    /**
     * 这次拖动属于哪个翻页方向（+1 下一页 / -1 上一页）。
     *
     * 只给**折痕锚点**定横向符号用：它的 `from` 就是按下点，手指越过按下点后 `u_x` 换号，
     * 而折痕要垂直于手指轨迹（[leanOf]）。翻页方向记录的是**起手那一刻**的横滑方向
     * （`direction = −sign(起手 u_x)`），整个手势不变——起手就垂直，越过按下点也不镜像。
     * 页边锚点的 `from` 是页右缘，手指永远在它左边（`u_x < 0`），用不到它。
     */
    val direction: Int = 1,
) {
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
 * ## 折痕垂直于手指轨迹
 *
 * 折痕方向是 `(bottomX − topX, h) = (2·lean·tiltW, h)`，要求它与拖动方向 `(u_x, u_y)` 垂直：
 *
 * ```
 *   lean = −(u_y / u_x) · h / (2·tiltW)
 * ```
 *
 * 纯水平拖动 `u_y = 0` ⇒ 竖折痕；纯上下拖方向退化、斜率爆掉，由 [verticalLeanCap] 兜住。
 *
 * ## 两种锚点的横向符号
 *
 * 页边锚点的 `from` 是页右缘，手指永远在它左边 ⇒ `u_x < 0` 恒成立，上式直接可算。
 *
 * 折痕锚点的 `from` 就是按下点，`u_x` 会随手指越过按下点换号：直接照上式算，折痕会在
 * 跨过按下点的那一帧整根镜像（读者 2026-11-19 报的「滑回右侧，手势还是反向」）。所以
 * 折痕锚点的横向符号取**起手那一刻**的 `sign(u_x)`，由 [CurlPull.direction] 锁住
 * （横滑方向决定翻页方向，`direction = −sign(起手 u_x)`），整个手势不变：
 *
 * ```
 *   lean = direction · (u_y / |u_x|) · h / (2·tiltW)      （折痕锚点）
 * ```
 *
 * 起手就垂直，越过按下点也不镜像。早先两种锚点都用 `u_y/|u_x|`，等于把折痕锚点的符号
 * 固定成「下一页」那一侧：屏幕左侧起手翻上一页（起手 `u_x > 0`）时，折痕与手指方向平行
 * 而不是垂直——读者 2026-11-26 报的「屏幕左侧起手，折痕方向没有与手指运行方向垂直，而是
 * 相反的」正是它。页边锚点没有这个自由度（`u_x` 恒负），仍用 `u_y/|u_x|`。
 *
 * ## 大小
 *
 * 斜率按 `u_y/|u_x|` 算（`u_x ≈ 0` 时不提前返回 0，见下），再乘 `h/(2·tiltW)` 换成倾斜量。
 * 纯上下拖方向退化时斜率爆掉，由 [verticalLeanCap] 兜住；纯横向拖 `u_y = 0`，折痕竖直。
 *
 * ## 为什么上限按竖向行程，不按横向
 *
 * `u_y/|u_x|` 在手指越过按下点、或从页角往上拖时必然爆掉（横向位移趋零、纵向还在）。
 * 旧版按横向行程收倾斜：横向越小折痕越直。可起手点就在页角上，斜拖出去的横向分量天然
 * 很小——倾斜被压到近 0，折痕退成贴着自由边的竖直线，而折痕位置还由纸边反解、离自由边
 * 有一整段纸卷。两者相乘让「折痕与自由边的交点」在几十像素的横向手指移动里扫过整个页高，
 * 读者看到的就是「页角忽然向上跳一下」（§16.10）。
 *
 * 竖向行程才是读者口径「手指向上、纸角就向上转」的那个量。上限随竖向行程长大：短行程
 * 保守（一按就掀一大块三角的旧账），长行程放开到真的垂直于手指轨迹（纸边咬住手指）。
 * 横向分量仍决定**方向与想要的大小**（`u_y/|u_x|`），只是不再当上限的分母。
 *
 * 代价：竖向几乎不动、横向很长的拖动倾斜很小——那本来就是竖直折痕，横折痕不在
 * [CurlShape]（两端落在页上下边）的表达范围内。
 */
internal fun leanOf(pull: CurlPull, pageW: Float, pageH: Float): Float {
    val u = pull.unit() ?: return 0f
    val w = pageW.coerceAtLeast(1f)
    val h = pageH.coerceAtLeast(1f)
    val tiltW = w * CURL_TILT_WIDTH
    val ax = kotlin.math.abs(u.x).coerceAtLeast(1e-4f)
    // lean = −u_y/u_x · h/(2·tiltW)。页边锚点的 u_x 恒为负；折痕锚点的 u_x 会越过按下点
    // 换号，横向符号改用起手那一侧（direction = −sign(起手 u_x)）锁住。
    val sideways = if (pull.anchor == CurlAnchor.Crease) pull.direction.toFloat() else 1f
    val wanted = sideways * u.y / ax * h / (2f * tiltW)
    val cap = verticalLeanCap(pull, tiltW)
    return leanWithinPageEnds(pull, w, h, wanted.coerceIn(-cap, cap))
}

/**
 * 倾斜量的**书脊侧端点**上限：折痕下端（或上端）不许越过书脊一个筒径
 *（`−R`，见 [curlShape] 的 `clampLo`）。
 *
 * ## 它修的是「手指向上、折痕反向跑掉、手指脱离折痕」
 *
 * 折痕两端是 `foldX ∓ lean·tiltW`（[curlShape]），而 `foldX` 由手指反解。左半边起手抓的是
 * **折痕**（[creaseFoldX]），手指往上滑时 `lean` 变成大负数，折痕下端被甩到页左边好几页宽
 * 以外。上一版在 [curlShape] 里**单独夹折痕端点**：下端被夹到 `−R`，上端照旧甩出去，折痕
 * 这条线因此被掰弯 —— 「折痕穿过手指」当场破功。实测（页 1840×2800，按下 (460,1400)，
 * 手指上移 200/400/600px）：手指仍在 x=460，折痕在手指那一行却跑到了 2211/2131/1965，
 * 而且手指越往上、折痕越往左，读者看到的就是「折痕反向运行、手指脱离折痕」。
 *
 * 所以**夹倾斜、不夹端点**：倾斜收小到端点刚好不越界，折痕位置再按这个倾斜用同一个反解
 * 重算（[creaseFoldX] / [anchoredFoldX]），「折痕穿过手指 / 纸边压在手指」这条不变量因此
 * 原样成立。倾斜越小两端越靠近折痕中点，`lean = 0` 时两端重合在 `foldX` 上，一定不越界
 * （手指在页内），二分收缩必定收敛。
 *
 * 这个上限**只跟手时用**（它要知道手指在哪）。结算没有手指，但它沿用的是松手那一刻
 * [leanOf] 给的值（`FlipController.curlLean`），所以松手那一帧与跟手最后一帧同形，不跳。
 *
 * 注意它算的是 **`blend = 1` 那一帧**的折痕：起手接手那百来毫秒里折痕还从静止位置
 * （上一页是书脊外一个筒径）滑向锚点，折痕本来就不在手指上，[curlShape] 的端点夹取会咬到
 * ——那是过渡态，不影响「接到手指之后折痕不再离开手指」。
 */
private fun leanWithinPageEnds(pull: CurlPull, w: Float, h: Float, lean: Float): Float {
    if (lean == 0f) return 0f
    val tiltW = w * CURL_TILT_WIDTH
    val lo = -w * CURL3D_RADIUS_RATIO
    val hi = w * CURL_ENDPOINT_BOUND
    fun fits(l: Float): Boolean {
        val fold = if (pull.anchor == CurlAnchor.Crease) {
            creaseFoldX(pull, h, l, tiltW)
        } else {
            anchoredFoldX(pull, w, h, l, tiltW)
        }
        val a = fold - l * tiltW
        val b = fold + l * tiltW
        return a >= lo && a <= hi && b >= lo && b <= hi
    }
    if (fits(lean)) return lean
    var ok = 0f
    var bad = 1f
    repeat(18) {
        val mid = (ok + bad) * 0.5f
        if (fits(lean * mid)) ok = mid else bad = mid
    }
    return lean * ok
}

/**
 * 跟手时倾斜量的**竖向行程**上限：`|lean| ≤ [CURL_LEAN_MAX] · (|竖向行程|/tiltW / [CURL_LEAN_FULL_TRAVEL])²`。
 *
 * ## 它修的是「手指退回起手点时整页折角瞬间消失」
 *
 * 折痕方向取自「起手页边点 → 手指」这条**整段位移**的方向，倾斜量正比于 `u_y / u_x`。
 * 手指一路向左拖再向回拖，横向分量重新趋零时，这个比率不可避免地爆掉：斜率夹取把它顶到
 * 上限，折痕被顶到最斜。折痕中点还贴着右缘（纸边压在手指上），可折痕本身从页角斜切
 * 出去——屏幕上就是**大半个页都被折起**。等手指真的回到起手点、位移小于 1px，方向退化、
 * 倾斜一步归零，那一大块折角在一帧里整块消失。读者原话：「接近之前的 x 坐标时，阴影逐渐
 * 消失，然后整个折起的页面瞬间消失，动画结束。」
 *
 * 上限随竖向行程连续长大（平方，两端都不冲）：行程越小折痕越直，回到起手点正好竖直
 * 贴边，折角面积连续收到零。短行程时上限压得比线性更紧，是为了不重演「一按就掀两三成
 * 页宽」；中等行程放开得更快，是为了页角折起来之后折痕仍垂直于手指轨迹。
 *
 * 限额只在跟手时用（结算期间没有手指，倾斜听 [settleLeanFade] / [settleLeanRewind] 的扶正
 * 系数，见文件头「头尾扶正」）。
 */
internal fun verticalLeanCap(pull: CurlPull, tiltW: Float): Float {
    val dy = kotlin.math.abs(pull.to.y - pull.from.y)
    val r = dy / tiltW.coerceAtLeast(1e-3f) / CURL_LEAN_FULL_TRAVEL
    return (CURL_LEAN_MAX * r * r).coerceAtMost(CURL_LEAN_MAX)
}

/**
 * 由进度算一帧折痕几何。
 *
 * @param direction +1 向后翻 / -1 向前翻（来自 [orilumn.reader.ui.reader.FlipSession.direction]）
 * @param tilt 折痕倾斜量（0 = 竖直折痕）。拖动中由 [leanOf] 从拖动方向算出——**是斜率**，
 *   不是 `dy/|d|` 的正弦，所以斜拖时折痕才真的垂直于手指的轨迹（折痕锚点的横向符号由
 *   起手横滑方向锁住，见 [CurlPull.direction]），结算期间保持松手那一刻的值。
 *   这是**想要的**倾斜，跟手时 [leanOf] 已按竖向行程（[verticalLeanCap]）夹过；书脊这一侧
 *   不再压倾斜——折痕端点可以卷过书脊一个筒径（见文件头）。
 * @param pull 本次拖动（起手点 + 此刻手指 + 起手那一刻定的锚点），非 null 时折痕位置改由它
 *   反解，两种锚点都跟手（见 [CurlAnchor]）：右半边起手 ⇒ 纸边压手指（[anchoredFoldX]），
 *   左半边起手 ⇒ 折痕穿手指（[creaseFoldX]）。锚点与翻页方向无关，两个方向同一套；
 *   翻页方向只经 [CurlPull.direction] 影响折痕锚点的横向符号（见 [leanOf]）。
 * @param fade 阴影淡出系数（0..1）。NaN = 按 [tailFade] 从进度算（点按 / 无手指）。
 *   结算从**跟手那一帧**接手时由 `FlipController` 逐帧给：拖到页角折起来时屏幕可见进度
 *   常常是负的（折痕中点在自由边之外），照 [tailFade] 算会在松手那一帧把阴影直接归零、
 *   折角整块消失（见 [CurlShape.shadowFade]）。
 * @param leanFade 倾斜扶正系数（0..1）。NaN = 跟 [fade] 同一个系数（旧口径）。跟手交接的
 *   结算里由 `FlipController` 逐帧给：翻页用 [settleLeanFade]（整段行程里平滑退回，接着松手前
 *   的角度走），回弹用 [settleLeanRewind]（倾斜跟着剩余行程退回）。
 */
internal fun curlShape(
    pageW: Float,
    pageH: Float,
    progress: Float,
    direction: Int,
    tilt: Float = 0f,
    pull: CurlPull? = null,
    restFold: Float = Float.NaN,
    fade: Float = Float.NaN,
    leanFade: Float = Float.NaN,
): CurlShape {
    val w = pageW.coerceAtLeast(1f)
    val h = pageH.coerceAtLeast(1f)
    val forward = direction >= 0
    // 进度只在**结算**里可以越界：跟手交接要把折痕中点在自由边之外的位置带过来（页角折起来
    // 时它就是负的），所以下界放到 [CURL_FOLD_BOUND]。上界多留 1 是给上一页的镜像
    // （`curlFrame` 传 `1-p`）留同样的空间。
    val p = progress.coerceIn(-CURL_FOLD_BOUND, CURL_FOLD_BOUND + 1f)

    // `direction = -1` 只给**镜像几何**用（低层几何 / 与镜像相关的测试；翻页层恒传 +1，
    // 见 [curlFrame]），所以这里按方向把倾斜一起镜像，`+1` 那条路上倾斜原样进。
    val lean0 = (if (forward) tilt else -tilt).coerceIn(-CURL_LEAN_MAX, CURL_LEAN_MAX)
    val tiltW = w * CURL_TILT_WIDTH

    // 进度头尾 10%：阴影淡出；倾斜另有扶正系数（跟手交接的翻页按整段行程退回，见下）。
    // **跟手时不淡倾斜**：起手时折痕就由手指反解、可能已经掀了一大块，再扶正只会让
    // 折痕在开头几帧歪回竖直（斜拖时纸边跟着偏）；末段那一次扶正由松手结算接手。
    // 但跟手时倾斜另受**竖向行程**约束（见 [verticalLeanCap]）：手指退回起手点时折痕
    // 一路扶正、折角面积连续收到零，不会整块消失。
    //
    // 跟手时**阴影也不淡**：扶正的口径是「进度 0 = 一页都没掀」，而跟手时进度根本不是
    // 折角的量尺（折痕听手指）。手指滑出去再滑回来，进度会先归零，折角却还有半个页宽——
    // 照进度淡影就是「阴影先消失、折角还在」，读者原话那半句「阴影逐渐消失」正是它。
    // 跟手时影的浓淡交给几何（纸抬多高、影有多宽），结算/点按照旧听进度。
    val finger = pull
    val anchored = finger != null
    // 阴影的淡出系数：跟手帧恒 1（形状听手指）；结算帧由调用方逐帧给（从跟手那一帧接手，
    // 见 [CurlShape.shadowFade]），没给就按进度算。倾斜另用下面的 leanFadeK。
    val fadeK = when {
        finger != null -> 1f
        !fade.isNaN() -> fade.coerceIn(0f, 1f)
        else -> tailFade(p)
    }
    // 倾斜的扶正系数与阴影分开：松手后倾斜按整段行程平滑退回（翻页 [settleLeanFade]）或跟着
    // 剩余行程退回（回弹 [settleLeanRewind]），阴影仍在末段淡出（[CURL_TAIL_FADE_SPAN]）。
    // 没单独给倾斜系数时沿用旧口径。
    val leanFadeK = when {
        finger != null -> 1f
        !leanFade.isNaN() -> leanFade.coerceIn(0f, 1f)
        else -> fadeK
    }
    val lean = if (finger != null) {
        lean0
    } else {
        lean0 * leanFadeK
    }

    // 纸卷退场（见文件头）：折痕贴到「纸少的那一侧」页边后，再用一个半径的行程把筒滚出页边。
    // 这一段只有 R/w 那么长，所以折痕在这里走得比手指快一倍；不这么做，筒要么被压扁
    // （按剩余纸宽收细），要么在末帧整根消失（撤层时跳一下）。
    val exit = w * CURL3D_RADIUS_RATIO
    val foldBound = w * CURL_FOLD_BOUND

    // 折痕位置：不跟手时按进度从右缘扫到左缘（旧版同款）；跟手时改听手指，
    // 锚点是**起手那一刻**定的那一种（见 [CurlAnchor]）：
    // 右半边起手 = 纸边压在手指上（[anchoredFoldX]），左半边起手 = 折痕穿过手指（[creaseFoldX]）。
    //
    // 起手接手也在这里：折痕先停在「静止位置」（`restFold`，由 [curlFrame] 按翻页方向给），
    // 再用 [CurlPull.blend] 在起手那一小段（[PULL_INTRO_MS]）里滑到锚点位置，然后完全交给手。
    // 少了这一段，起手那一帧就按锚点摆好了——屏幕右侧往右滑翻上一页、屏幕左侧往左滑翻
    // 下一页这两种起手，目标页一上来就展开大半，读者说「跳跃性太强」。
    //
    // 写成「倾斜 → 折痕」的函数，是因为折痕位置要按同一份倾斜反解（两种锚点都吃 `lean`），
    // 这样倾斜一旦调整，折痕位置跟着一致地重算。
    fun foldFor(l: Float): Float {
        val raw = when {
            finger == null -> {
                val base = if (forward) w * (1f - p) else w * p
                if (forward) {
                    if (base < exit) 2f * base - exit else base
                } else {
                    if (w - base < exit) 2f * base - (w - exit) else base
                }
            }
            finger.anchor == CurlAnchor.Crease ->
                creaseFoldX(finger, h, l, tiltW).coerceIn(-foldBound, foldBound)
            else ->
                anchoredFoldX(finger, w, h, l, tiltW).coerceIn(-foldBound, foldBound)
        }
        return if (finger != null && !restFold.isNaN() && finger.blend < 1f) {
            val k = finger.blend.coerceIn(0f, 1f)
            restFold + (raw - restFold) * k
        } else {
            raw
        }
    }

    val foldX = foldFor(lean)

    // 折痕与书脊：**两端一视同仁**（读者 2026-11-21 口径「你见过哪本书是翻不到书脊的？」）。
    //
    // 书脊这一侧不压倾斜：纸角卷过书脊是翻页本来的样子。折痕端点按倾斜走，
    // 装订边那一侧只留纸卷**退场**的一个筒径（`−R`），自由边那一侧只受 NaN 兜底
    // （见 [CURL_ENDPOINT_BOUND]）。左半边、右半边、两个翻页方向、跟手与结算用同一套；
    // 两种锚点只在「抓的是什么」上不同（纸边压在手指 / 折痕穿过手指），这里不再有第三种分支。
    //
    // **这里的夹取只是兜底，不许咬到跟手帧**：单独夹端点会把折痕这条线掰弯，
    // 「折痕穿过手指 / 纸边压在手指」当场破功（读者：「手指向上，折痕反向运行并脱离手指」）。
    // 跟手时倾斜已由 [leanOf] 按同一条边界（`−R`）收过（见 `leanWithinPageEnds`），
    // 所以下面的 `coerceIn` 在 `blend = 1` 的跟手帧上不生效；结算帧沿用松手那一刻的倾斜，
    // 同样不生效。唯一会咬到的是**起手接手**那百来毫秒（`blend < 1`，折痕正从静止位置
    // 滑向锚点）：那时折痕本来就不在手指上、还贴着书脊，形状本来就是过渡态。
    // 另外，低层调用方直接给一个超界的 `tilt`（测试、镜像几何）时，它也起作用。
    val endpointBound = w * CURL_ENDPOINT_BOUND
    val clampLo = if (forward) -exit else -endpointBound
    val clampHi = if (forward) endpointBound else w + exit
    val topX = (foldX - lean * tiltW).coerceIn(clampLo, clampHi)
    val bottomX = (foldX + lean * tiltW).coerceIn(clampLo, clampHi)

    return CurlShape(
        pageW = w,
        pageH = h,
        topX = topX,
        bottomX = bottomX,
        foldX = foldX,
        lean = lean,
        shadowFade = fadeK,
        progress = p,
        direction = direction,
        anchored = anchored,
    )
}

/**
 * 这一帧是否「一页都还没掀」：渲染层只画正面那张页（不做卷曲）。
 *
 * ## 为什么跟手时不能按进度判（读者报的「整块折角瞬间消失」）
 *
 * 结算、点按都由进度驱动几何（折痕位置 = 进度的函数），两端本来就退化 ⇒
 * 按进度收尾既精确又便宜。**跟手时不是**（[CurlShape.anchored]）：折痕位置由手指反解
 * （纸边压在手指上 / 折痕穿过手指，见 [CurlAnchor]），进度只是手指相对起手点的累计位移。
 * 读者向左滑出去、再向右滑回起手点时，
 * 进度会先掉到 0 以下，而折角还有半个页宽（真机日志：进度 −0.001 那一帧折痕仍在 1263px，
 * 折角 577px）—— 照进度收尾就是**整块折角一帧消失**，读者看到的正是「接近起手点时整个
 * 折起的页面瞬间消失，动画结束」。
 *
 * 跟手时两端的收尾因此交给几何自己：手指退回起手点，折痕跟着手指退回、折角连续收到零；
 * 翻到底则由纸卷退场把筒滚出书脊。与这半句配套的另一半是倾斜与阴影：跟手时它们也只听
 * 手指（见 [curlShape]）。上一版（`93603ce1`）只收了倾斜没管这条总闸，所以症状仍在。
 */
internal fun CurlShape.noLiftYet(): Boolean =
    !anchored && progress <= 0f && lean == 0f

/** 这一帧是否「整页已经翻完」：渲染层只画露出（目标）那张页。判据同 [noLiftYet]。 */
internal fun CurlShape.fullyTurned(): Boolean = !anchored && progress >= 1f

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
 * 进度取 `1-p`，折痕因此仍在 `w·p`；倾斜**原样带过去，不在这里取反**——倾斜的符号由
 * [leanOf] 按拖动方向与 [CurlPull.direction] 定，到这里已经是屏幕上该用的那个量。
 * 再由 [turningIsTarget] 把正面/露出页两张图换位。
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
    fade: Float = Float.NaN,
    leanFade: Float = Float.NaN,
): CurlFrame {
    val back = direction < 0
    val w = pageW.coerceAtLeast(1f)
    return CurlFrame(
        shape = curlShape(
            pageW = pageW,
            pageH = pageH,
            progress = if (back) 1f - progress else progress,
            direction = 1,
            // 倾斜原样带过去：两个方向共用同一根折痕（见 [CurlFrame]）。
            tilt = tilt,
            // 两个方向都跟手：锚点由**起手位置**定（右半边 = 纸边、左半边 = 折痕，
            // 见 [CurlAnchor]），与翻页方向无关，拖动中也不重判。
            pull = pull,
            // 静止位置（起手接手的起点，见 [pullEngage]）：下一页折痕停在自由边（页宽），
            // 上一页停在书脊外一个筒径——那一帧就是「一页都没掀」，与静止页一致。
            restFold = if (back) -w * CURL3D_RADIUS_RATIO else w,
            fade = fade,
            leanFade = leanFade,
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
 * 松手结算时，倾斜（折痕角度）在**整段行程**里平滑退回竖直。
 *
 * ## 为什么要整段退，而不是起手就扶平
 *
 * 跟手时折痕带着手指给的角度（`leanOf`）；读者往左上拖，纸角就朝左上掀起。松手之后，如果倾斜
 * 在开头几帧里就塌成 0，屏幕上看到的是：折角先原地扶平（被掀起的那块面积反而缩一下），然后
 * 折痕再竖直着横扫过去——两截动作断开，读者口径「先是回平再播放，非常不协调」。
 *
 * 所以倾斜改成跟**整段行程**一起退：松手那一帧原样接手，起手斜率 ≈ 0（先保持住手指给的角度），
 * 中段跟折痕一起转，末段再收住（斜率又回到 0，不会在收尾忽然掰一下）。曲线是 `1 − smoothstep`，
 * 两端斜率都是 0 —— 与 [pullEngage] 的接手曲线同一个形状，起手、收尾都不出速度台阶。
 *
 * ## 代价（实测记在 `docs/翻页动画设计.md` §16.11）
 *
 * 倾斜保留得更久，被掀起的那个纸角（页面角关于折痕的镜像点）在行程过半以后会**越过页顶**，
 * 末段再退回来。三维卷曲的网格按页矩形裁剪（`buildCurlMesh` 的扫线只到页边），越出页顶的那
 * 部分画不出来，屏幕上表现为纸角的轮廓贴着页顶走；这是「接着松手前的方向」必然的代价，
 * 早先按「页角绝不越过页顶」把倾斜压在起手 15% 里扶平，换来的就是那两截断开的动作。
 *
 * 与 [tailFade] 的分工：那个淡的是阴影；倾斜只由本系数（翻页）/ [settleLeanRewind]（回弹）管。
 * 窗口按**行程比例**而不是进度绝对值取：起点离终点近时整段跟着收窄，不会「还没扶正就到了」。
 */
internal fun settleLeanFade(from: Float, target: Float, v: Float): Float {
    val span = kotlin.math.abs(target - from).coerceAtLeast(1e-4f)
    val s = (kotlin.math.abs(v - from) / span).coerceIn(0f, 1f)
    return 1f - s * s * (3f - 2f * s)
}

/**
 * **回弹**结算的倾斜扶正系数（0..1）：倾斜跟着「还剩多少行程」一起退。
 *
 * 回弹是把手势倒着播：折痕退回起手位置，倾斜就该按同样的比例退回去，末帧正好竖直贴边、
 * 折角面积连续收到零。松手那一帧恒 1（从跟手帧原样接手），之后线性降到 0。
 *
 * 为什么不能沿用 [settleLeanFade]（按整段行程退回）或 [tailFade]（末段才扶正）：
 * - 整段行程退回：回弹是**倒着播**，那会把倾斜拖到末段才收，纸角先被抬上去再退回；
 *   而回弹里折痕中点常被钉在自由边之外，屏幕上的折角**全靠倾斜撑着**，
 *   倾斜在开头几十毫秒里就没了又会**整块塌掉**（真机那一手实测：折角在 12% 行程内消失）；
 * - 末段扶正：倾斜留到最后，折痕一边往回扫、倾斜一边把纸角放大着抬上去，末段再一口气扶正，
 *   读者看到的就是「页角先向上冲一下再退回」（读者 2026-11-21 报的那一条）。
 *
 * 判据见 `CurlGeometryTest.rollbackCornerDoesNotRushUpBeforeItLands`。
 */
internal fun settleLeanRewind(from: Float, target: Float, v: Float): Float {
    val span = kotlin.math.abs(target - from).coerceAtLeast(1e-4f)
    return (kotlin.math.abs(v - target) / span).coerceIn(0f, 1f)
}

/**
 * 结算里**屏幕上折痕该摆在哪**的进度：只有「折痕已经在自由边之外的回弹」把它**钉住**，
 * 其余（翻页、折痕还在页内的回弹）照旧跟着动画走。
 *
 * ## 为什么钉住（修「松手后折角/纸边乱颤」）
 *
 * 页角折起来时折痕中点在自由边之外（`from < 0`，真机那一手是 −0.626）。这一段里屏幕上那块
 * 折角**全部由倾斜撑着**：折痕一竖直，它整块就没了。所以回弹时「一边把倾斜扶正、一边把
 * 折痕扫回来」等于让折角同时受两个方向的拉扯——实测露出页面积先涨 5 帧（+0.0013/帧）再跌
 * 9 帧，屏幕上就是纸边先往外翻一下、再收回去，读者口径「折角/纸边在乱颤」。
 *
 * 钉住折痕、只让倾斜塌回平面，折角就是**原地塌平**（实测单调、连成一条），折痕本身不动。
 * 等倾斜归零，折痕扫回自由边那一小段已经**一点卷曲都没有**（竖直折痕在页外 = 平页），
 * 所以收尾那一下把进度拨回 0 是看不见的（见 `FlipController.settle` 的回弹分支）。
 *
 * 折痕还在页内（`from >= 0`，比如翻了一小半又反悔）时不能钉：那时折角是一块真的页角，
 * 必须跟着折痕一起扫回来，否则页面上会留下一块不动的折起。
 *
 * 判据是 `from < 0 && target <= 0`：回弹的终点是 0（起点在页外时 `target` 反而比 `from`
 * 大），翻页的终点是 1，所以同一套条件把「翻页」与「页内回弹」都排除在外。
 */
internal fun settleFoldProgress(from: Float, target: Float, v: Float): Float =
    if (from < 0f && target <= 0f) from else v

/**
 * 起手「接到手指上」的缓动：`t ∈ [0,1]` 映射到接手程度 `[0,1]`（缓入缓出，smoothstep）。
 *
 * 起手那一下不能直接按锚点摆折痕（见 [CurlPull.blend]）：屏幕右侧往右滑翻上一页、
 * 屏幕左侧往左滑翻下一页，锚点位置离静止位置差着大半个页宽，一帧到位就是「目标页
 * 突然展开大半」。所以先在 [PULL_INTRO_MS] 里把折痕从静止位置滑到锚点位置，到位之后
 * 完全交给跟手——后续滚动由手指逐帧决定，动画不再插手。
 *
 * 两端都收速度（缓入缓出）而不是一起步就冲：那种「一上来就走掉七成」的缓出，读者报的
 * 仍是「太快」，看着还是一记跳。读者实测后把这个时长从 150ms 放宽到 360ms。
 */
internal fun pullEngage(t: Float): Float {
    val k = t.coerceIn(0f, 1f)
    return k * k * (3f - 2f * k)
}

/** 起手接手的时长（ms），见 [pullEngage]。 */
internal const val PULL_INTRO_MS = 360f

/**
 * 折痕锚点 ⇒ 折痕在页高中线处的横坐标：**折痕直接穿过手指坐标**。
 *
 * 折痕在高度 `y` 处的横坐标是 `foldX + lean·tiltW·(2y/h − 1)`（两端 `topX`/`bottomX`
 * 夹出来的那条直线）。要求它过手指，解出
 *
 * ```
 *   foldX = 手指x + lean·tiltW·(1 − 2·手指y/h)
 * ```
 *
 * 竖直折痕（`lean = 0`）时它就等于手指 x。
 *
 * ## 为什么上一页是这一种、下一页是纸边那一种
 *
 * 下一页掀的是**当前页**：手指抓的是它朝右的那条自由边，纸边翻过去落在手指上，折痕在
 * 纸边与书脊之间（[anchoredFoldX]）。翻上一页掀的是**上一页**，它的自由边就是它翻回来
 * 时的那条前缘——也就是折痕本身，所以手指抓的就是折痕，折痕直接跟着手指走。
 * 两种锚点都由起手位置定（右半边 = 纸边、左半边 = 折痕，见 [CurlAnchor]），与翻页方向无关。
 */
internal fun creaseFoldX(pull: CurlPull, pageH: Float, lean: Float, tiltW: Float): Float {
    val h = pageH.coerceAtLeast(1f)
    val fy = pull.to.y.coerceIn(0f, h)
    return pull.to.x + lean * tiltW * (1f - 2f * fy / h)
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
 * @return 折痕中点横坐标（未夹取；调用方 [curlShape] 负责按 [CURL_FOLD_BOUND] 兜底）。
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
internal fun anchoredProgress(
    pull: CurlPull,
    pageW: Float,
    pageH: Float,
    tilt: Float,
    progress: Float,
    direction: Int = 1,
): Float {
    val w = pageW.coerceAtLeast(1f)
    val frame = curlFrame(
        pageW = w,
        pageH = pageH.coerceAtLeast(1f),
        progress = progress,
        direction = direction,
        tilt = tilt,
        pull = pull,
    )
    val fold = frame.shape.foldX
    // 折痕 ⇒ 进度按**不跟手**那条关系反解（见 [curlFrame]）：下一页 `fold = w·(1−p)`，
    // 上一页 `fold = w·p`。
    //
    // **不夹到 [0,1]**：页角折起来时折痕中点在自由边之外（`fold > w`），屏幕可见进度是负的。
    // 夹回 0 会让结算第一帧把折角整块收掉（见 [curlShape] 的 `fade` 参数）。
    return if (direction >= 0) 1f - fold / w else fold / w
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
