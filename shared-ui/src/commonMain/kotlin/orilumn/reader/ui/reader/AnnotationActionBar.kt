package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.data.correction.CorrectionCandidate
import orilumn.reader.data.correction.CorrectionRules
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
 * 单行时从左到右：`高亮` `划线` `修订`(占位) → 当前类别的 5 个色槽 → 3 个线型（仅划线）
 * → 贴右的 `复制` `删除` `确定`。装不下就换两行：第一行只放类别与右侧三键，第二行放色槽与线型；
 * 再装不下（窄手机）就三行：类别一行、色槽一行、线型一行。判定全部来自**实测宽度**，
 * 不看屏幕 dp 阈值。
 *
 * 正文修改模式下色槽整排隐藏，`修订` 右边那片空白用来放**纠错候选词**（见 [CorrectionRules]）：
 * 绿色文字、点一下即提交，不用再点 `确定`。
 *
 * 类别名本身就是样张：「高亮」二字垫当前高亮色，「划线」二字按当前**线型与线色**画一笔。
 * 线型行只画线不写字。点色槽 = 落笔（新建或改色）；点线型 = **只选中**，落笔交给色槽或 `确定`。
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
    /** 选区菜单的「修订」：有选区时可点（正文修改 v3）。 */
    onEdit: (() -> Unit)? = null,
    editEnabled: Boolean = false,
    /**
     * 纠错候选（修订模式，见 [CorrectionRules]）：词典命中**当前选区整段**时非空。
     *
     * 摆在「高亮 / 划线 / 修订」右边那片空白里（修订模式下色槽整排隐藏，那里本来就空着），
     * 绿色文字，**点一下就提交修订**，不用再点「确定」。长内容只显示首尾引号（`“…”`），
     * 提交的仍是完整文字（见 [CorrectionCandidate]）。
     */
    corrections: List<CorrectionCandidate> = emptyList(),
    onPickCorrection: (String) -> Unit = {},
    /**
     * 正文修改模式（方案 §8.8 / D-36）：**和高亮、划线共用同一根操作栏**，不另起一套。
     *
     * - 「修订」标为当前项（垫底、不再响应点按）；
     * - 左边的「高亮 / 划线」**照样可点**：点它就是切回该类别的批注（[onKindChange] 由调用方解释）；
     * - 色槽与线型虚掉（点按被吞掉）；
     * - 「删除」与批注同义，删的是**覆盖这段字的修订**（[canDelete] 由调用方按有没有修订给）；
     * - 「复制」「确定」照旧；取消仍是点卡片外 / 返回键 / Esc（落笔只认 `确定`）。
     */
    editMode: Boolean = false,
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
            // 正文修改模式下这两个**照样可点**：点它就是切回该类别的批注（用户定口）。
            // 只是「当前项」是右边的「修订」，所以两者都不垫底。
            MenuAction(
                label = "高亮",
                textColor = palette.text,
                palette = palette,
                active = !editMode && kind == AnnotationKind.Highlight,
                swatch = swatchOf(AnnotationKind.Highlight),
                onClick = { onKindChange(AnnotationKind.Highlight) },
            )
            MenuAction(
                label = "划线",
                textColor = palette.text,
                palette = palette,
                active = !editMode && kind == AnnotationKind.Underline,
                underlineArgb = underlineSwatch,
                underlineStyle = lineStyle,
                onClick = { onKindChange(AnnotationKind.Underline) },
            )
            // 正文修改（v3）：有选区才可点；点了把选区文字放进同一张底部卡片。
            // 进了修改模式后这一项就是**当前项**：垫底、不再响应点按（不会自己再点自己一次）。
            MenuAction(
                label = "修订",
                textColor = if (editMode || editEnabled) palette.text else palette.muted,
                palette = palette,
                active = editMode,
                enabled = !editMode && editEnabled && onEdit != null,
                onClick = { onEdit?.invoke() },
            )
        },
        // 正文修改模式不显示色槽（用户定口）：这一段直接不出现，操作栏少一截、也不留空位。
        colors = if (editMode) {
            null
        } else {
            {
                ColorSlots(
                    colors = colors,
                    defaultSlot = defaultSlot,
                    currentArgb = currentColor,
                    onPick = { index -> onPickColor(kind, index) },
                )
            }
        },
        styles = if (!editMode && kind == AnnotationKind.Underline) {
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
        // 纠错候选只在正文修改模式出现：那里没有色槽，`修订` 右边本来就是空的。
        corrections = if (editMode && corrections.isNotEmpty()) {
            { CorrectionRow(words = corrections, onPick = onPickCorrection) }
        } else {
            null
        },
        trail = {
            MenuAction("复制", palette.text, palette, active = false, onClick = onCopy)
            // 删除两种模式共用：批注模式删这条标注，正文修改模式删这条修改（恢复原文）。
            MenuAction(
                label = "删除",
                textColor = if (canDelete) palette.text else palette.muted,
                palette = palette,
                active = false,
                enabled = canDelete,
                onClick = onDelete,
            )
            MenuAction(
                label = "确定",
                textColor = palette.text,
                palette = palette,
                active = false,
                fontWeight = FontWeight.Bold,
                onClick = onDone,
            )
        },
    )
}

/* ---------------------------------------------------------------- 布局 */

private const val PART_LEAD = "lead"
private const val PART_COLORS = "colors"
private const val PART_STYLES = "styles"
private const val PART_CORRECTIONS = "corrections"
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
 *
 * 纠错候选（[PART_CORRECTIONS]）**紧跟 `修订` 右边、整段靠左**：它只出现在正文修改模式，
 * 那一行本来没有色槽，正好落在 `修订` 右边那片空白里。一行装不下时它让到下一行
 * （与色槽同一套让位规则），再窄就自己单独一行，仍然靠左。
 */
@Composable
private fun BarLayout(
    modifier: Modifier = Modifier,
    lead: @Composable () -> Unit,
    /** null = 这一段整段不出现（正文修改模式没有色槽）。 */
    colors: (@Composable () -> Unit)?,
    styles: (@Composable () -> Unit)?,
    /** null = 没有纠错候选（非修订模式，或选区不是词典里的词）。 */
    corrections: (@Composable () -> Unit)?,
    trail: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier,
        content = {
            Row(Modifier.layoutId(PART_LEAD), verticalAlignment = Alignment.CenterVertically) {
                lead()
            }
            if (colors != null) {
                Row(Modifier.layoutId(PART_COLORS), verticalAlignment = Alignment.CenterVertically) {
                    colors()
                }
            }
            if (styles != null) {
                Row(Modifier.layoutId(PART_STYLES), verticalAlignment = Alignment.CenterVertically) {
                    styles()
                }
            }
            if (corrections != null) {
                Row(
                    Modifier.layoutId(PART_CORRECTIONS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    corrections()
                }
            }
            Row(Modifier.layoutId(PART_TRAIL), verticalAlignment = Alignment.CenterVertically) {
                trail()
            }
        },
    ) { measurables, constraints ->
        // 候选词按可用宽度测量：三条候选（成对符号规则）在窄屏上换行，不会有一条被挤到屏幕外。
        fun part(id: String, maxWidth: Int = Constraints.Infinity) =
            measurables.firstOrNull { it.layoutId == id }?.measure(Constraints(maxWidth = maxWidth))
        val maxW = constraints.maxWidth
        val leadP = part(PART_LEAD)
        val colorsP = part(PART_COLORS)
        val stylesP = part(PART_STYLES)
        val corrP = part(PART_CORRECTIONS, maxWidth = maxW)
        val trailP = part(PART_TRAIL)
        val gap = BAR_GAP.roundToPx()
        val rowGap = BAR_ROW_GAP.roundToPx()

        fun w(p: androidx.compose.ui.layout.Placeable?) = p?.width ?: 0
        fun h(p: androidx.compose.ui.layout.Placeable?) = p?.height ?: 0

        val paletteW = w(colorsP) + if (stylesP != null) gap + w(stylesP) else 0
        // 候选与前面的色槽 / 类别之间也要留一格间距（有候选时才算这一格）。
        val corrW = if (corrP != null) gap + w(corrP) else 0
        val singleW = w(leadP) + gap + paletteW + corrW + gap + w(trailP)

        if (singleW <= maxW) {
            val rowH = maxOf(h(leadP), h(colorsP), h(stylesP), h(corrP), h(trailP))
            layout(maxW, rowH) {
                leadP?.placeRelative(0, (rowH - h(leadP)) / 2)
                var x = w(leadP) + gap
                colorsP?.placeRelative(x, (rowH - h(colorsP)) / 2)
                x += w(colorsP)
                if (stylesP != null) {
                    x += gap
                    stylesP.placeRelative(x, (rowH - h(stylesP)) / 2)
                    x += w(stylesP)
                }
                // 候选紧跟 `修订`（从左往右排），不贴右：读者先看到「换成什么」，再看右侧三键。
                if (corrP != null) {
                    x += gap
                    corrP.placeRelative(x, (rowH - h(corrP)) / 2)
                }
                trailP?.placeRelative(maxW - w(trailP), (rowH - h(trailP)) / 2)
            }
        } else {
            // 第二行放色槽 + 线型；候选跟着靠左，放得下就一起，放不下再单独一行。
            val corrOnSecond = corrP != null && paletteW + gap + w(corrP) <= maxW
            val secondH = maxOf(h(colorsP), h(stylesP), if (corrOnSecond) h(corrP) else 0)
            if (paletteW <= maxW) {
                val topH = maxOf(h(leadP), h(trailP))
                val corrRowH = if (corrP != null && !corrOnSecond) rowGap + h(corrP) else 0
                layout(maxW, topH + rowGap + secondH + corrRowH) {
                    leadP?.placeRelative(0, (topH - h(leadP)) / 2)
                    trailP?.placeRelative(maxW - w(trailP), (topH - h(trailP)) / 2)
                    colorsP?.placeRelative(0, topH + rowGap + (secondH - h(colorsP)) / 2)
                    if (stylesP != null) {
                        stylesP.placeRelative(
                            w(colorsP) + gap,
                            topH + rowGap + (secondH - h(stylesP)) / 2,
                        )
                    }
                    if (corrOnSecond) {
                        // 有色槽时接在色槽后面；修订模式没有色槽，就贴最左。
                        corrP?.placeRelative(
                            if (paletteW > 0) paletteW + gap else 0,
                            topH + rowGap + (secondH - h(corrP)) / 2,
                        )
                    } else if (corrP != null) {
                        corrP.placeRelative(0, topH + rowGap + secondH + rowGap)
                    }
                }
            } else {
                // 窄手机：色槽与线型各占一行（线型不存在时就只有色槽），候选再占一行。
                val topH = maxOf(h(leadP), h(trailP))
                val colorsH = h(colorsP)
                val stylesH = h(stylesP)
                val rowsH = topH + rowGap + colorsH +
                    (if (stylesP != null) rowGap + stylesH else 0)
                layout(maxW, rowsH + (if (corrP != null) rowGap + h(corrP) else 0)) {
                    leadP?.placeRelative(0, (topH - h(leadP)) / 2)
                    trailP?.placeRelative(maxW - w(trailP), (topH - h(trailP)) / 2)
                    colorsP?.placeRelative(0, topH + rowGap)
                    if (stylesP != null) {
                        stylesP.placeRelative(0, topH + rowGap + colorsH + rowGap)
                    }
                    if (corrP != null) {
                        corrP.placeRelative(0, rowsH + rowGap)
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- 色槽 / 线型 / 按钮 */

/**
 * 一排色槽：正在用的那一格在外面套**互补色 + 灰半透明**两圈，其余格只描一道白色细边；
 * 点一格即用该格颜色（回调带槽下标）。
 *
 * 「正在用」= 编辑时那条记录的颜色（能对上某一格时），否则是上次用的那一格。
 * 全栏只有这一种选中标记——不再有金边那种第二套画法。
 */
@Composable
private fun ColorSlots(
    colors: List<Int>,
    defaultSlot: Int,
    currentArgb: Int? = null,
    onPick: (Int) -> Unit,
) {
    val inUseSlot = colors.indexOfFirst { currentArgb != null && it == currentArgb }
        .takeIf { it >= 0 } ?: defaultSlot
    // 色槽不要点按水波纹：默认那层是方形、还会在圆外闪一下灰块。
    // 选中与否已经由双圈说清楚了，点下去不必再补一个动效。
    val noRipple = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        colors.forEachIndexed { index, argb ->
            if (index > 0) Spacer(Modifier.width(COLOR_SLOT_GAP))
            Box(
                modifier = Modifier
                    .size(COLOR_SLOT_BOX)
                    .inUseRing(argb, inUse = index == inUseSlot)
                    .clickable(interactionSource = noRipple, indication = null) { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(COLOR_SLOT_DOT)
                        .background(Color(argb), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
                )
            }
        }
    }
}

/**
 * 正在用的那一格：颜色圆外先一圈互补色、再一圈灰色半透明。
 *
 * 色槽本身常常很浅（高亮约 45% 透明度），单描一道边在浅色卡片上看不出来；互补色圈跟这格
 * 自己的颜色形成强对比，灰圈再把两圈与卡片底色隔开。不用金边，免得同一件事出现两种画法。
 */
private fun Modifier.inUseRing(argb: Int, inUse: Boolean): Modifier = drawBehind {
    if (!inUse) return@drawBehind
    val dotR = COLOR_SLOT_DOT.toPx() / 2f
    val complementW = COMPLEMENT_RING_WIDTH.toPx()
    val outerW = OUTER_RING_WIDTH.toPx()
    val complementR = dotR + complementW / 2f
    val outerR = complementR + complementW / 2f + outerW / 2f
    drawCircle(
        color = Color(annotationComplementRingColor(argb)),
        radius = complementR,
        style = Stroke(complementW),
    )
    drawCircle(
        color = SlotOuterRing,
        radius = outerR,
        style = Stroke(outerW),
    )
}

/** 色槽外框（含选中时外侧两圈的位置）；颜色圆本体见 [COLOR_SLOT_DOT]。 */
private val COLOR_SLOT_BOX = 32.dp

/** 色槽颜色圆本体：比外框小一圈，省出的空间给选中时的两圈。 */
private val COLOR_SLOT_DOT = 24.dp

/** 两个色槽之间的间距（外框之间；视觉上的圆间距 = 它 + 外框留白）。 */
private val COLOR_SLOT_GAP = 2.dp

/** 当前色外面的互补色圈线宽。 */
private val COMPLEMENT_RING_WIDTH = 1.5.dp

/** 最外侧那圈灰色半透明线宽。 */
private val OUTER_RING_WIDTH = 1.25.dp

/** 选中色槽最外侧的灰圈：把互补色圈与卡片底色隔开，深浅卡片上都留得住边界。 */
private val SlotOuterRing = Color(0xFF9A9A9A).copy(alpha = 0.45f)

/**
 * 划线线型行：只画线、不写字（实线 / 波浪 / 虚线），线色 = 当前划线色。
 * 选中的那条垫一层 [Palette.rowActive]：点它只是**选中**，落笔还要点色槽或 `确定`（D-28）。
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

/**
 * 纠错候选词（见 [CorrectionRules]）：绿色文字，**点一下就提交修订**（不用再点 `确定`）。
 *
 * 只出现在正文修改模式，紧跟在 `修订` 右边、整段靠左；绿色与审阅视图里「新写进去的字」
 * 同一支绿，读者一眼看出这是「改后」的样子。
 *
 * **一行放得下就显示完整候选；放不下才把中间内容收成中文省略号 `……`**（`“………”` / `‘……’` /
 * `……`）——提交的仍是完整文字。判定用实测文字宽度，不看字数：窄屏早收、宽屏晚收。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CorrectionRow(words: List<CorrectionCandidate>, onPick: (String) -> Unit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textStyle = remember { TextStyle(fontSize = MENU_LABEL_FONT, fontWeight = FontWeight.Medium) }
    BoxWithConstraints {
        // 完整候选 + 各自左右留白 + 格间距，超过一行可用宽度才收省略号。
        val collapsed = remember(words, constraints.maxWidth, density) {
            val gapPx = with(density) { CORRECTION_GAP.toPx() }
            val padPx = with(density) { CORRECTION_LABEL_PADDING.toPx() * 2 }
            var total = gapPx * (words.size - 1).coerceAtLeast(0)
            for (word in words) {
                total += measurer.measure(word.label, textStyle, maxLines = 1).size.width + padPx
            }
            total > constraints.maxWidth.toFloat()
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(CORRECTION_GAP),
            verticalArrangement = Arrangement.spacedBy(CORRECTION_GAP),
        ) {
            words.forEach { word ->
                Text(
                    text = if (collapsed) word.collapsedLabel else word.label,
                    color = Color(REVIEW_INSERTED_ARGB),
                    fontSize = MENU_LABEL_FONT,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = CORRECTION_LABEL_MAX_WIDTH)
                        .clickable { onPick(word.value) }
                        .padding(horizontal = CORRECTION_LABEL_PADDING, vertical = 7.dp),
                )
            }
        }
    }
}

/** 单条候选词的显示宽度上限（超长候选只影响显示，提交的仍是完整文字）。 */
private val CORRECTION_LABEL_MAX_WIDTH = 220.dp

/** 候选之间的间距（换行后纵向同值）。 */
private val CORRECTION_GAP = 2.dp

/** 候选左右留白（点击区域比字宽一圈）。 */
private val CORRECTION_LABEL_PADDING = 9.dp

/** 线型样张：与正文里落墨的位置对齐（线画在字的下沿）。 */
private fun DrawScope.drawLineStyleGlyph(
    style: AnnotationLineStyle,
    color: Color,
    /**
     * 线所在高度；`null` = 按样张行的默认（贴字的下沿 0.68）。
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
    // 一条线画在哪里：样张行里贴下沿；调用方也可显式指定（类别名样张用）。
    val y = lineY ?: size.height * 0.68f
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
    }
}

/** 类别名样张那条线的线心：贴字底并给波浪留出振幅。 */
private fun DrawScope.sampleLineY(style: AnnotationLineStyle, strokeWidth: Float): Float {
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
    /** 那条线**长什么样**：跟当前选定的线型走（实线 / 波浪 / 虚线），默认实线。 */
    underlineStyle: AnnotationLineStyle = AnnotationLineStyle.Straight,
    /** 字重：普通项用 Medium，最右的落笔键加粗。 */
    fontWeight: FontWeight = FontWeight.Medium,
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
            fontWeight = fontWeight,
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
