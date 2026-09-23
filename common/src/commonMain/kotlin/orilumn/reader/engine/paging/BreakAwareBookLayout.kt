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
}