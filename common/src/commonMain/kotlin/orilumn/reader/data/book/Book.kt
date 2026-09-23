package orilumn.reader.data.book

/**
 * A book on the shelf.
 *
 * S33 从 `app` 迁入 common（去 Room 注解；SQLDelight 生成代码与本模型同字段映射，
 * 见 [orilumn.reader.data.db.LibraryDb]）。
 *
 * Metadata + local book file location — `filePath` 是 EPUB 私有库副本的绝对路径
 * （SAF 选择后拷贝入私有目录；桌面为 `~/.orilumn/books/` 副本）。
 */
data class Book(
    val id: Long = 0,
    val title: String,
    val author: String? = null,
    val filePath: String,
    val addedAt: Long = 0L,
    /** Local private path of the cover image (extracted and cached from the EPUB on import); null when there is no cover. */
    val coverPath: String? = null,
    /** Timestamp of the most recent open-to-read (ms); used for sorting "most recently read first". */
    val readTime: Long? = null,
) {
    /**
     * Whether another book counts as "the same book": both title (case-insensitive) and author match.
     * A null author is treated as an empty string for comparison, avoiding misjudging "same title different
     * author" or "missing author".
     */
    fun sameBookAs(otherTitle: String, otherAuthor: String?): Boolean =
        title.equals(otherTitle, ignoreCase = true) &&
            (author ?: "").equals(otherAuthor ?: "", ignoreCase = true)
}
