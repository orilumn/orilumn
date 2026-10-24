package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
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
    /**
     * 框高（像素）：正常形态 = 行数 × 行高；小椭圆形态 = 椭圆高。
     * 默认 0 = 由调用方按 [maxLines] × 行高推算（旧调用不变）。
     */
    val heightPx: Float = 0f,
)

/** 旁注字号相对正文的比例：小一号看得清又不抢正文。 */
internal const val NOTE_BUBBLE_FONT_RATIO: Float = 0.72f

/** 旁注行高相对自身字号的比例（与正文行高无关：旁注自己排自己的）。 */
internal const val NOTE_BUBBLE_LINE_RATIO: Float = 1.38f

/** 旁注文字单侧内缩相对自身字号的比例（两侧合计 [NOTE_BUBBLE_PAD_RATIO] × 2）：落位估宽与绘制同源。 */
internal const val NOTE_BUBBLE_PAD_RATIO: Float = 0.35f

/** 框与原文近到这个距离以内（px）就没有可画的箭头：框已经贴住墨迹。 */
private const val NOTE_ARROW_MIN_GAP_PX: Float = 0.5f

/** 判断两个矩形/线段是否相碰时的容差（像素）：避免「刚好贴着」被判成压住。 */
private const val NOTE_GEOM_EPS: Float = 0.5f

/** 最少可读的字数：能显示这么多个字就显示框（一行也行）；再少就退成小图标。 */
private const val MIN_SHOWN_CHARS: Int = 4

/** 小椭圆宽 / 高（以旁注字号为单位）：只是「这里有一条批注」的记号，不抢正文。 */
private const val NOTE_ICON_WIDTH_RATIO: Float = 0.5f
private const val NOTE_ICON_HEIGHT_RATIO: Float = 0.32f

/** 小椭圆的点击热区边长（以旁注字号为单位）：视觉小，手指仍够得着。 */
internal const val NOTE_ICON_HIT_RATIO: Float = 1.2f

/**
 * 标注与障碍、与另一条标注之间的最小距离（以旁注字号为单位）：四周一律 0.1em。
 *
 * 横向只留一条细缝：只有「放在所选文字右侧」那种摆法需要给水平箭头留地方，
 * 那一段由 [NOTE_SIDE_GAP_EM] 单独给，不该让所有批注左侧都白留一格。
 */
private const val NOTE_MARGIN_X_EM: Float = 0.1f
private const val NOTE_MARGIN_Y_EM: Float = 0.1f

/** 标注放在所选文字右侧时与文字的距离（要留出画水平箭头的地方）。 */
private const val NOTE_SIDE_GAP_EM: Float = 1.8f

/** 标注离屏幕边的距离：贴边会让框看起来像被裁掉一条。 */
private const val NOTE_PAGE_EDGE_EM: Float = 0.5f

/** 每条批注最多保留多少个候选（按排序取前 N，避免极端页面候选爆炸）。 */
private const val MAX_CANDIDATES_PER_NOTE: Int = 300

/** 箭头避让逐字墨迹的容差：不到半个像素的贴边不算压字。 */
private const val ARROW_GLYPH_TOL_PX: Float = 0.5f

/** 箭头避让图形 / 表格 / 线条 / 其他旁注框的容差：这几类边界是硬线，多让一点。 */
private const val ARROW_SOLID_TOL_PX: Float = 1f

/** 箭头不许拐回自己的旁注框：只差一根线的量就算「进去」。 */
private const val ARROW_OWN_BUBBLE_TOL_PX: Float = 0.01f

/** 折线折点贴着障碍边取坐标时留的缝（像素）。 */
private const val ARROW_BEND_CLEARANCE_PX: Float = 1f

/** 折线最多三个拐点（4 段，§6.2）。 */
private const val ARROW_MAX_BENDS: Int = 3

/** 箭头最多考虑几个最近的锚点字：最近那个被围死时换下一个字。 */
private const val ARROW_TARGET_LIMIT: Int = 4

/** 两折 / 三折每个方向最多试几个折点坐标（再多的只是白算）。 */
private const val ARROW_MAX_BEND_COORDS: Int = 8

/** 折线找障碍时看两端连线附近多远（以旁注行高为单位，至少 8px）。 */
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

/**
 * 旁注文字的尺寸来源：整句要多宽、给定宽高下能读几个字，都问它。
 *
 * 默认实现是粗估（[EstimatedNoteTextSizer]，纯函数，单测直接用）；UI 层换成
 * **与绘制同一套 Compose 文字测量**（[rememberNoteTextSizer]）。粗估哪怕只比真实排版
 * 窄一点点，摆位时也会判成「整句放得下」、画出来却被省略号截掉——真机上就是
 * 「整页都是空白，批注却折成两个字」这种不该出现的折叠。
 */
internal interface NoteTextSizer {
    /**
     * 整句排成一行需要的宽（像素，不含两侧内缩）。
     *
     * 实现要把**绘制侧的取整损耗**算进去：绘制时框宽与内缩各自按整数像素落地，
     * 卡满整句宽的框会被少算掉半个像素，于是刚好放得下的整句反而被省略号截掉。
     */
    fun naturalWidth(text: String, fontPx: Float): Float

    /**
     * 在 [widthPx] 宽、最多 [maxLines] 行时，整句需要几行、能显示几个字。
     *
     * @param maxLines [Int.MAX_VALUE] = 不限行数（量整句要几行）。
     */
    fun fit(text: String, fontPx: Float, widthPx: Float, maxLines: Int): NoteTextFit
}

/** 粗估尺子：中文 / 全角按一个字宽、西文 0.55、空白 0.3（不依赖文字测量，单测的默认口径）。 */
internal object EstimatedNoteTextSizer : NoteTextSizer {
    override fun naturalWidth(text: String, fontPx: Float): Float = estimatedNoteTextWidth(text, fontPx)

    override fun fit(text: String, fontPx: Float, widthPx: Float, maxLines: Int): NoteTextFit =
        noteTextFit(text, widthPx, fontPx, maxLines)
}

/** 候选框相对锚点的方向（§0.1 的固定顺序；枚举顺序即环内优先级）。 */
internal enum class NoteSide {    /** 所选行右侧（只出现在第 0 环）。 */
    RIGHT,

    /** 右下方。 */
    BELOW_RIGHT,

    /** 右上方。 */
    ABOVE_RIGHT,

    /** 正下方（右侧不可用时）。 */
    BELOW,

    /** 正上方。 */
    ABOVE,

    /** 左侧（本段其余位置才允许）。 */
    LEFT,

    /** 归不进以上方向（兜底，排在最后）。 */
    OTHER,
}

/**
 * 一条批注的一个候选框（纯几何）。
 *
 * 字段只服务两件事：**排序**（[stage] → [ring] → [side] → [distance]）与**落位**
 * （[rect] 是最终框）。
 */
internal data class NoteCandidate(
    val annotationId: Long,
    /** 候选框本身（已按行数定好高）。 */
    val rect: Rect,
    /** 这个候选来自哪块空白：宽度 / 高度都是从它里面扣出来的。 */
    val freeRect: Rect,
    /** 候选框占几行（1 起）。 */
    val lines: Int,
    /** 整句在这一框里放得下。 */
    val complete: Boolean,
    /** 折行后实际能显示的字数（不含换行符）。 */
    val shownChars: Int,
    /** 相对锚点的方向。 */
    val side: NoteSide,
    /** 第几环：0 = 直接相邻，1 = 隔 1 行，依此类推。 */
    val ring: Int,
    /** 阶梯段：0 本段环、1 本段左侧、2 相邻段环、3 相邻段左侧（§1.4）。 */
    val stage: Int,
    /** 框到锚点墨迹的边到边最短距离（同段同环时的决胜项）。 */
    val distance: Float,
) {
    /**
     * 两个候选框是否冲突：矩形相交，或间距（横向 [marginX]、纵向 [marginY]）不足。
     */
    fun conflicts(other: NoteCandidate, marginX: Float = 0f, marginY: Float = marginX): Boolean =
        rect.left < other.rect.right + marginX - NOTE_GEOM_EPS &&
            other.rect.left < rect.right + marginX - NOTE_GEOM_EPS &&
            rect.top < other.rect.bottom + marginY - NOTE_GEOM_EPS &&
            other.rect.top < rect.bottom + marginY - NOTE_GEOM_EPS
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
 * 旁注样式：字号、字体、行距、内缩与横向 / 纵向留白（将来由「批注样式」设置页提供，D-29）。
 *
 * 比例全部以**旁注字号**为基准；字号与字体可以脱离正文单独设置，默认值只是推荐值，
 * 不是写死的常量。样式变化只影响旁注自己，不碰正文排版。
 *
 * @property fontPx 旁注字号（像素）。默认 = 正文主字号 × [NOTE_BUBBLE_FONT_RATIO]。
 * @property fontFamily 旁注字体（`null` = 跟随界面默认）；批注样式落地后从这里进来。
 * @property lineHeightRatio 行高 / 字号。
 * @property padEm 单侧内缩 / 字号。
 * @property marginXEm 与障碍、与另一条旁注的横向最小距离 / 字号。
 * @property marginYEm 与障碍、与另一条旁注的纵向最小距离 / 字号。
 * @property sideGapEm 放在文字右侧时与文字的侧向距离 / 字号（留给水平箭头）。
 * @property edgeEm 离屏幕边的距离 / 字号。
 */
internal data class NoteStyle(
    val fontPx: Float,
    val fontFamily: String? = null,
    val lineHeightRatio: Float = NOTE_BUBBLE_LINE_RATIO,
    val padEm: Float = NOTE_BUBBLE_PAD_RATIO,
    val marginXEm: Float = NOTE_MARGIN_X_EM,
    val marginYEm: Float = NOTE_MARGIN_Y_EM,
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

    /** 标注与障碍、与另一条标注之间的横向最小距离。 */
    val marginX: Float get() = em * style.marginXEm

    /** 标注与障碍、与另一条标注之间的纵向最小距离。 */
    val marginY: Float get() = em * style.marginYEm

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
 * @param sizer 文字尺寸来源：默认粗估；UI 层传与绘制同源的实测（[rememberNoteTextSizer]）。
 */
internal fun noteBoxFit(
    text: String,
    metrics: NoteMetrics,
    maxWidth: Float,
    maxHeight: Float,
    sizer: NoteTextSizer = EstimatedNoteTextSizer,
): NoteBoxSize {
    val content = text.trim()
    val total = content.count { it != '\n' }
    if (total == 0 || maxWidth <= 0f || maxHeight <= 0f) {
        return NoteBoxSize(0f, 0, 0f, 0, total)
    }
    val innerW = maxWidth - metrics.padTotal
    val capacity = (maxHeight / metrics.lineH).toInt()
    if (innerW <= 0f || capacity < 1) return NoteBoxSize(0f, 0, 0f, 0, total)

    val desired = sizer.naturalWidth(content, metrics.em)
    // 整句一行放得下就不再让折行模拟复核：真实排版比「宽/字宽」更怕差一像素，
    // 实测尺子（[rememberNoteTextSizer]）已在整句宽里含了绘制侧的取整余量。
    val oneLine = desired > 0f && desired <= innerW + NOTE_GEOM_EPS
    val width = min(desired, innerW) + metrics.padTotal
    val inner = width - metrics.padTotal
    val needed = if (oneLine) 1 else sizer.fit(content, metrics.em, inner, Int.MAX_VALUE).lines
    val lines = min(needed, capacity)
    val fit = if (oneLine) {
        NoteTextFit(lines = 1, shownChars = total, totalChars = total)
    } else {
        sizer.fit(content, metrics.em, inner, lines)
    }
    return NoteBoxSize(
        width = width,
        lines = lines,
        height = lines * metrics.lineH,
        shownChars = min(fit.shownChars, total),
        totalChars = total,
    )
}

/**
 * 一条批注的候选框，按**由近到远阶梯**逐格生成（方案 §0.1 / §1.1）。
 *
 * 顺序 = 段（本段 → 本段左侧 → 相邻段 → 相邻段左侧）→ 环（0 = 直接相邻，1 = 隔 1 行…）
 * → 方向（右 / 右下 / 右上 / 下 / 上 / 左）。所有位置都**贴住锚点**：右侧贴该行右缘 + `sideGap`，
 * 右下 / 右上贴锚点的角，正下 / 正上贴锚点的边；可用宽高从「包含该点的极大空闲矩形」里扣，
 * 框必须落在其中（空白已按 margin 外扩障碍切好）。
 */
internal fun noteCandidates(
    note: NoteInk,
    lines: List<NoteTextLine>,
    freeRects: List<Rect>,
    metrics: NoteMetrics,
    maxCandidates: Int = MAX_CANDIDATES_PER_NOTE,
    sizer: NoteTextSizer = EstimatedNoteTextSizer,
): List<NoteCandidate> {
    val text = note.text.trim()
    if (text.isEmpty() || note.rects.isEmpty() || lines.isEmpty() || freeRects.isEmpty()) return emptyList()
    val total = text.count { it != '\n' }
    if (total == 0) return emptyList()
    val anchor = rectUnion(note.rects)
    val noteRows = noteRowsBottomUp(note.rects, lines)
    if (noteRows.isEmpty()) return emptyList()
    val base = noteRows.first()
    val first = noteRows.last()
    val baseBlock = lines[base].blockId
    var blockStart = base
    while (blockStart > 0 && lines[blockStart - 1].blockId == baseBlock) blockStart--
    var blockEnd = base
    while (blockEnd + 1 < lines.size && lines[blockEnd + 1].blockId == baseBlock) blockEnd++

    val marginX = metrics.marginX
    val marginY = metrics.marginY
    val out = ArrayList<NoteCandidate>()
    val seen = HashSet<Long>()

    /**
     * 右 / 左：返回可用的空白列（最多两个：最宽的、最高的）。
     *
     * 只取一个矩形会漏掉「窄但高」的空白：最宽的那块常常只有一行高，框被高度逼成单行后
     * 明明旁边还有能折行的竖条，却只能打省略号（真机反馈的第一条）。两个都生成，由分配阶段
     * 按「整句优先」挑——窄高的能折行读出整句，自然胜出。
     *
     * 优先取「纵向包含 yHint」的；都不包含时取中心最近的一块（退化路径）。
     */
    fun columnRects(x: Float, row: Rect, toRight: Boolean): List<Rect> {
        val yHint = row.center.y
        val cols = freeRects.filter { f ->
            x >= f.left - NOTE_GEOM_EPS && x <= f.right + NOTE_GEOM_EPS &&
                (if (toRight) f.right - x else x - f.left) > 1f &&
                // 必须与这一行**纵向相交**：整块空白都在行下方，那是「下」不是「右」。
                f.top < row.bottom - NOTE_GEOM_EPS && f.bottom > row.top + NOTE_GEOM_EPS
        }
        if (cols.isEmpty()) return emptyList()
        val atY = cols.filter { yHint >= it.top - NOTE_GEOM_EPS && yHint <= it.bottom + NOTE_GEOM_EPS }
        if (atY.isEmpty()) {
            val nearest = cols.minWithOrNull(compareBy({ abs(it.center.y - yHint) }, { -it.height }))
            return listOfNotNull(nearest)
        }
        fun room(f: Rect) = if (toRight) f.right - x else x - f.left
        val widest = atY.maxWithOrNull(compareBy({ room(it) }, { it.height }))
        val tallest = atY.maxWithOrNull(compareBy({ it.height }, { room(it) }))
        return listOfNotNull(widest, tallest).distinct()
    }

    /**
     * 下 / 上：返回可用的空白带（最多两个：最贴 xHint 的、进深最大的）。
     *
     * 同样不能只取一个：贴边的一线薄空白进深不够折行，旁边稍远但更深的空白能放下整句时应该
     * 让后者参与竞争（整句优先），而不是先被薄空白截断。
     */
    fun bandRects(y: Float, xHint: Float, below: Boolean): List<Rect> {
        val band = freeRects.filter { y >= it.top - NOTE_GEOM_EPS && y <= it.bottom + NOTE_GEOM_EPS }
        if (band.isEmpty()) return emptyList()
        fun depth(f: Rect) = if (below) f.bottom - y else y - f.top
        fun dx(f: Rect) = when {
            xHint > f.right -> xHint - f.right
            xHint < f.left -> f.left - xHint
            else -> 0f
        }
        // 先排除「连一行都放不下」的薄缝（贴边的切口），再按横向距离、可用进深取。
        val usable = band.filter { depth(it) >= metrics.lineH - NOTE_GEOM_EPS }
        val pool = usable.ifEmpty { band }
        val nearest = pool.minWithOrNull(compareBy({ dx(it) }, { -depth(it) }, { -it.width }))
        val deepest = pool.maxWithOrNull(compareBy({ depth(it) }, { -dx(it) }, { it.width }))
        return listOfNotNull(nearest, deepest).distinct()
    }

    /** x 是左缘（LEFT 是右缘），y 是顶边（ABOVE* 是底边，RIGHT 是垂直中心）。 */
    fun emit(
        side: NoteSide,
        ring: Int,
        stage: Int,
        x: Float,
        y: Float,
        f: Rect,
        maxW: Float,
        maxH: Float,
    ) {
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        if (size.width > maxW + NOTE_GEOM_EPS || size.height > maxH + NOTE_GEOM_EPS) return
        val left: Float
        val top: Float
        when (side) {
            NoteSide.LEFT -> {
                left = min(x, f.right) - size.width
                top = (y - size.height / 2f).coerceIn(f.top, max(f.top, f.bottom - size.height))
            }
            NoteSide.RIGHT -> {
                left = max(x, f.left)
                top = (y - size.height / 2f).coerceIn(f.top, max(f.top, f.bottom - size.height))
            }
            NoteSide.ABOVE, NoteSide.ABOVE_RIGHT -> {
                left = x.coerceIn(f.left, max(f.left, f.right - size.width))
                top = y - size.height
            }
            else -> {
                left = x.coerceIn(f.left, max(f.left, f.right - size.width))
                top = y
            }
        }
        if (left < f.left - NOTE_GEOM_EPS || left + size.width > f.right + NOTE_GEOM_EPS ||
            top < f.top - NOTE_GEOM_EPS || top + size.height > f.bottom + NOTE_GEOM_EPS
        ) {
            return
        }
        val rect = Rect(left, top, left + size.width, top + size.height)
        // 同一块空白被不同极大矩形重复切出时，落位可能完全一样：只留一个。
        val key = (rect.left.roundToInt().toLong() shl 48) xor
            (rect.top.roundToInt().toLong() shl 32) xor
            (rect.right.roundToInt().toLong() shl 16) xor
            rect.bottom.roundToInt().toLong()
        if (!seen.add(key)) return
        out += NoteCandidate(
            annotationId = note.annotationId,
            rect = rect,
            freeRect = f,
            lines = size.lines,
            complete = size.complete,
            shownChars = size.shownChars,
            side = side,
            ring = ring,
            stage = stage,
            distance = note.rects.minOf { rectDistance(rect, it) },
        )
    }

    fun emitRight(rowIndex: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val x = row.rect.right + metrics.sideGap
        for (f in columnRects(x, row.rect, toRight = true)) {
            val left = max(x, f.left)
            emit(NoteSide.RIGHT, 0, 0, left, row.rect.center.y, f, f.right - left, f.height)
        }
    }

    fun emitBelow(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val y = row.rect.bottom + marginY
        // 右下角：框左边只留横向 margin（引线走斜线 / 折线，不需要水平箭头的空档）。
        val xr = max(anchor.right, row.rect.right) + marginX
        val floor = anchor.left + marginX
        for (f in bandRects(y, xr, below = true)) {
            val x0 = max(max(xr, f.left), floor)
            if (x0 < f.right - 1f) {
                emit(NoteSide.BELOW_RIGHT, ring, stage, x0, y, f, f.right - x0, f.bottom - y)
            }
        }
        for (f in bandRects(y, anchor.left, below = true)) {
            val x0 = anchor.left.coerceIn(f.left, f.right)
            emit(NoteSide.BELOW, ring, stage, x0, y, f, f.right - x0, f.bottom - y)
        }
    }

    fun emitAbove(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val y = row.rect.top - marginY
        // 右上角与右下角同口径：只留横向 margin。
        val xr = max(anchor.right, row.rect.right) + marginX
        val floor = anchor.left + marginX
        for (f in bandRects(y, xr, below = false)) {
            val x0 = max(max(xr, f.left), floor)
            if (x0 < f.right - 1f) {
                emit(NoteSide.ABOVE_RIGHT, ring, stage, x0, y, f, f.right - x0, y - f.top)
            }
        }
        for (f in bandRects(y, anchor.left, below = false)) {
            val x0 = anchor.left.coerceIn(f.left, f.right)
            emit(NoteSide.ABOVE, ring, stage, x0, y, f, f.right - x0, y - f.top)
        }
    }

    fun emitLeft(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val x = row.rect.left - marginX
        for (f in columnRects(x, row.rect, toRight = false)) {
            val right = min(x, f.right)
            emit(NoteSide.LEFT, ring, stage, right, row.rect.center.y, f, right - f.left, f.height)
        }
    }

    // 第 0 环：所选各行右侧（末行优先、再逐行向上），紧邻的右下 / 右上 / 正下 / 正上。
    for (idx in noteRows) emitRight(idx)
    emitBelow(base, 0, 0)
    emitAbove(first, 0, 0)

    // 环 1…n：本段内由近到远（往下一条、上一条地数）。
    var k = 1
    while (true) {
        var any = false
        if (base + k <= blockEnd) {
            emitBelow(base + k, k, 0)
            any = true
        }
        if (first - k >= blockStart) {
            emitAbove(first - k, k, 0)
            any = true
        }
        if (!any) break
        k++
    }

    // 本段其余位置：左侧页边距最后才允许。
    for (idx in blockStart..blockEnd) emitLeft(idx, abs(idx - base), 1)

    // 相邻段：继续往外，仍由近到远；左侧同样排在最后。
    for (idx in blockEnd + 1 until lines.size) emitBelow(idx, idx - base, 2)
    for (idx in blockStart - 1 downTo 0) emitAbove(idx, first - idx, 2)
    for (idx in 0 until lines.size) {
        if (idx < blockStart || idx > blockEnd) emitLeft(idx, abs(idx - base), 3)
    }

    // 读者反馈第 5 条：批注比所选文字长、又紧贴着它时，正下 / 正上的箭头只剩一条短竖线。
    // 同一侧再远一行还有整句位置的话，就让给远一行的那条（引线才有长度）。
    if (sizer.naturalWidth(text, metrics.em) > anchor.width) {
        val farther = out.any { c ->
            c.ring > 0 && c.stage == 0 && c.complete &&
                (c.side == NoteSide.BELOW || c.side == NoteSide.ABOVE)
        }
        if (farther) {
            out.removeAll { c ->
                c.ring == 0 && c.complete &&
                    (c.side == NoteSide.BELOW || c.side == NoteSide.ABOVE)
            }
        }
    }

    return out.sortedWith(
        compareBy<NoteCandidate> { it.stage }
            .thenBy { it.ring }
            .thenBy { it.side.ordinal }
            .thenBy { it.distance }
            .thenBy { it.rect.top }
            .thenBy { it.rect.left },
    ).take(maxCandidates)
}

/** 本页上的批注行，自下而上（右侧那一步「末行放不下再逐行向上」）。 */
private fun noteRowsBottomUp(rects: List<Rect>, lines: List<NoteTextLine>): List<Int> =
    rects.mapNotNull { ink -> nearestLineIndex(ink, lines) }
        .distinct()
        .sortedDescending()

private fun nearestLineIndex(ink: Rect, lines: List<NoteTextLine>): Int? {
    var best: Int? = null
    var bestDy = Float.MAX_VALUE
    for (i in lines.indices) {
        val r = lines[i].rect
        val overlap = min(ink.bottom, r.bottom) - max(ink.top, r.top)
        val dy = if (overlap > 0f) 0f else abs(ink.center.y - r.center.y)
        if (dy < bestDy) {
            bestDy = dy
            best = i
        }
    }
    return best
}

/** 障碍按横向 / 纵向 margin 外扩：框落在空白里即满足四周 0.1em 的间距（右侧箭头空档另算）。 */
private fun inflateRect(r: Rect, marginX: Float, marginY: Float): Rect =
    Rect(r.left - marginX, r.top - marginY, r.right + marginX, r.bottom + marginY)

/**
 * 本页的一条**正文行**：文字墨迹矩形 + 所属块序号 + 页内行序。
 *
 * 布点阶梯要按「隔几行」「本段 / 相邻段」排序（§1.1 / §1.5），所以行身份必须跟着几何一起传进来；
 * [index] 是页内自上而下的序号（0 起），[blockId] 来自渲染层（同一叶 / 同一块同号）。
 */
internal data class NoteTextLine(
    val rect: Rect,
    val blockId: Int = 0,
    val index: Int = 0,
)

/**
 * 一条批注在**本页**的画布事实（用户层 → 旁注层的输入）。
 *
 * [rects] 是这条标注在本页的墨迹矩形（页坐标，由测度引擎逐字算出，与画出来的字同源）：
 * 空 = 本页没有它的墨迹（在别的页上），也就没有「旁边」可言。
 *
 * [arrowRects] 是**箭头专用**的逐字墨迹（一个字一个矩形）：布点用行框（[rects]），
 * 箭头要能穿两字之间与标点前后的空白（`docs/旁注布点与引线-实施方案.md` §6.3）。空 = 回退到 [rects]。
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
     * 空白，故另收一份逐字几何（`docs/旁注布点与引线-实施方案.md` §2.1 / §6.3）。空 = 回退到 [textInk]。
     */
    val arrowInk: List<Rect> = emptyList(),
    /**
     * 本页正文行（框 + 块序号），布点阶梯按它数「隔几行」与「本段 / 相邻段」（§1.1 / §1.5）。
     * 空 = 从 [textInk] 合并出行、块序号一律 0（旧调用与纯几何测试的退化路径）。
     */
    val textLines: List<NoteTextLine> = emptyList(),
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
    textLines: List<NoteTextLine> = emptyList(),
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
        textLines = textLines,
    )
}

/**
 * 旁注箭头（D-28 第 3 部分）：从旁注框指向原文一个字的一条细线。
 *
 * 形态按 `docs/旁注布点与引线-实施方案.md` §6：直线、一折 / 两折 / 三折（最多三个拐点）。
 * 一律用折线（含直线），不用弧线：折线与文字行 / 框边同向，小屏上更清楚，也不会在字缝里
 * 画出一段看不出指向的曲线（真机反馈第 2 条）。
 * 除框退成小图标外一律画出来：宁可线贴一点字边，也不让读者不知道旁注指向哪里。
 */
internal sealed interface NoteArrow {
    val start: Offset
    val end: Offset

    data class Straight(override val start: Offset, override val end: Offset) : NoteArrow

    /** 正交折线：起点 → 拐点 → … → 终点（3～5 个点，相邻两点共享 x 或 y，最多三个拐点）。 */
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
 * 3. [noteCandidates] 按「段 → 环 → 方向 → 距离」的阶梯为每条批注生成候选框（§1.1）；
 * 4. [assignNoteCandidates] 全页分配：同行先成组占右侧，再逐条取整句，最后才开截断档，单向让位；
 * 5. 每条框再路由一条指向原文的箭头（小椭圆形态没有箭头）。
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
    noteTextSizer: NoteTextSizer = EstimatedNoteTextSizer,
): List<NoteBubble> {
    if (noteInk.isEmpty()) return emptyList()
    val metrics = NoteMetrics(noteStyle)
    val body = fontPx.coerceAtLeast(1f)
    val small = metrics.em
    val lineH = metrics.lineH
    val marginX = metrics.marginX
    val marginY = metrics.marginY
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
    // 正文行：优先用渲染层给的「行框 + 块序号」（§1.5）；缺失时退回按 y 合并行框、块号一律 0。
    val lines = obstacles.textLines.ifEmpty {
        // 没有块序号时按「行距 > 行高 × 1.5」断段（§1.5 的退化路径）。
        val merged = mergeTextLineRects(obstacles.textInk, body)
            .sortedWith(compareBy({ it.top }, { it.left }))
        var block = 0
        var prev: Rect? = null
        merged.mapIndexed { index, r ->
            if (prev != null && r.top - prev!!.bottom > prev!!.height * 1.5f) block++
            prev = r
            NoteTextLine(rect = r, blockId = block, index = index)
        }
    }
    // 障碍仍是「文字墨迹（含列表圆点）+ 图形 + 表格 + 线条」：块底色 / padding / 页边距算空白。
    val baseObstacles = obstacles.textInk + obstacles.figures + obstacles.tables + obstacles.strokes
    // 障碍按横向 / 纵向 margin 外扩后再切空白：框落在空白里即满足四周 0.1em 的间距（§2.2）。
    val inflatedObstacles = baseObstacles.map { inflateRect(it, marginX, marginY) }
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
            lines = lines,
            freeRects = freeRects,
            metrics = metrics,
            sizer = noteTextSizer,
        )
        if (list.isNotEmpty()) candidatesByNote[note.annotationId] = list
    }

    // 三档推进（整句 → 截断 → 小椭圆），同一行成组、单向让位（§1.4 / §5）。
    val choices = assignNoteCandidates(
        ordered, lines, candidatesByNote, metrics, marginX, marginY, noteTextSizer,
    )
    val chosen = choices.associateBy { it.note.annotationId }
    // 一条都分不到的批注不再消失：在原文右下角退成小椭圆（§1.6）。
    val iconW = max(small * NOTE_ICON_WIDTH_RATIO, 4f)
    val iconH = max(small * NOTE_ICON_HEIGHT_RATIO, 3f)
    // 同一个锚点掉出多个小椭圆时，沿右下角向下错开一个图标高（§1.6）。
    val icons = ArrayList<Rect>()
    val out = ordered.map { note ->
        val choice = chosen[note.annotationId]
        if (choice == null) {
            var icon = noteIconRect(rectUnion(note.rects), gap * 0.4f, iconW, iconH, pageBounds)
            var guard = 0
            while (icons.any { it.overlaps(icon) } && guard < 16) {
                icon = Rect(icon.left, icon.top + iconH + marginY, icon.right, icon.bottom + marginY)
                guard++
            }
            icons += icon
            NoteBubble(
                annotationId = note.annotationId,
                text = note.text.trim(),
                leftPx = icon.left,
                topPx = icon.top,
                widthPx = iconW,
                heightPx = iconH,
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
                heightPx = choice.candidate.lines * lineH,
                maxLines = choice.candidate.lines,
                argb = note.argb,
                fontPx = small,
            )
        }
    }

    val inkById = noteInk.associateBy { it.annotationId }
    val bubbleRects = out.filterNot { it.compact }.associate { b ->
        val h = if (b.heightPx > 0f) b.heightPx else b.maxLines * lineH
        b.annotationId to Rect(b.leftPx, b.topPx, b.leftPx + b.widthPx, b.topPx + h)
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

/**
 * 全页分配（方案 §1.4 / §5）：**整句 → 截断 → 小椭圆**三档推进，同一行成组、单向让位。
 *
 * 1. 同一基准行的批注先成组：按锚点左右次序**竖向**排开（上方或下方，引线不交叉）；
 *    整组放不下时不抢位，交给后面的阶梯（§1.3）；
 * 2. 其余批注按「锚点行自上而下、同行自右向左」逐条取**整句**候选里最靠前的一个
 *    （候选已按 段 → 环 → 方向 → 距离 排好序）；
 * 3. 整句都排不下的，再从自己的候选里取**至少能读 4 字**的最靠前一个；
 * 4. 仍然没有的交给调用方退成小椭圆（§1.6）。
 *
 * 已选框立即成为后选框的障碍（四周 0.1em），先挑者永不被挤走。
 * 不做两两互换、也不做页边距推挤：位置全部来自阶梯候选，顺序可复现。
 */
private fun assignNoteCandidates(
    notes: List<NoteInk>,
    lines: List<NoteTextLine>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    metrics: NoteMetrics,
    marginX: Float,
    marginY: Float,
    sizer: NoteTextSizer,
): List<NoteChoice> {
    if (notes.isEmpty() || lines.isEmpty()) return emptyList()
    val chosen = LinkedHashMap<Long, NoteCandidate>()
    val occupied = ArrayList<NoteCandidate>()

    fun fits(c: NoteCandidate): Boolean = occupied.none { c.conflicts(it, marginX, marginY) }
    fun take(note: NoteInk, c: NoteCandidate): Boolean {
        if (!fits(c)) return false
        chosen[note.annotationId] = c
        occupied += c
        return true
    }

    fun rowOf(note: NoteInk): Int = nearestLineIndex(rectUnion(note.rects), lines) ?: lines.size

    // 同一基准行成组：多条批注优先按锚点左右次序竖向排开（避免引线交叉）。
    for ((rowIndex, members) in notes.groupBy { rowOf(it) }.toSortedMap()) {
        packSameLineGroup(members, rowIndex, lines, candidatesByNote, metrics, sizer, ::take)
    }

    // 先挑顺序（§5.1）：正文流靠前 → 同行自右向左（右侧留给自己）→ 批注 id。
    val order = notes.sortedWith(
        compareBy<NoteInk>(
            { rowOf(it) },
            { -rectUnion(it.rects).right },
            { it.annotationId },
        ),
    )

    // 第 1 档：整句。
    for (note in order) {
        if (chosen.containsKey(note.annotationId)) continue
        val pick = candidatesByNote[note.annotationId]
            .orEmpty()
            .firstOrNull { it.complete && take(note, it) }
        if (pick != null) continue
    }
    // 第 2 档：截断（至少 4 字，候选里已保证 readable）。
    for (note in order) {
        if (chosen.containsKey(note.annotationId)) continue
        candidatesByNote[note.annotationId].orEmpty().firstOrNull { take(note, it) }
    }
    return order.mapNotNull { note -> chosen[note.annotationId]?.let { NoteChoice(note, it) } }
}

/**
 * 同一行的多条批注成组竖向排开（§1.3）：**先算高度，再整体决定放上方还是下方**。
 *
 * 读者反馈的第 3 条：同一行有多条批注时，要综合考虑垂直排布，优先整体。
 * 上方有空间时，最右边的批注放最下面、最左边的放最上面；下方有空间时反过来。
 * 这样引线从各自的锚点散开，不会为了指向并排的两个框而互相交叉。
 *
 * 具体做法：
 * 1. 取该行右侧空白里**最高**的一列（竖向成组需要高度，不取最宽）；
 * 2. 每条按这一列宽算整句高度（能折行就折行，不急着截断）；
 * 3. 整组高度放得进上方就放上方，否则放下方；两边都放不下就不抢位，交给阶梯逐条处理；
 * 4. 按锚点右缘排序后依次占位，框与框留纵向 margin。
 *
 * 单条不走这里（阶梯已经处理）。
 */
private inline fun packSameLineGroup(
    members: List<NoteInk>,
    rowIndex: Int,
    lines: List<NoteTextLine>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    metrics: NoteMetrics,
    sizer: NoteTextSizer,
    take: (NoteInk, NoteCandidate) -> Boolean,
) {
    if (members.size < 2) return
    val row = lines.getOrNull(rowIndex) ?: return
    // 该行右侧最高的空白列：竖向成组要的是高度，不是宽度。
    val f = members.asSequence()
        .flatMap { note ->
            candidatesByNote[note.annotationId].orEmpty().asSequence().filter {
                it.side == NoteSide.RIGHT && it.ring == 0
            }
        }
        .map { it.freeRect }
        .maxWithOrNull(compareBy({ it.height }, { it.width }))
        ?: return
    val x = max(row.rect.right + metrics.sideGap, f.left)
    val maxW = f.right - x
    if (maxW <= 1f) return

    // 先按同一列宽把每条的高度算出来；读不出四个字的条目不参与成组，交给小图标路径。
    val items = members.mapNotNull { note ->
        val size = noteBoxFit(note.text, metrics, maxW, f.height, sizer)
        if (size.readable) note to size else null
    }
    if (items.size < 2) return

    val gap = metrics.marginY
    var total = gap * (items.size - 1)
    for ((_, size) in items) total += size.height
    // 上方：最左的最上、最右的最下；下方：最右的最上、最左的最下（引线不交叉）。
    val ordered = if (row.rect.top - f.top - gap >= total) {
        items.sortedWith(
            compareBy({ rectUnion(it.first.rects).right }, { it.first.annotationId }),
        )
    } else {
        items.sortedWith(
            compareBy({ -rectUnion(it.first.rects).right }, { it.first.annotationId }),
        )
    }
    // 下方成组时，第一条尽量留在「行右侧」原位（先挑者的位置不被后来者挤走），
    // 其余按锚点次序往下排；上方成组则整体贴着行顶往上排。
    val above = row.rect.top - f.top - gap >= total
    val belowStart = (row.rect.center.y - ordered.first().second.height / 2f)
        .coerceIn(f.top, max(f.top, f.bottom - total))
    val canBelow = belowStart + total <= f.bottom + NOTE_GEOM_EPS
    if (!above && !canBelow) return
    var y = if (above) row.rect.top - gap - total else belowStart
    for ((note, size) in ordered) {
        val rect = Rect(x, y, x + size.width, y + size.height)
        val candidate = NoteCandidate(
            annotationId = note.annotationId,
            rect = rect,
            freeRect = f,
            lines = size.lines,
            complete = size.complete,
            shownChars = size.shownChars,
            side = if (above) NoteSide.ABOVE_RIGHT else NoteSide.BELOW_RIGHT,
            ring = 0,
            stage = 0,
            distance = note.rects.minOf { rectDistance(rect, it) },
        )
        take(note, candidate)
        y += size.height + gap
    }
}

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
private fun noteIconRect(ink: Rect, gap: Float, width: Float, height: Float, page: Rect): Rect {
    val xRight = ink.right + gap
    val xFlush = ink.right - width
    val yBelow = ink.bottom + gap
    val yAbove = ink.top - gap - height
    // 顺序：右下角外侧 → 右下角内侧 → 右上 → 左下（§1.6：贴住原文右下角）。
    val candidates = listOf(
        Rect(xRight, yBelow, xRight + width, yBelow + height),
        Rect(xFlush, yBelow, xFlush + width, yBelow + height),
        Rect(xRight, yAbove, xRight + width, yAbove + height),
        Rect(xFlush, yAbove, xFlush + width, yAbove + height),
        Rect(ink.left - gap - width, yBelow, ink.left - gap, yBelow + height),
    )
    fun inside(r: Rect) = r.left >= page.left - NOTE_GEOM_EPS && r.top >= page.top - NOTE_GEOM_EPS &&
        r.right <= page.right + NOTE_GEOM_EPS && r.bottom <= page.bottom + NOTE_GEOM_EPS
    val within = candidates.filter(::inside)
    within.firstOrNull { !it.overlaps(ink) }?.let { return it }
    candidates.firstOrNull { !it.overlaps(ink) }?.let { r ->
        val cx = r.left.coerceIn(page.left, max(page.left, page.right - width))
        val cy = r.top.coerceIn(page.top, max(page.top, page.bottom - height))
        return Rect(cx, cy, cx + width, cy + height)
    }
    within.firstOrNull()?.let { return it }
    val x = candidates.first().left.coerceIn(page.left, max(page.left, page.right - width))
    val y = candidates.first().top.coerceIn(page.top, max(page.top, page.bottom - height))
    return Rect(x, y, x + width, y + height)
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

    /** 通畅路径的择优顺序（§6.3）：已画引线交叉少 → 折数少 → 更短 → 候选顺序。 */
    fun betterThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (crosses != other.crosses) return crosses < other.crosses
        if (bends != other.bends) return bends < other.bends
        if (abs(length - other.length) > 0.5f) return length < other.length
        return order < other.order
    }

    /** 全撞时的兜底顺序：不穿自己的框 → 穿得少 → 更短 → 候选顺序。照样画，不留空。 */
    fun betterFallbackThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (entersBubble != other.entersBubble) return !entersBubble
        if (abs(hits - other.hits) > 0.5f) return hits < other.hits
        if (abs(length - other.length) > 0.5f) return length < other.length
        return order < other.order
    }
}

/** 一个目标字（锚点在本页的一个字）对应的搜索材料：它的图形 + 附近障碍。 */
private class ArrowTarget(val glyph: Rect, val obstacles: List<ArrowObstacle>)

/** 标注框与锚点墨迹的关系（§6.1）：四类互斥，判定顺序固定。 */
internal enum class NoteArrowRelation { RIGHT, LEFT, ABOVE, BELOW, SIDE }

/**
 * 关系判定（§6.1）：先看水平投影是否重叠 ≥ 1px，再看完全在右 / 左，余下归斜向。
 *
 * 投影用长度而不是中心点，避免「斜靠」被误判成上下。
 */
internal fun noteArrowRelation(bubble: Rect, anchor: Rect): NoteArrowRelation {
    val overlapX = min(bubble.right, anchor.right) - max(bubble.left, anchor.left)
    if (overlapX >= 1f) {
        return if (bubble.center.y < anchor.center.y) {
            NoteArrowRelation.ABOVE
        } else {
            NoteArrowRelation.BELOW
        }
    }
    return when {
        bubble.left >= anchor.right -> NoteArrowRelation.RIGHT
        bubble.right <= anchor.left -> NoteArrowRelation.LEFT
        else -> NoteArrowRelation.SIDE
    }
}

/**
 * 给一条旁注找箭头路由（纯函数，§6）。
 *
 * 形态按标注与正文的相对关系决定（§6.1 / §6.2）：
 * - 右侧：无字隔开是水平直线（标注左边中点 → 文字右边中点）；
 * - 左侧：无遮挡是水平直线（标注右边中点 → 文字左边中点）；
 * - 上下：垂直直线（标注朝文字那条边的中点 → 文字近端边）；
 * - 斜向：两框最近点直线。
 *
 * 直线被字挡住时**只用正交折线**（一折 → 两折 → 三折，最多 4 段），不画弧线。
 * **必画**：所有形态全撞障碍时，取「穿得最少、再最短」的一条照样画出来——宁可线贴一点字边，
 * 也不能让读者不知道这条旁注指向哪里。只有框退成小图标、或框已经贴住墨迹才没有箭头。
 *
 * 避让对象（§6.3）：文字**逐字**墨迹（容差 0.5px，自己的锚点字除外——箭头就是要指到它）、
 * 图形 / 表格 / 线条（1px）、其他旁注框（1px）。已画引线只做交叉惩罚，不硬堵死。
 * 贴边不算穿过；不得拐回自己的框。块底色 / `padding` / `margin` / 页边距都算空白。
 *
 * @param inkRects 这条批注**自己**在本页的逐字墨迹：箭头指向它，故它不是障碍。
 * @param glyphs 本页所有逐字墨迹（含自己的，内部剔除）：换成逐字几何后，线能从两字之间、
 *   标点前后穿过，不再把整行当一个黑块。
 * @param solids 图形 / 表格 / 线条。
 * @param otherBubbles 其他旁注框。
 * @param drawnArrows 已经画好的引线：尽量不穿越（软惩罚）。
 * @param lineH 旁注行高：折线找障碍的范围以它为单位。
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
    val anchor = rectUnion(inkRects)
    // 框已经贴住原文：连一根线的位置都没有，画出来只会压在字上。
    if (rectDistance(bubble, anchor) <= NOTE_ARROW_MIN_GAP_PX) return null
    val relation = noteArrowRelation(bubble, anchor)

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
    val window = max(lineH, 8f) * ARROW_BEND_WINDOW_LINES
    // 目标只取最近的几个字：最近那个被围死时，还有同一条批注的其他字可指。
    val targets = inkRects
        .sortedBy { rectDistance(bubble, it) }
        .take(ARROW_TARGET_LIMIT)
        .map { glyph ->
            val near = Rect(
                min(bubble.left, glyph.left) - window,
                min(bubble.top, glyph.top) - window,
                max(bubble.right, glyph.right) + window,
                max(bubble.bottom, glyph.bottom) + window,
            )
            ArrowTarget(glyph = glyph, obstacles = obstacles.filter { it.rect.overlaps(near) })
        }

    var order = 0
    var fallback: ArrowPath? = null
    var best: ArrowPath? = null

    fun consider(points: List<Offset>, target: ArrowTarget) {
        val path = measureArrowRoute(points, order++, target.obstacles, bubble, drawnSegments) ?: return
        if (path.clear) {
            if (path.betterThan(best)) best = path
        } else if (path.betterFallbackThan(fallback)) {
            fallback = path
        }
    }

    // 1) 首选形态（§6.2）：左右水平直线、上下垂直线、斜向最近点直线。
    for (t in targets) {
        val ends = straightArrowEnds(relation, bubble, t.glyph)
        consider(ends, t)
    }
    best?.let { if (it.clear && it.crosses == 0) return it.toNoteArrow() }

    // 2) 折线兜底：一折 → 两折 → 三折；先能用少的拐点就用少的。
    for (bends in 1..ARROW_MAX_BENDS) {
        for (t in targets) {
            val ends = straightArrowEnds(relation, bubble, t.glyph)
            for (points in noteElbowRoutes(
                start = ends.first(),
                end = ends.last(),
                obstacles = t.obstacles.map { it.rect },
                bends = bends,
            )) {
                consider(points, t)
            }
        }
        best?.let { if (it.clear && it.crosses == 0) return it.toNoteArrow() }
    }
    return (best ?: fallback)?.toNoteArrow()
}

/**
 * 首选形态的两端（§6.2）：左右是水平直线，上下是垂直线，斜向取两框最近点。
 *
 * 起点永远落在标注框朝正文的那条边上，终点落在文字朝标注的那条边上（gap = 0）。
 */
private fun straightArrowEnds(relation: NoteArrowRelation, bubble: Rect, glyph: Rect): List<Offset> =
    when (relation) {
        NoteArrowRelation.RIGHT ->
            listOf(Offset(bubble.left, bubble.center.y), Offset(glyph.right, glyph.center.y))
        NoteArrowRelation.LEFT ->
            listOf(Offset(bubble.right, bubble.center.y), Offset(glyph.left, glyph.center.y))
        NoteArrowRelation.ABOVE -> {
            val endX = bubble.center.x.coerceIn(glyph.left, glyph.right)
            listOf(Offset(bubble.center.x, bubble.bottom), Offset(endX, glyph.top))
        }
        NoteArrowRelation.BELOW -> {
            val endX = bubble.center.x.coerceIn(glyph.left, glyph.right)
            listOf(Offset(bubble.center.x, bubble.top), Offset(endX, glyph.bottom))
        }
        NoteArrowRelation.SIDE -> {
            val start = nearestPointOn(bubble, glyph.center)
            listOf(start, nearestPointOn(glyph, start))
        }
    }

/**
 * 正交折线的候选（§6.2）：[bends] = 1 试两个拐点，2 试横 / 纵各一条折点坐标，
 * 3 试两条折点坐标的四种组合。
 *
 * 折点坐标取自两端与附近障碍的四边（外扩 [ARROW_BEND_CLEARANCE_PX]），按离中点由近到远取前几个，
 * 近的更容易凑出短路径。返回顺序固定，同一输入结果恒定。
 */
internal fun noteElbowRoutes(
    start: Offset,
    end: Offset,
    obstacles: List<Rect> = emptyList(),
    bends: Int = 1,
): List<List<Offset>> {
    if (bends !in 1..ARROW_MAX_BENDS) return emptyList()
    val xs = ArrayList<Float>(obstacles.size + 2)
    val ys = ArrayList<Float>(obstacles.size + 2)
    xs += start.x
    xs += end.x
    ys += start.y
    ys += end.y
    for (r in obstacles) {
        xs += r.left - ARROW_BEND_CLEARANCE_PX
        xs += r.right + ARROW_BEND_CLEARANCE_PX
        ys += r.top - ARROW_BEND_CLEARANCE_PX
        ys += r.bottom + ARROW_BEND_CLEARANCE_PX
    }
    val bendXs = pickCoords(xs, (start.x + end.x) * 0.5f)
    val bendYs = pickCoords(ys, (start.y + end.y) * 0.5f)
    val out = ArrayList<List<Offset>>()
    when (bends) {
        1 -> {
            out += listOf(start, Offset(end.x, start.y), end)
            out += listOf(start, Offset(start.x, end.y), end)
        }
        2 -> {
            for (x in bendXs) out += listOf(start, Offset(x, start.y), Offset(x, end.y), end)
            for (y in bendYs) out += listOf(start, Offset(start.x, y), Offset(end.x, y), end)
        }
        else -> for (x in bendXs) {
            for (y in bendYs) {
                out += listOf(start, Offset(x, start.y), Offset(x, y), Offset(end.x, y), end)
                out += listOf(start, Offset(start.x, y), Offset(x, y), Offset(x, end.y), end)
            }
        }
    }
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
private fun measureArrowRoute(
    route: List<Offset>,
    order: Int,
    obstacles: List<ArrowObstacle>,
    bubble: Rect,
    drawnSegments: List<Pair<Offset, Offset>>,
): ArrowPath? {
    val points = simplifyPolyline(route)
    if (points.size < 2) return null
    var length = 0f
    var hits = 0f
    var crosses = 0
    var enters = false
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
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
        points = points,
        bends = max(0, points.size - 2),
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
 * 旁注的实测尺子：与 [NoteBubbles] 里那条 `Text` 用同一套 Compose 文字测量。
 *
 * 为什么要实测：粗估（[EstimatedNoteTextSizer]）按「中文一个字宽、西文 0.55」累加，
 * 而真实排版还有字距、字形进位的取整。差个一两像素时，摆位按粗估判成「整句放得下」，
 * 画出来却正好差半个字，`Text` 就补一个省略号——读者看到的是「整页空白，批注却折成两个字」。
 * 这里量的是绘制真正用的字号 / 行高 / 基础样式，量出来的宽就是 `Text` 需要的宽。
 */
private class ComposeNoteTextSizer(
    private val measurer: TextMeasurer,
    private val density: Density,
    private val baseStyle: TextStyle,
) : NoteTextSizer {
    override fun naturalWidth(text: String, fontPx: Float): Float {
        if (text.isEmpty() || fontPx <= 0f) return 0f
        // 多段按最长的一段量：`\n` 是强制换行，不是更宽。
        // `size.width` 已是向上取整的整数宽，再留 1px 给绘制侧「框宽 / 内缩各自取整」的损耗。
        return text.split('\n').maxOf { paragraph ->
            if (paragraph.isEmpty()) 0f else measurer.measure(
                text = paragraph,
                style = styleFor(fontPx),
                overflow = TextOverflow.Clip,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(),
            ).size.width.toFloat() + 1f
        }
    }

    override fun fit(text: String, fontPx: Float, widthPx: Float, maxLines: Int): NoteTextFit {
        val total = text.count { it != '\n' }
        if (total == 0) return NoteTextFit(lines = 0, shownChars = 0, totalChars = 0)
        if (fontPx <= 0f || widthPx <= 0f || maxLines < 1) {
            return NoteTextFit(lines = Int.MAX_VALUE, shownChars = 0, totalChars = total)
        }
        val layout = measurer.measure(
            text = text,
            style = styleFor(fontPx),
            overflow = TextOverflow.Ellipsis,
            softWrap = true,
            maxLines = maxLines,
            // 绘制侧拿到的约束是整数像素，这里同样按整数宽度量，抹掉「差半像素」的假放得下。
            constraints = Constraints(maxWidth = widthPx.toInt().coerceAtLeast(1)),
        )
        val end = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
        val shown = if (end <= 0) {
            0
        } else {
            text.take(end.coerceAtMost(text.length)).count { it != '\n' }
        }
        return NoteTextFit(lines = layout.lineCount, shownChars = shown, totalChars = total)
    }

    private fun styleFor(fontPx: Float): TextStyle = baseStyle.merge(
        TextStyle(
            fontSize = with(density) { fontPx.toSp() },
            lineHeight = with(density) { (fontPx * NOTE_BUBBLE_LINE_RATIO).toSp() },
        ),
    )
}

/** 一页取一次实测尺子：同一套字号 / 字体规则下测量结果稳定，不必每条批注重建。 */
@Composable
internal fun rememberNoteTextSizer(): NoteTextSizer {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    return remember(measurer, density, baseStyle) {
        ComposeNoteTextSizer(measurer, density, baseStyle)
    }
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
                // 小椭圆（§1.6）：原文右下角一个 0.5em × 0.32em 的椭圆，点它同样打开批注。
                // 视觉很小，热区单独放大到 1.2em：椭圆居中，透明边距一起接手势；这种形态没有箭头。
                val h = if (b.heightPx > 0f) b.heightPx else b.widthPx * 0.64f
                val hit = max(b.fontPx * NOTE_ICON_HIT_RATIO, max(b.widthPx, h))
                val padLeft = (hit - b.widthPx) / 2f
                val padTop = (hit - h) / 2f
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (b.leftPx - padLeft).roundToInt(),
                                (b.topPx - padTop).roundToInt(),
                            )
                        }
                        .size(with(d) { hit.toDp() })
                        .clickable { onOpen(b.annotationId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(with(d) { b.widthPx.toDp() })
                            .height(with(d) { h.toDp() })
                            .clip(RoundedCornerShape(50))
                            .background(tint.copy(alpha = 0.12f))
                            .border(1.dp, tint.copy(alpha = 0.75f), RoundedCornerShape(50)),
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val dot = size.height * 0.11f
                            val step = size.width * 0.22f
                            for (k in -1..1) {
                                drawCircle(
                                    color = tint.copy(alpha = 0.85f),
                                    radius = dot,
                                    center = Offset(size.width / 2f + k * step, size.height / 2f),
                                )
                            }
                        }
                    }
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
    // 箭头尖的方向：折线 / 直线取最后一段。
    val tail: Offset
    when (arrow) {
        is NoteArrow.Straight -> {
            path.moveTo(arrow.start.x, arrow.start.y)
            path.lineTo(arrow.end.x, arrow.end.y)
            tail = arrow.start
        }
        is NoteArrow.Elbow -> {
            path.moveTo(arrow.points.first().x, arrow.points.first().y)
            for (p in arrow.points.drop(1)) path.lineTo(p.x, p.y)
            tail = arrow.points[arrow.points.size - 2]
        }
    }
    drawPath(path, color = color, style = Stroke(width = stroke))

    // 箭头尖用两小段线，方向取末段。
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
