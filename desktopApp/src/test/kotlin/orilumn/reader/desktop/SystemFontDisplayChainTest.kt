package orilumn.reader.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.FontLibrary
import orilumn.reader.data.font.SystemFontFace
import orilumn.reader.db.OrilumnDb
import orilumn.reader.engine.SystemFontSource
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 方案B 实机验证：复刻 ReaderView 同一条字体链路——**平台枚举端口**（[DesktopFontScan.fontSource]）
 * → **本地化名链**（[DesktopFontScan.localizedNames]）→ `syncSystemFaces` 落库 → `allEntries` 展示名。
 *
 * 两处接缝都直接取生产实现，所以 Linux 走 fontconfig（枚举 + 本地化都直连 fontconfig），
 * mac / Windows 走 skiko 枚举 + name 表直读（+ macOS CoreText 补缺）——与产品路径逐字节同构，
 * 不在这里另起一套「测试专用枚举」。
 *
 * 重点：自装开源中文族（LXGW 霞鹜文楷、思源、更纱）的展示名必须是中文。
 * 未装这些族的机器跳过（与既有 TTF 依赖测试同口径）。
 */
class SystemFontDisplayChainTest {

    private fun freshLib(): Pair<FontLibrary, File> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        val db = LibraryDb(OrilumnDb(driver))
        val dir = Files.createTempDirectory("orilumn-font-chain").toFile()
        return FontLibrary(db, FileSystem.SYSTEM, File(dir, "fonts").absolutePath.toPath()) to dir
    }

    /** 生产枚举端口 + 生产本地化链：与 `SystemFontScanner` 里跑的完全是同一对函数。 */
    private fun productEnumeration(): Triple<SystemFontSource, List<String>, Map<String, String>> {
        val source = DesktopFontScan.fontSource()
        val families = source.families()
        return Triple(source, families, DesktopFontScan.localizedNames(families))
    }

    private fun facesOf(source: SystemFontSource, families: List<String>): List<SystemFontFace> =
        families.flatMap { source.facesFor(it) }

    private fun displaysOf(entries: List<FontEntry.System>, family: String): List<String> =
        entries.filter { it.family == family }.map { it.display }

    /** 族在列表里的可见名：本地化名优先，否则族名本身（与 `FontEntry.display` 同口径）。 */
    private fun shownName(family: String, localized: Map<String, String>): String =
        localized[family] ?: family

    @Test
    fun installedLxgwChineseNamesEndToEnd() = runBlocking {
        val (source, families, localized) = productEnumeration()
        // Linux 上基族名已是中文（霞鹜文楷）；mac / Windows 上是拉丁名 + 本地化展示名。
        val lxgwFamilies = families.filter {
            it.startsWith("LXGW") || shownName(it, localized).contains("霞鹜")
        }
        if (lxgwFamilies.isEmpty()) return@runBlocking // 未装机跳过
        val (lib, dir) = freshLib()
        try {
            // name 表直读只在 skiko 枚举接管的平台（mac / Windows）成立；
            // Linux 的展示名来自 fontconfig 自己的本地化族名，不再读 name 表。
            if (!FontconfigFonts.available()) {
                val nameTable = NameTableChineseNames.namesFor(lxgwFamilies)
                for (family in lxgwFamilies) {
                    assertTrue(
                        "name 表应直读中文名 $family（现 ${nameTable[family]}）",
                        nameTable[family]?.any { it.code in 0x4E00..0x9FFF } == true,
                    )
                }
            }
            val faces = lib.syncSystemFaces(facesOf(source, lxgwFamilies), localizedNames = localized)
            val entries = lib.allEntries(faces).filterIsInstance<FontEntry.System>()
            for (family in lxgwFamilies) {
                val displays = displaysOf(entries, family)
                assertTrue("族 $family 应出现在 allEntries", displays.isNotEmpty())
                displays.forEach { display ->
                    assertTrue("LXGW 展示名须中文，实际「$display」", display.containsCjk())
                }
                // 精确值：中文基名就是展示名，拉丁基名则收敛到对应的中文名。
                val expected = when (family) {
                    "LXGW WenKai", "霞鹜文楷" -> "霞鹜文楷"
                    "LXGW WenKai Mono", "霞鹜文楷等宽" -> "霞鹜文楷等宽"
                    else -> null
                }
                if (expected != null) displays.forEach { assertEquals(expected, it) }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun installedOpenSourceCjkAllChineseDisplay() = runBlocking {
        // 顺手核一遍：本机现存的开源 CJK 族（Sarasa/Source Han/LXGW 等）展示名
        // 必须中文，不允许英文名躺在列表（枚举 + 本地化都走生产接缝，无记录的族回退族名本身）。
        // 注意：Noto CJK 系（*Noto Sans/Serif/Mono CJK*）name 表**只有拉丁+日文
        // 记录、没有任何含 CJK 的记录**（Google 出厂如此，各平台一致），
        // 其展示名回退族名本身是正确行为，不进本断言集。
        val (source, families, localized) = productEnumeration()
        val markers = listOf("LXGW", "Sarasa", "Source Han", "霞鹜", "更纱", "思源")
        val known = families.filter { family ->
            val shown = shownName(family, localized)
            markers.any { family.contains(it) || shown.contains(it) }
        }
        if (known.isEmpty()) return@runBlocking
        val (lib, dir) = freshLib()
        try {
            val faces = lib.syncSystemFaces(facesOf(source, known), localizedNames = localized)
            val entries = lib.allEntries(faces).filterIsInstance<FontEntry.System>()
            for (family in known) {
                val displays = displaysOf(entries, family)
                assertTrue("族 $family 应出现在 allEntries", displays.isNotEmpty())
                displays.forEach { display ->
                    assertTrue(
                        "开源 CJK 族 $family 展示名须中文，实际「$display」",
                        display.containsCjk(),
                    )
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun String.containsCjk(): Boolean = any { it.code in 0x4E00..0x9FFF }
}
