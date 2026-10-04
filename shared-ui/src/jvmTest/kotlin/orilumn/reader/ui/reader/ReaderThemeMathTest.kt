package orilumn.reader.ui.reader

import orilumn.reader.data.epub.TocItem
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 内置 8 个主题全是浅底 ⇒ 系统栏图标判据必须给「深色图标」，与修复前的旧判据
     * （`scheme != "night"`）结论一致，即本轮修状态栏图标色**没有改动任何既有主题的表现**。
     *
     * 这条放在 `shared-ui` 而非 `common`：内置主题清单是 UI 层的东西，而依赖方向是
     * `shared-ui → common`，`common` 的测试够不着 `ReaderThemeMath`。
     *
     * 若日后新增内置主题时挑了个深色，这条会红 —— 那时是有意的（该主题本就需要浅色图标），
     * 记得连同 [TypographicProfile.isDarkBackground] 的期望一起更新。
     */
    @Test
    fun `every built-in theme is light so bar icons stay dark as before`() {
        val presets = ReaderThemeMath.BUILTIN_THEMES.filter { it.bg.isNotBlank() } // 原书设置无固定底色
        assertTrue("内置主题不应为空", presets.isNotEmpty())
        for (p in presets) {
            val bg = ReaderThemeMath.parseHex(p.bg)
            assertFalse("内置主题「${p.label}」(${p.bg}) 不该被判成暗色", TypographicProfile.isDarkBackground(bg))
            val s = ReaderSettings.DEFAULT.copy(scheme = "day", bgOverride = p.bg)
            assertTrue(
                "内置主题「${p.label}」(${p.bg}) 是浅底，必须仍要深色图标（与旧判据一致）",
                TypographicProfile.wantsLightBarIcons(s),
            )
        }
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

    // ---- 目录项1-2（用户层·共享纯函数） ----

    private fun frag(label: String, index: Int, fragment: String) =
        TocItem(label = label, index = index, fragment = fragment)

    private fun chapterToc() = listOf(
        node("c1", 0, children = listOf(frag("c1.1", 0, "s1"), frag("c1.2", 0, "s2"))),
        node("c2", 1, children = listOf(frag("c2.1", 1, "t1"))),
        node("c3", 2),
    )

    @Test
    fun resolvePrefersDeepestFragmentHit() {
        val rows = flattenToc(chapterToc())
        // 当页同时含 s1/s2 → 取文档序最末（最贴近阅读位置的子标题）。
        assertEquals(2, resolveTocCurrentRow(rows, 0, setOf("s1", "s2")))
        assertEquals(1, resolveTocCurrentRow(rows, 0, setOf("s1")))
    }

    @Test
    fun resolveFallsBackToChapterHead() {
        val rows = flattenToc(chapterToc())
        assertEquals(0, resolveTocCurrentRow(rows, 0, emptySet()))
        // 无命中的 fragment 同样回退章首。
        assertEquals(0, resolveTocCurrentRow(rows, 0, setOf("nope")))
        // 无子标题的章 → 章行本身。
        assertEquals(5, resolveTocCurrentRow(rows, 2, setOf("s1")))
        assertEquals(-1, resolveTocCurrentRow(rows, 9, setOf("s1")))
        assertEquals(-1, resolveTocCurrentRow(emptyList(), 0, emptySet()))
    }

    @Test
    fun initialCollapsedKeepsOnlyCurrentRootExpanded() {
        val rows = flattenToc(chapterToc())
        // 当前在 c1 子树 → 只折叠 c2（c3 无子节点不可折叠，不在集合）。
        assertEquals(setOf(3), initialTocCollapsed(rows, 2))
        // 当前在无子节点的 c3 → 顶层可折叠节点全折。
        assertEquals(setOf(0, 3), initialTocCollapsed(rows, 5))
        // 无当前行 → 全折。
        assertEquals(setOf(0, 3), initialTocCollapsed(rows, -1))
        // 扁平目录无可折叠节点 → 空集合。
        val flat = flattenToc(listOf(node("a", 0), node("b", 1)))
        assertTrue(initialTocCollapsed(flat, 0).isEmpty())
    }

    @Test
    fun toggleAccordionOnTopLevel() {
        val rows = flattenToc(chapterToc())
        var collapsed = initialTocCollapsed(rows, 2) // {3}
        // 展开 c2 → c1 同步折叠（手风琴）。
        collapsed = toggleTocCollapsed(collapsed, rows, 3)
        assertEquals(setOf(0), collapsed)
        // 折叠 c2 → 简单加回，不波及其他。
        collapsed = toggleTocCollapsed(collapsed, rows, 3)
        assertEquals(setOf(0, 3), collapsed)
        // 嵌套节点切换只管自己。
        collapsed = toggleTocCollapsed(collapsed, rows, 1)
        assertEquals(setOf(0, 3, 1), collapsed)
        collapsed = toggleTocCollapsed(collapsed, rows, 1)
        assertEquals(setOf(0, 3), collapsed)
        // 越界下标无操作。
        assertEquals(collapsed, toggleTocCollapsed(collapsed, rows, 99))
    }
}