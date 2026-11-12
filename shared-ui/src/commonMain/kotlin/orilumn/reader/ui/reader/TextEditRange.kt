package orilumn.reader.ui.reader

import orilumn.reader.engine.revision.TextEditBlock

/**
 * 表格行只按**整行**进卡：选区只碰到半行时，把区间扩到碰到的那几行的整行。
 *
 * 不扩到整张表（真机复报「我只选了两行，卡片里却列出整张表格」）：读者选几行就只改几行，
 * 这一档只能改格里的字，行列结构不能动。要改整张表的结构（删行 / 加行 / 删表 / 增删列），
 * 得把**整张表**都选上（表首到表尾）——提交侧按「选区正好等于整表区间」认整表结构修订。
 *
 * @return 扩后的区间；没有碰到表格行时原样返回 [from] until [to]。
 */
internal fun expandTableRowsForEdit(blocks: List<TextEditBlock>, from: Int, to: Int): IntRange {
    var start = from
    var end = to
    for (block in blocks) {
        if (block.table == null) continue
        // 只认压着这次选区的行：块表若还带着选区外的行，不能把它们也扩进来。
        if (block.endExclusive <= from || block.start >= to) continue
        if (block.start < start) start = block.start
        if (block.endExclusive > end) end = block.endExclusive
    }
    return start until end
}
