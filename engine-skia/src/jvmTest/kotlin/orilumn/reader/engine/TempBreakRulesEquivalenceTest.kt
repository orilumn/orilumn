package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BreakAwareBookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 非回归锁：**临时页填充**（`prepareLight` + `shapeTempPageForward/Backward`，锚章在磁盘表就绪前
 * 的临时表）与 **canonical 全章分页**（`prepare` + `fullLayout` → `Paginator` → 磁盘表）对断页规则
 * 给出**同一组页边界**。
 *
 * ## 为什么必须等价
 *
 * AGENTS.md 分页调度：本章内**始终用临时表**，只在翻回章开头且磁盘表已就绪时才切到磁盘表（直接
 * 显示磁盘表对应页），离开本章则废弃临时表。若两表对同一条 `break-*` 规则给出不同边界，切换瞬间
 * 就会「跳页」——用户翻回章首时看到页内容整段位移。所以临时路径不能只「大概差不多」，必须与
 * canonical 逐页 `charStart/charEnd` 相等。
 *
 * ## 单源口径
 *
 * 两边**不是各写一份规则碰巧等价**，而是调用同一段代码：临时页在 `BoxChapterLayouter` 里先建出窗口
 * 布局（`PartialDrawableLayout`，本身实现 `BreakAwareBookLayout`，其簇/强制行来自单源
 * `BreakClusters.plan`），再交给 `Paginator.paginateFrom` 定页尾；`shapeTempPageForward` 只负责
 * 「圈窗口」（按行高累加）与沿 `BreakClusters.chainRange` 把窗口取整到 avoid 簇闭合——它不含任何
 * 分页判据。
 *
 * ## 前向为什么是「全等」、后向为什么只是「自身合规」
 *
 * 前向从章首整行填充，与 canonical 同一起点、同一填充式 ⇒ 页界**逐页相等**。
 * 后向是**从章末倒包**的独立岛（`BookDocumentController` 的 backward-packed island，见
 * `shapeNextBackward`），倒包与正包的页界本就不保证重合（10 行 / 每页 3 行：正包 [0,3)[3,6)[6,9)[9,10)，
 * 倒包 [7,10)[4,7)[1,4)[0,1)）。所以后向只锁**自身合规**：不得撕裂 avoid 簇、强制行必为页首、
 * 且整链无缝覆盖全章（无洞/无重叠）。
 */
class TempBreakRulesEquivalenceTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
    private val css = CssBundle(listOf(""))

    private fun body(text: String, times: Int) = text.repeat(times)

    /**
     * 一章同时含强制断页与 avoid 边簇，且夹在长段之间，保证扫描 `contentH` 时切点会落到：
     * 长段内部、强制段落之前、以及 avoid 簇内部。
     */
    private fun chapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <h2>第一章 断页</h2>
          <p>${body("路漫漫其修远兮，吾将上下而求索。", 12)}</p>
          <p style="page-break-before: always">${body("这一步必须另起一页。", 12)}</p>
          <p>${body("溯洄从之，道阻且长。", 12)}</p>
          <p style="page-break-after: avoid">图注上段（与下一段同页）。</p>
          <p>图注下段。</p>
          <p>${body("山重水复疑无路，柳暗花明又一村。", 12)}</p>
        </body></html>
        """.trimIndent(),
    )!!

    /**
     * 巨型 avoid 边簇（目录 / 前言类）：多个 `page-break-after: avoid` 段落串成一条**总高远过一页**
     * 的长链，链后接 `pre` 与长段。
     *
     * 这正是旧临时填充的原始现场：窗口在切点块处被截断，`BreakClusters.plan` 于是把整簇看成
     * 「装得下一页」而错误退簇 —— 表现为半页空白、`pre` 整块后移。窗口必须沿
     * `BreakClusters.chainRange` 补到簇闭合，页尾仍交 `Paginator`（整簇装不下 ⇒ 允许撕裂）。
     */
    private fun giantClusterChapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <h2>目录</h2>
          <p>${body("启程之前，先说清这册书怎么读。", 8)}</p>
          ${(1..7).joinToString("") { "<p style=\"page-break-after: avoid\">${body("第 $it 节 断页规则示例文字。", 18)}</p>" }}
          <pre>${body("缩进与空行都要保住的原始块。", 6)}</pre>
          <p>${body("尾声很长的一节，用来把后面的页填满。", 12)}</p>
        </body></html>
        """.trimIndent(),
    )!!

    /** canonical：`prepare`（重路径）+ `fullLayout`（`BoxDrawableLayout` + `Paginator`）→ 磁盘表页。 */
    private fun canonical(markup: MarkupElement, contentH: Int): ChapterLayouter.ChapterLayoutProduct {
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        return layouter.fullLayout(heavy, profile, contentW, contentH)
    }

    private fun prepareLight(markup: MarkupElement, contentH: Int): LightPrepare =
        layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)

    /** 临时路径前向：从 (0,0) 连续灌页，收集页切片（页序 = 阅读序）。 */
    private fun tempForward(markup: MarkupElement, contentH: Int): List<PageSlice> {
        val light = prepareLight(markup, contentH)
        val out = ArrayList<PageSlice>()
        var block = 0
        var line = 0
        var guard = 0
        while (block < light.totalBlocks && guard++ < 2000) {
            val fwd = layouter.shapeTempPageForward(
                light, profile, contentW, contentH, block, cache = null, startLine = line,
            ) ?: break
            out.add(fwd.page.slice)
            if (fwd.nextBlock >= light.totalBlocks) break
            block = fwd.nextBlock
            line = fwd.nextLine
        }
        return out
    }

    /**
     * 临时路径后向：从章末倒包到章首。链式口径与 `BookDocumentController.shapeNextBackward` 逐字一致
     * （`endExcl`/`lineCut` 由上一页的 `blockStart`/`charStart` 反推）。
     */
    private fun tempBackward(markup: MarkupElement, contentH: Int): List<PageSlice> {
        val light = prepareLight(markup, contentH)
        val out = ArrayList<PageSlice>()
        var endExcl = light.totalBlocks
        var lineCut = -1
        var guard = 0
        while (endExcl > 0 && guard++ < 2000) {
            val page = layouter.shapeTempPageBackward(
                light, profile, contentW, contentH, endExcl, lineCut, cache = null,
            ) ?: break
            out.add(0, page.slice)
            val blockStart = page.slice.blockStart
            val blockChar = light.globalCharStarts[blockStart].toInt()
            val lineChar = page.slice.charStart
            if (lineChar > blockChar) {
                endExcl = blockStart + 1
                lineCut = lineChar
            } else {
                endExcl = blockStart
                lineCut = -1
            }
        }
        return out
    }

    @Test
    fun `temp forward pages equal canonical pages under forced and avoid rules`() {
        val markup = chapter()
        var sawForcedPageStart = false
        var sawAvoidClusterBoundary = false

        for (contentH in 300..660 step 12) {
            val product = canonical(markup, contentH)
            val canon = product.slices
            val temp = tempForward(markup, contentH)
            val aware = product.layout as BreakAwareBookLayout

            // 1) 页数 + 逐页 charStart/charEnd 全等（页边界即「切片」的唯一定义）。
            assertEquals("contentH=$contentH page count", canon.size, temp.size)
            for (i in canon.indices) {
                assertEquals("contentH=$contentH page $i charStart", canon[i].charStart, temp[i].charStart)
                assertEquals("contentH=$contentH page $i charEnd", canon[i].charEnd, temp[i].charEnd)
            }

            // 2) canonical 自身的两条不变量。
            val pageStarts = canon.map { it.firstLine }.toSet()
            for (forcedLine in aware.forcedBreakBeforeLines) {
                assertTrue(
                    "contentH=$contentH forced line $forcedLine must start a page (starts=$pageStarts)",
                    forcedLine in pageStarts,
                )
                sawForcedPageStart = true
            }
            for ((i, p) in canon.withIndex()) {
                val cut = p.lastLineExclusive
                for (range in aware.avoidSplitRanges) {
                    assertTrue(
                        "contentH=$contentH page $i boundary $cut torn inside avoid range $range",
                        !(range.first < cut && cut < range.last + 1),
                    )
                }
                // 3) 触发检测：某页恰好收在某个 avoid 簇首（且不是页首本身）＝ 整簇顺延过。
                if (aware.avoidSplitRanges.any { it.first == cut && it.first > p.firstLine }) {
                    sawAvoidClusterBoundary = true
                }
            }
        }

        assertTrue("扫不到强制页首：本锁空跑", sawForcedPageStart)
        assertTrue("扫不到 avoid 簇首断页：本锁空跑", sawAvoidClusterBoundary)
    }

    /**
     * 巨型 avoid 簇锁：与 §「前向全等」同一条断言，但样本换成**簇长跨页**的目录链。
     * 若窗口不沿 `chainRange` 补到簇闭合，这里必然出现半页空白 / `pre` 整块后移。
     */
    @Test
    fun `giant avoid cluster keeps temp forward pages identical to canonical`() {
        val markup = giantClusterChapter()
        var sawClusterTear = false

        for (contentH in 300..700 step 20) {
            val product = canonical(markup, contentH)
            val canon = product.slices
            val temp = tempForward(markup, contentH)
            val aware = product.layout as BreakAwareBookLayout

            assertEquals("contentH=$contentH page count", canon.size, temp.size)
            for (i in canon.indices) {
                assertEquals("contentH=$contentH page $i charStart", canon[i].charStart, temp[i].charStart)
                assertEquals("contentH=$contentH page $i charEnd", canon[i].charEnd, temp[i].charEnd)
            }

            // canonical 自身不得撕裂「装得下」的簇；整簇装不下时允许撕裂（oversized 口径）。
            for ((i, p) in canon.withIndex()) {
                val cut = p.lastLineExclusive
                val torn = aware.avoidSplitRanges.filter { it.first < cut && cut < it.last + 1 }
                if (torn.isEmpty()) continue
                assertTrue(
                    "contentH=$contentH page $i torn inside avoid ranges that all fit one page: $torn",
                    torn.any { aware.getLineBottom(it.last) - aware.getLineTop(it.first) > contentH },
                )
                sawClusterTear = true
            }
        }

        // 本锁必须真的扫到「超页高的巨型簇被撕」——否则本锁空跑（小簇根本碰不到窗口截断坑）。
        assertTrue("语料没造出跨页的巨型 avoid 簇：本锁空跑", sawClusterTear)
    }

    @Test
    fun `temp backward pages honour forced and avoid rules while tiling the chapter`() {
        val markup = chapter()
        var sawAvoidBoundary = false
        var sawForcedPageStart = false

        for (contentH in 300..660 step 12) {
            val product = canonical(markup, contentH)
            val canon = product.slices
            val layout = product.layout
            val aware = layout as BreakAwareBookLayout
            val totalChars = canon.last().charEnd

            // 行空间 → 字符空间（后向页切片自带字符区间，判据在字符空间比）。
            fun lineCharStart(line: Int) = if (line >= layout.lineCount) totalChars else layout.getLineStart(line)
            val forcedChars = aware.forcedBreakBeforeLines.map { lineCharStart(it) }.toSet()
            val avoidChars = aware.avoidSplitRanges
                .map { lineCharStart(it.first) to lineCharStart(it.last + 1) }
                .filter { it.first < it.second }

            val pages = tempBackward(markup, contentH)
            assertTrue("contentH=$contentH backward must produce pages", pages.isNotEmpty())
            assertEquals("contentH=$contentH backward head charStart", 0, pages.first().charStart)
            assertEquals("contentH=$contentH backward tail charEnd", totalChars, pages.last().charEnd)
            for (i in 0 until pages.size - 1) {
                assertEquals(
                    "contentH=$contentH backward page $i→${i + 1} must tile without gap/overlap",
                    pages[i].charEnd, pages[i + 1].charStart,
                )
            }

            val backStarts = pages.map { it.charStart }.toSet()
            for (fc in forcedChars) {
                if (fc == 0) continue
                assertTrue(
                    "contentH=$contentH forced char $fc must start a backward page (starts=$backStarts)",
                    fc in backStarts,
                )
                sawForcedPageStart = true
            }
            for (p in pages) {
                for ((lo, hi) in avoidChars) {
                    assertTrue(
                        "contentH=$contentH backward boundary ${p.charEnd} torn inside avoid [$lo,$hi)",
                        !(lo < p.charEnd && p.charEnd < hi),
                    )
                }
                if (avoidChars.any { it.first == p.charEnd && p.charStart < it.first }) sawAvoidBoundary = true
            }
        }

        assertTrue("扫不到 avoid 簇首断页：本锁空跑", sawAvoidBoundary)
        assertTrue("扫不到强制页首：本锁空跑", sawForcedPageStart)
    }
}
