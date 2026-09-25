package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
 * Probe T5 (P12 half, R7 order): whole-book B2 scan order = strict absolute-distance interleave.
 *
 * P12 gives a pure ordering function, [orderRemainingChapters], used by the whole-book B2 scan
 * ([BookDocumentController.requestWholeBookRelayout] → [BookDocumentController.remainingScanOrder]):
 * 1-away both sides, then 2-away, and so on; ties at the same distance are broken by the reading
 * direction (the side the reader is heading toward first), so a flip-out-of-bounds into a nearby
 * chapter lands on an already-laid-out chapter. Correctness never depends on the order — every
 * non-current chapter still completes its full pass + persist, and the per-chapter checkpoints (P7)
 * keep abandonment ≤1 chapter.
 *
 * Locked in here:
 *  1. **Unit.** The pure function across direction/current/boundaries: interleave order,
 *     every chapter exactly once, edge chapters when current is at either end of the book.
 *  2. **Integration.** The tracked reading direction is driven by the REAL flip entries
 *     ([BookDocumentController.findAdjacentPage] — the single reader-facing page-turn, covering
 *     in-chapter temp/canonical and out-of-bounds cross-chapter flips — and the direct
 *     [BookDocumentController.nextPageInChapter]/[prevPageInChapter] curl-adjacent entries), and the
 *     B2 coroutine's order (via [BookDocumentController.remainingScanOrder]) follows it.
 *
 * The cancellation/abandonment half of T5 (≤1-chapter granularity, no truncated tables) is exercised
 * by [WholeBookCancellationProbeTest] (P7).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WholeBookScanOrderProbeTest {

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

    // ───────────────────────────────────────────────────────────────
    // 1. Unit: the pure ordering function
    // ───────────────────────────────────────────────────────────────

    @Test
    fun `forward interleaves by absolute distance, forward side first on ties`() {
        // total=8, current=3, forward: 1-away {4, 2}, 2-away {5, 1}, 3-away {6, 0}, 4-away {7}.
        assertEquals(listOf(4, 2, 5, 1, 6, 0, 7), orderRemainingChapters(total = 8, current = 3, direction = 1))
    }

    @Test
    fun `backward interleaves by absolute distance, backward side first on ties`() {
        // total=8, current=3, backward: 1-away {2, 4}, 2-away {1, 5}, 3-away {0, 6}, 4-away {7}.
        assertEquals(listOf(2, 4, 1, 5, 0, 6, 7), orderRemainingChapters(total = 8, current = 3, direction = -1))
    }

    @Test
    fun `zero direction falls into the forward group`() {
        // Direction is never tracked as 0 (defaults to +1), but the function must stay total.
        assertEquals(
            orderRemainingChapters(total = 5, current = 2, direction = 0),
            orderRemainingChapters(total = 5, current = 2, direction = 1),
        )
    }

    @Test
    fun `edge currents produce only the non-empty group in order`() {
        // current at the book head: forward is the whole tail.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), orderRemainingChapters(total = 8, current = 0, direction = 1))
        // backward from the head has no behind group.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), orderRemainingChapters(total = 8, current = 0, direction = -1))
        // current at the book tail: backward is the whole ascending-free head (nearest = total-2 first).
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), orderRemainingChapters(total = 8, current = 7, direction = -1))
        // forward from the tail has no ahead group.
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), orderRemainingChapters(total = 8, current = 7, direction = 1))
    }

    @Test
    fun `every chapter appears exactly once for every current and direction`() {
        for (total in 1..6) {
            for (current in 0 until total) {
                for (direction in listOf(-1, 1)) {
                    val order = orderRemainingChapters(total, current, direction)
                    assertEquals("total=$total current=$current dir=$direction: size", total - 1, order.size)
                    assertEquals(
                        "total=$total current=$current dir=$direction: permutation",
                        (0 until total).filter { it != current }.sorted(),
                        order.sorted(),
                    )
                    assertEquals("no duplicate chapter", order.size, order.distinct().size)
                }
            }
        }
        assertTrue(orderRemainingChapters(1, 0, 1).isEmpty())
    }

    // ───────────────────────────────────────────────────────────────
    // 2. Integration: flips drive the direction, B2 order follows it
    // ───────────────────────────────────────────────────────────────

    @Test
    fun `page turns track the reading direction and reorder the B2 scan`() = runBlocking {
        val bookId = 9L
        assertTrue(controller.open(bookId, saved = null))
        controller.setViewport(viewW, viewH)
        // Lay out all chapters so ch1 has page slices to flip from.
        for (ch in 0..2) assertNotNull(controller.ensureChapterLayout(ch, 0))

        // Default is forward (the dominant reading direction).
        assertEquals("default reading direction is forward", 1, controller.currentReadingDirection())

        val ch1 = controller.unitAt(1) ?: error("no ch1")
        val page = ch1.pageSlices.firstOrNull() ?: error("ch1 has no pages")
        assertTrue("fixture must lay out ch1 with pages", page.charEnd > 0)

        // A backward page turn (the reader-facing flip entry) tracks -1 and biases B2 backward-first.
        val back = controller.findAdjacentPage(1, page, -1)
        assertEquals("backward flip must set the reading direction", -1, controller.currentReadingDirection())
        assertEquals("B2 order follows backward: behind, then ahead", listOf(0, 2), controller.remainingScanOrder(1))
        assertNotNull("backward flip must land somewhere", back)

        // A forward page turn tracks +1 and biases B2 forward-first.
        val fwd = controller.findAdjacentPage(1, page, 1)
        assertEquals("forward flip must set the reading direction", 1, controller.currentReadingDirection())
        assertEquals("B2 order follows forward: ahead, then behind", listOf(2, 0), controller.remainingScanOrder(1))
        assertNotNull("forward flip must land somewhere", fwd)

        // The direct curl-adjacent entries set the direction too.
        controller.nextPageInChapter(ch1, page)
        assertEquals(1, controller.currentReadingDirection())
        controller.prevPageInChapter(ch1, page)
        assertEquals(-1, controller.currentReadingDirection())
    }

    // ───────────────────────────────────────────────────────────────
    // Fixtures
    // ───────────────────────────────────────────────────────────────

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>P12</dc:title><dc:identifier id="bookid">urn:test:p12</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c3" href="Text/c3.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
            </package>"""
        val chapters = (1..3).associate { n ->
            val body = (1..30).joinToString("\n") { p ->
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