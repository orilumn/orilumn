package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字符级对齐单测：结果必须能重建原文/新文，且同一输入永远同一结果。 */
class CharDiffTest {

    private fun applyOps(old: String, ops: List<CharDiff.Op>): Pair<String, String> {
        val rebuiltOld = StringBuilder()
        val rebuiltNew = StringBuilder()
        for (op in ops) {
            when (op) {
                is CharDiff.Op.Keep -> {
                    assertEquals(rebuiltOld.length, op.oldStart)
                    val t = old.substring(op.oldStart, op.oldStart + op.length)
                    rebuiltOld.append(t)
                    rebuiltNew.append(t)
                }
                is CharDiff.Op.Delete -> {
                    assertEquals(rebuiltOld.length, op.oldStart)
                    rebuiltOld.append(old, op.oldStart, op.oldStart + op.length)
                }
                is CharDiff.Op.Insert -> rebuiltNew.append(op.text)
            }
        }
        return rebuiltOld.toString() to rebuiltNew.toString()
    }

    private fun assertAligned(old: String, new: String): List<CharDiff.Op> {
        val ops = CharDiff.diff(old, new)
        val (a, b) = applyOps(old, ops)
        assertEquals("对齐后必须重建原文：old=$old new=$new ops=$ops", old, a)
        assertEquals("对齐后必须重建新文：old=$old new=$new ops=$ops", new, b)
        return ops
    }

    @Test
    fun identicalIsOneKeep() {
        val ops = assertAligned("abc", "abc")
        assertEquals(listOf(CharDiff.Op.Keep(0, 3)), ops)
    }

    @Test
    fun replaceOneCharKeepsCommonParts() {
        val ops = assertAligned("他慢慢地走", "他快快地走")
        // 公共前缀「他」、公共后缀「地走」，中间「慢慢」→「快快」。
        assertEquals(CharDiff.Op.Keep(0, 1), ops.first())
        assertEquals(CharDiff.Op.Keep(3, 2), ops.last())
    }

    @Test
    fun pureInsertAndDelete() {
        assertTrue(assertAligned("", "abc").single() is CharDiff.Op.Insert)
        assertTrue(assertAligned("abc", "").single() is CharDiff.Op.Delete)
        val ops = assertAligned("ac", "abc")
        assertTrue(ops.any { it is CharDiff.Op.Insert && it.text == "b" })
        assertAligned("abc", "ac")
    }

    @Test
    fun repeatedCharactersAreDeterministic() {
        // 重复字有多个等价对齐解：必须每次取同一个（重放两次不能给出不同样式归属）。
        val first = assertAligned("哈哈哈哈", "哈哈")
        repeat(20) { assertEquals(first, assertAligned("哈哈哈哈", "哈哈")) }
        assertTrue(first.any { it is CharDiff.Op.Keep })
    }

    @Test
    fun wholeRewriteHasNoKeep() {
        val ops = assertAligned("甲乙丙", "XYZ")
        assertTrue(ops.none { it is CharDiff.Op.Keep })
        assertEquals("XYZ", ops.filterIsInstance<CharDiff.Op.Insert>().joinToString("") { it.text })
    }

    @Test
    fun keepsAreOrderedAndNonOverlapping() {
        val ops = assertAligned("0123456789", "01ab456789")
        val keeps = ops.filterIsInstance<CharDiff.Op.Keep>()
        var prevEnd = 0
        for (k in keeps) {
            assertTrue("keep 必须按原文顺序且不重叠：$ops", k.oldStart >= prevEnd)
            prevEnd = k.oldStart + k.length
        }
    }
}
