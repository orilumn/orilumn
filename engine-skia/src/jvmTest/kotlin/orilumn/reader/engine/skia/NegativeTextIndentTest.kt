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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * E8：CSS `text-indent` 的**有符号**语义（悬挂缩进）。
 *
 * ## 浏览器语义（CSS 2.1 §16.1）
 *
 * `text-indent` 是**有符号长度**：负值让首行**左移** `|indent|`，首行**可用宽**相应加宽
 * `|indent|`（`headPx = 版心 − indent` 本身就是这条式子）；其余行照旧按版心。诗歌、词典、
 * 悬挂缩进列表（`li`/`dd`/`blockquote`）都靠它。
 *
 * ## 修前
 *
 * 引擎在 **10 个消费点**一律 `coerceAtLeast(0f)` 把负值钳成 0：断行侧的首行可用宽没加宽、
 * 绘制侧的首行 x 也没左移 —— 悬挂缩进被**静默吃掉**（书里写了等于没写）。
 *
 * ## 本类锁的不变量
 *
 * 1. **加宽生效**：`nat − |indent| ≤ 版心 < nat` 的整个窗口内，负缩进必须让整段**单行**
 *    （缩进为 0 时同版心必然 ≥ 2 行）—— 这条在**两个断行器变体**上都必须成立；
 * 2. **量画同值**：绘制侧（[LineAligner]）首行 `x0` == 缩进值（负值即左移），
 *    且右缘仍在版心内（`NoLineExceedsContentWidthTest` 的硬约束不因负缩进破）；
 * 3. **重轻等价**：同一段 HTML、同一版心，重路径（[BoxLayouter] + [DrawLineBuilder]）与
 *    轻路径（[BoxChapterLayouter.tempShape]）的断行区间逐项相同，且投影出的
 *    [DrawLine.firstLineIndentPx] 是**同一个负值**；
 * 4. **分层照旧**：读者**配了**首行缩进（UI 层 tier 44，值 > 0）才压过书的 `p` 缩进；读数 0 =
 *    读者未配 ⇒ UI 层一条 `text-indent` 都不发，书的缩进（含负值）原样生效。负缩进的可见范围也
 *    因此扩大：`p` 之外（`blockquote`/`dd`/`li` 等）本来就不在 UI 层射程内。
 */
class NegativeTextIndentTest {

    private val fs = 44.4f
    private val ratio = 1.5f

    /** 真机字体栈（Skia 的 `TextIndent` 行为与字体相关，见 [FirstLineIndentSingleLineOverflowTest]）。 */
    private val families = listOf("STSong", "serif")

    /** 亚像素余量（同 [SkiaParagraphBreakerTest] 既有口径）。 */
    private val slack = 1f

    /** `text-indent: 2em` 的绝对值；负号即悬挂缩进。 */
    private val indent = 2f * fs

    /** 26 字：`nat` 略大于一个 2em 缩进（同 [FirstLineIndentSingleLineOverflowTest]）。 */
    private val cjk = "都用到这么厉害的科技了，事情肯定不会如字面上写的那样简单。"

    /** 整段「不折行」自然宽（绘制侧同一整形器量出，与断行侧同源）。 */
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

    // ---- 1. 断行侧：负缩进加宽首行可用宽（两个变体） ----

    @Test
    fun `悬挂缩进让首行多容纳一个字距`() = forEachBreakerVariant { breaker, v ->
        val nat = naturalWidth(cjk)
        // 窗口 = 「缩进为 0 时放不下（≥2 行）、负缩进后放得下（1 行）」的那段版心：`nat − |indent| < 版心 < nat`。
        // 两端各收 2px，避开「恰好等于」的浮点边界。
        val lo = ceil(nat - indent).toInt() + 2
        val hi = ceil(nat).toInt() - 2
        assertTrue("[$v] 窗口必须非空（nat=$nat indent=$indent）", hi > lo)
        var checked = 0
        for (w in lo..hi) {
            val hang = breaker.breakLines(cjk, fs, ratio, w, TextAlign.LEFT, "p", families, 400, false, false, -indent)
            assertEquals("[$v] 版心=$w 断行区间必须连续覆盖全文", cjk, hang.joinToString("") { cjk.substring(it.range) })
            for (l in hang) assertEquals("[$v] 行高必须是统一行框单一来源", lineHeightPx(fs, ratio), l.heightPx)
            assertEquals("[$v] 版心=$w 负缩进必须让整段单行（首行可用宽 = 版心 + 2em）", 1, hang.size)
            val zero = breaker.breakLines(cjk, fs, ratio, w, TextAlign.LEFT, "p", families, 400, false, false, 0f)
            assertTrue(
                "[$v] 版心=$w 是对照窗口：无缩进时必须放不下（否则本锁是空的）",
                zero.size >= 2,
            )
            checked++
        }
        assertTrue("[$v] 窗口至少要扫过 2 个像素宽（indent=$indent），实际 $checked", checked >= 2)
    }

    // ---- 2. 绘制侧：首行 x0 == 缩进（负值左移），右缘不破版心 ----

    @Test
    fun `绘制侧首行按负缩进左移且右缘仍在版心内`() {
        val w = ceil(naturalWidth(cjk) - indent / 2f).toInt().coerceAtLeast(1)
        val aligner = LineAligner()
        val hang = aligner.align(
            cjk, 0 until cjk.length, fs, w.toFloat(), 0f, "p", families, 400, false, false,
            align = TextAlign.LEFT, firstLineIndentPx = -indent, isLastLine = true,
        )
        // 量画同值：断行侧加宽了 |indent|，绘制侧就必须从 −|indent| 起墨。
        assertEquals("首行左缘 = 缩进值（负值左移）", -indent, hang.xs[0], 0.01f)
        assertTrue(
            "悬挂缩进不得把右缘顶出版心：右缘=${hang.visibleRight} 版心=$w",
            hang.visibleRight <= w + slack,
        )
        // 对照：同版心改成 0 缩进，首行起点回到 0（证明上面那个负值确实来自缩进）。
        val zero = aligner.align(
            cjk, 0 until cjk.length, fs, w.toFloat(), 0f, "p", families, 400, false, false,
            align = TextAlign.LEFT, firstLineIndentPx = 0f, isLastLine = true,
        )
        assertEquals(0f, zero.xs[0], 0.01f)
    }

    @Test
    fun `居中悬挂缩进不右溢`() {
        // 首行盒 = [−|indent|, 版心]，内容居中要落在盒内；旧实现的 `coerceAtLeast(0f)` 会把居中点右推半个缩进。
        val w = ceil(naturalWidth(cjk) - indent / 2f).toInt().coerceAtLeast(1)
        val p = LineAligner().align(
            cjk, 0 until cjk.length, fs, w.toFloat(), 0f, "p", families, 400, false, false,
            align = TextAlign.CENTER, firstLineIndentPx = -indent, isLastLine = true,
        )
        assertTrue("居中悬挂缩进不得右溢：右缘=${p.visibleRight} 版心=$w", p.visibleRight <= w + slack)
        assertTrue("居中首行不得跑到首行盒左缘之左：x0=${p.xs[0]}", p.xs[0] >= -indent - 0.01f)
    }

    // ---- 3. 端到端：重轻两路同值 + 绘制投影 ----

    /** 默认档：原书样式（主题层不加载）、首行缩进 0 ⇒ 书的 `text-indent` 只受 UI 层 `p` 规则影响。 */
    private fun profile(readerIndentEm: Float = 0f) = TypographicProfile.build(
        ReaderSettings.DEFAULT.copy(firstLineIndent = readerIndentEm.toDouble()),
        2f,
    )

    /** 悬挂缩进写在 `blockquote` 上（读者档位默认未配首行缩进 ⇒ UI 层不发声明，书的负值原样到达）。 */
    private val authorCss = "blockquote { margin: 0; text-indent: -2em }"

    /** 对照：同版心、同结构，缩进为 0。 */
    private val zeroCss = "blockquote { margin: 0; text-indent: 0 }"

    private val markupText = "床前明月光，疑是地上霜。举头望明月，低头思故乡。"

    private fun html() = "<html><body><blockquote>$markupText</blockquote></body></html>"

    private fun styleEngine(css: String, readerIndentEm: Float = 0f): StyleComputer {
        val p = profile(readerIndentEm)
        return StyleComputer(
            p.bodyPx,
            uaSheetFromProfile(p),
            listOf(LightCssParser().parse(css)),
            themeSheetFromProfile(p),
            null,
            ReaderUiSheet.build(p),
            paragraphScale = p.paragraphSpacingScale, gapScale = p.paragraphGapScale, pxScale = p.density,
        )
    }

    private fun heavyLeaves(css: String, w: Int): List<LayoutBox> {
        val root = HtmlTreeConverter().convert(html())!!
        val eng = styleEngine(css)
        val styles = eng.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
        val result = BoxLayouter(profile().bodyPx, variantBreaker()).layoutBoxes(root, w, styles, classify)
        val out = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) if (b.isContainer) walk(b.childBoxes) else out.add(b)
        }
        walk(result.boxes)
        return out
    }

    @Test
    fun `悬挂缩进端到端：重轻两路同值且绘制侧同缩进`() = forEachBreakerVariant { _, v ->
        val fsPx = profile().bodyPx
        val indentPx = 2f * fsPx
        // 版心取「整段恰好放不下」：`nat − |indent| < 版心 < nat`。
        val w = ceil(naturalWidth(markupText, fsPx) - indentPx / 2f).toInt()
        val leaves = heavyLeaves(authorCss, w)
        assertEquals("[$v] 结构：一个块叶", 1, leaves.size)
        val leaf = leaves.single()
        assertEquals("[$v] 悬挂缩进必须让整段单行", 1, leaf.ranges.size)
        assertEquals("[$v] 单行覆盖全文", markupText.length, leaf.ranges[0].count())
        // 对照：同版心、缩进 0 时必须折行 —— 否则「单行」不是缩进带来的。
        val zeroLeaves = heavyLeaves(zeroCss, w)
        assertTrue(
            "[$v] 对照：无缩进时同版心必须折行（实际 ${zeroLeaves.single().ranges.size} 行）",
            zeroLeaves.single().ranges.size >= 2,
        )

        // 绘制投影：这个叶的首行必须带上**负**缩进，且落墨右缘不出版心。
        val root = HtmlTreeConverter().convert(html())!!
        val eng = styleEngine(authorCss)
        val styles = eng.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
        val result = BoxLayouter(fsPx, variantBreaker()).layoutBoxes(root, w, styles, classify)
        val lines = DrawLineBuilder.build(result, styles, classify, HIDDEN_NONE, profile().letterSpacingEm)
        // 投影表按「章内行号」为键、每叶每行一条；本段单行 ⇒ 取唯一那条（按键排序口径稳定）。
        val first = lines.toSortedMap().values.first()
        assertEquals("[$v] 绘制侧首行缩进必须是同一个负值（量画同值）", -indentPx, first.firstLineIndentPx, 0.01f)
        val placement = LineAligner().align(
            first.text, first.range, first.fontSizePx, first.lineWidthPx.toFloat(), first.letterSpacingEm,
            "blockquote", first.families, first.weight, first.italic, first.monospace,
            fontRuns = first.fontRuns, align = first.alignment, firstLineIndentPx = first.firstLineIndentPx,
            isLastLine = lines.size == 1, hyphenAtEnd = first.hyphenAtEnd,
        )
        assertEquals("[$v] 绘制侧首行左缘 = 投影出的缩进", -indentPx, placement.xs[0], 0.01f)
        assertTrue(
            "[$v] 悬挂缩进右缘越出版心：${placement.visibleRight} > ${first.lineWidthPx}",
            placement.visibleRight <= first.lineWidthPx + slack,
        )

        // 轻路径（`tempShape`，生产接线）：断行区间必须与重路径逐项相同。
        val layouter = BoxChapterLayouter()
        val prep = layouter.prepareLight(
            HtmlTreeConverter().convert(html())!!, CssBundle(listOf(authorCss)),
            profile(), w, ChapterStructureCache(), 2400,
        )
        val lightRanges = (0 until prep.totalBlocks).map { i ->
            val s = layouter.tempShape(null, prep, i, profile())
            (0 until s.lineCount).map { s.lineStart(it) until s.lineEnd(it) }
        }
        assertEquals("[$v] 重轻两路断行区间分叉", leaves.map { it.ranges }, lightRanges)

        // 轻路径的**绘制侧**投影（临时页行窗）也必须带同一个负缩进：断行同值但落墨不同值 ⇒ 文字压回缩进里。
        val fwd = layouter.shapeTempPageForward(prep, profile(), w, 2400, 0, cache = null, startLine = 0)
            ?: error("[$v] 临时页未产出")
        val drawLines = (fwd.page.layout as orilumn.reader.engine.skia.LayoutReadback)
            .skiaLineWindow()!!.toSortedMap().values
        assertEquals("[$v] 轻路径行窗必须单行", 1, drawLines.size)
        assertEquals(
            "[$v] 轻路径绘制侧首行缩进与重路径不同值（量画失配）",
            -indentPx, drawLines.first().firstLineIndentPx, 0.01f,
        )
    }

    // ---- 4. 分层照旧：读者**配了**首行缩进才压过书的 `p` ----

    @Test
    fun `读者配了首行缩进才压过书的 p 缩进（未配则书原样）`() {
        val css = "p { text-indent: -2em } blockquote { text-indent: -2em }"
        val root = HtmlTreeConverter().convert(
            "<html><body><p>正文</p><blockquote>引文</blockquote></body></html>",
        )!!
        fun nth(map: Map<orilumn.reader.engine.html.MarkupElement, orilumn.reader.engine.css.ComputedStyle>, tag: String): orilumn.reader.engine.css.ComputedStyle {
            val acc = ArrayList<orilumn.reader.engine.html.MarkupElement>()
            fun walk(n: orilumn.reader.engine.html.MarkupElement) {
                if (n.tag == tag) acc.add(n)
                n.children.forEach(::walk)
            }
            walk(root)
            return map[acc[0]]!!
        }

        // 读者**未配**（0 = 中性默认）⇒ UI 层一条 `text-indent` 都不发 ⇒ 书的负缩进原样到达 `p`
        // （悬挂缩进不再被裸 `p{text-indent:0em}` 静默吃掉）。
        val unset = styleEngine(css, readerIndentEm = 0f).compute(root)
        assertEquals(-2f * nth(unset, "p").fontSizePx, nth(unset, "p").textIndentPx, 0.01f)

        // 读者配了 2em ⇒ UI 层（tier 44）压过书的 `p`；`blockquote` 不在 UI 层射程内 ⇒ 书的负值原样
        // （E8 的可见范围：UI 层只给 `p` 发缩进）。
        val set = styleEngine(css, readerIndentEm = 2f).compute(root)
        assertEquals(2f * nth(set, "p").fontSizePx, nth(set, "p").textIndentPx, 0.01f)
        assertEquals(-2f * nth(set, "blockquote").fontSizePx, nth(set, "blockquote").textIndentPx, 0.01f)
    }
}
