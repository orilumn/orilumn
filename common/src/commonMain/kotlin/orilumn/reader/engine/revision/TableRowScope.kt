package orilumn.reader.engine.revision

import orilumn.reader.data.revision.RevisionStyleSpan
import orilumn.reader.data.revision.TextRevision

/**
 * 审阅视图里「整表修订中的某几行」（属于排版层·上：纯逻辑，不碰 DOM / 排版）。
 *
 * 读者在审阅视图里拖选改过的表格中的一行时，卡片只列那一行：拖了哪一行就改哪一行。
 * 行号是**上一次改完的那张表**（`editedBlocks` 顺序）里的下标，含两端。
 *
 * 提交仍按**整张表**校验（行列要能铺成矩形网格、行数删到零就是删整张表），
 * 所以这里只记「哪几行」，未选中的行由提交侧从旧修订里原样拼回。
 */
data class TableRowScope(val revisionId: Long, val firstRow: Int, val lastRow: Int)

/**
 * 这几行在修改卡里的样子：文字（行之间用 `\n` 分隔）、每行短码、行内结构、每行格结构。
 *
 * 与整表卡片同源（[RevisionBlocks.cardSegments] + [TableRowInfo.wire]），只是切出其中几行，
 * 所以重开卡片时格内的行内样式、格界、合并方式与整表卡片完全一致。
 */
class TableRowCard(
    val text: String,
    val blocks: List<String>,
    val styles: List<RevisionStyleSpan>?,
    val tables: List<TableRowInfo>,
)

/**
 * 一条整表修订里「上一次改完的那张表」的行（一行一段，带格结构）。
 *
 * null = 不是整表结构修订（首行短码不是大写 `T`）/ 落库数据对不上（格长度与文字不符）。
 */
fun tableRowsOfRevision(r: TextRevision): List<InlineMarkup.Segment>? {
    if (r.editedBlocks.firstOrNull()?.startsWith("T") != true) return null
    val segments = RevisionBlocks.cardSegments(r.editedText, r.editedBlocks) ?: return null
    if (segments.isEmpty() || segments.any { it.table == null }) return null
    return segments
}

/**
 * 整表修订 [r] 里第 [scope] 这几行在修改卡里的内容；null = 行号越界 / 数据对不上。
 *
 * 行内结构按整卡文字里的行起点平移过来（卡片文字行与行之间隔一个 `\n`），
 * 于是这几行显示出来的标记与整表卡片里那几行逐字一致。
 */
fun tableRowCardOf(r: TextRevision, scope: TableRowScope): TableRowCard? {
    val rows = tableRowsOfRevision(r) ?: return null
    val from = scope.firstRow
    val to = scope.lastRow + 1
    if (from < 0 || to < from || to > rows.size) return null
    val subset = rows.subList(from, to)
    val text = subset.joinToString("\n") { it.text }
    // 先行在整卡文字里占的偏移：前面各行的文字 + 每行一个换行符。
    val shift = rows.take(from).sumOf { it.text.length } + from
    val stored = r.editedStyles
    val styles = stored?.mapNotNull { sp ->
        val start = maxOf(sp.start - shift, 0)
        val end = minOf(sp.endExclusive - shift, text.length)
        if (end > start) RevisionStyleSpan(start, end, sp.style) else null
    }
    val blocks = ArrayList<String>(subset.size + 4)
    for ((i, row) in subset.withIndex()) {
        val table = row.table ?: return null
        blocks += table.wire(wholeTable = i == 0)
        // 格内硬换行（`<br>`）：卡片里每多一行就多一个续行短码，重开时才能原样拼回这一行。
        repeat(row.text.count { it == '\n' }) { blocks += RevisionBlocks.CONTINUATION }
    }
    return TableRowCard(text, blocks, styles, subset.mapNotNull { it.table })
}
