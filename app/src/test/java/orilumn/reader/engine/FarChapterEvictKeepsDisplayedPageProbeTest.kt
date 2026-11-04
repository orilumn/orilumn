package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Probe（章末整页空白）：**后台整书预排的远章清扫不得清掉读者眼前那一章**。
 *
 * 真机现象（2026-11-03 落盘日志，一天 6 次）：翻到章末最后一页，页面整片空白，再翻一页才有内容。
 * 日志指纹是严格 1:1 的
 * ```
 * edge:167 prio=10 start … done      (章末加急排下一章)
 * +190ms  non-Readback layout ch=166 … blank content   ×4（本页四条绘制取数）
 * 翻一页  FLIP … |layoutLC=-1 tempLC=-1 |pageSlices=7 |laidOut=false   ← 只有 evictParsedState 造得出
 * ```
 * 病根：[BookDocumentController.evictFarChapters] 的默认 `keep` 取的是 [activeChapter]，
 * 而它只在开书/调参时写、翻页全程不动。读者翻出 30 章之后，B2/`edge:` 任务收尾那次清扫
 * 就以「读者身后很远的那个章」为中心保窗口，把**正在显示的那一章**的 `layout` 一起清掉
 * ⇒ `pageLines` 返回 null ⇒ 画布什么都不画（只读不自愈）。再翻一页触发 ensure 才恢复。
 *
 * 本探针直接踩这条路径：落地在 35 章（离开书章 0 有 35 > 30 章），然后触发整书 B2。
 * 只要有一次清扫真的跑了（用「书两端的章被清掉」作证），眼前章的版式与绘制行就必须还在。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FarChapterEvictKeepsDisplayedPageProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private val viewW = 720
    private val viewH = 1280

    /** 41 章（0..40）：够越过 window=30，且两端各留一个「清扫见证章」。 */
    private val chapters = 41
    private val landing = 35

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

    @Test
    fun `whole-book sweep never evicts the chapter on screen`() = runBlocking {
        assertTrue(controller.open(1L, saved = null))
        controller.setViewport(viewW, viewH)
        // 书两端的章先排好：清扫跑过之后它们会丢解析态，作为「清扫真的执行了」的见证。
        for (ch in intArrayOf(0, chapters - 1)) {
            assertNotNull("chapter $ch must be laid out", controller.ensureChapterLayout(ch, 0))
        }
        // 落到 35 章（开书章 0 在 30 章窗口之外）。
        val (landedCh, page) = controller.openChapterStart(landing) ?: error("no landing at ch$landing")
        assertEquals(landing, landedCh)
        assertNotNull("landing page must be drawable", controller.pageLines(landedCh, page))

        // 整书 B2：每一章的任务收尾都会跑一次远章清扫（keep 曾取 activeChapter=0）。
        controller.requestWholeBookRelayout()

        // 等清扫真的跑过一次（任一端章丢掉解析态）。
        withTimeout(20_000) {
            while (true) {
                val a = controller.unitAt(0)
                val z = controller.unitAt(chapters - 1)
                if (a?.markup == null || z?.markup == null) break
                delay(20)
            }
        }
        // 关键断言：眼前那一章的版式（本页唯一绘制源）不许被清扫掉。
        assertNotNull(
            "the chapter on screen must keep its layout after a background sweep",
            controller.unitAt(landing)?.layout,
        )
        assertNotNull(
            "the page on screen must stay drawable (else the reader sees a blank page)",
            controller.pageLines(landedCh, page),
        )
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val manifest = (0 until chapters).joinToString("\n") { i ->
            """<item id="c$i" href="Text/c$i.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spine = (0 until chapters).joinToString("\n") { i -> """<itemref idref="c$i"/>""" }
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>SWEEP</dc:title><dc:identifier id="bookid">urn:test:sweep</dc:identifier>
                </metadata>
                <manifest>$manifest</manifest>
                <spine>$spine</spine>
            </package>"""
        val files = HashMap<String, ByteArray>()
        files["META-INF/container.xml"] = container.toByteArray()
        files["OEBPS/package.opf"] = opf.toByteArray()
        for (i in 0 until chapters) {
            val body = (1..3).joinToString("\n") { p ->
                "<p>Chapter $i paragraph $p. " + "filler text for line wrapping. ".repeat(2) + "</p>"
            }
            files["OEBPS/Text/c$i.xhtml"] = "<html><body><h1>Chapter $i</h1>$body</body></html>".toByteArray()
        }
        return files
    }
}
