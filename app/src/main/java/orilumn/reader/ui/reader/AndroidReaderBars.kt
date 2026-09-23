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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * Reading top/bottom bars (replicating the old reader.html `#bars`): toggled by a middle-zone tap, dark-gray background `#303030`.
 *  - Top bar: ‹ bookshelf + book title + chapter name (40% width, shrunk).
 *  - Bottom bar: progress row (previous chapter / seek / percentage / next chapter) + tools row (☰ TOC ☆ bookmark ✎ note ☾ night ⚙ settings).
 * Settings-type buttons are entered only via ⚙; this component only renders the bars, actions are implemented via [ReaderActivity] callbacks.
 *
 * Tapping the blank space outside the bars ([onTapOutside]) hides the whole bar; taps landing on buttons/progress bar are consumed individually.
 */
@Composable
fun AndroidReaderBars(
    visible: Boolean,
    statusBarInsetPx: Int,
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
    /** P6 — true while a jump (TOC/seek/chapter) is in flight: shows a thin gold progress line at the
     *  reading surface's top edge (independent of the bars' visibility, since TOC/seek can jump while
     *  the bars are hidden). */
    jumpActive: Boolean,
) {
    val density = LocalDensity.current
    val statusInset = with(density) { statusBarInsetPx.toDp() }
    Box(modifier = Modifier.fillMaxSize()) {
        // Top-edge load line while a jump is being resolved/shaped (indeterminate, always on top).
        if (jumpActive) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(3.dp),
                color = Gold,
                trackColor = Color.Transparent,
            )
        }
        // Blank space outside the bars: consume the tap → hide the bars (middle-zone tap semantics).
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
        // Top bar: slides in/out from the top with fade. The background extends up to the top (behind the status bar);
        // content sinks/rises via translation (offset) along the status-bar inset animation, frame-aligned with the system, not via static padding.
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            enter = slideInVertically(tween(BarsAnim)) { -it } + fadeIn(tween(BarsAnim)),
            exit = slideOutVertically(tween(BarsAnim)) { -it } + fadeOut(tween(BarsAnim)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarBg),
            ) {
                // Background spans from y=0 down to the content bottom; the top first holds the status-bar height, then lays out the content —
                // total height = statusInset + content height, content sits below the status bar, not clipped.
                // The Spacer height is driven by the per-frame inset callback, aligned with the status bar frame-by-frame.
                Column(modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(statusInset))
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
        // Bottom bar: slides in/out from the bottom with fade.
        AnimatedVisibility(
            visible = visible,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            enter = slideInVertically(tween(BarsAnim)) { it } + fadeIn(tween(BarsAnim)),
            exit = slideOutVertically(tween(BarsAnim)) { it } + fadeOut(tween(BarsAnim)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarBg),
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
                    Text("${(fraction.coerceIn(0f, 1f) * 100).toInt()}%", color = BarFg, fontSize = 12.sp)
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

/** Top-bar "‹ bookshelf" back button: stroked rounded, dims on press. */
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

/** Bottom-bar "previous/next chapter" plain text button. */
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

/** Bottom-bar tool button: icon on top, label below (replicating .tool: ic 20px / lb 10px, pressed highlight rounded block). */
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

// ---- Bar palette (replicating the old #bars dark gray + gold accent) ----

private val BarBg = Color(0xFF303030)
private val BarFg = Color(0xFFF5F5F5)
private val BarSub = Color(0xFFF5F5F5).copy(alpha = 0.85f)
private val BarTrack = Color.White.copy(alpha = 0.28f)
private val Gold = Color(0xFFC8A15A)

/** Bar slide in/out duration (replicating the old --bars-anim 240ms). */
private const val BarsAnim = 240

/**
 * Bottom reading progress bar (thin rail + solid round thumb, same style as the settings-panel slider):
 *  thin rail (4dp) + gold fill + gold solid round thumb, positioned by drag/tap, calls back [onSeek] per frame.
 */
@Composable
private fun ThinSlider(fraction: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    // Local position during drag: the thumb follows the finger live; onSeek is only called once on release/off-screen.
    var dragValue by remember { mutableFloatStateOf(fraction.coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    // Pending landing: after release, wait for external progress to reach the target; keep the thumb from being pulled back to the old value meanwhile.
    var pendingSeek by remember { mutableStateOf(false) }

    val external = fraction.coerceIn(0f, 1f)
    // Follow external progress only when fully idle (not dragging, not pending landing) (page-flip/jump updates the bars' fraction).
    if (!dragging && !pendingSeek && abs(external - dragValue) > 0.0001f) dragValue = external
    // Pending landing ends: external progress has reached the target, release and rejoin the thumb.
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
                        // During a drag, no jump is triggered and the thumb is not overridden by external progress
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