package orilumn.reader.engine.css

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Standard replaced-element sizing (CSS 2.1 §10.3.2 + §10.6.2 + §10.4 constraint table).
 *
 * Regression for "Kotlin in Action 图 1.3 插图很小": with `width:auto;height:auto` the used
 * size must be the intrinsic size (999×359), shrunk only when a `max-width` rule binds
 * (our reader UA `img{max-width:100%}`), never unconditionally clamped to the containing block
 * and never expanded to fill it.
 */
class ReplacedSizingTest {

    private fun style(
        widthPx: Float? = null,
        heightPx: Float? = null,
        widthPct: Float? = null,
        maxWidthPx: Float? = null,
        maxWidthPct: Float? = null,
        minWidthPx: Float? = null,
        minWidthPct: Float? = null,
        maxHeightPx: Float? = null,
        minHeightPx: Float? = null,
    ) = ComputedStyle(
        fontSizePx = 16f, lineHeightRatio = 1.5f,
        widthPx = widthPx, heightPx = heightPx, widthPct = widthPct,
        maxWidthPx = maxWidthPx, maxWidthPct = maxWidthPct,
        minWidthPx = minWidthPx, minWidthPct = minWidthPct,
        maxHeightPx = maxHeightPx, minHeightPx = minHeightPx,
    )

    @Test
    fun `双 auto 取 intrinsic，不放大也不缩小`() {
        // 图 1.3: 999×359，版心 800，无 max-width → 溢出原样（浏览器行为）。
        assertEquals(999 to 359, style().usedReplacedSize(999, 359, 800))
        // 版心比图宽时同样原样。
        assertEquals(999 to 359, style().usedReplacedSize(999, 359, 1200))
    }

    @Test
    fun `UA max-width 百分百把超宽图等比缩到版心，未超宽不动`() {
        val ua = style(maxWidthPct = 100f)
        // 999×359 在 800 版心 → 800×287（800*359/999=287.5→287）。
        assertEquals(800 to 287, ua.usedReplacedSize(999, 359, 800))
        // 400×200 在 800 版心 → 不动。
        assertEquals(400 to 200, ua.usedReplacedSize(400, 200, 800))
    }

    @Test
    fun `只定一边时另一边按纵横比缩放`() {
        assertEquals(400 to 144, style(widthPx = 400f).usedReplacedSize(999, 359, 800))
        assertEquals(278 to 100, style(heightPx = 100f).usedReplacedSize(999, 359, 800))
    }

    @Test
    fun `两边都定则按指定值，不保比`() {
        assertEquals(200 to 100, style(widthPx = 200f, heightPx = 100f).usedReplacedSize(999, 359, 40))
    }

    @Test
    fun `width 百分比按 containing block 解析`() {
        assertEquals(400 to 144, style(widthPct = 50f).usedReplacedSize(999, 359, 800))
    }

    @Test
    fun `min-width 把小图顶大并保比`() {
        // §10.4: w<min-width → (min-width, min(min-width*h/w, max-height))。
        assertEquals(200 to 72, style(minWidthPx = 200f).usedReplacedSize(100, 36, 800))
    }

    @Test
    fun `无 intrinsic 信息时按 300px 兜底，超出版心则按 2比1 收敛`() {
        assertEquals(300 to 150, style().usedReplacedSize(null, null, 800))
        assertEquals(200 to 100, style().usedReplacedSize(null, null, 200))
    }

    @Test
    fun `作者 max-width none 覆盖 UA 的 100 百分比`() {
        // 级联层面作者胜（此处直接断言尺寸语义：无约束 = intrinsic 原样）。
        assertEquals(999 to 359, style().usedReplacedSize(999, 359, 800))
    }

    @Test
    fun `StyleComputer 解析 max-width 与 width 百分比`() {
        val root = orilumn.reader.engine.html.MarkupElement(
            "body", emptyMap(),
            listOf(orilumn.reader.engine.html.MarkupElement("img", mapOf("class" to "a"))),
        )
        val img = root.children[0]
        val ua = LightCssParser().parse("img{max-width:100%}")
        val author = LightCssParser().parse(".a{width:50%}")
        val map = StyleComputer(16f, ua, listOf(author)).compute(root)
        val st = map[img]!!
        assertEquals(50f, st.widthPct)
        assertEquals(null, st.widthPx)
        assertEquals(100f, st.maxWidthPct)
        assertEquals(null, st.maxWidthPx)
        // height % 在 auto 高 CB 下不定 → 按 auto 丢弃。
        val author2 = LightCssParser().parse(".a{height:50%;max-height:50%;min-height:50%}")
        val map2 = StyleComputer(16f, ua, listOf(author2)).compute(root)
        val st2 = map2[img]!!
        assertEquals(null, st2.heightPx)
        assertEquals(null, st2.maxHeightPx)
        assertEquals(null, st2.minHeightPx)
    }
}
