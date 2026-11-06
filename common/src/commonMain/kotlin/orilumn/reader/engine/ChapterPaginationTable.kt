package orilumn.reader.engine

import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.io.Logger

/**
 * Persisted pagination table for a single chapter under a single layout parameter combination.
 *
 * Stored on disk at `<cache>/pagination/<bookId>/<chapterIndex>_<paramHash>.bin` (see
 * [PaginationCacheCodec.filename]). Reading is a plain deserialization (a few KB, < 1ms).
 * When the layout key (font size, line spacing, width/height, ...) changes, a new hash is
 * produced and the old file becomes orphaned.
 *
 * **本表只回答"页在哪"——不保存塑形结果。** 表是纯确定性的几何（字符偏移 + 块区间），随时可重算。
 * 至于"这一页画什么"（行文本/行几何/盒背景/插图槽位），由**当前页快照**单独保存：一本书只有
 * 一份、翻页即覆盖（`PageSnapshot` / `PageSnapshotStore`，在 `:engine-skia`）。两者分工明确：
 * 本表按（章 × 参数）多份存档，快照按书一份、只记最后那一页。
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
            // 写盘前契约（fail-fast）：调用方四处全部前查 isNotEmpty，空表/负数/非单调
            // 进来即上游 bug。以往照写坏表落盘，现在出生即炸。
            require(slices.isNotEmpty()) { "fromSlices empty slices ch=$chapterIndex" }
            require(totalBlocks >= 0 && totalChars >= 0) { "fromSlices negative ch=$chapterIndex blocks=$totalBlocks chars=$totalChars" }
            require(slices.zipWithNext().none { (a, b) -> b.charStart < a.charStart }) { "fromSlices non-monotonic ch=$chapterIndex" }
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
