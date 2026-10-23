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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * 才知道这条标注写了什么。文字块由 [noteBubblePlacements] 按「整句可读优先」在整页空白里
 * 统一布点，**不改排版、不进栅格**——它只是盖在页面之上的一层，翻页即随页重组
 * （[ReaderScreen] 在翻页动画期间隐藏它）。
 *
 * **两种形态（§2.1）**，由 [compact] 区分：
 * - **文字框**（`compact = false`，常态）：按 [maxLines] 行显示批注正文，配 [arrow] 指向原文；
 * - **小图标**（`compact = true`，兜底）：整页没有能读的空白时才出现，在原文右下角画一个小椭圆
 *   省略号；[leftPx]/[topPx] 是椭圆左上角、[widthPx] 是直径、[maxLines] 恒为 1。
 *   这个形态**不画箭头**，也不参与其他旁注的避让。
 *
 * @property annotationId 这条批注属于哪条标注（点它 = 回去改这句话）。
 * @property text 批注正文（完整文本，超出可用行数由 Text 自己省略）。
 * @property leftPx/topPx 页面坐标（屏幕像素）；小图标形态下是椭圆左上角。
 * @property widthPx 文字框宽度（像素，由空白与整句宽取小）；小图标形态下是椭圆直径。
 * @property maxLines 文字框实际要用几行（1 起，已按「整句优先」与空白高度取小）；小图标形态恒为 1。
 * @property argb 所属标注的墨色：文字框用它做淡底与左侧色条，读者一眼看出是哪一条。
 * @property fontPx 批注字号（像素）：比正文小一号，读起来不抢正文。
 * @property arrow 指向原文的箭头：小图标形态恒为 `null`；文字框在箭头路由无解时暂为 `null`
 *   （§2.2 收口后除小图标外必画）。
 * @property compact 小图标形态（见上）：只在一条都排不下时由调用方置位。
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
    val compact: Boolean = false,
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

/** 最少可读的字数：能显示这么多个字就显示框（一行也行）；再少就退成小图标。 */
private const val MIN_SHOWN_CHARS: Int = 4

/** 右下角小图标的直径（以旁注字号为单位）：只是「这里有一条批注」的记号，不抢正文。 */
private const val NOTE_ICON_DIAMETER_RATIO: Float = 1.5f

/** 每条批注最多保留多少个候选（按排序取前 N，避免极端页面候选爆炸）。 */
private const val MAX_CANDIDATES_PER_NOTE: Int = 300

/** 同侧页边距相邻两条旁注的间距（以旁注行高为单位）。 */
private const val LANE_GAP_LINES: Float = 0.35f

/** 引线每个拐弯的代价（以旁注行高为单位）：宁可多走一点，也少拐弯。 */
private const val ARROW_BEND_PENALTY_LINES: Float = 0.6f

/** 引线穿越已画引线的代价（以旁注行高为单位）：软惩罚，不硬堵死。 */
private const val ARROW_CROSS_PENALTY_LINES: Float = 3f

/** 可见图节点上限：超过就放弃图搜索，退回直线 / 单折线兜底。 */
private const val MAX_VISIBILITY_NODES: Int = 60_000

/** 可见图里的方向：无 / 水平 / 垂直（换向就是拐弯）。 */
private const val ROUTE_DIR_NONE: Int = 0
private const val ROUTE_DIR_H: Int = 1
private const val ROUTE_DIR_V: Int = 2

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

/**
 * 一条批注在某块空白里的候选框（Step 2 的中间结果，纯几何）。
 *
 * 字段只服务两件事：**排序**（[complete] / [shownChars] / [distance] / [lines] / [inContent]）
 * 与**落位**（[rect] 是最终框，[freeRect] 是页边距一维排布的活动范围）。
 * 旧加权 `cost` 与截断比例已按 §2.1 删除：完整与否、显示字数、距离各自成键，互不折算。
 */
internal data class NoteCandidate(
    val annotationId: Long,
    /** 候选框本身（已按行数定好高）。 */
    val rect: Rect,
    /** 这个候选来自哪块空白：一维排布时只在原空白内挪，不越出可用范围。 */
    val freeRect: Rect,
    /** 候选框占几行（1 起）。 */
    val lines: Int,
    /** 整句在这一框里放得下（按 [shownChars] 与整句字数判）。 */
    val complete: Boolean,
    /** 折行后实际能显示的字数（不含换行符）：没有完整候选时按它取最多。 */
    val shownChars: Int,
    /** 完全落在版心内（左右页边距、页脚、页顶另外判）。 */
    val inContent: Boolean,
    /** 落在哪一侧页边距（[MarginSide.NONE] = 版心内）：页边距一维排布按它分组。 */
    val marginSide: MarginSide,
    /** 框到锚点**最近一行**墨迹的边到边最短距离（只用于排序）。 */
    val distance: Float,
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
 * 每条批注 × 每个空闲矩形生成候选框（§7.C）。
 *
 * - **位置**：在空白内取离锚点**最近一行**墨迹最近的地方，并留 [gap]；
 * - **附近**：框与那一行之间的视线若被别的文字 / 图 / 表格 / 线条挡住，这个候选直接丢弃
 *   （「附近」不按行数，按视线，见实施计划 §2.1）；
 * - **可读量**：折行后不足 [MIN_SHOWN_CHARS] 个字（整句不足时按整句）就不算候选；
 *   整句放得下 = 完整候选；否则按显示字数排序。
 *
 * [blockers] 是布点用的障碍（行框 + 图形 + 表格 + 线条），只用于视线判定；
 * 与锚点墨迹相交的那几个会先剔掉（锚点自己所在的行不能挡住自己）。
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
    blockers: List<Rect> = emptyList(),
    maxCandidates: Int = MAX_CANDIDATES_PER_NOTE,
): List<NoteCandidate> {
    val text = note.text.trim()
    if (text.isEmpty() || note.rects.isEmpty()) return emptyList()
    // 整句单行宽（略微放宽一点，给字形测量留余量）。
    val ideal = estimatedNoteTextWidth(text, fontPx) * 1.04f + padX
    val minShown = min(MIN_SHOWN_CHARS, text.count { it != '\n' }.coerceAtLeast(1))
    // 锚点自己的行框 / 背景不参与视线判定，否则锚点所在整行会把所有候选都挡掉。
    val sightBlockers = blockers.filterNot { b ->
        note.rects.any { ink ->
            ink.left < b.right - NOTE_GEOM_EPS && b.left < ink.right - NOTE_GEOM_EPS &&
                ink.top < b.bottom - NOTE_GEOM_EPS && b.top < ink.bottom - NOTE_GEOM_EPS
        }
    }
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
        if (fit.shownChars < minShown) continue
        val lines = min(fit.lines, capacity)
        val height = lines * lineH

        // 距离与位置都以**最近那一行**墨迹为基准（多行锚点不用整段并集）。
        val anchor = note.rects.minByOrNull { rectDistance(f, it) } ?: continue
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
        // 视线被别的文字 / 图 / 表 / 线挡住 = 不在锚点附近，不生成候选。
        if (sightLineBlocked(rect, anchor, sightBlockers)) continue

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
        out += NoteCandidate(
            annotationId = note.annotationId,
            rect = rect,
            freeRect = f,
            lines = lines,
            complete = fit.complete,
            shownChars = fit.shownChars,
            inContent = inContent,
            marginSide = side,
            distance = rectDistance(rect, anchor),
        )
    }
    // 排序（§2.1）：完整候选按距离最近（同距离再比行数、版心内、位置）；
    // 一个完整都没有时按显示字数最多，同字数再比距离。不再有加权 cost，页边距 / 行数不参与惩罚。
    return out
        .sortedWith(
            compareBy<NoteCandidate> { if (it.complete) 0 else 1 }
                .thenBy { if (it.complete) it.distance else -it.shownChars.toFloat() }
                .thenBy { it.distance }
                .thenBy { it.lines }
                .thenBy { if (it.inContent) 0 else 1 }
                .thenBy { it.rect.top }
                .thenBy { it.rect.left },
        )
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
 * 路由用正交可见图 + A*：代价 = 长度 + 拐弯惩罚 + 与已画引线的交叉惩罚，避开四类障碍、
 * 其他旁注与已画引线；图搜索无解时才退回直线 / 单折线，仍无解就不画箭头
 * （宁可少一条线，也不糊住正文）。
 */
internal sealed interface NoteArrow {
    val start: Offset
    val end: Offset

    data class Straight(override val start: Offset, override val end: Offset) : NoteArrow

    /** 正交折线：起点 → 拐点 → … → 终点（至少 3 个点，相邻两点共享 x 或 y）。 */
    data class Elbow(val points: List<Offset>) : NoteArrow {
        override val start: Offset get() = points.first()
        override val end: Offset get() = points.last()
    }
}

/**
 * 给本页每条批注在**整页范围内统一布点**（纯函数，无 Compose 依赖）。
 *
 * 流水线（`docs/旁注布点算法重做-实施计划.md` §5）：
 * 1. 正文按行合并成行框，连同图形 / 表格 / 线条一起当障碍；
 * 2. [maximalFreeRects] 一次算出整页的极大空闲矩形（含四个页边距）；
 * 3. [noteCandidates] 为每条批注 × 每块空白生成候选框（视线内就近、整句优先、4 字保底）；
 * 4. [assignNoteCandidates] 全页分配：候选少 → 更近 → 正文靠前，单向让位、不互换；
 * 5. 每条框再路由一条指向原文的箭头（小图标形态没有箭头）。
 *
 * 不重叠是硬约束；一条都排不下的批注退成原文右下角的小省略号（[NoteBubble.compact]），
 * 不再整条消失。空白的判据仍然只认四类障碍（D-28 第 3 部分）：文字、图形、表格、线条；
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
            blockers = baseObstacles,
        )
        if (list.isNotEmpty()) candidatesByNote[note.annotationId] = list
    }

    val choices = assignNoteCandidates(ordered, candidatesByNote, LANE_GAP_LINES * lineH)
    val chosen = choices.associateBy { it.note.annotationId }
    // 一条都分不到的批注不再消失：在原文右下角退成一个小省略号（§2.1）。
    val iconSize = small * NOTE_ICON_DIAMETER_RATIO
    val out = ordered.map { note ->
        val choice = chosen[note.annotationId]
        if (choice == null) {
            val icon = noteIconRect(rectUnion(note.rects), gap * 0.4f, iconSize, pageBounds)
            NoteBubble(
                annotationId = note.annotationId,
                text = note.text.trim(),
                leftPx = icon.left,
                topPx = icon.top,
                widthPx = iconSize,
                maxLines = 1,
                argb = note.argb,
                fontPx = small,
                compact = true,
            )
        } else {
            NoteBubble(
                annotationId = note.annotationId,
                text = note.text.trim(),
                leftPx = choice.candidate.rect.left,
                topPx = choice.candidate.rect.top,
                widthPx = choice.candidate.rect.width,
                maxLines = choice.candidate.lines,
                argb = note.argb,
                fontPx = small,
            )
        }
    }

    val inkById = noteInk.associateBy { it.annotationId }
    val bubbleRects = out.filterNot { it.compact }.associate { b ->
        b.annotationId to Rect(b.leftPx, b.topPx, b.leftPx + b.widthPx, b.topPx + b.maxLines * lineH)
    }
    // 逐条路由：先画的先占位置，后画的把已画引线当软障碍（尽量不交叉）。
    val drawn = ArrayList<NoteArrow>(out.size)
    return out.map { b ->
        // 小图标形态没有可指向的框，本来就不画箭头（§2.1）。
        if (b.compact) return@map b
        val ink = inkById[b.annotationId]?.rects.orEmpty()
        val self = bubbleRects.getValue(b.annotationId)
        val others = out.asSequence()
            .filter { it.annotationId != b.annotationId && !it.compact }
            .mapNotNull { bubbleRects[it.annotationId] }
            .toList()
        val arrow = routeNoteArrow(
            bubble = self,
            inkRects = ink,
            obstacles = baseObstacles,
            otherBubbles = others,
            pageBounds = pageBounds,
            drawnArrows = drawn,
            lineH = lineH,
        )
        if (arrow != null) drawn += arrow
        b.copy(arrow = arrow)
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
 * 全页分配（§4.3）：**单向让位**，不做两两互换。
 *
 * 1. **谁先挑**：候选少 → 候选里最近的那个更近 → 正文流靠前（锚点 top、id）；
 * 2. **先挑者拿走自己最好的候选**（完整优先、其次显示字数最多，候选已排好序），
 *    后来者只能跳过冲突候选，在自己剩下的候选里取最好的；
 * 3. **页边距一维排布**只对同一侧页边距里的框做上下 / 左右推开，不改变先挑顺序；
 * 4. 一条都分不到的批注交给调用方退成小图标，**不重叠、不压字**。
 *
 * @param notes 已经过滤空批注 / 按锚点排好序的批注。
 * @param candidatesByNote 每条批注的候选（已按完整 → 距离 → 显示字数排好序）。
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

    // 先挑顺序：候选少 → 最近候选更近 → 正文流靠前（锚点 top、id）。
    val order = requests.indices.sortedWith(
        compareBy(
            { requests[it].candidates.size },
            { requests[it].candidates.minOf { c -> c.distance } },
            { requests[it].anchor.top },
            { requests[it].note.annotationId },
        ),
    )
    val selected = arrayOfNulls<NoteCandidate>(requests.size)

    // 单向让位：按顺序各取自己最好的、不与先挑者冲突的候选；先挑者永不被挤走。
    for (i in order) {
        selected[i] = requests[i].candidates.firstOrNull { fits(it, selected, i) }
    }

    // 页边距一维排布：同侧同块空白的框按锚点纵序推开（只动这一组）。
    packMarginLanes(requests, selected, laneGap)

    // 排布后可能空出位置：还没分到的批注再试一次（仍不碰先挑者）。
    for (i in order) {
        if (selected[i] != null) continue
        selected[i] = requests[i].candidates.firstOrNull { fits(it, selected, i) }
    }

    // 硬约束兜底：任何残余重叠都放弃后挑的那条（交给小图标）。
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

/** 任何残余重叠都放弃后挑的那条（交给小图标，宁缺勿叠）。 */
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
 * 与已经移除的「四块互斥切分」不同：每个障碍把相交的空闲矩形切成上 / 下 / 左 / 右后，
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

/** 矩形上离 [p] 最近的点。 */
private fun nearestPointOn(r: Rect, p: Offset): Offset =
    Offset(p.x.coerceIn(r.left, r.right), p.y.coerceIn(r.top, r.bottom))

/**
 * 小图标落位（§2.1）：优先贴在锚点墨迹的**右下角外侧**，且保证不压锚点墨迹。
 *
 * 右下角放不下（贴到页面右下边界）时依次试右上、右下贴框、右上贴框、左下、左上；
 * 都不在页内才把首选位置夹回页面（此时只求有记号，不求不压）。
 */
private fun noteIconRect(ink: Rect, gap: Float, size: Float, page: Rect): Rect {
    val xRight = ink.right + gap
    val xFlush = ink.right - size
    val yBelow = ink.bottom + gap
    val yAbove = ink.top - gap - size
    val candidates = listOf(
        Rect(xRight, yBelow, xRight + size, yBelow + size),
        Rect(xRight, yAbove, xRight + size, yAbove + size),
        Rect(xFlush, yBelow, xFlush + size, yBelow + size),
        Rect(xFlush, yAbove, xFlush + size, yAbove + size),
        Rect(ink.left - gap - size, yBelow, ink.left - gap, yBelow + size),
        Rect(ink.left - gap - size, yAbove, ink.left - gap, yAbove + size),
    )
    fun inside(r: Rect) = r.left >= page.left - NOTE_GEOM_EPS && r.top >= page.top - NOTE_GEOM_EPS &&
        r.right <= page.right + NOTE_GEOM_EPS && r.bottom <= page.bottom + NOTE_GEOM_EPS
    val within = candidates.filter(::inside)
    within.firstOrNull { !it.overlaps(ink) }?.let { return it }
    candidates.firstOrNull { !it.overlaps(ink) }?.let { r ->
        val cx = r.left.coerceIn(page.left, max(page.left, page.right - size))
        val cy = r.top.coerceIn(page.top, max(page.top, page.bottom - size))
        return Rect(cx, cy, cx + size, cy + size)
    }
    within.firstOrNull()?.let { return it }
    val x = candidates.first().left.coerceIn(page.left, max(page.left, page.right - size))
    val y = candidates.first().top.coerceIn(page.top, max(page.top, page.bottom - size))
    return Rect(x, y, x + size, y + size)
}

/**
 * 两个矩形之间是否被挡住：取两者最近点连成的线段，穿过任何障碍内部就算挡住。
 *
 * 这就是 §2.1 的「附近」判据——不按行数，按视线。贴边不算穿过（容差 [NOTE_GEOM_EPS]）。
 */
internal fun sightLineBlocked(from: Rect, to: Rect, blockers: List<Rect>): Boolean {
    if (blockers.isEmpty()) return false
    val a = nearestPointOn(from, to.center)
    val b = nearestPointOn(to, a)
    val start = nearestPointOn(from, b)
    val end = b
    if (rectDistance(from, to) <= NOTE_GEOM_EPS) return false
    return blockers.any { segmentCrossesInterior(start, end, it) }
}

/**
 * 线段是否穿过矩形**内部**（Liang–Barsky；先按 [NOTE_GEOM_EPS] 内缩，贴边不算）。
 */
private fun segmentCrossesInterior(a: Offset, b: Offset, r: Rect): Boolean {
    var t0 = 0f
    var t1 = 1f
    val dx = b.x - a.x
    val dy = b.y - a.y
    val left = r.left + NOTE_GEOM_EPS
    val right = r.right - NOTE_GEOM_EPS
    val top = r.top + NOTE_GEOM_EPS
    val bottom = r.bottom - NOTE_GEOM_EPS
    if (left >= right || top >= bottom) return false
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - left, right - a.x, a.y - top, bottom - a.y)
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
    return t0 < t1
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
 * 给一条旁注找箭头路由（纯函数）：直线 → 正交可见图 + A* → 单折线兜底 → 不画。
 *
 * 只避四类障碍、**已放好的其他旁注**与**已画引线**；这条批注自己的墨迹与自己的旁注框
 * 不算障碍（箭头就是要从框边指回原文）。从块底色 / 内边距 / 页边距上穿过是允许的。
 *
 * @param pageBounds 页面边界（可见图的搜索范围）；null 时取框、墨迹与其他旁注的并集。
 * @param drawnArrows 已经画好的引线：尽量不穿越（软惩罚，不硬堵死）。
 * @param lineH 旁注行高（拐弯 / 交叉惩罚以它为单位）；0 时退用框高。
 */
internal fun routeNoteArrow(
    bubble: Rect,
    inkRects: List<Rect>,
    obstacles: List<Rect>,
    otherBubbles: List<Rect>,
    pageBounds: Rect? = null,
    drawnArrows: List<NoteArrow> = emptyList(),
    lineH: Float = 0f,
): NoteArrow? {
    if (inkRects.isEmpty() || bubble.width <= 0f || bubble.height <= 0f) return null
    val target = inkRects.minByOrNull { rectDistance(bubble, it) } ?: return null
    // 端口：框朝墨迹一侧的边 → 墨迹朝框一侧的边；箭头尖停在字外一线，不压字。
    val start = portOnRect(bubble, target.center)
    val site = portOnRect(target, bubble.center)
    val outward = site - target.center
    val outwardLen = sqrt(outward.x * outward.x + outward.y * outward.y)
    val end = if (outwardLen < 0.01f) {
        start
    } else {
        site + Offset(outward.x / outwardLen, outward.y / outwardLen) * NOTE_ARROW_END_GAP_PX
    }
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

    val unit = if (lineH > 0f) lineH else bubble.height.coerceAtLeast(8f)
    val bounds = pageBounds ?: unionBounds(bubble, inkRects, otherBubbles)
    val path = routeOrthogonal(
        start = start,
        end = end,
        bubble = bubble,
        blockers = blockers,
        drawnArrows = drawnArrows,
        bounds = bounds,
        bendPenalty = ARROW_BEND_PENALTY_LINES * unit,
        crossPenalty = ARROW_CROSS_PENALTY_LINES * unit,
    )
    // 自己的框不参与挡线，但也不许引线拐回框里：一旦穿框就当图搜索无解。
    if (path != null && !pathEntersRect(path, bubble)) {
        return if (path.size <= 2) NoteArrow.Straight(path.first(), path.last()) else NoteArrow.Elbow(path)
    }

    // 兜底：单折线（先竖后横 / 先横后竖），取较短且通的一条。
    val vh = listOf(start, Offset(start.x, end.y), end)
    val hv = listOf(start, Offset(end.x, start.y), end)
    val vhClear = isClear(vh, blockers)
    val hvClear = isClear(hv, blockers)
    if (vhClear || hvClear) {
        val pick = if (vhClear && hvClear) {
            if (polylineLength(vh) <= polylineLength(hv)) vh else hv
        } else if (vhClear) {
            vh
        } else {
            hv
        }
        return NoteArrow.Elbow(pick)
    }
    return null
}

/**
 * 正交可见图 + A*（§7.E）。
 *
 * 坐标取自页面边界、每个障碍的四边、自己的旁注框与起终点；相邻坐标之间连边，
 * 边中点落在障碍内部就删掉（贴着障碍边可以走）。状态 = `(节点, 进入方向)`，
 * 换向付拐弯代价，穿越已画引线付交叉代价，启发式是到终点的曼哈顿距离。
 *
 * @return 简化后的折线（至少 2 点）；无解或有向图过大时返回 null。
 */
private fun routeOrthogonal(
    start: Offset,
    end: Offset,
    bubble: Rect,
    blockers: List<Rect>,
    drawnArrows: List<NoteArrow>,
    bounds: Rect,
    bendPenalty: Float,
    crossPenalty: Float,
): List<Offset>? {
    if (bounds.width <= 0f || bounds.height <= 0f) return null
    val xs = buildCoords(
        values = buildList {
            add(bounds.left); add(bounds.right)
            add(bubble.left); add(bubble.right)
            add(start.x); add(end.x)
            for (b in blockers) { add(b.left); add(b.right) }
        },
        lo = bounds.left,
        hi = bounds.right,
    )
    val ys = buildCoords(
        values = buildList {
            add(bounds.top); add(bounds.bottom)
            add(bubble.top); add(bubble.bottom)
            add(start.y); add(end.y)
            for (b in blockers) { add(b.top); add(b.bottom) }
        },
        lo = bounds.top,
        hi = bounds.bottom,
    )
    val nx = xs.size
    val ny = ys.size
    if (nx < 2 || ny < 2) return null
    if (nx.toLong() * ny.toLong() > MAX_VISIBILITY_NODES) return null

    val (hBlocked, vBlocked) = blockedEdges(xs, ys, blockers)
    val sx = coordIndex(xs, start.x)
    val sy = coordIndex(ys, start.y)
    val ex = coordIndex(xs, end.x)
    val ey = coordIndex(ys, end.y)
    val startNode = sy * nx + sx
    val endNode = ey * nx + ex
    val endX = xs[ex]
    val endY = ys[ey]

    val stateCount = nx * ny * 3
    val gScore = FloatArray(stateCount) { Float.POSITIVE_INFINITY }
    val parent = IntArray(stateCount) { -1 }
    val closed = BooleanArray(stateCount)
    val startState = startNode * 3 + ROUTE_DIR_NONE
    gScore[startState] = 0f
    val queue = RoutePriorityQueue()
    queue.add(RouteStep(heuristic(startNode, nx, xs, ys, endX, endY), startState, 0f))
    val drawnSegments = drawnArrows.flatMap { arrowSegments(it) }

    while (!queue.isEmpty()) {
        val current = queue.poll()
        if (closed[current.state]) continue
        closed[current.state] = true
        val node = current.state / 3
        if (node == endNode) {
            return reconstructPath(parent, current.state, xs, ys, nx, start, end)
        }
        val dir = current.state % 3
        val xi = node % nx
        val yi = node / nx
        for (k in 0 until 4) {
            val nxi = xi + NEIGHBOR_DX[k]
            val nyi = yi + NEIGHBOR_DY[k]
            if (nxi !in 0 until nx || nyi !in 0 until ny) continue
            val newDir = NEIGHBOR_DIR[k]
            if (newDir == ROUTE_DIR_H) {
                val edge = if (nxi < xi) xi - 1 else xi
                if (hBlocked[yi * (nx - 1) + edge]) continue
            } else {
                val edge = if (nyi < yi) yi - 1 else yi
                if (vBlocked[edge * nx + xi]) continue
            }
            val a = Offset(xs[xi], ys[yi])
            val b = Offset(xs[nxi], ys[nyi])
            var step = abs(b.x - a.x) + abs(b.y - a.y)
            if (dir != ROUTE_DIR_NONE && dir != newDir) step += bendPenalty
            if (drawnSegments.isNotEmpty() &&
                drawnSegments.any { segmentsIntersect(a, b, it.first, it.second) }
            ) {
                step += crossPenalty
            }
            val nextState = (nyi * nx + nxi) * 3 + newDir
            val nextCost = current.g + step
            if (nextCost < gScore[nextState] - 1e-4f) {
                gScore[nextState] = nextCost
                parent[nextState] = current.state
                val h = heuristic(nyi * nx + nxi, nx, xs, ys, endX, endY)
                queue.add(RouteStep(nextCost + h, nextState, nextCost))
            }
        }
    }
    return null
}

/** 可见图的四个邻接方向（先横后竖，顺序固定保证可复现）。 */
private val NEIGHBOR_DX = intArrayOf(1, -1, 0, 0)
private val NEIGHBOR_DY = intArrayOf(0, 0, 1, -1)
private val NEIGHBOR_DIR = intArrayOf(ROUTE_DIR_H, ROUTE_DIR_H, ROUTE_DIR_V, ROUTE_DIR_V)

private data class RouteStep(val f: Float, val state: Int, val g: Float)

/**
 * 最小二叉堆（KMP 共享层不能用 `java.util.PriorityQueue`）。
 * 同代价按状态号排，保证同一输入得到同一条路径。
 */
private class RoutePriorityQueue {
    private var heap = arrayOfNulls<RouteStep>(256)
    private var size = 0

    fun isEmpty(): Boolean = size == 0

    fun add(item: RouteStep) {
        if (size == heap.size) heap = heap.copyOf(size * 2)
        heap[size] = item
        siftUp(size)
        size++
    }

    fun poll(): RouteStep {
        val top = heap[0]!!
        size--
        if (size > 0) {
            heap[0] = heap[size]
            siftDown(0)
        }
        heap[size] = null
        return top
    }

    private fun less(a: RouteStep, b: RouteStep): Boolean =
        a.f < b.f - 1e-4f || (abs(a.f - b.f) <= 1e-4f && a.state < b.state)

    private fun siftUp(from: Int) {
        var i = from
        while (i > 0) {
            val parent = (i - 1) / 2
            if (!less(heap[i]!!, heap[parent]!!)) break
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(from: Int) {
        var i = from
        while (true) {
            val left = i * 2 + 1
            if (left >= size) break
            val right = left + 1
            var best = left
            if (right < size && less(heap[right]!!, heap[left]!!)) best = right
            if (!less(heap[best]!!, heap[i]!!)) break
            swap(i, best)
            i = best
        }
    }

    private fun swap(a: Int, b: Int) {
        val tmp = heap[a]
        heap[a] = heap[b]
        heap[b] = tmp
    }
}

private fun heuristic(node: Int, nx: Int, xs: FloatArray, ys: FloatArray, endX: Float, endY: Float): Float =
    abs(xs[node % nx] - endX) + abs(ys[node / nx] - endY)

/** 坐标去重（容差 0.01px），并保证搜索边界本身在集合里。 */
private fun buildCoords(values: List<Float>, lo: Float, hi: Float): FloatArray {
    val sorted = values.filter { it >= lo - 0.01f && it <= hi + 0.01f }.sorted()
    val out = ArrayList<Float>(sorted.size)
    for (v in sorted) {
        val clamped = v.coerceIn(lo, hi)
        if (out.isEmpty() || clamped - out.last() > 0.01f) out += clamped
    }
    if (out.isEmpty()) return floatArrayOf(lo, hi)
    if (out.first() > lo + 0.01f) out.add(0, lo)
    if (out.last() < hi - 0.01f) out += hi
    return out.toFloatArray()
}

/** 最接近 [value] 的坐标下标（起终点一定已作为坐标加入，取到的就是它）。 */
private fun coordIndex(coords: FloatArray, value: Float): Int {
    var best = 0
    var bestDelta = Float.MAX_VALUE
    for (i in coords.indices) {
        val delta = abs(coords[i] - value)
        if (delta < bestDelta) {
            bestDelta = delta
            best = i
        }
    }
    return best
}

/**
 * 预计算哪些相邻坐标边被障碍挡住：横边的 Y 严格在障碍内、且整段落在障碍内；
 * 竖边同理。贴着障碍边界走是允许的（障碍已外扩 0.5px）。
 */
private fun blockedEdges(
    xs: FloatArray,
    ys: FloatArray,
    blockers: List<Rect>,
): Pair<BooleanArray, BooleanArray> {
    val nx = xs.size
    val ny = ys.size
    val hBlocked = BooleanArray(ny * (nx - 1))
    val vBlocked = BooleanArray((ny - 1) * nx)
    for (b in blockers) {
        if (b.width <= 0f || b.height <= 0f) continue
        val left = lowerBound(xs, b.left)
        val right = lowerBound(xs, b.right)
        for (yi in 0 until ny) {
            val y = ys[yi]
            if (y <= b.top + 0.01f || y >= b.bottom - 0.01f) continue
            var xi = left
            val end = min(right, nx - 1)
            while (xi < end) {
                hBlocked[yi * (nx - 1) + xi] = true
                xi++
            }
        }
        val top = lowerBound(ys, b.top)
        val bottom = lowerBound(ys, b.bottom)
        for (xi in 0 until nx) {
            val x = xs[xi]
            if (x <= b.left + 0.01f || x >= b.right - 0.01f) continue
            var yi = top
            val end = min(bottom, ny - 1)
            while (yi < end) {
                vBlocked[yi * nx + xi] = true
                yi++
            }
        }
    }
    return hBlocked to vBlocked
}

private fun lowerBound(coords: FloatArray, value: Float): Int {
    var lo = 0
    var hi = coords.size
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (coords[mid] < value) lo = mid + 1 else hi = mid
    }
    return lo
}

/** 从终点状态回溯节点，再把起终点替回真实坐标并合并共线点。 */
private fun reconstructPath(
    parent: IntArray,
    endState: Int,
    xs: FloatArray,
    ys: FloatArray,
    nx: Int,
    start: Offset,
    end: Offset,
): List<Offset> {
    val nodes = ArrayList<Int>()
    var state = endState
    while (state >= 0) {
        nodes += state / 3
        state = parent[state]
    }
    val points = ArrayList<Offset>(nodes.size)
    for (i in nodes.indices.reversed()) {
        val node = nodes[i]
        points += Offset(xs[node % nx], ys[node / nx])
    }
    if (points.isEmpty()) return emptyList()
    points[0] = start
    points[points.size - 1] = end
    return simplifyPath(points)
}

/** 去掉零长段与共线拐点。 */
private fun simplifyPath(points: List<Offset>): List<Offset> {
    val out = ArrayList<Offset>(points.size)
    for (p in points) {
        val last = out.lastOrNull()
        if (last != null && abs(p.x - last.x) < 0.01f && abs(p.y - last.y) < 0.01f) continue
        out += p
    }
    var i = 1
    while (i < out.size - 1) {
        val a = out[i - 1]
        val b = out[i]
        val c = out[i + 1]
        val collinear = (abs(a.x - b.x) < 0.01f && abs(b.x - c.x) < 0.01f) ||
            (abs(a.y - b.y) < 0.01f && abs(b.y - c.y) < 0.01f)
        if (collinear) out.removeAt(i) else i++
    }
    return out
}

/** 旁注框 / 墨迹朝对方一侧的边上的端口（另一轴按对方中心夹到框内）。 */
private fun portOnRect(rect: Rect, toward: Offset): Offset {
    val dx = toward.x - rect.center.x
    val dy = toward.y - rect.center.y
    val horizontal = rect.width > 0f && rect.height > 0f &&
        abs(dx) * rect.height >= abs(dy) * rect.width
    return if (horizontal) {
        Offset(
            if (dx >= 0f) rect.right else rect.left,
            toward.y.coerceIn(rect.top, rect.bottom),
        )
    } else {
        Offset(
            toward.x.coerceIn(rect.left, rect.right),
            if (dy >= 0f) rect.bottom else rect.top,
        )
    }
}

/** 框、墨迹与其他旁注的并集（没有页面边界时的可见图搜索范围）。 */
private fun unionBounds(bubble: Rect, inkRects: List<Rect>, otherBubbles: List<Rect>): Rect {
    var left = bubble.left
    var top = bubble.top
    var right = bubble.right
    var bottom = bubble.bottom
    for (r in inkRects) {
        if (r.left < left) left = r.left
        if (r.top < top) top = r.top
        if (r.right > right) right = r.right
        if (r.bottom > bottom) bottom = r.bottom
    }
    for (r in otherBubbles) {
        if (r.left < left) left = r.left
        if (r.top < top) top = r.top
        if (r.right > right) right = r.right
        if (r.bottom > bottom) bottom = r.bottom
    }
    return Rect(left, top, right, bottom)
}

private fun arrowSegments(arrow: NoteArrow): List<Pair<Offset, Offset>> = when (arrow) {
    is NoteArrow.Straight -> listOf(arrow.start to arrow.end)
    is NoteArrow.Elbow -> arrow.points.zipWithNext()
}

/** 折线是否进入矩形内部（贴着边走不算）。 */
private fun pathEntersRect(points: List<Offset>, rect: Rect): Boolean {
    if (rect.width <= 0.02f || rect.height <= 0.02f) return false
    val inner = Rect(rect.left + 0.01f, rect.top + 0.01f, rect.right - 0.01f, rect.bottom - 0.01f)
    for (i in 1 until points.size) {
        if (segmentHitsRect(points[i - 1], points[i], inner)) return true
    }
    return false
}

/** 两条线段是否相交（含端点接触；用于引线交叉计费）。 */
private fun segmentsIntersect(a: Offset, b: Offset, c: Offset, d: Offset): Boolean {
    val o1 = orientation(a, b, c)
    val o2 = orientation(a, b, d)
    val o3 = orientation(c, d, a)
    val o4 = orientation(c, d, b)
    if (o1 * o2 < -1e-6f && o3 * o4 < -1e-6f) return true
    return (abs(o1) < 1e-6f && onSegment(a, b, c)) ||
        (abs(o2) < 1e-6f && onSegment(a, b, d)) ||
        (abs(o3) < 1e-6f && onSegment(c, d, a)) ||
        (abs(o4) < 1e-6f && onSegment(c, d, b))
}

private fun orientation(a: Offset, b: Offset, c: Offset): Float =
    (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

private fun onSegment(a: Offset, b: Offset, p: Offset): Boolean =
    p.x >= min(a.x, b.x) - 0.01f && p.x <= max(a.x, b.x) + 0.01f &&
        p.y >= min(a.y, b.y) - 0.01f && p.y <= max(a.y, b.y) + 0.01f

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
            if (b.compact) {
                // 小图标形态（§2.1）：原文右下角一个小省略号，点它同样打开批注；这种形态没有箭头。
                Box(
                    modifier = Modifier
                        .offset { IntOffset(b.leftPx.roundToInt(), b.topPx.roundToInt()) }
                        .size(with(d) { b.widthPx.toDp() })
                        .clip(RoundedCornerShape(50))
                        .background(tint.copy(alpha = 0.10f))
                        .border(1.dp, tint.copy(alpha = 0.8f), RoundedCornerShape(50))
                        .clickable { onOpen(b.annotationId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "…",
                        color = fgColor,
                        fontSize = with(d) { (b.fontPx * 0.95f).toSp() },
                        maxLines = 1,
                    )
                }
                return@forEach
            }
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
    }
    if (points.isNotEmpty()) {
        path.moveTo(points.first().x, points.first().y)
        for (p in points.drop(1)) path.lineTo(p.x, p.y)
    }
    drawPath(path, color = color, style = Stroke(width = stroke))

    // 箭头尖用两小段线，方向取最后一段。
    val tail = when (arrow) {
        is NoteArrow.Straight -> arrow.start
        is NoteArrow.Elbow -> arrow.points[arrow.points.size - 2]
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
