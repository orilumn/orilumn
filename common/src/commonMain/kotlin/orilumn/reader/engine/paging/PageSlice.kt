package orilumn.reader.engine.paging

/**
 * Locating description of a page (char region + line region).
 *
 * Using a char region rather than pixel coordinates keeps the mapping to the source text stable
 * across the vertical displacement of a whole chapter laid out at once, and leaves compatible
 * room for future vertical text (which also slices by char order, just with a different drawing
 * direction).
 *
 * @property charStart page start char offset (inclusive).
 * @property charEnd page end char offset (exclusive); for full-chapter layout of body chapters, == the start of the line following this page's last line.
 * @property firstLine index of this page's first line.
 * @property lastLineExclusive this page's last line index (exclusive); i.e. this page covers `firstLine until lastLineExclusive`.
 * @property kind page type, see [Kind].
 */
data class PageSlice(
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val firstLine: Int = 0,
    val lastLineExclusive: Int = 0,
    val kind: Int = Kind.TEXT,
    /** Index of the first block that contributes text to this page. Filled by the incremental
     *  layout path; -1 means "not known" (full-chapter layout or legacy path). */
    val blockStart: Int = -1,
    /** One past the last block that contributes text to this page. -1 means "not known". */
    val blockEndExclusive: Int = -1,
) {
    val empty: Boolean get() = charEnd <= charStart

    object Kind {
        /** Ordinary body-chapter page. */
        const val TEXT = 0
        /** Whole-book cover sentinel page (bitmap provided by [orilumn.reader.engine.CoverDecoder]; does not use StaticLayout). */
        const val COVER = 1
    }
}