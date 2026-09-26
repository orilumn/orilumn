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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import okio.Path.Companion.toPath
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe R17: flips on the disk path store assembled page products; the store is synchronous
 * in the ensure path (no timing involved — deterministic). Consumption (zero-work bind on
 * revisit) is verified on device via `pagecache-hit` logs + `ensurePageRangeShaped` t= collapse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageCacheProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var cacheDir: java.io.File
    private val viewW = 720
    private val viewH = 1280

    private val ownedControllers = mutableListOf<BookDocumentController>()

    @After
    fun tearDown() {
        // R13: reclaim background shaping so worker-JVM neighbors run clean.
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

    @Test
    fun `flips store assembled page products`() = runBlocking {
        // Session 1: lay out so the DISK table lands under the current params.
        val a = newController()
        assertTrue(a.open(31L, saved = null))
        a.setViewport(viewW, viewH)
        assertNotNull(a.ensureChapterLayout(0, 0))
        val table = a.unitAt(0)?.paginationTable ?: error("no table")
        assertTrue("fixture needs 3+ pages", table.pages.size > 2)

        // Session 2 (fresh controller, same cache): disk-hit landing, then two flips.
        // Each flip's ensure shapes its target page and stores the product synchronously.
        val b = newController()
        assertTrue(b.open(31L, saved = null))
        b.setViewport(viewW, viewH)
        assertNotNull(b.ensureChapterLayout(0, 0))
        var slice = b.unitAt(0)?.pageSlices?.firstOrNull() ?: error("no page 0")
        val fwd1 = b.findAdjacentPage(0, slice, 1) ?: error("flip 1 failed")
        assertTrue("flip 1 must store page 1", b.pageCacheKeysForTest(0).contains(1))
        slice = fwd1.second
        val fwd2 = b.findAdjacentPage(0, slice, 1) ?: error("flip 2 failed")
        assertTrue("flip 2 must store page 2", b.pageCacheKeysForTest(0).contains(2))
        assertNotNull(fwd2)
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>R17</dc:title><dc:identifier id="bookid">urn:test:r17</dc:identifier>
                </metadata>
                <manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
        // Small chapter (<120 blocks ⇒ foreground full layout, disk-hit incremental path).
        val body = (1..90).joinToString("\n") { p ->
            "<p>Paragraph $p. " +
                "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Chapter 1</h1>" + body + "</body></html>").toByteArray(),
        )
    }
}
