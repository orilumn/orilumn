package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import java.util.IdentityHashMap
import kotlin.math.roundToInt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Dual-path consistency lock (P1-A): the heavy (full-chapter cascade + box tree) and the light
 * (lazy-cascade leaf enumeration) paths MUST produce the **same ordered leaf set** and the **same
 * `globalCharStarts`**, because any drift would shift character→page mapping between pagination and
 * on-demand shaping. This is the single invariant by which every block-classification change must pass.
 *
 * The light path consults CSS `display:block` only when the chapter declares `display`
 * ([StyleComputer.hasDisplayDeclaration]); when it does, [StyleComputer.resolveDisplayOnly] must mirror
 * the heavy path's `styleMap[el].displayBlock` exactly.
 */
class BoxPathConsistencyTest {

    /** Deterministic breaker: exactly `charsPerLine` chars per line, width-independent. */
    private class FixedWidthBreaker(private val charsPerLine: Int) : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
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

    private val converter = HtmlTreeConverter()

    private fun engineFor(css: String, ua: String = ""): StyleComputer =
        StyleComputer(10f, LightCssParser().parse(ua), listOf(LightCssParser().parse(css)))

    /** P3-c: 测试期生成内容装配（与生产重/轻两路同式：解析表门控＋伪样式缓存）。 */
    private fun testGenOf(
        root: MarkupElement,
        css: String,
        styleOf: (MarkupElement) -> ComputedStyle?,
        engine: StyleComputer,
        hidden: HiddenCheck,
    ): GenOf {
        val sheets = listOf(LightCssParser().parse(css))
        if (!GeneratedContent.needsPhase(sheets)) return EmptyGen
        val pseudoCache = HashMap<Pair<MarkupElement, String>, ComputedStyle?>()
        val pseudoOf: (MarkupElement, String) -> ComputedStyle? = { el, p ->
            pseudoCache.getOrPut(el to p) {
                val base = styleOf(el) ?: return@getOrPut null
                engine.pseudoStyle(el, ancestorsOf(el), base, p)
            }
        }
        val strings = GeneratedContent.resolveStrings(root, styleOf, pseudoOf, hidden::isHidden)
        return GeneratedContent.genOf(strings, pseudoOf)
    }

    /** Heavy leaf set: full cascade + box tree, using the same display gate as the light path. */
    private fun heavyLeafBoxes(root: MarkupElement, engine: StyleComputer, css: String = ""): List<LayoutBox> {
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = HiddenCheck { styleMap[it]?.displayNone == true }
        val gen = testGenOf(root, css, { styleMap[it] }, engine, hidden)
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 40, styleMap = styleMap, classify = classify, genOf = gen)
        val out = ArrayList<LayoutBox>()
        fun walk(boxes: List<LayoutBox>) { for (b in boxes) if (b.isContainer) walk(b.childBoxes) else out.add(b) }
        walk(result.boxes)
        return out
    }

    /** The base style the light path gives leaf [el] ([orilumn.reader.engine.BoxChapterLayouter.blockStyleFor]):
     *  an anonymous `#text` leaf takes its container block's computed style, every other leaf cascades
     *  its own — the single source the heavy path's box tree also uses. */
    private fun lightBlockStyle(engine: StyleComputer, el: MarkupElement, cache: IdentityHashMap<MarkupElement, ComputedStyle>): ComputedStyle =
        if (el.tag == "#text") engine.resolve(el.parent!!, cache) else engine.resolve(el, cache)

    /** Asserts the two paths produce byte-equal per-leaf base styles for the heading-most fields the
     *  defect B symptom (bold/color changing on flip) actually covers. */
    private fun assertSameLeafStyle(label: String, heavy: ComputedStyle, light: ComputedStyle, leaf: MarkupElement) {
        val ctx = "$label leaf=${leaf.tag} text='${leaf.text.take(8)}'"
        assertEquals("$ctx fontSizePx", heavy.fontSizePx, light.fontSizePx, 0f)
        assertEquals("$ctx lineHeightRatio", heavy.lineHeightRatio, light.lineHeightRatio, 0f)
        assertEquals("$ctx bold", heavy.bold, light.bold)
        assertEquals("$ctx fontWeight", heavy.fontWeight, light.fontWeight)
        assertEquals("$ctx color", heavy.colorHex, light.colorHex)
        assertEquals("$ctx textAlign", heavy.textAlign, light.textAlign)
        assertEquals("$ctx fontFamily", heavy.fontFamily, light.fontFamily)
        assertEquals("$ctx fontFamilies", heavy.fontFamilies, light.fontFamilies)
    }

    /** Light leaf set: lazy path, exactly as [orilumn.reader.engine.BoxChapterLayouter.prepareLight] wires it. */
    private fun lightLeaves(root: MarkupElement, engine: StyleComputer): List<MarkupElement> {
        val leaves = ArrayList<MarkupElement>()
        val classify = if (engine.hasDisplayDeclaration()) {
            val cache = IdentityHashMap<MarkupElement, Boolean>()
            BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || engine.resolveDisplayOnly(el, cache) }
        } else {
            NormalFlowLayout.DEFAULT_CLASSIFY
        }
        // P1-2: caption 叶序喂懒级联（与重路径盒序同式）。
        val sc = IdentityHashMap<MarkupElement, ComputedStyle>()
        NormalFlowLayout.enumerateBlockLeaves(
            root, leaves, classify,
            captionFirst = { t -> !NormalFlowLayout.captionIsBottom(t) { e -> engine.resolve(e, sc) } },
        )
        return leaves
    }

    private fun assertPathsConsistent(root: MarkupElement, engine: StyleComputer, label: String, css: String = "") {
        // P1-2: 重路径以盒 textLength（样式化归一），轻路径以同式 styledCharAdvance（懒级联），
        // 两者必须逐叶相等（空白归一下原始 textLength 不再一致）。
        // P3-c: 两路喂同一 phase-1 生成查找（门控命中才求值），字符起点恒等。
        val heavy = heavyLeafBoxes(root, engine, css)
        val light = lightLeaves(root, engine)
        assertEquals("$label: heavy/light leaf tags differ", heavy.map { it.el?.tag }, light.map { it.tag })
        val heavyStarts = NormalFlowLayout.accumulateCharStarts(heavy.map { it.textLength.toLong() })
        val cache = IdentityHashMap<MarkupElement, ComputedStyle>()
        val lightClassify = if (engine.hasDisplayDeclaration()) {
            val dc = IdentityHashMap<MarkupElement, Boolean>()
            BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || engine.resolveDisplayOnly(el, dc) }
        } else {
            NormalFlowLayout.DEFAULT_CLASSIFY
        }
        val hc = IdentityHashMap<MarkupElement, Boolean>()
        val lightHidden = HiddenCheck { engine.resolveHidden(it, hc) }
        val lightGen = testGenOf(root, css, { e -> engine.resolve(e, cache) }, engine, lightHidden)
        val lightStarts = NormalFlowLayout.accumulateCharStarts(
            light.map { NormalFlowLayout.styledCharAdvance(it, { e -> engine.resolve(e, cache) }, lightClassify, lightHidden, lightGen) },
        )
        assertArrayEquals("$label: heavy/light globalCharStarts differ", heavyStarts, lightStarts)
    }

    @Test
    fun `no display declaration - dual paths agree on current corpus`() {
        // Exercises the P0-A block tags (article/section/nav/dl/address) with no `display` anywhere.
        val root = converter.convert(
            "<article><h1>章</h1><p>第一段正文内容</p><section><p>嵌套段</p></section></article>" +
                "<nav><ul><li>链接一</li></ul></nav><dl><dt>术语</dt><dd>定义文字</dd></dl>" +
                "<address>作者地址</address><p>结尾段落</p>",
        )!!
        val engine = engineFor("")
        assertPathsConsistent(root, engine, "corpus")
    }

    @Test
    fun `display block via stylesheet - dual paths both honor it`() {
        // `<span style=display:block>` would only be honored if the chapter CSS declared `display`, so
        // use a stylesheet rule — both the sheet-gate and resolveDisplayOnly read the same cascade.
        val root = converter.convert("<div>前置<span>甲甲</span>中置<span>乙乙</span>后置</div>")!!
        val engine = engineFor("span { display: block }")
        // Sanity: the sheet really flips the light gate on.
        assertEquals(true, engine.hasDisplayDeclaration())
        // display:block on the spans turns them into blocks; the div's surrounding inline text (前置/中置/后置)
        // is retained as anonymous text leaves interleaved with the block spans.
        assertPathsConsistent(root, engine, "span-display-block")
        val light = lightLeaves(root, engine)
        assertEquals(listOf("#text", "span", "#text", "span", "#text"), light.map { it.tag })
        // The anonymous text runs carry the div's surrounding inline text.
        assertEquals(listOf("前置", "中置", "后置"), listOf(light[0], light[2], light[4]).map { it.text })
    }

    @Test
    fun `inline-only display is ignored consistently on both paths`() {
        // A span with inline style=display:block but NO sheet display declaration: the sheet gate is
        // off, so BOTH paths fall back to tag-only classification and leave the span inline. They agree
        // even though this edge-case display is not applied (documented trade-off of the sheet gate).
        val root = converter.convert("<div>a<span style='display:block'>bb</span><span style='display:block'>cc</span></div>")!!
        val engine = engineFor("")
        assertEquals(false, engine.hasDisplayDeclaration())
        assertPathsConsistent(root, engine, "inline-only-display")
    }

    @Test
    fun `img is a replaceable block in both paths, one char slot each`() {
        // P1-B: an <img> becomes a replaceable block leaf. Both paths must list it as a leaf, give it a
        // single `[k,k+1)` char slot (leafCharAdvance=1), and its box must carry a pixel height derived
        // from width/height attrs.
        val root = converter.convert("<p>前</p><img src='a.png' width='200' height='100'/><p>后</p>")!!
        val engine = engineFor("")
        assertPathsConsistent(root, engine, "img")

        // Heavy box: the img leaf has textLength=1 and a replaceableHeight from its aspect ratio.
        val styleMap = engine.compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, 40, styleMap, NormalFlowLayout.heavyClassify(styleMap, false))
        val boxes = mutableListOf<LayoutBox>()
        fun flatten(boxes0: List<LayoutBox>) { for (b in boxes0) if (b.isContainer) flatten(b.childBoxes) else boxes.add(b) }
        flatten(result.boxes)
        val imgLeaf = boxes.first { it.el?.tag == "img" }
        assertEquals(1, imgLeaf.textLength)
        val breakW = NormalFlowLayout.innerBreakWidth(imgLeaf.style, imgLeaf.contentWidth)
        assertEquals(NormalFlowLayout.replaceableHeightOf(imgLeaf.el!!, imgLeaf.style, breakW), imgLeaf.replaceableHeight)

        // The img emits a single FlowedLine, exactly one char slot wide and as tall as its pixel height.
        val imgLine = result.lines.firstOrNull { it.charEnd - it.charStart == 1 && it.yBottom - it.yTop == imgLeaf.replaceableHeight }
        assertNotNull("expected one img line matching its replaceable height", imgLine)

        // Light path: same single-slot step and an identical replaceableWidth-based height.
        val light = lightLeaves(root, engine)
        assertEquals(listOf("p", "img", "p"), light.map { it.tag })
    }

    @Test
    fun `table rows are the leaves in both paths`() {
        // P2-C: a table expands to one leaf per ROW (not per cell) in both the heavy and light paths,
        // with identical globalCharStarts so pagination/char mapping never drifts.
        val root = converter.convert(
            "<p>前</p><table><tr><td>甲</td><td>乙丙</td></tr><tr><td>丁</td><td>戊己</td></tr></table><p>后</p>",
        )!!
        val engine = engineFor("")
        assertPathsConsistent(root, engine, "table")
        val light = lightLeaves(root, engine)
        // 前(1) + row1(甲+乙丙=3) + row2(丁+戊己=3) + 后(1)
        assertEquals(listOf("p", "tr", "tr", "p"), light.map { it.tag })
    }

    @Test
    fun `table row leaf carries a grid and a pixel height`() {
        val root = converter.convert("<table><tr><td>甲甲</td><td>乙</td></tr><tr><td>丙</td><td>丁</td></tr></table>")!!
        val engine = engineFor("")
        val styleMap = engine.compute(root)
        val result = BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, 40, styleMap, NormalFlowLayout.heavyClassify(styleMap, false))
        val rows = mutableListOf<LayoutBox>()
        fun flatten(boxes0: List<LayoutBox>) { for (b in boxes0) if (b.isContainer) flatten(b.childBoxes) else rows.add(b) }
        flatten(result.boxes)
        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals("tr", first.el?.tag)
        assertNotNull(first.table) // carries the 2D grid for drawing
        assertEquals(2, first.table!!.columnWidths.size)
        assertEquals(2, first.table!!.cells.size)
        // row char range = 甲甲 + 乙 = 3
        assertEquals(3, first.textLength)
        assertEquals(true, first.replaceableHeight >= 1)
    }

    @Test
    fun `table caption order follows caption-side in both paths`() {
        // P1-2: caption 默认置顶；`caption-side: bottom` 沉底；重轻两路叶序/字符起点恒等。
        val top = converter.convert(
            "<table><caption>题注</caption><tr><td>甲</td><td>乙</td></tr></table>",
        )!!
        assertPathsConsistent(top, engineFor(""), "caption-top")
        assertEquals(listOf("caption", "tr"), lightLeaves(top, engineFor("")).map { it.tag })

        val bottom = converter.convert(
            "<table><caption>题注</caption><tr><td>甲</td><td>乙</td></tr></table>",
        )!!
        assertPathsConsistent(bottom, engineFor("table { caption-side: bottom }"), "caption-bottom")
        assertEquals(listOf("tr", "caption"), lightLeaves(bottom, engineFor("table { caption-side: bottom }")).map { it.tag })
    }

    @Test
    fun `container keeps trailing inline text after a block child`() {
        // Regression: <h2><span display:block>第xx章</span> 标题名</h2> must KEEP " 标题名" as an
        // anonymous text leaf instead of dropping it, in BOTH paths with identical char starts.
        val root = converter.convert("<h2><span>第xx章</span> 标题名</h2>")!!
        val engine = engineFor("span { display: block }")
        assertPathsConsistent(root, engine, "anon-block")
        val light = lightLeaves(root, engine)
        assertEquals(listOf("span", "#text"), light.map { it.tag })
        // The trailing inline text survives (it was the reported bug).
        assertNotNull(light[1])
        assertEquals(" 标题名", light[1].text)
    }

    @Test
    fun `p3c generated content and transforms agree on both paths`() {
        // 路线图验收：q 引号、ol 计数样式、脚注标记、大小写变换——重轻两路叶集/字符起点恒等，
        // 且叶文本逐字相等（生成串进字符流）。
        val ua = "q::before { content: open-quote } q::after { content: close-quote }"
        val css = "li::before { content: counter(list-item) \". \" }" +
            "a::after { content: \"[\" attr(href) \"]\" }" +
            ".up { text-transform: uppercase }"
        val root = converter.convert(
            "<p>他说<q>你好</q>而已</p>" +
                "<ol><li>甲</li><li>乙</li></ol>" +
                "<p>见<a href=\"#n1\">注</a>释</p>" +
                "<p class=\"up\">small caps</p>",
        )!!
        val engine = engineFor(css, ua)
        assertPathsConsistent(root, engine, "p3c", css)
        // 重路径叶文本抽查（轻路径起点恒等已由上式锁定）。
        val styleMap = engine.compute(root)
        val gen = testGenOf(root, css, { styleMap[it] }, engine, HiddenCheck { styleMap[it]?.displayNone == true })
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val texts = lightLeaves(root, engine).map { leaf ->
            if (leaf.tag == "#text") leaf.text
            else NormalFlowLayout.leafText(leaf, styleMap, classify, HiddenCheck { false }, gen)
        }
        assertEquals(
            listOf("他说“你好”而已", "1. 甲", "2. 乙", "见注[#n1]释", "SMALL CAPS"),
            texts,
        )
    }

    @Test
    fun `per-leaf base style matches across both paths for anonymous text leaves`() {
        // Defect B: <h2><span display:block>第xx章</span> 标题名</h2>'s anonymous " 标题名" leaf must carry
        // the SAME base ComputedStyle in the heavy (whole-chapter cascade/box tree) and light (lazy
        // cascade) paths. On the heavy path an anonymous #text box reuses its container (h2) style; the
        // light path must now do the same (blockStyleFor → parent), so a heading's bold/color/fontSize/
        // textAlign/fontFamily/fontWeight never drift between the canonical and temp renderings.
        val root = converter.convert("<h2><span>第xx章</span> 标题名</h2><p>正文</p>")!!
        val engine = engineFor("span { display: block; font-weight: bold }")
        val heavy = heavyLeafBoxes(root, engine)
        val light = lightLeaves(root, engine)
        assertEquals("span", light[0].tag)
        assertEquals("#text", light[1].tag)
        assertEquals(listOf("span", "#text", "p"), light.map { it.tag })

        val cache = IdentityHashMap<MarkupElement, ComputedStyle>()
        for ((i, leafEl) in light.withIndex()) {
            assertEquals("light/heavy leaf count diverged", heavy.size, light.size)
            assertSameLeafStyle("anon-block", heavy[i].style, lightBlockStyle(engine, leafEl, cache), leafEl)
        }
    }
}