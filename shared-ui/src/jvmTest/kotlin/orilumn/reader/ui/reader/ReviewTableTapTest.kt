package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.TableRowScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点按「压着整表修订的表格」的去处（用户定口：点修改区域就开修订卡、带出上次改的内容）。
 *
 * 真机复报：点表格里改动过的地方没反应。这里把 `tryOpenReviewEditAt` 用的那条判据直接打一遍，
 * 三件事必须成立：
 * 1. 普通阅读视图（没有红绿字可认）——点表上改动过的字 ⇒ 开整表卡；
 * 2. 审阅视图——点到改过的那一行（含同一行里没改的格）⇒ 开行卡，卡片只列这一行；
 * 3. 审阅视图——点一行都没改过的表格区域 ⇒ 吞掉点按，不进修订卡。
 */
class ReviewTableTapTest {

    private val row = TableRowScope(7L, 1, 1)

    /** 表里两行：`[100,140)` 改过（痕迹落在 131），`[140,180)` 没改过；`[180,200)` 拿不到行块。 */
    private fun tap(
        offset: Int,
        reviewSpace: Boolean,
        scope: TableRowScope? = row,
    ): ReviewTableTap = reviewTableTapOf(
        reviewSpace = reviewSpace,
        rowOf = {
            when {
                offset < 140 -> 100 until 140
                offset < 180 -> 140 until 180
                else -> null
            }
        },
        marksOf = { listOf(ReviewMark(131, 133, deleted = false)) },
        scopeOf = { scope },
    )

    @Test
    fun `normal view opens the whole table card`() {
        // 普通视图里正文就是改后的表：点表上任何改动过的字都算「点这处」。
        assertEquals(ReviewTableTap.WholeTable, tap(offset = 120, reviewSpace = false))
        // 不因「拿不到行对照」而变成没反应。
        assertEquals(ReviewTableTap.WholeTable, tap(offset = 120, reviewSpace = false, scope = null))
    }

    @Test
    fun `review view on a changed row opens the row card`() {
        val tap = tap(offset = 132, reviewSpace = true)
        assertTrue("改过的行应开行卡：$tap", tap is ReviewTableTap.Row)
        assertEquals(row, (tap as ReviewTableTap.Row).scope)
    }

    @Test
    fun `review view on another cell of the same row still opens the row card`() {
        // 改一格常常只改一两个字：痕迹在这行里，点这行别的字也该开修订卡。
        assertEquals(ReviewTableTap.Row(row), tap(offset = 110, reviewSpace = true))
    }

    @Test
    fun `review view on an unchanged row does nothing`() {
        assertEquals(ReviewTableTap.Ignore, tap(offset = 160, reviewSpace = true))
    }

    @Test
    fun `row scope missing falls back to the whole table card`() {
        // 审阅视图里拿不到行对照（删掉的行 / 老记录）：照旧能改，开整表卡。
        assertEquals(ReviewTableTap.WholeTable, tap(offset = 132, reviewSpace = true, scope = null))
    }

    @Test
    fun `tap where the row block is unknown still opens something`() {
        // 行间空白 / 表外：拿不到行块时按行对照兜底，行对照也没有才按整表卡——
        // 「点了没反应」才是 bug。
        assertEquals(ReviewTableTap.Row(row), tap(offset = 190, reviewSpace = true))
        assertEquals(ReviewTableTap.WholeTable, tap(offset = 190, reviewSpace = true, scope = null))
    }

    @Test
    fun `unchanged row lets the middle zone through to the bars`() {
        // 屏幕中部点按 = 呼出 / 收起上下栏，表格里没改过的行不该抢这个手势。
        val tap = tap(offset = 160, reviewSpace = true)
        assertEquals(ReviewTableTap.Ignore, tap)
        assertFalse(reviewTableTapConsumed(tap, middleZone = true))
        // 两侧照旧吞掉：点表格里没改过的行不该翻页。
        assertTrue(reviewTableTapConsumed(tap, middleZone = false))
    }

    @Test
    fun `changed rows consume the tap in every zone`() {
        assertTrue(reviewTableTapConsumed(tap(offset = 132, reviewSpace = true), middleZone = true))
        assertTrue(reviewTableTapConsumed(tap(offset = 120, reviewSpace = false), middleZone = true))
    }
}
