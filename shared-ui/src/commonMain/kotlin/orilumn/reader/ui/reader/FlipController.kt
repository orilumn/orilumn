package orilumn.reader.ui.reader

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import orilumn.reader.io.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import orilumn.reader.ui.reader.curl.CURL_TAIL_FADE_SPAN
import orilumn.reader.ui.reader.curl.CurlAnchor
import orilumn.reader.ui.reader.curl.CurlPull
import orilumn.reader.ui.reader.curl.PULL_INTRO_MS
import orilumn.reader.ui.reader.curl.anchoredProgress
import orilumn.reader.ui.reader.curl.leanOf
import orilumn.reader.ui.reader.curl.pullEngage
import orilumn.reader.ui.reader.curl.settleFoldProgress
import orilumn.reader.ui.reader.curl.settleLeanFade
import orilumn.reader.ui.reader.curl.settleLeanRewind

/**
 * 滑动翻页会话的**唯一宿主**（用户层·与 [FlipSession] 配套）。
 *
 * ## 为什么要有这一层
 *
 * 早先这些状态与守卫全部平铺在 [ReaderScreen] 里：9 个 `var`（`slideFromPos` /
 * `slideTargetPos` / `slideProgress` / `slideDirection` / `slidePrefetch` /
 * `settleJob`）+ 8 处各写一遍的
 * `if (!slideEnabled) return`。判据没有单点，于是「三处都判了、漏了一处」
 * 成了常态，先后踩过三次：
 *
 *  1. 回弹闪烁：`rollbackSlide` 里 `clearSlide()` 排在数据翻回**之前**；
 *  2. 白屏：等图在调用点重复了一次，与下一次手势抢漏斗锁 ⇒ 连锁 BUSY-DROP；
 *  3. 动画开关关掉后仍在滑动：两条入口（拖动/点按）都正确退化成瞬切，
 *     但**渲染层没有守卫**——[FlipSlideLayer] 是「始终在场」的（为修另一个 bug），
 *     于是它照旧按 `slideProgress` 位移，而它不在任何一处 `!slideEnabled` 里。
 *
 * 共同的形状都是同一个：**同一个事实被复制到多处判据，其中一处漏改**。
 * 所以这一层做的事只有一件——把「当前该不该滑动/动画在不在飞」收成
 * [slideActive] / [isSettling] 两个**唯一出口**，渲染层也必须走它们。
 *
 * ## 与 [FlipSession] 的分工
 *
 * [FlipSession] 是**纯状态机**（无 Compose 依赖、可 jvmTest 直测），只回答
 * 「progress 是多少」与「该 commit 还是 rollback」。本类负责**副作用与时序**：
 * 落位、等图、清场、动画协程，以及把状态以 Compose state 的形式喂给渲染层。
 *
 * ## 宿主能力注入
 *
 * 落位（[flipAwait]）与强制对齐（[forceOpenPos]）靠宿主，故构造注入。等图**不在
 * 这里**——它在 [AnchorFunnel.beforeCommit] 内、commit 之前完成；调用点再等一次
 * 既冗余又会与下一手势抢漏斗锁（真机日志：连锁 BUSY-DROP ⇒ 白屏）。**不允许**本类自己另开落位通道——显示状态的唯一写入通道是
 * [AnchorFunnel]（见 [ReaderScreen]）。
 */
class FlipController(
    /** 落位并等待结果；返回真正落定的页，边界/失败/BUSY-DROP 返回 null。 */
    private val flipAwait: suspend (Int) -> ReaderPos?,
    /** 强制把 [ReaderScreen.openPos] 拉回指定页（回滚重试仍失败时的自洽兜底）。 */
    private val forceOpenPos: (ReaderPos) -> Unit,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    /**
     * 是否允许真跑结算动画。单测环境没有 Compose 的 `MonotonicFrameClock`，
     * `Animatable.animateTo` 会直接抛异常，故测试传 false 只走状态机。
     */
    private val allowAnimation: Boolean = true,
    /**
     * 平台结算时长倍率（见 [FlipSession.settleDurationMs] 的 `scale`）：桌面传 2。
     *
     * 桌面没有「手指先拖半页」那一段垫底，一按就从 0 走满整页，300ms 在宽屏上就是瞬切；
     * 平板有跟手段，同一口径手感正好，故只有桌面放大。
     */
    private val settleScale: Float = 1f,
) {
    /**
     * 渲染用的位移进度（0..1）+ 方向（+1 下一页 / -1 上一页）。
     *
     * 持 [MutableFloatState] **对象**而非委托：它们要整体交给渲染层
     * （`ctl.progress.value`）与手势层，`by` 委托每次访问都要解包，且 `private set`
     * 与委托冲突。Compose 读的是 state 对象本身，写 `xxx.value` 即触发重组。
     */
    val progress: MutableFloatState = mutableFloatStateOf(0f)
    val direction: MutableIntState = mutableIntStateOf(0)

    /**
     * 卷曲手感：本次拖动的起止点（见 `curl/CurlPull`）。
     *
     * 参考实现 Moon+ 的折痕**恒垂直于拖动方向**，而拖动方向是「手指相对起手页边的位移」。
     * 只记手指位置不够：同一点上，横着拖和斜着拖的折痕角度不同。上一页也记
     * （起手边换成左缘），它的折痕同样该垂直于手指轨迹。
     *
     * 只在拖动中由 [updateCurlPull] 写；松手即清（结算期间折痕听进度）。
     */
    val curlPull: MutableState<CurlPull?> = mutableStateOf(null)

    /**
     * 折痕倾斜量（[orilumn.reader.ui.reader.curl.CurlShape.lean] 的输入，0 = 竖直折痕）。
     *
     * 拖动中由 [updateCurlPull] 跟着拖动方向实时算（见 `curl/leanOf`）——**是斜率**，
     * 不是旧版 `dy/|d|` 的正弦：斜拖时只有斜率才让折痕真的垂直于手指轨迹。松手后保持，
     * 结算从同一个角度接着走，折痕不会跳一下。
     */
    val curlLean: MutableFloatState = mutableFloatStateOf(0f)

    /**
     * 本次手势里**外向**（沿翻页方向）走得最远的横向距离（px，只增不减，见
     * `curl/CurlPull.spanX`）。
     *
     * 折痕锚点的倾斜分母用它，收回时折痕只平移、不转正。只给卷曲跟手用：起手清零，
     * [updateCurlPull] 逐帧取最大值。
     */
    private var curlSpanX = 0f

    /**
     * 结算期间的**阴影淡出系数**（0..1，见 `curl/CurlShape.shadowFade`）。
     *
     * 跟手时恒 1（形状听手指）；松手结算从**跟手那一帧**接手，逐帧淡到 0。不能照进度
     * 重新算（`tailFade`）：页角折起来时屏幕可见进度常常是负的，`tailFade(0)` 会在松手
     * 那一帧把阴影直接归零、折角整块消失。窗口按「起点到目标的距离」收窄（最多 10%），
     * 起点离目标近时也不会来不及淡完。**只管阴影**：倾斜另有 [curlLeanFade]。
     *
     * `NaN` = 没有跟手交接（点按 / 程序翻页 / 目标页没进渲染层）：此时阴影照旧按进度算
     * `tailFade`，头尾两端都淡。只有 [endDrag] 那条跟手交接才给真值，否则点按翻页会
     * 丢掉起手那一端的淡入。
     */
    val curlFade: MutableFloatState = mutableFloatStateOf(Float.NaN)

    /**
     * 结算期间的**倾斜扶正系数**（0..1，见 `curl/CurlShape.lean`）。
     *
     * 跟手时恒 1；松手后分两种口径：
     * - **翻页（commit）**：手松那一刻的折痕角度**原样保持住**，再跟整段行程一起平滑退回
     *   （[orilumn.reader.ui.reader.curl.settleLeanFade]：`1 − smoothstep`，两端斜率都是 0）。
     *   倾斜量乘的是折痕两端相对中点的横向偏移，它决定「纸角朝哪个方向掀着」；一松手就扶平、
     *   再让折痕竖直横扫，屏幕上就是两截断开的动作（读者口径「先是回平再播放，非常不协调」）。
     * - **回弹（rollback）**：按「还剩多少行程」匀速退回
     *   （[orilumn.reader.ui.reader.curl.settleLeanRewind]），末帧正好竖直贴边。回弹是把手势
     *   倒着播，末段才扶正会让纸角先上冲再退回；起手就扶正又会让折角在开头整块塌掉。
     *   折痕已被推到自由边之外时还会**钉住折痕**（[orilumn.reader.ui.reader.curl.settleFoldProgress]），
     *   这段行程里倾斜是唯一的形变：塌平 = 折角原地收拢，不跟折痕回扫抢方向。
     *
     * `NaN` = 没有跟手交接（点按 / 程序翻页），倾斜照旧按进度 `tailFade` 两头淡。
     */
    val curlLeanFade: MutableFloatState = mutableFloatStateOf(Float.NaN)

    /**
     * 跟手锚点：手指此刻在页坐标里的位置（null = 不跟手）。
     *
     * 卷曲层拿它反解折痕位置，让**翻起来那张纸的边线正好穿过手指**（见
     * `curl/anchoredFoldX`）。只在卷曲模式下有意义，滑动层完全不看它。
     *
     * 三个时机清空：起手（[beginSlide] / [beginProgrammaticPage]）、松手（[endDrag] 结算前
     * 先把它换成结算起点）、清场（[clear]）。结算期间折痕必须听进度——否则手指走了折痕
     * 还钉在松手那一点，动画会「卡住」。
     */
    val curlAnchor: MutableState<Offset?> = mutableStateOf(null)

    /**
     * 起手接手的起点时刻（纳秒，`System.nanoTime()`）：第一次喂拖动点时记一次
     * （[updateCurlPull] 里 `curlPull` 为 null 那一帧），之后 [PULL_INTRO_MS] 之内折痕
     * 从静止位置滑到锚点位置（见 [pullEngage]），到位后完全交给手指。
     */
    private var pullIntroNanos = 0L

    /** 锚点记录时的那一页尺寸（反解要用页高算折痕斜率），随 [curlAnchor] 一起写。 */
    private var anchorPageW = 0f
    private var anchorPageH = 0f

    /** 起手那一页（正被拖走的那张）。落位在起手就发生，故 openPos 已是目标页。 */
    val fromPos: MutableState<ReaderPos?> = mutableStateOf(null)

    /** 目标页；null = 未就绪（此时渲染层不许平移当前页，见 [targetReady]）。 */
    val targetPos: MutableState<ReaderPos?> = mutableStateOf(null)

    /**
     * 会话的「当前页」是封面（前滑/前点离开封面）。true 时渲染层在 current 槽画封面
     * 而非 [fromPos] 的正文画布——[fromPos] 仍记封面下的书首内容页身份（供回滚/对账），
     * 只是不拿它画 current 槽。
     */
    val fromIsCover: MutableState<Boolean> = mutableStateOf(false)

    /**
     * 会话的「目标页」是封面（后退/后点进入封面）。true 时 [targetPos] 为 null 但目标
     * **立即就绪**（封面早已解码），渲染层在 target 槽画封面；进入封面不移动引擎数据。
     */
    val targetIsCover: MutableState<Boolean> = mutableStateOf(false)

    /**
     * 封面会话 commit 达成后的封面显隐结算（宿主注入）：[leavingCover]=true 离开封面
     * （dismiss），false 进入封面（show）。仅在 [settle] 的 COMMIT 完成时被调用一次；
     * 回滚/作废不触发（离开封面时 coverVisible 仍为 true，直接回静态封面即可）。
     */
    var onCoverCommit: ((leavingCover: Boolean) -> Unit)? = null

    private var prefetchJob: Job? = null
    private var settleJob: Job? = null

    /**
     * 本次拖动的「手指按下」单调时刻（纳秒，`System.nanoTime()`；0 = 未记录）。
     *
     * 只服务一条诊断：**第一次真的把位移写回渲染**时落 `FLIPLAT first-move`（见 [updateDrag]）。
     * 手势层那条 `FLIPLAT drag … lat=` 记的是「按下 → 定轴」，而定轴之后目标页可能还要
     * 等落位/栅格，页面并不会动——把两个数当成同一个「起手时延」会误判。
     */
    private var downNanos = 0L

    /** 本次会话是否已落过 `first-move`（一次拖动只记一条）。 */
    private var firstMoveLogged = false

    /**
     * 回滚落位协程（`rollback()` 里的 `flipAwait(-dir)`）。
     *
     * 与 [prefetchJob] 一样**不可取消**：`adjacent`/`findAdjacentPage` 会推进引擎指针，
     * 半路取消 = 引擎指针与显示层错位（下一页从错误的源页出发）。但它区别于
     * [settleJob]（结算动画，数据已落定，可安全取消）。
     *
     * 早先它是不被跟踪的孤儿协程（`scope.launch` 结果丢弃）：phase 已回 Idle 时它仍在途，
     * 快速连翻时新手势拿到了空会话，随后它收尾的 `clear()` 把新会话的落位掐掉
     * ⇒「划了不翻页 + 当页闪烁」。记进字段后，在途期间被 [beginDrag]/[beginProgrammatic]
     * 当作忙碌丢弃，且 [clear] 能收掉它。
     */
    private var rollbackJob: Job? = null

    /** 纯状态机（无 Compose 依赖，可直测）。 */
    val session: FlipSession = FlipSession()

    // ---- 唯一出口：渲染层与手势层一律走这两个判据 ----

    /**
     * 滑动动画此刻是否应当发生（渲染层 [FlipSlideLayer] 的总闸）。
     *
     * **动画开关关闭时必须为 false** —— `FlipSlideLayer` 是「始终在场」的
     * （为保住渲染器与页位图池，见其 KDoc），它不在任何 `!slideEnabled` 早退里；
     * 早先正因如此，关闭动画后入口都瞬切了、渲染层却还在按 `progress` 位移。
     *
     * ## 判据是「会话是否仍在渲染层在场」（[fromPos] != null），**不是 phase**
     *
     * 结算动画的**最后一帧**上，[FlipSession.onSettleProgress] 会把 phase 置成
     * [FlipSession.Phase.Idle]，而 `clear()` 要等 `animateTo` 返回后才跑 ——
     * 中间这一小段里 phase 已是 Idle、会话却仍在渲染层锁着两页。
     * 若按 phase 判，这一帧的 `progress` 会被压成 0：当前页瞬间跳回原位、目标页
     * 滑出屏外 ⇒ **翻完闪一下旧页**，下一帧 `clear()` 才切到新页。
     *
     * 按 [fromPos] 判则全程稳定：动画期间与最后一帧都照常按 progress 画，
     * `clear()` 之后 fromPos 为 null、progress 本就是 0，画面自然衔接、不跳。
     */
    fun slideActive(enabled: Boolean): Boolean =
        enabled && (fromPos.value != null || fromIsCover.value)

    /** 是否有会话在飞（含结算）：点按、键盘、外部落位都据此避让。 */
    fun isBusy(): Boolean = session.phase != FlipSession.Phase.Idle

    /** 目标页是否已进渲染层：未就绪时**不许**平移当前页（否则露出底色空白）。 */
    fun targetReady(): Boolean = targetPos.value != null || targetIsCover.value

    /**
     * 落定代际：每 [clear] +1。
     *
     * 唯一用途是给预栅格一个「这一拍刚翻完」的时机（见 `PrerasterOnPrefill` 的
     * `settleTick`）：引擎的预排信号在**落位之前**抛，补到的是本次要翻的那张（落位
     * 自己正在栅）；落定之后又不再抛信号，于是下一张永远没人提前栅，下次起手现场
     * 栅 200–390ms。代际变化那一刻 `openPos` 与方向都已是最新，补的正是下一张。
     */
    val settleTick: MutableIntState = mutableIntStateOf(0)

    /**
     * 最近一次翻页方向（+1 下 / -1 上，0 = 还没翻过）。
     *
     * [FlipSession] 在结算收尾时把 direction 归零（那是状态机的口径），但预栅格需要
     * 的是「读者正在往哪边读」——归零后就只剩「默认向前」，往回翻时会把上一刻刚读过
     * 的那一页再栅一遍、真正要去的反侧仍没热身。故这里单独记一份，供
     * `PrerasterOnPrefill` 的 directionProvider 兜底。
     */
    val lastTurn: MutableIntState = mutableIntStateOf(0)

    // ---- 会话生命周期 ----

    /**
     * 清场（只清动画状态，不动数据）。
     *
         */
    fun clear() {
        settleJob?.cancel()
        settleJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        rollbackJob?.cancel()
        rollbackJob = null
        session.abort()
        fromPos.value = null
        targetPos.value = null
        fromIsCover.value = false
        targetIsCover.value = false
        progress.value = 0f
        direction.value = 0
        curlPull.value = null
        curlSpanX = 0f
        curlAnchor.value = null
        curlFade.value = Float.NaN
        curlLeanFade.value = Float.NaN
        // 会话收场：通知预栅格此刻可补方向侧那一张（见 [settleTick]）。
        settleTick.value++
    }

    /**
     * 动画作废**并把数据退回原页**。
     *
     * ## 清场必须等数据翻回之后（回弹闪烁的根因）
     *
     * 早先把 `clear()` 放在数据翻回**之前**。回弹动画播完那帧画面正好停在原页，
     * 紧接着 `fromPos` 被清成 null ⇒ 渲染层 `fromPos ?: openPos` 退回用 `openPos`，
     * 而 `openPos` 已被起手那次 commit 改成目标页 ⇒ 画面先闪一下目标页，数据翻回后
     * 又切回原页。用户看到的就是「回弹以后明显闪一下」。
     *
     * 期间 `fromPos` 保持原页、`progress` 停在 0，画面稳定停在原页不动。
     */
    fun rollback(navigateBack: Boolean = true) {
        // 封面会话不翻引擎数据：进入封面从未移动数据；离开封面虽落位到书首，但
        // 「回到封面」只是重新显示封面浮层（coverVisible 在 commit 前仍为 true）。
        // 故两者回滚都只清场——绝不能 flipAwait(-dir)，那会把书首再往回翻/翻进封面。
        if (fromIsCover.value || targetIsCover.value) {
            clear()
            return
        }
        val back = if (navigateBack) fromPos.value else null
        val dir = direction.value
        if (back == null || dir == 0) {
            clear()
            return
        }
        // 只停动画与预取，**刻意保留 fromPos / targetPos / direction**：
        // 它们是渲染层这期间显示原页的依据（progress 停在 0 即静止在原页）。
        settleJob?.cancel()
        settleJob = null
        prefetchJob?.cancel()
        prefetchJob = null
        rollbackJob?.cancel()
        rollbackJob = null
        session.abort()
        progress.value = 0f
        // 回滚落位记进 rollbackJob（不是孤儿协程）：这段 flipAwait **不可取消**
        // （adjacent 会推进引擎指针），在途期间 phase 已是 Idle，新手势必须被
        // 忙碌墙挡掉，否则其收尾 clear() 会把新人会话的落位掐掉（快速连翻
        // 「划了不翻页」的根因之一，日志：dispatch 后无 result、直接 settle instantly）。
        rollbackJob = scope.launch {
            var landed = flipAwait(-dir)
            // try-lock 不排队：被占时立刻失败，重试一次。
            if (landed == null) {
                log("slide-rollback BUSY, retry once")
                delay(ROLLBACK_RETRY_DELAY_MS)
                landed = flipAwait(-dir)
            }
            log("slide-rollback data dir=${-dir} landed=${landed?.let { "ch=${it.chapter} char=${it.slice.charStart}" } ?: "NULL"}")
            // 翻回失败：引擎指针停在新页而画面认定在原页 ⇒ 两者错位（真机表现为白屏）。
            // 强制把 openPos 拉回原页，让像素与数据自洽：宁可退一步也不能停在死局。
            if (landed == null) {
                log("slide-rollback FAILED ⇒ force openPos back to ch=${back.chapter} char=${back.slice.charStart}")
                forceOpenPos(back)
            }
            clear()
        }
    }

    /**
     * 拖动起手：**就地落位一次**，把落定结果当作本次动画的目标页。
     *
     * [ReaderHost.adjacent] 不是只读查询而是真导航（引擎 `tempNav` 会推进
     * `ip.curIndex`），故一次翻页**只能调一次**——曾「预取」一次、松手再落一次，
     * 第二次引擎已不在源页 ⇒ 观感是「翻到位又弹回上一页」。
     *
     * 方向在此定，不是等 [updateSlide]：未就绪时那里不写回渲染，而方向是渲染层
     * `shift = pageW * direction` 的因子，恒 0 会让目标页叠在原位而非从侧面滑入。
     *
     * ## 忙碌墙（快速连翻「划了不翻页 + 当页闪烁」的根因收敛点）
     *
     * 早先这里无任何守卫：上一手势的结算动画还在飞（90–300ms 的尾巴）就冲进来，
     * 覆盖 fromPos/targetPos/direction，却**不取消旧 settleJob**——旧动画照旧逐帧写
     * progress（用新 direction 画错的位移 ⇒ 当页闪烁/抽搐），随后旧 clear() 把新
     * 手势的 prefetchJob 掐掉 ⇒「划了不翻页」。两类在途分别处置：
     *
     * - **落位在途**（[prefetchJob] / [rollbackJob]）：**不可以取消**——`adjacent`/`findAdjacentPage`
     *   已推进引擎指针，半路取消是引擎与显示层次久错位（下一页从错误源页出发）。
     *   沿用漏斗 BUSY-DROP 口径**丢弃**这次起手（本次拖动整场不采纳位移、不翻页，
     *   在途落位自己收尾）。
     * - **结算动画在飞**（[settleJob]）：数据早已在起手那次 commit 落定，**可以安全取消**。
     *   取消旧动画、abort 会话，从「已落定的当前页」干净起手——新一划就从落定页跟手，
     *   不再等 90–300ms 尾巴（少等待；与「分页调度总则」第一优先级一致）。
     */
    fun beginDrag(
        src: ReaderPos,
        direction: Int,
        enabled: Boolean,
        coverVisible: Boolean,
        enterCover: Boolean = false,
        /** 手指按下的单调时刻（纳秒），仅用于 `FLIPLAT first-move` 诊断；0 = 未知。 */
        downNanos: Long = 0L,
    ) {
        if (!enabled) return
        // 封面是第一页：后退无操作；前进才离开封面（封面进 from 槽，见 [fromIsCover]）。
        if (coverVisible && direction <= 0) return
        if (prefetchInFlight() || rollbackInFlight()) {
            log("slide-begin DROPPED busy (${if (prefetchInFlight()) "landing" else "rollback-nav"} in flight)")
            return
        }
        this.downNanos = downNanos
        firstMoveLogged = false
        // 残留结算动画（含「最后一帧 → clear()」那个微窗口，见 slideActive KDoc）：
        // 取消它，避免旧动画继续写 progress/direction 并在此后 clear() 掐掉本次落位。
        settleJob?.cancel()
        settleJob = null
        session.abort()
        // 起手页 = 「当前页」身份，必须在这里存一份：落位在预取协程里发生，
        // 完成后 openPos 就是目标页了。不存的话渲染层 `fromPos ?: pos` 会退回用
        // pos（=目标页）⇒ 两张画布都画目标页，观感是「目标页空白、内容重复」。
        fromPos.value = src
        fromIsCover.value = coverVisible
        targetPos.value = null
        targetIsCover.value = enterCover
        this.direction.value = direction
        lastTurn.value = direction
        progress.value = 0f
        curlPull.value = null
        curlLean.value = 0f
        curlSpanX = 0f
        curlAnchor.value = null
        curlFade.value = Float.NaN
        curlLeanFade.value = Float.NaN
        if (enterCover) {
            // 后退回封面：目标（封面）早已解码、立即就绪，**无需落位**——跟手由
            // updateDrag 驱动（targetReady()=true），松手 endDrag 结算。绝不 flipAwait：
            // 封面不是引擎页，落位会把书首再往回翻一章。
            log("slide-begin cover-enter dir=$direction")
            return
        }
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            // 起手→目标页就绪的分段计时（配合手势层 FLIPLAT 的 lat 相加即端到端时延）：
            // 这一段包含「漏斗抢锁 + adjacent 落位 + beforeCommit 等图」，是时延的主要来源。
            val t0 = System.nanoTime()
            val landed = flipAwait(direction)
            val tLanded = System.nanoTime()
            // 边界/无源/BUSY-DROP：**数据从未移动**（返回 null 即没有 commit），
            // 所以只能清场。早先误调 rollback 会多翻回去一页。
            if (landed == null) {
                log("slide-begin no target dir=$direction ⇒ no animation")
                logLat(dir = direction, phase = "landed-null", ms = (tLanded - t0) / 1_000_000)
                clear()
                return@launch
            }
            // 数据先落，但目标页暂不进渲染层：翻页的前提是相邻两页都就绪。
            // 等图由 flipAwait 内部的 AnchorFunnel.beforeCommit 在 commit 之前做完，
            // 这里再等一次是冗余，且会和下一手势抢同一个漏斗锁 ⇒ 连锁 BUSY-DROP。
            targetPos.value = landed
            // 目标页已进渲染层：这一刻起 updateDrag 的位移才会写回 progress、页面才动。
            logLat(dir = direction, phase = "target-ready", ms = (tLanded - t0) / 1_000_000)
            // 调用方可能已经放弃（松手走了 abort 超时分支，见 [waitLandingThenSettle]）。
            // 那时没人会再触发 settle ⇒ 这里自己收场，否则会话永久停在 Dragging，
            // 后续手势全被 isBusy 吞。仍处于 Dragging 则交给调用方（松手/补播）。
            if (session.phase == FlipSession.Phase.Idle) {
                log("landing finished after abandon ⇒ settle now")
                settle(FlipSession.Decision.COMMIT, velocityX = 0f, pageW = 0f)
            }
        }
    }

    /**
     * 分段时延日志（tag `Orilumn.FLIPLAT`，W 级常驻）。
     *
     * 三条线的关系（按 dir 与时间先后对齐即可读出端到端时延）：
     *  - 手势层 `FLIPLAT drag … lat=` = 手指按下 → **定轴**（判成横向滑动），不代表页面已动；
     *  - `FLIPLAT target-ready ms=` = 起手 → 目标页进渲染层（含漏斗抢锁 + 落位 + 等图/栅格，
     *    最长 800ms）；
     *  - `FLIPLAT first-move ms=` = 手指按下 → **第一帧位移真的写回渲染**（用户看到页面开始动）。
     *
     * 端到端以 `first-move` 为准；它必然 ≥ `lat`，且 ≥ `target-ready`（目标页未就绪时
     * `updateDrag` 不写回渲染）。
     */
    private fun logLat(dir: Int, phase: String, ms: Long) {
        log("FLIPLAT $phase dir=$dir ms=$ms")
    }

    /**
     * 卷曲手感输入：拖动的起止点（`from` = 起手时贴到页边的点，`to` = 此刻手指）。
     *
     * 单独一条入口（不并进 [updateDrag]）：滑动层完全不看折痕角度，只有卷曲层读它；
     * 混在一起会让「翻页进度」和「折痕形状」两个无关的事实共用一次调用，将来谁改一个都会动到另一个。
     *
     * ## 折痕角度为什么不平滑
     *
     * 旧版在这里对 `dy / hypot(dx, dy)` 做指数滑动平均，理由是「手指上下抖几像素，折痕就
     * 来回摆几十像素」。但那笔平滑是**建在错误的自变量上**的：参考实现（Moon+
     * `updateCurlPos`）用的不是相邻两个事件的位移，而是**手指相对起手页边的位移**——
     * 它是积分量，天然平滑，`dy` 抖两下不会让整条轨迹转几十度。换成积分量之后平滑只剩
     * 滞后（斜拖时折痕角度永远追不上手指），所以这里不再平滑。
     *
     * @param downX/downY 按下点（页坐标）：`downY` 是抓点的行（与参考实现 `mDragStartPos` 同口径），
     *   `downX` 决定锚点模式——右半边 `Edge`、左半边 `Crease`（见 [CurlAnchor]），整个手势只判这一次。
     *   锚点本身与翻页方向无关；翻页方向只给折痕锚点锁横向符号与分母（见
     *   `curl/CurlPull.direction` / `curl/CurlPull.spanX`）
     * @param x/y 此刻手指的页坐标
     * @param pageW/pageH 当前页尺寸（px）：折痕斜率按页宽页高换算
     */
    fun updateCurlPull(
        downX: Float,
        downY: Float,
        x: Float,
        y: Float,
        pageW: Float,
        pageH: Float,
    ) {
        if (pageW <= 0f || pageH <= 0f) return
        anchorPageW = pageW
        anchorPageH = pageH
        // 起手接手（[pullEngage]）：第一次喂拖动点的时刻记一次，之后 [PULL_INTRO_MS] 内折痕从静止
        // 位置滑到锚点位置，然后完全听手指。只在**起手那一次**定，途中不重算起点。
        if (curlPull.value == null) pullIntroNanos = System.nanoTime()
        val introMs = (System.nanoTime() - pullIntroNanos) / 1_000_000f
        val blend = pullEngage(introMs / PULL_INTRO_MS)
        // 锚点由**起手位置**定，且只定这一次：右半边抓页边（纸的自由边压在手指上），
        // 左半边抓折痕（折痕穿过手指）。`downX` 整个手势不变 ⇒ 模式固定，拖动中手指
        // 跨过屏幕中线也不改判（读者口径）。与翻页方向无关。
        val crease = downX < pageW * 0.5f
        // 折痕锚点的倾斜分母锁在**外向行程的最大值**上（见 `curl/CurlPull.spanX`）：
        // 手指往回收时折痕只平移、不转正。外向 = 沿翻页方向（下一页向左、上一页向右）。
        if (crease) {
            val outward = direction.value * (downX - x)
            if (outward > curlSpanX) curlSpanX = outward
        }
        val pull = CurlPull(
            // 折痕锚点拿按下点当 `from`：拖动方向 = 按下点 → 手指，倾斜与行程都照它算；
            // 页边锚点用页右缘（参考实现 `pageRect.right`）。
            from = Offset(if (crease) downX else pageW, downY.coerceIn(0f, pageH)),
            to = Offset(x, y),
            anchor = if (crease) CurlAnchor.Crease else CurlAnchor.Edge,
            blend = blend,
            // 折痕锚点的横向符号由起手横滑方向锁住（见 `curl/leanOf`）：翻页方向在
            // `beginDrag` 就定了，整个手势不变。
            direction = direction.value,
            spanX = if (crease) curlSpanX else 0f,
        )
        curlPull.value = pull
        curlLean.value = leanOf(pull, pageW, pageH)
        curlAnchor.value = Offset(x, y)
    }

    /** 当前跟手位置对应的**屏幕可见进度**；无拖动 / 无尺寸时返回 null。 */
    private fun anchoredProgressNow(): Float? {
        val p = curlPull.value ?: return null
        if (anchorPageW <= 0f || anchorPageH <= 0f) return null
        return anchoredProgress(
            pull = p,
            pageW = anchorPageW,
            pageH = anchorPageH,
            tilt = curlLean.value,
            progress = session.progress,
            direction = direction.value,
        )
    }

    /**
     * 拖动中：把位移喂给状态机，逐帧写回渲染。
     *
     * ## 「画不画」与「判不判」必须分开
     *
     * 目标页未就绪时**不写回 progress** —— 否则渲染层会让当前页独自平移，身后直接
     * 露出阅读面底色（用户看到「先拉出一个空白页」）。单独平移一页在物理上不成立：
     * 平移必须有另一页填坑。
     *
     * 但位移**照样喂进状态机**（[FlipSession] 是纯数据、不知道渲染）：否则 progress
     * 恒 0，松手裁决会把「拖了满页」判成「没动过」而弹回。
     */
    fun updateDrag(dx: Float, pageW: Float, enabled: Boolean) {
        if (!enabled) return
        if (targetReady()) {
            if (!session.onDrag(dx, pageW)) return
            progress.value = session.progress
            direction.value = session.direction
            // 「手指按下 → 页面真的动起来」：第一次把位移写回渲染时落一条（一次拖动一条）。
            // 与手势层 `FLIPLAT drag … lat=`（按下→定轴）不是一回事，见 [downNanos]。
            if (!firstMoveLogged && session.progress > 0f) {
                firstMoveLogged = true
                if (downNanos != 0L) {
                    logLat(
                        dir = direction.value,
                        phase = "first-move",
                        ms = (System.nanoTime() - downNanos) / 1_000_000,
                    )
                }
            }
        } else {
            // 只喂位移不写回渲染：状态机照常累积（供 decide 用），画面停在当前页。
            session.onDrag(dx, pageW)
        }
    }

    /**
     * 松手：裁决 → 结算。
     *
     * 目标页此刻还没进渲染层时**不判回滚** —— progress 由 [updateDrag] 无条件喂进
     * 状态机，读到的是用户真实拖动量。早先「未就绪 ⇒ 无条件 rollback」把「拖了满页」
     * 当成「没动过」，用户反馈「开始滑动大概率就是要翻页，却常被弹回」。
     */
    fun endDrag(velocityX: Float, pageW: Float, enabled: Boolean) {
        if (!enabled || session.phase != FlipSession.Phase.Dragging) return
        val decision = session.decide(velocityX, pageW)
        // 跟手时屏幕上折痕的真实位置（可能领先/落后于按位移算的进度）：结算必须从这里起步，
        // 否则动画第一帧折痕会跳回「进度位置」再走。旧版 `startCommitAnim` 的 `start` 同款。
        // 目标页没进渲染层的那条路不走跟手（画面本来就停在当前页），故不取。
        val visibleFrom = if (targetReady()) anchoredProgressNow() else null
        curlAnchor.value = null
        // 起止点清掉，但 [curlLean] 保留：结算从松手那一刻的折痕角度接着走，形状不跳变。
        curlPull.value = null
        if (targetReady()) {
            settle(decision, velocityX, pageW, fromProgress = visibleFrom)
            return
        }
        if (decision == FlipSession.Decision.ROLLBACK) {
            log("slide-end not ready & rollback ⇒ undo data")
            rollback(navigateBack = true)
            return
        }
        // COMMIT 但目标页还没进渲染层。
        //
        // ## 为什么不能直接 clear()（上一版的空白页）
        //
        // 预取协程**可能仍在途**（`flipAwait` 在等漏斗锁 + beforeCommit 等图，
        // 真机实测有 667ms 的窗口）。此时 `clear()` 会把 fromPos/targetPos 清空，
        // 协程随后回来再设 `targetPos` —— 而 `fromPos` 已是 null ⇒ 渲染层
        // `fromPos ?: pos` 退回用 `pos`，若此刻 openPos 尚未 commit，画出来的
        // 就是一个尚未就绪的页 ⇒ 白屏。
        //
        // 早先的「挂起等补播」也错：它留下一个**无人认领**的等待窗口
        // （协程可能已跑完 clear 过，此后 endDrag 才写标志，就再没人清它）
        // ⇒ `isBusy()` 恒 true ⇒ 点按全被 `onTap` 吞 ⇒ 8 秒白屏。
        //
        // 现在的做法：**协程在途就等它自己收场**，但等待有界（[LANDING_WAIT_MS]）。
        // 等到 ⇒ 走正常 settle；等不到 ⇒ 放弃并把 openPos 强制对齐到起手页，
        // 保证「数据与像素自洽」，绝不停在两者错位的死局。
        if (prefetchInFlight()) {
            log("slide-end not ready & prefetch in flight ⇒ wait for it")
            waitLandingThenSettle(velocityX, pageW)
            return
        }
        // 协程不在途（已收场）：此刻清场是安全的。
        log("slide-end not ready but COMMIT & prefetch done ⇒ settle instantly")
        clear()
    }

    /** 预取协程是否仍在途（锁未抢到 / 落位未回 / 等图未完）。 */
    private fun prefetchInFlight(): Boolean = prefetchJob?.let { it.isActive && !it.isCompleted } ?: false

    /**
     * 上一个手势没结算就消失时的兜底（真机「所有批注突然消失 + 页面翻不动」的恢复闸）。
     *
     * **只在新一轮按下那一刻调用**：`awaitEachGesture` 保证上一轮所有指针都已抬起才进新块，
     * 所以此刻会话若仍非 Idle，只可能是上一次手势没走到 [endDrag]（抬起事件被上层消费、
     * 指针 change 丢失、手势协程异常退出），而 [endDrag] 不会来了 —— 会话永远非 Idle：
     * 渲染层 `isBusy()` 把批注层整个藏起来，点按也全被 busy 墙挡掉（只有返回键能退出去）。
     *
     * 也因此**不看 progress**：手指早已离开，位移停在哪儿都不代表「还在拖」。
     *
     * 两种卡死分别收场：
     *  - 停在 **Dragging**：数据早在起手那次 `flipAwait` 就落到目标页，补一次 **COMMIT**
     *    让像素追上去（用 rollback 会凭空多翻回去一页）；落位从未成功（目标页不就绪）
     *    或落位/回滚协程仍在途（会自己收尾）时不动它。
     *  - 停在 **Settling** 而结算协程已不在：动画半路没了，没人再推进状态机，裸清场。
     */
    fun recoverStuckDrag() {
        if (session.phase == FlipSession.Phase.Settling) {
            if (settleJob?.isActive == true) return
            log("stuck settle recovered ⇒ clear (no animation in flight)")
            clear()
            return
        }
        if (session.phase != FlipSession.Phase.Dragging) return
        if (prefetchInFlight() || rollbackInFlight()) return
        if (!targetReady()) {
            log("stuck drag recovered ⇒ clear (never landed)")
            clear()
            return
        }
        log("stuck drag recovered ⇒ settle commit")
        settle(FlipSession.Decision.COMMIT, velocityX = 0f, pageW = 0f)
    }

    /** 回滚落位协程是否在途（phase 已 Idle 也可能为 true——见 [rollbackJob] KDoc）。 */
    private fun rollbackInFlight(): Boolean = rollbackJob?.let { it.isActive && !it.isCompleted } ?: false

    /**
     * 等在途的落位收场，然后按结果结算。等待有界，超时则强制对齐（见 [endDrag]）。
     *
     * 用「轮询 `prefetchJob` 是否完成」而不是挂起标志：后者需要有人负责清理，
     * 而清理方（协程）可能根本不会跑到那条分支——那正是上一版死锁的成因。
     */
    private fun waitLandingThenSettle(velocityX: Float, pageW: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            var waited = 0L
            while (prefetchInFlight() && waited < LANDING_WAIT_MS) {
                delay(LANDING_POLL_MS)
                waited += LANDING_POLL_MS
            }
            if (prefetchInFlight()) {
                // 等不到：协程还锁着漏斗。多半是上一次落位的等图还没放完。
                // 此时**不能**裸 clear（协程回来会再设 targetPos，与 fromPos=null
                // 错位 ⇒ 白屏）。改用 abort：只停状态机，保留 fromPos/targetPos，
                // 让协程回来时它自己的 clear() 去收尾。
                Logger.w(
                    "Orilumn.TAP",
                    "landing wait timed out (${waited}ms) ⇒ abort, keep page lock",
                )
                session.abort()
                return@launch
            }
            // 协程已收场：按当前数据决定去向。
            if (targetReady()) {
                settle(FlipSession.Decision.COMMIT, velocityX, pageW)
            } else {
                clear()
            }
        }
    }

    /**
     * 点按/方向键翻页的落位（无手指，progress 从 0 起）。
     *
     * 与拖动路径同样「先落位后出画」：[beginProgrammatic] 只把会话置为 Dragging、
     * 定死方向，数据落位由本方法在预取协程里做。返回 false = 会话不空闲，
     * 调用方应**丢弃**这次翻页（与 [AnchorFunnel] 的 BUSY-DROP 同口径）。
     *
     * [src] 是起手时的 `openPos`：它同时是「当前页」身份，落位后 openPos 会变成
     * 目标页，不另存一份的话渲染层会拿新页当当前页画。
     */
    /**
     * 点按/方向键的无拖动起手：判忙 + 把会话置为 Dragging、方向定死、progress 从 0。
     *
     * 返回 false = 会话不空闲（有动画在飞 / 落位回滚在途），调用方应**丢弃**这次翻页，
     * 与 [AnchorFunnel] 的 BUSY-DROP 同一口径：不排队、不打断进行中的动画。
     * 紧接着调 [beginProgrammaticPage] 落位。
     *
     * 判忙不能只看 phase：落位/回滚协程在途时 phase 可能已是 Idle（`rollback()`
     * 先 abort 会话再起落位协程），若放行，那个在途协程的收尾 `clear()` 会掐掉
     * 本次新起的落位 ⇒ 快速连翻/连点「点了没反应」（与 [beginDrag] 的忙碌墙同源）。
     */
    fun beginProgrammatic(direction: Int): Boolean {
        if (direction == 0) return false
        if (session.phase != FlipSession.Phase.Idle) return false
        if (prefetchInFlight() || rollbackInFlight() || settleJob != null) return false
        return session.beginProgrammatic(direction)
    }

    /**
     * 点按/方向键翻页的落位（无手指，progress 从 0 起）。
     *
     * 与拖动路径同样「先落位后出画」：[beginProgrammatic] 只把会话置为 Dragging、
     * 定死方向，数据落位由本方法在预取协程里做。
     *
     * [src] 是起手时的 `openPos`：它同时是「当前页」身份，落位后 openPos 会变成
     * 目标页，不另存一份的话渲染层会拿新页当当前页画（真机症状：目标页空白）。
     */
    fun beginProgrammaticPage(src: ReaderPos, direction: Int, fromIsCover: Boolean = false) {
        fromPos.value = src
        this.fromIsCover.value = fromIsCover
        targetPos.value = null
        this.targetIsCover.value = false
        this.direction.value = direction
        lastTurn.value = direction
        progress.value = 0f
        curlPull.value = null
        curlLean.value = 0f
        curlAnchor.value = null
        curlFade.value = Float.NaN
        curlLeanFade.value = Float.NaN
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            val landed = flipAwait(direction)
            if (landed == null) {
                // 数据从未移动，只能清场；早先误调 rollback 会多翻回去一页。
                log("tap-flip no target dir=$direction ⇒ no animation")
                clear()
                return@launch
            }
            // 等图由 AnchorFunnel.beforeCommit 在 commit 之前完成（见类 KDoc）。
            targetPos.value = landed
            // 点按没有跟手过程垫底，必须播完整段动画（progress 从 0 走满）。
            settle(FlipSession.Decision.COMMIT, velocityX = 0f, pageW = 0f)
        }
    }

    /**
     * 点按/方向键的**进入封面**起手（正文首页后退）：目标=封面，无需落位。
     *
     * 封面不是引擎页，[flipAwait] 不该被调用（那会把书首往回翻）；目标已解码即就绪，
     * 故这里直接置位并立即结算整段动画。[src] = 起手那页（正文首页），供渲染层 current 槽用。
     */
    fun beginProgrammaticCoverEnter(src: ReaderPos, direction: Int) {
        fromPos.value = src
        fromIsCover.value = false
        targetPos.value = null
        targetIsCover.value = true
        this.direction.value = direction
        lastTurn.value = direction
        progress.value = 0f
        settle(FlipSession.Decision.COMMIT, velocityX = 0f, pageW = 0f)
    }


    /**
     * 跑结算动画（commit 走满位 / rollback 回零）。
     *
     * 起点必须取自 [FlipSession.beginSettle] **之前**的 progress：状态机进入结算后
     * 不再改写 progress（它只记目标），所以调用方手上这份就是「屏幕上真实的位置」。
     * 跟手松手时那个真实位置由 [fromProgress] 传入（折痕可能不在进度位置上，见
     * `curl/anchoredFoldX`）；其余路径（点按/兜底）传 null，退回按进度算。
     * 时长按甩动速度折算（快甩短促收尾），[pageW] 用于把进度换算回像素距离。
     *
     * 回弹时屏幕上折痕的进度另走 [settleFoldProgress]：折痕已被推到自由边之外就钉住不动、
     * 只让倾斜塌回平面（否则扶正与回扫互相拉扯，纸边先外翻再收回）；收尾再把进度拨回 0。
     */
    fun settle(
        decision: FlipSession.Decision,
        velocityX: Float,
        pageW: Float,
        fromProgress: Float? = null,
    ) {
        if (!allowAnimation) {
            // 测试路径：只推进状态机到端点，不启动 Animatable。
            session.beginSettle(decision)
            session.onSettleProgress(if (decision == FlipSession.Decision.COMMIT) 1f else 0f)
            if (decision == FlipSession.Decision.COMMIT) notifyCoverCommit()
            clear()
            return
        }
        val from = fromProgress ?: session.progress
        // 渲染进度先钉到起点：跟手松手时折痕离「按进度算」的位置可能有两三百像素
        // （纸边在手指上、折痕更快），清锚点与结算第一帧之间只要差一帧就是一次可见的跳变。
        progress.value = from
        session.beginSettle(decision)
        val duration = session.settleDurationMs(decision, from, velocityX, pageW, settleScale)
        // 离手速度衔接：起手斜率取手指速度，页面不在松手那一下刹停（详见
        // FlipSession.settleInitialSlope 的 KDoc）。slope==0（点按/慢拖/拿不到速度）
        // 时保持既有缓动，点按翻页的手感零变化。
        val slope = session.settleInitialSlope(decision, from, velocityX, pageW, duration)
        // 结算时长/起手斜率（与 FLIPLAT 同级的常驻单行日志）：验「两倍速」是否生效、
        // 快甩是否真衔接上（slope>0 才走 VelocityHandoffEasing）。
        log("settle ${if (decision == FlipSession.Decision.COMMIT) "commit" else "rollback"} dur=${duration}ms slope=$slope")
        val spec = if (slope > 0f) {
            androidx.compose.animation.core.tween<Float>(duration, easing = VelocityHandoffEasing(slope))
        } else {
            androidx.compose.animation.core.tween<Float>(duration)
        }
        val target = if (decision == FlipSession.Decision.COMMIT) 1f else 0f
        // 阴影的淡出窗口：从起点到目标这一段里、靠末段把阴影淡到 0。窗口不超过常规 10%，
        // 起点离目标更近时按起点距离收窄（否则还没淡完就到了）。起点即 1 时（见
        // `curl/anchoredProgress`），松手第一帧与跟手那一帧同形，不会整块消失。
        val fadeSpan = minOf(CURL_TAIL_FADE_SPAN, kotlin.math.abs(from - target)).coerceAtLeast(1e-4f)
        // 只有**跟手交接**才走自定义扶正；点按 / 程序翻页（fromProgress == null）留给
        // `tailFade`，起手与收尾两端照旧淡（NaN = 按进度算，见 [curlFade] / [curlLeanFade]）。
        val handoff = fromProgress != null
        val commit = decision == FlipSession.Decision.COMMIT
        curlFade.value = if (handoff) 1f else Float.NaN
        // 倾斜与阴影分开，且两个方向各有各的扶正口径（见 [curlLeanFade]）：
        //  - 翻页：松手那一刻的折痕角度先保持住，再跟整段行程一起平滑退回（[settleLeanFade]），
        //    接着手指的方向走；一松手就扶平、再竖直横扫就是两截断开的动作；
        //  - 回弹：倾斜跟着「还剩多少行程」一起退（[settleLeanRewind]），末帧正好竖直贴边。
        //    末段才扶正会让纸角先向上冲一下再退回；起手就扶正又会让折角整块塌掉。
        // 回弹还有第三件事：折痕已经在自由边之外时**钉住折痕**，只让倾斜塌回平面
        // （[settleFoldProgress]）——否则「扶正 + 扫折痕」两个方向互相拉扯，纸边会先外翻再收回。
        curlLeanFade.value = if (handoff) 1f else Float.NaN
        settleJob?.cancel()
        settleJob = scope.launch {
            val anim = androidx.compose.animation.core.Animatable(from)
            anim.animateTo(
                targetValue = target,
                animationSpec = spec,
            ) {
                // animateTo 的回调是无参的 Animatable.() -> Unit，当前值在 this.value 上。
                val v = value
                session.onSettleProgress(v)
                // 屏幕上折痕摆哪：翻页与「折痕还在页内」的回弹都跟着动画走；折痕已在自由边
                // 之外的回弹把它钉在松手位置（见 [settleFoldProgress]），那块折角全靠倾斜撑着，
                // 倾斜塌平它就地收拢，不会出现「先外翻再收回」的颤动。
                progress.value = settleFoldProgress(from, target, v)
                if (handoff) {
                    curlFade.value = (kotlin.math.abs(v - target) / fadeSpan).coerceIn(0f, 1f)
                    curlLeanFade.value = if (commit) {
                        settleLeanFade(from, target, v)
                    } else {
                        settleLeanRewind(from, target, v)
                    }
                }
            }
            if (decision == FlipSession.Decision.COMMIT) {
                // 数据早在起手那次 flipAwait 就已落定，动画走完即达成：清场即可。
                // 封面会话在此结算封面显隐（离开封面 dismiss / 进入封面 show），
                // 必须赶在 clear()（复位 fromIsCover/targetIsCover）之前读标志。
                notifyCoverCommit()
                clear()
            } else {
                // 回弹：动画回到 0，同时把引擎指针翻回原页。
                // 折痕可能被钉在页外（见 [settleFoldProgress]），收尾这一帧倾斜已归零、
                // 竖直折痕在页外 = 平页，所以把进度拨回 0 是**看不见**的；不拨的话，钉住的
                // 负进度会留到落位完成才复位，期间渲染层仍按页外折痕画（视觉上虽平，状态却是错的）。
                progress.value = 0f
                rollback(navigateBack = true)
            }
        }
    }

    /** 结算 commit 达成时通知宿主封面去向（见 [onCoverCommit]）。回滚/作废不触发。 */
    private fun notifyCoverCommit() {
        val cb = onCoverCommit ?: return
        when {
            fromIsCover.value -> cb(true)   // 离开封面：dismiss
            targetIsCover.value -> cb(false) // 进入封面：show
        }
    }
}

/**
 * 回滚重试前的等待（ms）：[AnchorFunnel] 的 try-lock 不排队，被占时立刻失败。
 * 给占锁方（通常是上一次落位尾巴上的等图）一个收尾窗口。
 */
internal const val ROLLBACK_RETRY_DELAY_MS = 60L

/**
 * 松手时等在途落位的上限（ms）与轮询间隔。
 *
 * 落位包含「漏斗抢锁 + adjacent + beforeCommit 等图」，真机实测最慢见过 667ms
 * （`Orilumn.FLIPLAT` 日志：dispatch 之后 667ms 才有 slide-end）。所以给一个
 * 宽裕但有界的窗口：有界是为了「等不到」也必须有个确定结局，而不是无限挂着。
 */
internal const val LANDING_WAIT_MS = 900L
internal const val LANDING_POLL_MS = 30L


/**
 * 起手斜率连续的结算缓动（用户层·纯数学，与平台无关）。
 *
 * Hermite 基 `u(s) = h01(s) + a·h10(s)`，其中 `h01 = -2s³+3s²`、`h10 = s³-2s²+s`：
 *  - 端点 `u(0)=0, u(1)=1`（位移与端点对齐）；
 *  - 斜率 `u'(0)=a, u'(1)=0` —— 起手保留手指速度、终点速度归零。
 *
 * `a=0` 退化为 smoothstep（两端都静止，点按/慢拖，与旧的 `FastOutSlowInEasing` 同为
 * 缓入缓出）；`a=1` 时起手斜率等于「匀速走完剩余距离」的斜率，即快甩离手那一下不再
 * 刹停（见 [FlipSession.settleInitialSlope]）。
 *
 * 对 `a∈[0,1]` 恒单调、不越界：`u'(s) = (1-a)·6s(1-s) + a·(1-s)(3s+1) ≥ 0`。
 */
internal class VelocityHandoffEasing(private val a: Float) : Easing {
    override fun transform(fraction: Float): Float {
        val s = fraction
        val s2 = s * s
        val s3 = s2 * s
        return (-2f * s3 + 3f * s2) + a * (s3 - 2f * s2 + s)
    }
}


/**
 * 记住一个 [FlipController]。
 *
 * `flipAwait` / `forceOpenPos` 都随宿主与组合变化，故用
 * [androidx.compose.runtime.rememberUpdatedState] 包一层再传入，保证 controller
 * 内部永远调的是**最新**闭包，而 controller 自身（及其页面位图池引用）稳定。
 */
@Composable
fun rememberFlipController(
    flipAwait: suspend (Int) -> ReaderPos?,
    forceOpenPos: (ReaderPos) -> Unit,
    /** 封面会话 commit 后的显隐结算，见 [FlipController.onCoverCommit]。 */
    onCoverCommit: ((leavingCover: Boolean) -> Unit)? = null,
    scope: CoroutineScope,
    log: (String) -> Unit = {},
    /** 平台结算时长倍率（桌面 2，见 [FlipController] 构造参数）。常量，不随组合变化。 */
    settleScale: Float = 1f,
): FlipController {
    val flipAwaitRef = rememberUpdatedState(flipAwait)
    val forceRef = rememberUpdatedState(forceOpenPos)
    val coverCommitRef = rememberUpdatedState(onCoverCommit)
    val logRef = rememberUpdatedState(log)
    return remember {
        FlipController(
            flipAwait = { d -> flipAwaitRef.value(d) },
            forceOpenPos = { p -> forceRef.value(p) },
            scope = scope,
            log = { m -> logRef.value(m) },
            settleScale = settleScale,
        ).also { c ->
            // 经 State 中转，controller 记住的恒是最新闭包（封面显隐状态在其内部）。
            c.onCoverCommit = { leaving -> coverCommitRef.value?.invoke(leaving) }
        }
    }
}