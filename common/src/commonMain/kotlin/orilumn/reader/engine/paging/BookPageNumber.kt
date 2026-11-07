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
 * 各章首页在全书中的页码（章号 → 1 起的页码；0 = 还不可算）。
 *
 * 目录面板用：**只要能算就显示**——第 0 章排完就能给出第 0 章；第 1 章也排完就再给出第 1 章……
 * 一旦遇到第一个「页数未知」的章就停：它后面的章首页页码依赖它，算了也是错的（不估）。
 *
 * 注意停的位置：**未知的那一章自己的首页仍是算得出来的**（它只需要前面各章的页数），所以先写
 * 首页页码、再判页数停表；只有它后面的章才不可算。
 *
 * @param chapterCount 全书章数。
 * @param pageCountOf 章号 → 该章页数；null = 当前参数下还没有正典分页。
 */
fun chapterStartPagesOf(chapterCount: Int, pageCountOf: (Int) -> Int?): IntArray {
    val out = IntArray(chapterCount)
    var acc = 0
    for (i in 0 until chapterCount) {
        out[i] = acc + 1
        val n = pageCountOf(i) ?: break
        acc += n
    }
    return out
}

/** 全书总页数；有一章页数未知就返回 null（半本书的和不是「共几页」）。 */
fun bookTotalPagesOf(chapterCount: Int, pageCountOf: (Int) -> Int?): Int? {
    var acc = 0
    for (i in 0 until chapterCount) acc += pageCountOf(i) ?: return null
    return acc
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

/**
 * 全书第 [bookPageIndex] 页（0 起）落在哪一章、章内第几页（0 起）。
 *
 * 进度条「有完整页码时按页」用：先把手指的 0..1 换成全书页下标，再反查章与章内页号。
 * 目标页**之前**的任一章页数未知、或下标越界，返回 null——调用方应先确认整本页数齐全
 * （缺章时按章定位）。0 页的空章不占页号，自然跳过。
 */
fun chapterPageAtBookPage(
    chapterCount: Int,
    bookPageIndex: Int,
    pageCountOf: (Int) -> Int?,
): Pair<Int, Int>? {
    if (bookPageIndex < 0) return null
    var acc = 0
    for (i in 0 until chapterCount) {
        val n = pageCountOf(i) ?: return null
        if (bookPageIndex < acc + n) return i to (bookPageIndex - acc)
        acc += n
    }
    return null
}
