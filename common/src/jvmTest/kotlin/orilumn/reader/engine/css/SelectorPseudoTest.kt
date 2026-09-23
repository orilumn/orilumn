package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动态伪类匹配（浏览器标准，无状态重排阅读器口径）：
 * `:link` 仅 `<a href>` 恒真；`:visited`/`:hover`/`:active`/`:focus*` 恒假。
 * 回归 internallinks.epub：`a:active{橙}` 曾恒覆盖 `a:link{蓝}`，全站链接变橙。
 */
class SelectorPseudoTest {

    private fun a(href: String? = "#x"): MarkupElement =
        MarkupElement("a", if (href == null) emptyMap() else mapOf("href" to href))

    @Test
    fun `link matches href anchors only`() {
        assertTrue(Selector.parse("a:link")!!.matches(a("#x"), emptyList()))
        assertFalse("无 href 的 a 不是链接", Selector.parse("a:link")!!.matches(a(null), emptyList()))
        assertFalse("非 a 元素不是链接", Selector.parse("span:link")!!.matches(MarkupElement("span"), emptyList()))
    }

    @Test
    fun `visited hover active focus never match`() {
        val el = a("#x")
        for (pseudo in listOf("visited", "hover", "active", "focus", "focus-visible", "focus-within")) {
            assertFalse(":$pseudo 无状态恒假", Selector.parse("a:$pseudo")!!.matches(el, emptyList()))
        }
    }

    @Test
    fun `plain tag still matches regardless of href`() {
        assertTrue(Selector.parse("a")!!.matches(a("#x"), emptyList()))
        assertTrue(Selector.parse("a")!!.matches(a(null), emptyList()))
    }

    @Test
    fun `book link blue beats active orange in the cascade`() {
        // internallinks common.css 原样：a / a:link 蓝 / a:visited / a:hover / a:active 橙。
        val author = """
            a { display: inline; text-decoration: underline; }
            a:link { color: #4080c0; }
            a:visited { color: #264d74; }
            a:hover { color: #4080c0; background-color: #d9e6f3; }
            a:active { color: #f88000; background-color: #fffcf8; }
        """.trimIndent()
        val link = a("0001.xhtml#t")
        val root = MarkupElement("body", children = listOf(MarkupElement("p", children = listOf(link))))
        link.parent = root.children[0]
        root.children[0].parent = root
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(author)))
        val styleMap = engine.compute(root)
        assertEquals("#ff4080c0", styleMap[link]!!.colorHex)
        assertTrue(styleMap[link]!!.underline)
    }
}
