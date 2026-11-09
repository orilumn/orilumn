package orilumn.reader.engine

import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.io.Logger

/**
 * Persisted pagination table for a single chapter under a single layout parameter combination.
 *
 * Stored on disk at `<cache>/pagination/<bookId>/<chapterIndex>_<paramHash>[_r<contentRev>].bin`
 * (see [PaginationCacheCodec.filename]). Reading is a plain deserialization (a few KB, < 1ms).
 * When the layout key (font size, line spacing, width/height, ...) changes, a new hash is
 * produced and the old file becomes orphaned; 正文修订（含审阅空间）换的是内容版本，落在另一个文件名上。
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
 * @property contentRev 本章正文修订的内容版本（`ChapterRevisionMap.contentRev`；0 = 无修订）。
 *   修订只影响被改的那一章，所以它是**章级**失效判据：别的章的表照常命中。
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
    /** 本章正文修订的内容版本（0 = 无修订）；见类 KDoc。 */
    val contentRev: Long = 0L,
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

    /**
     * 正文只换了字、**没有增减行**时，把表的字符偏移整体平移 [delta]（内容版本另行换新）。
     *
     * 前提是改动落在第 [pageIndex] 页之内（该页页首之前的字一个没动）：页界由「行」决定，
     * 每段行数没变 ⇒ 每页装的还是同一批行、页数也不变，只是改动之后的字整体挪了地方。
     * 于是：
     * - 第 `pageIndex` 页**页首不动**（改动在它之后），页尾平移 [delta]；
     * - 之后再每一页的页首 / 页尾都平移 [delta]；
     * - 之前的页一个字都不动。
     *
     * 用法见 `BookDocumentController.reflowAfterContentChange`：平移后的表要与「重塑出来的页界」
     * 逐页对得上才会被留用，对不上就整章重排。
     */
    fun shiftCharsFrom(pageIndex: Int, delta: Int): ChapterPaginationTable {
        if (delta == 0 || pages.isEmpty()) return this
        val at = pageIndex.coerceIn(0, pages.lastIndex)
        val shifted = pages.mapIndexed { i, p ->
            when {
                i < at -> p
                i == at -> p.copy(charEnd = p.charEnd + delta)
                else -> p.copy(charStart = p.charStart + delta, charEnd = p.charEnd + delta)
            }
        }
        return copy(pages = shifted, totalChars = totalChars + delta)
    }

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
            contentRev: Long = 0L,
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
                contentRev = contentRev,
            )
        }
    }
}
