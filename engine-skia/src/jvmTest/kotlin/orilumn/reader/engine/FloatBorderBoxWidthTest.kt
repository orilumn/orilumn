package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E24：**浮动盒指定宽的内容盒口径**（重 / 轻两路 + 画盒两层同源）。
 *
 * Chromium 实测（2026-10-20，`--headless=new`，`.fl{width:30px;border:1px}`）：`width` 是**内容盒**
 * 宽 ⇒ 底色 30px、边框**外画**、外框 **32px**；并排间距与环绕窄宽都按 32px 算。
 * 我方原先把 `width` 当 border-box 用（`floatTextWidth` / `replacedUsedSize` 给的都是内容宽，
 * 却被直接当成盒宽）⇒ 每盒少自身横边 2px，并排时 4px 变 4px 间距错 2px，浮动内容区也小 2px。
 *
 * 判据：
 *  1. 盒宽 = 内容宽 + 自身横向 `padding`+`border`（重路径 `LayoutBox.contentWidth`、轻路径 `block(i)`、
 *     画盒 `floatBoxOf` 三处同值）；
 *  2. 断行宽仍是内容宽（`innerBreakWidth` 反算）——窄了 2px 会让浮动文本提前折行；
 *  3. 排位落点按 border-box 累加（并排 x 依次 0 / 36 / 72…）；
 *  4. 环绕前导宽按 border-box 记的跨度收窄，重 / 轻两路逐值一致；
 *  5. 章首页切片与逐行相对几何重 / 轻一致（本项改的是横向，纵向不得漂移）。
 */
class FloatBorderBoxWidthTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val contentH = 640
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private val css = CssBundle(
        listOf(
            """
            body { font-size: 16px; line-height: 1.5; margin: 0 }
            p { margin: 0 }
            .fl { float: left; width: 30px; height: 30px; border: 1px solid #52c41a; margin: 2px; background: #d9f7be }
            .fli { float: left; width: 40px; height: 40px; border: 1px solid #faad14; margin: 2px; background: #ffd666 }
            .ft { float: left; width: 120px; border: 1px solid #333; padding: 2px }
            """.trimIndent(),
        ),
    )

    private fun chapter(body: String): MarkupElement = converter.convert("<html><body>$body</body></html>")!!

    private fun lightOf(markup: MarkupElement) =
        layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)

    private val body =
        """<div class="fl"></div><div class="fl"></div><div class="fli"></div>""" +
            """<p>${"正文绕排文字，须与浮动盒同宽口径。".repeat(40)}</p>"""

    @Test
    fun `float box width is content plus own horizontal edges on both paths`() {
        val markup = chapter(body)
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val hLeaves = heavy.leaves
        // 三个浮动盒：30+2 = 32、30+2 = 32、40+2 = 42；落点按 border-box 累加（各带 2px 外边距）。
        assertEquals(listOf(32, 32, 42), hLeaves.take(3).map { it.contentWidth })
        // 落点按 border-box 累加（各带 2px 左右外边距：2、2+32+4=38、38+32+4=74）。
        assertEquals(listOf(2, 38, 74), hLeaves.take(3).map { it.contentLeft })

        val light = lightOf(markup)
        val lWidths = ArrayList<Int>()
        val lLefts = ArrayList<Int>()
        for (i in 0 until light.totalBlocks) {
            val b = light.block(i)
            if (b.style.floatSide == orilumn.reader.engine.css.FloatSide.NONE) continue
            lWidths.add(b.contentWidth)
            lLefts.add(b.contentLeft)
        }
        assertEquals("轻路径盒宽必须与重路径逐值一致", hLeaves.take(3).map { it.contentWidth }, lWidths)
        assertEquals("轻路径落点必须与重路径逐值一致", hLeaves.take(3).map { it.contentLeft }, lLefts)
    }

    @Test
    fun `draw box and break width follow the same border-box rule`() {
        val markup = chapter(body)
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val fl = heavy.leaves[0]
        // 画盒（轻路径 `ownerBackgroundBox` 的来源）与重路径同宽：底色带 = border-box 32。
        val light = lightOf(markup)
        val drawn = light.floatBoxOf(fl.el!!)!!
        assertEquals("浮动画盒宽 = 盒宽", fl.contentWidth, drawn.second)
        // 画盒左缘 = 盒左缘（`floatBoxOf` 给的是相对容器内容的落点，容器内容左缘由同一 `descendContentLeft` 给出）。
        val lightFl = (0 until light.totalBlocks).map { light.block(it) }.first { it.el === fl.el }
        assertEquals(fl.contentLeft, lightFl.contentLeft)
        // 断行宽 = border-box − 自身横边 = 内容宽（Chrome 按 30px 折行，不是 28px）。
        assertEquals(30, NormalFlowLayout.innerBreakWidth(fl.style, fl.contentWidth))
        // 替换型浮动（图）：位图仍是内容宽 40，边框外画 ⇒ 盒宽 42 里图占 40。
        val img = heavy.leaves[2]
        assertEquals(42, img.contentWidth)
        assertEquals(
            40,
            NormalFlowLayout.replacedUsedSize(
                img.el!!, img.style, NormalFlowLayout.innerBreakWidth(img.style, img.contentWidth),
            ).first,
        )
    }

    @Test
    fun `text float breaks at its content width`() {
        val markup = chapter("""<div class="ft">${"浮动侧栏文字。".repeat(30)}</div><p>正文。</p>""")
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val box = heavy.leaves.first { it.el?.tag == "div" }
        // 120 内容宽 + 1px 边框 ×2 + 2px padding ×2 = 126。
        assertEquals(126, box.contentWidth)
        assertEquals(120, NormalFlowLayout.innerBreakWidth(box.style, box.contentWidth))
    }

    @Test
    fun `wrap lead narrows by the float border box on both paths`() {
        val markup = chapter(body)
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val lead = heavy.leaves.last().floatLead!!
        // 跨度 = (32+4) + (32+4) + (42+4) = 118；窄宽 = 720 − 118 − 可断行安全间隙。
        val gap = ListMarkers.markerGapPx(16f)
        assertEquals(contentW - 118 - gap, lead.widthPx)
        assertEquals((118 + gap).toFloat(), lead.xOffPx, 1e-6f)

        val light = lightOf(markup)
        val lLead = (0 until light.totalBlocks).map { light.floatLeadAt(it) }.last { it != null }!!
        assertEquals("环绕前导宽重 / 轻必须逐值一致", lead.widthPx, lLead.widthPx)
        assertEquals("环绕前导位移重 / 轻必须逐值一致", lead.xOffPx, lLead.xOffPx, 1e-6f)
    }

    @Test
    fun `page slices and relative line geometry match between paths`() {
        val markup = chapter(body)
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val full = layouter.fullLayout(heavy, profile, contentW, contentH)
        val light = lightOf(markup)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, contentH, 0, cache = null)!!
        val cs = full.slices[0]
        val ts = fwd.page.slice
        assertEquals(cs.charStart, ts.charStart)
        assertEquals(cs.charEnd, ts.charEnd)
        assertEquals(cs.firstLine, ts.firstLine)
        assertEquals(cs.lastLineExclusive, ts.lastLineExclusive)
        val n = ts.lastLineExclusive - ts.firstLine
        assertTrue("章首页至少两行", n >= 2)
        val hBase = full.layout.getLineTop(cs.firstLine)
        val tBase = fwd.page.layout.getLineTop(ts.firstLine)
        for (i in 0 until n) {
            assertEquals(
                "line $i relative top",
                full.layout.getLineTop(cs.firstLine + i) - hBase,
                fwd.page.layout.getLineTop(ts.firstLine + i) - tBase,
            )
        }
    }
}
