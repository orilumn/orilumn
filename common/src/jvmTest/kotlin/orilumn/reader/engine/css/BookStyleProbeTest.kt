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

    /** UA 层与生产读数同源（`ua.css`）：段距书没写时生效值就是 UA 的 1em，必须读得到。 */
    private val ua = LightCssParser().parse(ReaderStylesheets.ua())

    private fun snapshot(authorCss: String, bodyHtml: String): BookStyleProbe.Snapshot {
        val root = converter.convert("<html><body>$bodyHtml</body></html>")!!
        val styles = StyleComputer(
            18f,
            ua,
            listOf(LightCssParser().parse(authorCss)),
        ).compute(root)
        return BookStyleProbe.snapshot(styles)
    }

    @Test
    fun `Rust书式的两项排版一次快照`() {
        // p{margin-top:0;margin-bottom:0.3rem} + body p{text-indent:2em} + 行高 1.3
        // 纵边距不回填（段间距即疏密，书 margin 原样参与折叠），只快照缩进/行距。
        val s = snapshot(
            "html{font-size:18px} body{font-size:0.95rem;line-height:1.3rem} " +
                "p{margin-top:0;margin-bottom:0.3rem;line-height:1.3rem} " +
                "body p{text-indent:2em}",
            "<p>一</p><p>二</p>",
        )
        assertEquals(2.0, s.firstLineIndent, 1e-9)
        assertEquals(1.3, s.lineSpacing, 1e-9)
        // 0.3rem / 17.1px 正文 ≈ 0.32em → 滑条档位 0.3（书只写下边距，折叠距离仍是它）。
        assertEquals(0.3, s.paragraphSpacing, 1e-9)
    }

    @Test
    fun `段距书没写时读到 UA 基线 1em 而不是 0`() {
        val s = snapshot("p { color: red }", "<p>一</p>")
        assertEquals(1.0, s.paragraphSpacing, 1e-9)
    }

    @Test
    fun `段距读数按 0_1 一档量化并夹在 0 到 2em`() {
        assertEquals(0.3, snapshot("p { margin: 0.26em 0 }", "<p>一</p>").paragraphSpacing, 1e-9)
        assertEquals(2.0, snapshot("p { margin: 4em 0 }", "<p>一</p>").paragraphSpacing, 1e-9)
    }

    @Test
    fun `无排版声明的书快照为中性值`() {
        val s = snapshot("p { color: red }", "<p>一</p>")
        assertEquals(0.0, s.firstLineIndent, 1e-9)
        assertEquals(CssDefaults.DEFAULT_LINE_HEIGHT.toDouble(), s.lineSpacing, 1e-9)
    }

    @Test
    fun `行高只写在正文容器上 段落靠继承也读得到`() {
        // §3.3 点名的九本书正是这种写法：line-height 只声明在 body/容器，段落靠继承。
        // 若面板读数按「段落自己有没有声明」判，这类书会被误显示成引擎初值 1.5。
        val s = snapshot("body { line-height: 1.8 }", "<p>一</p><p>二</p>")
        assertEquals(1.8, s.lineSpacing, 1e-9)
    }

    @Test
    fun `行高读数按 0_1 一档量化`() {
        // 滑条是 0.1 一档：读数也必须落在档位上，否则滑条显示值与可调值对不上。
        val s = snapshot("p { line-height: 1.76 }", "<p>一</p>")
        assertEquals(1.8, s.lineSpacing, 1e-9)
    }

    @Test
    fun `字距读数就是段落自己的 letter-spacing 换算成 em`() {
        // 面板字间距滑条是 em 语义（槽位 = em × 500）：18px 正文下 0.9px = 0.05em。
        val s = snapshot("p { letter-spacing: 0.05em }", "<p>一</p>")
        assertEquals(0.05, s.letterSpacing, 1e-9)
    }

    @Test
    fun `字距只写在 body 上靠继承也读得到`() {
        val s = snapshot("body { letter-spacing: 0.03em }", "<p>一</p>")
        assertEquals(0.03, s.letterSpacing, 1e-6)
    }

    @Test
    fun `书没写字距时读数为 0`() {
        assertEquals(0.0, snapshot("p { color: red }", "<p>一</p>").letterSpacing, 1e-9)
    }
}
