package orilumn.reader.data.book

/**
 * A book's reading progress.
 *
 * S34a 从 `app` 迁入 common（去 Room 注解；见 [orilumn.reader.data.db.LibraryDb] 进度 API）。
 *
 * Precise restore relies on [locator] (foliate's lastLocation JSON).
 * [chapter] takes lastLocation.section.current (chapter index), and [progress] is the whole-book progress
 * (0..1); the two are used for shelf display and lightweight conversion, restore is authoritative by [locator].
 */
data class BookReadingState(
    val bookId: Long,
    val chapter: Int,
    val target: String? = null,
    val progress: Double = 0.0,
    /** JSON serialization of foliate `view.lastLocation`; passed back verbatim to `init({ lastLocation })` on reopen for precise location. */
    val locator: String? = null,
    val updatedAt: Long = 0L,
)
