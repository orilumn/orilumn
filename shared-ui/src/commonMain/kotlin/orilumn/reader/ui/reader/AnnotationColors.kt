package orilumn.reader.ui.reader

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.data.settings.ReaderSettings
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

/**
 * 设置面板里可直接套用的基础颜色（不透明 RGB；套用时保留当前槽的透明度）。
 * 十格刚好一行，高亮与划线共用。
 */
internal val ANNOTATION_PRESET_COLORS: List<Int> = listOf(
    0xFFE53935.toInt(), // 红
    0xFFFB8C00.toInt(), // 橙
    0xFFFDD835.toInt(), // 黄
    0xFF43A047.toInt(), // 绿
    0xFF00897B.toInt(), // 青
    0xFF1E88E5.toInt(), // 蓝
    0xFF3949AB.toInt(), // 靛
    0xFF8E24AA.toInt(), // 紫
    0xFFD81B60.toInt(), // 粉
    0xFF6D4C41.toInt(), // 棕
)

/** 某一类标注的色槽列表；`Note` 没有装饰色槽，返回空表。 */
internal fun annotationColorSlots(settings: ReaderSettings, kind: AnnotationKind): List<Int> {
    val slots = when (kind) {
        AnnotationKind.Highlight -> settings.highlightColorSlots
        AnnotationKind.Underline -> settings.underlineColorSlots
        AnnotationKind.Note -> emptyList()
    }
    if (slots.isNotEmpty()) return slots
    return when (kind) {
        AnnotationKind.Highlight -> ReaderSettings.DEFAULT_HIGHLIGHT_COLOR_SLOTS
        AnnotationKind.Underline -> ReaderSettings.DEFAULT_UNDERLINE_COLOR_SLOTS
        AnnotationKind.Note -> emptyList()
    }
}

/** 某一类标注的默认色槽下标（越界时钳到有效范围）。 */
internal fun annotationDefaultSlot(settings: ReaderSettings, kind: AnnotationKind): Int {
    val slots = annotationColorSlots(settings, kind)
    if (slots.isEmpty()) return 0
    val raw = when (kind) {
        AnnotationKind.Highlight -> settings.highlightDefaultSlot
        AnnotationKind.Underline -> settings.underlineDefaultSlot
        AnnotationKind.Note -> 0
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
    AnnotationKind.Note -> 0
}

/**
 * 上次新建 / 改色用的类别（`ReaderSettings.annotationLastKind` 的解析）。
 *
 * 浮动菜单据此**一步到位**停在该类别的色槽行上；未知值（老存档缺键、手改配置）按高亮处理，
 * 批注类不是色槽类别，不会被选出来。
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
        AnnotationKind.Note -> settings
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
        AnnotationKind.Note -> settings
    }
}

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
