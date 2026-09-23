package orilumn.reader.engine.layout

import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.StyleSheet
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版主题样式表加载 (Z2): 传统 (衬线 + 2em 首行缩进) 与现代 (无衬线 + 无缩进) 由 common 单源
 * (`ReaderStylesheets.theme`) 载入并进入级联; 原书设置返回 null (不起用主题层). 平板 assets 与
 * 桌面内联常量为旧副本, 已删除/改读单源.
 */
class ThemeCssTest {

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    private fun profile(theme: String) =
        TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = theme), density = 1f)

    /** Computed style of the first `<p>` under a `<body><p>text</p></body>` tree using the theme layer. */
    private fun bodyParagraphStyle(theme: String): ComputedStyle {
        val p = node("p", children = listOf(MarkupElement("#text", text = "正文")))
        val root = node("body", children = listOf(p))
        val sheet = CssLayouter(profile(theme)).themeSheetFromProfile(profile(theme))
        val engine = StyleComputer(
            profile(theme).bodyPx, StyleSheet(emptyList()), emptyList(), sheet,
        )
        return engine.compute(root)[p]!!
    }

    @Test
    fun `original has no theme sheet`() {
        assertNull(CssLayouter(profile("original")).themeSheetFromProfile(profile("original")))
    }

    @Test
    fun `traditional theme from common single source is serif with 2em indent and no paragraph gap`() {
        val style = bodyParagraphStyle("traditional")
        assertEquals("serif", style.fontFamily)
        assertEquals(2f, style.textIndentPx / style.fontSizePx, 1e-3f)
        // 无段间距: 主题表 p/li margin 清零 (UI 层同值, 见 TypographicProfileThemeTest).
        assertEquals(0f, style.margin.top, 1e-3f)
        assertEquals(0f, style.margin.bottom, 1e-3f)
    }

    @Test
    fun `modern theme from common single source is sans-serif with no indent`() {
        val style = bodyParagraphStyle("modern")
        assertEquals("sans-serif", style.fontFamily)
        assertEquals(0f, style.textIndentPx, 1e-3f)
    }

    @Test
    fun `theme texts are nonblank and distinct`() {
        val traditional = ReaderStylesheets.theme("traditional")!!
        val modern = ReaderStylesheets.theme("modern")!!
        assertTrue(traditional.isNotBlank())
        assertTrue(modern.isNotBlank())
        assertNotEquals(traditional, modern)
    }
}