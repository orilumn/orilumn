package orilumn.reader.engine.revision

/**
 * 表格一行在正文修改卡里的结构（规格见 `docs/正文修改-块级标记.md` §1.1）。
 *
 * 表格行叶在章字符流里是「各格文字按序拼接」（没有格分隔符，见 `NormalFlowLayout.leafStreamText`）。
 * 卡片不能只显示这串字，否则读者看不出格界；所以给每行带一份格结构，显示成 markdown 行
 * （`| a | b |`），有 `rowspan` / `colspan` 或格内有硬换行时退回基本 HTML 标签
 * （`<tr><td>a</td><td>b</td></tr>`）。
 *
 * 格边界是**相对本行文字**的偏移；总长必须等于行文字长度（不一致时调用方按纯文本处理，
 * 绝不猜边界）。
 */
data class TableRowInfo(
    val cells: List<TableCellInfo>,
    /** true = 用基本 HTML 标签显示（合并格 / 格内换行）；false = markdown 管道行。 */
    val html: Boolean = false,
) {
    /** 本行文字长度（最后一格的终点）。 */
    val textLength: Int get() = cells.lastOrNull()?.endExclusive ?: 0

    /**
     * 格结构是否正好盖住 [text]：格数、各格起点与总长逐项对齐。
     *
     * 对不上时调用方必须按纯文本处理（绝不猜边界）：老修订 / 手改坏的数据里常有
     * 「格长度之和与行文字不等」的情况。
     */
    fun covers(text: String): Boolean {
        if (cells.isEmpty() || textLength != text.length) return false
        var at = 0
        for (c in cells) {
            if (c.start != at || c.endExclusive < c.start) return false
            at = c.endExclusive
        }
        return at == text.length
    }

    /**
     * 结构是否相同（只比格数、表头、合并数与各格长度；用于卡片回显的往返校验）。
     *
     * 显示方式（[html]）不参与比较：它是从格结构**推出来**的（有合并格 / 格内有硬换行才走
     * HTML），同一份格结构两次显示本来就该得到同一个结果，拿它当判据只会把相等判成不等。
     *
     * @param ignoreHeader 不比较表头。markdown 管道行写不出「哪一格是 `<th>`」，卡片回验时
     *   表头只能忽略（提交时由 [orilumn.reader.engine.revision.TextEditPlan] 按原位补回）。
     */
    fun sameStructure(other: TableRowInfo?, ignoreHeader: Boolean = false): Boolean {
        if (other == null) return false
        if (cells.size != other.cells.size) return false
        return cells.indices.all { i ->
            val a = cells[i]
            val b = other.cells[i]
            (ignoreHeader || a.header == b.header) &&
                a.rowspan == b.rowspan &&
                a.colspan == b.colspan &&
                (a.endExclusive - a.start) == (b.endExclusive - b.start)
        }
    }

    /**
     * **形状**是否相同：只比格数、表头与合并方式，**不比格里的文字长度**。
     *
     * 提交时按它校验（读者就是在格子里改字，长度当然会变）；长度只用来在重放时
     * 把新文字落回各自的格。列数、表头、合并方式一变就不是这张卡能做的事了。
     *
     * @param ignoreHeader 同 [sameStructure]：管道行不写表头。
     */
    fun sameShape(other: TableRowInfo?, ignoreHeader: Boolean = false): Boolean {
        if (other == null) return false
        if (cells.size != other.cells.size) return false
        return cells.indices.all { i ->
            val a = cells[i]
            val b = other.cells[i]
            (ignoreHeader || a.header == b.header) && a.rowspan == b.rowspan && a.colspan == b.colspan
        }
    }

    /**
     * 落进修订的「每行块形态」短码（`editedBlocks`）：`t` + 显示方式 + 各格
     * 「长度,表头,rowspan,colspan」，用 `;` 分隔。
     *
     * @param wholeTable 首行短码用大写 `T`：这条修订描述的是**整张表**（行数、每行格数都可以
     *   与原来不同），重放按它整表重建。其余行仍用 `t`。
     */
    fun wire(wholeTable: Boolean = false): String = buildString {
        append(if (wholeTable) 'T' else 't')
        append(if (html) 'h' else 'm')
        for (c in cells) {
            append(';')
            append(c.endExclusive - c.start).append(',')
            append(if (c.header) '1' else '0').append(',')
            append(c.rowspan).append(',')
            append(c.colspan)
        }
    }

    companion object {
        /**
         * [wire] 的逆过程，总长由各格长度累加（重放侧只拿得到短码，不需要外部文字长度）。
         * 长度与调用方手里的文字对不上时，调用方自己用 [covers] 判。
         */
        fun fromWire(wire: String): TableRowInfo? {
            if (wire.length < 2 || (wire[0] != 't' && wire[0] != 'T')) return null
            val html = when (wire[1]) {
                'h' -> true
                'm' -> false
                else -> return null
            }
            if (wire.length == 2) return null
            val cells = ArrayList<TableCellInfo>()
            var start = 0
            for (part in wire.substring(3).split(';')) {
                if (part.isEmpty()) continue
                val f = part.split(',')
                if (f.size != 4) return null
                val len = f[0].toIntOrNull() ?: return null
                if (len < 0) return null
                val end = start + len
                cells.add(
                    TableCellInfo(
                        start = start,
                        endExclusive = end,
                        header = f[1] == "1",
                        rowspan = f[2].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        colspan = f[3].toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    ),
                )
                start = end
            }
            if (cells.isEmpty()) return null
            return TableRowInfo(cells, html)
        }

        /** [wire] 的逆过程；[textLength] 对不上（老数据 / 手改坏）返回 null。 */
        fun fromWire(wire: String, textLength: Int): TableRowInfo? =
            fromWire(wire)?.takeIf { it.textLength == textLength }
    }
}

/** 表格里的一个格（偏移相对本行文字）。 */
data class TableCellInfo(
    val start: Int,
    val endExclusive: Int,
    val header: Boolean,
    val rowspan: Int = 1,
    val colspan: Int = 1,
)

/**
 * 表格行列结构的自洽校验（正文修改卡提交整表结构时用）。
 *
 * 读者可以删行 / 加行 / 删整表，也可以每行增删格、改合并方式；**只要整张表还能铺成一个
 * 矩形网格**就放行。判据只有一条：按 HTML 表格的排布规则逐行落格，
 *
 * - 总列数取首行的 `colspan` 之和，所有行都必须正好铺满；
 * - 被上方 `rowspan` 占住的列不能再放格（重叠），也不能留下空格（空洞）；
 * - `rowspan` 不能越过末行（表已经结束却还有格往下占）。
 *
 * 0 行是合法的（删掉整张表）。不看文字、不看显示方式：纯结构。
 *
 * 属于排版层（上）：纯逻辑，可 JVM 单测。
 */
object TableGrid {

    /** 这张表的列数；结构不自洽返回 -1（0 行返回 0）。 */
    fun columns(rows: List<TableRowInfo>): Int {
        if (rows.isEmpty()) return 0
        if (rows.any { it.cells.isEmpty() }) return -1
        val width = rows[0].cells.sumOf { it.colspan }
        if (width <= 0) return -1
        // cover[c] = 这一列还被上方格占着几行（含当前行）；每行进完减一。
        val cover = IntArray(width)
        for (row in rows) {
            val filled = BooleanArray(width)
            for (c in 0 until width) if (cover[c] > 0) filled[c] = true
            var col = 0
            for (cell in row.cells) {
                if (cell.colspan <= 0 || cell.rowspan <= 0) return -1
                while (col < width && filled[col]) col++
                if (col + cell.colspan > width) return -1
                for (k in col until col + cell.colspan) {
                    if (filled[k]) return -1
                    filled[k] = true
                }
                if (cell.rowspan > 1) {
                    // 记「含当前行」的行数，本行进完统一减一；下几行才正好剩 rowspan-1 行。
                    for (k in col until col + cell.colspan) {
                        if (cover[k] < cell.rowspan) cover[k] = cell.rowspan
                    }
                }
                col += cell.colspan
            }
            for (c in 0 until width) if (!filled[c]) return -1
            for (c in 0 until width) if (cover[c] > 0) cover[c]--
        }
        // 还有格占着下一行 = rowspan 越过末行。
        if (cover.any { it > 0 }) return -1
        return width
    }

    /** 网格是否自洽（0 行 = 删表，合法）。 */
    fun consistent(rows: List<TableRowInfo>): Boolean = columns(rows) >= 0
}
