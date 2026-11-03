package orilumn.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.io.Logger
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 落位前等图超时（ms）：目标页缺图时最多等这一拍再提交定位，超时按缺图提交（老的异步补齐照常）。
 * 以后翻页动画截图直接复用预热好的位图，故预热是必经之路，不是可选优化。
 *
 * 预栅格那条等图路径（[prerasterPage]）也用这个上限：它同样挂在漏斗锁内，
 * 一旦某张图永远解不出来，整条落位链就永远不返回。
 */
internal const val PAGE_IMAGE_WARM_TIMEOUT_MS = 800L

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
 * （[ReaderSettingsPanel]/[ReaderSidePanel]，S29）；书签仍是占位（onBookmark 为 null），
 * 笔记按钮已接标注清单（onNote）
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
    /**
     * 键盘高度（Dp）：宿主能逐帧报告就传值，传 null 表示「这里读不到，自己去问系统 insets」。
     *
     * Android 阅读页是 `adjustNothing`，系统**不派发静态 IME insets**（`WindowInsets.ime` 恒为 0），
     * 只有 inset 动画回调拿得到键盘的真实高度，所以值由宿主逐帧喂进来（D-30）。
     * 桌面没有软键盘、API 30 以下没有逐帧回调，都传 null → 退回 `WindowInsets.ime`（与旧行为一致）。
     */
    imeInset: Dp? = null,
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
    /**
     * 标注数据外部变更号（标注清单删除后 ++）：单独一条 effect 重读当前章标注，
     * 让屏幕上那条装饰同步消失。**不能**并进「进章」effect——同章 `load(ch)` 会早退，
     * 不会重读；它也不是版式修订号，不驱动重新分页。
     */
    annotationsEpoch: Int = 0,
    /**
     * 统一字体列表（系统 + 导入）：只用来把「批注字体」的字族名解析成可绘制的字形。
     *
     * 批注是盖在页面上的覆盖层，与正文分页无关；缺省空表 = 批注用界面默认字体。
     */
    fontEntries: List<FontEntry> = emptyList(),
) {
    val density = LocalDensity.current.density
    // 卡片收起时主动让输入框失焦（D-30）：不主动收，键盘会等卡片卸载才开始降，
    // 于是「卡片先滑走、键盘再落」——正是要消掉的两段式。失焦后键盘与卡片同一段时间里各自归位。
    val focusManager = LocalFocusManager.current
    val profile = remember(settings, density) { TypographicProfile.build(settings, density) }
    // 批注字体（渲染层解析）：字族名 + 字重 → Compose 字体。空串 / 字体已删 = 界面默认。
    // 只影响旁注覆盖层，不进版式（`noteFont` 在 withoutNonLayout 里，面板走无重排提交）。
    // 字重**不烤进字体族**：桌面端单面族没有第二档，Compose 仍按族名去系统字库取面、烤好的字重被丢，
    // 所以族和字重分开给（族 → `fontFamily`，字重 → `fontWeight`），量宽与绘制两处都要带上。
    val noteFontFamily = remember(settings.noteFont, settings.noteFontWeight, fontEntries) {
        noteFontFamilyOf(settings, fontEntries)
    }
    val noteFontWeight = remember(settings.noteFontWeight) { noteFontWeightOf(settings) }
    val selectionHandleRadiusPx = SELECTION_HANDLE_RADIUS_DP * density
    val selectionHandleHitPx = SELECTION_HANDLE_HIT_RADIUS_DP * density

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
    // D-19：当前章标注内存态。翻页不重读库，换章才清空重载；增删改先改它再落库。
    val annotationState = remember(currentHost) { AnnotationState(currentHost) }
    // D-11：长期存在的选区（用户层，不落库）。换章清空；翻页保留，覆盖层各页只画交集。
    val selection = remember(currentHost) { TextSelectionState() }
    // 选区浮动菜单是否显示：长按/拖手柄期间收起，抬手后才出现；点正文或翻页即消失。
    var selectionMenuVisible by remember { mutableStateOf(false) }
    // 移动端放大镜的手指位置（null = 不显示）。桌面鼠标不显示（D-10）。
    var magnifierFinger by remember { mutableStateOf<Offset?>(null) }
    // 长按已有标注 / 单击旁注时打开的那一条（D-14 / D-28）；卡片不再需要锚点。
    var editingAnnotation by remember { mutableStateOf<Annotation?>(null) }
    // 批注输入条的内容（D-27）：它与浮动菜单**同开同关**，所以不需要自己的「打开」状态。
    // 文字提到这里（而不是留在输入框里），是因为点色槽落笔时要把它一起写进新记录。
    var noteText by remember { mutableStateOf("") }
    // 新建时用哪一类落笔：跟着操作栏顶部的 tab 走（读者切到「划线」，写下的批注就是划线的）。
    var noteKind by remember { mutableStateOf(AnnotationKind.Highlight) }
    // 新建 / 改型时用哪一线型：线型按钮**只选中**（D-28），落笔由点色槽或 `确定` 触发。
    var noteLineStyle by remember { mutableStateOf(AnnotationLineStyle.Straight) }
    // 卡片实测高度（像素）：页面让位要按它算，不能估（D-28 第 2 部分）。
    var noteBarHeightPx by remember { mutableStateOf(0) }
    // 卡片让位时整页上推的像素（屏幕坐标）：绘制与命中**共用这一个数**（单一来源）。
    var inputShiftPx by remember { mutableStateOf(0f) }
    // 读者自己上下滑过的页面偏移（null = 还没滑过，按「会遮住就推满」自动决定）。
    var userShiftPx by remember { mutableStateOf<Float?>(null) }
    // 卡片上沿（屏幕 px，含键盘让位）：手势侧用它判断「这一下是不是点在卡片里」。
    var sheetTopPxState by remember { mutableStateOf(0f) }
    // 视口高度（卡片上沿之上那一条）：手动滑动与手柄拖到边界时用它夹范围。
    var maxShiftPxState by remember { mutableStateOf(0f) }
    // 卡片开着时选中的颜色（null = 还没选，按默认色）：点色槽只记住，落笔还要等完成/点卡片外。
    var noteColorArgb by remember { mutableStateOf<Int?>(null) }
    // 旧的 ClipboardManager 仍是 CMP 唯一共享可用的同步写入口；新 LocalClipboard 只支持 suspend。
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val selectionDrawer = remember { LineWindowDrawer() }
    LaunchedEffect(currentHost, openPos?.chapter) {
        val ch = openPos?.chapter
        if (ch == null) {
            annotationState.clear()
        } else {
            // 相邻章的标注**与本章并行预读**：读者下一步几乎只会翻到这两章，趁现在（还在读本章）
            // 把它们的标注备好，翻过去那一刻目标页的高亮/划线/旁注首帧就有，不必再等一次库往返。
            launch { annotationState.prefetchNeighbors(ch) }
            annotationState.load(ch)
        }
        selection.clear()
        selectionMenuVisible = false
        magnifierFinger = null
        editingAnnotation = null
        noteText = ""
    }
    // 标注清单里删了当前章的标注：重读本章，屏幕上的装饰同步消失（与进章 effect 分离，见参数注释）。
    LaunchedEffect(currentHost, annotationsEpoch) {
        if (annotationsEpoch > 0) {
            annotationState.reload()
            // 清单删的可能是**别的章**的标注：reload 已把预读快照整批作废，这里顺手重备邻居。
            launch { annotationState.prefetchNeighbors(annotationState.chapter) }
        }
    }
    // 版式/参数变化后旧选区坐标不再成立（字符偏移没变，但端点所在行可能变），直接丢弃。
    LaunchedEffect(contentRevision, density) {
        selection.clear()
        selectionMenuVisible = false
        magnifierFinger = null
        editingAnnotation = null
        noteText = ""
    }
    // 选区被任何路径清空时，菜单必须同步收起（避免留下指向空选区的浮层）。
    LaunchedEffect(selection.hasSelection) {
        if (!selection.hasSelection) {
            selectionMenuVisible = false
            magnifierFinger = null
        }
    }
    // 卡片换了一条（新建 ↔ 编辑、或换编辑对象）⇒ 读者自己滑过的位置作废，重新按「遮不遮住」决定。
    LaunchedEffect(editingAnnotation?.id, selectionMenuVisible) {
        userShiftPx = null
    }
    // 翻页后浮动菜单不跟到新页（选区保留，各页只画交集；用户可再点选区/手柄）。
    // 但跨页自动翻页可能在抬手瞬间仍在飞行：抬手已把菜单置 true，此时不能再无条件抹掉，
    // 否则「拖到页边松手」永远看不到菜单。无选区时照旧收起。
    LaunchedEffect(openPos) {
        if (!selection.hasSelection) selectionMenuVisible = false
        // 翻页后编辑菜单不跟到新页；它锚定的是旧页上的那条记录。
        val wasEditing = editingAnnotation != null
        editingAnnotation = null
        // 批注输入条同理：它写的是旧页上的那条标注，翻页后不该继续挂着。
        noteText = ""
        if (wasEditing) {
            // 编辑时恢复出来的选区只服务于那张卡；卡收了就一起收掉，免得留下无主手柄。
            selectionMenuVisible = false
            if (selection.hasSelection) selection.clear()
        }
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
            // 整批 putAll（不是逐条 put）：同一页的多图必须一起留下，否则后一条会把前一条
            // （同页仍在用）淘汰掉 ⇒ 同页只画得出一张图（见 PageImageCache.putAll）。
            imgCache.putAll(
                imgJobs.awaitAll().mapNotNull { (img, bmp) -> bmp?.let { imgKeyOf(img) to it } },
            )
            bgCache.putAll(
                bgJobs.awaitAll().mapNotNull { (ref, bmp) ->
                    bmp?.let { bgKeyOf(ref.first, ref.second) to it }
                },
            )
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
            return anchorFunnel.push("open", { p ->
                openPos = p
                // 续读位不是封面 ⇒ 本代视为「已离开封面」：存档里停的是正文页（含正文首页），
                // 重开不该再弹一次封面。
                if (!p.atCover) coverDismissed = true
            }) {
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
        // 「已离开封面」标记同样不随排版换代重置：落位现在恒是该章自己的一张页
        //（短章 = 磁盘表里离锚点最近的那页，大章 = 锚点页），停在书首时落位仍是
        // charStart 0 的那页，`isBookStart` 不会因换代失真，无需再清标记兜底；
        // 反之一清，离开过封面的读者一旦回到正文首页就会重弹封面（真机：调完行距
        // 回翻一页直接撞封面）。封面本身仍可看：正文首页再往后翻即进封面（见 flipAwait）。
        if (currentHost !== coverHost) {
            coverHost = currentHost
            coverRev = contentRevision
            coverBmp = null
            coverStartChapter = null
            coverVisible = false
            coverDismissed = false
        } else if (contentRevision != coverRev) {
            coverRev = contentRevision
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

    // 封面显隐也是定位的一部分：进入/离开封面立即落一次存档。
    // 退出到书架常常紧跟「回翻进封面」这一拍，500ms 防抖会把它吞掉。
    LaunchedEffect(coverVisible) {
        val p = openPos ?: return@LaunchedEffect
        saveJob?.cancel()
        saveJob = null
        currentHost.onSaveProgress(p.copy(atCover = coverVisible))
    }

    // 落位统一入口：更新当前定位并防抖保存（复刻 Android scheduleSave 500ms）。
    // 只记 dismissed，不碰 coverVisible（显隐全权归下面 effect 按"首位 && 未离开"赋值；
    // 落位处先关再弹就是那一帧正文闪）。
    fun markPositionChanged(next: ReaderPos) {
        openPos = next
        // 封面解析未完成时 isBookStart 恒 false（缺 bmp/首章号）——那是"未知"而非
        // "非书首"：调参重排的落位推送先于封面解析到达时（启动继续阅读、
        // 开书后立即调参），会误记 dismissed，封面解析完成后 coverVisible 恒 false、
        // 封面永不再弹（封面跳正文首页根因）。仅在封面已解析且确实离开首位时记离开；
        // 未知则不动，由封面 effect 按落定后的解析结果结算显隐。
        if (coverBmp != null && coverStartChapter != null && !isBookStart(next)) {
            coverDismissed = true
        }
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val p = openPos ?: return@launch
            currentHost.onSaveProgress(p.copy(atCover = coverVisible))
            saveJob = null
        }
    }

    /** 防抖存档：位置 + 「是否停在封面」一并落盘（封面态取存档时刻的显隐）。 */
    fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val p = openPos ?: return@launch
            currentHost.onSaveProgress(p.copy(atCover = coverVisible))
            saveJob = null
        }
    }

    // —— 外部落位在途闸：调一次排版参数只重栅格一次（用户真机：短章第二页调行距刷两次）——
    // activity 推送重排落位时**先** bump 版式号、再写 externalPos，而落位要过锚页漏斗
    //（等图 + 备整页位图）才 commit。闸不关的话，这一窗里渲染层会按「新版本号 × 旧落位」
    // 重栅格一次（用户看到的「当前页自己重排了一下，页界还是旧的」），落位 commit 后
    // 再按新页界重栅格一次 ⇒ 调一次参数刷新两次。章首页两次的差别只是页尾一两行，
    // 肉眼看着还是一次，所以旧行为「章首只刷一次、第二页刷两次」。
    // 闸规则：`externalPos` 与上次**已结算**的推送不等 ⇒ 在途 ⇒ 显示侧继续用推送前那一个
    // 版本号（旧页位图原样命中、画面不动）；漏斗 push 一结束（commit 或 BUSY-DROP 丢弃）
    // 立即开闸，位置与新版本号同帧下发，只发生一次重栅格。
    var settledExternalPos by remember { mutableStateOf<ReaderPos?>(null) }
    var heldRevision by remember { mutableIntStateOf(contentRevision) }
    val externalInFlight = externalPos != null && externalPos != settledExternalPos
    // 不在途时随手跟上最新（同值不触发重组，且不在途分支不读它 ⇒ 无额外重组）；在途时不动。
    SideEffect { if (!externalInFlight) heldRevision = contentRevision }
    val displayRevision = if (externalInFlight) heldRevision else contentRevision

    // Q1-b：外部推送落位（键 = externalPos，值变化即认领；重排绑定/TOC 落地后 activity 推送）。
    // 经锚页漏斗：与在途导航互斥，值在锁内重取（取落定后的最新推送）。
    LaunchedEffect(externalPos) {
        try {
            anchorFunnel.push("external", ::markPositionChanged) {
                openFailed = false
                externalPos
            }
        } finally {
            // 结算（含 BUSY-DROP 丢弃）即开闸：位置与版本号同帧下发，显示只重栅格一次。
            settledExternalPos = externalPos
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
    suspend fun flipAwait(
        direction: Int,
        dismissCoverOnLand: Boolean = true,
        action: String = "tap-flip",
    ): ReaderPos? {
        // 封面页内翻页：前进回正文第一页（现查 fresh 落位，走漏斗，可存档)。
        // 封面已是第一页，后退无操作。
        // `dismissCoverOnLand=false`（动画路径）时**落位不 dismiss**：封面要作为滑动
        // 的 from 槽画到动画结束，dismiss 交由 [FlipController.onCoverCommit] 在 commit 达成时做。
        if (coverVisible) {
            if (direction > 0 && coverBmp != null) {
                val target = runCatching { currentHost.bookStart() }.getOrNull() ?: return null
                val landed = anchorFunnel.navigate(
                    action = "cover-forward",
                    read = { target },
                    commit = ::markPositionChanged,
                ) { target }
                if (landed != null && dismissCoverOnLand) {
                    coverDismissed = true
                    coverVisible = false
                }
                return landed
            }
            return null
        }
        // 正文第一页回翻且有封面 → 进封面（只读，不经过引擎翻页/存档）。
        // 瞬切路径就地显隐；动画路径只返回「目标=封面」的信号，显隐交给 commit 结算。
        if (direction < 0 && isBookStart(openPos)) {
            if (dismissCoverOnLand) coverVisible = true
            return null
        }
        return anchorFunnel.navigate(
            action = action,
            read = { resolveNavPos(action) },
            commit = ::markPositionChanged,
        ) { p ->
            Logger.d("Orilumn.TAP", "$action dispatch dir=$direction from ch=${p.chapter} char=${p.slice.charStart}")
            val landed = currentHost.adjacent(p, direction)
            // 引擎侧无声吞翻页时（返回 null），这里是唯一目击者——与 Orilumn.FLIP 对账。
            Logger.d("Orilumn.TAP", "$action result dir=$direction " + (landed?.let { "landed ch=${it.chapter} char=${it.slice.charStart}" } ?: "NULL (engine returned null)"))
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
    // 动画路径落位不就地改封面显隐（封面要画满整段滑动），结算 commit 达成后统一结算。
    val controller = rememberFlipController(
        flipAwait = { d -> flipAwait(d, dismissCoverOnLand = false) },
        forceOpenPos = { p -> markPositionChanged(p) },
        onCoverCommit = { leavingCover ->
            if (leavingCover) {
                coverDismissed = true
                coverVisible = false
            } else {
                coverVisible = true
            }
        },
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
        // 后退回封面：目标就绪即画（封面已解码），无需落位；前进离开封面仍需落位书首。
        val enterCover = !coverVisible && direction < 0 && isBookStart(src)
        ctl.beginDrag(src, direction, slideEnabled, coverVisible, enterCover)
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
        if (!slideEnabled) {
            flip(direction)
            return
        }
        // 封面往返走同一会话机制（封面作 from/target 槽，不再瞬切）：
        //  - 封面上前进 = 离开封面向书首落位（from 槽画封面，落位不就地 dismiss）；
        //  - 封面是第一页，封面上后退无操作。
        if (coverVisible) {
            if (direction <= 0) return
            if (!ctl.beginProgrammatic(direction)) {
                Logger.d("Orilumn.TAP", "cover-exit dropped dir=$direction (session busy)")
                return
            }
            val src = openPos
            if (src == null) {
                ctl.clear()
                flip(direction)
                return
            }
            ctl.beginProgrammaticPage(src, direction, fromIsCover = true)
            return
        }
        // 正文首页后退且封面就绪 = 进封面（目标槽画封面，无落位、立即就绪）。
        if (direction < 0 && isBookStart(openPos)) {
            val src = openPos
            if (src == null) return
            if (!ctl.beginProgrammatic(direction)) {
                Logger.d("Orilumn.TAP", "cover-enter dropped dir=$direction (session busy)")
                return
            }
            ctl.beginProgrammaticCoverEnter(src, direction)
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

    /** 离开封面（点按/方向键前进）：from=封面，target=书首内容页。 */
    fun coverExitWithAnimation() {
        if (!ctl.beginProgrammatic(1)) {
            Logger.d("Orilumn.TAP", "cover-exit dropped (session busy)")
            return
        }
        val src = openPos
        if (src == null) {
            ctl.clear()
            flip(1)
            return
        }
        ctl.beginProgrammaticPage(src, 1, fromIsCover = true)
    }

    /** 进入封面（点按/方向键后退）：from=书首内容页，target=封面（立即就绪，无落位）。 */
    fun coverEnterWithAnimation() {
        val src = openPos ?: return
        if (!ctl.beginProgrammatic(-1)) {
            Logger.d("Orilumn.TAP", "cover-enter dropped (session busy)")
            return
        }
        ctl.beginProgrammaticCoverEnter(src, -1)
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

    /** 当前页屏幕坐标 → 章内字符偏移；长按、拖选、链接命中三方共用。 */
    fun charOffsetAt(p: ReaderPos, xPx: Float, yPx: Float): Int? {
        val lines = currentHost.pageLines(p) ?: return null
        if (lines.isEmpty()) return null
        val images = runCatching { currentHost.pageImages(p) }.getOrNull()
        val backgrounds = runCatching { currentHost.pageBackgrounds(p) }.getOrNull()
        val frameLead = runCatching { currentHost.pageFrameLead(p) }.getOrDefault(0)
        val shift = readerPageShift(
            lines = lines,
            pageImages = images,
            pageBackgrounds = backgrounds,
            frameLead = frameLead,
            contentTop = profile.marginTop.toFloat(),
        ) ?: return null
        return readerCharOffsetAt(
            lines = lines,
            shift = shift,
            contentLeft = profile.marginLeft.toFloat(),
            xPx = xPx,
            // 卡片让位时整页上推了 inputShiftPx：屏幕 y 换算回页坐标要加回同一个数（D-28）。
            yPx = yPx + inputShiftPx,
        )
    }

    /**
     * 跨页接力用：手指在页边距里时按 y 找新页最近行，取靠该边的首/末字。
     *
     * 与 [charOffsetAt] 共用 `pageShift`，但**不要求 x 落在可见字形内**——
     * 自动翻页触发时手指本来就在页边，x 必然超出字形右缘。
     */
    fun charOffsetAtPageSide(p: ReaderPos, yPx: Float, direction: Int): Int? {
        val lines = currentHost.pageLines(p) ?: return null
        if (lines.isEmpty()) return null
        val images = runCatching { currentHost.pageImages(p) }.getOrNull()
        val backgrounds = runCatching { currentHost.pageBackgrounds(p) }.getOrNull()
        val frameLead = runCatching { currentHost.pageFrameLead(p) }.getOrDefault(0)
        val shift = readerPageShift(
            lines = lines,
            pageImages = images,
            pageBackgrounds = backgrounds,
            frameLead = frameLead,
            contentTop = profile.marginTop.toFloat(),
        ) ?: return null
        return selectionSideOffset(lines, shift, yPx + inputShiftPx, direction)
    }

    /** 当前页上的选区几何；无选区/跨章/页无行时 null。 */
    fun selectionGeometryAt(p: ReaderPos, widthPx: Float, heightPx: Float): SelectionPageGeometry? {
        if (!selection.hasSelection || selection.chapter != p.chapter) return null
        val lines = currentHost.pageLines(p) ?: return null
        if (lines.isEmpty()) return null
        val left = profile.marginLeft.toFloat()
        val top = profile.marginTop.toFloat()
        val right = (widthPx - profile.marginRight).coerceAtLeast(left)
        val bottom = (heightPx - profile.marginBottom).coerceAtLeast(top)
        return selectionPageGeometry(
            drawer = selectionDrawer,
            lines = lines,
            chapter = p.chapter,
            start = selection.start,
            endExclusive = selection.endExclusive,
            pageSlice = p.slice,
            frameLead = runCatching { currentHost.pageFrameLead(p) }.getOrDefault(0),
            pageImages = runCatching { currentHost.pageImages(p) }.getOrNull(),
            pageBackgrounds = runCatching { currentHost.pageBackgrounds(p) }.getOrNull(),
            contentLeft = left,
            contentTop = top,
            contentRight = right,
            contentBottom = bottom,
            handleRadiusPx = selectionHandleRadiusPx,
        )
    }

    /** 手指是否落在某个可见手柄的命中圈内；页面端点不在本页的手柄不参与。 */
    fun selectionHandleAt(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): SelectionHandle? {
        val p = openPos ?: return null
        val g = selectionGeometryAt(p, widthPx, heightPx) ?: return null
        // 几何是页坐标；卡片让位时整页上推了 inputShiftPx，屏幕 y 加回同一个数再比（D-28）。
        val pageY = yPx + inputShiftPx
        fun hit(center: Offset?): Boolean =
            center != null && hypot(xPx - center.x, pageY - center.y) <= selectionHandleHitPx
        return when {
            hit(g.startHandle) -> SelectionHandle.Start
            hit(g.endHandle) -> SelectionHandle.End
            else -> null
        }
    }

    /**
     * 打开一条已有标注的编辑卡片，并把选区恢复成这条记录原来的字符范围。
     *
     * 编辑卡开着时选区不消失：读者可以先拖两端手柄改范围，再按 `确定` 把范围连同样式 / 批注
     * 一起写回（2026-11-03 用户定口）。取不到本章原文就只开卡片、不画选区。
     */
    fun openAnnotationEdit(annotation: Annotation) {
        val text = currentHost.textOf(annotation.chapter, 0, Int.MAX_VALUE)
        val selected = text != null && selection.selectRange(
            chapter = annotation.chapter,
            text = text,
            start = annotation.charStart,
            endExclusive = annotation.charEnd,
        )
        if (!selected) selection.clear()
        selectionMenuVisible = false
        magnifierFinger = null
        editingAnnotation = annotation
    }

    /**
     * 长按命中已有标注（D-14）：命中则打开该标注的编辑菜单并返回 true。
     *
     * 只看当前章内存列表的字符区间（`charStart <= off < charEnd`），不查库、不做几何反查；
     * 页边距不参与命中，避免在空白边距上误开菜单。命中后选区恢复成这条标注的范围。
     */
    fun tryOpenAnnotationEdit(xPx: Float, yPx: Float, widthPx: Float, heightPx: Float): Boolean {
        val p = openPos ?: return false
        if (annotationState.chapter != p.chapter) return false
        val left = profile.marginLeft.toFloat()
        val top = profile.marginTop.toFloat()
        val right = (widthPx - profile.marginRight).coerceAtLeast(left)
        val bottom = (heightPx - profile.marginBottom).coerceAtLeast(top)
        // 屏幕 y 换算回页坐标（卡片让位时整页上推了 inputShiftPx，与 charOffsetAt 同口径）。
        val pageY = yPx + inputShiftPx
        if (xPx < left || xPx > right || pageY < top || pageY > bottom) return false
        val offset = charOffsetAt(p, xPx, yPx) ?: return false
        val hit = annotationState.annotationAt(offset) ?: return false
        openAnnotationEdit(hit)
        return true
    }

    /** 收起已有标注的编辑菜单（不改数据）。 */
    fun closeAnnotationEdit() {
        editingAnnotation = null
    }

    /** 长按起手：按 D-7 选整词 / 整句；返回 false 表示此处没有可选的章文本。 */
    fun beginSelectionAt(xPx: Float, yPx: Float): Boolean {
        closeAnnotationEdit()
        val p = openPos ?: return false
        val offset = charOffsetAt(p, xPx, yPx) ?: return false
        val text = currentHost.textOf(p.chapter, 0, Int.MAX_VALUE) ?: return false
        return selection.begin(p.chapter, text, offset, currentHost.textBlockEndsOf(p.chapter))
    }

    /** 拖动手柄/长按后移动：焦点按字符走；越出当前行时保持上一个焦点。 */
    fun extendSelectionAt(xPx: Float, yPx: Float) {
        val p = openPos ?: return
        val offset = charOffsetAt(p, xPx, yPx) ?: return
        selection.extendTo(offset)
    }

    /** 选区是否已顶到本章首/末页（只用于自动翻页停住时的提示，不参与导航）。 */
    fun isSelectionChapterBoundary(p: ReaderPos, direction: Int): Boolean {
        val length = currentHost.textOf(p.chapter, 0, Int.MAX_VALUE)?.length ?: return false
        return selectionAtChapterBoundary(p.slice.charStart, p.slice.charEnd, length, direction)
    }

    /**
     * 选区自动翻一页（D-8 / 3.3）：先只读确认邻页仍在**同一章**，再走 [flipAwait] 导航漏斗。
     *
     * 不能直接调 `adjacent`：那条口会推进临时表指针，且到章末会跨章。这里用
     * [ReaderHost.peekAdjacent] 只读探路，确认同章后才交给漏斗；落位后按手指 y
     * 把焦点接到新页最近行的靠边字（手柄接力）。
     *
     * @return true = 已翻页并接续选区；false = 到章边界 / 邻页未就绪 / 导航被 BUSY-DROP。
     */
    suspend fun autoFlipSelection(direction: Int, fingerY: Float): Boolean {
        val p = openPos ?: return false
        if (selection.chapter != p.chapter) return false
        val peek = runCatching { currentHost.peekAdjacent(p, direction) }.getOrNull() ?: return false
        if (peek.chapter != p.chapter) return false
        val landed = flipAwait(direction, action = "selection-auto-flip") ?: return false
        if (landed.chapter != p.chapter) return false
        val offset = charOffsetAtPageSide(landed, fingerY, direction) ?: return false
        selection.extendTo(offset)
        Logger.d(
            "Orilumn.SEL",
            "auto-flip dir=$direction ch=${p.chapter} ${p.slice.charStart}→${landed.slice.charStart} " +
                "focus=$offset sel=${selection.start}-${selection.endExclusive}",
        )
        return true
    }

    /** 点按 (xPx, yPx)（屏坐标 px）→ 命中链接即导航并返回 true；miss 回 false。 */
    fun tryOpenLinkAt(xPx: Float, yPx: Float): Boolean {
        val p = openPos ?: return false
        val offset = charOffsetAt(p, xPx, yPx) ?: return false
        val target = currentHost.linkTargetAt(p.chapter, offset) ?: return false
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
        // 封面页点按：无链接命中，前进区回正文（走滑动动画，封面作 current 槽）、
        // 中部切栏，后退区无操作（封面已是第一页；退出走顶栏返回键）。
        if (coverVisible) {
            when (ReaderMath.tapZone(xPx, widthPx)) {
                1 -> flipWithAnimation(1)
                else -> barsVisible = !barsVisible
            }
            return
        }
        // 编辑菜单开着时，正文点按只关菜单（卡片开着时点按已由手势层接住，这里是兜底）。
        // 语义与 [cancelNote] 一致：写了一半的内容丢掉、原记录不动、编辑时恢复出来的选区收掉。
        if (editingAnnotation != null) {
            focusManager.clearFocus()
            noteText = ""
            noteColorArgb = null
            selectionMenuVisible = false
            if (selection.hasSelection) selection.clear()
            closeAnnotationEdit()
            return
        }
        // 点按已有标注 = 打开卡片（D-28）：先于清选区、链接与翻页区判定——
        // 点在高亮/划线上不会翻页，也不会把「想改这条」当成「想翻页」。
        // 只看字符区间（与长按同一套命中），页边距上按不到任何行，自然也不会误开。
        val tappedAnnotation = openPos?.let { p ->
            if (annotationState.chapter != p.chapter) return@let null
            charOffsetAt(p, xPx, yPx)?.let { annotationState.annotationAt(it) }
        }
        if (tappedAnnotation != null) {
            openAnnotationEdit(tappedAnnotation)
            Logger.d("Orilumn.TAP", "tap x=${xPx.roundToInt()} y=${yPx.roundToInt()} → annotation card")
            return
        }
        // D-11：有选区时，正文点按只清除选区，不翻页也不开链接。
        if (selection.hasSelection) {
            selection.clear()
            selectionMenuVisible = false
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
    val longPressTimeout = LocalViewConfiguration.current.longPressTimeoutMillis

    // 桌面键鼠：阅读面常驻焦点；左右箭头翻页，Esc 等效中部点按（链接优先，否则栏显隐）。
    // 冒泡口径：输入框等消费方向键后不再到这里，不劫持打字。
    // 点栏按钮/滑条会抢走焦点（clickable 自带焦点），故随定位/栏/面板状态自动夺回；
    // 面板打开时 keysEnabled=false，不夺回也不处理（按键留给面板）。
    val focusRequester = remember { FocusRequester() }
    val currentKeysEnabled by rememberUpdatedState(keysEnabled)
    // 批注输入框开着时：正文手势与键盘翻页都不响应（字打在框里，页面不许动）。
    // 输入条与菜单同开同关（D-27）：放在这里给「关掉后把焦点还给正文」用。
    val currentNoteBarOpen by rememberUpdatedState(selectionMenuVisible || editingAnnotation != null)
    LaunchedEffect(openPos, barsVisible, keysEnabled) {
        if (keysEnabled) focusRequester.requestFocus()
    }
    // 批注输入条关掉后把焦点还给正文：输入框卸载时焦点随之消失，不还回来就得先点一下正文，
    // 方向键才会恢复翻页（桌面手上习惯键盘的人会以为「卡住了」）。
    LaunchedEffect(selectionMenuVisible, editingAnnotation) {
        if (!currentNoteBarOpen && keysEnabled) focusRequester.requestFocus()
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

    /** 删除已有标注：先移出内存（立即消失），再异步落库；成功不弹提示，失败回滚并提示。 */
    fun deleteAnnotation(annotation: Annotation) {
        closeAnnotationEdit()
        selectionMenuVisible = false
        if (selection.hasSelection) selection.clear()
        scope.launch {
            val ok = annotationState.delete(annotation.id)
            if (!ok) snackbar.show("删除失败")
        }
    }

    /** 把当前选区落成一条高亮/划线：先内存出图，再异步存库；成功不弹提示，失败回滚并提示。 */
    fun createSelectionAnnotation(
        kind: AnnotationKind,
        color: Int,
        lineStyle: AnnotationLineStyle,
        note: String? = null,
    ) {
        val ch = selection.chapter
        val s = selection.start
        val e = selection.endExclusive
        if (ch < 0 || e <= s) return
        selectionMenuVisible = false
        // 创建当时的原文快照（D-24）：清单行显示用；章节文本已在缓存里，取不到就留空。
        val quote = currentHost.textOf(ch, s, e)?.take(200)
        scope.launch {
            val saved = annotationState.add(
                Annotation(
                    bookId = 0,
                    chapter = ch,
                    charStart = s,
                    charEnd = e,
                    kind = kind,
                    color = color,
                    // 线型只属于划线：高亮记录不存线型，免得与划线菜单的偏好串味。
                    lineStyle = annotationLineStyleFor(kind, lineStyle),
                    // 批注是这条记录的附件（D-27）：与墨迹同一次写入，不分两步。
                    note = note?.trim()?.ifBlank { null },
                    quote = quote,
                ),
            )
            if (saved == null) {
                if (selection.hasSelection) selectionMenuVisible = true
                snackbar.show("保存失败")
            } else {
                // 落库期间用户可能已换了选区：只清掉仍是这一条的那份，别误清新选区。
                if (selection.chapter == ch && selection.start == s && selection.endExclusive == e) {
                    selection.clear()
                }
            }
        }
    }

    /**
     * 落笔（D-27 / D-28）：**只有操作栏的 `确定` 一个提交口**（点卡片外 / 返回键 / Esc 都是取消）。
     *
     * - 编辑已有记录：把这一段选好的类别 / 线型 / 颜色与批注文字一次性写回（清空文字即去掉批注）。
     * - 新选区：按当前类别 / 线型 / 颜色新建一条（没写字也落一条，这是「先选样式再落笔」的落笔口）。
     *
     * 与 [cancelNote]（点卡片外 / 返回键 / Esc）严格分开：那一个什么都不写。
     */
    fun commitNote() {
        // 落笔即收卡：先让输入框失焦，键盘与卡片才能同一段时间里一起退（D-30）。
        focusManager.clearFocus()
        val text = noteText.trim()
        val picked = noteColorArgb
        val kind = noteKind
        val style = noteLineStyle
        noteText = ""
        noteColorArgb = null
        val editing = editingAnnotation
        if (editing != null) {
            closeAnnotationEdit()
            // 输入期间可能刚点过色槽换了颜色：按 id 取当前记录，别拿旧快照盖回去。
            val current = annotationState.annotations.firstOrNull { it.id == editing.id } ?: run {
                selectionMenuVisible = false
                if (selection.hasSelection) selection.clear()
                return
            }
            val body = text.ifBlank { null }
            // 编辑卡打开时选区已恢复成这条标注的范围；读者拖过手柄就以当前选区为准。
            val range = if (selection.hasSelection && selection.chapter == current.chapter) {
                selection.start to selection.endExclusive
            } else {
                null
            }
            val newStart = range?.first ?: current.charStart
            val newEnd = range?.second ?: current.charEnd
            val rangeChanged = newStart != current.charStart || newEnd != current.charEnd
            // 范围变了就同步摘录：清单里那一行显示的是新选中的原文。
            val quote = if (rangeChanged) {
                currentHost.textOf(current.chapter, newStart, newEnd)?.take(200)
            } else {
                current.quote
            }
            // 线型 / 类别 / 颜色都只选中、不落笔（D-28）：真改过才在提交时写回。
            // 换类别（高亮 ⇄ 划线）也算改样式，口径全在 [annotationAfterEdit] 里。
            val next = annotationAfterEdit(
                current = current,
                kind = kind,
                lineStyle = style,
                pickedColor = picked,
                note = body,
                settings = light,
                charStart = newStart,
                charEnd = newEnd,
                quote = quote,
            )
            selectionMenuVisible = false
            if (selection.hasSelection) selection.clear()
            if (next == null) return
            scope.launch {
                if (!annotationState.update(next)) snackbar.show("保存失败")
            }
            return
        }
        selectionMenuVisible = false
        if (!selection.hasSelection) return
        createSelectionAnnotation(
            kind = kind,
            color = picked ?: annotationDefaultColor(light, kind),
            lineStyle = style,
            note = text.ifBlank { null },
        )
    }

    /**
     * 取消（点卡片外 / 返回键 / Esc）：**什么都不改**。
     *
     * 写了一半的批注、刚选的颜色与线型全部丢掉；编辑态不动原记录；新选区连同选区一起清掉
     * （卡片都关了就没人再引用这块蓝底）。与 [commitNote] 的分工是硬的：这几条路都不是「保存后退出」，
     * 落笔只认操作栏的 `确定`。
     */
    fun cancelNote() {
        // 同上：取消也先失焦，键盘跟着卡片一起落（D-30）。
        focusManager.clearFocus()
        noteText = ""
        noteColorArgb = null
        val editing = editingAnnotation
        if (editing != null) {
            closeAnnotationEdit()
            // 编辑时恢复出来的选区只服务于这张卡：取消后一起收掉，不留没有卡片的手柄。
            selectionMenuVisible = false
            if (selection.hasSelection) selection.clear()
            return
        }
        selectionMenuVisible = false
        if (selection.hasSelection) selection.clear()
    }

    /**
     * 同一格色槽：**只选中**（D-28），不落笔——点错颜色还有机会改，卡片也不会忽然关掉。
     *
     * 落笔交给 `确定` 或点卡片外的正文（见 [commitNote]）。顺手把「这次用的类别 + 色槽」记住
     * （写盘，无重排提交）：下次打开卡片直接停在这一类、这一格上——色槽同时成为该类的新建默认格。
     */
    fun pickNoteColor(kind: AnnotationKind, slot: Int) {
        noteKind = kind
        noteColorArgb = annotationSlotColor(light, kind, slot)
        // 落盘一律小写（`highlight` / `underline`，见 ReaderSettings 的约定），
        // 解析侧 annotationLastKindOf 再另行 lowercase，双向容错。
        var next = withAnnotationDefaultSlot(light, kind, slot)
            .copy(annotationLastKind = kind.name.lowercase())
        // 线型只属于划线：在高亮那一行点色，不能顺手改掉划线菜单记住的线型（两套偏好各自独立）。
        if (kind == AnnotationKind.Underline) {
            next = next.copy(annotationLastLineStyle = noteLineStyle.wire)
        }
        if (next != light) {
            light = next
            currentOnLightCommit(next)
        }
    }

    /**
     * 线型行：**只选中**（D-28），不再一次点按即落笔——否则刚想换线型卡片就关了，批注也写不成。
     * 落笔交给点色槽或 `确定`；收起卡片时按选中的线型落成划线（见 [commitNote]）。
     * 顺手记住这个线型，下次新建直接用。
     */
    fun pickNoteLineStyle(style: AnnotationLineStyle) {
        noteKind = AnnotationKind.Underline
        noteLineStyle = style
        val next = withAnnotationLastLineStyle(light, style)
        if (next != light) {
            light = next
            currentOnLightCommit(next)
        }
    }

    /** 复制本章一段原文：块间补换行，换行只进剪贴板、不参与标注偏移（D-18）。 */
    fun copyRange(ch: Int, s: Int, e: Int): Boolean {
        val text = currentHost.textOf(ch, 0, Int.MAX_VALUE)
        if (text == null) {
            snackbar.show("复制失败：本章内容尚未就绪")
            return false
        }
        val copied = copyTextOf(text, s, e, currentHost.textBlockEndsOf(ch))
        if (copied.isEmpty()) {
            snackbar.show("复制失败：选区为空")
            return false
        }
        val ok = runCatching { clipboard.setText(AnnotatedString(copied)) }.isSuccess
        if (!ok) {
            snackbar.show("复制失败")
            return false
        }
        snackbar.show("已复制")
        return true
    }

    /** 复制选区原文（复制后保留选区）。 */
    fun copySelection() {
        // 复制不改记录、也不关卡片：落笔只有「完成」与点卡片外两处（D-28）。
        copyRange(selection.chapter, selection.start, selection.endExclusive)
    }

    /** 复制一条已有标注覆盖的原文：长按命中后没有选区，复制对象就是这条记录。 */
    fun copyAnnotation(annotation: Annotation) {
        copyRange(annotation.chapter, annotation.charStart, annotation.charEnd)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(profile.bgColor))
            .pointerInput(touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 上一个手势要是没结算就消失了（抬起被吞 / 指针丢失 / 手势协程异常），
                    // 会话会永远停在 Dragging：批注层被藏、所有点按被 busy 墙挡住。
                    // 新的一轮按下说明上一根手指早已离开，正好在这里收尾（判据见下）。
                    ctl.recoverStuckDrag()
                    val downX = down.position.x
                    val downY = down.position.y
                    val downTime = down.uptimeMillis
                    // 栏区落点：手势归栏（按钮/滑条处理），翻页层只排空到抬起，
                    // 不消费、不翻页、不调亮度——点栏按钮漂移不再误翻页。
                    val barsOwned = ReaderMath.downInBars(
                        currentBarsVisible, downY, size.height.toFloat(),
                        currentTopBarH.toFloat(), currentBotBarH.toFloat(),
                    )
                    // 标注卡片开着（D-28）：点在卡片上归卡片（按钮/输入框自己消费），
                    // 手柄照旧可拖（否则选区改不了）；其余手势做两件事——
                    // 上下滑 = 在视口里滚页（视口 = 卡片上沿之上那一条），点按（没动过）= 取消。
                    if (currentNoteBarOpen && !barsOwned) {
                        if (down.isConsumed || downY >= sheetTopPxState) return@awaitEachGesture
                        // 编辑已有标注时同样允许拖手柄改范围：卡片开着不代表选区锁死（2026-11-03 用户定口）。
                        val handle = if (!coverVisible && !ctl.isBusy()) {
                            selectionHandleAt(downX, downY, size.width.toFloat(), size.height.toFloat())
                        } else {
                            null
                        }
                        if (handle == null) {
                            val shiftAtDown = inputShiftPx
                            var dragged = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!dragged &&
                                    ReaderMath.movedBeyondTapSlop(
                                        change.position.x - downX,
                                        change.position.y - downY,
                                    )
                                ) {
                                    dragged = true
                                }
                                if (dragged) {
                                    // 手指往上 ⇒ 页面往上走；最多滚到页底贴住卡片上沿。
                                    userShiftPx = (shiftAtDown - (change.position.y - downY))
                                        .coerceIn(0f, maxShiftPxState)
                                }
                                if (!change.pressed) break
                            }
                            // 点卡片外 = 取消（D-28）：落笔只认操作栏的 `确定`，点外面不写任何东西
                            // （免得「想收起卡片」被当成「那就按当前样式落一条」）。
                            if (!dragged) cancelNote()
                            return@awaitEachGesture
                        }
                        // 落在手柄上：交给下面的手柄拖动路径（卡片保持打开，页面跟着上顶）。
                    }
                    // D-11：长按是进入选区的唯一入口；手柄按下则直接进入拖动。
                    // 动画/封面/栏区都不参与选区；选区已存在时点按由 onTap 清除。
                    var selecting = false
                    var moved = false
                    var longPressJob: Job? = null
                    // D-8 / 3.3：手指在页边带内按住 → 自动翻页并接力选区。
                    // 独立于翻页手势会话（同一根手指正在拖手柄），只走 flipAwait 导航漏斗。
                    var gestureActive = true
                    var autoFlipJob: Job? = null
                    var autoFlipSide = 0
                    var boundaryHintShown = false
                    var lastFingerX = downX
                    var lastFingerY = downY
                    val autoFlipEdgePx = SELECTION_AUTO_FLIP_EDGE_DP * density
                    // 按指针类型分流：鼠标拖拽只做文本选择，不拖页；单击与平板同一个点按入口
                    //（窗口左中右三分区同作用，含点中标注卡片 / 链接的同一优先级）。
                    // 版心几何只用于判断「拖动起选」的落点，和绘制/命中同源。
                    val mouse = down.type == PointerType.Mouse
                    val contentLeftPx = profile.marginLeft.toFloat()
                    val contentTopPx = profile.marginTop.toFloat()
                    val contentRightPx = (size.width - profile.marginRight).toFloat().coerceAtLeast(contentLeftPx)
                    val contentBottomPx = (size.height - profile.marginBottom).toFloat().coerceAtLeast(contentTopPx)
                    val mouseTextDown = mouse && !barsOwned && !coverVisible && !ctl.isBusy() &&
                        !down.isConsumed &&
                        downX >= contentLeftPx && downX <= contentRightPx &&
                        downY >= contentTopPx && downY <= contentBottomPx
                    var mouseSelectingPending = mouseTextDown
                    var pageScrollJob: Job? = null
                    var pageScrollDir = 0
                    /**
                     * 手柄拖到视口上下边界时让**整页**跟着滚（D-28 补充）：视口 = 卡片上沿之上那一条，
                     * 滚到「页底贴卡片上沿」为止。每滚一步就按手指位置重新延伸一次选区，
                     * 于是手指按在原地也能一路选下去。
                     */
                    fun schedulePageScroll(dir: Int) {
                        if (dir == pageScrollDir) return
                        pageScrollDir = dir
                        pageScrollJob?.cancel()
                        pageScrollJob = null
                        if (dir == 0) return
                        pageScrollJob = scope.launch {
                            while (gestureActive && selecting && pageScrollDir == dir) {
                                delay(PAGE_SCROLL_STEP_MS)
                                val cur = inputShiftPx
                                val next = (cur + dir * PAGE_SCROLL_STEP_DP * density)
                                    .coerceIn(0f, maxShiftPxState)
                                if (next == cur) continue
                                userShiftPx = next
                                extendSelectionAt(lastFingerX, lastFingerY)
                            }
                        }
                    }
                    fun scheduleAutoFlip(side: Int) {
                        if (side == 0 || boundaryHintShown || autoFlipJob?.isActive == true) return
                        autoFlipJob = scope.launch {
                            var wait = SELECTION_AUTO_FLIP_FIRST_MS
                            while (gestureActive && selecting && autoFlipSide == side) {
                                delay(wait)
                                if (!gestureActive || !selecting || autoFlipSide != side) return@launch
                                val p = openPos
                                if (p == null || selection.chapter != p.chapter) return@launch
                                val ok = autoFlipSelection(side, lastFingerY)
                                if (!ok) {
                                    if (isSelectionChapterBoundary(p, side)) {
                                        // 封顶到章首/章末：不再跨章，但选区要吃到边界字。
                                        selection.extendTo(if (side > 0) Int.MAX_VALUE else 0)
                                        boundaryHintShown = true
                                        Logger.d(
                                            "Orilumn.SEL",
                                            "auto-flip boundary dir=$side ch=${p.chapter} " +
                                                "sel=${selection.start}-${selection.endExclusive}",
                                        )
                                        snackbar.show(if (side > 0) "已到章末" else "已到章首")
                                        return@launch
                                    }
                                    if (!selection.hasSelection) return@launch
                                }
                                wait = SELECTION_AUTO_FLIP_REPEAT_MS
                            }
                        }
                    }
                    if (!barsOwned && !coverVisible && !ctl.isBusy() && !down.isConsumed) {
                        val handle = selectionHandleAt(
                            downX, downY, size.width.toFloat(), size.height.toFloat(),
                        )
                        if (handle != null) {
                            // 卡片开着时先不改卡片状态：拖手柄期间卡片留在原位、字继续可写（D-28），
                            // 松手后选区还是这份，批注接着写就行。
                            if (!currentNoteBarOpen) {
                                closeAnnotationEdit()
                                selectionMenuVisible = false
                            }
                            mouseSelectingPending = false
                            selection.beginHandleDrag(handle)
                            selecting = true
                            if (!mouse) magnifierFinger = Offset(downX, downY)
                        } else if (!mouse || mouseTextDown) {
                            selectionMenuVisible = false
                            longPressJob = scope.launch {
                                delay(longPressTimeout)
                                if (!moved) {
                                    // 先看是否长按在已有标注上（D-14）：命中就编辑，空白才选字。
                                    val editing = tryOpenAnnotationEdit(
                                        downX, downY, size.width.toFloat(), size.height.toFloat(),
                                    )
                                    if (!editing && !mouse) {
                                        selecting = beginSelectionAt(downX, downY)
                                        if (selecting) magnifierFinger = Offset(downX, downY)
                                    }
                                }
                            }
                        }
                    }
                    var axis = ReaderMath.Axis.NONE
                    var dragDir = 0
                    var slideActive = false
                    // 这次滑动是否已经结算过（抬起时正常结算后置 true，见下面的 finally：
                    // 手势以**任何**方式结束，只要还没结算就必须补一次 endSlide）。
                    var slideEnded = false
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
                    try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 长按标注刚打开编辑菜单：本次手势只负责收尾，不再翻页/拖选/调亮度。
                        // 例外：编辑卡开着时从手柄起手的那次拖动要一路更新选区，不能在这里被吞掉。
                        if (editingAnnotation != null && !selecting) {
                            change.consume()
                            if (!change.pressed) break
                            continue
                        }
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
                        val dx = change.position.x - downX
                        val dy = change.position.y - downY
                        if (!moved && ReaderMath.movedBeyondTapSlop(dx, dy)) moved = true
                        if (!selecting && (moved || !change.pressed)) longPressJob?.cancel()
                        // 长按已起手/正在拖手柄：本次手势只服务选区，不翻页、不调亮度。
                        if (selecting) {
                            if (!change.pressed) {
                                gestureActive = false
                                autoFlipSide = 0
                                schedulePageScroll(0)
                                magnifierFinger = null
                                // 编辑卡片还开着时不要另开浮动菜单：这张卡就是操作栏。
                                if (editingAnnotation == null && selection.hasSelection) {
                                    selectionMenuVisible = true
                                }
                                change.consume()
                                break
                            }
                            lastFingerX = change.position.x
                            lastFingerY = change.position.y
                            if (!mouse) magnifierFinger = Offset(lastFingerX, lastFingerY)
                            extendSelectionAt(lastFingerX, lastFingerY)
                            autoFlipSide = selectionAutoFlipDirection(
                                lastFingerX, size.width.toFloat(), autoFlipEdgePx,
                            )
                            scheduleAutoFlip(autoFlipSide)
                            // 手柄拖到视口上下边界 ⇒ 整页滚（卡片开着时视口只到卡片上沿）。
                            val scrollDir = when {
                                currentNoteBarOpen && lastFingerY > sheetTopPxState - autoFlipEdgePx &&
                                    inputShiftPx < maxShiftPxState -> 1
                                currentNoteBarOpen && lastFingerY < autoFlipEdgePx &&
                                    inputShiftPx > 0f -> -1
                                else -> 0
                            }
                            schedulePageScroll(scrollDir)
                            change.consume()
                            continue
                        }
                        // 指针类型分流：鼠标拖拽才进入选区；单击与平板完全同一条路径
                        //（见上面 `onTap`：标注卡片 → 清选区 → 链接 → 左/中/右三分区），
                        // 触摸仍走下面的三区点按/滑动/亮度，两套语义不抢同一个手势。
                        if (mouse) {
                            if (mouseSelectingPending && moved) {
                                mouseSelectingPending = false
                                selectionMenuVisible = false
                                if (beginSelectionAt(downX, downY)) {
                                    selecting = true
                                    Logger.d(
                                        "Orilumn.SEL",
                                        "mouse-drag ch=${selection.chapter} " +
                                            "sel=${selection.start}-${selection.endExclusive}",
                                    )
                                    lastFingerX = change.position.x
                                    lastFingerY = change.position.y
                                    extendSelectionAt(lastFingerX, lastFingerY)
                                    autoFlipSide = selectionAutoFlipDirection(
                                        lastFingerX, size.width.toFloat(), autoFlipEdgePx,
                                    )
                                    scheduleAutoFlip(autoFlipSide)
                                }
                            }
                            if (!change.pressed) {
                                // 鼠标单击 = 与平板三区点按同作用：左 1/3 上一页、右 1/3 下一页、
                                // 中间切上下栏；点中标注或链接走同一优先级，不翻页。
                                // 按下点已被上层消费（栏/遮罩）就不落到阅读面。
                                if (!moved && !down.isConsumed) onTap(downX, downY, size.width.toFloat())
                                change.consume()
                                break
                            }
                            change.consume()
                            continue
                        }
                        if (!change.pressed) {
                            val upTime = change.uptimeMillis
                            val upX = change.position.x
                            val upDx = upX - downX
                            val upDy = change.position.y - downY
                            if (brightnessActive) endBrightnessGesture()
                            else if (slideActive) {
                                currentEndSlide(vx, size.width.toFloat())
                                slideEnded = true
                            }
                            // 走 flipWithAnimation（同点按）而非 flip()：flip() 是**瞬切**——直接
                            // flipAwait 硬换数据、不播动画、也不经过 FlipController，于是会话
                            // 状态与渲染层不同步。真机症状：快速翻页时同一内容"快速闪一下"
                            // （FLIPLAT 日志里 axis 定轴后紧跟一条 tap-flip，源页与滑动落定的
                            // 目标页一致 ⇒ 同一次手势既滑动又瞬切了一次）。
                            else if (dragDir != 0) flipWithAnimation(dragDir)
                            else if (upTime - downTime < ReaderMath.TAP_MAX_MS &&
                                !ReaderMath.movedBeyondTapSlop(upDx, upDy) &&
                                // 菜单/遮罩等上层已经消费的点按不再落到翻页/切栏/链接。
                                !change.isConsumed
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
                                        // 先立旗再起手：起手这一步若抛异常，finally 仍能把会话收掉
                                        // （否则会话停在 Dragging，批注层被 isBusy 藏起、手势全被吞）。
                                        slideActive = true
                                        currentBeginSlide(dragDir)
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
                    } finally {
                        // 手势以任何方式结束都必须把这次滑动结算掉：抬起事件被上层消费、
                        // 指针 change 丢失、组合离场、循环里抛异常——这些路径原先直接 break/抛出，
                        // `endSlide` 不再被调用，会话就永远停在 Dragging。后果是批注层被
                        // `isBusy()` 藏起来（真机症状：**所有批注突然全部消失**）且后续手势
                        // 全被挡（**页面翻不动**），只有返回键能退出去。
                        if (slideActive && !slideEnded) {
                            Logger.w("Orilumn.TAP", "slide gesture ended without up ⇒ settle")
                            currentEndSlide(vx, size.width.toFloat())
                            slideEnded = true
                        }
                        // 手势被取消/组合离场也必须撤掉长按计时，避免手指离开后凭空起选区。
                        gestureActive = false
                        autoFlipSide = 0
                        longPressJob?.cancel()
                        magnifierFinger = null
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
                    if (currentNoteBarOpen) return@onKeyEvent false
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
        // 封面作为滑动会话的一槽（离开封面=from 槽，进入封面=target 槽）时，路由到正文
        // slide 分支并把封面塞进对应槽——静态封面分支只在无会话时兜底。
        val slideFromCover = ctl.fromIsCover.value && ctl.slideActive(slideEnabled)
        val slideToCover = ctl.targetIsCover.value && ctl.slideActive(slideEnabled)
        val holdingForCover = cover == null && coverResolving && pos?.slice?.charStart == 0
        if (holdingForCover) {
            Box(modifier = Modifier.fillMaxSize().background(Color(profile.bgColor)))
        } else if (cover != null && !slideFromCover) {
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
                // 显示侧走闸后的版本号（见 externalInFlight）：在途时保持旧版式号，
                // 免得先画一次「新参数 × 旧落位」再画最终页。
                contentRevision = displayRevision,
                hostRevision = hostRevision,
                contentWidthPx = (contentRight - contentLeft).toInt(),
                contentHeightPx = (contentBottom - contentTop).toInt(),
                bgColor = profile.bgColor,
                inkColor = profile.fgColor,
                imgCache = imgCache,
                bgCache = bgCache,
                // 按**这一页所属章**取标注：跨章翻页时目标页也能带上自己那一章的装饰
                //（相邻章的标注已预读，见 AnnotationState.prefetchNeighbors）。
                annotations = annotationState.annotationsFor(fromPos.chapter).orEmpty(),
                annotationChapter = fromPos.chapter,
                decorRevision = annotationState.revision,
            )

            // P1 L1：目标页内容（落位未回 ⇒ null，此时只画当前页，那一帧不动画）。
            // 封面离开（from=封面）时 from/target 同为书首页，但 current 槽要画封面而非正文
            // ⇒ 不可按 samePage 抑制 targetContent（否则 target 槽空，露出底色）。
            val suppressTarget = !ctl.fromIsCover.value && samePage(ctl.targetPos.value, fromPos)
            val targetContent = if (ctl.targetReady() && !ctl.targetIsCover.value && !suppressTarget) {
                rememberPageContent(
                    host = currentHost,
                    pos = ctl.targetPos.value,
                    contentRevision = displayRevision,
                    hostRevision = hostRevision,
                    contentWidthPx = (contentRight - contentLeft).toInt(),
                    contentHeightPx = (contentBottom - contentTop).toInt(),
                    bgColor = profile.bgColor,
                    inkColor = profile.fgColor,
                    imgCache = imgCache,
                    bgCache = bgCache,
                    annotations = annotationState.annotationsFor(ctl.targetPos.value?.chapter ?: -1)
                        .orEmpty(),
                    annotationChapter = ctl.targetPos.value?.chapter ?: -1,
                    decorRevision = annotationState.revision,
                )
            } else {
                null
            }

            // 封面槽（离开/进入封面动画）
            val coverBmpNow = coverBmp
            val coverComposable: (@Composable () -> Unit)? = if (coverBmpNow != null) {
                {
                    ReaderCoverPage(
                        cover = coverBmpNow,
                        proportional = !light.coverStretch,
                        bgColor = androidx.compose.ui.graphics.Color(profile.bgColor),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else null

            // 选区几何：覆盖层、放大镜和菜单锚点共用同一份逐字几何（拖手柄时每帧只算一次）。
            // 跨页时只取本页交集；端点不在本页时对应手柄为空（D-8：不画假手柄）。
            val selectionGeo = remember(
                pageContent,
                fromPos.slice,
                selection.hasSelection,
                selection.chapter,
                selection.start,
                selection.endExclusive,
                contentLeft,
                contentTop,
                contentRight,
                contentBottom,
                selectionHandleRadiusPx,
            ) {
                val content = pageContent ?: return@remember null
                if (!selection.hasSelection || selection.chapter != fromPos.chapter) {
                    return@remember null
                }
                selectionPageGeometry(
                    drawer = selectionDrawer,
                    lines = content.lines.orEmpty(),
                    chapter = selection.chapter,
                    start = selection.start,
                    endExclusive = selection.endExclusive,
                    pageSlice = fromPos.slice,
                    frameLead = content.pageFrameLead,
                    pageImages = content.pageImages,
                    pageBackgrounds = content.pageBackgrounds,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRight = contentRight,
                    contentBottom = contentBottom,
                    handleRadiusPx = selectionHandleRadiusPx,
                )
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
                annotationsFor = annotationState::annotationsFor,
            )
            // 上面 `beforeCommit` 的「整页位图备齐」实现：与 PrerasterOnPrefill 同口径
            //（同一渲染器、同一缓存、同一条取数路径），只是针对**本次落位目标页**。
            // `prerasterPage` 已把重画切到 Default 线程，所以这里 await 不冻 UI；等它的结果是
            // 提交即命中——翻页动画不再在 UI 线程同步栅格（0.5–0.9s 冻帧的来源）。
            // 预栅格备的是**闸开后那一次**重栅格的位图 ⇒ 取调用时刻的最新版本号：在途途中
            // 后续 tick 还会再 bump，用构造时捕获的旧值会让这笔预栅格白做（画的那下还得重栅格）。
            val liveRevision by rememberUpdatedState(contentRevision)
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
                    contentRevision = liveRevision,
                    imgCache = imgCache,
                    bgCache = bgCache,
                    annotations = annotationState.annotationsFor(p.chapter).orEmpty(),
                    annotationChapter = p.chapter,
                )
            }
            SideEffect { rasterByRef.value = rasterPageNow }
            // ---- 卡片让位（D-28 第 2 部分）：卡片会压到选区 / 这条标注的墨迹时，整页上顶 ----
            // 卡片上沿 = 窗口高 − 键盘高 − 卡片高（adjustNothing：窗口不缩，键盘高度只能自己量）。
            // 只算一个平移量：绘制、命中、手柄、放大镜、旁注全部读同一个数，不重排、不重烤栅格。
            val editing = editingAnnotation
            val selectionMenuOn = editing == null && selectionMenuVisible && selection.hasSelection &&
                selection.chapter == fromPos.chapter
            val menuOn = (editing != null || selectionMenuOn) && !coverVisible && !ctl.isBusy()
            val imeBottomPx = imeInset?.let { with(LocalDensity.current) { it.toPx() } }
                ?: WindowInsets.ime.getBottom(LocalDensity.current).toFloat()
            // 卡片升起度（D-30）：0 = 整卡在屏幕外，1 = 贴住键盘上沿。它同时决定两件事：
            // 卡片自己的位置（见 AnnotationSheet）和**这边要不要让位**——卡片还在屏幕外时页面不动，
            // 否则先跳一下、再等卡片，又是一处「两段」。宿主没有逐帧 IME 值时不等键盘（gateOnIme = false）。
            val sheetReveal = rememberSheetReveal(menuOn, imeBottomPx, gateOnIme = imeInset != null)
            val sheetTopPx = (pxHeight - imeBottomPx - noteBarHeightPx * sheetReveal).coerceAtLeast(0f)
            // 视口 = 卡片上沿之上那一条：整页可以在里面上下滑，最多滑到「页底正好贴卡片上沿」。
            val maxShiftPx = (pxHeight - sheetTopPx).coerceAtLeast(0f)
            // 编辑时选区已经恢复成这条标注的范围；拖手柄改过以后也按当前选区让位。
            // 只有在取不到原文、没有选区时才退回按原记录范围量。
            val anchorBottomPx = selectionGeo?.rects?.maxOfOrNull { it.bottom }
                ?: if (editing != null) {
                    pageInkRects(
                        drawer = selectionDrawer,
                        lines = pageContent?.lines.orEmpty(),
                        chapter = editing.chapter,
                        charStart = editing.charStart,
                        charEnd = editing.charEnd,
                        pageImages = pageContent?.pageImages,
                        pageBackgrounds = pageContent?.pageBackgrounds,
                        frameLead = pageContent?.pageFrameLead ?: 0,
                        contentLeft = contentLeft,
                        contentTop = contentTop,
                    ).maxOfOrNull { it.bottom }
                } else {
                    null
                }
            // 会压住 ⇒ 一次推到「页底贴卡片上沿」（推满 H，用户定形）；不压 ⇒ 不动。
            // 读者自己上下滑过就以他滑的为准（[userShiftPx]），手柄拖到上下边界时也会滚。
            val baseShiftPx = if (menuOn && anchorBottomPx != null &&
                anchorBottomPx + INPUT_SHIFT_GAP_DP * density > sheetTopPx
            ) {
                maxShiftPx
            } else {
                0f
            }
            val shiftNow = if (menuOn) {
                (userShiftPx ?: baseShiftPx).coerceIn(0f, maxShiftPx)
            } else {
                0f
            }
            // 命中侧读这一份（事件处理发生在组合之后，读到的是最新值）。
            SideEffect {
                inputShiftPx = shiftNow
                sheetTopPxState = if (menuOn) sheetTopPx else pxHeight
                maxShiftPxState = maxShiftPx
            }

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
                    // 显示闸后的版本号：外部落位在途时冻结，避免「新参数 × 旧落位」多画一帧。
                    contentRevision = displayRevision,
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
            // 正文旁的批注文字（D-26）：批注是标注上的一句话，就显示在它旁边。
            // 纯覆盖层：只按行几何算空白，不改排版、不进栅格；**跟着页走**——它被放进
            // 滑动层的两个槽里，和页画布共用同一个平移量（见下面的 FlipSlideLayer）。
            // 早先动画期间整层藏起来、落地才冒出来，观感是「字滑走了、旁注原地消失又出现」。
            // 布点算得很慢（几十到几百毫秒，13 条批注时引线寻路一项就 300ms 上下），
            // 所以整条流水线走后台车道：UI 帧只读缓存结果，算完再由状态推上来。
            // **不能**在帧里同步算——目标页内容恰好在起手那一刻才出现，同步算就是
            // 「手指把页面拉出一点点，画面卡住」的头号来源（2026-11-01 用户报）。
            // 文字尺寸用与绘制同源的实测尺子（不是字宽粗估）：差半像素就会被省略号截掉。
            val noteTextSizer = rememberNoteTextSizer(noteFontFamily, noteFontWeight)
            val noteLane = remember(noteTextSizer) {
                // 车道独占的绘制器与尺子：前台那个 selectionDrawer 在别的线程手上用过就脏了。
                PageNoteLane(drawer = LineWindowDrawer(), textSizer = noteTextSizer)
            }
            // 布点结果按页留几页：同一次翻页里，目标页起手先当目标槽、落地又变成当前槽；
            // 留一份就不必算两遍（布点是本页最贵的一笔，几百毫秒量级）。
            // 有效性按「那一章的标注代数」判（见 PageNoteMemo），所以这里**不用**拿标注修订号
            // 当键：修订号在换章时会连续 bump，整只换新会让刚看过的上一章一起作废。
            val noteMemo = remember(
                noteTextSizer, profile.bodyPx, settings.noteFontScale, settings.noteCollapseAll,
                hostRevision, displayRevision, contentLeft, contentTop, contentRight, contentBottom,
                pxWidth, pxHeight, annotationState,
            ) { PageNoteMemo(generationOf = annotationState::generationOf) }
            // 换新表就把旧表作废：拖批注字号时每挪一格都会换表，旧表排队的布点请求若照跑，
            // 会把单线程车道堵住几十秒（2026-11-02 真机：拖 6 秒堵 94 秒，当前页批注久不出来）。
            DisposableEffect(noteMemo) { onDispose { noteMemo.dispose() } }
            // 落定后预热前后邻页的旁注：车道是异步的，不预热的话冷页要等算完才出现
            //（真机 600~1600ms 的「页面先空着，旁注后冒出来」）。预热后翻过去是缓存命中，
            // 旁注跟着目标页一起滑进来。
            WarmAdjacentPageNotes(
                host = currentHost,
                pos = openPos,
                // 按页所属章取标注：邻页在别的章时用那一章的预读快照（没有就跳过，不拿空表算）。
                annotationsFor = annotationState::annotationsFor,
                dataVersion = annotationState.dataVersion,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRight = contentRight,
                contentBottom = contentBottom,
                pageWidth = pxWidth,
                pageHeight = pxHeight,
                bodyFontPx = profile.bodyPx,
                fontScale = settings.noteFontScale,
                collapseAll = settings.noteCollapseAll,
                lane = noteLane,
                memo = noteMemo,
                directionProvider = { ctl.session.direction },
            )
            val noteBubbles: List<NoteBubble> = rememberPageNotes(
                content = pageContent,
                chapter = fromPos.chapter,
                charStart = fromPos.slice.charStart,
                charEnd = fromPos.slice.charEnd,
                annotations = annotationState.annotationsFor(fromPos.chapter).orEmpty(),
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRight = contentRight,
                contentBottom = contentBottom,
                pageWidth = pxWidth,
                pageHeight = pxHeight,
                bodyFontPx = profile.bodyPx,
                fontScale = settings.noteFontScale,
                collapseAll = settings.noteCollapseAll,
                lane = noteLane,
                memo = noteMemo,
                decorRevision = annotationState.revision,
                notesReady = annotationState.isReady(fromPos.chapter),
            )
            // 目标页的旁注：翻页时跟着目标页一起滑进来，落地那一刻才不会「凭空冒出来」。
            // 没有目标页时就是空表——不用 `if` 包起来，remember 的位置才稳定。
            val targetPosNow = ctl.targetPos.value
            val targetNoteBubbles: List<NoteBubble> = rememberPageNotes(
                content = targetContent,
                chapter = targetPosNow?.chapter ?: fromPos.chapter,
                charStart = targetPosNow?.slice?.charStart ?: 0,
                charEnd = targetPosNow?.slice?.charEnd ?: 0,
                annotations = annotationState.annotationsFor(targetPosNow?.chapter ?: fromPos.chapter)
                    .orEmpty(),
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRight = contentRight,
                contentBottom = contentBottom,
                pageWidth = pxWidth,
                pageHeight = pxHeight,
                bodyFontPx = profile.bodyPx,
                fontScale = settings.noteFontScale,
                collapseAll = settings.noteCollapseAll,
                lane = noteLane,
                memo = noteMemo,
                decorRevision = annotationState.revision,
                notesReady = annotationState.isReady(targetPosNow?.chapter ?: fromPos.chapter),
            )
            val NoteBubblesFun: @Composable (List<NoteBubble>) -> Unit = { bubbles ->
                if (bubbles.isNotEmpty()) {
                    NoteBubbles(
                        bubbles = bubbles,
                        fgColor = Color(profile.fgColor),
                        pageBgColor = profile.bgColor,
                        onOpen = { id ->
                            // 点旁注 = 打开这条标注的操作栏与输入框（有文字就带出来，可改可删）。
                            // 翻页动画进行中不响应（旁注现在跟着页走，滑动的页面上点一下不该弹编辑态）。
                            // 选区恢复成这条标注的范围：编辑时两端手柄仍在，可以直接改范围。
                            val target = annotationState.annotationsFor(fromPos.chapter)
                                ?.firstOrNull { it.id == id }
                            if (target != null && !ctl.isBusy()) {
                                openAnnotationEdit(target)
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        fontFamily = noteFontFamily,
                        fontWeight = noteFontWeight,
                        showBorder = settings.noteBorder,
                        showBackground = settings.noteBackground,
                        textSameColor = settings.noteTextSameColor,
                    )
                }
            }
            // 卡片让位：整层（页画布 + 选区 + 旁注 + 放大镜）一起上推，顶部超出屏幕的部分直接截断。
            // 放在同一个 `graphicsLayer` 里 ⇒ 子层之间的相对位置不变，不可能出现「页动了字没动」。
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { translationY = -shiftNow }) {
            FlipSlideLayer(
                direction = ctl.direction.value,
                // **不要在这里读 ctl.progress.value**：一旦在组合里读，动画每帧都会让整个
                // 阅读面重组、两个画布每帧重走 drawLines（见 FlipSlideLayer KDoc）。
                // 传闭包，让 progress 只在 graphicsLayer 的 draw 相位被读。
                progress = { if (ctl.slideActive(slideEnabled)) ctl.progress.value else 0f },
                pageWidthPx = pxWidth,
                modifier = Modifier.fillMaxSize(),
                target = when {
                    slideToCover && coverComposable != null -> coverComposable
                    targetContent != null -> {
                        {
                            PageCanvasFun(targetContent)
                            // 旁注和目标页同一个槽 ⇒ 同一个平移量，跟着一起滑进来。
                            NoteBubblesFun(targetNoteBubbles)
                        }
                    }
                    else -> null
                },
                current = if (slideFromCover && coverComposable != null) {
                    coverComposable
                } else {
                    {
                        PageCanvasFun(pageContent)
                        // 旁注和当前页同一个槽 ⇒ 一起滑出去，不会先「原地消失」再动画。
                        NoteBubblesFun(noteBubbles)
                    }
                },
            )
            // 选区覆盖层：轻量即时画，不参与页栅格；翻页动画期间隐藏，避免盖在滑动中的两页上。
            if (selection.hasSelection && pageContent != null && !coverVisible &&
                !ctl.isBusy() && selection.chapter == fromPos.chapter
            ) {
                SelectionOverlay(
                    geometry = selectionGeo,
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRight = contentRight,
                    contentBottom = contentBottom,
                    handleRadiusPx = selectionHandleRadiusPx,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 移动端放大镜：长按/拖手柄时跟随手指，桌面鼠标不显示（D-10）。
            // 内容复用同一页栅格（同一个 renderer / content / rasterKey），只做圆形裁剪与放大。
            val fingerNow = magnifierFinger
            if (fingerNow != null && selection.hasSelection && pageContent != null &&
                !coverVisible && !ctl.isBusy() && selection.chapter == fromPos.chapter
            ) {
                SelectionMagnifier(
                    content = pageContent,
                    // 这一层被「卡片让位」上推了 shiftNow，指针给的却是屏幕坐标：两端都换算成
                    // 层坐标，镜面才跟得住手指（少这一步，镜面会离手指差一整段顶推量）。
                    finger = magnifierLayerFinger(fingerNow, shiftNow),
                    bounds = magnifierLayerBounds(
                        pxWidth, pxHeight, shiftNow,
                        sheetTopPx = if (menuOn) sheetTopPx else pxHeight,
                    ),
                    contentLeft = contentLeft,
                    contentTop = contentTop,
                    contentRight = contentRight,
                    contentBottom = contentBottom,
                    pageBg = profile.bgColor,
                    inkColor = profile.fgColor,
                    contentRevision = displayRevision,
                    renderer = pageRenderer,
                    onMiss = onRasterMiss,
                    // 放大镜里的选区与正文覆盖层共用同一份几何：放大后仍能看见选到哪儿、手柄在哪儿。
                    selection = selectionGeo,
                    selectionHandleRadiusPx = selectionHandleRadiusPx,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            } // 结束「卡片让位」的整层平移
            // 标注卡片（D-28）：操作栏 + 输入行合成一张卡，贴键盘上沿。它不再跟选区锚点走，
            // 所以选区在页面任何位置都不用担心「菜单跑出屏幕」；被遮住时整页上顶（第 2 部分）。
            // 输入框与操作栏跟着「这一次写哪条」重置：编辑带出它的文字 / 类别 / 线型，新选区从空白开始。
            LaunchedEffect(editing?.id, selectionMenuOn) {
                // 换一条（新建 ↔ 编辑、或换编辑对象）就清掉「临时选过的颜色」：那是上一次的意图。
                noteColorArgb = null
                when {
                    editing != null -> {
                        noteText = editing.note.orEmpty()
                        noteKind = editing.kind
                        noteLineStyle = editing.lineStyle.takeIf { editing.kind == AnnotationKind.Underline }
                            ?: annotationLastLineStyleOf(light)
                    }
                    selectionMenuOn -> {
                        noteText = ""
                        noteKind = annotationLastKindOf(light)
                        noteLineStyle = annotationLastLineStyleOf(light)
                    }
                    else -> noteText = ""
                }
            }
            // 卡片**从屏幕下沿滑上来**、收起时再滑下去（D-30）。它自己已经按键盘高度上移（[AnnotationSheet]
            // 里的 imePx），所以滑入这一段里卡片的下半截正被升起的键盘挡着——看上去是「卡片 + 键盘」
            // 整整一块从底下推上来，而不是先弹一张卡片、再单独顶起键盘。
            // 没收动画时卡片一瞬消失、键盘还在半空；现在两者同一段时间里各自归位。
            // 在场由升起度决定：收起动画跑完才卸内容（降到 0 时卡片本来就已经在屏幕外面了）。
            if (menuOn || sheetReveal > 0f) {
                // 选区场景：选中的字里已有高亮/划线时，删除作用于**相交的第一条**（列表按起点排序）。
                val overlapping = if (editing == null) {
                    annotationState.annotationsFor(fromPos.chapter)?.firstOrNull {
                        it.charStart < selection.endExclusive && selection.start < it.charEnd
                    }
                } else {
                    null
                }
                val deleteTarget = editing ?: overlapping
                AnnotationSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    visible = menuOn,
                    imePx = imeBottomPx,
                    reveal = sheetReveal,
                    target = NoteBarTarget(
                        annotationId = editing?.id,
                        accentArgb = if (editing != null) {
                            editing.color ?: annotationFallbackColor(editing.kind)
                        } else {
                            annotationSlotColor(light, noteKind, annotationDefaultSlot(light, noteKind))
                        },
                    ),
                    text = noteText,
                    palette = paletteFor(light.scheme),
                    kind = noteKind,
                    lineStyle = noteLineStyle,
                    highlightColors = annotationColorSlots(light, AnnotationKind.Highlight),
                    highlightDefaultSlot = annotationDefaultSlot(light, AnnotationKind.Highlight),
                    underlineColors = annotationColorSlots(light, AnnotationKind.Underline),
                    underlineDefaultSlot = annotationDefaultSlot(light, AnnotationKind.Underline),
                    // 编辑时只在本类色槽行里给这条记录描圈。
                    // 编辑时只在本类色槽行里给这条记录描圈；刚点过的那一格优先（还没落笔也看得见）。
                    currentColor = noteColorArgb ?: if (editing != null && editing.kind == noteKind) {
                        editing.color ?: annotationFallbackColor(editing.kind)
                    } else {
                        null
                    },
                    canDelete = deleteTarget != null,
                    onTextChange = { noteText = it },
                    onKindChange = { noteKind = it },
                    onPickColor = { kind, slot -> pickNoteColor(kind, slot) },
                    onPickLineStyle = { pickNoteLineStyle(it) },
                    // 复制只进剪贴板：不动记录、也不关卡片（落笔只有「确定」与点卡片外两处）。
                    onCopy = {
                        if (editing != null) copyAnnotation(editing) else copySelection()
                    },
                    onDelete = { deleteTarget?.let { annotation -> deleteAnnotation(annotation) } },
                    onDone = { commitNote() },
                    onCancel = { cancelNote() },
                    onHeightChange = { noteBarHeightPx = it },
                )
            }
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
