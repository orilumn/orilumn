package orilumn.reader.engine.css

import orilumn.reader.engine.html.MarkupElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P3-b 计算层 guard：`background-image` url 提取、`background-repeat`、
 * `background-position`、 `background` 简写扫描、HTML `background` 属性下沉。
 */
class P3bComputeTest {

    private fun styles(
        root: MarkupElement,
        author: String,
    ): Map<MarkupElement, ComputedStyle> =
        StyleComputer(rootFontPx = 10f, ua = LightCssParser().parse(""), authorSheets = listOf(LightCssParser().parse(author))).compute(root)

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), children: List<MarkupElement> = emptyList()): MarkupElement {
        val el = MarkupElement(tag, attrs = attrs, children = children)
        for (c in children) c.parent = el
        return el
    }

    @Test
    fun `image url 提取与 none`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val m = styles(root, "p { background-image: url(\"a/b.png\") }")[p]!!
        assertEquals("a/b.png", m.backgroundImageUrl)
        val bare = styles(root, "p { background-image: url(c.png) }")[p]!!
        assertEquals("c.png", bare.backgroundImageUrl)
        val none = styles(root, "p { background-image: none }")[p]!!
        assertNull(none.backgroundImageUrl)
        val off = styles(root, "")[p]!!
        assertNull("默认无图（旧行为）", off.backgroundImageUrl)
        assertEquals(BackgroundRepeat.REPEAT, off.backgroundRepeat)
        assertEquals(BackgroundPosition(), off.backgroundPosition)
    }

    @Test
    fun `repeat 单双值`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        assertEquals(BackgroundRepeat.REPEAT_X, styles(root, "p { background-repeat: repeat-x }")[p]!!.backgroundRepeat)
        assertEquals(BackgroundRepeat.REPEAT_Y, styles(root, "p { background-repeat: repeat-y }")[p]!!.backgroundRepeat)
        assertEquals(BackgroundRepeat.NO_REPEAT, styles(root, "p { background-repeat: no-repeat }")[p]!!.backgroundRepeat)
        assertEquals(BackgroundRepeat.REPEAT_X, styles(root, "p { background-repeat: repeat no-repeat }")[p]!!.backgroundRepeat)
        assertEquals(BackgroundRepeat.REPEAT_Y, styles(root, "p { background-repeat: no-repeat repeat }")[p]!!.backgroundRepeat)
        assertEquals(BackgroundRepeat.NO_REPEAT, styles(root, "p { background-repeat: no-repeat no-repeat }")[p]!!.backgroundRepeat)
    }

    @Test
    fun `position 关键字与百分比长度`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        // rootFontPx=10：1em=10px。
        val left = styles(root, "p { background-position: left }")[p]!!.backgroundPosition
        assertEquals(BackgroundPosition(0f, 0.5f, 0f, 0f), left)
        val center = styles(root, "p { background-position: center }")[p]!!.backgroundPosition
        assertEquals(BackgroundPosition(0.5f, 0.5f, 0f, 0f), center)
        val top = styles(root, "p { background-position: top }")[p]!!.backgroundPosition
        assertEquals(BackgroundPosition(0.5f, 0f, 0f, 0f), top)
        val pct = styles(root, "p { background-position: 25% 75% }")[p]!!.backgroundPosition
        assertEquals(0.25f, pct.xPct, 1e-4f)
        assertEquals(0.75f, pct.yPct, 1e-4f)
        val len = styles(root, "p { background-position: 10px 1em }")[p]!!.backgroundPosition
        assertEquals(10f, len.xPx, 1e-4f)
        assertEquals(10f, len.yPx, 1e-4f)
        // 双关键字换序自动归轴。
        val swapped = styles(root, "p { background-position: top left }")[p]!!.backgroundPosition
        assertEquals(BackgroundPosition(0f, 0f, 0f, 0f), swapped)
    }

    @Test
    fun `background 简写组合与颜色共存`() {
        val p = node("p")
        val root = node("body", children = listOf(p))
        val m = styles(root, "p { background: url(t.png) no-repeat center #112233 }")[p]!!
        assertEquals("t.png", m.backgroundImageUrl)
        assertEquals(BackgroundRepeat.NO_REPEAT, m.backgroundRepeat)
        assertEquals(BackgroundPosition(0.5f, 0.5f, 0f, 0f), m.backgroundPosition)
        assertEquals("#ff112233", m.backgroundColorHex)
        // 单属性优先于简写。
        val over = styles(root, "p { background: url(t.png); background-repeat: repeat-x }")[p]!!
        assertEquals("t.png", over.backgroundImageUrl)
        assertEquals(BackgroundRepeat.REPEAT_X, over.backgroundRepeat)
    }

    @Test
    fun `HTML background 属性下沉`() {
        val div = node("div", attrs = mapOf("background" to "bg.png"))
        val root = node("body", children = listOf(div))
        val m = styles(root, "")[div]!!
        assertEquals("bg.png", m.backgroundImageUrl)
        // CSS 恒胜表示层。
        val win = styles(root, "div { background-image: url(css.png) }")[div]!!
        assertEquals("css.png", win.backgroundImageUrl)
    }

    @Test
    fun `body background 属性保留下沉`() {
        val body = node("body", attrs = mapOf("background" to "body-bg.png"))
        val m = styles(body, "")[body]!!
        assertEquals("body-bg.png", m.backgroundImageUrl)
    }
}
