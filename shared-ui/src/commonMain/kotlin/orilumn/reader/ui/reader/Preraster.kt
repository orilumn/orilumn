package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * ## 截图窗口 = 当前页 ±1（三页）
 *
 * 引擎在「±1 页分页完成」时抛信号（临时表逐页成形 / 磁盘表 d=1 两侧装配 / 小章全章排完），
 * 用户层据**当前三页截图窗口的需要**立即补栅：一次信号补**两侧各一张**（当前页由绘制同步栅格），
 * 不单独设置优先级、不排队（§0：需要得越快 → 越靠前；排队＝把成本推给用户）。
 * 方向侧先栅（§3.1 每层先方向侧），反侧随后。
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
) {
    val hostRef = rememberUpdatedState(host)
    val onPrefillRef = rememberUpdatedState<suspend (Int, Int) -> Unit>(onPrefillReady)
    val dirRef = rememberUpdatedState(directionProvider)
    val posRef = rememberUpdatedState(currentPos)
    val rendererRef = rememberUpdatedState(renderer)
    val imgCacheRef = rememberUpdatedState(imgCache)
    val bgCacheRef = rememberUpdatedState(bgCache)
    // 闸 2：同一时刻只允许一张预栅格在跑，不排队（排队＝把成本推给用户）。
    val prerasterBusy = remember { mutableStateOf(false) }

    // 几何/颜色随设置与视口变化，重建即重挂信号订阅。
    LaunchedEffect(contentLeft, contentTop, contentRight, contentBottom, pageBg, inkColor, contentRevision, hostRevision) {
        val hostNow = hostRef.value
        val rendererNow = rendererRef.value
        val imgCacheNow = imgCacheRef.value
        val bgCacheNow = bgCacheRef.value
        val dirNow = dirRef.value
        val posNow = posRef.value
        // 三页截图窗口 = 当前页 ±1：每次信号到达，补**两侧各一张**（当前页由绘制同步栅）。
        // 顺序：方向侧优先（§3.1 每层先方向侧；§0 需要得越快越靠前），再补反侧。
        // 不设第二套优先级：抢占仍由唯一钩子负责——栅格未命中时 `onRasterMiss` →
        // `controller.onForegroundRaster()` → `cancelForRaster(PRIO_FLIP)`（§5「唯一钩子」）。
        // 该钩子与翻页的 `notifyFlip()` 同源，但**放行当前章的 ±1 页预排**（`pg:` d=1 两档）：
        // ±1 正是本窗口要截的像素源，砍掉它会让 `pageCache` 永热不起来（真机 137–308ms/次）。
        // 预栅格自己再判一套「要不要让路」只会出错：第三版按「是否会话
        // 目标页」判，把预栅格整个掐死（真机：temp prefill 信号持续在发，preraster 只触发 7 次，
        // 每页仍现场栅 75~179ms ⇒ 每次翻页 200~250ms 空白）。
        //
        // 具名 suspend fun 而非 lambda：`return@...` 在 lambda 里不合法，而这段全是
        // 「条件不满足就地退出」的守卫。
        suspend fun rasterWindow(chapter: Int) {
            // 同一时刻只允许窗口内一轮预栅在跑（两个信号同时到达时不排队——这是去重不是优先级判断）。
            if (prerasterBusy.value) return
            val anchor = posNow() ?: return
            // 只处理当前章：跨章预排由第 2/3 档的整章兜底另行处理。
            if (anchor.chapter != chapter) return
            // 方向 0 = 无记录（开书/跳转/调参），此时前向更可能被用，语义等同向前（§3.2）。
            val effDir = if (dirNow() == 0) 1 else dirNow()
            prerasterBusy.value = true
            try {
                for (d in intArrayOf(effDir, -effDir)) {
                    // **必须用只读的 peekAdjacent**：`adjacent` 是有副作用的真导航（引擎 tempNav
                    // 推进指针），预栅格调它会在无手势时把指针多推一格 ⇒ 真机症状
                    // 「翻页完成后又跳了一页」（19:56:25日志）。
                    val target = runCatching { hostNow.peekAdjacent(anchor, d) }.getOrNull() ?: continue
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
                    )
                }
            } finally {
                prerasterBusy.value = false
            }
        }
        // 引擎抛信号 → 执行栅格（本协程在 UI 线程，与 drawLines 共用 surface 故安全）。
        hostNow.observePrefillReady { chapter, _ -> rasterWindow(chapter) }
    }
}

/**
 * 把一页栅进 [PageRasterStore]（UI 线程，与 `drawLines` 共用 store/surface）。
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
): Boolean {
    val lines = runCatching { host.pageLines(pos) }.getOrNull() ?: return false
    if (lines.isEmpty()) return false
    val pageImages: List<PageImage> = runCatching { host.pageImages(pos) }.getOrNull().orEmpty()
    val pageBackgrounds: List<PageBackground> = runCatching { host.pageBackgrounds(pos) }.getOrNull().orEmpty()

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

    // 与 ReaderPageCanvas 同款对齐：章节绝对 Y → 页坐标系。
    val imgs = pageImages.takeIf { it.isNotEmpty() }
    val rawBgs = pageBackgrounds.takeIf { it.isNotEmpty() }
    val lineMin = lines.minOf { it.yTop }
    val imgMin = imgs?.minOf { it.yTop }
    val bgMin = rawBgs?.minOf { it.yTop }
    val anchorY = listOfNotNull(lineMin, imgMin, bgMin).minOrNull() ?: return false
    val shift = anchorY - contentTop.toInt()
    val bgs = rawBgs?.map { it.copy(yTop = it.yTop - shift, yBottom = it.yBottom - shift) }.orEmpty()
    val slots = imgs?.map {
        PageImageSlot(it.copy(yTop = it.yTop - shift, yBottom = it.yBottom - shift), imgCache.get(imgKeyOf(it)))
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
    return renderer.preraster(
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
    kotlinx.coroutines.coroutineScope {
        val imgDeferred = missImgs.map { img -> this.async { img to runCatching { host.loadPageImage(img) }.getOrNull() } }
        val bgDeferred = missBgs.map { ref -> this.async { ref to runCatching { host.loadBackgroundImage(ref.first, ref.second) }.getOrNull() } }
        for ((img, bmp) in imgDeferred.awaitAll()) if (bmp != null) imgCache.put(imgKeyOf(img), bmp)
        for ((ref, bmp) in bgDeferred.awaitAll()) if (bmp != null) bgCache.put(bgKeyOf(ref.first, ref.second), bmp)
    }

    // 复核：解码失败/超时的图不算备齐（占位块入池就是闪烁的来源）。
    val stillMissing = missImgs.any { imgCache.get(imgKeyOf(it)) == null } ||
        missBgs.any { bgCache.get(bgKeyOf(it.first, it.second)) == null }
    return !stillMissing
}
