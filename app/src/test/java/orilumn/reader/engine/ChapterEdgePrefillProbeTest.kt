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
import org.junit.Assert.assertNotEquals
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
 * The escalation *decision* now lives in [neighborSequence] and is verified by
 * [NeighborSequenceProbeTest]; this probe keeps the two things that probe cannot reach:
 *
 *  1. **Key domains are disjoint** — the edge fallback (`edge:<chapter>`) and the whole-book scan
 *     (`b2:<chapter>`) are the SAME work at DIFFERENT urgencies. Same-key dedup is "later submit
 *     wins", so a shared key lets the lazy scan downgrade the urgent fallback into the
 *     chapter-distance queue. The second test proves distinct keys actually survive dedup.
 *
 *  2. **The two keys are real production strings**, not literals invented by the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChapterEdgePrefillProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(emptyMap()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        controller.cacheRoot = temp.newFolder("cache").absolutePath.toPath()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test
    fun `edge and chapter-scan keys never collide`() {
        // Same chapter, both routes to the same work; the keys must differ or dedup merges them.
        assertNotEquals(
            "the edge fallback and the chapter scan must use different keys",
            controller.edgeKey(3),
            controller.scanKey(3),
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

    @Test
    fun `the edge tiers sit between the neighbour work and this chapter's full layout`() {
        // §3.3/§3.4: an adjacent chapter the reader is about to enter is more urgent than this
        // chapter's lazy canonical pass, and a peer of the d=1 neighbour pages.
        assertEquals("forward edge is a peer of 第2档", TaskScheduler.PRIO_PAGE_NEXT, TaskScheduler.PRIO_EDGE_FORWARD)
        assertEquals("backward edge is a peer of 第3档", TaskScheduler.PRIO_PAGE_PREV, TaskScheduler.PRIO_EDGE_BACKWARD)
        assertTrue(
            "edge(${TaskScheduler.PRIO_EDGE_BACKWARD}) must outrank B1(${TaskScheduler.PRIO_B1_CHAPTER})",
            TaskScheduler.PRIO_EDGE_BACKWARD < TaskScheduler.PRIO_B1_CHAPTER,
        )
        assertTrue(
            "edge(${TaskScheduler.PRIO_EDGE_FORWARD}) must outrank the scan(${TaskScheduler.PRIO_B2_CHAPTER})",
            TaskScheduler.PRIO_EDGE_FORWARD < TaskScheduler.PRIO_B2_CHAPTER,
        )
    }
}
