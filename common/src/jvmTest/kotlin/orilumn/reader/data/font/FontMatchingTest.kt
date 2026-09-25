package orilumn.reader.data.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 — font subfamily metric + nearest-weight/style selection (pure JVM, no Android).
 */
class FontMatchingTest {

    private fun face(subfamily: String, family: String = "Source Han Sans", id: Long = 0): FontFace =
        FontFace(
            id = id,
            familyName = family,
            displayName = "$family-$subfamily",
            subfamily = subfamily,
            source = FontFace.SOURCE_IMPORTED,
            path = "/fonts/$family-$subfamily.ttf",
            lang = "cjk",
        )

    // ---- SubfamilyMetric weights ----

    @Test
    fun `subfamily 名映射到 CSS 字重`() {
        assertEquals(400, SubfamilyMetric.weight("Regular"))
        assertEquals(400, SubfamilyMetric.weight(""))
        assertEquals(700, SubfamilyMetric.weight("Bold"))
        assertEquals(300, SubfamilyMetric.weight("Light"))
        assertEquals(500, SubfamilyMetric.weight("Medium"))
        assertEquals(600, SubfamilyMetric.weight("SemiBold"))
        assertEquals(800, SubfamilyMetric.weight("ExtraBold"))
        assertEquals(900, SubfamilyMetric.weight("Black"))
        assertEquals(509, SubfamilyMetric.weight("509R"))
    }

    @Test
    fun `中文字重名映射到 CSS 字重`() {
        // Sarasa/苹方中文记录口径（与 FontParser.localizedName 同源）。
        assertEquals(400, SubfamilyMetric.weight("常规体"))
        assertEquals(700, SubfamilyMetric.weight("粗体"))
        assertEquals(600, SubfamilyMetric.weight("中粗体"))
        assertEquals(500, SubfamilyMetric.weight("中等"))
        assertEquals(500, SubfamilyMetric.weight("中黑体"))
        assertEquals(300, SubfamilyMetric.weight("细体"))
        assertEquals(200, SubfamilyMetric.weight("特细体"))
        assertEquals(100, SubfamilyMetric.weight("纤细体"))
        assertEquals(200, SubfamilyMetric.weight("极细体"))
        assertEquals(800, SubfamilyMetric.weight("特粗体"))
        assertEquals(900, SubfamilyMetric.weight("黑体"))
        assertTrue(SubfamilyMetric.italic("斜体"))
        assertTrue(SubfamilyMetric.italic("粗斜体"))
        assertFalse(SubfamilyMetric.italic("粗体"))
    }

    @Test
    fun `Hiragino W 档映射到 CSS 字重`() {
        // Hiragino Sans W0–W9 / 明朝 W3·W6：单数字档（可选 W 前缀）×100，
        // 否则 W3→3 这类非法锚点选了也永远生效不了。
        assertEquals(100, SubfamilyMetric.weight("W0"))
        assertEquals(300, SubfamilyMetric.weight("W3"))
        assertEquals(600, SubfamilyMetric.weight("W6"))
        assertEquals(900, SubfamilyMetric.weight("W9"))
        // 多位数字仍沿厂商内码口径，不进档位规则。
        assertEquals(509, SubfamilyMetric.weight("509R"))
    }

    @Test
    fun `数字前缀不盖过样式词`() {
        // 阿里巴巴普惠体 3.0：35..115 非 CSS 字重，样式词说了算；否则正文 400 会选中 Black。
        assertEquals(100, SubfamilyMetric.weight("35 Thin"))
        assertEquals(300, SubfamilyMetric.weight("45 Light"))
        assertEquals(400, SubfamilyMetric.weight("55 Regular"))
        assertEquals(500, SubfamilyMetric.weight("65 Medium"))
        assertEquals(600, SubfamilyMetric.weight("75 SemiBold"))
        assertEquals(700, SubfamilyMetric.weight("85 Bold"))
        assertEquals(800, SubfamilyMetric.weight("95 ExtraBold"))
        assertEquals(700, SubfamilyMetric.weight("105 Heavy"))
        assertEquals(900, SubfamilyMetric.weight("115 Black"))
    }

    @Test
    fun `普惠体正文选中Regular而非Black`() {
        val family = listOf(
            face("105 Heavy", id = 1),
            face("115 Black", id = 2),
            face("55 Regular", id = 3),
        )
        assertEquals(3L, FontFaceMatcher.choose(family, 400, false)?.id)
        assertEquals(1L, FontFaceMatcher.choose(family, 700, false)?.id)
    }

    @Test
    fun `italic 识别`() {
        assertTrue(SubfamilyMetric.italic("Italic"))
        assertTrue(SubfamilyMetric.italic("Oblique"))
        assertTrue(SubfamilyMetric.italic("Bold Italic"))
        assertFalse(SubfamilyMetric.italic("Regular"))
        assertFalse(SubfamilyMetric.italic(""))
    }

    // ---- FontFaceMatcher selection ----

    @Test
    fun `存在真实Bold时 font-weight bold 选中它`() {
        val family = listOf(face("Regular", id = 1), face("Bold", id = 2))
        assertEquals(2L, FontFaceMatcher.choose(family, 700, false)?.id)
        assertEquals(1L, FontFaceMatcher.choose(family, 400, false)?.id)
    }

    @Test
    fun `缺失目标字重时回退最近字重`() {
        // only Regular (400): a 700 request falls back to Regular
        val only = listOf(face("Regular", id = 1))
        assertEquals(1L, FontFaceMatcher.choose(only, 700, false)?.id)
        // Regular + Medium: 700 request picks Medium (500, nearest)
        val withMedium = listOf(face("Regular", id = 1), face("Medium", id = 2))
        assertEquals(2L, FontFaceMatcher.choose(withMedium, 700, false)?.id)
    }

    @Test
    fun `斜体优先于字重完全匹配`() {
        // Request bold-italic: a Bold Italic face is chosen even against a plain Bold closer in weight.
        val faces = listOf(face("Bold", id = 1), face("Bold Italic", id = 2))
        assertEquals(2L, FontFaceMatcher.choose(faces, 700, true)?.id)
        // No italic face exists: falls back to a non-italic face for an italic request.
        val noItalic = listOf(face("Regular", id = 1), face("Bold", id = 2))
        assertEquals(2L, FontFaceMatcher.choose(noItalic, 700, true)?.id)
    }

    @Test
    fun `空家族返回null`() {
        assertNull(FontFaceMatcher.choose(emptyList(), 400, false))
    }
}