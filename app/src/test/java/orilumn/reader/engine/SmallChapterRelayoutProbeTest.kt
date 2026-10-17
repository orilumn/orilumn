package orilumn.reader.engine

import orilumn.reader.data.epub.FakeEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 回归（用户真机 §调参 on 短章）：短章（≤120 块）调节行距后的落位与后续翻页。
 *
 * 短章恒走磁盘表（整块页），不得引入临时表的「行锚页」口径：
 *  1. 落位必须是**磁盘表里真实存在的页**，且取「页首行离锚点行最近」的那一页
 *     （并列取更靠前的，即含锚点的那一页）——锚点行落在页顶上方一行内，画面几乎不动；
 *     绝不能把锚点行钉在页顶造一张表外的合成页（页界不是表的分页界）；
 *  2. 落位后翻页必须走章内相邻页（索引 ±1），不得因为切片不在表里而走「行连续」分支，
 *     回填到章首后误判书首、跨章跳走。
 *
 * 真机现象（长生界 ch1，34 块小章，锚点=调参前当前页首字符）：
 *  - 1.4→1.5：落位页 char[574,1312) blk[-1,-1)（锚点行被钉在页顶），磁盘表那页是 [532,1279)；
 *  - 1.5→1.4：落位页 char[532,1312) blk[-1,-1)，回翻走行连续分支回填到行 0 →
 *    换成 `slices.first()`（字符 0 那页）→ 位置成「书首」→ 封面层弹出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmallChapterRelayoutProbeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var controller: BookDocumentController
    private val viewW = 720
    private val viewH = 1280

    @Before
    fun setUp() {
        controller = BookDocumentController(
            reader = FakeEpubResourceReader(epubFiles()),
            layouter = BoxChapterLayouter(),
            profile = TypographicProfile.build(ReaderSettings.DEFAULT),
        )
        controller.cacheRoot = temp.newFolder("cache").absolutePath.toPath()
    }

    @After
    fun tearDown() {
        if (::controller.isInitialized) controller.close()
    }

    @Test
    fun `small chapter relayout lands on the nearest real disk-table page`() = runBlocking {
        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.4))
        controller.open(31L, saved = null)
        controller.setViewport(viewW, viewH)

        val (_, p1) = controller.openChapterStart(1) ?: error("no ch1 head")
        val (_, p2) = controller.findAdjacentPage(1, p1, +1) ?: error("no ch1 page 2")
        println("SMALL-PROBE before: p1=[${p1.charStart},${p1.charEnd}) p2=[${p2.charStart},${p2.charEnd})")

        // 1.4 -> 1.5（行距变大）：锚点 = 调参前当前页首字符
        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.5))
        val r = controller.prepareRelayoutLight(1, p2.charStart) ?: error("no relayout product")
        val layout = r.layout ?: error("small chapter relayout must carry a real layout")
        val expected = nearestByRule(layout, r.slices, p2.charStart) ?: error("no canonical page")
        println(
            "SMALL-PROBE after: page=[${r.page.charStart},${r.page.charEnd}) firstLine=${r.page.firstLine} " +
                "slices=${r.slices.map { "[${it.charStart},${it.charEnd})" }} expected=[${expected.charStart},${expected.charEnd})",
        )

        assertEquals(
            "落位必须是磁盘表里「页首行离锚点行最近」的那一页",
            expected.charStart to expected.charEnd,
            r.page.charStart to r.page.charEnd,
        )
        assertTrue(
            "落位必须是磁盘表页（blockStart/firstLine 不得为 -1）",
            r.page.blockStart >= 0 && r.page.firstLine >= 0,
        )
        assertTrue(
            "落位必须原样来自 slices（不是表外合成页）",
            r.slices.any { it.charStart == r.page.charStart && it.charEnd == r.page.charEnd },
        )
        assertNotEquals("锚点不在章首时不得回落章首那一页", 0, r.page.charStart)
    }

    @Test
    fun `flips after small-chapter relayout stay on the disk table`() = runBlocking {
        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.5))
        controller.open(32L, saved = null)
        controller.setViewport(viewW, viewH)

        val (_, p1) = controller.openChapterStart(1) ?: error("no ch1 head")
        val (_, p2) = controller.findAdjacentPage(1, p1, +1) ?: error("no ch1 page 2")
        val (_, p3) = controller.findAdjacentPage(1, p2, +1) ?: error("no ch1 page 3")
        println(
            "SMALL-PROBE 1.5: p1=[${p1.charStart},${p1.charEnd}) p2=[${p2.charStart},${p2.charEnd}) " +
                "p3=[${p3.charStart},${p3.charEnd})",
        )

        // 1.5 -> 1.4（行距变小），从第 3 页调参（锚点在章中，回翻不该撞章首兜底）
        controller.profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(lineSpacing = 1.4))
        val r = controller.prepareRelayoutLight(1, p3.charStart) ?: error("no relayout product")
        controller.bindReflow(r)
        println(
            "SMALL-PROBE 1.4 relayout: page=[${r.page.charStart},${r.page.charEnd}) firstLine=${r.page.firstLine} " +
                "slices=${r.slices.map { "[${it.charStart},${it.charEnd})" }}",
        )
        assertTrue(
            "落位必须是磁盘表页",
            r.page.blockStart >= 0 && r.page.firstLine >= 0 &&
                r.slices.any { it.charStart == r.page.charStart && it.charEnd == r.page.charEnd },
        )
        val idx = r.slices.indexOfFirst { it.charStart == r.page.charStart }
        assertTrue("锚点应落在章中（第 2 页起）", idx >= 1)

        val back = controller.findAdjacentPage(1, r.page, -1)
        println("SMALL-PROBE backward -> chapter=${back?.first} page=${back?.second?.let { "[${it.charStart},${it.charEnd}) firstLine=${it.firstLine} blk=${it.blockStart}" }}")
        assertEquals("回翻必须留在本章（不得跨章跳走）", 1, back?.first)
        val backSlice = back?.second ?: error("no backward page")
        assertTrue("回翻必须落在磁盘表页上（不得是行连续合成页）", backSlice.blockStart >= 0 && backSlice.firstLine >= 0)
        assertTrue(
            "回翻必须是磁盘表里的上一页",
            r.slices.any { it.charStart == backSlice.charStart && it.charEnd == backSlice.charEnd },
        )
        assertEquals(
            "回翻必须是紧邻的上一页（索引 -1），不是「章首那一页」的兜底",
            r.slices[idx - 1].charStart,
            backSlice.charStart,
        )

        val fwd = controller.findAdjacentPage(1, r.page, +1)
        println("SMALL-PROBE forward  -> chapter=${fwd?.first} page=${fwd?.second?.let { "[${it.charStart},${it.charEnd}) firstLine=${it.firstLine} blk=${it.blockStart}" }}")
    }

    /** 测试侧独立复算「页首行离锚点行最近」：并列取更靠前的页（与实现同口径、不共用代码）。 */
    private fun nearestByRule(layout: BookLayout, slices: List<PageSlice>, anchorChar: Int): PageSlice? {
        val n = layout.lineCount
        if (n <= 0) return slices.firstOrNull()
        val anchorLine = (0 until n).firstOrNull {
            layout.getLineStart(it) <= anchorChar && layout.getLineEnd(it) > anchorChar
        } ?: (n - 1)
        var best: PageSlice? = null
        var bestDist = Int.MAX_VALUE
        for (s in slices) {
            if (s.firstLine < 0) continue
            val d = kotlin.math.abs(s.firstLine - anchorLine)
            if (d < bestDist) {
                bestDist = d
                best = s
            }
        }
        return best
    }

    private fun epubFiles(): Map<String, ByteArray> {
        val container = """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles><rootfile full-path="OEBPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>"""
        val opf = """<?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>SMALL</dc:title><dc:identifier id="bookid">urn:test:small</dc:identifier>
                </metadata>
                <manifest>
                    <item id="c0" href="Text/c0.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c1" href="Text/c1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="c2" href="Text/c2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine><itemref idref="c0"/><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>"""
        // 短章：每章 ~100 段（<120 块），每段一行 ⇒ 多页（≥3）但恒走磁盘表。
        val chapters = (0..2).associate { n ->
            val body = (1..100).joinToString("\n") { p ->
                "<p>第 $p 段，用来测量断行与分页的填充文字，多写一点让它跨行。</p>"
            }
            "OEBPS/Text/c$n.xhtml" to ("<html><body><h1>第 $n 章</h1>" + body + "</body></html>").toByteArray()
        }
        return mapOf(
            "META-INF/container.xml" to container.toByteArray(),
            "OEBPS/package.opf" to opf.toByteArray(),
        ) + chapters
    }
}
