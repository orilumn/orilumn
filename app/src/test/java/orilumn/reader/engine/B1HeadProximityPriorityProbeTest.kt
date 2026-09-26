package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Probe (原则 §3.4, 整改 D2b): the chapter-head-proximity boost for the chapter's own full layout.
 *
 * 原则 §3.4 makes 本章全量 a **dynamic** tier rather than the fixed 第6档:
 *
 * | 目标页            | 本章全量档位 |
 * |-------------------|--------------|
 * | 第 1 页（章首页） | 同步承接，不走后台档 |
 * | **第 2 页**       | **第 2/3 档（加急）** |
 * | 第 3 页及以后     | 第 6 档 |
 *
 * 原因：章首页是增量 → 全量的**交接点**（S5），离它越近全量越紧急。d≥2 不升级，与 §3.3
 * 同一把尺子——隔两页仍不翻就不值得为它动整章。
 *
 * The decision ([b1PriorityFor]) is a pure function of `d`, so this probe exercises the same code
 * the scheduler runs. It also pins the *relationships* the principle depends on (the boost must be
 * a peer of the d=1 neighbour work and must outrank every lazy tier).
 *
 * No book is opened: the decision reads no book state, so an empty reader is enough.
 */
class B1HeadProximityPriorityProbeTest {

    private lateinit var controller: BookDocumentController

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(emptyMap()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    private fun prio(d: Int?) = controller.b1PriorityFor(d)

    @Test
    fun `d=1 (second page) takes the urgent tier`() {
        assertEquals(
            "targeting page 2 must boost the chapter's full layout to the urgent tier",
            TaskScheduler.PRIO_B1_URGENT,
            prio(1),
        )
    }

    @Test
    fun `d=0 (chapter head) does not boost - the handoff is synchronous there`() {
        assertEquals(
            "on the chapter head the full table is bound synchronously, no background tier",
            TaskScheduler.PRIO_B1_CHAPTER,
            prio(0),
        )
    }

    @Test
    fun `d two or beyond does not boost - same yardstick as the cross-chapter rule`() {
        for (d in 2..8) {
            assertEquals(
                "d=$d is too far from the handoff to justify a full-chapter shape",
                TaskScheduler.PRIO_B1_CHAPTER,
                prio(d),
            )
        }
    }

    @Test
    fun `unknown d (predecessor not shaped yet) does not boost`() {
        assertEquals(
            "an undecidable distance must fall back to the base tier, never gamble on the boost",
            TaskScheduler.PRIO_B1_CHAPTER,
            prio(null),
        )
    }

    @Test
    fun `only d=1 is boosted - the boost is a single distance, not a range`() {
        val boosted = (-1..12).filter { prio(it) == TaskScheduler.PRIO_B1_URGENT }
        assertEquals("exactly one distance value may boost", listOf(1), boosted)
    }

    @Test
    fun `the boost outranks every lazy tier it must beat`() {
        assertTrue(
            "boost(${TaskScheduler.PRIO_B1_URGENT}) must outrank B1 base(${TaskScheduler.PRIO_B1_CHAPTER})",
            TaskScheduler.PRIO_B1_URGENT < TaskScheduler.PRIO_B1_CHAPTER,
        )
        assertTrue(
            "boost(${TaskScheduler.PRIO_B1_URGENT}) must outrank the scan(${TaskScheduler.PRIO_B2_CHAPTER})",
            TaskScheduler.PRIO_B1_URGENT < TaskScheduler.PRIO_B2_CHAPTER,
        )
        assertTrue(
            "boost(${TaskScheduler.PRIO_B1_URGENT}) must outrank prewarm(${TaskScheduler.PRIO_PREWARM})",
            TaskScheduler.PRIO_B1_URGENT < TaskScheduler.PRIO_PREWARM,
        )
    }

    @Test
    fun `the boost is a peer of the d=1 neighbour work, not a demotion below it`() {
        // 全量表**包含**第 2/3 档那些邻页。把一页的增量排在包含它的一趟之前说不通，所以加急档
        // 必须与 d=1 邻页同级（§3.4「与 d=1 邻页同级」）。
        assertEquals(
            "the boost must sit in the same tier as the d=1 neighbour work",
            TaskScheduler.PRIO_EDGE_FORWARD,
            TaskScheduler.PRIO_B1_URGENT,
        )
        assertNotEquals(
            "and it must be strictly more urgent than this chapter's own lazy pass",
            TaskScheduler.PRIO_B1_URGENT,
            TaskScheduler.PRIO_B1_CHAPTER,
        )
    }

    @Test
    fun `the boost shares a tier with the edge fallback but stays in its own key domain`() {
        // 同档但键必须分域：`edge:<章>` = 邻章加急，`b1:<章>` = 本章加急。共用键会被同键去重顶掉。
        assertEquals("edge and boosted-B1 are peers", TaskScheduler.PRIO_EDGE_FORWARD, TaskScheduler.PRIO_B1_URGENT)
        assertNotEquals(
            "edge key domain",
            controller.edgeKey(3),
            "b1:3",
        )
    }
}
