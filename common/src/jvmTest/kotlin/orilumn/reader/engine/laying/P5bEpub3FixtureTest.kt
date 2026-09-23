package orilumn.reader.engine.laying

import orilumn.reader.engine.LinkTargets
import orilumn.reader.engine.css.BackgroundRepeat
import orilumn.reader.engine.css.BoxShadow
import orilumn.reader.engine.css.ClearSide
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.CssViewport
import orilumn.reader.engine.css.EmphasisStyle
import orilumn.reader.engine.css.FloatSide
import orilumn.reader.engine.css.FontVariant
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.TextShadow
import orilumn.reader.engine.css.TextTransform
import orilumn.reader.engine.css.VerticalAlign
import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.css.collectBookFonts
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import java.util.IdentityHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-b EPUB3 fixture 一致性套件（§2/§3/§4 矩阵 EPUB3 列）。
 *
 * 一册"语义优先"章节：HTML5 结构/注音/行内语义标签 + 全局属性 + CSS3 模块
 * （`@font-face`/`@media`/合成/绘制/背景/生成内容/悬浮/链接），每行矩阵一个断言。
 */
class P5bEpub3FixtureTest {

    private val converter = HtmlTreeConverter()

    private val html = """
        <section epub:type="chapter" id="s1"><header><h1>题</h1></header>
        <article><p id="p1">文<mark>亮</mark><time datetime="2026-09-21">时</time><data value="42">数</data><ruby>漢<rp>(</rp><rt>kan</rt><rp>)</rp></ruby><bdi>xx</bdi><bdo dir="ltr">yy</bdo><span>行内</span><sup>2</sup><wbr/>后<q>语</q></p>
        <figure><img src="f.png" alt="图"/><figcaption>注</figcaption></figure>
        <aside>旁</aside></article><footer>尾</footer><nav>导</nav><main>主</main></section>
        <p class="note">见<a href="#p1">锚</a>与<a href="ch2.xhtml#s2">跨章</a></p>
        <p><img src="fl.png" style="float: left"/>悬后文</p>
        <p style="clear: both">另起</p>
    """.trimIndent()

    /** CSS3 模块全家桶（刻意不用 `display`，见双路测试注释）。 */
    private val css = """
        p { font-variant: small-caps; text-transform: none; white-space: normal; }
        pre.code { white-space: pre; }
        p.note { border-radius: 8px; box-shadow: 2px 3px 4px #112233; text-shadow: 1px 1px; opacity: 0.5; }
        p.note { background-image: url("bg.png"); background-repeat: no-repeat; }
        span.em { text-emphasis: circle; }
        sup.up { vertical-align: super; }
        p::before { content: "※"; }
        @font-face { font-family: "BookSerif"; src: url("fonts/book.woff2") format("woff2"); }
        @media screen and (min-width: 700px) { p.note { letter-spacing: 2px; } }
    """.trimIndent()

    private fun setup(author: String = css): Triple<MarkupElement, StyleComputer, Map<MarkupElement, ComputedStyle>> {
        val root = converter.convert(html)!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(author)))
        return Triple(root, engine, engine.compute(root))
    }

    private fun setupGen(htmlText: String, cssText: String): Triple<MarkupElement, Map<MarkupElement, ComputedStyle>, GenOf> {
        val root = converter.convert(htmlText)!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(cssText)))
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

    // ---- §2 标签：HTML5 语义零 de-shell ----

    @Test
    fun `epub3 semantic tags survive parsing`() {
        val tags = tagsOf(converter.convert(html)!!)
        for (t in listOf("section", "article", "aside", "header", "footer", "nav", "main", "figure", "figcaption", "mark", "time", "data", "ruby", "rt", "rp", "bdi", "bdo", "wbr", "q", "sup", "span")) {
            assertTrue("semantic tag <$t> de-shelled", t in tags)
        }
    }

    // ---- §3 属性：全局属性保留 ----

    @Test
    fun `global attrs retained`() {
        val root = converter.convert(html)!!
        val section = findAll(root, "section").single()
        assertEquals("chapter", section.attrs["epub:type"])
        assertEquals("s1", section.attrs["id"])
        val time = findAll(root, "time").single()
        assertEquals("2026-09-21", time.attrs["datetime"])
        val bdo = findAll(root, "bdo").single()
        assertEquals("ltr", bdo.attrs["dir"])
        val hrefs = findAll(root, "a").map { it.attrs["href"] }
        assertTrue("#p1" in hrefs)
        assertTrue("ch2.xhtml#s2" in hrefs)
    }

    // ---- §4 P4-b ruby（真实 UA 层） ----

    @Test
    fun `ruby rt superscript under real ua sheet`() {
        val ua = ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, "#0000ff")
        val root = converter.convert(html)!!
        val styles = StyleComputer(10f, LightCssParser().parse(ua), emptyList()).compute(root)
        val rt = findAll(root, "rt").single()
        assertEquals(6f, styles[rt]!!.fontSizePx, 1e-6f)
        assertEquals(VerticalAlign.SUPER, styles[rt]!!.verticalAlign)
        for (rp in findAll(root, "rp")) assertTrue(styles[rp]!!.displayNone)
    }

    // ---- §4 P2 字体与样式表 ----

    @Test
    fun `font-face collected for preload`() {
        val sheet = LightCssParser().parse("""@font-face { font-family: "BookSerif"; src: url("fonts/book.woff2") format("woff2"); }""")
        assertEquals(1, sheet.cssFontFaces.size)
        val refs = collectBookFonts(listOf(sheet), listOf("OEBPS/ch1.xhtml"), "OEBPS/ch1.xhtml") { base, rel ->
            base.substringBeforeLast('/') + "/" + rel
        }
        assertEquals(1, refs.size)
        assertEquals("BookSerif", refs[0].family)
        assertEquals("OEBPS/fonts/book.woff2", refs[0].href)
    }

    @Test
    fun `media gated by viewport`() {
        val cssText = "p { color: #000000 } @media screen and (min-width: 700px) { p { color: #123456 } }"
        fun colorOf(w: Int): String? {
            val root = converter.convert("<p>正文</p>")!!
            val sheet = LightCssParser().parse(cssText, CssViewport(w, 800))
            return StyleComputer(10f, LightCssParser().parse(""), listOf(sheet)).compute(root).values.first { it.colorHex != null }.colorHex
        }
        assertEquals("#ff123456", colorOf(720))
        assertEquals("#ff000000", colorOf(600))
        // null 视口 = 历史行为：整块丢弃，基色生效。
        val root = converter.convert("<p>正文</p>")!!
        val sheet = LightCssParser().parse(cssText)
        val base = StyleComputer(10f, LightCssParser().parse(""), listOf(sheet)).compute(root).values.first { it.colorHex != null }.colorHex
        assertEquals("#ff000000", base)
    }

    // ---- §4 P1-2b/P3-c 合成：大小写/空白/基线 ----

    @Test
    fun `small-caps and transform computed`() {
        val (root, _, styles) = setup()
        val p = findAll(root, "p").first { it.attrs["id"] == "p1" }
        assertEquals(FontVariant.SMALL_CAPS, styles[p]!!.fontVariant)
    }

    @Test
    fun `transform uppercases at absorb time`() {
        val (root, styles, gen) = setupGen("<p>ab cd</p>", "p { text-transform: uppercase }")
        val p = findAll(root, "p").single()
        val text = NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text
        assertEquals("AB CD", text)
        assertEquals(TextTransform.UPPERCASE, styles[p]!!.textTransform)
    }

    @Test
    fun `white-space pre and super baseline computed`() {
        val root = converter.convert("<pre class=\"code\">a  b</pre><p>x<sup class=\"up\">2</sup></p>")!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse("pre.code { white-space: pre; } sup.up { vertical-align: super; }")))
        val styles = engine.compute(root)
        assertEquals(WhiteSpace.PRE, styles[findAll(root, "pre").single()]!!.whiteSpace)
        assertEquals(VerticalAlign.SUPER, styles[findAll(root, "sup").single()]!!.verticalAlign)
    }

    // ---- §4 P3-a/P3-b 绘制与背景 ----

    @Test
    fun `draw props computed`() {
        val (root, _, styles) = setup()
        val note = findAll(root, "p").first { it.attrs["class"] == "note" }
        val s = styles[note]!!
        assertEquals(8f, s.borderRadius.topLeft, 1e-4f)
        assertEquals(BoxShadow(2f, 3f, 4f, "#112233"), s.boxShadow)
        assertEquals(TextShadow(1f, 1f, 0f, null), s.textShadow)
        assertEquals(0.5f, s.opacity, 1e-4f)
        assertEquals("bg.png", s.backgroundImageUrl)
        assertEquals(BackgroundRepeat.NO_REPEAT, s.backgroundRepeat)
    }

    @Test
    fun `epub emphasis prefix computed`() {
        val root = converter.convert("<p>x<span class=\"em\">着</span></p>")!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse("span.em { -epub-text-emphasis-style: filled dot }")))
        val styles = engine.compute(root)
        assertEquals(EmphasisStyle.DOT, styles[findAll(root, "span").single()]!!.emphasisStyle)
    }

    // ---- §4 P3-c 生成内容 ----

    @Test
    fun `before content enters char stream`() {
        val (root, styles, gen) = setupGen("<p class=\"note\">正文</p>", "p.note::before { content: \"※\" }")
        val p = findAll(root, "p").single()
        assertEquals("※正文", NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text)
    }

    @Test
    fun `q quotes generated by default`() {
        val (root, styles, gen) = setupGen("<p>他说<q>你好</q>而已</p>", "")
        val p = findAll(root, "p").single()
        assertEquals("他说\u201C你好\u201D而已", NormalFlowLayout.absorbStyled(p, styles, NormalFlowLayout.DEFAULT_CLASSIFY, HIDDEN_NONE, gen).text)
    }

    // ---- §4 P4-a 悬浮/clear + P4-c 链接 ----

    @Test
    fun `float img and clear para computed`() {
        val (root, _, styles) = setup("")
        val img = findAll(root, "img").first { it.attrs["src"] == "fl.png" }
        assertEquals(FloatSide.LEFT, styles[img]!!.floatSide)
        val cleared = findAll(root, "p").last()
        assertEquals(ClearSide.BOTH, styles[cleared]!!.clearSide)
    }

    @Test
    fun `link targets resolve intra and cross chapter`() {
        val idx = mapOf("oebps/ch1.xhtml" to 0, "oebps/ch2.xhtml" to 1)
        val self = LinkTargets.resolveLinkTarget("#p1", 0, "OEBPS/ch1.xhtml", idx)!!
        assertEquals(0, self.chapterIndex)
        assertEquals("p1", self.fragment)
        val cross = LinkTargets.resolveLinkTarget("ch2.xhtml#s2", 0, "OEBPS/ch1.xhtml", idx)!!
        assertEquals(1, cross.chapterIndex)
        assertEquals("s2", cross.fragment)
        assertNull(LinkTargets.resolveLinkTarget("https://x/y", 0, "OEBPS/ch1.xhtml", idx))
        assertNull(LinkTargets.resolveLinkTarget("nope.xhtml", 0, "OEBPS/ch1.xhtml", idx))
    }

    // ---- §6 不变式：双路一致 ----

    private class FakeBreaker : orilumn.reader.engine.laying.ParagraphBreaker {
        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean,
        ): List<BrokenLine> {
            if (text.isEmpty()) return emptyList()
            return listOf(BrokenLine(0 until text.length, lineHeightPx(fontSizePx, lineHeightRatio)))
        }
    }

    @Test
    fun `heavy and light agree on the epub3 leaf set`() {
        // author css 不含 display 声明：轻路走 DEFAULT_CLASSIFY，与重路同门。
        val root = converter.convert(html)!!
        val engine = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(css)))
        assertTrue(!engine.hasDisplayDeclaration())
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, false)
        val hidden = HiddenCheck { styles[it]?.displayNone == true }

        val light = ArrayList<MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(root, light, NormalFlowLayout.DEFAULT_CLASSIFY, hidden)

        val result = BoxLayouter(10f, FakeBreaker()).layoutBoxes(root, 400, styles, classify)
        val heavy = ArrayList<MarkupElement>()
        fun walk(boxes: List<LayoutBox>) {
            for (b in boxes) if (b.isContainer) walk(b.childBoxes) else heavy.add(b.el!!)
        }
        walk(result.boxes)

        fun key(el: MarkupElement) = el.tag + "|" + NormalFlowLayout.leafText(el, styles, classify, hidden)
        assertEquals(heavy.map(::key), light.map(::key))
        assertTrue(heavy.isNotEmpty())
    }
}
