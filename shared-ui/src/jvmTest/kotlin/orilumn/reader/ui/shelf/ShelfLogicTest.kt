package orilumn.reader.ui.shelf

import androidx.compose.ui.graphics.ImageBitmap
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** S27 书架纯逻辑单测：摘要文案 / 排序 / 同名判定 / 导入编排（重复聚合·覆盖语义）。 */
class ShelfLogicTest {

    private fun pf(name: String) = PlatformFile(File("/tmp/$name"))

    @Test
    fun shelfBookSameBookAs_treatsNullAuthorAsEmpty() {
        val a = ShelfBook(1, "书名", null, "/a.epub")
        assertTrue(a.sameBookAs("书名", null))
        assertTrue(a.sameBookAs("书名", ""))
        assertFalse(a.sameBookAs("书名", "作者"))
        assertFalse(a.sameBookAs("其他", null))
        val b = ShelfBook(2, "Title", "Author", "/b.epub")
        assertTrue(b.sameBookAs("title", "author"))
    }

    @Test
    fun shelfSort_labelAndCycle() {
        assertEquals("加入时间", ShelfSort.Added.label())
        assertEquals("阅读时间", ShelfSort.Read.label())
        assertEquals("书名", ShelfSort.Name.label())
        assertEquals(ShelfSort.Read, ShelfSort.Added.next())
        assertEquals(ShelfSort.Name, ShelfSort.Read.next())
        assertEquals(ShelfSort.Added, ShelfSort.Name.next())
    }

    @Test
    fun importSummary_variants() {
        assertEquals("未选择可导入的书籍", importSummary(0, 0))
        assertEquals("导入失败：3 本未导入", importSummary(0, 3))
        assertEquals("已加入书架 2 本", importSummary(2, 0))
        assertEquals("导入成功 2 本，失败 1 本", importSummary(2, 1))
    }

    @Test
    fun skipDuplicatesSummary_listsCounts() {
        assertEquals("导入成功 1 本，跳过重复 4 本", skipDuplicatesSummary(1, 0, 4))
        assertEquals("导入成功 1 本，失败 1 本，跳过重复 2 本", skipDuplicatesSummary(1, 1, 2))
    }

    @Test
    fun importer_run_aggregatesDuplicates_andImportsRest() = runBlocking {
        val existing = listOf(ShelfBook(1, "已有书", "作者A", "/x.epub"))
        val repo = FakeRepo(existing)
        val host = FakeHost(
            scanMap = mapOf("dup.epub" to ScannedShelfBook("已有书", "作者A")),
            importOk = setOf("new1.epub", "new2.epub"),
        )
        val outcome = ShelfImporter(repo, host)
            .run(listOf("new1.epub", "dup.epub", "bad.epub").map(::pf))

        // new1 导入成功；dup 聚合到重复；bad 无元数据 → 走导入路径且失败
        assertEquals(1, outcome.imported)
        assertEquals(1, outcome.failed)
        assertEquals(listOf("dup.epub"), outcome.duplicates.map { it.first.name })
        assertEquals(listOf("已有书"), outcome.duplicates.map { it.second })
        assertEquals(setOf("new1.epub"), host.imported)
    }

    @Test
    fun importer_run_reportsProgress() = runBlocking {
        val host = FakeHost(scanMap = emptyMap(), importOk = setOf("a.epub", "b.epub"))
        val repo = FakeRepo(emptyList())
        val progress = mutableListOf<Pair<Int, Int>>()
        ShelfImporter(repo, host).run(listOf("a.epub", "b.epub").map(::pf)) { i, n ->
            progress += i to n
        }
        assertEquals(listOf(1 to 2, 2 to 2), progress)
    }

    @Test
    fun importer_overwrite_delegatesOverwriteFlag() = runBlocking {
        val repo = FakeRepo(emptyList())
        val host = FakeHost(scanMap = emptyMap(), importOk = setOf("a.epub"))
        val (ok, fail) = ShelfImporter(repo, host)
            .overwrite(listOf("a.epub" to "t1", "b.epub" to "t2").map { (n, t) -> pf(n) to t })
        assertEquals(1, ok)
        assertEquals(1, fail)
        assertEquals(listOf("a.epub" to true, "b.epub" to true), host.importCalls)
    }
}

// ---- 测试替身 ----

private class FakeRepo(private val existing: List<ShelfBook>) : ShelfRepository {
    override fun books(sort: ShelfSort): Flow<List<ShelfBook>> = flowOf(existing)
    override suspend fun allBooks(): List<ShelfBook> = existing
    override suspend fun delete(id: Long) {}
    override suspend fun touchRead(id: Long) {}
}

private class FakeHost(
    private val scanMap: Map<String, ScannedShelfBook>,
    private val importOk: Set<String>,
) : ShelfHost {
    val imported = mutableSetOf<String>()
    val importCalls = mutableListOf<Pair<String, Boolean>>()

    override suspend fun scan(file: PlatformFile): ScannedShelfBook? = scanMap[file.name]
    override suspend fun import(file: PlatformFile, overwrite: Boolean): Boolean {
        importCalls += file.name to overwrite
        val ok = file.name in importOk
        if (ok) imported += file.name
        return ok
    }

    override suspend fun loadCover(coverRef: String?): ImageBitmap? = null
    override fun openBook(book: ShelfBook) {}
}