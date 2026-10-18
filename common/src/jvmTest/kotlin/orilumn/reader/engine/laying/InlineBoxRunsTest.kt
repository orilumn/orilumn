package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内盒单源：**有可见装饰的行内元素**投影成叶文本坐标系的 [InlineBoxRun]。
 *
 * 关键纪律（与 [TextDecorationRunsTest] 对照）：
 *  - **盒属性不继承**（与下划线相反）：`<mark><em>a</em></mark>` 只有 `<mark>` 一个盒；
 *  - **块元素不发 run**（块有自己的盒路径，重复发就是双画）；
 *  - 无装饰行内元素（`<em>`/`<a>`）回空表（零开销）；
 *  - 嵌套盒各发一条（外先内后）。
 */
class InlineBoxRunsTest {

    private fun text(t: String) = MarkupElement("#text", text = t)

    private fun node(
        tag: String,
        attrs: Map<String, String> = emptyMap(),
        children: List<MarkupElement> = emptyList(),
    ): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun uaSheet(): String =
        ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, "#0000ff")

    private fun styles(root: MarkupElement, author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(
            rootFontPx = 10f,
            ua = LightCssParser().parse(uaSheet()),
            authorSheets = listOf(LightCssParser().parse(author)),
        ).compute(root)

    private fun cls(styleMap: Map<MarkupElement, ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }

    private fun hid(styleMap: Map<MarkupElement, ComputedStyle>) =
        HiddenCheck { styleMap[it]?.displayNone == true }

    private fun runs(root: MarkupElement, author: String = ""): List<InlineBoxRun> {
        val sm = styles(root, author)
        return NormalFlowLayout.leafInlineBoxRuns(root, sm, cls(sm), hid(sm))
    }

    @Test
    fun `mark gets a box via UA background`() {
        val m = node("mark", children = listOf(text("重点")))
        val p = node("p", children = listOf(text("见"), m, text("。尾")))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(1, out.size)
        val r = out.single()
        assertEquals(1, r.start)
        assertEquals(3, r.endExclusive)
        assertTrue("UA 给 <mark> 铺了底色", r.bgHex != null)
        assertTrue(r.visible)
    }

    @Test
    fun `span background box covers only its own text`() {
        val s = node("span", mapOf("style" to "background-color:#ffcc00"), listOf(text("hi")))
        val p = node("p", children = listOf(text("a"), s, text("b")))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(listOf(1 to 3), out.map { it.start to it.endExclusive })
    }

    @Test
    fun `box attribute does not inherit to descendants`() {
        val inner = node("em", children = listOf(text("cd")))
        val m = node("mark", children = listOf(text("ab"), inner, text("ef")))
        val p = node("p", children = listOf(m))
        node("body", children = listOf(p))
        // 只有 <mark> 一个盒，覆盖整段（后代文本都属于它的盒）；<em> 无装饰不发 run。
        val out = runs(p)
        assertEquals(1, out.size)
        assertEquals(0, out.single().start)
        assertEquals(6, out.single().endExclusive)
    }

    @Test
    fun `nested boxes both emitted outer first`() {
        val inner = node("em", mapOf("style" to "background-color:#00ff00"), listOf(text("cd")))
        val m = node("mark", children = listOf(text("ab"), inner))
        val p = node("p", children = listOf(m))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(2, out.size)
        // 同起点时外层盒（end 大）在前：绘制外层背景先落。
        assertEquals(0 to 4, out[0].start to out[0].endExclusive)
        assertEquals(2 to 4, out[1].start to out[1].endExclusive)
    }

    @Test
    fun `block background is not emitted as an inline box`() {
        // 块的背景走盒路径；这里绝不能重复发 run。
        val p = node("p", mapOf("style" to "background-color:#eeeeee"), listOf(text("all")))
        node("body", children = listOf(p))
        assertTrue(runs(p).isEmpty())
    }

    @Test
    fun `border only element is emitted`() {
        val s = node(
            "span",
            mapOf("style" to "border-bottom:1px solid #000"),
            listOf(text("ul")),
        )
        val p = node("p", children = listOf(s))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(1, out.size)
        assertTrue(out.single().hasBorderWidth)
        assertEquals(0f, out.single().bgHex?.let { 1f } ?: 0f, 0f)
    }

    @Test
    fun `plain leaf has no runs`() {
        val p = node("p", children = listOf(text("plain"), node("em", children = listOf(text("x")))))
        node("body", children = listOf(p))
        assertTrue(runs(p).isEmpty())
    }

    @Test
    fun `box spans sibling text nodes contiguously`() {
        val m = node("mark", children = listOf(text("ab"), text("cd")))
        val p = node("p", children = listOf(m))
        node("body", children = listOf(p))
        val out = runs(p)
        assertEquals(listOf(0 to 4), out.map { it.start to it.endExclusive })
    }

    // ── 1b：横向 `padding`/`border` 参与断行宽度 ────────────────────────────────
    //
    // 口径：推进量记在**边界左侧那个字位**的 advance 上（`x_i = x_0 + Σ_{j<i} adv[j]`）；
    // 「盒起始于叶首字符」的左边内边距无处可记 ⇒ 单列走首行缩进（`inlineBoxLeadInsetPx`）。

    private fun box(
        start: Int,
        endExclusive: Int,
        padding: Edges = Edges(),
        border: Edges = Edges(),
        bgHex: String? = "#ffcc00",
    ) = InlineBoxRun(start, endExclusive, bgHex = bgHex, padding = padding, border = border)

    @Test
    fun `横向内边距折算成边界两侧的推进量`() {
        val edges = inlineBoxEdges(10, listOf(box(1, 3, padding = Edges(right = 7f, left = 5f))))
        // 左边记在 `start - 1`，右边记在 `endExclusive - 1`。
        assertEquals(listOf(InlineBoxEdge(0, 5f), InlineBoxEdge(2, 7f)), edges)
        assertEquals(0f, inlineBoxLeadInsetPx(listOf(box(1, 3, padding = Edges(left = 5f)))), 1e-6f)
    }

    @Test
    fun `横向边框同样推进后续文字`() {
        val edges = inlineBoxEdges(10, listOf(box(2, 5, border = Edges(right = 4f, left = 3f))))
        assertEquals(listOf(InlineBoxEdge(1, 3f), InlineBoxEdge(4, 4f)), edges)
    }

    @Test
    fun `只有下边框不产生任何推进量`() {
        // `border-bottom: 1px solid` 是书里最常见的行内边框写法 —— 必须**零推进**，
        // 否则「只画」阶段（1a）的像素会整体位移。
        val runs = listOf(box(1, 4, border = Edges(bottom = 2f), bgHex = null))
        assertTrue(inlineBoxEdges(10, runs).isEmpty())
        assertEquals(0f, inlineBoxLeadInsetPx(runs), 1e-6f)
    }

    @Test
    fun `只有底色无内边距零推进`() {
        // `<mark>`（UA 只给 background-color）零推进 ⇒ 1a 像素逐字节不变。
        val runs = listOf(box(0, 4, bgHex = "#fff176"))
        assertTrue(inlineBoxEdges(4, runs).isEmpty())
        assertEquals(0f, inlineBoxLeadInsetPx(runs), 1e-6f)
    }

    @Test
    fun `起始于叶首字符的左边内边距走首行缩进`() {
        val runs = listOf(box(0, 3, padding = Edges(right = 7f, left = 6f)))
        assertEquals(listOf(InlineBoxEdge(2, 7f)), inlineBoxEdges(10, runs))
        assertEquals(6f, inlineBoxLeadInsetPx(runs), 1e-6f)
    }

    @Test
    fun `嵌套盒起点重合时首行缩进叠加`() {
        val runs = listOf(
            box(0, 4, padding = Edges(left = 2f)),
            box(0, 2, padding = Edges(left = 3f)),
        )
        assertEquals(5f, inlineBoxLeadInsetPx(runs), 1e-6f)
    }

    @Test
    fun `不可见的盒不产生推进量`() {
        // 无底色、无边框宽 ⇒ `visible == false`（不该出现的 run），推进量恒 0。
        val runs = listOf(InlineBoxRun(1, 3, bgHex = null))
        assertEquals(0f, inlineBoxLeadInsetPx(runs), 1e-6f)
        assertTrue(inlineBoxEdges(10, runs).isEmpty())
    }

    @Test
    fun `切片自动得到 slice 语义`() {
        val edges = listOf(InlineBoxEdge(0, 5f), InlineBoxEdge(2, 7f))
        // 从 index 2 起切：index 0 的左推进量（盒的左边）落在切片外 ⇒ 续片段不重复推。
        assertEquals(listOf(InlineBoxEdge(0, 7f)), sliceInlineBoxEdges(edges, 2, 5))
        // 整行切片逐字位平移。
        assertEquals(listOf(InlineBoxEdge(3, 5f), InlineBoxEdge(5, 7f)), sliceInlineBoxEdges(edges, -3, 8))
        // 空表/越界不炸。
        assertEquals(emptyList<InlineBoxEdge>(), sliceInlineBoxEdges(edges, 100, 5))
    }

    @Test
    fun `推进量总和与盒内边距总量恒等`() {
        val runs = listOf(
            box(0, 2, padding = Edges(left = 3f, right = 7f), border = Edges(left = 1f)),
            box(4, 9, padding = Edges(left = 5f, right = 11f), border = Edges(right = 2f)),
            box(12, 14, border = Edges(right = 4f)),
        )
        val byEdges = inlineBoxEdges(20, runs).sumOf { it.px.toDouble() } + inlineBoxLeadInsetPx(runs)
        assertEquals(inlineBoxInsetTotalPx(runs).toDouble(), byEdges, 1e-6)
    }

    @Test
    fun `不可见盒不计入首选宽度增量`() {
        val runs = listOf(InlineBoxRun(0, 3, bgHex = null, padding = Edges(left = 9f)))
        assertEquals(0f, inlineBoxInsetTotalPx(runs), 1e-6f)
    }

    @Test
    fun `盒切片后仍带完整横向内边距`() {
        val runs = listOf(box(3, 9, padding = Edges(left = 4f, right = 6f)))
        // 裁到 [3, 5)：片段携带完整横向内边距（min-content 宁高估不低估）。
        val out = sliceInlineBoxRuns(runs, 3, 5)
        assertEquals(1, out.size)
        assertEquals(0, out.single().start)
        assertEquals(2, out.single().endExclusive)
        assertEquals(10f, inlineBoxInsetTotalPx(out), 1e-6f)
        // 完全不相交 ⇒ 空。
        assertEquals(emptyList<InlineBoxRun>(), sliceInlineBoxRuns(runs, 20, 30))
        assertEquals(emptyList<InlineBoxRun>(), sliceInlineBoxRuns(emptyList(), 0, 5))
    }

    /** 每字位 10px 的极简断行器；**认行内盒推进量**（与生产 [orilumn.reader.engine.skia.InhouseParagraphBreaker] 同语义）。 */
    private class EdgeBreaker : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence,
            fontSizePx: Float,
            lineHeightRatio: Float,
            widthPx: Int,
            alignment: TextAlign,
            tag: String?,
            families: List<String>,
            weight: Int,
            italic: Boolean,
            monospace: Boolean,
        ): List<BrokenLine> = breakLines(
            text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace,
            0f, emptyList(), emptyList(), emptyList(),
        )

        override fun breakLines(
            text: CharSequence,
            fontSizePx: Float,
            lineHeightRatio: Float,
            widthPx: Int,
            alignment: TextAlign,
            tag: String?,
            families: List<String>,
            weight: Int,
            italic: Boolean,
            monospace: Boolean,
            firstLineIndentPx: Float,
            fontRuns: List<FontRun>,
            baselineShifts: List<BaselineShift>,
            inlineBoxEdges: List<InlineBoxEdge>,
        ): List<BrokenLine> {
            val per = 10f
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                var x = if (out.isEmpty()) firstLineIndentPx else 0f
                var j = i
                while (j < text.length) {
                    val step = per + inlineBoxEdges.fold(0f) { acc, e -> if (e.index == j) acc + e.px else acc }
                    if (j > i && x + step > widthPx) break
                    x += step
                    j++
                }
                out.add(BrokenLine(i until j, lineHeightPx(fontSizePx, lineHeightRatio)))
                i = j
            }
            return out
        }
    }

    @Test
    fun `横向内边距把后续文字挤到下一行`() {
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1.5f)
        val text = "a".repeat(30)
        // 对照组：无推进量 ⇒ 每行 10 字。
        val plain = breakLeafLines(EdgeBreaker(), text, style, 100, "p")
        assertEquals(listOf(0..9, 10..19, 20..29), plain.map { it.range })
        // start = 1 的盒、左内边距 15：留白插在第 0 字与第 1 字之间 ⇒ 首行从 10 字降到 8 字。
        val withBox = breakLeafLines(
            EdgeBreaker(), text, style, 100, "p",
            inlineBoxRuns = listOf(box(1, 3, padding = Edges(left = 15f))),
        )
        assertEquals(listOf(0..7, 8..17, 18..27, 28..29), withBox.map { it.range })
    }

    @Test
    fun `起始于叶首字符的左边内边距只收窄首行`() {
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1.5f)
        val text = "a".repeat(30)
        // 左内边距 30 折入首行缩进（首行位移 + 首行减宽）：首行 7 字，余行恢复 10 字。
        val out = breakLeafLines(
            EdgeBreaker(), text, style, 100, "p",
            inlineBoxRuns = listOf(box(0, 2, padding = Edges(left = 30f))),
        )
        assertEquals(listOf(0..6, 7..16, 17..26, 27..29), out.map { it.range })
    }

    @Test
    fun `行尾的右内边距照样计入行宽`() {
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1.5f)
        val text = "a".repeat(30)
        // 末两字的盒、右内边距 50：留白记在末字位 ⇒ 第 3 行只容 9 字，余 1 字弃到第 4 行。
        val out = breakLeafLines(
            EdgeBreaker(), text, style, 100, "p",
            inlineBoxRuns = listOf(box(28, 30, padding = Edges(right = 50f))),
        )
        assertEquals(listOf(0..9, 10..19, 20..28, 29..29), out.map { it.range })
    }
}
