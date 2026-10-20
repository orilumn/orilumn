package orilumn.reader.ui.reader

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.engine.skia.DecorKind
import orilumn.reader.engine.skia.DecorRange

/**
 * 高亮 / 划线的兜底色（ARGB，含透明度）。真正的色槽在 `ReaderSettings`（§5.9）落地后由用户覆盖；
 * 这里只保证「记录里没颜色」时仍可读，且新建标注应写入当时解析后的颜色快照。
 */
internal const val DEFAULT_HIGHLIGHT_ARGB: Int = 0x73FFD54F // 45% 琥珀
internal const val DEFAULT_UNDERLINE_ARGB: Int = 0xFFE53935.toInt() // 不透明红

/**
 * 标注 → 渲染层装饰区间（用户层投影输入）。
 *
 * - 区间沿用标注的章内字符坐标，`[charStart, charEnd)`；
 * - `Note` 是 v2 批注，v1 不画（没有装饰形态）；
 * - 颜色为空时落默认色槽，已有颜色按创建时的快照原样使用（改调色板不回溯）。
 */
internal fun annotationDecorRanges(annotations: List<Annotation>): List<DecorRange> =
    annotations.mapNotNull { a ->
        val kind = when (a.kind) {
            AnnotationKind.Highlight -> DecorKind.BACKGROUND
            AnnotationKind.Underline -> DecorKind.UNDERLINE
            AnnotationKind.Note -> return@mapNotNull null
        }
        val color = a.color ?: when (kind) {
            DecorKind.BACKGROUND -> DEFAULT_HIGHLIGHT_ARGB
            DecorKind.UNDERLINE -> DEFAULT_UNDERLINE_ARGB
        }
        DecorRange(
            start = a.charStart,
            endExclusive = a.charEnd,
            kind = kind,
            argb = color,
        )
    }
