package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.engine.skia.ANNOTATION_DASH_OFF_RATIO
import orilumn.reader.engine.skia.ANNOTATION_DASH_ON_RATIO
import orilumn.reader.engine.skia.ANNOTATION_UNDERLINE_THICKNESS_EM
import orilumn.reader.engine.skia.decorWaveGeometry

/**
 * 标注操作栏（用户层，D-28 第 1 部分）。
 *
 * 取代原来贴着选区浮动的 [AnnotationMenu]：**没有锚点、没有翻边、没有左右收边**，
 * 它就是键盘上沿卡片的第一段，卡片自己不乱跑，所以选区在页面哪儿都不用管。
 *
 * 单行时从左到右：`高亮` `划线` `编辑`(占位) → 当前类别的 5 个色槽 → 4 个线型（仅划线）
 * → 贴右的 `复制` `删除` `完成`。装不下就换两行：第一行只放类别与右侧三键，第二行放色槽与线型；
 * 再装不下（窄手机）就三行：类别一行、色槽一行、线型一行。判定全部来自**实测宽度**，
 * 不看屏幕 dp 阈值。
 *
 * 类别名本身就是样张：「高亮」二字垫当前高亮色，「划线」二字按当前**线型与线色**画一笔。
 * 线型行只画线不写字。点色槽 = 落笔（新建或改色）；点线型 = **只选中**，落笔交给色槽或 `完成`。
 */
@Composable
internal fun AnnotationActionBar(
    palette: Palette,
    kind: AnnotationKind,
    lineStyle: AnnotationLineStyle,
    highlightColors: List<Int>,
    highlightDefaultSlot: Int,
    underlineColors: List<Int>,
    underlineDefaultSlot: Int,
    /** 正在编辑的那条记录的颜色（与当前类别同类时才描圈，否则 null）。 */
    currentColor: Int?,
    canDelete: Boolean,
    onKindChange: (AnnotationKind) -> Unit,
    onPickColor: (AnnotationKind, Int) -> Unit,
    onPickLineStyle: (AnnotationLineStyle) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = if (kind == AnnotationKind.Underline) underlineColors else highlightColors
    val defaultSlot = (if (kind == AnnotationKind.Underline) underlineDefaultSlot else highlightDefaultSlot)
        .coerceIn(0, colors.lastIndex.coerceAtLeast(0))

    fun swatchOf(target: AnnotationKind): Int = annotationSwatchColor(
        highlightColors = highlightColors,
        highlightDefaultSlot = highlightDefaultSlot,
        underlineColors = underlineColors,
        underlineDefaultSlot = underlineDefaultSlot,
        kind = target,
        editing = null,
    )
    val underlineSwatch = swatchOf(AnnotationKind.Underline)

    BarLayout(
        modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
        lead = {
            MenuAction(
                label = "高亮",
                textColor = palette.text,
                palette = palette,
                active = kind == AnnotationKind.Highlight,
                swatch = swatchOf(AnnotationKind.Highlight),
                onClick = { onKindChange(AnnotationKind.Highlight) },
            )
            MenuAction(
                label = "划线",
                textColor = palette.text,
                palette = palette,
                active = kind == AnnotationKind.Underline,
                underlineArgb = underlineSwatch,
                underlineStyle = lineStyle,
                onClick = { onKindChange(AnnotationKind.Underline) },
            )
            // 占位：v3 的内容修改还没做，虚掉且点了没反应（吞掉点按，不漏到正文）。
            MenuAction(
                label = "编辑",
                textColor = palette.muted,
                palette = palette,
                active = false,
                enabled = false,
                onClick = {},
            )
        },
        colors = {
            ColorSlots(
                colors = colors,
                defaultSlot = defaultSlot,
                palette = palette,
                currentArgb = currentColor,
                onPick = { index -> onPickColor(kind, index) },
            )
        },
        styles = if (kind == AnnotationKind.Underline) {
            {
                LineStyleRow(
                    current = lineStyle,
                    argb = underlineSwatch,
                    palette = palette,
                    onPick = onPickLineStyle,
                )
            }
        } else {
            null
        },
        trail = {
            MenuAction("复制", palette.text, palette, active = false, onClick = onCopy)
            MenuAction(
                label = "删除",
                textColor = if (canDelete) palette.text else palette.muted,
                palette = palette,
                active = false,
                enabled = canDelete,
                onClick = onDelete,
            )
            MenuAction(
                label = "完成",
                textColor = PanelGold,
                palette = palette,
                active = false,
                onClick = onDone,
            )
        },
    )
}

/* ---------------------------------------------------------------- 布局 */

private const val PART_LEAD = "lead"
private const val PART_COLORS = "colors"
private const val PART_STYLES = "styles"
private const val PART_TRAIL = "trail"

/** 组与组之间的横向间距。 */
private val BAR_GAP = 6.dp

/** 两行（或三行）之间的一点点呼吸。 */
private val BAR_ROW_GAP = 2.dp

/**
 * 操作栏的三段式布局：一段装得下就一行；装不下让位成两行；色槽 + 线型那行还装不下就三行。
 *
 * 判定用**实测子项宽度**（`layoutId` 分组），所以同一个组件在平板、手机、桌面窄窗里都能
 * 自己找到合适的排法，不需要外部传屏幕宽度或 dp 阈值。
 */
@Composable
private fun BarLayout(
    modifier: Modifier = Modifier,
    lead: @Composable () -> Unit,
    colors: @Composable () -> Unit,
    styles: (@Composable () -> Unit)?,
    trail: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Row(Modifier.layoutId(PART_LEAD), verticalAlignment = Alignment.CenterVertically) {
                lead()
            }
            Row(Modifier.layoutId(PART_COLORS), verticalAlignment = Alignment.CenterVertically) {
                colors()
            }
            if (styles != null) {
                Row(Modifier.layoutId(PART_STYLES), verticalAlignment = Alignment.CenterVertically) {
                    styles()
                }
            }
            Row(Modifier.layoutId(PART_TRAIL), verticalAlignment = Alignment.CenterVertically) {
                trail()
            }
        },
    ) { measurables, constraints ->
        fun part(id: String) = measurables.firstOrNull { it.layoutId == id }?.measure(Constraints())
        val leadP = part(PART_LEAD)
        val colorsP = part(PART_COLORS)
        val stylesP = part(PART_STYLES)
        val trailP = part(PART_TRAIL)
        val gap = BAR_GAP.roundToPx()
        val rowGap = BAR_ROW_GAP.roundToPx()
        val maxW = constraints.maxWidth

        fun w(p: androidx.compose.ui.layout.Placeable?) = p?.width ?: 0
        fun h(p: androidx.compose.ui.layout.Placeable?) = p?.height ?: 0

        val paletteW = w(colorsP) + if (stylesP != null) gap + w(stylesP) else 0
        val singleW = w(leadP) + gap + paletteW + gap + w(trailP)

        if (singleW <= maxW) {
            val rowH = maxOf(h(leadP), h(colorsP), h(stylesP), h(trailP))
            layout(maxW, rowH) {
                leadP?.placeRelative(0, (rowH - h(leadP)) / 2)
                var x = w(leadP) + gap
                colorsP?.placeRelative(x, (rowH - h(colorsP)) / 2)
                x += w(colorsP)
                if (stylesP != null) {
                    x += gap
                    stylesP.placeRelative(x, (rowH - h(stylesP)) / 2)
                }
                trailP?.placeRelative(maxW - w(trailP), (rowH - h(trailP)) / 2)
            }
        } else if (paletteW <= maxW) {
            val topH = maxOf(h(leadP), h(trailP))
            val bottomH = maxOf(h(colorsP), h(stylesP))
            layout(maxW, topH + rowGap + bottomH) {
                leadP?.placeRelative(0, (topH - h(leadP)) / 2)
                trailP?.placeRelative(maxW - w(trailP), (topH - h(trailP)) / 2)
                colorsP?.placeRelative(0, topH + rowGap + (bottomH - h(colorsP)) / 2)
                if (stylesP != null) {
                    stylesP.placeRelative(
                        w(colorsP) + gap,
                        topH + rowGap + (bottomH - h(stylesP)) / 2,
                    )
                }
            }
        } else {
            // 窄手机：色槽与线型各占一行（线型不存在时就只有色槽）。
            val topH = maxOf(h(leadP), h(trailP))
            val colorsH = h(colorsP)
            val stylesH = h(stylesP)
            layout(maxW, topH + rowGap + colorsH + (if (stylesP != null) rowGap + stylesH else 0)) {
                leadP?.placeRelative(0, (topH - h(leadP)) / 2)
                trailP?.placeRelative(maxW - w(trailP), (topH - h(trailP)) / 2)
                colorsP?.placeRelative(0, topH + rowGap)
                if (stylesP != null) {
                    stylesP.placeRelative(0, topH + rowGap + colorsH + rowGap)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- 色槽 / 线型 / 按钮 */

/** 一排色槽：默认格描金边，编辑时当前色描强调边；点一格即用该格颜色（回调带槽下标）。 */
@Composable
private fun ColorSlots(
    colors: List<Int>,
    defaultSlot: Int,
    palette: Palette,
    currentArgb: Int? = null,
    onPick: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { index, argb ->
            if (index > 0) Spacer(Modifier.width(6.dp))
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
 * 选中的那条垫一层 [Palette.rowActive]：点它只是**选中**，落笔还要点色槽或 `完成`（D-28）。
 */
@Composable
private fun LineStyleRow(
    current: AnnotationLineStyle,
    argb: Int,
    palette: Palette,
    onPick: (AnnotationLineStyle) -> Unit,
) {
    val color = Color(argb)
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnnotationLineStyle.entries.forEachIndexed { index, style ->
            if (index > 0) Spacer(Modifier.width(6.dp))
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

/** 菜单按钮文字字号：线样张的粗细也按它算，样张才与真迹同比例。 */
private val MENU_LABEL_FONT = 14.sp

/** 线型样张：与正文里落墨的位置对齐（下划线靠下、删除线过中）。 */
private fun DrawScope.drawLineStyleGlyph(
    style: AnnotationLineStyle,
    color: Color,
    /**
     * 线所在高度；`null` = 按样张行的默认（删除线过字中 0.5、其余贴下沿 0.68）。
     *
     * 类别名「划线」的样张要把线画在**字的下沿**，故显式传入。
     */
    lineY: Float? = null,
    from: Float = size.width * 0.2f,
    to: Float = size.width * 0.8f,
    /*
     * 线宽取**正文落墨的规则**（[ANNOTATION_UNDERLINE_THICKNESS_EM] × 菜单字号）：
     * 波形（波长、振幅）都随线宽线性放大，线宽对了样张才跟真迹一个样。
     */
    strokeWidth: Float = MENU_LABEL_FONT.toPx() * ANNOTATION_UNDERLINE_THICKNESS_EM,
) {
    val stroke = strokeWidth
    // 一条线画在哪里：样张行里删除线过字中、其余贴下沿；调用方也可显式指定（类别名样张用）。
    val y = lineY
        ?: if (style == AnnotationLineStyle.Strike) size.height * 0.5f else size.height * 0.68f
    when (style) {
        AnnotationLineStyle.Straight -> drawLine(
            color = color,
            start = Offset(from, y),
            end = Offset(to, y),
            strokeWidth = stroke,
        )
        AnnotationLineStyle.Wavy -> {
            // 与正文落墨同一套波形几何：波长/控制点都随线宽走，两端各多画一个波长再裁回片段
            // （Chromium 的 overhang 手法），两端才不会露出半截波。
            val g = decorWaveGeometry(stroke)
            val path = Path().apply { moveTo(from - g.wavelength, y) }
            var x = from - g.wavelength
            val endX = to + g.wavelength
            while (x < endX) {
                val xe = x + g.wavelength
                val cx = x + g.wavelength * 0.5f
                path.cubicTo(cx, y + g.controlOffset, cx, y - g.controlOffset, xe, y)
                x = xe
            }
            clipRect(from, y - g.halfHeight, to, y + g.halfHeight) {
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Butt))
            }
        }
        AnnotationLineStyle.Dashed -> drawLine(
            color = color,
            start = Offset(from, y),
            end = Offset(to, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(
                floatArrayOf(
                    stroke * ANNOTATION_DASH_ON_RATIO,
                    stroke * ANNOTATION_DASH_OFF_RATIO,
                ),
                0f,
            ),
        )
        AnnotationLineStyle.Strike -> drawLine(
            color = color,
            start = Offset(from, y),
            end = Offset(to, y),
            strokeWidth = stroke,
        )
    }
}

/** 类别名样张那条线的线心：下划线贴字底并给波浪留出振幅，删除线过字中。 */
private fun DrawScope.sampleLineY(style: AnnotationLineStyle, strokeWidth: Float): Float {
    if (style == AnnotationLineStyle.Strike) return size.height * 0.5f
    val half = if (style == AnnotationLineStyle.Wavy) {
        decorWaveGeometry(strokeWidth).halfHeight
    } else {
        strokeWidth / 2f
    }
    return size.height - half
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
    /** 那条线**长什么样**：跟当前选定的线型走（实线/波浪/虚线/删除线），默认实线。 */
    underlineStyle: AnnotationLineStyle = AnnotationLineStyle.Straight,
    onClick: () -> Unit,
) {
    // 虚掉的项仍是操作栏的一部分：点它**吞掉**本次点按，只显「不可用」，
    // 不能漏到下面去关卡片或翻页（点了没反应 ≠ 卡片自己消失）。
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
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = MENU_LABEL_FONT,
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
                            // 样张就是「这一笔真落下去长什么样」：线型、线色、线宽都跟正文落墨同一套规则，
                            // 波形自然也与真迹同比例（波长/振幅都随线宽走）。
                            val strokeWidth = MENU_LABEL_FONT.toPx() * ANNOTATION_UNDERLINE_THICKNESS_EM
                            drawLineStyleGlyph(
                                style = underlineStyle,
                                color = Color(underlineArgb),
                                lineY = sampleLineY(underlineStyle, strokeWidth),
                                from = 0f,
                                to = size.width,
                                strokeWidth = strokeWidth,
                            )
                        }
                    } else {
                        Modifier
                    },
                ),
        )
    }
}
