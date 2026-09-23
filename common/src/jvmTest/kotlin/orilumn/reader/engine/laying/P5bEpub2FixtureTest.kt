package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ClearSide
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.VerticalAlign
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.layout.ListMarkers
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-b EPUB2 fixture 一致性套件（§2/§3/§4 矩阵 EPUB2 列）。
 *
 * 一册"OPS 风格"章节：XHTML 1.1 标签集 + HTML4 表示型属性 + OPS CSS 必选子集，
 * 每行矩阵 = 一个渲染断言（解析保留 → 级联 → 布局/绘制几何），外加首尾两道
 * 不变式锁：重/轻双路叶集一致（§6 inv.1）与全局字符游标单调（§6 inv.4 前提）。
 */
class P5bEpub2FixtureTest {

    private val converter = HtmlTreeConverter()

    /** OPS 风格单章：块/行内/列表/定义/表格/替换块 + 表示型属性全家桶。 */
    private val html = """
        <h1 id="ch1">第一章</h1>
        <p align="center">居中段<span>续</span></p>
        <blockquote cite="http://x">引用</blockquote>
        <pre>码1
        码2</pre>
        <ul><li>甲</li><li><p>乙一</p><p>乙二</p></li></ul>
        <ol start="3"><li>丙</li><li value="9">丁</li></ol>
        <ol start="5" reversed><li>戊</li><li>己</li></ol>
        <dl><dt>词</dt><dd>释</dd></dl>
        <address>某地</address>
        <hr/>
        <table border="2" cellpadding="6" cellspacing="4" bgcolor="#ffffff"><tr><td valign="top" nowrap="nowrap">格</td><td>贰</td></tr></table>
        <p>链<a href="#ch1">回锚</a>与<a name="n1">名锚</a><strong>粗</strong><em>斜</em><code>码</code><sub>下</sub><sup>上</sup><s>删</s><ins>增</ins><q>引</q><br clear="all"/>后</p>
        <p><img src="i.png" width="100" height="50" align="left"/>图后文</p>
    """.trimIndent()

    /** OPS CSS 必选子集（刻意不用 `display`，见双路测试注释）。 */
    private val css = """
        p { text-indent: 2em; text-align: left; margin: 1em 0; }
        h1 { page-break-before: avoid; }
        ul { list-style-type: disc; }
        table { width: 80%; }
        td { text-align: right; }
    """.trimIndent()

    private fun setup(author: String = css): Triple<MarkupElement, StyleComputer, Map<MarkupElement, ComputedStyle>> {
        val root = converter.convert(html)!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(author)))
        return Triple(root, engine, engine.compute(root))
    }

    private fun tagsOf(root: MarkupElement): Set<String> {
        val out = HashSet<String>()
        fun walk(el: MarkupElement) {
            out.add(el.tag)
            for (c in el.children) walk(c)
        }
        walk(root)
        return out
    }

    private fun findAll(root: MarkupElement, tag: String): List<MarkupElement> {
        val out = ArrayList<MarkupElement>()
        fun walk(el: MarkupElement) {
            if (el.tag == tag) out.add(el)
            for (c in el.children) walk(c)
        }
        walk(root)
        return out
    }

    // ---- §2 标签：零 de-shell ----

    @Test
    fun `epub2 block tags survive parsing`() {
        val tags = tagsOf(converter.convert(html)!!)
        for (t in listOf("p", "h1", "blockquote", "pre", "ul", "ol", "li", "dl", "dt", "dd", "address", "hr", "table", "tr", "td")) {
            assertTrue("block tag <$t> de-shelled", t in tags)
        }
    }

    @Test
    fun `epub2 inline tags survive parsing`() {
        val tags = tagsOf(converter.convert(html)!!)
        for (t in listOf("span", "strong", "em", "a", "code", "sub", "sup", "s", "ins", "q", "br", "img")) {
            assertTrue("inline tag <$t> de-shelled", t in tags)
        }
    }

    // ---- §3 属性：保留 + 表示型进级联 ----

    @Test
    fun `presentation attrs retained on nodes`() {
        val root = converter.convert(html)!!
        val img = findAll(root, "img").single()
        assertEquals("i.png", img.attrs["src"])
        assertEquals("100", img.attrs["width"])
        assertEquals("50", img.attrs["height"])
        assertEquals("left", img.attrs["align"])
        val table = findAll(root, "table").single()
        assertEquals("2", table.attrs["border"])
        assertEquals("6", table.attrs["cellpadding"])
        assertEquals("4", table.attrs["cellspacing"])
        assertEquals("#ffffff", table.attrs["bgcolor"])
        val td = findAll(root, "td").first()
        assertEquals("top", td.attrs["valign"])
        assertEquals("true", td.attrs["nowrap"])
        val br = findAll(root, "br").single()
        assertEquals("all", br.attrs["clear"])
        val hrefs = findAll(root, "a").map { it.attrs["href"] }
        assertTrue("#ch1" in hrefs)
        assertTrue(findAll(root, "a").any { it.attrs["name"] == "n1" })
        val ols = findAll(root, "ol")
        assertEquals("3", ols[0].attrs["start"])
        assertEquals("9", findAll(ols[0], "li")[1].attrs["value"])
        assertEquals("true", ols[1].attrs["reversed"])
        assertEquals("http://x", findAll(root, "blockquote").single().attrs["cite"])
        assertEquals("ch1", findAll(root, "h1").single().attrs["id"])
    }

    @Test
    fun `align maps to text-align in cascade`() {
        val (root, _, styles) = setup("")
        val centered = findAll(root, "p").first { it.attrs["align"] == "center" }
        assertEquals(TextAlign.CENTER, styles[centered]!!.textAlign)
    }

    @Test
    fun `table presentation attrs enter cascade`() {
        val (root, _, styles) = setup("")
        val table = findAll(root, "table").single()
        assertEquals(2f, styles[table]!!.border.top, 1e-3f)
        assertEquals(6f, styles[table]!!.padding.top, 1e-3f)
        assertEquals("#ffffffff", styles[table]!!.backgroundColorHex)
        assertEquals(4f, styles[table]!!.borderSpacingH, 1e-3f)
        val td = findAll(root, "td").first()
        assertEquals(VerticalAlign.TOP, styles[td]!!.verticalAlign)
        assertEquals(WhiteSpace.NOWRAP, styles[td]!!.whiteSpace)
    }

    @Test
    fun `author css beats presentation attrs`() {
        // fixture css: td{text-align:right} 压过 align=center（浏览器语义）。
        val (root, _, styles) = setup()
        val td = findAll(root, "td").first()
        assertEquals(TextAlign.RIGHT, styles[td]!!.textAlign)
    }

    @Test
    fun `br clear attr drives clear side`() {
        val (root, _, styles) = setup("")
        val br = findAll(root, "br").single()
        assertEquals(ClearSide.BOTH, styles[br]!!.clearSide)
    }

    // ---- §2/§4 列表：编号 + marker 依附 ----

    @Test
    fun `ol start value reversed numbering`() {
        val root = converter.convert(html)!!
        val ols = findAll(root, "ol")
        val lis = findAll(ols[0], "li")
        assertEquals(3, ListMarkers.numberForItem(ols[0], lis[0]))
        assertEquals(9, ListMarkers.numberForItem(ols[0], lis[1]))
        assertTrue(ListMarkers.reversedFor(ols[1]))
        assertEquals(5, ListMarkers.numberFor(ols[1], 1))
        assertEquals(4, ListMarkers.numberFor(ols[1], 2))
        assertEquals("9", ListMarkers.markerText(ListMarkers.Kind.DECIMAL, 9))
    }

    @Test
    fun `ul first carrier carries disc marker`() {
        val (root, engine, styles) = setup()
        val leaves = ArrayList<MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(
            root, leaves,
            NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration()),
            HiddenCheck { styles[it]?.displayNone == true },
        )
        val carriers = ListMarkers.firstCarrierSet(leaves)
        val styleOf: (MarkupElement) -> ComputedStyle? = { styles[it] }
        // <li>甲</li> 首叶有 marker；<li><p>乙一</p><p>乙二</p></li> 仅首段有。
        val yi1 = leaves.first { NormalFlowLayout.leafText(it, styles, NormalFlowLayout.DEFAULT_CLASSIFY) == "乙一" }
        val yi2 = leaves.first { NormalFlowLayout.leafText(it, styles, NormalFlowLayout.DEFAULT_CLASSIFY) == "乙二" }
        val m1 = ListMarkers.markerForLeaf(yi1, carriers, styleOf)
        assertNotNull(m1)
        assertEquals(ListMarkers.Kind.DISC, m1!!.kind)
        assertEquals(ListMarkers.Position.OUTSIDE, m1.position)
        assertNull(ListMarkers.markerForLeaf(yi2, carriers, styleOf))
        val plain = leaves.first { NormalFlowLayout.leafText(it, styles, NormalFlowLayout.DEFAULT_CLASSIFY) == "引用" }
        assertNull(ListMarkers.markerForLeaf(plain, carriers, styleOf))
    }

    // ---- §2/§4 img 可替换块 + display:none ----

    @Test
    fun `img is a replaceable leaf owning no text`() {
        val (root, _, styles) = setup("")
        val img = findAll(root, "img").single()
        assertTrue(NormalFlowLayout.isReplaceable(img))
        val classify = NormalFlowLayout.heavyClassify(styles, false)
        assertEquals("", NormalFlowLayout.leafText(img, styles, classify))
    }

    @Test
    fun `display none hides subtree from leaves`() {
        val root = converter.convert("<p>见</p><p style=\"display:none\">藏</p>")!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), emptyList())
        val styles = engine.compute(root)
        val hiddenP = findAll(root, "p")[1]
        assertTrue(styles[hiddenP]!!.displayNone)
        val hidden = HiddenCheck { styles[it]?.displayNone == true }
        assertTrue(hidden.isHidden(hiddenP))
        val leaves = ArrayList<MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(root, leaves, NormalFlowLayout.DEFAULT_CLASSIFY, hidden)
        assertTrue(leaves.none { it === hiddenP })
    }

    // ---- §6 不变式：双路一致 + 字符游标 ----

    /** 确定性断行 fake：整 run 一行，行高走单源 lineHeightPx。 */
    private class FakeBreaker : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean,
        ): List<BrokenLine> {
            if (text.isEmpty()) return emptyList()
            return listOf(BrokenLine(0 until text.length, lineHeightPx(fontSizePx, lineHeightRatio)))
        }
    }

    private fun lightLeaves(root: MarkupElement, engine: StyleComputer): List<MarkupElement> {
        val leaves = ArrayList<MarkupElement>()
        val classify = if (engine.hasDisplayDeclaration()) {
            val cache = IdentityHashMap<MarkupElement, Boolean>()
            BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || engine.resolveDisplayOnly(el, cache) }
        } else {
            NormalFlowLayout.DEFAULT_CLASSIFY
        }
        val sc = IdentityHashMap<MarkupElement, ComputedStyle>()
        NormalFlowLayout.enumerateBlockLeaves(
            root, leaves, classify, HIDDEN_NONE,
            captionFirst = { t -> !NormalFlowLayout.captionIsBottom(t) { e -> engine.resolve(e, sc) } },
        )
        return leaves
    }

    private fun heavyLeaves(root: MarkupElement, engine: StyleComputer, styles: Map<MarkupElement, ComputedStyle>): List<MarkupElement> {
        val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
        val result = BoxLayouter(10f, FakeBreaker()).layoutBoxes(root, 400, styles, classify)
        val out = ArrayList<MarkupElement>()
        fun walk(boxes: List<LayoutBox>) {
            for (b in boxes) if (b.isContainer) walk(b.childBoxes) else out.add(b.el!!)
        }
        walk(result.boxes)
        return out
    }

    private fun leafKey(el: MarkupElement, styles: Map<MarkupElement, ComputedStyle>): String {
        val classify = NormalFlowLayout.DEFAULT_CLASSIFY
        return el.tag + "|" + NormalFlowLayout.leafText(el, styles, classify)
    }

    @Test
    fun `heavy and light agree on the epub2 leaf set`() {
        // 本章 author css 不含 display 声明：轻路走 DEFAULT_CLASSIFY，与重路同门。
        val (root, engine, styles) = setup()
        assertTrue(!engine.hasDisplayDeclaration())
        assertEquals(
            heavyLeaves(root, engine, styles).map { leafKey(it, styles) },
            lightLeaves(root, engine).map { leafKey(it, styles) },
        )
    }

    @Test
    fun `global char cursor accumulates monotonically`() {
        val starts = NormalFlowLayout.accumulateCharStarts(listOf(3L, 0L, 5L))
        assertEquals(0L, starts[0])
        assertEquals(3L, starts[1])
        assertEquals(3L, starts[2])
    }

    @Test
    fun `ops css subset takes effect`() {
        val (root, _, styles) = setup()
        val h1 = findAll(root, "h1").single()
        assertEquals(orilumn.reader.engine.css.BreakRule.AVOID, styles[h1]!!.breakBefore)
        val p = findAll(root, "p").first { it.attrs["align"] != "center" }
        assertEquals(20f, styles[p]!!.textIndentPx, 1e-3f)
    }
}
