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
}