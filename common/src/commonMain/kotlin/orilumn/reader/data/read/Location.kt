package orilumn.reader.data.read

/**
 * Reading-location model (M0 simplified: chapter index + within-chapter progress).
 *
 * Design motivation: see ARCHITECTURE-FOLIATE.md §5 — MEPUB location is primarily "chapter index +
 * within-chapter offset/ratio", convenient for progress reporting and save/resume; later M4/M5 stages add
 * CFI-compatible strings (stable traces for bookmarks/notes).
 *
 * @property chapter the chapter index in the spine (0-based).
 * @property target in-chapter anchor (e.g. the original book's `#id`); null when absent. An explicit anchor takes precedence over [progress] conversion.
 * @property progress within-chapter progress; 0.0 = chapter start, 1.0 = chapter end.
 */
data class Location(
    val chapter: Int,
    val target: String? = null,
    val progress: Double = 0.0,
) {
    init {
        require(chapter >= 0) { "chapter 不能为负：$chapter" }
    }

    /** Whole-book progress (0.0..1.0), converted by equal weighting across chapters; out-of-range is auto-clamped. */
    fun bookProgress(chapterCount: Int): Double {
        if (chapterCount <= 0) return 0.0
        val c = chapter.coerceIn(0, chapterCount - 1)
        val p = progress.coerceIn(0.0, 1.0)
        return ((c + p) / chapterCount).coerceIn(0.0, 1.0)
    }
}

/**
 * Mutual conversion between the whole-book progress value and [Location] (pure logic, unit-testable).
 *
 * Convention: whole-book progress is "chapter-equal-weight". This is only approximate for books with very
 * uneven chapter lengths, sufficient for M0's progress-bar display and "open from the shelf to continue at
 * the last position"; precise progress awaits the character-level fraction introduced by foliate in M1.
 */
object ProgressConverter {

    /** Derive a location from whole-book progress; null when the chapter count is invalid. */
    fun locationFromBookProgress(percent: Double, chapterCount: Int): Location? {
        if (chapterCount <= 0) return null
        val clamped = percent.coerceIn(0.0, 1.0)
        val raw = clamped * chapterCount
        val chapter = raw.toInt().coerceIn(0, chapterCount - 1)
        val progress = (raw - chapter).coerceIn(0.0, 1.0)
        return Location(chapter = chapter, progress = progress)
    }

    /** Chapter progress → whole-book progress. */
    fun bookProgressOf(chapter: Int, withinChapter: Double, chapterCount: Int): Double =
        Location(chapter, progress = withinChapter).bookProgress(chapterCount)
}