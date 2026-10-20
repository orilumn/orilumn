package orilumn.reader.engine.skia

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.ChapterStructureCache
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderUiSheet
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.css.uaSheetFromProfile
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.FirstLetterOf
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ancestorsOf
import orilumn.reader.engine.laying.lineHeightPx
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `::first-letter` 端到端（首字放大）：**量画同源 + 重轻等值 + 老书零开销**。
 *
 * ## 浏览器语义（CSS 2.1 §5.12.2）
 *
 * `::first-letter` 只作用于首行的首字（及其前的引号/开括号），伪元素自己声明的
 * `font-size`/`color` 按**原发元素**的字号解（2em = 2 × 段落字号）；段落其余文字与整段
 * 计算样式一概不动。此前引擎把这类规则整段套用（首字下沉示例整段放大），或整条不匹配。
 *
 * ## 锁什么
 *
 * 1. **整段不动**：段落自身的计算字号仍是基准字号（伪元素只改首字段）；
 * 2. **首字 run**：字体 run 恰好一条、区间 `[0,1)`、字号 2em —— 段落其余不再产生 run
 *    （face 与基底同 ⇒ 零 run），叶文本与不带该规则时逐字相同；
 * 3. **行高撑起**：载首字的那一行行框按 2em 行高算（大字不压上下行），且
 *    **重路径（盒流行高）与轻路径（tempShape 塑形行高）逐项相同**（量画/双路同源）；
 * 4. **绘制投影**：[DrawLineBuilder] 投影出的首行 `fontRuns` 带同一条 2em run ——
 *    `LineWindowDrawer` 按它换字号，缺了它像素侧首字会画成基准字号；
 * 5. **老书零开销**：章节没有 `::first-letter` 规则 ⇒ [StyleComputer.hasFirstLetterRules] 关、
 *    查找恒 null、run 为零、行高与基线逐值不变。
 */
class FirstLetterEndToEndTest {

    private val contentW = 240
    /** 设备密度：CSS px → 物理 px（`body{font-size:20px}` 在 2x 屏上是 40 物理 px）。 */
    private val density = 2f
    private val fs = 20f * density
    private val ratio = 1.5f

    private val bodyCss = "body { font-size: 20px; line-height: 1.5; margin: 0; font-family: STSong, serif }"

    private val withRule = bodyCss + "\n.dc::first-letter { font-size: 2em }"
    private val withoutRule = bodyCss + "\n.dc { }"

    private fun html() = """
        <html><body>
        <p class="dc">Test drop cap paragraph with enough words that it wraps onto more than one line here.</p>
        </body></html>
    """.trimIndent()

    private fun profile() = TypographicProfile.build(
        ReaderSettings.DEFAULT.copy(fontSize = 20, lineSpacing = 1.5, firstLineIndent = 0.0),
        density,
    )

    private fun styleEngine(css: String): StyleComputer {
        val p = profile()
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

    /** 与生产同式（[BoxChapterLayouter] 的章节级门控＋懒解）：无规则即 null。 */
    private fun flOf(eng: StyleComputer, styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>): FirstLetterOf? {
        if (!eng.hasFirstLetterRules()) return null
        return { el -> eng.firstLetterStyle(el, ancestorsOf(el), styles.getValue(el)) }
    }

    private class Heavy(
        val eng: StyleComputer,
        val styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        val classify: orilumn.reader.engine.laying.BlockClassify,
        val leaves: List<LayoutBox>,
        val fl: FirstLetterOf?,
        val result: orilumn.reader.engine.laying.BoxLayoutResult,
    )

    private fun heavy(css: String): Heavy {
        val root = HtmlTreeConverter().convert(html())!!
        val eng = styleEngine(css)
        val styles = eng.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
        val fl = flOf(eng, styles)
        val result = BoxLayouter(profile().bodyPx, variantBreaker())
            .layoutBoxes(root, contentW, styles, classify, firstLetterOf = fl)
        val leaves = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) if (b.isContainer) walk(b.childBoxes) else leaves.add(b)
        }
        walk(result.boxes)
        return Heavy(eng, styles, classify, leaves, fl, result)
    }

    private fun lightHeights(css: String): List<List<Int>> {
        val layouter = BoxChapterLayouter()
        val prep = layouter.prepareLight(
            HtmlTreeConverter().convert(html())!!, CssBundle(listOf(css)),
            profile(), contentW, ChapterStructureCache(), 2400,
        )
        return (0 until prep.totalBlocks).map { i ->
            val s = layouter.tempShape(null, prep, i, profile())
            (0 until s.lineCount).map { s.shapeLineBottom(it) - s.shapeLineTop(it) }
        }
    }

    @Test
    fun `整段不动只首字放大且文本逐字不变`() {
        val h = heavy(withRule)
        val leaf = h.leaves.single()
        val p = leaf.el!!
        assertEquals("整段计算字号不变（伪元素只改首字段）", fs, h.styles.getValue(p).fontSizePx, 1e-4f)
        assertTrue("首字必须真的更大", h.fl != null)

        val runs = NormalFlowLayout.leafFontRuns(
            leaf.el, h.styles, h.classify, HIDDEN_NONE, orilumn.reader.engine.laying.EmptyGen, firstLetterOf = h.fl,
        )
        assertEquals("段落其余同 face 同字号 ⇒ 只有首字一个 run", 1, runs.size)
        assertEquals(0, runs[0].start)
        assertEquals(1, runs[0].endExclusive)
        assertEquals("2em × 20px（含密度）", 2f * fs, runs[0].fontSizePx, 1e-4f)
        assertEquals("首字就是文本第一个字符", 'T', leafText(h).first())

        // 对照：同段同版心，没有 ::first-letter 时零 run、文本同长。
        val base = heavy(withoutRule)
        assertNull("无伪元素规则 ⇒ 门控关", base.fl)
        assertTrue(
            "无规则时零 run",
            NormalFlowLayout.leafFontRuns(
                base.leaves.single().el, base.styles, base.classify, HIDDEN_NONE,
                orilumn.reader.engine.laying.EmptyGen,
            ).isEmpty(),
        )
        assertEquals(
            "叶文本与不带该规则逐字相同（分段只多一个边界，不改字符流）",
            leafText(base),
            leafText(h),
        )
    }

    private fun leafText(h: Heavy): String =
        NormalFlowLayout.leafText(h.leaves.single().el, h.styles, h.classify, HIDDEN_NONE, orilumn.reader.engine.laying.EmptyGen)

    @Test
    fun `首行行高按 2em 撑起且重轻两路逐项相同`() {
        val h = heavy(withRule)
        val leaf = h.leaves.single()
        assertTrue("首字放大必须真的改变行框（否则本锁是空的）", leaf.lineHeights.isNotEmpty())
        assertEquals(
            "载 2em 首字的首行行框按 2em 行高算",
            lineHeightPx(2f * fs, ratio),
            leaf.lineHeights[0],
        )
        // 对照：没有该规则时首行是基准行框 —— 否则「撑起」与规则无关。
        val base = heavy(withoutRule)
        assertEquals("无规则时首行仍是基准行框", lineHeightPx(fs, ratio), base.leaves.single().lineHeights[0])

        // 重轻等值：轻路径（生产 prepareLight/tempShape）逐行行高与重路径一致。
        val light = lightHeights(withRule)
        assertEquals(
            "重轻两路行高（含首字撑高）逐项相同",
            listOf(leaf.lineHeights),
            light.filter { it.isNotEmpty() },
        )
        assertTrue("前提：本段必须断成多行（实际 ${leaf.lineHeights.size} 行）", leaf.lineHeights.size >= 2)
    }

    @Test
    fun `绘制投影带同一条 2em run`() {
        val h = heavy(withRule)
        val lines = DrawLineBuilder.build(
            h.result, h.styles, h.classify, HIDDEN_NONE, profile().letterSpacingEm,
            firstLetterOf = h.fl,
        )
        val first = lines.toSortedMap().values.first()
        assertEquals("首行投影必须带首字的 2em run（LineWindowDrawer 换字号的依据）", 1, first.fontRuns.size)
        assertEquals(0, first.fontRuns[0].start)
        assertEquals(1, first.fontRuns[0].endExclusive)
        assertEquals(2f * fs, first.fontRuns[0].fontSizePx, 1e-4f)

        // 对照：没有该规则 ⇒ 投影零 run（老像素逐字节一致）。
        val base = heavy(withoutRule)
        val baseLines = DrawLineBuilder.build(base.result, base.styles, base.classify, HIDDEN_NONE, profile().letterSpacingEm)
        assertTrue("无规则时投影零 run", baseLines.values.all { it.fontRuns.isEmpty() })
    }

    @Test
    fun `伪元素查找只在命中时问叶根`() {
        val h = heavy(withRule)
        assertTrue("本章确有 ::first-letter 规则", h.eng.hasFirstLetterRules())
        val p = h.leaves.single().el!!
        val style = h.eng.firstLetterStyle(p, ancestorsOf(p), h.styles.getValue(p))
        assertEquals("2em 按原发元素字号解", 2f * fs, style!!.fontSizePx, 1e-4f)
        assertEquals("未声明的颜色等属性照常继承", h.styles.getValue(p).colorHex, style.colorHex)
    }
}
