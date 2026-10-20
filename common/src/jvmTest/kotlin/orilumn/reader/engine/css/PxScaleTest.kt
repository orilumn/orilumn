package orilumn.reader.engine.css

import orilumn.reader.engine.html.HtmlTreeConverter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CSS px → 画布物理 px 的设备密度换算（`StyleComputer.pxScale`）。
 *
 * CSS 的 `px` 是**参考像素**：浏览器里 1 CSS px = 1 dp，高密度屏上由系统放大成 2/3 个物理像素。
 * 本引擎的坐标系是物理像素（正文字号 = dp × density），所以书里的 `px` 长度必须乘同一密度 ——
 * 否则 2x 屏上 `30px` 的方块只有 15dp（正文 18dp ⇒ 方块看着比字还小），并且 `300px` 宽的行
 * 只装得下一半字符（用户 2026-10-20 报的「方块太小」「超宽被折成两行」就是这一条）。
 *
 * 锁：`px` 随密度放大；`em`/`%`/`rem` 不额外放大（它们已以同样放大的字号为基准）。
 */
class PxScaleTest {

    private val rootHtml = """<html><body><div id="d">文字</div></body></html>"""

    private fun styleOf(css: String, pxScale: Float, rootPx: Float = 16f): ComputedStyle {
        val root = HtmlTreeConverter().convert(rootHtml)!!
        val engine = StyleComputer(
            rootPx,
            LightCssParser().parse(""),
            listOf(LightCssParser().parse(css)),
            pxScale = pxScale,
        )
        val map = engine.compute(root)
        return map.getValue(map.keys.first { it.attrs["id"] == "d" })
    }

    @Test
    fun `px 长度按密度等比放大`() {
        val css = "#d{width:30px;height:40px;padding:2px 4px;border:1px solid #000;margin:3px;font-size:12px}"
        val one = styleOf(css, 1f)
        val two = styleOf(css, 2f)

        assertEquals(30f, one.widthPx!!, 1e-3f)
        assertEquals(60f, two.widthPx!!, 1e-3f)
        assertEquals(40f, one.heightPx!!, 1e-3f)
        assertEquals(80f, two.heightPx!!, 1e-3f)
        assertEquals(1f, one.border.left, 1e-3f)
        assertEquals(2f, two.border.left, 1e-3f)
        assertEquals(4f, one.padding.left, 1e-3f)
        assertEquals(8f, two.padding.left, 1e-3f)
        assertEquals(3f, one.margin.top, 1e-3f)
        assertEquals(6f, two.margin.top, 1e-3f)
        // px 字号同样是 CSS px
        assertEquals(12f, one.fontSizePx, 1e-3f)
        assertEquals(24f, two.fontSizePx, 1e-3f)
    }

    @Test
    fun `em 百分比 rem 与边框关键字不额外放大`() {
        val css = "#d{font-size:1em;width:2em;text-indent:10%;line-height:2em;border-top-width:thin;border-top-style:solid}"
        val one = styleOf(css, 1f)
        val two = styleOf(css, 2f)

        // em 以父字号为基准：根 16 ⇒ 两档都是 16（pxScale 只作用于 px，不重复放大字号）。
        assertEquals(16f, one.fontSizePx, 1e-3f)
        assertEquals(16f, two.fontSizePx, 1e-3f)
        assertEquals(32f, one.widthPx!!, 1e-3f)
        assertEquals(32f, two.widthPx!!, 1e-3f)
        assertEquals(1.6f, one.textIndentPx, 1e-3f)
        assertEquals(1.6f, two.textIndentPx, 1e-3f)
        assertEquals(2f, one.lineHeightRatio, 1e-3f)
        assertEquals(2f, two.lineHeightRatio, 1e-3f)
        // thin = 1 CSS px ⇒ 随密度放大
        assertEquals(1f, one.border.top, 1e-3f)
        assertEquals(2f, two.border.top, 1e-3f)
    }

    @Test
    fun `px 行高的比例与密度无关`() {
        val one = styleOf("#d{font-size:12px;line-height:24px}", 1f)
        val two = styleOf("#d{font-size:12px;line-height:24px}", 2f)
        assertEquals(2f, one.lineHeightRatio, 1e-3f)
        assertEquals(2f, two.lineHeightRatio, 1e-3f)
    }
}
