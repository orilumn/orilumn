package orilumn.reader.engine.skia

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.ChapterStructureCache
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderUiSheet
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.css.uaSheetFromProfile
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ParagraphBreaker
import orilumn.reader.engine.laying.lineHeightPx
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * R1 回归：`text-indent` 的**单行段落**在「版心只比整段宽一两个字」时**不换行、尾部被版心右缘裁掉**。
 *
 * ## 现象
 *
 * 一段本来只有一行的文字，在「版心宽 − 边距」恰好比它的整段宽少 1~2 个字时，不折行也不掉字，
 * 而是尾部 1~2 个字被右缘切掉（读者看到「这段字被拦腰截断」）。
 *
 * ## 根因（探针实测，锁定在 [SkiaParagraphBreaker.layoutOnce] 的 R1 补偿注释里）
 *
 * 断行侧用 SkParagraph 的 `TextIndent` 承担首行缩进，而 `TextIndent` **只在首行真的折行时**扣减
 * 首行可用宽；「整段放得下一行」的快捷路径拿整段自然宽直接比 layout 宽，**不扣 indent**
 * （实测：indent 取 0/20/40/60，「整段单行」的临界版心宽恒等于整段自然宽）。于是窗口
 * `版心 − indent < 整段自然宽 ≤ 版心` 内断行侧判「放得下」不换行，绘制侧
 * （[LineWindowDrawer]：`paintX = textX + firstLineIndentPx`）仍把整行右移 indent，右缘越出版心
 * 至多 indent（默认 `text-indent: 2em` = 2 字）——正是「只差一两个字」的量级。
 * 只在一行时发作：一旦折行，Skia 自己就把首行按 `版心 − indent` 排对了。
 *
 * ## 本类锁的不变量
 *
 * 1. **不溢出**：用**绘制侧真实整形结果**（同配置、同 `lineWidthPx`）断言
 *    「首行缩进 + 第 0 行宽 ≤ 版心宽」，且绘制侧不得把该行二次折成多行（量画一致）；
 * 2. **不丢字**：断行区间仍严格连续覆盖全文（分页/CFI 锚点零漂移），行高仍走统一行框单一来源；
 * 3. **不多折**：整段 + 缩进放得下时保持单行（补偿只在命中窗口时才重排）；
 * 4. **不误伤**：无缩进路径逐值不变（补偿只对带 `text-indent` 的段生效）；
 * 5. **重轻等价**：同一段 HTML，重路径（[BoxLayouter]）与轻路径（[BoxChapterLayouter.tempShape]）
 *    的断行区间逐项相同。
 *
 * 度量用 Skia 自己的 `lineMetrics[0].width`（行尾空白不计入，正是墨迹范围），**不用**
 * `maxIntrinsicWidth`——后者含行尾空格的 advance，会虚报约一个空格宽的溢出。
 * 亚像素余量沿用仓库既有口径（[SkiaParagraphBreakerTest.everyLineFitsWithinContainer] 的 `+1f`）；
 * 本 bug 的溢出量级是 `indent`（≈2 字 ≈ 数十 px），与该余量差两个数量级，不会被容差吞掉。
 */
class FirstLineIndentSingleLineOverflowTest {

    private val fs = 44.4f
    private val ratio = 1.5f

    /**
     * 真机字体栈（真书 `p { font-family: STSong, serif }`）。
     *
     * 必须带上：本 bug 是 SkParagraph 的**字体相关**行为——实测同一段字在真机字体栈下
     * 「整段单行」只看 `nat ≤ 版心`（缩进完全不参与，故障复现），而在无族（`emptyList()`）
     * 的回退字体下缩进却被算进去了（不复现）。所以回归必须钉在真机字体栈上，否则测了个寂寞。
     * 族名缺失时 Skia 按字形回退，仍按「不溢出」不变量断言，绝不会假绿。
     */
    private val families = listOf("STSong", "serif")

    /** 断行度量/绘制共用的亚像素余量（同 [SkiaParagraphBreakerTest] 既有口径）。 */
    private val slack = 1f

    /** `text-indent: 2em`（traditional 主题 `p` 规则的默认值）。 */
    private val indent = 2f * fs

    /** 真书原句：26 字，本 bug 在真机参数下的最小复现（`nat + 2em` 恰好越出版心）。 */
    private val cjk = "都用到这么厉害的科技了，事情肯定不会如字面上写的那样简单。"

    /** 整段「不折行」自然宽（`maxIntrinsicWidth`）——定义命中窗口用，不用于溢出判定。 */
    private fun naturalWidth(sub: String, fontSizePx: Float = fs): Float {
        val p = org.jetbrains.skia.paragraph.ParagraphBuilder(
            SkParagraphFactory.paragraphStyle(TextAlign.LEFT, fontSizePx, ratio, "p", families, 400, false, false, letterSpacingEm = 0f),
            SkParagraphFactory.defaultCollection(),
        ).addText(sub).build()
        return try {
            p.layout(Float.MAX_VALUE)
            p.maxIntrinsicWidth
        } finally {
            p.close()
        }
    }

    /** [LineWindowDrawer] 对 [sub] 的真实整形结果：`(行数, 第 0 行宽)`，同配置同版心宽。 */
    private fun drawn(
        sub: String,
        layoutWidth: Int,
        alignment: TextAlign,
        fontSizePx: Float,
        families: List<String> = this.families,
        weight: Int = 400,
        italic: Boolean = false,
        monospace: Boolean = false,
        letterSpacingEm: Float = 0f,
    ): Pair<Int, Float> {
        val style = SkParagraphFactory.paragraphStyle(
            alignment, fontSizePx, ratio, "p", families, weight, italic, monospace, letterSpacingEm,
        )
        val p = org.jetbrains.skia.paragraph.ParagraphBuilder(style, SkParagraphFactory.defaultCollection())
            .addText(sub).build()
        return try {
            p.layout(layoutWidth.coerceAtLeast(1).toFloat())
            val n = p.lineMetrics.size
            n to if (n > 0) p.lineMetrics[0].width.toFloat() else 0f
        } finally {
            p.close()
        }
    }

    /**
     * **绘制侧代理**—— 按断行臂分派（2026-10-03 标点挤压落地时改）。
     *
     * ## 为什么不能一直用 [drawn]（Skia `Paragraph`）
     *
     * S5 之后生产绘制是 [LineAligner] + 逐字 `drawString`
     * （[orilumn.reader.engine.skia.LineWindowDrawer] 的 `paintGlyphs`），
     * `SkParagraph` **已经不再是绘制器** —— 它只剩回退阀那一侧的**断行器**身份
     * （[orilumn.reader.engine.skia.SkiaParagraphBreaker]）。
     *
     * 标点挤压（[orilumn.reader.engine.skia.PunctuationSqueeze]）让自建臂的宽度模型与
     * `SkParagraph` **有意不同**（收窄收尾标点的字位）。此时仍拿 `SkParagraph` 当绘制侧代理，
     * 就是「用一个不参与生产的量去判生产的不变量」⇒ 必然假红（实测本类三条锁全红：
     * 溢出 2.0px / 60.64px，而生产绘制侧的同一行是合规的）。
     *
     * ⇒ **自建臂改用真正的绘制器量**；Skia 臂保持原样（那一臂的绘制侧本就是 `SkParagraph`）。
     * 这样两条锁的不变量都变成「**该臂自己的**断行器 vs **该臂自己的**绘制器」，
     * 而不是跨臂错配。
     */
    private fun painted(
        breaker: ParagraphBreaker,
        sub: String,
        layoutWidth: Int,
        alignment: TextAlign,
        fontSizePx: Float,
        families: List<String>,
        weight: Int,
        italic: Boolean,
        monospace: Boolean,
        letterSpacingEm: Float,
        indentPx: Float,
        hyphenAtEnd: Boolean,
        fontRuns: List<orilumn.reader.engine.css.FontRun> = emptyList(),
    ): Pair<Int, Float> {
        if (breaker !is InhouseParagraphBreaker) {
            return drawn(sub, layoutWidth, alignment, fontSizePx, families, weight, italic, monospace, letterSpacingEm)
        }
        fun rightOf(al: TextAlign, lastLine: Boolean): Float = LineAligner().align(
            text = sub,
            range = 0 until sub.length,
            fontSizePx = fontSizePx,
            lineWidthPx = layoutWidth.coerceAtLeast(1).toFloat(),
            letterSpacingEm = letterSpacingEm,
            tag = "p",
            families = families,
            weight = weight,
            italic = italic,
            monospace = monospace,
            fontRuns = fontRuns,
            align = al,
            firstLineIndentPx = indentPx,
            // **末行口径 = 不拉伸**，量的是**内容本来的宽**。理由：拉伸只会把右缘拉到版心、
            // 把「断行器判错了」这个信号**掩盖**掉；不拉伸的版本在三种对齐下都给出同一个
            // `缩进 + 内容宽`，是最严的一条。CENTER/RIGHT/JUSTIFY 的 `x0` 定位另有锁
            // （[NoLineExceedsContentWidthTest] 与 `LineWindowDrawerTest`），此处不重复。
            isLastLine = true,
            hyphenAtEnd = hyphenAtEnd,
        ).visibleRight
        // 两个都量：真实对齐的几何 + LEFT 不拉伸的内容宽（后者恒 ≥ 前者，取 max 即最严）。
        val real = rightOf(alignment, true)
        val content = rightOf(TextAlign.LEFT, true)
        // 逐字落位**不可能**二次折行（S5 的结构性质：每字一次 `drawString`，Skia 无从整形），
        // 故行数恒为 1；这条在自建臂上不再由本代理提供，交给 [NoLineExceedsContentWidthTest] 的簇位轨分支。
        return 1 to maxOf(real, content)
    }

    /** 断行 + 「绘制侧右移后是否越版心」检查；返回违规描述（无违规返回 null）。 */
    private fun violationAt(
        breaker: ParagraphBreaker,
        text: String,
        width: Int,
        firstLineIndentPx: Float,
        fontSizePx: Float = fs,
        alignment: TextAlign = TextAlign.LEFT,
    ): String? {
        val lines = breaker.breakLines(text, fontSizePx, ratio, width, alignment, "p", families, 400, false, false, firstLineIndentPx)
        assertEquals(
            "版心=$width 断行区间必须连续覆盖全文",
            text,
            lines.joinToString("") { text.substring(it.range) },
        )
        for (line in lines) assertEquals("行高必须是统一行框单一来源", lineHeightPx(fontSizePx, ratio), line.heightPx)
        for ((i, l) in lines.withIndex()) {
            val (n, w0) = painted(
                breaker, text.substring(l.range), width, alignment, fontSizePx,
                families, 400, false, false, 0f, if (i == 0) firstLineIndentPx else 0f, l.hyphenAtEnd,
            )
            if (n != 1) return "版心=$width 第 $i 行在绘制侧被折成 $n 行（量画失步）"
            val right = w0
            if (right > width + slack) return "版心=$width 第 $i 行右缘 $right 越出版心（溢出 ${right - width}px）"
        }
        return null
    }

    @Test
    fun `命中窗口的单行段落必须折行且首行不越版心`() = forEachBreakerVariant { breaker, v ->
        // 扫多个字号：命中窗口宽 = 2em 缩进，跨字号才覆盖到「整段恰好放得下」的全部位置。
        for (fontSizePx in listOf(16f, 24f, 33.3f, 44.4f)) {
            val nat = naturalWidth(cjk, fontSizePx)
            val ind = 2f * fontSizePx
            assertTrue("前提：整段自然宽应显著大于一个缩进，否则窗口为空（fs=$fontSizePx nat=$nat）", nat > 4 * ind)
            // 从「整段刚好放得下」往下扫，扫完整个命中窗口（版心 − indent < 自然宽 ≤ 版心）。
            // 起点必须向上取整：自然宽是小数时 trunc 会落到阈值下方，扫到的全是已折行的版心，
            // 单行失效区反而被跳过（本测试第一版就栽在这，修复前后都绿）。
            val windowLo = (nat - ind).toInt()
            var width = ceil(nat).toInt()
            var checked = 0
            while (width > windowLo) {
                assertNull("[$v] fs=$fontSizePx 版心=$width 必须不越版心", violationAt(breaker, cjk, width, ind, fontSizePx))
                assertTrue(
                    "[$v] fs=$fontSizePx 版心=$width 命中窗口（自然宽 $nat + 缩进 $ind > 版心）必须折行，修复前是单行、尾部被裁",
                    breaker.breakLines(cjk, fontSizePx, ratio, width, TextAlign.LEFT, "p", families, 400, false, false, ind).size >= 2,
                )
                width--
                checked++
            }
            assertTrue("[$v] fs=$fontSizePx 命中窗口至少要扫过 2 像素宽（indent=$ind），实际 $checked", checked >= 2)
        }
    }

    @Test
    fun `整段加缩进放得下时保持单行`() = forEachBreakerVariant { breaker, v ->
        val nat = naturalWidth(cjk)
        // 版心取 `ceil(自然宽 + 缩进)`：自然宽常是小数，分开取整会低估（`ceil(nat) + indent`
        // 仍可能小于 `nat + indent`，把版心压回命中窗口，测试就变成在测「必须折行」）。
        val width = ceil(nat + indent).toInt()
        val lines = breaker.breakLines(cjk, fs, ratio, width, TextAlign.LEFT, "p", families, 400, false, false, indent)
        assertEquals("[$v] 整段 + 缩进放得下就不该多折行（补偿只在命中窗口触发）", 1, lines.size)
        assertEquals(0 until cjk.length, lines[0].range)
        assertNull(violationAt(breaker, cjk, width, indent))
    }

    @Test
    fun `无缩进路径逐值不变`() = forEachBreakerVariant { breaker, v ->
        // 护栏：补偿只对带 text-indent 的段生效，无缩进断行与修复前完全一致。
        val text = "床前明月光，疑是地上霜。举头望明月，低头思故乡。".repeat(3)
        for (w in 120..900 step 7) {
            assertEquals(
                "[$v] 版心=$w 无缩进路径被误改",
                breaker.breakLines(text, fs, ratio, w, TextAlign.LEFT, "p", families, 400, false, false)
                    .map { it.range to it.heightPx },
                breaker.breakLines(text, fs, ratio, w, TextAlign.LEFT, "p", families, 400, false, false, 0f)
                    .map { it.range to it.heightPx },
            )
        }
    }

    @Test
    fun `Latin与三种对齐下同样不溢出`() = forEachBreakerVariant { breaker, v ->
        // JUSTIFY（真书 `p { text-align: justify }`）与 Latin 混排走同一命中窗口，一并锁住。
        val latin = "Chapter twelve: the registrar of the clan house was waiting. ".repeat(2).trimEnd()
        for (alignment in listOf(TextAlign.LEFT, TextAlign.JUSTIFY, TextAlign.CENTER)) {
            val nat = naturalWidth(latin)
            var width = ceil(nat).toInt() // 同上：起点向上取整，必须扫到单行失效区
            while (width > (nat - indent).toInt()) {
                val v2 = violationAt(breaker, latin, width, indent, fs, alignment)
                assertNull("[$v] 对齐=$alignment 版心=$width $v2", v2)
                width--
            }
        }
    }

    // ---- 真书端到端（重路径 + 绘制侧投影）与重轻等价 ----

    /** 真书 `stylesheet.css` 里对 `p` 生效的规则。 */
    private val authorCss = """
        p { display: block; font-family: STSong, serif; font-size: 1em; line-height: 1.2;
            text-align: justify; text-indent: 2em; margin: 0; padding: 0; border: currentColor none 0; }
    """.trimIndent()

    /** 真书《每天都离现形更近一步》正文原样（含本 bug 的复现句）。 */
    private val realHtml = """
        <html><body>
        <p class="calibre1">风羿眉梢挑了挑。</p>
        <p class="calibre1">这话听着不对啊。</p>
        <p class="calibre1">“老科技。”老管家说道，“你去祖宅的时候也戴着它，它是打开族谱的钥匙。”</p>
        <p class="calibre1">风羿意识到，或许这个“族谱”，与他所想的并不一样。</p>
        <p class="calibre1">他没关注过科技板块，也不知道如今究竟有哪些神奇的科技，听老管家这么说，感慨了一会儿科技日新月异。甭管新科技还是老科技，震得住人的就是牛X科技。</p>
        <p class="calibre1">至少他震惊了。</p>
        <p class="calibre1">同时心中升起十二万分的警惕。</p>
        <p class="calibre1">都用到这么厉害的科技了，事情肯定不会如字面上写的那样简单。</p>
        <p class="calibre1">但协议还是签了。他如今的处境，这个才是最优解。</p>
        </body></html>
    """.trimIndent()

    /** 真机参数：viewW=1840、density=2.4 → 边距 50dp=120px，版心 = 1840−240 = 1600。 */
    private fun profile(fontSize: Int, fontScale: Double) = TypographicProfile.build(
        ReaderSettings.DEFAULT.copy(
            layoutTheme = "traditional", fontSize = fontSize, fontScale = fontScale,
            lineSpacing = 1.5, firstLineIndent = 2.0,
            marginLeft = 50, marginRight = 50, marginTop = 100, marginBottom = 60,
        ),
        2.4f,
    )

    /** 真机当前字号（`fontSize=18 / fontScale=51.39` → 正文 ≈ 44.4px）。 */
    private val deviceProfile = profile(18, 51.39)

    /** 备用字号：多字号才覆盖到「整段恰好放得下」在各种版心下的全部位置。 */
    private val otherProfile = profile(14, 40.0)

    /** 版心 = 视图宽 − 左右边距（真机 1840 − 120 − 120 = 1600）。 */
    private fun baseContentW(p: TypographicProfile) = 1840 - p.marginLeft - p.marginRight

    private fun styleEngine(p: TypographicProfile) = StyleComputer(
        p.bodyPx,
        uaSheetFromProfile(p),
        listOf(LightCssParser().parse(authorCss)),
        themeSheetFromProfile(p),
        null,
        ReaderUiSheet.build(p),
        gapScale = p.paragraphGapScale,
    )

    /** 真书全链路扫版心：重路径断行 → [DrawLineBuilder] 投影 → 绘制侧真实整形，任一行右缘越版心即失败。 */
    @Test
    fun `真书重路径全链路扫版心无一行越出右缘`() = forEachBreakerVariant { breaker, v ->
        for (p in listOf(deviceProfile, otherProfile)) {
            val root = HtmlTreeConverter().convert(realHtml)!!
            val eng = styleEngine(p)
            val styles = eng.compute(root)
            val classify = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
            // 命中窗口宽 = 2em 缩进；这里扫 320px，足以覆盖该字号下窗口的全部位置。
            for (w in (baseContentW(p) - 320)..(baseContentW(p) + 40)) {
                val result = BoxLayouter(p.bodyPx, breaker).layoutBoxes(root, w, styles, classify)
                for ((idx, dl) in DrawLineBuilder.build(result, styles, classify, HIDDEN_NONE, p.letterSpacingEm)) {
                    val sub = dl.text.substring(dl.range)
                    val (n, right) = painted(
                        breaker, sub, dl.lineWidthPx, dl.alignment, dl.fontSizePx,
                        dl.families, dl.weight, dl.italic, dl.monospace, dl.letterSpacingEm,
                        dl.firstLineIndentPx, dl.hyphenAtEnd, dl.fontRuns,
                    )
                    assertEquals("[$v] bodyPx=${p.bodyPx} 版心=$w 行 $idx 在绘制侧被二次折行（量画失步）", 1, n)
                    assertTrue(
                        "[$v] bodyPx=${p.bodyPx} 版心=$w 行 $idx 右缘 $right 越出版心 ${dl.lineWidthPx}" +
                            "（缩进 ${dl.firstLineIndentPx}）：\"$sub\"",
                        right <= dl.lineWidthPx + slack,
                    )
                }
            }
        }
    }

    /**
     * 重路径 vs 轻路径：同一段 HTML、同一版心，每个叶的断行区间必须逐项相同。
     *
     * ⚠ 混排字距必须喂 [TypographicProfile.cjkLatinSpacingEmApplied]（生产真值）：
     * 轻路径走 [BoxChapterLayouter] 生产接线、恒取 profile 的间隙；显式侧若喂 0，
     * 两侧量到不同行宽 ⇒ 分叉是**必然**的，而那证明的是「测试两侧没对齐」不是被测性质（假失败）。
     * 传函数而非值：该 getter 自己读开关，而开关在本函数体内才拨（见 [forEachBreakerVariant]）。
     */
    @Test
    fun `重轻两路断行区间逐项相同`() = forEachBreakerVariant(cjkLatinSpacingEm = { deviceProfile.cjkLatinSpacingEmApplied }) { breaker, v ->
        // 轻路径（`tempShape`）走的是**生产接线**，只有夹具拨了开关它才跟着换变体；
        // 只把这里的 `breaker` 换成自建而轻路径仍是 Skia，分叉是必然的，
        // 但那证明的是「测试两侧没对齐」而不是被测性质（假失败）。
        val layouter = BoxChapterLayouter()
        for (p in listOf(deviceProfile, otherProfile)) {
            // 命中窗口宽 = 2em 缩进；扫这一段即可覆盖轻/重两路可能分叉的全部位置。
            for (w in (baseContentW(p) - 120)..(baseContentW(p) + 8)) {
                val root = HtmlTreeConverter().convert(realHtml)!!
                val eng = styleEngine(p)
                val styles = eng.compute(root)
                val classify = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
                val heavy = BoxLayouter(p.bodyPx, breaker).layoutBoxes(root, w, styles, classify)
                val heavyRanges = leafRanges(heavy.boxes)

                val prep = layouter.prepareLight(
                    HtmlTreeConverter().convert(realHtml)!!, CssBundle(listOf(authorCss)),
                    p, w, ChapterStructureCache(), 2400,
                )
                val lightRanges = (0 until prep.totalBlocks).map { i ->
                    val s = layouter.tempShape(null, prep, i, p)
                    (0 until s.lineCount).map { s.lineStart(it) until s.lineEnd(it) }
                }

                assertEquals("[$v] bodyPx=${p.bodyPx} 版心=$w 重轻两路断行区间分叉", heavyRanges, lightRanges)
            }
        }
    }

    private fun leafRanges(boxes: List<LayoutBox>): List<List<IntRange>> {
        val out = ArrayList<List<IntRange>>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) if (b.isContainer) walk(b.childBoxes) else out.add(b.ranges)
        }
        walk(boxes)
        return out
    }
}
