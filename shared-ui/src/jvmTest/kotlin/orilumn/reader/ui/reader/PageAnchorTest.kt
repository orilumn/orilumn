package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绘制帧锚点回归：锚点只由**行内容**（文本行、插图）决定，盒背景/边框不参与。
 *
 * 真机事故（《Kotlin in Action, 2nd》ch8 char 2267-3289，原书设置）：
 * `div.fm-sidebar-block`（`padding: 10px 20px`）的 border-box 顶比本页首行高 91px
 * （padding-top 10 + 首子块 `.fm-sidebar-title` 的 margin-top 65.5 + padding-top 15），
 * [orilumn.reader.engine.laying.BoxDrawer] 有意让「本页内起始的箱子」保留完整 border-box
 * （见 `BoxDrawerPageBandTest`「页首起块顶 padding 不裁」），于是盒背景 yTop = −91。
 * 旧锚点取「行/图/背景三者最小」⇒ anchor = −91，整页下推 91px，末行落到恰为 `contentH`
 * 的栅格外被裁 ——「最后一行只显示半行」。
 *
 * 分页预算（`Paginator.fillWholeLines`）只量行，故绘制帧必须落在首行上；页外延伸的盒装饰
 * 由栅格边界自然裁掉（与页底装饰同口径）。
 */
class PageAnchorTest {

    @Test
    fun `页首盒装饰不进锚点_末行留在分页帧内`() {
        // 真机现场：行 0..2384，5 个盒背景 -91..2384，内容区高 2400。
        val lineMin = 0
        val lineBottom = 2384
        val bgMin = -91
        val contentH = 2400

        val anchor = ReaderMath.pageAnchorY(lineMin = lineMin, imgMin = null, bgMin = bgMin)

        assertEquals("锚点必须落在首行（0），不得被页首盒装饰（-91）拉低", 0, anchor)
        assertTrue(
            "末行 $lineBottom − anchor $anchor 必须 ≤ contentH $contentH（修复前 -91 ⇒ 2475，末行被裁）",
            lineBottom - anchor!! <= contentH,
        )
    }

    @Test
    fun `行与图取最小_插图比其后首个文本行更靠上`() {
        // 插图单独占行：不产出 DrawLine，其后首个文本行更低 ⇒ 必须锚在图顶（否则首图跑到屏外）。
        assertEquals(374, ReaderMath.pageAnchorY(lineMin = 943, imgMin = 374, bgMin = -91))
    }

    @Test
    fun `纯图页以首图为基准_背景不参与`() {
        assertEquals(374, ReaderMath.pageAnchorY(lineMin = null, imgMin = 374, bgMin = -10))
    }

    @Test
    fun `行与图都缺时才用盒背景兜底`() {
        assertEquals(-50, ReaderMath.pageAnchorY(lineMin = null, imgMin = null, bgMin = -50))
    }

    @Test
    fun `三者全空回 null`() {
        assertNull(ReaderMath.pageAnchorY(lineMin = null, imgMin = null, bgMin = null))
    }
}
