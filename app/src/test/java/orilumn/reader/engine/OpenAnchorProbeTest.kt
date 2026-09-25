package orilumn.reader.engine

import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.read.ReadingLocatorCodec
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
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
 * Probe R5/S3: opening with a saved mid-chapter position must land on the containing page —
 * never head-lift (a rotation → new viewport → disk miss used to rebase block≤100 anchors to
 * chapter head, and the head page got persisted as progress, losing the position permanently).
 *
 * Fixture is a LARGE chapter (>120 blocks ⇒ temp anchor path on miss) with the anchor at ~40%
 * of the text but within the first 100 blocks (the old head-lift trip wire).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenAnchorProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private lateinit var cacheDir: java.io.File
    private val viewW = 720
    private val viewH = 1280

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
    fun `open with saved mid position lands on the containing page`() = runBlocking {
        // ~150 blocks x ~60 chars ≈ 9000 chars; anchor at ~40% (char 3600 ≈ block 60 ≤ 100).
        val anchor = 3600
        val saved = BookReadingState(
            bookId = 23L,
            chapter = 0,
            locator = ReadingLocatorCodec.encode(0, anchor),
        )
        assertTrue(controller.open(23L, saved = saved))
        controller.setViewport(viewW, viewH)
        // Fresh cache ⇒ disk miss ⇒ temp anchor path (no table to converge to).
        val hit = controller.locateStart()
        assertNotNull("open must land", hit)
        assertTrue(
            "landing must contain the saved anchor (was [${hit!!.second.charStart},${hit.second.charEnd}))",
            hit.second.charStart <= anchor && anchor < hit.second.charEnd,
        )
        assertTrue(
            "landing must not be the chapter head page",
            hit.second.charStart > 0,
        )
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>R5</dc:title><dc:identifier id="bookid">urn:test:r5</dc:identifier>
                </metadata>
                <manifest><item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/></manifest>
                <spine><itemref idref="c1"/></spine>
            </package>"""
        val body = (1..150).joinToString("\n") { p ->
            "<p>Paragraph $p of the chapter. " +
                "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
            "OEBPS/Text/c1.xhtml" to ("<html><body><h1>Chapter 1</h1>" + body + "</body></html>").toByteArray(),
        )
    }
}
