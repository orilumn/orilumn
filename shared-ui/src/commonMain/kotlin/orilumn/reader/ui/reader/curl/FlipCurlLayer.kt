package orilumn.reader.ui.reader.curl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import orilumn.reader.io.Logger

/**
 * 一个页槽当前可用的两张纹理（**页栅格 + 覆盖层**，都由页槽自己画出来时顺手发布）。
 *
 * 为什么不是「现截一张整页图」：`GraphicsLayer` 整页快照在平板上实测三十帧全空
 * （`shot GIVE-UP tries=30 current=null target=null`），而页槽本来就已经把这两张图
 * 画到屏幕上了——直接把它们交出来，就是「屏幕上的像素」本身，没有第二条截图通道。
 *
 * 是 Compose 状态（不是普通字段）：图晚到（目标页栅格刚排完）时要能自己把卷曲层
 * 重新画一帧，否则手指按住不动就永远停在「没图」的那一帧上。
 */
@Stable
class CurlPageImages {
    /** 正文/插图/盒背景（版心大小的页栅格）。 */
    var page: ImageBitmap? by mutableStateOf(null)

    /** 旁注 + 页码（整页大小、透明底）。没有旁注也没有页码时为 null。 */
    var overlay: ImageBitmap? by mutableStateOf(null)
}

/**
 * 折页层（用户层）：两个页槽常驻组合，翻页时按折痕把两张页图摆成「正面 / 纸背 / 露出页」。
 *
 * ## 画法
 *
 * 逐行对位旧版 `orilum.bak` 的 `CurlView.drawBody`（见 [drawCurlFrame]）：
 * 一张**刚性**纸绕折痕翻过去，屏幕上只有三块——正面页（还没掀起来的）、纸背（露出来的
 * 背面，就是正面的镜像）、露出页（折痕另一侧的新页）。阴影是沿纸背外缘和折痕各一道
 * 线性渐变。没有曲面、没有着色器：折页不会自己卷成筒。
 *
 * 两端的像素与静止时**同一份**：页槽里的 `ReaderPageCanvas` 每帧把「页栅格 + 覆盖层」
 * 画出来，同时经 `onImages` 发布到 [CurlPageImages]，本层拿它们当纹理用。
 * 所以页码、旁注、高亮、下划线都跟着纸一起走，不需要第三条截图通道。
 *
 * ## 时序（宁可等图，不换一种翻法）
 *
 * 两张页图都在才画折页；缺图时**页面保持不动**（槽照常显示当前页）——不切滑动、不冻帧。
 *
 * ## 绘制顺序
 *
 * 目标页槽在下、当前页槽在上（与 `FlipSlideLayer` 同序）。折页整页不透明地盖在最上面，
 * 两个槽只是「图从哪来」，不再直接可见。
 *
 * @param direction 翻页方向（+1 下一页 / -1 上一页）
 * @param progress 归一化进度（0..1）
 * @param tilt 折痕倾斜（手指位移的上下分量，见 `FlipController.curlTilt`）
 * @param settling 是否在结算动画里：只有结算才做末段扶正（见 [CurlShape]），
 *   手指还按着的时候形状必须听手指的
 * @param texX/texY 页栅格在页坐标里的左上角（版心）；覆盖层恒为整页、贴 (0,0)
 *
 * 三个动画量都是闭包：只在 draw 相位读，动画期间不重组整个阅读面。
 */
@Composable
fun FlipCurlLayer(
    direction: () -> Int,
    progress: () -> Float,
    tilt: () -> Float,
    settling: () -> Boolean,
    pageWidthPx: Float,
    pageHeightPx: Float,
    active: () -> Boolean,
    paperArgb: Int,
    texX: Float,
    texY: Float,
    current: CurlPageImages,
    target: CurlPageImages?,
    currentSlot: @Composable () -> Unit,
    targetSlot: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 本轮翻页是否已经留过「等图」/「开画」的日志（各一条，便于真机对账；开画那条带几何）。
    val sessionLogged = remember { booleanArrayOf(false) }
    val drawLogged = remember { booleanArrayOf(false) }

    Box(modifier) {
        // 目标槽先画（在下），当前槽后画（在上）：与滑动层同序，折页没画出来时看到的就是这一对。
        if (targetSlot != null) {
            Box(Modifier.fillMaxSize()) { targetSlot() }
        }
        Box(Modifier.fillMaxSize()) { currentSlot() }

        Canvas(Modifier.fillMaxSize()) {
            if (!active()) {
                sessionLogged[0] = false
                drawLogged[0] = false
                return@Canvas
            }
            val dir = direction()
            // 正面 = 被翻的那一张，露出页 = 折痕另一侧的新页。
            // 向后翻：正面是当前页、露出下一页；向前翻：正面是上一页、露出当前页。
            val front = if (dir >= 0) current else target
            val back = if (dir >= 0) target else current
            if (front == null || back == null || front.page == null || back.page == null) {
                if (!sessionLogged[0]) {
                    sessionLogged[0] = true
                    Logger.d(
                        "Orilumn.Curl",
                        "wait dir=$dir front=${front?.page != null} back=${back?.page != null}",
                    )
                }
                return@Canvas
            }
            val shape = curlShape(
                pageW = pageWidthPx,
                pageH = pageHeightPx,
                progress = progress(),
                direction = dir,
                tilt = tilt(),
                settling = settling(),
            )
            if (!drawLogged[0]) {
                drawLogged[0] = true
                // 几何一起记：平板实测对不上形状时，直接拿这几个数离线复现同一帧。
                Logger.d(
                    "Orilumn.Curl",
                    "draw dir=$dir p=${shape.progress} " +
                        "fold=(${shape.topX},0)-(${shape.bottomX},${pageHeightPx}) " +
                        "lean=${shape.lean} fade=${shape.shadowFade} " +
                        "tex=${front.page?.width}x${front.page?.height} " +
                        "overlay=${front.overlay != null}/${back.overlay != null}",
                )
            }
            // 3D 卷曲优先；本端画不了（安卓 < 33）才退回折页。
            if (curlMeshSupported()) {
                val done = drawCurl3DFrame(
                    shape = shape,
                    front = front,
                    back = back,
                    paperArgb = paperArgb,
                    texLeft = texX,
                    texTop = texY,
                )
                if (done) return@Canvas
            }
            drawCurlFrame(
                shape = shape,
                front = front,
                back = back,
                paperArgb = paperArgb,
                texLeft = texX,
                texTop = texY,
            )
        }
    }
}
