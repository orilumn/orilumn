package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe T6 (P13): chunked canonical == sequential canonical.
 *
 * P13's U6k ([BoxChapterLayouter.fullLayoutChunked]) shapes a chapter's blocks across parallel chunk
 * workers, then stitches and paginates via the SAME completion as [BoxChapterLayouter.fullLayout]
 * (sequential): the parallel work is only the per-block StaticLayout shaping over disjoint leaves, so
 * the produced slices are equal page-by-page by construction. Locked in here:
 *
 *  1. **Unit equality**: `fullLayoutChunked` with parallelism 2 and 4 yields slices exactly equal to
 *     `fullLayout` (char/line/block ranges on every page + the same line stream length), across a
 *     large plain chapter AND a styled chapter (pre / lists / container margins / `display:none`
 *     leaves) — the chunk boundary must never change geometry.
 *  2. **Chunk-granularity abandon**: the per-block checkpoint makes a background cancel throw
 *     `CancellationException` out of the chunk path and never return a truncated product
 *     (mirrors T5's contract at block granularity).
 *  3. **Integration**: entering a large chapter (B1 anchor-canonical dispatch) shapes on the chunk
 *     workers and persists a disk table that equals a fresh sequential `fullLayout` canonical
 *     page-by-page — the production canonical is chunked and identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChunkedCanonicalEquivalenceProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 1280

    private fun prepare(html: String, css: String): ChapterPrepareResult {
        val root = converter.convert(html) ?: error("chapter parse failed")
        val profile = profile()
        return layouter.prepare(root, CssBundle(listOf(css)), profile, contentW, contentH)
    }

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT)

    // ─────────────────────────────────────────────────────────────
    // 1. Unit equality: chunked == sequential, page by page
    // ─────────────────────────────────────────────────────────────

    private fun assertSameSlices(label: String, seq: ChapterLayouter.ChapterLayoutProduct, chunked: ChapterLayouter.ChapterLayoutProduct) {
        assertEquals("$label: same page count", seq.slices.size, chunked.slices.size)
        assertEquals("$label: same line stream length", seq.layout.lineCount, chunked.layout.lineCount)
        for (i in seq.slices.indices) {
            val a = seq.slices[i]
            val b = chunked.slices[i]
            assertEquals("$label p$i: charStart", a.charStart, b.charStart)
            assertEquals("$label p$i: charEnd", a.charEnd, b.charEnd)
            assertEquals("$label p$i: firstLine", a.firstLine, b.firstLine)
            assertEquals("$label p$i: lastLineExclusive", a.lastLineExclusive, b.lastLineExclusive)
            assertEquals("$label p$i: blockStart", a.blockStart, b.blockStart)
            assertEquals("$label p$i: blockEndExclusive", a.blockEndExclusive, b.blockEndExclusive)
        }
    }

    private fun assertChunkedEqualsSequential(html: String, css: String, blocks: Int) {
        val prep = prepare(html, css)
        assertTrue("fixture must be large enough for real chunking (>$blocks blocks, was ${prep.totalBlocks})", prep.totalBlocks >= blocks)
        val seq = layouter.fullLayout(prep, profile(), contentW, 1280 - profile().marginTop - profile().marginBottom)
        val p2 = layouter.fullLayoutChunked(prep, profile(), 720, 1280 - profile().marginTop - profile().marginBottom, parallelism = 2)
        val p4 = layouter.fullLayoutChunked(prep, profile(), 720, 1280 - profile().marginTop - profile().marginBottom, parallelism = 4)
        assertSameSlices("plain p2", seq, p2)
        assertSameSlices("plain p4", seq, p4)
    }

    private fun plainHtml(blocks: Int): String =
        "<html><body><h1>Chunked</h1>" +
            (1..blocks).joinToString("\n") { p ->
                "<p>Paragraph $p: " + "filler for wrapping and page-break measurement. ".repeat(3) + "</p>"
            } +
            "</body></html>"

    @Test
    fun `chunked equals sequential on a large plain chapter for parallelism 2 and 4`() {
        assertChunkedEqualsSequential(plainHtml(240), FALLBACK_CSS, 240)
    }

    @Test
    fun `chunked equals sequential on a styled chapter with pre lists margins and hidden leaves`() {
        val html = """<html><body>
            <h1>Styled chapter</h1>
            ${(1..60).joinToString("\n") { "<p>Body paragraph $it with wrapping prose.</p>" }}
            <pre>function f() {
                line one of the pre block
                line two of the pre block with a very long line that must wrap inside the block
            }</pre>
            <ul>${(1..12).joinToString("") { "<li>List item $it </li>" }}</ul>
            <ol>${(1..8).joinToString("") { "<li>Ordered item $it </li>" }}</ol>
            <div class="block"><p>Nested paragraph inside a padded container.</p></div>
            ${(1..40).joinToString("\n") { "<p>Trailing paragraph $it with enough words to wrap.</p>" }}
            <p class="muted">This whole section is display:none and must be excluded from both paths.</p>
            <div class="muted"><p>Hidden too.</p></div>
            <p>Final visible paragraph.</p>
            </body></html>"""
        assertChunkedEqualsSequential(html, STYLED_CSS, 100)
    }

    // ─────────────────────────────────────────────────────────────
    // 2. Chunk-granularity abandon
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `chunked abandons at chunk granularity and never returns a partial product`() {
        val prep = prepare(plainHtml(240), FALLBACK_CSS)
        var calls = 0
        try {
            layouter.fullLayoutChunked(prep, profile(), contentW, 1100, parallelism = 4) {
                calls++
                if (calls == 3) throw CancellationException("T6 chunk cancel")
            }
            fail("a cancelled chunked fullLayout must NOT return a truncated product")
        } catch (e: CancellationException) {
            assertEquals("T6 chunk cancel", e.message)
        }
        assertTrue("checkpoint must fire on chunk workers (calls=$calls)", calls >= 3)
        assertTrue(
            "cancellation must abandon before every block is shaped (calls=$calls, blocks=${prep.totalBlocks})",
            calls < prep.totalBlocks,
        )
    }

    // ─────────────────────────────────────────────────────────────
    // 3. Integration: entering a large chapter persists a chunked canonical == sequential table
    // ─────────────────────────────────────────────────────────────

    @Test
    fun `entering a large chapter persists a chunked canonical table identical to sequential canonical`() = runBlocking {
        val controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        val cacheDir = temp.newFolder("cache")
        controller.cacheRoot = cacheDir.absolutePath.toPath()
        val viewW = 720
        val viewH = 1280
        val bookId = 31L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        // Enter chapter 1 (large): B1 dispatches the anchor-canonical, which shapes on the chunk
        // workers (fullLayoutChunked) and persists the disk table underneath.
        assertNotNull(controller.ensureChapterLayout(1, 0))
        withTimeout(30_000) {
            while (controller.unitAt(1)?.paginationTable == null) delay(20)
        }
        val bound = controller.unitAt(1)?.paginationTable ?: error("ch1 must have a table")

        // Independent sequential canonical: same profile/contentW/contentH/paramHash, fresh layouter.
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val contentW = viewW - profile.marginLeft - profile.marginRight
        val contentH = viewH - profile.marginTop - profile.marginBottom
        val root = converter.convert(DISK_CH_DOC) ?: error("fixture parse failed")
        val seqPrep = layouter.prepare(root, CssBundle(listOf(FALLBACK_CSS)), profile, contentW, contentH)
        val seq = layouter.fullLayout(seqPrep, profile, contentW, contentH)
        val hash = LayoutParamKey.fromProfile(profile, contentW, contentH).hash()

        assertEquals("B1 must persist under the current params", hash, bound.paramHash)
        assertEquals("same page count as sequential canonical", seq.slices.size, bound.pages.size)
        for (i in seq.slices.indices) {
            val s = seq.slices[i]
            val rec = bound.pages[i]
            assertEquals("p$i charStart", s.charStart, rec.charStart)
            assertEquals("p$i charEnd", s.charEnd, rec.charEnd)
            assertEquals("p$i blockStart", s.blockStart, rec.blockStart)
            assertEquals("p$i blockEndExclusive", s.blockEndExclusive, rec.blockEndExclusive)
        }
        assertTrue(
            "chunked canonical must cover the whole chapter",
            bound.pages.last().charEnd >= bound.totalChars,
        )
    }

    // ─────────────────────────────────────────────────────────────
    // Fixtures
    // ─────────────────────────────────────────────────────────────

    private fun docOf(blocks: Int): String =
        "<html><head><style>$FALLBACK_CSS</style></head><body><h1>Large</h1>" +
            (1..blocks).joinToString("\n") { p ->
                "<p>Paragraph $p: " + "filler for wrapping and page-break measurement. ".repeat(3) + "</p>"
            } +
            "</body></html>"

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>T6</dc:title><dc:identifier id="bookid">urn:test:t6</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xhtml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xhtml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        val chapters = (1..2).associate { n ->
            // 160 blocks > CHUNK_CANONICAL_MIN_BLOCKS (121): the canonical pass must shape on chunk workers.
            "OEBPS/Text/c$n.xhtml" to docOf(160).toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }

    private companion object {
        val FALLBACK_CSS = """
            * { margin: 0; padding: 0; border: 0; }
            html { font-size: 18px; }
            body { line-height: 1.3rem; font-size: 0.95rem; }
            h1 { text-align: center; margin: 1rem 0 3rem; font-size: 1.6rem; }
            p { line-height: 1.3rem; margin: 0 0 0.3rem; }
        """.trimIndent()

        // The fixture chapter carries FALLBACK_CSS as an embedded author stylesheet so the controller's
        // canonical (buildCssBundle resolves <style>) meets the test's independent sequential canonical
        // in the SAME css environment — otherwise the UA defaults inflate the controller's page count.
        val DISK_CH_DOC = "<html><head><style>$FALLBACK_CSS</style></head><body><h1>Large</h1>" +
            (1..160).joinToString("\n") { p ->
                "<p>Paragraph $p: " + "filler for wrapping and page-break measurement. ".repeat(3) + "</p>"
            } +
            "</body></html>"

        val STYLED_CSS = """
            ${FALLBACK_CSS}
            pre { margin: 0.6rem 1rem; padding: 0.4rem; border: 1px solid #999; white-space: pre-wrap; }
            ul, ol { margin: 0 0 0.3rem 1.5rem; }
            .block { margin: 0.4rem 0; padding: 0.2rem 0.4rem; }
            .muted { display: none; }
        """.trimIndent()
    }
}