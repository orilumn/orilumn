package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BoxBookLayout
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非回归锁：**页面绘制帧不得重复收块边界缺口、不得超出页高**（`BookLayout.getPageFrameTop/Bottom`）。
 *
 * ## 口径（v3：一条缺口不许两页各收一次）
 *
 * `frameTop(i) >= frameBottom(i-1)`、`frameBottom(i) = 行底(i) + 页尾叶自身边`。块边界缺口里
 * **页尾叶自身边**归上一页页尾、**下叶自身 top 边**（`padding-top/border-top`）归下一页页首；
 * 而下叶的折叠 `margin-top` **并入页边距**（v3，两页都不收）——只有**章首**首块的 `margin-top`
 * 例外，照画在章首页页首。
 *
 * 旧口径 `bottom(i)=lineTop(i+1)` ＋ `top(i+1)=lineBottom(i)` 把同一段缺口两页各收一次 ⇒ 每页白丢
 * 一行（真机：「明明还有空间，最后一行却被推到下一页」），且绘制侧把整页从行顶对齐时预留的缺口
 * 留成页**底**空白、页首块的 `border/padding` 反而在栅格上沿被裁。
 *
 * ## 锁法
 *
 * 1. **不许重复收**：相邻两页 `frameBottom(前页末行) <= frameTop(后页首行)`；两者之差恰为被页边距
 *    吞掉的那段折叠 `margin-top`（0 = 段中开页/无段前距）。旧口径下本页帧顶**低于**前页帧底，必红。
 * 2. **不超页高**：每页 `frameBottom(末行) - frameTop(首行) ≤ contentH` —— 绘制栅格恰为
 *    `contentH`，超了就是末行被裁（真机修前 `extent=2475 > 2400`）。重路径与轻路径（临时页）
 *    都要过这一条，两路坐标系不同故各自用自身布局量。
 * 3. 反空跑：扫描中必须真的出现过「页首缺口 > 0」「页尾叶自身边 > 0」与「段前距被吞（差值 > 0）」，
 *    否则 fixture 没量到东西。
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

    private fun canonical(markup: MarkupElement, contentH: Int, css: CssBundle = this.css): ChapterLayouter.ChapterLayoutProduct {
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        return layouter.fullLayout(heavy, profile, contentW, contentH)
    }

    private fun tempForward(markup: MarkupElement, contentH: Int, css: CssBundle = this.css): List<Pair<PageSlice, BookLayout>> {
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
        var sawDrop = 0
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
                    // 1. 不许重复收：本页帧顶**不得低于**前页帧底（旧口径下差值 = 整段缺口，必红）。
                    assertTrue(
                        "contentH=$contentH canonical 第 ${i - 1}→$i 页帧重复收缺口（本页帧顶 $top < 前页帧底 $prevBot）",
                        top >= prevBot,
                    )
                    // 差值只能是被页边距吞掉的段前距（= 行顶 − 页首自身 top 边 − 前页帧底，下限 0）。
                    assertEquals(
                        "contentH=$contentH canonical 第 $i 页帧顶与页首缺口的单源式不一致",
                        (layout.getLineTop(first) - layout.getPageTopLead(first) - prevBot).coerceAtLeast(0),
                        top - prevBot,
                    )
                    if (top > prevBot) sawDrop++
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
        assertTrue("扫描中从未出现「段前距被吞」（frameTop > 前页 frameBottom）⇒ v3 口径没被量到", sawDrop > 0)
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

    /**
     * v3 口径直测（期望值来自 fixture，不来自实现式）：页首块的折叠 `margin-top` **并入页边距**
     * （两页都不画），本块**自身** `padding-top` 仍画在本页页首；**章首**第一个块的 `margin-top`
     * 例外，照画在章首页页首。
     *
     * fixture：每段 `margin: 100px 0; padding: 40px 0` ⇒ 页首落在**段首**（非章首）时缺口必须恰为
     * `padding-top` = 40；落在**章首**时必须为 40 + 100 = 140；落在**段中**时为 0。重/轻两条路径
     * 各自量自己的布局（坐标系不同，但页首缺口同值）。
     */
    @Test
    fun `page top block merges margin but keeps padding, chapter head keeps margin`() {
        val v3css = CssBundle(
            listOf(
                "body { font-size: 16px; line-height: 1.5; margin: 0; } p { margin: 100px 0; padding: 40px 0; }",
            ),
        )
        val markup = converter.convert(
            """
            <html><body>
              ${(1..8).joinToString("\n") { "<p>第${it}段正文。".repeat(1) + "正文内容。".repeat(20) + "</p>" }}
            </body></html>
            """.trimIndent(),
        )!!
        val paddingTop = 40
        val chapterHeadLead = 140
        var sawBlockStart = 0
        var sawChapterHead = 0
        var sawMidBlock = 0
        for (contentH in 100..300 step 8) {
            val canon = canonical(markup, contentH, v3css)
            val layout = canon.layout
            for (s in canon.slices) {
                val lead = layout.getLineTop(s.firstLine) - layout.getPageFrameTop(s.firstLine)
                val expect = when {
                    s.charStart == 0 -> chapterHeadLead
                    layout.isParagraphBoundaryLine(s.firstLine) -> paddingTop
                    else -> 0
                }
                assertEquals(
                    "contentH=$contentH canonical 首行=${s.firstLine} 页首缺口（段首该留 padding、不留折叠 margin；章首反而要留 margin）",
                    expect, lead,
                )
                if (s.charStart == 0) sawChapterHead++
                else if (expect == paddingTop) sawBlockStart++
                else sawMidBlock++
            }
            for ((s, lay) in tempForward(markup, contentH, v3css)) {
                val lead = lay.getLineTop(s.firstLine) - lay.getPageFrameTop(s.firstLine)
                val expect = when {
                    s.charStart == 0 -> chapterHeadLead
                    lay.isParagraphBoundaryLine(s.firstLine) -> paddingTop
                    else -> 0
                }
                assertEquals(
                    "contentH=$contentH 临时页首行=${s.firstLine} 页首缺口（重/轻路径必须同值）",
                    expect, lead,
                )
            }
        }
        assertTrue("从未出现章首开页 ⇒ 用例空跑", sawChapterHead > 0)
        assertTrue("从未出现段首开页（缺口 = padding-top）⇒ 用例空跑", sawBlockStart > 0)
        assertTrue("从未出现段中开页（缺口 0）⇒ 用例空跑", sawMidBlock > 0)
    }

    /** 布局级反空跑：存在某行 `frameBottom > 行底`，即页尾叶自身 `padding-bottom/border-bottom` 被量到。 */
    private fun BookLayout.hasLineWithTailEdges(): Boolean =
        (0 until lineCount).any { getPageFrameBottom(it) > getLineBottom(it) }

    /**
     * v4 口径直测（期望值来自 fixture 样式，不来自实现式）：页首叶的**可见外沿盒**（背景色/背景图/
     * 边框/盒阴影 —— 与绘制侧 `hasPaintedSlab` 同判据）**之内**的首子 `margin-top` 不并入页边距。
     *
     * fixture（真书 `div.fm-sidebar-block` 形）：
     * `div.sidebar{background:#eee; padding:10px 20px}` 的首子
     * `p.title{margin:30px 0; padding-top:15px}` ⇒ 页首落在 title 段首时缺口 =
     * `10（容器 padding-top） + 30（首子 margin-top，**保留**） + 15（首子 padding-top） = 55`。
     * 同一 fixture 里 `p.plain{margin:100px 0; padding:40px 0}` 直挂 `<body>`（无可见盒）落页首时，
     * 段前距整段并入页边距，只留自身 `padding-top` = 40（v3 不变）。
     *
     * 直取两路（重 = `BoxBookLayout`，轻 = `shapeTempPageForward` 从该块开盘）避免依赖「扫描恰好
     * 在 title 处断页」——那是概率事件，锁不住。
     */
    @Test
    fun `painted container keeps its first child margin at page top`() {
        val css4 = CssBundle(
            listOf(
                """
                body { font-size: 16px; line-height: 1.5; margin: 0; }
                p { margin: 100px 0; padding: 40px 0; }
                div.sidebar { background: #eee; padding: 10px 20px; }
                p.title { margin: 30px 0; padding-top: 15px; }
                """.trimIndent(),
            ),
        )
        val markup = converter.convert(
            """
            <html><body>
              <p class="head">章首段。正文内容多一些以便分页。${"正文内容。".repeat(30)}</p>
              <div class="sidebar">
                <p class="title">枚举是软关键字</p>
                <p>侧栏第二段。${"正文内容。".repeat(20)}</p>
                <p>侧栏第三段。${"正文内容。".repeat(20)}</p>
              </div>
              ${(1..6).joinToString("\n") { "<p class=\"plain\">第${it}段。正文内容多一些以便分页。${"正文内容。".repeat(30)}</p>" }}
            </body></html>
            """.trimIndent(),
        )!!

        // ---- 重路径：直接从盒树取叶首行，量 getPageTopLead ----
        val heavy = layouter.prepare(markup, css4, profile, contentW, 900)
        val book = BoxBookLayout(heavy.structure)
        val titleEl = findByClass(markup, "title")!!
        val titleLine = heavy.leaves.first { it.el === titleEl }.firstLineIndex
        assertTrue("title 叶没有行 ⇒ fixture 没量到东西", titleLine > 0)
        assertEquals("canonical：可见外沿盒内首子 margin-top 必须保留", 55, book.getPageTopLead(titleLine))
        val plainLine = heavy.leaves.first { it.el === findByClass(markup, "plain") }.firstLineIndex
        assertEquals("canonical：无可见盒的普通段仍按 v3 只留自身 padding-top", 40, book.getPageTopLead(plainLine))

        // ---- 轻路径：从 title 块开盘，页首缺口 = 行顶 − 帧顶 ----
        val light = layouter.prepareLight(markup, css4, profile, contentW, ChapterStructureCache(), 900)
        var titleBlock = -1
        for (i in 0 until light.totalBlocks) if (light.block(i).el === titleEl) { titleBlock = i; break }
        assertTrue("轻路径找不到 title 块 ⇒ fixture 没量到东西", titleBlock > 0)
        val fwd = layouter.shapeTempPageForward(light, profile, contentW, 900, titleBlock, cache = null, startLine = 0)!!
        val s = fwd.page.slice
        val lay = fwd.page.layout
        assertEquals(
            "temp：与 canonical 的可见外沿盒缺口必须同值",
            55, lay.getLineTop(s.firstLine) - lay.getPageFrameTop(s.firstLine),
        )
    }

    /**
     * v4 扫描锁：本 fixture 下**所有**页首缺口只能是 fixture 派生的四个值 ——
     * `0`（段中开页）、`40`（普通段首：自身 `padding-top`，段前距并入页边距）、
     * `55`（可见外沿盒内首子：容器 `padding-top` 10 + 首子 `margin-top` 30 + 首子 `padding-top` 15）、
     * `140`（章首：自身 `padding-top` 40 + 自身 `margin-top` 100）。重/轻两路同口径、同取值域。
     */
    @Test
    fun `painted container scan keeps only fixture derived leads`() {
        val css4 = CssBundle(
            listOf(
                """
                body { font-size: 16px; line-height: 1.5; margin: 0; }
                p { margin: 100px 0; padding: 40px 0; }
                div.sidebar { background: #eee; padding: 10px 20px; }
                p.title { margin: 30px 0; padding-top: 15px; }
                """.trimIndent(),
            ),
        )
        val markup = converter.convert(
            """
            <html><body>
              <p>章首段。正文内容多一些以便分页。${"正文内容。".repeat(30)}</p>
              <div class="sidebar">
                <p class="title">枚举是软关键字</p>
                <p>侧栏第二段。${"正文内容。".repeat(20)}</p>
                <p>侧栏第三段。${"正文内容。".repeat(20)}</p>
              </div>
              ${(1..6).joinToString("\n") { "<p>第${it}段。正文内容多一些以便分页。${"正文内容。".repeat(30)}</p>" }}
            </body></html>
            """.trimIndent(),
        )!!
        val allowed = setOf(0, 40, 55, 140)
        var sawPlainStart = 0
        var sawChapterHead = 0
        var sawMidBlock = 0
        for (contentH in 100..420 step 7) {
            val canon = canonical(markup, contentH, css4)
            for (s in canon.slices) {
                val lead = canon.layout.getLineTop(s.firstLine) - canon.layout.getPageFrameTop(s.firstLine)
                assertTrue(
                    "contentH=$contentH canonical 首行=${s.firstLine} 页首缺口 $lead 不在 fixture 派生取值域 $allowed 内",
                    lead in allowed,
                )
                if (s.charStart == 0) sawChapterHead++ else if (lead == 40) sawPlainStart++ else if (lead == 0) sawMidBlock++
            }
            for ((s, lay) in tempForward(markup, contentH, css4)) {
                val lead = lay.getLineTop(s.firstLine) - lay.getPageFrameTop(s.firstLine)
                assertTrue(
                    "contentH=$contentH 临时页首行=${s.firstLine} 页首缺口 $lead 不在 fixture 派生取值域 $allowed 内",
                    lead in allowed,
                )
            }
        }
        assertTrue("从未出现章首开页 ⇒ 用例空跑", sawChapterHead > 0)
        assertTrue("从未出现普通段首开页（缺口 = 自身 padding-top）⇒ 用例空跑", sawPlainStart > 0)
        assertTrue("从未出现段中开页 ⇒ 用例空跑", sawMidBlock > 0)
    }

    /** 首个带 [cls] 的元素（fixture 定位用；不依赖实现式）。 */
    private fun findByClass(root: MarkupElement, cls: String): MarkupElement? {
        if (root.attrs["class"]?.split(' ')?.contains(cls) == true) return root
        for (c in root.children) findByClass(c, cls)?.let { return it }
        return null
    }

    /**
     * v4 重/轻同值扫描锁：同一 fixture、同一 `contentH` 下两路**逐页**页首缺口序列必须相等
     * （[orilumn.reader.engine.paging.BookLayout.getPageTopLead] 的重（`BoxBookLayout` 盒树）与轻
     * （`BoxChapterLayouter.leadAbove`/`windowLeadTop`）两套实现同口径 —— 不同值就是「调参后页数
     * 对不上」）。页数/序列一致顺带锁住两路分页同切。
     */
    @Test
    fun `page top lead agrees between heavy and light`() {
        val css4 = CssBundle(
            listOf(
                """
                body { font-size: 16px; line-height: 1.5; margin: 0; }
                p { margin: 100px 0; padding: 40px 0; }
                div.sidebar { background: #eee; padding: 10px 20px; }
                p.title { margin: 30px 0; padding-top: 15px; }
                """.trimIndent(),
            ),
        )
        val markup = converter.convert(
            """
            <html><body>
              <p>章首段。正文内容多一些以便分页。${"正文内容。".repeat(30)}</p>
              <div class="sidebar">
                <p class="title">枚举是软关键字</p>
                <p>侧栏第二段。${"正文内容。".repeat(20)}</p>
                <p>侧栏第三段。${"正文内容。".repeat(20)}</p>
              </div>
              ${(1..6).joinToString("\n") { "<p>第${it}段。正文内容多一些以便分页。${"正文内容。".repeat(30)}</p>" }}
            </body></html>
            """.trimIndent(),
        )!!
        var seenTitleLead = 0
        for (contentH in 150..420 step 9) {
            val canon = canonical(markup, contentH, css4)
            val heavyLeads = canon.slices.map { canon.layout.getLineTop(it.firstLine) - canon.layout.getPageFrameTop(it.firstLine) }
            val lightLeads = tempForward(markup, contentH, css4).map { (s, lay) ->
                lay.getLineTop(s.firstLine) - lay.getPageFrameTop(s.firstLine)
            }
            assertEquals("contentH=$contentH 两路页首缺口序列必须同值（页数/切点同源）", heavyLeads, lightLeads)
            seenTitleLead += heavyLeads.count { it == 55 }
        }
        assertTrue("扫描中从未出现可见外沿盒内首子开页（缺口 55）⇒ 用例空跑", seenTitleLead > 0)
    }
}
