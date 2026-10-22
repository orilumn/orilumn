package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BreakAwareBookLayout
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版层（上）· **临时页自己的义务**：页尾不得因为 `break-inside: avoid` 卡片只量到一半，就被退回
 * 卡片开头、空掉半页。
 *
 * 触发形态：`shapeTempPageForward` / `shapeAnchorPageForward` 只按行高累加**圈窗口**，切点交
 * `Paginator.paginateFrom`。窗口在卡片内部收盘时，窗口布局量到的原子区间只是卡片的一截（更矮）⇒
 * `resolveAvoidCut` 误判「整块装得下一页」⇒ 切点退回卡片首。合成章节 `contentH=420` 上，临时表
 * 第一页只装 1 行（引言那句），整章比实际多出一页。
 *
 * **本测试只测临时表，不碰磁盘表**（AGENTS.md「磁盘表与临时表互斥」）：两张表各自独立成立，页界、
 * 页数互不要求相同，拿临时页去比磁盘页没有意义。判据全部取自临时表自身：
 *
 * - 卡片真实高度由**同一张临时表**的一次「一张大页」塑形量出（与窗口大小无关）；
 * - 页尾不许停在这张「比一页还高」的卡片开头，除非本页本来就已装满（下一行的空间确实不够）；
 * - 页与页首尾相接（不丢不重）；
 * - 锚页与同起点的前向页必须给出同一页（同一张表、同一个页首，入口不同而已）；
 * - 用例必须真的把这张 oversized 卡片撕开过（否则等于空跑）。
 */
class TempAvoidWindowTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
    private val css = CssBundle(listOf(".card { break-inside: avoid; }"))

    private fun body(text: String, times: Int) = text.repeat(times)

    /** 短前言 + 一张远高过一页的 avoid 卡片 + 尾声。 */
    private fun chapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <p>${body("序言一句话。", 2)}</p>
          <div class="card">
            ${(1..8).joinToString("") { "<p>${body("卡片第 $it 段：这一段属于 break-inside avoid 的整张卡片，卡片内部不允许断页。", 16)}</p>" }}
          </div>
          <p>${body("卡片之后的尾声文字。", 6)}</p>
        </body></html>
        """.trimIndent(),
    )!!

    private fun prepareLight(contentH: Int): LightPrepare =
        layouter.prepareLight(chapter(), css, profile, contentW, ChapterStructureCache(), contentH)

    /** 卡片在本章里的位置与真实高度（字符区间 + 首行高），由同一张临时表的一张大页量得。 */
    private class Card(val firstChar: Int, val endChar: Int, val height: Int, val firstLineHeight: Int)

    private fun measureCard(light: LightPrepare): Card {
        val wide = layouter.shapeTempPageForward(
            light, profile, contentW, contentH = 1_000_000, startBlock = 0, cache = null, startLine = 0,
        ) ?: error("大页未产出")
        val layout = wide.page.layout
        val range = (layout as BreakAwareBookLayout).avoidSplitRanges.singleOrNull()
            ?: error("本章应只有一张 avoid 卡片，实际=${(layout as BreakAwareBookLayout).avoidSplitRanges}")
        return Card(
            firstChar = layout.getLineStart(range.first),
            endChar = if (range.last >= layout.lineCount) layout.length else layout.getLineStart(range.last),
            height = layout.getLineBottom(range.last - 1) - layout.getLineTop(range.first),
            firstLineHeight = layout.getLineBottom(range.first) - layout.getLineTop(range.first),
        )
    }

    /** 从章首先前向灌页（读者从本章第一页往下翻的那条链）。 */
    private fun forwardChain(light: LightPrepare, contentH: Int): List<TempPage> {
        val out = ArrayList<TempPage>()
        var block = 0
        var line = 0
        var guard = 0
        while (block < light.totalBlocks && guard++ < 2000) {
            val fwd = layouter.shapeTempPageForward(
                light, profile, contentW, contentH, block, cache = null, startLine = line,
            ) ?: break
            out.add(fwd.page)
            if (fwd.nextBlock >= light.totalBlocks) break
            if (fwd.nextBlock == block && fwd.nextLine == line) break
            block = fwd.nextBlock
            line = fwd.nextLine
        }
        return out
    }

    private fun usedHeight(p: TempPage): Int =
        p.layout.getPageFrameBottom(p.slice.lastLineExclusive - 1) - p.layout.getPageFrameTop(p.slice.firstLine)

    /** 页尾停在一张「比一页还高」的卡片开头时必须已经装满：卡片首行若放得下，就是我退早了。 */
    private fun assertTailNotRetractedAtOversizedCard(
        p: TempPage,
        card: Card,
        contentH: Int,
        label: String,
    ) {
        if (card.height <= contentH || p.slice.charEnd != card.firstChar) return
        val used = usedHeight(p)
        assertTrue(
            "$label 页尾停在 oversized 卡片开头，却只装了 ${used}/${contentH}px（卡片首行 ${card.firstLineHeight}px 还放得下）" +
                " —— 卡片只量到窗口内一截，被判「下一页装得下」后退回了卡片首",
            used + card.firstLineHeight > contentH,
        )
    }

    @Test
    fun `临时页不得把 oversized avoid 卡片的页尾退回卡片首`() {
        var sawTear = false

        for (contentH in 300..700 step 20) {
            val light = prepareLight(contentH)
            val card = measureCard(light)
            val chain = forwardChain(light, contentH)
            assertTrue("h=$contentH 前向链应有多页", chain.size >= 2)

            for (i in chain.indices) {
                val p = chain[i]
                if (i > 0) {
                    assertEquals(
                        "h=$contentH p$i 必须与上一页首尾相接（不丢不重）",
                        chain[i - 1].slice.charEnd, p.slice.charStart,
                    )
                }
                assertTrue(
                    "h=$contentH p$i 页高 ${usedHeight(p)}px 超出 ${contentH}px",
                    usedHeight(p) <= contentH,
                )
                assertTailNotRetractedAtOversizedCard(p, card, contentH, "h=$contentH p$i")
                // 页切点落在卡片内部 = 按 oversized 撕裂（本用例要的形态）。
                if (p.slice.charEnd > card.firstChar && p.slice.charEnd < card.endChar) sawTear = true
            }

            // 同一张表、同一个页首：锚页与前向页必须是同一页（入口不同而已）。
            for ((i, p) in chain.withIndex()) {
                val anchor = layouter.shapeAnchorPageForward(
                    light, profile, contentW, contentH, p.slice.charStart, cache = null,
                ) ?: error("h=$contentH p$i 锚页未产出 char=${p.slice.charStart}")
                assertEquals(
                    "h=$contentH p$i 锚点字必须落在首行",
                    p.slice.charStart, anchor.page.slice.charStart,
                )
                assertEquals(
                    "h=$contentH p$i 同起点的锚页与前向页页尾必须一致",
                    p.slice.charEnd, anchor.page.slice.charEnd,
                )
                assertTrue(
                    "h=$contentH p$i 锚页页高超出 ${contentH}px",
                    usedHeight(anchor.page) <= contentH,
                )
                assertTailNotRetractedAtOversizedCard(anchor.page, card, contentH, "h=$contentH p$i 锚页")
            }
        }

        assertTrue("用例空跑：没有任何一页撕开这张 oversized 卡片", sawTear)
    }
}
