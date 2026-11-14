package orilumn.reader.ui.reader

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 下拉最多把页面拉下整页高度的 1/4。 */
internal const val BookmarkPullMaxFraction = 0.25f

/**
 * 书签图案的宽高、距屏幕右边与上沿的距离（用户指定的右上角位置，不随标题栏移动）。
 *
 * 高度是原图案可见部分的两倍；上沿贴住屏幕顶边，所以 [BookmarkMarkTop] 为 0。
 */
internal val BookmarkMarkWidth = 36.dp
internal val BookmarkMarkHeight = 54.dp
internal val BookmarkMarkEnd = 16.dp
internal val BookmarkMarkTop = 0.dp

private val BookmarkMarkRed = Color(0xFFD32F2F)
private val BookmarkMarkGray = Color(0xFF9E9E9E)

/**
 * 实心长书签：内部整片红色，只在偏上位置镂空一颗五角星。
 *
 * 24×36 的视口与 36×54 的落点同比例（2:3），星形不会被拉扁。
 * 外轮廓是一条从顶边垂下的长条，底端留 V 形缺口；星形作为第二个子路径，
 * 用 EvenOdd 从实心区域内挖掉。
 */
internal val BookmarkRibbon: ImageVector = ImageVector.Builder(
    name = "BookmarkRibbon",
    defaultWidth = 24.dp,
    defaultHeight = 36.dp,
    viewportWidth = 24f,
    viewportHeight = 36f,
).apply {
    addPath(
        pathData = addPathNodes(
            "M5,0H19V36L12,31.5L5,36Z" +
                "M12,4.5L13.03,7.58L16.28,7.61L13.66,9.54L14.65,12.64L12,10.75" +
                "L9.35,12.64L10.34,9.54L7.72,7.61L10.97,7.58Z",
        ),
        pathFillType = PathFillType.EvenOdd,
        fill = SolidColor(Color.Black),
    )
}.build()

/**
 * 页面右上角的书签图案（用户层）。
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
        imageVector = BookmarkRibbon,
        contentDescription = null,
        tint = if (red) BookmarkMarkRed else BookmarkMarkGray,
        modifier = modifier
            .alpha(alpha)
            .graphicsLayer {
                // 从顶边向下长出来：缩放时上沿始终贴着屏幕顶边。
                transformOrigin = TransformOrigin(0.5f, 0f)
                scaleX = scale
                scaleY = scale
            }
            .size(BookmarkMarkWidth, BookmarkMarkHeight),
    )
}
