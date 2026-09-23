package orilumn.reader.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.FontLibrary
import orilumn.reader.data.font.SystemFontFace
import orilumn.reader.db.OrilumnDb
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** F5 中文名方案A：CoreText 桥的纯函数（白名单剥字重后缀 / CJK 判定）。 */
class MacFamilyNamesTest {
    @Test
    fun stripStyleSuffixDropsKnownWeightTokens() {
        assertEquals("宋体-简", MacFamilyNames.stripStyleSuffix("宋体-简 常规体"))
        assertEquals("黑体-简", MacFamilyNames.stripStyleSuffix("黑体-简 细体"))
        assertEquals("冬青黑体简体中文", MacFamilyNames.stripStyleSuffix("冬青黑体简体中文 W3"))
        assertEquals("冬青黑体简体中文", MacFamilyNames.stripStyleSuffix("冬青黑体简体中文 W6"))
        assertEquals("苹方-简", MacFamilyNames.stripStyleSuffix("苹方-简"))
        assertEquals("楷体-简", MacFamilyNames.stripStyleSuffix("楷体-简 常规体"))
        // F5 补：新增中文权重白名单 + CJK 尾部粘连拉丁字重剥离。
        assertEquals("兰亭黑-简", MacFamilyNames.stripStyleSuffix("兰亭黑-简 纤黑"))
        assertEquals("凌慧体-简", MacFamilyNames.stripStyleSuffix("凌慧体-简 中黑体"))
        assertEquals("寒蝉端黑宋", MacFamilyNames.stripStyleSuffix("寒蝉端黑宋Regular"))
    }

    @Test
    fun stripStyleSuffixLeavesEnglishAndRealTwoWordCjkAlone() {
        // head 无 CJK 不剥；真双词中文族名（尾部非白名单）不误伤；纯拉丁名不碰粘连规则。
        assertEquals("Songti SC", MacFamilyNames.stripStyleSuffix("Songti SC"))
        assertEquals("Noto Sans CJK SC", MacFamilyNames.stripStyleSuffix("Noto Sans CJK SC"))
        assertEquals("方正 楷体", MacFamilyNames.stripStyleSuffix("方正 楷体"))
        assertEquals("Arial", MacFamilyNames.stripStyleSuffix("Arial"))
        assertEquals("Regular", MacFamilyNames.stripStyleSuffix("Regular"))
        assertEquals("PingFangSC", MacFamilyNames.stripStyleSuffix("PingFangSC")) // 无 CJK，粘连规则不触发
    }

    @Test
    fun containsCjkDetectsHanCharacters() {
        assertTrue(MacFamilyNames.containsCjk("宋体-简"))
        assertTrue(MacFamilyNames.containsCjk("冬青黑体简体中文 W3"))
        assertFalse(MacFamilyNames.containsCjk("PingFang SC"))
        assertFalse(MacFamilyNames.containsCjk("Arial"))
    }

    @Test
    fun coreTextBridgeReturnsChineseOnMac() {
        // 门控：非 mac 直接过；英文系统（CoreText 无 CJK 结果 → 空表）也过——方案A预期。
        if (!MacFamilyNames.isMacAvailable) return
        val map = MacFamilyNames.localizedFamilyNames(
            listOf(
                "PingFang SC", "PingFang TC", "Songti SC", "Hiragino Sans GB",
                "Hannotate SC", "Hannotate TC", "Heiti TC", "Arial",
            ),
        )
        val pingfang = map["PingFang SC"] ?: return // 本机中文系统下应命中。
        assertTrue("本地化名须含 CJK", MacFamilyNames.containsCjk(pingfang))
        assertEquals("苹方-简", pingfang)
        assertEquals("苹方-繁", map["PingFang TC"]) // 简繁同源，TC 走 zh-Hans 名「苹方-繁」
        assertEquals("宋体-简", map["Songti SC"]) // "宋体-简 常规体" 剥离字重后缀
        assertEquals("手札体-简", map["Hannotate SC"])
        assertEquals("手札体-繁", map["Hannotate TC"]) // 用户点名：TC 必须出中文名
        assertEquals("黑体-繁", map["Heiti TC"])
        assertEquals("冬青黑体简体中文", map["Hiragino Sans GB"])
        assertFalse("Arial 无本地化名不进表", map.containsKey("Arial"))
        assertFalse("只收含 CJK 结果", map.values.any { !MacFamilyNames.containsCjk(it) })
    }

    @Test
    fun coreTextChineseNamesFlowIntoSystemRows() = runBlocking {
        // 与桌面 ReaderView 同接线：枚举族 → CoreText 本地化名 → syncSystemFaces 落库。
        if (!MacFamilyNames.isMacAvailable) return@runBlocking
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        val db = LibraryDb(OrilumnDb(driver))
        val dir = Files.createTempDirectory("orilumn-coretext-pipeline").toFile()
        try {
            val lib = FontLibrary(db, FileSystem.SYSTEM, File(dir, "fonts").absolutePath.toPath())
            val sys = listOf(
                SystemFontFace("PingFang SC"),
                SystemFontFace("Songti SC"),
                SystemFontFace("Arial"),
            )
            val localized = MacFamilyNames.localizedFamilyNames(sys.map { it.family })
            assertTrue("PingFang 应拿到中文本地化名", MacFamilyNames.containsCjk(localized["PingFang SC"] ?: ""))

            lib.syncSystemFaces(sys, localizedNames = localized)
            val rows = lib.list().associateBy { it.familyName }
            assertEquals("苹方-简", rows["PingFang SC"]!!.displayName)
            assertEquals("宋体-简", rows["Songti SC"]!!.displayName)
            assertEquals("Arial", rows["Arial"]!!.displayName) // 无本地化名 → 写回族名
            val entry = lib.allEntries(lib.list()).filterIsInstance<FontEntry.System>()
                .single { it.family == "PingFang SC" }
            assertEquals("PingFang SC", entry.family) // 逻辑键不动
            assertEquals("苹方-简", entry.display)
        } finally {
            dir.deleteRecursively()
        }
    }
}