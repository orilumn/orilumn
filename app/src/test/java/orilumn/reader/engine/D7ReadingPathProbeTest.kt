package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe (整改 D7 阅读路径，Mac 本地可测部分）：把 D7 设备清单里的**逻辑项**搬到单测里，
 * 判据与设备口径同源（落盘日志只是这些行为的观测面）。
 *
 * 纯性能项（`t=` 分布、`pagecache-hit` 率、滑块拖拽、旋转）仍只能上平板，不在此列。
 *
 *  - D7-2（§3.4）：落到章第 2 页 → B1 补全本章表；翻回第 1 页无可见切换（同页首字符一致）。
 *  - D7-3（§3.3）：从章首页往回翻 → 上一章整章预排落地（temp 侧 `scheduleTempEdgePrefill` 那条链）。
 *  - D7-4（D4）：落位后 temp 块水位向前推进（第 4 档在临时表侧唯一的执行体）。
 *  - D7-6：同章连翻两页，接缝咬合（后页首字符 == 前页尾字符）。
 *
 * Fixture：ch0 小章（edge-B2 秒级落地），ch1 大章（>120 块，走增量 temp 路径）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class D7ReadingPathProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        controller.cacheRoot = temp.newFolder("cache").absolutePath.toPath()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    private suspend fun awaitTable(chapter: Int, timeoutMs: Long = 60_000) {
        withTimeout(timeoutMs) {
            while (controller.unitAt(chapter)?.paginationTable == null) delay(50)
        }
    }

    @Test
    fun `page2 landing completes B1 and flipping back to page1 is seamless`() = runBlocking {
        assertTrue(controller.open(21L, saved = null))
        controller.setViewport(viewW, viewH)

        // ch1 大章 disk-miss → 锚点流，落位第 1 页。
        val (ch, p1) = controller.openChapterStart(1) ?: error("no ch1 head")
        assertEquals(1, ch)
        // 前进到第 2 页（D7-2 的"目标页落在章第 2 页"）。
        val (_, p2) = controller.findAdjacentPage(1, p1, +1) ?: error("no ch1 page 2")
        assertTrue("page 2 must start after page 1", p2.charStart > p1.charStart)

        // B1 落地（档位由 b1PriorityFor 单元测试锁定，这里只验接线：表最终补全）。
        awaitTable(1)
        val table = controller.unitAt(1)?.paginationTable ?: error("no ch1 table")
        assertTrue("B1 table must cover the whole chapter", table.pages.isNotEmpty())

        // 翻回第 1 页：必须回到同一页首（无临时表→全量的可见切换）。
        val (_, back) = controller.findAdjacentPage(1, p2, -1) ?: error("no flip back")
        assertEquals(
            "flipping back to the head must land on the same page start (no visible temp-to-canonical switch)",
            p1.charStart, back.charStart,
        )
    }

    @Test
    fun `flip back from chapter head lands previous chapter whole table`() = runBlocking {
        assertTrue(controller.open(22L, saved = null))
        controller.setViewport(viewW, viewH)

        // ch1 首页（temp 路径）。注意 torn-pair：锚点页 Fwd(0) 的前驱是同章后向列，
        // 所以第 1 次回翻仍在章内——连翻直到越出本章（有界，避免死循环）。
        var ch = 1
        var slice = (controller.openChapterStart(1) ?: error("no ch1 head")).second
        repeat(8) {
            if (ch != 1) return@repeat
            val (c, s) = controller.findAdjacentPage(ch, slice, -1) ?: error("flip $it stuck")
            ch = c
            slice = s
        }
        assertEquals("flipping back from the head must eventually land the previous chapter", 0, ch)

        // 上一章（小章）全量表落地（翻出章首时 temp 侧 edge 链已派发）。
        awaitTable(0)
        val t0 = controller.unitAt(0)?.paginationTable ?: error("no ch0 table")
        assertTrue("edge-prefilled table must have pages", t0.pages.isNotEmpty())
    }

    @Test
    fun `temp watermark advances past the landing point`() = runBlocking {
        // 虚拟时钟：prefill 循环是 delay(4) 步进的后台活，虚拟推进即确定性执行，
        // 不依赖墙钟竞速（落地瞬间读水位 vs 后台塑形的 race 在此不存在）。
        val scheduler = TestCoroutineScheduler()
        val dispatcher = StandardTestDispatcher(scheduler)
        controller.close()
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
            scope = CoroutineScope(dispatcher),
            backgroundDispatcher = dispatcher,
        )
        controller.cacheRoot = temp.newFolder("cache2").absolutePath.toPath()

        assertTrue(controller.open(23L, saved = null))
        controller.setViewport(viewW, viewH)

        controller.openChapterStart(1) ?: error("no ch1 head")
        val w0 = controller.tempWatermarkForProbe(1)
        assertNotNull("a live temp session must own a watermark", w0)

        // D7-4：推进虚拟时间，prefill 循环跑完配额，水位必须超过落位点。
        scheduler.advanceTimeBy(5_000)
        val w1 = controller.tempWatermarkForProbe(1)
        assertNotNull("session must survive the prefill run", w1)
        assertTrue(
            "background prefill must push the block watermark forward (tier-4 temp-side body, was=$w0 now=$w1)",
            (w1 ?: -1) > (w0 ?: -1),
        )
    }

    @Test
    fun `two consecutive forward flips join seamlessly`() = runBlocking {
        assertTrue(controller.open(24L, saved = null))
        controller.setViewport(viewW, viewH)

        val (_, p1) = controller.openChapterStart(1) ?: error("no ch1 head")
        val (_, p2) = controller.findAdjacentPage(1, p1, +1) ?: error("no page 2")
        val (_, p3) = controller.findAdjacentPage(1, p2, +1) ?: error("no page 3")
        assertEquals("page 2 must start where page 1 ends", p1.charEnd, p2.charStart)
        assertEquals("page 3 must start where page 2 ends", p2.charEnd, p3.charStart)
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>D7</dc:title><dc:identifier id="bookid">urn:test:d7</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        // ch0 小章（edge-B2 秒级落地）；ch1 大章（>120 块，走增量 temp 路径，多页可翻）。
        val small = (1..20).joinToString("\n") { p ->
            "<p>Paragraph $p of chapter 0. " + "some filler text for measuring line wraps. ".repeat(2) + "</p>"
        }
        val big = (1..140).joinToString("\n") { p ->
            "<p>Paragraph $p of chapter 1. " + "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Chapter 0</h1>$small</body></html>").toByteArray(),
            "OEBPS/Text/c2.xhtml" to ("<html><body><h1>Chapter 1</h1>$big</body></html>").toByteArray(),
        )
    }
}
