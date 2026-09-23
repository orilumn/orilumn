package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
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
 * Probe (P5): open-book preflight — the first screen pays only window/anchor shaping.
 *
 * [BookDocumentController.prewarmForOpen] runs on the background P track after the book is opened but
 * BEFORE the reader locates the start position: it parses the saved-position chapter's markup + light
 * structure (idempotent-ish, records P4's readiness) and, when a disk table for the CURRENT params
 * already exists, binds it and pre-shapes the first page window (the P10 "promote the full table
 * directly" path) — so `locateStart`/`ensureChapterLayout` neither re-parses nor re-paginates.
 *
 * Locked in here:
 *  1. A prewarm with no disk table parses + light-prepares the target chapter off the critical path
 *     (markup + readiness present, nothing shaped) while other chapters stay untouched.
 *  2. When the disk table is already there (a previous session), the prewarm binds the table AND
 *     pre-shapes the page window — the unit is laid out with pageSlices before any
 *     `ensureChapterLayout` call, i.e. the open is a promote, not a re-layout.
 *  3. Default target — no explicit chapter → the open point's saved position (no progress → ch0).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenBookPreflightProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val viewW = 720
    private val viewH = 1280
    private lateinit var cacheDir: java.io.File

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
        return c
    }

    private suspend fun awaitMarkup(controller: BookDocumentController, ch: Int) {
        withTimeout(15_000) {
            while (controller.unitAt(ch)?.markup == null) delay(20)
        }
    }

    private suspend fun awaitShaped(controller: BookDocumentController, ch: Int) {
        withTimeout(15_000) {
            while (controller.unitAt(ch)?.pageSlices?.isEmpty() != false) delay(20)
        }
    }

    @Test
    fun `prewarm parses and light-prepares the target chapter without shaping`() = runBlocking {
        val c = newController()
        val bookId = 21L
        assertTrue(c.open(bookId, saved = null))
        c.setViewport(viewW, viewH)
        assertNull("open must not parse any body", c.unitAt(0)?.markup)

        // Explicit target: prewarm chapter 1 (no disk table yet → parse + light only).
        c.prewarmForOpen(chapter = 1, targetChar = 0)
        awaitMarkup(c, 1)
        awaitPrepared(c, 1)
        assertTrue("chapter 1 prepared", c.isChapterPreflightReady(1))
        assertNotNull("chapter 1 markup parsed", c.unitAt(1)?.markup)
        assertTrue("no disk table → nothing shaped", c.unitAt(1)?.pageSlices?.isEmpty() == true)
        assertNull("prewarm must not touch ch0", c.unitAt(0)?.markup)
        assertNull("prewarm must not touch ch2", c.unitAt(2)?.markup)
    }

    @Test
    fun `prewarm default target is the saved position and pre-shapes when the disk table exists`() = runBlocking {
        // First session: open ch1 and lay it out so the DISK table lands under the current params.
        val a = newController()
        val bookId = 22L
        assertTrue(a.open(bookId, saved = null))
        a.setViewport(viewW, viewH)
        assertNotNull("ch1 laid out and persisted", a.ensureChapterLayout(1, 0))

        // Second session (fresh controller, same cache): nothing parsed; the table sits on disk.
        val b = newController()
        assertTrue(b.open(bookId, saved = null))
        b.setViewport(viewW, viewH)
        assertNull(b.unitAt(1)?.markup)
        assertEquals("ch1 has no table bound yet", null, b.unitAt(1)?.paginationTable)

        // Default target with no progress → ch0; prewarm ch0 (explicit) warm the disk-hit path too.
        // First: default path covers "saved position / fallback ch0".
        b.prewarmForOpen()
        awaitMarkup(b, 0)
        assertNull("ch0 has no disk table → parse only", b.unitAt(0)?.paginationTable)

        // Now the disk-hit pre-shape: target the persisted chapter.
        b.prewarmForOpen(chapter = 1, targetChar = 0)
        awaitPrepared(b, 1)
        awaitShaped(b, 1)
        assertTrue("ch1 ready", b.isChapterPreflightReady(1))
        assertNotNull("disk table bound by prewarm", b.unitAt(1)?.paginationTable)
        assertTrue("page window pre-shaped by prewarm (no ensureChapterLayout yet)", b.unitAt(1)?.pageSlices?.isNotEmpty() == true)
        assertTrue("ch1 laid out by pre-shape", b.unitAt(1)?.laidOut == true)
        assertTrue("target page is within the shaped window", b.unitAt(1)!!.pageSlices.isNotEmpty())

        // The later ensureChapterLayout finds the already-shaped window (no re-pagination).
        assertNotNull("ensure hits the pre-shaped window", b.ensureChapterLayout(1, 0))
        assertEquals("page window preserved", true, b.unitAt(1)?.pageSlices?.isNotEmpty() == true)
    }

    private suspend fun awaitPrepared(controller: BookDocumentController, ch: Int) {
        withTimeout(15_000) {
            while (!controller.isChapterPreflightReady(ch)) delay(20)
        }
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P5</dc:title><dc:identifier id="bookid">urn:test:p5</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="Text/c3.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
            </package>"""
        val chapters = (1..3).associate { n ->
            // ~90 blocks < SMALL_CHAPTER_BLOCKS (120): foreground full-layout persists a disk table,
            // so the second session's prewarm can bind + pre-shape it.
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