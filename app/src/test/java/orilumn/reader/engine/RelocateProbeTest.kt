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
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe R12: `relayoutTo` inside an already-shaped valid window just moves the pointer —
 * no [BookDocumentController.invalidateAllLayouts] nuke. Observed via table object identity
 * (a nuke clears + rebuilds the table, so identity can only survive the fast path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelocateProbeTest {

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
    fun `relocate inside shaped window skips nuke`() = runBlocking {
        assertTrue(controller.open(12L, saved = null))
        controller.setViewport(viewW, viewH)
        // Small chapter: foreground full layout + disk table, multi-page.
        assertNotNull(controller.ensureChapterLayout(0, 0))
        val unit = controller.unitAt(0) ?: error("no unit 0")
        val tableBefore = unit.paginationTable ?: error("ch0 has no table")
        val page0 = unit.pageSlices.firstOrNull() ?: error("ch0 has no pages")
        assertTrue("page 0 must be shaped", page0.firstLine >= 0)

        // Jump to the middle of the already-shaped first page.
        val mid = (page0.charStart + page0.charEnd) / 2
        val hit = controller.relayoutTo(0, mid)
        assertNotNull("fast relocate must land", hit)
        assertEquals(0, hit!!.first)
        assertTrue(
            "landing must contain the anchor",
            hit.second.charStart <= mid && mid < hit.second.charEnd,
        )
        assertSame("fast path must not nuke the table", tableBefore, unit.paginationTable)
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>R12</dc:title><dc:identifier id="bookid">urn:test:r12</dc:identifier>
                </metadata>
                <manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
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
