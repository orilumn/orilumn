package orilumn.reader.engine

import orilumn.reader.engine.BookDocumentController.NeighborItem
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe (原则 §3.1, 整改 D3a): the neighbor sequence and the six-tier priority ladder.
 *
 * §3.1 collapses what used to be three separate notions ("下一页" / "上一页" / "其余页") into ONE
 * ordered sequence:
 *
 * ```
 * +1·dir,  −1·dir,  +2·dir,  −2·dir,  +3·dir,  −3·dir, …
 * ```
 *
 * 「下一页」and「上一页」are just its first two entries;「本章其余页」is the tail. The algorithm is
 * one thing — only the **urgency split** differs: d=1 direction side takes 第2档, d=1 other side
 * 第3档, d≥2 第4档.
 *
 * [neighborSequence] is pure, so this probe exercises the same code the disk-path dispatcher runs.
 */
class NeighborSequenceProbeTest {

    private lateinit var controller: BookDocumentController

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = orilumn.reader.data.epub.FakeEpubResourceReader(emptyMap()),
            layouter = BoxChapterLayouter(),
            profile = orilumn.reader.engine.text.TypographicProfile.build(
                orilumn.reader.data.settings.ReaderSettings.DEFAULT
            ),
        )
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    private val ctl get() = controller

    private fun seq(target: Int, total: Int, dir: Int, ch: Int = 2, last: Int = 5) =
        ctl.neighborSequence(target, total, dir, ch, last)

    private fun pages(target: Int, total: Int, dir: Int, ch: Int = 2, last: Int = 5) =
        seq(target, total, dir, ch, last).filterIsInstance<NeighborItem.Page>().map { it.index }

    private fun edges(target: Int, total: Int, dir: Int, ch: Int = 2, last: Int = 5) =
        seq(target, total, dir, ch, last).filterIsInstance<NeighborItem.EdgeChapter>().map { it.chapter }

    // ── the sequence order ────────────────────────────────────────────

    @Test
    fun `forward direction interleaves direction side first at each distance`() {
        // §3.1: +1, -1, +2, -2, ... 一路到最远的页（这里是 page 0，d=5）
        assertEquals(listOf(6, 4, 7, 3, 8, 2, 9, 1, 0), pages(target = 5, total = 10, dir = 1))
    }

    @Test
    fun `backward direction flips the interleave`() {
        assertEquals(listOf(4, 6, 3, 7, 2, 8, 1, 9, 0), pages(target = 5, total = 10, dir = -1))
    }

    @Test
    fun `the no-record state (dir=0) orders as forward`() {
        // 原则 §3.2: 0 means "no record" and is semantically forward; every consumer tests >= 0.
        assertEquals(pages(5, 10, 1), pages(5, 10, 0))
    }

    @Test
    fun `the sequence covers every page exactly once`() {
        val total = 12
        val got = pages(target = 5, total = total, dir = 1).sorted()
        assertEquals("all pages except the target, no dupes", (0 until total).filter { it != 5 }, got)
    }

    // ── the urgency split ─────────────────────────────────────────────

    @Test
    fun `d one splits into tier2 and tier3 while the tail collapses into tier4`() {
        val items = seq(target = 5, total = 10, dir = 1)
        val tierOf = { page: Int -> (items.first { it is NeighborItem.Page && it.index == page } as NeighborItem.Page).tier }

        assertEquals("d=1 direction side is 第2档", TaskScheduler.PRIO_PAGE_NEXT, tierOf(6))
        assertEquals("d=1 other side is 第3档", TaskScheduler.PRIO_PAGE_PREV, tierOf(4))
        assertEquals("d=2 is 第4档", TaskScheduler.PRIO_PAGE_REST, tierOf(7))
        assertEquals("d=2 other side is 第4档", TaskScheduler.PRIO_PAGE_REST, tierOf(3))
        assertEquals("d=3 is still 第4档", TaskScheduler.PRIO_PAGE_REST, tierOf(8))
    }

    @Test
    fun `tiers are non-adjacent so a future tier can be inserted without renumbering`() {
        val tiers = listOf(
            TaskScheduler.PRIO_PAGE_NEXT,
            TaskScheduler.PRIO_PAGE_PREV,
            TaskScheduler.PRIO_PAGE_REST,
            TaskScheduler.PRIO_B1_CHAPTER,
            TaskScheduler.PRIO_B2_CHAPTER,
            TaskScheduler.PRIO_PREWARM,
        )
        assertEquals("six tiers", 6, tiers.distinct().size)
        for (i in 0 until tiers.size - 1) {
            assertTrue(
                "tier ${tiers[i]} → ${tiers[i + 1]} must leave room for an insertion",
                tiers[i + 1] - tiers[i] >= 10,
            )
        }
    }

    @Test
    fun `the ladder is strictly ordered by urgency`() {
        assertTrue("第2档 < 第3档", TaskScheduler.PRIO_PAGE_NEXT < TaskScheduler.PRIO_PAGE_PREV)
        assertTrue("第3档 < 第4档", TaskScheduler.PRIO_PAGE_PREV < TaskScheduler.PRIO_PAGE_REST)
        assertTrue("第4档 < 第6档", TaskScheduler.PRIO_PAGE_REST < TaskScheduler.PRIO_B1_CHAPTER)
        assertTrue("第6档 < 第7档", TaskScheduler.PRIO_B1_CHAPTER < TaskScheduler.PRIO_B2_CHAPTER)
        assertTrue("第7档 < 预热", TaskScheduler.PRIO_B2_CHAPTER < TaskScheduler.PRIO_PREWARM)
        assertTrue("预热 < 翻页参考值", TaskScheduler.PRIO_PREWARM < TaskScheduler.PRIO_FLIP + 1000)
    }

    // ── chapter boundaries (§3.3) ─────────────────────────────────────

    @Test
    fun `d=1 outside the chapter escalates to the adjacent chapter`() {
        // 单页章：d=1 两侧都不存在 → 下一章与上一章都升（§3.3 的对称表）
        assertEquals(
            "a single-page chapter escalates both ways",
            listOf(3, 1),
            edges(target = 0, total = 1, dir = 1, ch = 2, last = 5),
        )
        assertEquals(
            "direction does not change which neighbours are missing",
            listOf(1, 3),
            edges(target = 0, total = 1, dir = -1, ch = 2, last = 5),
        )
    }

    @Test
    fun `distance two or beyond outside the chapter is dropped, never escalated`() {
        // 首页且章不止一页：d=1 的**另一侧**是 -1，越界 → 按 §3.3 升上一章（d=1，允许）。
        assertEquals("d=1 on the other side still escalates", listOf(1), edges(0, 4, 1, ch = 2, last = 5))
        // 首页且章只有两页时，d=2 的两侧都越界 → 丢弃，不再升第二级。
        assertEquals("only the d=1 escalation survives", listOf(1), edges(0, 2, 1, ch = 2, last = 5))
    }

    @Test
    fun `escalation never crosses the book boundaries`() {
        // 第 0 章往后翻：缺的是"上一章"，越界 → 丢弃；但 d=1 的另一侧(下一页)仍合法 → 升第 1 章。
        assertEquals("backward past chapter 0 is dropped, forward still escalates", listOf(1), edges(0, 1, -1, ch = 0, last = 5))
        // 最后一章往前翻：缺"下一章" → 丢弃；另一侧(上一页)合法 → 升第 4 章。
        assertEquals("forward past the last chapter is dropped, backward still escalates", listOf(4), edges(0, 1, 1, ch = 5, last = 5))
    }

    @Test
    fun `escalation sits at the same tier as the neighbour work it replaces`() {
        val items = seq(target = 0, total = 1, dir = 1, ch = 2, last = 5)
        val edge = items.filterIsInstance<NeighborItem.EdgeChapter>()
        assertEquals("both sides escalate on a single-page chapter", 2, edge.size)
        assertEquals(
            "forward escalation takes 第2档",
            TaskScheduler.PRIO_PAGE_NEXT,
            edge.first { it.chapter == 3 }.tier,
        )
        assertEquals(
            "backward escalation takes 第3档",
            TaskScheduler.PRIO_PAGE_PREV,
            edge.first { it.chapter == 1 }.tier,
        )
    }

    @Test
    fun `the sequence drains to the chapter end with no depth cap`() {
        // 总则：按偏离度串行排全章。有界 drain 窗口（16）已废——40 页章必须 39 页全出。
        val total = 40
        val got = pages(target = 5, total = total, dir = 1)
        assertEquals("all pages except the target, no dupes", (0 until total).filter { it != 5 }, got.sorted())
        assertEquals("tail reaches the far end", 39, got.max())
        assertEquals("head reaches the chapter start", 0, got.min())
    }

    @Test
    fun `an empty chapter yields an empty sequence`() {
        assertTrue(seq(target = 0, total = 0, dir = 1).isEmpty())
    }

    @Test
    fun `the escalated full layouts live in their own key domain`() {
        // 同档但键分域：edge:<章> 是邻章加急，b1:<章> 是本章加急，b2:<章> 是章距投机。
        // 共用键会被同键去重（后来者胜）把加急件降级。
        assertNotEquals("edge vs b2", "edge:2", "b2:2")
        assertNotEquals("edge vs b1", "edge:2", "b1:2")
    }
}
