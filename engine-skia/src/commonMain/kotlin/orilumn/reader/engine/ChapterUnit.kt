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

    /** Fills the semantic tree, called by the controller (runs once; ignored if already set). */
    fun ensureMarkup(tree: MarkupElement, treeTitle: String) {
        if (markup != null) return
        markup = tree
        title = treeTitle
    }

    /** Binds the layout result. (C1-retire: the retired span pipeline's `Spanned` is gone —
     *  the box engine binds geometry only.) */
    fun bind(layout: BookLayout, pageSlices: List<PageSlice>) {
        this.layout = layout
        this.pageSlices = pageSlices
        this.laidOut = true
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
        shapedPageFrom = -1
        shapedPageTo = -1
        laidOut = false
        paramHash = -1L
        // Unblock any flip waiting on an in-flight birth (the session it referred to is gone).
        tempBirth?.let { it.complete(Unit) }
        tempBirth = null
    }

    /** Relayout invalidation keyed by param hash: only clears when [newHash] differs from the
     *  currently-bound hash. Purely structural fields (markup, cssBundle) survive. */
    fun invalidateForParam(newHash: Long) {
        if (newHash != paramHash) invalidateLayout()
    }

    /** Degraded fallback on layout failure: binds an empty layout to guarantee no crash and safe
     * pagination (no lines). */
    fun bindSafeEmpty() {
        this.layout = null
        this.pageSlices = emptyList()
        this.laidOut = true
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

    /** For each leaf element, the nearest box that carries its background/border (itself for `pre`,
     *  an ancestor container otherwise). Typography-invariant (bg/border presence doesn't change with
     *  font size / spacing), so it is computed once and reused so window rendering never re-walks the
     *  ancestor cascade. */
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
}

/**
 * One page of the anchor-based temporary pagination: a set of **whole blocks** cut on block
 * boundaries (never torn mid-block), rendered from its own [layout] whose first line aligns to the
 * content-area top.
 *
 * @property slice page descriptor; [PageSlice.firstLine]=0 and [PageSlice.lastLineExclusive] is this
 *   page's own line count (indices into [layout]).
 * @property layout the drawable layout that backs exactly this page's whole blocks.
 */
data class TempPage(
    val slice: PageSlice,
    val layout: BookLayout,
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

    /** True once a backward page reaching block 0 was produced (the real chapter head). */
    var headReached: Boolean = false

    /** True once this session has already completed a "chapter-head jump" (re-rooted at char 0, or
     *  handed the head landing to canonical). Guards the head boundary so the FIRST boundary flip
     *  lands the FULL chapter-head page and only the NEXT one crosses to the previous chapter. */
    var headLanded: Boolean = false

    /** Canonical (chapter-head) full-layout, produced in the background; used when head/leave. */
    var canonicalLayout: BookLayout? = null
    var canonicalSlices: List<PageSlice> = emptyList()

    /** The currently displayed page, kept so callers know what to show after a background switch. */
    var currentSlice: PageSlice? = null
        private set

    fun setCurrent(slice: PageSlice?) {
        currentSlice = slice
    }
}
