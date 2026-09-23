package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.VerticalAlign
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1-2 基线偏移收集 guard：与叶文本同一归一化坐标系；纯基线叶零开销；嵌套内层覆盖。
 */
class BaselineShiftsTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun styles(root: MarkupElement, ua: String = "", author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(ua), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun shifts(root: MarkupElement, styleMap: Map<MarkupElement, ComputedStyle>): List<BaselineShift> =
        NormalFlowLayout.leafBaselineShifts(
            root, styleMap,
            BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true },
            HiddenCheck { styleMap[it]?.displayNone == true },
        )

    private fun leafText(root: MarkupElement, styleMap: Map<MarkupElement, ComputedStyle>): String =
        NormalFlowLayout.leafText(
            root, styleMap,
            BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true },
            HiddenCheck { styleMap[it]?.displayNone == true },
        )

    @Test
    fun `纯基线叶回空表`() {
        val p = node("p", children = listOf(text("plain text")))
        val root = node("body", children = listOf(p))
        assertTrue(shifts(p, styles(root)).isEmpty())
    }

    @Test
    fun `sub 与 sup 段下标对齐归一文本`() {
        val p = node(
            "p",
            children = listOf(text("a"), node("sub", children = listOf(text("b"))), text("c"), node("sup", children = listOf(text("d"))), text("e")),
        )
        val root = node("body", children = listOf(p))
        val ua = "sub { vertical-align: sub } sup { vertical-align: super }"
        val styleMap = styles(root, ua = ua)
        assertEquals("abcde", leafText(p, styleMap))
        val ss = shifts(p, styleMap)
        assertEquals(2, ss.size)
        assertEquals(BaselineShift(1, 2, VerticalAlign.SUB.shiftEm()), ss[0])
        assertEquals(BaselineShift(3, 4, VerticalAlign.SUPER.shiftEm()), ss[1])
    }

    @Test
    fun `嵌套内层覆盖外层`() {
        val p = node(
            "p",
            children = listOf(node("sub", children = listOf(text("a"), node("sup", children = listOf(text("b"))), text("c")))),
        )
        val root = node("body", children = listOf(p))
        val ua = "sub { vertical-align: sub } sup { vertical-align: super }"
        val styleMap = styles(root, ua = ua)
        assertEquals("abc", leafText(p, styleMap))
        val ss = shifts(p, styleMap)
        assertEquals(3, ss.size)
        assertEquals(VerticalAlign.SUB.shiftEm(), ss[0].shiftEm)
        assertEquals(VerticalAlign.SUPER.shiftEm(), ss[1].shiftEm)
        assertEquals(VerticalAlign.SUB.shiftEm(), ss[2].shiftEm)
    }

    @Test
    fun `作者 middle 段即便同 face 也产出`() {
        // 位移通道独立于 face：同 face 的 middle 段无 fontRun 但必须有 shift。
        val p = node("p", children = listOf(text("a"), node("span", mapOf("style" to "vertical-align: middle"), children = listOf(text("b"))), text("c")))
        val root = node("body", children = listOf(p))
        val styleMap = styles(root)
        assertTrue(NormalFlowLayout.leafFontRuns(p, styleMap,
            BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true },
            HiddenCheck { styleMap[it]?.displayNone == true }).isEmpty())
        val ss = shifts(p, styleMap)
        assertEquals(1, ss.size)
        assertEquals(BaselineShift(1, 2, VerticalAlign.MIDDLE.shiftEm()), ss[0])
    }
}
