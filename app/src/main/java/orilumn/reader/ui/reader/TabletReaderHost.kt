package orilumn.reader.ui.reader

import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.book.BookReadingState
import orilumn.reader.data.book.BookRepository
import orilumn.reader.data.epub.TocItem
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.engine.BookDocumentController
import orilumn.reader.engine.revision.TextEditPlan
import orilumn.reader.engine.paging.BookPageNumber
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.ImageCodec
import orilumn.reader.ui.imageBitmapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
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
 *  - [pageLines] 从 controller 的 skia 行窗按 slice 行区间切片（章节绝对 Y，
 *    [orilumn.reader.ui.reader.ReaderPageCanvas] 平移到页坐标系）；
 *  - 增量/临时（大章锚点）页同样投影 skia 窗口（engine `buildPartialSkiaWindow`），三条路径
 *    （canonical/增量/临时）全绘，不再有空白页；
 *  - 跳转类操作（neighborChapterStart/pageAtFraction/chapterStart/openTocItem）跳前先
 *    finalizeOnLeave（复刻 legacy `jumpGate` 语义：离开→废除临时表、启用磁盘分页表 S5）；
 *  - 进度持久化 = controller 的 finalizeOnLeave + readingState（locator 串格式见
 *    共享 [orilumn.reader.data.read.ReadingLocatorCodec]）。
 */
class TabletReaderHost(
    private val controller: BookDocumentController,
    private val bookId: Long,
    private val repository: BookRepository,
    /** 开书即进审阅视图（底栏「审阅」与笔记面板「显示审阅模式」同一状态，默认打开）。 */
    private val initialReviewMode: Boolean = false,
) : ReaderHost {

    private val scopeJob = SupervisorJob()
    private val ioScope = CoroutineScope(scopeJob + Dispatchers.IO)
    /** UI 线程作用域：预栅格必须在这里跑（与 UI 绘制共用离屏 surface，见 onPrefillReady）。 */
    private val mainScope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)

    private var opened = false
    private var openedAtCover = true

    /**
     * 全书页码数据的代际（Compose 快照状态）：引擎在后台补齐各章页数、或某章正典表刚落时回调，
     * 这里 +1 —— 阅读面在组合期读 [pageNumberRevision]，静置不翻页也能让页码自己出现。
     */
    private var pageNumberTick by mutableIntStateOf(0)

    init {
        controller.onPageCountsChanged = { pageNumberTick++ }
        // 正文修订（与桌面同一共享表）：开书整本读一次，章树载入与缓存失效都以它为准。
        controller.textRevisionStore = repository
    }

    // ---- ReaderHost ----

    /** R13: 停掉 controller 名下所有后台塑形（落盘不受影响，调用方保证先落盘）。 */
    fun closeController() {
        controller.close()
    }

    override fun title(): String = controller.title()

    /** 第 1 档「渲染」半边的抢占钩子（见 `ReaderHost.onRasterMiss`）。 */
    override fun onRasterMiss() = controller.onForegroundRaster()

    /**
     * 预排出邻页版式后按方向补一张栅格（见 `ReaderHost.observePrefillReady`）。
     *
     * 引擎在后台线程抛信号，这里切到 **UI 线程**执行——预栅格与 UI 的绘制共用
     * `PageRasterStore` 的离屏 surface，跨线程会出事（见 `ReaderPageRenderer.preraster`）。
     * 重复订阅覆盖而非累加（设置/视口变化会重建订阅，见 `Preraster.kt`）。
     */
    override fun observePrefillReady(handler: suspend (chapter: Int, direction: Int) -> Unit) {
        // 订阅本身只是「装一个回调」，不需要切线程；回调被触发时才切 UI 线程执行
        // （引擎在后台线程抛信号，而预栅格必须在 UI 线程——与绘制共用离屏 surface）。
        controller.onPrefillReadyListener = { chapter ->
            mainScope.launch { handler(chapter, 0) }
        }
    }

    override fun unitTitle(chapter: Int): String {
        val unit = controller.unitAt(chapter) ?: return ""
        return unit.title
            .ifEmpty { unit.hrefName.substringAfterLast('/').substringBefore('#').ifEmpty { "第 ${chapter + 1} 章" } }
    }

    override fun pageCount(chapter: Int): Int = controller.unitAt(chapter)?.pageSlices?.size ?: 0

    override fun bookPageNumber(pos: ReaderPos): BookPageNumber? =
        controller.bookPageNumber(pos.chapter, pos.slice)

    override fun chapterStartPages(): IntArray = controller.chapterStartPages()

    override fun pageNumberRevision(): Int = pageNumberTick

    override fun tocIndex(chapter: Int): Int =
        flatToc().indexOfFirst { it.index == chapter }.takeIf { it >= 0 } ?: chapter

    override suspend fun open(): ReaderPos? = withContext(Dispatchers.IO) {
        if (!opened) {
            val state = repository.readingState(bookId)
            if (!controller.open(bookId, state, reviewMode = initialReviewMode)) return@withContext null
            // 注：open 后默认 prewarm 恒跳过落位章（open 同步塑形，一次为准），此处不再调用。
            // R7: open-book B2 dispatch (epoch-deduped; defer-gated inside) — far chapters get laid
            // without waiting for a settings change. Host-owned orchestration; prewarm stays single-chapter.
            openedAtCover = (state == null) || orilumn.reader.data.read.ReadingLocatorCodec.decodeAtCover(state.locator)
            controller.requestWholeBookRelayout()
            opened = true
        }
        controller.locateStart()?.let { ReaderPos(it.first, it.second, atCover = openedAtCover) }
    }

    override suspend fun adjacent(pos: ReaderPos, direction: Int): ReaderPos? = withContext(Dispatchers.IO) {
        controller.findAdjacentPage(pos.chapter, pos.slice, direction)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    /** 只读邻页查询：预栅格专用，**不推进指针、不抢占后台**（见 [ReaderHost.peekAdjacent]）。 */
    override suspend fun peekAdjacent(pos: ReaderPos, direction: Int): ReaderPos? = withContext(Dispatchers.IO) {
        controller.peekAdjacentPage(pos.chapter, pos.slice, direction)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    override suspend fun neighborChapterStart(chapter: Int, direction: Int): ReaderPos? =
        withContext(Dispatchers.IO) {
            // 复刻 legacy jumpGate：离开当前章前先 finalizeOnLeave（废除临时表、启用磁盘分页表）。
            controller.finalizeOnLeave(chapter)
            controller.neighborChapterStart(chapter, direction)?.let { ReaderPos(it.first, it.second, atCover = false) }
        }

    override suspend fun pageAtFraction(fraction: Double): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.pageAtFraction(fraction)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    override suspend fun chapterStart(index: Int): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.openChapterStart(index)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    /** 目录跳转（Android 专用）：finalizeOnLeave 后经 [BookDocumentController.openTocItem] 落位，
     *  继承索引 + 片段的既有语义（方向跳转走 [neighborChapterStart]）。 */
    suspend fun jumpToToc(index: Int, fragment: String?): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: 0
        controller.finalizeOnLeave(from)
        controller.openTocItem(index, fragment)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    /** 标注清单跳转：落到包含该标注起点的页（与目录跳转同口径，经控制器 [BookDocumentController.openAtChar]）。 */
    suspend fun jumpToChar(chapter: Int, charOffset: Int): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: chapter
        controller.finalizeOnLeave(from)
        // 标注清单的锚点是原书空间；落位要的是渲染空间。
        val rendered = controller.renderedOffset(chapter, charOffset)
        controller.openAtChar(chapter, rendered)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    /** 渲染区间 ↔ 原书区间（方案 §8.8；宿主是唯一换算层）。 */
    override fun originalRangeOf(chapter: Int, start: Int, endExclusive: Int): IntRange =
        controller.originalRange(chapter, start, endExclusive)

    override fun renderedRangeOf(chapter: Int, start: Int, endExclusive: Int): IntRange =
        controller.renderedRange(chapter, start, endExclusive)

    /**
     * 提交一次正文修改（方案 §8.8）：纯计算计划 → 一个事务落库 → 重读修订 → 只作废本章 →
     * 重新载入重放并落位到修改起点。
     */
    override suspend fun commitTextEdit(
        chapter: Int,
        start: Int,
        endExclusive: Int,
        text: String,
        currentPage: ReaderPos?,
    ): TextEditOutcome = withContext(Dispatchers.IO) {
        val plan = controller.planTextEdit(chapter, start, endExclusive, text)
        when (plan) {
            is TextEditPlan.Rejected -> return@withContext TextEditOutcome.Rejected(plan.reason)
            is TextEditPlan.Ready -> {
                if (plan.noop) {
                    return@withContext TextEditOutcome.Success(
                        pos = null,
                        selStart = plan.renderedStart,
                        selEnd = plan.renderedEndExclusive,
                    )
                }
                // 改前页首（坐标用**旧**修订表算）：改完按同一处内容落位，不按「改到哪儿」落位。
                val anchor = currentPageAnchor(chapter, currentPage)
                val saved = runCatching {
                    repository.replaceTextRevisions(plan.deletedIds, plan.inserted)
                }.getOrNull()
                if (saved == null || (plan.inserted.isNotEmpty() && saved.size != plan.inserted.size)) {
                    return@withContext TextEditOutcome.Failed("保存失败")
                }
                controller.reloadTextRevisions()
                val pos = if (anchor != null) {
                    controller.reflowAfterContentChange(
                        chapter = chapter,
                        prevPageCharStart = anchor.first,
                        prevPageOriginalStart = anchor.second,
                        changeOldStart = start,
                        changeOldEndExclusive = endExclusive,
                    ) ?: reflowFallback(chapter, anchor.second)
                    ?: return@withContext TextEditOutcome.Failed("重排失败")
                } else {
                    // 拿不到改前页首（不在这一章 / 还没落位）：退回旧口径，按改动起点落位。
                    controller.invalidateChapterContent(chapter)
                    runCatching { controller.openAtChar(chapter, plan.renderedStart) }.getOrNull()
                        ?: return@withContext TextEditOutcome.Failed("重排失败")
                }
                // 本章页数变了：页脚页码 / 目录页码跟着刷新（页码清单已在本章作废）。
                pageNumberTick++
                TextEditOutcome.Success(
                    pos = ReaderPos(pos.first, pos.second, atCover = false),
                    selStart = plan.renderedStart,
                    selEnd = plan.renderedEndExclusive,
                )
            }
        }
    }

    /**
     * 删除一条正文修订（编辑清单行左滑删除）：落库 → 重读修订 → 只作废该章。
     * 删的是当前阅读章时按**删除前的当前页首**重新落位（删除可能让前面的文字回退），
     * 并把新落位交给阅读面；别的章只作废缓存，不动当前页。
     */
    override suspend fun deleteTextRevision(
        revision: TextRevision,
        currentPage: ReaderPos?,
    ): TextRevisionDeleteOutcome =
        withContext(Dispatchers.IO) {
            // 先记当前页首（**原书**空间）：删除后按同一处内容重新落位，不把读者拽到清单行处。
            // 页首 slice 是渲染坐标，先换回原书坐标，删除后再换算成新的渲染坐标。
            val anchor = currentPageAnchor(revision.chapter, currentPage)
            // 被删的那条修订在**旧**渲染空间占的区间（判「改掉的是不是只在当前页之内」）。
            val changeRange = controller.textRevisionRange(revision.chapter, revision)
            val removed = runCatching { repository.deleteTextRevision(revision.id) }.isSuccess
            if (!removed) return@withContext TextRevisionDeleteOutcome.Failed
            controller.reloadTextRevisions()
            // 本章页数可能变了：页脚 / 目录页码跟着刷新。
            pageNumberTick++
            val pos = if (anchor != null) {
                val landed = controller.reflowAfterContentChange(
                    chapter = revision.chapter,
                    prevPageCharStart = anchor.first,
                    prevPageOriginalStart = anchor.second,
                    changeOldStart = changeRange.first,
                    changeOldEndExclusive = changeRange.last + 1,
                ) ?: reflowFallback(revision.chapter, anchor.second)
                landed?.let { ReaderPos(it.first, it.second, atCover = false) }
            } else {
                // 删的是别的章：只作废它的缓存，眼前这一页不动。
                controller.invalidateChapterContent(revision.chapter)
                null
            }
            TextRevisionDeleteOutcome.Success(pos)
        }

    /**
     * 正式重排失败（解析/排版没起来）时兜底：按改前页首对应的**原书偏移**重新落位。
     *
     * 内容缓存已在 [BookDocumentController.reflowAfterContentChange] 里清掉，若就此返回 null，
     * 阅读面会继续拿着旧页去画一个没有版式的章——整页空白且不会自愈。这里必须再走一次落位
     * （内部会重新解析并排版），实在不行才把 null 交回去。
     */
    private suspend fun reflowFallback(chapter: Int, originalOffset: Int): Pair<Int, PageSlice>? =
        runCatching {
            controller.openAtChar(chapter, controller.renderedOffset(chapter, originalOffset))
        }.getOrNull()

    /**
     * 改前当前页的落位锚点：([旧渲染空间页首], [同一处的原书坐标])。
     *
     * **当前页由调用方传入**（阅读面手里的 `openPos` / `currentPos`）。不能拿
     * [BookDocumentController.locateStart] 顶替：那是开书锚点，读者翻走以后仍停在开书那一章，
     * 会把当前章的锚点误判成「不在这一章」（null）——宿主随后只作废不重排，眼前整页空白。
     * 旧调用方不传时仍退回开书锚点，保持兼容。
     */
    private suspend fun currentPageAnchor(chapter: Int, currentPage: ReaderPos?): Pair<Int, Int>? {
        val slice = if (currentPage != null) {
            if (currentPage.chapter != chapter) return null
            currentPage.slice
        } else {
            val (ch, page) = runCatching { controller.locateStart() }.getOrNull() ?: return null
            if (ch != chapter) return null
            page
        }
        return slice.charStart to controller.originalOffset(chapter, slice.charStart)
    }

    /**
     * 审阅视图开关（排版前的另一份预处理）：有修订的章按「原文全保留 + 新文字插在后面」重排，
     * 没修订的章不动。分页表 / 页数 / 页快照照常按新内容版本读写（页码照常算）。
     *
     * 重读/重排是同步的状态清理（清解析态 + 版式），调用方在 IO 线程调；**切换 + 落位**请走
     * [switchReviewMode]，那一条把两个空间的换算顺序也一起管住了。
     */
    override fun setReviewMode(on: Boolean): Boolean = controller.setReviewMode(on)

    override fun isReviewMode(): Boolean = controller.reviewMode

    override fun reviewMarks(chapter: Int): List<ReviewMark> =
        controller.reviewMarks(chapter).mapNotNull { (range, deleted) ->
            if (range.isEmpty()) null else ReviewMark(range.first, range.last + 1, deleted)
        }

    /**
     * 切换到审阅视图（或切回）并按「同一处内容」重新落位，一步完成。
     *
     * [renderedCharStart] 是**切换前**当前页的页首（旧空间）；先换回原书坐标、再翻模式、再按新空间
     * 换算落位——顺序在控制器里管住。大章切换先按锚点行起一张临时页（页首不动），随后后台排本章
     * 正典；短章仍整章排完落位。没修订的章两种视图逐字相同，不动位置。
     *
     * 模式没变（重复点按）或本章没有修订可切时回 null，调用方据此跳过落位、只重绘痕迹。
     */
    suspend fun switchReviewMode(on: Boolean, chapter: Int, renderedCharStart: Int): ReaderPos? =
        withContext(Dispatchers.IO) {
            controller.switchReviewMode(on, chapter, renderedCharStart)
                ?.let { ReaderPos(it.first, it.second, atCover = false) }
        }

    /** P4-c2u: 点按命中的链接查表（控制器轻路径样式化区间，同步廉价）。 */
    override fun linkTargetAt(chapter: Int, charOffset: Int): orilumn.reader.engine.LinkTarget? =
        controller.linkTargetAt(chapter, charOffset)

    /** P4-c2u: 跟随链接（离开当前章先 finalizeOnLeave，与目录跳转同口径）。 */
    override suspend fun openLink(target: orilumn.reader.engine.LinkTarget): ReaderPos? = withContext(Dispatchers.IO) {
        val from = controller.locateStart()?.first ?: target.chapterIndex
        controller.finalizeOnLeave(from)
        controller.openLinkTarget(target)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    override fun pageProgress(pos: ReaderPos): Double = controller.pageProgress(pos.chapter, pos.slice)

    override fun bookPageNumberAtFraction(fraction: Double): Int? =
        controller.bookPageNumberAtFraction(fraction)

    /** 全书封面（书库 coverPath 原字节直解；无/失败回 null）。 */
    override suspend fun coverImage(): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val ref = repository.getBook(bookId)?.coverPath ?: return@runCatching null
            val bytes = java.io.File(ref).takeIf { it.isFile }?.readBytes() ?: return@runCatching null
            imageBitmapOf(bytes)
        }.getOrNull()
    }

    /** 全书第一内容页（**导航**：openChapterStart 落位 + 逐出远章；
     *  封面页内前进翻页用。判定书首请用 [firstContentChapter]）。 */
    override suspend fun bookStart(): ReaderPos? = withContext(Dispatchers.IO) {
        controller.openChapterStart(0)?.let { ReaderPos(it.first, it.second, atCover = false) }
    }

    /** 首章正文号（纯查询懒解析转发；无导航副作用，见 ReaderHost.firstContentChapter）。 */
    override suspend fun firstContentChapter(): Int? = withContext(Dispatchers.IO) {
        controller.firstContentChapter()
    }

    override fun pageLines(pos: ReaderPos): List<DrawLine>? = controller.pageLines(pos.chapter, pos.slice)

    /** D-18：整章文本切片（同步；prepare 未就绪返回 null）。 */
    override fun textOf(chapter: Int, start: Int, endExclusive: Int): String? {
        val text = controller.chapterText(chapter) ?: return null
        val s = start.coerceIn(0, text.length)
        val e = endExclusive.coerceIn(s, text.length)
        return text.substring(s, e)
    }

    override fun textBlockEndsOf(chapter: Int): IntArray? = controller.chapterTextBlockEnds(chapter)

    override fun reviewBreakOffsets(chapter: Int): IntArray = controller.reviewBreakMarkers(chapter)

    // ---- 文本标注：转手给共享 BookRepository/LibraryDb（平台侧只做壳） ----
    //
    // 坐标口径（方案 §8.8）：标注落库一律是**原书空间**；阅读面（选区 / 装饰 / 命中）
    // 是**渲染空间**。两个空间的换算只在宿主这一层做，shared-ui 不感知修订。

    private fun toRenderedRange(annotation: Annotation): Annotation {
        val r = controller.renderedRange(annotation.chapter, annotation.charStart, annotation.charEnd)
        val end = if (r.isEmpty()) r.first else r.last + 1
        return if (r.first == annotation.charStart && end == annotation.charEnd) annotation
        else annotation.copy(charStart = r.first, charEnd = end)
    }

    private fun toOriginalRange(annotation: Annotation): Annotation {
        val r = controller.originalRange(annotation.chapter, annotation.charStart, annotation.charEnd)
        val end = if (r.isEmpty()) r.first else r.last + 1
        return if (r.first == annotation.charStart && end == annotation.charEnd) annotation
        else annotation.copy(charStart = r.first, charEnd = end)
    }

    override suspend fun annotationsForChapter(chapter: Int): List<Annotation> = try {
        repository.annotationsForChapter(bookId, chapter).map(::toRenderedRange)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        emptyList()
    }

    override suspend fun annotationsForBook(): List<Annotation> = try {
        // 清单只显示原文摘录，不画装饰；坐标保持原书空间，跳转时再换算。
        repository.annotationsForBook(bookId)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        emptyList()
    }

    /**
     * 阅读面判「这段字改过没有」用：本章修订换成**当前文字空间**的坐标（与选区同尺）。
     *
     * 正常视图 = 渲染空间（替换后的区间）；审阅视图 = 原区间 + 紧随其后的新文字
     * （两半都算同一条修订，选到删掉的原文或新添的文字都判得到）。换算走控制器的修订表（纯算术，
     * 不需要排版就绪）；读取失败按空表处理——卡片上的「删除」会虚掉，但不会误删。
     */
    override suspend fun textRevisionsForChapter(chapter: Int): List<TextRevision> = try {
        repository.textRevisionsForChapter(bookId, chapter).map { r ->
            // 审阅视图里点按 / 开卡认「整段」（原文 + 新文字），与修改卡带出的改后文字同尺。
            val rr = controller.textRevisionEditRange(chapter, r)
            if (rr.first == r.charStart && rr.last + 1 == r.charEnd) r
            else r.copy(charStart = rr.first, charEnd = maxOf(rr.first, rr.last + 1))
        }
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        emptyList()
    }

    /** 编辑清单：全书正文修订（原书坐标），跳转时再由 [jumpToChar] 换算。 */
    override suspend fun textRevisionsForBook(): List<TextRevision> = try {
        // 快照两列上线前写下的老行：区间还在原书空间，先按持久化章树把原文补回库再读清单。
        controller.backfillLegacyRevisionTexts()
        repository.textRevisionsForBook(bookId)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        emptyList()
    }

    override suspend fun addAnnotation(annotation: Annotation): Annotation? = try {
        val now = System.currentTimeMillis()
        val saved = repository.addAnnotation(
            toOriginalRange(annotation).copy(
                bookId = bookId,
                createdAt = if (annotation.createdAt == 0L) now else annotation.createdAt,
                updatedAt = now,
            ),
        )
        // 返回给阅读面的是渲染空间（内存列表与装饰都按它画）。
        saved?.let(::toRenderedRange)
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    override suspend fun updateAnnotation(annotation: Annotation): Boolean = try {
        repository.updateAnnotation(
            toOriginalRange(annotation).copy(updatedAt = System.currentTimeMillis()),
        )
        true
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        false
    }

    override suspend fun deleteAnnotation(id: Long): Boolean = try {
        repository.deleteAnnotation(id)
        true
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        false
    }

    /** 页首缺口（绘制帧顶相对首行行顶的距离），画布/点按/预栅格三方同源。 */
    override fun pageFrameLead(pos: ReaderPos): Int = controller.pageFrameLead(pos.chapter, pos.slice)

    override fun pageImages(pos: ReaderPos): List<orilumn.reader.engine.skia.PageImage>? =
        controller.pageImages(pos.chapter, pos.slice)

    override fun pageBackgrounds(pos: ReaderPos): List<orilumn.reader.engine.skia.PageBackground>? =
        controller.pageBackgrounds(pos.chapter, pos.slice)

    // P3-b: 背景图直解（controller 复用 zip 管线/ImageCodec；失败回 null，該幅只留底色）。
    override suspend fun loadBackgroundImage(chapterHref: String, src: String): DecodedImage? =
        withContext(Dispatchers.IO) { controller.loadBackgroundImage(chapterHref, src) }

    override suspend fun loadPageImage(img: orilumn.reader.engine.skia.PageImage): DecodedImage? =
        withContext(Dispatchers.IO) {
            // P0a：正文插图解码统一到 skia（与背景图 `loadBackgroundImage` 同一类型）——
            // 插图已下沉进整页位图，下游要能直接进 skia 画布的东西，不再经 Compose BitmapFactory。
            // 采样口径：按 used 宽度缩放，且**只缩不放**（`decodeScaled` 即旧 inSampleSize+
            // createScaledBitmap 的等价物，见 engine-skia `ImageCodec` KDoc；绘制侧
            // `drawImageRect(源尺寸 → used 尺寸)` 会兜住放大，故源图更小时不退化解码工作）；
            // 失败回退 PNG 字节直解。
            val raw = controller.loadPageImageRaw(img)
            if (raw != null) {
                ImageCodec.decodeScaled(raw, img.widthPx.coerceAtLeast(1))?.let { return@withContext it }
            }
            val png = controller.loadPageImageBytes(img) ?: return@withContext null
            ImageCodec.decode(png)
        }

    override fun onSaveProgress(pos: ReaderPos) {
        // fire-and-forget：阅读面已防抖 500ms，这里只落盘（复刻 legacy scheduleSave）。
        // 存档读显示位本身（slice.charStart 即章内字符，temp 与 canonical 同一坐标系，无缝 canary 背书），
        // 不 finalize：存档不是离开，finalize 会废掉活着的 temp 会话（canonical 未就绪时整表作废），
        // 下一翻只能拿着 detached 切片跨章——大章（B1 跑不完）会被锁死在锚点页。finalize 只属于离开路径。
        ioScope.launch {
            if (bookId < 0) return@launch
            val progress = controller.pageProgress(pos.chapter, pos.slice)
            // 存档的锚点是**原书空间**：渲染空间的位置不能直接写进 locator。
            val locator = orilumn.reader.data.read.ReadingLocatorCodec.encode(
                pos.chapter,
                controller.originalOffset(pos.chapter, pos.slice.charStart),
                pos.atCover,
            )
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


    /** 目录扁平化（与桌面同一语义：焦点下标即扁平序，见共享 [flattenTocItems]）。 */
    private fun flatToc(): List<TocItem> = flattenTocItems(controller.toc())
}