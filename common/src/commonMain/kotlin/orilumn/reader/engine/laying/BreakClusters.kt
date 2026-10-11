package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule

/**
 * 断页簇 —— [LayoutBox] 树上的纯函数，给出「不许被页边界切开」的全局行区间。
 *
 * ## 口径（CSS Fragmentation §4.4 unforced break）
 *
 * 页边界的候选位置是**块级相邻兄弟之间**。两条相邻块 A(上)/B(下) 之间的边：
 *
 * - **强制边**（`A.breakAfter == ALWAYS || B.breakBefore == ALWAYS`）⇒ 必须在此断页，簇在此截断；
 * - **avoid 边**（`A.breakAfter == AVOID || B.breakBefore == AVOID`）⇒ 两块的整段并入同一簇；
 * - 否则 ⇒ 可自由断页，簇在此截断。
 *
 * 另加 `break-inside: avoid`：盒（叶或容器）自身整段成**原子簇**（其内部不许断）。
 * 原子簇仍可被 avoid 边与其相邻块串起来（§4.4 的「avoid 是可叠加的软约束」）。
 *
 * ## 与既有 `breakInsideAvoidRanges` 的关系
 *
 * 既有实现（`BoxBookLayout`/`BoxDrawableLayout`/`LightPrepare`）只收集 `break-inside`。本类是它的
 * **超集**：`break-inside` ∪ `avoid 边`。分页侧应消费本类的输出（`breakInsideAvoidRanges` 里的
 * `break-inside` 子集是其特例，行为不变）。
 *
 * ## 为什么行区间能自证连续
 *
 * 兄弟块在行流里**首尾相接**，故「相邻兄弟整段」拼出的区间天然连续；无行的兄弟被跳过也不破坏
 * 连续性（它不占行）。容器的行区间由其**后代**推出（[span]），因此**不依赖**重路径
 * `recomputeContainerSpans` 是否已跑过 —— 同一条判据在重/轻两路都成立。
 *
 * ## 架构归位
 *
 * 这是**分页语义**（排版层上）的输入，但输入是盒树（渲染层产物）：放在 `laying/` 里做纯函数，
 * 由分页侧消费。渲染层不认识「页」，不在这里做任何缩图/裁剪决策。
 */
object BreakClusters {

    /**
     * 全树的不可断行区间，按行序。重叠（嵌套原子簇 / 链式 avoid 边）无害：既有分页算法对重叠区间
     * 取最早断点（见 `Paginator.applyBreakInsideAvoid`）。
     */
    fun of(roots: List<LayoutBox>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (root in roots) collect(root, out)
        groupSiblings(roots, out)
        return out
    }

    private fun collect(box: LayoutBox, out: MutableList<IntRange>) {
        // 自身 break-inside: avoid ⇒ 整段原子（含其子树全部行）。
        if (box.breakInsideAvoid) span(box)?.let { out.add(it) }
        for (child in box.childBoxes) collect(child, out)
        groupSiblings(box.childBoxes, out)
    }

    /** 同一父容器下的相邻块：以 avoid 边成簇、以强制边截断。 */
    private fun groupSiblings(siblings: List<LayoutBox>, out: MutableList<IntRange>) {
        val vis = siblings.mapNotNull { box -> span(box)?.let { box to it } }
        var i = 0
        while (i < vis.size) {
            var j = i
            while (j + 1 < vis.size && joins(vis[j].first, vis[j + 1].first)) j++
            if (j > i) out.add(vis[i].second.first until vis[j].second.last + 1)
            i = j + 1
        }
    }

    private fun joins(a: LayoutBox, b: LayoutBox): Boolean {
        val after = a.breakAfter
        val before = b.breakBefore
        if (after == BreakRule.ALWAYS || before == BreakRule.ALWAYS) return false
        return after == BreakRule.AVOID || before == BreakRule.AVOID
    }

    /** 盒占用的全局行区间（首行索引 until 尾行排除）；无行（含空容器）返回 null。 */
    private fun span(box: LayoutBox): IntRange? {
        if (box.firstLineIndex >= 0 && box.lastLineExclusive > box.firstLineIndex) {
            return box.firstLineIndex until box.lastLineExclusive
        }
        // 容器：由后代推出（不依赖 recomputeContainerSpans）。
        var min = Int.MAX_VALUE
        var max = -1
        for (child in box.childBoxes) {
            val s = span(child) ?: continue
            if (s.first < min) min = s.first
            if (s.last + 1 > max) max = s.last + 1
        }
        return if (max > min) min until max else null
    }
}
