package orilumn.reader.engine.skia

import orilumn.reader.engine.css.ColorRun
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.NormalFlowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Q1-a — 共享 DrawLineBuilder：盒式布局 → 每行 DrawLine 的唯一语义测试。
 *
 * 桌面壳只调用它做单源投影（P3 xLeft/marker 语义在这里守住）；平板 Q1-a 输出端改造后同吃这一份。
 */
class DrawLineBuilderTest {

    private fun build(html: String, ua: String = ""): Map<Int, DrawLine> {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(ua), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        return DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
    }

    @Test
    fun inkColorFlowsIntoEveryDrawLine() {
        // 回归“换主题色内容区不变”：构建点喂的墨色必须逐行写入 DrawLine，
        // 否则 LineWindowDrawer 恒画黑字、安卓页缓存按行相等命中旧位图。
        val root = HtmlTreeConverter().convert("<html><body><p>ink test</p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val ink = 0xFF123456.toInt()
        val map = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f, ink)
        assertTrue("must produce lines", map.isNotEmpty())
        assertTrue("every line carries the theme ink", map.values.all { it.inkColor == ink })
        val def = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
        assertTrue("default ink stays black", def.values.all { it.inkColor == 0xFF000000.toInt() })
    }

    @Test
    fun everyFlowLineCarriesOneDrawLine() {
        val map = build("<html><body><p>first paragraph 中文填充�text</p><h2>title</h2><p>last</p></body></html>")
        assertTrue("must produce lines for shaped text", map.isNotEmpty())
        // 行下标连续无空洞（从 0 起，逐行 +1），与盒流 line 流严丝合缝。
        val keys = map.keys.sorted()
        assertEquals((0 until keys.size).toList(), keys)
    }

    @Test
    fun inlineCssColorBecomesColorRuns() {
        // 回归“书内 CSS 颜色无法显示”：行内着色必须进 DrawLine.colorRuns 交绘制分段上色。
        val root = HtmlTreeConverter().convert("<html><body><p>ab<span style=\"color:#ff0000\">cd</span>ef</p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val map = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
        val dl = map[0] ?: error("no line")
        assertEquals("abcdef", dl.text.substring(dl.range))
        assertEquals(listOf(ColorRun(2, 4, 0xFFFF0000.toInt())), dl.colorRuns)
    }

    @Test
    fun blockCssColorCoversWholeLeaf() {
        val root = HtmlTreeConverter().convert("<html><body><p style=\"color:#0000ff\">hello</p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val map = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
        val dl = map[0] ?: error("no line")
        assertEquals(listOf(ColorRun(0, 5, 0xFF0000FF.toInt())), dl.colorRuns)
    }

    @Test
    fun uncoloredLeafHasNoColorRuns() {
        val map = build("<html><body><p>plain</p></body></html>")
        val dl = map[0] ?: error("no line")
        assertTrue("no author color = empty runs, zero draw overhead", dl.colorRuns.isEmpty())
    }

    @Test
    fun blockBackgroundBecomesPageBackground() {
        // 回归“pre/blockquote 背景色不显示”：盒背景必须按页切片成交付绘制的背景指令。
        val root = HtmlTreeConverter().convert("<html><body><pre>code block</pre></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse("pre{background-color:#eeeeee}"), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val bgs = DrawLineBuilder.pageBackgrounds(result, 0, 1)
        assertTrue("pre background must be emitted", bgs.isNotEmpty())
        assertTrue(bgs.all { it.argb == 0xFFEEEEEE.toInt() })
        assertTrue("band must be non-empty", bgs.all { it.yBottom > it.yTop && it.right > it.left })
        // 无背景的块不产出指令。
        val plain = HtmlTreeConverter().convert("<html><body><p>plain</p></body></html>")!!
        val engine2 = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap2 = engine2.compute(plain)
        val classify2 = NormalFlowLayout.heavyClassify(styleMap2, engine2.hasDisplayDeclaration())
        val result2 = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(plain, 600, styleMap2, classify2)
        assertTrue(DrawLineBuilder.pageBackgrounds(result2, 0, 1).isEmpty())
    }

    @Test
    fun firstLineCarriesIndentOnly() {
        // 首行缩进只给叶首行（2em @16px = 32px），其余行恒 0；绘制侧按此右移。
        val text = "indent me ".repeat(40).trimEnd()
        val map = build("<html><body><p>$text</p></body></html>", ua = "p{text-indent:2em}")
        assertTrue("must wrap into several lines", map.size > 1)
        val first = map[map.keys.min()] ?: error("no first line")
        assertEquals(32f, first.firstLineIndentPx)
        assertTrue("non-first lines carry no indent", map.values.all {
            it == first || it.firstLineIndentPx == 0f
        })
    }

    @Test
    fun blockChildHeadingKeepsColorOnAnonymousText() {
        // 回归“hn标题后半段不变色”：display:block 的行内块把标题拆成容器，
        //  stray 文字合成匿名叶（不在级联表里），须继承父块颜色。
        val root = HtmlTreeConverter().convert(
            "<html><body><h2><span class=\"sec-num\">第 2 章</span>标题文字</h2></body></html>",
        )!!
        val author = "h2{color:#ff0000}.sec-num{display:block}"
        val engine = StyleComputer(16f, LightCssParser().parse(""), listOf(LightCssParser().parse(author)))
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val map = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
        assertEquals(2, map.size)
        val anon = map.values.single { it.text == "标题文字" }
        assertEquals(
            "匿名叶须继承 h2 颜色",
            listOf(ColorRun(0, 4, 0xFFFF0000.toInt())),
            anon.colorRuns,
        )
    }

    @Test
    fun codeRunsAreMappedToMono() {
        val map = build("<html><body><pre>mono block</pre></body></html>")
        val dl = map[0] ?: error("no line")
        assertTrue("pre → monospace", dl.monospace)
        assertEquals("pre", dl.tag)
    }

    @Test
    fun xLeftFollowsBorderAndPadding() {
        val map = build(
            "<html><body><p style=\"padding-left:20px;border-left:4px solid #000\">indented</p></body></html>",
        )
        val dl = map[0] ?: error("no line")
        assertEquals(24, dl.xLeft)
    }

    @Test
    fun listItemCarriesOneMarkerPerCarrier() {
        val map = build("<html><body><ul><li>first item</li><li>second item</li></ul></body></html>")
        val marked = map.values.filter { it.listMarker != null }
        assertEquals("each <li> paints exactly one bullet (its first line)", 2, marked.size)
        assertNotNull(marked[0].listMarker)
        assertNotNull(marked[1].listMarker)
    }

    @Test
    fun replaceableAndTableLeavesProduceNoDrawLines() {
        val map = build(
            "<html><body><img src=\"x.png\"/><p>after image</p></body></html>",
            ua = "img{height:40px}",
        )
        // img 行占位不产 DrawLine；其后段落文本正常产出。
        assertTrue("paragraph text must still be emitted", map.values.any { it.text.contains("after image") })
        assertTrue(map.values.none { it.text.contains("x.png") })
    }

    @Test
    fun soleFigureParagraphEmitsNoObjGlyphAndExposesPageImage() {
        // 回归“图显示为小虚线框内的 obj”：独占插图段落（<p><img/></p>）必须提升为替换叶，
        // 不再产出含 U+FFFC 占位字形的 DrawLine（Skia 会把它画成缺字形小虚线框），
        // 而是经 pageImages 交出图几何由阅读面贴图。
        val root = HtmlTreeConverter().convert("<html><body><p><img src=\"a.png\"/></p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse("img{height:40px}"), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val map = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
        assertTrue("sole-figure must not emit an OBJ-glyph text line", map.isEmpty())
        val imgs = DrawLineBuilder.pageImages(result, 0, 1, null, "Text/Ch.htm")
        assertEquals(1, imgs.size)
        assertEquals("a.png", imgs[0].src)
        assertEquals(40, imgs[0].heightPx)
        assertEquals(40, imgs[0].yBottom - imgs[0].yTop)
    }

    @Test
    fun soleFigureVariantsUnwrapToImageLeafOnBothPaths() {
        // 空白/<br>/span 包裹/div 容器都不改变“独占一张图”的事实：重轻两条路径须同时看到 img 叶。
        val variants = listOf(
            "<p> <img src=\"a.png\"/> </p>",
            "<p><img src=\"a.png\"/><br/></p>",
            "<p><span><img src=\"a.png\"/></span></p>",
            "<div><img src=\"a.png\"/></div>",
        )
        for (html in variants) {
            val root = HtmlTreeConverter().convert("<html><body>$html</body></html>")!!
            val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
            val styleMap = engine.compute(root)
            val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
            val light = ArrayList<orilumn.reader.engine.html.MarkupElement>()
            NormalFlowLayout.enumerateBlockLeaves(root, light, classify, HIDDEN_NONE)
            assertEquals("$html light leaf", listOf("img"), light.map { it.tag })
            val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
            val heavy = ArrayList<orilumn.reader.engine.laying.LayoutBox>()
            fun walk(boxes: List<orilumn.reader.engine.laying.LayoutBox>) {
                for (b in boxes) if (b.isContainer) walk(b.childBoxes) else heavy.add(b)
            }
            walk(result.boxes)
            assertEquals("$html heavy leaf", listOf("img"), heavy.map { it.el?.tag })
            assertEquals("$html one char slot", 1, heavy[0].textLength)
            assertTrue("$html replaceable height", heavy[0].replaceableHeight > 0)
        }
    }

    @Test
    fun mixedTextImageStaysInlineTextLeaf() {
        // 图文混排不是 figure：保持文本叶（含 U+FFFC 行内占位），不进 pageImages（已知缺口如实记录）。
        val root = HtmlTreeConverter().convert("<html><body><p>ab<img src=\"a.png\"/>cd</p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val light = ArrayList<orilumn.reader.engine.html.MarkupElement>()
        NormalFlowLayout.enumerateBlockLeaves(root, light, classify, HIDDEN_NONE)
        assertEquals(listOf("p"), light.map { it.tag })
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val map = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
        assertTrue(map.values.any { it.text.contains("\uFFFC") })
        assertTrue(DrawLineBuilder.pageImages(result, 0, 1, null, "Text/Ch.htm").isEmpty())
    }

    @Test
    fun hiddenBlocksAreExcluded() {
        val root = HtmlTreeConverter().convert("<html><body><p>shown</p><p style=\"display:none\">hidden</p></body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = { el: orilumn.reader.engine.html.MarkupElement -> styleMap[el]?.displayNone == true }
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val map = DrawLineBuilder.build(result, styleMap, classify, hidden, 0f)
        assertTrue(map.values.all { !it.text.contains("hidden") })
        assertTrue(map.values.any { it.text.contains("shown") })
    }

    @Test
    fun rubyStackGrowsRowAndCarriesRuns() {
        // P6-b/c：叠排注音行增高（基行高 + 注音高）且 DrawLine 透传 runs；无注音行零回归。
        val ua = "rp{display:none}rt{font-size:0.6em;vertical-align:super}"
        val root = HtmlTreeConverter().convert(
            "<html><body><p>文<ruby>漢<rt>kan</rt></ruby>字</p><p>plain</p></body></html>",
        )!!
        val engine = StyleComputer(10f, LightCssParser().parse(ua), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = { el: orilumn.reader.engine.html.MarkupElement -> styleMap[el]?.displayNone == true }
        val result = BoxLayouter(10f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val map = DrawLineBuilder.build(result, styleMap, classify, hidden, 0f)
        assertTrue(map.isNotEmpty())
        val rubyLine = map.values.first { it.text.contains("漢") }
        assertTrue("注音行须透传 runs", rubyLine.rubyRuns.isNotEmpty())
        assertEquals("kan", rubyLine.rubyRuns.single().rtText)
        // 注音行盒高于无注音行（同字号同行距下严格更大）。
        val plainLine = map.values.first { it.text == "plain" }
        assertTrue(
            "ruby row ${rubyLine.yBottom - rubyLine.yTop} vs plain ${plainLine.yBottom - plainLine.yTop}",
            rubyLine.yBottom - rubyLine.yTop > plainLine.yBottom - plainLine.yTop,
        )
        assertTrue("无注音行零 runs", plainLine.rubyRuns.isEmpty())
    }

    @Test
    fun rubyRbcRtcStructureSurvivesParse() {
        // P6-b/c：rbc/rtc/rb 不脱壳（语义保留），子文本顺排进字符流。
        val root = HtmlTreeConverter().convert(
            "<html><body><p><ruby><rbc><rb>漢</rb><rb>字</rb></rbc><rtc><rt>kan</rt><rt>ji</rt></rtc></ruby></p></body></html>",
        )!!
        fun tags(el: orilumn.reader.engine.html.MarkupElement, out: MutableList<String>) {
            out.add(el.tag)
            for (c in el.children) tags(c, out)
        }
        val all = ArrayList<String>()
        tags(root, all)
        assertTrue(all.contains("rbc"))
        assertTrue(all.contains("rtc"))
        assertTrue(all.contains("rb"))
        val engine = StyleComputer(10f, LightCssParser().parse("rp{display:none}"), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = { el: orilumn.reader.engine.html.MarkupElement -> styleMap[el]?.displayNone == true }
        val map = DrawLineBuilder.build(
            BoxLayouter(10f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify),
            styleMap, classify, hidden, 0f,
        )
        val dl = map.values.first()
        assertEquals("漢字kanji", dl.text.substring(dl.range))
        assertEquals(2, dl.rubyRuns.size)
    }

    @Test
    fun underlineRunsFlowIntoDrawLines() {
        // 链接下划线透传绘制指令；无下划线行零 runs。
        val ua = "rp{display:none}rt{font-size:0.6em;vertical-align:super}a{text-decoration:underline}"
        val root = HtmlTreeConverter().convert(
            "<html><body><p>见<a href=\"#t\">表1</a>尾</p><p>plain</p></body></html>",
        )!!
        val engine = StyleComputer(10f, LightCssParser().parse(ua), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = { el: orilumn.reader.engine.html.MarkupElement -> styleMap[el]?.displayNone == true }
        val map = DrawLineBuilder.build(
            BoxLayouter(10f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify),
            styleMap, classify, hidden, 0f,
        )
        val linked = map.values.first { it.text.contains("表1") }
        assertEquals(1, linked.underlineRuns.size)
        assertEquals(1, linked.underlineRuns[0].start)
        assertEquals(3, linked.underlineRuns[0].endExclusive)
        assertTrue(map.values.first { it.text == "plain" }.underlineRuns.isEmpty())
    }
}