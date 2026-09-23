package orilumn.reader.engine.skia

import orilumn.reader.engine.css.FontRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * min-content 真度量（Skia 字形）与分段接线的回归护栏：
 * 断言的是**同源不变量**（min = 各断片 [SkiaParagraphBreaker.preferredWidth] 的最大者、
 * min ≤ max、行内 face 段参与度量），而非硬编码宽度数字——字体由测试资源提供，
 * Chrome 实测标定见 `orilumn.reader.engine.laying.minContentSegments` 的文档与
 * `MinContentSegmentsTest`。
 */
class MinContentWidthSkiaTest {

    private fun woff2(): ByteArray =
        javaClass.getResourceAsStream("/fonts/roboto-latin.woff2")!!.readBytes()

    private fun withBreaker(block: (SkiaParagraphBreaker) -> Unit) {
        try {
            assertTrue(SkiaFontPool.setEmbedded(listOf(SkiaFontPool.EmbeddedFont("Roboto", woff2()))))
            block(SkiaParagraphBreaker(letterSpacingEm = 0f))
        } finally {
            SkiaFontPool.setEmbedded(emptyList())
        }
    }

    private val families = listOf("Roboto")

    private fun SkiaParagraphBreaker.max(s: String) = preferredWidth(s, 20f, families, 400, false, false, emptyList())

    private fun SkiaParagraphBreaker.min(s: String, runs: List<FontRun> = emptyList()) =
        minContentWidth(s, 20f, families, 400, false, false, runs)

    @Test
    fun minContentIsTheWidestUnbreakableSegment() = withBreaker { b ->
        val s = "Hello world foo"
        val expected = maxOf(b.max("Hello"), b.max("world"), b.max("foo"))
        assertEquals(expected, b.min(s), 1e-3f)
        assertTrue("min ≤ max", b.min(s) <= b.max(s))
        // 单字不可断：min == max。
        assertEquals(b.max("Hello"), b.min("Hello"), 1e-3f)
        // 汉字串 min-content = 单字宽（逐字可断，不依赖 CJK 字体是否安装：宽度仍按回退面测）。
        val cjk = "甲乙丙丁"
        assertTrue("CJK min must be much narrower than the whole run", b.min(cjk) < b.max(cjk) * 0.6f)
    }

    @Test
    fun minContentBreaksAfterHyphenAndKeepsNumbers() = withBreaker { b ->
        assertEquals(maxOf(b.max("a-"), b.max("b")), b.min("a-b"), 1e-3f)
        // 小数/逗号不断（LB13 IS）。
        assertEquals(b.max("1,234.56"), b.min("1,234.56"), 1e-3f)
        assertEquals(b.max("0.5.3"), b.min("0.5.3"), 1e-3f)
    }

    @Test
    fun minContentHonoursInlineFontRunsPerSegment() = withBreaker { b ->
        // "ab cd"：cd 加粗。min = max(宽体 "ab", 粗体 "cd")，逐段各按自己的 face 度量。
        val bold = FontRun(3, 5, families, "strong", 700, false, false)
        val expected = maxOf(
            b.max("ab"),
            b.preferredWidth("cd", 20f, families, 700, false, false, emptyList()),
        )
        assertEquals(expected, b.min("ab cd", listOf(bold)), 1e-3f)
    }
}
