package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审阅空间的对齐层：**原文照排 + 改动就地插入**（方案 D-34 修订版）。
 *
 * 这些用例直接盯着 `plan()` 的输出：文字里原文只出现一次、新写的字插在哪、¶ 落在哪、
 * 段界要不要动。上色与坐标换算是它的下游（`ChapterRevisionMapTest` 管那一头）。
 */
class ReviewOverlayTest {

    private val sep = RevisionBlocks.SEPARATOR.toString()
    private val mark = ChapterRevisionMap.BREAK_MARK_CHAR.toString()

    private fun pairs(spans: IntArray): List<IntRange> {
        val out = ArrayList<IntRange>(spans.size / 2)
        var i = 0
        while (i + 1 < spans.size) {
            out += spans[i] until spans[i + 1]
            i += 2
        }
        return out
    }

    @Test
    fun retainedTextIsNotRepeated() {
        // 用户的原例：「略微一攥，」→「¶略微一攥，¶然后」。原文那五个字一个都没删，
        // 只在它前后各插一个段界、后面加「然后」——原句只出现一次。
        val plan = ReviewOverlay.plan("略微一攥，", "${sep}略微一攥，${sep}然后", IntArray(0))!!
        assertEquals("${mark}${sep}略微一攥，${mark}${sep}然后", plan.text)
        assertEquals(9, plan.length)
        assertTrue(plan.deleted.isEmpty())
        assertEquals(listOf(0 until 1, 6 until 9), pairs(plan.inserted))
        assertEquals(mapOf(0 to true, 6 to true), plan.marks)
        assertTrue(plan.needsBlocks)
        // 两处就地插入：段界前的 ¶、段界后的 ¶ 加「然后」。
        assertEquals(listOf(0, 5), plan.insertions.map { it.at })
        assertEquals("$mark", plan.insertions[0].text)
        assertEquals("${mark}然后", plan.insertions[1].text)
    }

    @Test
    fun plainReplacementKeepsOriginalInPlace() {
        // 「乙丙」→「X」：原文两个字留在原位（红字），新写的「X」插在它后面（绿字）。
        val plan = ReviewOverlay.plan("乙丙", "X", IntArray(0))!!
        assertEquals("乙丙X", plan.text)
        assertEquals(3, plan.length)
        assertEquals(listOf(0 until 2), pairs(plan.deleted))
        assertEquals(listOf(2 until 3), pairs(plan.inserted))
        // 区间终点这一格：不越过插入停在 2，越过就落到 3。
        assertEquals(2, plan.beforeOf(2))
        assertEquals(3, plan.afterOf(2))
        assertFalse(plan.needsBlocks)
    }

    @Test
    fun pureInsertionStaysAtThePoint() {
        val plan = ReviewOverlay.plan("", "XY", IntArray(0))!!
        assertEquals("XY", plan.text)
        assertEquals(2, plan.length)
        assertTrue(plan.deleted.isEmpty())
        assertEquals(listOf(0 until 2), pairs(plan.inserted))
        assertEquals(0, plan.beforeOf(0))
        assertEquals(2, plan.afterOf(0))
        assertEquals(0, plan.oldAt(0))
        assertEquals(0, plan.oldAt(2))
        assertFalse(plan.needsBlocks)
    }

    @Test
    fun deletionLeavesRedTextAndNoInsertion() {
        val plan = ReviewOverlay.plan("乙丙", "", IntArray(0))!!
        assertEquals("乙丙", plan.text)
        assertEquals(2, plan.length)
        assertEquals(listOf(0 until 2), pairs(plan.deleted))
        assertTrue(plan.inserted.isEmpty())
        assertFalse(plan.needsBlocks)
    }

    @Test
    fun deletedParagraphBreakMergesWithRedMark() {
        // 两段「甲乙」「丙丁」并成一段：块界去掉，原来的断行处留一个红 ¶。
        val plan = ReviewOverlay.plan("甲乙丙丁", "甲乙丙丁", intArrayOf(2))!!
        assertEquals("甲乙${mark}丙丁", plan.text)
        assertEquals(5, plan.length)
        assertEquals(mapOf(2 to false), plan.marks)
        assertEquals(listOf(2 until 3), pairs(plan.deleted))
        assertTrue(plan.inserted.isEmpty())
        assertTrue(plan.needsBlocks)
        assertEquals(2, plan.beforeOf(2))
        assertEquals(3, plan.afterOf(2))
        assertEquals(2, plan.oldAt(2))
        assertEquals(2, plan.oldAt(3))
        assertEquals(3, plan.oldAt(4))
    }

    @Test
    fun keptParagraphBreakLeavesNoMark() {
        // 三段里只删掉第一处段界，第二处还留着：留着的换行照旧分段，不留痕迹。
        val plan = ReviewOverlay.plan(
            "甲乙丙丁戊己",
            "甲乙丙丁${sep}戊己",
            intArrayOf(2, 4),
        )!!
        assertEquals("甲乙${mark}丙丁${sep}戊己", plan.text)
        assertEquals(7, plan.length)
        assertEquals(mapOf(2 to false), plan.marks)
        assertTrue(plan.needsBlocks)
    }

    @Test
    fun insertedLineBreakLeavesGreenMark() {
        // 零散文字里敲一个真换行（`<br>`）：换行本身没有字形，红绿看不见。
        // 在断行处上一行末尾补一个绿 ¶，与段界那套同一个口径；换行照旧断行。
        val plan = ReviewOverlay.plan("风起云涌", "风起云\n涌", IntArray(0))!!
        assertEquals("风起云${mark}\n涌", plan.text)
        assertEquals(6, plan.length)
        assertEquals(mapOf(3 to true), plan.marks)
        // ¶ 与那个换行拼在同一处插入（都是读者新写的）。
        assertEquals(listOf(3), plan.insertions.map { it.at })
        assertEquals("${mark}\n", plan.insertions.single().text)
        // 上色区间含 ¶ 与换行；「涌」是原文保留，不标色。
        assertEquals(listOf(3 until 5), pairs(plan.inserted))
        assertTrue(plan.deleted.isEmpty())
        assertFalse(plan.needsBlocks)
        // 取文字时 ¶ 会被跳过（见 `reviewMarkOffsets`），正文与卡片里都不会多这个符号。
        assertEquals(3, plan.oldAt(3))
        assertEquals(3, plan.oldAt(5))
    }

    @Test
    fun deletedLineBreakLeavesRedMark() {
        // 原文里本来就有的叶内换行（`<br>`）被删掉：断行本身没有字形，标红也看不见，
        // 所以在断行处补一个红 ¶（与删段界同一个口径）。换行照旧留在原处——审阅空间
        // 「原文一字不删」，删掉的字都原地留着标红，这个换行也一起标红。
        val plan = ReviewOverlay.plan("风起云\n涌", "风起云涌", IntArray(0))!!
        assertEquals("风起云${mark}\n涌", plan.text)
        assertEquals(6, plan.length)
        assertEquals(mapOf(3 to false), plan.marks)
        assertEquals(listOf(3 until 5), pairs(plan.deleted))
        assertTrue(plan.inserted.isEmpty())
        assertFalse(plan.needsBlocks)
    }

    @Test
    fun insertedParagraphBreakSplitsBlock() {
        // 段内新敲一个回车：绿 ¶ 落在断行处上一行末尾，段界本身把块拆开（不占字符）。
        val plan = ReviewOverlay.plan("", sep, IntArray(0))!!
        assertEquals("${mark}${sep}", plan.text)
        assertEquals(1, plan.length)
        assertEquals(mapOf(0 to true), plan.marks)
        assertEquals(listOf(0 until 1), pairs(plan.inserted))
        assertTrue(plan.inserted.isNotEmpty())
        assertTrue(plan.needsBlocks)
    }

    @Test
    fun insertionInTheMiddleKeepsOrdering() {
        // 「甲乙」→「甲X乙」：新写的「X」插在两个字中间，原文位置照旧。
        val plan = ReviewOverlay.plan("甲乙", "甲X乙", IntArray(0))!!
        assertEquals("甲X乙", plan.text)
        assertEquals(0, plan.oldAt(0))
        // 「X」是插在原书 1 这一点上的：落在它上面夹回 1，后面那个「乙」也是 1，章尾才是 2。
        assertEquals(1, plan.oldAt(1))
        assertEquals(1, plan.oldAt(2))
        assertEquals(2, plan.oldAt(3))
        assertEquals(1, plan.insertions.single().at)
        assertEquals("X", plan.insertions.single().text)
    }
}
