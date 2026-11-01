package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.io.Logger
import kotlin.time.TimeSource

/**
 * 旁注布点的一页缓存（最近几页）。
 *
 * 布点是本页最贵的一笔（实测 100~900ms，冷启动能到 4s 量级），而一页在一次翻页里会被
 * 求两遍：起手时它先当「目标槽」摆进去，落地又变成「当前槽」。留几页结果，同一页就只算一次
 * ——否则「让旁注跟着翻页动画走」会因为多算一遍把起手那一帧拖住。
 *
 * 键由调用方给（页身份），失效由 [PageNoteMemo] 的持有者负责：`remember` 的键一变就整只换新。
 */
internal class PageNoteMemo(private val capacity: Int = 4) {

    private val entries = LinkedHashMap<String, List<NoteBubble>>()

    fun getOrPut(key: String, compute: () -> List<NoteBubble>): List<NoteBubble> {
        entries[key]?.let { return it }
        val value = compute()
        if (entries.size >= capacity) {
            val eldest = entries.keys.firstOrNull() ?: return value
            entries.remove(eldest)
        }
        entries[key] = value
        return value
    }
}

/**
 * 一页的旁注布点（D-26）：批注是标注上的一句话，就摆在它旁边。
 *
 * 纯覆盖层：只按行几何算空白，不改排版、不进栅格。**跟着页走**——翻页时它被放进
 * [FlipSlideLayer] 的两槽里，和页画布同一个平移量：当前页的旁注滑出去、目标页的旁注
 * 跟着滑进来。早先整层在动画期间藏起来、落地才冒出来，观感是「字滑走了、旁注原地
 * 消失又出现」（2026-11-01 用户报的闪烁）。
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
    drawer: LineWindowDrawer,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageWidth: Float,
    pageHeight: Float,
    textSizer: NoteTextSizer,
    fontScale: Float,
    memo: PageNoteMemo,
): List<NoteBubble> {
    // 页身份：章 + 页区间 + 版面尺寸 + 内容修订号，与页栅格同一个键（同页必同键）。
    // 拿到同一个键说明「同一页、同一套排版」，旁注的墨迹与空白都只会一模一样。
    val pageKey = content?.rasterKey?.id ?: "$chapter:$charStart-$charEnd"
    return remember(
        pageKey, content, chapter, charStart, charEnd, annotations, memo,
        contentLeft, contentTop, contentRight, contentBottom,
        pageWidth, pageHeight, textSizer, fontScale,
    ) {
        if (content == null) {
            emptyList()
        } else {
            memo.getOrPut(pageKey) {
                computePageNotes(
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
        }
    }
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
