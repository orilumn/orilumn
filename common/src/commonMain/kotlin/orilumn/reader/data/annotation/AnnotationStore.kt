package orilumn.reader.data.annotation

/**
 * 标注读写接口（共享层唯一入口）。数据库只是它的一个实现；将来若做「可导出的附件」，
 * 可在接口下再挂一个实现，不必改上层。
 *
 * 所有区间都是章内原书字符偏移，与 PageSlice / ReadingLocator 同口径。
 */
interface AnnotationStore {
    /** 本章全部标注，按起点、id 升序（同起点时先加的在前）。 */
    suspend fun annotationsForChapter(bookId: Long, chapter: Int): List<Annotation>

    /** 全书标注，按章序、章内起点、id 升序（标注清单用，不必为远章排版）。 */
    suspend fun annotationsForBook(bookId: Long): List<Annotation>

    /** 命中包含 [charOffset] 的最后一条（同起点时后加的优先）；没有则 null。 */
    suspend fun annotationAt(bookId: Long, chapter: Int, charOffset: Int): Annotation?

    /** 新增并返回带数据库 id 的记录。 */
    suspend fun addAnnotation(annotation: Annotation): Annotation

    /** 按 id 更新类型 / 颜色 / 批注文本；锚点（书/章/区间）不变。 */
    suspend fun updateAnnotation(annotation: Annotation)

    suspend fun deleteAnnotation(id: Long)

    suspend fun deleteAnnotationsForBook(bookId: Long)
}
