package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CssParserTest {

    private val parser: CssParser = LightCssParser()

    @Test
    fun `空串与空白返回空规则`() {
        assertEquals(0, parser.parse("").rules.size)
        assertEquals(0, parser.parse("   \n  ").rules.size)
    }

    @Test
    fun `基础规则解析选择器与声明`() {
        val sheet = parser.parse("p { color: red; font-size: 16px }")
        assertEquals(1, sheet.rules.size)
        val rule = sheet.rules[0]
        assertEquals(listOf("p"), rule.selectors)
        assertEquals(2, rule.declarations.size)
        assertEquals("color", rule.declarations[0].property)
        assertEquals("red", rule.declarations[0].value)
        assertEquals(false, rule.declarations[0].important)
    }

    @Test
    fun `多选择器逗号分组`() {
        val sheet = parser.parse("h1, h2 { margin: 0 }")
        val rule = sheet.rules[0]
        assertTrue(rule.selectors.contains("h1"))
        assertTrue(rule.selectors.contains("h2"))
    }

    @Test
    fun `类 id 与后代选择器保留`() {
        val sheet = parser.parse(".note { color: blue } #head { font-weight: bold } div p { text-indent: 2em }")
        val selectors = sheet.rules.flatMap { it.selectors }
        assertTrue(selectors.contains(".note"))
        assertTrue(selectors.contains("#head"))
        assertTrue(selectors.any { it.contains("div") && it.contains("p") })
    }

    @Test
    fun `important 标记保留`() {
        val sheet = parser.parse("p { color: red !important }")
        assertEquals(true, sheet.rules[0].declarations[0].important)
    }

    @Test
    fun `注释被剥离`() {
        val sheet = parser.parse("/* head */ p { /* inner */ color: red } /* tail */")
        assertEquals(1, sheet.rules.size)
        assertEquals(1, sheet.rules[0].declarations.size)
        assertEquals("color", sheet.rules[0].declarations[0].property)
    }

    @Test
    fun `media 与 font-face 规则被过滤`() {
        // @media rules are ignored for a reflowable reader; only top-level style rules survive.
        val sheet = parser.parse(
            "@media screen { body { color: red } }" +
                "@font-face { font-family: Custom; src: url(x.woff) }" +
                "body { margin: 0 }",
        )
        assertTrue(sheet.rules.isNotEmpty())
        // Every surviving rule must be an ordinary style rule; @media/@font-face bodies are dropped.
        assertTrue(sheet.rules.none { it.selectors.any { s -> s.contains("@media") || s.contains("@font-face") } })
    }

    @Test
    fun `畸形 CSS 容错返回空`() {
        // Unbalanced braces / garbage must not throw; empty sheet is a valid fail-open result.
        assertTrue(parser.parse("{ p { color: red }").rules.isEmpty() ||
            parser.parse("{ p { color: red }").rules.isEmpty())
    }

    @Test
    fun `字符串值中的大括号与分号不干扰解析`() {
        val sheet = parser.parse("p::before { content: \"}\"; color: red }")
        assertEquals(1, sheet.rules.size)
        assertTrue(sheet.rules[0].selectors.contains("p::before"))
        val byProp = sheet.rules[0].declarations.associateBy { it.property }
        assertEquals("red", byProp["color"]?.value)
        // the "}" stays inside the quoted content value, not treated as a block close
        assertEquals("\"}\"", byProp["content"]?.value)
    }

    @Test
    fun `url 与嵌套函数括号值`() {
        val sheet = parser.parse("body { background: url('data:image/png;base64,xxxx'); }")
        val decl = sheet.rules[0].declarations[0]
        // the semicolon inside data-url must not split the declaration
        assertEquals("background", decl.property)

        val t = parser.parse("p { transform: translate(10px, calc(2px + 20%)) }")
        assertEquals(listOf("transform"), t.rules[0].declarations.map { it.property })
        assertEquals("translate(10px, calc(2px + 20%))", t.rules[0].declarations[0].value)
    }

    @Test
    fun `重要标记变体与数值属性`() {
        val s = parser.parse("p { margin: 0 ; color: red !important }")
        val byProp = s.rules[0].declarations.associateBy { it.property }
        assertEquals("0", byProp["margin"]?.value)
        assertEquals(false, byProp["margin"]?.important)
        assertEquals("red", byProp["color"]?.value)
        assertEquals(true, byProp["color"]?.important)
    }
}