package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BreakClusters] 单测：`break-before`/`break-after` 的 avoid 边成簇、强制边（ALWAYS）截断、
 * `break-inside: avoid` 成原子簇。
 *
 * 每个 `<p>` 放 1 个字符、断行器 3 字/行 ⇒ **每块恰好 1 行**，于是行区间可以按块序直读：
 * 第 k 块 = 行 [k, k+1)。
 */
class BreakClustersTest {

    private fun text(t: String) = MarkupElement("#text", text = t)

    private fun p(style: String = ""): MarkupElement {
        val attrs = if (style.isBlank()) emptyMap() else mapOf("style" to style)
        return MarkupElement("p", attrs, listOf(text("a")))
    }

    private class FixedWidthBreaker(private val charsPerLine: Int) : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean,
        ): List<BrokenLine> {
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

    private fun clusters(vararg ps: MarkupElement): List<IntRange> =
        BreakClusters.of(BreakClusters.leavesOf(layoutBoxes(*ps)))

    /** P4：[plan] 同时给出簇与**强制断页行**（强制边右侧的页首行）。 */
    private fun plan(vararg ps: MarkupElement): BreakClusters.Plan =
        BreakClusters.plan(BreakClusters.leavesOf(layoutBoxes(*ps)))

    private fun layoutBoxes(vararg ps: MarkupElement): List<LayoutBox> {
        val root = MarkupElement("body", emptyMap(), ps.toList())
        val styles = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        return BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styles).boxes
    }

    @Test
    fun `无 avoid 边时无簇`() {
        assertTrue(clusters(p(), p(), p()).isEmpty())
    }

    @Test
    fun `break-after avoid 把后随块并入同簇`() {
        // p1 后不断 ⇒ p1+p2 一簇（行 0..2），p3 独立。
        assertEquals(listOf(0 until 2), clusters(p("break-after: avoid"), p(), p()))
    }

    @Test
    fun `break-before avoid 把前导块并入同簇`() {
        // p2 前不断 ⇒ p1+p2 一簇。
        assertEquals(listOf(0 until 2), clusters(p(), p("break-before: avoid"), p()))
    }

    @Test
    fun `legacy page-break-after avoid 同样成簇`() {
        assertEquals(listOf(0 until 2), clusters(p("page-break-after: avoid"), p()))
    }

    @Test
    fun `链式 avoid 边串成整段`() {
        // p1→p2 avoid，p2→p3 avoid ⇒ 三块一簇。
        assertEquals(listOf(0 until 3), clusters(p("break-after: avoid"), p("break-after: avoid"), p()))
    }

    @Test
    fun `强制边截断簇`() {
        // p1|p2 强制断页 ⇒ 簇不跨过 p1；p2→p3 avoid ⇒ 只剩 p2+p3（行 1..3）。
        assertEquals(listOf(1 until 3), clusters(p("break-after: always"), p("break-after: avoid"), p()))
    }

    @Test
    fun `break-before always 也截断`() {
        // p2 前强制断页 ⇒ p1|p2 不成簇；p3 的 avoid-after 把 p4 串上 ⇒ 只剩 p3+p4（行 2..4）。
        assertEquals(listOf(2 until 4), clusters(p("break-after: avoid"), p("break-before: always"), p("break-after: avoid"), p()))
    }

    @Test
    fun `break-inside avoid 的容器整棵成原子簇`() {
        // <div style="break-inside:avoid"><p>a</p><p>a</p></div> + 独立 <p> ⇒ 原子簇行 0..2。
        val div = MarkupElement("div", mapOf("style" to "break-inside: avoid"), listOf(p(), p()))
        val root = MarkupElement("body", emptyMap(), listOf(div, p()))
        assertEquals(listOf(0 until 2), clusters(div, p()))
    }

    @Test
    fun `原子容器可被 avoid 边串上邻块`() {
        // p1(avoid-after) + <div>[p,p] ⇒ 边簇 [0,2)（p1 与 div 首叶相邻）∪ div 原子簇 [1,3)。
        // 两个区间都保留（**嵌套区间是有意的**：分页器逐条独立判「整簇装得下才躲」）。
        val div = MarkupElement("div", mapOf("style" to "break-inside: avoid"), listOf(p(), p()))
        assertEquals(setOf(0 until 2, 1 until 3), clusters(p("break-after: avoid"), div).toSet())
    }

    @Test
    fun `跨容器边界的 avoid 边在扁平叶序上成簇`() {
        // 判据是**扁平块叶序列**（与 P3 预算同源），不做父容器边界检查：
        // p(after avoid) 在 <div> 外，p 在 <div> 内 ⇒ 两叶相邻 ⇒ 成簇 [0,2)。
        // 这条差异（相对 CSS 的同父容器判定）是**有意为之**并被此处钉住，见 BreakClusters 的类注释。
        val div = MarkupElement("div", emptyMap(), listOf(p()))
        assertEquals(listOf(0 until 2), clusters(p("break-after: avoid"), div))
    }

    // ---- P4：窗口取整 chainRange（只取窗口，不做页边界决定） ----

    /** 块级 `before`/`after` 取值的 lambda（`chainRange` 的入参）。 */
    private fun edgeRules(vararg ps: MarkupElement): Pair<(Int) -> BreakRule, (Int) -> BreakRule> {
        val leaves = BreakClusters.leavesOf(layoutBoxes(*ps))
        val before: (Int) -> BreakRule = { i -> leaves[i].before }
        val after: (Int) -> BreakRule = { i -> leaves[i].after }
        return before to after
    }

    @Test
    fun `chainRange 从簇中任意块都能取到整簇块域`() {
        // p1→p2 avoid、p2→p3 avoid ⇒ 三块一簇；从中间块 1 出发应取到 0..2。
        val (before, after) = edgeRules(p("break-after: avoid"), p("break-after: avoid"), p())
        assertEquals(0..2, BreakClusters.chainRange(1, 0, 3, before, after))
    }

    @Test
    fun `chainRange 不越过调用方给的窗口下界`() {
        // 同一簇，但窗口下界是 1（页首就是块 1）⇒ 不得把块 0 圈回来：窗口变大不会改页边界，
        // 却会让 `plan` 看到「另一个簇」，故下界必须由调用方（页首）兜住。
        val (before, after) = edgeRules(p("break-after: avoid"), p("break-after: avoid"), p())
        assertEquals(1..2, BreakClusters.chainRange(1, 1, 3, before, after))
    }

    @Test
    fun `chainRange 尊重窗口上界`() {
        // 三块一簇，但窗口块域只到 2（`[0,2)`）⇒ 前向只补到 1，不越界取块 2。
        val (before, after) = edgeRules(p("break-after: avoid"), p("break-after: avoid"), p())
        assertEquals(0..1, BreakClusters.chainRange(1, 0, 2, before, after))
    }

    @Test
    fun `chainRange 对孤立块返回自身`() {
        val (before, after) = edgeRules(p(), p(), p())
        assertEquals(1..1, BreakClusters.chainRange(1, 0, 3, before, after))
    }

    // ---- P4：强制断页行（plan.forcedBeforeLines） ----

    @Test
    fun `break-after always 报出下一块的首行为强制页首`() {
        // p1 后强制断页 ⇒ 行 1（p2 首行）是页首；p1 本身不成簇。
        val got = plan(p("break-after: always"), p(), p())
        assertTrue(got.clusters.isEmpty())
        assertEquals(setOf(1), got.forcedBeforeLines)
    }

    @Test
    fun `break-before always 报出该块首行为强制页首`() {
        val got = plan(p(), p("break-before: always"), p())
        assertTrue(got.clusters.isEmpty())
        assertEquals(setOf(1), got.forcedBeforeLines)
    }

    @Test
    fun `avoid 边不产生强制页首`() {
        val got = plan(p("break-after: avoid"), p())
        assertEquals(listOf(0 until 2), got.clusters)
        assertTrue(got.forcedBeforeLines.isEmpty())
    }

    @Test
    fun `强制边截断的簇不再与右侧成簇且仍报页首`() {
        // p1 强制断页；p2→p3 avoid ⇒ 簇只剩 p2+p3（行 1..3），页首行 = 1。
        val got = plan(p("break-after: always"), p("break-after: avoid"), p())
        assertEquals(listOf(1 until 3), got.clusters)
        assertEquals(setOf(1), got.forcedBeforeLines)
    }

    @Test
    fun `强制行与簇不相交`() {
        // 每个强制边右侧的页首行都不落在任何簇区间内部（段尾是硬边界）。
        val got = plan(p("break-after: avoid"), p("break-after: always"), p("break-after: avoid"), p())
        for (f in got.forcedBeforeLines) {
            for (c in got.clusters) assertTrue("forced $f inside cluster $c", f !in c.first + 1..c.last)
        }
        assertEquals(setOf(2), got.forcedBeforeLines)
    }
}
