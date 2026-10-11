package orilumn.reader.engine.paging

/**
 * Optional extension of [BookLayout] that lets the [Paginator] honour CSS `break-inside: avoid`.
 *
 * A layout that models block boxes can mark the line ranges that must not be torn across a page
 * break (e.g. a figure, a table, an indivisible block). The Paginator only consults this interface
 * when the layout implements it, so plain [BookLayout]s (like [FakeBookLayout] in tests) are
 * paginated exactly as before — keeping backward compatibility with existing paging tests.
 */
interface BreakAwareBookLayout : BookLayout {

    /**
     * Line ranges each owned by a `break-inside: avoid` block, as `[start, endExclusive)` global
     * line indexes.
     *
     * Contract for the caller: a range must be **small enough to fit one page** on its own. Blocks
     * taller than the page are deliberately omitted (they are allowed to tear, matching browser
     * behaviour). Overlapping ranges are harmless — the paginator retreats to the earliest break.
     */
    val breakInsideAvoidRanges: List<IntRange>

    /**
     * **不可断簇**（`BreakClusters` 口径）= `break-inside: avoid` ∪ **avoid 边**
     * （相邻块 `break-after`/`break-before: avoid` 串起来的整段），全局行区间。
     *
     * 与 [breakInsideAvoidRanges] 的关系：本属性是它的**超集**
     * （break-inside 子集是其特例，行为不变）。分页侧消费本属性。
     *
     * 默认回落 [breakInsideAvoidRanges] ⇒ 只实现了 break-inside 的旧布局**行为逐字节不变**。
     */
    val avoidSplitRanges: List<IntRange> get() = breakInsideAvoidRanges

    /**
     * 在这些行**之前**必须断页（CSS `break-before`/`break-after: always | page | left | right |
     * recto | verso`，即 [orilumn.reader.engine.css.BreakRule.ALWAYS]），全局行下标。
     *
     * 语义：行 `L` 是某一页的**首行** —— 上一页在这一行之前结束（可能**不满页**）。
     * 段内仍走整行填充 + [avoidSplitRanges]；强制边与 avoid 簇**不相交**
     * （`BreakClusters` 的 `joins` 在强制边返回 false）。
     *
     * `0` 与 ≥ [BookLayout.lineCount] 的行无意义（调用方应自行剔除）。
     * 默认空集 ⇒ 不认识强制断页的旧布局**行为逐字节不变**。
     */
    val forcedBreakBeforeLines: Set<Int> get() = emptySet()
}