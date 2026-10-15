package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非回归锁：**页面绘制帧必须拼合、且不得超出页高**（`BookLayout.getPageFrameTop/Bottom`）。
 *
 * ## 口径（一条缺口只归一页）
 *
 * `frameTop(i) = frameBottom(i-1)`、`frameBottom(i) = 行底(i) + 页尾叶自身边` —— 块边界缺口
 * （页尾叶自身 `padding-bottom/border-bottom` + 折叠 margin + 下叶自身 top 边）**只在下一段
 * 内容所在页收一次**。旧口径 `bottom(i)=lineTop(i+1)` ＋ `top(i+1)=lineBottom(i)` 把同一段缺口
 * 两页各收一次 ⇒ 每页白丢一行（真机：「明明还有空间，最后一行却被推到下一页」），且绘制侧把整页
 * 从行顶对齐时预留的缺口留成页**底**空白、页首块的 `border/padding` 反而在栅格上沿被裁。
 *
 * ## 锁法
 *
 * 1. **拼合**：相邻两页 `frameBottom(前页末行) == frameTop(后页首行)`（同一条行流坐标系下）；
 *    旧口径下两者差一整段缺口，必红。
 * 2. **不超页高**：每页 `frameBottom(末行) - frameTop(首行) ≤ contentH` —— 绘制栅格恰为
 *    `contentH`，超了就是末行被裁（真机修前 `extent=2475 > 2400`）。重路径与轻路径（临时页）
 *    都要过这一条，两路坐标系不同故各自用自身布局量。
 * 3. 反空跑：扫描中必须真的出现过「页首缺口 > 0」与「页尾叶自身边 > 0」的页，否则 fixture 没量到东西。
 */
class PageFrameTilingTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    /** 页尾叶自身边（`pre` 的 padding、`div.box` 的 border）与页首缺口（折叠 margin）都要被量到。 */
    private val css = CssBundle(
        listOf(
            """
            body { font-size: 16px; line-height: 1.5; margin: 0; }
            p { margin: 16px 0; }
            p.fig { margin: 44px 0 30px 0; }
            h2 { font-size: 22px; margin: 34px 0 14px 0; }
            pre { margin: 20px 0; padding: 12px 0 18px 0; white-space: pre; font-family: monospace; font-size: 14px; }
            div.box { padding: 14px 8px 22px 8px; border-top: 5px solid #000; border-bottom: 7px solid #000; }
            """.trimIndent(),
        ),
    )

    private fun para(text: String) = "<p class=\"body\">\n$text\n</p>"

    /** 多行 `<pre>`：`white-space: pre` 保留换行 ⇒ 块内每行末比下一行首少 1 字符。 */
    private fun code(lines: Int) = "<pre>" + (1..lines).joinToString("\n") { "val v$it = f($it)" } + "\n</pre>"

    private fun chapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <h2>帧拼合</h2>
          ${para("开头段落。".repeat(24))}
          ${code(18)}
          <div class="box">
            ${para("盒内第一段。".repeat(14))}
            ${para("盒内第二段。".repeat(14))}
          </div>
          <p class="fig"><img src="fig.png" width="200" height="160"/></p>
          ${para("图后段落。".repeat(18))}
          ${code(22)}
          ${para("结尾段落。".repeat(20))}
        </body></html>
        """.trimIndent(),
    )!!

    private fun canonical(markup: MarkupElement, contentH: Int): ChapterLayouter.ChapterLayoutProduct {
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        return layouter.fullLayout(heavy, profile, contentW, contentH)
    }

    private fun tempForward(markup: MarkupElement, contentH: Int): List<Pair<PageSlice, BookLayout>> {
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val out = ArrayList<Pair<PageSlice, BookLayout>>()
        var block = 0
        var line = 0
        var guard = 0
        while (block < light.totalBlocks && guard++ < 2000) {
            val fwd = layouter.shapeTempPageForward(
                light, profile, contentW, contentH, block, cache = null, startLine = line,
            ) ?: break
            out.add(fwd.page.slice to fwd.page.layout)
            if (fwd.nextBlock >= light.totalBlocks) break
            block = fwd.nextBlock
            line = fwd.nextLine
        }
        return out
    }

    /**
     * 主锁：`contentH` 扫描下，canonical 逐页帧拼合且不超页高；临时页也各自不超页高。
     * 反空跑同时守住「缺口/页尾边真的被量到」——否则本用例会变成恒真。
     */
    @Test
    fun `page frames tile and never exceed content height on both paths`() {
        val markup = chapter()
        val firstCanon = canonical(markup, 600)
        // 反空跑（布局级，确定性）：页尾叶自身边必须真的被 frame 函数量到，否则本用例只锁了个恒等式。
        assertTrue(
            "canonical 布局里没有任何行满足 frameBottom > 行底 ⇒ 页尾 padding/border 没有被量到",
            firstCanon.layout.hasLineWithTailEdges(),
        )
        var sawLead = 0
        var heights = 0
        for (contentH in 260..1400 step 6) {
            val canon = canonical(markup, contentH)
            val layout = canon.layout
            val slices = canon.slices
            assertTrue("contentH=$contentH canonical 必须出页", slices.isNotEmpty())
            for ((i, s) in slices.withIndex()) {
                val first = s.firstLine
                val last = s.lastLineExclusive - 1
                val top = layout.getPageFrameTop(first)
                val bot = layout.getPageFrameBottom(last)
                // 2. 不超页高（栅格恰 contentH，超了即末行被裁）。
                assertTrue(
                    "contentH=$contentH canonical 第 $i 页帧高 ${bot - top} > 页高（末行越出栅格）",
                    bot - top <= contentH,
                )
                // 3. 反空跑计数：页首缺口真的出现过。
                if (top < layout.getLineTop(first)) sawLead++
                if (first > 0) {
                    val prev = slices[i - 1]
                    val prevBot = layout.getPageFrameBottom(prev.lastLineExclusive - 1)
                    // 1. 拼合：缺口只归一页（前页帧底 == 本页帧顶）。
                    assertEquals(
                        "contentH=$contentH canonical 第 ${i - 1}→$i 页帧未拼合（块边界缺口被两页各收一次）",
                        prevBot, top,
                    )
                }
            }
            val temp = tempForward(markup, contentH)
            assertTrue("contentH=$contentH 临时表必须出页", temp.isNotEmpty())
            for ((i, p) in temp.withIndex()) {
                val (s, lay) = p
                val first = s.firstLine
                val last = s.lastLineExclusive - 1
                val top = lay.getPageFrameTop(first)
                val bot = lay.getPageFrameBottom(last)
                assertTrue(
                    "contentH=$contentH 临时页 $i 帧高 ${bot - top} > 页高（末行越出栅格）",
                    bot - top <= contentH,
                )
            }
            heights++
        }
        assertTrue("没有扫到足够档位 ⇒ 用例空跑", heights > 100)
        assertTrue("扫描中从未出现页首缺口（frameTop < 行顶）⇒ 没有量到页首 margin/padding", sawLead > 0)
    }

    /**
     * 页首缺口单源：`ReaderHost.pageFrameLead`（绘制锚点）与分页预算必须同值 ——
     * 即 `getLineTop(首行) - getPageFrameTop(首行)`，且 ≥ 0（锚点只会**上抬**，不会下推）。
     */
    @Test
    fun `frame lead equals first line top minus frame top`() {
        val markup = chapter()
        var sawPositive = 0
        for (contentH in 300..1400 step 13) {
            val canon = canonical(markup, contentH)
            val layout = canon.layout
            for (s in canon.slices) {
                val lead = layout.getLineTop(s.firstLine) - layout.getPageFrameTop(s.firstLine)
                assertTrue("contentH=$contentH 页首缺口不得为负（锚点下推即末行越栅格）", lead >= 0)
                // 帧顶不得低于首行行顶（否则首行本身就被裁）。
                assertTrue("帧顶低于首行行顶 ⇒ 首行落在栅格上沿之外", layout.getPageFrameTop(s.firstLine) <= layout.getLineTop(s.firstLine))
                if (lead > 0) sawPositive++
            }
        }
        assertTrue("从未出现正缺口 ⇒ 用例空跑", sawPositive > 0)
    }

    /** 布局级反空跑：存在某行 `frameBottom > 行底`，即页尾叶自身 `padding-bottom/border-bottom` 被量到。 */
    private fun BookLayout.hasLineWithTailEdges(): Boolean =
        (0 until lineCount).any { getPageFrameBottom(it) > getLineBottom(it) }
}
