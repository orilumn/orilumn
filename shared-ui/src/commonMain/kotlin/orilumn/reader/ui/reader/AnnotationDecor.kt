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
 * - 划线的线型：实线 / 虚线 / 波浪都画在基线下方，删除线画在字身中部（[DecorKind.STRIKETHROUGH]）；
 * - 颜色为空时落默认色槽，已有颜色按创建时的快照原样使用（改调色板不回溯）。
 *
 * 批注不是类型：它只是记录上附带的一段文字，墨迹仍按这条记录本来的类别画
 * （高亮垫底色 / 划线画卷线），文字由正文旁的旁注显示（见 `NoteBubbles`）。
 */
internal fun annotationDecorRanges(annotations: List<Annotation>): List<DecorRange> =
    annotations.map { a ->
        val kind = annotationDecorKind(a.kind, a.lineStyle)
        val stroke = annotationDecorStroke(a.lineStyle)
        val color = a.color ?: when (a.kind) {
            AnnotationKind.Highlight -> DEFAULT_HIGHLIGHT_ARGB
            AnnotationKind.Underline -> DEFAULT_UNDERLINE_ARGB
        }
        DecorRange(
            start = a.charStart,
            endExclusive = a.charEnd,
            kind = kind,
            argb = color,
            stroke = stroke,
        )
    }

/** 标注类别 + 线型 → 渲染层装饰类别（高亮垫底、划线 / 删除线画线）。 */
internal fun annotationDecorKind(kind: AnnotationKind, lineStyle: AnnotationLineStyle): DecorKind =
    when (kind) {
        AnnotationKind.Highlight -> DecorKind.BACKGROUND
        AnnotationKind.Underline -> when (lineStyle) {
            AnnotationLineStyle.Strike -> DecorKind.STRIKETHROUGH
            else -> DecorKind.UNDERLINE
        }
    }

/** 标注线型 → 渲染层描边（实线 / 虚线 / 波浪）。 */
internal fun annotationDecorStroke(lineStyle: AnnotationLineStyle): DecorStroke =
    when (lineStyle) {
        AnnotationLineStyle.Wavy -> DecorStroke.Wavy
        AnnotationLineStyle.Dashed -> DecorStroke.Dashed
        else -> DecorStroke.Solid
    }
