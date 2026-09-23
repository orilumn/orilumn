package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 渲染层：[NormalFlowLayout.firstChildDescentTop] 口径锁 —— 容器边框顶到首叶边框顶的距离，
 * 轻量路径凭它把背景带补到容器真顶（`blockquote > h2` 的 margin-top 段不再露页底）。
 * 与 [NormalFlowLayout.consecutiveLeafAdvance] B 侧同门（edge-free 段折叠一次、遇 edge 结算）。
 */
class FirstChildDescentTest {

    private fun el(tag: String, style: String = "", vararg children: MarkupElement): MarkupElement {
        val kids = children.toList()
        val e = MarkupElement(tag, if (style.isBlank()) emptyMap() else mapOf("style" to style), kids)
        kids.forEach { it.parent = e }
        return e
    }

    private fun text(): MarkupElement = MarkupElement("#text", text = "x")

    private fun styles(root: MarkupElement): Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle> =
        StyleComputer(16f, LightCssParser().parse(""), emptyList()).compute(root)

    @Test
    fun `blockquote padding plus h2 top margin`() {
        // Rust 3.1 形：链上只有 h2 margin-top 2rem(32)；owner 自身 padding 由
        // backgroundBandExtent 去，这里不计（否则带子顶进 owner 自身 margin）。
        val h2 = el("h2", "margin-top: 2rem", text())
        val bq = el("blockquote", "padding: 0.5rem; background: #fff", h2)
        val root = el("body", "", bq)
        root.children.forEach { it.parent = root }
        h2.parent = bq
        val map = styles(root)
        assertEquals(32, NormalFlowLayout.firstChildDescentTop(bq, h2) { map[it]!! })
    }

    @Test
    fun `self owned is zero`() {
        val pre = el("pre", "background: #eee", text())
        val root = el("body", "", pre)
        root.children.forEach { it.parent = root }
        val map = styles(root)
        assertEquals(0, NormalFlowLayout.firstChildDescentTop(pre, pre) { map[it]!! })
    }

    @Test
    fun `non first leaf returns null`() {
        val h2 = el("h2", "", text())
        val p = el("p", "margin-top: 11px", text())
        val div = el("div", "padding: 9px", h2, p)
        val root = el("body", "", div)
        root.children.forEach { it.parent = root }
        h2.parent = div
        p.parent = div
        val map = styles(root)
        assertNull(NormalFlowLayout.firstChildDescentTop(div, p) { map[it]!! })
    }

    @Test
    fun `edge free chain collapses once`() {
        // 全无 edge：20 与 30 折叠取大 30（owner 自身 margin 不计在内）。
        val p = el("p", "margin-top: 30px", text())
        val div = el("div", "margin-top: 20px", p)
        val owner = el("section", "", div)
        val root = el("body", "", owner)
        root.children.forEach { it.parent = root }
        div.parent = owner
        p.parent = div
        val map = styles(root)
        assertEquals(30, NormalFlowLayout.firstChildDescentTop(owner, p) { map[it]!! })
    }

    @Test
    fun `edge full middle commits and restarts`() {
        // div margin 7（结算）+ div pad 5 + p margin 11 = 23（owner 自身 edge 不计）。
        val p = el("p", "margin-top: 11px", text())
        val div = el("div", "margin-top: 7px; padding-top: 5px", p)
        val owner = el("section", "padding-top: 9px", div)
        val root = el("body", "", owner)
        root.children.forEach { it.parent = root }
        div.parent = owner
        p.parent = div
        val map = styles(root)
        assertEquals(23, NormalFlowLayout.firstChildDescentTop(owner, p) { map[it]!! })
    }
}
