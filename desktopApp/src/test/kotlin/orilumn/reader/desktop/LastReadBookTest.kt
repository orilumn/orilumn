package orilumn.reader.desktop

import orilumn.reader.ui.shelf.ShelfBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「启动时继续阅读」挑哪一本：读过、文件还在的书里取最近打开的那本。
 *
 * 只测挑选判据本身（不碰 Compose / 数据库）：桌面壳启动时用它决定要不要跳过书架。
 */
class LastReadBookTest {

    private fun book(id: Long, readTime: Long?, path: String = "/books/$id.epub") =
        ShelfBook(id = id, title = "书$id", filePath = path, readTime = readTime)

    @Test
    fun `picks the most recently opened book`() {
        val books = listOf(
            book(1, 100L),
            book(2, 900L),
            book(3, 500L),
        )
        assertEquals(2L, lastReadBook(books) { true }?.id)
    }

    @Test
    fun `books never opened are not candidates`() {
        val books = listOf(book(1, null), book(2, null), book(3, 42L))
        assertEquals(3L, lastReadBook(books) { true }?.id)
        assertNull(lastReadBook(listOf(book(1, null), book(2, null))) { true })
    }

    @Test
    fun `a book whose file is gone falls back to the next one`() {
        val books = listOf(book(1, 100L), book(2, 900L, path = "/gone.epub"), book(3, 500L))
        // 最新的那本（2）文件没了 → 退到下一本读过的（3），不是退到最老的（1）
        assertEquals(3L, lastReadBook(books) { it != "/gone.epub" }?.id)
        // 全都读不了 → 不开书，留在书架
        assertNull(lastReadBook(books) { false })
    }

    @Test
    fun `empty shelf stays on the shelf screen`() {
        assertNull(lastReadBook(emptyList()) { true })
    }
}
