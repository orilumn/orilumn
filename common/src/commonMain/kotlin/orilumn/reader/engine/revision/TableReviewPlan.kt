package orilumn.reader.engine.revision

/**
 * 整表结构修订在**审阅视图**里的行级对照（属于排版层·上：纯逻辑，不碰 DOM / 排版）。
 *
 * 审阅视图的规矩是**原文一字不删**：删掉的字留在原位（红字 + 删除线），新写的字就地插入
 * （绿字）。表格不能按普通文字那样把整张表当一串字重排，所以这里按行 / 格生成一份对照：
 *
 * - 没改到的行原样保留（[Kind.KEEP]）；
 * - 删掉的行整行留在原位（[Kind.DELETE]，行文字全部标红）；
 * - 新加的行排在它该在的位置（[Kind.ADD]，行文字全部标绿）；
 * - 只改了格里的字、行形状没变（[Kind.CHANGE]）：旧行保留，格内按字对齐就地插入新字，
 *   被删的字留在原位标红，新写的字标绿；
 * - 行形状变了（增删格 / 改合并方式）：旧行整行标红，紧跟一条新行标绿。
 *
 * [rows] 是 DOM 里**实际排出来的行序**；[overlay] 是同一串文字在审阅空间里的对齐结果
 * （红绿区间、审阅坐标 ↔ 原书坐标）。重放按 [rows] 建树，修订表按 [overlay] 换算——
 * 两边必须用**同一份**计划，不能各算一遍。
 */
class TableReviewPlan(
    val rows: List<Row>,
    val overlay: ReviewOverlay.Plan,
) {
    enum class Kind {
        /** 旧行原样保留。 */
        KEEP,

        /** 旧行整行删除（红字 + 删除线）。 */
        DELETE,

        /** 读者新加的行（绿字）。 */
        ADD,

        /** 旧行保留，格内就地插入新字（红删绿增）。 */
        CHANGE,
    }

    /**
     * 一行在审阅空间里的身份。
     *
     * @property oldIndex 旧表行下标；`-1` = 这一行只属于新表（新加的行）。
     * @property newIndex 新表行下标；`-1` = 这一行只属于旧表（保留 / 删除）。
     * @property textLength 这一行在审阅空间的文字长度。
     * @property cellTexts [Kind.CHANGE] 专用：每格在审阅空间的文字（旧字 + 就地插入的新字）。
     */
    data class Row(
        val kind: Kind,
        val oldIndex: Int = -1,
        val newIndex: Int = -1,
        val textLength: Int,
        val cellTexts: List<String>? = null,
    )

    /** 一行原有的文字 + 格结构（[cells] 的偏移相对 [text]）。 */
    class RowInfo(val text: String, val cells: List<TableCellInfo>)
}

/** 把整表结构修订翻译成审阅视图的行级对照（规格见 [TableReviewPlan]）。 */
object TableReview {

    /**
     * @param oldRows 旧表的行（按 DOM 顺序，文字与格结构都要能对上）。
     * @param newRows 修订落库的新表行（[TextRevision.editedBlocks] 里的 `T…` / `t…` 短码）。
     * @param rowSources 新行来自旧表哪一行（`-1` = 新加）；老修订没存过时传 null，按内容对齐。
     * @return null = 行文字 / 格结构对不上，调用方退回「整表按结果重建、不画红绿」的老路。
     */
    fun plan(
        oldRows: List<TableReviewPlan.RowInfo>,
        newRows: List<TableReviewPlan.RowInfo>,
        rowSources: List<Int>?,
    ): TableReviewPlan? {
        if (oldRows.isEmpty()) return null
        if (oldRows.any { !covers(it) } || newRows.any { !covers(it) }) return null
        val sources = sourcesOf(oldRows, newRows, rowSources)
        val parts = ArrayList<TableReviewPlan.Row>(newRows.size + oldRows.size)
        val plans = ArrayList<ReviewOverlay.Plan>(newRows.size + oldRows.size)
        var oi = 0
        for (ni in newRows.indices) {
            val src = sources[ni]
            if (src < 0 || src < oi || src >= oldRows.size) {
                // 新加的行；来源不按序 / 重复时也按新行处理，绝不抢已经用过的旧行。
                val p = insert(newRows[ni].text) ?: return null
                parts += TableReviewPlan.Row(TableReviewPlan.Kind.ADD, newIndex = ni, textLength = p.length)
                plans += p
                continue
            }
            while (oi < src) {
                val p = delete(oldRows[oi].text) ?: return null
                parts += TableReviewPlan.Row(TableReviewPlan.Kind.DELETE, oldIndex = oi, textLength = p.length)
                plans += p
                oi++
            }
            val old = oldRows[src]
            val new = newRows[ni]
            if (old.text == new.text && sameShape(old.cells, new.cells)) {
                val p = ReviewOverlay.plan(old.text, old.text, IntArray(0)) ?: return null
                parts += TableReviewPlan.Row(TableReviewPlan.Kind.KEEP, oldIndex = src, textLength = p.length)
                plans += p
            } else if (sameShape(old.cells, new.cells)) {
                // 形状没变：逐格做「原文照排 + 新字就地插入」，被删的字留在格子里。
                val cellPlans = ArrayList<ReviewOverlay.Plan>(old.cells.size)
                val cellTexts = ArrayList<String>(old.cells.size)
                for (ci in old.cells.indices) {
                    val oc = old.cells[ci]
                    val nc = new.cells[ci]
                    val oldText = old.text.substring(oc.start, oc.endExclusive)
                    val newText = new.text.substring(nc.start, nc.endExclusive)
                    val p = ReviewOverlay.plan(oldText, newText, IntArray(0)) ?: return null
                    cellPlans += p
                    cellTexts += p.text
                }
                val p = ReviewOverlay.concat(cellPlans)
                parts += TableReviewPlan.Row(
                    TableReviewPlan.Kind.CHANGE,
                    oldIndex = src,
                    newIndex = ni,
                    textLength = p.length,
                    cellTexts = cellTexts,
                )
                plans += p
            } else {
                // 行列结构变了：旧行整行标红，紧跟一条新行标绿（结构差异没法逐格对上）。
                val d = delete(old.text) ?: return null
                parts += TableReviewPlan.Row(TableReviewPlan.Kind.DELETE, oldIndex = src, textLength = d.length)
                plans += d
                val a = insert(new.text) ?: return null
                parts += TableReviewPlan.Row(TableReviewPlan.Kind.ADD, newIndex = ni, textLength = a.length)
                plans += a
            }
            oi = src + 1
        }
        while (oi < oldRows.size) {
            val p = delete(oldRows[oi].text) ?: return null
            parts += TableReviewPlan.Row(TableReviewPlan.Kind.DELETE, oldIndex = oi, textLength = p.length)
            plans += p
            oi++
        }
        val overlay = ReviewOverlay.concat(plans)
        if (parts.sumOf { it.textLength } != overlay.length) return null
        return TableReviewPlan(parts, overlay)
    }

    private fun insert(text: String): ReviewOverlay.Plan? = ReviewOverlay.plan("", text, IntArray(0))

    private fun delete(text: String): ReviewOverlay.Plan? = ReviewOverlay.plan(text, "", IntArray(0))

    private fun covers(row: TableReviewPlan.RowInfo): Boolean {
        var at = 0
        for (c in row.cells) {
            if (c.start != at || c.endExclusive < c.start) return false
            at = c.endExclusive
        }
        return at == row.text.length
    }

    private fun sameShape(a: List<TableCellInfo>, b: List<TableCellInfo>): Boolean {
        if (a.size != b.size) return false
        return a.indices.all { i ->
            a[i].header == b[i].header &&
                a[i].rowspan == b[i].rowspan &&
                a[i].colspan == b[i].colspan
        }
    }

    /**
     * 新行 → 旧行下标。存过 `rowSources` 就按它（必须严格递增、不重复）；老修订没存过时
     * 按「行文字 + 格形状」做最长公共子序列对齐（没改过的行一定完全相同）。
     */
    private fun sourcesOf(
        oldRows: List<TableReviewPlan.RowInfo>,
        newRows: List<TableReviewPlan.RowInfo>,
        stored: List<Int>?,
    ): IntArray {
        if (stored != null && stored.size == newRows.size) {
            var prev = -1
            var ok = true
            for (s in stored) {
                if (s < -1 || s >= oldRows.size || (s >= 0 && s <= prev)) {
                    ok = false
                    break
                }
                if (s >= 0) prev = s
            }
            if (ok) return IntArray(stored.size) { stored[it] }
        }
        // 老修订没存过行来源：按「行文字 + 格形状」做同一套对齐（删中间一行也不会错位）。
        return alignTableRows(
            oldRows.map { it.text },
            oldRows.map { TableRowInfo(it.cells) },
            newRows.map { it.text },
            newRows.map { TableRowInfo(it.cells) },
        )
    }
}
