package orilumn.reader.ui.reader

import orilumn.reader.engine.skia.DrawLine

/**
 * 选区拖到页边后自动翻页的触发带（dp）。
 *
 * 触发带按**正文**算，不按屏幕边算：手指要越过正文左/右缘再往外 [SELECTION_AUTO_FLIP_PAST_TEXT_DP]
 * 才算进带。按屏幕边算时，边距小的书（边距滑块最小 0）整条行末都落在带里，拖到行末就误翻页。
 *
 * 页边距窄到放不下 [SELECTION_AUTO_FLIP_PAST_TEXT_DP] 时，退回屏幕最外
 * [SELECTION_AUTO_FLIP_EDGE_DP] 的保底带 —— 零边距的书仍然能跨页，代价是带子会压住最后几个字。
 */
internal const val SELECTION_AUTO_FLIP_PAST_TEXT_DP: Float = 8f
internal const val SELECTION_AUTO_FLIP_EDGE_DP: Float = 12f

/** 翻过一页后要退回正文区多远才重新上膛（dp）：回差，避免手指停在边界上来回抖着连翻。 */
internal const val SELECTION_AUTO_FLIP_REARM_DP: Float = 6f

/**
 * 卡片让位时给选区留的呼吸距离（dp）：页面只会推到「选区下沿 + 这个距离」刚好高过卡片上沿，
 * 推得刚刚好，不多推（多推会把上半页无谓地推出屏幕）。
 */
internal const val INPUT_SHIFT_GAP_DP: Float = 8f

/**
 * 首次进入页边后等待多久再翻（ms）。
 *
 * **一次进入只翻一页**：翻完必须退回正文区（[SELECTION_AUTO_FLIP_REARM_DP] 回差）再进带才会翻下一页。
 * 早先是「按住不放每 400ms 再翻」，读者停在页边等选区跟过来时会被连翻两三页。
 */
internal const val SELECTION_AUTO_FLIP_FIRST_MS: Long = 350L

/** 翻页没成（邻页未就绪 / 导航被丢弃）时的重试间隔（ms）；成功即停，不再连翻。 */
internal const val SELECTION_AUTO_FLIP_RETRY_MS: Long = 200L

/** 手柄拖到视口上下边界时整页自动滚动的步长（dp）与间隔（ms）：慢到看得清、快到不用等。 */
internal const val PAGE_SCROLL_STEP_DP: Float = 12f
internal const val PAGE_SCROLL_STEP_MS: Long = 40L

/** 卡片让位时「拖到视口上下边界」的判定带（dp，距视口上沿 / 卡片上沿）。 */
internal const val PAGE_SCROLL_EDGE_DP: Float = 24f

/**
 * 翻页触发带（屏幕坐标）：`x <= left` 或 `x >= right` 才算进带。
 *
 * 用**正文缘**而不是屏幕边：手柄贴在版心内缘，手指继续往页边距里拖才进入触发带，
 * 与「拖到页边缘」的移动端习惯一致；边距再窄也不会让行末的字落进带里。
 */
internal data class SelectionFlipZone(val left: Float, val right: Float) {

    /** 手指 x 的翻页方向：-1 左（上一页）/ 1 右（下一页）/ 0 不在带内。 */
    fun directionOf(xPx: Float): Int = when {
        xPx <= left -> -1
        xPx >= right -> 1
        else -> 0
    }

    /**
     * 手指是否已经退回正文区（离带内边界至少 [insetPx]）。
     *
     * 翻过一页后必须满足这一条才重新上膛：手指停在带里（哪怕只动 y）不会再翻，
     * 于是「拖到页边」与「翻几页」是一一对应的，不会按住不动就连翻。
     */
    fun hasLeftBand(xPx: Float, insetPx: Float): Boolean =
        xPx > left + insetPx && xPx < right - insetPx
}

/**
 * 按正文左右缘算出触发带。
 *
 * 正常情况：正文缘再往外 [pastTextPx]。页边距窄到放不下时退回屏幕最外 [edgePx] 的保底带
 * （零边距的书仍可跨页；此时带子不可避免地压住最后几个字，这是「还能翻」的代价）。
 */
internal fun selectionFlipZone(
    widthPx: Float,
    contentLeftPx: Float,
    contentRightPx: Float,
    pastTextPx: Float,
    edgePx: Float,
): SelectionFlipZone {
    val left = (contentLeftPx - pastTextPx).takeIf { it >= edgePx } ?: edgePx
    val right = (contentRightPx + pastTextPx).takeIf { it <= widthPx - edgePx }
        ?: (widthPx - edgePx)
    return SelectionFlipZone(left, right)
}

/** 当前页是否已顶到本章首/末页（自动翻页到此停住，不跨章）。 */
internal fun selectionAtChapterBoundary(
    charStart: Int,
    charEnd: Int,
    textLength: Int,
    direction: Int,
): Boolean = if (direction > 0) charEnd >= textLength else charStart <= 0

/**
 * 新页上「最靠近手指 y 的那一行」的靠边字符（章内全局偏移）。
 *
 * 翻页后手指通常还在页边距里，x 已超出可见字形范围，[readerCharOffsetAt] 会 miss；
 * 这里只按 y 找最近行，再按翻页方向取该行首/末可见字：
 *  - direction < 0（上一页）：行首字，选区向左接上；
 *  - direction > 0（下一页）：行末字，选区向右接上。
 *
 * 行 y 为章节绝对 Y，[shift] 与 [readerPageShift] 同口径；返回 null = 本页没有可接续的字。
 */
internal fun selectionSideOffset(
    lines: List<DrawLine>,
    shift: Int,
    yPx: Float,
    direction: Int,
): Int? {
    val line = lines.asSequence()
        .filter { !it.range.isEmpty() }
        .minByOrNull { line ->
            val top = (line.yTop - shift).toFloat()
            val bottom = (line.yBottom - shift).toFloat()
            when {
                yPx < top -> top - yPx
                yPx >= bottom -> yPx - bottom
                else -> 0f
            }
        } ?: return null
    val index = if (direction < 0) line.range.first else line.range.last
    return line.charBase + index
}
