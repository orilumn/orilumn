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
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    settings: ReaderSettings,
    statusBarInset: Dp = 0.dp,
    debugActive: Boolean = false,
    onBack: () -> Unit = {},
    onNight: () -> Unit = {},
    onSettings: () -> Unit = {},
    onToc: () -> Unit = {},
    onBookmark: (() -> Unit)? = null,
    onNote: (() -> Unit)? = null,
    onDebug: () -> Unit = {},
    onLightChange: (ReaderSettings) -> Unit = {},
    onLightCommit: (ReaderSettings) -> Unit = {},
    /** Q1-b：顶/底栏显隐同步（Android 宿主据此显隐系统栏 chrome；桌面/其它平台可忽略）。 */
    onBarsVisibleChanged: (Boolean) -> Unit = {},
    /** Q1-b：外部推送的落位（版式重排绑定 / 目录跳转落地后同步）。非空即采用为当前定位并防抖
     *  保存；null 不动作（重排以同一目标字符合位，字符仍在新分页表里即可连续阅读）。 */
    externalPos: ReaderPos? = null,
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
    // 上次链接点按命中的抬起时刻（uptimeMillis；三区防抖用，见 onTap）。
    var lastLinkTapMs by remember { mutableStateOf(0L) }

    val scope = rememberCoroutineScope()
    val currentHost by rememberUpdatedState(host)
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnNight by rememberUpdatedState(onNight)
    val currentOnSettings by rememberUpdatedState(onSettings)
    val currentOnToc by rememberUpdatedState(onToc)
    val currentOnBookmark by rememberUpdatedState(onBookmark)
    val currentOnNote by rememberUpdatedState(onNote)
    val currentOnDebug by rememberUpdatedState(onDebug)
    val currentOnLightChange by rememberUpdatedState(onLightChange)
    val currentOnLightCommit by rememberUpdatedState(onLightCommit)
    val currentOnBarsVisibleChanged by rememberUpdatedState(onBarsVisibleChanged)
    // 手势 handler 常驻（key 只有 touchSlop）：栏显隐/栏高经引用读，不重启手势流。
    val currentBarsVisible by rememberUpdatedState(barsVisible)
    val currentTopBarH by rememberUpdatedState(topBarH)
    val currentBotBarH by rememberUpdatedState(botBarH)

    // 打开书籍并定位起始页（自动续读/首页）。
    LaunchedEffect(currentHost) {
        val p = currentHost.open()
        openFailed = p == null
        openPos = p
    }

    // 落位统一入口：更新当前定位并防抖保存（复刻 Android scheduleSave 500ms）。
    fun markPositionChanged(next: ReaderPos) {
        openPos = next
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            currentHost.onSaveProgress(next)
            saveJob = null
        }
    }

    // Q1-b：外部推送落位（键 = externalPos，值变化即认领；重排绑定/TOC 落地后 activity 推送）。
    LaunchedEffect(externalPos) {
        val p = externalPos ?: return@LaunchedEffect
        openFailed = false
        markPositionChanged(p)
    }

    // ---- 定位动作（host 为 suspend，统一挂到本组件作用域） ----
    fun flip(direction: Int) {
        val p = openPos ?: return
        scope.launch {
            currentHost.adjacent(p, direction)?.let { markPositionChanged(it) }
        }
    }

    fun jumpChapter(direction: Int) {
        val p = openPos ?: return
        scope.launch {
            currentHost.neighborChapterStart(p.chapter, direction)?.let { markPositionChanged(it) }
        }
    }

    fun seek(fraction: Float) {
        val p = openPos ?: return
        scope.launch {
            currentHost.pageAtFraction(fraction.toDouble())?.let { markPositionChanged(it) }
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
        scope.launch { currentHost.openLink(target)?.let { markPositionChanged(it) } }
        return true
    }

    fun onTap(xPx: Float, yPx: Float, widthPx: Float, atMs: Long = 0L) {
        // P4-c2u: 链接优先——点中链接字形即导航，未中才走三区（翻页/栏显隐）。
        if (tryOpenLinkAt(xPx, yPx)) {
            lastLinkTapMs = atMs
            return
        }
        // 手抖防抖：刚跳过链接，短窗内的三区点按吞掉（否则第二下常把新页翻走）。
        if (ReaderMath.linkTapDebounced(atMs, lastLinkTapMs)) return
        when (ReaderMath.tapZone(xPx, widthPx)) {
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
                    var brightnessActive = false
                    var brightnessBase = ReaderMath.MAX_BRIGHTNESS
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (barsOwned) {
                            if (!change.pressed) break
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
                            else if (dragDir != 0) flip(dragDir)
                            else if (upTime - downTime < ReaderMath.TAP_MAX_MS) {
                                onTap(downX, downY, size.width.toFloat(), upTime)
                            }
                            break
                        }
                        val dx = change.position.x - downX
                        val dy = change.position.y - downY
                        if (axis == ReaderMath.Axis.NONE) {
                            axis = ReaderMath.gestureAxis(dx, dy, touchSlop)
                        }
                        when (axis) {
                            ReaderMath.Axis.HORIZONTAL -> if (dragDir == 0) dragDir = ReaderMath.flipDirection(dx)
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
                    when (ReaderMath.keyAction(event.key)) {
                        ReaderMath.ReaderKeyAction.Prev -> { flip(-1); true }
                        ReaderMath.ReaderKeyAction.Next -> { flip(1); true }
                        ReaderMath.ReaderKeyAction.MiddleTap -> { onTap(pxWidth / 2f, pxHeight / 2f, pxWidth); true }
                        null -> false
                    }
                },
        ) {
        val pos = openPos
        if (pos != null) {
            val fraction = remember(pos) {
                currentHost.pageProgress(pos).toFloat().coerceIn(0f, 1f)
            }
            val chapterTitle = remember(pos) { currentHost.unitTitle(pos.chapter) }
            val lines = remember(pos, contentRevision) { currentHost.pageLines(pos) }
            // 盒背景/边框：与行同一切片口径，画布内画在文字之下（翻页即随 pos 刷新）。
            val pageBackgrounds = remember(pos, contentRevision) {
                runCatching { currentHost.pageBackgrounds(pos) }.getOrNull()
            }
            // 插图几何与位图：几何同步取（廉价），位图异步解码后按图缓存；
            // 翻页（新 pos）即清空旧图，避免旧页图片闪留。
            val pageImages = remember(pos, contentRevision) {
                runCatching { currentHost.pageImages(pos) }.getOrNull()
            }
            var imageBitmaps by remember(pos, contentRevision) { mutableStateOf<Map<orilumn.reader.engine.skia.PageImage, ImageBitmap>>(emptyMap()) }
            LaunchedEffect(pos, contentRevision, pageImages) {
                val imgs = pageImages?.takeIf { it.isNotEmpty() } ?: run {
                    imageBitmaps = emptyMap()
                    return@LaunchedEffect
                }
                // 并行解码（摄影类一页多图时串行要几秒，全程灰块）：每图一个 async，
                // 先全部并发再统一收敛，避免逐张 setState 反复重组。
                val deferred = imgs.map { img ->
                    async {
                        img to runCatching { currentHost.loadPageImage(img) }.getOrNull()
                    }
                }
                val out = deferred.awaitAll()
                    .mapNotNull { pair -> pair.second?.let { pair.first to it } }
                    .toMap(LinkedHashMap(imgs.size))
                imageBitmaps = out
            }
            // P3-b 背景图：按 bgKey 去重（同图多盒只解一次），异步解码后按 url 缓存；
            // 翻页（新 pos）即清空，避免旧页底图闪留。失败项直接缺席（該幅只留底色）。
            var bgImages by remember(pos, contentRevision) { mutableStateOf<Map<String, orilumn.reader.engine.skia.DecodedImage>>(emptyMap()) }
            LaunchedEffect(pos, contentRevision, pageBackgrounds) {
                val refs = pageBackgrounds?.mapNotNull { bg ->
                    bg.bgSrc?.takeIf { it.isNotBlank() }?.let { bg.bgChapterHref to it }
                }?.distinct().orEmpty()
                if (refs.isEmpty()) {
                    bgImages = emptyMap()
                    return@LaunchedEffect
                }
                val deferred = refs.map { ref ->
                    async {
                        "${ref.first}|${ref.second}" to runCatching {
                            currentHost.loadBackgroundImage(ref.first, ref.second)
                        }.getOrNull()
                    }
                }
                bgImages = deferred.awaitAll()
                    .mapNotNull { pair -> pair.second?.let { pair.first to it } }
                    .toMap(LinkedHashMap(refs.size))
            }

            // 画布：行窗口经 LineWindowDrawer 落到 skiko Canvas（见 ReaderPageCanvas）。
            ReaderPageCanvas(
                lines = lines,
                contentLeft = contentLeft,
                contentTop = contentTop,
                contentRectLeft = contentLeft,
                contentRectTop = contentTop,
                contentRectRight = contentRight,
                contentRectBottom = contentBottom,
                pageBg = profile.bgColor,
                inkColor = profile.fgColor,
                modifier = Modifier.fillMaxSize(),
                pageImages = pageImages,
                imageBitmaps = imageBitmaps,
                pageBackgrounds = pageBackgrounds,
                bgImages = bgImages,
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
                onDebug = currentOnDebug,
                debugActive = debugActive,
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