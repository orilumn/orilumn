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
 * [target] 为 null（落位/等图未完成、引擎说没有相邻页）时**只画当前页且保持不动**。
 *
 * 注意是「不动」不是「当前页独自平移」：平移必须有另一页填坑，单页平移会把
 * 身后的阅读面底色露出来，观感是「先拉出一个空白页」。翻页动画的前提是**相邻
 * 两页都就绪**，未就绪时调用方（`ReaderScreen.updateSlide`）也不采纳手指位移，
 * 两边合力保证这一帧画面完全静止。目标页就绪后手指仍在屏幕上，动画无缝接上。
 *
 * ## 为什么目标槽「恒在」而不是 `if (target != null)` 包起来
 *
 * 早先这里按 target 有无**增删**目标槽。代价是整个 `FlipSlideLayer` 的组合结构在
 * 「起手（无 target）→ 落位（有 target）→ 收尾（无 target）」之间来回变，而
 * `ReaderPageCanvas` 内部的 `rememberReaderPageRenderer()`（多页位图缓存的宿主）
 * 是**按组合位置**remember 的：结构一变，渲染器连同整池页位图一起被丢弃重建。
 * 于是一次翻页要重建 3～5 轮，每轮 current/target 各做一次**整页栅格**
 * （真机日志：每次 87ms、`hit=false`、`pool` 恒为 1）——用户看到的就是
 * 「滑一点点、卡一下、目标页冒出来、才能继续滑」。
 *
 * 现在目标槽恒在、target 为 null 时**画空**（空 Box 零成本），组合结构稳定，
 * 渲染器跨整个翻页会话存活 ⇒ 当前页位图始终命中，只目标页首现栅格一次。
 *
 * ## progress 为什么是 `() -> Float` 而不是 `Float`（每帧重组 / 每帧重栅的根因）
 *
 * 早先形参是 `Float`，调用方在组合里读 `ctl.progress.value` 再喂进来。于是
 * **动画期间每一帧 progress 一变，整个阅读面组合作用域就重组一次**：`ReaderPageCanvas`
 * 的形参含 `List<DrawLine>` 这类不稳定集合 ⇒ 不可跳过 ⇒ 两个画布每帧重新组合，
 * `Canvas { }` 的 draw lambda 是新实例 ⇒ 绘制节点失效、**每帧重走 `drawLines`**：
 * 指纹深比较（逐行 data class 相等）+ 位图重贴 + 每帧一条 `SkiaBridge` 日志。
 * 一帧的 CPU 开销就落在这个数量级，和 moon+/多看的「onDraw 贴两张已缓存位图」差出一档。
 *
 * 改成传闭包后，`progress()` 只在 `graphicsLayer { }` 的 **draw 相位**被读：Compose 会
 * 把这次读登记成「层属性依赖」，progress 变化只刷新两张页的平移量，**不触发重组、
 * 不重走 Canvas 绘制**——动画期间只剩 GPU 平移两张已缓存的页位图。
 *
 * 语义不变（调用方闭包内仍含 `slideActive` 守卫）：动画关闭 / 无会话时 progress()
 * 返回 0，画面静止。
 */
@Composable
fun FlipSlideLayer(
    direction: Int,
    progress: () -> Float,
    pageWidthPx: Float,
    modifier: Modifier = Modifier,
    target: (@Composable () -> Unit)? = null,
    current: @Composable () -> Unit,
) {
    val shift = pageWidthPx * direction
    Box(modifier = modifier) {
        // 读 progress() 的位置是关键：写在 graphicsLayer 的 lambda 里 = draw 相位读，
        // 快照依赖挂在层上而非组合作用域（见本函数 KDoc）。
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = (1f - progress()) * shift }) {
            target?.invoke()
        }
        Box(Modifier.fillMaxSize().graphicsLayer { translationX = -progress() * shift }) {
            current()
        }
    }
}
