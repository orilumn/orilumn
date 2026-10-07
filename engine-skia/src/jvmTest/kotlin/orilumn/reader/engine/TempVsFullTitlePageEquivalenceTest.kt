package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.max
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 问题 4「同参数复现」锁（`docs/调参与分页路径不一致-问题记录.md` 待验证清单⑤）：
 * 同一章、同一参数下，**FULL 路径**（`prepare` + `fullLayout`——其他章首次无磁盘表
 * 时走的整章全量排版）与 **TEMP 路径**（`prepareLight` + `shapeTempPageForward`——
 * 调参后当前章锚点会话，锚点=章首）排出的**章首页**必须切片一致、逐行相对几何
 * 一致——尤其是章首 h2 标题与首段正文的垂直间距（用户报告的「同一 CSS 规则两种
 * 渲染效果」的观测量）。
 *
 * ## 判据
 *
 * 1. 页切片（`PageSlice` charStart/charEnd/行区间）逐字段相等——页切分时机一致；
 * 2. 页内逐行 y 相对页首行相等——含 h2→正文间距（margin collapse 处理后两路的
 *    唯一间距来源 `NormalFlowLayout.consecutiveLeafAdvance`（轻）与 `emit`（重）
 *    镜像同式、同舍入口径）；
 * 3. 章首折叠顶 margin 的**绝对**分歧按现状钉住：重路径 `emit(first=true)` 消费
 *    整条首子链折叠 margin（章首行顶 = 折叠 margin），轻路径页首块顶 margin 并入
 *    页顶（章首行顶 = 0）。该绝对偏移**不可见**：渲染侧 `ReaderPageCanvas` 按
 *    页内最小 yTop 归一化（`shift = anchorY - contentTop`），分页容量自首行起量
 *    （`Paginator.fillWholeLines`），两路屏幕几何与页容量恒等。
 *
 * 历史根因（已修复于 `BookDocumentController.startAnchorStream`：TEMP 会话出生即
 * 丢弃 `unit.blockShapeCache`——形状缓存只按键索引、无参数维度，跨参数周期复用会
 * 供给旧行距/字号下的旧行高，而 margin 不在形状里即时更新，混合出「两种渲染效果」）：
 * 本锁在两条路径同参数同输入下运行，是该修复的非回归守卫。
 */
class TempVsFullTitlePageEquivalenceTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    /** 长正文逼出多页：页 0 = h2 + 首段前若干行（切点落在块内，行间切分两路同式）。 */
    private fun body(text: String) = text.repeat(30)

    private fun flatChapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <h2>第一章 启程</h2>
          <p>${body("路漫漫其修远兮，吾将上下而求索。")}</p>
          <p>${body("溯洄从之，道阻且长。")}</p>
          <p>${body("登高望远，天地一色。")}</p>
        </body></html>
        """.trimIndent(),
    )!!

    /** h2/正文包在无边框无内距容器里： exercised 轻路径 `consecutiveLeafAdvance`
     *  的祖先链折叠与重路径 `emit` 的首子链折叠。 */
    private fun nestedChapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <div class="chap">
            <h2>第一章 启程</h2>
            <p>${body("路漫漫其修远兮，吾将上下而求索。")}</p>
            <p>${body("溯洄从之，道阻且长。")}</p>
          </div>
        </body></html>
        """.trimIndent(),
    )!!

    @Test
    fun `flat chapter head page is identical on temp and full paths`() {
        assertHeadPageEquivalent(flatChapter())
    }

    @Test
    fun `nested chapter head page is identical on temp and full paths`() {
        assertHeadPageEquivalent(nestedChapter())
    }

    private fun assertHeadPageEquivalent(markup: MarkupElement) {
        val css = CssBundle(listOf(""))

        // ── FULL 路径（其他章首次无表）：heavy prepare + 整章全量排版 ──
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val full = layouter.fullLayout(heavy, profile, contentW, contentH)
        val fullLayout = full.layout
        val fullPage = full.slices[0]

        // ── TEMP 路径（当前章调参）：light prepare + 章首锚点前向页 ──
        val structure = ChapterStructureCache()
        val light = layouter.prepareLight(markup, css, profile, contentW, structure, contentH)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)
            ?: error("temp head page failed")
        val tempLayout = fwd.page.layout
        val tempPage = fwd.page.slice

        // 语料健全性：章首页确实含 h2 与正文首行之上（切点在块内），且全书不止一页。
        val pageLines = tempPage.lastLineExclusive - tempPage.firstLine
        assertTrue("page 0 must hold the h2 plus body lines (got $pageLines)", pageLines >= 2)
        assertTrue("chapter must paginate past page 0 (${full.slices.size})", full.slices.size >= 2)
        assertTrue("full layout must cover page 0's lines (${fullLayout.lineCount})", fullLayout.lineCount >= pageLines)

        // 1) 页切片逐字段一致：页切分时机与行区间两路同式。
        assertEquals("page-0 charStart", fullPage.charStart, tempPage.charStart)
        assertEquals("page-0 charEnd", fullPage.charEnd, tempPage.charEnd)
        assertEquals("page-0 firstLine", fullPage.firstLine, tempPage.firstLine)
        assertEquals("page-0 lastLineExclusive", fullPage.lastLineExclusive, tempPage.lastLineExclusive)

        // 2) 逐行相对几何一致（各行 y 相对页首行）——h2→正文间距含在内。
        val fullBase = fullLayout.getLineTop(0)
        val tempBase = tempLayout.getLineTop(0)
        for (i in 0 until pageLines) {
            assertEquals("line $i relative top", fullLayout.getLineTop(i) - fullBase, tempLayout.getLineTop(i) - tempBase)
            assertEquals("line $i relative bottom", fullLayout.getLineBottom(i) - fullBase, tempLayout.getLineBottom(i) - tempBase)
        }

        // 3) 章首 h2 → 首段正文的垂直间距：两路相等，且 = 折叠 margin（取大，非求和），
        //    一次舍入。line 0 = h2 唯一行，line 1 = 首段首行。
        val fullGap = fullLayout.getLineTop(1) - fullLayout.getLineBottom(0)
        val tempGap = tempLayout.getLineTop(1) - tempLayout.getLineBottom(0)
        assertEquals("h2→body vertical gap (temp vs full)", fullGap, tempGap)
        val h2Box = heavy.leaves.first { it.el?.tag == "h2" }
        val firstPBox = heavy.leaves.first { it.el?.tag == "p" }
        val h2Style = heavy.styleMap[h2Box.el] ?: error("h2 style missing")
        val pStyle = heavy.styleMap[firstPBox.el] ?: error("first p style missing")
        val collapsed = max(h2Style.margin.bottom, pStyle.margin.top)
        assertEquals("h2→body gap = collapsed margin (max, rounded once)", collapsed.roundToInt(), fullGap)

        // 4) 已知绝对分歧（渲染不可见，见类 KDoc）：重路径消费章首折叠顶 margin，
        //    轻路径页首块顶 margin 并入页顶。
        assertEquals("TEMP head line top = 0 (page-first-block top margin folds into page top)", 0, tempBase)
        assertEquals("FULL head line top = collapsed chapter-head top margin (emit consumes it)",
            h2Style.margin.top.roundToInt(), fullBase)
    }
}
