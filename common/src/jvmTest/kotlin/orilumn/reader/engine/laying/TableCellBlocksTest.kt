package orilumn.reader.engine.laying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement

/**
 * **Q15 正向锁**：表格单元格内**块级**子节点各自成块、纵向堆叠。
 *
 * ## 缺陷本体
 *
 * 修复前单元格只挂**一个** shape，格内文本由 `absorbStyled(cell.el, …)` 一把吸收；而该吸收按
 * `styledSegments` 的规则**跳过块级子节点**（`StyledText.kt` 的 `isBlock(c) -> Unit`）
 * ⇒ `<td><p>…</p></td>` 整格文本为空、塑形出零行、绘制侧整格丢弃：格撑住空间、边框照画，
 * **一个字都没有**。实测 4 本书 2558/4309 = 59.4% 的单元格如此。
 *
 * 本锁走**生产单源** [NormalFlowLayout.cellBlocks]（重路径 `buildTableRows` 与轻路径
 * `BoxChapterLayouter.cellBlockPlan` 共调），不手搓块。
 */
class TableCellBlocksTest {

    private fun setup(html: String, css: String = ""): Triple<MarkupElement, Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>, BlockClassify> {
        val root = HtmlTreeConverter().convert("<html><body>$html</body></html>")!!
        val engine = StyleComputer(16f, LightCssParser().parse(css), listOf(LightCssParser().parse(css)))
        val styles = engine.compute(root)
        // 与重路径 `BoxLayouter.layoutBoxes` 同口径：display-aware 块判据 ＋ `display:none` 隐藏门。
        val classify = BlockClassify { NormalFlowLayout.defaultBlock(it) || styles[it]?.displayBlock == true }
        return Triple(root, styles, classify)
    }

    private fun firstCell(root: MarkupElement): MarkupElement {
        fun find(el: MarkupElement): MarkupElement? {
            if (el.tag == "td" || el.tag == "th") return el
            for (c in el.children) find(c)?.let { return it }
            return null
        }
        return find(root)!!
    }

    private fun blocksOf(html: String, css: String = ""): List<TableCellBlock> {
        val (root, styles, classify) = setup(html, css)
        return NormalFlowLayout.cellBlocks(firstCell(root), styles, classify, { styles[it]?.displayNone == true })
    }

    @Test
    fun `block child becomes its own block and is not skipped`() {
        // 本条是 Q15 的正面：修复前 `absorbStyled(td)` 对这一格返回 ""（blocks 里没有 "内容"）。
        val bs = blocksOf("<table><tr><td><p>内容</p></td></tr></table>")
        assertEquals(1, bs.size)
        assertEquals("p", bs[0].el.tag)
        assertEquals("内容", bs[0].text)
    }

    @Test
    fun `pure inline cell is one anonymous block`() {
        // 与「包一层 `<p>`」同构（一个块）⇒ 旧单 shape 路径的行为保持不变。
        // 匿名块的 `el` 是**容器**（不是合成 `#text`）：塑形侧要从真实子树重抽文本，
        // `<br>` 硬换行才不会被 `white-space:normal` 折成空格。区间满 ⇒ 无兄弟要排。
        val bs = blocksOf("<table><tr><td>直写文字</td></tr></table>")
        assertEquals(1, bs.size)
        assertEquals("td", bs[0].el.tag)
        assertEquals("直写文字", bs[0].text)
        assertTrue("纯内联格不该有 run 外兄弟", bs[0].outsideSiblings.isEmpty())
    }

    @Test
    fun `mixed content splits into before-block-after`() {
        val bs = blocksOf("<table><tr><td>前段<p>中段</p>尾段</td></tr></table>")
        assertEquals(listOf("前段", "中段", "尾段"), bs.map { it.text })
        // 两个匿名块的 `el` 同为容器（各占容器 children 的一段），命名块 `el` 是自己。
        assertEquals(listOf("td", "p", "td"), bs.map { it.el.tag })
        // Q15 塑形侧复原同一段文本的唯一凭据：排掉 run 外的原始兄弟（含**文本**节点 —— 靠
        // `styledSegments` 里排在 `c.isText` 之前的 `isBlock` 才能排掉，见 StyledText.kt）。
        assertEquals(listOf("尾段"), bs[0].outsideSiblings.filter { it.isText }.map { it.text })
        assertTrue(bs[0].outsideSiblings.contains(bs[1].el))
        assertEquals(2, bs[0].outsideSiblings.size)
        assertTrue(bs[1].outsideSiblings.isEmpty())
        assertEquals(2, bs[2].outsideSiblings.size)
        assertTrue(bs[2].outsideSiblings.contains(bs[1].el))
    }

    @Test
    fun `hard break inside cell survives as newline`() {
        // `<br>` 必须留在块文本里（塑形侧按同一段重抽 ⇒ 硬换行不被折成空格；实测 Kindle 跨行格
        // 5 行掉成 3 行就是这么来的）。
        val bs = blocksOf("<table><tr><td>一<br>二</td></tr></table>")
        assertEquals(1, bs.size)
        assertEquals("一\n二", bs[0].text)
    }

    @Test
    fun `whitespace only anonymous run is dropped`() {
        // 纯空白 run 不成块（与 `flowChildren`/`styledCharAdvance.flush()` 的 `isNotBlank` 门同式）。
        val bs = blocksOf("<table><tr><td>  <p>中</p>  </td></tr></table>")
        assertEquals(listOf("中"), bs.map { it.text })
    }

    @Test
    fun `nested container flattens to inner blocks and accumulates horizontal inset`() {
        // `<td><div style="padding:8px"><p>a</p><p>b</p></div></td>`：div 自身不产块，
        // 两个 p 各自成块，横向 inset 累加 8（左）＋8（右）。
        val bs = blocksOf(
            "<table><tr><td><div style=\"padding:8px\"><p>a</p><p>b</p></div></td></tr></table>",
            "p{margin:0}",
        )
        assertEquals(listOf("a", "b"), bs.map { it.text })
        assertEquals(listOf(16, 16), bs.map { it.edgeH })
    }

    @Test
    fun `sibling block margins collapse by max`() {
        // CSS 2.1 §8.3.1 相邻块外边距取大；格建立独立块格式化上下文（§17.5.2.1）⇒ 首块顶外边距
        // **不逃出格**（照算 10），第二块间隙 = 前块底 10 ⊕ 本块顶 30 ⇒ 30。
        val bs = blocksOf(
            "<table><tr><td><p>a</p><p>b</p></td></tr></table>",
            "p{margin:10px} p+p{margin-top:30px}",
        )
        assertEquals(2, bs.size)
        assertEquals(10, bs[0].gapBefore)
        assertEquals(30, bs[1].gapBefore)
    }

    @Test
    fun `block own border padding goes into height not next gap`() {
        // 块自身 border+padding 进 `edgeV`（计入自身 `height`），下一块间隙仍是 0 —— 若把它
        // 折进下一块的 `gapBefore`，`stackTableCellBlocks` 会在前块高度之外再加一次。
        val bs = blocksOf(
            "<table><tr><td><p style=\"padding:6px\">a</p><p>b</p></td></tr></table>",
            "p{margin:0}",
        )
        assertEquals(2, bs.size)
        assertEquals(12, bs[0].edgeV)
        assertEquals(0, bs[1].gapBefore)
        bs[0].height = 32 // 排版侧：行高和 ＋ edgeV
        bs[1].height = 20
        stackTableCellBlocks(bs)
        assertEquals("第二块紧跟第一块高度之后", 32, bs[1].top)
    }

    @Test
    fun `stackTableCellBlocks places blocks by static gap`() {
        // p{margin:10px}：首块 gap 10；第二块 gap = max(10,10) = 10。
        val bs = blocksOf("<table><tr><td><p>a</p><p>b</p></td></tr></table>", "p{margin:10px}")
        for (b in bs) b.height = 20
        val h = stackTableCellBlocks(bs)
        assertEquals(10, bs[0].top)
        assertEquals(40, bs[1].top)
        assertEquals(60, h)
    }

    @Test
    fun `leaf container block keeps its own edges and needs no tail`() {
        // `<td><div style="padding:8px">文字</div></td>`：div 内全是纯文本 ⇒ div 自己就是一个叶块
        // （不递归），四个边都归它 ⇒ 无「尾部无处挂」的问题。
        val bs = blocksOf("<table><tr><td><div style=\"padding:8px\">文字</div></td></tr></table>")
        assertEquals(1, bs.size)
        assertEquals("div", bs[0].el.tag)
        assertEquals(0, bs[0].gapBefore)
        assertEquals(16, bs[0].edgeV) // 上下各 8
        assertEquals(16, bs[0].edgeH) // 左右各 8（影响断行宽）
    }

    @Test
    fun `trailing container bottom padding lands on last block`() {
        // `<td><div style="padding:8px"><p>a</p></div></td>`：div 内有块子节点 ⇒ 走递归，p 是叶块；
        // div 的上内边距进 p 的 `gapBefore`，**下**内边距没有后继块可挂 ⇒ 并入末块 `edgeV`。
        val bs = blocksOf(
            "<table><tr><td><div style=\"padding:8px\"><p>a</p></div></td></tr></table>",
            "p{margin:0}",
        )
        assertEquals(1, bs.size)
        assertEquals(8, bs[0].gapBefore)
        assertEquals(8, bs[0].edgeV)
        assertEquals(16, bs[0].edgeH)
    }

    @Test
    fun `inline img stays inside its anonymous run`() {
        // 行内 `<img>` **不**独立成块（`flowChildren` 也不拆）：它是 run 里的一个 U+FFFC 占位，
        // 只按 §10.8 抬所在行高。拆块会让塑形侧对 `img` 根早退、塑出零行。
        val bs = blocksOf("<table><tr><td>字<img src=\"a.png\"/>尾</td></tr></table>")
        assertEquals(listOf("字￼尾"), bs.map { it.text })
        // 配对扫描根是**容器**：匿名块自身没有子节点，`<td>字<img></td>` 的图要能配到。
        assertEquals("td", bs[0].el.tag)
        assertEquals("td", bs[0].imageRoot.tag)
        assertTrue(bs[0].outsideSiblings.isEmpty())
    }

    @Test
    fun `per block style drives face so nested p keeps its own font`() {
        // 块各自带自己的计算样式：`<td><p style="font-size:32px">` 的字号必须是 32，不是格的 16。
        val (root, styles, classify) = setup("<table><tr><td><p style=\"font-size:32px\">大</p></td></tr></table>")
        val cell = firstCell(root)
        val bs = NormalFlowLayout.cellBlocks(cell, styles, classify, { styles[it]?.displayNone == true })
        assertEquals(1, bs.size)
        assertEquals("p", bs[0].el.tag)
        assertEquals(32f, bs[0].style.fontSizePx, 1e-3f)
        assertEquals(16f, styles[cell]!!.fontSizePx, 1e-3f)
    }

    @Test
    fun `leaf block keeps its own tag so pre in cell stays monospace`() {
        // 回归：若把 `<pre>` 当中间容器递归下去，块 `el` 会退成 `#text` ⇒ 绘制侧 tag 丢 `pre`
        // ⇒ `DrawLine.monospace` 失效（等宽字变比例字）。
        val bs = blocksOf("<table><tr><td><pre>code</pre></td></tr></table>")
        assertEquals(1, bs.size)
        assertEquals("pre", bs[0].el.tag)
    }

    @Test
    fun `display none block child is not hidden from the block list`() {
        // `display:none` 的块级子节点不产块（hidden 门与全文一致），但不产生空块。
        val bs = blocksOf("<table><tr><td><p style=\"display:none\">藏</p><p>露</p></td></tr></table>")
        assertEquals(listOf("露"), bs.map { it.text })
    }

    @Test
    fun `empty cell yields no blocks`() {
        assertTrue(blocksOf("<table><tr><td></td></tr></table>").isEmpty())
    }
}
