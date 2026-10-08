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
    fun `commit 结算朝方向满位`() {
        val s = session()
        s.onDrag(dx = -600f, pageW = 1000f)
        val d = s.decide()
        s.beginSettle(d)
        assertEquals(FlipSession.Phase.Settling, s.phase)
        assertEquals(1f, s.progress, 1e-4f)
    }

    @Test
    fun `rollback 结算回零`() {
        val s = session()
        s.onDrag(dx = -200f, pageW = 1000f)
        s.beginSettle(s.decide())
        assertEquals(FlipSession.Phase.Settling, s.phase)
        assertEquals(0f, s.progress, 1e-4f)
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
    fun `时长——commit 600 rollback 500`() {
        val s = session()
        assertEquals(600, s.settleDurationMs(FlipSession.Decision.COMMIT))
        assertEquals(500, s.settleDurationMs(FlipSession.Decision.ROLLBACK))
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
}
