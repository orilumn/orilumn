package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe (P4): cross-chapter preflight — parse leaves the flip thread.
 *
 * P4 adds a background P track: once a flip lands in a chapter, BOTH neighbors (the chapters a next
 * out-of-bounds flip can enter) get `ensureMarkup` + `prepareLight` up front on
 * [BookDocumentController]'s background dispatcher, keyed by paramHash and idempotent. The crossing
 * then hits an already-parsed chapter — `crossChapterLanding`'s [BookDocumentController.ensureChapterLayout]
 * skips its parse/light-prepare and lands through the existing window/anchor shaping alone. A param
 * change ([BookDocumentController.prepareRelayout]) voids the readiness record and cancels the slots.
 *
 * Locked in here:
 *  1. A typography-param change voids the readiness record (next preflight must re-run).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossChapterPreflightProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
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
    }

    private suspend fun awaitPrepared(chapter: Int) {
        withTimeout(15_000) {
            while (!controller.isChapterPreflightReady(chapter)) delay(20)
        }
    }

    @Test
    fun `a param change voids the preflight readiness record`() = runBlocking {
        val bookId = 12L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        val ch1 = controller.ensureChapterLayout(1, 0) ?: error("no ch1")
        assertTrue(ch1.pageSlices.size >= 2)

        val land = controller.findAdjacentPage(1, ch1.pageSlices[0], 1)
        assertEquals(1, land!!.first)
        awaitPrepared(2)
        assertTrue(controller.isChapterPreflightReady(2))

        // A typography-affecting change goes through prepareRelayout (bumps the epoch) — the old
        // param-cycle readiness is void; a new preflight is re-dispatched on the next flip.
        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(fontScale = 55.0))
        assertNotNull("prepareRelayout must succeed", controller.prepareRelayout(1, 0))
        assertFalse("param change must void the readiness record", controller.isChapterPreflightReady(2))
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P4</dc:title><dc:identifier id="bookid">urn:test:p4</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="Text/c3.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
            </package>"""
        val chapters = (1..3).associate { n ->
            val body = (1..90).joinToString("\n") { p ->
                "<p>Paragraph $p of chapter $n. " + "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
            }
            "OEBPS/Text/c$n.xhtml" to ("<html><body><h1>Chapter $n</h1>" + body + "</body></html>").toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }
}