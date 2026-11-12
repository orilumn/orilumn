package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 整表结构修订在审阅视图里的行级对照：删掉的内容必须留在原位、新写的内容标绿。 */
class TableReviewPlanTest {

    private fun row(text: String, vararg cells: IntRange): TableReviewPlan.RowInfo =
        TableReviewPlan.RowInfo(
            text,
            cells.map {
                TableCellInfo(
                    start = it.first,
                    endExclusive = it.last + 1,
                    header = false,
                )
            },
        )

    /** `[start0, end0, start1, end1, …]` → 便于断言的区间表。 */
    private fun pairs(spans: IntArray?): List<IntRange> {
        if (spans == null) return emptyList()
        val out = ArrayList<IntRange>(spans.size / 2)
        var i = 0
        while (i + 1 < spans.size) {
            out += spans[i] until spans[i + 1]
            i += 2
        }
        return out
    }

    @Test
    fun deletedRowStaysInReviewTextAndIsPaintedRed() {
        val plan = TableReview.plan(
            oldRows = listOf(row("ab", 0..1), row("cd", 0..1), row("ef", 0..1)),
            newRows = listOf(row("ab", 0..1), row("ef", 0..1)),
            rowSources = listOf(0, 2),
        )
        assertNotNull(plan)
        plan!!
        assertEquals(
            listOf(
                TableReviewPlan.Kind.KEEP,
                TableReviewPlan.Kind.DELETE,
                TableReviewPlan.Kind.KEEP,
            ),
            plan.rows.map { it.kind },
        )
        // 原文一个字不删：审阅空间仍是「ab cd ef」。
        assertEquals("abcdef", plan.overlay.text)
        assertEquals(listOf(2 until 4), pairs(plan.overlay.deleted))
        assertTrue(pairs(plan.overlay.inserted).isEmpty())
        assertEquals(6, plan.overlay.length)
    }

    @Test
    fun changedCellInsertsNewTextInPlace() {
        val plan = TableReview.plan(
            oldRows = listOf(row("abcd", 0..1, 2..3)),
            newRows = listOf(row("aXcd", 0..1, 2..3)),
            rowSources = listOf(0),
        )
        assertNotNull(plan)
        plan!!
        assertEquals(TableReviewPlan.Kind.CHANGE, plan.rows[0].kind)
        assertEquals(listOf("abX", "cd"), plan.rows[0].cellTexts)
        // 旧字「b」留在原处（红），新字「X」紧跟其后（绿）。
        assertEquals("abXcd", plan.overlay.text)
        assertEquals(listOf(1 until 2), pairs(plan.overlay.deleted))
        assertEquals(listOf(2 until 3), pairs(plan.overlay.inserted))
    }

    @Test
    fun addedRowIsGreen() {
        val plan = TableReview.plan(
            oldRows = listOf(row("ab", 0..1)),
            newRows = listOf(row("ab", 0..1), row("cd", 0..1)),
            rowSources = listOf(0, -1),
        )
        assertNotNull(plan)
        plan!!
        assertEquals(listOf(TableReviewPlan.Kind.KEEP, TableReviewPlan.Kind.ADD), plan.rows.map { it.kind })
        assertEquals("abcd", plan.overlay.text)
        assertEquals(listOf(2 until 4), pairs(plan.overlay.inserted))
        assertTrue(pairs(plan.overlay.deleted).isEmpty())
    }

    @Test
    fun deletedWholeTableKeepsAllOldRows() {
        val plan = TableReview.plan(
            oldRows = listOf(row("ab", 0..1), row("cd", 0..1)),
            newRows = emptyList(),
            rowSources = emptyList(),
        )
        assertNotNull(plan)
        plan!!
        assertEquals(listOf(TableReviewPlan.Kind.DELETE, TableReviewPlan.Kind.DELETE), plan.rows.map { it.kind })
        assertEquals("abcd", plan.overlay.text)
        assertEquals(listOf(0 until 2, 2 until 4), pairs(plan.overlay.deleted))
    }

    @Test
    fun shapeChangeFallsBackToOldRowRedPlusNewRowGreen() {
        val plan = TableReview.plan(
            oldRows = listOf(row("ab", 0..1)),
            newRows = listOf(row("ab", 0..0, 1..1)),
            rowSources = listOf(0),
        )
        assertNotNull(plan)
        plan!!
        assertEquals(listOf(TableReviewPlan.Kind.DELETE, TableReviewPlan.Kind.ADD), plan.rows.map { it.kind })
        assertEquals("abab", plan.overlay.text)
        assertEquals(listOf(0 until 2), pairs(plan.overlay.deleted))
        assertEquals(listOf(2 until 4), pairs(plan.overlay.inserted))
    }

    @Test
    fun mapUsesStructuralPlanForReviewLengthAndPaint() {
        val plan = TableReview.plan(
            oldRows = listOf(row("ab", 0..1), row("cd", 0..1), row("ef", 0..1)),
            newRows = listOf(row("ab", 0..1), row("ef", 0..1)),
            rowSources = listOf(0, 2),
        )
        plan!!
        val r = TextRevision(
            id = 7,
            bookId = 1,
            chapter = 1,
            charStart = 4,
            charEnd = 10,
            text = "abef",
            delta = 4 - 6,
        )
        val map = ChapterRevisionMap(
            listOf(r),
            originalTexts = mapOf(r to "abcdef"),
            structuralIds = setOf(7),
            structuralPlans = mapOf(r to plan.overlay),
        )
        // 删掉的行留在审阅空间里，整条修订仍占原来的 6 个字。
        assertEquals(4 until 10, map.reviewInterval(r))
        // 原书「cd」在审阅空间 [6,8) 标红。
        assertEquals(listOf(6 until 8), pairs(map.paintedDeleted[r]))
        assertTrue(pairs(map.paintedInserted[r]).isEmpty())
        assertEquals(4, map.toOriginalFromReview(4))
        assertEquals(6, map.toOriginalFromReview(6))
        assertEquals(10, map.toOriginalFromReview(10))
    }
}
