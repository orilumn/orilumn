package orilumn.reader.ui.reader

import orilumn.reader.data.bookmark.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Test

/** 书签清单的排序口径：章序 → 章内页首 → id（与数据库查询同序）。 */
class ReaderBookmarkPanelTest {

    @Test
    fun sortBookmarksOrdersByChapterThenPageStart() {
        val list = listOf(
            Bookmark(id = 3, bookId = 1, chapter = 2, charStart = 5, charEnd = 9),
            Bookmark(id = 1, bookId = 1, chapter = 0, charStart = 10, charEnd = 10),
            Bookmark(id = 2, bookId = 1, chapter = 2, charStart = 1, charEnd = 4),
            Bookmark(id = 4, bookId = 1, chapter = 2, charStart = 5, charEnd = 12),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L), sortBookmarks(list).map { it.id })
    }
}
