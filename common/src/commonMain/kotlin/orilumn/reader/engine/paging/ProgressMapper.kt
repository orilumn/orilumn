package orilumn.reader.engine.paging

import orilumn.reader.engine.html.MarkupElement

/**
 * Whole-book progress conversion (character-based, pure logic, unit-testable).
 *
 * Each chapter gets its `textLength` (cumulative plain-text char count, no materialization or
 * layout needed) at parse time, so the exact position of any character within the book can be
 * computed without laying out the whole book. Resuming reading also avoids full-book layout:
 * first locate the chapter, then lazily lay out that chapter.
 *
 * Note: this mapping is based on character count, not exact page numbers; it is approximate for
 * books with many figures/images (images contribute no text but occupy visual space). It is
 * accurate for plain-text EPUBs.
 */
object ProgressMapper {

    /** Per-chapter length statistics, precomputed and passed in by the controller. */
    class ChapterLengths(
        /** Each chapter's `MarkupElement.textLength` (in spine.index order). */
        private val lengths: LongArray,
    ) {
        val size: Int get() = lengths.size

        /** Per-chapter char counts (spine order, read-only view). */
        val items: LongArray get() = lengths

        val totalChars: Long get() = lengths.sum()

        /** Cumulative char count of all chapters before [chapter]. */
        fun precedingChars(chapter: Int): Long {
            var s = 0L
            for (i in 0 until chapter.coerceIn(0, lengths.size)) s += lengths[i]
            return s
        }

        /** Char count of chapter [chapter] (returns 0 if out of bounds). */
        fun chapterChars(chapter: Int): Long =
            if (chapter in lengths.indices) lengths[chapter] else 0L
    }

    /**
     * Computes the whole-book progress in 0..1 from a chapter plus an intra-chapter char offset.
     * Out-of-range chapters and negative offsets are clamped automatically.
     * @param chapter spine index
     * @param charOffset the char offset reached within the chapter (e.g. the current page's `slice.charStart`)
     */
    fun bookProgress(lengths: ChapterLengths, chapter: Int, charOffset: Int): Double {
        val total = lengths.totalChars
        if (total <= 0L) return 0.0
        val c = chapter.coerceIn(0, lengths.size - 1)
        val offset = charOffset.coerceIn(0, Int.MAX_VALUE).toLong()
        val pos = lengths.precedingChars(c) + offset
        return (pos.toDouble() / total).coerceIn(0.0, 1.0)
    }

    /**
     * Derives the chapter from a whole-book progress in 0..1. Note this method does not lay out;
     * it only locates the chapter; the intra-chapter offset is further computed by
     * [chapterChar] after layout.
     */
    fun chapterFromBookProgress(lengths: ChapterLengths, progress: Double): Int {
        val total = lengths.totalChars
        if (total <= 0L) return 0
        val target = (progress.coerceIn(0.0, 1.0) * total).toLong()
        var acc = 0L
        for (i in 0 until lengths.size) {
            if (target < acc + lengths.items[i]) return i
            acc += lengths.items[i]
        }
        return lengths.size - 1
    }

    /** Intra-chapter char offset: the whole-book progress landing point minus the cumulative chars before this chapter. */
    fun chapterChar(lengths: ChapterLengths, chapter: Int, progress: Double): Int {
        val total = lengths.totalChars
        if (total <= 0L) return 0
        val c = chapter.coerceIn(0, lengths.size - 1)
        val target = (progress.coerceIn(0.0, 1.0) * total).toLong()
        val rel = (target - lengths.precedingChars(c)).coerceIn(0L, lengths.chapterChars(c))
        return rel.toInt()
    }

    /** Builds a [ChapterLengths] from the spine (in spine order). */
    fun ofChapters(spine: List<MarkupElement>): ChapterLengths =
        ChapterLengths(LongArray(spine.size) { spine[it].textLength })
}