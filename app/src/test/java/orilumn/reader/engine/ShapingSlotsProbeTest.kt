package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Probe (原则 §5, 整改 D5): the background shaping slot budget.
 *
 * `max(1, min(2, cpuCount - 2))` —— 留两份核给 UI / 目标页 / 系统，上限 2 是 P0 真机实测的拐点。
 *
 * 之所以把公式抽成纯函数来测：`-1` 与 `-2` 的差别**只在 3 核一类设备上出现**（核数更多时
 * 都被上限 2 夹住），而开发机通常核数很多 —— 内联在构造器里的话，这个偏差永远测不出来，
 * 只能靠人读代码发现。这里把每一档核数的结果都钉住。
 */
class ShapingSlotsProbeTest {

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

    // 本文件的顶层声明都在 BookDocumentController 类体内（Kotlin 允许成员不缩进），
    // 故与 controller.remainingScanOrder(1) 等既有 probe 同例，走接收者调用。
    private fun slots(cpu: Int) = controller.shapingSlotsFor(cpu)

    @Test
    fun `degenerate core counts still get one slot`() {
        assertEquals("0 cores (unmeasurable, must not divide by zero)", 1, slots(0))
        assertEquals("1 core", 1, slots(1))
        assertEquals("2 cores", 1, slots(2))
    }

    @Test
    fun `three cores is where minus-one and minus-two diverge`() {
        // 3 - 2 = 1 → 1 slot（原则：只留得起一份核）
        // 3 - 1 = 2 → 2 slots（旧代码的手误写法）—— 小核设备本该最保守，却给了更多后台并行
        assertEquals("3 cores must get exactly one slot", 1, slots(3))
    }

    @Test
    fun `four cores onward are capped at the measured knee of two`() {
        assertEquals(2, slots(4))
        assertEquals(2, slots(6))
        assertEquals(2, slots(8))
        assertEquals(2, slots(12))
        assertEquals(2, slots(64))
    }

    @Test
    fun `the cap never raises a small-device budget above the principle allows`() {
        // 扫全谱：任何核数下都不得出现「减 1」写法会给出的更多槽位
        for (cpu in 0..256) {
            val slots = slots(cpu)
            assertTrue("cpu=$cpu gave $slots slots", slots in 1..2)
            assertEquals("cpu=$cpu must leave two cores for UI/flip/system", slots, maxOf(1, minOf(2, cpu - 2)))
        }
    }

    @Test
    fun `the budget is monotone non-decreasing in core count`() {
        var prev = 0
        for (cpu in 1..32) {
            val s = slots(cpu)
            assertTrue("cpu=$cpu: $s dropped below previous $prev", s >= prev)
            prev = s
        }
    }

    @Test
    fun `a real machine reports a sane budget`() {
        val cpu = Runtime.getRuntime().availableProcessors()
        val slots = slots(cpu)
        assertTrue("availableProcessors=$cpu gave $slots slots", slots in 1..2)
        println("PROBE slots: cpu=$cpu slots=$slots")
    }
}
