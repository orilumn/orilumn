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
) : BookLayout {

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

    companion object {
        /** Builds from fixed line texts; each line is already split. No paragraph starts by default (a long
         *  continuous run); pass [boundaryLines] explicitly when paragraph starts are needed. */
        fun ofLines(vararg lines: String, lineHeight: Int = 20, boundaryLines: Set<Int>? = null) =
            FakeBookLayout(lines.toList(), lineHeight, boundaryLines ?: emptySet())
    }
}