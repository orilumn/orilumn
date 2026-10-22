package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E23-b：**容器指定高**（`.ovh{height:40px}`）在磁盘表与临时表里各自都要排对。
 *
 * 重路径 `emit` 的容器分支把内容盒高定为 `max(在流内容高, height)`（CSS 2.1 §10.6.3，与叶同一条
 * 「只多不少」口径：内容更高照排不裁），容器底边与后随内容的起点因此落在指定高上。轻路径的行流是
 * 局部位移累加（`consecutiveLeafAdvance` 只有边距与边），必须靠 `containerHeightLead` 补同一段差额
 * ——否则临时页/增量窗相对磁盘页少一段空白，分页在两种页之间横跳（正是 `docs/引擎欠账` E23-b 记录
 * 的那条）。
 *
 * **两张表各测各的**（AGENTS.md「磁盘表与临时表互斥」）：期望值全部来自 CSS（指定高 + 自身上下边、
 * 后随块的折叠 margin），每张表都对这个绝对值负责；不读另一张表的页切片、也不要求两面逐行相同。
 */
class FixedHeightContainerTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private val css = CssBundle(
        listOf(
            """
            body { margin: 0 }
            p { margin: 16px 0 }
            .ovh { height: 40px; padding: 4px; border: 1px solid #333; background: #f5f5f5 }
            .ovh p, .tall p { margin: 0 }
            .tall { height: 200px; padding: 4px; background: #eee }
            .minh { min-height: 120px; padding: 4px; border: 1px solid #333; background: #f5f5f5 }
            .minh-small { min-height: 10px; padding: 4px; border: 1px solid #333; background: #f5f5f5 }
            .minh p, .minh-small p { margin: 0 }
            .minh-gap { min-height: 120px; background: #f5f5f5 }
            .minh-gap p { margin: 0 0 16px }
            .minh-gap-small { min-height: 10px; background: #f5f5f5 }
            .minh-gap-small p { margin: 0 0 16px }
            """.trimIndent(),
        ),
    )

    private fun long(text: String) = text.repeat(12)

    private fun chapter(body: String): MarkupElement = converter.convert(
        "<html><body>$body</body></html>",
    )!!

    private fun backgroundsOf(layout: BookLayout, first: Int, last: Int) =
        (layout as orilumn.reader.engine.skia.LayoutReadback).pageBackgrounds(first, last)

    /** `pageBackgrounds` 里那块容器（按底色认）。 */
    private fun containerBand(bgs: List<PageBackground>): PageBackground? =
        bgs.firstOrNull { Integer.toHexString(it.argb) == "fff5f5f5" }

    /** 章首页的三种取法都归到这个形状，之后只按「这张表 + CSS 期望值」判。 */
    private class HeadPage(val layout: BookLayout, val slice: PageSlice, val band: PageBackground?)

    private fun headPageOf(full: Boolean, markup: MarkupElement): HeadPage {
        val layout: BookLayout
        val slice: PageSlice
        if (full) {
            val laid = layouter.fullLayout(
                layouter.prepare(markup, css, profile, contentW, contentH), profile, contentW, contentH,
            )
            layout = laid.layout
            slice = laid.slices[0]
        } else {
            val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
            val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)
                ?: error("临时页未产出")
            layout = fwd.page.layout
            slice = fwd.page.slice
        }
        return HeadPage(layout, slice, containerBand(backgroundsOf(layout, slice.firstLine, slice.lastLineExclusive)))
    }

    /** 容器底色带**之后**的第一行（= 后随块首行）到带底的距离。 */
    private fun gapAfterBand(page: HeadPage, band: PageBackground): Int {
        val s = page.slice
        for (i in s.firstLine until s.lastLineExclusive) {
            val top = page.layout.getLineTop(i)
            if (top >= band.yBottom) return top - band.yBottom
        }
        error("底色带之后没有行")
    }

    /**
     * 页首自身的义务：从章首起、至少两行、页高不超版心、容器盒高与后随间距等于 CSS 推导值。
     * 期望值都是绝对值（`null` 表示该用例另有断言），不与另一张表比较。
     */
    private fun assertHeadPageOwnFacts(
        page: HeadPage,
        label: String,
        expectedBandHeight: Int?,
        expectedGap: Int?,
    ) {
        val s = page.slice
        val lines = s.lastLineExclusive - s.firstLine
        assertEquals("$label：章首页必须从章首起", 0, s.charStart)
        assertTrue("$label：章首页至少两行（$lines）", lines >= 2)
        val span = page.layout.getPageFrameBottom(s.lastLineExclusive - 1) -
            page.layout.getPageFrameTop(s.firstLine)
        assertTrue("$label：章首页帧高 $span 超出 $contentH", span <= contentH)

        val band = page.band
        if (band == null) {
            if (expectedBandHeight != null || expectedGap != null) error("$label：缺容器底色盒")
            return
        }
        expectedBandHeight?.let {
            assertEquals("$label：容器盒高 = 指定高 + 自身上下边", it, band.yBottom - band.yTop)
        }
        expectedGap?.let {
            assertEquals("$label：容器底到后随块首行的距离", it, gapAfterBand(page, band))
        }
    }

    private fun assertHeadPage(markup: MarkupElement, label: String, bandHeight: Int?, gap: Int?) {
        assertHeadPageOwnFacts(headPageOf(full = true, markup), "$label/磁盘表", bandHeight, gap)
        assertHeadPageOwnFacts(headPageOf(full = false, markup), "$label/临时表", bandHeight, gap)
    }

    private fun headBandHeight(full: Boolean, markup: MarkupElement): Int {
        val page = headPageOf(full, markup)
        val band = page.band ?: error("缺容器底色盒")
        return band.yBottom - band.yTop
    }

    @Test
    fun `text after a fixed-height container sits at the CSS offset on each table`() {
        val markup = chapter(
            """
            <p>${long("路漫漫其修远兮，吾将上下而求索。")}</p>
            <div class="ovh"><p>短</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            <p>${long("登高望远，天地一色。")}</p>
            """.trimIndent(),
        )
        // 容器 40 内容 + 4+4 padding + 1+1 border = 50；后随 p 的 margin-top 16。
        assertHeadPage(markup, "指定高容器", bandHeight = 50, gap = 16)
    }

    @Test
    fun `nested fixed-height containers keep the CSS offset on each table`() {
        val markup = chapter(
            """
            <div class="ovh"><div class="tall"><p>短</p></div></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            <p>${long("登高望远，天地一色。")}</p>
            """.trimIndent(),
        )
        // 外层 40 高装不下内层 200 高：按 CSS「只多不少」，外层盒随内容长高，只锁后随间距。
        assertHeadPage(markup, "嵌套指定高", bandHeight = null, gap = 16)
    }

    @Test
    fun `container box extends to the specified height on each table`() {
        val markup = chapter(
            """
            <p>${long("路漫漫其修远兮，吾将上下而求索。")}</p>
            <div class="ovh"><p>短</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        assertHeadPage(markup, "容器盒高", bandHeight = 50, gap = 16)
    }

    @Test
    fun `content taller than the specified height keeps its own height on each table`() {
        val markup = chapter(
            """
            <div class="ovh"><p>${long("路漫漫其修远兮，吾将上下而求索。")}</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        assertHeadPage(markup, "内容高于指定高", bandHeight = null, gap = 16)
        assertTrue("内容更高时容器应随内容长高（磁盘表 ${headBandHeight(true, markup)}）", headBandHeight(true, markup) > 50)
        assertTrue("内容更高时容器应随内容长高（临时表 ${headBandHeight(false, markup)}）", headBandHeight(false, markup) > 50)
    }

    @Test
    fun `min-height container grows to the floor on each table`() {
        val markup = chapter(
            """
            <div class="minh"><p>短</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        // 120 内容 + 4+4 padding + 1+1 border = 130（Chromium 实测同值）。
        assertHeadPage(markup, "最小高", bandHeight = 130, gap = 16)
    }

    @Test
    fun `min-height below the content height is a no-op on each table`() {
        val markup = chapter(
            """
            <div class="minh-small"><p>${"路漫漫其修远兮，吾将上下而求索。".repeat(30)}</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        assertHeadPage(markup, "最小高低于内容", bandHeight = null, gap = 16)
        assertTrue("内容更高时最小高不应压扁盒子（磁盘表 ${headBandHeight(true, markup)}）", headBandHeight(true, markup) > 130)
        assertTrue("内容更高时最小高不应压扁盒子（临时表 ${headBandHeight(false, markup)}）", headBandHeight(false, markup) > 130)
    }

    @Test
    fun `min-height that binds drops the last child's bottom margin on each table`() {
        val markup = chapter(
            """
            <div class="minh-gap"><p>短</p><p>更短</p></div>
            <div>${long("溯洄从之，道阻且长。")}</div>
            """.trimIndent(),
        )
        // Chromium：末子下边距收进盒内，后随内容贴盒底（gap 0）。
        // `.minh-gap` 无 padding/border：120 最小高即盒高。
        assertHeadPage(markup, "最小高生效 + 末子下边距", bandHeight = 120, gap = 0)
    }

    @Test
    fun `min-height below the content keeps the normal margin collapse on each table`() {
        val markup = chapter(
            """
            <div class="minh-gap-small"><p>${"路漫漫其修远兮，吾将上下而求索。".repeat(30)}</p></div>
            <div>${long("溯洄从之，道阻且长。")}</div>
            """.trimIndent(),
        )
        // 下限没生效时末子下边距照常外塌（16）。
        assertHeadPage(markup, "最小高未生效 + 下边距外塌", bandHeight = null, gap = 16)
        assertTrue("容器应随内容长高（磁盘表 ${headBandHeight(true, markup)}）", headBandHeight(true, markup) > 130)
        assertTrue("容器应随内容长高（临时表 ${headBandHeight(false, markup)}）", headBandHeight(false, markup) > 130)
    }
}
