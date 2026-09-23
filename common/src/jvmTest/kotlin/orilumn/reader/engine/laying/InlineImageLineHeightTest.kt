package orilumn.reader.engine.laying

import orilumn.reader.engine.ImageBoundsReader
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Inline-`<img>` line-box heights (CSS 2.1 §10.8): a line holding a replaced element must grow
 * to contain it. Regression for "Kotlin in Action 图 1.3 插图很小" — the box flow used to keep
 * the plain-text strut height (~1 text row) for the image line while the draw shape painted the
 * full bitmap, so pagination overlapped the illustration and follow-up text.
 */
class InlineImageLineHeightTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    /** One line per paragraph (capacity far above test lengths); strut height 15. */
    private class OneLineBreaker : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean,
        ): List<BrokenLine> {
            if (text.isEmpty()) return emptyList()
            return listOf(BrokenLine(0 until text.length, 15))
        }
    }

    private class FakeBounds(private val w: Int, private val h: Int) : ImageBoundsReader {
        override fun decodeBounds(chapterHref: String, src: String): Pair<Int, Int> = w to h
    }

    private fun styles(root: MarkupElement, ua: String, author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(ua), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun figure(): MarkupElement {
        // <p class="fm-figure"><img .../><br/></p> — the book's actual figure shape.
        val img = node("img", mapOf("src" to "a.png"))
        val p = node("p", mapOf("class" to "fm-figure"), listOf(img, node("br")))
        return node("body", children = listOf(p))
    }

    private fun firstLineHeight(
        root: MarkupElement, ua: String, contentW: Int, iw: Int, ih: Int,
    ): Int {
        val result = BoxLayouter(10f, OneLineBreaker())
            .layoutBoxes(root, contentW, styles(root, ua), imageLoader = FakeBounds(iw, ih), chapterHref = "Text/Ch01.htm")
        return result.lines[0].yBottom - result.lines[0].yTop
    }

    @Test
    fun `含图行按 used 高度撑高_有UAmaxWidth时缩到版心`() {
        // 图 1.3 intrinsic 999×359；版心 800 + UA img{max-width:100%} → 800×287。
        // p 的文本为 U+FFFC + \n → 首行是图片行，应为 287（不是 15 的 strut）。
        assertEquals(287, firstLineHeight(figure(), "img{max-width:100%}", 800, 999, 359))
    }

    @Test
    fun `独占插图无maxWidth时按标准溢出原样_不拉伸`() {
        // 内核只做浏览器行为：intrinsic 原样，哪怕超出 800 版心也不缩不伸。
        // 撑满版心是用户层样式主题的工作（架构图：用户层/样式主题），主题未注入则不做。
        assertEquals(359, firstLineHeight(figure(), "", 800, 999, 359))
    }

    @Test
    fun `图文混排无maxWidth时按标准溢出原样`() {
        // 混排不是 figure：intrinsic 原样，哪怕超出 800 版心也不缩。
        val img = node("img", mapOf("src" to "a.png"))
        val p = node("p", children = listOf(text("ab"), img, text("cd")))
        val root = node("body", children = listOf(p))
        assertEquals(359, firstLineHeight(root, "", 800, 999, 359))
    }

    @Test
    fun `图文混排行取最高者`() {
        val img = node("img", mapOf("src" to "a.png"))
        val p = node("p", children = listOf(text("ab"), img, text("cd")))
        val root = node("body", children = listOf(p))
        assertEquals(287, firstLineHeight(root, "img{max-width:100%}", 800, 999, 359))
    }

    @Test
    fun `纯文本行不受影响`() {
        val p = node("p", children = listOf(text("hello")))
        val root = node("body", children = listOf(p))
        assertEquals(15, firstLineHeight(root, "img{max-width:100%}", 800, 999, 359))
    }
}
