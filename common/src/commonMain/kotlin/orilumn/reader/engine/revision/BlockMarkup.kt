package orilumn.reader.engine.revision

/**
 * 卡片文字的**块级切分**：行首块标记、代码块围栏、段内硬断行（`\` + 换行）。
 *
 * 只在卡片里用：输入区是一段 markdown 风格的文字，这里把它切成「块」——每块一行（代码块
 * 可以多行），块首那一个标记决定这一块提交后在书里是什么。行内标记由 [InlineMarkup] 解析，
 * 本类只管块这一层（规格见 `docs/正文修改-块级标记.md`）。
 *
 * 三条口径：
 * - **一行 = 一块**，除非该行以未转义的 `\` 结尾（段内硬断行，等价书里的 `<br>`）；
 * - 代码块围栏（``` ``` ```）里的行属于同一块，内容原样（不转义、不解析行内标记）；
 * - 行首文字要是长得像块标记（`# `、`> `、`- `、`1. `、```` ``` ````），序列化时**转义**成
 *   `\# ` 之类，解析时认出转义就按普通文字处理 —— 否则「正文里本来就有的 `- `」会被当成列表。
 *
 * 属于排版层（上）：纯逻辑，可 JVM 单测。
 */
object BlockMarkup {

    /** 一块：形态 + 去标记后的文字（代码块里含真换行）。 */
    class Block(
        /** 这一块的形态；null = 这一行没写块标记。 */
        val kind: BlockKind?,
        val content: String,
        /** 表格行结构；null = 不是表格行。 */
        val table: TableRowInfo? = null,
        /** 表格行的各格原始文字（与 [content] 按序拼接；非表格行为空）。 */
        val tableCells: List<String> = emptyList(),
    )

    /** 切分结果：块表 + 块标记在原文里的位置（输入框淡显用）+ 有没有没闭合的围栏。 */
    class Split(
        val blocks: List<Block>,
        val markerRanges: List<IntRange>,
        val unterminatedFence: Boolean,
    )

    private class Line(val start: Int, val text: String)

    fun split(markup: String): Split {
        val lines = linesOf(markup)
        val blocks = ArrayList<Block>(lines.size)
        val markers = ArrayList<IntRange>()
        var unterminated = false
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val fence = fenceOpen(line.text)
            if (fence > 0) {
                markers.add(line.start until line.start + fence)
                val content = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (isFenceClose(lines[j].text, fence)) {
                        markers.add(lines[j].start until lines[j].start + lines[j].text.trimEnd().length)
                        closed = true
                        break
                    }
                    if (content.isNotEmpty()) content.append('\n')
                    content.append(lines[j].text)
                    j++
                }
                if (!closed) {
                    unterminated = true
                    i = lines.size
                } else {
                    i = j + 1
                }
                blocks.add(Block(BlockKind.Pre, content.toString()))
                continue
            }
            // 表格行：markdown 管道行（`| a | b |`）或基本 HTML 行（`<tr><td>…</td></tr>`）。
            // 普通正文以 `|` 开头时序列化会先转义（见 [InlineMarkup] 的 `escapeLineStart`），
            // 所以这里见到未转义的 `|` 就是表格。
            tableRow(line.text)?.let { row ->
                blocks.add(
                    Block(
                        kind = null,
                        content = row.cells.joinToString(""),
                        table = row.info,
                        tableCells = row.cells,
                    ),
                )
                i++
                continue
            }
            val marker = markerAt(line.text)
            if (marker != null) markers.add(line.start until line.start + marker.second)
            val content = StringBuilder()
            var lineText = if (marker != null) line.text.substring(marker.second) else line.text
            var k = i
            while (true) {
                val hard = hardBreakLength(lineText)
                if (hard % 2 == 1) {
                    content.append(lineText, 0, lineText.length - 1).append('\n')
                    if (k + 1 >= lines.size) break
                    k++
                    lineText = lines[k].text
                } else {
                    content.append(lineText)
                    break
                }
            }
            blocks.add(Block(marker?.first, content.toString()))
            i = k + 1
        }
        if (blocks.isEmpty()) blocks.add(Block(null, ""))
        return Split(blocks, markers, unterminated)
    }

    /**
     * 行首块标记的长度：`#`×1..6 / `>` / `-` `*` `+` / `数字.`，都要跟一个空格（与 markdown 一致）。
     * 反斜杠开头的行不算（读者写的转义）。
     */
    fun markerAt(line: String): Pair<BlockKind, Int>? {
        if (line.isEmpty() || line[0] == '\\') return null
        val first = line[0]
        if (first == '#') {
            var n = 0
            while (n < line.length && line[n] == '#') n++
            if (n > 6) return null
            val after = line.getOrNull(n) ?: return null
            if (after != ' ' && after != '\t') return null
            val kind = BlockKind.fromWire("h$n") ?: return null
            return kind to n + 1
        }
        if (first == '>') {
            val at = if (line.getOrNull(1) == ' ' || line.getOrNull(1) == '\t') 2 else 1
            return BlockKind.Quote to at
        }
        if (first == '-' || first == '*' || first == '+') {
            if (line.getOrNull(1) != ' ' && line.getOrNull(1) != '\t') return null
            return BlockKind.Bullet to 2
        }
        if (first.isDigit()) {
            var n = 0
            while (n < line.length && line[n].isDigit() && n < 9) n++
            val dot = line.getOrNull(n) ?: return null
            if (dot != '.' && dot != ')') return null
            val after = line.getOrNull(n + 1) ?: return null
            if (after != ' ' && after != '\t') return null
            return BlockKind.Ordered to n + 2
        }
        return null
    }

    /** 表格行：格文字 + 结构（格偏移在 [InlineMarkup.parse] 里按解析后的文字重算）。 */
    private class TableLine(val cells: List<String>, val info: TableRowInfo)

    /**
     * 认一行表格：markdown 管道行或基本 HTML 行。
     *
     * 管道行要求以 `|` 起、以 `|` 收（普通正文的行首 `|` 在序列化时会被转义）；
     * HTML 行只认 `<tr>` + `<td>` / `<th>`，属性只保留 `rowspan` / `colspan`。
     */
    private fun tableRow(line: String): TableLine? {
        if (line.startsWith("|")) return pipeTableRow(line)
        if (line.startsWith("<tr", ignoreCase = true)) return htmlTableRow(line)
        return null
    }

    private fun pipeTableRow(line: String): TableLine? {
        if (line.indexOf('\\') >= 0 && line.startsWith("\\|")) return null
        val raw = pipeCells(line) ?: return null
        // 至少「| 格 |」三截；首尾两截必须是空的（只允许空白）。
        if (raw.size < 3) return null
        if (raw.first().isNotBlank() || raw.last().isNotBlank()) return null
        val cells = ArrayList<String>(raw.size - 2)
        for (k in 1 until raw.size - 1) cells.add(trimCellPadding(raw[k]))
        if (cells.isEmpty()) return null
        var at = 0
        val infos = cells.map { t ->
            val end = at + t.length
            TableCellInfo(at, end, header = false).also { at = end }
        }
        val info = TableRowInfo(infos, html = cells.any { it.indexOf('\n') >= 0 })
        return TableLine(cells, info)
    }

    /**
     * 管道行按未转义的 `|` 切片（保留两侧空片，由调用方判首尾）；不是管道行返回 null。
     */
    private fun pipeCells(line: String): List<String>? {
        if (!line.startsWith("|")) return null
        val raw = ArrayList<String>(4)
        val sb = StringBuilder()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && i + 1 < line.length) {
                sb.append(c).append(line[i + 1])
                i += 2
                continue
            }
            if (c == '|') {
                raw.add(sb.toString())
                sb.setLength(0)
            } else {
                sb.append(c)
            }
            i++
        }
        raw.add(sb.toString())
        return raw
    }

    /** markdown 行两边的 `| ` / ` |` 是分隔符，不算格内文字（各吃掉一个空格）。 */
    private fun trimCellPadding(s: String): String {
        var a = 0
        var b = s.length
        if (a < b && s[a] == ' ') a++
        if (b > a && s[b - 1] == ' ') b--
        return s.substring(a, b)
    }

    private val CELL_TAG = Regex("""<(td|th)\b([^>]*)>(.*?)</\1\s*>""", setOf(RegexOption.IGNORE_CASE))
    private val SPAN_ATTR = Regex("""(rowspan|colspan)\s*=\s*["']?(\d+)""", RegexOption.IGNORE_CASE)
    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)

    private fun htmlTableRow(line: String): TableLine? {
        val t = line.trim()
        if (!t.startsWith("<tr", ignoreCase = true)) return null
        if (!t.endsWith("</tr>", ignoreCase = true)) return null
        val cells = ArrayList<String>(4)
        val infos = ArrayList<TableCellInfo>(4)
        var at = 0
        for (m in CELL_TAG.findAll(t)) {
            val tag = m.groupValues[1].lowercase()
            val attrs = m.groupValues[2]
            val content = BR_TAG.replace(m.groupValues[3], "\n")
            val header = tag == "th"
            var rowspan = 1
            var colspan = 1
            for (a in SPAN_ATTR.findAll(attrs)) {
                val n = a.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
                if (a.groupValues[1].equals("rowspan", ignoreCase = true)) rowspan = n else colspan = n
            }
            val end = at + content.length
            cells.add(content)
            infos.add(TableCellInfo(at, end, header, rowspan, colspan))
            at = end
        }
        if (cells.isEmpty()) return null
        return TableLine(cells, TableRowInfo(infos, html = true))
    }

    /** 这一行以块标记开头吗（序列化时要转义）。围栏也算。 */
    fun startsLikeMarker(line: String): Boolean =
        markerAt(line) != null || fenceOpen(line) > 0

    /** 围栏开始的反引号个数；不是围栏返回 0。 */
    fun fenceOpen(line: String): Int {
        if (line.length < 3 || line[0] != '`') return 0
        var n = 0
        while (n < line.length && line[n] == '`') n++
        if (n < 3) return 0
        // 围栏行不能有其它内容（除了语言标识前的一个可有可无的空格）。
        val rest = line.substring(n)
        return if (rest.isEmpty() || rest.startsWith(" ")) n else 0
    }

    /** 收尾围栏：只由反引号（可带尾随空格）组成，且不短于开始的那道。 */
    private fun isFenceClose(line: String, open: Int): Boolean {
        val t = line.trimEnd()
        if (t.length < open) return false
        return t.all { it == '`' }
    }

    /** 行尾连续反斜杠个数（奇数 = 硬断行）。 */
    private fun hardBreakLength(line: String): Int {
        var n = 0
        for (i in line.indices.reversed()) {
            if (line[i] == '\\') n++ else break
        }
        return n
    }

    private fun linesOf(text: String): List<Line> {
        val out = ArrayList<Line>(text.count { it == '\n' } + 1)
        var from = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                out.add(Line(from, text.substring(from, i)))
                from = i + 1
            }
        }
        out.add(Line(from, text.substring(from)))
        return out
    }
}
