package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.Edges
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.css.WhiteSpace
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * min-content 断行机会分段与 `max/min-content` 度量：切分规则按 Chrome `width: min-content`
 * 实测标定（样本矩阵见 [minContentSegments] 文档），此处固定住这些结论，防回归。
 */
class MinContentSegmentsTest {

    /** 只实现 [ParagraphBreaker.breakLines] 的测试断行器；度量走接口默认实现（确定性）。 */
    private class FakeBreaker : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence,
            fontSizePx: Float,
            lineHeightRatio: Float,
            widthPx: Int,
            alignment: TextAlign,
            tag: String?,
            families: List<String>,
            weight: Int,
            italic: Boolean,
            monospace: Boolean,
        ): List<BrokenLine> = if (text.isEmpty()) emptyList() else listOf(BrokenLine(0 until text.length, 1))
    }

    private fun segs(s: String): List<String> =
        minContentSegments(s).map { s.substring(it.first, it.last + 1) }

    @Test
    fun `segments break at document whitespace and drop it`() {
        assertEquals(listOf("Hello", "world", "foo"), segs("Hello world foo"))
        assertEquals(listOf("aa", "aa", "aa"), segs("aa aa aa"))
        assertEquals(listOf("Kindle", "Previewer"), segs("  Kindle \t Previewer  "))
        // NBSP / EM SPACE 不是 CSS 文档空白 → 不断（Chrome 实测）。
        assertEquals(listOf("test\u00A0nbsp"), segs("test\u00A0nbsp"))
    }

    @Test
    fun `segments break between every CJK char`() {
        assertEquals(listOf("項", "目"), segs("項目"))
        assertEquals(listOf("あ", "い", "う", "え", "お"), segs("あいうえお"))
        // 假名与长音符同样逐字可断（Chrome 实测：min-content = 单字宽）。
        assertEquals(listOf("サ", "ポ", "ー", "ト"), segs("サポート"))
        assertEquals(listOf("コ", "ー", "ヒ", "ー"), segs("コーヒー"))
        // 拉丁与 CJK 之间可断。
        assertEquals(listOf("abc", "サ", "ポ", "ー", "ト", "def"), segs("abcサポートdef"))
        assertEquals(listOf("第", "1", "章"), segs("第1章"))
    }

    @Test
    fun `segments keep closers with the preceding text and openers with the following`() {
        // 闭合/中缀类前不断（LB13）。
        assertEquals(listOf("あ、", "い"), segs("あ、い"))
        assertEquals(listOf("【重", "要】"), segs("【重要】"))
        // 开括后不断（LB14）＋闭合前不断 → 整段。
        assertEquals(listOf("（注）"), segs("（注）"))
        assertEquals(listOf("A", "「B」", "C"), segs("A「B」C"))
        // 非起始类（々）前不断。
        assertEquals(listOf("時々"), segs("時々"))
    }

    @Test
    fun `segments break after hyphens and keep numbers and paths together`() {
        assertEquals(listOf("Wi-", "Fi"), segs("Wi-Fi"))
        assertEquals(listOf("e-", "book", "reader"), segs("e-book reader"))
        // 连字符前不断（Chrome：「日本-語」的最长断片是「本-」）。
        assertEquals(listOf("日", "本-", "語"), segs("日本-語"))
        // 数字/小数/逗号都不在内部断（LB13 IS 类，且 `,` `.` 未进 breakAfter）。
        assertEquals(listOf("Readium", "0.5.3"), segs("Readium 0.5.3"))
        assertEquals(listOf("1,234.56"), segs("1,234.56"))
        // T2d 改判：路径**会**在斜杠之后断。此前本行断言 `foo/bar` 整体不可断，那是旧表的行为。
        // T2d 的 `P×N` 二维矩阵实测 Skia 放行斜杠后断（两侧都非宽字的 `|A`/`|1`/`|(`/`|[` 四格逐格实测），
        // 故 min-content 的最长不可断单元从 `foo/bar` 缩成 `foo/` —— auto 分列的 min 侧随之变松。
        assertEquals(listOf("foo/", "bar"), segs("foo/bar"))
    }

    @Test
    fun `min content width is the widest unbreakable segment`() {
        val b = FakeBreaker()
        // 默认度量：每段 = 段长 x fontSizePx。
        assertEquals(20f, b.minContentWidth("aa aa aa", 10f, emptyList(), 400, false, false), 1e-4f)
        assertEquals(60f, b.minContentWidth("much longer cell text", 10f, emptyList(), 400, false, false), 1e-4f)
        // 汉字串 min-content = 单字宽（不是整串）。
        assertEquals(10f, b.minContentWidth("甲乙丙丁", 10f, emptyList(), 400, false, false), 1e-4f)
        assertEquals(0f, b.minContentWidth("", 10f, emptyList(), 400, false, false), 1e-4f)
        // min ≤ max 恒成立（同一切分与度量源）。
        for (s in listOf("Hello world", "MathMLサポート", "（注）", "a-b c")) {
            val max = b.preferredWidth(s, 10f, emptyList(), 400, false, false)
            val min = b.minContentWidth(s, 10f, emptyList(), 400, false, false)
            org.junit.Assert.assertTrue("min ≤ max for $s", min <= max)
        }
    }

    /**
     * Q15：单块格的度量等价旧式「一格一段文本」调用（`tableCellPref` 现在吃**块序列**）。
     * 纯内联格恒一块（匿名块），故这就是「格内无块级子节点」的老路径。
     */
    private fun oneBlock(text: String, style: ComputedStyle) =
        listOf(TableCellBlock(orilumn.reader.engine.html.MarkupElement("td"), style, text, emptyList()))

    @Test
    fun `table cell pref is border-box and nowrap cells have min == max`() {
        val b = FakeBreaker()
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f)
        val wrap = NormalFlowLayout.tableCellPref(b, 0, 1, oneBlock("aa bb", style), style)
        assertEquals(50f, wrap.pref, 1e-4f) // 整段 5 字 x 10
        assertEquals(20f, wrap.min, 1e-4f) // 最长单元 "aa"
        // white-space: nowrap → 不可换行，故 min-content 即 max-content（浏览器同式）。
        val nowrapStyle = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, whiteSpace = WhiteSpace.NOWRAP)
        val nowrap = NormalFlowLayout.tableCellPref(b, 0, 1, oneBlock("aa bb", nowrapStyle), nowrapStyle)
        assertEquals(nowrap.pref, nowrap.min, 1e-4f)
        // 空文本仍保留 padding+border（border-box）。
        val edges = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, padding = Edges(left = 3f, right = 2f))
        val blank = NormalFlowLayout.tableCellPref(b, 0, 1, oneBlock("", edges), edges)
        assertEquals(5f, blank.pref, 1e-4f)
        assertEquals(5f, blank.min, 1e-4f)
    }

    /**
     * **Q15 回归**：格内块级子节点各自贡献度量、内容宽度取**最大**（CSS 2.1 §10.5，浏览器同式）
     * —— 不是求和。这一把锁的是「别退回求和」：求和会把两段短文字的列撑到两倍宽。
     */
    @Test
    fun `table cell pref takes max over cell blocks not sum`() {
        val b = FakeBreaker()
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f)
        fun blk(text: String) = TableCellBlock(orilumn.reader.engine.html.MarkupElement("p"), style, text, emptyList())
        val two = NormalFlowLayout.tableCellPref(b, 0, 1, listOf(blk("aaaa"), blk("bbbbb")), style)
        assertEquals("max-content 取最长块", 50f, two.pref, 1e-4f)
        // min-content 同样取**最大**：块流里最长不可断单元在「bbbbb」(5x10) 这个块里。
        assertEquals("min-content 取最长块的最长单元", 50f, two.min, 1e-4f)
        // 逐块横向 inset（`edgeH`，由 `cellBlocks` 从容器的 border/padding 累加得来）计入列宽。
        val padded = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, padding = Edges(left = 5f, right = 5f))
        val onePad = NormalFlowLayout.tableCellPref(
            b, 0, 1,
            listOf(TableCellBlock(orilumn.reader.engine.html.MarkupElement("p"), padded, "aaaa", emptyList(), edgeH = 10)),
            style,
        )
        assertEquals(50f, onePad.pref, 1e-4f) // 4x10 内容 + 10 横向 inset
        // 空块不贡献内容（但格的 padding 仍在）。
        val withBlank = NormalFlowLayout.tableCellPref(b, 0, 1, listOf(blk(""), blk("aa")), style)
        assertEquals(20f, withBlank.pref, 1e-4f)
    }
}
