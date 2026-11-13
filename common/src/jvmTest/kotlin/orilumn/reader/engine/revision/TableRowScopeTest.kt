package orilumn.reader.engine.revision

import orilumn.reader.data.revision.TextRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 表格行卡片的行号换算（纯逻辑）。
 *
 * 两条口径：
 * 1. 拖到的是改完那张表里还有的行 ⇒ 卡片只列这几行；
 * 2. 拖到的是读者删掉的行（审阅视图里的红字）⇒ 卡片**空白**（上次编辑的结果就是这行不存在），
 *    位置留给读者补行。
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
        // 原书第 1 行被删掉：改完的表三行分别来自原书的 0、2、3 行。
        val r = revision(listOf(0, 2, 3))
        val scope = tableRowScopeOfDisplayedRows(r, 3, 2..2, displayedIsNewTable = false)
        assertEquals(TableRowScope(7, 1, 1), scope)
    }

    @Test
    fun `deleted row gives an empty card at its place`() {
        // 原书第 1 行被删掉：点它 → 空白卡片，补行位置在改完那张表的第一行之后。
        val r = revision(listOf(0, 2, 3))
        val scope = tableRowScopeOfDisplayedRows(r, 3, 1..1, displayedIsNewTable = false)
        assertEquals(TableRowScope(7, 1, 0), scope)
        assertEquals(true, scope?.isInsertion)
    }

    @Test
    fun `deleted first row inserts at the top`() {
        val r = revision(listOf(1, 2, 3))
        val scope = tableRowScopeOfDisplayedRows(r, 3, 0..0, displayedIsNewTable = false)
        assertEquals(TableRowScope(7, 0, -1), scope)
    }

    @Test
    fun `deleted and kept rows together fall back to the whole-table card`() {
        val r = revision(listOf(0, 2, 3))
        assertNull(tableRowScopeOfDisplayedRows(r, 3, 0..1, displayedIsNewTable = false))
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
