package orilumn.reader.desktop

import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.epub.TocItem
import orilumn.reader.data.epub.ZipEpubResourceReader
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BookDocumentController
import orilumn.reader.engine.BoxChapterLayouter
import orilumn.reader.engine.ImageLoader
import orilumn.reader.engine.LinkTarget
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageImage
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.ui.reader.ReaderHost
import orilumn.reader.ui.reader.ReaderPos
import orilumn.reader.ui.reader.flattenTocItems
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toPath
import java.io.File

/**
 * S32 桌面阅读宿主：shared-ui [ReaderHost] 的 JVM 实现。
 *
 * C2-P3：与平板 [orilumn.reader.ui.reader.TabletReaderHost] 同构的薄壳 —— 翻页/跳读/
 * 增量编排/进度/存档全走共享 [BookDocumentController]（单编排宿主），本文件只剩
 * 平台接缝：zip 常驻打开、视口/分发器装配、书内字体预装、存档 map、图片解码。
 * 旧 584 行自研简化管线（单章整塑形 + 本地分页表复刻）已删除；后台 canonical/整书
 * 预排按同一 F>A>B1>B2>P 契约启用（`prewarmForOpen`；F 由 `findAdjacentPage` 调用线程
 * 同步塑形闭环，与平板同一语义）。
 */
class DesktopReaderHost(
    private val bookFile: String,
    private val bookId: Long,
    private val store: DesktopShelfStore,
    settings: ReaderSettings,
    private val density: Float,
    /** 全窗视口 px（控制器内部再减边距；与平板 `setViewport` 同语义，调用方传整窗，勿预减）。 */
    private val viewportW: Int,
    private val viewportH: Int,
    /** 打开锚点（章，章内字符）：目录跳转/设置重排的落位；null = 存档定位 → 章首。 */
    private val initialAnchor: Pair<Int, Int>? = null,
    /** 分页表磁盘缓存根（与平板同一共享 Store/参数键/失效语义；null = 禁用）。 */
    private val cacheRoot: File? = null,
    /** F4b 共享字体库（用户字库 + 隐藏；`onDemandFonts` 追装与平板同式）。 */
    private val fontLibrary: orilumn.reader.data.font.FontLibrary,
) : ReaderHost {

    private val profile: TypographicProfile = TypographicProfile.build(settings, density)
    private val scopeJob = SupervisorJob()
    private val hostScope = CoroutineScope(scopeJob + Dispatchers.Default)
    private val ioScope = CoroutineScope(scopeJob + Dispatchers.IO)

    /** zip 常驻打开（控制器解析/取字节/字体全走它；[close] 时关闭）。 */
    private val reader: EpubResourceReader = ZipEpubResourceReader(bookFile)
    private val controller = BookDocumentController(
        reader = reader,
        layouter = BoxChapterLayouter(imageLoader = ImageLoader(reader)),
        profile = profile,
        logTag = "Orilumn.Desktop",
        scope = hostScope,
        imageLoader = ImageLoader(reader),
    ).also {
        it.cacheRoot = cacheRoot?.absolutePath?.toPath()
        it.setViewport(viewportW.coerceAtLeast(16), viewportH.coerceAtLeast(16))
        // F4b：用户字库追装（与平板 `topUpSkiaFonts` 同式；桌面无系统衬线补装，
        // CoreText 经系统集合自行回退）；书内字体见 `onBookFonts`。
        it.onDemandFonts = { demand -> topUpSkiaFonts(demand) }
        it.onBookFonts = { fonts -> syncBookFonts(fonts) }
    }

    private var opened = false

    /** 开屏预装的书内字体全集（与用户面同池；`onBookFonts` 合并后比较，无变化即 false）。 */
    private var bookFontEntries: List<orilumn.reader.engine.skia.SkiaFontPool.EmbeddedFont> = emptyList()

    /** 装池签名（`FontPoolSync` 两段式：命中即翻页零 IO；有加载失败的不记名，下次重试）。 */
    private var lastPoolFaceSig: String? = null

    /** 目录（面板用；open() 后就绪）。 */
    val toc: List<TocItem> get() = controller.toc()

    // ---- ReaderHost ----

    override fun title(): String = controller.title()

    override fun unitTitle(chapter: Int): String {
        val unit = controller.unitAt(chapter) ?: return ""
        return unit.title
            .ifEmpty { unit.hrefName.substringAfterLast('/').substringBefore('#').ifEmpty { "第 ${chapter + 1} 章" } }
    }

    override fun pageCount(chapter: Int): Int = controller.unitAt(chapter)?.pageSlices?.size ?: 0

    override fun tocIndex(chapter: Int): Int {
        flatToc().indexOfFirst { it.index == chapter }.takeIf { it >= 0 }?.let { return it }
        return chapter
    }

    override suspend fun open(): ReaderPos? = withContext(Dispatchers.Default) {
        if (!opened) {
            val saved = store.loadProgress(bookId)?.let { loc ->
                BookReadingState(
                    bookId = bookId,
                    chapter = loc.chapter,
                    locator = orilumn.reader.data.read.ReadingLocatorCodec.encode(loc.chapter, loc.char),
                )
            }
            if (!controller.open(bookId, saved)) return@withContext null
            // 后台 canonical/整书预排（与平板同一分发器划分；落位不等它）。
            // 书内字体不预装：整形前 `onBookFonts` 回调给字节（与平板同式，首绘即对）。
            controller.prewarmForOpen()
            opened = true
        }
        val anchor = initialAnchor
        if (anchor != null) {
            (landAnchor(anchor.first, anchor.second)
                ?: controller.locateStart()?.let { ReaderPos(it.first, it.second) })?.let { landing ->
                // 落位即存档：一次性锚点就此转正，后续重建回退到存档定位。
                ioScope.launch {
                    store.saveProgress(bookId, ReadingLocator(landing.chapter, landing.slice.charStart))
                }
                return@withContext landing
            }
        }
        controller.locateStart()?.let { ReaderPos(it.first, it.second) }
    }

    override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? = withContext(Dispatchers.Default) {
        controller.findAdjacentPage(pos.chapter, pos.slice, direction)?.let { ReaderPos(it.first, it.second) }
    }

    override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? =
        withContext(Dispatchers.Default) {
            controller.finalizeOnLeave(chapter)
            controller.neighborChapterStart(chapter, direction)?.let { ReaderPos(it.first, it.second) }
        }

    override suspend fun pageAtFraction(fraction: Double): ReaderPos? = withContext(Dispatchers.Default) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.pageAtFraction(fraction)?.let { ReaderPos(it.first, it.second) }
    }

    override suspend fun chapterStart(index: Int): ReaderPos? = withContext(Dispatchers.Default) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.openChapterStart(index)?.let { ReaderPos(it.first, it.second) }
    }

    /** P4-c2u: 点按命中的链接查表（控制器轻路径样式化区间，同步廉价）。 */
    override fun linkTargetAt(chapter: Int, charOffset: Int): LinkTarget? =
        controller.linkTargetAt(chapter, charOffset)

    /** P4-c2u: 跟随链接（离开当前章先 finalizeOnLeave，与目录跳转同口径）。 */
    override suspend fun openLink(target: LinkTarget): ReaderPos? = withContext(Dispatchers.Default) {
        val from = controller.locateStart()?.first ?: target.chapterIndex
        controller.finalizeOnLeave(from)
        controller.openLinkTarget(target)?.let { ReaderPos(it.first, it.second) }
    }

    override fun pageProgress(pos: ReaderPos): Double = controller.pageProgress(pos.chapter, pos.slice)

    override fun pageLines(pos: ReaderPos): List<DrawLine>? = controller.pageLines(pos.chapter, pos.slice)

    override fun pageImages(pos: ReaderPos): List<PageImage>? =
        controller.pageImages(pos.chapter, pos.slice)

    override fun pageBackgrounds(pos: ReaderPos): List<orilumn.reader.engine.skia.PageBackground>? =
        controller.pageBackgrounds(pos.chapter, pos.slice)

    override suspend fun loadPageImage(img: PageImage): ImageBitmap? = withContext(Dispatchers.IO) {
        // 取字节走控制器单源（常驻 reader；与平板 `loadPageImageRaw` 同一管线），解码走共享接缝：
        // 与书架封面同一解码口径，失败回 null（阅读面画灰色占位）。
        val bytes = controller.loadPageImageRaw(img) ?: return@withContext null
        orilumn.reader.ui.imageBitmapOf(bytes)
    }

    // P3-b: 背景图按需直解（取字节+解码全走控制器，与平板同一管线；失败回 null，該幅只留底色）。
    override suspend fun loadBackgroundImage(chapterHref: String, src: String): DecodedImage? = withContext(Dispatchers.IO) {
        controller.loadBackgroundImage(chapterHref, src)
    }

    override fun onSaveProgress(pos: ReaderPos) {
        // fire-and-forget：阅读面已防抖 500ms，这里只做 leave 收口 + 落盘（存档顺序契约：
        // 先 finalizeOnLeave 再读 displayed slice，持久化的 char 恒 canonical 权威）。
        ioScope.launch {
            controller.finalizeOnLeave(pos.chapter)
            if (bookId < 0) return@launch
            store.saveProgress(bookId, ReadingLocator(pos.chapter, pos.slice.charStart))
        }
    }

    fun close() {
        scopeJob.cancel()
        runCatching { reader.close() }
    }

    // ---- 平台接缝 ----

    /** 锚点落位（目录跳转/设置重建）：章内字符所在页；无内容回 null（调用方退回 locateStart）。 */
    private suspend fun landAnchor(chapter: Int, char: Int): ReaderPos? {
        val unit = controller.ensureChapterLayout(chapter, char) ?: return null
        val slice = unit.pageSlices.firstOrNull { char >= it.charStart && char < it.charEnd }
            ?: unit.pageSlices.lastOrNull() ?: return null
        return ReaderPos(chapter, slice)
    }

    /**
     * F4b 用户字库追装（与平板 `topUpSkiaFonts` 同式，经共享 [FontPoolSync]）：
     * 控制器整形前回调，集合变化即 true（调用方作废重排，首绘即对）。
     */
    private suspend fun topUpSkiaFonts(demand: orilumn.reader.engine.css.FontDemand): Boolean =
        withContext(Dispatchers.IO) {
            val (changed, sig) = orilumn.reader.engine.skia.FontPoolSync.syncPool(
                profile = profile,
                demand = demand,
                bookEntries = bookFontEntries,
                lastSig = lastPoolFaceSig,
                loadFaces = {
                    runCatching { fontLibrary.reconcileOrphanFiles() }.getOrNull()
                        ?: runCatching { fontLibrary.list() }.getOrNull()
                },
                fileSize = { p -> java.io.File(p).takeIf { it.isFile }?.length() },
                fontBytes = { id -> fontLibrary.fontBytes(id) },
                logTag = "Orilumn.Desktop",
            )
            lastPoolFaceSig = sig
            changed
        }

    /**
     * 整形前书内字体进池（与平板 `syncBookFonts` 同式：集合变化即走统一合并装载重排）。
     */
    private suspend fun syncBookFonts(fonts: List<orilumn.reader.engine.css.BookFont>): Boolean =
        withContext(Dispatchers.IO) {
            bookFontEntries = orilumn.reader.engine.skia.FontPoolSync.mergeBookFonts(bookFontEntries, fonts)
                ?: return@withContext false
            // 走统一合并装载（签名含书内部分，零变化即池不动）。
            topUpSkiaFonts(orilumn.reader.engine.css.FontDemand.EMPTY)
        }

    private fun flatToc(): List<TocItem> = flattenTocItems(toc)
}
