package orilumn.reader.ui.reader

/**
 * 审阅空间文字里「段界痕迹」¶ 的剥离工具（用户层：只负责把排版字符还原成读者读的文字）。
 *
 * ¶ 是审阅视图专有的**普通排版字符**（被删掉的段界在合并段中间、新插的段界在上一行末尾，
 * 见 `ChapterRevisionMap.BREAK_MARK_CHAR`）。分页、选区、标注偏移都要算它，所以
 * [ReaderHost.textOf] 原样带着它返回。凡是读者要**看**或**带走**的文字（摘录、复制、
 * 修改卡初值）都得按本类摘掉，并把偏移一起回缩，免得 ¶ 漏进剪贴板、书签与正文。
 *
 * 层的边界：这里不碰排版、不改偏移的可信度，只是一次纯文本投影；
 * 排版空间（分页表、选区、装饰）永远用 [ReaderHost.textOf] 的原样文字。
 */
internal class ReviewBreakText(private val marks: IntArray) {

    /** 审阅空间偏移 → 去掉痕迹后的偏移（落在 ¶ 上时取它在投影里的位置）。 */
    fun plain(offset: Int): Int {
        if (marks.isEmpty() || offset <= 0) return offset
        var n = 0
        for (m in marks) {
            if (m < offset) n++ else break
        }
        return offset - n
    }

    /** 整章文字去掉痕迹（偏移口径随之变成 [plain]）。 */
    fun plainChapterText(text: String): String {
        if (marks.isEmpty()) return text
        val out = StringBuilder(text.length)
        var from = 0
        for (m in marks) {
            if (m >= text.length) break
            if (m < from) continue
            out.append(text, from, m)
            from = m + 1
        }
        out.append(text, from, text.length)
        return out.toString()
    }

    /** 块尾表跟着换到投影口径（`ends.last()` 仍是章尾，因此仍是去痕迹后的元数）。 */
    fun plainBlockEnds(ends: IntArray?): IntArray? {
        if (ends == null || ends.isEmpty() || marks.isEmpty()) return ends
        return IntArray(ends.size) { plain(ends[it]) }
    }

    /** 审阅空间 [start, endExclusive) 这段文字（不含痕迹）；块间不加换行，与 `textOf` 同口径。 */
    fun slice(text: String, start: Int, endExclusive: Int): String {
        val s = start.coerceIn(0, text.length)
        val e = endExclusive.coerceIn(s, text.length)
        if (marks.isEmpty()) return text.substring(s, e)
        val out = StringBuilder(e - s)
        var from = s
        for (m in marks) {
            if (m < from) continue
            if (m >= e) break
            out.append(text, from, m)
            from = m + 1
        }
        out.append(text, from, e)
        return out.toString()
    }

    companion object {
        val NONE = ReviewBreakText(IntArray(0))
    }
}

/** 本章的 ¶ 剥离器（正常视图下痕迹表为空，直接返回 [ReviewBreakText.NONE] 的等价物）。 */
internal fun ReaderHost.reviewBreakText(chapter: Int): ReviewBreakText {
    val marks = reviewBreakOffsets(chapter)
    return if (marks.isEmpty()) ReviewBreakText.NONE else ReviewBreakText(marks)
}
