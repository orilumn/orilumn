package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.WhiteSpaceNormalize

/**
 * P1-2: `white-space` 断行单源（pure JVM）。
 *
 * 盒流（[NormalFlowLayout]）与轻路径塑形（`ParagraphShapes.shapeOf`）必须以同一规则
 * 把样式化叶文本交给断行器，否则重轻两路行数/区间漂移（路线图 §6 inv.1）：
 *  - 可换行（NORMAL/PRE_WRAP/PRE_LINE）：整段一次委托 [ParagraphBreaker]；
 *  - 不换行（PRE/NOWRAP）：只在硬换行处分段，每段一行、不断行（CSS 溢出语义；
 *    超宽段绘制侧以整行溢出绘制，见 `DrawLine.nowrap`）。
 */
fun breakLeafLines(
    breaker: ParagraphBreaker,
    text: CharSequence,
    style: ComputedStyle,
    widthPx: Int,
    tag: String?,
    fontRuns: List<FontRun> = emptyList(),
    firstLineIndentPx: Float = 0f,
    /** P1-2 行内基线位移（不断行几何，只随段整形使量画一致；不换行段忽略）。 */
    baselineShifts: List<BaselineShift> = emptyList(),
    /**
     * 行内盒（叶文本坐标系）：横向 `padding`/`border` 参与断行宽度，起始于叶首字符的左边内边距
     * 折入首行缩进。空 = 无（零回归）。与 [fontRuns] 同一纪律：调用方必须传与塑形同一份。
     */
    inlineBoxRuns: List<InlineBoxRun> = emptyList(),
): List<BrokenLine> {
    val edges = inlineBoxEdges(text.length, inlineBoxRuns)
    val lead = inlineBoxLeadInsetPx(inlineBoxRuns)
    if (WhiteSpaceNormalize.wraps(style.whiteSpace)) {
        return growLineHeightsForInlineFontSize(
            breaker.breakLines(
                text, style.fontSizePx, style.lineHeightRatio, widthPx, style.textAlign, tag,
                style.fontFamilies, style.fontWeight, style.italic, style.monospace || tag == "pre",
                firstLineIndentPx + lead, fontRuns, baselineShifts, edges,
            ),
            fontRuns, style.fontSizePx, style.lineHeightRatio,
        )
    }
    if (text.isEmpty()) return emptyList()
    val h = lineHeightPx(style.fontSizePx, style.lineHeightRatio)
    val out = ArrayList<BrokenLine>()
    var segStart = 0
    var i = 0
    val n = text.length
    while (i <= n) {
        if (i == n || text[i] == '\n') {
            if (segStart < i) {
                out.add(BrokenLine(segStart until i, h))
            } else if (i < n) {
                // 连续硬换行间的空段：零宽行（占一行高度，首尾字符位置重合）。
                out.add(BrokenLine(segStart until segStart, h))
            }
            // 以 `\n` 结尾不产生额外空行（与断行器幻影行口径一致）。
            segStart = i + 1
        }
        i++
    }
    // 窄体/平面文本同吃 E9：不换行段的行框同样由行内字号撑。
    return growLineHeightsForInlineFontSize(out, fontRuns, style.fontSizePx, style.lineHeightRatio)
}

/**
 * P4-a2: 悬浮环绕断行（[breakLeafLines] 的环绕变体，重/轻/桌面三方单源）。
 *
 * [lead] 为 null、不换行段、空文本时与 [breakLeafLines] 逐字节一致（零回归）；
 * 否则逐行取宽重断：前 [FloatLead.lines] 行按 [FloatLead.widthPx]，
 * 余行按 [widthPx]（首行缩进只作用第 0 行，与单次调用同式）。
 *
 * 实现为"首行循环"：每轮把剩余文本按本行宽度断一次、只取首行。断行器契约
 * （首行恒从 0 起，不断行器与 Skia 实现均满足）保证字符无缝 tiling；
 * 每轮至少消费 1 字符（防御性兜底，无限循环不可能）。
 */
fun breakWrappedLines(
    breaker: ParagraphBreaker,
    text: CharSequence,
    style: ComputedStyle,
    widthPx: Int,
    lead: FloatLead?,
    tag: String?,
    fontRuns: List<FontRun> = emptyList(),
    firstLineIndentPx: Float = 0f,
    baselineShifts: List<BaselineShift> = emptyList(),
    /** 行内盒（叶文本坐标系）：横向 `padding`/`border` 参与断行宽度（逐行切片）；空 = 无。 */
    inlineBoxRuns: List<InlineBoxRun> = emptyList(),
): List<BrokenLine> {
    if (lead == null || text.isEmpty() || !WhiteSpaceNormalize.wraps(style.whiteSpace)) {
        return breakLeafLines(
            breaker, text, style, widthPx, tag, fontRuns, firstLineIndentPx, baselineShifts, inlineBoxRuns,
        )
    }
    // 推进量按**叶坐标**算一次，逐轮切到子串坐标（切出 `slice` 语义：续片段无左边框/内边距）。
    val allEdges = inlineBoxEdges(text.length, inlineBoxRuns)
    val leadInset = inlineBoxLeadInsetPx(inlineBoxRuns)
    val out = ArrayList<BrokenLine>()
    var consumed = 0
    var i = 0
    val mono = style.monospace || tag == "pre"
    while (consumed < text.length) {
        val w = (if (i < lead.lines) lead.widthPx else widthPx).coerceAtLeast(1)
        val sub = text.subSequence(consumed, text.length)
        val lines = breaker.breakLines(
            sub, style.fontSizePx, style.lineHeightRatio, w, style.textAlign, tag,
            style.fontFamilies, style.fontWeight, style.italic, mono,
            // 盒起始于叶首字符的左边内边距只作用于**首行**（i == 0 ⟺ consumed == 0）。
            (if (i == 0) firstLineIndentPx + leadInset else 0f),
            sliceRuns(fontRuns, consumed, sub.length),
            sliceShifts(baselineShifts, consumed, sub.length),
            sliceInlineBoxEdges(allEdges, consumed, sub.length),
        )
        if (lines.isEmpty()) break
        val first = lines[0]
        out.add(BrokenLine((first.range.first + consumed)..(first.range.last + consumed), first.heightPx))
        // 断行器契约：首行从 0 起；推进量至少 1 字符。
        val adv = (first.range.last + 1).coerceAtLeast(1)
        consumed += adv
        i++
    }
    // E9：悬浮环绕逐行重断走的也是断行器（不经 [breakLeafLines]），抬升在此收口。
    return growLineHeightsForInlineFontSize(out, fontRuns, style.fontSizePx, style.lineHeightRatio)
}

/** 把叶坐标系 run 切到子串坐标系（越界裁剪，无交集丢弃）。 */
private fun sliceRuns(runs: List<FontRun>, consumed: Int, subLen: Int): List<FontRun> {
    if (runs.isEmpty()) return runs
    val out = ArrayList<FontRun>(runs.size)
    for (r in runs) {
        val s = (r.start - consumed).coerceAtLeast(0)
        val e = (r.endExclusive - consumed).coerceAtMost(subLen)
        if (e > s && s < subLen) out.add(r.copy(start = s, endExclusive = e))
    }
    return out
}

/** 把叶坐标系基线位移切到子串坐标系（同 [sliceRuns] 口径）。 */
private fun sliceShifts(shifts: List<BaselineShift>, consumed: Int, subLen: Int): List<BaselineShift> {
    if (shifts.isEmpty()) return shifts
    val out = ArrayList<BaselineShift>(shifts.size)
    for (r in shifts) {
        val s = (r.start - consumed).coerceAtLeast(0)
        val e = (r.endExclusive - consumed).coerceAtMost(subLen)
        if (e > s && s < subLen) out.add(r.copy(start = s, endExclusive = e))
    }
    return out
}
