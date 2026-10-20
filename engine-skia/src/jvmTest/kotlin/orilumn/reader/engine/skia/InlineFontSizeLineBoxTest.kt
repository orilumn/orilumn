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
import orilumn.reader.engine.laying.BoxLayoutResult
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.lineHeightPx
import orilumn.reader.engine.text.TypographicProfile
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E9：行内 `font-size` 撑高行框（CSS 2.1 §10.8），且**基线同源下移**（§10.8.1）——否则
 * 行框撑了、基线没动，大字顶部照样越出行框压到上一行，等于没修。
 *
 * ## 锁什么
 *
 * 1. **行高**：带 2em 行内 span 的那一行，行框高必须按该 span 的字号算（`fontSize × 行高比例`），
 *    不含它的行逐值不变 —— 与行内图 / 叠排注音同一条 §10.8 规则；
 * 2. **重轻同值**：同一段 HTML、同一版心，重路径（盒流 [LayoutBox.lineHeights]）与轻路径
 *    （塑形 `shapeLineTop/Bottom`）逐行行高相同（量画同源）；
 * 3. **落墨不越框**：真栅格化后，行内大字的墨迹必须落在**本行行框**内（上一段与本段之间的
 *    段间距条带上不许有墨）—— 这条同时锁住基线位移，只撑高不改基线时必红；
 * 4. **不误伤**：同字号的行内 face 段（`<code>`/加粗）与小字号（0.5em）不抬行高。
 *
 * 反空跑门：前提断言「行内 run 字号真的更大」（`bigPx > line.fontSizePx`）、
 * 「段间距条带真的够宽」（否则越框也无处可显）。
 */
class InlineFontSizeLineBoxTest {

    private val contentW = 240

    /**
     * 正文 20px / 行高 1.5 ⇒ 基准行框 30px；`span.big` 2em ⇒ 40px ⇒ 行框 60px。
     *
     * `p` 纵向边距交给主题层（tier 42，作者层的 `p{margin:0}` 压不过它，见 AGENTS.md 层叠优先级），
     * 于是相邻两段之间天然留出段间距条带 —— 第 3 条锁正是拿它当「上一行之外」的可见区。
     */
    private val authorCss = """
        body { font-size: 20px; line-height: 1.5; margin: 0; font-family: STSong, serif }
        span.big { font-size: 2em }
        span.tiny { font-size: 0.5em }
        span.face { font-weight: 700 }
    """.trimIndent()

    private fun profile(fs: Int = 20) = TypographicProfile.build(
        ReaderSettings.DEFAULT.copy(fontSize = fs, lineSpacing = 1.5, firstLineIndent = 0.0),
        2f,
    )

    private fun styleEngine(p: TypographicProfile) = StyleComputer(
        p.bodyPx,
        uaSheetFromProfile(p),
        listOf(LightCssParser().parse(authorCss)),
        themeSheetFromProfile(p),
        null,
        ReaderUiSheet.build(p),
        paragraphScale = p.paragraphSpacingScale, gapScale = p.paragraphGapScale, pxScale = p.density,
    )

    private class Heavy(
        val result: BoxLayoutResult,
        val leaves: List<LayoutBox>,
        val styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        val classify: orilumn.reader.engine.laying.BlockClassify,
    )

    private fun heavy(html: String, p: TypographicProfile = profile()): Heavy {
        val root: MarkupElement = HtmlTreeConverter().convert(html)!!
        val eng = styleEngine(p)
        val styles = eng.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, eng.hasDisplayDeclaration())
        val result = BoxLayouter(p.bodyPx, variantBreaker()).layoutBoxes(root, contentW, styles, classify)
        val leaves = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) if (b.isContainer) walk(b.childBoxes) else leaves.add(b)
        }
        walk(result.boxes)
        return Heavy(result, leaves, styles, classify)
    }

    private fun lightHeights(html: String, p: TypographicProfile = profile()): List<List<Int>> {
        val layouter = BoxChapterLayouter()
        val prep = layouter.prepareLight(
            HtmlTreeConverter().convert(html)!!, CssBundle(listOf(authorCss)),
            p, contentW, ChapterStructureCache(), 2400,
        )
        return (0 until prep.totalBlocks).map { i ->
            val s = layouter.tempShape(null, prep, i, p)
            (0 until s.lineCount).map { s.shapeLineBottom(it) - s.shapeLineTop(it) }
        }
    }

    /** 带行内大字的正文：段 1 供第 3 条锁当「上一行」，段 2 的 span 落在第 1 行。 */
    private val html = """
        <html><body>
        <p>第一段甲第一段乙第一段丙。</p>
        <p>前缀甲 <span class="big">H</span> 后缀甲后缀乙后缀丙后缀丁后缀戊后缀己后缀庚后缀辛</p>
        </body></html>
    """.trimIndent()

    @Test
    fun `行内2em把本行撑高而邻行不变`() {
        val h = heavy(html)
        var bigLines = 0
        var bigPx = 0f
        for (leaf in h.leaves) {
            val heights = leaf.lineHeights
            assertEquals("版心=$contentW 每叶行高数与断行行数必须等长", leaf.ranges.size, heights.size)
            val runs = NormalFlowLayout.leafFontRuns(leaf.el, h.styles, h.classify)
            val leafBig = runs.maxOfOrNull { it.fontPxOr(leaf.style.fontSizePx) } ?: leaf.style.fontSizePx
            if (leafBig <= leaf.style.fontSizePx) continue
            bigPx = leafBig
            val base = lineHeightPx(leaf.style.fontSizePx, leaf.style.lineHeightRatio)
            for ((k, hp) in heights.withIndex()) {
                val lo = leaf.ranges[k].first
                val hiEx = leaf.ranges[k].last + 1
                val onLine = runs.any { it.endExclusive > lo && it.start < hiEx && it.fontPxOr(leaf.style.fontSizePx) > leaf.style.fontSizePx }
                if (onLine) {
                    bigLines++
                    assertEquals(
                        "第 $k 行载行内大字（${leafBig}px）⇒ 行框必须按它撑高",
                        lineHeightPx(leafBig, leaf.style.lineHeightRatio), hp,
                    )
                } else {
                    assertEquals("第 $k 行没有行内大字 ⇒ 行框逐值不变", base, hp)
                }
            }
        }
        assertTrue("前提：必须有叶真的带行内大字，否则本用例空跑", bigLines > 0)
        assertTrue("前提：行内 run 字号必须真的更大（实测 $bigPx）", bigPx > 20f)
    }

    @Test
    fun `重轻两路行高逐项相同`() {
        val heavyHeights = heavy(html).leaves.map { it.lineHeights }
        val light = lightHeights(html)
        // 轻路径多出容器自身的空块（无行）时按非空行高表对齐（本段 HTML 无此形状，防御性裁剪）。
        assertEquals(
            "重轻两路行高（含行内大字撑高）必须逐项相同",
            heavyHeights.filter { it.isNotEmpty() },
            light.filter { it.isNotEmpty() },
        )
        assertTrue("前提：重路径必须真的有两行以上的叶，否则比较无意义", heavyHeights.any { it.size > 1 })
    }

    @Test
    fun `同行号与小字号行内face不抬行高`() {
        val same = """
            <html><body><p>甲乙丙 <span class="face">丁戊</span> 己庚辛 <span class="tiny">子丑</span> 寅卯辰</p></body></html>
        """.trimIndent()
        for (leaf in heavy(same).leaves) {
            val base = lineHeightPx(leaf.style.fontSizePx, leaf.style.lineHeightRatio)
            for ((k, hp) in leaf.lineHeights.withIndex()) {
                assertEquals("同号加粗 / 0.5em 行内 face 不得改行高（第 $k 行）", base, hp)
            }
            assertTrue("前提：本段应被断成多行才有意义", leaf.ranges.isNotEmpty())
        }
    }

    @Test
    fun `绘制侧行内大字不出本行框`() {
        val h = heavy(html)
        val lines = DrawLineBuilder.build(h.result, h.styles, h.classify, HIDDEN_NONE, profile().letterSpacingEm)
        val all = lines.toSortedMap().values.toList()
        assertTrue("前提：必须有行窗行", all.size >= 2)

        // 载行内大字的那一行：行框高 > 基准行框 ⇒ 它撑高了；用它作为「不许越界」的那一行。
        // ⚠ 段表是**整叶**的（未按行切）：必须按本行区间取交集，否则同叶其它行也被算成「载大字」。
        val big = all.filter { dl ->
            dl.fontRuns.any { r ->
                r.endExclusive > dl.range.first && r.start < dl.range.last + 1 &&
                    r.fontPxOr(dl.fontSizePx) > dl.fontSizePx
            }
        }
        assertEquals("前提：只有一行载行内大字", 1, big.size)
        val bigLine = big.single()
        val baseH = lineHeightPx(bigLine.fontSizePx, bigLine.lineHeightRatio)
        assertTrue(
            "前提：该行行框必须真的撑高（${bigLine.yBottom - bigLine.yTop} vs 基准 $baseH）",
            bigLine.yBottom - bigLine.yTop > baseH,
        )
        // 它前面必须另有一行（越框的可见区在**上面**）。
        val prev = all.lastOrNull { it.yBottom <= bigLine.yTop }
        assertTrue("前提：大字行前必须另有行", prev != null)
        val gap = bigLine.yTop - prev!!.yBottom
        assertTrue("前提：上一行与本行之间必须有可判读的条带（实测 $gap）", gap >= 8)

        val height = all.maxOf { it.yBottom } + 40
        val bitmap = Bitmap()
        bitmap.allocN32Pixels(contentW.toInt() + 20, height, true)
        val canvas = Canvas(bitmap)
        canvas.clear(Color.WHITE)
        LineWindowDrawer().drawLines(canvas, 0f, all, null)

        val px = requireNotNull(bitmap.peekPixels())
        val bandXs = (0 until contentW).toList()
        var inkInGap = 0
        for (y in prev.yBottom until bigLine.yTop) {
            for (x in bandXs) if (px.getColor(x, y) != Color.WHITE) { inkInGap++; break }
        }
        assertEquals("行内大字越出本行框压到上一行（$gap px 条带内有 $inkInGap 行墨）", 0, inkInGap)

        var inkInBox = 0
        for (y in bigLine.yTop until bigLine.yBottom) {
            for (x in bandXs) if (px.getColor(x, y) != Color.WHITE) { inkInBox++; break }
        }
        assertTrue("前提：大字行框内必须真有墨，否则上面的「条带无墨」是空跑", inkInBox > 0)
        bitmap.close()
    }
}
