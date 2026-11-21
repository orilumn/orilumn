package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.data.settings.onlyGlobal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版主题 (layoutTheme) 的新语义（`docs/设置模型整改计划.md` §3.5）：
 * 切模式按目标模式**重置本书全部排版参数**（字号、字体、行距、字距、段距、缩进、页边距），
 * 全局项（亮度 / 阅读主题 / 笔记族 / 三个全局字体槽）不动；再次点击当前模式是无操作。
 * 字体与缩进等基线由主题层样式表（tier 42）或书提供，读者层只写该模式确实要覆盖的可调值。
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
    fun `theme switch resets every book-local typography control`() {
        val dirty = ReaderSettings.DEFAULT.copy(
            layoutTheme = "original",
            fontSize = 22, fontScale = 40.0,
            fontBody = "屏显臻宋", fontTitle = "霞鹜文楷",
            fontWeightAnchors = mapOf("屏显臻宋" to 500),
            fontWeightAnchorsBySlot = mapOf("fontTitle|霞鹜文楷" to 700),
            lineSpacing = 1.8, paragraphSpacing = 1.5, paragraphGap = 20.0,
            letterSpacing = 3.0, cjkLatinSpacing = 60.0, firstLineIndent = 3.0,
            marginTop = 180, marginBottom = 140, marginLeft = 120, marginRight = 110,
            fontSerif = "GlobalSerif", fontSans = "GlobalSans", fontCode = "GlobalMono",
        )
        val base = ReaderSettings.DEFAULT
        val modern = TypographicProfile.withLayoutTheme(dirty, "modern")
        assertEquals("modern", modern.layoutTheme)
        // 字体：正文 / 标题与它们的按槽字重全部回该模式默认。
        assertEquals("", modern.fontBody)
        assertEquals("", modern.fontTitle)
        assertEquals(base.fontWeightAnchors, modern.fontWeightAnchors)
        assertEquals(base.fontWeightAnchorsBySlot, modern.fontWeightAnchorsBySlot)
        // 字号、间距、页边距与文字页全部重置。
        assertEquals(base.fontSize, modern.fontSize)
        assertEquals(base.fontScale, modern.fontScale, 1e-9)
        assertEquals(TypographicProfile.MODERN_LINE_SPACING, modern.lineSpacing!!, 1e-9)
        assertEquals(base.paragraphSpacing, modern.paragraphSpacing)
        assertEquals(base.paragraphGap, modern.paragraphGap, 1e-9)
        assertEquals(base.firstLineIndent, modern.firstLineIndent)
        assertEquals(base.letterSpacing, modern.letterSpacing)
        assertEquals(base.cjkLatinSpacing, modern.cjkLatinSpacing, 1e-9)
        assertEquals(base.marginTop, modern.marginTop)
        assertEquals(base.marginBottom, modern.marginBottom)
        assertEquals(base.marginLeft, modern.marginLeft)
        assertEquals(base.marginRight, modern.marginRight)
        // 全局字体槽保持原样。
        assertEquals("GlobalSerif", modern.fontSerif)
        assertEquals("GlobalSans", modern.fontSans)
        assertEquals("GlobalMono", modern.fontCode)

        val traditional = TypographicProfile.withLayoutTheme(dirty, "traditional")
        assertEquals(TypographicProfile.TRADITIONAL_LINE_SPACING, traditional.lineSpacing!!, 1e-9)
        assertEquals(0.0, traditional.paragraphSpacing!!, 1e-9)
        assertEquals(base.paragraphGap, traditional.paragraphGap, 1e-9)
        assertEquals("", traditional.fontBody)
        assertEquals("", traditional.fontTitle)
    }

    @Test
    fun `original mode also resets every book-local typography control`() {
        val dirty = ReaderSettings.DEFAULT.copy(
            layoutTheme = "modern",
            fontSize = 22, fontScale = 40.0,
            fontBody = "屏显臻宋", fontTitle = "霞鹜文楷",
            fontWeightAnchors = mapOf("屏显臻宋" to 500),
            fontWeightAnchorsBySlot = mapOf("fontTitle|霞鹜文楷" to 700),
            lineSpacing = 1.8, paragraphSpacing = 1.5, paragraphGap = 20.0,
            letterSpacing = 3.0, cjkLatinSpacing = 60.0, firstLineIndent = 3.0,
            marginTop = 180, marginBottom = 140, marginLeft = 120, marginRight = 110,
        )
        val base = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(dirty, "original")
        assertEquals("original", s.layoutTheme)
        assertEquals("", s.fontBody)
        assertEquals("", s.fontTitle)
        assertEquals(base.fontWeightAnchors, s.fontWeightAnchors)
        assertEquals(base.fontWeightAnchorsBySlot, s.fontWeightAnchorsBySlot)
        assertEquals(base.fontSize, s.fontSize)
        assertEquals(base.fontScale, s.fontScale, 1e-9)
        assertEquals(base.lineSpacing, s.lineSpacing)
        assertEquals(base.paragraphSpacing, s.paragraphSpacing)
        assertEquals(base.paragraphGap, s.paragraphGap, 1e-9)
        assertEquals(base.firstLineIndent, s.firstLineIndent)
        assertEquals(base.letterSpacing, s.letterSpacing)
        assertEquals(base.cjkLatinSpacing, s.cjkLatinSpacing, 1e-9)
        assertEquals(base.marginTop, s.marginTop)
        assertEquals(base.marginBottom, s.marginBottom)
        assertEquals(base.marginLeft, s.marginLeft)
        assertEquals(base.marginRight, s.marginRight)
    }

    @Test
    fun `clicking the current mode is a no-op`() {
        val dirty = ReaderSettings.DEFAULT.copy(
            layoutTheme = "traditional",
            fontSize = 24, fontBody = "屏显臻宋", lineSpacing = 2.0,
            paragraphSpacing = 1.2, letterSpacing = 12.0, firstLineIndent = 2.0,
            marginTop = 150,
        )
        for (theme in listOf("original", "modern", "traditional")) {
            val current = dirty.copy(layoutTheme = theme)
            val s = TypographicProfile.withLayoutTheme(current, theme)
            assertTrue("[$theme] 再次点击当前模式必须原样返回", s === current)
        }
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
            // 代码槽 + 通用族替换（衬线/无衬线）也是全局项：切主题一律不碰。
            fontCode = "Mono", fontSerif = "ReaderSerif", fontSans = "ReaderSans",
        )
        for (theme in listOf("original", "modern", "traditional")) {
            val s = TypographicProfile.withLayoutTheme(dirty, theme)
            assertEquals("主题 [$theme] 不得改全局项", dirty.onlyGlobal(), s.onlyGlobal())
        }
    }

    @Test
    fun `theme switch clears reader body and title fonts in every mode`() {
        // 切模式按目标模式重置字体：读者此前给本书挑的正文 / 标题字体也要让位。
        val dirty = ReaderSettings.DEFAULT.copy(
            layoutTheme = "original", fontBody = "屏显臻宋", fontTitle = "霞鹜文楷",
        )
        for (theme in listOf("modern", "traditional", "original")) {
            val current = if (theme == "original") dirty.copy(layoutTheme = "modern") else dirty
            val s = TypographicProfile.withLayoutTheme(current, theme)
            assertEquals("[$theme] 正文字体必须重置", "", s.fontBody)
            assertEquals("[$theme] 标题字体必须重置", "", s.fontTitle)
        }
    }

    @Test
    fun `themes differ from original by useOriginalStyle`() {
        assertTrue(TypographicProfile.build(ReaderSettings.DEFAULT).useOriginalStyle)
        assertFalse(TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern")).useOriginalStyle)
        assertFalse(TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "traditional")).useOriginalStyle)
    }

    @Test
    fun `original preset clears every book-local typography override`() {
        val dirty = ReaderSettings.DEFAULT.copy(
            layoutTheme = "modern",
            fontSize = 22, fontScale = 40.0,
            fontBody = "屏显臻宋", fontTitle = "屏显臻宋",
            lineSpacing = 1.9, paragraphSpacing = 2.0, paragraphGap = 20.0, letterSpacing = 80.0,
            cjkLatinSpacing = 60.0, firstLineIndent = 3.0,
            marginTop = 180, marginBottom = 140, marginLeft = 120, marginRight = 110,
            fontCode = "mono",
        )
        val d = ReaderSettings.DEFAULT
        val s = TypographicProfile.withLayoutTheme(dirty, "original")
        assertEquals("original", s.layoutTheme)
        assertEquals("", s.fontBody)
        assertEquals("", s.fontTitle)
        // 代码字体已是纯全局项：切「原书设置」只清本书覆盖，不碰全局偏好。
        assertEquals("mono", s.fontCode)
        assertEquals(d.fontSize, s.fontSize)
        assertEquals(d.fontScale, s.fontScale, 1e-9)
        assertEquals(d.lineSpacing, s.lineSpacing)
        assertEquals(d.paragraphSpacing, s.paragraphSpacing)
        assertEquals(d.paragraphGap, s.paragraphGap, 1e-9)
        assertEquals(d.letterSpacing, s.letterSpacing)
        assertEquals(d.cjkLatinSpacing, s.cjkLatinSpacing, 1e-9)
        // 首行缩进回到「不声明」，本书重新完全听书（含悬挂缩进）。
        assertEquals(d.firstLineIndent, s.firstLineIndent)
        assertEquals(d.marginTop, s.marginTop)
        assertEquals(d.marginBottom, s.marginBottom)
        assertEquals(d.marginLeft, s.marginLeft)
        assertEquals(d.marginRight, s.marginRight)
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