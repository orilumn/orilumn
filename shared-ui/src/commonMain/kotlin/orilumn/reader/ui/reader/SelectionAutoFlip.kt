package orilumn.reader.ui.reader

import orilumn.reader.engine.skia.DrawLine

/** 选区拖到页边后自动翻页的触发带（dp，距屏幕左/右缘）。 */
internal const val SELECTION_AUTO_FLIP_EDGE_DP: Float = 24f

/** 首次进入页边后等待多久再翻（ms）；按住不动时后续每 [SELECTION_AUTO_FLIP_REPEAT_MS] 翻一页。 */
internal const val SELECTION_AUTO_FLIP_FIRST_MS: Long = 300L
internal const val SELECTION_AUTO_FLIP_REPEAT_MS: Long = 400L

/**
 * 手指 x 落在左/右页边带内时给出翻页方向：-1 左（上一页）/ 1 右（下一页）/ 0 不在边带。
 *
 * 用屏幕页边而不是版心边：手柄贴在版心内缘，手指继续往页边距里拖才进入触发带，
 * 与「拖到页边缘」的移动端习惯一致。
 */
internal fun selectionAutoFlipDirection(xPx: Float, widthPx: Float, edgePx: Float): Int = when {
    xPx <= edgePx -> -1
    xPx >= widthPx - edgePx -> 1
    else -> 0
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
