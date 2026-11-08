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

/** 审阅视图的改动色：删除用红、新增用绿（2026-11-08 用户定口）。 */
internal const val REVIEW_DELETED_ARGB: Int = 0xFFE53935.toInt()
internal const val REVIEW_INSERTED_ARGB: Int = 0xFF2E7D32.toInt()

/**
 * 审阅视图的改动痕迹 → 渲染层装饰区间（用户层投影输入）。
 *
 * 删除：整段原文换成**红字 + 红色删除线**（原文在审阅空间里仍排出来，所以划得着）；
 * 新增：新文字换成**绿字**。不画框 —— 痕迹只落在字上（字色 + 删除线），版面不多一圈墨。
 * 两条都走 [DecorKind.TEXT_COLOR]（改字色）/ [DecorKind.STRIKETHROUGH]（划线），不改字形与断行。
 * 区间由宿主给出，已经是审阅空间坐标。
 */
internal fun reviewDecorRanges(marks: List<ReviewMark>): List<DecorRange> {
    if (marks.isEmpty()) return emptyList()
    val out = ArrayList<DecorRange>(marks.size * 2)
    for (m in marks) {
        if (m.endExclusive <= m.start) continue
        // 优先级压过标注（标注是 0）：审阅痕迹得压在读者自己的高亮/划线之上，不被糊住。
        if (m.deleted) {
            out += DecorRange(
                start = m.start,
                endExclusive = m.endExclusive,
                kind = DecorKind.STRIKETHROUGH,
                argb = REVIEW_DELETED_ARGB,
                priority = 1,
            )
            out += DecorRange(
                start = m.start,
                endExclusive = m.endExclusive,
                kind = DecorKind.TEXT_COLOR,
                argb = REVIEW_DELETED_ARGB,
                priority = 1,
            )
        } else {
            out += DecorRange(
                start = m.start,
                endExclusive = m.endExclusive,
                kind = DecorKind.TEXT_COLOR,
                argb = REVIEW_INSERTED_ARGB,
                priority = 1,
            )
        }
    }
    return out
}
