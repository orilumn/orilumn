package orilumn.reader.engine

import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.time.platformNowMs
import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.epub.EpubParser
import orilumn.reader.engine.css.BookStyleProbe
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.StyleSheet
import orilumn.reader.engine.html.ChapterPreprocessor
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.html.ParsedChapter
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.paging.Paginator
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LayoutReadback
import orilumn.reader.engine.text.LayoutParamKey
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.io.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Priority contract (C1-2; shared with every host — same F>A>B1>B2>P order as the desktop host):
 *  - **F foreground flip** (highest): runs on the caller thread — synchronous temp shaping that
 *    never waits on background work and is never preempted by it;
 *  - **A adjacent prefill** ([tempPrefillJob] on [backgroundDispatcher]): cancellable, deduped per
 *    cursor, yields to F by construction (short one-page shapes under [tempStateLock]);
 *  - **B1 chapter-head canonical** ([canonicalDispatcher], single thread): the authoritative
 *    full-chapter table; per-chapter FIFO slots that a same-chapter re-dispatch cancels, but never
 *    kill another chapter's in-flight persist (P3 queue-not-kill);
 *  - **B2 whole-book scan** ([wholeBookJob], same [canonicalDispatcher] FIFO): skips the active
 *    chapter (B1 owns it), ordered by [readingDirection];
 *  - **P neighbor preflight** ([preflightJobs] on [backgroundDispatcher], lowest): markup + light
 *    cascade only, no shaping, idempotent per paramHash.
 */
class BookDocumentController(
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
    /** Injected B1/B2 canonical dispatcher (tests/desktop); null = a private single-thread
     *  dispatcher off the shared pool (default production behavior — full-chapter shapes never
     *  compete with the foreground flip).
     *  (C2-P0: was `Executors.newSingleThreadExecutor`, JVM-only.) */
    injectedCanonicalDispatcher: kotlinx.coroutines.CoroutineDispatcher? = null,
    /** Optional EPUB image loader for `<img>` rendering; null = images fall back to placeholders. */
    private val imageLoader: ImageLoader? = null,
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

    /** Per-chapter canonical (B1, chapter-head full-chapter layout → LINE_DISK) slots (P3/R3, contract
     *  #6). ONE SLOT PER CHAPTER, keyed by chapterIndex. A dispatch cancels ONLY ITS OWN chapter's
     *  previous in-flight slot (same chapter re-shaped with newer params — genuinely stale); any OTHER
     *  chapter's B1 is left running on the single canonical thread's FIFO queue and finishes its
     *  persist. The cross-chapter flip never kills the just-tuned chapter's canonical (old design's
     *  single global `anchorBackfillJob` did), so returning to it is a disk hit, not a re-temp.
     *  Bounded by chapter count. Written ONLY on the dispatch thread (no lock — matches the plan's
     *  "plain concurrent container" risk note); a completed slot stays until the same chapter is next
     *  dispatched, where [kotlinx.coroutines.Job.cancel] on it is a no-op. */
    private val canonicalJobs: MutableMap<Int, kotlinx.coroutines.Job?> = HashMap()

    /** True while the settings panel is open: the heavy background canonical full-chapter relayout is
     *  suppressed so it never contends with the foreground real-time temp shaping (they share CPU).
     *  On panel close, [finalizeRelayoutAll] re-runs the canonical/full relayout wholesale. */
    var deferCanonical: Boolean = false

    /** Global layout epoch (P2): monotonically increasing; incremented on every [prepareRelayout].
     *  Every background job carries the epoch it was launched under and skips when it is stale
     *  (a newer params/target cycle superseded it). */
    @Volatile
    var layoutEpoch: Long = 0
        private set

    /** Epoch the most recently dispatched whole-book (B2) job was launched under; used to suppress
     *  re-dispatch of the same settled params (no churn while dragging). */
    @Volatile
    private var lastWholeBookEpoch: Long = -1L

    /** The chapter most recently prepared by [prepareRelayout]; the B2 scan skips it (B1 owns its
     *  canonical pass). */
    @Volatile
    private var activeChapter: Int = -1

    /** The direction the reader is moving (P12): +1 = toward the book tail, -1 = toward the head.
     *  Updated at the single reader-facing flip entry ([findAdjacentPage] — reached by every page turn,
     *  in-chapter temp/canonical and out-of-bounds cross-chapter) and by the direct
     *  [nextPageInChapter]/[prevPageInChapter] entries (curl-adjacent pre-render). Used to order the
     *  whole-book B2 scan so chapters the reader is heading toward are pre-laid-out first — a
     *  flip-out-of-bounds into a nearby chapter lands on an already-laid-out one. Defaults forward
     *  (the dominant reading direction). */
    @Volatile
    private var readingDirection: Int = 1

    /** The in-flight background whole-book remaining-chapter scan (B2, was `otherChaptersJob`).
     *  Re-dispatched with the latest params after the params-settled point; a newer dispatch cancels
     *  it (abandon within ≤1 chapter via per-chapter checkpoints). */
    private var wholeBookJob: kotlinx.coroutines.Job? = null

    /** P4 (track P): per-chapter background preflight slots — [index] → in-flight job. Each prelinks
     *  one neighbor chapter's markup + light cascade off the flip thread (no shaping, no table), so an
     *  out-of-bounds flip's parse leaves the critical path. Keyed and idempotent: a param change
     *  ([prepareRelayout]) cancels all slots and voids the readiness record. */
    private val preflightJobs: MutableMap<Int, kotlinx.coroutines.Job> = HashMap()

    /** P4: chapters whose markup + light structure were already prepped, chapter → the paramHash they
     *  were prepped under. A later change of the (paramHash-determining) typography throws it out; the
     *  light tree itself is structure-keyed (cssBundle + useOriginalStyle) so it survives. */
    private val preflightReadiness: MutableMap<Int, Long> = HashMap()

    /** Independent single-thread dispatcher reserved for heavy canonical / full-chapter relayout. Kept
     *  off [backgroundDispatcher]/[Dispatchers.Default] so a full-chapter (or whole-book) shape never
     *  competes with the foreground page-flip / real-time shaping on the same thread pool.
     *  (C2-P0: multiplatform default; was `Executors.newSingleThreadExecutor`, JVM-only.) */
    private val canonicalDispatcher: kotlinx.coroutines.CoroutineDispatcher by lazy {
        injectedCanonicalDispatcher ?: Dispatchers.Default.limitedParallelism(1)
    }

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

    /** Shared okio pagination store over [cacheRoot] (C1-2: the retired `File` adapter's logic now
     *  lives in common [PaginationCacheStore]). Null = disk caching disabled. */
    private fun cacheStore(): PaginationCacheStore? =
        cacheRoot?.let { PaginationCacheStore(okio.FileSystem.SYSTEM, it) }

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
     * @param bookId Primary key of the book (<0 means don't read progress, start from beginning).
     * @param saved Saved progress record (common [BookReadingState]); null = start from beginning.
     */
    suspend fun open(bookId: Long, saved: orilumn.reader.data.book.BookReadingState?): Boolean {
        this.bookId = bookId
        val t0 = platformNowMs()
        val parsedBook = runCatching { parser.parse(reader) }.getOrNull() ?: return false
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
            "parse=${t1 - t0}ms skeleton=${t2 - t1}ms spine0=${ctx(0)}")

        // Restore progress (equal chapter weights)
        if (bookId >= 0 && saved != null) parseLocator(saved.locator)?.let { (ch, char) ->
            startChapter = ch.coerceIn(0, chapterCount - 1)
            startChar = char.coerceAtLeast(0)
            Logger.w(logTag, "open: restore -> startCh=$startChapter char=$startChar ${ctx(startChapter)}")
        }
        return true
    }

    /**
     * 定位串解码单源见 [orilumn.reader.data.read.ReadingLocatorCodec]（写 `chapter:char`，
     * 读兼容 foliate JSON 旧行与 `chapter:char`）。
     */
    private fun parseLocator(locator: String?): Pair<Int, Int>? =
        orilumn.reader.data.read.ReadingLocatorCodec.decode(locator)

    /**
     * 原书设置快照（Q2 下沉：原 `ReaderActivity.withBookStyle`）：切到原书设置时，
     * 把当前章节的真实排版（首行缩进/段间距/行距）快照进设置值。探测失败原样返回。
     */
    fun snapshotBookStyle(chapter: Int, bodyPx: Float, base: ReaderSettings): ReaderSettings {
        if (base.layoutTheme != "original") return base
        val snap = runCatching {
            val unit = unitAt(chapter) ?: return@runCatching null
            val markup = unit.markup ?: return@runCatching null
            val sheets = (unit.cssBundle?.cssTexts ?: emptyList()).map { LightCssParser().parse(it) }
            val styles = StyleComputer(bodyPx, StyleSheet(emptyList()), sheets).compute(markup)
            BookStyleProbe.snapshot(styles)
        }.getOrNull() ?: return base
        return base.copy(
            firstLineIndent = snap.firstLineIndent,
            paragraphSpacing = snap.paragraphSpacing,
            lineSpacing = snap.lineSpacing,
        )
    }

    /** Start location after loading (null when there is no book/no chapter).
 *   Returns (chapter, page): starting from [startChapter], skips chapters with no laid-out
 *   content (cover/empty chapters) going forward, and within the located chapter aligns to the
 *   page for [startChar]; when there is no progress, falls back to the chapter's first page. */
    suspend fun locateStart(): Pair<Int, PageSlice>? {
        if (chapters.isEmpty()) return null
        var ch = startChapter.coerceIn(0, chapters.size - 1)
        var char = startChar
        while (ch < chapters.size) {
            val unit = ensureChapterLayout(ch, char)
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

    /** Chapter title text (kept local: only the title probe uses the joined string). */
    private fun collectText(el: orilumn.reader.engine.html.MarkupElement): String {
        return if (el.isText) el.text
        else el.children.joinToString("") { collectText(it) }
    }

    /** Injects the viewport size; returns whether it changed (a change requires relayout). */
    fun setViewport(width: Int, height: Int): Boolean {
        val changed = width > 0 && height > 0 && (width != viewW || height != viewH)
        if (width > 0 && height > 0) {
            viewW = width
            viewH = height
        }
        return changed
    }

    // ---- Layout ----

    /** Parses and caches [ChapterUnit.markup] for [index] WITHOUT laying the chapter out, so callers
     *  can inspect content length / significance (or compute a tail anchor) before deciding how the
     *  chapter should be built. Idempotent; no-op when already parsed. */
    private suspend fun ensureMarkup(index: Int): MarkupElement? {
        val unit = unitAt(index) ?: return null
        if (unit.markup != null) return unit.markup
        layoutMutex.withLock {
            if (unit.markup == null) {
                val tree = readChapter(index)
                if (tree != null) unit.ensureMarkup(tree, chapterTitle(tree))
            }
        }
        return unit.markup
    }

    /** Ensures a chapter is laid out (lazy): on first need, parses the body (ensureMarkup) then
     * lays it out.
     * @param targetChar Optional character offset within the chapter that we want to land on. Used
     *   by the incremental (disk-cache hit) path to shape only the page containing this offset
     *   instead of shaping the entire chapter. */
    suspend fun ensureChapterLayout(index: Int, targetChar: Int = 0, headLift: Boolean = true): ChapterUnit? {
        val unit = unitAt(index) ?: return null
        if (unit.markup == null) ensureMarkup(index)
        // 整形前先备字体：本章需求命中的导入面进池（锁外 IO），池变则旧版式作废重排，
        // 首绘即对，开屏后不再跳变。
        if (unit.markup != null) {
            val bc = layouter as? BoxChapterLayouter
            if (bc != null && onDemandFonts(bc.fontDemandFor(unit.cssBundle))) {
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
        return unit
    }

    /** Checks whether the page containing [targetChar] (plus a small look-ahead) has been shaped
     *  in the current incremental layout. If not, re-runs [BoxChapterLayouter.incrementalLayoutForPage]
     *  starting from the closest page index. */
    private fun ensurePageRangeShaped(unit: ChapterUnit, targetChar: Int) {
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

        // Capture the old window's edges (they get replaced below) for the seam canary.
        val oldFrom = unit.shapedPageFrom
        val oldTo = unit.shapedPageTo
        val oldLast = unit.pageSlices.getOrNull(oldTo - 1)
        val oldFirst = unit.pageSlices.getOrNull(oldFrom)

        // Need to (re)shape this page and a few look-ahead pages.
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val t0 = platformNowMs()
        val prepare = boxLayouter.prepareLight(unit.markup!!, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight)
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
        )
        unit.bind(newProduct.layout, newProduct.slices)
        unit.shapedPageFrom = targetPage
        unit.shapedPageTo = (targetPage + pagesToShape).coerceAtMost(table.pages.size)
        Logger.w(logTag, "ensurePageRangeShaped ${ctx(unit)} page=$targetPage t=${platformNowMs() - t0}ms")

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
        val markup = unit.markup ?: return
        val t0 = platformNowMs()
        runCatching {
            val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
            val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
            val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()

            // Check disk cache (shared okio store; bookId<0 = no namespace → disabled).
            val cache = cacheStore()
            val cacheFile = if (cache != null && bookId >= 0) {
                cache.file("book_$bookId", unit.chapterIndex, paramHash)
            } else null
            val cached = if (cache != null) cacheFile?.let { cache.read(it) } else null

            if (cached != null) {
                // PaginationMode.LINE_DISK: the disk CFI table holds the authoritative line-level page
                // boundaries; foreground only shapes the target page's block range, never re-paginates.
                unit.bindPaginationTable(cached)
                val boxLayouter = layouter as? BoxChapterLayouter
                if (boxLayouter != null) {
                    // Light prepare only (box tree, no shaping) — the table tells us which blocks to shape.
                    val startPage = pageIndexForChar(cached.pages, targetChar)
                    val prep = boxLayouter.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight)
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
                    )
                    Logger.w(logTag, "DISK-HIT shape t=${platformNowMs() - sp}ms shapedPages=${product.slices.count { it.firstLine >= 0 }} blocks=[${product.slices[startPage].blockStart},${product.slices[startPage].blockEndExclusive}) target=$startPage")
                    unit.bind(product.layout, product.slices)
                    unit.shapedPageFrom = startPage
                    unit.shapedPageTo = (startPage + 4).coerceAtMost(cached.pages.size)
                    Logger.w(logTag, "layout ${ctx(unit)} DISK-HIT+INCREMENTAL page=$startPage t=${platformNowMs() - t0}ms")
                } else {
                    // Legacy layouter: still full layout.
                    val product = layouter.layout(markup, unit.cssBundle, profile, contentWidth, contentHeight)
                        ?: error("layout failed")
                    unit.bind(product.layout, product.slices)
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
                    val light = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight)
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
                unit.bind(layout, slices)

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
                }

                val totalH = if (layout.lineCount > 0) layout.getLineBottom(layout.lineCount - 1) else 0
                Logger.w(logTag, "layout ${ctx(unit)} view=${viewW}x$viewH lines=${layout.lineCount} totalH=$totalH pages=${slices.size} " +
                    "t=${platformNowMs() - t0}ms")
                Logger.d(logTag, "layout ${ctx(unit)} lines=${layout.lineCount} pages=${slices.size} t=${platformNowMs() - t0}ms")
            }
        }.onFailure {
            Logger.e(logTag, "layout FAIL ch=${unit.chapterIndex} ${ctx(unit)} ${it.message}", it)
            Logger.e(logTag, "layout FAIL ch=${unit.chapterIndex} ${it.message}", it)
            unit.bindSafeEmpty()
        }
    }

    /** Chapters at or below this many blocks are laid out fully (small chapters are fast enough and a
 *  cleaner pagination than block-cut). Larger chapters use anchor-based incremental streaming. */
private val SMALL_CHAPTER_BLOCKS = 120

/** P13 (U6k): chapters strictly larger than [SMALL_CHAPTER_BLOCKS] shape their canonical pass on the
 *  parallel chunk workers (U6k) so the background canonical and the foreground track never serialize;
 *  small chapters keep the single-block sequential shape (chunking buys nothing below ~2 chunks). */
private val CHUNK_CANONICAL_MIN_BLOCKS = SMALL_CHAPTER_BLOCKS + 1

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
        val anchor = bc.shapeAnchorPageForward(prepare, snapshot, contentW, contentH, anchorCharAt, cache)
        // C2-P2b-3: `withLock` 非内联，块里不能裸 `return` —— 超期标记外置，语义不变。
        val superseded = tempStateLock.withLock {
            if (unit.tempBirth !== birth) true else {
                ip.forwardPages.add(anchor.page)
                ip.shapedForwardTo = anchor.nextBlock
                ip.forwardFromLine = anchor.nextLine
                ip.anchorLineCharStart = anchor.page.slice.charStart
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
    scheduleTempPrefill(unit, ip)

    // Background: full-canonical layout from the chapter head (LINE_DISK), then persist. The passed-in
    // [prepare] is the light (unshaped) one used by the foreground temp pages, so the canonical pass
    // re-runs the heavy `prepare` to get the full-chapter line geometry that `fullLayout` needs.
    // While the settings panel is open (deferCanonical) this is skipped — only the foreground temp page
    // is live — and [finalizeRelayoutAll] (panel close) re-runs it, so live slider tweaks never contend
    // with a full-chapter shape. Runs on the dedicated canonicalDispatcher (single thread) so it never
    // competes with the foreground flip thread.
    if (!deferCanonical) {
        // P3 queue-not-kill (R3, contract #6): canonical jobs are keyed ONE SLOT PER CHAPTER. A new
        // dispatch cancels only ITS OWN chapter's previous B1 (same chapter re-shaped with newer
        // params — genuinely stale); ANY OTHER chapter's in-flight B1 is left running on the single
        // canonical thread's FIFO queue and finishes its persist. Until today a cross-chapter flip
        // cancelled the just-tuned chapter's canonical wholesale (`anchorBackfillJob?.cancel()`), so
        // returning to it re-temp'd instead of hitting its fresh disk table. Now it lands a disk hit.
        canonicalJobs.remove(unit.chapterIndex)?.cancel()
        canonicalJobs[unit.chapterIndex] = scope.launch(canonicalDispatcher) {
            if (epoch != layoutEpoch) {
                Logger.w(logTag, "canonical superseded (epoch $epoch != $layoutEpoch) ch=${unit.chapterIndex}")
                return@launch
            }
            // P7: capture the launching coroutine's context so the between-blocks checkpoint can
            // observe this job's cancellation even inside the nested runCatching/inner lambdas.
            val ctx = coroutineContext
            runCatching {
                val heavy = unit.markup?.let { bc.prepare(it, unit.cssBundle, profile, contentW, contentH) }
                // P13 (U6k): large chapters shape their canonical pass on the parallel chunk workers
                // (identical slices by construction; see BoxChapterLayouter.fullLayoutChunked) so the
                // single canonical thread never serializes the whole chapter's StaticLayout shaping.
                val prod = heavy?.let {
                    if (it.totalBlocks >= CHUNK_CANONICAL_MIN_BLOCKS) {
                        bc.fullLayoutChunked(it, profile, contentW, contentH) { ctx.ensureActive() }
                    } else {
                        bc.fullLayout(it, profile, contentW, contentH) { ctx.ensureActive() }
                    }
                }
                if (prod != null) finishCanonicalBackground(unit, ip, prod, paramHash)
            }.onFailure { e ->
                // P7: a between-blocks cancel throws CancellationException — that is a TIMELY ABANDON,
                // not a failure. Never report it as FAIL (the per-chapter slot was re-used by design).
                if (e !is CancellationException) Logger.e(logTag, "canonical layout FAIL ch=${unit.chapterIndex} ${e.message}")
            }
        }
    } else {
        Logger.w(logTag, "canonical deferred (panel open) ch=${unit.chapterIndex}")
    }
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
    if (endExcl <= 0) return null
    return bc.shapeTempPageBackward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, endExcl, lineCut, ip.shapes)
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
private fun stepTempPrefill(ip: InProgressPagination, lastDir: Int): Boolean = tempStateLock.withLock {
    val bc = layouter as? BoxChapterLayouter ?: return@withLock false
    val prep = ip.prepare
    val f = ip.forwardPages
    val b = ip.backwardPages
    // Forward neighbor (depth 1): the page right AFTER the current one in reading order.
    val forwardMissing = if (ip.curIsForward) ip.curIndex + 1 >= f.size else ip.curIndex == 0 && f.isEmpty()
    // Backward neighbor (depth 1): the page right BEFORE the current one (toward the chapter head).
    val backwardMissing = if (!ip.curIsForward) ip.curIndex + 1 >= b.size else ip.curIndex == 0 && b.isEmpty()

    // Both sides are filled by the rule pair below: 0 = not applicable (already present / out of
    // range), 1 = a page was shaped, 2 = a required shape failed (abort the whole prefill).
    fun fillForward(): Int {
        if (!forwardMissing || ip.shapedForwardTo >= prep.totalBlocks) return 0
        val fwd = bc.shapeTempPageForward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, ip.shapedForwardTo, ip.shapes, ip.forwardFromLine)
            ?: return 2
        f.add(fwd.page)
        ip.shapedForwardTo = fwd.nextBlock
        ip.forwardFromLine = fwd.nextLine
        enforceTempWindow(ip)
        return 1
    }
    fun fillBackward(): Int {
        if (!backwardMissing) return 0
        val p = shapeNextBackward(ip, bc) ?: return 2
        b.add(p)
        if (p.blockStart == 0) ip.headReached = true
        enforceTempWindow(ip)
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
private fun scheduleTempPrefill(unit: ChapterUnit, ip: InProgressPagination, lastDir: Int = 1) {
    val cursor = (ip.sessionId shl 32) or
        ((if (ip.curIsForward) 1L else 0L) shl 20) or
        ((lastDir and 0x3).toLong() shl 16) or ip.curIndex.toLong()
    if (cursor == lastPrefillCursor && tempPrefillJob?.isActive == true) return
    lastPrefillCursor = cursor
    tempPrefillJob?.cancel()
    tempPrefillJob = scope.launch(backgroundDispatcher) {
        try {
            var guard = 0
            while (stepTempPrefill(ip, lastDir) && guard++ < TEMP_WINDOW_DEPTH * 16) kotlinx.coroutines.delay(4)
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
    return box.prepare(markup, unit.cssBundle, profile, contentWidth, contentHeight).also { unit.bindPrepare(it, paramHash) }
}

/** The current temp page the navigation pointer refers to. */
private fun currentTempPage(ip: InProgressPagination): TempPage? =
    if (ip.curIsForward) ip.forwardPages.getOrNull(ip.curIndex)
    else ip.backwardPages.getOrNull(ip.curIndex)

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
private fun tempNav(unit: ChapterUnit, ip: InProgressPagination, slice: PageSlice, dir: Int): Pair<Int, PageSlice>? {
    // C2-P2b-3: `withLock` 非内联，块里裸 `return` 全改 `return@withLock`，外层 `return` 接住表达式值。
    return tempStateLock.withLock {
    // Serialize with the background temp-prefill so list mutations + the shared shape cache are safe.
    val bc = layouter as? BoxChapterLayouter ?: return@withLock null
    // P12 race hardening: [navigateAdjacentPage] snapshots [ip] WITHOUT this lock, so a relayout / leave
    // can bind a NEW session between that snapshot and this lock. Navigating the detached old session
    // would return a page that is no longer on the live session — 页面缺失/混乱. Relocate the displayed
    // slice in the LIVE session and navigate that one instead.
    val live = unit.inProgress ?: return@withLock null
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
                if (ip.shapedForwardTo >= prep.totalBlocks) { Logger.w(logTag, "tempNav forward tail → null ch=${ip.chapterIndex}"); return@withLock null }
                val fwd = bc.shapeTempPageForward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, ip.shapedForwardTo, ip.shapes, ip.forwardFromLine) ?: return@withLock null
                ip.forwardPages.add(fwd.page)
                ip.shapedForwardTo = fwd.nextBlock
                ip.forwardFromLine = fwd.nextLine
                ip.curIndex = ip.forwardPages.size - 1
            }
        } else {
            if (ip.curIndex > 0) ip.curIndex -= 1
            else {
                // Step past the last backward page into forwardPages[0]. That page may have been
                // EVICTED (the bounded window dropped the whole forward side while the reader was deep
                // backward) — re-root it on demand from the page at hand: the forward neighbor of the
                // current backward page starts at its block boundary. (The anchor torn pair can never
                // trigger this: pair atomicity keeps backward[0] + forward[0] together, so the forward
                // side is non-empty whenever the nearest backward page is line-cut.)
                if (ip.forwardPages.isEmpty()) {
                    val startBlock = ip.backwardPages.firstOrNull()?.blockEndExclusive ?: ip.shapedForwardTo
                    if (startBlock >= prep.totalBlocks) { Logger.w(logTag, "tempNav forward tail → null ch=${ip.chapterIndex}"); return@withLock null }
                    Logger.w(logTag, "tempNav RE-ROOT fwd ch=${ip.chapterIndex} startBlock=$startBlock startLine=0 (块边界重启)")
                    val fwd = bc.shapeTempPageForward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, startBlock, ip.shapes, 0) ?: return@withLock null
                    // Re-root the absolute forward front to this new head edge; subsequent flips append
                    // from here instead of the (deep, evicted) old front.
                    ip.shapedForwardTo = fwd.nextBlock
                    ip.forwardFromLine = fwd.nextLine
                    ip.forwardPages.add(fwd.page)
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
                    if (target != null && target.blockStart == 0) return@withLock (resolveHeadArrival(unit, ip) ?: return@withLock null)
                }
            }
            is TempNavBackwardStep.Shape -> {
                val p = bc.shapeTempPageBackward(prep, ip.profileSnapshot, ip.contentW, ip.contentH, step.endExclusive, step.lineCut, ip.shapes)
                    ?: run {
                        Logger.w(logTag, "tempNav backward shapeTempPageBackward → null ch=${ip.chapterIndex} endExclusive=${step.endExclusive} lineCut=${step.lineCut}")
                        return@withLock null
                    }
                ip.backwardPages.add(p)
                if (p.blockStart == 0) ip.headReached = true
                ip.curIsForward = false
                ip.curIndex = ip.backwardPages.size - 1
                Logger.w(logTag, "tempNav backward shape ok blockStart=${p.blockStart} headReached=${ip.headReached} endExclusive=${step.endExclusive}")
                // 现象2: the whole-block packing descending to block 0 produces the SPARSE head page
                // (head blocks under-fill contentH). Never show it — resolve the head arrival on THIS flip
                // (canonical first page, or re-root temp at char 0 = a full head page per shapeAnchorPageForward).
                if (p.blockStart == 0) return@withLock (resolveHeadArrival(unit, ip) ?: return@withLock null)
            }
            TempNavBackwardStep.Boundary -> {
                // Step 3 (现象3): the TRUE chapter-head boundary. Block-0 arrivals are already resolved
                // on the arrival flip (Move/Shape above); this branch covers the remaining boundary
                // sources — the anchor itself sits AT the head (no backward page can precede it), or the
                // reader is ON the (already re-rooted) full head page. Either way the reader stays
                // in-chapter ON the chapter-head page; crossing to the previous chapter happens only on
                // the NEXT backward flip.
                //   - canonical ready: finalize the temp session in place (bind the disk/full table) and
                //     land on the canonical page containing the current char — at the head that is the
                //     FULL canonical first page (char0..contentH), never a sparse head.
                //   - canonical not ready: re-root the temp stream AT the chapter head —
                //     shapeAnchorPageForward(0) fills the head page forward to contentH, so the head
                //     page is full and the forward stream rebuilds from char 0. Mark the session
                //     headLanded so the NEXT boundary (reader already on the full head) crosses straight
                //     to the previous chapter instead of re-landing the identical page (a wasted flip).
                if (ip.headLanded) {
                    Logger.w(logTag, "tempNav boundary head-landed → cross-chapter ch=${ip.chapterIndex}")
                    return@withLock null
                }
                return@withLock resolveHeadArrival(unit, ip)
            }
        }
    }

    // P10 (§6.2): every pointer move slides the window and evicts the far end (never the current page,
    // never a torn pair), so the session stays bounded at ~±TEMP_WINDOW_DEPTH pages.
    enforceTempWindow(ip)
    currentTempPage(ip)?.let { setCurrentTempPage(unit, ip, it) }
    return@withLock (ip.currentSlice?.let { unit.chapterIndex to it } ?: return@withLock null)
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
 * Returns null when the caller should fall through to cross-chapter.
 */
private fun resolveHeadArrival(unit: ChapterUnit, ip: InProgressPagination): Pair<Int, PageSlice>? {
    if (ip.canonicalLayout != null && ip.canonicalSlices.isNotEmpty()) {
        val posChar = 0 // arriving AT the head: land on the canonical page containing char 0 (page 0)
        finalizeTempOnLeave(unit, ip)
        val landing = unit.pageSlices.firstOrNull { it.charStart <= posChar && it.charEnd > posChar }
            ?: unit.pageSlices.firstOrNull()
        if (landing != null) {
            Logger.w(logTag, "tempNav head-arrival → canonical head page ch=${ip.chapterIndex}")
            return unit.chapterIndex to landing
        }
        return null
    }
    Logger.w(logTag, "tempNav head-arrival → re-root temp stream at chapter head ch=${ip.chapterIndex}")
    val bc = layouter as? BoxChapterLayouter ?: return null
    startAnchorStream(unit, 0, ip.prepare, bc, ip.paramHash, ip.contentW, ip.contentH)
    unit.inProgress?.headLanded = true
    val head = unit.inProgress?.currentSlice ?: ip.forwardPages.firstOrNull()?.slice
    if (head != null) {
        Logger.w(logTag, "tempNav head-arrival → full head page ch=${ip.chapterIndex} char=[${head.charStart},${head.charEnd})")
        return unit.chapterIndex to head
    }
    return null
}

/** When the reader leaves the temp table (reached the chapter head / tail / crosses chapters), abandon
 *  the temp pagination and switch to the disk (canonical) pagination per S5 ("翻到章头或离开→废除临时表、
 *  启用磁盘分页表"). Binding happens here on leave — never mid-chapter — so the current page is not
 *  re-rendered in place while the temp session is active. */
private fun finalizeTempOnLeave(unit: ChapterUnit, ip: InProgressPagination) {
    if (ip.canonicalLayout != null) {
        unit.bind(ip.canonicalLayout!!, ip.canonicalSlices)
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
        // Canonical not ready yet at leave: clear the temp session. Under P3 (queue-not-kill) the
        // chapter's own B1 is NOT cancelled by another chapter's dispatch, so it keeps running and
        // persists on the canonical thread even after we leave — the next entry into this chapter is
        // therefore usually a disk hit (S5), not a re-temp.
        unit.invalidateLayout()
        Logger.w(logTag, "temp 作废→重排 ${ctx(unit)} (canonical 未就绪; P3 B1 仍在后台落盘)")
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
    while (next in 0 until chapters.size) {
        val unit = unitAt(next)
        val markup = if (unit != null) ensureMarkup(next) else null
        if (unit == null || markup == null) { next += direction; continue }
        if (!markup.hasSignificantText()) { next += direction; continue }
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
private fun finishCanonicalBackground(
    unit: ChapterUnit,
    ip: InProgressPagination,
    product: ChapterLayouter.ChapterLayoutProduct,
    paramHash: Long,
) {
    val layout = product.layout
    val slices = product.slices
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
    }
    ip.canonicalLayout = layout
    ip.canonicalSlices = slices
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
    suspend fun relayoutTo(chapter: Int, anchorChar: Int): Pair<Int, PageSlice>? {
        invalidateAllLayouts()
        val unit = ensureChapterLayout(chapter, anchorChar, headLift = false) ?: return null
        // Anchor temp streaming: return the anchor page directly.
        unit.inProgress?.let { ip ->
            val p = ip.currentSlice
            if (p != null) return chapter to p
        }
        val page = pageForChar(unit, anchorChar) ?: unit.firstPage() ?: return null
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
     * Relayout after a settings change, keeping the position (a **no-flicker** variant):
     *  - clears layout caches only for **other** chapters, keeping the current chapter's old
     *    layout -> the page being shown stays drawable during relayout, no blank screen;
     *  - computes the current chapter's new layout on a background thread with the new [profile]
     *    and returns the product to be bound;
     *  - the caller atomically replaces it on the **main thread** via [ChapterUnit.bind] + setPage,
     *    so the old page stays visible until the new page is ready.
     */
    suspend fun prepareRelayout(chapter: Int, anchorChar: Int): ReflowResult? {
        layoutEpoch++
        val myEpoch = layoutEpoch
        activeChapter = chapter
        voidPreflight()
        val unit = unitAt(chapter) ?: return null
        val markup = unit.markup ?: return null
        // 整形前先备字体（同 ensureChapterLayout；本函数产物必重建，无需作废）。
        (layouter as? BoxChapterLayouter)?.let { bc ->
            onDemandFonts(bc.fontDemandFor(unit.cssBundle))
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
                val light = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight)
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
                    val slice = lineAnchoredPage(product.layout, anchorChar, contentHeight)
                        ?: product.slices.firstOrNull() ?: return@runCatching null
                    Logger.w(logTag, "relayout ch=$chapter SMALL-FULL prepareLight=${tLight - t0}ms fullLayout=${platformNowMs() - tLight}ms total=${platformNowMs() - t0}ms blocks=${light.totalBlocks}")
                    ReflowResult(chapter, product.layout, product.slices, slice)
                }
            } else {
                val product = layouter.layout(markup, unit.cssBundle, profile, contentWidth, contentHeight)
                    ?: return@runCatching null
                val slice = lineAnchoredPage(product.layout, anchorChar, contentHeight)
                    ?: product.slices.firstOrNull() ?: return@runCatching null
                ReflowResult(chapter, product.layout, product.slices, slice)
            }
        }.getOrNull()
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
        // The wrap-up routes through the same epoch-ized B2 dispatch used by the params-settled point.
        requestWholeBookRelayout()
        return result
    }

    /** B2 trigger (P2): re-run the whole-book remaining-chapter scan with the CURRENT params. No-op when
     *  nothing changed since the last dispatch (epochs equal — no churn during a slider drag), when the
     *  canonical pass is deferred ([deferCanonical], settings panel open), or without a box layouter.
     *  Runs on the dedicated [canonicalDispatcher] single thread; a newer dispatch cancels the in-flight
     *  one (checkpoint per chapter), so abandoning exits within ≤1 chapter granularity. */
    fun requestWholeBookRelayout() {
        val epoch = layoutEpoch
        if (epoch <= lastWholeBookEpoch) return
        if (deferCanonical) return
        val bc = layouter as? BoxChapterLayouter ?: return
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
        lastWholeBookEpoch = epoch
        wholeBookJob?.cancel()
        wholeBookJob = scope.launch(canonicalDispatcher) {
            val current = activeChapter
            // P12: B2 scans in reading order — chapters the reader is heading toward first (direction
            // group), nearest-distance first within each group, so a flip-out-of-bounds into a nearby
            // chapter lands on an already-laid-out one. Pure function; correctness never depends on it
            // (every non-current chapter still completes its full pass + persist).
            val order = remainingScanOrder(current)
            // P7: capture the launching coroutine's context so the per-block checkpoint passed down to
            // fullLayout can observe this scan's cancellation from inside the nested runCatching.
            val ctx = coroutineContext
            Logger.w(logTag, "whole-book B2 start epoch=$epoch paramHash=$paramHash skipCh=$current order=${order.take(8)}…")
            runCatching {
                for (i in order) {
                    if (i == current) continue // B1 owns the active chapter's canonical pass
                    val u = chapters[i]
                    if (u.inProgress != null) continue // a live temp session owns that chapter
                    if (!isActive) {
                        Logger.w(logTag, "whole-book B2 cancelled at ch=$i (≤1 chapter abandon)")
                        return@launch
                    }
                    runCatching { fullLayoutAndPersist(u, bc, contentWidth, contentHeight, paramHash) { ctx.ensureActive() } }
                        .onFailure { e ->
                            // P7: a between-blocks cancel is a timely abandon, not a FAIL.
                            if (e !is CancellationException) Logger.e(logTag, "whole-book ch=${u.chapterIndex} FAIL ${e.message}")
                        }
                }
            }
            Logger.w(logTag, "whole-book B2 done epoch=$epoch")
        }
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
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
        if (preflightReadiness[index] == paramHash) return // already prepped under these params
        if (preflightJobs[index]?.isActive == true) return // dedup the same in-flight target
        preflightJobs[index] = scope.launch(backgroundDispatcher) {
            try {
                ensureMarkup(index)
                val bc = layouter as? BoxChapterLayouter
                if (bc != null && unit.markup != null) {
                    bc.prepareLight(unit.markup!!, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight)
                }
                preflightReadiness[index] = paramHash
            } catch (_: kotlinx.coroutines.CancellationException) {
                // superseded by a param change (voidPreflight) — expected, never a FAIL
            } catch (e: Exception) {
                Logger.e(logTag, "preflight FAIL ch=$index ${e.message}")
            }
        }
    }

    /** P4 abandon/re-deploy: a new params cycle voids every preflight readiness record and cancels the
     *  in-flight slots (the light tree is structure-keyed so the next re-preflight re-uses it cheaply). */
    private fun voidPreflight() {
        preflightReadiness.clear()
        preflightJobs.values.forEach { it.cancel() }
        preflightJobs.clear()
        openPreflightJob?.cancel()
    }

    /** P5: single-slot open-book prewarm (was `preflightJobs[chapter]`; per-book, one in flight). A new
     *  prewarm dispatch (book switch / new target / param change via [voidPreflight]) cancels the old;
     *  idempotent and safe to drop mid-flight (only markup/light/disk-table-shape warming). */
    private var openPreflightJob: kotlinx.coroutines.Job? = null

    /** P5 (track P): prewarm a book open so the reader's FIRST screen only pays window/anchor shaping.
     *  Parses [chapter]'s markup + light structure (idempotent; records [preflightReadiness] like P4)
     *  and — when the disk table for the CURRENT params already exists — binds it and pre-shapes the
     *  page range around [targetChar], promoting the table directly. Never shapes without a table.
     *  Defaults to the open point's saved position (`startChapter`/`startChar`; no progress → chapter 0).
     *
     *  The bookshelf layer taps a book and calls this before the reader opens; switching books abandons
     *  the old book's prewarm because each book owns its controller (single per-book slot). Later
     *  params (font/行距/尺寸) invalidate the readiness and the shape via [prepareRelayout]'s void. */
    fun prewarmForOpen(chapter: Int = -1, targetChar: Int = -1) {
        val books = chapters.size
        val index = (if (chapter >= 0) chapter else startChapter).coerceIn(0, books - 1)
        var char = if (targetChar >= 0) targetChar else startChar
        if (books == 0) return
        val unit = unitAt(index) ?: return
        if (index == activeChapter) return // B1/anchor owns the active chapter's passes
        openPreflightJob?.cancel()
        openPreflightJob = scope.launch(backgroundDispatcher) {
            try {
                ensureMarkup(index)
                val bc = layouter as? BoxChapterLayouter ?: return@launch
                if (unit.markup == null) return@launch
                val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
                val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
                if (contentHeight <= 16) return@launch // viewport not laid out yet — nothing meaningful to warm
                val paramHash = LayoutParamKey.fromProfile(profile, contentWidth, contentHeight).hash()
                val prepLight = bc.prepareLight(unit.markup!!, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight)
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
                    char = char.coerceIn(0, (cached.totalChars - 1).coerceAtLeast(0))
                    val startPage = pageIndexForChar(cached.pages, char)
                    val product = bc.incrementalLayoutForPage(
                        prepare = prepLight,
                        profile = profile,
                        contentW = contentWidth,
                        contentH = contentHeight,
                        table = cached,
                        targetPage = startPage,
                        pagesToShape = 1,
                        cache = unitShapeCache(unit),
                    )
                    unit.bind(product.layout, product.slices)
                    unit.shapedPageFrom = startPage
                    unit.shapedPageTo = (startPage + 1).coerceAtMost(cached.pages.size)
                    Logger.w(logTag, "prewarm ch=$index DISK-HIT pre-shaped pages=${product.slices.size} targetPage=$startPage")
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // superseded by a book switch / newer prewarm / param change — expected, never a FAIL
            } catch (e: Exception) {
                Logger.e(logTag, "prewarm FAIL ch=$index ${e.message}")
            }
        }
    }

    /** Full line-level canonical pre-layout of a non-current chapter: shape the whole chapter, bind its
     *  [orilumn.reader.engine.layout.DrawableBookLayout], and persist the line-level pagination table to
     *  disk so the chapter is ready when opened. No anchor/temp state is created here. */
    private fun fullLayoutAndPersist(unit: ChapterUnit, bc: BoxChapterLayouter, contentW: Int, contentH: Int, paramHash: Long, checkpoint: () -> Unit = {}) {
        val markup = unit.markup ?: return
        val prep = prepareFor(unit, bc, markup, contentW, contentH, paramHash)
        // P13 (U6k): large chapters shape on the parallel chunk workers, small ones sequentially.
        val product = if (prep.totalBlocks >= CHUNK_CANONICAL_MIN_BLOCKS) {
            bc.fullLayoutChunked(prep, profile, contentW, contentH, checkpoint = checkpoint)
        } else {
            bc.fullLayout(prep, profile, contentW, contentH, checkpoint) ?: return
        }
        val slices = product.slices
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
        }
        unit.bind(product.layout, slices)
    }

    /**
     * Builds a page slice that begins at (or above) the line containing [anchorChar], extending
     * downward to fill [contentH]. Rendered by the standard whole-chapter
     * [orilumn.reader.engine.layout.DrawableBookLayout], so the page's first line is the page top.
     *
     * A/B decision for the preceding chapter-title block: if the block right above the anchor line is
     * the chapter's first block (title) and including it keeps the anchor line within the upper half
     * of the page, the page starts at the title (title on top, the anchored line below — the anchor
     * stays on screen). Otherwise the page starts at the anchor line and the title is left whole on
     * the previous page (so it is never torn).
     */
    private fun lineAnchoredPage(layout: orilumn.reader.engine.paging.BookLayout, anchorChar: Int, contentH: Int): PageSlice? {
        val n = layout.lineCount
        if (n <= 0 || contentH <= 0) return null
        var l = 0
        for (i in 0 until n) if (layout.getLineStart(i) <= anchorChar && layout.getLineEnd(i) > anchorChar) { l = i; break }
        var start = l
        // First line of the anchor's block.
        var b0 = l
        while (b0 > 0 && !layout.isParagraphBoundaryLine(b0)) b0--
        // Try to pull the immediately-preceding block onto this page when it is the chapter's first
        // (title) block and it keeps the anchor line in the upper part of the page.
        if (b0 > 0) {
            var p0 = b0 - 1
            while (p0 > 0 && !layout.isParagraphBoundaryLine(p0)) p0--
            if (p0 == 0) {
                val anchorOffset = layout.getLineTop(l) - layout.getLineTop(0)
                if (anchorOffset <= contentH * 0.5f) start = 0
            }
        }
        val base = layout.getLineTop(start)
        var end = start
        while (end < n && layout.getLineBottom(end) - base <= contentH) end++
        if (end == start) end = start + 1
        val charStart = layout.getLineStart(start)
        val charEnd = if (end < n) layout.getLineStart(end) else layout.length
        return PageSlice(charStart, charEnd.coerceAtLeast(charStart), start, end, PageSlice.Kind.TEXT, -1, -1)
    }

    /**
     * A page starting at line [startLine], filling down to [contentH] (char-continuous tiling).
     * Rendered by the whole-chapter layout with the page's first line at the top.
     */
    private fun forwardPageFrom(layout: orilumn.reader.engine.paging.BookLayout, startLine: Int, contentH: Int): PageSlice? {
        val n = layout.lineCount
        if (n <= 0 || contentH <= 0 || startLine < 0 || startLine >= n) return null
        val base = layout.getLineTop(startLine)
        var end = startLine
        while (end < n && layout.getLineBottom(end) - base <= contentH) end++
        if (end == startLine) end = startLine + 1
        val cs = layout.getLineStart(startLine)
        val ce = if (end < n) layout.getLineStart(end) else layout.length
        return PageSlice(cs, ce.coerceAtLeast(cs), startLine, end, PageSlice.Kind.TEXT, -1, -1)
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
        while (start > 0 && layout.getLineBottom(e - 1) - layout.getLineTop(start - 1) <= contentH) start--
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
        val unit = ensureChapterLayout(chapter, char) ?: return null
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
        unit.bind(layout, r.slices)
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
    fun pageLines(chapter: Int, slice: PageSlice): List<DrawLine>? {
        val unit = unitAt(chapter) ?: return null
        if (slice.kind != PageSlice.Kind.TEXT || slice.firstLine < 0) return null
        val layout = (unit.tempRenderLayout ?: unit.layout) as? LayoutReadback ?: return null
        val window = layout.skiaLineWindow() ?: return null
        val out = ArrayList<DrawLine>(slice.lastLineExclusive - slice.firstLine)
        for (i in slice.firstLine until slice.lastLineExclusive) {
            window[i]?.let { out.add(it) }
        }
        // 表格单元格行（表行不产出行窗行，展开附在行下标上；纯表页只有它们）。
        // 与行窗合并后按 yTop 稳定排序（多列表格同行多列同 yTop；map 迭代序不定，见 gutenberg-11 1629/1657 错位）。
        out += layout.tableCellLines(slice.firstLine, slice.lastLineExclusive)
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
        if (slice.kind != PageSlice.Kind.TEXT || slice.firstLine < 0) return null
        val layout = (unit.tempRenderLayout ?: unit.layout) as? LayoutReadback ?: return null
        return layout.pageImages(slice.firstLine, slice.lastLineExclusive).ifEmpty { null }
    }

    /**
     * 与 [pageLines] 同一切片口径的盒背景/边框（章节绝对 Y，与 DrawLine 同一坐标系），
     * 阅读面画在文字之下。空窗口回 null。
     */
    fun pageBackgrounds(chapter: Int, slice: PageSlice): List<orilumn.reader.engine.skia.PageBackground>? {
        val unit = unitAt(chapter) ?: return null
        if (slice.kind != PageSlice.Kind.TEXT || slice.firstLine < 0) return null
        val layout = (unit.tempRenderLayout ?: unit.layout) as? LayoutReadback ?: return null
        return layout.pageBackgrounds(slice.firstLine, slice.lastLineExclusive).ifEmpty { null }
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
        val n = chapters.size
        if (n <= 0) return null
        val target = (fraction.coerceIn(0.0, 1.0) * n).toInt().coerceIn(0, n - 1)
        for (delta in 0 until n) {
            val c = target + delta
            if (c >= n) break
            val u = ensureChapterLayout(c) ?: continue
            val m = u.markup ?: continue
            if (m.hasSignificantText()) return c to (u.firstPage() ?: continue)
        }
        return null
    }

    /**
     * From [chapter], walks along [direction] to find the first page of the next "content-bearing"
     * chapter (previous/next chapter).
     */
    suspend fun neighborChapterStart(chapter: Int, direction: Int): Pair<Int, PageSlice>? {
        var c = chapter
        while (true) {
            c += direction
            if (c !in 0 until chapters.size) return null
            val u = ensureChapterLayout(c) ?: continue
            val m = u.markup ?: continue
            if (m.hasSignificantText()) return c to (u.firstPage() ?: continue)
        }
    }

    /** Locates the first content page of (or after) [index], skipping blank/cover chapters.
 *  Used by the TOC panel to jump to an arbitrary chapter. */
    suspend fun openChapterStart(index: Int): Pair<Int, PageSlice>? {
        var ch = index.coerceIn(0, chapters.size - 1)
        while (ch < chapters.size) {
            val unit = ensureChapterLayout(ch)
            val m = unit?.markup
            if (unit != null && m != null && m.hasSignificantText()) {
                val page = unit.inProgress?.currentSlice ?: unit.firstPage()
                if (page != null) return ch to page
            }
            ch++
        }
        return null
    }

    /**
     * TOC jump honoring the item's in-chapter anchor: lands on the page whose first line is the
     * element carrying [fragment] (a depth>0 subsection, e.g. `#sec3`), instead of the chapter head.
     * Falls back to [openChapterStart] when the fragment is absent/blank, the target heading cannot
     * be resolved, or the chapter isn't content-bearing. Re-anchors pagination at the fragment via
     * [relayoutTo], which covers both the disk-table and the live-temp paths.
     */
    suspend fun openTocItem(index: Int, fragment: String?): Pair<Int, PageSlice>? {
        if (fragment.isNullOrBlank() || index !in chapters.indices) return openChapterStart(index)
        val m = ensureMarkup(index) ?: return openChapterStart(index)
        val charStart = contentFragmentIdCharStart(m, fragment) ?: return openChapterStart(index)
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
        val markup = unit.markup ?: return null
        val bc = layouter as? BoxChapterLayouter ?: return null
        if (viewW <= 0 || viewH <= 0) return null
        val contentWidth = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentHeight = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val prepare = bc.prepareLight(markup, unit.cssBundle, profile, contentWidth, unit.structureCache, contentHeight)
        if (prepare.totalBlocks <= 0) return null
        val idx = prepare.blockIndexForChar(charOffset.coerceAtLeast(0))
        val leafStart = prepare.globalCharStarts.getOrNull(idx)?.toInt() ?: return null
        val href = prepare.linkRangesAt(idx)
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
        if (target.chapterIndex !in chapters.indices) return null
        val fragment = target.fragment
        if (fragment.isNullOrBlank()) return openChapterStart(target.chapterIndex)
        val m = ensureMarkup(target.chapterIndex) ?: return openChapterStart(target.chapterIndex)
        val charStart = contentFragmentIdCharStart(m, fragment) ?: return openChapterStart(target.chapterIndex)
        return relayoutTo(target.chapterIndex, charStart) ?: openChapterStart(target.chapterIndex)
    }

    /** Normalized spine-href → chapter index (the [LinkTargets] lookup table for this book). */
    private fun spineIndexByNormalizedHref(): Map<String, Int>? {
        val spine = book?.spine ?: return null
        val map = HashMap<String, Int>(spine.size)
        for (item in spine) {
            val (path, _) = LinkTargets.splitFragment(item.href)
            map[LinkTargets.normalizePath(path)] = item.index
        }
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
        return slices.firstOrNull { it.charStart <= charOffset && it.charEnd > charOffset }
            ?: slices.last()
    }

    fun unitAt(index: Int): ChapterUnit? = chapters.getOrNull(index)

    /** One page forward within a chapter. A line-anchored slice (not a canonical page) snaps to the
 *  canonical page after the one containing its char, avoiding content skips. */
    fun nextPageInChapter(unit: ChapterUnit, slice: PageSlice): PageSlice? {
        readingDirection = 1
        val slices = unit.pageSlices
        val idx = slices.indexOf(slice)
        if (idx >= 0) return slices.getOrNull(idx + 1)
        val c = slices.indexOfFirst { it.charStart <= slice.charStart && slice.charStart < it.charEnd }
        return if (c >= 0) slices.getOrNull(c + 1) else null
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
        readingDirection = direction
        // P4: a flip that lands in [chapter] prewarms both neighbors (the chapters a next out-of-bounds
        // flip can enter) on the background P track — so the crossing's parse leaves the flip thread.
        return navigateAdjacentPage(chapter, slice, direction)?.also { (ch, _) -> preflightNeighbors(ch) }
    }

    /** The actual adjacent-page navigation (direction already tracked by [findAdjacentPage] — see P12). */
    private suspend fun navigateAdjacentPage(chapter: Int, slice: PageSlice, direction: Int): Pair<Int, PageSlice>? {
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
        Logger.w(flipTag, "FLIP dir=$direction ch=$chapter title=${unit.title?.take(16) ?: "-"} " +
            "|slice[${slice.firstLine},${slice.lastLineExclusive})char[${slice.charStart},${slice.charEnd})blk[${slice.blockStart},${slice.blockEndExclusive})] " +
            "|inProgress=${ip != null}${ip?.let{"anchorBlk=${it.anchorBlockStart} anchorLineCh=${it.anchorLineCharStart} curFwd=${it.curIsForward} curIdx=${it.curIndex} head=${it.headReached} fwdPages=${it.forwardPages.size} bwdPages=${it.backwardPages.size}"} ?: ""} " +
            "|diskTable=${pt != null}${pt?.let{"pages=${pt.pages.size}"} ?: ""} " +
            "|layoutLC=$layoutLc tempLC=$tempLc " +
            "|pageSlices=${ps.size}${ps0?.let{" f[${it.charStart},${it.charEnd})"} ?: ""}${psN?.let{" l[${it.charStart},${it.charEnd})"} ?: ""} " +
            "|laidOut=${unit.laidOut} paramHash=${unit.paramHash}")
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
                if (unit.inProgress === ip) scheduleTempPrefill(unit, ip, direction)
                return it
            }
            // tempNav returned null — the temp stream reached its boundary (headReached or tail).
            // Do NOT fall through to pageSlices: prepareRelayout intentionally keeps the old disk
            // slices around to avoid a flash-of-white during the relayout, but they carry stale page
            // breaks from the previous typographic params. Skipping pageSlices here means a
            // head/tail-boundary flip during an active temp session goes straight to cross-chapter
            // (S5: 离开→废除临时表、启用磁盘分页表) instead of mis-identifying the current slice in
            // stale pageSlices and wandering onto wrong pages.
            Logger.w(logTag, "tempNav null → skip pageSlices fallthrough ch=${unit.chapterIndex} dir=$direction")
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
            ensureChapterLayout(chapter, inChapter.charStart)
            return chapter to unit.pageSlices.getOrElse(i) { inChapter }
        }
        Logger.w(logTag, "flip: 无章内页，开始跨章 dir=$direction from ch=$chapter")
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
        readingDirection = -1
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
            // User-layer annotations (e.g. full-width image figures) run here, once, and are cached
            // with the chapter tree. Author stylesheets feed a book-only cascade so a book that
            // sized an image itself is never stretched.
            // P2: 与排版同视口的 @media 求值（内容区全视口）。
            val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
            val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
            val viewport = orilumn.reader.engine.css.CssViewport(contentW, contentH)
            val authorSheets: List<StyleSheet> =
                (unitAt(index)?.cssBundle?.cssTexts ?: emptyList()).map { LightCssParser().parse(it, viewport) }
            return ChapterPreprocessor.preprocess(parsed.tree, authorSheets)
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
        val roots = ArrayList<Pair<String, String>>()
        for (style in parsed.styles) roots.add(spineHref to style)
        for (href in parsed.linkHrefs) {
            val resolved = reader.resolveRelative(spineHref, href)
            reader.readText(resolved)?.takeIf { it.isNotBlank() }?.let { roots.add(resolved to it) }
        }
        if (roots.isEmpty()) return CssBundle(emptyList())
        // P2: @import 媒体条件按内容区视口求值（与排版 parse 同值）。
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(16)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(16)
        val viewport = orilumn.reader.engine.css.CssViewport(contentW, contentH)
        val flat = orilumn.reader.engine.css.resolveCssImports(
            roots.map { it.second },
            roots.map { it.first },
            spineHref,
            reader::resolveRelative,
            { h -> runCatching { reader.readText(h) }.getOrNull() },
            viewport,
        )
        return CssBundle(flat.map { it.second }, flat.map { it.first })
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

    /** Chapter title: first 20 characters of the text from the first non-empty paragraph; empty
     * when none. */
    private fun chapterTitle(markup: orilumn.reader.engine.html.MarkupElement): String {
        val t = collectText(markup).trim()
        return t.take(20).replace(Regex("\\s+"), " ")
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
        orderRemainingChapters(chapters.size, current, readingDirection)

    fun currentReadingDirection(): Int = readingDirection

    /** P4 probe accessor: whether [chapter]'s markup + light structure were prepped under the current
     *  param cycle on the background P track (preflightReadiness holds a key). */
    /** C2-P2b-4: public probe hook (was `internal`; `:app` probe tests live across the module seam). */
    fun isChapterPreflightReady(chapter: Int): Boolean = preflightReadiness.containsKey(chapter)
}

/** Page anchor (stable handle for locating a relayout). */
data class PageAnchor(val chapter: Int, val char: Int)

/** Orders the non-current chapters for the whole-book B2 scan (P12): the reader's reading-direction
 *  group first (chapters the reader is heading toward — the ones a flip-out-of-bounds will land in),
 *  then nearest-distance-first inside each group (flip-out-of-bounds targets are always near, and far
 *  chapters still all complete + persist). Pure — correctness never depends on the visit order; a
 *  cancel still exits within ≤1 chapter granularity via the per-chapter checkpoints (P7/P13).
 *
 *  @param total     chapter count
 *  @param current   the active chapter (skipped by the caller regardless — B1 owns its pass)
 *  @param direction the reader's movement: >= 0 = toward the tail (forward group first),
 *                   < 0 = toward the head (backward group first)
 */
/** C2-P2b-4: public probe hook (was `internal`; `:app` probe tests live across the module seam). */
fun orderRemainingChapters(total: Int, current: Int, direction: Int): List<Int> {
    val ahead = (current + 1 until total).toList()
    val behind = (current - 1 downTo 0).toList()
    return if (direction >= 0) ahead + behind else behind + ahead
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