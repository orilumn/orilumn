package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.BaselineShift
import orilumn.reader.engine.laying.BreakOpportunitySet
import orilumn.reader.engine.laying.BrokenLine
import orilumn.reader.engine.laying.BreakOpportunitySource
import orilumn.reader.engine.laying.CodeIdentifierBreakSource
import orilumn.reader.engine.laying.EnglishHyphenationSource
import orilumn.reader.engine.laying.KinsokuBreakSource
import orilumn.reader.engine.laying.ParagraphBreaker
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.laying.lineHeightPx

/**
 * 自建断行器（排版层·上）——路线 B 的断行决策本体：量宽 → 断点集 → 贪心填充 → `List<BrokenLine>`。
 *
 * **只出「char 范围 + 行高」，不出任何几何**（S2(a) 定案）：行内几何归 [orilumn.reader.engine.laying.LineAligner]
 * 这个独立纯函数，本类零改动它、它也不依赖本类。接缝 [ParagraphBreaker] 的 3 个 `breakLines` 重载 +
 * `preferredWidth` + `minContentWidth` 一律沿用既有签名（S2 已冻结），本类是换实现不是换接口。
 *
 * 贪心的三条语义全部按 Skia 实测复刻（`docs/自建断行引擎-测试计划.md` §T1b，SkParagraph 0.144.6）：
 *
 *  1. **尾随文档空白悬挂**（UAX#14 LB SP）：换行判定看的是「扣掉行尾文档空白后的宽」，
 *     但空白仍留在行区间内（绘制侧要画它），**中间行同样如此**。
 *     实测「3 汉字 + 3 空格」@350 断在 `[0,6)`，即那 3 个空格入了行区间却只按 300 判定。
 *  2. **无空白折叠 / 无行首裁剪**：`"a  b"` 自然宽 = `"a b"` + 1 个空格宽；行首空格照算。
 *     SkParagraph 不做浏览器那套空白处理（实测锁死），故本类也不做。
 *  3. **`\n` 硬断**：行区间不含 `\n`（归一化与 [SkiaParagraphBreaker] 同口径），行首若「预领」了
 *     `\n` 则跳过；纯换行产生的空行丢弃，故 `"a\n\nb"` 得 2 行而非 3 行。
 *
 * **R1 core**（长串/URL 断行，S2(c) 不接线 F11/F12 时的兜底语义）：无可用断点且该单元放不下时，
 * **每个位置都算断点 + 贪心填满**（不是「塞不下就溢出」）。规格见 §2.2 的 R1 core 行为表。
 *
 * **量宽同源**（S2(a)）：宽度全部来自 [SkiaRunMeasurer]，与 S4 Aligner、S5 逐字绘制同一个取宽口。
 * 与 [SkiaParagraphBreaker] 的关系是「同输入两套实现」：后者保留作 T1 逐值等价性对照的基线与回退开关。
 */
class InhouseParagraphBreaker(
    private val letterSpacingEm: Float,
    private val measurer: SkiaRunMeasurer = SkiaRunMeasurer(),
    /** 断点增强器（顺序无关，S2(b) 冻结的形状）。本轮只接禁则表一个 source；S7 只加 source。 */
    private val breakSources: List<BreakOpportunitySource> = listOf(KinsokuBreakSource),
) : ParagraphBreaker {

    /**
     * S7：按 `tag` 分流额外的断点源。
     *
     * - **代码语境**（`pre`/`code`/`kbd`/`samp`/`var`/`tt`）→ [CodeIdentifierBreakSource]
     *   （分隔符后 + 驼峰交界）。**不给**音节断词：代码标识符按音节断是错的（见该类 KDoc）。
     * - **普通段落** → [EnglishHyphenationSource]（K-L 音节断点，R2 的落点）。
     *
     * 判定用 `tag`：`class="highlight"` 的代码块 tag 仍是 `p`，靠 tag 判不出来 ——
     * 那是已知缺口（要读 `class`/`style`），登记在 docs 29g，不在本轮扩接缝。
     */
    private fun sourcesFor(tag: String?): List<BreakOpportunitySource> = when {
        tag != null && tag in CODE_TAGS -> breakSources + CodeIdentifierBreakSource
        else -> breakSources + EnglishHyphenationSource
    }

    private companion object {
        /** 代码语境 tag（CSS UA 默认等宽的这些元素）。 */
        val CODE_TAGS = setOf("pre", "code", "kbd", "samp", "var", "tt")
    }

    override fun breakLines(
        text: CharSequence,
        fontSizePx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        alignment: TextAlign,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
    ): List<BrokenLine> =
        breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, 0f, emptyList())

    override fun breakLines(
        text: CharSequence,
        fontSizePx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        alignment: TextAlign,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        firstLineIndentPx: Float,
    ): List<BrokenLine> =
        breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, firstLineIndentPx, emptyList())

    override fun breakLines(
        text: CharSequence,
        fontSizePx: Float,
        lineHeightRatio: Float,
        widthPx: Int,
        alignment: TextAlign,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        firstLineIndentPx: Float,
        fontRuns: List<FontRun>,
        baselineShifts: List<BaselineShift>,
    ): List<BrokenLine> {
        val n = text.length
        if (n == 0) return emptyList()
        if (widthPx <= 0) {
            // 退化路径：与 SkiaParagraphBreaker 同口径 —— 整段一行（零宽/非法版心护栏）。
            return listOf(BrokenLine(0 until n, lineHeightPx(fontSizePx, lineHeightRatio)))
        }
        // 基线位移不改宽度/断行（探针锁定，SkiaParagraphBreaker 同一口径）：形参收下不参与，
        // 保证 S1-2 的 forceStrut 语义在自建侧天然成立（行盒基线由 S4/S5 侧固定）。
        @Suppress("UNUSED_EXPRESSION")
        baselineShifts

        val adv = measurer.advances(text, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns)
        // 首行可用宽直接扣缩进 —— SkiaParagraphBreaker 那段「TextIndent 整段单行快捷路径」的
        // R1 补偿在新实现下自然消失（本类没有那条快捷路径，首行从一开始就按 CSS 语义排）。
        val headPx = widthPx - firstLineIndentPx
        if (headPx <= 0f) {
            // 缩进已吃满版心：整段一行（再细分只会每行都放不下一个字符）。
            return listOf(BrokenLine(0 until n, lineHeightPx(fontSizePx, lineHeightRatio)))
        }
        return greedy(
            text, adv, n,
            headPx = headPx,
            restPx = widthPx.toFloat(),
            opp = BreakOpportunitySet.of(text, sourcesFor(tag)),
            targetLh = lineHeightPx(fontSizePx, lineHeightRatio),
        )
    }

    /**
     * 贪心填充：单趟 O(n)，同时定「行尾」与「区间归一化」。
     *
     * 规则是**唯一自洽的那一条**——由 §T1b 的受控实测反推，不是照抄文档：
     *
     *  - **判定宽 = `[s, j)` 扣掉行尾文档空白后的宽**（UAX#14 LB SP 的尾随空白悬挂）：
     *    换行判定因此可以把行尾空格放进行区间又不占版心。**中间行同样成立**，不只段末。
     *  - **取「可达断点里最靠右的那个」**：能填多宽填多宽，溢出则退到最近断点。
     *  - 行尾空白只扣**紧贴行尾的那一段**，行内空白照算。反例锁死这条：
     *    `填充填   填充填` @350 断在 `[0,6)`（"填充填␣␣␣" 判定宽 300），若把行内 3 个空格也免掉则断在 `[0,7)`。
     *  - **`\n` 硬断**，行区间不含它；行首「预领」的 `\n` 跳过 → `a\n\nb` 得 2 行不是 3 行。
     *  - **R1 core**：退不到任何断点即在此断开（贪心填满，不是溢出）。见类 KDoc 的行为规格。
     *
     * 不变量（`s` 行首 / `i` 待消费）：
     *  [hang] = `[s, i)` 扣掉**紧贴行尾的那一段**空白后的宽（UAX#14 LB SP）；
     *  [trail] = 紧贴行尾的那一段空白的宽（`hang` 加下一个字符时若该字符非空白，这段要并回来）；
     *  [lastOpp] = 已消费范围内最后一个断点（`-1` = 一个都没有）；已消费的每个断点位置判定宽必然 ≤ avail，
     *  故「最靠右的可达断点」就是它。
     */
    private fun greedy(
        text: CharSequence,
        adv: FloatArray,
        n: Int,
        headPx: Float,
        restPx: Float,
        opp: BreakOpportunitySet,
        targetLh: Int,
    ): List<BrokenLine> {
        val out = ArrayList<BrokenLine>(8)
        var s = 0
        var first = true
        while (s < n) {
            // 行首若「预领」了换行符则跳过（与 SkiaParagraphBreaker 的区间归一化同口径）。
            if (text[s] == '\n') {
                s++
                continue
            }
            val avail = if (first) headPx else restPx
            first = false
            var i = s
            var hang = 0f
            var trail = 0f
            var lastOpp = -1
            var brk = -1
            while (i < n) {
                val c = text[i]
                if (c == '\n') {
                    brk = i
                    break
                }
                val w = adv[i]
                val sp = isDocumentSpace(c)
                // 续上行尾空白段 → 判定宽不变；落到非空白 → 先把那段并回来再算本字符。
                val next = if (sp) hang else hang + trail + w
                if (i > s && next > avail) break
                hang = next
                trail = if (sp) trail + w else 0f
                i++
                if (opp.opportunityAt(i)) lastOpp = i
            }
            if (brk < 0) {
                brk = when {
                    i >= n -> n                       // 整段装下（含「尾随空白悬到段末」）
                    lastOpp > s -> lastOpp           // 退到最近断点
                    else -> i                        // R1 core：一个断点都没有 → 此处断开
                }
            }
            // 反自旋护栏（正常路径不可达：`brk > s` 由上面的 `i > s` 前置条件保证）。
            // 留着是因为一旦未来某个 source 标出 0 号断点，这里就是死循环而不是一次错行。
            if (brk <= s) brk = s + 1
            out.add(BrokenLine(s until brk, targetLh))
            s = brk
        }
        if (out.isEmpty()) out.add(BrokenLine(0 until n, targetLh))
        return out
    }
}
