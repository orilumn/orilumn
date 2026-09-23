package orilumn.reader.ui.shelf

/**
 * 书架条目：shared-ui 面向 UI 的书目模型（宿主数据层映射而来，纯数据；对应 app `data/book/Book` 的 UI 侧形态）。
 */
data class ShelfBook(
    val id: Long,
    val title: String,
    val author: String? = null,
    val filePath: String,
    val addedAt: Long = 0L,
    /** 封面本地引用（宿主可读的路径/uri）；null 表示无封面或需占位。 */
    val coverRef: String? = null,
    /** 最近一次打开阅读的时间戳（ms）；用于「最近阅读优先」排序。 */
    val readTime: Long? = null,
) {
    /**
     * 是否与「另一本书」算同一本：书名（忽略大小写）且作者一致。
     * null 作者按空串比较，避免「同名不同作者」或「缺作者」误判。
     */
    fun sameBookAs(otherTitle: String, otherAuthor: String?): Boolean =
        title.equals(otherTitle, ignoreCase = true) &&
            (author ?: "").equals(otherAuthor ?: "", ignoreCase = true)
}

/** 书架排序：加入时间 / 阅读时间 / 书名。 */
enum class ShelfSort { Added, Read, Name }

/** 书架视图：封面网格 ↔ 图片列表。 */
enum class ShelfView { Grid, CoverList }

/** 排序的展示文案（加入时间/阅读时间/书名）。 */
fun ShelfSort.label(): String = when (this) {
    ShelfSort.Added -> "加入时间"
    ShelfSort.Read -> "阅读时间"
    ShelfSort.Name -> "书名"
}

/** 排序循环切换：加入时间 → 阅读时间 → 书名 → 加入时间。 */
fun ShelfSort.next(): ShelfSort = when (this) {
    ShelfSort.Added -> ShelfSort.Read
    ShelfSort.Read -> ShelfSort.Name
    ShelfSort.Name -> ShelfSort.Added
}

/** 单本导入的摘要文案（纯逻辑，对齐原 MainActivity.importSummary）。 */
fun importSummary(ok: Int, fail: Int): String = when {
    ok == 0 && fail == 0 -> "未选择可导入的书籍"
    ok == 0 -> "导入失败：$fail 本未导入"
    fail == 0 -> "已加入书架 $ok 本"
    else -> "导入成功 $ok 本，失败 $fail 本"
}

/** 「跳过重复」时的摘要文案（对齐原 MainActivity.onOverwriteSkip）。 */
fun skipDuplicatesSummary(priorOk: Int, priorFail: Int, skipped: Int): String = buildString {
    append("导入成功 ")
    append(priorOk)
    append(" 本")
    if (priorFail > 0) {
        append("，失败 ")
        append(priorFail)
        append(" 本")
    }
    append("，跳过重复 ")
    append(skipped)
    append(" 本")
}