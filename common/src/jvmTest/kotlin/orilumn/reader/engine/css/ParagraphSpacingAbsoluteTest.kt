package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 段间距的**绝对值**语义（2026-10-29 由百分比乘算改回）：
 * `StyleComputer.paragraphEm = N` ⇒ `p`/`li` 的上下外边距直接是 N em（不看书原来写多少），
 * `null` ⇒ 不覆盖（书 / UA / 主题的 margin 原样）。疏密（`gapScale`）仍只作用于其它块。
 */
class ParagraphSpacingAbsoluteTest {

    private val html = """<html><body>
        <p>正文一</p><p>正文二</p>
        <h1>标题</h1>
        <ul><li>条目</li></ul>
    </body></html>"""

    private fun styles(
        css: String,
        paragraphEm: Float?,
        gapScale: Float = 1f,
        rootPx: Float = 20f,
    ): Map<MarkupElement, ComputedStyle> {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(
            rootPx,
            LightStyleSheet.EMPTY,
            listOf(LightCssParser().parse(css)),
            paragraphEm = paragraphEm,
            gapScale = gapScale,
        )
        return engine.compute(root)
    }

    /** 私有空表别名：`StyleSheet(emptyList())` 在测试里出现太多次，起个短名。 */
    private object LightStyleSheet {
        val EMPTY = StyleSheet(emptyList())
    }

    private fun Map<MarkupElement, ComputedStyle>.first(tag: String): ComputedStyle =
        getValue(keys.first { it.tag == tag })

    /** 元素字号即 em 的基准：字号 20px 时 0.5em = 10px。 */
    private fun assertEm(em: Float, expectedPx: Float, style: ComputedStyle) {
        assertEquals("${em}em 的上外边距", expectedPx, style.margin.top, 1e-3f)
        assertEquals("${em}em 的下外边距", expectedPx, style.margin.bottom, 1e-3f)
    }

    @Test
    fun `没配段间距时书的 margin 原样`() {
        val s = styles("p { margin: 1.4em 0 } h1 { margin: 2em 0 }", paragraphEm = null)
        assertEm(1.4f, 28f, s.first("p"))
        assertEm(2f, 40f, s.first("h1"))
    }

    @Test
    fun `配了段间距就是绝对 em 不随书放大缩小`() {
        // 书里 3em、读者 0.5em ⇒ 结果必须是 0.5em（10px），不是 1.5em 也不是 3em。
        val s = styles("p { margin: 3em 0 } li { margin: 3em 0 }", paragraphEm = 0.5f)
        assertEm(0.5f, 10f, s.first("p"))
        assertEm(0.5f, 10f, s.first("li"))
    }

    @Test
    fun `段间距为 0 时段落紧贴`() {
        val s = styles("p { margin: 1em 0 }", paragraphEm = 0f)
        assertEm(0f, 0f, s.first("p"))
    }

    @Test
    fun `段间距保留书的左右外边距`() {
        val s = styles("p { margin: 0.3em 2em }", paragraphEm = 0.8f)
        val p = s.first("p")
        assertEquals(16f, p.margin.top, 1e-3f)
        assertEquals(16f, p.margin.bottom, 1e-3f)
        // 水平方向不是段间距的地盘：书写的 2em 仍在。
        assertEquals(40f, p.margin.left, 1e-3f)
        assertEquals(40f, p.margin.right, 1e-3f)
    }

    @Test
    fun `段间距不碰标题等结构块`() {
        val s = styles("h1 { margin: 2em 0 }", paragraphEm = 0.4f)
        assertEm(2f, 40f, s.first("h1"))
    }

    @Test
    fun `疏密仍按比例缩放其它块`() {
        val s = styles("h1 { margin: 2em 0 }", paragraphEm = null, gapScale = 0.5f)
        assertEm(1f, 20f, s.first("h1"))
    }
}
