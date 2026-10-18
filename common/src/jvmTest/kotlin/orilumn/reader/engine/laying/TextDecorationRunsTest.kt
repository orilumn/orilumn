package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.DecorationLine
import orilumn.reader.engine.css.DecorationStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文本装饰单源（第三刀）：把已级联的 `text-decoration-*` 投影成叶文本坐标系的
 * [TextDecorationRun]，锁住四条浏览器语义：
 *
 * 1. **线型各归各的**：`underline` / `line-through` / `overline` 不再互相冒充
 *    （旧模型把 `line-through` 并成下划线）；
 * 2. **非继承但传播**：祖先声明的装饰覆盖后代文本，后代写 `none` 也**取消不掉**（规范如此）；
 * 3. **外观取声明元素**：颜色 / 线型样式 / 粗细 / skip-ink 都按**声明它的那个元素**解析，
 *    `currentColor` 用声明元素的有效颜色（不是叶自己的）；
 * 4. **可叠层**：祖先一层 + 自身一层同时存在（`<a><s>` 这种）。
 */
class TextDecorationRunsTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs, children)
        for (c in children) c.parent = el
        return el
    }

    private fun uaSheet(): String =
        ReaderStylesheets.ua().replace(ReaderStylesheets.LINK_COLOR_TOKEN, "#0000ff")

    private fun styles(root: MarkupElement, author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(uaSheet()), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun cls(styleMap: Map<MarkupElement, ComputedStyle>) =
        BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleMap[el]?.displayBlock == true }

    private fun hid(styleMap: Map<MarkupElement, ComputedStyle>) =
        HiddenCheck { styleMap[it]?.displayNone == true }

    /** 收一段叶文本的装饰 run。 */
    private fun runs(p: MarkupElement, author: String = ""): List<TextDecorationRun> {
        val sm = styles(p.parent!!, author)
        return NormalFlowLayout.leafTextDecorationRuns(p, sm, cls(sm), hid(sm))
    }

    @Test
    fun `link text is underlined via UA`() {
        val a = node("a", mapOf("href" to "#t"), listOf(text("表1")))
        val p = node("p", children = listOf(text("见"), a))
        node("body", children = listOf(p))
        val sm = styles(p.parent!!)
        assertEquals("见表1", NormalFlowLayout.leafText(p, sm, cls(sm), hid(sm)))
        val rs = runs(p)
        assertEquals(1, rs.size)
        assertEquals(1, rs[0].start)
        assertEquals(3, rs[0].endExclusive)
        assertEquals(setOf(DecorationLine.UNDERLINE), rs[0].paints.single().lines)
        // UA 的链接色就是装饰色（`text-decoration-color` 缺省 = currentColor = 声明元素的颜色）。
        assertEquals("#ff0000ff", rs[0].paints.single().colorHex)
    }

    @Test
    fun `underline propagates across nested inline`() {
        val u = node("u", children = listOf(text("ab"), node("span", children = listOf(text("cd"))), text("ef")))
        val p = node("p", children = listOf(text("x"), u, text("y")))
        node("body", children = listOf(p))
        val rs = runs(p)
        assertEquals(1, rs.size)
        assertEquals(1, rs[0].start)
        assertEquals(7, rs[0].endExclusive)
    }

    @Test
    fun `block underline covers the whole leaf`() {
        val p = node("p", mapOf("style" to "text-decoration: underline"), listOf(text("all")))
        node("body", children = listOf(p))
        val rs = runs(p)
        assertEquals(1, rs.size)
        assertEquals(0, rs[0].start)
        assertEquals(3, rs[0].endExclusive)
    }

    @Test
    fun `plain leaf has no runs`() {
        val p = node("p", children = listOf(text("plain")))
        node("body", children = listOf(p))
        assertTrue(runs(p).isEmpty())
    }

    // ── E3：三种线型归位 ────────────────────────────────────────────────────

    @Test
    fun `line-through is not drawn as underline`() {
        val p = node("p", children = listOf(text("旧价 "), node("s", children = listOf(text("38"))), text(" 新价")))
        node("body", children = listOf(p))
        val rs = runs(p)
        assertEquals(1, rs.size)
        // 只有 `<s>` 那两个字带装饰，且线型必须是删除线 —— 旧实现会把它并成下划线。
        assertEquals(3, rs[0].start)
        assertEquals(5, rs[0].endExclusive)
        assertEquals(setOf(DecorationLine.LINE_THROUGH), rs[0].paints.single().lines)
    }

    @Test
    fun `overline is its own line`() {
        val p = node("p", children = listOf(text("a"), node("span", mapOf("style" to "text-decoration: overline"), listOf(text("bc")))))
        node("body", children = listOf(p))
        val rs = runs(p)
        assertEquals(1, rs.size)
        assertEquals(1, rs[0].start)
        assertEquals(3, rs[0].endExclusive)
        assertEquals(setOf(DecorationLine.OVERLINE), rs[0].paints.single().lines)
    }

    @Test
    fun `all three lines can be declared together`() {
        val p = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: underline overline line-through"), listOf(text("xyz")))))
        node("body", children = listOf(p))
        val paint = runs(p).single().paints.single()
        assertEquals(setOf(DecorationLine.UNDERLINE, DecorationLine.OVERLINE, DecorationLine.LINE_THROUGH), paint.lines)
    }

    // ── 传播与叠加 ──────────────────────────────────────────────────────────

    @Test
    fun `descendant none cannot cancel ancestor decoration`() {
        val a = node("a", mapOf("href" to "#t"), listOf(text("前"), node("span", mapOf("style" to "text-decoration: none"), listOf(text("后")))))
        val p = node("p", children = listOf(a))
        node("body", children = listOf(p))
        val rs = runs(p)
        assertEquals(1, rs.size)
        assertEquals(0, rs[0].start)
        assertEquals(2, rs[0].endExclusive)
    }

    @Test
    fun `ancestor and own decorations stack`() {
        val a = node("a", mapOf("href" to "#t"), listOf(text("ab"), node("s", children = listOf(text("cd")))))
        val p = node("p", children = listOf(a))
        node("body", children = listOf(p))
        val rs = runs(p)
        assertEquals(2, rs.size)
        assertEquals(listOf(DecorationLine.UNDERLINE), rs[0].paints.map { it.lines.single() })
        // 叠加段：祖先的下划线 + 自己的删除线，两层都在，顺序为「祖先 → 自身」。
        assertEquals(
            listOf(setOf(DecorationLine.UNDERLINE), setOf(DecorationLine.LINE_THROUGH)),
            rs[1].paints.map { it.lines },
        )
        assertEquals(2, rs[1].start)
        assertEquals(4, rs[1].endExclusive)
    }

    @Test
    fun `decorated nested inline keeps outer run as separate segment`() {
        val p = node("p", children = listOf(
            text("x"),
            node("span", mapOf("style" to "text-decoration: underline"), listOf(text("ab"), node("em", children = listOf(text("cd"))), text("ef"))),
            text("y"),
        ))
        node("body", children = listOf(p))
        val rs = runs(p)
        assertEquals(1, rs.size)
        assertEquals(1, rs[0].start)
        assertEquals(7, rs[0].endExclusive)
    }

    // ── 外观取声明元素 ──────────────────────────────────────────────────────

    @Test
    fun `currentColor resolves to declaring element color not leaf color`() {
        // `p` 声明红色，`span.deco` 声明下划线（颜色走 currentColor）⇒ 红；
        // 内层 `em` 改成绿色只影响字形，**不影响**声明那层的装饰色。
        val p = node("p", children = listOf(
            node("span", mapOf("class" to "deco"), listOf(text("ab"), node("em", mapOf("style" to "color: #00ff00"), listOf(text("cd"))))),
        ))
        node("body", children = listOf(p))
        val rs = runs(p, author = "p{color:#ff0000} .deco{text-decoration:underline}")
        assertEquals(1, rs.size)
        assertEquals("#ffff0000", rs[0].paints.single().colorHex)
    }

    @Test
    fun `explicit decoration color wins over currentColor`() {
        val p = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: underline; text-decoration-color: #123456"), listOf(text("xy")))))
        node("body", children = listOf(p))
        assertEquals("#ff123456", runs(p).single().paints.single().colorHex)
    }

    @Test
    fun `decoration style and thickness longhands parse`() {
        val p = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: underline wavy; text-decoration-thickness: 3px"), listOf(text("xy")))))
        node("body", children = listOf(p))
        val paint = runs(p).single().paints.single()
        assertEquals(DecorationStyle.WAVY, paint.style)
        assertEquals(3f, paint.thicknessPx!!, 1e-3f)
    }

    @Test
    fun `shorthand carries style and color and thickness`() {
        val p = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: dashed line-through red 2px"), listOf(text("xy")))))
        node("body", children = listOf(p))
        val paint = runs(p).single().paints.single()
        assertEquals(setOf(DecorationLine.LINE_THROUGH), paint.lines)
        assertEquals(DecorationStyle.DASHED, paint.style)
        assertEquals("#ffff0000", paint.colorHex)
        assertEquals(2f, paint.thicknessPx!!, 1e-3f)
        // `auto` / `from-font` 都落成 null（粗细由绘制侧按字体度量取）。
        val p2 = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: underline from-font"), listOf(text("xy")))))
        node("body", children = listOf(p2))
        assertNull(runs(p2).single().paints.single().thicknessPx)
    }

    @Test
    fun `skip-ink defaults to auto and none turns it off`() {
        val on = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: underline"), listOf(text("xy")))))
        node("body", children = listOf(on))
        assertTrue(runs(on).single().paints.single().skipInk)

        val off = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: underline; text-decoration-skip-ink: none"), listOf(text("xy")))))
        node("body", children = listOf(off))
        assertTrue(!runs(off).single().paints.single().skipInk)
    }

    @Test
    fun `blink keyword is ignored without breaking the declaration`() {
        val p = node("p", children = listOf(node("span", mapOf("style" to "text-decoration: underline blink"), listOf(text("xy")))))
        node("body", children = listOf(p))
        val paint = runs(p).single().paints.single()
        assertEquals(setOf(DecorationLine.UNDERLINE), paint.lines)
        assertEquals(DecorationStyle.SOLID, paint.style)
    }

    @Test
    fun `decoration geometry follows the declaring font size`() {
        val p = node("p", children = listOf(node("span", mapOf("style" to "font-size: 2em; text-decoration: underline"), listOf(text("xy")))))
        node("body", children = listOf(p))
        val paint = runs(p).single().paints.single()
        // 装饰线位置/粗细按声明元素的字号取（叶块 10px ⇒ 2em = 20px）。
        assertEquals(20f, paint.fontSizePx, 1e-3f)
    }

    @Test
    fun `uppercase u element is not a decoration carrier`() {
        // 只有 `u` / `s` 这类元素由 UA 表给装饰；普通 `span` 不该凭空有。
        val p = node("p", children = listOf(node("span", children = listOf(text("plain")))))
        node("body", children = listOf(p))
        assertTrue(runs(p).isEmpty())
    }
}
