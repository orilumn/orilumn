package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.io.Logger
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 落位前等图超时（ms）：目标页缺图时最多等这一拍再提交定位，超时按缺图提交（老的异步补齐照常）。
 * 以后翻页动画截图直接复用预热好的位图，故预热是必经之路，不是可选优化。
 */
private const val PAGE_IMAGE_WARM_TIMEOUT_MS = 800L

// 跨页缓存键 imgKeyOf / bgKeyOf 已搬到 PageContent.kt（P1-2：页内容成模块后它们有两个用户）。

/**
 * S28 阅读面（shared-ui commonMain）：画布 + 手势 + 进度 + 亮度遮罩 + 字体切换逻辑的统一载体。
 *
 * 平移自 Android `ReaderActivity` 的 Compose 覆盖层与渲染入口，平台差异全部收敛到 [ReaderHost]
 * 与回调：
 *  - 画布：经 [ReaderPageCanvas] 用 engine-skia [orilumn.reader.engine.skia.LineWindowDrawer] 画宿主给的
 *    行窗口（替换旧 StaticLayout `BodyPageView`）；
 *  - 手势：CMP `pointerInput` 重写旧 `FlipGestureDetector`（三区点按 / 水平滑翻页 / 左|右 1/3 单指
 *    垂直亮度；双指垂直亮度暂未平移，见注释）；判定数学在 [ReaderMath]，本组件只装配；
 *  - 进度：[ReaderBars] 顶/底栏 + [orilumn.reader.ui.reader.ThinSlider]（`ReaderBars` 私有），宿主
 *    归一化 [ReaderHost.pageProgress]；
 *  - 亮度遮罩：[ReaderLightMask] 纯绘制（物理背光经 [onLightChange]/[onLightCommit] 上报宿主）；
 *  - 字体切换：[ReaderMath.fontSlotFor] 路由已迁（工程侧把槽位别名喂进 Skia FontCollection，宿主职责）。
 *
 * 状态机：打开后 `openPos` 为空 → 仅背页；翻页/跳转走宿主 suspend 后落位，且防抖保存
 * （[onSaveProgress]，500ms，复刻 Android `scheduleSave`）。设置面板/目录抽屉是独立组件
 * （[ReaderSettingsPanel]/[ReaderTocPanel]，S29）；书签/笔记占位未接线（onBookmark/onNote 为 null）
 * 时经 CMP Snackbar（[rememberReaderSnackbar]，替代 Android Toast）提示。
 */
@Composable
fun ReaderScreen(
    host: ReaderHost,
    // 第 1 档「渲染」半边的抢占钩子（栅格未命中时让后台预排让路）；默认接宿主实现。
    onRasterMiss: () -> Unit = { host.onRasterMiss() },
    settings: ReaderSettings,
    statusBarInset: Dp = 0.dp,
    onBack: () -> Unit = {},
    onNight: () -> Unit = {},
    onSettings: () -> Unit = {},
    onToc: () -> Unit = {},
    onBookmark: (() -> Unit)? = null,
    onNote: (() -> Unit)? = null,
    onLightChange: (ReaderSettings) -> Unit = {},
    onLightCommit: (ReaderSettings) -> Unit = {},
    /** Q1-b：顶/底栏显隐同步（Android 宿主据此显隐系统栏 chrome；桌面/其它平台可忽略）。 */
    onBarsVisibleChanged: (Boolean) -> Unit = {},
    /** Q1-b：外部推送的落位（版式重排绑定 / 目录跳转落地后同步）。非空即采用为当前定位并防抖
     *  保存；null 不动作（重排以同一目标字符合位，字符仍在新分页表里即可连续阅读）。 */
    externalPos: ReaderPos? = null,
    /** openPos 丢失时的回退定位（activity 侧 currentPos，每次落位更新）。
     *  Screen 重进 composition 会清空 remember，而 host 稳定时 LaunchedEffect 不重跑 open()，
     *  openPos 将永久为 null——此前 flip/jump/seek 静默吞动作（"点了没反应"）。
     *  有回退即用（引擎侧 locateTempPosition 可重定位陈旧 slice），都没有才丢弃并落盘。 */
    fallbackPos: ReaderPos? = null,
    /**
     * 版式版本号（宿主每次重排绑定+1）：行/图/背景的 `remember` 键随之刷新。字体等只换字形
     * 不断行的变更分页不变、新旧 pos 相等，不带本号行数据永远是旧的（重启才生效的根因）。
     */
    contentRevision: Int = 0,
    /**
     * 键盘翻页是否启用：设置/目录面板打开时为 false（按键留给面板，阅读面不翻页）。
     * 桌面壳经 `ReaderView` 按面板状态传入。
     */
    keysEnabled: Boolean = true,
) {
    val density = LocalDensity.current.density
    val profile = remember(settings, density) { TypographicProfile.build(settings, density) }

    var openPos by remember { mutableStateOf<ReaderPos?>(null) }
    var openFailed by remember { mutableStateOf(false) }
    // 封面页（用户层前置页，只读不存档）：coverVisible 时画封面盖住正文页，
    // openPos 仍为书里位置，供目录/跳转/存档照常工作。
    var coverVisible by remember { mutableStateOf(false) }
    var coverBmp by remember { mutableStateOf<ImageBitmap?>(null) }
    // 本代（宿主/排版）内用户是否已显式离开首位：离开后落回首位不再自动弹封面
    //（回翻专用通道仍可进）；换代即重置。
    var coverDismissed by remember { mutableStateOf(false) }
    // 换代结算中：首字符页先画底色占位，不抢画正文——否则正文闪一帧再被封面盖。
    // 默认 true：首帧组合先于 effect，openPos 落位那一拍来不及置 true 就会先画一行正文
    // （首次开书闪正文首页即此）；误伤不了非首位（holding 门限 charStart==0）与无封面书
    // （coverImage 回 null 即落回正文，只多一帧底色）。
    var coverResolving by remember { mutableStateOf(true) }
    // 宿主代际：open() 落定即 +1，行/图/背景 remember 键随之刷新——同 pos 也重取，
    // 换字体不断行时不滞留旧字、不白屏（open 落定前行数据恒有旧值可显）。
    var hostRevision by remember { mutableIntStateOf(0) }
    var barsVisible by remember { mutableStateOf(false) }
    // 上下栏实测高度（px）：栏区落点的手势归栏，不进翻页层（点栏按钮漂移误翻页的门控）。
    var topBarH by remember { mutableStateOf(0) }
    var botBarH by remember { mutableStateOf(0) }
    // 亮度工作快照：初始 = 传入设置；亮度手势只在本地改 brightness，物理背光经回调通知宿主。
    var light by remember { mutableStateOf(settings) }
    // 设置面板/外部 commit 驱动的亮度与护眼修改同步进本地工作快照（亮度手势期间 live 值由
    // onLightChange 直达宿主，此 effect 在宿主回写 settings 后收敛，二者同值不冲突）。
    LaunchedEffect(settings) { light = settings }
    var brightnessUi by remember { mutableStateOf<BrightnessGestureUi?>(null) }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    // ---- P1 L1 整幅滑动 ----
    // 会话状态机（纯逻辑见 FlipSession）。这里只持有「渲染需要的那几个数」：
    // progress 驱动位移、direction 定方向、targetPos 是目标页身份（决定第二张画布）。
    val flipSession = remember { FlipSession() }
    // 逐帧写 Compose 状态：graphicsLayer 的 lambda 每帧重读它，避免重组。
    var slideProgress by remember { mutableFloatStateOf(0f) }
    var slideDirection by remember { mutableIntStateOf(0) }
    // 起手那一页（正被拖走的那张）。落位在起手就发生，所以 openPos 已经是目标页了，
    // 「当前页」必须另存一份，否则动画画的是已经换掉的页。
    var slideFromPos by remember { mutableStateOf<ReaderPos?>(null) }
    var slideTargetPos by remember { mutableStateOf<ReaderPos?>(null) }
    var slidePrefetch by remember { mutableStateOf<Job?>(null) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    // L1 只在「动画总闸开 + 模式为 slide」时生效；curl 暂落 L0（P3 接 3D）。
    val slideEnabled = light.pageAnim && light.pageAnimationMode == "slide"

    val scope = rememberCoroutineScope()
    // 锚页事件串行漏斗：显示状态的唯一写入通道（见 AnchorFunnel）。所有改锚页位置的动作
    // （翻页/跳转/开书/外部落位/开链接）走它串行，后到按落定后的最新位置重取源，不再各算各的。
    val anchorFunnel = remember { AnchorFunnel() }
    val currentHost by rememberUpdatedState(host)
    // 跨页位图缓存（随宿主换代重建：换书即新池；同书内翻页/改参常驻，回访页首帧即有图）。
    // 键是稳定身份（与几何无关），容量按字节 LRU（大截图多的书自动腾退）。
    // P0a：正文插图与背景图同为 skia `DecodedImage`（插图已下沉进页位图，不再是 Compose 位图）。
    val imgCache = remember(currentHost) {
        PageImageCache<orilumn.reader.engine.skia.DecodedImage>(
            sizeOf = { (it.image.width * it.image.height * 4L).coerceAtLeast(1L) })
    }
    val bgCache = remember(currentHost) {
        PageImageCache<orilumn.reader.engine.skia.DecodedImage>(
            sizeOf = { (it.image.width * it.image.height * 4L).coerceAtLeast(1L) })
    }
    // 跨页位图缓存（随宿主换代重建：换书即新池；同书内翻页/改参常驻，回访页首帧即有图）。
    // 键是稳定身份（与几何无关），容量按字节 LRU（大截图多的书自动腾退）。
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnNight by rememberUpdatedState(onNight)
    val currentOnSettings by rememberUpdatedState(onSettings)
    val currentOnToc by rememberUpdatedState(onToc)
    val currentOnBookmark by rememberUpdatedState(onBookmark)
    val currentOnNote by rememberUpdatedState(onNote)
    val currentOnLightChange by rememberUpdatedState(onLightChange)
    val currentOnLightCommit by rememberUpdatedState(onLightCommit)
    val currentOnBarsVisibleChanged by rememberUpdatedState(onBarsVisibleChanged)
    // 手势 handler 常驻（key 只有 touchSlop）：栏显隐/栏高经引用读，不重启手势流。
    val currentBarsVisible by rememberUpdatedState(barsVisible)
    val currentTopBarH by rememberUpdatedState(topBarH)
    val currentBotBarH by rememberUpdatedState(botBarH)
    // L1 滑动的开关与三个动作也必须经引用读（声明在下方 beginSlide/updateSlide/endSlide
    // 之后——Kotlin 局部函数不可前向引用）。pointerInput 的 block 只在 touchSlop 变化时
    // 重建，直接捕获 val 会拿到**首次组合时**的快照——设置里刚打开「翻页动画」，手势那边
    // 还当关着，且不报错（只是滑不动，极难察觉）。

    // 打开书籍并定位起始页（自动续读/首页）。落定即推代际：同 pos 也刷新行数据。
    // 经锚页漏斗：与在途导航互斥，首帧后放行。漏斗 BUSY-DROP 时补一次重试，
    // 否则 open 是一次性事件——被吞即永久空白，无下一次点按来救。
    LaunchedEffect(currentHost) {
        suspend fun doOpen(): ReaderPos? {
            // 先占位再落位：openPos 一提交组合即画，effect 的置 true 赶不上首帧。
            coverResolving = true
            return anchorFunnel.push("open", { openPos = it }) {
                val p = currentHost.open()
                openFailed = p == null
                hostRevision++
                p
            }
        }
        if (doOpen() == null && openPos == null) {
            kotlinx.coroutines.delay(300)
            doOpen()
        }
    }

    // 封面页装配：开书/重排后落在全书第一内容页且有封面 → 先展示封面（只读，不存档）。
    // 首位判定只看（首章 + 首字符），不比较整页切片：重排落位是行锚页（blockStart=-1），
    // 内存表拒绝回填旧表，`bookStart()` 切片恒旧——对象比较永不等（窗口拉伸丢封面根因）。
    var coverStartChapter by remember { mutableStateOf<Int?>(null) }
    var coverHost by remember { mutableStateOf<ReaderHost?>(null) }
    var coverRev by remember { mutableIntStateOf(-1) }
    // 首位判定（本函数三处同式）：有封面 + 章节是首章 + 切片首字符为 0。
    // 不比较整页切片对象（见上）。
    fun isBookStart(p: ReaderPos?): Boolean {
        if (p == null || coverBmp == null || coverStartChapter == null) return false
        return p.chapter == coverStartChapter && p.slice.charStart == 0
    }
    LaunchedEffect(openPos, hostRevision, contentRevision, currentHost) {
        // 只有宿主换代才清缓存（新控制器新布局）：排版变化（contentRevision）不清，
        // 封面字节与首章号都不漂移；翻页（openPos 变化）更不清。
        // 但显式离开标记随排版换代重置：不换宿主时调参/拉伸只换版本号，
        // 不重置则一次离开后封面永不再弹（拉伸必跳第二页根因）。
        if (currentHost !== coverHost) {
            coverHost = currentHost
            coverRev = contentRevision
            coverBmp = null
            coverStartChapter = null
            coverVisible = false
            coverDismissed = false
        } else if (contentRevision != coverRev) {
            coverRev = contentRevision
            val pNow = openPos
            if (pNow == null || !isBookStart(pNow)) {
                coverDismissed = false
            }
            // 当前正好在书首时，不重置 dismissed，避免调参时闪回封面
        }
        // open 落位前不查：与开书解析并发必撞锁/竞态（首章 check），查也白查。
        val p = openPos ?: return@LaunchedEffect
        // 结算中首字符页画底色占位，不抢画正文。finally 落旗：取消即重算，不卡死。
        coverResolving = true
        try {
            // 取消异常重抛（`runCatching` 会吞取消，effect 重启即卡死）；
            // 其余异常回 null。
            if (coverBmp == null) {
                coverBmp = try {
                    currentHost.coverImage()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    null
                }
            }
            // 首章号查不到退避重试（开书并发期解析未就绪是常态，最多约 1.2s）。
            // 必须用纯查询 firstContentChapter：bookStart() 是带落位+逐出的
            // 导航（openChapterStart），续读位在书首 ±30 章之外时会把正在
            // 渲染的远章逐出，而本 effect 丢弃落位结果、阅读面仍按续读位
            // 取页 → pageLines 拿不到版式 → 白屏（FirstContentChapterProbeTest
            // 锁住该回归）。
            if (coverBmp != null && coverStartChapter == null) {
                repeat(6) {
                    coverStartChapter = try {
                        currentHost.firstContentChapter()
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                    if (coverStartChapter != null) return@repeat
                    delay(200)
                }
            }
        } finally {
            coverResolving = false
        }
        if (coverBmp == null) return@LaunchedEffect
        // 显隐全权在此赋值：首位 && 未离开即展示，否则隐藏。
        // 落位处不清（清了再弹就是一帧闪）；离开封面的唯一出口是前进翻页显式关闭。
        orilumn.reader.io.Logger.d("Orilumn.COVER",
            "decide bmp=${coverBmp != null} startCh=$coverStartChapter pos=${p.chapter}:${p.slice.charStart} dismissed=$coverDismissed")
        coverVisible = isBookStart(p) && !coverDismissed
    }

    // 落位统一入口：更新当前定位并防抖保存（复刻 Android scheduleSave 500ms）。
    // 只记 dismissed，不碰 coverVisible（显隐全权归下面 effect 按"首位 && 未离开"赋值；
    // 落位处先关再弹就是那一帧正文闪）。
    fun markPositionChanged(next: ReaderPos) {
        openPos = next
        // 封面解析未完成时 isBookStart 恒 false（缺 bmp/首章号）——那是"未知"而非
        // "非书首"：开书探针重排/调参重排的落位推送先于封面解析到达时（启动继续阅读、
        // 开书后立即调参），会误记 dismissed，封面解析完成后 coverVisible 恒 false、
        // 封面永不再弹（封面跳正文首页根因）。仅在封面已解析且确实离开首位时记离开；
        // 未知则不动，由封面 effect 按落定后的解析结果结算显隐。
        if (coverBmp != null && coverStartChapter != null && !isBookStart(next)) {
            coverDismissed = true
        }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            currentHost.onSaveProgress(next)
            saveJob = null
        }
    }

    // Q1-b：外部推送落位（键 = externalPos，值变化即认领；重排绑定/TOC 落地后 activity 推送）。
    // 经锚页漏斗：与在途导航互斥，值在锁内重取（取落定后的最新推送）。
    LaunchedEffect(externalPos) {
        anchorFunnel.push("external", ::markPositionChanged) {
            openFailed = false
            externalPos
        }
    }

    // ---- 定位动作（host 为 suspend，统一挂到本组件作用域） ----
    /**
     * 落位前等图（导航提交前预热）：目标页缺的插图/背景图并行解出来进跨页缓存再提交定位，
     * 首帧即整页。超时兜底（大图页最多慢这一拍，超时按缺图提交、老的异步补齐照常）。
     * 以后翻页动画截图直接复用这批已解码位图。
     */
    suspend fun warmPageImages(pos: ReaderPos) {
        val imgs = runCatching { currentHost.pageImages(pos) }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: emptyList()
        val bgs = runCatching { currentHost.pageBackgrounds(pos) }.getOrNull()
            ?.mapNotNull { bg -> bg.bgSrc?.takeIf { it.isNotBlank() }?.let { bg.bgChapterHref to it } }
            ?.distinct() ?: emptyList()
        val missImgs = imgs.filter { imgCache.get(imgKeyOf(it)) == null }
        val missBgs = bgs.filter { bgCache.get(bgKeyOf(it.first, it.second)) == null }
        if (missImgs.isEmpty() && missBgs.isEmpty()) return
        withTimeoutOrNull(PAGE_IMAGE_WARM_TIMEOUT_MS) {
            val imgJobs = missImgs.map { img ->
                async { img to runCatching { currentHost.loadPageImage(img) }.getOrNull() }
            }
            val bgJobs = missBgs.map { ref ->
                async { ref to runCatching { currentHost.loadBackgroundImage(ref.first, ref.second) }.getOrNull() }
            }
            for ((img, bmp) in imgJobs.awaitAll()) {
                if (bmp != null) imgCache.put(imgKeyOf(img), bmp)
            }
            for ((ref, bmp) in bgJobs.awaitAll()) {
                if (bmp != null) bgCache.put(bgKeyOf(ref.first, ref.second), bmp)
            }
        }
    }
    /** openPos 优先，回退 fallbackPos（activity currentPos），都没有才丢弃——永不静默。 */
    fun resolveNavPos(action: String): ReaderPos? {
        openPos?.let { return it }
        fallbackPos?.let {
            Logger.d("Orilumn.TAP", "$action openPos null → fallback ch=${it.chapter} char=${it.slice?.charStart}")
            return it
        }
        Logger.d("Orilumn.TAP", "$action DROPPED (openPos null, no fallback)")
        return null
    }
    /**
     * 落位并**等待结果**：返回真正落定的 pos；被 BUSY-DROP / 边界 / 无源时返回 null。
     *
     * 为什么要「可等待」而不是发射后不管：翻页动画提交后必须核对「数据真的换了吗」，
     * 换不了就得把动画拨回原页（见 [endSlide]）。发射后立刻返回的话那次核对必然
     * 读到着陆前的 `openPos`，恒判失败 ⇒ 每次提交都回滚 ⇒ 观感是「翻到位又弹回去」。
     */
    suspend fun flipAwait(direction: Int): ReaderPos? {
        // 封面页内翻页：前进回正文第一页（现查 fresh 落位，走漏斗，可存档），
        // 落定即记显式离开（否则推送带来的 effect 重算又弹回去）；封面已是第一页，后退无操作。
        if (coverVisible) {
            if (direction > 0 && coverBmp != null) {
                val target = runCatching { currentHost.bookStart() }.getOrNull() ?: return null
                val landed = anchorFunnel.navigate(
                    action = "cover-forward",
                    read = { target },
                    commit = ::markPositionChanged,
                ) { target }
                if (landed != null) {
                    coverDismissed = true
                    coverVisible = false
                }
                return landed
            }
            return null
        }
        // 正文第一页回翻且有封面 → 进封面（只读，不经过引擎翻页/存档）。
        if (direction < 0 && isBookStart(openPos)) {
            coverVisible = true
            return null
        }
        return anchorFunnel.navigate(
            action = "tap-flip",
            read = { resolveNavPos("tap-flip") },
            commit = ::markPositionChanged,
        ) { p ->
            Logger.d("Orilumn.TAP", "tap-flip dispatch dir=$direction from ch=${p.chapter} char=${p.slice.charStart}")
            val landed = currentHost.adjacent(p, direction)
            // 引擎侧无声吞翻页时（返回 null），这里是唯一目击者——与 Orilumn.FLIP 对账。
            Logger.d("Orilumn.TAP", "tap-flip result dir=$direction " + (landed?.let { "landed ch=${it.chapter} char=${it.slice.charStart}" } ?: "NULL (engine returned null)"))
            if (landed != null) warmPageImages(landed)
            landed
        }
    }

    fun flip(direction: Int) {
        scope.launch { flipAwait(direction) }
    }

    fun jumpChapter(direction: Int) {
        scope.launch {
            anchorFunnel.navigate(
                action = "jump-chapter",
                read = { resolveNavPos("jump-chapter") },
                commit = ::markPositionChanged,
            ) { p -> currentHost.neighborChapterStart(p.chapter, direction)?.also { warmPageImages(it) } }
        }
    }

    /**
     * 清场（只清动画状态，不动数据）。
     *
     * 数据要不要退回由调用方决定：commit 路径数据已落定，直接清；rollback 路径要先
     * `flipAwait(-dir)` 把引擎指针翻回原页再清（见 [endSlide]）。
     */
    fun clearSlide() {
        settleJob?.cancel()
        settleJob = null
        slidePrefetch?.cancel()
        slidePrefetch = null
        flipSession.abort()
        slideFromPos = null
        slideTargetPos = null
        slideProgress = 0f
        slideDirection = 0
    }

    /**
     * 动画作废**并把数据退回原页**。
     *
     * 用于：落位失败、或外部改写了 openPos。起手已经落位过一次，所以这里必须翻回去，
     * 否则引擎指针停在新页而画面回原页 ⇒ 下次翻页从错的页起（静默错页）。
     */
    fun rollbackSlide(navigateBack: Boolean = true) {
        val back = if (navigateBack) slideFromPos else null
        val dir = slideDirection
        clearSlide()
        if (back != null && dir != 0) {
            scope.launch {
                val landed = flipAwait(-dir)
                Logger.d(
                    "Orilumn.TAP",
                    "slide-rollback data dir=${-dir} landed=${landed?.let { "ch=${it.chapter} char=${it.slice.charStart}" } ?: "NULL"}",
                )
            }
        }
    }

    // ---- P1 L1：拖动开始（落位一次） / 拖动中（跟手） / 松手（结算或回退） ----

    /**
     * 拖动起手：**就地落位一次**，把落定结果当作本次动画的目标页。
     *
     * 这里是 P1 最容易写错的地方，代价是「翻到位又弹回原页」，记下来别再犯：
     *
     * 1. `ReaderHost.adjacent()` **不是只读查询，是有副作用的真导航**——引擎侧
     *    `tempNav` 会 `ip.curIndex = next`，真把临时表指针挪到下一页。曾在这里
     *    「预取」一次、松手落位时再调一次，第二次引擎已不在源页上，返回原页 ⇒
     *    落位落回原处 ⇒ 观感正是「翻上页停稳后又跳回上一页」（真机日志：
     *    `want=char=4751 landed=char=5539`）。⇒ 一次翻页**只能调一次** adjacent。
     *
     * 2. 因此改回设计文档 §6.2 的「**先落位（数据）后出画（像素）**」：起手就落位，
     *    动画层拿已落定的 pos 当目标页画，拖动期间不再问引擎。松手时 commit 无需
     *    再落位（数据早在起手就落了），rollback 才 `flipAwait(-dir)` 翻回去。
     *
     * 落位仍走 [anchorFunnel]，动画层不新增第二个落位通道。
     */
    fun beginSlide(direction: Int) {
        if (!slideEnabled || coverVisible) return
        val src = openPos ?: return
        flipSession.onDown()
        slideFromPos = src
        slideTargetPos = null
        slidePrefetch?.cancel()
        slidePrefetch = scope.launch {
            val landed = flipAwait(direction)
            // 边界 / 无源 / BUSY-DROP：没有目标页，这次手势不动画（画面保持原样）。
            // 必须在 await 之后再读 phase——期间可能已经松手结算完了。
            if (landed == null) {
                Logger.d("Orilumn.TAP", "slide-begin no target dir=$direction ⇒ no animation")
                if (flipSession.phase == FlipSession.Phase.Dragging) rollbackSlide()
                return@launch
            }
            slideTargetPos = landed
        }
    }

    /**
     * 拖动中：把位移喂给状态机，逐帧写回渲染用的 progress。
     *
     * **不要在这里预判 phase == Idle**：Idle → Dragging 的迁移正是 [FlipSession.onDrag]
     * 干的活，先判掉就永远进不了 Dragging（progress 恒 0、endSlide 永不触发），
     * 症状是「完全拖不动」且不报错。状态机自己会在 Settling 时返回 false。
     */
    fun updateSlide(dx: Float, pageW: Float) {
        if (!slideEnabled) return
        if (!flipSession.onDrag(dx, pageW)) return
        slideProgress = flipSession.progress
        slideDirection = flipSession.direction
    }

    /**
     * 两处 pos 是否指同一页。
     *
     * **不能用 `==`**：`ReaderPos.slice` 是排版侧的页切片对象，重排后落位是行锚页
     * （blockStart=-1）、内存表拒绝回填旧表，两次查询拿到的切片不是同一实例 ⇒
     * 对象比较**永不等**。项目在 [isBookStart] 的注释里已经踩过并明写「不比较整页
     * 切片对象」。故按章 + 首字符比，与封面首位判定同口径。
     */
    fun samePage(a: ReaderPos?, b: ReaderPos?): Boolean =
        a != null && b != null && a.chapter == b.chapter && a.slice.charStart == b.slice.charStart

    /**
     * 松手：裁决 → 结算动画。
     *
     * **commit 不再落位**：起手已落过一次（见 [beginSlide]），这里只需把动画走完并清场。
     * 早先在这里再落一次位，正是「翻到位又弹回上一页」的根因（第二次 adjacent 引擎已不在源页）。
     *
     * **rollback 要把数据翻回去**：起手那次落位是真的推进了引擎指针，不翻回去的话
     * 下次翻页从错的页起——静默错页。
     */
    fun endSlide(velocityX: Float = 0f) {
        if (!slideEnabled || flipSession.phase != FlipSession.Phase.Dragging) return
        val decision = flipSession.decide(velocityX)
        // 方向必须在 beginSettle 之前取：动画走到端点时状态机会把 direction 清零。
        val commitDir = flipSession.direction
        flipSession.beginSettle(decision)
        val from = flipSession.progress
        val duration = flipSession.settleDurationMs(decision)
        settleJob?.cancel()
        settleJob = scope.launch {
            val anim = androidx.compose.animation.core.Animatable(from)
            val target = if (decision == FlipSession.Decision.COMMIT) 1f else 0f
            anim.animateTo(
                targetValue = target,
                animationSpec = androidx.compose.animation.core.tween(duration),
            ) {
                flipSession.onSettleProgress(value)
                slideProgress = value
            }
            if (decision == FlipSession.Decision.COMMIT) {
                // 数据早已落定（起手那次），动画走完即达成：清场即可。
                clearSlide()
            } else {
                // 回弹：动画回到 0，同时把引擎指针翻回原页。
                rollbackSlide(navigateBack = true)
            }
        }
    }

    /**
     * 外部改写了 openPos（重排 / 外部落位 / seek）⇒ 作废进行中的会话。
     *
     * 不作废的后果是「拿旧页配新数据」：会话还锁着旧的两页，而 openPos 已经换成第三页，
     * 位移算式继续用旧方向画 ⇒ 画面与内容永久错位。
     *
     * 判据：起手落位后 `openPos` 就是 `slideTargetPos`，所以「openPos 既不是目标页、
     * 也不是起手那一页」才算被外部改写。两者都用 [samePage] 比——`ReaderPos.slice`
     * 是排版侧对象，两次查询不同实例，`==` 恒不等（见 [samePage] 的 KDoc）。
     */
    LaunchedEffect(openPos, hostRevision, contentRevision) {
        if (flipSession.phase == FlipSession.Phase.Idle) return@LaunchedEffect
        if (samePage(openPos, slideTargetPos) || samePage(openPos, slideFromPos)) return@LaunchedEffect
        Logger.d(
            "Orilumn.TAP",
            "flip-session ABORT external pos change (open=${openPos?.slice?.charStart} " +
                "target=${slideTargetPos?.slice?.charStart} from=${slideFromPos?.slice?.charStart})",
        )
        rollbackSlide()
    }


    fun seek(fraction: Float) {
        // 诊断常驻：手指值 vs 落位章对不上（两次远端拖动都落 ch11），W 级防 PGAP  flood 吞行。
        Logger.w("Orilumn.TAP", "seek finger fraction=$fraction")
        scope.launch {
            anchorFunnel.navigate(
                action = "seek",
                read = { resolveNavPos("seek") },
                commit = ::markPositionChanged,
            ) { currentHost.pageAtFraction(fraction.toDouble())?.also { warmPageImages(it) } }
            // 拉到头即进封面（与回翻进封面同口径）：落位首位即清已离开、封面就绪即展示；
            // 只读，markPositionChanged 已记首位存档，不另存。bmp 未到时 effect 在 openPos
            // 变化重跑后按新 dismissed 展示；已在首位且 bmp 未到是首开竞态，cover effect 自会收尾。
            if (fraction <= 0f) {
                val p = openPos
                if (p != null && coverStartChapter != null &&
                    p.chapter == coverStartChapter && p.slice.charStart == 0
                ) {
                    coverDismissed = false
                    if (coverBmp != null) coverVisible = true
                }
            }
        }
    }

    // Q1-b：栏显隐 → 通知宿主同步系统栏 chrome（初始 false 无副作用）。
    LaunchedEffect(barsVisible) { currentOnBarsVisibleChanged(barsVisible) }

    // ---- 亮度手势 ----
    fun applyBrightnessDelta(base: Int, fraction: Float) {
        light = light.copy(brightness = ReaderMath.brightnessFromDelta(base, fraction))
        brightnessUi = BrightnessGestureUi(light.brightness)
        currentOnLightChange(light)
    }

    fun endBrightnessGesture() {
        brightnessUi = null
        currentOnLightCommit(light)
    }

    /** 点按 (xPx, yPx)（屏坐标 px）→ 命中链接即导航并返回 true；miss 回 false。 */
    fun tryOpenLinkAt(xPx: Float, yPx: Float): Boolean {
        val p = openPos ?: return false
        val lines = currentHost.pageLines(p) ?: return false
        if (lines.isEmpty()) return false
        val contentLeft = profile.marginLeft.toFloat()
        val contentTop = profile.marginTop.toFloat()
        // 对齐锚点与画布同式（行/图/背景三者最小），否则行窗内首图页的 y 全偏。
        val imgMin = runCatching { currentHost.pageImages(p)?.minOfOrNull { it.yTop } }.getOrNull()
        val bgMin = runCatching { currentHost.pageBackgrounds(p)?.minOfOrNull { it.yTop } }.getOrNull()
        val anchorY = ReaderMath.pageAnchorY(lines.minOf { it.yTop }, imgMin, bgMin) ?: return false
        val shift = anchorY - contentTop.roundToInt()
        // 行窗 Y 是屏坐标（含 contentTop，见 ReaderPageCanvas 同式 shift），故 y 传屏坐标；
        // x 原点是内容区左缘，故 x 减 contentLeft（与绘制侧 paintX = contentLeft + xLeft 同式）。
        val (line, xInParagraph, yInLine) =
            ReaderMath.tapLineAt(lines, shift, xPx - contentLeft, yPx) ?: return false
        val offset = orilumn.reader.engine.skia.LineHitTest.hit(line, xInParagraph, yInLine) ?: return false
        val target = currentHost.linkTargetAt(p.chapter, line.charBase + offset) ?: return false
        scope.launch {
            anchorFunnel.navigate(
                action = "open-link",
                read = { resolveNavPos("open-link") },
                commit = ::markPositionChanged,
            ) { currentHost.openLink(target)?.also { warmPageImages(it) } }
        }
        return true
    }

    fun onTap(xPx: Float, yPx: Float, widthPx: Float) {
        // 封面页点按：无链接命中，前进区回正文、中部切栏，后退区无操作（封面已是第一页；
        // 退出走顶栏返回键）。
        if (coverVisible) {
            when (ReaderMath.tapZone(xPx, widthPx)) {
                1 -> flip(1)
                else -> barsVisible = !barsVisible
            }
            return
        }
        // P4-c2u: 链接优先——点中链接字形即导航，未中才走三区（翻页/栏显隐）。
        // 无时间防抖：链接跳转本身走锚页漏斗（在途导航中后到的点按 BUSY-DROP），锁即防抖，
        // 不再另设时间窗（误吞正常点按的"点了没反应"即此类）。
        if (tryOpenLinkAt(xPx, yPx)) {
            Logger.d("Orilumn.TAP", "tap x=${xPx.roundToInt()} y=${yPx.roundToInt()} → link")
            return
        }
        val zone = ReaderMath.tapZone(xPx, widthPx)
        Logger.d("Orilumn.TAP", "tap x=${xPx.roundToInt()} y=${yPx.roundToInt()} w=${widthPx.roundToInt()} zone=$zone bars=${currentBarsVisible}")
        when (zone) {
            -1 -> flip(-1)
            1 -> flip(1)
            else -> barsVisible = !barsVisible
        }
    }

    /** 进目录/设置面板：工具栏先退场（BarsAnim），再开面板（按钮只在栏可见时能点到）。 */
    fun hideBarsThen(action: () -> Unit) {
        barsVisible = false
        scope.launch {
            delay(BarsAnim.toLong())
            action()
        }
    }

    val touchSlop = LocalViewConfiguration.current.touchSlop

    // 桌面键鼠：阅读面常驻焦点；左右箭头翻页，Esc 等效中部点按（链接优先，否则栏显隐）。
    // 冒泡口径：输入框等消费方向键后不再到这里，不劫持打字。
    // 点栏按钮/滑条会抢走焦点（clickable 自带焦点），故随定位/栏/面板状态自动夺回；
    // 面板打开时 keysEnabled=false，不夺回也不处理（按键留给面板）。
    val focusRequester = remember { FocusRequester() }
    val currentKeysEnabled by rememberUpdatedState(keysEnabled)
    LaunchedEffect(openPos, barsVisible, keysEnabled) {
        if (keysEnabled) focusRequester.requestFocus()
    }

    // L1 滑动的引用包装（必须在上面几个局部函数声明之后——Kotlin 局部函数不可前向引用）。
    val currentSlideEnabled by rememberUpdatedState(slideEnabled)
    val currentBeginSlide by rememberUpdatedState<(Int) -> Unit> { beginSlide(it) }
    val currentUpdateSlide by rememberUpdatedState<(Float, Float) -> Unit> { dx, w -> updateSlide(dx, w) }
    val currentEndSlide by rememberUpdatedState<(Float) -> Unit> { endSlide(it) }

    // S29：Toast → CMP Snackbar；书签/笔记占位动作未接线（null）时给出提示。
    val snackbar = rememberReaderSnackbar()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(profile.bgColor))
            .pointerInput(touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downX = down.position.x
                    val downY = down.position.y
                    val downTime = down.uptimeMillis
                    // 栏区落点：手势归栏（按钮/滑条处理），翻页层只排空到抬起，
                    // 不消费、不翻页、不调亮度——点栏按钮漂移不再误翻页。
                    val barsOwned = ReaderMath.downInBars(
                        currentBarsVisible, downY, size.height.toFloat(),
                        currentTopBarH.toFloat(), currentBotBarH.toFloat(),
                    )
                    var axis = ReaderMath.Axis.NONE
                    var dragDir = 0
                    var slideActive = false
                    var brightnessActive = false
                    var brightnessBase = ReaderMath.MAX_BRIGHTNESS
                    // 抬手速度样本（两次采样的位移/时差）：滑动判定要「快甩过阈即翻」，
                    // 只看位移会把快速轻扫吃掉。
                    var lastDx = 0f
                    var lastMoveTime = downTime
                    var vx = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (barsOwned) {
                            if (!change.pressed) {
                                // 栏区点按被栏消费——此前静默，"点了没反应"先查这条。
                                // 附栏实测几何：若 downY 在栏条之外仍被吞，即 downInBars 误判/高度过期。
                                Logger.d("Orilumn.TAP", "tap x=${downX.roundToInt()} y=${downY.roundToInt()} SWALLOWED by bars (viewH=${size.height} topH=${currentTopBarH} botH=${currentBotBarH})")
                                break
                            }
                            continue
                        }
                        // 覆盖层（上下栏按钮等）已消费的事件交给它们；被消费的抬起也不再触发点按/翻页。
                        if (change.isConsumed) {
                            if (!change.pressed) break
                            continue
                        }
                        if (!change.pressed) {
                            val upTime = change.uptimeMillis
                            if (brightnessActive) endBrightnessGesture()
                            else if (slideActive) currentEndSlide(vx)
                            else if (dragDir != 0) flip(dragDir)
                            else if (upTime - downTime < ReaderMath.TAP_MAX_MS) {
                                onTap(downX, downY, size.width.toFloat())
                            }
                            break
                        }
                        val dx = change.position.x - downX
                        val dy = change.position.y - downY
                        // 抬手速度：本次 MOVE 相对上次 MOVE 的位移/时差（px/s）。
                        val now = change.uptimeMillis
                        val dt = (now - lastMoveTime).coerceAtLeast(1L)
                        vx = (dx - lastDx) / dt * 1000f
                        lastDx = dx
                        lastMoveTime = now
                        if (axis == ReaderMath.Axis.NONE) {
                            axis = ReaderMath.gestureAxis(dx, dy, touchSlop)
                        }
                        when (axis) {
                            ReaderMath.Axis.HORIZONTAL -> {
                                if (dragDir == 0) dragDir = ReaderMath.flipDirection(dx)
                                // L1 跟手：起手那一次 beginSlide（预取目标页），其后逐帧 updateSlide。
                                if (currentSlideEnabled) {
                                    if (!slideActive) {
                                        currentBeginSlide(dragDir)
                                        slideActive = true
                                    }
                                    currentUpdateSlide(dx, size.width.toFloat())
                                }
                            }
                            ReaderMath.Axis.VERTICAL -> if (!brightnessActive) {
                                val s = light
                                if (!s.brightnessFollowSystem &&
                                    ReaderMath.verticalBrightnessAllowed(
                                        ReaderMath.tapZone(downX, size.width.toFloat()),
                                        s.brightnessGestureLeft,
                                        s.brightnessGestureRight,
                                    )
                                ) {
                                    // 单指垂直亮度只在左/右 1/3 且对应开关打开；激活锁定向下报告累计竖向偏移。
                                    brightnessActive = true
                                    brightnessBase = s.brightness
                                }
                            }
                            ReaderMath.Axis.NONE -> Unit
                        }
                        change.consume()
                        if (brightnessActive) {
                            applyBrightnessDelta(brightnessBase, ReaderMath.brightnessFrac(dy, size.height.toFloat()))
                        }
                    }
                }
            },
    ) {
        val pxWidth = with(LocalDensity.current) { maxWidth.toPx() }
        val pxHeight = with(LocalDensity.current) { maxHeight.toPx() }
        val contentLeft = profile.marginLeft.toFloat()
        val contentTop = profile.marginTop.toFloat()
        val contentRight = (pxWidth - profile.marginRight).coerceAtLeast(contentLeft)
        val contentBottom = (pxHeight - profile.marginBottom).coerceAtLeast(contentTop)

        // 键盘入口（内层盒载焦点）：左右箭头翻页，Esc 等效中部点按。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusTarget()
                .onKeyEvent { event ->
                    if (!currentKeysEnabled) return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val action = ReaderMath.keyAction(event.key)
                    // TODO: 状态栏打开时左右键在栏按键之间切换焦点（未实现）。
                    // 在此之前状态栏打开即禁用左右翻页，不消费（冒泡给系统/后续焦点逻辑）。
                    if (currentBarsVisible &&
                        (action == ReaderMath.ReaderKeyAction.Prev || action == ReaderMath.ReaderKeyAction.Next)
                    ) return@onKeyEvent false
                    when (action) {
                        ReaderMath.ReaderKeyAction.Prev -> { flip(-1); true }
                        ReaderMath.ReaderKeyAction.Next -> { flip(1); true }
                        ReaderMath.ReaderKeyAction.MiddleTap -> { onTap(pxWidth / 2f, pxHeight / 2f, pxWidth); true }
                        null -> false
                    }
                },
        ) {
        val pos = openPos
        val cover = if (coverVisible) coverBmp else null
        // 换代结算中且落在首字符页：画底色占位，不抢画正文——否则正文闪一帧再被封面盖。
        val holdingForCover = cover == null && coverResolving && pos?.slice?.charStart == 0
        if (holdingForCover) {
            Box(modifier = Modifier.fillMaxSize().background(Color(profile.bgColor)))
        } else if (cover != null) {
            // 封面页：拉伸全屏（默认开）/等比居中（关），之上同样压遮罩；
            // 栏与提示与正文同制（标题取书名、进度 0），避免封面页无处进目录/设置。
            ReaderCoverPage(
                cover = cover,
                proportional = !light.coverStretch,
                bgColor = Color(profile.bgColor),
                modifier = Modifier.fillMaxSize(),
            )
            ReaderLightMask(light = light, modifier = Modifier.fillMaxSize())
            ReaderBars(
                visible = barsVisible,
                statusBarInset = statusBarInset,
                bookTitle = currentHost.title(),
                chapterTitle = "",
                fraction = 0f,
                onTapOutside = { barsVisible = false },
                onBack = currentOnBack,
                onPrev = { jumpChapter(-1) },
                onNext = { jumpChapter(1) },
                onSeek = ::seek,
                onNight = currentOnNight,
                onSettings = { hideBarsThen { currentOnSettings() } },
                onToc = { hideBarsThen { currentOnToc() } },
                onBookmark = { currentOnBookmark?.invoke() ?: snackbar.show("书签（规划中）") },
                onNote = { currentOnNote?.invoke() ?: snackbar.show("笔记（规划中）") },
                onTopBarSize = { topBarH = it.height },
                onBottomBarSize = { botBarH = it.height },
            )
            BrightnessGestureIndicator(ui = brightnessUi, modifier = Modifier.align(Alignment.BottomCenter))
            SnackbarHost(
                hostState = snackbar.hostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            )
        } else if (pos != null) {
            val fraction = remember(pos) {
                currentHost.pageProgress(pos).toFloat().coerceIn(0f, 1f)
            }
            val chapterTitle = remember(pos) { currentHost.unitTitle(pos.chapter) }
            // P1-2：页内容抽成对任意 pos 可复用的 `rememberPageContent`（见 PageContent.kt）。
            // 动画中「当前页」= slideFromPos（起手那一页），因为落位已在起手发生、
            // openPos 此刻已是目标页；非动画时 slideFromPos 为 null，即用 pos。
            val fromPos = slideFromPos ?: pos
            val pageContent = rememberPageContent(
                host = currentHost,
                pos = fromPos,
                contentRevision = contentRevision,
                hostRevision = hostRevision,
                contentWidthPx = (contentRight - contentLeft).toInt(),
                contentHeightPx = (contentBottom - contentTop).toInt(),
                bgColor = profile.bgColor,
                inkColor = profile.fgColor,
                imgCache = imgCache,
                bgCache = bgCache,
            )

            // P1 L1：目标页内容（落位未回 ⇒ null，此时只画当前页，那一帧不动画）。
            val targetContent = if (slideTargetPos != null && !samePage(slideTargetPos, fromPos)) {
                rememberPageContent(
                    host = currentHost,
                    pos = slideTargetPos,
                    contentRevision = contentRevision,
                    hostRevision = hostRevision,
                    contentWidthPx = (contentRight - contentLeft).toInt(),
                    contentHeightPx = (contentBottom - contentTop).toInt(),
                    bgColor = profile.bgColor,
                    inkColor = profile.fgColor,
                    imgCache = imgCache,
                    bgCache = bgCache,
                )
            } else {
                null
            }

            // 画布渲染器（栅格 + 多页位图缓存的宿主）在**此处** remember 一份，注入给 current/target
            // 两张画布：否则两个组合位置各自 remember，各得一池，当前页位图在 target 侧永远查不到
            // （真机日志：动画期间每页每轮各栅格一次，`hit=false`）。
            val pageRenderer = rememberReaderPageRenderer()
            // 画布本体（当前页 / 目标页同形，只是位移不同）。
            val PageCanvasFun: @Composable (PageContent?) -> Unit = { content ->
                ReaderPageCanvas(
                    lines = content?.lines,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRectLeft = contentLeft,
                    contentRectTop = contentTop,
                    contentRectRight = contentRight,
                    contentRectBottom = contentBottom,
                    pageBg = profile.bgColor,
                    inkColor = profile.fgColor,
                    modifier = Modifier.fillMaxSize(),
                    pageImages = content?.pageImages,
                    imageBitmaps = content?.imageBitmaps,
                    pageBackgrounds = content?.pageBackgrounds,
                    bgImages = content?.bgImages ?: emptyMap(),
                    // 字重这类纯字形变更行数据完全相等，靠修订号强制重画（见 ReaderPageCanvas）。
                    contentRevision = contentRevision,
                    // P0b：页身份键（多页位图缓存下标）
                    rasterKey = content?.rasterKey,
                    // 第 1 档「渲染」半边的抢占钩子：未命中真正栅格化时，让后台预排让路
                    // （总则第 1 条 + 线程调度原则 §5「唯一钩子」；翻页那半边早已接上）。
                    onMiss = onRasterMiss,
                    // 双页共用一个渲染器 ⇒ 共用一个多页位图池（见 ReaderPageCanvas.renderer）。
                    renderer = pageRenderer,
                )
            }
            // P1 L1：滑动层**始终在场**，且**只有一个调用点**——早先按 targetContent 有无
            // 分成两个 `FlipSlideLayer` 调用点，起手/落位/收尾之间来回切，等于每轮都把
            // `ReaderPageCanvas` 里 remember 的渲染器（多页位图缓存宿主）连池丢弃重建，
            // 一轮翻页重栅格 3～5 次整页（真机 87ms/次）——就是「滑一点卡一下」的来源。
            // 现在目标槽恒在（target 为 null 时画空，见 FlipSlideLayer KDoc），结构稳定。
            FlipSlideLayer(
                direction = slideDirection,
                progress = slideProgress,
                pageWidthPx = pxWidth,
                modifier = Modifier.fillMaxSize(),
                target = if (targetContent != null) {
                    { PageCanvasFun(targetContent) }
                } else {
                    null
                },
                current = { PageCanvasFun(pageContent) },
            )
            // 亮度/护眼遮罩：纯绘制于画布之上、栏之下。
            ReaderLightMask(light = light, modifier = Modifier.fillMaxSize())
            // 顶/底栏：中部点按切换；条外点击收起。
            // 进面板先退栏：工具栏滑出（BarsAnim）后再开面板，避免面板盖在晾着的工具栏上。
            ReaderBars(
                visible = barsVisible,
                statusBarInset = statusBarInset,
                bookTitle = currentHost.title(),
                chapterTitle = chapterTitle,
                fraction = fraction,
                onTapOutside = { barsVisible = false },
                onBack = currentOnBack,
                onPrev = { jumpChapter(-1) },
                onNext = { jumpChapter(1) },
                onSeek = ::seek,
                onNight = currentOnNight,
                onSettings = { hideBarsThen { currentOnSettings() } },
                onToc = { hideBarsThen { currentOnToc() } },
                onBookmark = { currentOnBookmark?.invoke() ?: snackbar.show("书签（规划中）") },
                onNote = { currentOnNote?.invoke() ?: snackbar.show("笔记（规划中）") },
                onTopBarSize = { topBarH = it.height },
                onBottomBarSize = { botBarH = it.height },
            )
            // 亮度手势中的底部滑块。
            BrightnessGestureIndicator(ui = brightnessUi, modifier = Modifier.align(Alignment.BottomCenter))
            // S29：CMP Snackbar（替代 Android Toast）承载占位提示。
            SnackbarHost(
                hostState = snackbar.hostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            )
        } else {
            // 打开中/失败：只显示背页（打开由宿主 suspend 解析，通常瞬时）。
            if (openFailed) {
                Text(
                    text = "无法打开书籍",
                    color = Color(profile.fgColor),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        } // 键盘入口内层盒
    }
}