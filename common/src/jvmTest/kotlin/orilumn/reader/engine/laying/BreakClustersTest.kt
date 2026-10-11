package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BreakClusters] 单测：`break-before`/`break-after` 的 avoid 边成簇、强制边（ALWAYS）截断、
 * `break-inside: avoid` 成原子簇。
 *
 * 每个 `<p>` 放 1 个字符、断行器 3 字/行 ⇒ **每块恰好 1 行**，于是行区间可以按块序直读：
 * 第 k 块 = 行 [k, k+1)。
 */
class BreakClustersTest {

    private fun text(t: String) = MarkupElement("#text", text = t)

    private fun p(style: String = ""): MarkupElement {
        val attrs = if (style.isBlank()) emptyMap() else mapOf("style" to style)
        return MarkupElement("p", attrs, listOf(text("a")))
    }

    private class FixedWidthBreaker(private val charsPerLine: Int) : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean,
        ): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + charsPerLine).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private fun clusters(vararg ps: MarkupElement): List<IntRange> {
        val root = MarkupElement("body", emptyMap(), ps.toList())
        val styles = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styles)
        return BreakClusters.of(result.boxes)
    }

    @Test
    fun `无 avoid 边时无簇`() {
        assertTrue(clusters(p(), p(), p()).isEmpty())
    }

    @Test
    fun `break-after avoid 把后随块并入同簇`() {
        // p1 后不断 ⇒ p1+p2 一簇（行 0..2），p3 独立。
        assertEquals(listOf(0 until 2), clusters(p("break-after: avoid"), p(), p()))
    }

    @Test
    fun `break-before avoid 把前导块并入同簇`() {
        // p2 前不断 ⇒ p1+p2 一簇。
        assertEquals(listOf(0 until 2), clusters(p(), p("break-before: avoid"), p()))
    }

    @Test
    fun `legacy page-break-after avoid 同样成簇`() {
        assertEquals(listOf(0 until 2), clusters(p("page-break-after: avoid"), p()))
    }

    @Test
    fun `链式 avoid 边串成整段`() {
        // p1→p2 avoid，p2→p3 avoid ⇒ 三块一簇。
        assertEquals(listOf(0 until 3), clusters(p("break-after: avoid"), p("break-after: avoid"), p()))
    }

    @Test
    fun `强制边截断簇`() {
        // p1|p2 强制断页 ⇒ 簇不跨过 p1；p2→p3 avoid ⇒ 只剩 p2+p3（行 1..3）。
        assertEquals(listOf(1 until 3), clusters(p("break-after: always"), p("break-after: avoid"), p()))
    }

    @Test
    fun `break-before always 也截断`() {
        // p2 前强制断页 ⇒ p1|p2 不成簇；p3 的 avoid-after 把 p4 串上 ⇒ 只剩 p3+p4（行 2..4）。
        assertEquals(listOf(2 until 4), clusters(p("break-after: avoid"), p("break-before: always"), p("break-after: avoid"), p()))
    }

    @Test
    fun `break-inside avoid 的容器整棵成原子簇`() {
        // <div style="break-inside:avoid"><p>a</p><p>a</p></div> + 独立 <p> ⇒ 原子簇行 0..2。
        val inner1 = p()
        val inner2 = p()
        val div = MarkupElement("div", mapOf("style" to "break-inside: avoid"), listOf(inner1, inner2))
        val root = MarkupElement("body", emptyMap(), listOf(div, p()))
        val styles = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styles)
        val got = BreakClusters.of(result.boxes)
        assertEquals(listOf(0 until 2), got)
    }

    @Test
    fun `原子容器可被 avoid 边串上邻块`() {
        // p1(avoid-after) + <div break-inside:avoid>[p,p] ⇒ 串成 [0,3)；div 自身原子簇 [1,3) 同时保留
        //（**嵌套区间是有意的**：分页器逐条独立判「装得下才躲」，丢掉内层会让「内层装得下、外层装不下」
        // 时本该保整的内层被撕）。
        val div = MarkupElement("div", mapOf("style" to "break-inside: avoid"), listOf(p(), p()))
        val root = MarkupElement("body", emptyMap(), listOf(p("break-after: avoid"), div))
        val styles = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styles)
        assertEquals(setOf(0 until 3, 1 until 3), BreakClusters.of(result.boxes).toSet())
    }
}
