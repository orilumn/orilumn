package orilumn.reader.io

import okio.FileSystem
import okio.FileSystem.Companion.SYSTEM
import okio.Path.Companion.toPath
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * S30 桌面侧冒烟 —— 镜像 FontParserTest 对 FontParser 的【桌面侧证明】同一冒烟范式：
 * FontParserTest 用「构造即解析、assert 不炸」证明 FontParser 在桌面壳可用；本冒烟用
 * 「temp root 注入 → Logger.i/e → SYSTEM.exists(logs/)」证明 [Logger] 这条 expect/actual seam
 * 在桌面可用（FontParser 是无状态纯函数故不需要 reset；AppRoot 是**有状态** seam —— AtomicRef
 * 注入缝 —— 按仓库「有状态 seam 必有测试 reset」的既有接缝范式，在每用例前隔离，与 FontParserTest
 * 对 FontParser 的证明方式同源，只是这台 seam 需要先 init 注入）。
 *
 * 冒烟只证明【壳不炸】：日志文件出现即通过，不校验行格式/轮转字节（那是实现细节，语义对齐
 * FontParserTest 只断言「解析成功、不炸」的本仓库冒烟口径）。
 */
class LoggerSmokeTest {

    @Before
    fun setUp() {
        AppRoot.cleanForTest()
    }

    @Test
    fun `logs to temp root and does not throw`() {
        val temp = Files.createTempDirectory("n-orilumn-log-smoke").toString()
        AppRoot.init(temp.toPath(), SYSTEM)

        Logger.i("S30smoke", "桌面侧正常落盘")
        Logger.e("S30smoke", "异常也走同一队列", IllegalStateException("冒烟"))

        // 单线程 worker 异步落盘：轮询到当日日志文件或 3 秒超时（不脆 sleep，镜像 FontParserTest
        // 对 FontParser 的轮询式断言口径——桌面壳异步，需 poll）
        val logsDir = temp.toPath() / "logs"
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            if (SYSTEM.exists(logsDir)) break
            Thread.sleep(20)
        }
        assertTrue("logs/ 目录应已创建", SYSTEM.exists(logsDir))
    }

    @Test
    fun `burst does not grow memory unbounded and leaves a marker`() {
        // B2 整书突发上万行/秒：队列超过 MAX_QUEUE 即丢最老，文件总量有界，
        // 且 marker 交代丢了多少行（证据链不断）。
        val temp = Files.createTempDirectory("n-orilumn-log-burst").toString()
        AppRoot.init(temp.toPath(), SYSTEM)
        repeat(10000) { Logger.d("burst", "x".repeat(100)) }
        val logsDir = temp.toPath() / "logs"
        val deadline = System.currentTimeMillis() + 15000
        var stable = 0
        var lastSize = -1L
        fun totalBytes(): Long = runCatching {
            SYSTEM.list(logsDir).sumOf { runCatching { SYSTEM.metadata(it).size ?: 0L }.getOrDefault(0L) }
        }.getOrDefault(-1L)
        while (System.currentTimeMillis() < deadline) {
            val sz = totalBytes()
            if (sz > 0 && sz == lastSize) {
                stable++
                if (stable >= 5) break
            } else {
                stable = 0
                lastSize = sz
            }
            Thread.sleep(100)
        }
        var seenMarker = false
        for (f in runCatching { SYSTEM.list(logsDir) }.getOrDefault(emptyList())) {
            val text = runCatching { SYSTEM.read(f) { readUtf8() } }.getOrDefault("")
            if (text.contains("burst-dropped")) seenMarker = true
        }
        val total = totalBytes()
        org.junit.Assert.assertTrue("burst 后落盘总量应有界 (<2MB), 实际=$total", total in 1..(2L * 1024 * 1024))
        org.junit.Assert.assertTrue("应有 burst-dropped marker", seenMarker)
        AppRoot.cleanForTest()
    }

@Test
    fun `unset root is ignored without throwing`() {
        AppRoot.cleanForTest()
        assertNull(AppRoot.root)
        Logger.i("S30unset", "未 init 时静默跳过")
    }
}
