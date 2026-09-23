package orilumn.reader.engine.skia

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderUiSheet
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.usedReplacedSize
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.DrawKind
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Rust 简介 Ferris 表（00_3.xhtml）真书端到端：平板报“表图占位符 + 下框线超宽”，
 * 桌面正常。引擎全共享，本地用真书 CSS/结构跑一遍，定性三处修复在共享链路上：
 * 指定宽 90% + margin auto 居中（外盒几何）、表图 60px 定宽（HTML width 属性）、
 * 逐边边框（仅 table 下边 + th/td 上边，无竖框；下框线收进表宽）。
 */
class FerrisTableEndToEndTest {

    private val contentW = 1000

    private val html = """
        <html><body>
        <table>
        <thead><tr><th width="100px">Ferris</th><th>含义</th></tr></thead>
        <tbody>
        <tr><td><img src="../Images/0001_does_not_compile.png" width="60"/></td><td style="vertical-align: middle;">这段代码无法通过编译！</td></tr>
        <tr><td><img src="../Images/0002_panics.png" width="60"/></td><td style="vertical-align: middle;">这段代码会 Panic！</td></tr>
        <tr><td><img src="../Images/0003_not_desired_behavior.png" width="60"/></td><td style="vertical-align: middle;">这段代码的运行结果不符合预期。</td></tr>
        </tbody>
        </table>
        </body></html>
    """.trimIndent()

    /** 原书 stylesheet.css 表格子集（原文顺序：`*` 清零在前）。 */
    private val css = """
        * { margin: 0; padding: 0; border: 0; }
        table { width: 90%; margin: 1rem auto; text-align: center; font-size: 0.8rem; border-collapse: collapse; border-bottom: 1px solid #000; }
        th, td { border-top: 1px solid #000; text-align: left; padding: 0.3em 0.5em; }
    """.trimIndent()

    private data class Setup(
        val root: MarkupElement,
        val table: MarkupElement,
        val tableBox: LayoutBox,
        val styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        val profile: TypographicProfile,
        val rects: List<orilumn.reader.engine.laying.DrawRect>,
    )

    private fun setup(): Setup {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(useOriginalStyle = true))
        val root = HtmlTreeConverter().convert(html)!!
        val ui = ReaderUiSheet.build(profile)
        val engine = StyleComputer(
            profile.bodyPx, LightCssParser().parse(""), listOf(LightCssParser().parse(css)),
            null, null, ui, gapScale = profile.paragraphGapScale,
        )
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
        val breaker = SkiaParagraphBreaker(0f)
        val loader = orilumn.reader.engine.ImageBoundsReader { _, _ -> 1259 to 847 }
        val result = BoxLayouter(profile.bodyPx, breaker)
            .layoutBoxes(root, contentW, styles, classify, imageLoader = loader, chapterHref = "OEBPS/Text/00_3.xhtml")
        val table = findFirst(root) { it.tag == "table" }!!
        val tableBox = findBox(result.boxes) { it.el === table }!!
        val rects = BoxDrawer.draw(result.boxes)
        return Setup(root, table, tableBox, styles, profile, rects)
    }

    private fun findFirst(el: MarkupElement, pred: (MarkupElement) -> Boolean): MarkupElement? {
        if (pred(el)) return el
        for (c in el.children) {
            val hit = findFirst(c, pred)
            if (hit != null) return hit
        }
        return null
    }

    private fun findBox(boxes: List<LayoutBox>, pred: (LayoutBox) -> Boolean): LayoutBox? {
        for (b in boxes) {
            if (pred(b)) return b
            val hit = findBox(b.childBoxes, pred)
            if (hit != null) return hit
        }
        return null
    }

    @Test
    fun `specified width 90 percent with auto margins centers the table`() {
        val s = setup()
        // 外盒几何单源：表宽 = 90% 版心，左缘 = 5%（居中），不是全宽也不是内容收缩。
        assertEquals(contentW * 0.9f, s.tableBox.contentWidth.toFloat(), 2f)
        assertEquals(contentW * 0.05f, s.tableBox.contentLeft.toFloat(), 2f)
    }

    @Test
    fun `cell image honors html width attr`() {
        val s = setup()
        val img = findFirst(s.root) { it.tag == "img" }!!
        val st = s.styles[img]!!
        // HTML width="60" 经表示属性进级联（tier 15），与原书 width:90% 同机制。
        assertEquals(60f, st.widthPx ?: -1f, 0.001f)
        // 真实 intrinsic 1259x847 按 60 定宽等比 → 高约 40。
        val (w, h) = st.usedReplacedSize(1259, 847, 800)
        assertEquals(60, w)
        assertTrue("等比高约 40，实际 $h", abs(h - 40) <= 2)
    }

    @Test
    fun `per-edge borders only bottom band within table width`() {
        val s = setup()
        val borders = s.rects.filter { it.kind == DrawKind.BORDER }
        assertTrue("表至少有分隔线", borders.isNotEmpty())
        for (b in borders) {
            val h = b.bottom - b.top
            val w = b.right - b.left
            // 全是横带（高 1px 量级）；书里没声明任何竖边框。
            assertTrue("出现竖边框带（左=${b.left} 宽=$w），逐边口径要求只画声明边", h <= 2)
            // 横带左右收进表宽：左 >= 表左，右 <= 表右。
            assertTrue("边框左越界：${b.left} < ${s.tableBox.contentLeft}", b.left >= s.tableBox.contentLeft)
            assertTrue(
                "边框右越界：${b.right} > ${s.tableBox.contentLeft + s.tableBox.contentWidth}",
                b.right <= s.tableBox.contentLeft + s.tableBox.contentWidth,
            )
        }
        // 下框线存在：表底上一条与表同宽的横带。
        val bottom = s.tableBox.contentBottom
        assertTrue(
            "下框线缺失或不在表底",
            borders.any { it.bottom == bottom && it.left == s.tableBox.contentLeft &&
                it.right == s.tableBox.contentLeft + s.tableBox.contentWidth },
        )
    }
}
