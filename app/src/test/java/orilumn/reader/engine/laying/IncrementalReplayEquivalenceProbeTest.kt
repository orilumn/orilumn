package orilumn.reader.engine.laying

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.ChapterPaginationTable
import orilumn.reader.engine.ChapterStructureCache
import orilumn.reader.engine.ChapterUnit
import orilumn.reader.engine.PaginationCacheStore
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import java.io.File
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Device 侧分页路径回归（Q1-c 退役 G 行后保留的用例）：
 *
 * - 增量/临时路径与 canonical 同走 skia 单源塑形（C1-0 起 [orilumn.reader.engine.layout.ParagraphShapes]
 *   即 [orilumn.reader.engine.skia.SkiaParagraphBreaker] 断行）——此处只守不依赖 shaper 等价的几何不变量
 *   （增量页不溢出、canonical 与磁盘表字节级一致、页边距调试辅助）。
 * - 原「disk-hit 增量回放 ≡ canonical 页高」等价用例（StaticLayoutBreaker 公式行高 vs
 *   ParagraphShapes 实塑形）随 StaticLayoutBreaker 一并退役（G 行）；该等价守则由
 *   `SkiaDrawLineWindowCoherenceTest` 承接，C 系列收增量塑形后再补回。
 *
 * Uses the real skia breaker so it exercises the production shaping/geometry code, not a
 * fake breaker. Falls back to a synthetic chapter with the same block structures when the extracted
 * Rust "简介" XHTML is not present (the test then still guards the geometry invariant).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncrementalReplayEquivalenceProbeTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()

    private fun readOr(path: String, fallback: String): String {
        val f = File(path)
        return if (f.exists()) f.readText() else fallback
    }

    private fun chapterHtml(): String =
        readOr("/tmp/epub_inspect/OEBPS/Text/00_3.xhtml", FALLBACK_HTML)

    private fun chapterCss(): String =
        readOr("/tmp/epub_inspect/OEBPS/Styles/stylesheet.css", FALLBACK_CSS)

    @Test
    fun `heading top uses one collapsed margin on the real chapter`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val root = converter.convert(chapterHtml()) ?: error("chapter parse failed")
        val bundle = CssBundle(listOf(chapterCss()))

        val contentW = 720
        val contentH = 1280
        val prep = layouter.prepare(root, bundle, profile, contentW, contentH)
        // First leaf, first line geometry on the canonical side.
        val leaf0 = prep.leaves.first()
        println("HEADING canonical leaf0=${leaf0.el?.tag} contentTop=${leaf0.contentTop} topEdges=${leaf0.style.border.top + leaf0.style.padding.top}")
        val canonical = layouter.fullLayout(prep, profile, contentW, contentH)
        println("HEADING canonical firstLineTop=${canonical.layout.getLineTop(0)} mTop=${leaf0.style.margin.top} mBottom=${leaf0.style.margin.bottom}")
        val light = layouter.prepareLight(root, bundle, profile, contentW, ChapterStructureCache(), contentH)
        val table = ChapterPaginationTable.fromSlices(0, 0L, canonical.slices, prep.totalBlocks, prep.totalChars)
        val inc = layouter.incrementalLayoutForPage(light, profile, contentW, contentH, table, 0, 4)
        println("HEADING incremental firstLineTop=${inc.layout.getLineTop(0)}")
    }

    @Test
    fun `every incremental page fits within content height`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val root = converter.convert(chapterHtml()) ?: error("chapter parse failed")
        val bundle = CssBundle(listOf(chapterCss()))

        val contentW = 720
        val report = StringBuilder()

        for (contentH in intArrayOf(900, 1100, 1280, 1600, 2000)) {
            val prep = layouter.prepare(root, bundle, profile, contentW, contentH)
            val canonical = layouter.fullLayout(prep, profile, contentW, contentH)
            val table = ChapterPaginationTable.fromSlices(
                chapterIndex = 0, paramHash = 0L,
                slices = canonical.slices, totalBlocks = prep.totalBlocks, totalChars = prep.totalChars,
            )
            val light = layouter.prepareLight(root, bundle, profile, contentW, ChapterStructureCache(), contentH)

            var start = 0
            while (start < table.pages.size) {
                val product = layouter.incrementalLayoutForPage(
                    prepare = light, profile = profile, contentW = contentW, contentH = contentH,
                    table = table, targetPage = start, pagesToShape = 4,
                )
                val end = (start + 4).coerceAtMost(table.pages.size)
                for (i in start until end) {
                    val s = product.slices[i]
                    if (s.firstLine < 0 || s.firstLine >= s.lastLineExclusive) continue
                    val ext = product.layout.getLineBottom(s.lastLineExclusive - 1) - product.layout.getLineTop(s.firstLine)
                    if (ext > contentH) report.append("\n  contentH=$contentH p$i ext=$ext > $contentH")
                }
                start = end
            }
        }

        assertTrue("incremental page extended past content height (overflow truncation missing):$report", report.isEmpty())
    }

    @Test
    fun `in-memory canonical product is byte-identical to the disk table it was persisted from`() {
        // Same-process write → read: a canonical full layout is built and persisted (as the controller's
        // DISK-WRITE / fullLayoutAndPersist does), then the disk-hit branch reopens the SAME chapter
        // while the canonical product (layout + full-chapter slices + matching paramHash) is still in
        // memory. buildLayout's CANONICAL-MEM reuse gate serves that product directly instead of
        // re-shaping — this guard pins the precondition: for the exact parameter hash that wrote the
        // table, the in-memory product's page boundaries match the disk table byte-for-byte, so serving
        // it cannot drift from what the table promised (the device "overflow / bottom blank" bug came
        // precisely from re-shaping with a second StaticLayout pass that stacked a hair taller).
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val root = converter.convert(chapterHtml()) ?: error("chapter parse failed")
        val bundle = CssBundle(listOf(chapterCss()))

        val contentW = 720
        val contentH = 1400
        val paramHash = 0x4f2d_1a9c_0000_0001L

        val tmp = java.nio.file.Files.createTempDirectory("pgl_canonical_mem_").toFile()
        try {
            val prep = layouter.prepare(root, bundle, profile, contentW, contentH)
            val canonical = layouter.fullLayout(prep, profile, contentW, contentH)
            // Canonical itself is over-free — the invariant the reuse path relies on (a page
            // starts and ends on lines that never exceed contentH), so the gate never hides a real
            // overflow behind the cache.
            for (i in canonical.slices.indices) {
                val s = canonical.slices[i]
                if (s.firstLine < 0) continue
                val ext = canonical.layout.getLineBottom(s.lastLineExclusive - 1) - canonical.layout.getLineTop(s.firstLine)
                assertTrue("canonical p$i ext=$ext > contentH=$contentH (gate would hide an over-capacity page)", ext <= contentH)
            }
            // C1-2: round-trips through the shared okio store (same bytes the controller persists).
            val disk = PaginationCacheStore(FileSystem.SYSTEM, tmp.absolutePath.toPath())
            val cacheFile = disk.file("book_test", 0, paramHash)
            val table = ChapterPaginationTable.fromSlices(
                chapterIndex = 0, paramHash = paramHash,
                slices = canonical.slices, totalBlocks = prep.totalBlocks, totalChars = prep.totalChars,
            )
            disk.write(table, cacheFile)

            // Same-process state after a DISK-WRITE: unit holds the canonical product AND the table.
            val unit = ChapterUnit(0)
            unit.bind(canonical.layout, canonical.slices)
            unit.bindPaginationTable(table)

            // Disk-hit reopen: read the table back, verify the CANONICAL-MEM reuse gate's conditions.
            val readBack = disk.read(cacheFile) ?: error("table not readable")
            assertTrue("CANONICAL-MEM gate needs matching paramHash", unit.paramHash == readBack.paramHash)
            assertTrue("CANONICAL-MEM gate needs canonical product in memory", unit.laidOut && unit.layout != null)
            assertTrue("CANONICAL-MEM gate needs full-chapter slices", unit.pageSlices.size == readBack.pages.size)
            // Byte-identical boundaries: serving the in-memory product = serving the disk table.
            for (i in readBack.pages.indices) {
                val a = unit.pageSlices[i]; val b = readBack.pages[i]
                assertTrue("p$i charStart mismatch mem=${a.charStart} disk=${b.charStart}", a.charStart == b.charStart)
                assertTrue("p$i charEnd mismatch mem=${a.charEnd} disk=${b.charEnd}", a.charEnd == b.charEnd)
                assertTrue("p$i blockStart mismatch mem=${a.blockStart} disk=${b.blockStart}", a.blockStart == b.blockStart)
                assertTrue("p$i blockEnd mismatch mem=${a.blockEndExclusive} disk=${b.blockEndExclusive}", a.blockEndExclusive == b.blockEndExclusive)
            }
        } finally {
            tmp.deleteRecursively()
        }
    }

    private companion object {
        val FALLBACK_HTML = """
            <html><body>
            <h1><span class="sec-num"> </span>简介</h1>
            <blockquote><p>注意：此书的英文原版与出版社出版的纸质版一致。</p></blockquote>
            <p>欢迎阅读本书，这是一段较长的正文，用来产生多行文本以便交叉页边界。</p>
            <h2>Rust 适合哪些人</h2>
            <p>Rust 因多种原因适合许多人。让我们看看几个最重要的群体。</p>
            <h3>开发者团队</h3>
            <p>Rust 已被证明是一个适合大型开发团队协作的高效工具，底层代码容易出现各种微妙的错误。</p>
            <ul>
              <li>Cargo 是内置的依赖管理器和构建工具，它能轻松增加、编译和管理依赖。</li>
              <li>Rustfmt 格式化工具确保开发者遵循一致的代码风格。</li>
            </ul>
            <p>通过使用 Rust 生态系统中丰富的工具，开发者可以更加高效。</p>
            <div class="table-wrapper" style="break-inside: avoid; width: 70%;">
              <table><thead><tr><th>Ferris</th><th>含义</th></tr></thead><tbody>
                <tr><td><img src="a.png" width="60"/></td><td>这段代码无法通过编译！</td></tr>
                <tr><td><img src="b.png" width="60"/></td><td>这段代码会 Panic！</td></tr>
              </tbody></table>
            </div>
            <p>在大部分情况，我们会指导你将错误的代码修改为正确版本。</p>
            <pre><span class="filename">main.rs</span>
            <code>fn main() {
                let guess: u32 = "42".trim().parse().expect("not a number");
                println!("guess = {}", guess);
                let mut counter = 0;
                while counter &lt; 10 { counter += 1; }
            }</code></pre>
            <p>这会输出一个编译错误，因为数字和数字匹配，而猜的数字是字符串。</p>
            <p>让我们再举一个完整的例子，展示所有权与借用的核心概念。</p>
            <pre><span class="filename">ownership.rs</span>
            <code>fn main() {
                let s1 = String::from("hello");
                let s2 = s1.clone();
                println!("{} {}", s1, s2);
                let len = calculate_len(&amp;s1);
                println!("length is {}", len);
            }</code></pre>
            <p>这段代码演示了克隆与借用，是我们理解所有权的重要步骤。</p>
            </body></html>
        """.trimIndent()

        val FALLBACK_CSS = """
            * { margin: 0; padding: 0; border: 0; }
            img { border: none; margin: 0.5rem auto; display: block; }
            html { font-size: 18px; }
            body { line-height: 1.3rem; font-size: 0.95rem; }
            h1,h2,h3,h4,h5,h6,strong,p.caption,table th { font-weight: 600; color: #0d9ea0; }
            h1,h2,h3,h4,h5,h6 { margin-top: 2rem; margin-bottom: 0.5rem; break-after: avoid; }
            h1 + h2, h2 + h3, h3 + h4, h4 + h5, h5 + h6 { margin-top: 0 !important; }
            h1 { text-align: center; margin-top: 1rem; margin-bottom: 3rem; font-size: 1.6rem; line-height: 1.3em; }
            .sec-num { display: block; font-size: 0.5em; margin-top: 1rem; margin-bottom: 3rem; text-align: left; }
            h2 { font-size: 1.3rem; padding-bottom: 0.25rem; border-bottom: 1px solid #ccc; }
            h3 { font-size: 1.2rem; border: none; }
            p { line-height: 1.3rem; margin-top: 0; margin-bottom: 0.3rem; }
            li { line-height: 1.3rem; list-style-type: circle; margin-bottom: 0.3rem; }
            ul,ol { padding-left: 2rem; }
            blockquote { margin: 0.5rem 0; padding: 0.5rem; padding-bottom: 0.2rem; }
            table { width: 90%; margin: 1rem auto; text-align: center; font-size: 0.8rem; border-collapse: collapse; }
            .table-wrapper { width: 80%; margin: 0 auto; }
            thead { break-after: avoid; }
            th,td { border-top: 1px solid #000; text-align: left; padding: 0.3em 0.5em; }
        """.trimIndent()
    }
}