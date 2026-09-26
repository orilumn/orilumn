package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import okio.Path.Companion.toPath
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 复现平板落盘日志的连续翻页跳页（排版层-上：全量/增量分页算法）。
 *
 * - `日志_20260927.txt`：TEMP 路径快速前翻中 4 次整页跳过（ch9 缺 935/990 字、
 *   ch10 缺 1153 字、ch11 缺 1116 字；被跳过的内容从未展示）。
 * - `日志_20260926.txt`：从 backward[0] 出发的前翻大跳（ch11 +5516/+6797 字）。
 *
 * 行为断言（与《调试日志与分页跟踪》§6 同口径）：连续翻页的相邻源页必须首尾
 * 相接，块边界分隔符容忍 ±3 字；超出即跳页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TempBurstFlipProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var cacheDir: java.io.File
    private val viewW = 720
    private val viewH = 1280

    private val ownedControllers = mutableListOf<BookDocumentController>()

    @After
    fun tearDown() {
        ownedControllers.forEach { runCatching { it.close() } }
        ownedControllers.clear()
    }

    @Before
    fun setUp() {
        cacheDir = temp.newFolder("cache")
    }

    private fun newController(): BookDocumentController {
        val c = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        c.cacheRoot = cacheDir.absolutePath.toPath()
        ownedControllers.add(c)
        return c
    }

    /** 相邻两源页是否相接（任一方向，容忍块边界分隔符）。*/
    private fun tiles(a: orilumn.reader.engine.paging.PageSlice, b: orilumn.reader.engine.paging.PageSlice): Boolean {
        if (a.charStart == b.charStart && a.charEnd == b.charEnd) return true
        val fwd = b.charStart - a.charEnd
        val bwd = a.charStart - b.charEnd
        return fwd in -3..3 || bwd in -3..3
    }

    private fun describe(s: orilumn.reader.engine.paging.PageSlice) =
        "char[${s.charStart},${s.charEnd})blk[${s.blockStart},${s.blockEndExclusive})"

    @Test
    fun `burst forward flips on TEMP path never skip content`() = runBlocking {
        val b = newController()
        assertTrue(b.open(31L, saved = null))
        b.setViewport(viewW, viewH)
        val start = b.openChapterStart(0) ?: error("no start page")
        assertEquals(0, start.first)
        // 复现前提：必须走 TEMP 锚点流，否则夹具失效。
        assertNotNull("fixture must take the TEMP path", b.unitAt(0)?.inProgress)

        var ch = start.first
        var slice = start.second
        val shown = mutableListOf(slice)
        repeat(40) {
            val next = b.findAdjacentPage(ch, slice, 1) ?: return@repeat
            if (next.first != ch) return@repeat
            shown.add(next.second)
            slice = next.second
        }
        assertTrue("burst too short to prove anything: ${shown.size}", shown.size > 10)
        for (i in 1 until shown.size) {
            val prev = shown[i - 1]
            val cur = shown[i]
            assertTrue(
                "forward flip #$i skipped content: ${describe(prev)} -> ${describe(cur)}",
                tiles(prev, cur),
            )
        }
    }

    @Test
    fun `back then forward flips keep tiling across the window edge`() = runBlocking {
        val b = newController()
        assertTrue(b.open(31L, saved = null))
        b.setViewport(viewW, viewH)
        val start = b.openChapterStart(0) ?: error("no start page")
        assertNotNull("fixture must take the TEMP path", b.unitAt(0)?.inProgress)

        var ch = start.first
        var slice = start.second
        val shown = mutableListOf(slice)
        // 前翻 10 页，再后翻 5 页（深退到 backward 窗口，前向侧被裁空可触发 RE-ROOT），
        // 再前翻 8 页（B0→F 的跳页签名位；转向后第二次前翻是真机跳页点）。
        val plan = List(10) { 1 } + List(5) { -1 } + List(8) { 1 }
        for (dir in plan) {
            val next = b.findAdjacentPage(ch, slice, dir) ?: break
            if (next.first != ch) break
            shown.add(next.second)
            slice = next.second
        }
        assertTrue("sequence too short: ${shown.size}", shown.size > 10)
        val chain = shown.mapIndexed { i, s -> "#$i ${describe(s)}" }.joinToString("\n")
        for (i in 1 until shown.size) {
            val prev = shown[i - 1]
            val cur = shown[i]
            assertTrue(
                "flip #$i broke tiling: ${describe(prev)} -> ${describe(cur)}\nchain:\n$chain",
                tiles(prev, cur),
            )
        }
    }

    @Test
    fun `oscillating flips keep tiling across repeated window rebuilds`() = runBlocking {
        val b = newController()
        assertTrue(b.open(32L, saved = null))
        b.setViewport(viewW, viewH)
        val start = b.openChapterStart(0) ?: error("no start page")
        assertNotNull("fixture must take the TEMP path", b.unitAt(0)?.inProgress)

        var ch = start.first
        var slice = start.second
        val shown = mutableListOf(slice)
        // 反复振荡：每次深入后向窗口再前翻回来，多次触发前向侧裁空 → RE-ROOT 重建 + 修复收敛。
        // 真机跳页签名（B0→F 大跳）就藏在这种转向里；单次转向的用例上面已覆盖，这里连锤 5 轮。
        val plan = mutableListOf<Int>()
        repeat(5) { plan += List(6) { 1 } + List(4) { -1 } }
        plan += List(6) { 1 }
        for (dir in plan) {
            val next = b.findAdjacentPage(ch, slice, dir) ?: break
            if (next.first != ch) break
            shown.add(next.second)
            slice = next.second
        }
        assertTrue("sequence too short: ${shown.size}", shown.size > 20)
        val chain = shown.mapIndexed { i, s -> "#$i ${describe(s)}" }.joinToString("\n")
        for (i in 1 until shown.size) {
            val prev = shown[i - 1]
            val cur = shown[i]
            assertTrue(
                "flip #$i broke tiling: ${describe(prev)} -> ${describe(cur)}\nchain:\n$chain",
                tiles(prev, cur),
            )
        }
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>TEMP-BURST</dc:title><dc:identifier id="bookid">urn:test:tempburst</dc:identifier>
                </metadata>
                <manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
        // 大章（> SMALL_CHAPTER_BLOCKS=120 块 ⇒ 前台走 TEMP 锚点流），规模对标真机 ch12（328 块）。
        val body = (1..300).joinToString("\n") { p ->
            "<p>Paragraph $p. " +
                "some filler text for measuring line wraps and page breaks. ".repeat(3) + "</p>"
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Chapter 1</h1>" + body + "</body></html>").toByteArray(),
        )
    }
}
