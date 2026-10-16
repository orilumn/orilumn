package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v4 页首缺口口径锁（[NormalFlowLayout.pageTopLead] / [NormalFlowLayout.pageStartOwner]）。
 *
 * ## 口径
 *
 * 页首叶沿「首有效子」链上溯到**最外层可见盒 X**（背景色/背景图/边框/盒阴影 —— 与绘制侧
 * `ComputedStyle.hasPaintedSlab` 同判据），缺口 = `X 自身 top 边 + X 内容顶→叶边框顶 + 叶自身
 * top 边`。X 边框盒**以下**的一切（X 的 padding、中间容器的边距、**首子自己的** `margin-top`）
 * 留在本页；X **之上**的折叠 `margin-top` 并入页边距。
 *
 * 链上无可见盒（纯文本块直挂 `<body>`）⇒ 退回 v3：只留叶自身 top 边，段前距整段并入页边距。
 * 期望值全部来自 fixture 样式本身，不来自实现式（折叠取大是 CSS 规则）。
 */
class PageTopLeadTest {

    private fun el(tag: String, style: String = "", vararg children: MarkupElement): MarkupElement {
        val kids = children.toList()
        val e = MarkupElement(tag, if (style.isBlank()) emptyMap() else mapOf("style" to style), kids)
        kids.forEach { it.parent = e }
        return e
    }

    private fun text(): MarkupElement = MarkupElement("#text", text = "x")

    /** `root` 为最外层；返回「元素 → 计算样式」查询（内联样式走同一台 `StyleComputer`）。 */
    private fun styles(root: MarkupElement): (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle {
        val map = StyleComputer(16f, LightCssParser().parse(""), emptyList()).compute(root)
        return { map.getValue(it) }
    }

    /** 真书形：`div.fm-sidebar-block{background:#EEE;padding:10px 20px}` 里 `p.fm-sidebar-title{margin:1em 0;padding-top:15px}`。 */
    @Test
    fun `painted container keeps its first child margin`() {
        val title = el("p", "margin: 1em 0; padding-top: 15px", text())
        val sidebar = el("div", "background: #eee; margin: 1em 0; padding: 10px 20px", title)
        val body = el("body", "", sidebar)
        // 10（容器 padding-top） + 16（1em 首子 margin-top，**保留**） + 15（首子自身 padding-top）
        assertEquals(41, NormalFlowLayout.pageTopLead(title, styles(body)))
    }

    /** 同一结构但容器**不可见**（只有 padding，无背景/边框/阴影）：整段折叠 margin 并入页边距（v3）。 */
    @Test
    fun `invisible padded container merges the child margin`() {
        val p = el("p", "margin: 1em 0; padding-top: 15px", text())
        val div = el("div", "padding: 10px 20px", p)
        val body = el("body", "", div)
        assertEquals(15, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 纯文本块直挂 `<body>`：自身段前距并入页边距，只留自身 `padding-top`（v3，回归锁）。 */
    @Test
    fun `plain body child merges its own margin`() {
        val p = el("p", "margin: 16px 0; padding-top: 15px", text())
        val body = el("body", "", p)
        assertEquals(15, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 只有边框也算可见盒（`hasPaintedSlab` 含 `hasBorderEdges`）：X 边框顶以下全留。 */
    @Test
    fun `border only container is a visible edge`() {
        val p = el("p", "margin: 16px 0", text())
        val box = el("div", "border-top: 5px solid #000; padding-top: 10px", p)
        val body = el("body", "", box)
        assertEquals(5 + 10 + 16, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 背景图（无背景色）同样算可见盒。 */
    @Test
    fun `background image container is a visible edge`() {
        val p = el("p", "margin: 8px 0", text())
        val div = el("div", "background-image: url(bg.png); padding-top: 4px", p)
        val body = el("body", "", div)
        assertEquals(4 + 8, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 盒阴影也算可见盒（绘制侧会为它出矩形）。 */
    @Test
    fun `box shadow only container is a visible edge`() {
        val p = el("p", "margin: 5px 0", text())
        val div = el("div", "box-shadow: 0 1px 2px #000; padding-top: 3px", p)
        val body = el("body", "", div)
        assertEquals(3 + 5, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 链上多个可见盒：取**最外层**（页面标识范围最大的那个），中间层降级为内部间距。 */
    @Test
    fun `outermost painted ancestor wins`() {
        val p = el("p", "margin: 11px 0; padding-top: 2px", text())
        val inner = el("div", "background: #ddd; margin-top: 33px; padding-top: 3px", p)
        val outer = el("section", "background: #eee; padding-top: 7px", inner)
        val body = el("body", "", outer)
        // 7（外层 padding-top） + 33（内层 margin-top，保留） + 3（内层 padding-top） + 11（首子 margin-top） + 2（首子 padding-top）
        assertEquals(7 + 33 + 3 + 11 + 2, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 不可见的包裹层在可见盒**之上**：并入页边距（它的 top 边在栅格上沿被吞掉看不出差别）。 */
    @Test
    fun `invisible wrapper above the visible box is merged`() {
        val p = el("p", "margin: 16px 0; padding-top: 3px", text())
        val box = el("div", "background: #eee; padding-top: 10px", p)
        val section = el("section", "padding-top: 9px", box)
        val body = el("body", "", section)
        assertEquals(10 + 16 + 3, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 链在「首有效子」处断开（本叶不是外沿盒的首子）：没有外沿可言，退回叶自身 top 边（v3）。 */
    @Test
    fun `non first child falls back to own edges`() {
        val h2 = el("h2", "", text())
        val p = el("p", "margin: 16px 0; padding-top: 4px", text())
        val div = el("div", "background: #eee; padding-top: 10px", h2, p)
        val body = el("body", "", div)
        assertEquals(4, NormalFlowLayout.pageTopLead(p, styles(body)))
    }

    /** 匿名文本叶（`#text`）：自身盒属性恒 0，可见容器仍是外沿盒。 */
    @Test
    fun `anonymous text leaf inside painted container`() {
        val t = text()
        val div = el("div", "background: #eee; padding-top: 10px", t)
        val body = el("body", "", div)
        assertEquals(10, NormalFlowLayout.pageTopLead(t, styles(body)))
    }

    /** 无可见祖先（到根都没有）：退回叶自身 top 边，恒等于其它路径的 `ownTopEdges`。 */
    @Test
    fun `no visible ancestor falls back to own edges`() {
        val p = el("p", "margin: 16px 0; padding-top: 4px", text())
        val body = el("body", "", p)
        assertEquals(4, NormalFlowLayout.pageTopLead(p, styles(body)))
    }
}
