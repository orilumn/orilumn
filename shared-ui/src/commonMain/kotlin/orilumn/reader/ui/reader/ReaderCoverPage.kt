package orilumn.reader.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale

/**
 * 阅读器封面页（用户层前置页）：只读展示，不进分页/存档/目录。
 *
 * - 拉伸全屏（默认，`coverProportional=false`）：`FillBounds` 铺满内容区（可能变形，
 *   与旧 `PageRenderer.drawCover(proportional=false)` 同语义）；
 * - 等比（`coverProportional=true`）：`Fit` 居中，周围露底色（旧 `proportional=true` 同语义）。
 */
@Composable
fun ReaderCoverPage(
    cover: ImageBitmap,
    proportional: Boolean,
    bgColor: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(bgColor), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Image(
            bitmap = cover,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = if (proportional) ContentScale.Fit else ContentScale.FillBounds,
        )
    }
}
