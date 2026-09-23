package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-a2h: img 悬浮环绕（重路径）——前导计算、环绕断行、零高悬浮行、clear/包容。
 *
 * 口径（fontSize 10 / lh 1.5 → 行高 15；无 UA 边距）：
 * - 100x60 左浮动 + 300 版心 → K=ceil(60/15)+1=5，窄宽 300-100-6=194，xOff 106；
 * - 150x200 左浮动 + 300 版心 → K=ceil(200/15)+1=15，窄宽 144，xOff 156。
 */
class P4aWrapTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    /** Width-driven breaker: chars-per-line from the available width. */
    private class WidthBreaker(private val pxPerChar: Int) : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val cpl = (widthPx / pxPerChar).coerceAtLeast(1)
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + cpl).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private fun styles(root: MarkupElement, author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(author))).compute(root)

    private fun boxes(root: MarkupElement, styleMap: Map<MarkupElement, ComputedStyle>, widthPx: Int = 300): BoxLayoutResult =
        BoxLayouter(10f, WidthBreaker(10)).layoutBoxes(
            root, widthPx, styleMap,
            NormalFlowLayout.heavyClassify(styleMap, StyleComputer(10f, LightCssParser().parse(""), emptyList()).hasDisplayDeclaration()),
        )

    private fun leafBox(result: BoxLayoutResult, tag: String, nth: Int = 0): LayoutBox =
        result.boxes.flatMap { if (it.isContainer) it.childBoxes else listOf(it) }
            .filter { it.el?.tag == tag }.get(nth)

    // ---- floatLeadFor ----

    @Test
    fun `lead 计算与退回规则`() {
        val img = node("img", mapOf("width" to "100", "height" to "60", "style" to "float: left"))
        val p = node("p", children = listOf(text("x".repeat(50))))
        val root = node("body", children = listOf(img, p))
        val styleMap = styles(root)
        val styleOf: (MarkupElement) -> ComputedStyle = { styleMap[it]!! }
        val lead = NormalFlowLayout.floatLeadFor(img, p, styleOf, 300)!!
        assertEquals(5, lead.lines)
        assertEquals(194, lead.widthPx)
        assertEquals(106f, lead.xOffPx, 1e-6f)
        // 非 img 前兄弟 / 无 float / 叶自带 clear / 过宽悬浮 / 合成匿名叶 → null。
        val span = node("span", children = listOf(text("s")))
        val root2 = node("body", children = listOf(span, p))
        val sm2 = styles(root2)
        assertNull(NormalFlowLayout.floatLeadFor(span, p, { sm2[it]!! }, 300))
        val imgPlain = node("img", mapOf("width" to "100", "height" to "60"))
        val root3 = node("body", children = listOf(imgPlain, p))
        val sm3 = styles(root3)
        assertNull(NormalFlowLayout.floatLeadFor(imgPlain, p, { sm3[it]!! }, 300))
        val pClear = node("p", mapOf("style" to "clear: both"), listOf(text("x".repeat(50))))
        val root4 = node("body", children = listOf(img, pClear))
        val sm4 = styles(root4)
        assertNull(NormalFlowLayout.floatLeadFor(img, pClear, { sm4[it]!! }, 300))
        val wide = node("img", mapOf("width" to "290", "height" to "60", "style" to "float: left"))
        val root5 = node("body", children = listOf(wide, p))
        val sm5 = styles(root5)
        assertNull("over-wide float falls back to block", NormalFlowLayout.floatLeadFor(wide, p, { sm5[it]!! }, 300))
        val synth = text("anon").also { it.parent = p }
        assertNull(NormalFlowLayout.floatLeadFor(img, synth, styleOf, 300))
    }

    // ---- breakWrappedLines ----

    @Test
    fun `lead 为空时与单次调用逐字节一致`() {
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1.5f)
        val text = "a".repeat(100)
        val single = breakLeafLines(WidthBreaker(10), text, style, 300, "p")
        val wrapped = breakWrappedLines(WidthBreaker(10), text, style, 300, null, "p")
        assertEquals(single.map { it.range }, wrapped.map { it.range })
    }

    @Test
    fun `前导行收窄余行全宽且字符无缝`() {
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1.5f)
        val out = breakWrappedLines(WidthBreaker(10), "a".repeat(100), style, 300, FloatLead(3, 50, 0f), "p")
        // 窄行 5 字 x3 + 全行 30 字 x3（15+85=100）。
        assertEquals(listOf(0..4, 5..9, 10..14, 15..44, 45..74, 75..99), out.map { it.range })
        assertTrue(out.all { it.heightPx == 15 })
    }

    // ---- 重路径集成 ----

    @Test
    fun `左浮动环绕不断字符流`() {
        val img = node("img", mapOf("width" to "100", "height" to "60", "src" to "a.png", "style" to "float: left"))
        val p = node("p", children = listOf(text("x".repeat(200))))
        val p2 = node("p", children = listOf(text("short")))
        val root = node("body", children = listOf(img, p, p2))
        val styleMap = styles(root)
        val result = boxes(root, styleMap)
        // 盒：悬浮高 60；环绕叶前导 5 行 x194。
        val imgBox = leafBox(result, "img")
        assertEquals(60, imgBox.replaceableHeight)
        val pBox = leafBox(result, "p", 0)
        assertEquals(FloatLead(5, 194, 106f), pBox.floatLead)
        assertNull("no-float control path stays lead-free", leafBox(result, "p", 1).floatLead)
        // 行：[0] 悬浮零高行 [0,1)；p 首行 y=0 贴着排；p2 在悬浮之下无重叠（悬浮 60 < p 尾 135，直接续排）。
        val lines = result.lines
        assertEquals(1 + 9 + 1, lines.size)
        assertEquals(0, lines[0].charStart); assertEquals(1, lines[0].charEnd)
        assertEquals(lines[0].yTop, lines[0].yBottom)
        assertEquals(1, lines[1].charStart); assertEquals(20, lines[1].charEnd)
        assertEquals(0, lines[1].yTop); assertEquals(15, lines[1].yBottom)
        assertEquals(135, lines[10].yTop)
        // 字符连续无洞：逐行首尾相接。
        for (k in 1 until lines.size) assertEquals(lines[k - 1].charEnd, lines[k].charStart)
    }

    @Test
    fun `高悬浮后段延续环绕不断档`() {
        // P6-a R3：p 吃掉 75px 后余 125px，p2 延续收窄（10 行配额，实 1 行），不再留 125px 空档。
        val img = node("img", mapOf("width" to "150", "height" to "200", "src" to "a.png", "style" to "float: left"))
        val p = node("p", children = listOf(text("y".repeat(60))))
        val p2 = node("p", children = listOf(text("short")))
        val root = node("body", children = listOf(img, p, p2))
        val result = boxes(root, styles(root))
        val pBox = leafBox(result, "p", 0)
        assertEquals(FloatLead(15, 144, 156f), pBox.floatLead)
        assertEquals(FloatLead(10, 144, 156f), leafBox(result, "p", 1).floatLead)
        val lines = result.lines
        // p 5 窄行高 75；p2 贴着续排（75），无重叠（窄行），字符连续。
        assertEquals(1 + 5 + 1, lines.size)
        assertEquals(0, lines[1].yTop)
        assertEquals(75, lines[5].yBottom)
        assertEquals(75, lines[6].yTop)
        assertEquals(90, lines[6].yBottom)
        for (k in 1 until lines.size) assertEquals(lines[k - 1].charEnd, lines[k].charStart)
    }

    @Test
    fun `环绕叶自带 clear 则整段退回块式`() {
        val img = node("img", mapOf("width" to "150", "height" to "200", "src" to "a.png", "style" to "float: left"))
        val p = node("p", mapOf("style" to "clear: both"), listOf(text("y".repeat(60))))
        val root = node("body", children = listOf(img, p))
        val result = boxes(root, styles(root))
        assertNull(leafBox(result, "p", 0).floatLead)
        // 全宽不断（30 字行 x2）且落在悬浮之下。
        assertEquals(1 + 2, result.lines.size)
        assertEquals(200, result.lines[1].yTop)
    }

    @Test
    fun `侧别不匹配的 clear 环绕不断不交叠`() {
        // P6-a R4：clear:right 只屏蔽右侧，左侧悬浮仍收窄环绕——v1 在此整宽重叠悬浮区，现已闭合。
        val img = node("img", mapOf("width" to "150", "height" to "200", "src" to "a.png", "style" to "float: left"))
        val p = node("p", children = listOf(text("y".repeat(60))))
        val p2 = node("p", mapOf("style" to "clear: right"), listOf(text("short")))
        val root = node("body", children = listOf(img, p, p2))
        val result = boxes(root, styles(root))
        assertEquals(FloatLead(10, 144, 156f), leafBox(result, "p", 1).floatLead)
        assertEquals(75, result.lines.last().yTop)
    }

    @Test
    fun `无悬浮旧几何逐字节不变`() {
        val img = node("img", mapOf("width" to "100", "height" to "60", "src" to "a.png"))
        val p = node("p", children = listOf(text("x".repeat(200))))
        val root = node("body", children = listOf(img, p))
        val result = boxes(root, styles(root))
        assertNull(leafBox(result, "p", 0).floatLead)
        // 非悬浮 img 仍是整行推进（60 高行），p 在其后。
        assertEquals(60, result.lines[0].yBottom - result.lines[0].yTop)
        assertEquals(60, result.lines[1].yTop)
    }
}
