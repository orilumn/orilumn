package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版层（上）· 倒包页 tiling 不变量锁（`shapeTempPageBackward`）。
 *
 * 真机症状「来回翻页内容变了」的根因：倒包页**没有恰好收到 pin**（pin = 相邻前向页的页首字符）。
 * 少了 ⇒ 两页之间那段字符无人显示（丢字），多了 ⇒ 两页重叠，两种都会让随后前向重建从错位原点起塑，
 * 页界整条链跟着漂。锁两条：
 *
 *  1. **tiling**：任何边界上，倒包页 `slice.charEnd` 必须**恒等于** pin；且页高不溢出 `contentH`。
 *     这条由 [orilumn.reader.engine.BoxChapterLayouter.shapeTempPageBackward] 内的 pin 自校验兜底
 *     （几何倒包估计失配时按 `Paginator` 反查修正），与累加器是否精确无关。
 *  2. **链边缓存页首**：给定本会话前向链登记过的游标（`prevStart`）时，倒包页必须与读者刚看过的前
 *     一页**逐字一致**（`charStart` + `charEnd`）。这是「同一翻页手势永远给出同一页」的保证。
 *     （本锁只测**临时表自己**的 tiling 与游标命中。不与磁盘表做任何对比 —— 两表互斥、各自独立，
 *     见 AGENTS.md「磁盘表与临时表互斥」。）
 *
 * 合成书特意混入 `pre { padding-bottom }`（`ownBottomEdges > 0` 的非文本块）：页内**每一块**的自身
 * 下边都落在本页帧内，漏记任一块都会让倒包起点过早、页尾收不到 pin。
 */
class BackwardPageTilingTest {

    private val contentW = 600

    /** 小页 ⇒ 每页只装几段，块边界多、块内切页多，累加/游标口径的偏差会被放大成整行差。 */
    private val contentH = 420

    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    private val css = CssBundle(
        listOf(
            """
            body { margin: 0; padding: 0; }
            p {
              display: block;
              margin: 30px 0;
              padding: 0;
              border: 0;
              font-size: 1em;
              line-height: 1.5;
              text-align: justify;
            }
            pre {
              display: block;
              margin: 12px 0;
              padding: 0 0 40px 0;
              border: 0;
              font-size: 0.95em;
              line-height: 1.4;
              white-space: pre-wrap;
            }
            """.trimIndent(),
        ),
    )

    private fun markup(): MarkupElement {
        val body = buildString {
            for (i in 1..24) {
                append("<p>第${i}段：把每一行高度加起来，尽可能填满页面高度空间，但又不溢出。</p>")
                if (i % 3 == 0) {
                    append("<pre>代码块 $i\nval x$i = ${i} * 2\nprintln(x$i)</pre>")
                }
            }
        }
        return HtmlTreeConverter().convert("<html><body>$body</body></html>")!!
    }

    @Test
    fun `every backward page tiles at its pin and reproduces the chain-edge start`() {
        val layouter = BoxChapterLayouter()
        val markup = markup()
        // 只看临时表自己（不与磁盘表对比，见 AGENTS.md「磁盘表与临时表互斥」）。
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)

        // 前向链（镜像读者从块 0 起的前向铺页），同时登记链边缓存 `页尾 → 页首游标`。
        val cache = HashMap<Int, ParagraphShapeRef>()
        val fwd = ArrayList<TempPage>()
        val edges = HashMap<Int, Pair<Int, Int>>()
        var sb = 0
        var sl = 0
        while (fwd.size < 400) {
            val fp = layouter.shapeTempPageForward(light, profile, contentW, contentH, sb, cache, sl) ?: break
            val s = fp.page.slice
            fwd.add(fp.page)
            if (s.charEnd > s.charStart) edges[s.charEnd] = s.blockStart to s.firstLine
            if (s.charEnd >= light.totalChars) break
            if (fp.nextBlock == sb && fp.nextLine == sl) break
            sb = fp.nextBlock
            sl = fp.nextLine
        }
        assertTrue("前向链应有多页", fwd.size >= 3)

        // 前向链自身：首尾相接、页高不超版心（不引用任何另一张表）。
        for (i in 1 until fwd.size) {
            assertEquals(
                "fwd[$i] 必须与上一页首尾相接（不丢不重）",
                fwd[i - 1].slice.charEnd, fwd[i].slice.charStart,
            )
        }
        for ((i, p) in fwd.withIndex()) {
            val span = p.layout.getPageFrameBottom(p.slice.lastLineExclusive - 1) -
                p.layout.getPageFrameTop(p.slice.firstLine)
            assertTrue("fwd[$i] 页高 $span 超出 $contentH", span <= contentH)
        }

        var midBlockStarts = 0
        var edgeHits = 0
        for (i in 1 until fwd.size) {
            val next = fwd[i]
            val pinBlock = next.blockStart
            val pinCharStart = next.slice.charStart
            val pinBlockCharStart = light.globalCharStarts[pinBlock].toInt()
            // lineCut = 页首块内行切时的切点字符；-1 = 块边界收页（pin = 该块首字）。
            val lineCut = if (pinCharStart > pinBlockCharStart) pinCharStart else -1
            val endExcl = if (lineCut >= 0) pinBlock + 1 else pinBlock
            val pin = if (lineCut >= 0) lineCut
            else light.globalCharStarts.getOrElse(endExcl) { light.totalChars.toLong() }.toInt()
            val want = fwd[i - 1]

            // ① 几何倒包（无权威游标）：页尾必须恰好收到 pin，且页高不溢出。
            val geo = layouter.shapeTempPageBackward(light, profile, contentW, contentH, endExcl, lineCut, cache)
                ?: error("bwd[$i] 几何倒包失败 endExcl=$endExcl lineCut=$lineCut")
            assertEquals("bwd[$i] 页尾必须恰好收到 pin（丢字/重叠都会让链漂）", pin, geo.slice.charEnd)
            val span = geo.layout.getPageFrameBottom(geo.slice.lastLineExclusive - 1) -
                geo.layout.getPageFrameTop(geo.slice.firstLine)
            assertTrue("bwd[$i] 倒包页不得溢出 contentH span=$span", span <= contentH)
            if (geo.slice.firstLine > 0) midBlockStarts++

            // ② 链边缓存页首（`prevStart`）：回翻必须逐字回到读者刚看过的那一页。
            val edge = edges[pin]
            if (edge != null) {
                edgeHits++
                val pinned = layouter.shapeTempPageBackward(
                    light, profile, contentW, contentH, endExcl, lineCut, cache, edge,
                ) ?: error("bwd[$i] 链边缓存页首组装失败 edge=$edge")
                assertEquals("bwd[$i] 链边页首 charStart 必须与前一页一致", want.slice.charStart, pinned.slice.charStart)
                assertEquals("bwd[$i] 链边页首 charEnd 必须与前一页一致", want.slice.charEnd, pinned.slice.charEnd)
            }
        }
        assertTrue("合成书必须出现块内切页（页首游标路径要真被走到）", midBlockStarts >= 1)
        assertTrue("链边缓存必须命中（回翻第一页靠它逐字复现读者见过的页）", edgeHits >= 1)
    }

    /**
     * 章中起锚（TOC / 搜索跳转）的临时链**没有**磁盘表/正典可依——章内也**不该**去读（两份分页本章
     * 内允许不同，只在章首合一）。但临时链必须自洽且稳定：每步页尾恰好收在 pin、页与页首尾相接
     * （无空洞/无重叠）、页高不溢出，且同一手势重复倒包逐字同页（不因后台正典何时算完而变）。
     *
     * 这条是旧口径「临时页必须等于正典」的替代：自洽 + 稳定才是临时表的义务。删掉它就会退回到
     * 「倒包页多收一行 → 页尾收不到 pin → 前向从错位原点重建」那个真机 bug。
     */
    @Test
    fun `mid-chapter backward chain tiles and is stable without any canonical input`() {
        val layouter = BoxChapterLayouter()
        val markup = markup()
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val cache = HashMap<Int, ParagraphShapeRef>()

        // 先铺前向，取中段一页当倒包的起点（模拟跳转落点）。
        var sb = 0
        var sl = 0
        val fwd = ArrayList<TempPage>()
        while (fwd.size < 200) {
            val fp = layouter.shapeTempPageForward(light, profile, contentW, contentH, sb, cache, sl) ?: break
            fwd.add(fp.page)
            if (fp.page.slice.charEnd >= light.totalChars) break
            if (fp.nextBlock == sb && fp.nextLine == sl) break
            sb = fp.nextBlock
            sl = fp.nextLine
        }
        assertTrue("前向链应有多页（否则无中段可取）", fwd.size >= 4)
        val mid = fwd[fwd.size / 2]

        // 从 mid 起往回倒包，**不给 pinnedStart**（无任何权威）——只用临时表自己的几何规则。
        fun walkBack(): ArrayList<TempPage> {
            val out = ArrayList<TempPage>()
            var pinBlock = mid.blockStart
            var pinCharStart = mid.slice.charStart
            while (out.size < 200) {
                val pinBlockCharStart = light.globalCharStarts[pinBlock].toInt()
                val lineCut = if (pinCharStart > pinBlockCharStart) pinCharStart else -1
                val endExcl = if (lineCut >= 0) pinBlock + 1 else pinBlock
                val pin = if (lineCut >= 0) lineCut
                else light.globalCharStarts.getOrElse(endExcl) { light.totalChars.toLong() }.toInt()
                val page = layouter.shapeTempPageBackward(
                    light, profile, contentW, contentH, endExcl, lineCut, cache, null,
                ) ?: break // 章首：无处可倒
                assertEquals("bwd pin=$pin 页尾必须恰好收在 pin", pin, page.slice.charEnd)
                val span = page.layout.getPageFrameBottom(page.slice.lastLineExclusive - 1) -
                    page.layout.getPageFrameTop(page.slice.firstLine)
                assertTrue("倒包页不得溢出 contentH span=$span", span <= contentH)
                out.add(page)
                if (page.blockStart == 0) break
                pinBlock = page.blockStart
                pinCharStart = page.slice.charStart
            }
            return out
        }

        val back = walkBack()
        assertTrue("中段起锚至少倒出 2 页（否则本锁失效）", back.size >= 2)
        for (j in 1 until back.size) {
            assertEquals(
                "倒包链必须首尾相接（back[$j] 页尾接 back[${j - 1}] 页首）",
                back[j - 1].slice.charStart, back[j].slice.charEnd,
            )
        }
        assertEquals(
            "同一手势重复倒包必须逐字同页（稳定：章内不受后台正典何时算完影响）",
            back.map { it.slice }, walkBack().map { it.slice },
        )
    }
}
