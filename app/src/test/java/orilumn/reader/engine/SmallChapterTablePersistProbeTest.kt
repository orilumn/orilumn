package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 短章改参后的**落盘归属**（D 项）：写不写盘看「参数周期稳没稳」，不看谁来跑。
 *
 *  - 预览（[BookDocumentController.prepareRelayoutLight]，拖动中每 16ms 一轮）参数还在动，
 *    写下的表下一帧就作废 ⇒ **不写**；
 *  - 稳定点（[BookDocumentController.prepareRelayout]，关面板/尺寸已定）产出的就是本参数周期的
 *    正典 ⇒ **必须写**。短章没有后台正典接手（不建临时会话 ⇒ 无 B1；B2 见内存表新鲜即
 *    skip-fresh 返回），稳定点不写就永远没人补，下次开书这一章得整章重排。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmallChapterTablePersistProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private lateinit var cacheRoot: okio.Path
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        cacheRoot = temp.newFolder("cache").absolutePath.toPath()
        controller.cacheRoot = cacheRoot
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test
    fun `settled small-chapter relayout persists the new table`() = runBlocking {
        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.4))
        controller.open(31L, saved = null)
        controller.setViewport(viewW, viewH)
        val (_, p1) = controller.openChapterStart(1) ?: error("no ch1 head")

        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.5))
        val hash = currentParamHash()
        val r = controller.prepareRelayout(1, p1.charStart) ?: error("no relayout product")

        val table = store().read(store().file("book_${controller.bookId}", 1, hash))
        assertNotNull("稳定点重排必须把小章表落盘（没人会替它补）", table)
        assertEquals("落盘页数必须与本次重排产物一致", r.slices.size, table!!.pages.size)
        assertEquals("落盘表必须带本次参数指纹", hash, table.paramHash)
    }

    @Test
    fun `preview small-chapter relayout does not persist`() = runBlocking {
        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.4))
        controller.open(31L, saved = null)
        controller.setViewport(viewW, viewH)
        val (_, p1) = controller.openChapterStart(1) ?: error("no ch1 head")

        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.5))
        val hash = currentParamHash()
        controller.prepareRelayoutLight(1, p1.charStart) ?: error("no relayout product")

        assertNull(
            "预览路径参数还在动，写下的表下一帧就作废 —— 不得写盘",
            store().readPageCount("book_${controller.bookId}", 1, hash),
        )
    }

    private fun store() = PaginationCacheStore(okio.FileSystem.SYSTEM, cacheRoot, 0)

    private fun currentParamHash(): Long {
        val cw = (viewW - controller.profile.marginLeft - controller.profile.marginRight).coerceAtLeast(16)
        val chh = (viewH - controller.profile.marginTop - controller.profile.marginBottom).coerceAtLeast(16)
        return LayoutParamKey.fromProfile(controller.profile, cw, chh).hash()
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>SMALL</dc:title><dc:identifier id="bookid">urn:test:small-persist</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c0" href="Text/c0.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c0"/><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        // 短章：每章 ~100 段（<120 块），每段一行 ⇒ 多页（≥3）但恒走磁盘表。
        val chapters = (0..2).associate { n ->
            val body = (1..100).joinToString("\n") { p ->
                "<p>第 $p 段，用来测量断行与分页的填充文字，多写一点让它跨行。</p>"
            }
            "OEBPS/Text/c$n.xhtml" to ("<html><body><h1>第 $n 章</h1>" + body + "</body></html>").toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }
}
