package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P6-b1 叠排几何：rbc/rtc/rb 保留 + run 配对 + 行高增量 + 双路一致（字符流不变）。
 */
class P6bRubyStackTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun uaSheet(): String =
        ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, "#0000ff")

    private fun styles(root: MarkupElement): Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(uaSheet()), authorSheets = emptyList()).compute(root)

    private fun classify(styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }

    private fun hidden(styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>) =
        HiddenCheck { styleMap[it]?.displayNone == true }

    @Test
    fun `mono run pairs base with single rt`() {
        val rt = node("rt", children = listOf(text("kan")))
        val ruby = node("ruby", children = listOf(text("漢"), rt))
        val p = node("p", children = listOf(text("文"), ruby, text("字")))
        node("body", children = listOf(p))
        val styleMap = styles(p.parent!!)
        val cls = classify(styleMap)
        val hid = hidden(styleMap)
        assertEquals("文漢kan字", NormalFlowLayout.leafText(p, styleMap, cls, hid))
        val runs = NormalFlowLayout.leafRubyRuns(p, styleMap, cls, hid)
        assertEquals(1, runs.size)
        assertEquals(1, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
        assertEquals("kan", runs[0].rtText)
        assertEquals(2, runs[0].rtStart)
        assertEquals(5, runs[0].rtEndExclusive)
        // 行高增量 = 注音字号上取整（0.6em x10 = 6）。
        val broken = listOf(BrokenLine(0 until 6, 15))
        val grown = adjustLineHeightsForRuby(broken, listOf(15), runs)
        assertEquals(listOf(21), grown)
    }

    @Test
    fun `jukugo single rt spans multi-base`() {
        val rt = node("rt", children = listOf(text("にほん")))
        val ruby = node("ruby", children = listOf(text("日本"), rt))
        val p = node("p", children = listOf(ruby))
        node("body", children = listOf(p))
        val styleMap = styles(p.parent!!)
        val runs = NormalFlowLayout.leafRubyRuns(p, styleMap, classify(styleMap), hidden(styleMap))
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
        assertEquals("にほん", runs[0].rtText)
    }

    @Test
    fun `rbc-rb-rtc structure pairs in order`() {
        // <ruby><rbc><rb>漢</rb><rb>字</rb></rbc><rtc><rt>kan</rt><rt>ji</rt></rtc></ruby>
        val rb1 = node("rb", children = listOf(text("漢")))
        val rb2 = node("rb", children = listOf(text("字")))
        val rbc = node("rbc", children = listOf(rb1, rb2))
        val rt1 = node("rt", children = listOf(text("kan")))
        val rt2 = node("rt", children = listOf(text("ji")))
        val rtc = node("rtc", children = listOf(rt1, rt2))
        val ruby = node("ruby", children = listOf(rbc, rtc))
        val p = node("p", children = listOf(ruby))
        node("body", children = listOf(p))
        val styleMap = styles(p.parent!!)
        val runs = NormalFlowLayout.leafRubyRuns(p, styleMap, classify(styleMap), hidden(styleMap))
        // 葉文本 = 漢字kanji（2 base + 5 rt）。
        assertEquals("漢字kanji", NormalFlowLayout.leafText(p, styleMap, classify(styleMap), hidden(styleMap)))
        assertEquals(2, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(1, runs[0].endExclusive)
        assertEquals("kan", runs[0].rtText)
        assertEquals(1, runs[1].start)
        assertEquals(2, runs[1].endExclusive)
        assertEquals("ji", runs[1].rtText)
    }

    @Test
    fun `rp never pairs and bare rt is dropped`() {
        val rp1 = node("rp", children = listOf(text("(")))
        val rt = node("rt", children = listOf(text("kan")))
        val rp2 = node("rp", children = listOf(text(")")))
        val ruby = node("ruby", children = listOf(text("漢"), rp1, rt, rp2))
        val p = node("p", children = listOf(ruby))
        node("body", children = listOf(p))
        val styleMap = styles(p.parent!!)
        // rp display:none：字符流无括号。
        assertEquals("漢kan", NormalFlowLayout.leafText(p, styleMap, classify(styleMap), hidden(styleMap)))
        val runs = NormalFlowLayout.leafRubyRuns(p, styleMap, classify(styleMap), hidden(styleMap))
        assertEquals(1, runs.size)
        // 无 base 的 rt 丢弃。
        val loneRt = node("rt", children = listOf(text("x")))
        val ruby2 = node("ruby", children = listOf(loneRt))
        val p2 = node("p", children = listOf(ruby2))
        node("body", children = listOf(p2))
        val sm2 = styles(p2.parent!!)
        assertTrue(NormalFlowLayout.leafRubyRuns(p2, sm2, classify(sm2), hidden(sm2)).isEmpty())
    }

    @Test
    fun `no ruby is zero-regression`() {
        val p = node("p", children = listOf(text("plain")))
        node("body", children = listOf(p))
        val styleMap = styles(p.parent!!)
        val runs = NormalFlowLayout.leafRubyRuns(p, styleMap, classify(styleMap), hidden(styleMap))
        assertTrue(runs.isEmpty())
        val broken = listOf(BrokenLine(0 until 5, 15))
        assertEquals(listOf(15), adjustLineHeightsForRuby(broken, listOf(15), runs))
    }

    @Test
    fun `light styleOf override matches heavy`() {
        val rt = node("rt", children = listOf(text("kan")))
        val ruby = node("ruby", children = listOf(text("漢"), rt))
        val p = node("p", children = listOf(text("文"), ruby, text("字")))
        node("body", children = listOf(p))
        val styleMap = styles(p.parent!!)
        val cls = classify(styleMap)
        val hid = hidden(styleMap)
        val heavy = NormalFlowLayout.leafRubyRuns(p, styleMap, cls, hid)
        // 轻路径以内联子树表 + 回退喂同一 helper（styleOf 覆盖），结果逐字节一致。
        val sub = HashMap(styleMap)
        val light = collectRubyRuns(
            p, sub, hid::isHidden, cls::isBlock,
            styleMap[p]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL,
            EmptyGen,
        ) { sub[it] }
        assertEquals(heavy, light)
    }
}
