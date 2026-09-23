package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FloatSide
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-a1 悬浮完整版（common 核心）：文本悬浮、并排双浮动、跨容器环绕、右对齐、真 clear。
 *
 * 口径（fontSize 10 / lh 1.5 → 行高 15；WidthBreaker 10px/字；无 UA 边距）。
 */
class P6aFloatTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

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

    private fun leafBoxes(result: BoxLayoutResult): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) if (b.isContainer) walk(b.childBoxes) else out.add(b)
        }
        walk(result.boxes)
        return out
    }

    @Test
    fun `文本悬浮指定宽环绕`() {
        // div 30% 左浮动（90px）+ 后随段环绕；悬浮盒自身不挂 lead。
        val f = node("div", mapOf("style" to "float: left; width: 30%"), listOf(text("q".repeat(40))))
        val p = node("p", children = listOf(text("x".repeat(200))))
        val root = node("body", children = listOf(f, p))
        val styleMap = styles(root)
        val result = boxes(root, styleMap)
        val leaves = leafBoxes(result)
        val fBox = leaves.first { it.el === f }
        assertEquals(90, fBox.contentWidth)
        assertEquals(0, fBox.contentLeft)
        assertNull(fBox.floatLead)
        // 悬浮高 5 行 x15=75 → K=(75+15-1)/15+1=6，窄宽 300-90-6=204。
        val pBox = leaves.first { it.el === p }
        assertEquals(FloatLead(6, 204, 96f), pBox.floatLead)
        // 字符连续：悬浮 40 字 + p 200 字。
        val lines = result.lines
        for (k in 1 until lines.size) assertEquals(lines[k - 1].charEnd, lines[k].charStart)
        assertEquals(240, lines.last().charEnd)
    }

    @Test
    fun `文本悬浮无指定宽收缩`() {
        // 无宽浮动 shrink-to-fit：默认 1em/字保守估计（10px/字 x12 字=120）。
        val f = node("div", mapOf("style" to "float: right"), listOf(text("q".repeat(12))))
        val p = node("p", children = listOf(text("x".repeat(100))))
        val root = node("body", children = listOf(f, p))
        val result = boxes(root, styles(root))
        val leaves = leafBoxes(result)
        val fBox = leaves.first { it.el === f }
        assertEquals(120, fBox.contentWidth)
        // 右悬浮右对齐：300-120=180。
        assertEquals(180, fBox.contentLeft)
        // 右侧收窄不断行：窄宽 300-120-6=174，x 不移。
        assertEquals(FloatLead(2, 174, 0f), leaves.first { it.el === p }.floatLead)
    }

    @Test
    fun `左右并排双浮动`() {
        val left = node("img", mapOf("width" to "60", "height" to "60", "style" to "float: left"))
        val right = node("img", mapOf("width" to "60", "height" to "60", "style" to "float: right"))
        val p = node("p", children = listOf(text("x".repeat(200))))
        val root = node("body", children = listOf(left, right, p))
        val result = boxes(root, styles(root))
        val leaves = leafBoxes(result)
        // 右悬浮右对齐。
        assertEquals(240, leaves.first { it.el === right }.contentLeft)
        // 双侧同收：300-60-6-60-6=168，x 右移 66。
        val lead = leaves.first { it.el === p }.floatLead!!
        assertEquals(168, lead.widthPx)
        assertEquals(66f, lead.xOffPx, 1e-6f)
        // 两悬浮零高行 + p 行，字符连续 1+1+200。
        val lines = result.lines
        assertEquals(lines[0].yTop, lines[0].yBottom)
        assertEquals(lines[1].yTop, lines[1].yBottom)
        for (k in 1 until lines.size) assertEquals(lines[k - 1].charEnd, lines[k].charStart)
        assertEquals(202, lines.last().charEnd)
    }

    @Test
    fun `跨容器环绕`() {
        // 悬浮在 div 内，后随文本在 div 外（同级兄弟容器首叶）——v1 在此断环绕。
        val img = node("img", mapOf("width" to "100", "height" to "60", "style" to "float: left"))
        val d1 = node("div", children = listOf(img))
        val p = node("p", children = listOf(text("x".repeat(200))))
        val d2 = node("div", children = listOf(p))
        val root = node("body", children = listOf(d1, d2))
        val result = boxes(root, styles(root))
        val leaves = leafBoxes(result)
        assertEquals(FloatLead(5, 194, 106f), leaves.first { it.el === p }.floatLead)
        // p 首行贴着排（y=0），无 60px 空档。
        val firstTextLine = result.lines.first { it.yTop < it.yBottom && it.charStart >= 1 }
        assertEquals(0, firstTextLine.yTop)
    }

    @Test
    fun `同侧相邻悬浮堆叠`() {
        val a = node("img", mapOf("width" to "100", "height" to "60", "style" to "float: left"))
        val b = node("img", mapOf("width" to "100", "height" to "60", "style" to "float: left"))
        val p = node("p", children = listOf(text("x".repeat(400))))
        val root = node("body", children = listOf(a, b, p))
        val result = boxes(root, styles(root))
        // 第二悬浮越过第一（y=60），跨度到底 120；p 前导覆盖 120 高。
        assertEquals(FloatLead(9, 194, 106f), leafBoxes(result).first { it.el === p }.floatLead)
        val lines = result.lines
        assertEquals(0, lines[2].yTop)
        for (k in 1 until lines.size) assertEquals(lines[k - 1].charEnd, lines[k].charStart)
    }

    @Test
    fun `窄列回退双路同退`() {
        // 260/300 宽悬浮 → 窄宽 34 < 120，双双回退：无 lead，emit 越过。
        val img = node("img", mapOf("width" to "260", "height" to "60", "style" to "float: left"))
        val p = node("p", children = listOf(text("x".repeat(60))))
        val root = node("body", children = listOf(img, p))
        val result = boxes(root, styles(root))
        assertNull(leafBoxes(result).first { it.el === p }.floatLead)
        assertEquals(60, result.lines[1].yTop)
    }

    @Test
    fun `重轻叶集在悬浮章一致`() {
        // pending 只改 lead/几何，不改块集与字符流（§6 inv.1 在悬浮下成立）。
        val left = node("img", mapOf("width" to "60", "height" to "60", "style" to "float: left"))
        val f = node("div", mapOf("style" to "float: right; width: 25%"), listOf(text("q".repeat(20))))
        val p = node("p", children = listOf(text("x".repeat(120))))
        val root = node("body", children = listOf(left, f, p))
        val engine = StyleComputer(10f, LightCssParser().parse(""), emptyList())
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
        val heavy = ArrayList<MarkupElement>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) if (b.isContainer) walk(b.childBoxes) else heavy.add(b.el!!)
        }
        walk(BoxLayouter(10f, WidthBreaker(10)).layoutBoxes(root, 300, styles, classify).boxes)
        val light = ArrayList<MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(root, light, classify)
        assertEquals(heavy.map { it.tag }, light.map { it.tag })
        val hChar = NormalFlowLayout.accumulateCharStarts(heavy.map {
            if (NormalFlowLayout.isReplaceable(it)) 1L else NormalFlowLayout.leafText(it, styles, classify).length.toLong()
        })
        val lChar = NormalFlowLayout.accumulateCharStarts(light.map {
            if (NormalFlowLayout.isReplaceable(it)) 1L else NormalFlowLayout.leafText(it, styles, classify).length.toLong()
        })
        assertTrue(hChar.contentEquals(lChar))
        // pending 收窄口径：首文本叶双侧同收 300-60-6-75-6=153。
        assertEquals(153, leafBoxes(BoxLayouter(10f, WidthBreaker(10)).layoutBoxes(root, 300, styles, classify)).first { it.el === p }.floatLead!!.widthPx)
    }
}
