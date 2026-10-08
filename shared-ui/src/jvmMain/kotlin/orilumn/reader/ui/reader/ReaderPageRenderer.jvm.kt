package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toComposeImageBitmap
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.skia.PageBackground
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Surface as SkiaSurface

/**
 * Desktop（JVM）actual：**离屏栅格整页 → skia Image → 画布**。
 *
 * P0a 之前这里是**直画**（行窗口直接交给 `LineWindowDrawer`，无离屏、无位图）。
 * 改成离屏不是为了好看，是三条硬需求：
 *  1. 翻页动画要「整页一张图」当纹理，桌面原来根本没有这个东西；
 *  2. 插图已下沉进渲染器（与 Android 同形），若仍直画就会「插图在 Compose 层、文字在离屏层」
 *     双真相，卷曲时插图不跟着卷；
 *  3. 有了整页图就能挂页缓存，同页重组零栅格化（与 Android 同一口径）。
 *
 * 代价：每页多一次整页拷贝（1200×1800 约 8.6MB memcpy，毫秒级）。绘制顺序/几何见
 * [drawPageContent]（两端共用一份，桌面与平板不再各画各的）。
 *
 * 平台差异只剩最后一跳：本端 `nativeCanvas` 就是 skia Canvas，缓存直接持 skia [SkiaImage]
 * 并按 1:1 贴回（动画层要纹理时也正是它，Skia runtime effect 可直接当子着色器）；
 * Android 那边 `nativeCanvas` 是 `android.graphics.Canvas`，必须桥成 `android.graphics.Bitmap`
 * （见 androidMain 同名 actual）。
 */
@Composable
actual fun rememberReaderPageRenderer(): ReaderPageRenderer = remember { JvmReaderPageRenderer() }

/** skia Image → Compose [ImageBitmap]（Desktop actual，零拷贝 backing）。 */
actual fun skiaImageToImageBitmap(image: SkiaImage): ImageBitmap? = runCatching {
    if (image.width <= 0 || image.height <= 0) return@runCatching null
    image.toComposeImageBitmap()
}.getOrNull()

private class JvmReaderPageRenderer : ReaderPageRenderer {
    private val drawer = LineWindowDrawer()

    private var surface: SkiaSurface? = null
    private var width = -1
    private var height = -1
    private var cachedLines: List<DrawLine>? = null
    private var cachedBg: Int = 0
    private var cachedBgs: List<PageBackground>? = null
    private var cachedBgImgKeys: Set<String>? = null
    private var cachedImages: List<PageImageSlot>? = null
    private var cachedRevision: Int = -1
    private var cachedPage: SkiaImage? = null

    override fun drawLines(
        canvas: Canvas,
        contentLeft: Float,
        lines: List<DrawLine>,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground>,
        bgImages: Map<String, DecodedImage>,
        images: List<PageImageSlot>,
        contentRevision: Int,
    ) {
        val w = (contentRectRight - contentRectLeft).toInt().coerceAtLeast(1)
        val h = (contentRectBottom - contentRectTop).toInt().coerceAtLeast(1)
        if (surface == null || w != width || h != height) {
            width = w
            height = h
            surface?.close()
            surface = SkiaSurface.makeRasterN32Premul(w, h)
            cachedLines = null
            cachedPage?.close()
            cachedPage = null
        }
        val bgKeys = backgrounds.mapNotNullTo(HashSet()) { it.bgKey()?.takeIf { k -> bgImages.containsKey(k) } }
        var page = cachedPage?.takeIf {
            pageBg == cachedBg && backgrounds == cachedBgs && lines == cachedLines &&
                bgKeys == cachedBgImgKeys && images == cachedImages && contentRevision == cachedRevision
        }
        if (page == null) {
            val s = surface!!
            val yOff = contentRectTop.toInt()
            drawPageContent(
                canvas = s.canvas,
                contentLeft = contentLeft - contentRectLeft,
                lines = if (yOff != 0 && lines.isNotEmpty()) {
                    lines.map { it.copy(yTop = it.yTop - yOff, yBottom = it.yBottom - yOff) }
                } else {
                    lines
                },
                backgrounds = if (yOff != 0 && backgrounds.isNotEmpty()) {
                    backgrounds.map { it.copy(yTop = it.yTop - yOff, yBottom = it.yBottom - yOff) }
                } else {
                    backgrounds
                },
                bgImages = bgImages,
                images = images,
                pageBg = pageBg,
                w = w,
                h = h,
                drawer = drawer,
            )
            page = s.makeImageSnapshot()
            cachedLines = lines
            cachedBg = pageBg
            cachedBgs = backgrounds
            cachedBgImgKeys = bgKeys
            cachedImages = images
            cachedRevision = contentRevision
            cachedPage?.close()
            cachedPage = page
        }
        // 1:1 贴回（栅格尺寸 == 内容区尺寸，故无缩放；坐标取整避免亚像素抖动）。
        canvas.skiaCanvas.drawImage(page, contentRectLeft.toInt().toFloat(), contentRectTop.toInt().toFloat())
    }
}