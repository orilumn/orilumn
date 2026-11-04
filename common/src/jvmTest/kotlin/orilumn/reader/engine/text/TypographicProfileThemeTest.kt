package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.data.settings.onlyGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版主题 (layoutTheme) 的新语义（`docs/设置模型整改计划.md` §3.5）：
 * 切主题只写该主题确实要让读者层跟随的那几项（段间距 / 正文字体族），
 * 基线（缩进 / 字体 / 对齐）交给主题层样式表（tier 42）；
 * 「原书设置」= 清空本书的排版覆盖（字体槽 / 行距 / 字距 / 段距 / 疏密 / 首行缩进），
 * 不动字号与页边距。build() 对存储值原样透传，用户拖滑块永远最高优先级。
 */
class TypographicProfileThemeTest {

    @Test
    fun `traditional preset writes zero density and serif body font without pinning indent`() {
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(d, "traditional")
        assertEquals("traditional", s.layoutTheme)
        // 2em 缩进由 traditional.css（tier 42）提供，读者层不再钉一个显式值。
        assertEquals(d.firstLineIndent, s.firstLineIndent)
        assertEquals(0.0, s.paragraphSpacing!!, 1e-9)
        assertEquals(100.0, s.paragraphGap, 1e-9)
        assertEquals("serif", s.fontBody)
    }

    @Test
    fun `modern preset writes no indent full density and sans-serif body font`() {
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(d, "modern")
        assertEquals("modern", s.layoutTheme)
        assertEquals(d.firstLineIndent, s.firstLineIndent)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing)
        assertEquals(100.0, s.paragraphGap, 1e-9)
        assertEquals("sans-serif", s.fontBody)
    }

    @Test
    fun `original preset falls back to reader defaults and clears the body font slot`() {
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(d, "original")
        assertEquals("original", s.layoutTheme)
        assertEquals(d.firstLineIndent, s.firstLineIndent)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing)
        assertEquals(d.paragraphGap, s.paragraphGap, 1e-9)
        assertEquals("", s.fontBody)
    }

    @Test
    fun `theme switch touches only theme indent spacing and font`() {
        val base = ReaderSettings.DEFAULT.copy(fontSize = 22, lineSpacing = 1.8, letterSpacing = 3.0)
        val s = TypographicProfile.withLayoutTheme(base, "traditional")
        assertEquals(22, s.fontSize)
        assertEquals(1.8, s.lineSpacing ?: Double.NaN, 1e-9)
        assertEquals(3.0, s.letterSpacing!!, 1e-9)
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
        assertEquals(1.5f, p.paragraphSpacingEm!!, 1e-9f)
        assertTrue(p.useOriginalStyle.not())
        assertEquals("traditional", p.layoutTheme)
    }

    @Test
    fun `theme switch never touches global fields`() {
        // 主题只影响本书排版：亮度/阅读主题/笔记族/开关一律不许被 withLayoutTheme 改动。
        val dirty = ReaderSettings.DEFAULT.copy(
            brightness = 42, scheme = "night", bgOverride = "#123456", fgOverride = "#eeeeee",
            noteFont = "NoteFace", noteFontScale = 1.4f, pageNum = true, autoContinue = false,
            highlightColorSlots = listOf(0xFF102030.toInt()), annotationLastKind = "underline",
            showHiddenFonts = true,
        )
        for (theme in listOf("original", "modern", "traditional")) {
            val s = TypographicProfile.withLayoutTheme(dirty, theme)
            assertEquals("主题 [$theme] 不得改全局项", dirty.onlyGlobal(), s.onlyGlobal())
        }
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
    fun `original preset clears the book layout overrides but keeps font size and margins`() {
        val dirty = ReaderSettings.DEFAULT.copy(
            fontSize = 22, fontScale = 40.0,
            fontBody = "屏显臻宋", fontTitle = "屏显臻宋", fontCode = "mono",
            lineSpacing = 1.9, paragraphSpacing = 2.0, paragraphGap = 20.0, letterSpacing = 80.0,
            cjkLatinSpacing = 60.0, firstLineIndent = 3.0,
        )
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(dirty, "original")
        assertEquals("original", s.layoutTheme)
        assertEquals("", s.fontBody)
        assertEquals("", s.fontTitle)
        assertEquals("", s.fontCode)
        // 字号与页边距是读者舒适度，不跟着「原书设置」重置。
        assertEquals(22, s.fontSize)
        assertEquals(40.0, s.fontScale, 1e-9)
        assertEquals(d.lineSpacing, s.lineSpacing)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing)
        assertEquals(d.paragraphGap, s.paragraphGap, 1e-9)
        assertEquals(d.letterSpacing, s.letterSpacing)
        assertEquals(d.cjkLatinSpacing, s.cjkLatinSpacing, 1e-9)
        // 首行缩进回到「不声明」，本书重新完全听书（含悬挂缩进）。
        assertEquals(d.firstLineIndent, s.firstLineIndent)
    }

    @Test
    fun `build passes stored ui values through in every mode including original`() {
        // 用户永远最高优先级: UI 设置层始终生效, 即使 原书设置 模式下也不被引擎归零.
        val p = TypographicProfile.build(
            ReaderSettings.DEFAULT.copy(
                layoutTheme = "original", fontScale = 50.0, fontSize = 20,
                fontBody = "屏显臻宋", paragraphSpacing = 2.0, letterSpacing = 50.0,
            ),
        )
        assertTrue(p.useOriginalStyle)
        assertEquals(20f, p.bodyPx)
        assertEquals("屏显臻宋", p.fontBody)
        assertEquals(2f, p.paragraphSpacingEm!!, 1e-9f)
        assertEquals(0.1f, p.letterSpacingEm!!, 1e-9f)
    }
}