package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 坐标换算单测：原书 ↔ 渲染，纯算术，不依赖排版。 */
class ChapterRevisionMapTest {

    private fun rev(start: Int, end: Int, text: String, id: Long = 0, group: Long = 0): TextRevision =
        TextRevision(
            id = id,
            bookId = 1,
            chapter = 1,
            charStart = start,
            charEnd = end,
            text = text,
            delta = text.length - (end - start),
            groupId = group,
        )

    @Test
    fun noRevisionsIsIdentity() {
        val map = ChapterRevisionMap.EMPTY
        assertEquals(0L, map.contentRev)
        assertEquals(5, map.toRendered(5))
        assertEquals(5, map.toOriginal(5))
        assertEquals(2 until 7, map.toRenderedRange(2, 7))
    }

    @Test
    fun replacementShiftsFollowingOffsets() {
        // 原书「甲乙丙丁」，把 [1,3)「乙丙」换成「X」（delta = -1）。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        assertEquals(0, map.toRendered(0))
        assertEquals(1, map.toRendered(1))
        assertEquals(2, map.toRendered(3))
        assertEquals(3, map.toRendered(4))
        assertEquals(3, map.renderedLength(4))

        assertEquals(0, map.toOriginal(0))
        assertEquals(1, map.toOriginal(1))
        assertEquals(3, map.toOriginal(2))
        assertEquals(4, map.toOriginal(3))
    }

    @Test
    fun insertionBoundarySemantics() {
        // 在位置 2 插入「XY」（delta = 2）。
        val map = ChapterRevisionMap(listOf(rev(2, 2, "XY")))

        // 单点默认越过插入。
        assertEquals(4, map.toRendered(2))

        // 区间起点越过插入、终点不越过：标注 [0,2) 仍是 [0,2)，新字不进旧标注。
        assertEquals(0 until 2, map.toRenderedRange(0, 2))
        // 区间起点在插入处：越过 → 从插入之后开始。
        assertEquals(4 until 5, map.toRenderedRange(2, 3))
        // 区间终点在插入处：不越过 → 停在插入之前。
        assertEquals(0 until 2, map.toRenderedRange(0, 2))

        // 反算：渲染 0..2 仍是原书 0..2，渲染 2..4 是插入本身。
        assertEquals(0 until 2, map.toOriginalRange(0, 2))
        assertEquals(2 until 2, map.toOriginalRange(2, 4))
    }

    @Test
    fun deletionCollapsesRange() {
        // 删掉原书 [1,3)。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "")))
        assertEquals(1, map.toRendered(1))
        assertEquals(1, map.toRendered(3))
        assertEquals(2, map.renderedLength(4))
        // 原书 [1,3) 映射成空区间（标注被完全删掉时装饰消失、记录保留）。
        assertEquals(1 until 1, map.toRenderedRange(1, 3))
        assertEquals(1 until 1, map.toOriginalRange(1, 1))
    }

    @Test
    fun multipleRevisionsAccumulateShift() {
        val map = ChapterRevisionMap(
            listOf(
                rev(1, 2, "aa"), // +1
                rev(5, 6, ""), // -1
            ),
        )
        assertEquals(0, map.toRendered(0))
        assertEquals(1, map.toRendered(1))
        assertEquals(3, map.toRendered(2))
        assertEquals(6, map.toRendered(5))
        assertEquals(6, map.toRendered(6))
        assertEquals(7, map.toRendered(7))

        assertEquals(0, map.toOriginal(0))
        assertEquals(1, map.toOriginal(1))
        assertEquals(2, map.toOriginal(3))
        assertEquals(5, map.toOriginal(6))
        assertEquals(7, map.toOriginal(7))
    }

    @Test
    fun touchingRevisionsIncludeBoundaryInsertions() {
        val map = ChapterRevisionMap(
            listOf(
                rev(0, 2, "x", id = 1),
                rev(2, 2, "插入", id = 2),
                rev(4, 6, "y", id = 3),
            ),
        )
        assertEquals(listOf(2L), map.revisionsTouching(2, 2).map { it.id })
        assertEquals(listOf(1L), map.revisionsTouching(0, 2).map { it.id })
        assertEquals(listOf(3L), map.revisionsTouching(3, 5).map { it.id })
    }

    @Test
    fun reviewSpaceKeepsOriginalAndPutsNewTextAfterIt() {
        // 原书「甲乙丙丁戊」，把 [1,3)「乙丙」换成「XY」：审阅空间 = 甲乙丙XY丁戊。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        // 原文按原位排，之后的字整体后移。
        assertEquals(0, map.toReview(0))
        assertEquals(1, map.toReview(1))
        assertEquals(3, map.toReview(3, crossInsertions = false))
        // 单点默认越过新文字（新文字排在边界之前）。
        assertEquals(5, map.toReview(3))
        assertEquals(6, map.toReview(4))
        assertEquals(10, map.toReview(8, crossInsertions = false))

        // 删除痕迹 = 原文区间，不含新文字；新增痕迹 = 新文字本身。
        val r = map.revisions.single()
        assertEquals(1 until 3, map.reviewDeletedRange(r))
        assertEquals(3 until 5, map.reviewInsertedRange(r))

        // 审阅 → 原书：原文照回原位，落进新文字的夹回区间末尾。
        assertEquals(0, map.toOriginalFromReview(0))
        assertEquals(1, map.toOriginalFromReview(1))
        assertEquals(3, map.toOriginalFromReview(4))
        assertEquals(3, map.toOriginalFromReview(3))
        assertEquals(3, map.toOriginalFromReview(5))
        assertEquals(4, map.toOriginalFromReview(6))
    }

    @Test
    fun reviewSpacePureInsertionSitsBeforeOriginalStart() {
        // 位置 2 插入「XY」：审阅空间 = 甲乙XY丙…（新文字在原位置之前，与原书渲染一致）。
        val map = ChapterRevisionMap(listOf(rev(2, 2, "XY")))
        val r = map.revisions.single()
        assertEquals(2 until 2, map.reviewDeletedRange(r))
        assertEquals(2 until 4, map.reviewInsertedRange(r))
        assertEquals(2, map.toReview(2, crossInsertions = false))
        assertEquals(4, map.toReview(2))
        assertEquals(5, map.toReview(3))
    }

    @Test
    fun reviewRangeCoversOriginalPlusInnerInsertions() {
        val map = ChapterRevisionMap(listOf(rev(5, 6, "ZZZ"))) // 原文 [5,6) 之后插 3 字
        // 区间起点越过该处插入、终点不越过：整段原文 + 内嵌新文字。
        val s = map.toReview(2, crossInsertions = true)
        val e = map.toReview(8, crossInsertions = false)
        assertEquals(2 until 11, s until e)
        // 正好等于该修订的区间：只框住原文，不吞新文字。
        assertEquals(5 until 6, map.toReview(5) until map.toReview(6, crossInsertions = false))
    }

    @Test
    fun reviewIntervalCoversDeletedAndInsertedHalves() {
        // 原书「甲乙丙丁戊」→ 审阅「甲乙丙XY丁戊」：一条修订占 [1,5)。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        val r = map.revisions.single()
        assertEquals(1 until 5, map.reviewInterval(r))
        // 纯插入：原区间为空，整段就是新文字。
        val ins = ChapterRevisionMap(listOf(rev(2, 2, "XY"))).revisions.single()
        assertEquals(2 until 4, ChapterRevisionMap(listOf(ins)).reviewInterval(ins))
    }

    @Test
    fun reviewSelectionMapsBackToOriginalRange() {
        // 审阅「甲乙丙XY丁戊」：选原文「乙丙」→ 原书 [1,3)。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 3))
        // 选新文字「XY」（审阅 [3,5)）→ 抬起那条修订的原区间 [1,3)，否则等于原地加字。
        assertEquals(1 until 3, map.originalEditRangeFromReview(3, 5))
        // 跨过删掉的原文与新添的文字整段 → 同样是 [1,3)。
        assertEquals(1 until 3, map.originalEditRangeFromReview(1, 5))
        // 没碰到修订：原样换算。
        assertEquals(0 until 1, map.originalEditRangeFromReview(0, 1))
    }

    @Test
    fun reviewSelectionOfPureInsertionKeepsPoint() {
        // 审阅「甲乙XY丙」里选新文字「XY」（审阅 [2,4)）→ 原书区间是空的（纯插入）。
        val map = ChapterRevisionMap(listOf(rev(2, 2, "XY")))
        assertTrue(map.originalEditRangeFromReview(2, 4).isEmpty())
        assertEquals(1, map.revisionsTouchingReview(2, 4).size)
    }

    @Test
    fun originalTextFromReviewStripsInsertedText() {
        // 审阅「甲乙丙XY丁戊」剪掉新文字 ⇒ 原书「甲乙丙丁戊」。
        val map = ChapterRevisionMap(listOf(rev(1, 3, "XY")))
        assertEquals("甲乙丙丁戊", map.originalTextFromReview("甲乙丙XY丁戊"))
        // 纯插入同样剪掉。
        val ins = ChapterRevisionMap(listOf(rev(2, 2, "XY")))
        assertEquals("甲乙丙丁", ins.originalTextFromReview("甲乙XY丙丁"))
        // 无修订原样返回。
        assertEquals("甲乙", ChapterRevisionMap.EMPTY.originalTextFromReview("甲乙"))
    }

    @Test
    fun contentRevIsStableAndSensitive() {
        val a = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        val b = ChapterRevisionMap(listOf(rev(1, 3, "X")))
        assertEquals(a.contentRev, b.contentRev)

        assertNotEquals(a.contentRev, ChapterRevisionMap(listOf(rev(1, 3, "Y"))).contentRev)
        assertNotEquals(a.contentRev, ChapterRevisionMap(listOf(rev(2, 4, "X"))).contentRev)
        assertNotEquals(a.contentRev, ChapterRevisionMap(listOf(rev(1, 3, "X", group = 9))).contentRev)
        // 顺序不影响（内部按起点排序）。
        val x = rev(1, 2, "a", id = 1)
        val y = rev(5, 6, "b", id = 2)
        assertEquals(
            ChapterRevisionMap(listOf(x, y)).contentRev,
            ChapterRevisionMap(listOf(y, x)).contentRev,
        )
        // 无修订固定为 0（老缓存按 0 处理）。
        assertEquals(0L, ChapterRevisionMap(emptyList()).contentRev)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlappingRevisionsRejected() {
        ChapterRevisionMap(listOf(rev(1, 5, "a"), rev(4, 8, "b")))
    }

    @Test
    fun touchingInsertionAtSamePointIsAllowed() {
        // 同点纯插入本应被提交侧合并；表侧容忍（按 id 排先后），不当作重叠。
        val map = ChapterRevisionMap(
            listOf(rev(3, 3, "甲", id = 1), rev(3, 3, "乙", id = 2)),
        )
        assertTrue(map.contentRev != 0L)
        assertEquals(3, map.toRendered(3, crossInsertions = false))
        assertEquals(5, map.toRendered(3, crossInsertions = true))
    }
}
