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
                phase = Phase.Dragging
                true
            }
            // 方向锁定：手势起手就定死，中途反向不重新选（否则来回拖会左右横跳）。
            Phase.Dragging -> {
                progress = clampProgress(normalize(dx / pageW, direction))
                true
            }
            // 结算中不接受新的拖动输入：动画尾正在落位，插手会让像素与数据错位。
            Phase.Settling -> false
        }
    }

    /**
     * 松手裁决。[velocityX] 是抬手瞬间的横向速度（px/s，符号与 [dx] 同向；取不到传 0）。
     *
     * [progress] 已归一（正 = 朝目标页），位移判据直接读它；速度仍需按方向归一
     * （[forwardFling] = `-velocityX * direction`，正 = 甩向目标页）。
     *
     * 两个判据取或：位移过阈 **或** 甩得够快。快甩是明确意图，即使位移没过半页也该翻；
     * 只看位移会让快速轻扫被吃掉（`CurlView` 与多数阅读器的共同口径）。
     * 反向甩的 [forwardFling] 为负，自然不触发 commit ⇒ 回退，即「起手左滑、
     * 抬手右甩」这种明确改主意的会被尊重。
     */
    fun decide(velocityX: Float = 0f): Decision {
        if (direction == 0) return Decision.ROLLBACK
        val forwardFling = -velocityX * direction
        return if (progress >= COMMIT_PROGRESS || forwardFling >= FLING_VELOCITY) {
            Decision.COMMIT
        } else {
            Decision.ROLLBACK
        }
    }

    /**
     * 进入结算。[Decision.COMMIT] 朝 ±1 走，[ROLLBACK] 回 0。
     *
     * 裁决（[decide]）与进入结算分两步：调用方要先按裁决结果决定「要不要发起落位」，
     * 再推进动画——顺序反了会出现「像素已经翻过去了、数据还没换」的错位帧。
     *
     * 动画时长随之不同（commit 600ms / rollback 500ms，见 [settleDurationMs]）。
     */
    fun beginSettle(decision: Decision) {
        phase = Phase.Settling
        settleTarget = if (decision == Decision.COMMIT) 1f else 0f
        progress = settleTarget
    }

    /** 结算途中推进（由动画器每帧调用）。到端点即回 [Phase.Idle]。 */
    fun onSettleProgress(value: Float) {
        if (phase != Phase.Settling) return
        progress = clampProgress(value)
        if (abs(progress - settleTarget) < 1e-3f) {
            progress = 0f
            direction = 0
            settleTarget = 0f
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
    }

    /** 结算动画时长（ms）：commit 600 / rollback 500（`CurlView.kt:196-210`）。 */
    fun settleDurationMs(decision: Decision): Int =
        if (decision == Decision.COMMIT) COMMIT_MS else ROLLBACK_MS

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

        /** 位移过阈即翻页（页宽的 0.5）。 */
        const val COMMIT_PROGRESS = 0.5f

        /** 甩动速度阈值（px/s）：过阈即翻，与位移判据取或。 */
        const val FLING_VELOCITY = 800f

        /** 提前落位阈值（`CurlView.kt:215-221`）。 */
        const val EARLY_COMMIT_AT = 0.9f

        const val COMMIT_MS = 600
        const val ROLLBACK_MS = 500
    }
}
