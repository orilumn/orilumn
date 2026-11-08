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
     * 图片、表格行、匿名文本叶（不在树里的合成叶）没有可合并的块结构，提交侧直接拒绝。
     */
    fun mergeableLeaf(el: MarkupElement): Boolean =
        !el.isText && el.tag != "br" && el.tag != "img" && el.tag != "tr" && el.tag != "table"
}
