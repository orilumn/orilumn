package orilumn.reader.ui.reader

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** 下拉最多把页面拉下整页高度的 1/4。 */
internal const val BookmarkPullMaxFraction = 0.25f

/** 书签图案的边长、距屏幕左边与上沿的距离（用户指定的左上角位置，不随标题栏移动）。 */
internal val BookmarkMarkSize = 36.dp
internal val BookmarkMarkStart = 16.dp
internal val BookmarkMarkTop = 12.dp

private val BookmarkMarkRed = Color(0xFFD32F2F)
private val BookmarkMarkGray = Color(0xFF9E9E9E)

/**
 * 页面左上角的书签图案（用户层）。
 *
 * 有书签的页面平时显示红色；下拉时图案留在原处，页面从它下面让开。
 * 页面让过图案（[armPx]）之后：
 *  - 本来没有书签 → 图案转红，松手添加；
 *  - 本来有书签 → 图案转灰，松手删除。
 *
 * 没有书签的页面平时不画，下拉才随页面让开淡入。
 */
@Composable
internal fun BookmarkPullIndicator(
    pullPx: () -> Float,
    maxPullPx: Float,
    /** 页首越过图案下沿所需的偏移（px）。 */
    armPx: Float,
    /** 当前页已有书签 = true，没有 = false，还在读 = null。 */
    bookmarked: Boolean?,
    /** 翻页/封面等页面不在静置态时整块不画，免得图案浮在滑动中的两页上。 */
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    val px = pullPx()
    if (px <= 0.5f && bookmarked != true) return
    val progress = if (maxPullPx > 0f) (px / maxPullPx).coerceIn(0f, 1f) else 0f
    val armed = px >= armPx
    val red = when (bookmarked) {
        true -> !armed
        false -> armed
        null -> false
    }
    // 有书签的页面常驻显示，不随下拉淡入；没有书签的页面跟着下拉淡入/回弹淡出。
    val alpha = if (bookmarked == true) 1f else progress
    val scale = if (bookmarked == true) 1f else 0.86f + 0.14f * progress
    Icon(
        imageVector = BarIcons.BookmarkStar,
        contentDescription = null,
        tint = if (red) BookmarkMarkRed else BookmarkMarkGray,
        modifier = modifier
            .alpha(alpha)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .size(BookmarkMarkSize),
    )
}
