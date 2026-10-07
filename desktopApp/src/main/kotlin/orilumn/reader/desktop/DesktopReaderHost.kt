package orilumn.reader.desktop

import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.data.epub.TocItem
import orilumn.reader.data.epub.ZipEpubResourceReader
import orilumn.reader.data.settings.BookSettings
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
import orilumn.reader.ui.imageBitmapOf
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
 * 预排按同一优先级契约启用（F > 第一邻页 > 第二上页 > B1 > B2 > temp 预填 > P，
 * 全部由共享 `TaskScheduler` 排定；F 由 `findAdjacentPage` 调用线程同步塑形闭环，
 * 与平板同一语义）。
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
    /** 磁盘分页表构建号（与平板 versionCode 同职责：发版递增 → 旧构建的表统一失效；
     *  桌面无 versionCode，手动维护此常量，随发版递增）。 */
    private val diskCacheVersion: Int = DISK_CACHE_VERSION,
    /** F4b 共享字体库（用户字库 + 隐藏；`onDemandFonts` 追装与平板同式）。 */
    private val fontLibrary: orilumn.reader.data.font.FontLibrary,
) : ReaderHost {

    private var profile: TypographicProfile = TypographicProfile.build(settings, density)
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
        it.diskCacheVersion = diskCacheVersion
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

    /** 当页标题 id 集合（目录抽屉定位当前项用；只读透传，与平板直调控制器同口径，用户层）。 */
    fun currentPageFragmentIds(chapter: Int, charStart: Int, charEnd: Int): Set<String> =
        controller.currentPageFragmentIds(chapter, charStart, charEnd)

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
            // 注：open 后默认 prewarm 恒跳过落位章（open 同步塑形，一次为准），此处不再调用。
            // R7: open-book B2 dispatch（epoch 去重；defer 门控在内）——远章不等改参即排（与平板同序）。
            controller.requestWholeBookRelayout()
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

    /** 目录跳转（含子章节 fragment；与平板 `jumpToToc` 同口径，经 engine `openTocItem` 落位）。 */
    suspend fun tocItem(index: Int, fragment: String?): ReaderPos? = withContext(Dispatchers.Default) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.openTocItem(index, fragment)?.let { ReaderPos(it.first, it.second) }
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

    /** 全书封面（ covers/ 私有缓存；无/失败回 null）。 */
    override suspend fun coverImage(): ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val ref = store.getEntry(bookId)?.coverPath ?: return@runCatching null
            val bytes = File(ref).takeIf { it.isFile }?.readBytes() ?: return@runCatching null
            orilumn.reader.ui.imageBitmapOf(bytes)
        }.getOrNull()
    }

    /** 全书第一内容页（只读，不 finalize 临时表）。 */
    override suspend fun bookStart(): ReaderPos? = withContext(Dispatchers.Default) {
        controller.openChapterStart(0)?.let { ReaderPos(it.first, it.second) }
    }

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
        // fire-and-forget：阅读面已防抖 500ms，这里只落盘。存档读显示位本身（与平板同口径，
        // 见 TabletReaderHost.onSaveProgress），不 finalize（存档不是离开，杀 temp 会话锁死大章）。
        ioScope.launch {
            if (bookId < 0) return@launch
            store.saveProgress(bookId, ReadingLocator(pos.chapter, pos.slice.charStart))
        }
    }

    fun close() {
        scopeJob.cancel()
        // R13: 先停 controller 后台塑形（落盘由调用方保证在前）。
        runCatching { controller.close() }
        runCatching { reader.close() }
    }

    // ---- 平台接缝 ----

    /** 锚点落位（目录跳转/设置重建）：章内字符所在页；无内容回 null（调用方退回 locateStart）。 */
    private suspend fun landAnchor(chapter: Int, char: Int): ReaderPos? =
        controller.pageAtChar(chapter, char)?.let { ReaderPos(chapter, it) }

    /**
     * R4 面板字库装载（用户层·壳，与平板 `ReaderActivity.loadPanelFonts` 同位）：
     * 系统枚举 + 中文名链（方案B name 表 → 方案A CoreText，macOS 独有，保留）+ 落库，
     * 返回统一全量表。原 `ReaderView` 视图层直写，归位到此。
     */
    suspend fun syncPanelFonts(): List<orilumn.reader.data.font.FontEntry> = withContext(Dispatchers.IO) {
        val sys = runCatching { orilumn.reader.engine.skia.systemFontFaces() }.getOrDefault(emptyList())
        val faces = runCatching {
            val localized = withContext(Dispatchers.Default) {
                val nameTable = NameTableChineseNames.namesFor(sys.map { it.family })
                val coreText = MacFamilyNames.localizedFamilyNames(sys.map { it.family })
                coreText + nameTable // 同键以右侧（name 表）为准
            }
            fontLibrary.syncSystemFaces(sys, localizedNames = localized)
        }.getOrElse { runCatching { fontLibrary.list() }.getOrDefault(emptyList()) }
        fontLibrary.allEntries(faces)
    }

    /**
     * R15 设置两段式·第一段（用户层·壳，150ms 防抖落位即调）：本章轻刷新。换 profile → 追装字库池 →
     * `prepareRelayoutLight(anchorChar)` 行锚重算 → `bindReflow` 绑定，不碰他章、不跑 B2；
     * 同一字符在新分页表合位，不重建宿主、不丢内存位。视口/换书仍走重建（`initialAnchor` 路径）。
     */
    suspend fun previewToSettings(next: ReaderSettings, chapter: Int, anchorChar: Int): ReaderPos? =
        withContext(Dispatchers.Default) {
            applyProfile(next)
            orilumn.reader.io.Logger.w("Orilumn.Desktop",
                "previewToSettings body=${next.fontBody} anchors=${profile.fontWeightAnchors} ch=$chapter anchorChar=$anchorChar")
            val r = controller.prepareRelayoutLight(chapter, anchorChar) ?: run {
                orilumn.reader.io.Logger.w("Orilumn.Desktop", "previewToSettings NULL (stale/empty) ch=$chapter")
                return@withContext null
            }
            controller.bindReflow(r)
            ReaderPos(r.chapter, r.page)
        }

    /**
     * R15 设置两段式·第二段（用户层·壳，设置静默约 800ms 后调一次）：全套。`prepareRelayout`
     *（bump 代际废他章，B2 的 epoch 去重靠这一次续命）→ `bindReflow` → B2，与平板关面板全套同序。
     */
    suspend fun commitRelayout(chapter: Int, anchorChar: Int): ReaderPos? =
        withContext(Dispatchers.Default) {
            val r = controller.prepareRelayout(chapter, anchorChar) ?: run {
                orilumn.reader.io.Logger.w("Orilumn.Desktop", "commitRelayout NULL (stale/empty) ch=$chapter")
                return@withContext null
            }
            controller.bindReflow(r)
            // 参数稳定点（面板外两段式提交，含开书探针回填）：版式指纹真变时
            // 清全书旧指纹磁盘表；指纹未变（夜间切换等非版式提交）是空操作，
            // 不误删当前参数下的有效磁盘表。
            controller.cleanStaleDiskTables()
            controller.requestWholeBookRelayout()
            ReaderPos(r.chapter, r.page)
        }

    /**
     * 视口尺寸应用（窗口拉伸）：只换视口，返回是否变化。
     * 调用方按调参与两段式推进（轻刷新即时落位 + 全套沉淀），与改参同序。
     */
    suspend fun applyViewportSize(viewW: Int, viewH: Int): Boolean =
        withContext(Dispatchers.Default) {
            controller.setViewport(viewW.coerceAtLeast(16), viewH.coerceAtLeast(16))
        }

    /** 换 profile（两段共用）：重建 profile（字重随 UI 层声明进级联）→ 控制器持有 → 追装字库池。 */
    private suspend fun applyProfile(next: ReaderSettings) {
        profile = TypographicProfile.build(next, density)
        controller.profile = profile
        topUpSkiaFonts(orilumn.reader.engine.css.FontDemand.EMPTY)
    }

    /** 设置面板门控（共享收口 PanelRelayoutGate，平板同调）：开抑制 + 关按指纹整书。 */
    private val panelGate = orilumn.reader.engine.PanelRelayoutGate(controller)

    /** 面板打开：抑制后台 canonical + 快照版式指纹。 */
    fun setSettingsPanelOpen(open: Boolean) {
        if (open) panelGate.onPanelOpen()
    }

    /**
     * 面板关闭门控全套：版式真变了才 `finalizeRelayoutAll` 并绑定落位，不变回 null。
     * 调用方（面板 onDismiss）负责推送落位。
     */
    suspend fun finalizePanelSettings(chapter: Int, anchorChar: Int): ReaderPos? =
        withContext(Dispatchers.Default) {
            val r = panelGate.onPanelClose(chapter, anchorChar) ?: return@withContext null
            controller.bindReflow(r)
            ReaderPos(r.chapter, r.page)
        }

    /**
     * 原书主题提交探针（用户层·壳，与平板 `withBookStyle` 同调共享收口）。
     *
     * 面板 `commitBook` 到达时已是 `withLayoutTheme(original)` 后的中性值，此处按当前章
     * 真实排版回填首行缩进/行距后再持久化；非 original / 无定位一律原样返回。
     */
    fun probeOriginalTheme(next: ReaderSettings, chapter: Int): ReaderSettings =
        controller.probeOriginalTheme(chapter, profile.bodyPx, next)

    /**
     * 首次开书探针（用户层·壳，与平板开书收口同调共享 `probeOriginalOnOpen`）。
     *
     * 默认原书主题开书时，按落位章回填本书未钉的缩进/行距；调用方（视图）负责 bookOnly
     * 持久化 + 设置状态更新（重排 effect 随后自动轻刷）。已钉/非原书原样返回。
     */
    suspend fun probeOriginalOnOpen(
        settings: ReaderSettings,
        overlay: BookSettings,
        chapter: Int,
    ): ReaderSettings = withContext(Dispatchers.Default) {
        controller.probeOriginalOnOpen(chapter, profile.bodyPx, settings, overlay)
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

    companion object {
        /** 磁盘分页表构建号：随桌面发版手动递增（职责同安卓 versionCode）。 */
        const val DISK_CACHE_VERSION = 1
    }
}
