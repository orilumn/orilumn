package orilumn.reader.engine.layout

import orilumn.reader.engine.html.HtmlTreeConverter
import org.junit.Assert.assertEquals
import org.junit.Test

/** Link-range extraction: `<a href>` offsets aligned to the block's absorbed text. */
class LinkRangesTest {

    private val converter = HtmlTreeConverter()

    @Test
    fun `extracts a href ranges at correct char offsets`() {
        // 看(1) 这里(2) 和(1) 那儿(2) 结(1) → link1 spans [1,3), link2 spans [4,6).
        val root = converter.convert("<p>看<a href='ch2.html#s'>这里</a>和<a href='#t'>那儿</a>结</p>")!!
        val p = root.children.first { it.tag == "p" }
        val links = LinkRanges.ofLeaf(p)
        assertEquals(2, links.size)
        assertEquals(LinkRange(1, 3, "ch2.html#s"), links[0])
        assertEquals(LinkRange(4, 6, "#t"), links[1])
    }

    @Test
    fun `nested inline inside a preserves full link range`() {
        // a contains bold text: <b>加粗</b>
        val root = converter.convert("<p>甲<a href='x'><b>乙丙</b></a>丁</p>")!!
        val p = root.children.first { it.tag == "p" }
        val links = LinkRanges.ofLeaf(p)
        assertEquals(1, links.size)
        assertEquals(LinkRange(1, 3, "x"), links[0])
    }

    @Test
    fun `an a with no href produces no range`() {
        val root = converter.convert("<p>甲<a>无名</a>乙</p>")!!
        val p = root.children.first { it.tag == "p" }
        assertEquals(0, LinkRanges.ofLeaf(p).size)
    }
}