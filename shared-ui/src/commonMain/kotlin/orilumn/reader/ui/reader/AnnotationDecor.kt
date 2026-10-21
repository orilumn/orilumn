package orilumn.reader.ui.reader

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.engine.skia.DecorKind
import orilumn.reader.engine.skia.DecorRange
import orilumn.reader.engine.skia.DecorStroke

/**
 * 标注 → 渲染层装饰区间（用户层投影输入）。
 *
 * - 区间沿用标注的章内字符坐标，`[charStart, charEnd)`；
 * - `Note` 是 v2 批注，v1 不画（没有装饰形态）；
 * - 划线的线型：实线 / 虚线 / 波浪都画在基线下方，删除线画在字身中部（[DecorKind.STRIKETHROUGH]）；
 * - 颜色为空时落默认色槽，已有颜色按创建时的快照原样使用（改调色板不回溯）。
 */
internal fun annotationDecorRanges(annotations: List<Annotation>): List<DecorRange> =
    annotations.mapNotNull { a ->
        val kind = when (a.kind) {
            AnnotationKind.Highlight -> DecorKind.BACKGROUND
            AnnotationKind.Underline -> when (a.lineStyle) {
                AnnotationLineStyle.Strike -> DecorKind.STRIKETHROUGH
                else -> DecorKind.UNDERLINE
            }
            AnnotationKind.Note -> return@mapNotNull null
        }
        val stroke = when (a.lineStyle) {
            AnnotationLineStyle.Wavy -> DecorStroke.Wavy
            AnnotationLineStyle.Dashed -> DecorStroke.Dashed
            else -> DecorStroke.Solid
        }
        val color = a.color ?: when (kind) {
            DecorKind.BACKGROUND -> DEFAULT_HIGHLIGHT_ARGB
            else -> DEFAULT_UNDERLINE_ARGB
        }
        DecorRange(
            start = a.charStart,
            endExclusive = a.charEnd,
            kind = kind,
            argb = color,
            stroke = stroke,
        )
    }
