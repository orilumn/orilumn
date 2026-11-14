package orilumn.reader.ui.reader

import orilumn.reader.data.bookmark.Bookmark

/** 书签摘录上限：清单一行最多两行字，再长也只是被省略号吞掉。 */
internal const val BookmarkQuoteMaxChars = 200

/**
 * 把**当前页**落成一条书签（用户层到宿主的唯一入口，平台壳只传当前位置）。
 *
 * 两件事都在这里做完，两个平台不必各写一遍：
 *  - 锚点从显示空间换到**原书空间**（与标注 / 阅读进度同口径，换版面不漂）；
 *  - 摘录取本页可见文字，并按 [plainTextOf] 摘掉审阅痕迹与中西接缝上的零宽空白
 *    （与标注摘录同一口，痕迹不会漏进清单）。
 *
 * 同一页已有书签时由 [ReaderHost.addBookmark] 返回已有那条，不会长出重复行。
 */
suspend fun ReaderHost.addBookmarkAt(pos: ReaderPos): Bookmark? {
    val chapter = pos.chapter
    val slice = pos.slice
    val range = originalRangeOf(chapter, slice.charStart, slice.charEnd)
    val start: Int
    val end: Int
    if (range == null) {
        // 宿主没实现换算（老实现 / 测试桩）：按同一坐标处理，与标注的缺省口径一致。
        start = slice.charStart
        end = slice.charEnd
    } else {
        start = range.first.coerceAtLeast(0)
        end = if (range.isEmpty()) start else range.last + 1
    }
    val quote = textOf(chapter, 0, Int.MAX_VALUE)
        ?.let { plainTextOf(chapter).slice(it, slice.charStart, slice.charEnd).take(BookmarkQuoteMaxChars) }
        ?.trim()
        ?.ifBlank { null }
    return addBookmark(
        Bookmark(
            bookId = 0,
            chapter = chapter,
            charStart = start,
            charEnd = maxOf(end, start),
            quote = quote,
        ),
    )
}
