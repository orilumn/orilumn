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
            coverStretch = true,
        )
        assertEquals(custom, ReaderSettings.fromJson(custom.toJson()))
    }

    @Test
    fun `paragraph spacing and gap persist through round-trip`() {
        val parsed = ReaderSettings.fromJson("""{"paragraphSpacing":150,"paragraphGap":150}""")
        assertEquals(150.0, parsed.paragraphSpacing, 0.0)
        assertEquals(150.0, parsed.paragraphGap, 0.0)
        val reread = ReaderSettings.fromJson(parsed.toJson())
        assertEquals(parsed, reread)
        assertEquals(150.0, reread.paragraphSpacing, 0.0)
        assertEquals(150.0, reread.paragraphGap, 0.0)
    }
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
            "useOriginalStyle", "useUserScripts", "pageAnim", "autoContinue", "pageNum", "coverStretch",
            "fontScale", "scheme", "bgOverride", "fgOverride", "layoutTheme",
        ).forEach { assertTrue("missing key $it", obj.containsKey(it)) }
        assertNotNull(obj.keys)
        assertFalse(ReaderSettings.DEFAULT.useOriginalStyle)
    }

    @Test
    fun `old coverProportional migrates inverted to coverStretch`() {
        assertEquals(true, ReaderSettings.fromJson("{\"coverProportional\":false}").coverStretch)
        assertEquals(false, ReaderSettings.fromJson("{\"coverProportional\":true}").coverStretch)
        assertEquals(true, ReaderSettings.fromJson("{\"coverStretch\":true}").coverStretch)
        assertEquals(true, ReaderSettings.DEFAULT.coverStretch)
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

    // ---- 混排字距 `cjkLatinSpacing` 的 schemaVersion v1 迁移（一次性）----

    /**
     * 用户报「中西字距默认值是 25？打开设置看到的是 0」——根因是该字段此前是**整套死代码**
     * （存得下、拖得动、没人消费），用户在盲选滑块上随手拖出的值被当成了明确意图继承。
     * v1 迁移：老存档（无 `schemaVersion` 键）一律回到默认 25。
     */
    @Test
    fun `混排字距 老存档无 schemaVersion 一律回到默认 25`() {
        for (stored in listOf(0.0, 1.0, 25.0, 80.0)) {
            val migrated = ReaderSettings.fromJson("""{"cjkLatinSpacing":$stored}""")
            assertEquals(
                "老存档（无 schemaVersion）里的 cjkLatinSpacing=$stored 必须回到默认值",
                ReaderSettings.DEFAULT.cjkLatinSpacing,
                migrated.cjkLatinSpacing,
                0.0,
            )
        }
    }

    /**
     * v1 之后**用户的每个值都尊重，包括 0**。
     *
     * 这条是迁移的反向锁，也是需求「滑块为 0 时不做多余动作」的持久化半边：
     * 若实现改成判值（`stored == 0 → 25`），故意关掉混排字距的用户**每次启动都会被改回 25**，
     * 症状是「我明明关了，它自己弹回来了」。版本号只对「写档那一刻还没有的语义」动手一次。
     */
    @Test
    fun `混排字距 schemaVersion 大于等于 1 时 0 必须原样保留`() {
        for (stored in listOf(0.0, 10.0, 25.0, 100.0)) {
            val parsed = ReaderSettings.fromJson(
                """{"schemaVersion":1,"cjkLatinSpacing":$stored}"""
            )
            assertEquals("schemaVersion=1 的存档必须原样保留用户设的值", stored, parsed.cjkLatinSpacing, 0.0)
        }
    }

    /** 迁移后的存档必须**写回**当前版本号，否则下次启动会再迁移一次（幂等性靠这个键）。 */
    @Test
    fun `混排字距 迁移后写回当前 schemaVersion`() {
        val migrated = ReaderSettings.fromJson("""{"cjkLatinSpacing":80}""")
        assertEquals(
            ReaderSettings.CURRENT_SCHEMA_VERSION,
            migrated.schemaVersion,
        )
        // 再走一轮：已经是当前版本 ⇒ 值不再被改（幂等）。
        val again = ReaderSettings.fromJson(migrated.toJson())
        assertEquals(ReaderSettings.DEFAULT.cjkLatinSpacing, again.cjkLatinSpacing, 0.0)
    }

    /** 缺该键的老存档读到的是默认值，再被迁移改一次仍是默认值（不能变成 0）。 */
    @Test
    fun `混排字距 老存档缺该键时读到默认值`() {
        val migrated = ReaderSettings.fromJson("""{"scheme":"day"}""")
        assertEquals(25.0, migrated.cjkLatinSpacing, 0.0)
    }

    // ---- 按书 overlay 稀疏化（标题字体 bug 根因）----

    private fun persistInTemp(): Triple<java.io.File, PerBookSettings, ReaderSettingsStore> {
        val dir = java.nio.file.Files.createTempDirectory("orilumn-sparse").toFile()
        val stores = PerBookSettings(
            ReaderSettingsStore(dir.absolutePath),
            BookSettingsStore(dir.absolutePath),
        )
        return Triple(dir, stores, ReaderSettingsStore(dir.absolutePath))
    }

    @Test
    fun `persist 只钉本次改动 没动过的槽位永不进 overlay`() {
        // 回归“调标题影响正文”：以前任何一次提交都全量快照，别的书/全局的字体值被冻进本书。
        val (dir, persist, _) = persistInTemp()
        try {
            val global = ReaderSettings.DEFAULT.copy(fontBody = "A", fontTitle = "B")
            persist.persist(null, global)
            // 在某书只动字号：overlay 只能有 fontScale 一项。
            val next = persist.effectiveFor(7L).copy(fontScale = 60.0)
            persist.persist(7L, next)
            val overlay = BookSettingsStore(dir.absolutePath).load(7L)
            assertEquals(60.0, overlay.fontScale)
            assertNull("没动过的正文槽不得快照", overlay.fontBody)
            assertNull("没动过的标题槽不得快照", overlay.fontTitle)
            assertNull("没动过的代码槽不得快照", overlay.fontCode)
            // 生效值：字号用书的，字体跟全局。
            val eff = persist.effectiveFor(7L)
            assertEquals(60.0, eff.fontScale, 0.0)
            assertEquals("A", eff.fontBody)
            assertEquals("B", eff.fontTitle)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `存量全量 overlay 的陈旧 pin 在下次提交时自愈`() {
        // 设备实证：1000.json 曾是全量快照（body=title=普惠体），之后调全局标题该书不动。
        val (dir, persist, globals) = persistInTemp()
        try {
            persist.persist(null, ReaderSettings.DEFAULT.copy(fontBody = "A", fontTitle = "B"))
            // 模拟老版本的全量快照：body 与全局一致（陈旧 pin），title 是真正的按书覆盖。
            BookSettingsStore(dir.absolutePath).save(
                7L,
                BookSettings(fontScale = 60.0, fontBody = "A", fontTitle = "C"),
            )
            // 本书再动一个无关项：陈旧 pin（body=A=全局）恢复跟随，真正的 pin（title=C）保留。
            val next = persist.effectiveFor(7L).copy(pageNum = true)
            persist.persist(7L, next)
            val overlay = BookSettingsStore(dir.absolutePath).load(7L)
            assertNull("与全局一致的陈旧 pin 必须恢复跟随", overlay.fontBody)
            assertEquals("真正的按书覆盖必须保留", "C", overlay.fontTitle)
            // 生效值不变：body 跟全局 A，title 用书的 C。
            val eff = persist.effectiveFor(7L)
            assertEquals("A", eff.fontBody)
            assertEquals("C", eff.fontTitle)
            assertEquals(globals.load().fontTitle, "B")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `本次刚改的字段即使与全局一致也保留为 pin`() {
        // 用户在本书面显式选了与全局相同的值：那是明确意图，不得被自愈清掉。
        val (dir, persist, _) = persistInTemp()
        try {
            persist.persist(null, ReaderSettings.DEFAULT.copy(fontTitle = "B"))
            val next = persist.effectiveFor(7L).copy(fontTitle = "B", fontScale = 60.0)
            // baseline 里 title 已是 B（跟全局），scale 60 是改动——为测豁免，先把 overlay 置空后显式提交 title。
            persist.persist(7L, next)
            // 此时 title 无 diff（=baseline），overlay 只有 scale。
            var overlay = BookSettingsStore(dir.absolutePath).load(7L)
            assertNull(overlay.fontTitle)
            // 改全局标题后再在本书面显式选回 B：changed 含 title=B，必须钉住。
            persist.persist(null, ReaderSettings.DEFAULT.copy(fontTitle = "D"))
            val next2 = persist.effectiveFor(7L).copy(fontTitle = "B")
            persist.persist(7L, next2)
            overlay = BookSettingsStore(dir.absolutePath).load(7L)
            assertEquals("B", overlay.fontTitle)
            assertEquals("B", persist.effectiveFor(7L).fontTitle)
        } finally {
            dir.deleteRecursively()
        }
    }
}
