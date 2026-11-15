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

/** 覆盖层那一遍缺一张纹理时的占位：1×1 全透明（采样恒为透明，不改变画面）。 */
private val blankTexture: ImageBitmap by lazy {
    ImageBitmap(1, 1)
}

/**
 * L3 3D 卷曲层（用户层）：两个页槽常驻组合，卷曲时把槽画出来的两张纹理交给着色器。
 *
 * ## 纹理从哪来
 *
 * 页槽里的 `ReaderPageCanvas` 每帧把「页栅格 + 覆盖层栅格」画出来，同时经 `onImages`
 * 发布到 [CurlPageImages]。所以卷曲用的纹理与屏幕上的像素**同一份**：页码、旁注、
 * 高亮、下划线全在里面（前三者随覆盖层/页栅格，后两者本来就在页栅格里）。
 *
 * 一帧画两遍：页栅格一遍、覆盖层一遍（覆盖层那一遍纸背/纸卷返回透明），
 * 于是旁注和页码跟着纸一起弯，而不是浮在上面不动。
 *
 * ## 时序（宁可等图，不换一种翻法）
 *
 * 两张页图都在才画卷曲；缺图时**页面保持不动**（槽照常显示当前页）——不切滑动、不冻帧。
 * 覆盖层缺一张时用 1×1 透明图占位（那页本来就没有旁注/页码）。
 *
 * ## 绘制顺序
 *
 * 目标页槽在下、当前页槽在上（与 `FlipSlideLayer` 同序）。卷曲层整页不透明地盖在最上面，
 * 两个槽只是「纹理的来源」，不再直接可见。
 *
 * @param active 本次翻页会话是否在进行（闭包：只在 draw 相位读，否则每帧重组整个阅读面）
 * @param progress 归一化进度闭包：同上，只在 draw 相位读
 * @param tilt 折痕倾斜闭包（手指位移的上下分量，见 `FlipController.curlTilt`）：同上
 * @param texX/texY/texW/texH 页栅格在页坐标里的位置与尺寸（版心）；覆盖层恒为整页
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
    texX: Float,
    texY: Float,
    texW: Float,
    texH: Float,
    current: CurlPageImages,
    target: CurlPageImages?,
    currentSlot: @Composable () -> Unit,
    targetSlot: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // 只在第一次画不出来时留一条日志：着色器编译失败会每帧都失败，刷屏没有意义。
    val warned = remember { booleanArrayOf(false) }
    // 本轮翻页是否已经留过一条「开画 / 等图」的日志（每次会话一条，便于真机对账）。
    val sessionLogged = remember { booleanArrayOf(false) }

    Box(modifier) {
        // 目标槽先画（在下），当前槽后画（在上）：与滑动层同序，卷曲没画出来时看到的就是这一对。
        if (targetSlot != null) {
            Box(Modifier.fillMaxSize()) { targetSlot() }
        }
        Box(Modifier.fillMaxSize()) { currentSlot() }

        // 卷曲：整屏一张不透明图，覆盖上面两个槽。读闭包 = draw 相位，动画期间不重组。
        Canvas(Modifier.fillMaxSize()) {
            if (!active()) {
                sessionLogged[0] = false
                return@Canvas
            }
            val dir = direction()
            val front = if (dir >= 0) current else target
            val back = if (dir >= 0) target else current
            if (front == null || back == null) {
                if (!sessionLogged[0]) {
                    sessionLogged[0] = true
                    Logger.d("Orilumn.Curl", "wait slot dir=$dir front=${front != null} back=${back != null}")
                }
                return@Canvas
            }
            val frontPage = front.page
            val backPage = back.page
            if (frontPage == null || backPage == null) {
                if (!sessionLogged[0]) {
                    sessionLogged[0] = true
                    Logger.d(
                        "Orilumn.Curl",
                        "wait page dir=$dir front=${frontPage != null} back=${backPage != null} " +
                            "overlay=${front.overlay != null}/${back.overlay != null}",
                    )
                }
                return@Canvas
            }
            val frame = curlFrame(
                pageW = pageWidthPx,
                pageH = pageHeightPx,
                progress = progress(),
                direction = dir,
                tilt = tilt(),
                paperArgb = paperArgb,
            )
            if (!sessionLogged[0]) {
                sessionLogged[0] = true
                Logger.d(
                    "Orilumn.Curl",
                    "draw dir=$dir p=${frame.progress} tex=${frontPage.width}x${frontPage.height} " +
                        "overlay=${front.overlay != null}/${back.overlay != null}",
                )
            }
            val ok = drawCurlFrame(
                frame = frame,
                front = frontPage,
                back = backPage,
                overlay = false,
                texX = texX,
                texY = texY,
                texW = texW,
                texH = texH,
            )
            if (!ok) {
                if (!warned[0]) {
                    warned[0] = true
                    // 平台画不出来（低版本 / 着色器编译失败 / 像素桥失败）：留一条，便于真机定位。
                    Logger.w("Orilumn.Curl", "drawCurlFrame returned false (dir=$dir)")
                }
                return@Canvas
            }
            // 覆盖层那一遍：旁注 + 页码跟着纸一起弯（纸背与纸卷里透明）。
            // 整页纹理 ⇒ 纹理原点恒为 (0,0)、尺寸恒为整页。
            val frontOverlay = front.overlay
            val backOverlay = back.overlay
            if (frontOverlay != null || backOverlay != null) {
                drawCurlFrame(
                    frame = frame,
                    front = frontOverlay ?: blankTexture,
                    back = backOverlay ?: blankTexture,
                    overlay = true,
                    texX = 0f,
                    texY = 0f,
                    texW = pageWidthPx,
                    texH = pageHeightPx,
                )
            }
        }
    }
}
