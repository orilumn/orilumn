package orilumn.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 左停靠抽屉的两页（D-23）：目录与笔记共用一个壳，`☰` 落目录页、`✎` 落笔记页，
 * 页内切换不关面板；当前页由平台壳记忆（会话内）。
 */
enum class ReaderPanelPage { Toc, Annotations }

/**
 * 阅读侧边抽屉的共用壳（用户层）：遮罩 + 滑入动画 + 顶部两页控件 + ✕。
 *
 * 外观常量沿用原目录抽屉（顶条 52dp、宽度 <600dp 时 85% 否则 360dp、滑入 280ms、
 * 遮罩 40% 黑），**不因抽壳改手感**；两页的列表体各自处理键盘与焦点。
 */
@Composable
fun ReaderSidePanel(
    visible: Boolean,
    page: ReaderPanelPage,
    onPageChange: (ReaderPanelPage) -> Unit,
    scheme: String,
    onDismiss: () -> Unit,
    tocContent: @Composable () -> Unit,
    annotationsContent: @Composable () -> Unit,
) {
    val p = paletteFor(scheme)
    val slide = remember { Animatable(1f) }
    var mounted by remember { mutableStateOf(false) }
    var maskOn by remember { mutableStateOf(false) }
    val maskAlpha by animateFloatAsState(
        if (maskOn) 1f else 0f,
        tween(SidePanelAnimMs, easing = FastOutSlowInEasing), label = "sidePanelMask",
    )

    LaunchedEffect(visible) {
        if (visible) {
            mounted = true
            slide.snapTo(1f)
            // 遮罩与滑入同拍，和原目录抽屉一致。
            maskOn = true
            slide.animateTo(0f, tween(SidePanelAnimMs, easing = FastOutSlowInEasing))
        } else {
            maskOn = false
            slide.animateTo(1f, tween(SidePanelAnimMs, easing = FastOutSlowInEasing))
            delay((SidePanelAnimMs + 20).toLong())
            mounted = false
        }
    }

    if (mounted) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val drawerWidth = if (maxWidth < 600.dp) maxWidth * 0.85f else 360.dp
            val drawerPx = with(androidx.compose.ui.platform.LocalDensity.current) { drawerWidth.toPx() }
            Box(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(maskAlpha)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClick = onDismiss,
                        ),
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset((-slide.value * drawerPx).roundToInt(), 0) }
                        .width(drawerWidth)
                        .fillMaxHeight()
                        .background(p.bg),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PageTab("目录", page == ReaderPanelPage.Toc, p) { onPageChange(ReaderPanelPage.Toc) }
                            Text("|", color = p.muted2, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 6.dp))
                            PageTab("笔记", page == ReaderPanelPage.Annotations, p) {
                                onPageChange(ReaderPanelPage.Annotations)
                            }
                        }
                        Text("✕", color = p.text, fontSize = 18.sp, modifier = Modifier
                            .padding(10.dp)
                            .clip(RoundedCornerShape(6.dp)).clickable(onClick = onDismiss))
                    }
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(p.borderSoft))
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        when (page) {
                            ReaderPanelPage.Toc -> tocContent()
                            ReaderPanelPage.Annotations -> annotationsContent()
                        }
                    }
                }
            }
        }
    }
}

/** 顶条分段控件的一页：选中金字加粗，未选中灰字；不加额外外观常量。 */
@Composable
private fun PageTab(label: String, selected: Boolean, p: Palette, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) PanelGold else p.muted2,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/** 抽屉滑入 / 遮罩共用时长（原目录抽屉常量，抽壳时照旧）。 */
internal const val SidePanelAnimMs = 280
