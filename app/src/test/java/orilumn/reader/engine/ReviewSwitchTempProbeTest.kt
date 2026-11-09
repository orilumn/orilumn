package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe（切审阅的落位策略）：**磁盘表已就绪也不能一上来就用它**。
 *
 * 审阅切换换的是本章文字，正常/审阅两套分页的页界本来就不一样；落在正典页上页首就跳了。
 * 所以大章切换必须跳过磁盘命中/快进，先按锚点行起一张临时页（[BookDocumentController.relayoutTo]
 * 的 `forceTemp`），随后后台照常排本章正典；章内翻页留在临时表，只有跳转/离开/回章首才切磁盘表。
 *
 * 章为 LARGE（> SMALL_CHAPTER_BLOCKS = 120 块），否则走短章整章路径，测不到这条分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReviewSwitchTempProbeTest {

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

    @Test
    fun `forced temp switch ignores a ready disk table and anchors the page top`() = runBlocking {
        assertTrue(controller.open(7L, saved = null))
        controller.setViewport(viewW, viewH)
        val hash = paramHash()
        val unit = controller.unitAt(0) ?: error("no ch0")
        controller.ensureChapterLayout(0, 0) ?: error("no ch0 layout")
        assertNotNull("large chapter starts on the anchor temp stream", unit.inProgress)

        // 等后台把本章正典排完并落盘，再离章绑定磁盘表：此刻本章磁盘表**已就绪**。
        withTimeout(15_000) {
            while (unit.paginationTable?.paramHash != hash) delay(20)
        }
        finalizeTempOnLeave(unit, unit.inProgress ?: error("no session"))
        assertNull("leave drops the temp session", unit.inProgress)
        assertNotNull("leave binds the disk table", unit.paginationTable)
        val diskPage = unit.pageSlices.firstOrNull() ?: error("no disk page")
        val anchor = diskPage.charEnd - 20

        // 审阅切换路径：forceTemp 必须无视这张就绪的磁盘表，按锚点行重开临时页。
        val landed = controller.relayoutTo(
            0, anchor,
            nearestPageStart = true,
            tailBackfill = false,
            forceTemp = true,
        ) ?: error("no forced-temp landing")

        assertEquals(0, landed.first)
        assertNotNull("switch must build a temp anchor page even when the disk table is ready", unit.inProgress)
        assertEquals(
            "landed page is the live temp anchor page",
            unit.inProgress?.currentSlice?.charStart,
            landed.second.charStart,
        )
        assertTrue(
            "page top must be the anchor line, not a canonical page start " +
                "(start=${landed.second.charStart}, anchor=$anchor)",
            landed.second.charStart <= anchor && anchor < landed.second.charEnd,
        )
    }

    /** 离章/跳转时才切磁盘表：`finalizeTempOnLeave` 是私有的，阅读面只能经几何相关的翻页到达。 */
    private fun finalizeTempOnLeave(unit: ChapterUnit, ip: InProgressPagination) {
        val m = BookDocumentController::class.java.getDeclaredMethod(
            "finalizeTempOnLeave", ChapterUnit::class.java, InProgressPagination::class.java,
        )
        m.isAccessible = true
        m.invoke(controller, unit, ip)
    }

    private fun paramHash(): Long {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        return LayoutParamKey.fromProfile(profile, contentW, contentH).hash()
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>TEMP</dc:title><dc:identifier id="bookid">urn:test:review-temp</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val big = (1..140).joinToString("\n") { p ->
            "<p>Paragraph $p. " + "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Chapter 0</h1>$big</body></html>").toByteArray(),
            "OEBPS/Text/c2.xhtml" to ("<html><body><h1>Chapter 1</h1>$big</body></html>").toByteArray(),
        )
    }
}
