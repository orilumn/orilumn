package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
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
 * 统一布点，**不改排版、不进栅格**——它只是盖在页面之上的一层，翻页时**跟着页一起滑**
 * （[ReaderScreen] 把它放进滑动层同一槽，与页画布共用平移量；早先动画期间隐藏它，已废）。
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
 * 先把**远离正文那一侧**收到 0.1em，还不够才换位置。
 *
 * 2026-10-27 用户定口：**靠近正文那一侧的留白一律不压到贴字**——
 * 正上 / 正下保住 1em（竖直引线要从这段空档出去，推不出就宁可不用这个方向）；
 * 四个斜向的**纵向**近侧只留 [NOTE_MIN_MARGIN_PX]（2px），横向特意错开一个字，
 * 让引线从框的侧边出去、沿那道空隙折成 L 形（见 [routeNoteArrow] 的收尾）；
 * 斜向的**横向**近侧与正侧同口径留 1em（2026-10-31 用户定口：上一行右边还空着，
 * 框不该贴着它的字根摆），只有 1em 放不下、且压紧确实换来更少的折行时才收 0.1em。
 * 正右 / 正左固定 `sideGap`（1em，也是横线引线的空档）。
 */
private const val NOTE_PREFERRED_MARGIN_EM: Float = 1f

/**
 * 正右 / 正左摆法下，框与所选文字之间的首选距离（以旁注字号为单位）。
 *
 * 默认与 [NOTE_PREFERRED_MARGIN_EM] 相同；保留 [NoteStyle.sideGapEm] 是为了让
 * 「批注样式」将来可以单独调这一段，候选生成统一走这个值。
 */
private const val NOTE_SIDE_GAP_EM: Float = 1f

/**
 * 候选留白的两个档（2026-10-27 用户定口）：先双侧 1em，再只收远侧到 0.1em。
 *
 * 正上 / 正下压到行距 / 2px 会让引线短到看不见（真机「用 var 声明」那条），所以它们不收近侧；
 * 四个斜向的**纵向**近侧固定 2px（那 2px 不是留白，而是 L 形引线的横向走廊），
 * **横向**近侧跟着档位走：首选档 1em，压缩档只在那 1em 确实换来更少的折行时才用。
 */
private const val NOTE_MARGIN_TIER_PREFERRED: Int = 0
private const val NOTE_MARGIN_TIER_FAR_MIN: Int = 1

/** 留白压缩的下限（规则第 13 条：压到 2px 才算这个方向不可用）。 */
private const val NOTE_MIN_MARGIN_PX: Float = 2f

/** 标注离屏幕边的距离：贴边会让框看起来像被裁掉一条。 */
private const val NOTE_PAGE_EDGE_EM: Float = 0.5f

/** 每条批注最多保留多少个候选（按排序取前 N，避免极端页面候选爆炸）。 */
private const val MAX_CANDIDATES_PER_NOTE: Int = 300

/**
 * 末段短到**箭头都画不下**的门槛（以旁注行高为单位）：这一档才走规则第 11 条
 * 「省掉末折、箭头画在横线上」。5dp 的箭头臂在正常字号下≈0.25 行高。
 */
private const val NOTE_ARROW_UNDRAWABLE_LINES: Float = 0.25f

/**
 * 引线与字墨迹贴得太近的判定距离（以旁注行高为单位）。
 *
 * 第 8 条管的是**穿越**；平行贴边不算穿越，只是「同样长、同样折数时挑个看着舒服的」，
 * 所以它在判据里排在长度之后（2026-10-28 定口：先绕字、再比长短）。
 */
private const val ARROW_HUG_LINES: Float = 0.3f

/**
 * 沿一条边取几个候选点（几何来源：内缩端、对齐点、障碍边、边中点）。
 *
 * 取代原来的固定比例（1/4、3/4、0.75）：比例点跟障碍没关系，框一动就落到字上，
 * 而真正该试的是「贴着障碍边穿出去」「与另一端对齐」这几种位置。
 */
private const val ARROW_EDGE_POINT_LIMIT: Int = 6

/** 「空间不足、允许突破 0.5em」时端点离边两端留的护栏（像素）：不许真的落在角上。 */
private const val ARROW_EDGE_GUARD_PX: Float = 2f

/** 把障碍边当候选点时离障碍留的缝（像素）：逐字墨迹 0.5 / 图形 1 的容差都要让开。 */
private const val ARROW_EDGE_BREAK_PX: Float = 2f

/** 只把「离首选区间这么近」的障碍边当候选点（再远的边跟这条边没关系，只会稀释候选）。 */
private const val ARROW_EDGE_BREAK_RANGE_PX: Float = 48f

/** 箭头避让逐字墨迹的容差：不到半个像素的贴边不算压字。 */
private const val ARROW_GLYPH_TOL_PX: Float = 0.5f

/** 箭头避让图形 / 表格 / 线条 / 其他旁注框的容差：这几类边界是硬线，多让一点。 */
private const val ARROW_SOLID_TOL_PX: Float = 1f

/** 箭头不许拐回自己的旁注框：只差一根线的量就算「进去」。 */
private const val ARROW_OWN_BUBBLE_TOL_PX: Float = 0.01f

/**
 * 划线标注的箭头进到行盒里以后，离「墨迹 + 下划线」这条禁触带留的缝（像素）。
 *
 * 规则只要求「不得触及」，但 0 距离在屏幕上看着就是压住了：箭头尖 5dp 长，
 * 留 2px 才能一眼看出是「停在旁边」（2026-10-28 用户定口）。
 */
private const val ARROW_NO_TOUCH_GAP_PX: Float = 2f

/** 折线折点贴着障碍边取坐标时留的缝（像素）。 */
private const val ARROW_BEND_CLEARANCE_PX: Float = 1f

/**
 * 引线沿别人的框（批注框 / 图形 / 表格）走时，离框边留的余量（em 的倍数）。
 *
 * 字墨迹要贴边 2px 才穿得过字缝，框不一样：贴着别人框边 2px 抹过去，看着像挂在人家框上，
 * 200 多像素的一条长边更明显（2026-10-30 用户：「可以距离其他批注框稍远一点」）。
 * 四分之一字在平板上是十几像素，既不蹭框，也不至于把引线推到另一行去。
 */
private const val ARROW_SOLID_CLEAR_EM: Float = 0.25f

/**
 * 两条引线之间至少留的缝（以旁注行高为单位）。
 *
 * 两条引线平行挤在同一段行间空白里，相距两三像素，看着就是一条双线（2026-11-01 真机
 * 「函数体」/「内 {}」）。它们并不相交，原来的「交叉数」判据看不见，所以平行且投影重叠、
 * 距离小于这个值的，也按一次「交叉」记——先画的占住走廊，后画的要么让开、要么换一侧。
 */
private const val ARROW_LEADER_CLEAR_LINES: Float = 0.2f

/**
 * 无布点方向的斜向引线把 L 收成弧线时，控制点沿两段折线外伸的比例。
 *
 * 0.55 ≈ 圆角：「起点垂直离开框、终点垂直扎向正文」（规则第 5 条）不变，中间不再走直角，
 * 朝 L 的拐点那一侧（也就是布线挑出来的那条空走廊）鼓出去。走廊里有字 / 框挡住时按
 * [ARROW_ARC_FALLOFFS] 一级级收小，最后收不住就照旧画 L。
 */
private const val ARROW_ARC_ELBOW: Float = 0.55f

/** 弧线鼓不出来时依次试的控制点比例（越小越贴近原来那条斜向的弦）。 */
private val ARROW_ARC_FALLOFFS = floatArrayOf(0.55f, 0.4f, 0.25f)

/** 弧线采样成几段短弦（交叉判定、已画引线索引只认线段）。 */
private const val ARROW_CURVE_SAMPLES: Int = 12

/** 折线最多四个拐点（5 段，§6.2）：首末段必须各自垂直于框边，绕过整条图形带时要多一个折。 */
private const val ARROW_MAX_BENDS: Int = 4

/** 箭头最多考虑几个最近的锚点字：最近那个被围死时换下一个字。 */
private const val ARROW_TARGET_LIMIT: Int = 4

/**
 * 四角 L 的两头都画不成干净路径时，在收角带里再扫几个落点（不含两头）。
 *
 * 规则第 2 条把 L 定成硬规定，而「一头被自己的框盖住、另一头那一竖又扎进下一行的字里」
 * 是常见组合；扫字缝比退成一条直线强（2026-10-28 真机）。
 */
private const val ARROW_CORNER_SWEEP: Int = 5

/** 两折 / 三折每个方向最多试几个折点坐标（再多的只是白算）。 */
private const val ARROW_MAX_BEND_COORDS: Int = 8

/**
 * 行间中线最多排几个在折点候选的最前面（引线规则第 10 条：两行字之间横穿要走正中间）。
 *
 * 只给「最靠近目标行的那几条缝」留位置：全页十几条缝都排进来的话，折点候选会被稀释，
 * 反而挑不到真正要穿的那一条。
 */
private const val ARROW_GAP_MID_LIMIT: Int = 4

/**
 * 引线形状名次（规则第 1 / 2 / 3 / 6 条）：先定形状，再比折数。
 *
 * 交叉数仍然排在最前面（规则第 7 条）；形状只在「都不交叉」的候选之间决胜，
 * 所以这里的名次是「这个摆法最想要哪种线型」，不参与跨摆法的比较。
 */
private const val ARROW_FORM_STRAIGHT: Int = 0
private const val ARROW_FORM_FIRST: Int = 1
private const val ARROW_FORM_SECOND: Int = 2
private const val ARROW_FORM_OTHER: Int = 3

/** 折线找障碍时看两端连线附近多远（以旁注行高为单位，至少 8px）。 */
private const val ARROW_BEND_WINDOW_LINES: Float = 2f

/** 一条候选折线最多几个顶点（4 折 = 6 个）：暂存数组按它开，热路径上不再分配。 */
private const val ARROW_ROUTE_MAX_POINTS: Int = 6

/** 一条批注最多记多少条「已经量过的直线」（去重用，见 [ArrowTarget.markStraight]）。 */
private const val ARROW_STRAIGHT_LINES: Int = 32

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
internal enum class NoteSide {
    /** 所选行右侧（锚点末行右侧还剩 1em 以上空白；满行也可以用它自己的页边距那一列）。 */
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

    /** 左侧（环内排在最后；本行缩进时只有最左那条可以提前）。 */
    LEFT,

    /** 归不进以上方向（兜底，排在最后）。 */
    OTHER,
}

/**
 * 环内的方向名次（2026-10-27 用户定口）：
 * **右上 → 右下 → 左上 → 左下 → 正右 → 正左 → 正上 → 正下**。
 *
 * 上 / 下被压到最后（本次定口）：它们要占掉 1em 的竖线空档，一压就贴着原文、
 * 引线看不见；四个角错开一个字反而能折出 L 形引线。上下只有在四个角、右、左
 * 都放不下时才用，而且推不出 1em 引线空档时**宁可不用**。
 *
 * 这一段只在**折行数、环都相同**时才轮到（§1.1「顺序怎么比」）。左右平时排在四角之后，
 * 但规则第 3 / 4 条给了**提前**的例外，由候选自带的 [NoteCandidate.sideRank] 落实
 * （2026-10-25 用户定口，左右都可能首选）：
 *
 * - 只有右侧离版心还有 1em 以上空白 ⇒ 正右提到最前（短行）；
 * - 只有这一行左侧有明显缩进 ⇒ 正左提到最前；
 * - 两边都空着 ⇒ 看选区在这一行里偏左还是偏右（哪边空档小），偏哪边就把哪边提到最前。
 *
 * 右侧已经被别的批注占住时，被提前的正右让到**其他所有方向之后**
 * （见 [NOTE_SIDE_RANK_TAKEN_RIGHT]）：它本来就不该凭「更靠右」抢先。
 */
internal fun noteSideRank(side: NoteSide): Int = when (side) {
    NoteSide.ABOVE_RIGHT -> 0
    NoteSide.BELOW_RIGHT -> 1
    NoteSide.ABOVE_LEFT -> 2
    NoteSide.BELOW_LEFT -> 3
    NoteSide.RIGHT -> 10
    NoteSide.LEFT -> 20
    NoteSide.ABOVE -> 30
    NoteSide.BELOW -> 40
    NoteSide.OTHER -> 50
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

/** 右侧已被占时，正右的名次：给一个比正左更大的值，候选链本身仍自洽（实际排序另有硬降位）。 */
private const val NOTE_SIDE_RANK_TAKEN_RIGHT: Int = 25

/**
 * 四个斜向的名次上界（[noteSideRank] 里右上 = 0 … 左下 = 3）。
 *
 * 满行页边距那一列的候选会被改写成正右 / 正左的名次（10 / 20），它们几何上是「右上 / 右下」
 * 却不是真斜向——凡是要按「真斜向」判断的地方都用这个上界把它们排除掉。
 */
private const val NOTE_SIDE_RANK_LAST_CORNER: Int = 3

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
 * 让位最多问到第几环（2026-10-29 用户定口）。
 *
 * 让位仍然排在「隔行」之前，但也不必只认本行：隔一行那块空白里如果能靠别人让一让就放下，
 * 比「自己滑到隔两行」更近。再往外就让不起了——离得越远，挪别人的批注越像在拆东墙补西墙。
 */
private const val NOTE_ROOM_MAX_RING: Int = 1

/**
 * 一条批注的一个候选框（纯几何）。
 *
 * 字段只服务两件事：**排序**（整句 → 环数 → 折行 → 就近 → 方向 / 留白）
 * 与**落位**（[rect] 是最终框）。先保整句；同一环里折行最少的先（[lines]），
 * 折行相同才比就近（[distanceBand] / [excessDistance]），最后才比方向与留白档。
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
    /** 框到锚点墨迹的边到边最短距离。 */
    val distance: Float,
    /**
     * 超出这个方向「自然留白」的距离（2026-10-27 用户定口：位置选择改为就近）。
     *
     * 正右 / 正左的自然留白是 [NoteMetrics.sideGap]；正上 / 正下是 1em（竖线引线空档）；
     * 四个斜向是「一个字的横向走廊 + 2px 近侧」的合成。
     * 排序先比这个「额外被拉长的引线」，再比方向：
     * 都在自然留白内时仍按方向名次取位；只有某个方向被迫放得更远时，
     * 才让位给真正贴着选区的方向。手搓候选不传时退回 [distance]。
     */
    val excessDistance: Float = distance,
    /**
     * 就近分档：额外引线长度按**一个行高**一档（2026-10-29 用户定口）。
     *
     * 同一片空白会被长短不一的行割成宽窄、高低不同的几块，差一个行高以内算「同样近」，
     * 这一档里挑最宽的那块（折行最少）——「首先选最近的，其他选最宽的，至少折行少」。
     * 差到一行以上才算真的更远，近的那块先占。
     */
    val distanceBand: Int = 0,
    /**
     * 斜向候选离「角落基准」的横向偏移（2026-10-27 用户定口）：
     * 右上 / 右下取 `rect.left - cornerRightX`，左上 / 左下取 `cornerLeftX - rect.right`，
     * 其余方向为 0。
     *
     * 右上角的定义是「至少比选区首字右偏一个字」，那个位置就是**默认的最近位置**。
     * 空白更靠右时（上一行右端形成的页边距列会把框一路推到行尾）也能凑出「右上」，
     * 但那是**被拉远**的右上：就近距离同为 0 时先比这个偏移，最后才比框顶，
     * 免得更高的远端框凭 `rect.top` 抢走角落里真正的那个位置。
     */
    val cornerOffset: Float = 0f,
    /**
     * 这个候选用哪一档留白（2026-10-24 用户定口）：
     * [NOTE_MARGIN_TIER_PREFERRED] = 四周 1em（正上 / 正下与正侧）；[NOTE_MARGIN_TIER_FAR_MIN] = 远侧收 0.1em。
     * 四个斜向的上下近侧固定 2px，不随档位变化。
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
     * 这个候选是不是来自**基准行**（多行选区取末行）。只作同距、同向时的最后决胜：
     * 首行 / 末行都够近时仍贴末行，保持「基准行」口径稳定；首行更近或更合适时照常赢。
     */
    val onBaseRow: Boolean = false,
    /**
     * 这个候选是从**哪一行**生成的（`lines` 的行下标）。
     *
     * 引线落点**不再**跟着它走（2026-10-25 用户改口）：布线时按「框离选区开头近还是离末尾近」
     * 另挑一头，见 [noteArrowRow]。它只剩逐字几何量不出来时的兜底。
     */
    val anchorRow: Int = -1,
) {
    /**
     * 四个斜向（右上 / 右下 / 左上 / 左下）。
     *
     * 斜向的横向近侧先留 1em（2026-10-31 用户定口，见 `cornerNearPadX`），压缩档只在
     * 换来更少折行 / 更完整的句子时才生成，所以「最近的那个就是 1em 那个」；
     * 纵向固定 2px（那一个字是给 L 形引线留的横向走廊），留白档在这一侧不参与先后。
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
 * 这个方向「贴着选区」时的自然距离：正侧是 1em（引线要从这段空档出去），
 * 正上 / 正下同样是 1em（竖直引线的空档），四个斜向是「横向一个字走廊 + 2px 近侧」的合成。
 *
 * 生成候选（[noteCandidates] 的 `add`）与挪动之后重算名次（[NoteCandidate.movedTo]）共用这一个口径。
 */
internal fun noteNaturalDistance(side: NoteSide, metrics: NoteMetrics): Float = when (side) {
    NoteSide.RIGHT, NoteSide.LEFT -> metrics.sideGap
    NoteSide.ABOVE, NoteSide.BELOW -> metrics.preferredMargin
    NoteSide.ABOVE_RIGHT, NoteSide.BELOW_RIGHT,
    NoteSide.ABOVE_LEFT, NoteSide.BELOW_LEFT ->
        sqrt(metrics.sideGap * metrics.sideGap + NOTE_MIN_MARGIN_PX * NOTE_MIN_MARGIN_PX)
    NoteSide.OTHER -> 0f
}

/**
 * 框被挪到 [rect] 之后，把「就近」三项（距离 / 额外引线 / 就近档）一起重算。
 *
 * 让位与自行挪开只改坐标；不同步这三个字段，排序就会拿**挪之前**的名次去比，
 * 于是「被挤下去两行」的框仍然自称贴着正文，比真正贴着选区的候选先赢（2026-10-29 用户指出）。
 */
internal fun NoteCandidate.movedTo(
    rect: Rect,
    anchors: List<Rect>,
    metrics: NoteMetrics,
): NoteCandidate {
    if (rect == this.rect) return this
    val d = anchors.minOf { rectDistance(rect, it) }
    val excess = d - noteNaturalDistance(side, metrics)
    return copy(
        rect = rect,
        distance = d,
        excessDistance = if (excess <= NOTE_GEOM_EPS) {
            0f
        } else {
            (excess / NOTE_GEOM_EPS).roundToInt() * NOTE_GEOM_EPS
        },
        distanceBand = if (excess <= NOTE_GEOM_EPS) 0 else (excess / metrics.lineH).toInt(),
    )
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
    // 2026-10-26 用户定口：**一行放不下几个字时，不许再往竖里叠**——折出来的行数
    // 不得超过这一行能放下的字数（否则就成了又窄又高的竖条，读者要歪头看）。
    // 整句档会因此直接放弃这种窄缝，改去更宽的空白；只有实在没地方时才截断到这条上限。
    val charsPerLine = max(1, (innerW / metrics.em).toInt())
    val capacity = min((maxHeight / metrics.lineH).toInt(), charsPerLine)
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
 * 一页十条批注时，旧实现会把整页每一行都枚举一遍（一条批注 ~380 个候选）。
 * 改成按批取：先 [NEAR]（本行相邻空间），再一圈一圈 [RINGS]。排序键不变，
 * 所以「取到一批就够」的结论与一次全量生成完全相同；每一圈先整句、放不下再补截断。
 */
internal enum class NoteCandidateScope {
    /** 全量（默认，测试与直接调用用这个）。 */
    ALL,

    /** 只算本行相邻空间：基准行 + 上下相邻行的正右 / 正左 / 正下 / 正上 / 斜向。 */
    NEAR,

    /** 跨行：只算 `ringFrom..ringTo` 这几圈（0 圈归 [NEAR]）。 */
    RINGS,
}

/**
 * 一条批注的候选框，按**环优先**逐格生成（方案 §0.1 / §1.1）。
 *
 * 顺序 = 环（0 = 直接相邻，1 = 隔 1 行…）→ 整句 → 折行数
 * → 就近 → 方向（四个角 → 正右 → 正左 → 正上 → 正下）。段的阶梯已删除
 * （2026-10-27 用户定口），范围只由「空白是否连成一片」决定。所有位置都**贴住锚点**：
 *
 * - **正右看留白**（2026-10-24 用户定口）：锚点末行右侧离版心右缘还有 **1em 以上空白**
 *   就先试正右——代码行 / 标题行这类左对齐的短行右边本来就是空白区，段落中间的行也一样；
 *   整行顶到版心右缘的行也生成右页边距那一列，只是名次排在四个角之后（2026-10-27）。
 * - **右下 / 右上** 的横向基准是**末行第一个字向右一个汉字**；**左下 / 左上** 是
 *   **末行末字符向左一个汉字**。它们都不再贴整个选区的左右缘（§0.2）。
 *   量的是**选区矩形**（选区正文的边），不是逐字墨迹（2026-10-28 用户定口）：斜体 / 标点后面
 *   那截后侧留白属于选区，按墨迹量会把框白往左推；引线走廊的宽度由布线侧按同一把尺子保证
 *   （见 [routeNoteArrow] 的 `cornerInk`）。
 * - 留白分两档（[NOTE_MARGIN_TIER_PREFERRED] / [NOTE_MARGIN_TIER_FAR_MIN]）：空间够时四周 1em；
 *   不够只把**远离正文那一侧**收到 0.1em。**正上 / 正下**近侧的 1em 是竖线引线空档，
 *   一律保留，推不出就不给这个方向；**四个斜向**近侧固定 2px——横向错开的那一个字才是
 *   L 形引线的走廊（2026-10-27 用户定口）。
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
 * @param scope 生成范围：默认全量；布点时按 NEAR → RINGS（一圈一批）分批发。
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

    val marginX = metrics.marginX
    val marginY = metrics.marginY
    val preferred = metrics.preferredMargin

    /**
     * 规则第 6 条的「分割行」：左右都顶满版心的行把上下空白切断，范围到此为止。
     *
     * 2026-10-27 用户定口：**不再按 p 段落截断**（段的规则已删）。范围只由空白是否连成
     * 一片决定——相邻行没顶满版心就能继续往外走，跨过段落边界也可以。
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

    // 基准行不挡扩展：它自己就是锚点所在行。向上下扩展时，只看**要纳入的那一行**
    // 是否顶满版心——顶满就是分割行，范围到此为止。
    var runStart = first
    while (runStart > 0 && !spansContent(lines[runStart - 1])) {
        runStart--
    }
    var runEnd = base
    while (runEnd + 1 < lines.size && !spansContent(lines[runEnd + 1])) {
        runEnd++
    }
    val charW = max(charWidthPx, 1f)
    val baseGlyphs = note.rects
        .filter { nearestLineIndex(it, lines) == base }
        .sortedBy { it.left }
    val firstGlyph = baseGlyphs.firstOrNull() ?: note.rects.minByOrNull { it.left } ?: return emptyList()
    val lastGlyph = baseGlyphs.lastOrNull() ?: note.rects.maxByOrNull { it.right } ?: return emptyList()
    // 「首字符右移一个汉字」/「末字符右移一个汉字」：斜向摆法的横向基准（§0.2）。
    // 2026-10-25 用户定口：**左上 / 左下看选区最右缘再往左一个字**（= 倒数第二个字的右缘），
    // 不是末字的左缘——框的右缘只有落在它左边，才叫「左上 / 左下」；越过它就是「正上方 / 正下方」。
    //
    // 2026-10-28 用户定口：量的是**选区正文右缘**（选区矩形，斜体末尾那截后侧留白也算选区的一部分），
    // 框右缘只比它往左一个字，不要按逐字墨迹再往左挪——那会把框白推一截（真机斜体差 33px）。
    // 引线走廊不够宽不是布点的问题，是布线那把尺子的问题：布线也必须用**选区矩形**量 0.5em 收角
    // （见 [routeNoteArrow] 的 cornerInk），两边同源，走廊自然是一个字减 0.5em。
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
    // 2026-10-26 用户定口：左侧有缩进时，**只有本行最左侧那条**可以优先考虑左侧；
    // 同一行其余批注仍按「右上/右下 → 左上/左下 → 正右 → 正左 → 正上 → 正下」的通常名次。
    // `otherRowLeft` 里存的是其他批注在这一行的最左墨迹，比本条更左就说明本条不是最左。
    val leftmostOnRow = otherRowLeft[base]?.let { it >= firstGlyph.left - NOTE_GEOM_EPS } ?: true
    val leftFirst = leftmostOnRow && when {
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
        // 障碍按 `marginY` 外扩，空白带的上 / 下缘会比「行盒边 + 2px」晚 / 早几像素：
        // 斜向的 y 恰好落在带外 1~2px 时，整块宽空白就被漏掉，只剩页边距那条窄缝
        // （真机「用 var 声明」：本行下方整带宽 1808px，却只找到左侧 198px 的窄列，
        // 框被挤到页左、框右缘离选区右缘差了四个字）。这里按一个 marginY 容差认带，
        // 落位时仍由 `top = max(row.rect.bottom + 2, f.top)` 把框顶推到真正的空白里。
        val band = freeRects.filter {
            y >= it.top - marginY - NOTE_GEOM_EPS && y <= it.bottom + marginY + NOTE_GEOM_EPS
        }
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
        // 规则第 6 条（2026-10-27 用户定口）：满行的左右页边距可以用，但名次排在四个角
        // 之后。满行在相邻一圈时，几何上会被判成右上 / 右下，这里按「页边距那一列」改用
        // 正右 / 正左的名次（10 / 20），不会被当成斜向抢在四个角前面。
        val rowFull = spansContent(lines.getOrNull(rowIndex) ?: baseRow)
        val inRightMargin = contentRight < Float.MAX_VALUE / 2f &&
            left >= contentRight - NOTE_GEOM_EPS
        val inLeftMargin = contentLeft != null &&
            left + size.width <= contentLeft + NOTE_GEOM_EPS
        val marginRank = when {
            rowFull && inRightMargin -> noteSideRank(NoteSide.RIGHT)
            rowFull && inLeftMargin -> noteSideRank(NoteSide.LEFT)
            else -> null
        }
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
        val distance = note.rects.minOf { rectDistance(rect, it) }
        // 这个方向「贴着选区」时的自然距离：正侧是 1em，正上 / 正下同样是 1em（近侧空档），
        // 四个斜向是「横向 1em 引线走廊 + 2px 近侧」的合成（2026-10-27 用户定口）。
        // 排序先比**超出自然留白的那一段**，避免把 1em 的侧边箭头空档误当成「远」。
        val natural = noteNaturalDistance(side, metrics)
        // 亚像素差不算「更远」：浮点误差会让同距的正右输给右上 / 右下。
        // 按 0.5px 分桶，半像素以内的差视觉上分不出来，交给方向名次决胜。
        val excess = distance - natural
        // 斜向离角落基准的横向偏移（见 [NoteCandidate.cornerOffset]）：排序时先比它再比框顶。
        val cornerOffset = when (side) {
            NoteSide.ABOVE_RIGHT, NoteSide.BELOW_RIGHT -> max(0f, rect.left - cornerRightX)
            NoteSide.ABOVE_LEFT, NoteSide.BELOW_LEFT -> max(0f, cornerLeftX - rect.right)
            else -> 0f
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
            distance = distance,
            excessDistance = if (excess <= NOTE_GEOM_EPS) {
                0f
            } else {
                (excess / NOTE_GEOM_EPS).roundToInt() * NOTE_GEOM_EPS
            },
            cornerOffset = cornerOffset,
            distanceBand = if (excess <= NOTE_GEOM_EPS) 0 else (excess / metrics.lineH).toInt(),
            marginTier = tier,
            sideRank = marginRank ?: sideRankOf(side),
            crossed = crossed,
            onBaseRow = rowIndex == base,
            anchorRow = rowIndex,
        )
    }

    /**
     * 正右 / 正左这一列的**竖坐标**。
     *
     * 本行就是垂直居中于本行。规则第 5 条的「上一行 / 下一行的左右两侧」不是那一行自己的位置，
     * 而是本行相邻空间里的**同一块空白**——这一列要从**靠近锚点的那一端**用起，不能居中于那一行：
     * 行距一大，框就被摆到离锚点一整行远（真机「使用 val」：上一行行尾短、右边整块空白，
     * 批注却贴到了上一行文字旁边）。竖直近侧与四个斜向同口径，留 [NOTE_MIN_MARGIN_PX]。
     *
     * @return `NaN` = 这块空白连框高都放不下。
     */
    fun sideColumnTop(rowIndex: Int, row: NoteTextLine, f: Rect, size: NoteBoxSize): Float {
        val lo = f.top
        val hi = f.bottom - size.height
        if (hi < lo - NOTE_GEOM_EPS) return Float.NaN
        val y = when {
            rowIndex == base -> row.rect.center.y - size.height / 2f
            rowIndex < base -> firstRow.rect.top - NOTE_MIN_MARGIN_PX - size.height
            else -> baseRow.rect.bottom + NOTE_MIN_MARGIN_PX
        }
        return y.coerceIn(lo, max(lo, hi))
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
        val top = sideColumnTop(rowIndex, row, f, size)
        if (top.isNaN()) return
        add(rowIndex, ring, tier, f, left, top, size)
    }

    /** 正左：与正右对称（含规则第 6 条：页边距那一列最多折两行）。 */
    fun emitLeft(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, tier: Int) {
        val x = row.rect.left - metrics.sideGap
        val far = farExtra(marginX, tier)
        val right = min(x, f.right)
        val maxW = right - f.left - far
        val maxH = marginColumnMaxHeight(f, f.left + far, right)
        if (maxW <= 1f || maxH <= 1f) return
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return
        val top = sideColumnTop(rowIndex, row, f, size)
        if (top.isNaN()) return
        add(rowIndex, ring, tier, f, right - size.width, top, size)
    }

    /** 正下：近侧 1em（竖直引线要从这段空档出去），下方按档位。 */
    fun emitBelow(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, tier: Int, reserveX: Float?) {
        // 近侧从**字墨迹下缘**算起（不是行盒下缘）：行盒底下那半段行距也是空白（§2.1）。
        // 正下只认**横向压住锚点**的空白带：右边那一列是「右」，不是「下」
        // （否则右侧列空白一高，就会冒出「横向在页边距、却叫正下」的候选，把让位挤掉）。
        if (f.right <= anchor.left + NOTE_GEOM_EPS || f.left >= anchor.right - NOTE_GEOM_EPS) return
        val near = preferred
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
        add(rowIndex, ring, tier, f, left, top, size)
    }

    /** 正上：与正下对称。 */
    fun emitAbove(f: Rect, rowIndex: Int, row: NoteTextLine, ring: Int, tier: Int, reserveX: Float?) {
        // 近侧 1em 从字墨迹上缘算起（行盒顶上那半段行距也算空白）；横向与正下同理，要压住锚点。
        if (f.right <= anchor.left + NOTE_GEOM_EPS || f.left >= anchor.right - NOTE_GEOM_EPS) return
        val near = preferred
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
        add(rowIndex, ring, tier, f, left, bottom - size.height, size)
    }

    /**
     * 四角的**横向近侧**留白（2026-10-31 用户定口）：首选档按 **1em** 起算。
     *
     * 原来四个斜向的横向也贴着空白边缘摆（上一行最后一个字右边几像素就起框），
     * 真机「函数体」：上一行右边还空着两百多像素，框却紧贴着上一行的字根，
     * 看起来像被挤过去的。规则第 13 条的 1em 是「摆放方向那一侧」离正文的间距，
     * 斜向的摆放方向既横也竖，横向同样要 1em；只有 1em 处摆不下时，
     * 压缩档（[NOTE_MARGIN_TIER_FAR_MIN]）才收到 [marginX]（0.1em）。
     *
     * 纵向近侧仍是 [NOTE_MIN_MARGIN_PX]：那 2px 是贴着行盒放，横向错开的这一个字
     * 才是 L 形引线的走廊，不是摆放留白。
     */
    fun cornerNearPadX(tier: Int): Float =
        if (tier == NOTE_MARGIN_TIER_PREFERRED) max(0f, preferred - marginX) else 0f

    /**
     * 四角候选的共用落位：把框压进 [f] 这块空白，返回框与尺寸。
     *
     * @param rightSide true = 框在基准行右侧（右上 / 右下），false = 左上 / 左下。
     * @param above true = 框底贴着这一行上方（右上 / 左上）。
     * @param nearPadX 横向近侧留白（[cornerNearPadX]）；纵向近侧固定 [NOTE_MIN_MARGIN_PX]。
     */
    fun cornerBox(
        f: Rect,
        row: NoteTextLine,
        reserveX: Float?,
        rightSide: Boolean,
        above: Boolean,
        nearPadX: Float,
        farX: Float,
        farY: Float,
    ): Pair<Rect, NoteBoxSize>? {
        val top = if (above) f.top else max(row.rect.bottom + NOTE_MIN_MARGIN_PX, f.top)
        val bottom = if (above) min(row.rect.top - NOTE_MIN_MARGIN_PX, f.bottom) else f.bottom
        val lo: Float
        val hi: Float
        if (rightSide) {
            lo = max(max(cornerRightX, f.left + nearPadX), reserveX ?: f.left)
            hi = f.right - farX
        } else {
            lo = f.left + farX
            hi = min(cornerLeftX, f.right - nearPadX)
        }
        val maxW = hi - lo
        val maxH = bottom - top - farY
        if (maxW <= 1f || maxH <= 1f) return null
        val size = noteBoxFit(text, metrics, maxW, maxH, sizer, completeOnly)
        if (!size.readable) return null
        val left = if (rightSide) lo else hi - size.width
        val boxTop = if (above) bottom - size.height else top
        return Rect(left, boxTop, left + size.width, boxTop + size.height) to size
    }

    /**
     * 四个斜向（右上 / 右下 / 左上 / 左下）：先按首选档（横向 1em）摆，首选档摆不下才要压缩档。
     *
     * 「空间不足才允许压缩」在这里落地：压缩档只在首选档的框放不进同一块空白时才生成，
     * 于是不会再出现「右侧还很空、框却贴着左边」的候选去和 1em 那位同场竞争。
     */
    fun emitCorner(
        f: Rect,
        rowIndex: Int,
        row: NoteTextLine,
        ring: Int,
        tier: Int,
        rightSide: Boolean,
        above: Boolean,
        reserveX: Float?,
    ) {
        val tight = cornerBox(
            f, row, reserveX, rightSide, above,
            nearPadX = cornerNearPadX(tier),
            farX = farExtra(marginX, tier),
            farY = farExtra(marginY, tier),
        ) ?: return
        if (tier == NOTE_MARGIN_TIER_FAR_MIN) {
            val roomy = cornerBox(
                f, row, reserveX, rightSide, above,
                nearPadX = cornerNearPadX(NOTE_MARGIN_TIER_PREFERRED),
                farX = farExtra(marginX, NOTE_MARGIN_TIER_PREFERRED),
                farY = farExtra(marginY, NOTE_MARGIN_TIER_PREFERRED),
            )
            // 压紧留白只在**换来更好的框**时才要（更少的折行 / 更完整的句子 / 更多的字）。
            // 寸土不让的 1em 那位同样放得下时，压缩版就是「右边还空着却贴着字」的那种框。
            if (roomy != null &&
                roomy.second.lines <= tight.second.lines &&
                (roomy.second.complete || !tight.second.complete) &&
                roomy.second.shownChars >= tight.second.shownChars
            ) {
                return
            }
        }
        add(rowIndex, ring, tier, f, tight.first.left, tight.first.top, tight.second)
    }

    fun emitBelowFamily(rowIndex: Int, ring: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        // 同一行左侧还有批注时，框左缘要让过它首字那一格（给它留引线空档）。
        val reserveX = leftReserve(rowIndex)
        // 正下（2026-10-27 用户定口）：近侧固定 1em、只压远侧——那 1em 是竖线引线的空档，
        // 收掉它箭头就只剩几像素。真推不出这段空档时这个方向宁可不用，改由下一环 / 页边距承接。
        val near = preferred
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in bandRects(row.inkBottom + near, anchor.left, below = true)) {
                emitBelow(f, rowIndex, row, ring, tier, reserveX)
            }
        }
        // 四个斜向（2026-10-27 用户定口）：**纵向近侧只留 2px、不推远**——横向特意错开一个字，
        // 就是为了让引线从框的侧边出去、沿那道空隙折成 L 形再竖着进字下（见 [finish]）。
        // 横向近侧与远侧按留白档：先保 1em，空间不足才收 0.1em（见 [cornerNearPadX]）。
        val nearCorner = NOTE_MIN_MARGIN_PX
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            val y = row.rect.bottom + nearCorner
            for (f in bandRects(y, cornerRightX, below = true)) {
                emitCorner(f, rowIndex, row, ring, tier, rightSide = true, above = false, reserveX)
            }
            for (f in bandRects(y, cornerLeftX, below = true)) {
                emitCorner(f, rowIndex, row, ring, tier, rightSide = false, above = false, reserveX = null)
            }
        }
    }

    fun emitAboveFamily(rowIndex: Int, ring: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        val reserveX = leftReserve(rowIndex)
        // 正上：同正下，近侧 1em 是引线空档，推不出就不用这个方向。
        val near = preferred
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in bandRects(row.inkTop - near, anchor.left, below = false)) {
                emitAbove(f, rowIndex, row, ring, tier, reserveX)
            }
        }
        // 四个斜向同 emitBelowFamily：纵向近侧贴着行放，横向近侧按留白档（先保 1em）。
        val nearCorner = NOTE_MIN_MARGIN_PX
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            val y = row.rect.top - nearCorner
            for (f in bandRects(y, cornerRightX, below = false)) {
                emitCorner(f, rowIndex, row, ring, tier, rightSide = true, above = true, reserveX)
            }
            for (f in bandRects(y, cornerLeftX, below = false)) {
                emitCorner(f, rowIndex, row, ring, tier, rightSide = false, above = true, reserveX = null)
            }
        }
    }

    fun emitLeftFamily(rowIndex: Int, ring: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in columnRects(row.rect.left - metrics.sideGap, row.rect, toRight = false)) {
                emitLeft(f, rowIndex, row, ring, tier)
            }
        }
    }

    /**
     * 某一行右侧的空白列。
     *
     * 2026-10-27 用户定口：**满行的右页边距也可以用**，只是名次排在四个角之后
     * （[NoteSide.RIGHT] 的名次本来就是 10）。所以这里不再要求「段末行」或「右侧还有 1em」；
     * 真有页边距空白，[columnRects] 会找出来；没有就自然为空。
     */
    fun emitRightFamily(rowIndex: Int, ring: Int) {
        val row = lines.getOrNull(rowIndex) ?: return
        for (tier in NOTE_MARGIN_TIER_PREFERRED..NOTE_MARGIN_TIER_FAR_MIN) {
            for (f in columnRects(row.rect.right + metrics.sideGap, row.rect, toRight = true)) {
                emitRight(f, rowIndex, row, ring, tier)
            }
        }
    }

    if (scope == NoteCandidateScope.ALL || scope == NoteCandidateScope.NEAR) {
        // 第 0 环：锚点末行的右侧。2026-10-24 用户定口：**不再看是不是段末行**——只要这一行
        // 右侧离版心右缘还有 1em 以上空白，正右就是第一优先级（代码行 / 标题行这类左对齐的短行
        // 右边本来就是空白区，段中行也一样）。满行只剩页边距时也生成，名次排在四个角之后。
        emitRightFamily(base, 0)
        // 规则第 4 条（2026-10-25 用户定口）：左侧就是首选时，它和正右一样进**本行这一批**。
        // 2026-10-27 用户定口：左侧不再只在收尾档生成——它按**环**参与排序，本行左侧是环 0。
        emitLeftFamily(base, 0)
        emitBelowFamily(base, 0)
        emitAboveFamily(first, 0)
        // 规则第 5 条：**上一行和下一行的左右两侧属于本行相邻空间，不算隔行**。
        if (first != base) {
            emitRightFamily(first, 0)
            emitLeftFamily(first, 0)
        }
        if (base + 1 <= runEnd) {
            emitRightFamily(base + 1, 0)
            emitLeftFamily(base + 1, 0)
        }
        if (first - 1 >= runStart) {
            emitRightFamily(first - 1, 0)
            emitLeftFamily(first - 1, 0)
        }
    }

    if (scope == NoteCandidateScope.ALL || scope == NoteCandidateScope.RINGS) {
        // 跨行：范围内由近到远（往下一条、上一条地数），每一圈都带上那一行自己的
        // 左右两侧（上一行右侧对下一行就是右上角，2026-10-24 用户定口）。
        // 分批时只算 `ringFrom..ringTo` 这几圈，调用方拿到够用的位置就不再往下要。
        // 第 k 圈 = 距基准行 **k + 1** 行：相邻行已经算在第 0 圈（规则第 5 条）。
        val lastRing = min(ringTo, lines.size)
        var k = max(1, ringFrom)
        while (k <= lastRing) {
            var any = false
            val down = base + k + 1
            val up = first - k - 1
            if (down <= runEnd) {
                emitBelowFamily(down, k)
                emitRightFamily(down, k)
                emitLeftFamily(down, k)
                any = true
            }
            if (up >= runStart) {
                emitAboveFamily(up, k)
                emitRightFamily(up, k)
                emitLeftFamily(up, k)
                any = true
            }
            if (!any) break
            k++
        }
    }

    return orderedNoteCandidates(out, rightTaken = false).take(maxCandidates)
}

/**
 * 一条批注的候选链顺序（§1.1）：**字典序**比较
 * 「整句 → 越过行数 → 环 → 折行数 → 就近档 → 首选侧 → 方向 → 留白」。
 *
 * **先保整句**（2026-10-27 / 2026-10-29 用户定口）：能整句放下的先于截断（规则 7：
 * 先换更宽的空白）；截断是最后手段。段的阶梯已删除，范围只由空白是否连成一片决定。
 *
 * **同环内先比折行、再比就近**（2026-10-31 用户定口，取代 2026-10-29「就近第一、宽度第二」）：
 * 先把环分开（上一行 / 下一行的左右也算 0 环），同一环里**折行最少的那块先**，
 * 折行相同才比离选区多远（差一个行高以内算同样近，同档再比精确的额外引线长度）。
 * 真机「用 val 声明」：左侧那格贴着字、只多 24px 却要折成两行，右上同环有一行放得下的空位、
 * 只多走 80px——旧口径按「差 56px 跨了一个行高档」选了左边，读者看到「一行放得下却折了」。
 * **方向**仍在距离之后。
 *
 * **四个斜向（右上 / 右下 / 左上 / 左下）近侧只留 2px、横向错开一个字**（2026-10-27
 * 用户定口）：那一个字不是摆放留白，而是给引线走廊留的——引线从框的侧边横着出来，
 * 沿走廊走到选区角外侧，再竖着进字下 / 字上，折成 L 形（见 [routeNoteArrow] 的收尾）。
 * 正上 / 正下相反：它们靠 1em 的竖直空档画引线，推不出空档时**宁可不用这个方向**。
 * 正右 / 正左固定 `sideGap`（也是 1em）。
 *
 * @param rightTaken 该行右侧已经被别的旁注占住：正右让到其他所有方向之后
 *   （2026-10-27 用户定口），不再为了「更靠右」把框推远。
 * @param rightCornerFirst 规则第 8 条的例外（2026-10-25 用户定口）：本条右侧只有本行最右那条，
 *   且它已经放在本行正右 ⇒ 本条回到**右上 → 右下 → 左上 → 左下**的通常顺序，
 *   不再因为「会罩住右侧选区」先走左侧。
 * @param rightExit 规则 3 的例外二（2026-10-26 用户定口）：同一行右侧那条已经落位、
 *   且它只能从上 / 下出线时，这是它的**出线通道**。本条的右上 / 右下候选如果**完整盖住**
 *   这条通道，就改按**左上 → 左下 → 右上 → 右下**排——先让开右侧那条的引线。
 */
internal fun noteCandidateOrder(
    rightTaken: Boolean,
    rightCornerFirst: Boolean = false,
    rightExit: Rect? = null,
): Comparator<NoteCandidate> {
    fun isCorner(side: NoteSide): Boolean = when (side) {
        NoteSide.ABOVE_RIGHT, NoteSide.BELOW_RIGHT,
        NoteSide.ABOVE_LEFT, NoteSide.BELOW_LEFT -> true
        else -> false
    }
    // 右侧没被占时 RIGHT 排在四个角之后（10），被占时让到正左之后、上下之前（25）。
    // 只压**本行**（环 0）的正右：别的行右侧那块空白仍然按正常名次参与（§0.1 0b）。
    fun rank(c: NoteCandidate): Int = when {
        // 例外：右侧只剩本行最右那条、且它已经在行右 ⇒ 四个斜向按通常顺序（右上最先）。
        // 只认**真斜向**（名次 0..3）：页边距那一列的候选已被改写成 10 / 20，不参与这个例外，
        // 否则满行的页边距框会被提回右上 / 右下，抢到四个角前面。
        rightCornerFirst && isCorner(c.side) && c.sideRank <= NOTE_SIDE_RANK_LAST_CORNER ->
            noteSideRank(c.side)
        rightTaken && c.side == NoteSide.RIGHT && c.ring == 0 ->
            max(c.sideRank, NOTE_SIDE_RANK_TAKEN_RIGHT)
        else -> c.sideRank
    }
    /** 右上 / 右下候选是不是把 [rightExit] 整块盖住了（盖住一部分不算「完整挡住」）。 */
    fun blocksExit(c: NoteCandidate): Boolean {
        val exit = rightExit ?: return false
        if (c.side != NoteSide.ABOVE_RIGHT && c.side != NoteSide.BELOW_RIGHT) return false
        return c.rect.left <= exit.left + NOTE_GEOM_EPS &&
            c.rect.right >= exit.right - NOTE_GEOM_EPS &&
            c.rect.top <= exit.top + NOTE_GEOM_EPS &&
            c.rect.bottom >= exit.bottom - NOTE_GEOM_EPS
    }
    fun rankOf(c: NoteCandidate): Int =
        if (blocksExit(c)) coveringSideRank(c.side) else rank(c)
    // 2026-10-27 用户定口（规则 1 / 2 / 3）：先保整句（规则 7：换更宽的空白再截断）。
    // 2026-10-31 用户定口（取代 2026-10-29「就近第一、宽度第二」）：**同环内先比折行、再比就近**。
    // 环数提到就近档之前——本行（上一行 / 下一行的左右也算 0 环）那一圈里，先挑折行最少的，
    // 折行相同才比离选区多远。真机「用 val 声明」：左侧那格几乎贴着字、只多 24px，却要折成
    // 两行；右上同环有空位、一行放得下、只多走 80px——旧口径选了左边（24 与 80 差 56px 跨了
    // 「一个行高」的档），读者看到的是「明明一行放得下却折了」。
    return compareBy<NoteCandidate> { if (it.complete) 0 else 1 }
        // 规则第 10 条仍是硬约束：框与锚点之间隔着别的批注，排在没隔的后面。
        .thenBy { it.crossed }
        // 例外二仍是硬约束：会完整挡住右侧批注出线通道的右上 / 右下，先让到后面。
        .thenBy { if (blocksExit(it)) 1 else 0 }
        // 正右已经被占时，本行正右让到其他方向之后（排在正左、上下之前的那份名次只在
        // 「同样能放」时按 `rank` 比；这里先保证它不会凭「更靠右」抢先）。
        .thenBy { if (rightTaken && it.side == NoteSide.RIGHT && it.ring == 0) 1 else 0 }
        // 环先分家：同环内再比折行与距离（2026-10-31 用户定口）。
        .thenBy { it.ring }
        // 同环内**折行最少的先**（框最短、引线最短），折行相同才比就近档。
        .thenBy { it.lines }
        // 就近：按一个行高分档，同档再比精确距离。
        .thenBy { it.distanceBand }
        .thenBy { it.excessDistance }
        // 距离相同才看首选侧与方向（短行正右 / 有缩进的正左 → 四个角 → 正右 → 正左 → 上 → 下）。
        .thenBy { if (rankOf(it) == NOTE_SIDE_RANK_SIDE_FIRST) 0 else 1 }
        .thenBy { rankOf(it) }
        // 首行 / 末行同样近、同方向时仍贴基准行（末行），保持多行选区的基准行口径。
        .thenBy { if (it.onBaseRow) 0 else 1 }
        // 斜向的留白档不参与先后（比距离），正侧仍然留白档优先。
        .thenBy { if (it.corner) 0 else it.marginTier }
        // 斜向内部先比「离角落基准的横向偏移」：默认最近的位置就是「首字右偏一个字」，
        // 远端那份空白只有基准点被占时才轮到；此前是靠 rect.top 决胜，更高的远端框会反超。
        .thenBy { it.cornerOffset }
        .thenBy { it.marginTier }
        .thenBy { it.rect.top }
        .thenBy { it.rect.left }
}

internal fun orderedNoteCandidates(
    all: List<NoteCandidate>,
    rightTaken: Boolean,
    rightExit: Rect? = null,
): List<NoteCandidate> = all.sortedWith(noteCandidateOrder(rightTaken, rightExit = rightExit))

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
 * 布点阶梯要按「隔几行」排序（§1.1 / §1.5），所以行身份必须跟着几何一起传进来；
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
    /**
     * 这条标注是**划线类**（下划线 / 删除线）：箭头可以进目标行的行盒，
     * 但不得触及 [underlineRects] 或文字墨迹（`docs/新版批注与引线规则.md` 第 9 条，2026-10-28 定口）。
     *
     * 高亮标注恒为 false：箭头停在行盒边框上，不侵入行盒。
     */
    val rowBoxEntry: Boolean = false,
    /** 划线本身在本页的落墨矩形（页坐标）：箭头进得行盒，但停在这条线外侧。空 = 只有墨迹要避。 */
    val underlineRects: List<Rect> = emptyList(),
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
     * 本页正文行（框 + 块序号），布点阶梯按它数「隔几行」（§1.1 / §1.5）。
     * 空 = 从 [textInk] 合并出行、块序号一律 0（旧调用与纯几何测试的退化路径）。
     */
    val textLines: List<NoteTextLine> = emptyList(),
    /**
     * 本页**所有读者标注划线**的落墨带（下划线 / 波浪线 / 删除线，页坐标）。
     *
     * 引线不得压在这些线上（引线规则第 8 条「尽量不穿越文字」）；更要紧的是它们比字墨迹
     * 低一截，行间中线必须按它们的下缘量，否则中线正好落在波浪的波峰上
     * （2026-10-31 真机「函数体」：引线在两行之间横穿，压着波浪线走）。
     * 空 = 页上没有划线标注（旧调用与纯几何测试）。
     */
    val arrowLines: List<Rect> = emptyList(),
) {
    val all: List<Rect> get() = textInk + figures + tables + strokes
}

/**
 * 把渲染层给的图形/线条几何换成页坐标的障碍物（用户层纯转换，不碰排版）。
 *
 * @param shift [readerPageShift] 的结果：章内绝对 Y → 页坐标。
 * @param contentLeft 内容区左缘（插图/背景的 X 是内容区相对坐标）。
 * @param arrowInk 箭头专用的逐字墨迹（页坐标）；空 = 箭头退回行框避让。
 * @param arrowLines 本页读者标注划线的落墨带（页坐标）：引线不得压在线上，行间中线也按它量。
 */
internal fun noteObstacles(
    textInk: List<Rect>,
    pageImages: List<PageImage>?,
    pageBackgrounds: List<PageBackground>?,
    shift: Int,
    contentLeft: Float,
    arrowInk: List<Rect> = emptyList(),
    textLines: List<NoteTextLine> = emptyList(),
    arrowLines: List<Rect> = emptyList(),
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
        arrowLines = arrowLines,
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

    /**
     * 一段三次曲线（只给**没有布点方向**的斜向引线用）。
     *
     * 两端切线仍是垂直的（规则第 5 条）：[c1] 沿「框边法线」外伸、[c2] 沿「行盒边法线」
     * 回缩，所以起点垂直离开框、终点垂直扎向正文，和原来的 L 同一个口径，只是中间那段
     * 不再走直角、改成朝空白那一侧鼓出去（见 `arrowArcFromElbow`）。
     */
    data class Curve(
        override val start: Offset,
        val c1: Offset,
        val c2: Offset,
        override val end: Offset,
    ) : NoteArrow
}

/**
 * 给本页每条批注在**整页范围内统一布点**（纯函数，无 Compose 依赖）。
 *
 * 流水线（`docs/旁注布点与引线-实施方案.md` §5）：
 * 1. 正文按行合并成行框，连同图形 / 表格 / 线条一起当障碍；
 * 2. [maximalFreeRects] 一次算出整页的极大空闲矩形（含四个页边距）；
 * 3. [noteCandidates] 按「整句 → 环 → 折行 → 就近 → 方向」的阶梯为每条批注生成候选框（§1.1）；
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
    // 行盒（正文每一行的框）：竖着进时箭头停在它的边框上（引线规则第 9 条）。
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

    // 分批发候选：先「本行相邻空间」，再一圈一圈跨行；每圈先整句、再补截断。
    // 放不下才继续要下一批。分批顺序按圈走（环 0 整句 → 环 0 截断 → 环 1 …），
    // 而排序键里整句排在环数之前，所以「整句优先」是在**已要到的候选**里成立的：
    // 更外那一圈的整句要等本圈截断也放不下时才会被要进来（每条批注都全页算一遍太贵）。
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
    // 每条批注下一批要算哪一圈、这一圈是整句还是截断：环 0 的整句已在 NEAR 里，
    // 之后按「环 0 截断 → 环 1 整句 → 环 1 截断 → …」推进。
    // 按圈分批是为了不让每条批注都全页算一遍；排序键上整句永远排在截断之前，
    // 所以新一批的整句并进链里后会立刻插到本圈截断的前面（还没要到的圈不参与比较）。
    val nextRing = HashMap<Long, Int>(ordered.size)
    val ringPhase = HashMap<Long, Int>(ordered.size) // 0 = 整句，1 = 截断
    fun nextBatch(note: NoteInk): List<NoteCandidate>? {
        val id = note.annotationId
        val k = nextRing[id] ?: 0
        if (k == 0) {
            nextRing[id] = 1
            // 环 0 的截断候选：NEAR 里整句已经发过，这里只补整句放不下的。
            return generate(note, NoteCandidateScope.NEAR, completeOnly = false)
                .filter { !it.complete }
        }
        if (k > lines.size) return null
        val phase = ringPhase[id] ?: 0
        if (phase == 0) {
            ringPhase[id] = 1
            return generate(note, NoteCandidateScope.RINGS, ringFrom = k, ringTo = k)
        }
        ringPhase[id] = 0
        nextRing[id] = k + 1
        return generate(note, NoteCandidateScope.RINGS, ringFrom = k, ringTo = k, completeOnly = false)
            .filter { !it.complete }
    }

    // 逐条推进：每条按自己的候选链（整句 → 环 → 折行 → 就近 → 方向）取第一个能放下的位置。
    // 2026-10-29 用户定口：截断（加省略号）是最后手段——链上整句一律排在截断之前；
    // 同一行里再按环数、折行数、就近档、精确距离、方向一路比下去。
    val choices = assignNoteCandidates(
        ordered, lines, candidatesByNote, marginX, marginY, metrics, noteTextSizer,
        nextBatch = ::nextBatch,
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
    //
    // 谁先画：**框在上方的那几条先画**（2026-11-01 用户定口，真机「函数体」/「内 {}」）。
    // 同一行的两条批注都要穿过同一段行间空白时，上方的框只有上方那条走廊可走，
    // 下方的框上下都能绕；先画下方的，就会把上面的那条挤到与它叠在一起。
    // 先把上方的路线留给上方的框，后画的看得见它，自然会往另一侧让。
    val routeOrder = out.sortedBy { b ->
        when (chosen[b.annotationId]?.candidate?.side) {
            NoteSide.ABOVE, NoteSide.ABOVE_LEFT, NoteSide.ABOVE_RIGHT -> 0
            else -> 1
        }
    }
    val drawn = ArrayList<NoteArrow>(out.size)
    val arrowById = HashMap<Long, NoteArrow?>(out.size)
    for (b in routeOrder) {
        // 小图标形态没有可指向的框，本来就不画箭头（§2.1）。
        if (b.compact) continue
        // 箭头用逐字墨迹（能穿两字之间）；布点仍用行框。
        val ink = inkById[b.annotationId]?.arrowInk.orEmpty()
        val self = bubbleRects.getValue(b.annotationId)
        // 引线规则第 6 条（2026-10-25 用户定口）：位置定下来后，比一比框离**选区开头**近
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
        // 斜向横向基准的尺子 = 布点用的选区矩形（取同一个目标行）：布点按「选区缘 ± 一个字」摆框，
        // 引线的 0.5em 收角也得按选区矩形量，两边同源走廊才稳定是一个字减 0.5em。
        val cornerInk = if (anchorRow in lines.indices) {
            (inkById[b.annotationId]?.rects.orEmpty()).filter { nearestLineIndex(it, lines) == anchorRow }
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
            // 行盒：高亮标注停在行盒边框上；划线标注进得行盒，但停在墨迹 / 划线外侧（引线规则第 9 条）。
            lineBoxes = lineBoxes,
            targetInk = targetInk,
            rowBoxEntry = inkById[b.annotationId]?.rowBoxEntry == true,
            underlineBands = inkById[b.annotationId]?.underlineRects.orEmpty(),
            pageLines = obstacles.arrowLines,
            cornerInk = cornerInk,
        )
        if (arrow != null) drawn += arrow
        arrowById[b.annotationId] = arrow
    }
    val routed = out.map { b ->
        if (b.compact) b else b.copy(arrow = arrowById[b.annotationId])
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
internal data class NoteChoice(
    val note: NoteInk,
    val candidate: NoteCandidate,
)

/**
 * 全页分配（方案 §1.4 / §5）：**环优先**，每条批注沿自己的链走
 * 「环 k 整句 → 环 k 截断 → 环 k+1 整句 → …」，所有环都失败才退成小椭圆。
 *
 * 先挑顺序（2026-10-24 用户定口，唯一一套）：
 * **正文行自上而下 → 同一行内先让最右那条占正右，再从「左到右」处理其余 → 批注 id**。
 * 「同一行」按**基准行**算——多行选区取所选文字的**末行**（§0.1），不是首行。
 *
 * 唯一例外（2026-10-25 用户定口，规则第 8 条）：**会遮住右侧文字区域的那条排在右侧那几条之后**。
 * 它整句宽会把右边那条的选区整个罩住时，贴着本行右侧那片空白是**右边那条的**——先让右边那条
 * 拿到自己文字旁边的位置，罩人的这条再去左侧找；左侧没有，就自己往下让，不许把右边那条挤走。
 *
 * 每一条批注**只按自己的候选链独立取位**（2026-10-24 用户定口：取消同行成组 / 统一收列）：
 * 初始阶段不把同一行的批注搬到右侧同一列，也不做横向滑移或「躲到旁边重新折行」。
 *
 * **让位是环 0 的后备手段**（2026-10-24 用户定口）：这条批注的环 0 整句候选放不下，
 * 才逐个候选去问挡路的既有批注能不能上下 / 左右让一让；正右 / 正左 / 四角往上下让，
 * 正上 / 正下往左右让；上下让不动时，正右 / 正左还可以在挡路框右侧找空（不搬动别人）。
 * 只移动**刚好够放下新框**的距离；让完仍越界 / 撞上别的批注 / 仍然放不下新框，就**不让**。
 * 会遮住右侧文字区域的那条**不搬别人**：它就是来让位的，自己继续往远处找。
 *
 * 已选框立即成为后选框的障碍（四周 0.1em），先挑者不会被无故挤走。初始放置不做两两互换；
 * 全部落位后再做一次「长的放外侧、短的放内侧」的综合平移（[arrangeSharedBlankNotes]），
 * 顺序可复现。
 */
private fun assignNoteCandidates(
    notes: List<NoteInk>,
    lines: List<NoteTextLine>,
    candidatesByNote: Map<Long, List<NoteCandidate>>,
    marginX: Float,
    marginY: Float,
    metrics: NoteMetrics,
    sizer: NoteTextSizer,
    /** 本行附近放不下时才调：取下一批候选（同一环的截断，或下一圈）；`null` = 没有更多了。 */
    nextBatch: (NoteInk) -> List<NoteCandidate>? = { null },
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
                cur = cur.movedTo(r, note.rects, metrics)
            }
            if (dead) continue
            if (fits(cur)) return if (cur.rect == c.rect) null else cur
        }
        return null
    }

    /** 把这条批注直接放在 [spot]（已经腾好的位置）。 */
    fun commitSpot(note: NoteInk, spot: NoteCandidate) {
        chosen[note.annotationId] = spot
        occupied += NoteChoice(note, spot)
    }

    fun take(note: NoteInk, c: NoteCandidate): Boolean {
        val spot = if (fits(c)) c else slideFree(note, c) ?: return false
        commitSpot(note, spot)
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
     * 规则 3 的例外二（2026-10-26 用户定口）：同一行**右侧那条已经落位**时，它朝向自己锚点的
     * 出线通道——贴着框**朝正文的那条竖边**（右侧摆法从左边出线）、一个行高的通道。
     * 本条的右上 / 右下候选完整盖住它，就要先让到左上 / 左下。
     *
     * 右侧那条落在正右（引线横着出去）或锚点与框纵向重叠时没有上下通道，返回 `null`。
     * 2026-10-27 修正：通道在框的**左缘**（朝正文一侧），不是右缘；只认真正在本条
     * 右边的批注（框左缘在本条基准行墨迹右缘之外），并取**离本条最近**的那条——
     * 它的出线通道最容易被本条的右上 / 右下盖住。
     */
    fun rightExitFor(note: NoteInk): Rect? {
        val row = baseRow(note)
        // 多行选区只看**基准行**那一段墨迹的右缘：整段并集的右缘可能来自首行，
        // 用它会把「同一行右侧」判得过宽（2026-10-27）。
        val rowInkRight = note.rects
            .filter { nearestLineIndex(it, lines) == row }
            .maxOfOrNull { it.right }
            ?: rectUnion(note.rects).right
        val right = occupied
            .filter {
                it.note.annotationId != note.annotationId &&
                    baseRow(it.note) == row &&
                    it.candidate.rect.left >= rowInkRight - NOTE_GEOM_EPS
            }
            // 取**离本条最近**的那条右侧批注：它的出线通道最容易被本条的右上 / 右下盖住。
            .minByOrNull { it.candidate.rect.left }
            ?: return null
        val box = right.candidate.rect
        val ink = rectUnion(right.note.rects)
        val band = metrics.lineH
        return when {
            ink.bottom <= box.top + NOTE_GEOM_EPS ->
                Rect(box.left, box.top - band, box.left + metrics.em, box.top)
            ink.top >= box.bottom - NOTE_GEOM_EPS ->
                Rect(box.left, box.bottom, box.left + metrics.em, box.bottom + band)
            else -> null
        }
    }

    /**
     * 按「整句 → 环 → 折行数 → 就近档 → 精确距离 → 方向 → 留白档」取这条批注的候选链。
     *
     * 逐圈要候选时池子会一批批变大，这里只把**新进来的那批**排序，再线性并进已排好的链：
     * 每批都整池重排会让「一圈一圈试」比一次全量还慢。
     * 缓存按「已并到第几个 + 正右是否被占 + 右侧出线通道」校验，后两者会随已放批注变化。
     */
    val chainCache = HashMap<Long, List<NoteCandidate>>(notes.size)
    val chainMerged = HashMap<Long, Int>(notes.size)
    val chainTaken = HashMap<Long, Boolean>(notes.size)
    val chainRightCorner = HashMap<Long, Boolean>(notes.size)
    val chainExit = HashMap<Long, Rect?>(notes.size)
    fun chainFor(note: NoteInk): List<NoteCandidate> {
        val all = pool[note.annotationId].orEmpty()
        val id = note.annotationId
        val taken = rightTaken(note)
        // 规则第 8 条例外：右侧只剩本行最右那条、且它已经在行右 ⇒ 本条右上优先。
        val rightCornerFirst = rightmostOnRight[id]?.let { owner ->
            chosen[owner]?.let { it.side == NoteSide.RIGHT && it.ring == 0 } == true
        } ?: false
        val rightExit = rightExitFor(note)
        val cached = chainCache[id]
        val merged = chainMerged[id] ?: 0
        if (cached != null && merged == all.size && chainTaken[id] == taken &&
            chainRightCorner[id] == rightCornerFirst && chainExit[id] == rightExit
        ) {
            return cached
        }
        val order = noteCandidateOrder(taken, rightCornerFirst, rightExit)
        val next = if (cached != null && chainTaken[id] == taken &&
            chainRightCorner[id] == rightCornerFirst && chainExit[id] == rightExit &&
            merged <= all.size
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
        chainExit[id] = rightExit
        return next
    }

    /** 两块空白是不是同一块 / 同一列（只要有一点重叠就算，跨行批注也认同一列）。 */
    fun sharedBlank(a: Rect, b: Rect): Boolean =
        min(a.right, b.right) - max(a.left, b.left) > 1f &&
            min(a.bottom, b.bottom) - max(a.top, b.top) > 1f

    /** 一个算好、还没落地的让位方案：挡路的几条挪到 [moved]，这条批注落在 [fresh]。 */
    class RoomPlan(val moved: List<NoteChoice>, val fresh: NoteCandidate)

    /**
     * 一个算好、还没落地的落位：候选框 + 是不是**挪出来**的 + 让别人让位时的搬运清单。
     *
     * [order] 是它来自候选链上的第几条：两个落位按 [nearer] 的判据一样好时，
     * 链上更靠前的那个赢——链序本身已经含了方向偏好，挪出来的位置不该凭「挪」这件事占先。
     */
    class Spot(
        val candidate: NoteCandidate,
        val pushed: Boolean,
        val room: RoomPlan? = null,
        val order: Int = 0,
    )

    /**
     * 让占住同一块空白的既有批注**上下让一让**，给 [c] 腾出位置。
     *
     * 2026-10-24 用户定口：
     * - **让位排在「隔行」之前**：本行（环 0）的位置只要别人让一让就能放下，就先让别人让，
     *   而不是直接跑去隔一行 / 隔两行的地方。
     * - **让完要真能把新批注放下**，包括新批注**折行**与**不折行**两种：把挡路框推到这块空白的
     *   上缘或下缘（正上 / 正下则推到左缘或右缘），剩下的那条空档里重新折行夹取整句；
     *   实在夹不下就是「无路可让」。
     * - 只认整句档；正右 / 正左 / 四角往上下让，正上 / 正下往左右让（左右让不出去再往上下让）；
     *   上下让不动时，正右 / 正左还可以在挡路框旁边找空（不搬动别人）。
     * - 上 / 下两种排法都可行时取位移小的（一样大取上移），保证同一输入结果恒定。
     *
     * @return 让位方案（**还没动任何框**，采信时交给 [commitRoomPlan]）；无路可让时为 `null`。
     */
    fun makeRoom(note: NoteInk, c: NoteCandidate): RoomPlan? {
        if (!c.complete) return null
        // 规则第 8 条（2026-10-25 用户定口）：会遮住右侧文字区域的那条**自己是来让位的**，
        // 不许反过来把右侧那条挤走——它继续往远处找位置，直到不挡别人的选区。
        if (note.annotationId in covering) return null
        // 规则第 8 条：先让挡路的批注挪一挪。**上下 / 斜向**摆的框，挡路框是竖着排的，往上下让；
        // **正上 / 正下**摆的框，挡路框是横着排的，往左右让。
        // 2026-10-25 用户定口：斜向也让位——同一行左边那条被往上挤一点、右边那条占到它原来的格子，
        // 引线不会交叉，可以接受。
        val verticalShift = c.side != NoteSide.ABOVE && c.side != NoteSide.BELOW
        // 2026-10-25 用户定口：**不再要求挡路框和新框在同一块空白**——挡路框只要挪完还在
        // 它自己的空白里就行（下面的校验逐个检查），隔壁空白里那条也该让一让。
        val blockers = occupied.filter { c.conflicts(it.candidate, marginX, marginY) }
        if (blockers.isEmpty()) return null
        val blockerIds = blockers.map { it.note.annotationId }.toSet()
        val f = c.freeRect
        val myRow = baseRow(note)

        /** 把挡路框整体挪 (dx, dy)，返回它们的新位置。 */
        fun shiftBlockers(dx: Float, dy: Float): List<NoteChoice> = blockers.map { b ->
            val r = b.candidate.rect
            val movedRect = Rect(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
            NoteChoice(b.note, b.candidate.movedTo(movedRect, b.note.rects, metrics))
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
            // 行自上而下的硬顺序（2026-10-24 用户定口）：后面那行的批注不许把前面那行已放好的挤走。
            // 否则上一行的批注会被拖到下一行旁边，读者会以为它说的是下一行（引线还要多绕一截）。
            // `attemptBeside` 不移动别人，不受这条限制（2026-10-27）。
            if (blockers.any { baseRow(it.note) < myRow }) return null
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
            if (blockers.any { baseRow(it.note) < myRow }) return null
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
            ).movedTo(rect, note.rects, metrics)
            if (occupied.any { fresh.conflicts(it.candidate, marginX, marginY) }) return null
            // 名次排在所有「上下让位」之后：能让人家让就让，实在让不动才自己挪到旁边。
            return Float.MAX_VALUE / 2f to (emptyList<NoteChoice>() to fresh)
        }

        val tried = if (verticalShift) {
            // 「往挡路框右侧找空」只认正右 / 正左（规则第 8 条后半讲的就是右侧那一条）。
            val beside = if (c.side == NoteSide.RIGHT || c.side == NoteSide.LEFT) attemptBeside() else null
            listOfNotNull(attemptVertical(up = true), attemptVertical(up = false), beside)
        } else {
            // 正上 / 正下那一档，挡路框本来是横着排的，先往左右让。环 0 里还有别的方向可选，
            // 就先别动「不该动的那条轴」；**隔行的候选**是这条批注贴着选区的最后一次机会，
            // 左右都让不出去时连上下也试（2026-10-29 用户定口：让位以后要能落回更近的位置，
            // 不能让「同行只能左右让」把本该贴在本行下方的批注推到隔两行去）。
            listOfNotNull(
                attemptHorizontal(toLeft = true),
                attemptHorizontal(toLeft = false),
                if (c.ring > 0) attemptVertical(up = true) else null,
                if (c.ring > 0) attemptVertical(up = false) else null,
            )
        }
        val pick = tried.minWithOrNull(compareBy({ it.first })) ?: return null
        // **先判后动**：让位结果只是一份搬运清单，采信时再由 [commitRoomPlan] 落地。
        return RoomPlan(pick.second.first, pick.second.second)
    }

    /**
     * 采信一份让位清单：把挡路的几条挪到位、这条批注落在 [RoomPlan.fresh]。
     *
     * 只改已放批注的坐标，不动候选池——所以「先算清单、后面又找到了更近的位置」时
     * 直接丢掉清单就行，不需要回滚。
     */
    fun commitRoomPlan(note: NoteInk, plan: RoomPlan) {
        for (m in plan.moved) {
            chosen[m.note.annotationId] = m.candidate
            val idx = occupied.indexOfFirst { it.note.annotationId == m.note.annotationId }
            if (idx >= 0) occupied[idx] = m
        }
        chosen[note.annotationId] = plan.fresh
        occupied += NoteChoice(note, plan.fresh)
    }

    /**
     * 两个落位谁更靠前：整句 → 环 → 折行数（越少越宽）→ 就近档。
     *
     * 与候选链的排序键同一套口径（[noteCandidateOrder]，2026-10-31 用户定口：同环内折行先于就近）。
     * **同一档里的零头不比**：一个行高以内视觉上分不出远近，
     * 交给链序（[Spot.order]）决胜，免得「滑开 10px」输给「换到另一侧」。
     */
    fun nearer(a: NoteCandidate, b: NoteCandidate): Boolean = when {
        a.complete != b.complete -> a.complete
        a.ring != b.ring -> a.ring < b.ring
        a.lines != b.lines -> a.lines < b.lines
        a.distanceBand != b.distanceBand -> a.distanceBand < b.distanceBand
        else -> false
    }

    /** 两个落位谁更该采信：[nearer] 的判据先比，同一方向上再比零头，最后按候选链的先后。 */
    fun before(a: Spot, b: Spot): Boolean {
        if (nearer(a.candidate, b.candidate)) return true
        if (nearer(b.candidate, a.candidate)) return false
        // 只有**同一方向**的两个落位才比那几十像素的零头：不同方向的「自然留白」本来就不一样长，
        // 跨方向比零头没有意义（否则「滑开 10px」会输给「换到另一侧」）。
        if (a.candidate.side == b.candidate.side) {
            val diff = a.candidate.excessDistance - b.candidate.excessDistance
            if (diff < -NOTE_GEOM_EPS) return true
            if (diff > NOTE_GEOM_EPS) return false
        }
        return a.order < b.order
    }

    /** 候选链上第一个**原样就能放下**的位置。 */
    fun freeSpot(note: NoteInk): Spot? {
        chainFor(note).forEachIndexed { i, c ->
            if (fits(c)) return Spot(c, pushed = false, order = i)
        }
        return null
    }

    /**
     * 候选链上**挪出来的**位置里最近的一个：自己沿空白滑开，或让别人让位。
     *
     * 只留最近的那一份（整句优先、就近档更小）：滑开与让位都可能把框推到隔行去，
     * 而链上后面还有真正贴着选区的空白时，当然选贴着的那个。
     */
    fun pushedSpot(note: NoteInk, allowRoom: Boolean): Spot? {
        var best: Spot? = null
        fun consider(spot: Spot) {
            val cur = best
            if (cur == null || before(spot, cur)) best = spot
        }
        for ((i, c) in chainFor(note).withIndex()) {
            val cur = best
            // 已经贴着自然留白、又是最窄的一行：没有更近的可能，不用再往下比。
            if (cur != null && cur.candidate.complete &&
                cur.candidate.distanceBand == 0 && cur.candidate.lines == 1 &&
                cur.candidate.excessDistance <= NOTE_GEOM_EPS
            ) {
                break
            }
            if (cur?.candidate?.complete == true && !c.complete) continue
            // 自己先沿空白挪开；实在挪不开才让别人让位（让位排在「自己挪开」之后）。
            // 让位排在隔行之前，但也不必只认本行：隔一行那块空白里如果能靠别人让一让就放下，
            // 比「自己滑到隔两行」更近（2026-10-29 用户定口）。最多问到隔一行。
            val slid = slideFree(note, c)
            if (slid != null) {
                consider(Spot(slid, pushed = true, order = i))
                continue
            }
            if (allowRoom && c.ring <= NOTE_ROOM_MAX_RING && roomTried.add(c)) {
                val plan = makeRoom(note, c) ?: continue
                consider(Spot(plan.fresh, pushed = true, room = plan, order = i))
            }
        }
        return best
    }

    /**
     * 从当前候选池里按顺序取一条能放下的位置。
     *
     * 候选链已经按「整句 → 环 → 折行数 → 就近档 → 方向」排好，所以一路扫下去就是规则顺序。
     * 截断候选因此只在整句都放不下时才会被取到——它是最后手段（2026-10-29 用户定口）。
     *
     * 2026-10-29 用户定口（真机「函数就是功能呀！老外的词汇太贫乏了。」）：**让位 / 自己滑开
     * 得到的位置不是定论**——它可能已经被挤出隔两行，而再往外一圈还有一条真正贴着选区的空白。
     * 所以这里先把「挪出来的位置」记着，继续沿链（必要时再要下一圈候选）找更近的那个：
     * 找到现成的、且明显更近的（整句优先、就近档更小）就用它，刚才那份挪动作废。
     */
    fun placeNote(note: NoteInk, allowRoom: Boolean = true): Boolean {
        var best: Spot? = null
        /** 还愿意往后看几圈：挪出来的位置被挤出去几档，就再往后看几圈。 */
        var lookUntil = 0
        var seenRing = 0
        while (true) {
            val free = freeSpot(note)
            val pushed = pushedSpot(note, allowRoom)
            val cand = when {
                pushed == null -> free
                free == null -> pushed
                // 挪出来的位置只有确实更靠前才值得要（2026-10-29 用户定口：让位以后可以
                // 另找更近的位置）；一样好时按候选链的先后，现成的那个不因为「没动别人」就吃亏。
                before(pushed, free) -> pushed
                else -> free
            }
            if (cand != null && (best == null || before(cand, best))) best = cand
            val cur = best
            if (cur != null) {
                // 现成的位置就是这一带最合适的：直接落。
                // 挪出来的位置还要再看几圈——它可能已经被挤出隔两行（下面是 [lookUntil] 的口径）。
                if (!cur.pushed) break
                lookUntil = cur.candidate.ring + max(1, cur.candidate.distanceBand)
                if (seenRing > lookUntil) break
            }
            val batch = nextBatch(note) ?: break
            addBatch(note, batch)
            seenRing = max(seenRing, batch.maxOfOrNull { it.ring } ?: seenRing)
        }
        val spot = best ?: return false
        if (spot.room != null) commitRoomPlan(note, spot.room) else commitSpot(note, spot.candidate)
        return true
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
        val chain = chainFor(note)
        val best = chain.firstOrNull()
        for (c in chain) {
            // 只认「规则第 3 条提前过的正右」：这一行右侧确实有 1em 以上空白才值得先占。
            // 只剩页边距那一窄列（规则第 6 条）不算——那种列该排在四个角之后，
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
            // 2026-10-29 用户定口：例外一的前提是「正右这一列对这一条不算差」。
            // 本行右侧被长短不一的行割成窄列时，这一列会把句子挤成七八行，而同一档里
            // 明明有更宽的位置（折行更少）——那就按正常判据取位，右边那列留给别人。
            if (best != null && best !== c && c.lines > best.lines) continue
            if (take(note, c)) return true
            // 规则第 8 条：右侧被别人占了，先让别人上下让一让。
            if (!roomTriedHere) {
                roomTriedHere = true
                val plan = makeRoom(note, c)
                if (plan != null) {
                    commitRoomPlan(note, plan)
                    return true
                }
            }
        }
        return false
    }

    // 先挑顺序：正文行自上而下 → 同一行从左到右 → 批注 id。
    // 例外（2026-10-25 用户定口，规则第 8 条）：会遮住右侧文字区域的那条排到同排最后，
    // 让右边那条先拿走自己文字旁边的位置；它再走左侧 / 往下让。
    val order = notes.sortedWith(
        compareBy<NoteInk> { baseRow(it) }
            .thenBy { if (it.annotationId in covering) 1 else 0 }
            .thenBy { noteRightOnRow(it.rects, lines, baseRow(it)) }
            .thenBy { it.annotationId },
    )

    // 第 1 档：整句。逐行走（自上而下），行内从左到右，每条按自己的候选链独立取位；
    // **让位排在隔行之前**（2026-10-24 用户定口）：本行的候选只差别人让一让，就先让别人让，
    // 而不是直接跑到隔一行、隔两行的地方去。让位只在 `ring == 0` 问。
    var start = 0
    while (start < order.size) {
        val rowIndex = baseRow(order[start])
        var end = start + 1
        while (end < order.size && baseRow(order[end]) == rowIndex) end++
        val members = order.subList(start, end)
        // 规则第 3 条的例外一：本行**最右那条**先试正右（它右侧那块空白先归它）。
        // 例外**只包含正右**：正右放不下就作废，这条按正常顺序（从左到右）取位——
        // 不许借预排去占其他方向，否则它会先挑走本行共享空白里离正文最近的那一格。
        // 「最右」要与别人拉开（同一条选区的几条不算）。
        if (members.size > 1) {
            val rightmost = members.maxWith(
                compareBy({ noteRightOnRow(it.rects, lines, rowIndex) }, { it.annotationId }),
            )
            val rightmostRight = noteRightOnRow(rightmost.rects, lines, rowIndex)
            val others = members
                .filter { it.annotationId != rightmost.annotationId }
                .maxOf { noteRightOnRow(it.rects, lines, rowIndex) }
            if (rightmostRight > others + NOTE_GEOM_EPS) {
                tryRightFirst(rightmost)
            }
        }
        for (note in members) {
            if (chosen.containsKey(note.annotationId)) continue
            placeNote(note)
        }
        start = end
    }

    // 规则 5（2026-10-27 用户定口）：同一个大空白区里的多条批注综合安排——
    // 长的放外侧、短的放内侧，把靠近正文的走廊留给短批注的引线。
    val arranged = arrangeSharedBlankNotes(occupied, marginX, marginY)
    for (c in arranged) chosen[c.note.annotationId] = c.candidate

    return order.mapNotNull { note -> chosen[note.annotationId]?.let { NoteChoice(note, it) } }
}

/**
 * 规则第 13 条：上下默认留白 = **当前正文行距让出来的纯空白**（行盒节距 − 行盒高）。
 *
 * 取同段相邻行的节距中位数与行盒高中位数，避免一两个大标题把整页带偏；
 * 量不出来（单行页、行重叠）时退回 [fallback]，并且无论如何不小于 2px。
 */
/**
 * 规则 5（2026-10-27 用户定口）：同一块大空白里的多条批注要**综合安排**，不是先到先得。
 *
 * 长的批注放到外侧（远离正文），短的放内侧（贴近正文），把靠近正文的那条走廊留给
 * 短批注的引线，避免长框横在短批注与正文之间。
 *
 * **只按「同一个大的空白区」认**（2026-10-27 用户定口）：不按侧别分家，也不要求两条的
 * 文字位置相邻。两条的「内 / 外」沿**这块空白自己的朝外方向**量——锚点正文在空白上方就是
 * 「向下」、在下方就是「向上」、在左侧就是「向右」、在右侧就是「向左」，与候选名叫「正下」
 * 还是「右下」无关，所以右侧页边距里的正右与右上、右下也在同一个深度轴上。沿这个轴平移，
 * 不改变行、不改变方向，也不动别的批注；短的接长的那条原来的内缘，长的紧贴短的外缘
 * （留 2 倍 margin，与让位一致），不会因为两条高低不同而在中间空出一截。换完仍要满足各自的空白边界
 * 与所有已放框的间距；任何一步不满足就保持原样，最多迭代 4 轮直到稳定。
 */
internal fun arrangeSharedBlankNotes(
    occupied: List<NoteChoice>,
    marginX: Float,
    marginY: Float,
): List<NoteChoice> {
    if (occupied.size < 2) return occupied
    val out = occupied.toMutableList()

    /** 朝外平移：向下往下、向上往上、向右往右、向左往左。 */
    fun shifted(outward: NoteOutward, r: Rect, delta: Float): Rect = when (outward) {
        NoteOutward.DOWN -> Rect(r.left, r.top + delta, r.right, r.bottom + delta)
        NoteOutward.UP -> Rect(r.left, r.top - delta, r.right, r.bottom - delta)
        NoteOutward.RIGHT -> Rect(r.left + delta, r.top, r.right + delta, r.bottom)
        NoteOutward.LEFT -> Rect(r.left - delta, r.top, r.right - delta, r.bottom)
    }

    /** 批注文字长度：长批注可能是折成好几行的窄框，不能用某个方向上的框宽代替。 */
    fun textLength(c: NoteChoice): Int = c.note.text.count { it != '\n' }

    /** 长的那条还在内侧时，返回需要让出的深度；已经在外侧则返回 0。 */
    fun innerGap(long: NoteChoice, short: NoteChoice, outward: NoteOutward): Float {
        val gap = when (outward) {
            NoteOutward.DOWN -> short.candidate.rect.top - long.candidate.rect.top
            NoteOutward.UP -> long.candidate.rect.bottom - short.candidate.rect.bottom
            NoteOutward.RIGHT -> short.candidate.rect.left - long.candidate.rect.left
            NoteOutward.LEFT -> long.candidate.rect.right - short.candidate.rect.right
        }
        return max(0f, gap)
    }

    fun inOwnBlank(c: NoteCandidate, r: Rect): Boolean =
        r.left >= c.freeRect.left - NOTE_GEOM_EPS &&
            r.right <= c.freeRect.right + NOTE_GEOM_EPS &&
            r.top >= c.freeRect.top - NOTE_GEOM_EPS &&
            r.bottom <= c.freeRect.bottom + NOTE_GEOM_EPS

    fun reflowOne(choice: NoteChoice, r: Rect): NoteChoice = NoteChoice(
        choice.note,
        choice.candidate.copy(
            rect = r,
            distance = choice.note.rects.minOf { rectDistance(r, it) },
        ),
    )

    var pass = 0
    var arranged = true
    while (arranged && pass < 4) {
        arranged = false
        pass++
        for (i in out.indices) {
            for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                val outward = blankOutward(a, b) ?: continue
                if (!blanksOverlap(a.candidate.freeRect, b.candidate.freeRect)) continue
                val aLen = textLength(a)
                val bLen = textLength(b)
                if (aLen == bLen) continue
                val long = if (aLen > bLen) a else b
                val short = if (long === a) b else a
                val delta = innerGap(long, short, outward)
                if (delta <= NOTE_GEOM_EPS) continue
                val gap = when (outward) {
                    NoteOutward.UP, NoteOutward.DOWN -> marginY
                    NoteOutward.RIGHT, NoteOutward.LEFT -> marginX
                } * 2f
                // 先试「贴紧」：短的接长的那条原来的内缘，长的紧贴短的外缘（留 2 倍 margin）。
                // 只交换上缘的话，短框比长框矮多少，两条之间就会空出多少（真机上曾空出 52px）。
                // 贴紧放不下（越出空白或撞别人）再退回只交换深度。
                val inner = depthOf(long.candidate.rect, outward)
                val packedShort = rectAtDepth(short.candidate.rect, outward, inner)
                val packedLong = rectAtDepth(
                    long.candidate.rect,
                    outward,
                    inner + extentOf(short.candidate.rect, outward) + gap,
                )
                val attempts = arrayOf(
                    packedLong to packedShort,
                    shifted(outward, long.candidate.rect, delta) to
                        shifted(outward, short.candidate.rect, -delta),
                )
                var applied = false
                for ((longRect, shortRect) in attempts) {
                    if (!inOwnBlank(long.candidate, longRect) || !inOwnBlank(short.candidate, shortRect)) continue
                    val newLong = reflowOne(long, longRect)
                    val newShort = reflowOne(short, shortRect)
                    if (newLong.candidate.conflicts(newShort.candidate, marginX, marginY)) continue
                    var blocked = false
                    for (k in out.indices) {
                        if (k == i || k == j) continue
                        if (newLong.candidate.conflicts(out[k].candidate, marginX, marginY) ||
                            newShort.candidate.conflicts(out[k].candidate, marginX, marginY)
                        ) {
                            blocked = true
                            break
                        }
                    }
                    if (blocked) continue
                    val longIndex = if (long === a) i else j
                    val shortIndex = if (short === a) i else j
                    out[longIndex] = newLong
                    out[shortIndex] = newShort
                    arranged = true
                    applied = true
                    break
                }
                if (!applied) continue
            }
        }
    }
    return out
}

/** 批注框相对自己锚点正文的朝外方向，也就是「深度」的测量轴。 */
private enum class NoteOutward { UP, DOWN, LEFT, RIGHT }

/** 沿朝外方向的深度：越靠外越大。 */
private fun depthOf(r: Rect, outward: NoteOutward): Float = when (outward) {
    NoteOutward.DOWN -> r.top
    NoteOutward.UP -> -r.bottom
    NoteOutward.RIGHT -> r.left
    NoteOutward.LEFT -> -r.right
}

/** 框在朝外方向上的长度。 */
private fun extentOf(r: Rect, outward: NoteOutward): Float = when (outward) {
    NoteOutward.DOWN, NoteOutward.UP -> r.height
    NoteOutward.RIGHT, NoteOutward.LEFT -> r.width
}

/** 把框平移到指定深度（只动朝外方向上的那一轴）。 */
private fun rectAtDepth(r: Rect, outward: NoteOutward, depth: Float): Rect = when (outward) {
    NoteOutward.DOWN -> Rect(r.left, depth, r.right, depth + r.height)
    NoteOutward.UP -> Rect(r.left, -depth - r.height, r.right, -depth)
    NoteOutward.RIGHT -> Rect(depth, r.top, depth + r.width, r.bottom)
    NoteOutward.LEFT -> Rect(-depth - r.width, r.top, -depth, r.bottom)
}

/**
 * 同一块空白里两条批注共用的朝外方向：看**锚点正文**落在空白的哪一侧，而不是看各自的框
 * 贴在锚点的哪个角。右侧页边距里的批注即使框在锚点上方（右上候选），朝外方向仍是「向右」；
 * 正文下方那块空白里的批注，不论候选叫正下还是右下，朝外方向都是「向下」。
 */
private fun blankOutward(a: NoteChoice, b: NoteChoice): NoteOutward? {
    val ink = rectUnion(a.note.rects + b.note.rects)
    val blank = a.candidate.freeRect
    val cx = (ink.left + ink.right) / 2f
    val cy = (ink.top + ink.bottom) / 2f
    val dx = (blank.left + blank.right) / 2f - cx
    val dy = (blank.top + blank.bottom) / 2f - cy
    val insideX = cx >= blank.left && cx <= blank.right
    val insideY = cy >= blank.top && cy <= blank.bottom
    return when {
        insideX && !insideY -> if (dy > 0f) NoteOutward.DOWN else NoteOutward.UP
        insideY && !insideX -> if (dx > 0f) NoteOutward.RIGHT else NoteOutward.LEFT
        !insideX && !insideY ->
            if (abs(dx) >= abs(dy)) {
                if (dx > 0f) NoteOutward.RIGHT else NoteOutward.LEFT
            } else {
                if (dy > 0f) NoteOutward.DOWN else NoteOutward.UP
            }
        else -> null
    }
}

/** 两块空白是不是同一块 / 同一列（只要有一点重叠就算，跨行批注也认同一列）。 */
private fun blanksOverlap(a: Rect, b: Rect): Boolean =
    min(a.right, b.right) - max(a.left, b.left) > 1f &&
        min(a.bottom, b.bottom) - max(a.top, b.top) > 1f

/**
 * 一条批注的**基准行**：所选文字在本页的**最后一行**（§0.1「多行选区以末行为基准行」）。
 *
 * 同一行分组、行自上而下都按它排序；同行的先后由分配阶段按「先最右那条、其余从左到右」决定。
 * 旧实现拿整段墨迹并集的最近行，
 * 多行选区会被算成**首行**，于是同一条末行上的几条批注被拆进不同组、位置互相打架。
 */
private fun noteBaseRow(rects: List<Rect>, lines: List<NoteTextLine>): Int? =
    rects.mapNotNull { nearestLineIndex(it, lines) }.maxOrNull()

/**
 * 引线该指哪一行（引线规则第 6 条，2026-10-25 用户定口）。
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
 * 批注墨迹落在 [rowIndex] 这一行上的**最右缘**：同一行取位顺序的键。
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
 * 箭头避让的一块障碍：矩形 + 它自己的容差（逐字墨迹小，图形 / 旁注框大）。
 *
 * [box] 标记「别人的框」——图形、表格、别的批注框。引线沿这种框的边抹过去不好看
 * （[ARROW_SOLID_CLEAR_EM]），量路径时要单独记一笔（见 [ArrowPath.grip]）；
 * [band] 标记**读者标注划线**的落墨带：箭头本来就停在它旁边，所以不算「贴别人的框」，
 * 但同样不该沿着它抹过去（并进 [ArrowPath.hug]）。
 */
private data class ArrowObstacle(
    val rect: Rect,
    val tol: Float,
    val text: Boolean = false,
    val box: Boolean = false,
    val band: Boolean = false,
)

/** 一条候选路径的度量：[clear] 是能否直接采用，[hits] 是全撞时比「穿得少」用的穿透长度。 */
private data class ArrowPath(
    val points: List<Offset>,
    val bends: Int,
    val length: Float,
    val crosses: Int,
    val hits: Float,
    /** 平行段离字墨迹太近的累计量（越小越好）：见 [measureArrowRoute]。 */
    val hug: Float,
    /**
     * 平行段贴着别人的框（图形 / 表格 / 别的批注框）走的累计量（越小越好）。
     *
     * 比 [hug] 靠前、比长度更靠前：少走几十像素不值得贴着人家的框边抹过去
     * （2026-10-30 用户：「可以距离其他批注框稍远一点」）。
     */
    val grip: Float = 0f,
    val entersBubble: Boolean,
    val order: Int,
    /** 横穿**自己锚点那一行的墨迹**（不是停在边上）：这种走法看着就像绕到字外面再扎回去。 */
    val entersInk: Boolean = false,
    /** 形状名次（[ARROW_FORM_STRAIGHT] …）：规则第 1 / 2 / 3 条先定线型。 */
    val form: Int = ARROW_FORM_OTHER,
    /** 末段长度：短于「画得下箭头」的门槛时按第 11 条省掉末折（见 `finish`）。 */
    val tailLen: Float = Float.MAX_VALUE,
    /** 端点为了绕开障碍**突破了 0.5em 内缩**（规则第 4 条）：只在前面的项全打平时才让位。 */
    val breakout: Boolean = false,
    /** 这个突破点直接怼在边的两头（见 [ArrowEdgePoint.corner]）：不进择优顺序，只做兜底判据。 */
    val corner: Boolean = false,
    /**
     * 画出来的折数（= 顶点数 - 2）：末段短到画不下箭头时按第 11 条省掉，那一折**不算**。
     *
     * 择优比的是**读者看到的形状**，不是模板里的点数。真机「讨论」（2026-10-30）：出口贴到
     * 邻框右边（1545）再回来的那条，模板是「竖 → 横 → 竖」，末折只有 6px 会被删掉，
     * 画出来和「竖 → 横」一模一样、却短了一半；按模板点数比，它多一折，只能让位给绕到框右端
     * （1756）的那条，引线白白多走两百多像素。
     */
    val rawBends: Int = bends,
) {
    val clear: Boolean get() = hits <= 1e-4f && !entersBubble && !entersInk

    /**
     * 通畅路径的择优顺序（引线规则第 7 / 1 / 3 / 2 / 6 / 8 条）：
     * **交叉数（0 条优先）→ 形状名次 → 折数少 → 穿字少 → 更短 → 贴字 → 是否突破 0.5em → 候选顺序**。
     *
     * 交叉仍然排第一（2026-10-25 用户定口）：两个批注的引线不得交叉，宁可多折一次
     * 也要绕开已经画好的那条。形状名次排在折数之前，是因为「正下一条竖线」「四角 L 形」
     * 「左右被字挡住走 下横上」这几种线型是规则直接点名的，不能因为多一折就丢掉。
     *
     * 2026-10-28 用户定口：**避开文字压过更短**（第 8 条在前），所以穿字量排在长度之前；
     * 「不往反方向起步」与「末段够长」两条**没有规则依据**，整条删掉——前者会杀掉第 3 条
     * 要的「上横下」，后者第 11 条已经给了出口。
     */
    fun betterThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (crosses != other.crosses) return crosses < other.crosses
        if (form != other.form) return form < other.form
        if (bends != other.bends) return bends < other.bends
        if (abs(hits - other.hits) > 0.5f) return hits < other.hits
        // 贴着别人的框走排在这儿：宁可多走几十像素，也别贴着框边抹过去（2026-10-30 用户定口）。
        if (grip != other.grip) return grip < other.grip
        if (abs(length - other.length) > 0.5f) return length < other.length
        if (abs(hug - other.hug) > 0.5f) return hug < other.hug
        if (breakout != other.breakout) return !breakout
        return order < other.order
    }

    /**
     * 全撞时的兜底顺序：不穿自己的框 → 不横穿自己那一行 → **交叉最少** → 形状 → 穿得少 →
     * 更短 → 候选顺序。照样画，不留空（规则第 8 条：实在没路时宁愿穿越，但要穿得少）。
     */
    fun betterFallbackThan(other: ArrowPath?): Boolean {
        if (other == null) return true
        if (entersBubble != other.entersBubble) return !entersBubble
        if (entersInk != other.entersInk) return !entersInk
        if (crosses != other.crosses) return crosses < other.crosses
        if (form != other.form) return form < other.form
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
    /** 这条批注**目标那一行**自己的墨迹：箭头只能停在它边上，不许横穿过去再扎回来。 */
    val own: Rect = glyph,
    /**
     * 划线标注的禁触带（这一行的墨迹 + 这条线）：箭头可以进 [box]，但停在它外侧。
     * null = 高亮标注，停在 [box] 的边框上。
     */
    val noTouch: Rect? = null,
) {
    /**
     * 折点坐标的来源（就是 [obstacles] 那批矩形）：**一条目标只建一次**。
     * 真机上一条批注要问十来组「出口 × 入口」，每组都重新 map 一遍就是白扔几十个列表。
     */
    val obstacleRects: List<Rect> = obstacles.map { it.rect }

    /**
     * 横段（折点的 y）能取的障碍边：**只算图形 / 表格 / 旁注框，不算文字墨迹、不算划线**。
     *
     * 第 10 条要的是「在行间穿行时布置在两行中间」；文字行的上 / 下缘外 1px 那条线
     * 既不是行间中间，也是贴着这一行抹过去（真机上就是「从字顶上 1px 蹭过去」）。
     * 划线带同理：它的边外 1px 就是贴着波浪线抹，走廊只能取行间中线
     * （[gapYs]，按划线下缘量的那条）与框外那条空白，所以两者都只当障碍量、不当折点来源；
     * 图形 / 表格绕不开，它们的边仍然是合法折点。
     */
    val bendRects: List<Rect> = obstacles.filter { !it.text && !it.band }.map { it.rect }

    /** 障碍的空间索引：量线段时只扫「纵向可能碰到」的那几行（见 [ArrowObstacleIndex]）。 */
    val index: ArrowObstacleIndex = ArrowObstacleIndex(obstacles)

    /**
     * 本目标已经量过的直线：每 4 个 float 一条（两端点坐标），见 [markStraight]。
     *
     * 直线只由「两条可动区间的交集坐标 + 两端各自固定的那条边」定下来，跟沿边挑了哪个候选点
     * 无关：同一条线量一次就够。旧实现只在**相邻两组**之间去重（一个 lastKey），
     * 正下批注一页会把同一条竖线量上千遍。
     */
    private val straightLines: FloatArray = FloatArray(ARROW_STRAIGHT_LINES * 4)
    private var straightCount: Int = 0

    /**
     * 记下这条直线，返回 true = 之前没量过。
     *
     * 比的是**坐标本身**而不是「出口边 × 入口边」：同一组边也可能量出不同的线（入口区间
     * 来源不同、突破点把坐标钉死），按边去重会把没量过的线也跳掉。存满时按「没量过」处理，
     * 宁可多量一条也不漏量。
     */
    fun markStraight(x0: Float, y0: Float, x1: Float, y1: Float): Boolean {
        for (i in 0 until straightCount) {
            val j = i * 4
            if (abs(straightLines[j] - x0) <= 0.01f &&
                abs(straightLines[j + 1] - y0) <= 0.01f &&
                abs(straightLines[j + 2] - x1) <= 0.01f &&
                abs(straightLines[j + 3] - y1) <= 0.01f
            ) {
                return false
            }
        }
        if (straightCount < ARROW_STRAIGHT_LINES) {
            val j = straightCount * 4
            straightLines[j] = x0
            straightLines[j + 1] = y0
            straightLines[j + 2] = x1
            straightLines[j + 3] = y1
            straightCount++
        }
        return true
    }
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
private class DrawnSegmentIndex(
    segments: List<Pair<Offset, Offset>>,
    /** 同向平行段的最小间距：小于它且投影重叠，按一次交叉记（见 [ARROW_LEADER_CLEAR_LINES]）。 */
    private val clear: Float = 0f,
) {
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
            // 同向横段：真重叠算交叉，平行且贴得太近也算一次（见 [clear]）。
            i = firstAtLeast(hAt, ay - clear - 0.01f)
            while (i < hSegs.size && hAt[i] <= ay + clear + 0.01f) {
                val s = hSegs[i]
                if (abs(hAt[i] - ay) <= 0.01f) {
                    if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
                } else if (min(hi, max(s.first.x, s.second.x)) - max(lo, min(s.first.x, s.second.x)) >= 1f) {
                    n++
                }
                i++
            }
        } else {
            var i = firstAtLeast(hAt, lo)
            while (i < hSegs.size && hAt[i] <= hi) {
                val s = hSegs[i]
                if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
                i++
            }
            i = firstAtLeast(vAt, ax - clear - 0.01f)
            while (i < vSegs.size && vAt[i] <= ax + clear + 0.01f) {
                val s = vSegs[i]
                if (abs(vAt[i] - ax) <= 0.01f) {
                    if (segmentsIntersect(ax, ay, bx, by, s.first.x, s.first.y, s.second.x, s.second.y)) n++
                } else if (min(hi, max(s.first.y, s.second.y)) - max(lo, min(s.first.y, s.second.y)) >= 1f) {
                    n++
                }
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
 * 正右 / 正左是水平直线（出口沿框的竖边、入口沿字的竖边各滑到一个共同坐标，
 * 两段可用区间取交集）；正下 / 正上从朝文字那条边竖着出去，横向落在「框与选区重叠段」里；
 * 右下 / 右上优先从**左边**横着出去、再竖回文字，也可以直接从框的上 / 下边竖着出去。
 *
 * 左右两侧被字挡住时走规则第 3 条的「下横上 / 上横下」：竖着从框的上 / 下边出去，
 * 沿两行之间的空隙横穿，再竖着进目标字。行间横穿尽量走路中间（规则第 10 条）。
 *
 * 直线被字挡住时**只用正交折线**（一折 → 两折 → 三折，最多 4 折 / 5 段）。
 * **必画**：所有形态全撞障碍时，取「穿得最少、再最短」的一条照样画出来——宁可线贴一点字边，
 * 也不能让读者不知道这条旁注指向哪里。只有框退成小图标、或框已经贴住墨迹才没有箭头。
 *
 * 择优顺序（规则第 7 / 1 / 2 / 3 / 6 / 8 条）：**不交叉 → 形状（直线 / L / 下横上优先）→
 * 折数 → 穿字 → 长度 → 贴字 → 是否突破 0.5em**。
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
 *   （引线规则第 9 条）。缺省时退回逐字墨迹的边（单测与无行盒信息的调用）。
 * @param targetInk 这条批注**要指的那一行**的逐字墨迹（2026-10-25 用户定口：位置定下来后，
 *   比一比框离选区开头近还是离末尾近，近哪头指哪头）。右侧摆法取它最右那个字、左侧摆法取
 *   最左那个字；缺省空 = 退回旧口径「与框纵向最近的那一行」。
 * @param rowBoxEntry 划线标注：箭头可以进目标行的行盒（2026-10-28 用户定口），只须避开墨迹与划线。
 *   高亮标注为 false，仍停在行盒边框上。
 * @param underlineBands 这条标注自己的划线在本页的落墨矩形：进得行盒时，箭头停在它们与墨迹
 *   并集的外侧 [ARROW_NO_TOUCH_GAP_PX]，不得触及。
 * @param pageLines 本页**所有**读者标注划线的落墨带（含本条自己的）：引线不但不得压在上面
 *   （规则第 8 条），横段也不能贴着它们抹过去，行间中线还得按它们的下缘量
 *   （2026-10-31 真机「函数体」：波浪线比字墨迹低一截，按墨迹量的中线正好落在波峰上）。
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
    rowBoxEntry: Boolean = false,
    underlineBands: List<Rect> = emptyList(),
    /**
     * 斜向（四个角）横向基准的**选区矩形**（一行一个框，来自 `NoteInk.rects`）。
     *
     * 布点把框摆在「选区右 / 左缘 ± 一个汉字」，引线量 0.5em 收角时也必须用同一把尺子：
     * 拿逐字墨迹量，斜体末尾那截后侧留白会把走廊吃掉（真机「使用 var」走廊只剩个位数像素，
     * L 形折线折不出来，退化成贴着框的一条 2px 直线）。空 = 退回逐字墨迹的并集。
     */
    cornerInk: List<Rect> = emptyList(),
    /** 本页所有读者标注划线的落墨带（含本条自己的，见 [underlineBands]），空 = 只有自己的要避。 */
    pageLines: List<Rect> = emptyList(),
): NoteArrow? {
    if (inkRects.isEmpty() || bubble.width <= 0f || bubble.height <= 0f) return null
    val anchor = rectUnion(inkRects)
    // 斜向横向基准用的「选区」：与布点同源（选区矩形），不是字墨迹。
    val cornerAnchor = if (cornerInk.isEmpty()) anchor else rectUnion(cornerInk)
    // 框已经贴住原文：连一根线的位置都没有，画出来只会压在字上。
    // 量的是**要指的那一行**（[targetInk]）：多行选区首行比末行长时，贴在末行右侧的框
    // 会落进整段包围盒里，但它离末行还有一段空档，引线照样画得出（2026-10-25）。
    val hugInk = if (targetInk.isEmpty()) anchor else rectUnion(targetInk)
    if (rectDistance(bubble, hugInk) <= NOTE_ARROW_MIN_GAP_PX) return null
    val relation = noteArrowRelation(bubble, anchor)
    // 沿边取点至少内缩 0.5em（引线规则第 4 条：端点不许落在四角）。旁注框的 em 取它的行高。
    val bubbleEm = if (lineH > 0f) lineH else bubble.height
    // 竖着进的时候箭头停在行盒边框上（规则第 9 条：不得侵入行盒内部）。
    // 2026-10-28 真机 A/B：按「选区墨迹」量（箭头贴到字边）会让引线的终点正好压在字的边上，
    // 被 0.5px 容差判成穿字，整条路变成「不通畅」，四角的走廊 L 反而退化成一条直线。
    // 所以竖进端点退回**整行行框**；「行盒」= 选区墨迹的口径只管第 4 / 5 条的量尺。
    fun boxOf(glyph: Rect): Rect = lineBoxes.firstOrNull {
        it.top <= glyph.center.y && it.bottom >= glyph.center.y &&
            it.left <= glyph.center.x && it.right >= glyph.center.x
    } ?: glyph

    /**
     * 划线标注在这一行上的那条线。
     *
     * 不按「线心是否落在行盒里」认：行高很紧时划线会画到行盒下边之外，那样反而把线漏掉、
     * 箭头压到线上。改按「与目标字纵向同一行」认，门槛取行盒高（多行选区里相邻一行至少
     * 隔一个行高，自己那条线只差半个字高）。
     */
    fun underlineOf(glyph: Rect, box: Rect): Rect? {
        if (!rowBoxEntry || underlineBands.isEmpty()) return null
        val rowTol = max(glyph.height, box.height) * 0.7f
        val onRow = underlineBands.filter { abs(it.center.y - glyph.center.y) <= rowTol }
        return if (onRow.isEmpty()) null else rectUnion(onRow)
    }

    // 自己的锚点字不是障碍：箭头本来就要指到它；同一条批注的其他字也不挡自己。
    val obstacles = buildList {
        for (g in glyphs) {
            if (inkRects.any { it.overlaps(g) }) continue
            add(ArrowObstacle(g, ARROW_GLYPH_TOL_PX, text = true))
        }
        for (s in solids) add(ArrowObstacle(s, ARROW_SOLID_TOL_PX, box = true))
        for (b in otherBubbles) add(ArrowObstacle(b, ARROW_SOLID_TOL_PX, box = true))
        // 划线本身也是硬障碍：箭头可以进它所在的行盒，但横着从线上压过去照样算「触及」。
        // 高亮标注不看这份几何（调用方本就不会传，这里再挡一道，免得旧数据影响旧行为）。
        if (rowBoxEntry) {
            for (band in underlineBands) add(ArrowObstacle(band, ARROW_SOLID_TOL_PX, band = true))
        }
        // 本页**所有**划线（别人的波浪线也在内）都不得压：一条引线从别人的波浪线上抹过去，
        // 读者分不清哪条是线、哪条是引线（2026-10-31 真机「函数体」）。
        // 自己那条已经算过（[underlineBands] 与 [pageLines] 同源），跳过免得算两遍。
        for (line in pageLines) {
            if (underlineBands.any { it == line }) continue
            add(ArrowObstacle(line, ARROW_SOLID_TOL_PX, band = true))
        }
    }
    val drawnSegments = drawnArrows.flatMap { arrowSegments(it) }
    // 本页还一条引线都没画时不可能交叉：这时候「通畅就停」的老早停仍然成立，
    // 免得为了一个不可能出现的交叉数把折数一路试到上限。
    val anyDrawn = drawnSegments.isNotEmpty()
    // 已画引线按横 / 竖分好桶：量候选段时只比「坐标落在这一段范围内」的那几条，
    // 不再对每条候选段逐条算包围盒（真机上一页九条批注要乘上四十来段已画引线）。
    val drawnIndex = DrawnSegmentIndex(drawnSegments, lineH * ARROW_LEADER_CLEAR_LINES)
    val window = max(lineH, 8f) * ARROW_BEND_WINDOW_LINES
    // 引线贴字门槛：旁注行高的 0.3 倍（lineH 缺省时放宽到 0：单测不量贴字）。
    val hugDistance = if (lineH > 0f) lineH * ARROW_HUG_LINES else 0f
    // 「贴着别人的框走」的门槛：与出口取点的余量同一把尺子（见 [ARROW_SOLID_CLEAR_EM]）。
    val gripDistance = max(bubbleEm * ARROW_SOLID_CLEAR_EM, ARROW_EDGE_BREAK_PX * 2f)
    // 末折短到画不下箭头时按第 11 条省掉（四个角也一样，2026-10-31 用户定口：末折不得省略
    // 那条硬规定撤销——竖段只剩一两像素时箭头被按比例缩到零点几像素，等于没画）；
    // 择优时也要按「画出来」的段数算（见 [ArrowPath.rawBends]）。
    val cornerFamily = side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT ||
        side == NoteSide.BELOW_LEFT || side == NoteSide.ABOVE_LEFT
    val shortTail = max(lineH * NOTE_ARROW_UNDRAWABLE_LINES, 10f)
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
            // 行间中线按「离框与目标字的纵向中点由近到远」排：折点候选只取最前面的几条，
            // 取错那几条就会从别的行缝横穿（规则第 10 条要的是这一段真正跨过的那条缝）。
            val midY = (bubble.center.y + glyph.center.y) * 0.5f
            // 框和正文在同一行时（正右 / 正左摆法），「上横下」的横线只能走框外那条空白：
            // 相邻两行墨迹的中线不一定有（段首上方是段间距，`arrowGapMidYs` 按一个字高截掉了），
            // 于是折点池里根本没有框外的 y，V-H-V 生成不出来，只能退成贴框线的之字形
            // （真机「使用 var」，2026-10-26）。框的上 / 下沿各让出一个折点，横线才算有地方放。
            // 让出的量取三分之一行高：比箭头本身（≈0.25 行高）长一截，末折才立得住、画得下。
            val liftPad = max(ARROW_BEND_CLEARANCE_PX * 2f, lineH * 0.35f)
            val liftYs = listOf(bubble.top - liftPad, bubble.bottom + liftPad)
            // 目标那一行的墨迹（箭头只能停在它边上）：多行选区时不能拿整段包围盒当它，
            // 否则「穿过行间空白」也会被当成压字。
            val rowInk = inkRects.filter {
                abs(it.center.y - glyph.center.y) <= max(it.height, glyph.height) * 0.6f
            }
            val box = boxOf(glyph)
            // 划线标注：箭头进得行盒，但停在「这一行墨迹 + 这条线」的外侧（规则第 9 条）。
            val noTouch = if (rowBoxEntry) {
                rectUnion(rowInk + listOfNotNull(underlineOf(glyph, box)))
            } else {
                null
            }
            ArrowTarget(
                glyph = glyph,
                obstacles = nearObstacles,
                gapYs = liftYs + arrowGapMidYs(nearObstacles).sortedBy { abs(it - midY) },
                box = box,
                // 指到哪个字，就从那个字的边上进：它自己不算「穿字」。整行字高不齐时
                // （'r' 比汉字矮）旧口径会把「落在字顶边」当成扎进行内，干净的竖进全被毙掉。
                own = rectUnion(rowInk.filter { it != glyph }.ifEmpty { listOf(glyph) }),
                noTouch = noTouch,
            )
        }

    var order = 0
    var fallback: ArrowPath? = null
    var best: ArrowPath? = null
    // [best] 是从哪个目标字量出来的：收弧线时要拿它的障碍表验「这条弧鼓出来压不压东西」。
    var bestTarget: ArrowTarget? = null

    // 热路径的暂存区：候选折线先写进这两对数组，量完就丢。
    // 真机上「一条候选 0.12ms」的大头就是每条候选一组短命对象（点列表 / 装箱的坐标），
    // 一页几千条候选光 GC 就够呛；改成数组后逐条候选不再分配任何对象。
    val rawX = FloatArray(ARROW_ROUTE_MAX_POINTS)
    val rawY = FloatArray(ARROW_ROUTE_MAX_POINTS)
    val ptsX = FloatArray(ARROW_ROUTE_MAX_POINTS)
    val ptsY = FloatArray(ARROW_ROUTE_MAX_POINTS)
    // 折点坐标池：一条批注只建一次（旧实现每生成一次就新开两个数组，见 [ArrowElbowScratch]）。
    val elbowScratch = ArrowElbowScratch(obstacles.size)

    fun consider(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        target: ArrowTarget,
        breakout: Boolean,
        corner: Boolean = false,
    ) {
        val seq = order++
        // 兜底顺序里「进了自己的框」「横穿自己那一行」排在最前面，而这两项只看两个矩形
        // （见 [arrowEnterFlags]）。比现有兜底还差的候选：既赢不了兜底，也不可能是通畅路径
        // ——通畅要求这两项都干净——直接跳过，省掉整条候选的字墨迹扫描。
        val fb = fallback
        if (fb != null) {
            val enters = arrowEnterFlags(xs, ys, count, bubble, target.own)
            val bubbleIn = (enters and 1) != 0
            val inkIn = (enters and 2) != 0
            // 逐项比：进了框比没进差；同样「进不进框」时，横穿自己那一行比没横穿差。
            if (if (bubbleIn != fb.entersBubble) bubbleIn else (inkIn && !fb.entersInk)) return
        }
        // 先廉价判一句「这条候选就算量完也赢不了」：形状名次、折数、两端直线距离都由顶点直接
        // 算出，不必碰障碍。已经有一条不交叉的通畅引线时，比它更远的「同形状同折数 / 更差形状」
        // 候选在长度这一关必输（判据里交叉、形状、折数、穿字量都打平或更差才会轮到长度）。
        // 真机上一条批注要量一千多条候选，绝大多数是这么白量的——每条都要扫几十个字墨迹。
        val mark = best
        if (mark != null && mark.clear && mark.crosses == 0 &&
            !canStillWin(xs, ys, count, mark, side, shortTail)
        ) {
            return
        }
        val path = measureArrowRoute(
            xs, ys, count, seq, target.index, bubble, drawnIndex, hugDistance,
            gripDistance = gripDistance,
            limitClear = best, limitFallback = fallback, side = side, own = target.own,
            breakout = breakout, corner = corner, shortTail = shortTail,
        )
        if (path == null) return
        if (path.clear) {
            // 「怼在边的两头」的突破点（见 [ArrowEdgePoint.corner]）只是**最后手段**：
            // 已经有一条不怼角的通畅引线时，哪怕它更长也不换——端点贴着行的角，比多绕几十像素
            // 难看（2026-10-28 用户定口）。这条要压过「更短」，所以不能只做择优顺序里的一项。
            // 而且必须逐条判、不能整遍否决（2026-10-30 真机「讨论」）：第二遍里怼着划线右端
            // 的那条确实最短，整遍否决会连带把「不怼角、起点已经贴到邻框边」的那条一起扔掉。
            val cur = best
            if (path.corner && cur != null && !cur.corner) return
            if (path.betterThan(cur)) {
                best = path
                bestTarget = target
            }
        } else {
            if (path.betterFallbackThan(fallback)) fallback = path
        }
    }

    /** 一组「出口 × 入口」：直线能走就直线，走不通再按折数由少到多试正交折线。 */
    fun tryRoute(exit: ArrowExit, entry: ArrowEntry, target: ArrowTarget) {
        // 这一组端点开始时的候选序号：判断「best 是不是这一组刚找到的」，
        // 免得把上一组端点找到的折线当成「本组已达标」而过早收手。
        val orderBefore = order
        val breakout = exit.breakout || entry.breakout
        // 「怼在边的两头」的突破：0.5em 可以突破到缝上，但不该突破到角上（见 [ArrowEdgePoint.corner]）。
        val corner = (exit.breakout && exit.corner) || (entry.breakout && entry.corner)
        // 突破点把自己的坐标钉死（[bandLow]），这一遍不做「同一条线只量一次」的去重。
        val dedupeStraight = !breakout
        // 0) 两端同向 ⇒ 一条直线。两端的落点都在边上的可动区间里，
        //    取两段区间的交集：交点就是「两端各自滑到同一个坐标」的那条直线。
        //    （旧口径要求两端坐标严丝合缝地相等，框边按 0.5em 内缩后差一两像素就永远凑不齐，
        //    本来一条直线能解决的「正右批注」被迫走成两折 Z 形。）
        if (exit.horizontal == entry.horizontal) {
            val lo = max(exit.lo, entry.lo)
            val hi = min(exit.hi, entry.hi)
            val shared = when {
                lo <= hi + 0.01f -> clampInset(
                    min(lo, hi),
                    max(lo, hi),
                    if (exit.horizontal) bubble.center.y else bubble.center.x,
                )
                // 两段「离角 0.5em」的可用区间对不上（一行高的框只剩中点，字墨迹中心又差几像素）：
                // 差得不多就按规则第 4 条后半句**取消内缩**，取两段之间正中的坐标。
                // 放宽也有底线：坐标得同时落在两条边的**整段**范围内（[ArrowExit.edgeLo] /
                // [ArrowEntry.edgeLo]），否则起点就滑出框边、漂到框外去了。
                // 不这么做，正右批注明明没阻挡也会被折成三段（2026-10-25 用户第 7 条）。
                lo - hi <= max(bubbleEm * 0.5f, 2f) -> {
                    val edgeLo = max(exit.edgeLo, entry.edgeLo)
                    val edgeHi = min(exit.edgeHi, entry.edgeHi)
                    val at = (lo + hi) * 0.5f
                    if (edgeLo <= edgeHi && at >= edgeLo - 0.01f && at <= edgeHi + 0.01f) at else null
                }
                else -> null
            }
            if (shared != null) {
                if (exit.horizontal) {
                    // 横线：y 取两段 y 区间的共同坐标，x 各走各的边界（框边 → 文字边）。
                    if (abs(exit.point.x - entry.point.x) > 0.01f) {
                        rawX[0] = exit.point.x
                        rawY[0] = shared
                        rawX[1] = entry.point.x
                        rawY[1] = shared
                        if (endpointsOutward(rawX, rawY, 2, exit.edge, entry.edge) &&
                            (!dedupeStraight ||
                                target.markStraight(rawX[0], rawY[0], rawX[1], rawY[1]))
                        ) {
                            consider(rawX, rawY, 2, target, breakout, corner)
                        }
                    }
                } else {
                    // 竖线：x 取两段 x 区间的共同坐标，y 各走各的边界（框边 → 行盒边）。
                    if (abs(exit.point.y - entry.point.y) > 0.01f) {
                        rawX[0] = shared
                        rawY[0] = exit.point.y
                        rawX[1] = shared
                        rawY[1] = entry.point.y
                        if (endpointsOutward(rawX, rawY, 2, exit.edge, entry.edge) &&
                            (!dedupeStraight ||
                                target.markStraight(rawX[0], rawY[0], rawX[1], rawY[1]))
                        ) {
                            consider(rawX, rawY, 2, target, breakout, corner)
                        }
                    }
                }
            }
        }
        // 1) 折线：只用横 / 竖段，且第一段 ⊥ 框边、最后一段 ⊥ 文字框边。
        // 折数由少到多，一旦这个折数有通畅路径就不再试更多折（少折永远优先）。
        //
        // 已经有一条「不交叉、形状已是首选」的通畅引线时，**比它还远的端点组合只剩直线可能**：
        // 折线长度 ≥ 两端直线距离 > 现有引线长度，交叉数 = 0 打平、形状名次不占优、
        // 穿字量都是 0，这几条判据全打平或更差，长度这一关必输。直线有 form=0 的可能，
        // 上面已经量过，这里跳过的只是白量的折线。
        // （正下批注一页九条要问一千六百组端点、量一千四百多条候选，剪掉的正是这一批。）
        //
        // 折数这一关要留神：判据里折数排在长度**前面**，所以只有「折数比现有引线还多」
        // 的档才一定输；折数更少的那几档哪怕更长，也可能靠少折翻盘，照样要生成。
        val limit = best
        val skipElbows = limit != null && limit.clear && limit.crosses == 0 &&
            limit.form <= ARROW_FORM_FIRST &&
            (exit.point.x - entry.point.x) * (exit.point.x - entry.point.x) +
            (exit.point.y - entry.point.y) * (exit.point.y - entry.point.y) >
            (limit.length + 0.5f) * (limit.length + 0.5f)
        // 折数这一关按**模板**折数比：末折删不删要到收尾才知道，早停不能因此少试一档。
        val skipAboveBends = if (limit != null && skipElbows) limit.rawBends else ARROW_MAX_BENDS
        val exitSign = arrowOutwardSign(exit.edge)
        val entrySign = arrowOutwardSign(entry.edge)
        // 首 / 末段方向：出口边在左右 ⇒ 横着出，在上下 ⇒ 竖着出；未知时不预筛。
        val firstNeed = when (exit.edge) {
            ARROW_EDGE_LEFT, ARROW_EDGE_RIGHT -> ARROW_NEED_HORIZONTAL
            ARROW_EDGE_TOP, ARROW_EDGE_BOTTOM -> ARROW_NEED_VERTICAL
            else -> ARROW_NEED_ANY
        }
        val lastNeed = when (entry.edge) {
            ARROW_EDGE_LEFT, ARROW_EDGE_RIGHT -> ARROW_NEED_HORIZONTAL
            ARROW_EDGE_TOP, ARROW_EDGE_BOTTOM -> ARROW_NEED_VERTICAL
            else -> ARROW_NEED_ANY
        }
        for (bends in 1..ARROW_MAX_BENDS) {
            if (bends > skipAboveBends) break
            emitElbowRoutes(
                start = exit.point,
                end = entry.point,
                obstacleRects = target.obstacleRects,
                bends = bends,
                extraYs = target.gapYs,
                bendRects = target.bendRects,
                px = rawX,
                py = rawY,
                firstNeed = firstNeed,
                lastNeed = lastNeed,
                exitSign = exitSign,
                entrySign = entrySign,
                scratch = elbowScratch,
                emit = { xs, ys, n ->
                    // 先验形状再量：不合规矩的候选连障碍都不用看。
                    val m = simplifyInto(xs, ys, n, ptsX, ptsY)
                    if (m >= 2 &&
                        orthogonalShaped(ptsX, ptsY, m, exit.horizontal, entry.horizontal) &&
                        endpointsOutward(ptsX, ptsY, m, exit.edge, entry.edge)
                    ) {
                        consider(ptsX, ptsY, m, target, breakout, corner)
                    }
                },
            )
            // 只有「通畅且一条已画引线都不交叉」才值得停：判据里交叉数排第一，
            // 早停在这里会把「多折一次但绕开别人引线」的路子直接掐掉。
            // 而且要**本组端点**自己找到了不差于当前折数的路：早停看的是全局最优的话，
            // 上一组端点找到的一折折线会让这一组连两折都不试，竖着进 / 竖着出的
            // 「下横上」就永远轮不到（2026-10-25 用户第 8 条）。
            val b = best
            if (b != null && b.order >= orderBefore && b.clear && b.rawBends <= bends &&
                (b.crosses == 0 || !anyDrawn)
            ) {
                break
            }
        }
    }

    /**
     * 四角走廊（规则第 2 条：**四角一律 L 形两段折线**）：从**框的侧边**横着出去，
     * 沿「错开的那一个字」让出来的空隙走到选区那一角的外侧，再竖着进字下 / 字上，
     * 箭头落在竖段末端。斜向的框本来就挨着那一行（近侧只留 2px），竖着直出只剩几像素；
     * 错开一个字就是为了腾出这条走廊。
     *
     * 落点按「近哪头 → 另一头 → 收角带里扫字缝」三档找：近端常常落在**框底下**（长批注的框
     * 伸到正文下面，近侧那一头就被自己盖住），这时先看**框边外侧半个字**能不能直接拐下去
     * （整条选区都是文字，不必横穿到选区角上，2026-11-01 用户定口），再改指同一选区的另一头；
     * 两头都画不成干净的路（那一竖扎进下一行的字里）才退到收角带里扫几个点。只有整条带都被框
     * 盖住、一个字也扫不出干净 L 时才返回 null，让调用方退回通畅路径（2026-10-28 真机：
     * 四角「很多个不是 L」）。
     */
    fun cornerRoute(): ArrowPath? {
        var nearBest: ArrowPath? = null
        // 横段是「错开的那一个字」那段空隙：短到看不出来就不改道。
        val minCorridor = max(4f, bubbleEm * 0.2f)

        for (t in targets) {
            val below = bubble.top >= t.glyph.bottom - NOTE_GEOM_EPS
            val above = bubble.bottom <= t.glyph.top + NOTE_GEOM_EPS
            if (!below && !above) continue
            val yEntry = verticalEntryEdge(t.box, above = !below, noTouch = t.noTouch)
            // 走廊离选区角 0.5em = 半个字。**取整行的高**（[cornerRowEm]）：一行里「l」和「使」
            // 高矮差一截，取目标字自己的高会让同样缩进的批注画出不同长度的横段（引线规则第 4 条）。
            // 区间与端点算法和 [cornerEntryX] 完全一致，两条路径画出的落点才会是同一个。
            val em = cornerRowEm(anchor, t.glyph)
            val insetLo = cornerAnchor.left + em * 0.5f
            val insetHi = cornerAnchor.right - em * 0.5f
            // 先指**离框更近的那一头**（§6.1）：目标字在选区右半就取末端，否则取开头。
            val nearEnd = t.glyph.center.x > cornerAnchor.center.x
            val near = clampInset(insetLo, insetHi, if (nearEnd) insetHi else insetLo)
            val far = clampInset(insetLo, insetHi, if (nearEnd) insetLo else insetHi)
            val gap = if (below) bubble.top - yEntry else yEntry - bubble.bottom

            // 竖段要够长：出口取**离正文最远**的位置（框底 / 框顶往里半个 em），
            // 再从那儿横出去、竖回来，竖段≈框高。这里是框内偏移，按**框**的 em 算
            // （[bubbleEm]），与选区的字高无关。
            val edgeInset = min(bubbleEm * 0.5f, bubble.height * 0.5f)
            val loY = bubble.top + edgeInset
            val hiY = bubble.bottom - edgeInset
            val ys = if (below) {
                floatArrayOf(hiY, bubble.center.y, loY)
            } else {
                floatArrayOf(loY, bubble.center.y, hiY)
            }

            /**
             * 这根入口 x 上的全部竖段候选：出口取上面那几条 y，横出去、竖回来。
             * 这条 x 被框盖住（横段得穿自己的框）或走廊太窄时返回 null——那是**这根 x** 的问题，
             * 换一头 / 换个字缝还能画。
             */
            fun attemptsAt(xEntry: Float): ArrowPath? {
                val edgeX = when {
                    xEntry <= bubble.left - NOTE_GEOM_EPS -> bubble.left
                    xEntry >= bubble.right + NOTE_GEOM_EPS -> bubble.right
                    else -> null
                } ?: return null
                if (abs(xEntry - edgeX) < minCorridor) return null
                var out: ArrowPath? = null
                for (y in ys) {
                    val y0 = clampInset(min(loY, hiY), max(loY, hiY), y)
                    rawX[0] = edgeX
                    rawY[0] = y0
                    rawX[1] = xEntry
                    rawY[1] = y0
                    rawX[2] = xEntry
                    rawY[2] = yEntry
                    val m = simplifyInto(rawX, rawY, 3, ptsX, ptsY)
                    if (m < 2 || !orthogonalShaped(ptsX, ptsY, m, true, false)) continue
                    val route = measureArrowRoute(
                        ptsX, ptsY, m, order++, t.index, bubble, drawnIndex, hugDistance,
                        side = side, own = t.own,
                    ) ?: continue
                    if (route.clear && route.betterThan(out)) out = route
                }
                return out
            }

            var bestOfTarget: ArrowPath? = null

            fun tryAt(x: Float) {
                val route = attemptsAt(x)
                if (route != null && route.betterThan(bestOfTarget)) bestOfTarget = route
            }

            tryAt(near)
            // 近端被框自己盖住时，别一下子跳到另一头：整条选区都是文字，横段只要走出半个字
            // 就能拐下去，落点仍在**这一行选区**的字上，引线短得多（2026-11-01 用户定口，
            // 真机「把握基本面」：横段横穿五个字，其实第一个字上方就有选区文字）。
            if (bestOfTarget == null) {
                val turnGap = max(bubbleEm * 0.5f, minCorridor)
                val rowLo = max(insetLo, t.own.left + minCorridor)
                val rowHi = min(insetHi, t.own.right - minCorridor)
                if (rowLo <= rowHi) {
                    for (x in floatArrayOf(bubble.left - turnGap, bubble.right + turnGap)) {
                        if (x < rowLo - 0.01f || x > rowHi + 0.01f) continue
                        // 和近端 / 另一头本来就在同一个位置时，交给那两个标准落点，
                        // 免得同样的缩进因为框宽不同多偏出几像素。
                        if (abs(x - near) < minCorridor || abs(x - far) < minCorridor) continue
                        tryAt(x)
                    }
                }
            }
            // 近端**画不成 L**（那一头落在框底下 / 走廊太窄）时，改指另一头——与缝宽无关：
            // 真机上常见的正是「长批注的框伸到正文底下，近端被自己盖住」，而框离正文并不近，
            // 旧判据（缝 < 8px 才换头）于是根本不试另一头，四角就没了 L（2026-10-28 真机）。
            // 缝很窄时两头都试，挑更好的那条；缝宽够、近端又走得通时不动：横穿整个选区指另一头只会更长。
            if (bestOfTarget == null || gap < 8f) tryAt(far)
            // 两头都画不出**干净**的 L 时（那一竖扎进下一行的字里），在收角带里再扫几个点：
            // 规则第 2 条是硬规定，宁可把落点挪到选区中段的字缝上，也不把四角退成一条直线。
            if (bestOfTarget == null) {
                for (i in 1..ARROW_CORNER_SWEEP) {
                    val f = i.toFloat() / (ARROW_CORNER_SWEEP + 1)
                    tryAt(insetLo + (insetHi - insetLo) * f)
                }
            }
            if (bestOfTarget != null && bestOfTarget.betterThan(nearBest)) nearBest = bestOfTarget
        }
        return nearBest
    }

    /**
     * 末折被省掉后，剩下的横段离框边远一点（2026-10-31 用户定口）。
     *
     * 横段是从「本来要往下扎的那一点」拐出来的，往往只差 1px 贴着框底走：从框底出线、
     * 立刻与框底平行地横过去，看上去像框底下多了一条影子（真机「函数体」）。改成
     * **先往下（上）走一小段再转**——从框底的左下角下来一点再横过去，两段都是清楚的。
     *
     * 出线口一并挪到横段要去的那个角上：它本来落在离角半个字的位置（规则第 4 条），横段就得
     * 先在框底下平行着抹这半个字，白留一截「影子」。挪到角上以后横段一出来就出了框的范围。
     *
     * 走多远：这段空白的一半，最多 0.4em；离**字墨迹** 2px 打住（这段横线不再竖着扎进行框，
     * 走在行框上方的字距留白里，见规则第 9 条口径）。这一段横线要是会碰到字 / 别的框 /
     * 图形 / 划线，就保持原样——宁可贴边，也不能为了好看压住别的墨。
     */
    fun legOffBubble(path: ArrowPath): ArrowPath {
        if (path.points.size < 2) return path
        val end = path.points[path.points.size - 1]
        val prev = path.points[path.points.size - 2]
        // 只处理「先竖后横」：横段已经和起点同高，说明本来就从框的侧边横出去，没问题。
        if (abs(end.y - prev.y) >= 0.01f) return path
        val exit = path.points.first()
        val down = end.y > exit.y
        if (abs(end.y - exit.y) < 0.01f) return path
        // 往这边还有多少空地（以选区墨迹为界，留 2px）。
        val floor = if (down) hugInk.top - ARROW_NO_TOUCH_GAP_PX else hugInk.bottom + ARROW_NO_TOUCH_GAP_PX
        val room = if (down) floor - exit.y else exit.y - floor
        val stub = min(room * 0.5f, bubbleEm * 0.4f)
        if (room < 8f || stub < 4f) return path
        // 已经离框边够远了就不要再挪。
        if (abs(end.y - exit.y) >= room * 0.5f) return path
        val y = if (down) exit.y + stub else exit.y - stub
        val out = path.points.toMutableList()
        for (i in 1 until out.size) {
            if (abs(out[i].y - end.y) < 0.01f) out[i] = Offset(out[i].x, y)
        }
        // 出线口本来落在框那条边**从角往里半个字**的位置（规则第 4 条），于是横段要先在框底下
        // 平行着抹这半个字才走得干净——正是用户说的「从下面出线，又是平行出线」。
        // 横段往哪头走，就把出线口挪到那一头的角上：从角上下（上）来一点再横过去。
        // 框角是 3dp 圆角，留在离角 [keep] 处，不真的落在弧上。
        val onEdge = if (down) abs(exit.y - bubble.bottom) <= 1f else abs(exit.y - bubble.top) <= 1f
        val leftward = end.x < exit.x
        if (onEdge) {
            val corner = if (leftward) bubble.left else bubble.right
            val keep = min(bubbleEm * 0.2f, bubble.height * 0.4f)
            val nx = corner + if (leftward) keep else -keep
            if (if (leftward) nx < exit.x - 0.5f else nx > exit.x + 0.5f) {
                out[0] = Offset(nx, exit.y)
                for (i in 1 until out.size) {
                    if (abs(out[i].x - exit.x) < 0.5f && abs(out[i].y - y) < 0.01f) {
                        out[i] = Offset(nx, out[i].y)
                    }
                }
            }
        }
        // 挪完以后这一段横线还得是干净的：碰字 / 别的框 / 图形 / 划线就整体保持原样
        // （宁可贴边，也不能为了好看压住别的墨）。
        val x0 = min(end.x, out[out.size - 2].x)
        val x1 = max(end.x, out[out.size - 2].x)
        fun blocked(r: Rect): Boolean =
            r.right > x0 && r.left < x1 && r.top <= y + 1f && r.bottom >= y - 1f
        if (glyphs.any(::blocked) || solids.any(::blocked) || otherBubbles.any(::blocked)) return path
        if (pageLines.any(::blocked) || underlineBands.any(::blocked)) return path
        return path.copy(points = out)
    }

    /**
     * 收尾：四角先按走廊构造 L 形（规则第 2 条），再按第 11 条检查末折画不画得下。
     *
     * **第 11 条对四个角同样适用**（2026-10-31 用户定口，撤销 2026-10-28 的「末折不得省略」）：
     * 竖段只剩一两像素时，箭头按 `末段长度 × 0.9` 缩到零点几像素，读者看到的就是一根
     * 没有箭头的横线（真机「函数体」：630px 横线，末折 1px，箭头 0.9px）。这种情形下
     * 末折照画没有任何意义，按第 11 条省掉、箭头落在倒数第二段末端（方向随那一段）——
     * 也就是用户要的「下面没空间就直接画一根左箭头到行盒边缘」。
     *
     * 顺序仍然是先定线型再按第 11 条裁：`cornerRoute()` 先按走廊挑出 L，再裁末折。
     *
     * 其余方向：末段竖线短到画不下箭头时省掉末折，箭头落在倒数第二段的末端
     * （2026-10-24 用户反馈：讨论的引线 17px 快看不见了；
     * 2026-10-25 补：末段一短，箭头两臂就退到上一段上，看着像「横线末端长了个竖箭头」）。
     */
    fun finish(path: ArrowPath, target: ArrowTarget?): NoteArrow {
        // 四角 L 不再无条件强制：框被推远（与选区之间隔了一行）且横向罩住选区时，
        // 一条竖直直线本来就更短、也照样指到字上（2026-11-01 用户定口，真机「函数」）。
        // 斜向近侧只留 2px 的那种竖段画不下箭头，仍旧走 L。
        val straightDown = path.points.size == 2 &&
            abs(path.points[0].x - path.points[1].x) < 0.5f &&
            abs(path.points[0].y - path.points[1].y) >= bubbleEm
        val chosen = if (path.clear && cornerFamily && !straightDown) {
            cornerRoute() ?: path
        } else {
            path
        }
        val trimmed = chosen.withoutUndrawableTail(lineH)
        // 末折被省掉时，剩下的横段先离框边远一点（不然就是框底下一条平行的影子），
        // 再只走到最近那条选区边框外（箭头叠在选区底色上就看不见了）。
        val clipped = if (trimmed.points.size < chosen.points.size) {
            legOffBubble(trimmed).stopOutsideInk(anchor, ARROW_NO_TOUCH_GAP_PX)
        } else {
            trimmed
        }
        // 没有布点方向的斜向引线：一折的 L 收成弧线（2026-10-30 用户定口）。
        // 四角 L（[cornerFamily]）与四个正向直线一律不动；收不住就照旧画 L。
        if (!cornerFamily && (side == null || side == NoteSide.OTHER) &&
            target != null && chosen.clear && clipped.points.size == 3
        ) {
            arrowArcFromElbow(chosen.points, bubbleEm, target.obstacles, drawnIndex)?.let { return it }
        }
        return clipped.toNoteArrow()
    }

    // 已经拿到**无条件最优**的引线（一条直线、且不交叉）就不必再试别的端点组合了。
    // 折线形状（哪怕只是一折的 L）不能停：后面的「入口」还可能凑出更短的一条直线。
    // 早停正是「正右批注明明没阻挡却被折成三段」「左右两侧永远走不成下横上」的直接原因
    // （2026-10-25 用户第 7 / 8 条）：入口是先横后竖地枚举的，横着进的那一组先找到能通的
    // 折线就返回，竖着进的那一组根本轮不到。
    fun optimal(): Boolean = best?.let {
        it.clear && it.bends == 0 && (it.crosses == 0 || !anyDrawn)
    } == true

    // 按布点方向摆放的批注：两端都不再固定在中心点，出口可以沿框边挪、入口可以沿字边挪，
    // 先找「一条直线走得通」的那一对（2026-10-24 用户定口：最短优先）。
    val belowFamily = side == NoteSide.BELOW || side == NoteSide.ABOVE
    val sideAware = side != null && side != NoteSide.OTHER && !belowFamily

    /** 按「允不允许突破 0.5em」把端点组合枚举一遍；拿到无条件最优的直线就当场收尾。 */
    fun enumerate(breakoutAllowed: Boolean): NoteArrow? {
        if (belowFamily) {
            // 正下 / 正上：竖着进出，横向优先落在「框与选区的重叠段」里（规则第 1 条）。
            val below = side == NoteSide.BELOW
            for (t in targets) {
                for (entry in arrowBelowEntries(
                    bubble, t.glyph, anchor, t.box, below,
                    t.obstacleRects, breakoutAllowed, t.noTouch,
                )) {
                    for (exit in arrowBelowExits(
                        bubble, below, entry, bubbleEm, t.obstacleRects, breakoutAllowed,
                        t.bendRects,
                    )) {
                        tryRoute(exit, entry, t)
                    }
                    if (optimal()) return finish(best!!, bestTarget)
                }
            }
        } else if (sideAware) {
            for (t in targets) {
                for (entry in arrowSideEntries(
                    side!!, bubble, t.glyph, anchor, t.box, cornerAnchor,
                    t.obstacleRects, breakoutAllowed, t.noTouch,
                )) {
                    for (exit in arrowSideExits(
                        entry, side, bubble, t.glyph, bubbleEm, t.obstacleRects, breakoutAllowed,
                        t.bendRects,
                    )) {
                        tryRoute(exit, entry, t)
                    }
                    if (optimal()) return finish(best!!, bestTarget)
                }
            }
        } else {
            // 退化路径（没有布点方向）：沿用「朝文字那条边的中点」。
            val exit = arrowExit(side, relation, bubble, anchor)
            for (t in targets) {
                val entryHorizontal = arrowEntryHorizontal(side, bubble, t.glyph)
                for (entry in arrowEntries(
                    bubble, t.glyph, t.box, exit, entryHorizontal,
                    t.obstacleRects, breakoutAllowed, t.noTouch,
                )) {
                    tryRoute(exit, entry, t)
                }
                if (optimal()) return finish(best!!, bestTarget)
            }
        }
        return null
    }

    // 规则第 4 条的顺序：**先只在 0.5em 区间里取点**；这一遍没拿到一条干净直线时，再允许
    // 突破到整条边（两端留护栏）枚举一遍。
    //
    // 这里的「没拿到」不能只算「一条通畅的路都没有」：0.5em 是**尽量**保持（第 4 条后半句
    // 「空间不足时允许突破」），而第 1 / 6 条要的是直线、段越少越好。只为凑那半个字就把
    // 一条直线掰成三段（真机「讨论」：竖线在另一条批注框边上错 7px，来回折了两下），
    // 明显是本末倒置。择优顺序里「突破」本来就排在折数、穿字、长度之后，所以放开这一遍
    // 只会把「为凑 0.5em 多折出来的 Z」换回直线，不会把已经够好的一条折线换成突破的。
    var done = enumerate(breakoutAllowed = false)
    if (done == null) {
        val before = best
        val beforeTarget = bestTarget
        if (before == null || !before.clear || before.bends > 0) {
            done = enumerate(breakoutAllowed = true)
            val after = best
            // 第二遍的结论只在真的更好时才认：
            // 1. 通畅（第 8 条：突破 0.5em 换一条穿字的直线，不算改善）；
            // 2. 确实比第一遍的好（形状 / 折数 / 长度逐项比，见 [ArrowPath.betterThan]）。
            // 「怼到边的两头」的突破点不在这里判：它在 [consider] 里就已经只能当最后手段，
            // 好让同一遍里不怼角的其它候选照样顶上来（整遍否决会连它们一起扔掉）。
            val worse = after != null && before != null && after !== before &&
                (!after.clear || !after.betterThan(before))
            if (worse) {
                best = before
                bestTarget = beforeTarget
                done = null
            }
        }
    }
    if (done != null) return done
    val chosen = best ?: fallback ?: return null
    if (best == null) {
        // 兜底：一条通畅的路都没找到，只能画穿字 / 穿引线的那条（规则第 8 条：
        // 实在找不到合适位置时宁愿穿越文字，但穿越的文字越少越好）。留给真机排查。
        Logger.d(
            "Orilumn.NOTE",
            "NOTE-ARROW fallback side=$side crosses=${chosen.crosses} " +
                "hits=${chosen.hits.toInt()} pts=${chosen.points.size}",
        )
    }
    return finish(chosen, bestTarget)
}

/**
 * 引线的目标字（§6.1，2026-10-24 用户定口 + 2026-10-25 / 2026-10-27 补充）：
 *
 * - 先按「近哪头指哪头」定**一头**：框中心落在这一行左半就指开头，右半就指末尾。
 *   这一条对**单行选区同样生效**——右上角的横基准是「首字右偏一个字」，
 *   框本来就贴着选区开头，再去指行尾只会横着拉一条长引线（真机上「以这种方式声明的变量」）。
 * - 头一侧取该行**最靠左**的那个字，末尾一侧取该行**最靠右**的那个字；左右两侧的摆法
 *   在各自那一侧自然也是这样一头（正右贴着行尾、正左贴着行首）。
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
    // 规则第 6 条「近哪头指哪头」：框中心落在这一行的左半就指行首那个字，右半就指行尾那个字。
    // 单行选区同样要比——右上角的横基准是「首字右偏一个字」，框本来就贴着头一个字，
    // 再去指行尾只会横着拉一条长引线（真机「以这种方式声明的变量」277px）。
    // 正右贴着行尾、正左贴着行首，按这条规则自然还是原来那一头。
    val rowLeft = rowGlyphs.minOfOrNull { it.left } ?: rowAnchor.left
    val rowRight = rowGlyphs.maxOfOrNull { it.right } ?: rowAnchor.right
    val mid = (rowLeft + rowRight) * 0.5f
    val nearStart = when {
        bubble.center.x < mid -> true
        bubble.center.x > mid -> false
        else -> leftFamily
    }
    val glyph = if (nearStart) {
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
 * 只看逐字墨迹与**划线带**（图形 / 表格 / 旁注框的边界是硬线，中线没有意义）；
 * 两行在横向上要有重叠，中间的空档不超过一个字的墨迹高度
 * （隔得很远时「中间」只是普通空地，不需要专门作为折点）。
 *
 * 划线带算「这一行的下缘」（[visualRows]）：波浪线比字墨迹低一截，
 * 只按墨迹量出来的中线正好落在波峰上——真机「函数体」的引线就是这样压着波浪线走的
 * （2026-10-31 用户定口）。按**整行**算、不按单个字算：引线横穿时是一个 y 走到底，
 * 只要这一行任何一处有划线，这条缝的中线就得让开它。
 */
private fun arrowGapMidYs(obstacles: List<ArrowObstacle>): List<Float> {
    val glyphs = obstacles.filter { it.tol <= ARROW_GLYPH_TOL_PX + 1e-4f }.map { it.rect }
    if (glyphs.size < 2) return emptyList()
    val bands = obstacles.filter { it.band }.map { it.rect }
    val rows = visualRows(glyphs, bands)
    if (rows.size < 2) return emptyList()
    // 行间空隙以一个字的墨迹高度为上限：正常行距（两三成行高）都在内，段落之间的大空档
    // 不算「两行之间」，那里横穿不需要专门挑中线。旧口径卡在一半字高，正常中文行距
    // （墨迹高 20、行距 12）反而判不出来，规则第 10 条等于没落地（2026-10-25 用户第 6 条）。
    val maxGap = rows.maxOf { it.height }
    val out = LinkedHashSet<Float>()
    for (i in rows.indices) {
        val up = rows[i]
        for (j in i + 1 until rows.size) {
            val down = rows[j]
            // 后面的行只会更靠下，间隔超过上限就可以收手。
            if (down.top - up.bottom > maxGap) break
            if (up.bottom > down.top) continue
            val overlapX = min(up.right, down.right) - max(up.left, down.left)
            if (overlapX < 1f) continue
            val gap = down.top - up.bottom
            if (gap <= 0.5f || gap > max(up.height, down.height)) continue
            out += (up.bottom + down.top) * 0.5f
        }
    }
    return out.toList()
}

/**
 * 把逐字墨迹并成**视觉上的行**（上缘排序）：纵向重叠过半的字算同一行，行框取整行的并集。
 *
 * 若这一行挂着**划线带**，行底下移到线带的下缘：波浪线画在字墨迹下面一截，读者看到的
 * 「这一行到哪儿为止」是波浪线，不是字底。只认「挂在本行下方」的线带——删除线穿在字身中部
 * （不改行底），下一行的划线隔着一整行，都不算这一行的。
 */
private fun visualRows(glyphs: List<Rect>, bands: List<Rect>): List<Rect> {
    val sorted = glyphs.sortedWith(compareBy({ it.top }, { it.left }))
    val rows = ArrayList<Rect>(sorted.size)
    for (g in sorted) {
        val last = rows.lastOrNull()
        val sameRow = last != null &&
            min(last.bottom, g.bottom) - max(last.top, g.top) > min(last.height, g.height) * 0.5f
        if (sameRow) {
            rows[rows.size - 1] = Rect(
                min(last!!.left, g.left),
                min(last.top, g.top),
                max(last.right, g.right),
                max(last.bottom, g.bottom),
            )
        } else {
            rows += g
        }
    }
    if (bands.isEmpty()) return rows
    return rows.map { row ->
        var bottom = row.bottom
        // 挂到本行下方多远以内的线带算这一行的：大约半个字高（行距再紧也不会把下一行的线带算进来）。
        val reach = row.bottom + row.height * 0.6f
        for (b in bands) {
            if (b.top < row.center.y) continue
            if (b.top > reach) continue
            if (min(row.right, b.right) - max(row.left, b.left) < 1f) continue
            if (b.bottom > bottom) bottom = b.bottom
        }
        if (bottom == row.bottom) row else Rect(row.left, row.top, row.right, bottom)
    }
}

/**
 * 引线出口：落在框的哪条边、第一段是不是横线。
 *
 * [lo] / [hi] 是**这条边上还能落点的区间**（横边是 y 区间、竖边是 x 区间）：
 * 端点离框的四角至少 0.5em，所以直线只能在这段区间里找共同坐标。
 * 没有区间信息（退化调用）时放开成全边。
 */
private data class ArrowExit(
    val point: Offset,
    val horizontal: Boolean,
    val lo: Float = Float.NEGATIVE_INFINITY,
    val hi: Float = Float.POSITIVE_INFINITY,
    /** 这条边的**整段**范围（不带 0.5em 内缩）：放宽直线时端点也不许滑出边外。 */
    val edgeLo: Float = Float.NEGATIVE_INFINITY,
    val edgeHi: Float = Float.POSITIVE_INFINITY,
    /** 出口在框的哪条边（[ARROW_EDGE_LEFT] …，-1 = 未知）：第一段只能朝边外走。 */
    val edge: Int = -1,
    /** 为了绕开障碍突破了框边 0.5em 内缩（规则第 4 条）。 */
    val breakout: Boolean = false,
    /** 这个突破点直接怼在边的两头（见 [ArrowEdgePoint.corner]）。 */
    val corner: Boolean = false,
)

/**
 * 引线入口：落在目标字的哪条边、最后一段是不是横线。
 *
 * [lo] / [hi] 同 [ArrowExit]：横着进是 y 区间、竖着进是 x 区间，
 * 竖着进时**优先取「框与选区的横向重叠段」**（引线规则第 1 条：正下方就是一条竖线）。
 */
private data class ArrowEntry(
    val point: Offset,
    val horizontal: Boolean,
    val lo: Float = Float.NEGATIVE_INFINITY,
    val hi: Float = Float.POSITIVE_INFINITY,
    /** 同 [ArrowExit.edgeLo]：入口所在那条边的整段范围。 */
    val edgeLo: Float = Float.NEGATIVE_INFINITY,
    val edgeHi: Float = Float.POSITIVE_INFINITY,
    /** 入口落在目标框的哪条边：最后一段只能从边外进（否则箭头会朝"行内"指）。 */
    val edge: Int = -1,
    /** 为了绕开障碍突破了行盒边 0.5em 内缩（规则第 4 条）。 */
    val breakout: Boolean = false,
    /** 这个突破点直接怼在边的两头（见 [ArrowEdgePoint.corner]）。 */
    val corner: Boolean = false,
)

/** 端点落在哪条边：0 左 / 1 右 / 2 上 / 3 下（-1 = 未知，不判方向）。 */
private const val ARROW_EDGE_LEFT: Int = 0
private const val ARROW_EDGE_RIGHT: Int = 1
private const val ARROW_EDGE_TOP: Int = 2
private const val ARROW_EDGE_BOTTOM: Int = 3

/** 首 / 末段的方向要求：[ARROW_NEED_HORIZONTAL] 横段、[ARROW_NEED_VERTICAL] 竖段、[ARROW_NEED_ANY] 未知。 */
private const val ARROW_NEED_HORIZONTAL: Int = 1
private const val ARROW_NEED_VERTICAL: Int = 0
private const val ARROW_NEED_ANY: Int = -1

/**
 * 折点 [bend] 是不是落在端点 [point] 的**外侧**（与 [endpointsOutward] 逐字同一条判据：
 * 出口看「折点在起点外侧」、入口看「折点在终点外侧」，两边同一个式子）。
 *
 * [sign] 见 [arrowOutwardSign]；0 = 不判方向。
 */
private fun arrowBeyond(bend: Float, point: Float, sign: Int): Boolean =
    sign == 0 || (bend - point) * sign > 0.01f

/** 这条边对应的「朝外」方向：-1 = 坐标变小，+1 = 坐标变大，0 = 未知。 */
private fun arrowOutwardSign(edge: Int): Int = when (edge) {
    ARROW_EDGE_LEFT, ARROW_EDGE_TOP -> -1
    ARROW_EDGE_RIGHT, ARROW_EDGE_BOTTOM -> 1
    else -> 0
}

/**
 * 这一端（首段 / 末段）**生成阶段**的预筛：模板这一段是横还是竖（[templateHorizontal]），
 * 与边要求的 [need] 对不上时，这一段必须**退化成零长**（坐标落在端点上，下一段顶上），
 * 否则这条候选一定过不了 [orthogonalShaped]，根本不必生成。
 *
 * 为什么值得单独判：一对端点能吐几百条候选，其中只有一两成首末段方向对得上；
 * 真机上「正下批注」一对端点就白吐十几万条死候选（1.16M 条里只有 1.2% 活到量测），
 * 生成阶段按方向跳过，是这一页引线从 2.2s 掉下来的关键。
 *
 * 退化分支不能一并跳过：六点模板在 `x1 == 起点` / `x2 == 终点` 时会化简成一条更少折的
 * 合规折线（真机上最常赢的正是这种），所以这里放行，最终仍由形状判据裁决。
 */
private fun arrowEndAllowed(
    bend: Float,
    point: Float,
    sign: Int,
    templateHorizontal: Boolean,
    need: Int,
): Boolean = when (need) {
    ARROW_NEED_ANY -> true
    ARROW_NEED_HORIZONTAL -> if (templateHorizontal) {
        arrowBeyond(bend, point, sign)
    } else {
        abs(bend - point) <= 0.01f
    }
    else -> if (templateHorizontal) {
        abs(bend - point) <= 0.01f
    } else {
        arrowBeyond(bend, point, sign)
    }
}

/**
 * 折点坐标池的暂存区：一条批注只建一次。
 *
 * 旧实现**每次生成**（每对端点 × 每个折数档）都新开两个坐标数组再排序去重，一页九条批注
 * 就是一万六千次分配（真机日志里这一页光折点池的垃圾就有十几兆）。数组尺寸取全页障碍数，
 * 换目标字时不用重建。
 */
private class ArrowElbowScratch(maxObstacles: Int) {
    val xs: FloatArray = FloatArray(maxObstacles * 2 + 2)
    val ys: FloatArray = FloatArray(maxObstacles * 2 + 2)
    val bendXs: FloatArray = FloatArray(ARROW_MAX_BEND_COORDS + 1)
    val bendYs: FloatArray = FloatArray(ARROW_GAP_MID_LIMIT + ARROW_MAX_BEND_COORDS + 1)
    val pickedYs: FloatArray = FloatArray(ARROW_MAX_BEND_COORDS)
    val xsFromStart: FloatArray = FloatArray(4)
    val xsFromEnd: FloatArray = FloatArray(4)
    val ysFromStart: FloatArray = FloatArray(4)
    val ysFromEnd: FloatArray = FloatArray(4)
}

/** 带宽区间统一成「下界 ≤ 上界」，供直线取交集。 */
private fun bandOf(a: Float, b: Float): Pair<Float, Float> = min(a, b) to max(a, b)

/**
 * 斜向摆法的 em：单行选区取整行的高度（一行里 'l' 和「使」高矮差一截，取谁的高引线就跟着变），
 * 多行选区取目标那一行那个字的高。
 */
private fun cornerRowEm(anchor: Rect, glyph: Rect): Float {
    val singleRow = abs(anchor.center.y - glyph.center.y) <= max(anchor.height, glyph.height) * 0.25f
    return if (singleRow) max(anchor.height, glyph.height) else glyph.height
}

/**
 * 沿边的一个候选点：[at] 是坐标，[breakout] 表示它**突破了 0.5em 内缩**
 * （规则第 4 条后半句「空间不足时允许突破」）。
 */
private data class ArrowEdgePoint(
    val at: Float,
    val breakout: Boolean,
    /** 直接怼在边的两头（2px 护栏上）的突破点：规则第 4 条的「突破」不该落到这个位置。 */
    val corner: Boolean = false,
)

/**
 * 沿一条边取候选坐标（**几何来源**，取代原来的 1/4、3/4、0.75 固定比例）。
 *
 * 比例点与障碍无关：框一挪、字一换，它就落到字上或跑进别人框里，只能靠路由器碰运气。
 * 真正该试的是这几种位置：
 *
 * 1. **内缩端**：0.5em 区间两头（规则第 4 条的首选位置）；
 * 2. **对齐点**：与另一端 / 框中心同坐标（能直接拉成一条直线）；
 * 3. **障碍边**：贴着障碍的边 ± 一条缝 —— 字缝、框缝里穿出去的位置；
 * 4. **整条边的两端**：0.5em 区间里一条路都走不通时，才轮到「突破」到边端（两端留护栏）。
 *
 * 前两类（以及落在区间里的障碍边）是**第一遍**的候选；第 4 类只在这一遍一条通畅的路
 * 都没找到时（[breakoutAllowed]）才加进来——规则第 4 条要的是「空间不足才突破」，
 * 平时就把边端塞进候选只会让候选池变浑、让 `tries` 白涨。
 *
 * @param edgeLo / edgeHi 这条边的**整段**范围：端点不许滑出边外。
 * @param bandA / bandB 0.5em 内缩后的首选区间（**按原序传**）：反向（这条边放不下 1em 内缩）
 *   就是「空间不足」，整条边按突破算；恰好收成一个点（一个字的竖边只剩中点）仍是合法区间。
 * @param wants 想对齐的坐标（可给几个：入口、框中心）。
 * @param vertical true = 竖边（候选是 x 坐标，取障碍的左右边）；false = 横边（取上下边）。
 * @param breakoutAllowed 是否允许取 0.5em 区间外的点（规则第 4 条的「空间不足时突破」）。
 */
private fun edgePoints(
    edgeLo: Float,
    edgeHi: Float,
    bandA: Float,
    bandB: Float,
    wants: FloatArray,
    obstacles: List<Rect>,
    vertical: Boolean,
    breakoutAllowed: Boolean = false,
    limit: Int = ARROW_EDGE_POINT_LIMIT,
    /** 「别贴着走」的那批障碍：图形 / 表格 / 别的批注框（不含文字墨迹，见 [ARROW_SOLID_CLEAR_EM]）。 */
    solids: List<Rect> = emptyList(),
    /** 在 [solids] 的边上落脚时退开的距离（0 = 与字墨迹一样贴边）。 */
    clearPx: Float = 0f,
): List<ArrowEdgePoint> {
    if (edgeHi - edgeLo < 1f) return emptyList()
    val guardLo = edgeLo + ARROW_EDGE_GUARD_PX
    val guardHi = edgeHi - ARROW_EDGE_GUARD_PX
    val out = ArrayList<ArrowEdgePoint>(limit + 2)
    // 区间反向（这条边短得放不下 1em）：没有 0.5em 的落脚点，整条边都按突破算。
    val hasBand = bandA <= bandB + 0.01f
    val bandLo = min(bandA, bandB)
    val bandHi = max(bandA, bandB)
    val mayBreak = breakoutAllowed || !hasBand
    fun add(v: Float, breakout: Boolean, corner: Boolean = false): Boolean {
        if (breakout && !mayBreak) return false
        // 2px 角部护栏只管「突破」到边端的点：它们本来就在边的两头。0.5em 区间的端点是
        // 规则第 4 条的首选位置，离的是**选区**的四角，不该被这条护栏再削掉一截
        // （多行选区里目标那一行比整段窄时，区间端点正落在这一行的边上，削掉就只剩中点了）。
        val lo = if (breakout) guardLo else edgeLo
        val hi = if (breakout) guardHi else edgeHi
        if (v < lo - 0.01f || v > hi + 0.01f) return false
        for (i in out.indices) if (abs(out[i].at - v) < 0.5f) return true
        out += ArrowEdgePoint(v, breakout, corner)
        return true
    }
    // 1) 首选区间：两头 + 对齐点 + 中点。
    if (hasBand) {
        add(bandLo, false)
        add(bandHi, false)
        add((bandLo + bandHi) * 0.5f, false)
        for (w in wants) add(w.coerceIn(bandLo, bandHi), false)
    }
    // 2) 障碍边：贴着字缝 / 框缝穿出去的位置（只收离首选区间近的几条，免得稀释候选）。
    //
    // 字墨迹照旧贴边 2px（要穿的就是字缝、行缝）；图形 / 表格 / 别的批注框退开 [clearPx]
    // 再落脚：贴着别人的框抹过去，看着像挂在人家框上（见 [ARROW_SOLID_CLEAR_EM]）。
    // 同一条边只摆一档——两档都摆，择优时「更短」会把贴边那条又挑回来；
    // 退开的那条落不下（跑出这条边、或被 0.5em 区间拦住）时，才退回贴边当最后手段。
    val hasClear = clearPx > ARROW_EDGE_BREAK_PX + 0.01f
    val center = if (hasBand) (bandLo + bandHi) * 0.5f else (edgeLo + edgeHi) * 0.5f
    val reach = ARROW_EDGE_BREAK_RANGE_PX + (if (hasBand) (bandHi - bandLo) * 0.5f else 0f)
    fun put(v: Float): Boolean = add(v, !(hasBand && v >= bandLo && v <= bandHi))
    fun putSolid(wide: Float, tight: Float) {
        if (!put(wide) && wide != tight) put(tight)
    }
    for (r in obstacles) {
        val solid = hasClear && solids.any { it === r }
        val near = if (solid) clearPx else ARROW_EDGE_BREAK_PX
        val lead = if (vertical) r.left else r.top
        val tail = if (vertical) r.right else r.bottom
        if (abs(lead - near - center) <= reach) {
            if (solid) putSolid(lead - near, lead - ARROW_EDGE_BREAK_PX) else put(lead - near)
        }
        if (abs(tail + near - center) <= reach) {
            if (solid) putSolid(tail + near, tail + ARROW_EDGE_BREAK_PX) else put(tail + near)
        }
    }
    // 3) 整条边的两端与对齐点：内缩区间全被堵死时才用得上（规则第 4 条的「突破」）。
    if (guardLo <= guardHi) {
        add(guardLo, true, corner = true)
        add(guardHi, true, corner = true)
        for (w in wants) {
            val v = w.coerceIn(guardLo, guardHi)
            add(v, true, corner = abs(v - guardLo) < 0.5f || abs(v - guardHi) < 0.5f)
        }
    }
    // 就近取前 limit 个：离 wanted 越近，越可能直接连成一条直线。
    //
    // 突破这一遍（[breakoutAllowed]）要放宽一倍：真正能救场的那条缝，常常是**两边里最靠外**的
    // 那一条（另一头的出口被别的东西顶到边上，只有它俩能对齐），按「离框心远近」排序它偏偏
    // 排最后，被 6 个一截就直接消失。真机「讨论」就是这样：出口只能走邻框右边的 1398，
    // 入口候选里 1398 排第 7，被截掉 → 永远折那 7px。
    val cap = if (breakoutAllowed) limit * 2 else limit
    val ranked = out.sortedBy { p -> wants.minOf { abs(p.at - it) } }
    if (ranked.size <= cap) return ranked
    // 0.5em 区间的两头是规则第 4 条的首选位置，也是「离目标最近」的那一头所在：
    // 被「就近取前几个」截掉就等于把最短的起点 / 终点扔掉（真机「函数体」）。
    // 先把这两头挑出来，再用离目标近的候选把名额填满。
    val kept = LinkedHashSet<ArrowEdgePoint>(cap + 2)
    if (hasBand) {
        for (p in ranked) {
            if (!p.breakout && (abs(p.at - bandLo) < 0.5f || abs(p.at - bandHi) < 0.5f)) kept += p
        }
    }
    val target = max(cap, kept.size)
    for (p in ranked) {
        if (kept.size >= target) break
        kept += p
    }
    return kept.toList()
}

/** 候选点在直线取交集时用的区间：突破点钉死在自己身上，内缩点才允许两端滑动对齐。 */
private fun bandLow(p: ArrowEdgePoint, bandA: Float, bandB: Float): Float =
    if (p.breakout) p.at else min(bandA, bandB)

private fun bandHigh(p: ArrowEdgePoint, bandA: Float, bandB: Float): Float =
    if (p.breakout) p.at else max(bandA, bandB)

/**
 * 斜向（右上 / 右下 / 左上 / 左下）的落点：**固定**在近那一端、离选区角 0.5em 的地方。
 *
 * 2026-10-27 用户定口：批注相对文字的缩进一样，引线就该画出同一个样子。旧实现把这个点当成
 * 「0.5em 区间里的一个候选」，交给路由器按长短从 {1, 0.75, 0.5} 里挑，于是同样的缩进能算出
 * 2px / 8px / 14px / 24px 好几种横向——不是随机，是没有唯一解。
 *
 * 唯一的改道：[bubble] 自己盖住了这个落点，而且框离行盒只有一条细缝（< 8px）：
 * 竖直那一段画不下箭头，只能从框的侧边出去再竖落，落点必须换到另一头。
 * 多行选区不做这个改道（另一头在别的行，落点就对不上那一行的边了）。
 */
private fun cornerEntryX(
    bubble: Rect,
    anchor: Rect,
    glyph: Rect,
    gap: Float,
    xAnchor: Rect = anchor,
): Float {
    val singleRow = abs(anchor.center.y - glyph.center.y) <= max(anchor.height, glyph.height) * 0.25f
    val h = cornerRowEm(anchor, glyph)
    // 横向区间量**选区矩形**（[xAnchor]）：与布点的「选区缘 ± 一个字」同源，
    // 走廊 = 一个字 − 0.5em，稳定折得出 L 形。
    val lo = xAnchor.left + h * 0.5f
    val hi = xAnchor.right - h * 0.5f
    val nearEnd = glyph.center.x > xAnchor.center.x
    val near = clampInset(lo, hi, if (nearEnd) hi else lo)
    val covered = near > bubble.left - NOTE_GEOM_EPS && near < bubble.right + NOTE_GEOM_EPS
    return if (singleRow && covered && gap < 8f) {
        clampInset(lo, hi, if (nearEnd) lo else hi)
    } else {
        near
    }
}

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
    /** 斜向横向基准的尺子（选区矩形）；其余方向仍按字墨迹的 [anchor]。 */
    xAnchor: Rect = anchor,
    /** 附近障碍：沿边取点时拿它们的边当候选（贴着字缝穿出去）。 */
    obstacles: List<Rect> = emptyList(),
    /** 是否允许突破 0.5em 内缩（第一遍走不通时才开，见 [edgePoints]）。 */
    breakoutAllowed: Boolean = false,
    /** 划线标注的禁触带（这一行的墨迹 + 这条线）：竖着进时停在它外侧。 */
    noTouch: Rect? = null,
): List<ArrowEntry> {
    val out = LinkedHashSet<ArrowEntry>()
    val h = glyph.height
    val rightFamily = side == NoteSide.RIGHT || side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT
    val overlapY = bubble.top < glyph.bottom - NOTE_GEOM_EPS && bubble.bottom > glyph.top + NOTE_GEOM_EPS
    // 端点离**选区的四个角**至少 0.5em（引线规则第 4 条）：沿边取点的可用区间。
    val alongLo = max(glyph.left, anchor.left + h * 0.5f)
    val alongHi = min(glyph.right, anchor.right - h * 0.5f)
    when {
        overlapY -> {
            // 进竖边：横着进，x 停在目标字（= 这一行选区的端点）的边上，不扎进文字里。
            // 划线标注不用再往外让：横进的 y 被夹在字身中部，下划线在字下方，够不着。
            val x = if (rightFamily) glyph.right else glyph.left
            // 纵向也要离选区上下两个角 0.5em。
            val bandA = max(glyph.top, anchor.top + h * 0.5f)
            val bandB = min(glyph.bottom, anchor.bottom - h * 0.5f)
            for (p in edgePoints(
                glyph.top, glyph.bottom, bandA, bandB,
                floatArrayOf(bubble.center.y), obstacles, vertical = false,
                breakoutAllowed = breakoutAllowed,
            )) {
                out += ArrowEntry(
                    Offset(x, p.at), horizontal = true,
                    lo = bandLow(p, bandA, bandB), hi = bandHigh(p, bandA, bandB),
                    edgeLo = glyph.top, edgeHi = glyph.bottom,
                    edge = if (rightFamily) ARROW_EDGE_RIGHT else ARROW_EDGE_LEFT,
                    breakout = p.breakout,
                    corner = p.corner,
                )
            }
        }
        else -> {
            val above = bubble.bottom <= glyph.top + NOTE_GEOM_EPS
            val y = verticalEntryEdge(box, above = above, noTouch = noTouch)
            val gap = if (above) y - bubble.bottom else bubble.top - y
            // 落点唯一确定（近端、离选区角 0.5em），不再交给路由器挑比例——
            // 同样缩进要画出同样的引线（2026-10-27）。斜向的「选区角」按选区矩形量（[xAnchor]）。
            val h = cornerRowEm(anchor, glyph)
            val (ilo, ihi) = bandOf(xAnchor.left + h * 0.5f, xAnchor.right - h * 0.5f)
            out += ArrowEntry(
                Offset(cornerEntryX(bubble, anchor, glyph, gap, xAnchor), y),
                horizontal = false, lo = ilo, hi = ihi,
                edgeLo = xAnchor.left, edgeHi = xAnchor.right,
                edge = if (above) ARROW_EDGE_TOP else ARROW_EDGE_BOTTOM,
            )
        }
    }
    // 左右两侧被正文挡住时，规则第 3 条要「下横上 / 上横下」：竖着从框的上 / 下边出去，
    // 沿两行之间的空隙横穿，再竖着进目标字的上 / 下边。这里把上 / 下两条边也补成入口。
    if (side == NoteSide.RIGHT || side == NoteSide.LEFT) {
        for (above in booleanArrayOf(true, false)) {
            val y = verticalEntryEdge(box, above = above, noTouch = noTouch)
            for (p in edgePoints(
                glyph.left, glyph.right, alongLo, alongHi,
                floatArrayOf(bubble.center.x), obstacles, vertical = true,
                breakoutAllowed = breakoutAllowed,
            )) {
                out += ArrowEntry(
                    Offset(p.at, y), horizontal = false,
                    lo = bandLow(p, alongLo, alongHi), hi = bandHigh(p, alongLo, alongHi),
                    edgeLo = glyph.left, edgeHi = glyph.right,
                    edge = if (above) ARROW_EDGE_TOP else ARROW_EDGE_BOTTOM,
                    breakout = p.breakout,
                    corner = p.corner,
                )
            }
        }
    }
    return out.toList()
}

/**
 * 正下 / 正上的入口：竖着进**朝框的那条行盒边**，横向优先落在
 * 「批注框与选区（要指的那一行）的横向重叠段」里（引线规则第 1 / 6 条）。
 *
 * 重叠段就是「一条竖线够得着」的范围：框在正下方时它通常把框的中心整个包住，
 * 于是直线一条竖线就解决；斜着靠过去时才退到「靠起点 / 靠末端」的那个字。
 */
private fun arrowBelowEntries(
    bubble: Rect,
    glyph: Rect,
    anchor: Rect,
    box: Rect,
    below: Boolean,
    /** 附近障碍：沿边取点时拿它们的边当候选（贴着字缝、框缝穿出去）。 */
    obstacles: List<Rect> = emptyList(),
    /** 是否允许突破 0.5em 内缩（第一遍走不通时才开，见 [edgePoints]）。 */
    breakoutAllowed: Boolean = false,
    /** 划线标注的禁触带（这一行的墨迹 + 这条线）：可以进得行盒，但停在它外侧。 */
    noTouch: Rect? = null,
): List<ArrowEntry> {
    val y = verticalEntryEdge(box, above = !below, noTouch = noTouch)
    val h = glyph.height
    val inset = max(h * 0.5f, 0.5f)
    val overlap = overlapBand(bubble.left, bubble.right, anchor.left, anchor.right, inset)
    val bandA = overlap?.first ?: max(glyph.left, anchor.left + h * 0.5f)
    val bandB = overlap?.second ?: min(glyph.right, anchor.right - h * 0.5f)
    // 行盒 = 所选文字的盒子（2026-10-28 定口）：整条边就是选区墨迹的左右两端；
    // 首选区间（框 ∩ 选区各收 0.5em）走不通时，允许突破到这两端（规则第 4 条）。
    val edge = if (below) ARROW_EDGE_BOTTOM else ARROW_EDGE_TOP
    return edgePoints(
        anchor.left, anchor.right, bandA, bandB,
        floatArrayOf(bubble.center.x), obstacles, vertical = true,
        breakoutAllowed = breakoutAllowed,
    ).map { p ->
        ArrowEntry(
            Offset(p.at, y), horizontal = false,
            lo = bandLow(p, bandA, bandB), hi = bandHigh(p, bandA, bandB),
            edgeLo = anchor.left, edgeHi = anchor.right, edge = edge,
            breakout = p.breakout,
            corner = p.corner,
        )
    }
}

/**
 * 正下 / 正上的出口：从**朝文字那条边**竖着出去（第一段 ⊥ 框边），
 * 沿边落点同样离框角至少 0.5em；再补一条侧边出口——文字横向错开时，
 * 「侧边出去 + 一折竖回文字」比绕回正上方少一段（引线规则第 6 条）。
 */
private fun arrowBelowExits(
    bubble: Rect,
    below: Boolean,
    entry: ArrowEntry,
    em: Float,
    /** 附近障碍：沿边取点时拿它们的边当候选（贴着框缝穿出去）。 */
    obstacles: List<Rect> = emptyList(),
    /** 是否允许突破 0.5em 内缩（第一遍走不通时才开，见 [edgePoints]）。 */
    breakoutAllowed: Boolean = false,
    /** 图形 / 表格 / 别的批注框：沿它们的边取点时退开一点（见 [ARROW_SOLID_CLEAR_EM]）。 */
    solids: List<Rect> = emptyList(),
): List<ArrowExit> {
    val out = LinkedHashSet<ArrowExit>()
    val clearPx = arrowSolidClearPx(em, solids)
    val pad = max(if (em > 0f) em * 0.5f else 0f, NOTE_GEOM_EPS)
    val bandXA = bubble.left + pad
    val bandXB = bubble.right - pad
    val bandYA = bubble.top + pad
    val bandYB = bubble.bottom - pad
    val facingY = if (below) bubble.top else bubble.bottom
    // 竖出口：x 候选取几何点（对齐入口 / 框中心 / 障碍边 / 边端），不再按 1/4、3/4 乱试。
    // 出口沿框的上边取点时，只要对齐**入口**（引线规则第 6 条：方向定了就取最短）。
    // 把框中心也当成对齐目标，会让「框心」白占一个候选名额：真机「讨论」（2026-10-30）上边
    // 一长条候选里，离目标最近的那一侧（邻框右边 1545）就这样被 6 个名额挤掉，
    // 引线只好从框的右端（1756）出去，再多走两百多像素才回头。
    for (p in preferFreeRun(
        edgePoints(
            bubble.left, bubble.right, bandXA, bandXB,
            floatArrayOf(entry.point.x), obstacles, vertical = true,
            breakoutAllowed = breakoutAllowed,
            // 先多收一些：见 [preferFreeRun]——名额要留给「真能竖着走上去」的那条缝。
            limit = edgePointCap(breakoutAllowed) * 3,
            solids = solids,
            clearPx = clearPx,
        ),
        from = facingY,
        to = entry.point.y,
        obstacles = obstacles,
        vertical = true,
        cap = edgePointCap(breakoutAllowed),
    )) {
        out += ArrowExit(
            Offset(p.at, facingY), horizontal = false,
            lo = bandLow(p, bandXA, bandXB), hi = bandHigh(p, bandXA, bandXB),
            edgeLo = bubble.left, edgeHi = bubble.right,
            edge = if (below) ARROW_EDGE_TOP else ARROW_EDGE_BOTTOM,
            breakout = p.breakout,
            corner = p.corner,
        )
    }
    val sideX = if (bubble.center.x >= entry.point.x) bubble.left else bubble.right
    // 侧边出口（文字横向错开时比绕回正上方少一段）：y 候选同理，同样先让「横着走得过去」的。
    for (p in preferFreeRun(
        edgePoints(
            bubble.top, bubble.bottom, bandYA, bandYB,
            floatArrayOf(entry.point.y, bubble.center.y), obstacles, vertical = false,
            breakoutAllowed = breakoutAllowed,
            limit = edgePointCap(breakoutAllowed) * 3,
            solids = solids,
            clearPx = clearPx,
        ),
        from = sideX,
        to = entry.point.x,
        obstacles = obstacles,
        vertical = false,
        cap = edgePointCap(breakoutAllowed),
    )) {
        out += ArrowExit(
            Offset(sideX, p.at), horizontal = true,
            lo = bandLow(p, bandYA, bandYB), hi = bandHigh(p, bandYA, bandYB),
            edgeLo = bubble.top, edgeHi = bubble.bottom,
            edge = if (bubble.center.x >= entry.point.x) ARROW_EDGE_LEFT else ARROW_EDGE_RIGHT,
            breakout = p.breakout,
            corner = p.corner,
        )
    }
    return out.toList()
}

/**
 * 一个出口方向上有多少条候选：首次枚举取 6 条，允许突破 0.5em 那一遍放宽一倍
 * （见 [edgePoints] 的说明）。
 */
private fun edgePointCap(breakoutAllowed: Boolean): Int =
    if (breakoutAllowed) ARROW_EDGE_POINT_LIMIT * 2 else ARROW_EDGE_POINT_LIMIT

/**
 * 沿别人的框（图形 / 表格 / 别的批注框）走时退开多少像素：四分之一字（见 [ARROW_SOLID_CLEAR_EM]），
 * 一个字都量不出来时（单测里不给 lineH）退到 4px——总比贴着 2px 强。
 */
private fun arrowSolidClearPx(em: Float, solids: List<Rect>): Float {
    if (solids.isEmpty()) return 0f
    return max(if (em > 0f) em * ARROW_SOLID_CLEAR_EM else 0f, ARROW_EDGE_BREAK_PX * 2f)
}

/**
 * 出口候选按「**这一头竖得上去吗**」（横出口则是横得过去吗）分两档：先排一路不撞东西的，
 * 再排会撞的；同档里保持 [edgePoints] 原有的「离入口近的在前」，最后截到 [cap] 条。
 *
 * 为什么必须这么分（2026-10-30 真机「讨论」）：候选名额只有 6 / 12 条，而页面上的逐字墨迹
 * 能贡献几十条「贴着某个字边」的坐标。按离入口近排序时，紧挨着入口的那一串字边（入口那一行
 * 自己的字）全排前面，可它们**竖不上去**——正下方那条批注框横在中间，从这些 x 出去立刻撞框，
 * 只剩「先横一小步再绕」的 Z 形；真正能用的是框左边那条缝（1545），它离入口远，
 * 却是一路空到目标行的最短起点（引线规则第 6 条：方向定了就取最短的那一头）。
 *
 * 判定取严：贴着障碍边（2px 以内）也算撞，宁可把一条其实能走的位置排到后面，
 * 也不让「看着近、实际堵死」的位置占掉名额。
 */
private fun preferFreeRun(
    points: List<ArrowEdgePoint>,
    /** 这条线的另一端坐标：竖出口传入口的 y，横出口传入口的 x。 */
    from: Float,
    /** 出口所在边的坐标（框的上 / 下边 y，左 / 右边 x）。 */
    to: Float,
    obstacles: List<Rect>,
    vertical: Boolean,
    cap: Int,
): List<ArrowEdgePoint> {
    if (points.size <= cap) return points
    val lo = min(from, to)
    val hi = max(from, to)
    val free = ArrayList<ArrowEdgePoint>(points.size)
    val blocked = ArrayList<ArrowEdgePoint>(points.size)
    for (p in points) {
        val hit = obstacles.any { r ->
            val across = if (vertical) {
                p.at > r.left - ARROW_EDGE_BREAK_PX && p.at < r.right + ARROW_EDGE_BREAK_PX
            } else {
                p.at > r.top - ARROW_EDGE_BREAK_PX && p.at < r.bottom + ARROW_EDGE_BREAK_PX
            }
            val along = if (vertical) {
                r.bottom > lo + 0.5f && r.top < hi - 0.5f
            } else {
                r.right > lo + 0.5f && r.left < hi - 0.5f
            }
            across && along
        }
        if (hit) blocked += p else free += p
    }
    if (free.size >= cap) return free.take(cap)
    return free + blocked.subList(0, min(cap - free.size, blocked.size))
}

/**
 * 两段区间（批注框与选区在某个方向上的投影）的交集，两端各留 [inset]。
 *
 * 交集窄到放不下两个端点（斜靠时只重叠一两个像素）就退回交集的中点；
 * 完全没有交集时返回 null，调用方改用自己的区间。
 */
private fun overlapBand(
    aLo: Float,
    aHi: Float,
    bLo: Float,
    bHi: Float,
    inset: Float,
): Pair<Float, Float>? {
    val lo = max(aLo, bLo)
    val hi = min(aHi, bHi)
    if (hi < lo) return null
    val pad = min(inset, (hi - lo) * 0.45f)
    return (lo + pad) to (hi - pad)
}

/** 把沿边坐标夹进「离两角至少 0.5em」的区间；区间反了就取中点。 */
private fun clampInset(lo: Float, hi: Float, v: Float): Float =
    if (lo >= hi) (lo + hi) * 0.5f else v.coerceIn(lo, hi)

/**
 * 竖着进时箭头停在哪条边上（引线规则第 9 条，2026-10-28 用户定口）：
 *
 * - **高亮标注**（[noTouch] 为 null）：停在**行盒边框**，不得侵入行盒内部。高亮底色画的就是
 *   整行行盒（`decorLineRects` 取 `line.yTop..yBottom`），所以无论批注框在哪一行，竖进端点
 *   都取行盒边；框与行盒叠在一起时宁可让箭头尖被框盖住，也不能让箭头扎进高亮里。
 * - **划线标注**：可以进到行盒内部，但停在「墨迹 + 划线」这条禁触带外侧
 *   [ARROW_NO_TOUCH_GAP_PX]，不得触及下划线或墨迹。落点由禁触带决定、不再夹回行盒：
 *   线画得比行盒还靠下时（行高很紧），箭头就停在行盒外的那条线下方，否则会压到线上。
 */
private fun verticalEntryEdge(
    box: Rect,
    above: Boolean,
    noTouch: Rect? = null,
): Float {
    if (noTouch != null) {
        return if (above) {
            noTouch.top - ARROW_NO_TOUCH_GAP_PX
        } else {
            noTouch.bottom + ARROW_NO_TOUCH_GAP_PX
        }
    }
    return if (above) box.top else box.bottom
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
    /** 附近障碍：沿边取点时拿它们的边当候选（贴着框缝穿出去）。 */
    obstacles: List<Rect> = emptyList(),
    /** 是否允许突破 0.5em 内缩（第一遍走不通时才开，见 [edgePoints]）。 */
    breakoutAllowed: Boolean = false,
    /** 图形 / 表格 / 别的批注框：沿它们的边取点时退开一点（见 [ARROW_SOLID_CLEAR_EM]）。 */
    solids: List<Rect> = emptyList(),
): List<ArrowExit> {
    val out = LinkedHashSet<ArrowExit>()
    val clearPx = arrowSolidClearPx(em, solids)
    val eps = NOTE_GEOM_EPS
    // 起点离框的四个角也至少 0.5em（引线规则第 4 条）：框边的可用区间。
    val pad = if (em > 0f) em * 0.5f else 0f
    val loY = bubble.top + max(pad, eps)
    val hiY = bubble.bottom - max(pad, eps)
    val loX = bubble.left + max(pad, eps)
    val hiX = bubble.right - max(pad, eps)
    // 方向定下来以后「越短越好」（引线规则第 6 条）：出口沿边取点时，唯一的对齐目标是
    // **另一端**（入口）。把框中心也算进对齐目标会把它挤下去——真机「函数体」就是这样：
    // 框中心附近的几条字边占满了候选，离入口最近的那一头（0.5em 区间端头）反而被截掉，
    // 引线只好从框中间出去，白白多走三百多像素。
    val wantY = floatArrayOf(entry.point.y)
    val wantX = floatArrayOf(entry.point.x)
    fun edgeY(x: Float) {
        for (p in edgePoints(
            bubble.top, bubble.bottom, loY, hiY, wantY, obstacles, vertical = false,
            breakoutAllowed = breakoutAllowed,
            solids = solids,
            clearPx = clearPx,
        )) {
            out += ArrowExit(
                Offset(x, p.at), horizontal = true,
                lo = bandLow(p, loY, hiY), hi = bandHigh(p, loY, hiY),
                edgeLo = bubble.top, edgeHi = bubble.bottom,
                edge = if (x <= bubble.center.x) ARROW_EDGE_LEFT else ARROW_EDGE_RIGHT,
                breakout = p.breakout,
                corner = p.corner,
            )
        }
    }
    fun edgeX(y: Float) {
        for (p in edgePoints(
            bubble.left, bubble.right, loX, hiX, wantX, obstacles, vertical = true,
            breakoutAllowed = breakoutAllowed,
            solids = solids,
            clearPx = clearPx,
        )) {
            out += ArrowExit(
                Offset(p.at, y), horizontal = false,
                lo = bandLow(p, loX, hiX), hi = bandHigh(p, loX, hiX),
                edgeLo = bubble.left, edgeHi = bubble.right,
                edge = if (y <= bubble.center.y) ARROW_EDGE_TOP else ARROW_EDGE_BOTTOM,
                breakout = p.breakout,
                corner = p.corner,
            )
        }
    }
    val rightFamily = side == NoteSide.RIGHT || side == NoteSide.BELOW_RIGHT || side == NoteSide.ABOVE_RIGHT
    // 朝正文的那条竖边：右侧摆法从左边出去，左侧摆法从右边出去。
    if (rightFamily) edgeY(bubble.left) else edgeY(bubble.right)
    // 文字在框上方 / 下方时，也可以直接从框的上边 / 下边竖着出去（对齐就是一条直线）。
    if (glyph.center.y < bubble.top - eps) edgeX(bubble.top)
    if (glyph.center.y > bubble.bottom + eps) edgeX(bubble.bottom)
    // 规则第 3 条「下横上 / 上横下」：左右两侧**同一行**被文字阻隔时，竖着从框的上 / 下边
    // 出去、沿行间横穿、再竖着进目标字。没有这一对出口，候选里只剩「横出 → 竖走 → 横进」
    // 的之字形，画出来就是绕到字外面再折回来（2026-10-25 用户第 2 / 8 条）。
    if (side == NoteSide.RIGHT || side == NoteSide.LEFT) {
        edgeX(bubble.top)
        edgeX(bubble.bottom)
    }
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
    val (loY, hiY) = bandOf(bubble.top + NOTE_GEOM_EPS, bubble.bottom - NOTE_GEOM_EPS)
    val (loX, hiX) = bandOf(bubble.left + NOTE_GEOM_EPS, bubble.right - NOTE_GEOM_EPS)
    val yAt = clampInset(loY, hiY, bubble.center.y)
    val xAt = clampInset(loX, hiX, bubble.center.x)
    return when (side) {
        // 右下 / 右上 / 左下 / 左上：从**朝正文那条竖边**横着出去，再 90° 竖回文字。
        NoteSide.BELOW_RIGHT, NoteSide.ABOVE_RIGHT, NoteSide.RIGHT ->
            ArrowExit(
                Offset(bubble.left, yAt), horizontal = true, lo = loY, hi = hiY,
                edgeLo = bubble.top, edgeHi = bubble.bottom,
            )
        NoteSide.BELOW_LEFT, NoteSide.ABOVE_LEFT, NoteSide.LEFT ->
            ArrowExit(
                Offset(bubble.right, yAt), horizontal = true, lo = loY, hi = hiY,
                edgeLo = bubble.top, edgeHi = bubble.bottom,
            )
        NoteSide.BELOW -> ArrowExit(
            Offset(xAt, bubble.top), horizontal = false, lo = loX, hi = hiX,
            edgeLo = bubble.left, edgeHi = bubble.right,
        )
        NoteSide.ABOVE -> ArrowExit(
            Offset(xAt, bubble.bottom), horizontal = false, lo = loX, hi = hiX,
            edgeLo = bubble.left, edgeHi = bubble.right,
        )
        null, NoteSide.OTHER -> when (relation) {
            NoteArrowRelation.RIGHT -> ArrowExit(
                Offset(bubble.left, yAt), true, loY, hiY, bubble.top, bubble.bottom,
            )
            NoteArrowRelation.LEFT -> ArrowExit(
                Offset(bubble.right, yAt), true, loY, hiY, bubble.top, bubble.bottom,
            )
            NoteArrowRelation.ABOVE -> ArrowExit(
                Offset(xAt, bubble.bottom), false, loX, hiX, bubble.left, bubble.right,
            )
            NoteArrowRelation.BELOW -> ArrowExit(
                Offset(xAt, bubble.top), false, loX, hiX, bubble.left, bubble.right,
            )
            // 斜靠：竖直方向先分开就先竖着出去，否则横着出去。
            NoteArrowRelation.SIDE -> if (anchor.bottom <= bubble.top || anchor.top >= bubble.bottom) {
                val up = anchor.bottom <= bubble.top
                ArrowExit(
                    Offset(xAt, if (up) bubble.top else bubble.bottom), false, loX, hiX,
                    bubble.left, bubble.right,
                )
            } else {
                val leftward = anchor.right <= bubble.left
                ArrowExit(
                    Offset(if (leftward) bubble.left else bubble.right, yAt), true, loY, hiY,
                    bubble.top, bubble.bottom,
                )
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
    /** 附近障碍：沿边取点时拿它们的边当候选（贴着字缝穿出去）。 */
    obstacles: List<Rect> = emptyList(),
    /** 是否允许突破 0.5em 内缩（第一遍走不通时才开，见 [edgePoints]）。 */
    breakoutAllowed: Boolean = false,
    /** 划线标注的禁触带（这一行的墨迹 + 这条线）：竖着进时停在它外侧。 */
    noTouch: Rect? = null,
): List<ArrowEntry> {
    val h = glyph.height
    if (horizontal) {
        // 横进的 y 夹在字身中部，碰不到字下方的划线，x 仍停在字墨迹的边上。
        val x = if (glyph.center.x <= bubble.center.x) glyph.right else glyph.left
        val bandA = glyph.top + h * 0.5f
        val bandB = glyph.bottom - h * 0.5f
        // 端点落在哪条边：落在右边就是「从右边进」，别按 x 跟中心比（那样正好反了）。
        val edge = if (x >= glyph.center.x) ARROW_EDGE_RIGHT else ARROW_EDGE_LEFT
        return edgePoints(
            glyph.top, glyph.bottom, bandA, bandB,
            floatArrayOf(exit.point.y), obstacles, vertical = false,
            breakoutAllowed = breakoutAllowed,
        ).map { p ->
            ArrowEntry(
                Offset(x, p.at), horizontal = true,
                lo = bandLow(p, bandA, bandB), hi = bandHigh(p, bandA, bandB),
                edgeLo = glyph.top, edgeHi = glyph.bottom, edge = edge,
                breakout = p.breakout,
                corner = p.corner,
            )
        }
    }
    val above = glyph.center.y > bubble.center.y
    val y = verticalEntryEdge(box, above = above, noTouch = noTouch)
    val edge = if (above) ARROW_EDGE_TOP else ARROW_EDGE_BOTTOM
    val bandA = glyph.left + h * 0.5f
    val bandB = glyph.right - h * 0.5f
    return edgePoints(
        glyph.left, glyph.right, bandA, bandB,
        floatArrayOf(exit.point.x), obstacles, vertical = true,
        breakoutAllowed = breakoutAllowed,
    ).map { p ->
        ArrowEntry(
            Offset(p.at, y), horizontal = false,
            lo = bandLow(p, bandA, bandB), hi = bandHigh(p, bandA, bandB),
            edgeLo = glyph.left, edgeHi = glyph.right, edge = edge,
            breakout = p.breakout,
            corner = p.corner,
        )
    }
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
 * 端点方向：第一段必须从框边**朝外**走，最后一段必须从边**外侧**进。
 *
 * 只验「垂直框边」还不够：从行盒内部往上折也能满足垂直，画出来就是箭头倒着指回行间
 * （真机「使用 val」引线，2026-10-26）。方向不对的候选直接不采用。
 */
private fun endpointsOutward(
    xs: FloatArray,
    ys: FloatArray,
    n: Int,
    exitEdge: Int,
    entryEdge: Int,
): Boolean {
    if (n < 2) return false
    val leaves = when (exitEdge) {
        ARROW_EDGE_LEFT -> xs[1] < xs[0] - 0.01f
        ARROW_EDGE_RIGHT -> xs[1] > xs[0] + 0.01f
        ARROW_EDGE_TOP -> ys[1] < ys[0] - 0.01f
        ARROW_EDGE_BOTTOM -> ys[1] > ys[0] + 0.01f
        else -> true
    }
    if (!leaves) return false
    return when (entryEdge) {
        ARROW_EDGE_LEFT -> xs[n - 2] < xs[n - 1] - 0.01f
        ARROW_EDGE_RIGHT -> xs[n - 2] > xs[n - 1] + 0.01f
        ARROW_EDGE_TOP -> ys[n - 2] < ys[n - 1] - 0.01f
        ARROW_EDGE_BOTTOM -> ys[n - 2] > ys[n - 1] + 0.01f
        else -> true
    }
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
    /** 横段折点（ys）能用的障碍边：缺省与 [obstacleRects] 相同，热路径传「不含文字墨迹」的那份。 */
    bendRects: List<Rect> = obstacleRects,
    /**
     * 首段 / 末段必须是横段还是竖段（[ARROW_NEED_HORIZONTAL] / [ARROW_NEED_VERTICAL] /
     * [ARROW_NEED_ANY] = 未知，不做预筛）。见 [arrowEndAllowed]。
     */
    firstNeed: Int = ARROW_NEED_ANY,
    lastNeed: Int = ARROW_NEED_ANY,
    /** 首 / 末段必须朝哪边（[arrowOutwardSign]）：与 [endpointsOutward] 同一条判据，提前剔掉反方向的折点。 */
    exitSign: Int = 0,
    entrySign: Int = 0,
    /** 折点坐标池的暂存区：一条批注只建一次，热路径不再每次生成都新开两个数组。 */
    scratch: ArrowElbowScratch = ArrowElbowScratch(obstacleRects.size),
    emit: (FloatArray, FloatArray, Int) -> Unit,
) {
    if (bends !in 1..ARROW_MAX_BENDS) return
    val xs = if (scratch.xs.size >= obstacleRects.size * 2 + 2) {
        scratch.xs
    } else {
        FloatArray(obstacleRects.size * 2 + 2)
    }
    val ys = if (scratch.ys.size >= bendRects.size * 2 + 2) {
        scratch.ys
    } else {
        FloatArray(bendRects.size * 2 + 2)
    }
    var xn = 0
    var yn = 0
    xs[xn++] = start.x
    xs[xn++] = end.x
    ys[yn++] = start.y
    ys[yn++] = end.y
    for (r in obstacleRects) {
        xs[xn++] = r.left - ARROW_BEND_CLEARANCE_PX
        xs[xn++] = r.right + ARROW_BEND_CLEARANCE_PX
    }
    for (r in bendRects) {
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
    val bendXs = scratch.bendXs
    var bendXn = pickCoords(xs, xn, (start.x + end.x) * 0.5f, bendXs, ARROW_MAX_BEND_COORDS)
    // 纵向候选：行间中线排在前面（优先被取到），再补「离中点最近的坐标」，最后是两端中点。
    val bendYs = scratch.bendYs
    var bendYn = 0
    for (y in extraYs) {
        if (bendYn >= ARROW_GAP_MID_LIMIT) break
        bendYs[bendYn++] = y
    }
    val pickedYs = scratch.pickedYs
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
    // 每一档折数都试「横先手（x 池）/ 竖先手（y 池）」两种模板；每一端能否用这一档的
    // 模板，由 [arrowEndAllowed] 按「这段是横是竖」提前判掉（详见那边的注释）。
    when (bends) {
        1 -> {
            // H,V：首段 x 从起点走到终点，末段 y 从起点走到终点。
            if (arrowEndAllowed(end.x, start.x, exitSign, true, firstNeed) &&
                arrowEndAllowed(start.y, end.y, entrySign, false, lastNeed)
            ) {
                emitPoints(
                    px, py, 3,
                    start.x, start.y, end.x, start.y, end.x, end.y,
                    0f, 0f, 0f, 0f, 0f, 0f, emit,
                )
            }
            // V,H：判据刚好换轴。
            if (arrowEndAllowed(end.y, start.y, exitSign, false, firstNeed) &&
                arrowEndAllowed(start.x, end.x, entrySign, true, lastNeed)
            ) {
                emitPoints(
                    px, py, 3,
                    start.x, start.y, start.x, end.y, end.x, end.y,
                    0f, 0f, 0f, 0f, 0f, 0f, emit,
                )
            }
        }
        2 -> {
            for (i in 0 until bendXn) {
                val x = bendXs[i]
                // 两个端点都在 x 上：首段朝框外、末段朝字外，同一个折点必须同时满足。
                if (!arrowEndAllowed(x, start.x, exitSign, true, firstNeed)) continue
                if (!arrowEndAllowed(x, end.x, entrySign, true, lastNeed)) continue
                emitPoints(
                    px, py, 4,
                    start.x, start.y, x, start.y, x, end.y, end.x, end.y,
                    0f, 0f, 0f, 0f, emit,
                )
            }
            for (i in 0 until bendYn) {
                val y = bendYs[i]
                if (!arrowEndAllowed(y, start.y, exitSign, false, firstNeed)) continue
                if (!arrowEndAllowed(y, end.y, entrySign, false, lastNeed)) continue
                emitPoints(
                    px, py, 4,
                    start.x, start.y, start.x, y, end.x, y, end.x, end.y,
                    0f, 0f, 0f, 0f, emit,
                )
            }
        }
        3 -> for (i in 0 until bendXn) {
            val x = bendXs[i]
            val firstX = arrowEndAllowed(x, start.x, exitSign, true, firstNeed)
            val lastX = arrowEndAllowed(x, end.x, entrySign, true, lastNeed)
            for (j in 0 until bendYn) {
                val y = bendYs[j]
                // H,V,H,V / V,H,V,H：首段与末段不在同一根轴上，两个判据各管一头。
                if (firstX && arrowEndAllowed(y, end.y, entrySign, false, lastNeed)) {
                    emitPoints(
                        px, py, 5,
                        start.x, start.y, x, start.y, x, y, end.x, y, end.x, end.y,
                        0f, 0f, emit,
                    )
                }
                if (arrowEndAllowed(y, start.y, exitSign, false, firstNeed) && lastX) {
                    emitPoints(
                        px, py, 5,
                        start.x, start.y, start.x, y, x, y, x, end.y, end.x, end.y,
                        0f, 0f, emit,
                    )
                }
            }
        }
        // 四折只做「绕开一整条竖带 / 横带」两种模板：两臂各一个自由坐标，够绕过图形与表格。
        // 竖 / 横那个「跨过去」的坐标取全部候选（绕过整块图形靠的就是它）；两臂的坐标则分别
        // **贴着起点 / 终点那一侧取**——只取离中点最近的几个会漏掉「先横到障碍之外、再竖过去」
        // 的那一对（真机第 5/6 条：从两行字之间横穿的绕行正是这种形状）。
        else -> {
            val xsFromStart = scratch.xsFromStart
            val xsFromEnd = scratch.xsFromEnd
            val ysFromStart = scratch.ysFromStart
            val ysFromEnd = scratch.ysFromEnd
            val xsn = pickCoords(xs, xn, start.x, xsFromStart)
            val xen = pickCoords(xs, xn, end.x, xsFromEnd)
            val ysn = pickCoords(ys, yn, start.y, ysFromStart)
            val yen = pickCoords(ys, yn, end.y, ysFromEnd)
            for (i in 0 until xsn) {
                val x1 = xsFromStart[i]
                // 两臂分别贴着起点 / 终点那一侧：反方向的那一整排循环直接跳过。
                if (!arrowEndAllowed(x1, start.x, exitSign, true, firstNeed)) continue
                for (j in 0 until xen) {
                    val x2 = xsFromEnd[j]
                    if (!arrowEndAllowed(x2, end.x, entrySign, true, lastNeed)) continue
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
                if (!arrowEndAllowed(y1, start.y, exitSign, false, firstNeed)) continue
                for (j in 0 until yen) {
                    val y2 = ysFromEnd[j]
                    if (!arrowEndAllowed(y2, end.y, entrySign, false, lastNeed)) continue
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


/**
 * 引线形状名次（规则第 1 / 2 / 3 / 6 条）：0 单段直线 → 1 首选折法 → 2 次选折法 → 3 其它。
 *
 * - **单段直线**（规则第 1 条）永远第一；
 * - **一折的 L 形**（规则第 2 条）是「首选折法」：斜向摆法本来就要它，正右 / 正左
 *   被挤得对不齐时也是它最短；
 * - **左右两侧**被字挡住时的「下横上 / 上横下」（竖出 → 行间横穿 → 竖进，规则第 3 条）
 *   也归首选：它跨过的是行间空白，而不是贴着字边抹过去；
 * - 其余两折归次选。首选与次选之间还按折数决胜（[ArrowPath.betterThan]），
 *   所以没有文字阻隔时，一折的 L 形压过两折的「下横上」。
 */
private fun arrowForm(
    side: NoteSide?,
    count: Int,
    firstVertical: Boolean,
    lastVertical: Boolean,
): Int {
    if (count <= 2) return ARROW_FORM_STRAIGHT
    val bends = count - 2
    val beside = side == NoteSide.RIGHT || side == NoteSide.LEFT
    return when {
        bends == 1 -> ARROW_FORM_FIRST
        beside && bends == 2 && firstVertical && lastVertical -> ARROW_FORM_FIRST
        bends == 2 -> ARROW_FORM_SECOND
        else -> ARROW_FORM_OTHER
    }
}

/**
 * 这条候选还有没有可能赢过 [best]（只看顶点就能判的那几项）。
 *
 * 判据顺序是「交叉 → 形状名次 → 折数 → 穿字量 → 更短 → 贴字 → 是否突破 → 候选顺序」，
 * 交叉数要靠障碍才知道，所以这里只在**形状名次已经更差、或形状相同而折数不少**时
 * 用「两端直线距离」当长度下界判死它：路径长度永远不小于两端直线距离。
 *
 * 结果严格保守（返回 false 就一定赢不了），所以跳过量测不改变选择结果。
 */
private fun canStillWin(
    xs: FloatArray,
    ys: FloatArray,
    count: Int,
    best: ArrowPath,
    side: NoteSide?,
    /** 末段短于这个长度时会被删掉（规则第 11 条）：形状按删掉以后算，与实测口径一致。 */
    shortTail: Float = 0f,
): Boolean {
    if (count < 2) return false
    // 「贴别人的框」（[ArrowPath.grip]）排在长度前面：只要现有最优还贴着谁，一条更长但离得开的
    // 候选就有可能赢，此时不能拿长度把它判死。
    if (best.grip > 0f) return true
    val lastVertical = abs(xs[count - 2] - xs[count - 1]) < 0.01f
    val tailDx = xs[count - 1] - xs[count - 2]
    val tailDy = ys[count - 1] - ys[count - 2]
    val hideTail = shortTail > 0f && count >= 3 && lastVertical &&
        sqrt(tailDx * tailDx + tailDy * tailDy) < shortTail
    val shown = if (hideTail) count - 1 else count
    val form = arrowForm(
        side,
        shown,
        abs(xs[0] - xs[1]) < 0.01f,
        if (hideTail) abs(xs[count - 3] - xs[count - 2]) < 0.01f else lastVertical,
    )
    val bends = max(0, shown - 2)
    if (form < best.form) return true
    if (form == best.form && bends < best.bends) return true
    val dx = xs[count - 1] - xs[0]
    val dy = ys[count - 1] - ys[0]
    val limit = best.length + 0.5f
    return dx * dx + dy * dy <= limit * limit
}

/** 候选路径的实测次数（真机排查用：一页引线慢的时候先看这个数）。 */
internal var noteArrowRouteTries: Int = 0

/**
 * 候选是否「进了自己的框」或「横穿自己锚点那一行」——兜底判据里排在最前面的两项，
 * 只看两个矩形就能算出来（[measureArrowRoute] 里是同一套 `insideSpan` 与零长段口径）。
 *
 * 返回位图：1 = 进了框，2 = 横穿自己那一行。
 */
private fun arrowEnterFlags(
    xs: FloatArray,
    ys: FloatArray,
    count: Int,
    bubble: Rect,
    own: Rect?,
): Int {
    var flags = 0
    for (i in 1 until count) {
        val dx = xs[i] - xs[i - 1]
        val dy = ys[i] - ys[i - 1]
        if (sqrt(dx * dx + dy * dy) < 0.01f) continue
        if (flags and 1 == 0 &&
            insideSpan(xs[i - 1], ys[i - 1], dx, dy, bubble, ARROW_OWN_BUBBLE_TOL_PX) > 1e-6f
        ) {
            flags = flags or 1
        }
        if (flags and 2 == 0 && own != null &&
            insideSpan(xs[i - 1], ys[i - 1], dx, dy, own, 0f) > 1e-6f
        ) {
            flags = flags or 2
        }
        if (flags == 3) return flags
    }
    return flags
}

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
    /** 离别人的框（图形 / 表格 / 别的批注框）多远以内算「贴着走」（见 [ArrowPath.grip]）。 */
    gripDistance: Float = 0f,
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
    /** 这条批注的布点方向：定形状名次。 */
    side: NoteSide? = null,
    /** 自己锚点那一行的墨迹：只能停在边上，不许横穿。 */
    own: Rect? = null,
    /** 端点突破了 0.5em 内缩（规则第 4 条）：判据里排在前面的项全打平时才让位。 */
    breakout: Boolean = false,
    /** 突破点是不是怼在边的两头（见 [ArrowEdgePoint.corner]）。 */
    corner: Boolean = false,
    /**
     * 末段短于这个长度时按第 11 条不画（0 = 这一族一律保留末折，见四角 L）。
     * 判形状时要把这一折扣掉：比的是画出来的形状（见 [ArrowPath.rawBends]）。
     */
    shortTail: Float = 0f,
): ArrowPath? {
    noteArrowRouteTries++
    if (count < 2) return null
    val rawBends = max(0, count - 2)
    // 形状名次 / 末段长度都是「开局就定死」的：量到一半就能拿它们跟现有最优比。
    val firstVertical = abs(xs[0] - xs[1]) < 0.01f
    val lastVertical = abs(xs[count - 2] - xs[count - 1]) < 0.01f
    val tailDx = xs[count - 1] - xs[count - 2]
    val tailDy = ys[count - 1] - ys[count - 2]
    val tailLen = sqrt(tailDx * tailDx + tailDy * tailDy)
    // 末折会被删掉：按删掉以后的段数与首末段方向判形状。
    val hideTail = shortTail > 0f && count >= 3 && lastVertical && tailLen < shortTail
    val bends = if (hideTail) rawBends - 1 else rawBends
    val shownLastVertical = if (hideTail) {
        abs(xs[count - 3] - xs[count - 2]) < 0.01f
    } else {
        lastVertical
    }
    val form = arrowForm(side, count - (if (hideTail) 1 else 0), firstVertical, shownLastVertical)
    var length = 0f
    var hits = 0f
    var crosses = 0
    var hug = 0f
    var grip = 0f
    var enters = false
    var entersInk = false
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
        if (own != null && insideSpan(ax, ay, dx, dy, own, 0f) > 1e-6f) entersInk = true
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
            if ((hugDistance > 0f || gripDistance > 0f) && crossesInterior && span <= 1e-6f) {
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
                if (hugDistance > 0f && d < hugDistance &&
                    (o.tol <= ARROW_GLYPH_TOL_PX + 1e-4f || o.band)
                ) {
                    // 划线带与字墨迹同一档：横着贴波浪线抹过去，和贴着字边一样难看
                    // （2026-10-31 真机「函数体」）。
                    hug += hugDistance - d
                }
                // 贴别人的框：不分文字 / 图形，只看它是不是「一块框」（见 [ArrowObstacle.box]）。
                if (gripDistance > 0f && d < gripDistance && o.box) {
                    grip += gripDistance - d
                }
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
            val worse = if (crosses > limitClear.crosses) {
                true
            } else if (crosses == limitClear.crosses) {
                when {
                    form != limitClear.form -> form > limitClear.form
                    bends != limitClear.bends -> bends > limitClear.bends
                    hits > limitClear.hits + 0.5f -> true
                    abs(hits - limitClear.hits) <= 0.5f && grip > limitClear.grip + 0.5f -> true
                    abs(hits - limitClear.hits) <= 0.5f && abs(grip - limitClear.grip) <= 0.5f &&
                        length > limitClear.length + 0.5f -> true
                    abs(hits - limitClear.hits) <= 0.5f && abs(grip - limitClear.grip) <= 0.5f &&
                        abs(length - limitClear.length) <= 0.5f &&
                        hug > limitClear.hug + 0.5f -> true
                    abs(hits - limitClear.hits) <= 0.5f && abs(grip - limitClear.grip) <= 0.5f &&
                        abs(length - limitClear.length) <= 0.5f &&
                        abs(hug - limitClear.hug) <= 0.5f && breakout && !limitClear.breakout -> true
                    else -> false
                }
            } else {
                false
            }
            if (worse) return null
        }
        if (limitFallback != null) {
            val worse = if (enters != limitFallback.entersBubble) {
                enters
            } else if (crosses > limitFallback.crosses) {
                true
            } else if (crosses == limitFallback.crosses) {
                when {
                    form != limitFallback.form -> form > limitFallback.form
                    hits > limitFallback.hits + 0.5f -> true
                    abs(hits - limitFallback.hits) <= 0.5f && length > limitFallback.length + 0.5f -> true
                    else -> false
                }
            } else {
                false
            }
            // 只有**已经确定要穿东西**的候选才归兜底那一档；通畅的候选走 best 那一档，
            // 拿兜底的形状名次去掐它会误杀（4 折但通畅的路，名次不如一条穿字的直线）。
            if (worse && (enters || hits > 1e-4f)) return null
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
        grip = grip,
        entersBubble = enters,
        entersInk = entersInk,
        order = order,
        form = form,
        tailLen = tailLen,
        breakout = breakout,
        corner = corner,
        rawBends = rawBends,
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

/**
 * 规则第 11 条：末段竖线短到画不下箭头（5dp 臂长）时，这一折不画——箭头落在**倒数第二段的
 * 末端**，方向随那一段（第 12 条）。只缩短、不越界：拐点本来就在原来那条折线上，
 * 不再把末段拉到选区边线（2026-10-28 定口）。
 *
 * **四个角不再例外**（2026-10-31 用户定口，撤销 2026-10-28 的「末折不得省略」）：斜向 L 的
 * 竖段只剩一两像素时，箭头按末段长度缩到零点几像素、等于没画，「保持 L」换来的是「看不见箭头」。
 * 现在四角与其他方向同口径：末折画不下就省掉，箭头落在倒数第二段末端。
 */
private fun ArrowPath.withoutUndrawableTail(lineH: Float): ArrowPath {
    if (!clear || points.size < 3) return this
    val end = points[points.size - 1]
    val bend = points[points.size - 2]
    if (abs(end.x - bend.x) >= 0.01f) return this
    if (tailLen >= max(lineH * NOTE_ARROW_UNDRAWABLE_LINES, 10f)) return this
    return copy(points = points.subList(0, points.size - 1).toList())
}

/**
 * 横着收尾的引线停在**最近那条选区边框**的外侧（2026-10-31 用户定口）。
 *
 * 末折被第 11 条省掉之后，剩下的横段是从「本来要往下扎的那一点」直接横过去的，落点常在选区
 * 中段——箭头压在选区底色（高亮）的正上方，读者既看不清箭头，也看不出它指的是哪条边
 * （真机「函数体」：一根横线一路伸到「数」和「体」中间，叠在高亮背景上）。
 * 横着进来的箭头只要走到最近的那条边框就够了：从右往左停在右边框外 [gap]，从左往右停在左边框外，
 * 不伸进选区的横向范围（那个范围在规则里是「行盒」，见第 9 条）。
 */
private fun ArrowPath.stopOutsideInk(ink: Rect, gap: Float): ArrowPath {
    if (points.size < 2) return this
    val end = points[points.size - 1]
    val prev = points[points.size - 2]
    // 只管横着收尾的那一段：竖着收尾的箭头本来就落在选区边上。
    if (abs(end.y - prev.y) >= 0.01f) return this
    val leftward = end.x < prev.x
    val stop = if (leftward) ink.right + gap else ink.left - gap
    if (if (leftward) end.x >= stop else end.x <= stop) return this
    // 收窄会把这最后一段压成零长甚至掉头（本来就已经贴在边框外），那就保持原样。
    if (if (leftward) stop >= prev.x - 1f else stop <= prev.x + 1f) return this
    val out = points.toMutableList()
    out[out.size - 1] = Offset(stop, end.y)
    return copy(points = out)
}

private fun arrowSegments(arrow: NoteArrow): List<Pair<Offset, Offset>> = when (arrow) {
    is NoteArrow.Straight -> listOf(arrow.start to arrow.end)
    is NoteArrow.Elbow -> arrow.points.zipWithNext()
    // 曲线没有折点，按弧长拉直成几条短弦：交叉判定 / 已画引线索引只认线段。
    is NoteArrow.Curve -> curvePoints(arrow).zipWithNext()
}

/** 曲线按 [ARROW_CURVE_SAMPLES] 等分取点（首尾都取，共 N+1 个）。 */private fun curvePoints(curve: NoteArrow.Curve, samples: Int = ARROW_CURVE_SAMPLES): List<Offset> =
    (0..samples).map { i -> curvePointAt(curve, i / samples.toFloat()) }

private fun curvePointAt(curve: NoteArrow.Curve, t: Float): Offset {
    val u = 1f - t
    val a = u * u * u
    val b = 3f * u * u * t
    val c = 3f * u * t * t
    val d = t * t * t
    return Offset(
        a * curve.start.x + b * curve.c1.x + c * curve.c2.x + d * curve.end.x,
        a * curve.start.y + b * curve.c1.y + c * curve.c2.y + d * curve.end.y,
    )
}

/**
 * 把一折的 L 收成一段三次曲线（只给**没有布点方向**的斜向引线用，2026-10-30 用户定口）。
 *
 * 形状：两端切线不动（规则第 5 条：起点垂直离开框、终点垂直扎向正文），中间朝 L 的
 * **拐点那一侧**鼓出去。那个拐点是布线绕障碍时挑出来的空走廊，所以「鼓向没有障碍的那一侧」
 * 与「鼓向拐点」是同一件事——另一侧就是框或者正文自己。
 *
 * 鼓出来压到字 / 框、或者压到已画的引线，就按 [ARROW_ARC_FALLOFFS] 一级级收小
 * （越收越贴近原来那条斜弦）；收不住返回 null，调用方照旧画 L。
 */
private fun arrowArcFromElbow(
    points: List<Offset>,
    em: Float,
    obstacles: List<ArrowObstacle>,
    drawn: DrawnSegmentIndex,
): NoteArrow.Curve? {
    if (points.size != 3) return null
    val a = points[0]
    val b = points[1]
    val c = points[2]
    val len1 = abs(b.x - a.x) + abs(b.y - a.y)
    val len2 = abs(c.x - b.x) + abs(c.y - b.y)
    // 两段都够长才值得换形状：短得像一条直线的 L，收圆看不出区别，反而容易蹭到旁边的字。
    val floorPx = if (em > 0f) em * 0.5f else 0f
    if (min(len1, len2) < floorPx) return null
    for (k in ARROW_ARC_FALLOFFS) {
        val curve = NoteArrow.Curve(
            start = a,
            c1 = Offset(a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k),
            c2 = Offset(c.x + (b.x - c.x) * k, c.y + (b.y - c.y) * k),
            end = c,
        )
        if (arcClear(curve, obstacles, drawn)) return curve
    }
    return null
}

/** 弧线采样成短弦后逐段验：既不穿字 / 框，也不和已画的引线交叉。 */
private fun arcClear(
    curve: NoteArrow.Curve,
    obstacles: List<ArrowObstacle>,
    drawn: DrawnSegmentIndex,
): Boolean {
    val pts = curvePoints(curve)
    for (i in 1 until pts.size) {
        val p = pts[i - 1]
        val q = pts[i]
        val dx = q.x - p.x
        val dy = q.y - p.y
        if (abs(dx) < 0.01f && abs(dy) < 0.01f) continue
        if (drawn.crossingsAll(p.x, p.y, q.x, q.y) > 0) return false
        for (o in obstacles) {
            if (insideSpan(p.x, p.y, dx, dy, o.rect, o.tol) > 1e-6f) return false
        }
    }
    return true
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

/**
 * 一页取一次实测尺子：同一套字号 / 字体规则下测量结果稳定，不必每条批注重建。
 *
 * @param fontFamily 批注字体（`null` = 跟随界面默认）。**必须**与 [NoteBubbles] 里那条
 *   `Text` 用同一个族：量宽和画字一旦不是同一套字形，摆位判「整句放得下」而画出来多折一行。
 */
@Composable
internal fun rememberNoteTextSizer(fontFamily: FontFamily? = null): NoteTextSizer {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val localStyle = LocalTextStyle.current
    val baseStyle = remember(localStyle, fontFamily) {
        if (fontFamily == null) localStyle else localStyle.merge(TextStyle(fontFamily = fontFamily))
    }
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
    /** 批注字体（`null` = 跟随界面默认）：与实测尺子同一个族，见 [rememberNoteTextSizer]。 */
    fontFamily: FontFamily? = null,
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
                    // 高度不另设上限：`maxLines` 已经把行数封顶，而这个上限是按**名义**行高
                    // （字号 × 1.38 = 55.15px）算的，排版却把每行高度向上取整到整像素（56px）——
                    // 2 行要 112px、上限只给 110，于是最后一行连同省略号一起被吃掉，
                    // 明明有空地也显示成「折了但没折完」（2026-10-29 真机定位）。
                    .clip(RoundedCornerShape(3.dp))
                    .background(tint.copy(alpha = 0.16f))
                    .border(1.dp, tint.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                    .clickable { onOpen(b.annotationId) },
            ) {
                Text(
                    text = b.text,
                    color = fgColor,
                    fontFamily = fontFamily,
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

/**
 * 箭头两臂的几何：臂根中心 + 法向半宽。
 *
 * **两臂必须整段落在末段以内**：末段只剩几个像素时还按死 5dp 画，两臂的根部就退到
 * 上一段上，看上去像「横线末端长了个竖箭头」（箭头和末端引线垂直，2026-10-25 用户第 3 条）。
 * 所以臂长夹在末段长度的 90% 以内，半宽随之等比缩小。
 */
internal fun noteArrowHead(end: Offset, tail: Offset, headPx: Float, halfPx: Float): Pair<Offset, Float> {
    val dx = end.x - tail.x
    val dy = end.y - tail.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 0.01f) return end to 0f
    val ux = dx / len
    val uy = dy / len
    val head = min(headPx, len * 0.9f)
    val half = min(halfPx, head * 0.6f)
    return Offset(end.x - ux * head, end.y - uy * head) to half
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
        is NoteArrow.Curve -> {
            path.moveTo(arrow.start.x, arrow.start.y)
            path.cubicTo(arrow.c1.x, arrow.c1.y, arrow.c2.x, arrow.c2.y, arrow.end.x, arrow.end.y)
            // 箭头方向取**终点切线**：曲线的末段就是 c2 → end 这一截。
            tail = arrow.c2
        }
    }
    drawPath(path, color = color, style = Stroke(width = stroke))

    // 箭头尖用两小段线，方向取末段。
    val (base, half) = noteArrowHead(arrow.end, tail, 5.dp.toPx(), 2.4.dp.toPx())
    if (half <= 0f) return
    val dx = arrow.end.x - tail.x
    val dy = arrow.end.y - tail.y
    val len = sqrt(dx * dx + dy * dy)
    val normal = Offset(-dy / len, dx / len)
    drawLine(color, base + normal * half, arrow.end, strokeWidth = stroke)
    drawLine(color, base - normal * half, arrow.end, strokeWidth = stroke)
}
