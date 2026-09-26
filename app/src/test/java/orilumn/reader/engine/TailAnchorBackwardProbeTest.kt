package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe（尾锚倒包）：无磁盘表时回翻落上章末页，锚点页必须与 -1/-2/-3… 链同制式——
 * 由 [BoxChapterLayouter.shapeTempPageBackward] 从文末倒包一整页，而不是前向灌的末尾几行。
 *
 * 旧行为：`shapeAnchorPageForward` 从文末锚（textLength-1）前向灌，落位页恒为最后一行
 * 左右（+边距）；而随后的 -1/-2 页是倒包的——尾页与全链制式不一。
 * 新行为：前向灌撞顶（`nextBlock >= totalBlocks`）即改倒包；落位满屏页，且后向链
 * 从此页起向后铺（`shapeNextBackward` 以 Fwd(0) 为头缘）， tiling 在页首处咬合。
 *
 * 两章皆大章（>120 块）：ch0 无表，回翻落尾必走 temp 锚点流（小章前台整章不经过此分支）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TailAnchorBackwardProbeTest {

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
    fun `tail landing without table is a full backward-packed page tiling with the chain`() = runBlocking {
        assertTrue(controller.open(31L, saved = null))
        controller.setViewport(viewW, viewH)

        // ch1 首页（temp 出生在章首，首翻即跨章）→ 回翻落 ch0 尾（ch0 无表，大章 temp 锚点流）。
        val (_, head) = controller.openChapterStart(1) ?: error("no ch1 head")
        val (ch, tail) = controller.findAdjacentPage(1, head, -1) ?: error("no cross landing")
        assertEquals("must land the previous chapter tail", 0, ch)

        // 满屏性：倒包页含大量字符（前向灌只剩末行约百字符内；阈值 500 留足余量）。
        val span = tail.charEnd - tail.charStart
        assertTrue("tail page must be a full backward-packed page, not the last-line remnant (span=$span)", span > 500)

        // 同制式：再往回翻一页，两页在尾页页首处咬合（无缝无叠）。
        val (ch2, prev) = controller.findAdjacentPage(0, tail, -1) ?: error("no chain page")
        assertEquals(0, ch2)
        assertEquals("backward chain must tile exactly at the tail page start", tail.charStart, prev.charEnd)
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>TAIL</dc:title><dc:identifier id="bookid">urn:test:tail</dc:identifier>
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
