package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BookStyleProbe] reads the book's own first-line indent (used to fill the 首行缩进 slider when
 * switching to 原书设置, so the slider keeps absolute semantics: 0 = no indent, always).
 */
class BookStyleProbeTest {

    private val converter = HtmlTreeConverter()

    private fun probe(authorCss: String, bodyHtml: String): Double {
        val root = converter.convert("<html><body>$bodyHtml</body></html>")!!
        val styles = StyleComputer(
            16f,
            StyleSheet(emptyList()),
            listOf(LightCssParser().parse(authorCss)),
        ).compute(root)
        return BookStyleProbe.firstLineIndentEm(styles)
    }

    @Test
    fun `Rust书式的2em缩进被读出`() {
        assertEquals(
            2.0,
            probe(
                "body p, blockquote p { text-indent: 2em }",
                "<p>第一段</p><p>第二段</p><blockquote><p>引用段</p></blockquote>",
            ),
            1e-9,
        )
    }

    @Test
    fun `无缩进规则的书读出0`() {
        assertEquals(0.0, probe("p { margin: 0 }", "<p>第一段</p><p>第二段</p>"), 1e-9)
    }

    @Test
    fun `多数段落的值胜出`() {
        assertEquals(
            2.0,
            probe(
                "p { text-indent: 2em } p.plain { text-indent: 0 }",
                "<p>一</p><p>二</p><p>三</p><p class=\"plain\">四</p>",
            ),
            1e-9,
        )
    }

    private fun snapshot(authorCss: String, bodyHtml: String): BookStyleProbe.Snapshot {
        val root = converter.convert("<html><body>$bodyHtml</body></html>")!!
        val styles = StyleComputer(
            18f,
            StyleSheet(emptyList()),
            listOf(LightCssParser().parse(authorCss)),
        ).compute(root)
        return BookStyleProbe.snapshot(styles)
    }

    @Test
    fun `Rust书式的三项排版一次快照`() {
        // p{margin-top:0;margin-bottom:0.3rem} + body p{text-indent:2em} + 行高 1.3
        val s = snapshot(
            "html{font-size:18px} body{font-size:0.95rem;line-height:1.3rem} " +
                "p{margin-top:0;margin-bottom:0.3rem;line-height:1.3rem} " +
                "body p{text-indent:2em}",
            "<p>一</p><p>二</p>",
        )
        assertEquals(2.0, s.firstLineIndent, 1e-9)
        assertEquals(0.3, s.paragraphSpacing, 1e-9)
        assertEquals(1.3, s.lineSpacing, 1e-9)
    }

    @Test
    fun `无排版声明的书快照为中性值`() {
        val s = snapshot("p { color: red }", "<p>一</p>")
        assertEquals(0.0, s.firstLineIndent, 1e-9)
        assertEquals(0.0, s.paragraphSpacing, 1e-9)
        assertEquals(CssDefaults.DEFAULT_LINE_HEIGHT.toDouble(), s.lineSpacing, 1e-9)
    }
}
