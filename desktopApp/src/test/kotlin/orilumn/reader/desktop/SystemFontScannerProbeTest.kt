package orilumn.reader.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import orilumn.reader.data.db.LibraryDb
import orilumn.reader.data.font.FontLibrary
import orilumn.reader.data.font.SystemFontFace
import orilumn.reader.db.OrilumnDb
import orilumn.reader.engine.SystemFontScanner
import orilumn.reader.engine.SystemFontSource
import orilumn.reader.engine.TaskScheduler
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Probe（P1 字体发现）：[SystemFontScanner] 的调度编排，**注入确定性枚举端口**（不依赖本机系统字体）。
 *
 * 锁定方案《字体发现与开书调度方案》§2–§3 的几条硬语义：
 *  1. 启动一次性检测：指纹变才扫；扫完写指纹；再次启动（指纹已匹配）**什么都不做**；
 *  2. 逐族落成果 + 回调：每族写库后调用一次 [SystemFontScanner.onFamilyScanned]（面板增量刷新）；
 *  3. 面板需求模式：指纹过期时打开面板即实扫（`PRIO_FONT_DEMAND`）；
 *  4. 抗抢占续跑：扫到一半被 `cancelLowerThan(0)`（模拟翻页）砍掉，面板仍可见 ⇒ 自动重提续跑，
 *     已完成族不重扫；
 *  5. 收尾清孤儿：本次枚举里不存在的族残留行删除。
 */
class SystemFontScannerProbeTest {

    private val version = 7L

    private fun freshLib(): Pair<FontLibrary, File> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OrilumnDb.Schema.create(driver)
        val dir = Files.createTempDirectory("orilumn-fontscan").toFile()
        val lib = FontLibrary(LibraryDb(OrilumnDb(driver)), FileSystem.SYSTEM, File(dir, "fonts").absolutePath.toPath())
        return lib to dir
    }

    /** 可变的确定性枚举端口：族集可换，记录 `facesFor` 调用与族哈希。 */
    private class FakeSource(families: List<String>) {
        @Volatile
        var families: List<String> = families
        val facesCalls: MutableList<String> = Collections.synchronizedList(ArrayList())

        fun asSource(): SystemFontSource = SystemFontSource(
            families = { families },
            facesFor = { f ->
                facesCalls.add(f)
                listOf(SystemFontFace(f, "Regular"), SystemFontFace(f, "Bold"))
            },
            familyHash = { families.joinToString("|").hashCode().toLong() },
            cacheVersion = 7L,
        )
    }

    private suspend fun awaitTrue(msg: String, timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        try {
            withTimeout(timeoutMs) { while (!cond()) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$msg (timed out after ${timeoutMs}ms)", e)
        }
    }

    @Test
    fun `startup detect scans only when the fingerprint changed, then skips`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            assertNull("fresh library has no stored fingerprint", lib.fontScanState())
            val fake = FakeSource(listOf("FamA", "FamB"))
            var callbacks = 0
            val scanner = SystemFontScanner(
                library = lib,
                scheduler = TaskScheduler(scope, Dispatchers.Default, 1),
                source = fake.asSource(),
            )
            scanner.onFamilyScanned = { callbacks++ }

            scanner.detectOnStart()
            awaitTrue("scan must land both families × 2 styles") { lib.list().size == 4 }
            assertEquals("stored fingerprint must match current", version to fake.families.joinToString("|").hashCode().toLong(), lib.fontScanState())
            assertEquals("one callback per family", 2, callbacks)
            assertEquals("one facesFor per family", listOf("FamA", "FamB"), fake.facesCalls.toList())

            // 第二台扫描器（指纹已匹配）：detectOnStart 不得再扫。
            val fake2 = FakeSource(listOf("FamA", "FamB"))
            SystemFontScanner(lib, TaskScheduler(scope, Dispatchers.Default, 1), source = fake2.asSource())
                .detectOnStart()
            delay(300)
            assertTrue("unchanged fingerprint must not enumerate faces", fake2.facesCalls.isEmpty())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `panel demand scans immediately when the library is stale`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // 预置一个过期指纹（版本不同）⇒ 库冷。
            lib.saveFontScanState(version - 1, 12345L)
            val fake = FakeSource(listOf("FamA", "FamB", "FamC"))
            val scanner = SystemFontScanner(lib, TaskScheduler(scope, Dispatchers.Default, 1), source = fake.asSource())

            scanner.onPanelVisibility(true)
            awaitTrue("panel demand must scan all families") { lib.list().size == 6 }
            assertEquals("fresh fingerprint written after completion", version to fake.families.joinToString("|").hashCode().toLong(), lib.fontScanState())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `preemption while the panel is open resubmits and resumes without redoing finished families`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(listOf("FamA", "FamB", "FamC"))
            val scheduler = TaskScheduler(scope, Dispatchers.Default, 1)
            val scanner = SystemFontScanner(lib, scheduler, source = fake.asSource())

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = AtomicInteger(0)
            scanner.onFamilyScanned = {
                // 首族落库后卡住扫描（此刻 FamA 已在库 + 已完成族集里），模拟"扫到一半"。
                if (callbacks.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await()
                }
            }

            scanner.onPanelVisibility(true)
            entered.await()
            // 翻页抢占（notifyFlip = cancelLowerThan(0)）：砍掉在跑的扫描。
            scheduler.cancelLowerThan(0)
            awaitTrue("preempted scan must auto-resume and finish all 3 families") { lib.list().size == 6 }
            assertEquals("FamA must not be re-enumerated on resume", listOf("FamA", "FamB", "FamC"), fake.facesCalls.toList())
            assertTrue("FamA finished before preemption must not rescan", fake.facesCalls.count { it == "FamA" } == 1)
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `final prune drops families no longer enumerated`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(listOf("FamA", "FamB"))
            val scanner = SystemFontScanner(lib, TaskScheduler(scope, Dispatchers.Default, 1), source = fake.asSource())
            scanner.detectOnStart()
            awaitTrue("initial scan lands both families") { lib.list().size == 4 }

            // FamB 卸载：族集变 ⇒ 指纹变 ⇒ 下一次面板打开重扫并清孤儿。
            fake.families = listOf("FamA")
            scanner.onPanelVisibility(true)
            awaitTrue("FamB rows must be pruned") { lib.list().all { it.familyName == "FamA" } && lib.list().size == 2 }
            assertTrue("FamA still present", lib.list().any { it.familyName == "FamA" })
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
