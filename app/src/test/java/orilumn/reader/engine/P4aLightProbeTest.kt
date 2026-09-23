package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.engine.layout.ParagraphShapes
import orilumn.reader.engine.text.FontPool
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Probe (P4-a3): 轻路径悬浮镜像与重路径逐字节一致。
 *
 * Locked in here (真实 Skia 断行，双路同 breaker）：
 *  1. `LightPrepare.floatLeadAt` 与重路径叶 `floatLead` 全章逐块相等；
 *  2. 逐块整形（`ParagraphShapes.shapeOf` + 前导）行区间/行高与重路径叶逐行相等；
 *  3. temp 前向流自 tiling（字符连续无洞）且含悬浮零高行、后段 clear 越位与重路径同值。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class P4aLightProbeTest {

    private lateinit var bc: BoxChapterLayouter
    private lateinit var profile: TypographicProfile
    private val pairing = FontPool()
    private val contentW = 300
    private val contentH = 4000

    @Before
    fun setUp() {
        bc = BoxChapterLayouter()
        profile = TypographicProfile.build(ReaderSettings.DEFAULT)
    }

    private fun chapter(floatStyle: String?): Triple<orilumn.reader.engine.html.MarkupElement, ChapterPrepareResult, LightPrepare> {
        val img = if (floatStyle == null) {
            "<img width=\"150\" height=\"200\" src=\"a.png\"/>"
        } else {
            "<img width=\"150\" height=\"200\" src=\"a.png\" style=\"float: left\"/>"
        }
        val html = "<html><body>$img<p>${"y".repeat(60)}</p><p>short</p></body></html>"
        val markup = HtmlTreeConverter().convert(html)!!
        val heavy = bc.prepare(markup, null, profile, contentW, contentH)
        val light = bc.prepareLight(markup, null, profile, contentW, ChapterStructureCache(), contentH)
        return Triple(markup, heavy, light)
    }

    @Test
    fun `floatLead parity heavy versus light per block`() {
        val (_, heavy, light) = chapter("float: left")
        assertEquals("same leaf count", heavy.totalBlocks, light.totalBlocks)
        for (i in 0 until heavy.totalBlocks) {
            assertTrue(
                "leaf $i identity",
                heavy.leaves[i].el === light.block(i).el,
            )
            assertEquals("leaf $i floatLead", heavy.leaves[i].floatLead, light.floatLeadAt(i))
        }
        assertTrue("wrapped leaf must carry a lead", heavy.leaves.any { it.floatLead != null })
    }

    @Test
    fun `shaping parity per block ranges and heights`() {
        val (_, heavy, light) = chapter("float: left")
        for (i in 0 until heavy.totalBlocks) {
            val box = light.block(i)
            val el = box.el!!
            val lightShape = ParagraphShapes.shapeOf(
                el, box.style, light.inlineStyles(i), profile,
                NormalFlowLayout.innerBreakWidth(box.style, box.contentWidth),
                pairing,
                ancestorStyleOf = light::resolveStyle,
                genOf = light.genOf,
                floatLead = light.floatLeadAt(i),
            )
            val heavyLeaf = heavy.leaves[i]
            if (heavyLeaf.ranges.isEmpty()) {
                // 替换叶（悬浮 img）：无文本行；高度口径一致。
                assertTrue("block $i replaceable shape", lightShape.isReplaceable)
                assertEquals(
                    "block $i replaceable height",
                    heavyLeaf.replaceableHeight, lightShape.replaceableBottom,
                )
            } else {
                assertEquals("block $i line count", heavyLeaf.ranges.size, lightShape.lineCount)
                for (k in heavyLeaf.ranges.indices) {
                    assertEquals("block $i line $k range", heavyLeaf.ranges[k].first, lightShape.lineStart(k))
                    assertEquals("block $i line $k end", heavyLeaf.ranges[k].last + 1, lightShape.lineEnd(k))
                    assertEquals("block $i line $k height", heavyLeaf.lineHeights[k], lightShape.lineBottom(k) - lightShape.lineTop(k))
                }
            }
        }
    }

    @Test
    fun `temp forward stream tiles with zero-height float line and cleared tail`() {
        val (_, heavy, light) = chapter("float: left")
        val floatH = heavy.leaves.first { it.el?.tag == "img" }.replaceableHeight
        assertTrue("float height", floatH > 0)
        // 单页驱动整章 temp 流（contentH 足够大）。
        val cache = HashMap<Int, orilumn.reader.engine.laying.ParagraphShapeRef>()
        val pages = ArrayList<orilumn.reader.engine.paging.PageSlice>()
        val pageLayouts = ArrayList<orilumn.reader.engine.paging.BookLayout>()
        var b = 0
        var l = 0
        var guard = 0
        while (guard++ < 50) {
            val fp = bc.shapeTempPageForward(light, profile, contentW, contentH, b, cache, l) ?: break
            pages.add(fp.page.slice)
            pageLayouts.add(fp.page.layout)
            if (fp.nextBlock >= light.totalBlocks) break
            b = fp.nextBlock
            l = fp.nextLine
        }
        assertTrue("must terminate", guard < 50)
        // 字符连续无洞全覆盖。
        var cursor = 0
        for (p in pages) {
            assertEquals("page char continuity", cursor, p.charStart)
            cursor = p.charEnd
        }
        assertEquals("full coverage", light.totalChars, cursor)
        // 零高悬浮行存在（1 字符宽，yTop == yBottom）。
        val allLines = pageLayouts.flatMap { layout ->
            (0 until layout.lineCount).map { k ->
                Triple(layout.getLineStart(k), layout.getLineEnd(k), layout.getLineTop(k) to layout.getLineBottom(k))
            }
        }
        val zero = allLines.firstOrNull { it.third.first == it.third.second }
        assertTrue("zero-height float line must exist", zero != null)
        assertEquals("zero line is one char", 1, zero!!.second - zero.first)
        // 后段与重路径同位；P6-a 延续环绕下后段可落在跨度 Y 内（窄行，不断不交叠），
        // 无 lead 时才须在悬浮底之下。
        val tailTop = allLines.last().third.first
        val heavyTailTop = heavy.leaves.filter { it.el?.tag == "p" }[1].contentTop
        assertEquals("cleared tail mirrors heavy", heavyTailTop, tailTop)
        val tailLead = light.floatLeadAt(light.totalBlocks - 1)
        assertTrue(
            "tail wraps (has lead) or sits below float bottom",
            tailLead != null || tailTop >= zero.third.first + floatH,
        )
    }

    @Test
    fun `no float control stays lead-free on both paths`() {
        val (_, heavy, light) = chapter(null)
        for (i in 0 until heavy.totalBlocks) {
            assertNull(heavy.leaves[i].floatLead)
            assertNull(light.floatLeadAt(i))
        }
    }

    @Test
    fun `backward stream tiles with zero-height float line`() {
        val (_, heavy, light) = chapter("float: left")
        val cache = HashMap<Int, orilumn.reader.engine.laying.ParagraphShapeRef>()
        // 按生产 shapeNextBackward 口径链式回填（共享块 + lineCut），页序倒收后连续全覆盖。
        val pages = ArrayList<orilumn.reader.engine.paging.PageSlice>()
        val layouts = ArrayList<orilumn.reader.engine.paging.BookLayout>()
        var endExcl = light.totalBlocks
        var lineCut = -1
        var guard = 0
        while (endExcl > 0 && guard++ < 50) {
            val page = bc.shapeTempPageBackward(light, profile, contentW, 120, endExcl, lineCut, cache)
                ?: break
            pages.add(0, page.slice)
            layouts.add(0, page.layout)
            if (page.blockStart <= 0) break
            val prevBlockStart = page.blockStart
            val prevLineCharStart = page.slice.charStart
            val prevBlockCharStart = light.globalCharStarts[prevBlockStart].toInt()
            lineCut = if (prevLineCharStart > prevBlockCharStart) prevLineCharStart else -1
            endExcl = if (lineCut >= 0) prevBlockStart + 1 else prevBlockStart
        }
        assertTrue("must terminate", guard < 50)
        assertTrue("multiple pages", pages.size > 1)
        var cursor = 0
        for (p in pages) {
            assertEquals("backward page char continuity", cursor, p.charStart)
            cursor = p.charEnd
        }
        assertEquals("backward full coverage", light.totalChars, cursor)
        val zero = layouts.flatMap { layout ->
            (0 until layout.lineCount).map { k -> Triple(layout.getLineStart(k), layout.getLineEnd(k), layout.getLineTop(k) to layout.getLineBottom(k)) }
        }.firstOrNull { it.third.first == it.third.second }
        assertTrue("zero-height float line must exist in backward stream", zero != null)
        assertEquals("zero line is one char", 1, zero!!.second - zero.first)
        assertTrue("only float chapter product matters", heavy.totalBlocks == light.totalBlocks)
    }

    @Test
    fun `canonical fullLayout rebuilds zero-height float line`() {
        val (_, heavy, _) = chapter("float: left")
        val product = bc.fullLayout(heavy, profile, contentW, contentH)!!
        // 切片全覆盖连续。
        var cursor = 0
        for (s in product.slices) {
            assertEquals("canonical slice continuity", cursor, s.charStart)
            cursor = s.charEnd
        }
        assertEquals("canonical full coverage", heavy.totalChars, cursor)
        // 零高悬浮行存在（与 emit/轻路径同式；分页不计悬浮高）。
        val layout = product.layout
        val zero = (0 until layout.lineCount).map { k ->
            Triple(layout.getLineStart(k), layout.getLineEnd(k), layout.getLineTop(k) to layout.getLineBottom(k))
        }.firstOrNull { it.third.first == it.third.second }
        assertTrue("canonical zero-height float line must exist", zero != null)
        assertEquals("zero line is one char", 1, zero!!.second - zero.first)
    }
}
