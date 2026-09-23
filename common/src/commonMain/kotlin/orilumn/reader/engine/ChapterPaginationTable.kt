package orilumn.reader.engine

import orilumn.reader.engine.paging.PageSlice

/**
 * Persisted pagination table for a single chapter under a single layout parameter combination.
 *
 * Stored on disk at `<cache>/pagination/<bookId>/<chapterIndex>_<paramHash>.bin` (see
 * [PaginationCacheCodec.filename]). Reading is a plain deserialization (a few KB, < 1ms).
 * When the layout key (font size, line spacing, width/height, ...) changes, a new hash is
 * produced and the old file becomes orphaned.
 *
 * **Only the pagination table is persisted — never the shaping results.** The table is pure,
 * deterministic geometry (char offsets + block ranges) that can be regenerated from scratch.
 *
 * C1-1: moved verbatim from the app shell into common — the single source both the Android
 * adapter (`orilumn.reader.engine.PaginationCacheStore`) and the shared [PaginationCacheStore]
 * persist through [PaginationCacheCodec].
 *
 * @property chapterIndex Index in the spine.
 * @property paramHash Stable hash of the layout params that produced this table. Used both as
 *   part of the filename and as an integrity check at read time.
 * @property totalBlocks Number of block-level leaf elements in the chapter (pre-computed).
 * @property totalChars Total character count of the chapter body text.
 * @property pages One entry per output page. Index == page number within the chapter.
 */
data class ChapterPaginationTable(
    val chapterIndex: Int,
    val paramHash: Long,
    val totalBlocks: Int,
    val totalChars: Int,
    val pages: List<PageRecord>,
) {
    val totalPages: Int get() = pages.size

    /**
     * Per-page locator record. Four Ints — the table is deliberately a flat array of these so
     * serialization is dead simple and the whole file stays under ~32 KB even for a 10K-block
     * chapter (a few hundred pages × 16 bytes each).
     *
     * @property charStart Inclusive character offset this page begins at in the full chapter stream.
     * @property charEnd Exclusive character offset this page ends at.
     * @property blockStart Index of the first block that contributes text to this page.
     * @property blockEndExclusive One past the last contributing block (useful for incremental shaping).
     */
    data class PageRecord(
        val charStart: Int,
        val charEnd: Int,
        val blockStart: Int,
        val blockEndExclusive: Int,
    )

    /** Converts a list of [PageSlice] (the engine's runtime page representation) into this
     *  persistable table, computing the chapter-level totals and block ranges. */
    companion object {

        /** Converts runtime [PageSlice]s + block metadata into a full table ready to persist. */
        fun fromSlices(
            chapterIndex: Int,
            paramHash: Long,
            slices: List<PageSlice>,
            totalBlocks: Int,
            totalChars: Int,
        ): ChapterPaginationTable {
            val pages = slices.map { s ->
                PageRecord(
                    charStart = s.charStart,
                    charEnd = s.charEnd,
                    blockStart = s.blockStart,
                    blockEndExclusive = s.blockEndExclusive,
                )
            }
            return ChapterPaginationTable(
                chapterIndex = chapterIndex,
                paramHash = paramHash,
                totalBlocks = totalBlocks,
                totalChars = totalChars,
                pages = pages,
            )
        }
    }
}
