package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt

/**
 * Pure 2D table model (P2-C): resolves a `table` subtree into a grid of cells honoring
 * `colspan`/`rowspan`, assigns equal-width auto columns, and computes each row's `[start,start+len)`
 * char range so the engine's linear global char stream stays coherent while a row is drawn as a grid.
 *
 * 本模型自身**不做文本度量**（纯 JVM、可单测）：列宽所需的单元格 max/min-content 由调用方
 * （[orilumn.reader.engine.laying.NormalFlowLayout.tableCellPref]，同一断行器真测）喂进来，
 * 重/轻两路共用 [autoColumnLayout] / [columnLayout] / [resolveRowHeights] 三个几何单源。
 */
class TableGridModel(
    /** Number of columns across the whole table (max col+colspan seen). */
    val columnCount: Int,
    /** The table's rows in document order (each with its cell layout). */
    val rows: List<Row>,
    /** Total char length of the whole table (sum of all rows' ranges). */
    val totalChars: Int,
    /** First direct `caption` child, if any (laid out as a full-width leaf above/below the rows). */
    val caption: MarkupElement? = null,
) {

    /** One table row: its cells in left-to-right order plus the row's own `[startChar, startChar+len)` range. */
    class Row(
        val el: MarkupElement,
        val cells: List<Cell>,
        val startChar: Int,
        val len: Int,
    )

    /** One cell: its markup, grid anchor + spans, and its char range **within its row**. */
    class Cell(
        val el: MarkupElement,
        val row: Int,
        val col: Int,
        val colSpan: Int,
        val rowSpan: Int,
        /** Cell's char offset within its row (0-based). */
        val startChar: Int,
        /** Cell's char length (its subtree `textLength`; may be 0 for empty cells). */
        val len: Int,
    ) {
        val isHeader: Boolean get() = el.tag == "th"
    }

    /** 表外盒几何：border-box 宽/左缘/内容宽（重/轻两路单源，见 [tableOuterGeometry]）。 */
    class TableOuterGeometry(val outerW: Int, val tableLeft: Int, val contentW: Int)

    /** auto 布局的单元格内容需求（列锚＋跨度＋首选/最小 px，均 ≥0；specified 为指定宽下限）。 */
    class CellPref(val col: Int, val colSpan: Int, val pref: Float, val min: Float, val specified: Float = 0f)

    /** 单元格对行高的贡献：纵向跨度＋单元格自身外高（内容＋padding＋border，含 `border-spacing` 前）。 */
    class CellHeight(val rowSpan: Int, val heightPx: Int)

    companion object {
        /**
         * 表外盒几何单源（重/轻两路共用）：指定宽（`%` 解容器/`px`）否则占满容器；
         * `margin auto` 定横向偏移（左右皆 auto 居中，单侧 auto 顶另一边；非 auto 边距照常生效）。
         *
         * @param innerW 容器内容宽（表 margin 盒的安置域）；@param left 容器内容左缘。
         */
        fun tableOuterGeometry(innerW: Int, left: Int, style: ComputedStyle): TableOuterGeometry {
            val outerW = (style.widthPct?.let { innerW * it / 100f } ?: style.widthPx ?: innerW.toFloat())
                .roundToInt().coerceAtLeast(1)
            // 注意：调用方传进的 left 已含非 auto margin-left（容器流通用规则），此处只补 auto 份；
            // auto 值在级联已按 0 计入 margin，故 usedML/MR 只用于剩余空间计算。
            val usedML = if (style.marginLeftAuto) 0f else style.margin.left
            val usedMR = if (style.marginRightAuto) 0f else style.margin.right
            val remaining = innerW - usedML - usedMR - outerW
            val extra = when {
                style.marginLeftAuto && style.marginRightAuto -> remaining.coerceAtLeast(0f) / 2f
                style.marginLeftAuto -> remaining
                else -> 0f
            }
            val tableLeft = left + extra.roundToInt()
            val contentW = (outerW - (style.border.horizontal + style.padding.horizontal).roundToInt()).coerceAtLeast(1)
            return TableOuterGeometry(outerW, tableLeft, contentW)
        }

        /**
         * Builds the model for a `table` [MarkupElement]. Rows are the `tr` descendants (one level of
         * `thead`/`tbody`/`tfoot` unwrapped); cells are the `td`/`th` children of each row, assigned to
         * the first free column (honoring prior `rowspan`s via an occupancy mask) and reserving
         * `colspan` columns for `rowspan` rows.
         */
        fun build(table: MarkupElement): TableGridModel {
            val rowsEl = collectRows(table)
            // occupancy[r][c] = true when column c at row r is already taken (by an earlier cell's span).
            val occupancy = ArrayList<BooleanArray>()
            val rows = ArrayList<Row>()
            var totalChars = 0
            for ((rowIndex, rowEl) in rowsEl.withIndex()) {
                ensureRow(occupancy, rowIndex)
                val cells = ArrayList<Cell>()
                var cursorChar = 0
                for (cellEl in rowEl.children) {
                    if (cellEl.tag != "td" && cellEl.tag != "th") continue
                    val colSpan = (cellEl.attrs["colspan"]?.trim()?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val rowSpan = (cellEl.attrs["rowspan"]?.trim()?.toIntOrNull() ?: 1).coerceAtLeast(1)
                    val col = firstFree(occupancy, rowIndex, colSpan)
                    val needRows = rowIndex + rowSpan
                    ensureRow(occupancy, needRows - 1)
                    for (r in rowIndex until needRows) {
                        ensureWidth(occupancy, r, col + colSpan)
                        for (c in col until col + colSpan) occupancy[r][c] = true
                    }
                    val len = cellEl.textLength.toInt()
                    cells.add(Cell(cellEl, rowIndex, col, colSpan, rowSpan, cursorChar, len))
                    cursorChar += len
                }
                rows.add(Row(rowEl, cells, totalChars, cursorChar))
                totalChars += cursorChar
            }
            val columnCount = rows.asSequence().flatMap { it.cells.asSequence() }.map { it.col + it.colSpan }.maxOrNull()?.coerceAtLeast(1) ?: 1
            val caption = table.children.firstOrNull { it.tag == "caption" }
            return TableGridModel(columnCount, rows, totalChars, caption)
        }

        private fun collectRows(table: MarkupElement): List<MarkupElement> {
            val out = ArrayList<MarkupElement>()
            for (child in table.children) {
                when (child.tag) {
                    "tr" -> out.add(child)
                    "thead", "tbody", "tfoot" -> for (g in child.children) if (g.tag == "tr") out.add(g)
                }
            }
            return out
        }

        private fun ensureRow(occupancy: ArrayList<BooleanArray>, r: Int) {
            while (occupancy.size <= r) occupancy.add(BooleanArray(0))
        }

        private fun ensureWidth(occupancy: ArrayList<BooleanArray>, r: Int, width: Int) {
            if (occupancy[r].size < width) occupancy[r] = occupancy[r].copyOf(width)
        }

        /** First column where [span] consecutive cells are free, growing the row's mask as needed. */
        private fun firstFree(occupancy: ArrayList<BooleanArray>, r: Int, span: Int): Int {
            var col = 0
            while (true) {
                ensureWidth(occupancy, r, col + span)
                val mask = occupancy[r]
                var free = true
                for (c in col until col + span) if (mask[c]) { free = false; break }
                if (free) return col
                col++
            }
        }

        /**
         * CSS 行高单源（重/轻两路共用），与普通浏览器（Chrome 实测）一致：
         *
         * 1. 每行自身高 = 该行 `rowspan == 1` 单元格外高的最大值（跨行格不撑高「首行」）；
         * 2. 再按跨度从小到大处理跨行格：若其外高超过所跨各行当前高之和，
         *    差额**按各行当前高比例**分摊到这些行（余数给末行）。
         *
         * 关键：跨行格的高度由所跨各行**共享**，而非整体压到第一行——这正是 rowspan 表格
         * 首行虚高、`rowspan=1` 格下方大片留白的根因。单行表（无跨行）与旧 `max(cellH)` 等价。
         */
        fun resolveRowHeights(rowCells: List<List<CellHeight>>): IntArray {
            val n = rowCells.size
            if (n <= 0) return IntArray(0)
            val rowH = IntArray(n) { 1 }
            for (r in 0 until n) {
                for (c in rowCells[r]) {
                    if (c.rowSpan <= 1) rowH[r] = maxOf(rowH[r], c.heightPx)
                }
            }
            for (span in 2..n) {
                for (r in 0 until n) {
                    for (c in rowCells[r]) {
                        if (c.rowSpan != span) continue
                        val last = minOf(r + span - 1, n - 1)
                        var cur = 0
                        for (k in r..last) cur += rowH[k]
                        val deficit = c.heightPx - cur
                        if (deficit <= 0) continue
                        val total = cur.coerceAtLeast(1)
                        var added = 0
                        for (k in r..last) {
                            val add = (deficit.toLong() * rowH[k] / total).toInt()
                            rowH[k] += add
                            added += add
                        }
                        rowH[last] += deficit - added
                    }
                }
            }
            return rowH
        }

        /**
         * P1-2: `border-spacing` 列几何单源（重/轻两路共用）：`contentW` 内均分列宽，
         * 列间＋两侧各留 `spacingH`（collapse 时调用方喂 0）。
         *
         * @return `(columnXs, columnWidths)`（`left` 基准的绝对 x；最小 1px/列）。
         */
        fun columnLayout(contentW: Int, count: Int, spacingH: Float, left: Int): Pair<IntArray, IntArray> {
            if (count <= 0) return IntArray(0) to IntArray(0)
            val gap = spacingH.coerceAtLeast(0f).roundToInt()
            val colW = ((contentW - (count + 1) * gap) / count).coerceAtLeast(1)
            val xs = IntArray(count) { ci -> left + gap + ci * (colW + gap) }
            val ws = IntArray(count) { colW }
            return xs to ws
        }

        /**
         * `table-layout: auto` 列宽——**与浏览器同解**（CSS 2.2 §17.5.2.2；其中的用宽/分配规则
         * 由 Chrome 实测反推验证，非规范性条文，见下）。与 [columnLayout] 同为重/轻两路单源。
         *
         * 1. 列 min/max = 只跨该列单元格的 min/max 最大者（[cellPref]，border-box 口径）；
         *    跨列格把所跨列 min/max 均摊抬到自身（step 3）。
         * 2. 用宽三区段（`MIN=Σmin`、`MAX=Σmax`、`avail` = 表内容宽 − `border-spacing`）：
         *    - `avail ≥ MAX`：表宽 = MAX，`w_i = max_i`（**不撑满**容器）；
         *    - `avail ≤ MIN`：表宽 = MIN（**溢出**容器），`w_i = min_i`；
         *    - 之间：表宽 = avail，`w_i = min_i + (avail−MIN)·(max_i−min_i)/(MAX−MIN)`。
         *
         * 中间段按 `(max−min)` 线性插值，**不是**按 max 比例分——这是与旧实现的关键差别，
         * 也是"换张表/换个宽度就不同"的根因。
         */
        fun autoColumnLayout(
            contentW: Int,
            count: Int,
            spacingH: Float,
            left: Int,
            cells: List<CellPref>,
        ): Pair<IntArray, IntArray> {
            if (count <= 0) return IntArray(0) to IntArray(0)
            val gap = spacingH.coerceAtLeast(0f).roundToInt()
            val avail = (contentW - (count + 1) * gap).toFloat()
            val pref = FloatArray(count)
            val min = FloatArray(count)
            val spec = FloatArray(count)
            for (c in cells) {
                if (c.colSpan <= 1) {
                    val col = c.col.coerceIn(0, count - 1)
                    if (c.pref > pref[col]) pref[col] = c.pref
                    if (c.min > min[col]) min[col] = c.min
                    if (c.specified > spec[col]) spec[col] = c.specified
                }
            }
            for (c in cells) {
                if (c.colSpan > 1) {
                    val span = (c.col until (c.col + c.colSpan)).filter { it in 0 until count }
                    if (span.isEmpty()) continue
                    val curPref = span.sumOf { pref[it].toDouble() }.toFloat()
                    if (c.pref > curPref) {
                        val add = (c.pref - curPref) / span.size
                        for (i in span) pref[i] += add
                    }
                    val curMin = span.sumOf { min[it].toDouble() }.toFloat()
                    if (c.min > curMin) {
                        val add = (c.min - curMin) / span.size
                        for (i in span) min[i] += add
                    }
                    val curSpec = span.sumOf { spec[it].toDouble() }.toFloat()
                    if (c.specified > curSpec) {
                        val add = (c.specified - curSpec) / span.size
                        for (i in span) spec[i] += add
                    }
                }
            }
            // 指定宽是列 min/pref 的下限（`th width=100px` 等表示型属性经级联已进 style）。
            for (i in 0 until count) {
                if (spec[i] > min[i]) min[i] = spec[i]
                if (spec[i] > pref[i]) pref[i] = spec[i]
            }
            val totalMax = pref.sum()
            val totalMin = min.sum()
            // 表用宽（浏览器三段式）。
            val target = when {
                avail >= totalMax -> totalMax
                avail <= totalMin -> totalMin
                else -> avail
            }
            val slack = totalMax - totalMin
            val w = FloatArray(count) { i ->
                when {
                    avail >= totalMax -> pref[i]
                    avail <= totalMin -> min[i]
                    slack > 0f -> min[i] + (avail - totalMin) * (pref[i] - min[i]) / slack
                    else -> pref[i]
                }
            }
            val ws = IntArray(count) { w[it].roundToInt().coerceAtLeast(1) }
            // 总和锁到表用宽（末列吸收舍入漂移；每列 ≥1）。
            val want = target.roundToInt().coerceAtLeast(count)
            ws[count - 1] = (ws[count - 1] + (want - ws.sum())).coerceAtLeast(1)
            val xs = IntArray(count)
            var x = left + gap
            for (i in 0 until count) {
                xs[i] = x
                x += ws[i] + gap
            }
            return xs to ws
        }

        /**
         * 单元格对 auto 分列的 border-box 需求（浏览器口径）：max/min-content（由断行器真测，
         * [orilumn.reader.engine.laying.NormalFlowLayout.tableCellPref]）＋自身横向 padding＋border。
         * 列宽即格 border-box 宽（见 `NormalFlowLayout.innerBreakWidth` 的反算），故此处必须把边距
         * 一并计入，否则列内容宽偏窄、换行更早、行更高。
         *
         * `min` 钳到 `[0, max]`：浏览器不变量（最长不可断单元不可能宽于整段不换行）。
         *
         * @param edgeHPx 单元格横向 padding + border 之和（`cs.padding.horizontal + cs.border.horizontal`）。
         */
        fun cellPref(col: Int, colSpan: Int, maxContentPx: Float, minContentPx: Float, edgeHPx: Float, specifiedW: Float = 0f): CellPref {
            val edges = edgeHPx.coerceAtLeast(0f)
            val pref = maxContentPx.coerceAtLeast(0f)
            return CellPref(col, colSpan, pref + edges, minContentPx.coerceIn(0f, pref) + edges, specifiedW.coerceAtLeast(0f))
        }
    }
}