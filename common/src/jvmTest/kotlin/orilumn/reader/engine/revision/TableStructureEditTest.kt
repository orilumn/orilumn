package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertArrayEquals
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
        replacementStyles: List<InlineMarkup.StyleSpan>? = null,
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
        replacementStyles = replacementStyles,
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
    fun `clearing one selected row deletes only that row`() {
        // 只选中第一行、把这一行清空：未选中的第二行要原样留在表里，整张表仍然自洽。
        val p = ready(plan(listOf(InlineMarkup.Segment("")), "", start = 0, endExclusive = 4))
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(8, r.charEnd)
        assertEquals("efgh", r.text)
        assertEquals(-4, r.delta)
        assertEquals("删掉第一行后只剩第二行", 1, r.editedBlocks.size)
        assertTrue("整表修订首行仍用大写 T", r.editedBlocks.first().startsWith("T"))
        assertEquals("abcdefgh", r.originalText)
        assertEquals("efgh", r.editedText)
        assertEquals("剩下的一行来自旧表第 2 行", listOf(1), r.rowSources)
    }

    @Test
    fun `row alignment keeps the rows after a deleted middle row`() {
        // 整表卡片里删掉中间一行：后面的行不能因为位置前移就被当成「改了字」。
        val oldRows = listOf(cells(2, 2), cells(2, 2), cells(2, 2), cells(2, 2))
        val oldTexts = listOf("甲一乙一", "甲二乙二", "甲三乙三", "甲四乙四")
        val newRows = listOf(oldRows[0], oldRows[2], oldRows[3])
        val newTexts = listOf(oldTexts[0], oldTexts[2], oldTexts[3])
        assertArrayEquals(
            intArrayOf(0, 2, 3),
            alignTableRows(oldTexts, oldRows, newTexts, newRows),
        )
    }

    @Test
    fun `deleting the last selected row keeps the first row`() {
        val p = ready(plan(listOf(InlineMarkup.Segment("")), "", start = 4, endExclusive = 8))
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(8, r.charEnd)
        assertEquals("abcd", r.text)
        assertEquals(-4, r.delta)
        assertEquals("abcd", r.editedText)
        assertEquals("剩下的第一行仍是旧表第一行", listOf(0), r.rowSources)
    }

    @Test
    fun `adding a row after the selected row rewrites the whole table`() {
        val p = ready(
            plan(
                listOf(row("efgh"), row("ijkl")),
                "efghijkl",
                start = 4,
                endExclusive = 8,
            ),
        )
        val r = p.inserted.single()
        assertEquals(0, r.charStart)
        assertEquals(8, r.charEnd)
        assertEquals("abcdefghijkl", r.text)
        assertEquals(4, r.delta)
        assertEquals(3, r.editedBlocks.count { it.startsWith("t") || it.startsWith("T") })
        assertTrue(r.editedBlocks.first().startsWith("T"))
        assertEquals("未选中的首行 + 改过的行 + 新加的行", listOf(0, 1, -1), r.rowSources)
    }

    @Test
    fun `changing one row shape is allowed when the whole table stays consistent`() {
        // 第一行两格合成一格（colspan=2），第二行仍是两格：整张表还是 2 列。
        val wide = InlineMarkup.Segment(
            "abcd",
            table = TableRowInfo(listOf(TableCellInfo(0, 4, header = false, colspan = 2))),
        )
        val p = ready(plan(listOf(wide), "abcd", start = 0, endExclusive = 4))
        val r = p.inserted.single()
        assertEquals("abcdefgh", r.text)
        assertEquals(0, r.delta)
        assertTrue(r.editedBlocks.first().startsWith("T"))
    }

    @Test
    fun `styles on a later selected row are shifted into the whole-table card`() {
        val sig = InlineMarkup.StyleSig("em")
        val p = ready(
            plan(
                pieces = listOf(row("efgh"), row("ijkl")),
                replacement = "efghijkl",
                start = 4,
                endExclusive = 8,
                replacementStyles = listOf(InlineMarkup.StyleSpan(0, 1, sig)),
            ),
        )
        val r = p.inserted.single()
        assertEquals("重放样式落在整表文字里的这一格", 4, r.styles!!.single().start)
        assertEquals("重开卡片的样式按输入区（abcd\\nefgh）算", 5, r.editedStyles!!.single().start)
    }

    @Test
    fun `changing a header row by pipe still restores the header`() {
        // 管道行写不出 `<th>`：只改表头行的字也要按整表重建，把表头补回去。
        val header0 = TableRowInfo(
            listOf(TableCellInfo(0, 2, header = true), TableCellInfo(2, 4, header = true)),
        )
        val p = ready(
            plan(
                pieces = listOf(InlineMarkup.Segment("abXd", table = cells(2, 2))),
                replacement = "abXd",
                start = 0,
                endExclusive = 4,
                tableOfLeaf = { i -> if (i == 0) header0 else row1 },
            ),
        )
        val r = p.inserted.single()
        assertEquals("abXdefgh", r.text)
        assertEquals("Tm;2,1,1,1;2,1,1,1", r.editedBlocks.first())
    }

    @Test
    fun `deleting a row that a rowspan still needs is rejected`() {
        // 第一行的格往下占两行：删掉第二行会让 rowspan 越过末行，整张表不自洽。
        val tall = TableRowInfo(
            listOf(
                TableCellInfo(0, 2, header = false, rowspan = 2),
                TableCellInfo(2, 4, header = false),
            ),
        )
        val second = TableRowInfo(listOf(TableCellInfo(0, 4, header = false)))
        rejected(
            plan(
                pieces = listOf(InlineMarkup.Segment("")),
                replacement = "",
                start = 4,
                endExclusive = 8,
                tableOfLeaf = { i -> when (i) {
                    0 -> tall
                    1 -> second
                    else -> null
                } },
            ),
        )
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
