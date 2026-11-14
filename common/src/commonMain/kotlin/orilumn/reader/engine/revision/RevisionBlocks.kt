package orilumn.reader.engine.revision

import orilumn.reader.engine.html.MarkupElement

/**
 * 修订文字里的**段界**标记（方案 D-34 修订版）。
 *
 * 正文修改的结果段数由读者输入里的换行符决定：一条修订可以跨多个排版叶，把中间的块
 * 合并掉，也可以把一个块拆成几段。可是修订文字里同时还要表达叶内的 `<br>`（换行符），
 * 两种换行必须分得开，所以段界在**存储的修订文字**里用私用区字符 [SEPARATOR] 表示：
 *
 * - `'\n'`：叶内换行（`<br>`），与字符流一致，算 1 个字；
 * - [SEPARATOR]：段界，不占字符（渲染时它是块与块的边界，`chapterText` 里没有它）。
 *
 * 只在修订文字里出现，不会进正文；`originalText` / `editedText`（审阅清单）仍用 `'\n'`，
 * 读者看到的还是普通的换行。
 *
 * 属于排版层（上）：纯逻辑，可 JVM 单测。
 */
object RevisionBlocks {

    /** 修订文字里的段界（U+E000，私用区；正文里不会出现）。 */
    const val SEPARATOR: Char = '\uE000'

    /**
     * 卡片「每行块形态」里表示**续行**：这一行属于上一块（块内换行：`<br>` 或代码块内部换行）。
     *
     * 只活在修订表的 `editedBlocks` 里（重开卡片时把行拼回块），不进正文、不进 [SEPARATOR] 那套编码。
     */
    const val CONTINUATION: String = "-"

    /**
     * 重开卡片时按「每行块形态」把文字拼回块：`[CONTINUATION]` 并进上一块，其余各起一块。
     *
     * @return null = 没有块信息（老修订）：调用方按空行切、块形态一律 null。
     */
    fun cardSegments(text: String, blocks: List<String>): List<InlineMarkup.Segment>? {
        if (blocks.isEmpty()) return null
        val lines = InlineMarkup.splitLines(text)
        val out = ArrayList<InlineMarkup.Segment>(blocks.size)
        // 每一段是由哪条短码起的（续行并入上一段，但短码仍属于第一行）。
        val codes = ArrayList<String>(blocks.size)
        for ((i, line) in lines.withIndex()) {
            val code = blocks.getOrNull(i).orEmpty()
            if (code == CONTINUATION && out.isNotEmpty()) {
                val last = out.removeAt(out.size - 1)
                out.add(last.copy(text = last.text + "\n" + line))
            } else {
                out.add(InlineMarkup.Segment(line, BlockKind.fromWire(code)))
                codes.add(code)
            }
        }
        // 表格行的短码（`t…`；整表修订的首行是大写 `T…`）在文字拼回整段之后才能解码：
        // 格长度按整行文字校验，续行拼回去之前长度不够，`fromWire` 会正确地拒绝。
        return out.mapIndexed { i, seg ->
            val code = codes.getOrNull(i).orEmpty()
            val table = if (code.startsWith("t") || code.startsWith("T")) {
                TableRowInfo.fromWire(code, seg.text.length)
            } else {
                null
            }
            if (table == null) seg else seg.copy(block = null, table = table)
        }
    }

    /** 按段界切块；没有段界时返回单元素表（保留空串）。 */
    fun split(text: String): List<String> {
        if (text.indexOf(SEPARATOR) < 0) return listOf(text)
        val out = ArrayList<String>(4)
        var from = 0
        for (i in text.indices) {
            if (text[i] == SEPARATOR) {
                out.add(text.substring(from, i))
                from = i + 1
            }
        }
        out.add(text.substring(from))
        return out
    }

    /** 段界个数（结果段数 = 段界数 + 1）。 */
    fun separatorCount(text: String): Int {
        var n = 0
        for (c in text) if (c == SEPARATOR) n++
        return n
    }

    /** 渲染空间里的长度：段界不占字符。 */
    fun renderedLength(text: String): Int = text.length - separatorCount(text)

    /**
     * 去掉段界后的文字（跨叶修订的「纯字符」）。
     *
     * 用来判「这条修订是不是只挪了段界」：把两段之间的换行删掉，结果文字与它盖住的原书文字
     * 逐字相同，只是块边界少了一道。这种修订在审阅视图里没有红绿可标，按真正的合并落结构。
     */
    fun textOf(text: String): String =
        if (text.indexOf(SEPARATOR) < 0) text else split(text).joinToString("")

    /**
     * 能不能参与跨段合并：只支持**文字块**。
     * 整块图片（`img` 单独成叶）、表格行、匿名文本叶（不在树里的合成叶）没有可合并的块结构，
     * 提交侧直接拒绝。
     *
     * 行内图片（`img` 在文字块内部，正文里是一个 `U+FFFC` 占位）不算：它随所在的文字块一起改，
     * 删掉占位就是删掉那张图（见 `LeafTextEditor.rebuild`）。
     */
    fun mergeableLeaf(el: MarkupElement): Boolean =
        !el.isText && el.tag != "br" && el.tag != "img" && el.tag != "tr" && el.tag != "table"
}
