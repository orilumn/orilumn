package orilumn.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import orilumn.reader.engine.paging.BookPageNumber

/**
 * 页脚页码（用户层「显示页码」开关的唯一渲染点）。
 *
 * ## 为什么是独立覆盖层，不画进页画布
 *
 *  - 页位图按（章 × 页 × 版式参数）缓存复用；页码是**全书口径**，换一章/换一版分页就变，
 *    打进位图等于往缓存键里塞一个纯 UI 值（还会让同一页在临时/正典切换时重栅一次）。
 *  - 页码要跟着页一起翻：放进 `FlipSlideLayer` 的两个槽里，与页画布共用同一个平移量，
 *    滑动时和正文一起走，而不是「字滑走了、页码原地不动」。
 *
 * ## 形态与落点
 *
 * 形态 `. 12 .`（点数夹页码，纸书页脚的老写法），落在**版心下方那条页边距**里、
 * **右下角**：水平右沿对齐正文右沿，竖直在页边距带里居中。
 * 全书总页数暂不显示（`BookPageNumber.total` 仍然保留，留给「要不要显示总数」的后续决定）。
 *
 * 字号取正文的 ~0.62，并夹在页边距高度的一半以内：读者把下边距调到很窄时先缩字号，
 * 缩到看不清就不画，绝不压到正文上。
 *
 * @param number 引擎给出的全书页码；null（本章还没有正典分页）时**整层不画**——宁可留白，
 *   也不显示一个会跳变的数。
 * @param contentBottomPx 版心下沿（页坐标 px）。
 * @param pageHeightPx 页高（px）。
 * @param bodyFontPx 正文字号（px），页脚据此缩字号。
 * @param inkColor 正文墨色；页脚用同一色降透明度，与主题/背景自动保持同一套配色。
 * @param endInsetPx 右侧内缩（px）：让页码右沿对齐正文右沿，而不是贴屏幕边。
 */
@Composable
internal fun PageNumberLabel(
    number: BookPageNumber?,
    contentBottomPx: Float,
    pageHeightPx: Float,
    bodyFontPx: Float,
    inkColor: Color,
    endInsetPx: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val index = number?.index ?: return
    val bandPx = (pageHeightPx - contentBottomPx).coerceAtLeast(0f)
    val fontPx = minOf(bodyFontPx * PAGE_NUMBER_SCALE, bandPx * 0.5f)
    if (fontPx < PAGE_NUMBER_MIN_PX) return
    val linePx = fontPx * 1.25f
    // 在页边距那条带里竖直居中：底对齐 + 下方留白把文字中心顶到带心。
    val bottomPx = ((bandPx - linePx) / 2f).coerceAtLeast(1f)
    val density = LocalDensity.current
    Box(modifier.fillMaxSize()) {
        Text(
            text = ". $index .",
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(
                    end = with(density) { endInsetPx.coerceAtLeast(0f).toDp() },
                    bottom = with(density) { bottomPx.toDp() },
                ),
            style = TextStyle(
                color = inkColor.copy(alpha = PAGE_NUMBER_ALPHA),
                // px → sp 要除以 fontScale（`toSp` 自带）：页脚跟正文同尺，不随系统字体缩放跳号。
                fontSize = with(density) { fontPx.toSp() },
                lineHeight = with(density) { linePx.toSp() },
                textAlign = TextAlign.End,
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 页脚占正文的比例：够看清又明显小于正文，不与正文抢注意力。 */
private const val PAGE_NUMBER_SCALE = 0.62f

/** 墨色透明度：比正文淡一档，像纸书页脚的灰。 */
private const val PAGE_NUMBER_ALPHA = 0.55f

/** 再小就不画了（px）。下边距被压到极限时页码自动消失，不压正文。 */
private const val PAGE_NUMBER_MIN_PX = 10f
