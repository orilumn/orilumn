package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.laying.isDocumentSpace

/**
 * [graftKerningOnto] 的返回值。
 *
 * @property xs 逐字**笔位**（与 [LineAligner.Placement.xs] 同长、同坐标系；已含对齐偏移）。
 * @property endDelta 末槽相对 [LineAligner.Placement.xs] 末槽的偏移。**只含收紧量**、
 *   不含 JUSTIFY 的补偿量 —— 行尾连字符靠它跟随（见 `LineWindowDrawer.paintGlyphs`）。
 */
internal class ClusterTrackGraft(
    val xs: FloatArray,
    val endDelta: Float,
)

/**
 * 末字所属 run 的**字号**（逐 run 查回，而非用全局值）。
 *
 * 行内换面 run 有各自字号（`fontRuns[].fontSizePx`），用全局 `fontSizePx` 会在换面行上
 * 算错末字的 `lsPx` ⇒ 拉伸基数偏 ⇒ JUSTIFY 铺不满或溢出。
 *
 * **定义只此一处**：[LineAligner] 算 `lastLs` 用它、[graftKerningOnto] 算末字墨宽也用它。
 * 两处若各写一份，换面行上两套口径会静默分叉（教训㉛的同类：同一规则两处实现）。
 */
internal fun lastRunSizePx(
    text: CharSequence, start: Int, lastLocal: Int, fontSizePx: Float, fontRuns: List<FontRun>,
): Float {
    if (fontRuns.isEmpty()) return fontSizePx
    val abs = start + lastLocal
    return fontRuns.firstOrNull { it.start <= abs && abs < it.endExclusive }?.fontPxOr(fontSizePx) ?: fontSizePx
}

/**
 * 把 [KerningClusterTable] 的「自然簇位轨」嫁接到 [LineAligner] 已对齐的落位上。
 *
 * ## 这条路径在修什么（用户报的两个真机缺陷的公共解法）
 *
 * 1. **缺陷②「`text-align: justify` 但右侧没对齐」**：[KerningClusterTable] 建的 Paragraph 是
 *    `TextAlign.LEFT` + 无限宽 ⇒ 它给出的 x **只含 kerning**，不含 JUSTIFY 的 `slack` 均摊、
 *    也不含 CENTER/RIGHT 的整体偏移。第一版在绘制侧直接用簇位轨顶替 `placement.xs`
 *    （`?: placement.xs`）⇒ **含拉丁字母的行整行按自然宽画**。实测（Rust 程序设计语言 全 22 章
 *    19257 行、9201 个 JUSTIFY 行）：6297 行走簇位，其中 **6085 行没铺满**，最大缺口 **1530.91px**。
 * 2. **缺陷①「有行溢出、末字只显示半个」**：见下面「只收紧」那段。
 *
 * 正确做法：**只把整形相对自然轨的**收紧**增量嫁接上去**，其余原样保留。
 * ```
 * tighten(i) = (cnat[i] − cnat[i−1]) − placement.advs[i−1]      // i ≥ 1，两边都含 lsPx（口径一致）
 * delta(i)  = Σ_{1≤k≤i} min(0, tighten(k))
 * xs(i)     = placement.xs[i] + delta(i)
 * ```
 * `cnat` 必须以 `originX = 0` 建（与 `cumAdv` 同坐标系才能逐字相减）；`cumAdv` 走
 * [LineAligner.Placement.advs]（Aligner 自己那份 advance，与 `xs` 同源，**不另算**）。
 *
 * ## ⚠ 只采纳「收紧」，绝不采纳「放宽」——这条路径的结构性不变式
 *
 * 整形 advance 与我方**裸 cmap** advance 只在**收紧方向**等价（kerning / 连字只会让字更近）；
 * **放宽方向并不等价**：Skia 整形出的 advance 偶尔比我方量出的更大（量的是「某个面的裸字宽」，
 * 画的是「整段整形后的落位」，两者口径不同）。此时「采纳放宽」＝「画得比量出的宽」⇒ 右缘越出版心
 * ⇒ 分页阅读器页宽固定、超出部分被页面裁掉 ⇒ **末字只显示半个 = 内容丢失**（浏览器能容忍是因为
 * 可横向滚动，本项目不能）。
 *
 * 实测（Rust 书 21.xhtml，`HTTP 1.1` 那种版本号混排行）：数字 `1` 整形 advance **23.199**
 * vs 我方量出 **19.867**（每处 +3.332px），一行两处即右溢 **6.116px**。全书 19257 行里右溢
 * > 0.5px 的有 18 行，最大 6.116px。
 *
 * 故取 `min(0, ·)`。不变式：**绘制宽恒 ≤ 量出宽 ≤ 版心** ⇒ 这条路径**结构上不可能**右溢。
 *
 * ## ⚠ JUSTIFY 把收紧量按「同一规则」补回去（右缘仍贴版心）
 *
 * 只收紧 ⇒ JUSTIFY 行的右缘会比版心短掉一个 kerning 总量。浏览器/Skia 是**先按整形后的
 * advance 算 slack 再均摊**，右缘因此精确；我们没把整形轨喂给 [LineAligner]（那会让排版期
 * 每行都建 Paragraph，性能不可接受），故在**绘制期**补：把末字缺的那截**按间隙线性均摊**回去
 * （`per = deficit/lv`，量级与 JUSTIFY 自己的 `extra` 同级）。实测（改前 Rust 书）：簇位 JUSTIFY
 * 行 4876 行里 **965 行缺口 > 1px**、最大 **17.29px**；改后缺口 > 1px 的行数 **0**。
 *
 * 目标是「末字**墨框**右缘 == 版心」：末字 advance 里挂着**看不见**的 `lsPx`
 * （[LineAligner] 里叫 `lastLs`），故比的时候两边都要减掉它 —— 用 [lastRunSizePx] 取末字的
 * run 字号，与 [LineAligner] 同一个函数，换面行上不会算错。
 *
 * ## ⚠ 有行尾连字符时**不补**（补了会把末字推进连字符的槽位里）
 *
 * [LineAligner.Placement.hyphenWidth] > 0 有两种形态，末槽右缘的目标**不同**：
 * - `&shy;` 槽位复用：连字符占 [LineAligner.Placement.advs] 末槽 ⇒ 末槽右缘**就是**行右缘；
 * - K-L 音节断词（`extraGlyphGap`）：连字符在区间**之外**、占 [LineAligner.Placement.trailStartX]
 *   之后 ⇒ 末可见字的目标右缘是 `trailStartX − hyphenWidth`。
 * 两者末槽目标差整整一个连字符宽，而 [LineAligner.Placement] 没有暴露是哪种。⇒ 宁可不补：
 * 连字符行会短掉一个 kerning 总量（**与本次修复前的行为一致，不是回归**），但绝不会
 * 「末字压进连字符槽位」或「连字符越出版心」。[ClusterTrackGraft.endDelta] 在这条路径上
 * 仍只含收紧量，连字符照旧跟随。
 */
internal fun graftKerningOnto(
    placement: LineAligner.Placement,
    cnat: FloatArray,
    text: CharSequence,
    start: Int,
    fontSizePx: Float,
    letterSpacingEm: Float,
    fontRuns: List<FontRun>,
    justifyRightEdge: Float,
): ClusterTrackGraft {
    val n = placement.xs.size
    val out = FloatArray(n)
    var delta = 0f
    for (i in 0 until n) {
        if (i > 0) {
            val tighten = (cnat[i] - cnat[i - 1]) - placement.advs[i - 1]
            if (tighten < 0f) delta += tighten
        }
        out[i] = placement.xs[i] + delta
    }
    if (!justifyRightEdge.isNaN() && placement.hyphenWidth <= 0f) {
        // 锚点 = **末可见字**（行末常带 HTML 源里的空白）。
        //
        // ⚠ 下标口径：判据要看 `text[start + lv]`（**这个槽自己**），不是 `text[start + lv - 1]`。
        //   `lv` 是「要补偿的那个槽的下标」，它从 `n - 1` 起**一路跳过空白槽**；
        //   写成 `lv - 1` 等于在「槽 6 是空白」时读到槽 5（`f`，非空白）就停 ⇒ `lv` 停在空白槽 6，
        //   拿它的右缘（= 版心 + 一截空白）当目标 ⇒ `deficit` 变负 ⇒ **补偿整个不发生**
        //   （实测「abcdef  」：应为 400 却得 394，即收紧量原样漏掉）。
        var lv = n - 1
        while (lv > 0 && isDocumentSpace(text[start + lv])) lv--
        if (lv > 0) {
            val lastLs = if (letterSpacingEm == 0f) 0f
            else letterSpacingEm * lastRunSizePx(text, start, lv, fontSizePx, fontRuns)
            val deficit = justifyRightEdge - (out[lv] + placement.advs[lv] - lastLs)
            if (deficit > 0f) {
                val per = deficit / lv
                for (i in 1..lv) out[i] += per * i
            }
        }
    }
    return ClusterTrackGraft(out, out[n - 1] - placement.xs[n - 1])
}
