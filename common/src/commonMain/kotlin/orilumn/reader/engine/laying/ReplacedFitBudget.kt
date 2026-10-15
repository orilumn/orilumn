package orilumn.reader.engine.laying
import kotlin.math.roundToInt

import orilumn.reader.engine.css.BreakRule
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement

/**
 * 排版层（上）· 分页约束：块级替换叶（`display:block` 的 img）的**高度预算**。
 *
 * ## 为什么这条约束在分页层而不在 CSS
 * 分页阅读器没有纵向滚动：一页装不下的东西只能被裁、或被推到下一页；而「一页有多高」
 * （`contentH`）要等排版层分页时才知道。CSS 层（`ua.css` / 主题表）拿不到这个量，所以它
 * **不能**写成声明去层叠，只能在分页/布局过程里算。
 * 见 `docs/分页高度约束与断页规则-方案.md` §2.1、§5。
 *
 * ## 两条要求
 * 1. **不变式（安全网）**：任何块级替换叶的 used 高 ≤ `contentH`——只要它自己就高过一页
 *    （书中写了 `height`/`min-height`，或内在尺寸在它的 used 宽下就超过一页），就等比缩到
 *    一页，哪怕没有图注。主动放弃按 §10.4 的 `min-height` 也撑破页面的例外（显式
 *    `min-height` 的图仍可超页并撕裂，`usedReplacedSize` 的 `min > max` 保证这一点）。
 * 2. **口径乙（簇）**：图与它所属的**不可断簇**（图注、`break-before/after` 链上的邻居，
 *    由 [BreakClusters] 定义）必须同页 ⇒ 预算先扣掉簇内**其它**内容的高度。
 *
 * ## 单源
 * 决策只在这里做一次。重路径（[NormalFlowLayout]）与轻路径（`LightPrepare`）各自注入
 * `measureBlock` 去量 `othersH`；两边必须量出同一个值（由量画/重轻一致性测试守）。
 *
 * 层级标注：本文件属 **排版层（上）**；它不产生任何 CSS 声明，不参与层叠。
 */
object ReplacedFitBudget {

    /**
     * 预算下限的分母：可用高度低于 `contentH / FLOOR_DIVISOR` 时**不缩**。
     *
     * 低于这个量级时，缩下去的图只剩一条线，不可辨认；与其把它压成一条缝，不如维持原样
     * （保持现状的溢出/撕裂——至少读者能看到「这里有一张图被裁了」而不是「一条线」）。
     * 取 1/6 是经验值：一页竖向切成 6 段时最下面那段已基本无信息量。**一个常量，易调。**
     */
    const val FLOOR_DIVISOR = 6

    /** 预算下限（px）。 */
    fun floor(contentH: Int): Int = contentH / FLOOR_DIVISOR

    /**
     * 计算替换叶可用高度预算。
     *
     * @param naturalH 该图**不加约束时**的 used 高（`usedReplacedSize(fitMaxH = null)` 的结果）。
     * @param othersH 同簇内**其它**内容（图注、邻居块）的实测总高；图自身按 0 计。
     *   簇里只有图本身时传 0。
     * @param contentH 一页的内容高（分页层给的版心高）。
     * @return 要传给 `usedReplacedSize(fitMaxH = …)` 的高度上限；**null = 不约束**。
     */
    fun budget(naturalH: Int, othersH: Int, contentH: Int): Int? {
        if (naturalH <= 0 || contentH <= 0) return null
        val others = othersH.coerceAtLeast(0)
        // 簇里其它内容自己就装不下一页 ⇒ 无从谈起（让它去撕裂，缩图也救不回来）。
        if (others >= contentH) return null
        val avail = contentH - others
        // 图不需要缩。
        if (avail >= naturalH) return null
        // 缩到下限以下不如不缩。
        if (avail < floor(contentH)) return null
        return avail
    }

    /** 一个块叶的**有效**断页边（提升叶取宿主块；见 `LayoutBox.breakCarrierStyle`）。 */
    data class BreakPair(val before: BreakRule, val after: BreakRule)

    private fun joins(a: BreakPair, b: BreakPair): Boolean = BreakClusters.joins(a.after, b.before)

    /**
     * **扁平块叶序列**上、叶 [index] 所属的不可断簇下标区间（含自身）。
     *
     * 判据与 [BreakClusters] **完全同源**（`BreakClusters.joins`）：相邻叶之间，任一端 `ALWAYS`
     * ⇒ 断开，任一端 `AVOID` ⇒ 同簇，否则断开。分页侧消费的就是同一条判据 —— 预算与分页必须
     * 对「簇」有同一个定义，否则缩了图却被分页撕裂。
     *
     * **有意不展开 `break-inside: avoid` 的祖先原子簇**：那种情形下 `othersH` 往往已经 ≥
     * `contentH`（预算自然失效），而「整段不许切」的语义已由既有 `breakInsideAvoidRanges` /
     * [BreakClusters.plan] 的原子簇承担（见方案 §9「簇判据不展开祖先原子簇」）。
     *
     * 于是两路只需要同一份扁平叶序列 —— 而它正是重/轻两路**保证一致**的那份
     * （`NormalFlowLayout.enumerateBlockLeaves` ↔ `LightPrepare.markupLeaves`）。
     */
    fun clusterRange(leaves: List<MarkupElement>, index: Int, breakOf: (MarkupElement) -> BreakPair): IntRange {
        var lo = index
        while (lo > 0 && joins(breakOf(leaves[lo - 1]), breakOf(leaves[lo]))) lo--
        var hi = index
        while (hi + 1 < leaves.size && joins(breakOf(leaves[hi]), breakOf(leaves[hi + 1]))) hi++
        return lo..hi
    }

    private fun ownBottomEdgesStyle(el: MarkupElement, styleOf: (MarkupElement) -> ComputedStyle): Int =
        if (el.isText) 0 else (styleOf(el).padding.bottom + styleOf(el).border.bottom).roundToInt()

    private fun topEdgesStyle(el: MarkupElement, styleOf: (MarkupElement) -> ComputedStyle): Int =
        if (el.isText) 0 else (styleOf(el).padding.top + styleOf(el).border.top).roundToInt()

    /** 块间落差（前叶自身下边 + 折叠边距 + 后叶自身 top 边），与 `BoxChapterLayouter.blockBoundaryGap` 同式。 */
    private fun blockBoundaryGapStyle(a: MarkupElement, b: MarkupElement, styleOf: (MarkupElement) -> ComputedStyle): Int =
        ownBottomEdgesStyle(a, styleOf) + NormalFlowLayout.consecutiveLeafAdvance(a, b, styleOf) + topEdgesStyle(b, styleOf)

    /**
     * 簇内**除图自身**以外内容的总高。
     *
     * 口径：Σ 各成员自身 border-box 高 ＋ Σ 相邻成员之间**折叠后**的纵向间隙。间隙用
     * [NormalFlowLayout.consecutiveLeafAdvance] —— 与轻路径 [orilumn.reader.engine.laying] 行流
     * 同一个函数，重路径 [NormalFlowLayout.emit] 的间隙按定义与它相等（`rebuildLocalLines` 的注释
     * 已把这个恒等式当作前提），故两路量出同一个 `othersH`。
     *
     * 再加上簇落在整页上时**周边归本页**的那两段（帧口径见 [orilumn.reader.engine.paging.BookLayout]）：
     * 簇首之上的**页首缺口**（块间落差**减去前叶自身下边**——前叶下边归前一页页尾）与簇尾所属叶
     * 自身的下边（簇尾恰为该叶末行时）。装不下这两段的簇同样会被推到下一页而图被迫裁切。
     * 簇尾**之下**的块间落差**不进本页**（它归下一页页首），故不以簇尾的后一叶计。
     *
     * @param members 簇成员（文档序，含图自身）
     * @param imageIndex [members] 中图的**下标**（不是叶下标）
     * @param ownHeight 按下标返回成员自身 border-box 高；图自身传**自然尺寸**（不受预算影响）
     * @param styleOf 元素 → 样式（折叠需要祖先链样式）
     * @param prevLeaf 簇首的上一个叶（用以计算簇首页首缺口）
     */
    fun othersHeight(
        members: List<MarkupElement>,
        imageIndex: Int,
        ownHeight: (Int) -> Int,
        styleOf: (MarkupElement) -> ComputedStyle,
        prevLeaf: MarkupElement? = null,
    ): Int {
        if (members.isEmpty()) return 0
        var total = 0
        for (k in members.indices) {
            if (k > 0) total += NormalFlowLayout.consecutiveLeafAdvance(members[k - 1], members[k], styleOf)
            total += ownHeight(k)
        }
        if (prevLeaf != null) {
            total += blockBoundaryGapStyle(prevLeaf, members[0], styleOf) - ownBottomEdgesStyle(prevLeaf, styleOf)
        }
        // 页尾叶自身边：簇尾属于某叶，只有簇尾是该叶末行时才占本页下边界（这里按「簇即末行」计，
        // 保守取满——预算偏大只会更早缩图，不会裁图）。
        total += ownBottomEdgesStyle(members[members.size - 1], styleOf)
        return (total - ownHeight(imageIndex)).coerceAtLeast(0)
    }

    /**
     * **唯一写者**：给定一份扁平块叶序列，算出需要缩的替换叶 → 高度上限。
     *
     * 重路径（`NormalFlowLayout.layout`）与轻路径（`LightPrepare.applyReplacedFits`）都调这一个
     * 函数，各自只负责把 [naturalH]/[ownHeight]/[breakOf] 喂成自己那份几何。图自身的高度**不参与**
     * `othersH`（只影响它自己那一行），所以一遍自然流量即可定案，不需要迭代。
     *
     * @param leaves 扁平块叶序列（文档序）
     * @param lo 起点叶下标（含）
     * @param hi 终点叶下标（不含）——只给这个区间的叶算预算；簇成员可越界（只被量，不被改）
     * @param contentH 一页内容高（px）；≤ 0 ⇒ 空表（无约束）
     * @param naturalH 按下标返回替换叶的**自然** used 高（非替换叶返回 0）
     * @param ownHeight 按下标返回该叶自身 border-box 高（自然尺寸）
     * @param breakOf 按下标（元素）返回有效断页边
     * @return 叶下标 → 高度上限（只含需要缩的叶）
     */
    fun plan(
        leaves: List<MarkupElement>,
        lo: Int,
        hi: Int,
        contentH: Int,
        naturalH: (Int) -> Int,
        ownHeight: (Int) -> Int,
        breakOf: (MarkupElement) -> BreakPair,
        styleOf: (MarkupElement) -> ComputedStyle,
    ): Map<Int, Int> {
        if (contentH <= 0) return emptyMap()
        val a = lo.coerceIn(0, leaves.size)
        val b = hi.coerceIn(a, leaves.size)
        var out: MutableMap<Int, Int>? = null
        for (i in a until b) {
            val natural = naturalH(i)
            if (natural <= 0) continue
            val range = clusterRange(leaves, i, breakOf)
            val members = range.map { leaves[it] }
            val others = othersHeight(
                members,
                i - range.first,
                { k -> ownHeight(range.first + k) },
                styleOf,
                prevLeaf = if (range.first > 0) leaves[range.first - 1] else null,
            )
            val fit = budget(natural, others, contentH) ?: continue
            (out ?: HashMap<Int, Int>().also { out = it })[i] = fit
        }
        return out ?: emptyMap()
    }
}