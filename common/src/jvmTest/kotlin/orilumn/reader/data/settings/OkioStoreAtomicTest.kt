package orilumn.reader.data.settings

import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `writeTextAtomic` 回归：macOS 实测 `delete` 会被同步盘/索引短暂锁定顶掉
 * （`failed to delete reader.json` 炸掉落盘协程）。原子写必须不抛异常、
 * 反复覆盖可读、并发落盘最终收敛。
 */
class OkioStoreAtomicTest {

    private fun tempDir(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "orilumn-atomic-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `overwrite existing file never throws and stays readable`() {
        val dir = tempDir()
        val fs = FileSystem.SYSTEM
        val target = (dir.absolutePath.toPath() / "reader.json")
        repeat(20) { i ->
            fs.writeTextAtomic(target, "{\"v\":$i}")
            assertEquals("{\"v\":$i}", dir.resolve("reader.json").readText())
        }
    }

    @Test
    fun `sequential saves converge to the last value`() {
        val store = ReaderSettingsStore(tempDir().absolutePath)
        repeat(10) { i ->
            store.save(ReaderSettings.DEFAULT.copy(brightness = i))
        }
        assertEquals(9, store.load().brightness)
    }
}
