package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.projectDecor
import orilumn.reader.io.Logger
import kotlin.time.TimeSource

/**
 * 旁注布点的一页缓存（最近几页）。
 *
 * 布点是本页最贵的一笔（实测 100~900ms，冷启动能到 4s 量级），而一页在一次翻页里会被
 * 求两遍：起手时它先当「目标槽」，落地又变成「当前槽」。留几页结果，同一页就只算一次。
 *
 * 键由调用方给（页身份），失效由 [PageNoteMemo] 的持有者负责：`remember` 的键一变就整只换新。
 * **只在主线程读写**（后台车道算完交回主线程再写，见 [PageNoteLane]）。
 */
internal class PageNoteMemo(private val capacity: Int = 6) {

    private val entries = LinkedHashMap<String, List<NoteBubble>>()

    /** 取一页的结果；取到就算「刚用过」（挪到队尾，少被挤掉）。 */
    fun get(key: String): List<NoteBubble>? {
        val value = entries.remove(key) ?: return null
        entries[key] = value
        return value
    }

    /** 只问「有没有」，**不动**最近使用序（预热前判重，不该把冷页顶成热页）。 */
    fun contains(key: String): Boolean = entries.containsKey(key)

    fun put(key: String, value: List<NoteBubble>) {
        entries.remove(key)
        if (entries.size >= capacity) {
            entries.keys.firstOrNull()?.let { entries.remove(it) }
        }
        entries[key] = value
    }
}

/**
 * 旁注布点的**后台车道**（用户层）。
 *
 * ## 为什么必须离开 UI 线程（2026-11-01 真机：翻页「拉出一点点就卡一下」）
 *
 * 布点是本页最贵的一笔：几何 30ms + 布点 100~650ms（13 条批注时 `route` 一项就 300ms 上下，
 * 试过的引线走法两万多次）。早先它在 `remember` 里同步算，而**目标页的内容是在起手那一刻
 * 才出现的** ⇒ 手指刚把页面拉出一点，这一帧就撞上这几百毫秒：画面停住，然后才继续滑。
 *
 * 所以整条流水线搬到一条后台线程上跑，UI 帧只负责把结果摆出来。车道上的一切都必须是
 * **这条车道独占的实例**：
 * - [drawer]：几何测量用的绘制器自带可变态缓存（对齐器 / 落墨器 / kerning 表），与前台
 *   `selectionDrawer` 共用会在两条线程上同时改它；
 * - [textSizer]：文字实测的缓存与统计同理，只能由一条线程问。
 *
 * 同一时刻只有一条线程在跑（[dispatcher] 限并发 1），所以这两只实例永远不并发。
 * 车道算完把结果交回主线程写进 [PageNoteMemo]，一次页面的结果只算一遍。
 */
internal class PageNoteLane(
    private val drawer: LineWindowDrawer,
    private val textSizer: NoteTextSizer,
) {
    /**
     * 车道本身：后台线程、**限并发 1**。
     *
     * 限 1 不是为了省 CPU，而是为了让 [drawer] / [textSizer] 只有一个使用者；
     * 顺带把「快速连翻时同时算好几页」变成「排队」，代价只是后面的页晚一点出旁注。
     */
    val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    fun compute(
        content: PageContent,
        chapter: Int,
        charStart: Int,
        charEnd: Int,
        annotations: List<Annotation>,
        contentLeft: Float,
        contentTop: Float,
        contentRight: Float,
        contentBottom: Float,
        pageWidth: Float,
        pageHeight: Float,
        fontScale: Float,
    ): List<NoteBubble> = computePageNotes(
        content = content,
        chapter = chapter,
        charStart = charStart,
        charEnd = charEnd,
        annotations = annotations,
        drawer = drawer,
        contentLeft = contentLeft,
        contentTop = contentTop,
        contentRight = contentRight,
        contentBottom = contentBottom,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        textSizer = textSizer,
        fontScale = fontScale,
    )
}

/**
 * 一页的旁注布点（D-26）：批注是标注上的一句话，就摆在它旁边。
 *
 * 纯覆盖层：只按行几何算空白，不改排版、不进栅格。**跟着页走**——翻页时它被放进
 * [FlipSlideLayer] 的两槽里，和页画布同一个平移量：当前页的旁注滑出去、目标页的旁注
 * 跟着滑进来。早先整层在动画期间藏起来、落地才冒出来，观感是「字滑走了、旁注原地
 * 消失又出现」（2026-11-01 用户报的闪烁）。
 *
 * **算在后台车道**（[lane]），UI 线程只读结果：缓存里有就当场给（翻页起手、落地都在这里命中），
 * 没有就返回空表、算完再由状态推上来——**绝不在帧里同步算**，那是「拉出一点点就卡一下」的来源。
 *
 * 文字尺寸用与绘制同源的实测尺子（不是字宽粗估）：差半像素就会被省略号截掉。
 */
@Composable
internal fun rememberPageNotes(
    content: PageContent?,
    chapter: Int,
    charStart: Int,
    charEnd: Int,
    annotations: List<Annotation>,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageWidth: Float,
    pageHeight: Float,
    fontScale: Float,
    lane: PageNoteLane,
    memo: PageNoteMemo,
): List<NoteBubble> {
    // 页身份键：章 + 页区间（见 [pageNoteKey]）。预热方也只有 ReaderPos，必须能算出同一个键。
    val pageKey = pageNoteKey(chapter, charStart, charEnd)
    var bubbles by remember(
        pageKey, content, chapter, charStart, charEnd, annotations, memo, lane,
        contentLeft, contentTop, contentRight, contentBottom,
        pageWidth, pageHeight, fontScale,
    ) {
        // 命中缓存的那一页直接有结果（翻页的当前页、落地后的新页都走这里）：
        // 首帧就有旁注，不会先空一帧再冒出来。
        mutableStateOf(memo.get(pageKey).orEmpty())
    }
    LaunchedEffect(
        pageKey, content, chapter, charStart, charEnd, annotations, lane,
        contentLeft, contentTop, contentRight, contentBottom,
        pageWidth, pageHeight, fontScale,
    ) {
        if (content == null) {
            bubbles = emptyList()
            return@LaunchedEffect
        }
        val cached = memo.get(pageKey)
        if (cached != null) {
            bubbles = cached
            return@LaunchedEffect
        }
        val computed = withContext(lane.dispatcher) {
            lane.compute(
                content = content,
                chapter = chapter,
                charStart = charStart,
                charEnd = charEnd,
                annotations = annotations,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRight = contentRight,
                contentBottom = contentBottom,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                fontScale = fontScale,
            )
        }
        memo.put(pageKey, computed)
        bubbles = computed
    }
    return bubbles
}

/** [rememberPageNotes] 的实体：只在缓存未命中时跑一次（开一次 NOTE-LAYOUT 计时）。 */
private fun computePageNotes(
    content: PageContent,
    chapter: Int,
    charStart: Int,
    charEnd: Int,
    annotations: List<Annotation>,
    drawer: LineWindowDrawer,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageWidth: Float,
    pageHeight: Float,
    textSizer: NoteTextSizer,
    fontScale: Float,
): List<NoteBubble> {
    val lines = content.lines.orEmpty()
    val shift = if (lines.isEmpty()) {
        null
    } else {
        readerPageShift(
            lines, content.pageImages, content.pageBackgrounds,
            content.pageFrameLead, contentTop,
        )
    }
    val notes = annotations.filter {
        it.chapter == chapter && !it.note.isNullOrBlank()
    }
    if (shift == null || lines.isEmpty() || notes.isEmpty()) return emptyList()

    // 墨迹矩形一律问测度引擎（与画出来的字同源）：自己拿行宽估算会把
    // 「整行排满」误判成「右侧还有空地」，也会把段内字符坐标与章内坐标比错。
    // 四类障碍：文字逐字墨迹（与画出来的字同源）+ 列表圆点 / 编号 + 图形 + 线条。
    // 表格整体暂时没有只读几何入口（D-29 前置），先不传。
    val pageInk = pageInkRects(
        drawer = drawer,
        lines = lines,
        chapter = chapter,
        charStart = charStart,
        charEnd = charEnd,
        pageImages = content.pageImages,
        pageBackgrounds = content.pageBackgrounds,
        frameLead = content.pageFrameLead,
        contentLeft = contentLeft,
        contentTop = contentTop,
    ) + pageMarkerRects(
        drawer = drawer,
        lines = lines,
        chapter = chapter,
        charStart = charStart,
        charEnd = charEnd,
        pageImages = content.pageImages,
        pageBackgrounds = content.pageBackgrounds,
        frameLead = content.pageFrameLead,
        contentLeft = contentLeft,
        contentTop = contentTop,
    )
    // 本页**所有**划线标注的落墨带（不只是有批注的那几条）：引线不得压在上面，
    // 行间中线也得按它们的下缘量——波浪线比字墨迹低一截，只按墨迹量，
    // 横穿的中线正好落在波峰上（2026-10-31 真机「函数体」）。
    val annotationLines = annotations
        .filter {
            it.chapter == chapter &&
                it.kind == AnnotationKind.Underline &&
                it.charEnd > charStart &&
                it.charStart < charEnd
        }
        .flatMap { a ->
            pageAnnotationLineRects(
                drawer = drawer,
                lines = lines,
                chapter = chapter,
                annotation = a,
                pageImages = content.pageImages,
                pageBackgrounds = content.pageBackgrounds,
                frameLead = content.pageFrameLead,
                contentLeft = contentLeft,
                contentTop = contentTop,
            )
        }
    val obstacles = noteObstacles(
        textInk = pageInk,
        pageImages = content.pageImages,
        pageBackgrounds = content.pageBackgrounds,
        shift = shift,
        contentLeft = contentLeft,
        // 箭头要能穿两字之间与标点前后：另给一份逐字墨迹（方案 §6.3）。
        arrowInk = pageGlyphRects(
            drawer = drawer,
            lines = lines,
            chapter = chapter,
            charStart = charStart,
            charEnd = charEnd,
            pageImages = content.pageImages,
            pageBackgrounds = content.pageBackgrounds,
            frameLead = content.pageFrameLead,
            contentLeft = contentLeft,
            contentTop = contentTop,
        ),
        // 布点阶梯要「隔几行 / 本段」：带上每行的块序号与行序（方案 §1.5）。
        textLines = pageTextLines(
            drawer = drawer,
            lines = lines,
            chapter = chapter,
            charStart = charStart,
            charEnd = charEnd,
            pageImages = content.pageImages,
            pageBackgrounds = content.pageBackgrounds,
            frameLead = content.pageFrameLead,
            contentLeft = contentLeft,
            contentTop = contentTop,
        ),
        arrowLines = annotationLines,
    )
    val noteLayoutMark = TimeSource.Monotonic.markNow()
    val noteInkList = notes.mapNotNull { a ->
            val rects = pageInkRects(
                drawer = drawer,
                lines = lines,
                chapter = chapter,
                charStart = a.charStart,
                charEnd = a.charEnd,
                pageImages = content.pageImages,
                pageBackgrounds = content.pageBackgrounds,
                frameLead = content.pageFrameLead,
                contentLeft = contentLeft,
                contentTop = contentTop,
            )
            if (rects.isEmpty()) {
                null
            } else {
                NoteInk(
                    annotationId = a.id,
                    text = a.note.orEmpty(),
                    argb = a.color ?: annotationFallbackColor(a.kind),
                    rects = rects,
                    // 箭头指向与避让都按逐字墨迹，不把整行当一个黑块。
                    arrowRects = pageGlyphRects(
                        drawer = drawer,
                        lines = lines,
                        chapter = chapter,
                        charStart = a.charStart,
                        charEnd = a.charEnd,
                        pageImages = content.pageImages,
                        pageBackgrounds = content.pageBackgrounds,
                        frameLead = content.pageFrameLead,
                        contentLeft = contentLeft,
                        contentTop = contentTop,
                    ),
                    // 划线标注：箭头进得行盒，但不得触及这条线（规则第 9 条）。
                    rowBoxEntry = a.kind == AnnotationKind.Underline,
                    underlineRects = pageAnnotationLineRects(
                        drawer = drawer,
                        lines = lines,
                        chapter = chapter,
                        annotation = a,
                        pageImages = content.pageImages,
                        pageBackgrounds = content.pageBackgrounds,
                        frameLead = content.pageFrameLead,
                        contentLeft = contentLeft,
                        contentTop = contentTop,
                    ),
                )
            }
        }
    val inkMark = TimeSource.Monotonic.markNow()
    val placeMark = TimeSource.Monotonic.markNow()
    val placed = noteBubblePlacements(
        noteInk = noteInkList,
        obstacles = obstacles,
        contentLeft = contentLeft,
        contentRight = contentRight,
        contentBottom = contentBottom,
        pageRight = pageWidth,
        // 旁注字号跟正文主字号走：页首是标题时不能跟着标题放大。
        // 读者在笔记页拖的倍率乘在这里（1 = 默认），布点 / 绘制 / 实测同一口径。
        fontPx = noteBodyFontPx(lines) * fontScale,
        pageBottom = pageHeight,
        noteTextSizer = textSizer,
    )
    // 真机计时留档（P5）：只在按页缓存失效时打一行，翻页 / 改批注才出现。
    // geo = 本页整页几何（行 / 逐字墨迹 / 圆点），ink = 各条批注自己的墨迹，
    // place = 布点 + 引线。三者分开才好判断该优化哪一段。
    val totalMs = noteLayoutMark.elapsedNow().inWholeMilliseconds
    val inkAndPlaceMs = inkMark.elapsedNow().inWholeMilliseconds
    val placeMs = placeMark.elapsedNow().inWholeMilliseconds
    // 文字实测次数与耗时：布点里最贵的一笔，和总时长一起看才知道瓶颈在哪。
    val sizer = textSizer as? CachingNoteTextSizer
    val fits = sizer?.realFits ?: 0
    val fitMs = (sizer?.realFitNanos ?: 0L) / 1_000_000
    sizer?.resetStats()
    Logger.d(
        "Orilumn.NOTE",
        "NOTE-LAYOUT notes=${notes.size}" +
            " placed=${placed.count { !it.compact }}" +
            " compact=${placed.count { it.compact }}" +
            " geo=${totalMs - inkAndPlaceMs}" +
            " ink=${inkAndPlaceMs - placeMs}" +
            " place=$placeMs fits=$fits fitMs=$fitMs total=$totalMs",
    )
    return placed
}

/**
 * 旁注缓存的**页身份键**：章 + 页区间。
 *
 * 刻意**不含**版面尺寸 / 主题色 / 内容修订号 —— 那些由 [PageNoteMemo] 的持有者整只换新来失效
 * （`remember` 的键里已经含它们）。这样预热方只要手里有 [ReaderPos] 就能算出同一个键，
 * 不必先凑出 `PageContent`（预热发生在翻页**之前**，那时目标页的内容还没被求过）。
 */
internal fun pageNoteKey(chapter: Int, charStart: Int, charEnd: Int): String =
    "$chapter:$charStart-$charEnd"

/**
 * 落定后**预热前后两页**的旁注（用户层）。
 *
 * ## 为什么需要它（异步车道的配套）
 *
 * [rememberPageNotes] 把布点搬出 UI 线程后，「起手那一帧不卡」了，但**冷页**的旁注要等算完
 * 才推上来：真机实测落定后还要 600~1600ms 才出现，观感是「页面先空着，一秒后旁注啪一下冒出来」。
 * 布点本身是确定性的、与翻页时机无关，所以正确的做法是**提前算**：页面一落定（并让出本次
 * 翻页的栅格/动画）就把前后邻页的布点算进 [PageNoteMemo]，等用户真的翻过去时是缓存命中，
 * 首帧就跟着页面一起滑进来。
 *
 * 预热只跑**同章**邻页：跨章要另取那一章的标注（`annotationsForChapter`），
 * 那是开章该做的事，不在翻页热路径上做第二次 IO；跨章那一页仍走车道的按需路径。
 */
@Composable
internal fun WarmAdjacentPageNotes(
    host: ReaderHost,
    pos: ReaderPos?,
    annotations: List<Annotation>,
    annotationChapter: Int,
    decorRevision: Int,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageWidth: Float,
    pageHeight: Float,
    fontScale: Float,
    lane: PageNoteLane,
    memo: PageNoteMemo,
    /** 最近翻页方向（+1 前 / -1 后 / 0 无）：预热先排需求最大的一侧。 */
    directionProvider: () -> Int,
) {
    val hostRef = rememberUpdatedState(host)
    val dirRef = rememberUpdatedState(directionProvider)
    LaunchedEffect(
        pos, host, memo, lane, annotations, annotationChapter, decorRevision,
        contentLeft, contentTop, contentRight, contentBottom, pageWidth, pageHeight, fontScale,
    ) {
        val anchor = pos ?: return@LaunchedEffect
        val hostNow = hostRef.value
        // 先让本次落定的结算动画与邻页栅格跑完：预热是「余量里的活」，不该和用户正在看的
        // 那一帧抢 CPU（真机日志里 NOTE-LAYOUT 与 raster 叠在一起时两笔都变慢）。
        delay(NOTE_WARM_DELAY_MS)
        val dir = dirRef.value().takeIf { it != 0 } ?: 1
        for (d in intArrayOf(dir, -dir)) {
            val target = runCatching { hostNow.peekAdjacent(anchor, d) }.getOrNull() ?: continue
            if (target.chapter != annotationChapter) continue
            val key = pageNoteKey(target.chapter, target.slice.charStart, target.slice.charEnd)
            if (memo.contains(key)) continue
            val rawLines = runCatching { hostNow.pageLines(target) }.getOrNull().orEmpty()
            if (rawLines.isEmpty()) continue
            // 与 rememberPageContent 同一条投影口径（否则预热结果与翻页时那一页不是同一份几何）。
            val content = PageContent(
                lines = projectDecor(rawLines, target.chapter, annotationDecorRanges(annotations)),
                pageImages = runCatching { hostNow.pageImages(target) }.getOrNull(),
                imageBitmaps = emptyMap(),
                pageBackgrounds = runCatching { hostNow.pageBackgrounds(target) }.getOrNull(),
                bgImages = emptyMap(),
                rasterKey = null,
                pageFrameLead = runCatching { hostNow.pageFrameLead(target) }.getOrDefault(0),
            )
            val bubbles = withContext(lane.dispatcher) {
                lane.compute(
                    content = content,
                    chapter = target.chapter,
                    charStart = target.slice.charStart,
                    charEnd = target.slice.charEnd,
                    annotations = annotations,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRight = contentRight,
                    contentBottom = contentBottom,
                    pageWidth = pageWidth,
                    pageHeight = pageHeight,
                    fontScale = fontScale,
                )
            }
            memo.put(key, bubbles)
        }
    }
}

/** 预热前的让路时长：够翻页结算动画（300ms）与邻页栅格收尾。 */
private const val NOTE_WARM_DELAY_MS = 400L
