package orilumn.reader.engine.css

import orilumn.reader.engine.css.WhiteSpace
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * P0-B 验收：HTML4 表示型属性经 [Cascade.PRESENTATION_ATTRS] 以 low-tier(15) 进级联。
 * 已消费属性（align→text-align、bgcolor→background-color、cellpadding→padding、
 * border→border）断言 ComputedStyle；尚未消费的（valign→vertical-align、nowrap→white-space、
 * cellspacing→border-spacing，消费属 P1）断言 cascade 胜出层；并验证作者样式恒压该层。
 */
class PresentationAttrCascadeTest {

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    private fun compute(root: MarkupElement, ua: String = "", author: String = ""): Map<MarkupElement, ComputedStyle> =
        StyleComputer(16f, LightCssParser().parse(ua), listOf(LightCssParser().parse(author))).compute(root)

    /** 一棵 table > tr > td 的三代树，带好 parent 链（lazy/重两路共享）。 */
    private fun cellTree(tdAttrs: Map<String, String>, tableAttrs: Map<String, String> = emptyMap()): Triple<MarkupElement, MarkupElement, MarkupElement> {
        val td = node("td", tdAttrs)
        val tr = node("tr", children = listOf(td))
        td.parent = tr
        val table = node("table", tableAttrs, listOf(tr))
        tr.parent = table
        return Triple(table, tr, td)
    }

    @Test
    fun `td align 进级联为 text-align`() {
        val (table, _, td) = cellTree(mapOf("align" to "center"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(TextAlign.CENTER, out[td]?.textAlign)
    }

    @Test
    fun `td bgcolor 进级联为 background-color`() {
        val (table, tr, td) = cellTree(mapOf("bgcolor" to "#ffeeff"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals("#ffffeeff", out[td]?.backgroundColorHex)
        // 背景作用于整行（tr 也继承了该呈现，但 td/th 才是绘制载体；此处仅验 tr 未误吞）。
        assertNotNull(out[tr])
    }

    @Test
    fun `table cellpadding 进级联为四边 padding`() {
        val (table, _, td) = cellTree(emptyMap(), mapOf("cellpadding" to "6"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(6f, out[table]?.padding?.top!!, 1e-3f)
        assertEquals(6f, out[table]?.padding?.left!!, 1e-3f)
    }

    @Test
    fun `table border 进级联为四边 border 宽度`() {
        val (table, _, td) = cellTree(emptyMap(), mapOf("border" to "2"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(2f, out[table]?.border?.right!!, 1e-3f)
        assertEquals(2f, out[table]?.border?.bottom!!, 1e-3f)
    }

    @Test
    fun `尚未消费的映射进入级联胜出层`() {
        // valign/nowrap/cellspacing 消费点在 P1；此处验证映射已产生低层级声明。
        val (table, tr, td) = cellTree(
            mapOf("valign" to "middle", "nowrap" to "true"),
            mapOf("cellspacing" to "4"),
        )
        val body = node("body", children = listOf(table)); table.parent = body
        val cascade = Cascade(null, emptyList())
        val tdWinners = cascade.winningDeclarations(td, listOf(tr, table, body), emptyList())
        assertEquals("middle", tdWinners["vertical-align"])
        assertEquals("nowrap", tdWinners["white-space"])
        val tableWinners = cascade.winningDeclarations(table, listOf(body), emptyList())
        assertEquals("4px", tableWinners["border-spacing"])
    }

    @Test
    fun `作者样式恒压表示型属性低层级`() {
        // low-tier(15) < author normal(20)：书里写 CSS 时属性不再生效（浏览器语义）。
        val (table, _, td) = cellTree(mapOf("align" to "center", "bgcolor" to "#ffeeff"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body, author = "td { text-align: right; background-color: #000000 }")
        assertEquals(TextAlign.RIGHT, out[td]?.textAlign)
        assertEquals("#ff000000", out[td]?.backgroundColorHex)
    }

    @Test
    fun `非法 white-space 值丢弃走继承`() {
        // 本书 `pre code { white-space: nowarp }`（拼错）：非法声明丢弃，code 继承 pre 的值，
        // 而不是回落 NORMAL（否则多行代码挤成一行）。
        val code = node("code")
        val pre = node("pre", children = listOf(code)); code.parent = pre
        val body = node("body", children = listOf(pre)); pre.parent = body
        val out = compute(
            body,
            ua = "pre { white-space: pre-wrap; }",
            author = "pre code { white-space: nowarp; }",
        )
        assertEquals(WhiteSpace.PRE_WRAP, out[code]?.whiteSpace)
    }

    // ---- 预格式化语境的「不可折行」降级（见 StyleComputer.resolveWhiteSpace 的 KDoc）----

    /** `<pre><code>` 骨架，`code` 上挂一条作者声明 [decl]。 */
    private fun preCodeWith(decl: String): Pair<MarkupElement, MarkupElement> {
        val code = node("code")
        val pre = node("pre", children = listOf(code)); code.parent = pre
        val body = node("body", children = listOf(pre)); pre.parent = body
        return body to code
    }

    @Test
    fun `pre 子树内合法的 nowrap 降级为 pre-wrap`() {
        // 真书《Rust 程序设计语言》book_1790865097552.epub / OEBPS/Styles/stylesheet.css:144
        //     pre code { font-size: 0.8em; white-space: nowrap; }
        // `nowrap` 是**合法**值（不像上面那条拼错的 nowarp 会走继承兜底），所以声明真的生效。
        // 浏览器靠横向滚动条兜底 ⇒ 本项目（页宽固定的分页阅读器）不折行就是溢出被裁 = 丢内容。
        val (body, code) = preCodeWith("nowrap")
        val out = compute(body, ua = "pre { white-space: pre-wrap; }", author = "pre code { white-space: nowrap; }")
        assertEquals(WhiteSpace.PRE_WRAP, out[code]?.whiteSpace)
    }

    @Test
    fun `pre 子树内的 pre 降级为 pre-wrap`() {
        // 浏览器标准是 `pre`（长行不折、靠横向滚动）；本仓 ua.css 有意偏离成 pre-wrap。
        // 但 ua.css 是最低优先级、压不过书 —— 降级必须做在级联结果上，见 resolveWhiteSpace。
        val (body, code) = preCodeWith("pre")
        val out = compute(body, ua = "pre { white-space: pre-wrap; }", author = "pre { white-space: pre; }")
        assertEquals(WhiteSpace.PRE_WRAP, out[code]?.whiteSpace)
    }

    @Test
    fun `pre 元素自身被声明 nowrap 时也降级`() {
        // 判据是「元素在 pre 子树内」，与声明挂在哪一层无关。
        // 这里**故意不给 UA 的 pre-wrap**：否则 `pre` 自己的 UA 声明压过继承，降级路径根本走不到
        // （第一版就踩了这个坑 —— 锁是绿的，但把 resolveWhiteSpace 改坏它也不红，等于没锁）。
        // 只让 body 的 nowrap 继承下来，pre 与 code 都必须靠降级拿到 pre-wrap。
        val code = node("code")
        val pre = node("pre", children = listOf(code)); code.parent = pre
        val body = node("body", children = listOf(pre)); pre.parent = body
        val out = compute(body, ua = "", author = "body { white-space: nowrap; }")
        assertEquals(WhiteSpace.NOWRAP, out[body]?.whiteSpace) // 降级只限 pre 子树，body 自己不动
        assertEquals(WhiteSpace.PRE_WRAP, out[pre]?.whiteSpace)
        assertEquals(WhiteSpace.PRE_WRAP, out[code]?.whiteSpace)
    }

    @Test
    fun `pre 子树之外的 nowrap 保持浏览器语义`() {
        // 正文里的 nowrap 是作者的正当意图（短标签、表格单元），且照样装得下 ⇒ 一律不动。
        // 这条钉住「降级只限 pre 子树」，防止日后被扩成全局改写。
        val span = node("span")
        val p = node("p", children = listOf(span)); span.parent = p
        val body = node("body", children = listOf(p)); p.parent = body
        val out = compute(body, ua = "p { white-space: pre-wrap; }", author = "span { white-space: nowrap; }")
        assertEquals(WhiteSpace.NOWRAP, out[span]?.whiteSpace)
    }

    @Test
    fun `lazy resolve 与 compute 在降级上一致`() {
        // 两条级联通路（重算全章 compute / 只算祖先链的 resolve）必须给出同一个值，
        // 否则「先 resolve 出 pre-wrap、后 compute 出 nowrap」会让同一页代码块时折时不折。
        val (body, code) = preCodeWith("nowrap")
        val css = "pre code { white-space: nowrap; }"
        val eng = StyleComputer(16f, StyleSheet(emptyList()), listOf(LightCssParser().parse(css)))
        val full = eng.compute(body)
        val lazy = eng.resolve(code, HashMap())
        assertEquals(WhiteSpace.PRE_WRAP, full[code]?.whiteSpace)
        assertEquals(WhiteSpace.PRE_WRAP, lazy.whiteSpace)
    }

    @Test
    fun `width height 表示型属性照旧进级联`() {
        val (table, _, td) = cellTree(mapOf("width" to "40"))
        val body = node("body", children = listOf(table)); table.parent = body
        val out = compute(body)
        assertEquals(40f, out[td]?.widthPx!!, 1e-3f)
    }

    @Test
    fun `width 实务杂质容错进级联`() {
        // 本书 `th width="100px;"`（px 后缀＋引号内分号）：取前导数字，浏览器同式宽容。
        val (_, _, td) = cellTree(mapOf("width" to "100px;"))
        val body = node("body", children = listOf(td.parent!!.parent!!)); td.parent!!.parent!!.parent = body
        val out = compute(body)
        assertEquals(100f, out[td]?.widthPx!!, 1e-3f)
    }
}