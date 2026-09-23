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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import kotlin.math.abs

/**
 * S28 阅读上下栏（平移自 Android `ReaderBars.kt`，行为/观感原样保留）：中部点按切换显示，
 * 深灰底 #303030，顶部 ‹书架 + 书名 + 章名，底部进度行（上一章/滑动条/百分比/下一章）+ 工具行
 * （☰目录 ☆书签 ✎笔记 ☾夜间 ⚙设置 ▦调试）。
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
    onNight: () -> Unit,
    onSettings: () -> Unit,
    onToc: () -> Unit,
    onBookmark: () -> Unit,
    onNote: () -> Unit,
    onDebug: () -> Unit,
    debugActive: Boolean,
    /** 顶/底栏实测高度回抛（px）：阅读面据此把栏区落点的手势让给栏，不进翻页层。默认空实现。 */
    onTopBarSize: (androidx.compose.ui.unit.IntSize) -> Unit = {},
    onBottomBarSize: (androidx.compose.ui.unit.IntSize) -> Unit = {},
) {
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
                    ThinSlider(
                        fraction = fraction.coerceIn(0f, 1f),
                        onSeek = onSeek,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                    )
                    Text("${ReaderMath.progressPercent(fraction)}%", color = BarFg, fontSize = 12.sp)
                    BarTextBtn("下一章", onNext)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    BarTool("☰", "目录", onToc)
                    BarTool("☆", "书签", onBookmark)
                    BarTool("✎", "笔记", onNote)
                    BarTool("☾", "夜间", onNight)
                    BarTool("⚙", "设置", onSettings)
                    BarTool("▦", "调试", onDebug, active = debugActive)
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

/** 底栏工具按钮：上图标/下文字（对应旧 .tool ic20/lb10，按下圆角高亮）。 */
@Composable
private fun BarTool(icon: String, label: String, onClick: () -> Unit, active: Boolean = false) {
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
        Text(text = icon, color = iconColor, fontSize = 20.sp, lineHeight = 20.sp)
        Text(text = label, color = BarFg, fontSize = 10.sp, lineHeight = 12.sp)
    }
}

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
private fun ThinSlider(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
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