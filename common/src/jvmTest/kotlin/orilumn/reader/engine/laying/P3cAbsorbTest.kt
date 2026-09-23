package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3-c 吸收层 guard：引号/生成字符串/计数器/attr/变换/small-caps 进叶文本，
 * run 下标与字符恒对齐。
 */
class P3cAbsorbTest {

    private val converter = HtmlTreeConverter()

    private fun setup(html: String, css: String): Triple<MarkupElement, Map<MarkupElement, ComputedStyle>, GenOf> {
        val root = converter.convert(html)!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(css)))
        val styleMap = engine.compute(root)
        val pseudoCache = HashMap<Pair<MarkupElement, String>, ComputedStyle?>()
        val pseudoOf: (MarkupElement, String) -> ComputedStyle? = { el, p ->
            pseudoCache.getOrPut(el to p) {
                val base = styleMap[el] ?: return@getOrPut null
                engine.pseudoStyle(el, ancestorsOf(el), base, p)
            }
        }
        val strings = GeneratedContent.resolveStrings(root, { styleMap[it] }, pseudoOf) { false }
        return Triple(root, styleMap, GeneratedContent.genOf(strings, pseudoOf))
    }

    private fun leafOf(root: MarkupElement, tag: String, index: Int = 0): MarkupElement {
        val out = ArrayList<MarkupElement>()
        fun walk(el: MarkupElement) {
            if (el.tag == tag && el.children.none { NormalFlowLayout.DEFAULT_CLASSIFY.isBlock(it) }) out.add(el)
            for (c in el.children) walk(c)
        }
        walk(root)
        return out[index]
    }

    @Test
    fun `裸 q 就地配默认引号`() {
        val (root, styles, gen) = setup("<p>他说<q>你好</q>而已</p>", "")
        val p = leafOf(root, "p")
        val text = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("他说\u201C你好\u201D而已", text)
    }

    @Test
    fun `嵌套 q 配内外两对`() {
        val (root, styles, gen) = setup("<p><q>甲<q>乙</q>丙</q></p>", "")
        val p = leafOf(root, "p")
        val text = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("\u201C甲\u2018乙\u2019丙\u201D", text)
    }

    @Test
    fun `quotes 属性覆盖默认`() {
        val (root, styles, gen) = setup("<p><q>你好</q></p>", "p { quotes: \"«\" \"»\" }")
        val p = leafOf(root, "p")
        val text = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("«你好»", text)
    }

    @Test
    fun `before after 字符串进字符流`() {
        val (root, styles, gen) = setup(
            "<p>正文</p>",
            "p::before { content: \"注：\" } p::after { content: \"（完）\" }",
        )
        val p = leafOf(root, "p")
        val text = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("注：正文（完）", text)
        // 轻路径计数同值。
        val n = NormalFlowLayout.styledCharAdvance(p, { styles[it]!! }, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen)
        assertEquals(text.length.toLong(), n)
    }

    @Test
    fun `ol 计数器与脚注标记`() {
        val (root, styles, gen) = setup(
            "<ol><li>甲</li><li>乙</li></ol><p>见<a href=\"#n1\">注</a>释</p>",
            "li::before { content: counter(list-item) \". \" } a::after { content: \"[\" attr(href) \"]\" }",
        )
        val lis = ArrayList<MarkupElement>()
        fun walk(el: MarkupElement) {
            if (el.tag == "li") lis.add(el)
            for (c in el.children) walk(c)
        }
        walk(root)
        val t0 = NormalFlowLayout.absorbStyled(lis[0], styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        val t1 = NormalFlowLayout.absorbStyled(lis[1], styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("1. 甲", t0)
        assertEquals("2. 乙", t1)
        val p = leafOf(root, "p")
        val tp = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("见注[#n1]释", tp)
    }

    @Test
    fun `text-transform 大小写 1比1`() {
        val (root, styles, gen) = setup(
            "<p>ab cd</p><h1>ef Gh</h1>",
            "p { text-transform: uppercase } h1 { text-transform: capitalize }",
        )
        val p = leafOf(root, "p")
        val h = leafOf(root, "h1")
        val tp = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        val th = NormalFlowLayout.absorbStyled(h, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("AB CD", tp)
        assertEquals("Ef Gh", th)
        assertEquals(5, tp.length)
    }

    @Test
    fun `small-caps 大写加小字号段`() {
        val (root, styles, gen) = setup("<p>ab CD</p>", "p { font-variant: small-caps }")
        val p = leafOf(root, "p")
        val segs = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen)
        assertEquals("AB CD", segs.text)
        val base = NormalFlowLayout.fontBaseOf(p, styles, styles[p])
        val runs = collectFontRuns(
            p, styles, { false }, { NormalFlowLayout.DEFAULT_CLASSIFY.isBlock(it) }, base,
            styles[p]!!.whiteSpace, gen,
        )
        assertEquals("小写 run 一段", 1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(2, runs[0].endExclusive)
        assertEquals(styles[p]!!.fontSizePx * GeneratedContent.SMALL_CAPS_SCALE, runs[0].fontSizePx, 1e-4f)
    }

    @Test
    fun `生成色段下标对齐`() {
        val (root, styles, gen) = setup(
            "<p>正文</p>",
            "p::before { content: \"!\"; color: #ff0000 }",
        )
        val p = leafOf(root, "p")
        val text = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("!正文", text)
        val runs = NormalFlowLayout.leafColorRuns(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen)
        assertEquals(1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(1, runs[0].endExclusive)
        assertTrue(runs[0].argb and 0x00FFFFFF == 0x00FF0000)
    }
}
