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
import androidx.compose.runtime.SideEffect
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
    /**
     * 预排出版式后回调（见 `ReaderHost.onPrefillReady`）：此刻**按翻页方向补栅格一张**。
     *
     * 翻页只需准备一页——方向侧那一张；另一侧等它成为方向侧时再补（§3.2 方向记录）。
     * 0 页（第 1 档）不在此列：它同步阻塞、像素早已随绘制产出。
     */
    onPrefillReady: (chapter: Int, direction: Int) -> Unit = { c, d -> host.onPrefillReady(c, d) },
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
    // 滑动会话的全部状态与守卫都收在 FlipController 里（见其 KDoc：判据散落多处
    // 导致「三处都判了、漏了一处」的 bug 已连踩三次）。这里只留两个派生值：
    // 会话态的两个**唯一出口**供渲染层与手势层使用。
    //
    // controller 需要 flipAwait / markPositionChanged（声明在下方），Kotlin 局部函数
    // 不可前向引用，故用可空占位 + 下方回填；回填发生在首次组合内、任何手势之前。
    var flipCtl by remember { mutableStateOf<FlipController?>(null) }
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
    // ---- 定位动作（host 为 suspend，统一挂到本组件作用域） ----
    /**
     * 落位前等图（导航提交前预热）：目标页缺的插图/背景图并行解出来进跨页缓存再提交定位，
     * 首帧即整页。以后翻页动画截图直接复用这批已解码位图。
     *
     * 调用点在拿到 [ReaderScreen] 返回的落定 pos 之后、`slideTargetPos` 赋值之前
     * （见 `beginSlide` / `flipWithAnimation`）：目标页在图齐之前**不进渲染层**，
     * 所以首帧就是整页真图，不存在「灰占位块 → 整页重栅格 → 真图」两跳。
     *
     * 两个调用来源，语义不同、互不替代：
     *  - [AnchorFunnel.beforeCommit]（**覆盖所有落位路径**）：commit 之前等，于是
     *    `openPos` 一变页面首帧即整页。这是「页面全部处理好再显示」的落点。
     *  - `beginSlide` / `flipWithAnimation`：排在 `slideTargetPos` 赋值之前。翻页
     *    动画另有一道闸（目标页未进渲染层 ⇒ 不许平移），等的是**渲染层入场**时机。
     *
     * 超时兜底（大图页最多慢这一拍）：超时按缺图放行，目标页会带占位块进场，
     * 由 `rememberPageContent` 的 `LaunchedEffect` 异步补齐（那条路径此时是**唯一**
     * 能救的路径，重复解码是有意的冗余，不是 bug）。
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

    // 「显示前把像素备齐」钩子挂进漏斗（见 AnchorFunnel.beforeCommit）：commit 之前
    // **真等**（suspend，不是 launch 出去）——钩子返回即 commit，若不等，页面照旧
    // 带着灰占位块显示，这套机制就白搭了。经引用读，避免捕获首次组合时的旧
    // imgCache/bgCache（它们随宿主换代重建）。写入走 SideEffect：组合体里直接
    // 赋值是副作用，每次重组都写。
    val warmByRef = rememberUpdatedState<suspend (ReaderPos) -> Unit> { pos -> warmPageImages(pos) }
    // 「提交前把**整页位图**也备好」的实现，在下方（拿到渲染器/几何的那一段）按当前组合重建。
    // 声明在此、实现留在后：局部函数不能前向引用，而渲染器 `remember` 在 `pos != null` 分支里。
    // 读经 State，避免捕获首次组合的旧值；`pos == null`（封面）时保持空实现。
    val rasterByRef = remember { mutableStateOf<suspend (ReaderPos) -> Unit>({ }) }
    SideEffect {
        anchorFunnel.beforeCommit = { pos ->
            // 先等图（缺图必等，灰占位不可入场），再等整页位图（翻页动画/首帧直接复用这张）。
            warmByRef.value(pos)
            rasterByRef.value(pos)
        }
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
            // 等图由 [AnchorFunnel.beforeCommit] 在**本函数内部、commit 之前**完成（漏斗锁内，
            // 所有落位路径共用），故返回时目标页整页就绪：`openPos` 一变，首帧即真图。
            // 调用点**不要**再等一次——那是冗余，且会和用户紧接着的下一手势抢同一个
            // 漏斗锁，连锁 BUSY-DROP（真机日志：tap-flip done 后 3ms 就 slide-rollback，
            // 末次 landed=NULL 导致引擎指针没翻回去而会话已清 ⇒ 白屏）。
            landed
        }
    }

    /**
     * 瞬切翻页（无动画路径：封面页 / 关掉动画 / 模式非 slide）。
     *
     * 等图由 [AnchorFunnel.beforeCommit] 在 commit 之前统一完成（所有落位路径共用），
     * 故 `openPos` 变化那一刻页面首帧即整页真图，不会「页面先出现、图再刷新」。
     */
    fun flip(direction: Int) {
        scope.launch { flipAwait(direction) }
    }

    // FlipController 的实例化点：必须晚于 flipAwait / markPositionChanged 的声明
    // （Kotlin 局部函数不可前向引用），早于任何手势（组合体内同步完成）。
    val controller = rememberFlipController(
        flipAwait = { d -> flipAwait(d) },
        forceOpenPos = { p -> markPositionChanged(p) },
        scope = scope,
        log = { m -> Logger.d("Orilumn.TAP", m) },
    )
    SideEffect { flipCtl = controller }
    // 手势/渲染层一律经它取会话；理论上组合内已完成赋值（SideEffect 在 apply 变更前跑）。
    val ctl = controller

    fun jumpChapter(direction: Int) {
        scope.launch {
            anchorFunnel.navigate(
                action = "jump-chapter",
                read = { resolveNavPos("jump-chapter") },
                commit = ::markPositionChanged,
            ) { p -> currentHost.neighborChapterStart(p.chapter, direction) }
        }
    }

    /**
     * 两处 pos 是否指同一页。
     *
     * **不能用 `==`**：`ReaderPos.slice` 是排版侧的页切片对象，重排后落位是行锚页
     * （blockStart=-1）、内存表拒绝回填旧表，两次查询拿到的切片不是同一实例 ⇒
     * 对象比较**永不等**。项目在 `isBookStart` 的注释里已经踩过并明写「不比较整页
     * 切片对象」。故按章 + 首字符比，与封面首位判定同口径。
     */
    fun samePage(a: ReaderPos?, b: ReaderPos?): Boolean =
        a != null && b != null && a.chapter == b.chapter && a.slice.charStart == b.slice.charStart

    /** 清场（只清动画状态，不动数据）。数据要不要退回由调用方决定。 */
    fun clearSlide() = ctl.clear()

    /** 动画作废并把数据退回原页（起手已落位过一次，不翻回去就是静默错页）。 */
    fun rollbackSlide(navigateBack: Boolean = true) = ctl.rollback(navigateBack)

    /**
     * 拖动起手：就地落位一次，把落定结果当作本次动画的目标页。
     *
     * 必须传起手时的 `openPos`：它是「当前页」身份，落位完成后 openPos 就变成
     * 目标页了，不另存一份渲染层会拿目标页当当前页画（真机症状：目标页空白）。
     */
    fun beginSlide(direction: Int) {
        val src = openPos ?: return
        ctl.beginDrag(src, direction, slideEnabled, coverVisible)
    }

    /** 拖动中：喂位移。目标页未就绪时只喂状态机、不写回渲染（避免拉出空白页）。 */
    fun updateSlide(dx: Float, pageW: Float) = ctl.updateDrag(dx, pageW, slideEnabled)

    /** 松手：裁决 → 结算。 */
    fun endSlide(velocityX: Float = 0f, pageW: Float = 0f) =
        ctl.endDrag(velocityX, pageW, slideEnabled)

    /** 结算动画（点按翻页复用）。 */
    fun settleSlide(decision: FlipSession.Decision, velocityX: Float = 0f, pageW: Float = 0f) =
        ctl.settle(decision, velocityX, pageW)

    /**
     * 点按/方向键翻页：有动画走程序化滑动，否则退回瞬切 [flip]。
     *
     * 动画在飞 ⇒ **丢弃**这次翻页（与 [AnchorFunnel] 的 BUSY-DROP 同口径），
     * 不能退回 flip()：那会硬切数据，而屏幕上仍锁着旧的两页 ⇒ 像素与数据立刻错位。
     */
    fun flipWithAnimation(direction: Int) {
        if (!slideEnabled || coverVisible) {
            flip(direction)
            return
        }
        if (!ctl.beginProgrammatic(direction)) {
            Logger.d("Orilumn.TAP", "tap-flip dropped dir=$direction (session busy)")
            return
        }
        val src = openPos
        if (src == null) {
            // 无源可落：数据没动，清场即可（不能 rollback——那次落位根本没发生）。
            ctl.clear()
            flip(direction)
            return
        }
        ctl.beginProgrammaticPage(src, direction)
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
        if (!ctl.isBusy()) return@LaunchedEffect
        val tgt = ctl.targetPos.value
        val from = ctl.fromPos.value
        if (samePage(openPos, tgt) || samePage(openPos, from)) return@LaunchedEffect
        Logger.d(
            "Orilumn.TAP",
            "flip-session ABORT external pos change (open=${openPos?.slice?.charStart} " +
                "target=${tgt?.slice?.charStart} from=${from?.slice?.charStart})",
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
            ) { currentHost.pageAtFraction(fraction.toDouble()) }
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
            ) { currentHost.openLink(target) }
        }
        return true
    }

    fun onTap(xPx: Float, yPx: Float, widthPx: Float) {
        // 动画播放期间不响应点按：会话在飞时画面正被动画独占（`slideFromPos`/
        // `slideTargetPos` 锁着两页），此时翻页/切栏/跳链接会让像素与数据错位。
        // 早先只有 `flipWithAnimation` 自己挡（beginProgrammatic 返回 false 即丢弃），
        // 但**中部点按切栏、链接跳转、封面回正文**三条路没有守卫——动画中点一下
        // 会把上下栏拉出来盖在正在翻的页面上，动画结束后面板还挂着，像卡死。
        // 故在唯一入口统一拦：三区点按与拖动松手走同一会话态。
        if (ctl.isBusy()) {
            Logger.d("Orilumn.TAP", "tap IGNORED (flip session busy)")
            return
        }
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
            // 点按翻页与拖动松手走同一条动画路径（见 flipWithAnimation）。
            -1 -> flipWithAnimation(-1)
            1 -> flipWithAnimation(1)
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
    val currentEndSlide by rememberUpdatedState<(Float, Float) -> Unit> { vx, w -> endSlide(vx, w) }
    // 点按/方向键翻页同样走动画，必须经引用读：否则设置里刚打开「翻页动画」，
    // 键盘那侧还按旧的瞬切走（与 pointerInput 的坑同源，见上方注释）。
    val currentFlipWithAnimation by rememberUpdatedState<(Int) -> Unit> { flipWithAnimation(it) }

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
                    // ---- 「手指按下 → 页面动起来」时延测量（见 FlipController 的 FLIPLAT KDoc）----
                    // 三个时刻取自 `System.nanoTime()`（单调钟，与事件 uptimeMillis 不同源，
                    // 但三者同源即可相减；uptimeMillis 只用于手势内的相对判定）。
                    // 关键锚点是 tMove：**第一帧 progress 真正写回渲染**的时刻，
                    // 它之后才谈得上「页面动起来」。
                    val tDown = System.nanoTime()
                    var tAxis = 0L
                    var tMove = 0L
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
                            val upX = change.position.x
                            val upDx = upX - downX
                            val upDy = change.position.y - downY
                            if (brightnessActive) endBrightnessGesture()
                            else if (slideActive) currentEndSlide(vx, size.width.toFloat())
                            // 走 flipWithAnimation（同点按）而非 flip()：flip() 是**瞬切**——直接
                            // flipAwait 硬换数据、不播动画、也不经过 FlipController，于是会话
                            // 状态与渲染层不同步。真机症状：快速翻页时同一内容"快速闪一下"
                            // （FLIPLAT 日志里 axis 定轴后紧跟一条 tap-flip，源页与滑动落定的
                            // 目标页一致 ⇒ 同一次手势既滑动又瞬切了一次）。
                            else if (dragDir != 0) flipWithAnimation(dragDir)
                            else if (upTime - downTime < ReaderMath.TAP_MAX_MS &&
                                !ReaderMath.movedBeyondTapSlop(upDx, upDy)
                            ) {
                                // 点按的判据是「没动过」，不是「没定向过」：早先只看
                                // dragDir==0 与时长，而 dragDir 只在 gestureAxis 判成
                                // HORIZONTAL 之后才赋值——于是**少量滑动**（没过 slop，
                                // 或位移没超过纵向 1.2 倍被判成 VERTICAL）会带着
                                // dragDir==0 落进这里，被当成点击：正文被点掉、
                                // 栏弹出、翻页没发生。抬手时只要真的挪过就不算点按。
                                onTap(downX, downY, size.width.toFloat())
                            }
                            // 抬手：把本次手势的起手时延落盘（W 级，常驻量级）。
                            // 口径见下：`latency = tMove - tDown`，即「手指按下 → 第一帧
                            // 页面位移真正写回渲染」。`tMove==0` 说明整场没采纳过位移
                            // （目标页始终未就绪），此时另打 `nomove` 分支便于区分。
                            if (slideActive || dragDir != 0) {
                                val lat = if (tMove != 0L) (tMove - tDown) / 1_000_000 else -1
                                val latAxis = if (tAxis != 0L && tMove != 0L) (tMove - tAxis) / 1_000_000 else -1
                                Logger.w(
                                    "Orilumn.FLIPLAT",
                                    "drag dir=$dragDir lat=${lat}ms axis2move=${latAxis}ms " +
                                        "dx=${upDx.roundToInt()} vx=${vx.roundToInt()}",
                                )
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
                                if (dragDir == 0) {
                                    dragDir = ReaderMath.flipDirection(dx)
                                    tAxis = System.nanoTime()
                                    // 带上 slop 与当时的 |dy|：区分「位移还没到slop」
                                    // 与「到了但没过 1.2 倍」两种推迟原因。
                                    Logger.w(
                                        "Orilumn.FLIPLAT",
                                        "axis dir=$dragDir dx=${dx.roundToInt()} dy=${dy.roundToInt()} " +
                                            "slop=${touchSlop.roundToInt()} lat=${(tAxis - tDown) / 1_000_000}ms",
                                    )
                                }
                                // L1 跟手：起手那一次 beginSlide（预取目标页），其后逐帧 updateSlide。
                                if (currentSlideEnabled) {
                                    if (!slideActive) {
                                        currentBeginSlide(dragDir)
                                        slideActive = true
                                    }
                                    currentUpdateSlide(dx, size.width.toFloat())
                                    if (tMove == 0L) tMove = System.nanoTime()
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
                        ReaderMath.ReaderKeyAction.Prev -> { currentFlipWithAnimation(-1); true }
                        ReaderMath.ReaderKeyAction.Next -> { currentFlipWithAnimation(1); true }
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
            val fromPos = ctl.fromPos.value ?: pos
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
            val targetContent = if (ctl.targetReady() && !samePage(ctl.targetPos.value, fromPos)) {
                rememberPageContent(
                    host = currentHost,
                    pos = ctl.targetPos.value,
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


            // 预栅格（`线程调度原则.md` §3 阶梯补的那一档）：引擎在预排出邻页版式后抛信号，
            // 此刻在 UI 线程按**方向**补一张像素，落定时目标页即可命中缓存、零栅格开销。
            // 翻页只需一页——方向侧那张；另一侧等它成为方向侧再补。
            //
            // 为什么此刻做：单页栅格 p50 143ms，落在「用户还在读当前页」时最划算；
            // 落在手指按下后就是可感知的迟钝（实测 target-ready p50 59ms 全等它）。
            PrerasterOnPrefill(
                host = currentHost,
                onPrefillReady = onPrefillReady,
                directionProvider = { ctl.session.direction },
                currentPos = { openPos },
                renderer = pageRenderer,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRight = contentRight,
                contentBottom = contentBottom,
                pageBg = profile.bgColor,
                inkColor = profile.fgColor,
                contentRevision = contentRevision,
                hostRevision = hostRevision,
                imgCache = imgCache,
                bgCache = bgCache,
            )
            // 上面 `beforeCommit` 的「整页位图备齐」实现：与 PrerasterOnPrefill 同口径
            //（同一渲染器、同一缓存、同一条取数路径），只是针对**本次落位目标页**。
            // `prerasterPage` 已把重画切到 Default 线程，所以这里 await 不冻 UI；等它的结果是
            // 提交即命中——翻页动画不再在 UI 线程同步栅格（0.5–0.9s 冻帧的来源）。
            val rasterPageNow: suspend (ReaderPos) -> Unit = { p ->
                prerasterPage(
                    host = currentHost,
                    pos = p,
                    renderer = pageRenderer,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRight = contentRight,
                    contentBottom = contentBottom,
                    pageBg = profile.bgColor,
                    inkColor = profile.fgColor,
                    contentRevision = contentRevision,
                    imgCache = imgCache,
                    bgCache = bgCache,
                )
            }
            SideEffect { rasterByRef.value = rasterPageNow }
            // 画布本体（当前页 / 目标页同形，只是位移不同）。
            val PageCanvasFun: @Composable (PageContent?) -> Unit = { content ->
                ReaderPageCanvas(
                    content = content,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRectLeft = contentLeft,
                    contentRectTop = contentTop,
                    contentRectRight = contentRight,
                    contentRectBottom = contentBottom,
                    pageBg = profile.bgColor,
                    inkColor = profile.fgColor,
                    modifier = Modifier.fillMaxSize(),
                    // 字重这类纯字形变更行数据完全相等，靠修订号强制重画（见 ReaderPageCanvas）。
                    contentRevision = contentRevision,
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
                direction = ctl.direction.value,
                // **不要在这里读 ctl.progress.value**：一旦在组合里读，动画每帧都会让整个
                // 阅读面重组、两个画布每帧重走 drawLines（见 FlipSlideLayer KDoc）。
                // 传闭包，让 progress 只在 graphicsLayer 的 draw 相位被读。
                progress = { if (ctl.slideActive(slideEnabled)) ctl.progress.value else 0f },
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