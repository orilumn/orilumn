package orilumn.reader.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
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
    /**
     * 与 [lines] 同一切片的 `<img>` 几何（章节绝对 Y）；null/空 = 本页无图。
     * **P0a 起插图不再在此绘制**：它与文字/背景一起下沉进 [ReaderPageRenderer]，
     * 由 [PageImageSlot] 传入（含「有图但未解出」的占位槽位）。
     * 理由是像素单一真相源——翻页动画拿整页位图当纹理，插图留在 Compose 层就会丢图。
     */
    pageImages: List<PageImage>? = null,
    /** 已解码的插图（键与 [pageImages] 同实例/同值）；缺失项由渲染器画灰色占位。 */
    imageBitmaps: Map<PageImage, DecodedImage>? = null,
    /** 与 [lines] 同一切片的盒背景/边框（章节绝对 Y）；null/空 = 本页无背景块。 */
    pageBackgrounds: List<PageBackground>? = null,
    /** P3-b: 背景图解码结果（键为 [PageBackground.bgKey]）；缺失项該幅只留底色。 */
    bgImages: Map<String, DecodedImage> = emptyMap(),
    /**
     * 版式版本号（`ReaderScreen.contentRevision` 同源）：
     * 字重这类"只换字形、不断行"的变更会产出与当前页结构完全相等的行数据，
     * 强跳过下相等即整棵跳过、零像素重画；此处用版本号做 key，
     * 推送即重建画布发射器、必重画（与数据是否相等无关）。
     */
    contentRevision: Int = 0,
) {
    val renderer = rememberReaderPageRenderer()
    // 修订号版本化：只包画布发射器（渲染器实例保留在 key 之外，Android 离屏
    // surface 不重建、其页缓存另经 contentRevision 显式失效，见各 actual）。
    key(contentRevision) {
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
        // P0a：插图与文本同一 shift 一并下沉进渲染器（整页一张位图，动画与静止同像素）。
        // 槽位**逐项保留**（含 decoded=null 的占位槽）——map 表达不了「有图未解出」，
        // 而那正是首帧常态；漏一个就是静默空白。
        val slots = imgs?.map { PageImageSlot(it.copy(yTop = it.yTop - shift, yBottom = it.yBottom - shift), imageBitmaps?.get(it)) }
            .orEmpty()
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
            images = slots,
            contentRevision = contentRevision,
        )
    }
    }
}