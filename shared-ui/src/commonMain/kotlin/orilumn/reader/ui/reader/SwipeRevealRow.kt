package orilumn.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * 左滑露出的操作按钮配色（删除 / 隐藏 / 恢复 三种语义）。
 *
 * 与字体管理面板同一套色：红=删除、橙=隐藏、紫=恢复；将来别处要别语义就加一格，
 * 不要在调用点自造颜色——同一手势在不同面板里颜色不一样，读者会以为是两种操作。
 */
internal enum class SwipeActionTone(val color: Color) {
    Delete(Color(0xFFD9534F)),
    Hide(Color(0xFFE67E22)),
    Restore(Color(0xFF9C27B0)),
}

/** 一行左滑能露出的那种操作（[swipeRevealRow] 的按钮配置）。 */
internal data class SwipeAction(
    val label: String,
    val tone: SwipeActionTone,
    val onClick: () -> Unit,
)

/** 左滑行程 = 按钮宽（沿用旧字体管理面板的 88dp 口径）。 */
internal val SwipeRevealActionWidth = 88.dp

/** 左滑 / 右滑超出量收敛用的「橡皮筋」特征长度（dp）：超得越多、再拉越拉不动。 */
private val SwipeRightRubberDp = 72.dp
private val SwipeLeftRubberDp = 56.dp

/**
 * 通用「左滑露出操作按钮」行（用户层组件）：iOS 式物理，行体与按钮一起滑。
 *
 * 交互口径（从字体管理面板抽出来，两个面板共用同一份行为）：
 *  - 左滑至多露出按钮宽，继续拉按指数收敛（橡皮筋）；右滑同样有橡皮筋，不会真的把行推走；
 *  - 松手按最后一段的滑动方向吸附开 / 合（右滑到底即收回）；
 *  - **同时只展开一行**：谁展开谁通过 [onOpenChange] 登记到调用方的 [openKey]，
 *    [openKey] 一变，其余行弹簧收回。展开只由手指驱动，切换行时两行不会在同一偏移上叠动画。
 *
 * 竖向滚动不受影响：水平拖拽识别器只认领水平位移，LazyColumn 的纵向手势照常。
 *
 * @param rowKey 本行在列表里的唯一键（同一列表内不重复）
 * @param openKey 当前展开行的键；null = 全部收起
 * @param onOpenChange 换行展开 / 收回时调用（传 null 即收起全部）
 * @param action 这行左滑能做什么；null = 这行没有左滑（点选与纵向滚动照旧）
 * @param modifier 作用在最外层（行背景、悬停、键盘焦点环都挂这里）
 * @param content 行体：自己负责内边距与点按，宽度撑满、高度按内容
 */
@Composable
internal fun SwipeRevealRow(
    rowKey: String,
    openKey: String?,
    onOpenChange: (String?) -> Unit,
    modifier: Modifier = Modifier,
    action: SwipeAction? = null,
    content: @Composable () -> Unit,
) {
    val yDp: Dp = SwipeRevealActionWidth
    val density = LocalDensity.current
    val yPx = with(density) { yDp.toPx() }
    val rightPx = with(density) { SwipeRightRubberDp.toPx() }
    val leftPx = with(density) { SwipeLeftRubberDp.toPx() }
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val overBest = remember { Animatable(0f) }
    val dragDir = remember { mutableFloatStateOf(0f) }
    val dragAccum = remember { mutableFloatStateOf(0f) }
    // latch 到共享 openKey：本行不再是展开行即弹簧收回（展开只由手指驱动）。
    LaunchedEffect(openKey) {
        if (openKey != rowKey) {
            offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium, dampingRatio = 0.7f))
            overBest.animateTo(0f, spring())
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clipToBounds(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (action == null) Modifier else Modifier
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .pointerInput(yPx, rightPx, leftPx) {
                            detectHorizontalDragGestures(
                                onDragStart = {
                                    dragDir.value = 0f
                                    dragAccum.value = offsetX.value
                                    // 触碰即认领：其他展开行立刻开始收（不 lingering 第二个按钮）。
                                    onOpenChange(rowKey)
                                },
                                onDragEnd = {
                                    scope.launch {
                                        val target = if (offsetX.value < 0f) {
                                            if (dragDir.value < 0f) -yPx else 0f
                                        } else 0f
                                        offsetX.animateTo(
                                            target,
                                            spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.5f),
                                        )
                                        overBest.animateTo(0f, spring())
                                        // 落位展开即认领，其他行收起。
                                        if (target < 0f) onOpenChange(rowKey)
                                    }
                                },
                                onDragCancel = {
                                    scope.launch { offsetX.snapTo(0f); overBest.snapTo(0f) }
                                },
                            ) { change, dragAmount ->
                                change.consume()
                                if (dragAmount != 0f) dragDir.value = if (dragAmount > 0f) 1f else -1f
                                dragAccum.value += dragAmount
                                val d = dragAccum.value
                                if (d < -yPx) {
                                    val over = -d - yPx
                                    val best = leftPx * (1f - exp(-over / leftPx))
                                    scope.launch {
                                        overBest.snapTo(best)
                                        offsetX.snapTo(-yPx - overBest.value)
                                    }
                                } else {
                                    val shown = if (d <= 0f) d else rightPx * (1f - exp(-d / rightPx))
                                    scope.launch { offsetX.snapTo(shown); overBest.snapTo(0f) }
                                }
                            }
                        },
                ),
        ) {
            content()
        }
        if (action != null) {
            val overDp = with(density) { overBest.value.toDp() }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .offset { IntOffset((offsetX.value + yPx + overBest.value).roundToInt(), 0) },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(yDp + overDp)
                        .fillMaxHeight()
                        .background(action.tone.color)
                        .clickable {
                            action.onClick()
                            onOpenChange(null)
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        action.label,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 28.dp),
                    )
                }
            }
        }
    }
}
