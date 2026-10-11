package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule

/**
 * 断页约束的**单源纯函数**：给出「不许被页边界切开」的全局行区间（簇）与「必须断页」的行（强制）。
 *
 * ## 口径（CSS Fragmentation §4.4 unforced break）
 *
 * 页边界的候选位置是**块级相邻叶之间**。相邻叶 A(上)/B(下) 之间的边：
 *
 * - **强制边**（`A.after == ALWAYS || B.before == ALWAYS`）⇒ 必须在此断页，簇在此截断；
 * - **avoid 边**（`A.after == AVOID || B.before == AVOID`）⇒ 两叶的整段并入同一簇；
 * - 否则 ⇒ 可自由断页，簇在此截断。
 *
 * 另加 `break-inside: avoid`：同一**属主**（叶自身或最近的该属性祖先）的全部行成**原子簇**。
 * 原子簇与 avoid 边簇**并存**（嵌套区间是有意的：分页器逐条独立判「装得下才躲」，丢掉内层会让
 * 「内层装得下、外层装不下」时本该保整的内层被撕）。
 *
 * ## 判据为什么是「扁平块叶序列」而不是「盒树兄弟」
 *
 * 唯一必须遵守的一致性，是**预算**与**分页**用同一条簇判据 —— 否则 P3 把图缩到「簇装得下」，
 * 分页器却把同一个簇撕开，缩图白做且观感自相矛盾。
 *
 * 预算侧（[ReplacedFitBudget.plan]）为兼顾重/轻两路，用的是**扁平块叶序列**（[NormalFlowLayout.enumerateBlockLeaves]
 * ↔ `LightPrepare.markupLeaves`，两路保证一致的那一份），**不做父容器边界检查**。本类因此采用
 * **同一份序列、同一判据**（[joins]/[isForced] 就是它的边判据）。于是：
 *
 * - 重路径（[BoxBookLayout] / `BoxDrawableLayout`）：[leavesOf] 把盒树摊平成叶序列；
 * - 轻路径（`PartialDrawableLayout`）：直接用窗口的叶序列。
 *
 * **与 CSS「同父容器相邻块」的差异**（有意，已文档化）：`<div><p>a</p></div><p break-before:avoid>b</p>`
 * 这类「跨容器边界」的 avoid 边，本判据会把两段并簇（CSS 只在容器内部判定）。真实语料不受影响
 * （块级 avoid 边都在同一父容器下；见 `docs/分页高度约束与断页规则-方案.md` §1.4 的 `.fm-figure`），
 * 但这一差异被 `BreakClustersTest` 的用例钉住，避免后人误当 bug 修。
 *
 * 提升叶（独图提升）的断页边取**宿主块**（[LayoutBox.breakCarrierStyle]）——真书
 * `.fm-figure { page-break-after: avoid }` 正是如此，否则「图 + 图注」的 avoid 边丢失。
 *
 * ## 架构归位
 *
 * 这是**分页语义**（排版层上）的输入，输入是盒树 / 块叶序列（渲染层产物）：放在 `laying/` 里做纯函数，
 * 由分页侧消费。渲染层不认识「页」，不在这里做任何缩图/裁剪决策。
 */
object BreakClusters {

    /**
     * 一个块叶的分页输入。
     *
     * @property before / after 该叶的**有效**断页边（提升叶取宿主块；见 [LayoutBox.breakCarrierStyle]）。
     * @property lines 该叶占的全局行区间（无行叶 = [IntRange.EMPTY]）。
     * @property avoidOwner 最近的 `break-inside: avoid` 属主标识（叶自身或祖先；同主 = 同一原子簇）；
     *   null = 无。**只做身份比较**，不要求具体类型。
     */
    data class Leaf(
        val before: BreakRule,
        val after: BreakRule,
        val lines: IntRange = IntRange.EMPTY,
        val avoidOwner: Any? = null,
    )

    /**
     * 同一次走查的两条分页约束。
     *
     * @property clusters 不可断行区间（`[start, endExclusive)`）。重叠无害——分页器逐条独立判
     *   「整簇装得下才躲」，取最早断点。
     * @property forcedBeforeLines 这些行**之前**必须断页（该行必为页首）。与 [clusters] 不相交：
     *   强制边在 [joins] 里返回 false，簇不会跨过它。
     */
    data class Plan(val clusters: List<IntRange>, val forcedBeforeLines: Set<Int>)

    /**
     * 边判据（单源）：任一端的 `ALWAYS` ⇒ 强制断页。
     * [ReplacedFitBudget] 的簇判据也是这一条，两处**必须**同源。
     */
    fun isForced(afterA: BreakRule, beforeB: BreakRule): Boolean =
        afterA == BreakRule.ALWAYS || beforeB == BreakRule.ALWAYS

    /** 边判据（单源）：任一端的 `AVOID` ⇒ 同簇；强制边优先（返回 false）。 */
    fun joins(afterA: BreakRule, beforeB: BreakRule): Boolean {
        if (isForced(afterA, beforeB)) return false
        return afterA == BreakRule.AVOID || beforeB == BreakRule.AVOID
    }

    /** 只要簇（[plan] 的薄包装）。 */
    fun of(leaves: List<Leaf>): List<IntRange> = plan(leaves).clusters

    /** 扁平块叶序列上的**簇 + 强制断页行**（分页侧两条约束的单源）。 */
    fun plan(leaves: List<Leaf>): Plan {
        val clusters = ArrayList<IntRange>()
        val forced = LinkedHashSet<Int>()
        // 1) avoid 边簇（扁平叶序列），强制边截断并记录右侧叶的首行。
        var i = 0
        while (i < leaves.size) {
            var j = i
            while (j + 1 < leaves.size) {
                val a = leaves[j]
                val b = leaves[j + 1]
                if (isForced(a.after, b.before)) {
                    if (!b.lines.isEmpty()) forced.add(b.lines.first)
                    break
                }
                if (!joins(a.after, b.before)) break
                j++
            }
            if (j > i) spanOf(leaves, i, j)?.let { clusters.add(it) }
            i = j + 1
        }
        // 2) break-inside 原子簇：同一属主的全部叶行取并集（叶序即行序，故并集连续）。
        val byOwner = LinkedHashMap<Any, IntRange>()
        for (leaf in leaves) {
            val owner = leaf.avoidOwner ?: continue
            if (leaf.lines.isEmpty()) continue
            val cur = byOwner[owner]
            byOwner[owner] = if (cur == null) leaf.lines
            else minOf(cur.first, leaf.lines.first)..maxOf(cur.last, leaf.lines.last)
        }
        for (span in byOwner.values) if (span.last > span.first) clusters.add(span.first until span.last + 1)
        return Plan(clusters, forced)
    }

    /**
     * 盒树 → 扁平块叶序列（重路径 / 绘制路径共用），顺路带上最近的 `break-inside: avoid` 属主。
     *
     * 叶序 = 文档序 = 行序（[LayoutBox.firstLineIndex]），与 [NormalFlowLayout.enumerateBlockLeaves]
     * 一致；提升叶的行区间由 `firstLineIndex/lastLineExclusive` 直接给出。
     */
    fun leavesOf(roots: List<LayoutBox>): List<Leaf> {
        val out = ArrayList<Leaf>()
        for (root in roots) collectLeaves(root, null, out)
        return out
    }

    private fun collectLeaves(box: LayoutBox, owner: Any?, out: MutableList<Leaf>) {
        val ownerHere = if (box.breakInsideAvoid) box else owner
        if (box.isContainer) {
            for (child in box.childBoxes) collectLeaves(child, ownerHere, out)
        } else {
            out.add(Leaf(box.breakBefore, box.breakAfter, linesOf(box), ownerHere))
        }
    }

    private fun linesOf(box: LayoutBox): IntRange =
        if (box.firstLineIndex >= 0 && box.lastLineExclusive > box.firstLineIndex) {
            box.firstLineIndex until box.lastLineExclusive
        } else {
            IntRange.EMPTY
        }

    /** `leaves[from..to]` 占的行区间；全为空叶返回 null。 */
    private fun spanOf(leaves: List<Leaf>, from: Int, to: Int): IntRange? {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (k in from..to) {
            val l = leaves[k].lines
            if (l.isEmpty()) continue
            lo = minOf(lo, l.first)
            hi = maxOf(hi, l.last)
        }
        return if (hi >= lo) lo until hi + 1 else null
    }
}
