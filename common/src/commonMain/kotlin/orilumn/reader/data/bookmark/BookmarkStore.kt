package orilumn.reader.data.bookmark

/**
 * 书签读写接口（共享层唯一入口）。数据库只是它的一个实现，
 * 与 [orilumn.reader.data.annotation.AnnotationStore] 同一分层口径。
 *
 * 所有区间都是章内原书字符偏移，与 PageSlice / ReadingLocator 同口径。
 */
interface BookmarkStore {
    /** 全书书签，按章序、章内页首升序（书签清单用）。 */
    suspend fun bookmarksForBook(bookId: Long): List<Bookmark>

    /** 取某一页已有的书签（同章同页首）；没有则 null。用于「同一页不重复加」。 */
    suspend fun bookmarkAtPage(bookId: Long, chapter: Int, charStart: Int): Bookmark?

    /**
     * 新增并返回带数据库 id 的记录。
     *
     * 同一页已有书签时**直接返回已有那条**，不重复插入：连点两次「添加当前页」不该在清单里
     * 长出两条一模一样的记录；换过版面后页首挪位，旧书签只要落在新页覆盖的区间里也算同一页。
     */
    suspend fun addBookmark(bookmark: Bookmark): Bookmark

    suspend fun deleteBookmark(id: Long)

    suspend fun deleteBookmarksForBook(bookId: Long)
}
