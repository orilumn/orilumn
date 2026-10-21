package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import kotlin.math.roundToInt

/** 浮动菜单与选区之间的间距。 */
private val ANNOTATION_MENU_GAP = 8.dp

/**
 * 标注浮动菜单（用户层，D-13 / D-14 / §3.2）。
 *
 * **一套菜单服务两种入口**：选区抬手（新建）与长按已有标注（编辑）用的是同一个弹窗。
 * 打开即**一步到位**：不先给一排按钮让你点「高亮 / 划线」，而是直接摊开**上次用过的类别**
 * 的色槽行（[initialKind]，编辑时用该标注的类别），点一格颜色就落笔。
 * 想换类别再点顶部的高亮 / 划线——所以按钮上的类别色小圆点已无意义（已去掉）。
 *
 * **类别名本身就是样张**：「高亮」二字垫当前高亮色，「划线」二字下沿画当前划线色，
 * 不用先点开才知道这一行长什么样。
 *
 * 划线色槽下面还有一行**线型**（实线 / 波浪 / 虚线 / 删除线），只画线不写字，
 * 线色跟当前划线色；点哪一条就是新建（或改型）用的线型。高亮没有线型，那一行不出现。
 *
 * 不用 `Popup`：阅读层至今零 Popup，菜单只是内容区上方的一层轻量覆盖物，跟着锚点矩形定位；
 * 贴顶时自动翻到下方，左右贴边时向内收。
 *
 * 项：高亮 / 划线（切类别）/ 复制 / 删除。没有「取消」：点菜单外面即关（遮罩/正文点按由调用方接）。
 * 删除只有存在可删记录（[deleteTarget] 非 null）时才亮，否则虚掉且点了没反应。
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
    initialKind: AnnotationKind,
    initialLineStyle: AnnotationLineStyle,
    editing: Annotation?,
    deleteTarget: Annotation?,
    onPickColor: (AnnotationKind, Int, AnnotationLineStyle) -> Unit,
    onPickLineStyle: (AnnotationLineStyle) -> Unit,
    onCopy: () -> Unit,
    onDelete: (Annotation) -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    // 展开哪个类别的色槽：编辑时先落到该条自己的类别，否则落到上次用过的类别。
    var kind by remember(editing?.id) {
        mutableStateOf(editing?.kind?.takeIf { it != AnnotationKind.Note } ?: initialKind)
    }
    // 线型：编辑划线时先落到该条自己的线型；新建、以及编辑高亮时用上次的
    // （高亮没有线型，那条记录上存的值不代表划线菜单的偏好，不能拿它当初始值）。
    var lineStyle by remember(editing?.id) {
        mutableStateOf(
            editing?.lineStyle?.takeIf { editing.kind == AnnotationKind.Underline } ?: initialLineStyle,
        )
    }
    val gap = with(LocalDensity.current) { ANNOTATION_MENU_GAP.roundToPx() }
    val offset = annotationMenuOffset(anchor, bounds, size, gap)
    val colors = if (kind == AnnotationKind.Underline) underlineColors else highlightColors
    val defaultSlot = (if (kind == AnnotationKind.Underline) underlineDefaultSlot else highlightDefaultSlot)
        .coerceIn(0, colors.lastIndex.coerceAtLeast(0))
    // 编辑中的记录用自身颜色描圈；只在它所属类别的色槽行里描。
    val editingColor = editing?.let { it.color ?: annotationFallbackColor(it.kind) }?.takeIf { editing.kind == kind }
    // 类别名的样张色：两类完全独立，各取本类默认槽（编辑本类记录时跟着它自己的颜色）。
    fun swatchOf(target: AnnotationKind): Int = annotationSwatchColor(
        highlightColors = highlightColors,
        highlightDefaultSlot = highlightDefaultSlot,
        underlineColors = underlineColors,
        underlineDefaultSlot = underlineDefaultSlot,
        kind = target,
        editing = editing,
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
                    textColor = palette.text,
                    palette = palette,
                    active = kind == AnnotationKind.Highlight,
                    swatch = swatchOf(AnnotationKind.Highlight),
                    onClick = { kind = AnnotationKind.Highlight },
                )
                MenuAction(
                    label = "划线",
                    textColor = palette.text,
                    palette = palette,
                    active = kind == AnnotationKind.Underline,
                    underlineArgb = swatchOf(AnnotationKind.Underline),
                    onClick = { kind = AnnotationKind.Underline },
                )
                MenuAction("复制", palette.text, palette, active = false, onClick = onCopy)
                MenuAction(
                    label = "删除",
                    textColor = if (deleteTarget != null) palette.text else palette.muted,
                    palette = palette,
                    active = false,
                    enabled = deleteTarget != null,
                    onClick = { deleteTarget?.let(onDelete) },
                )
            }
            ColorSlotRow(
                colors = colors,
                defaultSlot = defaultSlot,
                palette = palette,
                currentArgb = editingColor,
                bottomPadding = if (kind == AnnotationKind.Underline) 2.dp else 8.dp,
                onPick = { index -> onPickColor(kind, index, lineStyle) },
            )
            if (kind == AnnotationKind.Underline) {
                LineStyleRow(
                    current = lineStyle,
                    argb = swatchOf(AnnotationKind.Underline),
                    palette = palette,
                    onPick = {
                        lineStyle = it
                        onPickLineStyle(it)
                    },
                )
            }
        }
    }
}

/** 展开后的色槽行：默认格描金边，编辑时当前色描强调边；点一格即用该格颜色（回调带槽下标）。 */
@Composable
private fun ColorSlotRow(
    colors: List<Int>,
    defaultSlot: Int,
    palette: Palette,
    currentArgb: Int? = null,
    bottomPadding: Dp = 8.dp,
    onPick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = bottomPadding),
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
                    .clickable { onPick(index) },
            )
        }
    }
}

/**
 * 划线线型行：只画线、不写字（实线 / 波浪 / 虚线 / 删除线），线色 = 当前划线色。
 * 选中的那条垫一层 [Palette.rowActive]，与类别按钮的激活态同一套语汇。
 */
@Composable
private fun LineStyleRow(
    current: AnnotationLineStyle,
    argb: Int,
    palette: Palette,
    onPick: (AnnotationLineStyle) -> Unit,
) {
    val color = Color(argb)
    Row(
        modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnnotationLineStyle.entries.forEachIndexed { index, style ->
            if (index > 0) Spacer(Modifier.width(8.dp))
            val selected = style == current
            Box(
                modifier = Modifier
                    .size(width = 40.dp, height = 26.dp)
                    .background(
                        if (selected) palette.rowActive else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onPick(style) }
                    .drawBehind { drawLineStyleGlyph(style, color) },
            )
        }
    }
}

/** 线型样张：与正文里落墨的位置对齐（下划线靠下、删除线过中）。 */
private fun DrawScope.drawLineStyleGlyph(
    style: AnnotationLineStyle,
    color: Color,
) {
    val stroke = 2.dp.toPx()
    val from = size.width * 0.2f
    val to = size.width * 0.8f
    val underlineY = size.height * 0.68f
    val strikeY = size.height * 0.5f
    when (style) {
        AnnotationLineStyle.Straight -> drawLine(
            color = color,
            start = Offset(from, underlineY),
            end = Offset(to, underlineY),
            strokeWidth = stroke,
        )
        AnnotationLineStyle.Wavy -> {
            val amp = stroke * 1.2f
            val halfWave = (to - from) / 4f
            val path = Path().apply { moveTo(from, underlineY) }
            var x = from
            var up = true
            while (x < to) {
                val nx = (x + halfWave).coerceAtMost(to)
                path.quadraticTo((x + nx) / 2f, if (up) underlineY - amp else underlineY + amp, nx, underlineY)
                x = nx
                up = !up
            }
            drawPath(path, color, style = Stroke(width = stroke))
        }
        AnnotationLineStyle.Dashed -> {
            val dash = stroke * 2.5f
            val gap = stroke * 2f
            var x = from
            while (x < to) {
                drawLine(
                    color = color,
                    start = Offset(x, underlineY),
                    end = Offset((x + dash).coerceAtMost(to), underlineY),
                    strokeWidth = stroke,
                )
                x += dash + gap
            }
        }
        AnnotationLineStyle.Strike -> drawLine(
            color = color,
            start = Offset(from, strikeY),
            end = Offset(to, strikeY),
            strokeWidth = stroke,
        )
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
    textColor: Color,
    palette: Palette,
    active: Boolean,
    enabled: Boolean = true,
    /** 非空：给文字垫一块该颜色的底（高亮的样子）。 */
    swatch: Int? = null,
    /** 非空：在文字下沿画一条该颜色的线（划线的样子）。 */
    underlineArgb: Int? = null,
    onClick: () -> Unit,
) {
    // 虚掉的项仍是菜单的一部分：点它**吞掉**本次点按，只显「不可用」，
    // 不能漏到下面去关菜单或清选区（点了没反应 ≠ 菜单自己消失）。
    val eatenInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .background(
                if (active) palette.rowActive else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.clickable(interactionSource = eatenInteraction, indication = null) {}
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .then(
                    if (swatch != null) {
                        Modifier
                            .background(Color(swatch), RoundedCornerShape(4.dp))
                            .padding(horizontal = 3.dp, vertical = 1.dp)
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (underlineArgb != null) {
                        Modifier.drawBehind {
                            val stroke = 2.dp.toPx()
                            drawLine(
                                color = Color(underlineArgb),
                                start = Offset(0f, size.height - stroke / 2f),
                                end = Offset(size.width, size.height - stroke / 2f),
                                strokeWidth = stroke,
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
