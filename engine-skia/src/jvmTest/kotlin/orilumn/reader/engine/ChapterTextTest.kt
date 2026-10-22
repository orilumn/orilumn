package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.skia.DrawLineBuilder
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D-18：整章纯文本投影必须与排版叶文本同源、同序，且没有 prepare 时不伪造空章。 */
class ChapterTextTest {

    /** 章内既有独图叶（占 1 格）又有表格行叶（占格内文本）——两类漏算都会让其后全文漂移。 */
    private val mixedHtml = """
        <html><body>
        <p>开头段落。</p>
        <img src="a.png" width="10" height="10"/>
        <p>图后文字<img src="b.png" width="4" height="4"/>同行继续。</p>
        <table><tr><td>甲</td><td>乙</td></tr><tr><td><p>丙</p></td><td>丁</td></tr></table>
        <p>表后段落。</p>
        </body></html>
    """.trimIndent()

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT, density = 1f)

    private fun unitFor(html: String): Pair<ChapterUnit, ChapterPrepareResult> {
        val layouter = BoxChapterLayouter()
        val prepare = layouter.prepare(
            markup = HtmlTreeConverter().convert(html)!!,
            cssBundle = null,
            profile = profile(),
            contentW = 600,
            contentH = 800,
        )
        val unit = ChapterUnit(chapterIndex = 0)
        unit.bindPrepare(prepare, hash = 0L)
        return unit to prepare
    }

    /** 绘制行（`DrawLine.charBase`）必须能在整章文本里逐字对上——长按/点按按这个偏移取字。 */
    private fun assertDrawnLinesAligned(unit: ChapterUnit, prepare: ChapterPrepareResult) {
        val text = unit.chapterText()!!
        val lines = DrawLineBuilder.build(
            result = prepare.structure,
            styleMap = prepare.styleMap,
            classify = prepare.classify,
            hidden = prepare.hidden,
            letterSpacingEm = profile().letterSpacingEm,
            genOf = prepare.genOf,
            firstLetterOf = prepare.firstLetterOf,
        )
        assertTrue("本锁前提：章内必须画出文本行", lines.isNotEmpty())
        for ((lineIdx, line) in lines) {
            val to = line.charBase + line.text.length
            assertTrue(
                "行 $lineIdx 越过章文本末尾：base=${line.charBase} len=${line.text.length} textLen=${text.length}",
                line.charBase >= 0 && to <= text.length,
            )
            assertEquals(
                "行 $lineIdx 的 charBase 与章文本投影错位（长按会选到别的段落）",
                line.text,
                text.substring(line.charBase, to),
            )
        }
    }

    @Test
    fun `chapter text concatenates leaf text in document order`() {
        val (unit, prepare) = unitFor("<html><body><p>Hello <b>world</b></p><p>second</p></body></html>")

        // 叶与叶之间不插分隔符：标注/分页的字符流就是各叶归一文拼接，
        // 跨段复制由用户层按块边界补换行，不能在这里偷偷加字符（否则偏移全错）。
        assertEquals("Hello worldsecond", unit.chapterText())
        assertEquals(prepare.totalChars, unit.chapterText()!!.length)
        // 块尾：两个段落分别 11 / 17，选区按块边界截断。
        assertEquals(listOf(11, 17), unit.chapterTextBlockEnds()!!.toList())
    }

    @Test
    fun `chapter text is null before prepare`() {
        val unit = ChapterUnit(chapterIndex = 0)
        assertNull(unit.chapterText())
    }

    @Test
    fun `image and table leaves keep drawn lines aligned`() {
        val (unit, prepare) = unitFor(mixedHtml)
        val text = unit.chapterText()!!

        // 1) 整章长度与字符流同尺：漏算替换叶/表格行就会小于 totalChars。
        assertEquals("整章投影长度必须等于字符流总长", prepare.totalChars, text.length)
        // 2) 块尾与叶集同长、单调、末项收尾。
        val ends = unit.chapterTextBlockEnds()!!
        assertEquals(prepare.totalBlocks, ends.size)
        assertEquals(text.length, ends.last())
        assertTrue("块尾必须单调不减", (1 until ends.size).all { ends[it - 1] <= ends[it] })
        // 3) 独图叶恰好占一个 U+FFFC 槽。
        assertTrue("章内必须有替换叶槽位", text.contains('\uFFFC'))
        // 4) 表格行叶的投影就是格内文本（旧 leafText 在这里是空串）。
        val rowIdx = prepare.leaves.indexOfFirst { it.table != null }
        assertTrue("本锁前提：章内必须有表格行叶", rowIdx >= 0)
        val rowText = text.substring(if (rowIdx == 0) 0 else ends[rowIdx - 1], ends[rowIdx])
        assertEquals("表格行叶必须占「格内各块归一文本」", "甲乙", rowText)
        // 5) 行流偏移逐行对得上（真机上长按落点换偏移走的就是这一步）。
        assertDrawnLinesAligned(unit, prepare)
    }

    @Test
    fun `light prepare projects the same chapter text as heavy`() {
        val markup = HtmlTreeConverter().convert(mixedHtml)!!
        val layouter = BoxChapterLayouter()
        val heavy = layouter.prepare(
            markup = markup,
            cssBundle = null,
            profile = profile(),
            contentW = 600,
            contentH = 800,
        )
        val light = layouter.prepareLight(
            markup = markup,
            cssBundle = null,
            profile = profile(),
            contentW = 600,
            structure = ChapterStructureCache(),
            contentH = 800,
        )

        val heavyText = heavy.chapterTextProjection().text
        val lightProjection = light.chapterTextProjection()
        assertEquals("重路径投影必须与字符流总长同尺", heavy.totalChars, heavyText.length)
        assertEquals("轻路径投影必须与字符流总长同尺", light.totalChars, lightProjection.text.length)
        assertEquals("大章临时表在途时（轻路径）选区文本必须与全排（重路径）逐字相同", heavyText, lightProjection.text)
    }
}
