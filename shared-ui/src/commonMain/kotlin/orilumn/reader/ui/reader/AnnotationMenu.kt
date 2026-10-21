package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import kotlin.math.roundToInt

/** 浮动菜单与选区之间的间距。 */
private val ANNOTATION_MENU_GAP = 8.dp

/**
 * 选区浮动菜单（用户层，D-13 / §3.2）。
 *
 * 不用 `Popup`：阅读层至今零 Popup，菜单只是内容区上方的一层轻量覆盖物，
 * 跟着选区矩形定位；贴顶时自动翻到下方，左右贴边时向内收。
 *
 * v1 项：高亮 / 划线 / 复制 / 取消。点高亮或划线先展开该类色槽（D-21 / §5.9），
 * 再点一格即用**该格颜色**创建标注；默认格带金色描边，方便直接点第一格。
 */
@Composable
internal fun AnnotationMenu(
    anchor: Rect,
    bounds: Rect,
    palette: Palette,
    highlightColors: List<Int>,
    highlightDefaultSlot: Int,
    underlineColors: List<Int>,
    underlineDefaultSlot: Int,
    onHighlight: (Int) -> Unit,
    onUnderline: (Int) -> Unit,
    onCopy: () -> Unit,
    onCancel: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var expanded by remember { mutableStateOf<AnnotationKind?>(null) }
    val gap = with(LocalDensity.current) { ANNOTATION_MENU_GAP.roundToPx() }
    val offset = annotationMenuOffset(anchor, bounds, size, gap)
    val highlightDefaultSlotSafe = highlightDefaultSlot.coerceIn(0, highlightColors.lastIndex.coerceAtLeast(0))
    val underlineDefaultSlotSafe = underlineDefaultSlot.coerceIn(0, underlineColors.lastIndex.coerceAtLeast(0))
    // 菜单圆点只是类别标识：固定不透明，避免默认槽 alpha=0 时圆点消失。
    val highlightDefault = annotationWithAlpha(
        highlightColors.getOrElse(highlightDefaultSlotSafe) { DEFAULT_HIGHLIGHT_ARGB },
        255,
    )
    val underlineDefault = annotationWithAlpha(
        underlineColors.getOrElse(underlineDefaultSlotSafe) { DEFAULT_UNDERLINE_ARGB },
        255,
    )

    Box(modifier = Modifier.offset { offset }) {
        Column(
            modifier = Modifier
                .onSizeChanged { size = it }
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .background(palette.cardBg, RoundedCornerShape(12.dp))
                .border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp))
                .padding(vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MenuAction(
                    label = "高亮",
                    dotArgb = highlightDefault,
                    textColor = palette.text,
                    palette = palette,
                    active = expanded == AnnotationKind.Highlight,
                    onClick = {
                        expanded = if (expanded == AnnotationKind.Highlight) null else AnnotationKind.Highlight
                    },
                )
                MenuAction(
                    label = "划线",
                    dotArgb = underlineDefault,
                    textColor = palette.text,
                    palette = palette,
                    active = expanded == AnnotationKind.Underline,
                    onClick = {
                        expanded = if (expanded == AnnotationKind.Underline) null else AnnotationKind.Underline
                    },
                )
                MenuAction("复制", null, palette.text, palette, active = false, onClick = onCopy)
                MenuAction("取消", null, palette.muted, palette, active = false, onClick = onCancel)
            }
            when (expanded) {
                AnnotationKind.Highlight -> ColorSlotRow(
                    colors = highlightColors,
                    defaultSlot = highlightDefaultSlotSafe,
                    palette = palette,
                    onPick = onHighlight,
                )
                AnnotationKind.Underline -> ColorSlotRow(
                    colors = underlineColors,
                    defaultSlot = underlineDefaultSlotSafe,
                    palette = palette,
                    onPick = onUnderline,
                )
                AnnotationKind.Note, null -> Unit
            }
        }
    }
}

/**
 * 已有标注的编辑菜单（D-14）：改色 / 删除 / 取消。
 *
 * 与新建菜单同制：自绘覆盖层，不用 `Popup`；色槽来自该标注所属类别，
 * 当前色描一圈强调。改色或删除都由调用方先改内存再异步落库。
 */
@Composable
internal fun AnnotationEditMenu(
    annotation: Annotation,
    anchor: Rect,
    bounds: Rect,
    palette: Palette,
    colors: List<Int>,
    onPickColor: (Int) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // 删除不可撤销：第一次点只把按钮改成「确认删除」，第二次才真删。
    var confirmDelete by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { ANNOTATION_MENU_GAP.roundToPx() }
    val offset = annotationMenuOffset(anchor, bounds, size, gap)

    Box(modifier = Modifier.offset { offset }) {
        Column(
            modifier = Modifier
                .onSizeChanged { size = it }
                .shadow(8.dp, RoundedCornerShape(12.dp))
                .background(palette.cardBg, RoundedCornerShape(12.dp))
                .border(1.dp, palette.cardBorder, RoundedCornerShape(12.dp))
                .padding(vertical = 2.dp),
        ) {
            Text(
                "改颜色",
                color = palette.muted2,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
            )
            ColorSlotRow(
                colors = colors,
                defaultSlot = -1,
                palette = palette,
                currentArgb = annotation.color ?: annotationFallbackColor(annotation.kind),
                onPick = onPickColor,
            )
            Row(modifier = Modifier.padding(horizontal = 2.dp)) {
                MenuAction(
                    label = if (confirmDelete) "确认删除" else "删除",
                    dotArgb = null,
                    textColor = palette.text,
                    palette = palette,
                    active = confirmDelete,
                    onClick = {
                        if (confirmDelete) onDelete() else confirmDelete = true
                    },
                )
                MenuAction("取消", null, palette.muted, palette, active = false, onClick = onCancel)
            }
        }
    }
}

/** 展开后的色槽行：默认格描金边，编辑时当前色描强调边；点一格即用该格颜色。 */
@Composable
private fun ColorSlotRow(
    colors: List<Int>,
    defaultSlot: Int,
    palette: Palette,
    currentArgb: Int? = null,
    onPick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        colors.forEachIndexed { index, argb ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            val current = currentArgb != null && argb == currentArgb
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(Color(argb), CircleShape)
                    .border(
                        width = if (current) 3.dp else if (index == defaultSlot) 2.dp else 1.dp,
                        color = when {
                            current -> palette.text
                            index == defaultSlot -> palette.selBorder
                            else -> Color.White.copy(alpha = 0.85f)
                        },
                        shape = CircleShape,
                    )
                    .clickable { onPick(argb) },
            )
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
    palette: Palette,
    active: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .background(
                if (active) palette.rowActive else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
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
