package orilumn.reader.ui.reader

import orilumn.reader.engine.skia.DecorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审阅视图的改动痕迹 → 装饰区间（用户层投影输入）。
 *
 * 用户定口：不画框，删掉的字用红字 + 红色删除线，新添的字用绿字。
 */
class ReviewDecorTest {

    @Test
    fun `deleted text turns red with a red strikethrough`() {
        val ranges = reviewDecorRanges(listOf(ReviewMark(10, 13, deleted = true)))
        assertEquals(2, ranges.size)
        // 改字色 + 划线两条通道，缺一条都读不出「删掉了」。
        assertEquals(
            setOf(DecorKind.TEXT_COLOR, DecorKind.STRIKETHROUGH),
            ranges.map { it.kind }.toSet(),
        )
        // 两半同一个红：字色与删除线不能各是一个调子。
        assertTrue(ranges.all { it.argb == REVIEW_DELETED_ARGB })
        assertTrue(ranges.all { it.start == 10 && it.endExclusive == 13 })
        // 不画框、也不额外加下划线：痕迹只落在字上。
        assertTrue(ranges.none { it.kind == DecorKind.BACKGROUND || it.kind == DecorKind.UNDERLINE })
    }

    @Test
    fun `inserted text turns green with no line and no box`() {
        val range = reviewDecorRanges(listOf(ReviewMark(20, 24, deleted = false))).single()
        assertEquals(DecorKind.TEXT_COLOR, range.kind)
        assertEquals(REVIEW_INSERTED_ARGB, range.argb)
        assertEquals(20, range.start)
        assertEquals(24, range.endExclusive)
    }

    @Test
    fun `review marks outrank reader annotations`() {
        // 标注默认 priority 0；审阅痕迹必须压在读者自己的高亮/划线之上，不被糊住。
        val ranges = reviewDecorRanges(listOf(ReviewMark(0, 2, deleted = true)))
        assertTrue(ranges.all { it.priority > 0 })
    }

    @Test
    fun `empty marks project nothing`() {
        assertTrue(reviewDecorRanges(emptyList()).isEmpty())
        // 空区间（审阅空间里被压成 0 长）不该产出 0 长的装饰 —— DecorRange 也不收。
        assertTrue(reviewDecorRanges(listOf(ReviewMark(5, 5, deleted = true))).isEmpty())
    }
}
