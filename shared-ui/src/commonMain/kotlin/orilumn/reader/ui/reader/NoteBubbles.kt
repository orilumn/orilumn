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
import orilumn.reader.engine.skia.DrawLine
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
 * @property arrow 指向原文的箭头：小图标形态恒为 `null`；文字框恒有箭头
 *   （只有框已经贴住原文、连一根线都画不下时才为 `null`）。
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

/** 标注与障碍、与另一条标注之间的最小距离（以旁注字号为单位）。 */
private const val NOTE_MARGIN_EM: Float = 1f

/** 标注放在所选文字右侧时与文字的距离（要留出画水平箭头的地方）。 */
private const val NOTE_SIDE_GAP_EM: Float = 1.8f

/** 标注离屏幕边的距离：贴边会让框看起来像被裁掉一条。 */
private const val NOTE_PAGE_EDGE_EM: Float = 0.5f

/** 每条批注最多保留多少个候选（按排序取前 N，避免极端页面候选爆炸）。 */
private const val MAX_CANDIDATES_PER_NOTE: Int = 300

/** 扣分制常数（单位 u = 旁注行高）：右侧最优先，左侧最后退路（§1.1）。 */
private const val NOTE_PENALTY_RIGHT: Float = 0f
private const val NOTE_PENALTY_BELOW: Float = 2f
private const val NOTE_PENALTY_ABOVE: Float = 3f
private const val NOTE_PENALTY_OTHER: Float = 4f
private const val NOTE_PENALTY_LEFT: Float = 6f

/** 截断罚分：整句放得下不扣；要截断就一次扣 6u，让「远处整句」压过「近处小缝」。 */
private const val NOTE_TRUNC_PENALTY: Float = 6f

/** 箭头避让逐字墨迹的容差：不到半个像素的贴边不算压字。 */
private const val ARROW_GLYPH_TOL_PX: Float = 0.5f

/** 箭头避让图形 / 表格 / 线条 / 其他旁注框的容差：这几类边界是硬线，多让一点。 */
private const val ARROW_SOLID_TOL_PX: Float = 1f

/** 箭头不许拐回自己的旁注框：只差一根线的量就算「进去」。 */
private const val ARROW_OWN_BUBBLE_TOL_PX: Float = 0.01f

/** 端口在矩形每条边上取三点：两个内缩点（按边长这个比例）+ 中点。 */
private const val ARROW_PORT_INSET_RATIO: Float = 0.25f

/** 箭头最多考虑几个最近的锚点字：最近那个被围死时换下一个字。 */
private const val ARROW_TARGET_LIMIT: Int = 4

/** 一折 / 两折只试直线「最接近通畅」的前几对端口（全试一遍是白算）。 */
private const val ARROW_ONEBEND_PAIRS: Int = 96
private const val ARROW_TWOBEND_PAIRS: Int = 24

/** 弧线与三折只在前几对端口上试：它们是兜底形态，没必要铺满整页。 */
private const val ARROW_CURVE_PAIRS: Int = 48
private const val ARROW_THREEBEND_PAIRS: Int = 12

/** 贝塞尔弧线的采样段数：量穿字与交叉用，24 段在页面上看不出折。 */
private const val ARROW_CURVE_SAMPLES: Int = 24

/** 两折线每个方向最多试几个拐点坐标（再多的只是白算）。 */
private const val ARROW_MAX_BEND_COORDS: Int = 8

/** 两折线找拐点时看端口连线附近多远的障碍（以旁注行高为单位，至少 8px）。 */
private const val ARROW_BEND_WINDOW_LINES: Float = 2f

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

/** 候选框相对锚点整段的位置（决定侧别罚分，也决定引线走向，§6.1）。 */
internal enum class NoteSide(val penalty: Float) {
    RIGHT(NOTE_PENALTY_RIGHT),
    BELOW(NOTE_PENALTY_BELOW),
    ABOVE(NOTE_PENALTY_ABOVE),
    OTHER(NOTE_PENALTY_OTHER),
    LEFT(NOTE_PENALTY_LEFT),
}

/**
 * 一条批注在某块空白里的候选框（纯几何）。
 *
 * 字段只服务两件事：**评分**（[side] / [distance] / [complete] → [score]）与
 * **落位**（[rect] 是最终框，[freeRect] 是页边距一维排布的活动范围）。
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
    /** 折行后实际能显示的字数（不含换行符）。 */
    val shownChars: Int,
    /** 完全落在版心内（左右页边距、页脚、页顶另外判）。 */
    val inContent: Boolean,
    /** 落在哪一侧页边距（[MarginSide.NONE] = 版心内）：页边距一维排布按它分组。 */
    val marginSide: MarginSide,
    /** 框到锚点**最近一行**墨迹的边到边最短距离（评分里的距离项）。 */
    val distance: Float,
    /** 框相对锚点整段的位置。 */
    val side: NoteSide,
    /** 总分 = 侧别罚分 + 距离 / u + 截断罚分（§1.1），越小越好。 */
    val score: Float,
) {
    /**
     * 两个候选框是否冲突：矩形相交，或间距小于 [margin]。
     *
     * @param margin 已放好的框与这个候选之间要求的最小距离（默认只要求不重叠）。
     */
    fun conflicts(other: NoteCandidate, margin: Float = 0f): Boolean =
        rect.left < other.rect.right + margin - NOTE_GEOM_EPS &&
            other.rect.left < rect.right + margin - NOTE_GEOM_EPS &&
            rect.top < other.rect.bottom + margin - NOTE_GEOM_EPS &&
            other.rect.top < rect.bottom + margin - NOTE_GEOM_EPS
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
 * 旁注样式：字号、字体、行距、内缩与四周留白（将来由「批注样式」设置页提供，D-29）。
 *
 * 比例全部以**旁注字号**为基准；字号与字体可以脱离正文单独设置，默认值只是推荐值，
 * 不是写死的常量。样式变化只影响旁注自己，不碰正文排版。
 *
 * @property fontPx 旁注字号（像素）。默认 = 正文主字号 × [NOTE_BUBBLE_FONT_RATIO]。
 * @property fontFamily 旁注字体（`null` = 跟随界面默认）；批注样式落地后从这里进来。
 * @property lineHeightRatio 行高 / 字号。
 * @property padEm 单侧内缩 / 字号。
 * @property marginEm 与障碍、与另一条旁注的最小距离 / 字号。
 * @property sideGapEm 放在文字右侧时与文字的侧向距离 / 字号（留给水平箭头）。
 * @property edgeEm 离屏幕边的距离 / 字号。
 */
internal data class NoteStyle(
    val fontPx: Float,
    val fontFamily: String? = null,
    val lineHeightRatio: Float = NOTE_BUBBLE_LINE_RATIO,
    val padEm: Float = NOTE_BUBBLE_PAD_RATIO,
    val marginEm: Float = NOTE_MARGIN_EM,
    val sideGapEm: Float = NOTE_SIDE_GAP_EM,
    val edgeEm: Float = NOTE_PAGE_EDGE_EM,
) {
    companion object {
        /** 默认样式：字号比正文小一号，其余按推荐比例；字体跟随界面默认。 */
        fun forBody(bodyPx: Float): NoteStyle =
            NoteStyle(fontPx = bodyPx.coerceAtLeast(1f) * NOTE_BUBBLE_FONT_RATIO)
    }
}

/**
 * 由 [NoteStyle] 算出的像素度量：一页只算一次，落位、绘制与「整句宽」估算共用。
 *
 * 这里是唯一把「字号 / 行距 / 内缩 / 留白」变成像素的地方，避免三处各写一套比例
 * （旧实现曾出现「文字框内缩按 0.35 算、落位按 0.7 算」这种对不上、Text 多折一行被截掉）。
 */
internal data class NoteMetrics(val style: NoteStyle) {
    /** 旁注字号。 */
    val em: Float get() = style.fontPx

    /** 旁注行高：旁注自己排自己的，与正文行高无关。 */
    val lineH: Float get() = em * style.lineHeightRatio

    /** 单侧内缩（[NoteBubbles] 里 Text 的 `padding(horizontal = padX)`）。 */
    val padX: Float get() = em * style.padEm

    /** 两侧内缩合计：算宽度、折行都用它。 */
    val padTotal: Float get() = padX * 2f

    /** 标注与障碍、与另一条标注之间的最小距离。 */
    val margin: Float get() = em * style.marginEm

    /** 标注放在所选文字右侧时的侧向距离（留给水平箭头）。 */
    val sideGap: Float get() = em * style.sideGapEm

    /** 标注离屏幕边的距离。 */
    val edge: Float get() = em * style.edgeEm

    companion object {
        /** 按正文主字号取默认样式。 */
        fun forBody(bodyPx: Float): NoteMetrics = NoteMetrics(NoteStyle.forBody(bodyPx))
    }
}

/**
 * 一条批注在一块空白里最终的尺寸（字体 / 字号 / 行距 / margin 一起算出来的结果）。
 *
 * @property width 框宽（像素，含两侧内缩）。
 * @property lines 实际显示几行（受空白高度限制，可能少于整句所需行数）。
 * @property height 框高 = [lines] × [lineH][NoteMetrics.lineH]。
 * @property shownChars 放得下几个字（整体带省略号时小于 [totalChars]）。
 * @property totalChars 整句字数（不计换行符）。
 */
internal data class NoteBoxSize(
    val width: Float,
    val lines: Int,
    val height: Float,
    val shownChars: Int,
    val totalChars: Int,
) {
    /** 整句都放得下。 */
    val complete: Boolean get() = totalChars > 0 && shownChars >= totalChars

    /** 至少能读 4 个字（整句不足 4 字时按整句）：再少就交给小图标。 */
    val readable: Boolean get() = lines > 0 && shownChars >= min(MIN_SHOWN_CHARS, totalChars)
}

/**
 * 算一条批注在给定空白里的最终尺寸（见 `docs/旁注布点与引线-实施方案.md` §3）。
 *
 * 顺序：整句所需宽度 → 可用宽够不够 → 够就单行并按文字宽收窄，不够才按可用宽折行
 * → 行数再受可用高限制。
 *
 * @param maxWidth 框可用的最大宽度（**已经扣掉** margin / 侧向间距 / 页面留白）。
 * @param maxHeight 框可用的最大高度（**已经扣掉**上下 margin）。
 */
internal fun noteBoxFit(
    text: String,
    metrics: NoteMetrics,
    maxWidth: Float,
    maxHeight: Float,
): NoteBoxSize {
    val content = text.trim()
    val total = content.count { it != '\n' }
    if (total == 0 || maxWidth <= 0f || maxHeight <= 0f) {
        return NoteBoxSize(0f, 0, 0f, 0, total)
    }
    val innerW = maxWidth - metrics.padTotal
    val capacity = (maxHeight / metrics.lineH).toInt()
    if (innerW <= 0f || capacity < 1) return NoteBoxSize(0f, 0, 0f, 0, total)

    val desired = estimatedNoteTextWidth(content, metrics.em)
    val width = min(desired, innerW) + metrics.padTotal
    val inner = width - metrics.padTotal
    val needed = noteTextFit(content, inner, metrics.em, Int.MAX_VALUE).lines
    val lines = min(needed, capacity)
    val fit = noteTextFit(content, inner, metrics.em, lines)
    return NoteBoxSize(
        width = width,
        lines = lines,
        height = lines * metrics.lineH,
        shownChars = min(fit.shownChars, total),
        totalChars = total,
    )
}

/**
 * 每条批注 × 每个空闲矩形生成候选框（§4 / §7.D）。
 *
 * 取位只看这块空白相对「锚点整段并集」的位置：
 * - 上方：贴空白**底部**，水平对准锚点中心并 clamp；
 * - 下方：贴空白**顶部**，水平同上；
 * - 左 / 右：垂直居中于锚点整段中心（clamp 进空白），右侧再让出 [NoteMetrics.sideGap]；
 * - 其他：最近边取位。
 *
 * 尺寸由 [noteBoxFit] 按「整句宽 → 单行 / 折行 → 行数受空白高限制」算出。
 * 候选按扣分制排序：`侧别罚分 + 距离/u + 截断罚分`，同分再比距离、上边、左边。
 * 不再做视线过滤：越过其他文字去右侧只是距离更远，由分数决定值不值。
 *
 * [freeRects] 必须是**已按 margin 外扩障碍**后算出的空白，框落在里面即满足与障碍的间距。
 */
internal fun noteCandidates(
    note: NoteInk,
    freeRects: List<Rect>,
    contentLeft: Float,
    contentRight: Float,
    contentBottom: Float,
    metrics: NoteMetrics,
    maxCandidates: Int = MAX_CANDIDATES_PER_NOTE,
): List<NoteCandidate> {
    val text = note.text.trim()
    if (text.isEmpty() || note.rects.isEmpty()) return emptyList()
    val total = text.count { it != '\n' }
    if (total == 0) return emptyList()
    val u = metrics.lineH.coerceAtLeast(1f)
    val anchor = rectUnion(note.rects)
    val minShown = min(MIN_SHOWN_CHARS, total)
    val out = ArrayList<NoteCandidate>(freeRects.size)
    val seen = HashSet<Long>()

    for (f in freeRects) {
        if (f.width <= 0f || f.height <= 0f) continue
        val blankSide = blankSideOf(f, anchor)
        // 右侧要额外让出 sideGap（画水平箭头），左侧 / 上下按空白边取位。
        val widthLimit = when (blankSide) {
            NoteSide.RIGHT -> f.right - max(f.left, anchor.right + metrics.sideGap)
            else -> f.width
        }
        if (widthLimit <= 0f) continue
        val size = noteBoxFit(text, metrics, widthLimit, f.height)
        if (!size.readable) continue
        if (size.width > widthLimit + NOTE_GEOM_EPS || size.height > f.height + NOTE_GEOM_EPS) continue

        val (x, y) = when (blankSide) {
            NoteSide.ABOVE -> {
                val left = (anchor.center.x - size.width / 2f)
                    .coerceIn(f.left, max(f.left, f.right - size.width))
                left to (f.bottom - size.height)
            }
            NoteSide.BELOW -> {
                val left = (anchor.center.x - size.width / 2f)
                    .coerceIn(f.left, max(f.left, f.right - size.width))
                left to f.top
            }
            NoteSide.RIGHT -> {
                val left = max(f.left, anchor.right + metrics.sideGap)
                val top = (anchor.center.y - size.height / 2f)
                    .coerceIn(f.top, max(f.top, f.bottom - size.height))
                left to top
            }
            NoteSide.LEFT -> {
                val left = f.right - size.width
                val top = (anchor.center.y - size.height / 2f)
                    .coerceIn(f.top, max(f.top, f.bottom - size.height))
                left to top
            }
            NoteSide.OTHER -> {
                val left = (anchor.center.x - size.width / 2f)
                    .coerceIn(f.left, max(f.left, f.right - size.width))
                val top = (anchor.center.y - size.height / 2f)
                    .coerceIn(f.top, max(f.top, f.bottom - size.height))
                left to top
            }
        }
        val rect = Rect(x, y, x + size.width, y + size.height)
        // 同一块空白被不同极大矩形重复切出时，落位可能完全一样：只留一个。
        val key = (rect.left.roundToInt().toLong() shl 48) xor
            (rect.top.roundToInt().toLong() shl 32) xor
            (rect.right.roundToInt().toLong() shl 16) xor
            rect.bottom.roundToInt().toLong()
        if (!seen.add(key)) continue

        val side = sideOf(rect, anchor)
        val distance = note.rects.minOf { rectDistance(rect, it) }
        val score = side.penalty + distance / u + if (size.complete) 0f else NOTE_TRUNC_PENALTY
        val inContent = rect.left >= contentLeft - NOTE_GEOM_EPS &&
            rect.right <= contentRight + NOTE_GEOM_EPS &&
            rect.top >= -NOTE_GEOM_EPS &&
            rect.bottom <= contentBottom + NOTE_GEOM_EPS
        val marginSide = when {
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
            lines = size.lines,
            complete = size.complete,
            shownChars = size.shownChars,
            inContent = inContent,
            marginSide = marginSide,
            distance = distance,
            side = side,
            score = score,
        )
    }
    // 扣分制（§1.2）：总分 → 距离 → 框上边 → 框左边，保证同一输入结果恒定。
    return out
        .sortedWith(
            compareBy<NoteCandidate> { it.score }
                .thenBy { it.distance }
                .thenBy { it.rect.top }
                .thenBy { it.rect.left },
        )
        .take(maxCandidates)
}

/** 这块空白相对锚点整段的位置（§4 表格顺序：上 → 下 → 右 → 左 → 其他）。 */
private fun blankSideOf(f: Rect, anchor: Rect): NoteSide = when {
    f.bottom <= anchor.top + NOTE_GEOM_EPS -> NoteSide.ABOVE
    f.top >= anchor.bottom - NOTE_GEOM_EPS -> NoteSide.BELOW
    f.left >= anchor.right - NOTE_GEOM_EPS -> NoteSide.RIGHT
    f.right <= anchor.left + NOTE_GEOM_EPS -> NoteSide.LEFT
    else -> NoteSide.OTHER
}

/** 候选框相对锚点整段的位置（§6.1：先看水平投影是否重叠，再看左右）。 */
private fun sideOf(box: Rect, anchor: Rect): NoteSide {
    val overlap = min(box.right, anchor.right) - max(box.left, anchor.left)
    if (overlap >= 1f) {
        return if (box.center.y < anchor.center.y) NoteSide.ABOVE else NoteSide.BELOW
    }
    if (box.left >= anchor.right - NOTE_GEOM_EPS) return NoteSide.RIGHT
    if (box.right <= anchor.left + NOTE_GEOM_EPS) return NoteSide.LEFT
    return NoteSide.OTHER
}

/** 四边同时外扩 [m]：障碍按 margin 外扩后做 MaxRects，框落在空白里即满足间距。 */
private fun inflateRect(r: Rect, m: Float): Rect =
    Rect(r.left - m, r.top - m, r.right + m, r.bottom + m)

/**
 * 一条批注在**本页**的画布事实（用户层 → 旁注层的输入）。
 *
 * [rects] 是这条标注在本页的墨迹矩形（页坐标，由测度引擎逐字算出，与画出来的字同源）：
 * 空 = 本页没有它的墨迹（在别的页上），也就没有「旁边」可言。
 *
 * [arrowRects] 是**箭头专用**的逐字墨迹（一个字一个矩形）：布点用行框（[rects]），
 * 箭头要能穿两字之间与标点前后的空白（实施计划 §4.4.1）。空 = 回退到 [rects]。
 */
internal data class NoteInk(
    val annotationId: Long,
    val text: String,
    val argb: Int,
    val rects: List<Rect>,
    val arrowRects: List<Rect> = emptyList(),
) {
    /** 箭头避让 / 指向用的墨迹：优先逐字，缺省回退布点墨迹。 */
    val arrowInk: List<Rect> get() = arrowRects.ifEmpty { rects }
}

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
    /**
     * 箭头避让专用的**逐字**墨迹（一个字一个矩形）。
     *
     * 布点用行框（[textInk]）：词间空隙不能放下一句批注；箭头要能穿两字之间与标点前后的
     * 空白，故另收一份逐字几何（实施计划 §4.4.1 / §4.4.3）。空 = 回退到 [textInk]。
     */
    val arrowInk: List<Rect> = emptyList(),
) {
    val all: List<Rect> get() = textInk + figures + tables + strokes
}

/**
 * 把渲染层给的图形/线条几何换成页坐标的障碍物（用户层纯转换，不碰排版）。
 *
 * @param shift [readerPageShift] 的结果：章内绝对 Y → 页坐标。
 * @param contentLeft 内容区左缘（插图/背景的 X 是内容区相对坐标）。
 * @param arrowInk 箭头专用的逐字墨迹（页坐标）；空 = 箭头退回行框避让。
 */
internal fun noteObstacles(
    textInk: List<Rect>,
    pageImages: List<PageImage>?,
    pageBackgrounds: List<PageBackground>?,
    shift: Int,
    contentLeft: Float,
    arrowInk: List<Rect> = emptyList(),
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
    return NoteObstacles(
        textInk = textInk,
        figures = figures,
        strokes = strokes,
        arrowInk = arrowInk,
    )
}

/**
 * 旁注箭头（D-28 第 3 部分）：从旁注框指向原文一个字的一条细线。
 *
 * 形态只有三种（§2.2）：直线（1 段）、一折（2 段）、两折（3 段），**最多两个拐点**。
 * 除框退成小图标外一律画出来：宁可线贴一点字边，也不让读者不知道旁注指向哪里。
 */
internal sealed interface NoteArrow {
    val start: Offset
    val end: Offset

    data class Straight(override val start: Offset, override val end: Offset) : NoteArrow

    /** 正交折线：起点 → 拐点 → … → 终点（3～4 个点，相邻两点共享 x 或 y，最多两个拐点）。 */
    data class Elbow(val points: List<Offset>) : NoteArrow {
        override val start: Offset get() = points.first()
        override val end: Offset get() = points.last()
    }
}

/**
 * 给本页每条批注在**整页范围内统一布点**（纯函数，无 Compose 依赖）。
 *
 * 流水线（`docs/旁注布点与引线-实施方案.md` §5）：
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
    noteStyle: NoteStyle = NoteStyle.forBody(fontPx),
): List<NoteBubble> {
    if (noteInk.isEmpty()) return emptyList()
    val metrics = NoteMetrics(noteStyle)
    val body = fontPx.coerceAtLeast(1f)
    val small = metrics.em
    val lineH = metrics.lineH
    val margin = metrics.margin
    // 旁注框与墨迹之间留一线：贴在字上会让人觉得把正文框进去了。
    val gap = body * 0.25f

    // 整页（含四个页边距）都是可放旁注的空白：障碍之外哪里空就放哪里。
    val pageBounds = Rect(
        0f,
        0f,
        max(contentRight, pageRight),
        max(contentBottom, pageBottom),
    )
    // 框离屏幕边留一点，避免看起来像被裁掉一条（§2.2）。
    val blankBounds = Rect(
        pageBounds.left + metrics.edge,
        pageBounds.top + metrics.edge,
        pageBounds.right - metrics.edge,
        pageBounds.bottom - metrics.edge,
    )
    // 正文按行合并成一个行框（不是逐字）：既减少空闲矩形碎片，
    // 也不会把词间空隙当成能放下一句批注的空白。
    val textLines = mergeTextLineRects(obstacles.textInk, body)
    val baseObstacles = textLines + obstacles.figures + obstacles.tables + obstacles.strokes
    // 障碍按 margin 外扩后再切空白：框只要落在空白里，与任何障碍都至少隔 1em（§2.2）。
    val inflatedObstacles = baseObstacles.map { inflateRect(it, margin) }
    // 箭头避让另用逐字墨迹（能穿两字之间与标点前后）；没有逐字几何时才退回行框。
    val arrowGlyphs = obstacles.arrowInk.ifEmpty { obstacles.textInk }
    val arrowSolids = obstacles.figures + obstacles.tables + obstacles.strokes
    val freeRects = maximalFreeRects(
        bounds = blankBounds,
        obstacles = inflatedObstacles,
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
            metrics = metrics,
        )
        if (list.isNotEmpty()) candidatesByNote[note.annotationId] = list
    }

    // 页边距一维排布与候选冲突用同一个 margin：框与框之间也至少隔 1em（§5.3/§5.4）。
    val choices = assignNoteCandidates(ordered, candidatesByNote, margin)
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
        // 箭头用逐字墨迹（能穿两字之间）；布点仍用行框。
        val ink = inkById[b.annotationId]?.arrowInk.orEmpty()
        val self = bubbleRects.getValue(b.annotationId)
        val others = out.asSequence()
            .filter { it.annotationId != b.annotationId && !it.compact }
            .mapNotNull { bubbleRects[it.annotationId] }
            .toList()
        val arrow = routeNoteArrow(
            bubble = self,
            inkRects = ink,
            glyphs = arrowGlyphs,
            solids = arrowSolids,
            otherBubbles = others,
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
 * @param margin 框与框之间要求的最小间距（像素，= 1em），页边距一维排布也用它。
 */
private fun assignNoteCandidates(
    notes: List<NoteInk>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    margin: Float,
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
        selected[i] = requests[i].candidates.firstOrNull { fits(it, selected, i, margin) }
    }

    // 页边距一维排布：同侧同块空白的框按锚点纵序推开（只动这一组）。
    packMarginLanes(requests, selected, margin, margin)

    // 排布后可能空出位置：还没分到的批注再试一次（仍不碰先挑者）。
    for (i in order) {
        if (selected[i] != null) continue
        selected[i] = requests[i].candidates.firstOrNull { fits(it, selected, i, margin) }
    }

    // 硬约束兜底：任何残余重叠都放弃后挑的那条（交给小图标）。
    dropConflicts(requests, selected, margin)

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
    margin: Float,
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
                    j != i && selected[j]?.let { c.conflicts(it, margin) } == true
                }
                if (clash) selected[i] = null
                continue
            }
            if (side == MarginSide.LEFT || side == MarginSide.RIGHT) {
                packLaneVertical(requests, selected, group, laneGap, margin)
            } else {
                packLaneHorizontal(requests, selected, group, laneGap, margin)
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
    margin: Float,
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
    dropLaneConflicts(selected, ordered, margin)
}

/** 上下页边距：沿 X 前推 / 回推，高度与 Y 不变。 */
private fun packLaneHorizontal(
    requests: List<NoteRequest>,
    selected: Array<NoteCandidate?>,
    group: List<Int>,
    laneGap: Float,
    margin: Float,
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
    dropLaneConflicts(selected, ordered, margin)
}

/** 一维排布后仍与其他框相撞的，按顺序放弃（先撞上的那条让位）。 */
private fun dropLaneConflicts(
    selected: Array<NoteCandidate?>,
    group: List<Int>,
    margin: Float,
) {
    val dropped = HashSet<Int>()
    for (i in group) {
        val c = selected[i] ?: continue
        val clash = selected.indices.any { j ->
            j != i && j !in dropped && selected[j]?.let { c.conflicts(it, margin) } == true
        }
        if (clash) {
            dropped += i
            selected[i] = null
        }
    }
}

/** 任何残余重叠都放弃后挑的那条（交给小图标，宁缺勿叠）。 */
private fun dropConflicts(
    requests: List<NoteRequest>,
    selected: Array<NoteCandidate?>,
    margin: Float,
) {
    val dropped = HashSet<Int>()
    for (i in requests.indices) {
        val c = selected[i] ?: continue
        val clash = selected.indices.any { j ->
            j != i && j !in dropped && selected[j]?.let { c.conflicts(it, margin) } == true
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
    margin: Float,
    ignoreB: Int = -1,
): Boolean {
    for (k in selected.indices) {
        if (k == ignoreA || k == ignoreB) continue
        val other = selected[k] ?: continue
        // 同一侧页边距的框先都收下，再由 packMarginLanes 统一推开；
        // 实在排不下会在排布 / 兜底阶段丢掉。
        if (candidate.marginSide != MarginSide.NONE && other.marginSide == candidate.marginSide) continue
        if (candidate.conflicts(other, margin)) return false
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
 * 本页正文主字号：按「行内非空白字数」加权，取字数最多的那一档字号。
 *
 * 页首是 h1 / h2 时首行字号可能是正文的两倍；旁注若跟着标题放大，页边距立刻塞不下几个字
 * （真机出现过只剩「用 / va…」）。整页都是标题时退回第一行，不返回 0。
 */
internal fun noteBodyFontPx(lines: List<DrawLine>): Float {
    if (lines.isEmpty()) return 0f
    return lines
        .groupBy { it.fontSizePx }
        .maxByOrNull { (_, ls) -> ls.sumOf { l -> l.text.count { c -> !c.isWhitespace() } } }
        ?.key ?: lines.first().fontSizePx
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

/** 箭头避让的一块障碍：矩形 + 它自己的容差（逐字墨迹小，图形 / 旁注框大）。 */
private data class ArrowObstacle(val rect: Rect, val tol: Float)

/** 一条候选路径的度量：[clear] 是能否直接采用，[hits] 是全撞时比「穿得少」用的穿透长度。 */
private data class ArrowPath(
    val points: List<Offset>,
    val bends: Int,
    val length: Float,
    val crosses: Int,
    val hits: Float,
    val entersBubble: Boolean,
    val order: Int,
) {
    val clear: Boolean get() = hits <= 1e-4f && !entersBubble

    /** 通畅路径的择优顺序（§4.4.2）：已画引线交叉少 → 折数少 → 更短 → 端口顺序。 */
    fun betterThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (crosses != other.crosses) return crosses < other.crosses
        if (bends != other.bends) return bends < other.bends
        if (abs(length - other.length) > 0.5f) return length < other.length
        return order < other.order
    }

    /** 全撞时的兜底顺序：不穿自己的框 → 穿得少 → 更短 → 端口顺序。照样画，不留空。 */
    fun betterFallbackThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (entersBubble != other.entersBubble) return !entersBubble
        if (abs(hits - other.hits) > 0.5f) return hits < other.hits
        if (abs(length - other.length) > 0.5f) return length < other.length
        return order < other.order
    }
}

/** 一个目标字（锚点在本页的一个字）对应的搜索材料：它的端口 + 附近障碍。 */
private class ArrowTarget(val ends: List<Offset>, val obstacles: List<ArrowObstacle>)

/** 一对端口与它们之间直线的度量（直线被挡时拿来排「最接近通畅」的先后）。 */
private class ArrowPair(
    val start: Offset,
    val end: Offset,
    val target: ArrowTarget,
    val straight: ArrowPath,
)

/**
 * 给一条旁注找箭头路由（纯函数）：端口搜索 + 直线 / 一折 / 两折，**最多两个拐点**。
 *
 * 第一轮的可见图 + A* 已按 `docs/旁注布点与引线-实施方案.md` §2.2 退役：端口只在
 * 「旁注框四边 × 目标字四边」各取中点与两个内缩点，路径只在直线（1 段）、一折（2 段）、
 * 两折（3 段）里挑，**禁止三折及以上**。
 *
 * **必画**：三种折线全撞障碍时，取「穿得最少、再最短」的一条照样画出来——宁可线贴一点字边，
 * 也不能让读者不知道这条旁注指向哪里。只有框退成小图标才没有箭头。
 *
 * 避让对象（§4.4.3）：文字**逐字**墨迹（容差 0.5px，自己的锚点字除外——箭头就是要指到它）、
 * 图形 / 表格框线 / 线条（1px）、其他旁注框（1px）。已画引线只做交叉惩罚，不硬堵死。
 * 贴边不算穿过。块底色 / `padding` / `margin` / 页边距都算空白。
 *
 * @param inkRects 这条批注**自己**在本页的逐字墨迹：箭头指向它，故它不是障碍。
 * @param glyphs 本页所有逐字墨迹（含自己的，内部剔除）：换成逐字几何后，线能从两字之间、
 *   标点前后穿过，不再把整行当一个黑块。
 * @param solids 图形 / 表格框线 / 线条。
 * @param otherBubbles 其他旁注框。
 * @param drawnArrows 已经画好的引线：尽量不穿越（软惩罚）。
 * @param lineH 旁注行高：两折找拐点时看多远的障碍以它为单位。
 */
internal fun routeNoteArrow(
    bubble: Rect,
    inkRects: List<Rect>,
    glyphs: List<Rect> = emptyList(),
    solids: List<Rect> = emptyList(),
    otherBubbles: List<Rect> = emptyList(),
    drawnArrows: List<NoteArrow> = emptyList(),
    lineH: Float = 0f,
): NoteArrow? {
    if (inkRects.isEmpty() || bubble.width <= 0f || bubble.height <= 0f) return null
    // 框已经贴住原文：连一根线的位置都没有，画出来只会压在字上。
    if (inkRects.minOf { rectDistance(bubble, it) } <= NOTE_ARROW_END_GAP_PX) return null

    // 自己的锚点字不是障碍：箭头本来就要指到它；同一条批注的其他字也不挡自己。
    val obstacles = buildList {
        for (g in glyphs) {
            if (inkRects.any { it.overlaps(g) }) continue
            add(ArrowObstacle(g, ARROW_GLYPH_TOL_PX))
        }
        for (s in solids) add(ArrowObstacle(s, ARROW_SOLID_TOL_PX))
        for (b in otherBubbles) add(ArrowObstacle(b, ARROW_SOLID_TOL_PX))
    }
    val drawnSegments = drawnArrows.flatMap { arrowSegments(it) }
    val starts = edgePorts(bubble, 0f)
    val pad = max(lineH, 8f) * ARROW_BEND_WINDOW_LINES
    // 目标只取最近的几个字：最近那个被围死时，还有同一条批注的其他字可指。
    val targets = inkRects
        .sortedBy { rectDistance(bubble, it) }
        .take(ARROW_TARGET_LIMIT)
        .map { target ->
            val window = Rect(
                min(bubble.left, target.left) - pad,
                min(bubble.top, target.top) - pad,
                max(bubble.right, target.right) + pad,
                max(bubble.bottom, target.bottom) + pad,
            )
            ArrowTarget(
                ends = edgePorts(target, NOTE_ARROW_END_GAP_PX),
                obstacles = obstacles.filter { it.rect.overlaps(window) },
            )
        }

    var order = 0
    var fallback: ArrowPath? = null
    var best: ArrowPath? = null

    fun measure(points: List<Offset>, target: ArrowTarget): ArrowPath? =
        measureArrowPath(
            points = points,
            order = order++,
            obstacles = target.obstacles,
            bubble = bubble,
            drawnSegments = drawnSegments,
        )

    // 1) 直线：全端口对扫一遍，顺便留下「最接近通畅」的端口对给折线用。
    val pairs = ArrayList<ArrowPair>(starts.size * targets.size * 12)
    for (target in targets) {
        for (s in starts) {
            for (e in target.ends) {
                val path = measure(listOf(s, e), target) ?: continue
                pairs += ArrowPair(s, e, target, path)
                if (path.clear) {
                    if (path.betterThan(best)) best = path
                } else if (path.betterFallbackThan(fallback)) {
                    fallback = path
                }
            }
        }
    }
    best?.let { if (it.crosses == 0) return it.toNoteArrow() }

    // 折线只试直线最接近通畅的那些端口对：几百对全试一遍是白算（§4.4.4）。
    val ranked = pairs.sortedWith(compareBy({ it.straight.hits }, { it.straight.length }, { it.straight.order }))

    // 2) 一折：先横后竖 / 先竖后横。
    for (pair in ranked.take(ARROW_ONEBEND_PAIRS)) {
        for (points in oneBendPaths(pair.start, pair.end)) {
            val path = measure(points, pair.target) ?: continue
            if (path.clear) {
                if (path.betterThan(best)) best = path
            } else if (path.betterFallbackThan(fallback)) {
                fallback = path
            }
        }
    }
    best?.let { if (it.crosses == 0) return it.toNoteArrow() }

    // 3) 两折：横→竖→横 / 竖→横→竖，拐点取端口坐标与附近障碍边界。
    for (pair in ranked.take(ARROW_TWOBEND_PAIRS)) {
        for (points in twoBendPaths(pair.start, pair.end, pair.target.obstacles, pad)) {
            val path = measure(points, pair.target) ?: continue
            if (path.clear) {
                if (path.betterThan(best)) best = path
            } else if (path.betterFallbackThan(fallback)) {
                fallback = path
            }
        }
    }
    return (best ?: fallback)?.toNoteArrow()
}

/** 端口：矩形四条边各取「内缩点 / 中点 / 内缩点」，顺序固定（上、右、下、左）。 */
private fun edgePorts(rect: Rect, outward: Float): List<Offset> {
    if (rect.width <= 0.01f || rect.height <= 0.01f) return listOf(rect.center)
    val ix = rect.width * ARROW_PORT_INSET_RATIO
    val iy = rect.height * ARROW_PORT_INSET_RATIO
    val xs = floatArrayOf(rect.left + ix, rect.center.x, rect.right - ix)
    val ys = floatArrayOf(rect.top + iy, rect.center.y, rect.bottom - iy)
    val out = ArrayList<Offset>(12)
    // 上边朝上、右边朝右、下边朝下、左边朝左；[outward] 让箭头尖停在字外一线。
    for (x in xs) out += Offset(x, rect.top - outward)
    for (y in ys) out += Offset(rect.right + outward, y)
    for (x in xs) out += Offset(x, rect.bottom + outward)
    for (y in ys) out += Offset(rect.left - outward, y)
    return out
}

/** 一折线的两种走法：先横后竖 / 先竖后横（退化成一字的由 [simplifyPolyline] 收掉）。 */
private fun oneBendPaths(start: Offset, end: Offset): List<List<Offset>> = listOf(
    listOf(start, Offset(end.x, start.y), end),
    listOf(start, Offset(start.x, end.y), end),
)

/**
 * 两折线的两种走法：横→竖→横 / 竖→横→竖。
 *
 * 拐点坐标取端口本身与附近障碍的四边（贴着障碍走才绕得开），按离中点由近到远取前几个，
 * 近的更容易凑出短路径。
 */
private fun twoBendPaths(
    start: Offset,
    end: Offset,
    obstacles: List<ArrowObstacle>,
    pad: Float,
): List<List<Offset>> {
    val box = Rect(
        min(start.x, end.x) - pad,
        min(start.y, end.y) - pad,
        max(start.x, end.x) + pad,
        max(start.y, end.y) + pad,
    )
    val xs = ArrayList<Float>(obstacles.size + 2)
    val ys = ArrayList<Float>(obstacles.size + 2)
    xs += start.x
    xs += end.x
    ys += start.y
    ys += end.y
    for (o in obstacles) {
        if (!box.overlaps(o.rect)) continue
        xs += o.rect.left - o.tol
        xs += o.rect.right + o.tol
        ys += o.rect.top - o.tol
        ys += o.rect.bottom + o.tol
    }
    val bendXs = pickCoords(xs, (start.x + end.x) * 0.5f)
    val bendYs = pickCoords(ys, (start.y + end.y) * 0.5f)
    val out = ArrayList<List<Offset>>(bendXs.size + bendYs.size)
    for (x in bendXs) out += listOf(start, Offset(x, start.y), Offset(x, end.y), end)
    for (y in bendYs) out += listOf(start, Offset(start.x, y), Offset(end.x, y), end)
    return out
}

/** 按离 [mid] 由近到远取至多 [ARROW_MAX_BEND_COORDS] 个坐标（差半像素以内算同一个）。 */
private fun pickCoords(values: List<Float>, mid: Float): List<Float> {
    val sorted = values.sortedBy { abs(it - mid) }
    val out = ArrayList<Float>(ARROW_MAX_BEND_COORDS)
    for (v in sorted) {
        if (out.any { abs(it - v) <= 0.5f }) continue
        out += v
        if (out.size >= ARROW_MAX_BEND_COORDS) break
    }
    return out
}

/** 量一条候选路径：长度、折数、已画引线交叉数、穿障碍的长度、有没有拐回自己的框。 */
private fun measureArrowPath(
    points: List<Offset>,
    order: Int,
    obstacles: List<ArrowObstacle>,
    bubble: Rect,
    drawnSegments: List<Pair<Offset, Offset>>,
): ArrowPath? {
    val pts = simplifyPolyline(points)
    if (pts.size < 2) return null
    var length = 0f
    var hits = 0f
    var crosses = 0
    var enters = false
    for (i in 1 until pts.size) {
        val a = pts[i - 1]
        val b = pts[i]
        val len = segmentLength(a, b)
        if (len < 0.01f) continue
        length += len
        if (insideSpan(a, b, bubble, ARROW_OWN_BUBBLE_TOL_PX) > 1e-6f) enters = true
        // 先用线段的包围盒粗筛：绝大多数字形连边都碰不到，省掉逐块的裁剪计算。
        val segLeft = min(a.x, b.x)
        val segRight = max(a.x, b.x)
        val segTop = min(a.y, b.y)
        val segBottom = max(a.y, b.y)
        for (o in obstacles) {
            if (o.rect.right < segLeft || o.rect.left > segRight ||
                o.rect.bottom < segTop || o.rect.top > segBottom
            ) {
                continue
            }
            val span = insideSpan(a, b, o.rect, o.tol)
            if (span > 1e-6f) hits += span * len
        }
        for (d in drawnSegments) {
            if (segmentsIntersect(a, b, d.first, d.second)) crosses++
        }
    }
    if (length < 0.01f) return null
    return ArrowPath(
        points = pts,
        bends = max(0, pts.size - 2),
        length = length,
        crosses = crosses,
        hits = hits,
        entersBubble = enters,
        order = order,
    )
}

/**
 * 线段落在矩形**内部**的参数长度（0 = 没穿过）。
 *
 * 矩形先按 [tol] 内缩：[tol] 是该层容差（逐字墨迹 0.5px、图形 / 旁注框 1px），
 * 贴边与平行贴边都不算穿过。返回的是 t 区间长度，乘上段长才是穿透长度。
 */
private fun insideSpan(a: Offset, b: Offset, r: Rect, tol: Float): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val left = r.left + tol
    val right = r.right - tol
    val top = r.top + tol
    val bottom = r.bottom - tol
    if (left >= right || top >= bottom) return 0f
    var t0 = 0f
    var t1 = 1f
    val p = floatArrayOf(-dx, dx, -dy, dy)
    val q = floatArrayOf(a.x - left, right - a.x, a.y - top, bottom - a.y)
    for (i in 0..3) {
        if (abs(p[i]) < 1e-6f) {
            // 平行且贴在这条边上：不算穿过。
            if (q[i] <= 0f) return 0f
        } else {
            val t = q[i] / p[i]
            if (p[i] < 0f) {
                if (t > t1) return 0f
                if (t > t0) t0 = t
            } else {
                if (t < t0) return 0f
                if (t < t1) t1 = t
            }
        }
    }
    return if (t1 > t0) t1 - t0 else 0f
}

/** 去掉零长段与共线拐点（画出来还是同一条折线）。 */
private fun simplifyPolyline(points: List<Offset>): List<Offset> {
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

private fun segmentLength(a: Offset, b: Offset): Float {
    val dx = b.x - a.x
    val dy = b.y - a.y
    return sqrt(dx * dx + dy * dy)
}

private fun ArrowPath.toNoteArrow(): NoteArrow =
    if (points.size <= 2) {
        NoteArrow.Straight(points.first(), points.last())
    } else {
        NoteArrow.Elbow(points)
    }

private fun arrowSegments(arrow: NoteArrow): List<Pair<Offset, Offset>> = when (arrow) {
    is NoteArrow.Straight -> listOf(arrow.start to arrow.end)
    is NoteArrow.Elbow -> arrow.points.zipWithNext()
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
