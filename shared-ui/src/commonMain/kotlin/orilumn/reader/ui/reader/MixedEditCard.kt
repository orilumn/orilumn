package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.InlineMarkup
import orilumn.reader.engine.revision.TableRowScope
import orilumn.reader.engine.revision.TextEditPart

/**
 * 审阅视图里「改过的表格 + 表格外的文字」一起选中时，一张卡要落**两套改法**（属于用户层：只做切分，
 * 不碰正文，也不猜表格结构）：
 *
 * - 表格那几行按**整张表**算（行列自洽、未选中的行原样拼回，见 [TableRowScope]）；
 * - 表格外的文字按普通区间算（与「只选文字」同一条路）。
 *
 * 一条修订装不下两种改法，所以卡片按「表格那一段」切成前 / 中 / 后三段，逐段规划、一次落库
 * （见 [TextEditPart]）。这里只负责**认行**：卡片里连续的那一段表格行就是表格段，别的都是文字。
 */
internal object MixedEditCard {

    /**
     * 把卡片切成提交段；null = 切不了（卡片里没有表格行，或表格行被文字隔成几段——
     * 这两种都不是「表 + 文字」的混合物，调用方就地提示、正文不动）。
     *
     * [segments] / [body] 是**解析后**的卡片（`InlineMarkup.parse`）：一行一段，段与段之间一个 `\n`。
     * [tableRange] 是那条整表修订在当前显示空间里的区间（表格段落库仍按这个整表区间），
     * [rowScope] 是读者拖到的那几行（null = 拖到了整张表，行数 / 列数都可以改）。
     *
     * 前 / 后两段即使卡片里一行不剩也照样给（读者把那段文字删光了 = 要删掉那段文字）。
     * 表格段不给空：卡片里一行表格都不剩时切不了（删整张表请只选中表格再删）。
     */
    fun parts(
        segments: List<InlineMarkup.Segment>,
        styles: List<InlineMarkup.StyleSpan>?,
        body: String,
        cardStart: Int,
        cardEnd: Int,
        tableRange: IntRange,
        rowScope: TableRowScope?,
    ): List<TextEditPart>? {
        val first = segments.indexOfFirst { it.table != null }
        if (first < 0) return null
        var last = first
        while (last + 1 < segments.size && segments[last + 1].table != null) last++
        // 表格行被文字隔成几段：不知道该按哪一段落笔，宁可不改。
        if (segments.subList(last + 1, segments.size).any { it.table != null }) return null
        // 卡片里表格前面还留着文字行、可正文里那一段是空的：位置对不上（多半是读者在表前插了行）。
        if (first > 0 && tableRange.first <= cardStart) return null
        if (last + 1 < segments.size && tableRange.last + 1 >= cardEnd) return null

        val starts = IntArray(segments.size) { -1 }
        var cursor = 0
        for (i in segments.indices) {
            val t = segments[i].text
            val at = if (t.isEmpty()) {
                cursor
            } else {
                body.indexOf(t, cursor).takeIf { it >= 0 } ?: return null
            }
            starts[i] = at
            cursor = at + t.length
        }
        fun textOf(fromLine: Int, toLineExclusive: Int): Pair<String, List<InlineMarkup.StyleSpan>?> {
            val a = if (fromLine < segments.size) starts[fromLine] else body.length
            val b = when {
                toLineExclusive >= segments.size -> body.length
                else -> maxOf(starts[toLineExclusive] - 1, a)
            }
            val text = if (b > a) body.substring(a, b) else ""
            val sliced = styles?.mapNotNull { sp ->
                val s = maxOf(sp.start, a)
                val e = minOf(sp.endExclusive, b)
                if (e > s) InlineMarkup.StyleSpan(s - a, e - a, sp.sig) else null
            }
            return text to sliced
        }

        val out = ArrayList<TextEditPart>(3)
        if (cardStart < tableRange.first) {
            val (text, spans) = textOf(0, first)
            out += TextEditPart(
                cardStart,
                tableRange.first,
                text,
                spans,
                segments.subList(0, first).takeIf { it.isNotEmpty() },
            )
        }
        val (tableText, tableSpans) = textOf(first, last + 1)
        out += TextEditPart(
            start = tableRange.first,
            endExclusive = tableRange.last + 1,
            text = tableText,
            styles = tableSpans,
            blocks = segments.subList(first, last + 1),
            rowScope = rowScope,
        )
        if (tableRange.last + 1 < cardEnd) {
            val (text, spans) = textOf(last + 1, segments.size)
            out += TextEditPart(
                tableRange.last + 1,
                cardEnd,
                text,
                spans,
                segments.subList(last + 1, segments.size).takeIf { it.isNotEmpty() },
            )
        }
        return out
    }
}
