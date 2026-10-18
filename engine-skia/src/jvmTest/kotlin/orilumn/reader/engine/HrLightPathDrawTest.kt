package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E7 端到端：`<hr>` 是**零字符、只有边框**的块（无行叶）。轻路径（临时页 / 增量窗）的画盒由
 * 「叶 → 行区间」推位置 ⇒ 无锚行的叶整块被跳过，而正典（盒树）逐盒画得出 —— 同一章两路外观分叉
 * （翻到本章用临时表时不画分隔线，落盘后翻回来又出现）。本用例走真塑形 + 真临时页 + 真级联：
 * hr 的边框盒必须出现在页背景里，且相对页首行的位置与正典逐值相同。
 */
class HrLightPathDrawTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 1400
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    /** 不碰 `hr`：分隔线只能来自 UA 基线（书里写死 hr 样式才是另一回事）。 */
    private val css = CssBundle(
        listOf(
            """
            body { font-size: 16px; line-height: 1.5; margin: 0 }
            p { margin: 16px 0 }
            """.trimIndent(),
        ),
    )

    private fun chapter() = converter.convert(
        "<html><body><p>${"甲".repeat(30)}</p><hr/><p>${"乙".repeat(30)}</p></body></html>",
    )!!

    /**
     * hr 横线的**横向边**（页背景里的边框带）：横跨版心的上、下两条 1px 边框。
     * （左右两条 1px 竖端头不算 —— 它们窄。）
     */
    private fun hrBands(bgs: List<orilumn.reader.engine.skia.PageBackground>) =
        bgs.filter { it.border && it.right - it.left > contentW / 2 }.sortedBy { it.yTop }

    /** `pageBackgrounds` 在 [orilumn.reader.engine.skia.LayoutReadback] 上（`BookLayout` 不带绘制读回）。 */
    private fun backgroundsOf(
        layout: orilumn.reader.engine.paging.BookLayout,
        first: Int,
        last: Int,
    ): List<orilumn.reader.engine.skia.PageBackground> =
        (layout as orilumn.reader.engine.skia.LayoutReadback).pageBackgrounds(first, last)

    @Test
    fun `hr border band is drawn on the light path at the canonical offset`() {
        val markup = chapter()
        // 轻路径：整章一页（contentH 远大于两行）。
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null, startLine = 0)
            ?: error("临时页未产出")
        val tempLayout = fwd.page.layout
        val slices = fwd.page.slice
        val tempLines = slices.lastLineExclusive - slices.firstLine
        assertEquals("两段文本 + 中间一条分隔线 = 两行", 2, tempLines)

        val tempBands = hrBands(backgroundsOf(tempLayout, slices.firstLine, slices.lastLineExclusive))
        assertEquals("hr 边框盒缺失（轻路径把无行叶整块跳过了）", 2, tempBands.size)
        assertEquals(
            "hr 横线 = 上下两条 1px 边框",
            2,
            tempBands.last().yBottom - tempBands.first().yTop,
        )

        // 正典路径（盒树逐盒画）：同一章必须画出同一条横线，且相对页首行的位置逐值相同。
        val canon = layouter.fullLayout(layouter.prepare(markup, css, profile, contentW, contentH), profile, contentW, contentH)
        val canonSlice = canon.slices.single()
        val canonBands = hrBands(backgroundsOf(canon.layout, canonSlice.firstLine, canonSlice.lastLineExclusive))
        assertEquals("hr 边框盒缺失（正典路径）", 2, canonBands.size)
        assertEquals(
            "轻/正两路的 hr 相对页首行位置不一致",
            canonBands.first().yTop - canon.layout.getLineTop(canonSlice.firstLine),
            tempBands.first().yTop - tempLayout.getLineTop(slices.firstLine),
        )
        assertEquals(
            "轻/正两路的 hr 相对页底位置不一致",
            canonBands.last().yBottom - canon.layout.getLineTop(canonSlice.firstLine),
            tempBands.last().yBottom - tempLayout.getLineTop(slices.firstLine),
        )
    }

    @Test
    fun `hr band sits between the two paragraphs and never overlaps text`() {
        val markup = chapter()
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null, startLine = 0)!!
        val layout = fwd.page.layout
        val bands = hrBands(backgroundsOf(layout, 0, layout.lineCount))
        assertEquals(2, bands.size)
        val firstLineBottom = layout.getLineBottom(0)
        val secondLineTop = layout.getLineTop(1)
        assertTrue(
            "hr 应与上一行不重叠：band.top=${bands.first().yTop} 首行行底=$firstLineBottom",
            bands.first().yTop >= firstLineBottom,
        )
        assertTrue(
            "hr 应在下一行之上：band.bottom=${bands.last().yBottom} 次行行顶=$secondLineTop",
            bands.last().yBottom <= secondLineTop,
        )
    }
}
