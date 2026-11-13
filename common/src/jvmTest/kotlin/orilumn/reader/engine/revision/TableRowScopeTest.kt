package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 没有行级对照时，「拖到哪几行就改哪几行」的换算（纯逻辑）。
 *
 * 场景是审阅空间还没套上修订（本章结构尚未落盘，`reviewMarks` 一个都没有）：
 * 读者拖的是原书那张表的一行，卡片仍应只列改完那张表里对应的那一行，不能又变成整张表。
 */
class TableRowScopeTest {

    private fun revision(rowSources: List<Int>?): TextRevision = TextRevision(
        id = 7,
        bookId = 1,
        chapter = 2,
        charStart = 100,
        charEnd = 140,
        text = "甲一\n乙一\n丙一\n丁一",
        editedText = "甲一\n乙一\n丙一\n丁一",
        editedBlocks = listOf("T1x2", "t1x2", "t1x2", "t1x2"),
        rowSources = rowSources,
    )

    @Test
    fun `displayed new table keeps the row index`() {
        val scope = tableRowScopeOfDisplayedRows(revision(null), 4, 1..1, displayedIsNewTable = true)
        assertEquals(TableRowScope(7, 1, 1), scope)
    }

    @Test
    fun `contiguous displayed rows stay contiguous`() {
        val scope = tableRowScopeOfDisplayedRows(revision(null), 4, 1..2, displayedIsNewTable = true)
        assertEquals(TableRowScope(7, 1, 2), scope)
    }

    @Test
    fun `whole table still goes to the whole-table card`() {
        assertNull(tableRowScopeOfDisplayedRows(revision(null), 4, 0..3, displayedIsNewTable = true))
    }

    @Test
    fun `old table row folds back through row sources`() {
        // 原书第 0 行被删掉：新行来自旧表的 1、2、3、4 行。
        val r = revision(listOf(1, 2, 3, 4))
        val scope = tableRowScopeOfDisplayedRows(r, 4, 3..3, displayedIsNewTable = false)
        assertEquals(TableRowScope(7, 2, 2), scope)
    }

    @Test
    fun `deleted row cannot be folded to the new table`() {
        val r = revision(listOf(1, 2, 3, 4))
        assertNull(tableRowScopeOfDisplayedRows(r, 4, 0..0, displayedIsNewTable = false))
    }

    @Test
    fun `old table without row sources falls back to the whole-table card`() {
        assertNull(tableRowScopeOfDisplayedRows(revision(null), 4, 1..1, displayedIsNewTable = false))
    }

    @Test
    fun `out of range rows are refused`() {
        assertNull(tableRowScopeOfDisplayedRows(revision(null), 4, 4..4, displayedIsNewTable = true))
    }
}
