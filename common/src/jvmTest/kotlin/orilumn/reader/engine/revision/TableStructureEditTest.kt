package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正文修改卡：**整张表**一次改（删行 / 加行 / 删整表 / 每行增删格）。
 *
 * 章文本摆成两张表行 + 一个尾段：行 0 `abcd`、行 1 `efgh`、尾段 `尾段`，
 * 叶尾 `[4, 8, 10]`，整张表区间 = `[0, 8)`。
 */
class TableStructureEditTest {

    private val chapter = "abcdefgh尾段"

    private fun cells(vararg lengths: Int): TableRowInfo {
        var at = 0
        return TableRowInfo(
            lengths.map { len ->
                TableCellInfo(at, at + len, header = false).also { at += len }
            },
        )
    }

    private val row0 = cells(2, 2)
    private val row1 = cells(2, 2)

    private fun row(text: String): InlineMarkup.Segment =
        InlineMarkup.Segment(text, table = cells((text.length / 2).coerceAtLeast(1), (text.length / 2).coerceAtLeast(1)))

    private fun plan(
        pieces: List<InlineMarkup.Segment>,
        replacement: String,
        revisions: List<TextRevision> = emptyList(),
        start: Int = 0,
        endExclusive: Int = 8,
        tableOfLeaf: (Int) -> TableRowInfo? = { i ->
            when (i) {
                0 -> row0
                1 -> row1
                else -> null
            }
        },
    ): TextEditPlan = TextEditPlanner.plan(
        bookId = 1,
        chapter = 1,
        chapterText = chapter,
        blockEnds = intArrayOf(4, 8, 10),
        start = start,
        endExclusive = endExclusive,
        replacement = replacement,
        blocks = pieces,
        map = ChapterRevisionMap(revisions),
        groupId = 99,
        now = 1234,
        tableOfLeaf = tableOfLeaf,
        tableRangeOf = { i -> if (i == 0 || i == 1) 0..7 else null },
    )

    private fun ready(p: TextEditPlan): TextEditPlan.Ready {
        assertTrue("expected Ready but was $p", p is TextEditPlan.Ready)
        return p as TextEditPlan.Ready
    }

    private fun rejected(p: TextEditPlan): TextEditPlan.Rejected {
        assertTrue("expected Rejected but was $p", p is TextEditPlan.Rejected)
        return p as TextEditPlan.Rejected
    }

    @Test
    fun `deleting a row rewrites the whole table`() {
        val p = ready(plan(listOf(row("abcd")), "abcd"))
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(8, r.charEnd)
        assertEquals("整表修订：文字是剩下那一行", "abcd", r.text)
        assertEquals("行数少了一行，整段替换", -4, r.delta)
        assertTrue("首行短码用大写 T 标记整表修订", r.editedBlocks.first().startsWith("T"))
        assertEquals(1, r.editedBlocks.size)
        assertEquals("", r.blockKinds.firstOrNull().orEmpty())
        assertEquals("abcdefgh", r.originalText)
        assertEquals("abcd", r.editedText)
    }

    @Test
    fun `adding a row rewrites the whole table`() {
        val p = ready(plan(listOf(row("abcd"), row("efgh"), row("ijkl")), "abcdefghijkl"))
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(8, r.charEnd)
        assertEquals("abcdefghijkl", r.text)
        assertEquals(4, r.delta)
        assertEquals(3, r.editedBlocks.count { it.startsWith("t") || it.startsWith("T") })
        assertTrue(r.editedBlocks.first().startsWith("T"))
        assertTrue("其余行仍用小写 t", r.editedBlocks[1].startsWith("t"))
    }

    @Test
    fun `deleting the whole table removes it`() {
        val p = ready(plan(listOf(InlineMarkup.Segment("")), ""))
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(8, r.charEnd)
        assertEquals("", r.text)
        assertEquals(-8, r.delta)
        assertEquals(listOf("T"), r.editedBlocks)
    }

    @Test
    fun `a grid that cannot be tiled is rejected`() {
        val broken = InlineMarkup.Segment("ab", table = cells(2))
        rejected(plan(listOf(row("abcd"), broken), "abcdab"))
    }

    @Test
    fun `adding a column in every row stays consistent`() {
        val p = ready(plan(listOf(row("abcdef"), row("ghijkl")), "abcdefghijkl"))
        val r = p.inserted.single()
        assertEquals("abcdefghijkl", r.text)
        assertEquals(4, r.delta)
    }

    @Test
    fun `pipe card restores header cells from the original table`() {
        // 卡片里的管道行写不出 `<th>`：回验与提交都按原位从原表补回表头。
        val header0 = TableRowInfo(
            listOf(TableCellInfo(0, 2, header = true), TableCellInfo(2, 4, header = true)),
        )
        val p = ready(
            plan(
                pieces = listOf(row("abcd"), row("efGh")),
                replacement = "abcdefGh",
                tableOfLeaf = { i -> if (i == 0) header0 else row1 },
            ),
        )
        val r = p.inserted.single()
        assertEquals("Tm;2,1,1,1;2,1,1,1", r.editedBlocks[0])
        assertEquals("tm;2,0,1,1;2,0,1,1", r.editedBlocks[1])
    }

    @Test
    fun `confirming an unchanged pipe card after header restore is a no-op`() {
        // 表头补回后与原文逐项一致：原样确认不该白落一条修订。
        val header0 = TableRowInfo(
            listOf(TableCellInfo(0, 2, header = true), TableCellInfo(2, 4, header = true)),
        )
        val p = ready(
            plan(
                pieces = listOf(row("abcd"), row("efgh")),
                replacement = "abcdefgh",
                tableOfLeaf = { i -> if (i == 0) header0 else row1 },
            ),
        )
        assertTrue("原样确认应是无操作，实际 ${p.inserted.size} 条修订", p.inserted.isEmpty())
    }

    @Test
    fun `the previous whole-table revision is absorbed`() {
        val old = TextRevision(
            id = 7,
            bookId = 1,
            chapter = 1,
            charStart = 0,
            charEnd = 8,
            text = "abcd",
            editedBlocks = listOf("T" + row0.wire().substring(1)),
            delta = -4,
        )
        val p = ready(plan(listOf(row("abcd")), "abcd", revisions = listOf(old)))
        assertEquals(listOf(7L), p.deletedIds)
    }

    @Test
    fun `editing one row of a two-row table is not a whole-table edit`() {
        // 只选中第一行：仍按逐行校验（整行改字可以，改格数不行）。
        val p = ready(plan(listOf(InlineMarkup.Segment("abXd", table = cells(2, 2))), "abXd", start = 0, endExclusive = 4))
        val r = p.inserted.single()
        assertEquals(2, r.charStart)
        assertEquals(3, r.charEnd)
        assertEquals("X", r.text)
        assertEquals("单行修订带自己的格短码", 1, r.editedBlocks.size)
        assertTrue(r.editedBlocks.first().startsWith("t"))
    }

    @Test
    fun `changing one row shape outside a whole-table edit is rejected`() {
        val p = plan(
            listOf(InlineMarkup.Segment("abX", table = cells(3))),
            "abX",
            start = 0,
            endExclusive = 4,
        )
        rejected(p)
    }
}
