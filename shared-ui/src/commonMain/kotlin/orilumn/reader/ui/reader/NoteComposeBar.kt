package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle

/**
 * 标注卡片当前写的是哪一条（用户层，D-27）。
 *
 * 批注**不是标注类型**，而是高亮 / 划线记录上附带的一段文字（`note` 非空即「带批注」）。
 * 所以这里只带记录 id 与它的墨色：回写时按 id 找当前记录，颜色 / 类型 / 线型一概不动。
 *
 * [annotationId] 为 null = **还没落笔**：读者刚选好文字、卡片弹出来，
 * 点色槽或 `确定` 才按当前类别 / 线型 / 默认色新建一条（有字就一起写进去）。
 */
internal data class NoteBarTarget(
    val annotationId: Long?,
    val accentArgb: Int,
)

/**
 * 标注卡片（用户层，D-28 第 1 部分）：**贴着键盘上沿的一张卡片**，桌面没有键盘就贴窗口下边框。
 *
 * 自上而下两段：
 * 1. [AnnotationActionBar]：`高亮` `划线` `编辑`(占位) + 色槽 + 线型 + `复制` `删除` `确定`；
 *    装不下自己换两行 / 三行（实测宽度决定）。
 * 2. 输入行：只有输入框，**原来的「完成」按钮已经去掉**，提交统一走操作栏最右的 `确定`。
 *
 * 取代了原来贴着选区浮动的菜单：卡片不跟锚点走，所以选区在页面任何位置都不会「菜单跑出屏幕」，
 * 也不需要估算菜单高度。
 *
 * 落笔**只有**操作栏的 `确定` 一条路（D-28）。点卡片外的正文、返回键、Esc 都是**取消**
 * （[onCancel]）：写了一半的字与刚选的颜色一并不算数，原记录不动。
 *
 * 打字时正文仍看得见：卡片只占屏幕底部一条，不压暗（批注要一边看原文一边写）。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AnnotationSheet(
    target: NoteBarTarget,
    text: String,
    palette: Palette,
    kind: AnnotationKind,
    lineStyle: AnnotationLineStyle,
    highlightColors: List<Int>,
    highlightDefaultSlot: Int,
    underlineColors: List<Int>,
    underlineDefaultSlot: Int,
    /** 正在编辑的那条记录的颜色（与当前类别同类时才描圈）。 */
    currentColor: Int?,
    canDelete: Boolean,
    onTextChange: (String) -> Unit,
    onKindChange: (AnnotationKind) -> Unit,
    onPickColor: (AnnotationKind, Int) -> Unit,
    onPickLineStyle: (AnnotationLineStyle) -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    /** 操作栏的 `确定`：显式落笔（新建时即便没写字也按当前类别 / 线型 / 默认色落一条）。 */
    onDone: () -> Unit,
    /** 点卡片外 / 返回键 / Esc：**取消**（什么都不写，原记录不动；落笔只认 `确定`）。 */
    onCancel: () -> Unit,
    /** 汇报卡片自身高度（像素）：页面让位要用实测值，不能靠估。 */
    onHeightChange: (Int) -> Unit = {},
) {
    val p = palette
    // 输入框内部要光标与选区，所以持 TextFieldValue；外面只认纯文本（点色槽时要读它）。
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (text != value.text) value = TextFieldValue(text, TextRange(text.length))
    }
    val focus = remember { FocusRequester() }

    // 打开即聚焦：移动端顺带把键盘顶起来，卡片自己贴到键盘上沿（imePadding）。
    LaunchedEffect(target.annotationId) { focus.requestFocus() }
    // Android 返回键 / 桌面 Esc = 取消（不是「保存后退出」，落笔只认完成与点卡片外）。
    BackHandler(enabled = true) { onCancel() }

    Box(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { onHeightChange(it.height) }
                .background(p.cardBg)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                        onCancel()
                        true
                    } else {
                        false
                    }
                },
        ) {
            AnnotationActionBar(
                palette = p,
                kind = kind,
                lineStyle = lineStyle,
                highlightColors = highlightColors,
                highlightDefaultSlot = highlightDefaultSlot,
                underlineColors = underlineColors,
                underlineDefaultSlot = underlineDefaultSlot,
                currentColor = currentColor,
                canDelete = canDelete,
                onKindChange = onKindChange,
                onPickColor = onPickColor,
                onPickLineStyle = onPickLineStyle,
                onCopy = onCopy,
                onDelete = onDelete,
                onDone = onDone,
            )
            // 与输入行之间的分隔：两段同属一张卡片，但操作与打字是两件事。
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 1.dp)
                    .background(p.cardBorder),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 34.dp)
                            .background(p.selBg, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                    ) {
                        if (value.text.isEmpty()) {
                            Text(
                                text = "写点批注（可不填）",
                                color = p.muted2,
                                fontSize = 14.sp,
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = {
                                value = it
                                onTextChange(it.text)
                            },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            textStyle = TextStyle(color = p.text, fontSize = 14.sp, lineHeight = 20.sp),
                            cursorBrush = SolidColor(Color(target.accentArgb)),
                            maxLines = 4,
                        )
                    }
                }
                // 这里不再有第二个「完成」：提交统一走操作栏右侧那一个（D-28）。
                Spacer(Modifier.width(2.dp))
            }
            // 键盘之外的最后一段留白：贴边太紧时手指不好点。
            Spacer(Modifier.height(4.dp))
        }
    }
}
