package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P4-c2 回归：目录/链接锚点必须落在排版字符流（`globalCharStarts` 同空间），
 * 而不是裸文本 walk（空白折叠/`display:none`/img 槽位都会让后者漂移）。
 */
class AnchorCharStartTest {

    private fun prepare(html: String, css: String = ""): LightPrepare {
        val root = HtmlTreeConverter().convert(html)!!
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
        return BoxChapterLayouter().prepareLight(
            root, CssBundle(listOf(css)), profile, 800, ChapterStructureCache(), 1000,
        )
    }

    @Test
    fun `heading anchor lands on its leaf start`() {
        val p = prepare("<html><body><p>前言</p><h2 id=\"s1\">第一节</h2><p>正文</p></body></html>")
        // 块序：p(前言) h2(img 无) p(正文)；h2 是第 2 叶。
        assertEquals(p.globalCharStarts[1].toInt(), p.anchorCharStart("s1"))
    }

    @Test
    fun `image slot counts one in anchor offsets`() {
        val p = prepare(
            "<html><body><p><img src=\"a.png\" width=\"10\" height=\"10\"/>休息<span id=\"m\">X</span></p></body></html>",
        )
        // 排版流：U+FFFC + 休息 = 3；裸 walk 把 img 计 0 → 2。
        assertEquals(p.globalCharStarts[0].toInt() + 3, p.anchorCharStart("m"))
        assertEquals(2, contentFragmentIdCharStart(p.markup, "m"))
    }

    @Test
    fun `display none content excluded from anchor offsets`() {
        val p = prepare(
            "<html><body><p style=\"display:none\">隐藏</p><h2 id=\"s\">标题</h2></body></html>",
        )
        // 隐藏叶零字符（轻路径 textLength 同式），锚点即章首。
        assertEquals(0, p.anchorCharStart("s"))
        // 裸 walk 会数出 2。
        assertEquals(2, contentFragmentIdCharStart(p.markup, "s"))
    }

    @Test
    fun `container anchor resolves to its first leaf`() {
        val p = prepare(
            "<html><body><div id=\"box\"><p>甲</p><p>乙</p></div></body></html>",
        )
        assertEquals(p.globalCharStarts[0].toInt(), p.anchorCharStart("box"))
    }

    @Test
    fun `missing anchor returns null`() {
        val p = prepare("<html><body><p>正文</p></body></html>")
        assertNull(p.anchorCharStart("nope"))
    }
}
