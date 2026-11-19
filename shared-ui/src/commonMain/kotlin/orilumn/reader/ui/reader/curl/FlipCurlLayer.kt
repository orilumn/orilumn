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
import androidx.compose.ui.geometry.Offset
import orilumn.reader.io.Logger
import orilumn.reader.ui.reader.RasterDrawCache
import orilumn.reader.ui.reader.RasterTexture

/** 3D 卷曲总开关：关掉只用于排查（退回 2D 折页）。 */
private const val USE_CURL3D: Boolean = true

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
    /**
     * 正文/插图/盒背景的页栅格（版心大小）；封面槽放的是烤好的**整页**图（偏移见 `FlipCurlLayer`）。
     * 带上内容代数（[RasterTexture]）：页栅格是每次新分配的快照，代数恒 0。
     */
    var page: RasterTexture? by mutableStateOf(null)

    /**
     * 旁注 + 页码（整页大小、透明底）。没有旁注也没有页码时为 null。
     * 带上内容代数：覆盖层位图会在原地重画（见 `PageOverlayLayer`），代数变了才是「这一版」。
     *
     * 卷曲网格**不单独画它**：透明底经 `drawVertices` 会把卷起来的纸刷白，
     * 所以与页栅格先合成一张整页图（见 [CurlPageBake]）。
     */
    var overlay: RasterTexture? by mutableStateOf(null)

    /** 网格路径的整页烤图（页栅格 + 覆盖层），只在有覆盖层时用。 */
    internal val bake: CurlPageBake = CurlPageBake()

    /** 纹理画笔缓存（`ImageShader` 只跟图的**这一版像素**有关，见 [CurlTextureCache]）。 */
    internal val textures: CurlTextureCache = CurlTextureCache()

    /** 整页贴图缓存（桌面每帧重建纹理的代价见 [RasterDrawCache]）。 */
    internal val pageRaster: RasterDrawCache = RasterDrawCache()

    /** 覆盖层（旁注 + 页码）贴图缓存，同上。 */
    internal val overlayRaster: RasterDrawCache = RasterDrawCache()
}

/**
 * 卷曲层（用户层）：两个页槽常驻组合，翻页时把两张页图交给 3D 卷曲网格；网格交不出来时
 * 退回 2D 折页（[drawCurlFrame]）。
 *
 * ## 画法
 *
 * 主路径是 [drawCurl3DFrame]：照搬 moon+ `NewCurl3D` 的扫描线网格，纸真的卷成一个筒
 * （纹理走 `Canvas.drawVertices`，明暗 / 压淡 / 落影走渐变画笔）。兜底路径逐行对位旧版
 * `orilum.bak` 的 `CurlView.drawBody`：一张刚性纸绕折痕翻过去，屏幕上只有三块——
 * 正面页 / 纸背（正面的镜像）/ 露出页（折痕另一侧的新页）。
 *
 * **书脊恒在左**：两种翻页方向都用同一侧卷曲几何（[curlFrame]），上一页只是同一套几何
 * 倒着走并把被翻的那张纸换成目标页。不会因为要翻上一页就让右缘变成书脊。
 *
 * 两端的像素与静止时**同一份**：页槽里的 `ReaderPageCanvas` 每帧把「页栅格 + 覆盖层」
 * 画出来，同时经 `onImages` 发布到 [CurlPageImages]，本层拿它们当纹理用。
 * 所以页码、旁注、高亮、下划线都跟着纸一起走，不需要第三条截图通道。
 *
 * ## 时序（宁可等图，不换一种翻法）
 *
 * 两张页图都在才画；缺图时**页面保持不动**（槽照常显示当前页）——不切滑动、不冻帧。
 *
 * ## 绘制顺序
 *
 * 目标页槽在下、当前页槽在上（与 `FlipSlideLayer` 同序）。卷曲层整页不透明地盖在最上面，
 * 两个槽只是「图从哪来」，不再直接可见。
 *
 * @param direction 翻页方向（+1 下一页 / -1 上一页）
 * @param progress 归一化进度（0..1）
 * @param lean 折痕倾斜量（0 = 竖直折痕，见 `FlipController.curlLean`）：拖动中是
 *   拖动方向换算出来的**斜率**（`curl/leanOf`），斜拖时折痕垂直于手指轨迹；结算期间保持
 *   松手那一刻的值
 * @param pull 本次拖动的起点与此刻手指（页坐标）；非 null 时折痕跟着手指反解，让手上
 *   那张纸的边压在手指上（见 `curl/anchoredFoldX`）。上一页忽略它。
 * @param currentTex/targetTex 页栅格在页坐标里的左上角（页槽自己的事：正文是版心、
 *   封面这种整页烤图是 0,0）；覆盖层恒为整页、贴 (0,0)
 *
 * 三个动画量都是闭包：只在 draw 相位读，动画期间不重组整个阅读面。
 */
@Composable
fun FlipCurlLayer(
    direction: () -> Int,
    progress: () -> Float,
    lean: () -> Float,
    pull: () -> CurlPull?,
    pageWidthPx: Float,
    pageHeightPx: Float,
    active: () -> Boolean,
    paperArgb: Int,
    currentTex: Offset,
    targetTex: Offset,
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
                // 整页烤图（平板口径约 20MB）只服务这一次翻页，翻完就丢。
                current.bake.clear()
                target?.bake?.clear()
                return@Canvas
            }
            val dir = direction()
            // 拖动只读一次：日志与几何必须取自同一次读取，否则对账时对不上。
            val pullNow = pull()
            // 书脊恒在左：几何永远朝同一侧卷（见 [curlFrame]），上一页只是同一套几何倒着走，
            // 并把被翻的那张纸换成目标页——否则纸会挂在右缘，看起来右边成了书脊。
            val frame = curlFrame(
                pageW = pageWidthPx,
                pageH = pageHeightPx,
                progress = progress(),
                direction = dir,
                tilt = lean(),
                pull = pullNow,
            )
            // 正面 = 被翻的那一张；露出页 = 折痕另一侧的那一张。
            // 下一页翻当前页、露出目标页；上一页翻目标页（上一页）、露出当前页。
            val front = if (frame.turningIsTarget) target else current
            val back = if (frame.turningIsTarget) current else target
            // 纹理位置跟着**页槽**走，不跟着「正/背」走：正面是哪一槽由翻页方向决定。
            val frontTex = if (frame.turningIsTarget) targetTex else currentTex
            val backTex = if (frame.turningIsTarget) currentTex else targetTex
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
            val shape = frame.shape
            if (!drawLogged[0]) {
                drawLogged[0] = true
                // 几何一起记：平板实测对不上形状时，直接拿这几个数离线复现同一帧。
                Logger.d(
                    "Orilumn.Curl",
                    "draw dir=$dir p=${shape.progress} gesture=${progress()} " +
                        "sheet=${if (frame.turningIsTarget) "target" else "current"} " +
                        "fold=(${shape.topX},0)-(${shape.bottomX},${pageHeightPx}) " +
                        "lean=${shape.lean} fade=${shape.shadowFade} " +
                        "pull=${pullNow?.let { "(${it.from.x},${it.from.y})→(${it.to.x},${it.to.y})" } ?: "none"} " +
                        "anchor=${pullNow?.anchor ?: "-"} blend=${pullNow?.blend ?: "-"} " +
                        "page=${pageWidthPx}x${pageHeightPx} " +
                        "tex=${front.page?.image?.width}x${front.page?.image?.height} " +
                        "overlay=${front.overlay != null}/${back.overlay != null}",
                )
            }
            // 3D 卷曲是主路径：网格交不出来（这一帧没有可掀起的材料）时才退回折页。
            if (USE_CURL3D) {
                val done = drawCurl3DFrame(
                    shape = shape,
                    front = front,
                    back = back,
                    paperArgb = paperArgb,
                    frontTex = frontTex,
                    backTex = backTex,
                )
                if (done) return@Canvas
            }
            drawCurlFrame(
                shape = shape,
                front = front,
                back = back,
                paperArgb = paperArgb,
                frontTex = frontTex,
                backTex = backTex,
            )
        }
    }
}
