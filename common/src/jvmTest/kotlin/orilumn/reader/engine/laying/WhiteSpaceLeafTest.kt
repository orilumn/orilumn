package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 white-space 落地 guard：归一化（折叠/保留/制表符/收尾）、断行单源、
 * 重轻双路字符计数一致、run 下标对齐。
 */
class WhiteSpaceLeafTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private class FakeBreaker : ParagraphBreaker {
        var calls = 0
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            calls++
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            // 整段一行（调用方只断言委托发生与否，不测真实折行）。
            return if (text.isEmpty()) emptyList() else listOf(BrokenLine(0 until text.length, h))
        }
    }

    private fun styles(root: MarkupElement, author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun classify(styleMap: Map<MarkupElement, ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }

    private fun hidden(styleMap: Map<MarkupElement, ComputedStyle>) =
        HiddenCheck { styleMap[it]?.displayNone == true }

    @Test
    fun `normal 折叠与收尾`() {
        val p = node("p", children = listOf(text("  a   b\n\tc  ")))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root)
        assertEquals("a b c", NormalFlowLayout.leafText(p, styleMap, classify(styleMap), hidden(styleMap)))
    }

    @Test
    fun `pre 保留与制表符展开`() {
        val pre = node("pre", children = listOf(text("a  b\nc\td")))
        val root = node("body", children = listOf(pre))
        val styleMap = styles(root, "pre { white-space: pre }")
        assertEquals("a  b\nc        d", NormalFlowLayout.leafText(pre, styleMap, classify(styleMap), hidden(styleMap)))
    }

    @Test
    fun `pre-line 折空格留换行`() {
        val p = node("p", children = listOf(text("a   b\nc")))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root, "p { white-space: pre-line }")
        assertEquals("a b\nc", NormalFlowLayout.leafText(p, styleMap, classify(styleMap), hidden(styleMap)))
    }

    @Test
    fun `nowrap 断行单源只在硬换行处分段`() {
        // 源码换行在 nowrap 下折叠为空格；只有 `<br>` 才是硬换行。
        val p = node("p", children = listOf(text("aaa bbb"), node("br"), text("ccc\nddd")))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root, "p { white-space: nowrap }")
        val style = styleMap[p]!!
        val text = NormalFlowLayout.leafText(p, styleMap, classify(styleMap), hidden(styleMap))
        assertEquals("aaa bbb\nccc ddd", text)
        val broken = breakLeafLines(FakeBreaker(), text, style, 10, "p")
        assertEquals(2, broken.size)
        assertEquals(0 until 7, broken[0].range)
        assertEquals(8 until 15, broken[1].range)
    }

    @Test
    fun `可换行委托断行器`() {
        val p = node("p", children = listOf(text("aaa bbb")))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root)
        val fake = FakeBreaker()
        val broken = breakLeafLines(fake, "aaa bbb", styleMap[p]!!, 10, "p")
        assertEquals(1, fake.calls)
        assertEquals(1, broken.size)
    }

    @Test
    fun `重盒长度与轻计数恒等`() {
        val p1 = node("p", children = listOf(text("  a  "), node("b", children = listOf(text(" b ")) ), text("c  ")))
        val pre = node("pre", children = listOf(text("x  y")))
        val root = node("body", children = listOf(p1, pre))
        val author = "pre { white-space: pre }"
        val styleMap = styles(root, author)
        val cl = classify(styleMap)
        val hd = hidden(styleMap)
        val boxes = BoxLayouter(10f, FakeBreaker()).layoutBoxes(root, 300, styleMap, cl).let {
            val out = ArrayList<LayoutBox>()
            fun walk(bs: List<LayoutBox>) { for (b in bs) if (b.isContainer) walk(b.childBoxes) else out.add(b) }
            walk(it.boxes)
            out
        }
        assertEquals(2, boxes.size)
        for (b in boxes) {
            val el = b.el!!
            val heavy = b.textLength.toLong()
            val light = NormalFlowLayout.styledCharAdvance(el, { e -> styleMap[e]!! }, cl, hd)
            assertEquals("leaf ${el.tag} heavy=$heavy light=$light", heavy, light)
        }
        assertEquals("a b c", NormalFlowLayout.leafText(p1, styleMap, cl, hd))
        assertEquals("x  y", NormalFlowLayout.leafText(pre, styleMap, cl, hd))
    }

    @Test
    fun `run 下标与归一文本对齐`() {
        val p = node("p", children = listOf(text(" a "), node("code", children = listOf(text(" x "))), text(" b ")))
        val root = node("body", children = listOf(p))
        val ua = "code { font-family: monospace }"
        val styleMap = StyleComputer(10f, LightCssParser().parse(ua), listOf(LightCssParser().parse(""))).compute(root)
        val cl = classify(styleMap)
        val hd = hidden(styleMap)
        val t = NormalFlowLayout.leafText(p, styleMap, cl, hd)
        assertEquals("a x b", t)
        val runs = NormalFlowLayout.leafFontRuns(p, styleMap, cl, hd)
        assertEquals(1, runs.size)
        // 边界空格归左段（约定）：code 贡献 "x "（尾空格归左），run 恒对齐叶文本。
        assertEquals(2, runs[0].start)
        assertEquals(4, runs[0].endExclusive)
        assertEquals("x ", t.substring(runs[0].start, runs[0].endExclusive))
    }

    @Test
    fun `表格单元格按格收尾且行长为和`() {
        val tr = node("tr", children = listOf(
            node("td", children = listOf(text("  a  "))),
            node("td", children = listOf(text("b  c"))),
        ))
        val table = node("table", children = listOf(tr))
        val root = node("body", children = listOf(table))
        val styleMap = styles(root)
        val cl = classify(styleMap)
        val hd = hidden(styleMap)
        val boxes = BoxLayouter(10f, FakeBreaker()).layoutBoxes(root, 300, styleMap, cl).let {
            val out = ArrayList<LayoutBox>()
            fun walk(bs: List<LayoutBox>) { for (b in bs) if (b.isContainer) walk(b.childBoxes) else out.add(b) }
            walk(it.boxes)
            out
        }
        assertEquals(1, boxes.size)
        assertEquals("a".length + "b c".length, boxes[0].textLength)
        val light = NormalFlowLayout.styledCharAdvance(tr, { e -> styleMap[e]!! }, cl, hd)
        assertEquals(boxes[0].textLength.toLong(), light)
    }

    @Test
    fun `匿名 run 整段归一且两路同式`() {
        // 容器杂散文本多空格：重塑形与轻计数都按容器样式整段归一。
        val div = node("div", children = listOf(text("  a   b  "), node("p", children = listOf(text("c")))))
        val root = node("body", children = listOf(div))
        val styleMap = styles(root)
        val cl = classify(styleMap)
        val hd = hidden(styleMap)
        val boxes = BoxLayouter(10f, FakeBreaker()).layoutBoxes(root, 300, styleMap, cl).let {
            val out = ArrayList<LayoutBox>()
            fun walk(bs: List<LayoutBox>) { for (b in bs) if (b.isContainer) walk(b.childBoxes) else out.add(b) }
            walk(it.boxes)
            out
        }
        val anon = boxes.first { it.el?.tag == "#text" }
        assertEquals("a b", NormalFlowLayout.leafText(anon.el, styleMap, cl, hd))
        assertEquals(anon.textLength.toLong(), NormalFlowLayout.styledCharAdvance(anon.el!!, { e -> styleMap[e] ?: styleMap[div]!! }, cl, hd))
    }
}
