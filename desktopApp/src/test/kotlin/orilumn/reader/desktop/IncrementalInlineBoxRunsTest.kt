package orilumn.reader.desktop

import orilumn.reader.data.settings.ReaderSettings
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test

/**
 * 回归：磁盘表命中的增量路径（`buildPartialSkiaWindow`）必须与首开 canonical
 * （`DrawLineBuilder`）同样携带行内盒 run（`DrawLine.inlineBoxRuns`）。
 *
 * 症状（2026-10-19 用户报）：首次打开测试书时行内样式（mark 背景 / 行内 padding+border）
 * 看不到，第二次打开才看到——增量路径构造 DrawLine 时漏透传 `shape.inlineBoxRuns`
 * （首行缩进也漏了 `inlineBoxLeadInsetPx`），行内装饰随磁盘表命中而整体消失。
 */
class IncrementalInlineBoxRunsTest {

    private val book = File("../books/acceptance-v1v8.epub")

    private fun count(lines: List<orilumn.reader.engine.skia.DrawLine>): Pair<Int, Int> {
        val withRuns = lines.count { it.inlineBoxRuns.isNotEmpty() }
        return withRuns to lines.size
    }

    @Test
    fun `first open vs second open carry inline box runs`() {
        Assume.assumeTrue("无测试书跳过", book.isFile)
        val root = java.nio.file.Files.createTempDirectory("orilumn-probe-firstopen").toFile()
        val cacheDir = java.nio.file.Files.createTempDirectory("orilumn-probe-cache").toFile()

        val store = DesktopShelfStore(root)
        val entry = runBlocking {
            store.load()
            store.addBook(book.nameWithoutExtension, null, book.absolutePath, null)
        }

        fun openWith(): Pair<Int, Int> = runBlocking {
            val host = DesktopReaderHost(
                bookFile = entry.filePath,
                bookId = entry.id,
                store = store,
                settings = ReaderSettings.DEFAULT,
                density = 1f,
                viewportW = 760,
                viewportH = 1000,
                fontLibrary = store.fontLibrary(),
                cacheRoot = cacheDir,
            )
            try {
                var pos = host.open() ?: return@runBlocking (0 to 0)
                var withRuns = 0
                var total = 0
                var hops = 0
                val trace = ArrayList<String>()
                while (hops < 20) {
                    val lines = host.pageLines(pos) ?: break
                    val (a, b) = count(lines)
                    trace += "ch${pos.chapter}:$a/$b"
                    withRuns += a
                    total += b
                    pos = host.adjacent(pos, 1) ?: break
                    hops++
                }
                println("TRACE $trace")
                withRuns to total
            } finally {
                host.close()
            }
        }

        val first = openWith()
        val second = openWith()
        println("PROBE first-open: $first  second-open: $second")
        // 回归断言：磁盘表命中的再开（增量路径 buildPartialSkiaWindow）必须与首开
        // （canonical DrawLineBuilder）同样携带行内盒 run。曾因增量路径漏透传
        // `shape.inlineBoxRuns`，再开整章 0 run（mark 背景/行内 padding+border 全不画）。
        check(first.first > 0) { "首开就没有行内盒 run：$first" }
        check(second.first == first.first) {
            "首开/再开行内盒 run 数不一致（增量路径漏透传？）：first=$first second=$second"
        }
    }
}
