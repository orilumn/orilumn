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
 *  - [fontSlotFor]：`ReaderActivity.fontPairing` 的字体槽位路由（正文/标题/代码三槽），对齐
 *    engine-skia `SkParagraphFactory.resolveFamily` 的 CODE_TAGS 口径；
 *  - [shiftToPageFrame]：[ReaderPageCanvas] 把宿主给出的章节内**绝对 Y** 行窗口平移进页面坐标系。
 */
object ReaderMath {

    /** 点按/滑动的触摸松弛（px），对应旧 `scaledTouchSlop` 的近似值（宿主可按密度覆盖）。 */
    const val TAP_SLOP = 24f

    /** 无位移即举起判为点按的最长间隔（ms），对应旧 `FlipGestureDetector` 的 400ms。 */
    const val TAP_MAX_MS = 400L

    /**
     * 链接点按后的三区误触防抖窗（ms）：一次链接跳转成功后，此窗口内的三区点按
     * （翻页/栏显隐）直接吞掉——手抖的第二下常落在新页空白处，否则会被判成翻页，
     * 表现为"点链接跳走又立刻被翻回来"。链接本身不受影响（仍优先命中）。
     */
    const val LINK_TAP_DEBOUNCE_MS = 500L

    /** 亮度取值范围底部（-50：系统最暗 + 遮罩继续压暗到 0.8 alpha）。 */
    const val MIN_BRIGHTNESS = -50

    /** 亮度取值范围顶部（100 = 跟随系统）。 */
    const val MAX_BRIGHTNESS = 100

    /** code-like 标签集合，与 engine-skia 的 CODE_TAGS 一致。 */
    val CODE_TAGS = setOf("pre", "code", "kbd", "samp")

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
     * 链接防抖：上次链接点按发生在 [LINK_TAP_DEBOUNCE_MS] 内时，三区动作应吞掉。
     * 纯函数，时钟由调用方喂手势抬起时间（`uptimeMillis` 单调递增，无需平台时钟）。
     */
    fun linkTapDebounced(nowMs: Long, lastLinkMs: Long): Boolean {
        val dt = nowMs - lastLinkMs
        return lastLinkMs > 0L && dt >= 0L && dt < LINK_TAP_DEBOUNCE_MS
    }

    /**
     * 手势主轴判定，复刻旧 `FlipGestureDetector`：横向位移先越过 slop 且明显大于纵向（>1.2x）→ 水平翻页；
     * 纵向先越过 slop 且明显大于横向 → 垂直（亮度）手势；都未越过 → 未定型（点按）。
     */
    fun gestureAxis(dx: Float, dy: Float, slop: Float = TAP_SLOP): Axis = when {
        abs(dx) > slop && abs(dx) > abs(dy) * 1.2f -> Axis.HORIZONTAL
        abs(dy) > slop && abs(dy) > abs(dx) * 1.2f -> Axis.VERTICAL
        else -> Axis.NONE
    }

    /** 水平滑方向：左滑（dx<0）→ +1（下一页），右滑 → -1（上一页）。 */
    fun flipDirection(dx: Float): Int = if (dx < 0f) 1 else -1

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
     * 字体切换的槽位路由（复刻 `ReaderActivity.fontPairing`）：code-like（monospace 或 pre/code）→ 代码槽，
     * h1..h6 标题 → 标题槽，其余 → 正文槽；返回的即 `ReaderSettings.fontBody/fontTitle/fontCode` 的别名
     * （空串 = 该类型跟随原书）。宿主把该别名解析进 Skia FontCollection。
     */
    fun fontSlotFor(tag: String?, monospace: Boolean, body: String, title: String, code: String): String {
        val codeLike = monospace || tag in CODE_TAGS
        val heading = tag != null && tag.length == 2 && tag[0] == 'h' && tag[1].digitToIntOrNull() != null
        return when {
            codeLike -> code
            heading -> title
            else -> body
        }
    }

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