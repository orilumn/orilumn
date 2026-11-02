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
        val parsed = ReaderSettings.fromJson(ReaderSettings.DEFAULT.fullJson())
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
            layoutTheme = "traditional",
            useUserScripts = true,
            pageAnim = false,
            autoContinue = false,
            pageNum = true,
            coverStretch = true,
        )
        assertEquals(custom, ReaderSettings.fromJson(custom.fullJson()))
    }

    @Test
    fun `paragraph spacing is absolute em and legacy percent migrates once`() {
        // 新语义：绝对值 em（0..2），原样往返。
        val parsed = ReaderSettings.fromJson("""{"paragraphSpacing":1.4,"paragraphGap":150}""")
        assertEquals(1.4, parsed.paragraphSpacing!!, 1e-9)
        assertEquals(150.0, parsed.paragraphGap, 0.0)
        val reread = ReaderSettings.fromJson(parsed.fullJson())
        assertEquals(parsed, reread)
        assertEquals(1.4, reread.paragraphSpacing!!, 1e-9)
        assertEquals(150.0, reread.paragraphGap, 0.0)

        // 旧档百分比（> 2 一定是旧口径）：100 = 原书节奏 → 不覆盖（null）；其余 ÷100 夹到 0..2。
        assertNull(ReaderSettings.fromJson("""{"paragraphSpacing":100}""").paragraphSpacing)
        assertEquals(1.5, ReaderSettings.fromJson("""{"paragraphSpacing":150}""").paragraphSpacing!!, 1e-9)
        assertEquals(2.0, ReaderSettings.fromJson("""{"paragraphSpacing":900}""").paragraphSpacing!!, 1e-9)
    }
    fun `partial json falls back to defaults for missing fields`() {
        val parsed = ReaderSettings.fromJson("""{"fontSize":20}""")
        assertEquals(20, parsed.fontSize)          // the provided value takes effect
        assertEquals(ReaderSettings.DEFAULT.theme, parsed.theme)         // falls back to default when missing
        assertEquals(ReaderSettings.DEFAULT.lineSpacing, parsed.lineSpacing)
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
        assertEquals(on, ReaderSettings.fromJson(on.fullJson()))
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
    fun `笔记设置是全局项 只写全局 不写本书`() {
        // 笔记族（色槽/默认格/上次类别线型/批注字体字号）是全局项：与书无关的读者印记，
        // 任何一本书里改，所有书同步生效；本书 overlay 一个字节都不动。
        val dir = java.nio.file.Files.createTempDirectory("orilumn-annotation").toFile()
        try {
            val persist = PerBookSettings(
                ReaderSettingsStore(dir.absolutePath),
                BookSettingsStore(dir.absolutePath),
            )
            val next = ReaderSettings.DEFAULT.copy(
                highlightColorSlots = listOf(0x11223344, 0x22334455, 0x33445566, 0x44556677, 0x55667788),
                underlineDefaultSlot = 3,
                annotationLastKind = "underline",
                annotationLastLineStyle = "wavy",
                noteFont = "Noto Serif CJK SC",
                noteFontScale = 1.35f,
            )
            persist.persist(7L, next)
            // 全局同步写入：所有书（含未阅读的新书）共享同一份。
            val saved = ReaderSettingsStore(dir.absolutePath).load()
            assertEquals(next.highlightColorSlots, saved.highlightColorSlots)
            assertEquals(3, saved.underlineDefaultSlot)
            assertEquals("underline", saved.annotationLastKind)
            assertEquals("wavy", saved.annotationLastLineStyle)
            assertEquals("Noto Serif CJK SC", saved.noteFont)
            assertEquals(1.35f, saved.noteFontScale, 0.0001f)
            // 本书 overlay 保持空：全局项永不进本书。
            assertEquals(BookSettings.EMPTY, BookSettingsStore(dir.absolutePath).load(7L))
            // 读回：任何书都看到同一份全局笔记设置。
            assertEquals("Noto Serif CJK SC", persist.effectiveFor(7L).noteFont)
            assertEquals("Noto Serif CJK SC", persist.effectiveFor(8L).noteFont)
            assertEquals(3, persist.effectiveFor(8L).underlineDefaultSlot)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `笔记设置跨书共享 换主题不动它`() {
        // 笔记是全局项：书 7 调完，书 8 看到同一份；书 8 再调，书 7 跟着变；
        // 书 7 换排版主题（私有项）不会碰笔记，也不会把笔记写进本书。
        val (dir, persist, _) = persistInTemp()
        try {
            persist.persist(7L, ReaderSettings.DEFAULT.copy(noteFont = "Songti", noteFontScale = 1.5f))
            assertEquals("Songti", persist.effectiveFor(8L).noteFont)
            persist.persist(8L, persist.effectiveFor(8L).copy(noteFont = "Heiti", noteFontScale = 0.8f))
            assertEquals("Heiti", persist.effectiveFor(7L).noteFont)
            persist.persist(7L, persist.effectiveFor(7L).copy(layoutTheme = "traditional"))
            assertEquals("traditional", persist.effectiveFor(7L).layoutTheme)
            assertEquals("Heiti", persist.effectiveFor(7L).noteFont)
            assertEquals(0.8f, persist.effectiveFor(7L).noteFontScale, 0.0001f)
            // 主题是私有项：书 8 仍是原书主题。
            assertEquals("original", persist.effectiveFor(8L).layoutTheme)
            // 本书 overlay 里只有私有键，没有笔记键。
            val overlay7 = BookSettingsStore(dir.absolutePath).load(7L)
            assertEquals("traditional", overlay7.layoutTheme)
            assertFalse(overlay7.toJsonObject().containsKey("noteFont"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `annotation color slots round-trip and fall back safely`() {
        assertEquals(
            ReaderSettings.ANNOTATION_COLOR_SLOT_COUNT,
            ReaderSettings.DEFAULT.highlightColorSlots.size,
        )
        assertEquals(
            ReaderSettings.ANNOTATION_COLOR_SLOT_COUNT,
            ReaderSettings.DEFAULT.underlineColorSlots.size,
        )
        val custom = ReaderSettings(
            highlightColorSlots = listOf(0x11223344, 0x22334455, 0x33445566, 0x44556677, 0x55667788),
            underlineColorSlots = listOf(0xFF010203.toInt(), 0xFF040506.toInt(), 0xFF070809.toInt(), 0xFF0A0B0C.toInt(), 0xFF0D0E0F.toInt()),
            highlightDefaultSlot = 3,
            underlineDefaultSlot = 4,
        )
        assertEquals(custom, ReaderSettings.fromJson(custom.fullJson()))

        // 缺键 / 非数组 → 默认色槽；短列表用默认值补齐；长列表截断到固定槽数。
        assertEquals(ReaderSettings.DEFAULT.highlightColorSlots, ReaderSettings.fromJson("""{"fontSize":20}""").highlightColorSlots)
        assertEquals(ReaderSettings.DEFAULT.underlineColorSlots, ReaderSettings.fromJson("""{"underlineColorSlots":"x"}""").underlineColorSlots)
        val short = ReaderSettings.fromJson("""{"highlightColorSlots":[1,2]}""")
        assertEquals(5, short.highlightColorSlots.size)
        assertEquals(1, short.highlightColorSlots[0])
        assertEquals(ReaderSettings.DEFAULT.highlightColorSlots[2], short.highlightColorSlots[2])
        val long = ReaderSettings.fromJson("""{"underlineColorSlots":[1,2,3,4,5,6,7]}""")
        assertEquals(5, long.underlineColorSlots.size)
        // 默认槽下标越界时钳到有效范围。
        val clamped = ReaderSettings.fromJson("""{"highlightDefaultSlot":99,"underlineDefaultSlot":-3}""")
        assertEquals(4, clamped.highlightDefaultSlot)
        assertEquals(0, clamped.underlineDefaultSlot)
    }

    @Test
    fun `weight anchors are per book and never write global`() {
        assertTrue(ReaderSettings.DEFAULT.fontWeightAnchors.isEmpty())
        val parsed = ReaderSettings.fromJson(
            """{"fontWeightAnchors":{"F":700,"G":"x","H":50}}""",
        )
        // 合法 700 留下，非整数与越界丢弃。
        assertEquals(mapOf("F" to 700), parsed.fontWeightAnchors)
        assertEquals(parsed, ReaderSettings.fromJson(parsed.fullJson()))
        // 私有项：只进本书 overlay，全局文件里没有（别的书回到预设字重）。
        val dir = java.nio.file.Files.createTempDirectory("orilumn-anchors").toFile()
        try {
            val persist = PerBookSettings(
                ReaderSettingsStore(dir.absolutePath),
                BookSettingsStore(dir.absolutePath),
            )
            persist.persist(7L, ReaderSettings.DEFAULT.copy(fontWeightAnchors = mapOf("F" to 700)))
            assertEquals(mapOf("F" to 700), BookSettingsStore(dir.absolutePath).load(7L).fontWeightAnchors)
            assertTrue(ReaderSettingsStore(dir.absolutePath).load().fontWeightAnchors.isEmpty())
            assertEquals(mapOf("F" to 700), persist.effectiveFor(7L).fontWeightAnchors)
            assertTrue(persist.effectiveFor(8L).fontWeightAnchors.isEmpty())
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
            "useUserScripts", "pageAnim", "autoContinue", "pageNum", "coverStretch",
            "fontScale", "scheme", "bgOverride", "fgOverride", "layoutTheme",
            "highlightColorSlots", "underlineColorSlots",
            "highlightDefaultSlot", "underlineDefaultSlot",
            "annotationLastKind", "annotationLastLineStyle",
            "noteFont", "noteFontScale",
            "noteCollapseAll", "noteBorder", "noteBackground", "noteTextSameColor",
        ).forEach { assertTrue("missing key $it", obj.containsKey(it)) }
        assertNotNull(obj.keys)
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
        assertEquals(custom, ReaderSettings.fromJson(custom.fullJson()))
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
        val again = ReaderSettings.fromJson(migrated.fullJson())
        assertEquals(ReaderSettings.DEFAULT.cjkLatinSpacing, again.cjkLatinSpacing, 0.0)
    }

    /** 缺该键的老存档读到的是默认值，再被迁移改一次仍是默认值（不能变成 0）。 */
    @Test
    fun `混排字距 老存档缺该键时读到默认值`() {
        val migrated = ReaderSettings.fromJson("""{"scheme":"day"}""")
        assertEquals(25.0, migrated.cjkLatinSpacing, 0.0)
    }

    // ---- 迁移（§5-3）：旧档的行距 1.5 / 缩进 0 在**一次性迁移**时视为「没配」----

    @Test
    fun `旧本书档里行距 1_5 与缩进 0 迁移成没配`() {
        // 旧模型两项必有值（行距默认 1.5、缩进默认 0）。迁移那一次按「没配」读，
        // 否则升级瞬间会把本书行高压回 1.5、缩进按「明确不缩进」抹平（§5-3）。
        val overlay = BookSettings.fromJson("""{"lineSpacing":1.5,"firstLineIndent":0.0}""", legacy = true)
        assertNull(overlay.lineSpacing)
        assertNull(overlay.firstLineIndent)
    }

    @Test
    fun `旧本书档里行距与缩进的真值原样保留`() {
        val overlay = BookSettings.fromJson("""{"lineSpacing":1.7,"firstLineIndent":2.0}""", legacy = true)
        assertEquals(1.7, overlay.lineSpacing!!, 1e-9)
        assertEquals(2.0, overlay.firstLineIndent!!, 1e-9)
    }

    @Test
    fun `常规读档不改写 1_5 与 0 它们是读者的真值`() {
        // 反向锁（用户实测回归）：迁移只做一次。读者把行距设成 1.5、缩进设成 0 之后，
        // 每次重开书都必须原样读回，不能被当成「没配」打回原书的值。
        val overlay = BookSettings.fromJson("""{"lineSpacing":1.5,"firstLineIndent":0.0}""")
        assertEquals(1.5, overlay.lineSpacing!!, 1e-9)
        assertEquals(0.0, overlay.firstLineIndent!!, 1e-9)
    }

    @Test
    fun `行距设成 1_5 后关书重开仍是 1_5`() {
        // 端到端半条链（真实落盘 + 重新读）：书本身行高 1.3，读者在本书把行距设成 1.5。
        val dir = java.nio.file.Files.createTempDirectory("orilumn-ls15").toFile()
        try {
            val books = BookSettingsStore(dir.absolutePath)
            // 旧档：整份快照写进来的 1.5（迁移那一次要按「没配」读，避免升级瞬间压掉书的行高）。
            java.io.File(dir, "books").mkdirs()
            java.io.File(dir, "books/7.json").writeText("""{"lineSpacing":1.5}""")
            assertNull("迁移一次：旧档的 1.5 视为没配", books.load(7L).lineSpacing)

            // 迁移之后读者真的把行距设成 1.5：重开书必须还是 1.5（不能被打回书的 1.3）。
            books.save(7L, BookSettings(lineSpacing = 1.5))
            assertEquals(1.5, books.load(7L).lineSpacing!!, 1e-9)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- 按书稀疏覆盖层（2026-10-29 定口）----

    private fun persistInTemp(): Triple<java.io.File, PerBookSettings, ReaderSettingsStore> {
        val dir = java.nio.file.Files.createTempDirectory("orilumn-perbook").toFile()
        val stores = PerBookSettings(
            ReaderSettingsStore(dir.absolutePath),
            BookSettingsStore(dir.absolutePath),
        )
        return Triple(dir, stores, ReaderSettingsStore(dir.absolutePath))
    }

    @Test
    fun `persist 只写与预设不同的私有项`() {
        // 稀疏：只记读者真动过的项；预设值不落键（以后改预设能到达这本书）。
        val (dir, persist, _) = persistInTemp()
        try {
            persist.persist(7L, persist.effectiveFor(7L).copy(fontScale = 60.0, fontBody = "A"))
            val overlay = BookSettingsStore(dir.absolutePath).load(7L)
            assertEquals(60.0, overlay.fontScale!!, 0.0)
            assertEquals("A", overlay.fontBody)
            // 没动过的项一个都不落键。
            assertNull(overlay.lineSpacing)
            assertNull(overlay.fontTitle)
            assertNull(overlay.paragraphSpacing)
            assertEquals(setOf("fontScale", "fontBody"), overlay.toJsonObject().keys)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `改一本书的字体不串到另一本`() {
        // 私有项永不写全局：书 7 改字体，书 8 与新书 9 都不受影响。
        val (dir, persist, globals) = persistInTemp()
        try {
            persist.persist(7L, persist.effectiveFor(7L).copy(fontBody = "A", fontTitle = "B"))
            assertEquals("A", persist.effectiveFor(7L).fontBody)
            assertEquals("B", persist.effectiveFor(7L).fontTitle)
            assertEquals("", persist.effectiveFor(8L).fontBody)
            assertEquals("", persist.effectiveFor(9L).fontBody)
            // 全局文件里没有字体项（读到的是预设空槽）。
            assertEquals("", globals.load().fontBody)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `选回预设值即取消该项`() {
        // 稀疏归一：把某项改回预设 ⇒ 该键消失（不是钉住一个与预设相同的值）。
        val (dir, persist, _) = persistInTemp()
        try {
            persist.persist(7L, persist.effectiveFor(7L).copy(fontTitle = "D", fontScale = 70.0))
            assertEquals(
                setOf("fontTitle", "fontScale"),
                BookSettingsStore(dir.absolutePath).load(7L).toJsonObject().keys,
            )
            persist.persist(7L, persist.effectiveFor(7L).copy(fontTitle = ""))
            assertEquals(
                setOf("fontScale"),
                BookSettingsStore(dir.absolutePath).load(7L).toJsonObject().keys,
            )
            // 全部改回预设 ⇒ 空层被删除，本书完全听书。
            persist.persist(7L, persist.effectiveFor(7L).copy(fontScale = ReaderSettings.DEFAULT.fontScale))
            assertFalse(java.io.File(dir, "books/7.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `混排字距按书 不写全局`() {
        val (dir, persist, globals) = persistInTemp()
        try {
            persist.persist(7L, persist.effectiveFor(7L).copy(cjkLatinSpacing = 80.0))
            assertEquals(80.0, BookSettingsStore(dir.absolutePath).load(7L).cjkLatinSpacing!!, 0.0)
            assertEquals(80.0, persist.effectiveFor(7L).cjkLatinSpacing, 0.0)
            // 书 8 再改：两本书各用各的。
            persist.persist(8L, persist.effectiveFor(8L).copy(cjkLatinSpacing = 0.0))
            assertEquals(80.0, persist.effectiveFor(7L).cjkLatinSpacing, 0.0)
            assertEquals(0.0, persist.effectiveFor(8L).cjkLatinSpacing, 0.0)
            // 全局文件里没有这个私有项（读到的是预设 25）。
            assertEquals(ReaderSettings.DEFAULT.cjkLatinSpacing, globals.load().cjkLatinSpacing, 0.0)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `纯全局字段只写全局 不写本书 overlay`() {
        val (dir, persist, globals) = persistInTemp()
        try {
            // 先给书 7 建一个私有覆盖层。
            persist.persist(7L, persist.effectiveFor(7L).copy(fontScale = 60.0))
            val before = BookSettingsStore(dir.absolutePath).load(7L)
            // 只改亮度：全局生效，overlay 一个字节都不动。
            persist.persist(7L, persist.effectiveFor(7L).copy(brightness = 30))
            assertEquals(30, globals.load().brightness)
            assertEquals(before, BookSettingsStore(dir.absolutePath).load(7L))
            assertEquals(30, persist.effectiveFor(7L).brightness)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `首行缩进是本书私有 null 与 0 是两回事`() {
        // 没设过（null）：不发声明，书自己的缩进原样。
        assertNull(ReaderSettings.DEFAULT.copy(firstLineIndent = 4.0).applyOverlay(BookSettings.EMPTY).firstLineIndent)
        // 设成 0：明确不缩进，必须原样保留（0 不是「没配」）。
        assertEquals(0.0, ReaderSettings.DEFAULT.applyOverlay(BookSettings(firstLineIndent = 0.0)).firstLineIndent!!, 0.0)
        assertEquals(3.0, ReaderSettings.DEFAULT.applyOverlay(BookSettings(firstLineIndent = 3.0)).firstLineIndent!!, 0.0)
    }

    @Test
    fun `首行缩进不随全局继承到没动过的书`() {
        val (dir, persist, _) = persistInTemp()
        try {
            persist.persist(7L, ReaderSettings.DEFAULT.copy(firstLineIndent = 5.0))
            // 改过的那本：5 一直有效。
            assertEquals(5.0, persist.effectiveFor(7L).firstLineIndent!!, 0.0)
            // 没动过的书不继承：仍是 null（按自己的书排版）。
            assertNull(persist.effectiveFor(8L).firstLineIndent)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 全量 JSON（含私有键）：只在测试里做往返用。生产不提供这条序列化出口 ——
     *  全量写盘会把每本书私有的字号/行距/字体等写进全局存档。 */
    private fun ReaderSettings.fullJson(): String = toJsonObject().toString()
}
