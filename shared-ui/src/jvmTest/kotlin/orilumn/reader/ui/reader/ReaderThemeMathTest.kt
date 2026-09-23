package orilumn.reader.ui.reader

import orilumn.reader.data.epub.TocItem
import orilumn.reader.data.settings.ReaderSettings
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderThemeMathTest {

    @Test
    fun parseHexRgb() {
        assertEquals(0xFFFFFFFF.toInt(), ReaderThemeMath.parseHex("#ffffff"))
        assertEquals(0xFFFFFBF0.toInt(), ReaderThemeMath.parseHex("#FFFBF0"))
    }

    @Test
    fun parseHexArgbKeepsAlpha() {
        assertEquals(0x80FF8040.toInt(), ReaderThemeMath.parseHex("#80ff8040"))
    }

    @Test
    fun parseHexFallback() {
        assertEquals(0xFFF4F2EC.toInt(), ReaderThemeMath.parseHex("bogus"))
        assertEquals(0xFFF4F2EC.toInt(), ReaderThemeMath.parseHex(""))
    }

    @Test
    fun hexOfMasksAlpha() {
        assertEquals("#112233", ReaderThemeMath.hexOf(0xFF112233.toInt()))
        assertEquals("#123456", ReaderThemeMath.hexOf(0x123456))
    }

    @Test
    fun applyBgAltersOnlyOneChannel() {
        val s = ReaderSettings(bgOverride = "#112233")
        val out = ReaderThemeMath.applyBg(s, 0xFF112233.toInt(), 0xFFFFFF.toInt(), 0, 200.0)
        assertTrue(out.bgOverride.startsWith("#c8"))
    }

    @Test
    fun applyFgGray() {
        val s = ReaderSettings()
        val out = ReaderThemeMath.applyFg(s, 0xFFFAFBFC.toInt(), 128.0)
        assertEquals("#808080", out.fgOverride)
    }

    @Test
    fun grayOfAverage() {
        assertEquals((0x11 + 0x22 + 0x33) / 3.0, ReaderThemeMath.grayOf(0x112233), 1e-9)
    }

    @Test
    fun themeNameMatchesCustom() {
        val s = ReaderSettings(bgOverride = "#f2ecde", fgOverride = "#222222")
        assertEquals("缃色", ReaderThemeMath.themeName(s, emptyList()))
        val custom = ReaderSettings().copy(bgOverride = "#ff0000", fgOverride = "#00ff00")
        assertEquals("自定义", ReaderThemeMath.themeName(custom, emptyList()))
        assertEquals("我的主题", ReaderThemeMath.themeName(custom, listOf(ThemePreset("我的主题", "#ff0000", "#00ff00"))))
    }

    @Test
    fun labelForBuiltinsAndCustom() {
        assertEquals("缃色", ReaderThemeMath.labelFor("#F2ECDE", ""))
        assertEquals("自定义", ReaderThemeMath.labelFor("#123456", ""))
    }

    @Test
    fun saveThemeSkipsBlankAndDuplicate() {
        val list = listOf(ThemePreset("a", "#111111", "#222222"))
        assertEquals(list, ReaderThemeMath.saveTheme(list, "", ""))
        assertEquals(list, ReaderThemeMath.saveTheme(list, "#111111", "#222222"))
        val next = ReaderThemeMath.saveTheme(list, "#333333", "#444444")
        assertEquals(2, next.size)
        assertEquals("自定义", next.last().label)
    }

    @Test
    fun deleteThemeRemovesMatch() {
        val list = listOf(ThemePreset("a", "#111111", "#222222"), ThemePreset("b", "#333333", "#444444"))
        assertEquals(listOf(ThemePreset("b", "#333333", "#444444")), ReaderThemeMath.deleteTheme(list, "#111111", "#222222"))
        assertEquals(list, ReaderThemeMath.deleteTheme(list, "#ffffff", "#000000"))
    }

    @Test
    fun labels() {
        assertEquals("现代模式", ReaderThemeMath.layoutThemeLabel("modern"))
        assertEquals("传统模式", ReaderThemeMath.layoutThemeLabel("traditional"))
        assertEquals("原书设置", ReaderThemeMath.layoutThemeLabel("original"))
        assertEquals("卷曲", ReaderThemeMath.pageAnimationModeLabel("curl"))
        assertEquals("平滑", ReaderThemeMath.pageAnimationModeLabel("slide"))
    }

    @Test
    fun pxToScaleRoundTrip() {
        assertEquals(1.0, ReaderSettings.fontScaleToRatio(ReaderThemeMath.pxToScale(ReaderSettings.BASE_BODY_PX.toDouble())), 1e-6)
        assertEquals(1.5, ReaderSettings.fontScaleToRatio(ReaderThemeMath.pxToScale(27.0)), 1e-6)
        assertEquals(0.5, ReaderSettings.fontScaleToRatio(ReaderThemeMath.pxToScale(9.0)), 1e-6)
    }

    @Test
    fun pxToScaleClamps() {
        assertTrue(ReaderThemeMath.pxToScale(500.0) <= 100.0)
        assertTrue(ReaderThemeMath.pxToScale(1.0) >= 0.0)
    }

    @Test
    fun paletteForScheme() {
        val day = paletteFor("day")
        val night = paletteFor("night")
        assertEquals(0xFFFAF8F4.toInt(), day.bg.toArgb())
        assertEquals(0xFF1C1C1E.toInt(), night.bg.toArgb())
        assertNotEquals(day.bg, night.bg)
    }
}

class ReaderTocMathTest {

    private fun node(label: String, index: Int, children: List<TocItem> = emptyList()) =
        TocItem(label = label, index = index, fragment = null, children = children)

    @Test
    fun flattenDocumentOrderWithParents() {
        val toc = listOf(
            node("c1", 0, children = listOf(node("c1.1", 1), node("c1.2", 2))),
            node("c2", 3),
        )
        val rows = flattenToc(toc)
        assertEquals(listOf("c1", "c1.1", "c1.2", "c2"), rows.map { it.item.label })
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
        assertEquals(listOf(-1, 0, 0, -1), rows.map { it.parentIndex })
        assertEquals(listOf(0, 1, 2, 3), rows.map { it.idx })
    }

    @Test
    fun flattenEmpty() {
        assertTrue(flattenToc(emptyList()).isEmpty())
    }
}