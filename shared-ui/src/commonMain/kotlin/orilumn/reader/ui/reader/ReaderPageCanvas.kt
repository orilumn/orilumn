package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage
import kotlin.math.roundToInt
/**
 * S28 阅读画布：「画布」平移——把 [ReaderHost.pageLines] 给出的行窗口经 [ReaderPageRenderer]
 *（平台缝：Desktop 用 skia Canvas / Android 用 skia 离屏，但都共 [orilumn.reader.engine.skia.LineWindowDrawer]
 * 断行与字体集合）画到 CMP Canvas 上，替换掉旧 Android `BodyPageView` 的 StaticLayout 渲染。
 *
 * 几何约定：宿主行是**章节绝对 Y**，本画布按窗口首行 ([ReaderMath.shiftToPageFrame]) 平移到内容区顶部，
 * 再落位到物理内容区（[contentLeft/Top]）；[contentRectLeft/Top/Right/Bottom] 为可视内容区边界，
 * 窗口外行由裁剪丢弃。文字墨色以 [inkColor] 为准：行数据是 `remember(pos)` 锁死的，换主题色常常不触发
 * 重排版（分页键故意排除颜色），绘制时盖章是唯一与重排/缓存路径无关的落墨点；[DrawLine.inkColor]
 * 只做版式侧回退（旧直绘路径）。底色由 [pageBg] 打底（Android 离屏 surface / 桌面 Compose 背景同色）。
 */
@Composable
fun ReaderPageCanvas(
    lines: List<DrawLine>?,
    contentLeft: Float,
    contentTop: Float,
    contentRectLeft: Float,
    contentRectTop: Float,
    contentRectRight: Float,
    contentRectBottom: Float,
    pageBg: Int,
    /** 文本墨色（ARGB Int，调用方喂主题 fgColor）：逐帧盖章，与行数据的版式缓存无关。 */
    inkColor: Int = 0xFF000000.toInt(),
    modifier: Modifier = Modifier,
    /** 与 [lines] 同一切片的 `<img>` 几何（章节绝对 Y）；null/空 = 本页无图。 */
    pageImages: List<PageImage>? = null,
    /** 已解码位图（键与 [pageImages] 同实例/同值）；缺失项画灰色占位，不跳过几何。 */
    imageBitmaps: Map<PageImage, ImageBitmap>? = null,
    /** 与 [lines] 同一切片的盒背景/边框（章节绝对 Y）；null/空 = 本页无背景块。 */
    pageBackgrounds: List<PageBackground>? = null,
    /** P3-b: 背景图解码结果（键为 [PageBackground.bgKey]）；缺失项該幅只留底色。 */
    bgImages: Map<String, DecodedImage> = emptyMap(),
) {
    val renderer = rememberReaderPageRenderer()
    Canvas(modifier = modifier) {
        val list = lines
        val imgs = pageImages?.takeIf { it.isNotEmpty() }
        val rawBgs = pageBackgrounds?.takeIf { it.isNotEmpty() }
        if ((list.isNullOrEmpty()) && imgs == null && rawBgs == null) return@Canvas
        // 章节绝对 Y → 页面坐标系：对齐基准必须覆盖行与图两者。
        // 替换块（img）不产出 DrawLine：若只取行最小值，行窗内首图（gearIndex < 首文本行）
        // 的 yTop 会小于锚点，dstTop 为负而跑到屏外。取两者最小即复刻旧 drawPageSlice
        // alignPageTop(firstLine)（对齐切片首行，含图片行）。
        // 纯图页（无文本行）时以首图为对齐基准。
        val lineMin = list?.takeIf { it.isNotEmpty() }?.minOf { it.yTop }
        val imgMin = imgs?.minOf { it.yTop }
        val bgMin = rawBgs?.minOf { it.yTop }
        val anchorY = listOfNotNull(lineMin, imgMin, bgMin).minOrNull() ?: return@Canvas
        val shift = anchorY - contentTop.roundToInt()
        // 盒背景/边框与文本同一 shift（章节绝对 Y → 页坐标），渲染器画在文字之下。
        val bgs = rawBgs?.map {
            it.copy(yTop = it.yTop - shift, yBottom = it.yBottom - shift)
        }.orEmpty()
        if (!list.isNullOrEmpty() || bgs.isNotEmpty()) {
            val frame = if (!list.isNullOrEmpty()) ReaderMath.shiftToPageFrame(list, shift) else emptyList()
            // 主题墨色盖章：行自带墨色只在“恰好重排过”时才新鲜，绘制时按当前主题统一覆盖，
            // 换色即时生效且不触发布局/分页缓存失效（键里本来就没有颜色）。
            val inked = if (frame.any { it.inkColor != inkColor }) {
                frame.map { if (it.inkColor != inkColor) it.copy(inkColor = inkColor) else it }
            } else {
                frame
            }
            renderer.drawLines(
                canvas = drawContext.canvas,
                contentLeft = contentLeft,
                lines = inked,
                contentRectLeft = contentRectLeft,
                contentRectTop = contentRectTop,
                contentRectRight = contentRectRight,
                contentRectBottom = contentRectBottom,
                pageBg = pageBg,
                backgrounds = bgs,
                bgImages = bgImages,
            )
        }
        // 插图：与文本同一 shift 平移后按盒流 used 尺寸贴图（Compose 层直画，
        // 双平台共用；Android 离屏文本缓存不受影响）。
        imgs?.forEach { img ->
            drawPageImage(img, shift, contentLeft, imageBitmaps?.get(img))
        }
    }
}

/**
 * 单张插图落位：[PageImage.xLeft] 与 [DrawLine.xLeft] 同口径（相对内容区左缘），
 * 故 `dstLeft = contentLeft + xLeft`；Y 与文本共用同一 [shift]（章节绝对 Y → 页坐标）。
 */
private fun DrawScope.drawPageImage(
    img: PageImage,
    shift: Int,
    contentLeft: Float,
    bitmap: ImageBitmap?,
) {
    val w = img.widthPx.coerceAtLeast(1)
    val h = (img.yBottom - img.yTop).coerceAtLeast(1)
    val dstLeft = (contentLeft + img.xLeft).roundToInt()
    val dstTop = img.yTop - shift
    if (bitmap != null) {
        drawImage(
            image = bitmap,
            dstOffset = IntOffset(dstLeft, dstTop),
            dstSize = IntSize(w, h),
        )
    } else {
        // 解码中/失败的灰色占位（与旧 Android drawImageOrPlaceholder 同语义），
        // 保证“有图几何”永远可见，不会静默空白。
        drawRect(
            color = androidx.compose.ui.graphics.Color(0xFFDDDDDD),
            topLeft = Offset(dstLeft.toFloat(), dstTop.toFloat()),
            size = Size(w.toFloat(), h.toFloat()),
        )
    }
}