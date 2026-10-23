package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage

/**
 * 正文旁的批注文字（用户层覆盖层，D-26 / D-28）。
 *
 * 批注是高亮 / 划线记录上附带的一句话，所以它显示在**那条墨迹旁边**：读者不必打开清单
 * 才知道这条标注写了什么。文字块由 [noteBubblePlacements] 就近找空白放，**不改排版、
 * 不进栅格**——它只是盖在页面之上的一层，翻页即随页重组（[ReaderScreen] 在翻页动画期间隐藏它）。
 *
 * @property annotationId 这条批注属于哪条标注（点文字块 = 回去改这句话）。
 * @property text 批注正文（完整文本，超出可用行数由 Text 自己省略）。
 * @property leftPx/topPx 页面坐标（屏幕像素）。
 * @property widthPx 可用宽度（像素）。
 * @property maxLines 这块空白实际要用几行（1 起，已按「整句优先」与空白高度取小）。
 * @property argb 所属标注的墨色：文字块用它做淡底与左侧色条，读者一眼看出是哪一条。
 * @property fontPx 批注字号（像素）：比正文小一号，读起来不抢正文。
 * @property arrow 指向原文的箭头（null = 三种路由都走不通，只画旁注）。
 */
internal data class NoteBubble(
    val annotationId: Long,
    val text: String,
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val maxLines: Int,
    val argb: Int,
    val fontPx: Float,
    val arrow: NoteArrow? = null,
)

/** 旁注字号相对正文的比例：小一号看得清又不抢正文。 */
internal const val NOTE_BUBBLE_FONT_RATIO: Float = 0.72f

/** 旁注行高相对自身字号的比例（与正文行高无关：旁注自己排自己的）。 */
internal const val NOTE_BUBBLE_LINE_RATIO: Float = 1.38f

/** 旁注文字单侧内缩相对自身字号的比例（两侧合计 [NOTE_BUBBLE_PAD_RATIO] × 2）：落位估宽与绘制同源。 */
internal const val NOTE_BUBBLE_PAD_RATIO: Float = 0.35f

/** 箭头终点离原文墨迹的距离：箭头尖不压字。 */
private const val NOTE_ARROW_END_GAP_PX: Float = 2.5f

/** 判断两个矩形/线段是否相碰时的容差（像素）：避免「刚好贴着」被判成压住。 */
private const val NOTE_GEOM_EPS: Float = 0.5f

/**
 * 一条批注在**本页**的画布事实（用户层 → 旁注层的输入）。
 *
 * [rects] 是这条标注在本页的墨迹矩形（页坐标，由测度引擎逐字算出，与画出来的字同源）：
 * 空 = 本页没有它的墨迹（在别的页上），也就没有「旁边」可言。
 */
internal data class NoteInk(
    val annotationId: Long,
    val text: String,
    val argb: Int,
    val rects: List<Rect>,
)

/**
 * 页面上挡旁注 / 箭头的「非空白」（D-28 第 3 部分）。
 *
 * 只认四类：文字逐字墨迹、图形（插图与背景图）、表格整体、线条（边框带 / 描边）。
 * 块底色（`blockquote` / `div` / `pre` 的非文字区）、`margin` / `padding`、四个页边距
 * **都不在这里**——那里本来就是空白，旁注与箭头都可以压。
 *
 * 表格整体目前还没有只读几何入口（登记在 D-29 前置里），[tables] 先留空。
 */
internal data class NoteObstacles(
    val textInk: List<Rect> = emptyList(),
    val figures: List<Rect> = emptyList(),
    val tables: List<Rect> = emptyList(),
    val strokes: List<Rect> = emptyList(),
) {
    val all: List<Rect> get() = textInk + figures + tables + strokes
}

/**
 * 把渲染层给的图形/线条几何换成页坐标的障碍物（用户层纯转换，不碰排版）。
 *
 * @param shift [readerPageShift] 的结果：章内绝对 Y → 页坐标。
 * @param contentLeft 内容区左缘（插图/背景的 X 是内容区相对坐标）。
 */
internal fun noteObstacles(
    textInk: List<Rect>,
    pageImages: List<PageImage>?,
    pageBackgrounds: List<PageBackground>?,
    shift: Int,
    contentLeft: Float,
): NoteObstacles {
    val figures = mutableListOf<Rect>()
    val strokes = mutableListOf<Rect>()
    pageImages?.forEach { img ->
        // 裁剪（E11）只会让可见区域更小；整盒当障碍是保守口径（宁可少放一条旁注，不压图）。
        val rect = Rect(
            contentLeft + img.xLeft,
            (img.yTop - shift).toFloat(),
            contentLeft + img.xLeft + img.widthPx,
            (img.yBottom - shift).toFloat(),
        )
        if (rect.width > 0f && rect.height > 0f) figures += rect
    }
    pageBackgrounds?.forEach { bg ->
        val box = Rect(
            contentLeft + bg.left,
            (bg.yTop - shift).toFloat(),
            contentLeft + bg.right,
            (bg.yBottom - shift).toFloat(),
        )
        if (box.width <= 0f || box.height <= 0f) return@forEach
        when {
            // 背景图：整盒算图形。
            bg.bgSrc != null -> figures += box
            // 边框带：出图画时已展开成细带，直接当线条。
            bg.border -> strokes += box
            // 圆角描边环：只取盒的四条窄边，盒内仍算空白（块底色 / 内边距）。
            bg.strokeWidthPx > 0f -> {
                val t = bg.strokeWidthPx.coerceAtLeast(1f)
                strokes += Rect(box.left, box.top, box.right, min(box.top + t, box.bottom))
                strokes += Rect(box.left, max(box.bottom - t, box.top), box.right, box.bottom)
                strokes += Rect(box.left, box.top, min(box.left + t, box.right), box.bottom)
                strokes += Rect(max(box.right - t, box.left), box.top, box.right, box.bottom)
            }
            // 纯块底色：空白，不挡。
        }
    }
    return NoteObstacles(textInk = textInk, figures = figures, strokes = strokes)
}

/**
 * 旁注箭头（D-28 第 3 部分）：从旁注框指向原文墨迹的一条细线。
 *
 * 三种路由依次尝试：直线 → 折线（先竖后横 / 先横后竖取短）→ 弧线（朝远离障碍一侧鼓出）。
 * 三种都不通就不画箭头（宁可少一条线，也不糊住正文）。
 */
internal sealed interface NoteArrow {
    val start: Offset
    val end: Offset

    data class Straight(override val start: Offset, override val end: Offset) : NoteArrow

    /** 折线：起点 → 拐点 → 终点（至少 3 个点）。 */
    data class Elbow(val points: List<Offset>) : NoteArrow {
        override val start: Offset get() = points.first()
        override val end: Offset get() = points.last()
    }

    /** 二次贝塞尔弧线：起点 → 控制点 → 终点。 */
    data class Arc(
        override val start: Offset,
        override val end: Offset,
        val control: Offset,
    ) : NoteArrow
}

/**
 * 就近给每条批注找一块空白（Free Rectangle List，纯函数，无 Compose 依赖）。
 *
 * 这是 2D 空闲矩形拆分，不是文本流式排版：
 * 1. 整页（含左右页边距）作为初始空闲矩形；
 * 2. 每个障碍（正文行框 / 图形 / 表格 / 线条 / 已放好的旁注）把与它相交的空闲矩形切成
 *    上、下、左、右四块，丢掉装不下「一个字 + 省略号」的碎片；
 * 3. 批注需求 `R = (宽, 高)` 按批注文字估算：整句所需宽度（不超过内容宽）与对应行高；
 * 4. 每个空闲矩形都试一遍：先比旁注框到锚点墨迹的边到边距离（按约一行小字分桶），
 *    距离相当再优先整句放得下的，再选排得舒展（行数更少）的，最后才看锚点右侧与空白大小；
 * 5. 旁注框贴着锚点最近的边起排，不塞在矩形左上角。
 *
 * 内容区实在装不下整句时先就地截断（至少一个字加省略号），只有内容区一个候选都没有
 * 才退到左右页边距兜底（D-28）。每放好一条旁注都重建一次空闲矩形表，
 * 所以旁注之间天然不会重叠。
 *
 * 空白的判据仍然只认四类障碍（D-28 第 3 部分）：文字、图形、表格、线条；
 * 块底色 / `margin` / `padding` / 页边距都算空白。
 *
 * @param noteInk 本页上有墨迹的批注（`note` 非空；[NoteInk.rects] 为本页墨迹矩形）。
 * @param obstacles 本页四类障碍（文字 / 图形 / 表格 / 线条）。
 * @param contentBottom 内容区下缘（页坐标）。
 * @param pageRight 页面右边界（页宽）：左边距从 0 起算，右边距到 [pageRight]。
 * @param fontPx 正文主字号（像素）：旁注按 [NOTE_BUBBLE_FONT_RATIO] 缩一号。
 * @param pageBottom 页面下边界（页高）：上下页边距也算空白（D-28），默认到内容区下缘。
 */
internal fun noteBubblePlacements(
    noteInk: List<NoteInk>,
    obstacles: NoteObstacles,
    contentLeft: Float,
    contentRight: Float,
    contentBottom: Float,
    pageRight: Float,
    fontPx: Float,
    pageBottom: Float = contentBottom,
): List<NoteBubble> {
    if (noteInk.isEmpty()) return emptyList()
    val body = fontPx.coerceAtLeast(1f)
    val small = body * NOTE_BUBBLE_FONT_RATIO
    val lineH = small * NOTE_BUBBLE_LINE_RATIO
    val charW = small * 1.05f
    val padX = small * NOTE_BUBBLE_PAD_RATIO * 2f
    // 旁注框与墨迹之间留一线：贴在字上会让人觉得把正文框进去了。
    val gap = body * 0.25f
    // 「至少一个字加省略号」：再窄的格子也得容得下一个字和省略号。
    val minWidth = charW * 1.9f
    // 「距离相当」的判定容差：约一行小字以内算同一档，再由「完整显示 / 更舒展」决胜。
    val distanceBucket = lineH
    val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)

    // 整页（含四个页边距）都是可放旁注的空白：障碍之外哪里空就放哪里。
    val pageBounds = Rect(
        0f,
        0f,
        max(contentRight, pageRight),
        max(contentBottom, pageBottom),
    )
    // 正文按行合并成一个行框（不是逐字）：既减少空闲矩形碎片，
    // 也不会把词间空隙当成能放下一句批注的空白。
    val textLines = mergeTextLineRects(obstacles.textInk, small)
    val baseObstacles = textLines + obstacles.figures + obstacles.tables + obstacles.strokes

    val placed = mutableListOf<Rect>()
    val out = mutableListOf<NoteBubble>()

    for (note in noteInk.sortedBy { it.rects.minOfOrNull { r -> r.top } ?: 0f }) {
        val text = note.text.trim()
        if (text.isEmpty()) continue
        // 锚点不在本页（在别的页上）：本页没有它的墨迹，也就没有「旁边」。
        if (note.rects.isEmpty()) continue

        val anchor = rectUnion(note.rects)
        // 批注需求：整句宽度（略微放宽一点，给字形测量留余量），不超过内容宽。
        val wantedWidth = estimatedNoteTextWidth(text, small) * 1.04f + padX
        val reqWidth = wantedWidth.coerceIn(minWidth, contentWidth)

        val freeRects = buildFreeRects(
            bounds = pageBounds,
            obstacles = baseObstacles + placed,
            minWidth = minWidth,
            minHeight = lineH,
        )
        val options = notePlacementOptions(
            freeRects = freeRects,
            anchor = anchor,
            inkRects = note.rects,
            text = text,
            reqWidth = reqWidth,
            fontPx = small,
            lineH = lineH,
            padX = padX,
            gap = gap,
            minWidth = minWidth,
            contentLeft = contentLeft,
            contentRight = contentRight,
        )
        // 顺序即 D-28 的优先级：内容区（就近 → 完整显示）→ 页边距兜底（实在没地方才用）。
        val pick = options.pick(inContent = true, bucket = distanceBucket)
            ?: options.pick(inContent = false, bucket = distanceBucket)
            ?: continue

        placed += pick.rect
        out += NoteBubble(
            annotationId = note.annotationId,
            text = text,
            leftPx = pick.rect.left,
            topPx = pick.rect.top,
            widthPx = pick.rect.width,
            maxLines = pick.lines,
            argb = note.argb,
            fontPx = small,
        )
    }

    if (out.isEmpty()) return out
    val inkById = noteInk.associateBy { it.annotationId }
    val bubbleRects = out.associate { b ->
        b.annotationId to Rect(b.leftPx, b.topPx, b.leftPx + b.widthPx, b.topPx + b.maxLines * lineH)
    }
    return out.map { b ->
        val ink = inkById[b.annotationId]?.rects.orEmpty()
        val self = bubbleRects.getValue(b.annotationId)
        val others = out.asSequence()
            .filter { it.annotationId != b.annotationId }
            .mapNotNull { bubbleRects[it.annotationId] }
            .toList()
        b.copy(
            arrow = routeNoteArrow(
                bubble = self,
                inkRects = ink,
                obstacles = baseObstacles,
                otherBubbles = others,
            ),
        )
    }
}

/** 一条旁注在某个空闲矩形里的候选落点。 */
private data class NotePlacement(
    val rect: Rect,
    val lines: Int,
    val distance: Float,
    val inContent: Boolean,
    val rightOfAnchor: Boolean,
    val freeArea: Float,
    val fits: Boolean,
)

/**
 * 把每个空闲矩形都试一遍：批注框先按「贴近锚点」定起点（右侧 / 左下方 / 正下方各取近的一侧），
 * 批注框的宽取「整句所需宽」与剩余宽的较小者，行数取「这个宽度下要几行」与「剩余高度能放几行」
 * 的较小者。
 */
private fun notePlacementOptions(
    freeRects: List<Rect>,
    anchor: Rect,
    inkRects: List<Rect>,
    text: String,
    reqWidth: Float,
    fontPx: Float,
    lineH: Float,
    padX: Float,
    gap: Float,
    minWidth: Float,
    contentLeft: Float,
    contentRight: Float,
): List<NotePlacement> {
    val out = ArrayList<NotePlacement>(freeRects.size)
    for (f in freeRects) {
        if (f.width < minWidth || f.height < lineH) continue
        // 起点：空白在锚点右侧就从锚点右缘往右让一线，在下方就贴着锚点下缘，
        // 和锚点横向重叠时从锚点左缘起排（段落间空隙里读起来像给这一行加的注）。
        val x = when {
            f.left >= anchor.right - NOTE_GEOM_EPS -> max(f.left, anchor.right + gap)
            f.right <= anchor.left + NOTE_GEOM_EPS ->
                min(f.right - minWidth, anchor.left - gap).coerceAtLeast(f.left)
            else -> anchor.left.coerceIn(f.left, max(f.left, f.right - minWidth))
        }
        val y = when {
            f.top >= anchor.bottom - NOTE_GEOM_EPS -> max(f.top, anchor.bottom + gap)
            f.bottom <= anchor.top + NOTE_GEOM_EPS ->
                min(f.bottom - lineH, anchor.top - gap).coerceAtLeast(f.top)
            else -> (anchor.center.y - lineH / 2f).coerceIn(f.top, max(f.top, f.bottom - lineH))
        }
        val width = min(reqWidth, f.right - x)
        if (width < minWidth) continue
        val usable = width - padX
        if (usable <= 0f) continue
        val needed = estimatedNoteLines(text, usable, fontPx)
        val capacity = ((f.bottom - y) / lineH).toInt()
        if (capacity < 1) continue
        val lines = min(needed, capacity)
        val rect = Rect(x, y, x + width, y + lines * lineH)
        out += NotePlacement(
            rect = rect,
            lines = lines,
            // 距离按「旁注框到锚点墨迹的边到边最短距离」算（用户层口径，不是框心到框心）。
            distance = inkRects.minOfOrNull { rectDistance(rect, it) } ?: 0f,
            inContent = rect.left >= contentLeft - NOTE_GEOM_EPS &&
                rect.right <= contentRight + NOTE_GEOM_EPS,
            rightOfAnchor = rect.left >= anchor.right - NOTE_GEOM_EPS,
            freeArea = f.width * f.height,
            fits = needed <= capacity,
        )
    }
    return out
}

/**
 * 排序取第一个：
 * 1. **就近优先**：边到边距离按约一行小字分桶，同一档内先比完整显示；
 * 2. **完整显示优先**：距离相当，整句放得下的胜过只能显示一截的；
 * 3. 同样完整时，选能让这句话排得更舒展（行数更少）的空白，不让它变成一条窄缝；
 * 4. 再看锚点右侧、空白大小、精确距离与阅读顺序。
 *
 * @param inContent true = 只要落在内容区的；false = 页边距兜底用。
 */
private fun List<NotePlacement>.pick(inContent: Boolean, bucket: Float): NotePlacement? = asSequence()
    .filter { it.inContent == inContent }
    .minWithOrNull(
        compareBy<NotePlacement> { (it.distance / bucket).toInt() }
            .thenBy { !it.fits }
            .thenBy { if (it.fits) it.lines else -it.lines }
            .thenBy { !it.rightOfAnchor }
            .thenByDescending { it.freeArea }
            .thenBy { it.distance }
            .thenBy { it.rect.top }
            .thenBy { it.rect.left },
    )

/** 空闲矩形列表（FRL）：整页起手，每个障碍把相交的矩形切成上 / 下 / 左 / 右四块。 */
private fun buildFreeRects(
    bounds: Rect,
    obstacles: List<Rect>,
    minWidth: Float,
    minHeight: Float,
): List<Rect> {
    if (bounds.width <= 0f || bounds.height <= 0f) return emptyList()
    var free = listOf(bounds)
    for (o in obstacles) {
        if (o.right <= bounds.left || o.left >= bounds.right ||
            o.bottom <= bounds.top || o.top >= bounds.bottom ||
            o.width <= 0f || o.height <= 0f
        ) {
            continue
        }
        val next = ArrayList<Rect>(free.size + 4)
        for (f in free) {
            if (!f.overlaps(o)) {
                next += f
                continue
            }
            // 左 / 右两条整高，上 / 下只取障碍物横向覆盖的那一段：四块互不重叠。
            if (f.left < o.left - NOTE_GEOM_EPS) {
                next += Rect(f.left, f.top, min(f.right, o.left), f.bottom)
            }
            if (f.right > o.right + NOTE_GEOM_EPS) {
                next += Rect(max(f.left, o.right), f.top, f.right, f.bottom)
            }
            if (f.top < o.top - NOTE_GEOM_EPS) {
                next += Rect(max(f.left, o.left), f.top, min(f.right, o.right), min(f.bottom, o.top))
            }
            if (f.bottom > o.bottom + NOTE_GEOM_EPS) {
                next += Rect(max(f.left, o.left), max(f.top, o.bottom), min(f.right, o.right), f.bottom)
            }
        }
        free = next
    }
    return free.filter { it.width >= minWidth && it.height >= minHeight }
}

/**
 * 整页的**极大空闲矩形**（MaxRects，Jylänki《A Thousand Ways to Pack the Bin》§2.2）。
 *
 * 与 [buildFreeRects] 的四块互斥切分不同：每个障碍把相交的空闲矩形切成上 / 下 / 左 / 右后，
 * **只剪掉被其他空闲矩形包含的那些**，不做「切完就再也看不到」的互斥划分。
 * 于是障碍之间的错位切口不会把跨过切口的大块空白切碎，后面仍能找到它。
 *
 * @param bounds 页面边界（含四个页边距）。
 * @param obstacles 四类障碍（已按行合并的文字行框 / 图形 / 表格 / 线条）。
 * @param minW/minH 低于这个尺寸的碎片直接丢弃（候选阶段还会做更严的淘汰）。
 * @param maxRects 空闲矩形上限：极端页面上按面积保留前 N 个（只影响观感，不影响正确性）。
 */
internal fun maximalFreeRects(
    bounds: Rect,
    obstacles: List<Rect>,
    minW: Float,
    minH: Float,
    maxRects: Int = 3000,
): List<Rect> {
    if (bounds.width < minW || bounds.height < minH) return emptyList()
    var free = listOf(bounds)
    for (o in dedupeRects(obstacles)) {
        if (o.right <= bounds.left || o.left >= bounds.right ||
            o.bottom <= bounds.top || o.top >= bounds.bottom ||
            o.width <= 0f || o.height <= 0f
        ) {
            continue
        }
        val next = ArrayList<Rect>(free.size + 4)
        for (f in free) {
            if (!f.overlaps(o)) {
                next += f
                continue
            }
            // 标准 MaxRects 四切：左 / 右两道**整高**，上 / 下两道**整宽**（会互相重叠，
            // 由 pruneFreeRects 剪掉被包含的那些）。这正是它比「四块互斥切分」强的地方：
            // 跨过障碍切口的大块空白仍然作为一整条保留下来。
            if (o.left > f.left + NOTE_GEOM_EPS) {
                next += Rect(f.left, f.top, min(o.left, f.right), f.bottom)
            }
            if (o.right < f.right - NOTE_GEOM_EPS) {
                next += Rect(max(o.right, f.left), f.top, f.right, f.bottom)
            }
            if (o.top > f.top + NOTE_GEOM_EPS) {
                next += Rect(f.left, f.top, f.right, min(o.top, f.bottom))
            }
            if (o.bottom < f.bottom - NOTE_GEOM_EPS) {
                next += Rect(f.left, max(o.bottom, f.top), f.right, f.bottom)
            }
        }
        free = pruneFreeRects(next, minW, minH)
        if (free.isEmpty()) break
    }
    val capped = if (free.size > maxRects) {
        free.sortedByDescending { it.width * it.height }.take(maxRects)
    } else {
        free
    }
    return capped.sortedWith(compareBy({ it.top }, { it.left }, { it.right }, { it.bottom }))
}

/** 去掉被其他空闲矩形包含的碎片（MaxRects 的 prune），并按最小尺寸过滤。 */
private fun pruneFreeRects(rects: List<Rect>, minW: Float, minH: Float): List<Rect> {
    val kept = ArrayList<Rect>(rects.size)
    outer@ for (i in rects.indices) {
        val r = rects[i]
        if (r.width < minW || r.height < minH) continue
        for (j in rects.indices) {
            if (j == i) continue
            val other = rects[j]
            if (!containsRect(other, r)) continue
            // 完全相同的两块只留输入顺序靠前的那块。
            if (!containsRect(r, other) || j < i) continue@outer
        }
        kept += r
    }
    return kept
}

/** [outer] 是否包含 [inner]（四边都不比它紧，留 [NOTE_GEOM_EPS] 容差）。 */
private fun containsRect(outer: Rect, inner: Rect): Boolean =
    outer.left <= inner.left + NOTE_GEOM_EPS &&
        outer.top <= inner.top + NOTE_GEOM_EPS &&
        outer.right >= inner.right - NOTE_GEOM_EPS &&
        outer.bottom >= inner.bottom - NOTE_GEOM_EPS

/** 四边取整去重：重复的障碍不必反复切同一块空白。 */
private fun dedupeRects(rects: List<Rect>): List<Rect> {
    if (rects.size < 2) return rects
    val seen = HashSet<Long>()
    val out = ArrayList<Rect>(rects.size)
    for (r in rects) {
        val l = r.left.roundToInt()
        val t = r.top.roundToInt()
        val rr = r.right.roundToInt()
        val b = r.bottom.roundToInt()
        val key = (l.toLong() shl 48) xor (t.toLong() shl 32) xor (rr.toLong() shl 16) xor b.toLong()
        if (seen.add(key)) out += Rect(l.toFloat(), t.toFloat(), rr.toFloat(), b.toFloat())
    }
    return out
}


/**
 * 把逐字墨迹合并成**正文行框**（障碍物用）。
 *
 * 同一行的字按横向区间合并，词间空隙一并盖住（不让旁注钻进词与词之间），
 * 但左右两栏之间的大空隙不合并。行高取这一带墨迹的上下缘。
 */
internal fun mergeTextLineRects(rects: List<Rect>, fontPx: Float): List<Rect> {
    if (rects.isEmpty()) return emptyList()
    val mergeGap = fontPx * 0.45f
    val sorted = rects.sortedWith(compareBy({ it.top }, { it.left }))
    val out = mutableListOf<Rect>()
    var i = 0
    while (i < sorted.size) {
        val band = mutableListOf<Rect>()
        var top = sorted[i].top
        var bottom = sorted[i].bottom
        var j = i
        while (j < sorted.size && sorted[j].top < bottom - NOTE_GEOM_EPS) {
            band += sorted[j]
            if (sorted[j].top < top) top = sorted[j].top
            if (sorted[j].bottom > bottom) bottom = sorted[j].bottom
            j++
        }
        band.sortBy { it.left }
        var left = band.first().left
        var right = band.first().right
        for (r in band.drop(1)) {
            if (r.left <= right + mergeGap) {
                if (r.right > right) right = r.right
            } else {
                out += Rect(left, top, right, bottom)
                left = r.left
                right = r.right
            }
        }
        out += Rect(left, top, right, bottom)
        i = j.coerceAtLeast(i + 1)
    }
    return out
}

private fun rectUnion(rects: List<Rect>): Rect {
    var left = rects.first().left
    var top = rects.first().top
    var right = rects.first().right
    var bottom = rects.first().bottom
    for (r in rects.drop(1)) {
        if (r.left < left) left = r.left
        if (r.top < top) top = r.top
        if (r.right > right) right = r.right
        if (r.bottom > bottom) bottom = r.bottom
    }
    return Rect(left, top, right, bottom)
}

/** 两个矩形之间的最短距离（相交为 0）。 */
private fun rectDistance(a: Rect, b: Rect): Float {
    val dx = max(0f, max(a.left - b.right, b.left - a.right))
    val dy = max(0f, max(a.top - b.bottom, b.top - a.bottom))
    return sqrt(dx * dx + dy * dy)
}

/** 估一句批注排成一行需要多宽（按字宽粗估，中文/全角按一个字宽，西文按 0.55）。 */
internal fun estimatedNoteTextWidth(text: String, fontPx: Float): Float {
    if (fontPx <= 0f) return 0f
    var widest = 0f
    for (paragraph in text.split('\n')) {
        var px = 0f
        for (ch in paragraph) px += noteCharWidth(ch.code, fontPx)
        if (px > widest) widest = px
    }
    return widest
}

/**
 * 估一句批注要占几行（纯函数，无文字测量依赖）。
 *
 * 中文/日文/韩文/全角与 emoji 按一个字宽，西文按 0.55 个字宽，空白按 0.3 个字宽；
 * 只要不是「本可以整句显示却被省略号截掉」这种一眼能看出的委屈，这个粗估就够了。
 */
internal fun estimatedNoteLines(text: String, widthPx: Float, fontPx: Float): Int {
    if (widthPx <= 0f || fontPx <= 0f) return Int.MAX_VALUE
    var lines = 0
    for (paragraph in text.split('\n')) {
        var px = 0f
        for (ch in paragraph) px += noteCharWidth(ch.code, fontPx)
        lines += max(1, ceil(px / widthPx).toInt())
    }
    return max(lines, 1)
}

private fun noteCharWidth(code: Int, fontPx: Float): Float = when {
    code == 0x20 || code == 0x09 -> fontPx * 0.3f
    code < 0x80 -> fontPx * 0.55f
    isWideCodePoint(code) -> fontPx
    else -> fontPx * 0.55f
}

private fun isWideCodePoint(code: Int): Boolean =
    code in 0x1100..0x115F || // Hangul Jamo
        code in 0x2E80..0xA4CF || // CJK / 假名 / 注音 / 韩文
        code in 0xAC00..0xD7A3 || // Hangul 音节
        code in 0xF900..0xFAFF || // CJK 兼容
        code in 0xFE30..0xFE4F || // CJK 兼容标点
        code in 0xFF00..0xFF60 || // 全角
        code in 0xFFE0..0xFFE6 ||
        code in 0x1F300..0x1FAFF || // emoji
        code in 0x20000..0x3FFFD

/**
 * 给一条旁注找箭头路由（纯函数）：直线 → 折线 → 弧线，三种都不通就不画。
 *
 * 只避四类障碍与**已放好的其他旁注**；这条批注自己的墨迹不算障碍（箭头就是要指过去）。
 * 从块底色 / 内边距 / 页边距上穿过是允许的——那里本来就是空白。
 */
internal fun routeNoteArrow(
    bubble: Rect,
    inkRects: List<Rect>,
    obstacles: List<Rect>,
    otherBubbles: List<Rect>,
): NoteArrow? {
    if (inkRects.isEmpty() || bubble.width <= 0f || bubble.height <= 0f) return null
    val target = inkRects.minByOrNull { rectDistance(bubble, it) } ?: return null
    val start = arrowStart(bubble, target)
    val onInk = Offset(
        start.x.coerceIn(target.left, target.right),
        start.y.coerceIn(target.top, target.bottom),
    )
    val away = start - onInk
    val awayLen = sqrt(away.x * away.x + away.y * away.y)
    if (awayLen < 0.01f) return null
    // 箭头尖停在墨迹外一点，不压字。
    val end = onInk + Offset(away.x / awayLen, away.y / awayLen) * NOTE_ARROW_END_GAP_PX
    // 旁注本来就贴着墨迹（留不出一条线）：没有可画的箭头。
    val arrowDx = end.x - start.x
    val arrowDy = end.y - start.y
    if (sqrt(arrowDx * arrowDx + arrowDy * arrowDy) < 1.5f) return null

    // 这条批注自己的墨迹不是障碍（其它文字/图形/线条/表格仍是）。
    // 障碍里的正文已按行合并成行框，行框会**包住**锚点墨迹，所以这里按「相交」排除，
    // 而不是按「被墨迹包含」排除；否则锚点所在整行都会挡在箭头前面。
    val blockers = (obstacles + otherBubbles).filter { b ->
        inkRects.none { ink ->
            ink.left < b.right - NOTE_GEOM_EPS && b.left < ink.right - NOTE_GEOM_EPS &&
                ink.top < b.bottom - NOTE_GEOM_EPS && b.top < ink.bottom - NOTE_GEOM_EPS
        }
    }.map { inflate(it, NOTE_GEOM_EPS) }

    if (isClear(listOf(start, end), blockers)) {
        return NoteArrow.Straight(start, end)
    }

    // 折线：先竖后横 / 先横后竖，取较短且通的一条。
    val vh = listOf(start, Offset(start.x, end.y), end)
    val hv = listOf(start, Offset(end.x, start.y), end)
    val vhClear = isClear(vh, blockers)
    val hvClear = isClear(hv, blockers)
    if (vhClear || hvClear) {
        val pick = if (vhClear && hvClear) {
            if (polylineLength(vh) <= polylineLength(hv)) vh else hv
        } else if (vhClear) vh else hv
        return NoteArrow.Elbow(pick)
    }

    // 弧线：朝远离障碍的一侧鼓出（两侧都试，取先通的一侧）。
    val distance = sqrt((end.x - start.x) * (end.x - start.x) + (end.y - start.y) * (end.y - start.y))
    if (distance < 1f) return null
    val mid = Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f)
    val unit = Offset((end.x - start.x) / distance, (end.y - start.y) / distance)
    val normal = Offset(-unit.y, unit.x)
    val bulge = min(distance * 0.35f, 40f)
    for (sign in floatArrayOf(1f, -1f)) {
        val control = mid + normal * (bulge * 2f * sign)
        if (isArcClear(start, control, end, blockers)) {
            return NoteArrow.Arc(start, end, control)
        }
    }
    return null
}

/** 旁注框朝墨迹一侧的边上的起点：按方向占框宽/框高的比例选边。 */
private fun arrowStart(bubble: Rect, target: Rect): Offset {
    val dx = target.center.x - bubble.center.x
    val dy = target.center.y - bubble.center.y
    val horizontal = bubble.width > 0f && bubble.height > 0f &&
        abs(dx) * bubble.height >= abs(dy) * bubble.width
    return if (horizontal) {
        Offset(
            if (dx >= 0f) bubble.right else bubble.left,
            target.center.y.coerceIn(bubble.top, bubble.bottom),
        )
    } else {
        Offset(
            target.center.x.coerceIn(bubble.left, bubble.right),
            if (dy >= 0f) bubble.bottom else bubble.top,
        )
    }
}

private fun inflate(r: Rect, by: Float): Rect = Rect(r.left - by, r.top - by, r.right + by, r.bottom + by)

private fun polylineLength(points: List<Offset>): Float {
    var total = 0f
    for (i in 1 until points.size) {
        val dx = points[i].x - points[i - 1].x
        val dy = points[i].y - points[i - 1].y
        total += sqrt(dx * dx + dy * dy)
    }
    return total
}

private fun isClear(points: List<Offset>, blockers: List<Rect>): Boolean {
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        if (abs(a.x - b.x) < 0.01f && abs(a.y - b.y) < 0.01f) continue
        if (blockers.any { segmentHitsRect(a, b, it) }) return false
    }
    return true
}

private fun isArcClear(start: Offset, control: Offset, end: Offset, blockers: List<Rect>): Boolean {
    val steps = 24
    var prev = start
    for (i in 1..steps) {
        val t = i.toFloat() / steps
        val point = quadraticPoint(start, control, end, t)
        if (blockers.any { segmentHitsRect(prev, point, it) }) return false
        prev = point
    }
    return true
}

private fun quadraticPoint(start: Offset, control: Offset, end: Offset, t: Float): Offset {
    val u = 1f - t
    val x = u * u * start.x + 2f * u * t * control.x + t * t * end.x
    val y = u * u * start.y + 2f * u * t * control.y + t * t * end.y
    return Offset(x, y)
}

/** 线段与矩形是否相交（Liang–Barsky）。端点落在矩形内也算相交。 */
private fun segmentHitsRect(a: Offset, b: Offset, r: Rect): Boolean {
    var t0 = 0f
    var t1 = 1f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - r.left, r.right - a.x, a.y - r.top, r.bottom - a.y)
    for (i in 0..3) {
        if (abs(p[i]) < 1e-6f) {
            if (q[i] < 0f) return false
        } else {
            val t = q[i] / p[i]
            if (p[i] < 0f) {
                if (t > t1) return false
                if (t > t0) t0 = t
            } else {
                if (t < t0) return false
                if (t < t1) t1 = t
            }
        }
    }
    return true
}

/**
 * 批注文字覆盖层：箭头先画（框下），再把 [noteBubblePlacements] 的结果画在页面之上。
 *
 * 点文字块 = 打开这条标注的批注输入条（[onOpen]）。它盖在正文之上、亮度遮罩之下，
 * 翻页动画期间由调用方隐藏（与选区覆盖层同口径）。
 */
@Composable
internal fun NoteBubbles(
    bubbles: List<NoteBubble>,
    fgColor: Color,
    onOpen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bubbles.isEmpty()) return
    val d = LocalDensity.current
    Box(modifier = modifier) {
        // 箭头画在旁注框之下（同一覆盖层里先画线后画框，D-28）。
        if (bubbles.any { it.arrow != null }) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                bubbles.forEach { b ->
                    val arrow = b.arrow ?: return@forEach
                    drawNoteArrow(arrow, Color(b.argb).copy(alpha = 0.9f))
                }
            }
        }
        bubbles.forEach { b ->
            val tint = Color(b.argb)
            Box(
                modifier = Modifier
                    // 布局位移（不是动画）：位置只跟页面几何走，翻页时整层一起让位。
                    .offset { IntOffset(b.leftPx.roundToInt(), b.topPx.roundToInt()) }
                    .width(with(d) { b.widthPx.toDp() })
                    .heightIn(max = with(d) { (b.maxLines * b.fontPx * NOTE_BUBBLE_LINE_RATIO).toDp() })
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                    .clickable { onOpen(b.annotationId) },
            ) {
                Text(
                    text = b.text,
                    color = fgColor,
                    fontSize = with(d) { b.fontPx.toSp() },
                    lineHeight = with(d) { (b.fontPx * NOTE_BUBBLE_LINE_RATIO).toSp() },
                    maxLines = b.maxLines,
                    overflow = TextOverflow.Ellipsis,
                    // 左右内缩与落位时的「整句宽度」估算同源（[NOTE_BUBBLE_PAD_RATIO]）：
                    // 估宽留的余量就是这里真占的内缩，否则 Text 会多折一行、被省略号截掉。
                    modifier = Modifier.padding(horizontal = with(d) { (b.fontPx * NOTE_BUBBLE_PAD_RATIO).toDp() }),
                )
            }
        }
    }
}

/** 箭头绘制：细线 + 小箭头，颜色取所属标注的墨色。 */
private fun DrawScope.drawNoteArrow(arrow: NoteArrow, color: Color) {
    val stroke = 1.dp.toPx()
    val path = Path()
    val points: List<Offset> = when (arrow) {
        is NoteArrow.Straight -> listOf(arrow.start, arrow.end)
        is NoteArrow.Elbow -> arrow.points
        is NoteArrow.Arc -> {
            path.moveTo(arrow.start.x, arrow.start.y)
            path.quadraticTo(arrow.control.x, arrow.control.y, arrow.end.x, arrow.end.y)
            emptyList()
        }
    }
    if (points.isNotEmpty()) {
        path.moveTo(points.first().x, points.first().y)
        for (p in points.drop(1)) path.lineTo(p.x, p.y)
    }
    drawPath(path, color = color, style = Stroke(width = stroke))

    // 箭头尖用两小段线，方向取最后一段（弧线取 92% 处的切线近似）。
    val tail = when (arrow) {
        is NoteArrow.Straight -> arrow.start
        is NoteArrow.Elbow -> arrow.points[arrow.points.size - 2]
        is NoteArrow.Arc -> quadraticPoint(arrow.start, arrow.control, arrow.end, 0.92f)
    }
    val dx = arrow.end.x - tail.x
    val dy = arrow.end.y - tail.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.01f) return
    val ux = dx / len
    val uy = dy / len
    val head = 5.dp.toPx()
    val half = 2.4.dp.toPx()
    val base = Offset(arrow.end.x - ux * head, arrow.end.y - uy * head)
    val normal = Offset(-uy, ux)
    drawLine(color, base + normal * half, arrow.end, strokeWidth = stroke)
    drawLine(color, base - normal * half, arrow.end, strokeWidth = stroke)
}
