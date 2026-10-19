package orilumn.reader.desktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `epubContentMatches` 的锁：启动开书时书架副本与源文件的**内容**比对。
 *
 * 逐字节比对的老口径被两类「内容没变」的差异误伤：重新打包（成员顺序 / 压缩方式 /
 * 时间戳变化）与阅读器回写的书签文件 —— 都会误触发覆盖重导、换掉书 id 冲掉阅读进度。
 */
class EpubContentMatchesTest {

    /** 打一个临时 zip（模拟 EPUB 容器；内容相同、打包参数不同也算同一本书）。 */
    private fun zip(vararg entries: Pair<String, ByteArray>): File {
        val f = File.createTempFile("epub-content-", ".epub").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream().buffered()).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun `same members match regardless of member order`() {
        val a = zip("mimetype" to "application/epub+zip".toByteArray(), "x.html" to "<p>正文</p>".toByteArray())
        val b = zip("x.html" to "<p>正文</p>".toByteArray(), "mimetype" to "application/epub+zip".toByteArray())
        assertTrue(epubContentMatches(a, b))
    }

    @Test
    fun `calibre bookmarks churn does not count as content change`() {
        val base = "mimetype" to "application/epub+zip".toByteArray()
        val a = zip(base, "x.html" to "<p>正文</p>".toByteArray(), "META-INF/calibre_bookmarks.txt" to "old".toByteArray())
        val b = zip(base, "x.html" to "<p>正文</p>".toByteArray(), "META-INF/calibre_bookmarks.txt" to "new-and-longer".toByteArray())
        assertTrue(epubContentMatches(a, b))
    }

    @Test
    fun `real content difference is detected`() {
        val base = "mimetype" to "application/epub+zip".toByteArray()
        val a = zip(base, "x.html" to "<p>第一版</p>".toByteArray())
        val b = zip(base, "x.html" to "<p>修订版</p>".toByteArray())
        assertFalse(epubContentMatches(a, b))
    }

    @Test
    fun `extra or missing member is detected`() {
        val a = zip("x.html" to "<p>正文</p>".toByteArray())
        val b = zip("x.html" to "<p>正文</p>".toByteArray(), "extra.png" to byteArrayOf(1, 2, 3))
        assertFalse(epubContentMatches(a, b))
        assertFalse(epubContentMatches(b, a))
    }

    @Test
    fun `missing file counts as different`() {
        val a = zip("x.html" to "<p>正文</p>".toByteArray())
        val gone = File.createTempFile("epub-gone-", ".epub").apply { delete() }
        assertFalse(epubContentMatches(a, gone))
        assertFalse(epubContentMatches(gone, a))
    }
}
