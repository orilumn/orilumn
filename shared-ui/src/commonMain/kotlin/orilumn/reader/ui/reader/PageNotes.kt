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
import kotlinx.coroutines.NonCancellable
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
internal class PageNoteMemo(
    private val capacity: Int = 6,
    /** 章 → 标注代数（[AnnotationState.generationOf]）：判条目还有没有效。 */
    private val generationOf: (chapter: Int) -> Int = { 0 },
) {

    /** 一条布点结果 + 它算出来时那一章的标注代数。 */
    private data class Entry(val generation: Int, val bubbles: List<NoteBubble>)

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * 取一页的结果；取到就算「刚用过」（挪到队尾，少被挤掉）。
     *
     * 有效性按**这一章自己的标注代数**判，而不是「全局修订号一变就把整只缓存换新」：
     * 换章时标注状态会连续两次 bump（清空 + 读回），整只换新会让刚看过的上一章一起作废，
     * 翻回去只能重算——那正是「回翻比正翻慢」的来源。
     */
    fun get(key: String, chapter: Int): List<NoteBubble>? {
        val value = entries.remove(key) ?: return null
        if (value.generation != generationOf(chapter)) return null
        entries[key] = value
        return value.bubbles
    }

    /** 只问「有没有」，**不动**最近使用序（预热前判重，不该把冷页顶成热页）。 */
    fun contains(key: String, chapter: Int): Boolean =
        entries[key]?.generation == generationOf(chapter)

    fun put(key: String, chapter: Int, value: List<NoteBubble>) {
        entries.remove(key)
        if (entries.size >= capacity) {
            entries.keys.firstOrNull()?.let { entries.remove(it) }
        }
        entries[key] = Entry(generationOf(chapter), value)
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
        bodyFontPx: Float,
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
        bodyFontPx = bodyFontPx,
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
    /**
     * **这一页所属章**的标注（`AnnotationState.annotationsFor(chapter).orEmpty()`）。
     * 传别的章的列表不会串位（下面按 `chapter` 过滤），但这一页就没有自己那一章的批注了。
     */
    annotations: List<Annotation>,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageWidth: Float,
    pageHeight: Float,
    /**
     * 正文主字号（物理像素，= 读者字号 × 字号缩放 × 密度，见 `TypographicProfile.bodyPx`）。
     *
     * 旁注字号**只认这个数**，不再从本页行几何里挑字号：早先按「本页字数最多的那一档字号」
     * 算基数，同一本书不同页会因此抖 —— 代码页 `pre`（0.8em）字数占优时旁注直接小 20%，
     * 切排版主题重新分页也会跟着变（2026-11-01 用户报「切传统后批注明显变小」）。
     * 读者拖的是倍率，基数就该是读者自己那把尺子，而不是这一页碰巧排了什么。
     */
    bodyFontPx: Float,
    /** 读者在面板上拖的旁注倍率（`ReaderSettings.noteFontScale`，1 = 默认 0.72 正文）。 */
    fontScale: Float,
    lane: PageNoteLane,
    memo: PageNoteMemo,
    /** 标注修订号（[AnnotationState.revision]）：只当「这一页要重算一遍」的触发键，不进缓存判据。 */
    decorRevision: Int,
    /**
     * 这一页所属章的标注**是否已就位**（`AnnotationState.isReady(chapter)`：当前章已读回，
     * 或已被相邻章预读）。
     *
     * 没就位时列表是空表，算出来必然是「一页没有旁注」——这个空结果**不能存**：
     * 存进去以后标注读回来也没人知道该作废它，整页批注就再也不显示了（真机 2026-11-01）。
     */
    notesReady: Boolean,
): List<NoteBubble> {
    // 页身份键：章 + 页区间（见 [pageNoteKey]）。预热方也只有 ReaderPos，必须能算出同一个键。
    val pageKey = pageNoteKey(chapter, charStart, charEnd)
    var bubbles by remember(pageKey, memo, decorRevision) {
        // 命中缓存的那一页直接有结果（翻页的当前页、落地后的新页都走这里）：
        // 首帧就有旁注，不会先空一帧再冒出来。
        mutableStateOf(memo.get(pageKey, chapter).orEmpty())
    }
    // 输入一律走 `rememberUpdatedState`，**不进 effect 的键**：见下面的 KDoc。
    val contentNow by rememberUpdatedState(content)
    val annotationsNow by rememberUpdatedState(annotations)
    val geomNow by rememberUpdatedState(
        NoteGeom(contentLeft, contentTop, contentRight, contentBottom, pageWidth, pageHeight),
    )
    // 键只有「这一页是谁」+「这一页的内容到没到」+ 字号口径（改倍率/改正文字号时 memo 也整只换新，
    // 这里是兜底）。**不能**把 `content` 整个当键：`PageContent` 里的插图/背景位图是异步解出来的，
    // 解完就是一次新值 ⇒ effect 重启、在途的布点被取消，而布点要几百毫秒，取消时结果还没
    // 写进缓存 ⇒ 下一轮又从头算，同一页连算三遍还一直「没算完」（真机 2026-11-01：
    // 同一页 3 秒内 NOTE-LAYOUT 三次，翻页进去旁注仍然晚到）。
    LaunchedEffect(
        pageKey, memo, lane, content != null, bodyFontPx, fontScale, decorRevision, notesReady,
    ) {
        val c = contentNow ?: run {
            bubbles = emptyList()
            return@LaunchedEffect
        }
        // 缓存里有就直接用（含「这一章的标注还没读回来、但之前算过」的情形：那时算的才作数）。
        val cached = memo.get(pageKey, chapter)
        if (cached != null) {
            bubbles = cached
            return@LaunchedEffect
        }
        // 标注没读回来：这一页的旁注无从算起，**不存空结果**（见参数 KDoc）。
        // `notesReady` 是 effect 的键，读回来会重新进来一次。
        if (!notesReady) {
            bubbles = emptyList()
            return@LaunchedEffect
        }
        // 算完就写缓存，**不受取消影响**：快翻/重组打断这一轮时，这一页的结果照样留下，
        // 下次进来是命中而不是重算（布点几百毫秒，白算一次就是肉眼可见的晚到）。
        withContext(NonCancellable) {
            val geom = geomNow
            val computed = withContext(lane.dispatcher) {
                lane.compute(
                    content = c,
                    chapter = chapter,
                    charStart = charStart,
                    charEnd = charEnd,
                    annotations = annotationsNow,
                    contentLeft = geom.left,
                    contentTop = geom.top,
                    contentRight = geom.right,
                    contentBottom = geom.bottom,
                    pageWidth = geom.pageWidth,
                    pageHeight = geom.pageHeight,
                    bodyFontPx = bodyFontPx,
                    fontScale = fontScale,
                )
            }
            memo.put(pageKey, chapter, computed)
            bubbles = computed
        }
    }
    return bubbles
}

/** 布点用到的版面几何（一处打包，免得 effect 键里摊七个 float）。 */
private data class NoteGeom(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val pageWidth: Float,
    val pageHeight: Float,
)

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
    bodyFontPx: Float,
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
        // 旁注字号 = 读者字号口径的正文主字号 × 读者倍率（× 0.72 在 NoteStyle 里）。
        // **不再**从本页行几何里挑「字数最多的那档字号」：那会让旁注随页内构成抖动
        // （代码页 `pre` 占优就小 20%，切排版主题重新分页也跟着变）。
        fontPx = bodyFontPx * fontScale,
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
        "NOTE-LAYOUT ch=$chapter page=$charStart-$charEnd notes=${notes.size}" +
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
 * ## 跨章也预热（靠相邻章预读）
 *
 * 邻页在另一章时，这一页的布点要用**那一章**的标注。以前这里直接跳过（「跨章要另取标注，
 * 不在翻页热路径上做第二次 IO」），于是每本书第一次翻进某章时旁注总要晚一小截。
 * 现在那两章的标注已经由 `AnnotationState.prefetchNeighbors` 在**还读着当前章的时候**备好，
 * 这里按页所属章取即可（[annotationsFor]）；取不到（还没读回来）才跳过——
 * 绝不能拿空表算，那等于把「这页没有旁注」写进缓存。
 */
@Composable
internal fun WarmAdjacentPageNotes(
    host: ReaderHost,
    pos: ReaderPos?,
    /**
     * 某章可用的标注（`AnnotationState.annotationsFor`）；`null` = 还没读回来。
     *
     * 按**页所属章**问，不假定就是当前章：跨章邻页要的是目标章自己那一份，
     * 传当前章列表过去会按错的字符偏移投影。
     */
    annotationsFor: (chapter: Int) -> List<Annotation>?,
    /** 标注数据版本（`AnnotationState.dataVersion`）：当前章内容或预读快照一变就重排预热。 */
    dataVersion: Any?,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageWidth: Float,
    pageHeight: Float,
    /** 正文主字号（物理像素）：预热与翻页那次必须是同一个口径，否则预热出来的结果字号不对。 */
    bodyFontPx: Float,
    fontScale: Float,
    lane: PageNoteLane,
    memo: PageNoteMemo,
    /** 最近翻页方向（+1 前 / -1 后 / 0 无）：预热先排需求最大的一侧。 */
    directionProvider: () -> Int,
) {
    val hostRef = rememberUpdatedState(host)
    val dirRef = rememberUpdatedState(directionProvider)
    val annotationsRef = rememberUpdatedState(annotationsFor)
    LaunchedEffect(
        pos, host, memo, lane, dataVersion,
        contentLeft, contentTop, contentRight, contentBottom, pageWidth, pageHeight,
        bodyFontPx, fontScale,
    ) {
        val anchor = pos ?: return@LaunchedEffect
        val hostNow = hostRef.value
        // 让出起手那一帧：结算动画与邻页栅格优先（预热是「余量里的活」，不该和用户正在看的
        // 那一帧抢 CPU）。但让路要短——预热跑得越早，下一次翻页越是缓存命中。
        delay(NOTE_WARM_DELAY_MS)
        val dir = dirRef.value().takeIf { it != 0 } ?: 1
        for (d in intArrayOf(dir, -dir)) {
            val t0 = TimeSource.Monotonic.markNow()
            val target = runCatching { hostNow.peekAdjacent(anchor, d) }.getOrNull()
            if (target == null) {
                Logger.d("Orilumn.NOTE", "NOTE-WARM skip d=$d reason=no-adjacent")
                continue
            }
            // 这一页所属章的标注还没读回来：算出来是空的，存进去就把这一页的旁注永久抹掉了
            // （见 rememberPageNotes）。当前章靠进章那次读，邻章靠预读。
            val chapterAnnotations = annotationsRef.value(target.chapter)
            if (chapterAnnotations == null) {
                Logger.d(
                    "Orilumn.NOTE",
                    "NOTE-WARM skip d=$d reason=no-annotations ch=${target.chapter}",
                )
                continue
            }
            val key = pageNoteKey(target.chapter, target.slice.charStart, target.slice.charEnd)
            if (memo.contains(key, target.chapter)) {
                Logger.d("Orilumn.NOTE", "NOTE-WARM skip d=$d key=$key reason=cached")
                continue
            }
            val rawLines = runCatching { hostNow.pageLines(target) }.getOrNull().orEmpty()
            if (rawLines.isEmpty()) {
                Logger.d("Orilumn.NOTE", "NOTE-WARM skip d=$d key=$key reason=no-lines")
                continue
            }
            // 与 rememberPageContent 同一条投影口径（否则预热结果与翻页时那一页不是同一份几何）。
            val content = PageContent(
                lines = projectDecor(
                    rawLines,
                    target.chapter,
                    annotationDecorRanges(chapterAnnotations),
                ),
                pageImages = runCatching { hostNow.pageImages(target) }.getOrNull(),
                imageBitmaps = emptyMap(),
                pageBackgrounds = runCatching { hostNow.pageBackgrounds(target) }.getOrNull(),
                bgImages = emptyMap(),
                rasterKey = null,
                pageFrameLead = runCatching { hostNow.pageFrameLead(target) }.getOrDefault(0),
            )
            withContext(NonCancellable) {
                val bubbles = withContext(lane.dispatcher) {
                    lane.compute(
                        content = content,
                        chapter = target.chapter,
                        charStart = target.slice.charStart,
                        charEnd = target.slice.charEnd,
                        annotations = chapterAnnotations,
                        contentLeft = contentLeft,
                        contentTop = contentTop,
                        contentRight = contentRight,
                        contentBottom = contentBottom,
                        pageWidth = pageWidth,
                        pageHeight = pageHeight,
                        bodyFontPx = bodyFontPx,
                        fontScale = fontScale,
                    )
                }
                // 写回也在 NonCancellable 里：否则「刚算完就被下一次翻页取消」，
                // 结果算完却写不进缓存，白算一遍（快翻时每一页都重算）。
                memo.put(key, target.chapter, bubbles)
                Logger.d(
                    "Orilumn.NOTE",
                    "NOTE-WARM done d=$d key=$key n=${bubbles.size} " +
                        "ms=${t0.elapsedNow().inWholeMilliseconds}",
                )
            }
        }
    }
}

/** 预热前的让路时长：够起手那一帧与邻页栅格先跑起来，又不至于拖到下一次翻页。 */
private const val NOTE_WARM_DELAY_MS = 120L
