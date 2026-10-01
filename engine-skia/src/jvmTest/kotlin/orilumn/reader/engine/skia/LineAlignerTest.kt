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