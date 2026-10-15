package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版层（上）· 锚页手工累加 vs 磁盘表 [orilumn.reader.engine.paging.Paginator] 同一口径锁。
 *
 * `shapeAnchorPageForward` 不走 [orilumn.reader.engine.paging.Paginator]，自己用「逐行高 + 块边界缺口」
 * 累加到 [contentH] 决定切点（大章锚页必须**同步**出页，来不及跑整章分页）。它必须与磁盘表分页
 * **逐行同切**，否则跳转/开书/调参时锚页与后续磁盘页在边界处错一格：多装一行就是丢字/越栅格，
 * 少装一行就是页尾空白。
 *
 * 行高与缺口的口径是 [orilumn.reader.engine.paging.BookLayout.getPageFrameTop] /
 * `getPageFrameBottom`：**页内**每一处块边界只记一次完整落差（前块自身下边 + 折叠边距 + 本块自身 top
 * 边），只有**本页首块首行**记 [leadAbove]（折扣掉已归前一页页尾的前块自身下边）。
 *
 * 历史缺陷（2026-10-15 真机）：把 `leadAbove` 无条件记给**每个**块首行，而页内相邻块已经按完整落差记过
 * 同一段 ⇒ 每个块边界白丢「折叠边距 + 本块自身 top 边」。真书 Ch02 锚页因此少装 5 行、页尾空 532px
 * （`PG [anchor] lines=[0,20) chars=0..542 UNDER-532`，磁盘表同页是 26 行 / `0..798`）。
 * 本文件锁住「锚页 == 磁盘表同页」这条不变式：段落足够多 + 每段有非零折叠边距，双记就会立刻显形。
 */
class AnchorPageFillEquivalenceTest {

    private val contentW = 720

    /** 一页只装得下十来段 ⇒ 锚页里必然有十余处块边界，双记/漏记都会被放大成整行差。 */
    private val contentH = 640

    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    /** 非零折叠边距是这条口径的关键：叶自身边全 0 时双记也「恰好」看不出来。 */
    private val css = CssBundle(
        listOf(
            """
            body { margin: 0; padding: 0; }
            p {
              display: block;
              margin: 40px 0;
              padding: 0;
              border: 0;
              font-size: 1em;
              line-height: 1.5;
              text-align: justify;
            }
            """.trimIndent(),
        ),
    )

    private fun markup(): MarkupElement {
        val body = buildString {
            for (i in 1..40) {
                append("<p>第${i}段：把每一行高度加起来，尽可能填满页面高度空间，但又不溢出。</p>")
            }
        }
        return HtmlTreeConverter().convert("<html><body>$body</body></html>")!!
    }

    @Test
    fun `every anchor page reproduces its canonical disk-table page`() {
        val markup = markup()
        val layouter = BoxChapterLayouter()

        // 磁盘表口径：整章 prepare + emit 分页（单源 Paginator.fillWholeLines + 帧口径）。
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val full = layouter.fullLayout(heavy, profile, contentW, contentH)
        val slices = full.slices
        assertTrue("章节应有多页（否则本锁失效）", slices.size >= 3)

        // 临时表口径：同一章的轻量塑形 + 锚页手工累加。
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        assertTrue("锚页至少要有十余行，否则锁不到块边界累加", slices[0].lastLineExclusive - slices[0].firstLine >= 6)

        // 每一页的页首字符作锚点，锚页都必须复现该页（跳转/开书/调参后的第一屏就是这条路径）。
        for (k in slices.indices) {
            val want = slices[k]
            val fwd = layouter.shapeAnchorPageForward(light, profile, contentW, contentH, want.charStart, cache = null)
                ?: error("anchor page p$k failed")
            val got = fwd.page.slice
            val wantLines = want.lastLineExclusive - want.firstLine
            val gotLines = got.lastLineExclusive - got.firstLine
            assertEquals("p$k charStart", want.charStart, got.charStart)
            assertEquals("p$k line count", wantLines, gotLines)
            assertEquals("p$k charEnd", want.charEnd, got.charEnd)
        }

        // 反向：锚页尾部不推进 ⇒ 相邻页之间不重叠、不丢块。
        assertEquals("非末页必须向后推进", slices[1].charStart, slices[0].charEnd)
    }
}
