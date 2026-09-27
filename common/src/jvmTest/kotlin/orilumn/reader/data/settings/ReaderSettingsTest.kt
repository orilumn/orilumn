package orilumn.reader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ReaderSettings] serialization/parsing/fallback behavior:
 *  - the default set round-trips consistently;
 *  - partially missing/invalid fields fall back tolerantly (backward compatible);
 *  - unknown fields are silently ignored;
 *  - empty/broken JSON falls back to defaults.
 */
class ReaderSettingsTest {

    @Test
    fun `default round-trips through fromJson`() {
        val parsed = ReaderSettings.fromJson(ReaderSettings.DEFAULT.toJson())
        assertEquals(ReaderSettings.DEFAULT, parsed)
    }

    @Test
    fun `full custom round-trips`() {
        val custom = ReaderSettings(
            theme = "dark",
            fontSize = 22,
            lineSpacing = 2.0,
            marginTop = 40, marginBottom = 40, marginLeft = 48, marginRight = 48,
            fontBody = "Songti",
            fontTitle = "Heiti",
            fontCode = "Monaco",
            useOriginalStyle = true,
            useUserScripts = true,
            pageAnim = false,
            autoContinue = false,
            pageNum = true,
            coverProportional = false,
        )
        assertEquals(custom, ReaderSettings.fromJson(custom.toJson()))
    }

    @Test
    fun `paragraph spacing and gap persist through round-trip`() {
        val parsed = ReaderSettings.fromJson("""{"paragraphSpacing":1.2,"paragraphGap":150}""")
        assertEquals(1.2, parsed.paragraphSpacing, 0.0)
        assertEquals(150.0, parsed.paragraphGap, 0.0)
        // Persisting and reading back (reproducing the "paragraph spacing reset on open" path) should preserve
        // rather than fall back to the defaults 0/100
        val reread = ReaderSettings.fromJson(parsed.toJson())
        assertEquals(parsed, reread)
        assertEquals(1.2, reread.paragraphSpacing, 0.0)
        assertEquals(150.0, reread.paragraphGap, 0.0)
    }

    @Test
    fun `partial json falls back to defaults for missing fields`() {
        val parsed = ReaderSettings.fromJson("""{"fontSize":20}""")
        assertEquals(20, parsed.fontSize)          // the provided value takes effect
        assertEquals(ReaderSettings.DEFAULT.theme, parsed.theme)         // falls back to default when missing
        assertEquals(ReaderSettings.DEFAULT.lineSpacing, parsed.lineSpacing, 0.0)
        assertEquals(ReaderSettings.DEFAULT.useOriginalStyle, parsed.useOriginalStyle)
    }

    @Test
    fun `unknown fields are ignored`() {
        val parsed = ReaderSettings.fromJson("""{"fontSize":19,"someFutureField":"x"}""")
        assertNull(parsed.toJsonObject()["someFutureField"])
        assertEquals(19, parsed.fontSize)
    }

    @Test
    fun `blank json returns default`() {
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson(null))
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson(""))
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson("   "))
    }

    @Test
    fun `malformed json returns default`() {
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson("not json at all"))
        assertEquals(ReaderSettings.DEFAULT, ReaderSettings.fromJson("{broken"))
    }

    @Test
    fun `show hidden fonts defaults hidden and round-trips`() {
        assertFalse(ReaderSettings.DEFAULT.showHiddenFonts)
        // 旧 JSON 无此键 → 默认隐藏。
        assertFalse(ReaderSettings.fromJson("""{"fontSize":20}""").showHiddenFonts)
        val on = ReaderSettings.fromJson("""{"showHiddenFonts":true}""")
        assertTrue(on.showHiddenFonts)
        assertEquals(on, ReaderSettings.fromJson(on.toJson()))
    }

    @Test
    fun `show hidden fonts persists global never overlay`() {
        // 开关纯全局：persist 直写全局，永不进按书 overlay。
        val dir = java.nio.file.Files.createTempDirectory("orilumn-settings").toFile()
        try {
            val persist = PerBookSettings(
                ReaderSettingsStore(dir.absolutePath),
                BookSettingsStore(dir.absolutePath),
            )
            persist.persist(7L, ReaderSettings.DEFAULT.copy(showHiddenFonts = true))
            assertTrue(ReaderSettingsStore(dir.absolutePath).load().showHiddenFonts)
            // 只动开关时 overlay 保持空白（开关永不进按书层）。
            val overlay = BookSettingsStore(dir.absolutePath).load(7L)
            assertEquals(BookSettings.EMPTY, overlay)
            // 读回：本书生效值继承全局开关。
            assertTrue(persist.effectiveFor(7L).showHiddenFonts)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `weight anchors round-trip and persist global never overlay`() {
        assertTrue(ReaderSettings.DEFAULT.fontWeightAnchors.isEmpty())
        val parsed = ReaderSettings.fromJson(
            """{"fontWeightAnchors":{"F":700,"G":"x","H":50}}""",
        )
        // 合法 700 留下，非整数与越界丢弃。
        assertEquals(mapOf("F" to 700), parsed.fontWeightAnchors)
        assertEquals(parsed, ReaderSettings.fromJson(parsed.toJson()))
        // persist 直写全局，永不进按书 overlay。
        val dir = java.nio.file.Files.createTempDirectory("orilumn-anchors").toFile()
        try {
            val persist = PerBookSettings(
                ReaderSettingsStore(dir.absolutePath),
                BookSettingsStore(dir.absolutePath),
            )
            persist.persist(7L, ReaderSettings.DEFAULT.copy(fontWeightAnchors = mapOf("F" to 700)))
            assertEquals(mapOf("F" to 700), ReaderSettingsStore(dir.absolutePath).load().fontWeightAnchors)
            assertEquals(BookSettings.EMPTY, BookSettingsStore(dir.absolutePath).load(7L))
            assertEquals(mapOf("F" to 700), persist.effectiveFor(7L).fontWeightAnchors)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `json output contains all keys`() {
        val obj = ReaderSettings.DEFAULT.toJsonObject()
        listOf(
            "theme", "fontSize", "lineSpacing",
            "marginTop", "marginBottom", "marginLeft", "marginRight",
            "fontBody", "fontTitle", "fontCode",
            "useOriginalStyle", "useUserScripts", "pageAnim", "autoContinue", "pageNum", "coverProportional",
            "fontScale", "scheme", "bgOverride", "fgOverride", "layoutTheme",
        ).forEach { assertTrue("missing key $it", obj.containsKey(it)) }
        assertNotNull(obj.keys)
        assertFalse(ReaderSettings.DEFAULT.useOriginalStyle)
    }

    // ── Style system · font-size relative step mapping ─────────────────────────
    @Test
    fun `font scale midpoint is identity`() {
        assertEquals(1.0, ReaderSettings.fontScaleToRatio(50.0), 1e-9)
        assertEquals(0.5, ReaderSettings.fontScaleToRatio(0.0), 1e-9)
        assertEquals(2.0, ReaderSettings.fontScaleToRatio(100.0), 1e-9)
        // inverse: 1.0 ↔ 50
        assertEquals(50.0, ReaderSettings.ratioToFontScale(1.0), 1e-9)
    }

    @Test
    fun `legacy absolute fontsize migrates to scale`() {
        // the old set has only fontSize=18 (default) → migrated to 50 (default step)
        val defaultMigrated = ReaderSettings.fromJson("""{"fontSize":18}""")
        assertEquals(50.0, defaultMigrated.fontScale, 1e-9)
        // old fontSize=36 (2x) → migrated to 100
        val doubleMigrated = ReaderSettings.fromJson("""{"fontSize":36}""")
        assertEquals(100.0, doubleMigrated.fontScale, 1e-9)
    }

    @Test
    fun `legacy theme migrates to scheme and bg override`() {
        // old theme=dark (night) → the full night palette
        val dark = ReaderSettings.fromJson("""{"theme":"dark"}""")
        assertEquals("night", dark.scheme)
        assertEquals("", dark.bgOverride)
        // old theme=sepia (parchment) → day palette + background override
        val sepia = ReaderSettings.fromJson("""{"theme":"sepia"}""")
        assertEquals("day", sepia.scheme)
        assertEquals("#f4f2ec", sepia.bgOverride)
        // the new scheme fields take priority and are no longer overridden by the old theme
        val explicit = ReaderSettings.fromJson("""{"theme":"white","scheme":"night","bgOverride":"#112233"}""")
        assertEquals("night", explicit.scheme)
        assertEquals("#112233", explicit.bgOverride)
    }

    @Test
    fun `legacy margin preset migrates to per-side px`() {
        // old margin=compact → all four margins enlarged (narrower content line width)
        val compact = ReaderSettings.fromJson("""{"margin":"compact"}""")
        assertEquals(40, compact.marginTop)
        assertEquals(48, compact.marginLeft)
        assertEquals(48, compact.marginRight)
        // old margin=wide → margins reduced
        val wide = ReaderSettings.fromJson("""{"margin":"wide"}""")
        assertEquals(16, wide.marginTop)
        assertEquals(16, wide.marginLeft)
        // new independent fields take priority, ignoring the old margin preset
        val explicit = ReaderSettings.fromJson("""{"margin":"compact","marginTop":10,"marginLeft":20}""")
        assertEquals(10, explicit.marginTop)
        assertEquals(20, explicit.marginLeft)
        assertEquals(50, explicit.marginRight) // missing direction falls back to default (ReaderSettings.DEFAULT.marginRight=50)
    }

    @Test
    fun `new style fields round-trip`() {
        val custom = ReaderSettings(
            fontScale = 72.0,
            scheme = "night",
            bgOverride = "#123456",
            fgOverride = "#abcdef",
            layoutTheme = "traditional",
        )
        assertEquals(custom, ReaderSettings.fromJson(custom.toJson()))
    }

    @Test
    fun `legacy five-category fonts migrate to element-type slots`() {
        // the old five-category fontBody/fontTitle/fontCode are kept by name; fontBold/fontItalic have no matching
        // slot and are dropped; fontKai (temporary slot) is dropped
        val migrated = ReaderSettings.fromJson(
            """{"fontBody":"Songti","fontTitle":"Heiti","fontCode":"Monaco","fontBold":"B","fontItalic":"I"}"""
        )
        assertEquals("Songti", migrated.fontBody)
        assertEquals("Heiti", migrated.fontTitle)
        assertEquals("Monaco", migrated.fontCode)
    }

    @Test
    fun `legacy temporary kaiti-slot is dropped`() {
        // the removed "楷书" temporary slot: residual fontKai in old data is no longer read (reset to empty default)
        val migrated = ReaderSettings.fromJson("""{"fontKai":"Kaiti"}""")
        assertEquals("", migrated.fontBody)
        assertEquals("", migrated.fontTitle)
        assertEquals("", migrated.fontCode)
    }
}