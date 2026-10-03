package orilumn.reader.engine.skia

import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.RectHeightMode
import org.jetbrains.skia.paragraph.RectWidthMode
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.text.preprocess.CjkLatinGap
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

/**
 * S5b **西文 kerning / 连字保留**（渲染层，绘制侧现算）。
 *
 * ## 问题
 *
 * S5 逐字 `drawString` 后，绘制侧与量宽侧统一成「裸 cmap」，量画彻底同源，
 * 但**丢掉了 HarfBuzz 的 kerning 与 `fi`/`fl` 连字**（Q5）：西文字距略宽。
 *
 * ## 为什么单字符画会丢，以及正确解法
 *
 * 单个字符交给 Skia 时**没有邻居**，kern 表查不了、连字也融不了。
 * 但**不需要按词切**——`getRectsForRange(0, len)` **一次就能拿整段的全部簇矩形**，
 * 实测（docs 29e）**比裸 cmap 还快**（0.047ms vs 0.0877ms / 段）。
 * 簇位 `rect.left - paintX` 即该字符的**真实绘制 x**（含 kerning；连字时两字符共用一簇 ⇒ 同 x，天然正确）。
 *
 * ## 量画同源怎么保证（这是本类存在的理由）
 *
 * **不**让绘制侧单方面用簇位去改已算好的几何 —— 那样会与量宽侧失配。
 * 本类的职责是**给出「含 kerning 的真实字形位置」**，而 [LineAligner] 的
 * 「无 kerning 布局」与它**同源且同步**：两者都由同一段文本 + 同一版心产出，
 * 且**只对含 Latin 的行启用**。CJK 行（无 kerning）走 [LineAligner] 原路径，零行为变化。
 *
 * 换句话说：**含 Latin 的行用 Skia 簇位（保 kerning，同源因为两端都来自 Skia 整形），
 * 纯 CJK 行用裸 cmap（同源因为两端都是裸 cmap）。** 判据是**逐行**而非全段，故无跨源混排错位。
 */
internal class KerningClusterTable(
    private val measurer: SkiaRunMeasurer = SkiaRunMeasurer(),
) {

    /** 该行是否**可能**需要 kerning：无 Latin 字母则整段 kern 表无命中，不建 Paragraph。 */
    fun needsClusters(text: CharSequence, start: Int, endExcl: Int): Boolean {
        for (i in start until endExcl) {
            if (isLatinish(text[i])) return true
        }
        return false
    }

    /**
     * 取 `text[start, endExcl)` 的逐字符绘制 x（含 kerning；连字共用簇时同x）。
     *
     * @param originX 整条簇位轨的起点偏移。**JUSTIFY 行必须传 0**：本方法给的是**未拉伸的自然轨**，
     *   拉伸/对齐偏移由 [LineWindowDrawer.paintGlyphs] 按 [orilumn.reader.engine.skia.LineAligner.Placement]
     *   平移叠加（把 kerning 增量嫁接到已对齐的落位上）。传非 0 会让那条偏移被计两遍。
     * @param letterSpacingEm 与量宽侧同一个 em 值（[SkiaRunMeasurer] 的 `lsPx` 口径）。传 0 会让
     *   带字距的行整行窄 `n·lsPx`（与断行几何失配 ⇒ JUSTIFY 铺不满、可能右溢）。
     * @param cjkLatinSpacingEm 与量宽侧同一个 em 值（混排字距）。施加方式见
     *   [shiftTrackByCjkGaps] —— **不在段内量宽里施加**。
     * @return 与 range 同长的 x 数组；无命中簇时返回 null（调用方退回 [LineAligner] 的 x）。
     */
    fun clusterXs(
        text: CharSequence,
        start: Int,
        endExcl: Int,
        fontSizePx: Float,
        lineHeightRatio: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        originX: Float = 0f,
        letterSpacingEm: Float = 0f,
        cjkLatinSpacingEm: Float = 0f,
    ): FloatArray? {
        val n = endExcl - start
        if (n <= 0) return null
        // 混排字距：与 [LineAligner] **同一份**结果（同一个整段检测 + 裁到本行），最后一次性平移整条轨。
        // 检测坐标已平移成**行内局部**（[CjkLatinSpacing.gapsForRange] 的契约），
        // 故 [shiftTrackByCjkGaps] 拿到的是局部下标。
        //
        // ⚠ 这里若改成「按 `text[start, endExcl)` 子串检测」，本轨就会与 [LineAligner] 的 `adv`
        //   不同源 ⇒ `tighten = (cnat[i] − cnat[i−1]) − placement.advs[i−1]` 在行尾那个边界字上
        //   恒为负一个间隙 ⇒ 被 `min(0, ·)` 全额采纳 ⇒ **刚注入的间隙在绘制侧被抹掉**。
        val gaps = if (cjkLatinSpacingEm > 0f) {
            CjkLatinSpacing.gapsForRange(text, start, endExcl, cjkLatinSpacingEm, fontRuns)
        } else {
            emptyList()
        }
        // 行内换面：分段建（浏览器 inline-run 语义），否则簇位会按错误的面算。
        if (fontRuns.isEmpty()) {
            val single = clusterXsSingle(text, start, endExcl, fontSizePx, lineHeightRatio, tag, families, weight, italic, monospace, originX, letterSpacingEm)
            return single?.also { shiftTrackByCjkGaps(it, gaps, text, start, fontSizePx, fontRuns) }
        }
        val edges = (fontRuns.flatMap { listOf(it.start, it.endExclusive) } + listOf(start, endExcl))
            .filter { it in start..endExcl }.distinct().sorted()
        val out = FloatArray(n) { Float.NaN }
        // **段间必须串接**：各段独立建 Paragraph、坐标都从 0 起，直接拼会得到断裂的 x
        //（换面边界处回跳 —— 被「簇位必须单调不减」那把锁抓出来）。
        // 故每段起点的 x = 前一段末尾 x + 该交界字符的 advance（advance 用**当前段的面**量，
        // 与量宽侧 `SkiaRunMeasurer` 同出口），保证连续且与裸 cmap 的段内相对位置一致。
        var cursorX = originX
        for (k in 0 until edges.size - 1) {
            val s = edges[k]; val e = edges[k + 1]
            if (e <= s) continue
            val r = fontRuns.firstOrNull { it.start <= s && e <= it.endExclusive }
            val segFam = r?.families ?: families
            val segSize = r?.fontPxOr(fontSizePx) ?: fontSizePx
            val seg = clusterXsSingle(
                text, s, e, segSize, lineHeightRatio,
                r?.tag ?: tag, segFam, r?.weight ?: weight,
                r?.italic ?: italic, r?.monospace ?: monospace, cursorX, letterSpacingEm,
            )
            if (seg == null) return null
            for (j in s until e) out[j - start] = seg[j - s]
            // 下一段起点 = 本段末字 x + 该字 advance（**用当前段的面**量，与量宽侧同一出口；
            // 段内 kerning 已含在簇位里，不重复加）。
            //
            // ⚠ **下标必须是全文本绝对下标 `e-1`**：`adv` 是对**整段 `text`** 量的（形参就是 `text`），
            // 与 `text` 同坐标。曾经写成段内局部下标 `e-1-s`（`advances` 早先是对子串量的），
            // 换段后就取了**别的字符**的 advance 当交界宽 —— 而 Rust 书正文 `<code>` 段几乎都是
            // **单字符**（`x` / `4` / `y` …），于是每遇一个换面边界就凭空加进一个 CJK 全角宽
            // （实测 42.18px），逐段累加。实测（19.xhtml 一行 47 字、17 个换面段）：
            // 我方 Σadv=1510.99 而簇位轨末 x=1718.19，**漂移 207.21px** ⇒ 该行右溢出版心 244px，
            // 末字被页面裁掉半个。全书 19257 行里这类「p + 行内换面」行几乎全中。
            val adv = measurer.advances(
                text, segSize, letterSpacingEm, r?.tag ?: tag, segFam,
                r?.weight ?: weight, r?.italic ?: italic, r?.monospace ?: monospace,
                emptyList(),
            )
            cursorX = out[e - 1 - start] + adv[e - 1]
        }
        if (out.any { it.isNaN() }) return null
        shiftTrackByCjkGaps(out, gaps, text, start, fontSizePx, fontRuns)
        return out
    }

    /**
     * 混排字距在 Skia 簇位轨上的**唯一施加点**（渲染层·几何测量）。
     *
     * ## 为什么必须在最后统一平移，而不能塞进 [clusterXsSingle] 或段间的 [advances]
     *
     * 这条轨是 Skia 整形算出来的，**它不知道我们注入了间隙**（SkParagraph 只认
     * `letterSpacing`）。若不补，[graftKerningOnto] 会把间隙当成「整形收紧量」吃掉：
     * ```
     * tighten(i) = (cnat[i] − cnat[i−1]) − placement.advs[i−1]
     * ```
     * 间隙已进 [LineAligner.Placement.advs]，而 `cnat` 里没有 ⇒ `tighten` 恒为负（正好一个间隙）
     * ⇒ 被 `min(0, ·)` 全额采纳 ⇒ **刚注入的间隙在绘制侧被抹掉**，滑块看起来「只影响断行不影响落墨」。
     * 补进轨里之后 `tighten` 回到 0，该路径对间隙**恒等**（[ClusterTrackGraft] 不需要改）。
     *
     * ## 为什么段间拼接（`cursorX = out[...] + adv[e-1]`）**必须留成不含间隙**
     *
     * 拼接用的是「到 `e-1` 为止的自然轨 + 交界字符的无间隙 advance」。若这里也带上间隙，
     * 那一段间隙会在拼接时进 `cursorX`、又被末尾的累计位移再进一次 ⇒ **双计**。
     * 末尾那次累计位移的定义是「下标严格小于 `start + j` 的全部间隙之和」，它恰好等于
     * `x_j = x_0 + Σ_{i<j} (adv_i + gap_i)`，与断行侧的 [SkiaRunMeasurer.advances] 逐位同源。
     *
     * @param gaps 局部坐标的间隙（检测对象 = `text[start, endExcl)`）。
     * @param track **原地**平移（返回同一数组；调用方按「我传的数组被改了」使用）。
     */
    private fun shiftTrackByCjkGaps(
        track: FloatArray,
        gaps: List<CjkLatinGap>,
        text: CharSequence,
        start: Int,
        fontSizePx: Float,
        fontRuns: List<FontRun>,
    ) {
        if (gaps.isEmpty()) return
        // gaps 按 leftIndex 升序 ⇒ 单趟扫，「累计位移」随下标单调增长。
        var gi = 0
        var shift = 0f
        for (j in track.indices) {
            // ⚠⚠ **必须拿局部下标比局部下标**（`gaps[gi].leftIndex < j`）。
            //   旧写法是 `gaps[gi].leftIndex < abs`（`abs = start + j`）—— 拿**行内局部**的
            //   `leftIndex` 去比**段内绝对**的 `abs`，坐标系混用。由于段首 `start` 通常是几百/几千，
            //   几乎每条间隙都满足 `leftIndex < abs` 而在 `j == 0` 就被计入 ⇒ 整条轨被**同一个常量**
            //   平移 ⇒ `cnat[i] − cnat[i−1]` 的**逐槽差分里根本没有间隙**（常量在相减时消掉）⇒
            //   [graftKerningOnto] 的 `tighten = (cnat[i] − cnat[i−1]) − placement.advs[i−1]`
            //   在每个间隙位恒为 −gap ⇒ 被 `min(0, ·)` 全额采纳 ⇒ **刚注入的间隙在落墨侧被整条抹掉**。
            //
            //   实测（真书《Rust 程序设计语言》8738 段 / 29238 行，走完整绘制管线
            //   `LineAligner` + `clusterXs` + `graftKerningOnto`，把滑块从 0.25 拉到 1.0）：
            //   **18990 个行内中英边界里 18743 个（98.7%）墨位一动不动**（应为 32.81px）。
            //   纯 CJK 行不走簇位轨（[needsClusters] 为 false）⇒ 间隙正常 ⇒ 这就是真机症状
            //   「**有些**起作用、**有些**不起作用」的成因；末行更刺眼，是因为
            //   `graftKerningOnto` 的 `justifyRightEdge` 补偿块只对「JUSTIFY 且非末行」生效，
            //   末行没有那层补偿来掩盖被抹掉的那份间隙。
            while (gi < gaps.size && gaps[gi].leftIndex < j) {
                // 间隙宽度按**边界左侧那个字所在 run** 的字号算（与 [SkiaRunMeasurer.applyCjkLatinGaps]
                // 同一条规则、同一个 [lastRunSizePx] 取字号函数 —— 那个形参收的也是**局部**下标）。
                shift += gaps[gi].gapEm * lastRunSizePx(text, start, gaps[gi].leftIndex, fontSizePx, fontRuns)
                gi++
            }
            track[j] += shift
        }
    }

    // ⚠ 原有私有的 `runsWithin(runs, start, endExcl)`（把 [FontRun] 裁到子串并平移成局部坐标）
    //   **已删**：它唯一的调用点就是上面那次「按行子串检测」，而检测已改为在整段上做
    //   （[CjkLatinSpacing.gapsForRange] 的 [fontRuns] 形参要的就是**整段坐标**的 runs ——
    //   探测器当前不消费它，但语义上必须传对，否则将来 `runs` 一旦被消费就是段/行坐标混用）。
    //   `LineAligner.sliceRuns` 仍保留：它服务的是 `advances` 的**取宽**路径（那里 `adv` 是
    //   行内局部数组，run 必须同步平移），与检测无关。

    private fun clusterXsSingle(
        text: CharSequence,
        start: Int,
        endExcl: Int,
        fontSizePx: Float,
        lineHeightRatio: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        originX: Float,
        letterSpacingEm: Float,
    ): FloatArray? {
        val n = endExcl - start
        if (n <= 0) return null
        val style = SkParagraphFactory.paragraphStyle(
            TextAlign.LEFT, fontSizePx, lineHeightRatio, tag, families, weight, italic,
            monospace, letterSpacingEm, firstLineIndentPx = 0f,
        )
        val p = ParagraphBuilder(style, SkiaFontPool.current()).addText(text.subSequence(start, endExcl).toString()).build()
        try {
            p.layout(Float.MAX_VALUE)
            val out = FloatArray(n) { Float.NaN }
            var covered = 0
            // 逐字符取簇左沿。**不能**靠「整段一次 getRectsForRange」——`TextBox` 在 skiko 里
            // **不带 startIndex/endIndex**（`javap` 确认：只有 `rect` + `direction`），
            // 无法把簇归位到字符；连字时多字共簇更无从下手。
            // 逐字查仍比「每词建一个 Paragraph」快 5.9x（docs 29e B 臂 0.20ms/段），
            // 且本方法只对**含拉丁字母的行**调用，CJK 行的零成本。
            for (i in 0 until n) {
                val boxes = runCatching {
                    p.getRectsForRange(i, i + 1, RectHeightMode.TIGHT, RectWidthMode.TIGHT)
                }.getOrNull()
                if (boxes.isNullOrEmpty()) continue
                // 连字：若下一字与本字同簇（x 相同），沿用本字 x —— 天然正确，无需特判。
                out[i] = originX + boxes[0].rect.left
                covered++
            }
            return if (covered == 0) null else out
        } finally {
            p.close()
        }
    }

    /** 拉丁字母判据（ASCII 字母 + 常见拉丁补充）。CJK 行一律 false ⇒ 完全不建 Paragraph。 */
    private fun isLatinish(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '\u00C0'..'\u024F' || c == '\u2019'
}