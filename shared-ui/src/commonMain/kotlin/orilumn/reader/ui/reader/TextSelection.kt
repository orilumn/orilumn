package orilumn.reader.ui.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 选区两端的拖动柄方向。 */
enum class SelectionHandle { Start, End }

/**
 * 当前章的文字选区（用户层，D-7 / D-11）。
 *
 * 锚点与焦点都是**章内字符偏移**，区间对外始终规范成 `[start, endExclusive)`；
 * 锚点在拖动中不动，焦点跟着手指走。长按起手先按 [selectionUnitAt] 选整词 / 整句，
 * 之后手指或手柄移动都按**单字符**微调。
 *
 * 只持当前章：换章由调用方 [clear]，不跨章（D-8）。
 */
class TextSelectionState {

    var chapter by mutableIntStateOf(-1)
        private set

    var start by mutableIntStateOf(0)
        private set

    var endExclusive by mutableIntStateOf(0)
        private set

    var hasSelection by mutableStateOf(false)
        private set

    private var anchor = 0
    private var focus = 0
    private var textLength = 0

    /** 对外区间；空选区返回 `IntRange.EMPTY`（可直接喂装饰几何）。 */
    val range: IntRange
        get() = if (endExclusive > start) start..(endExclusive - 1) else IntRange.EMPTY

    /**
     * 长按起手：在 [offset] 处按 D-7 选整词 / 整句。
     *
     * [text] 是整章原文（与标注同坐标）；[blockEnds] 是整章文本的累积块尾
     * （每个排版叶一段），用于把句子/英文词截在本块内。返回 false = 空章或偏移越界。
     */
    fun begin(chapter: Int, text: String, offset: Int, blockEnds: IntArray? = null): Boolean {
        if (text.isEmpty()) return false
        val i = offset.coerceIn(0, text.length - 1)
        val unit = selectionUnitAt(text, i, blockEnds)
        if (unit.isEmpty()) return false
        this.chapter = chapter
        textLength = text.length
        anchor = unit.first
        focus = unit.last
        publish()
        return true
    }

    /**
     * 手柄起手：把锚点挪到另一端，之后 [extendTo] 只动被拖的那一端。
     *
     * 例如拖左柄时锚点固定在选区末尾，焦点向左走；拖右柄则相反。
     */
    fun beginHandleDrag(handle: SelectionHandle) {
        if (!hasSelection) return
        if (handle == SelectionHandle.Start) {
            anchor = endExclusive - 1
            focus = start
        } else {
            anchor = start
            focus = endExclusive - 1
        }
    }

    /** 焦点移到 [offset]（钳在章内），并按锚点重新规范区间。 */
    fun extendTo(offset: Int) {
        if (!hasSelection || textLength <= 0) return
        focus = offset.coerceIn(0, textLength - 1)
        publish()
    }

    /** 丢弃选区（换章 / 点按清除）；不触发任何排版或栅格。 */
    fun clear() {
        chapter = -1
        start = 0
        endExclusive = 0
        hasSelection = false
        anchor = 0
        focus = 0
        textLength = 0
    }

    private fun publish() {
        val lo = minOf(anchor, focus)
        val hi = maxOf(anchor, focus)
        start = lo
        endExclusive = hi + 1
        hasSelection = endExclusive > start
    }
}

/**
 * 长按落点对应的选区单位（D-7）。
 *
 * - 中文 / 日文 / 韩文：按句，句末全角标点与逗号等不断句；句末的闭引号 / 闭括号归前句。
 * - 其他文字：按英文词，空白与常规标点断开，撇号 / 连字符只作词内连缀。
 * - 落在空白或标点上：退化为一个码点，保证总有可拖的起点。
 *
 * 返回 `[start, endExclusive)`；空串返回空区间。
 */
internal fun selectionUnitAt(text: String, offset: Int, blockEnds: IntArray? = null): IntRange {
    if (text.isEmpty()) return IntRange.EMPTY
    val i = offset.coerceIn(0, text.length - 1)
    val block = blockRangeAt(text.length, i, blockEnds)
    val c = text[i]
    return when {
        isCjk(c) || isSentenceTerminator(c) -> sentenceRange(text, i, block)
        isClosingPunctuation(c) ->
            if (i > block.first) sentenceRange(text, i - 1, block) else codePointRange(text, i, block)
        isWordChar(c) -> wordRange(text, i, block)
        else -> codePointRange(text, i, block)
    }
}

/** 落点所在的排版叶区间 `[start, endExclusive)`；无块表时是整章。 */
private fun blockRangeAt(textLength: Int, i: Int, blockEnds: IntArray?): IntRange {
    if (blockEnds == null || blockEnds.isEmpty()) return 0 until textLength
    var lo = 0
    var hi = blockEnds.lastIndex
    while (lo <= hi) {
        val mid = (lo + hi) / 2
        if (i < blockEnds[mid]) hi = mid - 1 else lo = mid + 1
    }
    val idx = lo.coerceIn(0, blockEnds.lastIndex)
    val start = if (idx == 0) 0 else blockEnds[idx - 1]
    val end = blockEnds[idx].coerceIn(start, textLength)
    return start until end
}

private fun publishRange(start: Int, endExclusive: Int): IntRange =
    if (endExclusive > start) start..(endExclusive - 1) else IntRange.EMPTY

private fun codePointRange(text: String, i: Int, block: IntRange): IntRange {
    val blockEnd = if (block.isEmpty()) text.length else block.last + 1
    val end = if (i + 1 < blockEnd && text[i].isHighSurrogate() && text[i + 1].isLowSurrogate()) {
        i + 2
    } else {
        i + 1
    }
    return publishRange(i, end)
}

private fun wordRange(text: String, i: Int, block: IntRange): IntRange {
    val blockStart = if (block.isEmpty()) 0 else block.first
    val blockEnd = if (block.isEmpty()) text.length else block.last + 1
    var s = i
    while (s > blockStart && isWordChar(text[s - 1])) s--
    var e = i + 1
    while (e < blockEnd && isWordChar(text[e])) e++
    // 撇号 / 连字符只有夹在词内才算词的一部分；词首尾的留给标点。
    while (s < e && isWordSeparatorAtEdge(text[s])) s++
    while (e > s && isWordSeparatorAtEdge(text[e - 1])) e--
    return if (e > s) publishRange(s, e) else codePointRange(text, i, block)
}

private fun sentenceRange(text: String, i: Int, block: IntRange): IntRange {
    val blockStart = if (block.isEmpty()) 0 else block.first
    val blockEnd = if (block.isEmpty()) text.length else block.last + 1
    var s = i
    while (s > blockStart) {
        val p = text[s - 1]
        if (p == '\n' || isSentenceTerminator(p)) break
        s--
    }
    // 上一句的闭引号 / 闭括号紧跟在句末标点后，不属于本句。
    while (s < i && isClosingPunctuation(text[s])) s++

    var e = i
    while (e < blockEnd) {
        val c = text[e]
        if (c == '\n') break
        if (isSentenceTerminator(c)) {
            e++
            // 连续句末（“……”、“！？”）与闭引号 / 闭括号一起归本句。
            while (e < blockEnd && (isSentenceTerminator(text[e]) || isClosingPunctuation(text[e]))) {
                e++
            }
            break
        }
        e++
    }
    if (e <= s) return codePointRange(text, i, block)
    return publishRange(s, e)
}

private fun isWordChar(c: Char): Boolean =
    !isCjk(c) && (c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-')

private fun isWordSeparatorAtEdge(c: Char): Boolean = c == '\'' || c == '’' || c == '-'

/** CJK 统一表意文字、扩展区、假名、韩文；这些语言按句选，不做分词。 */
private fun isCjk(c: Char): Boolean =
    c in '\u3400'..'\u4DBF' ||
        c in '\u4E00'..'\u9FFF' ||
        c in '\uF900'..'\uFAFF' ||
        c in '\u3040'..'\u30FF' ||
        c in '\uAC00'..'\uD7AF'

private fun isSentenceTerminator(c: Char): Boolean =
    c == '。' || c == '！' || c == '？' || c == '…' || c == '；' ||
        c == '!' || c == '?' || c == ';'

private fun isClosingPunctuation(c: Char): Boolean =
    c == '”' || c == '’' || c == '」' || c == '』' || c == '】' ||
        c == '》' || c == '〉' || c == '〕' || c == '）' || c == '］' ||
        c == '}' || c == ')' || c == ']'
