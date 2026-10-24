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
import orilumn.reader.io.Logger

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
 * 标注与障碍、与另一条标注之间的**最小**距离（以旁注字号为单位）：四周 0.1em。
 *
 * 这是「空间实在不够」时才允许落到的下限；空间够时一律先用
 * [NOTE_PREFERRED_MARGIN_EM]（1em）。障碍按这个下限外扩切空白，候选再按方向
 * 逐级把留白加回去（见 [noteCandidates] 的 margin 档）。
 */
private const val NOTE_MARGIN_X_EM: Float = 0.1f
private const val NOTE_MARGIN_Y_EM: Float = 0.1f

/**
 * 空间足够时，标注与正文 / 障碍之间的**首选**距离（以旁注字号为单位）：四周 1em。
 *
 * 2026-10-24 用户定口：不再只给右侧留 1em，而是上下左右都优先留 1em；空间不足时
 * 先把**远离正文那一侧**收到 0.1em，还不够才换位置；正右 / 正下 / 正上 / 正左
 * 靠近正文那一侧的 1em 必须保留（引线要从这段空档画出去），只有四个斜向摆法
 * 才允许把近侧也收到 0.1em。
 */
private const val NOTE_PREFERRED_MARGIN_EM: Float = 1f

/**
 * 正右 / 正左摆法下，框与所选文字之间的首选距离（以旁注字号为单位）。
 *
 * 默认与 [NOTE_PREFERRED_MARGIN_EM] 相同；保留 [NoteStyle.sideGapEm] 是为了让
 * 「批注样式」将来可以单独调这一段，候选生成统一走这个值。
 */
private const val NOTE_SIDE_GAP_EM: Float = 1f

/** 候选留白的三个档（2026-10-24 用户定口）：先双侧 1em，再远侧 0.1em，斜向最后收近侧。 */
private const val NOTE_MARGIN_TIER_PREFERRED: Int = 0
private const val NOTE_MARGIN_TIER_FAR_MIN: Int = 1
private const val NOTE_MARGIN_TIER_NEAR_MIN: Int = 2

/** 标注离屏幕边的距离：贴边会让框看起来像被裁掉一条。 */
private const val NOTE_PAGE_EDGE_EM: Float = 0.5f

/** 每条批注最多保留多少个候选（按排序取前 N，避免极端页面候选爆炸）。 */
private const val MAX_CANDIDATES_PER_NOTE: Int = 300

/**
 * 引线短到看不清的门槛（以旁注行高为单位）：短竖线改走左侧 L 形。
 *
 * 只取**半行**：1em 的摆放留白（≈0.72 行）已经够看清，不该被这条规则改道；
 * 真机上「只剩几像素」那种才会触发（箭头尖本身就有 5dp 长，短过它整条线就被箭头吃掉）。
 */
private const val NOTE_ARROW_MIN_VISIBLE_LINES: Float = 0.5f

/** 引线与字墨迹贴得太近的判定距离（以旁注行高为单位）：平行段离字太近要让位。 */
private const val ARROW_HUG_LINES: Float = 0.3f

/** 箭头避让逐字墨迹的容差：不到半个像素的贴边不算压字。 */
private const val ARROW_GLYPH_TOL_PX: Float = 0.5f

/** 箭头避让图形 / 表格 / 线条 / 其他旁注框的容差：这几类边界是硬线，多让一点。 */
private const val ARROW_SOLID_TOL_PX: Float = 1f

/** 箭头不许拐回自己的旁注框：只差一根线的量就算「进去」。 */
private const val ARROW_OWN_BUBBLE_TOL_PX: Float = 0.01f

/** 折线折点贴着障碍边取坐标时留的缝（像素）。 */
private const val ARROW_BEND_CLEARANCE_PX: Float = 1f

/** 折线最多四个拐点（5 段，§6.2）：首末段必须各自垂直于框边，绕过整条图形带时要多一个折。 */
private const val ARROW_MAX_BENDS: Int = 4

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

/** 候选框相对锚点的方向（环内的先后由 [noteSideRank] 给出，枚举声明顺序不参与排序）。 */
internal enum class NoteSide {    /** 所选行右侧（锚点末行右侧还剩 1em 以上空白，或该行本身就是段末行）。 */
    RIGHT,

    /** 右下方（相对末行第一个字右移一个汉字之后的正下）。 */
    BELOW_RIGHT,

    /** 右上方（同一个横向基准的上方）。 */
    ABOVE_RIGHT,

    /** 左下方（相对末行末字符左移一个汉字之后的正下）。 */
    BELOW_LEFT,

    /** 左上方（同一个横向基准的上方）。 */
    ABOVE_LEFT,

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
 * 环内的方向名次（2026-10-24 用户定口）：右 → 右下 → 右上 → 左下 → 左上 → 下 → 上 → 左。
 *
 * 这一段只在**折行数、环都相同**时才轮到（§1.1「顺序怎么比」）。正右在锚点末行
 * **右侧还剩 1em 以上空白**时生成，所以两端对齐的段中行自然从右下开始；左对齐的代码行 /
 * 标题行右边本来就是空白区，照样先试正右。段末行（满行时用页边距）照旧生成。
 * 右侧已经被别的批注占住时，正右让到「左」之前（见 [NOTE_SIDE_RANK_TAKEN_RIGHT]）。
 */
private fun noteSideRank(side: NoteSide): Int = when (side) {
    NoteSide.RIGHT -> 0
    NoteSide.BELOW_RIGHT -> 1
    NoteSide.ABOVE_RIGHT -> 2
    NoteSide.BELOW_LEFT -> 3
    NoteSide.ABOVE_LEFT -> 4
    NoteSide.BELOW -> 5
    NoteSide.ABOVE -> 6
    NoteSide.LEFT -> 8
    NoteSide.OTHER -> 9
}

/** 右侧已被占时，正右的名次：让到「左」之前（倒数第二位，§0.1 0b）。 */
private const val NOTE_SIDE_RANK_TAKEN_RIGHT: Int = 7

/**
 * 一条批注的一个候选框（纯几何）。
 *
 * 字段只服务两件事：**排序**（[stage] → [ring] → [lines] → [side] → [distance]）与**落位**
 * （[rect] 是最终框）。同一圈里**不折行优先、行数越少越优先**：先把这句话当一行放，
 * 放不下才两行、三行……行数相同再比方向与距离。
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
    /**
     * 这个候选用哪一档留白（2026-10-24 用户定口）：
     * [NOTE_MARGIN_TIER_PREFERRED] = 四周 1em；[NOTE_MARGIN_TIER_FAR_MIN] = 远侧收 0.1em；
     * [NOTE_MARGIN_TIER_NEAR_MIN] = 斜向摆法连近侧也收 0.1em。
     *
     * 排序时它排在方向名次之后：同一个方向上，空间够就先要 1em，不够才要细缝。
     */
    val marginTier: Int = NOTE_MARGIN_TIER_PREFERRED,
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
 * @property sideGapEm 引线只能从框左 / 右侧横着出去时，与文字的距离 / 字号（留给箭头）。
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

    /**
     * 空间足够时四周的首选留白（1em）：候选先按它取位，不够才退到 [marginX] / [marginY]。
     */
    val preferredMargin: Float get() = em * NOTE_PREFERRED_MARGIN_EM

    /** 引线只能从框左 / 右侧横着出去时与文字的距离（留给箭头）。 */
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
 * → 方向（右 / 右下 / 右上 / 左下 / 左上 / 下 / 上 / 左）。所有位置都**贴住锚点**：
 *
 * - **正右看留白，不只看段末**（2026-10-24 用户定口）：锚点末行右侧离版心右缘还有
 *   **1em 以上空白**就先试正右——代码行 / 标题行这类左对齐的短行右边本来就是空白区，
 *   段中行也一样；整行顶到版心右缘的行只在它是段末行时才试（用页边距那一列）。
 * - **右下 / 右上** 的横向基准是**末行第一个字向右一个汉字**；**左下 / 左上** 是
 *   **末行末字符向左一个汉字**。它们都不再贴整个选区的左右缘（§0.2）。
 * - 留白分三档（[NOTE_MARGIN_TIER_PREFERRED] / [NOTE_MARGIN_TIER_FAR_MIN] /
 *   [NOTE_MARGIN_TIER_NEAR_MIN]）：空间够时四周 1em；不够先把**远离正文那一侧**
 *   收到 0.1em；正右 / 正下 / 正上 / 正左靠近正文那一侧的 1em 必须保留（引线要从这里
 *   画出去），只有四个斜向摆法才允许把近侧也收到 0.1em。
 *
 * 可用宽高从「包含该点的极大空闲矩形」里扣：空白已按**最小**留白（0.1em）切好，
 * 候选再按档位把首选留白补回去。
 *
 * @param otherRowLeft 同一页上**其他批注**在每一行的最左墨迹（行序 → x）。同一行有多条批注时，
 *   落在该行上方 / 下方的框要给左侧那条留出引线空档（见下方 `leftReserve`）。
 * @param contentRight **版心右缘**（页坐标）：锚点末行在这条线左边还留着 1em 以上空白时，
 *   正右才作为第一优先级（2026-10-24 用户定口）。默认无上限 = 右侧永远算有空白。
 */
internal fun noteCandidates(
    note: NoteInk,
    lines: List<NoteTextLine>,
    freeRects: List<Rect>,
    metrics: NoteMetrics,
    maxCandidates: Int = MAX_CANDIDATES_PER_NOTE,
    sizer: NoteTextSizer = EstimatedNoteTextSizer,
    charWidthPx: Float = metrics.em / NOTE_BUBBLE_FONT_RATIO,
    otherRowLeft: Map<Int, Float> = emptyMap(),
    contentRight: Float = Float.MAX_VALUE,
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
    val baseRow = lines[base]
    val firstRow = lines[first]
    val baseBlock = baseRow.blockId
    var blockStart = base
    while (blockStart > 0 && lines[blockStart - 1].blockId == baseBlock) blockStart--
    var blockEnd = base
    while (blockEnd + 1 < lines.size && lines[blockEnd + 1].blockId == baseBlock) blockEnd++

    val marginX = metrics.marginX
    val marginY = metrics.marginY
    val preferred = metrics.preferredMargin
    val charW = max(charWidthPx, 1f)
    val baseGlyphs = note.rects
        .filter { nearestLineIndex(it, lines) == base }
        .sortedBy { it.left }
    val firstGlyph = baseGlyphs.firstOrNull() ?: note.rects.minByOrNull { it.left } ?: return emptyList()
    val lastGlyph = baseGlyphs.lastOrNull() ?: note.rects.maxByOrNull { it.right } ?: return emptyList()
    // 「首字符右移一个汉字」/「末字符左移一个汉字」：斜向摆法的横向基准（§0.2）。
    val cornerRightX = firstGlyph.left + charW
    val cornerLeftX = lastGlyph.right - charW
    val out = ArrayList<NoteCandidate>()
    val seen = HashSet<String>()

    /**
     * 这一行左侧那条批注的**引线空档**（2026-10-24 用户定口）：
     *
     * 同一行有多条批注时，框落在这一行**上方 / 下方**（右下 / 右上 / 正下 / 正上）时，
     * 左缘不得越过**左侧那条批注第二个字的左缘**——也就是它第一个字的下方（或上方）必须空着，
     * 好让左侧那条的引线从那一格里接进首字。左侧那条不在这一行时不设限。
     *
     * @return 框左缘的下界；`null` = 这一行没有更靠左的批注，照常取位。
     */
    fun leftReserve(rowIndex: Int): Float? {
        val otherLeft = otherRowLeft[rowIndex] ?: return null
        // 第二个字的左缘 = 首字左缘 + 一个汉字；只有它仍在本条起点左边时才需要留空。
        val second = otherLeft + charW
        return if (second <= firstGlyph.left + NOTE_GEOM_EPS) second else null
    }

    /** 远侧留白：首选档要补到 1em，其余档直接用空白已有的 0.1em。 */
    fun farExtra(axisMin: Float, tier: Int): Float =
        if (tier == NOTE_MARGIN_TIER_PREFERRED) max(0f, preferred - axisMin) else 0f

    /** 斜向摆法的近侧留白：首选 / 远侧档仍是 1em，最后一档才收到 0.1em。 */
    fun diagonalNear(axisMin: Float, tier: Int): Float =
        if (tier == NOTE_MARGIN_TIER_NEAR_MIN) axisMin else preferred

    /**
     * 右 / 左：返回可用的空白列（最多两个：最宽的、最高的）。
     *
     * 只取一个矩形会漏掉「窄但高」的空白：最宽的那块常常只有一行高，框被高度逼成单行后
     * 明明旁边还有能折行的竖条，却只能打省略号。两个都生成，由分配阶段按「整句优先」挑。
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
     * 同样不能只取一个：贴边的一线薄空白进深不够折行，旁边稍远但更深的空白能放下整句时
     * 应该让后者参与竞争（整句优先），而不是先被薄空白截断。
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
        val usable = band.filter { depth(it) >= metrics.lineH - marginY - NOTE_GEOM_EPS }
        val pool = usable.ifEmpty { band }
        val nearest = pool.minWithOrNull(compareBy({ dx(it) }, { -depth(it) }, { -it.width }))
        val deepest = pool.maxWithOrNull(compareBy({ depth(it) }, { -dx(it) }, { it.width }))
        return listOfNotNull(nearest, deepest).distinct()
    }

    fun add(
        side: NoteSide,
        ring: Int,
        stage: Int,
        tier: Int,
        f: Rect,
        left: Float,
        top: Float,
        size: NoteBoxSize,
    ) {
        if (size.width <= 0f || size.height <= 0f) return
        if (left < f.left - NOTE_GEOM_EPS || left + size.width > f.right + NOTE_GEOM_EPS ||
            top < f.top - NOTE_GEOM_EPS || top + size.height > f.bottom + NOTE_GEOM_EPS
        ) {
            return
        }
        val rect = Rect(left, top, left + size.width, top + size.height)
        // 同一块空白被不同极大矩形重复切出时，落位可能完全一样：只留一个。
        val key = "${side.ordinal}/$tier/${rect.left.roundToInt()}/${rect.top.roundToInt()}/" +
            "${rect.right.roundToInt()}/${rect.bottom.roundToInt()}"
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
            marginTier = tier,
        )
    }

    /**
     * 正右：近侧固定 1em（水平引线要从这段空档出去），远侧按档位。
     *
     * 上下两条边不是「与正文的间距」，沿用空白自带的 0.1em 最小缝即可——
     * 「1em 无论上下左右」说的是**摆放方向那一侧**离正文的间距。
     */
    fun emitRight(f: Rect, row: NoteTextLine, tier: Int) {
        val x = row.rect.right + metrics.sideGap
        val far = farExtra(marginX, tier)
        val left = max(x, f.left)
        val maxW = f.right - far - left
        val maxH = f.height
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        val lo = f.top
        val hi = f.bottom - size.height
        if (hi < lo - NOTE_GEOM_EPS) return
        val top = (row.rect.center.y - size.height / 2f).coerceIn(lo, max(lo, hi))
        add(NoteSide.RIGHT, 0, 0, tier, f, left, top, size)
    }

    /** 正左：与正右对称。 */
    fun emitLeft(f: Rect, row: NoteTextLine, ring: Int, stage: Int, tier: Int) {
        val x = row.rect.left - metrics.sideGap
        val far = farExtra(marginX, tier)
        val right = min(x, f.right)
        val maxW = right - f.left - far
        val maxH = f.height
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        val lo = f.top
        val hi = f.bottom - size.height
        if (hi < lo - NOTE_GEOM_EPS) return
        val top = (row.rect.center.y - size.height / 2f).coerceIn(lo, max(lo, hi))
        add(NoteSide.LEFT, ring, stage, tier, f, right - size.width, top, size)
    }

    /** 正下：近侧固定 1em（竖直引线要从这段空档出去），下方按档位。 */
    fun emitBelow(f: Rect, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        val y = row.rect.bottom + preferred
        val far = farExtra(marginY, tier)
        val top = max(y, f.top)
        val maxH = f.bottom - far - top
        val lo = max(f.left, reserveX ?: f.left)
        val maxW = f.right - lo
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        val hi = f.right - size.width
        if (hi < lo - NOTE_GEOM_EPS) return
        val left = anchor.left.coerceIn(lo, max(lo, hi))
        add(NoteSide.BELOW, ring, stage, tier, f, left, top, size)
    }

    /** 正上：与正下对称。 */
    fun emitAbove(f: Rect, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        val y = row.rect.top - preferred
        val far = farExtra(marginY, tier)
        val bottom = min(y, f.bottom)
        val maxH = bottom - f.top - far
        val lo = max(f.left, reserveX ?: f.left)
        val maxW = f.right - lo
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        val hi = f.right - size.width
        if (hi < lo - NOTE_GEOM_EPS) return
        val left = anchor.left.coerceIn(lo, max(lo, hi))
        add(NoteSide.ABOVE, ring, stage, tier, f, left, bottom - size.height, size)
    }

    /** 右下：左缘在「末行第一个字 + 一个汉字」右侧，框顶在该行下方。 */
    fun emitBelowRight(f: Rect, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        val nearX = diagonalNear(marginX, tier)
        val nearY = diagonalNear(marginY, tier)
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val left = max(max(cornerRightX + nearX, f.left), reserveX ?: f.left)
        val top = max(row.rect.bottom + nearY, f.top)
        val maxW = f.right - farX - left
        val maxH = f.bottom - farY - top
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        add(NoteSide.BELOW_RIGHT, ring, stage, tier, f, left, top, size)
    }

    /** 左下：右缘在「末行末字符 - 一个汉字」左侧，框顶在该行下方。 */
    fun emitBelowLeft(f: Rect, row: NoteTextLine, ring: Int, stage: Int, tier: Int) {
        val nearX = diagonalNear(marginX, tier)
        val nearY = diagonalNear(marginY, tier)
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val right = min(cornerLeftX - nearX, f.right)
        val top = max(row.rect.bottom + nearY, f.top)
        val maxW = right - f.left - farX
        val maxH = f.bottom - farY - top
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        add(NoteSide.BELOW_LEFT, ring, stage, tier, f, right - size.width, top, size)
    }

    /** 右上：横向基准与右下相同，框底在该行上方。 */
    fun emitAboveRight(f: Rect, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        val nearX = diagonalNear(marginX, tier)
        val nearY = diagonalNear(marginY, tier)
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val left = max(max(cornerRightX + nearX, f.left), reserveX ?: f.left)
        val bottom = min(row.rect.top - nearY, f.bottom)
        val maxW = f.right - farX - left
        val maxH = bottom - f.top - farY
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        add(NoteSide.ABOVE_RIGHT, ring, stage, tier, f, left, bottom - size.height, size)
    }

    /** 左上：横向基准与左下相同，框底在该行上方。 */
    fun emitAboveLeft(f: Rect, row: NoteTextLine, ring: Int, stage: Int, tier: Int) {
        val nearX = diagonalNear(marginX, tier)
        val nearY = diagonalNear(marginY, tier)
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val right = min(cornerLeftX - nearX, f.right)
        val bottom = min(row.rect.top - nearY, f.bottom)
        val maxW = right - f.left - farX
        val maxH = bottom - f.top - farY
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer)
        if (!size.readable) return
        add(NoteSide.ABOVE_LEFT, ring, stage, tier, f, right - size.width, bottom - size.height, size)
    }

    fun emitBelowFamily(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        // 同一行左侧还有批注时，框左缘要让过它首字那一格（给它留引线空档）。
        val reserveX = leftReserve(rowIndex)
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in bandRects(row.rect.bottom + preferred, anchor.left, below = true)) {
                emitBelow(f, row, ring, stage, tier, reserveX)
            }
        }
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_NEAR_MIN) {
            val near = diagonalNear(marginX, tier)
            for (f in bandRects(row.rect.bottom + diagonalNear(marginY, tier), cornerRightX + near, below = true)) {
                emitBelowRight(f, row, ring, stage, tier, reserveX)
            }
            for (f in bandRects(row.rect.bottom + diagonalNear(marginY, tier), cornerLeftX - near, below = true)) {
                emitBelowLeft(f, row, ring, stage, tier)
            }
        }
    }

    fun emitAboveFamily(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val reserveX = leftReserve(rowIndex)
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in bandRects(row.rect.top - preferred, anchor.left, below = false)) {
                emitAbove(f, row, ring, stage, tier, reserveX)
            }
        }
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_NEAR_MIN) {
            val near = diagonalNear(marginX, tier)
            for (f in bandRects(row.rect.top - diagonalNear(marginY, tier), cornerRightX + near, below = false)) {
                emitAboveRight(f, row, ring, stage, tier, reserveX)
            }
            for (f in bandRects(row.rect.top - diagonalNear(marginY, tier), cornerLeftX - near, below = false)) {
                emitAboveLeft(f, row, ring, stage, tier)
            }
        }
    }

    fun emitLeftFamily(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in columnRects(row.rect.left - metrics.sideGap, row.rect, toRight = false)) {
                emitLeft(f, row, ring, stage, tier)
            }
        }
    }

    // 第 0 环：锚点末行的右侧。2026-10-24 用户定口：**不再看是不是段末行**——只要这一行
    // 右侧离版心右缘还有 1em 以上空白，正右就是第一优先级（代码行 / 标题行这类左对齐的短行
    // 右边本来就是空白区，段中行也一样）。整行顶到版心右缘的行只保留原来的「段末行」入口：
    // 段落还有下一行时不试这一行的正右。
    val rightBlank = contentRight - baseRow.rect.right
    if (rightBlank >= preferred || base == blockEnd) {
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in columnRects(baseRow.rect.right + metrics.sideGap, baseRow.rect, toRight = true)) {
                emitRight(f, baseRow, tier)
            }
        }
    }
    emitBelowFamily(base, 0, 0)
    emitAboveFamily(first, 0, 0)

    // 环 1…n：本段内由近到远（往下一条、上一条地数）。
    var k = 1
    while (true) {
        var any = false
        if (base + k <= blockEnd) {
            emitBelowFamily(base + k, k, 0)
            any = true
        }
        if (first - k >= blockStart) {
            emitAboveFamily(first - k, k, 0)
            any = true
        }
        if (!any) break
        k++
    }

    // 本段其余位置：左侧页边距最后才允许。
    for (idx in blockStart..blockEnd) emitLeftFamily(idx, abs(idx - base), 1)

    // 相邻段：继续往外，仍由近到远；左侧同样排在最后。
    for (idx in blockEnd + 1 until lines.size) emitBelowFamily(idx, idx - base, 2)
    for (idx in blockStart - 1 downTo 0) emitAboveFamily(idx, first - idx, 2)
    for (idx in 0 until lines.size) {
        if (idx < blockStart || idx > blockEnd) emitLeftFamily(idx, abs(idx - base), 3)
    }

    return orderedNoteCandidates(out, rightTaken = false).take(maxCandidates)
}

/**
 * 一条批注的候选链顺序（§1.1）：**字典序**比较
 * 「段 → 折行数 → 环 → 方向 → 留白档 → 距离」。
 *
 * **折行数在环之前**（2026-10-24 用户定口）：**不折行优先、行数越少越优先**是段内的第一判据，
 * 折行更少的候选压过更近但折行更多的候选——宁可隔一行一行放下，也不贴着本行折三四行，
 * 更不能因为右侧页边距那一列「够高、塞得下整句」就把批注压成每行两三个字的竖条。
 * 折行数相同才比环（本行 → 隔 1 行 → 隔 2 行……），再比方向
 * （右 → 右下 → 右上 → 左下 → 左上 → 下 → 上 → 左，2026-10-24 用户定口）。
 *
 * **留白档在方向之后**（2026-10-24 用户定口）：同一个方向上，空间够就先要 1em，
 * 不够才把远侧收到 0.1em（斜向最后才收近侧）；换方向仍然排在「同方向先试留白」之后。
 *
 * @param rightTaken 该行右侧已经被别的旁注占住：正右让到「左」之前
 *   （倒数第二位，§0.1 0b），不再为了「更靠右」把框推远。
 */
internal fun orderedNoteCandidates(
    all: List<NoteCandidate>,
    rightTaken: Boolean,
): List<NoteCandidate> {
    // 右侧没被占时 RIGHT 就是 0 号方向，和枚举顺序无关；被占时挪到 LEFT 之前。
    fun rank(side: NoteSide): Int =
        if (rightTaken && side == NoteSide.RIGHT) NOTE_SIDE_RANK_TAKEN_RIGHT else noteSideRank(side)
    return all.sortedWith(
        compareBy<NoteCandidate> { it.stage }
            // 不折行优先、行数越少越优先：先比折行数，再比远近（环）。
            .thenBy { it.lines }
            .thenBy { it.ring }
            .thenBy { rank(it.side) }
            .thenBy { it.marginTier }
            .thenBy { it.distance }
            .thenBy { it.rect.top }
            .thenBy { it.rect.left },
    )
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
 * 3. [noteCandidates] 按「段 → 环 → 行数 → 方向 → 距离」的阶梯为每条批注生成候选框（§1.1）；
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

    // 同一行有多条批注时，落在这一行上方 / 下方的框要让过**左侧那条**首字那一格
    // （2026-10-24 用户定口：第一个字的下方 / 上方要空着，好让它的引线接进去）。
    // 这里先按行算出「每条批注在这一行的最左墨迹」，再给每条批注汇总「其他批注」的那张表。
    val rowLefts = ordered.associate { n ->
        val perRow = HashMap<Int, Float>()
        for (r in n.rects) {
            val row = nearestLineIndex(r, lines) ?: continue
            perRow[row] = min(perRow[row] ?: Float.MAX_VALUE, r.left)
        }
        n.annotationId to perRow
    }
    val otherRowLefts = ordered.associate { n ->
        val merged = HashMap<Int, Float>()
        for ((id, perRow) in rowLefts) {
            if (id == n.annotationId) continue
            for ((row, left) in perRow) merged[row] = min(merged[row] ?: Float.MAX_VALUE, left)
        }
        n.annotationId to merged
    }

    val candidatesByNote = HashMap<Long, List<NoteCandidate>>(ordered.size)
    for (note in ordered) {
        val list = noteCandidates(
            note = note,
            lines = lines,
            freeRects = freeRects,
            metrics = metrics,
            sizer = noteTextSizer,
            // 「一个汉字」按正文字号算：斜向摆法要据此把框挪开首 / 末字符。
            charWidthPx = body,
            otherRowLeft = otherRowLefts.getValue(note.annotationId),
            // 版心右缘：判断「这一行右侧还有没有 1em 以上空白」，决定正右是否第一优先级。
            contentRight = contentRight,
        )
        if (list.isNotEmpty()) candidatesByNote[note.annotationId] = list
    }

    // 三档推进（整句 → 截断 → 小椭圆）：同一行从右到左逐条独立取位，
    // 整句档全部放不下时才让既有批注上下让位（§1.4 / §5）。
    val choices = assignNoteCandidates(
        ordered, lines, candidatesByNote, marginX, marginY,
    )
    val chosen = choices.associateBy { it.note.annotationId }
    traceNotePlacement(ordered, lines, freeRects, candidatesByNote, chosen, marginY)
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
            side = chosen[b.annotationId]?.candidate?.side,
        )
        if (arrow != null) {
            drawn += arrow
            // TEMP-TRACE 引线形态诊断（定位右下角「贴着左框拉竖线」用，定位完即删）。
            Logger.d(
                "Orilumn.NOTE",
                "  arrow id=${b.annotationId} side=${chosen[b.annotationId]?.candidate?.side} " +
                    "box=${self.left.roundToInt()},${self.top.roundToInt()}-${self.right.roundToInt()},${self.bottom.roundToInt()} $arrow",
            )
        }
        b.copy(arrow = arrow)
    }
}

/** TEMP-TRACE 上一次记录的布点结果：同一版面重复重排时不再重复落盘。 */
private var noteTraceKey: String = ""

/**
 * TEMP-TRACE 旁注布点诊断（定位「讨论」批注折 14 行时加的临时日志，定位完即删）。
 *
 * 落盘内容：每条批注的锚点、基准行、全部候选（方向 / 环 / 行数 / 框）、最终选中，
 * 以及基准行下方那一带的空白矩形（用来判断「正下」为什么没有候选）。
 */
private fun traceNotePlacement(
    ordered: List<NoteInk>,
    lines: List<NoteTextLine>,
    freeRects: List<Rect>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    chosen: Map<Long, NoteChoice>,
    marginY: Float,
) {
    val key = ordered.joinToString("|") { n ->
        val c = chosen[n.annotationId]?.candidate
        "${n.annotationId}@${c?.rect?.left?.roundToInt()},${c?.rect?.top?.roundToInt()},${c?.lines}"
    }
    if (key == noteTraceKey) return
    noteTraceKey = key
    for (note in ordered) {
        val anchor = rectUnion(note.rects)
        val baseRow = noteBaseRow(note.rects, lines)
        Logger.d(
            "Orilumn.NOTE",
            "note=${note.annotationId} text=${note.text.take(10)} anchor=${anchor.left.roundToInt()},${anchor.top.roundToInt()}-${anchor.right.roundToInt()},${anchor.bottom.roundToInt()} baseRow=$baseRow rows=${noteRowsBottomUp(note.rects, lines)} lines=${lines.size}",
        )
        val all = candidatesByNote[note.annotationId].orEmpty()
        for (c in all.take(60)) {
            Logger.d(
                "Orilumn.NOTE",
                "  cand side=${c.side} ring=${c.ring} stage=${c.stage} lines=${c.lines} cmp=${c.complete} " +
                    "rect=${c.rect.left.roundToInt()},${c.rect.top.roundToInt()}-${c.rect.right.roundToInt()},${c.rect.bottom.roundToInt()} " +
                    "free=${c.freeRect.left.roundToInt()},${c.freeRect.top.roundToInt()}-${c.freeRect.right.roundToInt()},${c.freeRect.bottom.roundToInt()}",
            )
        }
        val pick = chosen[note.annotationId]?.candidate
        Logger.d(
            "Orilumn.NOTE",
            "  pick=${pick?.let { "side=${it.side} ring=${it.ring} lines=${it.lines} rect=${it.rect.left.roundToInt()},${it.rect.top.roundToInt()}-${it.rect.right.roundToInt()},${it.rect.bottom.roundToInt()}" } ?: "none"}",
        )
        if (baseRow != null && baseRow in lines.indices) {
            val row = lines[baseRow].rect
            val y = row.bottom + marginY
            val near = freeRects.filter { abs(it.top - y) < 40f || (y >= it.top && y <= it.bottom) }
            Logger.d(
                "Orilumn.NOTE",
                "  band y=${y.roundToInt()} rowY=${row.top.roundToInt()}-${row.bottom.roundToInt()} rowX=${row.left.roundToInt()}-${row.right.roundToInt()} n=${near.size}",
            )
            for (f in near.take(12)) {
                Logger.d(
                    "Orilumn.NOTE",
                    "    free=${f.left.roundToInt()},${f.top.roundToInt()}-${f.right.roundToInt()},${f.bottom.roundToInt()} depth=${(f.bottom - y).roundToInt()}",
                )
            }
        }
    }
}

/** 全页分配的结果：这条批注最终用哪个候选框。 */
private data class NoteChoice(
    val note: NoteInk,
    val candidate: NoteCandidate,
)

/**
 * 全页分配（方案 §1.4 / §5）：**整句 → 截断 → 小椭圆**三档推进。
 *
 * 先挑顺序（2026-10-24 用户定口，唯一一套）：
 * **正文行自上而下 → 同一行内从右到左 → 批注 id**。
 * 「同一行」按**基准行**算——多行选区取所选文字的**末行**（§0.1），不是首行；
 * 从右到左指**先算最右那条**的位置，右边的批注先挑，左边的只能跳过。
 *
 * 每一条批注**只按自己的阶梯独立取位**（2026-10-24 用户定口：取消同行成组 / 统一收列）：
 * 不再把同一行的批注搬到右侧同一列，也不做横向滑移或「躲到旁边重新折行」。
 *
 * **垂直让位是最后的相邻手段**（2026-10-24 用户定口）：这条批注的整句候选全部试完仍放不下，
 * 才逐个候选去问「占住同一块空白的既有批注能不能上下让一让」。同一行右侧的既有批注、
 * 以及别的行用同一块空白的既有批注都可以让；只移动**刚好够放下新框**的距离，
 * 让完仍越界 / 撞上别的批注 / 仍然放不下新框，就**不让**，保留原位置。
 *
 * 已选框立即成为后选框的障碍（四周 0.1em），先挑者不会被无故挤走；不做两两互换，顺序可复现。
 */
private fun assignNoteCandidates(
    notes: List<NoteInk>,
    lines: List<NoteTextLine>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    marginX: Float,
    marginY: Float,
): List<NoteChoice> {
    if (notes.isEmpty() || lines.isEmpty()) return emptyList()
    val chosen = LinkedHashMap<Long, NoteCandidate>()
    val occupied = ArrayList<NoteChoice>()

    fun fits(c: NoteCandidate): Boolean =
        occupied.none { c.conflicts(it.candidate, marginX, marginY) }

    fun take(note: NoteInk, c: NoteCandidate): Boolean {
        if (!fits(c)) return false
        chosen[note.annotationId] = c
        occupied += NoteChoice(note, c)
        return true
    }

    fun baseRow(note: NoteInk): Int =
        noteBaseRow(note.rects, lines) ?: lines.size

    /**
     * 这条批注所在行的**「正右」是不是已经有标注**（§0.1「右已被占，正右让到左之前」）。
     *
     * 判据取它自己最靠近正文那块右侧空白：已经被放好的框占住（`conflicts`）就算被占——
     * 不再为它去挤更靠右的窄列，而是先试右下 / 右上 / 左下 / 左上 / 下 / 上 / 左。
     */
    fun rightTaken(note: NoteInk): Boolean {
        val rightCands = candidatesByNote[note.annotationId].orEmpty()
            .filter { it.side == NoteSide.RIGHT && it.ring == 0 }
        if (rightCands.isEmpty()) return false
        return rightCands.any { rc -> occupied.any { rc.conflicts(it.candidate, marginX, marginY) } }
    }

    /** 按「段 → 折行数 → 环 → 方向 → 留白档 → 距离」取这条批注的候选链。 */
    fun chainFor(note: NoteInk): List<NoteCandidate> =
        orderedNoteCandidates(candidatesByNote[note.annotationId].orEmpty(), rightTaken(note))

    /** 两块空白是不是同一块 / 同一列（只要有一点重叠就算，跨行批注也认同一列）。 */
    fun sharedBlank(a: Rect, b: Rect): Boolean =
        min(a.right, b.right) - max(a.left, b.left) > 1f &&
            min(a.bottom, b.bottom) - max(a.top, b.top) > 1f

    /**
     * 让占住同一块空白的既有批注**上下移动**，给 [c] 腾出位置。
     *
     * - 只认整句档（`c.complete`）：不为了一条要被截断的批注去搬动别人。
     * - 只移动刚好够的距离：上移要求既有框的底边让到 `c.top - marginY` 之上，
     *   下移要求顶边让到 `c.bottom + marginY` 之下，取所有挡路框里需要的最大位移。
     * - 移完必须仍在它自己的空白里、彼此不撞、也不撞没让位的批注；任一条不满足就不让。
     * - 上 / 下两种都可行时取位移小的（一样大时取上移），保证同一输入结果恒定。
     *
     * @return 让位成功并放下 [c] 时为 `true`；让不出空间或让完仍放不下则为 `false`（不改任何位置）。
     */
    fun makeRoom(note: NoteInk, c: NoteCandidate): Boolean {
        if (!c.complete) return false
        val blockers = occupied.filter {
            c.conflicts(it.candidate, marginX, marginY) &&
                sharedBlank(c.freeRect, it.candidate.freeRect)
        }
        if (blockers.isEmpty()) return false
        val blockerIds = blockers.map { it.note.annotationId }.toSet()

        fun attempt(up: Boolean): Pair<Float, List<NoteChoice>>? {
            var delta = 0f
            for (b in blockers) {
                val d = if (up) {
                    b.candidate.rect.bottom - (c.rect.top - marginY)
                } else {
                    (c.rect.bottom + marginY) - b.candidate.rect.top
                }
                if (d > delta) delta = d
            }
            if (delta <= NOTE_GEOM_EPS) return null
            val moved = blockers.map { b ->
                val r = b.candidate.rect
                val rect = if (up) {
                    Rect(r.left, r.top - delta, r.right, r.bottom - delta)
                } else {
                    Rect(r.left, r.top + delta, r.right, r.bottom + delta)
                }
                val cand = b.candidate.copy(
                    rect = rect,
                    distance = b.note.rects.minOf { rectDistance(rect, it) },
                )
                NoteChoice(b.note, cand)
            }
            // 移动后的框必须仍在它自己的空白里（空白已含 0.1em 最小留白）。
            for (m in moved) {
                val f = m.candidate.freeRect
                val r = m.candidate.rect
                if (r.left < f.left - NOTE_GEOM_EPS || r.right > f.right + NOTE_GEOM_EPS ||
                    r.top < f.top - NOTE_GEOM_EPS || r.bottom > f.bottom + NOTE_GEOM_EPS
                ) {
                    return null
                }
            }
            // 移完彼此不撞、不撞没让位的批注、也不撞新框。
            for (i in moved.indices) {
                if (moved[i].candidate.conflicts(c, marginX, marginY)) return null
                for (j in i + 1 until moved.size) {
                    if (moved[i].candidate.conflicts(moved[j].candidate, marginX, marginY)) return null
                }
                for (o in occupied) {
                    if (o.note.annotationId in blockerIds) continue
                    if (moved[i].candidate.conflicts(o.candidate, marginX, marginY)) return null
                }
            }
            return delta to moved
        }

        val up = attempt(up = true)
        val down = attempt(up = false)
        val pick = listOfNotNull(up, down)
            .minWithOrNull(compareBy({ it.first }, { if (it === up) 0 else 1 }))
            ?: return false
        // 让位只解决「挡路的那几条」：新框还撞着没让位的批注就不算成功，**先判后动**，
        // 免得让位失败时把已放批注挪到半路。
        if (occupied.any {
                it.note.annotationId !in blockerIds && c.conflicts(it.candidate, marginX, marginY)
            }
        ) {
            return false
        }
        for (m in pick.second) {
            chosen[m.note.annotationId] = m.candidate
            val idx = occupied.indexOfFirst { it.note.annotationId == m.note.annotationId }
            if (idx >= 0) occupied[idx] = m
        }
        chosen[note.annotationId] = c
        occupied += NoteChoice(note, c)
        return true
    }

    // 先挑顺序（§5.1）：正文行自上而下 → 同行从右到左 → 批注 id。
    val order = notes.sortedWith(
        compareBy<NoteInk> { baseRow(it) }
            .thenByDescending { noteRightOnRow(it.rects, lines, baseRow(it)) }
            .thenBy { it.annotationId },
    )

    // 第 1 档：整句。逐行走（自上而下），行内从右到左，每条按自己的阶梯独立取位；
    // 整句候选全部放不下时，才试着让既有批注上下让一让。
    var start = 0
    while (start < order.size) {
        val rowIndex = baseRow(order[start])
        var end = start + 1
        while (end < order.size && baseRow(order[end]) == rowIndex) end++
        val members = order.subList(start, end)
        for (note in members) {
            val chain = chainFor(note).filter { it.complete }
            if (chain.any { take(note, it) }) continue
            for (c in chain) {
                if (makeRoom(note, c)) break
            }
        }
        start = end
    }
    // 第 2 档：截断（至少 4 字，候选里已保证 readable），同一顺序再走一遍；不再搬动别人。
    for (note in order) {
        if (chosen.containsKey(note.annotationId)) continue
        chainFor(note).firstOrNull { take(note, it) }
    }
    return order.mapNotNull { note -> chosen[note.annotationId]?.let { NoteChoice(note, it) } }
}

/**
 * 一条批注的**基准行**：所选文字在本页的**最后一行**（§0.1「多行选区以末行为基准行」）。
 *
 * 同一行成组、同行从右到左、行自上而下都按它排序。旧实现拿整段墨迹并集的最近行，
 * 多行选区会被算成**首行**，于是同一条末行上的几条批注被拆进不同组、位置互相打架。
 */
private fun noteBaseRow(rects: List<Rect>, lines: List<NoteTextLine>): Int? =
    rects.mapNotNull { nearestLineIndex(it, lines) }.maxOrNull()

/**
 * 批注墨迹落在 [rowIndex] 这一行上的**最右缘**：同一行按从右到左排序时的键。
 *
 * 多行选区只看末行那一段，不看整段并集——首行很长、末行很短时，并集右缘会把它排到
 * 实际更靠右的批注前面，先挑顺序就乱了。
 */
private fun noteRightOnRow(rects: List<Rect>, lines: List<NoteTextLine>, rowIndex: Int): Float {
    val onRow = rects.filter { nearestLineIndex(it, lines) == rowIndex }
    return (onRow.ifEmpty { rects }).maxOf { it.right }
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
    /** 平行段离字墨迹太近的累计量（越小越好）：见 [measureArrowRoute]。 */
    val hug: Float,
    val entersBubble: Boolean,
    val order: Int,
) {
    val clear: Boolean get() = hits <= 1e-4f && !entersBubble

    /**
     * 通畅路径的择优顺序（§6.3，2026-10-24 用户再定口）：
     * **折数少（能直线就直线）→ 不贴着字（[hug]）→ 已画引线交叉少 → 更短 → 候选顺序**。
     *
     * 读者原话：「引线就近就短原则要加前提：引线不能太靠近文字；从两行文字之间横穿时，
     * 应尽可能从中间位置穿过」。所以 [hug] 排在「短」之前——宁可多走几个像素，
     * 也不要贴着字的顶边 / 底边划过去。交叉仍只是美观上的软约束。
     */
    fun betterThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (bends != other.bends) return bends < other.bends
        if (abs(hug - other.hug) > 0.5f) return hug < other.hug
        if (crosses != other.crosses) return crosses < other.crosses
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

/**
 * 一个目标字（锚点在本页的一个字）对应的搜索材料：它的图形、附近障碍、以及
 * **相邻两行墨迹之间的垂直中线**（横穿两行之间时从正中间过，2026-10-24 用户定口）。
 */
private class ArrowTarget(
    val glyph: Rect,
    val obstacles: List<ArrowObstacle>,
    val gapYs: List<Float> = emptyList(),
)

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
 * **线型只有正交线**（2026-10-24 用户定稿）：横线、竖线、折线，**不画斜线、也不画弧线**。
 * 两端必须垂直：
 * - 第一段 ⊥ 标注框边：从左边 / 右边出去是横线，从上边 / 下边出去是竖线；
 * - 最后一段 ⊥ 文字框边：从文字下面进来是竖线，从文字右边进来是横线。
 *
 * 正右 / 正左是水平直线（标注左边 / 右边中点 → 文字右 / 左边中点），
 * 正下 / 正上是从朝文字那条边出去的垂直直线；右下 / 右上从**左边**横着出去、再竖回文字，
 * 所以这两种本来就不需要为箭头留空档。
 *
 * 直线被字挡住时**只用正交折线**（一折 → 两折 → 三折，最多 4 段）。
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
 * @param side 这条批注的布点方向（§1.1）：决定引线从框的哪条边出去（正对时走法线，
 *   `BELOW_RIGHT` / `ABOVE_RIGHT` 从**左边**横着出去再竖回文字）。
 */
internal fun routeNoteArrow(
    bubble: Rect,
    inkRects: List<Rect>,
    glyphs: List<Rect> = emptyList(),
    solids: List<Rect> = emptyList(),
    otherBubbles: List<Rect> = emptyList(),
    drawnArrows: List<NoteArrow> = emptyList(),
    lineH: Float = 0f,
    side: NoteSide? = null,
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
    // 引线贴字门槛：旁注行高的 0.3 倍（lineH 缺省时放宽到 0：单测不量贴字）。
    val hugDistance = if (lineH > 0f) lineH * ARROW_HUG_LINES else 0f
    // 目标：右侧摆法指向所选正文的**终点**（末行最右那个字），左侧摆法指向起点；
    // 正下 / 正上 / 退化调用才回到「最近的几个字」。
    val targetPool = arrowTargetGlyphs(side, inkRects, bubble)
    val targets = targetPool
        .sortedBy { rectDistance(bubble, it) }
        .take(ARROW_TARGET_LIMIT)
        .map { glyph ->
            val near = Rect(
                min(bubble.left, glyph.left) - window,
                min(bubble.top, glyph.top) - window,
                max(bubble.right, glyph.right) + window,
                max(bubble.bottom, glyph.bottom) + window,
            )
            val nearObstacles = obstacles.filter { it.rect.overlaps(near) }
            ArrowTarget(
                glyph = glyph,
                obstacles = nearObstacles,
                gapYs = arrowGapMidYs(nearObstacles),
            )
        }

    var order = 0
    var fallback: ArrowPath? = null
    var best: ArrowPath? = null

    fun consider(points: List<Offset>, target: ArrowTarget) {
        val path = measureArrowRoute(
            points, order++, target.obstacles, bubble, drawnSegments, hugDistance,
        ) ?: return
        if (path.clear) {
            if (path.betterThan(best)) best = path
        } else if (path.betterFallbackThan(fallback)) {
            fallback = path
        }
    }

    /** 一组「出口 × 入口」：直线能走就直线，走不通再按折数由少到多试正交折线。 */
    fun tryRoute(exit: ArrowExit, entry: ArrowEntry, target: ArrowTarget) {
        // 0) 两端恰好对齐：一条直线（横 → 横 / 竖 → 竖）。
        if (exit.horizontal == entry.horizontal) {
            val aligned = if (exit.horizontal) {
                abs(exit.point.y - entry.point.y) < 0.01f && abs(exit.point.x - entry.point.x) > 0.01f
            } else {
                abs(exit.point.x - entry.point.x) < 0.01f && abs(exit.point.y - entry.point.y) > 0.01f
            }
            if (aligned) consider(listOf(exit.point, entry.point), target)
        }
        // 1) 折线：只用横 / 竖段，且第一段 ⊥ 框边、最后一段 ⊥ 文字框边。
        // 折数由少到多，一旦这个折数有通畅路径就不再试更多折（少折永远优先）。
        for (bends in 1..ARROW_MAX_BENDS) {
            for (points in noteElbowRoutes(
                start = exit.point,
                end = entry.point,
                obstacles = target.obstacles.map { it.rect },
                bends = bends,
                extraYs = target.gapYs,
            )) {
                if (!orthogonalShaped(points, exit.horizontal, entry.horizontal)) continue
                consider(points, target)
            }
            if (best?.let { it.clear } == true) break
        }
    }

    /**
     * 收尾：通畅路径先看是不是「挤在一起的短竖线」——是就改走左侧 L 形；
     * 否则原样返回（2026-10-24 用户反馈：讨论的引线 17px 快看不见了）。
     */
    fun finish(path: ArrowPath): NoteArrow {
        val cornerFamily = side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT ||
            side == NoteSide.BELOW_LEFT || side == NoteSide.ABOVE_LEFT
        val squeezed = path.clear &&
            cornerFamily &&
            path.points.size == 2 &&
            abs(path.points[0].x - path.points[1].x) < 0.01f &&
            path.length < max(lineH * NOTE_ARROW_MIN_VISIBLE_LINES, 8f)
        if (!squeezed) return path.toNoteArrow()
        val leftCorner = side == NoteSide.BELOW_LEFT || side == NoteSide.ABOVE_LEFT
        var nearBest: ArrowPath? = null
        for (t in targets) {
            val below = bubble.top >= t.glyph.bottom - NOTE_GEOM_EPS
            val above = bubble.bottom <= t.glyph.top + NOTE_GEOM_EPS
            if (!below && !above) continue
            val yEntry = if (below) t.glyph.bottom else t.glyph.top
            // 同一行里靠起点 / 终点那个字：L 形的竖段贴着它的边，箭头指向锚点的角落。
            val rowGlyphs = inkRects.filter {
                abs(it.center.y - t.glyph.center.y) <= max(it.height, t.glyph.height) * 0.6f
            }
            val startGlyph = if (leftCorner) {
                rowGlyphs.maxByOrNull { it.right } ?: t.glyph
            } else {
                rowGlyphs.minByOrNull { it.left } ?: t.glyph
            }
            val xEntry = if (leftCorner) startGlyph.right else startGlyph.left
            val edgeX = if (leftCorner) bubble.right else bubble.left
            if (abs(xEntry - edgeX) < 1f) continue
            for (f in floatArrayOf(0.5f, 0.25f, 0.75f)) {
                val y = bubble.top + bubble.height * f
                val points = listOf(
                    Offset(edgeX, y),
                    Offset(xEntry, y),
                    Offset(xEntry, yEntry),
                )
                if (!orthogonalShaped(points, exitHorizontal = true, entryHorizontal = false)) continue
                val near = measureArrowRoute(
                    points, order++, t.obstacles, bubble, drawnSegments, hugDistance,
                ) ?: continue
                if (near.clear && near.betterThan(nearBest)) nearBest = near
            }
        }
        return (nearBest ?: path).toNoteArrow()
    }

    // 按布点方向摆放的批注：两端都不再固定在中心点，出口可以沿框边挪、入口可以沿字边挪，
    // 先找「一条直线走得通」的那一对（2026-10-24 用户定口：最短优先）。
    val sideAware = side != null && side != NoteSide.OTHER &&
        side != NoteSide.BELOW && side != NoteSide.ABOVE
    if (sideAware) {
        for (t in targets) {
            for (entry in arrowSideEntries(side!!, bubble, t.glyph)) {
                for (exit in arrowSideExits(entry, side, bubble, t.glyph)) {
                    tryRoute(exit, entry, t)
                }
                best?.let { if (it.clear) return finish(it) }
            }
        }
    } else {
        // 退化路径（正下 / 正上 / 没有布点方向）：沿用「朝文字那条边的中点」。
        val exit = arrowExit(side, relation, bubble, anchor)
        for (t in targets) {
            val entryHorizontal = arrowEntryHorizontal(side, bubble, t.glyph)
            for (entry in arrowEntries(bubble, t.glyph, exit, entryHorizontal)) {
                tryRoute(exit, ArrowEntry(entry, entryHorizontal), t)
            }
            best?.let { if (it.clear) return finish(it) }
        }
    }
    return (best ?: fallback)?.let { finish(it) }
}

/**
 * 引线的目标字（§6.1，2026-10-24 用户定口）：
 *
 * - **放在正文右侧**（正右 / 右下 / 右上）→ 指向所选正文的**终点**：与框纵向最近的那一行里
 *   最靠右的那个字。框在文字右侧就进它的右侧面，框偏上 / 偏下就进上边 / 下边靠末端的位置。
 * - **放在左侧** → 对称地指向**起点**。
 * - 正下 / 正上 / 没有布点方向 → 仍旧取最近的几个字（最近那个被围死时还能换下一个）。
 */
private fun arrowTargetGlyphs(side: NoteSide?, inkRects: List<Rect>, bubble: Rect): List<Rect> {
    val rightFamily = side == NoteSide.RIGHT || side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT
    val leftFamily = side == NoteSide.LEFT || side == NoteSide.BELOW_LEFT || side == NoteSide.ABOVE_LEFT
    if (!rightFamily && !leftFamily) {
        return inkRects.sortedBy { rectDistance(bubble, it) }.take(ARROW_TARGET_LIMIT)
    }
    // 与框纵向最近的那一行（多行选区里框贴着哪一行，就指哪一行的末端）。
    fun gapY(r: Rect): Float = when {
        r.bottom <= bubble.top -> bubble.top - r.bottom
        r.top >= bubble.bottom -> r.top - bubble.bottom
        else -> 0f
    }
    val rowAnchor = inkRects.minByOrNull { gapY(it) } ?: return emptyList()
    val rowGlyphs = inkRects.filter {
        abs(it.center.y - rowAnchor.center.y) <= max(it.height, rowAnchor.height) * 0.6f
    }
    val glyph = if (leftFamily) {
        rowGlyphs.minByOrNull { it.left } ?: rowAnchor
    } else {
        rowGlyphs.maxByOrNull { it.right } ?: rowAnchor
    }
    return listOf(glyph)
}

/**
 * 相邻两行墨迹之间的**垂直中线**（§6.4，2026-10-24 用户定口）：
 * 引线要从上下两行文字之间横穿时，尽可能从正中间过，不贴着任何一边。
 *
 * 只看逐字墨迹（图形 / 表格 / 旁注框的边界是硬线，中线没有意义）；两行在横向上要有重叠，
 * 间距不超过两行高度的一半（隔得很远时「中间」只是普通空地，不需要专门作为折点）。
 */
private fun arrowGapMidYs(obstacles: List<ArrowObstacle>): List<Float> {
    val glyphs = obstacles.filter { it.tol <= ARROW_GLYPH_TOL_PX + 1e-4f }.map { it.rect }
    val out = LinkedHashSet<Float>()
    for (i in glyphs.indices) {
        for (j in i + 1 until glyphs.size) {
            val a = glyphs[i]
            val b = glyphs[j]
            val overlapX = min(a.right, b.right) - max(a.left, b.left)
            if (overlapX < 1f) continue
            val up: Rect
            val down: Rect
            if (a.bottom <= b.top) {
                up = a
                down = b
            } else if (b.bottom <= a.top) {
                up = b
                down = a
            } else {
                continue
            }
            val gap = down.top - up.bottom
            if (gap <= 0.5f || gap > max(up.height, down.height) * 0.5f) continue
            out += (up.bottom + down.top) * 0.5f
        }
    }
    return out.toList()
}

/** 引线出口：落在框的哪条边、第一段是不是横线。 */
private data class ArrowExit(val point: Offset, val horizontal: Boolean)

/** 引线入口：落在目标字的哪条边、最后一段是不是横线。 */
private data class ArrowEntry(val point: Offset, val horizontal: Boolean)

/**
 * 入边候选（§6.1）：**右侧摆法进终点所在的边**——
 *
 * - 框与文字纵向重叠 ⇒ 进右侧面（横着进）；
 * - 框在文字上方 ⇒ 进上边、取靠末端（右侧摆法）/ 靠起点（左侧摆法）的那几个点；
 * - 框在文字下方 ⇒ 进下边，同样取靠末端 / 靠起点。
 *
 * 入口不再固定在字的中点：靠末端的那几个点才能「指到终点」，也更容易和出口对齐成一条直线。
 */
private fun arrowSideEntries(side: NoteSide, bubble: Rect, glyph: Rect): List<ArrowEntry> {
    val out = LinkedHashSet<ArrowEntry>()
    val w = glyph.width
    val h = glyph.height
    val rightFamily = side == NoteSide.RIGHT || side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT
    val overlapY = bubble.top < glyph.bottom - NOTE_GEOM_EPS && bubble.bottom > glyph.top + NOTE_GEOM_EPS
    when {
        overlapY -> {
            val x = if (rightFamily) glyph.right else glyph.left
            for (f in floatArrayOf(0.5f, 0.25f, 0.75f)) {
                out += ArrowEntry(Offset(x, glyph.top + h * f), horizontal = true)
            }
        }
        bubble.bottom <= glyph.top + NOTE_GEOM_EPS -> {
            val xs = if (rightFamily) {
                floatArrayOf(glyph.right, glyph.right - w * 0.25f, glyph.center.x)
            } else {
                floatArrayOf(glyph.left, glyph.left + w * 0.25f, glyph.center.x)
            }
            for (x in xs) out += ArrowEntry(Offset(x, glyph.top), horizontal = false)
        }
        else -> {
            val xs = if (rightFamily) {
                floatArrayOf(glyph.right, glyph.right - w * 0.25f, glyph.center.x)
            } else {
                floatArrayOf(glyph.left, glyph.left + w * 0.25f, glyph.center.x)
            }
            for (x in xs) out += ArrowEntry(Offset(x, glyph.bottom), horizontal = false)
        }
    }
    return out.toList()
}

/**
 * 出边候选（§6.1 / 2026-10-24 用户定口「起点也不一定在中心点」）：
 *
 * 沿**朝目标字的那些边**取点：文字在左就从左边出去、在右就从右边出去、在上 / 下就从上 / 下边出去。
 * 第一个点总与入口对齐（能直接画直线），其余几个让折线有绕行余地。
 */
private fun arrowSideExits(
    entry: ArrowEntry,
    side: NoteSide,
    bubble: Rect,
    glyph: Rect,
): List<ArrowExit> {
    val out = LinkedHashSet<ArrowExit>()
    val eps = NOTE_GEOM_EPS
    val ys = floatArrayOf(
        entry.point.y,
        bubble.center.y,
        bubble.top + bubble.height * 0.25f,
        bubble.bottom - bubble.height * 0.25f,
    )
    val xs = floatArrayOf(
        entry.point.x,
        bubble.center.x,
        bubble.left + bubble.width * 0.25f,
        bubble.right - bubble.width * 0.25f,
    )
    fun edgeY(x: Float) {
        for (y in ys) {
            out += ArrowExit(
                Offset(x, y.coerceIn(bubble.top + eps, bubble.bottom - eps)),
                horizontal = true,
            )
        }
    }
    fun edgeX(y: Float) {
        for (x in xs) {
            out += ArrowExit(
                Offset(x.coerceIn(bubble.left + eps, bubble.right - eps), y),
                horizontal = false,
            )
        }
    }
    val rightFamily = side == NoteSide.RIGHT || side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT
    // 朝正文的那条竖边：右侧摆法从左边出去，左侧摆法从右边出去。
    if (rightFamily) edgeY(bubble.left) else edgeY(bubble.right)
    // 文字在框上方 / 下方时，也可以直接从框的上边 / 下边竖着出去（对齐就是一条直线）。
    if (glyph.center.y < bubble.top - eps) edgeX(bubble.top)
    if (glyph.center.y > bubble.bottom + eps) edgeX(bubble.bottom)
    return out.toList()
}

/**
 * 引线的出口（§6.2）——**第一段必须垂直于框边**：
 *
 * - 正右（RIGHT）→ 从**左边**横着出去；
 * - 正左（LEFT）→ 从**右边**横着出去；
 * - 正下（BELOW）→ 从**上边**竖着出去；正上（ABOVE）→ 从**下边**竖着出去。
 *
 * 右下 / 右上以及所有「按布点方向」的批注改由 [arrowSideExits] 沿边取点（起点不再固定在中心），
 * 这里的分支只作兜底；没有布点方向（退化调用）时按几何关系兜底。
 */
private fun arrowExit(
    side: NoteSide?,
    relation: NoteArrowRelation,
    bubble: Rect,
    anchor: Rect,
): ArrowExit {
    return when (side) {
        // 右下 / 右上 / 左下 / 左上：从**朝正文那条竖边中点**横着出去，再 90° 竖回文字。
        NoteSide.BELOW_RIGHT, NoteSide.ABOVE_RIGHT ->
            ArrowExit(Offset(bubble.left, bubble.center.y), horizontal = true)
        NoteSide.BELOW_LEFT, NoteSide.ABOVE_LEFT ->
            ArrowExit(Offset(bubble.right, bubble.center.y), horizontal = true)
        NoteSide.RIGHT -> ArrowExit(Offset(bubble.left, bubble.center.y), horizontal = true)
        NoteSide.LEFT -> ArrowExit(Offset(bubble.right, bubble.center.y), horizontal = true)
        NoteSide.BELOW -> ArrowExit(Offset(bubble.center.x, bubble.top), horizontal = false)
        NoteSide.ABOVE -> ArrowExit(Offset(bubble.center.x, bubble.bottom), horizontal = false)
        null, NoteSide.OTHER -> when (relation) {
            NoteArrowRelation.RIGHT -> ArrowExit(Offset(bubble.left, bubble.center.y), true)
            NoteArrowRelation.LEFT -> ArrowExit(Offset(bubble.right, bubble.center.y), true)
            NoteArrowRelation.ABOVE -> ArrowExit(Offset(bubble.center.x, bubble.bottom), false)
            NoteArrowRelation.BELOW -> ArrowExit(Offset(bubble.center.x, bubble.top), false)
            // 斜靠：竖直方向先分开就先竖着出去，否则横着出去。
            NoteArrowRelation.SIDE -> if (anchor.bottom <= bubble.top || anchor.top >= bubble.bottom) {
                val up = anchor.bottom <= bubble.top
                ArrowExit(Offset(bubble.center.x, if (up) bubble.top else bubble.bottom), false)
            } else {
                val leftward = anchor.right <= bubble.left
                ArrowExit(Offset(if (leftward) bubble.left else bubble.right, bubble.center.y), true)
            }
        }
    }
}

/**
 * 引线末端落在文字框的哪条边上（§6.2 第 3 条：**最后一段必须垂直于文字框边**）：
 * 正右 / 正左 → 横着进（末段横线）；正下 / 正上 / 右下 / 右上 → 竖着进（末段竖线）。
 */
private fun arrowEntryHorizontal(side: NoteSide?, bubble: Rect, glyph: Rect): Boolean =
    when (side) {
        NoteSide.RIGHT, NoteSide.LEFT -> true
        NoteSide.BELOW, NoteSide.ABOVE,
        NoteSide.BELOW_RIGHT, NoteSide.ABOVE_RIGHT,
        NoteSide.BELOW_LEFT, NoteSide.ABOVE_LEFT -> false
        // 退化调用按几何：上下分开就竖着进，否则横着进。
        null, NoteSide.OTHER -> !(glyph.bottom <= bubble.top || glyph.top >= bubble.bottom)
    }

/**
 * 末端点的候选：都要落在文字框朝框的那条边上（竖进取底 / 顶边，横进取右 / 左边）。
 *
 * 第一个候选是「与出口对齐」的那个点——两端正好对齐时就是一条直线；后面几个让折线有左右挪动的余地。
 * 右下 / 右上已由 [arrowSideEntries] 接管（指向终点，不再固定最左字），这里只服务正下 / 正上 / 退化调用。
 */
private fun arrowEntries(
    bubble: Rect,
    glyph: Rect,
    exit: ArrowExit,
    horizontal: Boolean,
): List<Offset> {
    val out = LinkedHashSet<Offset>()
    if (horizontal) {
        val x = if (glyph.center.x <= bubble.center.x) glyph.right else glyph.left
        out += Offset(x, exit.point.y.coerceIn(glyph.top, glyph.bottom))
        for (f in floatArrayOf(0.5f, 0.25f, 0.75f)) out += Offset(x, glyph.top + glyph.height * f)
    } else {
        val y = if (glyph.center.y <= bubble.center.y) glyph.bottom else glyph.top
        out += Offset(exit.point.x.coerceIn(glyph.left, glyph.right), y)
        for (f in floatArrayOf(0.5f, 0.25f, 0.75f)) out += Offset(glyph.left + glyph.width * f, y)
    }
    return out.toList()
}

/**
 * 折线是否守规矩：**只有横竖段**，第一段垂直于框边（[exitHorizontal]）、
 * 最后一段垂直于文字框边（[entryHorizontal]）。
 */
private fun orthogonalShaped(
    points: List<Offset>,
    exitHorizontal: Boolean,
    entryHorizontal: Boolean,
): Boolean {
    val p = simplifyPolyline(points)
    if (p.size < 2) return false
    fun along(a: Offset, b: Offset, horizontal: Boolean): Boolean =
        if (horizontal) {
            abs(a.y - b.y) < 0.01f && abs(a.x - b.x) > 0.01f
        } else {
            abs(a.x - b.x) < 0.01f && abs(a.y - b.y) > 0.01f
        }
    if (!along(p[0], p[1], exitHorizontal)) return false
    if (!along(p[p.size - 2], p[p.size - 1], entryHorizontal)) return false
    return p.zipWithNext().all { (a, b) -> abs(a.x - b.x) < 0.01f || abs(a.y - b.y) < 0.01f }
}

/**
 * 正交折线的候选（§6.2）：[bends] = 1 试两个拐点，2 试横 / 纵各一条折点坐标，
 * 3 试两条折点坐标的四种组合，4 只试「绕开一整条竖带 / 横带」的两族（坐标只取前三个）。
 *
 * 折点坐标取自两端与附近障碍的四边（外扩 [ARROW_BEND_CLEARANCE_PX]），按离中点由近到远取前几个，
 * 近的更容易凑出短路径；两端中点补在最后。返回顺序固定，同一输入结果恒定。
 */
internal fun noteElbowRoutes(
    start: Offset,
    end: Offset,
    obstacles: List<Rect> = emptyList(),
    bends: Int = 1,
    /** 额外的候选 y 坐标（通常是相邻两行墨迹的中线）：排在前面，优先被取到。 */
    extraYs: List<Float> = emptyList(),
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
    val bendXs = pickCoords(xs, (start.x + end.x) * 0.5f).toMutableList()
    val bendYs = LinkedHashSet<Float>()
    for (y in extraYs.take(2)) bendYs += y
    for (y in pickCoords(ys, (start.y + end.y) * 0.5f)) bendYs += y
    // 两端都不在障碍边上时（比如空地上折一次），中线坐标是唯一能同时满足「首末段垂直」的落点，
    // 所以补在最后：不抢前面候选的先后，只在它们都不通时才用上。
    val midX = (start.x + end.x) * 0.5f
    val midY = (start.y + end.y) * 0.5f
    if (bendXs.none { abs(it - midX) <= 0.5f } && abs(start.x - end.x) > 0.01f) bendXs += midX
    if (bendYs.none { abs(it - midY) <= 0.5f } && abs(start.y - end.y) > 0.01f) bendYs += midY
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
        3 -> for (x in bendXs) {
            for (y in bendYs) {
                out += listOf(start, Offset(x, start.y), Offset(x, y), Offset(end.x, y), end)
                out += listOf(start, Offset(start.x, y), Offset(x, y), Offset(x, end.y), end)
            }
        }
        // 四折只做「绕开一整条竖带 / 横带」两族：两臂各一个自由坐标，够绕过图形与表格。
        // 竖 / 横那个「跨过去」的坐标取全部候选（绕过整块图形靠的就是它）；两臂的坐标则分别
        // **贴着起点 / 终点那一侧取**——只取离中点最近的几个会漏掉「先横到障碍之外、再竖过去」
        // 的那一对（真机第 5/6 条：从两行字之间横穿的绕行正是这种形状）。
        else -> {
            val xsFromStart = pickCoords(xs, start.x).take(4)
            val xsFromEnd = pickCoords(xs, end.x).take(4)
            val ysFromStart = pickCoords(ys, start.y).take(4)
            val ysFromEnd = pickCoords(ys, end.y).take(4)
            for (x1 in xsFromStart) {
                for (x2 in xsFromEnd) {
                    for (y in bendYs) {
                        out += listOf(
                            start, Offset(x1, start.y), Offset(x1, y),
                            Offset(x2, y), Offset(x2, end.y), end,
                        )
                    }
                }
            }
            for (y1 in ysFromStart) {
                for (y2 in ysFromEnd) {
                    for (x in bendXs) {
                        out += listOf(
                            start, Offset(start.x, y1), Offset(x, y1),
                            Offset(x, y2), Offset(end.x, y2), end,
                        )
                    }
                }
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
    hugDistance: Float = 0f,
): ArrowPath? {
    val points = simplifyPolyline(route)
    if (points.size < 2) return null
    var length = 0f
    var hits = 0f
    var crosses = 0
    var hug = 0f
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
        val horizontal = abs(a.y - b.y) < 0.01f
        val vertical = abs(a.x - b.x) < 0.01f
        for (o in obstacles) {
            // 粗筛要把「贴字」的门槛算进去：刚好在门槛以内的字形不能因为包围盒不相交被跳过。
            if (o.rect.right < segLeft - hugDistance || o.rect.left > segRight + hugDistance ||
                o.rect.bottom < segTop - hugDistance || o.rect.top > segBottom + hugDistance
            ) {
                continue
            }
            val span = insideSpan(a, b, o.rect, o.tol)
            if (span > 1e-6f) hits += span * len
            // 「贴字」只量平行段：横线看它离上下字墨迹的垂直距离，竖线看左右距离。
            // 斜着接近字边（入口那一段）不算——箭头本来就要接住字。
            val crossesInterior = when {
                horizontal -> o.rect.left < segRight - 1f && o.rect.right > segLeft + 1f
                vertical -> o.rect.top < segBottom - 1f && o.rect.bottom > segTop + 1f
                else -> false
            }
            if (hugDistance > 0f && crossesInterior && span <= 1e-6f &&
                o.tol <= ARROW_GLYPH_TOL_PX + 1e-4f
            ) {
                val d = when {
                    horizontal -> when {
                        a.y <= o.rect.top -> o.rect.top - a.y
                        a.y >= o.rect.bottom -> a.y - o.rect.bottom
                        else -> 0f
                    }
                    vertical -> when {
                        a.x <= o.rect.left -> o.rect.left - a.x
                        a.x >= o.rect.right -> a.x - o.rect.right
                        else -> 0f
                    }
                    else -> Float.MAX_VALUE
                }
                if (d < hugDistance) hug += hugDistance - d
            }
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
        hug = hug,
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
