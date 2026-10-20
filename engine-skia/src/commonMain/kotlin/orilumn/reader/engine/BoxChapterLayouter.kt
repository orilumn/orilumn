package orilumn.reader.engine

import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.identityMap
import orilumn.reader.collections.withLock
import orilumn.reader.engine.EngineDiag
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.FloatSide
import orilumn.reader.engine.css.cssHexToArgb
import orilumn.reader.engine.css.FontDemand
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderUiSheet
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.StyleSheet
import orilumn.reader.engine.css.collectFontDemand
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.css.uaSheetFromProfile
import orilumn.reader.engine.html.BLOCK_TAGS
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.layout.ListMarkers
import orilumn.reader.engine.laying.ParagraphShapeRef
import orilumn.reader.engine.laying.ShapedGeometry
import orilumn.reader.engine.laying.adjustLineHeightsForInlineImages
import orilumn.reader.engine.skia.shapeGeometry
import orilumn.reader.io.Logger
import orilumn.reader.engine.laying.BoxLayoutResult
import orilumn.reader.engine.laying.breakWrappedLines
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.BoxOffset
import orilumn.reader.engine.laying.ClipRect
import orilumn.reader.engine.laying.contentClip
import orilumn.reader.engine.laying.contentClipOf
import orilumn.reader.engine.laying.relativeOffsetOf
import orilumn.reader.engine.laying.inheritedClip
import orilumn.reader.engine.laying.clipsOverflow
import orilumn.reader.engine.laying.paddingBox
import orilumn.reader.engine.laying.FlowedLine
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.TableGridModel
import orilumn.reader.engine.paging.BookLayout
import orilumn.reader.engine.paging.BreakAwareBookLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.paging.Paginator
import orilumn.reader.engine.text.BoxDrawableLayout
import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * How a chapter's pages are cut, and — critically — **which pagination table the result feeds**.
 * These are not interchangeable choices: each mechanism is permanently bound to one table type and the
 * binding is decided once in `BookDocumentController.buildLayout`, never re-litigated per call.
 *
 *  - [LINE_DISK]: line-level continuous paging (fill-a-page-then-break). Always the source of the
 *    **canonical disk CFI pagination table**. Used only for: disk-hit on-demand shaping
 *    ([BoxChapterLayouter.incrementalLayoutForPage]) and full-chapter layout
 *    ([BoxChapterLayouter.fullLayout] — small-chapter foreground + large-chapter background canonical).
 *    A large chapter's *foreground* is NEVER [LINE_DISK].
 *
 *  - [BLOCK_TEMP]: whole-block page cutting ([AnchorPagePacker]) — each page = whole blocks, cut on
 *    block boundaries. Only ever backs the in-memory **temp table** (`InProgressPagination`) while the
 *    canonical disk table is still being built; never persisted. On reaching the chapter head or
 *    leaving the chapter it is replaced by the canonical [LINE_DISK] table.
 *
 *  Invariant: the two modes are mutually exclusive at any moment, and the disk table / temp table are
 *  respectively line-level / block-cut — never crossed.
 */
enum class PaginationMode { LINE_DISK, BLOCK_TEMP }

/** R6: 平台 CPU 核数（调度塑形槽预算用；iOS actual 后续补）。 */
internal expect fun platformCpuCount(): Int



/**
 * Result of the "fast prepare" phase of chapter layout — everything that can be done for the
 * whole chapter without invoking any per-block text shaping (the expensive part).
 *
 * [BoxChapterLayouter.prepare] produces this; [BoxChapterLayouter.fullLayout] consumes it.
 * This split exists so that callers can:
 *  - Full-layout small chapters in one shot ([BoxChapterLayouter.layout])
 *  - Incrementally shape only the blocks around the target page for large chapters (Phase 2)
 */
data class ChapterPrepareResult(
    val markup: MarkupElement,
    val styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
    val structure: BoxLayoutResult,
    val leaves: List<LayoutBox>,
    /** [leaves] index → global character offset of that block's first character. */
    val globalCharStarts: LongArray,
    val totalBlocks: Int,
    val totalChars: Int,
    /** Q1-a：与 [structure] 同一套块分类 / 隐藏判定——DrawLineBuilder 投影 [leaves] 文本时
     *  必须用布局同款 class/hidden，几何与绘制才不会漂移（单源 leafText）。 */
    val classify: orilumn.reader.engine.laying.BlockClassify,
    val hidden: orilumn.reader.engine.laying.HiddenCheck,
    /** P3-c 生成内容查找（与塑形同一 phase-1 结果；空即无）。 */
    val genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
    /** `::first-letter` 伪元素查找（章节无该伪规则即 null，塑形侧零开销）。 */
    val firstLetterOf: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? = null,
) {
    /** Locates the block index that contains the given chapter-wide char offset. */
    fun blockIndexForChar(charOffset: Int): Int {
        // Binary search on globalCharStarts (ascending by construction).
        var lo = 0; var hi = globalCharStarts.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val start = globalCharStarts[mid]
            val end = if (mid + 1 <= globalCharStarts.lastIndex) globalCharStarts[mid + 1] else Long.MAX_VALUE
            if (charOffset in start until end) return mid
            else if (charOffset < start) hi = mid - 1
            else lo = mid + 1
        }
        return lo.coerceAtMost(totalBlocks - 1).coerceAtLeast(0)
    }
}

/**
 * The **production** [ChapterLayouter], powered by the box engine (S0–S7 results),
 * and the drawable path of S7:
 *
 *  cascade → box-model geometry → per-paragraph shaping ([ParagraphShapeRef]s) → [BoxDrawableLayout] → pages.
 *
 * Split into [prepare] (fast, full-chapter, no per-block shaping) and [fullLayout] (shape + paginate).
 * The legacy one-shot [layout] delegates to both in sequence.
 *
 * The cascade's reader upper layer (决策 6) carries the reading preferences that must beat the book
 * (行距 → line-height, 段间距 → paragraph margin), while the book's own styles run through the real
 * cascade underneath.
 */
class BoxChapterLayouter(
    /** Reader theme layer (lowest reader layer, above the book). */
    private val themeSheet: StyleSheet? = null,

    /** Reader custom-style layer (highest reader layer; vacant for now). */
    private val customStyleSheet: StyleSheet? = null,

    /** Optional EPUB image loader for `<img>` rendering. When null, images fall back to placeholder
     *  bands. Set via [bindChapterContext] before layout so image paths can be resolved relative to
     *  the current chapter's spine href. */
    private val imageLoader: ImageLoader? = null,
) : ChapterLayouter {

    /** Spine href of the chapter currently being laid out. Set by [bindChapterContext] right before
     *  layout so renderers can resolve relative `src` paths against it. Empty string = no chapter bound. */
    var chapterHref: String = ""
        private set

    /** Binds [chapterHref] so subsequent drawables resolve image paths against this chapter. */
    fun bindChapterContext(href: String) { chapterHref = href }

    /**
     * **重路径 `BoxLayouter` 与轻路径 `LightPrepare`（表格 auto 分列测宽）共用的唯一断行器实例。**
     *
     * ## 为什么必须是**同一个对象**而不是两处各调一次工厂（2026-10-02，Q6 消解）
     *
     * 接线期是两个独立入口：重路径 `BoxLayouter(fs, heavyPathBreaker(...))`，
     * 轻路径 `tableBreaker = SkiaParagraphBreaker(...)`。两处要维持一致就得长期维护两处接线 ——
     * 而**两处接线正是当初重轻分叉的真正来源**（正文一接线，重路径的表格列宽跟着变体走，
     * 轻路径却还钉在 Skia）。
     *
     * 更要紧的是**它让锁打不出牙齿**：只走重路径的回归锁改轻路径的 `tableBreaker` **照样绿**
     * （轻路径的 breaker 只被 `LightPrepare` 消费，重路径压根不经过它 —— 这正是变异 MUT-I
     * 打不死锁的原因）。合实例之后，任何对断行器来源的改动都**同时**打到两条路。
     *
     * ## 缓存键为什么是 `(profile, 变体)` 两个分量
     *
     * `prepare()` 与 `prepareLight()` 是两个函数，各自拿到 [TypographicProfile] 形参，
     * 而 [TypographicProfile.letterSpacingEm] 参与断行器构造 ⇒ **profile 变了必须重建**。
     * 但**只按 profile 缓存是不够的**：断行器实例的**类**由 [orilumn.reader.engine.AbSwitch.inhouseBreak]
     * 决定，而 layouter 的寿命是**整本书**、跨多次排版参数变更。拨一次 `ab="inhouseBreak=0/1"`
     * 之后若还按 profile 命中缓存，就会继续发**旧变体**的实例 —— 于是「回退阀」在缓存命中时
     * 静默失效，且 `paramHash` 变了会重排、重排却拿到同一套断行器。
     * （这个缺陷是锁 7「轻路径也响应变体」当场抓出来的：两侧列宽完全相同。）
     *
     * 判据同 `docs/自建断行引擎-测试计划.md` 教训 ⑩：键的每个分量都要能回答
     * 「它变了，那步昂贵操作真的需要重做吗」。`TypographicProfile` 是值对象，`equals` 可用。
     *
     * ## 敢用变体量列宽的前提
     *
     * 此前表格测宽恒为 Skia，理由是自建侧 `preferredWidth` 是接口默认桩
     * `text.length * fontSizePx`（fs=44.4 下 Latin/URL 高估 2.0~2.6 倍）。该前提已由
     * [orilumn.reader.engine.skia.InhouseParagraphBreaker.preferredWidth] 接
     * `SkiaRunMeasurer.naturalWidth` 解除 —— 实测 CJK/URL/code/混排 **0.0000%**、
     * 含 kern 对的 Latin **+1.104%**（恒为一个 kern 对的像素量），方向**永不反向**。
     */
    private var cachedBreaker: Triple<TypographicProfile, Boolean, orilumn.reader.engine.laying.ParagraphBreaker>? = null

    private fun breakerFor(profile: TypographicProfile): orilumn.reader.engine.laying.ParagraphBreaker {
        val inhouse = orilumn.reader.engine.AbSwitch.inhouseBreak()
        cachedBreaker?.takeIf { it.first == profile && it.second == inhouse }?.let { return it.third }
        val made = orilumn.reader.engine.skia.bodyParagraphBreaker(profile.letterSpacingEm, profile.cjkLatinSpacingEmApplied)
        cachedBreaker = Triple(profile, inhouse, made)
        return made
    }

    // ─────────────────────────────────────────────────────────────────
    // Two-phase public surface
    // ─────────────────────────────────────────────────────────────────

    /**
     * Fast full-chapter prepare: cascade + box-model geometry + leaf extraction.
     * Produces everything except the per-paragraph skia shapes. Called once per chapter
     * (or once per parameter change) and cached on [ChapterUnit].
     */
    fun prepare(
        markup: MarkupElement,
        cssBundle: CssBundle?,
        profile: TypographicProfile,
        contentW: Int,
        /** P2: 版心高（与 contentW 共同组成 `@media` 求值视口；与轻路径同值）。 */
        contentH: Int,
        /** P3: 已绑定的生成内容字符串（结构缓存/持久化，同 CSS 下与现算恒等）：跳过整树求值，
         *  只做门控复核 + 伪样式新鲜装配。Null = 现算（首触/含 media 章）。 */
        genStrings: Map<MarkupElement, Pair<String?, String?>>? = null,
    ): ChapterPrepareResult {
        // P2: 与轻路径同视口的 @media 求值，重轻规则恒一致。
        val authorSheets = parseAuthorSheets(cssBundle, orilumn.reader.engine.css.CssViewport(contentW.coerceAtLeast(1), contentH.coerceAtLeast(1)))
        val engine = styleComputerFor(
            cssBundle, profile,
            authorSheets,
        )
        val styleMap = engine.compute(markup)
        val breaker = breakerFor(profile)
        val boxLayouter = BoxLayouter(profile.bodyPx, breaker, profile.density)
        // Heavy: full-chapter line shaping (real skia break per leaf). Only needed for line-level
        // pagination — small-chapter foreground and large-chapter background canonical. The display gate
        // is shared with the light path so both produce identical leaf sets / globalCharStarts.
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = orilumn.reader.engine.laying.HiddenCheck { styleMap[it]?.displayNone == true }
        // P3-c: 生成内容 phase-1（ gating 命中才整树求值；伪样式按需缓存，重轻同输入同输出）。
        // 已绑定字符串直接复用（P3），否则现算。
        val genOf = genStrings
            ?.takeIf { it.isNotEmpty() }
            ?.let { genOfFromStrings(authorSheets, it, engine) { styleMap[it] } }
            ?: genOfFor(markup, authorSheets, { styleMap[it] }, engine, hidden)
        // `::first-letter`：章节无该伪规则即 null（塑形与断行侧零开销）；命中则按叶根懒解＋缓存。
        val firstLetterOf = firstLetterOfFor(engine) { styleMap[it] }
        val structure = boxLayouter.layoutBoxes(markup, contentW, styleMap, classify, imageLoader, chapterHref, genOf = genOf, firstLetterOf = firstLetterOf, contentH = contentH)
        val leaves = collectLeaves(structure.boxes)
        return buildPrepareResult(markup, styleMap, structure, leaves, classify, hidden, genOf, firstLetterOf)
    }

    /**
     * P3-c: 章节生成内容 phase-1 单源（轻/重两路同式，勿各写一份）。
     *
     * gating（[GeneratedContent.needsPhase]）未命中即 [EmptyGen] 零开销；命中则文档序一遍
     * 求字符串＋伪样式按需缓存。伪样式基址两路同源（整表查表 / 懒级联，由 [styleOf] 注入），
     * 输出恒等。字符串恒有效（轻路进结构缓存可持久化）；伪样式按新鲜级联懒解。
     *
     * @return (字符串表, 查找器)：轻路取全对，重启只取查找器。
     */
    private fun genPhase1(
        markup: MarkupElement,
        sheets: List<orilumn.reader.engine.css.StyleSheet>,
        hidden: orilumn.reader.engine.laying.HiddenCheck,
        engine: StyleComputer,
        styleOf: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
    ): Pair<Map<MarkupElement, Pair<String?, String?>>, orilumn.reader.engine.laying.GenOf> {
        if (!orilumn.reader.engine.laying.GeneratedContent.needsPhase(sheets)) {
            return emptyMap<MarkupElement, Pair<String?, String?>>() to orilumn.reader.engine.laying.EmptyGen
        }
        val pseudoOf = pseudoOfFor(engine, styleOf)
        val strings = orilumn.reader.engine.laying.GeneratedContent.resolveStrings(markup, styleOf, pseudoOf, hidden::isHidden)
        return strings to orilumn.reader.engine.laying.GeneratedContent.genOf(strings, pseudoOf)
    }

    /**
     * P3-c phase-1 后半：调用方已持有字符串表（结构缓存/持久化）时跳过 `resolveStrings` 整树求值，
     * 只做门控复核 + 伪样式懒装配。字符串与 `resolveStrings` 同源（同 sheet 下恒等），伪样式恒按
     * 新鲜级联解——复用字符串不复用样式，无 stale。
     */
    private fun genOfFromStrings(
        sheets: List<orilumn.reader.engine.css.StyleSheet>,
        strings: Map<MarkupElement, Pair<String?, String?>>,
        engine: StyleComputer,
        styleOf: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
    ): orilumn.reader.engine.laying.GenOf {
        if (!orilumn.reader.engine.laying.GeneratedContent.needsPhase(sheets)) {
            return orilumn.reader.engine.laying.EmptyGen
        }
        return orilumn.reader.engine.laying.GeneratedContent.genOf(strings, pseudoOfFor(engine, styleOf))
    }

    private fun pseudoOfFor(
        engine: StyleComputer,
        styleOf: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
    ): (MarkupElement, String) -> orilumn.reader.engine.css.ComputedStyle? {
        val pseudoCache = HashMap<Pair<MarkupElement, String>, orilumn.reader.engine.css.ComputedStyle?>()
        return { el, p ->
            pseudoCache.getOrPut(el to p) {
                val base = styleOf(el) ?: return@getOrPut null
                engine.pseudoStyle(el, orilumn.reader.engine.laying.ancestorsOf(el), base, p)
            }
        }
    }

    /**
     * `::first-letter` 伪元素计算样式查找（重/轻两路同式，与 [pseudoOfFor] 同一懒解＋缓存纪律）。
     *
     * 章节级先行门控（[StyleComputer.hasFirstLetterRules]）：整章没有 `::first-letter` 规则时返回
     * null ⇒ 塑形侧一个字都不多算（老书零开销）。命中章节也只在**每个叶根**解一次并缓存（伪元素
     * 样式一律按新鲜级联算，不进结构缓存，无跨字号/主题过期）。
     */
    private fun firstLetterOfFor(
        engine: StyleComputer,
        styleOf: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
    ): ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? {
        if (!engine.hasFirstLetterRules()) return null
        val cache = HashMap<MarkupElement, orilumn.reader.engine.css.ComputedStyle?>()
        return { el ->
            cache.getOrPut(el) {
                val base = styleOf(el) ?: return@getOrPut null
                engine.firstLetterStyle(el, orilumn.reader.engine.laying.ancestorsOf(el), base)
            }
        }
    }

    /**
     * P3-c: 章节生成内容查找装配（重/轻/桌面同式，体现在 [genPhase1] 单源）。
     */
    private fun genOfFor(
        markup: MarkupElement,
        sheets: List<orilumn.reader.engine.css.StyleSheet>,
        styleOf: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
        engine: StyleComputer,
        hidden: orilumn.reader.engine.laying.HiddenCheck,
    ): orilumn.reader.engine.laying.GenOf =
        genPhase1(markup, sheets, hidden, engine, styleOf).second

    /**
     * Cheap ("light") full-chapter pass: cascade + box **tree** (leaf ordering / styles / width), but
     *  **no per-block shaping** — leaf ranges/lineHeights are left empty and [ChapterPrepareResult.structure].lines
     * is empty. Feeds the large-chapter foreground: block-cut temp pages ([PaginationMode.BLOCK_TEMP])
     * and disk-hit incremental shaping ([PaginationMode.LINE_DISK]), which shape only the page's blocks.
     *
     * [ChapterPrepareResult.blockIndexForChar] / [ChapterPrepareResult.leaves] / globalCharStarts are
     * fully valid here; only per-block line geometry is absent (recomputed on demand by [tempShape]).
     */
    fun prepareLight(
        markup: MarkupElement,
        cssBundle: CssBundle?,
        profile: TypographicProfile,
        contentW: Int,
        structure: orilumn.reader.engine.ChapterStructureCache,
        /** P2: 版心高（与 contentW 共同组成 `@media` 求值视口；与重路径同值）。 */
        contentH: Int,
        /** Fires only when the cascade actually ran (first touch per CSS): the caller persists the
         *  media-free result for import/open reuse. Null = compute without persisting. */
        onStructureComputed: ((cssHash: Long) -> Unit)? = null,
    ): LightPrepare = structure.lock.withLock {
        // prepareLight runs concurrently for the same chapter's cache: the open thread's DISK/ANCHOR
        // pass races prewarmForOpen (backgroundDispatcher) and the canonical pass. `key` and
        // `parsedAuthorSheets` are written as two separate stores, so a peer checking only `key` can
        // observe it already matching while `parsedAuthorSheets` is still null and crash on the
        // force-unwrap below. Guard on the payload too, and hold the per-chapter lock across the whole
        // check-build-read so no caller ever sees a half-assembled cache (double parse is benign).
        val cssTexts = cssBundle?.cssTexts ?: emptyList()
        val cssHash = ChapterStructureCodec.cssHashOf(cssTexts)
        val structureKey = structureKeyOf(cssBundle, profile.useOriginalStyle, contentW, contentH)
        val keyChanged = structure.key != structureKey
        val needSheets = keyChanged || structure.parsedAuthorSheets == null
        if (needSheets) {
            structure.key = structureKey
            // P2: 全视口 @media 求值（宽＋高；重轻两路同值，规则恒一致）。
            structure.parsedAuthorSheets = parseAuthorSheets(
                cssBundle,
                orilumn.reader.engine.css.CssViewport(contentW.coerceAtLeast(1), contentH.coerceAtLeast(1)),
            )
        }
        // Media-free bound structure (import-built or previously computed) survives viewport and
        // typography changes while the CSS matches: the cascade properties it encodes (display,
        // white-space, background, break-inside, visibility) never come from reader sheets.
        // Sheets above still re-parse when the viewport moves (the shaping cascade needs them);
        // only the whole-tree walk is skipped.
        val boundHit = structure.loadedMediaFree && structure.boundCssHash == cssHash
        if (!boundHit && keyChanged) {
            computeStructure(markup, styleComputerFor(cssBundle, profile, structure.parsedAuthorSheets!!), structure)
            structure.boundCssHash = cssHash
            structure.loadedMediaFree = !ChapterStructurePersist.hasMediaRules(cssTexts)
            onStructureComputed?.invoke(cssHash)
        }
        // styleComputerFor reuses the cached parsed author sheets on a hit, so no CSS re-tokenize.
        val engine = styleComputerFor(cssBundle, profile, structure.parsedAuthorSheets!!)
        // `hidden` is a cheap closure; it is only exercised later, lazily, during per-block materialization
        // and its display decisions are typography-invariant, so the cached leaf set stays valid.
        val hidden = hiddenCheckFor(engine)
        val prepare = LightPrepare(markup, profile, contentW, engine, structure.leaves, structure.globalCharStarts, hidden, imageLoader, chapterHref, structure.leafToBackgroundOwner, structure.leafToBreakInsideAvoidOwner, structure.genStrings, structure.anyFloat, breakerFor(profile), contentH)
        // P3：预算的测量回调（块自身 border-box 高）。预算定在塑形之前，故这里量出的自然尺寸
        // 不会被后续按预算的塑形污染（见 `LightPrepare.clusterBlockOwnHeight`）。
        prepare.clusterBlockOwnHeight = { i -> clusterBlockOwnHeight(prepare, i) }
        prepare
    }

    /** Parses the chapter's author CSS once into [StyleSheet]s (cached in [ChapterStructureCache]).
     *  P2: `@media` 按视口求值（排版点仅知版心宽，高度恒未知→高度查询丢弃，重轻两路同值）。
     *  同文本同视口全书只解一次（`sheetCache`，layouter 与 book 同寿，无 stale）：实测一书 1115 份
     *  文本仅 3 种不同，去重后 tokenize 趋近于零。 */
    private val sheetCache = HashMap<SheetKey, orilumn.reader.engine.css.StyleSheet>()

    /** layouter 全局缓存的并发门（B2 池线程与翻页线程同时进出；无锁 HashMap 并发写会坏表）。 */
    private val sheetLock = SyncLock()

    private data class SheetKey(val textHash: Long, val viewportW: Int, val viewportH: Int)

    private fun parseAuthorSheets(
        cssBundle: CssBundle?,
        viewport: orilumn.reader.engine.css.CssViewport? = null,
    ): List<orilumn.reader.engine.css.StyleSheet> =
        (cssBundle?.cssTexts ?: emptyList()).map { text ->
            val key = SheetKey(cssTextHash(text), viewport?.widthPx ?: -1, viewport?.heightPx ?: -1)
            sheetLock.withLock {
                sheetCache.getOrPut(key) { orilumn.reader.engine.css.LightCssParser().parse(text, viewport) }
            }
        }

    /** 全量文本 hash：与 tokenize 同量级但每文本每视口只付一次；命中即复用，碰撞即错用，故不采样。 */
    private fun cssTextHash(text: String): Long {
        var h = 0L
        for (i in text.indices) h = h * 31 + text[i].code
        return h
    }

    /** 本章字体需求缓存（CSS 文本指纹键）：整形前宿主凭它追装导入字库，无可见跳变。 */
    private val demandCache = HashMap<Long, FontDemand>()

    /**
     * 本章实际用到的字体需求（作者 CSS 文本指纹缓存，毫秒级；KB 文本解析一次后常驻）。
     * 宿主在任何整形发生**之前**调用，命中的导入面先装池——开屏后追装必见字体跳变，不允许。
     */
    internal fun fontDemandFor(cssBundle: CssBundle?): FontDemand {
        val texts = cssBundle?.cssTexts ?: return FontDemand.EMPTY
        var h = 0L
        texts.forEach { h = h * 31 + it.hashCode() }
        return demandCache.getOrPut(h) { collectFontDemand(texts) }
    }

    /** Fingerprint of the structural inputs that determine the block order/leaf set
     *  (P2: 作者 CSS 文本＋`@media` 求值视口宽高——旋转/尺寸变化即重解）。 */
    private fun structureKeyOf(cssBundle: CssBundle?, useOriginalStyle: Boolean, contentW: Int, contentH: Int): Long {
        var h = 0L
        cssBundle?.cssTexts?.forEach { h = h * 31 + it.hashCode() }
        h = h * 31 + contentW
        h = h * 31 + contentH
        return (h shl 1) or (if (useOriginalStyle) 1L else 0L)
    }

    /** Lazily-built `display:none` check bound to [engine], matching the heavy path's hidden-ness. */
    private fun hiddenCheckFor(engine: StyleComputer): orilumn.reader.engine.laying.HiddenCheck {
        val cache = identityMap<MarkupElement, Boolean>()
        return orilumn.reader.engine.laying.HiddenCheck { el -> engine.resolveHidden(el, cache) }
    }

    /** Runs the expensive per-element display cascade + leaf enumeration + char-start accumulation,
     *  storing the result into [structure]. Returns the value for chaining. */
    private fun computeStructure(
        markup: MarkupElement,
        engine: StyleComputer,
        structure: orilumn.reader.engine.ChapterStructureCache,
    ): orilumn.reader.engine.ChapterStructureCache {
        // Block-ness on the lazy path = default tag blocks, plus CSS display:block **only when the
        // chapter/reader declares `display`**, else the default (tag-only) predicate keeps the hot
        // path zero-cost. resolveDisplayOnly mirrors the heavy path's styleMap[el].displayBlock, so
        // the two paths' leaf sets and globalCharStarts stay identical.
        val classify = if (engine.hasDisplayDeclaration()) {
            val displayCache = identityMap<MarkupElement, Boolean>()
            orilumn.reader.engine.laying.BlockClassify { el -> orilumn.reader.engine.laying.NormalFlowLayout.defaultBlock(el) || engine.resolveDisplayOnly(el, displayCache) }
        } else {
            orilumn.reader.engine.laying.NormalFlowLayout.DEFAULT_CLASSIFY
        }
        val hidden = hiddenCheckFor(engine)
        val leaves = ArrayList<MarkupElement>()
        // P1-2: caption 叶序喂懒级联（与重路径同式）。
        val styleCache = HashMap<MarkupElement, orilumn.reader.engine.css.ComputedStyle>()
        orilumn.reader.engine.laying.NormalFlowLayout.enumerateBlockLeaves(
            markup, leaves, classify, hidden,
            captionFirst = { t -> !orilumn.reader.engine.laying.NormalFlowLayout.captionIsBottom(t) { e -> engine.resolve(e, styleCache) } },
        )
        // P1-2: 字符起点按样式化归一长度累计（与重路径盒 textLength 同式）。
        // P3-c: 生成内容 phase-1（解析表门控命中才整树求值；字符串进结构缓存恒有效，
        // 伪样式各 prepare 按新鲜级联懒解）。与重路径同式，见 [genPhase1] 单源。
        val (liteStrings, liteGenOf) = genPhase1(
            markup, structure.parsedAuthorSheets ?: emptyList(), hidden, engine,
        ) { e -> engine.resolve(e, styleCache) }
        structure.genStrings = liteStrings
        val starts = orilumn.reader.engine.laying.NormalFlowLayout.accumulateCharStarts(
            leaves.map { orilumn.reader.engine.laying.NormalFlowLayout.styledCharAdvance(it, { e -> engine.resolve(e, styleCache) }, classify, hidden, liteGenOf) },
        )
        // Compute the typography-invariant leaf->background-owner map once (reuses this same cascade, so
        // window rendering never re-walks ancestor styles). Background-only attribution (color/image):
        // border-only leaves keep showing their ancestor container's fill; their own borders are
        // emitted as per-leaf border boxes by buildBackgroundDrawBoxes (mirroring the heavy path,
        // where every leaf box draws its own borders over the ancestor background).
        val ownerMap = HashMap<MarkupElement, MarkupElement>(leaves.size)
        for (el in leaves) {
            backgroundOwnerElement(el) { e -> engine.resolve(e, styleCache) }?.let { ownerMap[el] = it }
        }
        // Same once-only cascade for the nearest `break-inside: avoid` ancestor, so the light path's
        // pagination can reproduce the canonical per-page avoid cuts without re-walking ancestors.
        val avoidMap = HashMap<MarkupElement, MarkupElement>(leaves.size)
        for (el in leaves) {
            avoidOwnerElement(el) { e -> engine.resolve(e, styleCache) }?.let { avoidMap[el] = it }
        }
        structure.leaves = leaves
        structure.globalCharStarts = starts
        structure.leafToBackgroundOwner = ownerMap
        structure.leafToBreakInsideAvoidOwner = avoidMap
        // R26：本章有无 float。这里的级联缓存已经被上面几趟走热，逐叶读一个字段近乎免费。
        // 口径必须与 `LightPrepare.blockStyleFor` 一致（`#text` 叶取父级样式），否则
        // "文本叶在 float 容器内"这种章会被误判成无 float。`float` 与字号/行高无关，
        // 且结构持久化两侧都被 `!hasMediaRules` 门住，故与 leaves/globalCharStarts 同等可复用。
        var anyFloat = false
        for (el in leaves) {
            val owner = if (el.tag == "#text") (el.parent ?: el) else el
            if (engine.resolve(owner, styleCache).floatSide != orilumn.reader.engine.css.FloatSide.NONE) {
                anyFloat = true
                break
            }
        }
        structure.anyFloat = anyFloat
        return structure
    }

    /** 内核层：[el] 自身起最近的背景承载盒（背景色/背景图，自身优先，否则块级祖先），无则 null。
     *  只带边框、不带背景的元素（如带 `border-bottom` 的 `h2`）不算属主——其行区仍透出祖先
     *  容器的底（如 `blockquote`），边框由轻量路径的叶边框盒另行绘制（重路径整树绘制天然如此）。 */
    private fun backgroundOwnerElement(
        el: MarkupElement,
        resolve: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle,
    ): MarkupElement? {
        var cur: MarkupElement? = el
        while (cur != null && cur.tag != "body") {
            val s = resolve(cur)
            if (s.hasBackground()) return cur
            cur = cur.parent
        }
        return null
    }

    /** Nearest element whose own resolved style is `break-inside: avoid` (itself first), or null —
     *  mirrors the heavy path's `LayoutBox.breakInsideAvoid` on the leaf/container boxes. */
    private fun avoidOwnerElement(
        el: MarkupElement,
        resolve: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle,
    ): MarkupElement? {
        var cur: MarkupElement? = el
        while (cur != null) {
            if (resolve(cur).breakInside == orilumn.reader.engine.css.BreakRule.AVOID) return cur
            cur = cur.parent
        }
        return null
    }

    /** Shared cascade engine (layers built once; no full-tree computation). [prepare] calls
     *  [StyleComputer.compute] (whole chapter); [prepareLight] holds the same engine and calls
     *  [StyleComputer.resolve] per rendered block. */
    private fun styleComputerFor(
        cssBundle: CssBundle?,
        profile: TypographicProfile,
        authorSheets: List<orilumn.reader.engine.css.StyleSheet>? = null,
    ): StyleComputer {
        val ua = uaSheetFromProfile(profile)
        // Reuse the caller's cached parsed author sheets (light path); otherwise parse now (heavy path).
        val sheets = authorSheets ?: parseAuthorSheets(cssBundle)
        // UI 排版层是全局偏好的最高层且永远生效 (行距/段间距/首行缩进/疏密), 含 原书设置;
        // 读者自定义样式层在它之上.
        val ui = uiSheetFromProfile(profile)
        // 排版主题层 (tier 42): 注入的 themeSheet 优先; 否则按 profile.layoutTheme 加载
        // 资产 CSS (traditional.css / modern.css). 原书设置不起用主题层.
        val theme = when {
            profile.useOriginalStyle -> null
            themeSheet != null -> themeSheet
            else -> themeSheetFromProfile(profile)
        }
        val custom = customStyleSheet
        return StyleComputer(profile.bodyPx, ua, sheets, theme, custom, ui, paragraphScale = profile.paragraphSpacingScale, gapScale = profile.paragraphGapScale, pxScale = profile.density)
    }

    /** Assembles a [ChapterPrepareResult] from a cascade + box tree, computing global char starts. */
    private fun buildPrepareResult(
        markup: MarkupElement,
        styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        structure: BoxLayoutResult,
        leaves: List<LayoutBox>,
        classify: orilumn.reader.engine.laying.BlockClassify,
        hidden: orilumn.reader.engine.laying.HiddenCheck,
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
        firstLetterOf: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? = null,
    ): ChapterPrepareResult {
        // Compute global char start for each leaf by walking in chapter order and accumulating textLength.
        val starts = NormalFlowLayout.accumulateCharStarts(leaves.map { it.textLength.toLong() })
        return ChapterPrepareResult(
            markup = markup,
            styleMap = styleMap,
            structure = structure,
            leaves = leaves,
            globalCharStarts = starts,
            totalBlocks = leaves.size,
            totalChars = leaves.sumOf { it.textLength },
            classify = classify,
            hidden = hidden,
            genOf = genOf,
            firstLetterOf = firstLetterOf,
        )
    }

    /**
     * Shape all blocks in [prepare] (the expensive skia break calls) and paginate into
     * [orilumn.reader.engine.paging.PageSlice]s. Called after [prepare] — either immediately for
     * small chapters, or later when enough blocks have been shaped incrementally for large ones.
     */
    fun fullLayout(
        prepare: ChapterPrepareResult,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
        checkpoint: () -> Unit = {},
    ): ChapterLayouter.ChapterLayoutProduct {
        val carriers = firstCarrierLeaves(prepare.leaves)
        val shapes = prepare.leaves.map { leaf ->
            // P7 cancellation hook: invoked once per block between the expensive skia shapes.
            // Background canonical callers pass a ctx.ensureActive() so a mid-chapter cancel abandons
            // with block granularity instead of finishing the whole chapter. Foreground paths (default)
            // keep a no-op hook, so fullLayout's pre-existing behaviour is byte-identical.
            checkpoint()
            // P4-a3: 环绕前导随叶下发（与盒流断行同宽，否则 canonical 形状与结构行漂移）。
            shapeLeaf(leaf, prepare.styleMap, profile,
                listMarkerFor(leaf.el, prepare.styleMap::get, carriers), genOf = prepare.genOf,
                // 「首字」一旦按 `::first-letter` 单列一段，其字号/face 行为由伪元素样式主导；
            // 单叶根只求一次并缓存（章节无规则即 null 零开销）。
            firstLetterOf = prepare.firstLetterOf,
                floatLead = leaf.floatLead, classify = prepare.classify, hidden = prepare.hidden,
                // P3：重路径的预算已写在盒上（含量画同值）。
                replaceableFitH = leaf.replaceableFitH)
        }
        return completeFullLayout(prepare, shapes, contentH, profile)
    }

    /** Shared completion of [fullLayout]: line rebuild, drawable, pagination,
     *  block-range backfill. (P1: the parallel-chunk variant is deleted — the scheduler pool,
     *  not intra-task fan-out, owns all parallelism now.) */
    private fun completeFullLayout(
        prepare: ChapterPrepareResult,
        shapes: List<ParagraphShapeRef>,
        contentH: Int,
        profile: TypographicProfile,
    ): ChapterLayouter.ChapterLayoutProduct {
        val lines = rebuildLinesFromShapes(prepare.structure, prepare.leaves, shapes)
        // Q1：重排版流投影成本页 DrawLine 窗口（engine-skia 单源：同样的 leafText / xLeft / marker
        // 语义），BoxPageRenderer 经 LineWindowDrawer 像素桥绘制文本；盒背景/边框与替换块
        // （img/table）仍走 Android 附加绘制。
        val skiaLines = orilumn.reader.engine.skia.DrawLineBuilder.build(
            BoxLayoutResult(lines, prepare.structure.boxes),
            prepare.styleMap,
            prepare.classify,
            prepare.hidden,
            profile.letterSpacingEm,
            profile.cjkLatinSpacingEmApplied,
            profile.fgColor,
            prepare.genOf,
            prepare.firstLetterOf,
            deviceScale = profile.density,
        )
        val drawable = run {
            // 表格行展开进窗口（Compose 面随行窗绘制单元格文本 + 边框；legacy drawTableRow 不动）。
            // 行顶/行首 char 取行 FlowedLine（表行单行，见 rebuildLinesFromShapes 同式）。
            val frames = ArrayList<orilumn.reader.engine.skia.TableCellLines.RowFrame>()
            for (leaf in prepare.leaves) {
                val t = leaf.table ?: continue
                val li = leaf.firstLineIndex
                if (li < 0 || li >= lines.size) continue
                val fl = lines[li]
                frames.add(
                    orilumn.reader.engine.skia.TableCellLines.RowFrame(
                        t, li, fl.yTop, leaf.replaceableHeight.coerceAtLeast(1), fl.charStart,
                        leaf.style, orilumn.reader.engine.skia.TableCellLines.tableAncestorOf(leaf.el),
                        // E11：格内容/格边框的裁剪（重路径：盒流后叶的 `clipRect` 已就位）。
                        leaf.contentClip(),
                        // E12：本行的累积相对定位偏移（上下/左右都在 `expandTable` 里落到行几何上）。
                        leaf.relOffsetX, leaf.relOffsetY,
                    ),
                )
            }
            val tableWin = orilumn.reader.engine.skia.TableCellLines.expandTable(
                frames, { prepare.styleMap[it] }, profile.letterSpacingEm, profile.fgColor,
                imageLoader, chapterHref, profile.cjkLatinSpacingEmApplied, profile.density,
            )
            BoxDrawableLayout(
                BoxLayoutResult(lines, prepare.structure.boxes), shapes,
                imageLoader, chapterHref, skiaLines = skiaLines,
                tableCells = tableWin.lines, tableCellImages = tableWin.images, tableBorders = tableWin.borders,
                density = profile.density,
            )
        }
        val rawSlices = Paginator.paginate(drawable, contentH)
        val slices = backfillBlockRanges(rawSlices, prepare.globalCharStarts, prepare.totalBlocks, prepare.totalChars)
        return ChapterLayouter.ChapterLayoutProduct(drawable, slices)
    }

    /** Given a list of [PageSlice]s, computes each slice's block range **from its character range**
     *  via a binary search over [globalCharStarts] (the leaf-ordered cumulative char offsets of the
     *  full chapter). This is authoritative: a page's first block is the leaf that contains its
     *  starting char, its last block (exclusive) is one past the leaf that contains its last char.
     *  It deliberately does NOT rely on per-leaf line indexes ([LayoutBox.firstLineIndex] /
     *  [LayoutBox.lastLineExclusive]) — those proved unreliable for very large chapters and produced
     *  whole-chapter block ranges, which made disk-hit increments degrade into full-chapter shapes.
     *  The resulting [PageSlice.blockStart] / [blockEndExclusive] are persisted in the disk table and
     *  tell [incrementalLayoutForPage] exactly which blocks to shape.
     *
     * @param globalCharStarts leaf i's first global char offset (ascending).
     * @param totalBlocks total leaf count of the chapter.
     * @param totalChars total chapter character count.
     * @param only range of indices to recompute. `null` = all (canonical path, where the input
     *   carries no block ranges at all). The **incremental** path passes the shaped window only:
     *   every other slice was copied straight off the disk table with its own persisted block
     *   range (see [incrementalLayoutForPage] step 1), so recomputing those is pure waste — and it
     *   is not a small waste: 151-page chapter × 2 binary searches × **every single page shape**,
     *   on the foreground open path.
     */
    private fun backfillBlockRanges(
        slices: List<PageSlice>,
        globalCharStarts: LongArray,
        totalBlocks: Int,
        totalChars: Int,
        only: IntRange? = null,
    ): List<PageSlice> {
        // 未 prepare 的章直接透传无块范围 slices——以往静默，上游错块且不可查。
        // 调用方两处恒传已 prepare 的 globals，进来空即调用方 bug。抛。
        require(globalCharStarts.isNotEmpty()) { "backfillBlockRanges without prepare" }
        // Leaf index whose range contains [c]; clamped to 0..last (mirrors blockIndexForChar).
        fun blockOf(char: Int): Int {
            var lo = 0; var hi = globalCharStarts.lastIndex
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val start = globalCharStarts[mid]
                val end = if (mid + 1 <= globalCharStarts.lastIndex) globalCharStarts[mid + 1] else Long.MAX_VALUE
                when {
                    char >= start && char < end -> return mid
                    char < start -> hi = mid - 1
                    else -> lo = mid + 1
                }
            }
            return lo.coerceAtMost(totalBlocks - 1).coerceAtLeast(0)
        }
        fun withRange(slice: PageSlice): PageSlice {
            val blockStart = blockOf(slice.charStart.coerceAtLeast(0))
            val blockEnd = if (slice.charEnd >= totalChars) totalBlocks
            else (blockOf((slice.charEnd - 1).coerceAtLeast(0)) + 1).coerceAtMost(totalBlocks)
            val lo = blockStart.coerceAtLeast(0)
            val hi = blockEnd.coerceAtMost(totalBlocks).coerceAtLeast(lo + 1)
            return slice.copy(blockStart = lo, blockEndExclusive = hi.coerceAtMost(totalBlocks))
        }
        // R21 诊断：把本函数拆成「拷 list」与「算块范围」两笔墙钟，并打出三个入参规模。
        // 本函数本体恒为 0ms（Rust 21 页/172 块实测），留这对计时当护栏：**哪天它自己不再是 0ms，
        // 立刻能看出是 list 拷贝还是块范围计算出了事**，而不必再等外层 `pgBackfill` 报一个
        // 归因不明的数字。
        //
        // 附：此函数曾两次被误判，教训记在这里免得重犯。
        //  (1) 「是 GC 停顿」——错。同期试过在窗口内取 `Runtime.freeMemory()` 做堆采样，而 ART 的
        //      实现是 `CollectGarbage(clear_soft_references=true)` **之后**才返回可用字节数，探针
        //      自己就是一次全量阻塞 GC（单次 ≈660ms），把窗口量成了 1.3s。
        //  (2) 「是线程在算」——也错。改用 `Debug.threadCpuTimeNanos()` 后 cpu≈wall，看着像真在算；
        //      但该调用走 VMDebug 落 /proc，vivo PA2353 实测**进程内首次 ≈1.2s、之后每次 ≈27ms**，
        //      同样是探针自己的钱。`java.lang.management` 在 app classloader 里干脆不存在。
        // 真凶在**实参求值**上（[LightPrepare.totalChars] 每次全章重算），已修；本函数始终清白。
        val dT0 = orilumn.reader.time.platformNowMs()
        val out: List<PageSlice>
        var dOnly = -1
        if (only == null) {
            out = slices.map(::withRange)
        } else if (only.isEmpty()) {
            out = slices
        } else {
            val copy = slices.toMutableList()
            val dT1 = orilumn.reader.time.platformNowMs()
            dOnly = only.count()
            for (i in only) {
                if (i in copy.indices) copy[i] = withRange(copy[i])
            }
            val dT2 = orilumn.reader.time.platformNowMs()
            Logger.w("Orilumn.Engine", "bf copy=${dT1 - dT0}ms calc=${dT2 - dT1}ms " +
                "slices=${slices.size} only=$dOnly blocks=$totalBlocks")
            return copy
        }
        val dT1 = orilumn.reader.time.platformNowMs()
        Logger.w("Orilumn.Engine", "bf copy=0ms calc=${dT1 - dT0}ms " +
            "slices=${slices.size} only=$dOnly blocks=$totalBlocks full=1")
        return out
    }

    // ─────────────────────────────────────────────────────────────────
    // Incremental layout path (disk-cache hit)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Q1-b：把局部已塑形的块（增量/临时路径的 [PartialDrawableLayout]）投影成 skia [orilumn.reader.engine.skia.DrawLine]
     * 窗口——与 canonical [orilumn.reader.engine.skia.DrawLineBuilder] 同一行级语义（text/range 即塑形
     * 输入、yTop/yBottom 即本 drawable 行几何、xLeft = contentLeft + border.left + padding.left、
     * marker 只挂首载体叶首行、替换块不产出）。key = 本 drawable 的**局部**行序，与该窗口
     * drawPageSlice 与 PageSlice 一致 —— ReaderScreen 的 pageLines 合流。Q1-c：不再有旧管线回退。
     */
    private fun buildPartialSkiaWindow(
        prepare: LightPrepare,
        leafList: List<LayoutBox>,
        localFirst: IntArray,
        shapes: List<ParagraphShapeRef>,
        lines: List<FlowedLine>,
        letterSpacingEm: Float,
        cjkLatinSpacingEm: Float,
        inkColor: Int,
        /**
         * E11：裁剪几何源（元素 → 其自身 padding box，若该元素 `overflow` 裁剪；窗口坐标系）——
         * 与本次 [lines] 同一 Y 口径（由 [buildBackgroundDrawBoxes] 的同一聚合给出）。缺省即不裁。
         */
        clipOf: (MarkupElement) -> ClipRect? = { null },
        /** 显示密度（只影响虚线边框的段/隙比例，见 [orilumn.reader.engine.skia.DrawLine.deviceScale]）。 */
        deviceScale: Float = 1f,
    ): Map<Int, orilumn.reader.engine.skia.DrawLine>? {
        val out = HashMap<Int, orilumn.reader.engine.skia.DrawLine>()
        for (i in leafList.indices) {
            val leaf = leafList[i]
            val shape = shapes[i]
            if (shape.isReplaceable || leaf.table != null) continue
            val el = leaf.el ?: continue
            val text = shape.text
            if (text.isEmpty()) continue
            val style = leaf.style
            // E11：本叶内容的 `overflow` 祖先裁剪（含叶自身；无声明即 null）。叶内各行共用。
            val leafClip = contentClipOf(el, clipOf)
            val mono = style.monospace || el.tag == "pre"
            val tag = if (el.isText) el.parent?.tag ?: el.tag else el.tag
            val w = NormalFlowLayout.innerBreakWidth(style, leaf.contentWidth)
            val xLeft = leaf.contentLeft + (style.border.left + style.padding.left).roundToInt() + leaf.relOffsetX
            val marker = listMarkerFor(el, prepare::resolveStyle, prepare.firstCarrierLeaves)
            val base = localFirst.getOrElse(i) { -1 }
            if (base < 0) continue
            // P4-c2: 行文本在章内的字符基址（与 canonical DrawLineBuilder 同式：
            // 首行 FlowedLine.charStart 减首区间起点即形文本[0] 位置；缺省 0 即旧行为，
            // 但旧行为把整窗基址归零，点按查链会错整整一叶——平板增量窗只此一处写基址）。
            val firstFl = lines.getOrNull(base)
            val shapeBase = if (firstFl != null && shape.lineCount > 0) {
                firstFl.charStart - shape.lineStart(0)
            } else 0
            // P4-a3: 环绕前导行按收缩宽整形、x 右移悬浮偏移（与 DrawLineBuilder 重路径同式）。
            val lead = leaf.floatLead
            // E21 残留修复：首字下沉盒（塑形侧已按它断行，见 `shapeGeometry`；此处同式落位）。
            val cap = shape.shapeCapFloat
            for (k in 0 until shape.lineCount) {
                val lineIdx = base + k
                val fl = lines.getOrNull(lineIdx) ?: continue
                val s = shape.lineStart(k)
                val e = shape.lineEnd(k)
                // 断行器不变式（fail-fast）：行起止非法只可能是 breaker 坏了——以往当"画少一行"
                // 跳过，页少字而不报错。现在炸。
                require(s >= 0 && e >= s && e <= text.length) { "buildPartialSkiaWindow bad range s=$s e=$e len=${text.length}" }
                val intruded = lead != null && k < lead.lines
                val capIntruded = cap != null && k in 1..cap.lines
                out[lineIdx] = orilumn.reader.engine.skia.DrawLine(
                    text = text,
                    deviceScale = deviceScale,
                    // lineEnd 是开区间（塑形 shape 约定）；DrawLine.range 是闭区间（与断行器输出一致），
                    // 此处 s..e 会多含下一行首字、末行直接 substring 越界崩回书架，必须 s until e。
                    range = s until e,
                    // E12：`position: relative` 的绘制偏移（叶的累积偏移；纯增量，与坐标系无关）。
                    yTop = fl.yTop + leaf.relOffsetY,
                    yBottom = fl.yBottom + leaf.relOffsetY,
                    alignment = style.textAlign,
                    fontSizePx = style.fontSizePx,
                    lineHeightRatio = style.lineHeightRatio,
                    tag = tag,
                    families = style.fontFamilies,
                    weight = style.fontWeight,
                    italic = style.italic,
                    monospace = mono,
                    letterSpacingEm = letterSpacingEm,
                    cjkLatinSpacingEm = cjkLatinSpacingEm,
                    lineWidthPx = when {
                        intruded -> lead!!.widthPx
                        capIntruded -> (w - cap!!.widthPx).coerceAtLeast(1)
                        else -> w
                    },
                    xLeft = xLeft + when {
                        intruded -> lead!!.xOffPx.roundToInt()
                        capIntruded -> cap!!.widthPx
                        else -> 0
                    },
                    capFloat = cap,
                    listMarker = if (k == 0) marker else null,
                    inkColor = inkColor,
                    colorRuns = shape.colorRuns,
                    fontRuns = shape.fontRuns,
                    // **行内盒的「起始于叶首字符」左边内边距折在首行缩进里**（与 canonical
                    // DrawLineBuilder 同式：断行侧 breakWrappedLines 已按同一份 inlineBoxes 减宽，
                    // 绘制侧首行右移同值 ⇒ 量画同源。漏加 ⇒ 行内盒左 padding 被文字压住）。
                    firstLineIndentPx = if (k == 0) {
                        leaf.style.textIndentPx +
                            orilumn.reader.engine.laying.inlineBoxLeadInsetPx(shape.inlineBoxRuns)
                    } else 0f,
                    // 行内盒（背景/边框/内边距）随形透传（与 canonical 同源 shape.inlineBoxRuns，
                    // 即喂断行器的同一份）。**漏传取默认空表 ⇒ 行内背景/边框/推进全不画**——
                    // 症状：磁盘表命中/临时表渲染的页看不到 mark 背景、行内 padding+border
                    // （2026-10-19 用户报「首开看不到、再开才看到」的真凶之一，探针实证
                    // first-open 15/72 行带 run、增量再开 0/72）。
                    inlineBoxRuns = shape.inlineBoxRuns,
                    // P1-2: 不换行段绘制侧以无限宽整形（与 canonical DrawLineBuilder 一致）。
                    nowrap = !orilumn.reader.engine.css.WhiteSpaceNormalize.wraps(style.whiteSpace),
                    // P1-2: 行内基线位移随段整形（与 canonical 一致）。
                    baselineShifts = shape.baselineShifts,
                    // P3-a: 行阴影（currentColor 按叶墨色解）＋着重号＋祖先 opacity（与 canonical 同式）。
                    textShadow = style.textShadow?.let { sh ->
                        val argb = sh.colorHex?.let(::cssHexToArgb)
                            ?: style.colorHex?.let(::cssHexToArgb) ?: inkColor
                        orilumn.reader.engine.css.TextShadow(sh.dx, sh.dy, sh.blur, "#%08x".format(argb))
                    },
                    emphasis = style.emphasisStyle,
                    emphasisUnder = style.emphasisUnder,
                    alpha = orilumn.reader.engine.laying.effectiveOpacity(el) { prepare.resolveStyle(it) },
                    // P6-b: 叠排注音随形透传（与 canonical DrawLineBuilder 同源 shape.rubyRuns）。
                    rubyRuns = shape.rubyRuns,
                    // 下划线随形透传（与 canonical 同源 shape.decorationRuns）。
                    decorationRuns = shape.decorationRuns,
                    // E10：`visibility: hidden` 覆盖区间随形透传（与 canonical 同源）。
                    visibilityHiddenRuns = shape.visibilityHiddenRuns,
                    // **Q20**：断词收尾随行读 **shape**（增量/临时页手里没有 `LayoutBox`；值与盒流
                    // `leaf.hyphenAtEnd` 同断行器同宽逐项一致）。漏传取默认 `false` ⇒
                    // `LineAligner.hyphenW == 0` ⇒ `LineWindowDrawer` 整块跳过 ⇒ 连字符画不出来。
                    hyphenAtEnd = shape.shapeLineHyphenAtEnd(k),
                    // 挤压比例随行读 **shape**（同 Q20 理由：增量/临时页手里没有 `LayoutBox`）。
                    // 漏传取默认 0 ⇒ 画比量宽 ⇒ 右溢被裁。
                    squeezeRatio = shape.shapeLineSqueezeRatio(k),
                    // P4-c2: 章内基址（与 canonical 同式；点按命中经它换算章内 char）。
                    charBase = shapeBase,
                    // E11：`overflow` 祖先裁剪（与 canonical `DrawLineBuilder` 同源：同为叶级闭包）。
                    clip = leafClip,
                )
            }
        }
        return out.ifEmpty { null }
    }

    /**
     * Incremental layout driven by a persisted pagination table. Shapes **only the blocks that
     * contribute text to the target page** (per-page lookup via [table]). This skips the expensive
     * full-chapter shaping and is the path taken when [PaginationCacheStore] reports a
     * disk hit for the current parameter hash.
     *
     * @param prepare full-chapter prepare result (always needed for box geometry / global char
     *   offsets / leaf ordering).
     * @param table cached pagination table for this chapter + param hash.
     * @param targetPage 0-based page index to shape and render.
     * @param prefillL2 R3: immutable published neighbor shapes (controller window prefill), consulted
     *   between [cache] and fresh shaping. Read-only — never written through (callers may run off
     *   the flip lock, e.g. prewarm).
     * @return [ChapterLayouter.ChapterLayoutProduct] containing:
     *   - A partial [DrawableBookLayout] that can draw just [targetPage].
     *   - A **complete** [List<PageSlice>] built straight from [table] (all pages, block ranges +
     *     char ranges, but line indexes are unknown = -1 until those pages are shaped).
     */
    fun incrementalLayoutForPage(
        prepare: LightPrepare,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
        table: ChapterPaginationTable,
        targetPage: Int,
        pagesToShape: Int = 4,
        cache: MutableMap<Int, ParagraphShapeRef>? = null,
        prefillL2: Map<Int, ParagraphShapeRef>? = null,
        /** R20：锚页前先塑章首这么多块做暖机（0 = 关）。开书路径显式传，别的调用点不动。 */
        warmupBlocks: Int = 0,
    ): ChapterLayouter.ChapterLayoutProduct {
        val totalPages = table.pages.size
        val startIdx = targetPage.coerceAtLeast(0)
        val endIdx = (startIdx + pagesToShape).coerceAtMost(totalPages)

        // 1. Build the full page slice list from the disk table (all pages, line indexes unknown).
        val allSlices = table.pages.map { rec ->
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

        // 2. Compute the contiguous block range covering all pages we want to shape.
        var blockLo = Int.MAX_VALUE
        var blockHi = Int.MIN_VALUE
        for (i in startIdx until endIdx) {
            val rec = table.pages[i]
            if (rec.blockStart >= 0) blockLo = minOf(blockLo, rec.blockStart)
            if (rec.blockEndExclusive > 0) blockHi = maxOf(blockHi, rec.blockEndExclusive)
        }
        // 哨兵即脏表/空窗（所选页全无有效块范围）：以往静默归零产出 NOSHAPE 整窗，
        // 调用方当完整 slices 用，翻页后续才崩。现在出生即炸。
        if (blockLo == Int.MAX_VALUE || blockHi == Int.MIN_VALUE) {
            error("incrementalLayoutForPage empty block range pages=[$startIdx,$endIdx)/${table.pages.size}")
        }
        blockLo = blockLo.coerceAtLeast(0)
        blockHi = blockHi.coerceAtMost(prepare.totalBlocks)

        // 3. Shape all blocks in that range (reusing already-shaped blocks from [cache]).
        // R3: l2hits counts published-neighbor hits (diagnostic for prefill effectiveness).
        var l2hits = 0

        // R20 预热：开书锚页是冷进程里的**第一次** shape，先在章首塑 [0, warmupBlocks) 一小块，
        // 两笔分别计时，锚页净变快才算赚。warmupBlocks=0 即完全关闭（默认），由调用方显式开。
        //
        // 【未证实，勿当定论】原注释写"付掉 class-load / JIT / 字体段落初始化这些固定项"——已被数据否证：
        // 同一进程内相隔 0.7s 的两次开书，warmup 代码路径与块数完全相同，耗时却是
        // ch4 warm(3 块)=346~419ms 对 ch0 warm(3 块)=30~37ms，差 11 倍。
        // 固定成本模型（warm=F+3p、anchor=F+np）解出 p<0，自身就不成立。
        // 也就是说这 350ms 是 ch4 自身前 3 块（章标题 h1 + 首段）的真实排版开销，
        // 不是"暖机"。它是否换来锚页变快尚未做过 warmB=0 的冷开书 A/B，
        // 在那之前这段预热只能算对锚页有利的一种猜测，见 docs/待分析-GIMP开书慢-结论清单.md。
        var tWarm0 = 0L
        var tWarm1 = 0L
        var warmBlocks = 0
        if (warmupBlocks > 0) {
            val warmHi = warmupBlocks.coerceAtMost(prepare.totalBlocks)
            if (warmHi > 0) {
                tWarm0 = orilumn.reader.time.platformNowMs()
                for (i in 0 until warmHi) tempShape(cache, prepare, i, profile, prefillL2)
                tWarm1 = orilumn.reader.time.platformNowMs()
                warmBlocks = warmHi
            }
        }

        val probe = ShapeProbe()
        val outerProbe = shapeProbe
        // 注意置位点在暖机**之后**：probe 只统计锚页自己那几块（sBlocks 等于 blocks 数），
        // 暖机的 3 块不计入，故 warm 的 CSS/Skia 成分目前无数据。
        // 要拆 warm 需把这两行上移到暖机之前并分成两个 probe，届时 sBlocks 会出现 3+n。
        shapeProbe = probe
        // R26：block(i) 物化在 tempShape 里、在 shapeBlock 之外，原先两笔都盖不到它。
        // 实测它就是那 ~500ms 未归因的大头嫌疑（textLength 的 styledCharAdvance 是子集）。
        val outerSink = prepare.blockTimingSink
        val outerFirst = prepare.firstBlockMs
        prepare.firstBlockMs = -1L
        prepare.blockTimingSink = { b, s0, s1, s2, s3 ->
            probe.blockMs += b
            probe.advanceMs += s3
            probe.styleForMs += s0
            probe.widthMs += s1
            probe.leftMs += s2
            probe.textLenMs += s3
        }
        // R26：同一窗口顺带装渲染层的级联探针，把 stylesMs 拆到选择器匹配/建样式那两笔里。
        // 塑形全程在 layoutMutex 内单线程串行，挂在 common 的全局对象上这段时间内不会有旁路调用；
        // 窗口结束立刻恢复原 sink，嵌套调用（rebuildLocalLines 的回看 lambda）自动记进同一笔。
        val outerCascadeSink = orilumn.reader.engine.css.CascadeProbe.sink
        orilumn.reader.engine.css.CascadeProbe.sink = { m, p, b, s, _ ->
            probe.cascadeMatchMs += m
            probe.inlineParseMs += p
            probe.styleBuildMs += b
            probe.secondPassMs += s
        }
        // R29：cBuild 的细分（边家族 / font-family 三连），与上面那笔独立置位。
        val outerSplitSink = orilumn.reader.engine.css.CascadeProbe.splitSink
        orilumn.reader.engine.css.CascadeProbe.splitSink = { e, f, _ ->
            probe.cEdgeMs += e
            probe.cFontMs += f
        }
        // R29 第三层：cEdge 内部按家族拆（box/width/color/style/radius + 声明了 border 的元素数）。
        val outerFamilySink = orilumn.reader.engine.css.CascadeProbe.familySink
        orilumn.reader.engine.css.CascadeProbe.familySink = { bx, wd, cl, st, rd, ab ->
            probe.eBoxMs += bx
            probe.eWidthMs += wd
            probe.eColorMs += cl
            probe.eStyleMs += st
            probe.eRadiusMs += rd
            probe.eAnyBorder += ab
        }
        val tShape0 = orilumn.reader.time.platformNowMs()
        val localShapes = (blockLo until blockHi).map {
            if (cache?.get(it) == null && prefillL2?.get(it) != null) l2hits++
            tempShape(cache, prepare, it, profile, prefillL2)
        }
        val tShape1 = orilumn.reader.time.platformNowMs()
        shapeProbe = outerProbe
        probe.blockFirstMs = prepare.firstBlockMs
        prepare.blockTimingSink = outerSink
        prepare.firstBlockMs = outerFirst
        orilumn.reader.engine.css.CascadeProbe.sink = outerCascadeSink
        orilumn.reader.engine.css.CascadeProbe.splitSink = outerSplitSink
        orilumn.reader.engine.css.CascadeProbe.familySink = outerFamilySink
        // (l2hits counted above; reported in the asm breakdown below.)

        // 4. Build merged local FlowedLine stream covering all shaped blocks (P6-a2 R6 前视 carry-in).
        val tLines0 = orilumn.reader.time.platformNowMs()
        val localFlow = rebuildLocalLines(prepare, blockLo, blockHi, localShapes) {
            tempShape(cache, prepare, it, profile, prefillL2)
        }
        val localLines = localFlow.lines
        val tLines1 = orilumn.reader.time.platformNowMs()

        // 5. Build the drawable covering all shaped pages. It is a BookLayout, so pagination below can
        // use the SAME whole-line fill rule (Paginator.fillWholeLines) the canonical path uses.
        val leavesForDraw = prepare.blocks(blockLo, blockHi)
        val (localFirst, localLast) = localLineRanges(localShapes)
        // Q1-b：本窗口已塑形块投影成 skia DrawLine（局部行序），TextReader 合流——
        // 增量页不再回落旧 StaticLayout 画法。
        //
        // E11：**先**聚合窗口画盒（含裁剪几何）再投影 skia 窗口 —— 行/图/表格格的 `overflow` 裁剪
        // 与盒自身裁剪必须同源于这一份窗口几何（对调顺序即两处裁剪边界可能不一致）。
        val tBox0 = orilumn.reader.time.platformNowMs()
        val boxes = buildBackgroundDrawBoxes(
            prepare, leavesForDraw, localLines, localFirst, localLast, localFlow.lineLessBoxTop,
        )
        val tBox1 = orilumn.reader.time.platformNowMs()
        val clipOf: (MarkupElement) -> ClipRect? = { boxes.clip[it] }
        val tSkia0 = orilumn.reader.time.platformNowMs()
        val skiaLines = buildPartialSkiaWindow(prepare, leavesForDraw, localFirst, localShapes, localLines, profile.letterSpacingEm, profile.cjkLatinSpacingEmApplied, profile.fgColor, clipOf, deviceScale = profile.density)
        val tSkia1 = orilumn.reader.time.platformNowMs()
        // 表格行展开进窗口（与 canonical/临时页同源 helper；行顶/行首取本窗 FlowedLine，char 章内；
        // 相邻同表行成组，rowspan 格边框跨行）。
        val incrFrames = ArrayList<orilumn.reader.engine.skia.TableCellLines.RowFrame>()
        for (i in leavesForDraw.indices) {
            val leaf = leavesForDraw[i]
            val t = leaf.table ?: continue
            val li = localFirst.getOrElse(i) { -1 }
            if (li < 0 || li >= localLines.size) continue
            val fl = localLines[li]
            val rowH = maxOf(leaf.replaceableHeight, localShapes.getOrNull(i)?.replaceableBottom ?: 0).coerceAtLeast(1)
            incrFrames.add(
                orilumn.reader.engine.skia.TableCellLines.RowFrame(
                    t, li, fl.yTop, rowH, fl.charStart,
                    leaf.style, orilumn.reader.engine.skia.TableCellLines.tableAncestorOf(leaf.el),
                    // E11：格内容/格边框的裁剪取本行叶内容的裁剪（含 `<table>` 及其祖先）。
                    contentClipOf(leaf.el, clipOf),
                    // E12：本行的累积相对定位偏移（叶盒已由正典盒树预处理，纯增量）。`yTop` 在此**不**平移，
                    // 由 `expandTable` 加到 `rowTop` 上（同一份偏移只落一处，避免双加）。
                    leaf.relOffsetX, leaf.relOffsetY,
                ),
            )
        }
        val tTbl0 = orilumn.reader.time.platformNowMs()
        val incrWin = orilumn.reader.engine.skia.TableCellLines.expandTable(
            incrFrames, prepare::resolveStyle, profile.letterSpacingEm, profile.fgColor,
            imageLoader, chapterHref, profile.cjkLatinSpacingEmApplied, profile.density,
        )
        val tTbl1 = orilumn.reader.time.platformNowMs()
        val frameLeadTop = windowLeadTop(prepare, blockLo, localShapes)
        val drawable = PartialDrawableLayout(
            lines = localLines,
            leafList = leavesForDraw,
            shapeList = localShapes,
            localFirst = localFirst,
            localLast = localLast,
            boxes = boxes.boxes,
            loader = imageLoader,
            href = chapterHref,
            avoidOwnerMap = prepare.avoidOwnerMap,
            skiaLinesSource = skiaLines,
            tableCellsSource = incrWin.lines,
            tableCellImagesSource = incrWin.images,
            tableBordersSource = incrWin.borders,
            frameLeadTop = frameLeadTop,
            elementStyleSource = prepare::resolveStyle,
            clipSource = clipOf,
            // 量画同源：本窗 `pageImages` 的 used 宽必须与 shape 高（`shapeGeometry` 已按
            // profile.density 算）同一个 density。漏传取默认 1 ⇒ 宽 ×1、高 ×density，
            // 图被竖向拉伸（宽高比坏），此为「长宽比不对」的直接根因。
            density = prepare.profile.density,
        )

        // 6. Paginate the shaped window's OWN line stream by content height — the SAME whole-line fill
        // rule the canonical Paginator used when it wrote the disk table (Paginator.fillWholeLines).
        // This replaces the old disk-charEnd mapping, which forced each page to whatever lines happened
        // to fall inside the table's stale char range: when the replayed geometry drifted off the
        // canonical run (device font metrics differ from the ones that produced the table) that range
        // stacked UNDER or OVER contentH — the exact "page under-fill / overflow / clipped last line"
        // device bug. Filling locally can never: the page ends on a line that fully fits contentH, and
        // pages tile contiguously (no gap, no overlap).
        //
        // The disk table's charStart still anchors this window's FIRST page (the reader's page number ↔
        // character mapping and bookmarks stay with the persisted table); every page after it flows
        // continuously from its predecessor's last line. With matching geometry this reproduces the
        // canonical page boundaries exactly — the shared fill rule makes drift a no-op instead of a bug.
        val slicesWithLine = allSlices.toMutableList()
        val tPgFind0 = orilumn.reader.time.platformNowMs()
        var tPgPaginate0 = tPgFind0
        var tPgPaginate1 = tPgFind0
        if (localLines.isNotEmpty() && startIdx < endIdx) {
            // Anchor the window's first page at the disk table's EXACT first line (the line whose
            // charStart == the table's authoritative charStart), NOT the merely-containing line —
            // the window is re-paginated from that line, so the anchor must be the real canonical
            // boundary for adjacent windows to tile seamlessly. Falls back to the containing line only
            // when geometry drift moved the boundary (then the char label is snapped to the disk value).
            val anchorChar = table.pages[startIdx].charStart
            var lo = localLines.indexOfFirst { it.charStart == anchorChar }
            if (lo < 0) lo = localLines.indexOfFirst { it.charStart < anchorChar + 1 && it.charEnd > anchorChar }
            if (lo < 0) lo = 0
            tPgPaginate0 = orilumn.reader.time.platformNowMs()
            val rePages = Paginator.paginateFrom(drawable, lo, contentH)
            tPgPaginate1 = orilumn.reader.time.platformNowMs()
            for (i in startIdx until endIdx) {
                val k = i - startIdx
                if (k >= rePages.size) break
                val p = rePages[k]
                // The disk table stays the source of truth for the WINDOW-EDGE char labels: the first
                // page is pinned to the disk anchor (so this window starts exactly where the previous
                // window's last page ended) and the last page's charEnd is pinned to the next disk page
                // (so the NEXT window tiles on seamlessly). Interior boundaries keep the locally
                // re-paginated values — they're what the equivalence guard verifies against canonical.
                val snapStart = if (i == startIdx) anchorChar else p.charStart
                val nextDiskStart = if (i + 1 < totalPages) table.pages[i + 1].charStart else -1
                val snapEnd = if (i + 1 == endIdx && nextDiskStart >= 0) nextDiskStart else p.charEnd
                slicesWithLine[i] = PageSlice(
                    charStart = snapStart.coerceAtLeast(0),
                    charEnd = maxOf(snapEnd, snapStart),
                    firstLine = p.firstLine,
                    lastLineExclusive = p.lastLineExclusive,
                    kind = PageSlice.Kind.TEXT,
                    blockStart = -1, blockEndExclusive = -1,   // recomputed by backfillBlockRanges below
                )
            }
        }
        // Recompute the block ranges from the (possibly re-paginated) char ranges — the same
        // authoritative char→block mapping the canonical path used, so the two paths' block ranges can
        // never drift and disk-hit incremental shaping always knows which blocks each page needs.
        // ONLY the re-paginated window: the other slices came off the disk table already carrying
        // their persisted block range (step 1), so recomputing all N pages on every page shape was
        // pure repeated work on the open path.
        val tPgLoop1 = orilumn.reader.time.platformNowMs()
        val withBlocks = backfillBlockRanges(
            slicesWithLine, prepare.globalCharStarts, prepare.totalBlocks, prepare.totalChars,
            only = startIdx until endIdx,
        )
        val tPg1 = orilumn.reader.time.platformNowMs()
        // R19: assembly segment breakdown (permanent diagnostic) — shape/lines/skia/tbl/box/pg.
        // R20 附加：sStyles/sSkia 把 shape 段拆成 CSS 级联与 Skia 断行，sDepth 记被塑块最大嵌套深度，
        // warm 记锚页前那笔暖机。**不要**往这里加堆/CPU 采样——已实测两者探针自身比被测对象贵
        // （见 [backfillBlockRanges] 里 R21 诊断注释），会把这一行自己变成瓶颈。
        Logger.w("Orilumn.Engine", "asm ch=${table.chapterIndex} page=$targetPage " +
            "shape=${tShape1 - tShape0}ms lines=${tLines1 - tLines0}ms skia=${tSkia1 - tSkia0}ms " +
            "tbl=${tTbl1 - tTbl0}ms box=${tBox1 - tBox0}ms pg=${tPg1 - tPgFind0}ms " +
            "pgFind=${tPgPaginate0 - tPgFind0}ms pgPaginate=${tPgPaginate1 - tPgPaginate0}ms " +
            "pgLoop=${tPgLoop1 - tPgPaginate1}ms " +
            "pgBackfill=${tPg1 - tPgLoop1}ms " +
            "sStyles=${probe.stylesMs}ms sSkia=${probe.skiaMs}ms " +
            "sBlk=${probe.blockMs}ms sBlkF=${probe.blockFirstMs}ms sAdv=${probe.advanceMs}ms " +
            "sBsty=${probe.styleForMs}ms sBWid=${probe.widthMs}ms sBLft=${probe.leftMs}ms sBTxt=${probe.textLenMs}ms " +
            "cMatch=${probe.cascadeMatchMs}ms cParse=${probe.inlineParseMs}ms cBuild=${probe.styleBuildMs}ms c2nd=${probe.secondPassMs}ms " +
            "cEdge=${probe.cEdgeMs}ms cFont=${probe.cFontMs}ms " +
            "eBox=${probe.eBoxMs}ms eWid=${probe.eWidthMs}ms eCol=${probe.eColorMs}ms " +
            "eSty=${probe.eStyleMs}ms eRad=${probe.eRadiusMs}ms eBrd=${probe.eAnyBorder} " +
            "sBlocks=${probe.blocks} sEls=${probe.elements} sDepth=${probe.maxDepth} " +
            "warm=${tWarm1 - tWarm0}ms warmB=$warmBlocks " +
            "linesN=${localLines.size} pagesN=${table.pages.size} " +
            "l2hits=$l2hits/${blockHi - blockLo}")

        // Debug: reconcile every shaped (incremental) page's vertical extent with the content capacity,
        // and flag any page whose boundary drifted from the disk table (the page-boundary-source probe).
        if (EngineDiag.enabled) {
            val sb = StringBuilder("PGL win=[$startIdx,$endIdx) contentH=$contentH")
            for (i in startIdx until endIdx) {
                val sl = withBlocks[i]
                val lo = sl.firstLine; val hi = sl.lastLineExclusive
                if (lo < 0 || hi <= lo) { sb.append(" | p$i NOSHAPE"); continue }
                val top = localLines[lo].yTop
                val bot = localLines[hi - 1].yBottom
                val diff = (bot - top) - contentH
                val rec = table.pages.getOrNull(i)
                val drifts = rec != null && (sl.charStart != rec.charStart || sl.charEnd != rec.charEnd)
                sb.append(" | p$i[${sl.charStart},${sl.charEnd})n=${hi - lo}ext=${bot - top}"
                    .let { if (diff > 0) "$it OVER+$diff" else if (diff < -40) "$it UNDER$diff" else it }
                    .let { if (drifts) "$it#DRIFT(dS=${sl.charStart - (rec?.charStart ?: 0)} dE=${sl.charEnd - (rec?.charEnd ?: 0)} rec=[${rec?.charStart},${rec?.charEnd}) firstLine=${sl.firstLine})" else it })
                // Alarm detail: for any page flagged UNDER/OVER/#DRIFT, dump its per-line heights so the
                // source of page-end blank (avoid-block moved whole vs block replacement moved) and of
                // drift (window vs disk geometry) is decidable offline from the log alone.
                if (diff > 0 || diff < -40 || drifts) {
                    val rows = StringBuilder(" | PGLD p$i rows=")
                    var prevBot = localLines[lo].yTop
                    for (j in lo until hi) {
                        val l = localLines[j]
                        rows.append(l.yBottom - l.yTop)
                        if (j + 1 < hi) rows.append(',')
                        if (l.yTop != prevBot && j > lo) rows.append('^') // tombstone: non-contiguous line feed
                        prevBot = l.yBottom
                    }
                    rows.append(" tailBot=${localLines[hi - 1].yBottom} contentH=$contentH")
                    sb.append(rows)
                }
            }
            Logger.d("Orilumn.Engine", sb.toString())
        }

        return ChapterLayouter.ChapterLayoutProduct(drawable, withBlocks)
    }

    /** Local FlowedLine builder for a contiguous range of leaves. Uses [leaf.contentTop] /
     *  [leaf.contentBottom] from prepare to compute inter-block gaps, so the y geometry is correct
     *  without needing to shape the preceding blocks. */
    /** 文本叶（`el` 为 `#text` 匿名块）的**自身边**为 0：轻路径按约定给文本叶盒
     *  继承容器样式（`LightPrepare.blockStyleFor`），而容器底边已由
     *  [orilumn.reader.engine.laying.NormalFlowLayout.consecutiveLeafAdvance] 的
     *  A 侧祖先链（`pathA[1..ia-1]`，含直接父容器）计入块间 advance——叶自身边
     *  再计一次即重复（真书 `h2{padding-bottom:6rem}` 的章标题→正文间距被翻倍，
     *  TEMP/WIN 路径相对 FULL/emit 路径漂移）。元素叶（p/img/表行）自身边不在
     *  advance 里，照常计入；悬浮叶不在此列（浮部分支 spanEnd 取容器盒自身边距）。
     *  与重路径 `BoxLayouter.layoutBoxes` 给文本叶盒清零盒属性（padding/margin/border
     *  为 0、仅继承字体属性）的约定同效——重路径 emit 叶分支 `s = box.style` 因此
     *  对文本叶加 0 边，容器边由容器分支恰好加一次。 */
    private fun ownBottomEdges(leaf: LayoutBox): Int =
        if (leaf.el?.isText == true) 0 else (leaf.style.padding.bottom + leaf.style.border.bottom).roundToInt()

    /**
     * 叶自身 **top 边** = `border-top + padding-top`（文本叶恒 0，判据同 [ownBottomEdges]）。
     * 块边界开页时只有这一段画在本页页首（段前折叠 `margin-top` 并入页边距，见 [leadAbove] 与
     * `BookLayout.getPageTopLead`）。
     */
    private fun ownTopEdges(leaf: LayoutBox): Int =
        if (leaf.el?.isText == true) 0 else (leaf.style.padding.top + leaf.style.border.top).roundToInt()

    /** 叶自身 `margin-top`（文本叶恒 0）。章首段前距不并入页边距，见 [leadAbove]。 */
    private fun ownMarginTop(leaf: LayoutBox): Int =
        if (leaf.el?.isText == true) 0 else leaf.style.margin.top.roundToInt()

    /**
     * 块边界落差单源：块 [a] 的**末行行底** → 块 [b] 的**首行行顶**。与 [rebuildLocalLines] 的行流推进
     * 逐字节同式（`ownBottomEdges(prev) + consecutiveLeafAdvance + 后块自身 top 边`，见该函数 1395/1430
     * 与 [shapeAnchorPageForward] 的累计式），也因此与 canonical `emit` 的块间距同值。
     *
     * 只服务**窗内块间落差**：窗内每一块的间距本来就在行流里（相邻行的差），但倒包/锚页估计按
     * shapes 的行高逐块重建，块与块之间要靠这个数与 canonical 同值（[interiorGapAbove] 是它减去
     * 前块自身下边后的「页内边界」段）。窗口**上边界**（窗外那段）另走 [leadAbove]/[windowLeadTop]，
     * 两者相差一个段前折叠 `margin-top`（页首并入页边距，页内边界记满）。
     *
     * ⚠ 已知口径差（**本次不动**，非本函数职责）：第三项对**文本叶**不按 [ownTopEdges] 清零
     * （文本叶的 `style` 是宿主容器样式 ⇒ 等于把宿主的 `border/padding-top` 记成后块自身 top 边），
     * 而 [ownBottomEdges] 对 A 侧文本叶清零（A 侧祖先链底边已在 `consecutiveLeafAdvance` 里）。
     * 后块是**宿主首子**文本叶时宿主 top 边确实在它上方（该记），非首子时不该记。既有 fixture 宿主
     * `padding-top` 多为 0，未触到该差；要修需按盒树判「是否首子」，另立项。
     */
    private fun blockBoundaryGap(prepare: LightPrepare, a: Int, b: Int): Int {
        val pa = prepare.block(a)
        val pb = prepare.block(b)
        // ⚠ 只用于**相邻**块（`b == a + 1`）：E23-b 的容器指定高差额按块下标记账
        // （[containerHeightLead][b] = 进入 b 之前闭合的容器差额总和）。
        // E23-e：先取差额表（顺带填「下限真的生效」的容器集），再算间距 —— 折叠判据要读那份集。
        val lead = containerHeightLead(prepare)[b]
        return ownBottomEdges(pa) +
            NormalFlowLayout.consecutiveLeafAdvance(pa.el!!, pb.el!!, prepare::resolveStyle, prepare::floorBinds) +
            (pb.style.border.top + pb.style.padding.top).roundToInt() +
            lead
    }

    /**
     * 块 [b] 作为**页首块**时，页首行行顶之上**归本页**的那段缺口
     * （[orilumn.reader.engine.paging.BookLayout.getPageTopLead] 的轻路径单源）。
     *
     * v4 口径（2026-10-15）：本块**作为页首块**时的缺口 = [NormalFlowLayout.pageTopLead] —— 页首叶沿
     * 「首有效子」链上溯到**最外层可见盒 X**（背景色/背景图/边框/盒阴影），X 边框顶以下一切（X 的
     * padding、中间容器的边距、**首子自己的** `margin-top`/`padding-top`）画在本页页首；X 之上的折叠
     * `margin-top` 并入页边距。链上无可见盒（纯文本块直接挂在 `<body>` 下）⇒ 退回 v3：只留本块
     * **自身**的 `border-top`/`padding-top` ⇒ `ownTopEdges(b)`。
     * **章首块（b == 0）例外**：章首段前距不与页边距合并，另加本块自身 `margin-top`。
     * 段中开页（调用方的 `k0 > 0`）本页首行之上无缺口，恒 0 —— 调用方按 `k0 > 0` 短路。
     */
    private fun leadAbove(prepare: LightPrepare, b: Int): Int {
        val leaf = prepare.block(b)
        // 章首：v3 不变（章首段前距不与页边距合并，照画在章首页页首）。
        if (b <= 0) return ownTopEdges(leaf) + ownMarginTop(leaf)
        val el = leaf.el ?: return ownTopEdges(leaf)
        return NormalFlowLayout.pageTopLead(el, prepare::resolveStyle)
    }

    /**
     * **页内**块边界单源：块 [b] 首行行顶之上到**前块末行帧底**的那段 = [blockBoundaryGap]`(b-1,b)`
     * 减去**前块自身下边**（前块下边归本页前一行的帧底，见 [ownBottomEdges]，别处已单记一次）。
     *
     * 与 [leadAbove]（页首）的差别正是那段段前距：页首块的折叠 `margin-top` 并入页边距，页内
     * 边界的落差两端都在本页里、没有下一页来分摊 ⇒ 必须记满「段前距 + 本块自身 top 边」。
     * 倒包估计（[shapeTempPageBackward]）逐行向上累加时按本函数记 `acc`，仅在该行**当选页首行**
     * 的判据里改用 [leadAbove]。
     */
    private fun interiorGapAbove(prepare: LightPrepare, b: Int): Int =
        if (b <= 0) 0
        else blockBoundaryGap(prepare, b - 1, b) - ownBottomEdges(prepare.block(b - 1))

    /**
     * E23-b：**容器指定高差额**前缀表（[LightPrepare.containerHeightLead] 的唯一写入点）。
     *
     * 重路径 `emit` 的容器分支把容器内容盒高定为 `max(在流内容高, height)`，容器底边随之落到
     * 指定高，后随内容的 `st.y` 因此多出 `height − 内容高` 这一段（`.ovh{height:40px}` 里只有
     * 一行 27px 时多 13px）。轻路径的行流是**局部位移**累加（[consecutiveLeafAdvance] 只含边距
     * 与边，没有内容高可用），所以这一段必须另外补，否则临时/增量页相对磁盘页少一段 ⇒ 分页在
     * 两种页之间横跳。
     *
     * 累计口径与行流逐值同源：容器内容高 = 首子链下沉 + 首叶 top 边 + Σ(叶内容盒高 + 叶自身下边
     * + 块间 advance + 后叶自身 top 边)。**内层容器先算**（叶区间短的先），其差额写进前缀表后
     * 自然被外层的内容高吃掉（内层盒子本来就是外层的内容）。内容高一旦达到指定高即**早退**
     * （差额 0），故开销以「填满指定高所需的那几叶」为界，不会为一个固定高盒子扫全章。
     *
     * 兜底：首叶取不到 `el`、或不在容器首有效子链上（理论不可达，浮动作首子并不破链）⇒ **不补**，
     * 与改动前同值（方向宁少不多，不会多补出重叠）。浮动叶不推进行流，只贡献自身边距（已含在
     * advance 里），所以容器内容高不会被浮动的盒高虚增。
     */
    private fun containerHeightLead(prepare: LightPrepare): IntArray {
        prepare.containerHeightLead?.let { return it }
        val n = prepare.totalBlocks
        val before = IntArray(n + 1)
        if (n <= 0 || !prepare.chapterHasBoxHeight) {
            prepare.containerHeightLead = before
            return before
        }
        // 指定高**容器** → 叶区间 [lo, hi)。只认祖先（叶自身的高由 `extentOf` 与行流内的
        // `specifiedContentHeight` 单独消费，不能重复计入）。
        val loOf = identityMap<MarkupElement, Int>()
        val hiOf = identityMap<MarkupElement, Int>()
        val els = ArrayList<MarkupElement>()
        for (i in 0 until n) {
            var a: MarkupElement? = prepare.leafElement(i).parent
            while (a != null) {
                if (!a.isText && NormalFlowLayout.specifiedVerticalFloor(prepare.resolveStyle(a)) != null) {
                    if (loOf[a] == null) {
                        loOf[a] = i
                        els.add(a)
                    }
                    hiOf[a] = i + 1
                }
                if (a === prepare.markup) break
                a = a.parent
            }
        }
        if (els.isEmpty()) {
            prepare.containerHeightLead = before
            return before
        }
        els.sortBy { hiOf.getValue(it) - loOf.getValue(it) }
        for (c in els) {
            val lo = loOf.getValue(c)
            val hi = hiOf.getValue(c)
            val h = NormalFlowLayout.specifiedVerticalFloor(prepare.resolveStyle(c)) ?: continue
            if (h <= 0) continue
            val firstLeaf = prepare.block(lo)
            val firstEl = prepare.leafElement(lo)
            val descent = NormalFlowLayout.firstChildDescentTop(
                c, firstEl, prepare::resolveStyle, zeroLeafBox = firstEl.isText,
            ) ?: continue
            var content = descent + ownTopEdges(firstLeaf)
            var reached = false
            var k = lo
            while (k < hi) {
                val leaf = prepare.block(k)
                val isFloat = leaf.table == null &&
                    leaf.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE
                if (!isFloat) content += leafContentHeight(prepare, k, leaf) + ownBottomEdges(leaf)
                if (k + 1 >= hi) {
                    // 容器内容底 = 末叶盒底 + 末叶到容器之间各层容器的底边 + 该边界已记的内层差额。
                    var a: MarkupElement? = prepare.leafElement(k).parent
                    while (a != null && a !== c) {
                        val s2 = prepare.resolveStyle(a)
                        content += (s2.border.bottom + s2.padding.bottom).roundToInt()
                        a = a.parent
                    }
                    content += before[hi]
                } else {
                    val next = prepare.block(k + 1)
                    val ea = leaf.el
                    val eb = next.el
                    if (ea != null && eb != null) {
                        content += NormalFlowLayout.consecutiveLeafAdvance(ea, eb, prepare::resolveStyle, prepare::floorBinds)
                    }
                    content += ownTopEdges(next) + before[k + 1]
                }
                if (content >= h) { // 内容已达指定高 ⇒ 差额 0（省掉其余叶的塑形）
                    reached = true
                    break
                }
                k++
            }
            if (!reached && content < h) {
                before[hi] += h - content
                // E23-e：下限**真的生效** ⇒ 末子下边距收进盒内（不再外塌），折叠判据要认这一条。
                prepare.floorBoundContainers.add(c)
            }
        }
        prepare.containerHeightLead = before
        return before
    }

    /** E23-b：叶 [i] 的**内容盒高**（不含自身边；含指定高 —— 与行流、`emit` 同一口径）。 */
    private fun leafContentHeight(prepare: LightPrepare, i: Int, leaf: LayoutBox): Int {
        if (leaf.replaceableHeight > 0) return leaf.replaceableHeight
        val sh = shapeBlock(prepare, i, leaf, prepare.profile)
        val natural = if (sh.lineCount > 0) sh.lineBottom(sh.lineCount - 1) - sh.lineTop(0) else 0
        return NormalFlowLayout.specifiedContentHeight(leaf.style, natural)
    }

    /**
     * 窗口**上边界缺口**（[PartialDrawableLayout.frameLeadTop] 的取值单源）：窗口首行行顶之上、
     * 落在窗外的那段页首缺口 = 窗口首行所属块的 [leadAbove]（窗口首行必为该页页首行）。
     *
     * 窗口从**章头**起（[blockLo] == 0）时首行即章首行，章首段前距不并入页边距 ⇒ 另加首块自身
     * `margin-top`（见 [leadAbove]）。空行块（`lineCount == 0`，轻路径理论上不产出）按最近的实块
     * 兜底，不追求该退化情形的精度。
     */
    private fun windowLeadTop(
        prepare: LightPrepare,
        blockLo: Int,
        shapes: List<ParagraphShapeRef>,
    ): Int {
        val firstIdx = shapes.indexOfFirst { it.lineCount > 0 }
        if (firstIdx < 0) return 0
        val leaf = prepare.block(blockLo + firstIdx)
        // 章头起：v3 不变（章首段前距不与页边距合并，另加首块自身 `margin-top`）。
        if (blockLo == 0) return ownTopEdges(leaf) + ownMarginTop(leaf)
        val el = leaf.el ?: return ownTopEdges(leaf)
        return NormalFlowLayout.pageTopLead(el, prepare::resolveStyle)
    }

    private fun rebuildLocalLines(
        prepare: LightPrepare,
        blockLo: Int,
        blockHi: Int,
        shapes: List<ParagraphShapeRef>,
        /** P6-a2 R6: 前视塑形（lookback 块状态预热用；null = 无前视，窗口即全章）。 */
        shapeLookback: ((Int) -> ParagraphShapeRef)? = null,
    ): LocalLineFlow {
        val out = ArrayList<FlowedLine>()
        // 无行叶（零字符、只有边框/背景的块，如 UA `<hr>`：`lineCount == 0`）的 border-box 顶记账。
        // 行流里它没有任何锚行，轻路径的画盒聚合 [buildBackgroundDrawBoxes] 本拿不到位置 —— 这里把
        // 行流当场算出的 `y`（与重路径 `emit` 的 `st.y` 逐值同源）带出去。仅当窗内真有无行叶时才分配
        // （其余窗口零开销、零行为变化）；有行叶的画盒仍由行流首/末行给出，与此无涉。
        var lineLessBoxTop: IntArray? = null
        // 记一笔无行叶的 border-box 顶（[top] = 本叶 border-box 顶 `y`，已含 advance/clear/回填，
        // 与重路径 `emit` 的 `st.y` 同相位）。
        fun noteLineLessBoxTop(gi: Int, shape: ParagraphShapeRef, top: Int) {
            if (gi < blockLo || shape.lineCount > 0 || shape.isReplaceable) return
            val arr = lineLessBoxTop
                ?: IntArray(blockHi - blockLo) { LocalLineFlow.NO_LINE_LESS_TOP }.also { lineLessBoxTop = it }
            arr[gi - blockLo] = top
        }
        // P6-a2 R6: 窗口前视 carry-in（≤8 块）：状态预热，不发射；切入窗顶时整体归零。
        val lo2 = if (blockLo > 0 && shapeLookback != null) maxOf(0, blockLo - 8) else blockLo
        val extLeaves = (lo2 until blockHi).map { prepare.block(it) }
        val extShapes = (lo2 until blockLo).map { shapeLookback!!(it) } + shapes
        // 表格行高跨行分摊（与 canonical 同源；窗口内同表行叶分组，缺行退化为旧口径）。
        applyTableRowspanHeights(extLeaves, extShapes)
        var runningChar = prepare.globalCharStarts[lo2].toInt()
        // Vertical geometry is computed locally (light-safe): each block's top comes from the previous
        // block's bottom plus the **tree-aware collapse** [NormalFlowLayout.consecutiveLeafAdvance],
        // which reproduces emit() including container margins. This matches the canonical flow exactly
        // yet needs no full-chapter absolute y (contentTop/...), so it works from a [prepareLight]
        // result without ever shaping the whole chapter.
        var prevBlock: LayoutBox? = null
        var prevContentBottom = 0
        var prevLastLineYBottom: Int? = null
        // P6-a2: 双侧生效悬浮跨度（与重路径 FlowState 同式；窗口内局部＋前视 carry-in）。
        var floatBottomL: Int? = null
        var floatBottomR: Int? = null
        var floatTopL: Int? = null
        var floatTopR: Int? = null
        var lastTextBottom = 0
        // E23-b：容器指定高差额（按块下标记的前缀表；无指定高容器时恒 0）。
        val boxLead = containerHeightLead(prepare)
        for (idx in extLeaves.indices) {
            val gi = lo2 + idx
            val leaf = extLeaves[idx]
            val shape = extShapes[idx]
            val emit = gi >= blockLo
            // P6-a2: 基址先行（advance 后加）：clear/回填在基址上判定，与重路径 st.y 同相位。
            var y = if (prevBlock == null) 0 else prevContentBottom
            if (emit && gi == blockLo && lo2 < blockLo) {
                // 切入窗顶：帧归零并忘掉窗前 previous（页首 margin 并入页顶，v1 同式）；
                // 跨度/回填点同移 carry-in。
                val h = y
                y = 0
                prevContentBottom = 0
                prevBlock = null
                prevLastLineYBottom = null
                if (floatBottomL != null) floatBottomL = floatBottomL!! - h
                if (floatBottomR != null) floatBottomR = floatBottomR!! - h
                if (floatTopL != null) floatTopL = floatTopL!! - h
                if (floatTopR != null) floatTopR = floatTopR!! - h
                lastTextBottom -= h
            }
            // E23-b：容器指定高差额。重路径的容器底边在**此刻**落到指定高（`st.y` 已含这一段），
            // 轻路径的 advance 只带边距与边 ⇒ 这里按同一相位补回：先入基址，clear/回填/advance
            // 全落在它上面（与重路径 `st.y` 已含差额、随后才做 margin/clear 同序）。
            if (prevBlock != null) y += boxLead[gi]
            val advance = if (prevBlock == null) 0
            else NormalFlowLayout.consecutiveLeafAdvance(prevBlock!!.el!!, leaf.el!!, prepare::resolveStyle, prepare::floorBinds)
            val ls = leaf.style
            // P6-a2: 悬浮注册（img/文本；与重路径 emit 同式：零推进＋双侧跨度＋同侧堆叠）。
            // 表格行永不视为悬浮（与重路径 `box.table == null` 守卫同式）。
            // E23-b：空悬浮盒（无文本、非替换）只要指定高为正也是真浮动盒（重路径同判据）。
            val hasSize = (shape.isReplaceable && shape.replaceableBottom > 0) || shape.lineCount > 0 ||
                (!shape.isReplaceable && NormalFlowLayout.specifiedContentHeight(ls, 0) > 0)
            if (leaf.table == null && ls.floatSide != orilumn.reader.engine.css.FloatSide.NONE && hasSize) {
                // 悬浮盒自身 advance 照常（与重路径 margin 消费同式），再堆叠/注册。
                // E23-b：同行并排的第二块起不加自身顶边距（与重路径同阶；行顶由本行首块定）。
                if (leaf.floatNewRow) y += advance
                // 显式 clear 只越指定侧。
                if (ls.clearSide == orilumn.reader.engine.css.ClearSide.LEFT || ls.clearSide == orilumn.reader.engine.css.ClearSide.BOTH) {
                    val b = floatBottomL
                    if (b != null && b > y) y = b
                }
                if (ls.clearSide == orilumn.reader.engine.css.ClearSide.RIGHT || ls.clearSide == orilumn.reader.engine.css.ClearSide.BOTH) {
                    val b = floatBottomR
                    if (b != null && b > y) y = b
                }
                // 相邻同侧：**另起一行**才越过本侧旧悬浮；同行并排同顶（CSS 2.1 §9.5.1）。
                if (leaf.floatNewRow) {
                    val own = if (ls.floatSide == orilumn.reader.engine.css.FloatSide.LEFT) floatBottomL else floatBottomR
                    if (own != null && own > y) y = own
                }
                noteLineLessBoxTop(gi, shape, y)
                val contentY = y + (ls.border.top + ls.padding.top).roundToInt()
                if (shape.isReplaceable && shape.replaceableBottom > 0) {
                    if (emit) {
                        out.add(
                            FlowedLine(
                                charStart = runningChar,
                                charEnd = runningChar + leaf.textLength,
                                yTop = contentY,
                                yBottom = contentY,
                                paragraphStart = true,
                            ),
                        )
                    }
                    val spanEnd = contentY + shape.replaceableBottom
                    if (ls.floatSide == orilumn.reader.engine.css.FloatSide.LEFT) {
                        if (floatBottomL == null) floatTopL = y
                        floatBottomL = maxOf(floatBottomL ?: spanEnd, spanEnd)
                    } else {
                        if (floatBottomR == null) floatTopR = y
                        floatBottomR = maxOf(floatBottomR ?: spanEnd, spanEnd)
                    }
                    prevLastLineYBottom = spanEnd
                } else {
                    var cumY = contentY
                    val shapeStart = if (shape.lineCount > 0) shape.lineStart(0) else 0
                    val charBase = runningChar
                    for (k in 0 until shape.lineCount) {
                        val h = (shape.lineBottom(k) - shape.lineTop(k)).coerceAtLeast(1)
                        if (emit) {
                            out.add(
                                FlowedLine(
                                    charStart = charBase + (shape.lineStart(k) - shapeStart),
                                    charEnd = charBase + (shape.lineEnd(k) - shapeStart),
                                    yTop = cumY,
                                    yBottom = cumY + h,
                                    paragraphStart = k == 0,
                                ),
                            )
                        }
                        cumY += h
                    }
                    // E23 指定高：内容盒高 = max(行高和, `height`)（CSS 2.1 §10.6.3，与重路径同式）。
                    val spanEnd = contentY + NormalFlowLayout.specifiedContentHeight(ls, cumY - contentY) +
                        (ls.padding.bottom + ls.border.bottom).roundToInt()
                    if (ls.floatSide == orilumn.reader.engine.css.FloatSide.LEFT) {
                        if (floatBottomL == null) floatTopL = y
                        floatBottomL = maxOf(floatBottomL ?: spanEnd, spanEnd)
                    } else {
                        if (floatBottomR == null) floatTopR = y
                        floatBottomR = maxOf(floatBottomR ?: spanEnd, spanEnd)
                    }
                    prevLastLineYBottom = cumY
                }
                prevContentBottom = y
                runningChar += leaf.textLength
                prevBlock = leaf
                continue
            }
            // P6-a2: 非悬浮盒与生效悬浮的关系——环绕叶贴着排（显式单侧 clear 按剩余侧收窄，
            // 指定侧跳跃；回填被收窄侧跨度顶），其余越过重叠侧（窄列回退/table/img/匿名，不重叠）。
            // 基址口径与重路径逐字节一致：判定与失效都看加边距前的 prevContentBottom
            //（重路径 st.y），越过后补回 margin 部分（y - prevContentBottom）。
            val baseBottom = prevContentBottom
            val lead = prepare.floatLeadAt(gi)
            if (lead == null) {
                // 不可环绕：越过一切重叠侧（窄列回退/table/img/匿名，不重叠；advance 后加）。
                val bl = floatBottomL
                if (bl != null && bl > baseBottom) y = bl
                val br = floatBottomR
                if (br != null && br > baseBottom) y = br
                if (floatBottomL != null && floatBottomL!! <= baseBottom) {
                    floatBottomL = null
                    floatTopL = null
                }
                if (floatBottomR != null && floatBottomR!! <= baseBottom) {
                    floatBottomR = null
                    floatTopR = null
                }
            } else {
                if (ls.clearSide == orilumn.reader.engine.css.ClearSide.LEFT || ls.clearSide == orilumn.reader.engine.css.ClearSide.BOTH) {
                    val b = floatBottomL
                    if (b != null && b > baseBottom) y = b
                }
                if (ls.clearSide == orilumn.reader.engine.css.ClearSide.RIGHT || ls.clearSide == orilumn.reader.engine.css.ClearSide.BOTH) {
                    val b = floatBottomR
                    if (b != null && b > baseBottom) y = b
                }
                // P6-a 回填：仅跨度顶之下为空域时回填到跨度顶（基址相位，advance 后加）。
                if (lead.xOffPx > 0.5f) {
                    val t = floatTopL
                    if (t != null && t < y && lastTextBottom <= t) y = t
                }
                if (leaf.contentWidth - lead.widthPx - lead.xOffPx > 0.5f) {
                    val t = floatTopR
                    if (t != null && t < y && lastTextBottom <= t) y = t
                }
                if (floatBottomL != null && floatBottomL!! <= baseBottom) {
                    floatBottomL = null
                    floatTopL = null
                }
                if (floatBottomR != null && floatBottomR!! <= baseBottom) {
                    floatBottomR = null
                    floatTopR = null
                }
            }
            y += advance
            noteLineLessBoxTop(gi, shape, y)
            val shapeStart = if (shape.lineCount > 0) shape.lineStart(0) else 0
            val charBase = runningChar
            val lastK = shape.lineCount - 1
            // First line must sit at the leaf's border-box top (`y`, from emit/master-flow) plus its OWN
            // top border/padding, mirroring emit()'s `contentY = contentTop + topEdges`. Previously this
            // was flush at the border-box top, so any self-padded block (pre/.filename) lost its 9px top
            // padding AND its line stream drifted up — the shared root cause behind the overlapping
            // gray band / .filename on BOTH the heavy and light render paths.
            var cumY = y + (leaf.style.border.top + leaf.style.padding.top).roundToInt()
            for (k in 0 until shape.lineCount) {
                // The shape's per-line height is the CSS line box (lineHeightRatio x font size) for
                // EVERY line — the skia breaker reports it — so geometry,
                // pagination and the painted glyph pitch are in phase and uniform (no last-line drift).
                val h = (shape.lineBottom(k) - shape.lineTop(k)).coerceAtLeast(1)
                // P6-a2 R6: 前视块只预热状态，不发射行。
                if (emit) {
                    out.add(
                        FlowedLine(
                            charStart = charBase + (shape.lineStart(k) - shapeStart),
                            charEnd = charBase + (shape.lineEnd(k) - shapeStart),
                            yTop = cumY,
                            yBottom = cumY + h,
                            paragraphStart = k == 0,
                        ),
                    )
                }
                cumY += h
            }
            // Debug: for a second consecutive paragraph (and for every p onwards), report the box-level
            // gap from the previous paragraph's last line bottom to this paragraph's top, plus this
            // paragraph's line heights, to see what creates the visible inter-paragraph spacing.
            if (EngineDiag.enabled && (leaf.el?.tag == "p" || prevBlock?.el?.tag == "p") && prevLastLineYBottom != null) {
                val lastH = if (shape.lineCount > 0) (shape.lineBottom(lastK) - shape.lineTop(lastK)) else 0
                val firstH = if (shape.lineCount > 0) (shape.lineBottom(0) - shape.lineTop(0)) else 0
                Logger.d("Orilumn.Engine", "PGAP tag=${leaf.el?.tag} prevTag=${prevBlock?.el?.tag} " +
                    "prevLastLineBottom=$prevLastLineYBottom thisTop=$y boxGap=${y - prevLastLineYBottom!!} " +
                    "prevPB=${prevBlock?.style?.padding?.bottom} prevMB=${prevBlock?.style?.margin?.bottom} " +
                    "thisMT=${leaf.style.margin.top} thisMB=${leaf.style.margin.bottom} fs=${leaf.style.fontSizePx} " +
                    "firstLineH=$firstH lastLineH=$lastH")
            }
            prevLastLineYBottom = cumY
            // Mirror emit(): a leaf's contentBottom = max(行底, 指定高) + its vertical padding/border.
            prevBlock = leaf
            val leafContentTop = y + (leaf.style.border.top + leaf.style.padding.top).roundToInt()
            val leafContentBottom =
                if (shape.isReplaceable && shape.replaceableBottom > 0) cumY
                else leafContentTop +
                    NormalFlowLayout.specifiedContentHeight(leaf.style, cumY - leafContentTop)
            prevContentBottom = leafContentBottom + ownBottomEdges(leaf)
            // P6-a2: 实高行底记账（回填不上越它；与重路径同式）。
            if (prevContentBottom > y + (leaf.style.border.top + leaf.style.padding.top).roundToInt()) {
                lastTextBottom = maxOf(lastTextBottom, prevContentBottom)
            }
            runningChar += leaf.textLength
        }
        return LocalLineFlow(out, lineLessBoxTop)
    }

    /** Per-leaf local line ranges for a contiguous [shapes] list — (firstLine, lastLineExclusive)
     *  parallel to the shape list. Does not touch the shared [LayoutBox] line-index fields. */
    private fun localLineRanges(shapes: List<ParagraphShapeRef>): Pair<IntArray, IntArray> {
        val first = IntArray(shapes.size)
        val last = IntArray(shapes.size)
        var g = 0
        for (i in shapes.indices) { first[i] = g; g += shapes[i].lineCount; last[i] = g }
        return first to last
    }

    // ─────────────────────────────────────────────────────────────────
    // Anchor-based temp-page shaping (whole blocks per page, cut on block boundaries)
    // ─────────────────────────────────────────────────────────────────

    /**
     * Builds the line-anchored anchor page for relayout. The page begins at the **line** that
     * contains [anchorChar] (so that character always lands on the current page's first line), then
     * includes the rest of that block and whole blocks until the page is full. Its top may start
     * mid-block (the block's leading lines before the anchor line belong to the backward page).
     *
     * 页尾与 [shapeTempPageForward] **同源**：本函数只圈窗口，切点由 `assembleTempPage` 内的
     * `Paginator.paginateFrom` 给出（整行填充 + 强制段 + avoid 簇），锚点落在正典页首行时锚页
     * 逐字等于磁盘表对应页。
     */
    fun shapeAnchorPageForward(
        prepare: LightPrepare,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
        anchorChar: Int,
        cache: MutableMap<Int, ParagraphShapeRef>?,
    ): ForwardedPage {
        val total = prepare.totalBlocks
        // 空章进锚点流是调用方 bug（跨章已跳过无正文章）：以往 coerce 到块 0 后偶然越界，
        // 信息为零。现在出生即炸。
        require(total > 0) { "shapeAnchorPageForward empty chapter blocks=0" }
        val anchorBlock = prepare.blockIndexForChar(anchorChar.coerceAtLeast(0)).coerceIn(0, (total - 1).coerceAtLeast(0))

        // Find the line within the anchor block that contains anchorChar.
        val s0 = tempShape(cache, prepare, anchorBlock, profile)
        val baseChar = prepare.globalCharStarts[anchorBlock].toInt()
        val localAnchor = (anchorChar.coerceAtLeast(baseChar) - baseChar)
        var lIdx = 0
        for (k in 0 until s0.lineCount) if (s0.lineStart(k) <= localAnchor && localAnchor < s0.lineEnd(k)) { lIdx = k; break }

        // 本函数**只圈窗口**（本页大致触达哪些块）：按内容行高 + 块间 advance 累加到超出 contentH。
        // 页尾切点**不在这里决定** —— 它由 `assembleTempPage` 内的 `Paginator.paginateFrom` 给出，
        // 即锚页与临时表/磁盘表/增量窗用**同一段**分页代码（含整行填充、强制段与 avoid 簇）。
        //
        // 旧版在这里自算填充并把它当页尾**钉死**（`endLineCharStart = ecs`）：高度累加器只看行高 +
        // `leadAbove`/`blockBoundaryGap`，**看不到 avoid 规则**（`break-inside: avoid` 的原子区间、
        // 禁止切点）。凡 canonical 因 avoid 提前收盘的页（真书 ch2 的图/侧栏块），锚页就把切点钉在
        // avoid 块之后 ⇒ 页尾多吃若干行、与磁盘表错开（JVM 复现：重路径 [21240,23601) / 旧锚页
        // [21240,23628)）。窗口不闭合 avoid 边链时 `Paginator` 同样看不到链尾接口的禁止切点，故这里
        // 与 [shapeTempPageForward] 一样沿 [BreakClusters.chainRange] 把窗口取整到链闭合。
        val shapes = ArrayList<ParagraphShapeRef>()
        var acc = 0
        var endBlockExclusive = anchorBlock
        var b = anchorBlock
        // 本页已纳入的行数（「游标永不停滞」判据，见 [shapeTempPageForward]）。
        var placedLines = 0
        var full = false
        while (b < total) {
            if (b > anchorBlock) {
                // 块间落差单源 [blockBoundaryGap]。**页首块的缺口与页尾叶自身边在这里刻意不收**：acc
                // 只用来圈窗口，少收 ⇒ 窗口偏大，必然仍含真实切点（证明见 [shapeTempPageForward]）。
                acc += blockBoundaryGap(prepare, b - 1, b)
            }
            val s = tempShape(cache, prepare, b, profile)
            shapes.add(s)
            val k0 = if (b == anchorBlock) lIdx.coerceAtLeast(0) else 0
            // P4-a3: 悬浮 img 零高（字符槽照占、页高不计；与重路径分页同式）。
            val aLeaf = prepare.block(b)
            val aFloat = aLeaf.table == null && aLeaf.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE &&
                (s.isReplaceable || s.lineCount > 0)
            if (!aFloat) {
            for (k in k0 until s.lineCount) {
                // Every shaped line equals the CSS line box (uniform across first/interior/last), so a
                // page fills by exactly the drawn pixels per line, never over/under-estimating.
                val h = (s.lineBottom(k) - s.lineTop(k)).coerceAtLeast(1)
                // 「游标永不停滞」（单源口径同上）：页首行自身就高过整页时强收这一行。
                if (placedLines > 0 && acc + h > contentH) { full = true; break }
                acc += h
                placedLines++
            }
            }
            endBlockExclusive = b + 1
            if (full) break
            b++
        }
        // 窗口取整：切点块所在的 avoid **边链**可能跨出本窗（链尾在后面若干块）。窗口若把链截断，
        // `PartialDrawableLayout.breakPlan` 就看不到链上后续接口的禁止切点，故沿 [BreakClusters.chainRange]
        // 把窗口补到链闭合。这**只是取窗口**，不决定任何页边界。
        if (endBlockExclusive < total) {
            val chain = orilumn.reader.engine.laying.BreakClusters.chainRange(
                endBlockExclusive - 1, anchorBlock, total,
                before = { prepare.block(it).breakBefore }, after = { prepare.block(it).breakAfter },
            )
            while (endBlockExclusive <= chain.last) {
                shapes.add(tempShape(cache, prepare, endBlockExclusive, profile))
                endBlockExclusive++
            }
        }
        // 页尾切点 = `Paginator.paginateFrom`（与磁盘表/增量窗的**同一段**代码）；`endLineCharStart = null`
        // ⇒ 走整行填充 + 断页规则，而不是把切点钉在某个字符行上。
        val page = assembleTempPage(prepare, anchorBlock, endBlockExclusive, shapes, pageFirstLine = lIdx.coerceAtLeast(0), endLineCharStart = null, contentH = contentH) {
            tempShape(cache, prepare, it, profile)
        }
        diagPage(contentH, page, "anchor")
        // 把 `Paginator` 解出的行切点翻回 `(block, line)` 游标（与 [shapeTempPageForward] 同式）。
        val (lf, ll) = localLineRanges(shapes)
        var nb = endBlockExclusive
        var nl = 0
        for (i in lf.indices) {
            if (page.slice.lastLineExclusive in lf[i] until ll[i]) {
                nb = anchorBlock + i
                nl = page.slice.lastLineExclusive - lf[i]
                break
            }
        }
        tempDiag("ANCHOR", "in anchorChar=$anchorChar anchorBlk=$anchorBlock lIdx=$lIdx", page, "next=($nb,$nl)")
        return ForwardedPage(page, nb, nl)
    }

    /**
     * Builds a single **whole-block** forward page starting at [startBlock]: shapes consecutive
     * blocks until the next one would exceed [contentH] (an oversized first block becomes its own
     * page, matching the box-layout page-break rule). The page is rendered top-aligned from its own
     * layout, so no page ever starts/ends mid-block.
     * @return the built page, or null when [startBlock] is out of range.
     */
    fun shapeTempPageForward(
        prepare: LightPrepare,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
        startBlock: Int,
        cache: MutableMap<Int, ParagraphShapeRef>?,
        startLine: Int = 0,
    ): ForwardedPage? {
        val total = prepare.totalBlocks
        if (startBlock !in 0 until total) return null
        // 本函数**只圈窗口**（本页大致触达哪些块）：按内容行高 + 块间 advance 累加到超出 contentH。
        // 页尾切点**不在这里决定** —— 它由 `assembleTempPage` 内的 `Paginator.paginateFrom` 给出，
        // 即临时表与磁盘表/增量窗**同一段**分页代码（旧版在此另写了一份 avoid 块链 + 簇高填充，
        // 与 canonical 漂移：`pre` 整块后移、目录乱分页、半页空白）。
        val shapes = ArrayList<ParagraphShapeRef>()
        var acc = 0
        var endBlockExclusive = startBlock
        var b = startBlock
        // 本页已纳入的行数（「游标永不停滞」判据，见下）。
        var placedLines = 0
        var full = false
        while (b < total) {
            if (b > startBlock) {
                // 块间落差（单源 [blockBoundaryGap] = 前块自身下边 + 折叠边距 + 本块自身 top 边）——
                // 与 `rebuildLocalLines` 的行流推进、与 canonical `emit` 逐字节同值。
                //
                // **页首块的缺口与页尾叶自身边在这里刻意不收**：本函数的 acc 只用来**圈窗口**
                // （页尾切点由 `Paginator` 在同一段代码里决定）。少收 ⇒ acc 偏小 ⇒ 窗口偏大，
                // 一定仍然包含真实切点（证明：若切点 C 在 acc 溢出行 L 之后，则 frame(C) ≥ acc(L)+h(L)
                // > contentH，与 C 是切点矛盾）；多收才会把切点挤到窗外。窗口只影响塑形成本。
                acc += blockBoundaryGap(prepare, b - 1, b)
            }
            val s = tempShape(cache, prepare, b, profile)
            shapes.add(s)
            val k0 = if (b == startBlock) startLine.coerceAtLeast(0) else 0
            // P4-a3: 悬浮零高（字符槽照占、页高不计；与重路径分页同式）。
            // P6-a2: img/文本悬浮一律跳过（文本悬浮行画在悬浮位，分页不计高）。
            val fLeaf = prepare.block(b)
            val fStyle = fLeaf.style
            val fFloat = fLeaf.table == null && fStyle.floatSide != orilumn.reader.engine.css.FloatSide.NONE &&
                (s.isReplaceable || s.lineCount > 0)
            if (!fFloat) {
            for (k in k0 until s.lineCount) {
                // Every shaped line is the CSS line box; fill by it so capacity matches drawn pixels.
                val h = (s.lineBottom(k) - s.lineTop(k)).coerceAtLeast(1)
                // 游标永不停滞（单源口径：重路径 `Paginator.fillWholeLines` 末尾
                // `if (end == startLine) end = startLine + 1`）——页首行自身就高过整页时**强收这一行**
                // 并允许它被页面裁掉（oversized block 允许撕裂，与浏览器一致），而不是把切点留在页首行。
                // 切在页首行会产出 `charStart == charEnd` 且 next == 起点的零前进退化页：前向预填水位
                // 永不推进（窗口塞满同页副本），翻页原地不动。
                // 复现：宽屏全屏（版心 4920x2560）下主题 `width:100%` 把 1122x735 的插图拉到 3223 高。
                if (placedLines > 0 && acc + h > contentH) { full = true; break }
                acc += h
                placedLines++
            }
            }
            endBlockExclusive = b + 1
            if (full) break
            b++
        }
        // 窗口取整：切点块所在的 avoid **边链**可能跨出本窗（链尾在后面若干块）。窗口若把链截断，
        // `PartialDrawableLayout.breakPlan` 就看不到链上后续接口的禁止切点，故沿单源
        // [BreakClusters.chainRange] 把窗口补到链闭合。这**只是取窗口**，不决定任何页边界。
        if (endBlockExclusive < total) {
            val chain = orilumn.reader.engine.laying.BreakClusters.chainRange(
                endBlockExclusive - 1, startBlock, total,
                before = { prepare.block(it).breakBefore }, after = { prepare.block(it).breakAfter },
            )
            while (endBlockExclusive <= chain.last) {
                shapes.add(tempShape(cache, prepare, endBlockExclusive, profile))
                endBlockExclusive++
            }
        }
        // 页尾切点 = `Paginator.paginateFrom`（临时表与磁盘表/增量窗的**同一段**代码）；
        // `endLineCharStart = null` ⇒ 走整行填充 + 断页规则，而不是把切点钉在某个字符行上。
        val page = assembleTempPage(prepare, startBlock, endBlockExclusive, shapes, pageFirstLine = startLine.coerceAtLeast(0), endLineCharStart = null, contentH = contentH) {
            tempShape(cache, prepare, it, profile)
        }
        diagPage(contentH, page, "fwd")
        // 把 `Paginator` 解出的行切点翻回 `(block, line)` 游标（行区间与 `rebuildLocalLines` 同式）。
        val (lf, ll) = localLineRanges(shapes)
        var nb = endBlockExclusive
        var nl = 0
        for (i in lf.indices) {
            if (page.slice.lastLineExclusive in lf[i] until ll[i]) {
                nb = startBlock + i
                nl = page.slice.lastLineExclusive - lf[i]
                break
            }
        }
        tempDiag("FWD", "in blk=$startBlock line=$startLine", page, "next=($nb,$nl)")
        return ForwardedPage(page, nb, nl)
    }

    /**
     * Builds a single backward page ending at [endExclusive] (blocks `[low, endExclusive)`), packed
     * closest-to-`endExclusive` first. When [endLineCharStart] >= 0, the page's last line is trimmed
     * to the line starting at that char offset (used for the page immediately before a line-anchored
     * anchor page), so the same block is split between two pages at the reader's line with no overlap.
     *
     * Mirrors [shapeTempPageForward] (line-based, targetLh, inter-block gap from consecutiveLeafAdvance),
     * but walks blocks bottom-up and accumulates lines from each block's bottom upward. On overflow the
     * current block is **kept and truncated** (its topmost fitting line becomes the page's first line),
     * matching forward's "cut inside block" semantics exactly.
     * @return the built page, or null when [endExclusive] is out of range.
     * @param pinnedStart 本会话前向链边缓存 `ip.prevStart` 给出的页首游标 `(块, 块内行)`——命中即以该行
     *   页首组装，跳过几何倒包估计；仍要过下面的页尾 pin 自校验，失配则记 w 回退倒包（见
     *   `BookDocumentController.resolveBackwardStartCursor`）。**不读磁盘表/正典**：章内边界只由临时
     *   表自己决定。
     */
    fun shapeTempPageBackward(
        prepare: LightPrepare,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
        endExclusive: Int,
        endLineCharStart: Int,
        cache: MutableMap<Int, ParagraphShapeRef>?,
        pinnedStart: Pair<Int, Int>? = null,
    ): TempPage? {
        val total = prepare.totalBlocks
        if (endExclusive <= 0 || endExclusive > total) return null

        // Walk blocks bottom-up from endExclusive-1. On overflow we truncate the current block rather
        // than drop it, so partial blocks stay visible at the page top (mirrors forward's mid-block cut).
        // We collect shapes for every visited block (in visual order) and sublist after the loop using
        // the final [startBlock, endExclusive) range — this keeps shapes aligned with the block range
        // that assembleTempPage will pass to rebuildLocalLines.
        val allShapes = ArrayList<ParagraphShapeRef>()
        var acc = 0
        var startBlock = endExclusive - 1   // final page's first block (may be partially consumed)
        var startLine = 0                  // final page's first line inside startBlock (will be set on truncation)
        var b = endExclusive - 1
        var firstInPage = true
        var truncated = false
        // 本页已纳入的行数（「游标永不停滞」判据，见下）。
        var placedLines = 0
        while (b >= 0) {
            val leaf = prepare.block(b)
            // P4d：强制断页边（块 b 与 b+1 之间，`always`）⇒ 本页必须在块 b **之前**收盘，
            // 块 b 归前一页。与 `shapeTempPageForward`、与 canonical 的分段同口径。
            if (!firstInPage &&
                orilumn.reader.engine.laying.BreakClusters.isForced(leaf.breakAfter, prepare.block(b + 1).breakBefore)
            ) {
                startBlock = b + 1
                startLine = 0
                truncated = true
                break
            }
            val s = tempShape(cache, prepare, b, profile)
            allShapes.add(0, s)  // prepend — we walk backward, but shapes must stay in visual order

            // Bottom block's tailing lines may belong to the anchor page (endLineCharStart >= 0).
            val maxLine = if (b == endExclusive - 1 && endLineCharStart >= 0) {
                val baseChar = prepare.globalCharStarts[b].toInt()
                val localEnd = endLineCharStart - baseChar
                (0 until s.lineCount).firstOrNull { s.lineStart(it) >= localEnd } ?: s.lineCount
            } else s.lineCount

            if (firstInPage) {
                firstInPage = false
                // 页**尾**叶自身边：页尾行恰为本窗末块末行时，帧底 = 行底 + 该叶自身 `padding-bottom`/
                // `border-bottom`（[BookLayout.getPageFrameBottom]）。页尾被钉在锚点行（`endLineCharStart`
                // 剪掉末行）时该边不在页内，故按 `maxLine` 判定。
                //
                // 块边界缺口仍不再算进本页——它归**下一页**页首（旧口径把整段缺口算进页尾，页尾就少装
                // 一行；v3 下下一页页首只收 [leadAbove]，折叠段前距两页都不收），而本块的**自身下边**
                // 例外：它是帧**内**的一段（见下）。
                if (maxLine == s.lineCount) {
                    acc += ownBottomEdges(prepare.block(endExclusive - 1))
                }
            } else {
                // 页内**其余块**（含本页起始块）的自身下边同样落在本页帧内：本页无论从本块哪一行起页，
                // 块末行都在页内，而块底那段（`ownBottomEdges`）是「块末行行底 → 下一块首行行顶」整段
                // 落差 [blockBoundaryGap] 里属于**本块帧底**的一部分（[BookLayout.getPageFrameBottom]），
                // 故随块末行落进本页。
                //
                // 帧高单源推导：自页首行 S 到页尾行 L 的帧跨度
                //   span(S, L) = frameBottom(L) − frameTop(S)
                //              = [leadAbove(页首块)（仅当页首落在某块首行）] + Σ行高
                //              + Σ_{页内块 b} ownBottomEdges(b) + Σ_{页内块 b > 页首块} interiorGapAbove(b)
                // （[blockBoundaryGap] = ownBottomEdges(prev) + 折叠边距 + 本块自身 top 边；
                // [interiorGapAbove] = blockBoundaryGap − ownBottomEdges(prev) 是**页内**边界段，
                // [leadAbove] 是它的**页首**版本（段前距并入页边距，故少一段）。
                // `shapeAnchorPageForward` 的累加式是同一恒等式的另一种写法。）
                // ⇒ **每个**页内块都要记一次自身下边。旧式只记页尾那一次，
                // acc 偏小 ⇒ 倒包起点过早 ⇒ `Paginator` 从该起点整行填充收不到页尾（帧高先超）⇒
                // 「末行被推走/页尾 2 字落到页外」——真机 ch2 倒包页少了整整一行（10 字），随后前向重建
                // 从错位原点起塑，来回翻页内容就变了。
                acc += ownBottomEdges(leaf)
            }

            // Accumulate lines from the bottom of this block upward. On overflow truncate this block
            // at the last fitting line — the page starts there.
            // P4-a3: 悬浮零高（字符槽照占、页高不计；shapes 照收以保块对齐）。
            // P6-a2: img/文本悬浮一律跳过。
            var overflowLine = -1
            val bFloat = leaf.table == null && leaf.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE &&
                (s.isReplaceable || s.lineCount > 0)
            if (!bFloat) {
            for (k in (maxLine - 1) downTo 0) {
                // Every shaped line is the CSS line box; fill by it so capacity matches drawn pixels.
                val h = (s.lineBottom(k) - s.lineTop(k)).coerceAtLeast(1)
                // 行顶之上那一段（只有本块首行 k == 0 才有）分两种口径，**绝不混用**：
                //  · 本行若**当选页首行**（下面已收的各行都在本页内、更靠上的行都进不来）⇒ 只记本块
                //    **自身 top 边** [leadAbove]：段前折叠 `margin-top` 并入页边距（v3 口径）。
                //  · 本行只是**页内**边界（页首还在更上面）⇒ 整段落差 [interiorGapAbove]（段前距 +
                //    本块自身 top 边）。
                // `acc` 一路按**页内**口径累加；判据才用 [leadAbove]，两者之差正是被页边距吞掉的段前距。
                // 这样继续向上找页首时，下面已收的各段仍是完整的页内落差（若把页首口径也累进 acc，
                // 每个块边界都会少收一段折叠边距 ⇒ 倒包页多装行）。
                val interior = if (k == 0 && b > 0) interiorGapAbove(prepare, b) else 0
                val pageTop = if (k == 0) leadAbove(prepare, b) else 0
                // 与 shapeTempPageForward 同一条「游标永不停滞」guard（单源：重路径 fillWholeLines）：
                // 页底块自己那一行就高过整页时（宽屏全屏下的超高插图）强收它并允许撕裂，否则
                // `overflowLine == maxLine - 1` ⇒ `startLine = maxLine` ⇒ 空页/丢块，向前回溯同样卡住。
                if (placedLines > 0 && acc + pageTop + h > contentH) { overflowLine = k; break }
                acc += interior + h
                placedLines++
            }
            }
            if (overflowLine >= 0) {
                // Truncate: page's first line inside this block = overflowLine + 1.
                startBlock = b
                startLine = overflowLine + 1
                truncated = true
                break
            }
            b--
        }
        // If no truncation happened, all blocks from 0..endExclusive-1 fit perfectly.
        if (!truncated) startBlock = 0
        // P4：页首 = 前一页的切点，故须与 canonical 同源判「这个切点是否合法」——与 canonical 同判据、
        // 同几何，全部来自单源：窗口先沿 [BreakClusters.chainRange] 取整到 avoid 边链闭合，再用
        // `PartialDrawableLayout.breakPlan`（= `BreakClusters.plan`）的原子区间 / 禁止切点，由
        // `Paginator.resolveAvoidCut` 退到前一个合法切点、`Paginator.paginateFrom` 校验「自该切点整行
        // 填充恰好收到锚点行」（既不溢出也不早停）。旧版自算块链高度，与 canonical 漂移。
        var pageStartBlock = startBlock
        var pageStartLine = startLine
        var shapes = allShapes.takeLast(endExclusive - pageStartBlock)
        if (truncated && pageStartBlock < endExclusive) {
            val chain = orilumn.reader.engine.laying.BreakClusters.chainRange(
                pageStartBlock, 0, endExclusive,
                before = { prepare.block(it).breakBefore }, after = { prepare.block(it).breakAfter },
            )
            if (chain.first < pageStartBlock || pageStartLine > 0) {
                val below = ArrayList<ParagraphShapeRef>()
                for (i in chain.first until pageStartBlock) below.add(tempShape(cache, prepare, i, profile))
                val probeShapes = below + shapes
                val packStart = below.sumOf { it.lineCount } + pageStartLine
                val probe = assembleTempPage(
                    prepare, chain.first, endExclusive, probeShapes, pageFirstLine = packStart,
                    endLineCharStart = if (endLineCharStart >= 0) endLineCharStart else null, contentH = contentH,
                ) { tempShape(cache, prepare, it, profile) }
                val probeAware = probe.layout as? orilumn.reader.engine.paging.BreakAwareBookLayout
                val probeRanges = probeAware?.avoidSplitRanges ?: emptyList()
                val probeForbidden = probeAware?.forbiddenCutLines ?: emptySet()
                // `pageStartLine = -1`：本页页首 = **前一页的切点**，故原子区间首在 0 时也要退到区间首
                // （而不是把它当成「页首恰为区间首 ⇒ 装到区间尾」）。禁止切点同源一并修正。
                val adj = Paginator.resolveAvoidCut(
                    probeRanges, probeForbidden, -1, packStart, contentH,
                    probe.layout::getPageFrameTop, probe.layout::getPageFrameBottom,
                )
                if (adj != packStart) {
                    val pinned = (0 until probe.layout.lineCount).firstOrNull { probe.layout.getLineStart(it) >= endLineCharStart }
                        ?: probe.layout.lineCount
                    val p0 = Paginator.paginateFrom(probe.layout, adj, contentH).firstOrNull()
                    if (p0 != null && p0.lastLineExclusive == pinned) {
                        val (lf, ll) = localLineRanges(probeShapes)
                        for (i in lf.indices) {
                            if (adj in lf[i] until ll[i]) {
                                pageStartBlock = chain.first + i
                                pageStartLine = adj - lf[i]
                                shapes = probeShapes.takeLast(endExclusive - pageStartBlock)
                                break
                            }
                        }
                    }
                }
            }
        }
        // ── 页尾 pin 自校验（**单源**：页首切点由 `Paginator` 反查，不由自算累加器拍板）──
        // 本页必须**恰好**收到 pinChar —— 相邻前向页的页首字符（`endLineCharStart`）或本窗末块的后继
        // 块首字（块边界收页）。短了 = 页缝丢字（两页之间那段字符无人显示），长了 = 两页重叠。
        // 倒包估计与 `Paginator` 帧口径的**任何**失配（页内块自身下边、页首缺口、avoid 退切、窗口端点）
        // 都在这里被拦下、当场修正并出声，而不是把错位页灌进窗口。真机症状「来回翻页内容变了」正是
        // 错位页进窗口：倒包页尾少 10 字，前向重建又从错位的原点起塑，页界整条链跟着漂。
        val pinChar = if (endLineCharStart >= 0) endLineCharStart
            else prepare.globalCharStarts.getOrElse(endExclusive) { prepare.totalChars.toLong() }.toInt()
        fun assembleFrom(sb: Int, sl: Int, sh: List<ParagraphShapeRef>): TempPage =
            assembleTempPage(prepare, sb, endExclusive, sh, pageFirstLine = sl,
                endLineCharStart = if (endLineCharStart >= 0) endLineCharStart else null,
                contentH = contentH) { tempShape(cache, prepare, it, profile) }
        // 本会话前向链边缓存给出的页首游标（`ip.prevStart`）：按该行组装即为读者刚看过的那一页，省掉
        // 整段几何估计。仍要过 pin 自校验——游标与轻路径几何若分叉（落在别的行上），这里当场拦下。
        if (pinnedStart != null) {
            val (pb, pl) = pinnedStart
            val pShape = if (pb in 0 until endExclusive) tempShape(cache, prepare, pb, profile) else null
            if (pShape != null && pl >= 0 && pl < pShape.lineCount) {
                val pShapes = (pb until endExclusive).map { tempShape(cache, prepare, it, profile) }
                val pinned = assembleFrom(pb, pl, pShapes)
                if (pinned.slice.charEnd == pinChar) {
                    tempDiag("BWD", "in endExcl=$endExclusive lineCut=$endLineCharStart", pinned,
                        "truncated=$truncated pinned=($pb,$pl)")
                    return pinned
                }
                Logger.w(
                    "Orilumn.TEMP",
                    "BWD-PIN-DRIFT href=$chapterHref pin=$pinChar pinned=($pb,$pl) " +
                        "got=[${pinned.slice.charStart},${pinned.slice.charEnd}) —— 权威页首游标与几何不一致，回退倒包",
                )
            } else {
                Logger.w("Orilumn.TEMP", "BWD-PIN-STALE href=$chapterHref pinnedStart=($pb,$pl) endExcl=$endExclusive")
            }
        }
        val backPage = assembleFrom(pageStartBlock, pageStartLine, shapes)
        if (backPage.slice.charEnd == pinChar) {
            tempDiag("BWD", "in endExcl=$endExclusive lineCut=$endLineCharStart", backPage,
                "truncated=$truncated start=($pageStartBlock,$pageStartLine)")
            return backPage
        }
        // 慢路（估计失配）：在**探针布局**上用帧几何反查「自哪一行起页，整行填充恰好收到 pin」——
        // 逐行下移（页变短）取**首个**可行行 = 倒包允许的最大填充（页最满），判据就是
        // `Paginator.paginateFrom`（与 `assembleTempPage` 逐字同式），只多付一次组装。
        val layout = backPage.layout
        val pinLine = if (endLineCharStart >= 0) {
            (0 until layout.lineCount).firstOrNull { layout.getLineStart(it) == pinChar } ?: layout.lineCount
        } else layout.lineCount
        var fixedLine = -1
        var probeLine = pageStartLine + 1
        while (probeLine < pinLine) {
            val p = Paginator.paginateFrom(layout, probeLine, contentH).firstOrNull()
            if (p != null && p.lastLineExclusive >= pinLine) { fixedLine = probeLine; break }
            probeLine++
        }
        if (fixedLine >= 0) {
            val (lf, ll) = localLineRanges(shapes)
            for (i in lf.indices) {
                if (fixedLine in lf[i] until ll[i]) {
                    val fb = pageStartBlock + i
                    val fl = fixedLine - lf[i]
                    val fixed = assembleFrom(fb, fl, shapes.drop(i))
                    if (fixed.slice.charEnd == pinChar) {
                        Logger.w(
                            "Orilumn.TEMP",
                            "BWD-FIX href=$chapterHref pin=$pinChar start=($pageStartBlock,$pageStartLine)→($fb,$fl) " +
                                "lost=${pinChar - backPage.slice.charEnd}（倒包估计偏差，已按 Paginator 反查修正）",
                        )
                        tempDiag("BWD", "in endExcl=$endExclusive lineCut=$endLineCharStart", fixed,
                            "truncated=$truncated fixed=($fb,$fl)")
                        return fixed
                    }
                    break
                }
            }
        }
        Logger.e(
            "Orilumn.TEMP",
            "BWD-UNFIXED href=$chapterHref pin=$pinChar endExcl=$endExclusive start=($pageStartBlock,$pageStartLine) " +
                "got=[${backPage.slice.charStart},${backPage.slice.charEnd}) lines=${layout.lineCount} —— 反查无解（页缝丢字），保留估计页待查",
        )
        tempDiag("BWD", "in endExcl=$endExclusive lineCut=$endLineCharStart", backPage,
            "truncated=$truncated start=($pageStartBlock,$pageStartLine) UNFIXED")
        return backPage
    }

    /**
     * R20 塑形成本探针（诊断用）。把 `shape` 段拆成两笔：**CSS 级联**（[LightPrepare.inlineStyles]
     * 的 resolve 循环）与 **Skia 断行**（[shapeLeaf] 本体）。另记两块——被塑元素数与最大嵌套深度，
     * 后者用来验证「深层嵌套把 resolve 拖成 O(depth)」这一假设。
     *
     * 挂在 layouter 实例上而非参数上：塑形全程在 layoutMutex 内单线程串行，[incrementalLayoutForPage]
     * 进入时置位、形状段结束即清空，嵌套调用（rebuildLocalLines 的回看 lambda）自动记进同一笔。
     */
    private class ShapeProbe {
        var stylesMs = 0L
        var skiaMs = 0L

        /** [LightPrepare.block] 物化总耗时（子树外的部分：盒构造、容器链下降、textLength）。 */
        var blockMs = 0L

        /** `blockMs` 的子集：块内 `textLength` 的 styledCharAdvance。现恒为 0（改走差分），留作哨兵。 */
        var advanceMs = 0L

        /** `blockMs` 的四段拆分：块样式 / 内容宽下降 / 内容左下降 / textLength 差分。 */
        var styleForMs = 0L
        var widthMs = 0L
        var leftMs = 0L
        var textLenMs = 0L

        /** 本窗口第一次 [LightPrepare.block] 物化的耗时；`sBlk ≈ sBlkFirst` 即一次性初始化而非逐块成本。 */
        var blockFirstMs = 0L
        var blocks = 0
        var elements = 0
        var maxDepth = 0

        // R26：把 `stylesMs` 拆到渲染层内部（选择器匹配 / 内联属性解析 / 建 ComputedStyle /
        // 通用字体兜底的第二次级联）。数据来自 common 的 `CascadeProbe`，由本窗口装 sink 收集。
        var cascadeMatchMs = 0L
        var inlineParseMs = 0L
        var styleBuildMs = 0L
        var secondPassMs = 0L

        /** R29：`styleBuildMs` 的细分——「边家族」六兄弟（margin/padding/border-*）合计。 */
        var cEdgeMs = 0L

        /** R29：`styleBuildMs` 的细分——font-family 三连（同一字符串解析三次中的两次）。 */
        var cFontMs = 0L

        /** R29 三层：`cEdge` 按家族拆——padding（margin 计入 cEdge 总量，不在此分）。 */
        var eBoxMs = 0L

        /** R29 三层：`parseBorderEdges`。 */
        var eWidthMs = 0L

        /** R29 三层：`parseBorderColors`。 */
        var eColorMs = 0L

        /** R29 三层：`parseBorderStyles`。 */
        var eStyleMs = 0L

        /** R29 三层：`parseBorderRadius`。 */
        var eRadiusMs = 0L

        /** R29 三层：真声明了 `border*` 属性的元素数（= 空跑 vs 真解析的分界）。 */
        var eAnyBorder = 0L
    }

    private var shapeProbe: ShapeProbe? = null

    /**
     * P3：一个块**自身 border-box 高**的自然测量（口径乙 `othersH` 的轻路径口径）。
     *
     * 与重路径的 `box.contentBottom - box.contentTop` 同式：正文叶 = 行高和，替换叶 = used 高，
     * 都再＋自身纵向 padding/border（表格行高由 [shapeLeaf] 内部合成，同式）。
     *
     * 塑形**不进共享塑形缓存**：预算定在各叶塑形**之前**，此时若是把自然尺寸的 shape 写进缓存，
     * 之后按预算塑形就会命中旧值（图的 used 高与预算不符 ⇒ 量画漂移）。
     */
    private fun clusterBlockOwnHeight(prepare: LightPrepare, i: Int): Int {
        if (i < 0 || i >= prepare.totalBlocks) return 0
        val leaf = prepare.block(i)
        val edges = (leaf.style.padding.vertical + leaf.style.border.vertical).roundToInt()
        if (leaf.replaceableHeight > 0) return leaf.replaceableHeight + edges
        val sh = shapeBlock(prepare, i, leaf, prepare.profile)
        val contentH = if (sh.lineCount > 0) (sh.lineBottom(sh.lineCount - 1) - sh.lineTop(0)) else 0
        return contentH + edges
    }

    private fun shapeBlock(
        prepare: LightPrepare,
        i: Int,
        leaf: LayoutBox,
        profile: TypographicProfile,
    ): ParagraphShapeRef {
        val probe = shapeProbe ?: return shapeBlockWith(prepare, i, leaf, profile, prepare.inlineStyles(i))
        // 探针开启：CSS 级联与 Skia 断行分别计墙钟；深度只数一次（每块 O(depth)，相对两侧开销可忽略）。
        val t0 = orilumn.reader.time.platformNowMs()
        val styles = prepare.inlineStyles(i)
        val t1 = orilumn.reader.time.platformNowMs()
        val r = shapeBlockWith(prepare, i, leaf, profile, styles)
        val t2 = orilumn.reader.time.platformNowMs()
        probe.stylesMs += t1 - t0
        probe.skiaMs += t2 - t1
        probe.blocks++
        probe.elements += styles.size
        var d = 0
        var n: MarkupElement? = leaf.el
        while (n != null) { d++; n = n.parent }
        if (d > probe.maxDepth) probe.maxDepth = d
        return r
    }

    private fun shapeBlockWith(
        prepare: LightPrepare,
        i: Int,
        leaf: LayoutBox,
        profile: TypographicProfile,
        styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
    ): ParagraphShapeRef = shapeLeaf(
        leaf, styles, profile,
        listMarkerFor(leaf.el, prepare::resolveStyle, prepare.firstCarrierLeaves),
        // P3-a: 内联表只含子树，祖先 opacity 经懒级联回退（与重路径整表同值）。
        ancestorStyleOf = prepare::resolveStyle,
        // P3-c: 生成内容与塑形同一 phase-1 结果。
        genOf = prepare.genOf,
        // `::first-letter`：与重路径同值（叶根懒解＋缓存）。
        firstLetterOf = prepare.firstLetterOf,
        // P4-a3: 悬浮环绕前导与重路径同源（双路断行逐字节一致）。
        floatLead = prepare.floatLeadAt(i),
        // P6-a2: 文本悬浮按悬浮宽塑形（与重路径同宽；img/表格不管）。
        breakWidthOverride = if (leaf.table == null &&
            (leaf.el?.let { NormalFlowLayout.isReplaceable(it) } != true) &&
            leaf.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE
        ) {
            prepare.floatWidths[i]?.let { NormalFlowLayout.innerBreakWidth(leaf.style, it) }
        } else null,
        classify = prepare.lightClassify(),
        hidden = prepare.hidden,
        // P3：分页层高度预算来自盒（`LightPrepare.applyReplacedFits` 写入），塑形只是读它。
        replaceableFitH = leaf.replaceableFitH,
    )

    /** 无 [classify] 时的块判据回退（与 [shapeLeaf] 的 `BLOCK_TAGS` 同规则）。 */
    private val BLOCK_TAG_CLASSIFY = BlockClassify { it.tag in BLOCK_TAGS }

    /**
     * Fills a table-row leaf's per-cell shapes ([TableCellLayout.shape]/`height`, each shaped
     * within its column width) and returns the row's own height (max over `rowspan == 1` cells).
     *
     * The fill MUST run on the exact leaf instances the draw/expand stage reads: cell shapes live
     * only on those instances, so every shaping path that can produce a row shape — full shaping
     * ([shapeLeaf]) and shape-cache hits ([tempShape]) alike — has to (re)fill them. Otherwise a
     * cache hit on re-materialized leaves hands [TableCellLines.expandTable] cells with null
     * shapes, which it silently drops (no text, no borders): a blank table holding its space.
     */
    private fun fillTableRowCells(
        leaf: LayoutBox,
        t: orilumn.reader.engine.laying.TableRowLayout,
        styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        profile: TypographicProfile,
        ancestorStyleOf: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? = null,
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
        classify: orilumn.reader.engine.laying.BlockClassify? = null,
        hidden: orilumn.reader.engine.laying.HiddenCheck = orilumn.reader.engine.laying.HIDDEN_NONE,
        /** `::first-letter` 伪元素查找（格内块同样吃首字规则的放大/着色）。 */
        firstLetterOf: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? = null,
    ): Int {
        // 行高只取本行「rowspan==1」格的最大外高：跨行格的高度由其跨越的各行**分摊**
        // （[TableGridModel.resolveRowHeights]，浏览器实测口径），故此处不把跨行格整体压进
        // 本行——否则 rowspan 表首行虚高、rowspan==1 格下方留白。每格外高写回
        // [TableCellLayout.height]，供后续 [applyTableRowspanHeights] 分摊读取。
        var rowH = leaf.replaceableHeight.coerceAtLeast(1)
        for (cell in t.cells) {
            val cs = styles[cell.el] ?: leaf.style
            val cellContentW = (cell.width - (cs.border.horizontal + cs.padding.horizontal).roundToInt()).coerceAtLeast(1)
            // **Q15：逐块塑形**（格内 `<p>`/`<div>`…各自成块；块的可用宽扣掉逐层容器内缩
            // `TableCellBlock.edgeH`，格自身横向边已由 cellContentW 扣掉）。
            //
            // 块高按 `lineBottom − lineTop` 复算（与排版侧的逐行高之和同口径、允许差一格），
            // 再经 **同一份** `stackTableCellBlocks` 复位 [TableCellBlock.top] —— 该函数只吃静态
            // `gapBefore`，故两侧的块顶逐值一致，行高预算与实际绘制不错位。
            for (b in cell.blocks) {
                val bw = (cellContentW - b.edgeH).coerceAtLeast(1)
                // **Q15**：匿名块的 `el` 是整个容器，本块只占其中一段 ⇒ 块判据并上
                // `TableCellBlock.outsideSiblings`（run 外的原始兄弟），塑形层重抽出来的文本
                // 才与 [TableCellBlock.text] 逐字相同（否则 `<td>前<br>中</td>` 的 `<br>` 硬换行
                // 会被 `white-space:normal` 折成空格，行数从 5 掉到 3）。命名块沿用调用方的
                // classify（重路径整章表、轻路径 `lightClassify()`），与排版侧同口径。
                val bCls = if (b.outsideSiblings.isEmpty()) (classify ?: BLOCK_TAG_CLASSIFY)
                else BlockClassify { it in b.outsideSiblings || (classify ?: BLOCK_TAG_CLASSIFY).isBlock(it) }
                val sh = shapeGeometry(b.el, b.style, styles, profile, bw, imageLoader = imageLoader, chapterHref = chapterHref, ancestorStyleOf = ancestorStyleOf, genOf = genOf, classify = bCls, hidden = hidden, isBlock = { bCls.isBlock(it) }, firstLetterOf = firstLetterOf)
                b.shape = sh
                val contentH = if (sh.lineCount > 0) (sh.lineBottom(sh.lineCount - 1) - sh.lineTop(0)) else 0
                b.height = contentH + b.edgeV
            }
            val cellH = (orilumn.reader.engine.laying.stackTableCellBlocks(cell.blocks).coerceAtLeast(1) +
                (cs.padding.vertical + cs.border.vertical).roundToInt()).coerceAtLeast(1)
            cell.height = cellH
            if (cell.rowSpan <= 1) rowH = maxOf(rowH, cellH)
        }
        return rowH
    }

    /**
     * Shapes one leaf into a [ParagraphShapeRef]. A table-row leaf fills its cells' shapes (each shaped
     * within its column width) and returns a synthetic single-line shape of the row's height; every other
     * leaf falls through to skia shapeGeometry.
     */
    private fun shapeLeaf(
        leaf: LayoutBox,
        styles: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        profile: TypographicProfile,
        listMarker: ListMarkers.ListMarker? = null,
        ancestorStyleOf: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? = null,
        /** P3-c 生成内容查找（空即无；调用方喂与塑形同一 phase-1 结果）。 */
        genOf: orilumn.reader.engine.laying.GenOf = orilumn.reader.engine.laying.EmptyGen,
        /** P4-a3 悬浮环绕前导（null = 无环绕旧路径；temp 路径经 `shapeBlock` 同源）。 */
        floatLead: orilumn.reader.engine.laying.FloatLead? = null,
        /** P6-a2 文本悬浮断行宽（null = 常规 border-box 宽）。 */
        breakWidthOverride: Int? = null,
        /** 行内图行高配对用分类/隐藏判定（与盒流同口径；塑形层透传）。 */
        classify: orilumn.reader.engine.laying.BlockClassify? = null,
        hidden: orilumn.reader.engine.laying.HiddenCheck = orilumn.reader.engine.laying.HIDDEN_NONE,
        /** P3：盒上写好的分页高度预算（重/轻两路都在盒上，塑形只读）。 */
        replaceableFitH: Int? = null,
        /** `::first-letter` 伪元素查找（null = 章节无该伪规则 / 未命中）。 */
        firstLetterOf: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? = null,
    ): ParagraphShapeRef {
        val t = leaf.table
        if (t != null) {
            val rowH = fillTableRowCells(t = t, leaf = leaf, styles = styles, profile = profile, ancestorStyleOf = ancestorStyleOf, genOf = genOf, classify = classify, hidden = hidden, firstLetterOf = firstLetterOf)
            return ShapedGeometry(isReplaceable = true, replaceableBottom = rowH.coerceAtLeast(1), replaceableCharEnd = leaf.textLength.coerceAtLeast(1))
        }
        return shapeGeometry(
            leaf.el ?: orilumn.reader.engine.html.MarkupElement("body"), leaf.style, styles,
            profile, breakWidthOverride ?: breakWidthPx(leaf), listMarker,
            imageLoader = imageLoader, chapterHref = chapterHref, ancestorStyleOf = ancestorStyleOf, genOf = genOf,
            floatLead = floatLead, classify = classify, hidden = hidden, isBlock = { it.tag in BLOCK_TAGS },
            replaceableFitH = replaceableFitH, firstLetterOf = firstLetterOf,
        )
    }

    /**
     * Resolves the list marker for [leafEl] when it is the **first leaf under its `<li>`** — [carriers]
     * is [ListMarkers.firstCarrierSet] of the current leaf order, so `<li><p>a</p><p>b</p></li>` draws
     * one bullet (on `a`), never one per paragraph. Uses [styleOf] — heavy supplies the whole-chapter
     * style map, light its lazy resolver — so both paths read the `ul`/`ol`'s `list-style-*` and
     * horizontal `padding/margin` consistently. 单源委托 common [ListMarkers.markerForLeaf]（P2）. */
    private fun listMarkerFor(
        leafEl: MarkupElement?,
        styleOf: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
        carriers: Set<MarkupElement>,
    ): ListMarkers.ListMarker? =
        ListMarkers.markerForLeaf(leafEl, carriers, styleOf)

    /** [ListMarkers.firstCarrierSet] for a box leaf list (heavy path; the light path uses
     *  [LightPrepare.firstCarrierLeaves] over the same document-ordered markup leaves). */
    private fun firstCarrierLeaves(leaves: List<LayoutBox>): Set<MarkupElement> =
        ListMarkers.firstCarrierSet(leaves.mapNotNull { it.el })

    /** Shapes block [i], reusing a previously-shaped result from [cache] (may be null). Keyed by leaf
     *  index so the same block is shaped at most once per layout-parameter cycle. */
    /**
     * R3: [l2] is an immutable published neighbor-shape snapshot (controller window prefill):
     * consulted between the live [cache] and fresh shaping, with the same per-instance table-cell
     * refill as a cache hit. Read-only — never written through (callers may run off the flip lock).
     */
    internal fun tempShape(
        cache: MutableMap<Int, ParagraphShapeRef>?,
        prepare: LightPrepare,
        i: Int,
        profile: TypographicProfile,
        l2: Map<Int, ParagraphShapeRef>? = null,
    ): ParagraphShapeRef {
        // P3：塑形**之前**定这一块的高度预算（口径乙）；命中缓存也不能跳过——盒上的预算必须在
        // 任何一次塑形之前就位，否则塑形读到的 used 高与后续预算不一致（量画漂移）。
        prepare.applyReplacedFits(i, i + 1)
        val hit = cache?.get(i) ?: l2?.get(i)
        if (hit != null) {
            // 表格行命中塑形缓存也必须回填当前实例的格 shape：格 shape 只活在叶实例上
            // （[fillTableRowCells]），而 `prepareLight` 每次重建都产出新叶实例；只复用行
            // shape 会让展开阶段拿到全空 shape（[TableCellLines.emitCell] 静默整表丢弃）。
            val leaf = prepare.block(i)
            val t = leaf.table
            if (t != null) {
                fillTableRowCells(t = t, leaf = leaf, styles = prepare.inlineStyles(i), profile = profile, ancestorStyleOf = prepare::resolveStyle, genOf = prepare.genOf, classify = prepare.lightClassify(), hidden = prepare.hidden, firstLetterOf = prepare.firstLetterOf)
            }
            return hit
        }
        val s = shapeBlock(prepare, i, prepare.block(i), profile)
        cache?.put(i, s)
        return s
    }

    /**
     * Assembles a [TempPage] from a contiguous whole-block range + its (already shaped) shapes.
     * @param pageFirstLine the page's first line within the built (block-local) line stream; 0 for
     *   whole-block pages, >0 for the line-anchored anchor page.
     * @param endLineCharStart when non-null, the page's last line is the line starting at this char
     *   offset (trimming a shared anchor block); when null the page spans the full block range.
     */
    private fun assembleTempPage(
        prepare: LightPrepare,
        blockStart: Int,
        blockEndExclusive: Int,
        shapes: List<ParagraphShapeRef>,
        pageFirstLine: Int,
        endLineCharStart: Int?,
        contentH: Int,
        /** P6-a2 R6: 前视塑形（carry-in 状态预热；null = 无前视）。 */
        shapeLookback: ((Int) -> ParagraphShapeRef)? = null,
    ): TempPage {
        // 入口契约（fail-fast）：调用方三处（anchor-forward/temp-forward/temp-backward）传的
        // shapes 与块区间必须对齐——错位会拼出残次页。以往静默拼，现在出生即炸。
        require(blockStart in 0..blockEndExclusive) { "assembleTempPage inverted range [$blockStart,$blockEndExclusive)" }
        require(blockEndExclusive <= prepare.totalBlocks) { "assembleTempPage range over total [$blockStart,$blockEndExclusive) total=${prepare.totalBlocks}" }
        require(shapes.size == blockEndExclusive - blockStart) { "assembleTempPage shapes/blocks misaligned shapes=${shapes.size} range=[$blockStart,$blockEndExclusive)" }
        require(contentH > 0) { "assembleTempPage contentH=$contentH" }
        val total = prepare.totalBlocks
        val rawFlow = rebuildLocalLines(prepare, blockStart, blockEndExclusive, shapes, shapeLookback)
        val rawLines = rawFlow.lines
        // Normalize the temp page's line y coordinates to start at the page's first line = 0. This
        // removes any dependence on the blocks' absolute content offsets (a mid-chapter anchor page can
        // start far down the chapter), so the page always renders top-aligned regardless of those offsets.
        val base = rawLines.getOrNull(pageFirstLine.coerceAtLeast(0))?.yTop ?: 0
        val lines = if (base == 0) rawLines else rawLines.map {
            FlowedLine(it.charStart, it.charEnd, it.yTop - base, it.yBottom - base, it.paragraphStart)
        }
        // 无行叶的 border-box 顶记账与行同坐标系 ⇒ 同样要归一化（占位值不参与减法，防下溢）。
        val lineLessBoxTop = rawFlow.lineLessBoxTop?.let { a ->
            if (base == 0) a else IntArray(a.size) {
                if (a[it] == LocalLineFlow.NO_LINE_LESS_TOP) a[it] else a[it] - base
            }
        }
        val leavesForDraw = prepare.blocks(blockStart, blockEndExclusive)
        val (localFirst, localLast) = localLineRanges(shapes)
        // E11：同增量路径 —— **先**聚合窗口画盒（含裁剪几何），行/图/表格格的裁剪同源于它。
        val boxes = buildBackgroundDrawBoxes(prepare, leavesForDraw, lines, localFirst, localLast, lineLessBoxTop)
        val clipOf: (MarkupElement) -> ClipRect? = { boxes.clip[it] }
        // Q1-b：临时页同样投影 skia 窗口（页内局部行序，行 y 已归一化到页首行 = 0）——
        // ReaderScreen 合流绘制，不再回落旧 StaticLayout 画法。
        val skiaLines = buildPartialSkiaWindow(prepare, leavesForDraw, localFirst, shapes, lines, prepare.profile.letterSpacingEm, prepare.profile.cjkLatinSpacingEmApplied, prepare.profile.fgColor, clipOf, deviceScale = prepare.profile.density)
        // 表格行展开进窗口（与 canonical 同源 helper；行顶/行首取本窗 FlowedLine，char 章内；
        // 相邻同表行成组，rowspan 格边框跨行）。
        val tempFrames = ArrayList<orilumn.reader.engine.skia.TableCellLines.RowFrame>()
        for (i in leavesForDraw.indices) {
            val leaf = leavesForDraw[i]
            val t = leaf.table ?: continue
            val li = localFirst.getOrElse(i) { -1 }
            if (li < 0 || li >= lines.size) continue
            val fl = lines[li]
            val rowH = maxOf(leaf.replaceableHeight, shapes.getOrNull(i)?.replaceableBottom ?: 0).coerceAtLeast(1)
            tempFrames.add(
                orilumn.reader.engine.skia.TableCellLines.RowFrame(
                    t, li, fl.yTop, rowH, fl.charStart,
                    leaf.style, orilumn.reader.engine.skia.TableCellLines.tableAncestorOf(leaf.el),
                    // E11：格内容/格边框的裁剪取本行叶内容的裁剪（含 `<table>` 及其祖先）。
                    contentClipOf(leaf.el, clipOf),
                    // E12：本行的累积相对定位偏移（与重路径同源：正典叶盒预处理值）。
                    leaf.relOffsetX, leaf.relOffsetY,
                ),
            )
        }
        val tempWin = orilumn.reader.engine.skia.TableCellLines.expandTable(
            tempFrames, prepare::resolveStyle, prepare.profile.letterSpacingEm, prepare.profile.fgColor,
            imageLoader, chapterHref, prepare.profile.cjkLatinSpacingEmApplied, prepare.profile.density,
        )
        val tempEdgeGaps = windowLeadTop(prepare, blockStart, shapes)
        val layout = PartialDrawableLayout(
            lines = lines,
            leafList = leavesForDraw,
            shapeList = shapes,
            localFirst = localFirst,
            // 必须显式传 `localLast`：它是 `localFirst` 的默认值，漏传会让**每个叶的行区间都是空**
            // （`localLast[i] == localFirst[i]`），于是本窗口的 `BreakClusters.plan` 恒为「无簇、无
            // 强制行」——临时页/增量窗的断页规则静默失效（P4 曾如此）。
            localLast = localLast,
            boxes = boxes.boxes,
            loader = imageLoader,
            href = chapterHref,
            // P4d：临时页的绘制布局也带上 `break-inside: avoid` 属主图 —— 临时/磁盘两条路径的
            // `avoidSplitRanges` / `forbiddenCutLines` 因此同源（临时页填充另经
            // `Paginator.resolveAvoidCut` 走同一条判据）。
            avoidOwnerMap = prepare.avoidOwnerMap,
            skiaLinesSource = skiaLines,
            tableCellsSource = tempWin.lines,
            tableCellImagesSource = tempWin.images,
            tableBordersSource = tempWin.borders,
            frameLeadTop = tempEdgeGaps,
            elementStyleSource = prepare::resolveStyle,
            clipSource = clipOf,
            density = prepare.profile.density,
        )
        var lastLine = if (lines.isEmpty()) 0 else lines.size
        var charEnd = if (blockEndExclusive < total) prepare.globalCharStarts[blockEndExclusive].toInt()
            else prepare.totalChars
        var sliceEndBlock = blockEndExclusive
        if (endLineCharStart != null) {
            // 页尾被**钉死**在某个字符行（锚页 / 锚前页：读者锚点决定，不是填充判据）——
            // 只做「不超页高」回退（`lastLine` 只会变小）。
            val cut = rawLines.indexOfFirst { it.charStart == endLineCharStart }
            lastLine = if (cut >= 0) cut else lines.size
            charEnd = endLineCharStart
            // 帧几何（含页首/页尾块的 border/padding 与页首/页尾元素的 margin）而不是行顶/行底：
            // 否则绘制侧按盒顶对齐时末行越出页高被裁（本页按 `endLineCharStart` 钉死页尾，只有
            // 「不超页高」这一条回退可以救它）。与 `Paginator.fillWholeLines` 同判据。
            val frameTop = layout.getPageFrameTop(pageFirstLine.coerceAtLeast(0))
            while (lastLine > pageFirstLine + 1 && lastLine - 1 in lines.indices &&
                layout.getPageFrameBottom(lastLine - 1) - frameTop > contentH
            ) {
                lastLine--
            }
        } else {
            // **单源**页尾（临时表与磁盘表/增量窗的同一段代码）：`Paginator.paginateFrom` =
            // 整行填充 `fillWholeLines` + 强制段 + avoid 簇。临时页**不再**自算填充/簇高
            // （旧版两份实现会漂移：`pre` 整块后移、目录乱分页、半页空白）。
            val startLine = pageFirstLine.coerceAtLeast(0)
            val p = if (startLine < layout.lineCount) {
                Paginator.paginateFrom(layout, startLine, contentH).firstOrNull()
            } else null
            if (p != null) {
                lastLine = p.lastLineExclusive
                // 调用方会把窗口取整到 avoid 簇闭合（切点可能落在窗口内部），故块区间也收到切点
                // 所在块 —— 与本页真正画出的行一致。
                for (i in localFirst.indices) {
                    if (lastLine - 1 in localFirst[i] until localLast[i]) { sliceEndBlock = blockStart + i + 1; break }
                }
            }
        }
        // 裁切兜底（**恒开**，不受 `EngineDiag` 门控）：绘制侧把整页从版心顶对齐（本页首行 y=0），
        // 末行越过版心高就被窗口裁掉 —— 这正是「最后一行被推出栅格」的真根因。`Paginator.paginateFrom`
        // 已用同一帧几何做判据（`fillWholeLines` 的注释），此处是**兜底**：任何让帧高与内容高失配的
        // 改动（窗口边缘缺口算错、叶自身边重复计入、新增容器盒…）都在这里被拦下并**出声**，而不是
        // 无声裁切。`Logger.w` 是恒开通道（日志落盘见 AGENTS.md），只在本应不可能的分支触发。
        val guardFirst = pageFirstLine.coerceAtLeast(0)
        if (lastLine - 1 in lines.indices && lastLine > guardFirst) {
            val frameTop = layout.getPageFrameTop(guardFirst)
            var retreated = 0
            while (lastLine > guardFirst + 1 &&
                layout.getPageFrameBottom(lastLine - 1) - frameTop > contentH
            ) {
                lastLine--
                retreated++
            }
            if (retreated > 0) {
                Logger.w(
                    "Orilumn.TEMP",
                    "CLIP-GUARD href=$chapterHref blk=[$blockStart,$blockEndExclusive) " +
                        "retreated=$retreated lines -> [$guardFirst,$lastLine) " +
                        "frame=${layout.getPageFrameBottom(lastLine - 1) - frameTop} contentH=$contentH " +
                        "（末行本会越出栅格被裁：帧几何与版心高失配）",
                )
                for (i in localFirst.indices) {
                    if (lastLine - 1 in localFirst[i] until localLast[i]) { sliceEndBlock = blockStart + i + 1; break }
                }
            }
        }
        if (lastLine - 1 in lines.indices) {
            // 页尾字符边界与 canonical `Paginator`/`fillWholeLines` 同口径：取**末行之后那一行的行首**
            // （canonical 是 `charEnd = getLineStart(cut)`）。若用「末行自身的 charEnd」，因样式化归一
            // 文本的行流满足 `lineEnd(i) == lineStart(i+1) - 1`（见 `rebuildLocalLines`），相邻两页就会
            // 在边界处各漏 1 个字符（真机表现为「翻页/调参后少字」）；窗口末行（其下一行不在窗内）才
            // 退到块边界/章末。真书 ch2 的 11 处漏字全落在 `<pre>` 代码块**内部**（`white-space: pre`
            // 保留换行 ⇒ 每行末都比下一行首少 1 字符），正是用户报的 §2.1.2 代码块现象。
            charEnd = when {
                endLineCharStart != null -> if (lastLine in lines.indices) lines[lastLine].charStart else endLineCharStart
                lastLine in lines.indices -> lines[lastLine].charStart
                blockEndExclusive < total -> prepare.globalCharStarts[blockEndExclusive].toInt()
                else -> prepare.totalChars
            }
        }
        val charStart = if (pageFirstLine in 0 until rawLines.size) rawLines[pageFirstLine].charStart
            else prepare.globalCharStarts[blockStart].toInt()
        val slice = PageSlice(
            charStart = charStart,
            charEnd = charEnd.coerceAtLeast(charStart),
            firstLine = pageFirstLine.coerceAtLeast(0),
            lastLineExclusive = lastLine.coerceAtLeast(pageFirstLine + 1),
            kind = PageSlice.Kind.TEXT,
            blockStart = blockStart,
            blockEndExclusive = sliceEndBlock,
        )
        return TempPage(slice, layout)
    }

    // ─────────────────────────────────────────────────────────────────
    // Legacy one-shot (delegates to prepare + fullLayout)
    // ─────────────────────────────────────────────────────────────────

    override fun layout(
        markup: MarkupElement,
        cssBundle: CssBundle?,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
    ): ChapterLayouter.ChapterLayoutProduct? = runCatching {
        val prepare = prepare(markup, cssBundle, profile, contentW, contentH)
        fullLayout(prepare, profile, contentW, contentH)
    }.onFailure {
        // 内部异常归因：记 e 后重抛（fail-fast）——调用方原先见 null 走 bindSafeEmpty
        // 伪装空章节；现同一结局（buildLayout catch 记 ERROR + 安全空），
        // 但栈归因到源头。行为结局不变，哑巴路点亮。
        Logger.e("Orilumn.Engine", "BoxChapterLayouter.layout FAIL ${it.message}")
        throw it
    }.getOrNull()

    // ─────────────────────────────────────────────────────────────────
    // Helpers (unchanged from the pre-split version)
    // ─────────────────────────────────────────────────────────────────

    /** Debug: reconcile a pagination point with the content-area capacity — logs the decided line range,
     *  char range and the page's actual vertical extent vs [contentH] (OVER = overflow/clipped bottom,
     *  UNDER = under-filled). */
    private fun diagPage(contentH: Int, page: TempPage, label: String) {
        if (!EngineDiag.enabled) return
        val s = page.slice
        val drawnBottom = page.layout.getLineBottom(s.lastLineExclusive - 1) - page.layout.getLineTop(s.firstLine)
        val diff = drawnBottom - contentH
        Logger.d("Orilumn.Engine",
            "PG [$label] lines=[${s.firstLine},${s.lastLineExclusive}) n=${(s.lastLineExclusive - s.firstLine).coerceAtLeast(0)} " +
                "chars=${s.charStart}..${s.charEnd} extent=$drawnBottom contentH=$contentH " +
                (if (diff > 0) "OVER+$diff" else if (diff < -40) "UNDER$diff" else "OK"))
    }

    /**
     * Temp-page seam diagnostic: dumps how each temp page was built and the exact char/block span it
     * covers, so two consecutive pages whose spans do not tile (`prevEnd != nextStart`) can be traced
     * to the builder call that produced the offending start. Tag = `Orilumn.TEMP`.
     */
    private fun tempDiag(kind: String, inDesc: String, page: TempPage, extra: String) {
        if (!EngineDiag.enabled) return
        val s = page.slice
        Logger.w("Orilumn.TEMP",
            "BUILD $kind $inDesc -> char[${s.charStart},${s.charEnd}) blk=[${s.blockStart},${s.blockEndExclusive}) " +
                "line[${s.firstLine},${s.lastLineExclusive}) $extra")
    }

    /**
     * 表格行高「跨行分摊」后处理（重/轻两路单源）：[shapeLeaf] 只给出每行「rowspan==1」格的自身高，
     * 跨行格需按 [TableGridModel.resolveRowHeights] 把超出的部分**按比例分摊**到它跨越的每一行；
     * 否则跨行格整体压进首行（浏览器里不会，表现为首行虚高、`rowspan==1` 格下方大片留白）。
     *
     * 按「同表连续行叶」分组重算并抬高各行形状的 `replaceableBottom`（只增不减，故与重路径已解析
     * 的 `leaf.replaceableHeight` 幂等）。分组不完整（表被窗口/分页截断）时退化为旧的「行内所有格
     * 的最大值」，宁可偏高也不截断跨行格。
     */
    private fun applyTableRowspanHeights(leaves: List<LayoutBox>, shapes: List<ParagraphShapeRef>) {
        var i = 0
        while (i < leaves.size) {
            if (leaves[i].table == null) { i++; continue }
            val tableEl = orilumn.reader.engine.skia.TableCellLines.tableAncestorOf(leaves[i].el)
            if (tableEl == null) { i++; continue }
            var j = i
            while (j + 1 < leaves.size && leaves[j + 1].table != null &&
                orilumn.reader.engine.skia.TableCellLines.tableAncestorOf(leaves[j + 1].el) === tableEl
            ) j++
            val rows = (i..j).map { leaves[it].table!! }
            val model = TableGridModel.build(tableEl)
            val complete = model.rows.size == rows.size &&
                model.rows.withIndex().all { (k, r) -> r.el === leaves[i + k].el }
            val resolved = if (complete) {
                TableGridModel.resolveRowHeights(rows.map { r -> r.cells.map { TableGridModel.CellHeight(it.rowSpan, it.height) } })
            } else {
                IntArray(rows.size) { k -> rows[k].cells.maxOfOrNull { it.height } ?: 1 }
            }
            for (k in rows.indices) {
                val s = shapes.getOrNull(i + k) ?: continue
                if (resolved[k] > s.replaceableBottom) s.replaceableBottom = resolved[k]
            }
            i = j + 1
        }
    }

    /**
     * Rebuilds the whole-chapter line stream from each leaf's real [ParagraphShapeRef], preserving the
     * box tree's structural inter-block margins (from [structure], height-independent) but laying the
     * lines with the shape's exact per-line heights. Afterwards the box geometry and painting agree
     * exactly: getLineTop(k) == the shape line's top at its global position.
     */
    private fun rebuildLinesFromShapes(
        structure: BoxLayoutResult,
        leaves: List<LayoutBox>,
        shapes: List<ParagraphShapeRef>,
    ): List<FlowedLine> {
        applyTableRowspanHeights(leaves, shapes)
        val out = ArrayList<FlowedLine>()
        var runningChar = 0
        var gIndex = 0
        for (i in leaves.indices) {
            val leaf = leaves[i]
            val shape = shapes[i]
            // P4-a3: 悬浮 img 重建零高行（与 emit/轻路径同式；canonical 分页不计悬浮高，
            // 环绕文本填充；shape 行数 1 ↔ 重建行数 1 的对应恒成立）。
            if (leaf.table == null && shape.isReplaceable && shape.replaceableBottom > 0 &&
                leaf.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE
            ) {
                val topEdges = (leaf.style.border.top + leaf.style.padding.top).roundToInt()
                val y = leaf.contentTop.coerceAtLeast(0) + topEdges
                leaf.firstLineIndex = gIndex
                out.add(
                    FlowedLine(
                        charStart = runningChar,
                        charEnd = runningChar + leaf.textLength,
                        yTop = y,
                        yBottom = y,
                        paragraphStart = true,
                    ),
                )
                leaf.lastLineExclusive = gIndex + 1
                gIndex += 1
                runningChar += leaf.textLength
                continue
            }
            // First line sits at the leaf's border-box top (contentTop, from emit) plus its OWN top
            // border/padding — mirroring emit()'s `contentY = contentTop + topEdges`. Anchoring to emit's
            // absolute contentTop (rather than a running reconstruction) keeps these rebuilt line
            // positions byte-identical to emit for every leaf: plain paragraphs are unchanged, and only
            // self-padded blocks (pre/.filename) get their real top padding inset. This is the shared
            // fix for the flush-first-line bug present on BOTH the heavy and light render paths.
            val topEdges = (leaf.style.border.top + leaf.style.padding.top).roundToInt()
            var y = leaf.contentTop.coerceAtLeast(0) + topEdges
            leaf.firstLineIndex = gIndex
            val shapeStart = if (shape.lineCount > 0) shape.lineStart(0) else 0
            val charBase = runningChar
            for (k in 0 until shape.lineCount) {
                // Every shaped line is the CSS line box (lineHeightRatio x font size), reported by the
                // skia breaker, so per-line height is uniform across
                // first/interior/last and phase-consistent with drawing and pagination.
                val h = (shape.lineBottom(k) - shape.lineTop(k)).coerceAtLeast(1)
                out.add(
                    FlowedLine(
                        charStart = charBase + (shape.lineStart(k) - shapeStart),
                        charEnd = charBase + (shape.lineEnd(k) - shapeStart),
                        yTop = y,
                        yBottom = y + h,
                        paragraphStart = k == 0,
                    ),
                )
                y += h
            }
            leaf.lastLineExclusive = gIndex + shape.lineCount
            gIndex += shape.lineCount
            runningChar += leaf.textLength
        }
        for (root in structure.boxes) recomputeContainerSpans(root)
        return out
    }

    /** Re-derives a container's line span from its children's updated (rebuilt) spans, bottom-up. */
    private fun recomputeContainerSpans(box: LayoutBox) {
        if (!box.isContainer) return
        for (child in box.childBoxes) recomputeContainerSpans(child)
        var min = Int.MAX_VALUE
        var max = -1
        for (child in box.childBoxes) {
            if (child.firstLineIndex >= 0) {
                min = minOf(min, child.firstLineIndex)
                max = maxOf(max, child.lastLineExclusive)
            }
        }
        box.firstLineIndex = if (min == Int.MAX_VALUE) -1 else min
        box.lastLineExclusive = if (max < 0) -1 else max
    }

    /** Recovers the line-breaking width of a leaf (its border-box width minus its horizontal edges).
     *  Single-sourced through [NormalFlowLayout.innerBreakWidth], the canonical formula [buildBoxTree]
     *  uses, so the light shaper and the canonical box layout break lines identically. */
    private fun breakWidthPx(leaf: LayoutBox): Int =
        NormalFlowLayout.innerBreakWidth(leaf.style, leaf.contentWidth)

    /**
     * The reader-app (UI) layer stylesheet — highest global reader layer (fixed tier; custom styles sit above it). It enforces
     * the reader's 行距 (line-height) and 段间距 (paragraph spacing) so they always win over the book,
     * without mutating computed styles. 单源在 common（[ReaderUiSheet], Z4），与桌面同规则生成.
     */
    /** C2-P2b-4: public probe hook (was `internal`; `:app` probe tests live across the module seam). */
    fun uiSheetFromProfile(profile: TypographicProfile): StyleSheet =
        ReaderUiSheet.build(profile)

    private fun collectLeaves(boxes: List<LayoutBox>): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        for (b in boxes) if (b.isContainer) out.addAll(collectLeaves(b.childBoxes)) else out.add(b)
        return out
    }

    /**
     * Builds the background/border boxes for an incremental page window, so block-level container
     * backgrounds (e.g. `blockquote`) survive the light path — which otherwise composites only leaf
     * text and would drop the container's fill. Mirrors the full path's box-tree backgrounds: every
     * window leaf is attributed to its nearest background-bearing box (itself for `pre`, or an
     * ancestor container), and one box spans that owner's leaves' real vertical extent. So backgrounds
     * stay correct within the shaped window with no extra shaping.
     *
     * 内核层：背景归属只看背景色/背景图；只带边框的叶（如 `blockquote > h2` 的下边框）另出
     * 叶边框盒，只带边框的容器（如只有 `border-bottom` 的 `table`）另出容器边框盒
     * （聚合其窗内后代叶范围，首子链下沉同式；已有背景盒的自身边框由背景盒画，不重复）。
     * 边框盒均无背景填充，只画自身边框，且一律排在背景盒之后绘制——与重路径“先祖先背景、
     * 后子孙边框”同序，否则容器底会盖掉标题下边框。
     */
    internal fun buildBackgroundDrawBoxes(
        prepare: LightPrepare,
        leafList: List<LayoutBox>,
        lines: List<FlowedLine>,
        firstByBlock: IntArray,
        lastByBlock: IntArray,
        /**
         * E7：无行叶（零字符、只有边框/背景的块，如 UA `<hr>`）的 border-box 顶 ——
         * [LocalLineFlow.lineLessBoxTop]，下标与 [leafList] 一一对应。null/占位 = 旧口径
         * （该叶有行，或有行但窗口半截）。画盒侧没有它就只能整块跳过（旧行为：行流里没锚行）。
         */
        lineLessBoxTop: IntArray? = null,
    ): LightDrawBoxes {
        if (leafList.isEmpty()) return LightDrawBoxes(emptyList(), emptyMap())
        // 叶 [i] 的 border-box 上下沿 + 行锚。有行叶 = 行流首/末行 ± 自身边距（旧口径逐值不变）；
        // **无行叶**（`hi <= lo`）没有任何锚行：用行流记账的 border-box 顶 + 自身上下边距造一个零宽锚
        // （`lo == hi`，与重路径 `emit` 的 `firstLineIndex == lastLineExclusive` 同式 ⇒ 页面归属门
        // 两路同判）。null = 本叶无可画区间（窗口半截 / 有行但落在窗外 / 无行且无记账）。
        fun extentOf(i: Int): LeafBand? {
            val leaf = leafList[i]
            val lo0 = firstByBlock[i].coerceAtLeast(0)
            val hi0 = lastByBlock[i]
            val s = leaf.style
            val topEdges = (s.border.top + s.padding.top).roundToInt()
            val bottomEdges = (s.border.bottom + s.padding.bottom).roundToInt()
            val recorded = if (hi0 <= lo0) lineLessBoxTop?.getOrNull(i) else null
            if (recorded != null && recorded != LocalLineFlow.NO_LINE_LESS_TOP) {
                val anchor = lo0.coerceAtMost(lines.size)
                // E23 指定高：无行叶（空块）的盒高 = max(0, `height`) + 自身上下边距。
                return LeafBand(
                    recorded,
                    recorded + topEdges +
                        NormalFlowLayout.specifiedContentHeight(s, 0) + bottomEdges,
                    anchor,
                    anchor,
                )
            }
            if (lo0 >= lines.size || hi0 <= lo0) return null
            // §10.6.7（E11）：**替换型浮动叶**（图）在行流里只有零高行 —— 真实下沿按替换高还原，
            // 否则裁剪盒聚合不到它（同章在轻路径上会把整张图齐根切掉）。文本浮动叶的行是真的，无需还原。
            val contentTop = lines[lo0].yTop
            val bottom = if (s.floatSide != orilumn.reader.engine.css.FloatSide.NONE && leaf.replaceableHeight > 0) {
                contentTop + leaf.replaceableHeight + bottomEdges
            } else {
                // E23 指定高：内容盒高 = max(行底 − 内容顶, `height`)，与 `emit` 逐值同式。
                contentTop +
                    NormalFlowLayout.specifiedContentHeight(
                        s,
                        lines[minOf(hi0, lines.size) - 1].yBottom - contentTop,
                    ) + bottomEdges
            }
            return LeafBand(
                top = lines[lo0].yTop - topEdges,
                bottom = bottom,
                lo = lo0,
                hi = hi0,
            )
        }
        // A "self-owned" owner is the background bearing LEAF itself (a `pre`/`p.filename` with its own
        // background): its aggregated extent already counts its own top+bottom edges once, so the band
        // must NOT be extended by them again. Container owners (blockquote/.rust-example-rendered) are
        // not self-owned and DO extend by their own edges (their lines span only the content area).
        //
        // 浮动叶的**底外边距**另算进「容器内容底」：CSS 2.1 §10.6.7 规定 BFC 根的高度包含浮动盒
        // 的 margin box，且浮动盒外边距不与父底边折叠。少了这 2dp，clearfix 容器的底 padding
        // 看起来比顶部薄一截（与 Chromium 逐值对照量出）。
        fun bandBottomOf(i: Int, band: LeafBand, includeFloatMargin: Boolean = true): Int {
            val s = leafList[i].style
            return band.bottom + if (includeFloatMargin && s.floatSide != FloatSide.NONE) {
                s.margin.bottom.roundToInt()
            } else 0
        }
        val aggTop = HashMap<MarkupElement, Int>()
        val ownerBottom = HashMap<MarkupElement, Int>()
        val ownerFirst = HashMap<MarkupElement, Int>()
        val ownerLast = HashMap<MarkupElement, Int>()
        val ownerLeaf = HashMap<MarkupElement, MarkupElement>()
        val selfOwned = HashSet<MarkupElement>()
        for (i in leafList.indices) {
            val leaf = leafList[i]
            val band = extentOf(i) ?: continue
            val el = leaf.el ?: continue
            val owner = prepare.backgroundOwnerMap[el] ?: continue
            if (owner === el) selfOwned.add(owner)
            val bottom = bandBottomOf(i, band, includeFloatMargin = owner !== el)
            val prevTop = aggTop[owner]
            if (prevTop == null || band.top < prevTop) {
                aggTop[owner] = band.top
                ownerLeaf[owner] = el
            }
            ownerBottom[owner] = maxOf(ownerBottom[owner] ?: bottom, bottom)
            ownerFirst[owner] = minOf(ownerFirst[owner] ?: band.lo, band.lo)
            ownerLast[owner] = maxOf(ownerLast[owner] ?: band.hi, band.hi)
        }
        val out = ArrayList<LayoutBox>(aggTop.size)
        // E11：`overflow` 祖先裁剪几何 —— **同一份窗口聚合口径**（同一 `extentOf`、同一
        // `backgroundBandExtent` + 首子链下沉），故轻路径的裁剪区与重路径 `applyInheritedClips`
        // 同值，临时/增量页不会与正典页在裁剪边界上横跳。窗口叶沿**自身＋祖先链**上溯，凡
        // `overflow` 非 visible 者聚合出一个裁剪盒（横向走 `ownerBackgroundBox`，纵向走同一 band 数学）。
        // 无 `overflow` 声明时本表恒空 ⇒ 全链 clip 为 null、像素零回归。
        // E23-b：容器（含 `overflow` 裁剪盒）的**指定高**在这里落一次 —— 内容底抬到
        // `max(内容顶 + height, 聚合内容底)`，与重路径 `emit` 容器分支、与视口页的盒几何同式。
        // 自属叶（背景/裁剪属主就是叶自身）的指定高已在 [extentOf] 里消费，不走这里。
        val clipTop = HashMap<MarkupElement, Int>()
        val clipBottom = HashMap<MarkupElement, Int>()
        val clipLeaf = HashMap<MarkupElement, MarkupElement>()
        val clipSelfOwned = HashSet<MarkupElement>()
        for (i in leafList.indices) {
            val band = extentOf(i) ?: continue
            val el = leafList[i].el ?: continue
            var a: MarkupElement? = el
            while (a != null) {
                if (!a.isText && prepare.resolveStyle(a).clipsOverflow()) {
                    if (a === el) clipSelfOwned.add(a)
                    val prev = clipTop[a]
                    if (prev == null || band.top < prev) {
                        clipTop[a] = band.top
                        clipLeaf[a] = el
                    }
                    val b = bandBottomOf(i, band, includeFloatMargin = a !== el)
                    clipBottom[a] = maxOf(clipBottom[a] ?: b, b)
                }
                a = a.parent
            }
        }
        val clip = HashMap<MarkupElement, ClipRect>(clipTop.size)
        // E12：`position: relative` 的累积绘制偏移 —— 轻路径的聚合盒是**扁平**的（没有盒链可累加），
        // 故按**元素链**现算（与重路径 `applyRelativeOffsets` 同源同值），并随盒一起平移裁剪区。
        val offsetOf: (MarkupElement?) -> BoxOffset = { relativeOffsetOf(it, prepare::resolveStyle) }
        for ((owner, top) in clipTop) {
            val box = ownerBackgroundBox(owner, prepare)
            val off = offsetOf(owner)
            box.relOffsetX = off.x
            box.relOffsetY = off.y
            val s = box.style
            val descent = clipLeaf[owner]?.let {
                NormalFlowLayout.firstChildDescentTop(owner, it, prepare::resolveStyle)
            } ?: 0
            val contentTop = top - descent
            val aggBottom = clipBottom[owner] ?: top
            val (bandTop, bandBottom) = NormalFlowLayout.backgroundBandExtent(
                ownerTop = contentTop,
                ownerBottom = maxOf(aggBottom, contentTop + NormalFlowLayout.specifiedContentHeight(s, 0)),
                ownerEdgesTop = (s.border.top + s.padding.top).roundToInt(),
                ownerEdgesBottom = (s.border.bottom + s.padding.bottom).roundToInt(),
                selfOwned = owner in clipSelfOwned,
            )
            box.contentTop = bandTop
            box.contentBottom = bandBottom
            clip[owner] = box.paddingBox()
        }
        // 盒自身（底色/边框）的裁剪 = 祖先链裁剪（不含自身 padding box；内容侧另交自身）。
        for ((owner, top) in aggTop) {
            val box = ownerBackgroundBox(owner, prepare)
            val off = offsetOf(owner)
            box.relOffsetX = off.x
            box.relOffsetY = off.y
            val s = box.style
            // 首叶 margin-top 段本就画容器底：按首子链补到容器真顶（自属/窗半截即 0，保持旧行为）。
            val descent = ownerLeaf[owner]?.let {
                NormalFlowLayout.firstChildDescentTop(owner, it, prepare::resolveStyle)
            } ?: 0
            val contentTop = top - descent
            val aggBottom = ownerBottom[owner] ?: top
            val (bandTop, bandBottom) = NormalFlowLayout.backgroundBandExtent(
                ownerTop = contentTop,
                ownerBottom = maxOf(aggBottom, contentTop + NormalFlowLayout.specifiedContentHeight(s, 0)),
                ownerEdgesTop = (s.border.top + s.padding.top).roundToInt(),
                ownerEdgesBottom = (s.border.bottom + s.padding.bottom).roundToInt(),
                selfOwned = owner in selfOwned,
            )
            box.contentTop = bandTop
            box.contentBottom = bandBottom
            // Window-local line span of the owner's in-window leaves, so BoxPageRenderer's
            // page-ownership gate can exclude backgrounds that belong to another page.
            box.firstLineIndex = ownerFirst[owner] ?: -1
            box.lastLineExclusive = ownerLast[owner] ?: -1
            box.clipRect = box.inheritedClip(clip::get)
            if (box.contentBottom > box.contentTop) out.add(box)
        }
        // **无叶属主的容器**（只带边框的 `table`；以及**背景容器**装了自绘背景的子盒，
        // 典型是 clearfix `.cf{overflow:hidden;background:#f5f5f5;border:1px solid}` 只装浮动方块）：
        // 上面的背景归属只沿「叶 → 最近背景属主」上溯，浮动方块自己有背景 ⇒ 属主是方块自己，
        // 容器永远进不了 [aggTop]；重路径画每个容器盒，轻路径就会整块丢掉容器的底色与边框
        // （症状：首开/临时页不画，再开命中磁盘表才出现）。这里补一层：沿窗内叶的祖先链
        // 聚合出这些容器自己的矩形带（同一 band 数学、同一首子链下沉）。
        // 已有背景盒的祖先跳过（那个盒子已经连底色带边框一起画了）。
        val cTop = HashMap<MarkupElement, Int>()
        val cBottom = HashMap<MarkupElement, Int>()
        val cFirst = HashMap<MarkupElement, Int>()
        val cLast = HashMap<MarkupElement, Int>()
        val cLeaf = HashMap<MarkupElement, MarkupElement>()
        for (i in leafList.indices) {
            val leaf = leafList[i]
            val el = leaf.el ?: continue
            val band = extentOf(i) ?: continue
            var a = el.parent
            while (a != null && a.tag != "body") {
                val s = prepare.resolveStyle(a)
                if ((s.hasBackground() || s.hasBorderEdges()) && !aggTop.containsKey(a)) {
                    val prev = cTop[a]
                    if (prev == null || band.top < prev) {
                        cTop[a] = band.top
                        cLeaf[a] = el
                    }
                    cBottom[a] = maxOf(cBottom[a] ?: Int.MIN_VALUE, bandBottomOf(i, band))
                    cFirst[a] = minOf(cFirst[a] ?: band.lo, band.lo)
                    cLast[a] = maxOf(cLast[a] ?: band.hi, band.hi)
                }
                a = a.parent
            }
        }
        for ((container, top) in cTop) {
            val box = ownerBackgroundBox(container, prepare)
            val off = offsetOf(container)
            box.relOffsetX = off.x
            box.relOffsetY = off.y
            val s = box.style
            val descent = cLeaf[container]?.let {
                NormalFlowLayout.firstChildDescentTop(container, it, prepare::resolveStyle)
            } ?: 0
            val contentTop = top - descent
            val aggBottom = cBottom[container] ?: top
            val (bandTop, bandBottom) = NormalFlowLayout.backgroundBandExtent(
                ownerTop = contentTop,
                ownerBottom = maxOf(aggBottom, contentTop + NormalFlowLayout.specifiedContentHeight(s, 0)),
                ownerEdgesTop = (s.border.top + s.padding.top).roundToInt(),
                ownerEdgesBottom = (s.border.bottom + s.padding.bottom).roundToInt(),
                selfOwned = false,
            )
            box.contentTop = bandTop
            box.contentBottom = bandBottom
            box.firstLineIndex = cFirst[container] ?: -1
            box.lastLineExclusive = cLast[container] ?: -1
            box.clipRect = box.inheritedClip(clip::get)
            if (box.contentBottom > box.contentTop) out.add(box)
        }
        // Border-only leaves (e.g. a bordered `h2` inside a `blockquote`): their background comes
        // from the ancestor owner box above, but their own borders still need a carrier — the leaf
        // border box draws no fill (BoxDrawer skips fill-less backgrounds) and only its border edges.
        // Appended after all background boxes so the fill never overpaints these borders. Leaves that
        // already own a background box (self-owned, e.g. `pre` with its own fill) are skipped: that
        // box already draws their borders.
        for (i in leafList.indices) {
            val leaf = leafList[i]
            val el = leaf.el ?: continue
            if (!leaf.style.hasBorderEdges()) continue
            if (aggTop.containsKey(el)) continue
            val band = extentOf(i) ?: continue
            if (band.bottom <= band.top) continue
            val borderBox = LayoutBox(
                el = el, style = leaf.style, contentLeft = leaf.contentLeft, contentWidth = leaf.contentWidth,
                ranges = emptyList(), textLength = 0, lineHeights = emptyList(), childBoxes = emptyList(),
            )
            borderBox.contentTop = band.top
            borderBox.contentBottom = band.bottom
            // E12：叶的累积相对定位偏移（叶盒已由正典盒树预处理，纯增量、与坐标系无关）。
            borderBox.relOffsetX = leaf.relOffsetX
            borderBox.relOffsetY = leaf.relOffsetY
            borderBox.firstLineIndex = band.lo
            borderBox.lastLineExclusive = band.hi
            borderBox.clipRect = borderBox.inheritedClip(clip::get)
            out.add(borderBox)
        }
        val depthCache = HashMap<MarkupElement, Int>()
        fun depthOf(el: MarkupElement): Int = depthCache.getOrPut(el) {
            var d = 0
            var p = el.parent
            while (p != null) { d++; p = p.parent }
            d
        }
        // **子盒底回填祖先**：上面的聚合只沿**叶带**取容器底，中间夹一个自带内边距/边框的容器
        // （典型 `overflow:hidden;padding:4px;border:1px` 的 clearfix `.cf`）时，那一圈（4+1）
        // 不进祖先聚合 ⇒ 祖先（`.test-case` 虚线框）的底比浏览器高 5px，框下方间距看起来变小。
        // 与重路径 `emit` 的递归同式：祖先内容底 ≥ 子盒 border-box 底 + 祖先后沿（下内边距+下边框）。
        // 取 max，不与已聚合的叶带重复计。**深的先算**：子盒回填后再往上回填祖父。
        if (out.size > 1) {
            val byEl = HashMap<MarkupElement, LayoutBox>(out.size)
            for (b in out) {
                val el = b.el ?: continue
                if (!byEl.containsKey(el)) byEl[el] = b
            }
            val deepestFirst = out.filter { it.el != null }.sortedByDescending { depthOf(it.el!!) }
            for (b in deepestFirst) {
                var a = b.el?.parent
                while (a != null) {
                    val p = byEl[a]
                    if (p != null && p !== b) {
                        val edges = (p.style.padding.bottom + p.style.border.bottom).roundToInt()
                        val want = b.contentBottom + edges
                        if (want > p.contentBottom) p.contentBottom = want
                    }
                    a = a.parent
                }
            }
            // **祖先先于后代**：上面三张表都是 HashMap（遍历序不确定），而这里的盒子是扁平表
            // （重路径是盒树按序递归）。不排序时，一个不透明底色可能画在子孙之后把子孙盖掉
            // （本轮的 clearfix 容器就属于这种：容器带 `#f5f5f5` 底色，里面是自带背景的浮动方块）。
            // 按元素在文档树里的深度做**稳定**排序：同深度保持插入序，祖先恒在前。
            out.sortBy { box -> box.el?.let(::depthOf) ?: Int.MAX_VALUE }
        }
        return LightDrawBoxes(out, clip)
    }

    /** Builds a [LayoutBox] for a background owner: its resolved style + horizontal box geometry. */
    private fun ownerBackgroundBox(el: MarkupElement, prepare: LightPrepare): LayoutBox {
        val style = prepare.resolveStyle(el)
        val contentWidth = NormalFlowLayout.descendContentWidth(el, prepare.layoutContentWidth,
            { e ->
                val s = prepare.resolveStyle(e)
                (s.padding.horizontal + s.border.horizontal).roundToInt()
            },
            { e ->
                val s = prepare.resolveStyle(e)
                s.margin.left.roundToInt() + s.margin.right.roundToInt()
            },
            { e -> prepare.resolveStyle(e) },
        ).coerceAtLeast(1)
        val contentLeft = NormalFlowLayout.descendContentLeft(el, 0,
            leftEdgesOf = { e -> val s = prepare.resolveStyle(e); (s.border.left + s.padding.left).roundToInt() },
            marginLeftOf = { e -> val s = prepare.resolveStyle(e); s.margin.left.roundToInt() },
        )
        // E23-b：悬浮叶的画盒几何走 eager 表的实际落点与自身宽（否则浮动底盒会画成满宽，
        // 与重路径（`LayoutBox` 的 boxLeft/boxW = 悬浮盒自身）分叉）。
        prepare.floatBoxOf(el)?.let { (x, w) ->
            return LayoutBox(
                el = el, style = style, contentLeft = contentLeft + x, contentWidth = w,
                ranges = emptyList(), textLength = 0, lineHeights = emptyList(), childBoxes = emptyList(),
            )
        }
        // 表容器与重路径同式（指定宽/margin auto），否则增量/临时页的表边框画满容器宽，
        // 与正典页（33..627）反复横跳。descend 值是旧全宽口径：宽含表自身边距，左缘已含 margin。
        if (el.tag == "table") {
            val insets = (style.border.horizontal + style.padding.horizontal).roundToInt()
            val mL = if (style.marginLeftAuto) 0f else style.margin.left
            val g = orilumn.reader.engine.laying.TableGridModel.tableOuterGeometry(contentWidth + insets, contentLeft - mL.roundToInt(), style)
            return LayoutBox(
                el = el, style = style, contentLeft = g.tableLeft, contentWidth = g.outerW,
                ranges = emptyList(), textLength = 0, lineHeights = emptyList(), childBoxes = emptyList(),
            )
        }
        return LayoutBox(
            el = el, style = style, contentLeft = contentLeft, contentWidth = contentWidth,
            ranges = emptyList(), textLength = 0, lineHeights = emptyList(), childBoxes = emptyList(),
        )
    }
}

/**
 * 内核层：[BoxChapterLayouter.rebuildLocalLines] 的产出 —— 窗口行流 + **无行叶的 border-box 顶**记账。
 *
 * 无行叶 = 零字符、只有边框/背景的块（`lineCount == 0`，如 UA `<hr>`：2px 高的场景分隔线）。它在
 * 行流里没有任何锚行，[BoxChapterLayouter.buildBackgroundDrawBoxes] 的「叶 → 行区间」聚合本来
 * 拿不到它的位置 ⇒ 轻路径（临时页 / 增量窗）画不出它的边框盒，而重路径（盒树）画得出 ——
 * 同章在两路下外观分叉。这里把行流当场算出的 border-box 顶（与重路径 `emit` 的 `st.y` 同式同值）
 * 带出来，画盒侧据此补一个零宽行锚的边框盒。
 *
 * [lineLessBoxTop] 仅当窗内**真的存在**无行叶时非空（下标 = 窗内叶序，其余项为
 * [NO_LINE_LESS_TOP]），所以普通窗口的分配与行为都零变化。
 */
private class LocalLineFlow(
    val lines: List<FlowedLine>,
    val lineLessBoxTop: IntArray?,
) {
    companion object {
        /** [lineLessBoxTop] 的占位值：该叶有行（画盒走行流首/末行旧口径）。 */
        const val NO_LINE_LESS_TOP = Int.MIN_VALUE
    }
}

/** 内核层画盒侧的一次叶区间取值：border-box 上下沿 + 行锚（[lo], [hi]）。 */
private class LeafBand(val top: Int, val bottom: Int, val lo: Int, val hi: Int)

/**
 * E11：轻路径一次窗口绘盒聚合的产物。
 *
 * @property boxes 底色/边框盒（含只带边框的容器/叶与**裁剪属主**盒；见
 *   [BoxChapterLayouter.buildBackgroundDrawBoxes]）。
 * @property clip 裁剪几何：**裁剪元素 → 其 padding box**（窗口坐标系，与 [boxes] 同一 Y 口径）。
 *   等价于重路径 [orilumn.reader.engine.laying.applyInheritedClips] 的返回表；文本行/插图/表格格内容
 *   与盒自身的裁剪都从这张表查（[orilumn.reader.engine.laying.contentClipOf]）。
 */
internal class LightDrawBoxes(
    val boxes: List<LayoutBox>,
    val clip: Map<MarkupElement, ClipRect>,
)

/**
 * A [DrawableBookLayout] that backs only a contiguous range of shaped blocks — the minimal rendering
 * surface needed by the incremental (disk-cache hit) layout path.
 *
 * [lines] / [leaves] / [shapes] all share the same range (blockLo..blockHi from the full chapter).
 * The caller supplies [boxes] (the full chapter box tree) so background/border painting is still
 * correct even though most blocks haven't been shaped — [android.graphics.Canvas.clipRect] discards
 * anything outside the viewport anyway.
 */
private class PartialDrawableLayout(
    private val lines: List<FlowedLine>,
    private val leafList: List<LayoutBox>,
    private val shapeList: List<ParagraphShapeRef>,
    private val localFirst: IntArray,
    private val localLast: IntArray = localFirst,
    private val boxes: List<orilumn.reader.engine.laying.LayoutBox>,
    private val loader: ImageLoader? = null,
    private val href: String = "",
    private val avoidOwnerMap: Map<MarkupElement, MarkupElement> = emptyMap(),
    private val skiaLinesSource: Map<Int, orilumn.reader.engine.skia.DrawLine>? = null,
    private val tableCellsSource: Map<Int, List<orilumn.reader.engine.skia.DrawLine>> = emptyMap(),
    private val tableCellImagesSource: Map<Int, List<orilumn.reader.engine.skia.PageImage>> = emptyMap(),
    private val tableBordersSource: List<orilumn.reader.engine.skia.PageBackground> = emptyList(),
    /**
     * 窗口**上边界缺口**：本窗口首行之上、落在窗外（前一块）的那段页首缺口 ——
     * [orilumn.reader.engine.paging.BookLayout.getPageTopLead] 的窗外取值（[windowLeadTop]）。窗口
     * 行流的原点建在首行自己身上，前一块不在流里 ⇒ 默认式（取上一行帧底）查不到它，必须由调用方
     * 按盒树算出传入。窗口从章头起时为「首块自身 top 边 + 首块自身 margin-top」（章首段前距不与
     * 页边距合并），否则只为「首块自身 top 边」（折叠段前距并入页边距）。0 = 段中开页。
     */
    private val frameLeadTop: Int = 0,
    /**
     * v4 页首缺口的**祖先样式源**（= 调用方的 `prepare::resolveStyle`）。`getPageTopLead(i > 0)`
     * 按盒链上溯找可见外沿盒（[NormalFlowLayout.pageTopLead]）要用它；null = 无源，退回叶自身
     * top 边（v3 口径）。
     */
    private val elementStyleSource: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle)? = null,
    /**
     * E11：裁剪几何源（元素 → 自身 padding box，若该元素 `overflow` 裁剪；窗口坐标系）。
     * 与 [boxes]/[skiaLinesSource] 同源于一次窗口聚合（[LightDrawBoxes.clip]）。null = 不裁。
     */
    private val clipSource: ((MarkupElement) -> ClipRect?)? = null,
    protected override val density: Float = 1f,
) : orilumn.reader.engine.skia.WindowedBookLayout() {

    init {
        require(leafList.size == shapeList.size) { "leaves size(${leafList.size}) != shapes size(${shapeList.size})" }
        require(localFirst.size == leafList.size) { "localFirst size(${localFirst.size}) != leaves size(${leafList.size})" }
        require(localLast.size == leafList.size) { "localLast size(${localLast.size}) != leaves size(${leafList.size})" }
    }

    // BoxPageRenderer structure (drawing is shared with BoxDrawableLayout). The per-leaf local line
    // mapping is owned by this drawable (never the shared LayoutBox fields, which neighbouring
    // temp pages overwrite), so a drawn page is immutable after construction.
    protected override val leaves: List<LayoutBox> get() = leafList
    protected override val shapes: List<ParagraphShapeRef> get() = shapeList
    protected override val leafLocalFirstLine: IntArray get() = localFirst
    protected override val boxesForDraw: List<orilumn.reader.engine.laying.LayoutBox> get() = boxes
    protected override val imageLoader: ImageLoader? get() = loader
    protected override val chapterHref: String get() = href
    // Q1-b：增量/临时页也投影 skia 窗口（buildPartialSkiaWindow）经
    // LineWindowDrawer 像素桥绘制（合流 ReaderScreen）；C1-0 起恒非空（空窗口除外），null → canvas 兜底绘制。
    protected override val skiaLines: Map<Int, orilumn.reader.engine.skia.DrawLine>? get() = skiaLinesSource
    // 表格行展开随窗口预填（与 canonical 同源 helper；Compose 面随行窗绘制）。
    protected override val tableCells: Map<Int, List<orilumn.reader.engine.skia.DrawLine>> get() = tableCellsSource
    protected override val tableCellImages: Map<Int, List<orilumn.reader.engine.skia.PageImage>> get() = tableCellImagesSource
    protected override val tableBorders: List<orilumn.reader.engine.skia.PageBackground> get() = tableBordersSource

    override val lineCount: Int get() = lines.size
    override val length: Int get() = lines.lastOrNull()?.charEnd ?: 0

    /** v4 页首缺口：祖先样式源（调用方的 `prepare::resolveStyle`）；null = 无源，退回 v3 口径。 */
    override fun elementStyle(el: MarkupElement): orilumn.reader.engine.css.ComputedStyle? =
        elementStyleSource?.invoke(el)

    /** E11：该元素内容的 `overflow` 祖先裁剪（含自身裁剪盒）= 窗口聚合表的交集；无源即不裁。 */
    override fun elementClip(el: MarkupElement?): ClipRect? {
        val src = clipSource ?: return null
        return contentClipOf(el, src)
    }

    override fun getLineTop(i: Int): Int = lines[i].yTop
    override fun getLineBottom(i: Int): Int = lines[i].yBottom
    override fun getLineStart(i: Int): Int = lines[i].charStart
    override fun getLineEnd(i: Int): Int = lines[i].charEnd
    override fun isParagraphBoundaryLine(i: Int): Boolean = lines[i].paragraphStart

    /**
     * 页面绘制帧上沿：与 [BookLayout.getPageFrameTop] 同口径，只把**窗口首行**换成调用方传入的
     * 窗外缺口（[frameLeadTop]，= [windowLeadTop]，即 [BookLayout.getPageTopLead] 的窗外取值）——
     * 窗口首行的上一行不在本流里，默认式会退化成行顶，临时页就会比磁盘表多装一行（真机=调参后
     * 页数对不上）。窗口**末**行不需要特殊处理：[WindowedBookLayout] 已按叶自身边给出帧底，且它
     * 不依赖窗外（口径见 [BookLayout.getPageFrameBottom]）。
     */
    override fun getPageFrameTop(i: Int): Int =
        if (i <= 0) getLineTop(0) - frameLeadTop
        else maxOf(getPageFrameBottom(i - 1), getLineTop(i) - getPageTopLead(i))

    /**
     * Line ranges of `break-inside: avoid` blocks, in this window's LOCAL line indexes — mirrors
     * [BoxDrawableLayout.breakInsideAvoidRanges] but aggregated only over the shaped leaves, then
     * clamped to the window. This is safe because the disk table's boundary that anchors this window
     * is a canonical boundary, which never lies strictly inside an avoid range, so a truncated range
     * at the window's left edge can never corrupt a cut.
     */
    override val breakInsideAvoidRanges: List<IntRange> by lazy {
        if (avoidOwnerMap.isEmpty()) return@lazy emptyList()
        val ownerFirst = HashMap<MarkupElement, Int>()
        val ownerLast = HashMap<MarkupElement, Int>()
        for (i in leafList.indices) {
            val leaf = leafList[i]
            val owner = leaf.el?.let { avoidOwnerMap[it] } ?: continue
            val lo = localFirst[i].coerceAtLeast(0)
            val hi = localLast[i]
            if (hi <= lo) continue
            ownerFirst[owner] = minOf(ownerFirst[owner] ?: lo, lo)
            ownerLast[owner] = maxOf(ownerLast[owner] ?: hi, hi)
        }
        val out = ArrayList<IntRange>(ownerFirst.size)
        for ((owner, first) in ownerFirst) {
            val last = ownerLast[owner] ?: first
            if (last > first) out.add(first until last)
        }
        out
    }

    /**
     * P4：断页约束**单源**（[orilumn.reader.engine.laying.BreakClusters.plan]）的窗口叶序列。
     *
     * 叶序 = 本窗口的局部行序；每叶的 `break-*` 取盒上的**有效**边（提升叶取宿主块，见
     * `LayoutBox.breakCarrierStyle`），`break-inside` 属主取结构缓存给的 [avoidOwnerMap]。
     * [breakInsideAvoidRanges] 是这里的 `break-inside` 子集（原子区间）。
     */
    private val breakLeaves: List<orilumn.reader.engine.laying.BreakClusters.Leaf> by lazy {
        val out = ArrayList<orilumn.reader.engine.laying.BreakClusters.Leaf>(leafList.size)
        for (i in leafList.indices) {
            val leaf = leafList[i]
            val lo = localFirst[i].coerceAtLeast(0)
            val hi = localLast[i]
            out.add(
                orilumn.reader.engine.laying.BreakClusters.Leaf(
                    before = leaf.breakBefore,
                    after = leaf.breakAfter,
                    lines = if (hi > lo) lo until hi else IntRange.EMPTY,
                    avoidOwner = leaf.el?.let { avoidOwnerMap[it] },
                ),
            )
        }
        out
    }

    private val breakPlan: orilumn.reader.engine.laying.BreakClusters.Plan by lazy {
        orilumn.reader.engine.laying.BreakClusters.plan(breakLeaves)
    }

    override val avoidSplitRanges: List<IntRange> get() = breakPlan.atomicRanges

    override val forbiddenCutLines: Set<Int> get() = breakPlan.forbiddenCutLines

    override val forcedBreakBeforeLines: Set<Int> get() = breakPlan.forcedBeforeLines
}

/**
 * E23-b：P6-a2 悬浮 eager 透传的四张表（下标 = 叶下标）。
 *
 * [leads]/[widths] 是 P6-a2 原有的环绕前导与悬浮实宽；[xOffs] = 悬浮盒内容左相对其包含块内容左的
 * 偏移（右浮动已含自身宽，直接可用）、[newRows] = 该盒是否另起一行（CSS 2.1 §9.5.1 行装箱结果，
 * 重路径 build 期同一份 [orilumn.reader.engine.laying.NormalFlowLayout.FloatPending] 算法）。
 */
private class FloatEager(
    val leads: List<orilumn.reader.engine.laying.FloatLead?>,
    val widths: List<Int?>,
    val xOffs: List<Int>,
    val newRows: List<Boolean>,
)

/**
 * Light structural cascade for the large-chapter foreground: leaf ordering + char starts (a pure markup
 * pass) plus a **lazy** per-block style/width materializer. Blocks are only cascaded when actually shaped,
 * so the foreground never computes styles for the whole chapter (matching how shaping is already scoped).
 *
 * [block] materializes an index's [LayoutBox] on demand: it resolves the block's own style along its
 * ancestor path and derives its content width from the container chain, with results cached per index.
 * [inlineStyles] resolves the block's paragraph-inline subtree (for emphasis/color spans) lazily too.
 * Day while geometry and line positions come from per-block shaping + local CSS margins in
 * [BoxChapterLayouter.rebuildLocalLines].
 */
class LightPrepare(
    val markup: MarkupElement,
    val profile: TypographicProfile,
    private val contentW: Int,
    private val engine: StyleComputer,
    private val markupLeaves: List<MarkupElement>,
    val globalCharStarts: LongArray,
    /** display:none check, shared with the heavy path's styleMap-derived hidden-ness. */
    internal val hidden: orilumn.reader.engine.laying.HiddenCheck = orilumn.reader.engine.laying.HIDDEN_NONE,
    private val imageLoader: ImageLoader? = null,
    private val chapterHref: String = "",
    internal val backgroundOwnerMap: Map<MarkupElement, MarkupElement> = emptyMap(),
    internal val avoidOwnerMap: Map<MarkupElement, MarkupElement> = emptyMap(),
    /** P3-c 生成内容字符串（结构缓存恒有效；伪样式本 prepare 按新鲜级联懒解）。 */
    genStrings: Map<MarkupElement, Pair<String?, String?>> = emptyMap(),
    /**
     * R26：本章是否存在 `float`（=NONE）。由 [computeStructure] 在**已经解析过每个叶子的那次**
     * 里顺带算出，替代 [computeFloatLeads] 开头那次全章 `blockStyleFor` 扫描。
     *
     * 默认 `true`（保守）：只有拿到确凿的"无 float"才走短路，走错方向最多退回原行为。
     */
    private val chapterHasFloat: Boolean = true,
    /**
     * 表格 auto 分列测宽用的断行器 —— **由 [BoxChapterLayouter] 传入，与重路径 `BoxLayouter`
     * 共用同一个实例**（见 [BoxChapterLayouter.breakerFor]）。本类自己**不再构造**断行器。
     */
    private val breaker: orilumn.reader.engine.laying.ParagraphBreaker,
    /**
     * P3：一页内容高（px）——替换叶高度预算（口径乙）的唯一依据。≤ 0 ⇒ 不施加预算。
     * 与重路径 [BoxChapterLayouter.prepare] 收到的 `contentH` 同一个值。
     */
    private val contentH: Int = 0,
    /**
     * P3：**块自身 border-box 高**的测量回调，由 [BoxChapterLayouter] 注入（用与临时页同一份
     * 塑形器量块 j；自带缓存，**不进共享塑形缓存** —— 预算定在塑形之前，若复用缓存会把自然尺寸
     * 的 shape 留在那儿）。null = 不施加预算（旧调用方/测试）。
     */
    internal var clusterBlockOwnHeight: ((Int) -> Int)? = null,
) {
    val totalBlocks: Int get() = markupLeaves.size

    /** E23-b：块 [i] 的叶元素 —— 只读叶集，**不物化** [block]（结构级扫描用）。 */
    internal fun leafElement(i: Int): MarkupElement = markupLeaves[i]

    /**
     * E23-b：**块边界上的容器指定高差额**前缀表（下标 = 块下标，值 = 进入该块之前要补的差额）。
     * 由 [BoxChapterLayouter] 首次需要时算好写回（要塑形，本类算不了）；null = 尚未计算。
     */
    internal var containerHeightLead: IntArray? = null

    /**
     * E23-e：**竖向下限真的生效**的容器（`min-height` 把盒子撑高了）—— 这些容器的末子下边距被
     * 收进盒内、不再外塌（与 Chromium 实测同式；见 `NormalFlowLayout.emit` 的容器分支）。
     * 由 [BoxChapterLayouter.containerHeightLead] 在算差额时顺带填，供
     * [orilumn.reader.engine.laying.NormalFlowLayout.consecutiveLeafAdvance] 的折叠判据读取
     * （重路径同判据读 `LayoutBox.verticalFloorBound`，两路同源）。
     */
    internal val floorBoundContainers = HashSet<MarkupElement>()

    /** 元素 [el] 的竖向下限是否**真的生效**（撑高了盒子 ⇒ 断掉父↔末子底折叠）。 */
    internal fun floorBinds(el: MarkupElement): Boolean = floorBoundContainers.contains(el)

    /**
     * E23-b / E23-e：本章是否可能有**指定高（或最小高）的非叶容器**（= [containerHeightLead] 的总开关）。
     *
     * 门取**超集**：样式表里有能出 px 的 `height` / `min-height` 声明（`auto`/百分比不算），或树上
     * inline `style` 提到 height（`min-height` 含 "height" 子串，自然命中）；命中后才沿每叶祖先链
     * （按元素去重）真读一次竖向下限。
     * 绝大多数书的 `height` 只出现在 `img` 这类替换叶上（或压根没有）⇒ 这里恒 false，
     * 差额表整章零扫描、零塑形（与 R26 `anyFloat` 同一个取舍：先探一次，别在开书路径上扫）。
     */
    val chapterHasBoxHeight: Boolean by lazy {
        if (!engine.hasBoxHeightDeclaration() && !inlineMentionsHeight(markup)) return@lazy false
        val seen = identityMap<MarkupElement, Boolean>()
        for (leaf in markupLeaves) {
            var n: MarkupElement? = leaf.parent
            while (n != null) {
                if (seen.containsKey(n)) break // 该节点及其祖先已扫过（链一律上溯到根）
                seen[n] = true
                if (NormalFlowLayout.specifiedVerticalFloor(resolveStyle(n)) != null) return@lazy true
                if (n === markup) break
                n = n.parent
            }
        }
        false
    }

    /** 树上是否有 inline `style` 提到 height（粗判超集：命中才交给真级联去判）。 */
    private fun inlineMentionsHeight(root: MarkupElement): Boolean {
        val stack = ArrayList<MarkupElement>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeAt(stack.size - 1)
            if (el.attrs["style"]?.contains("height") == true) return true
            val kids = el.children
            for (i in kids.indices) stack.add(kids[i])
        }
        return false
    }
    /**
     * P3-c: 本 prepare 的生成内容查找（字符串恒有效＋伪样式新鲜懒解，与重路径同值）。
     * 空表即 [EmptyGen] 零开销。
     */
    val genOf: orilumn.reader.engine.laying.GenOf by lazy {
        if (genStrings.isEmpty()) orilumn.reader.engine.laying.EmptyGen
        else {
            val pseudoCache = HashMap<Pair<MarkupElement, String>, orilumn.reader.engine.css.ComputedStyle?>()
            orilumn.reader.engine.laying.GeneratedContent.genOf(genStrings) { el, p ->
                pseudoCache.getOrPut(el to p) {
                    engine.pseudoStyle(el, orilumn.reader.engine.laying.ancestorsOf(el), engine.resolve(el, styleCache), p)
                }
            }
        }
    }
    /**
     * `::first-letter` 伪元素查找（与 [genOf] 同式：章节无该伪规则即 null ⇒ 塑形侧零开销；
     * 命中则按叶根懒解＋缓存，伪样式恒新鲜，不吃旧表）。
     */
    internal val firstLetterOf: ((MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?)? by lazy {
        if (!styleComputer().hasFirstLetterRules()) {
            null
        } else {
            val flCache = HashMap<MarkupElement, orilumn.reader.engine.css.ComputedStyle?>()
            return@lazy { el: MarkupElement ->
                flCache.getOrPut(el) {
                    engine.firstLetterStyle(el, orilumn.reader.engine.laying.ancestorsOf(el), engine.resolve(el, styleCache))
                }
            }
        }
    }
    // P1-2: 与 computeStructure 的 globalCharStarts 同式（样式化归一长度）。
    /**
     * 全章字符总数 = 各叶 `styledCharAdvance` 之和。
     *
     * **不能**写成 `markupLeaves.sumOf { styledCharAdvance(...) }` 每次现算。那是全章一遍 CSS 级联
     * ＋文本前进宽度测量：Rust 书 172 块实测 **1.25s**（≈7.3ms/块，锚页开书的一半时间就花在这
     * 一个实参上），GIMP 2579 块实测 9.7s。而 [BoxChapterLayouter.incrementalLayoutForPage] 的
     * `backfillBlockRanges` 实参每次开书都要它一次，等于每开一次书就重算一遍全章。
     *
     * 改走 `globalCharStarts` 的恒等式：[computeStructure] 与重路径 [ChapterPrepareResult] 都用
     * `accumulateCharStarts(leaves.map { styledCharAdvance(同一式) })`，即 `starts` 就是同一批
     * advance 的前缀和，于是 `sum == starts.last() + advance(末叶)`——只需**一次** advance，约 7ms。
     * 该恒等式与 `blockOf` 的二分共用同一前提（`starts` 与活级联同步，见 [globalCharStarts]），
     * 不引入新的不一致来源；`by lazy` 顺带让重复访问也免费。
     */
    val totalChars: Int by lazy {
        val starts = globalCharStarts
        val n = markupLeaves.size
        if (starts.isEmpty() || n == 0 || starts.size != n) {
            // 结构与叶集不同源（不该发生）。退回归原先的现算口径，行为与改动前逐字节一致。
            markupLeaves.sumOf {
                NormalFlowLayout.styledCharAdvance(
                    it, { e -> styleComputer().resolve(e, styleCache) }, lightClassify(), hidden, genOf,
                ).toInt()
            }
        } else {
            starts.last().toInt() + NormalFlowLayout.styledCharAdvance(
                markupLeaves[n - 1], { e -> styleComputer().resolve(e, styleCache) },
                lightClassify(), hidden, genOf,
            ).toInt()
        }
    }

    /** 轻路径块判定：与 computeStructure 同门（有 display 声明才读 display:block）。 */
    internal fun lightClassify(): orilumn.reader.engine.laying.BlockClassify =
        if (styleComputer().hasDisplayDeclaration()) {
            val displayCache = identityMap<MarkupElement, Boolean>()
            orilumn.reader.engine.laying.BlockClassify { el -> NormalFlowLayout.defaultBlock(el) || styleComputer().resolveDisplayOnly(el, displayCache) }
        } else {
            NormalFlowLayout.DEFAULT_CLASSIFY
        }

    /** First-leaf-per-`<li>` marker carriers (see [ListMarkers.firstCarrierSet]); each `<li>` draws
     *  exactly one bullet, at its first leaf, so `<li><p>a</p><p>b</p></li>` never double-bullets. */
    val firstCarrierLeaves: Set<MarkupElement> by lazy {
        orilumn.reader.engine.layout.ListMarkers.firstCarrierSet(markupLeaves)
    }

    /** The chapter content width this prepare was built for (used to resolve box horizontal geometry). */
    val layoutContentWidth: Int get() = contentW

    private val materialized = MutableList<LayoutBox?>(totalBlocks) { null }
    private val styleCache = HashMap<MarkupElement, orilumn.reader.engine.css.ComputedStyle>()
    private val inlineMaps = HashMap<Int, Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>>()

    /** Leaf whose range contains [charOffset]; clamps to 0..last. Mirrors `ChapterPrepareResult.blockIndexForChar`. */
    fun blockIndexForChar(charOffset: Int): Int {
        var lo = 0
        var hi = globalCharStarts.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val start = globalCharStarts[mid]
            val end = if (mid + 1 <= globalCharStarts.lastIndex) globalCharStarts[mid + 1] else Long.MAX_VALUE
            when {
                charOffset in start until end -> return mid
                charOffset < start -> hi = mid - 1
                else -> lo = mid + 1
            }
        }
        return lo.coerceIn(0, totalBlocks - 1)
    }

    /**
     * P6-a2: 文本叶相对其原文前兄弟的悬浮环绕前导（P6-a 起全章 eager 透传，
     * 与重路径 build 期 [FloatPending] 同式，双路恒一致；v1 单 img 前驱是其特例）。
     *
     * E23-b 起同一遍顺带给出**浮动盒落点**：[FloatEager.xOffs] = 悬浮盒的内容左相对其包含块
     * 内容左的偏移（右浮动已含自身宽，直接可用）、[FloatEager.newRows] = 是否另起一行。
     */
    private val floatData: FloatEager by lazy {
        computeFloatLeads()
    }
    val floatLeads: List<orilumn.reader.engine.laying.FloatLead?> get() = floatData.leads
    /** P6-a2: 悬浮实宽表（与 floatLeads 同一前向透传产物；右对齐/塑形宽同源，零重算）。 */
    val floatWidths: List<Int?> get() = floatData.widths
    /** E23-b: 悬浮盒落点偏移表（非悬浮恒 0）。 */
    val floatXOffs: List<Int> get() = floatData.xOffs
    /** E23-b: 悬浮盒是否另起一行表（非悬浮恒 true）。 */
    private val floatNewRows: List<Boolean> get() = floatData.newRows

    /** E23-b: 块 [i] 是悬浮叶时给出「落点左 + 自身宽」（非悬浮 null）。 */
    internal fun floatBoxAt(i: Int): Pair<Int, Int>? {
        val w = floatWidths.getOrNull(i) ?: return null
        return floatXOffs.getOrNull(i)?.let { it to w }
    }

    /** E23-b: 元素 → 悬浮盒「落点左 + 自身宽」（身份表；仅悬浮章节按需建，无悬浮零开销）。 */
    private val floatBoxByEl: Map<MarkupElement, Pair<Int, Int>> by lazy {
        if (!chapterHasFloat) {
            emptyMap()
        } else {
            val m = identityMap<MarkupElement, Pair<Int, Int>>()
            for (i in markupLeaves.indices) floatBoxAt(i)?.let { m[markupLeaves[i]] = it }
            m
        }
    }

    /** E23-b: 该元素的悬浮盒几何（非悬浮/无悬浮章节 = null ⇒ 调用方走原口径）。 */
    internal fun floatBoxOf(el: MarkupElement): Pair<Int, Int>? = floatBoxByEl[el]

    fun floatLeadAt(blockIdx: Int): orilumn.reader.engine.laying.FloatLead? {
        if (totalBlocks <= 0) return null
        return floatLeads[blockIdx.coerceIn(0, totalBlocks - 1)]
    }

    /**
     * P6-a2 eager 环绕透传：文档序单遍，复刻重路径 build 期 float 语义
     * （注册/消耗/收窄同 helper；塑形同 breaker 同输入，高度恒等，故 lead 恒等）。
     * 无悬浮章节恒全空（仅样式扫描，零塑形开销）。
     */
    private fun computeFloatLeads(): FloatEager {
        val n = totalBlocks
        // R26：结构层已经算过这个标志（有叶子时几乎白送），别再全章扫一遍只为回答同一个问题。
        // 实测 Rust 书 ch7（169 叶）这第一次扫描要 275ms，且它被 `block(i)` 里的 `floatLeads[i]`
        // 触发，直接落在开书关键路径上——与 totalChars 曾把整章重算 9.7s 同一个病。
        if (!chapterHasFloat) return FloatEager(List(n) { null }, List(n) { null }, List(n) { 0 }, List(n) { true })
        var anyFloat = false
        for (i in 0 until n) {
            if (blockStyleFor(markupLeaves[i]).floatSide != orilumn.reader.engine.css.FloatSide.NONE) {
                anyFloat = true
                break
            }
        }
        if (!anyFloat) return FloatEager(List(n) { null }, List(n) { null }, List(n) { 0 }, List(n) { true })
        // S3：与重路径同一单源（浮动 lead 的宽度决定其宿主块宽，必须与正文同变体）。
        val breaker = orilumn.reader.engine.skia.bodyParagraphBreaker(profile.letterSpacingEm, profile.cjkLatinSpacingEmApplied)
        val pending = NormalFlowLayout.FloatPending()
        val out = ArrayList<orilumn.reader.engine.laying.FloatLead?>(n)
        val widths = ArrayList<Int?>(n)
        val xOffs = ArrayList<Int>(n)
        val newRows = ArrayList<Boolean>(n)
        val classify = lightClassify()
        for (i in 0 until n) {
            val el = markupLeaves[i]
            // 祖先容器进入与重路径递归同序（preClear 幂等，逐叶全链重放与单次进入同效）。
            var a = el.parent
            while (a != null) {
                pending.preClear(resolveStyle(a).clearSide)
                if (a === markup) break
                a = a.parent
            }
            val style = blockStyleFor(el)
            val cw = NormalFlowLayout.descendContentWidth(el, contentW,
                { e ->
                    val s = styleComputer().resolve(e, styleCache)
                    (s.padding.horizontal + s.border.horizontal).roundToInt()
                },
                { e ->
                    val s = styleComputer().resolve(e, styleCache)
                    s.margin.left.roundToInt() + s.margin.right.roundToInt()
                },
                { e -> styleComputer().resolve(e, styleCache) },
            ).coerceAtLeast(1)
            if (el.tag == "table" || el.tag == "tr" || el.tag == "#text") {
                // 表格分支清零（重路径 table 分支同式）；合成匿名恒空（重路径匿名分支同式）。
                if (el.tag != "#text") pending.dropAll()
                out.add(null)
                widths.add(null)
                xOffs.add(0)
                newRows.add(true)
                continue
            }
            if (NormalFlowLayout.isReplaceable(el)) {
                if (style.floatSide != orilumn.reader.engine.css.FloatSide.NONE) {
                    val (fw, fh) = NormalFlowLayout.replacedUsedSize(
                        el, style, NormalFlowLayout.innerBreakWidth(style, cw), imageLoader, chapterHref, density = profile.density,
                    )
                    // E24：`replacedUsedSize` 给内容宽；盒宽 / 排位 / 环绕一律 border-box（+ img 自身横边）。
                    val fbb = NormalFlowLayout.floatBorderBoxWidth(style, fw)
                    pending.register(
                        style.floatSide, fbb + (style.margin.left + style.margin.right).roundToInt(), fh,
                        cw + (style.margin.left + style.margin.right).roundToInt(),
                    )
                    widths.add(fbb)
                    xOffs.add(
                        if (style.floatSide == orilumn.reader.engine.css.FloatSide.RIGHT) {
                            (cw + (style.margin.left + style.margin.right).roundToInt() - pending.lastX).coerceAtLeast(0)
                        } else {
                            pending.lastX
                        },
                    )
                    newRows.add(pending.lastNewRow)
                } else {
                    pending.dropAll()
                    widths.add(null)
                    xOffs.add(0)
                    newRows.add(true)
                }
                out.add(null)
                continue
            }
            // 文本叶：子树样式（与重路径吸收口径同集）。
            val sub = HashMap<MarkupElement, orilumn.reader.engine.css.ComputedStyle>()
            fun reg(e: MarkupElement) {
                sub[e] = resolveStyle(e)
                for (c in e.children) reg(c)
            }
            reg(el)
            if (style.floatSide != orilumn.reader.engine.css.FloatSide.NONE) {
                val text = NormalFlowLayout.absorbStyled(el, sub, classify, hidden, genOf).text
                val runs = NormalFlowLayout.leafFontRuns(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf)
                val floatW = NormalFlowLayout.floatBorderBoxWidth(
                    style,
                    NormalFlowLayout.floatTextWidth(
                        style, text, cw, breaker, runs,
                        NormalFlowLayout.leafInlineBoxRuns(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf),
                    ),
                )
                val breakWf = NormalFlowLayout.innerBreakWidth(style, floatW)
                val shaped = breakWrappedLines(
                    breaker, text, style, breakWf, null, el.tag, runs,
                    style.textIndentPx,
                    NormalFlowLayout.leafBaselineShifts(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf),
                    NormalFlowLayout.leafInlineBoxRuns(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf),
                )
                val baseH = adjustLineHeightsForInlineImages(
                    text, shaped, el, sub, classify, hidden, breakWf, imageLoader, chapterHref, profile.density,
                )
                // P6-b: 叠排注音行增高（与重路径同式；无注音零回归）。
                val grownH = orilumn.reader.engine.laying.adjustLineHeightsForRuby(
                    shaped, baseH, NormalFlowLayout.leafRubyRuns(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf),
                )
                val h = grownH.sumOf { it.coerceAtLeast(1) }
                // E23-b：悬浮跨度高 = max(行高和, `height`)（与重路径 `emit` 的叶盒高同式；空悬浮盒也占位）。
                // 行装箱按**外边盒**宽记账（左右外边距参与同行排位），容量 = 含边距的包含块内容宽。
                val mH = (style.margin.left + style.margin.right).roundToInt()
                pending.register(
                    style.floatSide, floatW + mH, NormalFlowLayout.specifiedContentHeight(style, h), cw + mH,
                )
                widths.add(floatW)
                xOffs.add(
                    if (style.floatSide == orilumn.reader.engine.css.FloatSide.RIGHT) {
                        (cw + mH - pending.lastX).coerceAtLeast(0)
                    } else {
                        pending.lastX
                    },
                )
                newRows.add(pending.lastNewRow)
                out.add(null)
                continue
            }
            val lead = NormalFlowLayout.pendingLeadFor(pending, style, cw)
            out.add(lead)
            widths.add(null)
            xOffs.add(0)
            newRows.add(true)
            val text = NormalFlowLayout.absorbStyled(el, sub, classify, hidden, genOf).text
            val runs = NormalFlowLayout.leafFontRuns(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf)
            val shaped = breakWrappedLines(
                breaker, text, style, NormalFlowLayout.innerBreakWidth(style, cw), lead, el.tag, runs,
                style.textIndentPx,
                NormalFlowLayout.leafBaselineShifts(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf),
                NormalFlowLayout.leafInlineBoxRuns(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf),
            )
            val baseH = adjustLineHeightsForInlineImages(
                text, shaped, el, sub, classify, hidden, NormalFlowLayout.innerBreakWidth(style, cw), imageLoader, chapterHref, profile.density,
            )
            // P6-b: 叠排注音行增高（与重路径同式；无注音零回归）。
            val grownH = orilumn.reader.engine.laying.adjustLineHeightsForRuby(
                shaped, baseH, NormalFlowLayout.leafRubyRuns(el, sub, classify, hidden, genOf, firstLetterOf = firstLetterOf),
            )
            val h = grownH.sumOf { it.coerceAtLeast(1) } + style.margin.top.coerceAtLeast(0f).roundToInt()
            pending.consume(h)
        }
        return FloatEager(out, widths, xOffs, newRows)
    }

    /**
     * P4-c2: styled `<a href>` ranges for leaf [blockIdx] ([globalCharStarts]`[blockIdx]` is the
     * caller-side base), computed with the same resolver/white-space/gen inputs the shaper uses —
     * the tap hit-test's offsets and these ranges can never drift. Empty for
     * replaceable/table leaves (they own no shaped text).
     */
    fun linkRangesAt(blockIdx: Int): List<orilumn.reader.engine.layout.LinkRange> {
        if (totalBlocks <= 0) return emptyList()
        val el = markupLeaves[blockIdx.coerceIn(0, totalBlocks - 1)]
        if (orilumn.reader.engine.laying.NormalFlowLayout.isReplaceable(el) || el.tag == "table") return emptyList()
        val classify = lightClassify()
        return orilumn.reader.engine.layout.LinkRanges.ofLeafStyled(
            el,
            wsOf = { resolveStyle(it).whiteSpace },
            isExcluded = { hidden.isHidden(it) },
            isBlock = { classify.isBlock(it) },
            leafWs = resolveStyle(el).whiteSpace,
            genOf = genOf,
            styleOf = { resolveStyle(it) },
        )
    }

    /**
     * P4-c2: [fragment] 锚点在排版字符流中的起始偏移（与 [globalCharStarts] 同空间）。
     * 与塑形/分页/点按命中同源（[styledSegments] 同输入），裸文本 walk 的漂移
     * （空白折叠、`display:none`、img 槽位）不再进入目录/链接跳转。
     * 锚点元素即叶时偏移为叶起始；锚点在容器上时取其下文档序首叶起始。
     * 无命中回 null（调用方走既有章首回退）。
     */
    internal fun anchorCharStart(fragment: String): Int? {
        if (totalBlocks <= 0) return null
        var target: MarkupElement? = null
        fun find(el: MarkupElement) {
            if (target != null) return
            if (fragment in orilumn.reader.engine.anchorKeysOf(el)) { target = el; return }
            for (c in el.children) find(c)
        }
        find(markup)
        val ael = target ?: return null
        // 叶表身份索引（MarkupElement 非值语义，恒用身份比较）。
        val leafIdxByEl = identityMap<MarkupElement, Int>()
        for (i in markupLeaves.indices) leafIdxByEl[markupLeaves[i]] = i
        // 所属叶：锚点上行（含自身）命中叶表；容器锚点取其下文档序首叶。
        var leafIdx = -1
        var cur: MarkupElement? = ael
        while (cur != null) {
            leafIdxByEl[cur]?.let { leafIdx = it; break }
            if (cur === markup) break
            cur = cur.parent
        }
        if (leafIdx < 0) {
            for (i in markupLeaves.indices) {
                var p: MarkupElement? = markupLeaves[i]
                while (p != null) {
                    if (p === ael) { leafIdx = i; break }
                    if (p === markup) break
                    p = p.parent
                }
                if (leafIdx >= 0) break
            }
        }
        if (leafIdx < 0) return null
        val leafEl = markupLeaves[leafIdx]
        if (leafEl === ael) return globalCharStarts[leafIdx].toInt()
        val classify = lightClassify()
        val segs = orilumn.reader.engine.laying.styledSegments(
            leafEl,
            wsOf = { resolveStyle(it).whiteSpace },
            isExcluded = { hidden.isHidden(it) },
            isBlock = { classify.isBlock(it) },
            leafWs = resolveStyle(leafEl).whiteSpace,
            genOf = genOf,
            styleOf = { resolveStyle(it) },
        ).segments
        var cursor = 0
        for (s in segs) {
            // 锚点元素是段节点自身或其祖先（不越过所属叶）即命中：段首即锚点起始。
            var n: MarkupElement? = s.node
            while (n != null && n !== leafEl) {
                if (n === ael) return globalCharStarts[leafIdx].toInt() + cursor
                n = n.parent
            }
            cursor += s.text.length
        }
        // 锚点在容器上且叶是其首叶：段循环必已命中；保底回叶起始。
        return globalCharStarts[leafIdx].toInt()
    }

    /**
     * P3：**提升叶的宿主块**（叶是 `<img>`，块是最近的块级祖先）。
     *
     * `enumerateBlockLeaves` 把「独图块」枚举成它里面的 img（重路径同式），于是叶的 `el` 是 img
     * 而布局地位是那个块：`break-*` 这类挂在块上的属性该按块算。img 自身就是块级子元素时返回
     * `el`（那个分支里叶本来就是块本身，自身样式即正确语义）。`null` = 找不到（不该发生）。
     */
    private fun carrierBlockOf(el: MarkupElement): MarkupElement? {
        if (el.tag != "img") return el
        val classify = lightClassify()
        if (classify.isBlock(el)) return el
        var p = el.parent
        while (p != null) {
            if (classify.isBlock(p)) return p
            p = p.parent
        }
        return null
    }

    /** P3：叶的**有效**断页边（提升叶取宿主块，与重路径 `LayoutBox.breakBefore/breakAfter` 同义）。 */
    private fun breakPairOf(el: MarkupElement): orilumn.reader.engine.laying.ReplacedFitBudget.BreakPair {
        val s = if (el.tag == "img") resolveStyle(carrierBlockOf(el) ?: el) else resolveStyle(el)
        return orilumn.reader.engine.laying.ReplacedFitBudget.BreakPair(s.breakBefore, s.breakAfter)
    }

    /** 替换叶的**自然** used 高（无视任何已写入的预算）——预算重算的幂等输入。 */
    private fun naturalReplaceableHeight(b: LayoutBox): Int {
        val el = b.el ?: return b.replaceableHeight
        return NormalFlowLayout.replaceableHeightOf(
            el, b.style, NormalFlowLayout.innerBreakWidth(b.style, b.contentWidth), imageLoader, chapterHref, density = profile.density,
        )
    }

    /**
     * P3：给 `[lo, hi)` 的替换叶写入高度预算（口径乙），**必须在塑形之前**调用。
     *
     * 与重路径 [orilumn.reader.engine.laying.NormalFlowLayout.layout] 的 `applyReplacedFit` 同一份
     * 决策单源（[orilumn.reader.engine.laying.ReplacedFitBudget.plan]）：这里只把「自然高 / 自身高 /
     * 断页边」三条喂成轻路径的几何。簇按**扁平叶序列**取（与重路径同一份、重轻恒一致），
     * 故«图 + 图注»在两侧都被当成一个簇。
     *
     * 幂等：`naturalH` 走 [naturalReplaceableHeight]（不看已写入的预算），重复调用同一区间结果不变。
     */
    fun applyReplacedFits(lo: Int, hi: Int) {
        val measure = clusterBlockOwnHeight ?: return
        if (contentH <= 0 || totalBlocks <= 0) return
        val a = lo.coerceIn(0, totalBlocks)
        val b = hi.coerceIn(a, totalBlocks)
        val leaves = markupLeaves
        val plan = orilumn.reader.engine.laying.ReplacedFitBudget.plan(
            leaves = leaves,
            lo = a,
            hi = b,
            contentH = contentH,
            naturalH = { i ->
                val box = block(i)
                if (box.replaceableHeight > 0) naturalReplaceableHeight(box) else 0
            },
            ownHeight = { i ->
                val box = block(i)
                if (box.replaceableHeight > 0) {
                    box.replaceableHeight + (box.style.padding.vertical + box.style.border.vertical).roundToInt()
                } else measure(i)
            },
            breakOf = ::breakPairOf,
            styleOf = ::resolveStyle,
        )
        if (plan.isEmpty()) return
        for ((i, fit) in plan) {
            val box = block(i)
            val el = box.el ?: continue
            box.replaceableFitH = fit
            box.replaceableHeight = NormalFlowLayout.replaceableHeightOf(
                el, box.style, NormalFlowLayout.innerBreakWidth(box.style, box.contentWidth),
                imageLoader, chapterHref, fit, profile.density,
            )
        }
    }

    /** Materializes (and caches) index [i]'s [LayoutBox] — style via lazy cascade, content width via the
     *  container chain — without ever cascading the whole chapter. */
    fun block(i: Int): LayoutBox {
        materialized[i]?.let { return it }
        // R26 诊断：只有置位方（ShapeProbe）非 null 才计墙钟，热路径仅多一次 null 检查。
        val sink = blockTimingSink
        var m0 = 0L; var m1 = 0L; var m2 = 0L; var m3 = 0L
        val tBlk0 = if (sink != null) orilumn.reader.time.platformNowMs() else 0L
        val el = markupLeaves[i]
        val style = blockStyleFor(el)
        if (sink != null) m0 = orilumn.reader.time.platformNowMs() - tBlk0
        val contentWidth = NormalFlowLayout.descendContentWidth(el, contentW,
            { e ->
                val s = styleComputer().resolve(e, styleCache)
                (s.padding.horizontal + s.border.horizontal).roundToInt()
            },
            { e ->
                val s = styleComputer().resolve(e, styleCache)
                s.margin.left.roundToInt() + s.margin.right.roundToInt()
            },
            { e -> styleComputer().resolve(e, styleCache) },
        )
        // Horizontal position = the accumulated block left edges (mirrors the heavy path's descent).
        val tW = if (sink != null) orilumn.reader.time.platformNowMs() else 0L
        val contentLeft = NormalFlowLayout.descendContentLeft(el, 0,
            leftEdgesOf = { e -> val s = styleComputer().resolve(e, styleCache); (s.border.left + s.padding.left).roundToInt() },
            marginLeftOf = { e -> val s = styleComputer().resolve(e, styleCache); s.margin.left.roundToInt() },
        )
        if (sink != null) { m1 = orilumn.reader.time.platformNowMs() - tW; m2 = m1 }
        val replaceable = NormalFlowLayout.isReplaceable(el)
        val isTr = el.tag == "tr"
        // R26：`textLength` 不再现算，改走 `globalCharStarts` 的差分。
        //
        // 原本每物化一块就跑一次 `styledCharAdvance`（按块文本长度线性、每元素回调 resolveStyle）。
        // 实测 Rust 书 ch7 锚页 5 块里这一项 211~236ms，占该页 shape 的 37%。与 totalChars
        // 曾把整章重算 9.7s 的是同一个函数。
        //
        // 恒等式：[computeStructure] 用**同一表达式**（同 classify / hidden / genOf / 级联）逐叶
        // `styledCharAdvance` 后交给 `accumulateCharStarts`，而后者是精确整数前缀和
        // （`starts[i] = running; running += lengths[i]`，无舍入），`styledCharAdvance` 返回 `Long`，
        // 故 `starts[i+1] - starts[i]` **精确等于**块 i 的 advance，与原 `.toInt()` 逐块同值。
        // 末块没有后继，用已按同式证明过的 `totalChars - starts[n-1]`。
        //
        // 不能改成置 0：`rebuildLocalLines` 三处（charEnd / runningChar 累加 ×2）都读逐块
        // textLength，那是轻路径行流的字符记账，置 0 会让 charStart/charEnd 全错。
        // `sAdv=` 埋点留作回归哨兵：本项归零后它应恒为 0。
        val tL = if (sink != null) orilumn.reader.time.platformNowMs() else 0L
        val textLen = blockTextLengths[i]
        if (sink != null) m3 = orilumn.reader.time.platformNowMs() - tL
        // P6-a2: 右悬浮右对齐（宽取 eager 表，与重路径同源，零重算）。
        var boxLeft = contentLeft
        // E23-b：悬浮盒的落点与自身宽（同行装箱结果）——左浮动自内容左起、右浮动自内容右起，
        // 与重路径 build 期同值；非悬浮叶走原口径（containing block 内容宽）。
        var boxW = contentWidth
        val floatW = if (el.tag == "#text") null else floatWidths[i]
        if (floatW != null && floatW > 0) {
            boxLeft = contentLeft + floatXOffs[i]
            boxW = floatW
        }
        // P3：独图提升叶的宿主块样式（break-* 挂在块上；见 `LayoutBox.breakCarrierStyle`）。
        val carrierStyle = if (!replaceable) null else (carrierBlockOf(el) ?: el).let { c ->
            if (c === el) null else resolveStyle(c)
        }
        val box = LayoutBox(
            el = el, style = style,
            contentLeft = boxLeft,
            contentWidth = boxW,
            ranges = emptyList(),
            // P1-2: 与重路径盒 textLength 同式（样式化归一长度）。
            textLength = textLen,
            lineHeights = emptyList(),
            childBoxes = emptyList(),
            replaceableHeight = if (replaceable) {
                NormalFlowLayout.replaceableHeightOf(el, style, NormalFlowLayout.innerBreakWidth(style, contentWidth), imageLoader, chapterHref, density = profile.density)
            } else 0,
            table = if (isTr) tableRowLayoutFor(el, contentWidth) else null,
            // P6-a2: 环绕前导取全章 eager 表（与重路径 pending 透传同式；匿名/替换/表格叶恒空）。
            floatLead = floatLeads[i],
            // E23-b：同行装箱结果（重路径 build 期同一份）；非悬浮恒 true。
            floatNewRow = if (floatW != null && floatW > 0) floatNewRows[i] else true,
            breakCarrierStyle = carrierStyle,
        )
        materialized[i] = box
        if (sink != null) {
            val total = orilumn.reader.time.platformNowMs() - tBlk0
            if (firstBlockMs < 0L) firstBlockMs = total
            // m0=blockStyleFor / m1=descendContentWidth / m2=descendContentLeft / m3=textLength 差分
            sink(total, m0, m1, m2, m3)
        }
        return box
    }

    /**
     * R26：逐块 `textLength`，由 [globalCharStarts] 差分得来，供 [block] 的 `textLength` 实参用。
     *
     * 精确性依据见 [block] 里那段注释：`accumulateCharStarts` 是整数前缀和、无舍入，
     * `styledCharAdvance` 返回 `Long`，故差分与逐块现算逐值相同。
     * `starts.size != n`（结构与叶集不同源）时退回归原先的现算口径，行为与改动前一致。
     */
    private val blockTextLengths: IntArray by lazy {
        val n = markupLeaves.size
        val starts = globalCharStarts
        if (starts.isEmpty() || n == 0 || starts.size != n) {
            IntArray(n) {
                NormalFlowLayout.styledCharAdvance(
                    markupLeaves[it], { e -> styleComputer().resolve(e, styleCache) },
                    lightClassify(), hidden, genOf,
                ).toInt()
            }
        } else {
            val last = totalChars - starts[n - 1]
            IntArray(n) { i -> if (i + 1 < n) (starts[i + 1] - starts[i]).toInt() else last.toInt() }
        }
    }

    /**
     * R26 诊断：[block] 物化计时回调
     * `(blockMs, styleForMs, widthMs, leftMs, textLenMs)`。`textLenMs` 曾是块内 `textLength`
     * 的 styledCharAdvance 耗时，现已改走 [blockTextLengths] 差分、不再现算，故**恒为 0**；
     * 保留参数位是为了让日志里 `sAdv=0` 本身成为"这条路没被走"的哨兵。
     * `null` = 不计时（默认）。置位方是 `BoxChapterLayouter` 的 `ShapeProbe`，只在那一个
     * shape 窗口内挂。
     */
    internal var blockTimingSink: ((Long, Long, Long, Long, Long) -> Unit)? = null

    /** R26 诊断：本窗口内第一次 [block] 物化的耗时。与 [blockTimingSink] 的累计值对比即可区分
     *  「一次性初始化」与「逐块成本」——`sBlk ≈ sBlkFirst` 即前者。 */
    internal var firstBlockMs: Long = -1L

    /** The 2D grid layout for one table row (`tr`) given its content width — column x/widths + cells,
     *  cell heights unknown (filled & used at shaping time). Mirrors the heavy path's column
     *  (`table-layout: fixed` equal-split / `auto` content measure + border-spacing gaps)
     *  and cell placements so the light and heavy rows agree. */
    private fun tableRowLayoutFor(trEl: MarkupElement, contentWidth: Int): orilumn.reader.engine.laying.TableRowLayout? {
        var table = trEl.parent
        while (table != null && table.tag != "table") table = table.parent
        if (table == null) return null
        val model = TableGridModel.build(table)
        if (model.columnCount <= 0) return null
        val row = model.rows.firstOrNull { it.el === trEl } ?: return null
        // P1-2: 与重路径同式（fixed 均分 / auto 内容测宽；单源 helpers）。
        val tstyle = styleComputer().resolve(table, styleCache)
        val spH = if (tstyle.borderCollapse) 0f else tstyle.borderSpacingH
        val gapH = spH.coerceAtLeast(0f).roundToInt()
        // The row's border-box left (accumulated block edges) so cell x matches the heavy path's
        // column layout (both absolutely positioned in content coordinates).
        val rowLeftBase = NormalFlowLayout.descendContentLeft(trEl, 0,
            leftEdgesOf = { e -> val s = styleComputer().resolve(e, styleCache); (s.border.left + s.padding.left).roundToInt() },
            marginLeftOf = { e -> val s = styleComputer().resolve(e, styleCache); s.margin.left.roundToInt() },
        )
        // 同表各行列宽一致：按表缓存（同 prepare 内同表同宽；宽/位变化即重算）。
        // P1-2 表外盒：与重路径同单源 [TableGridModel.tableOuterGeometry]（指定宽/margin auto）；
        // light 的 contentWidth 即表内容宽（旧全宽口径），反推容器域后与重路径同算。
        val tableInsets = (tstyle.border.horizontal + tstyle.padding.horizontal).roundToInt()
        val g = TableGridModel.tableOuterGeometry(contentWidth + tableInsets, rowLeftBase - tableInsets, tstyle)
        val tableW = g.contentW
        val rowLeft = g.tableLeft + tableInsets
        val cached = tableColCache[table]
        val (xs, ws) = if (cached != null && cached.tableW == tableW && cached.rowLeft == rowLeft) {
            cached.xs to cached.ws
        } else {
            val laid = if (tstyle.tableLayoutFixed) {
                TableGridModel.columnLayout(tableW, model.columnCount, spH, rowLeft)
            } else {
                val classify = lightClassify()
                val prefs = ArrayList<TableGridModel.CellPref>()
                for (r in model.rows) for (cell in r.cells) {
                    prefs.add(
                        NormalFlowLayout.tableCellPref(
                            breaker, cell.col, cell.colSpan,
                            cellBlockPlan(cell.el, classify), cellStyle(cell.el),
                        ),
                    )
                }
                val tSpecified = tstyle.widthPct != null || tstyle.widthPx != null
                TableGridModel.autoColumnLayout(
                    tableW, model.columnCount, spH, rowLeft, prefs,
                    minTableW = if (tSpecified) tableW else 0,
                )
            }
            tableColCache[table] = CachedTableCols(tableW, rowLeft, laid.first, laid.second)
            laid
        }
        val cells = row.cells.map {
            var outer = 0
            for (i in it.col until it.col + it.colSpan) outer += ws.getOrElse(i) { 0 }
            outer = (outer + (it.colSpan - 1) * gapH).coerceAtLeast(1)
            // **Q15**：块序列在此物化（与重路径 `buildTableRows` 同一份 `cellBlocks`），塑形由
            // `fillTableRowCells` 回填 —— 它按块循环，故这里必须给出块的**集合**与静态几何。
            orilumn.reader.engine.laying.TableCellLayout(
                it.el, it.col, it.colSpan, xs.getOrElse(it.col) { rowLeft }, outer, 0, it.isHeader,
                cellBlockPlan(it.el, lightClassify()), it.rowSpan,
            )
        }
        return orilumn.reader.engine.laying.TableRowLayout(xs, ws, cells, tstyle.emptyCellsHide)
    }

    /** 同表列几何缓存（表元素 → 宽/位快照＋列 x/宽）。 */
    private class CachedTableCols(val tableW: Int, val rowLeft: Int, val xs: IntArray, val ws: IntArray)

    private val tableColCache = HashMap<MarkupElement, CachedTableCols>()


    /**
     * **Q15**：格内容的块序列（子树懒级联；与重路径 `NormalFlowLayout.buildTableRows` 同一份
     * [NormalFlowLayout.cellBlocks]，故轻路径的列宽度量与块几何逐值同重路径）。
     *
     * 只物化**静态**部分（文本/样式/runs/`gapBefore`/`edgeH`/`edgeV`）；行高与塑形由
     * `fillTableRowCells` 回填（逐块），故轻路径不必重复断行。
     */
    private fun cellBlockPlan(
        cellEl: MarkupElement,
        classify: orilumn.reader.engine.laying.BlockClassify,
    ): List<orilumn.reader.engine.laying.TableCellBlock> {
        val map = HashMap<MarkupElement, orilumn.reader.engine.css.ComputedStyle>()
        val eng = styleComputer()
        fun reg(el: MarkupElement) {
            map[el] = eng.resolve(el, styleCache)
            for (c in el.children) {
                if (c.tag == "table") continue // 嵌套表独立塑形
                reg(c)
            }
        }
        reg(cellEl)
        return NormalFlowLayout.cellBlocks(cellEl, map, classify, hidden, genOf)
    }

    /** 格的计算样式（懒级联；[cellBlockPlan] 同源）。 */
    private fun cellStyle(cellEl: MarkupElement): orilumn.reader.engine.css.ComputedStyle =
        styleComputer().resolve(cellEl, styleCache)

    /** Materialized [LayoutBox]es for a contiguous range (used as the light chain's drawable leaves). */
    fun blocks(lo: Int, hi: Int): List<LayoutBox> = (lo until hi).map { block(it) }

    /** Style used for an index leaf's box: an anonymous `#text` leaf is a *continuation* of its
     *  container block, so it takes the parent's computed style (mirroring the heavy path, which reuses
     *  the container style for such leaves) — one single source for both paths. */
    private fun blockStyleFor(el: MarkupElement): orilumn.reader.engine.css.ComputedStyle {
        if (el.tag == "#text") {
            val p = el.parent
            if (p != null) return styleComputer().resolve(p, styleCache)
        }
        return styleComputer().resolve(el, styleCache)
    }

    /** A small, lazily-resolved style map covering [markupLeaves[i]]'s paragraph-inline subtree, exactly the
     *  set `shapeGeometry` 的 `emitPlainText` walks for the shaped char stream (display:none gate). An anonymous `#text`
     *  leaf has no subtree — returning an empty map matches the heavy path (the whole-chapter styleMap
     *  holds no entry for synthesized anonymous leaves), so both paths shape the same text. */
    fun inlineStyles(i: Int): Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle> = inlineMaps.getOrPut(i) {
        if (markupLeaves[i].tag == "#text") return@getOrPut emptyMap()
        val map = HashMap<MarkupElement, orilumn.reader.engine.css.ComputedStyle>()
        val engine = styleComputer()
        fun reg(el: MarkupElement) {
            map[el] = engine.resolve(el, styleCache)
            for (c in el.children) if (c.tag != "br" && c.tag !in orilumn.reader.engine.html.BLOCK_TAGS) reg(c)
        }
        reg(markupLeaves[i])
        map
    }

    private fun styleComputer(): StyleComputer = engine

    /** Lazily resolves any element's computed style along its ancestor chain (containers included). */
    fun resolveStyle(el: MarkupElement): orilumn.reader.engine.css.ComputedStyle = styleComputer().resolve(el, styleCache)
}

