package orilumn.reader.engine.revision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表格行列结构校验：读者可以删行 / 加行 / 删整表 / 每行增删格 / 改合并方式，
 * 只要整张表还能铺成一个矩形网格就放行（[TableGrid]）。
 */
class TableGridTest {

    /** 一格文字长度 1；参数是 `rowspan to colspan`。 */
    private fun row(vararg spans: Pair<Int, Int>): TableRowInfo {
        var at = 0
        return TableRowInfo(
            spans.map { (rs, cs) ->
                TableCellInfo(
                    start = at,
                    endExclusive = at + 1,
                    header = false,
                    rowspan = rs,
                    colspan = cs,
                ).also { at += 1 }
            },
        )
    }

    @Test
    fun `simple rectangle is consistent`() {
        val rows = listOf(row(1 to 1, 1 to 1), row(1 to 1, 1 to 1))
        assertEquals(2, TableGrid.columns(rows))
        assertTrue(TableGrid.consistent(rows))
    }

    @Test
    fun `deleting a row keeps the table consistent`() {
        val rows = listOf(row(1 to 1, 1 to 1), row(1 to 1, 1 to 1), row(1 to 1, 1 to 1))
        assertTrue(TableGrid.consistent(rows.drop(1)))
        assertTrue(TableGrid.consistent(rows.take(2)))
    }

    @Test
    fun `adding a row with the same width is consistent`() {
        val rows = listOf(row(1 to 1, 1 to 1), row(1 to 1, 1 to 1), row(1 to 1, 1 to 1))
        assertEquals(2, TableGrid.columns(rows))
    }

    @Test
    fun `a row with one fewer cell is inconsistent`() {
        val rows = listOf(row(1 to 1, 1 to 1), row(1 to 1))
        assertFalse(TableGrid.consistent(rows))
        assertEquals(-1, TableGrid.columns(rows))
    }

    @Test
    fun `removing one cell while widening the neighbour stays consistent`() {
        val rows = listOf(row(1 to 1, 1 to 1), row(1 to 2))
        assertTrue(TableGrid.consistent(rows))
    }

    @Test
    fun `a row with an extra cell is inconsistent`() {
        val rows = listOf(row(1 to 1), row(1 to 1, 1 to 1))
        assertFalse(TableGrid.consistent(rows))
    }

    @Test
    fun `colspan must not leave a hole`() {
        // 首行宽 3；第二行两格 colspan=1 只铺 2 列，还空一列。
        val rows = listOf(row(1 to 1, 1 to 1, 1 to 1), row(1 to 1, 1 to 1))
        assertFalse(TableGrid.consistent(rows))
    }

    @Test
    fun `rowspan spanning two rows is consistent`() {
        val rows = listOf(
            row(2 to 1, 1 to 1),
            row(1 to 1),
            row(1 to 1, 1 to 1),
        )
        assertTrue(TableGrid.consistent(rows))
    }

    @Test
    fun `rowspan reaching past the last row is inconsistent`() {
        // 首行左格要占三行，但整张表只剩两行。
        val rows = listOf(row(3 to 1, 1 to 1), row(1 to 1))
        assertFalse(TableGrid.consistent(rows))
    }

    @Test
    fun `rowspan overlap is inconsistent`() {
        // 第一行左格占两行；第二行还想在左格位置放一格（排布会把它挤到右列，第二格越界）。
        val rows = listOf(row(2 to 1), row(1 to 1, 1 to 1))
        assertFalse(TableGrid.consistent(rows))
    }

    @Test
    fun `an empty table is a legal deletion`() {
        assertTrue(TableGrid.consistent(emptyList()))
        assertEquals(0, TableGrid.columns(emptyList()))
    }

    @Test
    fun `whole table wire round trips and is marked with a capital T`() {
        val rowInfo = row(1 to 1, 1 to 1)
        val wire = rowInfo.wire(wholeTable = true)
        assertTrue(wire.startsWith("T"))
        val back = TableRowInfo.fromWire(wire)
        assertNotNull(back)
        assertTrue(rowInfo.sameStructure(back))
        assertNull(TableRowInfo.fromWire("T"))
    }
}
