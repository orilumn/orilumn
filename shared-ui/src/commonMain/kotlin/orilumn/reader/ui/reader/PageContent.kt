package orilumn.reader.ui.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.DrawLine
import orilumn.reader.engine.skia.PageBackground
import orilumn.reader.engine.skia.PageImage

/**
 * 跨页缓存键（稳定身份，与几何无关）：同图跨页 `yTop/widthPx` 不同也命中。
 *
 * 键里刻意**不含几何**：插图在两页上可能尺寸/位置不同，但那是同一次解码，
 * 按几何分键会让同一张图重复解码、内存翻倍。
 */
internal fun imgKeyOf(img: PageImage): String = "${img.chapterHref}|${img.src}|${img.widthPx}"

/** 背景图缓存键。 */
internal fun bgKeyOf(href: String, src: String): String = "$href|$src"

/**
 * 一页的绘制输入（用户层）：行窗口 + 插图 + 背景 + 页身份键。
 *
 * 抽它出来是为了 P1 的双页渲染——跟手滑动期间要同时持有**当前页与目标页**两套内容，
 * 而这在 P1 之前是不可能的：`ReaderScreen` 里 `pos → 页内容` 整段只对当前 `openPos`
 * 算一次，翻页又是 UP 时才换 `openPos`。数据结构与绘制路径都不变，只是把「对某个 pos
 * 求值」变成可复用的纯函数步骤（见 [rememberPageContent]）。
 *
 * 逐字段对应原 `ReaderScreen` 内联的那几段，语义与 remember 键**一字不改**
 * （本步是纯重构，验收线是像素全等——见设计文档 §11.1 第 2 步）。
 *
 * **[Immutable] + `data class`**：给 `ReaderPageCanvas` 当唯一内容入参用。Compose 的
 * 强跳过只能按实例比不稳定参数，而本类每次重组都会新建实例 ⇒ 不标不可变、不给值相等，
 * 画布每帧都无法跳过（每帧重走 drawLines）。标不可变后按值比较，字段多是 remember
 * 出来的同一实例，比较是廉价的短路相等。
 */
@Immutable
data class PageContent(
    val lines: List<DrawLine>?,
    val pageImages: List<PageImage>?,
    val imageBitmaps: Map<PageImage, DecodedImage>,
    val pageBackgrounds: List<PageBackground>?,
    val bgImages: Map<String, DecodedImage>,
    val rasterKey: PageRasterKey?,
)

/**
 * 对**任意** [pos] 求一页的绘制输入（commonMain）。
 *
 * 为什么是 `@Composable`：插图/背景图的解码是异步的（`LaunchedEffect` + 并发 `async`），
 * 首帧只有几何、位图后到。把它藏进一个 composable 后，调用方不必知道「哪些字段是同步的、
 * 哪些会晚一帧」，也不会漏抄 `remember` 键——这类漏抄的后果是静默的旧像素。
 *
 * @param contentWidthPx/HeightPx 栅格尺寸（内容区），进 [PageRasterKey]：视口变了像素就得重画。
 */
@Composable
fun rememberPageContent(
    host: ReaderHost,
    pos: ReaderPos?,
    contentRevision: Int,
    hostRevision: Int,
    contentWidthPx: Int,
    contentHeightPx: Int,
    bgColor: Int,
    inkColor: Int,
    imgCache: PageImageCache<DecodedImage>,
    bgCache: PageImageCache<DecodedImage>,
): PageContent? {
    val currentHost by rememberUpdatedState(host)
    // 不用 `if (pos == null) return null` 提前返回：那样 remember 的调用次数会随 pos
    // 摆动而变（Compose 允许条件 composable，但两个调用点共用本页时极易记错槽）。
    // 这里让 pos 可空贯穿始终，返回值末尾再判 null。

    val lines = remember(pos, contentRevision, hostRevision) {
        pos?.let { currentHost.pageLines(it) }
    }
    // 盒背景/边框：与行同一切片口径，画布内画在文字之下（翻页即随 pos 刷新）。
    val pageBackgrounds = remember(pos, contentRevision, hostRevision) {
        pos?.let { runCatching { currentHost.pageBackgrounds(it) }.getOrNull() }
    }
    // 插图几何与位图：几何同步取（廉价）；位图跨页 LRU 缓存（`imgCache`，与几何无关的
    // 稳定身份为键），回访页首帧即有图；未命中才异步解码入库。翻页不再清空旧图。
    val pageImages = remember(pos, contentRevision, hostRevision) {
        pos?.let { runCatching { currentHost.pageImages(it) }.getOrNull() }
    }
    var imageBitmaps by remember(pos, contentRevision, hostRevision) {
        mutableStateOf(pageImages
            ?.mapNotNull { img -> imgCache.get(imgKeyOf(img))?.let { img to it } }
            ?.toMap(LinkedHashMap()) ?: emptyMap())
    }
    LaunchedEffect(pos, contentRevision, hostRevision, pageImages) {
        val imgs = pageImages?.takeIf { it.isNotEmpty() } ?: run {
            imageBitmaps = emptyMap()
            return@LaunchedEffect
        }
        val missing = imgs.filter { imgCache.get(imgKeyOf(it)) == null }
        if (missing.isEmpty()) {
            // 全命中：首帧已由上面的 remember 直接摆出，无灰闪、无重组。
            val full = imgs.mapNotNull { img -> imgCache.get(imgKeyOf(img))?.let { img to it } }
                .toMap(LinkedHashMap(imgs.size))
            if (full != imageBitmaps) imageBitmaps = full
            return@LaunchedEffect
        }
        // 并行解码（摄影类一页多图时串行要几秒，全程灰块）：每图一个 async，
        // 先全部并发再统一收敛，避免逐张 setState 反复重组。
        val deferred = missing.map { img ->
            async {
                img to runCatching { currentHost.loadPageImage(img) }.getOrNull()
            }
        }
        for ((img, bmp) in deferred.awaitAll()) {
            if (bmp != null) imgCache.put(imgKeyOf(img), bmp)
        }
        imageBitmaps = imgs.mapNotNull { img -> imgCache.get(imgKeyOf(img))?.let { img to it } }
            .toMap(LinkedHashMap(imgs.size))
    }

    // P3-b 背景图：按 url 跨页缓存（同上）；失败项直接缺席（該幅只留底色），不入库。
    var bgImages by remember(pos, contentRevision, hostRevision) { mutableStateOf<Map<String, DecodedImage>>(emptyMap()) }
    LaunchedEffect(pos, contentRevision, hostRevision, pageBackgrounds) {
        val refs = pageBackgrounds?.mapNotNull { bg ->
            bg.bgSrc?.takeIf { it.isNotBlank() }?.let { bg.bgChapterHref to it }
        }?.distinct().orEmpty()
        if (refs.isEmpty()) {
            bgImages = emptyMap()
            return@LaunchedEffect
        }
        val cached = refs.mapNotNull { ref ->
            bgCache.get(bgKeyOf(ref.first, ref.second))?.let { bgKeyOf(ref.first, ref.second) to it }
        }.toMap(LinkedHashMap(refs.size))
        val missing = refs.filter { bgCache.get(bgKeyOf(it.first, it.second)) == null }
        if (missing.isEmpty()) {
            if (cached != bgImages) bgImages = cached
            return@LaunchedEffect
        }
        val deferred = missing.map { ref ->
            async {
                bgKeyOf(ref.first, ref.second) to runCatching {
                    currentHost.loadBackgroundImage(ref.first, ref.second)
                }.getOrNull()
            }
        }
        for ((key, bmp) in deferred.awaitAll()) {
            if (bmp != null) bgCache.put(key, bmp)
        }
        bgImages = refs.mapNotNull { ref -> bgCache.get(bgKeyOf(ref.first, ref.second))?.let { bgKeyOf(ref.first, ref.second) to it } }
            .toMap(LinkedHashMap(refs.size))
    }

    // P0b 页身份键：页 + 视口 + 修订号 + 主题色 → 渲染器多页位图缓存的下标。
    // 少任何一项都会静默复用旧像素（视口变、字重变、换色都必须重画）。
    val rasterKey = remember(pos, contentRevision, hostRevision, contentWidthPx, contentHeightPx, bgColor, inkColor) {
        pos?.let {
            PageRasterKey(
                chapter = it.chapter,
                charStart = it.slice.charStart,
                charEnd = it.slice.charEnd,
                widthPx = contentWidthPx,
                heightPx = contentHeightPx,
                contentRevision = contentRevision,
                bgColor = bgColor,
                inkColor = inkColor,
            )
        }
    }

    if (pos == null) return null
    return PageContent(
        lines = lines,
        pageImages = pageImages,
        imageBitmaps = imageBitmaps,
        pageBackgrounds = pageBackgrounds,
        bgImages = bgImages,
        rasterKey = rasterKey,
    )
}
