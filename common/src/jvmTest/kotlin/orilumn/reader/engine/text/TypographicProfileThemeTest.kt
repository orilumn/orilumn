package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版主题 (layoutTheme) 的新语义: 切主题经由 withLayoutTheme 把主题预设的
 * 首行缩进/段间距 一次写入设置 (滑块值跟随主题); build() 对存储值原样透传,
 * 因此主题激活期间手动拖滑块同样生效 (不再被主题覆盖), 且该提交的字段 diff
 * 会传染到全局 (未定制书跟随), 已定制书保留全量快照.
 */
class TypographicProfileThemeTest {

    @Test
    fun `traditional preset writes 2em indent no gap and serif body font`() {
        val s = TypographicProfile.withLayoutTheme(ReaderSettings.DEFAULT, "traditional")
        assertEquals("traditional", s.layoutTheme)
        assertEquals(2.0, s.firstLineIndent, 1e-9)
        assertEquals(0.0, s.paragraphSpacing, 1e-9)
        assertEquals("serif", s.fontBody)
    }

    @Test
    fun `modern preset writes no indent default gap and sans-serif body font`() {
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(d, "modern")
        assertEquals("modern", s.layoutTheme)
        assertEquals(d.firstLineIndent, s.firstLineIndent, 1e-9)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing, 1e-9)
        assertEquals("sans-serif", s.fontBody)
    }

    @Test
    fun `original preset falls back to reader defaults and clears the body font slot`() {
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(d, "original")
        assertEquals("original", s.layoutTheme)
        assertEquals(d.firstLineIndent, s.firstLineIndent, 1e-9)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing, 1e-9)
        assertEquals("", s.fontBody)
    }

    @Test
    fun `theme switch touches only theme indent spacing and font`() {
        val base = ReaderSettings.DEFAULT.copy(fontSize = 22, lineSpacing = 1.8, letterSpacing = 3.0)
        val s = TypographicProfile.withLayoutTheme(base, "traditional")
        assertEquals(22, s.fontSize)
        assertEquals(1.8, s.lineSpacing, 1e-9)
        assertEquals(3.0, s.letterSpacing, 1e-9)
        assertEquals("traditional", s.layoutTheme)
    }

    @Test
    fun `build passes stored values through - slider is the truth in a theme`() {
        val p = TypographicProfile.build(
            ReaderSettings.DEFAULT.copy(
                layoutTheme = "traditional", firstLineIndent = 5.0, paragraphSpacing = 1.5,
            ),
        )
        assertEquals(5f, p.firstLineIndentEm)
        assertEquals(1.5f * p.bodyPx, p.paragraphSpacingPx.toFloat(), 1f)
        assertTrue(p.useOriginalStyle.not())
        assertEquals("traditional", p.layoutTheme)
    }

    @Test
    fun `theme font family matches the theme preset`() {
        assertEquals("serif", TypographicProfile.layoutThemeFontFamily("traditional"))
        assertEquals("sans-serif", TypographicProfile.layoutThemeFontFamily("modern"))
        assertEquals(null, TypographicProfile.layoutThemeFontFamily("original"))
    }

    @Test
    fun `themes differ from original by useOriginalStyle`() {
        assertTrue(TypographicProfile.build(ReaderSettings.DEFAULT).useOriginalStyle)
        assertFalse(TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern")).useOriginalStyle)
        assertFalse(TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "traditional")).useOriginalStyle)
    }

    @Test
    fun `original preset resets all typography fields to neutral`() {
        val dirty = ReaderSettings.DEFAULT.copy(
            fontSize = 22, fontScale = 40.0,
            fontBody = "屏显臻宋", fontTitle = "屏显臻宋", fontCode = "mono",
            lineSpacing = 1.9, paragraphGap = 350.0, letterSpacing = 80.0,
            firstLineIndent = 3.0, paragraphSpacing = 1.2,
        )
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(dirty, "original")
        assertEquals("original", s.layoutTheme)
        assertEquals("", s.fontBody)
        assertEquals("", s.fontTitle)
        assertEquals("", s.fontCode)
        assertEquals(d.fontSize, s.fontSize)
        assertEquals(d.fontScale, s.fontScale, 1e-9)
        assertEquals(d.lineSpacing, s.lineSpacing, 1e-9)
        assertEquals(d.paragraphGap, s.paragraphGap, 1e-9)
        assertEquals(d.letterSpacing, s.letterSpacing, 1e-9)
        assertEquals(d.firstLineIndent, s.firstLineIndent, 1e-9)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing, 1e-9)
    }

    @Test
    fun `build passes stored ui values through in every mode including original`() {
        // 用户永远最高优先级: UI 设置层始终生效, 即使 原书设置 模式下也不被引擎归零.
        val p = TypographicProfile.build(
            ReaderSettings.DEFAULT.copy(
                layoutTheme = "original", fontScale = 50.0, fontSize = 20,
                fontBody = "屏显臻宋", paragraphGap = 200.0, letterSpacing = 50.0,
            ),
        )
        assertTrue(p.useOriginalStyle)
        assertEquals(20f, p.bodyPx)
        assertEquals("屏显臻宋", p.fontBody)
        assertEquals(2f, p.paragraphGapScale, 1e-9f)
        assertEquals(0.1f, p.letterSpacingEm, 1e-9f)
    }
}