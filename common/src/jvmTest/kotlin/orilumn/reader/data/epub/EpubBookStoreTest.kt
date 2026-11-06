package orilumn.reader.data.epub

import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开书骨架缓存的四条锁：存什么读回什么、身份/构建号一变就当 miss、坏文件当 miss、
 * 一本书只留一个文件（不留临时文件残骸）。
 */
class EpubBookStoreTest {

    private fun tempRoot(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "orilumn-bookindex-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun sampleBook(): EpubBook = EpubBook(
        title = "GIMP 3.0 用户手册",
        author = "GIMP 文档团队",
        spine = listOf(
            SpineItem(0, "OEBPS/chap_0001.xhtml", null, "application/xhtml+xml"),
            SpineItem(1, "OEBPS/chap_0005.xhtml", "sec1", "application/xhtml+xml"),
            SpineItem(2, "OEBPS/chap_0029.xhtml", null, "application/xhtml+xml"),
        ),
        toc = listOf(
            TocItem(
                label = "第 3 章：GIMP 入门指南",
                index = 1,
                fragment = "sec1",
                children = listOf(TocItem("入门", 1, "sec1a")),
            ),
            TocItem("附录 E：缺少帮助文档", 2, null),
        ),
        cover = "OEBPS/images/gimp-navigation.png",
        uid = "urn:uuid:1234",
        obfuscatedFonts = setOf("OEBPS/fonts/book.otf"),
    )

    @Test
    fun `round trip keeps spine toc cover uid and obfuscated fonts`() {
        val store = EpubBookStore(FileSystem.SYSTEM, tempRoot().absolutePath.toPath())
        val book = sampleBook()
        store.write(bookId = 40, identity = "a.epub|100|1", appVersion = 20, book = book)

        val read = store.read(bookId = 40, identity = "a.epub|100|1", appVersion = 20)
        assertEquals(book, read)
    }

    @Test
    fun `identity mismatch is a miss`() {
        val store = EpubBookStore(FileSystem.SYSTEM, tempRoot().absolutePath.toPath())
        store.write(bookId = 40, identity = "a.epub|100|1", appVersion = 20, book = sampleBook())
        // 文件被换掉（体积/时间变了）⇒ 旧骨架不许再冒充这本书。
        assertNull(store.read(bookId = 40, identity = "a.epub|200|2", appVersion = 20))
        // 取不到身份（reader 不支持）⇒ 不认任何落盘骨架。
        assertNull(store.read(bookId = 40, identity = null, appVersion = 20))
    }

    @Test
    fun `app version mismatch is a miss`() {
        val store = EpubBookStore(FileSystem.SYSTEM, tempRoot().absolutePath.toPath())
        store.write(bookId = 40, identity = "a.epub|100|1", appVersion = 20, book = sampleBook())
        assertNull(store.read(bookId = 40, identity = "a.epub|100|1", appVersion = 21))
    }

    @Test
    fun `corrupt or empty file is a miss`() {
        val root = tempRoot()
        val store = EpubBookStore(FileSystem.SYSTEM, root.absolutePath.toPath())
        val f = store.file(40)
        f.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
        FileSystem.SYSTEM.write(f) { write("{not json".encodeToByteArray()) }
        assertNull(store.read(bookId = 40, identity = "a.epub|100|1", appVersion = 20))
    }

    @Test
    fun `one book keeps exactly one file and rewriting replaces it`() {
        val root = tempRoot()
        val store = EpubBookStore(FileSystem.SYSTEM, root.absolutePath.toPath())
        val book = sampleBook()
        repeat(3) { i ->
            store.write(bookId = 40, identity = "a.epub|100|$i", appVersion = 20, book = book)
        }
        val files = File(root, "structure/book_40").listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertEquals(listOf(EpubBookStore.FILENAME), files)
        assertTrue(File(root, "structure/book_40/${EpubBookStore.FILENAME}").length() > 0)
        // 最后一次写的身份才认。
        assertEquals(book, store.read(40, "a.epub|100|2", 20))
    }
}
