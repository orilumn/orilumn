package orilumn.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 版式变更期间的锚点钉住（真机 2026-10-22 12:02 复现：拖行距滑块锚点 5133→5059→…→5024 单向退；
 * 11:59 来回旋转同样逐次后退）。
 *
 * 落位会把「落位页首」写回定位；下一次重排若再取当前页首当锚点，就会一路往回走。
 * 规矩：锚点只在用户真的导航时更换；改参预览、关面板落位、旋转都同锚。
 */
class RelayoutAnchorTest {

    @Test
    fun `pin survives preview landings`() {
        val anchor = RelayoutAnchor()
        anchor.pin(5, 5133)
        // 每次预览落位后「当前页首」都在变（变到 5059、5024…），锚点必须还是 5133。
        assertEquals(LayoutAnchor(5, 5133), anchor.resolve(5, 5059))
        assertEquals(LayoutAnchor(5, 5133), anchor.resolve(5, 5024))
        assertEquals(LayoutAnchor(5, 5133), anchor.resolve(5, 5568))
    }

    @Test
    fun `first pin wins inside one interaction`() {
        val anchor = RelayoutAnchor()
        anchor.pin(5, 5133)
        anchor.pin(5, 5059) // 拖动中的第二次上钉不得改锚
        assertEquals(LayoutAnchor(5, 5133), anchor.resolve(5, 5000))
    }

    @Test
    fun `release restarts from the current page head`() {
        val anchor = RelayoutAnchor()
        anchor.pin(5, 5133)
        anchor.release()
        assertFalse(anchor.isPinned)
        assertEquals(LayoutAnchor(5, 4762), anchor.resolve(5, 4762))
    }

    @Test
    fun `other chapter is looked up by the caller's value`() {
        val anchor = RelayoutAnchor()
        anchor.pin(5, 5133)
        // 换了章（目录跳转后来不及松开）：本次落位按调用方现值取，不把旧章的字符带过去。
        assertEquals(LayoutAnchor(9, 120), anchor.resolve(9, 120))
    }
}
