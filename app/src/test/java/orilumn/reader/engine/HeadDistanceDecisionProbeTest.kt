package orilumn.reader.engine

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probe (原则 §3.4, 整改 D2b + D7): the "target page distance from chapter head" decision table.
 *
 * This table decides whether the chapter's own full layout is boosted to 第2档. Getting it wrong
 * is expensive in both directions: a false negative loses the handoff guarantee (§3.4), a false
 * positive pulls a whole-chapter shape into the slot the flip needs.
 *
 * The two rows that bit us on-device (see [previousTempPage]):
 *  - the predecessor of `Fwd(0)` lives at the **end of the backward list**, not at
 *    `forwardPages[-1]`; missing that made `prev` null at exactly the chapter-head moment, so
 *    `BOOST` never fired — `d` came out null instead of 1.
 *  - the boost must be re-evaluated on **every pointer move**, not only when a new backward page
 *    happens to be shaped (by then the head page is already in the window and nothing fires).
 */
class HeadDistanceDecisionProbeTest {

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

    // 本文件的声明都在 BookDocumentController 类体内（Kotlin 允许成员不缩进），
    // 故与 controller.remainingScanOrder(1) 等既有 probe 同例走接收者调用。
    private fun d(cur: Int, prev: Int?) = controller.headDistanceFrom(cur, prev)

    @Test
    fun `current page at the chapter head is distance zero`() {
        assertEquals("block 0 IS the chapter head", 0, d(0, null))
        assertEquals(
            "and it stays 0 even with a predecessor value present",
            0,
            d(0, 0),
        )
    }

    @Test
    fun `target on page 2 is distance one and therefore boosts`() {
        assertEquals(
            "predecessor starting at block 0 means the current page is the chapter's 2nd page",
            1,
            d(11, 0),
        )
    }

    @Test
    fun `deeper targets are distance two and do not boost`() {
        for (cur in 2..200) {
            assertEquals("cur=$cur with a non-head predecessor is deeper than d=1", 2, d(cur, 3))
        }
    }

    @Test
    fun `unknown predecessor never guesses the boost`() {
        assertEquals(
            "no predecessor in the window means undecidable, not d=1",
            null,
            d(11, null),
        )
    }

    @Test
    fun `only distance one boosts`() {
        // 扫全谱：d 只可能是 0/1/2/null，且升档只认 d==1。造法：当前页在 block c、前驱页在 block p。
        val seen = mutableSetOf<Int>()
        var boosted = 0
        for (c in 0..64) for (pp in intArrayOf(0, 1, 7, 63)) {
            val dd = d(c, pp)
            if (dd == null) seen.add(-1) else { seen.add(dd); if (dd == 1) boosted++ }
        }
        assertEquals("给了前驱页时 d 只可能是 0/1/2（null 由另一用例覆盖）", setOf(0, 1, 2), seen)
        assertTrue("d==1 必须可达，否则升档是死代码", boosted > 0)
        assertEquals("d==1 恰好发生在前驱页是章首页时（c=1..64 共 64 次）", 64, boosted)
    }

    @Test
    fun `a boosted target really outranks the lazy tiers`() {
        assertTrue(
            "boost(${TaskScheduler.PRIO_B1_URGENT}) must outrank base(${TaskScheduler.PRIO_B1_CHAPTER})",
            TaskScheduler.PRIO_B1_URGENT < TaskScheduler.PRIO_B1_CHAPTER,
        )
        assertEquals(
            "and the boost is the same slot as the d=1 neighbour work",
            TaskScheduler.PRIO_PAGE_NEXT,
            TaskScheduler.PRIO_B1_URGENT,
        )
    }
}
