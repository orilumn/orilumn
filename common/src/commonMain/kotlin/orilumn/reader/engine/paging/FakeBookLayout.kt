package orilumn.reader.engine.paging

/**
 * A [BookLayout] fake for JVM tests: every line has a fixed height and line start/end advance by the
 * given text. Used to verify the pure geometric behavior of [Paginator] / [ProgressMapper] without
 * depending on Android's StaticLayout. Text lines are separated by "\n"; each line's character count is
 * its text length and the line count equals the number of segments. Whether a line is a paragraph
 * boundary is given explicitly by [boundaryLines] (by default every line is a paragraph start, convenient
 * for testing simple full-line pagination).
 */
class FakeBookLayout(
    private val lineTexts: List<String>,
    private val lineHeight: Int = 20,
    private val boundaryLines: Set<Int> = emptySet(),
    /**
     * P4：不可断簇（[BreakAwareBookLayout.avoidSplitRanges]）——`break-inside: avoid` 块与
     * `break-*: avoid` 边簇合并后的行区间。空表（默认）⇒ `Paginator` 行为与旧版逐字节一致。
     */
    private val avoidRanges: List<IntRange> = emptyList(),
    /**
     * P4：强制断页行（[BreakAwareBookLayout.forcedBreakBeforeLines]）——该行必为页首。
     * 空集（默认）⇒ 整章一段，行为与旧版逐字节一致。
     */
    private val forcedLines: Set<Int> = emptySet(),
) : BreakAwareBookLayout {

    // Prefix sums: lineStart[i] = cumulative length of the first i lines
    private val startByLine: IntArray = run {
        val arr = IntArray(lineTexts.size + 1)
        for (i in lineTexts.indices) arr[i + 1] = arr[i] + lineTexts[i].length
        arr
    }

    override val lineCount: Int get() = lineTexts.size

    override val length: Int get() = startByLine.last()

    override fun getLineTop(i: Int): Int = i * lineHeight

    override fun getLineBottom(i: Int): Int = (i + 1) * lineHeight

    override fun getLineStart(i: Int): Int = startByLine[i]

    override fun getLineEnd(i: Int): Int = startByLine[i + 1]

    override fun isParagraphBoundaryLine(i: Int): Boolean = i in boundaryLines

    // ---- P4：断页约束（默认空 ⇒ 旧行为零变化）----

    override val breakInsideAvoidRanges: List<IntRange> get() = avoidRanges

    override val avoidSplitRanges: List<IntRange> get() = avoidRanges

    override val forcedBreakBeforeLines: Set<Int> get() = forcedLines

    companion object {
        /** Builds from fixed line texts; each line is already split. No paragraph starts by default (a long
         *  continuous run); pass [boundaryLines] explicitly when paragraph starts are needed. */
        fun ofLines(
            vararg lines: String,
            lineHeight: Int = 20,
            boundaryLines: Set<Int>? = null,
            avoidRanges: List<IntRange> = emptyList(),
            forcedLines: Set<Int> = emptySet(),
        ) = FakeBookLayout(lines.toList(), lineHeight, boundaryLines ?: emptySet(), avoidRanges, forcedLines)
    }
}