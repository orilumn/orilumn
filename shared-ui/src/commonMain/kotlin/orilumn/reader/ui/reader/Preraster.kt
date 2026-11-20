package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage
import orilumn.reader.engine.skia.projectDecor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import orilumn.reader.io.Logger

/**
 * 预栅格执行器（用户层·`线程调度原则.md` §3 阶梯补的那一档）。
 *
 * ## 背景：阶梯里一直缺栅格这一档
 *
 * 第 2/3/4 档全是**排版**（产出 `pageLines` 版式数据），栅格不在体系内，于是像素只在
 * 页面**被绘制时**才生产。实测单页栅格 p50 **143ms**、max 977ms；而真机 `FLIPLAT`
 * 日志里翻页的 `target-ready` p50 59ms —— 用户手指按下后要先等这张位图才动得了。
 *
 * 预排出的邻页**用户下一步就要用**（§0 紧急度原则：需要得越快 → 越靠前、越不限手段），
 * 所以像素该在预排完成那一刻就跟上，而不是等绘制。这也是 moon+ 的做法：它静止时就
 * `getPageShot()` 预先截好下一页（`tmpFlipShot2`），滑动时直接复用。
 *
 * ## 每次只栅**方向侧**那一张（§3.2 方向记录）
 *
 * 引擎在「±1 页分页完成」时抛信号（临时表逐页成形 / 磁盘表 d=1 两侧装配 / 小章全章排完），
 * 用户层据翻页方向补**一张**像素即可。窗口 {反侧, 当前, 方向侧} 三页里：
 *  - **反侧 = 上一刻的当前页**，必然还在池中（命中 0ms）—— 补它是重复劳动；
 *  - 当前页由绘制同步栅格；
 *  - **只有方向侧是缺的**。
 * 不单独设置优先级、不排队（§0：需要得越快 → 越靠前；排队＝把成本推给用户）。
 *
 * 补反侧不只是白做：缓存是 access-order LRU，读它会给它续命，
 * 于是被挤出去的反而可能是方向侧那一张（真机实测：两侧版 16 次翻页 29 次整页真栅格）。
 *
 * ## 指纹必须与 UI 首帧逐字一致，否则照样 miss
 *
 * `PageRasterCache` 的命中要求「[PageRasterKey] 的 id 相同 **且** [PageRasterFingerprint]
 * 相等」。后者含 `lines`（data class 按值）、`images`（含 skia Image **按实例**比）。
 * 所以本页的取数口径必须**复用 [rememberPageContent] 的同一条路径**（同一个
 * `imgKeyOf`/`bgKeyOf`、同一个 `imgCache`），否则插图按实例不等 ⇒ 白栅一次。
 */
@Composable
fun PrerasterOnPrefill(
    host: ReaderHost,
    /** 引擎信号：预排出版式后回调（chapter + 翻页方向）。 */
    onPrefillReady: suspend (chapter: Int, direction: Int) -> Unit,
    /** 当前翻页方向（+1 前 / -1 后 / 0 无记录），决定补哪一侧。 */
    directionProvider: () -> Int,
    /** 当前显示页（锚点）。 */
    currentPos: () -> ReaderPos?,
    renderer: ReaderPageRenderer,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageBg: Int,
    inkColor: Int,
    contentRevision: Int,
    hostRevision: Int,
    imgCache: PageImageCache<DecodedImage>,
    bgCache: PageImageCache<DecodedImage>,
    /**
     * 按章取标注（`AnnotationState.annotationsFor`；`null` = 该章还没读回来）。
     *
     * 预栅格只补**当前章**的邻页，但页属于哪一章仍按 pos 问：拿别章的区间投影会错位，
     * 而预栅格的指纹又必须与首帧绘制逐字相等，两边只能走同一条「按页所属章取标注」的口径。
     */
    annotationsFor: (chapter: Int) -> List<Annotation>? = { null },
    /**
     * 翻页会话是否在飞（`FlipController.isBusy()`）。
     *
     * **在飞时一律不补**：此刻落位自己正在栅同一张（`AnchorFunnel.beforeCommit` 的
     * `prerasterPage`），两笔并发只是把同一页画两遍——真机日志里 300ms 的栅格被同时
     * 做两次（`preraster … 289ms hit=false` 与 `pre-commit raster=311ms` 同一 key），
     * 还互相抢 store 锁。落定后由 [settleTick] 补，方向与页都对。
     */
    sessionBusy: () -> Boolean = { false },
    /**
     * 翻页会话落定的代际（每 `FlipController.clear()` +1）：**翻完立刻补方向侧像素**。
     *
     * 为什么不能只等引擎的预排信号：信号在**落位之前**抛（引擎装配 ±1 页 product 时
     * 抛 `notifyPrefillReady`），那一刻 `openPos` 还是起手页 ⇒ 补的是「本次要翻到的
     * 那一页」——正是落位自己在栅的那张（白做）；落定之后又不再抛信号（±1 页已装配完），
     * 于是**下一页永远没人补**，下一次翻页只能现场栅（真机：每次 220–390ms，
     * `FLIPLAT first-move` 因此 300–650ms）。落定代际把这一拍补上。
     */
    settleTick: Int = 0,
) {
    val hostRef = rememberUpdatedState(host)
    val onPrefillRef = rememberUpdatedState<suspend (Int, Int) -> Unit>(onPrefillReady)
    val dirRef = rememberUpdatedState(directionProvider)
    val posRef = rememberUpdatedState(currentPos)
    val rendererRef = rememberUpdatedState(renderer)
    val imgCacheRef = rememberUpdatedState(imgCache)
    val bgCacheRef = rememberUpdatedState(bgCache)
    val annotationsRef = rememberUpdatedState(annotationsFor)
    val sessionBusyRef = rememberUpdatedState(sessionBusy)
    // 闸 2：同一时刻只允许一轮预栅格在跑（信号触发的撞上就丢——这是去重，不是优先级判断；
    // 落定触发的那一轮改成**等**它收尾，因为落定这一拍必须补上，丢了就没人补了）。
    val prerasterLock = remember { Mutex() }
    // 落定信号：`settleTick` 每变一次代表一次翻页会话结束（见参数）。CONFLATED 合并同一拍
    // 的多条——落定补的是「此刻的下一张」，补两遍没有意义。
    val settleSignals = remember { Channel<Unit>(Channel.CONFLATED) }
    LaunchedEffect(settleTick) {
        if (settleTick > 0) settleSignals.trySend(Unit)
    }

    // 几何/颜色随设置与视口变化，重建即重挂信号订阅。
    LaunchedEffect(contentLeft, contentTop, contentRight, contentBottom, pageBg, inkColor, contentRevision, hostRevision) {
        val hostNow = hostRef.value
        val rendererNow = rendererRef.value
        val imgCacheNow = imgCacheRef.value
        val bgCacheNow = bgCacheRef.value
        val dirNow = dirRef.value
        val posNow = posRef.value
        // 只补**方向侧**那一张（§3.2 方向记录）：反侧就是上一刻的当前页、必然还在池中，
        // 补它是重复劳动，且会给它续命（access-order LRU）而挤掉方向侧。
        // 不设第二套优先级：抢占仍由唯一钩子负责——栅格未命中时 `onRasterMiss` →
        // `controller.onForegroundRaster()` → `cancelForRaster(PRIO_FLIP)`（§5「唯一钩子」）。
        // 该钩子与翻页的 `notifyFlip()` 同源，但**放行当前章的 ±1 页预排**（`pg:` d=1 两档）：
        // ±1 是方向侧像素的版式来源，砍掉它会让 `pageCache` 永热不起来（真机 137–308ms/次）。
        // 预栅格自己再判一套「要不要让路」只会出错：第三版按「是否会话
        // 目标页」判，把预栅格整个掐死（真机：temp prefill 信号持续在发，preraster 只触发 7 次，
        // 每页仍现场栅 75~179ms ⇒ 每次翻页 200~250ms 空白）。
        //
        // 具名 suspend fun 而非 lambda：`return@...` 在 lambda 里不合法，而这段全是
        // 「条件不满足就地退出」的守卫。
        //
        // [afterSettle] = 落定触发（见参数 [settleTick]）：此刻翻页会话已收场，方向与锚页都是
        // 最新的；引擎那一拍的 ±1 页装配可能还差一点，允许再等两拍（用户正在读当前页，白送的）。
        suspend fun rasterWindow(chapter: Int, afterSettle: Boolean) {
            if (afterSettle) prerasterLock.lock()
            else if (!prerasterLock.tryLock()) {
                Logger.d("Orilumn.TAP", "preraster window skip: busy ch=$chapter")
                return
            }
            try {
                var attempt = 0
                while (true) {
                    // 会话在飞 ⇒ 落位自己正在栅这一张（见参数 sessionBusy）：让给它，别抢 store 锁。
                    if (sessionBusyRef.value()) {
                        Logger.d("Orilumn.TAP", "preraster window skip: flip in flight ch=$chapter")
                        return
                    }
                    val anchor = posNow() ?: return
                    // 章边界照补：`peekAdjacent` 在章末/章首会回答**邻章边缘那一页**（引擎只读查询，
                    // 版式由 §3.3 的邻章边缘页预排提前装配）。信号里的 `chapter` 只是"哪个章排完了"
                    // 的提示，真正要栅的目标页一律以 peek 为准——所以这里不再按章筛掉。
                    if (anchor.chapter != chapter) {
                        Logger.d(
                            "Orilumn.TAP",
                            "preraster window: signal ch=$chapter ≠ anchor ch=${anchor.chapter}（章边界）",
                        )
                    }
                    // 方向 0 = 无记录（开书/跳转/调参），此时前向更可能被用，语义等同向前（§3.2）。
                    val effDir = if (dirNow() == 0) 1 else dirNow()
                    // **必须用只读的 peekAdjacent**：`adjacent` 是有副作用的真导航（引擎 tempNav
                    // 推进指针），预栅格调它会在无手势时把指针多推一格 ⇒ 真机症状
                    // 「翻页完成后又跳了一页」（19:56:25日志）。
                    val target = runCatching { hostNow.peekAdjacent(anchor, effDir) }.getOrNull()
                    if (target != null) {
                        prerasterPage(
                            host = hostNow,
                            pos = target,
                            renderer = rendererNow,
                            contentLeft = contentLeft,
                            contentTop = contentTop,
                            contentRight = contentRight,
                            contentBottom = contentBottom,
                            pageBg = pageBg,
                            inkColor = inkColor,
                            contentRevision = contentRevision,
                            imgCache = imgCacheNow,
                            bgCache = bgCacheNow,
                            // 按**目标页所属章**取标注：跨章目标（邻居章）也要投影出它自己那一章的装饰，
                            // 否则预栅出来的位图与首帧绘制不等，白栅一次。
                            annotations = annotationsRef.value(target.chapter).orEmpty(),
                            annotationChapter = target.chapter,
                        )
                        return
                    }
                    // 版式还没装配出来（引擎的 ±1 页 product 落后一拍）：落定触发的这一轮再等两拍，
                    // 信号触发的直接放弃（下一次落定/信号还会来）。
                    if (!afterSettle || attempt >= PRERASTER_SETTLE_RETRIES) {
                        Logger.d("Orilumn.TAP", "preraster window: peek d=$effDir ch=$chapter → null")
                        return
                    }
                    attempt++
                    delay(PRERASTER_SETTLE_RETRY_MS)
                }
            } finally {
                prerasterLock.unlock()
            }
        }
        // 引擎抛信号 → 执行栅格。取数在本协程（前台），整页重画由 `prerasterPage` 切到
        // Default；store 的 surface 已加锁，命中路径不碰锁（见 `PageRasterStore` 线程段）。
        hostNow.observePrefillReady { chapter, _ -> rasterWindow(chapter, afterSettle = false) }
        // 落定信号 → 立刻补方向侧那一张（下一章/下一页）。引擎的预排信号在落位**之前**抛，
        // 补不到这一张（见参数 [settleTick]），这里是它唯一的补法。
        launch {
            for (ignored in settleSignals) {
                val chapter = posNow()?.chapter ?: continue
                rasterWindow(chapter, afterSettle = true)
            }
        }
    }
}

/**
 * 把一页栅进 [PageRasterStore]（与 `drawLines` 共用 store/surface）。
 *
 * 取数与建 key 在前台上下文（读 `ReaderHost`/快照），**整页重画切到 [Dispatchers.Default]**
 * ——`PageRasterStore` 的 surface 已加锁，可被 UI/后台两条线程交替使用；命中判定仍在锁外，
 * 所以 UI 帧里的命中不会被后台预栅格挡住。栅格耗时因此不再计入 UI 帧预算。
 *
 * 取数口径与 [rememberPageContent] 逐条对齐（`pageLines` / `pageImages` / `pageBackgrounds`
 * + `imgKeyOf`/`bgKeyOf` 命中缓存），否则指纹不等、首帧仍会重画。
 */
internal suspend fun prerasterPage(
    host: ReaderHost,
    pos: ReaderPos,
    renderer: ReaderPageRenderer,
    contentLeft: Float,
    contentTop: Float,
    contentRight: Float,
    contentBottom: Float,
    pageBg: Int,
    inkColor: Int,
    contentRevision: Int,
    imgCache: PageImageCache<DecodedImage>,
    bgCache: PageImageCache<DecodedImage>,
    annotations: List<Annotation> = emptyList(),
    annotationChapter: Int = -1,
): Boolean {
    val rawLines: List<DrawLine> = runCatching { host.pageLines(pos) }.getOrNull().orEmpty()
    // 审阅痕迹与行同一次读取（先取行）：两样必须属于同一空间，否则预栅进池的位图
    // 是「有 ¶ 没颜色」的中间态（进出审阅时先黑后红/消失，见 rememberPageContent）。
    val reviewMarks: List<ReviewMark> =
        runCatching { host.reviewMarks(pos.chapter) }.getOrDefault(emptyList())
    // 与 rememberPageContent 同一条标注投影路径。调用方按 pos 所属章传（相邻章由预读提供）；
    // 章不匹配就**不投影**（宁缺勿串）——区间是章内偏移，拿别章的去投会画到错字上。
    val activeAnnotations =
        if (pos.chapter == annotationChapter) annotations else emptyList()
    val decorRanges = if (reviewMarks.isEmpty()) {
        annotationDecorRanges(activeAnnotations)
    } else {
        annotationDecorRanges(activeAnnotations) + reviewDecorRanges(reviewMarks)
    }
    val lines: List<DrawLine> = projectDecor(
        rawLines,
        pos.chapter,
        decorRanges,
    )
    val pageImages: List<PageImage> = runCatching { host.pageImages(pos) }.getOrNull().orEmpty()
    val pageBackgrounds: List<PageBackground> = runCatching { host.pageBackgrounds(pos) }.getOrNull().orEmpty()
    // 跳过条件必须与绘制路径 `ReaderPageCanvas` 同口径（「三类皆空」才算空页）：纯图页没有
    // 任何行（替换块不产 `DrawLine`），早先这里只看 `lines` ⇒ 图页既不预栅也不预热位图，
    // 翻到它只能靠绘制路径现解现画：真机日志 `preraster skip: no lines ch=7 char=17667` 之后
    // 整页栅格 `n=0 imgs=1`（灰占位就有图位）⇒ 症状「翻到图页先空/灰一下」。
    if (lines.isEmpty() && pageImages.isEmpty() && pageBackgrounds.isEmpty()) {
        Logger.d("Orilumn.TAP", "preraster skip: empty page ch=${pos.chapter} char=${pos.slice.charStart}")
        return false
    }

    // **先把图解出来再栅**：早先这里只用 `imgCache.get()` 取已缓存的图，取不到就填
    // null⇒ 把**灰占位块**栅进池子；真图解出后 `PageRasterFingerprint.images` 里的
    // skia Image换了实例（该字段按实例比）⇒ 整页重栅 ⇒ 用户看到「先页面、后插图」
    // 两跳（真机日志：raster imgs=1 连续两次 hit=false 114ms/120ms，第三次才 hit=true）。
    //
    // 口径复用 [warmPageImages]（同一个 `imgKeyOf`/`bgKeyOf`、同一个 cache），否则
    // 解出来的图与 UI 首帧认的不是同一份，指纹仍不等。
    if (!warmImagesForPreraster(host, pos, pageImages, pageBackgrounds, imgCache, bgCache)) {
        // 有图但没备齐 ⇒ **不入池**。留空池比入一个灰块好：入灰块会被当成命中，
        // 真图到达后仍要重画，且用户已经看到灰块闪了一下。
        Logger.w(
            "Orilumn.TAP",
            "preraster skipped: images not ready ch=${pos.chapter} char=${pos.slice.charStart}",
        )
        return false
    }

    // 与 ReaderPageCanvas 同款对齐：章节绝对 Y → 页坐标系，单一来源见 ReaderMath.pageAnchorY。
    // 页首缺口（`pageFrameLead`）必须与绘制路径取同值，否则预栅格与 UI 首帧指纹不等 ⇒ 首帧重画。
    //（盒背景/边框不进锚点，理由见该函数 KDoc）。
    val imgs = pageImages.takeIf { it.isNotEmpty() }
    val rawBgs = pageBackgrounds.takeIf { it.isNotEmpty() }
    val frameLead = runCatching { host.pageFrameLead(pos) }.getOrDefault(0)
    val anchorY = ReaderMath.pageAnchorY(
        lineMin = lines.minOfOrNull { it.yTop },
        imgMin = imgs?.minOf { it.yTop },
        bgMin = rawBgs?.minOf { it.yTop },
        frameLead = frameLead,
    ) ?: return false
    val shift = anchorY - contentTop.toInt()
    val bgs = rawBgs?.map { it.shiftedBy(-shift) }.orEmpty()
    val slots = imgs?.map {
        PageImageSlot(it.shiftedBy(-shift), imgCache.get(imgKeyOf(it)))
    }.orEmpty()
    val frame = ReaderMath.shiftToPageFrame(lines, shift)
    val inked = if (frame.any { it.inkColor != inkColor }) {
        frame.map { if (it.inkColor != inkColor) it.copy(inkColor = inkColor) else it }
    } else {
        frame
    }
    val bgImages = rawBgs.orEmpty().mapNotNull { bg ->
        bg.bgSrc?.takeIf { it.isNotBlank() }?.let { bgKeyOf(bg.bgChapterHref, it) }
    }.distinct().mapNotNull { k -> bgCache.get(k)?.let { k to it } }.toMap()

    val key = PageRasterKey(
        chapter = pos.chapter,
        charStart = pos.slice.charStart,
        charEnd = pos.slice.charEnd,
        widthPx = (contentRight - contentLeft).toInt(),
        heightPx = (contentBottom - contentTop).toInt(),
        contentRevision = contentRevision,
        bgColor = pageBg,
        inkColor = inkColor,
    )
    // P0c：整页光栅化搬离 UI 线程（栅格 p50 143ms、高负载 0.5–0.9s，计入帧预算就是冻帧）。
    // 只切「重画」这一段：`store.obtain` 的命中判定在锁外，UI 帧命中零阻塞。
    return withContext(Dispatchers.Default) {
        renderer.preraster(
            lines = inked,
            contentLeft = contentLeft,
            contentRectLeft = contentLeft,
            contentRectTop = contentTop,
            contentRectRight = contentRight,
            contentRectBottom = contentBottom,
            pageBg = pageBg,
            backgrounds = bgs,
            bgImages = bgImages,
            images = slots,
            contentRevision = contentRevision,
            rasterKey = key,
        )
    }
}

/**
 * 预栅格前的等图（与 `ReaderScreen.warmPageImages` 同口径、同 cache）。
 *
 * 返回 true = 该页所有图都已进缓存、可安全栅格；false = 仍有图没备齐（调用方应放弃，
 * 不要把灰占位块栅进池子——见 `prerasterPage` 的 KDoc）。
 */
private suspend fun warmImagesForPreraster(
    host: ReaderHost,
    pos: ReaderPos,
    pageImages: List<orilumn.reader.engine.skia.PageImage>,
    pageBackgrounds: List<PageBackground>,
    imgCache: PageImageCache<DecodedImage>,
    bgCache: PageImageCache<DecodedImage>,
): Boolean {
    val missImgs = pageImages.filter { imgCache.get(imgKeyOf(it)) == null }
    val bgRefs = pageBackgrounds
        .mapNotNull { bg -> bg.bgSrc?.takeIf { it.isNotBlank() }?.let { bg.bgChapterHref to it } }
        .distinct()
    val missBgs = bgRefs.filter { bgCache.get(bgKeyOf(it.first, it.second)) == null }
    if (missImgs.isEmpty() && missBgs.isEmpty()) return true

    // 并行解，与 warmPageImages 同一并发口径（逐张串行会让摄影类一页多图等几秒）。
    //
    // **必须有界**：这段挂在 `AnchorFunnel.beforeCommit` 里，也就是漏斗锁内。某张图
    // 永远解不出来时，落位链就永远不返回 ⇒ 会话停在 Dragging、批注层被藏、点按全被挡
    // （真机症状「点一下，批注全消失、之后翻不动」的一种来源）。超时按「没备齐」处理：
    // 调用方放弃预栅格，翻页照常提交，图由绘制路径异步补齐。
    val warmed = withTimeoutOrNull(PAGE_IMAGE_WARM_TIMEOUT_MS) {
        kotlinx.coroutines.coroutineScope {
            val imgDeferred = missImgs.map { img -> this.async { img to runCatching { host.loadPageImage(img) }.getOrNull() } }
            val bgDeferred = missBgs.map { ref -> this.async { ref to runCatching { host.loadBackgroundImage(ref.first, ref.second) }.getOrNull() } }
            // 整批 putAll：同页多图必须一起留下，否则后一条会把前一条（同页仍在用）淘汰掉。
            imgCache.putAll(imgDeferred.awaitAll().mapNotNull { (img, bmp) -> bmp?.let { imgKeyOf(img) to it } })
            bgCache.putAll(
                bgDeferred.awaitAll().mapNotNull { (ref, bmp) ->
                    bmp?.let { bgKeyOf(ref.first, ref.second) to it }
                },
            )
        }
        true
    } ?: false
    if (!warmed) {
        Logger.w(
            "Orilumn.TAP",
            "preraster warm timeout ch=${pos.chapter} char=${pos.slice.charStart} " +
                "imgs=${missImgs.size} bgs=${missBgs.size}",
        )
        return false
    }

    // 复核：解码失败/超时的图不算备齐（占位块入池就是闪烁的来源）。
    val stillMissing = missImgs.any { imgCache.get(imgKeyOf(it)) == null } ||
        missBgs.any { bgCache.get(bgKeyOf(it.first, it.second)) == null }
    return !stillMissing
}

/**
 * 落定补栅格：版式还没装配出来时最多再等几拍（见 [PrerasterOnPrefill] 的 `settleTick`）。
 *
 * 引擎装配 ±1 页版式是异步的，落定那一拍常常还差一点；用户正在读当前页，等这几拍
 * 是白送的（不像起手后现场栅那样计进用户时延）。超过这个窗口就不再等：宁可这一次
 * 现场栅，也不要在用户已经起手时还占着预栅格锁。
 */
internal const val PRERASTER_SETTLE_RETRY_MS = 120L
internal const val PRERASTER_SETTLE_RETRIES = 4
