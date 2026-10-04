package orilumn.reader.engine.layout

import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.StyleSheet
import orilumn.reader.engine.css.TextAlign
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
    private fun bodyParagraphStyle(theme: String): ComputedStyle = bodyStyleOf(theme, "p")

    /** Computed style of `<tag>` under `<body>` using the theme layer alone (no author/UA sheet). */
    private fun bodyStyleOf(theme: String, tag: String, attrs: Map<String, String> = emptyMap()): ComputedStyle {
        val el = node(tag, attrs, listOf(MarkupElement("#text", text = "正文")))
        val root = node("body", children = listOf(el))
        val prof = profile(theme)
        val engine = StyleComputer(
            prof.bodyPx, StyleSheet(emptyList()), emptyList(),
            CssLayouter(prof).themeSheetFromProfile(prof),
        )
        return engine.compute(root)[el]!!
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

    /**
     * 现代/传统两个主题给正文容器兜底两端对齐。
     *
     * 选择器是量出来的：真书离屏全管线 71 本书 3344 行按标签归属 = `p` 66% / **`div` 21%** /
     * `li` 5% / `blockquote` 1%。`div` 必带 —— 大量中文 epub 段落直接放 div 不套 p。
     * `p` 是主选择器（类型选择器不看祖先，已覆盖 `li p`/`blockquote p`/`td p`）。
     */
    @Test
    fun `both themes justify body text containers`() {
        for (theme in listOf("traditional", "modern")) {
            for (tag in listOf("p", "div", "li", "blockquote", "dd", "td")) {
                assertEquals(
                    "$theme/$tag should be justified",
                    TextAlign.JUSTIFY, bodyStyleOf(theme, tag).textAlign,
                )
            }
        }
    }

    /** 标题与等宽代码刻意不兜底两端对齐：短标题拉平反而难看，代码不该被拉伸间隙。 */
    @Test
    fun `themes leave headings and pre untouched`() {
        for (theme in listOf("traditional", "modern")) {
            for (tag in listOf("h1", "h2", "h3", "pre", "code")) {
                assertEquals(
                    "$theme/$tag should stay default", TextAlign.LEFT, bodyStyleOf(theme, tag).textAlign,
                )
            }
        }
    }

    /**
     * 原书设置 = 书作者说了算：主题表整张不加载，所以正文对齐完全不参与层叠，
     * `<p>` 落回 UA 默认 left。书上写了 `text-align` 才由书的说了算。
     */
    @Test
    fun `original mode leaves body alignment to the book`() {
        assertNull(CssLayouter(profile("original")).themeSheetFromProfile(profile("original")))
        val engine = StyleComputer(18f, StyleSheet(emptyList()), emptyList(), null)
        val p = node("p", children = listOf(MarkupElement("#text", text = "正文")))
        val style = engine.compute(node("body", children = listOf(p)))[p]!!
        assertEquals("原书设置不该有兜底对齐", TextAlign.LEFT, style.textAlign)
    }

    /**
     * 层叠代价的诚实边界：theme=42 高于作者层全部声明（`Winner.beats` 先比 tier 再比特异度），
     * 所以本条会盖掉书自己在 `<p>` 上写的居中。若要「书显式声明则从书、沉默才兜底」，得把这几行
     * 挪到 `ua.css`（tier 10，作者层 20 压得过）—— 代价是原书设置也会跟着兜底，与「原书听书作者」
     * 冲突。此断言把现状钉住，便于日后决策。
     */
    @Test
    fun `theme justify overrides author align on p because tier beats specificity`() {
        val el = node("p", mapOf("class" to "copyrightbody"), listOf(MarkupElement("#text", text = "正文")))
        val root = node("body", children = listOf(el))
        val prof = profile("modern")
        val author = LightCssParser().parse(".copyrightbody { text-align: center; }")
        val engine = StyleComputer(
            prof.bodyPx, StyleSheet(emptyList()), listOf(author),
            CssLayouter(prof).themeSheetFromProfile(prof),
        )
        assertEquals(TextAlign.JUSTIFY, engine.compute(root)[el]!!.textAlign)
    }
}