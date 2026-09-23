package orilumn.reader.ui.reader

import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.book.BookRepository
import orilumn.reader.data.epub.TocItem
import orilumn.reader.engine.BookDocumentController
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Q1-b 平板阅读宿主：shared-ui [ReaderHost] 的 Android 实现，底层复用现有
 * [BookDocumentController]（same 单源：翻页/跳读/进度/章节切换全走 controller 的既有语义，
 * 与 legacy `ReaderActivity` 手势管线并行）。
 *
 * 与桌面宿主的差异（最小壳口径）：
 *  - 开书/定位前，调用方须先构建好 controller 并 [BookDocumentController.setViewport] 设置视口，
 *    宿主 [open] 只做「打开 → prewarm → locateStart」的收口（保证 `pageLines` 的窗口与当前视口
 *    同参数）；
 *  - [pageLines] 从 chapter-drawable 的 [orilumn.reader.engine.text.BoxPageRenderer.skiaLineWindow] 按
 *    slice 行区间切片（章节绝对 Y，[orilumn.reader.ui.reader.ReaderPageCanvas] 平移到页坐标系）；
 *  - 增量/临时（大章锚点）页同样投影 skia 窗口（engine `buildPartialSkiaWindow`），三条路径
 *    （canonical/增量/临时）全绘，不再有空白页；
 *  - 跳转类操作（neighborChapterStart/pageAtFraction/chapterStart/openTocItem）跳前先
 *    finalizeOnLeave（复刻 legacy `jumpGate` 语义：离开→废除临时表、启用磁盘分页表 S5）；
 *  - 进度持久化 = controller 的 finalizeOnLeave + Room readingState（复刻 legacy `saveReadingStateNow`
 *    的 locator JSON 格式）。
 */
class TabletReaderHost(
    private val controller: BookDocumentController,
    private val bookId: Long,
    private val repository: BookRepository,
) : ReaderHost {

    private val scopeJob = SupervisorJob()
    private val ioScope = CoroutineScope(scopeJob + Dispatchers.IO)

    private var opened = false

    // ---- ReaderHost ----

    override fun title(): String = controller.title()

    override fun unitTitle(chapter: Int): String {
        val unit = controller.unitAt(chapter) ?: return ""
        return unit.title
            .ifEmpty { unit.hrefName.substringAfterLast('/').substringBefore('#').ifEmpty { "第 ${chapter + 1} 章" } }
    }

    override fun pageCount(chapter: Int): Int = controller.unitAt(chapter)?.pageSlices?.size ?: 0

    override fun tocIndex(chapter: Int): Int =
        flatToc().indexOfFirst { it.index == chapter }.takeIf { it >= 0 } ?: chapter

    override suspend fun open(): ReaderPos? = withContext(Dispatchers.IO) {
        if (!opened) {
            if (!controller.open(bookId, repository.readingState(bookId))) return@withContext null
            controller.prewarmForOpen()
            opened = true
        }
        controller.locateStart()?.let { ReaderPos(it.first, it.second) }
    }

    override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? = withContext(Dispatchers.IO) {
        controller.findAdjacentPage(pos.chapter, pos.slice, direction)?.let { ReaderPos(it.first, it.second) }
    }

    override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? =
        withContext(Dispatchers.IO) {
            // 复刻 legacy jumpGate：离开当前章前先 finalizeOnLeave（废除临时表、启用磁盘分页表）。
            controller.finalizeOnLeave(chapter)
            controller.neighborChapterStart(chapter, direction)?.let { ReaderPos(it.first, it.second) }
        }

    override suspend fun pageAtFraction(fraction: Double): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.pageAtFraction(fraction)?.let { ReaderPos(it.first, it.second) }
    }

    override suspend fun chapterStart(index: Int): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.openChapterStart(index)?.let { ReaderPos(it.first, it.second) }
    }

    /** 目录跳转（Android 专用）：finalizeOnLeave 后经 [BookDocumentController.openTocItem] 落位，
     *  继承索引 + 片段的既有语义（方向跳转走 [neighborChapterStart]）。 */
    suspend fun jumpToToc(index: Int, fragment: String?): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.openTocItem(index, fragment)?.let { ReaderPos(it.first, it.second) }
    }

    /** P4-c2u: 点按命中的链接查表（控制器轻路径样式化区间，同步廉价）。 */
    override fun linkTargetAt(chapter: Int, charOffset: Int): orilumn.reader.engine.LinkTarget? =
        controller.linkTargetAt(chapter, charOffset)

    /** P4-c2u: 跟随链接（离开当前章先 finalizeOnLeave，与目录跳转同口径）。 */
    override suspend fun openLink(target: orilumn.reader.engine.LinkTarget): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: target.chapterIndex
        controller.finalizeOnLeave(from)
        controller.openLinkTarget(target)?.let { ReaderPos(it.first, it.second) }
    }

    override fun pageProgress(pos: ReaderPos): Double = controller.pageProgress(pos.chapter, pos.slice)

    override fun pageLines(pos: ReaderPos): List<DrawLine>? = controller.pageLines(pos.chapter, pos.slice)

    override fun pageImages(pos: ReaderPos): List<orilumn.reader.engine.skia.PageImage>? =
        controller.pageImages(pos.chapter, pos.slice)

    override fun pageBackgrounds(pos: ReaderPos): List<orilumn.reader.engine.skia.PageBackground>? =
        controller.pageBackgrounds(pos.chapter, pos.slice)

    // P3-b: 背景图直解（controller 复用 zip 管线/ImageCodec；失败回 null，該幅只留底色）。
    override suspend fun loadBackgroundImage(chapterHref: String, src: String): DecodedImage? =
        withContext(Dispatchers.IO) { controller.loadBackgroundImage(chapterHref, src) }

    override suspend fun loadPageImage(img: orilumn.reader.engine.skia.PageImage): androidx.compose.ui.graphics.ImageBitmap? =
        withContext(Dispatchers.IO) {
            // 直解原字节（BitmapFactory 采样）：skia decodeScaled→PNG→BitmapFactory 的往返
            // 对摄影类大 JPEG 既慢又易失败（encode 回 null / 大 PNG 解码 null），灰块主因。
            // 这里按 targetWidth 粗采样 + 精缩放，一次到位；失败才回退旧 PNG 桥。
            val raw = controller.loadPageImageRaw(img)
            if (raw != null) {
                decodeSampled(raw, img.widthPx.coerceAtLeast(1))?.let { return@withContext it }
            }
            val png = controller.loadPageImageBytes(img) ?: return@withContext null
            runCatching {
                android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                    ?.asImageBitmap()
            }.getOrNull()
        }

    override fun onSaveProgress(pos: ReaderPos) {
        // fire-and-forget：阅读面已防抖 500ms，这里只做 leave 收口 + 落盘（复刻 legacy scheduleSave）。
        // C1-2 存档顺序契约：先 finalizeOnLeave（临时表转正/作废）再读 displayed slice 落盘，
        // 持久化的 char 恒是 canonical 权威（磁盘表就绪时），见 finalizeOnLeave。
        ioScope.launch {
            controller.finalizeOnLeave(pos.chapter)
            if (bookId < 0) return@launch
            val progress = controller.pageProgress(pos.chapter, pos.slice)
            val locator = "{\"v\":1,\"chapter\":${pos.chapter},\"char\":${pos.slice.charStart}}"
            repository.saveReadingState(
                BookReadingState(
                    bookId = bookId,
                    chapter = pos.chapter,
                    progress = progress,
                    locator = locator,
                ),
            )
        }
    }

    /**
     * 按目标宽直解 [bytes]：先只读头取尺寸算 2 的幂采样率粗解，再精缩到目标宽
     * （与退役 BitmapFactory 管线同语义）。失败回 null，调用方回退旧桥。
     */
    private fun decodeSampled(
        bytes: ByteArray,
        targetWidth: Int,
    ): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val bounds = android.graphics.BitmapFactory.Options().also { it.inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return@runCatching null
        var sample = 1
        if (targetWidth > 0) {
            while (rawW / (sample * 2) >= targetWidth) sample *= 2
        }
        val opts = android.graphics.BitmapFactory.Options().also {
            it.inSampleSize = sample
            it.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            ?: return@runCatching null
        val tw = targetWidth.coerceAtLeast(1)
        // 粗采样后仍宽于目标（或窄于目标）→ 精缩到目标宽等比高；已吻合则直接用。
        if (bmp.width != tw) {
            val th = ((bmp.height * tw.toFloat() / bmp.width).toInt()).coerceAtLeast(1)
            val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, tw, th, true)
            if (scaled !== bmp) runCatching { bmp.recycle() }
            scaled.asImageBitmap()
        } else {
            bmp.asImageBitmap()
        }
    }.getOrNull()

    /** 目录扁平化（与桌面同一语义：焦点下标即扁平序）。 */
    private fun flatToc(): List<TocItem> {
        val out = ArrayList<TocItem>()
        fun walk(items: List<TocItem>) {
            for (t in items) {
                out.add(t)
                walk(t.children)
            }
        }
        walk(controller.toc())
        return out
    }
}