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
 * 排版层（上）· **增量窗口右端取整**回归：窗口尾块落在一个跨窗的 `break-inside: avoid` 卡片里时，
 * 必须把卡片补全再判页尾。
 *
 * 真机现场（2026-10-22，Kotlin 书 ch5 第 7 页）：磁盘表 42 行，增量窗只塑形 24 行；卡片（Practice:
 * Loops 及其后的代码块）从第 24 行开始、跨出窗口右端，窗口布局量到的那一截**矮于一页** ⇒
 * `Paginator.resolveAvoidCut` 判「整块装得下一页」，切点从 42 行退到 24 行。页尾空 1230px；
 * 又因为增量路径只把窗口的前若干页映射到磁盘表页，被退掉的这批行既不在本页、也不在下一页
 * （下一页按磁盘表页首锚定，行在它「上面」）⇒ 卡片标题与引导段整段消失。
 *
 * 本测试用合成章节复现同一形态：卡片**远高过一页**（oversized，正典允许撕裂），且页切点落在卡片
 * 内部、卡片首行又在页首之后。窗口若不补到卡片末叶，第二页起就会把切点退回卡片首（页尾空白 +
 * 内容丢失）；补全后量到整张卡片高过一页，切点留在自然填充点，与正典逐页一致。
 */
class IncrementalAvoidWindowTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
    private val css = CssBundle(listOf(".card { break-inside: avoid; }"))

    private fun body(text: String, times: Int) = text.repeat(times)

    /** 短前言 + 一张远高过一页的 avoid 卡片（首段自己就长过一页）+ 尾声。 */
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

    private fun canonical(contentH: Int): ChapterLayouter.ChapterLayoutProduct {
        val heavy = layouter.prepare(chapter(), css, profile, contentW, contentH)
        return layouter.fullLayout(heavy, profile, contentW, contentH)
    }

    /**
     * 逐页用**单页窗口**（= 真机磁盘命中点按路径的默认形状）重塑，页尾必须与正典同页同长。
     *
     * 只比字符区间不够：增量路径会把窗口边缘的 `charEnd` 钉回磁盘表，丢了行也照样「相等」；
     * 真正能抓住缺陷的是**本页装了几行**（页尾还能不能继续填）。
     */
    @Test
    fun `单页增量窗不得把跨窗 avoid 卡片的页尾退回卡片首`() {
        var sawOversizedCardTear = false

        for (contentH in 300..700 step 20) {
            val product = canonical(contentH)
            val canon = product.slices
            val light = prepareLight(contentH)
            val table = ChapterPaginationTable.fromSlices(
                chapterIndex = 0, paramHash = 0L,
                slices = canon, totalBlocks = light.totalBlocks, totalChars = light.totalChars,
            )
            val ranges = (product.layout as BreakAwareBookLayout).avoidSplitRanges
            val rowH: (IntRange) -> Int = { r ->
                product.layout.getLineBottom(r.last - 1) - product.layout.getLineTop(r.first)
            }
            // 卡片首块：窗口从这里起就只剩这一块，保证原子区间确实被窗口右端截断。
            val cardFirstBlock = (0 until light.totalBlocks).first {
                light.avoidOwnerMap[light.leafElement(it)] != null
            }

            for (i in canon.indices) {
                val page = canon[i]
                val inc = layouter.incrementalLayoutForPage(
                    prepare = light, profile = profile, contentW = contentW, contentH = contentH,
                    table = table, targetPage = i, pagesToShape = 1,
                )
                val s = inc.slices[i]
                val expectLines = page.lastLineExclusive - page.firstLine
                val gotLines = s.lastLineExclusive - s.firstLine
                assertEquals(
                    "contentH=$contentH page $i 页尾行数（窗口把 avoid 区间截断后误判「装得下」）",
                    expectLines, gotLines,
                )
                assertEquals("contentH=$contentH page $i charStart", page.charStart, s.charStart)
                assertEquals("contentH=$contentH page $i charEnd", page.charEnd, s.charEnd)

                // 触发检测：切点落在**页内才开始**的 oversized 卡片内部，且窗口只装到卡片首块。
                val cut = page.lastLineExclusive
                if (page.blockEndExclusive == cardFirstBlock + 1 &&
                    ranges.any { r -> r.first > page.firstLine && cut > r.first && cut <= r.last && rowH(r) > contentH }
                ) {
                    sawOversizedCardTear = true
                }
            }
        }

        assertTrue("用例空跑：没有任何页切点落在跨窗 oversized avoid 卡片内部", sawOversizedCardTear)
    }
}
