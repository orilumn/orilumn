package orilumn.reader.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 「设置字段 → 全局 / 本书私有」的归属锁（2026-10-29 两级模型）。
 *
 * 规则（`docs/设置模型整改计划.md`）：
 *  - **全局**：[SettingsScope.GLOBAL]，只写 `reader.json`，永不进本书；
 *  - **私有**：[SettingsScope.PRIVATE]，只写 `books/{id}.json`，**永不写全局**；
 *  - **会话只读**：[SettingsScope.SESSION_ONLY]（面板读数），不落盘。
 *
 * 私有项写进全局 = 改一本书串到所有书（本次整改要根除的症状）；全局项写进本书 =
 * 别的书改全局对本册无效。本锁用六种方式看同一件事：
 *  1. 字段全集 = 三张表之并，两两不交（源码扫描，新增字段忘登记即红）；
 *  2. [BookSettings] 的字段恰好等于 [SettingsScope.PRIVATE]（没有全局项混进来）；
 *  3. 每个私有字段单独改一次：本书 overlay 只多这一个键，全局文件**一个字节都不变**；
 *  4. 每个全局字段单独改一次：全局真的落盘，本书 overlay **一个字节都不变**；
 *  5. 稀疏归一：改回预设即掉键，空层即删文件；新书 = 预设 + 全局项；
 *  6. 面板读数（[SettingsScope.SESSION_ONLY]）提交后两边都不落盘，重开面板重新量。
 */
class SettingsPersistenceScopeTest {

    private val privateFields = SettingsScope.PRIVATE
    private val globalFields = SettingsScope.GLOBAL

    @Test
    fun `字段全集恰好是全局 私有 会话只读 三张表`() {
        val declared = declaredValFields("ReaderSettings.kt")
        val union = SettingsScope.GLOBAL + SettingsScope.PRIVATE + SettingsScope.SESSION_ONLY
        assertEquals(
            "ReaderSettings 字段必须恰好归属全局 / 私有 / 会话只读（新增字段忘登记即红）",
            declared,
            union,
        )
        assertEquals("全局与私有有交集", emptySet<String>(), SettingsScope.GLOBAL.intersect(SettingsScope.PRIVATE))
        assertEquals("全局与会话只读有交集", emptySet<String>(), SettingsScope.GLOBAL.intersect(SettingsScope.SESSION_ONLY))
        assertEquals("私有与会话只读有交集", emptySet<String>(), SettingsScope.PRIVATE.intersect(SettingsScope.SESSION_ONLY))
    }

    @Test
    fun `BookSettings 字段恰好等于私有集合`() {
        assertEquals(
            "本书覆盖层只能有私有字段；多一个 = 全局项被本书钉死，少一个 = 私有项无处落盘",
            SettingsScope.PRIVATE,
            declaredValFields("BookSettings.kt"),
        )
    }

    @Test
    fun `每个私有字段只写本书 全局一个字节都不变`() {
        for (field in privateFields) {
            val dir = Files.createTempDirectory("orilumn-private").toFile()
            try {
                val persist = PerBookSettings(
                    ReaderSettingsStore(dir.absolutePath),
                    BookSettingsStore(dir.absolutePath),
                )
                val next = mutatePrivate(field, ReaderSettings.DEFAULT)
                assertNotEquals("探针无效：$field 改后与默认值相同", ReaderSettings.DEFAULT, next)
                persist.persist(7L, next)

                val overlay = BookSettingsStore(dir.absolutePath).load(7L)
                assertEquals(
                    "私有字段 [$field] 必须落进本书 overlay（且只多它一个键）",
                    setOf(field),
                    overlay.toJsonObject().keys,
                )
                assertFalse(
                    "私有字段 [$field] 不得写全局（全局文件应仍不存在）",
                    File(dir, "reader.json").exists(),
                )
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `每个全局字段只写全局 本书一个字节都不变`() {
        for (field in globalFields - "schemaVersion") {
            val dir = Files.createTempDirectory("orilumn-global").toFile()
            try {
                val persist = PerBookSettings(
                    ReaderSettingsStore(dir.absolutePath),
                    BookSettingsStore(dir.absolutePath),
                )
                // 先给书 7 建一个私有层作对照（全局改动不得碰它）。
                persist.persist(7L, ReaderSettings.DEFAULT.copy(fontScale = 61.0))
                val overlayBefore = BookSettingsStore(dir.absolutePath).load(7L)

                val base = persist.effectiveFor(7L)
                val next = mutateGlobal(field, base)
                assertNotEquals("探针无效：$field 改后与当前值相同", base, next)
                persist.persist(7L, next)

                assertTrue(
                    "全局字段 [$field] 必须写回全局（漏拷白名单 = 改完等于没存）",
                    File(dir, "reader.json").exists(),
                )
                assertEquals(
                    "全局字段 [$field] 不得写进本书 overlay",
                    overlayBefore,
                    BookSettingsStore(dir.absolutePath).load(7L),
                )
            } finally {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `稀疏归一 改回预设即掉键 空层即删文件`() {
        val dir = Files.createTempDirectory("orilumn-sparse").toFile()
        try {
            val persist = PerBookSettings(
                ReaderSettingsStore(dir.absolutePath),
                BookSettingsStore(dir.absolutePath),
            )
            persist.persist(7L, ReaderSettings.DEFAULT.copy(fontScale = 60.0, fontBody = "A"))
            assertEquals(
                setOf("fontScale", "fontBody"),
                BookSettingsStore(dir.absolutePath).load(7L).toJsonObject().keys,
            )
            // 改回预设：键消失。
            persist.persist(7L, ReaderSettings.DEFAULT.copy(fontScale = 60.0))
            assertEquals(
                setOf("fontScale"),
                BookSettingsStore(dir.absolutePath).load(7L).toJsonObject().keys,
            )
            // 全部回预设：文件被删除（= 完全听书）。
            persist.persist(7L, ReaderSettings.DEFAULT)
            assertFalse(File(dir, "books/7.json").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `新书 = 预设 + 全局项`() {
        val dir = Files.createTempDirectory("orilumn-newbook").toFile()
        try {
            val persist = PerBookSettings(
                ReaderSettingsStore(dir.absolutePath),
                BookSettingsStore(dir.absolutePath),
            )
            persist.persist(null, ReaderSettings.DEFAULT.copy(brightness = 30, noteFont = "NoteFace"))
            val fresh = persist.effectiveFor(99L)
            assertEquals(30, fresh.brightness)
            assertEquals("NoteFace", fresh.noteFont)
            // 私有项一律停在预设（= 原书样式）。
            assertEquals("original", fresh.layoutTheme)
            assertEquals(ReaderSettings.DEFAULT.lineSpacing, fresh.lineSpacing)
            assertEquals(ReaderSettings.DEFAULT.fontScale, fresh.fontScale, 0.0)
            assertEquals("", fresh.fontBody)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `面板读数不落盘 重开面板重新量`() {
        val dir = Files.createTempDirectory("orilumn-session").toFile()
        try {
            val persist = PerBookSettings(
                ReaderSettingsStore(dir.absolutePath),
                BookSettingsStore(dir.absolutePath),
            )
            persist.persist(7L, ReaderSettings.DEFAULT.copy(fontScale = 60.0))
            val overlayBefore = BookSettingsStore(dir.absolutePath).load(7L)
            val globalBefore = ReaderSettingsStore(dir.absolutePath).load()

            // 打开面板量出的原书缩进/行高：只进会话只读字段，提交一次也不得落盘。
            persist.persist(
                7L,
                persist.effectiveFor(7L).copy(probeIndent = 2.0, probeLineSpacing = 1.7, probeParagraphSpacing = 0.9),
            )

            assertEquals(
                "面板读数不得进本书覆盖层（否则书的缩进/行高会被量出来的值钉死）",
                overlayBefore,
                BookSettingsStore(dir.absolutePath).load(7L),
            )
            assertEquals(
                "面板读数不得进全局存档",
                globalBefore,
                ReaderSettingsStore(dir.absolutePath).load(),
            )
            val reread = persist.effectiveFor(7L)
            assertNull("读数不落盘：下次开书应是没量过的状态", reread.probeIndent)
            assertNull(reread.probeLineSpacing)
            assertNull(reread.probeParagraphSpacing)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** 让某个私有字段取一个与预设不同的值；未登记的字段直接报错。 */
    private fun mutatePrivate(field: String, s: ReaderSettings): ReaderSettings = when (field) {
        "layoutTheme" -> s.copy(layoutTheme = "traditional")
        "fontSize" -> s.copy(fontSize = s.fontSize + 3)
        "fontScale" -> s.copy(fontScale = s.fontScale + 3.0)
        "lineSpacing" -> s.copy(lineSpacing = (s.lineSpacing ?: 1.5) + 0.13)
        "cjkLatinSpacing" -> s.copy(cjkLatinSpacing = s.cjkLatinSpacing + 1.0)
        "firstLineIndent" -> s.copy(firstLineIndent = (s.firstLineIndent ?: 0.0) + 2.0)
        "paragraphSpacing" -> s.copy(paragraphSpacing = (s.paragraphSpacing ?: 0.0) + 0.7)
        "paragraphGap" -> s.copy(paragraphGap = s.paragraphGap + 19.0)
        "letterSpacing" -> s.copy(letterSpacing = (s.letterSpacing ?: 0.0) + 5.0)
        "marginTop" -> s.copy(marginTop = s.marginTop + 7)
        "marginBottom" -> s.copy(marginBottom = s.marginBottom + 7)
        "marginLeft" -> s.copy(marginLeft = s.marginLeft + 7)
        "marginRight" -> s.copy(marginRight = s.marginRight + 7)
        "fontBody" -> s.copy(fontBody = "ProbeBody")
        "fontTitle" -> s.copy(fontTitle = "ProbeTitle")
        "fontCode" -> s.copy(fontCode = "ProbeCode")
        "useUserScripts" -> s.copy(useUserScripts = !s.useUserScripts)
        "pageAnim" -> s.copy(pageAnim = !s.pageAnim)
        "pageAnimationMode" -> s.copy(pageAnimationMode = if (s.pageAnimationMode == "slide") "curl" else "slide")
        "coverStretch" -> s.copy(coverStretch = !s.coverStretch)
        "fontWeightAnchors" -> s.copy(fontWeightAnchors = mapOf("Probe" to 700))
        "fontWeightAnchorsBySlot" -> s.copy(fontWeightAnchorsBySlot = mapOf("fontBody|Probe" to 700))
        else -> error("私有集合新增了字段 [$field]，但本测试还没有它的探针")
    }

    /** 让某个全局字段取一个与预设不同的值；未登记的字段直接报错。 */
    private fun mutateGlobal(field: String, s: ReaderSettings): ReaderSettings = when (field) {
        "theme" -> s.copy(theme = if (s.theme == "sepia") "white" else "sepia")
        "scheme" -> s.copy(scheme = if (s.scheme == "day") "night" else "day")
        "bgOverride" -> s.copy(bgOverride = "#101010")
        "fgOverride" -> s.copy(fgOverride = "#eeeeee")
        "brightness" -> s.copy(brightness = if (s.brightness == 100) 42 else 100)
        "brightnessFollowSystem" -> s.copy(brightnessFollowSystem = !s.brightnessFollowSystem)
        "brightnessOffset" -> s.copy(brightnessOffset = s.brightnessOffset + 13)
        "eyeProtectionLevel" -> s.copy(eyeProtectionLevel = if (s.eyeProtectionLevel == 40) 7 else 40)
        "brightnessGestureLeft" -> s.copy(brightnessGestureLeft = !s.brightnessGestureLeft)
        "brightnessGestureRight" -> s.copy(brightnessGestureRight = !s.brightnessGestureRight)
        "brightnessGestureTwo" -> s.copy(brightnessGestureTwo = !s.brightnessGestureTwo)
        "autoContinue" -> s.copy(autoContinue = !s.autoContinue)
        "pageNum" -> s.copy(pageNum = !s.pageNum)
        "showHiddenFonts" -> s.copy(showHiddenFonts = !s.showHiddenFonts)
        "highlightColorSlots" -> s.copy(highlightColorSlots = listOf(0x11, 0x22, 0x33, 0x44, 0x55))
        "underlineColorSlots" -> s.copy(underlineColorSlots = listOf(0x66, 0x77, 0x88, 0x99, 0xAA))
        "highlightDefaultSlot" -> s.copy(highlightDefaultSlot = if (s.highlightDefaultSlot == 0) 1 else 0)
        "underlineDefaultSlot" -> s.copy(underlineDefaultSlot = if (s.underlineDefaultSlot == 0) 1 else 0)
        "annotationLastKind" -> s.copy(annotationLastKind = if (s.annotationLastKind == "underline") "highlight" else "underline")
        "annotationLastLineStyle" -> s.copy(annotationLastLineStyle = if (s.annotationLastLineStyle == "wavy") "straight" else "wavy")
        "noteFont" -> s.copy(noteFont = "NoteFace")
        "noteFontScale" -> s.copy(noteFontScale = if (s.noteFontScale == 1.4f) 1.1f else 1.4f)
        "noteCollapseAll" -> s.copy(noteCollapseAll = !s.noteCollapseAll)
        "noteBorder" -> s.copy(noteBorder = !s.noteBorder)
        "noteBackground" -> s.copy(noteBackground = !s.noteBackground)
        "noteTextSameColor" -> s.copy(noteTextSameColor = !s.noteTextSameColor)
        else -> error("全局集合新增了字段 [$field]，但本测试还没有它的探针")
    }

    /** 从设置数据类源码的**主构造参数**里抽 `val xxx:` 字段名（模块工作目录相对路径）。
     *  只取构造参数，避免把类体里的计算属性（如 `isEmpty`）算成字段。 */
    private fun declaredValFields(fileName: String): Set<String> {
        val file = File("src/commonMain/kotlin/orilumn/reader/data/settings/$fileName")
        assertTrue("找不到 ${file.absolutePath}（测试工作目录应为 :common 模块目录）", file.exists())
        val ctor = file.readText().substringAfter("data class ").substringAfter("(").substringBefore("\n) {")
        val fields = Regex("""^\s{4}val\s+([A-Za-z][A-Za-z0-9_]*)\s*:""", RegexOption.MULTILINE)
            .findAll(ctor).map { it.groupValues[1] }.toSet()
        assertTrue("源码扫描没抽到字段（正则失效？）", fields.size > 10)
        return fields
    }
}
