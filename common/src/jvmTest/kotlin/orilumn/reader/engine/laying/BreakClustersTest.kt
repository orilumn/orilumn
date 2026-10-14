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
 * [BreakClusters] 单测：`break-before`/`break-after: avoid` 的**禁止切点**（class A 断点，只否那一条边）、
 * 强制边（ALWAYS）截断边链并报页首行、`break-inside: avoid` 成**原子区间**。
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

    /** `break-inside: avoid` 原子区间（行区间）。 */
    private fun atomic(vararg ps: MarkupElement): List<IntRange> =
        planOf(*ps).atomicRanges

    /** `break-*: avoid` 边的**禁止切点**（右侧叶首行 —— 页边界不得落在它之前）。 */
    private fun forbidden(vararg ps: MarkupElement): Set<Int> =
        planOf(*ps).forbiddenCutLines

    private fun planOf(vararg ps: MarkupElement): BreakClusters.Plan =
        BreakClusters.plan(BreakClusters.leavesOf(layoutBoxes(*ps)))

    private fun layoutBoxes(vararg ps: MarkupElement): List<LayoutBox> {
        val root = MarkupElement("body", emptyMap(), ps.toList())
        val styles = StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse(""))).compute(root)
        return BoxLayouter(10f, FixedWidthBreaker(3)).layoutBoxes(root, widthPx = 30, styleMap = styles).boxes
    }

    // ---- avoid 边：只否掉那一条 class A 断点（不把两侧块合成原子区间） ----

    @Test
    fun `无 avoid 边时无禁止切点也无原子区间`() {
        assertTrue(atomic(p(), p(), p()).isEmpty())
        assertTrue(forbidden(p(), p(), p()).isEmpty())
    }

    @Test
    fun `break-after avoid 只否掉后随边那一条切点`() {
        // p1 后不断 ⇒ 禁止切点 = 行 1（p2 首行）；p1、p2 各自内部仍可断 ⇒ 没有原子区间。
        assertEquals(setOf(1), forbidden(p("break-after: avoid"), p(), p()))
        assertTrue(atomic(p("break-after: avoid"), p(), p()).isEmpty())
    }

    @Test
    fun `break-before avoid 否掉该块首行`() {
        assertEquals(setOf(1), forbidden(p(), p("break-before: avoid"), p()))
    }

    @Test
    fun `legacy page-break-after avoid 同样给出禁止切点`() {
        assertEquals(setOf(1), forbidden(p("page-break-after: avoid"), p()))
    }

    @Test
    fun `链式 avoid 边给出多个禁止切点`() {
        // p1→p2、p2→p3 各否一条 ⇒ {1, 2}；块内仍可断（不是 [0,3) 的原子区间）。
        assertEquals(setOf(1, 2), forbidden(p("break-after: avoid"), p("break-after: avoid"), p()))
        assertTrue(atomic(p("break-after: avoid"), p("break-after: avoid"), p()).isEmpty())
    }

    @Test
    fun `强制边截断边链`() {
        // p1|p2 强制断页 ⇒ 不生成 p2 首行的禁止切点；p2→p3 avoid ⇒ 只剩禁止切点 2。
        assertEquals(setOf(2), forbidden(p("break-after: always"), p("break-after: avoid"), p()))
    }

    @Test
    fun `break-before always 也截断`() {
        // p2 前强制断页 ⇒ p1|p2 无禁止切点；p3→p4 avoid ⇒ 只剩禁止切点 3。
        assertEquals(setOf(3), forbidden(p("break-after: avoid"), p("break-before: always"), p("break-after: avoid"), p()))
    }

    // ---- break-inside: avoid：整棵容器成原子区间 ----

    @Test
    fun `break-inside avoid 的容器整棵成原子区间`() {
        // <div style="break-inside:avoid"><p>a</p><p>a</p></div> + 独立 <p> ⇒ 原子区间行 0..2。
        val div = MarkupElement("div", mapOf("style" to "break-inside: avoid"), listOf(p(), p()))
        assertEquals(listOf(0 until 2), atomic(div, p()))
        assertTrue(forbidden(div, p()).isEmpty())
    }

    @Test
    fun `原子容器可被 avoid 边串上邻块_两者正交`() {
        // p1(avoid-after) 与 <div>(break-inside:avoid)[p,p] 相邻：
        //  - avoid 边只给出禁止切点 {1}（p1|div 首叶之间那一条）；
        //  - break-inside 给出原子区间 [1,3)。
        // 两者**正交**（一个是块间那一条边，一个是块内整段），互不抵消。
        val div = MarkupElement("div", mapOf("style" to "break-inside: avoid"), listOf(p(), p()))
        assertEquals(listOf(1 until 3), atomic(p("break-after: avoid"), div))
        assertEquals(setOf(1), forbidden(p("break-after: avoid"), div))
    }

    @Test
    fun `跨容器边界的 avoid 边在扁平叶序上给出禁止切点`() {
        // 判据是**扁平块叶序列**（与 P3 预算同源），不做父容器边界检查：
        // p(after avoid) 在 <div> 外，p 在 <div> 内 ⇒ 两叶相邻 ⇒ 禁止切点 {1}。
        // 这条差异（相对 CSS 的同父容器判定）是**有意为之**并被此处钉住，见 BreakClusters 的类注释。
        val div = MarkupElement("div", emptyMap(), listOf(p()))
        assertEquals(setOf(1), forbidden(p("break-after: avoid"), div))
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
    fun `chainRange 从链中任意块都能取到整链块域`() {
        // p1→p2 avoid、p2→p3 avoid ⇒ 三块一链；从中间块 1 出发应取到 0..2。
        val (before, after) = edgeRules(p("break-after: avoid"), p("break-after: avoid"), p())
        assertEquals(0..2, BreakClusters.chainRange(1, 0, 3, before, after))
    }

    @Test
    fun `chainRange 不越过调用方给的窗口下界`() {
        // 同一条链，但窗口下界是 1（页首就是块 1）⇒ 不得把块 0 圈回来：窗口变大不会改页边界，
        // 却会让 `plan` 看到「另一条链的接口」，故下界必须由调用方（页首）兜住。
        val (before, after) = edgeRules(p("break-after: avoid"), p("break-after: avoid"), p())
        assertEquals(1..2, BreakClusters.chainRange(1, 1, 3, before, after))
    }

    @Test
    fun `chainRange 尊重窗口上界`() {
        // 三块一链，但窗口块域只到 2（`[0,2)`）⇒ 前向只补到 1，不越界取块 2。
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
        // p1 后强制断页 ⇒ 行 1（p2 首行）是页首。
        val got = planOf(p("break-after: always"), p(), p())
        assertTrue(got.atomicRanges.isEmpty())
        assertTrue(got.forbiddenCutLines.isEmpty())
        assertEquals(setOf(1), got.forcedBeforeLines)
    }

    @Test
    fun `break-before always 报出该块首行为强制页首`() {
        val got = planOf(p(), p("break-before: always"), p())
        assertTrue(got.atomicRanges.isEmpty())
        assertEquals(setOf(1), got.forcedBeforeLines)
    }

    @Test
    fun `avoid 边不产生强制页首`() {
        val got = planOf(p("break-after: avoid"), p())
        assertEquals(setOf(1), got.forbiddenCutLines)
        assertTrue(got.atomicRanges.isEmpty())
        assertTrue(got.forcedBeforeLines.isEmpty())
    }

    @Test
    fun `强制边截断边链且仍报页首`() {
        // p1 强制断页；p2→p3 avoid ⇒ 禁止切点只剩行 2，页首行 = 1。
        val got = planOf(p("break-after: always"), p("break-after: avoid"), p())
        assertEquals(setOf(2), got.forbiddenCutLines)
        assertTrue(got.atomicRanges.isEmpty())
        assertEquals(setOf(1), got.forcedBeforeLines)
    }

    @Test
    fun `强制行不落在 break-inside 原子区间的内部`() {
        // 强制边在 joins 里返回 false ⇒ 边链不会跨过它；而 break-inside 原子区间来自属主，
        // Paginator 用「先按强制行切段、段内再躲」保证强制行必为页首（段尾是硬边界）。
        val div = MarkupElement(
            "div",
            mapOf("style" to "break-inside: avoid"),
            listOf(p(), p("break-before: always")),
        )
        val got = planOf(div)
        assertEquals(listOf(0 until 2), got.atomicRanges)
        assertEquals(setOf(1), got.forcedBeforeLines)
    }
}
