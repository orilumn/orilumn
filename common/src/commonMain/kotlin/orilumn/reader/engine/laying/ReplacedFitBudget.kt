package orilumn.reader.engine.laying

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
}
