package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground

/**
 * S28 阅读画布「光栅化缝」：把「行窗口如何变成像素」与阅读面几何收口成一个平台 actual。
 *
 * CMP 1.7 的 [Canvas.nativeCanvas] 在不同目标暴露不同底层画布：Desktop 上是 skia
 * [org.jetbrains.skia.Canvas]（可直接交给 [orilumn.reader.engine.skia.LineWindowDrawer]），
 * Android 上仍是 `android.graphics.Canvas`（无法从中取出 skia Canvas）。因此：
 *  - jvmMain（Desktop）：直接用 skia Canvas 走 [orilumn.reader.engine.skia.LineWindowDrawer]。
 *  - androidMain：用 skia 离屏 [org.jetbrains.skia.Surface] 光栅化，再把像素桥回
 *    `android.graphics.Bitmap` 绘制到 Compose 画布——两条路径共用同一 FontCollection/断行。
 *
 * 语义约定（与 [ReaderPageCanvas] 相同）：[lines] 已是**页面坐标系**（章节绝对 Y 已平移），
 * 画进内容区并裁剪在 [contentRectLeft/Top/Right/Bottom]（物理 px）内。
 */
interface ReaderPageRenderer {
    /**
     * @param pageBg 页面底色（ARGB Int，与 Compose `Color(profile.bgColor)` 同一值）：
     *   Android 离屏 surface 用它打底（JPEG 无 alpha，不打底透明区变黑）；Desktop 直画忽略。
     * @param backgrounds 盒背景/边框（已是页面坐标系，与 [lines] 同一平移）：渲染器画在文字之下；
     *   Android 必须画进离屏 surface（成品位图不透明，画在 Compose 层会被盖住）。
     * @param bgImages P3-b 背景图（键为 [PageBackground.bgKey]，缺失即該幅只留底色）。
     */
    fun drawLines(
        canvas: Canvas,
        contentLeft: Float,
        lines: List<DrawLine>,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground> = emptyList(),
        bgImages: Map<String, DecodedImage> = emptyMap(),
        /**
         * P0a：**整页插图下沉进渲染器**（此前由 `ReaderPageCanvas` 在 Compose 层另画）。
         *
         * 下沉的理由不是洁癖，是**像素单一真相源**：翻页动画要拿「整页一张位图」当纹理，
         * 插图若留在 Compose 层，截图就丢图、且静止画面与动画像素不一致（卷曲时插图
         * 不跟着卷 = 露馅）。绘制语义与旧 Compose 路径逐项同形（`dstLeft = contentLeft + xLeft`，
         * `dstTop = yTop - shift`，`decoded == null` 画灰色占位，绝不静默空白）。
         */
        images: List<PageImageSlot> = emptyList(),
        /**
         * 版式版本号（调用方 `ReaderScreen.contentRevision` 同源）：
         * 字重这类"只换字形、不断行"的变更会产出与当前页结构完全相等的行数据，
         * 相等即被跳过/缓存命中、零像素重画；版本号递增即强制重走绘制
         * （绘制仍读 live 锚点，见 `SkParagraphFactory.anchoredWeight`）。
         */
        contentRevision: Int = 0,
        /**
         * P0b：页身份键。非 null 即参与 [PageRasterCache]（多页 LRU）；null = 不缓存
         * （封面等无页身份的调用方直接重画）。键必须含视口/修订号/颜色，理由见 `PageRasterCache.kt`。
         */
        rasterKey: PageRasterKey? = null,
        /**
         * 未命中、即将真正栅格化时调用一次（宿主接后台抢占钩子，让第 7 档后台预排让路 ——
         * 见 `线程调度原则.md` §5「唯一钩子」与 §3.6；栅格属总则第 1 档的「渲染」半边）。
         * 命中路径不调。默认 null = 不抢占。
         */
        onMiss: (() -> Unit)? = null,
    )

    /**
     * **预栅格**：[drawLines] 的无 UI 版本——把一页画进离屏 surface 并入
     * [PageRasterStore]，之后该页首次 [drawLines] 即命中缓存、零栅格开销。
     *
     * ## 为什么需要它（`线程调度原则.md` §3 阶梯缺的那一档）
     *
     * 阶梯第 2/3/4 档全是**排版**，产出 `pageLines` 版式数据；栅格这一档一直不在体系内，
     * 于是像素只在页面**被绘制时**才生产。实测单页栅格 p50 **143ms**、max 977ms，而翻页
     * 时 `target-ready` p50 59ms ——用户手指按下后要先等这一张位图才动得了。
     *
     * 但预排的下一页版式**用户下一步就要用**（§0 紧急度原则），像素该同号跟进而非等绘制。
     * 所以在预排完成的那一刻栅格一次，成本落在「用户还在读当前页」时，而不是手指上。
     *
     * ## 为什么不能直接复用 [drawLines]
     *
     * 它要一个 Compose `Canvas` 才能画，而预栅格发生在**没有画布**的后台时刻。
     * 本方法自己开离屏 surface 画完整页，产物按 [rasterKey] 入同一个 store。
     *
     * ## 线程：**限 UI/渲染线程**
     *
     * 与 [drawLines] 共用 store（及其 surface），所以必须在 UI 线程调用。调用方负责
     * 择时（如预排完成回调、落定后的空闲帧），不得从引擎后台线程直接调。
     * 引擎侧只抛「该页可栅格」的信号（见 `ReaderHost.onPrefillReady`），执行在此层。
     *
     * @return true = 已入池（后续 [drawLines] 会命中）；false = 未入池（缺 key/栅格失败），
     *   调用方应记日志：预栅格失败只影响手感（退化为实时栅格），不影响正确性。
     */
    fun preraster(
        lines: List<DrawLine>,
        contentLeft: Float,
        contentRectLeft: Float,
        contentRectTop: Float,
        contentRectRight: Float,
        contentRectBottom: Float,
        pageBg: Int,
        backgrounds: List<PageBackground> = emptyList(),
        bgImages: Map<String, DecodedImage> = emptyMap(),
        images: List<PageImageSlot> = emptyList(),
        contentRevision: Int = 0,
        rasterKey: PageRasterKey?,
    ): Boolean
}

/**
 * 一个 `<img>` 槽位（几何 + 解码结果，缺图显式为 null）。
 *
 * 「缺图」不是被跳过的条目，而是**画灰色占位**的条目（复刻旧 `drawPageImage` 的 `:136`
 * 语义）——所以这里用槽位而不是 `Map<PageImage, DecodedImage>`：map 表达不了
 * 「该位置有图但还没解出来」，而那正是首帧最常见的中间态。
 */
data class PageImageSlot(
    val image: orilumn.reader.engine.skia.PageImage,
    /** 解码结果；null = 还没解出来（画灰色占位）。skia Image 无值相等 ⇒ 按实例比。 */
    val decoded: DecodedImage?,
)

/**
 * skia Image → Compose [ImageBitmap] 的最后一跳（平台 actual）。
 *
 * - jvmMain：skia Image 本身就是 Compose 位图的 backing，零拷贝；
 * - androidMain：`android.graphics.Bitmap` 与 skia 内存布局不同，必须过一次像素搬运
 *   （skia `Image.readPixels` → RGBA_8888 字节 → `Bitmap.copyPixelsFromBuffer`），
 *   取代此前"JPEG 编解码单跳"（实测 ~100ms/页，见 `docs/翻页动画设计.md` §3.2 S1）。
 */
expect fun skiaImageToImageBitmap(image: org.jetbrains.skia.Image): ImageBitmap?

@Composable
expect fun rememberReaderPageRenderer(): ReaderPageRenderer