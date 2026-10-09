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
 * ## 翻页只需一页
 *
 * 每次只栅格**方向侧**那一张（§3.2 方向记录）。三窗口已有：`PAGE_CACHE_BYTES` 56MB ÷
 * 单页约 14MB = 4 槽，落定时补一张、LRU 自动淘汰最旧，不用改容量策略。
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
     * 让路闸：返回 true 表示**不要**预栅格（此刻用户更需要别的页）。
     *
     * 判据是「有没有交互在飞」——拖动中、结算动画中、落位在途中。预栅格是投机活
     * （用户不等它），按§3.6「抢占代替等待」必须给第 1 档让路，而不是等静默。
     */
    gate: () -> Boolean = { false },
) {
    val hostRef = rememberUpdatedState(host)
    val onPrefillRef = rememberUpdatedState<suspend (Int, Int) -> Unit>(onPrefillReady)
    val dirRef = rememberUpdatedState(directionProvider)
    val posRef = rememberUpdatedState(currentPos)
    val rendererRef = rememberUpdatedState(renderer)
    val imgCacheRef = rememberUpdatedState(imgCache)
    val bgCacheRef = rememberUpdatedState(bgCache)
    // 闸 1：会话在飞就让路（用户马上要用别的页）。经引用读，避免捕获旧闭包。
    val gateRef = rememberUpdatedState(gate)
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
        // 具名 suspend fun 而非 lambda：`return@...` 在 lambda 里不合法，而这段全是
        // 「条件不满足就地退出」的守卫。
        suspend fun rasterDirectionSide(chapter: Int) {
            // **第 1 档优先于一切**（§3.6：抢占代替等待）。预栅格是投机活——用户不等它，
            // 它绝不能跟「用户正想翻到的那页」抢主线程。真机教训：预栅格跑在 UI 线程
            //（与 drawLines 共用 surface 的约束），一次 300~580ms，撞上滑动就是卡顿。
            // 三道闸，任一命中就让路：
            //   1. 会话在飞（正在拖/正在结算/落位在途）⇒ 用户马上要用别的页，让路；
            //   2. 上一张预栅格还在跑 ⇒ 不排队（排队只会把成本推给用户），让路；
            //   3. 已在渲染层调用 ⇒ 让路（§0：第 1 档「渲染」半边优先）。
            if (gateRef.value()) return
            if (prerasterBusy.value) return
            prerasterBusy.value = true
            try {
                // 方向 0 = 无记录（开书/跳转/调参），此时前向更可能被用，语义等同向前（§3.2）。
                val effDir = if (dirNow() == 0) 1 else dirNow()
                val anchor = posNow() ?: return
                // 只处理当前章：跨章预排由第 2/3 档的整章兜底另行处理。
                if (anchor.chapter != chapter) return
                val target = runCatching { hostNow.adjacent(anchor, effDir) }.getOrNull() ?: return
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
            } finally {
                prerasterBusy.value = false
            }
        }
        // 引擎抛信号 → 执行栅格（本协程在 UI 线程，与 drawLines 共用 surface 故安全）。
        hostNow.observePrefillReady { chapter, _ -> rasterDirectionSide(chapter) }
    }
}

/**
 * 把一页栅进 [PageRasterStore]（UI 线程，与 `drawLines` 共用 store/surface）。
 *
 * 取数口径与 [rememberPageContent] 逐条对齐（`pageLines` / `pageImages` / `pageBackgrounds`
 * + `imgKeyOf`/`bgKeyOf` 命中缓存），否则指纹不等、首帧仍会重画。
 */
internal fun prerasterPage(
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