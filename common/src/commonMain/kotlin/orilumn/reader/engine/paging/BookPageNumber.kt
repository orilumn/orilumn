package orilumn.reader.engine.paging

/**
 * 全书累计页码（用户层「显示页码」开关的显示值，纯值模型，排版层不参与）。
 *
 * 口径：**全书从第一内容页起连续数**，封面（用户层前置页）不计页。
 *
 * @property index 本页在全书中的页码，1 起。
 * @property total 全书总页数；null = 还有章在当前参数下没有正典分页表，总数**不估**。
 *   未知就留白（只显示 [index]），宁可少一个数字，也不给读者一个会变的数。
 */
data class BookPageNumber(
    val index: Int,
    val total: Int?,
) {
    /** 页脚显示文本：总数已知 `n / m`，未知只写 `n`。 */
    val label: String get() = if (total != null) "$index / $total" else "$index"
}

/**
 * 由「本章页下标 + 各章页数」合成全书页码。
 *
 * 拆成纯函数是为了能脱离引擎单测：页数从哪来（磁盘表头 / 内存表 / 临时表）是引擎控制器
 * 的事，这里只做加和与「未知就不出数」的判定。
 *
 * @param chapter 本章章号（spine 下标）。
 * @param pageInChapter 本页在章内的下标，0 起。
 * @param chapterCount 全书章数。
 * @param pageCountOf 章号 → 该章页数；null = 该章当前参数下还没有正典分页。
 * @return 页码；本章或**它之前的任一章**页数未知时返回 null（页号会算错，不如不显示）。
 */
fun bookPageNumberOf(
    chapter: Int,
    pageInChapter: Int,
    chapterCount: Int,
    pageCountOf: (Int) -> Int?,
): BookPageNumber? {
    if (chapter !in 0 until chapterCount || pageInChapter < 0) return null
    var before = 0
    for (i in 0 until chapter) {
        before += pageCountOf(i) ?: return null
    }
    var total = before
    var complete = true
    for (i in chapter until chapterCount) {
        val n = pageCountOf(i)
        if (n == null) complete = false else total += n
    }
    return BookPageNumber(index = before + pageInChapter + 1, total = if (complete) total else null)
}
