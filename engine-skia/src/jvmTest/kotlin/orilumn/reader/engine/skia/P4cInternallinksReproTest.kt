package orilumn.reader.engine.skia

import orilumn.reader.engine.LinkTarget
import orilumn.reader.engine.LinkTargets
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.layout.LinkRange
import orilumn.reader.engine.layout.LinkRanges
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.wsOfNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * internallinks.epub 真书复现：`表1･1` 链路端到端（级联→行区间→目标解析→字形命中）。
 *
 * 原文（OEBPS/0001.xhtml）：段内 `<a href="0001.xhtml#t.0001.…">表1･1 …</a>` 指向同章表格 div；
 * 书内 common.css：`a:link{#4080c0} … a:active{#f88000}`。逐段断言，点按无反应时先看哪段变红。
 */
class P4cInternallinksReproTest {

    private val frag = "t.0001.メジャーなEPUBリーダのMathJaxサポート"
    private val href = "0001.xhtml#$frag"

    private val html = """
        <html><body>
        <p>これに対して、iBooksはMathMLの直接レンダリングを強化したようです。<a href="$href">表1･1 メジャーなEPUBリーダのMathJaxサポート</a>は、主要なEPUBリーダのMathMLレンダリングサポート状況です。</p>
        <div class="gext tbl" id="$frag"><div><table><tbody><tr><td>MathMLサポート</td><td>MathML直接描画</td></tr></tbody></table></div><div class="caption">表1･1</div></div>
        </body></html>
    """.trimIndent()

    /** 书内 common.css 的 a 规则原样（顺序原样）。 */
    private val css = """
        a { display: inline; text-decoration: underline; }
        a:link { color: #4080c0; }
        a:visited { color: #264d74; }
        a:hover { color: #4080c0; background-color: #d9e6f3; }
        a:active { color: #f88000; background-color: #fffcf8; }
    """.trimIndent()

    private data class Setup(
        val root: orilumn.reader.engine.html.MarkupElement,
        val styleMap: Map<orilumn.reader.engine.html.MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        val classify: BlockClassify,
        val lines: Map<Int, DrawLine>,
    )

    private fun setup(): Setup {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), listOf(LightCssParser().parse(css)))
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val hidden = { el: orilumn.reader.engine.html.MarkupElement -> styleMap[el]?.displayNone == true }
        return Setup(root, styleMap, classify, DrawLineBuilder.build(result, styleMap, classify, hidden, 0f))
    }

    private fun findLink(root: orilumn.reader.engine.html.MarkupElement): orilumn.reader.engine.html.MarkupElement {
        val out = ArrayList<orilumn.reader.engine.html.MarkupElement>()
        fun walk(n: orilumn.reader.engine.html.MarkupElement) {
            if (n.tag == "a" && n.attrs["href"] == href) out.add(n)
            for (c in n.children) walk(c)
        }
        walk(root)
        assertEquals("原文有且仅有一处目标链接", 1, out.size)
        return out[0]
    }

    @Test
    fun `cascade link is blue and underlined`() {
        val s = setup()
        val link = findLink(s.root)
        val st = s.styleMap[link]!!
        assertEquals("a:link 蓝（非 active 橙）", "#ff4080c0", st.colorHex)
        assertTrue("a 下划线", st.underline)
    }

    @Test
    fun `styled link range covers the anchor text`() {
        val s = setup()
        val link = findLink(s.root)
        val leaf = link.parent!!
        val sub = HashMap(s.styleMap)
        val ranges = LinkRanges.ofLeafStyled(
            leaf,
            wsOf = { wsOfNode(it, sub, leaf, sub[leaf]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL) },
            isExcluded = { sub[it]?.displayNone == true },
            isBlock = { NormalFlowLayout.defaultBlock(it) || sub[it]?.displayBlock == true },
            leafWs = sub[leaf]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL,
        )
        assertEquals("单段链接一个区间", 1, ranges.size)
        val r: LinkRange = ranges[0]
        assertEquals(href, r.href)
        val text = NormalFlowLayout.leafText(leaf, sub, s.classify, HIDDEN_NONE)
        assertEquals("表1･1 メジャーなEPUBリーダのMathJaxサポート", text.substring(r.start, r.endExclusive))
    }

    @Test
    fun `href resolves to same-chapter fragment`() {
        val target: LinkTarget? = LinkTargets.resolveLinkTarget(
            href, 0, "OEBPS/0001.xhtml", mapOf("oebps/0001.xhtml" to 0),
        )
        assertEquals(LinkTarget(0, frag), target)
    }

    @Test
    fun `glyph hit lands inside the link range`() {
        val s = setup()
        val linkLine = s.lines.values.firstOrNull { it.text.contains("表1") }
        assertNotNull("含链接的行必须存在", linkLine)
        val line = linkLine!!
        val linkStart = line.text.indexOf("表1")
        assertTrue(linkStart >= 0)
        val yMid = (line.yTop + line.yBottom) / 2f - line.yTop
        // 逐像素扫整行：至少一处字形命中落进链接区间（否则 tight 命中即断点）。
        val hits = (0..line.lineWidthPx step 4).mapNotNull { x -> LineHitTest.hit(line, x.toFloat(), yMid) }
        assertTrue("行内须有命中", hits.isNotEmpty())
        val linkRange = linkStart until line.text.indexOf("サポート") + "サポート".length
        assertTrue(
            "扫行命中 ${hits.size} 处，无一落进链接区间 $linkRange（行区间 ${line.range}）：字形命中即断点",
            hits.any { it in linkRange },
        )
    }

    @Test
    fun `target id survives parsing`() {
        val s = setup()
        val ids = ArrayList<String>()
        fun walk(n: orilumn.reader.engine.html.MarkupElement) {
            n.attrs["id"]?.let { ids.add(it) }
            for (c in n.children) walk(c)
        }
        walk(s.root)
        assertTrue("目标 id 须保留", ids.contains(frag))
    }

    @Test
    fun `footnote sup link resolves cross-chapter`() {
        // 原文注脚：<a class="note-d" href="xnotes.xhtml#n.0001.1.r"><sup>[1]</sup></a>
        val noteHtml = """
            <html><body><p>Readiumは3月にMathJaxを使ったMathML表示をサポートしました<a class="note-d" href="xnotes.xhtml#n.0001.1.r" title="Readium 0.19 update adds MathML support"><sup>[1]</sup></a>。</p></body></html>
        """.trimIndent()
        val root = HtmlTreeConverter().convert(noteHtml)!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), listOf(LightCssParser().parse(css)))
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = { el: orilumn.reader.engine.html.MarkupElement -> styleMap[el]?.displayNone == true }
        val result = BoxLayouter(16f, SkiaParagraphBreaker(0f)).layoutBoxes(root, 600, styleMap, classify)
        val lines = DrawLineBuilder.build(result, styleMap, classify, hidden, 0f)
        // 链接区间：sup 内 [1] 仍归属 a。
        val leaf = run {
            var found: orilumn.reader.engine.html.MarkupElement? = null
            fun walk(n: orilumn.reader.engine.html.MarkupElement) {
                if (n.tag == "p") found = n
                for (c in n.children) walk(c)
            }
            walk(root)
            found!!
        }
        val sub = HashMap(styleMap)
        val ranges = LinkRanges.ofLeafStyled(
            leaf,
            wsOf = { wsOfNode(it, sub, leaf, sub[leaf]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL) },
            isExcluded = { sub[it]?.displayNone == true },
            isBlock = { NormalFlowLayout.defaultBlock(it) || sub[it]?.displayBlock == true },
            leafWs = sub[leaf]?.whiteSpace ?: orilumn.reader.engine.css.WhiteSpace.NORMAL,
        )
        assertEquals(1, ranges.size)
        assertEquals("xnotes.xhtml#n.0001.1.r", ranges[0].href)
        val text = NormalFlowLayout.leafText(leaf, sub, classify, hidden)
        assertEquals("[1]", text.substring(ranges[0].start, ranges[0].endExclusive))
        // 跨章解析：0001 → xnotes。
        assertEquals(
            LinkTarget(4, "n.0001.1.r"),
            LinkTargets.resolveLinkTarget(
                ranges[0].href, 1, "OEBPS/0001.xhtml",
                mapOf("oebps/0001.xhtml" to 1, "oebps/xnotes.xhtml" to 4),
            ),
        )
        // 字形命中：扫行必有命中落进 [1] 区间（含 sup 上标位移行）。
        val line = lines.values.first { it.text.contains("[1]") }
        val yMid = (line.yTop + line.yBottom) / 2f - line.yTop
        val hits = (0..line.lineWidthPx step 4).mapNotNull { x -> LineHitTest.hit(line, x.toFloat(), yMid) }
        val linkRange = line.text.indexOf("[1]") until line.text.indexOf("[1]") + 3
        assertTrue(
            "扫行命中 ${hits.size} 处，无一落进注脚区间 $linkRange：sup+链接即断点",
            hits.any { it in linkRange },
        )
    }
}
