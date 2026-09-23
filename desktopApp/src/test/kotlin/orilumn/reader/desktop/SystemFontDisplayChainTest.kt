package orilumn.reader.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.FontLibrary
import orilumn.reader.data.font.SystemFontFace
import orilumn.reader.db.OrilumnDb
import orilumn.reader.engine.skia.systemFontFaces
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 方案B 实机验证：复刻 ReaderView 同一条字体链路——真实枚举 (engine-skia `systemFontFaces`)
 * → 中文名链（name 表直读 `NameTableChineseNames` 优先 → CoreText 本地化 `MacFamilyNames`
 * 补缺）→ `syncSystemFaces` 落库 → `allEntries` 展示名。
 *
 * 重点：自装开源中文族（LXGW 霞鹜文楷）的枚举原始族名应命中 name 表直读
 * （不依赖 CoreText/字典）——「霞鹜文楷」「霞鹜文楷等宽」都是字体 name 表 zh-CN 直出。
 * 未装 LXGW 的机器跳过（与既有 TTF 依赖测试同口径）。
 */
class SystemFontDisplayChainTest {

    private fun freshLib(): Pair<FontLibrary, File> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        val db = LibraryDb(OrilumnDb(driver))
        val dir = Files.createTempDirectory("orilumn-font-chain").toFile()
        return FontLibrary(db, FileSystem.SYSTEM, File(dir, "fonts").absolutePath.toPath()) to dir
    }

    private fun lxgwChinese(entries: List<FontEntry.System>, family: String): List<String> =
        entries.filter { it.family == family }.map { it.display }

    /** 与 ReaderView LaunchedEffect 同构：枚举 → name 表直读 + CoreText 补缺 → syncSystemFaces → allEntries。 */
    private fun localizedOf(sys: List<SystemFontFace>): Map<String, String> {
        val nameTable = NameTableChineseNames.namesFor(sys.map { it.family })
        val coreText = MacFamilyNames.localizedFamilyNames(sys.map { it.family })
        return coreText + nameTable
    }

    @Test
    fun installedLxgwChineseNamesEndToEnd() = runBlocking {
        val sys = runCatching { systemFontFaces() }.getOrDefault(emptyList())
        val lxgwFamilies = sys.map { it.family }.filter { it.startsWith("LXGW") }.distinct()
        if (lxgwFamilies.isEmpty()) return@runBlocking // 未装机跳过
        val (lib, dir) = freshLib()
        try {
            // name 表直读应覆盖 LXGW。
            val nameTable = NameTableChineseNames.namesFor(lxgwFamilies)
            for (family in lxgwFamilies) {
                assertTrue(
                    "name 表应直读中文名 $family（现 ${nameTable[family]}）",
                    nameTable[family]?.any { it.code in 0x4E00..0x9FFF } == true,
                )
            }
            val faces = lib.syncSystemFaces(sys, localizedNames = localizedOf(sys))
            val entries = lib.allEntries(faces).filterIsInstance<FontEntry.System>()
            val wenKai = lxgwChinese(entries, "LXGW WenKai")
            val mono = lxgwChinese(entries, "LXGW WenKai Mono")
            // 存在与否取决于系统：装了的必须中文，未装的族自然为空列表。
            (wenKai + mono).forEach { display ->
                assertTrue(
                    "LXGW 展示名须中文，实际「$display」",
                    display.any { it.code in 0x4E00..0x9FFF },
                )
            }
            // 精确值 = name 表 zh-CN 直读（不筛简繁，名字表写什么显示什么）。
            wenKai.forEach { assertEquals("霞鹜文楷", it) }
            mono.forEach { assertEquals("霞鹜文楷等宽", it) }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun installedOpenSourceCjkAllChineseDisplay() = runBlocking {
        // 顺手核一遍：本机现存的开源 CJK 族（Sarasa/Source Han/Noto CJK/等）展示名必须中文，
        // 不允许英文名躺在列表（name 表直读 + CoreText 两链，无记录的族回退族名本身）。
        val sys = runCatching { systemFontFaces() }.getOrDefault(emptyList())
        val known = sys.map { it.family }.filter {
            it.startsWith("LXGW") || it.startsWith("Sarasa") || it.startsWith("Source Han") ||
                it.startsWith("Noto Sans CJK") || it.startsWith("Noto Serif CJK") ||
                it.startsWith("Noto Sans Mono CJK")
        }.distinct()
        if (known.isEmpty()) return@runBlocking
        val (lib, dir) = freshLib()
        try {
            // 生产链路：name 表直读 + CoreText 补缺。
            val localized = localizedOf(sys)
            val faces = lib.syncSystemFaces(sys, localizedNames = localized)
            val entries = lib.allEntries(faces).filterIsInstance<FontEntry.System>()
            for (family in known) {
                val displays = lxgwChinese(entries, family)
                assertTrue("族 $family 应出现在 allEntries", displays.isNotEmpty())
                displays.forEach { display ->
                    assertTrue(
                        "开源 CJK 族 $family 展示名须中文，实际「$display」",
                        display.any { it.code in 0x4E00..0x9FFF },
                    )
                }
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}