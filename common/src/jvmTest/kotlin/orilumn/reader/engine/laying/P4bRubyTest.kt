package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.VerticalAlign
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4-b ruby 注音：`rt` 0.6em 小字 + super 上标（与 sub/sup 同一 run 通道），
 * `rp` 恒隐藏，字符流不变（rt 文本仍 inline 进叶文本）。
 */
class P4bRubyTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    /** 真实 UA 表（token 先换成合法色再解析）。 */
    private fun uaSheet(): String =
        ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, "#0000ff")

    private fun styles(root: MarkupElement): Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(uaSheet()), authorSheets = emptyList()).compute(root)

    private fun classify(styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }

    private fun hidden(styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>) =
        HiddenCheck { styleMap[it]?.displayNone == true }

    private fun rubyTree(): Triple<MarkupElement, MarkupElement, List<MarkupElement>> {
        val rp1 = node("rp", children = listOf(text("(")))
        val rt = node("rt", children = listOf(text("kan")))
        val rp2 = node("rp", children = listOf(text(")")))
        val ruby = node("ruby", children = listOf(text("漢"), rp1, rt, rp2))
        val p = node("p", children = listOf(text("文"), ruby, text("字")))
        val root = node("body", children = listOf(p))
        return Triple(root, rt, listOf(rp1, rp2))
    }

    @Test
    fun `ua ships the rt rule`() {
        val flat = ReaderStylesheets.ua()
        assertTrue(flat, flat.contains("rt { font-size: 0.6em; vertical-align: super; }"))
        assertTrue(flat, flat.contains("rp { display: none; }"))
    }

    @Test
    fun `rt shrinks and superscripts while rp hides`() {
        val (root, rt, rps) = rubyTree()
        val styleMap = styles(root)
        assertEquals(6f, styleMap[rt]!!.fontSizePx, 1e-6f)
        assertEquals(VerticalAlign.SUPER, styleMap[rt]!!.verticalAlign)
        for (rp in rps) assertTrue(styleMap[rp]!!.displayNone)
    }

    @Test
    fun `rt text stays inline in the char stream with a super shift`() {
        val (root, _, _) = rubyTree()
        val p = root.children.first()
        val styleMap = styles(root)
        val cls = classify(styleMap)
        val hid = hidden(styleMap)
        // 字符流恒等：rp 括号消失，rt 注音顺排（不断字符流、无 LAYOUT 块集变化）。
        assertEquals("文漢kan字", NormalFlowLayout.leafText(p, styleMap, cls, hid))
        val shifts = NormalFlowLayout.leafBaselineShifts(p, styleMap, cls, hid)
        assertEquals(1, shifts.size)
        assertEquals(BaselineShift(2, 5, VerticalAlign.SUPER.shiftEm()), shifts[0])
    }

    @Test
    fun `author css can still override rt`() {
        val (root, rt, _) = rubyTree()
        val styleMap = StyleComputer(
            rootFontPx = 10f,
            ua = LightCssParser().parse(uaSheet()),
            authorSheets = listOf(LightCssParser().parse("rt { font-size: 0.5em; vertical-align: baseline; }")),
        ).compute(root)
        assertEquals(5f, styleMap[rt]!!.fontSizePx, 1e-6f)
        assertEquals(VerticalAlign.BASELINE, styleMap[rt]!!.verticalAlign)
        val p = root.children.first()
        assertTrue(NormalFlowLayout.leafBaselineShifts(p, styleMap, classify(styleMap), hidden(styleMap)).isEmpty())
    }
}
