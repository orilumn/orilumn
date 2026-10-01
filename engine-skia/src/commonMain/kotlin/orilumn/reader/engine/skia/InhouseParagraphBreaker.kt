package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.laying.SoftHyphenBreakSource
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
     * - **代码语境**（`pre`/`code`/`kbd`/`samp`/`tt`；判据见 CODE_TAGS 的逐条核对）→ [CodeIdentifierBreakSource]
     *   （分隔符后 + 驼峰交界）。**不给**音节断词：代码标识符按音节断是错的（见该类 KDoc）。
     * - **普通段落** → [EnglishHyphenationSource]（K-L 音节断点，R2 的落点）。
     *
     * 判定用 `tag`：`class="highlight"` 的代码块 tag 仍是 `p`，靠 tag 判不出来 ——
     * 那是已知缺口（要读 `class`/`style`），登记在 docs 29g，不在本轮扩接缝。
     */
    private fun sourcesFor(tag: String?): List<BreakOpportunitySource> = when {
        // `&shy;` 是**作者显式写进正文的**断点意图 ⇒ **两个分支都要注入**，
        // 即使它出现在 `<code>` 里（作者在代码注释里写 `&shy;` 也是有意的）。
        tag != null && tag in CODE_TAGS -> breakSources + SoftHyphenBreakSource + CodeIdentifierBreakSource
        // S7：`lang` 未声明（空）时按 en —— 书库里绝大多数非中文段落是英文，
        // 且 [EnglishHyphenationSource.forLang] 对未加载语言退到 [NoHyphenation]（= R1 兜底），
        // 不会误用 en 表去断德语/法语词。真正按 `lang` 分表需要样式通道，见 docs 29g。
        else -> breakSources + SoftHyphenBreakSource + EnglishHyphenationSource.forLang("en")
    }

    private companion object {
        /**
         * **代码/技术文本语境**的 HTML tag，命中则注入 [CodeIdentifierBreakSource] 而非音节断词。
         *
         * **判据 = 本仓 `ua.css` 实际给它 `font-family: monospace`**，逐条核对过：
         * `code, kbd, samp, tt, pre { font-family: monospace; }`（ua.css:61）。
         *
         * - `code` 代码片段 —— 语料 **26229** 处（最常见）
         * - `pre` 预格式化块 —— 语料 **2021** 处
         * - `kbd` 键盘输入 —— 语料 14 处
         * - `tt` 老式打字机文本、`samp` 程序输出 —— 本仓语料 0 处，
         *   但**书商转换器爱用老标签**，同样要进。
         *
         * ## 刻意排除的（**第一版凭「浏览器也这么定」印象加错，逐条核对 ua.css 后删掉**）
         *
         * - `var` —— ua.css:40 给的是 `dfn, cite, var { font-style: italic }`，
         *   **斜体而非等宽**。它装的是数学变量名（`width`），按音节断词才对；
         *   给它强加驼峰规则（`maxWidth` 当标识符）是误判。
         * - `data` —— ua.css 里**没有**任何规则，凭想象加的。
         * - `acronym` / `abbr` —— 语料 585 / 39 处，但是**术语缩写**而非代码
         *   （浏览器也只给 `abbr` 加虚线下划线）。误判会给 `HTTP` 这类词禁掉音节断词。
         *
         * ## `pre` 的 white-space 是 `pre-wrap` 不是 `pre`（有意偏离浏览器）
         *
         * ua.css:32 写的是 `pre { white-space: pre-wrap; }`。浏览器标准是 `pre`（长行不折、
         * 靠横向滚动），本项目改成 `pre-wrap`（长行仍按版心折行）——分页阅读器没有横向滚动条，
         * `pre` 语义是「保留格式」而非「禁止折行」，一行 200 字符的代码必须能折否则被裁掉。
         * **⇒ `pre-wrap` 仍满足 `WhiteSpaceNormalize.wraps() == true` ⇒ `pre` 的断行器会被调用，
         * 本 source 对 `pre` 有效**（曾因误以为「本仓没有 UA 样式表」而给出同一结论，理由是错的）。
         *
         * ## 判据的另一面：`class` 通道不存在
         *
         * `class="language-*"` / `class="highlight"` 这类**类名**标记判不出来
         * （[ParagraphBreaker.breakLines] 签名被 S2 冻结，没有 class 形参）。
         * 实测语料里 `code`+`pre` 已覆盖 28000+ 处代码块，剩下的漏网形态已登记 docs 29g。
         */
        val CODE_TAGS = setOf("pre", "code", "kbd", "samp", "tt")
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
        val opp = BreakOpportunitySet.of(text, sourcesFor(tag))
        val hyphenW = hyphenWidths(
            n, opp, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns,
        )
        return greedy(
            text, adv, n,
            headPx = headPx,
            restPx = widthPx.toFloat(),
            opp = opp,
            hyphenW = hyphenW,
            targetLh = lineHeightPx(fontSizePx, lineHeightRatio),
        )
    }

    /**
     * **逐断点算出「若在该处断开、要补的连字符有多宽」**（数组与 `adv` 同长，非断词点为 0）。
     *
     * ## 为什么断行侧必须预留（不能只在绘制侧补画）
     *
     * 行尾那个连字符是**真实占版心的墨**。不预留的话，断行器会认为 `hyphen|ation` 放得下，
     * 画完连字符就**超出版心** —— 而分页阅读器没有横向滚动条，超出部分被页面裁掉 = 内容丢失
     * （同 `NoLineExceedsContentWidthTest` 钉的硬约束）。
     *
     * ## 为什么**不能**把连字符宽焊进 `adv[i-1]`（第一版就这么写，实测被打脸）
     *
     * `adv[i-1]` 是**行内任意位置都会累计**的量：一条行里若含有 3 个断词点，
     * 焊进去就等于**三份**连字符宽全被算进这一行，哪怕这一行只在**其中一处**断开。
     * 实测后果：`line count fairness holds on production configurations`
     * 的最差单格比值从 1.500 涨到 **2.0**（URL 格 360px 从 1 行变 2 行）、
     * 行数比值 0.9924 → 1.0102 —— 明明放得下却提前断行，正是这条的实现错误。
     *
     * ⇒ 正确形态是**独立数组，只在真正选中该断点的那一行生效**（见 [greedy]）。
     *
     * ## 软连字符（`&shy;`）与 K-L 断词共用这一个数组
     *
     * 区别只在**槽位**：SHY 的槽位是它自己那个字位（[LineAligner] 把 `adv[shySlot]`
     * 从 0 改成连字符宽），K-L 断词的槽位在行末之外。断行侧只管「宽多少」，不管画在哪。
     *
     * @return 长度 = `text.length`；下标 `j` = 「行尾是第 j 字、且此处可断词」时要补的宽，否则 0。
     */
    private fun hyphenWidths(
        n: Int,
        opp: BreakOpportunitySet,
        fontSizePx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun>,
    ): FloatArray {
        val out = FloatArray(n)
        // 缓存键必须覆盖**全部**决定 face 的参数：`<code>` 与正文字号相同但族不同，
        // 按字号单独缓存会让等宽段里的连字符按正文字体量宽（行尾对不齐）。
        val cache = HashMap<String, Float>()
        for (i in 1 until n) {
            if (!opp.isHyphenAt(i)) continue
            val at = i - 1
            val r = fontRuns.firstOrNull { it.start <= at && at < it.endExclusive }
            val size = r?.fontPxOr(fontSizePx) ?: fontSizePx
            val key = buildString {
                append(size.toRawBits()).append('\u0001')
                append(r?.tag ?: tag).append('\u0001')
                append(r?.families ?: families).append('\u0001')
                append(r?.weight ?: weight).append('\u0001')
                append(r?.italic ?: italic).append('\u0001')
                append(r?.monospace ?: monospace)
            }
            out[at] = cache.getOrPut(key) {
                measurer.hyphenWidthPx(
                    fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns, at,
                )
            }
        }
        return out
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
     *  - **断词断点的连字符宽只在「本行真断在那里」时计入**（[hyphenW]）：断点候选的判定宽
     *    是 `hang + hyphenW[brk-1]`，**不是**把连字符宽焊进 `adv`（第一版焊进 `adv` 导致
     *    一行里多个断词点被重复计宽、提前断行，实测最差单格比值 1.5→2.0，见 [hyphenWidths] KDoc）。
     *
     * 不变量（`s` 行首 / `i` 待消费）：
     *  [hang] = `[s, i)` 扣掉**紧贴行尾的那一段**空白后的宽（UAX#14 LB SP）；**不含**连字符宽；
     *  [trail] = 紧贴行尾的那一段空白的宽（`hang` 加下一个字符时若该字符非空白，这段要并回来）；
     *  [lastOpp] = 已消费范围内最后一个断点（`-1` = 一个都没有）；[lastOppWidth] = 该断点处的
     *  判定宽 `hang + hyphenW[lastOpp-1]`（断词断点的连字符**在这一行真的会出现**）。
     */
    private fun greedy(
        text: CharSequence,
        adv: FloatArray,
        n: Int,
        headPx: Float,
        restPx: Float,
        opp: BreakOpportunitySet,
        hyphenW: FloatArray,
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
            // 「最近断点处的判定宽」= 断点被记录那一刻的 hang（已扣行尾空白），随滚动增量维护，
            // 供退出循环后判「该断点是否装得下」。O(1) 更新，不能用重算（那是 O(n) × 行数）。
            var lastOppWidth = 0f
            var brk = -1
            // 本行行尾**是不是我们主动选中的断点**（而非 R1 core 兜底 / 硬换行）。
            // 这是 `hyphenAtEnd` 的前提，见下面产出处的注释。
            var brkIsOpp = false
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
                // **含该断点自己的连字符宽**：断在这里 ⇒ 行尾真会多一个 `-` ⇒ 它占版心。
                if (opp.opportunityAt(i)) { lastOpp = i; lastOppWidth = hang + hyphenW[i - 1] }
            }
            if (brk < 0) {
                brk = when {
                    i >= n -> n                       // 整段装下（含「尾随空白悬到段末」）
                    // 退到最近断点 —— 但**仅当它装得下**。
                    //
                    // ⚠ 原实现是 `lastOpp > s -> lastOpp` 无条件退回，于是「最近断点也放不下」
                    // 时会产出一行**超出版心的内容**。实测 `Donaudampfschiff…`（无空格超长单词）
                    // 在版心 80 下溢出 **32.45px**、首行缩进 88% + 版心 120 溢出 **65.07px**
                    // （`NoLineExceedsContentWidthTest` 钉住）。**分页阅读器不能容忍溢出**：
                    // 浏览器能横向滚动所以能溢出，本项目页宽固定、超出部分被页面裁掉 = 内容丢失。
                    // ⇒ 装不下时必须落到 R1 core（逐字断开、贪心填满版心），而不是溢出。
                    //
                    // 注意「记账点在 `i++` 之后」：`hang > avail` 的那一刻循环已 `break`，
                    // 故常规路径下 `lastOppWidth <= avail` 恒成立 —— 这条判定只在**版心窄到
                    // 连断点都装不下**（版心 80 / 缩进吃掉版心）时才真正生效。
                    lastOpp > s && lastOppWidth <= avail -> { brkIsOpp = true; lastOpp }
                    else -> i                          // R1 core：退无可退（含「最近断点也装不下」）→ 填满即断
                }
            }
            // 反自旋护栏（正常路径不可达：`brk > s` 由上面的 `i > s` 前置条件保证）。
            // 留着是因为一旦未来某个 source 标出 0 号断点，这里就是死循环而不是一次错行。
            if (brk <= s) brk = s + 1
            // **只有真正退到断点的那条路径才可能带连字符。**
            //
            // ⚠ R1 core（`brk = i`）与硬换行（`text[brk] == '\n'`）这两条路径的行宽
            //   **从未把连字符宽算进过可用性判定** —— 它们是在「装不下」的前提下退到
            //   「贪心填满」的。此处若照抄 `opp.isHyphenAt(brk)`，就会给一条
            //   **放不下连字符**的行补上一个连字符 ⇒ **超出版心被裁**（分页阅读器硬错误）。
            //   实测：`hyphenation extraordinarily` @版心 80 的 `n ex` 行
            //   （字符宽 70.53，`isHyphenAt(14)=true` 但 [lastOppWidth]=83.73 > 80 走了 R1 core）
            //   ⇒ 补上 13.20 的连字符后行宽 **83.73 > 80**，溢出 3.73px。
            //
            // ⇒ 连字符是「**主动选了某个断点**」的产物，[brkIsOpp] 才是它的前提；
            //   而 [lastOppWidth <= avail] 这个门槛保证了「选中的断点连同连字符一起装得下」。
            out.add(BrokenLine(s until brk, targetLh, hyphenAtEnd = brkIsOpp && opp.isHyphenAt(brk)))
            s = brk
        }
        if (out.isEmpty()) out.add(BrokenLine(0 until n, targetLh))
        return out
    }
}
