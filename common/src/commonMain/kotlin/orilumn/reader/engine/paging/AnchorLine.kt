package orilumn.reader.engine.paging

/**
 * 锚点字符 → 行：**最后一个「行首 ≤ 锚点」的行**。
 *
 * 为什么不是「`start <= c < end` 的行」：章的字符空间不是连续铺满的 ——
 *  - **空行/空块**（页首插图、空容器）的 `start == end`，其字符足迹为 0，任何半开区间都不收它；
 *  - 块与块之间还有未绘制字符的空隙。
 * 旧扫描一遇这两种情况就「找不到」，再退到末行兜底 ⇒ 落位跳到本章最后一页
 * （真机旋转实测：锚点 3583 落到 11057）。本函数是全函数：行首非递减，总能取到一行，
 * 空行照旧命中自己（用户口径：页首是块元素时，锚点就是这个元素本身）。
 *
 * 越界（锚点 ≥ 章尾）也落在末行，不抛、不越界。
 */
fun anchorLineOf(layout: BookLayout, anchorChar: Int): Int {
    val n = layout.lineCount
    if (n <= 0) return 0
    var lo = 0
    var hi = n - 1
    var best = 0
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (layout.getLineStart(mid) <= anchorChar) { best = mid; lo = mid + 1 } else hi = mid - 1
    }
    return best
}

/**
 * 含锚点的磁盘表页：页首行不晚于锚点行的**最后一页**。
 *
 * 口径（总则 §2「刷新显示当前锚点所在页面」）：落位页必须**含**锚点，不是「页首最近」——
 * 锚点靠近页尾时，「最近」会选中下一页，把锚点甩到屏幕外（真机调参实测 5024 → 5151）。
 * 页恒按行连续切分，所以「含」= 最后一个 `firstLine <= anchorLine` 的规范页；
 * 锚点行正好是页首时留在这一页（并列不得前进，否则等于替用户翻了一页）。
 *
 * @param slices 磁盘表的页切片；`firstLine < 0` 的非规范页（旧表/临时产物）不参与落位。
 */
fun anchoredDiskPage(layout: BookLayout, slices: List<PageSlice>, anchorChar: Int): PageSlice? {
    if (slices.isEmpty()) return null
    val canonical = slices.filter { it.firstLine >= 0 }
    if (canonical.isEmpty()) return slices.firstOrNull()
    if (layout.lineCount <= 0) return canonical.firstOrNull()
    val anchorLine = anchorLineOf(layout, anchorChar)
    var best: PageSlice? = null
    for (s in canonical) {
        if (s.firstLine <= anchorLine) best = s else break
    }
    return best ?: canonical.first()
}
