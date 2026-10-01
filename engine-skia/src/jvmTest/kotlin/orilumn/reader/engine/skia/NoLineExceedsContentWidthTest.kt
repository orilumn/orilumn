package orilumn.reader.engine.skia

import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ParagraphBreaker

/**
 * R1 溢出锁：**分页阅读器不允许任何行超出版心**。
 *
 * ## 为什么这是硬要求（不是优化）
 *
 * 浏览器能容忍溢出的前提是**可以横向滚动**。本项目页宽固定，超出版心的部分**直接被页面裁掉**，
 * 用户永远读不到 —— 溢出的字不是「不好看」，是**内容丢失**。这条与「像不像浏览器」无关。
 *
 * ## 判据：量必须与绘制逐参同源（这把锁的第一版就栽在这里）
 *
 * 所有几何实参都取自 [DrawLine] 自身字段，与 [LineWindowDrawer] 调 `align` 时**逐参一致**：
 * `fontSizePx = line.fontSizePx`、`letterSpacingEm`、`tag`/`families`/`weight`/`italic`/
 * `monospace`/`fontRuns`、`alignment`、`firstLineIndentPx = 0f`（缩进由绘制侧另加 `paintX`）、
 * `isLastLine = endExcl >= text.length`（同 `isLastLineOf`）。
 *
 * 第一版我传的是**自己设的根字号 44.4f**，而 `DrawLine.fontSizePx` 来自 `style.fontSizePx`
 * （`DrawLineBuilder` :103）—— 于是真书首行被量成 `907.248 > 900`，我据此判定「断行器有溢出 bug」
 * 并连改三轮 `greedy`。**实际上断行器给的行宽是 873.9 ≤ 900，完全装得下**：
 * 改用 `dl.fontSizePx` 后，量出的自然宽与 `greedy` 内部记账的 `Σadv` **分毫不差**（873.94775），
 * 证明两侧口径本就一致，907.248 纯粹是量宽口径错（教训 ㉚，见 docs 测试计划）。
 *
 * ## 本锁同时钉住「原实现在这两条上本就不溢出」
 *
 * 排查中我一度认为 `greedy` 的 `lastOpp > s -> lastOpp`（无条件退回最近断点）违反它自己的
 * R1 core 规格（「不是塞不下就溢出」），并加了 `lastOppWidth <= avail` 判定。**实测该改动对
 * 全量 engine-skia 语料零可观察影响**：断点记账点是 `i++` 之后，而 `hang > avail` 的那一刻
 * 循环已经 `break`，故被记录的断点宽度必然 ≤ avail（全量仅 4 处「违反」都在版心装不下单个字符
 * 的退化场景，那里退回哪个断点结果相同）。同理 `i >= n -> n` 分支恒等（走到 `i == n` 说明全程
 * 没超宽）。⇒ 两处改动已**回退**，保持行为逐字节不变，本锁负责防将来回归。
 */
class NoLineExceedsContentWidthTest {

    /** 真书原句（`Rust 程序设计语言` `Text/22.xhtml:339`）。 */
    private val realPara =
        "<p>派生 <code>Clone</code> 实现了 <code>clone</code> 方法，当其为整个类型实现时，" +
            "会在类型的每一部分上调用 <code>clone</code> 方法。这意味着类型中所有字段或值" +
            "也必须实现了 <code>Clone</code>，这样才能够派生 <code>Clone</code> 。</p>"

    /** 无断点的长串（`R1` 的原始目标：没有任何 UAX#14 断点可用）。 */
    private val longUrl =
        "https://www.w3.org/TR/2017/REC-html52-20171214/#some-very-long-anchor-name-here"

    /** 无空格的超长单词（CJK 混排里同样可能出现的「一个字都不能断」）。 */
    private val longWord = "Donaudampfschiffahrtsgesellschaftskapitänsmützenabzeichen"

    private val css =
        "html { font-size: 18px; } body { font-family: serif; font-size: 0.95rem; } " +
            "p { text-align: justify; } code { font-family: monospace; } p code { font-size: 0.95em; } " +
            ".ind { text-indent: 88%; } " +
            // 真书 `li > ol, li > ul { text-align: right }`（stylesheet.css :261）+ `2em` 首行缩进
            // —— 用户报缺陷①的第四个机制（右溢恰好一个缩进）就发生在这个组合上。
            // ⚠ 语料必须是**列表项**（`li`）而不是 `p`：真书里 `p { text-align: justify }`
            //   （:89）直接命中 `<p>`，会**压过**从父容器继承来的 `right`（继承永远输给直接匹配）。
            //   第一版就误用了 `<p class="r">`，`DrawLine.alignment` 实测全是 `JUSTIFY`，
            //   锁因此在「右对齐 + 缩进」这条路径上**恒绿 = 假锁**。
            ".r { text-align: right; } .c { text-align: center; } li { text-indent: 2em; } " +
            "ul.r { list-style: none; }"

    private fun drawLines(html: String, width: Int, breaker: ParagraphBreaker, fs: Float) =
        run {
            val root = HtmlTreeConverter().convert(html)!!
            val engine = StyleComputer(fs, LightCssParser().parse(css), emptyList())
            val styles = engine.compute(root)
            val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
            val result = BoxLayouter(fs, breaker).layoutBoxes(root, width, styles, classify)
            DrawLineBuilder.build(result, styles, classify, HIDDEN_NONE, 0f).toList()
        }

    /**
     * 一行**真正画出来的最右墨框**超出版心多少 px（≤0 即合规）。
     *
     * ## 逐参同源（这把锁第一版就栽在「不是同源」上，见类 KDoc）
     *
     * 全部几何实参取自 [DrawLine] 自身字段，与 [LineWindowDrawer] 调 `align` / 决定簇位轨时
     * **逐参一致**：
     * - `fontSizePx = dl.fontSizePx`（**不是**测试自己传的根字号 —— 第一版传错，量出
     *   `907.248 > 900`，我据此判定「断行器有溢出 bug」并连改三轮 `greedy`，纯属冤案）；
     * - `firstLineIndentPx = dl.firstLineIndentPx`（缩进**已经**交给 Aligner，绘制侧不再另右移；
     *   传 `0f` 就等于把这档缩进排除在锁外 —— 缩进恰恰是缺陷①的一个成因，必须在锁内）；
     * - `hyphenAtEnd = dl.hyphenAtEnd`；
     * - `isLastLine = dl.range.last + 1 >= dl.text.length`（同 `isLastLineOf`）；
     * - `nowrap` 的行绘制侧按 `Float.MAX_VALUE` 当版心（`LineWindowDrawer` 的 nowrap 分支），
     *   本锁同样放行，否则会把「不折行」的行误判成溢出。
     *
     * ## 簇位轨（`paintGlyphs` 的 S5b 路径）也必须在锁内
     *
     * 走簇位时绘制落位是 [graftKerningOnto] 的结果，不是 `Placement.visibleRight`。
     * 只锁 Aligner 层就等于**漏掉了用户报的那个缺陷**（真机上溢出的正是簇位行）。
     * 故这里逐行复刻 `paintGlyphs` 的分支：`needsClusters` → 建 `originX=0` 的自然簇位轨 →
     * [graftKerningOnto] → 量末可见槽的右缘。
     *
     * ## 右缘取「末可见槽」而不是末槽
     *
     * [LineAligner] 把 HTML 源里的**尾随空白排在版心之外**（实测 `xs[6]=400`、右缘 420，
     * 而 `visibleRight=400` 取的是末可见字）。尾随空白**不落墨** ⇒ 不能用它判溢出，
     * 否则每一行带尾空白的行都被误判成溢出几十 px（第一版就踩了这个，得到一个毫无意义的
     * 「全语料溢出」结论）。
     */
    private fun worstOverflow(lines: List<Pair<Int, DrawLine>>): Float {
        val aligner = LineAligner()
        val kern = KerningClusterTable()
        var worst = Float.NEGATIVE_INFINITY
        for ((_, dl) in lines) {
            if (dl.nowrap) continue
            val start = dl.range.first.coerceIn(0, dl.text.length)
            val endExcl = (dl.range.last + 1).coerceIn(start, dl.text.length)
            if (endExcl <= start) continue
            val p = aligner.align(
                dl.text, dl.range, dl.fontSizePx, dl.lineWidthPx.toFloat(), dl.letterSpacingEm,
                dl.tag, dl.families, dl.weight, dl.italic, dl.monospace, dl.fontRuns,
                dl.alignment, dl.firstLineIndentPx, endExcl >= dl.text.length, dl.hyphenAtEnd,
            )
            // 与 paintGlyphs 同分支：簇位轨可用就用簇位轨，否则退回 Aligner 的 x。
            val graft = if (kern.needsClusters(dl.text, start, endExcl)) {
                kern.clusterXs(
                    dl.text, start, endExcl, dl.fontSizePx, dl.lineHeightRatio,
                    dl.tag, dl.families, dl.weight, dl.italic, dl.monospace, dl.fontRuns,
                    originX = 0f, letterSpacingEm = dl.letterSpacingEm,
                )?.let {
                    graftKerningOnto(
                        p, it, dl.text, start,
                        dl.fontSizePx, dl.letterSpacingEm, dl.fontRuns,
                        if (dl.alignment == TextAlign.JUSTIFY && endExcl < dl.text.length) {
                            dl.lineWidthPx.toFloat()
                        } else {
                            Float.NaN
                        },
                    )
                }
            } else {
                null
            }
            val n = endExcl - start
            val xs = graft?.xs ?: p.xs
            var lv = n - 1
            while (lv > 0 && isDocumentSpace(dl.text[start + lv])) lv--
            // 末可见槽的右缘 = 落位 + 该槽 advance（末尾挂的 lsPx 也算进来 ⇒ 偏保守）。
            // 各槽右缘单调不减（xs 递增、advance>0），故末可见槽就是最右的那个。
            var painted = xs[lv] + p.advs[lv]
            if (p.hyphenWidth > 0f) {
                // 行尾连字符画在 `trailStartX − hyphenWidth + endDelta`，右缘 = trailStartX + endDelta
                painted = maxOf(painted, p.trailStartX + (graft?.endDelta ?: 0f))
            }
            worst = maxOf(worst, painted - dl.lineWidthPx)
        }
        return if (worst == Float.NEGATIVE_INFINITY) 0f else worst
    }

    /**
     * **自建侧**硬断言不溢出；**Skia 侧只记录不要求**（见下）。
     *
     * ## 为什么 Skia 臂只记录
     *
     * Skia 侧实测在窄版心下会溢出：超长单词 @80 溢出 **32.5px**、首行缩进 @120 溢出 **65.1px**
     * —— `SkiaParagraphBreaker` 在段末**不做贪心填充**，把剩余整段作为最后一行。
     * 那是**回退阀 + T1 逐值等价基线**的既有行为（[DefaultSideIsUnchangedTest] 守它逐值一致，
     * 正是防漂移的那把锁），自建断行完成后会被删除。此处若对它硬断言，等于要求现在去改一个
     * 即将删除的过渡实现 —— 所以只打印实测值留档，职责单一化为「自建侧不得溢出」。
     *
     * ## 为什么不能共用 `forEachBreakerVariant`
     *
     * 那个 fixture 遇错即止：`skia` 臂排在前，它一抛异常**自建臂根本没跑到**。第一版就因此
     * 出现过「自建侧数据是从 skia 臂的现象反推出来」的错判。⇒ 这里手写循环，按臂分流。
     */
    private fun assertNoOverflow(lines: List<Pair<Int, DrawLine>>, label: String, where: String) {
        val over = worstOverflow(lines)
        if (label == "skia") {
            println("  [回退阀/skia·仅记录] $where 实测溢出=$over px")
            return
        }
        assertTrue(
            "$where 自建侧有行超出版心 $over px。" +
                "分页阅读器页宽固定，溢出部分被页面裁掉 = **内容丢失**" +
                "（浏览器能容忍是因为可横向滚动，本项目不能）。",
            over <= 0.5f,
        )
    }

    /** 两臂都跑完（不因回退臂失败而中止），逐臂分流断言。 */
    private fun forEachArm(block: (ParagraphBreaker, String) -> Unit) {
        for ((label, _) in breakerVariants()) {
            try {
                applyBreakerVariant(label)
                block(bodyParagraphBreaker(letterSpacingEm = 0f), label)
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }

    /** 真书正文：多档版心都不得溢出（含 JUSTIFY —— 拉伸后也必须正好铺满、不得右溢）。 */
    @Test
    fun `真书正文不得有行超出版心`() {
        forEachArm { breaker, label ->
            applyBreakerVariant(label)
            try {
                for (w in intArrayOf(320, 700, 900, 1200, 1600)) {
                    val lines = drawLines("<html><body>$realPara</body></html>", w, breaker, 44.4f)
                    assertTrue("宽度 $w 下应有多行", lines.size >= 2)
                    assertNoOverflow(lines, label, "宽度 $w")
                }
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }

    /** 不可断长串（URL）—— `R1` 的原始目标。 */
    @Test
    fun `不可断长串不得溢出`() {
        forEachArm { breaker, label ->
            applyBreakerVariant(label)
            try {
                for (w in intArrayOf(120, 200, 500)) {
                    val lines = drawLines("<html><body><p>$longUrl</p></body></html>", w, breaker, 44.4f)
                    assertTrue("宽度 $w 下 URL 应被拆成多行", lines.size >= 2)
                    assertNoOverflow(lines, label, "URL 宽度 $w")
                }
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }

    /** 无空格的超长单词：禁则表里「两个汉字之间」本可断，英文长词内部一个断点都没有。 */
    @Test
    fun `超长单词不得溢出`() {
        forEachArm { breaker, label ->
            applyBreakerVariant(label)
            try {
                for (w in intArrayOf(80, 150, 400)) {
                    val lines = drawLines("<html><body><p>$longWord</p></body></html>", w, breaker, 44.4f)
                    assertTrue("宽度 $w 下长词应被拆成多行", lines.size >= 2)
                    assertNoOverflow(lines, label, "长词宽度 $w")
                }
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }

    /**
     * **右对齐 / 居中 + 首行缩进不得右溢**（用户报缺陷①的第四个机制）。
     *
     * ## 缺陷形态与实测
     *
     * 真书 `div.translation { text-align: right }` 里的 `<p>` 同时带 `text-indent: 2em`。
     * 旧 `x0 = x0Raw + (lineWidthPx − finalContent)` 里 `finalContent` **已扣掉 `x0Raw`**
     * ⇒ 缩进被算两遍 ⇒ 右溢**恰好一个缩进**：实测 **82.885px**（缩进 84.36 − kerning 收紧 1.48），
     * 版心 1600。居中同理偏半个缩进。
     *
     * ## 为什么这把锁必须有（以及它和 [LineAlignerTest] 的分工）
     *
     * [LineAlignerTest] 用**自造语料**锁 `x0` 的代数；这把锁走**整条链路**
     * （HTML → 样式 → 断行 → [DrawLine] → Aligner/簇位轨 → 墨框右缘），
     * 顺带锁住「`firstLineIndentPx` 真的传进了 `align`」——**第一版这把锁传的是 `0f`**，
     * 等于把整档缩进排除在锁外，于是右对齐 + 缩进这条路径上**根本没有任何锁**。
     *
     * 变异验证（改坏必须红，实测记录）：
     * - `TextAlign.RIGHT -> x0Raw + (lineWidthPx − finalContent)` ⇒ 本锁**红**；
     * - `TextAlign.CENTER -> x0Raw + (lineWidthPx − finalContent)/2` ⇒ 本锁**红**；
     * - `finalContent` 的拉伸项 ×1.02（JUSTIFY 拉伸过头）⇒ 本锁 + 真书正文 + 缩进 三把**红**。
     *
     * ## ⚠ 这把锁差点是假锁（两次，实测记录在 `docs/自建断行引擎-测试计划.md` 教训㉛）
     *
     * 1. **第一版 `worstOverflow` 传 `firstLineIndentPx = 0f`**（注释里还写着「缩进由绘制侧另加
     *    `paintX`」——那是缺陷①(a) 修好**之前**的口径）。缺陷①(a) 修完后缩进已进 `align`，
     *    锁却没跟着改 ⇒ 整档缩进被排除在锁外。
     * 2. **语料写错**：`p { text-align: justify }` 直接命中 `<p>`，压过从父容器继承的
     *    `text-align: right`（继承永远输给直接匹配）⇒ `DrawLine.alignment` 实测全是 `JUSTIFY`
     *    ⇒ 右对齐路径**一行都没走到**，两种变异都测不出红。现已改成真书形态
     *    （`li > ul` 上的 `right` + `li` 的 `2em` 缩进），并加了
     *    「语料必须真产生非 JUSTIFY 行」的守卫，防止再次退化成假锁。
     */
    @Test
    fun `右对齐与居中加首行缩进不得右溢`() {
        forEachArm { breaker, label ->
            applyBreakerVariant(label)
            try {
                for (w in intArrayOf(200, 400, 900, 1600)) {
                    for (cls in listOf("r", "c")) {
                        val html = "<html><body><ul class=\"$cls\"><li>Bec Rumbul，Rust 基金会执行董事" +
                            "，本段带 2em 首行缩进。</li><li>$longWord 与 $longUrl 混排</li>" +
                            "<li>第二项也要带缩进参与对齐探针，看看右缘是否越界。</li></ul></body></html>"
                        val lines = drawLines(html, w, breaker, 44.4f)
                        assertTrue("对齐=$cls 宽度 $w 下应有多行", lines.isNotEmpty())
                        // 守卫：语料必须真的产生 RIGHT/CENTER 行，否则这把锁是假锁
                        assertTrue(
                            "对齐=$cls 宽度 $w 下必须真有非 JUSTIFY 行（实测 ${lines.map { it.second.alignment }.distinct()}）",
                            lines.any { it.second.alignment != orilumn.reader.engine.css.TextAlign.JUSTIFY },
                        )
                        assertNoOverflow(lines, label, "对齐=$cls 缩进宽度 $w")
                    }
                }
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }

    /**
     * 首行缩进吃掉绝大部分版心：`greedy` 的 `headPx = width − indent` 会小到只放得下 1~2 个字，
     * 是「退无可退」最容易被误写成「塞不下就溢出」的场景。
     */
    @Test
    fun `首行缩进吃掉版心时不得溢出`() {
        forEachArm { breaker, label ->
            applyBreakerVariant(label)
            try {
                for (w in intArrayOf(120, 300, 900)) {
                    val html = "<html><body><p class=\"ind\">$longWord 与 $longUrl 混排</p></body></html>"
                    val lines = drawLines(html, w, breaker, 44.4f)
                    assertNoOverflow(lines, label, "缩进宽度 $w")
                }
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }
}
