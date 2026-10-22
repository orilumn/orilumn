package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

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
 *
 * ## 回填走与主拉伸**同一套四级优先级**（2026-10-03）
 *
 * 旧版是 `per = deficit / lv` 逐槽等额回填。主拉伸换成 [JustifySlack] 的四级瀑布之后，
 * 若这里仍等额：主拉伸把 `slack` 优先花在**词间空格**上，回填却把它等额摊进**词内字母缝**
 * —— 两笔账叠起来，词内字母缝实际拿到的仍是全量，用户看到的观感与「均摊」没区别。
 * ⇒ 两处**必须同一套**（教训㩼：同一规则两处实现会静默分叉）。
 *
 * 下标口径：`out[i] += Σ_{k<i} per[k]`，故**槽 k = 字符 k 与 k+1 之间**，与
 * [LineAligner] 的槽定义逐位对齐；SHY 槽取 [JustifySlack.Plan.levels] 的 −1（不加），
 * 累积照旧往下走 —— 与主拉伸 `x += per[lv]` 遇 SHY 跳过的语义完全一致。
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
    /**
     * 混排字距 em —— **只用于槽位分类**（[JustifySlack] 的级 2），不是宽度来源
     * （宽度早在 [SkiaRunMeasurer.applyCjkLatinGaps] 就进了 `adv`）。
     *
     * 传 em 而不是现成的 `List<CjkLatinGap>`：**按需算**。分类只在真有 kerning 且真要回填时
     * 发生（`deficit > 0`），而 [CjkLatinSpacing.gapsForRange] 要扫**整段**——绝大多数行走不到这里。
     */
    cjkLatinSpacingEm: Float = 0f,
    /**
     * [placement] 的起点相对 [start] 的偏移（槽 0 所在处）。
     *
     * 正常落墨时 [placement] 就按 `start until endExcl` 建、偏移恒 0；但**同一行分两趟画**
     * （已定位行内盒压在在流文字之上，见 `LineWindowDrawer.paintText`）时，第二趟只画 run 那段，
     * 而 [placement] 仍是整行的 —— 此时 [off] = 该段起点 − 整行起点，槽位按它索引。
     */
    off: Int = 0,
    /** 本次要处理的字数（默认 = [placement] 里剩下的槽数）。 */
    count: Int = placement.xs.size - off,
): ClusterTrackGraft {
    val n = count
    val out = FloatArray(n)
    var delta = 0f
    for (i in 0 until n) {
        if (i > 0) {
            val tighten = (cnat[i] - cnat[i - 1]) - placement.advs[i - 1 + off]
            if (tighten < 0f) delta += tighten
        }
        out[i] = placement.xs[i + off] + delta
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
            val deficit = justifyRightEdge - (out[lv] + placement.advs[lv + off] - lastLs)
            if (deficit > 0f) {
                // 槽 `k` = 字符 `k` 与 `k+1` 之间 ⇒ 覆盖 `k ∈ [0, lv)`，
                // 绝对下标区间 `[start, start + lv)`（与 `gapsForRange` 的裁剪口径一致）。
                val gaps = CjkLatinSpacing.gapsForRange(text, start, start + lv, cjkLatinSpacingEm, fontRuns)
                val plan = JustifySlack.plan(text, start, lv, gaps, fontSizePx, deficit)
                var acc = 0f
                for (i in 1..lv) {
                    val l = plan.levels[i - 1]
                    if (l >= 0) acc += plan.per[l]
                    out[i] += acc
                }
                // ⚠ `plan.placed ≤ deficit`（三级封顶且无级 3 槽时会**严格小于**）⇒ 这一行
                //   停在距版心 `deficit − placed` 处。**这是有意的**：回填是「补回被 kerning
                //   收掉的量」，宁可少补也不把字撑开；旧版等额回填则一定会补满。
            }
        }
    }
    return ClusterTrackGraft(out, out[n - 1] - placement.xs[n - 1 + off])
}

/**
 * 本行**落墨实走**的逐字 x 轨（`null` = 无拉丁字母，直接用 [LineAligner.Placement.xs]）。
 *
 * S5b：含拉丁字母的行改用 Skia 簇位（保 kerning + `fi`/`fl` 连字），纯 CJK 行零成本走
 * Aligner 的 x。**逐行判据、不混用**：一行要么全用簇位、要么全用 Aligner —— 两套 x 混在
 * 一行里就是错位。
 *
 * 簇位轨是「未拉伸的自然轨」，拉伸 / 对齐偏移必须由 Aligner 出、[graftKerningOnto] 嫁接。
 * 全部推导、实测数据、不变式与两个禁忌都写在那里（真书实测：修复前 965 行右缘缺口 >1px、
 * 最大 17.29px；18 行右溢最大 6.116px；修复后两者归零）。
 *
 * ⚠ **这是「画」的唯一出口**：逐字落墨、选区/标注矩形的横向几何、点按命中的二分查找
 * **必须**都读它。谁另读 [LineAligner.Placement.xs]，谁的横向位置就与实际墨迹差一个
 * 「从行首到该字的累计收紧量」（实测真有 10~17px 量级，即大半个字宽）——
 * 用户看到的就是「选区整体偏右、行首那个字母没选上」。量画同源由此保证。
 *
 * @param slotOff 本趟起点在 [placement] 里的槽位偏移（同一行分两趟画时用，见
 *   [LineWindowDrawer] 的 `paintGlyphs`）。
 * @param count 本趟处理字数。
 */
internal fun lineGlyphTrack(
    line: DrawLine,
    placement: LineAligner.Placement,
    table: KerningClusterTable,
    start: Int,
    endExcl: Int,
    slotOff: Int = 0,
    count: Int = endExcl - start,
): ClusterTrackGraft? {
    if (!table.needsClusters(line.text, start, endExcl)) return null
    // originX 必须 0：本轨要与 Placement.advs 同坐标系，才能逐字相减出 kerning 增量。
    val cnat = table.clusterXs(
        line.text, start, endExcl, line.fontSizePx, line.lineHeightRatio,
        line.tag, line.families, line.weight, line.italic, line.monospace,
        line.fontRuns, originX = 0f, letterSpacingEm = line.letterSpacingEm,
        // 混排字距：必须传与量宽侧同一个 em，否则 [graftKerningOnto] 会把注入的间隙
        // 当成「整形收紧量」吃掉（该方法的 KDoc 有完整推导）。
        cjkLatinSpacingEm = line.cjkLatinSpacingEm,
        // 行内盒横向推进：同理必须与量宽侧同一份，否则被当收紧量抹掉。
        inlineBoxRuns = line.inlineBoxRuns,
    ) ?: return null
    return graftKerningOnto(
        placement, cnat, line.text, start,
        line.fontSizePx, line.letterSpacingEm, line.fontRuns,
        // 只有 JUSTIFY 且非末行才把收紧量补回右缘；其余传 NaN = 「不补」。
        if (line.alignment == TextAlign.JUSTIFY && endExcl < line.text.length) {
            line.lineWidthPx.toFloat()
        } else {
            Float.NaN
        },
        // 槽位分类要与主拉伸同源（同 em）；不传则级 2（中英注入间隙）恒为空。
        line.cjkLatinSpacingEm,
        // 本趟只画整行里的一段（同一行分两趟）⇒ 槽位按段落起点偏移索引。
        off = slotOff, count = count,
    )
}

/**
 * 区间 `[from, to)`（**轨内局部下标**）的可见左右并集 —— [LineAligner.visibleSpan] 的同式，
 * 但作用在**已嫁接的落墨轨**上（[LineAligner.visibleSpan] 只认 `Placement`）。
 *
 * 宽度仍取 [LineAligner.Placement.advs]：嫁接只**平移**槽位，不改字的 advance（见
 * [graftKerningOnto]）。
 */
internal fun spanOn(
    xs: FloatArray,
    advs: FloatArray,
    from: Int,
    to: Int,
): Pair<Float, Float>? {
    val lo = from.coerceIn(0, xs.size)
    val hi = to.coerceIn(lo, xs.size)
    if (hi <= lo) return null
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    for (k in lo until hi) {
        val x = xs[k]
        left = minOf(left, x)
        right = maxOf(right, x + (advs.getOrNull(k) ?: 0f))
    }
    return if (right <= left) null else left to right
}
