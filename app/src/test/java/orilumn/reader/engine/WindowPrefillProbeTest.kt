package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe R3 (S7 steps 2–3): a disk-path landing publishes neighbor block shapes in the background.
 *
 * Guards the R3 machinery (dispatch conditions, guards, keyed publish). Consumption (L2 lookup
 * inside incremental shaping) is timing-shaped and verified on device via `win-prefill` logs +
 * `ensurePageRangeShaped` t= drops, not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WindowPrefillProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private lateinit var cacheDir: java.io.File
    private val viewW = 720
    private val viewH = 1280

    @After
    fun tearDown() {
        // R13: reclaim background shaping so worker-JVM neighbors run clean.
        if (::controller.isInitialized) controller.close()
    }

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        cacheDir = temp.newFolder("cache")
        controller.cacheRoot = cacheDir.absolutePath.toPath()
    }

    @Test
    fun `disk landing publishes next-page blocks`() = runBlocking {
        assertTrue(controller.open(11L, saved = null))
        controller.setViewport(viewW, viewH)
        // Small chapter: foreground full layout + disk table, no temp session.
        assertNotNull(controller.ensureChapterLayout(0, 0))
        val unit = controller.unitAt(0) ?: error("no unit 0")
        val table = unit.paginationTable ?: error("ch0 has no table")
        assertTrue("fixture needs 2+ pages", table.pages.size > 1)

        // The dispatch runs at the end of ensureChapterLayout; shaping starts after the
        // linger delay. Poll for the keyed publish.
        var snap: Triple<Int, Long, Set<Int>>? = null
        withTimeout(15_000) {
            while (snap == null) {
                val s = controller.windowPrefillSnapshotForTest()
                if (s != null && s.first == 0 && s.second == table.paramHash) snap = s
                else delay(50)
            }
        }
        val got = snap ?: error("no prefill publish")

        // Forward-first order (default direction): page 1's block range must be covered.
        val rec = table.pages[1]
        val want = (rec.blockStart.coerceAtLeast(0) until rec.blockEndExclusive).toSet()
        assertTrue("prefill must cover page 1 blocks", got.third.containsAll(want))
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>R3</dc:title><dc:identifier id="bookid">urn:test:r3</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        // ~90 blocks/chapter: below SMALL_CHAPTER_BLOCKS (120) so the landing is a foreground
        // full-layout (disk-hit path, no temp session), multi-page for neighbor coverage.
        fun chapter(n: Int): ByteArray {
            val body = (1..90).joinToString("\n") { p ->
                "<p>Paragraph $p of chapter $n. " +
                    "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
            }
            return ("<html><body><h1>Chapter $n</h1>" + body + "</body></html>").toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to chapter(1),
            "OEBPS/Text/c2.xhtml" to chapter(2),
        )
    }
}
