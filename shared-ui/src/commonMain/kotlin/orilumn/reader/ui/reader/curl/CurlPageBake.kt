package orilumn.reader.ui.reader.curl

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import orilumn.reader.ui.reader.RasterTexture
import kotlin.math.roundToInt

/**
 * 交给卷曲网格的一张**不透明整页**贴图 + 它在页坐标里的左上角。
 *
 * 正文页栅格只占版心（左上角非 0），封面与烤图是整页（左上角 0）。
 */
internal class CurlMeshTexture(
    val texture: RasterTexture,
    val texLeft: Float,
    val texTop: Float,
)

/**
 * 网格路径的贴图：页栅格与覆盖层**先合成成一张不透明整页图**（[CurlPageBake.textureOf]）。
 *
 * ## 为什么不在网格上分两遍画
 *
 * 覆盖层（旁注 + 页码）是**透明底**的整页图。第二遍用 `drawVertices` 把它贴到卷起来的纸上时，
 * 透明像素在桌面端会被「抬」成顶点色（白）：卷起的那张纸整个刷成纯白，只剩旁注还看得见 ——
 * 读者报的「反面没有文字，只有批注」就是它。合成一张**不透明**整页图就没有透明像素可抬，
 * 两个平台的绘制语义也就一致了（顶点色 × 着色器 / 顶点色打底，两种实现的结果都是这张图本身）。
 *
 * 顺带还省掉每帧一遍 `drawVertices`：一帧原来要贴「页栅格 + 覆盖层」两次。
 *
 * ## 内存
 *
 * 一张整页合成图在平板口径约 20MB，所以只在**有覆盖层**时才烤，翻页一结束就丢
 * （见 [clear]）：烤图只服务「这一张纸的这一次翻页」，没有跨翻页复用的价值。
 */
internal class CurlPageBake {

    private var baked: CurlMeshTexture? = null
    private var fromPage: RasterTexture? = null
    private var fromOverlay: RasterTexture? = null
    private var paperArgb: Int = 0
    private var pageW: Int = 0
    private var pageH: Int = 0
    private var texLeft: Float = 0f
    private var texTop: Float = 0f

    /** 累计真正烤过几张（诊断用，见 [CurlPerf]）；只增不减，会话结束时由调用方取差值。 */
    internal var bakedCount: Int = 0
        private set

    /** 累计烤图耗时（纳秒，诊断用）。 */
    internal var bakedNanosTotal: Long = 0L
        private set

    /**
     * 网格要贴的那张图：没有覆盖层就是页栅格本身（偏移照旧），有覆盖层就烤一张整页图。
     *
     * @param pageW/pageH 页尺寸（像素，烤图与页同尺寸）
     * @param texLeft/texTop 页栅格在页坐标里的左上角（页槽自己的事，见 `FlipCurlLayer`）
     */
    fun textureOf(
        images: CurlPageImages,
        pageW: Float,
        pageH: Float,
        paperArgb: Int,
        texLeft: Float,
        texTop: Float,
    ): CurlMeshTexture? {
        val page = images.page ?: return null
        val overlay = images.overlay ?: return CurlMeshTexture(page, texLeft, texTop)
        val w = pagePixel(pageW)
        val h = pagePixel(pageH)
        baked?.let { cached ->
            if (fromPage == page && fromOverlay == overlay && this.paperArgb == paperArgb &&
                this.pageW == w && this.pageH == h &&
                this.texLeft == texLeft && this.texTop == texTop
            ) {
                return cached
            }
        }
        // 不透明白底：整页图一旦有透明像素，网格那条路径就会把它画成白色。
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val out = ImageBitmap(w, h, ImageBitmapConfig.Argb8888, hasAlpha = false)
        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(out),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            drawRect(Color(paperArgb))
            drawImage(page.image, topLeft = Offset(texLeft, texTop))
            drawImage(overlay.image, topLeft = Offset.Zero)
        }
        val fresh = CurlMeshTexture(RasterTexture(out), 0f, 0f)
        baked = fresh
        fromPage = page
        fromOverlay = overlay
        this.paperArgb = paperArgb
        this.pageW = w
        this.pageH = h
        this.texLeft = texLeft
        this.texTop = texTop
        bakedCount++
        val took = mark.elapsedNow().inWholeNanoseconds
        bakedNanosTotal += took
        return fresh
    }

    /** 丢掉烤图（翻页结束时调用，见 `FlipCurlLayer`）。 */
    fun clear() {
        baked = null
        fromPage = null
        fromOverlay = null
    }
}

/** 页尺寸取整（网格的纹理坐标是页坐标，整页烤图必须与页同尺寸）。 */
private fun pagePixel(size: Float): Int = size.roundToInt().coerceAtLeast(1)
