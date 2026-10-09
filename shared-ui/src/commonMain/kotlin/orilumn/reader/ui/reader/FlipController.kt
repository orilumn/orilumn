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
import kotlinx.coroutines.CoroutineScope
import orilumn.reader.io.Logger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    /** 起手那一页（正被拖走的那张）。落位在起手就发生，故 openPos 已是目标页。 */
    val fromPos: MutableState<ReaderPos?> = mutableStateOf(null)

    /** 目标页；null = 未就绪（此时渲染层不许平移当前页，见 [targetReady]）。 */
    val targetPos: MutableState<ReaderPos?> = mutableStateOf(null)

    private var prefetchJob: Job? = null
    private var settleJob: Job? = null

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
    fun slideActive(enabled: Boolean): Boolean = enabled && fromPos.value != null

    /** 是否有会话在飞（含结算）：点按、键盘、外部落位都据此避让。 */
    fun isBusy(): Boolean = session.phase != FlipSession.Phase.Idle

    /** 目标页是否已进渲染层：未就绪时**不许**平移当前页（否则露出底色空白）。 */
    fun targetReady(): Boolean = targetPos.value != null

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
        session.abort()
        fromPos.value = null
        targetPos.value = null
        progress.value = 0f
        direction.value = 0
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
        session.abort()
        progress.value = 0f
        scope.launch {
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
     */
    fun beginDrag(src: ReaderPos, direction: Int, enabled: Boolean, coverVisible: Boolean) {
        if (!enabled || coverVisible) return
        session.onDown()
        // 起手页 = 「当前页」身份，必须在这里存一份：落位在预取协程里发生，
        // 完成后 openPos 就是目标页了。不存的话渲染层 `fromPos ?: pos` 会退回用
        // pos（=目标页）⇒ 两张画布都画目标页，观感是「目标页空白、内容重复」。
        fromPos.value = src
        targetPos.value = null
        this.direction.value = direction
        progress.value = 0f
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
     * 与手势层那条 `FLIPLAT drag … lat=` 的关系：
     *  - 手势层 `lat` = 手指按下 → 第一帧页面位移（**用户真正感知的起手延迟**）；
     *  - 本函数 `ms` = 目标页落位就绪耗时（含 beforeCommit 等图，最长 800ms）。
     *
     * 端到端 ≈ `lat`（若 `target-ready` 早于首次位移，则完全由 `lat` 决定）。
     * 若 `target-ready` 晚于首次位移，那段差额就是「页面本该动却没动」的空窗，
     * 两条日志按 dir 与时间先后对齐即可读出来。
     */
    private fun logLat(dir: Int, phase: String, ms: Long) {
        log("FLIPLAT $phase dir=$dir ms=$ms")
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
        if (targetReady()) {
            settle(decision, velocityX, pageW)
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
     * 返回 false = 会话不空闲（有动画在飞），调用方应**丢弃**这次翻页，与
     * [AnchorFunnel] 的 BUSY-DROP 同一口径：不排队、不打断进行中的动画。
     * 紧接着调 [beginProgrammaticPage] 落位。
     */
    fun beginProgrammatic(direction: Int): Boolean = session.beginProgrammatic(direction)

    /**
     * 点按/方向键翻页的落位（无手指，progress 从 0 起）。
     *
     * 与拖动路径同样「先落位后出画」：[beginProgrammatic] 只把会话置为 Dragging、
     * 定死方向，数据落位由本方法在预取协程里做。
     *
     * [src] 是起手时的 `openPos`：它同时是「当前页」身份，落位后 openPos 会变成
     * 目标页，不另存一份的话渲染层会拿新页当当前页画（真机症状：目标页空白）。
     */
    fun beginProgrammaticPage(src: ReaderPos, direction: Int) {
        fromPos.value = src
        targetPos.value = null
        this.direction.value = direction
        progress.value = 0f
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
     * 跑结算动画（commit 走满位 / rollback 回零）。
     *
     * 起点必须取自 [FlipSession.beginSettle] **之前**的 progress：状态机进入结算后
     * 不再改写 progress（它只记目标），所以调用方手上这份就是「屏幕上真实的位置」。
     * 时长按甩动速度折算（快甩短促收尾），[pageW] 用于把进度换算回像素距离。
     */
    fun settle(
        decision: FlipSession.Decision,
        velocityX: Float,
        pageW: Float,
    ) {
        if (!allowAnimation) {
            // 测试路径：只推进状态机到端点，不启动 Animatable。
            session.beginSettle(decision)
            session.onSettleProgress(if (decision == FlipSession.Decision.COMMIT) 1f else 0f)
            if (decision == FlipSession.Decision.COMMIT) clear() else clear()
            return
        }
        val from = session.progress
        session.beginSettle(decision)
        val duration = session.settleDurationMs(decision, from, velocityX, pageW)
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
        settleJob?.cancel()
        settleJob = scope.launch {
            val anim = androidx.compose.animation.core.Animatable(from)
            val target = if (decision == FlipSession.Decision.COMMIT) 1f else 0f
            anim.animateTo(
                targetValue = target,
                animationSpec = spec,
            ) {
                // animateTo 的回调是无参的 Animatable.() -> Unit，当前值在 this.value 上。
                val v = value
                session.onSettleProgress(v)
                progress.value = v
            }
            if (decision == FlipSession.Decision.COMMIT) {
                // 数据早在起手那次 flipAwait 就已落定，动画走完即达成：清场即可。
                clear()
            } else {
                // 回弹：动画回到 0，同时把引擎指针翻回原页。
                rollback(navigateBack = true)
            }
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
    scope: CoroutineScope,
    log: (String) -> Unit = {},
): FlipController {
    val flipAwaitRef = rememberUpdatedState(flipAwait)
    val forceRef = rememberUpdatedState(forceOpenPos)
    val logRef = rememberUpdatedState(log)
    return remember {
        FlipController(
            flipAwait = { d -> flipAwaitRef.value(d) },
            forceOpenPos = { p -> forceRef.value(p) },
            scope = scope,
            log = { m -> logRef.value(m) },
        )
    }
}