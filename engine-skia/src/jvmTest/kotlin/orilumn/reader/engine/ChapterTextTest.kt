package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** D-18：整章纯文本投影必须与排版叶文本同源、同序，且没有 prepare 时不伪造空章。 */
class ChapterTextTest {

    @Test
    fun `chapter text concatenates leaf text in document order`() {
        val markup = HtmlTreeConverter()
            .convert("<html><body><p>Hello <b>world</b></p><p>second</p></body></html>")!!
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT, density = 1f)
        val layouter = BoxChapterLayouter()
        val prepare = layouter.prepare(
            markup = markup,
            cssBundle = null,
            profile = profile,
            contentW = 600,
            contentH = 800,
        )

        val unit = ChapterUnit(chapterIndex = 0)
        unit.bindPrepare(prepare, hash = 0L)

        // 叶与叶之间不插分隔符：标注/分页的字符流就是各叶归一文拼接，
        // 跨段复制由用户层按块边界补换行，不能在这里偷偷加字符（否则偏移全错）。
        assertEquals("Hello worldsecond", unit.chapterText())
        assertEquals(prepare.totalChars, unit.chapterText()!!.length)
    }

    @Test
    fun `chapter text is null before prepare`() {
        val unit = ChapterUnit(chapterIndex = 0)
        assertNull(unit.chapterText())
    }
}
