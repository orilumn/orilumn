package orilumn.reader.data.font

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.db.OrilumnDb
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * F3 共享字体库单测：导入/删除/隐藏/系统落行/孤儿自愈（真 SQLite 内存库 + 真临时目录）。
 * 导入用例需本机 TTF（macOS 补充字体），缺席即跳过——与既有 jvmTest 口径一致。
 */
class FontLibraryTest {

    private fun freshDb(): LibraryDb {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        return LibraryDb(OrilumnDb(driver))
    }

    private fun freshLib(db: LibraryDb = freshDb()): Pair<FontLibrary, File> {
        val dir = Files.createTempDirectory("orilumn-fontlib").toFile()
        val lib = FontLibrary(db, FileSystem.SYSTEM, File(dir, "fonts").absolutePath.toPath())
        return lib to dir
    }

    private fun realTtf(): ByteArray? =
        listOf(
            "/System/Library/Fonts/Supplemental/Arial.ttf",
            "/System/Library/Fonts/Supplemental/Times New Roman.ttf",
        ).firstOrNull { File(it).isFile }?.let { File(it).readBytes() }

    @Test
    fun importDeleteRoundTrip() = runBlocking {
        val bytes = realTtf() ?: return@runBlocking
        val (lib, dir) = freshLib()
        try {
            val after = lib.importBytes(bytes, "Arial.ttf")
            assertEquals(1, after.size)
            val face = after.single()
            assertEquals(FontFace.SOURCE_IMPORTED, face.source)
            assertEquals(false, face.hidden)
            assertNotNull(face.path)
            assertTrue(File(face.path!!).isFile)

            // 同文件重导：同一行覆盖，不增行（REPLACE 语义换新 rowid，重取行）。
            assertEquals(1, lib.importBytes(bytes, "Arial.ttf").size)
            val face2 = lib.list().single()

            // 字节可回读。
            assertEquals(bytes.toList(), lib.fontBytes(face2.id)?.toList())

            lib.delete(face2)
            assertTrue(lib.list().isEmpty())
            assertNull(face2.path?.let { File(it) }?.takeIf { it.exists() })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun importRejectsGarbage() = runBlocking {
        val (lib, dir) = freshLib()
        try {
            assertTrue(lib.importBytes(null, "x.ttf").isEmpty())
            assertTrue(lib.importBytes(ByteArray(0), "x.ttf").isEmpty())
            assertTrue(lib.importBytes("not a font".toByteArray(), "x.ttf").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun systemSyncHideAndEntries() = runBlocking {
        val (lib, dir) = freshLib()
        try {
            val faces = lib.syncSystemFaces(listOf(
                SystemFontFace("Songti SC"),
                SystemFontFace("PingFang SC"),
                SystemFontFace("  "),
                SystemFontFace("Songti SC"),
            ))
            assertEquals(2, faces.size)
            assertTrue(faces.all { it.source == FontFace.SOURCE_SYSTEM && it.path == null })

            // 统一列表：系统 + 导入同列；隐藏过滤走 visibleEntries。
            val sysId = faces.first { it.familyName == "Songti SC" }.id
            lib.setHidden(sysId, true)
            val all = lib.allEntries(lib.list())
            assertEquals(2, all.size)
            assertTrue(all.filterIsInstance<FontEntry.System>().single { it.family == "Songti SC" }.hidden)
            val visible = lib.visibleEntries(lib.list())
            assertEquals(listOf("PingFang SC"), visible.map { it.family })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deleteFamilyKeepsSystemRow() = runBlocking {
        val bytes = realTtf() ?: return@runBlocking
        val db = freshDb()
        val (lib, dir) = freshLib(db)
        try {
            lib.importBytes(bytes, "Fam.ttf")
            val importedFamily = lib.list().single { it.source == FontFace.SOURCE_IMPORTED }.familyName
            // 同名系统行落行（edge：导入族与系统族同名）。
            lib.syncSystemFaces(listOf(SystemFontFace(importedFamily)))
            assertEquals(2, lib.list().size)

            lib.deleteFamily(importedFamily)
            val rest = lib.list()
            assertEquals(1, rest.size)
            assertEquals(FontFace.SOURCE_SYSTEM, rest.single().source)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun reconcileHealsOrphanFile() = runBlocking {
        val bytes = realTtf() ?: return@runBlocking
        val db = freshDb()
        val (lib, dir) = freshLib(db)
        try {
            lib.importBytes(bytes, "Orphan.ttf")
            val path = lib.list().single().path!!
            // 模拟丢行：删 DB 行留文件。
            db.deleteFontsByFamily(lib.list().single().familyName)
            assertTrue(lib.list().isEmpty())
            assertTrue(File(path).isFile)

            val healed = lib.reconcileOrphanFiles()
            assertEquals(1, healed.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun systemFacesPersistSubfamilies() = runBlocking {
        // 系统面枚举（族 + 字重）落行：同族多字重各自成行，(family, subfamily) 去重覆盖。
        val (lib, dir) = freshLib()
        try {
            val faces = lib.syncSystemFaces(listOf(
                SystemFontFace("Songti SC", "Regular"),
                SystemFontFace("Songti SC", "Bold"),
                SystemFontFace("Songti SC", "Regular"), // 重复面，去重。
                SystemFontFace("PingFang SC"),
                SystemFontFace("", "Bold"), // 空族名过滤。
            ))
            val songti = faces.filter { it.familyName == "Songti SC" }
            assertEquals(2, songti.size)
            assertEquals(setOf("Regular", "Bold"), songti.map { it.subfamily }.toSet())
            assertTrue(songti.all { it.source == FontFace.SOURCE_SYSTEM && it.path == null })
            val pingfang = faces.single { it.familyName == "PingFang SC" }
            assertEquals("", pingfang.subfamily)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun systemSyncInheritsFamilyHiddenToNewWeights() = runBlocking {
        // 迁移口径：旧库某族已隐藏（单行 subfamily=""），API 升级后同步出的新字重行
        // 必须继承族隐藏态（一族同隐同显），不得让已隐藏族复活进主列表；
        // 同步收尾的同时清掉 subfamily="" 旧世代残留行（该族已枚举出字重行即过期）。
        val (lib, dir) = freshLib()
        try {
            lib.syncSystemFaces(listOf(SystemFontFace("Songti SC")))
            val first = lib.list().single()
            lib.setHidden(first.id, true)
            assertTrue(lib.list().single().hidden)

            lib.syncSystemFaces(listOf(
                SystemFontFace("Songti SC", "Regular"),
                SystemFontFace("Songti SC", "Bold"),
            ))
            val rows = lib.list().filter { it.familyName == "Songti SC" }
            assertEquals(2, rows.size) // 残留 subfamily="" 行被清理
            assertEquals(setOf("Regular", "Bold"), rows.map { it.subfamily }.toSet())
            assertTrue("新字重行继承族隐藏态", rows.all { it.hidden })
            val visible = lib.visibleEntries(lib.list())
            assertEquals(0, visible.count { it.family == "Songti SC" })
            val all = lib.allEntries(lib.list())
            assertEquals(2, all.filterIsInstance<FontEntry.System>().count { it.family == "Songti SC" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun syncSystemFacesPurgesStaleEmptySubfamilyRows() = runBlocking {
        // F5 补丁：旧世代（字重枚举前）每族一行 subfamily=""、displayName=族名。
        // 该族本次枚举出字重行后残留行即过期——不删则面板合并行首成员仍取残留行，
        // 本地化 displayName（手札体-简 等）显示不出（族名本身）。
        // 只清「本次有字重行的族」，纯 "" 族（无字重名）保留原行。
        val (lib, dir) = freshLib()
        try {
            // 先造旧世代残留：族"有字重的族"与"没字重的族"都只有 "" 行。
            lib.syncSystemFaces(listOf(
                SystemFontFace("Hannotate SC"),
                SystemFontFace("BareFamily"),
            ))
            assertEquals(setOf("Hannotate SC", "BareFamily"), lib.list().map { it.familyName }.toSet())

            // 新世代同步：Hannotate SC 枚举出字重；BareFamily 仍只有 ""。
            lib.syncSystemFaces(
                listOf(
                    SystemFontFace("Hannotate SC", "Regular"),
                    SystemFontFace("Hannotate SC", "Bold"),
                    SystemFontFace("BareFamily"),
                ),
                localizedNames = mapOf("Hannotate SC" to "手札体-简"),
            )
            val rows = lib.list()
            val hannotate = rows.filter { it.familyName == "Hannotate SC" }
            assertEquals("残留 \"\" 行被清", setOf("Regular", "Bold"), hannotate.map { it.subfamily }.toSet())
            assertTrue("本地化名随字重行", hannotate.all { it.displayName == "手札体-简" })
            assertEquals("未枚举字重的族保留原 \"\" 行", "BareFamily",
                rows.single { it.familyName == "BareFamily" }.familyName)

            // 数据层视角：合并行首成员即字重行，FontEntry.display 出带后缀中文名。
            val entry = lib.allEntries(rows).filterIsInstance<FontEntry.System>()
                .first { it.family == "Hannotate SC" }
            assertEquals("手札体-简", entry.display)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun syncSystemFacesPersistsLocalizedDisplayName() = runBlocking {
        // F5 中文名方案A：macOS 桌面喂 CoreText 本地化名，落库 displayName（族名逻辑键不动）。
        val (lib, dir) = freshLib()
        try {
            lib.syncSystemFaces(
                listOf(SystemFontFace("PingFang SC"), SystemFontFace("Songti SC")),
                localizedNames = mapOf("PingFang SC" to "苹方-简", "Songti SC" to "宋体-简"),
            )
            val byName = lib.list().associateBy { it.familyName }
            assertEquals("苹方-简", byName["PingFang SC"]!!.displayName)
            assertEquals("宋体-简", byName["Songti SC"]!!.displayName)

            // 族名仍为逻辑键（槽位/取字形），展示名经 FontEntry.display 出中文。
            val entry = lib.allEntries(lib.list()).filterIsInstance<FontEntry.System>()
                .single { it.family == "PingFang SC" }
            assertEquals("PingFang SC", entry.family)
            assertEquals("苹方-简", entry.display)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun syncSystemFacesUpsertRefreshesDisplayNameKeepsHidden() = runBlocking {
        // UPSERT 语义：既有系统行刷新 displayName，hidden 保留（隐藏族不复活）；id 不换。
        val (lib, dir) = freshLib()
        try {
            lib.syncSystemFaces(listOf(SystemFontFace("Songti SC", "Regular")))
            val firstId = lib.list().single().id
            lib.setHidden(firstId, true)

            lib.syncSystemFaces(
                listOf(SystemFontFace("Songti SC", "Regular")),
                localizedNames = mapOf("Songti SC" to "宋体-简"),
            )
            val row = lib.list().single()
            assertEquals("宋体-简", row.displayName)
            assertTrue("hidden 保留", row.hidden)
            assertEquals(firstId, row.id)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun systemDisplayFallsBackToFamilyWhenNoLocalizedName() = runBlocking {
        // 非 mac 平台：localizedNames 缺省 → displayName 落族名，展示直接回退族名本身
        // （字典已删，是什么显示什么）。
        val (lib, dir) = freshLib()
        try {
            lib.syncSystemFaces(listOf(SystemFontFace("PingFang SC")))
            val entry = lib.allEntries(lib.list()).filterIsInstance<FontEntry.System>().single()
            assertEquals("PingFang SC", entry.displayName) // 无本地化名 = 族名本身
            assertEquals("PingFang SC", entry.display) // 展示回退族名本身
        } finally {
            dir.deleteRecursively()
        }
    }
}
