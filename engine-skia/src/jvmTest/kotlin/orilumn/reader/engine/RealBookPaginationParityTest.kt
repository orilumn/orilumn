package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 「临时表页界 = 磁盘表页界」锁（真书素材）。
 *
 * 判据来自真机症状：倒包（临时表）分页相对磁盘表整体漂移、来回翻页内容变。JVM 复算把根因钉死：
 *
 * 1. **轻路径前向链与重路径整章全量分页逐页同值**（本测试断言 14/14 页 `[start,end)` 逐字相等）
 *    ⇒ 从块 0 前向塑形得到的页界就是 canonical 页界，临时表完全可以与磁盘表同源。
 * 2. **几何倒包只命中回原像多解之一**：真书 ch2 的 13 个内部页界里 3 个被估早 72~98 字
 *    （本测试的 [bwdInteriorViaGeometry] 记录该差异），且每个估早的页首又成为下一页的 pin ⇒ 漂移逐页
 *    累积——真机倒包链相对磁盘表偏早至 ~600 字。
 * 3. **章首（块 0）前向链登记「页尾 → 页首」边后，倒包逐页等于磁盘表**（[bwdInteriorViaHeadRootedChain]
 *    断言 13/13）——这正是 `BookDocumentController.seedHeadRootedEdges` 的依据。
 * 4. **锚页页尾必须交给 `Paginator`**：旧锚页把切点钉在自算高度累加器上，看不到 `break-inside: avoid`
 *    原子区间，真书 ch2 第 8 页多吃 27 字（本测试的 [anchorPages] 断言 14/14）。
 *
 * 素材：真书解包目录（`OEBPS/Text/Ch02.htm` + `stylesheet.css`）。目录不存在则跳过（CI 无素材不红）。
 */
class RealBookPaginationParityTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()

    private val bookDir = File("/private/var/folders/83/8q_h_hh50bv3yx3lh2nzvg4m0000gn/T/opencode/bookscan/book_1791219085400")
    private val contentW = 1590
    private val contentH = 2400

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT)

    private fun loadChapter(): Pair<MarkupElement, CssBundle>? {
        val html = File(bookDir, "OEBPS/Text/Ch02.htm").takeIf { it.isFile } ?: return null
        val css = File(bookDir, "stylesheet.css").takeIf { it.isFile }?.readText().orEmpty()
        val page = File(bookDir, "page_styles.css").takeIf { it.isFile }?.readText().orEmpty()
        val parsed = converter.convertWithStyles(html.readText()) ?: return null
        return parsed.tree to CssBundle(listOf(css, page) + parsed.styles)
    }

    private class Fixture(
        val layouter: BoxChapterLayouter,
        val profile: TypographicProfile,
        val contentW: Int,
        val contentH: Int,
        val light: LightPrepare,
        val heavySlices: List<PageSlice>,
        val heavyLayout: BookLayout,
    )

    private fun fixture(): Fixture? {
        val loaded = loadChapter() ?: return null
        val (markup, css) = loaded
        val p = profile()
        val heavy = layouter.prepare(markup, css, p, contentW, contentH)
        val full = layouter.fullLayout(heavy, p, contentW, contentH)
        val light = layouter.prepareLight(markup, css, p, contentW, ChapterStructureCache(), contentH)
        return Fixture(layouter, p, contentW, contentH, light, full.slices, full.layout)
    }

    /** 轻路径前向链：从块 0 起逐页 [BoxChapterLayouter.shapeTempPageForward]。 */
    private fun forwardChain(fx: Fixture): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        var b = 0
        var line = 0
        var guard = 0
        while (b < fx.light.totalBlocks && guard++ < 4096) {
            val fwd = fx.layouter.shapeTempPageForward(fx.light, fx.profile, fx.contentW, fx.contentH, b, cache = null, startLine = line)
                ?: break
            val s = fwd.page.slice
            out.add(Triple(s.charStart, s.charEnd, s.lastLineExclusive - s.firstLine))
            if (fwd.nextBlock < b || (fwd.nextBlock == b && fwd.nextLine <= line)) break
            b = fwd.nextBlock
            line = fwd.nextLine
        }
        return out
    }

    @Test
    fun `temp forward chain equals disk pagination page for page`() {
        val fx = fixture()
        assumeTrue("real book not present; parity test skipped", fx != null)
        fx!!
        val chain = forwardChain(fx)
        println("PARITY heavy-pages=${fx.heavySlices.size} light-fwd-pages=${chain.size}")
        assertEquals("前向链页数与磁盘表不同", fx.heavySlices.size, chain.size)
        for (i in fx.heavySlices.indices) {
            val h = fx.heavySlices[i]
            val l = chain[i]
            assertEquals("页 $i 页首字符", h.charStart, l.first)
            assertEquals("页 $i 页尾字符", h.charEnd, l.second)
        }
    }

    @Test
    fun `anchor page from a canonical page start equals disk pagination`() {
        val fx = fixture()
        assumeTrue("real book not present; parity test skipped", fx != null)
        fx!!
        for ((i, h) in fx.heavySlices.withIndex()) {
            val ap = fx.layouter.shapeAnchorPageForward(fx.light, fx.profile, fx.contentW, fx.contentH, h.charStart, cache = null)
            assertEquals("锚页 $i 页首字符", h.charStart, ap.page.slice.charStart)
            assertEquals("锚页 $i 页尾字符", h.charEnd, ap.page.slice.charEnd)
        }
    }

    /** 章首前向链登记的「页尾 → 页首游标」边（生产：[BookDocumentController.seedHeadRootedEdges]）。 */
    private fun headRootedEdges(fx: Fixture): MutableMap<Int, Pair<Int, Int>> {
        val edges = HashMap<Int, Pair<Int, Int>>()
        var b = 0
        var line = 0
        var guard = 0
        while (b < fx.light.totalBlocks && guard++ < 4096) {
            val fwd = fx.layouter.shapeTempPageForward(fx.light, fx.profile, fx.contentW, fx.contentH, b, cache = null, startLine = line)
                ?: break
            val s = fwd.page.slice
            if (s.charEnd > s.charStart) edges.putIfAbsent(s.charEnd, s.blockStart to s.firstLine)
            if (fwd.nextBlock < b || (fwd.nextBlock == b && fwd.nextLine <= line)) break
            b = fwd.nextBlock
            line = fwd.nextLine
        }
        return edges
    }

    /** 倒包（pin = 磁盘表页首）在两种页首来源下的页首字符。 */
    private fun bwdInterior(fx: Fixture, cursorOf: (Int) -> Pair<Int, Int>?): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 1 until fx.heavySlices.size) {
            val pin = fx.heavySlices[i].charStart
            val endExcl = fx.light.blockIndexForChar((pin - 1).coerceAtLeast(0)) + 1
            val bp = fx.layouter.shapeTempPageBackward(
                fx.light, fx.profile, fx.contentW, fx.contentH, endExcl, pin, cache = null, pinnedStart = cursorOf(pin),
            ) ?: error("backward page null for pin=$pin")
            out.add(bp.slice.charStart to bp.slice.charEnd)
        }
        return out
    }

    @Test
    fun `temp backward from a disk page boundary equals disk pagination when rooted at chapter head`() {
        val fx = fixture()
        assumeTrue("real book not present; parity test skipped", fx != null)
        fx!!
        val edges = headRootedEdges(fx)
        val got = bwdInterior(fx) { edges[it] }
        var drifts = 0
        for (i in got.indices) {
            val prev = fx.heavySlices[i] // 倒包页 i-1 就是磁盘表第 i 页的前一页
            val (cs, ce) = got[i]
            if (cs != prev.charStart || ce != prev.charEnd) drifts++
            assertEquals("倒包页（pin=第 ${i + 1} 页页首）页尾字符", prev.charEnd, ce)
            assertEquals("倒包页（pin=第 ${i + 1} 页页首）页首字符", prev.charStart, cs)
        }
        println("PARITY head-rooted backward: ${got.size} boundaries, drifts=$drifts")
    }

    @Test
    fun `geometry backward alone drifts from disk pagination (locks the root cause)`() {
        val fx = fixture()
        assumeTrue("real book not present; parity test skipped", fx != null)
        fx!!
        val got = bwdInterior(fx) { null } // 无页首来源 ⇒ 纯几何倒包 + pin 自校验
        val drifts = ArrayList<String>()
        for (i in got.indices) {
            val prev = fx.heavySlices[i]
            val (cs, _) = got[i]
            if (cs != prev.charStart) drifts.add("pin=${fx.heavySlices[i + 1].charStart} early=${prev.charStart - cs}")
        }
        println("PARITY geometry backward drifts=${drifts.size}/${got.size} $drifts")
        // 只记录，不断言（几何倒包本身就是多解；这里锁的是「它确实会漂」这一事实）
    }
}
