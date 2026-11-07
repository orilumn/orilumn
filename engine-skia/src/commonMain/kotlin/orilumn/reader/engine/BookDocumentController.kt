package orilumn.reader.engine

import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.settings.BookSettings
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.time.platformNowMs
import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.epub.EpubParser
import orilumn.reader.engine.css.BookStyleProbe
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.StyleSheet
import orilumn.reader.engine.css.uaSheetFromProfile
import orilumn.reader.engine.html.HEADING_TAGS
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.ParsedChapter
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.BookPageNumber
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.paging.bookPageNumberOf
import orilumn.reader.engine.paging.bookTotalPagesOf
import orilumn.reader.engine.paging.chapterStartPagesOf
import orilumn.reader.engine.paging.Paginator
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PagedLayout
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.io.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.Path
import okio.Path.Companion.toPath


/**
 * Backward cross-chapter entry anchor (C1-2): app-side projection of the shared
 * [backwardEntryAnchor] strategy onto [ChapterUnit]+markup. Tests keep calling this FQN.
 */
/** C2-P2b-4: public probe hook (was `internal`; `:app` probe tests live across the module seam). */
fun backwardEntryAnchorChar(unit: ChapterUnit, markup: MarkupElement): Int =
    backwardEntryAnchor(
        unit.paginationTable?.pages?.lastOrNull()?.charStart,
        unit.pageSlices.lastOrNull()?.charStart,
        markup.textLength,
    )


/**
 * Whole-book orchestration facade: lazy chapter layout, whole-book
 * progress conversion, page-flip advancement, and relayout anchoring.
 * It is the sole entry point between `ReaderActivity` and the engine internals.
 *
 * Key design: this class holds the reader and parses the book (the constructor only takes the
 * reader). Layout depends on the visible viewport size (width controls line wrapping, height
 * controls pagination), injected by the UI layer through [setViewport] after layout is ready.
 * Progress restore and whole-book progress conversion are based on each chapter's `textLength`
 * obtained during parsing, so no whole-book layout is needed.
 *
 * Threading model: [open]/[ensureChapterLayout] may be called on a background thread; layout is
 * serialized sideways with [Mutex] to avoid building the same chapter concurrently; the UI thread
 * only reads already-laid-out results.
 *
 * Priority contract (C1-2 定序；P2 全部收进 [TaskScheduler]，与桌面宿主同一顺序）：
 *  - **F 目标页**（最高，`PRIO_FLIP`=0 仅为参考值）：跑在调用方线程上——同步塑形并呈现，
 *    既不等后台也永不被后台抢；**不进队列**。翻页入口 [notifyFlip] 统杀其下全部池任务（唯一钩子）。
 *  - **第 2/3/4 档 = 邻页序列**（`PRIO_PAGE_NEXT`=10 / `PRIO_PAGE_PREV`=20 / `PRIO_PAGE_REST`=30）：
 *    序列由 [neighborSequence] 统一生成（原则 §3.1 的 `+1·dir, −1·dir, +2·dir, −2·dir, …`），
 *    d=1 两侧各占一档、d≥2 合为第三档；磁盘表路径用同键 `pg:<章>:<页>` 去重，只有第 2 档那侧
 *    才组装（见 [scheduleWindowPrefill]）。
 *  - **d=1 章外兜底**（`PRIO_EDGE_FORWARD`=第2档 / `PRIO_EDGE_BACKWARD`=第3档，原则 §3.3）：
 *    d=1 邻页越出本章时，加急整章排邻章。键 `edge:<章>` **与 B2 扫描的 `b2:<章>` 分域**——
 *    同一份工作、两种紧急度，共用键会被同键去重把加急件降级（见 [edgeKey]）。
 *  - **B1 本章全量**（`PRIO_B1_CHAPTER`=40，原则 §3.4 动态档）：同键 `b1:<章>` 提交替换孪生；
 *    目标页距章首 d=1 时升到 `PRIO_B1_URGENT`（=第2档），见 [b1PriorityFor] / [dispatchB1]。
 *  - **B2 其他章全量**（`PRIO_B2_CHAPTER`=50）：逐章任务，跳过当前章（B1 拥有），
 *    键 `b2:<章>`，按 [flipDir] 的 |章距| 交错序。
 *  - **A temp 邻页预排**（[tempPrefillJob] on [backgroundDispatcher]）：临时表侧邻页塑形。
 *    **D3 待改**：原则 §3 规定第 2/3/4 档按定义跑在临时表上，而本通道现在仍在池外，
 *    这三档对最延迟敏感的活等于没有执行体——见整改方案 D3b。
 *  - **P 解析预热**（`PRIO_PREWARM`=60，最低）：邻章/开书的 markup + 轻结构，无塑形，
 *    按 paramHash 幂等，同键 `pre:<章>` 去重。
 */class BookDocumentController(
    private val reader: EpubResourceReader,
    private val layouter: ChapterLayouter,
    var profile: TypographicProfile,
    private val logTag: String = "Orilumn.Engine",
    /** Coroutine scope the background canonical (chapter-head) full relayout runs on. Supply a
     *  lifecycle-bound scope from the UI layer so background work is cancelled on exit.
     *  [backgroundDispatcher] is where the heavy full-chapter canonical layout actually runs — it must
     *  be a background dispatcher (NOT the UI dispatcher), so a full-chapter shape never blocks the
     *  main thread / foreground page-flip. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val backgroundDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
    /** Optional EPUB image loader for `<img>` rendering; null = images fall back to placeholders. */
    private val imageLoader: ImageLoader? = null,
    /**
     * **应用级共享队列**（宿主注入 [AppTaskScheduler.scheduler]）。null ⇒ 退回「每书一个池」
     * （绑定 [bgScope]，[close] 整池取消）——**测试与无宿主场景的兼容默认值**，语义与旧行为一致。
     *
     * 注入共享池时，[close] 只撤本控制器键域内的活（[controllerKeyPrefixes]），不惊动
     * 共享池里的其他书 / 系统级字体任务。
     */
    private val sharedScheduler: TaskScheduler? = null,
) {

    /**
     * 章字体需求回调（宿主实现）：任一整形发生**之前**调用，宿主把命中的导入面装进
     * skia 共用池。开屏后追装必见字体跳变，故需求先行；返回是否发生变化
     * （变化且已有版式时调用方作废重排，保证首绘即对）。
     */
    var onDemandFonts: suspend (orilumn.reader.engine.css.FontDemand) -> Boolean = { false }

    /**
     * P2-b: 书内字体就绪回调（宿主把字节合并入池；返回池是否变化）。
     * 与 [onDemandFonts] 同在整形前调用，变化即作废本章版式（字体度量一变几何全变）。
     */
    var onBookFonts: suspend (List<orilumn.reader.engine.css.BookFont>) -> Boolean = { false }

    /** 书内字体字节缓存（归一化 href → 去混淆后字节；null＝不可用，记得住不再试）。 */
    private val bookFontBytesCache = HashMap<String, ByteArray?>()

    /** 版式指纹基线：上次磁盘清理时的 [currentParamHash]（构造时按初始 profile 钉底，
     *  [setViewport] 注入真实视口后经 [cleanStaleDiskTables] 推进）。
     *  [cleanStaleDiskTables] 只在指纹偏离基线时才动磁盘：非版式提交（夜间模式
     *  切换、开书探针回填后与存量参数一致）零误删；换字体/调参/探针真回填
     *  新值（指纹变）才清旧指纹磁盘表。声明在 [init] 之前：基线在构造时钉底。 */
    @Volatile
    private var cleanBaselineParamHash: Long = 0L

    /**
     * P2-b: 章节书内字体（`@font-face` → 相对源 href 解析 → zip 取字节 →
     * IDPF 去混淆 → 魔数校验）。缺 CSS/无引用即空表；坏字体跳过并记日志，永不崩版式。
     */
    fun bookFontsFor(index: Int): List<orilumn.reader.engine.css.BookFont> {
        val unit = unitAt(index) ?: return emptyList()
        val bundle = unit.cssBundle ?: return emptyList()
        if (bundle.cssTexts.isEmpty()) return emptyList()
        val spineHref = book?.spine?.getOrNull(index)?.href ?: return emptyList()
        // P2: 与排版 parse 同视口（@media 下字体引用与规则同取舍）。
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val viewport = orilumn.reader.engine.css.CssViewport(contentW, contentH)
        val sheets = bundle.cssTexts.map {
            orilumn.reader.engine.css.LightCssParser().parse(it, viewport)
        }
        val refs = orilumn.reader.engine.css.collectBookFonts(sheets, bundle.baseHrefs, spineHref, reader::resolveRelative)
        if (refs.isEmpty()) return emptyList()
        val obf = book?.obfuscatedFonts?.map { reader.normalizePath(it) }?.toSet() ?: emptySet()
        val uid = book?.uid ?: ""
        val out = ArrayList<orilumn.reader.engine.css.BookFont>()
        for (ref in refs) {
            val key = reader.normalizePath(ref.href)
            if (!bookFontBytesCache.containsKey(key)) {
                val raw = runCatching { reader.readBytes(ref.href) }.getOrNull()
                val bytes = if (raw == null || raw.isEmpty()) {
                    null
                } else {
                    val deob = if (key in obf) orilumn.reader.engine.css.deobfuscateIdpf(raw, uid) else raw
                    if (orilumn.reader.engine.css.sniffFontFormat(deob).isEmpty()) {
                        Logger.w(logTag, "book font skip unreadable href=${ref.href} family=${ref.family}")
                        null
                    } else deob
                }
                if (bytes == null) {
                    Logger.w(logTag, "book font miss href=${ref.href} family=${ref.family}")
                }
                bookFontBytesCache[key] = bytes
            }
            bookFontBytesCache[key]?.let { out.add(orilumn.reader.engine.css.BookFont(ref.family, it)) }
        }
        return out.distinct()
    }

    init {
        // If the layouter is a BoxChapterLayouter, inject the ImageLoader at construction time.
        (layouter as? BoxChapterLayouter)?.let { bc ->
            // ImageLoader is bound per-chapter via bindChapterContext before each layout call,
            // so we only need to ensure it exists here. BoxChapterLayouter was already constructed
            // with imageLoader in its constructor (passed from ReaderActivity), so nothing to do
            // here — but this init block documents the intent.
        }
        // 指纹基线钉底：构造时 profile 已知、视口未注入（0→内容盒钳 16px，
        // 是幻影指纹）；宿主紧随构造调 setViewport 注入真实视口，指纹随之
        // 偏离基线 —— 那次清理因 bookId 尚未设置（<0）不动磁盘，只把基线
        // 推进到真实指纹。此后开书探针回填/字体变更等面板外提交才谈得上
        // 「指纹真变 vs 一致」。
        cleanBaselineParamHash = currentParamHash()
    }

    private val parser = EpubParser()
    private val converter = HtmlTreeConverter()
    private val chapters: MutableList<ChapterUnit> = ArrayList()
    private val layoutMutex = Mutex()

    /** The parsed book (available after [open]). */
    var book: orilumn.reader.data.epub.EpubBook? = null
        private set

    /** Restored starting chapter (0 when there is no saved progress). */
    var startChapter: Int = 0
        private set

    /** Character offset within the starting chapter (used to locate the displayed page). */
    var startChar: Int = 0
        private set

    private var viewW = 0
    private var viewH = 0

    /** P0 spike: retired per-chapter B1 slots — same-key scheduler submit (`b1:<chapter>`)
     *  replaces twins, preserving the one-slot-per-chapter dedup semantic. */

    /** True while the settings panel is open: the heavy background canonical full-chapter relayout is
     *  suppressed so it never contends with the foreground real-time temp shaping (they share CPU).
     *  On panel close, [finalizeRelayoutAll] re-runs the canonical/full relayout wholesale. */
    var deferCanonical: Boolean = false

    /**
     * 拉窗口 / 拖分栏手势在途（与 [deferCanonical] 同义、来源不同）：几何每帧都在变，整章 B1 /
     * 整书 B2 / 邻页预排产出的表下一帧就作废（纯孤儿），却会和第一优先级的锚页重排抢同一把
     * `layoutMutex` 与 CPU——实测拖窗时锚页重排被饿到 0.5–1.9 秒，肉眼就是「窗口先拉开、内容好久
     * 才出现」。故手势期间只跑锚页重排，其余一律让路（总则第三优先级让位第一优先级）。
     *
     * 生命周期归用户层（桌面壳）：尺寸开始变 → [beginViewportGesture]；收口那次重排前 →
     * [endViewportGesture]。与面板互不干扰（两者可同时成立）。
     */
    private var deferCanonicalForViewport: Boolean = false

    /** canonical（B1/B2/整书）此刻是否该让路：面板打开 **或** 视口手势在途。 */
    private val canonicalDeferred: Boolean get() = deferCanonical || deferCanonicalForViewport

    /**
     * 视口手势开始（第一优先级接管）：钳住后台 canonical，并就地砍掉已经派出去的低档活。
     *
     * 为什么必须砍而不是等它自己跑完：它们全都按**旧视口指纹**在排，几何一变就是废活；
     * 而锚页重排要跟同一把 `layoutMutex` 与同一批 CPU 争，等它跑完等于让读者盯着黑窗。
     * 幂等：手势内每帧都会调，只有第一次真正生效。
     */
    fun beginViewportGesture() {
        if (deferCanonicalForViewport) return
        deferCanonicalForViewport = true
        scheduler.cancelForRaster(TaskScheduler.PRIO_FLIP)
        Logger.w(logTag, "viewport gesture begin: background cancelled")
    }

    /** 视口手势结束（收口提交之前解抑）：后续 B1/B2 照常派发（B2 自己还有静默合并）。 */
    fun endViewportGesture() {
        if (!deferCanonicalForViewport) return
        deferCanonicalForViewport = false
        Logger.w(logTag, "viewport gesture end")
    }

    /** Global layout epoch (P2): monotonically increasing; incremented on every [prepareRelayout].
     *  Every background job carries the epoch it was launched under and skips when it is stale
     *  (a newer params/target cycle superseded it). */
    @Volatile
    var layoutEpoch: Long = 0
        private set

    /** Epoch the most recently dispatched whole-book (B2) job was launched under; used to suppress
     *  re-dispatch of the same settled params (no churn while dragging). */
    @Volatile
    /** 派发层去重戳（D6）：挡滑块拖拽期的**重复派发**。
     *
     *  为什么留着（原则 §8 映射表末行曾把它列为待删散装变量，此处更正）：
     *  池的同键去重替代不了它。拖拽期每次改参都派一趟 B2，每趟 paramHash 都更新，
     *  于是同键孪生永远是「新的赢」，每一趟都会真的起跑；真正兜住的是任务体内的
     *  [b2ChapterTask] skip-fresh，但那已经在执行之后了。本戳在**派发之前**挡住，
     *  比任务体内的 no-op 便宜一个量级。P0 实测拖拽期约 37 次/分钟。
     *
     *  与 skip-fresh 的分工：一个挡派发、一个挡执行。两者都要。 */
    private var lastDispatchEpoch: Long = -1L

    /** 整书 B2 的静默合并计时（见 [requestWholeBookRelayout]）：新请求取消旧计时，只留最后一趟。 */
    private var b2DispatchJob: Job? = null

    /** The chapter most recently prepared by [prepareRelayout]; the B2 scan skips it (B1 owns its
     *  canonical pass). */
    @Volatile
    private var activeChapter: Int = -1

    /**
     * 读者**眼前**那一章的章号（最近一次落位/翻页的目标章）。[evictFarChapters] 绕开它。
     *
     * 为什么不能拿 [activeChapter] 顶替：后者只在开书与调参时写，翻页全程不动。读者一口气翻出
     * 30 章之后，`keep` 就落在读者身后很远的地方，于是后台 B2/`edge:` 任务收尾那次清扫把
     * **正在显示的那一章**的解析态（含 `layout`）一起清掉——`pageLines` 读不到版式 ⇒ 整页空白，
     * 且渲染路径只读不自愈，必须再翻一页（触发 ensure）才画得出来。真机日志指纹：
     * `non-Readback layout ch=N ... blank content` 恰好出现在 `edge:N+1` 完成之后 ~200ms，
     * 紧接着翻页日志里 `layoutLC=-1 pageSlices=7 laidOut=false`（= 只有 `evictParsedState`
     * 能造出这个组合）。
     *
     * 与开书白屏（见 [firstContentChapter]）是同一类事故：驱逐必须永远让开「正在渲染的章」。
     */
    @Volatile
    private var displayedChapter: Int = -1

    // ── 开书闸门（总则 §第一优先级：目标页出画面之前不启动任何整章重排）──
    //
    // 病根：开书时 [requestWholeBookRelayout]（宿主在 `open()` 之后立刻派）和
    // `startAnchorStream` 尾部的 `dispatchB1` + `requestWholeBookRelayout(force)` 都在锚页
    // **排版期间**就把整章全量的活塞进池里。真机实测：GIMP 落位章 ch18（2579 块）被
    // B2/B1 抢走两个槽，`layoutMutex` 上堵 15.9s，锚页 5.84s 才拿到锁，读者全程白屏。
    //
    // 闸门期间这些派发不执行，只**记账**（[openGateWantsB1] / [openGateWantsB2]）；锚页排完、
    // `ensureChapterLayout` 末尾由 [releaseOpenGate] 统一补派。出闸次序 = 原则次序：第 2/3 档邻页
    // （`scheduleWindowPrefill` / `scheduleTempPrefill`，在闸门内就已派）→ 本章全量 B1 → 其他章 B2。
    @Volatile
    private var openAnchorGate: Boolean = false

    /** 闸门期间被压下的「锚页章需要整章表」请求（出闸时补派 B1）。 */
    @Volatile
    private var openGateWantsB1: Boolean = false

    /**
     * R20 开书基线（墙钟）：[open] 写、落位（`logJumpLanding("open", …)`）读。
     * 0 = 本次没走过 [open]。**只放墙钟**：堆/CPU 采样探针的自身成本高于被测对象
     * （`Runtime.freeMemory` 一次全量 GC；`Debug.threadCpuTimeNanos` 首次 ≈1.2s），
     * 不能进开书热路径——教训记在 [BoxChapterLayouter.backfillBlockRanges] 的 R21 诊断注释里。
     */
    @Volatile
    private var openT0: Long = 0L

    /** R28：本次开书的续读锚点（`章@字符`），供 `jump: open ->` 自报，A/B 按它分组。 */
    @Volatile
    private var openAnchor: String = "-"

    /** 闸门期间被压下的「整书章扫描」请求（出闸时补派 B2）。 */
    @Volatile
    private var openGateWantsB2: Boolean = false

        /** **翻页方向记录**（原则 §3.2）：`+1` 上次向前翻页、`−1` 上次向后翻页、`0` **无记录**。
     *
     *  写入：
     *  - 翻页入口 [findAdjacentPage] 记当次 `direction`；[nextPageInChapter] / [prevPageInChapter]
     *    是真实的翻页动作，各自记 `+1` / `−1`（curl 邻页预渲染也走这两条）。
     *  - **开书（字段初值）、跳转、参数重排一律清 `0`**，见 [clearFlipDir]。
     *
     *  消费（三处，全按「非负即前向」判定，故 `0` 无需特判、自动归前向）：
     *  第 2/3 档邻页选择（[scheduleWindowPrefill]）、第 4 档本章排序、第 7 档章距排序
     *  （[orderRemainingChapters]）。清零后"下一页"回到 页码+1。
     *
     *  清零的理由：开书/跳转/调参这三个动作**本身不携带方向含义**，不该继承上一个动作的猜测。 */
    @Volatile
    private var flipDir: Int = 0

    /** 清除翻页方向记录（原则 §3.2）。落位型入口在**开始**时调用——落位完成后紧接着的那次翻页
     *  才是排序信号的来源，若在落位后写就等于用"跳转前"的猜测指导"跳转后"的首翻。 */
    private fun clearFlipDir(reason: String) {
        if (flipDir != 0) Logger.w(logTag, "flipDir cleared by $reason (was $flipDir)")
        flipDir = 0
    }


/** P1f/R13: controller-owned background scope (child of the injected [scope]'s Job).
     *  ALL background launches (prefill/B1/B2/prewarm/watchers, scheduler internals) run here so
     *  [close] cancels exactly this controller's background work — never the host's scope.
     *  Hosts call [close] on destroy; tests call it in teardown (leaked shapers otherwise pile up
     *  in the shared worker JVM and starve later timing-sensitive tests — observed flake). */
    private val bgScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + backgroundDispatcher)

    /** Cancels all background work owned by this controller. Idempotent; safe to call twice. */
    fun close() {
        // 闸门随控制器一起作废：控制器没了，记着"待补派"也无处可派。
        openAnchorGate = false
        openGateWantsB1 = false
        openGateWantsB2 = false
        // B1 续跑看门狗活到共享池的 scope 上（不是 bgScope），键域取消清不到它——必须显式作废代号，
        // 否则控制器关掉之后它还会补派整章全量。清表即让所有在途看门狗下一轮自行退场。
        b1ResumeLock.withLock { b1ResumeTokens.clear() }
        rollingLaneLock.withLock { rollingLanes.clear() }
        // 页数快照的防抖落盘可能还挂着：关书前补一次，别把这一轮学到的章页数丢掉。
        countsWriteJob?.cancel()
        if (diskPageCountsHash != Long.MIN_VALUE) writePageCountsNow(diskPageCountsHash)
        // 眼前这一章的表刷成最新（见 [pinCurrentChapterTable]）：下次开书磁盘命中，页码当拍就有。
        val shown = if (displayedChapter >= 0) displayedChapter else activeChapter
        if (shown >= 0) pinCurrentChapterTable(shown, currentParamHash())
        // 键域取消要先于 bgScope.cancel()：共享池的任务体不在 bgScope 上，不受后者影响。
        if (sharedScheduler != null) scheduler.cancelMatchingPrefixes(CONTROLLER_KEY_PREFIXES)
        bgScope.cancel()
    }

    /** Priority pool for ALL background layout work (P2.1/P2.4: page prefill, prev-chapter bump,
     *  B1, B2, parse prewarm). P0 spike measurements decided the shape; it is production since P1.
     *
     *  调度重构：宿主注入 [sharedScheduler]（[AppTaskScheduler.scheduler]，应用级单例）后，
     *  排版与系统级字体扫描共处同一队列；未注入（测试/无宿主）时退回每书一池（绑 [bgScope]，
     *  [close] 随 [bgScope] 整池取消，旧语义）。 */
    private val scheduler: TaskScheduler = sharedScheduler ?: TaskScheduler(
        bgScope,
        // 原则 §5：`max(1, cpuCount() - 2)` —— 给 UI / 目标页 / 系统各留一份。**上限 2** 是 P0 真机
        // 实测的拐点（平板：轻页在 0/1/2 并发下耗持平，重页由内容主导、与槽数无关），既然加槽
        // 买不到速度就不必给更多核去抢。
        // 过去这里是 `cpuCount() - 1`，比原则少留一份核，且无任何记录（疑手误）——本机 12 核时被
        // 上限夹住测不出差别，只有 3 核一类的小核设备才暴露（原则给 1 槽、旧代码给 2 槽），
        // 而那恰恰是最该保守的一档。D5 已改回 -2。
        // 翻页永不占槽（调用方线程同步跑），槽数只管后台活。
        maxSlots = shapingSlotsFor(platformCpuCount()),
    )


    /** P4: chapters whose markup + light structure were already prepped, chapter → the paramHash they
     *  were prepped under. A later change of the (paramHash-determining) typography throws it out; the
     *  light tree itself is structure-keyed (cssBundle + useOriginalStyle) so it survives. */
    private val preflightReadiness: MutableMap<Int, Long> = HashMap()

    /** Serializes access to the anchor-temp pagination state ([InProgressPagination] lists + the shared
     *  block shape cache) between the foreground flip thread and the background temp-prefill coroutine
     *  (整改 E). Shape calls are short (one page at a time), so lock contention is minimal.
     *  (C2-P2b: move-time switch to `SynchronizedObject`; `kotlin.concurrent` only resolves in
     *  common source sets, not in this JVM module.) */
    private val tempStateLock = SyncLock()

    /** The in-flight background temp-prefill job for the current anchor stream; a new initiate cancels
     *  it (keeps the prefetch window from stacking across rapid flips). */
    private var tempPrefillJob: kotlinx.coroutines.Job? = null

    /** P10 (§6.2): the (session identity + pointer) key of the last dispatched prefill, so a redundant
     *  re-dispatch for the SAME in-flight target is deduped instead of cancel/restart churn. */
    private var lastPrefillCursor: Long = -1L

    /** Book-level cache directory for persisted pagination tables. Set by [open] from the UI layer.
     *  Null means disk caching is disabled (fallback to legacy full-chapter layout).
     *  (C2-P0: okio Path, was `java.io.File`.) */
    var cacheRoot: Path? = null

    /** Monotonic build number stamped into every persisted pagination table (Android
     *  `versionCode`, desktop `DISK_CACHE_VERSION`; 0 = unset/legacy). Set by the host before
     *  [open]. Tables from other builds miss on read (upgrade invalidates uniformly); stale files
     *  are swept on open. */
    var diskCacheVersion: Int = 0

    /** Shared okio pagination store over [cacheRoot] (C1-2: the retired `File` adapter's logic now
     *  lives in common [PaginationCacheStore]). Null = disk caching disabled. */
    private fun cacheStore(): PaginationCacheStore? =
        cacheRoot?.let { PaginationCacheStore(okio.FileSystem.SYSTEM, it, diskCacheVersion) }

    /** Shared okio chapter-structure store over [cacheRoot] (import-built, open-relinked).
     *  Null = structure persistence disabled (compute every time, as before). */
    private fun structureStore(): ChapterStructureStore? =
        cacheRoot?.let { ChapterStructureStore(okio.FileSystem.SYSTEM, it) }

    /** 当前页快照存储（每本一份、翻页覆盖）。Null = 关（无缓存目录）。 */
    private fun pageSnapshotStore(): PageSnapshotStore? =
        cacheRoot?.let { PageSnapshotStore(okio.FileSystem.SYSTEM, it) }

    /** 开书骨架存储（书脊 + 目录；只随书文件变化）。Null = 关（无缓存目录）。 */
    private fun bookIndexStore(): orilumn.reader.data.epub.EpubBookStore? =
        cacheRoot?.let { orilumn.reader.data.epub.EpubBookStore(okio.FileSystem.SYSTEM, it) }

    /** 本章作者样式哈希：快照键的一部分（书/样式换了，旧页面的行文本与盒子就都不作数）。 */
    private fun chapterCssHash(unit: ChapterUnit): Long? =
        unit.cssBundle?.let { ChapterStructureCodec.cssHashOf(it.cssTexts) }

    // ─────────────────────────────────────────────────────────────────
    // 字体需求清单（章号 → 该章作者 CSS 声明的族/字重/斜体）
    // ─────────────────────────────────────────────────────────────────
    //
    // 整形**之前**要先知道本章要用哪些字体（命中的导入面先进池，否则开屏后追装必见跳变）。
    // 清单只由作者 CSS 决定，落盘后开章直接查表，省掉每章一次重复解析；清单一律由
    // `ChapterFontDemandEntry.cssHash` 逐章校验，不符即当没存过（现算并覆盖）。

    /** 本书记忆中的清单（开书时载入，解析出来的新条目就地补齐并防抖落盘）。 */
    private val fontDemandByChapter = HashMap<Int, ChapterFontDemandEntry>()

    /** 清单落盘防抖：开一本书会逐章补齐，攒一小段再写一次。 */
    private var fontDemandWriteJob: kotlinx.coroutines.Job? = null

    private fun fontDemandStore(): ChapterFontDemandStore? =
        cacheRoot?.let { ChapterFontDemandStore(okio.FileSystem.SYSTEM, it) }

    /**
     * 本章字体需求：先查落盘清单（命中零解析），未命中才解析作者 CSS 并排队补写。
     *
     * @param unit 章单元（`cssBundle` 已绑定；为 null 即无作者样式，需求为空）。
     */
    private fun fontDemandFor(unit: ChapterUnit, bc: BoxChapterLayouter): orilumn.reader.engine.css.FontDemand {
        val bundle = unit.cssBundle ?: return orilumn.reader.engine.css.FontDemand.EMPTY
        val cssHash = ChapterStructureCodec.cssHashOf(bundle.cssTexts)
        val cached = fontDemandByChapter[unit.chapterIndex]
        if (cached != null && cached.cssHash == cssHash) {
            Logger.d(logTag, "FONT-DEMAND hit ch=${unit.chapterIndex} families=${cached.families.size}")
            return cached.toDemand()
        }
        val t0 = platformNowMs()
        val demand = bc.fontDemandFor(bundle)
        val cost = platformNowMs() - t0
        fontDemandByChapter[unit.chapterIndex] = ChapterFontDemandEntry.of(cssHash, demand)
        Logger.d(logTag, "FONT-DEMAND miss ch=${unit.chapterIndex} t=${cost}ms families=${demand.families.size}")
        scheduleFontDemandWrite()
        return demand
    }

    private fun scheduleFontDemandWrite() {
        val store = fontDemandStore() ?: return
        if (bookId < 0) return
        // 在**调用线程**上先拷一份：后台协程不再碰这张表（塑形线程仍在往里补条目，
        // 后台去读就是并发改 HashMap —— 拷出来的这份才是要写盘的快照）。
        val snapshot = HashMap(fontDemandByChapter)
        fontDemandWriteJob?.cancel()
        fontDemandWriteJob = bgScope.launch {
            delay(FONT_DEMAND_WRITE_DEBOUNCE_MS)
            store.write(bookId, diskCacheVersion, snapshot)
        }
    }

    /** 落盘防抖：读者连翻几页只写最后一页（写盘是后台活，但不该为每一格都写一次）。 */
    private var pageSnapshotJob: kotlinx.coroutines.Job? = null

    /** Current book's id, used as the disk-cache namespace. Set by [open]. */
    var bookId: Long = -1L
        private set

    /** Cover href inside the EPUB (if any). Bitmap decoding is the host's job (C2-P1:
     *  cover out of the controller; pagination never depends on it). */
    var coverHref: String? = null
        private set

    val chapterCount: Int get() = book?.spine?.size ?: 0

    // ---- Opening ----

    /**
     * Parses the whole book and restores the start position from saved progress
     * (lays out the target chapter).
     * Cover is NOT decoded here (C2-P1: host concern, see [coverHref]).
     * R13 invariant: ONE controller instance per book — [open] appends chapter skeletons and never
     * clears (no `close()` by design; both hosts construct a fresh controller per book). Reusing an
     * instance for a second book accumulates stale chapters/sessions. Do not call twice.
     * @param bookId Primary key of the book (<0 means don't read progress, start from beginning).
     * @param saved Saved progress record (common [BookReadingState]); null = start from beginning.
     */
    suspend fun open(bookId: Long, saved: orilumn.reader.data.book.BookReadingState?): Boolean {
        clearFlipDir("open-book")
        this.bookId = bookId
        val t0 = platformNowMs()
        // 字体需求清单：一本书读一次（几 KB），开章时按章查表，省掉重复解析作者 CSS。
        fontDemandByChapter.clear()
        fontDemandStore()?.read(bookId, diskCacheVersion)?.let { fontDemandByChapter.putAll(it) }
        // 先试落盘骨架：书脊 + 目录只随书文件变化，命中就不必再读 container.xml / OPF / nav。
        // 判据（版本 / 构建号 / 资源身份）任一不符即 miss，重新解析并覆盖——宁可多解析一次，
        // 不可用旧骨架冒充新书。identity 为 null（非文件型 reader）时两侧都不缓存。
        val indexStore = bookIndexStore()
        val identity = reader.identity()
        val cachedBook = indexStore?.read(bookId, identity, diskCacheVersion)
        val parsedBook = cachedBook ?: (
            runCatching { parser.parse(reader) }
                .onFailure { Logger.e(logTag, "open parse FAIL bookId=$bookId ${it.message}") }
                .getOrNull() ?: return false
            )
        val t1 = platformNowMs()
        if (parsedBook.isEmpty) return false
        book = parsedBook
        // Lazy loading: only build the chapter skeletons, don't parse chapter bodies one by one
        // (bodies are parsed when ensureChapterLayout first needs them)
        for (spine in parsedBook.spine) {
            chapters.add(
                ChapterUnit(
                    chapterIndex = spine.index,
                    hrefName = spine.href.substringAfterLast('/'),
                ),
            )
        }
        val t2 = platformNowMs()
        coverHref = parsedBook.cover
        Logger.w(logTag, "open: chapters=${chapters.size} " +
            "parse=${t1 - t0}ms skeleton=${t2 - t1}ms spine0=${ctx(0)}" +
            (if (cachedBook != null) " src=index" else " src=epub"))
        // 骨架只写一次：解析出来的那一趟在后台落盘（前台不等）；命中时显然不用再写。
        if (cachedBook == null && indexStore != null && identity != null) {
            val parsed = parsedBook
            bgScope.launch {
                runCatching { indexStore.write(bookId, identity, diskCacheVersion, parsed) }
            }
        }
        // R20：开书墙钟基线，落位时随 `jump: open ->` 一起报，方便对齐开书总时长与其中排版那一段。
        openT0 = platformNowMs()
        // Version-stale disk tables (older build) can never hit again — sweep them now on a
        // background scope so the first screen isn't blocked; the running build's tables stay.
        if (bookId >= 0) {
            bgScope.launch { runCatching { cacheStore()?.sweepStale("book_$bookId") } }
        }

        // Restore progress (equal chapter weights)
        if (bookId >= 0 && saved != null) parseLocator(saved.locator)?.let { (ch, char) ->
            startChapter = ch.coerceIn(0, chapterCount - 1)
            startChar = char.coerceAtLeast(0)
            Logger.w(logTag, "open: restore -> startCh=$startChapter char=$startChar ${ctx(startChapter)}")
        }
        // R28：A/B 的可比性第一要件——把本次开书的锚点钉在日志里。
        // 续读位置会因采样途中翻页而漂移，跨锚点的 openT 不可比（R31 自己就走过
        // ch4@3242 → ch3@2075 → ch7@12982 → ch7@12091）。落位时随 `jump: open ->` 一起报，
        // 由 tools/abrun.py 按此分组，不一致直接拒绝出结论。
        openAnchor = "$startChapter@$startChar"
        // The landing chapter is owned by open (locateStart shapes it synchronously, exactly once):
        // background passes (prewarm/B2/preflight) must not duplicate it. Setting this here (it was
        // previously only set on relayout paths) arms their guards from the first screen on.
        activeChapter = startChapter.coerceIn(0, (chapterCount - 1).coerceAtLeast(0))
        // 开书闸门上闩：见字段注释。宿主在 `open()` 之后紧跟的 `requestWholeBookRelayout()` 由此
        // 变成记账而不是执行，直到 [ensureChapterLayout] 把锚页排完才补派。
        openAnchorGate = true
        openGateWantsB1 = false
        openGateWantsB2 = false
        return true
    }

    // ─────────────────────────────────────────────────────────────
    // 首章正文号（封面层判定书首的纯查询原料）
    // ─────────────────────────────────────────────────────────────

    /**
     * 全书第一个「含正文」章的下标（章 0 常是无文本的封面/标题页）。
     *
     * 封面层（ReaderScreen 封面 effect）只用它判定「当前是否落在书首」。
     * 历史上封面层调 `bookStart()`——实现是 `openChapterStart(0)` **导航**：
     * 逐章排版 + 落位首内容章 + `evictFarChapters(keep=首章, window=30)`
     * 逐出窗外远章；续读位在书首 ±30 章之外（真机：长生界 ch=89）时，
     * **正在渲染的续读章被逐出**，而封面丢弃落位结果、阅读面仍按续读位
     * 取页 → `pageLines` 的 `unit.layout` 为 null → 整页空白（白屏），
     * 且渲染路径不自愈（pageLines 只读不 ensure），点按翻页也无法恢复。
     *
     * @Volatile：宿主后台线程写，封面绘制线程读。
     */
    @Volatile
    private var firstContentChapterCache: Int? = null

    /**
     * 纯查询（懒解析）：缓存命中即回；未解析时从章 0 起找首个
     * `hasSignificantText` 的章并缓存。**只经 [ensureMarkup] 解析结构**
     * （幂等、无排版、无落位、无逐出）——比 `bookStart()`（导航：
     * 排版+落位+逐出）轻得多；且懒到封面首次查询才算，不碰
     * 「开书不解析正文」的 preflight 契约（见 OpenBookPreflightProbeTest）。
     * 未找到（全书无正文）回 null。
     */
    suspend fun firstContentChapter(): Int? {
        firstContentChapterCache?.let { return it }
        var ch = 0
        while (ch < chapters.size) {
            val markup = ensureMarkup(ch)
            if (markup != null && markup.hasSignificantText()) {
                firstContentChapterCache = ch
                return ch
            }
            ch++
        }
        return null
    }

    /**
     * 定位串解码单源见 [orilumn.reader.data.read.ReadingLocatorCodec]（写 `chapter:char`，
     * 读兼容 foliate JSON 旧行与 `chapter:char`）。
     */
    private fun parseLocator(locator: String?): Pair<Int, Int>? =
        orilumn.reader.data.read.ReadingLocatorCodec.decode(locator)

    /**
     * **面板读数**（两端宿主同调，`docs/设置模型整改计划.md` §3.3 / §3.4）：打开设置面板时按当前章
     * 量一次原书正文段落的缩进与行高，写进**只读读数** [ReaderSettings.probeIndent] /
     * [ReaderSettings.probeLineSpacing] 供滑块显示（读者没配该项时显示书的真实值；书没写行高
     * 就显示引擎初值 1.5，那本来就是生效值）。
     *
     * **只读**：不参与渲染、不进版式键、不落盘（`SettingsScope.SESSION_ONLY`）；读者一拖滑条才写
     * 成本书的显式值。开书路径零测量 —— 开书探针已退役（§3.7），「每本书先读原书」靠
     * 「没配就不发声明」让级联自己读，不靠量出来再回填。
     *
     * 读数口径是**书自己的**（UA + 作者样式裸级联），不含读者 UI 层与主题层，探测失败原样返回。
     * 段间距读数含 UA 基线（`p { margin: 1em 0 }`）：书没写 margin 时引擎实际用的就是这 1em。
     */
    fun bookStyleReadings(
        chapter: Int,
        bodyPx: Float,
        base: ReaderSettings,
        density: Float = 1f,
    ): ReaderSettings {
        val snap = snapshotTypography(chapter, bodyPx, density) ?: return base
        return base.copy(
            probeIndent = snap.firstLineIndent,
            probeLineSpacing = snap.lineSpacing,
            probeParagraphSpacing = snap.paragraphSpacing,
            probeLetterSpacing = snap.letterSpacing,
        )
    }

    /** 原书排版快照（缩进/行距/段距）：UA+作者裸级联；失败回 null（调用方回退 base）。 */
    private fun snapshotTypography(chapter: Int, bodyPx: Float, density: Float): BookStyleProbe.Snapshot? = runCatching {
        val unit = unitAt(chapter) ?: return@runCatching null
        val markup = unit.markup ?: return@runCatching null
        val sheets = (unit.cssBundle?.cssTexts ?: emptyList()).map { LightCssParser().parse(it) }
        // UA 层必须真加载：段间距读的是「引擎实际生效的段距」，书没写 margin 时生效值来自 UA 的 1em。
        val styles = StyleComputer(bodyPx, uaSheetFromProfile(profile), sheets, pxScale = density).compute(markup)
        BookStyleProbe.snapshot(styles, root = markup)
    }.onFailure { Logger.w(logTag, "bookStyleReadings FAIL ch=$chapter ${it.message} → 回退 base") }
        .getOrNull()

    /** Start location after loading (null when there is no book/no chapter).
 *   Returns (chapter, page): starting from [startChapter], skips chapters with no laid-out
 *   content (cover/empty chapters) going forward, and within the located chapter aligns to the
 *   page for [startChar]; when there is no progress, falls back to the chapter's first page. */
    suspend fun locateStart(): Pair<Int, PageSlice>? {
        if (chapters.isEmpty()) {
            // 无章可落位：闸门不许因此留着（后台预排会永久停摆）。
            releaseOpenGate()
            return null
        }
        try {
            return locateStartLocked()
        } finally {
            // 兜底开闩。正常路径在 [ensureChapterLayout] 末尾已经开过了（那里才是"目标页成形"的
            // 真实时刻）；这里覆盖的是 locateStart 一个章都没排成的路径（无内容/无 markup）。
            // 闸门只该覆盖这一次锚页排版，卡住不释放等于后台预排永久停摆。
            releaseOpenGate()
        }
    }

    /** [locateStart] 的原体，闸门由外层 [locateStart] 统一开/关。 */
    private suspend fun locateStartLocked(): Pair<Int, PageSlice>? {
        var ch = startChapter.coerceIn(0, chapters.size - 1)
        var char = startChar
        while (ch < chapters.size) {
            // R5/S3: open restores the SAVED position — never head-lift a mid-chapter anchor
            // (rotation → new viewport → disk miss used to rebase block≤100 anchors to 0 and the
            // head page got persisted as progress, losing the position permanently).
            val unit = ensureChapterLayout(ch, char, headLift = false)
            val markup = unit?.markup
            if (unit != null && markup != null) {
                val hasContent = markup.hasSignificantText()
                Logger.w(logTag, "locateStart: try ch=$ch ${ctx(unit)} hasContent=$hasContent char=$char")
                if (hasContent) {
                    // Anchor temp streaming: the anchor page is the current temp page.
                    val page = unit.inProgress?.currentSlice
                        ?: pageForChar(unit, char)
                        ?: unit.firstPage()
                    if (page != null) {
                        val p = pageKind(page)
                        Logger.w(logTag, "locateStart: FOUND ch=$ch ${ctx(unit)} page=$p total=${unit.pageSlices.size}")
                        logJumpLanding("open", ch)
                        return ch to page
                    }
                }
            }
            char = 0
            ch++
        }
        Logger.w(logTag, "locateStart: no start page found")
        return null
    }

    /** 开书闸门开闩：把闸门期间记下的整章重排请求按原则次序补派出去。
     *
     *  次序 = 第 2/3 档邻页（在闸门内已派，此刻通常已在池里跑或已跑完）→ 本章全量 B1 → 其他章 B2。
     *  B1 必须在 B2 之前：整书扫描显式跳过锚页章（"B1 owns the active chapter"），闸门把 B1 压下
     *  之后若只补 B2，落位章就永远拿不到自己的磁盘表。
     *
     *  幂等：闸门已开时直接返回，重复调用安全。 */
    private fun releaseOpenGate() {
        if (!openAnchorGate) return
        openAnchorGate = false
        val wantsB1 = openGateWantsB1
        val wantsB2 = openGateWantsB2
        openGateWantsB1 = false
        openGateWantsB2 = false
        if (!wantsB1 && !wantsB2) return
        val bc = layouter as? BoxChapterLayouter
        if (bc == null || viewW <= 0 || viewH <= 0) {
            Logger.w(logTag, "open gate released but no layouter/viewport (b1=$wantsB1 b2=$wantsB2)")
            return
        }
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val paramHash = LayoutParamKey.fromProfile(profile, contentW, contentH).hash()
        Logger.w(logTag, "open gate released ch=$activeChapter b1=$wantsB1 b2=$wantsB2")
        if (wantsB1) {
            val unit = unitAt(activeChapter)
            val ip = unit?.inProgress
            if (unit != null && ip != null) {
                dispatchB1(unit, ip, layoutEpoch, bc, contentW, contentH, paramHash, tempHeadDistancePages(ip))
            }
        }
        if (wantsB2) requestWholeBookRelayout(force = true)
    }

    /** Chapter title text (kept local: only the title probe uses the joined string). */
    private fun collectText(el: orilumn.reader.engine.html.MarkupElement): String {
        return if (el.isText) el.text
        else el.children.joinToString("") { collectText(it) }
    }

    /** Injects the viewport size; returns whether it changed (a change requires relayout). */
    fun setViewport(width: Int, height: Int): Boolean {
        val changed = setViewportLight(width, height)
        // 视口尺寸变化（旋转屏幕/开书/尺寸稳定点）导致版面改变 → 指纹必变，走统一
        // 收口清全书旧指纹磁盘表（守卫恒过；内容盒在边距补偿下恰不变时指纹
        // 不变，旧表仍有效，不删才是对的）。
        if (changed) cleanStaleDiskTables()
        return changed
    }

    /**
     * 视口快速切换（拉窗口 / 拖分栏的**每一帧**）：只换 [viewW]/[viewH]，**不清磁盘表、
     * 不动代际**；返回是否变化。
     *
     * 为什么与 [setViewport] 分开：拖一次窗口是几百帧，每帧尺寸都是一张新指纹 ⇒
     * [cleanStaleDiskTables] 每帧把本书分页表目录整个预扫一遍再逐文件删，而下一帧的尺寸
     * 又让这批表重新作废——净效果是把读者攒下的同书分页表（换字号来回即命中）全删干净。
     * 故拖动期间只换尺寸 + 本章轻刷新（[prepareRelayoutLight]，第一优先级），尺寸稳定后
     * 才由 [cleanStaleDiskTables] 与整书 B2 收口（第三优先级）。
     *
     * 期间旧指纹表按 [LayoutParamKey] 寻址永远命中不到，是纯孤儿，晚删不产生错误页。
     */
    fun setViewportLight(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val changed = width != viewW || height != viewH
        viewW = width
        viewH = height
        return changed
    }

    /**
     * 版式参数（[LayoutParamKey] 指纹）真变时的磁盘分页表清理收口（排版层·上）。
     *
     * 指纹与基线 [cleanBaselineParamHash] 一致 = 空操作：当前参数下的磁盘表
     * 全部保留 —— 非版式提交（夜间模式切换、开书探针回填后与存量参数一致、
     * 原书设置快照无变化）零误删。指纹偏离 = 删除全书**旧指纹**磁盘表（读路径
     * 按当前指纹寻址，旧指纹文件永远命中不到，是纯孤儿）并推进基线；同指纹的
     * 历史表保留（参数回退即命中）。
     *
     * 调用点 = 参数稳定点：面板外提交的收口（平板轻刷新节流循环末尾、桌面
     * 两段式第二段 [orilumn.reader.desktop.DesktopReaderHost.commitRelayout]）；
     * [setViewport] 与 [finalizeRelayoutAll] 同走本入口（视口变化/面板关闭的
     * 指纹必变，守卫恒过）。
     */
    fun cleanStaleDiskTables() {
        val hash = currentParamHash()
        if (hash == cleanBaselineParamHash) return
        cleanBaselineParamHash = hash
        if (bookId < 0) return
        runCatching { cacheStore()?.cleanStaleParams("book_$bookId", hash) }
            .onFailure { Logger.w(logTag, "cleanStaleDiskTables FAIL ${it.message}") }
    }

    /** 字体集合变化（导入/删除/系统字体变更）后的全书磁盘表全清。字体度量不进
     *  [LayoutParamKey]（键只含族名/字重锚点，不含度量）→ [cleanStaleDiskTables]
     *  的指纹清理抓不到同指纹旧表：集合一变，旧表几何全旧 —— 「字体修改必须删
     *  旧表」按全清兜底。B2 随后按当前指纹逐章重写；内存已驻表的章被 skip-fresh
     *  跳过重写，下个会话首次访问经 fullLayout 重塑（纯预热损失，非正确性问题）。 */
    fun cleanAllDiskTables() {
        cleanBaselineParamHash = currentParamHash()
        if (bookId < 0) return
        runCatching { cacheStore()?.cleanBookAll(bookId.toString()) }
            .onFailure { Logger.w(logTag, "cleanAllDiskTables FAIL ${it.message}") }
        // 页面快照与分页表是同一份版式的两种落盘物：字体集合一变（度量变），表清了快照也必须清，
        // 否则下次开书会命中"按旧字形算出来的行几何"（参数键里只有族名，认不出这种变化）。
        runCatching { pageSnapshotStore()?.clear(bookId) }
            .onFailure { Logger.w(logTag, "cleanAllDiskTables snapshot FAIL ${it.message}") }
    }

    /**
     * 宿主字库池当前的环境签名（[orilumn.reader.engine.skia.FontPoolSync] 的 `sig`）。
     * 由宿主每次同步池后回填；进 [PageSnapshot] 的键，字体文件被删/换后旧快照即不认。
     * null = 宿主没提供（测试/未接线），此时快照不做这项校验。
     */
    var fontPoolSignature: String? = null

    // ---- Layout ----

    /** Parses and caches [ChapterUnit.markup] for [index] WITHOUT laying the chapter out, so callers
     *  can inspect content length / significance (or compute a tail anchor) before deciding how the
     *  chapter should be built. Idempotent; no-op when already parsed. */
    private suspend fun ensureMarkup(index: Int): MarkupElement? {
        val unit = unitAt(index) ?: return null
        bindChapterFor(unit)
        if (unit.markup != null) return unit.markup
        layoutMutex.withLock {
            if (unit.markup == null) {
                // Import-built file first: tree + CSS + structure with zero epub text IO.
                // Epub fallback (pre-persist path, media chapters): parse, bind, and let the first
                // prepareLight converge the file via onStructureComputed.
                if (loadPersistedChapter(unit) == null) readChapterInto(unit, index)
            }
        }
        return unit.markup
    }

    /** Epub fallback for [ensureMarkup] (the pre-persist path, unchanged). */
    private fun readChapterInto(unit: ChapterUnit, index: Int) {
        val tree = readChapter(index)
        if (tree != null) unit.ensureMarkup(tree, chapterTitle(tree))
    }

    /**
     * Loads one chapter's import-built file: resolves sheet texts through the book's deduped map,
     * rebuilds the identical `CssBundle`, takes the persisted post-process tree as-is (no XML
     * re-parse, no preprocess), and relinks the structure onto it. Returns the tree on success,
     * null when anything is missing or inconsistent (caller falls back to epub).
     *
     * Atomicity: `apply` fills `unit.structureCache` only on full success and never touches
     * `markup`; `cssBundle`/`ensureMarkup` are assigned only after it succeeds — a null return
     * always leaves the unit pristine for the epub fallback (which asserts first-bind).
     */
    private fun loadPersistedChapter(unit: ChapterUnit): MarkupElement? {
        if (bookId < 0) return null
        val store = structureStore() ?: return null
        val ns = "book_$bookId"
        val file = runCatching { store.readChapter(ns, unit.chapterIndex) }.getOrNull() ?: return null
        val sheets = runCatching { store.readSheets(ns) }.getOrNull() ?: return null
        val texts = file.sheetHashes.map { sheets[it] ?: return null }
        if (texts.size != file.baseHrefs.size) return null
        if (ChapterStructureCodec.cssHashOf(texts) != file.cssHash) return null
        // 视口口径：与视口无关的结构（键 0）任何版心都能用；带视口键的结构只在同一版心下成立
        // （作者 CSS 含视口相关媒体查询时，块/叶集合随版心变化）。版心未知时宁可不复用。
        val storedKey = file.structure.viewportKey
        if (storedKey == 0L) {
            if (ChapterStructurePersist.hasMediaRules(texts)) return null
        } else {
            if (viewW <= 0 || viewH <= 0) return null
            val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
            val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
            if (storedKey != ChapterStructurePersist.viewportKeyOf(contentW, contentH)) return null
        }
        if (!ChapterStructurePersist.apply(file.tree, file.structure, file.cssHash, unit.structureCache)) {
            return null
        }
        unit.cssBundle = CssBundle(texts, file.baseHrefs)
        unit.ensureMarkup(file.tree, chapterTitle(file.tree))
        Logger.w(logTag, "open: ch=${unit.chapterIndex} chapter loaded from persist leaves=${file.structure.leafRefs.size}")
        return file.tree
    }

    /** Binds the layouter's per-chapter href for [unit] before ANY layout/shape work that may touch
     *  `<img>` blocks (see [BoxChapterLayouter.bindChapterContext]).
     *
     *  Why: [readChapter] (the epub-fallback path) was the only caller of `bindChapterContext`, so
     *  a persist-loaded chapter on a cold process left `chapterHref` empty. `intrinsicSizeOf`
     *  then skipped the binary-bounds probe (`chapterHref.isNotBlank()` guard) and the image fell
     *  back to the "no intrinsic info" 300px / 2:1 box, which changed the pagination table
     *  (修复见分支 fix/photo-relayout-height，实测 5页↔4页)。当时主题还把无约束独图拉满宽，
     *  回退后果被放大成「半高图盒」；该主题规则已于 P3a 退役（见 `traditional.css` 编者注），
     *  但探图仍必须发生 —— 否则连自然尺寸都拿不到。
     *
     *  层级：排版层·上 编排——把正在铺排的章（spine href）喂给 排版层·下 的分层引擎上下文，
     *  不改形状/量测语义、不动用户层主题。廉价幂等（字符串赋值），热路径无感。
     */
    private fun bindChapterFor(unit: ChapterUnit) {
        val href = book?.spine?.getOrNull(unit.chapterIndex)?.href ?: return
        (layouter as? BoxChapterLayouter)?.bindChapterContext(href)
    }

    /** Persists a freshly computed chapter structure so the next cold open relinks instead of
     *  re-walking the whole tree (`computeStructure` costs seconds on large chapters). Called with
     *  the cssHash + the viewport the cascade ran at; media-affected chapters are stored tagged
     *  with that viewport and only reused when it matches (see `ChapterStructurePersist`). */
    private fun saveUnitStructure(unit: ChapterUnit, cssHash: Long, contentW: Int, contentH: Int) {
        if (bookId < 0) return
        val store = structureStore() ?: return
        val tree = unit.markup ?: return
        val bundle = unit.cssBundle ?: return
        if (ChapterStructureCodec.cssHashOf(bundle.cssTexts) != cssHash) return
        val viewportKey = ChapterStructurePersist.viewportKeyOf(contentW, contentH)
        val ok = runCatching {
            ImportStructures.persistChapter(store, "book_$bookId", unit.chapterIndex, tree, bundle, unit.structureCache, viewportKey = viewportKey)
        }.onFailure {
            Logger.w(logTag, "structure save FAILED ch=${unit.chapterIndex}: ${it::class.simpleName}: ${it.message}")
        }.getOrDefault(false)
        if (!ok) Logger.w(logTag, "structure save SKIP ch=${unit.chapterIndex} (inconsistent)")
    }

    // ─────────────────────────────────────────────────────────────────
    // 当前页快照（"这一页排好之后长什么样"落盘）
    // ─────────────────────────────────────────────────────────────────
    //
    // 目标：**冷开书不再为已经排过的同一页重付一次塑形**。分页表只记住"页在哪"，页面长什么样
    // （每行画什么字/画在哪/什么字号颜色装饰/盒子背景/插图槽位）此前只活在进程内存里，
    // 每次开书随 `ChapterUnit` 一起被丢弃 ⇒ 同样 21 块、同样参数，每次都要重新塑形一遍。
    //
    // 只存当前页、每本一份、异步覆盖（[schedulePageSnapshot]）：一本书关掉之后排版不会再变
    // （参数/版心/引擎版本任一变，键就变，直接判 miss），所以"上次那页"永远是对的。
    // 真正失效的判据只有一处：`PageSnapshot.matches`。

    /**
     * 目标页快照命中则直接绑定，跳过 `prepareLight` + 塑形。返回 true = 已绑定。
     *
     * 与磁盘表同一套幂等语义：切片列表按分页表铺开（未塑形页 firstLine=-1），
     * 只把**目标页**那条换成快照里的真实行号/块区间——和一次正常增量排版绑出来的形状完全一致。
     */
    private fun restorePageSnapshot(
        unit: ChapterUnit,
        table: ChapterPaginationTable,
        pageIndex: Int,
        paramHash: Long,
        contentWidth: Int,
        contentHeight: Int,
    ): Boolean {
        if (bookId < 0) return false
        val store = pageSnapshotStore() ?: return false
        val t0 = platformNowMs()
        val snap = store.read(bookId) ?: return false
        val cssHash = chapterCssHash(unit) ?: return false
        if (!snap.matches(
                layoutVersion = PaginationCacheCodec.LAYOUT_VERSION,
                appVersion = diskCacheVersion,
                bookId = bookId,
                chapterIndex = unit.chapterIndex,
                pageIndex = pageIndex,
                paramHash = paramHash,
                contentWidth = contentWidth,
                contentHeight = contentHeight,
                cssHash = cssHash,
                fontSig = fontPoolSignature ?: "",
            ) ||
            // 同一页首字：分页表是正典，快照必须锚在同一页上（换表/换页序即不认）。
            table.pages.getOrNull(pageIndex)?.charStart != snap.charStart
        ) {
            Logger.d(logTag, "PAGE-SNAP miss ch=${unit.chapterIndex} page=$pageIndex " +
                "(have ch=${snap.chapterIndex} page=${snap.pageIndex} params=${snap.paramHash == paramHash} " +
                "view=${snap.contentWidth}x${snap.contentHeight} css=${snap.cssHash == cssHash} " +
                "fonts=${snap.fontSig == (fontPoolSignature ?: "")} " +
                "lv=${snap.layoutVersion == PaginationCacheCodec.LAYOUT_VERSION})")
            return false
        }
        val layout = PersistedPageLayout(snap)
        val slices = ArrayList<PageSlice>(table.pages.size)
        table.pages.forEachIndexed { i, rec ->
            slices += if (i == pageIndex) {
                PageSlice(
                    charStart = snap.charStart,
                    charEnd = snap.charEnd,
                    firstLine = snap.firstLine,
                    lastLineExclusive = snap.lastLineExclusive,
                    kind = PageSlice.Kind.TEXT,
                    blockStart = snap.blockStart,
                    blockEndExclusive = snap.blockEndExclusive,
                )
            } else {
                PageSlice(
                    charStart = rec.charStart,
                    charEnd = rec.charEnd,
                    firstLine = -1,
                    lastLineExclusive = -1,
                    kind = PageSlice.Kind.TEXT,
                    blockStart = rec.blockStart,
                    blockEndExclusive = rec.blockEndExclusive,
                )
            }
        }
        unit.bind(layout, slices)
        unit.shapedPageFrom = pageIndex
        unit.shapedPageTo = (pageIndex + 1).coerceAtMost(table.pages.size)
        unit.pageCache[pageIndex] = ChapterUnit.PageProduct(layout, slices, paramHash)
        Logger.w(logTag, "PAGE-SNAP hit ch=${unit.chapterIndex} page=$pageIndex " +
            "lines=${snap.lines.size} t=${platformNowMs() - t0}ms")
        return true
    }

    /**
     * 从**当前绑定的绘制布局**抓一份快照（调用方须持有 [layoutMutex]：布局/切片都在锁内换）。
     *
     * 只抓"确实来自分页表的那一页"：临时表会话（`inProgress != null`）的页切点与磁盘表不同，
     * 存下来会在下次开书冒充"第 N 页"——宁可这一轮不存。
     */
    private fun capturePageSnapshot(
        unit: ChapterUnit,
        charStart: Int,
        contentWidth: Int,
        contentHeight: Int,
    ): PageSnapshot? {
        if (bookId < 0) return null
        if (unit.inProgress != null) return null
        val layout = (unit.tempRenderLayout ?: unit.layout) as? PagedLayout ?: return null
        val table = unit.paginationTable ?: return null
        val pageIndex = unit.pageSlices.indexOfFirst {
            it.firstLine >= 0 && it.lastLineExclusive > it.firstLine &&
                it.charStart <= charStart && charStart < it.charEnd
        }
        if (pageIndex < 0) return null
        val slice = unit.pageSlices[pageIndex]
        // 分页表是正典：页首字与表不符说明这一页不是按表塑出来的（临时页/漂移），不存。
        if (table.pages.getOrNull(pageIndex)?.charStart != slice.charStart) return null
        val cssHash = chapterCssHash(unit) ?: return null
        return buildPageSnapshot(
            layout = layout,
            slice = slice,
            pageIndex = pageIndex,
            bookId = bookId,
            chapterIndex = unit.chapterIndex,
            paramHash = table.paramHash,
            appVersion = diskCacheVersion,
            contentWidth = contentWidth,
            contentHeight = contentHeight,
            cssHash = cssHash,
            fontSig = fontPoolSignature ?: "",
        )
    }

    /**
     * 请求把"包含 [charStart] 的那一页"存成当前页快照。**异步 + 防抖**，读者连翻只写最后一页。
     *
     * 调用点在"这一页真的落位了"之后（开书落位 / 翻页落位）。写盘在 [bgScope] 上，
     * 不占前台；失败只记 w（快照是加速手段，永远不该让开书失败）。
     */
    fun schedulePageSnapshot(chapter: Int, charStart: Int) {
        if (bookId < 0 || cacheRoot == null) return
        pageSnapshotJob?.cancel()
        pageSnapshotJob = bgScope.launch {
            delay(PAGE_SNAPSHOT_DEBOUNCE_MS)
            val unit = unitAt(chapter) ?: return@launch
            if (viewW <= 0 || viewH <= 0) return@launch
            val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
            val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
            val snap = layoutMutex.withLock { capturePageSnapshot(unit, charStart, contentWidth, contentHeight) }
                ?: return@launch
            val t0 = platformNowMs()
            pageSnapshotStore()?.write(bookId, snap)
            Logger.w(logTag, "PAGE-SNAP write ch=$chapter page=${snap.pageIndex} " +
                "lines=${snap.lines.size} t=${platformNowMs() - t0}ms")
        }
    }

    /**
     * 整章文本投影（长按选区用）**移出开书关键路径**：先把目标页画出来，绑定完成后在后台算完回填。
     *
     * 投影本身要跑一遍整章级联（实测大章 2444 元素 × ≈1ms ≈ 5s，占当时开书 6s 的绝大部分），
     * 但目标页渲染根本不需要它——它只服务长按选区的整章切句。开书该等的是排版，不是它。
     *
     * 后台**自建**一个轻 prepare（结构已绑定，组装百毫秒级）再投影：临时表会话持有的那个
     * `LightPrepare` 带着自己的懒级联缓存、且被塑形线程共用，不能跨线程拿来算。
     * 回填前用 [ChapterUnit.layoutEpoch] 确认版式没变过；期间读者若长按，[chapterText] 的
     * 同步兜底照旧现算（行为与改造前一致）。
     */
    private fun scheduleChapterTextProjection(
        unit: ChapterUnit,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
    ) {
        if (unit.chapterTextCache != null || unit.textProjectionInFlight) return
        val epoch = unit.layoutEpoch
        unit.textProjectionInFlight = true
        bgScope.launch {
            try {
                // 先让锚页/首批预填把 CPU 拿走：这一笔是"读者接下来才可能用到"的工作。
                kotlinx.coroutines.delay(1200)
                val markup = unit.markup ?: return@launch
                val bundle = unit.cssBundle
                val bc = layouter as? BoxChapterLayouter ?: return@launch
                val t0 = platformNowMs()
                val light = bc.prepareLight(markup, bundle, profile, contentW, unit.structureCache, contentH)
                val projection = light.chapterTextProjection()
                if (unit.layoutEpoch == epoch && unit.chapterTextCache == null) unit.putTextProjection(projection)
                Logger.d(logTag, "text projection bg ch=${unit.chapterIndex} t=${platformNowMs() - t0}ms chars=${projection.text.length}")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                Logger.w(logTag, "text projection failed ch=${unit.chapterIndex}: ${t::class.simpleName}")
            } finally {
                unit.textProjectionInFlight = false
            }
        }
    }

    /** Ensures a chapter is laid out (lazy): on first need, parses the body (ensureMarkup) then
     * lays it out.
     * @param targetChar Optional character offset within the chapter that we want to land on. Used
     *   by the incremental (disk-cache hit) path to shape only the page containing this offset
     *   instead of shaping the entire chapter. */
    suspend fun ensureChapterLayout(index: Int, targetChar: Int = 0, headLift: Boolean = true): ChapterUnit? {
        val unit = unitAt(index) ?: return null
        bindChapterFor(unit)
        if (unit.markup == null) ensureMarkup(index)
        // 整形前先备字体：本章需求命中的导入面进池（锁外 IO），池变则旧版式作废重排，
        // 首绘即对，开屏后不再跳变。
        if (unit.markup != null) {
            val bc = layouter as? BoxChapterLayouter
            if (bc != null && onDemandFonts(fontDemandFor(unit, bc))) {
                unit.invalidateLayout()
            }
            // P2-b: 书内字体先行入池（整形前；池变即旧版式作废，同用户字库语义）。
            if (bc != null && onBookFonts(bookFontsFor(index))) {
                unit.invalidateLayout()
            }
        }
        layoutMutex.withLock {
            if (viewW <= 0 || viewH <= 0) return unit
            if (unit.markup != null) {
                // R6: never consume a table bound under different params (a viewport change
                // pre-R5 is the only path that can leave one behind). Live temp sessions own
                // their lifecycle via profileSnapshot and are never killed here.
                if (unit.inProgress == null) {
                    unit.invalidateForParam(currentParamHash())
                }
                if (unit.laidOut && unit.inProgress != null) {
                    // Anchor temp streaming active — the current temp page is rendered from its own
                    // whole-block layout; further shaping happens on flip via [findAdjacentPage].
                } else if (unit.laidOut && unit.paginationTable != null && unit.layout != null) {
                    // Already laid out via disk-hit incremental path. Re-shape if the new targetChar
                    // falls outside the range of pages currently shaped.
                    ensurePageRangeShaped(unit, targetChar)
                } else if (!unit.laidOut) {
                    buildLayout(unit, targetChar, headLift)
                }
            }
        }
        // R3 (S7): disk-path landing warms neighbor blocks in the background (temp path owns
        // its own prefill; direction follows the last flip, defaulting forward).
        if (viewW > 0 && viewH > 0 && unit.inProgress == null) {
            val t = unit.paginationTable
            if (t != null && unit.laidOut) {
                scheduleWindowPrefill(unit, pageIndexForChar(t.pages, targetChar), flipDir)
            }
        }
        // 开书闸门开闩点：**一次前台落位排版完成** = 目标页已经成形、可以画了。此刻之前不许有整章
        // 重排（见字段注释），此刻之后按原则次序补派。放在邻页派发**之后**，第 2/3 档就一定排在
        // B1/B2 前面（总则：目标页 > 邻页 > 本章全量 > 其他章）。
        // 幂等：非开书路径进来时闸门已开，这里什么也不做。
        //
        // 注意：这里**不能**顺手驱逐远章 —— 调用方可能是跨章 sweep（pageAtFraction），
        // active 还是旧章，驱逐会把刚建好的目标章当场清掉（markup=null），调用方判空跳过，
        // 远扫全灭、只能落在窗口内。驱逐由落位成功后显式 evictFarChapters(keep=landed) 做。
        releaseOpenGate()
        return unit
    }

    /** Checks whether the page containing [targetChar] (plus a small look-ahead) has been shaped
     *  in the current incremental layout. If not, re-runs [BoxChapterLayouter.incrementalLayoutForPage]
     *  starting from the closest page index. */
    private fun ensurePageRangeShaped(unit: ChapterUnit, targetChar: Int) {
        bindChapterFor(unit)
        // R6: same guard for direct callers (cross-chapter stub reshape). A mismatch invalidates
        // first, so the `?: return` below trips on the nulled table instead of shaping from it.
        if (unit.inProgress == null) {
            unit.invalidateForParam(currentParamHash())
        }
        val table = unit.paginationTable ?: return
        val boxLayouter = layouter as? BoxChapterLayouter ?: return
        val slices = unit.pageSlices
        if (slices.isEmpty()) return

        val targetPage = pageIndexForChar(table.pages, targetChar)

        // Stable windowing: if the target page is already inside the currently-shaped window (and has a
        // valid line index), keep it EXACTLY as-is — do NOT re-window/re-derive. Incremental pagination
        // points must stay fixed while flipping within a window; only crossing the window edge re-windows.
        if (targetPage in unit.shapedPageFrom until unit.shapedPageTo) {
            val cur = unit.pageSlices.getOrNull(targetPage)
            if (cur != null && cur.firstLine >= 0 && cur.lastLineExclusive > cur.firstLine) return
        }

        // R17: page-product cache hit — bind with zero shaping/assembly. The product is
        // deterministic given table + params (same edge-snap argument as the seam canary below),
        // so binding it equals a fresh shape; the hash check rejects stale param cycles.
        val tEnter = platformNowMs()
        val cachedHit = unit.pageCache[targetPage]
        if (cachedHit != null && cachedHit.paramHash == table.paramHash) {
            unit.bind(cachedHit.layout, cachedHit.slices)
            unit.shapedPageFrom = targetPage
            unit.shapedPageTo = (targetPage + 1).coerceAtMost(table.pages.size)
            Logger.w(logTag, "ensurePageRangeShaped ${ctx(unit)} page=$targetPage pagecache-hit t=${platformNowMs() - tEnter}ms")
            return
        }

        // Capture the old window's edges (they get replaced below) for the seam canary.
        val oldFrom = unit.shapedPageFrom
        val oldTo = unit.shapedPageTo
        val oldLast = unit.pageSlices.getOrNull(oldTo - 1)
        val oldFirst = unit.pageSlices.getOrNull(oldFrom)

        // Need to (re)shape this page and a few look-ahead pages.
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val t0 = platformNowMs()
        val prepare = boxLayouter.prepareLight(unit.markup!!, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
        // 整章文本投影转后台：翻页路径更不能为长按选区这一笔（大章 ~5s）让读者等。
        scheduleChapterTextProjection(unit, profile, contentWidth, contentHeight)
        val tPrep = platformNowMs()
        val pagesToShape = 1
        val newProduct = boxLayouter.incrementalLayoutForPage(
            prepare = prepare,
            profile = profile,
            contentW = contentWidth,
            contentH = contentHeight,
            table = table,
            targetPage = targetPage,
            pagesToShape = pagesToShape,
            cache = unitShapeCache(unit),
            prefillL2 = windowPrefillShapesFor(unit, table),
        )
        unit.bind(newProduct.layout, newProduct.slices)
        unit.shapedPageFrom = targetPage
        unit.shapedPageTo = (targetPage + pagesToShape).coerceAtMost(table.pages.size)
        // R17: store the assembled product + trim to the pointer neighborhood (bounded memory).
        unit.pageCache[targetPage] =
            ChapterUnit.PageProduct(newProduct.layout, newProduct.slices, table.paramHash)
        unit.pageCache.keys.toList().forEach { p ->
            val stale = unit.pageCache[p]?.paramHash != table.paramHash
            if (stale || p < targetPage - 2 || p > targetPage + 2) unit.pageCache.remove(p)
        }
        Logger.w(logTag, "ensurePageRangeShaped ${ctx(unit)} page=$targetPage t=${platformNowMs() - t0}ms prep=${tPrep - t0}ms shape=${platformNowMs() - tPrep}ms")

        // Step 2 seam canary: when the new window directly abuts the old one, the shared boundary must
        // agree exactly (a gap/overlap between windows is the "missing lines" symptom). Both sides are
        // pinned to the disk table by incrementalLayoutForPage's edge snap, so any nonzero seam here is
        // a regression in the shared-boundary rule, not drift to be tuned.
        val newLastIdx = (targetPage + pagesToShape).coerceAtMost(table.pages.size) - 1
        if (targetPage == oldTo && oldLast != null && oldLast.firstLine >= 0) {
            val newFirst = newProduct.slices.getOrNull(targetPage)
            if (newFirst != null && newFirst.firstLine >= 0) {
                val seam = newFirst.charStart - oldLast.charEnd
                if (seam != 0) Logger.w(logTag, "ensurePageRangeShaped SEAM-FWD ${ctx(unit)} " +
                    "p${oldTo - 1}.charEnd=${oldLast.charEnd} p$targetPage.charStart=${newFirst.charStart} seam=$seam")
            }
        }
        if (targetPage + pagesToShape == oldFrom && oldFirst != null && oldFirst.firstLine >= 0) {
            val newLast = newProduct.slices.getOrNull(newLastIdx)
            if (newLast != null && newLast.firstLine >= 0) {
                val seam = newLast.charEnd - oldFirst.charStart
                if (seam != 0) Logger.w(logTag, "ensurePageRangeShaped SEAM-BWD ${ctx(unit)} " +
                    "p$newLastIdx.charEnd=${newLast.charEnd} p$oldFrom.charStart=${oldFirst.charStart} seam=$seam")
            }
        }
    }

    private fun buildLayout(unit: ChapterUnit, targetChar: Int = 0, headLift: Boolean = true) {
        // ensureChapterLayout 已在锁外备好 markup；到这里为 null 即锁间隙状态矛盾，抛而不吞。
        val markup = checkNotNull(unit.markup) { "buildLayout without markup ch=${unit.chapterIndex}" }
        val t0 = platformNowMs()
        runCatching {
            val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
            val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
            val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()

            // 磁盘命中判据（总则 1：命中磁盘表 → 同步渲染目标页）：先读缓存文件，再退回**内存里已
            // 绑定的本章表**——同一 paramHash 的表与文件同源（同一产者），有它就等于命中：不必回读
            // 缓存，更不该退回临时表。覆盖两种文件读不到的情形：缓存写入失败、bookId<0 / 无 store
            //（那时内存表就是唯一副本）。write-only 的 prewarm/B2 路径也正靠它「直接晋级整表」。
            val cache = cacheStore()
            val cacheFile = if (cache != null && bookId >= 0) {
                cache.file("book_$bookId", unit.chapterIndex, paramHash)
            } else null
            val cached = (if (cache != null) cacheFile?.let { cache.read(it) } else null)
                ?: unit.paginationTable?.takeIf { it.paramHash == paramHash }

            if (cached != null) {
                // PaginationMode.LINE_DISK: the disk CFI table holds the authoritative line-level page
                // boundaries; foreground only shapes the target page's block range, never re-paginates.
                unit.bindPaginationTable(cached)
                val boxLayouter = layouter as? BoxChapterLayouter
                val startPage = pageIndexForChar(cached.pages, targetChar)
                if (boxLayouter != null) {
                    // 先试"上次那一页"的绘制模型：命中就一步到位（不建 prepare、不塑形）。
                    // 用 runCatching 兜住：快照是加速手段，它自己出任何岔子都不许把开书拖下水。
                    val snapHit = runCatching {
                        restorePageSnapshot(unit, cached, startPage, paramHash, contentWidth, contentHeight)
                    }.getOrElse {
                        Logger.w(logTag, "PAGE-SNAP restore FAIL ch=${unit.chapterIndex}: ${it::class.simpleName}")
                        false
                    }
                    if (snapHit) {
                        Logger.w(logTag, "layout ${ctx(unit)} PAGE-SNAPSHOT page=$startPage t=${platformNowMs() - t0}ms")
                        // 选区用的整章文本投影照旧转后台：快照只解决"画出来"，
                        // 长按选区的整章切句不在这条路上。
                        scheduleChapterTextProjection(unit, profile, contentWidth, contentHeight)
                        return@runCatching
                    }
                    // Light prepare only (box tree, no shaping) — the table tells us which blocks to shape.
                    val prep = boxLayouter.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
                    val sp = platformNowMs()
                    val product = boxLayouter.incrementalLayoutForPage(
                        prepare = prep,
                        profile = profile,
                        contentW = contentWidth,
                        contentH = contentHeight,
                        table = cached,
                        targetPage = startPage,
                        pagesToShape = 1,
                        cache = unitShapeCache(unit),
                        prefillL2 = windowPrefillShapesFor(unit, cached),
                        // 开书路径专属的锚页预热，实测净亏，默认 0（数据见 AbSwitch.warmupBlocks 的 KDoc）。
                        // R28 搬到运行期开关：顺序装机 A/B 无法排除慢时段整段落在一侧，
                        // 且本开关有**已知答案**（+50ms），正好用来先验证量法本身。
                        warmupBlocks = AbSwitch.warmupBlocks,
                    )
                    Logger.w(logTag, "DISK-HIT shape t=${platformNowMs() - sp}ms shapedPages=${product.slices.count { it.firstLine >= 0 }} blocks=[${product.slices[startPage].blockStart},${product.slices[startPage].blockEndExclusive}) target=$startPage")
                    unit.bind(product.layout, product.slices)
                    unit.shapedPageFrom = startPage
                    // R9: honest window — only 1 page is shaped here (pagesToShape=1 above).
                    unit.shapedPageTo = (startPage + 1).coerceAtMost(cached.pages.size)
                    Logger.w(logTag, "layout ${ctx(unit)} DISK-HIT+INCREMENTAL page=$startPage t=${platformNowMs() - t0}ms")
                    // 选区用的整章文本投影转后台（见 scheduleChapterTextProjection）：目标页已绑定，
                    // 开书不必再等这一笔整章级联。
                    scheduleChapterTextProjection(unit, profile, contentWidth, contentHeight)
                } else {
                    // Legacy layouter: still full layout.
                    val product = layouter.layout(markup, unit.cssBundle, profile, contentWidth, contentHeight)
                        ?: error("layout failed")
                    unit.bindFull(product.layout, product.slices)
                    Logger.w(logTag, "layout ${ctx(unit)} DISK-HIT+LEGACY pages=${cached.totalPages} t=${platformNowMs() - t0}ms")
                }
            } else {
                // Disk miss. Try the anchor-based incremental path first for large chapters, so the
                // reader sees the target page immediately instead of blocking on a full-chapter shape.
                val bc = layouter as? BoxChapterLayouter
                if (bc != null) {
                    // PaginationMode.BLOCK_TEMP: decide large/small via a cheap light prepare (no
                    // shaping). A large chapter's foreground threads only the current page's blocks
                    // through the temp table — never a full-chapter line-level layout.
                    val light = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
                    // 选区用的整章文本投影转后台（见 scheduleChapterTextProjection）：此刻最要紧的是
                    // 锚页流，整章级联（大章 ~5s）不该挡在这里。临时表在途用的是 `light` 自己的懒缓存，
                    // 后台另建一个轻 prepare 算，互不共享。
                    scheduleChapterTextProjection(unit, profile, contentWidth, contentHeight)
                    if (light.totalBlocks > SMALL_CHAPTER_BLOCKS) {
                        // anchorChar stays at the user's actual position — startAnchorStream anchors the
                        // temp stream there. A head-proximity lift (anchor to block 0) is designed but
                        // deferred (P8/§1); the backward window derives from the anchor page's own
                        // block/line (P10 head-edge), so correctness never depends on it. The lift only
                        // affects how closely the leading temp pages match the eventual canonical table.
                        val anchorBlock = light.blockIndexForChar(targetChar).coerceIn(0, light.totalBlocks - 1)
                        startAnchorStream(unit, targetChar, light, bc, paramHash, contentWidth, contentHeight, headLift = headLift)
                        Logger.w(logTag, "layout ${ctx(unit)} LARGE-ANCHOR anchorBlock=" +
                            "$anchorBlock blocks=${light.totalBlocks} " +
                            "t=${platformNowMs() - t0}ms")
                        return
                    }
                }

                // PaginationMode.LINE_DISK: small chapters line-level full-layout in foreground, then persist
                // straight to the disk CFI table.
                // Full layout, then persist the pagination table (small chapter / legacy layouter).
                val product = layouter.layout(markup, unit.cssBundle, profile, contentWidth, contentHeight)
                    ?: error("layout failed")
                val layout = product.layout
                val slices = product.slices
                unit.bindFull(layout, slices)
                // 空章（纯封面页/全空白）不落表，但页数是已知的 0 —— 全书页码要靠它凑齐。
                if (slices.isEmpty()) noteZeroPageChapter(unit.chapterIndex, paramHash)

                // Persist pagination table (with block ranges backfilled by BoxChapterLayouter).
                if (cache != null && cacheFile != null && slices.isNotEmpty() && slices[0].blockStart >= 0) {
                    val totalChars = slices.last().charEnd
                    val table = ChapterPaginationTable.fromSlices(
                        chapterIndex = unit.chapterIndex,
                        paramHash = paramHash,
                        slices = slices,
                        totalBlocks = slices.maxOf { it.blockEndExclusive },
                        totalChars = totalChars,
                    )
                    runCatching { cache.write(table, cacheFile) }
                        .onFailure { Logger.e(logTag, "cache write FAIL ch=${unit.chapterIndex} ${it.message}") }
                    unit.bindPaginationTable(table)
                    Logger.w(logTag, "layout ${ctx(unit)} DISK-WRITE pages=${table.totalPages} totalChars=$totalChars")
                    // 全书页码：本章页数从「未知」变已知（可能凑齐全书），记进快照并推一拍。
                    notePageCountKnown(unit.chapterIndex, paramHash, table.totalPages)
                }

                val totalH = if (layout.lineCount > 0) layout.getLineBottom(layout.lineCount - 1) else 0
                Logger.w(logTag, "layout ${ctx(unit)} view=${viewW}x$viewH lines=${layout.lineCount} totalH=$totalH pages=${slices.size} " +
                    "t=${platformNowMs() - t0}ms")
                Logger.d(logTag, "layout ${ctx(unit)} lines=${layout.lineCount} pages=${slices.size} t=${platformNowMs() - t0}ms")
            }
        }.onFailure {
            Logger.e(logTag, "layout FAIL ch=${unit.chapterIndex} ${ctx(unit)} ${it.message}", it)
            Logger.e(logTag, "layout FAIL ch=${unit.chapterIndex} ${it.message}", it)
            // 失败不再伪装空章节（bindSafeEmpty 已删）：抛，让调用链按失败处理。
            // 翻页线程上即崩（开发期暴露）；重排路径各自 runCatching 记 e 后丢弃本轮。
            throw it
        }
        // 版式已就绪（小章**全章排完** / 磁盘命中目标窗）→ 立即让用户层补栅方向侧那一张。
        // 大章磁盘未命中已在 [startAnchorStream] 处 `return`（临时表路径自带逐页信号），不到这里。
        notifyPrefillReady(unit.chapterIndex)
    }

    /** Chapters at or below this many blocks are laid out fully (small chapters are fast enough and a
 *  cleaner pagination than block-cut). Larger chapters use anchor-based incremental streaming. */
private val SMALL_CHAPTER_BLOCKS = 120

/**
 * 整书 B2 派发前的静默窗口（ms，第三优先级）。
 *
 * 见 [BookDocumentController.requestWholeBookRelayout]：请求只登记，静默这么久才真派。
 * 取值只需大于「一次拖窗/连点调参的抖动间隔」，又不能让开书后的整书预热慢到读者察觉
 * （翻到下一章前通常还有几百 ms）——1.2 s 是两者的折中。
 */
private val B2_DISPATCH_QUIET_MS = 1_200L

/**
 * 整书 B2 一趟最多补派几轮缺章（见 [BookDocumentController.requestWholeBookRelayout]）。
 *
 * 缺章的唯一成因是「整章任务被前台抢占后没人再派」：正常一趟扫完不该有缺章，所以第 2 轮起
 * 只在异常路径上跑。3 轮足够覆盖「补派途中又被翻页砍掉」的连续抢占，又不至于在真正的 bug
 * （每轮都失败）上无限打转。
 */
private val B2_RETRY_ROUNDS = 3

/**
 * 每轮补派前的冷却（ms）。
 *
 * 整章任务被一次跳章/翻页整批清空时（真机一次 jump 清掉 526 章），立刻重派只会马上再被清一次，
 * 白烧 CPU 还和前台抢锁。等读者停手、池子真空这么久之后再补，才补得上。
 */
private val B2_RETRY_COOLDOWN_MS = 1_500L

/**
 * 当前页快照的写盘防抖（ms）：落位后静置这么久才写，读者连翻只写最后那一页。
 *
 * 取值只需大于"一次翻页动画 + 落位"的间隔（真机 300–600ms），又不能大到"翻一页就切走应用
 * 还没写下去"。0 也可用（每次落位都写），但翻页连击时会做几十次几十 KB 的无用写。
 */
private val PAGE_SNAPSHOT_DEBOUNCE_MS = 700L

/**
 * 字体需求清单的落盘防抖（ms）。开一本书会逐章补齐条目，攒 1.2s 再写一次；
 * 清单是纯缓存，进程被杀丢几条只是下次多解析一次，不影响正确性。
 */
private val FONT_DEMAND_WRITE_DEBOUNCE_MS = 1200L

/**
 * Header-lift threshold. When a large chapter's anchor block lies within the first
 * [HEAD_START_BLOCK_LIMIT] blocks (~5 pages at ~20 blocks/page), the anchor is *lifted to the
 * chapter head* (anchorChar = 0) and the temp table is streamed from block 0. This is nearly as fast
 * as a mid-chapter line-cut (only the leading ~5 pages are shaped) and yields page breaks identical to
 * the eventual canonical/disk table, avoiding the divergence a line-cut anchor introduces. Beyond the
 * threshold the anchor keeps a mid-chapter line-cut so deep jumps never shape the whole chapter.
 */
internal val HEAD_START_BLOCK_LIMIT = 100

/** P10 (§6): temp-window depth ±N around the current page — default 1 ("prev + cur + next"). A flip
 *  slides the window: the newly exposed side is backfilled by background pre-shape at depth 1 (down
 *  from the old `FLIP_AHEAD_PAGES = 4`), `tempNav`'s on-demand shaping covers when pre-shape hasn't
 *  landed, and the far end is evicted so the session never outgrows ~3 pages (+ the anchor torn pair).
 *  Window depth symmetric ⇒ forward/backward flip latency risk symmetric. */
private val TEMP_WINDOW_DEPTH = 1

/** 章首前向正典链采样（[seedHeadRootedEdges]）的页数上限：真书单章页数在数十量级，取远大于其的护栏，
 *  只防畸形内容（块数极多而逐页不推进）把前台线程钉死。 */
private val TEMP_HEAD_ROOTED_MAX_PAGES = 4096


// ─────────────────────────────────────────────────────────────────
// Anchor-based incremental layout (large-chapter, disk-miss)
// ─────────────────────────────────────────────────────────────────

/**
 * Starts anchor-based streaming for a large chapter whose disk table is missing. Immediately shows a
 * line-anchored anchor page (starting at the line containing [anchorChar], so that character stays on
 * the page's first line); forward and backward pages are pre-shaped in the background (整改 E), and the
 * canonical (chapter-head) full layout is computed in the background and persisted to disk.
 *
 * @param anchorChar any char whose line becomes the pagination baseline.
 */
private fun startAnchorStream(
    unit: ChapterUnit,
    anchorChar: Int,
    prepare: LightPrepare,
    bc: BoxChapterLayouter,
    paramHash: Long,
    contentW: Int,
    contentH: Int,
    /** When false the anchor is ALWAYS respected as-is — the relayout path requires this so the
     *  reflow preserves the reader's current position. Only [openChapterLayout] sets it true so a
     *  near-head anchor is lifted to the chapter head (P8/R2). */
    headLift: Boolean = true,
) {
    bindChapterFor(unit)
    val last = (prepare.totalBlocks - 1).coerceAtLeast(0)
    val anchor = prepare.blockIndexForChar(anchorChar.coerceAtLeast(0)).coerceIn(0, last)
    // P8 (R2) head lift: the temp table is anchored at a LINE-CUT mid-chapter page by default, which
    // diverges from the canonical (chapter-head) table wherever the reader is near the chapter head.
    // When the requested anchor lies within the chapter's first HEAD_START_BLOCK_LIMIT blocks (≈ the
    // first ~5 pages), re-anchor at the TRUE chapter head (block 0 / char 0) so the temp pagination and
    // the background canonical share the same source — save→relocate and head-area flips then cannot
    // diverge. Cost: a near-head reader may retreat up to HEAD_START_BLOCK_LIMIT blocks (logged).
    val headLift = headLift && anchor > 0 && anchor <= HEAD_START_BLOCK_LIMIT
    if (headLift) {
        Logger.w(logTag, "head lift ch=${unit.chapterIndex} anchorBlock=$anchor <= $HEAD_START_BLOCK_LIMIT → re-anchor at chapter head (retreat ≤ $HEAD_START_BLOCK_LIMIT blocks)")
    }
    val anchorBlock = if (headLift) 0 else anchor
    val anchorCharAt = if (headLift) 0 else anchorChar.coerceAtLeast(0)
    // P9 (R5): freeze the profile at session birth — the live `profile` is swapped by a typography
    // tune outside tempStateLock, so temp shaping must read THIS snapshot uniformly. Immutable data
    // class, so a reference IS the snapshot (no copy needed).
    val snapshot = profile
    val ip = InProgressPagination(unit.chapterIndex, paramHash, anchorBlock, contentW, contentH, snapshot, prepare)
    // This anchor stream is a NEW layout-parameter cycle. The per-block shape cache is keyed only by
    // leaf index, so a reused map would serve stale shapes shaped under the previous 行距/字号 —
    // Line-height changes (行距) would then never reflow live (paragraph margins don't live in the
    // shape and therefore did update, which is exactly the reported bug). Drop it so every block is
    // re-shaped with the new profile; the caller (incrementalLayoutForPage) recreates it on demand.
    unit.blockShapeCache = null
    val cache = unitShapeCache(unit)
    ip.shapes = cache

    // 修复 R1（出生窗）：挂起出生信号，锚点页仍在锁外成形（同步段 350–550ms，纯 CPU 不可中断），
    // 成形完成后在 tempStateLock 内整体原子提交（bindInProgress + 填窗 + setCurrentTempPage）。
    // 期间到达的翻页在 findAdjacentPage await 出生信号后重读状态，绝不对半初始化窗口 nav/按需成形。
    val birth = CompletableDeferred<Unit>()
    unit.tempBirth = birth
    // P2: this anchor stream is a new layout-parameter cycle; its B1 carries the epoch so a canonical
    // coroutine queued behind newer rounds skips the whole segment instead of shaping stale params.
    val epoch = layoutEpoch
    try {
        // Build the line-anchored anchor page first (locals, still outside any lock — the flip thread
        // is blocked on the birth signal, so this window can no longer be raced into).
        // 尾锚直达倒包：锚落在末块时前向灌注定撞顶（至多覆盖末块余行），跳过前向试探，
        // 直接倒包——省一次整块塑形+组装（旧路径先前向后倒包，白做一次）。
        // 非末块锚仍先前向灌：撞顶不满才倒包；灌满则保留前向页（与正向尾余页同源）。
        // 失败一律可观测 + 回退前向锚（原行为），出生永不因此落空：
        //   - total<=0 畸形章（跨章已跳过无正文章，理论不可达）：记 w 跳过；
        //   - 倒包返回 null：记 w 回退；
        //   - 倒包抛异常（tempShape/rebuildLocalLines 遇畸形内容）：记 e 回退——
        //     不包的话异常穿透 birth 直达 buildLayout 的 catch（ERROR + 空白章），
        //     回退接不住真正的失败模式。
        val tailAnchored = anchorBlock >= prepare.totalBlocks - 1
        val anchorFwd = if (!tailAnchored) {
            bc.shapeAnchorPageForward(prepare, snapshot, contentW, contentH, anchorCharAt, cache)
        } else null
        // 尾锚倒包：前向灌撞到章末（无后继块）时，锚点页改由 shapeTempPageBackward 从文末
        // 倒包一整页——与 -1/-2/-3… 链同函数、同页界制式（backward-packed island）。
        // 前向灌只在"灌满整页"这一种情况下与正向尾余页同源，而撞顶不满是常态（锚在文末
        // 最后一行时恒为 1 行）；无磁盘表时本就没有 canonical 可兼容，倒包恒为满屏，
        // 且后向链从此页起向后铺（shapeNextBackward 以 Fwd(0) 为头缘），全链统一。
        // shapeTempPageBackward 失败则回退前向锚（原行为）。
        // C2-P2b-3: `withLock` 非内联，块里不能裸 `return` —— 超期标记外置，语义不变。
        // 开发期 fail-fast：倒包按构造必成（与前向同函数、同批块，只是遍历方向反了；
        // 前向能塑的块倒包必能塑，没有倒包特有的失败理由）——一旦失败必是底层回归，
        // 必须抛出来，不许静默回退藏掉。total<=0、返回 null、抛异常三条路都不吞：
        // null 在此抛，异常自然上抛（buildLayout 记 ERROR）。
        val tailPage: TempPage? = if (tailAnchored || (anchorFwd != null && anchorFwd.nextBlock >= prepare.totalBlocks)) {
            bc.shapeTempPageBackward(prepare, snapshot, contentW, contentH, prepare.totalBlocks, -1, cache)
                ?.also { page -> Logger.w(logTag, "anchor TAIL-BACKWARD ch=${unit.chapterIndex} blocks=[${page.slice.blockStart},${page.slice.blockEndExclusive}) chars=[${page.slice.charStart},${page.slice.charEnd})") }
                ?: error("anchor TAIL-BACKWARD null ch=${unit.chapterIndex} totalBlocks=${prepare.totalBlocks} anchorChar=$anchorCharAt")
        } else null
        // 尾包成功不再碰前向；非尾锚未撞顶走前向页。anchorFwd 为 null 只可能与 tailPage
        // 并存（尾锚直达必走上一分支）——到这里为 null 即状态矛盾，直接抛，不补塑遮掩。
        val anchorPage: TempPage
        val shapedFwdTo: Int
        val fwdFromLine: Int
        if (tailPage != null) {
            anchorPage = tailPage
            shapedFwdTo = prepare.totalBlocks
            fwdFromLine = 0
        } else {
            val fwd = checkNotNull(anchorFwd) { "anchorFwd null without tailPage ch=${unit.chapterIndex}" }
            anchorPage = fwd.page
            shapedFwdTo = fwd.nextBlock
            fwdFromLine = fwd.nextLine
        }
        val anchorBlockStart = anchorPage.slice.blockStart
        val anchorLineChar = anchorPage.slice.charStart
        val superseded = tempStateLock.withLock {
            if (unit.tempBirth !== birth) true else {
                val anchorTemp = anchorPage.copy(resumeBlock = shapedFwdTo, resumeLine = fwdFromLine)
                ip.forwardPages.add(anchorTemp)
                recordChainEdge(ip, anchorTemp)
                ip.shapedForwardTo = shapedFwdTo
                ip.forwardFromLine = fwdFromLine
                ip.anchorLineCharStart = anchorLineChar
                // 出生即在章首（anchorBlock==0）：锚点页就是 shapeAnchorPageForward 灌满的完整首页，
                // "到达章首"在出生那一刻已经发生——首翻回翻直达 Boundary 时即跨章，不再 waste 一下
                // re-root 到同一页（此前缺这行，在首页回翻形同翻不动）。 mid-章出生的会话不受影响，
                // 真正的回翻到达仍走 resolveHeadArrival（现象2）。
                if (anchorBlock == 0) ip.headLanded = true
                unit.bindInProgress(ip)
                unit.markLaidOut()
                // Forward whole-block pages are NOT shaped here synchronously — shaping them on the caller
                // thread made adjusting 行距/字号 stall (every block re-shapes after the cache is dropped
                // above). The background prefill below replenishes the FLIP_AHEAD window; if the reader
                // flips before it lands, tempNav shapes that page on demand as a fallback, so no blank is
                // ever shown.
                ip.forwardPages.firstOrNull()?.let { setCurrentTempPage(unit, ip, it) }
                false
            }
        }
        if (superseded) return // superseded by a newer stream; its commit wins
    } finally {
        // Every path (commit / supersede / invalidate / shape failure) releases the waiters and drops
        // the marker; a newer stream's marker is left in place.
        if (unit.tempBirth === birth) unit.tempBirth = null
        birth.complete(Unit)
    }

    // Background pre-shape (P10/§6.2): the temp session is a bounded sliding window ±1 from the
    // CURRENT pointer — keep the immediate forward AND backward neighbor shaped so the first flips in
    // either direction are served instantly. Each flip re-dispatches the prefill for the new pointer.
    // 视口手势期间跳过：±1 预排按**当前**几何塑形，下一帧尺寸变了就是废活，还和锚页抢槽。
    if (!deferCanonicalForViewport) scheduleTempPrefill(unit, ip)

    // Background: full-canonical layout from the chapter head (LINE_DISK), then persist. The passed-in
    // [prepare] is the light (unshaped) one used by the foreground temp pages, so the canonical pass
    // re-runs the heavy `prepare` to get the full-chapter line geometry that `fullLayout` needs.
    // While the settings panel is open (deferCanonical) this is skipped — only the foreground temp page
    // is live — and [finalizeRelayoutAll] (panel close) re-runs it, so live slider tweaks never contend
    // with a full-chapter shape. Runs on the dedicated canonicalDispatcher (single thread) so it never
    // competes with the foreground flip thread.
    if (!canonicalDeferred) {
        // 原则 §3.4：档位由「目标页距章首 d」决定。birth 这一刻通常还判不出（锚点页的前驱页
        // 尚未成形）→ 第 6 档；等 fillBackward 塑出前驱页后由 maybeBoostB1ForHeadProximity 升档。
        // B1 没派（表已新鲜）则不重扫整书——否则每次落位都把收敛中的 B2 打散重来，
        // B2 永不 drain，前台翻页长期与满槽后台抢 CPU。
        val b1Launched = dispatchB1(unit, ip, epoch, bc, contentW, contentH, paramHash, tempHeadDistancePages(ip))
        // Relaunch the whole-book pass behind this B1 (skip-fresh repositions cheaply; temp-live and
        // fresh chapters no-op). Unconditional: same-key dedup + skip-fresh bound the cost.
        if (b1Launched) requestWholeBookRelayout(force = true)
        // 开书闸门：这里 dispatchB1 只记账不执行，于是 `b1Launched` 恒 false、B2 这一趟不会走到。
        // 闸门压住 B1 的语义就是"B1 及其后面的 B2 一起延到锚页出画面"，所以在此显式记账。
        if (openAnchorGate) openGateWantsB2 = true
    } else {
        Logger.w(logTag, "canonical deferred (panel open) ch=${unit.chapterIndex}")
    }
}

/** 本章权威整章表（B1）的派发。档位由 [headDistancePages] 经 [b1PriorityFor] 定（原则 §3.4），
 *  并把「档位 + d」打进落盘日志——否则设备上无法核对升档是否真的发生了。
 *
 *  P3 queue-not-kill (R3, contract #6): keyed ONE SLOT PER CHAPTER. A new dispatch cancels only
 *  ITS OWN chapter's previous B1 (same chapter re-shaped with newer params — genuinely stale);
 *  ANY OTHER chapter's in-flight B1 is left running and finishes its persist. Until today a
 *  cross-chapter flip cancelled the just-tuned chapter's canonical wholesale, so returning to it
 *  re-temp'd instead of hitting its fresh disk table. Now it lands a disk hit.
 *
 *  抢占范围按**实际档位**给（`cancelLowerThan(priority)`）——加急档只砍比它更低的活，
 *  不误伤同为第 2/3 档的邻页与 d=1 跨章兜底。 */
private fun dispatchB1(
    unit: ChapterUnit,
    ip: InProgressPagination,
    epoch: Long,
    bc: BoxChapterLayouter,
    contentW: Int,
    contentH: Int,
    paramHash: Long,
    headDistancePages: Int?,
): Boolean {
    bindChapterFor(unit)
    // 派发层 skip-fresh（与 b2ChapterTask 执行层 skip-fresh 分工，D6 同理）：
    // 表已新鲜时整趟 B1 是纯浪费——更糟的是它连带 cancelLowerThan 杀在途 B2 +
    // 外层 force 整书重扫，把一个已收敛的 B2 又打散。直接不派。
    if (unit.paginationTable?.paramHash == paramHash) {
        Logger.w(logTag, "b1 skip-fresh ch=${unit.chapterIndex} (table already fresh)")
        return false
    }
    // 开书闸门：锚页还没排完就不许整章全量占槽（总则 §第一优先级）。记账 + 返回 false，
    // 让调用方知道这一趟没派出去——`requestWholeBookRelayout` 的 B2 压在这个返回上。
    if (openAnchorGate) {
        openGateWantsB1 = true
        Logger.w(logTag, "b1 deferred by open gate ch=${unit.chapterIndex}")
        return false
    }
    val priority = b1PriorityFor(headDistancePages)
    Logger.w(
        logTag,
        "b1 dispatch ch=${unit.chapterIndex} prio=$priority headDist=${headDistancePages ?: "?"}"
    )
    scheduler.cancelLowerThan(priority)
    scheduler.submit(TaskScheduler.Task(
        key = "b1:${unit.chapterIndex}",
        priority = priority,
        block = b1@{
        if (epoch != layoutEpoch) {
            Logger.w(logTag, "canonical superseded (epoch $epoch != $layoutEpoch) ch=${unit.chapterIndex}")
            return@b1
        }
        // 执行层 skip-fresh（与 b2ChapterTask 同式）：派发后、开工前表已新鲜
        // （B2 先落盘）则整趟白做，直接让出槽位。
        if (unit.paginationTable?.paramHash == paramHash) {
            Logger.w(logTag, "b1 skip-fresh at execution ch=${unit.chapterIndex}")
            return@b1
        }
        // P7: capture the task coroutine's context so the between-blocks checkpoint can
        // observe this task's cancellation even inside the nested runCatching/inner lambdas.
        val ctx = currentCoroutineContext()
        runCatching {
            val heavy = unit.markup?.let { bc.prepare(it, unit.cssBundle, profile, contentW, contentH, genStrings = boundGenStrings(unit)) }
            // P1: chunk workers deleted (P13) — background canonical shapes sequentially;
            // per-block P7 checkpoints keep abandonment at block granularity, and the
            // scheduler pool (not intra-task fan-out) owns all parallelism now.
            val prod = heavy?.let {
                bc.fullLayout(it, profile, contentW, contentH) { ctx.ensureActive() }
            }
            if (prod != null) finishCanonicalBackground(unit, ip, prod, paramHash)
        }.onFailure { e ->
            // P7: a between-blocks cancel throws CancellationException — that is a TIMELY ABANDON,
            // not a failure. Never report it as FAIL (the per-chapter slot was re-used by design).
            if (e !is CancellationException) Logger.e(logTag, "canonical layout FAIL ch=${unit.chapterIndex} ${e.message}")
        }
        },
    ))
    // 让位后**能回来**：第 6 档低于第 1 档，栅格/翻页一刀就把它砍掉，而 B2 章扫描显式跳过
    // 锚页章（"B1 owns the active chapter"）——没人补派，本章就永远攒不出磁盘表。
    armB1Resume(unit.chapterIndex, epoch, paramHash)
    return true
}

// ── 本章全量（B1）的续跑看门狗 ────────────────────────────────────────────────
//
// 为什么必须有（原则 `docs/线程调度原则.md` §3.6 的缺省实现）：
//   第 6 档「本章全量」低于第 1 档，`notifyFlip()` / `onForegroundRaster()` 的
//   `cancelLowerThan(0)` / `cancelForRaster(0)` 会把它整趟砍掉；而整书 B2 **显式跳过锚页章**
//   （"B1 owns the active chapter"），于是没有任何补派路径。结果：读者正在读的那一章永远
//   落不了盘 → 下次开书落到该章恒走临时表 → 大章要整章重排（GIMP ch4 实测 LARGE-ANCHOR 10.0s）。
//   正确性不依赖后台（第 1 档兜底），但**下次开书的耗时**依赖这张表。
//
// 修法照抄 `SystemFontScanner.armResume` 的既有先例（`docs/字体发现与开书调度方案.md` §3.3
// 「光可续跑不够，还得能回来」）：挂 `TaskScheduler.submitWhenIdle`——它不在队列里，抢占清不到；
// 队列真空时再看一眼：表已新鲜就收工，仍在队/在跑就接着等，被砍掉了就补派一次。

/** 每章一份「在途 B1」的代号：新的派发换新代号 ⇒ 旧看门狗下一轮自行退场，不会叠加成多份。 */
private val b1ResumeTokens = HashMap<Int, Long>()
private val b1ResumeLock = SyncLock()
private var b1ResumeSeq = 0L

/** 为刚派出去的本章 B1 布防一次续跑看门狗（同一章重复派发 = 换代号，旧的作废）。 */
private fun armB1Resume(chapterIndex: Int, epoch: Long, paramHash: Long) {
    val token = b1ResumeLock.withLock {
        b1ResumeSeq += 1
        b1ResumeTokens[chapterIndex] = b1ResumeSeq
        b1ResumeSeq
    }
    armB1ResumeLoop(chapterIndex, epoch, paramHash, token)
}

private fun b1ResumeAlive(chapterIndex: Int, token: Long): Boolean =
    b1ResumeLock.withLock { b1ResumeTokens[chapterIndex] == token }

/**
 * 看门狗本体。**只在队列真空时**动手（`submitWhenIdle`），故不会与前台翻页/栅格抢槽。
 *
 * 每一轮都重新读取表与在途键：这是「状态表即 done 登记」的直接消费，不另存一份视图
 * （原则 §4：派发守卫各自就地判据）。
 */
private fun armB1ResumeLoop(chapterIndex: Int, epoch: Long, paramHash: Long, token: Long) {
    scheduler.submitWhenIdle {
        withContext(Dispatchers.Default) {
            if (!b1ResumeAlive(chapterIndex, token)) return@withContext
            if (layoutEpoch != epoch) {
                Logger.w(logTag, "b1 resume skip ch=$chapterIndex reason=epoch $epoch->$layoutEpoch")
                return@withContext
            }
            if (canonicalDeferred) {
                Logger.w(logTag, "b1 resume skip ch=$chapterIndex reason=deferred")
                return@withContext
            }
            // 开书闸门仍关着 ⇒ 出闸时 [releaseOpenGate] 自己会派 B1 并重新布防，此处不得抢跑。
            if (openAnchorGate) {
                Logger.w(logTag, "b1 resume skip ch=$chapterIndex reason=open-gate")
                return@withContext
            }
            val unit = unitAt(chapterIndex) ?: return@withContext
            if (unit.paginationTable?.paramHash == paramHash) return@withContext // 已落盘：收工
            if (scheduler.pendingPriority("b1:$chapterIndex") != null) {
                // 还在队里/在跑：接着看。它一动队列就不再真空，本轮等待是免费的。
                armB1ResumeLoop(chapterIndex, epoch, paramHash, token)
                return@withContext
            }
            // 临时会话已结束（读者离章/重锚）：不再越俎代庖，章外整章属第 7 档 B2 的活。
            val ip = unit.inProgress
            if (ip == null) {
                Logger.w(logTag, "b1 resume skip ch=$chapterIndex reason=no-temp")
                return@withContext
            }
            val bc = layouter as? BoxChapterLayouter ?: return@withContext
            Logger.w(logTag, "b1 resume on idle ch=$chapterIndex (preempted before persist)")
            dispatchB1(unit, ip, epoch, bc, ip.contentW, ip.contentH, paramHash, tempHeadDistancePages(ip))
        }
    }
}

/** 原则 §3.4 的**再评估**钩子：临时表侧的前驱页塑出之后，才知道锚点页是不是第 2 页。
 *  若是，把本章全量按加急档重派一次（同键 `b1:<章>`，池按调用序让新的孪生胜出）。
 *
 *  重派会取消在途的第 6 档 B1 重来——这是有意的取舍：交接点就剩一次翻页，此时后台那趟
 *  大概率还没排完，等它排完等于交接时手上没有表。只在 d 判定为 1 时才派；d 为 0（已在
 *  交接点，同步承接）/ 2（更深）/ null（判不出）一律不动。 */
private fun maybeBoostB1ForHeadProximity(unit: ChapterUnit, ip: InProgressPagination) {
    if (canonicalDeferred) return
    val d = tempHeadDistancePages(ip) ?: return
    if (d != 1) return
    val bc = layouter as? BoxChapterLayouter ?: return
    Logger.w(logTag, "b1 BOOST ch=${unit.chapterIndex} headDist=1 → 加急档（交接点临近）")
    dispatchB1(unit, ip, layoutEpoch, bc, ip.contentW, ip.contentH, ip.paramHash, d)
}

/** The shared per-block shape cache for [unit]'s current layout-parameter cycle (lazily created). */
private fun unitShapeCache(unit: ChapterUnit) =
    unit.blockShapeCache ?: HashMap<Int, orilumn.reader.engine.laying.ParagraphShapeRef>().also { unit.blockShapeCache = it }

/** Shapes the next backward whole-block temp page (nearest-to-head-edge first). Shares its boundary
 *  rule with [tempNav]'s backward-branch so foreground flips and background prefill build the SAME
 *  sequence (each page's end = the previous page's blockStart; the first page ends at/near the window
 *  head-edge block — [InProgressPagination.forwardPages][0], which is the anchor block until the
 *  bounding window slides past it). */
private fun shapeNextBackward(ip: InProgressPagination, bc: BoxChapterLayouter): TempPage? {
    val prep = ip.prepare
    val isFirst = ip.backwardPages.isEmpty()
    val head = ip.forwardPages.firstOrNull()
    // The page this one must END exactly where it STARTS: the window head-edge for the first backward
    // page, else the deepest backward page shaped so far. Using its real start char (not just its block
    // boundary) keeps the two pages tiling when that page's first line begins mid-block.
    val prevBlockStart = if (isFirst) (head?.blockStart ?: ip.anchorBlockStart) else ip.backwardPages.last().blockStart
    val prevLineCharStart = if (isFirst) (head?.slice?.charStart ?: ip.anchorLineCharStart) else ip.backwardPages.last().slice.charStart
    val prevBlockCharStart = prep.globalCharStarts[prevBlockStart].toInt()
    val lineCut = if (prevLineCharStart > prevBlockCharStart) prevLineCharStart else -1
    val endExcl = if (lineCut >= 0) prevBlockStart + 1 else prevBlockStart
    return shapeBackwardPage(ip, bc, endExcl, lineCut)
}

/** 倒包一页的**唯一入口**（预填与前台翻页共用同一页界制式）：pin = 本页必须**恰好**收到的字符，
 *  由调用方按「相邻前向页页首」折算成 `(endExclusive, lineCut)`——页首块或页首字符所在块 +1，
 *  `lineCut` = 页首字符（块内行切时）或 -1（块边界收页）。
 *
 *  页首游标取 [resolveBackwardStartCursor]（前向链边缓存 → 章首前向正典链 → 几何倒包），三条路都过
 *  [BoxChapterLayouter.shapeTempPageBackward] 内的 pin 自校验，故**本页页尾恒等于 pin**（区块 tiling
 *  不变量：既不留空洞丢字，也不重叠重字）。临时表的页界与磁盘表**同源**：轻路径前向链逐页等于重路径
 *  整章全量分页，故从章首起的倒包页就是 canonical 页页——两份分页在章内不再允许不同，交接时无缝。
 *  端到端见 `docs/分页高度约束与断页规则-方案.md` §12.7/§12.10。
 *
 *  只在 [tempStateLock] 内调用（读 `ip.prevStart`）。`endExclusive <= 0` 返回
 *  null = 章首不适用（非塑形失败，调用方据此返回 0 而非中止预填循环）；塑形失败抛（fail-fast）。 */
private fun shapeBackwardPage(ip: InProgressPagination, bc: BoxChapterLayouter, endExclusive: Int, lineCut: Int): TempPage? {
    if (endExclusive <= 0) return null
    val prep = ip.prepare
    val pinChar = if (lineCut >= 0) lineCut
        else prep.globalCharStarts.getOrElse(endExclusive) { prep.totalChars.toLong() }.toInt()
    val cursor = resolveBackwardStartCursor(ip, bc, pinChar)
    return bc.shapeTempPageBackward(
        prep, ip.profileSnapshot, ip.contentW, ip.contentH, endExclusive, lineCut, ip.shapes, cursor,
    ) ?: error("shapeBackwardPage FAIL ch=${ip.chapterIndex} endExclusive=$endExclusive lineCut=$lineCut pin=$pinChar")
}

/** 倒包页首来源（按优先级），前两级都**只来自临时表自己**；第三级才是几何倒包 + pin 自校验。
 *
 *  1. **本会话前向链边缓存** [InProgressPagination.prevStart]：读者往前翻时**实际看到的**那一页。回翻
 *     必须逐字回到它——已见过的页界不得漂移。
 *  2. **章首（块 0）起的前向正典链** [seedHeadRootedEdges]：临时表的页界与磁盘表**同源**。轻路径前向
 *     链与重路径整章全量分页逐页同值（JVM 真书 ch2 复算：14/14 页 `[start,end)` 逐字相等），所以从块 0
 *     前向塑形得到的「页尾 → 页首」边就是 canonical 页界。几何倒包只能猜回原像多解之一——真书 ch2 的
 *     13 个内部页界里 3 个会被估早 72~98 字，而每个估早的页首又成为下一页的 pin ⇒ 漂移逐页累积（真机
 *     倒包链相对磁盘表整体偏早至 ~600 字）。章首链把这条漂移从构造上消掉。
 *  3. **几何倒包**（`shapeTempPageBackward` 的估计 + pin 自校验）：pin **不是**正典页界时（中章起锚的
 *     行切页首、畸形章）用。页界仍严格 tiling（页尾恒等于 pin），只是不保证等于磁盘表。
 *
 *  **不读 `ip.canonicalSlices` / 磁盘表**：那等于把「后台正典何时算完」变成翻页结果的输入（同一手势
 *  因时机不同给出不同页界）。第 2 级是**确定性**的轻路径前向链（与后台何时算完无关），不是另一份时序
 *  相关的分页。读 `ip.prevStart` / 采样水位全程在 [tempStateLock] 内（写也只在锁内，无发布问题）。 */
private fun resolveBackwardStartCursor(ip: InProgressPagination, bc: BoxChapterLayouter, pinChar: Int): Pair<Int, Int>? {
    ip.prevStart[pinChar]?.let { return it }
    return seedHeadRootedEdges(ip, bc, pinChar)
}

/** 章首（块 0）起的前向正典链采样（见 [resolveBackwardStartCursor] 第 2 级）。
 *
 *  从块 0、首行起逐页 [BoxChapterLayouter.shapeTempPageForward]，登记 `prevStart[页尾字符] = (页首块, 块内行)`，
 *  直到某页页尾 ≥ [upToChar]：命中 ⇒ 返回该页首游标（= `upToChar` 所在页的页首）；越过 ⇒ 返回 null
 *  （`upToChar` 不是正典页界，交几何倒包）。采样水位 [InProgressPagination.headRootedTo] 兼作负缓存，
 *  已越过而未登记 ⇒ 直接 null，不重复整章前向塑形。
 *
 *  **只登记前向页**（与 [prevStart] 纪律一致：倒包页的页首不回写）。顺带把整章各块塑形进共享缓存
 *  （[InProgressPagination.shapes]），故这次采样也让后续回翻页的塑形变便宜。
 *
 *  只在 [tempStateLock] 内调用。 */
private fun seedHeadRootedEdges(ip: InProgressPagination, bc: BoxChapterLayouter, upToChar: Int): Pair<Int, Int>? {
    if (upToChar <= 0) return null
    if (upToChar <= ip.headRootedTo) return null // 已采样越过且未登记：非正典页界
    val prep = ip.prepare
    var b = 0
    var line = 0
    var guard = 0
    while (guard++ < TEMP_HEAD_ROOTED_MAX_PAGES) {
        val fwd = bc.shapeTempPageForward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, b, ip.shapes, line)
            ?: error("seedHeadRootedEdges FAIL ch=${ip.chapterIndex} blk=$b line=$line")
        val s = fwd.page.slice
        // putIfAbsent：读者**实际看到过**的边（第 1 级）优先，采样只补空缺——否则会把读者见过的页界
        // 改成正典的，回翻当场「内容变了」（正是本要修的症状）。
        if (s.charEnd > s.charStart) ip.prevStart.putIfAbsent(s.charEnd, s.blockStart to s.firstLine)
        if (s.charEnd >= upToChar) {
            // 命中页尾恰好 = 目标 ⇒ 该边就是目标页的页首游标；越过 ⇒ 目标不是正典页界。
            ip.headRootedTo = maxOf(ip.headRootedTo, s.charEnd)
            return if (s.charEnd == upToChar) ip.prevStart[upToChar] else null
        }
        ip.headRootedTo = maxOf(ip.headRootedTo, s.charEnd)
        // 「游标永不停滞」（同 shapeTempPageForward 内 guard）：不推进即停，防死循环。
        if (fwd.nextBlock < b || (fwd.nextBlock == b && fwd.nextLine <= line)) break
        b = fwd.nextBlock
        line = fwd.nextLine
    }
    Logger.w(
        logTag,
        "head-rooted chain incomplete ch=${ip.chapterIndex} upTo=$upToChar reached=${ip.headRootedTo} " +
            "blocks=${prep.totalBlocks} chars=${prep.totalChars}",
    )
    return null
}

/** 前向链边登记（见 [InProgressPagination.prevStart]）：`prevStart[本页页尾] = 本页页首游标 (块, 行)`。
 *  前向塑形的**每一个**页面都要登记（含锚点页与失配补塑页）——回翻时据此重建即为同一页。
 *  只在 [tempStateLock] 内调用。 */
private fun recordChainEdge(ip: InProgressPagination, page: TempPage) {
    val s = page.slice
    if (s.blockStart >= 0 && s.charEnd > s.charStart) ip.prevStart[s.charEnd] = s.blockStart to s.firstLine
}

/** The window head-edge page: [InProgressPagination.forwardPages][0] (the anchor page until far-end
 *  eviction slides the window past it). Backward steps derive their packing front from this page's own
 *  block/line, never from the true anchor, so eviction of the anchor region stays correct. */
private fun headEdgePage(ip: InProgressPagination): TempPage? = ip.forwardPages.firstOrNull()

/** One background prefill step (P10 §6.2): shape exactly one temp page to replenish the sliding
 *  window ±1 from the CURRENT pointer — the immediate neighbor first, the immediate OTHER neighbor
 *  second. [lastDir] is the reader's most recent flip direction, so the page the reader is heading to
 *  is shaped FIRST (a backward flip re-dispatches a backward-first prefill; at birth there is no prior
 *  flip and the default forward-first stands). Only the page that will actually sit next to the
 *  CURRENT page is shaped (a page two+ slots out is outside the window and would just be evicted
 *  again), and every append runs [enforceTempWindow] so the window stays within its ±[TEMP_WINDOW_DEPTH]
 *  bound even mid-prefill. Returns whether any shaping happened (the caller loops until false, yielding
 *  between steps). Runs under [tempStateLock] so it is thread-safe with foreground tempNav. */
private fun stepTempPrefill(unit: ChapterUnit, ip: InProgressPagination, lastDir: Int): Boolean = tempStateLock.withLock {
    // layouter 是构造期装配不变量；配错时整轮预填停——此前静默，症状只剩"偶发翻页慢"。抛。
    val bc = checkNotNull(layouter as? BoxChapterLayouter) { "stepTempPrefill without BoxChapterLayouter" }
    val prep = ip.prepare
    val f = ip.forwardPages
    val b = ip.backwardPages
    // Forward neighbor (depth 1): the page right AFTER the current one in reading order.
    val forwardMissing = if (ip.curIsForward) ip.curIndex + 1 >= f.size else ip.curIndex == 0 && f.isEmpty()
    // Backward neighbor (depth 1): the page right BEFORE the current one (toward the chapter head).
    val backwardMissing = if (!ip.curIsForward) ip.curIndex + 1 >= b.size else ip.curIndex == 0 && b.isEmpty()

    // Both sides are filled by the rule pair below: 0 = not applicable (already present / out of
    // range), 1 = a page was shaped. Shaping failure throws (fail-fast) instead of the old
    // code-2 abort — a poisoned prefill must not silently stall the watermark.
    fun fillForward(): Int {
        if (!forwardMissing || ip.shapedForwardTo >= prep.totalBlocks) return 0
        // 前向侧已空：无尾可续，水位必陈旧——禁从水位盲塑（必跳页）。B0→F 的重建归 tempNav RE-ROOT。
        if (f.isEmpty()) return 0
        val tail = f.last()
        // 连续页以当前（尾）页分页点为原点（增量分页原则）；水位只做边界判定，不再是塑形依据。
        // 推不出（尾页非法）即窗口腐败，不塑形；漂移记 w（canary：按理恒为零）。
        val origin = tempResumeAfter(ip, bc, tail.slice) ?: run {
            Logger.e(logTag, "prefill fwd-no-origin DROP ch=${unit.chapterIndex} tail=b${tail.blockStart}[${tail.slice.charStart},${tail.slice.charEnd})")
            return 0
        }
        if (origin.first != ip.shapedForwardTo || origin.second != ip.forwardFromLine) {
            Logger.w(logTag, "prefill origin-drift ch=${unit.chapterIndex} derived=(${origin.first},${origin.second}) waterline=(${ip.shapedForwardTo},${ip.forwardFromLine})")
        }
        // 塑形失败编码 2 中止整轮：后台水位停推，前台下次翻页按需塑——延迟归因在此记 e。
        // 开发期 fail-fast：记 e 后抛（后台 SupervisorJob 收容，不连累前台；栈留痕）。
        val fwd = bc.shapeTempPageForward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, origin.first, ip.shapes, origin.second)
            ?: error("stepTempPrefill fillForward FAIL ch=${unit.chapterIndex} origin=(${origin.first},${origin.second})")
        // 「游标永不停滞」canary（按理恒为零；源头是 shapeTempPageForward 内的 placedLines guard）：
        // 塑形点没越过入参原点 ⇒ 该页一个字符都没推进。追进去只会让窗口塞满同页副本、水位永不推进，
        // 症状是前向翻页原地不动（曾经的真实事故：宽屏全屏下超高整宽插图）。丢弃并记 w。
        if (fwd.nextBlock < origin.first || (fwd.nextBlock == origin.first && fwd.nextLine <= origin.second)) {
            Logger.w(logTag, "prefill fwd-stall DROP ch=${unit.chapterIndex} origin=(${origin.first},${origin.second}) next=(${fwd.nextBlock},${fwd.nextLine}) shaped=b${fwd.page.blockStart}[${fwd.page.slice.charStart},${fwd.page.slice.charEnd})")
            return 0
        }
        // 失配即窗口/水位已分叉：追加即跳页。丢弃（不追加、不推水位，不变量 intact），记 e 待查。
        if (!tempPagesTile(tail.slice, fwd.page.slice)) {
            Logger.e(logTag, "prefill fwd-stale-window DROP ch=${unit.chapterIndex} tail=b${tail.blockStart}[${tail.slice.charStart},${tail.slice.charEnd}) shaped=b${fwd.page.blockStart}[${fwd.page.slice.charStart},${fwd.page.slice.charEnd})")
            return 0
        }
        val appended = fwd.page.copy(resumeBlock = fwd.nextBlock, resumeLine = fwd.nextLine)
        f.add(appended)
        recordChainEdge(ip, appended)
        ip.shapedForwardTo = fwd.nextBlock
        ip.forwardFromLine = fwd.nextLine
        enforceTempWindow(ip)
        return 1
    }
    fun fillBackward(): Int {
        if (!backwardMissing) return 0
        // null = 章首不适用（无塑可出），非塑形失败；塑形失败已在 shapeNextBackward 内抛。
        val p = shapeNextBackward(ip, bc) ?: return 0
        b.add(p)
        if (p.blockStart == 0) ip.headReached = true
        enforceTempWindow(ip)
        // 原则 §3.4：前驱页刚成形，才知道指针页是不是第 2 页（其前驱从 block 0 起）。
        // 是则把本章全量升到加急档——交接点就剩一次翻页。d 判不出/更深时本函数静默返回。
        //
        // 承重前提：本函数已在 [tempStateLock] 内，而 [maybeBoostB1ForHeadProximity] 会再取一次
        // 同一把锁。这依赖 SyncLock 在各平台 actual 上**可重入**（JVM/Android/Desktop 是
        // `synchronized`，JVM 监视器可重入）。iOS actual 尚未落地，若将来不可重入则必须把本调用
        // 移到锁外（stepTempPrefill 返回后在调用方循环里判）。所幸链路上不做阻塞动作
        //（dispatchB1 只 submit，不等待），即便锁不可重入也只是自锁而非死锁——但仍须显式修。
        maybeBoostB1ForHeadProximity(unit, ip)
        return 1
    }
    // Direction-prioritized: the reader's NEXT page (the one in the direction of the last flip) is
    // replenished first, so a rapid second flip in the same direction is served from an already-shaped
    // window page instead of the opposite side winning the background slot.
    val order = if (lastDir >= 0) arrayOf(::fillForward, ::fillBackward) else arrayOf(::fillBackward, ::fillForward)
    for (fill in order) {
        when (fill()) {
            1 -> return@withLock true
            2 -> return@withLock false
        }
    }
    return@withLock false
}

/** Starts the background temp-window prefill for the current pointer. Each flip re-dispatches it so
 *  the window slides (P10 §6.2) — [lastDir] is that flip's direction and steers the shape order
 *  (direction-prioritized, see [stepTempPrefill]) — but a re-dispatch for the SAME in-flight target
 *  (pointer unchanged, e.g. a locate re-sync) is deduped. Cancels any previous prefill before
 *  launching. Runs on [backgroundDispatcher] so it never blocks the foreground flip thread. */
private fun scheduleTempPrefill(unit: ChapterUnit, ip: InProgressPagination, lastDir: Int = 0) {
    bindChapterFor(unit)
    val cursor = (ip.sessionId shl 32) or
        ((if (ip.curIsForward) 1L else 0L) shl 20) or
        ((lastDir and 0x3).toLong() shl 16) or ip.curIndex.toLong()
    if (cursor == lastPrefillCursor && tempPrefillJob?.isActive == true) return
    lastPrefillCursor = cursor
    tempPrefillJob?.cancel()
    tempPrefillJob = bgScope.launch(backgroundDispatcher) {
        try {
            // 无步数截断：循环在窗口补满（stepTempPrefill 回 false）时自然结束；
            // 每步只塑一页且前后邻均有界（章尾/章首），无死循环空间。
            var guard = 0
            while (true) {
                val stepped = stepTempPrefill(unit, ip, lastDir)
                if (!stepped) break
                guard++
                // 刚塑出一页版式 ⇒ 该页的像素可以开始生产了（阶梯里补的栅格档）。
                // 抛信号让用户层按方向补一张；不在此直接栅格（引擎线程不能碰画布）。
                onPrefillReadyListener?.invoke(unit.chapterIndex)
                kotlinx.coroutines.delay(4)
            }
            // 报块水位深度，否则第 4 档在临时表侧的推进幅度设备上不可见。
            val (fwd, total) = tempStateLock.withLock { ip.shapedForwardTo to ip.prepare.totalBlocks }
            Logger.d(logTag, "temp prefill done ch=${unit.chapterIndex} steps=$guard shapedFwdTo=$fwd/$total")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // superseded by a newer prefill or a new anchor stream — expected
        } catch (e: Exception) {
            Logger.e(logTag, "temp prefill FAIL ch=${unit.chapterIndex} ${e.message}")
        }
    }
}

/** Reuses [unit]'s cached full-chapter (heavy) prepare when [paramHash] is unchanged; rebuilds and
 *  caches otherwise. Heavy prepare performs full-chapter line shaping, so it is only used on paths that
 *  legitimately need it — relayout ([prepareRelayout]) and background canonical — never on the
 *  large-chapter foreground (which uses the cheap [BoxChapterLayouter.prepareLight]). */
private fun prepareFor(
    unit: ChapterUnit,
    box: BoxChapterLayouter,
    markup: orilumn.reader.engine.html.MarkupElement,
    contentWidth: Int,
    contentHeight: Int,
    paramHash: Long,
): ChapterPrepareResult {
    unit.prepareResult?.let { if (unit.paramHash == paramHash) return it }
    return box.prepare(markup, unit.cssBundle, profile, contentWidth, contentHeight, genStrings = boundGenStrings(unit)).also { unit.bindPrepare(it, paramHash) }
}

/** P3: already-bound generated-content strings for heavy-prepare reuse. Valid only while the CSS
 *  matches the bound content (same gate the light path uses); null → the heavy path computes. */
private fun boundGenStrings(unit: ChapterUnit): Map<orilumn.reader.engine.html.MarkupElement, Pair<String?, String?>>? {
    val texts = unit.cssBundle?.cssTexts ?: return null
    if (unit.structureCache.boundCssHash != ChapterStructureCodec.cssHashOf(texts)) return null
    return unit.structureCache.genStrings.takeIf { it.isNotEmpty() }
}

/** The current temp page the navigation pointer refers to. Null = 指针越界（窗口腐败）或
 *  会话无页——调用方把 null 当"判不出"处理，但越界本身记 e（见 tempHeadDistancePages）。 */
private fun currentTempPage(ip: InProgressPagination): TempPage? =
    if (ip.curIsForward) ip.forwardPages.getOrNull(ip.curIndex)
    else ip.backwardPages.getOrNull(ip.curIndex)

private fun tempPointerValid(ip: InProgressPagination): Boolean =
    if (ip.curIsForward) ip.curIndex in ip.forwardPages.indices
    else ip.curIndex in ip.backwardPages.indices

private fun setCurrentTempPage(unit: ChapterUnit, ip: InProgressPagination, page: TempPage) {
    ip.setCurrent(page.slice)
    if (unit.inProgress === ip) unit.setTempRenderLayout(page.layout)
}

/** Syncs the navigation pointer to the (currently displayed) temp page. The torn anchor pair shares a
 *  blockStart — [backwardPages][0] and [forwardPages][0] both start at the head block — so a
 *  blockStart-only match would re-route a backward-page slice onto the head-edge page and make a
 *  forward flip SKIP the head page. Disambiguate by (blockStart, charStart): every temp page owns a
 *  unique charStart. Falls back to blockStart only for a slice with no char match (stale/foreign). */
private fun locateTempPosition(ip: InProgressPagination, slice: PageSlice) {
    val bs = slice.blockStart
    if (bs >= 0) {
        val fi = ip.forwardPages.indexOfFirst { it.blockStart == bs && it.slice.charStart == slice.charStart }
        if (fi >= 0) { ip.curIsForward = true; ip.curIndex = fi; return }
        val bi = ip.backwardPages.indexOfFirst { it.blockStart == bs && it.slice.charStart == slice.charStart }
        if (bi >= 0) { ip.curIsForward = false; ip.curIndex = bi; return }
        val f2 = ip.forwardPages.indexOfFirst { it.blockStart == bs }
        if (f2 >= 0) { ip.curIsForward = true; ip.curIndex = f2; return }
        val b2 = ip.backwardPages.indexOfFirst { it.blockStart == bs }
        if (b2 >= 0) { ip.curIsForward = false; ip.curIndex = b2; return }
    }
}

/** 相接判据（排版层-上/增量分页）：两源页首尾相接即连续，块边界分隔符容忍 ±3 字（纯判据，不存状态）。 */
private fun tempPagesTile(prev: PageSlice, next: PageSlice): Boolean {
    if (prev.charStart == next.charStart && prev.charEnd == next.charEnd) return true
    val fwd = next.charStart - prev.charEnd
    val bwd = prev.charStart - next.charEnd
    return fwd in -3..3 || bwd in -3..3
}

/** 源页后继原点（排版层-上/增量分页）：返回 Triple(块, 行, dev)，dev = 后继首字 − 源页末字。
 *  行内取"首个起始不早于末字符的行"（行首/分隔符结尾的源页精确命中，dev≈0）；
 *  落到行中（陈旧源页）则取该行行首，dev 为负=重叠。下一块/空块/非法一律 fail-open 返回 null。
 *  纯判据，不存状态。 */
private fun tempResumeAfter(ip: InProgressPagination, bc: BoxChapterLayouter, src: PageSlice): Triple<Int, Int, Int>? {
    val prep = ip.prepare
    val total = prep.totalBlocks
    if (total <= 0 || src.blockEndExclusive <= 0) return null
    val gcs = prep.globalCharStarts
    if (gcs.size < total) return null
    val bl = (src.blockEndExclusive - 1).coerceIn(0, total - 1)
    val base = gcs[bl].toInt()
    val rel = src.charEnd - base
    if (rel < 0) return null
    val nextStart = if (bl + 1 < total) gcs[bl + 1].toInt() else prep.totalChars
    if (src.charEnd >= nextStart) {
        // 源页止于块末或块外：后继从下一块首行起（dev≤0 为源页越界的重叠量）。
        if (bl + 1 >= total) return null
        return Triple(bl + 1, 0, nextStart - src.charEnd)
    }
    val shape = try { bc.tempShape(ip.shapes, prep, bl, ip.profileSnapshot) } catch (_: Exception) { return null }
    if (shape.lineCount <= 0) return Triple(bl + 1, 0, nextStart - src.charEnd)
    val l0 = try { shape.lineStart(0) } catch (_: Exception) { return null }
    var gapK = -1
    var lapK = -1
    var i = 0
    while (i < shape.lineCount) {
        val ls = try { shape.lineStart(i) } catch (_: Exception) { return null } - l0
        if (ls >= rel && gapK < 0) gapK = i
        if (ls <= rel) lapK = i
        i++
    }
    // 精确（行首/分隔符结尾）优先取 gap 侧；行中（陈旧）取重叠侧，由调用方按 dev 裁决。
    if (gapK >= 0) {
        val ls = try { shape.lineStart(gapK) } catch (_: Exception) { return null } - l0
        if (ls - rel <= 3) return Triple(bl, gapK, (base + ls) - src.charEnd)
    }
    if (lapK >= 0) {
        val ls = try { shape.lineStart(lapK) } catch (_: Exception) { return null } - l0
        return Triple(bl, lapK, (base + ls) - src.charEnd)
    }
    return null
}

/** 前向失配修复（排版层-上/增量分页；S7 连续 miss 按需补塑，不废会话）：
 *  以源页末端为原点当场补塑真正的下一页，替换错位落页，并把窗口收敛到"源页→补塑页"链上
 *  （截掉错位页；水位=补塑页后继）。失败返回 null（调用方保留原落页 + 记 e）。全程持 tempStateLock。 */
private fun repairTempForwardGap(
    unit: ChapterUnit, ip: InProgressPagination, bc: BoxChapterLayouter, src: PageSlice, bad: PageSlice,
): Pair<Int, PageSlice>? {
    val prep = ip.prepare
    val resume = tempResumeAfter(ip, bc, src) ?: run {
        Logger.e(logTag, "tempNav fwd-repair-abort ch=${unit.chapterIndex} no-resume src=char[${src.charStart},${src.charEnd})")
        return null
    }
    // dev = 后继首字 − 源页末字：±3 内静默精确修复；[-100,3) 为行级近似（w 可见）；
    // 超出即两代分页口径分叉过大，不强行续接。
    if (resume.third < -100 || resume.third > 3) {
        Logger.e(logTag, "tempNav fwd-repair-abort ch=${unit.chapterIndex} dev-out-of-range src=char[${src.charStart},${src.charEnd}) resume=${resume.first},${resume.second} dev=${resume.third}")
        return null
    }
    val fwd = try {
        bc.shapeTempPageForward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, resume.first, ip.shapes, resume.second)
    } catch (e: Exception) {
        Logger.e(logTag, "tempNav fwd-repair-abort ch=${unit.chapterIndex} shape-throw ${e.message}")
        return null
    } ?: run {
        Logger.e(logTag, "tempNav fwd-repair-abort ch=${unit.chapterIndex} shape-null resume=$resume")
        return null
    }
    val page = fwd.page.copy(resumeBlock = fwd.nextBlock, resumeLine = fwd.nextLine)
    // 塑形必须复现推导原点（分隔符容忍 ±3），否则是塑形非确定性回归，不装窗。
    val actual = page.slice.charStart - src.charEnd
    if (actual < resume.third - 3 || actual > resume.third + 3) {
        Logger.e(logTag, "tempNav fwd-repair-abort ch=${unit.chapterIndex} nondeterministic src=char[${src.charStart},${src.charEnd}) fixed=char[${page.slice.charStart},${page.slice.charEnd}) dev=${resume.third}")
        return null
    }
    val f = ip.forwardPages
    val b = ip.backwardPages
    val fi = f.indexOfFirst { it.slice.charStart == src.charStart && it.blockStart == src.blockStart }
    if (fi >= 0) {
        // 源页在前向侧：其后全是错位页，截掉，补塑页接上。
        while (f.size > fi + 1) f.removeAt(f.size - 1)
        f.add(page)
        ip.curIsForward = true
        ip.curIndex = f.size - 1
    } else {
        val bi = b.indexOfFirst { it.slice.charStart == src.charStart && it.blockStart == src.blockStart }
        if (bi >= 0) {
            // 源页在后向侧：其后（b[0..bi) + 全前向）皆错位，截掉，补塑页重建前向。
            repeat(bi) { b.removeAt(0) }
            f.clear()
            f.add(page)
            ip.curIsForward = true
            ip.curIndex = 0
        } else {
            // 源页已不在窗口（被裁）：窗口与显示分叉，前后皆不可信，重建（后向按需重塑）。
            b.clear()
            f.clear()
            f.add(page)
            ip.curIsForward = true
            ip.curIndex = 0
        }
    }
    ip.shapedForwardTo = fwd.nextBlock
    ip.forwardFromLine = fwd.nextLine
    recordChainEdge(ip, page)
    enforceTempWindow(ip)
    setCurrentTempPage(unit, ip, page)
    Logger.w(logTag, "tempNav fwd-repair ch=${unit.chapterIndex} src=char[${src.charStart},${src.charEnd}) bad=char[${bad.charStart},${bad.charEnd}) fixed=char[${page.slice.charStart},${page.slice.charEnd}) dev=${resume.third}")
    return unit.chapterIndex to page.slice
}

/** One unit of the temp window in reading order (headward → tailward). The anchor block's torn pair —
 *  `backwardPages[0]` + `forwardPages[0]`, the two pages sharing the anchor line-cut — is ONE unit so
 *  it is always kept or dropped together (§6.1). */
private sealed class WinUnit {
    data class Fwd(val idx: Int) : WinUnit()
    data class Bwd(val idx: Int) : WinUnit()
    object Pair : WinUnit()
}

/** P10 (§6.2): shrink the temp window back to ±[TEMP_WINDOW_DEPTH] around the current pointer. Only
 *  the FAR end (in reading order) is ever removed — the keep-set is a contiguous interval around the
 *  current page, so no middle page is ever hopped; the page under the pointer is never evicted; and
 *  the torn pair is atomic. After the rebuild the pointer is recovered by object identity. Runs under
 *  [tempStateLock]. */
private fun enforceTempWindow(ip: InProgressPagination) {
    val depth = TEMP_WINDOW_DEPTH
    val prep = ip.prepare
    val f = ip.forwardPages
    val b = ip.backwardPages
    if (f.isEmpty() && b.isEmpty()) return
    // A page whose first line is a line-cut of its first block shares that block with its backward
    // neighbor — they are the torn pair and must stay together.
    val head = f.firstOrNull()
    val torn = head != null && head.slice.charStart > prep.globalCharStarts[head.blockStart].toInt()
    val pair = torn && b.isNotEmpty()

    // Reading-order units, headward → tailward (the torn pair sits between backward[0] and forward[0]).
    val units = ArrayList<WinUnit>()
    for (k in b.size - 1 downTo 0) if (!pair || k != 0) units.add(WinUnit.Bwd(k))
    if (pair) units.add(WinUnit.Pair)
    for (k in 0 until f.size) if (!pair || k != 0) units.add(WinUnit.Fwd(k))
    if (units.isEmpty()) return

    val curUnit: WinUnit? =
        if (ip.curIsForward) (if (pair && ip.curIndex == 0) WinUnit.Pair else WinUnit.Fwd(ip.curIndex))
        else (if (pair && ip.curIndex == 0) WinUnit.Pair else WinUnit.Bwd(ip.curIndex))
    val curPos = units.indexOf(curUnit)
    if (curPos < 0) return

    val lo = (curPos - depth).coerceAtLeast(0)
    val hi = (curPos + depth).coerceAtMost(units.size - 1)
    val curPage = currentTempPage(ip) ?: return

    val keepF = ArrayList<Int>()
    val keepB = ArrayList<Int>()
    for (u in units.subList(lo, hi + 1)) when (u) {
        is WinUnit.Fwd -> keepF.add(u.idx)
        is WinUnit.Bwd -> keepB.add(u.idx)
        WinUnit.Pair -> { keepF.add(0); keepB.add(0) }
    }
    keepF.sort()
    keepB.sort() // ascending → nearest-to-head-edge first, the backward list's invariant
    if (keepF.size == f.size && keepB.size == b.size) return // window already within budget
    val newF = ArrayList<TempPage>(keepF.size)
    for (k in keepF) newF.add(f[k])
    val newB = ArrayList<TempPage>(keepB.size)
    for (k in keepB) newB.add(b[k])
    f.clear(); f.addAll(newF)
    b.clear(); b.addAll(newB)
    // 水位-窗口不变量（排版层-上/增量分页）：水位恒为前向尾的后继原点。尾裁剪若不断水位，
    // 下一次前向塑形（按需/prefill）必从陈旧水位起塑 → 整页跳过。倒回新尾；前向侧清空则
    // 水位失效（-1），前向重建只许走 RE-ROOT（fillForward 空窗守卫；按需侧见下）。
    val newTail = f.lastOrNull()
    if (newTail != null) {
        if (newTail.resumeBlock >= 0) {
            ip.shapedForwardTo = newTail.resumeBlock
            ip.forwardFromLine = newTail.resumeLine
        } else {
            Logger.e(logTag, "tempWindow rewind-unknown ch=${ip.chapterIndex} tail=b${newTail.blockStart} — 水位保持旧值")
        }
    } else {
        ip.shapedForwardTo = -1
        ip.forwardFromLine = 0
    }
    val fi = newF.indexOfFirst { it === curPage }
    val bi = newB.indexOfFirst { it === curPage }
    if (fi >= 0) { ip.curIsForward = true; ip.curIndex = fi }
    else if (bi >= 0) { ip.curIsForward = false; ip.curIndex = bi }
}

/** P8 probe/test accessor: the head-lift threshold ([HEAD_START_BLOCK_LIMIT]). Follows the
 *  [tempWindowSnapshot] precedent — android unit tests can't resolve main-source-set top-level internal
 *  declarations, only controller members. */
/** C2-P2b-4: public probe hook (was `internal`; `:app` probe tests live across the module seam). */
fun headStartBlockLimit(): Int = HEAD_START_BLOCK_LIMIT

/** Diagnostic snapshot of a chapter's temp window (P10 T7 probe + flip debugging): the kept page
 *  counts and block ranges, plus whether the head edge is torn. Reads under [tempStateLock]. Null when
 *  the chapter has no active temp session. */
fun tempWindowSnapshot(chapter: Int): TempWindowSnapshot? {
    val ip = unitAt(chapter)?.inProgress ?: return null
    return tempStateLock.withLock {
        val head = ip.forwardPages.firstOrNull()
        val torn = head != null && head.slice.charStart > ip.prepare.globalCharStarts[head.blockStart].toInt()
        TempWindowSnapshot(
            forwardPages = ip.forwardPages.size,
            backwardPages = ip.backwardPages.size,
            forwardBlocks = ip.forwardPages.map { it.blockStart to it.blockEndExclusive },
            backwardBlocks = ip.backwardPages.map { it.blockStart to it.blockEndExclusive },
            curIsForward = ip.curIsForward,
            curIndex = ip.curIndex,
            headEdgeTorn = torn,
        )
    }
}

/** A chapter's temp-window state, read atomically for probes/diagnostics. */
data class TempWindowSnapshot(
    val forwardPages: Int,
    val backwardPages: Int,
    val forwardBlocks: List<Pair<Int, Int>>,
    val backwardBlocks: List<Pair<Int, Int>>,
    val curIsForward: Boolean,
    val curIndex: Int,
    val headEdgeTorn: Boolean,
)

/** One step of temp navigation. Returns null at a chapter boundary (caller falls through to
 *  cross-chapter navigation). */
/** R2: last tempNav null-branch reason (diagnostic only; flip thread writes, flip thread reads). */
private var lastTempNavNull: String? = null

/** P1d: the single flip hook. Preempts all pool work below flip priority (page prefill
 *  tasks included — same-key resubmits reposition on the next landing); resubmits reposition
 *  via skip-fresh.
 *  P2.6: R18's `lastFlipMs` stamp is GONE — preemption never polls a clock (cancel-on-flip is
 *  unconditional, resume by resubmit), so the timestamp had no reader. R18 doctrine: cancel
 *  promptly, don't wait out bursts. */
private fun notifyFlip() {
    scheduler.cancelLowerThan(TaskScheduler.PRIO_FLIP)
}

/**
 * **栅格（第 1 档「渲染」半边）的抢占钩子** —— 与 [notifyFlip] 同一动作、不同触发源。
 *
 * 总则第 1 条把目标页定为「立即同步排版**渲染**」，即渲染与排版同属第 1 档；
 * `线程调度原则.md` §5 规定第 1 档入口统杀其下全部后台活（唯一钩子 = `notifyFlip`）。
 * 此前只有翻页/导航接了钩子，**栅格这半边漏接**：页位图未命中、真正栅格化时，
 * 第 7 档整章全量（b2，纯章距投机、紧急度最低）能全程压在绘制线程上。
 *
 * 实测（2 核 Linux 桌面，同一份代码、同一页）：
 * - 漏接（b2 与栅格并发）：单页栅格 3874 / 1626 / 671 ms；
 * - 隔离基准（无并发后台）：30–160 ms。
 *
 * 平板核多、槽位只占 2/8，同一缺陷不显形，但**两端同源**，故钩子进共享排版层。
 * 幂等可重入：被抢占的后台活按状态表/代际门自行重启（§3.6：低优可被无限推迟，不加 aging）。
 *
 * **但必须放行当前章的 ±1 页预排**（[TaskScheduler.cancelForRaster]，不是裸 [notifyFlip]）：
 * ±1 装配正是方向侧预栅格（`Preraster.kt`）的版式源，也是下一次翻页落位能命中
 * `pageCache` 快路径（`ensurePageRangeShaped` pagecache-hit）的前提。裸砍等于「反手掐掉
 * 自己下一步要用的 ±1」——真机（vivo PA2353）实测：连读翻页时每次落位都走同步重排
 * `shape=115–176ms`、`target-ready 137–308ms`，预栅格一次都不触发，形成冷稳态自锁；
 * 放行后同一序列 target-ready 回落到 32–40ms，预栅格正常命中。参见 `Preraster.kt`。
 */
fun onForegroundRaster() {
    scheduler.cancelForRaster(TaskScheduler.PRIO_FLIP)
}

/**
 * 预排出邻页版式后抛信号：**像素尚缺，用户层可据此按方向补一张栅格**
 * （`线程调度原则.md` §3 阶梯补的那一档）。
 *
 * ## 为什么需要
 *
 * 阶梯第 2/3/4 档全是**排版**（产出 `pageLines`），栅格不在体系内，于是像素只在页面
 * **被绘制时**才生产。实测单页栅格 p50 143ms、max 977ms，而翻页的 `target-ready`
 * p50 59ms —— 用户手指按下后要先等这张位图才动得了。
 *
 * 但预排出的邻页**用户下一步就要用**（§0 紧急度原则），像素该同号跟进而非等绘制。
 * moon+ 就是这么做的：页面静止时 `getPageShot()` 预先截好下一页（`tmpFlipShot2`），
 * 滑动时直接复用，于是感知零时延。
 *
 * ## 为什么由引擎抛、用户层执行
 *
 * 引擎跑后台线程且**不能碰画布**（`LineWindowDrawer`/`drawPageContent` 在本模块，但栅格
 * 产物要与 UI 的 `PageRasterStore` 共用离屏 surface，跨线程会出事）。故这里只抛信号，
 * 执行落在用户层的 UI 线程（见 `ReaderHost.observePrefillReady`）。
 *
 * **翻页只需一页**：由订阅方按方向取那一张（§3.2 方向记录），不在这里扇出。
 *
 * @param chapter 刚出版式的那一章。
 */
fun notifyPrefillReady(chapter: Int) {
    onPrefillReadyListener?.invoke(chapter)
}

/** 用户层注册的预排完成回调（`ReaderHost.observePrefillReady` 装进来）。非空即表示有订阅者。 */
@Volatile
var onPrefillReadyListener: ((Int) -> Unit)? = null

    /**
     * 导航/调参入口抢占（跳转五路 + prepareRelayout 共用）：砍掉在途低档活（整书 B2、
     * 预填充等），落位章的新布局独占 CPU/内存。砍掉过东西才把派发戳清零 —— 下次自然派发
     * （翻页尾/循环尾）即重扫补回被砍的章；池里没活时只取消（空转）不碰戳，收敛好的书
     * 跳转零扰动。落位后的补派走各路径已有的尾部分派（anchor 流 force / finalize 显式）。
     */
    private suspend fun preemptForNavigation() {
        if (scheduler.pendingCount() + scheduler.runningCount() > 0) {
            notifyFlip()
            lastDispatchEpoch = -1L
        }
    }

/** R3: immutable published neighbor shapes (chapter + paramHash keyed). Read-only after
 *  publication — safe to consult from any thread; stale entries are ignored by key check. */
@Volatile
private var windowPrefillL2: WindowPrefillShapes? = null

private data class WindowPrefillShapes(
    val chapterIndex: Int,
    val paramHash: Long,
    val shapes: Map<Int, ParagraphShapeRef>,
)

/** R17 probe hook (C2-P2b-4 pattern): cached page indexes for [chapter]. */
fun pageCacheKeysForTest(chapter: Int): Set<Int> =
    unitAt(chapter)?.pageCache?.keys?.toSet() ?: emptySet()

/** R3 probe hook (C2-P2b-4 pattern): last published window-prefill snapshot, if any. */
fun windowPrefillSnapshotForTest(): Triple<Int, Long, Set<Int>>? =
    windowPrefillL2?.let { Triple(it.chapterIndex, it.paramHash, it.shapes.keys.toSet()) }

/** d=1 章外兜底任务的键（原则 §3.3）。**与整书章扫描的 [scanKey] 刻意分域**——
 * 两者是同一份工作（整章全量）但紧急度不同：兜底是"读者下一步就要翻过去"（第 2/3 档），
 * 章扫描是纯章距投机（第 7 档）。共用键时同键去重按"后来者胜"处理，章扫描那趟若后到就会把
 * 兜底降级回第 7 档，兜底随即被埋进章距序列——这正是"跨章跳过"旧规则想避开的困境，
 * 换个键才是真正的解法。 */
fun edgeKey(chapter: Int) = "edge:$chapter"

/**
 * 本控制器在（可能共享的）[TaskScheduler] 中拥有的**全部键域前缀**。
 *
 * [BookDocumentController.close] 在调度器为应用级共享时按此整域取消，避免误杀他书/系统任务。
 * 新增 dispatch 点时**必须同步登记**（键命名见：`b1:` 本章全量、`b2:` 整书章扫描、
 * `edge:` d=1 章外兜底、`pg:` 邻页装配、`pre:` 解析预热）。
 */
private val CONTROLLER_KEY_PREFIXES = listOf("b1:", "b2:", "edge:", "pre:", "pg:")

/** 邻页序列的一项（原则 §3.1）。序列本身是**有序**的，出队次序即优先级次序。 */
sealed class NeighborItem {
    /** 章内第 [index] 页（[tier] = 10/20/30，见 [TaskScheduler.PRIO_PAGE_*]）。 */
    data class Page(val index: Int, val tier: Int) : NeighborItem()

    /** d=1 越出本章 → 加急整章排邻章（[chapter] = 邻章号，[tier] 同上）。§3.3。 */
    data class EdgeChapter(val chapter: Int, val tier: Int) : NeighborItem()
}

/** 原则 §3.1 的**邻页序列**：从目标页出发，按距离 d = 1, 2, 3, … 逐层展开，每层先方向侧、
 *  后另一侧：`+1·dir, −1·dir, +2·dir, −2·dir, …`
 *
 *  档位分配：
 *  - **d = 1 顺方向侧** → 第 2 档（原则的「下一页」）
 *  - **d = 1 反方向侧** → 第 3 档（原则的「上一页」）
 *  - **d ≥ 2** → 第 4 档（本章其余页）
 *
 *  章边界（§3.3）：**只有 d=1 越界才升级**成 [NeighborItem.EdgeChapter]（加急整章排邻章）；
 *  d≥2 越界说明本章至少还剩两页，隔两页仍不翻就不值得为它动整章——该项直接丢弃。
 *
 *  第 4 档排到章尾，不设深度截断：总则要求按偏离度串行排全章（有界 drain 窗口已废）。
 *  指针前移一格，整段序列按新指针重派（同键替换），读者持续读，drain 就持续推进。
 *
 *  纯函数、无副作用：生产派发与 probe 验的是同一份判据。 */
fun neighborSequence(
    targetPage: Int,
    totalPages: Int,
    dir: Int,
    chapterIdx: Int,
    lastChapter: Int,
): List<NeighborItem> {
    if (totalPages <= 0) return emptyList()
    val out = ArrayList<NeighborItem>()
    for (d in 1..(totalPages + 1)) {
        val dirSide = targetPage + d * if (dir >= 0) 1 else -1
        val otherSide = targetPage - d * if (dir >= 0) 1 else -1
        for ((page, isDirSide) in listOf(dirSide to true, otherSide to false)) {
            val tier = when {
                d > 1 -> TaskScheduler.PRIO_PAGE_REST
                isDirSide -> TaskScheduler.PRIO_PAGE_NEXT
                else -> TaskScheduler.PRIO_PAGE_PREV
            }
            if (page in 0 until totalPages) {
                out.add(NeighborItem.Page(page, tier))
            } else if (d == 1) {
                val beyond = if (page >= totalPages) chapterIdx + 1 else chapterIdx - 1
                if (beyond in 0..lastChapter) {
                    out.add(NeighborItem.EdgeChapter(beyond, if (isDirSide) {
                        TaskScheduler.PRIO_EDGE_FORWARD
                    } else {
                        TaskScheduler.PRIO_EDGE_BACKWARD
                    }))
                }
            }
            // d >= 2 越界：丢弃（§3.3）
        }
        // 序列已覆盖全章 + 两侧各一次越界，无需再远。
        if (d > totalPages) break
    }
    return out
}

/** 整书章扫描（B2，第 7 档）的键。与 [edgeKey] 必须分域，见该函数 KDoc。 */
fun scanKey(chapter: Int) = "b2:$chapter"

/** 原则 §3.4：目标页距章首的页数 d，决定本章全量的档位。**纯判定，无副作用。**
 *
 *  - `d == 0`（目标页就是章首页）→ 已在交接点，全量表走**同步承接**，不升档；
 *  - `d == 1`（目标页是第 2 页）→ **加急档**：交接点就剩一次翻页，而增量侧此刻还要逐页往后推
 *    （每页边界依赖前页尾部），全量一趟给出整章全部边界；
 *  - `d >= 2` 或 `null`（判不出）→ 第 6 档。d≥2 不升级，与 §3.3 同一把尺子：隔两页仍不翻，
 *    就不值得为它动整章。
 *
 *  @param headDistancePages d；`null` = 前驱页尚未成形、判不出（临时表侧刚birth时的常态）。 */
fun b1PriorityFor(headDistancePages: Int?): Int =
    if (headDistancePages == 1) TaskScheduler.PRIO_B1_URGENT else TaskScheduler.PRIO_B1_CHAPTER

/** 临时表侧的目标页距章首页数，**仅在可判定时**返回（原则 §3.4 只要 d==1 那一档）。
 *
 *  - `0` = 当前页从 block 0 起，即章首页；
 *  - `1` = 当前页的前驱页从 block 0 起，即当前页是第 2 页；
 *  - `null` = 前驱页还没塑出来，判不出（新锚流 birth 时就是这种——`stepTempPrefill` 的
 *    `fillBackward` 跑完之后才会变成 1 或"更深"）。
 *
 *  已判定为更深时返回 2（与 `null` 同档，调用方只需区分 ==1）。
 */
private fun tempHeadDistancePages(ip: InProgressPagination): Int? = tempStateLock.withLock {
    // 指针越界是窗口腐败（不是"判不出"）：记 e 后仍回 null（调用方按不升档处理，不断链）。
    if (!tempPointerValid(ip)) {
        Logger.e(logTag, "tempHeadDistance corrupt pointer ch=${ip.chapterIndex} fwd=${ip.curIsForward} idx=${ip.curIndex} f=${ip.forwardPages.size} b=${ip.backwardPages.size}")
        return@withLock null
    }
    val cur = currentTempPage(ip) ?: return@withLock null
    headDistanceFrom(cur.blockStart, previousTempPage(ip)?.blockStart)
}

/** Probe 缝（D7-4）：[chapter] 的 temp 块水位，无活会话时为 null。
 *  第 4 档在临时表侧唯一的执行体就是这个水位的推进，读它即观测该档。 */
fun tempWatermarkForProbe(chapter: Int): Int? =
    tempStateLock.withLock { unitAt(chapter)?.inProgress?.shapedForwardTo }

fun tempWindowStateForProbe(chapter: Int): String? =
    tempStateLock.withLock { unitAt(chapter)?.inProgress?.let { "fwd=${it.curIsForward} idx=${it.curIndex} f=${it.forwardPages.size} b=${it.backwardPages.size} wl=${it.shapedForwardTo}" } }

/** 原则 §3.4 的判据表（纯函数）：由「当前页起始块」与「前驱页起始块」定出目标页距章首的页数 d。
 *
 *  - `curBlockStart == 0` → `0`：当前页就是章首页，已在交接点，同步承接，不升档。
 *  - `prevBlockStart == null` → `null`：前驱页尚未塑出/不在窗口，**判不出**，不升档
 *    （宁可落到基档，也不猜——猜错会把整章活提到第 2 档去挤占翻页）。
 *  - `prevBlockStart == 0` → `1`：前驱页是章首页，当前页正是第 2 页 → 交接点临近，加急档。
 *  - 其余 → `2`：已判定为更深，与 `null` 同档。
 *
 *  抽成纯函数是为了能测：判据表本身极易写错（平板实测已错过一次，见 [previousTempPage]）。 */
fun headDistanceFrom(curBlockStart: Int, prevBlockStart: Int?): Int? = when {
    curBlockStart == 0 -> 0
    prevBlockStart == null -> null
    prevBlockStart == 0 -> 1
    else -> 2
}

/** 指针页在阅读顺序上的前驱页（窗口内）。null = 指针在头边或前驱已被淘汰出窗口。 */
private fun previousTempPage(ip: InProgressPagination): TempPage? {
    val prevIdx = ip.curIndex - 1
    return if (ip.curIsForward) {
        if (prevIdx >= 0) ip.forwardPages.getOrNull(prevIdx)
        // 跨列表边界：阅读顺序是 `Bwd(末) … Bwd(0), [Pair], Fwd(0), Fwd(1) …`（见 [enforceTempWindow]），
        // 所以 Fwd(0) 的前驱在**后向列表的末尾**，不在 forwardPages[-1]。
        // 这个边界正是章首页场景：目标页 = Fwd(0)（锚点页），其前驱 = 章首页（Bwd 末，blockStart 0）。
        // 漏掉它会让 [tempHeadDistancePages] 在最该升档的时候返回 null —— 实测平板上
        // 「跳到章第 2 页」BOOST 一次都不触发，就是这里。
        else ip.backwardPages.lastOrNull()
    } else {
        ip.backwardPages.getOrNull(prevIdx)
    }
}


// ── 滚动派发（原则 §第二优先级「串行排全章」）──
//
// 为什么不是"一次性把整段序列 submit 出去，让池自己排队"：池只有 2 槽（`maxSlots`），但**队列
// 深度**和 submit 次数是另一回事。151 页的章意味着一次落位 150 次 `scheduler.submit`，每次一个
// `scope.launch` 去抢调度器 mutex + 一次按优先级线性插入 + 一次同键 `removeAll` 全队列扫描。
// 落位频率是每次翻页都触发，于是纯调度开销按 O(序列长 × 落位频率) 涨，而真正在跑的永远只有 2 个。
// 滚动派发把这个 O(序列长) 降到 O(窗口)：在途 2 个 + 队列 1 个，跑完一个补一个。
//
// 不改 [TaskScheduler]：补派发生在**任务体内**（`finally` 里），池自己的 `pumpLocked()` 随后会把
// 队列里那一个启动起来，槽位记账、抢占、同键去重全部沿用既有语义。
/** 同时在途的滚动任务上限。与池的 `maxSlots` 同为 2：并发由池封顶，滚动窗口只管"队列别太深"。 */
private val ROLLING_WINDOW = 2

/** 滚动序列里的一步。 */
private class RollingStep(val key: String, val priority: Int, val body: suspend () -> Unit)

/** 滚动序列：一次落位、整章全量预排的有序步骤表 + 已发出的游标。 */
private class RollingLane(val chapter: Int, val steps: List<RollingStep>) {
    @Volatile
    var cursor: Int = 0
}

/** **按章分槽**的在滚链表（key = 章号）。**不能是单一全局槽**：多章会同时在滚——翻页落位章 A
 *  的链还在跑，目录恢复 / 跨章落位又把章 B 派了一遍。单一槽下，任意一次 B 的新落位都会顶掉 A 的
 *  链，A 剩余的整章预排被静默掐断（真机日志：目录恢复循环每 ~40ms 落位一次 ch0/ch1，
 *  ch18 的 150 步链在第一步之后就再没补派过）。
 *
 *  分槽后回到改动前的语义：**不同章互不干涉**，同一次落位仍然只有一条链。 */
private val rollingLanes = HashMap<Int, RollingLane>()
private val rollingLaneLock = SyncLock()

/** 换代：新落位装入**新对象**，旧链在跑完当前步后经 [nextRollingStep] 的身份判定自行断链——
 *  不需要显式取消，也不会去 cancel 新链。 */
private fun installRollingLane(lane: RollingLane) {
    rollingLaneLock.withLock { rollingLanes[lane.chapter] = lane }
}

/** 原子地"取下一步"：换代判定 + 游标推进必须在同一个临界区，否则并发补派会各拿同一个游标、
 *  重复发同一个键。[SyncLock.withLock] 非内联，所以用返回值表达，不用块内 `return`。 */
private fun nextRollingStep(lane: RollingLane): RollingStep? =
    rollingLaneLock.withLock {
        if (rollingLanes[lane.chapter] !== lane) return@withLock null
        val s = lane.steps.getOrNull(lane.cursor) ?: return@withLock null
        lane.cursor = lane.cursor + 1
        s
    }

/** 往池里放至多 [budget] 步。任务体的 `finally` 每次再调一次（budget=1），于是稳态是
 *  「在途 2 + 队列 1」。已换代的链取不到步，自然停止补派。
 *
 *  被抢占（[notifyFlip] 的 `cancelLowerThan(PRIO_FLIP)`）时不补派：补派会在抢占刚发生完的
 *  空池里立刻塞回一个第 4 档任务，抵消抢占。判据用协程自身的 `isActive`——任务被取消时为 false。 */
private fun pumpRolling(lane: RollingLane, budget: Int) {
    var issued = 0
    while (issued < budget) {
        val step = nextRollingStep(lane) ?: return
        scheduler.submit(TaskScheduler.Task(
            key = step.key,
            priority = step.priority,
            block = {
                try {
                    step.body()
                } finally {
                    if (currentCoroutineContext().isActive) pumpRolling(lane, budget = 1)
                }
            },
        ))
        issued++
    }
}

/** P2.1: disk-path neighbor prefill after a landing (rolling-dispatch edition). No-op on the temp
 *  path (its own prefill owns it) and without a table.
 *  The whole neighbor SEQUENCE comes from [neighborSequence] (原则 §3.1): d=1 direction side at
 *  第2档, d=1 other side at 第3档, d≥2 at 第4档, and a d=1 neighbor falling OUTSIDE this chapter
 *  becomes a full-layout of the adjacent chapter at that side's tier (原则 §3.3).
 *  The sequence is **rolled** through [pumpRolling] — 原则 §第二优先级 says 「串行排全章」, and
 *  the pool (2 slots) is the only concurrency source; what changed is that the rest of the sequence
 *  is no longer *submitted* up front. A new landing in the SAME chapter installs a fresh lane and the
 *  old chain stops at its next step (same-key submit still cancels the in-flight twin); a landing in a
 *  DIFFERENT chapter gets its own lane and leaves this one alone.
 *  No start delay (R18 doctrine: cancel promptly instead of waiting out bursts). */
private fun scheduleWindowPrefill(unit: ChapterUnit, targetPage: Int, dir: Int) {
    val table = unit.paginationTable ?: return
    if (unit.inProgress != null) return
    val total = table.pages.size
    if (total <= 0) return
    val chapterIdx = unit.chapterIndex
    val lastChapter = chapters.size - 1
    val bc = layouter as? BoxChapterLayouter
    val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
    val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
    val hash = LayoutParamKey.fromProfile(profile, contentW, contentH).hash()
    // 原则 §3.1：整段邻页序列按 +1、−1、+2、−2…… 排到章尾。**滚动派发**，不再一次性全 submit
    // （[pumpRolling]）：旧的写法把 151 页的章变成 150 个 `pg:` 任务同时躺在池队列里，代价有三——
    //  ① 每次落位再来一次「全序列同键替换」= O(序列长) 的全队列扫描 + 同样多的协程启动去抢
    //    调度器锁（`submit` 每次一个 `scope.launch`），真机日志里这是每次落位几百 ms 的纯开销；
    //  ② 队列深度 = 序列长度，池只有 2 槽时后面 148 个任务是纯占位；
    //  ③ 与总则「串行排全章」的字面要求相悖。
    val steps = ArrayList<RollingStep>(total + 2)
    for (item in neighborSequence(targetPage, total, dir, chapterIdx, lastChapter)) {
        when (item) {
            is NeighborItem.Page -> steps.add(RollingStep(
                key = "pg:$chapterIdx:${item.index}",
                priority = item.tier,
                // 三页窗口 {反侧, 当前, 方向侧} 需要**两侧**邻页的版式都能被读到：总则第 2 条
                // 把 d=1 两侧都列为第二优先级（方向侧 = 第2档「下一页」、反侧 = 第3档「上一页」），
                // 两侧都装配 product 进 `pageCache`。用户层预栅格只补**方向侧**像素（反侧＝上一刻的
                // 当前页，池中命中），但反侧版式仍要备好——回翻时显示路径直接读它。
                body = {
                    prefillPageTask(
                        chapterIdx, item.index, targetPage,
                        assemble = item.tier == TaskScheduler.PRIO_PAGE_NEXT ||
                            item.tier == TaskScheduler.PRIO_PAGE_PREV,
                    )
                },
            ))
            is NeighborItem.EdgeChapter -> {
                if (bc == null || unitAt(item.chapter) == null) continue
                // 在途就不重派（同 [preflightChapter] 的 `pre:<章>` 口径）。**长任务不能靠同键替换
                // 保鲜**：池在同键新孪生到来时会取消在跑的旧孪生（`TaskScheduler.kt:128`），对单页
                // 塑形是对的（指针动了旧的就没意义），对整章全量则是灾难——每次落位都重启一遍几秒的
                // 整章活，两个槽被长期占死，第 7 档的章扫描永远排不上。紧急度已由在途那个满足了。
                if (scheduler.runningKeys.contains(edgeKey(item.chapter))) continue
                steps.add(RollingStep(
                    key = edgeKey(item.chapter),
                    priority = item.tier,
                    body = { b2ChapterTask(item.chapter, bc, contentW, contentH, hash) },
                ))
            }
        }
    }
    if (steps.isEmpty()) return
    val lane = RollingLane(chapterIdx, steps)
    installRollingLane(lane)
    Logger.w(
        logTag,
        "pg rolling ch=$chapterIdx page=$targetPage dir=$dir steps=${steps.size} window=$ROLLING_WINDOW",
    )
    pumpRolling(lane, budget = ROLLING_WINDOW)
}

/** d=1 章外兜底，**临时表侧**（原则 §3.3）。与 [scheduleWindowPrefill] 的磁盘表侧同一套规则，
 *  只是判据来自 temp 会话而非分页表：
 *  - **缺上一页**：当前页 `blockStart == 0`，本章内没有更靠前的页 → 加急整章排上一章（第 3 档）。
 *  - **缺下一页**：块水位已到本章末尾且当前页已抵末尾 → 加急整章排下一章（第 2 档）。
 *
 *  两处都用**会话冻结的参数快照**（[InProgressPagination.profileSnapshot]，P9）算 hash——
 *  与 temp 页自身同参数，兜底排出来的表才能被后续落位复用；用会变的 `profile` 会算出
 *  另一个 hash，白排一遍。 */
private fun scheduleTempEdgePrefill(unit: ChapterUnit, ip: InProgressPagination) {
    val bc = layouter as? BoxChapterLayouter ?: return
    val chapterIdx = unit.chapterIndex
    val lastChapter = chapters.size - 1
    val totalBlocks = ip.prepare.totalBlocks
    val contentW = ip.contentW
    val contentH = ip.contentH
    val hash = LayoutParamKey.fromProfile(ip.profileSnapshot, contentW, contentH).hash()
    val (curBlockStart, curBlockEnd, atTail) = tempStateLock.withLock {
        // 坏指针直接不派邻章加急：判法同 tempHeadDistancePages（越界记 e，不断链）。
        if (!tempPointerValid(ip)) {
            Logger.e(logTag, "scheduleTempEdgePrefill corrupt pointer ch=${ip.chapterIndex} fwd=${ip.curIsForward} idx=${ip.curIndex}")
            return@withLock Triple(-1, -1, false)
        }
        val cur = currentTempPage(ip) ?: return@withLock Triple(-1, -1, false)
        Triple(cur.blockStart, cur.blockEndExclusive, ip.shapedForwardTo >= totalBlocks)
    }
    if (curBlockStart < 0) return
    fun submitEdge(beyond: Int, prio: Int) {
        if (beyond !in 0..lastChapter) return
        if (unitAt(beyond) == null) return
        scheduler.submit(TaskScheduler.Task(
            key = edgeKey(beyond),
            priority = prio,
            block = { b2ChapterTask(beyond, bc, contentW, contentH, hash) },
        ))
    }
    if (curBlockStart == 0) submitEdge(chapterIdx - 1, TaskScheduler.PRIO_EDGE_BACKWARD)
    if (atTail && curBlockEnd >= totalBlocks) {
        submitEdge(chapterIdx + 1, TaskScheduler.PRIO_EDGE_FORWARD)
    }
}

/** P2.1: one neighbor page — warm its blocks, merge-publish L2, and (priority side only)
 *  assemble + store the product. Skips (no-op) on temp-live / missing table / out-of-range:
 *  the S7 step-3 scope, checked live (state moves between dispatch and run). */
/** 落位级共享 light prepare：同一落位派发的几十个 `pg:` 任务曾各跑一次全章 light
 *  cascade（去 16 截断后放量，池槽会被 cascade 淹没）。同（章，表 hash，profile）
 *  inputs 相同 → 首个任务计算并发布，后续复用。last-writer-wins + 不可变结果，
 *  竞态 benign；参数/视口一变 key 即失配，回落重算（正确性仍由各任务既有守卫兜底）。 */
private data class WindowPrepKey(val chapter: Int, val hash: Long, val profile: TypographicProfile)
@Volatile
private var windowPrepCache: Pair<WindowPrepKey, LightPrepare>? = null

private suspend fun prefillPageTask(chapterIdx: Int, page: Int, anchor: Int, assemble: Boolean) {
    val u0 = unitAt(chapterIdx) ?: return
    bindChapterFor(u0)
    val table = u0.paginationTable ?: return
    if (u0.inProgress != null) return
    if (page !in 0 until table.pages.size) return
    val hash = table.paramHash
    val profileSnap = profile
    val markup = u0.markup ?: return
    val bc = layouter as? BoxChapterLayouter ?: return
    val ctx = currentCoroutineContext()
    // Snapshot guards + dims under a short lock; shaping runs lock-free into a private map.
    val snap = layoutMutex.withLock {
        if (u0.inProgress != null || u0.paginationTable !== table || u0.paramHash != hash) null
        else Triple(
            u0.cssBundle,
            (viewW - profileSnap.marginLeft - profileSnap.marginRight).coerceAtLeast(16),
            (viewH - profileSnap.marginTop - profileSnap.marginBottom).coerceAtLeast(16),
        )
    } ?: return
    val (css, cw, chh) = snap
    val rec = table.pages[page]
    val lo = rec.blockStart.coerceAtLeast(0)
    if (rec.blockEndExclusive <= lo) return
    // 同一落位的任务共享 light prepare（见 windowPrepCache）：各跑一次 cascade 是纯浪费。
    // 组装用的第二次 prepareLight 与此处同参，一并复用（原代码重复计算）。
    val prepKey = WindowPrepKey(chapterIdx, hash, profileSnap)
    val lp = windowPrepCache?.takeIf { it.first == prepKey }?.second
        ?: bc.prepareLight(markup, css, profileSnap, cw, u0.structureCache, chh, onStructureComputed = { h, cw, chh -> saveUnitStructure(u0, h, cw, chh) })
            .also { windowPrepCache = prepKey to it }
    val local = HashMap<Int, ParagraphShapeRef>()
    for (b in lo until rec.blockEndExclusive) {
        ctx.ensureActive()
        if (b < 0 || b >= lp.totalBlocks) continue
        if (!local.containsKey(b)) local[b] = bc.tempShape(local, lp, b, profileSnap)
    }
    if (local.isEmpty()) return
    publishPrefillShapes(chapterIdx, hash, local)
    // Non-priority side stays L2-warmed (shaping covered); assembly is priority-side only —
    // doubling it per landing cost more (device-measured) than the backward-hit rate covers.
    if (!assemble) return
    // Assemble with the just-warmed shapes as L1 (same params, deterministic) so assembly is
    // pure assembly cost. Reuses the shared landing prepare (same args as above, no second cascade).
    val prod = try {
        bc.incrementalLayoutForPage(
            prepare = lp,
            profile = profileSnap,
            contentW = cw,
            contentH = chh,
            table = table,
            targetPage = page,
            pagesToShape = 1,
            cache = local,
            prefillL2 = null,
        )
    } catch (e: CancellationException) {
        return // superseded — expected, stop burning CPU
    } catch (e: Exception) {
        Logger.e(logTag, "win-prefill assemble ch=$chapterIdx page=$page FAIL ${e.message}")
        return
    }
    // Live-object identity baseline — store only if no flip rebound meanwhile.
    val liveLayout = u0.layout
    val liveSlices = u0.pageSlices
    val stored = layoutMutex.withLock {
        val u2 = unitAt(chapterIdx)
        if (u2 == null || u2.inProgress != null || u2.paginationTable !== table ||
            u2.paramHash != hash || u2.layout !== liveLayout || u2.pageSlices !== liveSlices
        ) {
            false
        } else {
            u2.pageCache[page] = ChapterUnit.PageProduct(prod.layout, prod.slices, hash)
            u2.pageCache.keys.toList().forEach { k ->
                if (k < anchor - 2 || k > anchor + 2) u2.pageCache.remove(k)
            }
            true
        }
    }
    if (stored) {
        Logger.w(logTag, "win-prefill ch=$chapterIdx page=$page cached")
        // ±1 页分页（装配）完成 → 抛信号让用户层立即补栅。两侧都经此路径（assemble 为真）：
        // 用户层只补方向侧那一张像素，反侧的版式供落位/回翻命中 `pageCache` 快路径。
        // 本函数跑在调度器线程，`onPrefillReadyListener` 会把执行切回 UI 线程（见 TabletReaderHost）。
        notifyPrefillReady(chapterIdx)
    }
}

/** P2.1: merge warmed blocks into the published L2 snapshot (copy-on-write replace). */
private val prefillPublishMutex = Mutex()

private suspend fun publishPrefillShapes(chapterIdx: Int, hash: Long, extra: Map<Int, ParagraphShapeRef>) {
    if (extra.isEmpty()) return
    prefillPublishMutex.withLock {
        val cur = windowPrefillL2
        val merged = if (cur != null && cur.chapterIndex == chapterIdx && cur.paramHash == hash) {
            HashMap(cur.shapes).also { it.putAll(extra) }
        } else HashMap(extra)
        windowPrefillL2 = WindowPrefillShapes(chapterIdx, hash, merged)
    }
    Logger.w(logTag, "win-prefill ch=$chapterIdx +${extra.size} blocks")
}

/** R3: read-only L2 lookup for the incremental path — keyed by chapter + table hash. */
private fun windowPrefillShapesFor(unit: ChapterUnit, table: ChapterPaginationTable): Map<Int, ParagraphShapeRef>? {
    val s = windowPrefillL2 ?: return null
    if (s.chapterIndex != unit.chapterIndex || s.paramHash != table.paramHash) return null
    return s.shapes
}

/** Window page spans shared by [tempWindowDiag] and the FLIP diagnostic (single format). */
private fun tempWinSpans(pages: List<TempPage>): String =
    if (pages.isEmpty()) "-" else pages.joinToString(",") {
        "b${it.blockStart}[${it.slice.charStart},${it.slice.charEnd})"
    }

/** R2: one-line temp pointer + window snapshot for cross-chapter diagnosis. */
private fun tempWindowDiag(ip: InProgressPagination): String {
    return "reason=${lastTempNavNull ?: "?"} " +
        "ptr=${if (ip.curIsForward) "F" else "B"}${ip.curIndex} " +
        "headLanded=${ip.headLanded} headReached=${ip.headReached} " +
        "anchorBlk=${ip.anchorBlockStart} shapedFwdTo=${ip.shapedForwardTo} " +
        "fwd[${tempWinSpans(ip.forwardPages)}] bwd[${tempWinSpans(ip.backwardPages)}]"
}

/** 前向塑形并接窗（排版层-上/增量分页）：从给定原点塑一页，记后继原点、推水位、追加到前向尾。
 *  返回追加页。null 即塑形器回归，抛（既有 fail-fast，不当越界吞）。 */
private fun shapeAndAppendForward(
    ip: InProgressPagination, bc: BoxChapterLayouter, startBlock: Int, startLine: Int, failTag: String,
): TempPage {
    val prep = ip.prepare
    val fwd = bc.shapeTempPageForward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, startBlock, ip.shapes, startLine)
        ?: error("$failTag ch=${ip.chapterIndex} start=($startBlock,$startLine) waterline=${ip.shapedForwardTo} total=${prep.totalBlocks}")
    ip.shapedForwardTo = fwd.nextBlock
    ip.forwardFromLine = fwd.nextLine
    val page = fwd.page.copy(resumeBlock = fwd.nextBlock, resumeLine = fwd.nextLine)
    ip.forwardPages.add(page)
    recordChainEdge(ip, page)
    return page
}

private fun tempNav(unit: ChapterUnit, ip: InProgressPagination, slice: PageSlice, dir: Int): Pair<Int, PageSlice>? {
    bindChapterFor(unit)
    lastTempNavNull = null
    // C2-P2b-3: `withLock` 非内联，块里裸 `return` 全改 `return@withLock`，外层 `return` 接住表达式值。
    return tempStateLock.withLock {
    // Serialize with the background temp-prefill so list mutations + the shared shape cache are safe.
    // layouter 是构造期装配不变量；翻页热路径上类型不符即抛，不降级成"越章"。
    val bc = checkNotNull(layouter as? BoxChapterLayouter) { "tempNav without BoxChapterLayouter ch=${unit.chapterIndex}" }
    // P12 race hardening: [navigateAdjacentPage] snapshots [ip] WITHOUT this lock, so a relayout / leave
    // can bind a NEW session between that snapshot and this lock. Navigating the detached old session
    // would return a page that is no longer on the live session — 页面缺失/混乱. Relocate the displayed
    // slice in the LIVE session and navigate that one instead.
    val live = unit.inProgress ?: run { lastTempNavNull = "no-session"; return@withLock null }
    if (live !== ip) locateTempPosition(live, slice)
    val ip = live
    // NOTE: there is deliberately NO `headReached && dir<0` shortcut here. headReached remembers that a
    // background prefill / backward flip once produced a page starting at block 0, but it says nothing
    // about the CURRENT pointer position — the reader may be mid-forwardPages with valid in-chapter
    // pages behind. A blanket shortcut would hijack an ordinary in-chapter backward flip and jump it
    // cross-chapter the moment the head was ever pre-shaped (缺陷A). The true head boundary is handled
    // position-sensitively by [tempNavBackwardPointerStep] (endInc/endFor <= 0 → Boundary).
    val prep = ip.prepare
    if (dir > 0) {
        if (ip.curIsForward) {
            val next = ip.curIndex + 1
            if (next < ip.forwardPages.size) {
                ip.curIndex = next
            } else {
                if (ip.shapedForwardTo >= prep.totalBlocks) { lastTempNavNull = "fwd-tail"; Logger.w(logTag, "tempNav forward tail → null ch=${ip.chapterIndex}"); return@withLock null }
                // 连续页以当前（源）页分页点为原点（增量分页原则）；推不出（源页非法）则回退
                // 旧路（水位/void），落页校验 + 修复层兜底。漂移记 w（canary：按理恒为零）。
                val origin = tempResumeAfter(ip, bc, slice)
                if (origin != null) {
                    if (origin.first != ip.shapedForwardTo || origin.second != ip.forwardFromLine) {
                        Logger.w(logTag, "tempNav origin-drift ch=${ip.chapterIndex} derived=(${origin.first},${origin.second}) waterline=(${ip.shapedForwardTo},${ip.forwardFromLine})")
                    }
                    // 边界已前查：此处的 null 只可能是塑形器回归。抛，不当越界吞（丢内容翻页）。
                    shapeAndAppendForward(ip, bc, origin.first, origin.second, "tempNav fwd-shape-fail")
                    ip.curIndex = ip.forwardPages.size - 1
                } else if (ip.shapedForwardTo < 0) {
                    // 水位失效（前向侧曾被裁空）：无尾可续，从水位盲塑必崩（startBlock 越界）/必跳页。
                    // 后向非空则走 RE-ROOT 同式重建（后继原点，行切亦可）；双空即结构性 miss（S7 废表重走）。
                    val back0 = ip.backwardPages.firstOrNull()
                        ?: run { lastTempNavNull = "fwd-void"; return@withLock null }
                    val vorigin = tempResumeAfter(ip, bc, back0.slice)
                    val startBlock = vorigin?.first ?: back0.blockEndExclusive
                    val startLine = vorigin?.second ?: 0
                    if (startBlock < 0 || startBlock >= prep.totalBlocks) { lastTempNavNull = "reroot-tail"; Logger.w(logTag, "tempNav forward tail → null ch=${ip.chapterIndex}"); return@withLock null }
                    Logger.w(logTag, "tempNav RE-ROOT fwd (void-waterline) ch=${ip.chapterIndex} startBlock=$startBlock startLine=$startLine")
                    shapeAndAppendForward(ip, bc, startBlock, startLine, "tempNav reroot-shape-fail")
                    ip.curIndex = ip.forwardPages.size - 1
                } else {
                    // 边界已前查：此处的 null 只可能是塑形器回归。抛，不当越界吞（丢内容翻页）。
                    shapeAndAppendForward(ip, bc, ip.shapedForwardTo, ip.forwardFromLine, "tempNav fwd-shape-fail")
                    ip.curIndex = ip.forwardPages.size - 1
                }
            }
        } else {
            if (ip.curIndex > 0) ip.curIndex -= 1
            else {
                // Step past the last backward page into forwardPages[0]. That page may have been
                // EVICTED (the bounded window dropped the whole forward side while the reader was deep
                // backward) — re-root it on demand from the page at hand: the forward neighbor resumes
                // at the backward page's successor origin (block boundary, or mid-block line for a
                // line-cut page). (The anchor torn pair can never trigger this: pair atomicity keeps
                // backward[0] + forward[0] together, so the forward side is non-empty whenever the
                // nearest backward page is line-cut.)
                if (ip.forwardPages.isEmpty()) {
                    // 后向首个页若行切（撕裂对/行尾），块边界重启会丢其尾行：以后继原点为准，
                    // 推不出则回退旧式（落页校验 + 修复层兜底）。
                    val back0 = ip.backwardPages.firstOrNull()
                    val origin = back0?.let { tempResumeAfter(ip, bc, it.slice) }
                    val startBlock = origin?.first ?: back0?.blockEndExclusive ?: ip.shapedForwardTo
                    val startLine = origin?.second ?: 0
                    if (startBlock < 0 || startBlock >= prep.totalBlocks) { lastTempNavNull = "reroot-tail"; Logger.w(logTag, "tempNav forward tail → null ch=${ip.chapterIndex}"); return@withLock null }
                    Logger.w(logTag, "tempNav RE-ROOT fwd ch=${ip.chapterIndex} startBlock=$startBlock startLine=$startLine")
                    // Re-root the absolute forward front to this new head edge; subsequent flips append
                    // from here instead of the (deep, evicted) old front.
                    shapeAndAppendForward(ip, bc, startBlock, startLine, "tempNav reroot-shape-fail")
                }
                ip.curIsForward = true
                ip.curIndex = 0
            }
        }
    } else {
        // backward. The step's "anchor" inputs are the WINDOW HEAD-EDGE page (forward[0]) — the true
        // anchor until the bounded window slides past it (P10) — so backward packing stays correct
        // after far-end eviction redefines the head edge.
        val head = headEdgePage(ip)
        val headBlockStart = head?.blockStart ?: ip.anchorBlockStart
        val headLineCharStart = head?.slice?.charStart ?: ip.anchorLineCharStart
        val headBlockCharStart = prep.globalCharStarts[headBlockStart].toInt()
        val deepest = ip.backwardPages.lastOrNull()
        val deepestBlockCharStart = deepest?.let { prep.globalCharStarts[it.blockStart].toInt() } ?: -1
        when (val step = tempNavBackwardPointerStep(
            ip.curIsForward, ip.curIndex, headBlockStart, headLineCharStart, headBlockStart,
            headBlockCharStart, ip.backwardPages.size,
            deepest?.blockStart, deepest?.slice?.charStart ?: -1, deepestBlockCharStart,
        )) {
            is TempNavBackwardStep.Move -> {
                ip.curIsForward = step.curIsForward
                ip.curIndex = step.curIndex
                // 现象2 (内容不满): reaching block 0 (a prefilled/live backward page) IS the chapter-head
                // arrival — resolve it on THIS flip so the reader lands the FULL head page. The pointer
                // Move itself can land on a pre-shaped head page, so arrival is detected by the TARGET
                // page, not by the packing front (which already reached 0 ∈ backwardPages).
                if (!step.curIsForward) {
                    val target = ip.backwardPages.getOrNull(step.curIndex)
                    if (target != null && target.blockStart == 0) {
                        val landing = resolveHeadArrival(unit, ip)
                        if (landing == null) lastTempNavNull = "head-move-null"
                        return@withLock landing
                    }
                }
            }
            is TempNavBackwardStep.Shape -> {
                // 纯判据已保证 endExclusive>0 且不超全章：null 即回归，抛，不当边界吞。
                val p = shapeBackwardPage(ip, bc, step.endExclusive, step.lineCut)
                    ?: error("tempNav bwd-shape-fail ch=${ip.chapterIndex} endExclusive=${step.endExclusive} lineCut=${step.lineCut}")
                ip.backwardPages.add(p)
                if (p.blockStart == 0) ip.headReached = true
                ip.curIsForward = false
                ip.curIndex = ip.backwardPages.size - 1
                Logger.w(logTag, "tempNav backward shape ok blockStart=${p.blockStart} headReached=${ip.headReached} endExclusive=${step.endExclusive}")
                // 现象2: the whole-block packing descending to block 0 produces the SPARSE head page
                // (head blocks under-fill contentH). Never show it — resolve the head arrival on THIS flip
                // (canonical first page, or re-root temp at char 0 = a full head page per shapeAnchorPageForward).
                if (p.blockStart == 0) {
                    val landing = resolveHeadArrival(unit, ip)
                    if (landing == null) lastTempNavNull = "head-shape-null"
                    return@withLock landing
                }
            }
            TempNavBackwardStep.Boundary -> {
                // Step 3 (现象3): the TRUE chapter-head boundary. Block-0 arrivals are already resolved
                // on the arrival flip (Move/Shape above); this branch covers the remaining boundary
                // sources — the anchor itself sits AT the head (no backward page can precede it and the
                // arrival already happened at birth: headLanded is set in startAnchorStream), or the
                // reader is ON the (already re-rooted) full head page. A born-at-head session therefore
                // crosses on the FIRST boundary flip (headLanded=true from birth); a genuine arrival
                // re-roots first (headLanded set by resolveHeadArrival) and crosses on the next one —
                // never re-landing the identical page as a wasted flip.
                //   - canonical ready: finalize the temp session in place (bind the disk/full table) and
                //     land on the canonical page containing the current char — at the head that is the
                //     FULL canonical first page (char0..contentH), never a sparse head.
                //   - canonical not ready: re-root the temp stream AT the chapter head —
                //     shapeAnchorPageForward(0) fills the head page forward to contentH, so the head
                //     page is full and the forward stream rebuilds from char 0. Mark the session
                //     headLanded so the NEXT boundary (reader already on the full head) crosses straight
                //     to the previous chapter instead of re-landing the identical page (a wasted flip).
                if (ip.headLanded) {
                    lastTempNavNull = "head-landed"
                    Logger.w(logTag, "tempNav boundary head-landed → cross-chapter ch=${ip.chapterIndex}")
                    return@withLock null
                }
                val landing = resolveHeadArrival(unit, ip)
                if (landing == null) lastTempNavNull = "head-arrival-null"
                return@withLock landing
            }
        }
    }

    // P10 (§6.2): every pointer move slides the window and evicts the far end (never the current page,
    // never a torn pair), so the session stays bounded at ~±TEMP_WINDOW_DEPTH pages.
    enforceTempWindow(ip)
    currentTempPage(ip)?.let { setCurrentTempPage(unit, ip, it) }
    val cur = ip.currentSlice?.let { unit.chapterIndex to it }
    if (cur == null) lastTempNavNull = "no-current"
    // 前向落页必须与源页相接（排版层-上/增量分页）：失配即跳页，先按需补塑修复（S7 连续 miss，
    // 不废会话）；修不好才记 e 保留原落页。
    if (dir > 0 && cur != null && !tempPagesTile(slice, cur.second)) {
        repairTempForwardGap(unit, ip, bc, slice, cur.second)?.let { return@withLock it }
        Logger.e(logTag, "tempNav fwd-gap ch=${unit.chapterIndex} src=char[${slice.charStart},${slice.charEnd}) dst=char[${cur.second.charStart},${cur.second.charEnd}) fwd=[${tempWinSpans(ip.forwardPages)}] bwd=[${tempWinSpans(ip.backwardPages)}]")
    }
    return@withLock cur
    }
    // Unreachable: every path above returns inside the lock. Kept to satisfy the compiler's
    // control-flow analysis (non-inline `withLock` still needs the function-level tail).
    return null
}

/**
 * 现象2 fix (回章首页内容不满): the chapter-head ARRIVAL. Called on the flip that first reaches the true
 * chapter head (the backward page with blockStart==0), whether via [TempNavBackwardStep.Shape] (the
 * whole-block packing just produced the sparse head page) or [TempNavBackwardStep.Move] (a prefilled
 * head page). Resolving HERE — on the arrival flip, instead of one flip later at the Boundary — means
 * the reader lands directly on the FULL head page, never on the sparse [0,k) block page that under-fills
 * contentH. The old table is discarded and the session is re-anchored at char 0 exactly as the spec
 * requires ("回章首页时若有权威表未就绪，应废弃之前的临时表，以章头为锚点重启增量排版"):
 *   - canonical ready: finalize the temp session in place (bind the disk/full table) and land on the
 *     canonical page containing char 0 — the FULL canonical first page.
 *   - canonical not ready: re-root the temp stream AT the chapter head —
 *     shapeAnchorPageForward(0) fills the head page forward to contentH, so the head page is full and
 *     the forward stream rebuilds from char 0. Mark the session headLanded so the NEXT boundary (reader
 *     already on the full head) crosses straight to the previous chapter, not re-landing the same page.
 *     Sub-case: 本章磁盘表已就绪，但**本会话**没拿到正典副本（B1 被 skip-fresh，或上一代会话的 B1 晚到
 *     ——`ip` 已换代）。此时仍走重根：章首那一页两条路径逐字同源（同一段 `paginateFrom` 从 char 0 起
 *     切），读者看到的内容与磁盘表那一页相同，只是会话继续用临时表。字面偏离总则 1(d)，理由与代价见
 *     `docs/分页高度约束与断页规则-方案.md` §12.7 结论 2；这里记 w 以便落盘日志可判。
 * Returns null when the caller should fall through to cross-chapter.
 */
private fun resolveHeadArrival(unit: ChapterUnit, ip: InProgressPagination): Pair<Int, PageSlice>? {
    if (ip.canonicalLayout != null && ip.canonicalSlices.isNotEmpty()) {
        val posChar = 0 // arriving AT the head: land on the canonical page containing char 0 (page 0)
        finalizeTempOnLeave(unit, ip)
        val landing = unit.pageSlices.firstOrNull { it.charStart <= posChar && it.charEnd > posChar }
            ?: unit.pageSlices.firstOrNull()?.also {
                // 正典表首页空洞（首包容失败）：鲁棒着陆保留，但记 e（表错位）。
                Logger.e(logTag, "resolveHeadArrival canonical head hole ch=${ip.chapterIndex}, fallback first page")
            }
        if (landing != null) {
            Logger.w(logTag, "tempNav head-arrival → canonical head page ch=${ip.chapterIndex}")
            return unit.chapterIndex to landing
        }
        return null
    }
    if (unit.paginationTable?.paramHash == ip.paramHash) {
        // 表其实已就绪（本会话没拿到副本）：仍按「未就绪」重根——重根后章首页与磁盘表那一页逐字同源，
        // 读者可见内容无差（§12.7 结论 2）。此行是给落盘日志的判断标记，不是错误。
        Logger.w(logTag, "tempNav head-arrival → re-root temp stream (disk table already fresh, no session copy) ch=${ip.chapterIndex}")
    } else {
        Logger.w(logTag, "tempNav head-arrival → re-root temp stream at chapter head ch=${ip.chapterIndex}")
    }
    // layouter 装配不变量：头到达重根无处可降级（返回 null 会把读者丢到上一章），抛。
    val bc = checkNotNull(layouter as? BoxChapterLayouter) { "resolveHeadArrival without BoxChapterLayouter ch=${ip.chapterIndex}" }
    startAnchorStream(unit, 0, ip.prepare, bc, ip.paramHash, ip.contentW, ip.contentH)
    unit.inProgress?.headLanded = true
    val head = unit.inProgress?.currentSlice ?: ip.forwardPages.firstOrNull()?.slice
    if (head != null) {
        Logger.w(logTag, "tempNav head-arrival → full head page ch=${ip.chapterIndex} char=[${head.charStart},${head.charEnd})")
        return unit.chapterIndex to head
    }
    // 重根后仍无头页：startAnchorStream 出生失败。调用方当普通边界越章，不断链；记 e。
    Logger.e(logTag, "resolveHeadArrival re-root headless ch=${ip.chapterIndex} → cross-chapter")
    return null
}

/** When the reader leaves the temp table (reached the chapter head / tail / crosses chapters), abandon
 *  the temp pagination and switch to the disk (canonical) pagination per S5 ("翻到章头或离开→废除临时表、
 *  启用磁盘分页表"). Binding happens here on leave — never mid-chapter — so the current page is not
 *  re-rendered in place while the temp session is active. */
private fun finalizeTempOnLeave(unit: ChapterUnit, ip: InProgressPagination) {
    // 就绪判据与 [resolveHeadArrival] 一致：`layout` 非空 **且** slices 非空。两个字段都 `@Volatile`
    // 且写侧固定先 slices 后 layout，故「layout 非空 + slices 空」按说不该出现；此处仍按不变式判，
    // 真出现也只是走下面的磁盘表分支（比 `bindFull(layout, emptyList())` 出零页单元安全）。
    if (ip.canonicalLayout != null && ip.canonicalSlices.isNotEmpty()) {
        unit.bindFull(ip.canonicalLayout!!, ip.canonicalSlices)
        unit.clearInProgress()
        Logger.w(logTag, "temp 作废→磁盘 ${ctx(unit)} pages=${ip.canonicalSlices.size}")
        // Step 2 (temp→canonical handoff continuity): the handoff must never skip content the temp
        // session already showed. The canonical page that will contain the temp position must start AT
        // OR BEFORE the temp page's charStart (a landing page starting after it would drop the temp
        // page's beginning on re-entry). Structurally guaranteed by char-range containment below; the
        // check is a canary so a future boundary-source regression fails loudly, not silently.
        val tempStart = ip.currentSlice?.charStart
        if (tempStart != null) {
            val landing = ip.canonicalSlices.firstOrNull { it.charStart <= tempStart && it.charEnd > tempStart }
            if (landing != null && landing.charStart > tempStart) {
                Logger.w(logTag, "temp 作废→磁盘 CONTINUITY-FAIL ${ctx(unit)} tempStart=$tempStart " +
                    "canonicalLanding=[${landing.charStart},${landing.charEnd}) (temp 页首段会在重进时被跳过)")
            }
        }
    } else {
        // 本会话没拿到正典副本，但**本章磁盘表可能已就绪**：B1 的 skip-fresh 命中（另一路已落表），
        // 或上一代会话的 B1 在读者离开后才落盘（`ip` 已换代，`unit` 上的表才是权威）。此时按总则 1
        // 「若本章磁盘表已就绪，则立即切换到磁盘表」——**保留该表**，只清临时会话与旧版式：下一次
        // 进入即磁盘命中（[buildLayout] 用内存表直接塑目标页，连回读缓存文件都省了）。丢掉它就等于
        // 磁盘表已就绪却作废重排。
        val keep = unit.paginationTable?.takeIf { it.paramHash == ip.paramHash }
        unit.invalidateLayout()
        if (keep != null) {
            unit.bindPaginationTable(keep)
            Logger.w(logTag, "temp 作废→磁盘 ${ctx(unit)} pages=${keep.pages.size} (leave 时表已就绪; 会话副本未及发布/已换代)")
        } else {
            // Canonical not ready yet at leave: clear the temp session. Under P3 (queue-not-kill) the
            // chapter's own B1 is NOT cancelled by another chapter's dispatch, so it keeps running and
            // persists on the canonical thread even after we leave — the next entry into this chapter is
            // therefore usually a disk hit (S5), not a re-temp.
            Logger.w(logTag, "temp 作废→重排 ${ctx(unit)} (canonical 未就绪; P3 B1 仍在后台落盘)")
        }
    }
}

/** P11 (S5 leave): finalize [unit]'s temp session if one is still present, serialized with in-session
 *  temp navigation — [tempNav]/[stepTempPrefill]/the anchor-commit all hold [tempStateLock]. Idempotent:
 *  a no-op once the session has been cleared. The flip leave path and the reader's jump paths route here. */
private fun finalizeTempSession(unit: ChapterUnit) {
    tempStateLock.withLock {
        (unit.inProgress ?: return@withLock).let { finalizeTempOnLeave(unit, it) }
    }
}

/** P11 public entry for the reader's three jump paths (TOC / seek / prev-next chapter / open book):
 *  abandon [chapter]'s live temp session BEFORE the jump resolves, so re-entering it can never land on the
 *  old anchor (perceived as a position bounce). Canonical ready → bind + clear; not ready → invalidate
 *  (P3 keeps its B1 persisting in the background, so the next entry is usually a disk hit). No-op when the
 *  chapter has no session; safe to call repeatedly (P6's gate may re-submit).
 *
 *  Save-ordering contract (C1-2; digests TODO「阅读定位不稳定」): progress persistence must ALWAYS go
 *  through this first ([TabletReaderHost.onSaveProgress] does) and only then read the displayed
 *  slice's char. The persisted char is therefore canonical-authoritative whenever the disk table is
 *  ready; a temp char is persisted only while canonical is still in flight (next entry re-lays-out
 *  rather than trusting it). The temp→canonical handoff canary in [finalizeTempOnLeave] fails loudly
 *  if a landing would ever start after the shown temp position. */
fun finalizeOnLeave(chapter: Int) {
    unitAt(chapter)?.let { finalizeTempSession(it) }
}

/** Cross-chapter flip landing (shared by the temp-exhausted and the normal pageSlices paths): walks
 *  [direction] from [fromChapter] to the first content-bearing chapter and returns its ENTRY page —
 *  first page forward, LAST page backward ([backwardEntryAnchorChar]). Skips blank/opening chapters.
 *  The target's markup is parsed BEFORE its first layout so the backward tail anchor can be computed
 *  ahead of building (no more head-anchored first build). */
private suspend fun crossChapterLanding(fromChapter: Int, direction: Int): Pair<Int, PageSlice>? {
    var next = fromChapter + direction
    // R2/D3: legit empty-chapter skips ride along so they are distinguishable from spurious cross jumps.
    val skipped = ArrayList<Int>()
    // R6: current layout params — stale tables are dropped before the anchor reads them
    // (backwardEntryAnchorChar below runs before ensureChapterLayout). Never kills live temp.
    val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
    val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
    val currentHash = LayoutParamKey.fromProfile(profile, contentW, contentH).hash()
    while (next in 0 until chapters.size) {
        val unit = unitAt(next)
        val markup = if (unit != null) ensureMarkup(next) else null
        if (unit == null || markup == null) { skipped.add(next); next += direction; continue }
        if (unit.inProgress == null) unit.invalidateForParam(currentHash)
        if (!markup.hasSignificantText()) { skipped.add(next); next += direction; continue }
        val anchorChar = if (direction > 0) 0 else backwardEntryAnchorChar(unit, markup)
        ensureChapterLayout(next, anchorChar)
        var page = if (direction > 0) unit.pageSlices.firstOrNull() ?: unit.inProgress?.currentSlice
                   else unit.pageSlices.lastOrNull() ?: unit.inProgress?.currentSlice
        // A disk-hit landing may pick the LAST table page whose slice is an UNSHAPED stub (firstLine < 0)
        // when the anchor char fell outside the table's coverage (a page-0 window was shaped instead of
        // the tail). Re-ensure around the picked page's own charStart — always inside the table — so the
        // tail window gets shaped before we hand it to the renderer.
        if (page != null && page.firstLine < 0 && unit.paginationTable != null) {
            ensurePageRangeShaped(unit, page.charStart)
            page = if (direction > 0) unit.pageSlices.firstOrNull() else unit.pageSlices.lastOrNull()
        }
        if (page != null) {
            if (skipped.isNotEmpty()) Logger.w(logTag, "flip: 跨章跳过空章 dir=$direction from ch=$fromChapter skipped=$skipped")
            Logger.w(logTag, "flip: 跨章落位 dir=$direction -> ${ctx(unit)} page=${pageKind(page)} isTemp=${unit.inProgress != null}")
            return next to page
        }
        next += direction
    }
    Logger.w(logTag, "flip: 到书${if (direction > 0) "末" else "首"}边界，无目标 ch=$fromChapter")
    return null
}

/** Background completion of the canonical relayout: persist the disk table and keep it ready for the
 *  leave-time hand-off. The anchor-temp pages remain the live pagination for the whole session — the
 *  canonical (correct line-level) table is only activated when the reader reaches the chapter head /
 *  crosses chapters, via [finalizeTempOnLeave]. The current page is never re-rendered mid-chapter, so
 *  the temp↔canonical boundary cannot produce an in-place redraw or flash. */
    /** P1f: single source for freshness checks (R6 guards + completion gates below). */
    private fun currentParamHash(): Long {
        val cw = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val chh = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        return LayoutParamKey.fromProfile(profile, cw, chh).hash()
    }

    /**
     * 当前版式指纹（与磁盘分页表的 `paramHash` 同源，**含版心尺寸**）。
     *
     * 设置面板门控用：边距、字号、行距……任何一项变了都算换版面，必须整书重来。
     * 以前门控只看「不带宽高的 `LayoutParamKey`」，改边距时指纹不变 → 关面板不整书重排，
     * 旧表被清、新表没人排，页面页码（与目录页码）就从此停在半截。
     */
    fun layoutParamHash(): Long = currentParamHash()

/**
 * P2.6 状态表落点决议（替代 P1f 的计算式状态视图）：`ChapterStatus`/`PageState` 视图对已删——
 * 它们零消费者，只是三处派发守卫各自判据的第四份拷贝。三处守卫即状态表：
 * [scheduleWindowPrefill] 的有表门、[b2ChapterTask] 的 skip-fresh、[ensurePageRangeShaped] 的
 * pageCache fast-path。表本体留在 unit 上（`paginationTable`/`pageCache`，各自带 paramHash），
 * 调度池只管队列 + 在途键，不存第二份，故无同步 bug。改接线而不删视图，会在翻页热路径上
 * 每次调用重算 [currentParamHash]，而三处守卫本来就已持有该 hash。
 *
 * 原则依据：`线程调度原则.md` §4「状态表不集中另存……派发守卫各自就地判据，**不得**再写一份
 * 计算式状态视图——那是第四份拷贝，且会把它要避免的 paramHash 重算塞进翻页热路径」。
 */
private fun finishCanonicalBackground(
    unit: ChapterUnit,
    ip: InProgressPagination,
    product: ChapterLayouter.ChapterLayoutProduct,
    paramHash: Long,
) {
    val layout = product.layout
    val slices = product.slices
    // P1f: completion gate — a task outliving its param cycle must not bind stale results
    // (mid-shape param change; the fresh cycle re-queues via dirtyGen). Drop + log, no write.
    if (paramHash != currentParamHash()) {
        Logger.w(logTag, "layout ${ctx(unit)} CANONICAL-STALE-DROP ch=${unit.chapterIndex}")
        return
    }
    val cache = cacheStore()
    val cacheFile = if (cache != null && bookId >= 0) {
        cache.file("book_$bookId", unit.chapterIndex, paramHash)
    } else null
    if (cache != null && cacheFile != null && slices.isNotEmpty() && slices[0].blockStart >= 0) {
        val totalChars = slices.last().charEnd
        val table = ChapterPaginationTable.fromSlices(
            chapterIndex = unit.chapterIndex,
            paramHash = paramHash,
            slices = slices,
            totalBlocks = slices.maxOf { it.blockEndExclusive },
            totalChars = totalChars,
        )
        runCatching { cache.write(table, cacheFile) }
            .onFailure { Logger.e(logTag, "canonical WRITE FAIL ch=${unit.chapterIndex} ${it.message}") }
        unit.bindPaginationTable(table)
        Logger.w(logTag, "layout ${ctx(unit)} CANONICAL-DISK-WRITE pages=${table.totalPages} totalChars=$totalChars")
        // 全书页码：本章正典页数落定（临时会话期间也能凭它给号），记进快照并推一拍。
        notePageCountKnown(unit.chapterIndex, paramHash, table.totalPages)
        // 不在单章写入后清空全书缓存；全局失效由专门时机处理
    }
    // 发布序（[InProgressPagination.canonicalLayout] 的规则）：先 slices 后 layout——layout 非空即
    // 「本会话已拿到完整正典」，读侧不会再看到「layout 非空 + slices 空」的半初始化副本。
    if (unit.inProgress === ip) {
        ip.canonicalSlices = slices
        ip.canonicalLayout = layout
        // 全书页码：本章正典页数/页号从「未知」变「已知」了（上面落表被跳过时磁盘上并没有表，
        // 读侧用的就是这份切片）。页数必须同时记进快照——这一章若只活在这份会话切片里，
        // 离开章节后就再也数不到它，全书页码前缀会永远缺一块。
        if (slices.isNotEmpty()) notePageCountKnown(unit.chapterIndex, paramHash, slices.size)
        // 推一拍让页脚与目录立刻出数，不必等下一次翻页。
        notifyPageCountsChanged()
    } else {
        // 会话已换代（读者离开本章又回来）：ip 对象已作废，副本无处可读——交接点的权威是 unit 上的
        // 磁盘表（上面的 bindPaginationTable 已落），[finalizeTempOnLeave]/[resolveHeadArrival] 直接读它。
        Logger.w(logTag, "canonical ready for superseded temp session ch=${unit.chapterIndex} (disk table on unit)")
    }
    // Intentionally no mid-session switch here: [finalizeTempOnLeave] activates the canonical table when
    // the reader leaves the temp session (head/tail/cross-chapter), so it is never re-rendered in place.
}

/**
     * Relayout after a settings change, keeping the position:
     *  - clears layout caches for all laid-out chapters (keeps the semantic tree
     *    [ChapterUnit.markup], so bodies need not be re-parsed);
     *  - rebuilds [chapter] with the current new [profile];
     *  - anchors to the page after relayout by [anchorChar] (falls back to the first page when
     *    there is no exact page).
     *
     * The caller must capture anchorChar **before** relayout (the old [PageSlice] is unusable
     * afterwards).
     * @return (chapter, relaid-out page); null when the chapter doesn't exist.
     */
    /** R12: same-chapter fast relocate — the target char already lives in a shaped-valid window
     *  page (live temp session or disk window with real line indexes): move the pointer there and
     *  return it, skipping the [invalidateAllLayouts] nuke. Anything else (unshaped stub, stale
     *  params, no session/table) returns null for the full path. Pointer moves run under
     *  [tempStateLock] (temp branch) / [layoutMutex] (disk read) so a concurrent flip can't tear them. */
    private suspend fun fastRelocate(chapter: Int, anchorChar: Int): Pair<Int, PageSlice>? {
        val unit = unitAt(chapter) ?: return null
        if (unit.markup == null) return null
        val cw = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val chh = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val currentHash = LayoutParamKey.fromProfile(profile, cw, chh).hash()
        // Live temp: find the window page containing the anchor and swing the pointer onto it.
        unit.inProgress?.let { ip ->
            if (ip.paramHash != currentHash) return@let
            val hit = (ip.forwardPages + ip.backwardPages)
                .firstOrNull { p -> p.slice.charStart <= anchorChar && anchorChar < p.slice.charEnd }
                ?: return@let
            return tempStateLock.withLock {
                if (unit.inProgress !== ip) return@withLock null // session replaced meanwhile
                locateTempPosition(ip, hit.slice)
                setCurrentTempPage(unit, ip, hit)
                logJumpLanding("jump", chapter)
                chapter to hit.slice
            }
        }
        // Disk path: a shaped-valid window page (stubs carry firstLine = -1). Temp-live chapters
        // never fall through here (anchor outside the temp window means a clean-slate rebuild,
        // not a mixed-path landing).
        if (unit.inProgress != null) return null
        val t = unit.paginationTable
        if (t == null || t.paramHash != currentHash) return null
        return layoutMutex.withLock {
            val page = pageForChar(unit, anchorChar)
            if (page != null && page.firstLine >= 0 && page.lastLineExclusive > page.firstLine) {
                logJumpLanding("jump", chapter)
                chapter to page
            } else null
        }
    }

    suspend fun relayoutTo(chapter: Int, anchorChar: Int): Pair<Int, PageSlice>? {
        clearFlipDir("mid-jump")
        // R12: fast path — target inside an already-shaped valid window: just move the pointer,
        // no nuke. Structural misses fall through to the full path below.
        fastRelocate(chapter, anchorChar)?.let { return it }
        invalidateAllLayouts()
        val unit = ensureChapterLayout(chapter, anchorChar, headLift = false) ?: return null
        // Anchor temp streaming: return the anchor page directly.
        unit.inProgress?.let { ip ->
            val p = ip.currentSlice
            if (p != null) {
                logJumpLanding("jump", chapter)
                evictFarChapters(keep = chapter)
                return chapter to p
            }
        }
        val page = pageForChar(unit, anchorChar) ?: unit.firstPage() ?: return null
        logJumpLanding("jump", chapter)
        evictFarChapters(keep = chapter)
        return chapter to page
    }

    /** Clears layout caches for all laid-out chapters (markup is kept). The caller must set the
     * new [profile] first. */
    fun invalidateAllLayouts() {
        for (u in chapters) u.invalidateLayout()
        Logger.w(logTag, "relayout: invalidated ${chapters.size} chapter layout(s)")
    }

    // ---- Progress / positioning ----

    /**
     * R5: rebinds a fresh in-memory pagination table from a small-chapter full product.
     * No disk write (writes belong to background canonical per S5 §3.5); only fixes the
     * table + paramHash so the R6 reuse guard passes on subsequent flips.
     * Also drops the per-block shape cache: shapes are keyed by leaf without a param dimension
     * and fullLayout never populates this cache, so any entries predate this param cycle and would
     * poison later flips once the rebound hash lets the R6 guard pass. Pure memo loss.
     */
    private fun rebindSmallTable(unit: ChapterUnit, slices: List<PageSlice>, paramHash: Long) {
        unit.blockShapeCache = null
        if (slices.isEmpty() || slices[0].blockStart < 0) return
        unit.bindPaginationTable(ChapterPaginationTable.fromSlices(
            chapterIndex = unit.chapterIndex,
            paramHash = paramHash,
            slices = slices,
            totalBlocks = slices.maxOf { it.blockEndExclusive },
            totalChars = slices.last().charEnd,
        ))
    }

    /**
     * R4 (S1): preview-period light relayout — current chapter only. Same product as
     *  [prepareRelayout] for the anchor chapter, but touches nothing else: no epoch bump, no
     *  preflight void, no other-chapter invalidation, no B2. The panel-close / commit-settle path
     *  still goes through [prepareRelayout] + whole-book scan (S2). B1 dispatch inside
     *  [startAnchorStream] is unchanged (defer-gated as before). Callers are sequential
     *  (throttled loops), so no P7 stale check is needed — session supersede via tempBirth covers overlap.
     */
    suspend fun prepareRelayoutLight(chapter: Int, anchorChar: Int): ReflowResult? {
        clearFlipDir("param-preview")
        val unit = unitAt(chapter) ?: return null
        val markup = unit.markup ?: return null   // 无 markup：无事可做，副作用全免
        bindChapterFor(unit)
        activeChapter = chapter
        // 整形前先备字体（同 prepareRelayout；新字体预览依赖池跟进）。
        (layouter as? BoxChapterLayouter)?.let { bc ->
            onDemandFonts(fontDemandFor(unit, bc))
            onBookFonts(bookFontsFor(chapter))
        }
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val t0 = platformNowMs()
        Logger.w(logTag, "relayout-light ch=$chapter anchorChar=$anchorChar start")
        return runCatching {
            val bc = layouter as? BoxChapterLayouter
            if (bc != null) {
                val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
                val light = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
                val tLight = platformNowMs()
                if (light.totalBlocks > SMALL_CHAPTER_BLOCKS) {
                    startAnchorStream(unit, anchorChar, light, bc, paramHash, contentWidth, contentHeight, headLift = false)
                    val tAnchor = platformNowMs()
                    val page = unit.inProgress?.currentSlice ?: return@runCatching null
                    Logger.w(logTag, "relayout-light ch=$chapter LARGE-ANCHOR prepareLight=${tLight - t0}ms anchorShape=${tAnchor - tLight}ms total=${platformNowMs() - t0}ms blocks=${light.totalBlocks}")
                    ReflowResult(chapter, null, emptyList(), page)
                } else {
                    val prep = prepareFor(unit, bc, markup, contentWidth, contentHeight, paramHash)
                    val product = bc.fullLayout(prep, profile, contentWidth, contentHeight)
                        ?: return@runCatching null
                    val slice = anchoredDiskPage(product.layout, product.slices, anchorChar)
                        ?: return@runCatching null
                    Logger.w(logTag, "relayout-light ch=$chapter SMALL-FULL prepareLight=${tLight - t0}ms fullLayout=${platformNowMs() - tLight}ms total=${platformNowMs() - t0}ms blocks=${light.totalBlocks}")
                    // R5: small chapters never enter the temp path, so rebind a fresh in-memory
                    // table here (writes still belong to background canonical). Otherwise the stale
                    // table + stale paramHash trip the R6 guard on the next flip and the fresh
                    // layout is discarded for a redundant rebuild.
                    rebindSmallTable(unit, product.slices, paramHash)
                    ReflowResult(chapter, product.layout, product.slices, slice)
                }
            } else {
                val product = layouter.layout(markup, unit.cssBundle, profile, contentWidth, contentHeight)
                    ?: return@runCatching null
                // 非 Box 排版器同样是整章全量产物：落位取磁盘表里含锚点的那一页（同短章口径）。
                val slice = anchoredDiskPage(product.layout, product.slices, anchorChar)
                    ?: return@runCatching null
                ReflowResult(chapter, product.layout, product.slices, slice)
            }
        }.onFailure { Logger.e(logTag, "relayout-light FAIL ch=$chapter anchorChar=$anchorChar ${it.message}") }.getOrNull()
    }

    /**
     * Relayout after a settings change, keeping the position (a **no-flicker** variant):
     *  - clears layout caches only for **other** chapters, keeping the current chapter's old
     *    layout -> the page being shown stays drawable during relayout, no blank screen;
     *  - computes the current chapter's new layout on a background thread with the new [profile]
     *    and returns the product to be bound;
     *  - the caller atomically replaces it on the **main thread** via [ChapterUnit.bind] + setPage,
     *    so the old page stays visible until the new page is ready.
     */
    suspend fun prepareRelayout(chapter: Int, anchorChar: Int): ReflowResult? {
        clearFlipDir("param-change")
        val unit = unitAt(chapter) ?: return null
        val markup = unit.markup ?: return null   // 无 markup：无事可做，副作用全免
        // 调参同样第一优先：epoch 已 bump 使在途 B2 过期（执行后丢弃），此处再主动砍掉，
        // 免得它们排完整章才丢（纯浪费 CPU/内存峰值）。落位章的新 B1 与尾部分派不受影响。
        preemptForNavigation()
        layoutEpoch++
        val myEpoch = layoutEpoch
        activeChapter = chapter
        voidPreflight()
        bindChapterFor(unit)
        // 整形前先备字体（同 ensureChapterLayout；本函数产物必重建，无需作废）。
        (layouter as? BoxChapterLayouter)?.let { bc ->
            onDemandFonts(fontDemandFor(unit, bc))
            onBookFonts(bookFontsFor(chapter))
        }
        for ((i, u) in chapters.withIndex()) if (i != chapter) u.invalidateLayout()
        // The current chapter's layout is NOT invalidated here — the anchor path swaps only
        // tempRenderLayout (independent of unit.layout), so the page in view never flashes blank.
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val t0 = platformNowMs()
        Logger.w(logTag, "relayout ch=$chapter anchorChar=$anchorChar start")
        return runCatching {
            val bc = layouter as? BoxChapterLayouter
            if (bc != null) {
                val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
                val light = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
                val tLight = platformNowMs()
                if (light.totalBlocks > SMALL_CHAPTER_BLOCKS) {
                    // Large chapter (or any chapter above the threshold): anchor-anchored immediate
                    // relayout, never a foreground full-chapter shape. An anchor page (top = the line
                    // containing anchorChar) is shaped synchronously for instant feedback while the
                    // background canonical continues the new-hash line-level pagination and persists it.
                    // This path does NOT write a pagination table (writes belong to background canonical
                    // only, per S5 §3.5).
                    val tA0 = platformNowMs()
                    // P7 — pure-CPU abandon point BEFORE the anchor shaping: if a newer relayout cycle
                    // (its own layoutEpoch bump) superseded this one while prepareLight ran, the anchor
                    // shape that follows is 350–550ms of non-interruptible pure CPU. Skip the whole
                    // stale segment — the newer cycle owns the anchor stream.
                    if (myEpoch != layoutEpoch) {
                        Logger.w(logTag, "relayout ch=$chapter stale (epoch $myEpoch != $layoutEpoch), skip anchor shape")
                        return@runCatching null
                    }
                    startAnchorStream(unit, anchorChar, light, bc, paramHash, contentWidth, contentHeight, headLift = false)
                    val tAnchor = platformNowMs()
                    val page = unit.inProgress?.currentSlice ?: return@runCatching null
                    Logger.w(logTag, "relayout ch=$chapter LARGE-ANCHOR prepareLight=${tLight - t0}ms anchorShape=${tAnchor - tA0}ms total=${platformNowMs() - t0}ms blocks=${light.totalBlocks}")
                    ReflowResult(chapter, null, emptyList(), page)
                } else {
                    // Small chapter: full line-level relayout (also no write here).
                    val prep = prepareFor(unit, bc, markup, contentWidth, contentHeight, paramHash)
                    val product = bc.fullLayout(prep, profile, contentWidth, contentHeight)
                        ?: return@runCatching null
                    val slice = anchoredDiskPage(product.layout, product.slices, anchorChar)
                        ?: return@runCatching null
                    Logger.w(logTag, "relayout ch=$chapter SMALL-FULL prepareLight=${tLight - t0}ms fullLayout=${platformNowMs() - tLight}ms total=${platformNowMs() - t0}ms blocks=${light.totalBlocks}")
                    // R5: same in-memory table rebind as the light path (see above).
                    rebindSmallTable(unit, product.slices, paramHash)
                    ReflowResult(chapter, product.layout, product.slices, slice)
                }
            } else {
                val product = layouter.layout(markup, unit.cssBundle, profile, contentWidth, contentHeight)
                    ?: return@runCatching null
                // 非 Box 排版器同样是整章全量产物：落位取磁盘表里含锚点的那一页（同短章口径）。
                val slice = anchoredDiskPage(product.layout, product.slices, anchorChar)
                    ?: return@runCatching null
                ReflowResult(chapter, product.layout, product.slices, slice)
            }
        }.onFailure { Logger.e(logTag, "relayout FAIL ch=$chapter anchorChar=$anchorChar epoch=$myEpoch ${it.message}") }.getOrNull()
    }

    /**
     * Panel-close finalization when a typography-affecting settings change actually happened:
     * relayout the whole book on the dedicated [canonicalDispatcher] (single thread, off the
     * foreground pool):
     *  1. current chapter FIRST — its new layout is computed via [prepareRelayout] (which keeps the
     *     old drawable layout bound until the caller atomically replaces it, so no blank page), and
     *     returned for the caller to bind on the main thread;
     *  2. then every other chapter full pre-layout. Incremental temp pagination cuts on block
     *     boundaries while the canonical pass cuts on lines, so the current chapter STILL needs its
     *     full pass afterwards — the two pageings differ.
     *
     * Returns the current chapter's relayout product (layout + slices + page) for the UI to bind on
     * the main thread — unlike the old `PageSlice` return, it must NOT be thrown away, or the unit is
     * left with no layout and the page renders blank.
     */
    suspend fun finalizeRelayoutAll(currentChapter: Int, anchorChar: Int): ReflowResult? {
        // The current chapter's old layout is intentionally NOT invalidated here: prepareRelayout keeps
        // it bound (it is the drawable page in view) and swaps in the fresh product when the caller
        // binds on the main thread. Other chapters get invalidated by prepareRelayout itself.
        val result = prepareRelayout(currentChapter, anchorChar)
        // 版式参数真正变化导致全局失效时，清全书旧磁盘分页表（统一收口：门控
        // 已保证指纹真变，守卫恒过；同指纹历史表保留，B2 逐章重写新指纹表）。
        cleanStaleDiskTables()
        // The wrap-up routes through the same epoch-ized B2 dispatch used by the params-settled point.
        requestWholeBookRelayout()
        return result
    }

    /** B2 trigger (P2): re-run the whole-book remaining-chapter scan with the CURRENT params. No-op when
     *  nothing changed since the last dispatch (epochs equal — no churn during a slider drag), when the
     *  canonical pass is deferred ([deferCanonical], settings panel open), or without a box layouter.
     *  P0 spike: the pass is a set of per-chapter pool tasks (was: one coroutine looping chapters on the
     *  single canonical thread); same-key submit replaces twins, order preserved FIFO within priority.
     *  R7: [force] bypasses the epoch dedup (B1-preempt relaunch); the dedup stamp is still refreshed. */
    fun requestWholeBookRelayout(force: Boolean = false) {
        val epoch = layoutEpoch
        if (!force && epoch <= lastDispatchEpoch) return
        if (canonicalDeferred) return
        // 开书闸门：整书章扫描是最低档的投机活，绝不能和锚页抢 `layoutMutex`（总则 §第一优先级）。
        // 记账后返回；[releaseOpenGate] 在锚页排完时补派。放在 deferCanonical 之后：面板开着
        // 的时候本来就不扫整书，没必要记账。
        if (openAnchorGate) {
            openGateWantsB2 = true
            Logger.w(logTag, "B2 deferred by open gate epoch=$epoch")
            return
        }
        val bc = layouter as? BoxChapterLayouter ?: return
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
        lastDispatchEpoch = epoch
        val current = activeChapter
        // R7: B2 scans strict absolute-distance interleave from the active chapter, so a
        // flip-out-of-bounds into a nearby chapter lands on an already-laid-out one. Pure
        // function; correctness never depends on it (every non-current chapter still completes
        // its full pass + persist).
        val order = remainingScanOrder(current)
        // 派发**静默合并**：整书 B2 是第三优先级的最低档，一次拖窗/连点会连来好几趟请求，
        // 每趟都立刻撒 24 个整章任务 ⇒ 满槽后台把第一优先级的锚页重排挤到 0.5–1.9 秒
        // （真机日志：拖窗期每 1 秒就有一趟 B2 起跑，锚页重排全程在等锁）。故请求只登记，
        // 等**最后一次**请求之后静默 [B2_DISPATCH_QUIET_MS] 再真派；期间来的新请求直接
        // 取消旧计时（几何/参数又变了，上一趟已经没意义）。任务体里的 skip-fresh 挡执行，
        // 这里挡派发——两件事不重样。
        b2DispatchJob?.cancel()
        b2DispatchJob = bgScope.launch {
            delay(B2_DISPATCH_QUIET_MS)
            if (layoutEpoch != epoch || canonicalDeferred) return@launch
            Logger.w(logTag, "whole-book B2 start epoch=$epoch paramHash=$paramHash skipCh=$current order=${order.take(8)}…")
            var submitted = 0
            for (i in order) {
                if (i == current) continue // B1 owns the active chapter's canonical pass
                scheduler.submit(TaskScheduler.Task(
                    key = scanKey(i),
                    priority = TaskScheduler.PRIO_B2_CHAPTER,
                    block = { b2ChapterTask(i, bc, contentWidth, contentHeight, paramHash) },
                ))
                submitted++
            }
            // P1a: pass-completion visibility (the producer rewrite dropped the old done-log).
            // Best-effort: a newer dispatch superseding this pass silences it.
            scheduler.awaitIdle()
            // 补派轮次：整章任务在首个挂起点被前台抢占（`cancelLowerThan` / `cancelForRaster`
            // 会把 B2 任务从 running 里 cancel）时，同一 epoch 不会再派它 —— 这一章就永远拿不到
            // 页数（762 章实测每趟稳定缺 1 章，先后是 ch426、ch348），全书页码前缀从此断掉。
            //
            // 判据只看**页数是否已知**（[chapterPageCountAt]，含页数清单），不是「内存里有没有表」：
            // 表和页数是两件事——表会被 per-book LRU 挪走、也会被 [evictFarChapters] 丢掉引用，
            // 拿表当判据会把「已经算过、只是表不在了」的章当成缺章，一趟补派就变成重跑整本书。
            // 冷却 [B2_RETRY_COOLDOWN_MS]：整批任务被一次跳章/翻页清空时，立刻重派只会马上再被
            // 清一次（真机 13:19:40 一次 jump 清掉 526 章）；等读者停手、池子真空之后再补。
            // 有界：[B2_RETRY_ROUNDS] 轮，之后放手，交给下一次派发（开书 / 调参 / B1 看门狗）。
            var round = 0
            while (layoutEpoch == epoch && !canonicalDeferred) {
                val missingNow = b2MissingPageCounts(current, paramHash)
                if (missingNow.isEmpty()) break
                if (round >= B2_RETRY_ROUNDS) {
                    Logger.w(logTag, "whole-book B2 give up epoch=$epoch rounds=$round missing=${missingNow.size}")
                    break
                }
                delay(B2_RETRY_COOLDOWN_MS)
                if (layoutEpoch != epoch || canonicalDeferred) break
                val missing = b2MissingPageCounts(current, paramHash).toHashSet()
                if (missing.isEmpty()) break
                // 仍按章距交错次序补派（离锚页近的先齐），不是按章号。
                val rescan = order.filter { it in missing }
                if (rescan.isEmpty()) break
                round++
                Logger.w(logTag, "whole-book B2 retry round=$round epoch=$epoch missing=${rescan.size} ch=${rescan.take(8)}…")
                for (i in rescan) {
                    scheduler.submit(TaskScheduler.Task(
                        key = scanKey(i),
                        priority = TaskScheduler.PRIO_B2_CHAPTER,
                        block = { b2ChapterTask(i, bc, contentWidth, contentHeight, paramHash) },
                    ))
                    submitted++
                }
                scheduler.awaitIdle()
            }
            if (layoutEpoch == epoch) {
                // 收尾把眼前这一章的表现在重写一遍（字节完全相同，只刷新 mtime）：整趟要写几百张表，
                // 表帽按 mtime 只留最新的 256 张，而当前章被 B2 跳过、它的表正好是最旧的那一批 ⇒
                // 下次开书落点变成磁盘未命中，页码要等本章正典排完才出得来。重写一次让它成为最新，
                // 开书直接磁盘命中，页码当拍就有。
                pinCurrentChapterTable(current, paramHash)
                Logger.w(logTag, "whole-book B2 pass drained epoch=$epoch submitted=$submitted rounds=$round")
                logMemoryBreakdown("b2-drained")
            }
        }
    }

    /**
     * 把 [chapter] 的表文件重写一遍（内容不变，只刷新 mtime）。
     *
     * 用途：表帽 LRU 按 mtime 只留最新的 [PaginationCacheCodec.MAX_TABLES_PER_BOOK] 张，而整书
     * 扫描会连续写几百张新表，把「眼前这一章」挤出帽外。那一章的页码恰恰是读者最常看、开书最先
     * 要用的一张：表在，开书磁盘命中、页码当拍就有；表没了就要退临时表，等本章正典排完才有页码。
     * 所以在整趟扫描收尾与关书前各刷一次，让它保持为最新的一张。
     *
     * 只刷新文件时间，不改内容、不改调度状态；表不在内存（临时会话未完成正典排版）时什么也不做。
     */
    private fun pinCurrentChapterTable(chapter: Int, hash: Long) {
        val store = cacheStore() ?: return
        if (bookId < 0 || chapter < 0) return
        val table = unitAt(chapter)?.paginationTable
            ?.takeIf { it.paramHash == hash && it.pages.isNotEmpty() } ?: return
        runCatching { store.write(table, store.file("book_$bookId", chapter, hash)) }
            .onFailure { Logger.w(logTag, "PIN table FAIL ch=$chapter ${it.message}") }
    }

    /**
     * 远章解析态驱逐（内存有界）：只保留落位章附近窗口，之外只留表（翻页的全部所需）。
     * markup/DOM/派生结构对远章是纯常驻（965 章即上百 MB），落位经 ensure 重建
     * （markup 重解析、结构重算，均幂等；磁盘命中章节只塑目标页）。
     * 跳过落位章与 live 会话章；B2 尾与落位尾各扫一次（O(chapters) 纯字段检查）。
     * 窗口 30 章 ≈ 数 MB 常驻；窗口外回翻付一次重解析（百 ms 级，远跳本就付排版费）。
     *
     * keep 默认取 [displayedChapter]（读者眼前那一章），**不是** [activeChapter]：后者只在开书/
     * 调参时写，翻页不动，读远之后按它清就是把正在显示的那一章清掉（整页空白，见字段注释）。
     * 循环里另按 [displayedChapter] / [keep] 双保险无条件跳过——调用方传错 keep 也不会清到眼前章。
     */
    private suspend fun evictFarChapters(
        keep: Int = if (displayedChapter >= 0) displayedChapter else activeChapter,
        window: Int = 30,
    ) {
        // keep 显式传落位章：跨章 sweep/跳转过程中 active 还是旧值，按 active 清会把刚建好的
        // 目标章毒死。B2 尾传默认（= 眼前章）；导航落位传 landed。
        // 与塑形侧同锁序（layoutMutex → structure 锁）：prepareLight 把整段 check-build-read
        // 包在 structure 锁里，清扫逐章同锁重置，无撕裂；从不反向持锁，无死锁。
        // 字段读写本身对并发读者 benign（引用原子 + 旧对象存活），锁只保 check-then-use 窗口
        // （如 buildLayout 的 markup 非空断言）与结构组的原子性。
        layoutMutex.withLock {
            for ((i, u) in chapters.withIndex()) {
                if (i == keep) continue
                // 眼前那一章永远不驱逐：它的版式就是屏幕上那一页的唯一绘制源，清掉即整页空白。
                if (i == displayedChapter) continue
                // 窗口内保留（回翻即时），之外才驱逐 —— 条件反了就是清近留远。
                val dist = if (i >= keep) i - keep else keep - i
                if (dist <= window) continue
                if (u.inProgress != null || u.tempBirth != null) continue
                u.structureCache.lock.withLock { u.evictParsedState() }
            }
        }
    }

    /**
     * 常驻内存盘点（诊断常驻，不进热路径）：B2 落定后记一笔，定位堆增长来自哪一类常驻
     * （markup DOM / structure / layout / pageCache / table），再决定驱逐哪一层。
     * 只计数不估字节（节点数×经验值即可定级，精确称重走 hprof）。
     */
    private fun logMemoryBreakdown(why: String) {
        var withMarkup = 0
        var domNodes = 0L
        var withLayout = 0
        var withTable = 0
        var pageCacheEntries = 0
        var withStructure = 0
        for (u in chapters) {
            val m = u.markup
            if (m != null) {
                withMarkup++
                var n = 0
                fun walk(x: orilumn.reader.engine.html.MarkupElement) {
                    n++
                    for (c in x.children) walk(c)
                }
                walk(m)
                domNodes += n
            }
            if (u.layout != null) withLayout++
            if (u.paginationTable != null) withTable++
            pageCacheEntries += u.pageCache.size
            if (u.structureCache.key != Long.MIN_VALUE) withStructure++
        }
        Logger.w(logTag, "MEM $why chapters=${chapters.size} markup=$withMarkup domNodes=$domNodes " +
            "layouts=$withLayout tables=$withTable pageCacheEntries=$pageCacheEntries structured=$withStructure")
    }

    /** One B2 chapter pass (P0: was one loop iteration inside the whole-book coroutine; P2.1: also
     *  the body the prev-chapter second-priority bump reuses under B2's own key).
     *  Skip checks re-run at execution (state moves between dispatch and run). No quiet gate —
     *  R18 doctrine as ratified: a flip PREEMPTS this work ([notifyFlip]) rather than waiting it
     *  out, so the gate would only add latency (原则文档 §6: quiet 门删除，抢占代替等待). */
    private suspend fun b2ChapterTask(
        index: Int,
        bc: BoxChapterLayouter,
        contentW: Int,
        contentH: Int,
        paramHash: Long,
    ) {
        val u = unitAt(index) ?: return
        if (u.inProgress != null) return // a live temp session owns that chapter
        // R7: the disk table itself is the done-registry — a fresh table means this chapter
        // already completed under these params; shaping it again is pure waste.
        //
        // 但仍要把页数**记进快照**：这张表可能是别的路径绑上来的（prewarm / 上一次控制器留下的
        // unit），它的文件也可能早被 per-book LRU 清掉了。不记就等于给全书页码前缀留一个永久缺口
        // ——真机见过整整一章（762 章里的一章）因此从快照里消失。
        u.paginationTable?.takeIf { it.paramHash == paramHash }?.let { table ->
            notePageCountKnown(index, paramHash, table.totalPages)
            return
        }
        // R7: bodies parse lazily — an unparsed chapter has no markup to shape. Parse here.
        // (Parse itself carries no checkpoint: a task cancelled mid-parse drains it, then the
        // suspend checkpoint below aborts before shaping a whole chapter for nothing.)
        if (u.markup == null) ensureMarkup(index)
        currentCoroutineContext().ensureActive()
        val ctx = currentCoroutineContext()
        try {
            fullLayoutAndPersist(u, bc, contentW, contentH, paramHash) { ctx.ensureActive() }
        } catch (e: CancellationException) {
            // 取消**必须冒泡**，不能当成「本章已排完」吞掉：同 epoch 不会再派这一章（派发只按
            // epoch 去重），吞掉就是给页数清单留一个永久缺口。留一行痕，方便对账补派轮次。
            Logger.w(logTag, "whole-book ch=${u.chapterIndex} CANCEL (preempted before pass done)")
            throw e
        } catch (e: Exception) {
            Logger.e(logTag, "whole-book ch=${u.chapterIndex} FAIL ${e.message}")
        }
        // 本章落定（无论成败）后清扫远章：B2 自己就是堆积源，清扫频率与 B2 同步最省事。
        evictFarChapters()
    }

    /**
     * B2 补派扫描：本参数下**页数还未知**的章，就是这一趟漏掉的章。
     *
     * 判据是 [chapterPageCountAt]（内存表 / 正典切片 / 页数清单任一已知即可），**不是**「内存里
     * 有没有表」：表只是页数的来源之一，per-book LRU 与 [evictFarChapters] 都会让表离开内存，
     * 而页数仍留在清单里。拿表当判据会把「算过但表不在了」的章误判成缺章，补派立刻退化成
     * 重跑整本书（真机一次补派 526 章）。
     *
     * 眼前章（B1 管）、活着的临时会话章（会话自己管）不抢。
     */
    private fun b2MissingPageCounts(current: Int, hash: Long): List<Int> {
        val missing = ArrayList<Int>()
        for (u in chapters) {
            val i = u.chapterIndex
            if (i == current) continue
            if (u.inProgress != null) continue
            if (chapterPageCountAt(i, hash) != null) continue
            missing.add(i)
        }
        return missing
    }

    /** P4: once a flip lands in [chapter], pre-warm BOTH neighbors (the chapters a next out-of-bounds
     *  flip can enter) on the background P track: **parse + light cascade only** — no shaping, no table.
     *  Each [ensureChapterLayout] on the flip thread skips its parse when this already ran, so a
     *  cross-chapter flip's `crossChapterLanding` parse leaves the critical path and the landing is
     *  served by the existing window/anchor shaping alone. Idempotent (already-prepped → no-op;
     *  in-flight same-chapter → dedup), keyed by paramHash, cancelled+voided on [prepareRelayout]. */
    private fun preflightNeighbors(chapter: Int) {
        preflightChapter(chapter - 1)
        preflightChapter(chapter + 1)
    }

    private fun preflightChapter(index: Int) {
        if (index == activeChapter) return // B1/anchor owns the active chapter's passes
        val unit = unitAt(index) ?: return
        bindChapterFor(unit)
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
        if (preflightReadiness[index] == paramHash) return // already prepped under these params
        if (scheduler.runningKeys.contains("pre:$index")) return // same target in flight
        // P2.4: pool task (same-key replace dedups in-flight twins — was per-index job slot).
        scheduler.submit(TaskScheduler.Task(
            key = "pre:$index",
            priority = TaskScheduler.PRIO_PREWARM,
            block = {
                try {
                    ensureMarkup(index)
                    val bc = layouter as? BoxChapterLayouter
                    if (bc != null && unit.markup != null) {
                        bc.prepareLight(unit.markup!!, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
                    }
                    preflightReadiness[index] = paramHash
                } catch (_: CancellationException) {
                    // superseded by a param change (voidPreflight) — expected, never a FAIL
                } catch (e: Exception) {
                    Logger.e(logTag, "preflight FAIL ch=$index ${e.message}")
                }
            },
        ))
    }

    /** P4 abandon/re-deploy: a new params cycle voids every preflight readiness record and cancels the
     *  in-flight slots (the light tree is structure-keyed so the next re-preflight re-uses it cheaply).
     *  P2.4: pool tasks at/below prewarm priority go through the scheduler (prewarm only — B2 at 30
     *  survives by priority). */
    private fun voidPreflight() {
        preflightReadiness.clear()
        scheduler.cancelLowerThan(TaskScheduler.PRIO_B2_CHAPTER)
    }

    /** P5 (track P): prewarm a book open with the NON-landing chapters, so early flips out of the
     *  landing chapter find parsed markup + light structure waiting.
     *  Parses [chapter]'s markup + light structure (idempotent; records [preflightReadiness] like P4)
     *  and — when the disk table for the CURRENT params already exists — binds it and pre-shapes the
     *  page range around [targetChar], promoting the table directly. Never shapes without a table.
     *  Defaults to the open point's saved position (`startChapter`/`startChar`; no progress → chapter 0),
     *  which [open] then skips (see below).
     *
     *  The LANDING chapter is always skipped ([activeChapter], set by [open]): open shapes it
     *  synchronously exactly once, so any background twin would be pure duplication + CPU contention
     *  on the critical path. Prewarm only ever serves non-landing chapters.
     *
     *  The bookshelf layer taps a book and calls this before the reader opens; switching books abandons
     *  the old book's prewarm because each book owns its controller (single per-book slot). Later
     *  params (font/行距/尺寸) invalidate the readiness and the shape via [prepareRelayout]'s void.
     *  P2.4: pool task under the shared `pre:<index>` key (dedups with neighbor preflights for the
     *  same chapter — the richer open body wins the replace). */
    fun prewarmForOpen(chapter: Int = -1, targetChar: Int = -1) {
        val books = chapters.size
        val index = (if (chapter >= 0) chapter else startChapter).coerceIn(0, books - 1)
        val char = if (targetChar >= 0) targetChar else startChar
        if (books == 0) return
        val unit = unitAt(index) ?: return
        if (index == activeChapter) return // B1/anchor owns the active chapter's passes
        scheduler.submit(TaskScheduler.Task(
            key = "pre:$index",
            priority = TaskScheduler.PRIO_PREWARM,
            block = { prewarmChapterBody(index, char) },
        ))
    }

    /** P2.4: open-prewarm body (extracted: early returns don't survive inline task lambdas). */
    private suspend fun prewarmChapterBody(index: Int, char: Int) {
        val unit = unitAt(index) ?: return
        bindChapterFor(unit)
        var c = char
        try {
            ensureMarkup(index)
            val bc = layouter as? BoxChapterLayouter ?: return
            if (unit.markup == null) return
                val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
                val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
                if (contentHeight <= 16) return // viewport not laid out yet — nothing meaningful to warm
                val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
                val prepLight = bc.prepareLight(unit.markup!!, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
                preflightReadiness[index] = paramHash
                // Disk table ready under the current params → bind + pre-shape the FIRST page window so
                // the first screen is served without re-parsing/re-paginating (P10 "promote the full
                // table directly"). Mirrors buildLayout's disk-hit branch (a fresh chapter has empty
                // pageSlices, so ensurePageRangeShaped alone would early-return).
                val cache = cacheStore()
                val cacheFile = if (cache != null && bookId >= 0) {
                    cache.file("book_$bookId", index, paramHash)
                } else null
                val cached = if (cache != null) cacheFile?.let { cache.read(it) } else null
                if (cached != null && unit.paginationTable == null) {
                    unit.bindPaginationTable(cached)
                    // 表可以绑（它是本章交接点的权威，且不改变当前显示）；但**窗口预塑 `bind(layout,
                    // slices)` 必须避开活着的临时会话**：派发时判过 `index == activeChapter`，可任务真正
                    // 执行时读者可能已经跳进本章（此时 `unit.inProgress != null`），在章内绑磁盘版式 =
                    // 总则 1 禁止的章内切换（显示不会变——渲染走临时版式——但 `pageSlices`/`laidOut` 会
                    // 变成另一份分页的，随后读表路径读到的是错位的页）。留在会话里，由进入路径按需塑。
                    if (unit.inProgress != null) {
                        Logger.w(logTag, "prewarm ch=$index bind table only (live temp session; skip window pre-shape)")
                        return
                    }
                    c = c.coerceIn(0, (cached.totalChars - 1).coerceAtLeast(0))
                    val startPage = pageIndexForChar(cached.pages, c)
                    val product = bc.incrementalLayoutForPage(
                        prepare = prepLight,
                        profile = profile,
                        contentW = contentWidth,
                        contentH = contentHeight,
                        table = cached,
                        targetPage = startPage,
                        pagesToShape = 1,
                        cache = unitShapeCache(unit),
                        prefillL2 = windowPrefillShapesFor(unit, cached),
                    )
                    unit.bind(product.layout, product.slices)
                    unit.shapedPageFrom = startPage
                    unit.shapedPageTo = (startPage + 1).coerceAtMost(cached.pages.size)
                    Logger.w(logTag, "prewarm ch=$index DISK-HIT pre-shaped pages=${product.slices.size} targetPage=$startPage")
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // superseded by a newer prewarm / param change — expected, never a FAIL
                // (scheduler surfaces it as `spike-task cancel`).
            } catch (e: Exception) {
                Logger.e(logTag, "prewarm FAIL ch=$index ${e.message}")
            }
    }

    /** Full line-level canonical pre-layout of a non-current chapter: shape the whole chapter,
     *  persist the line-level pagination table to disk, then DROP the product (B2 只算表、不驻留）。
     *
     *  B2 是整书后台扫描（调度池任务），964 章的书若每章都 `bindFull` 全量版式常驻，堆无限涨
     *  （万族之劫 256MB/512MB 连爆即此）。表（KB 级）+ markup/结构是翻页的全部所需：
     *  落位时 `buildLayout` 走磁盘命中，只塑目标页；`crossChapterLanding` 等读表路径先
     *  `ensureChapterLayout` 再读 slices，自愈。调用方仅 [b2ChapterTask]（前台路径另有
     *  bind，见 `buildLayout`/`finishCanonicalBackground`），故此处不判 live 版式。
     */
    private fun fullLayoutAndPersist(unit: ChapterUnit, bc: BoxChapterLayouter, contentW: Int, contentH: Int, paramHash: Long, checkpoint: () -> Unit = {}) {
        bindChapterFor(unit)
        val markup = unit.markup ?: return
        val prep = prepareFor(unit, bc, markup, contentW, contentH, paramHash)
        // P1: chunk workers deleted (P13) — sequential canonical for all sizes.
        val product = bc.fullLayout(prep, profile, contentW, contentH, checkpoint) ?: return
        val slices = product.slices
        // P1f: completion gate (same as canonical-background above) — stale product dropped.
        if (paramHash != currentParamHash()) {
            Logger.w(logTag, "layout ${ctx(unit)} FULL-PRELIM-STALE-DROP ch=${unit.chapterIndex}")
            return
        }
        // 空章（纯封面页/全空白）不落表，但页数是已知的 0 —— 全书页码要靠它凑齐。
        if (slices.isEmpty()) noteZeroPageChapter(unit.chapterIndex, paramHash)
        val cache = cacheStore()
        val cacheFile = if (cache != null && bookId >= 0) {
            cache.file("book_$bookId", unit.chapterIndex, paramHash)
        } else null
        if (cache != null && cacheFile != null && slices.isNotEmpty() && slices[0].blockStart >= 0) {
            val table = ChapterPaginationTable.fromSlices(
                chapterIndex = unit.chapterIndex,
                paramHash = paramHash,
                slices = slices,
                totalBlocks = slices.maxOf { it.blockEndExclusive },
                totalChars = slices.last().charEnd,
            )
            runCatching { cache.write(table, cacheFile) }
                .onFailure { Logger.e(logTag, "full prelim WRITE FAIL ch=${unit.chapterIndex} ${it.message}") }
            unit.bindPaginationTable(table)
            Logger.w(logTag, "layout ${ctx(unit)} FULL-PRELIM pages=${table.totalPages} blocks=${slices.maxOf { it.blockEndExclusive }}")
            // 全书页码：B2 每扫完一章就多一个已知页数，全书总数随之逼近「齐」。
            notePageCountKnown(unit.chapterIndex, paramHash, table.totalPages)
            // 不在每章写入后清空全书缓存：B2 是逐章后台计算，整本清空会破坏其他章尚未写入的新表
            // 只在参数变化等全局失效时机清理（如 finalizeRelayoutAll 后由调用方决定，或通过 sweepStale/trim 处理）
        }
        // R4: the heavy path never populates the shared per-block shape cache (it shapes via
        // shapeLeaf into locals), so any entries here predate this param cycle — or, if same-cycle,
        // are pure memo. Dropping is always safe; keeping risks stale-metric reuse on later flips.
        unit.blockShapeCache = null
        // B2 不 bind 版式产物：表已落盘+绑定，产物出函数即释放。常驻即全书堆爆炸（见本函数 KDoc）。
        // 翻页落位经 buildLayout 磁盘命中重塑目标页； laidOut 保持 false，ensure 路径自愈。
    }

    /**
     * Landing page for a **settings change / rotation** on a chapter that owns no temp session
     * (short chapter, or a non-Box layouter): the disk-table page that **contains the anchor**
     * (总则 §2「刷新显示当前锚点所在页面」). Pure rule lives in
     * [orilumn.reader.engine.paging.anchoredDiskPage] (gap/empty-line total, better unit-testable
     * in `common`); see there for why "nearest page head" is wrong and why the old scan could land
     * on the chapter's last page.
     */
    private fun anchoredDiskPage(
        layout: orilumn.reader.engine.paging.BookLayout,
        slices: List<PageSlice>,
        anchorChar: Int,
    ): PageSlice? = orilumn.reader.engine.paging.anchoredDiskPage(layout, slices, anchorChar)

    /**
     * A page starting at line [startLine], filling down to [contentH] (char-continuous tiling).
     * Rendered by the whole-chapter layout with the page's first line at the top.
     */
    private fun forwardPageFrom(layout: orilumn.reader.engine.paging.BookLayout, startLine: Int, contentH: Int): PageSlice? {
        val n = layout.lineCount
        if (n <= 0 || contentH <= 0 || startLine < 0 || startLine >= n) return null
        // 帧几何与 `Paginator.fillWholeLines` 同源（页首/页尾块装饰占页内空间）。
        val base = layout.getPageFrameTop(startLine)
        var end = startLine
        while (end < n && layout.getPageFrameBottom(end) - base <= contentH) end++
        if (end == startLine) end = startLine + 1
        val cs = layout.getLineStart(startLine)
        val ce = if (end < n) layout.getLineStart(end) else layout.length
        check(ce >= cs) { "forwardPageFrom inverted startLine=$startLine cs=$cs ce=$ce lines=$n" }
        return PageSlice(cs, ce, startLine, end, PageSlice.Kind.TEXT, -1, -1)
    }

    /**
     * The fullest page that ends exactly at line [endExclusive] (so the previous page always ends at
     * the current page's first line — no falling into the middle of a standard page). Near the chapter
     * head this naturally makes the title its own (possibly short) page; a return to line 0 keeps the
     * anchor at the head.
     */
    private fun backwardPageTo(layout: orilumn.reader.engine.paging.BookLayout, endExclusive: Int, contentH: Int): PageSlice? {
        if (endExclusive <= 0) return null
        val n = layout.lineCount
        if (n <= 0 || contentH <= 0) return null
        val e = endExclusive.coerceAtMost(n)
        var start = e - 1
        // 反向填充同样走帧几何：页尾钉死在行 [e-1]，问「再往上含一行 [start-1] 还装得下吗」。
        while (start > 0 && layout.getPageFrameBottom(e - 1) - layout.getPageFrameTop(start - 1) <= contentH) start--
        val cs = layout.getLineStart(start)
        val ce = if (e < n) layout.getLineStart(e) else layout.length
        return PageSlice(cs, ce.coerceAtLeast(cs), start, e, PageSlice.Kind.TEXT, -1, -1)
    }

    /** Relayout product (bound to the chapter and placed by the caller on the main thread).
     *  For a large-chapter anchor relayout [layout] is null and [slices] empty — the chapter is left
     *  in its anchor (temp) state rendered from [ChapterUnit.tempRenderLayout]; the caller must NOT bind
     *  the null layout (that would discard the old page still used for the back buffer). */
    class ReflowResult(
        val chapter: Int,
        val layout: orilumn.reader.engine.paging.BookLayout?,
        val slices: List<PageSlice>,
        val page: PageSlice,
    )

    /**
     * Q2/R5：锚点落位查询下沉（原桌面 `landAnchor`）：章内字符所在页；无内容回 null
     *（调用方退回 locateStart）。切片查找纯函数见 common [pageSliceAtChar]。
     */
    suspend fun pageAtChar(chapter: Int, char: Int): PageSlice? {
        // R5/S3: same no-lift rule as locateStart — a desktop landing anchor is positional.
        val unit = ensureChapterLayout(chapter, char, headLift = false) ?: return null
        return orilumn.reader.engine.paging.pageSliceAtChar(unit.pageSlices, char)
    }

    /**
     * Q2/R5：版式绑定下沉（原壳 `applyReflowResult` 的引擎半）：unit 变更收归控制器，
     * 壳不再触碰 [ChapterUnit]。true = 调用方应刷版本号并落位；false = unit 缺失直接返回。
     *
     * 唯一语义差：unit 存在但无产品版式（大章锚点保留旧版式）时，原壳会空刷一次版本号
     *（内容无变化，仅强制重取同一窗口），此处不再空刷——像素与定位完全一致。
     */
    fun bindReflow(r: ReflowResult): Boolean {
        val unit = unitAt(r.chapter) ?: return false
        if (unit.inProgress != null) return true
        val layout = r.layout ?: return false
        // r.layout is non-null only for full-chapter products (small-chapter/legacy relayout;
        // large chapters carry null and keep the anchor stream), so this is always a full bind.
        unit.bindFull(layout, r.slices)
        return true
    }

    fun pageProgress(chapter: Int, slice: PageSlice): Double {
        // After lazy loading the whole-book character total is unknown, so progress is converted
        // with "equal chapter weights": chapter start 0, chapter end 1, whole book = cumulative/chapters.
        val total = chapters.size
        if (total <= 0) return 0.0
        val c = chapter.coerceIn(0, total - 1)
        return ((c + 1.0) / total).coerceIn(0.0, 1.0)
    }

    // ─────────────────────────────────────────────────────────────────
    // 全书累计页码（用户层「显示页码」）
    //
    // 排版层只回答「各章分页事实」，不认这个开关（开关是 ReaderSettings 的纯 UI 项）。
    // 本节的三个口径：
    //  1) 页数只取**正典分页**（当前参数下的章表 / 整章全量版式），没有就说不认识，**绝不估**；
    //  2) 全书总页数 = 各章页数之和，缺一章就整体置 null（半本书的和不是「共几页」）；
    //  3) 本页页号只表达「这页字在正典里的位置」：临时表会话期间若正典表已在，按本页首字符
    //     落在表里哪一页给号；**不承诺临时页与正典页界一一对应**（两表互斥，禁止对比）。
    // ─────────────────────────────────────────────────────────────────

    /** 各章页数快照（章号 → 页数），只装**磁盘上**当前参数下的那部分；还活在内存里的章以
     *  [ChapterUnit.paginationTable] / 整章页切片为准（见 [chapterPageCountAt]）。后台扫描整体
     *  换引用，读者线程只读，无需锁。 */
    @Volatile
    private var diskPageCounts: Map<Int, Int> = emptyMap()

    /** [diskPageCounts] 的写锁：扫描收尾整体换引用，与逐章落表时的补记互斥（读侧不加锁）。 */
    private val pageCountsLock = SyncLock()

    /** [diskPageCounts] 属于哪个参数指纹；指纹一变（调参/旋转/字体集合变更）整份作废重扫。 */
    @Volatile
    private var diskPageCountsHash: Long = Long.MIN_VALUE
    private var diskPageCountsJob: Job? = null

    /** 页数快照的防抖落盘（见 [schedulePageCountsWrite]）。 */
    private var countsWriteJob: Job? = null

    /**
     * 本参数下**排完是 0 页**的章（纯封面页 / 只有 svg / 全空白）。
     *
     * 这类章永远不会落表，若按「没有表 = 未知」处理，全书总页数就永远凑不齐——一个空章能把
     * 整本书的页码全废掉。0 页是**已知事实**，不是未知。写少读多：后台排版线程记录，组合期
     * 逐章读，故用「整体换引用」的不可变集合，读侧不加锁。
     */
    @Volatile
    private var zeroPageChapters: Set<Int> = emptySet()

    /** [zeroPageChapters] 属于哪个参数指纹（指纹变了，上一轮的「0 页」结论不再作数）。 */
    @Volatile
    private var zeroPageHash: Long = Long.MIN_VALUE

    /**
     * 「全书页码所需数据变了」的信号：后台扫描补齐章页数、或某一章的正典表刚落（B1/B2/小章
     * 前台全排）时触发。
     *
     * 页码是**组合期**读的（同步、廉价），读者坐在第一页不动时没有任何重组由头——没有这一拍，
     * 页码要等下一次翻页才冒出来。宿主接这个信号推一次重组（跨线程安全，由宿主自行切线程）。
     */
    var onPageCountsChanged: (() -> Unit)? = null

    /**
     * 全书累计页码（用户层「第 n 页 / 共 m 页」的唯一数据源）。
     *
     * **同步、廉价**（O(章数) 加和 + 两次查表，不读盘）：只在阅读面组合期调用。磁盘扫描由
     * [ensureDiskPageCounts] 在后台搭车，本方法不等它。
     *
     * @return null = 本页暂时给不出页码（本章还没有当前参数下的正典分页 / 本页不在本章表里 /
     *   本章之前的某一章页数未知）。调用方应当**留白**，不要拿估算值凑。
     */
    fun bookPageNumber(chapter: Int, slice: PageSlice): BookPageNumber? {
        val hash = currentParamHash()
        ensureDiskPageCounts(hash)
        val unit = unitAt(chapter) ?: return null
        val idx = shownPageIndex(unit, slice, hash)
        if (idx < 0) return null
        return bookPageNumberOf(chapter, idx, chapters.size) { i -> chapterPageCountAt(i, hash) }
    }

    /**
     * 各章首页在全书的页码（章号 → 1 起；0 = 还不可算），目录面板用。
     *
     * 与 [bookPageNumber] 同一份页数快照、同一条口径：**能算的都给，遇到第一个未知章就停**
     * （它后面的章依赖它，不估）。换版式参数后快照按指纹整体作废，这里自然从空表重新长出来。
     */
    fun chapterStartPages(): IntArray {
        val hash = currentParamHash()
        ensureDiskPageCounts(hash)
        return chapterStartPagesOf(chapters.size) { i -> chapterPageCountAt(i, hash) }
    }

    /** 全书总页数；有一章页数未知就 null（页脚暂不显示总数，留给后续决定）。 */
    fun bookTotalPages(): Int? {
        val hash = currentParamHash()
        ensureDiskPageCounts(hash)
        return bookTotalPagesOf(chapters.size) { i -> chapterPageCountAt(i, hash) }
    }

    /** 屏幕上这一页在本章**正典分页**里的下标；给不出返回 -1。 */
    private fun shownPageIndex(unit: ChapterUnit, slice: PageSlice, hash: Long): Int {
        // 无临时会话时 `pageSlices` 就是本章正典分页的整表（磁盘表全量切片 / 整章全量版式），
        // 按页首字符定位最贴近屏幕上这张页。临时会话期间它是临时表，不能用来数正典页号。
        if (unit.inProgress == null && unit.pageSlices.isNotEmpty()) {
            val i = unit.pageSlices.indexOfFirst { it.charStart == slice.charStart }
            if (i >= 0) return i
        }
        // 临时会话期间：后台正典整章可能已经算完并发布到会话上（`canonicalSlices`）。它就本章正典
        // 分页本身，可用来回答「这段字落在正典第几页」——只认同参数的会话；这是同一张正典表的
        // 定位，不是拿临时页跟正典页对比（两表互斥，禁止对比）。
        val ip = unit.inProgress?.takeIf { it.paramHash == hash }
        if (ip != null) {
            val canon = ip.canonicalSlices
            if (canon.isNotEmpty()) {
                val i = canon.indexOfLast { it.charStart <= slice.charStart }
                if (i >= 0 && slice.charStart < canon[i].charEnd) return i
            }
        }
        val table = unit.paginationTable?.takeIf { it.paramHash == hash && it.pages.isNotEmpty() }
            ?: return -1
        // 「本页首字符落在正典第几页」：落在页中（临时页界与正典页界不同）也给该页号，
        // 不给会算成整段留白。与文档口径一致（临时会话期间的页号 = 这段字在正典分页里的位置）。
        return pageIndexForChar(table.pages, slice.charStart)
    }

    /** 第 [chapter] 章在当前参数下的页数；未知返回 null（全书页码不估）。 */
    private fun chapterPageCountAt(chapter: Int, hash: Long): Int? {
        val unit = unitAt(chapter)
        if (unit != null) {
            unit.paginationTable?.takeIf { it.paramHash == hash }?.let { return it.totalPages }
            // 整章全量版式（小章前台全排、大章磁盘命中窗口）= 正典分页，可当页数（含空章 0 页）；
            // 临时会话期间 `pageSlices` 是临时表，不能数。
            if (unit.inProgress == null && unit.layout != null) return unit.pageSlices.size
            // 临时会话期间：后台正典整章算完后会把切片发布到会话上（落表被跳过时磁盘上没有表，
            // 这份切片就是本章唯一一份正典分页）。只认同参数的会话。
            val ip = unit.inProgress
            if (ip != null && ip.paramHash == hash && ip.canonicalSlices.isNotEmpty()) {
                return ip.canonicalSlices.size
            }
        }
        if (zeroPageHash == hash && chapter in zeroPageChapters) return 0
        return diskPageCounts[chapter]
    }

    /**
     * 页数快照的落盘（整份覆盖）。快照里同时带上「排完是 0 页」的章：空章永远不落表，
     * 不记进清单的话，重开之后它又变回未知，前缀照样断在它身上。
     */
    private fun writePageCountsNow(hash: Long, merged: Map<Int, Int>? = null) {
        val store = cacheStore() ?: return
        if (bookId < 0) return
        if (hash != diskPageCountsHash) return
        val base = merged ?: diskPageCounts
        val snapshot = pageCountsLock.withLock {
            if (zeroPageHash != hash) {
                base
            } else {
                val m = base.toMutableMap()
                for (c in zeroPageChapters) m[c] = 0
                m
            }
        }
        if (snapshot.isEmpty()) return
        store.writePageCounts("book_$bookId", hash, snapshot)
    }

    /**
     * 防抖落盘：整书 B2 一趟会连推几百次页数，逐次写盘纯属浪费；停手 700ms 后整份覆盖一次即可。
     * 进程被强杀最多丢最后一次增量——下次开书由表头扫描补齐，不会错，只是多花一点时间。
     */
    private fun schedulePageCountsWrite(hash: Long) {
        if (cacheStore() == null || bookId < 0 || diskPageCountsHash != hash) return
        // 已在队里就不重复排：那一趟落盘时取的是当时的整份快照，天然带上期间的新增量。
        // （每 700ms 最多写一次：B2 一趟几百次更新也只落几次盘。）
        if (countsWriteJob?.isActive == true) return
        countsWriteJob = bgScope.launch {
            delay(700)
            writePageCountsNow(hash)
        }
    }

    /** 记下「本章排完是 0 页」：0 是已知页数，见 [zeroPageChapters]。 */
    private fun noteZeroPageChapter(chapter: Int, hash: Long) {
        val added = pageCountsLock.withLock {
            if (zeroPageHash != hash) {
                zeroPageHash = hash
                zeroPageChapters = emptySet()
            }
            if (chapter in zeroPageChapters) {
                false
            } else {
                zeroPageChapters = zeroPageChapters + chapter
                true
            }
        }
        if (added) {
            schedulePageCountsWrite(hash)
            notifyPageCountsChanged()
        }
    }

    /**
     * 确保「各章页数」快照属于 [hash]：换指纹即重扫。先按内存里已有的表给一份初值（当拍即可
     * 显示），再把磁盘上缺的章补齐——每章只读 40 字节表头，整书几十到几百个小读，放后台。
     *
     * 幂等：同一指纹只扫一次；扫描期间参数又变（读者拖字号）则结果整体作废（不写回）。
     */
    private fun ensureDiskPageCounts(hash: Long) {
        if (diskPageCountsHash == hash) return
        diskPageCountsHash = hash
        val mem = HashMap<Int, Int>()
        for (i in chapters.indices) {
            val t = chapters[i].paginationTable
            if (t != null && t.paramHash == hash) mem[i] = t.totalPages
        }
        pageCountsLock.withLock {
            if (zeroPageHash != hash) {
                zeroPageHash = hash
                zeroPageChapters = emptySet()
            }
            diskPageCounts = mem
        }
        val store = cacheStore() ?: return
        if (bookId < 0 || chapters.isEmpty()) return
        val n = chapters.size
        val bookKey = "book_$bookId"
        diskPageCountsJob?.cancel()
        diskPageCountsJob = bgScope.launch {
            val t0 = platformNowMs()
            val found = HashMap(mem)
            // 先吃「上一轮同参数下记下的页数清单」（`counts_<hash>.idx`）。为什么必须靠它：
            // 表文件有 per-book LRU 上限（长书 762 章只留 256 张），扫表永远凑不齐前缀；
            // 而页数是同参数下确定不变的小事实，清单在，开书立刻就有全书页码。
            val stored = runCatching { store.readPageCounts(bookKey, hash) }.getOrNull()
            if (stored != null) {
                for ((chapter, pages) in stored) if (chapter in 0 until n) found[chapter] = pages
            }
            if (diskPageCountsHash != hash) return@launch
            // 清单已覆盖全书 ⇒ 不必再逐章读表头（大书那一趟是秒级的小读扫描）。
            if (found.size < n) {
                for (i in 0 until n) {
                    if (found.containsKey(i)) continue
                    val pages = runCatching { store.readPageCount(bookKey, i, hash) }.getOrNull() ?: continue
                    found[i] = pages
                }
            }
            if (diskPageCountsHash != hash) return@launch
            // 扫描期间又有章落表（[notePageCountKnown]）→ 并进去，别把新结论盖回「未知」。
            val merged = pageCountsLock.withLock { diskPageCounts + found }
            if (diskPageCountsHash != hash) return@launch
            diskPageCounts = merged
            Logger.w(logTag, "PAGE-COUNT hash=$hash chapters=$n known=${merged.size} stored=${stored?.size ?: 0} t=${platformNowMs() - t0}ms")
            // 这一趟学到的结论顺手落成清单：下次开书（哪怕表已被 LRU 掉）直接可用。
            if (merged.size > (stored?.size ?: 0)) writePageCountsNow(hash, merged)
            if (merged.size > mem.size) onPageCountsChanged?.invoke()
        }
    }

    /**
     * 「本章正典页数落定」——把 [pages] 补记进页数快照，并按需推一拍给阅读面。
     *
     * 为什么不能只推信号：磁盘扫描（[ensureDiskPageCounts]）**每个参数指纹只扫一次**，之后
     * B2/B1 才落盘的表它不会再读；而 B2 落完会驱逐远章，[chapterPageCountAt] 的「内存表」
     * 快路径也随之消失。只推信号的话，全书总数会永远停在「未知」——表明明都在盘上。
     *
     * 快照还没建立（读者没开页码、或这一版还没扫过盘）时什么都不做：让扫描从盘上一次性补齐，
     * 别先造一份只有几章的基线（那会让扫描跳过其余章）。
     */
    private fun notePageCountKnown(chapter: Int, hash: Long, pages: Int) {
        if (hash != currentParamHash()) return
        val changed = pageCountsLock.withLock {
            if (diskPageCountsHash != hash || diskPageCounts[chapter] == pages) {
                false
            } else {
                diskPageCounts = diskPageCounts + (chapter to pages)
                true
            }
        }
        if (changed) {
            schedulePageCountsWrite(hash)
            notifyPageCountsChanged()
        }
    }

    /** 章表新落 → 全书页码可能从「缺章」变成「齐了」，推一拍给宿主（见 [onPageCountsChanged]）。 */
    private fun notifyPageCountsChanged() {
        onPageCountsChanged?.invoke()
    }

    /**
     * The current page's visible-window drawing instructions, as [DrawLine] rows in **chapter-absolute
     * Y** (the shared-ui [orilumn.reader.ui.reader.ReaderPageCanvas] shifts them into page coordinates).
     *
     * Walks the slice's line window over the unit's drawable (the temp anchor layout when active,
     * else the canonical product) and returns its skia window rows sorted by line index. Null when the
     * page isn't text (cover), the slice carries no line window, or the drawable has no skia window
     * (temporary/incremental layouts under the legacy StaticLayout engine — Q1-b accepts that interim
     * degradation for anchor-temp pages, canonical pages are always liftable).
     */
    /**
     * 预栅格读「非当前页」的绘制源（**只读**，不改指针、不改窗口）。
     *
     * 邻页预栅格要求 [pageLines]/[pageImages]/[pageBackgrounds] 能读 ±1 页：
     *  - **临时表**：每页各有自己的 `TempPage.layout`，行号是**该页局部序**（`slice.firstLine=0`）。
     *    早先无条件读 `tempRenderLayout`（当前页）⇒ 拿邻居 slice 去读会读到**当前页的行**（栅错页）。
     *  - **磁盘表**：窗口内直用 `unit.layout`；窗口外（增量 1 页窗）用 `unit.pageCache` 里已装配的
     *    product（`prefillPageTask` 产物，行号在 product 内自洽）。
     *
     * 解析由 [peekAdjacentPage]（IO 线程）写入 [peekedReadback]；本方法在 UI 线程紧随读取——
     * 避开 `pageCache`/临时页列表的跨线程裸读（它们只在排版锁/临时锁内变更）。
     */
    private class PeekedReadback(
        val chapter: Int,
        val charStart: Int,
        val layout: BookLayout,
        val slice: PageSlice,
        /** 临时表会话身份（磁盘表路径为 null）：换会话即失效，避免旧会话页冒充当前页。 */
        val ipRef: InProgressPagination?,
        /** 磁盘表参数哈希（临时表路径不参与比较）：换版式/重排即失效。 */
        val paramHash: Long,
    )

    @Volatile
    private var peekedReadback: PeekedReadback? = null

    /** 一页的绘制回读源（版式 + 该版式内有效的行区间）。非当前页经 [peekedReadback] 命中。 */
    private fun readbackFor(unit: ChapterUnit, slice: PageSlice): Pair<PagedLayout, PageSlice>? {
        val ip = unit.inProgress
        val pk = peekedReadback
        // 临时表按会话身份判新鲜（不依赖 unit.paramHash——它由 prepare/bind 分支写，未必等于 ip）；
        // 磁盘表按参数哈希判新鲜（inProgress 均为 null，用哈希挡住重排后的旧产物）。
        val fresh = pk != null && pk.chapter == unit.chapterIndex && pk.charStart == slice.charStart &&
            if (ip != null) pk.ipRef === ip
            else pk.ipRef == null && pk.paramHash == unit.paramHash
        if (fresh) {
            val rb = pk!!.layout as? PagedLayout ?: return null
            return rb to pk.slice
        }
        val rb = (unit.tempRenderLayout ?: unit.layout) as? PagedLayout ?: run {
            // 非 Readback 的 drawable（legacy/增量布局）静默空白：Q1-b interim 降级，记 w 可见。
            // 带上 null 归属与切片区间：整页空白的现场只有这一行可查（页面显示不出来时
            // `pageLines` 是本页唯一的绘制源，缺了它画布什么也不画，且只读不自愈）。
            Logger.w(logTag, "non-Readback layout ch=${unit.chapterIndex} kind=${slice.kind} " +
                "char=[${slice.charStart},${slice.charEnd}) lines=[${slice.firstLine},${slice.lastLineExclusive}) " +
                "unitLayout=${unit.layout != null} tempLayout=${unit.tempRenderLayout != null} " +
                "temp=${unit.inProgress != null}, blank content")
            return null
        }
        return rb to slice
    }

    fun pageLines(chapter: Int, slice: PageSlice): List<DrawLine>? {
        val unit = unitAt(chapter) ?: return null
        if (slice.kind != PageSlice.Kind.TEXT) return null
        val (layout, s) = readbackFor(unit, slice) ?: return null
        if (s.firstLine < 0) return null
        val window = layout.skiaLineWindow() ?: return null
        val out = ArrayList<DrawLine>(s.lastLineExclusive - s.firstLine)
        for (i in s.firstLine until s.lastLineExclusive) {
            window[i]?.let { out.add(it) }
        }
        // 表格单元格行（表行不产出行窗行，展开附在行下标上；纯表页只有它们）。
        // 与行窗合并后按 yTop 稳定排序（多列表格同行多列同 yTop；map 迭代序不定，见 gutenberg-11 1629/1657 错位）。
        out += layout.tableCellLines(s.firstLine, s.lastLineExclusive)
        if (out.isEmpty()) return null
        out.sortBy { it.yTop }
        return out
    }

    /**
     * 与 [pageLines] 同一切片口径的 `<img>` 插图几何（章节绝对 Y），阅读面异步解码贴图。
     * 空窗口回 null（调用方只画文本，与旧行为一致）。
     */
    fun pageImages(chapter: Int, slice: PageSlice): List<orilumn.reader.engine.skia.PageImage>? {
        val unit = unitAt(chapter) ?: return null
        if (slice.kind != PageSlice.Kind.TEXT) return null
        val (layout, s) = readbackFor(unit, slice) ?: return null
        if (s.firstLine < 0) return null
        return layout.pageImages(s.firstLine, s.lastLineExclusive).ifEmpty { null }
    }

    /**
     * 本页**页首缺口**（[orilumn.reader.ui...] `ReaderHost.pageFrameLead` 的引擎侧实现）：
     * `getLineTop(首行) - getPageFrameTop(首行)`，px ≥ 0。
     *
     * 与分页预算（[orilumn.reader.engine.paging.Paginator.fillWholeLines]）取**同一对帧函数**，
     * 故绘制锚点抬头的那段恰好等于预算里记在页首的那段——重路径与临时页因此同值
     * （返回值是相对首行行顶的差值，与坐标系无关）。
     * 空窗口/非法行回 0（= 旧口径，绘制像素与修前一致）。
     */
    fun pageFrameLead(chapter: Int, slice: PageSlice): Int {
        val unit = unitAt(chapter) ?: return 0
        if (slice.kind != PageSlice.Kind.TEXT) return 0
        val (layout, s) = readbackFor(unit, slice) ?: return 0
        if (s.firstLine < 0 || s.firstLine >= layout.lineCount) return 0
        return (layout.getLineTop(s.firstLine) - layout.getPageFrameTop(s.firstLine)).coerceAtLeast(0)
    }

    /**
     * 与 [pageLines] 同一切片口径的盒背景/边框（章节绝对 Y，与 DrawLine 同一坐标系），
     * 阅读面画在文字之下。空窗口回 null。
     */
    fun pageBackgrounds(chapter: Int, slice: PageSlice): List<orilumn.reader.engine.skia.PageBackground>? {
        val unit = unitAt(chapter) ?: return null
        if (slice.kind != PageSlice.Kind.TEXT) return null
        val (layout, s) = readbackFor(unit, slice) ?: return null
        if (s.firstLine < 0) return null
        return layout.pageBackgrounds(s.firstLine, s.lastLineExclusive).ifEmpty { null }
    }

    /** 按 [PageImage] 取原字节（直解用，不经 skia 缩放/PNG 往返）；缺失回 null。 */
    fun loadPageImageRaw(img: orilumn.reader.engine.skia.PageImage): ByteArray? {
        if (img.src.isBlank() || img.chapterHref.isBlank()) return null
        val path = runCatching { reader.resolveRelative(img.chapterHref, img.src) }.getOrNull()
            ?: return null
        return runCatching { reader.readBytes(path) }.getOrNull()
    }

    /** 按 [PageImage] 解码并转 PNG 字节（Compose 桥直接可用）；失败回 null（调用方跳过该图）。 */
    fun loadPageImageBytes(img: orilumn.reader.engine.skia.PageImage): ByteArray? {
        val loader = imageLoader ?: return null
        val decoded = runCatching {
            loader.decode(img.chapterHref, img.src, img.widthPx.coerceAtLeast(1))
        }.getOrNull() ?: return null
        try {
            return orilumn.reader.engine.skia.ImageCodec.encodePng(decoded.image)
        } finally {
            runCatching { decoded.image.close() }
        }
    }

    /** P3-b: 按章节相对 url 直解背景图（1:1 原尺寸，平铺在绘制层缩放之外完成）；缺失/失败回 null。 */
    fun loadBackgroundImage(chapterHref: String, src: String): orilumn.reader.engine.skia.DecodedImage? {
        if (chapterHref.isBlank() || src.isBlank()) return null
        val path = runCatching { reader.resolveRelative(chapterHref, src) }.getOrNull() ?: return null
        val bytes = runCatching { reader.readBytes(path) }.getOrNull() ?: return null
        return runCatching { orilumn.reader.engine.skia.ImageCodec.decode(bytes) }.getOrNull()
    }

    /** Book title (empty string when unparsed), for the top bar display. */
    fun title(): String = book?.title.orEmpty()

    /**
     * Locates the first page of the chapter at a given whole-book equal-weight progress (skips
     * blank/opening chapters).
     * @param fraction 0..1.
     */
    suspend fun pageAtFraction(fraction: Double): Pair<Int, PageSlice>? {
        clearFlipDir("seek")
        preemptForNavigation()
        val n = chapters.size
        if (n <= 0) return null
        val target = (fraction.coerceIn(0.0, 1.0) * n).toInt().coerceIn(0, n - 1)
        // 诊断常驻：手指值→目标章，W 级防 PGAP flood 吞行（D 级 seek start 已被吞过）。
        Logger.w(logTag, "seek map fraction=$fraction target=$target")
        // R14: nearest-content in BOTH directions (|distance| interleave, ties follow the reading
        // direction) — the old forward-only sweep could skip past the intended chapter.
        //
        // sweep 只判"有没有正文"（ensureMarkup：parse 级，无塑形、无临时会话），整章塑形只
        // 发生在落位章（与目录 openTocItem 同路径）。反例：sweep 里调 ensureChapterLayout 会
        // 给扫过的每一章建临时会话 + 塑形 —— 新进程无表时远扫即 OOM（15:50 实测），且扫过的
        // 临时会话带 inProgress 护身、驱逐跳过它们，等于一路漏。
        var landed: Int? = null
        for (c in listOf(target) + orderRemainingChapters(n, target, flipDir)) {
            val m = ensureMarkup(c) ?: continue
            if (m.hasSignificantText()) { landed = c; break }
        }
        val c = landed ?: run {
            Logger.w(logTag, "seek NULL: no landed chapter (fraction=$fraction target=$target)")
            return null
        }
        val u = ensureChapterLayout(c) ?: run {
            Logger.w(logTag, "seek NULL: ensureChapterLayout failed ch=$c")
            return null
        }
        val p = u.firstPage() ?: u.inProgress?.currentSlice ?: run {
            Logger.w(logTag, "seek NULL: ch=$c has no page (slices=${u.pageSlices.size} temp=${u.inProgress != null})")
            return null
        }
        logJumpLanding("seek", c)
        // 落位成功后才驱逐，且按刚落的章保窗口 —— active 还是旧值，按它清等于毒死刚落的章。
        evictFarChapters(keep = c)
        return c to p
    }

    /**
     * From [chapter], walks along [direction] to find the first page of the next "content-bearing"
     * chapter (previous/next chapter).
     */
    suspend fun neighborChapterStart(chapter: Int, direction: Int): Pair<Int, PageSlice>? {
        preemptForNavigation()
        var c = chapter
        while (true) {
            c += direction
            if (c !in 0 until chapters.size) return null
            val u = ensureChapterLayout(c) ?: continue
            val m = u.markup ?: continue
            if (m.hasSignificantText()) {
                val p = u.firstPage() ?: u.inProgress?.currentSlice ?: run {
                    Logger.w(logTag, "neighbor NULL: ch=$c no page (slices=${u.pageSlices.size} temp=${u.inProgress != null})")
                    continue
                }
                logJumpLanding("neighbor", c)
                evictFarChapters(keep = c)
                return c to p
            }
        }
    }

    /** R8 (S3/D4): jump-landing one-liner — PATH marker + current-params table hit. */
    private fun logJumpLanding(where: String, chapter: Int) {
        // 落位即记账：所有导航落位（开书/目录/进度/邻章/标注）都经由这里记下「眼前那一章」，
        // 远章清扫（[evictFarChapters]）据此绕开它。翻页落位另见 [navigateAdjacentPage]。
        displayedChapter = chapter
        val u = unitAt(chapter) ?: return
        val cw = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val chh = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val hit = u.paginationTable?.paramHash == LayoutParamKey.fromProfile(profile, cw, chh).hash()
        // R20：`where == "open"` 时带上整段 open 的墙钟（`open:` 那行到落位）。
        // R28：开书行另带三项可比性要件——锚点（分组用）、A/B 变体（自解释）、控制量（离群标记）。
        // 非 open 落位不测控制量：那是用户翻页的关键路径，不该塞任何测量负载。
        val tail = if (where == "open" && openT0 > 0L) {
            " openT=${platformNowMs() - openT0}ms anchor=$openAnchor" +
                " ab=${AbSwitch.describe()} ctl=${"%.2f".format(AbSwitch.controlMs())}ms"
        } else ""
        Logger.w(logTag, "jump: $where -> ch=$chapter path=${u.pathMarker} tableHit=$hit temp=${u.inProgress != null}$tail")
        // 落位成功后把"这一页排好的结果"存盘（异步防抖）：下次开书直接拿来画，不再塑形。
        // 落位页取"当前绑定的那一页"（窗口成形记录的首个成形页）；临时表会话的页切点与磁盘表
        // 不同，`capturePageSnapshot` 会自行拒绝。
        val landed = u.inProgress?.currentSlice
            ?: u.pageSlices.getOrNull(u.shapedPageFrom)?.takeIf { it.firstLine >= 0 }
            ?: u.firstPage()
        landed?.let { schedulePageSnapshot(chapter, it.charStart) }
    }

    /** Locates the first content page of (or after) [index], skipping blank/cover chapters.
 *  Used by the TOC panel to jump to an arbitrary chapter. */
    suspend fun openChapterStart(index: Int): Pair<Int, PageSlice>? {
        clearFlipDir("chapter-start")
        preemptForNavigation()
        if (chapters.isEmpty()) return null
        var ch = index.coerceIn(0, chapters.size - 1)
        while (ch < chapters.size) {
            val unit = ensureChapterLayout(ch)
            val m = unit?.markup
            if (unit != null && m != null && m.hasSignificantText()) {
                val page = unit.inProgress?.currentSlice ?: unit.firstPage()
                if (page != null) {
                    logJumpLanding("toc", ch)
                    evictFarChapters(keep = ch)
                    return ch to page
                } else {
                    Logger.w(logTag, "toc NULL: ch=$ch no page (slices=${unit.pageSlices.size} temp=${unit.inProgress != null})")
                }
            }
            ch++
        }
        return null
    }

    /**
     * 标注清单跳转：落到包含章内 [anchorChar] 的那一页（不是章首）。
     *
     * 与 [openTocItem] 同构——[preemptForNavigation] 是私有钩子，只有控制器能包这一层；
     * 失败（章越界 / 重排失败）退回 [openChapterStart]。
     */
    suspend fun openAtChar(chapter: Int, anchorChar: Int): Pair<Int, PageSlice>? {
        clearFlipDir("annotation-jump")
        preemptForNavigation()
        if (chapter !in chapters.indices) return null
        return relayoutTo(chapter, anchorChar.coerceAtLeast(0)) ?: openChapterStart(chapter)
    }

    /**
     * TOC jump honoring the item's in-chapter anchor: lands on the page whose first line is the
     * element carrying [fragment] (a depth>0 subsection, e.g. `#sec3`), instead of the chapter head.
     * Falls back to [openChapterStart] when the fragment is absent/blank, the target heading cannot
     * be resolved, or the chapter isn't content-bearing. Re-anchors pagination at the fragment via
     * [relayoutTo], which covers both the disk-table and the live-temp paths.
     */
    suspend fun openTocItem(index: Int, fragment: String?): Pair<Int, PageSlice>? {
        clearFlipDir("toc-item")
        preemptForNavigation()
        if (fragment.isNullOrBlank() || index !in chapters.indices) return openChapterStart(index)
        val m = ensureMarkup(index) ?: return openChapterStart(index)
        val charStart = styledAnchorChar(index, m, fragment)
            ?: contentFragmentIdCharStart(m, fragment) ?: return openChapterStart(index)
        return relayoutTo(index, charStart) ?: openChapterStart(index)
    }

    /**
     * P4-c2: `<a href>` target at chapter-wide [charOffset] (tap hit-testing entry), or null when
     * the position carries no link. Ranges come from the chapter's light prepare
     * ([BoxChapterLayouter.linkRangesAt] — same styled inputs the shaper used), the href resolves
     * via [LinkTargets] against the spine. Null also when the viewport isn't set yet.
     */
    fun linkTargetAt(chapter: Int, charOffset: Int): LinkTarget? {
        val unit = unitAt(chapter) ?: return null
        bindChapterFor(unit)
        val markup = unit.markup ?: return null
        val bc = layouter as? BoxChapterLayouter ?: return null
        if (viewW <= 0 || viewH <= 0) return null
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val prepare = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
        if (prepare.totalBlocks <= 0) return null
        val idx = prepare.blockIndexForChar(charOffset.coerceAtLeast(0))
        // 同一 prepare 的块表自查：blockIndexForChar 给出的 idx 必在 globalCharStarts 内；
        // 为 null 即内部表不一致，记 e（链接仍返回 null，不断整链）。
        val leafStart = prepare.globalCharStarts.getOrNull(idx)?.toInt() ?: run {
            Logger.e(logTag, "linkTargetAt inconsistent tables ch=$chapter idx=$idx blocks=${prepare.totalBlocks}")
            return null
        }
        // Link ranges depend only on params (fresh cascade); the tap only selects the block.
        // Memo per chapter + param hash so repeat taps on one block skip the styled segmentation.
        val rangeKey = currentParamHash()
        if (unit.linkRangeKey != rangeKey) {
            unit.linkRangeCache.clear()
            unit.linkRangeKey = rangeKey
        }
        val href = unit.linkRangeCache.getOrPut(idx) { prepare.linkRangesAt(idx) }
            .firstOrNull { charOffset - leafStart in it.start until it.endExclusive }?.href ?: return null
        val spineHref = book?.spine?.getOrNull(chapter)?.href ?: return null
        val indexByHref = spineIndexByNormalizedHref() ?: return null
        return LinkTargets.resolveLinkTarget(href, chapter, spineHref, indexByHref)
    }

    /**
     * P4-c2: follows [target] (same-chapter anchor or cross-chapter jump, P2-D closure): a blank
     * fragment lands on the chapter head, otherwise pagination re-anchors at the fragment's char
     * (falling back to the head when unresolvable). Mirrors [openTocItem]'s fallback chain.
     */
    suspend fun openLinkTarget(target: LinkTarget): Pair<Int, PageSlice>? {
        clearFlipDir("link-target")
        preemptForNavigation()
        if (target.chapterIndex !in chapters.indices) return null
        val fragment = target.fragment
        if (fragment.isNullOrBlank()) return openChapterStart(target.chapterIndex)
        val m = ensureMarkup(target.chapterIndex) ?: return openChapterStart(target.chapterIndex)
        val charStart = styledAnchorChar(target.chapterIndex, m, fragment)
            ?: contentFragmentIdCharStart(m, fragment) ?: return openChapterStart(target.chapterIndex)
        return relayoutTo(target.chapterIndex, charStart) ?: openChapterStart(target.chapterIndex)
    }

    /**
     * P4-c2: [fragment] 在排版字符流中的起始偏移（轻 prepare 同塑形输入；裸文本兜底在外）。
     * 与 [linkTargetAt] 同一 prepare 口径（结构缓存复用，毫秒级），无命中回 null。
     */
    private fun styledAnchorChar(chapter: Int, markup: MarkupElement, fragment: String): Int? {
        val unit = unitAt(chapter) ?: return null
        val bc = layouter as? BoxChapterLayouter ?: return null
        if (viewW <= 0 || viewH <= 0) return null
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val prepare = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight, onStructureComputed = { h, cw, chh -> saveUnitStructure(unit, h, cw, chh) })
        if (prepare.totalBlocks <= 0) return null
        return prepare.anchorCharStart(fragment)
    }

    /** Normalized spine-href → chapter index (the [LinkTargets] lookup table for this book).
     *  Built once: the spine is fixed after [open] (one controller per book, R13), so every
     *  link/TOC jump reuses the same map instead of rebuilding it per call. */
    private var spineIndexCache: Map<String, Int>? = null

    /** Book-level spine lookup table (see [spineIndexCache]). */
    fun spineIndexByNormalizedHref(): Map<String, Int>? {
        spineIndexCache?.let { return it }
        val spine = book?.spine ?: return null
        val map = HashMap<String, Int>(spine.size)
        for (item in spine) {
            val (path, _) = LinkTargets.splitFragment(item.href)
            map[LinkTargets.normalizePath(path)] = item.index
        }
        spineIndexCache = map
        return map
    }

    /** The book's table-of-contents tree (empty when absent/unparsed). */
    fun toc(): List<orilumn.reader.data.epub.TocItem> = book?.toc ?: emptyList()

    /**
     * The set of element `id`s (TOC `fragment` targets) whose document-positioned char offset falls
     * within [charStart, charEnd) of [chapter] — i.e. the headings actually on the current page. Used
     * by the TOC drawer to highlight only the current page's headings instead of the whole chapter.
     * Empty when the chapter isn't parsed yet or the page has no headings.
     */
    fun currentPageFragmentIds(chapter: Int, charStart: Int, charEnd: Int): Set<String> {
        val markup = unitAt(chapter)?.markup ?: return emptySet()
        return contentFragmentIdsInPage(markup, charStart, charEnd)
    }

    /** Locates within a chapter: which page a char offset falls in. */
    fun pageForChar(unit: ChapterUnit, charOffset: Int): PageSlice? {
        val slices = unit.pageSlices
        if (slices.isEmpty()) return null
        // 锚点落在表外钉到末页：锚点多为外部陈旧输入（保存位点/表漂移），钉住不断链；
        // 但钉住即掩盖，记 w 让漂移可见。
        return slices.firstOrNull { it.charStart <= charOffset && it.charEnd > charOffset }
            ?: slices.last().also {
                Logger.w(logTag, "pageForChar off-table anchor ch=${unit.chapterIndex} char=$charOffset pinned to last page")
            }
    }

    fun unitAt(index: Int): ChapterUnit? = chapters.getOrNull(index)

    /**
     * 整章纯文本（D-18）：与排版共用 [ChapterUnit.prepareResult] 的叶文本投影，同步、无毒副作用。
     *
     * null = 该章尚未 prepare（未打开/已被远章驱逐）；空串是合法的「本章无可排文字」。
     * 调用方（复制/选词/长按）不要为了拿文本去触发开章，避免把一次点按变成昂贵的排版。
     */
    fun chapterText(index: Int): String? = unitAt(index)?.chapterText()

    /** D-18 伴生：与 [chapterText] 同尺的块尾偏移；选区据此不跨块。 */
    fun chapterTextBlockEnds(index: Int): IntArray? = unitAt(index)?.chapterTextBlockEnds()

    /** One page forward within a chapter. A line-anchored slice (not a canonical page) snaps to the
 *  canonical page after the one containing its char, avoiding content skips. */
    fun nextPageInChapter(unit: ChapterUnit, slice: PageSlice): PageSlice? {
        flipDir = 1
        val slices = unit.pageSlices
        val idx = slices.indexOf(slice)
        if (idx >= 0) return slices.getOrNull(idx + 1)
        // 切片身份丢失（relayout 后旧对象）：按 char 模糊续上，不断链；但调用方拿过期
        // slice 翻页即掩盖，记 w。
        val c = slices.indexOfFirst { it.charStart <= slice.charStart && slice.charStart < it.charEnd }
        if (c < 0) return null
        Logger.w(logTag, "nextPageInChapter stale-slice fuzzy ch=${unit.chapterIndex} char=${slice.charStart} → page $c")
        return slices.getOrNull(c + 1)
    }

    /**
     * Finds the page-flip target: first looks for an adjacent page within the chapter; when none
     * exists, crosses chapters (next -> next chapter's first page, previous -> previous chapter's
     * last page). Cross-chapter navigation automatically skips blank/opening chapters with
     * "no valid text", and ensures the target chapter is laid out.
     * @return (target chapter, target page); null when already at the end/beginning boundary.
     */
    suspend fun findAdjacentPage(chapter: Int, slice: PageSlice, direction: Int): Pair<Int, PageSlice>? {
        // P12: every real page turn reports its movement direction to the B2 scan-order tracker —
        // in-chapter (temp/canonical) and out-of-bounds cross-chapter alike, so the whole-book scan
        // follows the reading direction even when the active chapter is a live temp session.
        flipDir = direction
        // P1d: single flip hook — preempts all background shaping below flip priority through
        // the pool (was: scattered R18 cancels). Published products survive; in-flight work
        // abandons at checkpoints and resubmits naturally via skip-fresh positioning.
        notifyFlip()
        // P4: a flip that lands in [chapter] prewarms both neighbors (the chapters a next out-of-bounds
        // flip can enter) on the background P track — so the crossing's parse leaves the flip thread.
        return navigateAdjacentPage(chapter, slice, direction)?.also { (ch, landed) ->
            preflightNeighbors(ch)
            // R7: belt-and-braces B2 dispatch (epoch-deduped no-op unless nothing dispatched yet,
            // e.g. open-prewarm still in flight) — navigation never cancels in-flight work (S7).
            requestWholeBookRelayout()
            // 翻页落位后同上：把新的当前页存成快照（异步防抖，连翻只写最后一页）。
            schedulePageSnapshot(ch, landed.charStart)
        }
    }

    /**
     * **只读**地回答「相邻页是哪个」：不推进引擎指针、不`notifyFlip()`抢占。
     *
     * 供预栅格用（`ReaderHost.peekAdjacent`）。`findAdjacentPage` 有两处副作用，
     * 投机活都不能承受：
     *  - `flipDir = direction`：改全局翻页方向记录（§3.2），会把后台预排的排序带偏；
     *  - `notifyFlip()`：`cancelLowerThan(PRIO_FLIP)` **砍掉所有后台预排**——预栅格
     *    每触发一次就把预排打断一次，于是「预排→预栅格」互相打断，两边都做不成。
     *
     * 指针推进在 `navigateAdjacentPage`（tempNav），预栅格同样不能碰——真机症状
     * 「翻页完成后又跳了一页」（预栅格调 adjacent 把指针多推一格）。
     *
     * 只读路径：从当前窗口直接读相邻页，不做任何推进。读不到（窗口未成形）返回 null，
     * 调用方跳过即可——预栅格失败只影响手感（退化为实时栅格），不影响正确性。
     */
    suspend fun peekAdjacentPage(chapter: Int, slice: PageSlice, direction: Int): Pair<Int, PageSlice>? {
        val unit = unitAt(chapter) ?: return null
        val ip = unit.inProgress
        if (ip != null) {
            // 增量（临时表）窗口：阅读序为 `Bwd(末) … Bwd(0), Fwd(0), Fwd(1) …`（见 [tempNav] /
            // [TempNavigation.tempNavBackwardPointerStep]）。方向由最近翻页决定：direction>0 = 后一页。
            // 取页在 [tempStateLock] 内做（列表随临时预排增删）；解析出的邻页绘制源一并落入
            // [peekedReadback]，供 UI 线程 [pageLines] 读取——临时表每页各有自己的 layout。
            val neighbour = tempStateLock.withLock {
                val f = ip.forwardPages
                val b = ip.backwardPages
                if (direction > 0) {
                    if (ip.curIsForward) f.getOrNull(ip.curIndex + 1)
                    else if (ip.curIndex > 0) b.getOrNull(ip.curIndex - 1)
                    else f.getOrNull(0)
                } else {
                    if (ip.curIsForward) {
                        if (ip.curIndex > 0) f.getOrNull(ip.curIndex - 1) else b.getOrNull(0)
                    } else {
                        b.getOrNull(ip.curIndex + 1)
                    }
                }
            } ?: return null
            peekedReadback = PeekedReadback(
                chapter, neighbour.slice.charStart, neighbour.layout, neighbour.slice, ip, ip.paramHash,
            )
            return chapter to neighbour.slice
        }
        // 磁盘表路径：按 char 定位后读 ±1。窗口内直用 unit.layout；窗口外（增量 1 页窗）
        // 查已装配的 pageCache——它只在排版锁内变更，故读取也在锁内（本方法在 IO 线程，不卡 UI）。
        val pages = unit.pageSlices
        if (pages.isEmpty()) return null
        val idx = pages.indexOfFirst { it.charStart == slice.charStart }
        if (idx < 0) return null
        val nIdx = idx + direction
        val neighbour = pages.getOrNull(nIdx) ?: return null
        val resolved: Pair<BookLayout, PageSlice> = if (neighbour.firstLine >= 0) {
            (unit.layout ?: return null) to neighbour
        } else {
            layoutMutex.withLock {
                val cached = unit.pageCache[nIdx]?.takeIf { it.paramHash == unit.paramHash } ?: return@withLock null
                val cs = cached.slices.getOrNull(nIdx)?.takeIf { it.firstLine >= 0 } ?: return@withLock null
                cached.layout to cs
            } ?: return null
        }
        peekedReadback = PeekedReadback(
            chapter, neighbour.charStart, resolved.first, resolved.second, null, unit.paramHash,
        )
        return chapter to neighbour
    }

    /**
     * 翻页落位（章内 / 跨章 / 临时表 / 磁盘表全部经此）：先落位，落成即记下「眼前那一章」，
     * 供远章清扫绕开（见 [displayedChapter]）。落位失败（返回 null，界面回滚原页）不改记账——
     * 原页仍是眼前那一页，绝不能因为一次失败的翻页把它清掉。
     */
    private suspend fun navigateAdjacentPage(chapter: Int, slice: PageSlice, direction: Int): Pair<Int, PageSlice>? =
        navigateAdjacentPageInner(chapter, slice, direction)?.also { displayedChapter = it.first }

    /** The actual adjacent-page navigation (direction already tracked by [findAdjacentPage] — see P12). */
    private suspend fun navigateAdjacentPageInner(chapter: Int, slice: PageSlice, direction: Int): Pair<Int, PageSlice>? {
        val unit = unitAt(chapter) ?: run {
            Logger.w(logTag, "flip: FAIL 源章不存在 ch=$chapter")
            return null
        }
        // 修复 R1（出生窗）：若一个锚流正在出生（inProgress 尚未绑定、tempBirth 挂起），等待出生
        // 提交（至多 ~3×800ms）后再重读状态导航，否则对半初始化窗口的 tempNav/按需成形会把
        // on-demand 页塞进即将成为 forwardPages[0] 的位置（重复块/回跳）。多次出生则逐次等待。
        var birthGuard = 0
        while (unit.inProgress == null && unit.tempBirth != null && birthGuard++ < 3) {
            unit.tempBirth?.let { withTimeoutOrNull(800L) { it.await() } }
        }
        // ─── PERMANENT FLIP DIAGNOSTIC: dump every key state before any branch ───
        // Tag = Orilumn.FLIP so `adb logcat -s Orilumn.FLIP:W` gives clean flip traces.
        val flipTag = "Orilumn.FLIP"
        val layoutLc = unit.layout?.lineCount ?: -1
        val tempLc = unit.tempRenderLayout?.lineCount ?: -1
        val pt = unit.paginationTable
        val ps = unit.pageSlices
        val ps0 = ps.firstOrNull()
        val psN = ps.lastOrNull()
        val ip = unit.inProgress
        // 页首缺口（§12.9 v4）：本片首行之上**仍画在本页**的那段（重路径=整章布局，t=临时窗口布局）。
        // 排障「侧栏/有背景的父容器里第一段被推走或被裁」时直接读这里：值应等于
        // 容器 padding-top + 首子 margin-top + 首子 padding-top（§12.9），两路必须同值。
        fun BookLayout.topLeadOf(firstLine: Int): Int =
            if (firstLine in 0 until lineCount) getPageTopLead(firstLine) else -1
        val topLeadF = unit.layout?.topLeadOf(slice.firstLine) ?: -1
        val topLeadT = unit.tempRenderLayout?.topLeadOf(slice.firstLine) ?: -1
        Logger.w(flipTag, "FLIP dir=$direction ch=$chapter title=${unit.title?.take(16) ?: "-"} " +
            "|slice[${slice.firstLine},${slice.lastLineExclusive})char[${slice.charStart},${slice.charEnd})blk[${slice.blockStart},${slice.blockEndExclusive})] " +
            "|inProgress=${ip != null}${ip?.let{"anchorBlk=${it.anchorBlockStart} anchorLineCh=${it.anchorLineCharStart} curFwd=${it.curIsForward} curIdx=${it.curIndex} head=${it.headReached} fwdPages=${it.forwardPages.size} bwdPages=${it.backwardPages.size} shapedFwdTo=${it.shapedForwardTo} fwd=[${tempWinSpans(it.forwardPages)}] bwd=[${tempWinSpans(it.backwardPages)}]"} ?: ""} " +
            "|diskTable=${pt != null}${pt?.let{"pages=${it.pages.size} tableParam=${it.paramHash}"} ?: ""} " +
            "|layoutLC=$layoutLc tempLC=$tempLc " +
            "|pageSlices=${ps.size}${ps0?.let{" f[${it.charStart},${it.charEnd})"} ?: ""}${psN?.let{" l[${it.charStart},${it.charEnd})"} ?: ""} " +
            "|topLead=f:$topLeadF t:$topLeadT " +
            "|laidOut=${unit.laidOut} paramHash=${unit.paramHash} path=${unit.pathMarker}")
        // Anchor temp streaming: navigate the whole-block temp pages instead of pageSlices indices.
        val inProgress = unit.inProgress
        val tempExhausted = inProgress != null
        inProgress?.let { ip ->
            locateTempPosition(ip, slice)
            tempNav(unit, ip, slice, direction)?.let {
                // A successful in-chapter flip moved the reading position — replenish the bidirectional
                // ahead window so the next flip stays pre-shaped (整改 E), steering the fill toward the
                // reader's direction of travel. Skip once the temp session handed off to canonical
                // (clearInProgress), where there is nothing left to prefill.
                if (unit.inProgress === ip) {
                    scheduleTempPrefill(unit, ip, direction)
                    // 原则 §3.3：d=1 邻页越出本章则加急整章排邻章（临时表侧）。
                    scheduleTempEdgePrefill(unit, ip)
                    // 原则 §3.4：**指针每移动一次**都要重算「目标页距章首 d」——这才是 BOOST 的
                    // 主触发点。锚点页出生时 d=0（在章首页，不升档），读者前翻一次落到第 2 页，
                    // 此时 d 才变成 1。此前钩子只挂在 [stepTempPrefill] 的 fillBackward 上，
                    // 而前翻时章首页**已经在窗口里**（backwardMissing 为 false），fillBackward 压根
                    // 不执行，钩子永远不跑 —— 实测平板 BOOST 一次都不触发，就是这里。
                    maybeBoostB1ForHeadProximity(unit, ip)
                }
                return it
            }
            // tempNav returned null — the temp stream reached its boundary (headReached or tail).
            // Do NOT fall through to pageSlices: prepareRelayout intentionally keeps the old disk
            // slices around to avoid a flash-of-white during the relayout, but they carry stale page
            // breaks from the previous typographic params. Skipping pageSlices here means a
            // head/tail-boundary flip during an active temp session goes straight to cross-chapter
            // (S5: 离开→废除临时表、启用磁盘分页表) instead of mis-identifying the current slice in
            // stale pageSlices and wandering onto wrong pages.
            // R2: reason + pointer + window ride along so a one-page-becomes-one-chapter flip names its branch.
            Logger.w(logTag, "tempNav null → skip pageSlices fallthrough ch=${unit.chapterIndex} dir=$direction " + tempWindowDiag(ip))
        }
        if (tempExhausted) {
            // Short-circuit straight to cross-chapter, bypassing both line-continuous and pageSlices
            // lookups — both hold stale data during an active temp relayout.
            Logger.w(logTag, "flip: 无章内页，开始跨章 dir=$direction from ch=$chapter")
            finalizeTempSession(unit)
            // Cross-chapter
            return crossChapterLanding(chapter, direction)
        }
        // Line-continuous temp pagination (blockStart<0 marks a line-anchored slice): keep tiling the
        // whole-chapter layout contiguously (next from the current end line, prev to the current start
        // line) so navigation never falls into the middle of a standard page. Stays active until the
        // chapter head/leaving (which then falls through to standard/cross-chapter).
        if (slice.blockStart < 0) {
            val layout = unit.layout
            if (layout != null) {
                val ch = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
                if (direction < 0) {
                    // Backward: the previous page ends at the current top line. If that page reaches
                    // the chapter head (starts at line 0), drop the line-continuous paging and use the
                    // STANDARD first page — it fills with following content per the pager's break rules,
                    // so the chapter's first page never ends with a big blank bottom.
                    val next = backwardPageTo(layout, slice.firstLine, ch)
                    if (next != null && next.firstLine == 0) {
                        unit.pageSlices.firstOrNull()?.let { return chapter to it }
                    }
                    if (next != null) {
                        Logger.w(logTag, "flip: temp-line dir=$direction -> ${ctx(unit)} " +
                            "char=[${next.charStart},${next.charEnd}) lines=[${next.firstLine},${next.lastLineExclusive})")
                        return chapter to next
                    }
                } else {
                    val next = forwardPageFrom(layout, slice.lastLineExclusive, ch)
                    if (next != null) {
                        Logger.w(logTag, "flip: temp-line dir=$direction -> ${ctx(unit)} " +
                            "char=[${next.charStart},${next.charEnd}) lines=[${next.firstLine},${next.lastLineExclusive})")
                        return chapter to next
                    }
                }
            }
        }
        val srcIdx = unit.pageSlices.indexOf(slice)
        Logger.w(logTag, "flip: src ${ctx(unit)} page=$srcIdx/${unit.pageSlices.size} kind=${pageKind(slice)} charStart=${slice.charStart}")
        val inChapter = if (direction > 0) nextPageInChapter(unit, slice)
        else prevPageInChapter(unit, slice)
        if (inChapter != null) {
            val i = unit.pageSlices.indexOf(inChapter)
            Logger.w(logTag, "flip: 章内 dir=$direction -> ${ctx(unit)} page=$i/${unit.pageSlices.size}")
            // Ensure the target page is shaped (disk-hit incremental path may have skipped it).
            // R5/S3: a flip-time rebuild must never head-lift (post-invalidate edge on pages 2–5).
            ensureChapterLayout(chapter, inChapter.charStart, headLift = false)
            // ensure 后重取：i 越界即锁间隙竞态（表被替换），此前静默用旧页，记 w 可见。
            val fresh = unit.pageSlices.getOrNull(i)
            if (fresh == null) Logger.w(logTag, "flip:章内重取越界 ch=$chapter i=$i/${unit.pageSlices.size} → fallback stale page")
            return chapter to (fresh ?: inChapter)
        }
        Logger.w(logTag, "flip: 无章内页，开始跨章 dir=$direction from ch=$chapter srcIdx=$srcIdx/${unit.pageSlices.size}")
        // Leaving the chapter abandons its temp pagination and enables the disk (canonical) table per S5
        // ("离开→废除临时表、启用磁盘分页表"). Binding on leave (never mid-chapter) avoids re-rendering the
        // page in place / flashing while the temp session is still the live pagination.
        finalizeTempSession(unit)

        // Cross-chapter
        return crossChapterLanding(chapter, direction)
    }

    /** One page backward within a chapter. A line-anchored slice (not a canonical page) snaps to the
 *  canonical page CONTAINING its char (showing the content above it, no skip); normal pages go -1. */
    fun prevPageInChapter(unit: ChapterUnit, slice: PageSlice): PageSlice? {
        flipDir = -1
        val slices = unit.pageSlices
        val idx = slices.indexOf(slice)
        if (idx > 0) return slices.getOrNull(idx - 1)
        if (idx == 0) return null
        // line-anchored: return the canonical page that contains the anchor char (see above content).
        val c = slices.indexOfFirst { it.charStart <= slice.charStart && slice.charStart < it.charEnd }
        return if (c >= 0) slices[c] else null
    }

    // ---- Utilities ----

    private fun readChapter(index: Int): MarkupElement? {
        val t0 = platformNowMs()
        val spine = book?.spine?.getOrNull(index) ?: return null
        // Bind this chapter's href on the BoxChapterLayouter so subsequent drawables can resolve
        // relative <img src> paths against the chapter's directory.
        (layouter as? BoxChapterLayouter)?.bindChapterContext(spine.href)
        val text = reader.readText(spine.href) ?: return null
        val parsed = converter.convertWithStyles(text)
        if (parsed != null) {
            // Collect the chapter's stylesheets (embedded <style> + linked css) for the cascade step.
            unitAt(index)?.cssBundle = buildCssBundle(spine.href, parsed)
            Logger.w(logTag, "readChapter ch=$index len=${text.length} css=${unitAt(index)?.cssBundle?.cssTexts?.size ?: 0} t=${platformNowMs() - t0}ms")
            return parsed.tree
        }
        // Malformed XML: fall back to plain text (no stylesheet sources).
        unitAt(index)?.cssBundle = null
        Logger.w(logTag, "readChapter ch=$index len=${text.length} FALLBACK t=${platformNowMs() - t0}ms")
        val plain = stripTags(text)
        return MarkupElement("body", children = listOf(MarkupElement("#text", text = plain)))
    }

    /** Assembles a chapter's CSS sources: embedded `<style>` blocks first, then linked stylesheets
     * resolved relative to the chapter and read through the epub reader (missing/malformed ones are
     * skipped, so a bad author stylesheet never breaks layout).
     * P2: 每份源记录基准 href（嵌入＝章节，链接＝样式表）并递归内联 `@import`
     * （防环＋限深＋媒体条件按当前视口；视口未知即只内联无条件导入）。 */
    private fun buildCssBundle(spineHref: String, parsed: ParsedChapter): CssBundle {
        // P2: @import 媒体条件按内容区视口求值（与排版 parse 同值）。
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val viewport = orilumn.reader.engine.css.CssViewport(contentW, contentH)
        return ImportStructures.collectChapterCssTexts(reader, spineHref, parsed, viewport)
    }

    private fun stripTags(html: String): String =
        html.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ")

    /** Unified chapter context: `[ch{i} title hrefName]`, to spot which chapter a log entry
     * refers to at a glance. */
    private fun ctx(unit: ChapterUnit?): String =
        unit?.let { "[ch${it.chapterIndex} ${it.title.ifEmpty { "(无标题)" }} ${it.hrefName}]" }
            ?: "[ch- 无]"

    /** Context by chapter index (works for callers before chapters exist/are laid out). */
    private fun ctx(index: Int): String = ctx(unitAt(index))

    /** Chapter context exposed to the UI layer for logging. */
    fun ctxPublic(index: Int): String = ctx(unitAt(index))

    /** Chapter title: the first heading (`h1`–`h6`) node's own text, whitespace-normalized; empty
     *  when the chapter carries no heading (callers fall back to the href name / "第 N 章").
     *
     *  只取标题元素自身文本，**绝不**把正文并进来：早先取「全树前 20 字」，而章节树的第一段
     *  正文紧跟着标题，于是顶栏章节名后面挂着一截正文（读起来像标题没写完）。 */
    private fun chapterTitle(markup: MarkupElement): String {
        val heading = firstHeading(markup) ?: return ""
        return collectText(heading).trim().replace(Regex("\\s+"), " ").take(20)
    }

    /** First heading node in document order (depth-first pre-order), or null when the tree has none. */
    private fun firstHeading(el: MarkupElement): MarkupElement? {
        if (el.tag in HEADING_TAGS) return el
        for (c in el.children) firstHeading(c)?.let { return it }
        return null
    }

    /** Page-location description, e.g. `pages[0..1)line0-3`; "(无页)" when there is no page. */
    private fun pageKind(slice: PageSlice?): String =
        slice?.let { "page[${it.firstLine},${it.lastLineExclusive})char[${it.charStart},${it.charEnd})" }
            ?: "(无页)"

    private fun log(msg: String) {
        Logger.d(logTag, msg)
    }

    /** P12 probe/test accessors (follow the [tempWindowSnapshot] precedent): the B2 scan-order helper
     *  (the exact order the whole-book coroutine will use) and the tracked reading direction as driven
     *  by the real flip entries.
     *  C2-P2b-4: public (was `internal`; `:app` probe tests live across the module seam). */
    fun remainingScanOrder(current: Int): List<Int> =
        orderRemainingChapters(chapters.size, current, flipDir)

    fun currentFlipDir(): Int = flipDir

    /** P4 probe accessor: whether [chapter]'s markup + light structure were prepped under the current
     *  param cycle on the background P track (preflightReadiness holds a key). */
    /** C2-P2b-4: public probe hook (was `internal`; `:app` probe tests live across the module seam). */
    fun isChapterPreflightReady(chapter: Int): Boolean = preflightReadiness.containsKey(chapter)
}

/** Page anchor (stable handle for locating a relayout). */
data class PageAnchor(val chapter: Int, val char: Int)

/** Orders the non-current chapters for the whole-book B2 scan (R7): strict absolute-distance
 *  interleave from [current] (1-away both sides, then 2-away, …); ties at the same distance are
 *  broken by [direction] (the side the reader is heading toward first). A flip-out-of-bounds
 *  always lands near, so the nearest chapters complete first regardless of travel direction.
 *  Pure — correctness never depends on the visit order; a cancel still exits within ≤1 chapter
 *  granularity via the per-chapter checkpoints (P7/P13).
 *
 *  @param total     chapter count
 *  @param current   the active chapter (skipped by the caller regardless — B1 owns its pass)
 *  @param direction the reader's movement: >= 0 = forward side first on ties, < 0 = backward first
 */
/** C2-P2b-4: public probe hook (was `internal`; `:app` probe tests live across the module seam). */
/** Chapter scan order for the whole-book B2 pass: strict |chapter distance| interleave from
 *  [current], the direction side first at each distance ([direction] >= 0 = tailward, so the
 *  no-record state 0 orders exactly as +1 does — see [flipDir]). */
fun orderRemainingChapters(total: Int, current: Int, direction: Int): List<Int> {
    val out = ArrayList<Int>(maxOf(0, total - 1))
    var d = 1
    while (out.size < total - 1) {
        val fwd = current + d
        val bwd = current - d
        if (direction >= 0) {
            if (fwd < total) out.add(fwd)
            if (bwd >= 0) out.add(bwd)
        } else {
            if (bwd >= 0) out.add(bwd)
            if (fwd < total) out.add(fwd)
        }
        d++
    }
    return out
}

/** The visible-char stream of [el]'s subtree in document order, sliced to `[from, to)`. This is the
 *  exact offset space the pagination tables use — `img` occupies exactly one slot, `br` one "\n" —
 *  mirroring [orilumn.reader.engine.laying.NormalFlowLayout.visibleCharAdvance] on `HIDDEN_NONE` (the same
 *  premise the light/disk pagination paths run under), so [PageSlice.charStart]/[charEnd] slice it
 *  directly. Debug-only page-content dump. */
internal fun extractVisiblePageText(el: MarkupElement, from: Int, to: Int): String {
    if (to <= from) return ""
    val sb = StringBuilder(minOf(300, to - from))
    var pos = 0
    fun walk(node: MarkupElement) {
        if (pos >= to) return
        when {
            orilumn.reader.engine.laying.NormalFlowLayout.isReplaceable(node) -> {
                if (pos >= from) sb.append('\uFFFC')
                pos++
            }
            node.isText -> {
                val n = node.text.length
                val lo = maxOf(from, pos)
                val hi = minOf(to, pos + n)
                if (lo < hi) sb.append(node.text, lo - pos, hi - pos)
                pos += n
            }
            node.tag == "br" -> {
                if (pos >= from) sb.append('\n')
                pos++
            }
            else -> for (c in node.children) walk(c)
        }
    }
    walk(el)
    return sb.toString()
}

/**
 * Debug dump of the currently displayed page — written so the reader can cross-check what is on
 * screen against the log:
 *  1) incremental-temp (`TEMP-INCR`) vs disk-table (`DISK-INCR`/`DISK-FULL`) vs full-chapter mode;
 *  2) the disk table's pagination position of this page (page count + index + the table record);
 *  3) this page's page number relative to the chapter head (head = 1, next = 2, …;
 *     "?(无盘表)" when the disk table hasn't landed yet);
 *  4) the visible text actually on the page (head/tail window; `[img]` = one image slot) plus a
 *     preview of the NEXT page's opening chars.
 * Critically it also dumps the **draw layout's authoritative char range** — the exact char range
 * of the lines the renderer paints (`drawChar=[c,d) drawLC=N`) — so "slice label char range" vs
 * "actually drawn char range" can diverge visibly when the label is stale/snapped (the usual cause
 * of log-content ≠ screen-content). `!!CHAR-DIFF` fires when they disagree; `!!SEAM-GAP` /
 * `!!SEAM-OVERLAP` fire when consecutive flips leave content between pages un-rendered.
 * Tag = `Orilumn.DBGPAGE`, filter with `adb logcat -s Orilumn.DBGPAGE:W`.
 */
internal fun debugCurrentPage(unit: ChapterUnit?, slice: PageSlice?) {
    val tag = "Orilumn.DBGPAGE"
    val s = slice ?: run { Logger.w(tag, "slice=null"); return }
    val u = unit ?: run { Logger.w(tag, "slice=[${s.charStart},${s.charEnd}) unit=null"); return }
    val ch = u.chapterIndex
    val table = u.paginationTable
    val ip = u.inProgress
    val markup = u.markup

    // 1) 当前页实际由哪张分页表产生 —— 这是判断内容对不对的前提。
    val src = when {
        ip != null -> "TEMP(临时锚点窗口表)"
        table != null && u.shapedPageFrom >= 0 -> "DISK-WIN(盘表+窗口成形)"
        table != null -> "DISK(盘表全量)"
        u.layout != null && u.pageSlices.isNotEmpty() -> "FULL(整章布局)"
        else -> "NONE(未布局)"
    }

    // 2) 当前**实际使用**的那张表的分页位置（TEMP 下盘表只是参考，不参与绘制）。
    val tempN = ip?.let { it.forwardPages.size + it.backwardPages.size } ?: 0
    val tempIdx = ip?.let { p ->
        if (p.curIsForward) p.backwardPages.size + p.curIndex else p.backwardPages.size - 1 - p.curIndex
    } ?: -1
    val diskIdx = table?.let { pageIndexForChar(it.pages, s.charStart) } ?: -1
    val diskRec = table?.let { it.pages.getOrNull(diskIdx) }
    val diskAligned = diskRec?.let { r -> r.charStart == s.charStart && r.charEnd == s.charEnd } ?: false
    val diskRef = if (table != null) {
        "diskRef=${diskIdx + 1}/${table.totalPages}" +
            (diskRec?.let { r -> "char[${r.charStart},${r.charEnd})blk[${r.blockStart},${r.blockEndExclusive})" } ?: "(越界)") +
            (if (diskAligned) "(同页)" else "(不同页,仅参考)")
    } else "diskRef=-"

    val srcPos = when {
        ip != null -> {
            val p = ip
            "srcTable=TEMP tempPages=$tempN tempIdx=${tempIdx + 1}/$tempN" +
                " side=${if (p.curIsForward) "fwd" else "bwd"}[${p.curIndex}]" +
                " anchorBlk=${p.anchorBlockStart} anchorLineChar=${p.anchorLineCharStart}" +
                " winBlk=[${s.blockStart},${s.blockEndExclusive})" +
                " shapedFwdTo=${p.shapedForwardTo} headReached=${p.headReached} headLanded=${p.headLanded}" +
                " $diskRef"
        }
        table != null -> "srcTable=DISK diskPages=${table.totalPages} diskPageIdx=$diskIdx rec=" +
            (diskRec?.let { r -> "char[${r.charStart},${r.charEnd})blk[${r.blockStart},${r.blockEndExclusive})" } ?: "(越界)") +
            (if (!diskAligned) "!!MISMATCH-当前切片≠表页" else "")
        else -> "srcTable=FULL pageSlices=${u.pageSlices.size}"
    }

    // 页码：TEMP 下盘表只是参考，真实页号未知
    val pageNo = when {
        ip != null && table != null -> "?/?(temp第${tempIdx + 1}页; $diskRef)"
        ip != null -> "?/?(temp第${tempIdx + 1}页)"
        table != null -> "${diskIdx + 1}/${table.totalPages}"
        else -> "?(无盘表)"
    }

    // 5) 渲染器真正绘制的布局 (tempRenderLayout : unit.layout)，以及它画出的行的实际 char 范围。
    //    这是"屏幕内容"的权威来源 —— 若与 slice 标签不一致，说明标签是快照/钉死的，日志文本≠屏幕。
    val drawLayout = u.tempRenderLayout ?: u.layout
    var drawChar: Pair<Int, Int>? = null
    var drawRangeNote = ""
    if (drawLayout != null && s.firstLine >= 0 && s.lastLineExclusive > s.firstLine) {
        if (s.lastLineExclusive - 1 < drawLayout.lineCount) {
            val d0 = drawLayout.getLineStart(s.firstLine)
            val dn = drawLayout.getLineEnd(s.lastLineExclusive - 1)
            drawChar = d0 to dn
            drawRangeNote = "drawChar=[$d0,$dn)drawLC=${drawLayout.lineCount}" +
                if (d0 != s.charStart || dn != s.charEnd) "!!CHAR-DIFF" else ""
        } else {
            drawRangeNote = "!!drawOutOfRange(slice线超出draw布局 lineCount=${drawLayout.lineCount})"
        }
    } else {
        drawRangeNote = "drawChar=-"
    }
    // 用权威 draw 范围抽取内容（回退到 slice 标签），保证日志文本与屏幕像素一致。
    val drawFrom = drawChar?.first ?: s.charStart
    val drawTo = drawChar?.second ?: s.charEnd

    // 3) 当前页内容：**直接从绘制布局回读**（与 renderer 同一坐标空间，绝不可能与屏幕漂移）。
    //    仅当没有绘制布局时，才退回整树遍历（该遍历与引擎叶子空间有漂移，仅供参考）。
    val totalChars = markup?.let { orilumn.reader.engine.laying.NormalFlowLayout.visibleCharAdvance(it).toInt() } ?: -1
    val drawText = if (drawLayout != null && s.firstLine >= 0 && s.lastLineExclusive > s.firstLine &&
        s.firstLine < drawLayout.lineCount
    ) ((drawLayout as? orilumn.reader.engine.skia.PagedLayout)?.debugPageText(s.firstLine, s.lastLineExclusive) ?: "") else ""
    val contentFromDraw = drawText.isNotEmpty()
    val content = if (contentFromDraw) drawText
    else markup?.let { extractVisiblePageText(it, drawFrom.coerceAtLeast(0), drawTo.coerceAtLeast(drawFrom)) } ?: ""
    // 下一页开头：同一绘制布局里紧随其后的几行（真实绘制文本）；无绘制布局时退回遍历。
    val nextPreview = if (drawLayout != null && s.lastLineExclusive in 0 until drawLayout.lineCount) {
        (drawLayout as? orilumn.reader.engine.skia.PagedLayout)?.debugPageText(s.lastLineExclusive, minOf(s.lastLineExclusive + 8, drawLayout.lineCount)) ?: ""
    } else if (markup != null) {
        extractVisiblePageText(markup, drawTo.coerceAtLeast(0), (drawTo + 80).coerceAtMost(totalChars.coerceAtLeast(0)))
    } else ""

    fun view(str: String) = str.replace("\uFFFC", "[img]").replace("\n", "\\n")
    val headPart = view(content.take(150))
    val tailPart = view(content.takeLast(150))
    val previewPart = view(nextPreview)
    val truncated = content.length > 150

    // 6) 相邻翻页接缝：上一显示页的 charEnd 与当前页 charStart 的差 —— >0 表示中间丢了内容，
    //    <0 表示重叠。跨章/同页重绘不算。
    var seam = ""
    val prev = dbgLastPage
    if (prev.chapter == ch && prev.charEnd >= 0 && prev.charStart >= 0 && prev.charStart != s.charStart) {
        val dx = s.charStart - prev.charEnd
        seam = " seam(curPrevEnd=$prev.charEnd→${s.charStart})=$dx" +
            when {
                dx == 0 -> ""
                // 只有相邻翻页（缝隙量级 <= SEAM_ADJACENT_MAX）才判为丢/重；更大的位移是跨页跳转/定位，不是缝。
                kotlin.math.abs(dx) <= SEAM_ADJACENT_MAX ->
                    if (dx > 0) "!!SEAM-GAP" else "!!SEAM-OVERLAP"
                else -> "(jump)"
            }
    }
    dbgLastPage = DbgLastPage(ch, s.charStart, s.charEnd)

    Logger.w(tag, "PAGE|ch=$ch ${u.title?.take(8) ?: "-"} src=$src" +
        "|slice char[${s.charStart},${s.charEnd})" +
        (if (s.firstLine >= 0) " line[${s.firstLine},${s.lastLineExclusive})" else "") +
        " blk[${s.blockStart},${s.blockEndExclusive})" +
        "|$srcPos" +
        " pageNo=$pageNo totalChars=$totalChars" +
        "|$drawRangeNote$seam" +
        "|content(${if (contentFromDraw) "draw" else "markup!"}) len=${content.length} head=${headPart}" +
        (if (truncated) " ...tail=${tailPart}" else "") +
        "|nextStart=${previewPart}")
}

/** Last displayed page, for the cross-flip seam check in [debugCurrentPage]. */
private data class DbgLastPage(val chapter: Int, val charStart: Int, val charEnd: Int)

/** A seam delta at or below this many chars is treated as an adjacent flip (gap/overlap); a larger
 *  jump is a page seek/locate and is not flagged as dropped content. */
private const val SEAM_ADJACENT_MAX = 300

private var dbgLastPage: DbgLastPage = DbgLastPage(-1, -1, -1)