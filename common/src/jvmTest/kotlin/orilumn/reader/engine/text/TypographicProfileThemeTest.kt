package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.data.settings.onlyGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版主题 (layoutTheme) 的新语义（`docs/设置模型整改计划.md` §3.5）：
 * 切主题写该主题确实要让读者层跟随的那几项（行距、段间距）——可调的基线必须落在设置里、
 * 由 UI 层发声明；基线（缩进 / 正文字体 / 标题字体 / 对齐）交给主题层样式表（tier 42）；
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
        // 行距是滑块项 ⇒ 主题预设写成本书显式值（传统 1.5），由 UI 层发行高声明。
        assertEquals(TypographicProfile.TRADITIONAL_LINE_SPACING, s.lineSpacing!!, 1e-9)
        assertEquals(0.0, s.paragraphSpacing!!, 1e-9)
        assertEquals(100.0, s.paragraphGap, 1e-9)
        // 字族一律不写：正文字体由 traditional.css 的 body/h1..h6 提供，读者层不钉显式值。
        assertEquals("", s.fontBody)
        assertEquals("", s.fontTitle)
    }

    @Test
    fun `modern preset writes no indent full density and sans-serif body font`() {
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(d, "modern")
        assertEquals("modern", s.layoutTheme)
        assertEquals(d.firstLineIndent, s.firstLineIndent)
        // 现代主题默认行距 1.4。
        assertEquals(TypographicProfile.MODERN_LINE_SPACING, s.lineSpacing!!, 1e-9)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing)
        assertEquals(100.0, s.paragraphGap, 1e-9)
        assertEquals("", s.fontBody)
        assertEquals("", s.fontTitle)
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
    fun `theme switch writes the theme line spacing and keeps reader font size and tracking`() {
        val base = ReaderSettings.DEFAULT.copy(fontSize = 22, lineSpacing = 1.8, letterSpacing = 3.0)
        val s = TypographicProfile.withLayoutTheme(base, "traditional")
        assertEquals(22, s.fontSize)
        // 行距是主题预设的一部分（传统 1.5）：切到主题就以预设为准，读者再拖滑块即覆盖。
        assertEquals(TypographicProfile.TRADITIONAL_LINE_SPACING, s.lineSpacing!!, 1e-9)
        assertEquals(3.0, s.letterSpacing!!, 1e-9)
        assertEquals("traditional", s.layoutTheme)
    }

    @Test
    fun `theme defaults reach the profile as explicit line spacing`() {
        val modern = TypographicProfile.build(
            TypographicProfile.withLayoutTheme(ReaderSettings.DEFAULT, "modern"),
        )
        assertEquals(1.4f, modern.lineSpacing!!, 1e-9f)
        val traditional = TypographicProfile.build(
            TypographicProfile.withLayoutTheme(ReaderSettings.DEFAULT, "traditional"),
        )
        assertEquals(1.5f, traditional.lineSpacing!!, 1e-9f)
        // 原书设置回到「不声明」，书自己的行高生效。
        assertTrue(
            TypographicProfile.build(
                TypographicProfile.withLayoutTheme(ReaderSettings.DEFAULT, "original"),
            ).lineSpacing == null,
        )
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
            // 代码槽 + 通用族替换（衬线/无衬线）与它们的字重也是全局项：切主题一律不碰。
            fontCode = "Mono", fontSerif = "ReaderSerif", fontSans = "ReaderSans",
            fontWeightAnchorsGlobal = mapOf("fontSerif|ReaderSerif" to 300),
        )
        for (theme in listOf("original", "modern", "traditional")) {
            val s = TypographicProfile.withLayoutTheme(dirty, theme)
            assertEquals("主题 [$theme] 不得改全局项", dirty.onlyGlobal(), s.onlyGlobal())
        }
    }

    @Test
    fun `theme switch drops generic family residue left by older builds`() {
        // 旧版本切主题会把通用族写进槽（面板永远选不到通用族 ⇒ 必是主题写的残留）。
        // 残留值在 UI 层（tier 44）压住主题层（tier 42）⇒ 切主题字体不变；切主题时清掉。
        val dirty = ReaderSettings.DEFAULT.copy(
            layoutTheme = "original", fontBody = "serif", fontTitle = "sans-serif",
        )
        for (theme in listOf("modern", "traditional")) {
            val s = TypographicProfile.withLayoutTheme(dirty, theme)
            assertEquals("[$theme] 通用族残留必须清掉", "", s.fontBody)
            assertEquals("[$theme] 通用族残留必须清掉", "", s.fontTitle)
        }
        // 读者自己选的具名字族原样保留（主题不越权改读者的显式选择）。
        val named = dirty.copy(fontBody = "屏显臻宋", fontTitle = "霞鹜文楷")
        val s = TypographicProfile.withLayoutTheme(named, "traditional")
        assertEquals("屏显臻宋", s.fontBody)
        assertEquals("霞鹜文楷", s.fontTitle)
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
        // 代码字体已是纯全局项：切「原书设置」只清本书覆盖，不碰全局偏好。
        assertEquals("mono", s.fontCode)
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