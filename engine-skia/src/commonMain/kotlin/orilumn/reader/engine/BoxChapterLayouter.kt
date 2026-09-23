package orilumn.reader.engine

import orilumn.reader.collections.identityMap
import orilumn.reader.collections.withLock
import orilumn.reader.engine.EngineDiag
import orilumn.reader.engine.css.CssBundle
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

/** P13 (U6k): default worker count for chunk-parallel canonical shaping — at least 2, never more
 *  than 4, never stealing the last core (the foreground flip / UI stays on its own core).
 *  R6: CPU 计数经 [platformCpuCount] expect/actual（commonMain 不可见 `Runtime`）。 */
private val DEFAULT_CHUNK_PARALLELISM: Int = maxOf(
    2,
    minOf(4, maxOf(1, platformCpuCount() - 1)),
)

/** R6: 平台 CPU 核数（仅 chunk 并行度启发式用；iOS actual 后续补）。 */
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
 *  cascade → box-model geometry → per-paragraph shaping ([ParagraphShape]s) → [BoxDrawableLayout] → pages.
 *
 * Split into [prepare] (fast, full-chapter, no per-block shaping) and [fullLayout] (shape + paginate).
 * The legacy one-shot [layout] delegates to both in sequence.
 *
 * The cascade's reader upper layer (决策 6) carries the reading preferences that must beat the book
 * (行距 → line-height, 段间距 → paragraph margin), while the book's own styles run through the real
 * cascade underneath.
 */
class BoxChapterLayouter(
    /** Reader-app layer (2nd-highest priority). */
    private val themeSheet: StyleSheet? = null,

    /** Per-item reader-settings layer (highest of the book-overriding layers; vacant for now). */
    private val settingsSheet: StyleSheet? = null,

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
    ): ChapterPrepareResult {
        // P2: 与轻路径同视口的 @media 求值，重轻规则恒一致。
        val authorSheets = parseAuthorSheets(cssBundle, orilumn.reader.engine.css.CssViewport(contentW.coerceAtLeast(1), contentH.coerceAtLeast(1)))
        val engine = styleComputerFor(
            cssBundle, profile,
            authorSheets,
        )
        val styleMap = engine.compute(markup)
        val boxLayouter = BoxLayouter(
            profile.bodyPx,
            // Q1-c：不再双引擎——重排断行统一走 engine-skia SkParagraph 实现。
            orilumn.reader.engine.skia.SkiaParagraphBreaker(profile.letterSpacingEm),
        )
        // Heavy: full-chapter line shaping (real skia break per leaf). Only needed for line-level
        // pagination — small-chapter foreground and large-chapter background canonical. The display gate
        // is shared with the light path so both produce identical leaf sets / globalCharStarts.
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val hidden = orilumn.reader.engine.laying.HiddenCheck { styleMap[it]?.displayNone == true }
        // P3-c: 生成内容 phase-1（ gating 命中才整树求值；伪样式按需缓存，重轻同输入同输出）。
        val genOf = genOfFor(markup, authorSheets, { styleMap[it] }, engine, hidden)
        val structure = boxLayouter.layoutBoxes(markup, contentW, styleMap, classify, genOf = genOf)
        val leaves = collectLeaves(structure.boxes)
        return buildPrepareResult(markup, styleMap, structure, leaves, classify, hidden, genOf)
    }

    /**
     * P3-c: 章节生成内容查找装配（重/轻/桌面同式）。
     *
     * gating（[GeneratedContent.needsPhase]）未命中即 [EmptyGen] 零开销；命中则文档序一遍
     * 求字符串＋伪样式按需缓存。伪样式基址与祖先链两路同源（整表查表 / 懒级联），输出恒等。
     */
    private fun genOfFor(
        markup: MarkupElement,
        sheets: List<orilumn.reader.engine.css.StyleSheet>,
        styleOf: (MarkupElement) -> orilumn.reader.engine.css.ComputedStyle?,
        engine: StyleComputer,
        hidden: orilumn.reader.engine.laying.HiddenCheck,
    ): orilumn.reader.engine.laying.GenOf {
        if (!orilumn.reader.engine.laying.GeneratedContent.needsPhase(sheets)) {
            return orilumn.reader.engine.laying.EmptyGen
        }
        val pseudoCache = HashMap<Pair<MarkupElement, String>, orilumn.reader.engine.css.ComputedStyle?>()
        val pseudoOf: (MarkupElement, String) -> orilumn.reader.engine.css.ComputedStyle? = { el, p ->
            pseudoCache.getOrPut(el to p) {
                val base = styleOf(el) ?: return@getOrPut null
                engine.pseudoStyle(el, orilumn.reader.engine.laying.ancestorsOf(el), base, p)
            }
        }
        val strings = orilumn.reader.engine.laying.GeneratedContent.resolveStrings(markup, styleOf, pseudoOf, hidden::isHidden)
        return orilumn.reader.engine.laying.GeneratedContent.genOf(strings, pseudoOf)
    }

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
    ): LightPrepare = structure.lock.withLock {
        // prepareLight runs concurrently for the same chapter's cache: the open thread's DISK/ANCHOR
        // pass races prewarmForOpen (backgroundDispatcher) and the canonical pass. `key` and
        // `parsedAuthorSheets` are written as two separate stores, so a peer checking only `key` can
        // observe it already matching while `parsedAuthorSheets` is still null and crash on the
        // force-unwrap below. Guard on the payload too, and hold the per-chapter lock across the whole
        // check-build-read so no caller ever sees a half-assembled cache (double parse is benign).
        val structureKey = structureKeyOf(cssBundle, profile.useOriginalStyle, contentW, contentH)
        val rebuild = structure.key != structureKey || structure.parsedAuthorSheets == null
        if (rebuild) {
            structure.key = structureKey
            // P2: 全视口 @media 求值（宽＋高；重轻两路同值，规则恒一致）。
            structure.parsedAuthorSheets = parseAuthorSheets(
                cssBundle,
                orilumn.reader.engine.css.CssViewport(contentW.coerceAtLeast(1), contentH.coerceAtLeast(1)),
            )
        }
        // styleComputerFor reuses the cached parsed author sheets on a hit, so no CSS re-tokenize.
        val engine = styleComputerFor(cssBundle, profile, structure.parsedAuthorSheets!!)
        if (rebuild) {
            computeStructure(markup, engine, structure)
        }
        // `hidden` is a cheap closure; it is only exercised later, lazily, during per-block materialization
        // and its display decisions are typography-invariant, so the cached leaf set stays valid.
        val hidden = hiddenCheckFor(engine)
        LightPrepare(markup, profile, contentW, engine, structure.leaves, structure.globalCharStarts, hidden, imageLoader, chapterHref, structure.leafToBackgroundOwner, structure.leafToBreakInsideAvoidOwner, structure.genStrings)
    }

    /** Parses the chapter's author CSS once into [StyleSheet]s (cached in [ChapterStructureCache]).
     *  P2: `@media` 按视口求值（排版点仅知版心宽，高度恒未知→高度查询丢弃，重轻两路同值）。 */
    private fun parseAuthorSheets(
        cssBundle: CssBundle?,
        viewport: orilumn.reader.engine.css.CssViewport? = null,
    ): List<orilumn.reader.engine.css.StyleSheet> =
        (cssBundle?.cssTexts ?: emptyList()).map { orilumn.reader.engine.css.LightCssParser().parse(it, viewport) }

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
        // 伪样式各 prepare 按新鲜级联懒解）。
        val liteGenOf = if (orilumn.reader.engine.laying.GeneratedContent.needsPhase(structure.parsedAuthorSheets ?: emptyList())) {
            val pseudoCache = HashMap<Pair<MarkupElement, String>, orilumn.reader.engine.css.ComputedStyle?>()
            val litePseudoOf: (MarkupElement, String) -> orilumn.reader.engine.css.ComputedStyle? = { el, p ->
                pseudoCache.getOrPut(el to p) {
                    engine.pseudoStyle(el, orilumn.reader.engine.laying.ancestorsOf(el), engine.resolve(el, styleCache), p)
                }
            }
            val liteStrings = orilumn.reader.engine.laying.GeneratedContent.resolveStrings(
                markup, { e -> engine.resolve(e, styleCache) }, litePseudoOf,
                hidden::isHidden,
            )
            structure.genStrings = liteStrings
            orilumn.reader.engine.laying.GeneratedContent.genOf(liteStrings, litePseudoOf)
        } else {
            structure.genStrings = emptyMap()
            orilumn.reader.engine.laying.EmptyGen
        }
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
        // UI 排版层是最高优先级且永远生效 (行距/段间距/首行缩进/疏密), 含 原书设置.
        val ui = uiSheetFromProfile(profile)
        // 排版主题层 (tier 42): 注入的 themeSheet 优先; 否则按 profile.layoutTheme 加载
        // 资产 CSS (traditional.css / modern.css). 原书设置不起用主题层.
        val theme = when {
            profile.useOriginalStyle -> null
            themeSheet != null -> themeSheet
            else -> themeSheetFromProfile(profile)
        }
        val settings = settingsSheet
        return StyleComputer(profile.bodyPx, ua, sheets, theme, settings, ui, gapScale = profile.paragraphGapScale)
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
                floatLead = leaf.floatLead, classify = prepare.classify, hidden = prepare.hidden)
        }
        return completeFullLayout(prepare, shapes, contentH, profile)
    }

    /**
     * U6k (P13): shape the whole chapter's blocks across parallel chunk workers, then stitch and
     * paginate exactly like [fullLayout]. The parallel work is ONLY the expensive per-block
     * skia shaping — [prepare] (the heavy whole-chapter cascade) already computed the box
     * geometry once, and [completeFullLayout] rebuilds the continuous line flow from that same
     * structure, so the produced slices are **bit-identical to sequential canonical**: the chunk
     * boundary only re-orders WHERE the shaping happens, never the geometry. The single check
     * [checkpoint] (re-invoked per block in every chunk, like the sequential path) lets a background
     * cancel abandon with ~chunk granularity, ≤1 chapter as required by P2/P7.
     *
     * Threading: chunks run as coroutines on `Dispatchers.Default` capped to k-way
     * parallelism (R6, ex daemon-thread pool; no priority API in common). Worker count degrades
     * naturally on fewer cores; parallelism=1 runs in-order on the caller thread (test/debug). [prepare] is
     * read-only across workers (disjoint leaves), so no lock is needed.
     */
    fun fullLayoutChunked(
        prepare: ChapterPrepareResult,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
        parallelism: Int = DEFAULT_CHUNK_PARALLELISM,
        checkpoint: () -> Unit = {},
    ): ChapterLayouter.ChapterLayoutProduct {
        val leaves = prepare.leaves
        val total = leaves.size
        if (total == 0) return fullLayout(prepare, profile, contentW, contentH, checkpoint)
        val k = parallelism.coerceIn(1, total)
        if (k == 1) return fullLayout(prepare, profile, contentW, contentH, checkpoint)
        val chunkSize = (total + k - 1) / k
        val ranges = ArrayList<IntRange>(k)
        for (s in 0 until total step chunkSize) ranges.add(s until minOf(s + chunkSize, total))
        val carriers = firstCarrierLeaves(leaves)
        // R6: 结构化并发替代 Executor/Future——异常（含 checkpoint 的 CancellationException）
        // 经 awaitAll 原样抛出并取消同批兄弟协程，无需 Future 解包；调用线程阻塞等齐
        //（语义同旧 futures.get）。线程优先级 nicety 在 common 无 API，chunk 仍限 k 路，
        // 不与前台抢跑的性质由 limitedParallelism 保持。
        val shapes = runBlocking(Dispatchers.Default.limitedParallelism(k)) {
            ranges.map { rng ->
                async {
                    rng.map { bi ->
                        checkpoint()
                        shapeLeaf(leaves[bi], prepare.styleMap, profile,
                            listMarkerFor(leaves[bi].el, prepare.styleMap::get, carriers), genOf = prepare.genOf,
                            floatLead = leaves[bi].floatLead, classify = prepare.classify, hidden = prepare.hidden)
                    }
                }
            }.awaitAll().flatten()
        }
        return completeFullLayout(prepare, shapes, contentH, profile)
    }

    /** Shared completion of [fullLayout]/[fullLayoutChunked]: line rebuild, drawable, pagination,
     *  block-range backfill — the parts that must stay identical regardless of shaping order. */
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
            profile.fgColor,
            prepare.genOf,
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
                    ),
                )
            }
            val tableWin = orilumn.reader.engine.skia.TableCellLines.expandTable(
                frames, { prepare.styleMap[it] }, profile.letterSpacingEm, profile.fgColor,
                imageLoader, chapterHref,
            )
            BoxDrawableLayout(
                BoxLayoutResult(lines, prepare.structure.boxes), shapes,
                imageLoader, chapterHref, skiaLines = skiaLines,
                tableCells = tableWin.lines, tableCellImages = tableWin.images, tableBorders = tableWin.borders,
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
     */
    private fun backfillBlockRanges(
        slices: List<PageSlice>,
        globalCharStarts: LongArray,
        totalBlocks: Int,
        totalChars: Int,
    ): List<PageSlice> {
        if (globalCharStarts.isEmpty()) return slices
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
        return slices.map { slice ->
            val blockStart = blockOf(slice.charStart.coerceAtLeast(0))
            val blockEnd = if (slice.charEnd >= totalChars) totalBlocks
            else (blockOf((slice.charEnd - 1).coerceAtLeast(0)) + 1).coerceAtMost(totalBlocks)
            val lo = blockStart.coerceAtLeast(0)
            val hi = blockEnd.coerceAtMost(totalBlocks).coerceAtLeast(lo + 1)
            slice.copy(blockStart = lo, blockEndExclusive = hi.coerceAtMost(totalBlocks))
        }
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
        inkColor: Int,
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
            val mono = style.monospace || el.tag == "pre"
            val tag = if (el.isText) el.parent?.tag ?: el.tag else el.tag
            val w = NormalFlowLayout.innerBreakWidth(style, leaf.contentWidth)
            val xLeft = leaf.contentLeft + (style.border.left + style.padding.left).roundToInt()
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
            for (k in 0 until shape.lineCount) {
                val lineIdx = base + k
                val fl = lines.getOrNull(lineIdx) ?: continue
                val s = shape.lineStart(k)
                val e = shape.lineEnd(k)
                if (s < 0 || e < s || e > text.length) continue
                val intruded = lead != null && k < lead.lines
                out[lineIdx] = orilumn.reader.engine.skia.DrawLine(
                    text = text,
                    // lineEnd 是开区间（ParagraphShape 约定）；DrawLine.range 是闭区间（与断行器输出一致），
                    // 此处 s..e 会多含下一行首字、末行直接 substring 越界崩回书架，必须 s until e。
                    range = s until e,
                    yTop = fl.yTop,
                    yBottom = fl.yBottom,
                    alignment = style.textAlign,
                    fontSizePx = style.fontSizePx,
                    lineHeightRatio = style.lineHeightRatio,
                    tag = tag,
                    families = style.fontFamilies,
                    weight = style.fontWeight,
                    italic = style.italic,
                    monospace = mono,
                    letterSpacingEm = letterSpacingEm,
                    lineWidthPx = if (intruded) lead!!.widthPx else w,
                    xLeft = xLeft + if (intruded) lead!!.xOffPx.roundToInt() else 0,
                    listMarker = if (k == 0) marker else null,
                    inkColor = inkColor,
                    colorRuns = shape.colorRuns,
                    fontRuns = shape.fontRuns,
                    firstLineIndentPx = if (k == 0) leaf.style.textIndentPx.coerceAtLeast(0f) else 0f,
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
                    // 下划线随形透传（与 canonical 同源 shape.underlineRuns）。
                    underlineRuns = shape.underlineRuns,
                    // P4-c2: 章内基址（与 canonical 同式；点按命中经它换算章内 char）。
                    charBase = shapeBase,
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
        if (blockLo == Int.MAX_VALUE) blockLo = 0
        if (blockHi == Int.MIN_VALUE) blockHi = 0
        blockLo = blockLo.coerceAtLeast(0)
        blockHi = blockHi.coerceAtMost(prepare.totalBlocks)

        // 3. Shape all blocks in that range (reusing already-shaped blocks from [cache]).
        val localShapes = (blockLo until blockHi).map { tempShape(cache, prepare, it, profile) }

        // 4. Build merged local FlowedLine stream covering all shaped blocks (P6-a2 R6 前视 carry-in).
        val localLines = rebuildLocalLines(prepare, blockLo, blockHi, localShapes) {
            tempShape(cache, prepare, it, profile)
        }

        // 5. Build the drawable covering all shaped pages. It is a BookLayout, so pagination below can
        // use the SAME whole-line fill rule (Paginator.fillWholeLines) the canonical path uses.
        val leavesForDraw = prepare.blocks(blockLo, blockHi)
        val (localFirst, localLast) = localLineRanges(localShapes)
        // Q1-b：本窗口已塑形块投影成 skia DrawLine（局部行序），TextReader 合流——
        // 增量页不再回落旧 StaticLayout 画法。
        val skiaLines = buildPartialSkiaWindow(prepare, leavesForDraw, localFirst, localShapes, localLines, profile.letterSpacingEm, profile.fgColor)
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
                ),
            )
        }
        val incrWin = orilumn.reader.engine.skia.TableCellLines.expandTable(
            incrFrames, prepare::resolveStyle, profile.letterSpacingEm, profile.fgColor,
            imageLoader, chapterHref,
        )
        val drawable = PartialDrawableLayout(
            lines = localLines,
            leafList = leavesForDraw,
            shapeList = localShapes,
            localFirst = localFirst,
            localLast = localLast,
            boxes = buildBackgroundDrawBoxes(prepare, leavesForDraw, localLines, localFirst, localLast),
            loader = imageLoader,
            href = chapterHref,
            avoidOwnerMap = prepare.avoidOwnerMap,
            skiaLinesSource = skiaLines,
            tableCellsSource = incrWin.lines,
            tableCellImagesSource = incrWin.images,
            tableBordersSource = incrWin.borders,
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
            val rePages = Paginator.paginateFrom(drawable, lo, contentH)
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
        val withBlocks = backfillBlockRanges(slicesWithLine, prepare.globalCharStarts, prepare.totalBlocks, prepare.totalChars)

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
    private fun rebuildLocalLines(
        prepare: LightPrepare,
        blockLo: Int,
        blockHi: Int,
        shapes: List<ParagraphShapeRef>,
        /** P6-a2 R6: 前视塑形（lookback 块状态预热用；null = 无前视，窗口即全章）。 */
        shapeLookback: ((Int) -> ParagraphShapeRef)? = null,
    ): List<FlowedLine> {
        val out = ArrayList<FlowedLine>()
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
            val advance = if (prevBlock == null) 0
            else NormalFlowLayout.consecutiveLeafAdvance(prevBlock!!.el!!, leaf.el!!, prepare::resolveStyle)
            val ls = leaf.style
            // P6-a2: 悬浮注册（img/文本；与重路径 emit 同式：零推进＋双侧跨度＋同侧堆叠）。
            // 表格行永不视为悬浮（与重路径 `box.table == null` 守卫同式）。
            val hasSize = (shape.isReplaceable && shape.replaceableBottom > 0) || shape.lineCount > 0
            if (leaf.table == null && ls.floatSide != orilumn.reader.engine.css.FloatSide.NONE && hasSize) {
                // 悬浮盒自身 advance 照常（与重路径 margin 消费同式），再堆叠/注册。
                y += advance
                // 显式 clear 只越指定侧。
                if (ls.clearSide == orilumn.reader.engine.css.ClearSide.LEFT || ls.clearSide == orilumn.reader.engine.css.ClearSide.BOTH) {
                    val b = floatBottomL
                    if (b != null && b > y) y = b
                }
                if (ls.clearSide == orilumn.reader.engine.css.ClearSide.RIGHT || ls.clearSide == orilumn.reader.engine.css.ClearSide.BOTH) {
                    val b = floatBottomR
                    if (b != null && b > y) y = b
                }
                // 相邻同侧堆叠。
                val own = if (ls.floatSide == orilumn.reader.engine.css.FloatSide.LEFT) floatBottomL else floatBottomR
                if (own != null && own > y) y = own
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
                    val spanEnd = cumY + (ls.padding.bottom + ls.border.bottom).roundToInt()
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
            // Mirror emit(): a leaf's contentBottom = last line bottom + its vertical padding/border.
            prevBlock = leaf
            prevContentBottom = cumY + (leaf.style.padding.bottom + leaf.style.border.bottom).roundToInt()
            // P6-a2: 实高行底记账（回填不上越它；与重路径同式）。
            if (prevContentBottom > y + (leaf.style.border.top + leaf.style.padding.top).roundToInt()) {
                lastTextBottom = maxOf(lastTextBottom, prevContentBottom)
            }
            runningChar += leaf.textLength
        }
        return out
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
        val anchorBlock = prepare.blockIndexForChar(anchorChar.coerceAtLeast(0)).coerceIn(0, (total - 1).coerceAtLeast(0))

        // Find the line within the anchor block that contains anchorChar.
        val s0 = tempShape(cache, prepare, anchorBlock, profile)
        val baseChar = prepare.globalCharStarts[anchorBlock].toInt()
        val localAnchor = (anchorChar.coerceAtLeast(baseChar) - baseChar)
        var lIdx = 0
        for (k in 0 until s0.lineCount) if (s0.lineStart(k) <= localAnchor && localAnchor < s0.lineEnd(k)) { lIdx = k; break }

        // LINE-based, MARGIN-AWARE fill from the anchor line (like forward pages): accumulate content line
        // heights and inter-block margin advances until contentH, cutting mid-block. The page's first
        // block starts at the anchor line (no leading top margin); between blocks the margin collapse
        // applies; the page's last block's bottom margin merges into the page bottom edge.
        val shapes = ArrayList<ParagraphShapeRef>()
        var acc = 0
        var endBlockExclusive = anchorBlock
        var cutBlock = -1
        var cutLine = -1
        var b = anchorBlock
        var firstInPage = true
        while (b < total) {
            if (!firstInPage) {
                val prevLeaf = prepare.block(b - 1)
                val thisLeaf = prepare.block(b)
                val advance = NormalFlowLayout.consecutiveLeafAdvance(prevLeaf.el!!, thisLeaf.el!!, prepare::resolveStyle)
                // prev bottom edges + margin collapse + THIS leaf's own top edges: exactly the vertical
                // span rebuildLocalLines now gives between prev's last line and this leaf's first line
                // (which sits at contentTop + this leaf's top border/padding). Mirrors heavy pagination,
                // which counts a pre block's top padding in its occupied page height.
                acc += (prevLeaf.style.padding.bottom + prevLeaf.style.border.bottom).roundToInt() + advance +
                    (thisLeaf.style.border.top + thisLeaf.style.padding.top).roundToInt()
            }
            firstInPage = false
            val s = tempShape(cache, prepare, b, profile)
            shapes.add(s)
            val k0 = if (b == anchorBlock) lIdx.coerceAtLeast(0) else 0
            // P4-a3: 悬浮 img 零高（字符槽照占、页高不计；与重路径分页同式）。
            val aLeaf = prepare.block(b)
            val aFloat = aLeaf.table == null && s.isReplaceable && aLeaf.style.floatSide != orilumn.reader.engine.css.FloatSide.NONE
            if (!aFloat) {
            for (k in k0 until s.lineCount) {
                // Every shaped line equals the CSS line box (uniform across first/interior/last), so a
                // page fills by exactly the drawn pixels per line, never over/under-estimating.
                val h = (s.lineBottom(k) - s.lineTop(k)).coerceAtLeast(1)
                if (acc + h > contentH) { cutBlock = b; cutLine = k; break }
                acc += h
            }
            }
            if (cutLine >= 0) { endBlockExclusive = b + 1; break }
            endBlockExclusive = b + 1
            b++
        }
        val ecs = if (cutBlock >= 0) {
            val sc = tempShape(cache, prepare, cutBlock, profile)
            prepare.globalCharStarts[cutBlock].toInt() + (sc.lineStart(cutLine) - sc.lineStart(0))
        } else null
        val page = assembleTempPage(prepare, anchorBlock, endBlockExclusive, shapes, pageFirstLine = lIdx.coerceAtLeast(0), endLineCharStart = ecs, contentH = contentH) {
            tempShape(cache, prepare, it, profile)
        }
        diagPage(contentH, page, "anchor")
        val nb = if (cutLine >= 0) cutBlock else endBlockExclusive
        val nl = if (cutLine >= 0) cutLine else 0
        tempDiag("ANCHOR", "in anchorChar=$anchorChar anchorBlk=$anchorBlock lIdx=$lIdx", page, "cut=${if (cutLine >= 0) "blk$cutBlock/line$cutLine" else "-"} next=($nb,$nl)")
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
        // LINE-based, MARGIN-AWARE fill from (startBlock, startLine). Accumulate content line heights,
        // and between consecutive blocks add the inter-block advance `rebuildLocalLines`/emit use:
        // previous padding/border bottom + the margin-collapse (neighbours take the larger margin). A
        // page's FIRST block's top margin is excluded (it merges into the page top edge), and a page's
        // LAST block's bottom margin is excluded (merges into the page bottom edge). Half-blocks on a
        // page boundary carry no extra margin at the seam.
        val shapes = ArrayList<ParagraphShapeRef>()
        var acc = 0
        var endBlockExclusive = startBlock
        var cutBlock = -1
        var cutLine = -1
        var b = startBlock
        var firstInPage = true
        while (b < total) {
            if (!firstInPage) {
                // Inter-block gap: prev content-bottom → this content-top.
                val prevLeaf = prepare.block(b - 1)
                val thisLeaf = prepare.block(b)
                val advance = NormalFlowLayout.consecutiveLeafAdvance(prevLeaf.el!!, thisLeaf.el!!, prepare::resolveStyle)
                // prev bottom edges + margin collapse + THIS leaf's own top edges: exactly the vertical
                // span rebuildLocalLines now gives between prev's last line and this leaf's first line
                // (which sits at contentTop + this leaf's top border/padding). Mirrors heavy pagination,
                // which counts a pre block's top padding in its occupied page height.
                acc += (prevLeaf.style.padding.bottom + prevLeaf.style.border.bottom).roundToInt() + advance +
                    (thisLeaf.style.border.top + thisLeaf.style.padding.top).roundToInt()
            }
            firstInPage = false
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
                if (acc + h > contentH) { cutBlock = b; cutLine = k; break }
                acc += h
            }
            }
            if (cutLine >= 0) { endBlockExclusive = b + 1; break }
            endBlockExclusive = b + 1
            b++
        }
        val ecs = if (cutBlock >= 0) {
            val s = tempShape(cache, prepare, cutBlock, profile)
            prepare.globalCharStarts[cutBlock].toInt() + (s.lineStart(cutLine) - s.lineStart(0))
        } else null
        val page = assembleTempPage(prepare, startBlock, endBlockExclusive, shapes, pageFirstLine = startLine.coerceAtLeast(0), endLineCharStart = ecs, contentH = contentH) {
            tempShape(cache, prepare, it, profile)
        }
            ?: return null
        diagPage(contentH, page, "fwd")
        val nb = if (cutLine >= 0) cutBlock else endBlockExclusive
        val nl = if (cutLine >= 0) cutLine else 0
        tempDiag("FWD", "in blk=$startBlock line=$startLine", page, "cut=${if (cutLine >= 0) "blk$cutBlock/line$cutLine" else "-"} next=($nb,$nl)")
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
     */
    fun shapeTempPageBackward(
        prepare: LightPrepare,
        profile: TypographicProfile,
        contentW: Int,
        contentH: Int,
        endExclusive: Int,
        endLineCharStart: Int,
        cache: MutableMap<Int, ParagraphShapeRef>?,
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
        while (b >= 0) {
            val leaf = prepare.block(b)
            val s = tempShape(cache, prepare, b, profile)
            allShapes.add(0, s)  // prepend — we walk backward, but shapes must stay in visual order

            // Inter-block gap: above block(b) padding/border bottom + margin collapse with block(b+1).
            if (!firstInPage) {
                val belowLeaf = prepare.block(b + 1)
                val advance = NormalFlowLayout.consecutiveLeafAdvance(leaf.el!!, belowLeaf.el!!, prepare::resolveStyle)
                // Inter-block gap between b and b+1 = b's bottom edges + margin collapse + the block
                // below's own top edges (its first line sits at contentTop + its top border/padding after
                // the rebuildLocalLines fix). Mirrors heavy pagination counting a padded block's top padding.
                acc += (leaf.style.padding.bottom + leaf.style.border.bottom).roundToInt() + advance +
                    (belowLeaf.style.border.top + belowLeaf.style.padding.top).roundToInt()
            }
            firstInPage = false

            // Bottom block's tailing lines may belong to the anchor page (endLineCharStart >= 0).
            val maxLine = if (b == endExclusive - 1 && endLineCharStart >= 0) {
                val baseChar = prepare.globalCharStarts[b].toInt()
                val localEnd = endLineCharStart - baseChar
                (0 until s.lineCount).firstOrNull { s.lineStart(it) >= localEnd } ?: s.lineCount
            } else s.lineCount

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
                if (acc + h > contentH) { overflowLine = k; break }
                acc += h
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
        val shapes = allShapes.takeLast(endExclusive - startBlock)
        val backPage = assembleTempPage(prepare, startBlock, endExclusive, shapes,
            pageFirstLine = startLine, endLineCharStart = if (endLineCharStart >= 0) endLineCharStart else null,
            contentH = contentH) {
            tempShape(cache, prepare, it, profile)
        }
        tempDiag("BWD", "in endExcl=$endExclusive lineCut=$endLineCharStart", backPage,
            "truncated=$truncated start=($startBlock,$startLine)")
        return backPage
    }

    /** Shapes one block's [ParagraphShape] for the temp-page path. */
    private fun shapeBlock(
        prepare: LightPrepare,
        i: Int,
        leaf: LayoutBox,
        profile: TypographicProfile,
    ): ParagraphShapeRef = shapeLeaf(
        leaf, prepare.inlineStyles(i), profile,
        listMarkerFor(leaf.el, prepare::resolveStyle, prepare.firstCarrierLeaves),
        // P3-a: 内联表只含子树，祖先 opacity 经懒级联回退（与重路径整表同值）。
        ancestorStyleOf = prepare::resolveStyle,
        // P3-c: 生成内容与塑形同一 phase-1 结果。
        genOf = prepare.genOf,
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
    )

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
    ): Int {
        // 行高只取本行「rowspan==1」格的最大外高：跨行格的高度由其跨越的各行**分摊**
        // （[TableGridModel.resolveRowHeights]，浏览器实测口径），故此处不把跨行格整体压进
        // 本行——否则 rowspan 表首行虚高、rowspan==1 格下方留白。每格外高写回
        // [TableCellLayout.height]，供后续 [applyTableRowspanHeights] 分摊读取。
        var rowH = leaf.replaceableHeight.coerceAtLeast(1)
        for (cell in t.cells) {
            val cs = styles[cell.el] ?: leaf.style
            val cw = NormalFlowLayout.innerBreakWidth(cs, cell.width)
            val cs_ = shapeGeometry(cell.el, cs, styles, profile, cw, imageLoader = imageLoader, chapterHref = chapterHref, ancestorStyleOf = ancestorStyleOf, genOf = genOf, classify = classify, hidden = hidden, isBlock = { it.tag in BLOCK_TAGS })
            cell.shape = cs_
            val cellH = if (cs_.lineCount > 0) (cs_.lineBottom(cs_.lineCount - 1) - cs_.lineTop(0)) else 0
            cell.height = (cellH + (cs.padding.vertical + cs.border.vertical).roundToInt()).coerceAtLeast(1)
            if (cell.rowSpan <= 1) rowH = maxOf(rowH, cell.height)
        }
        return rowH
    }

    /**
     * Shapes one leaf into a [ParagraphShape]. A table-row leaf fills its cells' shapes (each shaped
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
    ): ParagraphShapeRef {
        val t = leaf.table
        if (t != null) {
            val rowH = fillTableRowCells(t = t, leaf = leaf, styles = styles, profile = profile, ancestorStyleOf = ancestorStyleOf, genOf = genOf, classify = classify, hidden = hidden)
            return ShapedGeometry(isReplaceable = true, replaceableBottom = rowH.coerceAtLeast(1), replaceableCharEnd = leaf.textLength.coerceAtLeast(1))
        }
        return shapeGeometry(
            leaf.el ?: orilumn.reader.engine.html.MarkupElement("body"), leaf.style, styles,
            profile, breakWidthOverride ?: breakWidthPx(leaf), listMarker,
            imageLoader = imageLoader, chapterHref = chapterHref, ancestorStyleOf = ancestorStyleOf, genOf = genOf,
            floatLead = floatLead, classify = classify, hidden = hidden, isBlock = { it.tag in BLOCK_TAGS },
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
    private fun tempShape(
        cache: MutableMap<Int, ParagraphShapeRef>?,
        prepare: LightPrepare,
        i: Int,
        profile: TypographicProfile,
    ): ParagraphShapeRef {
        val hit = cache?.get(i)
        if (hit != null) {
            // 表格行命中塑形缓存也必须回填当前实例的格 shape：格 shape 只活在叶实例上
            // （[fillTableRowCells]），而 `prepareLight` 每次重建都产出新叶实例；只复用行
            // shape 会让展开阶段拿到全空 shape（[TableCellLines.emitCell] 静默整表丢弃）。
            val leaf = prepare.block(i)
            val t = leaf.table
            if (t != null) {
                fillTableRowCells(t = t, leaf = leaf, styles = prepare.inlineStyles(i), profile = profile, ancestorStyleOf = prepare::resolveStyle, genOf = prepare.genOf, classify = prepare.lightClassify(), hidden = prepare.hidden)
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
        val total = prepare.totalBlocks
        val rawLines = rebuildLocalLines(prepare, blockStart, blockEndExclusive, shapes, shapeLookback)
        // Normalize the temp page's line y coordinates to start at the page's first line = 0. This
        // removes any dependence on the blocks' absolute content offsets (a mid-chapter anchor page can
        // start far down the chapter), so the page always renders top-aligned regardless of those offsets.
        val base = rawLines.getOrNull(pageFirstLine.coerceAtLeast(0))?.yTop ?: 0
        val lines = if (base == 0) rawLines else rawLines.map {
            FlowedLine(it.charStart, it.charEnd, it.yTop - base, it.yBottom - base, it.paragraphStart)
        }
        val leavesForDraw = prepare.blocks(blockStart, blockEndExclusive)
        val (localFirst, localLast) = localLineRanges(shapes)
        // Q1-b：临时页同样投影 skia 窗口（页内局部行序，行 y 已归一化到页首行 = 0）——
        // ReaderScreen 合流绘制，不再回落旧 StaticLayout 画法。
        val skiaLines = buildPartialSkiaWindow(prepare, leavesForDraw, localFirst, shapes, lines, prepare.profile.letterSpacingEm, prepare.profile.fgColor)
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
                ),
            )
        }
        val tempWin = orilumn.reader.engine.skia.TableCellLines.expandTable(
            tempFrames, prepare::resolveStyle, prepare.profile.letterSpacingEm, prepare.profile.fgColor,
            imageLoader, chapterHref,
        )
        val layout = PartialDrawableLayout(
            lines = lines,
            leafList = leavesForDraw,
            shapeList = shapes,
            localFirst = localFirst,
            boxes = buildBackgroundDrawBoxes(prepare, leavesForDraw, lines, localFirst, localLast),
            loader = imageLoader,
            href = chapterHref,
            skiaLinesSource = skiaLines,
            tableCellsSource = tempWin.lines,
            tableCellImagesSource = tempWin.images,
            tableBordersSource = tempWin.borders,
        )
        var lastLine = if (lines.isEmpty()) 0 else lines.size
        var charEnd = if (blockEndExclusive < total) prepare.globalCharStarts[blockEndExclusive].toInt()
            else prepare.totalChars
        if (endLineCharStart != null) {
            val cut = rawLines.indexOfFirst { it.charStart == endLineCharStart }
            lastLine = if (cut >= 0) cut else lines.size
            charEnd = endLineCharStart
        }
        // Guarantee the page never exceeds the content area: back off trailing lines that would push
        // the page's vertical extent past contentH (which would clip a bottom line). This only ever
        // shrinks a page, never grows it, and keeps the page's char range consistent with what's shown.
        val pageTop = lines.getOrNull(pageFirstLine.coerceAtLeast(0))?.yTop ?: 0
        while (lastLine > pageFirstLine + 1 && lastLine - 1 in lines.indices && lines[lastLine - 1].yBottom - pageTop > contentH) {
            lastLine--
        }
        if (lastLine - 1 in lines.indices) {
            val lastActualEnd = lines[lastLine - 1].charEnd
            if (charEnd > lastActualEnd) charEnd = lastActualEnd
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
            blockEndExclusive = blockEndExclusive,
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
     * Rebuilds the whole-chapter line stream from each leaf's real [ParagraphShape], preserving the
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
     * The reader-app (UI) layer stylesheet — highest book-overriding layer (fixed tier). It enforces
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
     * 叶边框盒（无背景填充，只画自身边框），且一律排在背景盒之后绘制——与重路径“先祖先背景、
     * 后子孙边框”同序，否则容器底会盖掉标题下边框。
     */
    private fun buildBackgroundDrawBoxes(
        prepare: LightPrepare,
        leafList: List<LayoutBox>,
        lines: List<FlowedLine>,
        firstByBlock: IntArray,
        lastByBlock: IntArray,
    ): List<LayoutBox> {
        if (leafList.isEmpty() || lines.isEmpty()) return emptyList()
        // A "self-owned" owner is the background bearing LEAF itself (a `pre`/`p.filename` with its own
        // background): its aggregated extent already counts its own top+bottom edges once, so the band
        // must NOT be extended by them again. Container owners (blockquote/.rust-example-rendered) are
        // not self-owned and DO extend by their own edges (their lines span only the content area).
        val aggTop = HashMap<MarkupElement, Int>()
        val ownerBottom = HashMap<MarkupElement, Int>()
        val ownerFirst = HashMap<MarkupElement, Int>()
        val ownerLast = HashMap<MarkupElement, Int>()
        val selfOwned = HashSet<MarkupElement>()
        for (i in leafList.indices) {
            val leaf = leafList[i]
            val lo = firstByBlock[i].coerceAtLeast(0)
            val hi = lastByBlock[i]
            if (lo >= lines.size || hi <= lo) continue
            // Mirror NormalFlowLayout.emit's leaf branch: a leaf's border box = its text lines ± its own
            // padding/border (counted once, already including the top inset rebuildLocalLines applies).
            val top = lines[lo].yTop - (leaf.style.border.top + leaf.style.padding.top).roundToInt()
            val bottom = lines[minOf(hi, lines.size) - 1].yBottom + (leaf.style.border.bottom + leaf.style.padding.bottom).roundToInt()
            val owner = leaf.el?.let { prepare.backgroundOwnerMap[it] } ?: continue
            if (owner === leaf.el) selfOwned.add(owner)
            aggTop[owner] = minOf(aggTop[owner] ?: top, top)
            ownerBottom[owner] = maxOf(ownerBottom[owner] ?: bottom, bottom)
            ownerFirst[owner] = minOf(ownerFirst[owner] ?: lo, lo)
            ownerLast[owner] = maxOf(ownerLast[owner] ?: hi, hi)
        }
        val out = ArrayList<LayoutBox>(aggTop.size)
        for ((owner, top) in aggTop) {
            val box = ownerBackgroundBox(owner, prepare)
            val s = box.style
            val (bandTop, bandBottom) = NormalFlowLayout.backgroundBandExtent(
                ownerTop = top,
                ownerBottom = ownerBottom[owner] ?: top,
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
            val lo = firstByBlock[i].coerceAtLeast(0)
            val hi = lastByBlock[i]
            if (lo >= lines.size || hi <= lo) continue
            val top = lines[lo].yTop - (leaf.style.border.top + leaf.style.padding.top).roundToInt()
            val bottom = lines[minOf(hi, lines.size) - 1].yBottom + (leaf.style.border.bottom + leaf.style.padding.bottom).roundToInt()
            if (bottom <= top) continue
            val borderBox = LayoutBox(
                el = el, style = leaf.style, contentLeft = leaf.contentLeft, contentWidth = leaf.contentWidth,
                ranges = emptyList(), textLength = 0, lineHeights = emptyList(), childBoxes = emptyList(),
            )
            borderBox.contentTop = top
            borderBox.contentBottom = bottom
            borderBox.firstLineIndex = lo
            borderBox.lastLineExclusive = hi
            out.add(borderBox)
        }
        return out
    }

    /** Builds a [LayoutBox] for a background owner: its resolved style + horizontal box geometry. */
    private fun ownerBackgroundBox(el: MarkupElement, prepare: LightPrepare): LayoutBox {
        val style = prepare.resolveStyle(el)
        val contentWidth = NormalFlowLayout.descendContentWidth(el, prepare.layoutContentWidth) { e ->
            val s = prepare.resolveStyle(e)
            (s.padding.horizontal + s.border.horizontal).roundToInt()
        }.coerceAtLeast(1)
        val contentLeft = NormalFlowLayout.descendContentLeft(el, 0,
            leftEdgesOf = { e -> val s = prepare.resolveStyle(e); (s.border.left + s.padding.left).roundToInt() },
            marginLeftOf = { e -> val s = prepare.resolveStyle(e); s.margin.left.roundToInt() },
        )
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

    override fun getLineTop(i: Int): Int = lines[i].yTop
    override fun getLineBottom(i: Int): Int = lines[i].yBottom
    override fun getLineStart(i: Int): Int = lines[i].charStart
    override fun getLineEnd(i: Int): Int = lines[i].charEnd
    override fun isParagraphBoundaryLine(i: Int): Boolean = lines[i].paragraphStart

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
}

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
) {
    val totalBlocks: Int get() = markupLeaves.size
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
    // P1-2: 与 computeStructure 的 globalCharStarts 同式（样式化归一长度）。
    val totalChars: Int get() = markupLeaves.sumOf { NormalFlowLayout.styledCharAdvance(it, { e -> styleComputer().resolve(e, styleCache) }, lightClassify(), hidden, genOf).toInt() }

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
     */
    private val floatData: Pair<List<orilumn.reader.engine.laying.FloatLead?>, List<Int?>> by lazy {
        computeFloatLeads()
    }
    val floatLeads: List<orilumn.reader.engine.laying.FloatLead?> get() = floatData.first
    /** P6-a2: 悬浮实宽表（与 floatLeads 同一前向透传产物；右对齐/塑形宽同源，零重算）。 */
    val floatWidths: List<Int?> get() = floatData.second

    fun floatLeadAt(blockIdx: Int): orilumn.reader.engine.laying.FloatLead? {
        if (totalBlocks <= 0) return null
        return floatLeads[blockIdx.coerceIn(0, totalBlocks - 1)]
    }

    /**
     * P6-a2 eager 环绕透传：文档序单遍，复刻重路径 build 期 float 语义
     * （注册/消耗/收窄同 helper；塑形同 breaker 同输入，高度恒等，故 lead 恒等）。
     * 无悬浮章节恒全空（仅样式扫描，零塑形开销）。
     */
    private fun computeFloatLeads(): Pair<List<orilumn.reader.engine.laying.FloatLead?>, List<Int?>> {
        val n = totalBlocks
        var anyFloat = false
        for (i in 0 until n) {
            if (blockStyleFor(markupLeaves[i]).floatSide != orilumn.reader.engine.css.FloatSide.NONE) {
                anyFloat = true
                break
            }
        }
        if (!anyFloat) return List(n) { null } to List(n) { null }
        val breaker = orilumn.reader.engine.skia.SkiaParagraphBreaker(profile.letterSpacingEm)
        val pending = NormalFlowLayout.FloatPending()
        val out = ArrayList<orilumn.reader.engine.laying.FloatLead?>(n)
        val widths = ArrayList<Int?>(n)
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
            val cw = NormalFlowLayout.descendContentWidth(el, contentW) { e ->
                val s = styleComputer().resolve(e, styleCache)
                (s.padding.horizontal + s.border.horizontal).roundToInt()
            }.coerceAtLeast(1)
            if (el.tag == "table" || el.tag == "tr" || el.tag == "#text") {
                // 表格分支清零（重路径 table 分支同式）；合成匿名恒空（重路径匿名分支同式）。
                if (el.tag != "#text") pending.dropAll()
                out.add(null)
                widths.add(null)
                continue
            }
            if (NormalFlowLayout.isReplaceable(el)) {
                if (style.floatSide != orilumn.reader.engine.css.FloatSide.NONE) {
                    val (fw, fh) = NormalFlowLayout.replacedUsedSize(
                        el, style, NormalFlowLayout.innerBreakWidth(style, cw), imageLoader, chapterHref,
                    )
                    pending.register(style.floatSide, fw.coerceAtLeast(1), fh)
                    widths.add(fw.coerceAtLeast(1))
                } else {
                    pending.dropAll()
                    widths.add(null)
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
                val runs = NormalFlowLayout.leafFontRuns(el, sub, classify, hidden, genOf)
                val floatW = NormalFlowLayout.floatTextWidth(style, text, cw, breaker, runs)
                val breakWf = NormalFlowLayout.innerBreakWidth(style, floatW)
                val shaped = breakWrappedLines(
                    breaker, text, style, breakWf, null, el.tag, runs,
                    style.textIndentPx.coerceAtLeast(0f),
                    NormalFlowLayout.leafBaselineShifts(el, sub, classify, hidden, genOf),
                )
                val baseH = adjustLineHeightsForInlineImages(
                    text, shaped, el, sub, classify, hidden, breakWf, imageLoader, chapterHref,
                )
                // P6-b: 叠排注音行增高（与重路径同式；无注音零回归）。
                val grownH = orilumn.reader.engine.laying.adjustLineHeightsForRuby(
                    shaped, baseH, NormalFlowLayout.leafRubyRuns(el, sub, classify, hidden, genOf),
                )
                val h = grownH.sumOf { it.coerceAtLeast(1) }
                pending.register(style.floatSide, floatW, h)
                widths.add(floatW)
                out.add(null)
                continue
            }
            val lead = NormalFlowLayout.pendingLeadFor(pending, style, cw)
            out.add(lead)
            widths.add(null)
            val text = NormalFlowLayout.absorbStyled(el, sub, classify, hidden, genOf).text
            val runs = NormalFlowLayout.leafFontRuns(el, sub, classify, hidden, genOf)
            val shaped = breakWrappedLines(
                breaker, text, style, NormalFlowLayout.innerBreakWidth(style, cw), lead, el.tag, runs,
                style.textIndentPx.coerceAtLeast(0f),
                NormalFlowLayout.leafBaselineShifts(el, sub, classify, hidden, genOf),
            )
            val baseH = adjustLineHeightsForInlineImages(
                text, shaped, el, sub, classify, hidden, NormalFlowLayout.innerBreakWidth(style, cw), imageLoader, chapterHref,
            )
            // P6-b: 叠排注音行增高（与重路径同式；无注音零回归）。
            val grownH = orilumn.reader.engine.laying.adjustLineHeightsForRuby(
                shaped, baseH, NormalFlowLayout.leafRubyRuns(el, sub, classify, hidden, genOf),
            )
            val h = grownH.sumOf { it.coerceAtLeast(1) } + style.margin.top.coerceAtLeast(0f).roundToInt()
            pending.consume(h)
        }
        return out to widths
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

    /** Materializes (and caches) index [i]'s [LayoutBox] — style via lazy cascade, content width via the
     *  container chain — without ever cascading the whole chapter. */
    fun block(i: Int): LayoutBox {
        materialized[i]?.let { return it }
        val el = markupLeaves[i]
        val style = blockStyleFor(el)
        val contentWidth = NormalFlowLayout.descendContentWidth(el, contentW) { e ->
            val s = styleComputer().resolve(e, styleCache)
            (s.padding.horizontal + s.border.horizontal).roundToInt()
        }
        // Horizontal position = the accumulated block left edges (mirrors the heavy path's descent).
        val contentLeft = NormalFlowLayout.descendContentLeft(el, 0,
            leftEdgesOf = { e -> val s = styleComputer().resolve(e, styleCache); (s.border.left + s.padding.left).roundToInt() },
            marginLeftOf = { e -> val s = styleComputer().resolve(e, styleCache); s.margin.left.roundToInt() },
        )
        val replaceable = NormalFlowLayout.isReplaceable(el)
        val isTr = el.tag == "tr"
        // P6-a2: 右悬浮右对齐（宽取 eager 表，与重路径同源，零重算）。
        var boxLeft = contentLeft
        if (el.tag != "#text" && style.floatSide == orilumn.reader.engine.css.FloatSide.RIGHT) {
            val fw = floatWidths[i]
            if (fw != null && fw > 0) boxLeft = contentLeft + maxOf(0, contentWidth - fw)
        }
        val box = LayoutBox(
            el = el, style = style,
            contentLeft = boxLeft,
            contentWidth = contentWidth,
            ranges = emptyList(),
            // P1-2: 与重路径盒 textLength 同式（样式化归一长度）。
            textLength = (if (replaceable) 1 else NormalFlowLayout.styledCharAdvance(el, { e -> styleComputer().resolve(e, styleCache) }, lightClassify(), hidden, genOf)).toInt(),
            lineHeights = emptyList(),
            childBoxes = emptyList(),
            replaceableHeight = if (replaceable) {
                NormalFlowLayout.replaceableHeightOf(el, style, NormalFlowLayout.innerBreakWidth(style, contentWidth), imageLoader, chapterHref)
            } else 0,
            table = if (isTr) tableRowLayoutFor(el, contentWidth) else null,
            // P6-a2: 环绕前导取全章 eager 表（与重路径 pending 透传同式；匿名/替换/表格叶恒空）。
            floatLead = floatLeads[i],
        )
        materialized[i] = box
        return box
    }

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
                    val styled = cellStyledText(cell.el, classify)
                    prefs.add(
                        NormalFlowLayout.tableCellPref(
                            tableBreaker, cell.col, cell.colSpan, styled.text, styled.style, cell.el.tag, styled.runs,
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
            orilumn.reader.engine.laying.TableCellLayout(it.el, it.col, it.colSpan, xs.getOrElse(it.col) { rowLeft }, outer, 0, it.isHeader, null, it.rowSpan)
        }
        return orilumn.reader.engine.laying.TableRowLayout(xs, ws, cells, tstyle.emptyCellsHide)
    }

    /** 同表列几何缓存（表元素 → 宽/位快照＋列 x/宽）。 */
    private class CachedTableCols(val tableW: Int, val rowLeft: Int, val xs: IntArray, val ws: IntArray)

    private val tableColCache = HashMap<MarkupElement, CachedTableCols>()

    /**
     * auto 分列表度量用断行器（与塑形/绘制同 profile：同 [TypographicProfile.letterSpacingEm]、
     * 同共用字库），首次用时构造；量画同理，绝不另起一套度量。
     */
    private val tableBreaker by lazy { orilumn.reader.engine.skia.SkiaParagraphBreaker(profile.letterSpacingEm) }

    /** 单元格归一文本＋自身样式＋行内 face 段（auto 测宽用；子树懒级联，与重路径同吸收/同 run 口径）。 */
    private class CellStyled(
        val text: String,
        val style: orilumn.reader.engine.css.ComputedStyle,
        val runs: List<orilumn.reader.engine.css.FontRun>,
    )

    private fun cellStyledText(cellEl: MarkupElement, classify: orilumn.reader.engine.laying.BlockClassify): CellStyled {
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
        val st = map[cellEl] ?: eng.resolve(cellEl, styleCache)
        val text = NormalFlowLayout.absorbStyled(cellEl, map, classify, hidden, genOf).text
        return CellStyled(text, st, NormalFlowLayout.leafFontRuns(cellEl, map, classify, hidden, genOf))
    }

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
     *  set `ParagraphShapes.emitPlainText` walks for the shaped char stream (display:none gate). An anonymous `#text`
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

