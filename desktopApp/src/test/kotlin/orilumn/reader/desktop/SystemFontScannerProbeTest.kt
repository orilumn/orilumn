package orilumn.reader.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.CancellationException
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
 *  4. 抗抢占续跑：扫到一半被 `cancelLowerThan(0)`（模拟翻页）砍掉，**面板开没开都要**续跑到底
 *     并写指纹（让位不丢活），已完成族不重扫；
 *  5. 收尾清孤儿：本次枚举里不存在的族残留行删除；
 *  6. 开书/换书带的 `onPanelVisibility(false)` **不得**重启在途扫描（同档去重）；
 *  7. 空族集（平台枚举失败）不写指纹、也不空转。
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

        /** 文件环境哈希（§8-2）：默认 0（纯族名指纹）；测试可改以模拟「族名不变、文件被换」。 */
        @Volatile
        var environmentHash: Long = 0L
        val facesCalls: MutableList<String> = Collections.synchronizedList(ArrayList())

        /** 族名枚举次数：续跑看门狗空转（无成果的反复补跑）会把它推高。 */
        val familiesCalls = java.util.concurrent.atomic.AtomicInteger(0)

        fun asSource(): SystemFontSource = SystemFontSource(
            families = {
                familiesCalls.incrementAndGet()
                families
            },
            facesFor = { f ->
                facesCalls.add(f)
                listOf(SystemFontFace(f, "Regular"), SystemFontFace(f, "Bold"))
            },
            familyHash = { families.joinToString("|").hashCode().toLong() },
            familyHashOf = { list -> list.joinToString("|").hashCode().toLong() },
            environmentHash = { environmentHash },
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
            scanner.onFamilyScanned = { _, _ -> callbacks++ }

            scanner.detectOnStart()
            val expectedFingerprint = version to fake.families.joinToString("|").hashCode().toLong()
            // 族行先落库、指纹在收尾时写；只等 lib.list() 会与收尾写指纹竞态（曾偶发红）。
            awaitTrue("scan must land both families × 2 styles and the fingerprint") {
                lib.list().size == 4 && lib.fontScanState() == expectedFingerprint
            }
            assertEquals("stored fingerprint must match current", expectedFingerprint, lib.fontScanState())
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
    fun `per-family callback reports running done over total`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(listOf("FamA", "FamB", "FamC"))
            val seen = Collections.synchronizedList(ArrayList<Pair<Int, Int>>())
            val scanner = SystemFontScanner(
                library = lib,
                scheduler = TaskScheduler(scope, Dispatchers.Default, 1),
                source = fake.asSource(),
            )
            scanner.onFamilyScanned = { done, total -> seen.add(done to total) }

            scanner.detectOnStart()
            awaitTrue("all families land") { lib.list().size == 6 }
            assertEquals("每族回调须带 (已扫, 总数)", listOf(1 to 3, 2 to 3, 3 to 3), seen.toList())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unchanged family names but changed font files still rescan`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(listOf("FamA", "FamB"))
            SystemFontScanner(lib, TaskScheduler(scope, Dispatchers.Default, 1), source = fake.asSource())
                .detectOnStart()
            awaitTrue("initial scan lands both families") { lib.list().size == 4 }
            assertEquals(listOf("FamA", "FamB"), fake.facesCalls.toList())

            // 族名集合**不变**，但字体文件被换（环境哈希 0 -> 42）⇒ 指纹变 ⇒ 下次检测必须重扫。
            val fake2 = FakeSource(listOf("FamA", "FamB")).also { it.environmentHash = 42L }
            SystemFontScanner(lib, TaskScheduler(scope, Dispatchers.Default, 1), source = fake2.asSource())
                .detectOnStart()
            awaitTrue("environment change must trigger a rescan") { fake2.facesCalls.size == 2 }
            assertEquals(listOf("FamA", "FamB"), fake2.facesCalls.toList())
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
            scanner.onFamilyScanned = { _, _ ->
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
            // 首族落库时卸载 FamB：扫描体仍拿着 [FamA, FamB] 快照，收尾必须按**快照**写指纹。
            // 若收尾再读一次系统字体，就会把旧数据贴上新指纹，下一次检测误判「已最新」而跳过清理。
            scanner.onFamilyScanned = { done, _ -> if (done == 1) fake.families = listOf("FamA") }
            scanner.detectOnStart()
            val oldHash = listOf("FamA", "FamB").joinToString("|").hashCode().toLong()
            awaitTrue("initial scan must land rows and the snapshot fingerprint") {
                lib.list().size == 4 && lib.fontScanState() == (version to oldHash)
            }

            // 族集变 ⇒ 指纹变 ⇒ 下一次面板打开重扫并清孤儿。
            scanner.onPanelVisibility(true)
            awaitTrue("FamB rows must be pruned") { lib.list().all { it.familyName == "FamA" } && lib.list().size == 2 }
            assertTrue("FamA still present", lib.list().any { it.familyName == "FamA" })
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * **开书（面板并未打开）不得重启在途扫描**（问题 A 回归）。
     *
     * 宿主把「面板关着」与「版面刚换/刚开书」走同一个 `onPanelVisibility(false)` 调用。同档同键重提
     * 在 `TaskScheduler.submit` 里 = 取消在跑的孪生再重启，所以没去重时每次开书都会把在途扫描砍掉
     * 重来（实测日志：`start 45 → start 45 → cancel 45`）。
     */
    @Test
    fun `opening a book must not restart an in-flight scan`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(listOf("FamA", "FamB", "FamC"))
            val scheduler = TaskScheduler(scope, Dispatchers.Default, 1)
            val scanner = SystemFontScanner(lib, scheduler, source = fake.asSource())

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = AtomicInteger(0)
            var cancelled = false
            scanner.onFamilyScanned = { _, _ ->
                if (callbacks.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    try {
                        release.await()
                    } catch (e: CancellationException) {
                        cancelled = true
                        throw e
                    }
                }
            }

            scanner.detectOnStart() // 启动检测 ⇒ 在途、档位 45、扫到首族卡住
            entered.await()
            delay(50)
            scanner.onPanelVisibility(false) // 开书/换书/视口变化路径
            delay(200)
            assertTrue("开书不得取消在途扫描（同档重提）", !cancelled)

            release.complete(Unit)
            awaitTrue("scan must still finish after the book-open call") { lib.list().size == 6 }
            assertEquals("每族只枚举一次", listOf("FamA", "FamB", "FamC"), fake.facesCalls.toList())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * **被抢占的扫描必须还能回来**（问题 B 回归）：面板没开、扫描在跑时被翻页取消（`cancelLowerThan(0)`）
     * 也要续跑到底并写指纹；初版只在「面板可见」时自动重提，这里是超时。
     */
    @Test
    fun `preemption with the panel closed resumes and finishes`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(listOf("FamA", "FamB", "FamC"))
            val scheduler = TaskScheduler(scope, Dispatchers.Default, 1)
            val scanner = SystemFontScanner(lib, scheduler, source = fake.asSource())

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val callbacks = AtomicInteger(0)
            scanner.onFamilyScanned = { _, _ ->
                if (callbacks.incrementAndGet() == 1) {
                    entered.complete(Unit)
                    release.await() // 永不完成：这就是"扫到一半"的卡点，随抢占被取消
                }
            }

            scanner.detectOnStart() // demand = false（面板关着）
            entered.await()
            scheduler.cancelLowerThan(0) // 翻页抢占
            awaitTrue("preempted scan must resume on idle even with the panel closed") { lib.list().size == 6 }
            assertEquals("FamA must not be re-enumerated on resume", listOf("FamA", "FamB", "FamC"), fake.facesCalls.toList())
            awaitTrue("指纹必须落库，否则下次启动白扫一遍") { lib.fontScanState() != null }
            assertEquals(
                "指纹必须等于当前族名指纹",
                version to fake.families.joinToString("|").hashCode().toLong(),
                lib.fontScanState(),
            )
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /**
     * **排队期被清掉的扫描也要补回来**（问题 B 的另一半）：`cancelLowerThan` 的
     * `queue.removeAll { priority > threshold }` 会把还没起跑的 `fontscan` 直接扫出队列——任务体
     * 从未起跑，**没有 catch 可进**，靠自己重提是补不回来的。
     */
    @Test
    fun `scan dropped while queued resumes when the queue goes idle`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(listOf("FamA", "FamB"))
            val scheduler = TaskScheduler(scope, Dispatchers.Default, 1)
            val scanner = SystemFontScanner(lib, scheduler, source = fake.asSource())

            // 占住唯一槽位，让 fontscan 只进队、不起跑。
            val blockerEntered = CompletableDeferred<Unit>()
            val releaseBlocker = CompletableDeferred<Unit>()
            scheduler.submit(
                TaskScheduler.Task("hold", TaskScheduler.PRIO_PAGE_NEXT) {
                    blockerEntered.complete(Unit)
                    releaseBlocker.await()
                },
            )
            blockerEntered.await()

            scanner.detectOnStart()
            awaitTrue("scan must be enqueued behind the blocker, not started") {
                scheduler.pendingPriority("fontscan") == TaskScheduler.PRIO_FONT_SCAN && fake.familiesCalls.get() == 0
            }
            // 翻页抢占：队列里 priority > 0 的活被一并清掉 —— fontscan 从未起跑，没进过任何 catch。
            scheduler.cancelLowerThan(0)
            awaitTrue("queued scan must be dropped from the queue") { scheduler.pendingPriority("fontscan") == null }

            awaitTrue("queued-drop must be healed by the idle resume") { lib.list().size == 4 }
            assertEquals(listOf("FamA", "FamB"), fake.facesCalls.toList())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }

    /** 平台枚举失败（空族集）⇒ 不写指纹、也不许续跑看门狗空转（无成果的补跑循环）。 */
    @Test
    fun `empty enumeration writes no fingerprint and does not spin`() = runBlocking {
        val (lib, dir) = freshLib()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val fake = FakeSource(emptyList())
            SystemFontScanner(lib, TaskScheduler(scope, Dispatchers.Default, 1), source = fake.asSource())
                .detectOnStart()
            delay(600)
            val enumerations = fake.familiesCalls.get()
            assertNull("empty enumeration must not be stored as a fingerprint", lib.fontScanState())
            delay(600)
            assertEquals("resume must not spin on empty enumeration", enumerations, fake.familiesCalls.get())
        } finally {
            scope.cancel()
            dir.deleteRecursively()
        }
    }
}
