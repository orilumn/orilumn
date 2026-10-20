package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E23-b：**容器指定高**（`.ovh{height:40px}`）在重/轻两路的等价锁。
 *
 * 重路径 `emit` 的容器分支把内容盒高定为 `max(在流内容高, height)`（CSS 2.1 §10.6.3，与叶同一条
 * 「只多不少」口径：内容更高照排不裁），容器底边与后随内容的起点因此落在指定高上。轻路径的行流是
 * 局部位移累加（`consecutiveLeafAdvance` 只有边距与边），必须靠 `containerHeightLead` 补同一段差额
 * ——否则临时页/增量窗相对磁盘页少一段空白，分页在两种页之间横跳（正是 `docs/引擎欠账` E23-b 记录
 * 的那条）。
 *
 * 判据（与 [TempVsFullTitlePageEquivalenceTest] 同一套）：
 *  1. 页切片逐字段一致；
 *  2. 页内逐行相对几何一致（含容器底之后的那段空白）；
 *  3. 容器自身盒（背景带底）在两路同值，且 = 指定高 + 自身上下边；
 *  4. 内容比指定高更高时按内容走（差额 0），两路同样一致。
 */
class BoxFixedHeightEquivalenceTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private val css = CssBundle(
        listOf(
            """
            body { font-size: 16px; line-height: 1.5; margin: 0 }
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

    private fun backgroundsOf(layout: orilumn.reader.engine.paging.BookLayout, first: Int, last: Int) =
        (layout as orilumn.reader.engine.skia.LayoutReadback).pageBackgrounds(first, last)

    /** `pageBackgrounds` 里那块容器（按底色认）。 */
    private fun containerBand(bgs: List<PageBackground>): PageBackground? =
        bgs.firstOrNull { Integer.toHexString(it.argb) == "fff5f5f5" }

    private fun assertHeadPageEquivalent(markup: MarkupElement, label: String) {
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val full = layouter.fullLayout(heavy, profile, contentW, contentH)
        val fullPage = full.slices[0]
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)
            ?: error("$label: temp head page failed")
        val tempLayout = fwd.page.layout
        val tempPage = fwd.page.slice

        assertEquals("$label: page-0 charStart", fullPage.charStart, tempPage.charStart)
        assertEquals("$label: page-0 charEnd", fullPage.charEnd, tempPage.charEnd)
        assertEquals("$label: page-0 firstLine", fullPage.firstLine, tempPage.firstLine)
        assertEquals("$label: page-0 lastLineExclusive", fullPage.lastLineExclusive, tempPage.lastLineExclusive)

        val pageLines = tempPage.lastLineExclusive - tempPage.firstLine
        assertTrue("$label: 章首页至少两行（$(pageLines)）", pageLines >= 2)
        val fullBase = full.layout.getLineTop(fullPage.firstLine)
        val tempBase = tempLayout.getLineTop(tempPage.firstLine)
        for (i in 0 until pageLines) {
            assertEquals(
                "$label: line $i relative top",
                full.layout.getLineTop(fullPage.firstLine + i) - fullBase,
                tempLayout.getLineTop(tempPage.firstLine + i) - tempBase,
            )
            assertEquals(
                "$label: line $i relative bottom",
                full.layout.getLineBottom(fullPage.firstLine + i) - fullBase,
                tempLayout.getLineBottom(tempPage.firstLine + i) - tempBase,
            )
        }
    }

    @Test
    fun `text after a fixed-height container sits at the canonical offset on both paths`() {
        val markup = chapter(
            """
            <p>${long("路漫漫其修远兮，吾将上下而求索。")}</p>
            <div class="ovh"><p>短</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            <p>${long("登高望远，天地一色。")}</p>
            """.trimIndent(),
        )
        assertHeadPageEquivalent(markup, "fixed-height container")
    }

    @Test
    fun `nested fixed-height containers keep the same gap on both paths`() {
        val markup = chapter(
            """
            <div class="ovh"><div class="tall"><p>短</p></div></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            <p>${long("登高望远，天地一色。")}</p>
            """.trimIndent(),
        )
        assertHeadPageEquivalent(markup, "nested")
    }

    @Test
    fun `container box extends to the specified height on both paths`() {
        val markup = chapter(
            """
            <p>${long("路漫漫其修远兮，吾将上下而求索。")}</p>
            <div class="ovh"><p>短</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        // 正典（盒树）。
        val canon = layouter.fullLayout(layouter.prepare(markup, css, profile, contentW, contentH), profile, contentW, contentH)
        val canonSlice = canon.slices[0]
        val canonBand = containerBand(backgroundsOf(canon.layout, canonSlice.firstLine, canonSlice.lastLineExclusive))
            ?: error("正典路径缺容器底色盒")
        // 轻路径（临时页）。
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)
            ?: error("临时页未产出")
        val tempBand = containerBand(
            backgroundsOf(fwd.page.layout, fwd.page.slice.firstLine, fwd.page.slice.lastLineExclusive),
        ) ?: error("轻路径缺容器底色盒")
        // 40 内容 + 4+4 padding + 1+1 border = 50。
        assertEquals("容器盒高 = 指定高 + 自身上下边", 50, canonBand.yBottom - canonBand.yTop)
        assertEquals("容器盒高 = 指定高 + 自身上下边（轻路径）", 50, tempBand.yBottom - tempBand.yTop)
        assertEquals(
            "轻/正两路的容器盒相对页首行位置不一致",
            canonBand.yTop - canon.layout.getLineTop(canonSlice.firstLine),
            tempBand.yTop - fwd.page.layout.getLineTop(fwd.page.slice.firstLine),
        )
        assertEquals(
            "轻/正两路的容器盒底相对页首行位置不一致",
            canonBand.yBottom - canon.layout.getLineTop(canonSlice.firstLine),
            tempBand.yBottom - fwd.page.layout.getLineTop(fwd.page.slice.firstLine),
        )
    }

    @Test
    fun `content taller than the specified height keeps its own height`() {
        val markup = chapter(
            """
            <div class="ovh"><p>${long("路漫漫其修远兮，吾将上下而求索。")}</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        assertHeadPageEquivalent(markup, "content taller than height")
        val canon = layouter.fullLayout(layouter.prepare(markup, css, profile, contentW, contentH), profile, contentW, contentH)
        val canonSlice = canon.slices[0]
        val canonBand = containerBand(backgroundsOf(canon.layout, canonSlice.firstLine, canonSlice.lastLineExclusive))
            ?: error("正典路径缺容器底色盒")
        assertTrue(
            "内容更高时容器应随内容长高（实际 ${canonBand.yBottom - canonBand.yTop}）",
            canonBand.yBottom - canonBand.yTop > 50,
        )
    }

    /** 正典 / 临时两路的容器底色盒（按底色认；只取章首页）。 */
    private fun bothContainerBands(markup: MarkupElement): Pair<PageBackground, PageBackground> {
        val canon = layouter.fullLayout(layouter.prepare(markup, css, profile, contentW, contentH), profile, contentW, contentH)
        val cs = canon.slices[0]
        val cb = containerBand(backgroundsOf(canon.layout, cs.firstLine, cs.lastLineExclusive))
            ?: error("正典路径缺容器底色盒")
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)
            ?: error("临时页未产出")
        val lb = containerBand(
            backgroundsOf(fwd.page.layout, fwd.page.slice.firstLine, fwd.page.slice.lastLineExclusive),
        ) ?: error("轻路径缺容器底色盒")
        return cb to lb
    }

    @Test
    fun `min-height container grows to the floor on both paths`() {
        val markup = chapter(
            """
            <div class="minh"><p>短</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        assertHeadPageEquivalent(markup, "min-height container")
        val (cb, lb) = bothContainerBands(markup)
        // 120 内容 + 4+4 padding + 1+1 border = 130（Chromium 实测同值）。
        assertEquals("最小高生效：容器盒高 = 最小高 + 自身上下边", 130, cb.yBottom - cb.yTop)
        assertEquals("最小高生效（轻路径）", 130, lb.yBottom - lb.yTop)
    }

    @Test
    fun `min-height below the content height is a no-op on both paths`() {
        val markup = chapter(
            """
            <div class="minh-small"><p>${"路漫漫其修远兮，吾将上下而求索。".repeat(30)}</p></div>
            <p>${long("溯洄从之，道阻且长。")}</p>
            """.trimIndent(),
        )
        assertHeadPageEquivalent(markup, "min-height below content")
        val (cb, lb) = bothContainerBands(markup)
        assertTrue(
            "内容更高时最小高不应压扁盒子（正典 ${cb.yBottom - cb.yTop}）",
            cb.yBottom - cb.yTop > 130,
        )
        assertEquals("重/轻两路容器盒高同值", cb.yBottom - cb.yTop, lb.yBottom - lb.yTop)
    }

    /** 容器底色带**之后**的第一行（= 后随段首行）到带底的距离。 */
    private fun gapAfterBand(
        layout: orilumn.reader.engine.paging.BookLayout,
        slice: orilumn.reader.engine.paging.PageSlice,
        band: PageBackground,
    ): Int {
        for (i in slice.firstLine until slice.lastLineExclusive) {
            val top = layout.getLineTop(i)
            if (top >= band.yBottom) return top - band.yBottom
        }
        error("底色带之后没有行")
    }

    @Test
    fun `min-height that binds drops the last child's bottom margin on both paths`() {
        val markup = chapter(
            """
            <div class="minh-gap"><p>短</p><p>更短</p></div>
            <div>${long("溯洄从之，道阻且长。")}</div>
            """.trimIndent(),
        )
        // 重/轻两路的行几何逐行一致（含容器底之后那段）—— Chromium：末子下边距收进盒内，后随内容贴盒底。
        assertHeadPageEquivalent(markup, "min-height bound + last margin")
        val canon = layouter.fullLayout(layouter.prepare(markup, css, profile, contentW, contentH), profile, contentW, contentH)
        val cs = canon.slices[0]
        val band = containerBand(backgroundsOf(canon.layout, cs.firstLine, cs.lastLineExclusive))
            ?: error("正典路径缺容器底色盒")
        assertEquals("下限生效时末子下边距不外塌（Chromium 同值 0）", 0, gapAfterBand(canon.layout, cs, band))
    }

    @Test
    fun `min-height below the content keeps the normal margin collapse on both paths`() {
        val markup = chapter(
            """
            <div class="minh-gap-small"><p>${"路漫漫其修远兮，吾将上下而求索。".repeat(30)}</p></div>
            <div>${long("溯洄从之，道阻且长。")}</div>
            """.trimIndent(),
        )
        assertHeadPageEquivalent(markup, "min-height below content + margin")
        val canon = layouter.fullLayout(layouter.prepare(markup, css, profile, contentW, contentH), profile, contentW, contentH)
        val cs = canon.slices[0]
        val band = containerBand(backgroundsOf(canon.layout, cs.firstLine, cs.lastLineExclusive))
            ?: error("正典路径缺容器底色盒")
        assertEquals("下限没生效时末子下边距照常外塌（16）", 16, gapAfterBand(canon.layout, cs, band))
    }
}
