package orilumn.reader.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe (原则 §3.3/§3.5, 整改 D2): the d=1 chapter-edge full-layout fallback.
 *
 * Locks two properties, both about **identity and urgency**, not about pagination:
 *
 *  1. **Key domains are disjoint.** The edge fallback (`edge:<chapter>`) and the whole-book chapter
 *     scan (`b2:<chapter>`) are the SAME work at DIFFERENT urgencies (§3.5: 加急 vs 章距投机).
 *     Same-key dedup is "later submit wins", so a shared key let the lazy scan downgrade the urgent
 *     fallback into the chapter-distance queue — the trap the retired "跨章跳过" rule was avoiding.
 *     Distinct keys ARE the fix; the second test proves distinct keys actually survive dedup.
 *
 *  2. **Both sides escalate, each at its own d=1 tier.** Last page → next chapter at 第2档;
 *     first page → previous chapter at 第3档. Previously only head→prev existed, and only on the
 *     disk-table path.
 *
 * The escalation decision ([edgeEscalations]) is a pure function, so the probe verifies the SAME
 * code the scheduler path runs — no second copy to drift.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterEdgePrefillProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val viewW = 720
    private val viewH = 1280
    private val lastChapter = CHAPTERS - 1

    private lateinit var controller: BookDocumentController

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        controller.cacheRoot = temp.newFolder("cache").absolutePath.toPath()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    private fun keysAt(chapter: Int, page: Int, dir: Int): List<String> =
        controller.edgeEscalations(chapter, PAGES, page, dir, lastChapter)
            .map { (beyond, prio) -> "edge:$beyond@$prio" }

    // ───────────────────────────────────────────────────────────────
    // 1. Key domains are disjoint
    // ───────────────────────────────────────────────────────────────

    @Test
    fun `edge and chapter-scan keys never collide`() {
        // Both routes target chapter 3 with the same work; the keys must differ or dedup merges them.
        assertTrue(
            "the edge fallback and the chapter scan must use different keys",
            controller.edgeKey(3) != controller.scanKey(3),
        )
        assertEquals("edge:3", controller.edgeKey(3))
        assertEquals("b2:3", controller.scanKey(3))
    }

    @Test
    fun `distinct keys survive dedup - a shared key would drop the urgent work`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sched = TaskScheduler(scope, Dispatchers.Default, 1)
        try {
            val gate = CompletableDeferred<Unit>()
            val ran = java.util.Collections.synchronizedList(ArrayList<String>())
            sched.submit(TaskScheduler.Task("gate", 0) { gate.await() })
            withTimeout(5_000) { while (sched.runningCount() == 0) delay(10) }
            // Urgent edge first, lazy scan second — same chapter, same work, different urgency.
            sched.submit(TaskScheduler.Task("edge:3", TaskScheduler.PRIO_EDGE_BACKWARD) { ran.add("edge") })
            sched.submit(TaskScheduler.Task("b2:3", TaskScheduler.PRIO_B2_CHAPTER) { ran.add("scan") })
            gate.complete(Unit)
            withTimeout(10_000) { while (ran.size < 2) delay(10) }
            delay(300)
            assertTrue("both key domains must run, got $ran", ran.containsAll(listOf("edge", "scan")))
        } finally {
            scope.cancel()
        }
    }

    // ───────────────────────────────────────────────────────────────
    // 2. Both sides escalate, each at its own d=1 tier
    // ───────────────────────────────────────────────────────────────

    @Test
    fun `last page escalates to the next chapter at tier 2`() {
        val keys = keysAt(ACTIVE, PAGES - 1, dir = 1)
        assertTrue(
            "landing on the last page must escalate to the next chapter, got $keys",
            keys.contains("edge:${ACTIVE + 1}@${TaskScheduler.PRIO_EDGE_FORWARD}"),
        )
    }

    @Test
    fun `first page escalates to the previous chapter at tier 3`() {
        val keys = keysAt(ACTIVE, 0, dir = 1)
        assertTrue(
            "landing on the first page must escalate to the previous chapter, got $keys",
            keys.contains("edge:${ACTIVE - 1}@${TaskScheduler.PRIO_EDGE_BACKWARD}"),
        )
    }

    @Test
    fun `a mid-chapter landing escalates to neither neighbour`() {
        val keys = keysAt(ACTIVE, PAGES / 2, dir = 1)
        assertTrue("a mid-chapter page has both d=1 neighbours in-chapter, got $keys", keys.isEmpty())
    }

    @Test
    fun `escalation is symmetric in both flip directions`() {
        // Flipping backward off the last page: the missing neighbour is still the NEXT page, so the
        // forward escalation must fire regardless of travel direction.
        assertTrue(
            "backward flip off the last page still needs the next chapter",
            keysAt(ACTIVE, PAGES - 1, dir = -1).contains(
                "edge:${ACTIVE + 1}@${TaskScheduler.PRIO_EDGE_FORWARD}"
            ),
        )
        // And flipping forward off the first page still needs the previous chapter (head = handoff).
        assertTrue(
            "forward flip off the first page still needs the previous chapter",
            keysAt(ACTIVE, 0, dir = -1).contains(
                "edge:${ACTIVE - 1}@${TaskScheduler.PRIO_EDGE_BACKWARD}"
            ),
        )
    }

    @Test
    fun `a single-page chapter escalates on both sides`() {
        val keys = controller.edgeEscalations(ACTIVE, totalPages = 1, targetPage = 0, dir = 1, lastChapter = lastChapter)
            .map { (b, p) -> "$b@$p" }
        assertEquals("both d=1 neighbours are missing, so both escalate", 2, keys.size)
        assertTrue(keys.contains("${ACTIVE + 1}@${TaskScheduler.PRIO_EDGE_FORWARD}"))
        assertTrue(keys.contains("${ACTIVE - 1}@${TaskScheduler.PRIO_EDGE_BACKWARD}"))
    }

    @Test
    fun `no escalation past the book boundaries`() {
        assertTrue(
            "no chapter before 0",
            controller.edgeEscalations(0, PAGES, 0, 1, lastChapter).none { it.first < 0 },
        )
        assertTrue(
            "no chapter after the last one",
            controller.edgeEscalations(lastChapter, PAGES, PAGES - 1, 1, lastChapter).none { it.first > lastChapter },
        )
    }

    @Test
    fun `escalation outranks this chapter's own full layout and the chapter scan`() {
        // §3.4/§3.5: what the reader is about to enter is more urgent than this chapter's lazy
        // canonical pass. Lower priority number = more urgent.
        assertTrue(
            "edge(${TaskScheduler.PRIO_EDGE_BACKWARD}) must outrank B1(${TaskScheduler.PRIO_B1_CHAPTER})",
            TaskScheduler.PRIO_EDGE_BACKWARD < TaskScheduler.PRIO_B1_CHAPTER,
        )
        assertTrue(
            "edge(${TaskScheduler.PRIO_EDGE_FORWARD}) must outrank the scan(${TaskScheduler.PRIO_B2_CHAPTER})",
            TaskScheduler.PRIO_EDGE_FORWARD < TaskScheduler.PRIO_B2_CHAPTER,
        )
    }

    // ───────────────────────────────────────────────────────────────
    // Fixtures
    // ───────────────────────────────────────────────────────────────

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
                    <dc:title>Edge</dc:title><dc:identifier id="bookid">urn:test:edge</dc:identifier>
                </metadata>
                <manifest>$manifest</manifest>
                <spine>$spine</spine>
            </package>"""
        val chapters = (1..CHAPTERS).associate { n ->
            "OEBPS/Text/c$n.xhtml" to
                ("<html><body><h1>Chapter $n</h1><p>Body of chapter $n.</p></body></html>").toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }

    private companion object {
        const val CHAPTERS = 6
        const val ACTIVE = 2

        /** Stand-in page count for the pure-decision probes (the decision never touches the book). */
        const val PAGES = 20
    }
}
