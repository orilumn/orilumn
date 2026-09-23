package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-a 解析/模型 guard：`@font-face` 记录、`@import` 记录、条件 `@media` 按视口内联、
 * `font-variant`/`font-stretch` 计算、`@font-face` 进需求扫描。
 */
class FontFaceParseTest {

    private val parser = LightCssParser()
    private val vp = CssViewport(widthPx = 600, heightPx = 800)

    @Test
    fun `font-face 记录族名权重与多源`() {
        val sheet = parser.parse(
            """
            @font-face {
              font-family: "Test Serif";
              font-weight: 700;
              font-style: italic;
              src: url("fonts/a.woff2") format("woff2"), url(fonts/a.ttf) format("truetype");
            }
            p { color: red }
            """.trimIndent(),
        )
        assertEquals(1, sheet.cssFontFaces.size)
        val f = sheet.cssFontFaces[0]
        assertEquals("Test Serif", f.family)
        assertEquals(700, f.weight)
        assertEquals(true, f.italic)
        assertEquals(
            listOf(CssFontFaceSource("fonts/a.woff2", "woff2"), CssFontFaceSource("fonts/a.ttf", "truetype")),
            f.sources,
        )
        assertEquals(1, sheet.rules.size)
    }

    @Test
    fun `font-face 无效即丢弃且 local 跳过`() {
        val sheet = parser.parse(
            """
            @font-face { font-family: "NoSrc"; }
            @font-face { src: url(a.ttf); }
            @font-face { font-family: "L"; src: local("X"), url(b.woff) format("woff"); }
            """.trimIndent(),
        )
        assertEquals(1, sheet.cssFontFaces.size)
        assertEquals("L", sheet.cssFontFaces[0].family)
        assertEquals(listOf(CssFontFaceSource("b.woff", "woff")), sheet.cssFontFaces[0].sources)
    }

    @Test
    fun `import 记录 url 与媒体条件`() {
        val sheet = parser.parse(
            """
            @import url("base.css");
            @import "wide.css" screen and (min-width: 500px);
            p { color: red }
            """.trimIndent(),
        )
        assertEquals(2, sheet.imports.size)
        assertEquals(CssImport("base.css", ""), sheet.imports[0])
        assertEquals("wide.css", sheet.imports[1].url)
        assertTrue(sheet.imports[1].media.contains("min-width"))
    }

    @Test
    fun `media 按视口内联或丢弃`() {
        val css = """
            p { color: red }
            @media screen and (max-width: 700px) { p { color: green } }
            @media screen and (min-width: 700px) { p { color: blue } }
            @media print { p { color: black } }
        """.trimIndent()
        // 600px 视口：max-width 命中，min-width/print 丢弃。
        val hit = parser.parse(css, vp)
        assertEquals(2, hit.rules.size)
        assertTrue(hit.rules.any { it.declarations.any { d -> d.value == "green" } })
        // null 视口即历史行为：@media 整体丢弃。
        val legacy = parser.parse(css)
        assertEquals(1, legacy.rules.size)
        // 800px 视口：min-width 命中。
        val wide = parser.parse(css, CssViewport(800, 800))
        assertTrue(wide.rules.any { it.declarations.any { d -> d.value == "blue" } })
    }

    @Test
    fun `media 未知查询安全丢弃`() {
        val sheet = parser.parse(
            "@media (orientation: portrait) { p { color: red } } @media (min-height: 700px) { p { color: green } }",
            vp,
        )
        assertEquals(1, sheet.rules.size)
        assertEquals("green", sheet.rules[0].declarations[0].value)
    }

    @Test
    fun `font-variant 与 stretch 计算`() {
        val sc = StyleComputer(16f, LightCssParser().parse(""), listOf(LightCssParser().parse("p { font-variant: small-caps; font-stretch: condensed }")))
        val root = orilumn.reader.engine.html.MarkupElement("body", children = listOf(orilumn.reader.engine.html.MarkupElement("p")))
        root.children.forEach { it.parent = root }
        val map = sc.compute(root)
        val p = map[root.children[0]]!!
        assertEquals(FontVariant.SMALL_CAPS, p.fontVariant)
        assertEquals(0.75f, p.fontStretch, 1e-6f)
        // 未声明即旧行为。
        val plain = StyleComputer(16f, LightCssParser().parse(""), emptyList()).compute(root)[root.children[0]]!!
        assertEquals(FontVariant.NORMAL, plain.fontVariant)
        assertEquals(1f, plain.fontStretch, 1e-6f)
    }

    @Test
    fun `font-face 族名进需求扫描`() {
        val d = collectFontDemand(
            listOf(
                "@font-face { font-family: \"Book Serif\"; src: url(a.ttf); } p { font-family: \"Book Serif\", serif }",
            ),
        )
        assertTrue(d.families.contains("Book Serif"))
    }
}
