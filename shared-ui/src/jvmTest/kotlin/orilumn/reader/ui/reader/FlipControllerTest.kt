package orilumn.reader.ui.reader

import kotlinx.coroutines.runBlocking
import orilumn.reader.engine.paging.PageSlice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FlipController] 的时序与守卫锁。
 *
 * 这一层的存在理由是「判据散落 ⇒ 漏一处就错位」（见类 KDoc 列出的三次事故），
 * 所以测试重点不是单个函数的计算，而是**跨函数的时序与守卫**：
 *  - 动画关闭时渲染层必须真的不画（`slideActive` 为 false）；
 *  - 目标页未就绪不许平移当前页，但位移仍要累积进状态机；
 *  - 目标页未就绪时收场，不得留下无人认领的等待窗口。
 */
class FlipControllerTest {

    private fun pos(char: Int) = ReaderPos(1, PageSlice(charStart = char, charEnd = char + 10))

    private fun controller(
        flipAwait: suspend (Int) -> ReaderPos?,
        forced: MutableList<ReaderPos> = mutableListOf(),
        scope: kotlinx.coroutines.CoroutineScope,
    ) = FlipController(
        flipAwait = flipAwait,
        forceOpenPos = { forced += it },
        scope = scope,
        log = {},
        allowAnimation = false, // 单测无 MonotonicFrameClock，不能真跑 Animatable
    )

    @Test
    fun `动画关闭时渲染层不得位移——入口都瞬切、像素仍在动那个 bug`() = runBlocking {
        val forced = mutableListOf<ReaderPos>()
        val ctl = controller({ pos(100) }, forced, this)
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        // 会话确实在动：开着动画时应当位移。
        assertTrue("开着动画时应位移", ctl.slideActive(enabled = true))
        // 关掉动画：渲染层只读这一个出口，早先它不在任何 !slideEnabled 早退里。
        assertFalse("关闭动画后渲染层必须静止", ctl.slideActive(enabled = false))
        assertTrue(forced.isEmpty())
    }

    @Test
    fun `目标页未就绪时渲染层不许平移当前页`() = runBlocking {
        // 落位返回 null ⇒ 目标页不就绪。
        val ctl = controller({ null }, mutableListOf(), this)
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -400f, pageW = 2560f, enabled = true)
        assertFalse(ctl.targetReady())
        assertEquals("未就绪时进度必须为 0（画面停当前页，不露底色空白）", 0f, ctl.progress.value, 0f)
    }

    @Test
    fun `目标页未就绪时位移仍累积进状态机`() = runBlocking {
        val ctl = controller({ null }, mutableListOf(), this)
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -900f, pageW = 2560f, enabled = true)
        assertEquals("渲染仍为 0", 0f, ctl.progress.value, 0f)
        // 关键：状态机必须记住真实拖动量，否则松手裁决会把「拖了满页」判成没动过。
        assertTrue("状态机应累积真实位移，实际 ${ctl.session.progress}", ctl.session.progress > 0.3f)
    }

    @Test
    fun `方向在起手就定死——否则未就绪期间 shift 为零目标页叠在原位`() = runBlocking {
        val ctl = controller({ null }, mutableListOf(), this)
        ctl.beginDrag(pos(0), direction = -1, enabled = true, coverVisible = false)
        assertEquals(-1, ctl.direction.value)
        // 起手页必须被存下：落位后 openPos 变成目标页，渲染层靠它画「当前页」。
        assertEquals(0, ctl.fromPos.value?.slice?.charStart)
        ctl.clear()
        assertEquals(0, ctl.direction.value)
        assertEquals(0f, ctl.progress.value, 0f)
        assertNull(ctl.targetPos.value)
        assertNull(ctl.fromPos.value)
    }

    @Test
    fun `起手页必须被存下——否则当前页与目标页都画成目标页（目标页空白）`() = runBlocking {
        val ctl = controller({ pos(100) }, mutableListOf(), this)
        // 回归锁：beginDrag 曾把 `fromPos` 写成 null 且**根本不收 src 参数**，
        // 落位后 openPos 变成目标页 ⇒ 渲染层 `fromPos ?: pos` 退回用 pos
        // ⇒ 两张画布都画目标页，观感是「目标页空白 + 内容重复」。
        ctl.beginDrag(pos(7), direction = 1, enabled = true, coverVisible = false)
        assertEquals("起手页应被记住", 7, ctl.fromPos.value?.slice?.charStart)
    }

    @Test
    fun `点按翻页同样要存起手页`() = runBlocking {
        val ctl = controller({ pos(100) }, mutableListOf(), this)
        assertTrue(ctl.beginProgrammatic(1))
        ctl.beginProgrammaticPage(pos(9), direction = 1)
        assertEquals(9, ctl.fromPos.value?.slice?.charStart)
        ctl.clear()
    }

    @Test
    fun `结算最后一帧不得把进度塌成零——否则翻完闪一下旧页`() = runBlocking {
        val ctl = controller({ pos(100) }, mutableListOf(), this)
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        // 只驱动状态机，不真跑动画（单测环境没有 MonotonicFrameClock）：
        // 结算动画的**最后一帧**上 onSettleProgress 会把 phase 置成 Idle，
        // 而 clear() 要等 animateTo 返回后才跑 ⇒ 中间这段 phase 已是 Idle、
        // 会话却仍锁着两页。守卫必须看 fromPos（在场）而不是 phase。
        ctl.session.beginSettle(FlipSession.Decision.COMMIT)
        ctl.session.onSettleProgress(1f)
        ctl.progress.value = 1f
        assertEquals("phase 应已回 Idle", FlipSession.Phase.Idle, ctl.session.phase)
        assertTrue(
            "会话仍在渲染层在场，最后一帧必须继续按 progress 渲染",
            ctl.slideActive(enabled = true),
        )
        assertEquals(1f, ctl.progress.value, 0f)
    }

@Test
    fun `落位在途时不得裸clear——否则协程回来设targetPos 而fromPos 已空`() = runBlocking {
        // 回归锁（真机空白页）：`flipAwait` 在等漏斗锁+等图时最长见过 667ms，
        // 期间用户松手。早先 endDrag 直接 clear()，而预取协程仍在途 ——
        // 协程回来再设 targetPos，此时 fromPos 已是 null ⇒ 渲染层
        // `fromPos ?: pos` 退回用尚未 commit 的 pos ⇒ 画出未就绪的页（白屏）。
        val forced = mutableListOf<ReaderPos>()
        val gate = kotlinx.coroutines.CompletableDeferred<ReaderPos?>()
        val ctl = FlipController(
            flipAwait = { gate.await() },      // 挂住 ⇒ 模拟在途
            forceOpenPos = { forced += it },
            scope = this,
            log = {},
            allowAnimation = false,
        )
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        // 目标页未就绪 + COMMIT ⇒ 走「等在途落位」分支，**不是**裸 clear。
        ctl.endDrag(velocityX = 0f, pageW = 2560f, enabled = true)
        // 起手页必须仍在：这是渲染层画「当前页」的唯一依据。
        assertEquals(
            "落位在途时 fromPos 必须保留，否则协程回来即错位",
            0,
            ctl.fromPos.value?.slice?.charStart,
        )
        gate.complete(pos(100))
        ctl.clear()
    }

@Test
    fun `清场之后渲染层立即交位——不会停在旧页`() = runBlocking {
        val ctl = controller({ pos(100) }, mutableListOf(), this)
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        assertTrue(ctl.slideActive(enabled = true))
        ctl.clear()
        assertFalse("清场后不应再驱动动画", ctl.slideActive(enabled = true))
        assertEquals(0f, ctl.progress.value, 0f)
    }

    // ---- 快速连翻：beginDrag 的忙碌墙（「划了不翻页 + 当页闪烁」回归锁）----

    @Test
    fun `结算动画在飞时beginDrag接管——取消旧动画从落定页干净起手`() = runBlocking {
        val ctl = controller({ pos(100) }, mutableListOf(), this)
        // 上一手势已起手并进入结算（数据落定为 pos(100)，动画仍在飞）。
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        kotlinx.coroutines.yield() // 让上一手势的落位协程真正跑完（targetPos=pos(100)）
        ctl.session.beginSettle(FlipSession.Decision.COMMIT)
        ctl.session.onSettleProgress(0.6f)
        ctl.progress.value = 0.6f
        assertEquals(FlipSession.Phase.Settling, ctl.session.phase)
        // 新一划（快速连翻，落位返回同一页）——必须接管而不是并发覆盖。
        ctl.beginDrag(pos(100), direction = 1, enabled = true, coverVisible = false)
        // 旧会话被 abort、旧动画（settleJob）被取消 ⇒ 状态从「落定页」干净起手。
        assertEquals(FlipSession.Phase.Idle, ctl.session.phase)
        assertEquals(100, ctl.fromPos.value?.slice?.charStart)
        assertNull("目标页重置，等新落位", ctl.targetPos.value)
        assertEquals(0f, ctl.progress.value, 0f)
        assertEquals(1, ctl.direction.value)
        ctl.clear()
    }

    @Test
    fun `落位在途时beginDrag丢弃——不得取消在途落位`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<ReaderPos?>()
        var landingCalls = 0
        val ctl = FlipController(
            flipAwait = { landingCalls++; gate.await() }, // 挂住 ⇒ 第一手势落位在途
            forceOpenPos = {},
            scope = this,
            log = {},
            allowAnimation = false,
        )
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        kotlinx.coroutines.yield() // 让落位协程真正起跑（挂死在 gate.await 上）
        assertEquals("第一手势应已在落位", 1, landingCalls)
        assertEquals(0, ctl.fromPos.value?.slice?.charStart)
        // 新一划落在在途落位上：BUSY-DROP——不能覆盖会话、更不能取消在途 adjacent。
        ctl.beginDrag(pos(100), direction = 1, enabled = true, coverVisible = false)
        assertEquals("在途落位不得被新一划取消", 1, landingCalls)
        assertEquals("fromPos 不得被覆盖", 0, ctl.fromPos.value?.slice?.charStart)
        assertTrue("会话仍在 Dragging", ctl.session.phase == FlipSession.Phase.Dragging)
        gate.complete(pos(100))
        ctl.clear()
    }

    @Test
    fun `回滚落位在途时beginDrag丢弃——孤儿clear不再掐新会话`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<ReaderPos?>()
        var landingCalls = 0
        val ctl = FlipController(
            flipAwait = { landingCalls++; gate.await() },
            forceOpenPos = {},
            scope = this,
            log = {},
            allowAnimation = false,
        )
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        kotlinx.coroutines.yield()
        assertEquals(1, landingCalls)
        // 直接触发回滚（回弹路径）：rollbackJob 起落位协程（phase 已回 Idle）。
        ctl.rollback(navigateBack = true)
        kotlinx.coroutines.yield() // 让回滚落位协程真正起跑
        assertEquals(FlipSession.Phase.Idle, ctl.session.phase)
        assertEquals("回滚落位应在途", 2, landingCalls) // +rollback 的 flipAwait(-1)
        // 新手势在回滚落位在途时起手：必须丢弃，不能抢在孤儿 clear() 前覆盖会话。
        ctl.beginDrag(pos(10), direction = -1, enabled = true, coverVisible = false)
        assertEquals(2, landingCalls)
        assertEquals("fromPos 仍是回滚那次的起手页", 0, ctl.fromPos.value?.slice?.charStart)
        gate.complete(pos(0))
        ctl.clear()
    }

    @Test
    fun `落位在途时点按翻页丢弃——beginProgrammatic 走同一道忙碌墙`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<ReaderPos?>()
        val ctl = FlipController(
            flipAwait = { gate.await() },
            forceOpenPos = {},
            scope = this,
            log = {},
            allowAnimation = false,
        )
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        kotlinx.coroutines.yield()
        // 落位在途（phase Dragging）时点按/方向键不得起新会话。
        assertFalse("落位在途应丢弃程序化翻页", ctl.beginProgrammatic(1))
        assertFalse(ctl.beginProgrammatic(-1))
        gate.complete(pos(100))
        ctl.clear()
    }

    @Test
    fun `回滚落位在途时点按翻页丢弃`() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<ReaderPos?>()
        val ctl = FlipController(
            flipAwait = { gate.await() },
            forceOpenPos = {},
            scope = this,
            log = {},
            allowAnimation = false,
        )
        ctl.beginDrag(pos(0), direction = 1, enabled = true, coverVisible = false)
        ctl.updateDrag(dx = -300f, pageW = 2560f, enabled = true)
        kotlinx.coroutines.yield()
        ctl.rollback(navigateBack = true) // rollbackJob 在途、phase 已 Idle
        kotlinx.coroutines.yield()
        // 旧守卫只看 phase ⇒ Idle 会放行；新守卫必须把在途回滚落位也当忙碌。
        assertFalse("回滚落位在途应丢弃程序化翻页", ctl.beginProgrammatic(1))
        gate.complete(pos(0))
        ctl.clear()
    }
}