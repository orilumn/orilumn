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

/** 页边距相对版心的偏好代价（以旁注行高为单位）：只是偏好，不是最后一档。 */
private const val MARGIN_PENALTY_LINES: Float = 1.5f

/** 每多一行的代价（以行高为单位）：同一档内优先排得舒展的。 */
private const val LINE_PENALTY_LINES: Float = 0.35f

/** 截断程度的附加代价（以行高为单位）：同属截断档内，显示越多越先。 */
private const val TRUNC_PENALTY_LINES: Float = 4f

/** 完整 > 截断的分界：完整候选（哪怕在页边距）绝对优先于任何截断候选。 */
private const val TRUNC_TIER: Float = 1_000_000f

/** 截断候选至少能读出的字数：只有省略号或一两个字的不算候选。 */
private const val MIN_SHOWN_CHARS: Int = 4

/** 每条批注最多保留多少个候选（按代价取前 N，避免极端页面候选爆炸）。 */
private const val MAX_CANDIDATES_PER_NOTE: Int = 300

/** 同侧页边距相邻两条旁注的间距（以旁注行高为单位）。 */
private const val LANE_GAP_LINES: Float = 0.35f

/** 成对交换时每条批注最多尝试的候选数：控制最坏复杂度，只影响极端小页。 */
private const val MAX_SWAP_CANDIDATES: Int = 40

/** 一行批注折行模拟的结果：比「宽 / 字宽」粗估准，用于完整性判定。 */
internal data class NoteTextFit(
    /** 整句需要几行（不限行数时的折行结果）。 */
    val lines: Int,
    /** 在给定行数容量下最多能显示几个字（不含换行符）。 */
    val shownChars: Int,
    /** 整句共几个字（不含换行符）。 */
    val totalChars: Int,
) {
    val complete: Boolean get() = shownChars >= totalChars
}

/** 一块空白落在版心里还是哪一侧页边距（页边距一侧做一维排布用）。 */
internal enum class MarginSide { NONE, LEFT, RIGHT, TOP, BOTTOM }

/** 一条批注在某个空闲矩形里的候选框（Step 2 的中间结果，纯几何）。 */
internal data class NoteCandidate(
    val annotationId: Long,
    /** 候选框本身（已按行数定好高）。 */
    val rect: Rect,
    /** 这个候选来自哪块空白：一维排布时只在原空白内挪，不越出可用范围。 */
    val freeRect: Rect,
    val lines: Int,
    val complete: Boolean,
    /** 截断时 = 显示出来的字 / 整句字数；完整时为 1。 */
    val shownFraction: Float,
    /** 完全落在版心内（左右页边距、页脚、页顶另外判）。 */
    val inContent: Boolean,
    val marginSide: MarginSide,
    /** 框到锚点墨迹的边到边最短距离（页边距与版心内同一口径）。 */
    val distance: Float,
    val cost: Float,
) {
    fun conflicts(other: NoteCandidate): Boolean =
        rect.left < other.rect.right + 2f && other.rect.left < rect.right + 2f &&
            rect.top < other.rect.bottom + 2f && other.rect.top < rect.bottom + 2f
}

/**
 * 折行模拟：按 [noteCharWidth] 逐字累加，超过可用宽就换行，`\n` 强制换行。
 *
 * @param maxLines 这块空白最多能放几行（由空白高度算出）。
 */
internal fun noteTextFit(
    text: String,
    widthPx: Float,
    fontPx: Float,
    maxLines: Int,
): NoteTextFit {
    val total = text.count { it != '\n' }
    if (total == 0) return NoteTextFit(lines = 0, shownChars = 0, totalChars = 0)
    if (widthPx <= 0f || fontPx <= 0f || maxLines < 1) {
        return NoteTextFit(lines = Int.MAX_VALUE, shownChars = 0, totalChars = total)
    }
    if (text.isEmpty()) return NoteTextFit(lines = 1, shownChars = 0, totalChars = 0)
    val (needed, _) = wrapNoteText(text, widthPx, fontPx, Int.MAX_VALUE)
    val (_, shown) = wrapNoteText(text, widthPx, fontPx, maxLines)
    return NoteTextFit(
        lines = needed,
        shownChars = min(shown, total),
        totalChars = total,
    )
}

/** @return (用到第几行, 放得下的字数)；超过 [maxLines] 后停止累计。 */
private fun wrapNoteText(
    text: String,
    widthPx: Float,
    fontPx: Float,
    maxLines: Int,
): Pair<Int, Int> {
    var lines = 1
    var px = 0f
    var shown = 0
    for (ch in text) {
        if (lines > maxLines) break
        if (ch == '\n') {
            lines++
            px = 0f
            continue
        }
        val w = noteCharWidth(ch.code, fontPx)
        if (px + w > widthPx + 0.01f && px > 0f) {
            lines++
            px = 0f
            if (lines > maxLines) break
        }
        px += w
        shown++
    }
    return lines to shown
}

/**
 * 每条批注 × 每个空闲矩形生成候选框（§7.C），并淘汰「只有省略号 / 少于 4 个字 /
 * 连一行都读不出」的候选。
 *
 * 候选宽度取 `min(整句单行宽, 矩形宽)`；位置取矩形内离锚点最近处，并留 [gap]。
 * 代价（§7.C）：
 * ```
 * cost = (完整 ? 0 : TRUNC_TIER)           // 完整级绝对优先
 *      + (版心内 ? 0 : MARGIN_PENALTY)      // 页边距只是偏好
 *      + 距离
 *      + (行数 - 1) * LINE_PENALTY
 *      + (完整 ? 0 : (1 - 显示比例) * TRUNC_PENALTY)
 * ```
 */
internal fun noteCandidates(
    note: NoteInk,
    freeRects: List<Rect>,
    contentLeft: Float,
    contentRight: Float,
    contentBottom: Float,
    fontPx: Float,
    lineH: Float,
    padX: Float,
    gap: Float,
    maxCandidates: Int = MAX_CANDIDATES_PER_NOTE,
): List<NoteCandidate> {
    val text = note.text.trim()
    if (text.isEmpty() || note.rects.isEmpty()) return emptyList()
    val anchor = rectUnion(note.rects)
    // 整句单行宽（略微放宽一点，给字形测量留余量）。
    val ideal = estimatedNoteTextWidth(text, fontPx) * 1.04f + padX
    val minShown = min(MIN_SHOWN_CHARS, text.count { it != '\n' }.coerceAtLeast(1))
    val out = ArrayList<NoteCandidate>(freeRects.size)

    for (f in freeRects) {
        if (f.width <= 0f || f.height <= 0f) continue
        val width = min(ideal, f.width)
        if (width - padX < fontPx * 2f) continue
        val usable = width - padX
        val capacity = (f.height / lineH).toInt()
        if (capacity < 1) continue
        val fit = noteTextFit(text, usable, fontPx, capacity)
        if (fit.totalChars == 0) continue
        val lines = min(fit.lines, capacity)
        val complete = fit.complete
        if (!complete) {
            if (fit.shownChars < minShown) continue
            if (usable < fontPx * 3f) continue
        }
        val height = lines * lineH

        // 位置：框在空白矩形内取离锚点最近处，再按贴边方向补 gap。
        val x = when {
            f.right <= anchor.left - NOTE_GEOM_EPS -> f.right - width
            f.left >= anchor.right - NOTE_GEOM_EPS -> max(f.left, anchor.right + gap)
            else -> (anchor.center.x - width / 2f).coerceIn(f.left, max(f.left, f.right - width))
        }.coerceIn(f.left, max(f.left, f.right - width))
        val y = when {
            f.bottom <= anchor.top - NOTE_GEOM_EPS -> f.bottom - height
            f.top >= anchor.bottom - NOTE_GEOM_EPS -> max(f.top, anchor.bottom + gap)
            else -> (anchor.center.y - height / 2f).coerceIn(f.top, max(f.top, f.bottom - height))
        }.coerceIn(f.top, max(f.top, f.bottom - height))
        val rect = Rect(x, y, x + width, y + height)

        val inContent = rect.left >= contentLeft - NOTE_GEOM_EPS &&
            rect.right <= contentRight + NOTE_GEOM_EPS &&
            rect.top >= -NOTE_GEOM_EPS &&
            rect.bottom <= contentBottom + NOTE_GEOM_EPS
        val side = when {
            inContent -> MarginSide.NONE
            rect.left >= contentRight - NOTE_GEOM_EPS -> MarginSide.RIGHT
            rect.right <= contentLeft + NOTE_GEOM_EPS -> MarginSide.LEFT
            rect.top >= contentBottom - NOTE_GEOM_EPS -> MarginSide.BOTTOM
            rect.bottom <= NOTE_GEOM_EPS -> MarginSide.TOP
            else -> MarginSide.NONE
        }
        val shownFraction = if (fit.totalChars == 0) 1f else fit.shownChars.toFloat() / fit.totalChars
        val distance = note.rects.minOf { rectDistance(rect, it) }
        val cost = (if (complete) 0f else TRUNC_TIER) +
            (if (inContent) 0f else MARGIN_PENALTY_LINES * lineH) +
            distance +
            max(0, lines - 1) * LINE_PENALTY_LINES * lineH +
            (if (complete) 0f else (1f - shownFraction) * TRUNC_PENALTY_LINES * lineH)
        out += NoteCandidate(
            annotationId = note.annotationId,
            rect = rect,
            freeRect = f,
            lines = lines,
            complete = complete,
            shownFraction = shownFraction,
            inContent = inContent,
            marginSide = side,
            distance = distance,
            cost = cost,
        )
    }
    return out
        .sortedWith(compareBy({ it.cost }, { it.rect.top }, { it.rect.left }))
        .take(maxCandidates)
}


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
 * 给本页每条批注在**整页范围内统一布点**（纯函数，无 Compose 依赖）。
 *
 * 流水线（`docs/旁注布点算法重做-实施计划.md` §5）：
 * 1. 正文按行合并成行框，连同图形 / 表格 / 线条一起当障碍；
 * 2. [maximalFreeRects] 一次算出整页的极大空闲矩形（含四个页边距）；
 * 3. [noteCandidates] 为每条批注 × 每块空白生成候选框（整句可读优先、截断兜底）；
 * 4. [assignNoteCandidates] 全页统一分配：最受限优先贪心 → 页边距一维排布 → 两轮局部搜索；
 * 5. 每条框再路由一条指向原文的箭头。
 *
 * 不重叠是硬约束；整页都没有候选的批注本页不显示（宁可不显示，也不放只有省略号的框）。
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
    val padX = small * NOTE_BUBBLE_PAD_RATIO * 2f
    // 旁注框与墨迹之间留一线：贴在字上会让人觉得把正文框进去了。
    val gap = body * 0.25f

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
    val freeRects = maximalFreeRects(
        bounds = pageBounds,
        obstacles = baseObstacles,
        minW = max(small * 1.2f, 1f),
        minH = lineH,
    )
    if (freeRects.isEmpty()) return emptyList()

    // 固定顺序：锚点自上而下，同高按 id —— 布点结果只跟输入几何有关，可复现。
    val ordered = noteInk
        .filter { it.text.isNotBlank() && it.rects.isNotEmpty() }
        .sortedWith(compareBy({ it.rects.minOf { r -> r.top } }, { it.annotationId }))
    if (ordered.isEmpty()) return emptyList()

    val candidatesByNote = HashMap<Long, List<NoteCandidate>>(ordered.size)
    for (note in ordered) {
        val list = noteCandidates(
            note = note,
            freeRects = freeRects,
            contentLeft = contentLeft,
            contentRight = contentRight,
            contentBottom = contentBottom,
            fontPx = small,
            lineH = lineH,
            padX = padX,
            gap = gap,
        )
        if (list.isNotEmpty()) candidatesByNote[note.annotationId] = list
    }
    if (candidatesByNote.isEmpty()) return emptyList()

    val choices = assignNoteCandidates(ordered, candidatesByNote, LANE_GAP_LINES * lineH)
    if (choices.isEmpty()) return emptyList()

    val out = choices.map { choice ->
        NoteBubble(
            annotationId = choice.note.annotationId,
            text = choice.note.text.trim(),
            leftPx = choice.candidate.rect.left,
            topPx = choice.candidate.rect.top,
            widthPx = choice.candidate.rect.width,
            maxLines = choice.candidate.lines,
            argb = choice.note.argb,
            fontPx = small,
        )
    }

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

/** 全页分配的结果：这条批注最终用哪个候选框。 */
private data class NoteChoice(
    val note: NoteInk,
    val candidate: NoteCandidate,
)

/** 一条批注的分配请求（候选已按代价排好序）。 */
private class NoteRequest(
    val note: NoteInk,
    val anchor: Rect,
    val candidates: List<NoteCandidate>,
)

/**
 * 全页统一分配（§7.D）：最受限优先贪心 + 页边距一维排布 + 两轮局部搜索。
 *
 * 1. **最受限优先**：候选越少越先分（受限制最大），同数按锚点 `top`、`id`；
 * 2. **贪心**：选第一条与已放框不冲突的候选；没有时若最佳候选在页边距，先占位，
 *    交给一维排布腾挪（同侧按锚点纵序前推 / 回推，互不重叠）；
 * 3. **局部搜索**：单条移动 + 成对交换（批注 ≤ 8 时），总代价下降才换；
 * 4. **兜底**：仍没分到的再贪心一次，还没有就本页不显示那条批注。
 *
 * @param notes 已经过滤空批注 / 按锚点排好序的批注。
 * @param candidatesByNote 每条批注的候选（按代价升序）。
 * @param laneGap 同侧页边距相邻框的间距（像素）。
 */
private fun assignNoteCandidates(
    notes: List<NoteInk>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    laneGap: Float,
): List<NoteChoice> {
    val requests = notes.mapNotNull { note ->
        val candidates = candidatesByNote[note.annotationId] ?: return@mapNotNull null
        if (candidates.isEmpty()) return@mapNotNull null
        NoteRequest(note, rectUnion(note.rects), candidates)
    }
    if (requests.isEmpty()) return emptyList()

    // 最受限优先：候选少 = 受限制大；同数按锚点 top、id。
    val order = requests.indices.sortedWith(
        compareBy(
            { requests[it].candidates.size },
            { requests[it].anchor.top },
            { requests[it].note.annotationId },
        ),
    )
    val selected = arrayOfNulls<NoteCandidate>(requests.size)

    // 1. 贪心：先选不冲突的最低代价候选；页边距候选可先占位。
    for (i in order) {
        val request = requests[i]
        val pick = request.candidates.firstOrNull { fits(it, selected, i) }
        if (pick != null) {
            selected[i] = pick
            continue
        }
        val best = request.candidates.first()
        if (best.marginSide != MarginSide.NONE) selected[i] = best
    }

    // 2. 页边距一维排布：同侧同块空白的框按锚点纵序推开。
    packMarginLanes(requests, selected, laneGap)

    // 3. 两轮局部搜索：单条移动 + 成对交换。
    localSearch(requests, selected)

    // 4. 兜底：腾挪后可能空出了位置，再给还没分到的批注一次机会。
    for (i in order) {
        if (selected[i] != null) continue
        selected[i] = requests[i].candidates.firstOrNull { fits(it, selected, i) }
    }

    // 5. 硬约束兜底：任何残余重叠都放弃较先（离锚点更远）的那条。
    dropConflicts(requests, selected)

    return requests.indices.mapNotNull { i ->
        val pick = selected[i] ?: return@mapNotNull null
        NoteChoice(requests[i].note, pick)
    }
}

/**
 * 页边距一维排布（§7.D.4）：同一侧、同一块空白里的框按锚点顺序排开。
 *
 * 纵向页边距（左 / 右）排 Y，横向（上 / 下）排 X；做两遍（前推 + 回推），
 * 让框尽量对齐自己的锚点，又保持 [laneGap] 的间距。整组放不下或与别的框相撞时，
 * 放弃这一组里对应的框，交给后面的兜底贪心或直接不显示（宁缺勿叠）。
 */
private fun packMarginLanes(
    requests: List<NoteRequest>,
    selected: Array<NoteCandidate?>,
    laneGap: Float,
) {
    for (side in MARGIN_SIDES) {
        val members = selected.indices.filter { i -> selected[i]?.marginSide == side }
        if (members.isEmpty()) continue
        // 只在同一块空白里排（不同空白的可用范围不同，硬排会压到障碍）。
        val groups = LinkedHashMap<LaneKey, MutableList<Int>>()
        for (i in members) {
            groups.getOrPut(LaneKey.of(selected[i]!!.freeRect)) { mutableListOf() } += i
        }
        for (group in groups.values) {
            if (group.size < 2) {
                // 单条页边距框也要验收：占位候选可能与版心内的框重叠。
                val i = group.single()
                val c = selected[i] ?: continue
                val clash = selected.indices.any { j ->
                    j != i && selected[j]?.let { c.conflicts(it) } == true
                }
                if (clash) selected[i] = null
                continue
            }
            if (side == MarginSide.LEFT || side == MarginSide.RIGHT) {
                packLaneVertical(requests, selected, group, laneGap)
            } else {
                packLaneHorizontal(requests, selected, group, laneGap)
            }
        }
    }
}

/** 同一块空白的一维排布分组键：四边取整，避免浮点误差把同一条边距拆成两组。 */
private data class LaneKey(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        fun of(rect: Rect) = LaneKey(
            rect.left.roundToInt(),
            rect.top.roundToInt(),
            rect.right.roundToInt(),
            rect.bottom.roundToInt(),
        )
    }
}

/** 左右页边距：沿 Y 前推 / 回推，宽度与 X 不变。 */
private fun packLaneVertical(
    requests: List<NoteRequest>,
    selected: Array<NoteCandidate?>,
    group: List<Int>,
    laneGap: Float,
) {
    val free = selected[group.first()]!!.freeRect
    val ordered = group.sortedWith(
        compareBy({ requests[it].anchor.center.y }, { requests[it].note.annotationId }),
    )
    val heights = ordered.map { selected[it]!!.rect.height }
    val tops = FloatArray(ordered.size)

    var cursor = free.top
    for (k in ordered.indices) {
        val h = heights[k]
        val desired = (requests[ordered[k]].anchor.center.y - h / 2f)
            .coerceIn(free.top, max(free.top, free.bottom - h))
        tops[k] = max(desired, cursor)
        cursor = tops[k] + h + laneGap
    }
    cursor = free.bottom
    for (k in ordered.indices.reversed()) {
        tops[k] = min(tops[k], cursor - heights[k])
        cursor = tops[k] - laneGap
    }

    var inBounds = true
    for (k in ordered.indices) {
        if (tops[k] < free.top - NOTE_GEOM_EPS ||
            tops[k] + heights[k] > free.bottom + NOTE_GEOM_EPS
        ) {
            inBounds = false
            break
        }
    }
    if (!inBounds) {
        for (i in ordered) selected[i] = null
        return
    }
    for (k in ordered.indices) {
        val i = ordered[k]
        val c = selected[i]!!
        selected[i] = c.copy(rect = Rect(c.rect.left, tops[k], c.rect.right, tops[k] + heights[k]))
    }
    dropLaneConflicts(selected, ordered)
}

/** 上下页边距：沿 X 前推 / 回推，高度与 Y 不变。 */
private fun packLaneHorizontal(
    requests: List<NoteRequest>,
    selected: Array<NoteCandidate?>,
    group: List<Int>,
    laneGap: Float,
) {
    val free = selected[group.first()]!!.freeRect
    val ordered = group.sortedWith(
        compareBy({ requests[it].anchor.center.x }, { requests[it].note.annotationId }),
    )
    val widths = ordered.map { selected[it]!!.rect.width }
    val lefts = FloatArray(ordered.size)

    var cursor = free.left
    for (k in ordered.indices) {
        val w = widths[k]
        val desired = (requests[ordered[k]].anchor.center.x - w / 2f)
            .coerceIn(free.left, max(free.left, free.right - w))
        lefts[k] = max(desired, cursor)
        cursor = lefts[k] + w + laneGap
    }
    cursor = free.right
    for (k in ordered.indices.reversed()) {
        lefts[k] = min(lefts[k], cursor - widths[k])
        cursor = lefts[k] - laneGap
    }

    var inBounds = true
    for (k in ordered.indices) {
        if (lefts[k] < free.left - NOTE_GEOM_EPS ||
            lefts[k] + widths[k] > free.right + NOTE_GEOM_EPS
        ) {
            inBounds = false
            break
        }
    }
    if (!inBounds) {
        for (i in ordered) selected[i] = null
        return
    }
    for (k in ordered.indices) {
        val i = ordered[k]
        val c = selected[i]!!
        selected[i] = c.copy(rect = Rect(lefts[k], c.rect.top, lefts[k] + widths[k], c.rect.bottom))
    }
    dropLaneConflicts(selected, ordered)
}

/** 一维排布后仍与其他框相撞的，按顺序放弃（先撞上的那条让位）。 */
private fun dropLaneConflicts(selected: Array<NoteCandidate?>, group: List<Int>) {
    val dropped = HashSet<Int>()
    for (i in group) {
        val c = selected[i] ?: continue
        val clash = selected.indices.any { j ->
            j != i && j !in dropped && selected[j]?.let { c.conflicts(it) } == true
        }
        if (clash) {
            dropped += i
            selected[i] = null
        }
    }
}

/** 两轮局部搜索（§7.D.3）：总代价严格下降才换，且换完仍满足不重叠硬约束。 */
private fun localSearch(requests: List<NoteRequest>, selected: Array<NoteCandidate?>) {
    repeat(2) {
        var changed = false
        // 单条移动：换成更低代价且不冲突的候选。
        for (i in requests.indices) {
            val current = selected[i] ?: continue
            for (candidate in requests[i].candidates) {
                if (candidate.cost >= current.cost - 0.01f) break
                if (candidate == current) break
                if (fits(candidate, selected, i)) {
                    selected[i] = candidate
                    changed = true
                    break
                }
            }
        }
        // 成对交换：批注少时才做，避免组合爆炸。
        if (requests.size <= 8) {
            for (i in requests.indices) {
                for (j in i + 1 until requests.size) {
                    val ci = selected[i] ?: continue
                    val cj = selected[j] ?: continue
                    var bestTotal = ci.cost + cj.cost - 0.01f
                    var bestJ: NoteCandidate? = null
                    var bestI: NoteCandidate? = null
                    for (a in requests[j].candidates.take(MAX_SWAP_CANDIDATES)) {
                        if (a.cost >= bestTotal) break
                        if (!fits(a, selected, j, i)) continue
                        for (b in requests[i].candidates.take(MAX_SWAP_CANDIDATES)) {
                            val total = a.cost + b.cost
                            if (total >= bestTotal) break
                            if (a.conflicts(b)) continue
                            if (!fits(b, selected, i, j)) continue
                            bestJ = a
                            bestI = b
                            bestTotal = total
                        }
                    }
                    val newJ = bestJ
                    val newI = bestI
                    if (newJ != null && newI != null) {
                        selected[j] = newJ
                        selected[i] = newI
                        changed = true
                    }
                }
            }
        }
        if (!changed) return
    }
}

/** 任何残余重叠都放弃较先选中的（通常是离锚点更远、代价更高的那条）。 */
private fun dropConflicts(requests: List<NoteRequest>, selected: Array<NoteCandidate?>) {
    val dropped = HashSet<Int>()
    for (i in requests.indices) {
        val c = selected[i] ?: continue
        val clash = selected.indices.any { j ->
            j != i && j !in dropped && selected[j]?.let { c.conflicts(it) } == true
        }
        if (clash) {
            dropped += i
            selected[i] = null
        }
    }
}

/** 候选框是否与除 [ignoreA] / [ignoreB] 之外的已选框都不相交。 */
private fun fits(
    candidate: NoteCandidate,
    selected: Array<NoteCandidate?>,
    ignoreA: Int,
    ignoreB: Int = -1,
): Boolean {
    for (k in selected.indices) {
        if (k == ignoreA || k == ignoreB) continue
        val other = selected[k] ?: continue
        if (candidate.conflicts(other)) return false
    }
    return true
}

/** 页边距四个方向（排布顺序固定，保证可复现）。 */
private val MARGIN_SIDES = listOf(
    MarginSide.RIGHT,
    MarginSide.LEFT,
    MarginSide.BOTTOM,
    MarginSide.TOP,
)

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
