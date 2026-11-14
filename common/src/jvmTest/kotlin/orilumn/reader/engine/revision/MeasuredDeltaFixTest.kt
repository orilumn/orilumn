package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 重放实测长度修正库里的 delta（方案 §8.10 最大风险）。 */
class MeasuredDeltaFixTest {

    private fun rev(id: Long, start: Int, end: Int, text: String): TextRevision =
        TextRevision(
            id = id,
            bookId = 1,
            chapter = 1,
            charStart = start,
            charEnd = end,
            text = text,
            delta = text.length - (end - start),
        )

    @Test
    fun noDriftKeepsOriginalTable() {
        val a = rev(1, 1, 3, "X")
        val b = rev(2, 10, 12, "YY")
        val fixed = MeasuredDeltaFix.apply(
            revisions = listOf(a, b),
            revisionsByLeaf = mapOf(0 to listOf(a), 1 to listOf(b)),
            measuredLeafDeltas = mapOf(0 to a.delta, 1 to b.delta),
        )
        assertNull(fixed)
    }

    @Test
    fun driftAdjustsTheRevisionOfThatLeaf() {
        val a = rev(1, 1, 3, "X") // delta = -1
        val b = rev(2, 10, 11, "YZ") // delta = +1
        var drift: Triple<Int, Int, Long>? = null
        val fixed = MeasuredDeltaFix.apply(
            revisions = listOf(a, b),
            revisionsByLeaf = mapOf(0 to listOf(a), 1 to listOf(b)),
            // 第一叶实测多出一个字（节点边界空白没被吃掉）。
            measuredLeafDeltas = mapOf(0 to 0, 1 to b.delta),
        ) { leaf, stored, measured, id ->
            drift = Triple(leaf, measured - stored, id)
        }!!
        assertEquals(2, fixed.size)
        assertEquals(0, fixed[0].delta) // -1 + 1
        assertEquals(1, fixed[1].delta)
        assertEquals(Triple(0, 1, 1L), drift)
        // 只改内存副本，原对象不动。
        assertEquals(-1, a.delta)
    }

    @Test
    fun driftOfMultiRevisionLeafLandsOnLast() {
        val a = rev(1, 1, 2, "AB") // +1
        val b = rev(2, 5, 6, "") // -1
        // 库里合计 0，实测该叶长度不变之外的边界差异 -1：末条再 -1。
        val fixed = MeasuredDeltaFix.apply(
            revisions = listOf(a, b),
            revisionsByLeaf = mapOf(0 to listOf(a, b)),
            measuredLeafDeltas = mapOf(0 to -1),
        )!!
        assertEquals(1, fixed[0].delta)
        assertEquals(-2, fixed[1].delta)
    }

    @Test
    fun leafWithoutMeasurementIsLeftAlone() {
        // 匿名 run 叶被改到时不重放（needsRecompute），没有实测值，不能瞎修。
        val a = rev(1, 1, 3, "X")
        val fixed = MeasuredDeltaFix.apply(
            revisions = listOf(a),
            revisionsByLeaf = mapOf(0 to listOf(a)),
            measuredLeafDeltas = emptyMap(),
        )
        assertNull(fixed)
        assertSame(a, listOf(a)[0])
    }
}
