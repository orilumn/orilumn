package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.BlockKind
import orilumn.reader.engine.revision.TableCellInfo
import orilumn.reader.engine.revision.TableRowInfo
import orilumn.reader.engine.revision.TextEditBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 卡片里装的字必须与卡片区间逐字对齐（真机复报：改一段从段中开始选的字，确定后正文多出一个字）。
 *
 * 段首 / 段尾在选区之外时，卡片只切选中的那一段；表格行例外——整行进卡，区间跟着扩到整行。
 */
class ClipBlockForCardTest {

    private fun paragraph(start: Int, endExclusive: Int) = TextEditBlock(
        kind = BlockKind.Paragraph,
        startsAtBlockStart = true,
        start = start,
        endExclusive = endExclusive,
    )

    private fun row(start: Int, endExclusive: Int) = TextEditBlock(
        kind = null,
        startsAtBlockStart = true,
        table = TableRowInfo(listOf(TableCellInfo(0, endExclusive - start, header = false))),
        start = start,
        endExclusive = endExclusive,
    )

    @Test
    fun paragraphStartingBeforeSelectionIsClippedToSelection() {
        // 选中的是 [105, 130)，段落本身从 100 开始：卡片只切 105..130。
        assertEquals(105 until 130, clipBlockForCard(paragraph(100, 200), 105, 130))
    }

    @Test
    fun paragraphEndingAfterSelectionIsClippedToSelection() {
        // 选区在段中间收尾：不把段尾那几个字带进卡片。
        assertEquals(100 until 120, clipBlockForCard(paragraph(100, 200), 100, 120))
    }

    @Test
    fun blockFullyOutsideSelectionIsDropped() {
        assertNull(clipBlockForCard(paragraph(300, 400), 100, 200))
        assertNull(clipBlockForCard(paragraph(100, 200), 300, 400))
    }

    @Test
    fun tableRowKeepsWholeRow() {
        // 表格行整行进卡（格偏移按整行文字算）：选区只碰到半行，也切整行。
        assertEquals(80 until 140, clipBlockForCard(row(80, 140), 100, 200))
    }

    @Test
    fun tableRowOutsideSelectionIsDropped() {
        assertNull(clipBlockForCard(row(300, 340), 100, 200))
    }
}
