package orilumn.reader.engine

import orilumn.reader.collections.withLock
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.engine.paging.PageSlice
import kotlinx.coroutines.CompletableDeferred

/**
 * Layout product and cache slot for a single chapter.
 *
 * Uses **lazy loading**: `markup` (semantic tree) is filled by [BookDocumentController] via
 * [ensureMarkup] the first time layout is needed; the `open` phase usually does not parse bodies
 * (only the book list), keeping the first screen fast. Once loaded the semantic tree is fixed;
 * Spanned/layout/pagination are invalidated and re-laid-out when font size/line spacing/margins
 * change.
 *
 * Layout data now lives in three layers:
 *  1. **Pagination table** ([paginationTable]) — persisted on disk, keyed by `paramHash`.
 *     Maps page number → char range + block range. Never contains StaticLayout data.
 *  2. **Prepare result** ([prepareResult]) — full-chapter fast work (cascade + box geometry +
 *     leaf extraction). Reusable across parameter changes only if contentW is identical.
 *  3. **Final layout** ([layout], [pageSlices]) — produced by full-chapter skia shaping
 *     + paginate. Invalidated by param changes.
 *
 * @property chapterIndex Chapter index in the spine.
 * @property hrefName Chapter xhtml file name (the last segment of spine.href, obtainable at
 *   construction without depending on body text).
 */
class ChapterUnit(
    val chapterIndex: Int,
    val hrefName: String = "",
) {
    /** The parsed semantic tree for this chapter; null until [ensureMarkup] loads it. */
    var markup: MarkupElement? = null
        private set

    /** The chapter's author stylesheets (embedded `<style>` + linked `.css`), collected alongside
     * [markup]; null until the body is parsed. */
    var cssBundle: CssBundle? = null

    /** Chapter name (taken from the first paragraph/title text, for log locating); empty when
     * markup isn't loaded. */
    var title: String = ""
        private set

    /** Whether layout is complete (layout + pageSlices ready). */
    var laidOut: Boolean = false
        private set

    var layout: BookLayout? = null
        private set

    var pageSlices: List<PageSlice> = emptyList()
        private set

    /** Current layout parameter hash. -1 = never laid out. Set when [bind] / [bindPrepare] run. */
    var paramHash: Long = -1L
        private set

    /** Result of the fast prepare phase (cascade + box geometry + leaf extraction). Reusable until
     *  [invalidateForParam] finds a different paramHash, or until the markup changes. */
    var prepareResult: ChapterPrepareResult? = null
        private set

    /** Disk-backed pagination table. Null when the table hasn't been computed yet (first open, or
     *  after a param change). Read by callers to determine which blocks to shape for a target page. */
    var paginationTable: ChapterPaginationTable? = null
        private set

    /** Page range `[shapedPageFrom, shapedPageTo)` currently shaped into [layout]/[pageSlices] on the
     *  disk-hit incremental path. Only pages inside it have valid, window-relative line indexes; pages
     *  outside get `-1` and are re-windowed on demand. The window stays FIXED while flipping within it,
     *  so incremental pagination points are stable until the reader crosses the window edge. */
    var shapedPageFrom: Int = -1
    var shapedPageTo: Int = -1

    /** R8 (S3/D4): current pagination path marker. Written only by bind sites (never guessed):
     *  [bindInProgress] → TEMP, [bind] (partial window) → WIN, [bindFull] → FULL,
     *  [bindSafeEmpty]/[invalidateLayout] → null. [clearInProgress] intentionally leaves it —
     *  it always follows a [bindFull] (finalize handoff). */
    enum class PathKind { TEMP, FULL, WIN }
    var pathMarker: PathKind? = null
        private set

    /** The first page of the chapter (null when not laid out ahead). */
    fun firstPage(): PageSlice? = pageSlices.firstOrNull()

    /** Temporary (anchor-based) incremental layout state, active only while a large chapter is being
     *  streamed from an in-chapter anchor. Null once the canonical (chapter-head) pagination takes over. */
    var inProgress: InProgressPagination? = null
        private set

    /** Drawable layout for the currently displayed temporary page (a whole-block page). While
     *  [inProgress] is non-null, renders here instead of [layout]; null once canonical layout is used. */
    var tempRenderLayout: BookLayout? = null
        private set

    /** Cache of per-block [ParagraphShape]s for the current layout-parameter cycle (indexed by leaf
     *  index). Reused across flips so a block is shaped at most once per params; cleared on
     *  [invalidateLayout]. Null until the first block is shaped. */
    var blockShapeCache: MutableMap<Int, orilumn.reader.engine.laying.ParagraphShapeRef>? = null

    /** R17: per-page assembled products on the disk path (page → layout + slices + table hash).
     *  A flip to a cached page binds with zero shaping/assembly. Trimmed to the pointer
     *  neighborhood on every store; entries carry their table hash so stale params never hit.
     *  Cleared on [invalidateLayout]. Live binding still goes through [bind]/[bindFull] only. */
    val pageCache = HashMap<Int, PageProduct>()
    /** Tap-driven link ranges per block (`linkTargetAt`): ranges depend only on params (fresh
     *  cascade), the tap only selects the block. Keyed by [linkRangeKey]; cleared on
     *  [invalidateLayout]. */
    val linkRangeCache = HashMap<Int, List<orilumn.reader.engine.layout.LinkRange>>()
    var linkRangeKey: Long? = null

    data class PageProduct(
        val layout: BookLayout,
        val slices: List<PageSlice>,
        val paramHash: Long,
    )

    /** Memoized typography-independent block structure (leaf set + global char starts), see
     *  [ChapterStructureCache]. Survives typography changes (not cleared by [invalidateLayout]) so a
     *  slider drag reuses it; its internal [ChapterStructureCache.key] self-validates against
     *  `cssBundle`/`useOriginalStyle`/viewport, recomputing only when those structural inputs change. */
    val structureCache = ChapterStructureCache()

    /** Birth signal for an in-flight anchor-temp stream: non-null while a session is being born but
     *  not yet committed (bind + filled window + current page). Flips that start inside this window
     *  await it in [BookDocumentController.findAdjacentPage] before navigating, so they never shape
     *  into / read a half-initialized temp window (修复 R1). Completed (and nulled) at the birth
     *  commit, supersede, or invalidation. `@Volatile` so the marker and its clearing are visible
     *  across the relayout / flip threads. */
    @Volatile
    var tempBirth: CompletableDeferred<Unit>? = null

    /** Sets the active temp pagination. Called by the controller when anchor streaming starts. */
    fun bindInProgress(p: InProgressPagination) {
        this.inProgress = p
        this.pathMarker = PathKind.TEMP
    }

    /** Points [tempRenderLayout] at the current temp page's layout so [PageRenderer] draws it. */
    fun setTempRenderLayout(l: BookLayout) {
        this.tempRenderLayout = l
    }

    /** Clears the temp pagination (anchor streaming finished/invalidated). */
    fun clearInProgress() {
        this.inProgress = null
        this.tempRenderLayout = null
    }

    /** Marks this chapter as laid-out even when only the anchor temp pages exist. */
    fun markLaidOut() {
        this.laidOut = true
    }

    /** Fills the semantic tree, called by the controller (runs once; ignored if already set).
     *  二次绑定不同树即上游重复解析 bug：以往静默丢弃，标题/样式停留旧树。现在抛
     *  （调用方双重判空保证只进一次，见控制器 ensureMarkup）。 */
    fun ensureMarkup(tree: MarkupElement, treeTitle: String) {
        check(markup == null) { "ensureMarkup twice ch=$chapterIndex" }
        markup = tree
        title = treeTitle
    }

    /** Binds the layout result. (C1-retire: the retired span pipeline's `Spanned` is gone —
     *  the box engine binds geometry only.) */
    fun bind(layout: BookLayout, pageSlices: List<PageSlice>) {
        this.layout = layout
        this.pageSlices = pageSlices
        this.laidOut = true
        this.pathMarker = PathKind.WIN
    }

    /** Binds a FULL-chapter layout result (R1): [bind] plus records the shaped window as the full
     *  page range, so on-demand re-windowing sees every page as already shaped instead of discarding
     *  the full result on the next flip. Partial-window binds keep using [bind] + explicit window. */
    fun bindFull(layout: BookLayout, pageSlices: List<PageSlice>) {
        bind(layout, pageSlices)
        shapedPageFrom = 0
        shapedPageTo = pageSlices.size
        pathMarker = PathKind.FULL
    }

    /** Binds the prepare result. [hash] is the layout-param hash that produced it (for later
     *  change detection). */
    fun bindPrepare(prepare: ChapterPrepareResult, hash: Long) {
        this.prepareResult = prepare
        this.paramHash = hash
    }

    /** Binds a previously-read pagination table (from disk). Sets paramHash from the table. */
    fun bindPaginationTable(table: ChapterPaginationTable) {
        this.paginationTable = table
        this.paramHash = table.paramHash
    }

    /** Relayout invalidation (settings changed): clear caches to rebuild. */
    fun invalidateLayout() {
        layout = null
        pageSlices = emptyList()
        prepareResult = null
        paginationTable = null
        inProgress = null
        tempRenderLayout = null
        blockShapeCache = null
        pageCache.clear()
        linkRangeCache.clear()
        linkRangeKey = null
        shapedPageFrom = -1
        shapedPageTo = -1
        laidOut = false
        paramHash = -1L
        pathMarker = null
        // Unblock any flip waiting on an in-flight birth (the session it referred to is gone).
        tempBirth?.let { it.complete(Unit) }
        tempBirth = null
    }

    /** Relayout invalidation keyed by param hash: only clears when [newHash] differs from the
     *  currently-bound hash. Purely structural fields (markup, cssBundle) survive. */
    fun invalidateForParam(newHash: Long) {
        if (newHash != paramHash) invalidateLayout()
    }

    /**
     * 远章解析态驱逐（B2 扫过/翻页远离后）：只留表（翻页的全部所需）；版式/DOM/结构按需重建。
     *
     * 965 章的书 markup+结构常驻即上百 MB（B2 每章都 parse，从无驱逐，堆无限涨）。
     * 调用方保证：非 active 章、无 live 会话（inProgress）。表/参数/书内包保留；
     * 落位经 `ensureChapterLayout → buildLayout` 磁盘命中重建（markup 经 `ensureMarkup`
     * 重解析，结构经 prepareLight 重算，均幂等）。
     */
    fun evictParsedState() {
        // 只丢重的：版式产物（layout/shapes）+ DOM + 派生结构 + 页缓存 + 块面缓存。
        // 表/参数/页切片/书内包保留（翻页落位数学照常，ensure 重建按需回填）。
        // tempRenderLayout 不碰：非空 ⟺ inProgress 非空（配对置空，见 clearInProgress），
        // 调用方跳过 live 会话章，故它恒是 null。
        layout = null
        prepareResult = null
        markup = null
        blockShapeCache = null
        pageCache.clear()
        linkRangeCache.clear()
        linkRangeKey = null
        shapedPageFrom = -1
        shapedPageTo = -1
        laidOut = false
        structureCache.reset()
    }
}

/**
 * Memoized typography-independent block structure for one chapter's markup.
 *
 * Holds the result of `enumerateBlockLeaves` ([leaves]) + `accumulateCharStarts`
 * ([globalCharStarts]) — both invariant across font size / line spacing / letter spacing / text scale.
 * [key] fingerprints the structural inputs (`cssBundle` author CSS + `useOriginalStyle`); when it no
 * longer matches, [StructureComputer] recomputes. Deliberately **not** cleared on typography changes:
 * that is the entire point — a slider drag reuses the structure instead of re-running the expensive
 * per-element display cascade. See `docs/incremental-layout-plan.md` §4.
 */
class ChapterStructureCache {
    /** Per-chapter lock for `prepareLight` races (C2-P2b-4: same-instance identity scope as the
     *  old `synchronized(structure)`; `SyncLock` is multiplatform). */
    val lock = orilumn.reader.collections.SyncLock()
    /** Fingerprint of the structural inputs this cache was computed from. */
    var key: Long = Long.MIN_VALUE

    /** Leaf blocks in document order (`enumerateBlockLeaves` output). */
    var leaves: List<orilumn.reader.engine.html.MarkupElement> = emptyList()

    /** Chapter-wide global char offset per leaf (`accumulateCharStarts` output). */
    var globalCharStarts: LongArray = LongArray(0)

    /** The chapter's author stylesheets already parsed from [key]'s underlying CSS ([LightCssParser]
     *  output). Reused so [BoxChapterLayouter.styleComputerFor] never re-tokenizes the same CSS. */
    var parsedAuthorSheets: List<orilumn.reader.engine.css.StyleSheet>? = null

    /** 内核层：每叶的最近背景承载盒（自身带背景色/背景图则为自身，否则为祖先容器）。
     *  只带边框的元素不算属主（其行区仍透出祖先底色，自身边框由窗口叶边框盒另画）。
     *  Typography-invariant（背景有无不随字号/间距变化），一次算出供窗口绘制复用，不再逐窗走祖先级联。 */
    var leafToBackgroundOwner: Map<orilumn.reader.engine.html.MarkupElement, orilumn.reader.engine.html.MarkupElement> = emptyMap()

    /** For each leaf element, the nearest ancestor whose resolved style is `break-inside: avoid`
     *  (the element itself first). Typography-invariant (`break-inside` presence doesn't change with
     *  font size / spacing), computed once so the light pagination path can reproduce the canonical
     *  `break-inside: avoid` page cuts without walking the ancestor cascade per window. */
    var leafToBreakInsideAvoidOwner: Map<orilumn.reader.engine.html.MarkupElement, orilumn.reader.engine.html.MarkupElement> = emptyMap()

    /**
     * P3-c: 生成内容字符串（元素 → before/after；排版无关，跨字号/主题恒有效）。
     * 伪元素样式不在此（跨参数过期），各 prepare 按新鲜级联懒解（`LightPrepare.genOf`）。
     */
    var genStrings: Map<orilumn.reader.engine.html.MarkupElement, Pair<String?, String?>> = emptyMap()

    /** Author-CSS hash the bound leaves/starts/owners were built from (loaded or computed). */
    var boundCssHash: Long? = null

    /** True only for media-free content (no `@media`/media-`@import`): the bound structure stays
     *  valid across viewport/typography changes while the CSS still matches. Never set for
     *  media-affected chapters — those always recompute via [key]. */
    var loadedMediaFree: Boolean = false

    /**
     * R26：本章是否存在 `float`（块级，非 NONE）。由 [orilumn.reader.reader.engine.BoxChapterLayouter]
     * 的 `computeStructure` 在已解析过每个叶子的那趟里顺带算出。
     *
     * 用来省掉 `LightPrepare.computeFloatLeads` 开头那次**全章** `blockStyleFor` 扫描
     * （Rust 书 ch7 169 叶实测 275ms，且被第一次 `block(i)` 触发而落在开书关键路径上）。
     *
     * 有效性：`float` 与字号/行高无关（排版非变量），而结构持久化读写两侧都被
     * [orilumn.reader.engine.ChapterStructurePersist.hasMediaRules] 门住，故不会被 `@media`
     * 的视口查询翻转——与同批持久化的 [leaves] / [globalCharStarts] 同等安全。
     */
    var anyFloat: Boolean = true

    /**
     * 远章驱逐配套：清空全部派生结构（下次 prepareLight 按 key/boundCssHash 重算，幂等）。
     * 锁对象本身保留（跨线程身份域不变）。
     */
    fun reset() {
        key = Long.MIN_VALUE
        leaves = emptyList()
        globalCharStarts = LongArray(0)
        parsedAuthorSheets = null
        leafToBackgroundOwner = emptyMap()
        leafToBreakInsideAvoidOwner = emptyMap()
        genStrings = emptyMap()
        boundCssHash = null
        loadedMediaFree = false
        anyFloat = true
    }
}

/**
 * One page of the anchor-based temporary pagination: a set of **whole blocks** cut on block
 * boundaries (never torn mid-block), rendered from its own [layout] whose first line aligns to the
 * content-area top.
 *
 * @property slice page descriptor; [PageSlice.firstLine]=0 and [PageSlice.lastLineExclusive] is this
 *   page's own line count (indices into [layout]).
 * @property layout the drawable layout that backs exactly this page's whole blocks.
 * @property resumeBlock/resumeLine shaping origin of the page AFTER this one, i.e. the
 *   [ForwardedPage.nextBlock]/[nextLine] output that built the successor (排版层-上/增量分页：
 *   水位失配修复——窗口尾裁剪后水位必须倒回新尾的后继原点，否则按需/预填从陈旧水位塑形即跳页；
 *   后向页不使用，恒为 -1）。
 */
data class TempPage(
    val slice: PageSlice,
    val layout: BookLayout,
    val resumeBlock: Int = -1,
    val resumeLine: Int = 0,
) {
    val blockStart: Int get() = slice.blockStart
    val blockEndExclusive: Int get() = slice.blockEndExclusive
}

/** A forward temp page plus the continuation point for the next page: [nextBlock] and the line offset
 *  [nextLine] within it where the next page resumes (a page may cut mid-block on a line boundary). */
data class ForwardedPage(
    val page: TempPage,
    val nextBlock: Int,
    val nextLine: Int,
)

/**
 * Anchor-based incremental pagination state for a large chapter whose disk table is missing.
 *
 * Two page lists, both cut on **whole-block** boundaries:
 *  - [forwardPages]: page [0] is the **head-edge page** — the anchor page until the bounded window
 *    slides past it — then forward to the chapter tail. Built/appended as the reader flips forward.
 *  - [backwardPages]: pages before the head edge, packed **nearest-to-head-edge first** so
 *    `backwardPages[0]` is the page right before it. Higher index = closer to the chapter head.
 *
 * Every page owns an independent [TempPage.layout]. Rendering is always whole-block page-to-top
 * aligned, so flips never show a torn block — the natural consequence of cutting at block boundaries.
 *
 * The session is a **bounded contiguous sliding window ±1** around the current page (P10, design spec
 * §6): after every flip [P10: `enforceTempWindow`] evicts only the far end (never the page under the
 * pointer), so the lists never grow past ~3 pages (+ the anchor torn pair). Window-outside movement
 * (TOC/seek/annotation/cross-chapter) discards the whole session and restarts the anchor stream.
 *
 * Once the reader reaches the real chapter head (block 0) or leaves the chapter, the whole state is
 * discarded and the canonical (chapter-head, disk-persisted) pagination takes over.
 */
class InProgressPagination(
    val chapterIndex: Int,
    val paramHash: Long,
    /** Block whose head CFI is the pagination baseline (block containing the anchor char). */
    val anchorBlockStart: Int,
    /** Content width/height (px) of the content area for this phase. */
    val contentW: Int,
    val contentH: Int,
    /** P9 (R5): the [TypographicProfile] frozen when this temp session was born. Temp shaping reads
     *  THIS snapshot (never the live `BookDocumentController.profile`, which a typography tune swaps
     *  outside [tempStateLock]) so every page shaped in the session uses one uniform param set — a
     *  flip racing a live tune can no longer flash a single mixed-param frame. */
    val profileSnapshot: TypographicProfile,
    /** Structured (lazy) prepare result — leaf order/char starts + on-demand block style materialization —
     *  reused for all on-demand shaping in this phase. */
    val prepare: LightPrepare,
) {
    /**
     * C2-P2b-4: temp-stream identity for prefill dispatch dedup (was `System.identityHashCode`,
     * JVM-only). Strictly better: sequential, collision-free (identity hash can theoretically collide).
     */
    val sessionId: Long = nextSessionId()

    companion object {
        private var sessionSeq = 0L
        private val seqLock = orilumn.reader.collections.SyncLock()
        fun nextSessionId(): Long = seqLock.withLock { ++sessionSeq }
    }

    /** Char offset where the line-anchored anchor page begins (the first line's char start). The
     *  anchor page starts at this line so its first character always sits on the page's first line. */
    var anchorLineCharStart: Int = 0

    /** Shared per-block shape cache for this temp phase (same map as [ChapterUnit.blockShapeCache]),
     *  so already-shaped blocks are reused across flips instead of re-shaping. */
    var shapes: MutableMap<Int, orilumn.reader.engine.laying.ParagraphShapeRef>? = null
    /** Anchor page first, then forward. */
    val forwardPages: MutableList<TempPage> = ArrayList()

    /** Nearest-to-anchor first; higher index = closer to the chapter head. */
    val backwardPages: MutableList<TempPage> = ArrayList()

    /** Inclusive; exclusive block end reached by the deepest forward page. */
    var shapedForwardTo: Int = anchorBlockStart

    /** For line-based forward pages: the line offset WITHIN [shapedForwardTo] where the next forward page
     *  begins (0 when the next page starts at that block's first line). Lets a page cut through a code
     *  block (fill the prior page's blank bottom) and resume at the same block's next line. */
    var forwardFromLine: Int = 0

    /** Navigation pointer: whether the current page lives in [forwardPages] and its index. */
    var curIsForward: Boolean = true
    var curIndex: Int = 0

    /**
     * 前向链边缓存（排版层-上/增量分页）：`页尾字符 → 该页页首游标 (块, 块内行)`。
     *
     * 倒包一页时，「以哪一行为页首」**不能**由几何累加器反推——同一个页尾字符在回原像里常有多个
     * 候选行首（真书 ch2 的 45 个页界：唯一解 30、最大解 8、最小解 7、内部解 3），几何倒包只能猜
     * 其中之一，猜错即与**本会话已见过的**页界漂移（真机：倒包页比前向页多收一行，页尾少 10 字，
     * 随后前向重建从错位原点起塑，来回翻页内容就变了）。本会话内唯一可靠的来源是**前向链本身**：
     * 每塑一页前向页就登记一条边（键=该页 [PageSlice.charEnd]，值=该页页首游标），回翻时命中即
     * **同一页**。
     *
     * 只在 [tempStateLock] 内读写（与临时会话同生命周期；会话作废即随对象废弃，不跨会话串味）。
     * 只登记**前向**页：倒包页的页首按设计**不**回写（避免把几何估计当边记忆，覆盖真正见过的前向
     * 链边）。两条来源按优先级：读者实际走过的前向链边（[BookDocumentController.recordChainEdge]）
     * 优先，缺口由[章首前向正典链][headRootedTo]补齐（[BookDocumentController.seedHeadRootedEdges]，
     * 只 `putIfAbsent`，绝不改写读者见过的边）。**不读磁盘表/`canonicalSlices`**：那会把「后台正典
     * 何时算完」变成翻页结果的输入（同一手势因时机不同给出不同页界）；章首链是**确定性**的轻路径
     * 前向塑形，不是时序相关的第二份分页。磁盘表仍只在章首交接 / 离开本章时出场（AGENTS.md 总则 1）。
     */
    val prevStart: MutableMap<Int, Pair<Int, Int>> = HashMap()

    /**
     * 章首（块 0）起的前向正典链已采样到的页尾字符（含）；0 = 未采样。
     *
     * [BookDocumentController.seedHeadRootedEdges] 用 [BoxChapterLayouter.shapeTempPageForward] 从块 0
     * 逐页前向塑形，把每条「页尾字符 → 页首游标」边登记进 [prevStart]，直到越过某个目标页尾。轻路径前向
     * 链与重路径整章全量分页**逐页同值**（真书 ch2 JVM 复算 14/14 页逐字相等），故这些边就是 canonical
     * 页界——临时表倒包据此取页首，回原像的多解不再靠猜测（几何估计只能命中其一，猜错即漂移）。
     *
     * **确定性**：链只由轻路径 + 本章参数算出，**不读 [canonicalSlices]/磁盘表**，故同一 pin 恒得同一页，
     * 与后台正典何时算完无关（AGENTS.md 总则：章内只用临时表；这里只是让临时表自己的页界与磁盘表同源）。
     * 采样水位同时是**负缓存**：目标页尾小于水位却没登记边 ⇒ 该字符不是正典页界（中章起锚的行切页首、
     * 畸形章）⇒ 直接交几何倒包，不重复整章前向塑形。
     */
    var headRootedTo: Int = 0

    /** True once a backward page reaching block 0 was produced (the real chapter head). */
    var headReached: Boolean = false

    /** True once this session has already completed a "chapter-head jump" (re-rooted at char 0, or
     *  handed the head landing to canonical). Guards the head boundary so the FIRST boundary flip
     *  lands the FULL chapter-head page and only the NEXT one crosses to the previous chapter. */
    var headLanded: Boolean = false

    /** 正典（章首全量）版式 + 页切，后台线程 [BookDocumentController.finishCanonicalBackground] 产出，
     *  只在**交接点**（回章首 / 离开本章）被前台使用——章内绝不据此切换（AGENTS.md 总则 1）。
     *
     *  **发布规则（无锁跨线程，必须守）**：写侧先 `canonicalSlices` 后 `canonicalLayout`，读侧先判
     *  `canonicalLayout != null` 再取 `canonicalSlices`。两个字段都 `@Volatile`，`layout` 因此是
     *  「已发布」标记：看到非空即能读到非空 slices（否则会 `bindFull(layout, emptyList())` → 零页单元）。
     *  读侧全在 [BookDocumentController] 的 `tempStateLock` 内，写侧不在 → 无该锁即无 happens-before。 */
    @Volatile
    var canonicalLayout: BookLayout? = null

    @Volatile
    var canonicalSlices: List<PageSlice> = emptyList()

    /** The currently displayed page, kept so callers know what to show after a background switch. */
    var currentSlice: PageSlice? = null
        private set

    fun setCurrent(slice: PageSlice?) {
        currentSlice = slice
    }
}
