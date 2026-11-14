package orilumn.reader.ui.reader.curl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import orilumn.reader.io.Logger

/**
 * L3 3D 卷曲层（用户层）：两个页槽常驻组合，卷曲时把槽**整页快照**当纹理交给着色器。
 *
 * ## 为什么是快照，而不是直接拿页栅格
 *
 * 页码与旁注是画在页图**外面**的 Compose 覆盖层（高亮/下划线在页图里）。要让它们跟着纸
 * 一起弯，只有两条路：把它们的绘制重写一遍到栅格里（旁注是 7.6k 行 Compose 布局，
 * 双份实现必然漂移），或者把「已经画好的整页」截下来当纹理。这里选后者：
 * 快照天然包含页图 + 页码 + 旁注，以后再加任何页内覆盖层都不用改这里。
 *
 * ## 时序（宁可等图，不换一种翻法）
 *
 * 槽先以正常方式画一帧（`drawLayer` 记录），下一帧开头截图；两张都就绪才画卷曲。
 * 未就绪时**页面保持不动**——不切滑动、不冻帧（调用方负责在这期间锁住手指位移）。
 * 快照的失效键由调用方给（页内容 + 旁注 + 页码），任一变化重截。
 *
 * **截到空图不算数**：层的记录与截图隔一帧，任何时序错位都会截出一张全透明图
 * （症状是「露出侧透出底下的当前页、纸背只剩纸色」——看着像卷曲算错了，其实没纹理）。
 * 所以每张图截完都验一下「至少有一个不透明像素」，没有就下一帧再截，直到拿到为止；
 * 连续 [MAX_SHOT_TRIES] 帧都拿不到才放弃并留日志（着色器画不出来时槽的原样内容仍在 ⇒ 不黑屏）。
 *
 * ## 绘制顺序
 *
 * 目标页槽在下、当前页槽在上（与 `FlipSlideLayer` 同序）。卷曲层整页不透明地盖在最上面，
 * 两个槽只是「快照的来源」，不再直接可见。
 *
 * @param active 本次翻页会话是否在进行（闭包：只在 draw 相位读，否则每帧重组整个阅读面）
 * @param progress 归一化进度闭包：同上，只在 draw 相位读
 * @param tilt 折痕倾斜闭包（手指位移的上下分量，见 `FlipController.curlTilt`）：同上
 */
@Composable
fun FlipCurlLayer(
    direction: () -> Int,
    progress: () -> Float,
    tilt: () -> Float,
    pageWidthPx: Float,
    pageHeightPx: Float,
    active: () -> Boolean,
    paperArgb: Int,
    currentKey: Any?,
    targetKey: Any?,
    current: @Composable () -> Unit,
    target: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val currentLayer = rememberGraphicsLayer()
    val targetLayer = rememberGraphicsLayer()
    var currentShot by remember { mutableStateOf<ImageBitmap?>(null) }
    var targetShot by remember { mutableStateOf<ImageBitmap?>(null) }
    // 已经截到（且验过非空）的键：同键不重截。
    var currentShotKey by remember { mutableStateOf<Any?>(null) }
    var targetShotKey by remember { mutableStateOf<Any?>(null) }

    // 快照：键变了就（重）截，截到空图下一帧再试（见 KDoc「截到空图不算数」）。
    // 只有拿到两张图才退出循环 ⇒ 循环本身不常驻，稳态零开销。
    suspend fun shotOf(layer: GraphicsLayer): ImageBitmap? {
        val img = runCatching { layer.toImageBitmap() }.getOrNull() ?: return null
        return if (img.visiblePixelCount() > 0) img else null
    }

    LaunchedEffect(currentKey, targetKey) {
        var tries = 0
        while (true) {
            tries++
            withFrameNanos { }
            var pending = false
            if (currentShot == null || currentShotKey != currentKey) {
                val shot = shotOf(currentLayer)
                if (shot != null) {
                    currentShot = shot
                    currentShotKey = currentKey
                    Logger.d("Orilumn.Curl", "shot current tries=$tries ${shot.probe()}")
                } else {
                    pending = true
                }
            }
            if (targetKey == null) {
                // 没有目标槽（落位未回 / 就在同一页）：清掉，别拿上一次的旧图去卷。
                targetShot = null
                targetShotKey = null
            } else if (targetShot == null || targetShotKey != targetKey) {
                val shot = shotOf(targetLayer)
                if (shot != null) {
                    targetShot = shot
                    targetShotKey = targetKey
                    Logger.d("Orilumn.Curl", "shot target tries=$tries ${shot.probe()}")
                } else {
                    pending = true
                }
            }
            if (!pending) return@LaunchedEffect
            if (tries >= MAX_SHOT_TRIES) {
                Logger.w(
                    "Orilumn.Curl",
                    "shot GIVE-UP tries=$tries current=${currentShot?.probe() ?: "null"} " +
                        "target=${targetShot?.probe() ?: "null"}",
                )
                return@LaunchedEffect
            }
        }
    }

    Box(modifier) {
        // 目标槽先画（在下），当前槽后画（在上）：与滑动层同序，卷曲没画出来时看到的就是这一对。
        Box(Modifier.fillMaxSize().snapshotLayer(targetLayer)) { target?.invoke() }
        Box(Modifier.fillMaxSize().snapshotLayer(currentLayer)) { current() }

        // 卷曲：整屏一张不透明图，覆盖上面两个槽。读闭包 = draw 相位，动画期间不重组。
        if (currentShot != null) {
            Canvas(Modifier.fillMaxSize()) {
                val dir = direction()
                val front = if (dir >= 0) currentShot else targetShot
                val back = if (dir >= 0) targetShot else currentShot
                if (!active() || front == null || back == null) return@Canvas
                val frame = curlFrame(
                    pageW = pageWidthPx,
                    pageH = pageHeightPx,
                    progress = progress(),
                    direction = dir,
                    tilt = tilt(),
                    paperArgb = paperArgb,
                )
                if (!drawCurlFrame(frame, front, back)) {
                    // 平台画不出来（低版本 / 着色器编译失败 / 像素桥失败）：留一条，便于真机定位。
                    Logger.w("Orilumn.Curl", "drawCurlFrame returned false (dir=$dir)")
                }
            }
        }
    }
}

/** 连续多少帧截不到非空图就放弃（约半秒；再久说明这张图根本截不出来）。 */
private const val MAX_SHOT_TRIES = 30

/**
 * 抽样看这张图有没有内容：取中间一小块，数不透明像素。
 *
 * 为什么不是「整张全透明」判据：整页遍历是 7M 次读，且在 Android 上要额外分配一张整图。
 * 空层（尚未画过）是**整张全零**，中间那一块必然是零；有内容的页哪怕全白，页底色也不透明。
 */
private fun ImageBitmap.visiblePixelCount(): Int {
    if (width <= 0 || height <= 0) return 0
    val n = 4
    val x = (width / 2).coerceAtMost(width - n).coerceAtLeast(0)
    val y = (height / 2).coerceAtMost(height - n).coerceAtLeast(0)
    val buf = IntArray(n * n)
    return runCatching { readPixels(buf, x, y, n, n) }.getOrDefault(0).let {
        buf.count { (it ushr 24) != 0 }
    }
}

/** 一行诊断：尺寸与抽样像素（日志里认得出「同一张图 / 空图 / 拿错页」）。 */
private fun ImageBitmap.probe(): String {
    if (width <= 0 || height <= 0) return "empty"
    val n = 2
    val x = (width / 2).coerceAtMost(width - n).coerceAtLeast(0)
    val y = (height / 2).coerceAtMost(height - n).coerceAtLeast(0)
    val buf = IntArray(n * n)
    runCatching { readPixels(buf, x, y, n, n) }
    return "${width}x$height " + buf.joinToString(",") { it.toUInt().toString(16) }
}

/**
 * 把内容画进指定的 [GraphicsLayer] 并显示它（本版本 Compose 没有 `Modifier.drawLayer`，
 * 只有 DrawScope 扩展，故用 `drawWithContent` 手动 record）。
 *
 * 每帧重录一层看着浪费，其实槽内容是页位图 + 旁注文字，录的是绘制指令不重算布局；
 * 换来的是「任意时刻都能截到当前画面的整页快照」。
 */
private fun Modifier.snapshotLayer(layer: GraphicsLayer): Modifier =
    drawWithContent {
        layer.record(
            density = this,
            layoutDirection = layoutDirection,
            size = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
        ) {
            this@drawWithContent.drawContent()
        }
        drawLayer(layer)
    }
