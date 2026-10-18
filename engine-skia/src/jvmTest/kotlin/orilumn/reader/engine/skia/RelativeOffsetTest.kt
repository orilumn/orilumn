package orilumn.reader.engine.skia

import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.BoxLayoutResult
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * E12 `position: relative` 的**绘制偏移**（`top/right/bottom/left`）。
 *
 * 相对定位在浏览器里**不改排版**：占位、兄弟位置、分页切点全不动，只把落墨几何整体平移，
 * 且沿祖先链累积（相对定位盒为其后代建立新的「原位置」）。四层锁：
 * 1. **级联 → 值**：px/em 解析、`%` 与 `auto` 不参与、`left` 压过 `right`；
 * 2. **流几何不动**：同一份 HTML 加不加 `position: relative` 的行流 yTop 逐值相同（分页不受影响）；
 * 3. **落墨平移**：文本行 xLeft/yTop 与盒底色矩形整体平移同值，且沿祖先链累积；
 * 4. **反空跑**：`position: static`（或没写 `position`）上的 `top/left` 一律不生效（对照必须为零偏移）。
 */
@RunWith(Parameterized::class)
class RelativeOffsetTest(private val variant: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "breaker={0}")
        fun variants() = breakerVariantParams()
    }

    @Before fun applyVariant() = applyBreakerVariant(variant)

    @After fun resetVariant() = resetBreakerVariant()

    private class Run(
        val result: BoxLayoutResult,
        val styleMap: Map<MarkupElement, orilumn.reader.engine.css.ComputedStyle>,
        val classify: orilumn.reader.engine.laying.BlockClassify,
    ) {
        fun lines() = DrawLineBuilder.build(result, styleMap, classify, HIDDEN_NONE, 0f)
    }

    private fun run(html: String): Run {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(16f, LightCssParser().parse(""), emptyList())
        val styleMap = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styleMap, engine.hasDisplayDeclaration())
        val result = BoxLayouter(16f, variantBreaker()).layoutBoxes(root, 600, styleMap, classify)
        return Run(result, styleMap, classify)
    }

    private fun allBoxes(result: BoxLayoutResult): List<LayoutBox> {
        val out = ArrayList<LayoutBox>()
        fun walk(bs: List<LayoutBox>) {
            for (b in bs) {
                out.add(b)
                if (b.childBoxes.isNotEmpty()) walk(b.childBoxes)
            }
        }
        walk(result.boxes)
        return out
    }

    private fun leaf(result: BoxLayoutResult, tag: String): LayoutBox =
        allBoxes(result).first { it.el?.tag == tag && !it.el!!.isText }

    /** 真级联（真 DOM + 真样式计算）算出该元素的 [orilumn.reader.engine.css.ComputedStyle]。 */
    private fun styleOf(html: String, tag: String, rootFontPx: Float = 16f): orilumn.reader.engine.css.ComputedStyle {
        val root = HtmlTreeConverter().convert(html)!!
        fun find(n: MarkupElement): MarkupElement? {
            if (n.tag == tag && !n.isText) return n
            for (c in n.children) find(c)?.let { return it }
            return null
        }
        val el = find(root) ?: error("no <$tag>")
        return StyleComputer(rootFontPx, LightCssParser().parse(""), emptyList()).compute(root)[el]
            ?: error("级联缺 <$tag> 的样式")
    }

    // ---- 1. 级联 → 值 ----

    @Test
    fun `px 与 em 偏移被解析`() {
        val style = styleOf("<html><body><p style=\"position:relative;top:4px;left:8px\">x</p></body></html>", "p")
        assertTrue(style.positionRelative)
        assertEquals(4f, style.relativeInset.top!!, 0.01f)
        assertEquals(8f, style.relativeInset.left!!, 0.01f)
        assertNull(style.relativeInset.right)
        val em = styleOf(
            "<html><body><p style=\"position:relative;bottom:1.5em;font-size:20px\">x</p></body></html>", "p",
        )
        assertEquals("em 相对自身字号解算（20px × 1.5）", 30f, em.relativeInset.bottom!!, 0.01f)
    }

    @Test
    fun `auto 与百分比不参与偏移`() {
        val style = styleOf("<html><body><p style=\"position:relative;top:auto;left:50%\">x</p></body></html>", "p")
        assertNull("auto 不参与", style.relativeInset.top)
        assertNull("百分比未支持（账本 E12 残留），按不参与处理", style.relativeInset.left)
    }

    @Test
    fun `right 与 bottom 是非 auto 时取反方向`() {
        val left = leaf(run("<html><body><p style=\"position:relative;left:9px\">abc</p></body></html>").result, "p")
        assertEquals(9, left.relOffsetX)
        val right = leaf(run("<html><body><p style=\"position:relative;right:9px\">abc</p></body></html>").result, "p")
        assertEquals("right:9px 等价左移 9px", -9, right.relOffsetX)
        val bottom = leaf(run("<html><body><p style=\"position:relative;bottom:9px\">abc</p></body></html>").result, "p")
        assertEquals("bottom:9px 等价上移 9px", -9, bottom.relOffsetY)
    }

    // ---- 2. 流几何不动 ----

    @Test
    fun `相对定位不改行流几何`() {
        val base = run("<html><body><p>abc</p><p>def</p></body></html>")
        val rel = run(
            "<html><body><p style=\"position:relative;top:20px;left:10px\">abc</p><p>def</p></body></html>",
        )
        assertEquals(
            "行流 yTop/yBottom 逐值相同 ⇒ 分页与占位不受偏移影响",
            base.result.lines.map { it.yTop to it.yBottom },
            rel.result.lines.map { it.yTop to it.yBottom },
        )
        assertEquals("两叶的流位置相同", base.result.boxes.size, rel.result.boxes.size)
        // 兄弟叶（未被偏移的那个）行流一致，且偏移叶本身在盒上的流位置也不动。
        val baseLeaf = leaf(base.result, "p")
        val relLeaf = allBoxes(rel.result).first { it.el?.tag == "p" }
        assertEquals(baseLeaf.contentTop, relLeaf.contentTop)
        assertEquals(baseLeaf.contentBottom, relLeaf.contentBottom)
    }

    // ---- 3. 落墨平移 ----

    @Test
    fun `文本行与盒底色随偏移平移`() {
        val base = run("<html><body><p style=\"background-color:#fff176\">abcdefgh</p></body></html>")
        val rel = run(
            "<html><body><p style=\"background-color:#fff176;position:relative;top:20px;left:10px\">abcdefgh</p></body></html>",
        )
        val b0 = base.lines().values.single()
        val r0 = rel.lines().values.single()
        assertEquals("行顶 +20", b0.yTop + 20, r0.yTop)
        assertEquals("行底 +20", b0.yBottom + 20, r0.yBottom)
        assertEquals("行左 +10", b0.xLeft + 10, r0.xLeft)
        assertEquals("行宽不动", b0.lineWidthPx, r0.lineWidthPx)

        val bRect = backgroundRect(base.result)
        val rRect = backgroundRect(rel.result)
        assertEquals("底色矩形顶 +20", bRect.top + 20, rRect.top)
        assertEquals("底色矩形底 +20", bRect.bottom + 20, rRect.bottom)
        assertEquals("底色矩形左 +10", bRect.left + 10, rRect.left)
        assertEquals("底色矩形右 +10", bRect.right + 10, rRect.right)
    }

    /** 该章里承载高亮底色的那个盒（按色值认，避开 body/根盒的默认底色）。 */
    private fun backgroundRect(result: BoxLayoutResult): orilumn.reader.engine.laying.DrawRect {
        val bgs = BoxDrawer.draw(result.boxes).filter {
            it.kind == orilumn.reader.engine.laying.DrawKind.BACKGROUND && it.colorHex.endsWith("fff176", true)
        }
        return bgs.singleOrNull()
            ?: error("找不到高亮底色盒；实得 ${BoxDrawer.draw(result.boxes).map { it.kind to it.colorHex }}")
    }

    @Test
    fun `偏移沿祖先链累积`() {
        val r = run(
            "<html><body><div style=\"position:relative;top:5px;left:5px\">" +
                "<p style=\"position:relative;top:3px;left:3px\">abc</p></div></body></html>",
        )
        val p = leaf(r.result, "p")
        assertEquals("子 = 祖先 5 + 自身 3", 8, p.relOffsetX)
        assertEquals(8, p.relOffsetY)
        val div = allBoxes(r.result).first { it.el?.tag == "div" }
        assertEquals("容器自身只算自己那一段", 5, div.relOffsetX)
        val base = run("<html><body><div><p>abc</p></div></body></html>")
        val bLine = base.lines().values.single()
        val rLine = r.lines().values.single()
        assertEquals(bLine.yTop + 8, rLine.yTop)
        assertEquals(bLine.xLeft + 8, rLine.xLeft)
    }

    // ---- 4. 反空跑 ----

    @Test
    fun `非相对定位元素上的偏移一律不生效`() {
        val html = "<html><body><p style=\"top:20px;left:10px\">abc</p></body></html>"
        val r = run(html)
        val p = leaf(r.result, "p")
        assertEquals("没写 position 时 top/left 无效果（CSS 2.1 §9.4.3）", 0, p.relOffsetX)
        assertEquals(0, p.relOffsetY)
        val base = run("<html><body><p>abc</p></body></html>")
        assertEquals("与完全没写偏移的对照逐值相同", base.lines().values.single().yTop, r.lines().values.single().yTop)
        assertEquals(base.lines().values.single().xLeft, r.lines().values.single().xLeft)

        val abs = run("<html><body><p style=\"position:absolute;top:20px\">abc</p></body></html>")
        assertEquals("absolute 不在本刀范围，不得被当成 relative 偏移", 0, leaf(abs.result, "p").relOffsetY)
    }
}
