package orilumn.reader.ui.reader

import kotlin.math.abs

/**
 * P1 翻页会话（用户层·纯状态机，commonMain）：把「一次翻页手势」从 UI 事件里剥出来。
 *
 * 为什么要单独一层：跟手滑动要求在 DRAG 期间就持有**连续 progress**，而落位（换页数据）
 * 只在 UP 时发生一次。这两件事的节奏不同，混在一个 `pointerInput` 块里必然长出
 * 互相污染的临时状态。剥出来之后它无 Compose 依赖、可 jvmTest 直测——而 progress
 * 的阈值与夹取写错**不会崩，只会慢慢坏**（跟手不对、回弹突兀），正是最需要单测兜住的一类。
 *
 * 语义取自死代码 `app/.../ui/reader/curl/CurlView.kt`（本文 §6.2 的权威参考）：
 *  - `progress = dx / pageW`，域 `[-0.9, 1.9]`（`CurlView.kt:128-131`）；
 *  - commit 600ms / rollback 500ms（`:196-210`）；
 *  - `p ≥ 0.9` 时**提前落位**，动画尾与落位重叠（`:215-221`）——
 *    否则会出现「看起来翻完了但点不动」：像素已到位、落位还在路上，那一拍的手势被吞。
 *
 * **本类不碰数据**：落位仍走 `AnchorFunnel.navigate`（显示状态的唯一写入通道）。
 * 本类只回答两件事——「现在该画成什么样」与「松手该 commit 还是 rollback」。
 */
class FlipSession {

    enum class Phase { Idle, Dragging, Settling }

    /**
     * UP 时的裁决。[COMMIT] 翻页，[ROLLBACK] 弹回。
     *
     * 判定用「位移过阈 **或** 甩得够快」：快甩是明确意图，即使位移没过半页也该翻；
     * 只看位移会让快速轻扫被吃掉（这是旧 `CurlView` 与多数阅读器的共同口径）。
     */
    enum class Decision { COMMIT, ROLLBACK }

    var phase: Phase = Phase.Idle
        private set

    /** 翻页方向：+1 下一页，-1 上一页。Idle 时为 0。 */
    var direction: Int = 0
        private set

    /**
     * 当前进度，**已按方向归一**：正值 = 朝目标页翻了这么多页，负值 = 往回拖。
     *
     * 归一是必须的：原始 `dx/pageW` 对「下一页」是负的（dx<0），若直接用它，
     * `beginSettle(COMMIT)` 会从 -0.6 跳到 +1（符号反了），动画中途整页瞬移。
     */
    var progress: Float = 0f
        private set

    /** 结算目标（commit → +1，rollback → 0）；非 Settling 时无意义。 */
    private var settleTarget: Float = 0f

    /**
     * 提前落位阈值（`CurlView.kt:215-221`）：commit 途中 progress 越过它就先落数据，
     * 让落位的耗时藏在剩余动画尾里。
     */
    var earlyCommitAt: Float = EARLY_COMMIT_AT
        private set

    /**
     * 手指按下、尚未定向：什么也不做（等越过 slop 才由 [onDrag] 定轴）。
     *
     * 幂等：已 Dragging 时再按一次是**丢弃**而不是重置——同一手势流里 DOWN 只会有一次，
     * 而重入意味着有两个手势在打架，此时静默忽略后来者比互相重置安全。
     */
    fun onDown(): Unit = Unit

    /**
     * 抬手前**最后一段**的位移方向（已按 [direction] 归一：正 = 仍朝目标页）。
     *
     * 为什么要单独记：用户常在松手前「犹豫一下往回带一点」——先拖过阈、临抬手
     * 又反向回拉。此时累计位移（[progress]）仍过阈 ⇒ 只看位移会照样翻页，
     * 但用户最后那一段的意图明明是「算了，不翻了」。这是「离屏前最后滑动方向」
     * 要回答的问题，也是手势里最常见的反悔动作。
     *
     * 与速度判据不同：抬手前手指停住再抬起时速度 ≈ 0，方向信息全靠这里。
     */
    private var tailDirection: Int = 0

    /**
     * 水平拖动。[dx] 是自按下起的累计横向位移，[pageW] 页宽（<=0 视为无效，按 0 处理）。
     *
     * 返回 true 表示会话进入/保持 Dragging（调用方据此决定要不要接管这次手势）。
     */
    fun onDrag(dx: Float, pageW: Float): Boolean {
        if (pageW <= 0f) return false
        val dir = ReaderMath.flipDirection(dx)
        return when (phase) {
            Phase.Idle -> {
                direction = dir
                progress = clampProgress(normalize(dx / pageW, dir))
                tailDirection = 0
                phase = Phase.Dragging
                true
            }
            // 方向锁定：手势起手就定死，中途反向不重新选（否则来回拖会左右横跳）。
            Phase.Dragging -> {
                val prev = progress
                progress = clampProgress(normalize(dx / pageW, direction))
                // 只在**确实动了**的那一段更新末段方向：手指停住不动时 dx 不变，
                // 不能把「静止」记成「回拉」，否则抬手前静止反被判成反悔。
                if (progress > prev + PROGRESS_EPSILON) tailDirection = 1
                else if (progress < prev - PROGRESS_EPSILON) tailDirection = -1
                true
            }
            // 结算中不接受新的拖动输入：动画尾正在落位，插手会让像素与数据错位。
            Phase.Settling -> false
        }
    }

    /**
     * 无拖动的程序化起手（点按翻页 / 方向键翻页）：没有手指，所以不经 [onDrag]，
     * 由调用方直接把方向定死并进入 [Phase.Dragging]，progress 从 **0** 起。
     *
     * 为什么不拿一个假位移去喂 [onDrag]：那会把 progress 写成非零，等于宣称
     * 「用户已经拖出去这么多了」——点按翻页并没有拖。progress 必须停在 0，
     * 随后的 [beginSettle] 才能从静止处把这一页平滑推走。
     *
     * 返回 false = 会话不空闲（有动画正在结算），调用方应**丢弃**这次翻页，
     * 与 [AnchorFunnel] 的 BUSY-DROP 同一口径：不排队、不打断进行中的动画。
     */
    fun beginProgrammatic(direction: Int): Boolean {
        if (direction == 0) return false
        if (phase != Phase.Idle) return false
        this.direction = direction
        progress = 0f
        tailDirection = 0
        phase = Phase.Dragging
        return true
    }

    /**
     * 松手裁决。[velocityX] 是抬手瞬间的横向速度（px/s，符号与 [dx] 同向；取不到传 0）。
     * [pageW] 是页宽（px），用于把 [COMMIT_THRESHOLD_PX] 这个绝对阈值换算成 progress；
     * 取不到（0）时退化到 [COMMIT_PROGRESS]（半页）。
     *
     * [progress] 已归一（正 = 朝目标页），位移判据读它 × pageW 得到实际像素位移；
     * 速度仍需按方向归一（[forwardFling] = `-velocityX * direction`，正 = 甩向目标页）。
     *
     * 两个判据取或：位移过 [COMMIT_THRESHOLD_PX]（约三四个字）**或** 甩得够快
     * （[FLING_VELOCITY]）。快甩是明确意图，即使位移没过阈也该翻；只看位移会把
     * 快速轻扫吃掉（`CurlView` 与多数阅读器的共同口径）。反向甩的 [forwardFling]
     * 为负，自然不触发 commit ⇒ 回退，即「起手左滑、抬手右甩」这种明确改主意的会被尊重。
     */
    fun decide(velocityX: Float = 0f, pageW: Float = 0f): Decision {
        if (direction == 0) return Decision.ROLLBACK
        val forwardFling = -velocityX * direction
        // 阈值：能拿到页宽就用绝对像素（跨设备手感一致），否则退回半页。
        val commitAt = if (pageW > 0f) (COMMIT_THRESHOLD_PX / pageW).coerceIn(0.01f, 0.5f) else COMMIT_PROGRESS
        // 末段反悔否决：累计位移/速度都说「翻」，但抬手前最后一段是**往回拉**的
        // ⇒ 用户改主意了，尊重它。放在位移与速度**之后**作为否决项——它是唯一的
        // 反向信号，两个正向判据都不能推翻它。
        val tailRejects = tailDirection < 0
        return if (!tailRejects && (progress >= commitAt || forwardFling >= FLING_VELOCITY)) {
            Decision.COMMIT
        } else {
            Decision.ROLLBACK
        }
    }

    /** 抬手前最后一段是否在往回拉（已归一：true = 反悔）。诊断/测试用。 */
    fun isTailRetreating(): Boolean = tailDirection < 0

    /**
     * 进入结算。[Decision.COMMIT] 朝 ±1 走，[ROLLBACK] 回 0。
     *
     * 裁决（[decide]）与进入结算分两步：调用方要先按裁决结果决定「要不要发起落位」，
     * 再推进动画——顺序反了会出现「像素已经翻过去了、数据还没换」的错位帧。
     *
     * **这里刻意不写 [progress]**：动画器把当前 progress 当起点（`Animatable(from)`），
     * 若此处就把它拨到终点，起终点相等 ⇒ 整段结算零位移，观感是「松手瞬移」而不是滑动。
     * progress 只由动画器的每帧回调 [onSettleProgress] 推进。
     *
     * 动画时长随之不同（commit 600ms / rollback 500ms，见 [settleDurationMs]）。
     */
    fun beginSettle(decision: Decision) {
        phase = Phase.Settling
        settleTarget = if (decision == Decision.COMMIT) 1f else 0f
    }

    /** 结算途中推进（由动画器每帧调用）。到端点即回 [Phase.Idle]。 */
    fun onSettleProgress(value: Float) {
        if (phase != Phase.Settling) return
        progress = clampProgress(value)
        if (abs(progress - settleTarget) < 1e-3f) {
            progress = 0f
            direction = 0
            settleTarget = 0f
            tailDirection = 0
            phase = Phase.Idle
        }
    }

    /**
     * 作废本次会话（外部改写了 `openPos`：重排/外部落位/seek）。
     *
     * 必须调：拿旧页配新数据比没有动画更糟——它是**静默错页**，用户看得见但无从报错。
     */
    fun abort() {
        phase = Phase.Idle
        direction = 0
        progress = 0f
        settleTarget = 0f
        tailDirection = 0
    }

    /**
     * 结算动画时长（ms）：commit 600 / rollback 500（`CurlView.kt:196-210`）。
     *
     * 速度折算：快甩时动画按「剩余进度 ÷ 速度」短促收尾（对过快甩动过慢的观感——
     * 甩得飞快还要匀速走满 600ms，读起来就是「释放后动画拖沓」），慢拖/点按保持默认。
     * [fromProgress] 是 settle 起点的归一 progress（屏上真实位置），[velocityX] 是松手瞬间
     * 横向速度（px/s，符号与位移同向，参见 [decide]）。速度取不到（程序化翻页传 0）时
     * 走默认时长，手势手感的基准不变。
     */
    fun settleDurationMs(decision: Decision, fromProgress: Float = 0f, velocityX: Float = 0f, pageW: Float = 0f): Int {
        val base = if (decision == Decision.COMMIT) COMMIT_MS else ROLLBACK_MS
        val speed = abs(velocityX)
        if (speed < FLING_VELOCITY) return base
        if (pageW <= 0f) return base
        val remaining = if (decision == Decision.COMMIT) (1f - fromProgress).coerceIn(0f, 1f) else fromProgress.coerceIn(0f, 1f)
        // 剩余进度按页宽换成像素距离，除以速度得秒数再放大成 ms；夹在 [MIN_ANIM_MS, base]。
        return (remaining * pageW / speed * 1000f).toInt().coerceIn(MIN_ANIM_MS, base)
    }

    /**
     * 结算动画的**起手斜率衔接系数** `a ∈ [0,1]`（用户层纯计算，可 jvmTest 直测）。
     *
     * ## 它修的是「翻一半停一下」
     *
     * 跟手阶段 progress 由手指逐帧驱动，松手瞬间页面速度 = 手指速度。若结算动画起手
     * 斜率为 0，页面会在离手那一刻**刹停**，再从静止重新加速 —— 观感正是「动画分成
     * 两截、中间停顿一下」，快甩与跨章连翻最明显。生产实现早先用的是 `tween` 默认的
     * `FastOutSlowInEasing`（起点斜率 = 0），而权威参考 `CurlView.kt:211` 用的是
     * `DecelerateInterpolator()`（起点斜率非零、一股冲劲衰减）——这一条当初被漏掉了。
     *
     * ## 定义（配合 `VelocityHandoffEasing`）
     *
     * 结算缓动 `u(s) = h01(s) + a·h10(s)`：`u(0)=0, u(1)=1, u'(0)=a, u'(1)=0`。
     * 要离手速度连续，需 `Δ · u'(0) / D = v₀`，其中 `Δ = target − fromProgress`、
     * `D = 时长`（秒）、`v₀ = d(progress)/dt = −velocityX·direction/pageW`（progress/s）。
     * 解得 `a = v₀·D/Δ`，夹在 `[0,1]`：`a=1` = 起手速度与手指完全一致（快甩），
     * `a=0` = 从静止缓起（点按 / 慢拖 / 拿不到速度 / 速度与去向相反）。
     *
     * 快甩时 [settleDurationMs] 取 `D = Δ·pageW/speed`，代回恰好 `a = 1` —— 两条口径
     * 天然自洽：时长那条本就是按「匀速走完剩余距离」定的。
     */
    fun settleInitialSlope(
        decision: Decision,
        fromProgress: Float,
        velocityX: Float,
        pageW: Float,
        durationMs: Int = settleDurationMs(decision, fromProgress, velocityX, pageW),
    ): Float {
        if (pageW <= 0f) return 0f
        val target = if (decision == Decision.COMMIT) 1f else 0f
        val delta = target - fromProgress
        if (abs(delta) < 1e-6f) return 0f
        // progress 的瞬时速度：progress = -dx·dir/pageW ⇒ d(progress)/dt = −velocityX·dir/pageW。
        val v0 = -velocityX * direction / pageW
        return (v0 * (durationMs / 1000f) / delta).coerceIn(0f, 1f)
    }

    /** 当前是否应做落位（提前落位窗口内为 true）。progress 已归一，直接比大小。 */
    fun shouldEarlyCommit(): Boolean =
        phase == Phase.Settling && direction != 0 && progress >= earlyCommitAt

    /** 结算是否刚刚结束（供调用方在帧回调里感知「该清会话了」）。 */
    fun isSettled(): Boolean = phase == Phase.Idle

    /**
     * 把原始 `dx/pageW` 归一成「朝目标页为正」。
     *
     * 手指方向与翻页方向天然相反（左滑 = 下一页 ⇒ dx<0 而 direction=+1），
     * 不翻转符号的话 `beginSettle(COMMIT)` 会朝 −1 走，动画从当前位置反向抽搐。
     */
    private fun normalize(raw: Float, dir: Int): Float = -raw * dir

    private fun clampProgress(v: Float): Float = v.coerceIn(-PROGRESS_MAX, PROGRESS_MAX)

    companion object {
        /** progress 域上限（`CurlView.kt:128-131`）：1.9 允许略微过冲，回弹有空间。 */
        const val PROGRESS_MAX = 1.9f

        /**
         * 位移过阈即翻页的**绝对像素**：约 3～4 个汉字宽（正文 18.5px × 3 ≈ 55px）。
         *
         * 早先用「页宽的一半」（[COMMIT_PROGRESS]）作阈值，在平板上那是几百像素——
         * 用户得把整页拖掉小半才翻页，体感是「怎么老弹回」。多数阅读器（duokan/moon
         * 的实测口径）都是**按内容尺寸给阈值**，不按页宽：手指划过三四个字就够，
         * 因为「开始滑动」本身就是明确意图。
         *
         * 为什么不用页宽比例：同一阈值在手机（页宽 ~1080px）与平板（~2560px）差一倍多，
         * 跨设备手感会漂。绝对像素才有「三四个字」这个跨设备稳定的语义。
         */
        const val COMMIT_THRESHOLD_PX = 55f

        /**
         * 位移过阈比例（页宽的 0.5）——仅在拿不到页宽时兜底（见 [decide]）。
         *
         * 保留是因为 `decide` 允许 `pageW = 0`（拿不到页面尺寸）；那时退化到
         * 「半页」而不是「零像素」（后者会让任何位移都翻页，误翻页比误弹回更烦）。
         */
        const val COMMIT_PROGRESS = 0.5f

        /**
         * 快甩阈值（px/s）：救「快速轻扫」，与位移判据取或。
         *
         * 早先取 800，在平板上随手一划就过 ⇒ 等于把「滑一点点也翻页」合法化，
         * 用户反馈的「回弹阈值太大」正是这条在起作用。收紧到 1400：只有**真的**
         * 甩起来才免位移，否则一律按半页线裁决。
         */
        const val FLING_VELOCITY = 1400f

        /** 结算动画时长下限（ms）：再快也不许「瞬切」级的视觉割裂。 */
        const val MIN_ANIM_MS = 180

        /**
         * 判定「这一段算不算动了」的 progress 死区。
         *
         * 手指停在屏幕上时 MOVE 事件仍会送来，但 dx 不变 ⇒ progress 不变。若不设死区，
         * 「抬手前静止」会被逐帧判成「仍在前进/回拉」，把末段方向判错。取 0.002
         * （约 5px @2560px 页宽）——小于一次正常 MOVE 的抖动，大于浮点噪声。
         */
        const val PROGRESS_EPSILON = 0.002f

        /** 提前落位阈值（`CurlView.kt:215-221`）。 */
        const val EARLY_COMMIT_AT = 0.9f

        const val COMMIT_MS = 600
        const val ROLLBACK_MS = 500
    }
}
