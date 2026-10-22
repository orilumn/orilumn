package orilumn.reader.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.data.font.FontLibrary
import orilumn.reader.data.font.SystemFontFace
import orilumn.reader.engine.skia.FONT_SCAN_CACHE_VERSION
import orilumn.reader.engine.skia.systemFontFacesFor
import orilumn.reader.engine.skia.systemFontFamilies
import orilumn.reader.engine.skia.systemFontFamilyHash
import orilumn.reader.engine.skia.systemFontFamilyHashOf
import orilumn.reader.io.Logger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * **系统字体枚举端口**（注入接缝，方案 §5「平台差异经注入端口进任务体」）。
 *
 * 默认接 skiko 平台枚举（[systemFontFamilies] / [systemFontFacesFor] /
 * [systemFontFamilyHash]）；单测可注入确定性实现，让扫描编排（提/降档、逐族续跑、清孤儿、
 * 指纹判定）在无平台字体依赖下可验证。
 */
class SystemFontSource(
    /** ①族名枚举（廉价）。 */
    val families: () -> List<String> = ::systemFontFamilies,
    /** ②单族字形枚举（贵；`matchFamily` + name 表）。 */
    val facesFor: (String) -> List<SystemFontFace> = ::systemFontFacesFor,
    /** 族名集合哈希（指纹的一半）。 */
    val familyHash: () -> Long = ::systemFontFamilyHash,
    /** 对**已枚举的族名快照**算哈希：扫描体用它写与自己数据配对的指纹。 */
    val familyHashOf: (List<String>) -> Long = ::systemFontFamilyHashOf,
    /**
     * **字体文件环境哈希**（指纹的另一半，§8-2 加固）：平台字体文件「路径 + 大小 + mtime」哈希，
     * 用于检测「族名不变、文件被换」（同族名重打包 / OTA 更新系统字体）。默认 `0L` = 无环境信息
     * （纯族名指纹）——测试与未接平台的宿主零改动。宿主传入
     * [orilumn.reader.data.font.fontFilesEnvironmentHash] 按平台根目录计算。
     */
    val environmentHash: () -> Long = { 0L },
    /** 缓存 schema 版本：本地化名推导逻辑变更时递增以主动作废。 */
    val cacheVersion: Long = FONT_SCAN_CACHE_VERSION,
)

/**
 * **系统字体发现编排**（排版层（上））：把「系统字体列表该不该重扫、什么时候扫、扫多少」
 * 收在一块，交给应用级 [TaskScheduler] 排定（方案《字体发现与开书调度方案》§2–§3）。
 *
 * 三层次（方案 §2）：
 *  - **层次一 · 全量扫描**：枚举族（①廉价）→ 逐族枚举字形 + 本地化名并落库
 *    （[FontLibrary.syncSystemFamily]，②③贵）+ 收尾清孤儿（[FontLibrary.pruneMissingSystemFamilies]）
 *    → 写指纹。**逐族提交**是抗抢占续跑与面板增量刷新的同一机制（方案 §3.3）。
 *  - **层次二 · 阅读期变化检测**：每次进程启动**只检测一次**（廉价族名枚举 + 比指纹），
 *    指纹变才提交扫描（档 [TaskScheduler.PRIO_FONT_SCAN]）。指纹 = 族名哈希
 *    ⊕ [SystemFontSource.environmentHash]（§8-2：族名不变但字体文件被换也判为「变」）。
 *  - **层次三 · 导入**：不进调度（用户直接动作，调用方立即刷新）。
 *
 * 需求模式（方案 §2.4、§3.4）：面板可见 ⇒ 同键 `fontscan` 提档到
 * [TaskScheduler.PRIO_FONT_DEMAND]（池顶、立即实扫）；面板关闭 ⇒ 同键降回
 * [TaskScheduler.PRIO_FONT_SCAN]（续跑不丢成果）。同键重提＝提/降档，无需调度器新 API。
 * **同档重提绝不发**（[TaskScheduler.pendingPriority] 去重）：`submit` 对同键的语义是「取消在跑的
 * 旧孪生 + 重启」，而开书/换书/视口变化都会带着 `open=false` 打进来，不去重就会把在途扫描反复
 * 砍掉重来。
 *
 * 抗抢占（方案 §3.3、§8-1）：任务体**逐族落成果**，被 `notifyFlip`
 * （[TaskScheduler.cancelLowerThan] 0）/ `onForegroundRaster`（[TaskScheduler.cancelForRaster]
 * 0）抢占后跳过 [scannedFamilies] 里已完成的族。让位后**必须还能回来**，见 [armResume]：
 * 无论面板开没开，扫描都会在队列真空时自行续跑，直到写出指纹为止。
 *
 * @param library 共享字体库（`:common`）。
 * @param scheduler 应用级队列（[AppTaskScheduler.scheduler]）。
 * @param localizedNames 平台接缝：给定族名列表 → 「拉丁族名 → 本地化（中文）族名」映射。
 *        Android = `/system/fonts` name 表；桌面 = name 表直读 + macOS CoreText。
 * @param source 平台枚举端口（[SystemFontSource]，默认真程序系统枚举；测试可注入）。
 *
 * 面板增量刷新经可更换的 [onFamilyScanned] 回调接入（面板打开时装、关闭时卸）。
 * 回调带 `(done, total)` 族数——面板据此显示「已 N / 共 M 族」实时进度（方案 §2.4）。
 */
class SystemFontScanner(
    private val library: FontLibrary,
    private val scheduler: TaskScheduler,
    private val localizedNames: suspend (List<String>) -> Map<String, String> = { emptyMap() },
    private val logTag: String = "Orilumn.FontScan",
    private val source: SystemFontSource = SystemFontSource(),
) {

    companion object {
        /** 扫描任务的键：提/降档靠**同键重提**，见类文档。 */
        const val KEY = "fontscan"

        /**
         * 续跑看门狗[armResume]连续「空转」上限：只在**没有成果**的补跑上累加，落一族的成果
         * 即归零（见 [scanBody]）。纯粹防空转——例如任务体反复抛异常时，不至于无休止地空闲重排。
         */
        private const val MAX_RESUME_ATTEMPTS = 20

        /**
         * 续跑看门狗的「入队静置窗」：`TaskScheduler.submit` 的入队维护跳是**异步**的，刚提交完
         * 立刻查 [TaskScheduler.pendingPriority] 会查不到键（还没登记），把「还没登记」误判成
         * 「活丢了」而多排一次 —— 那会把刚起跑的扫描砍掉重来。补跑本就在等队列，静置一拍零成本。
         */
        private val RESUME_SETTLE = 200.milliseconds
    }

    /**
     * 每族落库回调（**可更换**）：面板打开时装上（增量刷新列表 + 显示进度）、关闭时卸下。
     * 在扫描任务线程（后台）调用，壳负责切回主线程。可变是为了让应用级单例扫描器
     * 服务「当前这台设备上唯一打开的」那个面板，而无需把 UI 引用塞进构造器。
     *
     * @param done 本轮已落库族数（含被抢占续跑前已完成的），随每族完成递增。
     * @param total 本轮枚举到的族总数（`source.families().size`）。
     */
    var onFamilyScanned: suspend (done: Int, total: Int) -> Unit = { _, _ -> }

    /** 非挂起门（[SyncLock]）：保护 [scannedFamilies]/[scannedFingerprint]。 */
    private val gate = SyncLock()

    /** 同进程内已扫完的族。**跨重提保留** ⇒ 被抢占后重提跳过已完成族；指纹变才整体作废。 */
    private val scannedFamilies = LinkedHashSet<String>()

    /** [scannedFamilies] 所属的指纹；与当前指纹不同 ⇒ 先整体作废再扫。 */
    private var scannedFingerprint: Pair<Long, Long>? = null

    /** 面板是否在用（需求模式）：只影响**续跑**时补跑的档位（面板在用则池顶，否则 45）。 */
    @Volatile
    private var demand: Boolean = false

    /** 启动一次性检测门闩（方案 §2.2 `@Volatile once`）：本实例只检测一次。 */
    @Volatile
    private var detected: Boolean = false

    /** 续跑看门狗[armResume]连续「空转」计数：落一族的成果即归零，只有无成果的补跑才累加。 */
    @Volatile
    private var resumeAttempts: Int = 0

    /** 平台枚举失败（空族集）⇒ 停摆续跑看门狗，等下一次外部触发（面板打开 / 下次启动）再试。 */
    @Volatile
    private var resumeSuppressed: Boolean = false

    /** 最近一次提交的时刻（[RESUME_SETTLE] 静置窗用）。 */
    @Volatile
    private var lastSubmitAt: TimeMark? = null

    /**
     * **层次二 · 启动一次性检测**：只比族名指纹（廉价），指纹变才提交 [TaskScheduler.PRIO_FONT_SCAN]
     * 全量增量扫；不变什么都不做（常态，也是开书变快的根本）。幂等：本实例仅首次生效。
     */
    suspend fun detectOnStart() {
        if (detected) return
        detected = true
        resumeSuppressed = false
        withContext(Dispatchers.Default) {
            // 已有在途扫描（例如面板先打开、已按需求提档到 5）：别用 45 覆盖它，交给它收尾写指纹。
            if (scheduler.pendingPriority(KEY) != null) return@withContext
            val current = currentFingerprint()
            val stored = storedFingerprint()
            if (stored == current) {
                Logger.w(logTag, "startup detect: fingerprint unchanged, skip scan")
                return@withContext
            }
            Logger.w(logTag, "startup detect: fingerprint $stored -> $current, submit scan prio=${TaskScheduler.PRIO_FONT_SCAN}")
            submitScan(TaskScheduler.PRIO_FONT_SCAN)
        }
    }

    /**
     * **需求模式**（方案 §2.4、§3.4）：面板可见 ⇒ 提档到池顶（立即实扫）；不可见 ⇒ 降回 45 续跑。
     *
     * 打开时若已有在途扫描，也要提档（那是「面板要用，正扫着」）；指纹未变且没有在途扫描则什么都不做
     * （库热，DB 直读即可）。关闭时仅在**确实被提档过**（在途档位就是 5）时才降回 45。
     *
     * **同档不动**：开书 / 换书 / 视口变化都会带着 `open=false` 打进来（宿主把「面板关着」与
     * 「版面刚换」走同一个调用），而 `submit` 对同键的语义是取消在跑的孪生再重启 —— 不按档位去重，
     * 就等于每次开书都把在途扫描砍掉重来（实测：`start 45 → start 45 → cancel 45`）。
     */
    suspend fun onPanelVisibility(open: Boolean) {
        demand = open
        if (open) resumeSuppressed = false
        withContext(Dispatchers.Default) {
            val target = if (open) TaskScheduler.PRIO_FONT_DEMAND else TaskScheduler.PRIO_FONT_SCAN
            val current = scheduler.pendingPriority(KEY)
            if (current == target) return@withContext
            if (open) {
                // 在途 ⇒ 同键提档；不在途但指纹过期 ⇒ 提档启动；库热 ⇒ 什么都不做。
                if (current != null || !storedFingerprintMatches()) submitScan(target)
            } else {
                if (current == TaskScheduler.PRIO_FONT_DEMAND) submitScan(target)
            }
        }
    }

    /**
     * 同键提交一次扫描（提/降档），并挂上续跑看门狗 [armResume]。
     *
     * **非挂起**：指纹枚举（族名，廉价）与看门狗续挂都不需要挂起点，且这样 [armResume] 的动作体
     * 也能直接重排。指纹枚举走调用方线程，故 [detectOnStart]/[onPanelVisibility] 已把自身切到
     * [Dispatchers.Default]，看门狗动作体亦然。
     */
    private fun submitScan(priority: Int) {
        val current = currentFingerprint()
        gate.withLock {
            if (scannedFingerprint != current) {
                // 系统字体集合变了（或首次）：已完成族集整体作废。
                scannedFamilies.clear()
                scannedFingerprint = current
            }
        }
        scheduler.submit(
            TaskScheduler.Task(KEY, priority) { scanBody() },
        )
        lastSubmitAt = TimeSource.Monotonic.markNow()
        armResume()
    }

    /**
     * **续跑看门狗**（方案 §8-1「抢占饥饿」收口）：让位给翻页 / 栅格的扫描**必须还能回来**。
     *
     * 为什么不能只在被抢占的 `catch` 里重提（初版做法）：抢占有两条路，只有一条进得了 `catch`。
     * ①在跑被 [TaskScheduler.cancelLowerThan] / [cancelForRaster] 取消 ⇒ 进 catch；
     * ②**还在排队**就被同一句 `queue.removeAll { priority > threshold }` 清出队列 ⇒ 任务体从未
     * 起跑，**没有 catch 可进**，且外面无人知道它没了（连"在途"标志都会永远停在 true）。
     *
     * 于是改挂 [TaskScheduler.submitWhenIdle]：它**不在队列里**，抢占清不到它；等队列真空了再补一次
     * ——那正是「该轮到没人等的活」的时刻。补跑后自行续挂，直到扫描真正收尾（写指纹）为止；
     * 指纹对上即解锁，所以常态（库热）与扫完之后都是零成本、零残留。
     */
    private fun armResume() {
        scheduler.submitWhenIdle {
            withContext(Dispatchers.Default) {
                // 先静置：submit 的入队跳是异步的，刚提交完就查会查不到键（见 [RESUME_SETTLE]）。
                lastSubmitAt?.let { mark ->
                    val elapsed = mark.elapsedNow()
                    if (elapsed < RESUME_SETTLE) delay((RESUME_SETTLE - elapsed).inWholeMilliseconds)
                }
                if (resumeSuppressed) return@withContext
                if (scheduler.pendingPriority(KEY) != null) {
                    // 已有人在扫：接着看（这个分支不会空转——awaitIdle 要求队列真空，而只要它还在
                    // 队里/在跑，下一轮就会继续等它）。
                    armResume()
                    return@withContext
                }
                if (storedFingerprintMatches()) return@withContext // 已收尾：解锁
                if (resumeAttempts >= MAX_RESUME_ATTEMPTS) {
                    Logger.w(logTag, "scan resume gave up after $resumeAttempts attempts without progress")
                    return@withContext
                }
                resumeAttempts += 1
                Logger.w(logTag, "scan resume on idle (attempt $resumeAttempts, demand=$demand)")
                submitScan(if (demand) TaskScheduler.PRIO_FONT_DEMAND else TaskScheduler.PRIO_FONT_SCAN)
            }
        }
    }

    private suspend fun scanBody() {
        val families = source.families()
        // 指纹必须描述**这次实际扫描的族集**：中途系统字体变化时，收尾再读一次会把旧数据
        // 贴上新手印，下次检测误判「已最新」而跳过，已卸载族的残留行也再没人清。
        val environmentHash = source.environmentHash()
        if (families.isEmpty()) {
            // 平台枚举失败：不落成果、不写指纹（否则空指纹会被当成"已扫完"，永久跳过）；同时停摆
            // 续跑看门狗，否则会「补跑又空 ⇒ 再补」空转（等下一次外部触发再试）。
            resumeSuppressed = true
            Logger.w(logTag, "scan aborted: no families enumerated (platform failure)")
            return
        }
        for (family in families) {
            // 被抢占（翻页/栅格/同键重提）时在此抛出；让位后由 [armResume] 的看门狗补回来。
            currentCoroutineContext().ensureActive()
            if (gate.withLock { family in scannedFamilies }) continue
            val faces = source.facesFor(family)
            val localized = localizedNames(listOf(family))
            library.syncSystemFamily(faces, localized)
            // 有成果 ⇒ 续跑看门狗的"空转"计数归零（只有连着补跑又扫不出东西的才该被上限挡下）。
            resumeAttempts = 0
            val done = gate.withLock { scannedFamilies.add(family); scannedFamilies.size }
            notifyFamilyScanned(done, families.size)
        }
        // 收尾：删除本次全量枚举里已不存在的族残留行（空集合短路，平台失败不清库）+ 写指纹。
        library.pruneMissingSystemFamilies(families)
        val fp = source.cacheVersion to (source.familyHashOf(families) xor environmentHash)
        library.saveFontScanState(fp.first, fp.second)
        Logger.w(logTag, "scan done families=${families.size}")
    }

    /** 面板增量刷新/进度回调：UI 回调自身不该中断扫描，但要放行取消（否则吞掉抢占）。 */
    private suspend fun notifyFamilyScanned(done: Int, total: Int) {
        try {
            onFamilyScanned(done, total)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Logger.w(logTag, "onFamilyScanned callback failed: ${e.message}")
        }
    }

    /**
     * 当前指纹 =（缓存 schema 版本，族名哈希 ⊕ 字体文件环境哈希）。
     * 环境哈希默认 `0L` ⇒ `a xor 0 == a`，等价旧「纯族名」指纹（未接平台/测试零改动）。
     */
    private fun currentFingerprint(): Pair<Long, Long> =
        source.cacheVersion to (source.familyHash() xor source.environmentHash())

    /** 已存档指纹；无 / 读失败回 null（视作「变」）。 */
    private suspend fun storedFingerprint(): Pair<Long, Long>? =
        try {
            library.fontScanState()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

    /** 廉价判定：已存档指纹是否等于当前指纹（一次 DB 直读 + 一次族名枚举）。 */
    private suspend fun storedFingerprintMatches(): Boolean = storedFingerprint() == currentFingerprint()
}
