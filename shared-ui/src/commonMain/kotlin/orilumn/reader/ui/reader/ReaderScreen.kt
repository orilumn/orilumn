package orilumn.reader.ui.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import orilumn.reader.data.annotation.Annotation
import orilumn.reader.data.annotation.AnnotationKind
import orilumn.reader.data.annotation.AnnotationLineStyle
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.revision.TextRevision
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.paging.BookPageNumber
import orilumn.reader.engine.revision.BlockKind
import orilumn.reader.engine.revision.FormatTraces
import orilumn.reader.engine.revision.InlineMarkup
import orilumn.reader.engine.revision.RevisionBlocks
import orilumn.reader.engine.revision.TableGrid
import orilumn.reader.engine.revision.TableRowInfo
import orilumn.reader.engine.revision.TableRowScope
import orilumn.reader.engine.revision.tableRowCardOf
import orilumn.reader.engine.revision.tableRowsOfRevision
import orilumn.reader.engine.skia.LineWindowDrawer
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.io.Logger
import orilumn.reader.ui.reader.curl.CurlPageImages
import orilumn.reader.ui.reader.curl.FlipCurlLayer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * （[ReaderSettingsPanel]/[ReaderSidePanel]，S29）；书签 / 笔记 / 修订按钮打开同一抽屉的对应页
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
    /** 底栏「修订」：打开正文修改清单（修订面板）。null = 宿主尚未支持，点了给提示。 */
    onRevision: (() -> Unit)? = null,
    /** 底栏「审阅」：切换审阅视图（删掉的字变红划红线、新添的字变绿）。null = 宿主尚未支持。 */
    onReview: (() -> Unit)? = null,
    /** 审阅视图是否开着（栏上按钮高亮）。 */
    reviewActive: Boolean = false,
    /** 夜间（深色）模式是否开着（栏上「夜间」按钮与「审阅」同一套高亮）。 */
    nightActive: Boolean = false,
    onLightChange: (ReaderSettings) -> Unit = {},
    onLightCommit: (ReaderSettings) -> Unit = {},
    /**
     * 本机能不能调屏幕亮度（默认能，平板走系统背光）。桌面版没有背光控制通路，壳传 false：
     * 三组亮度手势整组禁用，且旧存档里残留的负亮度不再画压暗遮罩（否则读者无从调回）。
     */
    brightnessAdjustable: Boolean = true,
    /** Q1-b：顶/底栏显隐同步（Android 宿主据此显隐系统栏 chrome；桌面/其它平台可忽略）。 */
    onBarsVisibleChanged: (Boolean) -> Unit = {},
    /** Q1-b：外部推送的落位（版式重排绑定 / 目录跳转落地后同步）。非空即采用为当前定位并防抖
     *  保存；null 不动作（重排以同一目标字符合位，字符仍在新分页表里即可连续阅读）。 */
    externalPos: ReaderPos? = null,
    /**
     * 外部推送序号：同一个目标反复推送（翻走后再点同一条书签）时，单靠 [externalPos]
     * 的值相等不会触发 effect，页面就停在原处；序号每次推送都变，保证重新认领。
     */
    externalEpoch: Int = 0,
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
     * 结算动画时长倍率：桌面壳传 2，平板/其它默认 1。
     *
     * 平板是手指拖拽，松手前页面已经跟手走了半页，剩下那半页 300ms 够用；桌面是
     * 滚轮/方向键/触控板，一按就从 0 走满整页，没有跟手段垫底，同一时长在宽屏上
     * 就是「瞬切」。只放大时长，不动帧率与几何（见 [FlipSession.settleDurationMs]）。
     */
    flipSettleScale: Float = 1f,
    /**
     * 标注数据外部变更号（标注清单删除后 ++）：单独一条 effect 重读当前章标注，
     * 让屏幕上那条装饰同步消失。**不能**并进「进章」effect——同章 `load(ch)` 会早退，
     * 不会重读；它也不是版式修订号，不驱动重新分页。
     */
    annotationsEpoch: Int = 0,
    /**
     * 书签外部变更号：书签清单里添加 / 删除后 +1，阅读面重读当前页的书签状态，
     * 右上角图案随即出现 / 消失。只影响当前页图案，不重排、不重读正文。
     */
    bookmarksEpoch: Int = 0,
    /**
     * 统一字体列表（系统 + 导入）：只用来把「批注字体」的字族名解析成可绘制的字形。
     *
     * 批注是盖在页面上的覆盖层，与正文分页无关；缺省空表 = 导入族解析不到，退回系统默认字体。
     */
    fontEntries: List<FontEntry> = emptyList(),
) {
    val density = LocalDensity.current.density
    // 卡片收起时主动让输入框失焦（D-30）：不主动收，键盘会等卡片卸载才开始降，
    // 于是「卡片先滑走、键盘再落」——正是要消掉的两段式。失焦后键盘与卡片同一段时间里各自归位。
    val focusManager = LocalFocusManager.current
    val profile = remember(settings, density) { TypographicProfile.build(settings, density) }
    // 批注字体（渲染层解析）：字族名 + 字重 → Compose 字体。没单独挑 = 采用正文字体
    // （正文槽 → 主题层 / 通用族替换；原书设置下退回平台默认），字体已删 = 平台默认。
    // 只影响旁注覆盖层，不进版式（`noteFont` 在 withoutNonLayout 里，面板走无重排提交）。
    // 字重**不烤进字体族**：桌面端单面族没有第二档，Compose 仍按族名去系统字库取面、烤好的字重被丢，
    // 所以族和字重分开给（族 → `fontFamily`，字重 → `fontWeight`），量宽与绘制两处都要带上。
    // 跟随正文时要跟着正文字形变：`profile` 里含正文槽 / 主题 / 通用族与字重锚点，一并做键。
    val noteFontFamily = remember(profile, settings.noteFont, settings.noteFontWeight, fontEntries) {
        noteFontFamilyOf(settings, profile, fontEntries)
    }
    val noteFontWeight = remember(profile, settings.noteFont, settings.noteFontWeight) {
        noteFontWeightOf(settings, profile)
    }
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
    // 下拉切换书签：页面让开的偏移（px，最多 1/4 页）与当前页是否已有书签（null = 还在读/读取失败）。
    // 偏移用 Animatable 承载：拖动时直接 snapTo，松手走弹簧；读它的只有页层平移与右上角图案。
    val bookmarkPull = remember { Animatable(0f) }
    var bookmarkOnPage by remember { mutableStateOf<Boolean?>(null) }
    // 上下栏实测高度（px）：栏区落点的手势归栏，不进翻页层（点栏按钮漂移误翻页的门控）。
    var topBarH by remember { mutableStateOf(0) }
    var botBarH by remember { mutableStateOf(0) }
    // 亮度工作快照：初始 = 传入设置；亮度手势只在本地改 brightness，物理背光经回调通知宿主。
    var light by remember { mutableStateOf(settings) }
    // 设置面板/外部 commit 驱动的亮度与护眼修改同步进本地工作快照（亮度手势期间 live 值由
    // onLightChange 直达宿主，此 effect 在宿主回写 settings 后收敛，二者同值不冲突）。
    LaunchedEffect(settings) { light = settings }
    // 不提供亮度调节的平台（桌面）：压暗遮罩一律按 0 画——亮度手势既然禁用，
    // 旧存档里残留的负值就没有任何出口，留在屏上等于永久压暗。
    val maskLight = remember(light, brightnessAdjustable) {
        if (brightnessAdjustable) light else light.copy(brightness = 0)
    }
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
    // 动画总闸：任何模式都走同一套翻页状态机（手势 / 结算 / 落位）。
    // 具体画哪一档（滑动 / 卷曲）由渲染层二选一，状态机不区分；「无动画」= 不走动画，
    // 点按与手势直接落到目标页。
    val slideEnabled = light.pageAnimationMode != "none"
    // 折页档：模式 = curl。折页是纯 Compose 画布（多边形裁切 + 渐变），不再依赖 GPU 着色器，
    // 因此**全平台可用**，不需要平台能力判定。
    val curlEnabled = light.pageAnimationMode == "curl"

    val scope = rememberCoroutineScope()
    // 锚页事件串行漏斗：显示状态的唯一写入通道（见 AnchorFunnel）。所有改锚页位置的动作
    // （翻页/跳转/开书/外部落位/开链接）走它串行，后到按落定后的最新位置重取源，不再各算各的。
    val anchorFunnel = remember { AnchorFunnel() }
    val currentHost by rememberUpdatedState(host)
    // 右上角书签图案的落点：固定在屏幕上沿，不随标题栏/状态栏显隐上下移动；
    // 页首越过图案下沿即算「拉过图案」。
    val bookmarkMarkTop = BookmarkMarkTop
    val bookmarkMarkBottomPx = with(LocalDensity.current) {
        (bookmarkMarkTop + BookmarkMarkHeight).toPx()
    }
    val currentMarkBottomPx by rememberUpdatedState(bookmarkMarkBottomPx)
    // 翻到新页、或书签清单外部增删后就重读这一页有没有书签：决定右上角图案是否常驻红色。
    LaunchedEffect(openPos, bookmarksEpoch) {
        val pos = openPos
        bookmarkOnPage = if (pos == null) {
            null
        } else {
            when (currentHost.lookupBookmarkOnPage(pos)) {
                is BookmarkLookup.Found -> true
                BookmarkLookup.None -> false
                BookmarkLookup.Failed -> null
            }
        }
    }
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
    // 正文修改（v3）：与批注卡片共用同一张卡（模式不同）。打开时把选区冻成下面三个数，
    // 输入区内容单独持一份（不与批注文字串味）。
    var textEditOpen by remember { mutableStateOf(false) }
    var textEditChapter by remember { mutableIntStateOf(-1) }
    var textEditStart by remember { mutableIntStateOf(0) }
    var textEditEnd by remember { mutableIntStateOf(0) }
    var textEditText by remember { mutableStateOf("") }
    // 开卡时选区的**行内结构**（已换算到输入区那把尺）与纯文字：提交时原样没变就直接短路，
    // 不白落一条修订；属性补回（读者没写的 class / id）也拿它当模板。
    var textEditOriginalSpans by remember {
        mutableStateOf<List<InlineMarkup.StyleSpan>?>(null)
    }
    var textEditBaseText by remember { mutableStateOf("") }
    // 开卡时输入区的原文（**含块标记**）：读者只改了标记（比如删掉 `## `）也算改过，不能按 no-op 收卡。
    var textEditBaseCard by remember { mutableStateOf("") }
    // 开卡时每一块的表格结构 / 是不是整块图片（与选区切出的块一一对应）。
    // 提交时按它校验：表格只能改格里的字，图片只能整张删除，结构不能借这张卡改掉。
    var textEditTables by remember { mutableStateOf<List<TableRowInfo?>>(emptyList()) }
    var textEditImages by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    // 这次修订的选区**正好是一整张表**（卡片已扩到表首 / 表尾）：行数、每行列数都可以改，
    // 提交时按网格自洽校验（见 `TableGrid`），不按逐行形状校验。
    var textEditWholeTable by remember { mutableStateOf(false) }
    // 这次改的是整表修订里的**某几行**（审阅视图里拖选改过的表格中的一行）：卡片只列这几行，
    // 提交时再拼回整张表（结构校验仍按整张表）。null = 普通卡片 / 整表卡片。
    var textEditRowScope by remember { mutableStateOf<TableRowScope?>(null) }
    // 审阅视图里「改过的表格 + 表格外的文字」一起选：卡片把两样都列出来，提交时按段切开——
    // 表格那段走行范围 / 整表，文字那段走普通区间（见 `TextEditPart`）。
    // 值 = 那条整表修订在当前显示空间里的区间；null = 普通卡片。
    var textEditTableRange by remember { mutableStateOf<IntRange?>(null) }
    // 整段都包着选区的行内元素（选区完全落在元素内部）：卡片里**不显示标记**——读者看不出
    // 边界在哪，写了标记反而会把它挪位置；提交时按「整段仍然属于这个元素」原样套回。
    var textEditAmbientSpans by remember {
        mutableStateOf<List<InlineMarkup.StyleSpan>>(emptyList())
    }
    // 卡片每行「自己就是书里那个行内元素」（`display:block` 的 `span` 之类）的标签名，与输入区
    // 的行一一对应；null = 这次开卡没有这种行（老宿主 / 拿不到结构 / 老修订）。只用于显示与校验。
    var textEditOwnTags by remember { mutableStateOf<List<String?>?>(null) }
    // 读者动过输入区里的字没有：没动过时，拖正文手柄改选区要把输入区一起换过去（用户定口）；
    // 动过之后一律以输入区为准，选区只当落笔范围，不再回写输入框（免得吞掉改写）。
    // 打开旧修改（审阅视图点红字 / 绿字）时输入区带的是已经改过的字，一律按「动过」算。
    var textEditDirty by remember { mutableStateOf(false) }
    // 这次开卡是不是「重开一条已有修订」：是的话输入区带的是改后整段，拖手柄不能把它换成新选区原文。
    var textEditFromRevision by remember { mutableStateOf(false) }
    // 块级标记（`# ` / `> ` / `- ` / 围栏）：这次开卡拿不拿得到每块的形态。
    // 拿不到（老宿主 / 非盒式排版）就整套按纯文本提交，绝不按「没写标记 = 普通段落」去改结构。
    var textEditBlocksKnown by remember { mutableStateOf(false) }
    // 第一行改不了块形态：选区起点落在段中间（拿到的是半截文字），或这一行本来就不归 markdown 管。
    // 那一行不能写块标记（写了要拒），提交时也不写形态——那里的「没有标记」不代表普通段落。
    var textEditFirstLineNoKind by remember { mutableStateOf(false) }
    var textEditBusy by remember { mutableStateOf(false) }
    // 输入区里的标记错在哪（规格 §5）：解析失败 / 提交被拒就**写在卡片里**，
    // 卡片不关、正文不动。不再弹底部提示——那里离输入框太远，而且只显示第一条。
    var textEditError by remember { mutableStateOf<String?>(null) }
    // 选区所在**段**的渲染空间区间：提交时按它取同段的行内结构当模板（规格 §4.3「读者新写的标记」
    // 要长得像同段已有的同标签元素）。取不到就退回选区自身的结构。
    var textEditParagraphRange by remember { mutableStateOf<IntRange?>(null) }
    // 修改成功后 +1：同一 pos 的页面也要重取行/图/栅格（contentRevision 由壳传入，这里改不到）。
    var textEditRevision by remember { mutableIntStateOf(0) }
    val effectiveRevision = contentRevision + textEditRevision
    // 本章正文修订（**渲染坐标**）：正文修改卡用它判「这段字改过没有」——
    // 有修订时「删除」才可点（删除 = 撤掉这条修改、把那段字恢复成原文）。
    var chapterRevisions by remember { mutableStateOf<List<TextRevision>>(emptyList()) }
    // 审阅开关也是键：修订区间是「当前文字空间」的（审阅 = 原区间 + 紧随其后的新文字），
    // 切视图后同一章要重读一遍，否则点按命中还按旧空间的区间算。
    LaunchedEffect(currentHost, openPos?.chapter, selectionMenuVisible, effectiveRevision, reviewActive) {
        val ch = openPos?.chapter
        chapterRevisions = if (ch == null) emptyList() else currentHost.textRevisionsForChapter(ch)
    }
    // 卡片让位时整页上推的像素（屏幕坐标）：绘制与命中**共用这一个数**（单一来源）。
    var inputShiftPx by remember { mutableStateOf(0f) }
    // 读者自己上下滑过的页面偏移（null = 还没滑过，按「会遮住就推满」自动决定）。
    var userShiftPx by remember { mutableStateOf<Float?>(null) }
    // 卡片上沿（屏幕 px，含键盘让位）：手势侧用它判断「这一下是不是点在卡片里」。
    var sheetTopPxState by remember { mutableStateOf(0f) }
    // 视口高度（卡片上沿之上那一条）：手动滑动与手柄拖到边界时用它夹范围。
    var maxShiftPxState by remember { mutableStateOf(0f) }
    // 卡片让位时页面上下滚动的惯性动画：抬手后继续走、逐步减速（见 startPageFling）。
    // 用不参与组合状态的壳持有，写它不该触发重组。
    val pageFling = remember { JobSlot() }
    // 桌面滚动翻页：横滑判据（只有横向算数、一次连续横滑最多翻一页）与
    // 「动画在飞时攒一次、落地补翻」的槽，见 ScrollFlip.kt。
    val scrollFlip = remember { ScrollFlipArbiter() }
    val pendingScrollFlip = remember { PendingScrollFlip() }
    // 滚轮那一串的断流计时器（见 ScrollFlip.kt 的 WheelBurstTimer）。
    val wheelBurstTimer = remember { WheelBurstTimer() }
    // 卡片开着时**按类别分开**记住选过的颜色：点色槽只记住，落笔还要等完成 / 点卡片外。
    // 高亮与划线是两套互不相通的色槽（半透明底色 vs 不透明墨色），所以颜色必须跟类别一起记；
    // 只存一个不分类别的颜色，切到另一类就会把上一类刚点的颜色带进落笔（实测的串色就是这个）。
    val notePickedColors = remember { mutableStateMapOf<AnnotationKind, Int>() }
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
        textEditOpen = false
        textEditText = ""
        textEditDirty = false
    }
    // 选区被任何路径清空时，菜单必须同步收起（避免留下指向空选区的浮层）。
    LaunchedEffect(selection.hasSelection) {
        if (!selection.hasSelection) {
            selectionMenuVisible = false
            magnifierFinger = null
        }
    }
    // 卡片换了一条（新建 ↔ 编辑、或换编辑对象）⇒ 读者自己滑过的位置作废，重新按「遮不遮住」决定；
    // 还在跑的惯性滚动一起收掉，免得它把刚清掉的位置又写回去。
    LaunchedEffect(editingAnnotation?.id, selectionMenuVisible) {
        pageFling.job?.cancel()
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
            // 两段各自计时：落位时延的主要来源就是这两笔（见 FLIPLAT 分段日志）。
            val t0 = System.nanoTime()
            warmByRef.value(pos)
            val tWarm = System.nanoTime()
            rasterByRef.value(pos)
            val tEnd = System.nanoTime()
            Logger.d(
                "Orilumn.TAP",
                "FLIPLAT pre-commit ch=${pos.chapter} char=${pos.slice.charStart} " +
                    "img=${(tWarm - t0) / 1_000_000}ms raster=${(tEnd - tWarm) / 1_000_000}ms",
            )
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
    val currentOnRevision by rememberUpdatedState(onRevision)
    val currentOnReview by rememberUpdatedState(onReview)
    /**
     * 审阅视图的改动痕迹**以宿主当前文字空间为准**（[ReaderHost.reviewMarks]），不跟 UI 的
     * [reviewActive] 走：开关状态与引擎换空间不是同一拍，按 UI 状态取会在切换中途拿到
     * 「新文字 + 旧痕迹表」⇒ 先看到黑色的 ¶、下一帧才上色或消失。
     *
     * 每次重组现取；只在宿主真的处于审阅空间且文字已套上修订时才有值，正常视图恒空。
     */
    val reviewMarksFor: (Int) -> List<ReviewMark> = { ch -> currentHost.reviewMarks(ch) }
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
    // 闸规则：`externalPos` / 序号与上次**已结算**的推送不等 ⇒ 在途 ⇒ 显示侧继续用推送前那一个
    // 版本号（旧页位图原样命中、画面不动）；漏斗 push 一结束（commit 或其它推送的 BUSY-DROP）
    // 立即开闸，位置与新版本号同帧下发，只发生一次重栅格。
    var settledExternalPos by remember { mutableStateOf<ReaderPos?>(null) }
    var settledExternalEpoch by remember { mutableIntStateOf(externalEpoch) }
    var heldRevision by remember { mutableIntStateOf(effectiveRevision) }
    val externalInFlight = externalPos != null &&
        (externalPos != settledExternalPos || externalEpoch != settledExternalEpoch)
    // 不在途时随手跟上最新（同值不触发重组，且不在途分支不读它 ⇒ 无额外重组）；在途时不动。
    SideEffect { if (!externalInFlight) heldRevision = effectiveRevision }
    val displayRevision = if (externalInFlight) heldRevision else effectiveRevision

    // Q1-b：外部推送落位（键 = externalPos + 序号；同值重复推送也要重新认领）。
    // 经锚页漏斗：与在途导航互斥。外部落位是用户明确的目的地，**不丢**——漏斗被翻页/开书
    // 占着时排队等它结束再提交（新推送到来时本 effect 被取消，由最新值接管）。
    LaunchedEffect(externalPos, externalEpoch) {
        val pos = externalPos
        val epoch = externalEpoch
        try {
            if (pos != null) {
                anchorFunnel.push("external", ::markPositionChanged, wait = true) {
                    openFailed = false
                    pos
                }
            }
        } finally {
            // 结算即开闸：位置与版本号同帧下发，显示只重栅格一次。
            settledExternalPos = pos
            settledExternalEpoch = epoch
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
            val tNav = System.nanoTime()
            val landed = currentHost.adjacent(p, direction)
            // 落位本身（引擎：章内换页/跨章开章+整形）与 beforeCommit（等图+整页栅格）分开记，
            // 二者相加即 FLIPLAT target-ready 的主体（见 FlipController.logLat）。
            Logger.d(
                "Orilumn.TAP",
                "FLIPLAT nav dir=$direction ms=${(System.nanoTime() - tNav) / 1_000_000} " +
                    "to=${landed?.let { "ch=${it.chapter} char=${it.slice.charStart}" } ?: "NULL"}",
            )
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
        settleScale = flipSettleScale,
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
    fun beginSlide(direction: Int, downNanos: Long = 0L) {
        val src = openPos ?: return
        // 后退回封面：目标就绪即画（封面已解码），无需落位；前进离开封面仍需落位书首。
        val enterCover = !coverVisible && direction < 0 && isBookStart(src)
        ctl.beginDrag(src, direction, slideEnabled, coverVisible, enterCover, downNanos)
    }

    /** 拖动中：喂位移。目标页未就绪时只喂状态机、不写回渲染（避免拉出空白页）。 */
    fun updateSlide(dx: Float, pageW: Float) = ctl.updateDrag(dx, pageW, slideEnabled)

    /**
     * 拖动中：喂卷曲手感的**起点与终点**（手指此刻的绝对页坐标）。
     *
     * 只给一条入口：折痕角度与折痕位置是同一个事实的两面——都从「手指相对起手点的位移」
     * 反解（角度 = 拖动方向的垂线；位置看**起手在屏幕哪半边**：右半边纸边压手指、左半边
     * 折痕穿手指，见 `curl/CurlPull` / `curl/leanOf` / `curl/anchoredFoldX` / `curl/creaseFoldX`）。
     * 滑动层不看它。
     */
    fun updateCurlPull(
        downX: Float,
        downY: Float,
        x: Float,
        y: Float,
        pageW: Float,
        pageH: Float,
    ) {
        if (slideEnabled) ctl.updateCurlPull(downX, downY, x, y, pageW, pageH)
    }

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
    LaunchedEffect(openPos, hostRevision, effectiveRevision) {
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

    /**
     * 起选：把 (xPx, yPx) 处那个字变成选区锚点，返回该字在章内的偏移（`null` = 此处没有可选的章文本）。
     *
     * [selectUnit] 见 [TextSelectionState.begin]：长按入口按 D-7 展开整词 / 整句，
     * 桌面鼠标拖选只要按下那一个字。
     */
    fun beginSelectionAt(xPx: Float, yPx: Float, selectUnit: Boolean = true): Int? {
        closeAnnotationEdit()
        val p = openPos ?: return null
        val offset = charOffsetAt(p, xPx, yPx) ?: return null
        val text = currentHost.textOf(p.chapter, 0, Int.MAX_VALUE) ?: return null
        val ok = selection.begin(
            chapter = p.chapter,
            text = text,
            offset = offset,
            blockEnds = currentHost.textBlockEndsOf(p.chapter),
            selectUnit = selectUnit,
        )
        return if (ok) offset else null
    }

    /**
     * 手指落在正文左右边距里时的接续偏移：越过右缘取该行末字、越过左缘取行首字。
     *
     * 上下超出正文时不接管（仍按原来的 miss 处理）：手指划出页面上下不该一下选中整页。
     */
    fun marginSideOffsetAt(p: ReaderPos, xPx: Float, yPx: Float, widthPx: Float): Int? {
        val left = profile.marginLeft.toFloat()
        val right = (widthPx - profile.marginRight).coerceAtLeast(left)
        val direction = when {
            xPx > right -> 1
            xPx < left -> -1
            else -> return null
        }
        return charOffsetAtPageSide(p, yPx, direction)
    }

    /**
     * 拖动手柄 / 长按后移动：焦点按字符走。
     *
     * 手指落到正文左右边距里时，[charOffsetAt] 会 miss（`LineHitTest` 的口径是「行尾空区不算命中」），
     * 于是选区冻在上一个字、直到自动翻页一次性跳过去——读者看到的是「选区不跟手，然后突然多一页」。
     * 这里补一条拖选专用出口：先贴到该行靠边的字，再按触发带翻页。
     */
    fun extendSelectionAt(xPx: Float, yPx: Float, widthPx: Float) {
        val p = openPos ?: return
        val offset = charOffsetAt(p, xPx, yPx) ?: marginSideOffsetAt(p, xPx, yPx, widthPx) ?: return
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
     * @return true = 本次**已经翻过页**（不必再试，哪怕选区没接上）；false = 没翻成
     *   （到章边界 / 邻页未就绪 / 导航被 BUSY-DROP），调用方按重试间隔再试。
     *
     * 注意别把「翻成了但选区没接上」报成 false：调用方的重试循环会把它当成没翻，
     * 隔 200ms 再翻一页，读者一次拖进页边就看到跳了两三页。
     */
    suspend fun autoFlipSelection(direction: Int, fingerY: Float): Boolean {
        val p = openPos ?: return false
        if (selection.chapter != p.chapter) return false
        val peek = runCatching { currentHost.peekAdjacent(p, direction) }.getOrNull() ?: return false
        if (peek.chapter != p.chapter) return false
        val landed = flipAwait(direction, action = "selection-auto-flip") ?: return false
        // 已经翻过去了：从这里开始无论接力成不成都要停（见上面的返回值说明）。
        if (landed.chapter != p.chapter) return true
        val offset = charOffsetAtPageSide(landed, fingerY, direction) ?: return true
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

    /**
     * 按**当前显示文字**开一次普通修改卡：[s, e) 是当前文字空间的选区。
     *
     * 输入区与目标区间同进同退（`textEditStart/End` = 卡里那段文字的范围）：卡里放的一律是
     * 「书里现在这几段长什么样」，不走审阅视图的「原文 + 新文字」对照。
     *
     * 从 `setTextEditTarget` 抽出来，是为了让**重开旧修订**那条路也能用它：只加了换行的修订
     * 在最小修订里只剩那个换行本身，得扩到所在整块再按这一段开卡（见 `openRevisionEdit`）。
     */
    fun openTextEditAt(ch: Int, s: Int, e: Int): Boolean {
        val text = currentHost.textOf(ch, 0, Int.MAX_VALUE) ?: return false
        // 卡片里给人看的是书里的字：审阅视图的「段界痕迹」¶ 一并摘掉（偏移自己回缩到同一口径），
        // 否则确认时会把 ¶ 当成字写进正文。分页 / 选区 / 修订换算仍走原样文字。
        val brk = currentHost.reviewBreakText(ch)
        val body = brk.plainChapterText(text)
        val rawBlockEnds = currentHost.textBlockEndsOf(ch)
        val blockEnds = brk.plainBlockEnds(rawBlockEnds)
        // 表格行整行呈现：格偏移是按**整行文字**算的，选区只碰到半行时，片段文字与格界对不上
        // （管道行会错位）。所以卡片与提交区间只扩到**碰到的那几行**的整行 —— 读者选两行就只
        // 列两行，不把整张表都塞进卡片。删行 / 加行 / 删表 / 增删列都放行：提交侧把未选中的
        // 行一起拼回**整张表**，按网格自洽校验后再落一条整表修订。
        // 整块图片只有一个占位字符，不存在半块选中，不扩。
        var from = s
        var to = e
        var blockInfo = currentHost.textEditBlocks(ch, from, to)?.takeIf { it.isNotEmpty() }
        while (blockInfo != null) {
            val grown = expandTableRowsForEdit(blockInfo, from, to)
            if (grown.first == from && grown.last + 1 == to) break
            from = grown.first
            to = grown.last + 1
            blockInfo = currentHost.textEditBlocks(ch, from, to)?.takeIf { it.isNotEmpty() }
        }
        textEditChapter = ch
        textEditStart = from
        textEditEnd = to
        // 逐块取文字（块内 `<br>` / 代码块换行原样留在块里，不当段界）。
        val pieces = selectionPiecesForEdit(body, blockEnds, brk.plain(from), brk.plain(to))
        val plain = pieces.joinToString("\n")
        // 选区所在的**段**（渲染空间）：提交时按它取同段的行内结构当模板。
        // 取整段而不是只取选区：读者新写 `*新*` 要长得像同段别的斜体，那些斜体常常在选区之外。
        textEditParagraphRange = paragraphRangeOf(rawBlockEnds, text.length, from, to)
        // 选区上的行内元素摊成标记文字（`*斜体*`、`[文字](地址)`）：看得见、也改得动。
        // 宿主给不出结构（尚未就绪 / 非盒式排版）就按纯文本，行为与引入标记之前一致。
        // 宿主给的是**相对选区起点**的区间，先换成章文本绝对偏移；审阅视图另有 ¶ 痕迹，
        // 一并按同一把尺剥掉，输入区才与正文逐字对得上。
        val rawSpans = currentHost.textEditSpans(ch, from, to)
        val spans = rawSpans?.let { list ->
            spansToEditText(
                spans = list.map {
                    InlineMarkup.StyleSpan(
                        brk.plain(from + it.start),
                        brk.plain(from + it.endExclusive),
                        it.sig,
                    )
                },
                blockEnds = blockEnds,
                start = brk.plain(from),
                endExclusive = brk.plain(to),
            )
        }
        // 整段都包着选区的元素不算「读者要改的结构」：选区完全在元素内部时，标记只是噪音
        // （读者看不见边界，写了反而会把元素挪位或截断）。这类元素单独记下，提交时原样套回
        // 整段；只有**边界切在选区里**的元素才摊成标记给读者看。
        // 例外：外层元素是局部的，内层就不能隐形——否则补回时会落到外层之外。
        val (ambient, visible) = splitAmbientSpans(spans.orEmpty(), plain.length)
        // 这一行本身就是书里那个行内元素（`display:block` 的 `span` 之类）：整行包一层标签。
        // 它永远显示（不走「整段覆盖就隐形」那条：它天生就覆盖整行），也只活在卡片里。
        val ownTags = blockInfo?.map { it.ownTag }
        val own = ownTagSpans(pieces, ownTags)
        // 块级形态：宿主按排版叶给出每块是哪一种（标题 / 引用 / 列表 / 代码块 / 段落），
        // 卡片把它写成行首标记；拿不到就整套按纯文本（不改任何块的形态）。
        textEditBlocksKnown = blockInfo != null
        textEditFirstLineNoKind = blockInfo != null && (
            blockInfo.firstOrNull()?.startsAtBlockStart == false ||
                blockInfo.firstOrNull()?.kind == null
            )
        val segments = pieces.mapIndexed { i, piece ->
            val info = blockInfo?.getOrNull(i)
            // 第一行从段中间开始时**不写标记**：那里的「没有标记」不代表普通段落，是「改不了」。
            InlineMarkup.Segment(
                text = piece,
                block = if (info?.startsAtBlockStart == false) null else info?.kind,
                // 表格行按格结构排成管道行 / 基本 HTML 行；盖不住这份文字就按纯文本（不猜格界）。
                table = info?.table?.takeIf { it.covers(piece) },
            )
        }
        textEditBaseText = plain
        textEditTables = blockInfo?.map { it.table } ?: emptyList()
        textEditImages = blockInfo?.map { it.image } ?: emptyList()
        // 这次选中的正好是一整张表（每一块都是它的行，表首 / 表尾就是选区两头）：
        // 行数、每行列数都允许改，提交前按网格自洽校验。
        textEditWholeTable = blockInfo != null && pieces.isNotEmpty() &&
            blockInfo.size == pieces.size &&
            blockInfo.all { it.table != null && it.tableRange?.first == from && it.tableRange?.last == to - 1 }
        // 新选区：不是「重开整表修订里的某几行」，行范围清掉。
        textEditRowScope = null
        textEditTableRange = null
        // 原件仍记**完整**结构：no-op 比较和属性补回都要拿它当模板。
        textEditOriginalSpans = spans
        textEditAmbientSpans = ambient
        // 行内元素本人那层包装不算结构（与块标记同待遇）：提交时摘掉，不参与 no-op 比较。
        textEditOwnTags = ownTags?.takeIf { own.isNotEmpty() }
        // 书是 EPUB，不是 markdown：原文里的 `*` `~` `[` `<` `&` 都是**普通文字**。输入区是
        // markdown 编辑区，所以**永远**走一遍序列化把标记字符转义（`*` → `\*`），不然读者一确认
        // 就会被解析成粗体 / 斜体、标签或实体，原文被悄悄改掉。没有行内结构时也一样。
        // 确认时 [InlineMarkup.parse] 把反斜杠去掉，落进书里的仍是原来那几个字。
        textEditText = InlineMarkup.show(segments, visible + own)
        textEditBaseCard = textEditText
        textEditFromRevision = false
        return true
    }

    /**
     * 打开一条已有修订的修改卡：输入区带出**这条修订改完的整段内容**（不是审阅视图里的
     * 「原文 + 新文字」对照），选区同步框住整段。点按红绿字与长按选中改过的字都走这里。
     *
     * 目标区间始终是这条修订在当前显示空间里的整段（`chapterRevisions` 已换算好），
     * 提交时再换回原书空间——读者改的仍是原书那一段，不是把改后的字当成新底稿。
     *
     * 例外：这条修订只加了换行（最小修订里只剩那个换行本身，见 [revisionNeedsBlockContext]）时，
     * 卡片改按**所在整块**开——只按那个换行开，输入区里一个能看见的字都没有。
     *
     * @param select 是否把选区改成这条修订的整段。`false` = 只开卡片、不动选区（审阅视图里
     *   读者拖选改过的表格中的某几行：拖了哪一行就框住哪一行）。
     * @param rows 非空 = 只改整表修订里的这几行：卡片只列这几行，提交时拼回整张表。
     */
    fun openRevisionEdit(
        revision: TextRevision,
        select: Boolean = true,
        rows: TableRowScope? = null,
    ): Boolean {
        val p = openPos ?: return false
        // 只加了换行的修订：最小修订里只剩那个换行本身，按它开卡输入区里一个能看见的字都没有
        // （只剩行尾一个 `\`），读者看不出换行落在哪两个词之间。这类修订扩到所在**整块**，
        // 卡片文字与目标区间用同一段当前文字（直接确定就是恒等，两种视图都验过）。
        val blockRange = if (rows == null && revisionNeedsBlockContext(revision)) {
            currentHost.textOf(p.chapter, 0, Int.MAX_VALUE)?.let { text ->
                paragraphRangeOf(
                    currentHost.textBlockEndsOf(p.chapter),
                    text.length,
                    revision.charStart,
                    revision.charEnd,
                )
            }
        } else {
            null
        }
        if (select) {
            val text = currentHost.textOf(p.chapter, 0, Int.MAX_VALUE) ?: return false
            val s = blockRange?.first ?: revision.charStart
            val e = blockRange?.last?.plus(1) ?: revision.charEnd
            if (!selection.selectRange(p.chapter, text, s, e)) {
                selection.clear()
            }
        }
        closeAnnotationEdit()
        magnifierFinger = null
        noteText = ""
        if (blockRange != null && openTextEditAt(p.chapter, blockRange.first, blockRange.last + 1)) {
            // 卡里已是「改完的整段」：落笔前不许用手柄把它换回原文，提交时也按重开旧修订算。
            textEditDirty = true
            textEditFromRevision = true
            textEditOpen = true
            textEditBusy = false
            textEditError = null
            selectionMenuVisible = false
            return true
        }
        textEditChapter = p.chapter
        textEditStart = revision.charStart
        textEditEnd = revision.charEnd
        // 行范围卡片：只列读者拖到的那几行（改完的那张表里的对应行），提交时再拼回整张表。
        val rowCard = rows?.let { tableRowCardOf(revision, it) }
        // 重开旧修订：这一行如果本身就是书里那个行内元素（`display:block` 的 `span` 之类），
        // 标签也要带出来——第一次开卡看得见，重开时不能凭空消失。只在区间正好盖住整叶、行数也
        // 对得上时才用：半截文字的「整行包装」会把元素画到错的地方（那种卡片照旧不带标签）。
        val ownTags = if (rowCard == null && revision.editedBlocks.none { it.startsWith("t") || it.startsWith("T") }) {
            val lines = RevisionBlocks.split(revision.text).size
            val blocks = currentHost.textEditBlocks(p.chapter, revision.charStart, revision.charEnd)
            blocks?.takeIf {
                it.size == lines &&
                    it.first().start == revision.charStart &&
                    it.last().endExclusive == revision.charEnd
            }?.map { b -> b.ownTag }
        } else {
            null
        }
        val rc = if (rowCard != null) {
            RevisionCard(editCardOf(rowCard.text, rowCard.blocks, rowCard.styles), rowCard.text)
        } else {
            // 书里这一段**原来**的行内结构：只改标记的修订靠它区分「读者新写的」与「本来就有的」，
            // 与审阅视图的痕迹同一份来源（见 `FormatTraces`）。取不到就按纯文本开卡，不猜。
            revisionCardOf(
                revision,
                currentHost.textRevisionOriginalSpans(p.chapter, revision.id),
                ownTags,
            )
        }
        textEditText = rc.card.text
        textEditOriginalSpans = rc.card.originalSpans
        textEditAmbientSpans = rc.card.ambientSpans
        textEditOwnTags = ownTags?.takeIf { list -> list.any { it != null } }
        textEditBaseText = rc.plain
        textEditBaseCard = rc.card.text
        val tableLike = revision.editedBlocks.any { it.startsWith("t") || it.startsWith("T") }
        // 打开旧修改时按落库的每行短码还原表格 / 图片形状（提交时仍按它校验结构没被改掉）。
        val shapeSegments = if (tableLike) {
            RevisionBlocks.cardSegments(revision.editedText, revision.editedBlocks)
        } else {
            null
        }
        textEditTables = if (rowCard != null) rowCard.tables else shapeSegments?.map { it.table } ?: emptyList()
        // 整表结构修订（首行短码大写 `T`）：这张卡覆盖的就是整张表，行 / 列都可以改。
        // 行范围卡片也按整表算（结构改了仍按整张表校验网格）。
        textEditWholeTable = revision.editedBlocks.firstOrNull()?.startsWith("T") == true
        textEditRowScope = if (rowCard != null) rows else null
        textEditTableRange = null
        // 老修订的 `editedText` 分不出「整块图片」与「整段只有一个行内图的段落」，别靠猜：
        // 图片限制只在当次打开卡片、宿主明确给了叶类型时生效。
        textEditImages = emptyList()
        // 旧修订的每行形态来自它自己落库的那份：第一行能不能写标记，看它的首行是不是块首
        // （落库时按块首对齐过，所以只要第一行有形态就说明它是块首）。
        textEditBlocksKnown = if (tableLike) {
            revision.editedBlocks.isNotEmpty()
        } else {
            revision.blockKinds.isNotEmpty()
        }
        // 落库的第一行没带形态（`""`）= 当时那一行改不了块形态（半截文字 / 不归 markdown 管）。
        textEditFirstLineNoKind = !tableLike && revision.blockKinds.firstOrNull()?.isEmpty() == true
        // 输入区带出来的是**已经改过的字**，按「已动过」算：之后拖手柄只挪落笔范围，
        // 不会把读者上次的改写换成新选区的原文（那等于悄悄丢掉上一次修改）。
        textEditDirty = true
        textEditFromRevision = true
        textEditError = null
        textEditOpen = true
        textEditBusy = false
        selectionMenuVisible = false
        return true
    }

    /**
     * 点按改动过的文字（审阅视图里是红字 / 绿字，普通阅读视图里就是改后的正文）：
     * 打开正文修改卡、弹出键盘（不用先长按再点「修订」）。
     *
     * 命中口径与长按标注同一套：只看本章内存修订表（`chapterRevisions` 已是**当前显示空间**），
     * 按字符区间判，不做几何反查；页边距上按不到行，自然也不会误开。
     *
     * 命中范围是这条修订的**整段**（原文 + 新文字），不是只认画出来的红绿字——段里保留下来的
     * 那些字也属于这条修订，点它同样算命中，落到同一条修订上。
     *
     * 命中后选区恢复成这条修订的**整段**（当前空间），输入区带出改后的文字——
     * 点红字或点绿字落到的是同一条修订，改的就是它（与 D-36「改过 = 改后的内容」同口径）。
     *
     * **表格例外**：整表修订（首码大写 `T`）只在**审阅视图**里点得开——只认**真正改动过的字**
     * （红字 / 绿字）：按点到的行开行卡片，选区框住点的那一行（点的是删掉的行就框住那排红字）；
     * 点在表格其他区域（没改过的格、行间空白）不开修订卡，两侧吞掉这次点按（不翻页 / 切栏），
     * **屏幕中部照旧交给上下栏的显隐**。普通阅读视图里正文就是改后的表、没有红绿字可认，点它
     * 一律不进修订卡——卡片会带出整张表，正文却框不出任何一段，读者不知道自己改的是哪儿；
     * 要改这张表就走长按起选再点「修订」，那一路选区与卡片同进同退。
     *
     * @return true = 已经打开修改卡（本次点按不再翻页 / 开链接 / 切栏），或点按落在了改过的表格里
     *   （普通阅读视图除外：那里不进卡，返回 false，按普通正文处理）
     */
    fun tryOpenReviewEditAt(xPx: Float, yPx: Float, widthPx: Float): Boolean {
        val p = openPos ?: return false
        if (chapterRevisions.isEmpty()) {
            Logger.d("Orilumn.TAP", "review tap skip: revisions not loaded yet ch=${p.chapter}")
            return false
        }
        val offset = charOffsetAt(p, xPx, yPx) ?: run {
            Logger.d("Orilumn.TAP", "review tap skip: no char under tap (margin / line gap)")
            return false
        }
        // 判「在不在审阅空间」看宿主**实际**换没换空间，不看 UI 开关：两者不是同一拍
        // （切视图要重排），按开关取会在切换中途拿错尺。痕迹与修订区间都跟着宿主的空间走。
        val reviewSpace = currentHost.isReviewMode()
        // 审阅视图里本章有修订、但正文没套上审阅空间时一个痕迹都不画，这时按审阅空间的区间命中
        // 会框错字，宁可不开。普通视图没有这个问题：修订区间本来就是渲染空间的。
        if (reviewSpace && reviewMarksFor(p.chapter).isEmpty()) {
            Logger.d("Orilumn.TAP", "review tap skip: no marks in review space ch=${p.chapter}")
            return false
        }
        // 命中口径是**整条修订**（原文 + 新文字），不是只认画出来的那几个红绿字：段里保留下来的
        // 那些字也属于这条修订，点它同样开这条修订的卡（红绿只是标出真正改动的位置）。
        val revision = chapterRevisions.firstOrNull { r ->
            offset >= r.charStart && offset < r.charEnd
        } ?: run {
            Logger.d(
                "Orilumn.TAP",
                "review hit miss offset=$offset ranges=" +
                    chapterRevisions.joinToString(",") { "${it.charStart}-${it.charEnd}" },
            )
            return false
        }
        // 表格上压着整表修订（首码大写 `T`）时走单独一套判据（见 reviewTableTapOf）：
        // 审阅视图里点改过的那一行 = 开修订卡、开行卡片，选区框住那一行；点一行都没改过的区域
        // 不开修订卡（两侧吞掉不翻页，中部让给上下栏）；普通视图里点表格不进修订卡（按普通正文）。
        // 见 docs/正文修改-块级标记.md §8 / 验收 30 / 36 / 37。
        if (revision.editedBlocks.firstOrNull()?.startsWith("T") == true) {
            // 点到的行块（当前显示空间）：行卡片的选框就是它，行间空白 / 表外拿不到。
            val row = currentHost.textEditBlocks(p.chapter, offset, offset + 1)
                ?.firstOrNull()
                ?.let { it.start until it.endExclusive }
            val tap = reviewTableTapOf(
                reviewSpace = reviewSpace,
                rowOf = { row },
                marksOf = { reviewMarksFor(p.chapter) },
                scopeOf = { currentHost.tableRowScope(p.chapter, revision.id, offset, offset + 1) },
            )
            val middle = ReaderMath.tapZone(xPx, widthPx) == 0
            when (tap) {
                ReviewTableTap.PassThrough -> Logger.d(
                    "Orilumn.TAP",
                    "tap offset=$offset in revised table (normal view) → no revision card",
                )
                ReviewTableTap.Ignore -> Logger.d(
                    "Orilumn.TAP",
                    "tap offset=$offset in revised table (unchanged row) → " +
                        if (middle) "pass through (bars)" else "ignored",
                )
                ReviewTableTap.WholeTable -> {
                    Logger.d("Orilumn.TAP", "tap offset=$offset in revised table → whole-table card")
                    // 整表卡片：选区就是这条修订的整段（卡片带走的就是它），开不出来就别留选区。
                    if (!openRevisionEdit(revision)) selection.clear()
                }
                is ReviewTableTap.Row -> {
                    Logger.d("Orilumn.TAP", "tap offset=$offset in revised table → row ${tap.scope.firstRow} card")
                    // 正文里框住读者点的那一行：卡片可能空白（点的是删掉的行——这一行在改完的表里
                    // 已经没有了），正文不能也跟着空着，否则读者看不出自己正在改哪儿。
                    if (row != null) {
                        val text = currentHost.textOf(p.chapter, 0, Int.MAX_VALUE)
                        if (text == null ||
                            !selection.selectRange(p.chapter, text, row.first, row.last + 1)
                        ) {
                            selection.clear()
                        }
                    } else {
                        // 拿不到行块（行间空白 / 表外）：没有能框的整行，别留上一段旧选区。
                        selection.clear()
                    }
                    if (!openRevisionEdit(revision, select = false, rows = tap.scope)) selection.clear()
                }
            }
            return reviewTableTapConsumed(tap, middle)
        }
        return openRevisionEdit(revision)
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
        // 正文修改卡片开着时同理：点卡片 / 键盘以外的正文 = 取消本次修改（与批注一致）。
        // 这里是手势层没接住时的兜底；`cancelTextEdit` 定义在后面，局部函数不能前向引用，故就地展开。
        if (textEditOpen) {
            focusManager.clearFocus()
            textEditOpen = false
            textEditText = ""
            textEditDirty = false
            textEditBusy = false
            if (selection.hasSelection) selection.clear()
            return
        }
        // 编辑菜单开着时，正文点按只关菜单（卡片开着时点按已由手势层接住，这里是兜底）。
        // 语义与 [cancelNote] 一致：写了一半的内容丢掉、原记录不动、编辑时恢复出来的选区收掉。
        if (editingAnnotation != null) {
            focusManager.clearFocus()
            noteText = ""
            notePickedColors.clear()
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
        // 点按**改过的文字**直接开卡改它（审阅视图的红绿字、普通视图里改后的正文都算）。
        // 放在标注之后：同一段字上既有标注又有修订时先按老规矩开标注卡；要改这段字可长按起选再点「修订」。
        if (tryOpenReviewEditAt(xPx, yPx, widthPx)) {
            Logger.d("Orilumn.TAP", "tap x=${xPx.roundToInt()} y=${yPx.roundToInt()} → revision edit card")
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
            // 左 / 中 / 右三等分（ReaderMath.tapZone）：左右 1/3 翻页，中部切上下栏。
            // 桌面鼠标单击与触摸同一套语义（2026-11-20 恢复桌面点击翻页）。
            -1 -> flipWithAnimation(-1)
            1 -> flipWithAnimation(1)
            else -> barsVisible = !barsVisible
        }
    }

    /**
     * 进目录/笔记/修订/设置面板：点下就开面板，工具栏同时在面板遮罩后退场。
     *
     * 以前是「先收栏、等 BarsAnim 走完再开面板」，读者会先看到约 240ms 的「点了没反应」，
     * 抽屉才开始滑入；现在两步同一帧发起，面板不会盖在晾着的工具栏上（抽屉在栏之上），
     * 而清单里点一条跳转时 `barsVisible` 早已是 false，落到的仍是干净的一页。
     */
    fun hideBarsThen(action: () -> Unit) {
        barsVisible = false
        action()
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
    val currentNoteBarOpen by rememberUpdatedState(selectionMenuVisible || editingAnnotation != null || textEditOpen)
    LaunchedEffect(openPos, barsVisible, keysEnabled) {
        if (keysEnabled) focusRequester.requestFocus()
    }
    // 卡片（批注输入条 / 正文修改卡）关掉后把焦点还给正文：输入框卸载时焦点随之消失，
    // 不还回来就得先点一下正文，方向键才会恢复翻页（桌面习惯键盘的人会以为「卡死了」）。
    // 键取「有没有卡片开着」整体，而不是逐个开关：正文修改卡关掉时 selectionMenuVisible /
    // editingAnnotation 都不变，早先正好漏在这一条上——点屏幕中部退出选区，左右键就再也不响应。
    LaunchedEffect(currentNoteBarOpen, keysEnabled) {
        if (!currentNoteBarOpen && keysEnabled) focusRequester.requestFocus()
    }

    // L1 滑动的引用包装（必须在上面几个局部函数声明之后——Kotlin 局部函数不可前向引用）。
    val currentSlideEnabled by rememberUpdatedState(slideEnabled)
    val currentBeginSlide by rememberUpdatedState<(Int, Long) -> Unit> { d, down -> beginSlide(d, down) }
    val currentUpdateSlide by rememberUpdatedState<(Float, Float) -> Unit> { dx, w -> updateSlide(dx, w) }
    val currentUpdateCurlPull by rememberUpdatedState<(Float, Float, Float, Float, Float, Float) -> Unit> {
            downX, downY, x, y, w, h ->
        updateCurlPull(downX, downY, x, y, w, h)
    }
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
        // 摘录是给人看的：审阅视图的「段界痕迹」¶ 与中西接缝上被画成零宽的空白都要摘掉
        // （原文与下标一个不动，只在「带走文字」这一口处理）。
        val quote = currentHost.textOf(ch, 0, Int.MAX_VALUE)
            ?.let { currentHost.plainTextOf(ch).slice(it, s, e).take(200) }
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
        val kind = noteKind
        // 只认**本类别**刚点的那一格：切类别后，上一类的颜色绝不能当这一类的墨色 / 底色落笔。
        val pickedByKind = notePickedColors.toMap()
        val picked = pickedByKind[kind]
        val style = noteLineStyle
        noteText = ""
        notePickedColors.clear()
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
            // 范围变了就同步摘录：清单里那一行显示的是新选中的原文（同样摘掉 ¶ 痕迹与接缝空白）。
            val quote = if (rangeChanged) {
                currentHost.textOf(current.chapter, 0, Int.MAX_VALUE)
                    ?.let { currentHost.plainTextOf(current.chapter).slice(it, newStart, newEnd).take(200) }
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
            color = annotationCommitColor(light, kind, pickedByKind),
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
        notePickedColors.clear()
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
     * 审阅视图里选中了「改过的字 + 别的内容」：卡片按**当前结果**拼出整段，而不是审阅视图的
     * 「原文 + 新文字」对照。每条修订取它改完的整段（普通文字取 `text`、表格取 `editedText`），
     * 修订之间的原文照抄；提交时仍按原书区间重算，旧修订被新记录吸收（层叠后只剩最终结果）。
     *
     * 表格和表格外的文字一起选时，卡片把两样都列出来：表格只列读者拖到的那几行（[selectedStart],
     * [selectedEnd] 是读者真正拖到的区间，卡片扩到整条修订后仍按它挑行），其余文字照抄。提交时
     * 按段切开（[textEditTableRange] 那一段走行范围 / 整表，别的走普通区间），一次落库。
     *
     * @return true = 已把卡片目标设成 [from]..[to]（当前空间）
     */
    fun setReviewCurrentTarget(
        ch: Int,
        from: Int,
        to: Int,
        touched: List<TextRevision>,
        selectedStart: Int = from,
        selectedEnd: Int = to,
    ): Boolean {
        val text = currentHost.textOf(ch, 0, Int.MAX_VALUE) ?: return false
        val brk = currentHost.reviewBreakText(ch)
        val segments = ArrayList<InlineMarkup.Segment>(touched.size + 2)
        val spans = ArrayList<InlineMarkup.StyleSpan>(touched.size * 2)
        // 逐行「这一行本身就是书里那个行内元素」的标签（与 [segments] 等长）与它的整行包装。
        val ownTags = ArrayList<String?>(touched.size + 2)
        val ownSpans = ArrayList<InlineMarkup.StyleSpan>(2)
        var firstStartsAtBlock = true
        var pos = from
        var at = 0
        // 卡片里真正装着的字覆盖的范围：普通块只到读者选中的那一段（不把块首 / 块尾多带进来），
        // 表格行整行进卡、范围跟着扩到整行。**卡片里的字必须与这个区间逐字对齐**，
        // 否则确定时会把多带进来的那几个字当作新文字再插一遍（正文就多出一截）。
        var cardFrom = from
        var cardTo = to
        // 选中的那张表（整表修订）在当前显示空间的区间 + 读者拖到的那几行；
        // 两个都记下来，提交时表格那段按行范围走、文字那段按普通区间走（见 `TextEditPart`）。
        var tableRange: IntRange? = null
        var tableScope: TableRowScope? = null
        fun appendPiece(
            piece: String,
            block: BlockKind?,
            rawSpans: List<InlineMarkup.StyleSpan>,
            reviewStart: Int,
            table: TableRowInfo? = null,
            ownTag: String? = null,
        ) {
            val base = brk.plain(reviewStart)
            for (sp in rawSpans) {
                val s = brk.plain(reviewStart + sp.start) - base
                val e = brk.plain(reviewStart + sp.endExclusive) - base
                if (e > s) spans += InlineMarkup.StyleSpan(at + s, at + e, sp.sig)
            }
            segments += InlineMarkup.Segment(piece, block, table)
            // 这一行本身就是书里那个行内元素（`display:block` 的 `span` 之类）：整行包一层标签，
            // 与普通卡片同一口径（见 `ownTagSpans`）。它只给显示，提交时摘掉。
            if (ownTag != null && piece.isNotEmpty()) {
                ownTags += ownTag
                ownSpans += InlineMarkup.StyleSpan(at, at + piece.length, InlineMarkup.StyleSig(ownTag))
            } else {
                ownTags += null
            }
            at += piece.length + 1
        }
        fun appendGap(start: Int, endExclusive: Int) {
            val blocks = currentHost.textEditBlocks(ch, start, endExclusive).orEmpty()
            if (blocks.isEmpty()) {
                appendPiece(
                    brk.slice(text, start, endExclusive),
                    null,
                    currentHost.textEditSpans(ch, start, endExclusive).orEmpty(),
                    start,
                )
            } else {
                if (segments.isEmpty() && blocks.first().startsAtBlockStart == false) {
                    firstStartsAtBlock = false
                }
                for (b in blocks) {
                    // 表格行整行进卡（格偏移按整行文字算），卡片范围跟着扩到整行；
                    // 普通块只切**选中的那一段**：块首 / 块尾多带进来的字，区间里没有，
                    // 确定时会被当成新写的字再插一遍。
                    val range = clipBlockForCard(b, start, endExclusive) ?: continue
                    val bStart = range.first
                    val bEnd = range.last + 1
                    if (b.table != null) {
                        if (bStart < cardFrom) cardFrom = bStart
                        if (bEnd > cardTo) cardTo = bEnd
                    }
                    if (segments.isEmpty() && bStart > b.start) firstStartsAtBlock = false
                    appendPiece(
                        brk.slice(text, bStart, bEnd),
                        if (bStart > b.start || b.startsAtBlockStart == false) null else b.kind,
                        currentHost.textEditSpans(ch, bStart, bEnd).orEmpty(),
                        bStart,
                        ownTag = b.ownTag,
                    )
                }
            }
        }
        for (r in touched.sortedBy { it.charStart }) {
            val rs = maxOf(from, r.charStart)
            val re = minOf(to, r.charEnd)
            if (rs > pos) appendGap(pos, rs)
            if (r.editedBlocks.firstOrNull()?.startsWith("T") == true) {
                // 整表修订：卡片只列读者拖到的那几行（上次改完那张表里的行），表里其余行原样不动。
                // 拖到的行全被删掉了（插入位置）就一行都不列，卡片里只剩表格外那些文字。
                val scope = if (selectedStart < selectedEnd) {
                    currentHost.tableRowScope(
                        ch,
                        r.id,
                        maxOf(selectedStart, r.charStart),
                        minOf(selectedEnd, r.charEnd),
                    )
                } else {
                    null
                }
                val rows = tableRowsOfRevision(r)
                    ?: RevisionBlocks.cardSegments(r.editedText, r.editedBlocks).orEmpty()
                val lo = (scope?.firstRow ?: 0).coerceIn(0, rows.size)
                val hi = ((scope?.lastRow ?: (rows.size - 1)) + 1).coerceIn(lo, rows.size)
                if (hi > lo) {
                    tableRange = r.charStart until r.charEnd
                    tableScope = scope
                    val revAt = at
                    // 整表卡片带出整张表的结构；行范围卡片只带那几行，行内结构按整卡平移过来。
                    val stored = if (scope != null) {
                        tableRowCardOf(r, scope)?.styles
                    } else {
                        r.editedStyles
                    }
                    stored?.let { list ->
                        for (sp in InlineMarkup.fromStored(list)) {
                            val s = revAt + sp.start
                            val e = revAt + sp.endExclusive
                            if (e > s) spans += InlineMarkup.StyleSpan(s, e, sp.sig)
                        }
                    }
                    for (i in lo until hi) {
                        appendPiece(rows[i].text, null, emptyList(), r.charStart, rows[i].table)
                    }
                }
            } else {
                // 这条修订改完的整段：普通文字按段界切开。
                val parts = RevisionBlocks.split(r.text)
                val kinds = r.blockKinds
                // 这一段里有没有「整行就是书里那个行内元素」的叶（`display:block` 的 span 之类）：
                // 行数与叶数、两头都对得上才用，半截文字不猜（与重开旧修订同一口径）。
                val ownOfParts = currentHost.textEditBlocks(ch, r.charStart, r.charEnd)?.takeIf {
                    it.size == parts.size &&
                        it.first().start == r.charStart &&
                        it.last().endExclusive == r.charEnd
                }?.map { b -> b.ownTag }
                val revAt = at
                val stored = r.styles ?: r.editedStyles?.takeIf {
                    r.editedText == parts.joinToString("\n")
                }
                stored?.let { list ->
                    for (sp in InlineMarkup.fromStored(list)) {
                        val s = revAt + sp.start
                        val e = revAt + sp.endExclusive
                        if (e > s) spans += InlineMarkup.StyleSpan(s, e, sp.sig)
                    }
                }
                for (i in parts.indices) {
                    appendPiece(
                        parts[i],
                        if (kinds.size == parts.size) BlockKind.fromWire(kinds[i]) else null,
                        emptyList(),
                        r.charStart,
                        ownTag = ownOfParts?.getOrNull(i),
                    )
                }
            }
            pos = maxOf(pos, re)
        }
        if (pos < to) appendGap(pos, to)
        if (segments.isEmpty()) return false
        val plain = segments.joinToString("\n") { it.text }
        val (ambient, visible) = splitAmbientSpans(spans, plain.length)
        // 只要有一块拿不到形态，就整套按纯文本开卡（不写任何块标记）：否则卡片里别的块的
        // `## ` 会让提交时误以为「这次要改形态」，而这一块又承载不了。表格行本来就没有块形态，
        // 不参与这条判定（它的形态由格结构给）。
        val allKnown = segments.all { it.block != null || it.table != null }
        val shown = if (allKnown) {
            segments
        } else {
            segments.map { if (it.table != null) it else InlineMarkup.Segment(it.text, null) }
        }
        val card = InlineMarkup.show(shown, visible + ownSpans)
        // 选区与卡片目标必须同尺：卡片覆盖的是「旧修订整段 + 别的内容」，选区也一起扩过去。
        // 放在这里而不是开头：卡片范围要等块 / 表格行扩完才定下来（见 `cardFrom` / `cardTo`）。
        if (!selection.selectRange(ch, text, cardFrom, cardTo)) selection.clear()
        textEditChapter = ch
        textEditStart = cardFrom
        textEditEnd = cardTo
        textEditText = card
        textEditBaseText = plain
        textEditBaseCard = card
        textEditOriginalSpans = spans
        textEditAmbientSpans = ambient
        // 行内元素本人那层包装不算结构（与块标记同待遇）：提交时摘掉，不参与 no-op 比较。
        textEditOwnTags = ownTags.takeIf { list -> list.any { it != null } }
        textEditTables = emptyList()
        textEditImages = emptyList()
        textEditWholeTable = false
        textEditRowScope = tableScope
        textEditTableRange = tableRange
        textEditBlocksKnown = allKnown
        textEditFirstLineNoKind = allKnown && !firstStartsAtBlock
        textEditParagraphRange = null
        textEditDirty = false
        textEditFromRevision = false
        textEditError = null
        textEditOpen = true
        textEditBusy = false
        selectionMenuVisible = false
        return true
    }

    /**
     * 把正文修改的目标区间与输入区**一起**换成某一章的一段文字（[s, e) 是当前文字空间的偏移）。
     *
     * 打开卡片时用一次；之后读者还没动过输入区时，拖手柄改选区也走这里（用户定口）——
     * 输入区与目标区间必须同进同退，否则确定时会把旧文字写进新范围。
     */
    fun setTextEditTarget(ch: Int, s: Int, e: Int): Boolean {
        // 审阅视图里选中改过的字：卡片带出**改后整段**（与点按红绿字同一口径），
        // 不能把「原文 + 新文字」的对照当输入区——那会把旧文字一起写回去。
        //
        // 只在**第一次开卡**时这样扩：卡片已经开着时读者拖手柄，选区必须停在读者拖到的地方，
        // 不能再被弹回修订整段（用户定口：「用户拖到哪里，下次还是显示哪里」）。
        if (reviewActive && !textEditOpen) {
            val touched = chapterRevisions.filter { r -> s < r.charEnd && e > r.charStart }
            if (touched.isNotEmpty()) {
                if (touched.size == 1 && s >= touched[0].charStart && e <= touched[0].charEnd) {
                    val r = touched[0]
                    // 整表修订：读者只拖选其中几行时，选区就停在他拖的那几行上，不扩到整张表
                    // （拖了哪行就框住哪行），卡片也只列那几行——改完的那张表里对应的几行，
                    // 提交时再拼回整张表（结构校验仍按整张表）。
                    val wholeTable = r.editedBlocks.firstOrNull()?.startsWith("T") == true
                    val subset = wholeTable && (s > r.charStart || e < r.charEnd)
                    if (subset) {
                        val rows = currentHost.tableRowScope(ch, r.id, s, e)
                        if (openRevisionEdit(r, select = false, rows = rows)) return true
                    }
                    if (openRevisionEdit(r)) return true
                }
                val from = minOf(s, touched.minOf { it.charStart })
                val to = maxOf(e, touched.maxOf { it.charEnd })
                if (setReviewCurrentTarget(ch, from, to, touched, s, e)) return true
            }
        }
        return openTextEditAt(ch, s, e)
    }

    /**
     * 选区菜单的「修订」：把选区文字放进同一张底部卡片（正文修改 v3，方案 §8.8）。
     *
     * 跨段时用 `\n` 分隔各段：**结果段数 = 输入里的换行符数 + 1**（删光中间的字就把几段并成一段）；
     * 单叶选区里的 `\n` 仍是叶内 `<br>`。
     */
    fun beginTextEdit() {
        val p = openPos ?: return
        if (!selection.hasSelection || selection.chapter != p.chapter) return
        val ch = selection.chapter
        val s = selection.start
        val e = selection.endExclusive
        if (!setTextEditTarget(ch, s, e)) {
            snackbar.show("本章内容尚未就绪")
            return
        }
        closeAnnotationEdit()
        noteText = ""
        // 重开已有修订时输入区是改后整段，按「已动过」算；新选区才允许拖手柄把原文换进输入区。
        textEditDirty = textEditFromRevision
        textEditError = null
        textEditOpen = true
        textEditBusy = false
        selectionMenuVisible = false
        // 这次是「新用一次修订」：下次选字直接停到修订。打开旧修改走 tryOpenReviewEditAt，
        // 不经过这里，所以「只是修改原有修订」不会把默认项带跑（用户定口）。
        val remembered = withSelectionAction(light, SelectionAction.Edit)
        if (remembered != light) {
            light = remembered
            currentOnLightCommit(remembered)
        }
    }

    /** 取消正文修改：卡片关掉、正文不动、选区一并收掉（与批注取消同口径）。 */
    fun cancelTextEdit() {
        focusManager.clearFocus()
        textEditOpen = false
        textEditText = ""
        textEditDirty = false
        textEditError = null
        textEditOriginalSpans = null
        textEditAmbientSpans = emptyList()
        textEditOwnTags = null
        textEditTables = emptyList()
        textEditImages = emptyList()
        textEditWholeTable = false
        textEditRowScope = null
        textEditTableRange = null
        textEditBusy = false
        if (selection.hasSelection) selection.clear()
    }

    /**
     * 提交正文修改：纯计算 + 落库 + 单章重排都在宿主里完成；这里只管卡片、提示与选区收尾。
     *
     * 成功：关卡片、落位到修改起点、**收掉选区**（改已落库，蓝底与手柄不再留在正文上）。
     * 失败 / 校验拒绝：**卡片不关、正文不动**，就地提示。
     */
    fun commitTextEdit() {
        if (textEditBusy) return
        val ch = textEditChapter
        val s = textEditStart
        val e = textEditEnd
        if (ch < 0 || e <= s) return
        // 输入区里的标记**只活在卡片里**：先解析回「纯文本 + 样式区间」，写进正文的一律是纯文本。
        // 解析失败（非白名单标签、非法链接之类）就地提示，卡片不关、正文不动。
        val parsed = InlineMarkup.parse(textEditText)
        if (!parsed.ok) {
            // 卡片里就地列出**全部**问题（规格 §5）：只弹底部第一条，后面的错误全丢了，
            // 而且提示离输入框太远，读者不知道该改哪儿。
            textEditError = parsed.errors.joinToString("；")
            return
        }
        textEditError = null
        // 块级形态：卡片每行一块，行首标记就是这一块的目标形态；**没写标记 = 普通段落**（绝对口径，
        // 删掉 `## ` 就是把标题改回段落）。两处例外按「不改块形态」提交：
        //  - 第一行从段中间开始（那是半截文字，承载不了「这一块该长成什么样」，写了标记就拒）；
        //  - 宿主给不出块信息（老宿主 / 非盒式排版）：整套按纯文本，绝不拿「没标记」去改结构。
        // 拿不到段落结构（老宿主 / 非盒式排版 / 打开的是没存过块信息的老修改）时写不了形态：
        // 这时候得说一句，不能让读者写的 `## ` 悄悄消失。
        if (!textEditBlocksKnown && parsed.segments.any { it.block != null }) {
            textEditError = "这一处拿不到段落结构，改不了段落形态（标题、引用、列表、代码块）"
            return
        }
        // 图片 / 表格的形状校验：图片只能整张删（空串）或原样留着（占位），不能换成文字；
        // 表格在「正好选中一整张表」时只校验网格自洽（可以增删行 / 列），其余情形仍只许改格里的字。
        // 混合物卡片（表格 + 表格外的文字）不在这里逐行比形状：表格那一段交给规划层按整张表校验
        // （`TextEditPart`），文字那一段本来就不带表格行。
        if (textEditTableRange == null) {
            textEditShapeError(parsed.segments, textEditTables, textEditImages, textEditWholeTable)?.let {
                textEditError = it
                return
            }
        }
        // 行内元素本人那层标签（书里 `display:block` 的 span 之类）：卡片里看得见，提交时按
        // 「叶自己」摘掉——它不是修订里的结构（叶元素本来就留着）。读者删掉 / 换掉这层标签要拒绝：
        // 把块级元素摘掉等于把这一行并回上一行，那是结构改写，卡片这一版不做。
        val (inlineSpans, ownTagError) = withoutOwnTags(
            parsed.segments,
            parsed.spans,
            textEditOwnTags,
        )
        if (ownTagError != null) {
            textEditError = ownTagError
            return
        }
        val pieces: List<InlineMarkup.Segment>? = if (!textEditBlocksKnown && !textEditFirstLineNoKind) {
            null
        } else {
            val blocks = parsed.segments
            if (textEditFirstLineNoKind && blocks.firstOrNull()?.block != null) {
                textEditError = "第一行是从段落中间开始的半截文字，不能带块级标记（标题、引用、列表、代码块）；" +
                    "请从段首开始改"
                return
            }
            blocks.mapIndexed { i, seg ->
                when {
                    // 表格行：格结构随修订回显（重开卡片不丢形状），块形态恒 null。
                    seg.table != null -> InlineMarkup.Segment(seg.text, null, seg.table)
                    // 第一行改不了形态：保持原样（空短码 = 引擎不动这一块）。
                    i == 0 && textEditFirstLineNoKind -> InlineMarkup.Segment(seg.text, null)
                    textEditBlocksKnown -> InlineMarkup.Segment(seg.text, seg.block ?: BlockKind.Paragraph)
                    else -> seg
                }
            }
        }
        // 读者写不出属性（卡片里只有标签名，§2.2）：`class` / `id` / `style` 一律从原选区补回来；
        // 没有原结构（宿主给不出、比如审阅视图）就按同段模板补——标记仍算读者明确写的，
        // 不能因为拿不到原结构就丢掉。
        // 补回要在 no-op 比较**之前**做：解析出来的 `code` 本来就没有 class，补回后才是原样。
        val body = parsed.text
        val originalSpans = textEditOriginalSpans
        val ambient = textEditAmbientSpans
        // 同段的行内结构当模板（规格 §4.3）：读者新写的裸标记照抄同段同标签元素的属性。
        // 取不到段结构（宿主给不出 / 段落太大）就退回选区自身的结构。
        val paragraphSpans = textEditParagraphRange?.let { r ->
            currentHost.textEditSpans(ch, r.first, r.last + 1)
        } ?: originalSpans
        // 隐形元素（整段都在元素内、卡片里没显示标记）不进属性补回的模板：读者在它内部新写的
        // 同名标记是**新元素**，不能把外围那层的 class / id 借过来，否则同一层会套两遍。
        val template = originalSpans?.filterNot { it in ambient }
        val restored = if (template != null) {
            InlineMarkup.restore(template, inlineSpans, paragraphSpans.orEmpty())
        } else {
            InlineMarkup.inheritTemplateAttrs(inlineSpans, paragraphSpans.orEmpty())
        }
        val styles: List<InlineMarkup.StyleSpan>? = if (ambient.isEmpty()) {
            // 原结构拿得到 ⇒ 补回后的结果就是全部结构（空表＝读者把标记删光了，要真删）。
            if (originalSpans != null) restored else restored.ifEmpty { null }
        } else {
            // 原文没变时与原区间逐字段相等，no-op 短路照样成立。
            ambient.map { InlineMarkup.StyleSpan(0, body.length, it.sig) } + restored
        }
        // 混合物卡片（审阅视图里表格 + 表格外的文字）：按「表格那一段」切开，一次提交。
        // 切不了就就地提示、正文不动（不在猜不准的时候动书）。
        val tableRange = textEditTableRange
        val mixedParts = tableRange?.let { range ->
            MixedEditCard.parts(
                segments = pieces ?: parsed.segments,
                styles = styles,
                body = body,
                cardStart = s,
                cardEnd = e,
                tableRange = range,
                rowScope = textEditRowScope,
            )
        }
        if (tableRange != null && mixedParts == null) {
            textEditError = "表格那几行和卡片里的位置对不上：请重新选中要改的表格行"
            return
        }
        // 一个字没改、结构也没变：直接收卡，不白落一条修订（少了这一句，开卡直接确定也会写库）。
        val original = originalSpans.orEmpty()
        if (body == textEditBaseText && textEditText == textEditBaseCard && styles.orEmpty() == original) {
            focusManager.clearFocus()
            textEditOpen = false
            textEditText = ""
            textEditDirty = false
            textEditOriginalSpans = null
            textEditAmbientSpans = emptyList()
            textEditOwnTags = null
            textEditTables = emptyList()
            textEditImages = emptyList()
            textEditWholeTable = false
            textEditRowScope = null
            textEditTableRange = null
            selection.clear()
            selectionMenuVisible = false
            return
        }
        focusManager.clearFocus()
        textEditBusy = true
        scope.launch {
            var outcome: TextEditOutcome? = null
            anchorFunnel.push("text-edit", ::markPositionChanged) {
                val o = if (mixedParts != null) {
                    currentHost.commitTextEdits(ch, mixedParts, openPos)
                } else {
                    currentHost.commitTextEdit(
                        ch, s, e, body, styles, openPos, pieces, textEditRowScope,
                    )
                }
                outcome = o
                (o as? TextEditOutcome.Success)?.pos
            }
            textEditBusy = false
            when (val o = outcome) {
                is TextEditOutcome.Success -> {
                    textEditOpen = false
                    textEditText = ""
                    textEditDirty = false
                    textEditError = null
                    textEditOriginalSpans = null
                    textEditAmbientSpans = emptyList()
                    textEditOwnTags = null
                    textEditTables = emptyList()
                    textEditImages = emptyList()
                    textEditWholeTable = false
                    textEditRowScope = null
                    textEditTableRange = null
                    textEditRevision++
                    // 确认即收选区：改后的字已经落库，再留一圈蓝底只会挡住刚改过的正文。
                    selection.clear()
                    selectionMenuVisible = false
                    // 正文变了，本章标注的渲染坐标要按新修订表重算（落库仍是原书坐标）。
                    annotationState.reload()
                }
                // 校验拒绝（选区碰到图片 / 表格、跨段不能合并…）同样写在卡片里（规格 §5）：
                // 卡片不关、正文不动，读者就在输入框上方看到原因。
                is TextEditOutcome.Rejected -> textEditError = o.reason
                is TextEditOutcome.Failed -> snackbar.show(o.message)
                null -> snackbar.show("正在处理，请稍后再试")
            }
        }
    }

    /**
     * 正文修改卡的「删除」：撤掉覆盖这段字的修订，**恢复原文**（与删高亮 / 划线 / 批注同一语义）。
     *
     * 只删与**当前选区相交**的修订：跨段一次提交会落多条，整组都相交时一起撤掉。
     * 没有相交修订时按钮本来就是虚的，这里再挡一次（不改正文、不关卡片）。
     */
    fun deleteTextEdit() {
        if (textEditBusy) return
        val ch = textEditChapter
        val s = textEditStart
        val e = textEditEnd
        if (ch < 0 || e <= s) return
        val targets = chapterRevisions.filter { r ->
            (r.charStart < e && s < r.charEnd) ||
                (r.charStart == r.charEnd && r.charStart in s..e)
        }
        if (targets.isEmpty()) return
        focusManager.clearFocus()
        textEditBusy = true
        scope.launch {
            var newPos: ReaderPos? = null
            var failed = false
            // 逐条删除时，当前页在**新文字空间**里的页首也跟着换：第一条删完用宿主返回的
            // 新落位给第二条当锚点，别拿删除前的旧页首去算下一条（偏移已经平移过了）。
            var page = openPos
            for (r in targets) {
                when (val o = currentHost.deleteTextRevision(r, page)) {
                    is TextRevisionDeleteOutcome.Success -> o.pos?.let {
                        newPos = it
                        page = it
                    }
                    else -> {
                        failed = true
                        break
                    }
                }
            }
            textEditBusy = false
            if (failed) {
                snackbar.show("删除失败，请稍后再试")
                return@launch
            }
            textEditOpen = false
            textEditText = ""
            textEditDirty = false
            textEditRevision++
            chapterRevisions = emptyList()
            selection.clear()
            selectionMenuVisible = false
            // 正文变了：本章标注的渲染坐标要按新修订表重算（落库仍是原书坐标）。
            annotationState.reload()
            newPos?.let { openPos = it }
        }
    }

    /**
     * 正文修改卡上点「高亮 / 划线」：**切回该类别的批注**（同一根操作栏，不另起一套）。
     *
     * 与取消的区别：这里**保留选区**（切过去就是要落这一笔），只丢掉还没提交的输入。
     */
    fun switchTextEditToAnnotation(kind: AnnotationKind) {
        textEditOpen = false
        textEditText = ""
        textEditDirty = false
        textEditBusy = false
        noteText = ""
        noteKind = kind
        noteLineStyle = annotationLastLineStyleOf(light)
        val next = withAnnotationLastKind(light, kind)
        if (next != light) {
            light = next
            currentOnLightCommit(next)
        }
        // 选区还在：把批注卡重新露出来（`selectionMenuOn` 由它 + 选区共同决定）。
        selectionMenuVisible = true
    }

    /**
     * 同一格色槽：**只选中**（D-28），不落笔——点错颜色还有机会改，卡片也不会忽然关掉。
     *
     * 落笔交给 `确定` 或点卡片外的正文（见 [commitNote]）。顺手把「这次用的类别 + 色槽」记住
     * （写盘，无重排提交）：下次打开卡片直接停在这一类、这一格上——色槽同时成为该类的新建默认格。
     */
    fun pickNoteColor(kind: AnnotationKind, slot: Int) {
        noteKind = kind
        notePickedColors[kind] = annotationSlotColor(light, kind, slot)
        // 落盘一律小写（`highlight` / `underline`，见 ReaderSettings 的约定），
        // 解析侧 annotationLastKindOf 再另行 lowercase，双向容错。
        var next = withAnnotationDefaultSlot(light, kind, slot)
        // 只在新建时把「上次用的操作」改成这一类：给旧标注改颜色不算（用户定口）。
        if (editingAnnotation == null) {
            next = withAnnotationLastKind(next, kind)
        }
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
        // 新建时算「用了一次划线」；给旧标注改线型只记住线型偏好，不把默认操作带成划线。
        val next = if (editingAnnotation == null) {
            withAnnotationLastLineStyle(light, style)
        } else {
            light.copy(annotationLastLineStyle = style.wire)
        }
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
        // 剪贴板里只该有书里的字：审阅视图的「段界痕迹」¶ 与中西接缝上被画成零宽的空白一并摘掉，
        // 偏移与块尾同步回缩（原文与下标不动，只在「带走文字」这一口处理）。
        val brk = currentHost.plainTextOf(ch)
        val copied = copyTextOf(
            brk.plainChapterText(text),
            brk.plain(s),
            brk.plain(e),
            brk.plainBlockEnds(currentHost.textBlockEndsOf(ch)),
        )
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

    /**
     * 键盘卡片开着时，页面上下滚动的惯性（D-28 键盘视口）：抬手后按当时的速度继续走，
     * 按指数摩擦逐步减速，撞到视口上/下沿即停——读者把页面往上甩一把，它会自己滑到页底，
     * 而不是抬手指就钉在原地。
     *
     * 驱动的是组合状态 [userShiftPx] 而不是 Animatable：上限 [maxShiftPxState] 跟着卡片高度 /
     * 键盘高度变，每帧都得按当下上限夹一次（衰减动画本身不知道这个边界）。
     */
    fun startPageFling(velocityPxPerSec: Float) {
        pageFling.job?.cancel()
        if (abs(velocityPxPerSec) < PAGE_FLING_MIN_DP_S * density) return
        pageFling.job = scope.launch {
            var v = velocityPxPerSec
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                val cur = userShiftPx ?: inputShiftPx
                val next = (cur + v * dt).coerceIn(0f, maxShiftPxState)
                userShiftPx = next
                // 到顶 / 到底就收：继续衰减也只是原地不动。
                if (next <= 0f || next >= maxShiftPxState) return@launch
                v = pageFlingDecay(v, dt)
                if (abs(v) < PAGE_FLING_STOP_DP_S * density) return@launch
            }
        }
    }

    /**
     * 滚动翻页（桌面）：横滑 / 横向滚轮 / 鼠标滚轮一格出来的翻页请求都走这里。
     *
     * 触控板的**上下滑不翻页**（2026-11-04 用户定口：那是误触）；鼠标滚轮照旧拨一格翻一页
     * （判据见 [ScrollFlipArbiter]）。翻页动画在飞时先攒一次、等落地补翻，不当场丢掉。
     */
    fun requestScrollFlip(direction: Int) {
        if (direction == 0) return
        // 封面只能往前走（与点按同一口径）；往回滚在封面上不响应。
        if (coverVisible && direction < 0) {
            Logger.d("Orilumn.SCROLL", "flip dir=$direction dropped (cover)")
            return
        }
        if (!ctl.isBusy()) {
            pendingScrollFlip.clear()
            Logger.d("Orilumn.SCROLL", "flip dir=$direction")
            flipWithAnimation(direction)
            return
        }
        Logger.d("Orilumn.SCROLL", "flip dir=$direction queued (busy)")
        pendingScrollFlip.offer(direction, scope, { ctl.isBusy() }) { d ->
            if (!(coverVisible && d < 0)) {
                Logger.d("Orilumn.SCROLL", "flip dir=$d queued")
                flipWithAnimation(d)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(profile.bgColor))
            // 桌面滚动通道（滚轮 / 触控板双指），分两套语义：
            //  - 标注卡片开着：在本页视口里上下滚页（2026-11-04 用户定口，不用鼠标拖拽滚页）；
            //  - 卡片关着：横滑与**鼠标滚轮一格**翻页，触控板双指上下滑不翻页
            //    （2026-11-04 用户定口；判据见 [ScrollFlipArbiter]，一次连续横滑最多翻一页）。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type != PointerEventType.Scroll) continue
                        val change = event.changes.firstOrNull() ?: continue
                        if (change.isConsumed) continue
                        if (currentNoteBarOpen) {
                            // 指针压在卡片上：让给卡片自己（卡片里的滚动区 / 输入框）。
                            if (change.position.y >= sheetTopPxState) continue
                            // 指针压在栏上：那是栏自己的滚动区（列表等），不替它滚页。
                            val inBars = ReaderMath.downInBars(
                                currentBarsVisible, change.position.y, size.height.toFloat(),
                                currentTopBarH.toFloat(), currentBotBarH.toFloat(),
                            )
                            if (inBars) continue
                            val dy = change.scrollDelta.y
                            if (dy == 0f) continue
                            // 滚轮接管：先把还没停的惯性停掉，再按读者滚的量走。
                            pageFling.job?.cancel()
                            val cur = userShiftPx ?: inputShiftPx
                            userShiftPx = (cur + dy * PAGE_WHEEL_DP_PER_UNIT * density)
                                .coerceIn(0f, maxShiftPxState)
                            change.consume()
                            continue
                        }
                        val inBars = ReaderMath.downInBars(
                            currentBarsVisible, change.position.y, size.height.toFloat(),
                            currentTopBarH.toFloat(), currentBotBarH.toFloat(),
                        )
                        if (inBars) continue
                        val dx = change.scrollDelta.x
                        val dy = change.scrollDelta.y
                        val dir = scrollFlip.onScroll(change.uptimeMillis, dx, dy)
                        // 滚轮的一格被 macOS 摊成一串小数事件，得等这串**断流**才能判
                        //（末尾是不是收在峰值上，见 ScrollFlipArbiter）。每来一条竖向事件
                        // 就把计时推后；判出是滚轮再翻页。
                        if (dx == 0f && dy != 0f) {
                            val token = scrollFlip.wheelBurstVersion
                            wheelBurstTimer.restart(scope) {
                                if (scrollFlip.wheelBurstVersion != token) return@restart
                                val wheelDir = scrollFlip.finishWheelBurst()
                                if (wheelDir != 0) requestScrollFlip(wheelDir)
                            }
                        }
                        if (dir != 0) {
                            change.consume()
                            requestScrollFlip(dir)
                        }
                    }
                }
            }
            .pointerInput(touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 新手势按下即打断上一次的惯性滚动（抬手后页面还在滑，读者又按下去）。
                    pageFling.job?.cancel()
                    // 上一个手势要是没结算就消失了（抬起被吞 / 指针丢失 / 手势协程异常），
                    // 会话会永远停在 Dragging：批注层被藏、所有点按被 busy 墙挡住。
                    // 新的一轮按下说明上一根手指早已离开，正好在这里收尾（判据见下）。
                    ctl.recoverStuckDrag()
                    // 同理补收亮度指示条：上一轮亮度手势若在「抬手之外」的路径上结束
                    //（指针丢失 / 手势被系统取消），收尾已由 settleBrightness 兜住；
                    // 这里再加一道新手势兜底——只要读者再碰屏幕，屏底就不会留着旧条。
                    if (brightnessUi != null) endBrightnessGesture()
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
                    // 触摸上下滑 = 在视口里滚页（视口 = 卡片上沿之上那一条），点按（没动过）= 取消。
                    // 桌面鼠标拖拽不滚页（2026-11-04 用户定口）：上下滚交给滚轮 / 触控板双指，
                    // 见上面的滚动分支；鼠标在这儿只认「点按 = 取消」，拖动什么也不做。
                    if (currentNoteBarOpen && !barsOwned) {
                        if (down.isConsumed || downY >= sheetTopPxState) return@awaitEachGesture
                        // 编辑已有标注时同样允许拖手柄改范围：卡片开着不代表选区锁死（2026-11-03 用户定口）。
                        val handle = if (!coverVisible && !ctl.isBusy()) {
                            selectionHandleAt(downX, downY, size.width.toFloat(), size.height.toFloat())
                        } else {
                            null
                        }
                        if (handle == null) {
                            val fingerScroll = down.type != PointerType.Mouse
                            val shiftAtDown = inputShiftPx
                            var dragged = false
                            var upTime = 0L
                            // 抬手速度样本（时间 → 手指 y）：只留回看窗口内的一段，算「甩」的那一下。
                            val samples = ArrayDeque<Pair<Long, Float>>()
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
                                if (dragged && fingerScroll) {
                                    // 手指往上 ⇒ 页面往上走；最多滚到页底贴住卡片上沿。
                                    userShiftPx = (shiftAtDown - (change.position.y - downY))
                                        .coerceIn(0f, maxShiftPxState)
                                    samples.addLast(change.uptimeMillis to change.position.y)
                                    while (samples.size > 2 &&
                                        change.uptimeMillis - samples.first().first > PAGE_FLING_SAMPLE_MS
                                    ) {
                                        samples.removeFirst()
                                    }
                                }
                                if (!change.pressed) {
                                    upTime = change.uptimeMillis
                                    break
                                }
                            }
                            // 手指停住再抬（最后一次移动已经超出回看窗口）不算甩：否则会凭空滑一大段。
                            val velocity = if (upTime - (samples.lastOrNull()?.first ?: 0L) <= PAGE_FLING_SAMPLE_MS) {
                                pageFlingVelocityPxPerSec(samples)
                            } else {
                                0f
                            }
                            // 没拖过 = 点卡片外：按 D-28 取消（落笔只认操作栏的 `确定`，点外面不写任何东西，
                            // 免得「想收起卡片」被当成「那就按当前样式落一条」）。
                            // 正文修改卡片同口径：点卡片与键盘以外的正文 = 取消本次修改（写了一半的内容作废、
                            // 正文不动、选区收掉），与批注的取消路径完全一致。
                            // 触摸拖过 = 抬手后按当前速度继续滑、逐步减速（惯性）；鼠标拖动到此为止。
                            when {
                                !dragged -> if (textEditOpen) cancelTextEdit() else cancelNote()
                                fingerScroll -> startPageFling(velocity)
                            }
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
                    // 上膛状态：一次进入只翻一页，退回正文区才重新上膛（见 scheduleAutoFlip）。
                    var autoFlipArmed = true
                    var boundaryHintShown = false
                    var lastFingerX = downX
                    var lastFingerY = downY
                    // 按指针类型分流：鼠标拖拽只做文本选择，不拖页；单击与平板同一个点按入口
                    //（窗口左中右三分区同作用，含点中标注卡片 / 链接的同一优先级）。
                    // 版心几何只用于判断「拖动起选」的落点，和绘制/命中同源。
                    val mouse = down.type == PointerType.Mouse
                    val contentLeftPx = profile.marginLeft.toFloat()
                    val contentTopPx = profile.marginTop.toFloat()
                    val contentRightPx = (size.width - profile.marginRight).toFloat().coerceAtLeast(contentLeftPx)
                    val contentBottomPx = (size.height - profile.marginBottom).toFloat().coerceAtLeast(contentTopPx)
                    // 自动翻页触发带按**正文缘**算：越过正文再往外 8dp 才算进带，边距小的书
                    // 不会「拖到行末就翻页」；边距窄到放不下时退回屏幕最外 12dp 的保底带。
                    val autoFlipZone = selectionFlipZone(
                        widthPx = size.width.toFloat(),
                        contentLeftPx = contentLeftPx,
                        contentRightPx = contentRightPx,
                        pastTextPx = SELECTION_AUTO_FLIP_PAST_TEXT_DP * density,
                        edgePx = SELECTION_AUTO_FLIP_EDGE_DP * density,
                    )
                    val autoFlipRearmPx = SELECTION_AUTO_FLIP_REARM_DP * density
                    val pageScrollEdgePx = PAGE_SCROLL_EDGE_DP * density
                    val mouseTextDown = mouse && !barsOwned && !coverVisible && !ctl.isBusy() &&
                        !down.isConsumed &&
                        downX >= contentLeftPx && downX <= contentRightPx &&
                        downY >= contentTopPx && downY <= contentBottomPx
                    var mouseSelectingPending = mouseTextDown
                    // 长按起手展开的整词 / 整句只是「抬手不拖」时的样子：一旦开始拖，锚点就改成
                    // 按下的那个字（见 TextSelectionState.anchorAt），否则从行中往右拖会变成
                    // 「行首 → 手指」，读者看到的是选区从行首开始。
                    var dragAnchor: Int? = null
                    var pageScrollJob: Job? = null
                    var pageScrollDir = 0
                    /**
                     * 手柄拖到视口上下边界时让**整页**跟着滚（D-28 补充）：视口 = 卡片上沿之上那一条，
                     * 滚到「页底贴卡片上沿」为止。每滚一小段就按手指位置重新延伸一次选区，
                     * 于是手指按在原地也能一路选下去。
                     *
                     * 按帧连续推进（早先是每 40ms 定步长挪 12dp，两步之间看得见一顿一顿），
                     * 起手 [PAGE_SCROLL_RAMP_MS] 内从 0 线性加速，进带子那一刻不会整块跳出去。
                     */
                    fun schedulePageScroll(dir: Int) {
                        if (dir == pageScrollDir) return
                        pageScrollDir = dir
                        pageScrollJob?.cancel()
                        pageScrollJob = null
                        if (dir == 0) return
                        pageScrollJob = scope.launch {
                            val speed = dir * PAGE_SCROLL_SPEED_DP_S * density
                            val startNs = withFrameNanos { it }
                            var last = startNs
                            var lastExtendPx = userShiftPx ?: inputShiftPx
                            while (gestureActive && selecting && pageScrollDir == dir) {
                                val now = withFrameNanos { it }
                                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                                last = now
                                val ramp = ((now - startNs) / 1_000_000f / PAGE_SCROLL_RAMP_MS).coerceIn(0f, 1f)
                                val cur = userShiftPx ?: inputShiftPx
                                val next = (cur + speed * ramp * dt).coerceIn(0f, maxShiftPxState)
                                if (next == cur) continue
                                userShiftPx = next
                                // 选区每挪够 2dp 才重算一次：命中测试本身不便宜，不必每帧都做。
                                if (abs(next - lastExtendPx) >= 2f * density) {
                                    lastExtendPx = next
                                    extendSelectionAt(lastFingerX, lastFingerY, size.width.toFloat())
                                }
                            }
                        }
                    }
                    /**
                     * 进带 [SELECTION_AUTO_FLIP_FIRST_MS] 后翻**一页**就停：翻完置 [autoFlipArmed] = false，
                     * 手指要退回正文区（[autoFlipRearmPx] 回差）再进带才会翻下一页。
                     *
                     * 早先是「按住不放每 400ms 再翻」：读者停在页边等选区跟过来、或只是沿着页边
                     * 往下拖，就会被连翻两三页（真机反馈）。翻页没成（邻页未就绪 / 导航被丢弃）时
                     * 按 [SELECTION_AUTO_FLIP_RETRY_MS] 重试，成功即停。
                     */
                    fun scheduleAutoFlip(side: Int) {
                        if (side == 0 || !autoFlipArmed || boundaryHintShown ||
                            autoFlipJob?.isActive == true
                        ) {
                            return
                        }
                        autoFlipJob = scope.launch {
                            var wait = SELECTION_AUTO_FLIP_FIRST_MS
                            while (true) {
                                delay(wait)
                                if (!gestureActive || !selecting || !autoFlipArmed ||
                                    autoFlipSide != side
                                ) {
                                    return@launch
                                }
                                val p = openPos
                                if (p == null || selection.chapter != p.chapter) return@launch
                                if (autoFlipSelection(side, lastFingerY)) {
                                    autoFlipArmed = false
                                    return@launch
                                }
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
                                wait = SELECTION_AUTO_FLIP_RETRY_MS
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
                                        val pressOffset = beginSelectionAt(downX, downY)
                                        selecting = pressOffset != null
                                        if (selecting) {
                                            magnifierFinger = Offset(downX, downY)
                                            dragAnchor = pressOffset
                                        }
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
                    // 下拉切换书签：本次手势是否正在拉页、以及起手时查「本页有没有书签」的读取。
                    // 页面偏移写在 bookmarkPull 里（组合外状态），这里只记手势归属与一次性查询。
                    var pullActive = false
                    var pullOffsetNow = 0f
                    var pullQueryJob: Job? = null
                    // 双指上下滑调亮度（brightnessGestureTwo）：第二根手指落下就接管本手势，
                    // 按两指平均纵向位移算（两指各自动也不算「一起滑」）；期间不翻页、不选字。
                    var twoFingerBrightness = false
                    var twoFingerBaseY = 0f
                    /**
                     * 亮度手势收尾（单指/双指共用，幂等）：抬手、事件被上层吞掉、开工指针丢失、
                     * 手势协程被取消（系统返回手势 / 切后台 / 掌心误触）——任何一条退出路径
                     * 都要把底部指示条收掉并落库。
                     *
                     * 早先只有 finally 里的双指分支会收，单指的 `brightnessActive` 一旦不走
                     * 「抬手」那条路就再没人清 `brightnessUi`，指示条永久留在屏底（真机反馈
                     * 「调节亮度的进度条经常不消失」）。
                     */
                    fun settleBrightness() {
                        if (!brightnessActive) return
                        brightnessActive = false
                        twoFingerBrightness = false
                        Logger.d("Orilumn.BRIGHT", "settle b=${light.brightness}")
                        endBrightnessGesture()
                    }
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
                        // 双指上下滑调亮度（任意位置，见设置里的「任意位置双指上下滑」）。
                        // 原实现只把开关存下来、渲染出来，手势层从没读过它 ⇒ 开了也没反应。
                        // 判定用「按下的指针数 ≥ 2」，不要求第二根手指也过 slop：
                        // 两指按住再一起上下滑即可，接管的时机是**第二根手指落下那一帧**。
                        val pressedPointers = event.changes.filter { it.pressed }
                        if (!mouse && !selecting && brightnessAdjustable &&
                            ReaderMath.twoFingerBrightnessAllowed(
                                light.brightnessGestureTwo, light.brightnessFollowSystem,
                            ) &&
                            pressedPointers.size >= 2
                        ) {
                            if (!twoFingerBrightness) {
                                twoFingerBrightness = true
                                brightnessActive = true
                                brightnessBase = light.brightness
                                // 起手位置取两指平均 y，之后按平均位移算：单看一根手指会漏掉
                                // 「另一根手指在动、这根不动」。
                                twoFingerBaseY = ReaderMath.averagePressedY(
                                    pressedPointers.map { it.position.y },
                                )
                                // 双指常被系统/自身当成「长按再移动」：撤掉还没起手的长按计时，
                                // 已经起手的单指翻页滑动就地结算，别让页面一边翻一边变亮。
                                longPressJob?.cancel()
                                autoFlipSide = 0
                                if (slideActive && !slideEnded) {
                                    currentEndSlide(vx, size.width.toFloat())
                                    slideEnded = true
                                }
                                Logger.d(
                                    "Orilumn.BRIGHT",
                                    "two-finger start fingers=${pressedPointers.size} " +
                                        "y=${twoFingerBaseY.roundToInt()} b=$brightnessBase",
                                )
                            }
                            val avgY = ReaderMath.averagePressedY(pressedPointers.map { it.position.y })
                            applyBrightnessDelta(
                                brightnessBase,
                                ReaderMath.brightnessFrac(avgY - twoFingerBaseY, size.height.toFloat()),
                            )
                            // 全消费：翻页/选字/联动的手指都别再从这次手势里捞事件。
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (twoFingerBrightness) {
                            // 两指只剩一根（或一起抬起）：本次亮度手势到此为止。
                            // 不降级成单指翻页/选字——那会让读者抬一根手指就被翻页。
                            Logger.d("Orilumn.BRIGHT", "two-finger end b=${light.brightness}")
                            settleBrightness()
                            break
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
                                    // 上次选字用的是「修订」：新选区直接进正文修改卡（与点操作栏的
                                    // 「修订」同一条路）。正在改的修改卡不再重开，否则会冲掉刚输入的字。
                                    if (!textEditOpen && selectionActionOf(light) == SelectionAction.Edit) {
                                        beginTextEdit()
                                    }
                                }
                                change.consume()
                                break
                            }
                            lastFingerX = change.position.x
                            lastFingerY = change.position.y
                            if (!mouse) magnifierFinger = Offset(lastFingerX, lastFingerY)
                            // 第一次真的拖动：锚点从「整词/整句首」挪到按下那个字（只挪一次）。
                            dragAnchor?.let {
                                selection.anchorAt(it)
                                dragAnchor = null
                            }
                            extendSelectionAt(lastFingerX, lastFingerY, size.width.toFloat())
                            // 一次进入只翻一页：退回正文区（回差）才重新上膛。
                            if (autoFlipZone.hasLeftBand(lastFingerX, autoFlipRearmPx)) {
                                autoFlipArmed = true
                            }
                            autoFlipSide = autoFlipZone.directionOf(lastFingerX)
                            scheduleAutoFlip(autoFlipSide)
                            // 手柄拖到视口上下边界 ⇒ 整页滚（卡片开着时视口只到卡片上沿）。
                            val scrollDir = when {
                                currentNoteBarOpen && lastFingerY > sheetTopPxState - pageScrollEdgePx &&
                                    inputShiftPx < maxShiftPxState -> 1
                                currentNoteBarOpen && lastFingerY < pageScrollEdgePx &&
                                    inputShiftPx > 0f -> -1
                                else -> 0
                            }
                            schedulePageScroll(scrollDir)
                            change.consume()
                            continue
                        }
                        // 指针类型分流：鼠标拖拽进入选区；单击与触摸同一套语义
                        //（标注卡片 → 清选区 → 链接 → 三区点按：左右翻页 / 中部切栏）。
                        // 滚轮 / 触控板双指仍走上面的滚动通道；
                        // 触摸走下面的三区点按/滑动/亮度，两套语义不抢同一个手势。
                        if (mouse) {
                            if (mouseSelectingPending && moved) {
                                mouseSelectingPending = false
                                selectionMenuVisible = false
                                // 鼠标拖选从按下那个字起，不扩成整词 / 整句（D-9）：桌面没有长按语义，
                                // 扩成整句就是「选区从行首开始」。
                                if (beginSelectionAt(downX, downY, selectUnit = false) != null) {
                                    selecting = true
                                    Logger.d(
                                        "Orilumn.SEL",
                                        "mouse-drag ch=${selection.chapter} " +
                                            "sel=${selection.start}-${selection.endExclusive}",
                                    )
                                    lastFingerX = change.position.x
                                    lastFingerY = change.position.y
                                    extendSelectionAt(lastFingerX, lastFingerY, size.width.toFloat())
                                    if (autoFlipZone.hasLeftBand(lastFingerX, autoFlipRearmPx)) {
                                        autoFlipArmed = true
                                    }
                                    autoFlipSide = autoFlipZone.directionOf(lastFingerX)
                                    scheduleAutoFlip(autoFlipSide)
                                }
                            }
                            if (!change.pressed) {
                                // 鼠标单击：与触摸同一套三区语义（左右 1/3 翻页、
                                // 中部切栏，2026-11-20 恢复桌面点击翻页）。
                                // 按下点已被上层消费（栏/遮罩）就不落到阅读面。
                                if (!moved && !down.isConsumed) {
                                    onTap(downX, downY, size.width.toFloat())
                                }
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
                            if (pullActive) {
                                val maxPull = size.height * BookmarkPullMaxFraction
                                val armed = pullOffsetNow >= currentMarkBottomPx
                                pullActive = false
                                pullOffsetNow = 0f
                                pullQueryJob?.cancel()
                                // 松手即弹回：与左滑露按钮同一个弹簧口径（中低刚度 + 明显回弹）。
                                scope.launch {
                                    bookmarkPull.animateTo(
                                        0f,
                                        spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.5f),
                                    )
                                }
                                if (armed) {
                                    val pos = openPos
                                    if (pos != null) {
                                        scope.launch {
                                            when (val result = currentHost.toggleBookmarkAt(pos)) {
                                                is BookmarkToggleResult.Added -> bookmarkOnPage = true
                                                is BookmarkToggleResult.Removed -> bookmarkOnPage = false
                                                BookmarkToggleResult.Failed -> snackbar.show("书签操作失败")
                                            }
                                        }
                                    }
                                }
                                change.consume()
                                break
                            }
                            if (brightnessActive) settleBrightness()
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
                                        currentBeginSlide(dragDir, tDown)
                                    }
                                    currentUpdateSlide(dx, size.width.toFloat())
                                    // 卷曲手感：起手点 + 手指的**绝对页坐标**。折痕角度取拖动
                                    // 方向的垂线，折痕位置由起手所在半边的锚点反解（右半边
                                    // 纸边压手指、左半边折痕穿手指，见 curl/CurlPull）。
                                    currentUpdateCurlPull(
                                        downX,
                                        downY,
                                        downX + dx,
                                        downY + dy,
                                        size.width.toFloat(),
                                        size.height.toFloat(),
                                    )
                                    if (tMove == 0L) tMove = System.nanoTime()
                                }
                            }
                            ReaderMath.Axis.VERTICAL -> {
                                if (!brightnessActive && brightnessAdjustable) {
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
                                // 下拉切换书签：亮度手势没接管、也没在选字、标题栏收着时才认。
                                // 左右 1/3 若开着亮度手势，那一段仍归亮度——书签下拉只吃屏幕其余部分。
                                val maxPull = size.height * BookmarkPullMaxFraction
                                if (!brightnessActive && !mouse && maxPull > 0f && !coverVisible &&
                                    !currentBarsVisible && !selection.hasSelection && !ctl.isBusy()
                                ) {
                                    if (dy > 0f) {
                                        if (!pullActive) {
                                            pullActive = true
                                            val p = openPos
                                            val pageChapter = p?.chapter
                                            val pageStart = p?.slice?.charStart
                                            pullQueryJob?.cancel()
                                            // 起手就查本页有没有书签：右上角图案据此决定红/灰。
                                            pullQueryJob = scope.launch {
                                                val hit = p?.let { currentHost.lookupBookmarkOnPage(it) }
                                                if (pullActive && hit != null && openPos?.chapter == pageChapter &&
                                                    openPos?.slice?.charStart == pageStart
                                                ) {
                                                    bookmarkOnPage = when (hit) {
                                                        is BookmarkLookup.Found -> true
                                                        BookmarkLookup.None -> false
                                                        BookmarkLookup.Failed -> null
                                                    }
                                                }
                                            }
                                        }
                                        // 指数阻尼（同左滑露按钮的橡皮筋）：越拉越费力，渐近但不超过 1/4 页。
                                        val raw = (dy - touchSlop).coerceAtLeast(0f)
                                        val pulled = maxPull * (1f - exp(-raw / maxPull))
                                        pullOffsetNow = pulled
                                        scope.launch { bookmarkPull.snapTo(pulled) }
                                    } else if (pullActive) {
                                        // 拉回起手点以上：本次下拉作废，页面归位，不切换书签。
                                        pullActive = false
                                        pullOffsetNow = 0f
                                        pullQueryJob?.cancel()
                                        scope.launch { bookmarkPull.snapTo(0f) }
                                    }
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
                        // 下拉手势以任何方式结束（抬手之外：指针丢失 / 手势被取消 / 组合离场）：
                        // 页面必须弹回去，不能停在半拉的位置。
                        if (pullActive) {
                            pullActive = false
                            pullOffsetNow = 0f
                            pullQueryJob?.cancel()
                            scope.launch {
                                bookmarkPull.animateTo(
                                    0f,
                                    spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.5f),
                                )
                            }
                        }
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
                        // 亮度手势期间指针丢失 / 手势被取消（返回手势、切后台、掌心误触）：
                        // 指示条与落库都收掉，别留在屏上（单指、双指同一条路）。
                        settleBrightness()
                    }
                }
            },
    ) {
        val pxWidth = with(LocalDensity.current) { maxWidth.toPx() }
        val pxHeight = with(LocalDensity.current) { maxHeight.toPx() }
        val bookmarkPullMaxPx = pxHeight * BookmarkPullMaxFraction
        val contentLeft = profile.marginLeft.toFloat()
        val contentTop = profile.marginTop.toFloat()
        val contentRight = (pxWidth - profile.marginRight).coerceAtLeast(contentLeft)
        val contentBottom = (pxHeight - profile.marginBottom).coerceAtLeast(contentTop)

        // 封面也算「一页纸」：卷曲层要的是页大小的纹理，而封面是纯组合件（没有页栅格），
        // 所以按封面页同一套画法烤一张整页位图交给它（见 [bakeCoverPageBitmap]）。
        // 只在可能翻到封面的位置烤（停在书首 / 封面正显示），离开书首就放掉，不常驻一张整页图。
        val coverImages = remember { CurlPageImages() }
        // 正文页栅格在页坐标里的左上角（版心）；覆盖层恒为整页、贴 (0,0)。
        val bodyTex = Offset(contentLeft, contentTop)
        val coverTexNeeded = coverBmp != null && (coverVisible || isBookStart(openPos))
        LaunchedEffect(coverTexNeeded, coverBmp, pxWidth, pxHeight, light.coverStretch, profile.bgColor) {
            val src = coverBmp
            if (!coverTexNeeded || src == null) {
                coverImages.page = null
                return@LaunchedEffect
            }
            val w = pxWidth.roundToInt()
            val h = pxHeight.roundToInt()
            // 烤图不占主线程（一张整页位图的内存搬运没必要卡动画起手那一帧）。
            coverImages.page = withContext(Dispatchers.Default) {
                RasterTexture(
                    bakeCoverPageBitmap(src, w, h, proportional = !light.coverStretch, bgArgb = profile.bgColor),
                )
            }
        }

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
                    // 上下栏露着也照翻：早先「状态栏一打开就禁用左右键」是给「在栏按键之间切焦点」
                    // 占的位，可那条路一直没做，实际只剩副作用——点屏幕中部（这也是退出选区的那个
                    // 手势）把栏点出来之后，左右键**静默失效**，读者只会觉得「翻页卡死了」。
                    // 栏里真要键盘导航，将来另走焦点遍历；面板打开时 keysEnabled=false 已经隔离。
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
            ReaderLightMask(light = maskLight, modifier = Modifier.fillMaxSize())
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
                // 书签 / 笔记：与设置 / 目录 / 修订同一拍——收栏与开清单同时发起（栏在抽屉之下
                // 退场）。清单里点一条就是跳转；跳转一律从「工具栏已退出」的状态发起，
                // 落位后读者看到的是干净的一页。
                onBookmark = currentOnBookmark?.let { cb -> { hideBarsThen { cb() } } }
                    ?: { snackbar.show("当前版本不支持书签") },
                onNote = currentOnNote?.let { cb -> { hideBarsThen { cb() } } }
                    ?: { snackbar.show("笔记（规划中）") },
                // 修订：收栏与开清单同一帧发起（栏在抽屉之下退场，不会盖在晾着的工具栏上）。
                onRevision = currentOnRevision?.let { cb -> { hideBarsThen { cb() } } },
                // 审阅：切换后栏要留着（按钮高亮本身就是「现在看的是改动痕迹」的指示）。
                onReview = currentOnReview,
                reviewActive = reviewActive,
                nightActive = nightActive,
                showBookmark = settings.showToolbarBookmark,
                showNote = settings.showToolbarNote,
                showRevision = settings.showToolbarRevision,
                showReview = settings.showToolbarReview,
                showNight = settings.showToolbarNight,
                onTopBarSize = { topBarH = it.height },
                onBottomBarSize = { botBarH = it.height },
            )
            BrightnessGestureIndicator(ui = brightnessUi, modifier = Modifier.align(Alignment.BottomCenter))
            SnackbarHost(
                hostState = snackbar.hostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
            )
        } else if (pos != null) {
            // 进度条口径随页数完整度切换（按页 ↔ 按章）：页数补齐、换参数清空都要立刻反映，
            // 只认 pos 的 remember 会停在旧口径上，所以把页数代际也当键。
            val progressRev = currentHost.pageNumberRevision()
            val fraction = remember(pos, progressRev) {
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
                // 会话在飞时用本次方向；落定后状态机把 direction 归零，退回「最近一次翻页方向」
                // （否则往回读时永远只热身正方向）。两者都无 → 0，预栅格按向前处理。
                directionProvider = { ctl.session.direction.takeIf { it != 0 } ?: ctl.lastTurn.value },
                currentPos = { openPos },

                renderer = pageRenderer,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRight = contentRight,
                contentBottom = contentBottom,
                pageBg = profile.bgColor,
                inkColor = profile.fgColor,
                contentRevision = effectiveRevision,
                hostRevision = hostRevision,
                imgCache = imgCache,
                bgCache = bgCache,
                annotationsFor = annotationState::annotationsFor,
                // 会话在飞时让位：此刻落位自己正在栅同一张（见 PrerasterOnPrefill.sessionBusy）。
                sessionBusy = { ctl.isBusy() },
                // 落定代际：翻完立刻补方向侧像素，下一次起手就不用现场栅 200–390ms。
                settleTick = ctl.settleTick.value,
            )
            // 上面 `beforeCommit` 的「整页位图备齐」实现：与 PrerasterOnPrefill 同口径
            //（同一渲染器、同一缓存、同一条取数路径），只是针对**本次落位目标页**。
            // `prerasterPage` 已把重画切到 Default 线程，所以这里 await 不冻 UI；等它的结果是
            // 提交即命中——翻页动画不再在 UI 线程同步栅格（0.5–0.9s 冻帧的来源）。
            // 预栅格备的是**闸开后那一次**重栅格的位图 ⇒ 取调用时刻的最新版本号：在途途中
            // 后续 tick 还会再 bump，用构造时捕获的旧值会让这笔预栅格白做（画的那下还得重栅格）。
            val liveRevision by rememberUpdatedState(effectiveRevision)
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
            // 正文修改卡与批注卡共用同一张卡：打开期间保持选中（让位要按选区量），
            // 但不显示选区浮动菜单。
            val textEditOn = textEditOpen && !coverVisible && !ctl.isBusy()
            val menuOn = (editing != null || selectionMenuOn || textEditOn) && !coverVisible && !ctl.isBusy()
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
            // 全书页码（用户层「显示页码」）：引擎在章分页未就绪时给 null → 页脚留白。
            // 代际 `pageNumberRevision()` 是宿主里的 Compose 快照状态：后台把各章页数补齐、
            // 或本章正典表刚落时它一变，读者静置不翻页也能让页码自己出现（不读就订阅不到）。
            val pageNoRev = currentHost.pageNumberRevision()
            val fromPageNumber = remember(fromPos, currentHost, pageNoRev, settings.pageNum, pageContent) {
                if (settings.pageNum && pageContent != null) currentHost.bookPageNumber(fromPos) else null
            }
            val targetPageNumber = remember(targetPosNow, currentHost, pageNoRev, settings.pageNum, targetContent) {
                if (settings.pageNum && targetContent != null) {
                    targetPosNow?.let { currentHost.bookPageNumber(it) }
                } else {
                    null
                }
            }
            // ---- 覆盖层（旁注 + 页码）：与页栅格分两张图，见 `PageOverlay.kt` ----
            // 页码几何：与旧 `PageNumberLabel` 同一套规格（只是产出数据、不产出 Composable）。
            // 右下角：右沿对齐正文右沿（页宽 - 版心右沿），不贴屏幕边。
            val pageNumEndInset = (pxWidth - contentRight).coerceAtLeast(0f)
            val fromPageNumberSpec = pageNumberLayoutOf(
                index = fromPageNumber?.index,
                pageHeightPx = pxHeight,
                contentBottomPx = contentBottom,
                bodyFontPx = profile.bodyPx,
                inkArgb = profile.fgColor,
                endInsetPx = pageNumEndInset,
            )
            val targetPageNumberSpec = pageNumberLayoutOf(
                index = targetPageNumber?.index,
                pageHeightPx = pxHeight,
                contentBottomPx = contentBottom,
                bodyFontPx = profile.bodyPx,
                inkArgb = profile.fgColor,
                endInsetPx = pageNumEndInset,
            )
            // 覆盖层样式：墨色/底色/字体/三个开关。基础样式取 `LocalTextStyle`——布点用的是
            // 同一份基础样式量出来的尺子，画字必须同源，否则折行/省略号会漂。
            val overlayBaseStyle = LocalTextStyle.current
            val overlayStyle = PageOverlayStyle(
                fgArgb = profile.fgColor,
                pageBgArgb = profile.bgColor,
                baseStyle = overlayBaseStyle,
                fontFamily = noteFontFamily,
                fontWeight = noteFontWeight,
                showBorder = settings.noteBorder,
                showBackground = settings.noteBackground,
                textSameColor = settings.noteTextSameColor,
            )
            val fromOverlay = PageOverlayContent(
                notes = noteBubbles,
                style = overlayStyle,
                pageNumber = fromPageNumberSpec,
            )
            val targetOverlay = PageOverlayContent(
                notes = targetNoteBubbles,
                style = overlayStyle,
                pageNumber = targetPageNumberSpec,
            )
            // 覆盖层栅格缓存 + 页槽纹理：两个槽各一份（见 `PageOverlayLayer` / `CurlPageImages`）。
            val fromOverlayLayer = rememberPageOverlayLayer()
            val targetOverlayLayer = rememberPageOverlayLayer()
            val currentImages = remember { CurlPageImages() }
            val targetImages = remember { CurlPageImages() }
            val overlayMeasurer = rememberTextMeasurer()
            // 画布本体（当前页 / 目标页同形，只是位移不同）：页栅格 + 覆盖层一次画完，
            // 顺手把两张图发布给卷曲层当纹理（屏幕上的像素与卷曲纹理是同一份）。
            val PageCanvasFun: @Composable (
                PageContent?,
                PageOverlayContent,
                PageOverlayLayer,
                CurlPageImages,
            ) -> Unit = { content, overlayContent, overlayLayer, images ->
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
                    overlay = overlayContent,
                    overlayLayer = overlayLayer,
                    // 旁注在左右留白、页码在下边距：都在版心外，覆盖层必须是整页大小。
                    overlayWidthPx = pxWidth.toInt(),
                    overlayHeightPx = pxHeight.toInt(),
                    overlayTextMeasurer = overlayMeasurer,
                    onImages = { pageTexture, overlayTexture ->
                        images.page = pageTexture
                        images.overlay = overlayTexture
                    },
                )
            }
            // 点旁注 = 打开这条标注的操作栏与输入框（有文字就带出来，可改可删）。
            // 翻页动画进行中不响应（旁注跟着页走，滑动/卷曲的页面上点一下不该弹编辑态）。
            // 选区恢复成这条标注的范围：编辑时两端手柄仍在，可以直接改范围。
            val openNote: (Long) -> Unit = { id ->
                val target = annotationState.annotationsFor(fromPos.chapter)
                    ?.firstOrNull { it.id == id }
                if (target != null && !ctl.isBusy()) {
                    openAnnotationEdit(target)
                }
            }

            // 卡片让位：整层（页画布 + 选区 + 旁注 + 放大镜）一起上推，顶部超出屏幕的部分直接截断。
            // 放在同一个 `graphicsLayer` 里 ⇒ 子层之间的相对位置不变，不可能出现「页动了字没动」。
            // 下拉书签的让位量也并进这一层：整页（含旁注/页码/选区）一起下移，松手弹簧归位。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        translationY = -shiftNow + bookmarkPull.value
                    }
                    .background(Color(profile.bgColor)),
            ) {
            // 两页槽（页栅格 + 覆盖层）：滑动与卷曲共用同一批内容，只是搬运方式不同——
            // 滑动整槽平移（槽里的东西天然一起动）；卷曲把槽画出来的两张图当纹理按曲面弯
            // （见 FlipCurlLayer）。旁注与页码的**像素**在覆盖层栅格里，槽里只剩它们的透明热区。
            val currentSlot: @Composable () -> Unit = if (slideFromCover && coverComposable != null) {
                coverComposable
            } else {
                {
                    PageCanvasFun(pageContent, fromOverlay, fromOverlayLayer, currentImages)
                    // 旁注和当前页同一个槽 ⇒ 一起滑出去，不会先「原地消失」再动画。
                    NoteBubbleHitAreas(
                        bubbles = noteBubbles,
                        onOpen = openNote,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            val targetSlot: (@Composable () -> Unit)? = when {
                slideToCover && coverComposable != null -> coverComposable
                targetContent != null -> {
                    {
                        PageCanvasFun(targetContent, targetOverlay, targetOverlayLayer, targetImages)
                        // 旁注和目标页同一个槽 ⇒ 同一个平移量，跟着一起滑进来。
                        NoteBubbleHitAreas(
                            bubbles = targetNoteBubbles,
                            onOpen = openNote,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                else -> null
            }
            // 封面也算一页纸：离开/进入封面时，卷曲层的那一槽换成封面烤图（纹理贴页角）。
            // 封面纹理还没就绪就退回滑动（宁可换一种翻法，不闪一帧空页）。
            val coverSession = slideFromCover || slideToCover
            val curlNow = curlEnabled && (coverSession || !coverVisible) &&
                (!coverSession || coverImages.page != null)
            if (curlNow) {
                FlipCurlLayer(
                    // 方向/进度/倾斜/是否在结算，全部传闭包：只在 draw 相位读 ⇒ 动画期间不重组阅读面。
                    direction = { ctl.direction.value },
                    progress = { if (ctl.slideActive(slideEnabled)) ctl.progress.value else 0f },
                    lean = { ctl.curlLean.value },
                    // 跟手拖动（起手页边点 → 手指页坐标）：非 null 时折痕改由「纸边压在手指上」
                    // 反解，只对下一页生效、松手即清（见 FlipController.curlPull）。
                    pull = { ctl.curlPull.value },
                    // 结算期间的阴影淡出系数：跟手那一帧起 1，末段才淡到 0；NaN = 点按/程序翻页，
                    // 由几何按进度两头淡（见 FlipController.curlFade）。
                    fade = { ctl.curlFade.value },
                    // 结算期间的倾斜扶正系数：跟手那一帧起 1；翻页在起手那一小段行程里就淡到 0，
                    // 回弹按剩余行程匀速退回，页角都不先向上冲（见 FlipController.curlLeanFade）。
                    // 折痕已在自由边之外的回弹还会被钉住，只让倾斜塌平（见 curl/settleFoldProgress）。
                    leanFade = { ctl.curlLeanFade.value },
                    // 只有结算动画才做扶正（手指跟手时形状听手指的，见 curl/CurlGeometry.kt）。
                    pageWidthPx = pxWidth,
                    pageHeightPx = pxHeight,
                    active = { ctl.slideActive(slideEnabled) },
                    paperArgb = profile.bgColor,
                    // 纹理位置跟着页槽走：正文的页栅格在版心、覆盖层贴页角；封面烤图是整页，贴 (0,0)。
                    currentTex = if (slideFromCover) Offset.Zero else bodyTex,
                    targetTex = if (slideToCover) Offset.Zero else bodyTex,
                    // 两张纹理由页槽自己发布（页栅格 + 覆盖层），缺图时页面保持不动；
                    // 封面槽没有页栅格，用烤好的整页图顶上。
                    current = if (slideFromCover) coverImages else currentImages,
                    target = when {
                        targetSlot == null -> null
                        slideToCover -> coverImages
                        else -> targetImages
                    },
                    currentSlot = currentSlot,
                    targetSlot = targetSlot,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FlipSlideLayer(
                    direction = ctl.direction.value,
                    // **不要在这里读 ctl.progress.value**：一旦在组合里读，动画每帧都会让整个
                    // 阅读面重组、两个画布每帧重走 drawLines（见 FlipSlideLayer KDoc）。
                    // 传闭包，让 progress 只在 graphicsLayer 的 draw 相位被读。
                    progress = { if (ctl.slideActive(slideEnabled)) ctl.progress.value else 0f },
                    pageWidthPx = pxWidth,
                    modifier = Modifier.fillMaxSize(),
                    target = targetSlot,
                    current = currentSlot,
                )
            }
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
            // 右上角书签图案：有书签时常驻红色；下拉时页面从图案下面让开，页首越过图案下沿才改色。
            BookmarkPullIndicator(
                pullPx = { bookmarkPull.value },
                maxPullPx = bookmarkPullMaxPx,
                armPx = bookmarkMarkBottomPx,
                bookmarked = bookmarkOnPage,
                visible = !coverVisible && !ctl.isBusy(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = BookmarkMarkEnd, top = bookmarkMarkTop),
            )
            // 标注卡片（D-28）：操作栏 + 输入行合成一张卡，贴键盘上沿。它不再跟选区锚点走，
            // 所以选区在页面任何位置都不用担心「菜单跑出屏幕」；被遮住时整页上顶（第 2 部分）。
            // 输入框与操作栏跟着「这一次写哪条」重置：编辑带出它的文字 / 类别 / 线型，新选区从空白开始。
            LaunchedEffect(editing?.id, selectionMenuOn) {
                // 换一条（新建 ↔ 编辑、或换编辑对象）就清掉「临时选过的颜色」：那是上一次的意图。
                notePickedColors.clear()
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
            // 输入区跟着选区走（用户定口）：正文修改卡开着时，拖两端手柄换一段选区——
            // 读者还没动过输入区里的字：输入区与落笔范围**一起**换成新选区的文字（否则确定时会把
            //   旧文字写进新范围）；
            // 动过字以后：输入区保留读者写的内容，只把落笔范围挪到当前选区——所见即所得，
            //   确定写的就是看得见的这一段，不必再重开卡片。
            // 区间没变就什么都不做：开卡那一刻选区本来就等于落笔范围，这条守卫让点按旧修改打开时
            // 保留它带出来的改后文字，不会被原文顶掉。
            LaunchedEffect(
                textEditOpen,
                textEditDirty,
                selection.chapter,
                selection.start,
                selection.endExclusive,
            ) {
                if (!textEditOpen) return@LaunchedEffect
                if (!selection.hasSelection) return@LaunchedEffect
                val ch = selection.chapter
                if (ch != textEditChapter) return@LaunchedEffect
                val s = selection.start
                val e = selection.endExclusive
                if (s == textEditStart && e == textEditEnd) return@LaunchedEffect
                // 整表修订的卡片目标永远是整张表：读者拖选其中一行（松手后选区只停在那一行）
                // 或拖手柄时，只动选区，不把卡片目标缩成那一行——否则确定时会把整张表写进一行。
                //
                // 但选区**离开了那条修订**（读者另选了别处的文字）就不该再挂着这张表：目标、
                // 输入区和「删除」都跟着新选区走。否则删除按钮仍按表上那条修订点亮，按下去删的是
                // 手上根本没选的那张表。读者先前没确定的字随之作废（他把选区挪走了）。
                if (textEditFromRevision && textEditWholeTable) {
                    if (s < textEditEnd && textEditStart < e) return@LaunchedEffect
                    setTextEditTarget(ch, s, e)
                    return@LaunchedEffect
                }
                if (textEditDirty) {
                    textEditStart = s
                    textEditEnd = e
                } else {
                    setTextEditTarget(ch, s, e)
                }
            }
            // 卡片**从屏幕下沿滑上来**、收起时再滑下去（D-30）。它自己已经按键盘高度上移（[AnnotationSheet]
            // 里的 imePx），所以滑入这一段里卡片的下半截正被升起的键盘挡着——看上去是「卡片 + 键盘」
            // 整整一块从底下推上来，而不是先弹一张卡片、再单独顶起键盘。
            // 没收动画时卡片一瞬消失、键盘还在半空；现在两者同一段时间里各自归位。
            // 在场由升起度决定：收起动画跑完才卸内容（降到 0 时卡片本来就已经在屏幕外面了）。
            if (menuOn || sheetReveal > 0f) {
                // 选区场景：选中的字里已有高亮/划线时，删除作用于**相交的第一条**（列表按起点排序）。
                val overlapping = if (editing == null && !textEditOn) {
                    annotationState.annotationsFor(fromPos.chapter)?.firstOrNull {
                        it.charStart < selection.endExclusive && selection.start < it.charEnd
                    }
                } else {
                    null
                }
                val deleteTarget = editing ?: overlapping
                // 正文修改：删除的对象是**这条修改本身**（覆盖选区的修订），不是标注——
                // 删掉就把那段字恢复成原文，与删高亮/划线/批注同一语义（D-36）。
                // 用**冻住的**修改范围判（与 [deleteTextEdit] 同一口径），不受拖手柄影响。
                val revisionTarget = if (textEditOn) {
                    chapterRevisions.firstOrNull { r ->
                        (r.charStart < textEditEnd && textEditStart < r.charEnd) ||
                            (r.charStart == r.charEnd && r.charStart in textEditStart..textEditEnd)
                    }
                } else {
                    null
                }
                AnnotationSheet(
                    modifier = Modifier.align(Alignment.BottomCenter),
                    visible = menuOn,
                    mode = if (textEditOn) NoteSheetMode.TextEdit else NoteSheetMode.Annotation,
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
                    text = if (textEditOn) textEditText else noteText,
                    dimMarkup = textEditOn,
                    errorText = if (textEditOn) textEditError else null,
                    // 输入框最多长到「卡片上沿贴屏幕顶、上方还留三行给点卡外取消」——
                    // 一次选很多行时 8 行就看不全了（用户复报）。剩余高度按窗口高 − 键盘高算。
                    maxCardHeightPx = (pxHeight - imeBottomPx).coerceAtLeast(0f),
                    palette = paletteFor(light.scheme),
                    kind = noteKind,
                    lineStyle = noteLineStyle,
                    highlightColors = annotationColorSlots(light, AnnotationKind.Highlight),
                    highlightDefaultSlot = annotationDefaultSlot(light, AnnotationKind.Highlight),
                    underlineColors = annotationColorSlots(light, AnnotationKind.Underline),
                    underlineDefaultSlot = annotationDefaultSlot(light, AnnotationKind.Underline),
                    // 编辑时只在本类色槽行里给这条记录描圈。
                    // 编辑时只在本类色槽行里给这条记录描圈；刚点过的那一格优先（还没落笔也看得见）。
                    currentColor = notePickedColors[noteKind] ?: if (editing != null && editing.kind == noteKind) {
                        editing.color ?: annotationFallbackColor(editing.kind)
                    } else {
                        null
                    },
                    // 正文修改：有修订才可删（删的是那条修改本身）；批注：有记录才可删。
                    canDelete = if (textEditOn) revisionTarget != null else deleteTarget != null,
                    onTextChange = {
                        if (textEditOn) {
                            textEditText = it
                            textEditDirty = true
                            // 读者一改字，上一条解析错误的提示就过期了。
                            textEditError = null
                        } else {
                            noteText = it
                        }
                    },
                    // 切类别就记住：不点色、不点线型直接「确定」也要把默认停到这一类
                    // （否则用过一次划线后，切回高亮落笔也改不回来）。
                    // 正文修改模式下点「高亮 / 划线」= 切回该类别批注（同一根操作栏，保留选区）。
                    onKindChange = { pickedKind ->
                        if (textEditOn) {
                            switchTextEditToAnnotation(pickedKind)
                        } else {
                            noteKind = pickedKind
                            // 只有新建时才算「用了一次」；给旧标注改类别不算（用户定口）。
                            if (editingAnnotation == null) {
                                val next = withAnnotationLastKind(light, pickedKind)
                                if (next != light) {
                                    light = next
                                    currentOnLightCommit(next)
                                }
                            }
                        }
                    },
                    onPickColor = { kind, slot -> pickNoteColor(kind, slot) },
                    onPickLineStyle = { pickNoteLineStyle(it) },
                    // 复制只进剪贴板：不动记录、也不关卡片（落笔只有「确定」与点卡片外两处）。
                    onCopy = {
                        if (editing != null) copyAnnotation(editing) else copySelection()
                    },
                    onDelete = {
                        if (textEditOn) deleteTextEdit() else deleteTarget?.let { annotation -> deleteAnnotation(annotation) }
                    },
                    onDone = { if (textEditOn) commitTextEdit() else commitNote() },
                    onCancel = { if (textEditOn) cancelTextEdit() else cancelNote() },
                    onEdit = { beginTextEdit() },
                    editEnabled = selection.hasSelection,
                    onHeightChange = { noteBarHeightPx = it },
                )
            }
            // 亮度/护眼遮罩：纯绘制于画布之上、栏之下。
            ReaderLightMask(light = maskLight, modifier = Modifier.fillMaxSize())
            // 顶/底栏：中部点按切换；条外点击收起。
            // 进面板：收栏与开面板同一帧发起；工具栏在抽屉之下退场，不会晾在面板边上。
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
                // 有完整页码就写页码（与落位同一换算），否则右端退回百分比。
                pageOfFraction = { f -> currentHost.bookPageNumberAtFraction(f.toDouble()) },
                onNight = currentOnNight,
                onSettings = { hideBarsThen { currentOnSettings() } },
                onToc = { hideBarsThen { currentOnToc() } },
                // 书签 / 笔记：与设置 / 目录 / 修订同一拍——收栏与开清单同时发起（栏在抽屉之下
                // 退场）。清单里点一条就是跳转；跳转一律从「工具栏已退出」的状态发起，
                // 落位后读者看到的是干净的一页。
                onBookmark = currentOnBookmark?.let { cb -> { hideBarsThen { cb() } } }
                    ?: { snackbar.show("当前版本不支持书签") },
                onNote = currentOnNote?.let { cb -> { hideBarsThen { cb() } } }
                    ?: { snackbar.show("笔记（规划中）") },
                // 修订：收栏与开清单同一帧发起（栏在抽屉之下退场，不会盖在晾着的工具栏上）。
                onRevision = currentOnRevision?.let { cb -> { hideBarsThen { cb() } } },
                // 审阅：切换后栏要留着（按钮高亮本身就是「现在看的是改动痕迹」的指示）。
                onReview = currentOnReview,
                reviewActive = reviewActive,
                nightActive = nightActive,
                showBookmark = settings.showToolbarBookmark,
                showNote = settings.showToolbarNote,
                showRevision = settings.showToolbarRevision,
                showReview = settings.showToolbarReview,
                showNight = settings.showToolbarNight,
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

/**
 * 单个 Job 的持有壳（手势协程间共享）：不参与组合状态，写它不该触发重组。
 * 用于「抬手后仍在跑的惯性滚动」这类动画句柄。
 */
private class JobSlot {
    var job: Job? = null
}

/**
 * 选区文字 → 正文修改输入区的初始内容（方案 §8.8）。
 *
 * 单叶：原样放进去（叶内 `\n` 就是 `<br>`）。
 * 跨叶：各叶之间插 `\n`，于是初始内容行数 = 叶数；读者删光行间文字就减少行数，
 * 提交时**结果段数 = 输入里的换行符数 + 1**（方案 D-34 修订版），少则并段、多则拆段。
 */
/**
 * 把行内样式区间从「平铺章文本」坐标换算到「输入区文字」坐标。
 *
 * 入参 [spans] 是**章文本绝对偏移**（与 [blockEnds]、[start] 同一把尺），返回相对
 * [start] 的输入区偏移。输入区在块与块之间插了 `\n`（见 [selectionTextForEdit]），
 * 所以块边界之后的偏移整体后移一位；段内 `<br>` 的换行本来就在章文本里，不用动。
 *
 * 边界上的两个端点口径不同：区间**起点**落在块边界上时属于后一块（在换行之后），
 * 区间**终点**落在边界上时属于前一块（在换行之前）。
 */
internal fun spansToEditText(
    spans: List<InlineMarkup.StyleSpan>,
    blockEnds: IntArray?,
    start: Int,
    endExclusive: Int,
): List<InlineMarkup.StyleSpan> {
    val ends = blockEnds?.takeIf { it.isNotEmpty() && it.last() >= endExclusive }
    val insertAt = ArrayList<Int>(4)
    if (ends != null) {
        for (end in ends) {
            if (end <= start) continue
            if (end >= endExclusive) break
            // **块尾可能指到同一个位置**（相邻两个叶，前一个是空的）：切段的
            // `selectionPiecesForEdit` 遇到重复只算一段，这里也必须一样，否则后面每一段
            // 都会整体往后挪一格（读者复报的「`Tab` 的 `T` 跑到标签外面」就是这条）。
            if (insertAt.isNotEmpty() && end == insertAt.last()) continue
            insertAt.add(end)
        }
    }
    fun starts(off: Int): Int {
        var n = 0
        for (position in insertAt) if (position <= off) n++
        return off - start + n
    }
    fun ends(off: Int): Int {
        var n = 0
        for (position in insertAt) if (position < off) n++
        return off - start + n
    }
    return spans.map { InlineMarkup.StyleSpan(starts(it.start), ends(it.endExclusive), it.sig) }
}

/**
 * 把选区上的行内结构分成「隐形」与「看得见」两份（输入区文字的偏移，长度为 [length]）。
 *
 * 整段都包着选区的元素（起点 ≤ 0 且终点 ≥ 全长）是**隐形**的：读者看不出边界在哪，
 * 卡片里摆一串 `` ` `` 或 `*` 只会让他以为结构是自己写的，提交时按整段套回更稳。
 * 只有边界切在选区内（元素露出半截）的元素才摊成标记。
 *
 * [alwaysVisible] 是**必须摊开**的那几条：读者这一次新写的标记（[FormatTraces.freshSpans]）。
 * 它们即使正好盖住整张卡也照实显示——那张卡就是这次改动的全部内容，藏起来读者看到的
 * 是一段没改过的普通文字，与审阅视图里那对绿 `**` 对不上（用户复报）。书本来就有的
 * 整段覆盖不在表里，仍旧隐形。
 *
 * 隐形不能越级：外层是局部的，内层即使整段覆盖也要显示——否则补回时会落到外层之外。
 */
internal fun splitAmbientSpans(
    spans: List<InlineMarkup.StyleSpan>,
    length: Int,
    alwaysVisible: List<InlineMarkup.StyleSpan> = emptyList(),
): Pair<List<InlineMarkup.StyleSpan>, List<InlineMarkup.StyleSpan>> {
    val ambient = ArrayList<InlineMarkup.StyleSpan>()
    val visible = ArrayList<InlineMarkup.StyleSpan>()
    val open = ArrayList<InlineMarkup.StyleSpan>(4)
    for (sp in spans.sortedWith(compareBy({ it.start }, { -it.endExclusive }))) {
        // ⚠ 用 `removeAt(size - 1)`，**不要**写 `removeLast()`：`ArrayList` 没有自己的 `removeLast`，
        //   解析到的是 JDK 21 的 `SequencedCollection.removeLast`（API 35 起才有），
        //   真机（Android 14 / API 34）一进这个函数就 `NoSuchMethodError` 闪退。
        while (open.isNotEmpty() && open.last().endExclusive <= sp.start) open.removeAt(open.size - 1)
        val parentAmbient = open.isEmpty() || ambient.contains(open.last())
        val coversAll = sp.start <= 0 && sp.endExclusive >= length
        if (coversAll && parentAmbient && sp !in alwaysVisible) ambient.add(sp) else visible.add(sp)
        open.add(sp)
    }
    return ambient to visible
}

/**
 * 卡片里「这一行**就是**书里那个行内元素本身」的包装区间（输入区文字的偏移）。
 *
 * 书里用 `display:block` 把 `<span>` / `<a>` 这类行内元素做成独立一行时，排版层把它切成单独
 * 一叶（见 [orilumn.reader.engine.revision.InlineSpans.ownTagOf]），卡片只摆那一行的字，
 * 标签就「消失」了。这里按 [lines] 与 [ownTags] 一一对应，给这类行补一层整行包装。
 *
 * 它的地位与块标记相同：**只活在卡片里**。提交时 [withoutOwnTags] 把它摘掉（叶元素本来就
 * 还在，进修订的只能是叶里的字与结构），所以它不进 `textEditOriginalSpans`、不参与 no-op 比较。
 */
internal fun ownTagSpans(
    lines: List<String>,
    ownTags: List<String?>?,
): List<InlineMarkup.StyleSpan> {
    if (ownTags.isNullOrEmpty()) return emptyList()
    val out = ArrayList<InlineMarkup.StyleSpan>(ownTags.size)
    var at = 0
    for ((i, line) in lines.withIndex()) {
        val tag = ownTags.getOrNull(i)
        if (tag != null && line.isNotEmpty()) {
            out.add(
                InlineMarkup.StyleSpan(at, at + line.length, InlineMarkup.StyleSig(tag)),
            )
        }
        at += line.length + 1
    }
    return out
}

/**
 * 提交前把「整行就是书里那个行内元素」的包装摘掉（它不属于修订里的结构：叶元素本来就留着）。
 *
 * 同时校验它还**在**：读者把标签删了 / 换成别的标签时，卡片里那一行就不再是那个块级元素了，
 * 而「把块级元素摘掉、让这一行并回上一行」是结构改写，卡片这一版做不到——直接拒绝并说清楚，
 * 别让读者以为自己改成了（标记悄悄失效比报错更糟）。
 *
 * 空行（读者把这一行的字删光了）不校验：那是在删这一行的内容，不是在删元素。
 *
 * @return (剩下的结构, 错误提示)；提示为 null = 一切照旧。
 */
internal fun withoutOwnTags(
    segments: List<InlineMarkup.Segment>,
    spans: List<InlineMarkup.StyleSpan>,
    ownTags: List<String?>?,
): Pair<List<InlineMarkup.StyleSpan>, String?> {
    // 结构为空也要查：读者把整行标签删光时 `spans` 正好是空表，那恰恰是最该拒绝的一种。
    if (ownTags.isNullOrEmpty()) return spans to null
    val drop = HashSet<Int>(2)
    var at = 0
    for ((i, seg) in segments.withIndex()) {
        val tag = ownTags.getOrNull(i)
        val end = at + seg.text.length
        if (tag != null && end > at) {
            // 外层在前：同标签多层时摘最外那层（那就是叶自己）。
            val hit = spans.indices
                .filter { it !in drop && spans[it].sig.tag == tag }
                .filter { spans[it].start <= at && spans[it].endExclusive >= end }
                .minWithOrNull(
                    compareBy({ spans[it].start }, { -spans[it].endExclusive }),
                )
            if (hit == null) {
                return spans to (
                    "这一行是书里单独成行的 <$tag>，删掉或换掉标签会把它并回上一行；" +
                        "这一版还改不了，请把 <$tag>…</$tag> 留着"
                    )
            }
            drop.add(hit)
        }
        at = end + 1
    }
    if (drop.isEmpty()) return spans to null
    return spans.filterIndexed { i, _ -> i !in drop } to null
}

/**
 * 提交前的形状校验：整块图片与表格行的**结构不能借这张卡改掉**。
 *
 * - 图片块：输入后只能是原样的 `U+FFFC` 占位（保留）或空串（整张删除）；换成文字会悄悄
 *   把图变成正文，拒绝并提示；
 * - 表格行：必须仍能被认成表格行，且格数 / 表头 / 合并方式与原来相同；格里的字允许变长变短
 *   （读者就是在改格子里的字），列数、合并方式一变就拒绝。
 *
 * @return 错误提示；null = 形状没变（或这一处本来就没有图片 / 表格）。
 */
internal fun textEditShapeError(
    segments: List<InlineMarkup.Segment>,
    tables: List<TableRowInfo?>,
    images: List<Boolean>,
    wholeTable: Boolean = false,
): String? {
    if (tables.isEmpty() && images.isEmpty()) return null
    if (wholeTable && images.none { it }) {
        // 选中的正好是一整张表：行数、每行格数、合并方式都可以改，只要整张表还是自洽的网格。
        // 一行表格行都不剩（读者把字删光了）= 删掉整张表。
        val rows = segments.mapNotNull { it.table }
        val deleting = segments.all { it.text.isEmpty() } && rows.isEmpty()
        if (deleting) return null
        if (rows.size != segments.size) {
            return "表格里混进了非表格行：一行要么是表格行，要么整张表一起删掉"
        }
        if (!TableGrid.consistent(rows)) {
            return "表格的行列对不上：每一行的列数必须与整张表一致（合并格也不能重叠或留空）"
        }
        return null
    }
    // 选区全落在表格行上（可能只是其中连续几行，没覆盖整张表）：行列结构也放行，
    // 由提交侧把未选中的行一起拼回**整张表**、按 HTML 表格的铺格规则校验。
    // 这里只看卡片本身：全是表格行，或者空卡片（读者把选中的行删了）。
    if (images.none { it } && tables.isNotEmpty() && tables.all { it != null } &&
        (segments.isEmpty() || segments.all { it.table != null || it.text.isEmpty() })
    ) {
        return null
    }
    val n = maxOf(tables.size, images.size, segments.size)
    for (i in 0 until n) {
        val image = images.getOrNull(i) == true
        val table = tables.getOrNull(i)
        if (!image && table == null) continue
        val text = segments.getOrNull(i)?.text
        if (image) {
            if (text != null && text.isNotEmpty() && text != "\uFFFC") {
                return "图片只能整张删除，不能改成文字"
            }
            continue
        }
        if (text == null) return "表格行不能和别的段落混在一起改；请只选中表格的整行（可以只选其中几行）"
        val now = segments[i].table
            ?: return "表格行不能和别的段落混在一起改；请只选中表格的整行（可以只选其中几行）"
        if (!table!!.sameShape(now, ignoreHeader = !now.html)) {
            return "要改列数或合并方式，请只选中表格的整行（不夹别的段落）"
        }
    }
    return null
}

/** 打开一条已有修订时输入区的三件套：文字（带标记）、原件结构、隐形结构。 */
internal class EditCard(
    val text: String,
    val originalSpans: List<InlineMarkup.StyleSpan>?,
    val ambientSpans: List<InlineMarkup.StyleSpan>,
)

/** [revisionCardOf] 的结果：卡片显示文字 + 提交时的纯文本。 */
internal class RevisionCard(val card: EditCard, val plain: String)

/**
 * 一条修订在修改卡里的**当前结果**（不是审阅视图里的「原文 + 新文字」对照）。
 *
 * 普通文字修订的 `editedText` 只记了**读者当时选中的那一段**；如果这条修订后来吸收了
 * 前后的公共字（在改过的段里再改一处），`editedText` 就不再等于整段改后的内容。重开卡片
 * 必须用 [TextRevision.text]（整段替换文字）重建，否则确认时会把没显示出来的前后文丢掉。
 *
 * 表格修订（`editedBlocks` 里有 `t…` / 首行 `T…`）仍用 `editedText` / `editedBlocks`：
 * 整表修订的 `editedText` 本来就是整张表（未选中的行也拼回去了）。
 */
internal fun revisionCardOf(
    revision: TextRevision,
    oldSpans: List<InlineMarkup.StyleSpan>? = null,
    ownTags: List<String?>? = null,
): RevisionCard {
    val tableLike = revision.editedBlocks.any { it.startsWith("t") || it.startsWith("T") }
    if (tableLike) {
        val plain = revision.editedText
        return RevisionCard(editCardOf(plain, revision.editedBlocks, revision.editedStyles), plain)
    }
    // 段界字符 U+E000 与卡片里的 `\n` 都是一位：按段切开再用 `\n` 拼回去，长度与偏移都不变。
    val segments = RevisionBlocks.split(revision.text)
    val kinds = revision.blockKinds
    val hasKinds = kinds.size == segments.size
    val lines = ArrayList<String>(segments.size)
    val blocks = ArrayList<String>(segments.size)
    for ((i, seg) in segments.withIndex()) {
        lines += seg
        blocks += if (hasKinds) kinds[i] else ""
        // 段内的 `\n`（`<br>` / 代码块换行）是续行：重开时并回上一块。
        repeat(seg.count { it == '\n' }) { blocks += RevisionBlocks.CONTINUATION }
    }
    val plain = lines.joinToString("\n")
    // `styles` 是最小修订上的结构（含前后文）；老修订没存过它时，只有在输入区全文与整段
    // 改后文字完全一致的情况下才能拿 `editedStyles` 顶上（否则偏移对不上，宁可不套）。
    val stored = revision.styles
        ?: revision.editedStyles?.takeIf { revision.editedText == plain }
    // 原书结构只在宿主给得出时用（它已经按「文字逐字没变」筛过）：单段才回比，
    // 多段时卡片里多了 `\n`，偏移对不上。给了就按审阅视图同一套判据摊开新标记。
    val old = oldSpans?.takeIf { segments.size == 1 }
    return RevisionCard(editCardOf(plain, blocks, stored, old, ownTags), plain)
}

/**
 * 按落库的**输入区全文结构**重建修改卡内容（打开旧修改 / 点审阅痕迹）。
 *
 * 落库有两份区间：`TextRevision.styles` 是最小修订上的（重放用），`TextRevision.editedStyles`
 * 是输入区全文上的（回显用）。公共前后缀被裁掉的那一截只有后者还带着结构 —— 只读前者会把
 * `<code>` 之类的元素丢掉，读者再确认一次就真的把它们删了。
 *
 * [blocks] 是输入区**每行**的块形态（`TextEditRevision.editedBlocks`）：块级的 `# ` / `> ` /
 * 围栏按它回显，续行并回上一块。空表 = 老修订没存过（按纯文本开卡，不改任何块的形态）。
 *
 * [stored] 为 null = 老修订没存过输入区结构（或这次提交本来就没有结构）：结构按纯文本开卡，
 * 文字仍要转义——原文里的 `*` `~` `<` 是普通文字，不转义就会被输入区当成标记解析。
 */
internal fun editCardOf(
    editedText: String,
    blocks: List<String>,
    stored: List<orilumn.reader.data.revision.RevisionStyleSpan>?,
    oldSpans: List<InlineMarkup.StyleSpan>? = null,
    ownTags: List<String?>? = null,
): EditCard {
    val spans = stored?.let(InlineMarkup::fromStored)
    val segments = orilumn.reader.engine.revision.RevisionBlocks.cardSegments(editedText, blocks)
        ?: listOf(InlineMarkup.Segment(editedText))
    // 读者这次新写的标记必须看得见（与审阅视图的痕迹同一个判据）：书本来就有的整段覆盖
    // 仍然隐形，读者新加的整段覆盖照实摊开。
    val fresh = if (oldSpans != null && spans != null) {
        FormatTraces.freshSpans(editedText, oldSpans, spans)
    } else {
        emptyList()
    }
    val (ambient, visible) = splitAmbientSpans(spans.orEmpty(), editedText.length, fresh)
    // 「这一行本身就是书里那个行内元素」的包装：只给显示（它天生盖住整行，不走隐形那条），
    // 不进 [EditCard.originalSpans]——提交时由 `withoutOwnTags` 摘掉，不算修订里的结构。
    val own = ownTagSpans(segments.map { it.text }, ownTags)
    return EditCard(
        text = InlineMarkup.show(segments, visible + own),
        originalSpans = spans,
        ambientSpans = ambient,
    )
}

/**
 * 选区所在**段**的渲染空间区间（`[from, to)`）；取不到块界就退回选区本身。
 *
 * 正文修改用它取「同段的行内结构」当模板（规格 §4.3）：读者新写的 `*新*` 要长得像同段已有的
 * 斜体，而那些斜体常常落在选区之外。跨段选区只取**首段**——提交后首段就是读者落笔的那一段。
 */
internal fun paragraphRangeOf(
    blockEnds: IntArray?,
    textLength: Int,
    start: Int,
    endExclusive: Int,
): IntRange? {
    if (textLength <= 0) return null
    val s = start.coerceIn(0, textLength)
    val e = endExclusive.coerceIn(s, textLength)
    if (e <= s) return null
    val ends = blockEnds?.takeIf { it.isNotEmpty() && it.last() == textLength } ?: return s until e
    var from = 0
    for (end in ends) {
        if (s < end) return if (end > from) from until end else null
        from = end
    }
    return null
}

/**
 * 重开这条修订时，输入区里**一个能看见的字都没有**（读者只加了一个换行 / 空白，且没删掉任何字）。
 *
 * 落库的是**最小修订**：只加一个 `<br>` 或段界时，替换文本就那一个换行，卡片按它开出来只剩
 * 行尾一个 `\`，读者既看不出换行落在哪两个词之间，也认不出改的是哪一段。这种要扩到所在整块再开卡。
 *
 * 只认「纯插入、插入的内容全是空白」：
 * - 删字 / 换字的修订卡片里本来就有字，不需要上下文；
 * - 审阅视图里扩块要拿**显示文字**当卡片文字，而那里还排着被删掉的原文——只有纯插入（没删字）
 *   才保证显示文字就是改后结果，扩块直接确定才恒等。
 */
internal fun revisionNeedsBlockContext(revision: TextRevision): Boolean {
    val inserted = RevisionBlocks.renderedLength(revision.text)
    // 原区间长度 = 替换文本长度 − delta；不为 0 说明删过原文，不是纯插入。
    if (inserted - revision.delta != 0) return false
    return inserted > 0 && RevisionBlocks.textOf(revision.text).isBlank()
}

internal fun selectionTextForEdit(
    chapterText: String,
    blockEnds: IntArray?,
    start: Int,
    endExclusive: Int,
): String = selectionPiecesForEdit(chapterText, blockEnds, start, endExclusive).joinToString("\n")

/**
 * 与 [selectionTextForEdit] 同一切法，但返回**每块的文字**（块内换行原样留在块里）。
 *
 * 正文修改卡按块处理：块与块的边界是「段界」（提交时才分块），块内的 `\n` 是书里本来的
 * 换行（`<br>` / 代码块），两者的差别只有这里分得出来。
 */
internal fun selectionPiecesForEdit(
    chapterText: String,
    blockEnds: IntArray?,
    start: Int,
    endExclusive: Int,
): List<String> {
    val s = start.coerceIn(0, chapterText.length)
    val e = endExclusive.coerceIn(s, chapterText.length)
    val ends = blockEnds?.takeIf { it.isNotEmpty() && it.last() == chapterText.length }
        ?: return listOf(chapterText.substring(s, e))
    val out = ArrayList<String>(ends.size)
    var from = s
    for (end in ends) {
        if (end <= from) continue
        if (from >= e) break
        val segEnd = minOf(end, e)
        out.add(chapterText.substring(from, segEnd))
        from = segEnd
    }
    if (out.isEmpty()) out.add(chapterText.substring(s, e))
    return out
}
