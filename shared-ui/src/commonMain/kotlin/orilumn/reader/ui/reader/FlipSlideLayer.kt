package orilumn.reader.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * P1 L1 整幅滑动层（用户层）：把「当前页」与「目标页」两张画布并排，按 progress 整幅平移。
 *
 * 为什么单独一层：`ReaderScreen` 已经够长了（手势 + 落位 + 栏 + 面板都在里面），
 * 而双页几何只有一处关心——两页的偏移量。把偏移算式收在这里，翻页数学将来换成
 * 卷曲（P3）时只改这一个 composable，外层不动。
 *
 * 几何（progress 已由 [FlipSession] 归一：正 = 朝目标页翻了这么多页）：
 * ```
 *   当前页 x = -progress * shift          // shift = pageW * direction
 *   目标页 x = (1 - progress) * shift
 * ```
 * 即「下一页」时：当前页从 0 滑到 -pageW（向左出屏），目标页**从右侧 +pageW 屏外**
 * 随它一起向左滑进 0——像粘在当前页右侧一样。上一页（direction=-1）自动镜像。
 *
 * 目标页的符号写反会得到「从左边滑入」的错误观感，且 progress=0 那一刻它已经在
 * 左屏外 ⇒ 静止时若目标页恰好有内容会露出一条。是那种一眼就看出不对、但不会崩的错。
 *
 * [target] 为 null（预取未到位 / 引擎说没有相邻页）时**只画当前页**：那一帧不动画。
 * 观感是「起手顿一下」而不是卡住；P0c 的后台预热正是为了消掉这一帧。
 */
@Composable
fun FlipSlideLayer(
    direction: Int,
    progress: Float,
    pageWidthPx: Float,
    modifier: Modifier = Modifier,
    target: (@Composable () -> Unit)? = null,
    current: @Composable () -> Unit,
) {
    val shift = pageWidthPx * direction
    Box(modifier = modifier) {
        if (target != null) {
            Box(Modifier.fillMaxSize().graphicsLayer { translationX = (1f - progress) * shift }) {
                target()
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -progress * shift }) {
            current()
        }
    }
}
