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
    var coverResolving by remember { mutableStateOf(false) }
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

    val scope = rememberCoroutineScope()
    // 锚页事件串行漏斗：显示状态的唯一写入通道（见 AnchorFunnel）。所有改锚页位置的动作
    // （翻页/跳转/开书/外部落位/开链接）走它串行，后到按落定后的最新位置重取源，不再各算各的。
    val anchorFunnel = remember { AnchorFunnel() }
    val currentHost by rememberUpdatedState(host)
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

    // 打开书籍并定位起始页（自动续读/首页）。落定即推代际：同 pos 也刷新行数据。
    // 经锚页漏斗：与在途导航互斥，首帧后放行。漏斗 BUSY-DROP 时补一次重试，
    // 否则 open 是一次性事件——被吞即永久空白，无下一次点按来救。
    LaunchedEffect(currentHost) {
        suspend fun doOpen(): ReaderPos? {
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
            coverDismissed = false
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
            // 首章号查不到退避重试（open 并发期布局未就绪是常态，最多约 1.2s）。
            if (coverBmp != null && coverStartChapter == null) {
                repeat(6) {
                    coverStartChapter = try {
                        currentHost.bookStart()?.chapter
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
        if (!isBookStart(next)) coverDismissed = true
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
    fun flip(direction: Int) {
        // 封面页内翻页：前进回正文第一页（现查 fresh 落位，走漏斗，可存档），
        // 落定即记显式离开（否则推送带来的 effect 重算又弹回去）；封面已是第一页，后退无操作。
        if (coverVisible) {
            if (direction > 0 && coverBmp != null) {
                scope.launch {
                    val target = runCatching { currentHost.bookStart() }.getOrNull() ?: return@launch
                    val landed = anchorFunnel.navigate(
                        action = "cover-forward",
                        read = { target },
                        commit = ::markPositionChanged,
                    ) { target }
                    if (landed != null) {
                        coverDismissed = true
                        coverVisible = false
                    }
                }
            }
            return
        }
        // 正文第一页回翻且有封面 → 进封面（只读，不经过引擎翻页/存档）。
        if (direction < 0 && isBookStart(openPos)) {
            coverVisible = true
            return
        }
        scope.launch {
            anchorFunnel.navigate(
                action = "tap-flip",
                read = { resolveNavPos("tap-flip") },
                commit = ::markPositionChanged,
            ) { p ->
                Logger.d("Orilumn.TAP", "tap-flip dispatch dir=$direction from ch=${p.chapter} char=${p.slice?.charStart}")
                val landed = currentHost.adjacent(p, direction)
                // 引擎侧无声吞翻页时（返回 null），这里是唯一目击者——与 Orilumn.FLIP 对账。
                Logger.d("Orilumn.TAP", "tap-flip result dir=$direction " + (landed?.let { "landed ch=${it.chapter} char=${it.slice?.charStart}" } ?: "NULL (engine returned null)"))
                landed
            }
        }
    }

    fun jumpChapter(direction: Int) {
        scope.launch {
            anchorFunnel.navigate(
                action = "jump-chapter",
                read = { resolveNavPos("jump-chapter") },
                commit = ::markPositionChanged,
            ) { p -> currentHost.neighborChapterStart(p.chapter, direction) }
        }
    }

    fun seek(fraction: Float) {
        scope.launch {
            anchorFunnel.navigate(
                action = "seek",
                read = { resolveNavPos("seek") },
                commit = ::markPositionChanged,
            ) { currentHost.pageAtFraction(fraction.toDouble()) }
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
                            else if (dragDir != 0) flip(dragDir)
                            else if (upTime - downTime < ReaderMath.TAP_MAX_MS) {
                                onTap(downX, downY, size.width.toFloat())
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
            // 封面页：拉伸全屏（默认）/等比居中（coverProportional 开），之上同样压遮罩；
            // 栏与提示与正文同制（标题取书名、进度 0），避免封面页无处进目录/设置。
            ReaderCoverPage(
                cover = cover,
                proportional = light.coverProportional,
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
            val lines = remember(pos, contentRevision, hostRevision) { currentHost.pageLines(pos) }
            // 盒背景/边框：与行同一切片口径，画布内画在文字之下（翻页即随 pos 刷新）。
            val pageBackgrounds = remember(pos, contentRevision, hostRevision) {
                runCatching { currentHost.pageBackgrounds(pos) }.getOrNull()
            }
            // 插图几何与位图：几何同步取（廉价），位图异步解码后按图缓存；
            // 翻页（新 pos）即清空旧图，避免旧页图片闪留。
            val pageImages = remember(pos, contentRevision, hostRevision) {
                runCatching { currentHost.pageImages(pos) }.getOrNull()
            }
            var imageBitmaps by remember(pos, contentRevision, hostRevision) { mutableStateOf<Map<orilumn.reader.engine.skia.PageImage, ImageBitmap>>(emptyMap()) }
            LaunchedEffect(pos, contentRevision, hostRevision, pageImages) {
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
            var bgImages by remember(pos, contentRevision, hostRevision) { mutableStateOf<Map<String, orilumn.reader.engine.skia.DecodedImage>>(emptyMap()) }
            LaunchedEffect(pos, contentRevision, hostRevision, pageBackgrounds) {
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
                // 字重这类纯字形变更行数据完全相等，靠修订号强制重画（见 ReaderPageCanvas）。
                contentRevision = contentRevision,
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