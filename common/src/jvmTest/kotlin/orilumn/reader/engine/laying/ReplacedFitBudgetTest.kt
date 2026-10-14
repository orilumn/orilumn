package orilumn.reader.engine.laying

import orilumn.reader.engine.laying.ReplacedFitBudget.budget
import orilumn.reader.engine.laying.ReplacedFitBudget.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 排版层（上）· 分页层替换叶高度预算的纯决策（`docs/分页高度约束与断页规则-方案.md` §5）。
 *
 * 这里只测**决策**（自然高 vs 簇内其它内容高 vs 一页高），不测怎么量——量法在重/轻两路各自
 * 注入，由量画/重轻一致性测试守。
 */
class ReplacedFitBudgetTest {

    @Test
    fun `图装得下就不约束`() {
        // 一页 2560，图 735，无图注 → avail 2560 ≥ 735。
        assertNull(budget(naturalH = 735, othersH = 0, contentH = 2560))
        // 刚好相等也算装得下。
        assertNull(budget(naturalH = 2560, othersH = 0, contentH = 2560))
    }

    @Test
    fun `图超高按剩余空间等比缩`() {
        // 一页 2560，图注 300 ⇒ 预算 2260；图自然 5000 → 缩到 2260。
        assertEquals(2260, budget(naturalH = 5000, othersH = 300, contentH = 2560))
    }

    @Test
    fun `簇内其它内容自己就装不下则不缩`() {
        // 图注 3000 > 一页 2560：缩图也救不回来（让簇去撕裂）。
        assertNull(budget(naturalH = 5000, othersH = 3000, contentH = 2560))
        assertNull(budget(naturalH = 5000, othersH = 2560, contentH = 2560))
    }

    @Test
    fun `预算低于下限则维持原样`() {
        // 下限 = 2560/6 = 426。图注 2200 ⇒ avail 360 < 426 → 不缩（缩成一条线毫无意义）。
        assertEquals(426, floor(2560))
        assertNull(budget(naturalH = 5000, othersH = 2200, contentH = 2560))
        // 恰好等于下限 → 缩。
        assertEquals(426, budget(naturalH = 5000, othersH = 2560 - 426, contentH = 2560))
    }

    @Test
    fun `退化输入一律不约束`() {
        assertNull(budget(naturalH = 0, othersH = 0, contentH = 2560))
        assertNull(budget(naturalH = -3, othersH = 0, contentH = 2560))
        assertNull(budget(naturalH = 5000, othersH = 0, contentH = 0))
        assertNull(budget(naturalH = 5000, othersH = 0, contentH = -1))
    }

    @Test
    fun `负的 othersH 当 0 处理`() {
        assertEquals(2560, budget(naturalH = 5000, othersH = -50, contentH = 2560))
    }
}
