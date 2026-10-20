package orilumn.reader.engine.laying

import orilumn.reader.engine.css.FontRun

/**
 * E9：行内 `font-size` 抬升行框（CSS 2.1 §10.8 line-box rule；渲染层·几何测量，pure）。
 *
 * ## 缺的是什么
 *
 * 行框高由**本行所有行内盒 leading 的最大值**决定，不是叶自身那张样式。此前
 * [breakLeafLines]（与两个断行器）只接叶的 `fontSizePx`/`lineHeightRatio`，[FontRun] 只进量宽
 * ⇒ `<span style="font-size:2em">` 的字形按 2em 画、行框仍按 1em 的行高留位，大字直接压到邻行
 * （浏览器不会）。行内**图**早有同一条规则的实现（[adjustLineHeightsForInlineImages]），行内
 * **文字**一直缺 —— 本函数补齐文字这一半。
 *
 * ## 口径
 *
 * 本引擎的 leading 是一把尺（[lineHeightPx]，`fontSizePx × lineHeightRatio`）：行内元素继承
 * `line-height` **比例**，所以 1.5 倍行高下的 2em 行内 span 在本行贡献 3em 行高。取最大 = 只抬不降，
 * 与行内图 / 叠排注音（[adjustLineHeightsForRuby]）同一纪律。
 *
 * 只治**字号变大**这一条：行内元素变小（0.8em）时它的 leading 更小，最大值仍是叶自身（天然无操作）；
 * 同字号但异族的 run 在本引擎的行高模型里 L 相同（L 只由字号 × 比例定），故也不参与。
 *
 * ## 为什么在断行出口而不是像图/注音那样单独一调
 *
 * 重路径（[NormalFlowLayout]）、轻路径塑形（`ShapeGeometry`）、悬浮前视（`BoxChapterLayouter`）
 * 各自接一次图/注音的抬升；这条若也散成多调，漏接一处就是「同一章重轻两路行高不同」——
 * 断行出口是三方共用的唯一收口，抬完即随 [BrokenLine.heightPx] 流进各自的行高账。
 *
 * @param lines 断行结果（叶文本坐标系）。
 * @param fontRuns 与断行/整形**同一份**行内 face 段。
 * @param fontSizePx 叶自身字号（行框基线值）。
 * @param lineHeightRatio 叶自身行高比例（行内元素继承的那个比例）。
 */
fun growLineHeightsForInlineFontSize(
    lines: List<BrokenLine>,
    fontRuns: List<FontRun>,
    fontSizePx: Float,
    lineHeightRatio: Float,
): List<BrokenLine> {
    if (lines.isEmpty() || fontRuns.isEmpty()) return lines
    // 叶自身那张行高：任何一行至少这么高（断行器给的 heightPx 通常就是它，但可能因回退面更大）。
    val base = lineHeightPx(fontSizePx, lineHeightRatio)
    // 快筛：全篇最大的行内字号都不超过叶字号 ⇒ 三方向全部无操作（常见的 <strong>/<code> 同号 run 走这里）。
    var peak = 0f
    for (r in fontRuns) if (r.fontSizePx > peak) peak = r.fontSizePx
    if (peak <= 0f || lineHeightPx(peak, lineHeightRatio) <= base) return lines
    var changed = false
    val out = ArrayList<BrokenLine>(lines.size)
    for (line in lines) {
        val lo = line.range.first
        val hiEx = line.range.last + 1
        var need = base
        for (r in fontRuns) {
            if (r.fontSizePx <= 0f) continue
            if (r.endExclusive <= lo || r.start >= hiEx) continue
            val h = lineHeightPx(r.fontSizePx, lineHeightRatio)
            if (h > need) need = h
        }
        if (need > line.heightPx) {
            changed = true
            out.add(BrokenLine(line.range, need, line.hyphenAtEnd, line.squeezeRatio))
        } else {
            out.add(line)
        }
    }
    return if (changed) out else lines
}
