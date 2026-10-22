package orilumn.reader.ui.reader

import kotlin.math.roundToInt
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineHitTest
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage

/**
 * 章节绝对 Y → 页面坐标的位移（用户层几何单源）。
 *
 * 画布、链接点按、选区覆盖层、手柄、放大镜都必须走这一口：锚点取本页行/图最小者，
 * 再上抬页首缺口；盒背景不参与锚点（理由见 [ReaderMath.pageAnchorY]）。
 *
 * @return 页坐标系位移；全空（连盒背景都没有）返回 null。
 */
internal fun readerPageShift(
    lines: List<DrawLine>,
    pageImages: List<PageImage>?,
    pageBackgrounds: List<PageBackground>?,
    frameLead: Int,
    contentTop: Float,
): Int? {
    val anchor = ReaderMath.pageAnchorY(
        lineMin = lines.minOfOrNull { it.yTop },
        imgMin = pageImages?.minOfOrNull { it.yTop },
        bgMin = pageBackgrounds?.minOfOrNull { it.yTop },
        frameLead = frameLead,
    ) ?: return null
    return anchor - contentTop.roundToInt()
}

/**
 * 屏幕坐标 → 章内字符偏移（D-7 / §5.2）。
 *
 * 三步全部复用现成几何：`tapLineAt` 找行 → `LineHitTest` 找行内字 → `charBase` 换章内偏移。
 * `x` 已是屏幕坐标，内部减内容区左缘；`y` 保持屏幕坐标（与 [ReaderMath.tapLineAt] 同式）。
 */
internal fun readerCharOffsetAt(
    lines: List<DrawLine>,
    shift: Int,
    contentLeft: Float,
    xPx: Float,
    yPx: Float,
): Int? {
    val (line, xInParagraph, yInLine) =
        ReaderMath.tapLineAt(lines, shift, xPx - contentLeft, yPx) ?: return null
    val offset = LineHitTest.hit(line, xInParagraph, yInLine) ?: return null
    return line.charBase + offset
}
