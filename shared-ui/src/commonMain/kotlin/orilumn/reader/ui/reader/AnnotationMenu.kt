package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 浮动菜单与选区之间的间距。 */
private val ANNOTATION_MENU_GAP = 8.dp

/**
 * 选区浮动菜单（用户层，D-13 / §3.2）。
 *
 * 不用 `Popup`：阅读层至今零 Popup，菜单只是内容区上方的一层轻量覆盖物，
 * 跟着选区矩形定位；贴顶时自动翻到下方，左右贴边时向内收。
 *
 * v1 项：高亮 / 划线 / 复制 / 取消。高亮/划线用默认色快照创建标注，
 * 颜色槽（D-21 / §5.9）在第 8 步接入后只改这里传入的色值。
 */
@Composable
internal fun AnnotationMenu(
    anchor: Rect,
    bounds: Rect,
    palette: Palette,
    onHighlight: () -> Unit,
    onUnderline: () -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val gap = with(LocalDensity.current) { ANNOTATION_MENU_GAP.roundToPx() }
    val offset = annotationMenuOffset(anchor, bounds, size, gap)

    Box(modifier = Modifier.offset { offset }) {
        Row(
            modifier = Modifier
                .onSizeChanged { size = it }
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .background(palette.cardBg, RoundedCornerShape(12.dp))
                .border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuAction("高亮", DEFAULT_HIGHLIGHT_ARGB, palette.text, onHighlight)
            MenuAction("划线", DEFAULT_UNDERLINE_ARGB, palette.text, onUnderline)
            MenuAction("复制", null, palette.text, onCopy)
            MenuAction("取消", null, palette.muted, onCancel)
        }
    }
}

/**
 * 菜单左上角位置（px）：优先放在选区上方，贴顶翻到下方；左右不越出内容区。
 * 抽成纯函数以便单测，避免「贴边飞出屏幕」只能靠真机发现。
 */
internal fun annotationMenuOffset(
    anchor: Rect,
    bounds: Rect,
    size: IntSize,
    gap: Int,
): IntOffset {
    val x = (anchor.center.x - size.width / 2f).roundToInt().coerceIn(
        bounds.left.roundToInt(),
        (bounds.right - size.width).roundToInt().coerceAtLeast(bounds.left.roundToInt()),
    )
    val above = anchor.top - gap - size.height
    val maxY = (bounds.bottom - size.height).coerceAtLeast(bounds.top)
    val y = if (above >= bounds.top) {
        above
    } else {
        (anchor.bottom + gap).coerceAtMost(maxY)
    }.roundToInt()
    return IntOffset(x, y)
}

@Composable
private fun MenuAction(
    label: String,
    dotArgb: Int?,
    textColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dotArgb != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(Color(dotArgb), CircleShape),
            )
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
