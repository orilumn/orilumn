package orilumn.reader.engine.laying

import orilumn.reader.engine.css.BreakRule

/**
 * 断页约束的**单源纯函数**：给出「不许被页边界切开」的全局行区间（`break-inside: avoid` 原子区间）、
 * 「页边界不得落在其前」的行（`break-*: avoid` 禁止切点）与「必须断页」的行（强制）。
 *
 * ## 口径（CSS Fragmentation §4.4 unforced break）
 *
 * 页边界的候选位置分两族：**class A**（相邻块叶之间）与 **class B**（块内相邻行之间）。相邻叶
 * A(上)/B(下) 之间的 class A 边上：
 *
 * - **强制边**（`A.after == ALWAYS || B.before == ALWAYS`）⇒ 必须在此断页，边链在此截断；
 * - **avoid 边**（`A.after == AVOID || B.before == AVOID`）⇒ **只否掉这一条 class A 断点**
 *   （记入 [Plan.forbiddenCutLines]，取 B 的首行）；A、B **各自内部仍可断**（规范 Rule 1 的
 *   措辞是「all the break-before/after values applicable to this break point allow it」，avoid
 *   只否这一个点，**不**把 A、B 合成原子块）。切勿再把 avoid 边膨胀成「A 首行..B 末行」的整段
 *   区间——那正是 P4 首版的口径错误（标题后的大段正文被整段顶走）。
 * - 否则 ⇒ 此处可断。
 *
 * 另加 `break-inside: avoid`（Rule 4）：同一**属主**（叶自身或最近的该属性祖先）内部没有任何
 * class B/C 断点 ⇒ 其全部行成**原子区间** [Plan.atomicRanges]。原子区间与禁止切点**正交**：前者
 * 是「块内不许断」，后者是「这一条块间边不许断」。两者可嵌套（原子区间嵌在被 avoid 边串起来的
 * 链里）——分页器对二者分别判，不会互相抵消。
 *
 * ## 判据为什么是「扁平块叶序列」而不是「盒树兄弟」
 *
 * 唯一必须遵守的一致性，是**预算**与**分页**用同一条边判据 —— 否则 P3 把图缩到「图+图注装得下」，
 * 分页器却跨过图与图注之间的 avoid 边，缩图白做且观感自相矛盾。
 *
 * 预算侧（[ReplacedFitBudget.plan]）为兼顾重/轻两路，用的是**扁平块叶序列**（[NormalFlowLayout.enumerateBlockLeaves]
 * ↔ `LightPrepare.markupLeaves`，两路保证一致的那一份），**不做父容器边界检查**。本类因此采用
 * **同一份序列、同一判据**（[joins]/[isForced] 就是它的边判据）。于是：
 *
 * - 重路径（[BoxBookLayout] / `BoxDrawableLayout`）：[leavesOf] 把盒树摊平成叶序列；
 * - 轻路径（`PartialDrawableLayout`）：直接用窗口的叶序列。
 *
 * **与 CSS「同父容器相邻块」的差异**（有意，已文档化）：`<div><p>a</p></div><p break-before:avoid>b</p>`
 * 这类「跨容器边界」的 avoid 边，本判据会否掉这条切点（CSS 只在容器内部判定）。真实语料不受影响
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
     * @property avoidOwner 最近的 `break-inside: avoid` 属主标识（叶自身或祖先；同主 = 同一原子区间）；
     *   null = 无。**只做身份比较**，不要求具体类型。
     */
    data class Leaf(
        val before: BreakRule,
        val after: BreakRule,
        val lines: IntRange = IntRange.EMPTY,
        val avoidOwner: Any? = null,
    )

    /**
     * 同一次走查的三条分页约束（语义严格区分，见类头）。
     *
     * @property atomicRanges `break-inside: avoid` 的**原子行区间**（`[start, endExclusive)`）。
     *   区间**内部**没有合法断点；装得下一页才整体顺延，装不下允许撕裂。重叠无害——分页器逐条
     *   独立判「整个区间装得下才躲」，取最早断点。
     * @property forbiddenCutLines `break-before/after: avoid` 的**禁止切点**：页边界**不得**落在这
     *   些行之前（`cut == line` 即「页尾在该行之前」，于是该行成为下一页页首 = 跨过了一条 avoid 边）。
     *   只否掉这一条 class A 断点；两侧块各自内部仍可断（与 [atomicRanges] 正交）。
     * @property forcedBeforeLines 这些行**之前**必须断页（该行必为页首）。与 [atomicRanges] 不相交：
     *   强制边在 [joins] 里返回 false，原子区间不会跨过它。
     */
    data class Plan(
        val atomicRanges: List<IntRange>,
        val forbiddenCutLines: Set<Int>,
        val forcedBeforeLines: Set<Int>,
    )

    /**
     * 边判据（单源）：任一端的 `ALWAYS` ⇒ 强制断页。
     * [ReplacedFitBudget] 的（预算用）同链判据也走这一条，两处**必须**同源。
     */
    fun isForced(afterA: BreakRule, beforeB: BreakRule): Boolean =
        afterA == BreakRule.ALWAYS || beforeB == BreakRule.ALWAYS

    /** 边判据（单源）：任一端的 `AVOID` ⇒ 同簇；强制边优先（返回 false）。 */
    fun joins(afterA: BreakRule, beforeB: BreakRule): Boolean {
        if (isForced(afterA, beforeB)) return false
        return afterA == BreakRule.AVOID || beforeB == BreakRule.AVOID
    }

    /**
     * avoid **边链的块级范围**（`[f, l]`，含两端），边界判据仍是 [joins] 单源。
     *
     * 给**没有全章叶序列**的调用方（临时页填充 / 插图预算）把范围取到整条 avoid 边链用：窗口若
     * 把链截断，[plan] 就看不到链上后续接口的禁止切点。本函数**只圈范围**，不做任何页边界决定
     * —— 页边界一律由 `Paginator` 在取整后的窗口上算。
     *
     * @param i 起点块；范围块域 `[lo, hi)`；`before/after` 为块的有效断页边。
     */
    fun chainRange(
        i: Int,
        lo: Int,
        hi: Int,
        before: (Int) -> BreakRule,
        after: (Int) -> BreakRule,
    ): IntRange {
        var f = i
        while (f - 1 >= lo && joins(after(f - 1), before(f))) f--
        var l = i
        while (l + 1 < hi && joins(after(l), before(l + 1))) l++
        return f..l
    }

    /** 扁平块叶序列上的**原子区间 + 禁止切点 + 强制断页行**（分页侧三条约束的单源）。 */
    fun plan(leaves: List<Leaf>): Plan {
        val atomic = ArrayList<IntRange>()
        val forbidden = LinkedHashSet<Int>()
        val forced = LinkedHashSet<Int>()
        // 1) 边：强制边截断并记录右侧叶的首行；avoid 边只记「禁止切点」（也是右侧叶首行）。
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
            // avoid 边链 [i..j]：链内每一个接口的右侧叶首行都是「禁止切点」。
            if (j > i) for (k in i + 1..j) {
                val l = leaves[k].lines
                if (!l.isEmpty()) forbidden.add(l.first)
            }
            i = j + 1
        }
        // 2) break-inside 原子区间：同一属主的全部叶行取并集（叶序即行序，故并集连续）。
        val byOwner = LinkedHashMap<Any, IntRange>()
        for (leaf in leaves) {
            val owner = leaf.avoidOwner ?: continue
            if (leaf.lines.isEmpty()) continue
            val cur = byOwner[owner]
            byOwner[owner] = if (cur == null) leaf.lines
            else minOf(cur.first, leaf.lines.first)..maxOf(cur.last, leaf.lines.last)
        }
        for (span in byOwner.values) if (span.last > span.first) atomic.add(span.first until span.last + 1)
        return Plan(atomic, forbidden, forced)
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
}

