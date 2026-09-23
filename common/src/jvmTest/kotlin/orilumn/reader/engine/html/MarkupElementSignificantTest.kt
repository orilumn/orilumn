package orilumn.reader.engine.html

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公共判空语义锁定：Android/desktop 双宿主落位经此单源，行为必须一致。 */
class MarkupElementSignificantTest {

    private fun text(s: String) = MarkupElement("#text", text = s)

    @Test
    fun `纯封面章判空`() {
        // 空 div / 仅空白 / 仅 br+img（无文本叶）均判空。
        assertFalse(MarkupElement("div").hasSignificantText())
        assertFalse(MarkupElement("div", children = listOf(text("  \n "))).hasSignificantText())
        assertFalse(
            MarkupElement("div", children = listOf(MarkupElement("br"), MarkupElement("img")))
                .hasSignificantText(),
        )
    }

    @Test
    fun `正文判有内容`() {
        assertTrue(MarkupElement("p", children = listOf(text("正文"))).hasSignificantText())
        assertTrue(
            MarkupElement(
                "div",
                children = listOf(MarkupElement("p", children = listOf(text("  "), text("a")))),
            ).hasSignificantText(),
        )
    }
}
