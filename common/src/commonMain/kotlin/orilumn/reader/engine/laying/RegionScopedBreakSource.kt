package orilumn.reader.engine.laying

/**
 * **按位置分区**的断点源（Q18）：同一段文本里，内区与外区各走**各自**的 source 集。
 *
 * ## 为什么需要它（Q18 缺陷本体）
 *
 * 「是不是代码」在 CSS 里是**行内元素的属性**，而 [BreakOpportunitySource] 拿到的是**整段文本 + 一个叶块 tag**。
 * 于是同一个引擎对同一段文字里的两类代码给出两套互相矛盾的规则：
 *
 * - 整块 `<pre>`（叶块 tag = `pre`）→ 注入 [CodeIdentifierBreakSource]、**不**注入音节断词；
 * - 段落里的行内 `<code>…</code>`（叶块 tag = `li`/`p`）→ 注入 [EnglishHyphenationSource]，
 *   **在 `<code>` 内部按音节断词**，把 `wrapping_add` 断成 `wrap` ‖ `ping_add` 并补一个连字符。
 *
 * 后者**违反 [CodeIdentifierBreakSource] 与 [EnglishHyphenationSource] 各自 KDoc 里写明的规则**
 * （「代码标识符不走音节断词」）。真机实测（Rust 书 `<li>…如 <code>wrapping_add</code></li>`，版心 360/640）：
 * 断点落在 `text[41]`，而该 run 是 `[38,50)` 的 `code` ⇒ 断在**行内代码 run 内部**。
 *
 * ## 为什么不能用「只置位不清位」硬叠
 *
 * [BreakOpportunitySet.of] 的顺序无关性是**多 source 取并集**的语义：每个 source 只置位、不清位。
 * 要「在代码 run 内**撤销**音节断点」就得引入清位操作，那会**破坏已冻结的不变式**（并让注入顺序重新变得有意义）。
 * ⇒ 改成**按位置分区**：把文本按 [innerMask] 切成极大连续区间，每个区间只跑自己那套 source，
 * 各区间的结果再并进同一个 [BreakOpportunitySet]。source 集合内部仍然是纯并集，**顺序无关性原样保留**。
 *
 * ## 分区判据：`text[i-1]` 与 `text[i]` **都**落在内区
 *
 * 即**内区只含 run 的严格内部**，跨 run 的**边界位置一律归外区**。三条理由：
 *
 * 1. 边界位置几乎总是空白或标点，两套规则都放行（ZH_EN 与代码分隔符集在空白上都断）⇒ 归哪边多不改变结果；
 * 2. 归外区 ⇒ 外区的「禁则 + 音节断词」不会被代码规则污染，散文行为**逐值不变**；
 * 3. 归内区则会在 `foo<code>Bar</code>` 的 `o|B` 处凭驼峰规则断词，把标识符首字母甩到下一行 —— 错的。
 *
 * ⚠ 「边界归外区」不是可有可无的简化，而是**必需**：若把边界交给内区，外区就会在自己的
 * 子串里**看不到**紧邻的代码字符，音节断词的词扫描也就不会把散文尾巴与代码首字连成一个词
 * （反过来若整段都跑外区源，则 `<code>` 内部又会拿到音节断点 —— 那正是 Q18 的缺陷本体）。
 * 掩码怎么算见 [maskOf]，**判据本身在本类的 companion 里**（不在调用方）——
 * 因为这条判据在生产版心下**观察不到**（见 [maskOf] 的 KDoc），只能在这里钉。
 *
 * ## 逐区间调用带来的三处已知收窄（刻意接受，逐条钉在 KDoc 里）
 *
 * 1. **跨区界的词不再被音节断词**：音节断词按「词」工作（[EnglishHyphenationSource] 自己扫词边界），
 *    切开后两侧各自成词。`foo<code>bar</code>` 的 `foobar` 整段不再被断（6 字 ≥ MIN_WORD），
 *    切成 `foo`/`bar`（各 3 字）后都不断。**这正是想要的结果**（代码那半不该断），散文那半短到不足词长也不算损失。
 * 2. **外区里紧贴代码 run 的位置不再被代码 source 看到**：反过来也成立 —— 内区看不到外区。
 *    对 [CodeIdentifierBreakSource] 无影响（它只看相邻两字符，天然局部）。
 * 3. **外区在自己子串里能看到紧邻的那 1 个代码字符**（左邻域的代价）：
 *    `<code>x</code>phenomenon` 这种**无空格**接缝上，外区的词扫描看到的是 `xphenomenon`。
 *    ⇒ **与 Q18 改动前逐值相同**（那时整段都跑散文源），**不构成新增差异**；
 *    单独断 `phenomenon` 与断 `xphenomenon` 的差别由断词表本身决定，本类无从干预。
 *
 * ## 不做什么
 *
 * - **不改** [BreakOpportunitySet] 的「只置位不清位」不变式，不加清位 API。
 * - **不改** [ParagraphBreaker] 签名（分区掩码由调用方算好塞进来，source 本身不认识 [orilumn.reader.engine.css.FontRun]）。
 * - **不碰** [KinsokuRules] / [minContentSegments]：min-content 早就只用 ZH_EN（连音节断词都不算），
 *   本改动**不改变** min-content 的任何输出，故那条口径分叉是**先前既有**的、不由本类引入（见 docs Q18）。
 */
class RegionScopedBreakSource(
    /** 长度 ≥ `text.length + 1`；下标 `i`（1..n-1）= 位置 `i` 是否属内区。下标 0 不使用。 */
    private val innerMask: BooleanArray,
    /** 内区 source 集（代码语境：禁则 + 软连字符 + 代码标识符断点，**无**音节断词）。 */
    private val inner: List<BreakOpportunitySource>,
    /** 外区 source 集（散文语境：禁则 + 软连字符 + 音节断词，**无**代码标识符断点）。 */
    private val outer: List<BreakOpportunitySource>,
) : BreakOpportunitySource {

    override fun mark(text: CharSequence, into: BreakOpportunitySet) {
        val n = text.length
        if (n < 2) return
        val first = innerMask[1]
        // 快速路径：**整段同一个区**（无行内代码，或整段就是代码）⇒ 直接把 source 跑在整段上。
        // 无行内代码的普通段落是热路径，不能为此多分配一次子串 + 一次子集。
        var k = 1
        while (k < n && innerMask[k] == first) k++
        if (k >= n) {
            for (s in if (first) inner else outer) s.mark(text, into)
            return
        }
        // 慢路径：逐极大同区区间各算一个子集，再按下标平移并进 [into]。
        //
        // ## 子串必须带**一字符左邻域**（第一版的真缺陷）
        //
        // 位置 `pos` 的判定需要 `text[pos-1]` 与 `text[pos]` **两个**字符。若子串取 `text[i, j)`
        // （`i` = 区间首个位置），则区间首个位置 `i` 缺 `text[i-1]` ⇒ **算不出来**。
        // 第一版正是这样 ⇒ **每个分区的首个位置被静默丢弃**（不止代码区，外区也丢）。
        //
        // ⚠ 后果**不是**溢出。贪心的 `hang` 只在 `next <= avail` 时才被赋值，故恒有 `hang <= avail`
        //   ⇒ 任何一条产出的行都不会超版心（`NoLineExceedsContentWidthTest` 钉的就是这条不变式）。
        //   真正的后果是「**少一个断点**」：贪心取的是「可达断点里最靠右且装得下的那个」，
        //   少一个断点时它会越过该位置继续填，必要时改走 R1 core 在**下一处**硬切 ——
        //   即该词被**硬切在字母中间**而不是在断词点断开，与 Q18 的缺陷同类但程度轻。
        //
        // ⇒ 故 `from = i - 1`（`i >= 1` 恒成立，无需再夹取），子串取 `text[from, j)`。
        // 这样每个位置看到的都是它**真正的那一对字符**，一个位置都不丢。
        //
        // ## 坐标换算（唯一依据是下标定义，本类最容易写错的一处）
        //
        // `sub = text[from, j)` ⇒ `sub[k] == text[from + k]`；子集位置 `t` 指 `sub[t-1]` 与 `sub[t]`
        // ⇒ 全局 `pos` 满足 `text[pos-1] == sub[t-1] == text[from + t - 1]` ⇒ **`t == pos - from`**。
        // 取值域自检：`pos ∈ [i, j)` ⇒ `t ∈ [1, j-from-1] = [1, sub.size-1]`，**永不落在段末**。
        //
        // ⚠ 第一版写成 `t == pos - i + 1`（配 `sub = text[i, j)`），断点集整体**左移一位**。
        var i = 1
        while (i < n) {
            val inInner = innerMask[i]
            var j = i + 1
            while (j < n && innerMask[j] == inInner) j++
            val srcs = if (inInner) inner else outer
            if (srcs.isNotEmpty()) {
                val from = i - 1
                val sub = BreakOpportunitySet.of(text.subSequence(from, j), srcs)
                for (pos in i until j) {
                    val t = pos - from
                    if (!sub.opportunityAt(t)) continue
                    if (sub.isHyphenAt(t)) into.markHyphen(pos) else into.mark(pos)
                }
            }
            i = j
        }
    }

    companion object {
        /**
         * 由「代码字符的下标集合」算出**位置掩码**（长度 `n + 1`；下标 `i`（1..n-1）= 位置 `i` 是否属内区，
         * 下标 0 不使用）。
         *
         * ## 判据：位置 `i` 的**两侧字符都在代码内**（`inCode[i-1] && inCode[i]`）
         *
         * ⇒ 内区只含 run 的严格内部，**跨 run 的边界位置一律归外区**（理由见类 KDoc）。
         *
         * ## ⚠ 这条判据**在生产版心下观察不到**（实测：把 `&&` 改成 `||` 端到端全绿）
         *
         * `||` 会把「散文 ‖ 代码」「代码 ‖ 散文」两类**边界位置**也变成内区，于是多出断点
         * （典型：`foo<code>Bar</code>` 的 `o|B` 凭驼峰规则被标上）。但这些多出来的断点**全部落在
         * min-content 单元内部**，而生产恒有 `widthPx ≥ ceil(minContentWidth)`
         * （`minContentSegments` 只用 ZH_EN，比本判据更粗 ⇒ 单元只会更宽）⇒ 贪心永远选不到它们。
         * 换句话说：**任何按行/按版心的锁都抓不到这条判据**，只有断点集层能钉。
         * 这也是本判据从调用方搬进本类 companion 的唯一理由（可单测）。
         *
         * @param codeSpans 代码字符的下标区间，半开区间、可重叠、顺序任意；越界与空区间被夹掉。
         * @return `null` = **整段没有任何代码字符** ⇒ 调用点直接走整段老路径
         *   （普通段落是热路径，不能为此多分配一次掩码 + 一次子串）。
         */
        fun maskOf(n: Int, codeSpans: List<IntRange>): BooleanArray? {
            if (n < 2 || codeSpans.isEmpty()) return null
            val inCode = BooleanArray(n)
            var any = false
            for (span in codeSpans) {
                val s = span.first.coerceAtLeast(0)
                val e = minOf(span.last + 1, n)
                if (e <= s) continue
                any = true
                for (i in s until e) inCode[i] = true
            }
            if (!any) return null
            val mask = BooleanArray(n + 1)
            for (i in 1 until n) mask[i] = inCode[i - 1] && inCode[i]
            return mask
        }
    }
}
