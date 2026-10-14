package orilumn.reader.data.font

import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Probe（P2 指纹加固）：[fontFilesEnvironmentHash] 的确定性——族名不变但字体文件被换时指纹必须变。
 */
class FontEnvironmentHashTest {

    private fun tempRoot(): File = Files.createTempDirectory("orilumn-fontenv").toFile()

    private fun writeFile(dir: File, name: String, size: Int, mtimeMs: Long = 1_600_000_000_000L): File {
        val f = File(dir, name)
        f.writeBytes(ByteArray(size))
        f.setLastModified(mtimeMs)
        return f
    }

    @Test
    fun `same tree hashes identically and different content differs`() {
        val dir = tempRoot()
        try {
            writeFile(dir, "a.ttf", 10)
            writeFile(dir, "b.otf", 20)

            val h1 = fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf(dir.absolutePath))
            val h2 = fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf(dir.absolutePath))
            assertEquals("同一目录树两次哈希须一致", h1, h2)
            assertNotEquals("有文件即非零", 0L, h1)

            // 加一个文件（族名可不变）⇒ 指纹必须变。
            writeFile(dir, "c.ttc", 30)
            assertNotEquals("新增文件须改变哈希", h1, fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf(dir.absolutePath)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rewriting a file with a new mtime changes the hash`() {
        val dir = tempRoot()
        try {
            val f = writeFile(dir, "a.ttf", 10, mtimeMs = 1_600_000_000_000L)
            val before = fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf(dir.absolutePath))

            // 同大小、改 mtime：模拟原地替换字体文件（族名不变）⇒ 指纹须变。
            f.setLastModified(1_700_000_000_000L)
            assertNotEquals("mtime 变须改变哈希", before, fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf(dir.absolutePath)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `missing or empty roots fall back to zero`() {
        val dir = tempRoot()
        try {
            assertEquals("不存在的根回 0（退回族名指纹）", 0L, fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf("$dir/nope")))
            assertEquals("空根列表回 0", 0L, fontFilesEnvironmentHash(FileSystem.SYSTEM, emptyList()))
            assertEquals("空目录回 0", 0L, fontFilesEnvironmentHash(FileSystem.SYSTEM, listOf(tempRoot().also { it.delete() }.absolutePath)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
