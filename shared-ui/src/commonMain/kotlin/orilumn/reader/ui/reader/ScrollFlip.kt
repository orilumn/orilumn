package orilumn.reader.ui.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.round

/**
 * 桌面版滚动翻页：横滑（触控板双指左右滑 / 横向滚轮）与**鼠标滚轮**（竖向一格一页）翻页；
 * 触控板的**上下滑**不翻页。
 *
 * 2026-11-04 用户定口：双指上下滑是误触（想滚这一页、或只是手指在触控板上挪了一下），
 * 不能翻页；但鼠标滚轮照旧拨一格翻一页。两者在事件里长得像，只能按形状分：
 * 滚轮是**整数格**（±1..±3）、一格一格拨、没有配对事件；触控板是小数、8–17ms 一条的连续流。
 * 判据见 [isMouseWheelNotch] 与 [PAGE_SCROLL_WHEEL_MIN_GAP_MS]，拿不准就按触控板处理（不翻）。
 *
 * 但 macOS 会把**一格**滚轮平滑成一串小数事件（真机的一格：
 * `0.1 → 0.84 → 3.38 → 5.27 → … → 7.71`，整串 100–250ms，随后断流 200–1700ms），
 * 整数格判据认不出来，读者会以为「滚轮不起作用」。这一串按 [PAGE_SCROLL_WHEEL_BURST_GAP_MS]
 * 那段说明的**形状**认（只有竖向、收在峰值上），由调用方计时器收账。
 *
 * 横滑的判据是**净横向位移**（横向累计 − 纵向累计）：真实手势里两个轴分别报事件
 * （竖滑时横向只剩每事件零点几到一两个单位的噪声，横滑时横向几十个单位、纵向才一两个），
 * 相减够 [PAGE_SCROLL_FLIP_UNITS] 才算「读者是在横滑」，既不会把竖滑当横滑，
 * 也不要求读者精确地只走横轴（略微斜着滑照样翻）。
 */
internal const val PAGE_SCROLL_FLIP_UNITS: Float = 3f

/**
 * 两次滚动事件断流超过这么久（ms）就算**新手势**：翻页重新上膛。
 *
 * 触控板抬手后的惯性事件是 8–17ms 一条的连续流，中间不会断这么久。但真机上读者连着
 * 滑两把时，两把之间常只断 47–60ms（手没离开触控板，抬一下又落下），所以断流只是兜底，
 * 同向的第二把主要靠下面的「淡出再起手」认出来。
 */
internal const val PAGE_SCROLL_SESSION_GAP_MS: Long = 60L

/**
 * 掉头：反方向累计够这么多单位，就认作读者**往回滑**，立刻算新的一次。
 *
 * 不能拿「上一次翻页之后的净位移」去判——翻页之后惯性还会往原方向再走几百个单位，
 * 读者往回滑一点根本抵消不回来（旧实现在真机上表现为「往回滑没反应」）。
 */
internal const val PAGE_SCROLL_REVERSE_UNITS: Float = 2f

/**
 * 「淡出」判据：每事件量掉到这一把峰值的这一比例以下，就认为这一把正在收尾。
 *
 * 抬手后的惯性是平滑衰减的（真机上相邻两条只差 5% 上下）；读者松手换下一把时，
 * 重新落手会让事件量断层式掉下来（真机日志里 157.7 → 53.1、137.6 → 5.6 都是这种情况）。
 * 取四分之一而不是一半：同一次横滑的惯性本身有小起伏（真机见过 218 → 276 → 321），
 * 一半会把这种起伏当成「收尾」。
 */
internal const val PAGE_SCROLL_DECAY_RATIO: Float = 0.25f

/** 连续这么多条事件都掉到淡出线以下，才认为这一把真的收尾了（起手瞬间的一抖不算）。 */
internal const val PAGE_SCROLL_DECAY_STREAK: Int = 2

/** 峰值不到这个量的小手势不做淡出切分：量太小，涨落都是噪声。 */
internal const val PAGE_SCROLL_DECAY_PEAK_MIN: Float = 6f

/**
 * 淡出之后再**涨回来**：新的一条是谷底的两倍以上、且够一格，才算下一把起手。
 *
 * 只认「掉下去又涨回来」这一种形状：单看掉下去就翻，会在慢速横滑的惯性尾巴上多翻一页
 * （尾巴每一条都在衰减、但偶尔抖一下）；只认「涨」也不行，同一次横滑的惯性本身就有
 * 小起伏（真机见过 218 → 276 → 321）。
 */
internal const val PAGE_SCROLL_REARM_FACTOR: Float = 2f

/**
 * 鼠标滚轮一格与上一条滚动事件至少隔这么久（ms）。
 *
 * 触控板不管滑得多慢，事件是 8–17ms 一条的连续流；惯性尾巴更是密。滚轮一格一格拨，
 * 两格之间不会小于这个值。偶尔触控板的惯性事件正好落在 1.0 上（真机见过），
 * 靠这一条把它挡在「滚轮」之外。
 */
internal const val PAGE_SCROLL_WHEEL_MIN_GAP_MS: Long = 25L

/**
 * 滚轮被 macOS 平滑成的那一串，断流这么久（ms）就算这一格收完了。
 *
 * 真机上串内相邻两条最长隔 72ms（滚动越慢隔得越久），两格之间至少隔 167ms，
 * 取 130ms 两头都留余量。串没断完不能下结论——「最后一格是不是峰值」要看末尾。
 */
internal const val PAGE_SCROLL_WHEEL_BURST_GAP_MS: Long = 130L

/** 一串至少这么多条才算滚轮：真机一格 5–8 条，触控板的短促一抖只有 1–2 条。 */
internal const val PAGE_SCROLL_WHEEL_BURST_MIN_EVENTS: Int = 3

/** 一串的峰值不到这个量就不算：真机竖滑噪声都在 2 以下，真滚轮一格峰值 5.5–8.4。 */
internal const val PAGE_SCROLL_WHEEL_BURST_MIN_PEAK: Float = 2f

/** 一串的位移合计不到这个量就不算（真机一格 10.5–41）。 */
internal const val PAGE_SCROLL_WHEEL_BURST_MIN_TOTAL: Float = 4f

/** 一串从起手到收尾超过这么久（ms）就不算：真机一格 84–248ms。 */
internal const val PAGE_SCROLL_WHEEL_BURST_MAX_MS: Long = 320L

/**
 * 「收在峰值上」：末条的绝对量至少是峰值的这个比例。
 *
 * 滚轮这一串是系统把一格摊平的缓动曲线，画到峰值就没了；触控板抬手后是**惯性尾巴**，
 * 一路衰减到零点几（真机峰值 13.1 收到 0.9、峰值 5.6 收到 0.4）。
 */
internal const val PAGE_SCROLL_WHEEL_BURST_END_RATIO: Float = 0.5f

/** 起手的绝对量不能超过峰值的这个比例：滚轮头一条总是很小（真机恒为 0.1）。 */
internal const val PAGE_SCROLL_WHEEL_BURST_START_RATIO: Float = 0.5f

/**
 * 连拨时两格会连成一串：同向串里数值掉回峰值的这个比例以下，就是下一格的起手。
 *
 * 真机连拨两格：`… 7.71` 断 311ms 之后 `0.1 → 0.99 → …`；拨得再快一点就并成一条流，
 * 中间会掉回 0.1 再涨。
 */
internal const val PAGE_SCROLL_WHEEL_BURST_RESTART_RATIO: Float = 0.3f

/** 翻页动画在飞时把这次滚动攒下来，等动画落地再补翻；等超过这么久（ms）就丢弃。 */
internal const val PAGE_SCROLL_PENDING_TTL_MS: Long = 600L

/**
 * 这条竖向滚动量是不是「鼠标滚轮的一格」：整数格 ±1..±3（误差 < 0.05）。
 *
 * 触控板报的是小数（0.2、1.3、16.9……），惯性尾巴偶尔正好是 1.0，所以还要配合
 * [PAGE_SCROLL_WHEEL_MIN_GAP_MS] 一起判。
 */
internal fun isMouseWheelNotch(dy: Float): Boolean {
    val a = abs(dy)
    return a >= 0.9f && a <= 3.05f && abs(a - round(a)) < 0.05f
}


/**
 * 把桌面滚动的原始事件判成「翻页方向」的纯状态机（无 Compose 依赖，可直测）。
 *
 * 一次连续滚动（下文叫「一把」）**最多翻一页**：翻过之后同向的惯性事件一律吞掉，
 * 否则一次甩手连翻好几页。下面三种情况算**新的一把**：
 * 1. 断流超过 [PAGE_SCROLL_SESSION_GAP_MS]；
 * 2. 这一把已经淡出（[PAGE_SCROLL_DECAY_RATIO]）之后，量又涨回来
 *    （[PAGE_SCROLL_REARM_FACTOR] 倍谷底且够一格）;
 * 3. 掉头：反方向累计够 [PAGE_SCROLL_REVERSE_UNITS]。
 *
 * 触控板的上下滑不翻页，这里连方向都不返回（返回 0）；**鼠标滚轮**拨一格翻一页
 * （判据见 [isMouseWheelNotch]）。
 */
internal class ScrollFlipArbiter {

    private var lastMs = Long.MIN_VALUE

    /** 当前这一把的净位移（从起手算起），以及这一把是否已经翻过。 */
    private var tryX = 0f
    private var tryY = 0f
    private var tryFlipped = false

    /** 当前这一把的横向方向（+1 / -1 / 0 还没定）。 */
    private var motionSign = 0
    /** 反方向累计：够 [PAGE_SCROLL_REVERSE_UNITS] 才算掉头。 */
    private var reverseX = 0f

    /** 这一把的横向峰值、淡出后的谷底、是否已经进入淡出、连续低于淡出线的条数。 */
    private var peakAbs = 0f
    private var valleyAbs = 0f
    private var decaying = false
    private var lowStreak = 0

    /** 最近一条事件距上一条的间隔（ms；首条为 -1）。 */
    var lastGapMs: Long = -1
        private set

    // ---- 滚轮被系统平滑成的那一串（判据见 PAGE_SCROLL_WHEEL_BURST_*） ----
    private var burstStartMs = Long.MIN_VALUE
    private var burstLastMs = Long.MIN_VALUE
    private var burstCount = 0
    private var burstTotal = 0f
    private var burstPeak = 0f
    private var burstFirstAbs = 0f
    private var burstLastAbs = 0f
    private var burstSign = 0
    /** 这一串里夹过横向事件（触控板的轴向拆分）⇒ 是触控板，不是滚轮。 */
    private var burstTainted = false

    /**
     * 当前这一串的版本号：起新串、判完清空都会 +1。
     *
     * 外部计时器（[WheelBurstTimer]）记下调度时的版本号，醒来发现变了就说明这串已经
     * 收过账 / 换了一串，不能再按旧的一串判。
     */
    var wheelBurstVersion: Int = 0
        private set

    /** 清空全部状态（卡片开关、外部落位等场合由调用方显式调）。 */
    fun reset() {
        lastMs = Long.MIN_VALUE
        beginTrial(0)
        clearBurst()
    }

    /**
     * 喂一条滚动事件，返回要翻的方向（1 下一页 / -1 上一页 / 0 不翻）。
     *
     * @param nowMs 事件时间（`change.uptimeMillis`，与调用方同源，不能混用别的时钟）
     */
    fun onScroll(nowMs: Long, dx: Float, dy: Float): Int {
        lastGapMs = if (lastMs == Long.MIN_VALUE) -1L else nowMs - lastMs
        // 断流只重置**横滑**这一把；滚轮那一串有自己的断流判据（串内最长可隔 72ms），
        // 不能让 60ms 的会话间隔把它清掉。
        if (lastGapMs > PAGE_SCROLL_SESSION_GAP_MS) beginTrial(0)
        lastMs = nowMs
        // 横向事件：滚轮那一串里出现横向，就是触控板的轴向拆分（同一帧拆成竖一条、横一条），
        // 这一串作废。
        if (dx != 0f && burstCount > 0) burstTainted = true
        // 纵向事件只记进这一把的纵向量：竖滑时它远大于横向噪声，净横向永远凑不够一格。
        if (dx == 0f) {
            tryY += dy
            return accumulateWheelBurst(nowMs, dy)
        }
        val adx = abs(dx)
        val sign = if (dx > 0f) 1 else -1
        // 掉头：反方向先累计，够一格（而不是一点点噪声）才算读者往回滑。
        if (motionSign != 0 && sign != motionSign) {
            reverseX += dx
            if (abs(reverseX) < PAGE_SCROLL_REVERSE_UNITS) {
                // 还没掉过头：只抵消这一把的净位移，不翻。
                tryX += dx
                tryY += dy
                return 0
            }
            beginTrial(sign)
            return addAndMaybeFlip(dx, dy, adx)
        }
        reverseX = 0f
        motionSign = sign
        if (tryFlipped) {
            // 这一把已经翻过：先等它淡出，再从谷底涨回来才算下一把。
            peakAbs = max(peakAbs, adx)
            if (!decaying) {
                if (peakAbs >= PAGE_SCROLL_DECAY_PEAK_MIN &&
                    adx <= peakAbs * PAGE_SCROLL_DECAY_RATIO
                ) {
                    lowStreak++
                    if (lowStreak >= PAGE_SCROLL_DECAY_STREAK) {
                        decaying = true
                        valleyAbs = adx
                    }
                } else {
                    lowStreak = 0
                }
                return 0
            }
            if (adx < valleyAbs) {
                valleyAbs = adx
                return 0
            }
            if (adx < PAGE_SCROLL_FLIP_UNITS || adx < valleyAbs * PAGE_SCROLL_REARM_FACTOR) return 0
            beginTrial(sign)
            return addAndMaybeFlip(dx, dy, adx)
        }
        return addAndMaybeFlip(dx, dy, adx)
    }

    /** 开新的一把：清空累计与淡出状态，横向方向记成 [sign]（0 = 还没定）。 */
    private fun beginTrial(sign: Int) {
        tryX = 0f
        tryY = 0f
        tryFlipped = false
        motionSign = sign
        reverseX = 0f
        peakAbs = 0f
        valleyAbs = 0f
        decaying = false
        lowStreak = 0
    }

    private fun addAndMaybeFlip(dx: Float, dy: Float, adx: Float): Int {
        tryX += dx
        tryY += dy
        peakAbs = max(peakAbs, adx)
        if (tryFlipped) return 0
        // 净横向：横向走的量要压过纵向噪声整整一格，才算读者在横滑。
        if (tryX * motionSign - abs(tryY) < PAGE_SCROLL_FLIP_UNITS) return 0
        tryFlipped = true
        return motionSign
    }

    /**
     * 竖向事件攒进「滚轮一串」候选；只有在**上一串收完**（断流、掉头、或下一格起手）时
     * 才返回上一串的翻页方向。串尾要等调用方计时器调 [finishWheelBurst]。
     */
    private fun accumulateWheelBurst(nowMs: Long, dy: Float): Int {
        val a = abs(dy)
        val sign = if (dy > 0f) 1 else -1
        if (burstCount > 0) {
            // 非平滑滚轮（系统没做缓动的鼠标）：一条就是一整格，隔开 25ms 以上就是下一格。
            // 这条必须在「连拨掉到三成以下」之前判：整格 1.0 不会掉到 0.3。
            if (isMouseWheelNotch(dy) && nowMs - burstLastMs >= PAGE_SCROLL_WHEEL_MIN_GAP_MS) {
                val dir = finishWheelBurst()
                startBurst(nowMs, dy)
                return dir
            }
            val ended = nowMs - burstLastMs > PAGE_SCROLL_WHEEL_BURST_GAP_MS
            // 掉头也是新的一格（上滚紧接着下滚）。
            if (ended || sign != burstSign) {
                val dir = finishWheelBurst()
                startBurst(nowMs, dy)
                return dir
            }
            // 连拨：数值掉回峰值的三成以下，上一格到此为止，这一条是下一格的起手。
            if (burstCount >= PAGE_SCROLL_WHEEL_BURST_MIN_EVENTS &&
                burstPeak >= PAGE_SCROLL_WHEEL_BURST_MIN_PEAK &&
                a < burstPeak * PAGE_SCROLL_WHEEL_BURST_RESTART_RATIO
            ) {
                val dir = finishWheelBurst()
                startBurst(nowMs, dy)
                return dir
            }
        } else {
            startBurst(nowMs, dy)
            return 0
        }
        burstLastMs = nowMs
        burstCount++
        burstTotal += dy
        burstLastAbs = a
        if (a > burstPeak) burstPeak = a
        return 0
    }

    /**
     * 这一串到此为止，按形状判它是不是**一格鼠标滚轮**，返回翻页方向（0 = 不是）。
     *
     * 由调用方在断流 [PAGE_SCROLL_WHEEL_BURST_GAP_MS] 后调；判完一定清空这一串。
     */
    fun finishWheelBurst(): Int {
        val dir = evaluateWheelBurst()
        clearBurst()
        return dir
    }

    private fun evaluateWheelBurst(): Int {
        // 非平滑滚轮：一格就是一条整数量（±1..±3）。
        if (burstCount == 1) {
            return if (!burstTainted && isMouseWheelNotch(burstTotal)) burstSign else 0
        }
        if (burstCount < PAGE_SCROLL_WHEEL_BURST_MIN_EVENTS) return 0
        // 夹过横向事件 = 触控板的轴向拆分，不是滚轮。
        if (burstTainted) return 0
        if (burstPeak < PAGE_SCROLL_WHEEL_BURST_MIN_PEAK) return 0
        if (abs(burstTotal) < PAGE_SCROLL_WHEEL_BURST_MIN_TOTAL) return 0
        if (burstLastMs - burstStartMs > PAGE_SCROLL_WHEEL_BURST_MAX_MS) return 0
        // 收在峰值上：末条还贴着峰值。惯性尾巴会一路衰减到零点几。
        if (burstLastAbs < burstPeak * PAGE_SCROLL_WHEEL_BURST_END_RATIO) return 0
        // 起手必须很小：滚轮是把一格摊成一串缓动，头一条不会已经有峰值的份量。
        if (burstFirstAbs > burstPeak * PAGE_SCROLL_WHEEL_BURST_START_RATIO) return 0
        return burstSign
    }

    private fun startBurst(nowMs: Long, dy: Float) {
        burstStartMs = nowMs
        burstLastMs = nowMs
        burstCount = 1
        burstTotal = dy
        burstPeak = abs(dy)
        burstFirstAbs = abs(dy)
        burstLastAbs = abs(dy)
        burstSign = if (dy > 0f) 1 else -1
        burstTainted = false
        wheelBurstVersion++
    }

    private fun clearBurst() {
        burstStartMs = Long.MIN_VALUE
        burstLastMs = Long.MIN_VALUE
        burstCount = 0
        burstTotal = 0f
        burstPeak = 0f
        burstFirstAbs = 0f
        burstLastAbs = 0f
        burstSign = 0
        burstTainted = false
        wheelBurstVersion++
    }
}

/**
 * 滚轮一串的**断流计时器**：每来一条竖向滚动就把计时推后，静默满
 * [PAGE_SCROLL_WHEEL_BURST_GAP_MS] 才回调，让调用方去问 [ScrollFlipArbiter.finishWheelBurst]。
 *
 * 为什么非要等：这一格是不是滚轮，要看**末尾**是不是收在峰值上；先到的那几条跟触控板
 * 竖向滑长得一模一样，当场判就会误翻。
 */
internal class WheelBurstTimer {

    private var job: Job? = null

    /** 重新计时（上一次还没醒就作废）。 */
    fun restart(scope: CoroutineScope, fire: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(PAGE_SCROLL_WHEEL_BURST_GAP_MS)
            fire()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * 翻页动画在飞时攒下**一次**滚动翻页，等动画落地补上。
 *
 * 读者连滑两下、第二下正好碰上翻页动画（桌面版滑动约 300ms），此前的实现在动画期间
 * 直接丢弃 ⇒ 读者觉得「滑了没反应」。这里只留一个槽：同一时间补一次就够，
 * 攒多次会让读者滚了一堆之后莫名连翻好几页。
 */
internal class PendingScrollFlip {

    private var dir = 0
    private var job: Job? = null

    fun offer(
        direction: Int,
        scope: CoroutineScope,
        isBusy: () -> Boolean,
        fire: (Int) -> Unit,
    ) {
        dir = direction
        if (job?.isActive == true) return
        job = scope.launch {
            var waited = 0L
            while (isBusy()) {
                if (waited >= PAGE_SCROLL_PENDING_TTL_MS) {
                    dir = 0
                    return@launch
                }
                delay(16L)
                waited += 16L
            }
            val pending = dir
            dir = 0
            if (pending != 0) fire(pending)
        }
    }

    /** 立刻落地一次翻页，槽里的补翻作废（同一次手势已经翻过了）。 */
    fun clear() {
        dir = 0
    }
}
