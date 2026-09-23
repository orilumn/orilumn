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
        // 数字/小数/逗号、路径都不在内部断（LB13 IS/SY）。
        assertEquals(listOf("Readium", "0.5.3"), segs("Readium 0.5.3"))
        assertEquals(listOf("1,234.56"), segs("1,234.56"))
        assertEquals(listOf("foo/bar"), segs("foo/bar"))
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

    @Test
    fun `table cell pref is border-box and nowrap cells have min == max`() {
        val b = FakeBreaker()
        val style = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f)
        val wrap = NormalFlowLayout.tableCellPref(b, 0, 1, "aa bb", style, "td", emptyList())
        assertEquals(50f, wrap.pref, 1e-4f) // 整段 5 字 x 10
        assertEquals(20f, wrap.min, 1e-4f) // 最长单元 "aa"
        // white-space: nowrap → 不可换行，故 min-content 即 max-content（浏览器同式）。
        val nowrap = NormalFlowLayout.tableCellPref(
            b, 0, 1, "aa bb",
            ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, whiteSpace = WhiteSpace.NOWRAP),
            "td", emptyList(),
        )
        assertEquals(nowrap.pref, nowrap.min, 1e-4f)
        // 空文本仍保留 padding+border（border-box）。
        val edges = ComputedStyle(fontSizePx = 10f, lineHeightRatio = 1f, padding = Edges(left = 3f, right = 2f))
        val blank = NormalFlowLayout.tableCellPref(b, 0, 1, "", edges, "td", emptyList())
        assertEquals(5f, blank.pref, 1e-4f)
        assertEquals(5f, blank.min, 1e-4f)
    }
}
