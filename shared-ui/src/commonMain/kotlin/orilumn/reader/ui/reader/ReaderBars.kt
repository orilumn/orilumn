package orilumn.reader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs

/**
 * S28 阅读上下栏（平移自 Android `ReaderBars.kt`，行为/观感原样保留）：中部点按切换显示，
 * 深灰底 #303030，顶部 ‹书架 + 书名 + 章名，底部进度行（上一章/滑动条/百分比/下一章）+ 工具行
 * （目录 / 书签 / 笔记 / 修订 / 审阅 / 夜间 / 设置，图标见 `ReaderBarIcons.kt`）。
 *
 * 平台差异收敛点：原 `statusBarInsetPx`（Android 物理 px）改为 **Dp** [statusBarInset]，由宿主把
 * 系统 insets 换算成 Dp 传入（桌面无状态栏则 0.dp）。其余为纯 CMP。
 */
@Composable
fun ReaderBars(
    visible: Boolean,
    statusBarInset: Dp = 0.dp,
    bookTitle: String,
    chapterTitle: String,
    fraction: Float,
    onTapOutside: () -> Unit,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    /**
     * 进度条某个位置（0..1）对应的全书页码（1 起）；返回 null = 全书页数不全，右端显示百分比。
     * 与松手落位同一换算（宿主侧 [ReaderHost.bookPageNumberAtFraction]）。
     */
    pageOfFraction: (Float) -> Int? = { null },
    onNight: () -> Unit,
    onSettings: () -> Unit,
    onToc: () -> Unit,
    onBookmark: () -> Unit,
    onNote: () -> Unit,
    /** 底栏「修订」：直接打开正文修改清单（修订面板）。默认不出现该按钮。 */
    onRevision: (() -> Unit)? = null,
    /** 底栏「审阅」：切换审阅视图（删掉的字变红划红线、新添的字变绿）。默认不出现该按钮。 */
    onReview: (() -> Unit)? = null,
    /** 审阅视图是否开着（「审阅」按钮高亮）。 */
    reviewActive: Boolean = false,
    /** 夜间（深色）模式是否开着（「夜间」按钮跟「审阅」同一套高亮）。 */
    nightActive: Boolean = false,
    /** 底栏按钮显隐（设置 → 工具栏）：false 时该按钮不出现。「目录」「设置」始终显示。 */
    showBookmark: Boolean = true,
    showNote: Boolean = true,
    showRevision: Boolean = true,
    showReview: Boolean = true,
    showNight: Boolean = true,
    /** 顶/底栏实测高度回抛（px）：阅读面据此把栏区落点的手势让给栏，不进翻页层。默认空实现。 */
    onTopBarSize: (androidx.compose.ui.unit.IntSize) -> Unit = {},
    onBottomBarSize: (androidx.compose.ui.unit.IntSize) -> Unit = {},
) {
    // 拖动中的位置（松手后先保留，等外部进度追上来再交还）：右端数字/页码实时跟手。
    var seekPreview by remember { mutableStateOf<Float?>(null) }
    // 上下栏收起时清掉，免得下次打开还挂着上一次没落成的预览值。
    LaunchedEffect(visible) { if (!visible) seekPreview = null }

    Box(modifier = Modifier.fillMaxSize()) {
        // 条外空白：吃掉点按 → 收起上下栏（中部点按语义）。
        AnimatedVisibility(visible = visible, enter = fadeIn(tween(160)), exit = fadeOut(tween(200))) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() }, indication = null,
                        onClick = onTapOutside,
                    ),
            )
        }
        // 顶栏：从顶部滑入/滑出 + 淡入淡出。背景上探到顶（状态栏之后由 insets 顶出），
        // 内容随状态栏 inset 下沉（屏障高度 = 传入的 Dp）。
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter = slideInVertically(tween(BarsAnim)) { -it } + fadeIn(tween(BarsAnim)),
            exit = slideOutVertically(tween(BarsAnim)) { -it } + fadeOut(tween(BarsAnim)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarBg)
                    .onSizeChanged(onTopBarSize),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(statusBarInset))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BackChip(onBack)
                        Text(
                            text = bookTitle, color = BarFg, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        if (chapterTitle.isNotBlank()) {
                            Text(
                                text = chapterTitle, color = BarSub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
        // 底栏：从底部滑入/滑出 + 淡入淡出。
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = slideInVertically(tween(BarsAnim)) { it } + fadeIn(tween(BarsAnim)),
            exit = slideOutVertically(tween(BarsAnim)) { it } + fadeOut(tween(BarsAnim)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarBg)
                    .onSizeChanged(onBottomBarSize),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BarTextBtn("上一章", onPrev)
                    // 拖动中显示手指位置；松手后与圆点同口径：等外部进度追到附近再交还。
                    val shown = seekPreview?.takeIf { abs(it - fraction) > 0.02f } ?: fraction
                    ThinSlider(
                        fraction = fraction.coerceIn(0f, 1f),
                        onSeek = onSeek,
                        onDragFraction = { seekPreview = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                    // 有完整页码就写页码（与落位同一换算），否则退回百分比。
                    // 定宽右对齐：数字位数变化时滑块长度不跟着抖。
                    Text(
                        text = pageOfFraction(shown)?.toString()
                            ?: "${ReaderMath.progressPercent(shown)}%",
                        color = BarFg,
                        fontSize = 12.sp,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                        modifier = Modifier.widthIn(min = 42.dp),
                    )
                    BarTextBtn("下一章", onNext)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    BarTool(BarIcons.Menu, "目录", onToc)
                    // 书签 / 笔记 / 修订 / 审阅 / 夜间：读者可在设置 → 工具栏里逐个收起。
                    if (showBookmark) BarTool(BarIcons.Bookmark, "书签", onBookmark)
                    if (showNote) BarTool(BarIcons.Note, "笔记", onNote)
                    // 修订紧挨笔记（两者同属「记录」），审阅再往右：点开才有改动痕迹。
                    if (showRevision) onRevision?.let { BarTool(BarIcons.Edit, "修订", it) }
                    if (showReview) onReview?.let { BarTool(BarIcons.Review, "审阅", it, active = reviewActive) }
                    if (showNight) BarTool(BarIcons.Moon, "夜间", onNight, active = nightActive)
                    BarTool(BarIcons.Gear, "设置", onSettings)
                }
            }
        }
    }
}

/** 顶栏 "‹ 书架" 返回键：描边圆角，按下变灰。 */
@Composable
private fun BackChip(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Text(
        text = "‹ 书架", color = BarFg, fontSize = 14.sp,
        modifier = Modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .scale(if (pressed) 0.92f else 1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** 底栏 "上一章/下一章" 纯文字按钮。 */
@Composable
private fun BarTextBtn(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Text(
        text = label, color = BarFg, fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .scale(if (pressed) 0.92f else 1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/** 底栏工具按钮：上图标/下文字（对应旧 .tool ic20/lb10，按下圆角高亮）。
 *
 * 图标与文字各占**固定槽位**：图标一律缩放到同一边长、在槽内居中；文字行由槽位高度决定。
 * 一排按钮因此列高一致，图标墨迹中心与文字基线都落在同一条线上，与平台字体无关。
 */
@Composable
private fun BarTool(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val iconColor = if (active) Gold else BarFg
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (pressed) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .scale(if (pressed) 0.92f else 1f)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(BarToolIconSlot),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(BarToolIconSize),
            )
        }
        Text(text = label, color = BarFg, fontSize = 10.sp, lineHeight = 12.sp)
    }
}

/** 每个按钮共用的图标槽位与图标边长：图标在槽内居中，槽高决定下方文字行的位置。 */
private val BarToolIconSlot = 24.dp
private val BarToolIconSize = 20.dp

// ---- 条调色板（复刻旧 #bars 深灰 + 金色点缀） ----

private val BarBg = Color(0xFF303030)
private val BarFg = Color(0xFFF5F5F5)
private val BarSub = Color(0xFFF5F5F5).copy(alpha = 0.85f)
private val BarTrack = Color.White.copy(alpha = 0.28f)
private val Gold = Color(0xFFC8A15A)

/** 上下栏滑入/滑出时长（复刻旧 --bars-anim 240ms）。 */
/** 顶/底栏滑入滑出时长（ms）：面板打开前等工具栏退场就按这个等。 */
internal const val BarsAnim = 240

/**
 * 底部阅读进度条（细轨 4dp + 金色实心圆点，同设置面板滑块风格）：点按/拖动定位，[onSeek] 仅在释放/离屏时回调一次。
 */
@Composable
private fun ThinSlider(
    fraction: Float,
    onSeek: (Float) -> Unit,
    onDragFraction: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 拖动中的本地位置：圆点实时跟手；onSeek 释放/离屏才回调。
    var dragValue by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    // 待落位：释放后等待外部进度到位，期间圆点不被拖回旧值。
    var pendingSeek by remember { mutableStateOf(false) }

    val external = fraction.coerceIn(0f, 1f)
    // 完全空闲时跟随外部进度（翻页/跳转更新进度条）。
    if (!dragging && !pendingSeek && abs(external - dragValue) > 0.0001f) dragValue = external
    // 待落位结束：外部进度已达目标，解除锁定重回跟手。
    if (pendingSeek && abs(external - dragValue) <= 0.02f) pendingSeek = false

    BoxWithConstraints(
        modifier = modifier
            .height(26.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        dragging = true
                        dragValue = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        onDragFraction(dragValue)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek(dragValue.coerceIn(0f, 1f))
                        pendingSeek = true
                    },
                    onDragCancel = {
                        dragging = false
                        onSeek(dragValue.coerceIn(0f, 1f))
                        pendingSeek = true
                    },
                )
            },
    ) {
        val frac = dragValue.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .fillMaxWidth().height(4.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(2.dp))
                .background(BarTrack),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(frac).height(4.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(2.dp))
                .background(Gold),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = maxWidth * frac - 9.dp)
                .size(18.dp)
                .background(Gold, CircleShape),
        )
    }
}