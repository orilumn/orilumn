package orilumn.reader.ui.reader

import orilumn.reader.data.bookmark.Bookmark

/** 书签摘录上限：清单一行最多两行字，再长也只是被省略号吞掉。 */
internal const val BookmarkQuoteMaxChars = 200

/** 当前页在原书空间里的锚点：章号 + 页首，以及页尾后一个字（空页与页首相同）。 */
private data class PageAnchor(
    val chapter: Int,
    val start: Int,
    /** 本页覆盖的 [start, endExclusive)；null = 没有可用的原书区间，按页首一个字比。 */
    val endExclusive: Int?,
    /** 落库用的页尾后一个字；构造时与 [start] 同值也不会越界。 */
    val charEnd: Int,
)

/** 当前页 → 原书空间锚点（与标注 / 阅读进度同口径）。 */
private fun ReaderHost.pageAnchorOf(pos: ReaderPos): PageAnchor {
    val chapter = pos.chapter
    val slice = pos.slice
    val range = originalRangeOf(chapter, slice.charStart, slice.charEnd)
    return when {
        // 宿主没实现换算（老实现 / 测试桩）：按同一坐标处理，与标注的缺省口径一致。
        range == null -> PageAnchor(
            chapter = chapter,
            start = slice.charStart.coerceAtLeast(0),
            endExclusive = null,
            charEnd = maxOf(slice.charEnd, slice.charStart, 0),
        )
        range.isEmpty() -> PageAnchor(
            chapter = chapter,
            start = range.first.coerceAtLeast(0),
            endExclusive = null,
            charEnd = range.first.coerceAtLeast(0),
        )
        else -> PageAnchor(
            chapter = chapter,
            start = range.first.coerceAtLeast(0),
            endExclusive = range.last + 1,
            charEnd = range.last + 1,
        )
    }
}

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
    val anchor = pageAnchorOf(pos)
    val slice = pos.slice
    val quote = textOf(anchor.chapter, 0, Int.MAX_VALUE)
        ?.let { plainTextOf(anchor.chapter).slice(it, slice.charStart, slice.charEnd).take(BookmarkQuoteMaxChars) }
        ?.trim()
        ?.ifBlank { null }
    return addBookmark(
        Bookmark(
            bookId = 0,
            chapter = anchor.chapter,
            charStart = anchor.start,
            charEnd = anchor.charEnd,
            quote = quote,
        ),
    )
}

/** 当前页有没有书签；读取失败与「没有」分开，免得把失败当成可以重新加一条。 */
internal sealed interface BookmarkLookup {
    data class Found(val bookmark: Bookmark) : BookmarkLookup
    data object None : BookmarkLookup
    data object Failed : BookmarkLookup
}

/** 当前页上已有的书签（页首落在本页覆盖的原书区间内即算）。 */
internal suspend fun ReaderHost.lookupBookmarkOnPage(pos: ReaderPos): BookmarkLookup {
    val anchor = pageAnchorOf(pos)
    val all = bookmarksForBook() ?: return BookmarkLookup.Failed
    val hit = all.firstOrNull {
        it.chapter == anchor.chapter && if (anchor.endExclusive == null) {
            it.charStart == anchor.start
        } else {
            it.charStart >= anchor.start && it.charStart < anchor.endExclusive
        }
    }
    return if (hit != null) BookmarkLookup.Found(hit) else BookmarkLookup.None
}

/** 下拉一次的结果：加了 / 删了 / 失败。 */
internal sealed interface BookmarkToggleResult {
    data class Added(val bookmark: Bookmark) : BookmarkToggleResult
    data class Removed(val bookmark: Bookmark) : BookmarkToggleResult
    data object Failed : BookmarkToggleResult
}

/**
 * 下拉切换当前页书签：没有就加，有就删。
 *
 * 「有」按当前页覆盖的原书区间判，不是只比页首一个点：换过版面后旧书签落在本页里，
 * 下拉一次仍然应该删掉它，而不是在旁边再记一条。
 */
internal suspend fun ReaderHost.toggleBookmarkAt(pos: ReaderPos): BookmarkToggleResult =
    when (val hit = lookupBookmarkOnPage(pos)) {
        BookmarkLookup.Failed -> BookmarkToggleResult.Failed
        is BookmarkLookup.Found -> if (deleteBookmark(hit.bookmark.id)) {
            BookmarkToggleResult.Removed(hit.bookmark)
        } else {
            BookmarkToggleResult.Failed
        }
        BookmarkLookup.None -> addBookmarkAt(pos)
            ?.let { BookmarkToggleResult.Added(it) }
            ?: BookmarkToggleResult.Failed
    }
