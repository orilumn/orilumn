package orilumn.reader.ui.reader

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import kotlin.math.roundToInt

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
 *
 * **进出跟着键盘走**（D-30）：卡片自己不吃 `imePadding` 的系统 insets——那套 insets 只在键盘动画
 * **结束**时才跳到终值，卡片会先弹在屏幕底下、等键盘升完再跳上去（读者看到的「先菜单、再键盘」）。
 * 竖向位置由两个逐帧值合成：`−[imePx]（键盘高度）+ 卡片高 ×（1 − [reveal]）`。
 * [reveal] 为 0 时整张卡片都在屏幕下沿之外，随键盘升起一点点冒头（下半截始终压在键盘下面），
 * 看上去是「卡片 + 键盘」一整块从底下推上来；键盘落回去时 likewise 被键盘一口吞掉。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun AnnotationSheet(
    /** 卡片是否该在场：收起动画期间仍是 false，但内容要继续留着（不能中途换内容）。 */
    visible: Boolean,
    /** 键盘高度（像素，逐帧动画值）：卡片就贴它的上沿。0 = 没有软键盘（贴窗口下边）。 */
    imePx: Float,
    /** 卡片自己的升起度（0..1，见 [rememberSheetReveal]）：0 = 整卡在屏幕下沿之外。 */
    reveal: Float,
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
    /** 放卡片的位置（外层 Box 的底边对齐）；默认铺满宽度、按 Box 的默认对齐。 */
    modifier: Modifier = Modifier,
) {
    val p = palette
    // 输入框内部要光标与选区，所以持 TextFieldValue；外面只认纯文本（点色槽时要读它）。
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (text != value.text) value = TextFieldValue(text, TextRange(text.length))
    }
    val focus = remember { FocusRequester() }

    // 光标自己画（桌面复报：系统光标太浅，像没有光标）：
    //  - 系统光标在桌面只有 1dp（`DefaultCursorThickness` 的 desktop actual），细线抗锯齿后发虚；
    //  - 系统光标闪烁是 500ms 的透明度渐变，一半时间停在半透明上，看着更浅。
    // 这里把系统光标设成透明，按文本布局算出的光标位置画一条 2dp 竖线，只做硬开关闪烁。
    // 内容超过 4 行时字段内部会滚动，滚动位移拿不到，这时退回系统光标（位置一定对，只是细）。
    var textLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldHeightPx by remember { mutableStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    var caretOn by remember { mutableStateOf(true) }
    val layout = textLayout
    val selfCaret = layout == null || layout.size.height <= fieldHeightPx
    LaunchedEffect(visible, value.text, value.selection) {
        caretOn = true
        while (true) {
            delay(CARET_BLINK_MS)
            caretOn = !caretOn
        }
    }

    // 打开即聚焦：移动端顺带把键盘顶起来，卡片自己贴到键盘上沿（imePx 逐帧跟随）。
    // 用 visible 当键：收起动画还在跑时卡片没卸载，光靠 annotationId 不会重新请求焦点。
    LaunchedEffect(visible, target.annotationId) { if (visible) focus.requestFocus() }
    // Android 返回键 / 桌面 Esc = 取消（不是「保存后退出」，落笔只认确定与点卡片外）。
    BackHandler(enabled = visible) { onCancel() }

    // 卡片位置 = 屏幕底 − 键盘高 + 卡片高 × (1 − 升起度)：升起度 0 时整张卡片在屏幕下沿之外，
    // 跟着键盘一帧一帧冒出来。位移在 layout 阶段算（这两个值每帧都变），不触发重组。
    var cardHeightPx by remember { mutableStateOf(0) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged {
                cardHeightPx = it.height
                onHeightChange(it.height)
            }
            .offset {
                IntOffset(0, (-imePx + cardHeightPx * (1f - reveal)).roundToInt())
            }
            // 收起时先淡掉（卡面内容在这一刻已经清了，别让读者看见一张空卡往下滑）；
            // 还没量到高度的那一帧卡片会暂时落在屏幕底边（位移要用实测高度算），也先藏起来。
            .graphicsLayer {
                alpha = when {
                    cardHeightPx == 0 -> 0f
                    visible -> 1f
                    else -> reveal.coerceIn(0f, 1f)
                }
            }
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
                        // 提示字必须和输入框用同一套行高：外面套着 MaterialTheme，
                        // 只写 fontSize 会继承 bodyLarge 的 24sp 行高，空框比有字时高 4dp
                        // （用户复报：一输入框子就变矮），卡片高度跟着跳、页面也跟着让位一次。
                        Text(
                            text = "写点批注（可不填）",
                            style = NoteTextStyle,
                            color = p.muted2,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = {
                            value = it
                            onTextChange(it.text)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus)
                            .onFocusChanged { focused = it.isFocused }
                            .onSizeChanged { fieldHeightPx = it.height },
                        textStyle = NoteTextStyle.copy(color = p.text),
                        // 光标用正文墨色，不用批注色：高亮色槽是 45% 透明度的荧光笔底色（`0x73…`），
                        // 拿它画光标压在卡片的浅底上几乎看不见。
                        cursorBrush = if (selfCaret) SolidColor(Color.Transparent) else SolidColor(p.text),
                        onTextLayout = { textLayout = it },
                        maxLines = NOTE_MAX_LINES,
                    )
                    // 自绘光标：只有「光标处在这一版里」时才画（选择一段文字时系统本来也不画光标）。
                    if (selfCaret && focused && value.selection.collapsed) {
                        Canvas(Modifier.matchParentSize()) {
                            val l = textLayout ?: return@Canvas
                            if (!caretOn) return@Canvas
                            val rect = l.getCursorRect(
                                value.selection.start.coerceIn(0, l.layoutInput.text.length),
                            )
                            // 光标已经落到底边之外（滚动态检测晚一帧）：这一帧宁可不画，
                            // 也不要画在框外的错误位置。
                            if (rect.top >= size.height) return@Canvas
                            val w = CaretWidth.toPx()
                            val h = rect.height.takeIf { it > 0f } ?: l.size.height.toFloat()
                            drawRoundRect(
                                color = p.text,
                                topLeft = Offset(rect.left - w / 2f, rect.top),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(w / 2f),
                            )
                        }
                    }
                }
            }
            // 这里不再有第二个「完成」：提交统一走操作栏右侧那一个（D-28）。
            Spacer(Modifier.width(2.dp))
        }
        // 键盘之外的最后一段留白：贴边太紧时手指不好点。
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * 卡片进 / 出场的位移时长（毫秒）：卡片从「整卡在屏幕下沿之外」走到「贴住键盘上沿」那一段。
 * 贴着键盘的那一段由 [AnnotationSheet] 的 `imePx` 逐帧带着走（键盘升多快它就多快）。
 */
internal const val SHEET_SLIDE_MS = 200

/** 批注输入框最多显示几行（超出后字段内部滚动）。 */
private const val NOTE_MAX_LINES = 4

/** 输入框正文与提示字共用的字面（两者行高必须一致，否则空框和有字时高度不同）。 */
private val NoteTextStyle = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)

/** 自绘光标的宽度与闪烁周期（毫秒）：硬开关，不做系统光标那种透明度渐变。 */
private val CaretWidth = 2.dp
private const val CARET_BLINK_MS = 500L

/**
 * 等键盘起步的上限（毫秒）：光标落进输入框后宿主才去叫键盘，键盘窗口要过几十毫秒才开始升。
 * 超过这个时限还没等到（外接实体键盘 / 系统压根不给逐帧值），卡片就自己走，不会卡在屏幕外。
 */
internal const val SHEET_IME_WAIT_MS = 200

/**
 * 卡片升起度（D-30）：0 = 整张卡片在屏幕下沿之外，1 = 卡片贴在键盘上沿。
 *
 * 关键在**起步时刻**：光标一落进输入框，宿主就去叫键盘，但键盘窗口要再过几十毫秒才开始升。
 * 卡片如果这时已经自己往上滑，读者看到的就是「先弹卡片、再顶键盘」——两段（2026-11-03 用户复报）。
 * 所以这里等 [imePx] 离开 0（= 键盘真的开始升）才放开闸门，两者同一时刻起步：键盘压在卡片下沿上，
 * 卡片看着就是从键盘后面长出来的；键盘落回去时同理被它一口吞掉。
 *
 * [gateOnIme] 为 false 时（宿主压根没有逐帧 IME 值：桌面、Android 10 及以下）不等，立刻走。
 * 等待也有上限（[SHEET_IME_WAIT_MS]），等不到就自己走——键盘真的不来时卡片不能永远藏在屏幕外。
 */
@Composable
internal fun rememberSheetReveal(visible: Boolean, imePx: Float, gateOnIme: Boolean): Float {
    var armed by remember { mutableStateOf(!gateOnIme) }
    // 先声明「开闸前的复位 + 看门狗」，再声明「键盘一开始升就开闸」：同一帧里后者覆盖前者。
    LaunchedEffect(visible, gateOnIme) {
        if (!gateOnIme) {
            armed = true
            return@LaunchedEffect
        }
        if (!visible) {
            armed = false
            return@LaunchedEffect
        }
        delay(SHEET_IME_WAIT_MS.toLong())
        armed = true
    }
    LaunchedEffect(visible, imePx > 0f) {
        if (visible && imePx > 0f) armed = true
    }
    return animateFloatAsState(
        targetValue = if (visible && armed) 1f else 0f,
        animationSpec = tween(SHEET_SLIDE_MS),
        label = "annotationSheetReveal",
    ).value
}

