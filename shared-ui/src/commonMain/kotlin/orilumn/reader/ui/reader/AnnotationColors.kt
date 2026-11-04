package orilumn.reader.ui.reader

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.data.settings.ReaderSettings
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 标注色槽的读写辅助（用户层，D-13 / D-21）。
 *
 * 颜色是**创建当时的 ARGB 快照**：这里只负责把 `ReaderSettings` 里的色槽交给
 * 新建标注 / 浮动菜单 / 设置面板，不参与 CSS 级联，也不进排版指纹。
 * 改色槽不会回溯已有标注（墨迹已干）。
 */

/** 高亮默认色槽（旧记录没有颜色时的兜底，也是默认色槽第一格）。 */
internal val DEFAULT_HIGHLIGHT_ARGB: Int = ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS[0]

/** 划线默认色槽（旧记录没有颜色时的兜底，也是默认色槽第一格）。 */
internal val DEFAULT_UNDERLINE_ARGB: Int = ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS[0]

/** 某一类标注的色槽列表（高亮 / 划线各一组，互不共用）。 */
internal fun annotationColorSlots(settings: ReaderSettings, kind: AnnotationKind): List<Int> {
    val slots = when (kind) {
        AnnotationKind.Highlight -> settings.highlightColorSlots
        AnnotationKind.Underline -> settings.underlineColorSlots
    }
    if (slots.isNotEmpty()) return slots
    return when (kind) {
        AnnotationKind.Highlight -> ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS
        AnnotationKind.Underline -> ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS
    }
}

/** 某一类标注的默认色槽下标（越界时钳到有效范围）。 */
internal fun annotationDefaultSlot(settings: ReaderSettings, kind: AnnotationKind): Int {
    val slots = annotationColorSlots(settings, kind)
    if (slots.isEmpty()) return 0
    val raw = when (kind) {
        AnnotationKind.Highlight -> settings.highlightDefaultSlot
        AnnotationKind.Underline -> settings.underlineDefaultSlot
    }
    return raw.coerceIn(0, slots.lastIndex)
}

/** 某一类标注的默认色（新建标注用）。 */
internal fun annotationDefaultColor(settings: ReaderSettings, kind: AnnotationKind): Int {
    val slots = annotationColorSlots(settings, kind)
    if (slots.isEmpty()) return annotationFallbackColor(kind)
    return slots[annotationDefaultSlot(settings, kind)]
}

/** 取指定色槽的颜色；越界回退到该类默认色。 */
internal fun annotationSlotColor(settings: ReaderSettings, kind: AnnotationKind, index: Int): Int {
    val slots = annotationColorSlots(settings, kind)
    if (slots.isEmpty()) return annotationFallbackColor(kind)
    return slots.getOrElse(index) { slots[annotationDefaultSlot(settings, kind)] }
}

/** 记录没有颜色时的兜底色（只在旧数据/异常数据上出现）。 */
internal fun annotationFallbackColor(kind: AnnotationKind): Int = when (kind) {
    AnnotationKind.Highlight -> DEFAULT_HIGHLIGHT_ARGB
    AnnotationKind.Underline -> DEFAULT_UNDERLINE_ARGB
}

/**
 * 上次新建 / 改色用的类别（`ReaderSettings.annotationLastKind` 的解析）。
 *
 * 浮动菜单据此**一步到位**停在该类别的色槽行上；未知值（老存档缺键、手改配置）按高亮处理。
 */
internal fun annotationLastKindOf(settings: ReaderSettings): AnnotationKind =
    when (settings.annotationLastKind.trim().lowercase()) {
        AnnotationKind.Underline.name.lowercase() -> AnnotationKind.Underline
        else -> AnnotationKind.Highlight
    }

/**
 * 上次新建 / 改线型用的划线线型（`ReaderSettings.annotationLastLineStyle` 的解析）。
 *
 * 未知值（老存档缺键、手改配置）按实线处理；高亮不读这个值。
 */
internal fun annotationLastLineStyleOf(settings: ReaderSettings): AnnotationLineStyle =
    AnnotationLineStyle.entries.firstOrNull {
        it.wire == settings.annotationLastLineStyle.trim().lowercase()
    } ?: AnnotationLineStyle.Straight

/**
 * 菜单里「高亮 / 划线」两个类别名各自的样张颜色（纯函数，便于单测）。
 *
 * **两个类别完全独立**：样张只取本类别的默认色槽；正在编辑的记录属于本类别时，
 * 跟着记录自己的颜色走。编辑另一类记录不影响本类的样张（否则切换类别看一眼就串色）。
 */
internal fun annotationSwatchColor(
    highlightColors: List<Int>,
    highlightDefaultSlot: Int,
    underlineColors: List<Int>,
    underlineDefaultSlot: Int,
    kind: AnnotationKind,
    editing: Annotation?,
): Int {
    if (editing != null && editing.kind == kind) {
        return editing.color ?: annotationFallbackColor(kind)
    }
    val colors = if (kind == AnnotationKind.Underline) underlineColors else highlightColors
    if (colors.isEmpty()) return annotationFallbackColor(kind)
    val slot = if (kind == AnnotationKind.Underline) underlineDefaultSlot else highlightDefaultSlot
    return colors[slot.coerceIn(0, colors.lastIndex)]
}

/**
 * 由某条记录改型成划线时用的**墨色**（纯函数，便于单测）。
 *
 * 高亮与划线是两套完全独立的色槽：高亮那半透明的底色绝不能当作墨线颜色带过去。
 * 所以只有本来就是划线的记录才保留自己原来的颜色，其余一律取划线类别的默认色。
 */
internal fun annotationUnderlineInkColor(editing: Annotation?, settings: ReaderSettings): Int =
    editing?.color?.takeIf { editing.kind == AnnotationKind.Underline }
        ?: annotationDefaultColor(settings, AnnotationKind.Underline)

/**
 * 由某条记录改型成高亮时用的**底色**（[annotationUnderlineInkColor] 的反向）。
 *
 * 同样是两套独立色槽：划线的墨色不透明，直接当高亮底色会糊住正文，
 * 所以只有本来就是高亮的记录才保留自己原来的颜色，其余一律取高亮类别的默认色。
 */
internal fun annotationHighlightFillColor(editing: Annotation?, settings: ReaderSettings): Int =
    editing?.color?.takeIf { editing.kind == AnnotationKind.Highlight }
        ?: annotationDefaultColor(settings, AnnotationKind.Highlight)

/**
 * 记录上该存哪种线型：**线型只属于划线**。
 *
 * 高亮侧一律存实线（不画线，仅作占位），免得高亮那行的点按把划线菜单记住的线型带歪，
 * 也免得旧记录上无意义的线型被当成划线的初始值。
 */
internal fun annotationLineStyleFor(
    kind: AnnotationKind,
    style: AnnotationLineStyle,
): AnnotationLineStyle = if (kind == AnnotationKind.Underline) style else AnnotationLineStyle.Straight

/**
 * 编辑一条已有标注之后该写成什么样；`null` = 一个字都没改（调用方不必写盘）。
 *
 * 操作栏里「类别 / 线型 / 色槽」都只是**选中**，真正写回只在这里发生一次：
 * - 换类别（高亮 ⇄ 划线）算改样式：记录必须真的换类别，否则点「高亮」只是把高亮的颜色
 *   当成划线的墨色画一条线（2026-11-02 反馈的正是这个）；
 * - 换类别时颜色一律换成**目标类别**的默认色（两套色槽完全独立：半透明的底色不能当墨线，
 *   不透明的墨色也不能糊成底色）；
 * - 刚点过色槽就用那一格的颜色（那是读者的明确意图）。
 */
internal fun annotationAfterEdit(
    current: Annotation,
    kind: AnnotationKind,
    lineStyle: AnnotationLineStyle,
    pickedColor: Int?,
    note: String?,
    settings: ReaderSettings,
    /** 编辑时读者拖手柄改过的范围；不传 = 保持原范围。 */
    charStart: Int = current.charStart,
    charEnd: Int = current.charEnd,
    /** 范围变了要同步的摘录；不传 = 保持原摘录。 */
    quote: String? = current.quote,
): Annotation? {
    val kindChanged = kind != current.kind
    val reline = kind == AnnotationKind.Underline && current.lineStyle != lineStyle
    val restyled = kindChanged || reline
    val recolor = pickedColor != null &&
        pickedColor != (current.color ?: annotationFallbackColor(current.kind))
    val rangeChanged = charStart != current.charStart || charEnd != current.charEnd
    if (!restyled && !recolor && note == current.note && !rangeChanged) return null
    return current.copy(
        charStart = charStart,
        charEnd = charEnd,
        kind = kind,
        lineStyle = annotationLineStyleFor(kind, if (restyled) lineStyle else current.lineStyle),
        color = when {
            pickedColor != null -> pickedColor
            kindChanged -> when (kind) {
                AnnotationKind.Highlight -> annotationHighlightFillColor(current, settings)
                AnnotationKind.Underline -> annotationUnderlineInkColor(current, settings)
            }
            restyled -> annotationUnderlineInkColor(current, settings)
            else -> current.color
        },
        note = note,
        quote = quote,
    )
}

/**
 * 记住「上次用的线型」，同时把**上次用的类别**定为划线。
 *
 * 线型只属于划线：读者点线型就是一次划线动作，所以下次打开菜单要停回划线，
 * 而不是停在早先用过的高亮上（否则「点线型直接落笔」这条最常用路径永远记不住类别）。
 */
internal fun withAnnotationLastLineStyle(
    settings: ReaderSettings,
    style: AnnotationLineStyle,
): ReaderSettings = settings.copy(
    annotationLastKind = AnnotationKind.Underline.name.lowercase(),
    annotationLastLineStyle = style.wire,
)

/** 改某一格颜色（保留其它槽与默认槽）。 */
internal fun withAnnotationColorSlot(
    settings: ReaderSettings,
    kind: AnnotationKind,
    index: Int,
    color: Int,
): ReaderSettings {
    val slots = annotationColorSlots(settings, kind).toMutableList()
    if (slots.isEmpty()) return settings
    slots[index.coerceIn(0, slots.lastIndex)] = color
    return when (kind) {
        AnnotationKind.Highlight -> settings.copy(highlightColorSlots = slots)
        AnnotationKind.Underline -> settings.copy(underlineColorSlots = slots)
    }
}

/** 改默认色槽下标（越界钳制）。 */
internal fun withAnnotationDefaultSlot(
    settings: ReaderSettings,
    kind: AnnotationKind,
    index: Int,
): ReaderSettings {
    val slots = annotationColorSlots(settings, kind)
    if (slots.isEmpty()) return settings
    val safe = index.coerceIn(0, slots.lastIndex)
    return when (kind) {
        AnnotationKind.Highlight -> settings.copy(highlightDefaultSlot = safe)
        AnnotationKind.Underline -> settings.copy(underlineDefaultSlot = safe)
    }
}

/**
 * 两类标注的色槽恢复出厂值（笔记页的「恢复默认颜色池」）。
 *
 * 只动十格颜色，不动默认格下标：新建标注用哪一格不归笔记页管（由浮动菜单决定），
 * 这一页也不显示金点。
 */
internal fun withDefaultAnnotationColorSlots(settings: ReaderSettings): ReaderSettings = settings.copy(
    highlightColorSlots = ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS,
    underlineColorSlots = ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS,
)

/** 取 ARGB 的 alpha 通道 0..255。 */
internal fun annotationAlpha(argb: Int): Int = (argb ushr 24) and 0xFF

/** 取 ARGB 的 alpha 百分比 0..100（滑块用）。 */
internal fun annotationAlphaPercent(argb: Int): Float = annotationAlpha(argb) * 100f / 255f

/** 百分比 0..100 → alpha 0..255（四舍五入并钳制）。 */
internal fun annotationAlphaFromPercent(percent: Float): Int =
    (percent.coerceIn(0f, 100f) * 255f / 100f).roundToInt().coerceIn(0, 255)

/** 只换 alpha，保留 RGB。 */
internal fun annotationWithAlpha(argb: Int, alpha: Int): Int =
    (argb and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

/** 只换 RGB，保留 alpha。 */
internal fun annotationWithRgb(argb: Int, rgb: Int): Int =
    (argb and 0xFF000000.toInt()) or (rgb and 0x00FFFFFF)

/** 红色通道 0..255。 */
internal fun annotationRed(argb: Int): Int = (argb ushr 16) and 0xFF

/** 绿色通道 0..255。 */
internal fun annotationGreen(argb: Int): Int = (argb ushr 8) and 0xFF

/** 蓝色通道 0..255。 */
internal fun annotationBlue(argb: Int): Int = argb and 0xFF

/** 只换某一个 RGB 通道（[shift] = 16/8/0），保留 alpha 与其它通道。 */
internal fun annotationWithChannel(argb: Int, shift: Int, value: Int): Int {
    val v = value.coerceIn(0, 255) shl shift
    val mask = 0xFF shl shift
    return (argb and mask.inv()) or v
}

/**
 * 当前选中色槽外面那圈**互补色**（纯函数，便于单测）。
 *
 * 操作栏要回答「现在用的是哪一格」，可色槽本身往往很浅（高亮约 45% 透明度），
 * 在浅色卡片上单描一道边看不出来。圈取该色在 HSV 里转 180° 的色相，并把饱和度提到
 * 不透明的浓色：浅琥珀配明显的蓝、深藏青配亮黄，圈自己就能跟色槽拉开。
 * 透明度不参与——圈要实心才压得住底下的卡片。
 */
internal fun annotationComplementRingColor(argb: Int): Int {
    val r = annotationRed(argb) / 255f
    val g = annotationGreen(argb) / 255f
    val b = annotationBlue(argb) / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    // RGB → HSV 的色相；灰色没有色相，按 0° 处理（转 180° 后是青色）。
    val hue = when {
        delta <= 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    var complementHue = (hue + 180f) % 360f
    if (complementHue < 0f) complementHue += 360f
    // 提饱和到浓色；浅色槽（高亮）压到 0.85 免得刺眼，深色槽（划线墨色）留 1.0 提亮。
    val s = 0.90f
    val v = if (max >= 0.55f) 0.85f else 1f
    val c = v * s
    val hh = complementHue / 60f
    val x = c * (1f - abs(hh % 2f - 1f))
    val m = v - c
    val (r1, g1, b1) = when {
        hh < 1f -> Triple(c, x, 0f)
        hh < 2f -> Triple(x, c, 0f)
        hh < 3f -> Triple(0f, c, x)
        hh < 4f -> Triple(0f, x, c)
        hh < 5f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(value: Float): Int = ((value + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r1) shl 16) or (channel(g1) shl 8) or channel(b1)
}
