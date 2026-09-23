package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import java.util.concurrent.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe T5 (P7): timely abandonment.
 *
 * P7 adds a between-blocks cancellation checkpoint to [BoxChapterLayouter.fullLayout] and threads
 * `ctx.ensureActive()` into it from the two background canonical callers (B1 anchor-canonical, B2
 * whole-book scan). Two properties are locked in here:
 *
 *  1. **Unit (deterministic).** `fullLayout` invokes the checkpoint once per block and a throwing
 *     checkpoint aborts the shape loop with a `CancellationException` — it must NEVER return a
 *     truncated product (a short product bound/persisted would corrupt the line-level disk table).
 *     The default no-op checkpoint keeps the pre-existing behaviour byte-identical.
 *
 *  2. **Integration.** Rapid consecutive param changes cancel an in-flight whole-book scan; the final
 *     state must be the LAST paramHash on every non-active chapter, every persisted table must cover the
 *     FULL chapter (no partial table from an aborted shape), and the active chapter stays owned by the
 *     anchor path. The disk file for the last hash must exist.
 *
 * The scan-order half of T5 (direction x distance) belongs to P12 (Phase 2) and is not exercised here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeBookCancellationProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val viewW = 720
    private val viewH = 1280

    // ───────────────────────────────────────────────────────────────
    // 1. Unit: fullLayout abandons between blocks, never returns a partial product
    // ───────────────────────────────────────────────────────────────

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()

    @Test
    fun `fullLayout abandons between blocks on cancellation and never returns a partial product`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        val root = converter.convert(bigChapterHtml(150)) ?: error("chapter parse failed")
        val bundle = CssBundle(listOf(FALLBACK_CSS))
        val contentW = 720
        val prep = layouter.prepare(root, bundle, profile, contentW, viewH)

        assertTrue(
            "fixture must have many leaves so the checkpoint fires between blocks (was ${prep.totalBlocks})",
            prep.totalBlocks >= 8,
        )

        // Default hook — unchanged behaviour: a complete product covering the whole chapter.
        val product = layouter.fullLayout(prep, profile, contentW, viewH)
        assertTrue("default fullLayout must produce pages", product.slices.isNotEmpty())
        assertEquals(
            "default fullLayout must cover the whole chapter",
            prep.totalChars,
            product.slices.last().charEnd,
        )

        // Throwing hook — abandon mid-shape. The exception must propagate; no truncated product.
        var calls = 0
        val cancelAt = 4
        try {
            layouter.fullLayout(prep, profile, contentW, viewH) {
                calls++
                if (calls >= cancelAt) throw CancellationException("P7 mid-block cancel")
            }
            fail("a cancelled fullLayout must NOT return a truncated product")
        } catch (e: CancellationException) {
            assertEquals("P7 mid-block cancel", e.message)
        }
        assertTrue("checkpoint must run between block shapes (calls=$calls)", calls >= cancelAt)
        assertTrue(
            "cancellation must abandon before shaping every block (calls=$calls, blocks=${prep.totalBlocks})",
            calls < prep.totalBlocks,
        )
    }

    // ───────────────────────────────────────────────────────────────
    // 2. Integration: mid-flight whole-book cancel leaves only complete, last-hash tables
    // ───────────────────────────────────────────────────────────────

    private lateinit var controller: BookDocumentController
    private lateinit var cacheDir: java.io.File

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

    private fun paramHash(settings: ReaderSettings): Long {
        val profile = TypographicProfile.build(settings)
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        return LayoutParamKey.fromProfile(profile, contentW, contentH).hash()
    }

    private suspend fun cycle(settings: ReaderSettings): Long {
        controller.profile = TypographicProfile.build(settings)
        assertNotNull("prepareRelayout must succeed", controller.prepareRelayout(ACTIVE, anchorChar = 0))
        controller.requestWholeBookRelayout()
        return paramHash(settings)
    }

    private suspend fun awaitParamHash(chapter: Int, want: Long) {
        val unit = controller.unitAt(chapter) ?: error("no unit $chapter")
        withTimeout(30_000) {
            while (unit.paginationTable?.paramHash != want) delay(20)
        }
    }

    /** A table is complete iff its last page ends where the chapter text ends — an aborted shape that
     *  somehow escaped would produce a short table whose last page ends early. */
    private fun assertComplete(chapter: Int, expectedHash: Long) {
        val unit = controller.unitAt(chapter) ?: error("no unit $chapter")
        val table = unit.paginationTable ?: error("ch$chapter has no table")
        assertEquals("ch$chapter must carry the last hash", expectedHash, table.paramHash)
        assertTrue("ch$chapter table must have pages", table.pages.isNotEmpty())
        assertTrue(
            "ch$chapter table must cover the whole chapter (last=${table.pages.last().charEnd}, total=${table.totalChars})",
            table.pages.last().charEnd >= table.totalChars,
        )
    }

    @Test
    fun `mid-flight whole-book cancels settle on the last hash with no truncated table`() = runBlocking {
        val bookId = 7L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        // Load markup + a default-hash table for every chapter.
        for (ch in 0 until CHAPTERS) assertNotNull(controller.ensureChapterLayout(ch, 0))

        val defaultHash = paramHash(ReaderSettings.DEFAULT)
        val startEpoch = controller.layoutEpoch

        // Three rapid param cycles; the first two scans get cancelled mid-flight as the next lands.
        val hA = cycle(ReaderSettings.DEFAULT)
        delay(30)
        val hB = cycle(ReaderSettings.DEFAULT.copy(fontScale = 52.0))
        delay(30)
        val hC = cycle(ReaderSettings.DEFAULT.copy(fontScale = 58.0))
        assertTrue("three cycles must bump the epoch thrice", controller.layoutEpoch >= startEpoch + 3)
        assertTrue("hashes must differ", hA != hB && hB != hC)

        // Every non-active chapter must end on the LAST hash, with a COMPLETE table (no truncation).
        for (ch in 0 until CHAPTERS) {
            if (ch == ACTIVE) continue
            awaitParamHash(ch, hC)
            assertComplete(ch, hC)
        }

        // The active chapter is owned by B1/anchoring — its DEFAULT table is untouched by B2.
        val active = controller.unitAt(ACTIVE) ?: error("no active unit")
        assertEquals("B2 must skip the active chapter", defaultHash, active.paginationTable?.paramHash)

            // The last hash is also persisted on disk for every non-active chapter.
            // (C1-2: probed through the shared okio store — the same bytes the controller wrote.)
            val disk = PaginationCacheStore(FileSystem.SYSTEM, cacheDir.absolutePath.toPath())
            for (ch in 0 until CHAPTERS) {
                if (ch == ACTIVE) continue
                val f = disk.file("book_$bookId", ch, hC)
                assertTrue("ch$ch must be persisted on disk with the last hash", FileSystem.SYSTEM.exists(f))
            }
    }

    // ───────────────────────────────────────────────────────────────
    // Fixtures
    // ───────────────────────────────────────────────────────────────

    private fun bigChapterHtml(blocks: Int): String =
        "<html><body><h1>Big</h1>" +
            (1..blocks).joinToString("\n") { p ->
                "<p>第 $p 段。这是一段用于产生多行文本的正文，包含足够的字符以便分行并为整章贡献可测量的排版工作量。</p>"
            } +
            "</body></html>"

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val manifest = (1..CHAPTERS).joinToString("\n") {
            """<item id="c$it" href="Text/c$it.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spine = (1..CHAPTERS).joinToString("\n") { """<itemref idref="c$it"/>""" }
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P7</dc:title><dc:identifier id="bookid">urn:test:p7</dc:identifier>
                </metadata>
                <manifest>$manifest</manifest>
                <spine>$spine</spine>
            </package>"""
        val chapters = (1..CHAPTERS).associate { n ->
            // ~90 blocks/chapter: below SMALL_CHAPTER_BLOCKS (120) so ensureChapterLayout is a foreground
            // full-layout (no temp session), while still giving B2 enough shaping work to be cancelled
            // mid-chapter.
            val body = (1..90).joinToString("\n") { p ->
                "<p>Paragraph $p of chapter $n. " +
                    "some filler text for measuring line wraps and page breaks. ".repeat(2) + "</p>"
            }
            "OEBPS/Text/c$n.xhtml" to ("<html><body><h1>Chapter $n</h1>" + body + "</body></html>").toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }

    private companion object {
        const val CHAPTERS = 8
        const val ACTIVE = 1

        val FALLBACK_CSS = """
            * { margin: 0; padding: 0; border: 0; }
            html { font-size: 18px; }
            body { line-height: 1.3rem; font-size: 0.95rem; }
            h1 { text-align: center; margin: 1rem 0 3rem; font-size: 1.6rem; }
            p { line-height: 1.3rem; margin: 0 0 0.3rem; }
        """.trimIndent()
    }
}
