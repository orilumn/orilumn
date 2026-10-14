package orilumn.reader.engine.paging

/**
 * Optional extension of [BookLayout] that lets the [Paginator] honour the CSS Fragmentation
 * (`break-inside: avoid`, `break-before/after: avoid`, `break-before/after: always`) rules.
 *
 * A layout that models block boxes can mark (a) the line ranges inside which no break is allowed
 * (`break-inside: avoid` — atomic blocks such as a figure or a table), (b) the class A break points
 * between two blocks that must not be broken (`break-before/after: avoid`), and (c) the lines that
 * must start a page (forced breaks). The Paginator only consults this interface when the layout
 * implements it, so plain [BookLayout]s (like [FakeBookLayout] in tests) are paginated exactly as
 * before — keeping backward compatibility with existing paging tests.
 *
 * All three sets are produced by the single source [orilumn.reader.engine.laying.BreakClusters.plan].
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
     * **原子区间**（`BreakClusters` 口径）= `break-inside: avoid` 块的行区间，全局行区间。区间
     * **内部**没有合法断点：装得下一页才整体顺延，装不下允许撕裂。分页侧消费本属性。
     *
     * 与 [breakInsideAvoidRanges] 等价（后者是历史名）：默认回落 [breakInsideAvoidRanges]
     * ⇒ 只实现了 break-inside 的旧布局**行为逐字节不变**。
     *
     * 注意：这里**不含** `break-before/after: avoid` 的整段绑定——那是一条 **class A 断点**
     * 的禁用，交由 [forbiddenCutLines] 表达（否则「标题别与第一段分开」会被实现成「整段顶走」）。
     */
    val avoidSplitRanges: List<IntRange> get() = breakInsideAvoidRanges

    /**
     * **禁止切点**：页边界不得落在这些行之前——`cut == L` 表示页尾在第 `L` 行之前结束、行 `L`
     * 成为下一页页首，也就是跨过了一条 `break-before/after: avoid` 的 class A 边。
     *
     * 命中禁止切点时，分页器退到**前一个可断点**（可能是左侧块内部的行缝 —— 规范只否掉这一条
     * 边，不把两侧块合成原子块）；若页内已无其它可断点，则按规范放弃 discourage、仍切在自然填充点
     * （避免丢内容）。
     *
     * 默认空集 ⇒ 不认识 avoid 边的旧布局**行为逐字节不变**。
     */
    val forbiddenCutLines: Set<Int> get() = emptySet()

    /**
     * 在这些行**之前**必须断页（CSS `break-before`/`break-after: always | page | left | right |
     * recto | verso`，即 [orilumn.reader.engine.css.BreakRule.ALWAYS]），全局行下标。
     *
     * 语义：行 `L` 是某一页的**首行** —— 上一页在这一行之前结束（可能**不满页**）。
     * 段内仍走整行填充 + [avoidSplitRanges]；强制边与原子区间**不相交**
     * （`BreakClusters` 的 `joins` 在强制边返回 false）。
     *
     * `0` 与 ≥ [BookLayout.lineCount] 的行无意义（调用方应自行剔除）。
     * 默认空集 ⇒ 不认识强制断页的旧布局**行为逐字节不变**。
     */
    val forcedBreakBeforeLines: Set<Int> get() = emptySet()
}
