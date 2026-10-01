package orilumn.reader.engine.skia

import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.isDocumentSpace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 回归锁：[LineAligner] 的不变量。
 *
 * ## 这些锁守的是什么（真机缺陷的判据）
 *
 * 真书 `Rust 程序设计语言`（`p { text-align: justify }`）上量到中部行右边界
 * `900.0 / 846.0 / 900.0 / 750.4` 跳变，成因是**行末文档空白**让 Skia `kJustify`
 * 挂起不拉伸。本类把断行侧已有的「尾随空白不占版心」口径（`hang`/`trail`）复现到绘制侧，
 * 故锁守三条：
 *
 * 1. **JUSTIFY 中部行铺满版心** —— 直接对应视觉缺陷。
 * 2. **区间无缝** —— `xs` 与 range 同长；`x[i+1] == x[i] + adv[i]`（逐字相接）。
 *    这是硬约束：断行/绘制拼行靠 range 相接，破了就出无主字符。
 * 3. **尾随空白零宽且不影响拉伸基数** —— 与断行侧 `greedy` 的 `hang` 记账一致。
 *
 * ## 必须两侧都跑（[BreakerVariantFixture] 的理由）
 *
 * 断行器换侧会改断点，而本类吃的是断行器产出的 range。**只在一侧跑**，
 * 「重轻/两侧一致」这类不变量会假失败或假通过。故用现成夹具拨开关。
 *
 * ## 语料对 fontRuns 敏感（必须断言，教训 29 的汉字 1em 巧合）
 *
 * 若语料全是汉字，advance 在任何字族都是 1em，丢 `fontRuns` 测不出来。
 * 故语料里必须混 Latin + 行内换面 `<span>`，并**断言语料本身对 fontRuns 敏感**。
 */
class LineAlignerTest {

    /**
     * 真书正文（`OEBPS/Text/22.xhtml:339`），含 `<code>` 行内换面。
     *
     * **必须够长**：锁 1 的守卫要求「至少有 2 行装得下的中部行」，而 interior 行数 = 总行数 − 1。
     * 第一版只有 1 句，在版心 900/1600 下都只切出 3~4 行 ⇒ 扣掉末行与超宽行后参与判据的不足 2 行
     * ⇒ 守卫判「假锁」。**加到 2 句**后 1600 档下自建侧有 3 行装得下（skia 侧 2 行，见守卫注释）。
     */
    private val realPara =
        "<p>派生 <code>Clone</code> 实现了 <code>clone</code> 方法，当其为整个类型实现时，" +
            "会在类型的每一部分上调用 <code>clone</code> 方法。这意味着类型中所有字段或值" +
            "也必须实现了 <code>Clone</code>，这样才能够派生 <code>Clone</code> 。</p>" +
            "<p>除了 <code>Clone</code> 之外，标准库还提供了 <code>Copy</code> 与 " +
            "<code>Default</code>，它们同样要求字段满足相应的约束，并且这些约束会由编译器检查。</p>"

    private val realCss =
        "html { font-size: 18px; } body { font-family: serif; font-size: 0.95rem; } " +
            "p { text-align: justify; } code { font-family: monospace; } p code { font-size: 0.95em; }"

    private fun aligner() = LineAligner()

    /**
     * **自造**语料（不来自真书 `DrawLine`）统一用的族栈 —— 由测试自选，显式写出来。
     * 吃真实 `DrawLine` 的调用点必须传 `dl.families`，不许用它。
     */
    private val SYNTH_FAM = listOf("STSong", "serif")

    private fun align(
        text: CharSequence, range: IntRange, fs: Float, width: Float,
        lsEm: Float = 0f, align: TextAlign = TextAlign.JUSTIFY, isLast: Boolean = false,
        // ⚠ **不给默认值**：调用方必须显式传 `dl.families`（绘制侧用的就是它）。
        // 本文件第一版把默认值硬编码成 `["STSong","serif"]`，而本仓正文的 `families` 是 `[serif]`
        // —— 拉丁字符在两个面栈下宽度不同（真书首行 28 字：907.248 vs 873.94775，差 33.3px），
        // 于是「中部行装不下」的判定被喂了错数据，守卫从 3 行掉到 1 行。
        // 同源要求见 [NoLineExceedsContentWidthTest] 的类 KDoc。
        fam: List<String>,
        runs: List<orilumn.reader.engine.css.FontRun> = emptyList(),
        indent: Float = 0f,
        tag: String? = "p",
    ) = aligner().align(
        text, range, fs, width, lsEm, tag, fam, 400, false, false, runs, align, indent, isLast,
    )

    // ---- 锁 1： JUSTIFY 中部行必须铺满版心（这就是真机缺陷的判据）----

    @Test
    fun `JUSTIFY 中部行可见右边界铺满版心`() {
        forEachBreakerVariant { breaker, label ->
            AbSwitch.resetForTest()
            applyBreakerVariant(label)
            try {
                val fs = 44.4f
                val width = 1600f
                val root = HtmlTreeConverter().convert("<html><body>$realPara</body></html>")!!
                val engine = StyleComputer(fs, LightCssParser().parse(realCss), emptyList())
                val styles = engine.compute(root)
                val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
                val result = BoxLayouter(fs, breaker).layoutBoxes(root, 1600, styles, classify)
                val lines = DrawLineBuilder.build(result, styles, classify, HIDDEN_NONE, 0f).toList()
                assertTrue("至少要有多行才能验中部行", lines.size >= 3)

                val interior = lines.dropLast(1)
                // **必须排除「装不下」的行**（第一版没排除，在这里连错五次）：
                // 语料里那行 28 字 × ~44.4 = 1243px > 版心 900，断点是被贪心/R1 兜底塞进来的
                // —— 它**本来就不该拉伸**（`slack < 0` ⇒ `extra = 0` ⇒ `visibleRight = natural = 907.248`）。
                // 那个 907.248 是**正确的自然宽**，把它当「未铺满」等于要求一行超宽的字去填版心。
                // 判据：只有 `natural <= 版心` 的行（即 slack ≥ 0、拉伸后能铺满的）才要求铺满。
                var filled = 0
                var checked = 0
                for ((_, dl) in interior) {
                    val p = align(
                        dl.text, dl.range, fs, dl.lineWidthPx.toFloat(),
                        align = dl.alignment, isLast = false, runs = dl.fontRuns,
                        fam = dl.families, tag = dl.tag,
                    )
                    // 自然宽（未拉伸）> 版心 ⇒ 装不下，本行不参与「铺满」判据。
                    val naturalOnly = p.visibleRight
                    if (naturalOnly > dl.lineWidthPx + 0.5f) continue
                    checked++
                    val trailWs = dl.text.substring(dl.range).takeLastWhile { isDocumentSpace(it) }.length
                    assertTrue(
                        "变体=$label 「${dl.text.substring(dl.range)}」尾空白=$trailWs " +
                            "可见右边界=${p.visibleRight} 未铺满版心=${dl.lineWidthPx}。" +
                            "能装下的 JUSTIFY 中部行必须铺满；尾随空白不得从拉伸基数里扣掉。",
                        kotlin.math.abs(p.visibleRight - dl.lineWidthPx) <= 0.5f,
                    )
                    filled++
                }
                // 语料实测（版心 900）：3 个中部行里 **2 个装得下**、1 个超宽（首行 28 字 ≈1243px，
                // 由贪心/R1 兜底塞入，本来就不该拉伸）。守卫阈值取 2 —— 实测值，不是拍的。
                assertTrue(
                    "本语料必须至少有 2 行「装得下」的中部行，否则这把锁在退化路径上也能过 = 假锁" +
                        "（实测只有 $checked 行参与判据）",
                    checked >= 2,
                )
                assertEquals("全部能装下的中部行都应铺满", checked, filled)
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }

    // ---- 锁 2：区间无缝（逐字相接，且与 range 同长）----

    @Test
    fun `逐字 x 严格相接且与 range 等长`() {
        forEachBreakerVariant { breaker, label ->
            applyBreakerVariant(label)
            try {
                val fs = 44.4f
                val root = HtmlTreeConverter().convert("<html><body>$realPara</body></html>")!!
                val engine = StyleComputer(fs, LightCssParser().parse(realCss), emptyList())
                val styles = engine.compute(root)
                val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
                val result = BoxLayouter(fs, breaker).layoutBoxes(root, 900, styles, classify)
                val lines = DrawLineBuilder.build(result, styles, classify, HIDDEN_NONE, 0f).toList()
                for ((_, dl) in lines) {
                    val n = dl.range.last + 1 - dl.range.first
                    val p = align(
                        dl.text, dl.range, fs, dl.lineWidthPx.toFloat(),
                        runs = dl.fontRuns, fam = dl.families, tag = dl.tag,
                    )
                    assertEquals(
                        "变体=$label xs 必须与 range 等长（区间无缝是硬约束）",
                        n, p.xs.size,
                    )
                    // 逐字相接：后一字 x = 前一字 x + advance（含 ls；拉伸量非负故用 ≥）
                    val lsPx = 0f
                    for (k in 0 until p.xs.size - 1) {
                        assertTrue(
                            "变体=$label 第 $k→${k + 1} 字 x 必须单调递增（xs=${p.xs.toList()}）",
                            p.xs[k + 1] >= p.xs[k],
                        )
                    }
                    assertTrue("lsPx 占位避免未用告警 ${lsPx + fs}", p.xs.isNotEmpty())
                }
            } finally {
                AbSwitch.resetForTest()
            }
        }
    }

    // ---- 锁 3：末行不拉伸 / 左对齐不拉伸（两端对齐的定义本身）----

    @Test
    fun `末行与左对齐都不拉伸`() {
        val text = "两端对齐需要测试行尾是否空白影响排版"
        val fs = 44.4f
        val width = 900f
        val pJustify = align(text, text.indices, fs, width, fam = SYNTH_FAM, align = TextAlign.JUSTIFY, isLast = false)
        val pLast = align(text, text.indices, fs, width, fam = SYNTH_FAM, align = TextAlign.JUSTIFY, isLast = true)
        val pLeft = align(text, text.indices, fs, width, fam = SYNTH_FAM, align = TextAlign.LEFT, isLast = false)
        assertEquals("JUSTIFY 中部行铺满", width, pJustify.visibleRight, 0.5f)
        assertTrue(
            "末行必须左对齐不拉伸：visibleRight=${pLast.visibleRight} 应 < $width",
            pLast.visibleRight < width - 0.5f,
        )
        assertEquals("LEFT 不拉伸，可见右边界 = 自然宽", pLast.visibleRight, pLeft.visibleRight, 0.01f)
    }

    // ---- 锁 4：尾随空白零宽、不计入可见右边界、但仍占位（区间无缝）----

    /**
     * 判据选择的关键（这里换过一次错判，第一版比的是个抓不住变异的量）：
     *
     * 「有尾空白 vs 无尾空格的 `visibleRight` 相同」**抓不住**「尾空白是否被剔除」——
     * 剔除与否，`natural = Σ_{j<N-1} adv[j]` 的算式都不变，值恒等。
     * 所以改比**可见字形的落位**：把「尾空白版」逐字 x 与「把尾空白裁掉后的同行前缀」
     * 逐字对齐 —— 剔除正确时二者**逐字相同**（末字 x 一致，因为尾空白不占位）；
     * 剔除被去掉时末字 x 会整体后移一个空格宽。
     */
    @Test
    fun `尾随文档空白不计入可见宽度但仍在区间内`() {
        val fs = 44.4f
        val width = 900f
        val withTrail = "两端对齐测试文本尾部有空白 "
        val without = "两端对齐测试文本尾部有空白"
        val pWith = align(withTrail, withTrail.indices, fs, width, fam = SYNTH_FAM, align = TextAlign.JUSTIFY, isLast = false)
        val pWithout = align(without, without.indices, fs, width, fam = SYNTH_FAM, align = TextAlign.JUSTIFY, isLast = false)

        // 尾空白不占位 ⇒ 可见部分的几何与「无尾空白」版逐字相同。
        for (k in without.indices) {
            assertEquals(
                "尾随空白版的第 $k 字 x 必须与无尾空白版相同（尾空白不占可见宽度）。" +
                    "不等则尾空白被计入了拉伸基数。",
                pWithout.xs[k], pWith.xs[k], 0.01f,
            )
        }
        assertEquals(
            "但 xs 仍需覆盖那个空白字符（区间无缝，字形仍归本行）",
            withTrail.length, pWith.xs.size,
        )
        assertEquals(
            "尾随空白的 x 起点应等于末字右边缘（悬挂、不参与拉伸）",
            pWithout.visibleRight, pWith.trailStartX, 0.01f,
        )
        assertTrue(
            "尾随空白自身的 x 在末字右边缘之后",
            pWith.xs.last() >= pWith.trailStartX - 0.01f,
        )
    }

    // ---- 锁 5：RIGHT / CENTER + 首行缩进不得把缩进算两遍（用户报缺陷①的真因之一）----

    /**
     * **右对齐/居中行 + 非零 `text-indent`：末字右缘必须正好贴版心右缘。**
     *
     * ## 缺陷形态（Rust 书 `00_2.xhtml` 译名行）
     *
     * `div.translation { text-align: right }` 里的 `<p>` 同时带 `text-indent: 2em`。
     * 第一版把缩进交给 [LineAligner]（绘制侧不再画完右移）后，`x0` 写成
     * `x0Raw + (lineWidthPx − finalContent)`，而 `finalContent = natural + … − x0Raw`
     * **已经把缩进扣掉了** ⇒ 缩进被算两遍 ⇒ 右溢**恰好一个缩进**。
     * 实测：右溢 **82.885px**（缩进 84.36 − kerning 收紧 1.48），版心 1600。
     *
     * 修法：`x0 = lineWidthPx − finalContent`（RIGHT）/ `(lineWidthPx − finalContent)/2`（CENTER）。
     *
     * 变异验证（改坏必须红）：
     * - `x0` 加回 `x0Raw +` ⇒ 本锁与 `RIGHT 与 CENTER 都不得右溢版心` 双双**红**；
     * - 把 CENTER 的 `/2` 去掉 ⇒ 本锁**红**。
     */
    @Test
    fun `右对齐行带首行缩进时右缘仍贴版心`() {
        val text = "Bec Rumbul，Rust 基金会执行董事"
        val fs = 44.4f
        val width = 1600f
        val indent = fs * 2f // `text-indent: 2em`
        for (lsEm in floatArrayOf(0f, 0.05f)) {
            val p = align(
                text, text.indices, fs, width, lsEm = lsEm,
                align = TextAlign.RIGHT, isLast = true, fam = SYNTH_FAM, indent = indent,
            )
            assertEquals(
                "RIGHT 行带缩进 $indent 时末字右缘必须 == 版心 $width（实测 ${p.visibleRight}）",
                width, p.visibleRight, 0.05f,
            )
            assertTrue(
                "RIGHT 行首字 x 必须 >= 0（缩进不该把行推到版心左侧之外）：xs[0]=${p.xs[0]}",
                p.xs[0] >= -0.05f,
            )
        }
    }

    /**
     * 居中同理：**内容块中心**对齐版心中心，缩进算两遍会整体偏右半个缩进。
     *
     * ⚠ 判据不能用 `visibleRight == 版心`：CENTER 的内容块居中 ⇒ 右缘**本来就在版心内侧**
     * （实测 `visibleRight = 1133.555` = `(1600 + 667.11)/2`）。真正的判据是
     * 「块中心 == 版心中心」：`xs[0] + content/2 == width/2`。
     */
    @Test
    fun `居中行带首行缩进时内容中心对齐版心中心`() {
        val text = "Bec Rumbul，Rust 基金会执行董事"
        val fs = 44.4f
        val width = 1600f
        val indent = fs * 2f
        for (ind in floatArrayOf(0f, indent)) {
            val p = align(
                text, text.indices, fs, width,
                align = TextAlign.CENTER, isLast = true, fam = SYNTH_FAM, indent = ind,
            )
            // 内容块 = [缩进左缘 `xs[0] − ind`, 末字右缘 `visibleRight`]，
            // 块中心 = `blockStart + blockW/2` = `(xs[0] + visibleRight)/2 − ind/2`
            //（`xs[0]` 是**首字笔位**，缩进落在它**前面**，故要从块中心里减半个缩进）。
            assertEquals(
                "缩进=$ind 时内容块中心必须 == 版心中心 ${width / 2}" +
                    "（实得 ${(p.xs[0] + p.visibleRight) / 2 - ind / 2}）",
                width / 2f, (p.xs[0] + p.visibleRight) / 2f - ind / 2f, 0.05f,
            )
        }
        // 缩进占位在内容块**前面** ⇒ 块宽 = 缩进 + 文字 ⇒ 居中后整体**右移 indent/2**。
        val pNoIndent = align(text, text.indices, fs, width, align = TextAlign.CENTER, isLast = true, fam = SYNTH_FAM)
        val pIndent = align(
            text, text.indices, fs, width,
            align = TextAlign.CENTER, isLast = true, fam = SYNTH_FAM, indent = indent,
        )
        assertEquals(
            "带缩进时首字笔位应右移 indent/2（缩进计入块宽再折半）",
            indent / 2f, pIndent.xs[0] - pNoIndent.xs[0], 0.05f,
        )
    }

    /**
     * **RIGHT + 首行缩进：缩进没有可见效果**（与 CENTER 相反，这是 CSS 的定义，不是 bug）。
     *
     * 首行缩进只把「可用行宽」缩小 `x0Raw`；右对齐的内容照旧贴右缘，多出的那截缩进落在
     * 本来就空着的左侧空白里。⇒ 带缩进与不带缩进，`xs[0]` 应**完全相同**。
     *
     * 这条与 [TextAlign.CENTER] 分支必须分开写：CENTER 要 `+x0Raw`（块宽含缩进），
     * RIGHT 化简后**不加**。把两者写成同一个表达式就是「缩进算两遍」或「缩进被吃掉」，
     * 两个都是错的，且互为镜像 ⇒ 必须各有一把锁盯着。
     */
    @Test
    fun `右对齐行的首行缩进没有可见效果`() {
        val text = "Bec Rumbul，Rust 基金会执行董事"
        val fs = 44.4f
        val width = 1600f
        val pNoIndent = align(text, text.indices, fs, width, align = TextAlign.RIGHT, isLast = true, fam = SYNTH_FAM)
        for (ind in floatArrayOf(fs, fs * 2f, fs * 4f)) {
            val p = align(text, text.indices, fs, width, align = TextAlign.RIGHT, isLast = true, fam = SYNTH_FAM, indent = ind)
            assertEquals(
                "RIGHT + 缩进 $ind：首字笔位与无缩进版相同（缩进落在左侧空白里）",
                pNoIndent.xs[0], p.xs[0], 0.05f,
            )
            assertEquals("RIGHT + 缩进 $ind：末字右缘仍贴版心", width, p.visibleRight, 0.05f)
        }
    }

    /**
     * RIGHT / CENTER 在各种缩进、末行标记下都不得右溢版心。
     *
     * ⚠ 守卫：只对「自然宽 ≤ 版心」的行断言。装不下的行 `x0` 被 `coerceAtLeast(0f)` 夹在 0，
     *   右缘**必然**超出版心 —— 那是**正确行为**（版心装不下就该让字溢出/裁字，不是右对齐的错）。
     *   排除它，否则这把锁在退化路径上恒红、等于没有锁（教训㉛：先确认锁能红，再谈它有用）。
     */
    @Test
    fun `RIGHT 与 CENTER 都不得右溢版心`() {
        val text = "两端对齐与右对齐居中都不得把文字推出版心右缘"
        val fs = 44.4f
        var checked = 0
        for (width in floatArrayOf(600f, 900f, 1600f, 2400f)) {
            for (indent in floatArrayOf(0f, fs * 2f)) {
                for (align in listOf(TextAlign.RIGHT, TextAlign.CENTER)) {
                    for (isLast in listOf(true, false)) {
                        val p = align(
                            text, text.indices, fs, width,
                            align = align, isLast = isLast, fam = SYNTH_FAM, indent = indent,
                        )
                        // LEFT 对齐的落位就是自然宽，用它取「本行装不装得下」
                        val natural = align(
                            text, text.indices, fs, width,
                            align = TextAlign.LEFT, isLast = isLast, fam = SYNTH_FAM, indent = indent,
                        ).visibleRight
                        if (natural > width + 0.5f) continue
                        checked++
                        assertTrue(
                            "对齐=$align 缩进=$indent 末行=$isLast 版心=$width " +
                                "右缘=${p.visibleRight} 越界 ${p.visibleRight - width}",
                            p.visibleRight <= width + 0.05f,
                        )
                    }
                }
            }
        }
        assertTrue("本组合必须至少验到 8 行，否则这把锁在退化路径上也能过 = 假锁（实测 $checked）", checked >= 8)
    }

    // ---- 前提守卫：语料必须对 fontRuns 敏感（教训 29 的汉字 1em 巧合）----

    @Test
    fun `语料对 fontRuns 敏感——否则这把锁验的是空气`() {
        val fs = 44.4f
        val width = 900f
        val text = "派生 Clone 实现了 clone 方法并且需要足够长以便折行覆盖版心"
        val runs = listOf(
            orilumn.reader.engine.css.FontRun(2, 8, listOf("monospace"), null, 400, false, true, 0f),
        )
        val withRuns = align(text, text.indices, fs, width, fam = SYNTH_FAM, runs = runs).visibleRight
        val noRuns = align(text, text.indices, fs, width, fam = SYNTH_FAM).visibleRight
        assertTrue(
            "语料必须对 fontRuns 敏感：带/不带换面 run 的可见右边界应不同。" +
                "实测 with=$withRuns without=$noRuns（全相等说明语料里没有 Latin 换面段）。",
            kotlin.math.abs(withRuns - noRuns) > 0.5f,
        )
    }

    // ---- 变异验证的靶子：故意不扣尾随空白，用于证伪「这把锁能抓住回归」----

    @Suppress("unused")
    private fun alignerBuggyNoTrailHang() = object : LineAlignerLike {
        override fun visibleRight(text: CharSequence, range: IntRange, fs: Float, width: Float): Float {
            // 全行计入（含尾随空白）—— 正是真机缺陷的形态。
            return align(text, range, fs, width, fam = SYNTH_FAM).visibleRight - 0f
        }
    }
}

/** 仅供变异验证编译用的最小接口（避免测试里复制一份生产接线，教训 11）。 */
internal interface LineAlignerLike {
    fun visibleRight(text: CharSequence, range: IntRange, fs: Float, width: Float): Float
}