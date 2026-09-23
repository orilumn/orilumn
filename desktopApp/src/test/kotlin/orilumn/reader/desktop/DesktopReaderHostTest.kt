package orilumn.reader.desktop

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.PaginationCacheStore
import orilumn.reader.engine.skia.DrawLine
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * S32 桌面管线冒烟：真书（仓库自带 EPUB）走完整阅读管线
 * （解析→级联→盒子→Skia 断行→分页→DrawLine→翻页→进度存档），JVM 直跑。
 */
class DesktopReaderHostTest {

    /** 语料目录扫描：换书不再炸测试（P5-c 真书语料）。 */
    private fun sampleBooks(): List<File> {
        val dir = File("../books")
        val all = dir.listFiles { f -> f.isFile && f.name.endsWith(".epub", ignoreCase = true) }
            ?.sortedBy { it.name } ?: emptyList()
        org.junit.Assume.assumeTrue("无语料跳过真书测试：${dir.absolutePath}", all.isNotEmpty())
        return all
    }

    private fun sampleBook(): File = sampleBooks().first()

    private fun tmpRoot(): File = Files.createTempDirectory("orilumn-desktop-test").toFile()

    @Test
    fun openPaginateFlipAndProgressRoundTrip() = kotlinx.coroutines.runBlocking {
        val root = tmpRoot()
        val store = DesktopShelfStore(root)
        store.load()
        val entry = store.addBook("Kotlin in Action", null, sampleBook().absolutePath, null)

        val host = DesktopReaderHost(
            bookFile = entry.filePath,
            bookId = entry.id,
            store = store,
            settings = ReaderSettings.DEFAULT,
            density = 1f,
            viewportW = 760,
            viewportH = 1000,
            fontLibrary = store.fontLibrary(),
        )
        try {
            val start = withContext(Dispatchers.Default) { host.open() }
            assertNotNull("首开应落位", start)
            assertTrue("书名非空", host.title().isNotBlank())
            val pos = start!!
            assertTrue("首章应有页", host.pageCount(pos.chapter) > 0)

            val lines = host.pageLines(pos)
            assertNotNull("首屏行窗口非空", lines)
            // P1-2: 首章可能是纯空白封面（svg cover 归一化后零行）→ 空页零绘制行；
            // 有行时不断言行带几何。
            for (l in lines!!) {
                assertTrue("行高为正：$l", l.yBottom > l.yTop)
                assertTrue("行带不越界：$l", l.yTop >= 0 && l.yBottom <= 1100)
            }
            assertEquals(
                "行带应严格递增",
                lines.map { it.yTop },
                lines.map { it.yTop }.sorted(),
            )

            val fwd = requireNotNull(withContext(Dispatchers.Default) { host.adjacent(pos, 1) }) { "应能下翻" }
            val back = requireNotNull(withContext(Dispatchers.Default) { host.adjacent(fwd, -1) }) { "应能回翻" }
            assertEquals("回翻应回到首屏", pos.slice.firstLine, back.slice.firstLine)

            val frac = host.pageProgress(fwd)
            assertTrue("进度在 0..1：$frac", frac in 0.0..1.0)

            host.onSaveProgress(fwd)
            // onSaveProgress 是 fire-and-forget，轮询等落盘。
            var locator: ReadingLocator? = null
            repeat(50) {
                locator = store.loadProgress(entry.id)
                if (locator != null) return@repeat
                kotlinx.coroutines.delay(100)
            }
            assertNotNull("进度应落盘", locator)
            assertEquals(fwd.chapter, locator!!.chapter)
            assertEquals(fwd.slice.charStart, locator!!.char)
        } finally {
            host.close()
        }
    }

    @Test
    fun listMarkersAndXLeftReachDrawLines() = kotlinx.coroutines.runBlocking {
        // P3/P5-c：语料内列表叶要有水平偏移 xLeft>0（≈ul 2em 缩进）、列表首行要携带 marker、
        // 代码行要落到等宽栈（monospace）——与平板绘制语义同一标的。信号在语料并集上找，
        // 单本书缺某信号不算失败（换书自由）。
        var seenXLeft = false
        var markerLine: DrawLine? = null
        var seenMono = false
        for (book in sampleBooks()) {
            if (seenXLeft && markerLine != null && seenMono) break
            val root = tmpRoot()
            val store = DesktopShelfStore(root)
            store.load()
            val entry = store.addBook(book.nameWithoutExtension, null, book.absolutePath, null)
            val host = DesktopReaderHost(
                bookFile = entry.filePath,
                bookId = entry.id,
                store = store,
                settings = ReaderSettings.DEFAULT,
                density = 1f,
                viewportW = 760,
                viewportH = 1000,
            fontLibrary = store.fontLibrary(),
            )
            try {
                var pos = requireNotNull(host.open())
                var hops = 0
                while (hops < 40 && !(seenXLeft && markerLine != null && seenMono)) {
                    host.pageLines(pos)?.forEach { l ->
                        if (l.xLeft > 0) seenXLeft = true
                        if (markerLine == null && l.listMarker != null) markerLine = l
                        if (l.monospace) seenMono = true
                    }
                    pos = host.adjacent(pos, 1) ?: break
                    hops++
                }
            } finally {
                host.close()
            }
        }
        assertTrue("语料应含 xLeft>0 的列表缩进行", seenXLeft)
        assertNotNull("语料应含携带 marker 的列表首行", markerLine)
        assertTrue("语料应含等宽栈代码行", seenMono)
        assertTrue("marker 行应带水平偏移（列表缩进内）", markerLine!!.xLeft > 0)
    }

    @Test
    fun paginationCacheWriteThroughAndHit() = kotlinx.coroutines.runBlocking {
        // C1-3：桌面与平板同一共享 Store/参数键——首开写穿 .bin，次开同目录命中同页数。
        val root = tmpRoot()
        val cacheRoot = File(root, "cache")
        val store = DesktopShelfStore(root)
        store.load()
        val entry = store.addBook("Kotlin in Action", null, sampleBook().absolutePath, null)

        fun openHost() = DesktopReaderHost(
            bookFile = entry.filePath,
            bookId = entry.id,
            store = store,
            settings = ReaderSettings.DEFAULT,
            density = 1f,
            viewportW = 760,
            viewportH = 1000,
            fontLibrary = store.fontLibrary(),
            cacheRoot = cacheRoot,
        )
        val host1 = openHost()
        try {
            val start = withContext(Dispatchers.Default) { host1.open() }
            assertNotNull("首开应落位", start)
            val firstCount = host1.pageCount(start!!.chapter)
            assertTrue("首章应有页", firstCount > 0)

            val disk = PaginationCacheStore(FileSystem.SYSTEM, cacheRoot.absolutePath.toPath())
            fun binsUnder(dir: okio.Path): List<okio.Path> =
                runCatching { FileSystem.SYSTEM.list(dir) }.getOrDefault(emptyList())
                    .flatMap { p ->
                        if (p.name.endsWith(".bin")) listOf(p) else binsUnder(p)
                    }
            // C2-P3：写穿走后台 canonical，轮询等落盘（与进度轮询同式）。
            var bins: List<okio.Path> = emptyList()
            repeat(100) {
                bins = binsUnder(cacheRoot.absolutePath.toPath())
                if (bins.isNotEmpty()) return@repeat
                kotlinx.coroutines.delay(100)
            }
            assertTrue("写穿后应有分页表文件", bins.isNotEmpty())
            val table = disk.read(disk.file("book_${entry.id}", start.chapter, paramHashOf()))
                ?: bins.firstNotNullOfOrNull { runCatching { disk.read(it) }.getOrNull() }
            assertNotNull("磁盘表应可读（同一编解码）", table)
            assertEquals("磁盘表页数应与宿主页数一致", firstCount, table!!.totalPages)
        } finally {
            host1.close()
        }

        // 次开：同目录命中，未塑形章亦可报页数（C1-3 pageCount 口径）。
        val host2 = openHost()
        try {
            val start2 = withContext(Dispatchers.Default) { host2.open() }
            assertNotNull("次开应落位", start2)
            assertEquals("命中后页数一致", host1.pageCount(start2!!.chapter), host2.pageCount(start2.chapter))
        } finally {
            host2.close()
        }
    }

    // 与控制器同式：参数键按版心（视口 - profile 边距）哈希，视口语义见 DesktopReaderHost。
    @Test
    fun legacyUnversionedDbRepairsFontTable() = kotlinx.coroutines.runBlocking {
        // F 系列回归：遗留库 user_version=0（旧版 create 未盖章）+ v4 字体表（无 hidden），
        // load() 须查漏补缺并盖章，否则字体查询全挂、面板空白。
        val root = tmpRoot()
        val dbFile = File(root, "orilumn.db")
        val setup = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}")
        setup.execute(
            null,
            "CREATE TABLE font_faces(id INTEGER PRIMARY KEY AUTOINCREMENT, familyName TEXT NOT NULL, " +
                "displayName TEXT NOT NULL, subfamily TEXT NOT NULL DEFAULT '', source TEXT NOT NULL, " +
                "path TEXT, lang TEXT NOT NULL);",
            0,
        )
        setup.execute(null, "PRAGMA user_version = 0;", 0)
        setup.close()

        val store = DesktopShelfStore(root)
        store.load()
        val lib = store.fontLibrary()
        val faces = lib.syncSystemFaces(listOf(orilumn.reader.data.font.SystemFontFace("TestFam")))
        assertEquals(listOf("TestFam"), faces.map { it.familyName })
        lib.setHidden(faces.single().id, true)
        assertEquals(true, lib.list().single().hidden)
    }

    private fun paramHashOf(): Long {
        val profile = orilumn.reader.engine.text.TypographicProfile.build(ReaderSettings.DEFAULT, 1f)
        return orilumn.reader.engine.text.LayoutParamKey.fromProfile(
            profile,
            760 - profile.marginLeft - profile.marginRight,
            1000 - profile.marginTop - profile.marginBottom,
        ).hash()
    }

    @Test
    fun shelfStoreRoundTrip() = kotlinx.coroutines.runBlocking {
        val root = tmpRoot()
        val store = DesktopShelfStore(root)
        store.load()
        assertTrue(store.allBooks().isEmpty())

        val e = store.addBook("T", "A", "/tmp/x.epub", null)
        assertEquals(1, store.allBooks().size)
        store.touchRead(e.id)
        assertNotNull(store.getEntry(e.id)?.readTime)

        store.savePref("k", "v")
        assertEquals("v", store.loadPref("k", ""))

        store.saveProgress(e.id, ReadingLocator(3, 42))
        assertEquals(ReadingLocator(3, 42), store.loadProgress(e.id))

        // 跨实例持久化。
        val store2 = DesktopShelfStore(root)
        store2.load()
        assertEquals(1, store2.allBooks().size)
        assertEquals("T", store2.allBooks().single().title)
        assertEquals(ReadingLocator(3, 42), store2.loadProgress(e.id))

        store2.delete(e.id)
        assertTrue(store2.allBooks().isEmpty())
    }
}
