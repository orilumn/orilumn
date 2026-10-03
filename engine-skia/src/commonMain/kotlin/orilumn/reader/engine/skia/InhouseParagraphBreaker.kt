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
import orilumn.reader.engine.laying.RegionScopedBreakSource
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.laying.lineHeightPx
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

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
    /**
     * CJK–Latin 自动间距（em）—— **断行侧的量宽必须把它算进版心**（[SkiaRunMeasurer.advances]
     * 的单一出口，见该方法 KDoc）。
     *
     * 0 = 关（默认）。由 [bodyParagraphBreaker] 按 [orilumn.reader.engine.AbSwitch.inhouseBreak]
     * 闸门传入，回退到 Skia 断行器时**必须**传 0：Skia 那条路无法为间隙预留版心，
     * 若绘制侧照插间隙就会**超出版心被裁**（`NoLineExceedsContentWidthTest` 钉的硬约束）。
     */
    private val cjkLatinSpacingEm: Float = 0f,
    /**
     * 标点挤压上限（em，0 = 关）—— 默认读 [PunctuationSqueeze.appliedMaxEm]（回退阀闸门后的单源值）。
     *
     * 与 [cjkLatinSpacingEm] 的方向**相反**（那个是**注入**宽度、本项是**让出**宽度）：
     * 注入宽度必须两侧同进同退否则溢出裁字，让出宽度只可能让行变短 ⇒ 最坏是排得松一点，
     * **不可能超出版心**。所以闸门关掉时即使照挤也不违反 [NoLineExceedsContentWidthTest]，
     * 但那会让回退到 Skia 断点的那一侧排得比基线松 ⇒ 仍按闸门关掉，理由是**回退保真**。
     */
    private val punctuationSqueezeMaxEm: Float = PunctuationSqueeze.appliedMaxEm(),
    private val measurer: SkiaRunMeasurer = SkiaRunMeasurer(),
    /** 断点增强器（顺序无关，S2(b) 冻结的形状）。本轮只接禁则表一个 source；S7 只加 source。 */
    private val breakSources: List<BreakOpportunitySource> = listOf(KinsokuBreakSource),
) : ParagraphBreaker {

    /**
     * S7 + Q18：**代码语境**的 source 集 —— 整块代码（叶块 tag ∈ [CODE_TAGS]）与**行内代码 run**
     * （[codeInteriorMask] 掩码出的内区）**共用这一个**，保证「同一个引擎对同一种代码只给一套规则」。
     *
     * 组成：禁则（[breakSources]）+ 软连字符（[SoftHyphenBreakSource]，两个区都要 —— `&shy;` 是
     * **作者显式写进正文的**断点意图，即使它出现在 `<code>` 里也是有意的）+ [CodeIdentifierBreakSource]
     * （标识符分隔符后 + 驼峰交界）。**不给**音节断词：代码标识符按音节断是错的
     * （`parse_config_file` → `parse_con-fig_file`；见 [CodeIdentifierBreakSource] KDoc）。
     *
     * 行内代码**也**注入 [CodeIdentifierBreakSource]（Q18②，实测已定）：否则窄版心下 R1 会硬切在
     * 字母中间（`wrapping_add_w` ‖ `ith_capacity_c`），正是用户报的那一类难看。
     */
    private fun codeSources(): List<BreakOpportunitySource> =
        breakSources + SoftHyphenBreakSource + CodeIdentifierBreakSource

    /**
     * **散文语境**的 source 集：禁则 + 软连字符 + [EnglishHyphenationSource]（K-L 音节断点，R2 的落点）。
     *
     * S7：`lang` 未声明（空）时按 en —— 书库里绝大多数非中文段落是英文，
     * 且 [EnglishHyphenationSource.forLang] 对未加载语言退到 [NoHyphenation]（= R1 兜底），
     * 不会误用 en 表去断德语/法语词。真正按 `lang` 分表需要样式通道，见 docs 29g。
     */
    private fun proseSources(): List<BreakOpportunitySource> =
        breakSources + SoftHyphenBreakSource + EnglishHyphenationSource.forLang("en")

    /**
     * Q18：收集「代码 run」的下标区间，交给 [RegionScopedBreakSource.maskOf] 算位置掩码。
     *
     * ## 判据 = run 的 `tag` 落在 [CODE_TAGS]
     *
     * 与「整块代码」分支**同一个判据**（不是新的一套）：代码性在 CSS 里是元素属性，
     * 而本仓能拿到的最细粒度就是 [FontRun.tag]（[orilumn.reader.engine.laying.collectFontRuns]
     * 对文本节点取 `node.parent?.tag` ⇒ `<code>` 里的文本 run 正是 `tag = "code"`；已实测确认）。
     *
     * **刻意不用 `run.monospace` 作为判据**：那会把「非代码但等宽」的文本（作者手写
     * `font-family: monospace` 的 `<span>`）也切进代码规则，而本仓对那类文本没有断词口径的裁决。
     * 宁可漏（退回散文规则 = 现状），不可误判。
     *
     * ## 「哪些位置算内区」的判据不在本类
     *
     * 在 [RegionScopedBreakSource.maskOf]（两侧字符都在代码内 ⇒ 边界归外区）。本类只负责
     * 「哪些**字符**是代码」。分家是因为那条判据**在生产版心下观察不到**（`&&` 与 `||` 只在
     * min-content 单元内部有别，而生产恒有 `widthPx ≥ ceil(minContentWidth)`），
     * 只有断点集层能钉住它 ⇒ 判据必须落在可单测的那一侧。
     *
     * @return `null` = 整段没有任何代码 run（调用点直接走整段老路径，热路径零额外开销）。
     */
    private fun codeInteriorMask(n: Int, fontRuns: List<FontRun>): BooleanArray? {
        if (fontRuns.isEmpty()) return null
        val spans = ArrayList<IntRange>(2)
        for (r in fontRuns) {
            if (r.tag == null || r.tag !in CODE_TAGS) continue
            if (r.endExclusive <= r.start) continue
            spans.add(r.start until r.endExclusive)
        }
        return RegionScopedBreakSource.maskOf(n, spans)
    }

    /**
     * Q18 接线点（断点集的唯一入口）。三条路径：
     *
     * 1. **叶块本身就是代码**（tag ∈ [CODE_TAGS]）→ 整段 [codeSources]，**不分区**。
     *    刻意不分区：`<pre>` 里可以嵌 `<em>`/`<span>`（那些 run 的 tag 不是代码 tag），
     *    一旦分区它们就会落进散文规则、被音节断词切开 —— 而 `pre` 整块都是代码，**不能**切。
     * 2. **有行内代码 run** → [RegionScopedBreakSource] 按 [codeInteriorMask] 分区。
     * 3. **都没有** → 整段 [proseSources]（与本轮改动前**逐值相同**，普通段落零回归）。
     *
     * 已知缺口（不在本轮）：`class="highlight"` 的代码块 tag 仍是 `p`，靠 tag 判不出来 ——
     * 要读 `class`/`style`，登记在 docs 29g。
     */
    private fun breakOpportunities(text: CharSequence, tag: String?, fontRuns: List<FontRun>): BreakOpportunitySet {
        if (tag != null && tag in CODE_TAGS) return BreakOpportunitySet.of(text, codeSources())
        val mask = codeInteriorMask(text.length, fontRuns)
            ?: return BreakOpportunitySet.of(text, proseSources())
        return BreakOpportunitySet.of(
            text,
            listOf(RegionScopedBreakSource(mask, inner = codeSources(), outer = proseSources())),
        )
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
         * 浏览器标准是 `pre`（长行不折、靠横向滚动），本项目要 `pre-wrap`（长行仍按版心折行）
         * ——分页阅读器没有横向滚动条，`pre` 语义是「保留格式」而非「禁止折行」，
         * 一行 200 字符的代码必须能折否则被裁掉。
         * **⇒ `pre-wrap` 满足 `WhiteSpaceNormalize.wraps() == true` ⇒ `pre` 的断行器会被调用，
         * 本 source 对 `pre` 有效**（曾因误以为「本仓没有 UA 样式表」而给出同一结论，理由是错的）。
         *
         * ⚠ **不要再拿 `ua.css:32` 的 `pre { white-space: pre-wrap; }` 当依据**：
         * UA 是最低优先级、书一声明就压过它。真书《Rust 程序设计语言》
         * （`book_1790865097552.epub` / `OEBPS/Styles/stylesheet.css:144`）写的正是
         * `pre code { white-space: nowrap; }` —— 合法值、照做、整个代码块连成一段并被裁。
         * 真正保证 `pre` 子树拿到 `pre-wrap` 的是**级联层**的
         * `StyleComputer.resolveWhiteSpace`（预格式化语境里把 `pre`/`nowrap` 降级成 `pre-wrap`）。
         *
         * ## 判据的另一面：`class` 通道不存在
         *
         * `class="language-*"` / `class="highlight"` 这类**类名**标记判不出来
         * （[ParagraphBreaker.breakLines] 签名被 S2 冻结，没有 class 形参）。
         * 实测语料里 `code`+`pre` 已覆盖 28000+ 处代码块，剩下的漏网形态已登记 docs 29g。
         */
        val CODE_TAGS = setOf("pre", "code", "kbd", "samp", "tt")

        /**
         * 断点回退历史长度（[greedy] 一次最多往回看几个断点）。
         *
         * 理论下限是 **2**：连续 K-L 断点间距 ≥2 字符，而「最近断点装不下」的 `hang` 窗口宽度
         * 只有一个连字符宽（≈1 字符），故连续失败至多 1 个。取 4 兜住字距 / 混合字体下的抖动。
         * 退到底仍装不下 → R1 core（不溢出），**安全兜底不会退化**。
         */
        const val OPP_HISTORY = 4
    }

    /**
     * max-content 宽（px）—— **Q6：表格 auto 分列的列宽量真测量**（本类曾无覆写，落回接口
     * 默认桩 `text.length * fontSizePx`，实测 Latin 2.49x / URL 2.09x，于是两侧列宽被迫钉回 Skia）。
     *
     * ## 为什么是 [SkiaRunMeasurer.naturalWidth] 而不是「再排一遍」
     *
     * max-content 的定义是「**不折行**时的整段宽」，而本类的 [breakLines] 在 [widthPx] 内必然折行，
     * 拿它量等于把「排版结果」当「内容固有宽度」，是另一个量。
     * [SkiaRunMeasurer.naturalWidth] 走的是**与断行完全相同的 `advances` 单源**（逐码本 × 逐字形回退），
     * 差别只是**不施加版心宽、不找断点** —— 即「同一批字形宽，加起来」。
     * ⇒ 因此本方法与 [breakLines] **量源同源**，不存在「量宽走一条、排版走另一条」的分叉。
     *
     * ## 标点挤压也在这条「同源」里（2026-10-03 补）
     *
     * 挤压让字位变**窄**，所以 max-content 必须**减掉**同一份额度，否则同一个 `adv` 会被两个方法
     * 读出两个答案。本方法在补上这一减之前，端到端锁实测出行宽 **706.2265 > 版心 700**。
     *
     * ## `tag` 传 `null` 而不是原样传下去
     *
     * 与 [SkiaParagraphBreaker.preferredWidth] 同口径（那里 `paragraphStyle(..., tag = null, ...)`）。
     * 表格格子的 `mono` 已由调用方 `NormalFlowLayout.tableCellPref` 算成
     * `style.monospace || tag == "pre"` 并**显式传进 `monospace` 形参**，
     * 此处再按 `tag` 二次判定会让 `pre` 格被重复施加。
     *
     * ## 与 Skia 侧不可避免的差：整形 vs 不整形
     *
     * Skia 侧走 HarfBuzz（有 kern / liga），本方法走裸 cmap ⇒ 本方法**永不窄于** Skia 侧
     * （整形缺口全为负，见 `docs/TODO-未尽事宜.md` Q5）。差值量级由
     * `TableColumnWidthRealMeasureTest` 钉住（Latin 每万字符 25–32 处 kern，最大 −3.52px @ Times New Roman）。
     * 方向是**偏宽**，符合 [ParagraphBreaker.preferredWidth] 契约里「永不窄于实需」那一句。
     */
    override fun preferredWidth(
        text: CharSequence,
        fontSizePx: Float,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun>,
    ): Float {
        if (text.isEmpty()) return 0f
        val n = text.length
        val gaps = if (cjkLatinSpacingEm > 0f) {
            CjkLatinSpacing.gaps(text, cjkLatinSpacingEm, fontRuns)
        } else {
            emptyList()
        }
        val adv = measurer.advances(text, fontSizePx, letterSpacingEm, null, families, weight, italic, monospace, fontRuns, gaps)
        var sum = 0f
        for (a in adv) sum += a
        // 标点挤压**必须从 max-content 里也减掉**（与 [breakLines] 同一个 [PunctuationSqueeze.widths]）。
        //
        // ⚠ 漏这一步的后果不是「表格列宽偏大一点」这么轻：max-content 是**「整段放得下一行吗」**的判据
        //   （表格 auto 分列、单行快路径都问它）。断行侧按挤后的宽度判「放得下」、max-content 按没挤的
        //   宽度答「放得下」⇒ 同一段两套答案 ⇒ 画比量宽 ⇒ 右溢被裁。
        //   实测（`CjkLatinSpacingWiringTest` 的端到端锁）：版心 700 的行量出 **706.2265**。
        //   —— 那把锁一直拿本方法当「绘制侧宽度」用，所以它当场抓到了这个分叉。
        val squeeze = PunctuationSqueeze.widths(
            text, 0, n, adv, 0, measurer, fontSizePx, letterSpacingEm,
            null, families, weight, italic, monospace, fontRuns, punctuationSqueezeMaxEm,
        )
        for (s in squeeze) sum -= s
        return sum
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

        // 混排字距的间隙就在这里被算进 `adv`（[SkiaRunMeasurer.applyCjkLatinGaps]）——
        // **不在别处**：断行要预留版心、绘制要落在同一批字位上，两者必须走同一个取宽口（量画同源，教训 ⑩）。
        val gaps = if (cjkLatinSpacingEm > 0f) {
            CjkLatinSpacing.gaps(text, cjkLatinSpacingEm, fontRuns)
        } else {
            emptyList()
        }
        val adv = measurer.advances(text, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns, gaps)
        // 首行可用宽直接扣缩进 —— SkiaParagraphBreaker 那段「TextIndent 整段单行快捷路径」的
        // R1 补偿在新实现下自然消失（本类没有那条快捷路径，首行从一开始就按 CSS 语义排）。
        val headPx = widthPx - firstLineIndentPx
        if (headPx <= 0f) {
            // 缩进已吃满版心：整段一行（再细分只会每行都放不下一个字符）。
            return listOf(BrokenLine(0 until n, lineHeightPx(fontSizePx, lineHeightRatio)))
        }
        val opp = breakOpportunities(text, tag, fontRuns)
        val hyphenW = hyphenWidths(
            n, opp, fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace, fontRuns,
        )
        // 标点挤压的**预留**（与 [LineAligner] 画侧同一个 [PunctuationSqueeze.widths]）。
        // ⚠ 必须在**整段**上算而不是按行算：S 是「位置」的属性，与行怎么切无关；
        //   按行算则两侧的分段边界可能不同（行是由本方法切的）⇒ 同一位置两个 S ⇒ 量画失配。
        val squeezeW = PunctuationSqueeze.widths(
            text, 0, n, adv, 0, measurer, fontSizePx, letterSpacingEm,
            tag, families, weight, italic, monospace, fontRuns, punctuationSqueezeMaxEm,
        )
        return greedy(
            text, adv, n,
            headPx = headPx,
            restPx = widthPx.toFloat(),
            opp = opp,
            hyphenW = hyphenW,
            squeezeW = squeezeW,
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
     *  - **取「可达断点里最靠右、且装得下的那个」**：能填多宽填多宽，溢出则**从最近的断点起往回退**，
     *    退到第一个装得下的为止（**含它自己的连字符宽**），全都不装得下才落 R1 core。
     *    「往回退」不是可选项 —— 见下面「为什么必须回退」那条。
     *  - 行尾空白只扣**紧贴行尾的那一段**，行内空白照算。反例锁死这条：
     *    `填充填   填充填` @350 断在 `[0,6)`（"填充填␣␣␣" 判定宽 300），若把行内 3 个空格也免掉则断在 `[0,7)`。
     *  - **`\n` 硬断**，行区间不含它；行首「预领」的 `\n` 跳过 → `a\n\nb` 得 2 行不是 3 行。
     *  - **R1 core**：所有断点（含连字符）都装不下才在此断开（贪心填满，不是溢出）。
     *    见类 KDoc 的行为规格。
     *  - **断词断点的连字符宽只在「本行真断在那里」时计入**（[hyphenW]）：断点候选的判定宽
     *    是 `hang + hyphenW[brk-1]`，**不是**把连字符宽焊进 `adv`（第一版焊进 `adv` 导致
     *    一行里多个断词点被重复计宽、提前断行，实测最差单格比值 1.5→2.0，见 [hyphenWidths] KDoc）。
     *
     * ## 为什么「最近断点装不下」必须**往回退**，不能原地硬切（本轮实测抓到的真缺陷）
     *
     * 单槽 `lastOpp` 的写法是：`lastOppWidth > avail` 就直接落 R1 core（`brk = i`）。但
     * `hang <= avail` 恒成立（循环只在装得下时才 `hang = next`），故 `lastOppWidth > avail`
     * **只可能是「差一个连字符宽」**。而此时 `i` 往往**恰好等于 `lastOpp`**（`i` 就是那个
     * 装不下连字符的断点位置），于是产出一条 **「在断词点断开、却没有连字符」**的行 ——
     * 词看起来被硬切，正是用户报的「软连字符没有加，`dependen-`/`cies`、`com-`/`piled`
     * 两处都不加」。
     *
     * 实测（Rust 书 22 章、版心 1600）：`isHyphenAt(brk)=true` 却 `brkIsOpp=false` 共 **45 行**，
     * 全部形如 `gap = avail − lastOppWidth ∈ [−23, −1]`（只差 1~23px），例如
     * ```
     *   '…它就会返回 Re|'   avail=1600  hang=1596.98  hyW=24.66  gap=−21.64
     *   '…已定义 an|'       avail=1515.64  hang=1491.27  hyW=24.66  gap=−3.29
     * ```
     * ⇒ 字符本身装得下，只差一个连字符。**三个选项里只有「回退」是对的**：
     *  - 原地断、不给连字符（现状）：词被无声切坏，读者分不清是断词还是原文如此。
     *  - 原地断、给连字符：**超出版心 = 内容被裁**（分页阅读器硬错误，
     *    `NoLineExceedsContentWidthTest` 钉住）。
     *  - 往回退到上一个装得下的断点：CSS Text 3 §5.2 贪心的定义本身，浏览器亦如此。
     *
     * 回退深度 [OPP_HISTORY]：连续 K-L 断点间距 ≥2 个字符，而「装不下」的 `hang` 窗口宽度
     * 只有一个连字符宽（≈1 字符），故**连续失败最多 1 个**，2 就够。取 [OPP_HISTORY] 兜住
     * 字距/混合字体下的抖动；退到底仍装不下就是 R1 core（不溢出，安全兜底）。
     *
     * 不变量（`s` 行首 / `i` 待消费）：
     *  [hang] = `[s, i)` 扣掉**紧贴行尾的那一段**空白后的宽（UAX#14 LB SP）；**不含**连字符宽；
     *  [trail] = 紧贴行尾的那一段空白的宽（`hang` 加下一个字符时若该字符非空白，这段要并回来）；
     *  [oppIdx]/[oppW] = 本行已消费范围内**最近 [OPP_HISTORY] 个断点**及其判定宽
     *  （`hang + hyphenW[下标-1]`，断词断点的连字符**在这一行真的会出现**），按记录顺序；
     *  取断点时从**最新**往回扫，取第一个 `下标 > s 且 判定宽 <= avail` 的。
     */
    private fun greedy(
        text: CharSequence,
        adv: FloatArray,
        n: Int,
        headPx: Float,
        restPx: Float,
        opp: BreakOpportunitySet,
        hyphenW: FloatArray,
        /**
         * 标点挤压的逐位置额度（px，与 `adv` 同长同坐标；见 [PunctuationSqueeze.widths]）。
         *
         * 在 [greedy] 里**只做一件事**：`val w = adv[i] − squeezeW[i]`。
         * 不焊进 `adv` 本身，理由同 [hyphenW]：焊进去就得让断点记账、尾空白记账、
         * 以及 `preferredWidth` 三处同时改口径，漏一处就静默失配。
         */
        squeezeW: FloatArray,
        targetLh: Int,
    ): List<BrokenLine> {
        val out = ArrayList<BrokenLine>(8)
        // 断点历史缓冲（整趟共用，逐行只重置计数）。定长：回退深度需求见 KDoc（2 就够，取 4 留抖动余量）。
        val oppIdx = IntArray(OPP_HISTORY)
        val oppW = FloatArray(OPP_HISTORY)
        var s = 0
        var first = true
        while (s < n) {
            // 行首遇硬换行 = **空行**（连续换行之间的空段）⇒ 产出一行**零宽**行。
            //
            // ⚠ 早前的写法是 `s++; continue` —— 不产出任何行 ⇒ `pre code` 块里的空行**全部消失**
            //   （用户真机报）。根因不是渲染层：`LineAligner` 对零宽行正常返回空 `Placement`，
            //   `DrawLineBuilder` 也不丢零宽行，**零宽空行一旦被生产出来就能正常画**。
            //   而且 `WhiteSpaceBreak.breakLeafLines` 的**不折行分支**（`PRE`/`NOWRAP`）一直
            //   就是这么产的（`:44` 的 `else if (i < n)` 分支）—— 只有 `wraps()` 的这条
            //   （`pre` 被 [StyleComputer.resolveWhiteSpace] 降级成 `PRE_WRAP` ⇒ `wraps()==true`）
            //   在丢。⇒ 两个分支此前**口径不一致**，本行就是对齐点。
            //
            // 末尾的 `'\n'` **一般**不产行（`s + 1 < n` 门槛，与 [WhiteSpaceBreak.breakLeafLines] 的
            // 「以 `\n` 结尾不产生额外空行」同一条）：EPUB 源码惯用 `<pre>…\n</pre>`，多一行空白是噪声。
            // **唯一例外**：最后两个字符都是 `'\n'` 时（`a\n\n`），末尾那个换行终止的是**一个真实空行**
            // （CSS：段「a」「」「」三行，去掉未终结的末行仍是两行），必须保留。
            // 门槛写这么长是因为两侧要逐条一致 —— Skia 侧把这个空行与末尾幻影行**合并成同一条非零宽指标**
            // （实测 `[0..1, 2..3, 2..3]`），归一化剔掉幻影行后要靠字符补回来，见 [SkiaParagraphBreaker.layoutOnce]。
            if (text[s] == '\n') {
                val trailingTerminator = s + 1 == n && (s == 0 || text[s - 1] != '\n')
                if (!trailingTerminator) out.add(BrokenLine(s until s, targetLh))
                s++
                continue
            }
            val avail = if (first) headPx else restPx
            first = false
            var i = s
            var hang = 0f
            var trail = 0f
            // 本行的断点历史（按下标升序，[oppN-1] 是最近的）。判定宽随滚动增量维护，
            // 供退出循环后**从最近往回**扫「装得下的那个」。不能重算（那是 O(n) × 行数 × 历史长）。
            var oppN = 0
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
                // 标点挤压：这一格**实际占的宽**比 [advances] 量出的少 `squeezeW[i]`。
                // 判定宽、尾空白记账、断点记账全部走这一个 `w` ⇒ 一处施加、三处生效。
                val w = adv[i] - squeezeW[i]
                val sp = isDocumentSpace(c)
                // 续上行尾空白段 → 判定宽不变；落到非空白 → 先把那段并回来再算本字符。
                val next = if (sp) hang else hang + trail + w
                if (i > s && next > avail) break
                hang = next
                trail = if (sp) trail + w else 0f
                i++
                // **含该断点自己的连字符宽**：断在这里 ⇒ 行尾真会多一个 `-` ⇒ 它占版心。
                if (opp.opportunityAt(i)) {
                    if (oppN < OPP_HISTORY) { oppIdx[oppN] = i; oppW[oppN] = hang + hyphenW[i - 1]; oppN++ }
                    else {
                        // 历史满：丢最旧的一个（整体左移一位）。
                        // 只在「一行里断点多于 OPP_HISTORY」时触发（版心 1600 / 40px 字 ⇒ 一行 ~40 断点），
                        // [OPP_HISTORY] 次移位，可接受。
                        System.arraycopy(oppIdx, 1, oppIdx, 0, OPP_HISTORY - 1)
                        System.arraycopy(oppW, 1, oppW, 0, OPP_HISTORY - 1)
                        oppIdx[OPP_HISTORY - 1] = i; oppW[OPP_HISTORY - 1] = hang + hyphenW[i - 1]
                    }
                }
            }
            if (brk < 0) {
                brk = when {
                    i >= n -> n                       // 整段装下（含「尾随空白悬到段末」）
                    // 退到断点 —— 但**必须逐个往回退到装得下的那个**。
                    //
                    // ⚠ 最早的写法是 `lastOpp > s -> lastOpp` 无条件退回，于是「最近断点也放不下」
                    // 时会产出一行**超出版心的内容**。实测 `Donaudampfschiff…`（无空格超长单词）
                    // 在版心 80 下溢出 **32.45px**、首行缩进 88% + 版心 120 溢出 **65.07px**
                    // （`NoLineExceedsContentWidthTest` 钉住）。**分页阅读器不能容忍溢出**：
                    // 浏览器能横向滚动所以能溢出，本项目页宽固定、超出部分被页面裁掉 = 内容丢失。
                    //
                    // ⚠ 第二版改成单槽 + `lastOppWidth <= avail` 门槛，溢出是消了，但**把
                    // 「差一个连字符宽」当成了「退无可退」** → 原地硬切又不给连字符（词被无声
                    // 切坏）。真值只有第三个：**往回退**。见上面「为什么必须往回退」。
                    //
                    // 注意「记账点在 `i++` 之后」：`hang > avail` 的那一刻循环已 `break`，
                    // 故常规路径下 `oppW <= avail` 恒成立 —— 这里的判定真正生效的只有两种情形：
                    // ① **差一个连字符宽**（最常见，实测 gap ∈ [−23, −1]px）；
                    // ② 版心窄到连断点都装不下（版心 80 / 缩进吃掉版心）⇒ 退到底仍失败
                    //    才是 R1 core（逐字断开、贪心填满版心），不是溢出。
                    else -> {
                        // 从最近的断点**往回退**，取第一个装得下的（贪心的定义，见 KDoc）。
                        var k = oppN - 1
                        while (k >= 0 && (oppIdx[k] <= s || oppW[k] > avail)) k--
                        if (k >= 0) { brkIsOpp = true; oppIdx[k] } else i   // 退无可退 → R1 core：填满即断
                    }
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
            // `brk` 落在硬换行上时 `text[brk]` 就是**刚这一行的终止符**，不是空行的开头。
            // 跨过去，行首的 `'\n'` 判据才只表示**真正的空行**（否则 `a\n\nb` 会多产一行）。
            s = if (brk < n && text[brk] == '\n') brk + 1 else brk
        }
        if (out.isEmpty()) out.add(BrokenLine(0 until n, targetLh))
        return out
    }
}
