package orilumn.reader.data.bookmark

/**
 * 一条书签：锚点是「章号 + 章内原书字符区间」，与标注 / 阅读进度同源。
 *
 * 书签记的是**页首**那一个字，不是页号：换字号、换边距、旋转之后页数会变，
 * 但同一个字仍在同一处内容上，书签不会因为版面变化漂到别页去。
 *
 * [charEnd] 只用于取创建当时的原文摘录（[quote]），跳转只认 [charStart]。
 * 区间允许为空（页首落在图片等无字位置时），所以约束是 `charEnd >= charStart`，
 * 比标注的 `charEnd > charStart` 宽一档。
 */
data class Bookmark(
    val id: Long = 0,
    val bookId: Long,
    val chapter: Int,
    /** 页首字符（含，原书空间）。 */
    val charStart: Int,
    /** 页尾后一个字（不含，原书空间）；可为 [charStart]。 */
    val charEnd: Int,
    /**
     * 创建当时的原文摘录（清单显示）。取的是读者正在看的那一页文字，
     * 审阅痕迹与中西接缝上被画成零宽的空白都已摘掉；老数据可空。
     */
    val quote: String? = null,
    val createdAt: Long = 0,
) {
    init {
        require(charStart >= 0) { "charStart 不能为负：$charStart" }
        require(charEnd >= charStart) { "charEnd 不能小于 charStart：[$charStart, $charEnd)" }
    }
}
