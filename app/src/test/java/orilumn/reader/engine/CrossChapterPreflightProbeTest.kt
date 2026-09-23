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
 *  1. A flip landing in ch1 prewarms BOTH neighbors (ch0, ch2) — their markup moves off the flip
 *     thread to the background preflight even though neither was ever laid out or visited.
 *  2. The out-of-bounds crossing into ch2 happens strictly AFTER ch2's markup was already parsed by
 *     preflight, i.e. the parse is no longer on the crossing's critical path.
 *  3. A typography-param change voids the readiness record (next preflight must re-run).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrossChapterPreflightProbeTest {

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
    }

    private suspend fun awaitPrepared(chapter: Int) {
        withTimeout(15_000) {
            while (!controller.isChapterPreflightReady(chapter)) delay(20)
        }
    }

    @Test
    fun `a flip landing prewarms both neighbors off the flip thread and the crossing is parse-free`() = runBlocking {
        val bookId = 11L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        // Only the active chapter is laid out; opening a book does not parse every body.
        val ch1 = controller.ensureChapterLayout(1, 0) ?: error("no ch1")
        assertNotNull("ch1 must be laid out", ch1.pageSlices.firstOrNull())
        assertTrue("ch1 needs ≥2 pages for an in-chapter flip (was ${ch1.pageSlices.size})", ch1.pageSlices.size >= 2)
        assertNull("open must not parse ch0 yet", controller.unitAt(0)?.markup)
        assertNull("open must not parse ch2 yet", controller.unitAt(2)?.markup)

        // In-chapter forward flip lands in ch1 → prewarms ch0 AND ch2 in the background.
        val first = controller.findAdjacentPage(1, ch1.pageSlices[0], 1)
        assertNotNull("forward flip must land", first)
        assertEquals("in-chapter flip stays in ch1", 1, first!!.first)

        awaitPrepared(0)
        awaitPrepared(2)
        assertTrue("preflight must prep both neighbors", controller.isChapterPreflightReady(0) && controller.isChapterPreflightReady(2))
        assertEquals("ch0 markup parsed by preflight", true, controller.unitAt(0)?.markup != null)
        assertEquals("ch2 markup parsed by preflight", true, controller.unitAt(2)?.markup != null)

        // Advance to the LAST page of ch1 without crossing (the forward flip past it is the crossing).
        var current = ch1.pageSlices[0]
        for (i in 1 until ch1.pageSlices.size) {
            val res = controller.findAdjacentPage(1, current, 1)
            assertNotNull(res)
            assertEquals("advancing within ch1 must stay in ch1", 1, res!!.first)
            current = ch1.pageSlices[i]
        }
        assertEquals("current is the last ch1 page", ch1.pageSlices.last(), current)

        // The out-of-bounds flip lands in ch2 — its parse already happened in the background.
        assertTrue("ch2 parse must have completed before the crossing", controller.unitAt(2)?.markup != null)
        val cross = controller.findAdjacentPage(1, current, 1)
        assertNotNull("forward crossing must land", cross)
        assertEquals("crossing lands in ch2", 2, cross!!.first)
        assertTrue("ch2 is laid out after the crossing", controller.unitAt(2)?.pageSlices?.isNotEmpty() == true)
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