package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind

/**
 * 批注输入框要去写的那一段（用户层，D-25）。
 *
 * [editing] 非空 = 在给已有记录写 / 改批注（可能是批注本身，也可能是带批注的高亮划线）；
 * 为空 = 为当前选区新建一条批注。
 */
internal data class NoteEditorTarget(
    val chapter: Int,
    val charStart: Int,
    val charEnd: Int,
    val editing: Annotation?,
    /** 原文摘录（顶部引文）：新建时来自章节文本缓存，编辑时用记录里的快照。 */
    val quote: String?,
    /** 打开时选中的批注色槽（新建 = 上次的默认槽；编辑批注 = 它自己的颜色）。 */
    val initialSlot: Int,
)

/**
 * 批注输入框（用户层，v2 / D-25）：自绘覆盖层，与浮动菜单同制——**不用 `Popup`**。
 *
 * - 遮罩**只吞点按、不关闭**：刚写了一半的字不能被手一滑弄丢，退出只有「取消」/ 返回键 / Esc；
 * - 批注色槽在框里挑（批注是独立颜色体系，且新建时不经过浮动菜单的色槽行）；
 *   给已有高亮 / 划线补写批注时不显示色槽——那条墨迹的颜色是它自己的，不该被批注色改掉；
 * - 打开即聚焦，卡片贴上半屏（键盘在下方）；`imePadding` 把可用高度收到键盘上方，矮屏整卡可滚
 *   （Android 壳用 `adjustNothing`，键盘弹出**不改窗口尺寸**，否则阅读面会把「键盘顶起」
 *   当成视口变化去整书重排）。
 *
 * 保存语义由调用方定（新建 / 改文字 / 清空即删），本组件只回传文本与选中的色槽。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AnnotationNoteDialog(
    target: NoteEditorTarget,
    noteColors: List<Int>,
    showColorRow: Boolean,
    palette: Palette,
    onCancel: () -> Unit,
    onSave: (text: String, slot: Int) -> Unit,
) {
    val p = palette
    val initial = target.editing?.note.orEmpty()
    var value by remember(target) {
        mutableStateOf(TextFieldValue(initial, selection = TextRange(initial.length)))
    }
    var slot by remember(target) {
        mutableIntStateOf(target.initialSlot.coerceIn(0, noteColors.lastIndex.coerceAtLeast(0)))
    }
    val focus = remember { FocusRequester() }
    val scrimInteraction = remember { MutableInteractionSource() }

    // 打开即聚焦：移动端顺带把键盘顶起来（输入框的常态）。
    LaunchedEffect(target) { focus.requestFocus() }
    // Android 返回键 = 取消（桌面没有返回键，Esc 见下）。
    BackHandler(enabled = true) { onCancel() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().imePadding()) {
        // 卡片最多占「键盘上方可用高度」的九成：矮屏（横屏手机）里整卡可滚，按钮不会被挤出屏幕。
        val cardMaxHeight = maxHeight * 0.92f
        // 遮罩：只消费点按（点了不关，避免误触丢掉刚写的内容）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(interactionSource = scrimInteraction, indication = null) {},
        )
        Column(
            modifier = Modifier
                // 贴上半屏：键盘在下方，输入框不会被它盖住（旧系统拿不到键盘高度时也不会）。
                .align(Alignment.TopCenter)
                .padding(top = 12.dp, bottom = 12.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.9f)
                .shadow(10.dp, RoundedCornerShape(14.dp))
                .background(p.cardBg, RoundedCornerShape(14.dp))
                .border(1.dp, p.cardBorder, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .heightIn(max = cardMaxHeight)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onCancel()
                        true
                    } else {
                        false
                    }
                },
        ) {
            Text(
                // 给已有高亮 / 划线补文字时点明作用对象，新建与编辑批注都只叫「批注」。
                text = if (target.editing != null && target.editing.kind != AnnotationKind.Note) {
                    "给这条标注写批注"
                } else {
                    "批注"
                },
                color = p.text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val quote = target.quote?.trim()?.takeIf { it.isNotEmpty() }
            if (quote != null) {
                Text(
                    text = quote,
                    color = p.muted2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (showColorRow && noteColors.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    noteColors.forEachIndexed { index, argb ->
                        if (index > 0) Spacer(Modifier.width(8.dp))
                        val selected = index == slot
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(Color(argb), CircleShape)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) p.text else Color.White.copy(alpha = 0.85f),
                                    shape = CircleShape,
                                )
                                .clickable { slot = index },
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(p.selBg, RoundedCornerShape(8.dp))
                    .border(1.dp, p.cardBorder, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                if (value.text.isEmpty()) {
                    Text(
                        text = "写下这一段的批注…",
                        color = p.muted2,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = TextStyle(color = p.text, fontSize = 15.sp, lineHeight = 22.sp),
                    cursorBrush = SolidColor(p.text),
                )
            }
            Row(
                modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                val canSave = value.text.isNotBlank() || target.editing != null
                DialogAction(
                    label = "取消",
                    color = p.muted2,
                    enabled = true,
                    palette = p,
                    onClick = onCancel,
                )
                Spacer(Modifier.width(4.dp))
                DialogAction(
                    label = "保存",
                    color = if (canSave) PanelGold else p.muted2,
                    enabled = canSave,
                    palette = p,
                    onClick = { if (canSave) onSave(value.text, slot) },
                )
            }
            // 清空后保存 = 删掉这条批注（批注记录除文字外没有别的内容）；高亮 / 划线只去掉附带的批注。
            if (target.editing?.kind == AnnotationKind.Note) {
                Text(
                    text = "清空后保存 = 删除这条批注",
                    color = p.muted2,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 框内按钮：与浮动菜单同一种朴素文字按钮（无 Material 按钮的大圆角与填充）。 */
@Composable
private fun DialogAction(
    label: String,
    color: Color,
    enabled: Boolean,
    palette: Palette,
    onClick: () -> Unit,
) {
    val eaten = remember { MutableInteractionSource() }
    Text(
        text = label,
        color = color,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    // 虚掉的按钮吞掉点按：点了没反应 ≠ 弹窗自己消失。
                    Modifier.clickable(interactionSource = eaten, indication = null) {}
                },
            )
            .background(if (enabled) palette.rowActive else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
