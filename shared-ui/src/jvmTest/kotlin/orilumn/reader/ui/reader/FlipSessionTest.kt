package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 [FlipSession] 的状态迁移锁。
 *
 * 这些判据写错**不会崩，只会慢慢坏**：跟手不对、回弹突兀、翻完了点不动。
 * 所以每条边界都锁死——尤其是 progress 的夹取与 commit/rollback 的分界。
 */
class FlipSessionTest {

    private fun session() = FlipSession()

    @Test
    fun `onDrag 必须能从 Idle 自行进入 Dragging——调用方不得预判 phase`() {
        // 回归锁：ReaderScreen.updateSlide 曾写成「还是 Idle 就 return」，
        // 而 Idle→Dragging 的迁移正是 onDrag 干的活 ⇒ 永远进不了 Dragging，
        // 症状是「完全拖不动」且不报错。这条锁死调用契约。
        val s = session()
        assertEquals(FlipSession.Phase.Idle, s.phase)
        assertTrue("首次 onDrag 就该接管手势", s.onDrag(dx = -50f, pageW = 1000f))
        assertEquals(FlipSession.Phase.Dragging, s.phase)
        assertTrue("progress 已非零（渲染才有位移）", s.progress > 0f)
    }

    @Test
    fun `程序化起手点按即翻——progress 从 0 起`() {
        // 点按/方向键没有手指，不能拿假位移喂 onDrag：progress 必须是 0，
        // 否则 beginSettle 的起点被抬高，点按翻页会「从中途开始滑」。
        val s = session()
        assertTrue(s.beginProgrammatic(1))
        assertEquals(FlipSession.Phase.Dragging, s.phase)
        assertEquals(1, s.direction)
        assertEquals(0f, s.progress, 0f)
    }

    @Test
    fun `程序化起手上上一页方向为负`() {
        val s = session()
        assertTrue(s.beginProgrammatic(-1))
        assertEquals(-1, s.direction)
        assertEquals(0f, s.progress, 0f)
    }

    @Test
    fun `程序化起手无方向不入会话`() {
        assertFalse(session().beginProgrammatic(0))
    }

    @Test
    fun `程序化起手在结算中拒绝——新翻页须被丢弃而非打断动画`() {
        val s = session()
        assertTrue(s.beginProgrammatic(1))
        s.beginSettle(FlipSession.Decision.COMMIT)
        assertFalse("结算中再来一次翻页必须丢弃", s.beginProgrammatic(-1))
        assertEquals(FlipSession.Phase.Settling, s.phase)
        assertEquals(1, s.direction)
    }

    @Test
    fun `Settling 时 onDrag 返回 false 但不改状态`() {
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        s.beginSettle(s.decide())
        assertFalse(s.onDrag(dx = -900f, pageW = 1000f))
        assertEquals(FlipSession.Phase.Settling, s.phase)
    }

    @Test
    fun `初始为空闲`() {
        val s = session()
        assertEquals(FlipSession.Phase.Idle, s.phase)
        assertEquals(0, s.direction)
        assertEquals(0f, s.progress, 0f)
    }

    @Test
    fun `左滑是下一页`() {
        val s = session()
        assertTrue(s.onDrag(dx = -100f, pageW = 1000f))
        assertEquals(FlipSession.Phase.Dragging, s.phase)
        assertEquals(1, s.direction)
        assertEquals(0.1f, s.progress, 1e-4f)
    }

    @Test
    fun `右滑是上一页`() {
        val s = session()
        s.onDrag(dx = 100f, pageW = 1000f)
        assertEquals(-1, s.direction)
        assertEquals(0.1f, s.progress, 1e-4f)
    }

    @Test
    fun `页宽非正不进会话——除零会让 progress 变 NaN 并静默毁掉整条动画`() {
        val s = session()
        assertFalse(s.onDrag(dx = -100f, pageW = 0f))
        assertFalse(s.onDrag(dx = -100f, pageW = -10f))
        assertEquals(FlipSession.Phase.Idle, s.phase)
    }

    @Test
    fun `progress 夹在域内——过冲有限但不为零`() {
        val s = session()
        s.onDrag(dx = -5000f, pageW = 1000f)
        assertEquals(FlipSession.PROGRESS_MAX, s.progress, 1e-4f)
        s.onDrag(dx = 5000f, pageW = 1000f)
        // 方向锁定：不会因为反向拖而改朝上翻。
        assertEquals(1, s.direction)
        // 归一后往回拖是负值，且同样被夹住（否则回拖过冲会飞出域外）。
        assertEquals(-FlipSession.PROGRESS_MAX, s.progress, 1e-4f)
    }

    @Test
    fun `方向起手锁定——拖到另一边不重新选向`() {
        val s = session()
        s.onDrag(dx = -300f, pageW = 1000f)
        assertEquals(1, s.direction)
        s.onDrag(dx = 300f, pageW = 1000f) // 手指折回右边
        assertEquals(1, s.direction)
        assertEquals(-0.3f, s.progress, 1e-4f)
    }

    @Test
    fun `位移过阈判 commit`() {
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f) // progress -0.6
        assertEquals(FlipSession.Decision.COMMIT, s.decide())
    }

    @Test
    fun `位移不足判 rollback`() {
        val s = session()
        s.onDrag(dx = -300f, pageW = 1000f) // progress -0.3
        assertEquals(FlipSession.Decision.ROLLBACK, s.decide())
    }

    @Test
    fun `恰好在阈值上判 commit——边界含等号`() {
        val s = session()
        s.onDrag(dx = -500f, pageW = 1000f) // progress -0.5 == COMMIT_PROGRESS
        assertEquals(FlipSession.Decision.COMMIT, s.decide())
    }

    @Test
    fun `快甩过阈即翻页——位移没过半也算明确意图`() {
        val s = session()
        s.onDrag(dx = -200f, pageW = 1000f) // 只走了 0.2 页
        assertEquals(FlipSession.Decision.ROLLBACK, s.decide(velocityX = 0f))
        assertEquals(FlipSession.Decision.COMMIT, s.decide(velocityX = -2000f))
    }

    @Test
    fun `反方向快甩判回退`() {
        val s = session()
        s.onDrag(dx = -200f, pageW = 1000f) // 上一页方向
        assertEquals(FlipSession.Decision.ROLLBACK, s.decide(velocityX = 2000f))
    }

    @Test
    fun `commit 进入结算后 progress 停在拖动处——起点由动画器取走`() {
        // 回归锁：beginSettle 曾把 progress 直接写成结算目标，于是动画器拿到
        // Animatable(from=目标)，animateTo(同一目标) 零位移 ⇒ 松手瞬间整页瞬移。
        // 起点必须留给 onSettleProgress 逐帧推进。
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        s.beginSettle(FlipSession.Decision.COMMIT)
        assertEquals(FlipSession.Phase.Settling, s.phase)
        assertEquals(0.6f, s.progress, 1e-4f)
    }

    @Test
    fun `commit 结算终点是满位`() {
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        s.beginSettle(FlipSession.Decision.COMMIT)
        s.onSettleProgress(1f)
        assertEquals(FlipSession.Phase.Idle, s.phase)
    }

    @Test
    fun `rollback 结算起点停在拖动处`() {
        val s = session()
        s.onDrag(dx = -200f, pageW = 1000f)
        s.beginSettle(FlipSession.Decision.ROLLBACK)
        assertEquals(FlipSession.Phase.Settling, s.phase)
        assertEquals(0.2f, s.progress, 1e-4f)
        s.onSettleProgress(0f)
        assertEquals(FlipSession.Phase.Idle, s.phase)
    }

    @Test
    fun `结算到端点即回空闲`() {
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        s.beginSettle(s.decide())
        s.onSettleProgress(0.5f)
        assertEquals(FlipSession.Phase.Settling, s.phase)
        s.onSettleProgress(1f)
        assertEquals(FlipSession.Phase.Idle, s.phase)
        assertEquals(0f, s.progress, 0f)
        assertEquals(0, s.direction)
    }

    @Test
    fun `上一页结算同样走到 +1——归一后两个方向共用一条路径`() {
        val s = session()
        s.onDrag(dx = 600f, pageW = 1000f)
        assertEquals(-1, s.direction)
        s.beginSettle(s.decide())
        s.onSettleProgress(0.5f)
        assertEquals(FlipSession.Phase.Settling, s.phase)
        s.onSettleProgress(1f)
        assertEquals(FlipSession.Phase.Idle, s.phase)
    }

    @Test
    fun `提前落位窗口`() {
        val s = session()
        s.onDrag(dx = -1000f, pageW = 1000f)
        s.beginSettle(s.decide())
        s.onSettleProgress(0.5f)
        assertFalse("未到 0.9 不提前落位", s.shouldEarlyCommit())
        s.onSettleProgress(0.95f)
        assertTrue(s.shouldEarlyCommit())
    }

    @Test
    fun `结算中不接受新的拖动——否则动画尾与新手势打架`() {
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        s.beginSettle(s.decide())
        assertFalse(s.onDrag(dx = -900f, pageW = 1000f))
    }

    @Test
    fun `非结算态的推进是空操作`() {
        val s = session()
        s.onSettleProgress(0.5f)
        assertEquals(FlipSession.Phase.Idle, s.phase)
        assertEquals(0f, s.progress, 0f)
    }

    @Test
    fun `作废回到空闲——外部改写 openPos 时必须走这条`() {
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        s.beginSettle(s.decide())
        s.abort()
        assertEquals(FlipSession.Phase.Idle, s.phase)
        assertEquals(0f, s.progress, 0f)
        assertEquals(0, s.direction)
        assertTrue(s.isSettled())
    }

    @Test
    fun `时长——commit 300 rollback 250（CurlView 基准的两倍速）`() {
        val s = session()
        assertEquals(300, s.settleDurationMs(FlipSession.Decision.COMMIT))
        assertEquals(250, s.settleDurationMs(FlipSession.Decision.ROLLBACK))
    }

    @Test
    fun `空方向会话结算即收敛`() {
        // 防御：direction==0（不该发生）时不能卡在 Settling 永不归位。
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        s.abort()
        s.beginSettle(FlipSession.Decision.ROLLBACK)
        s.onSettleProgress(0f)
        assertEquals(FlipSession.Phase.Idle, s.phase)
    }

    @Test
    fun `慢拖时长保持默认——速度未过甩动阈值`() {
        val s = session()
        // 400px/s < 800 阈值：无论拖到哪，时长都不折算。
        assertEquals(300, s.settleDurationMs(FlipSession.Decision.COMMIT, 0.5f, 400f, 1000f))
        assertEquals(250, s.settleDurationMs(FlipSession.Decision.ROLLBACK, 0.5f, 400f, 1000f))
    }

    @Test
    fun `快甩时长按剩余距离折算——甩越快收得越快`() {
        val s = session()
        // 2000px/s 甩动、剩余半页（500px）：500/2000*1000 = 250ms（仍 < 默认 300ms）。
        val duration = s.settleDurationMs(FlipSession.Decision.COMMIT, fromProgress = 0.5f, velocityX = 2000f, pageW = 1000f)
        assertEquals(250, duration)
        // 更快的 4000px/s：500/4000*1000 = 125ms（仍 > 下限）。
        assertEquals(125, s.settleDurationMs(FlipSession.Decision.COMMIT, 0.5f, 4000f, 1000f))
        // 10000px/s：500/10000*1000 = 50ms → 被下限托住（不许瞬切）。
        assertEquals(FlipSession.MIN_ANIM_MS, s.settleDurationMs(FlipSession.Decision.COMMIT, 0.5f, 10000f, 1000f))
    }

    @Test
    fun `快甩 rollback 同样折算——回弹也跟手速`() {
        val s = session()
        // fromProgress=0.2、rollback 剩余 0.2 页（200px）、3000px/s：200/3000*1000 ≈ 66ms → 下限。
        assertEquals(FlipSession.MIN_ANIM_MS, s.settleDurationMs(FlipSession.Decision.ROLLBACK, 0.2f, 3000f, 1000f))
    }

    @Test
    fun `速度折算拿不到页宽时回退默认——程序化翻页不意外变速`() {
        val s = session()
        // pageW=0（点按翻页不传页面尺寸）：即使速度虚构为快，也走默认 300ms。
        assertEquals(300, s.settleDurationMs(FlipSession.Decision.COMMIT, 0.5f, 2000f, 0f))
        // 速度取不到（程序化翻页传 0）：默认时长。
        assertEquals(300, s.settleDurationMs(FlipSession.Decision.COMMIT))
    }

    @Test
    fun `少量慢速滑动不再误判为点击——点按判据是「没动过」`() {
        // 手势层早先以 dragDir==0 代理「没动过」，而 dragDir 只在定轴 HORIZONTAL
        // 后才赋值 ⇒ 未过 slop 的少量滑动会带着 dragDir==0 落进点按分支。
        // movedBeyondTapSlop 不要求定轴：任一轴过 slop 就不算点按。
        assertFalse("未动过才是点按", ReaderMath.movedBeyondTapSlop(0f, 0f))
        assertFalse("小幅抖动仍算点按", ReaderMath.movedBeyondTapSlop(8f, 3f))
        assertTrue(
            "未过 slop 的横滑：定轴为 NONE，但确实动过 ⇒ 不是点按",
            ReaderMath.movedBeyondTapSlop(30f, 5f),
        )
        assertTrue(
            "纵向占优被判 VERTICAL 的那一路，同样不该落进点按",
            ReaderMath.movedBeyondTapSlop(5f, 60f),
        )
    }

    @Test
    fun `未就绪期间位移仍累积——否则松手把「拖满页」判成「没动过」`() {
        // 回归锁：目标页未就绪（落位/等图在途）时，ReaderScreen 早先整段 return，
        // 既不写回 progress 也不喂状态机 ⇒ progress 恒 0 ⇒ 松手 decide() 判
        // ROLLBACK，用户拖了满页却被弹回。现在 onDrag 无条件喂，仅渲染层不写回。
        val s = session()
        // 模拟「未就绪」期间的三帧拖动：只有 onDrag，没有渲染写回。
        assertTrue(s.onDrag(dx = -200f, pageW = 1000f))
        assertTrue(s.onDrag(dx = -600f, pageW = 1000f))
        assertTrue(s.onDrag(dx = -900f, pageW = 1000f))
        // 关键：progress 必须反映真实拖动量（90%），而不是 0。
        assertTrue("progress 应累积到 0.9，实际 ${s.progress}", s.progress > 0.85f)
        // 于是松手裁决为 COMMIT —— 用户「开始滑动就是要翻页」的意图被尊重。
        assertEquals(FlipSession.Decision.COMMIT, s.decide(velocityX = 0f))
    }

    @Test
    fun `回弹阈值是三四个字宽——拖过约55px 即翻页`() {
        val s = session()
        // 平板页宽约 2560px：拖 55px（三个汉字）就该翻，早先的「半页」要 1280px。
        s.onDrag(dx = -60f, pageW = 2560f)
        assertEquals(FlipSession.Decision.COMMIT, s.decide(velocityX = 0f, pageW = 2560f))

        val s2 = session()
        // 未过阈（约 40px < 55px）⇒ 回滚。
        s2.onDrag(dx = -40f, pageW = 2560f)
        assertEquals(FlipSession.Decision.ROLLBACK, s2.decide(velocityX = 0f, pageW = 2560f))
    }

    @Test
    fun `阈值是绝对像素而非页宽比例——同一拖动距离在大小屏都翻`() {
        // 手机页宽 ~1080px：拖 60px 同样过阈（3.5% 页宽）。
        val phone = session()
        phone.onDrag(dx = -60f, pageW = 1080f)
        assertEquals(FlipSession.Decision.COMMIT, phone.decide(velocityX = 0f, pageW = 1080f))
        // 平板页宽 ~2560px：同样 60px 也过阈。若按页宽比例，手机会过、平板不会。
        val tablet = session()
        tablet.onDrag(dx = -60f, pageW = 2560f)
        assertEquals(FlipSession.Decision.COMMIT, tablet.decide(velocityX = 0f, pageW = 2560f))
    }

    @Test
    fun `拿不到页宽时退回半页——不是零像素`() {
        val s = session()
        s.onDrag(dx = -400f, pageW = 1000f) // 40% 页宽
        // pageW=0（拿不到页面尺寸）：退回半页阈值 ⇒ 40% 不够，回滚。
        // 不能退成「零像素」，那会让任何微小位移都翻页，误翻页比误弹回更烦。
        assertEquals(FlipSession.Decision.ROLLBACK, s.decide(velocityX = 0f, pageW = 0f))
    }

    @Test
    fun `快甩仍能救快速轻扫——小位移高速度照样翻`() {
        val s = session()
        s.onDrag(dx = -30f, pageW = 2560f) // 远未到 55px
        assertEquals(FlipSession.Decision.COMMIT, s.decide(velocityX = -2000f, pageW = 2560f))
    }

    @Test
    fun `末段回拉否决翻页——拖过阈又往回带就弹回`() {
        val s = session()
        // 先拖过阈（-200px > 55px 阈值）
        assertTrue(s.onDrag(dx = -200f, pageW = 2560f))
        // 临抬手往回带（dx 减小 = 往右拉回）
        assertTrue(s.onDrag(dx = -150f, pageW = 2560f))
        assertTrue("末段应是回拉", s.isTailRetreating())
        // 累计位移仍过阈（progress≈0.078 > 0.021），但末段反悔 ⇒ 判回滚。
        assertEquals(FlipSession.Decision.ROLLBACK, s.decide(velocityX = 0f, pageW = 2560f))
    }

    @Test
    fun `末段回拉连快甩也否决——反悔是强信号`() {
        val s = session()
        assertTrue(s.onDrag(dx = -300f, pageW = 2560f))
        assertTrue(s.onDrag(dx = -280f, pageW = 2560f)) // 极小的回拉
        assertTrue(s.isTailRetreating())
        // 抬手瞬间速度是「往回」的速度（vx>0 = 右），forwardFling 为负，本来就不 commit；
        // 这里再给一个「仍朝前的伪造高速」确认末段否决独立生效。
        assertEquals(FlipSession.Decision.ROLLBACK, s.decide(velocityX = -3000f, pageW = 2560f))
    }

    @Test
    fun `末段继续前进则照常翻页——否决项不误伤`() {
        val s = session()
        assertTrue(s.onDrag(dx = -60f, pageW = 2560f))
        assertTrue(s.onDrag(dx = -120f, pageW = 2560f)) // 继续往外拖
        assertFalse("末段应是前进", s.isTailRetreating())
        assertEquals(FlipSession.Decision.COMMIT, s.decide(velocityX = 0f, pageW = 2560f))
    }

    @Test
    fun `抬手前静止不算反悔`() {
        val s = session()
        assertTrue(s.onDrag(dx = -200f, pageW = 2560f))
        // 手指停住：MOVE 继续来但 dx 不变
        assertTrue(s.onDrag(dx = -200f, pageW = 2560f))
        assertTrue(s.onDrag(dx = -200f, pageW = 2560f))
        assertFalse("静止不该被当成回拉", s.isTailRetreating())
        assertEquals(FlipSession.Decision.COMMIT, s.decide(velocityX = 0f, pageW = 2560f))
    }

    @Test
    fun `未就绪期间少量拖动仍回弹——挂起结算不会把每次都当翻页`() {
        val s = session()
        assertTrue(s.onDrag(dx = -30f, pageW = 2560f))
        // 只拖 30px < 55px 阈值、速度也不够 ⇒ 回滚（否则误翻页比误弹回更烦人）。
        assertEquals(FlipSession.Decision.ROLLBACK, s.decide(velocityX = 0f, pageW = 2560f))
    }

    // ---- 离手速度衔接（真机「翻一半停一下」的回归锁）----

    @Test
    fun `快甩 commit 起手斜率与手指速度一致——离手不刹停`() {
        val s = session()
        s.onDrag(dx = -500f, pageW = 1000f) // progress 0.5，方向 +1
        val vx = -2000f // px/s，朝下一页
        val from = s.progress
        val d = s.settleDurationMs(FlipSession.Decision.COMMIT, from, vx, 1000f) // 250ms
        val a = s.settleInitialSlope(FlipSession.Decision.COMMIT, from, vx, 1000f, d)
        assertEquals("快甩起手斜率必须为 1（速度连续）", 1f, a, 1e-4f)
        // 不变式：动画起手速度（px/s）== 手指速度。
        val delta = 1f - from
        val animV = delta * a / (d / 1000f) * 1000f
        assertEquals(2000f, animV, 1f)
    }

    @Test
    fun `快甩 rollback 起手斜率同样为 1`() {
        val s = session()
        s.onDrag(dx = -500f, pageW = 1000f) // progress 0.5，方向 +1
        val vx = 2000f // 手指向右回拖
        val from = s.progress
        val d = s.settleDurationMs(FlipSession.Decision.ROLLBACK, from, vx, 1000f) // 250ms
        val a = s.settleInitialSlope(FlipSession.Decision.ROLLBACK, from, vx, 1000f, d)
        assertEquals(1f, a, 1e-4f)
    }

    @Test
    fun `点按与拿不到速度时起手斜率为 0——缓动保持原样`() {
        val s = session()
        // 程序化翻页：pageW=0（调用点不传页面尺寸）
        assertEquals(0f, s.settleInitialSlope(FlipSession.Decision.COMMIT, 0.5f, 2000f, 0f), 0f)
        // 已起手但速度取不到
        s.onDrag(dx = -500f, pageW = 1000f)
        assertEquals(0f, s.settleInitialSlope(FlipSession.Decision.COMMIT, 0.5f, 0f, 1000f), 0f)
    }

    @Test
    fun `速度与去向相反时不起手加速——夹到 0 不给负斜率`() {
        val s = session()
        s.onDrag(dx = -200f, pageW = 1000f) // direction +1，progress 0.2
        // 判回滚，但手指仍在朝前甩（vx<0）⇒ 不能给负斜率让它先往前冲。
        assertEquals(0f, s.settleInitialSlope(FlipSession.Decision.ROLLBACK, 0.2f, -2000f, 1000f), 1e-5f)
    }

    @Test
    fun `剩余距离极小时斜率夹在 1——不越界过冲`() {
        val s = session()
        s.onDrag(dx = -990f, pageW = 1000f) // progress 0.99
        val a = s.settleInitialSlope(FlipSession.Decision.COMMIT, 0.99f, -5000f, 1000f)
        assertEquals(1f, a, 1e-4f)
    }

    @Test
    fun `VelocityHandoffEasing 端点归位、起点斜率等于 a、终点斜率归零`() {
        val a = 0.5f
        val e = VelocityHandoffEasing(a)
        assertEquals(0f, e.transform(0f), 1e-5f)
        assertEquals(1f, e.transform(1f), 1e-5f)
        val h = 1e-3f
        assertEquals("起点斜率 = a", a, (e.transform(h) - e.transform(0f)) / h, 1e-2f)
        assertEquals("终点斜率 = 0", 0f, (e.transform(1f) - e.transform(1f - h)) / h, 1e-2f)
    }

    @Test
    fun `VelocityHandoffEasing 在 a 全域单调不越界`() {
        for (a in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val e = VelocityHandoffEasing(a)
            var prev = e.transform(0f)
            var s = 0.01f
            while (s <= 1.0001f) {
                val v = e.transform(s)
                assertTrue("a=$a 必须单调（$prev → $v @ $s）", v >= prev - 1e-4f)
                assertTrue("a=$a 不得越界：$v", v >= -1e-4f && v <= 1f + 1e-4f)
                prev = v
                s += 0.01f
            }
        }
    }
}
