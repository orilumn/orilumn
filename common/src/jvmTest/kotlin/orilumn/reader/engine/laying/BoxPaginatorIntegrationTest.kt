package orilumn.reader.engine.laying

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.paging.Paginator
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 — integration of the box layout ([BoxLayouter] → [BoxBookLayout]) with the [Paginator]:
 * empty blocks, long-paragraph fallback, and `break-inside: avoid` keeping whole blocks intact
 * (including oversized blocks being allowed to tear).
 */
class BoxPaginatorIntegrationTest {

    private fun text(t: String) = MarkupElement("#text", text = t)
    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()) =
        MarkupElement(tag, attrs, children)

    /** Deterministic breaker: exactly `charsPerLine` chars per line (width-independent). */
    private class FixedWidthBreaker(private val charsPerLine: Int) : ParagraphBreaker {
        override fun breakLines(text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int, alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): List<BrokenLine> {
            val h = (fontSizePx * lineHeightRatio).roundToInt().coerceAtLeast(1)
            val out = ArrayList<BrokenLine>()
            var i = 0
            while (i < text.length) {
                val e = (i + charsPerLine).coerceAtMost(text.length)
                out.add(BrokenLine(i until e, h))
                i = e
            }
            return out
        }
    }

    private fun layout(root: MarkupElement): BookLayout =
        BoxLayouter(10f, FixedWidthBreaker(3)).layoutWith(root, widthPx = 100,
            styleMap = StyleComputer(10f, LightCssParser().parse(""), listOf()).compute(root))

    private fun pagesOf(root: MarkupElement, contentH: Int): List<PageSlice> =
        Paginator.paginate(layout(root), contentH)

    @Test
    fun `空块不产生分页且被平滑跳过`() {
        // text block (6 chars → 2 lines), an empty paragraph, then text block (4 chars → 2 lines)
        val root = node("body", children = listOf(
            node("p", children = listOf(text("abcdef"))),
            node("p"),
            node("p", children = listOf(text("ghijklm"))), // 7 → 3 lines
        ))
        // 行流: 2 + 3 = 5 lines; 空段无行.
        val pages = pagesOf(root, contentH = 60)
        assertEquals(5, layout(root).lineCount)
        assertTrue(pages.isNotEmpty())
        // 覆盖整个文本, 前进无死循环
        assertEquals(13, pages.last().charEnd)
    }

    @Test
    fun `长段落回退保证前进且逐页填满`() {
        // A single paragraph of 10 lines (a long run) → page 45px shows exactly 3 lines and moves forward.
        val root = node("body", children = listOf(node("p", children = listOf(text("x".repeat(30))))))
        val pages = pagesOf(root, contentH = 45)
        assertEquals(10, layout(root).lineCount)
        assertEquals(3, pages.first().lastLineExclusive - pages.first().firstLine)
        // no page is empty and progress strictly advances
        for (i in 1 until pages.size) {
            assertTrue(pages[i].firstLine > pages[i - 1].firstLine)
            assertTrue(pages[i].charEnd > pages[i - 1].charStart)
        }
        assertEquals(10, pages.last().lastLineExclusive)
    }

    @Test
    fun `break-inside避免使整段容器块移入下一页不跨页`() {
        // block0 2 lines; container(break-inside:avoid) 含两个3行段落 →6行; block2 2行. 页高90正好容纳容器.
        val figure = node("div", mapOf("style" to "break-inside: avoid"), children = listOf(
            node("p", children = listOf(text("abcdefghi"))), // 9 → 3 lines
            node("p", children = listOf(text("jklmnopqr"))), // 9 → 3 lines
        ))
        val root = node("body", children = listOf(
            node("p", children = listOf(text("abcdef"))),   // lines 0,1
            figure,                                          // lines 2..7
            node("p", children = listOf(text("uchijm"))),   // lines 8,9
        ))

        val layout = layout(root)
        assertEquals(10, layout.lineCount)
        val aware = layout as? orilumn.reader.engine.paging.BreakAwareBookLayout
        assertEquals(true, aware != null)
        // container owns the line range [2, 8)
        assertEquals(listOf(2 until 8), aware?.breakInsideAvoidRanges)

        val pages = pagesOf(root, contentH = 90)
        // 页1 只含 block0 (2行): 容器整块移入下一页
        assertEquals(0, pages[0].firstLine)
        assertEquals(2, pages[0].lastLineExclusive)
        // 页2 恰好容纳整个容器 (6行) 不跨页
        assertEquals(2, pages[1].firstLine)
        assertEquals(8, pages[1].lastLineExclusive)
    }

    @Test
    fun `超过一页的avoid块允许按页撕裂`() {
        // Same figure container (6 lines, 90px) but the page only fits 45px → block taller than the page tears.
        val figure = node("div", mapOf("style" to "break-inside: avoid"), children = listOf(
            node("p", children = listOf(text("abcdefghi"))),
            node("p", children = listOf(text("jklmnopqr"))),
        ))
        val root = node("body", children = listOf(node("p", children = listOf(text("ab"))), figure))

        val pages = pagesOf(root, contentH = 45)
        // 单段(内)在页内可断, 容器无法整体搬移时按段落撕裂: 页数应 > 把容器整体搬移校验.
        assertTrue(pages.size >= 2)
        // 不产生空页且覆盖全文
        for (i in 1 until pages.size) assertTrue(pages[i].charEnd > pages[i - 1].charStart)
    }

    // ---- P4b：canonical 重路径真的消费 BreakClusters 的两条约束 ----

    /** 五块，每块 6 字符 → `FixedWidthBreaker(3)` 下每块恰好 2 行（行高 15px）。 */
    private fun fiveBlocks(): MutableList<MarkupElement> =
        MutableList(5) { node("p", children = listOf(text("abcdef"))) }

    @Test
    fun `break-after avoid 边簇把相邻块整体保在同一页`() {
        // 行高 15px（fontSize 10 × lineHeightRatio 1.5），每块 6 字符 → 2 行，共 10 行。
        // 只在块 1 上写 break-after: avoid ⇒ 边 1|2 成簇 ⇒ 簇 = 块 1..2 = 行 [2,6)（60px）。
        val blocks = fiveBlocks()
        blocks[1] = node("p", mapOf("style" to "break-after: avoid"), listOf(text("abcdef")))
        val root = node("body", children = blocks)
        // 页高 75（5 行）：整行填充第 1 页会切到行 5，正好撕裂簇 [2,6) ⇒ 回退到簇首行 2。
        val pages = pagesOf(root, contentH = 75)
        assertEquals(listOf(0 to 2, 2 to 7, 7 to 10), pages.map { it.firstLine to it.lastLineExclusive })
    }

    @Test
    fun `break-before always 使该块成为页首`() {
        val blocks = fiveBlocks()
        blocks[3] = node("p", mapOf("style" to "page-break-before: always"), listOf(text("abcdef")))
        val root = node("body", children = blocks)
        // 10 行（15px/行），块 3 从行 6 起。页高 75（5 行）：
        // 无强制 ⇒ [0,5) [5,10)；有强制 ⇒ 第 2 页在行 5 处就断（只装行 5），行 6 另起一页。
        val pages = pagesOf(root, contentH = 75)
        assertEquals(listOf(0 to 5, 5 to 6, 6 to 10), pages.map { it.firstLine to it.lastLineExclusive })
    }
}