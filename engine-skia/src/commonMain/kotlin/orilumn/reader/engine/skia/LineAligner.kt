package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.laying.isSoftHyphen
import orilumn.reader.engine.laying.lineHeightPx
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * S4 行内几何对齐器（**渲染层**）：把一行文本的字符区间落成逐字 x 坐标，并给出
 * 「行末可见右边界」。这是 S5 逐字绘制的前置件 —— 有了 placement，S5 只需按 x 逐字
 * `drawString`，不必再让 `ParagraphBuilder` 二次整形。
 *
 * ## 为什么需要它（真机实测缺陷，不是设想）
 *
 * 端到端跑真书（`Rust 程序设计语言`，`p { text-align: justify }`）时量到：
 * 版心 900、`STSong/serif` fs=47，中部行右边界在 **900.0 / 846.0 / 900.0 / 750.4** 之间跳。
 * 逐行尾码位打印定位到规律：**行末是文档空白（`U+0020`）时 JUSTIFY 拉伸量为 `+0.0`**，
 * 行末是汉字时才拉伸到版心。断行侧（[InhouseParagraphBreaker] 的 `hang`/`trail` 记账）
 * 判定宽**已经扣掉**尾空白，但那份信息没传下来 —— 绘制侧
 * （[LineWindowDrawer.paintText]）把 range 原样喂 `ParagraphBuilder`，Skia 按 CSS 2.1 §3.1
 * 挂起尾随空白、不拉伸，缺口也不消 ⇒ 视觉参差。**信息在断行→绘制之间丢了。**
 *
 * ## 本类就是那个传递通道
 *
 * 断行侧的「行末空白不占版心」由 [isDocumentSpace]（与断点判定**同一份单源**）在此复现：
 * 尾随文档空白**计入 range 但不计入可见宽度、且不参与拉伸**。这样 JUSTIFY 的拉伸基数
 * 回到「最后一个可见字形的右边缘」，两端对齐才看得到。
 *
 * ## 与断行侧的两条口径（**不要单方面「修正」**）
 *
 * 1. **`lsPx` 按码本数、含末字符**（`Σadv + n·lsPx`，[SkiaRunMeasurer] KDoc 已冻结）。
 *    直觉上「间隙只有 n−1 个」，但这套模型把 `lsPx` 当**每码本附加量**记账。
 *    本类的 `x_i = x_0 + Σ(w_j + lsPx_j)` 必须沿用同一口径，否则量画失配（教训 20）。
 *    —— 已有人栽过：看到「末字后多算一个 ls」就去改 `advances`，那会让 S2(a)/S4/S5 三处口径分家。
 * 2. **尾随文档空白不进可见宽度**，但**仍在 range 内**（字形仍要画，见下）。
 *
 * ## 尾随空白「不计入可见宽度」但仍要绘制
 *
 * 空白字形本身不可见（无墨），绘制它无害；而若把它从 range 剔除，下一行的
 * `first` 与本行的 `last` 就不再相接，`DrawLineBuilder` 拼行会出现**无主字符**。
 * 故本类保留它的 placement（x = 前一字右边缘），只是**不把它算进 `visibleRight`**，
 * 也不让它参与拉伸分配。**区间无缝是硬约束**，改 range 是 S4 明确不做的事。
 */
internal class LineAligner(
    private val measurer: SkiaRunMeasurer = SkiaRunMeasurer(),
) {

    /** 一行逐字落位结果。`x[i]` 对应 `range[i]` 的字符（与 [range] 同坐标、同长度）。 */
    data class Placement(
        /** 行内逐字 x（首字为 `x0`，末字为 `x0 + Σ(w+ls)`）。与 range 同长。 */
        val xs: FloatArray,
        /**
         * 与 [xs] 同长的**逐字 advance**（已含该码本的 `lsPx`，未含 JUSTIFY 拉伸量）。
         *
         * 单独存一份而不是从 `xs` 相减反推：ruby 注音要**每个基字的精确字形盒宽**来居中，
         * 反推会在末字（无后继）与含拉伸的区间上失真（教训：能用原始量就别用二次推算）。
         */
        val advs: FloatArray,
        /**
         * 本行**可见右边界** = 最后一个非文档空白字符的**右边缘**（含该字符本体的宽，
         * **不含**它之后那个不可见的 `lsPx`）。末行/全空白行退到行首。
         *
         * 判据用途：行尾空区（`x > visibleRight` 不该吸附到行尾字形）、下划线/着重号的区间右界。
         */
        val visibleRight: Float,
        /** 行末连续文档空白的 x 起点（= 尾随空白**之前**那个字符的右边缘；无尾空白则 = [visibleRight]）。 */
        val trailStartX: Float,
        /**
         * **行尾连字符宽**（px）：本行断词收尾（`hyphens: auto` 的音节断点，或 `&shy;` 落在行末）时
         * 行尾那个 [orilumn.reader.engine.laying.HYPHEN_GLYPH] 的宽；无连字符恒 `0f`。
         *
         * 连字符**已经计入** [visibleRight] / [trailStartX]（它占版心，不能溢出），
         * 本字段只告诉绘制侧「那个位置要画 `-`、画多宽」。
         */
        val hyphenWidth: Float = 0f,
        /**
         * **本行的 slack 被优先级额度封顶了**（真 = 留缺口，[visibleRight] < 行宽）。
         *
         * 唯一成因（[JustifySlack.plan] 的既定行为）：有限级 0/1/2 全部吃满、
         * 而行里**一个级 3（西文词内）槽都没有** —— 即「整行无西文字母 + slack 大到每缝都
         * 超过该级上限」。此时**宁可右缘短一点也不把每个汉字都撑开**（实施方案 S4 当年写的
         * 「超出即放弃该行」被实测推翻，现在只在这一种情形下才放弃）。
         *
         * ## 为什么这个字段住在生产侧（不是给测试开的口子）
         *
         * 「这一行没铺满是缺陷还是设计」这条规则**只写在 [JustifySlack.plan] 里**。
         * 若让 `LineAlignerTest` 自己重算「有限级额度总和 vs slack」，那就成了**同一规则两份实现**
         * —— 本仓最贵的一类 bug（教训㩼：同一规则两处各判一次必然静默分叉）。
         * 所以这里只**汇报结果**，判定仍在 plan 里，测试只读这个布尔。
         */
        val justifyCapped: Boolean = false,
    )

    /** 连字符左缘（= [Placement.trailStartX] − [Placement.hyphenWidth]）；无连字符时 −1。 */
    fun hyphenXOf(p: Placement): Float =
        if (p.hyphenWidth > 0f) p.trailStartX - p.hyphenWidth else -1f

    /**
     * 把 `text[range]` 对齐落位。
     *
     * @param lineWidthPx 本行整形宽（[orilumn.reader.engine.skia.DrawLine.lineWidthPx]）。
     * @param justify 是否拉伸（[TextAlign.JUSTIFY] 且**非末行**时为 true —— 末行按定义左对齐）。
     * @param isLastLine 该行是否为段落末行。末行不拉伸（CSS 两端对齐的定义本身）。
     * @param hyphenAtEnd 本行是否断词收尾（[orilumn.reader.engine.laying.BrokenLine.hyphenAtEnd]）——
     *   行尾补一个连字符，**它占版心**：进 [Placement.natural] 参与的拉伸基数、进 [visibleRight]，
     *   否则要么超出版心被裁、要么 JUSTIFY 按「少一个字」铺满而右缘退进版心。
     * @param cjkLatinSpacingEm 混排字距（em，0 = 关）。**刻意放在形参表末尾**：前面的形参全是
     *   历史冻结签名，调用点里存在按位置传参的老写法（形如 `.align(t, r, fs, w, ls, tag, …, runs)`），
     *   插在中间会把后面那些实参静默重绑到别的形参上 —— `Boolean`/`Float` 之间那种重绑编译器
     *   **不报错**，症状是整本书的对齐全乱。末尾追加则既安全又显式。
     */
    fun align(
        text: CharSequence,
        range: IntRange,
        fontSizePx: Float,
        lineWidthPx: Float,
        letterSpacingEm: Float,
        tag: String?,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        fontRuns: List<FontRun> = emptyList(),
        align: TextAlign = TextAlign.LEFT,
        firstLineIndentPx: Float = 0f,
        isLastLine: Boolean = false,
        hyphenAtEnd: Boolean = false,
        cjkLatinSpacingEm: Float = 0f,
        /**
         * 标点挤压上限（em，0 = 关）。默认读 [PunctuationSqueeze.appliedMaxEm] ——
         * **与断行侧同一个来源**，所以两侧不可能一个挤一个不挤（量画同源，教训 ⑩）。
         *
         * 显式传参只给「要单独量一侧」的测试用；生产一律走默认值。
         */
        punctuationSqueezeMaxEm: Float = PunctuationSqueeze.appliedMaxEm(),
        /**
         * 本行**实际施加的标点挤压比例**（`[0,1]`，0 = 不挤），来自
         * [orilumn.reader.engine.laying.BrokenLine.squeezeRatio]。
         *
         * **断行侧定断点时用的是同一个比例**（[orilumn.reader.engine.skia.InhouseParagraphBreaker.greedy]
         * 「挤到词尾不破词」），所以这里必须原样套上去才能画 == 量；漏传（默认 0）会让画比量宽
         * ⇒ 右溢被裁（分页阅读器不能横向滚动）。
         *
         * 默认 0 = 完全不挤（绝大多数行；挤压只在「不挤就会把词切坏」时才发生，
         * 见 [PunctuationSqueeze.ratioNeeded]）。
         */
        squeezeRatio: Float = 0f,
    ): Placement {
        val start = range.first.coerceIn(0, text.length)
        val endExcl = (range.last + 1).coerceIn(start, text.length)
        val n = endExcl - start
        if (n <= 0) {
            return Placement(FloatArray(0), FloatArray(0), firstLineIndentPx, firstLineIndentPx)
        }

        // 取宽与断行侧同一出口（`adv[i]` 已含该码本的 `lsPx`，见类 KDoc 第 1 条）。
        //
        // ⚠ **必须传本行的子串**，不能传整段 `text`：本方法后续一律用**行内局部下标**
        // （`adv[k]`，k 从 0 起），而 [SkiaRunMeasurer.advances] 返回的数组与其 `text` 形参
        // **同一坐标**——传全串就得到全串绝对下标，再拿局部下标去索引即**整段错位**。
        // [sliceRuns] 把 run 坐标平移成局部，也正是配套「局部子串」的做法。
        //
        // 这个 bug 的暴露方式很隐蔽：**首行 `start == 0` 时恰好正确**，所以只测首行/
        // 只测单行的用例全绿；一旦有第二行，该行的逐字宽就全取自别的字符 ——
        // 实测超长单词在版心 80 下每个单字符行都量成 `30.46`（那是 `'D'` 的宽，`'f'` 实为 14.05），
        // 于是凭空冒出 11~65px 的「溢出」（`NoLineExceedsContentWidthTest` 抓到）。
        val lineRuns = sliceRuns(fontRuns, start, endExcl)
        val line = text.subSequence(start, endExcl)
        // 混排字距：间隙直接进 `adv`（由 [SkiaRunMeasurer.applyCjkLatinGaps] 施加）。
        //
        // ⚠ **必须在整段 `text` 上检测、再裁到本行**（[CjkLatinSpacing.gapsForRange]），
        //   不能按行子串检测：断行器预留的是**整段**那份结果（间隙 + 被吃掉的空格都算进了 `adv`），
        //   按子串检测会漏掉「挂在行尾空白里、仍在行区间内」的那几个被吃空格 ⇒
        //   绘制侧多出一个空格宽 ⇒ 画比量宽 ⇒ 右溢被裁（实测 fs=40/版心300 时正好 10.000px），
        //   且末行没有 JUSTIFY 兜底 ⇒ 英文与中文之间那个距离与滑块无关。详见
        //   [CjkLatinSpacing.gapsForRange] 的 KDoc。
        // ⚠ **不对 `cjkLatinSpacingEm == 0` 短路**（产品口径 2026-10-03）：0 档与其它档同规则 ——
        //   边界照检、被吃掉的分隔空格照吃（零宽）、注入间隙宽 = 0。短路会让 0 档独走一套版面
        //   （作者空格留着 ⇒ 中西之间还隔着一个整空格），那正是需求否掉的「0 的特例」。
        //   见 [CjkLatinSpacing] 类 KDoc「`gapEm = 0` 是参数为 0 的那一档」。
        val cjkGaps = CjkLatinSpacing.gapsForRange(text, start, endExcl, cjkLatinSpacingEm, fontRuns)
        val adv = measurer.advances(
            line, fontSizePx, letterSpacingEm, tag, families,
            weight, italic, monospace, lineRuns, cjkGaps,
        )
        // 标点挤压：**按比例收窄收尾类标点的字位**（渲染层·几何测量，[PunctuationSqueeze]）。
        //
        // ⚠ **必须在 `natural` 之前施加**：`slack = lineWidth − natural`，`natural` 少了 ΣS
        //   ⇒ `slack` 多出 ΣS ⇒ JUSTIFY 把它分摊出去 ⇒ 行末右缘**照旧贴版心**，
        //   变的是**墨迹**右缘（内移 S）与**总行数**（断行侧也按同一比例预留了，见 [InhouseParagraphBreaker]）。
        //   顺序反了就是「挤了但右缘跟着退」＝ 什么都没换到。
        //
        // ⚠ **`squeezeRatio` 是断行侧算出的那一个**（不是这里重算的）：挤压参与断点决策，
        //   两边各算一份就会在「挤到词尾 vs 多塞一个字」上分叉，而那种分叉表现为**画比量宽**。
        //
        // ⚠ `advBase = start`：本方法的 `adv` 是**行内局部**下标，而额度是**段级**属性
        //   （`inkLeft(i+1)` 要看下一行第一个字）。传 `start` 让 [PunctuationSqueeze.widths]
        //   自己换算，两侧因此能共用同一份实现 —— 传别的值就会与断行侧分叉。
        val squeeze = PunctuationSqueeze.widths(
            text, start, endExcl, adv, start, measurer, fontSizePx, letterSpacingEm,
            tag, families, weight, italic, monospace, fontRuns, punctuationSqueezeMaxEm,
        )
        for (k in 0 until n) if (squeeze[k] != 0f) adv[k] -= PunctuationSqueeze.slotSqueeze(squeeze[k], squeezeRatio)

        // 尾随文档空白：计入 range、画（无墨无害）、但**不计入可见宽度**。
        var visEnd = endExcl
        while (visEnd > start && isDocumentSpace(text[visEnd - 1])) visEnd--
        val trailLen = endExcl - visEnd
        val visibleCount = visEnd - start

        // ---- 软连字符与行尾连字符（量画同源：宽度全部来自 [SkiaRunMeasurer]）----
        //
        // `SkiaRunMeasurer` 已把 SHY 位置 0（不占宽、不绘制）。本段处理两件事：
        // ① 本行断词收尾且 SHY 落在行末 ⇒ 把**那个 SHY 字位**从 0 宽改成连字符宽（槽位复用，
        //    这就是 `&shy;` 的标准行为：不换行时看不见，换行时才显形）；
        // ② 本行断词收尾但没有 SHY 槽位（K-L 音节断词）⇒ 行末**外新增**一个字位。
        // 两种情况最终右缘都是 `trailStartX`，故 [Placement.hyphenXOf] 无需分支。
        //
        // ⚠ **SHY 个数必须从拉伸基数里扣掉**：SHY 不可见，给它分一份 `extra` 就是在行里
        //   撑出一个看不见的洞，行右缘冲出版心（实测一行的 SHY 越多冲得越远）。
        val shySlot: Int = if (
            hyphenAtEnd && visibleCount > 0 && isSoftHyphen(text[endExcl - 1])
        ) endExcl - 1 - start else -1
        val hyphenW = if (hyphenAtEnd) {
            measurer.hyphenWidthPx(
                fontSizePx, letterSpacingEm, tag, families, weight, italic, monospace,
                fontRuns, endExcl - 1,
            )
        } else {
            0f
        }
        if (shySlot >= 0) adv[shySlot] = hyphenW
        var shyCount = 0
        for (k in 0 until visibleCount) if (isSoftHyphen(text[start + k])) shyCount++
        // 无 SHY 槽位而要补连字符时，连字符在**可见区间之外**新增一个字位 ⇒ 多一个可拉伸间隙。
        val extraGlyphGap = hyphenAtEnd && shySlot < 0
        // **对齐的水平定位**（S5 第一版漏了，只做了 JUSTIFY ⇒ CENTER 行左缘起墨，`LineWindowDrawerTest`
        // 1 把红）。四种语义在此**一次性**算清，绘制侧不再各自为政：
        //  - LEFT   ：x0 = 缩进。
        //  - JUSTIFY：x0 = 缩进，可见行末恰好贴版心右缘（slack 均摊，见下）。
        //  - CENTER ：整体右移 (lineWidth - 可见宽)/2。
        //  - RIGHT  ：整体右移 (lineWidth - 可见宽)。
        // 可见宽在拉伸**之后**才是最终宽，故 CENTER/RIGHT 的偏移必须用拉伸后的值 ——
        // 顺序上：先按 JUSTIFY 算 natural + extra 得「最终可见宽」，再据此定位。
        val x0Raw = firstLineIndentPx.coerceAtLeast(0f)

        if (visibleCount == 0) {
            // 整行皆文档空白：无可见字符，placement 仍铺满区间（区间无缝），右边界退到行首。
            return Placement(FloatArray(n) { x0Raw }, FloatArray(n), x0Raw, x0Raw)
        }

        /**
         * **末字右边缘**（= 可见行宽）= `x0 + Σ_{j<visibleCount-1} adv[j]`。
         *
         * 刻意**不是** `Σ_{j<visibleCount} adv[j]`：末字那一份 advance 里含它自己的 `lsPx`，
         * 那是排在末字**之后**的不可见附加量，计入就等于把行末的不可见宽度算进「可见右边界」，
         * 实测直接溢出版心（`907.248 > 900`）。同理行末尾随空白的起点就是这里。
         */
        /**
         * **可见行宽** = `Σ_{j<N-1} advance_j + w_{N-1}` = `Σ_{j<N} advance_j − ls_{N-1}`。
         *
         * 语义（本轮踩了三次才对齐，**三个量必须分清**）：
         * - `Σ_{j<N-1} advance_j` = **末字左缘**：前 N−1 个字符各自的 `ls_j` 是它们**之后**确实
         *   存在的间隙（字符 j 与 j+1 之间），**该计入**。
         * - `w_{N-1}` = 末字的**纯字宽**：它的 `ls_{N-1}` 排在末字**之后**、无后继字符，
         *   **不可见、不该计入**。
         * - 故可见右缘 = 末字左缘 + 纯字宽，**不是** `+ adv[末字]`（那多算一个 `lsPx`）。
         *
         * 这是 JUSTIFY 的**拉伸基数**，也是 `visibleRight`。两处必须同源，否则
         * 「拉伸后铺满版心」与「算出的可见右缘」会差一个身位（实测 907.248 / 944.4）。
         */
        val lastLs = if (letterSpacingEm == 0f) 0f
            else letterSpacingEm * lastRunSizePx(text, start, visibleCount - 1, fontSizePx, fontRuns)
        var natural = x0Raw
        for (k in 0 until visibleCount) natural += adv[k]
        // 末字的 `lsPx` 通常排在末字**之后、无后继**，不可见 ⇒ 不计。
        // **但断词收尾时末字总有后继**（那个行尾连字符就跟在它后面），那份 `lsPx` 是
        // 字与连字符之间**确实存在的间隙** ⇒ 必须计入。这是「量画同源」的对称要求：
        // `xs` 循环会把这份 `lsPx` 算进落墨推进，这里不减，`finalContent`
        // （= [visibleRight] = 连字符右缘）才与墨真正到的地方一致，否则 JUSTIFY 行
        // 凭空少铺一个 `lsPx`、右缘说谎。实测（ls=0.05, fs=42.18 ⇒ **2.109px**）。
        //
        // ⚠ 槽位复用（[shySlot] ≥ 0）时**减得更不能减**：那个字位的 `adv` 已被覆写成
        // [hyphenW]，**它自己的 `lsPx` 早就不在里面了**（[SkiaRunMeasurer] 量的
        // SHY 是严格 0 宽、连 `lsPx` 也不给）。再减一次就是**减了两遍** ——
        // 实测 `hyphen&shy;` @300 ls=0.05：`hyphenX = 284.689` 而 `xs[末] = 286.798`，
        // 连字符左缘比它的槽位左缘**内缩了整整一个 `lsPx`（2.109px）**。
        if (shySlot < 0 && !extraGlyphGap) natural -= lastLs
        // 行尾连字符（K-L 音节断词那种「无 SHY 槽位」的）：接在可见行末**之外**，占版心。
        // SHY 槽位那种已写进 `adv[shySlot]`，这里**不能再加**（否则算两遍）。
        if (extraGlyphGap) natural += hyphenW
        //
        // ⚠ **这里原本有一段「把紧贴可见行末的混排字距间隙挂掉」的修正，2026-10-03 已删**
        //   （它是一条**不可达**的分支，且当时的 KDoc 把因果讲反了 —— 见 [orilumn.reader.engine.text.preprocess.CjkLatinSpacing]
        //   的类 KDoc「按段检测 vs 按行检测」一节）：
        //
        //   **不变式（当日已升级为等式）**：本行的间隙集合 = 断行侧在**整段**上检出、
        //   再由 [CjkLatinSpacing.gapsForRange] 裁到本行的那一份 ⇒ 逐槽 **画 == 量**，
        //   方向是 [NoLineExceedsContentWidthTest] 要的「画 == 量 ≤ 版心」。
        //   **不需要**任何修正项，也不需要动断行器。
        //
        //   删掉它的代价记录在案：把「少算」误算成「多算」是这里唯一可能出错的改法。
        //   若哪天 `visibleRight` / `hang` 变了口径（比如把行末 NBSP 算进可见宽），
        //   必须重跑 `CjkLatinSpacingWiringTest` 里那两条「画 == 量」的锁（跨行边界 / 扫版心）。

        // JUSTIFY 拉伸：**按四级优先级分配到可见字形之间的间隙**（[JustifySlack]）；
        // 末行不拉伸（两端对齐的定义本身）。
        //
        // ## [gapCount] 现在只剩一个作用：**「这一行到底有没有槽」的判据**
        //
        // 2026-10-03 起拉伸量由 [JustifySlack.plan] 逐槽给（`per[级]`），不再有单一的
        // `extra = slack / gapCount`；分配额由 `plan.placed` 汇报。下面这整套推导因此
        // **只用于回答「有没有槽」**（`doJustify` 守卫）与记录历史，但结论仍然成立 —— 一行有槽
        // ⟺ `gapCount > 0` ⟺ [JustifySlack.plan] 的 `levels` 里至少有一个非 −1。
        // 推导保留在案是因为它是 `gapCount` 公式的唯一出处，删了推导公式就成了魔数。
        //
        // ## 间隙数必须与「行里真正存在的字间空当数」一致（否则右缘会说谎）
        //
        // 一行的**可见字形序列** = 文本可见字形 + （断词收尾时）那个行尾连字符：
        // ```
        // G = visibleCount − shyCount + (shySlot >= 0 ? 1 : 0)   // 文本里的可见字形数
        //   （shySlot 复用后那个 SHY **不再不可见**，要加回来）
        // 可见字形总数 = G + (extraGlyphGap ? 1 : 0)            // 行末外新增的连字符
        // 间隙数 = 可见字形总数 − 1
        // ```
        // 两个分支互斥且都等价于「`hyphenAtEnd` 加一」，故公式可化简成下面这一行：
        //
        // ```
        // gapCount = visibleCount − shyCount − 1 + (hyphenAtEnd ? 1 : 0)
        // ```
        //
        // **踩过的坑（两处都错，且方向相反，实测才抓到）**：
        //  - 第一版是 `(visibleCount − 1 − shyCount) + (extraGlyphGap ? 1 : 0)`。
        //    K-L 情形（`extraGlyphGap`）得 `visibleCount`，SHY 情形少 1。
        //  - 而下面 `xs` 循环的护栏是 `k < visibleCount − 1`，两处各错一边 ⇒ **净额为零、
        //    但方向相反的错位**：`hy­phen` @200 JUSTIFY 时 `gapCount=6` 而只放了 5 份，
        //    剩下的 `extra`（实测 10.25px）被凭空当作「末字到连字符之间的空当」留在行里，
        //    同时 `visibleRight` 报告 200.0 = 版心。**墨实际停在 176.55**，连字符从 186.80 起
        //    ⇒ 一个 10.25px 的假洞；反向的 K-L 情形则整行少铺 `slack/gapCount`。
        // - 故 [gapCount] 的公式是上面那个推导的**结论**，不是随手写的；
        //    [xsExtraLimit] 处另记了「它与 [gapCount] 其实不必同式」的变异验证结论。
        val gapCount = visibleCount - shyCount - 1 + (if (hyphenAtEnd) 1 else 0)
        // `xs` 循环放 `extra` 的上界：**只到「末可见字形之后」为止**（`k < visibleCount - 1`）。
        //
        // 「末字 → 连字符」那一个间隙**不放进 `xs`**：连字符在区间之外（[extraGlyphGap]），
        // 它到 `xs[n-1]` 没有 x 坐标可落，放进去也无处可观测 —— 它的位置由
        // [Placement.trailStartX] − [hyphenWidth] 一次算清（见 [hyphenXOf]）。
        //
        // ⚠ 变异验证：这上界若改成 `visibleCount - 1 + extraGlyphGap`（看似对称、实则
        //   把一份 `extra` 加到最后一个字的 x **之后**）**全部现有锁仍全绿** ——
        //   因为那份 `extra` 加在 `xs[n-1]` 记完之后，`x` 随即被丢弃。
        //   两版可观测几何逐位相同（实测 `hyphenation` @300：`gap` 同为 6.4780）。
        //   ⇒ 保留朴素写法（少一处状态、语义更直白），并在此登记该变异点。
        val xsExtraLimit = visibleCount - 1
        val doJustify = align == TextAlign.JUSTIFY && !isLastLine && gapCount > 0
        val slack = lineWidthPx - natural
        // 拉伸**按四级优先级分配**，不再均摊（[JustifySlack] 是唯一实现，渲染层）。
        //
        // ⚠ 只在真的要拉时才做逐槽分类（每行一次），LEFT/CENTER/RIGHT、末行、`slack ≤ 0`
        //   全都直接拿到 [JustifySlack.Plan] 的空形态（下面 `xs` 循环里 `per[lv]` 恒 0）——
        //   分类是 O(字位) 的循环，非 JUSTIFY 行走它属于白付的成本。
        val plan = if (doJustify && slack > 0f) {
            JustifySlack.plan(text, start, xsExtraLimit, cjkGaps, fontSizePx, slack)
        } else {
            null
        }
        // ---- 挤压理由 ③：「行尾另有剩余额度，也一并挤」（产品裁决 2026-10-03 第 2 条的第 2 个理由）----
        //
        // [JustifySlack] 铺不满时行末会留一道缺口（`slack − plan.placed > 0`，判据与
        // [Placement.justifyCapped] 同一式）。唯一的例外形态是「**行内无级 3 槽**（纯汉字行）
        // 且 `slack` 超过全部有限额度之和」—— 实测真书只占 **0.27%** 的行。那道缺口没法靠拉伸补，
        // 就用挤压补：**二次挤压只会让行变窄** ⇒ 1-Lipschitz ⇒ 绝不产生右溢，这是它敢放在这里的前提。
        //
        // ⚠ **必须 clamp 到「剩余额度」**：`ratio` 是**相对**额度表的，第二次挤压若拿满额度，
        //   一行的实际挤压比例就会 `> 1`（把标点压成负宽）。故上界取 `1 − squeezeRatio`。
        //   （断行侧给的 [squeezeRatio] 恒 `≤ 1` —— 它自己先判过「挤到全额也不够就不挤」。）
        //
        // ⚠ **只扫一趟**：第二次挤压后 `slack` 变大，`plan` 可能又能铺满一点，于是可能又出现缺口。
        //   但那属于「挤了也填不满」的病态行（额度与槽都极少），再挤一轮收益是亚像素级的 ——
        //   与其加一趟 O(字位) 的重规划，不如让它留着缺口。
        var plan1 = plan
        if (plan != null) {
            val leftover = slack - plan.placed
            if (leftover > 1e-3f) {
                var capTotal = 0f
                for (k in 0 until n) capTotal += squeeze[k]
                if (capTotal > 0f) {
                    val room = 1f - squeezeRatio
                    val r = min(1f, leftover / capTotal)
                    val r2 = if (room < r) room else r
                    if (r2 > 0f) {
                        var applied = 0f
                        for (k in 0 until n) {
                            val d = PunctuationSqueeze.slotSqueeze(squeeze[k], r2)
                            if (d != 0f) { adv[k] -= d; applied += d }
                        }
                        natural -= applied
                        val slack2 = lineWidthPx - natural
                        if (slack2 > 0f) plan1 = JustifySlack.plan(text, start, xsExtraLimit, cjkGaps, fontSizePx, slack2)
                    }
                }
            }
        }
        // 拉伸后**内容**宽 = natural + 实际落到 `xs` 上的总量 − 缩进（`natural` 已含缩进 `x0Raw`，
        // 故这里要减掉）。
        //
        // ⚠ **`natural` 含缩进而 `x0` 也要含缩进**，`trailStartX` 若直接 `x0 + natural + 拉伸量`
        //   就把缩进**算了两遍** ⇒ 右缘越出版心整一个缩进。绘制侧现在把 `firstLineIndentPx` 交给本方法
        //   （不再画完再右移），这条路径才第一次真的带上非零缩进（Rust 书 `p { text-indent: 2em }`
        //   + `text-align: justify`，即**每个正文段首行**）⇒ 必须在此扣掉，否则那行右溢 84.36px。
        //
        // ⚠ **用 [JustifySlack.Plan.placed] 而不是 `slack`**：后者是「预算」，前者是「实际落到
        //   逐槽 x 上的量」。两者在三种情形下不同，且必须按后者算右缘：
        //  ① K-L 音节断词（`extraGlyphGap`）：[gapCount] 里那个「末字 → 行尾连字符」的间隙
        //     **没有 x 坐标可落**（连字符在区间之外），故实际落地的槽位比 [gapCount] 少一个
        //     ⇒ 行比版心窄一档。用 `slack` 会在末字与连字符之间留一个「假洞」
        //     （旧实现实测 10.25px，`visibleRight` 却报版心 —— 墨实际停在 176.55）。
        //  ② 三级全部封顶而行内无级 3 槽（纯汉字行且每缝要超过 0.25em）：按预算铺会把
        //     每个汉字都撑开，比留缺口更难看。宁可短一点也不撑。
        //  ③ 级内均分的浮点尾差（`per × cnt` 与预算差一个 ulp）。右缘是硬边界
        //     （[NoLineExceedsContentWidthTest] 钉它），宁可短不可超。
        val finalContent = natural + (plan1?.placed ?: 0f) - x0Raw
        // CENTER/RIGHT 按**最终**内容宽定位（用拉伸前的 natural 会偏）。
        //
        // ⚠ 这里**不能再加 `x0Raw`**：缩进只是「行首多出来的一段空白」，它排在文字**前面**，
        // 而 [finalContent] 量的是「从首字笔位到末字右缘」的内容宽。对齐要把**末字右缘**
        // 贴到版心右缘（`x0 + finalContent == lineWidthPx`），故 `x0` 就是 `lineWidthPx − finalContent`。
        // 曾经写成 `x0Raw + (lineWidthPx − finalContent)` ⇒ 缩进被算两遍 ⇒ 右溢**恰好一个缩进**。
        // 实测（Rust 书 00_2.xhtml 译名行：`text-align: right` + `text-indent: 2em`）：
        // 右溢 **82.885px**（= 缩进 84.36 − kerning 1.48）。
        val x0 = when (align) {
            // ⚠ `+ x0Raw`：**缩进排在内容块前面**，居中要把缩进算进块宽再折半。
            //   正确落位 = `x0Raw + (width − x0Raw − finalContent)/2` = `(width − finalContent + x0Raw)/2`。
            //   漏掉 `x0Raw` ⇒ 整行左移**半个缩进**（实测 fs=44.4、2em 缩进 ⇒ 44.4px）：
            //   缩进被彻底吃掉（带缩进与不带缩进的 `xs[0]` 分毫不差，实测差 0.0000305px）。
            TextAlign.CENTER -> (lineWidthPx - finalContent + x0Raw).coerceAtLeast(0f) / 2f
            // RIGHT 则**不加**：`x0Raw + (width − x0Raw − finalContent)` 化简回 `width − finalContent`。
            //   这是对的 —— CSS 里首行缩进只是把「可用行宽」缩小 `x0Raw`，右对齐的内容照旧贴右缘，
            //   左边多出来的那截缩进落在本来就空着的左侧空白里，**没有可见效果**。
            TextAlign.RIGHT -> (lineWidthPx - finalContent).coerceAtLeast(0f)
            else -> x0Raw
        }

        val xs = FloatArray(n)
        var x = x0
        for (k in 0 until n) {
            xs[k] = x
            x += adv[k]
            // 拉伸只加在**可见字形之间**：k 是前一个字符的本地下标。
            // ① 上界 [xsExtraLimit]（与 [gapCount] 同式）；② **SHY 之后不加**
            // （零宽不可见，它的 `lsPx`/拉伸都不该给 —— 不加就等于在行里留一个看不见的洞）。
            // ③ 不可见的 SHY **跨过去不加**之后仍要继续给下一个可见字形分拉伸，故只是
            //    跳过 `k` 这一次，不是 `continue` 整个可见段。
            //
            // ⚠ ①② 现在**收在 [JustifySlack.Plan.levels] 里**（`-1` 即「不是可拉伸槽」），
            //   本循环只剩「查级 → 加该级的量」一步。这是有意的：**判据只有一份**
            //   （[graftKerningOnto] 的回填走同一个 [JustifySlack.plan]），两处各判一次会分叉。
            if (plan1 != null && k < xsExtraLimit) {
                val lv = plan1.levels[k]
                if (lv >= 0) x += plan1.per[lv]
            }
        }

        // 行末尾随空白紧贴**可见右缘**（含对齐偏移 x0）。有连字符时它就是**连字符右缘**，
        // 连字符左缘 = 本值 − `hyphenWidth`（见 [Placement.hyphenXOf]）。
        val trailStartX = x0 + finalContent
        // 封顶检测：**用 `placed` 与 `slack` 比**，不重算额度（额度只有 plan 知道，见字段 KDoc）。
        // `1e-3px` 门槛：级内均分的浮点尾差（`per × cnt` 与预算差一个 ulp）不该算成「封顶」。
        //
        // ⚠ `slack` 取**二次挤压后**的那个（`lineWidthPx − natural`，`natural` 已被上面改写）——
        //   否则 `capped` 会把「二次挤压已经补上的那道缺口」也算进去，于是明明补满了却报封顶
        //   （[LineAlignerTest] 的 `JUSTIFY 纯汉字行封顶后留缺口而不是撑字缝` 会当场红）。
        val capped = plan1 != null && (lineWidthPx - natural) - plan1.placed > 1e-3f
        return Placement(xs, adv.copyOf(n), trailStartX, trailStartX, hyphenW, capped)
    }

    // 末字 run 字号取自**顶层** [lastRunSizePx]（与 [graftKerningOnto] 共用同一份定义 ——
    // 「同一规则两处各写一份」会在换面行上静默分叉，见教训㉛）。

    /** 行末圆整右边界（版心对齐判据用，避免 899.9997 判成未铺满）。 */
    fun visibleRightInt(p: Placement): Int = p.visibleRight.roundToInt()

    /**
     * S5 逐字绘制的**基线 y**（相对行顶 `yTop`）。
     *
     * CSS 2.2 §10.8.1 半行距居中：`baseline = halfLeading + ascent`，
     * 其中 `halfLeading = (lineHeightPx - (ascent + descent)) / 2`（可负，负值即行盒装不下时的溢出）。
     *
     * ## 为什么必须自己算（第一版漏了它，7 把锁全红「行无墨」）
     *
     * 旧路径 `Paragraph.paint` **内部**按 `setHeight` + `setHalfLeading(true)` 落基线，
     * 绘制侧只需给 `yTop`；逐字 `drawString` 画的原点**就是基线**，少加这两项就等于
     * 把整行字画在 `yTop`（yTop=0 时画到画布顶边被裁），表现为「行没有墨」。
     * 插桩打出来是 `y=0.0` 才定案的 —— 字体/颜色/整栈回退当时都是对的。
     *
     * @param ascentDescent `font.getMetrics()` 的 `ascent - descent`（Skia 约定 ascent 为负、
     *   descent 为正，故字体盒高 = `descent - ascent`；本函数按**传入的盒高**算，不假设符号）。
     */
    fun baselineOffset(
        yTop: Float,
        fontSizePx: Float,
        lineHeightRatio: Float,
        ascentPx: Float,
        descentPx: Float,
        extraTop: Float = 0f,
    ): Float {
        val lineH = lineHeightPx(fontSizePx, lineHeightRatio)
        val boxH = ascentPx + descentPx
        val halfLeading = (lineH - boxH) / 2f
        return yTop + extraTop + halfLeading + ascentPx
    }

    /**
     * `[from, to)`（**range 本地坐标**）的可见左右并集，供 ruby 注音居中 / 着重号定位用。
     *
     * 取代 Skia `Paragraph.getRectsForRange(RectWidthMode.TIGHT)`：那个量的是 Skia 整形
     * 后的墨盒，与本仓「裸 cmap 量宽」口径不同源；[Placement.xs] 才是落墨真用的 x。
     * **量画同源由此保证**（教训 20）。返回 null 表示区间无有效字形。
     */
    fun visibleSpan(p: Placement, rangeLen: Int, from: Int, to: Int): Pair<Float, Float>? {
        val lo = from.coerceIn(0, rangeLen)
        val hi = to.coerceIn(lo, rangeLen)
        if (hi <= lo) return null
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (k in lo until hi) {
            val x = p.xs.getOrNull(k) ?: continue
            left = minOf(left, x)
            right = maxOf(right, x + (p.advs.getOrNull(k) ?: 0f))
        }
        if (right <= left) return null
        return left to right
    }

    private fun sliceRuns(runs: List<FontRun>, start: Int, endExcl: Int): List<FontRun> {
        if (runs.isEmpty()) return runs
        val len = endExcl - start
        val out = ArrayList<FontRun>(runs.size)
        for (r in runs) {
            val s = (r.start - start).coerceAtLeast(0)
            val e = (r.endExclusive - start).coerceAtMost(len)
            if (e > s && s < len) out.add(r.copy(start = s, endExclusive = e))
        }
        return out
    }
}