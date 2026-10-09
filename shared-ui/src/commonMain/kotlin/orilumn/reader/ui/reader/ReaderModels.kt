package orilumn.reader.ui.reader

import orilumn.reader.engine.skia.DrawLine
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * S28 阅读面纯逻辑（H 阶段"画布/手势/进度/亮度遮罩/字体切换"中可单测的部分）。
 *
 * 全部为无副作用纯函数，取自 Android 旧管线的现场语义（ReaderActivity / FlipGestureDetector /
 * BrightnessOverlayView / ReaderBars），迁移时保持行为一致：
 *  - [tapZone] / [gestureAxis] / [flipDirection] / [verticalBrightnessAllowed] / [brightnessFrac]：
 *    来自 `FlipGestureDetector` 的三区点按、横竖滑判定与亮度手势换算；
 *  - [brightnessFromDelta]：`ReaderActivity.applyBrightnessGesture` 的 `起始值 + 累计比例×100` 落位；
 *  - [dimAlphaOf] / [warmAlphaOf] / [ReaderWarmColor]：`ReaderActivity.applyLightOverlay` 与
 *    `BrightnessOverlayView` 的遮罩 alpha 换算（纯绘制，不碰系统背光）；
 *  - [progressPercent]：底栏进度百分比（`ReaderBars`）。
 *  - [shiftToPageFrame]：[ReaderPageCanvas] 把宿主给出的章节内**绝对 Y** 行窗口平移进页面坐标系。
 *  （字体三槽路由已归位 `orilumn.reader.engine.text.FontSlots`，UI 层不再持有。）
 */
object ReaderMath {

    /** 点按/滑动的触摸松弛（px），对应旧 `scaledTouchSlop` 的近似值（宿主可按密度覆盖）。 */
    const val TAP_SLOP = 24f

    /**
     * 横向手势的**最小即时判定位移**（px）：首个 MOVE 事件超过它就接管手势，不再等 slop。
     *
     * 取 2px：只排掉「完全没动」的数值噪声（触摸抖动通常 < 1px），任何有意的
     * 手指移动都立刻越过。理由见 [gestureAxis] 的 KDoc（真机 FLIPLAT 实测）。
     */
    const val FLING_AXIS_MIN_PX = 2f

    /** 无位移即举起判为点按的最长间隔（ms），对应旧 `FlipGestureDetector` 的 400ms。 */
    const val TAP_MAX_MS = 400L

    /** 亮度取值范围底部（-50：系统最暗 + 遮罩继续压暗到 0.8 alpha）。 */
    const val MIN_BRIGHTNESS = -50

    /** 亮度取值范围顶部（100 = 跟随系统）。 */
    const val MAX_BRIGHTNESS = 100

    /** 护眼暖色（对应旧 `BrightnessOverlayView` 的 `Color.rgb(255, 178, 125)`）。 */
    const val ReaderWarmColor = 0xFFFFB27DL

    enum class Axis { NONE, HORIZONTAL, VERTICAL }

    /** 三区点按：左侧占用 w/3 → -1（上一页），右侧占用 w/3 → +1（下一页），中部 → 0（呼出上下栏）。 */
    fun tapZone(x: Float, width: Float): Int = when {
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    /**
     * 手势主轴判定。
     *
     * ## 两条路径：快速通道（手指一动就响应）+ slop 通道（防抖）
     *
     * 早先只有 slop 一条（`abs(dx) > slop && abs(dx) > abs(dy) * 1.2`，slop≈20px）。
     * 真机实测（`Orilumn.FLIPLAT axis` 日志，density 400 / 1840×2800）：
     *
     * ```
     * axis dir=1 dx=-35 dy=0 slop=20 lat=184ms
     * axis dir=1 dx=-34 dy=-1 slop=20 lat=77ms
     * axis dir=1 dx=-22 dy=0 slop=20 lat=91ms
     * ```
     *
     * `dx` 只有 22~47px（slop 刚过），`dy≈0`（1.2 倍轻松满足），可按下→定轴仍要
     * 67~184ms。而定轴之后 `axis2move` 是**1ms**——代码零延迟。
     *
     * 也就是说：这几十毫秒不是我们算得慢，而是**手指真的只移动了 22~47px**，
     * 而人在「刚要划」到「划出 30px」之间的物理起动就要几十毫秒。moon+ 之所以
     * 「手指一动立刻响应」，是因为它**没有 slop**：第一个 MOVE 事件就接管。
     *
     * 故加一条快速通道：横向有 [FLING_AXIS_MIN_PX]（2px，纯排抖动）以上位移、
     * 且纵向不超过横向的 1.2 倍时**立刻**判水平——不等 slop。
     *
     * 代价与取舍：轻微手抖可能被当成翻页。但按当前阈值（位移 55px 才 commit、
     * 末段回拉还可否决），抖一下不会真的翻页，最多是画面轻微动一下然后弹回。
     * 这与用户诉求「开始滑动大概率就是要翻页」一致。
     *
     * 纵向亮度手势仍走 slop 通道：它要的是「整根手指在左/右 1/3 竖划」这种
     * 明确动作，早判定会让横滑时的轻微纵向抖动抢走手势。
     */
    fun gestureAxis(dx: Float, dy: Float, slop: Float = TAP_SLOP): Axis = when {
        // 快速通道：横向一动就接管（不等 slop），纵向必须明显更小。
        abs(dx) >= FLING_AXIS_MIN_PX && abs(dx) > abs(dy) * 1.2f -> Axis.HORIZONTAL
        // slop 通道：纵向手势仍需越过 slop（防抖，见上）。
        abs(dy) > slop && abs(dy) > abs(dx) * 1.2f -> Axis.VERTICAL
        else -> Axis.NONE
    }

    /** 水平滑方向：左滑（dx<0）→ +1（下一页），右滑 → -1（上一页）。 */
    fun flipDirection(dx: Float): Int = if (dx < 0f) 1 else -1

    /**
     * 抬手时判定「这次到底算不算点按」：**任一轴越过 slop 就不算**。
     *
     * 与 [gestureAxis] 用同一个 slop，但**不要求定轴**：定轴还额外要求「明显大于
     * 另一轴 1.2 倍」，那是为区分横向翻页与纵向亮度；判定「有没有动过」不需要
     * 这个区分——挪了就是挪了。
     *
     * 为什么要单列：手势层早先以 `dragDir == 0` 代理「没动过」，而 `dragDir` 只在
     * 定轴为 HORIZONTAL 后才赋值，于是少量滑动（未过 slop、或纵向占优被判成
     * VERTICAL）会带着 `dragDir == 0` 落进点按分支——正文被点掉、栏弹出、
     * 翻页没发生。症状是「轻轻一划变成了点击」，不崩不报错，最难自查。
     */
    fun movedBeyondTapSlop(dx: Float, dy: Float, slop: Float = TAP_SLOP): Boolean =
        abs(dx) > slop || abs(dy) > slop

    /** 阅读面键盘动作（桌面三端统一）：左右箭头翻页，Esc 等效中部点按。
     *  上/下箭头刻意不绑——将来滚动阅读模式用它们滚屏；面板内上下另走焦点遍历。 */
    enum class ReaderKeyAction { Prev, Next, MiddleTap }

    /** 键盘映射纯函数：命中的返回动作，其余回 null（调用方不消费）。 */
    fun keyAction(key: androidx.compose.ui.input.key.Key): ReaderKeyAction? = when (key) {
        androidx.compose.ui.input.key.Key.DirectionLeft -> ReaderKeyAction.Prev
        androidx.compose.ui.input.key.Key.DirectionRight -> ReaderKeyAction.Next
        androidx.compose.ui.input.key.Key.Escape -> ReaderKeyAction.MiddleTap
        else -> null
    }

    /**
     * 落点是否在已显露的上下栏内：是则该手势归栏（栏按钮/进度滑条自己处理），
     * 翻页层不得翻页/调亮度/三区点按——否则点栏按钮时手指稍一漂移就会误翻页。
     * 栏高为实测 px（`ReaderBars` 经 `onSizeChanged` 回抛）；为 0（未测到）即不门控该侧。
     */
    fun downInBars(
        barsVisible: Boolean,
        downY: Float,
        viewH: Float,
        topBarH: Float,
        botBarH: Float,
    ): Boolean {
        if (!barsVisible) return false
        if (topBarH > 0f && downY < topBarH) return true
        if (botBarH > 0f && downY > viewH - botBarH) return true
        return false
    }

    /** 垂直亮度手势是否允许：仅屏幕左/右 1/3 且对应开关打开（单指），与旧逻辑一致。 */
    fun verticalBrightnessAllowed(zone: Int, leftEnabled: Boolean, rightEnabled: Boolean): Boolean =
        (zone == -1 && leftEnabled) || (zone == 1 && rightEnabled)

    /** 累计纵向位移 → 亮度比例（相对屏高，向上为正，约 -1..1）。 */
    fun brightnessFrac(dy: Float, viewportHeight: Float): Float =
        if (viewportHeight > 0f) -dy / viewportHeight else 0f

    /** 亮度手势落位：起始值 + 比例×100，收敛到 [min]..[max]（默认 -50..100）。 */
    fun brightnessFromDelta(
        start: Int,
        fraction: Float,
        min: Int = MIN_BRIGHTNESS,
        max: Int = MAX_BRIGHTNESS,
    ): Int = (start + fraction * 100f).roundToInt().coerceIn(min, max)

    /** 压暗遮罩 alpha（0..0.8）：亮度 < 0 时用黑遮罩把系统最暗继续压暗。 */
    fun dimAlphaOf(brightness: Int): Float =
        if (brightness < 0) (-brightness / 50f).coerceIn(0f, 0.8f) else 0f

    /** 护眼暖色遮罩 alpha：0..100 → 0..0.22（不随日/夜变化）。 */
    fun warmAlphaOf(eyeProtectionLevel: Int): Float =
        if (eyeProtectionLevel > 0) (eyeProtectionLevel / 100f) * 0.22f else 0f

    /** 底栏百分比显示（0..100 整数），复刻 `ReaderBars` 的 `(fraction*100).toInt()`。 */
    fun progressPercent(fraction: Float): Int = (fraction.coerceIn(0f, 1f) * 100).toInt()

    /**
     * 把章节内**绝对 Y** 的行窗口平移进页面坐标系：每行 yTop/yBottom 减去 [shift]。
     *
     * [ReaderHost.pageLines] 返回的行是章节全局绝对坐标（LineWindowDrawer 的"分页/滚动共用同一组绝对
     * Y"设计）；当前 [ReaderPageCanvas] 只画一页的可视窗口，故按窗口首行高度平移到本页顶端。
     */
    fun shiftToPageFrame(lines: List<DrawLine>, shift: Int): List<DrawLine> {
        if (lines.isEmpty() || shift == 0) return lines
        return lines.map { it.copy(yTop = it.yTop - shift, yBottom = it.yBottom - shift) }
    }

    /**
     * P4-c2u: 页内点按的对齐锚点 Y（复刻 [ReaderPageCanvas]：行/图/背景三者最小 —
     * 只取行最小会让行窗内首图跑到屏外）。任一为 null 即忽略；全空回 null（调用方退回三区行为）。
     */
    fun pageAnchorY(lineMin: Int?, imgMin: Int?, bgMin: Int?): Int? =
        listOfNotNull(lineMin, imgMin, bgMin).minOrNull()

    /**
     * P4-c2u: 点按行命中（纯几何）：点按 (tapX, tapY) → (行, 段落内 x, 行内 y)，供字形反查。
     * 坐标约定（与 [ReaderPageCanvas] 绘制侧同式，错一次即整页偏，见 LinkTap 定点记录）：
     * x 为内容区相对坐标（调用方已减 contentLeft；段落原点 = xLeft + 首行缩进）；
     * y 为屏坐标（行窗 `yTop - shift` 已含 contentTop，调用方不得再减）。
     * 行区间为页坐标系 [yTop-shift, yBottom-shift)；miss 回 null。
     */
    fun tapLineAt(lines: List<DrawLine>, shift: Int, tapX: Float, tapY: Float): Triple<DrawLine, Float, Float>? {
        for (line in lines) {
            val top = (line.yTop - shift).toFloat()
            val bottom = (line.yBottom - shift).toFloat()
            if (tapY >= top && tapY < bottom) {
                val xInParagraph = tapX - line.xLeft - line.firstLineIndentPx.coerceAtLeast(0f)
                return Triple(line, xInParagraph, tapY - top)
            }
        }
        return null
    }
}

/** 亮度手势进行中的底栏滑块状态（只持当前亮度值驱动 UI），对应 `ReaderActivity.BrightnessGestureUi`。 */
data class BrightnessGestureUi(val brightness: Int)