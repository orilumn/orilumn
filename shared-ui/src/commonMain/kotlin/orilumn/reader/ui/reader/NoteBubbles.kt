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
import kotlin.time.TimeSource
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.io.Logger
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

/**
 * 最少可读的字数（规则第 11 / 12 条）：折叠最多折到**一个字 + 省略号**，
 * 所以只要能显示一个字就还画框；一个字都放不下才退成小图标。
 */
private const val MIN_SHOWN_CHARS: Int = 1

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
/** 近侧也收：竖直方向先收到「正文行距让出来的空白」（规则第 13 条的默认上下留白）。 */
private const val NOTE_MARGIN_TIER_NEAR_GAP: Int = 2
/** 压到底：近侧只留 [NOTE_MIN_MARGIN_PX]（规则第 13 条的下限）。 */
private const val NOTE_MARGIN_TIER_NEAR_MIN: Int = 3

/** 留白压缩的下限（规则第 13 条：压到 2px 才算这个方向不可用）。 */
private const val NOTE_MIN_MARGIN_PX: Float = 2f

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

/** 一条候选折线最多几个顶点（4 折 = 6 个）：暂存数组按它开，热路径上不再分配。 */
private const val ARROW_ROUTE_MAX_POINTS: Int = 6

/** 沿框边取点的比例（正上 / 正下与左侧 L 形都按这几个比例挪）。 */
private val ARROW_EDGE_FRACTIONS = floatArrayOf(0.5f, 0.25f, 0.75f)

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

/**
 * 文字测量缓存：同一句话、同一字号、同一宽度反复问，只真量第一次。
 *
 * 为什么必须有：布点会给一条批注枚举几十个候选位置，每个候选都问一遍「整句多宽」
 * 「这么宽能放几个字」。实测一页 10 条批注会问 3886 次，而 Compose 的 `TextMeasurer`
 * 默认只缓存 8 条，等于每次都真排版，一页就要 2～4 秒。
 *
 * 缓存键只放**测量真正依赖的东西**：文字、字号位模式、宽度位模式、行数上限。
 * 键按浮点原样取，不按整数取整，避免两个相邻宽度被并成一个而改变折行结果；
 * `ComposeNoteTextSizer.fit` 本来就把宽度取整后再量，所以同一整数宽度的重复询问照样命中。
 * 条目超上限就整体清空，不做 LRU——清空是 O(1)，
 * 而且纯函数重算一遍也还是同一个值。
 */
internal class CachingNoteTextSizer(
    private val delegate: NoteTextSizer,
    private val maxEntries: Int = 4096,
) : NoteTextSizer {

    private data class NaturalKey(val text: String, val fontBits: Int)

    private data class FitKey(val text: String, val fontBits: Int, val widthBits: Int, val maxLines: Int)

    private data class WidthKey(val text: String, val fontBits: Int, val widthBits: Int)

    private val natural = HashMap<NaturalKey, Float>(64)
    private val fits = HashMap<FitKey, NoteTextFit>(128)

    /** 同一宽度下「整句放得下」的结果：与 maxLines 无关（只要不比它小），可跨容量复用。 */
    private val completeFits = HashMap<WidthKey, NoteTextFit>(128)

    /** 真排版次数与耗时（真机计时用：这是布点里最贵的一笔，量清楚才知道该优化哪）。 */
    var realFits: Int = 0
        private set
    var realFitNanos: Long = 0L
        private set

    fun resetStats() {
        realFits = 0
        realFitNanos = 0L
    }

    override fun naturalWidth(text: String, fontPx: Float): Float {
        val key = NaturalKey(text, fontPx.toRawBits())
        natural[key]?.let { return it }
        val value = delegate.naturalWidth(text, fontPx)
        if (natural.size >= maxEntries) natural.clear()
        natural[key] = value
        return value
    }

    override fun fit(text: String, fontPx: Float, widthPx: Float, maxLines: Int): NoteTextFit {
        val key = FitKey(text, fontPx.toRawBits(), widthPx.toRawBits(), maxLines)
        fits[key]?.let { return it }
        val wide = WidthKey(text, fontPx.toRawBits(), widthPx.toRawBits())
        completeFits[wide]?.let { if (it.lines <= maxLines) return it }
        val mark = TimeSource.Monotonic.markNow()
        val value = delegate.fit(text, fontPx, widthPx, maxLines)
        realFits++
        realFitNanos += mark.elapsedNow().inWholeNanoseconds
        if (value.shownChars >= value.totalChars) {
            if (completeFits.size >= maxEntries) completeFits.clear()
            completeFits[wide] = value
        }
        if (fits.size >= maxEntries) fits.clear()
        fits[key] = value
        return value
    }
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
 * 环内的方向名次（2026-10-24 用户定口，规则第 5 条）：**右上 → 右下 → 左上 → 左下 → 上 → 下 → 右 → 左**。
 *
 * 这一段只在**折行数、环都相同**时才轮到（§1.1「顺序怎么比」）。左右排在最后是因为
 * 它们往往只剩页边距那一窄列（规则第 6 条：那里最多折两行）；
 * 但规则第 3 / 4 条给了**提前**的例外，由候选自带的 [NoteCandidate.sideRank] 落实
 * （2026-10-25 用户定口，左右都可能首选）：
 *
 * - 只有右侧离版心还有 1em 以上空白 ⇒ 正右提到最前；
 * - 只有这一行左侧有明显缩进 ⇒ 正左提到最前；
 * - 两边都空着 ⇒ 看选区在这一行里偏左还是偏右（哪边空档小），偏哪边就把哪边提到最前。
 *
 * 右侧已经被别的批注占住时，被提前的正右退回「左」之前（见 [NOTE_SIDE_RANK_TAKEN_RIGHT]）。
 */
internal fun noteSideRank(side: NoteSide): Int = when (side) {
    NoteSide.ABOVE_RIGHT -> 0
    NoteSide.BELOW_RIGHT -> 1
    NoteSide.ABOVE_LEFT -> 2
    NoteSide.BELOW_LEFT -> 3
    NoteSide.ABOVE -> 4
    NoteSide.BELOW -> 5
    NoteSide.RIGHT -> 6
    NoteSide.LEFT -> 7
    NoteSide.OTHER -> 8
}

/** 规则 3 / 4 命中时「首选那一侧」的名次：排到八个方向之前（2026-10-25：左右都可能首选）。 */
internal const val NOTE_SIDE_RANK_SIDE_FIRST: Int = -2

/**
 * 另一侧也空着、但不是首选时的名次：排在首选侧之后、四角之前。
 *
 * 首选那一列太窄（缩进只让出几像素）放不下整句时，退回另一侧的正左 / 正右，
 * 而不是直接掉到四角或页边距那条窄缝里。
 */
private const val NOTE_SIDE_RANK_SIDE_SECOND: Int = -1

/** 右侧已被占时，正右的名次：让到「左」之前（倒数第二位）。 */
private const val NOTE_SIDE_RANK_TAKEN_RIGHT: Int = 6

/**
 * 「这一条贴着自己那一行摆，会横着盖住右侧那条的选区」时的方向名次
 * （规则第 8 条，2026-10-25 用户定口）：**左上 → 左下 → 右上 → 右下**，其余照旧。
 *
 * 右侧已经有批注、而本条的框横向会把它那段文字整个罩住时，右侧那片空白是**右边那条的**
 * ——本条的框摆过去，两条的引线要在同一片空白里交叉，读者也分不清哪条批注在说哪段字。
 * 所以先走左侧；左侧实在放不下才回到右上 / 右下，并且**按距离就近取**，不再往页面另一头跑。
 */
private fun coveringSideRank(side: NoteSide): Int = when (side) {
    NoteSide.ABOVE_LEFT -> 0
    NoteSide.BELOW_LEFT -> 1
    NoteSide.ABOVE_RIGHT -> 2
    NoteSide.BELOW_RIGHT -> 3
    NoteSide.ABOVE -> 4
    NoteSide.BELOW -> 5
    NoteSide.RIGHT -> 6
    NoteSide.LEFT -> 7
    NoteSide.OTHER -> 8
}

/** 让位／自行挪开时最多试几步（每步让到最近的挡路框之外，再判一次）。 */
private const val NOTE_SLIDE_STEPS: Int = 4

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
    /**
     * 这个候选的方向名次（规则第 3 / 4 / 5 条一起决定）：默认取 [noteSideRank]，
     * 命中「右侧还有 1em 以上空白」或「左侧有缩进」时由生成端提前。
     * 存在候选上而不是排序时现算：它取决于**这一行**的几何，不是方向的固定属性。
     */
    val sideRank: Int = noteSideRank(side),
    /**
     * 规则第 10 条「跨越其他标注视同跨行」：这个框与锚点之间**隔着几行别的批注墨迹**。
     *
     * 排序时按 `ring + crossed` 比远近：同一圈里，要越过别人那条的路子排到没越过的后面去。
     */
    val crossed: Int = 0,
    /**
     * 这个候选是从**哪一行**生成的（`lines` 的行下标）。
     *
     * 引线落点**不再**跟着它走（2026-10-25 用户改口）：布线时按「框离选区开头近还是离末尾近」
     * 另挑一头，见 [noteArrowRow]。它只剩逐字几何量不出来时的兜底。
     */
    val anchorRow: Int = -1,
    /**
     * 布点阶梯档（2026-10-25 用户定口）：0 = **末行侧**（末行的环 0，含下方相邻行），
     * 1 = **首行侧**（首行的环 0，含上方相邻行），2 = 其他（跨行各圈、段外左侧）。
     *
     * 多行选区先贴末行排；末行那一圈放不下，才轮到首行那一圈。排序时它排在
     * 「整句放不放得下 / 折行段」之后、环数之前——**整句优先（规则 9 / 11）不被它推翻**。
     */
    val nearRank: Int = 2,
) {
    /**
     * 四个斜向（右上 / 右下 / 左上 / 左下）。
     *
     * 斜向的留白不是规则第 7 条的 1em：横向就是规则第 5 条的「偏右 / 偏左一个字」，
     * 纵向是行距空档，所以**留白档在斜向上不参与先后**——先取最近的，留白档只在同距时决胜。
     * 正上 / 正下 / 正左 / 正右 那一侧的 1em 是给引线与箭头的空档，仍然优先保住。
     */
    val corner: Boolean
        get() = side == NoteSide.ABOVE_RIGHT || side == NoteSide.BELOW_RIGHT ||
            side == NoteSide.ABOVE_LEFT || side == NoteSide.BELOW_LEFT

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
    /**
     * 只保留「有可能整句放下」的候选（方案 §5 第 1 档）。
     *
     * 便宜的必要条件：整句字宽总和超过「每行可用宽 × 行数」时，怎么断行都装不下
     * （断行只会浪费，不会省宽）。这种候选连排版都不必做——整句档永远不会用它，
     * 留到截断档真要它时再量。真机上一页十条批注就是被这些候选的实测拖慢的。
     */
    completeOnly: Boolean = false,
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
    if (completeOnly && desired > 0f && desired > innerW * capacity + NOTE_GEOM_EPS) {
        return NoteBoxSize(0f, 0, 0f, 0, total)
    }
    // 整句一行放得下就不再让折行模拟复核：真实排版比「宽/字宽」更怕差一像素，
    // 实测尺子（[rememberNoteTextSizer]）已在整句宽里含了绘制侧的取整余量。
    val oneLine = desired > 0f && desired <= innerW + NOTE_GEOM_EPS
    val width = min(desired, innerW) + metrics.padTotal
    val inner = width - metrics.padTotal
    // **只量一次**：maxLines 直接给容量。整句放得下时返回真实行数（不截断），放不下时返回
    // 容量行 + 可见字数——与「先量自然行数、再按 min(自然, 容量) 重量」完全等价。
    // 同一宽度下「整句放得下」的结果与容量无关，由 [CachingNoteTextSizer] 跨容量复用，
    // 所以一个宽度一辈子只排一次版（真机上这是布点里最大的一笔开销）。
    val fit = if (oneLine) {
        NoteTextFit(lines = 1, shownChars = total, totalChars = total)
    } else {
        sizer.fit(content, metrics.em, inner, capacity)
    }
    val lines = if (oneLine) 1 else fit.lines.coerceIn(1, capacity)
    return NoteBoxSize(
        width = width,
        lines = lines,
        height = lines * metrics.lineH,
        shownChars = min(fit.shownChars, total),
        totalChars = total,
    )
}

/**
 * 候选生成的范围：分批发给调用方，放不下才继续往外要。
 *
 * 一页十条批注时，旧实现会把本段每一行、相邻段每一行、左侧页边距都枚举一遍
 * （一条批注 ~380 个候选）。改成按批取：先 [NEAR]（本行相邻空间），再一圈一圈
 * [RINGS]，最后才 [TAIL]（本段左侧 / 相邻段 / 段外左侧）。排序键不变，
 * 所以「取到一批就够」的结论与一次全量生成完全相同。
 */
internal enum class NoteCandidateScope {
    /** 全量（默认，测试与直接调用用这个）。 */
    ALL,

    /** 只算本行相邻空间：基准行 + 上下相邻行的正右 / 正下 / 正上 / 斜向。 */
    NEAR,

    /** 跨行：只算 `ringFrom..ringTo` 这几圈（0 圈归 [NEAR]）。 */
    RINGS,

    /** 收尾：本段左侧、相邻段、段外左侧（排序上排在所有跨行之后）。 */
    TAIL,
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
 * @param rightInk 同一行上**在本条右边**那些批注的墨迹矩形：本条的整句宽在右侧会把它们整个罩住时，
 *   这一条判为「会遮挡右侧文字区域」——方向改成左上 → 左下 → 右上 → 右下，同排也要让右边那条先取位
 *   （规则第 8 条，2026-10-25 用户定口）。默认空 = 不判。
 * @param covering 这一条是不是「会遮挡右侧文字区域」。`null` 按 [rightInk] 现算；
 *   布点时由调用方一次算好传进来，避免每批候选都重算。
 * @param contentRight **版心右缘**（页坐标）：锚点末行在这条线左边还留着 1em 以上空白时，
 *   正右提到最前（规则第 3 条）。默认无上限 = 不做这个提前（单测与直接调用）。
 * @param contentLeft **版心左缘**（页坐标）：右侧没余地、但这一行左侧有明显缩进时，
 *   正左提到最前（规则第 4 条）。默认 `null` = 不做这个提前。
 * @param scope 生成范围：默认全量；布点时按 NEAR → RINGS（一圈一批）→ TAIL 分批发。
 * @param ringFrom / @param ringTo 只在 [NoteCandidateScope.RINGS] 下生效：要生成第几圈到第几圈。
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
    rightInk: List<Rect> = emptyList(),
    covering: Boolean? = null,
    contentRight: Float = Float.MAX_VALUE,
    contentLeft: Float? = null,
    /**
     * 竖直方向的默认留白（规则第 13 条）：取「正文行盒节距 − 行盒高」，即行与行之间
     * 那段纯空白；压缩到 [NOTE_MIN_MARGIN_PX] 为止。缺省沿用 [NoteMetrics.marginY]。
     */
    verticalGap: Float = metrics.marginY,
    scope: NoteCandidateScope = NoteCandidateScope.ALL,
    ringFrom: Int = 1,
    ringTo: Int = Int.MAX_VALUE,
    /** 只生成「有可能整句放下」的候选（整句档用）；截断档要重跑一遍取剩下的。 */
    completeOnly: Boolean = false,
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

    /**
     * 规则第 9 / 11 条的「本段落可用范围」（2026-10-25 用户定口）：
     *
     * 指**空白连成一片**的区域——上下相邻的行只要没占满整个版心，它左右两侧的空白就把
     * 上下两片连起来，算同一范围；**占满整个版心的行把空白切断**，范围到此为止。
     * 页边距那一列上下是通的，但**不算**把两片空白连起来。
     *
     * 所以范围 = 基准段向上下各延伸到第一行「顶满版心」的行之前。
     */
    fun spansContent(row: NoteTextLine): Boolean {
        val leftEdge = contentLeft ?: lines.minOf { it.rect.left }
        val rightEdge = if (contentRight < Float.MAX_VALUE / 2f) {
            contentRight
        } else {
            lines.maxOf { it.rect.right }
        }
        return row.rect.left - leftEdge < preferred && rightEdge - row.rect.right < preferred
    }

    var runStart = blockStart
    while (runStart > 0 && !spansContent(lines[runStart - 1])) runStart--
    var runEnd = blockEnd
    while (runEnd + 1 < lines.size && !spansContent(lines[runEnd + 1])) runEnd++
    val charW = max(charWidthPx, 1f)
    val baseGlyphs = note.rects
        .filter { nearestLineIndex(it, lines) == base }
        .sortedBy { it.left }
    val firstGlyph = baseGlyphs.firstOrNull() ?: note.rects.minByOrNull { it.left } ?: return emptyList()
    val lastGlyph = baseGlyphs.lastOrNull() ?: note.rects.maxByOrNull { it.right } ?: return emptyList()
    // 「首字符右移一个汉字」/「末字符右移一个汉字」：斜向摆法的横向基准（§0.2）。
    // 2026-10-25 用户定口：**左上 / 左下看选区最右缘再往左一个字**（= 倒数第二个字的右缘），
    // 不是末字的左缘——框的右缘只有落在它左边，才叫「左上 / 左下」；越过它就是「正上方 / 正下方」。
    val cornerRightX = firstGlyph.left + charW
    val cornerLeftX = lastGlyph.right - charW

    // 规则 3 / 4（2026-10-25 用户定口）：
    // - 只有右侧离版心还有 1em 以上空档 ⇒ 正右首选；
    // - 只有这一行左侧有明显缩进 ⇒ 正左首选；
    // - 两边都空着（左缩进 + 右非满行）⇒ 看选区在这一行里**偏左还是偏右**：
    //   哪边空档小（离得近）就走哪边。偏左 = 左侧空档比右侧小。
    // contentRight 缺省时退回「本页空白能到的最右缘」：直接调用（单测）没有版心边界，
    // 但空白本身仍有右缘，照样能判断「这一行右边还有没有 1em」。
    val rightEdge = if (contentRight < Float.MAX_VALUE / 2f) {
        contentRight
    } else {
        freeRects.maxOfOrNull { it.right } ?: contentRight
    }
    val leftGap = contentLeft?.let { baseRow.rect.left - it }
    val rightGap = rightEdge - baseRow.rect.right
    val leftRoom = leftGap != null && leftGap > preferred
    val rightRoom = rightGap > preferred
    val leftFirst = when {
        leftRoom && rightRoom -> leftGap!! < rightGap
        leftRoom -> true
        else -> false
    }
    val rightFirst = rightRoom && !leftFirst

    // 规则第 8 条（2026-10-25 用户定口）：整句宽会罩住右侧那条的选区 ⇒ 本条先走左侧。
    // 只在**这一条自己的基准行**上判；判据用整句一行的自然宽，不看某个候选碰巧折成多宽。
    val coversRightText = covering
        ?: noteCoversRightText(note, lines, rightInk, metrics, charWidthPx, sizer)

    fun sideRankOf(side: NoteSide): Int = when {
        // 会遮住右侧文字的，先左侧、再右侧，规则 3 / 4 的「首选一侧」也让位给它。
        coversRightText -> coveringSideRank(side)
        leftFirst && side == NoteSide.LEFT -> NOTE_SIDE_RANK_SIDE_FIRST
        rightFirst && side == NoteSide.RIGHT -> NOTE_SIDE_RANK_SIDE_FIRST
        // 首选那一列放不下（缩进只让出几像素）时，退回另一侧的正左 / 正右，再轮到四角。
        leftFirst && rightRoom && side == NoteSide.RIGHT -> NOTE_SIDE_RANK_SIDE_SECOND
        rightFirst && leftRoom && side == NoteSide.LEFT -> NOTE_SIDE_RANK_SIDE_SECOND
        else -> noteSideRank(side)
    }
    val out = ArrayList<NoteCandidate>()
    val seen = HashSet<Long>()

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

    /**
     * 竖直近侧留白（规则第 7 / 13 条）：1em（给引线与箭头留位置）→
     * 「正文行距让出来的纯空白」→ 2px。压到 2px 才算这个方向不可用。
     */
    fun nearYRoom(tier: Int): Float = when (tier) {
        NOTE_MARGIN_TIER_NEAR_GAP -> max(NOTE_MIN_MARGIN_PX, verticalGap)
        NOTE_MARGIN_TIER_NEAR_MIN -> NOTE_MIN_MARGIN_PX
        else -> preferred
    }

    /**
     * 规则第 6 条：左右**只剩页边距**那一列时最多折两行——那一列本来就窄，再多折会折成竖条。
     *
     * 判据看框自己落在哪儿（不是看空白矩形）：框整个在版心右缘之外（或左缘之外）才算页边距列。
     * 短行尾部的空白、缩进让出的空白都不算页边距，那里可以放心折行。
     */
    fun marginColumnMaxHeight(f: Rect, boxLeft: Float, boxRight: Float): Float {
        val inRightMargin = contentRight < Float.MAX_VALUE / 2f &&
            boxLeft >= contentRight - NOTE_GEOM_EPS
        val inLeftMargin = contentLeft != null && boxRight <= contentLeft + NOTE_GEOM_EPS
        // 2.5 行高的上限：容量按整除算，正好 2 行高会因为浮点取整掉成 1 行。
        return if (inRightMargin || inLeftMargin) min(f.height, metrics.lineH * 2.5f) else f.height
    }

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
        // **不能只留「最近 / 最深」两块**（2026-10-24 用户定口）：锚在行首时斜向基准点落在左边，
        // 页面左侧页边距那条窄缝既更近又更深，于是正文右侧的大块空白被整块挤掉——
        // 真机上就是「上一行右侧明明空着，却直接跳到隔两行」。这里按面积先取前几块，
        // 再补上横向最近、进深最大的各一块，让宽而矮的空白和窄而高的空白都能参与竞争。
        val picked = ArrayList<Rect>(5)
        pool.sortedByDescending { it.width * it.height }.take(3).forEach { picked += it }
        pool.minWithOrNull(compareBy({ dx(it) }, { -depth(it) }, { -it.width }))?.let { picked += it }
        pool.maxWithOrNull(compareBy({ depth(it) }, { -dx(it) }, { it.width }))?.let { picked += it }
        return picked.distinct()
    }

    /**
     * 方向按**框最终落在哪儿**判，不按「从哪个口子生成」判（规则第 5 条，2026-10-25 用户定口）。
     *
     * 生成端有几处会把「上一行右侧那一列」标成右上 / 右下（规则第 5 条：上一行、下一行的左右两侧
     * 属于本行相邻空间）——那一列对锚点来说其实落在**左边**，标成右下就会凭「离得更近」抢走
     * 真正右下角的位置（真机上就是「用几章来」明明有右下角，却摆到了左下）。
     */
    fun classify(rect: Rect): NoteSide {
        if (rect.bottom <= firstRow.rect.top + NOTE_GEOM_EPS) {
            return when {
                rect.left >= cornerRightX - NOTE_GEOM_EPS -> NoteSide.ABOVE_RIGHT
                rect.right <= cornerLeftX + NOTE_GEOM_EPS -> NoteSide.ABOVE_LEFT
                else -> NoteSide.ABOVE
            }
        }
        if (rect.top >= baseRow.rect.bottom - NOTE_GEOM_EPS) {
            return when {
                rect.left >= cornerRightX - NOTE_GEOM_EPS -> NoteSide.BELOW_RIGHT
                rect.right <= cornerLeftX + NOTE_GEOM_EPS -> NoteSide.BELOW_LEFT
                else -> NoteSide.BELOW
            }
        }
        if (rect.right <= firstGlyph.left + NOTE_GEOM_EPS) return NoteSide.LEFT
        if (rect.left >= lastGlyph.right - NOTE_GEOM_EPS) return NoteSide.RIGHT
        return if (rect.center.y <= baseRow.rect.center.y) NoteSide.ABOVE else NoteSide.BELOW
    }

    fun add(
        rowIndex: Int,
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
        val side = classify(rect)
        // 规则第 10 条：框与锚点之间隔了几行**别的批注**，就当多跨了几行。
        // `otherRowLeft` 的键就是「这一行有别的批注墨迹」，直接数它们有几行落在这段空档里。
        val crossed = if (otherRowLeft.isEmpty()) {
            0
        } else {
            val down = rect.top >= baseRow.rect.bottom - NOTE_GEOM_EPS
            val up = rect.bottom <= baseRow.rect.top + NOTE_GEOM_EPS
            when {
                down -> otherRowLeft.keys.count { r ->
                    val row = lines.getOrNull(r) ?: return@count false
                    row.rect.top >= baseRow.rect.bottom - NOTE_GEOM_EPS &&
                        row.rect.bottom <= rect.top + NOTE_GEOM_EPS
                }
                up -> otherRowLeft.keys.count { r ->
                    val row = lines.getOrNull(r) ?: return@count false
                    row.rect.bottom <= baseRow.rect.top + NOTE_GEOM_EPS &&
                        row.rect.top >= rect.bottom - NOTE_GEOM_EPS
                }
                else -> 0
            }
        }
        // 同一块空白被不同极大矩形重复切出时，落位可能完全一样：只留一个。
        // 键打成整数（方向 + 留白档 + 四个坐标各 13 位）：页坐标 < 8192，拼字符串纯属浪费。
        fun axis(v: Float): Long = v.roundToInt().toLong() and 0x1FFF
        val key = (side.ordinal.toLong() shl 55) or (tier.toLong() shl 52) or
            (axis(rect.left) shl 39) or (axis(rect.top) shl 26) or
            (axis(rect.right) shl 13) or axis(rect.bottom)
        if (!seen.add(key)) return
        // 2026-10-25 用户定口：多行选区先贴末行（环 0），末行那一圈放不下才轮到首行那一圈。
        // 用行下标判侧：末行及其下方相邻行 = 末行侧；首行及其上方相邻行 = 首行侧。
        val nearRank = when {
            ring != 0 -> 2
            rowIndex >= base -> 0
            rowIndex <= first -> 1
            else -> 2
        }
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
            sideRank = sideRankOf(side),
            crossed = crossed,
            anchorRow = rowIndex,
            nearRank = nearRank,
        )
    }

    /**
     * 正右：近侧固定 1em（水平引线要从这段空档出去），远侧按档位。
     *
     * 上下两条边不是「与正文的间距」，沿用空白自带的 0.1em 最小缝即可——
     * 「1em 无论上下左右」说的是**摆放方向那一侧**离正文的间距。
     *
     * 规则第 6 条：左右两侧**只剩页边距**时最多折两行——那一列窄，再多折会折出竖条。
     *
     * 落点由 [add] 按最终几何判方向：别的行右侧那一列对本行来说可能是右上 / 右下，
     * 也可能整个落在锚点左边（那就不是「右」）。
     */
    fun emitRight(
        f: Rect,
        rowIndex: Int,
        row: NoteTextLine,
        ring: Int,
        stage: Int,
        tier: Int,
    ) {
        val x = row.rect.right + metrics.sideGap
        val far = farExtra(marginX, tier)
        val left = max(x, f.left)
        val maxW = f.right - far - left
        val maxH = marginColumnMaxHeight(f, left, left + maxW)
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        val lo = f.top
        val hi = f.bottom - size.height
        if (hi < lo - NOTE_GEOM_EPS) return
        val top = (row.rect.center.y - size.height / 2f).coerceIn(lo, max(lo, hi))
        add(rowIndex, ring, stage, tier, f, left, top, size)
    }

    /** 正左：与正右对称（含规则第 6 条：页边距那一列最多折两行）。 */
    fun emitLeft(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, stage: Int, tier: Int) {
        val x = row.rect.left - metrics.sideGap
        val far = farExtra(marginX, tier)
        val right = min(x, f.right)
        val maxW = right - f.left - far
        val maxH = marginColumnMaxHeight(f, f.left + far, right)
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        val lo = f.top
        val hi = f.bottom - size.height
        if (hi < lo - NOTE_GEOM_EPS) return
        val top = (row.rect.center.y - size.height / 2f).coerceIn(lo, max(lo, hi))
        add(rowIndex, ring, stage, tier, f, right - size.width, top, size)
    }

    /** 正下：近侧 1em（竖直引线要从这段空档出去），下方按档位。 */
    fun emitBelow(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        // 近侧从**字墨迹下缘**算起（不是行盒下缘）：行盒底下那半段行距也是空白（§2.1）。
        // 最后一档把这 1em 也收到 0.1em，好让两行之间那段行距放得下一条批注。
        // 正下只认**横向压住锚点**的空白带：右边那一列是「右」，不是「下」
        // （否则右侧列空白一高，就会冒出「横向在页边距、却叫正下」的候选，把让位挤掉）。
        if (f.right <= anchor.left + NOTE_GEOM_EPS || f.left >= anchor.right - NOTE_GEOM_EPS) return
        val near = nearYRoom(tier)
        val y = row.inkBottom + near
        val far = farExtra(marginY, tier)
        val top = max(y, f.top)
        val maxH = f.bottom - far - top
        val lo = max(f.left, reserveX ?: f.left)
        val maxW = f.right - lo
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        val hi = f.right - size.width
        if (hi < lo - NOTE_GEOM_EPS) return
        val left = anchor.left.coerceIn(lo, max(lo, hi))
        add(rowIndex, ring, stage, tier, f, left, top, size)
    }

    /** 正上：与正下对称。 */
    fun emitAbove(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        // 近侧 1em 从字墨迹上缘算起（行盒顶上那半段行距也算空白）；横向与正下同理，要压住锚点。
        if (f.right <= anchor.left + NOTE_GEOM_EPS || f.left >= anchor.right - NOTE_GEOM_EPS) return
        val near = nearYRoom(tier)
        val y = row.inkTop - near
        val far = farExtra(marginY, tier)
        val bottom = min(y, f.bottom)
        val maxH = bottom - f.top - far
        val lo = max(f.left, reserveX ?: f.left)
        val maxW = f.right - lo
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        val hi = f.right - size.width
        if (hi < lo - NOTE_GEOM_EPS) return
        val left = anchor.left.coerceIn(lo, max(lo, hi))
        add(rowIndex, ring, stage, tier, f, left, bottom - size.height, size)
    }

    /** 右下：左缘在「末行第一个字 + 一个汉字」右侧，框顶在该行下方。 */
    fun emitBelowRight(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        val nearY = NOTE_MIN_MARGIN_PX
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val left = max(max(cornerRightX, f.left), reserveX ?: f.left)
        val top = max(row.rect.bottom + nearY, f.top)
        val maxW = f.right - farX - left
        val maxH = f.bottom - farY - top
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        add(rowIndex, ring, stage, tier, f, left, top, size)
    }

    /** 左下：右缘在「末行末字符 - 一个汉字」左侧，框顶在该行下方。 */
    fun emitBelowLeft(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, stage: Int, tier: Int) {
        val nearY = NOTE_MIN_MARGIN_PX
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val right = min(cornerLeftX, f.right)
        val top = max(row.rect.bottom + nearY, f.top)
        val maxW = right - f.left - farX
        val maxH = f.bottom - farY - top
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        add(rowIndex, ring, stage, tier, f, right - size.width, top, size)
    }

    /** 右上：横向基准与右下相同，框底在该行上方。 */
    fun emitAboveRight(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, stage: Int, tier: Int, reserveX: Float?) {
        val nearY = NOTE_MIN_MARGIN_PX
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val left = max(max(cornerRightX, f.left), reserveX ?: f.left)
        val bottom = min(row.rect.top - nearY, f.bottom)
        val maxW = f.right - farX - left
        val maxH = bottom - f.top - farY
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        add(rowIndex, ring, stage, tier, f, left, bottom - size.height, size)
    }

    /** 左上：横向基准与左下相同，框底在该行上方。 */
    fun emitAboveLeft(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, stage: Int, tier: Int) {
        val nearY = NOTE_MIN_MARGIN_PX
        val farX = farExtra(marginX, tier)
        val farY = farExtra(marginY, tier)
        val right = min(cornerLeftX, f.right)
        val bottom = min(row.rect.top - nearY, f.bottom)
        val maxW = right - f.left - farX
        val maxH = bottom - f.top - farY
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        add(rowIndex, ring, stage, tier, f, right - size.width, bottom - size.height, size)
    }

    fun emitBelowFamily(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        // 同一行左侧还有批注时，框左缘要让过它首字那一格（给它留引线空档）。
        val reserveX = leftReserve(rowIndex)
        // 正下的近侧也走三档：1em → 只收远侧 → 近侧也收到 0.1em（最后才用行距）。
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_NEAR_MIN) {
            val near = nearYRoom(tier)
            for (f in bandRects(row.rect.bottom + near, anchor.left, below = true)) {
                emitBelow(f, rowIndex, row, ring, stage, tier, reserveX)
            }
        }
        // 斜向（2026-10-25 用户定口）：横向基准就是「偏右 / 偏左一个字」（规则第 5 条），
        // 不再额外加 1em；近侧也不留 1em——贴着上一行 / 下一行放即可（那 1em 是规则第 7 条
        // 给上 / 下 / 左 / 右 摆法让给引线的）。留白档只剩「远侧」：先保 1em，不够收 0.1em。
        val near = NOTE_MIN_MARGIN_PX
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            val y = row.rect.bottom + near
            for (f in bandRects(y, cornerRightX, below = true)) {
                emitBelowRight(f, rowIndex, row, ring, stage, tier, reserveX)
            }
            for (f in bandRects(y, cornerLeftX, below = true)) {
                emitBelowLeft(f, rowIndex, row, ring, stage, tier)
            }
        }
    }

    fun emitAboveFamily(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val reserveX = leftReserve(rowIndex)
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_NEAR_MIN) {
            val near = nearYRoom(tier)
            for (f in bandRects(row.rect.top - near, anchor.left, below = false)) {
                emitAbove(f, rowIndex, row, ring, stage, tier, reserveX)
            }
        }
        // 斜向同 emitBelowFamily：横向偏一个字，近侧贴着行放，留白档只管远侧。
        val near = NOTE_MIN_MARGIN_PX
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            val y = row.rect.top - near
            for (f in bandRects(y, cornerRightX, below = false)) {
                emitAboveRight(f, rowIndex, row, ring, stage, tier, reserveX)
            }
            for (f in bandRects(y, cornerLeftX, below = false)) {
                emitAboveLeft(f, rowIndex, row, ring, stage, tier)
            }
        }
    }

    fun emitLeftFamily(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in columnRects(row.rect.left - metrics.sideGap, row.rect, toRight = false)) {
                emitLeft(f, rowIndex, row, ring, stage, tier)
            }
        }
    }

    /**
     * 第 n 环的**正右**：别的行右侧那块空白，对本行来说也是「右上 / 右下」
     * （2026-10-24 用户定口：上一行右侧对下一行就是右上角，凭什么不能用）。
     *
     * 与第 0 环同一个门槛：这一行右侧离版心右缘有 1em 以上空白，或者它是所在段的末行
     * （顶到版心右缘的段落只有末行后面才有空）。框在该行的垂直中线上。
     *
     * 规则第 9 条：跨行时只认右上 / 右下 / 左上 / 左下 / 上 / 下——**别的行的正右，对本行就是斜向**，
     * 所以侧别不能按「用哪个口子生成」定，落点由 [add] 按最终几何判定（见 `classify`）。
     */
    fun emitRightFamily(rowIndex: Int, ring: Int, stage: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val lastInBlock = rowIndex + 1 >= lines.size || lines[rowIndex + 1].blockId != row.blockId
        if (contentRight - row.rect.right < preferred && !lastInBlock) return
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in columnRects(row.rect.right + metrics.sideGap, row.rect, toRight = true)) {
                emitRight(f, rowIndex, row, ring, stage, tier)
            }
        }
    }

    if (scope == NoteCandidateScope.ALL || scope == NoteCandidateScope.NEAR) {
        // 第 0 环：锚点末行的右侧。2026-10-24 用户定口：**不再看是不是段末行**——只要这一行
        // 右侧离版心右缘还有 1em 以上空白，正右就是第一优先级（代码行 / 标题行这类左对齐的短行
        // 右边本来就是空白区，段中行也一样）。整行顶到版心右缘的行只保留原来的「段末行」入口：
        // 段落还有下一行时不试这一行的正右。
        emitRightFamily(base, 0, 0)
        // 规则第 4 条（2026-10-25 用户定口）：左侧就是首选时，它必须和正右一样进**本行这一批**——
        // 左边那条以前只在最后一批（本段左侧）生成，排序第二个键就是批次，永远轮不到它。
        if (leftFirst) emitLeftFamily(base, 0, 0)
        emitBelowFamily(base, 0, 0)
        emitAboveFamily(first, 0, 0)
        // 2026-10-25 用户定口：多行选区先贴**末行**（上面那一圈是末行侧）；末行那一圈放不下，
        // 才轮到**首行**那一圈——首行自己的正右（它右侧那片空白），以及它上方的正上 / 右上 / 左上。
        // 排序靠 `nearRank`（末行侧 0 < 首行侧 1），这里只负责把候选生成出来。
        if (first != base) emitRightFamily(first, 0, 0)
        // 规则第 5 条：**上一行和下一行的左右两侧属于本行相邻空间，不算隔行**。
        if (base + 1 <= runEnd) emitRightFamily(base + 1, 0, 0)
        if (first - 1 >= runStart) emitRightFamily(first - 1, 0, 0)
    }

    if (scope == NoteCandidateScope.ALL || scope == NoteCandidateScope.RINGS) {
        // 跨行：本段内由近到远（往下一条、上一条地数），每一圈都带上那一行自己的正右
        // （上一行右侧对下一行就是右上角，2026-10-24 用户定口）。
        // 分批时只算 `ringFrom..ringTo` 这几圈，调用方拿到够用的位置就不再往下要。
        // 第 k 圈 = 距基准行 **k + 1** 行：相邻行已经算在第 0 圈（规则第 5 条）。
        val lastRing = min(ringTo, lines.size)
        var k = max(1, ringFrom)
        while (k <= lastRing) {
            var any = false
            val down = base + k + 1
            val up = first - k - 1
            if (down <= runEnd) {
                emitBelowFamily(down, k, 0)
                emitRightFamily(down, k, 0)
                any = true
            }
            if (up >= runStart) {
                emitAboveFamily(up, k, 0)
                emitRightFamily(up, k, 0)
                any = true
            }
            if (!any) break
            k++
        }
    }

    if (scope == NoteCandidateScope.ALL || scope == NoteCandidateScope.TAIL) {
        // 本范围其余位置：左侧页边距最后才允许。
        //
        // 2026-10-25 用户定口：范围**到此为止**——占满整个版心的行已经把空白切断，
        // 再往下就是别的段落自己的空白，页边距上下相通也不算连成一片。
        for (idx in runStart..runEnd) {
            // 首选左侧时，基准行那一条已经在本行第一批里发过了，这里不再重复。
            if (leftFirst && idx == base) continue
            emitLeftFamily(idx, abs(idx - base), 1)
        }
    }

    return orderedNoteCandidates(out, rightTaken = false).take(maxCandidates)
}

/**
 * 一条批注的候选链顺序（§1.1）：**字典序**比较
 * 「段 → 跨行 → 首选一侧 → 折行数 → 方向 → 距离（斜向）/ 留白（正侧）→ 留白 → 距离」。
 *
 * **跨行在折行之前**（2026-10-25 用户定口，规则第 6 条：「其他位置都可以考虑折行，只要能放下，
 * 不考虑跨行」）：批注也是正文的一部分，宁可贴着锚点折成两行、三行，也不要为了少折一行
 * 就整个挪到隔行去。同一圈里仍然**折行数少的先**（2026-10-24 用户定口）——
 * 那条是「同一条行上哪个方向更省行数」，与「就近不跨行」不冲突。
 * 「整句放不放得下」排在段与跨行之前（规则第 9 / 11 条：折成省略号只是整段都放不下时的兜底）。
 *
 * **折行排在位置、距离之后**（2026-10-25 用户定口）：同一环里先看**首选那一侧**、方向名次、
 * 留白档与距离（位置好、离锚点近的赢），折行数只是最后才比的省钱项——
 * 宁可贴着锚点折两行，也不为了少折一行跑到隔几行的空档里去。
 *
 * **斜向（右上 / 右下 / 左上 / 左下）比距离、不比留白档**（2026-10-25 用户定口）：
 * 斜向横向就是规则第 5 条的「偏一个字」，纵向就是行距空档，那 1em 是规则第 7 条给
 * 上 / 下 / 左 / 右 摆法让给引线的——斜向为了凑 1em 把批注送到版心另一头，读者是能看见的
 * （真机上「函数体」的批注离开上一行文字的右缘只剩 0.1em，「使用 val」明明挨着段间距
 * 却飞到左边那条窄缝里）。正侧（右 / 下 / 上 / 左）仍按留白档优先（先要 1em，不够才收细缝）。
 *
 * @param rightTaken 该行右侧已经被别的旁注占住：正右让到「左」之前
 *   （倒数第二位，§0.1 0b），不再为了「更靠右」把框推远。
 * @param rightCornerFirst 规则第 8 条的例外（2026-10-25 用户定口）：本条右侧只有本行最右那条，
 *   且它已经放在本行正右 ⇒ 本条回到**右上 → 右下 → 左上 → 左下**的通常顺序，
 *   不再因为「会罩住右侧选区」先走左侧。
 */
internal fun noteCandidateOrder(
    rightTaken: Boolean,
    rightCornerFirst: Boolean = false,
): Comparator<NoteCandidate> {
    fun isCorner(side: NoteSide): Boolean = when (side) {
        NoteSide.ABOVE_RIGHT, NoteSide.BELOW_RIGHT,
        NoteSide.ABOVE_LEFT, NoteSide.BELOW_LEFT -> true
        else -> false
    }
    // 右侧没被占时 RIGHT 就是 0 号方向，和枚举顺序无关；被占时挪到 LEFT 之前。
    // 只压**本行**（环 0）的正右：别的行右侧那块空白仍然按正常名次参与（§0.1 0b）。
    fun rank(c: NoteCandidate): Int = when {
        // 例外：右侧只剩本行最右那条、且它已经在行右 ⇒ 四个斜向按通常顺序（右上最先）。
        rightCornerFirst && isCorner(c.side) -> noteSideRank(c.side)
        rightTaken && c.side == NoteSide.RIGHT && c.ring == 0 ->
            max(c.sideRank, NOTE_SIDE_RANK_TAKEN_RIGHT)
        else -> c.sideRank
    }
    // 规则第 9 / 11 条：**在本段落可用范围内，整句放得下（不折叠）永远是第一优先**，
    // 折成省略号只是整段都放不下时的兜底，所以「能不能整句放下」排得比段 / 环都前。
    return compareBy<NoteCandidate> { if (it.complete) 0 else 1 }
        .thenBy { it.stage }
        // 2026-10-25 用户定口：位置先按**末行**来，末行那一圈放不下才轮到首行那一圈。
        // 排在整句 / 折行段之后：整句优先（规则 9 / 11）不被位置推翻。
        .thenBy { it.nearRank }
        // 规则第 10 条：越过别的批注视同跨行，所以远近按「环 + 越过的行数」比。
        .thenBy { it.ring + it.crossed }
        // 规则 3 / 4（2026-10-25 用户定口）：同一环里先看是不是落在**首选那一侧**
        // （选区偏左就左侧、偏右就右侧）。
        .thenBy { if (rank(it) == NOTE_SIDE_RANK_SIDE_FIRST) 0 else 1 }
        .thenBy { rank(it) }
        // 斜向的留白档不参与先后（比距离），正侧仍然留白档优先。
        .thenBy { if (it.corner) 0 else it.marginTier }
        // **距离 / 位置是第一优先，折行不是大问题**（2026-10-25 用户定口）：
        // 宁可贴着锚点折两行，也不为了少折一行跑到隔几行的空档里（「在内 {}」那条长批注
        // 曾为了单行占到锚点上方隔两行的空档）。
        .thenBy { it.distance }
        .thenBy { it.marginTier }
        .thenBy { it.lines }
        .thenBy { it.rect.top }
        .thenBy { it.rect.left }
}

internal fun orderedNoteCandidates(
    all: List<NoteCandidate>,
    rightTaken: Boolean,
): List<NoteCandidate> = all.sortedWith(noteCandidateOrder(rightTaken))

/** 两个各自有序的候选链并成一条（分批发候选时用：新批只排自己，再线性并入）。 */
internal fun mergeOrderedCandidates(
    a: List<NoteCandidate>,
    b: List<NoteCandidate>,
    order: Comparator<NoteCandidate>,
): List<NoteCandidate> {
    if (b.isEmpty()) return a
    if (a.isEmpty()) return b
    val out = ArrayList<NoteCandidate>(a.size + b.size)
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        out += if (order.compare(a[i], b[j]) <= 0) a[i++] else b[j++]
    }
    while (i < a.size) out += a[i++]
    while (j < b.size) out += b[j++]
    return out
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
    /**
     * 这一行**字墨迹**的上下缘（缺省 = 行盒）：
     * 行盒上下各有半段行距，那一段是空白，批注可以贴进来（2026-10-24 用户定口，见 §2.1）。
     */
    val inkTop: Float = rect.top,
    val inkBottom: Float = rect.bottom,
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
    // 分阶段计时：真机上「一页十条批注」慢的时候，要知道是切空白、量候选、还是排位置慢。
    val placeMark = TimeSource.Monotonic.markNow()
    noteArrowRouteTries = 0
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
    val rawLines = obstacles.textLines.ifEmpty {        // 没有块序号时按「行距 > 行高 × 1.5」断段（§1.5 的退化路径）。
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
    // 每行的「字墨迹上下缘」：行盒上下那半段行距是空白，正上 / 正下 按字的位置量 1em（§2.1）。
    val lines = rawLines.map { line ->
        val ink = tightLineInk(line.rect, obstacles.arrowInk)
        line.copy(inkTop = ink.top, inkBottom = ink.bottom)
    }
    // 规则第 13 条：上下默认留白取「当前正文行距让出来的纯空白」（行盒节距 − 行盒高），
    // 压缩下限 2px。行距越大，批注与正文之间默认就隔得越开。
    val verticalGap = noteLineGap(lines, marginY)
    // 障碍仍是「文字墨迹（含列表圆点）+ 图形 + 表格 + 线条」：块底色 / padding / 页边距算空白。
    // **文字取字墨迹、不取行盒**（2026-10-24 用户定口）：行盒上下各有一段行距（≈10px），
    // 那一段本来就是空白，批注贴着字墨迹留 0.1em 就能放进去；行盒只有**带边框时**才算实心
    // （边框在 strokes 里，仍是硬障碍）。
    val textObstacles = tightTextInk(obstacles.textInk, obstacles.arrowInk)
    val baseObstacles = textObstacles + obstacles.figures + obstacles.tables + obstacles.strokes
    // 障碍按横向 / 纵向 margin 外扩后再切空白：框落在空白里即满足四周 0.1em 的间距（§2.2）。
    val inflatedObstacles = baseObstacles.map { inflateRect(it, marginX, marginY) }
    // 箭头避让另用逐字墨迹（能穿两字之间与标点前后）；没有逐字几何时才退回行框。
    val arrowGlyphs = obstacles.arrowInk.ifEmpty { obstacles.textInk }
    // 行盒（正文每一行的框）：竖着进时箭头停在它的边框上（引线规则第 4 条）。
    val lineBoxes = lines.map { it.rect }
    val arrowSolids = obstacles.figures + obstacles.tables + obstacles.strokes
    val freeRects = maximalFreeRects(
        bounds = blankBounds,
        obstacles = inflatedObstacles,
        minW = max(small * 1.2f, 1f),
        minH = lineH,
    )
    val freeMs = placeMark.elapsedNow().inWholeMilliseconds
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

    // 同一行上**在本条右边**那些批注的墨迹（2026-10-25 用户定口，规则第 8 条）：
    // 本条的框横向把这几个字整个罩住时，右上 / 右下就要让给左侧——否则两条批注的引线
    // 要在同一片空白里交叉，读者分不清哪条批注在说哪段字。
    val inkRows = ordered.associate { n ->
        n.annotationId to n.rects.mapNotNull { ink -> nearestLineIndex(ink, lines)?.let { ink to it } }
    }
    val rightInks = ordered.associate { n ->
        // 「本行」用布点的基准行（多行选区取末行）——和 [noteCoversRightText] 以及取位用的是同一行。
        val base = noteBaseRow(n.rects, lines) ?: inkRows.getValue(n.annotationId).minByOrNull { it.second }?.second
        val out = ArrayList<Rect>()
        if (base != null) {
            val myLeft = inkRows.getValue(n.annotationId)
                .filter { it.second == base }
                .minOfOrNull { it.first.left } ?: n.rects.minOf { it.left }
            for (m in ordered) {
                if (m.annotationId == n.annotationId) continue
                for ((ink, row) in inkRows.getValue(m.annotationId)) {
                    if (row == base && ink.left > myLeft + NOTE_GEOM_EPS) out += ink
                }
            }
        }
        n.annotationId to out
    }

    // 规则第 8 条（2026-10-25 用户定口）：这一条贴着自己那一行摆会罩住右侧那条的选区吗？
    // 每条只算一次（整句自然宽 + 基准行），候选分批发的时候直接复用这个结论。
    val coveringIds = ordered
        .filter { n ->
            noteCoversRightText(
                note = n,
                lines = lines,
                rightInk = rightInks.getValue(n.annotationId),
                metrics = metrics,
                charWidthPx = body,
                sizer = noteTextSizer,
            )
        }
        .mapTo(HashSet(ordered.size)) { it.annotationId }

    // 例外（2026-10-25 用户定口）：右侧那一片如果**只有本行最右那条**的选区，且它已经放在
    // 本行正右，本条就回到「右上优先」——右边那条的框贴着行右，不在这一带，两条引线不会打架。
    // 中间还夹着别的批注时不算（比如 boxes 与 of 之间还有 popcorn），仍按左上 → 左下。
    val rightmostOnRow = HashMap<Int, NoteInk>()
    for (n in ordered) {
        val base = noteBaseRow(n.rects, lines) ?: continue
        val key = noteRightOnRow(n.rects, lines, base)
        val cur = rightmostOnRow[base]
        val curKey = cur?.let { noteRightOnRow(it.rects, lines, base) }
        if (curKey == null || key > curKey + NOTE_GEOM_EPS ||
            (abs(key - curKey) <= NOTE_GEOM_EPS && n.annotationId > cur!!.annotationId)
        ) {
            rightmostOnRow[base] = n
        }
    }
    val coveringRightmost = ordered.mapNotNull { n ->
        if (n.annotationId !in coveringIds) return@mapNotNull null
        val base = noteBaseRow(n.rects, lines) ?: return@mapNotNull null
        val rightmost = rightmostOnRow[base] ?: return@mapNotNull null
        if (rightmost.annotationId == n.annotationId) return@mapNotNull null
        val myRight = rightInks.getValue(n.annotationId)
        val theirRight = inkRows.getValue(rightmost.annotationId)
            .filter { it.second == base }
            .map { it.first }
        val onlyRightmost = myRight.size == theirRight.size &&
            myRight.all { r -> theirRight.any { it == r } }
        if (onlyRightmost) n.annotationId to rightmost.annotationId else null
    }.toMap()

    // 分批发候选：先「本行相邻空间」，再一圈一圈跨行，最后才本段左侧 / 相邻段。
    // 放不下才继续要下一批；排序键不变，所以「拿到够用的就停」与一次全量生成结果相同。
    fun generate(
        note: NoteInk,
        scope: NoteCandidateScope,
        ringFrom: Int = 1,
        ringTo: Int = Int.MAX_VALUE,
        completeOnly: Boolean = true,
    ): List<NoteCandidate> = noteCandidates(
        note = note,
        lines = lines,
        freeRects = freeRects,
        metrics = metrics,
        sizer = noteTextSizer,
        // 「一个汉字」按正文字号算：斜向摆法要据此把框挪开首 / 末字符。
        charWidthPx = body,
        otherRowLeft = otherRowLefts.getValue(note.annotationId),
        // 同一行右侧那几条的墨迹：本条整句宽会罩住它们时，先走左侧（规则 8）。
        rightInk = rightInks.getValue(note.annotationId),
        covering = note.annotationId in coveringIds,
        // 版心右缘：判断「这一行右侧还有没有 1em 以上空白」，决定正右是否提到最前（规则 3）。
        contentRight = contentRight,
        // 版心左缘：右侧没余地时，看这一行左侧有没有明显缩进，决定正左是否提到最前（规则 4）。
        contentLeft = contentLeft,
        // 上下默认留白（规则 13）：正文行距让出来的那段纯空白。
        verticalGap = verticalGap,
        scope = scope,
        ringFrom = ringFrom,
        ringTo = ringTo,
        completeOnly = completeOnly,
    )

    val candidatesByNote = HashMap<Long, List<NoteCandidate>>(ordered.size)
    for (note in ordered) {
        val list = generate(note, NoteCandidateScope.NEAR)
        if (list.isNotEmpty()) candidatesByNote[note.annotationId] = list
    }

    val candMs = placeMark.elapsedNow().inWholeMilliseconds - freeMs
    // 每条批注下一批要算第几圈：1 → 2 → … → 段内最后一行 → 收尾（左侧 / 相邻段）。
    val nextRing = HashMap<Long, Int>(ordered.size)
    fun nextBatch(note: NoteInk): List<NoteCandidate>? {
        val id = note.annotationId
        val k = nextRing[id] ?: 1
        if (k <= lines.size) {
            nextRing[id] = k + 1
            return generate(note, NoteCandidateScope.RINGS, ringFrom = k, ringTo = k)
        }
        if (k != Int.MAX_VALUE) {
            nextRing[id] = Int.MAX_VALUE
            return generate(note, NoteCandidateScope.TAIL)
        }
        return null
    }

    // 收尾部分一次性取完（跨行已经全部试过之后才轮到它）。
    fun restBatch(note: NoteInk): List<NoteCandidate> =
        generate(note, NoteCandidateScope.RINGS) + generate(note, NoteCandidateScope.TAIL)

    // 截断档才要的候选：整句注定放不下的那些，到这一步才真排版（一条批注只排一次）。
    fun truncatedBatch(note: NoteInk): List<NoteCandidate> =
        generate(note, NoteCandidateScope.ALL, completeOnly = false)

    // 整句在最宽的空白里都放不下一行时，「最少行数」这一档根本不可能命中，
    // 就别一圈一圈试了：一次取完，直接走折行档。
    val maxFreeWidth = freeRects.maxOfOrNull { it.width } ?: 0f
    fun minLinesReachable(note: NoteInk): Boolean {
        val natural = noteTextSizer.naturalWidth(note.text.trim(), metrics.em)
        return natural > 0f && natural <= maxFreeWidth - metrics.padTotal
    }

    // 三档推进（整句 → 截断 → 小椭圆）：同一行从右到左逐条独立取位，
    // 整句档全部放不下时才让既有批注上下让位（§1.4 / §5）。
    val choices = assignNoteCandidates(
        ordered, lines, candidatesByNote, marginX, marginY, metrics, noteTextSizer,
        nextBatch = ::nextBatch,
        restBatch = ::restBatch,
        truncatedBatch = ::truncatedBatch,
        minLinesReachable = ::minLinesReachable,
        covering = coveringIds,
        rightmostOnRight = coveringRightmost,
    )
    val assignMs = placeMark.elapsedNow().inWholeMilliseconds - freeMs - candMs
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
    val routed = out.map { b ->
        // 小图标形态没有可指向的框，本来就不画箭头（§2.1）。
        if (b.compact) return@map b
        // 箭头用逐字墨迹（能穿两字之间）；布点仍用行框。
        val ink = inkById[b.annotationId]?.arrowInk.orEmpty()
        val self = bubbleRects.getValue(b.annotationId)
        // 引线规则第 10 条（2026-10-25 用户定口）：位置定下来后，比一比框离**选区开头**近
        // 还是离**选区末尾**近——近哪头就指哪一头，不再固定指「决定落位的那一行」。
        val anchorRow = noteArrowRow(
            bubble = self,
            ink = ink,
            lines = lines,
            fallbackRow = chosen[b.annotationId]?.candidate?.anchorRow ?: -1,
        )
        val targetInk = if (anchorRow in lines.indices) {
            ink.filter { nearestLineIndex(it, lines) == anchorRow }
        } else {
            emptyList()
        }
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
            // 行盒：竖着进时箭头停在行盒边框上，不侵入行盒内部（引线规则第 4 条）。
            lineBoxes = lineBoxes,
            targetInk = targetInk,
        )
        if (arrow != null) drawn += arrow
        b.copy(arrow = arrow)
    }
    Logger.d(
        "Orilumn.NOTE",
        "NOTE-PLACE notes=${ordered.size}" +
            " free=$freeMs cand=$candMs assign=$assignMs" +
            " route=${placeMark.elapsedNow().inWholeMilliseconds - freeMs - candMs - assignMs}" +
            " tries=$noteArrowRouteTries" +
            " total=${placeMark.elapsedNow().inWholeMilliseconds}",
    )
    return routed
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
 * **正文行自上而下 → 同一行内从左到右 → 批注 id**。
 * 「同一行」按**基准行**算——多行选区取所选文字的**末行**（§0.1），不是首行。
 *
 * 唯一例外（2026-10-25 用户定口，规则第 8 条）：**会遮住右侧文字区域的那条排在右侧那几条之后**。
 * 它整句宽会把右边那条的选区整个罩住时，贴着本行右侧那片空白是**右边那条的**——先让右边那条
 * 拿到自己文字旁边的位置，罩人的这条再去左侧找；左侧没有，就自己往下让，不许把右边那条挤走。
 *
 * 每一条批注**只按自己的阶梯独立取位**（2026-10-24 用户定口：取消同行成组 / 统一收列）：
 * 不再把同一行的批注搬到右侧同一列，也不做横向滑移或「躲到旁边重新折行」。
 *
 * **垂直让位是最后的相邻手段**（2026-10-24 用户定口）：这条批注的整句候选全部试完仍放不下，
 * 才逐个候选去问「占住同一块空白的既有批注能不能上下让一让」。同一行右侧的既有批注、
 * 以及别的行用同一块空白的既有批注都可以让；只移动**刚好够放下新框**的距离，
 * 让完仍越界 / 撞上别的批注 / 仍然放不下新框，就**不让**，保留原位置。
 * 会遮住右侧文字区域的那条**不搬别人**：它就是来让位的，自己继续往远处找。
 *
 * 已选框立即成为后选框的障碍（四周 0.1em），先挑者不会被无故挤走；不做两两互换，顺序可复现。
 */
private fun assignNoteCandidates(
    notes: List<NoteInk>,
    lines: List<NoteTextLine>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    marginX: Float,
    marginY: Float,
    metrics: NoteMetrics,
    sizer: NoteTextSizer,
    /** 本行附近放不下时才调：取下一批候选（一圈跨行，或收尾档）；`null` = 没有更多了。 */
    nextBatch: (NoteInk) -> List<NoteCandidate>? = { null },
    /** 一次性取完剩余候选（跨行全量 + 收尾）：只在「最少行数注定不可达」时用。 */
    restBatch: (NoteInk) -> List<NoteCandidate> = { emptyList() },
    /**
     * 截断档要用的候选（整句注定放不下的那些，整句档没量过）：真走到这一档才补进来。
     * 整句档已经放好的批注因此一次都不量它们——真机上这是省下来的大头。
     */
    truncatedBatch: (NoteInk) -> List<NoteCandidate> = { emptyList() },
    /** 这句话在本页最宽的空白里能不能排成「最少行数」（不能就别逐圈试了）。 */
    minLinesReachable: (NoteInk) -> Boolean = { true },
    /** 会遮住右侧文字区域的那几条：排在同排右侧那几条之后，且不搬动别人（规则第 8 条）。 */
    covering: Set<Long> = emptySet(),
    /** 例外（规则第 8 条）：本条右侧只有本行最右那条；值 = 那条的 id，等它落在本行正右后回到右上优先。 */
    rightmostOnRight: Map<Long, Long> = emptyMap(),
): List<NoteChoice> {
    if (notes.isEmpty() || lines.isEmpty()) return emptyList()
    val chosen = LinkedHashMap<Long, NoteCandidate>()
    val occupied = ArrayList<NoteChoice>()
    /** 已经试过「让别人让位」但让不动的候选（结论只会在已放批注变多时更差）。 */
    val roomTried = HashSet<NoteCandidate>()
    // 候选池：先装「本行附近」，某条批注放不下时才把它的远处候选并进来。
    // 用可变列表 + 增量合并：逐圈要候选时不能每来一批就把整池重排一遍。
    val pool = HashMap<Long, ArrayList<NoteCandidate>>(candidatesByNote.size)
    for ((id, list) in candidatesByNote) pool[id] = ArrayList(list)

    /** 往这条批注的候选池里追加一批（保持追加顺序，排序交给 [chainFor] 增量合并）。 */
    fun addBatch(note: NoteInk, batch: List<NoteCandidate>) {
        if (batch.isEmpty()) return
        pool.getOrPut(note.annotationId) { ArrayList() }.addAll(batch)
    }

    fun fits(c: NoteCandidate): Boolean =
        occupied.none { c.conflicts(it.candidate, marginX, marginY) }

    /**
     * 撞上已放批注时，**让自己沿远离锚点的方向挪开**（2026-10-25 用户定口，规则第 8 条）。
     *
     * 此前撞了就丢掉这个候选，于是同一块很高的空白里放下第一条之后，第二条只能跨行去别处
     * （真机上「讨论」越过两行批注、飞到下面）。挪开时先按 **2 倍上下 margin** 让开，
     * 空间不够压到 1 倍，最后才是 2px：前后两条批注之间的缝要一样宽，不能一条贴着上沿、
     * 一条贴着下缘。挪不出（出了自己的空白）就算这个候选没位置。
     *
     * @return 挪好且不撞任何已放批注的候选；原位置就能放下、或挪不动时为 `null`。
     */
    fun slideFree(note: NoteInk, c: NoteCandidate): NoteCandidate? {
        val dy = when (c.side) {
            NoteSide.ABOVE, NoteSide.ABOVE_RIGHT, NoteSide.ABOVE_LEFT -> -1f
            NoteSide.BELOW, NoteSide.BELOW_RIGHT, NoteSide.BELOW_LEFT -> 1f
            else -> 0f
        }
        // 正右 / 正左那一列是「同一列上下叠放」（规则第 8 条讲的就是上下让位）：
        // 横向挪开就变成两条并排，读者会以为它们在说不同的行，所以这里只让上下 / 斜向自己挪。
        if (dy == 0f) return null
        val f = c.freeRect
        for (scale in floatArrayOf(2f, 1f, 0f)) {
            val gapY = if (scale == 0f) NOTE_MIN_MARGIN_PX else marginY * scale
            var cur = c
            var dead = false
            for (step in 0 until NOTE_SLIDE_STEPS) {
                val hit = occupied.filter { cur.conflicts(it.candidate, marginX, marginY) }
                if (hit.isEmpty()) break
                val shiftY = if (dy > 0f) {
                    hit.maxOf { it.candidate.rect.bottom } + gapY - cur.rect.top
                } else {
                    hit.minOf { it.candidate.rect.top } - gapY - cur.rect.bottom
                }
                // 只许**远离**锚点：算出来的位移方向反了，说明这条候选本来就不该往这边挪。
                if (shiftY * dy < -NOTE_GEOM_EPS) {
                    dead = true
                    break
                }
                val r = Rect(cur.rect.left, cur.rect.top + shiftY, cur.rect.right, cur.rect.bottom + shiftY)
                if (r.top < f.top - NOTE_GEOM_EPS || r.bottom > f.bottom + NOTE_GEOM_EPS) {
                    dead = true
                    break
                }
                cur = cur.copy(rect = r, distance = note.rects.minOf { rectDistance(r, it) })
            }
            if (dead) continue
            if (fits(cur)) return if (cur.rect == c.rect) null else cur
        }
        return null
    }

    fun take(note: NoteInk, c: NoteCandidate): Boolean {
        val spot = if (fits(c)) c else slideFree(note, c) ?: return false
        chosen[note.annotationId] = spot
        occupied += NoteChoice(note, spot)
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
        val rightCands = pool[note.annotationId].orEmpty()
            .filter { it.side == NoteSide.RIGHT && it.ring == 0 }
        if (rightCands.isEmpty()) return false
        return rightCands.any { rc -> occupied.any { rc.conflicts(it.candidate, marginX, marginY) } }
    }

    /**
     * 按「段 → 折行数 → 环 → 方向 → 留白档 → 距离」取这条批注的候选链。
     *
     * 逐圈要候选时池子会一批批变大，这里只把**新进来的那批**排序，再线性并进已排好的链：
     * 每批都整池重排会让「一圈一圈试」比一次全量还慢。
     * 缓存按「已并到第几个 + 正右是否被占」校验，后者会随已放批注变化。
     */
    val chainCache = HashMap<Long, List<NoteCandidate>>(notes.size)
    val chainMerged = HashMap<Long, Int>(notes.size)
    val chainTaken = HashMap<Long, Boolean>(notes.size)
    val chainRightCorner = HashMap<Long, Boolean>(notes.size)
    fun chainFor(note: NoteInk): List<NoteCandidate> {
        val all = pool[note.annotationId].orEmpty()
        val id = note.annotationId
        val taken = rightTaken(note)
        // 规则第 8 条例外：右侧只剩本行最右那条、且它已经在行右 ⇒ 本条右上优先。
        val rightCornerFirst = rightmostOnRight[id]?.let { owner ->
            chosen[owner]?.let { it.side == NoteSide.RIGHT && it.ring == 0 } == true
        } ?: false
        val cached = chainCache[id]
        val merged = chainMerged[id] ?: 0
        if (cached != null && merged == all.size && chainTaken[id] == taken &&
            chainRightCorner[id] == rightCornerFirst
        ) {
            return cached
        }
        val order = noteCandidateOrder(taken, rightCornerFirst)
        val next = if (cached != null && chainTaken[id] == taken &&
            chainRightCorner[id] == rightCornerFirst && merged <= all.size
        ) {
            mergeOrderedCandidates(
                cached,
                all.subList(merged, all.size).sortedWith(order),
                order,
            )
        } else {
            all.sortedWith(order)
        }
        chainCache[id] = next
        chainMerged[id] = all.size
        chainTaken[id] = taken
        chainRightCorner[id] = rightCornerFirst
        return next
    }

    /** 两块空白是不是同一块 / 同一列（只要有一点重叠就算，跨行批注也认同一列）。 */
    fun sharedBlank(a: Rect, b: Rect): Boolean =
        min(a.right, b.right) - max(a.left, b.left) > 1f &&
            min(a.bottom, b.bottom) - max(a.top, b.top) > 1f

    /**
     * 让占住同一块空白的既有批注**上下让一让**，给 [c] 腾出位置。
     *
     * 2026-10-24 用户定口：
     * - **让位排在「隔行」之前**：本行（环 0）的位置只要别人让一让就能放下，就先让别人让，
     *   而不是直接跑去隔一行 / 隔两行的地方（调用方只在 `ring == 0` 时问这一句）。
     * - **让完要真能把新批注放下**，包括新批注**折行**与**不折行**两种：把挡路框推到这块空白的
     *   上缘或下缘，剩下的那条空档里重新折行夹取整句；实在夹不下就是「无路可让」。
     * - 只认整句档、只对同一列的正右 / 正左（竖排让位）；挡路框要么在空白上缘要么在下缘。
     * - 上 / 下两种排法都可行时取位移小的（一样大取上移），保证同一输入结果恒定。
     *
     * @return 让位成功并放下 [c] 时为 `true`；让不出空间或让完仍放不下则为 `false`（不改任何位置）。
     */
    fun makeRoom(note: NoteInk, c: NoteCandidate): Boolean {
        if (!c.complete) return false
        // 规则第 8 条（2026-10-25 用户定口）：会遮住右侧文字区域的那条**自己是来让位的**，
        // 不许反过来把右侧那条挤走——它继续往远处找位置，直到不挡别人的选区。
        if (note.annotationId in covering) return false
        // 规则第 8 条：先让挡路的批注挪一挪。**上下 / 斜向**摆的框，挡路框是竖着排的，往上下让；
        // **正上 / 正下**摆的框，挡路框是横着排的，往左右让。
        // 2026-10-25 用户定口：斜向也让位——同一行左边那条被往上挤一点、右边那条占到它原来的格子，
        // 引线不会交叉，可以接受。
        val verticalShift = c.side != NoteSide.ABOVE && c.side != NoteSide.BELOW
        // 2026-10-25 用户定口：**不再要求挡路框和新框在同一块空白**——挡路框只要挪完还在
        // 它自己的空白里就行（下面的校验逐个检查），隔壁空白里那条也该让一让。
        val blockers = occupied.filter { c.conflicts(it.candidate, marginX, marginY) }
        if (blockers.isEmpty()) return false
        // 行自上而下的硬顺序（2026-10-24 用户定口）：后面那行的批注不许把前面那行已放好的挤走。
        // 否则上一行的批注会被拖到下一行旁边，读者会以为它说的是下一行（引线还要多绕一截）。
        val myRow = baseRow(note)
        if (blockers.any { baseRow(it.note) < myRow }) return false
        val blockerIds = blockers.map { it.note.annotationId }.toSet()
        val f = c.freeRect

        /** 把挡路框整体挪 (dx, dy)，返回它们的新位置。 */
        fun shiftBlockers(dx: Float, dy: Float): List<NoteChoice> = blockers.map { b ->
            val r = b.candidate.rect
            val movedRect = Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
            NoteChoice(
                b.note,
                b.candidate.copy(
                    rect = movedRect,
                    distance = b.note.rects.minOf { rectDistance(movedRect, it) },
                ),
            )
        }

        /**
         * 收尾校验：让位后每个框都要还在**它自己的空白**里、彼此不撞、不撞没让位的框、
         * 也不撞新框；新框同样不许和没让位的框相撞。
         */
        fun accept(
            delta: Float,
            moved: List<NoteChoice>,
            fresh: NoteCandidate,
        ): Pair<Float, Pair<List<NoteChoice>, NoteCandidate>>? {
            for (m in moved) {
                val mf = m.candidate.freeRect
                val mr = m.candidate.rect
                if (mr.left < mf.left - NOTE_GEOM_EPS || mr.right > mf.right + NOTE_GEOM_EPS ||
                    mr.top < mf.top - NOTE_GEOM_EPS || mr.bottom > mf.bottom + NOTE_GEOM_EPS
                ) {
                    return null
                }
                if (m.candidate.conflicts(fresh, marginX, marginY)) return null
            }
            for (i in moved.indices) {
                for (j in i + 1 until moved.size) {
                    if (moved[i].candidate.conflicts(moved[j].candidate, marginX, marginY)) return null
                }
                for (o in occupied) {
                    if (o.note.annotationId in blockerIds) continue
                    if (moved[i].candidate.conflicts(o.candidate, marginX, marginY)) return null
                }
            }
            for (o in occupied) {
                if (o.note.annotationId in blockerIds) continue
                if (fresh.conflicts(o.candidate, marginX, marginY)) return null
            }
            return abs(delta) to (moved to fresh)
        }

        /**
         * 竖排让位：挡路框**只挪开刚好够的那一点**（让出新框这一条带），不推到空白边缘。
         *
         * 2026-10-25 用户定口：让位是为了腾地方，不是把别人的批注甩到页面另一头——尤其是
         * 空白很大时，「推到边缘」会把一条批注从本行弹到页顶。
         */
        fun attemptVertical(up: Boolean): Pair<Float, Pair<List<NoteChoice>, NoteCandidate>>? {
            val top = blockers.minOf { it.candidate.rect.top }
            val bottom = blockers.maxOf { it.candidate.rect.bottom }
            // 规则第 8 条（2026-10-25 用户定口）：一次让出「新批注 + **2 倍**上下 margin」，
            // 别让让位后的新旧两条挤在一起；实在挤不出 2 倍，会先由 `slideFree` 均匀压缩
            // 新批注自己的上下 margin（2 倍 → 1 倍 → 2px），再轮到挪动别人的批注。
            val dy = if (up) c.rect.top - 2f * marginY - bottom else c.rect.bottom + 2f * marginY - top
            if (abs(dy) <= NOTE_GEOM_EPS) return null
            return accept(abs(dy), shiftBlockers(0f, dy), c)
        }


        /** 横排让位：挡路框横着让开刚好够的那一点（正上 / 正下那一档用）。 */
        fun attemptHorizontal(toLeft: Boolean): Pair<Float, Pair<List<NoteChoice>, NoteCandidate>>? {
            val left = blockers.minOf { it.candidate.rect.left }
            val right = blockers.maxOf { it.candidate.rect.right }
            val dx = if (toLeft) c.rect.left - 2f * marginX - right else c.rect.right + 2f * marginX - left
            if (abs(dx) <= NOTE_GEOM_EPS) return null
            return accept(abs(dx), shiftBlockers(dx, 0f), c)
        }

        /**
         * 规则第 8 条后半：**上下让不动时，看挡路框右侧还有没有地方**。
         *
         * 只认同一个空白里、位于所有挡路框右缘之外的那条竖带；新框仍夹在原本想待的高度
         * （本行垂直居中），并且不许撞任何已放批注——这一档**不搬动别人**。
         */
        fun attemptBeside(): Pair<Float, Pair<List<NoteChoice>, NoteCandidate>>? {
            val left = blockers.maxOf { it.candidate.rect.right } + marginX
            val strip = Rect(left, f.top, f.right, f.bottom)
            if (strip.width <= 1f || strip.height <= 1f) return null
            // 高度最多放到原候选的两倍：再高就不是「挪到旁边」而是一整条竖柱了。
            val maxH = min(strip.height, c.rect.height * 2f)
            val size = noteBoxFit(note.text, metrics, strip.width, maxH, sizer)
            if (!size.complete) return null
            val top = c.rect.top.coerceIn(strip.top, max(strip.top, strip.bottom - size.height))
            val rect = Rect(strip.left, top, strip.left + size.width, top + size.height)
            if (rect.right > f.right + NOTE_GEOM_EPS || rect.bottom > f.bottom + NOTE_GEOM_EPS) return null
            val fresh = c.copy(
                rect = rect,
                lines = size.lines,
                complete = true,
                shownChars = size.shownChars,
                distance = note.rects.minOf { rectDistance(rect, it) },
            )
            if (occupied.any { fresh.conflicts(it.candidate, marginX, marginY) }) return null
            // 名次排在所有「上下让位」之后：能让人家让就让，实在让不动才自己挪到旁边。
            return Float.MAX_VALUE / 2f to (emptyList<NoteChoice>() to fresh)
        }

        val tried = if (verticalShift) {
            // 「往挡路框右侧找空」只认正右 / 正左（规则第 8 条后半讲的就是右侧那一条）。
            val beside = if (c.side == NoteSide.RIGHT || c.side == NoteSide.LEFT) attemptBeside() else null
            listOfNotNull(attemptVertical(up = true), attemptVertical(up = false), beside)
        } else {
            listOfNotNull(attemptHorizontal(toLeft = true), attemptHorizontal(toLeft = false))
        }
        val pick = tried.minWithOrNull(compareBy({ it.first })) ?: return false
        // **先判后动**：让位失败时不能把已放批注挪到半路。
        for (m in pick.second.first) {
            chosen[m.note.annotationId] = m.candidate
            val idx = occupied.indexOfFirst { it.note.annotationId == m.note.annotationId }
            if (idx >= 0) occupied[idx] = m
        }
        chosen[note.annotationId] = pick.second.second
        occupied += NoteChoice(note, pick.second.second)
        return true
    }

    /**
     * 从当前候选池里按顺序取一条能放下的位置。
     *
     * @param minOnly 只考虑「最少行数」的候选（不折行，或正好等于段落数）：
     *   排序键里折行数排在跨行数之前，所以更远的、不折行的位置应当压过更近的折行位置，
     *   只有把这一档走完才轮到折行。
     * @return 放好（或被让位成功）时为 `true`。
     */
    fun placeComplete(note: NoteInk, minOnly: Boolean): Boolean {
        val minLines = note.text.trim().count { it == '\n' } + 1
        for (c in chainFor(note)) {
            if (!c.complete) continue
            if (minOnly && c.lines != minLines) continue
            if (take(note, c)) return true
            // 让位排在隔行之前：本行（ring 0）只差别人让一让就先让别人让。
            // 已经试过让不动的就别再试：逐圈补候选时同一批环 0 候选会被反复扫到，
            // 而池子只会变长、已放批注只会变多，让不动的结论不会翻案。
            if (c.ring == 0 && roomTried.add(c) && makeRoom(note, c)) return true
        }
        return false
    }

    /**
     * 两阶段的第一阶段（2026-10-24 用户定口，规则第 2 条的唯一例外）：
     * 本行**最右那条**批注先试正右；占下来之后，本行其余批注从左到右取位时，
     * 正右已经被它占住，自然不会抢。
     *
     * 只认「正右 + 本行 + 整句」：其余情况仍按规则顺序，交给正常流程。
     */
    fun tryRightFirst(note: NoteInk): Boolean {
        var roomTriedHere = false
        for (c in chainFor(note)) {
            // 只认「规则第 3 条提前过的正右」：这一行右侧确实有 1em 以上空白才值得先占。
            // 只剩页边距那一窄列（规则第 6 条）不算——那种列该排在六个方向之后，
            // 不能因为「本行最右」就先把批注塞进去。
            if (c.side != NoteSide.RIGHT || c.ring != 0 || !c.complete) continue
            // 这一行右侧确实有 1em 以上空白才值得先占：正右要么是首选（-2），要么是次选（-1，
            // 另一侧因缩进更空）。左右互换首选之后仍要认后者，否则「本行最右那条先占正右」
            // 会失效，右侧被左边那条抢走（2026-10-25 用户指出）。
            if (c.sideRank != NOTE_SIDE_RANK_SIDE_FIRST &&
                c.sideRank != NOTE_SIDE_RANK_SIDE_SECOND
            ) {
                continue
            }
            if (take(note, c)) return true
            // 规则第 8 条：右侧被别人占了，先让别人上下让一让。
            if (!roomTriedHere) {
                roomTriedHere = true
                if (makeRoom(note, c)) return true
            }
        }
        return false
    }

    /**
     * 按「最少行数」档取位：一圈一圈要候选，拿到能放下的就停。
     *
     * 为什么可以停：排序键里折行数排在跨行数之前，所以只要手里已经有了
     * 「最少行数（无换行时 1 行，有 `\n` 时按段落数）且放得下」的候选，
     * 更远的圈不可能更好。停之前必须确认它**真能放下**（不与已放批注冲突），
     * 否则继续要下一圈。
     *
     * @return 放好时为 `true`；所有批次都试完仍没有，返回 `false`。
     */
    fun placeMinLines(note: NoteInk): Boolean {
        if (!minLinesReachable(note)) {
            // 注定排不出一行：逐圈试纯属浪费，一次取完直接进折行档。
            addBatch(note, restBatch(note))
            return false
        }
        while (true) {
            if (placeComplete(note, minOnly = true)) return true
            val batch = nextBatch(note) ?: return false
            addBatch(note, batch)
        }
    }

    // 先挑顺序（规则第 1 / 2 条）：正文行自上而下 → **同一行从左到右** → 批注 id。
    // 例外（2026-10-25 用户定口，规则第 8 条）：会遮住右侧文字区域的那条排到同排最后，
    // 让右边那条先拿走自己文字旁边的位置；它再走左侧 / 往下让。
    val order = notes.sortedWith(
        compareBy<NoteInk> { baseRow(it) }
            .thenBy { if (it.annotationId in covering) 1 else 0 }
            .thenBy { noteRightOnRow(it.rects, lines, baseRow(it)) }
            .thenBy { it.annotationId },
    )

    // 第 1 档：整句。逐行走（自上而下），行内从左到右，每条按自己的阶梯独立取位；
    // **让位排在隔行之前**（2026-10-24 用户定口）：本行的候选只差别人让一让，就先让别人让，
    // 而不是直接跑到隔一行、隔两行的地方去。让位只在 `ring == 0` 问。
    var start = 0
    while (start < order.size) {
        val rowIndex = baseRow(order[start])
        var end = start + 1
        while (end < order.size && baseRow(order[end]) == rowIndex) end++
        val members = order.subList(start, end)
        // 规则第 2 条的唯一例外：本行**最右那条**先试正右（它右侧那块空白先归它），
        // 之后再从左到右给本行其余批注取位。「最右」要与别人拉开（同一条选区的几条不算），
        // 否则谁先谁后不该因此改变。遮挡右侧的那条排在最后，这里不能拿 `last` 当最右。
        if (members.size > 1) {
            val rightmost = members.maxWith(
                compareBy({ noteRightOnRow(it.rects, lines, rowIndex) }, { it.annotationId }),
            )
            val rightmostRight = noteRightOnRow(rightmost.rects, lines, rowIndex)
            val others = members
                .filter { it.annotationId != rightmost.annotationId }
                .maxOf { noteRightOnRow(it.rects, lines, rowIndex) }
            if (rightmostRight > others + NOTE_GEOM_EPS) tryRightFirst(rightmost)
        }
        for (note in members) {
            if (chosen.containsKey(note.annotationId)) continue
            // 先按「最少行数」一圈一圈要位置，拿到就停；要不到才允许折行。
            if (placeMinLines(note)) continue
            // 整句但必须折行：仍在整句档内（不折叠），只是行数多于最少行数。
            placeComplete(note, minOnly = false)
        }
        start = end
    }
    // 第 2 档：截断（候选里已保证 readable），同一顺序再走一遍；不再搬动别人。
    // 整句注定放不下的候选到这一步才补量（整句档放好的批注一次都没量过它们）。
    for (note in order) {
        if (chosen.containsKey(note.annotationId)) continue
        addBatch(note, truncatedBatch(note))
        chainFor(note).firstOrNull { take(note, it) }
    }
    return order.mapNotNull { note -> chosen[note.annotationId]?.let { NoteChoice(note, it) } }
}

/**
 * 规则第 13 条：上下默认留白 = **当前正文行距让出来的纯空白**（行盒节距 − 行盒高）。
 *
 * 取同段相邻行的节距中位数与行盒高中位数，避免一两个大标题把整页带偏；
 * 量不出来（单行页、行重叠）时退回 [fallback]，并且无论如何不小于 2px。
 */
private fun noteLineGap(lines: List<NoteTextLine>, fallback: Float): Float {
    val pitches = ArrayList<Float>()
    for (i in 1 until lines.size) {
        val up = lines[i - 1]
        val down = lines[i]
        if (up.blockId != down.blockId) continue
        val pitch = down.rect.top - up.rect.top
        if (pitch > 0f) pitches += pitch
    }
    val floor = max(NOTE_MIN_MARGIN_PX, fallback)
    if (pitches.isEmpty()) return floor
    pitches.sort()
    val heights = lines.map { it.rect.height }.sorted()
    val gap = pitches[pitches.size / 2] - heights[heights.size / 2]
    return max(floor, gap)
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
 * 引线该指哪一行（引线规则第 10 条，2026-10-25 用户定口）。
 *
 * 框的位置定下来以后，比一比框离**选区开头**（首字左上角）近、还是离**选区末尾**
 * （末字右下角）近：近开头就指首行，近末尾就指末行。单行选区不用挑，仍是它自己那一行；
 * 逐字几何量不出来时退回 [fallbackRow]（哪一行决定落位就指哪一行，旧口径）。
 */
internal fun noteArrowRow(
    bubble: Rect,
    ink: List<Rect>,
    lines: List<NoteTextLine>,
    fallbackRow: Int = -1,
): Int {
    if (ink.isEmpty() || lines.isEmpty()) return fallbackRow
    val rows = ink.mapNotNull { nearestLineIndex(it, lines) }.distinct()
    if (rows.size <= 1) return rows.firstOrNull() ?: fallbackRow
    val start = ink.minWithOrNull(compareBy({ it.top }, { it.left })) ?: return fallbackRow
    val end = ink.maxWithOrNull(compareBy({ it.bottom }, { it.right })) ?: return fallbackRow
    val startRow = nearestLineIndex(start, lines) ?: return fallbackRow
    val endRow = nearestLineIndex(end, lines) ?: return fallbackRow
    if (startRow == endRow) return startRow
    // 量到**点**（开头 = 首字左上角、末尾 = 末字右下角），不量整行矩形：
    // 首行那一段很长时，拿整行去量会把它算成「很近」，框明明在末尾旁边也会指回开头。
    fun toPoint(px: Float, py: Float): Float {
        val dx = when {
            px < bubble.left -> bubble.left - px
            px > bubble.right -> px - bubble.right
            else -> 0f
        }
        val dy = when {
            py < bubble.top -> bubble.top - py
            py > bubble.bottom -> py - bubble.bottom
            else -> 0f
        }
        return sqrt(dx * dx + dy * dy)
    }
    return if (toPoint(start.left, start.top) < toPoint(end.right, end.bottom)) startRow else endRow
}

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
 * 规则第 8 条（2026-10-25 用户定口）：**这一条贴着自己那一行摆时，会不会横着罩住右侧那条的选区**。
 *
 * 判据：整句一行的自然宽（含两侧内缩）从右上 / 右下基准（基准行首字左缘 + 一个字）往右量，
 * 只要把右侧任一选区的墨迹**整个**罩进去，就算会遮挡。
 *
 * 用「整句一行的自然宽」而不是某个候选的实际框宽：候选折行后会变窄，用它判会让同一条批注
 * 在折行与不折行之间来回改变方向；用户定的是「这条批注会不会挡住右边」，与摆法无关。
 */
internal fun noteCoversRightText(
    note: NoteInk,
    lines: List<NoteTextLine>,
    rightInk: List<Rect>,
    metrics: NoteMetrics,
    charWidthPx: Float,
    sizer: NoteTextSizer = EstimatedNoteTextSizer,
): Boolean {
    if (rightInk.isEmpty() || lines.isEmpty()) return false
    val base = noteBaseRow(note.rects, lines) ?: return false
    val baseGlyphs = note.rects
        .filter { nearestLineIndex(it, lines) == base }
        .sortedBy { it.left }
    val firstGlyph = baseGlyphs.firstOrNull() ?: note.rects.minByOrNull { it.left } ?: return false
    val width = sizer.naturalWidth(note.text.trim(), metrics.em) + metrics.padTotal
    if (width <= 0f) return false
    val left = firstGlyph.left + max(charWidthPx, 1f)
    val right = left + width
    return rightInk.any { ink ->
        left <= ink.left + NOTE_GEOM_EPS && right >= ink.right - NOTE_GEOM_EPS
    }
}


/**
 * 文字障碍按**字墨迹**收紧（2026-10-24 用户定口）：只收纵向，横向仍是整行范围。
 *
 * 行框（`textInk`）是高 = 行高的盒子，上下各有约半段行距（正文 48 / 行高 69 时约 10px）不是字。
 * 那一段本来就该算空白——批注贴字墨迹留 0.1em 就不会压字，所以行距能用来放批注。
 * 横向故意**不**收到字墨迹：收窄了就会把「词与词之间的小缝」当成能放批注的地方（§2.1 禁止）。
 *
 * 逐字墨迹 [glyphInk] 缺失（或某行没有对应字，例如列表圆点、占位框）时原样返回该行。
 */
internal fun tightTextInk(textInk: List<Rect>, glyphInk: List<Rect>): List<Rect> {
    if (textInk.isEmpty() || glyphInk.isEmpty()) return textInk
    return textInk.map { tightLineInk(it, glyphInk) }
}

/** 单个行盒按字墨迹收紧纵向范围（横向仍是整行；无字时原样返回）。 */
internal fun tightLineInk(line: Rect, glyphInk: List<Rect>): Rect {
    var top = Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    for (g in glyphInk) {
        val cy = g.center.y
        if (cy < line.top || cy > line.bottom) continue
        if (g.right <= line.left || g.left >= line.right) continue
        top = min(top, g.top)
        bottom = max(bottom, g.bottom)
    }
    return if (bottom <= top) line else Rect(line.left, top, line.right, bottom)
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
     * 通畅路径的择优顺序（引线规则第 9 / 3 / 7 条）：
     * **交叉数（0 条优先）→ 折数少 → 穿字少 → 不贴着字（[hug]）→ 更短 → 候选顺序**。
     *
     * 规则第 9 条「两个批注的引线不得交叉」是硬要求，所以交叉数排在折数之前：
     * 宁可多折一次绕开别人的引线，也不许交叉。
     * 规则第 3 条「能用少线段就用少线段」→ 折数。
     * 规则第 7 条「实在找不到合适位置时宁愿穿越文字，但穿越的文字越少越好」→ [hits]。
     * [hug] 是「横穿两行之间要从正中间过」的度量，排在「短」之前。
     */
    fun betterThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (crosses != other.crosses) return crosses < other.crosses
        if (bends != other.bends) return bends < other.bends
        if (abs(hits - other.hits) > 0.5f) return hits < other.hits
        if (abs(hug - other.hug) > 0.5f) return hug < other.hug
        if (abs(length - other.length) > 0.5f) return length < other.length
        return order < other.order
    }

    /**
     * 全撞时的兜底顺序：不穿自己的框 → **交叉最少** → 穿得少 → 更短 → 候选顺序。
     * 照样画，不留空（规则第 7 条：实在没路时宁愿穿越，但要穿得少）。
     */
    fun betterFallbackThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (entersBubble != other.entersBubble) return !entersBubble
        if (crosses != other.crosses) return crosses < other.crosses
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
    /** 竖着进时箭头停在哪条边上：字所在的行盒（缺省退回字本身）。 */
    val box: Rect = glyph,
) {
    /**
     * 折点坐标的来源（就是 [obstacles] 那批矩形）：**一条目标只建一次**。
     * 真机上一条批注要问十来组「出口 × 入口」，每组都重新 map 一遍就是白扔几十个列表。
     */
    val obstacleRects: List<Rect> = obstacles.map { it.rect }

    /** 障碍的空间索引：量线段时只扫「纵向可能碰到」的那几行（见 [ArrowObstacleIndex]）。 */
    val index: ArrowObstacleIndex = ArrowObstacleIndex(obstacles)
}

/**
 * 一批障碍的空间索引：**按上缘排好序**，量线段时二分定位纵向可能相交的第一行。
 *
 * 为什么要它：一条候选折线四五段，每段都要扫一遍近处五十来个逐字墨迹，而一段通常只穿一两行。
 * 从「线段上缘再高一个最高障碍」处起步、扫到「比线段下缘还低」就停，结论与全量扫**完全一致**
 * （再靠上的障碍，上缘加上最大高度也够不到这段），只是不再白扫那些隔着几十行的字。
 */
private class ArrowObstacleIndex(obstacles: List<ArrowObstacle>) {
    val sorted: List<ArrowObstacle> = obstacles.sortedBy { it.rect.top }
    private val tops: FloatArray = FloatArray(sorted.size) { sorted[it].rect.top }

    /** 最高的那个障碍：二分起点要往上放宽这么多，才不会漏掉「起点更高、但下缘压住线段」的字。 */
    val slack: Float = sorted.maxOfOrNull { it.rect.height } ?: 0f

    /** 第一个上缘 ≥ [top] 的下标。 */
    fun firstFrom(top: Float): Int {
        var lo = 0
        var hi = tops.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (tops[mid] < top) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/**
 * 已画引线的索引：引线只有横竖段，按方向拆成两组、各按那条恒定坐标排好序。
 *
 * 量候选段时，横段只可能被「x 落在这一段范围内」的竖段穿过（共线的横段再单独按 y 找），
 * 竖段对称。于是每条候选段只查十来条，不再把整页已画引线逐条算一遍包围盒
 * （一页九条批注时已画引线有四十来段，乘上每条候选的四五段，这一笔在真机上是引线耗时的大头）。
 *
 * 斜段理论上不会出现（引线只有横平竖直）；万一出现，放一份到 [odd] 里逐条查，
 * 保证「宁可多查，也不漏判」。
 */
private class DrawnSegmentIndex(segments: List<Pair<Offset, Offset>>) {
    private val all: Array<Pair<Offset, Offset>> = segments.toTypedArray()
    private val hSegs: Array<Pair<Offset, Offset>>
    private val hAt: FloatArray
    private val vSegs: Array<Pair<Offset, Offset>>
    private val vAt: FloatArray
    private val odd: Array<Pair<Offset, Offset>>

    init {
        val h = ArrayList<Pair<Offset, Offset>>()
        val v = ArrayList<Pair<Offset, Offset>>()
        val o = ArrayList<Pair<Offset, Offset>>()
        for (s in segments) {
            val horizontal = abs(s.first.y - s.second.y) < 0.01f
            val vertical = abs(s.first.x - s.second.x) < 0.01f
            when {
                horizontal -> h += s
                vertical -> v += s
                else -> o += s
            }
        }
        hSegs = h.sortedBy { it.first.y }.toTypedArray()
        vSegs = v.sortedBy { it.first.x }.toTypedArray()
        hAt = FloatArray(hSegs.size) { hSegs[it].first.y }
        vAt = FloatArray(vSegs.size) { vSegs[it].first.x }
        odd = o.toTypedArray()
    }

    /**
     * 候选段与已画引线相交几次。[horizontal] 说明候选段是横段（[fixed] 是它的 y，
     * [lo] / [hi] 是它沿 x 的范围）；竖段同理换成 x 与 y 范围。
     */
    fun crossings(horizontal: Boolean, ax: Float, ay: Float, bx: Float, by: Float, lo: Float, hi: Float): Int {
        var n = 0
        if (horizontal) {
            var i = firstAtLeast(vAt, lo)
            while (i < vSegs.size && vAt[i] <= hi) {
                val s = vSegs[i]
                if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
                i++
            }
            i = firstAtLeast(hAt, ay - 0.01f)
            while (i < hSegs.size && hAt[i] <= ay + 0.01f) {
                val s = hSegs[i]
                if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
                i++
            }
        } else {
            var i = firstAtLeast(hAt, lo)
            while (i < hSegs.size && hAt[i] <= hi) {
                val s = hSegs[i]
                if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
                i++
            }
            i = firstAtLeast(vAt, ax - 0.01f)
            while (i < vSegs.size && vAt[i] <= ax + 0.01f) {
                val s = vSegs[i]
                if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
                i++
            }
        }
        for (s in odd) {
            if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
        }
        return n
    }

    /** 斜段 / 非横平竖直的退路：逐条比一遍。 */
    fun crossingsAll(ax: Float, ay: Float, bx: Float, by: Float): Int {
        var n = 0
        for (s in all) {
            if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
        }
        return n
    }

    private fun firstAtLeast(values: FloatArray, v: Float): Int {
        var lo = 0
        var hi = values.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

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
 * @param lineBoxes 本页正文的行盒：竖着进的时候箭头**停在行盒边框上**、不侵入行盒内部
 *   （引线规则第 4 条）。缺省时退回逐字墨迹的边（单测与无行盒信息的调用）。
 * @param targetInk 这条批注**要指的那一行**的逐字墨迹（2026-10-25 用户定口：位置定下来后，
 *   比一比框离选区开头近还是离末尾近，近哪头指哪头）。右侧摆法取它最右那个字、左侧摆法取
 *   最左那个字；缺省空 = 退回旧口径「与框纵向最近的那一行」。
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
    lineBoxes: List<Rect> = emptyList(),
    targetInk: List<Rect> = emptyList(),
): NoteArrow? {
    if (inkRects.isEmpty() || bubble.width <= 0f || bubble.height <= 0f) return null
    val anchor = rectUnion(inkRects)
    // 框已经贴住原文：连一根线的位置都没有，画出来只会压在字上。
    // 量的是**要指的那一行**（[targetInk]）：多行选区首行比末行长时，贴在末行右侧的框
    // 会落进整段包围盒里，但它离末行还有一段空档，引线照样画得出（2026-10-25）。
    val hugInk = if (targetInk.isEmpty()) anchor else rectUnion(targetInk)
    if (rectDistance(bubble, hugInk) <= NOTE_ARROW_MIN_GAP_PX) return null
    val relation = noteArrowRelation(bubble, anchor)
    // 沿边取点至少内缩 0.5em（引线规则第 6 条：端点不许落在四角）。旁注框的 em 取它的行高。
    val bubbleEm = if (lineH > 0f) lineH else bubble.height
    // 竖着进的时候箭头停在行盒边框上（规则第 4 条：不得侵入行盒内部）。
    fun boxOf(glyph: Rect): Rect = lineBoxes.firstOrNull {
        it.top <= glyph.center.y && it.bottom >= glyph.center.y &&
            it.left <= glyph.center.x && it.right >= glyph.center.x
    } ?: glyph

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
    // 本页还一条引线都没画时不可能交叉：这时候「通畅就停」的老早停仍然成立，
    // 免得为了一个不可能出现的交叉数把折数一路试到上限。
    val anyDrawn = drawnSegments.isNotEmpty()
    // 已画引线按横 / 竖分好桶：量候选段时只比「坐标落在这一段范围内」的那几条，
    // 不再对每条候选段逐条算包围盒（真机上一页九条批注要乘上四十来段已画引线）。
    val drawnIndex = DrawnSegmentIndex(drawnSegments)
    val window = max(lineH, 8f) * ARROW_BEND_WINDOW_LINES
    // 引线贴字门槛：旁注行高的 0.3 倍（lineH 缺省时放宽到 0：单测不量贴字）。
    val hugDistance = if (lineH > 0f) lineH * ARROW_HUG_LINES else 0f
    // 目标：右侧摆法指向**要指那一行**的终点（最右那个字），左侧摆法指向起点；
    // 正下 / 正上 / 退化调用才回到「最近的几个字」。
    val targetPool = arrowTargetGlyphs(side, inkRects, bubble, targetInk)
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
                box = boxOf(glyph),
            )
        }

    var order = 0
    var fallback: ArrowPath? = null
    var best: ArrowPath? = null

    // 热路径的暂存区：候选折线先写进这两对数组，量完就丢。
    // 真机上「一条候选 0.12ms」的大头就是每条候选一组短命对象（点列表 / 装箱的坐标），
    // 一页几千条候选光 GC 就够呛；改成数组后逐条候选不再分配任何对象。
    val rawX = FloatArray(ARROW_ROUTE_MAX_POINTS)
    val rawY = FloatArray(ARROW_ROUTE_MAX_POINTS)
    val ptsX = FloatArray(ARROW_ROUTE_MAX_POINTS)
    val ptsY = FloatArray(ARROW_ROUTE_MAX_POINTS)

    fun consider(xs: FloatArray, ys: FloatArray, count: Int, target: ArrowTarget) {
        val path = measureArrowRoute(
            xs, ys, count, order++, target.index, bubble, drawnIndex, hugDistance,
            limitClear = best, limitFallback = fallback,
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
            if (aligned) {
                rawX[0] = exit.point.x
                rawY[0] = exit.point.y
                rawX[1] = entry.point.x
                rawY[1] = entry.point.y
                consider(rawX, rawY, 2, target)
            }
        }
        // 1) 折线：只用横 / 竖段，且第一段 ⊥ 框边、最后一段 ⊥ 文字框边。
        // 折数由少到多，一旦这个折数有通畅路径就不再试更多折（少折永远优先）。
        for (bends in 1..ARROW_MAX_BENDS) {
            emitElbowRoutes(
                start = exit.point,
                end = entry.point,
                obstacleRects = target.obstacleRects,
                bends = bends,
                extraYs = target.gapYs,
                px = rawX,
                py = rawY,
                emit = { xs, ys, n ->
                    // 先验形状再量：不合规矩的候选连障碍都不用看。
                    val m = simplifyInto(xs, ys, n, ptsX, ptsY)
                    if (m >= 2 && orthogonalShaped(ptsX, ptsY, m, exit.horizontal, entry.horizontal)) {
                        consider(ptsX, ptsY, m, target)
                    }
                },
            )
            // 只有「通畅且一条已画引线都不交叉」才值得停：判据里交叉数排第一，
            // 早停在这里会把「多折一次但绕开别人引线」的路子直接掐掉。
            if (best?.let { it.clear && (it.crosses == 0 || !anyDrawn) } == true) break
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
            val yEntry = verticalEntryEdge(bubble, t.glyph, t.box, above = !below)
            // 同一行里靠起点 / 终点那个字：L 形的竖段贴着它的边，箭头指向锚点的角落。
            val rowGlyphs = inkRects.filter {
                abs(it.center.y - t.glyph.center.y) <= max(it.height, t.glyph.height) * 0.6f
            }
            val startGlyph = if (leftCorner) {
                rowGlyphs.maxByOrNull { it.right } ?: t.glyph
            } else {
                rowGlyphs.minByOrNull { it.left } ?: t.glyph
            }
            // 箭头末端同样离选区的角至少 0.5em（引线规则第 6 条）。
            val xEntry = clampInset(
                max(startGlyph.left, anchor.left + startGlyph.height * 0.5f),
                min(startGlyph.right, anchor.right - startGlyph.height * 0.5f),
                if (leftCorner) startGlyph.right else startGlyph.left,
            )
            val edgeX = if (leftCorner) bubble.right else bubble.left
            if (abs(xEntry - edgeX) < 1f) continue
            for (f in ARROW_EDGE_FRACTIONS) {
                val y = clampInset(
                    bubble.top + bubbleEm * 0.5f,
                    bubble.bottom - bubbleEm * 0.5f,
                    bubble.top + bubble.height * f,
                )
                rawX[0] = edgeX
                rawY[0] = y
                rawX[1] = xEntry
                rawY[1] = y
                rawX[2] = xEntry
                rawY[2] = yEntry
                val m = simplifyInto(rawX, rawY, 3, ptsX, ptsY)
                if (m < 2 || !orthogonalShaped(ptsX, ptsY, m, true, false)) continue
                val near = measureArrowRoute(
                    ptsX, ptsY, m, order++, t.index, bubble, drawnIndex, hugDistance,
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
            for (entry in arrowSideEntries(side!!, bubble, t.glyph, anchor, t.box)) {
                for (exit in arrowSideExits(entry, side, bubble, t.glyph, bubbleEm)) {
                    tryRoute(exit, entry, t)
                }
                best?.let { if (it.clear && (it.crosses == 0 || !anyDrawn)) return finish(it) }
            }
        }
    } else {
        // 退化路径（正下 / 正上 / 没有布点方向）：沿用「朝文字那条边的中点」。
        val exit = arrowExit(side, relation, bubble, anchor)
        for (t in targets) {
            val entryHorizontal = arrowEntryHorizontal(side, bubble, t.glyph)
            for (entry in arrowEntries(bubble, t.glyph, t.box, exit, entryHorizontal)) {
                tryRoute(exit, ArrowEntry(entry, entryHorizontal), t)
            }
            best?.let { if (it.clear && (it.crosses == 0 || !anyDrawn)) return finish(it) }
        }
    }
    val chosen = best ?: fallback ?: return null
    if (best == null) {
        // 兜底：一条通畅的路都没找到，只能画穿字 / 穿引线的那条（规则第 7 条：
        // 实在找不到合适位置时宁愿穿越文字，但穿越的文字越少越好）。留给真机排查。
        Logger.d(
            "Orilumn.NOTE",
            "NOTE-ARROW fallback side=$side crosses=${chosen.crosses} " +
                "hits=${chosen.hits.toInt()} pts=${chosen.points.size}",
        )
    }
    return finish(chosen)
}

/**
 * 引线的目标字（§6.1，2026-10-24 用户定口 + 2026-10-25 补充）：
 *
 * - **放在正文右侧**（正右 / 右下 / 右上）→ 指向该行**最靠右**的那个字；**放在左侧** → 指向最左那个字。
 * - 「该行」= [targetInk]（要指的那一行：框离选区开头近就取首行、离末尾近就取末行，
 *   见 [noteArrowRow]）；缺省空时退回旧口径「与框纵向最近的那一行」。
 * - 正下 / 正上 / 没有布点方向 → 仍旧取最近的几个字（最近那个被围死时还能换下一个）。
 */
private fun arrowTargetGlyphs(
    side: NoteSide?,
    inkRects: List<Rect>,
    bubble: Rect,
    targetInk: List<Rect> = emptyList(),
): List<Rect> {
    val pool = targetInk.ifEmpty { inkRects }
    val rightFamily = side == NoteSide.RIGHT || side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT
    val leftFamily = side == NoteSide.LEFT || side == NoteSide.BELOW_LEFT || side == NoteSide.ABOVE_LEFT
    if (!rightFamily && !leftFamily) {
        return pool.sortedBy { rectDistance(bubble, it) }.take(ARROW_TARGET_LIMIT)
    }
    // 与框纵向最近的那一行（同一行里框贴着哪儿都是它自己；退化成多行时才需要再挑一次）。
    fun gapY(r: Rect): Float = when {
        r.bottom <= bubble.top -> bubble.top - r.bottom
        r.top >= bubble.bottom -> r.top - bubble.bottom
        else -> 0f
    }
    val rowAnchor = pool.minByOrNull { gapY(it) } ?: return emptyList()
    val rowGlyphs = pool.filter {
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
    if (glyphs.size < 2) return emptyList()
    // 行间空隙最多是字高的一半：按上缘排序后只和「紧接着的几行」比，隔得再远不可能相邻。
    // 整对整对地比是 O(n²)，密页上这个函数一度是引线耗时的大头。
    val sorted = glyphs.sortedBy { it.top }
    val maxGap = sorted.maxOf { it.height } * 0.5f
    val out = LinkedHashSet<Float>()
    for (i in sorted.indices) {
        val up = sorted[i]
        for (j in i + 1 until sorted.size) {
            val down = sorted[j]
            // 后面的行只会更靠下，间隔超过上限就可以收手。
            if (down.top - up.bottom > maxGap) break
            if (up.bottom > down.top) continue
            val overlapX = min(up.right, down.right) - max(up.left, down.left)
            if (overlapX < 1f) continue
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
private fun arrowSideEntries(
    side: NoteSide,
    bubble: Rect,
    glyph: Rect,
    anchor: Rect,
    box: Rect = glyph,
): List<ArrowEntry> {
    val out = LinkedHashSet<ArrowEntry>()
    val h = glyph.height
    val rightFamily = side == NoteSide.RIGHT || side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT
    val overlapY = bubble.top < glyph.bottom - NOTE_GEOM_EPS && bubble.bottom > glyph.top + NOTE_GEOM_EPS
    // 端点离**选区的四个角**至少 0.5em（引线规则第 6 条）：沿边取点的可用区间。
    val alongLo = max(glyph.left, anchor.left + h * 0.5f)
    val alongHi = min(glyph.right, anchor.right - h * 0.5f)
    // 右侧摆法靠末端取点，左侧摆法靠起点取点。
    val fractions = if (rightFamily) floatArrayOf(1f, 0.75f, 0.5f) else floatArrayOf(0f, 0.25f, 0.5f)
    when {
        overlapY -> {
            // 进竖边：横着进，纵向也要离选区上下两个角 0.5em。
            val x = if (rightFamily) glyph.right else glyph.left
            val lo = max(glyph.top, anchor.top + h * 0.5f)
            val hi = min(glyph.bottom, anchor.bottom - h * 0.5f)
            for (f in floatArrayOf(0.5f, 0.25f, 0.75f)) {
                out += ArrowEntry(Offset(x, insetAlong(lo, hi, f)), horizontal = true)
            }
        }
        else -> {
            val y = verticalEntryEdge(bubble, glyph, box, above = bubble.bottom <= glyph.top + NOTE_GEOM_EPS)
            for (f in fractions) {
                out += ArrowEntry(Offset(insetAlong(alongLo, alongHi, f), y), horizontal = false)
            }
        }
    }
    return out.toList()
}

/**
 * 沿边按比例取点：区间反了（这条边短得放不下 1em，怎么取都挨着角）就退回中点。
 * 见引线规则第 6 条「至少向内偏移 0.5em」。
 */
private fun insetAlong(lo: Float, hi: Float, f: Float): Float =
    if (lo >= hi) (lo + hi) * 0.5f else lo + (hi - lo) * f

/** 把沿边坐标夹进「离两角至少 0.5em」的区间；区间反了就取中点。 */
private fun clampInset(lo: Float, hi: Float, v: Float): Float =
    if (lo >= hi) (lo + hi) * 0.5f else v.coerceIn(lo, hi)

/**
 * 竖着进时箭头停在哪条边上：优先**行盒边框**（引线规则第 4 条：画到行盒边框为止，
 * 不得侵入行盒内部）；行盒已经和批注框叠在一起时退回字边，免得箭头从框里长出来。
 */
private fun verticalEntryEdge(bubble: Rect, glyph: Rect, box: Rect, above: Boolean): Float =
    if (above) {
        if (box.top >= bubble.bottom - NOTE_GEOM_EPS) box.top else glyph.top
    } else {
        if (box.bottom <= bubble.top + NOTE_GEOM_EPS) box.bottom else glyph.bottom
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
    em: Float = 0f,
): List<ArrowExit> {
    val out = LinkedHashSet<ArrowExit>()
    val eps = NOTE_GEOM_EPS
    // 起点离框的四个角也至少 0.5em（引线规则第 6 条）：框边的可用区间。
    val pad = if (em > 0f) em * 0.5f else 0f
    val loY = bubble.top + max(pad, eps)
    val hiY = bubble.bottom - max(pad, eps)
    val loX = bubble.left + max(pad, eps)
    val hiX = bubble.right - max(pad, eps)
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
            out += ArrowExit(Offset(x, clampInset(loY, hiY, y)), horizontal = true)
        }
    }
    fun edgeX(y: Float) {
        for (x in xs) {
            out += ArrowExit(Offset(clampInset(loX, hiX, x), y), horizontal = false)
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
    box: Rect,
    exit: ArrowExit,
    horizontal: Boolean,
): List<Offset> {
    val out = LinkedHashSet<Offset>()
    val h = glyph.height
    if (horizontal) {
        val x = if (glyph.center.x <= bubble.center.x) glyph.right else glyph.left
        val lo = glyph.top + h * 0.5f
        val hi = glyph.bottom - h * 0.5f
        out += Offset(x, clampInset(lo, hi, exit.point.y.coerceIn(glyph.top, glyph.bottom)))
        for (f in floatArrayOf(0.5f, 0.25f, 0.75f)) out += Offset(x, insetAlong(lo, hi, f))
    } else {
        val y = verticalEntryEdge(bubble, glyph, box, above = glyph.center.y > bubble.center.y)
        val lo = glyph.left + h * 0.5f
        val hi = glyph.right - h * 0.5f
        out += Offset(clampInset(lo, hi, exit.point.x.coerceIn(glyph.left, glyph.right)), y)
        for (f in floatArrayOf(0.5f, 0.25f, 0.75f)) out += Offset(insetAlong(lo, hi, f), y)
    }
    return out.toList()
}

/**
 * 折线是否守规矩：**只有横竖段**，第一段垂直于框边（[exitHorizontal]）、
 * 最后一段垂直于文字框边（[entryHorizontal]）。
 *
 * 传进来的顶点必须已经去过零长段与共线拐点（[simplifyInto]）：这里不再化简一遍，
 * 免得每条候选多扫一次数组。
 */
private fun orthogonalShaped(
    xs: FloatArray,
    ys: FloatArray,
    n: Int,
    exitHorizontal: Boolean,
    entryHorizontal: Boolean,
): Boolean {
    if (n < 2) return false
    val firstAlong = if (exitHorizontal) {
        abs(ys[0] - ys[1]) < 0.01f && abs(xs[0] - xs[1]) > 0.01f
    } else {
        abs(xs[0] - xs[1]) < 0.01f && abs(ys[0] - ys[1]) > 0.01f
    }
    if (!firstAlong) return false
    val lastAlong = if (entryHorizontal) {
        abs(ys[n - 2] - ys[n - 1]) < 0.01f && abs(xs[n - 2] - xs[n - 1]) > 0.01f
    } else {
        abs(xs[n - 2] - xs[n - 1]) < 0.01f && abs(ys[n - 2] - ys[n - 1]) > 0.01f
    }
    if (!lastAlong) return false
    for (i in 1 until n) {
        if (abs(xs[i] - xs[i - 1]) >= 0.01f && abs(ys[i] - ys[i - 1]) >= 0.01f) return false
    }
    return true
}

/**
 * 正交折线的候选（§6.2）：[bends] = 1 试两个拐点，2 试横 / 纵各一条折点坐标，
 * 3 试两条折点坐标的四种组合，4 只试「绕开一整条竖带 / 横带」的两族（坐标只取前三个）。
 *
 * 折点坐标取自两端与附近障碍的四边（外扩 [ARROW_BEND_CLEARANCE_PX]），按离中点由近到远取前几个，
 * 近的更容易凑出短路径；两端中点补在最后。返回顺序固定，同一输入结果恒定。
 *
 * 一次取全部候选（测试与排查用）；路由热路径走 [emitElbowRoutes] 逐条吐。
 */
internal fun noteElbowRoutes(
    start: Offset,
    end: Offset,
    obstacles: List<Rect> = emptyList(),
    bends: Int = 1,
    /** 额外的候选 y 坐标（通常是相邻两行墨迹的中线）：排在前面，优先被取到。 */
    extraYs: List<Float> = emptyList(),
): List<List<Offset>> {
    val out = ArrayList<List<Offset>>()
    emitElbowRoutes(
        start = start,
        end = end,
        obstacleRects = obstacles,
        bends = bends,
        extraYs = extraYs,
        px = FloatArray(ARROW_ROUTE_MAX_POINTS),
        py = FloatArray(ARROW_ROUTE_MAX_POINTS),
    ) { xs, ys, n ->
        out += List(n) { Offset(xs[it], ys[it]) }
    }
    return out
}

/**
 * 折线候选的**生成器内核**：一条一条写进 [px] / [py]（[emit] 拿到点数 [n]），不等整批建成
 * 点列表再量。
 *
 * 为什么这么做：一页九条批注最贵的一步就是引线，一对端点在 4 折档上就有三百来条候选。
 * 先建成「几百个点列表」再逐条量，等于给每一对端点白扔一大堆马上作废的对象；
 * 真机（ART）上没有 JVM 那种逃逸分析兜底，这些短命对象最后都落在 GC 上。
 *
 * 顺序与 [noteElbowRoutes] 完全一致（同一输入逐条同序），所以选择结果不变。
 */
private inline fun emitElbowRoutes(
    start: Offset,
    end: Offset,
    obstacleRects: List<Rect>,
    bends: Int,
    extraYs: List<Float>,
    px: FloatArray,
    py: FloatArray,
    emit: (FloatArray, FloatArray, Int) -> Unit,
) {
    if (bends !in 1..ARROW_MAX_BENDS) return
    val xs = FloatArray(obstacleRects.size * 2 + 2)
    val ys = FloatArray(obstacleRects.size * 2 + 2)
    var xn = 0
    var yn = 0
    xs[xn++] = start.x
    xs[xn++] = end.x
    ys[yn++] = start.y
    ys[yn++] = end.y
    for (r in obstacleRects) {
        xs[xn++] = r.left - ARROW_BEND_CLEARANCE_PX
        xs[xn++] = r.right + ARROW_BEND_CLEARANCE_PX
        ys[yn++] = r.top - ARROW_BEND_CLEARANCE_PX
        ys[yn++] = r.bottom + ARROW_BEND_CLEARANCE_PX
    }
    // 折点坐标的候选池：横向（竖带用）与纵向（横带用）各一层。
    // 先就地把坐标排好序、去掉 0.5px 以内的重号，之后每次「取离中点最近的几个」
    // 只要二分定位 + 两头往外扩 —— 否则一页要在这几个小数组上比上千万次（真机上真能吃掉几十毫秒）。
    xs.sort(0, xn)
    ys.sort(0, yn)
    xn = uniqueFloats(xs, xn)
    yn = uniqueFloats(ys, yn)
    val bendXs = FloatArray(ARROW_MAX_BEND_COORDS + 1)
    var bendXn = pickCoords(xs, xn, (start.x + end.x) * 0.5f, bendXs, ARROW_MAX_BEND_COORDS)
    // 纵向候选：行间中线排在前面（优先被取到），再补「离中点最近的坐标」，最后是两端中点。
    val bendYs = FloatArray(ARROW_MAX_BEND_COORDS + 3)
    var bendYn = 0
    for (y in extraYs) {
        if (bendYn >= 2) break
        bendYs[bendYn++] = y
    }
    val pickedYs = FloatArray(ARROW_MAX_BEND_COORDS)
    val pickedYn = pickCoords(ys, yn, (start.y + end.y) * 0.5f, pickedYs)
    for (i in 0 until pickedYn) {
        val v = pickedYs[i]
        var dup = false
        for (j in 0 until bendYn) {
            if (bendYs[j] == v) {
                dup = true
                break
            }
        }
        if (!dup) bendYs[bendYn++] = v
    }
    // 两端都不在障碍边上时（比如空地上折一次），中线坐标是唯一能同时满足「首末段垂直」的落点，
    // 所以补在最后：不抢前面候选的先后，只在它们都不通时才用上。
    val midX = (start.x + end.x) * 0.5f
    val midY = (start.y + end.y) * 0.5f
    if (abs(start.x - end.x) > 0.01f) {
        var hasMidX = false
        for (i in 0 until bendXn) {
            if (abs(bendXs[i] - midX) <= 0.5f) {
                hasMidX = true
                break
            }
        }
        if (!hasMidX) bendXs[bendXn++] = midX
    }
    if (abs(start.y - end.y) > 0.01f) {
        var hasMidY = false
        for (i in 0 until bendYn) {
            if (abs(bendYs[i] - midY) <= 0.5f) {
                hasMidY = true
                break
            }
        }
        if (!hasMidY) bendYs[bendYn++] = midY
    }
    when (bends) {
        1 -> {
            emitPoints(
                px, py, 3,
                start.x, start.y, end.x, start.y, end.x, end.y,
                0f, 0f, 0f, 0f, 0f, 0f, emit,
            )
            emitPoints(
                px, py, 3,
                start.x, start.y, start.x, end.y, end.x, end.y,
                0f, 0f, 0f, 0f, 0f, 0f, emit,
            )
        }
        2 -> {
            for (i in 0 until bendXn) {
                val x = bendXs[i]
                emitPoints(
                    px, py, 4,
                    start.x, start.y, x, start.y, x, end.y, end.x, end.y,
                    0f, 0f, 0f, 0f, emit,
                )
            }
            for (i in 0 until bendYn) {
                val y = bendYs[i]
                emitPoints(
                    px, py, 4,
                    start.x, start.y, start.x, y, end.x, y, end.x, end.y,
                    0f, 0f, 0f, 0f, emit,
                )
            }
        }
        3 -> for (i in 0 until bendXn) {
            val x = bendXs[i]
            for (j in 0 until bendYn) {
                val y = bendYs[j]
                emitPoints(
                    px, py, 5,
                    start.x, start.y, x, start.y, x, y, end.x, y, end.x, end.y,
                    0f, 0f, emit,
                )
                emitPoints(
                    px, py, 5,
                    start.x, start.y, start.x, y, x, y, x, end.y, end.x, end.y,
                    0f, 0f, emit,
                )
            }
        }
        // 四折只做「绕开一整条竖带 / 横带」两族：两臂各一个自由坐标，够绕过图形与表格。
        // 竖 / 横那个「跨过去」的坐标取全部候选（绕过整块图形靠的就是它）；两臂的坐标则分别
        // **贴着起点 / 终点那一侧取**——只取离中点最近的几个会漏掉「先横到障碍之外、再竖过去」
        // 的那一对（真机第 5/6 条：从两行字之间横穿的绕行正是这种形状）。
        else -> {
            val xsFromStart = FloatArray(4)
            val xsFromEnd = FloatArray(4)
            val ysFromStart = FloatArray(4)
            val ysFromEnd = FloatArray(4)
            val xsn = pickCoords(xs, xn, start.x, xsFromStart)
            val xen = pickCoords(xs, xn, end.x, xsFromEnd)
            val ysn = pickCoords(ys, yn, start.y, ysFromStart)
            val yen = pickCoords(ys, yn, end.y, ysFromEnd)
            for (i in 0 until xsn) {
                val x1 = xsFromStart[i]
                for (j in 0 until xen) {
                    val x2 = xsFromEnd[j]
                    for (k in 0 until bendYn) {
                        val y = bendYs[k]
                        emitPoints(
                            px, py, 6,
                            start.x, start.y, x1, start.y, x1, y, x2, y, x2, end.y, end.x, end.y,
                            emit,
                        )
                    }
                }
            }
            for (i in 0 until ysn) {
                val y1 = ysFromStart[i]
                for (j in 0 until yen) {
                    val y2 = ysFromEnd[j]
                    for (k in 0 until bendXn) {
                        val x = bendXs[k]
                        emitPoints(
                            px, py, 6,
                            start.x, start.y, start.x, y1, x, y1, x, y2, end.x, y2, end.x, end.y,
                            emit,
                        )
                    }
                }
            }
        }
    }
}

/** 把一条折线写进暂存数组并交给 [emit]：顶点数最多 6（4 折），没用的坐标填 0。 */
private inline fun emitPoints(
    px: FloatArray,
    py: FloatArray,
    n: Int,
    x0: Float, y0: Float,
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    x3: Float, y3: Float,
    x4: Float, y4: Float,
    x5: Float, y5: Float,
    emit: (FloatArray, FloatArray, Int) -> Unit,
) {
    px[0] = x0
    py[0] = y0
    px[1] = x1
    py[1] = y1
    px[2] = x2
    py[2] = y2
    if (n > 3) {
        px[3] = x3
        py[3] = y3
    }
    if (n > 4) {
        px[4] = x4
        py[4] = y4
    }
    if (n > 5) {
        px[5] = x5
        py[5] = y5
    }
    emit(px, py, n)
}

/**
 * 按离 [mid] 由近到远取至多 [limit] 个坐标，写进 [out]。
 *
 * [values] 的前 [count] 个必须**已升序、且两两相差超过 0.5px**（[emitElbowRoutes] 入口就排好去重了）：
 * 于是「取最近的几个」只要二分找到 mid 落点，再像归并那样从两头往外扩。全程不分配、也不装箱。
 */
private fun pickCoords(
    values: FloatArray,
    count: Int,
    mid: Float,
    out: FloatArray,
    limit: Int = out.size,
): Int {
    if (count <= 0) return 0
    var lo = 0
    var hi = count
    while (lo < hi) {
        val m = (lo + hi) ushr 1
        if (values[m] < mid) lo = m + 1 else hi = m
    }
    var left = lo - 1
    var right = lo
    var picked = 0
    val cap = min(limit, out.size)
    while (picked < cap && (left >= 0 || right < count)) {
        val takeLeft = if (left < 0) {
            false
        } else if (right >= count) {
            true
        } else {
            abs(values[left] - mid) <= abs(values[right] - mid)
        }
        out[picked++] = if (takeLeft) values[left--] else values[right++]
    }
    return picked
}

/** 就地去掉 0.5px 以内的重号（输入已升序），返回剩下的个数。 */
private fun uniqueFloats(values: FloatArray, count: Int): Int {
    var n = 0
    for (i in 0 until count) {
        if (n > 0 && abs(values[i] - values[n - 1]) <= 0.5f) continue
        values[n++] = values[i]
    }
    return n
}


/** 量一条候选路径：长度、折数、已画引线交叉数、穿障碍的长度、有没有拐回自己的框。 */
/** 候选路径的实测次数（真机排查用：一页引线慢的时候先看这个数）。 */
internal var noteArrowRouteTries: Int = 0

/**
 * 量一条候选折线（顶点已经去过零长段与共线拐点）。
 *
 * 入口用数组而不是点列表：真机上一页九条批注要量两千多条候选，每条都先建一份点列表的话，
 * 光这些马上作废的对象就够 GC 忙一阵（JVM 有逃逸分析兜底，真机没有）。
 * 只有**量完没被掐掉**的候选才建点列表（[ArrowPath]）。
 */
private fun measureArrowRoute(
    xs: FloatArray,
    ys: FloatArray,
    count: Int,
    order: Int,
    obstacles: ArrowObstacleIndex,
    bubble: Rect,
    drawn: DrawnSegmentIndex,
    hugDistance: Float = 0f,
    /**
     * 现有的通畅路径 / 兜底路径：量到一半就已经**赢不了**它们时立刻放弃。
     *
     * 判据里交叉数排第一、长度排最后，各项都只会随段数增加而变差（交叉、穿字、贴字、长度
     * 全是累加量，折数开局就定了），所以「已经更差」是**严格**结论，不是近似。
     * 真机上一页九条批注要量两千多条候选路径，绝大多数第一条线段就撞上已画引线——
     * 不在这里掐掉，引线就是全页最慢的一步。
     */
    limitClear: ArrowPath? = null,
    limitFallback: ArrowPath? = null,
): ArrowPath? {
    noteArrowRouteTries++
    if (count < 2) return null
    val bends = max(0, count - 2)
    var length = 0f
    var hits = 0f
    var crosses = 0
    var hug = 0f
    var enters = false
    for (i in 1 until count) {
        val ax = xs[i - 1]
        val ay = ys[i - 1]
        val bx = xs[i]
        val by = ys[i]
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.01f) continue
        length += len
        if (insideSpan(ax, ay, dx, dy, bubble, ARROW_OWN_BUBBLE_TOL_PX) > 1e-6f) enters = true
        // 先用线段的包围盒粗筛：绝大多数字形连边都碰不到，省掉逐块的裁剪计算。
        val segLeft = min(ax, bx)
        val segRight = max(ax, bx)
        val segTop = min(ay, by)
        val segBottom = max(ay, by)
        val horizontal = abs(ay - by) < 0.01f
        val vertical = abs(ax - bx) < 0.01f
        // 只扫「纵向可能碰到这段」的那几行：按上缘排好序，起点上移一个最高障碍、
        // 扫到下缘超过线段就收手。结果与全量扫一致，但不白看上下的几十个字。
        val nearby = obstacles.sorted
        var oi = obstacles.firstFrom(segTop - hugDistance - obstacles.slack)
        while (oi < nearby.size) {
            val o = nearby[oi]
            oi++
            if (o.rect.top > segBottom + hugDistance) break
            // 粗筛要把「贴字」的门槛算进去：刚好在门槛以内的字形不能因为包围盒不相交被跳过。
            if (o.rect.right < segLeft - hugDistance || o.rect.left > segRight + hugDistance ||
                o.rect.bottom < segTop - hugDistance || o.rect.top > segBottom + hugDistance
            ) {
                continue
            }
            val span = insideSpan(ax, ay, dx, dy, o.rect, o.tol)
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
                        ay <= o.rect.top -> o.rect.top - ay
                        ay >= o.rect.bottom -> ay - o.rect.bottom
                        else -> 0f
                    }
                    vertical -> when {
                        ax <= o.rect.left -> o.rect.left - ax
                        ax >= o.rect.right -> ax - o.rect.right
                        else -> 0f
                    }
                    else -> Float.MAX_VALUE
                }
                if (d < hugDistance) hug += hugDistance - d
            }
        }
        // 已画引线走索引：横段只比「x 落在这段里」的竖段与同 y 的横段，竖段对称。
        crosses += if (horizontal) {
            drawn.crossings(true, ax, ay, bx, by, segLeft, segRight)
        } else if (vertical) {
            drawn.crossings(false, ax, ay, bx, by, segTop, segBottom)
        } else {
            // 斜段（正常不会出现）：退回逐条比，宁可多查也不漏判。
            drawn.crossingsAll(ax, ay, bx, by)
        }        // 已量到的部分就赢不了了：后面的段只会让交叉 / 穿字 / 长度继续变大。
        if (limitClear != null) {
            if (crosses > limitClear.crosses) return null
            if (crosses == limitClear.crosses) {
                if (bends > limitClear.bends) return null
                if (bends == limitClear.bends) {
                    if (hits > limitClear.hits + 0.5f) return null
                    if (abs(hits - limitClear.hits) <= 0.5f) {
                        if (hug > limitClear.hug + 0.5f) return null
                        if (abs(hug - limitClear.hug) <= 0.5f && length > limitClear.length + 0.5f) {
                            return null
                        }
                    }
                }
            }
        }
        if (limitFallback != null) {
            if (enters != limitFallback.entersBubble) {
                if (enters) return null
            } else {
                if (crosses > limitFallback.crosses) return null
                if (crosses == limitFallback.crosses) {
                    if (hits > limitFallback.hits + 0.5f) return null
                    if (abs(hits - limitFallback.hits) <= 0.5f && length > limitFallback.length + 0.5f) {
                        return null
                    }
                }
            }
        }
    }
    if (length < 0.01f) return null
    return ArrowPath(
        points = List(count) { Offset(xs[it], ys[it]) },
        bends = bends,
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
private fun insideSpan(ax: Float, ay: Float, dx: Float, dy: Float, r: Rect, tol: Float): Float {
    val left = r.left + tol
    val right = r.right - tol
    val top = r.top + tol
    val bottom = r.bottom - tol
    if (left >= right || top >= bottom) return 0f
    var t0 = 0f
    var t1 = 1f
    // Liang–Barsky 四条边逐条裁剪。这里刻意**不建数组**：一条引线要量成百上千条候选 ×
    // 每段十几个障碍，数组（哪怕只有四个元素）在这里就是热路径上最大的垃圾来源。
    // 左：p = -dx, q = a.x - left
    val pLeft = -dx
    val qLeft = ax - left
    if (abs(pLeft) < 1e-6f) {
        // 平行且贴在这条边上：不算穿过。
        if (qLeft <= 0f) return 0f
    } else {
        val t = qLeft / pLeft
        if (pLeft < 0f) {
            if (t > t1) return 0f
            if (t > t0) t0 = t
        } else {
            if (t < t0) return 0f
            if (t < t1) t1 = t
        }
    }
    // 右：p = dx, q = right - a.x
    val pRight = dx
    val qRight = right - ax
    if (abs(pRight) < 1e-6f) {
        if (qRight <= 0f) return 0f
    } else {
        val t = qRight / pRight
        if (pRight < 0f) {
            if (t > t1) return 0f
            if (t > t0) t0 = t
        } else {
            if (t < t0) return 0f
            if (t < t1) t1 = t
        }
    }
    // 上：p = -dy, q = a.y - top
    val pTop = -dy
    val qTop = ay - top
    if (abs(pTop) < 1e-6f) {
        if (qTop <= 0f) return 0f
    } else {
        val t = qTop / pTop
        if (pTop < 0f) {
            if (t > t1) return 0f
            if (t > t0) t0 = t
        } else {
            if (t < t0) return 0f
            if (t < t1) t1 = t
        }
    }
    // 下：p = dy, q = bottom - a.y
    val pBottom = dy
    val qBottom = bottom - ay
    if (abs(pBottom) < 1e-6f) {
        if (qBottom <= 0f) return 0f
    } else {
        val t = qBottom / pBottom
        if (pBottom < 0f) {
            if (t > t1) return 0f
            if (t > t0) t0 = t
        } else {
            if (t < t0) return 0f
            if (t < t1) t1 = t
        }
    }
    return if (t1 > t0) t1 - t0 else 0f
}

/**
 * 去掉零长段与共线拐点（画出来还是同一条折线），结果写进 [outX] / [outY]，返回顶点数。
 *
 * 用数组就地化简：每条候选都要过一次，不能在这里建列表。
 */
private fun simplifyInto(
    srcX: FloatArray,
    srcY: FloatArray,
    count: Int,
    outX: FloatArray,
    outY: FloatArray,
): Int {
    var n = 0
    for (i in 0 until count) {
        val x = srcX[i]
        val y = srcY[i]
        if (n > 0 && abs(x - outX[n - 1]) < 0.01f && abs(y - outY[n - 1]) < 0.01f) continue
        outX[n] = x
        outY[n] = y
        n++
    }
    var i = 1
    while (i < n - 1) {
        val ax = outX[i - 1]
        val ay = outY[i - 1]
        val bx = outX[i]
        val by = outY[i]
        val cx = outX[i + 1]
        val cy = outY[i + 1]
        val collinear = (abs(ax - bx) < 0.01f && abs(bx - cx) < 0.01f) ||
            (abs(ay - by) < 0.01f && abs(by - cy) < 0.01f)
        if (collinear) {
            for (k in i until n - 1) {
                outX[k] = outX[k + 1]
                outY[k] = outY[k + 1]
            }
            n--
        } else {
            i++
        }
    }
    return n
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
private fun segmentsIntersect(
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
    cx: Float,
    cy: Float,
    dx: Float,
    dy: Float,
): Boolean {
    val o1 = orientation(ax, ay, bx, by, cx, cy)
    val o2 = orientation(ax, ay, bx, by, dx, dy)
    val o3 = orientation(cx, cy, dx, dy, ax, ay)
    val o4 = orientation(cx, cy, dx, dy, bx, by)
    if (o1 * o2 < -1e-6f && o3 * o4 < -1e-6f) return true
    return (abs(o1) < 1e-6f && onSegment(ax, ay, bx, by, cx, cy)) ||
        (abs(o2) < 1e-6f && onSegment(ax, ay, bx, by, dx, dy)) ||
        (abs(o3) < 1e-6f && onSegment(cx, cy, dx, dy, ax, ay)) ||
        (abs(o4) < 1e-6f && onSegment(cx, cy, dx, dy, bx, by))
}

private fun orientation(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Float =
    (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

private fun onSegment(ax: Float, ay: Float, bx: Float, by: Float, px: Float, py: Float): Boolean =
    px >= min(ax, bx) - 0.01f && px <= max(ax, bx) + 0.01f &&
        py >= min(ay, by) - 0.01f && py <= max(ay, by) + 0.01f

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
        CachingNoteTextSizer(ComposeNoteTextSizer(measurer, density, baseStyle))
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
