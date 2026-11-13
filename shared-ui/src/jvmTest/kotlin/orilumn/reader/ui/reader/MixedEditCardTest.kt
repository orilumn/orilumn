package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.InlineMarkup
import orilumn.reader.engine.revision.TableCellInfo
import orilumn.reader.engine.revision.TableRowInfo
import orilumn.reader.engine.revision.TableRowScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「表格 + 表格外的文字」一起选时卡片的切段（用户定口：卡片要带出拖到的那些字，不能只剩整张表）。
 *
 * 卡片里连续的那一段表格行就是表格段，前面 / 后面都是文字段：三段各自有自己的正文区间，
 * 表格那段带行范围（提交时拼回整张表），文字那段按普通区间落笔。
 */
class MixedEditCardTest {

    /** 一行一格（格界只影响卡片显示，这里只借用「这是表格行」）。 */
    private fun row(text: String) = InlineMarkup.Segment(
        text,
        null,
        TableRowInfo(listOf(TableCellInfo(0, text.length, header = false))),
    )

    /** 卡片：`前一段文字` / 表格两行 / `后一段文字`，正文区间 `[100, 300)`，表格占 `[140, 220)`。 */
    private fun threeParts(
        segments: List<InlineMarkup.Segment>,
        body: String,
        rowScope: TableRowScope? = TableRowScope(5L, 1, 2),
    ) = MixedEditCard.parts(
        segments = segments,
        styles = null,
        body = body,
        cardStart = 100,
        cardEnd = 300,
        tableRange = 140 until 220,
        rowScope = rowScope,
    )

    @Test
    fun `表前表后都是文字时切成三段`() {
        val text = InlineMarkup.Segment("前一段")
        val r1 = row("甲")
        val r2 = row("乙")
        val tail = InlineMarkup.Segment("后一段")
        val body = "前一段\n甲\n乙\n后一段"
        val parts = threeParts(listOf(text, r1, r2, tail), body)

        assertEquals(3, parts!!.size)
        assertEquals(100, parts[0].start)
        assertEquals(140, parts[0].endExclusive)
        assertEquals("前一段", parts[0].text)
        assertEquals(140, parts[1].start)
        assertEquals(220, parts[1].endExclusive)
        assertEquals("甲\n乙", parts[1].text)
        assertEquals(2, parts[1].blocks!!.size)
        assertEquals(220, parts[2].start)
        assertEquals(300, parts[2].endExclusive)
        assertEquals("后一段", parts[2].text)
    }

    @Test
    fun `只拖到表格和后面一段文字时没有前段`() {
        val r1 = row("甲")
        val tail = InlineMarkup.Segment("后一段")
        val parts = MixedEditCard.parts(
            segments = listOf(r1, tail),
            styles = null,
            body = "甲\n后一段",
            cardStart = 140,
            cardEnd = 300,
            tableRange = 140 until 220,
            rowScope = TableRowScope(5L, 1, 1),
        )!!
        assertEquals(2, parts.size)
        assertEquals("甲", parts[0].text)
        assertEquals("后一段", parts[1].text)
    }

    @Test
    fun `读者把表格外的文字删光了 - 文字段是空串`() {
        val r1 = row("甲")
        val parts = threeParts(listOf(r1), "甲")!!
        assertEquals(3, parts.size)
        assertEquals("", parts[0].text)
        assertEquals("甲", parts[1].text)
        assertEquals("", parts[2].text)
    }

    @Test
    fun `拖到整张表时表格段不带行范围`() {
        val r1 = row("甲")
        val parts = threeParts(listOf(r1), "甲", rowScope = null)!!
        assertNull(parts[1].rowScope)
    }

    @Test
    fun `表格行被文字隔成两段时不切（宁可不改）`() {
        val parts = threeParts(
            listOf(row("甲"), InlineMarkup.Segment("中间"), row("乙")),
            "甲\n中间\n乙",
        )
        assertNull(parts)
    }

    @Test
    fun `卡片里一行表格都不剩时不切（删整张表请只选表格）`() {
        val parts = threeParts(listOf(InlineMarkup.Segment("只剩文字")), "只剩文字")
        assertNull(parts)
    }

    @Test
    fun `行内结构跟着各段平移`() {
        val r1 = row("甲")
        val tail = InlineMarkup.Segment("后一段")
        val body = "甲\n后一段"
        val parts = MixedEditCard.parts(
            segments = listOf(r1, tail),
            styles = listOf(InlineMarkup.StyleSpan(2, 4, InlineMarkup.StyleSig("b"))),
            body = body,
            cardStart = 140,
            cardEnd = 300,
            tableRange = 140 until 220,
            rowScope = TableRowScope(5L, 1, 1),
        )!!
        // 后一段在卡片里从第 2 个字开始，原区间 [2,4) 平移后是 [0,2)。
        assertTrue(parts[1].styles!!.single().start == 0)
        assertEquals(2, parts[1].styles!!.single().endExclusive)
    }
}
