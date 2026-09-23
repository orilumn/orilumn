package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cascade must keep the full `font-family` stack (author order, generics kept), not just the
 * first name — the font pairing walks down to the trailing generic when the first names are not
 * installed (e.g. `"思源宋体 VF", …, serif` must still reach `serif`).
 */
class FontFamilyStackTest {

    private val converter = HtmlTreeConverter()

    private fun familiesOf(authorCss: String): List<String> {
        val root = converter.convert("<html><body><p>正文</p></body></html>")!!
        val styles = StyleComputer(
            16f,
            StyleSheet(emptyList()),
            listOf(LightCssParser().parse(authorCss)),
        ).compute(root)
        val p = root.children.first { it.tag == "p" }
        return styles[p]!!.fontFamilies
    }

    @Test
    fun `完整栈按序保留含保底通用族`() {
        assertEquals(
            listOf("思源宋体 VF", "思源宋体 SC", "思源宋体 CN", "DK-SONGTI", "STSong", "SimSong", "Times New Roman", "serif"),
            familiesOf(
                "body{font-family:\"思源宋体 VF\",\"思源宋体 SC\",\"思源宋体 CN\", \"DK-SONGTI\"," +
                    "\"STSong\",\"SimSong\",\"Times New Roman\",serif}",
            ),
        )
    }

    @Test
    fun `首选名兼容旧单名语义`() {
        val root = converter.convert("<html><body><p>正文</p></body></html>")!!
        val styles = StyleComputer(
            16f,
            StyleSheet(emptyList()),
            listOf(LightCssParser().parse("body{font-family:\"思源宋体 VF\",serif}")),
        ).compute(root)
        val p = root.children.first { it.tag == "p" }
        assertEquals("思源宋体 VF", styles[p]!!.fontFamily)
        assertEquals(listOf("思源宋体 VF", "serif"), styles[p]!!.fontFamilies)
    }

    @Test
    fun `未声明时栈为空可继承`() {
        val root = converter.convert("<html><body><div><p>正文</p></div></body></html>")!!
        val styles = StyleComputer(
            16f,
            StyleSheet(emptyList()),
            listOf(LightCssParser().parse("div{font-family:Georgia,serif}")),
        ).compute(root)
        val p = root.children.first().children.first { it.tag == "p" }
        assertEquals(listOf("Georgia", "serif"), styles[p]!!.fontFamilies)
    }
}
