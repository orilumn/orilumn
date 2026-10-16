package orilumn.reader.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 绘制帧锚点回归：锚点 = **行内容（文本行、插图）最小者 − 页首缺口**，盒背景/边框不参与。
 *
 * ## 页首缺口（`ReaderHost.pageFrameLead`）
 *
 * 分页预算按**绘制帧**算：`Paginator.fillWholeLines` 的页高 = `getPageFrameBottom(末行) −
 * getPageFrameTop(首行)`，而 `frameTop(首行) = frameBottom(上一行)` ⇒ 本页首行之上那段
 * （页首块的 `margin-top` 与页首容器的 `border/padding`，真机 `div.fm-sidebar-block` 达 91px）
 * **记在本页预算里**。栅格恰为 `contentW × contentH`，故绘制必须与预算**同一起点**：锚点抬到帧顶
 * `首行行顶 − 缺口`，那段间距才画在页顶；否则它被栅格上沿裁掉、又在页底留成等量空白
 * （「明明还有空间，最后一行却被推走」）。
 *
 * 真机事故（《Kotlin in Action, 2nd》ch8 char 2267-3289，原书设置）正是锚点口径与预算口径不一致：
 * 旧预算把这段间距记作**页尾**开销（`frameBottom = 下一行行顶`），旧锚点却落在首行行顶 ⇒ 91px
 * 恰好把末行顶出栅格（「最后一行只显示半行」）。
 *
 * ## 盒背景/边框为何仍不进锚点
 *
 * [orilumn.reader.engine.laying.BoxDrawer] 对本页内起始的箱子**有意**放宽到 border-box
 * （见 `BoxDrawerPageBandTest`「页首起块顶 padding 不裁」），盒背景 yTop 可比首行更靠上
 * （真机 `.fm-sidebar-block` 的 border-box 顶 = −91）。那段间距已由缺口承担，再让盒 yTop
 * 参与取最小就会**重复下推**整页、末行越出栅格被裁。
 */
class PageAnchorTest {

    @Test
    fun `页首缺口抬升锚点_页首装饰画全且末行留在栅格内`() {
        // 真机现场口径：内容区高 2400；页首缺口 91（.fm-sidebar-block 的 border-box 顶比首行高 91）；
        // 分页预算给到恰好满页 ⇒ 末行行底落在 contentH − 缺口 = 2309。
        val lineMin = 0
        val frameTop = lineMin - 91
        val lineBottom = 2309
        val bgMin = lineMin - 91
        val contentH = 2400

        val anchor = ReaderMath.pageAnchorY(lineMin = lineMin, imgMin = null, bgMin = bgMin, frameLead = 91)

        assertEquals("锚点必须抬到帧顶（首行行顶 − 缺口）", frameTop, anchor)
        assertEquals(
            "页首盒装饰顶必须落在栅格 0（画全，不得被上沿裁）",
            0, bgMin - anchor!!,
        )
        assertEquals(
            "本页绘制帧底必须恰好铺满栅格（缺口画在页顶，不在页底留空白）",
            contentH, lineBottom - anchor!!,
        )
    }

    @Test
    fun `缺口为零时锚点与旧口径逐字节相同`() {
        // 宿主未实现 / 页首无缺口（段内中段开页）：抬升量为 0，像素与修前一致。
        assertEquals(0, ReaderMath.pageAnchorY(lineMin = 0, imgMin = null, bgMin = -91, frameLead = 0))
        assertEquals(120, ReaderMath.pageAnchorY(lineMin = 120, imgMin = null, bgMin = null, frameLead = 0))
    }

    @Test
    fun `负缺口被夹到零_锚点只许上抬不许下推`() {
        // 宿主若给出负值（不应发生），也不得把整页下推——下推即末行越栅格被裁。
        assertEquals(120, ReaderMath.pageAnchorY(lineMin = 120, imgMin = null, bgMin = null, frameLead = -80))
    }

    @Test
    fun `行与图取最小_插图比其后首个文本行更靠上`() {
        // 插图单独占行：不产出 DrawLine，其后首个文本行更低 ⇒ 必须锚在图顶（否则首图跑到屏外）。
        assertEquals(374, ReaderMath.pageAnchorY(lineMin = 943, imgMin = 374, bgMin = -91))
        assertEquals(374 - 20, ReaderMath.pageAnchorY(lineMin = 943, imgMin = 374, bgMin = -91, frameLead = 20))
    }

    @Test
    fun `纯图页以首图为基准_背景不参与`() {
        assertEquals(374, ReaderMath.pageAnchorY(lineMin = null, imgMin = 374, bgMin = -10))
    }

    @Test
    fun `行与图都缺时才用盒背景兜底_缺口不参与`() {
        // 无行即无「首行」，缺口无从谈起（宿主的 pageFrameLead 此时也给 0），背景兜底值原样返回。
        assertEquals(-50, ReaderMath.pageAnchorY(lineMin = null, imgMin = null, bgMin = -50, frameLead = 91))
    }

    @Test
    fun `三者全空回 null`() {
        assertNull(ReaderMath.pageAnchorY(lineMin = null, imgMin = null, bgMin = null))
        assertNull(ReaderMath.pageAnchorY(lineMin = null, imgMin = null, bgMin = null, frameLead = 40))
    }
}
