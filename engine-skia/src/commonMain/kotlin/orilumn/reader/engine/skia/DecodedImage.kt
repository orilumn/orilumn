package orilumn.reader.engine.skia

import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import kotlin.math.roundToInt

/**
 * 统一图片载体（E1）。平板原先的 BitmapFactory/BitmapHolder 解码路径与桌面 skia 解码在此合并为
 * 「skia 解码结果」单一类型，shared-ui 与各壳共用；Compose 位图经共享
 * [orilumn.reader.ui.imageBitmapOf] 接缝产出（旧 PNG 单跳桥 R3 已删）。
 */
class DecodedImage(val image: Image) {
    val width: Int get() = image.width
    val height: Int get() = image.height
}

/**
 * E1 — 图片解码统一走 Skia（`Image.makeFromEncoded` / `Codec`，skiko 0.9.2）。
 * 与旧 Android 采样管线的行为对齐：
 *  - [probeBounds] 只读尺寸（解码 header，不分配像素），替代 `inJustDecodeBounds`；
 *  - [decodeScaled] 解码并缩放到精确目标宽（源图太宽下采样、太窄上采样），
 *    替代 `inSampleSize` 粗采样 + `createScaledBitmap` 精缩放。
 */
object ImageCodec {

    /** 解码失败 / 非图片字节返回 null，方便布局期安全探测。 */
    fun probeBounds(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val info = Codec.makeFromData(Data.makeFromBytes(bytes)).imageInfo
        val w = info.width
        val h = info.height
        if (w <= 0 || h <= 0) null else w to h
    }.getOrNull()

    /** 全量解码。 */
    fun decode(bytes: ByteArray): DecodedImage? = runCatching {
        val img = Image.makeFromEncoded(bytes) ?: return@runCatching null
        DecodedImage(img)
    }.getOrNull()

    /**
     * 解码并缩放到 [targetWidth] 宽（保留纵横比）。**只缩不放**：`targetWidth <= 0` 或
     * `targetWidth >= 源宽` 时直接返回全量解码结果。缩放走 CPU 栅格 surface 的 `drawImageRect`
     * （Mitchell 采样），产物仍是 [DecodedImage]。
     *
     * 为什么明确不放大：源图没有的细节，放大只是伪造像素。解码期把一张 569×245 的小图拉到
     * 4880×2101（8.6×，ARGB = 41 MB，Mitchell 重采样 409 ms）纯粹是白烧内存与时间——而绘制侧
     * `PageRaster.drawPageImages` 本就用 `drawImageRect(源尺寸 → used 尺寸)` 把整张图映射进目标
     * 矩形，**不要求两者相等**，所以放大拿不到任何视觉回报。
     *
     * （此前的 `full.width == targetWidth` 判等只挡住了「恰好相等」，`targetWidth` 更大时照放不误；
     * `ImageCodecTest.decodeScaledUpscaleKeepsTargetWidth` 就是那条旧契约，已随之改判。）
     */
    fun decodeScaled(bytes: ByteArray, targetWidth: Int): DecodedImage? {
        val full = decode(bytes) ?: return null
        if (targetWidth <= 0 || full.width <= 0 || targetWidth >= full.width) return full
        val newH = maxOf(1, (full.height * targetWidth.toFloat() / full.width).roundToInt())
        val surface = Surface.makeRasterN32Premul(targetWidth, newH)
        surface.canvas.drawImageRect(
            full.image,
            Rect.makeWH(full.width.toFloat(), full.height.toFloat()),
            Rect.makeWH(targetWidth.toFloat(), newH.toFloat()),
            SamplingMode.MITCHELL,
            Paint(),
            false,
        )
        return DecodedImage(surface.makeImageSnapshot())
    }

    /** 编码为 PNG（标量通道；封面落盘、测试产数用）。 */
    fun encodePng(image: Image): ByteArray? =
        runCatching { image.encodeToData(EncodedImageFormat.PNG, 90)?.bytes }.getOrNull()
}