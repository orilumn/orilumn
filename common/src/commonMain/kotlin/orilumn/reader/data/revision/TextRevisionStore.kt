package orilumn.reader.data.revision

/**
 * 正文修订读写接口（共享层唯一入口）。数据库只是它的一个实现。
 *
 * 所有区间都是章内**原书**字符偏移，与标注 / PageSlice / ReadingLocator 同口径。
 * 写操作必须成对：一次提交里被吸收的旧修订删除与新修订插入在同一事务内完成，
 * 避免中途出现半截重叠（方案 §8.4 第 7 步）。
 */
interface TextRevisionStore {
    /** 本章修订，按起点、id 升序。 */
    suspend fun textRevisionsForChapter(bookId: Long, chapter: Int): List<TextRevision>

    /** 全书修订，按章序、章内起点、id 升序（开书灌入控制器 / 审阅视图用）。 */
    suspend fun textRevisionsForBook(bookId: Long): List<TextRevision>

    /**
     * 同一事务内删掉 [deletedIds]、插入 [inserted]，返回带数据库 id 的新记录。
     * 这是提交一次正文修改唯一的写入路径（跨段时 [inserted] 有多条）。
     */
    suspend fun replaceTextRevisions(
        deletedIds: List<Long>,
        inserted: List<TextRevision>,
    ): List<TextRevision>

    /**
     * 给加清单快照之前写下的老修订补两行文字（一次性迁移），**只在两列仍为空时**写入：
     * 与读者正在提交的新修订竞争时不会覆盖新快照。
     */
    suspend fun fillLegacyRevisionSnapshot(id: Long, originalText: String, editedText: String)

    /** 删除一条修订（编辑清单里逐行删除）。删除后该区间恢复原书文字。 */
    suspend fun deleteTextRevision(id: Long)

    suspend fun deleteTextRevisionsForBook(bookId: Long)
}
