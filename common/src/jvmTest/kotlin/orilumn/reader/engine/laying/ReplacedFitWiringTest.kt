package orilumn.reader.engine.laying

import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.MarkupElement
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版层（上）· P3 替换叶高度预算的**重路径接线**（`docs/分页高度约束与断页规则-方案.md` §5）。
 *
 * 这里测「盒流真的照预算缩了、缩完簇装得下一页」，量法细节交给线上实现；轻路径的同一判据走
 * `:engine-skia` 的临时页一致性测试。**不变量**（任何块级替换叶 ≤ 一页）与**口径乙**（图与图注
 * 同页）都在这里各压一条。
 */
class ReplacedFitWiringTest {

    /** 一行吃掉全部文本，行高 = fontSize × ratio —— 让每段高度可控可算。 */
    private class OneLineBreaker : ParagraphBreaker {
        override fun breakLines(
            text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
            alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>,
            weight: Int, italic: Boolean, monospace: Boolean,
        ): List<BrokenLine> {
            if (text.isEmpty()) return emptyList()
            val h = (fontSizePx * (if (lineHeightRatio > 0f) lineHeightRatio else 1f)).roundToInt().coerceAtLeast(1)
            return listOf(BrokenLine(0 until text.length, h))
        }
    }

    private fun text(t: String) = MarkupElement("#text", text = t)

    /** 10px 高、零边距的文本块。 */
    private fun para(body: String, extra: String = ""): MarkupElement =
        MarkupElement("p", mapOf("style" to "margin:0; font-size:10px; line-height:1; $extra"), listOf(text(body)))

    /** 独图块：`<p>` 里只有一张图 ⇒ 提升成 img 叶（宿主块就是那个 `p`）。 */
    private fun figure(
        w: Int,
        h: Int,
        imgStyle: String = "",
        figureStyle: String = "",
    ): MarkupElement = MarkupElement(
        "p",
        mapOf("style" to "margin:0; font-size:10px; line-height:1; $figureStyle"),
        listOf(MarkupElement("img", mapOf("src" to "a.png", "width" to "$w", "height" to "$h", "style" to imgStyle))),
    )

    private fun layout(root: MarkupElement, contentH: Int, widthPx: Int = 500): BoxLayoutResult {
        val styles: Map<MarkupElement, ComputedStyle> =
            StyleComputer(10f, LightCssParser().parse(""), listOf(LightCssParser().parse("")))
                .compute(root)
        return BoxLayouter(10f, OneLineBreaker()).layoutBoxes(
            root, widthPx, styles, NormalFlowLayout.heavyClassify(styles, false),
            imageLoader = null, chapterHref = "Text/Ch01.htm", contentH = contentH,
        )
    }

    private fun leaves(r: BoxLayoutResult): List<LayoutBox> {
        val out = mutableListOf<LayoutBox>()
        fun walk(boxes: List<LayoutBox>) { for (b in boxes) if (b.isContainer) walk(b.childBoxes) else out.add(b) }
        walk(r.boxes)
        return out
    }

    private fun imgOf(r: BoxLayoutResult) = leaves(r).first { it.el?.tag == "img" }

    private fun captionOf(r: BoxLayoutResult, body: String) =
        leaves(r).first { it.el?.children?.firstOrNull()?.text == body }

    /** 两个叶在全局行流里的纵向跨度（含折叠间隙）。 */
    private fun extent(r: BoxLayoutResult, top: LayoutBox, bottom: LayoutBox): Int =
        r.lines[bottom.lastLineExclusive - 1].yBottom - r.lines[top.firstLineIndex].yTop

    private fun usedOf(box: LayoutBox, fitMaxH: Int? = box.replaceableFitH): Pair<Int, Int> =
        NormalFlowLayout.replacedUsedSize(
            box.el!!, box.style, NormalFlowLayout.innerBreakWidth(box.style, box.contentWidth),
            null, "Text/Ch01.htm", fitMaxH,
        )

    @Test
    fun `内容高为 0 时不约束_旧行为逐字节一致`() {
        val r = layout(MarkupElement("body", children = listOf(figure(10, 3000))), contentH = 0)
        val img = imgOf(r)
        assertNull(img.replaceableFitH)
        assertEquals(3000, img.replaceableHeight)
    }

    @Test
    fun `图装得下一页就不约束`() {
        val r = layout(MarkupElement("body", children = listOf(figure(10, 300))), contentH = 1000)
        val img = imgOf(r)
        assertNull(img.replaceableFitH)
        assertEquals(300, img.replaceableHeight)
    }

    @Test
    fun `不变量_高过一页的图等比缩到一页_无图注`() {
        val r = layout(MarkupElement("body", children = listOf(figure(10, 300))), contentH = 100)
        val img = imgOf(r)
        assertEquals(100, img.replaceableFitH)
        assertEquals(100, img.replaceableHeight)
        // 10×300 缩到高 100 ⇒ 宽按比例 3（不裁切：宽跟着缩）。
        assertEquals(3, usedOf(img).first)
        assertEquals(100, usedOf(img).second)
    }

    @Test
    fun `口径乙_图与图注同页_预算先扣图注高`() {
        // 预算 = 100 − 图注 10 = 90；若丢了宿主块的 page-break-after:avoid，簇里只剩图本身，预算会变成 100。
        val r = layout(
            MarkupElement(
                "body",
                children = listOf(
                    para("上文"),
                    figure(10, 300, figureStyle = "page-break-after: avoid"),
                    para("图 1 说明"),
                    para("后文"),
                ),
            ),
            contentH = 100,
        )
        val img = imgOf(r)
        val caption = captionOf(r, "图 1 说明")
        assertEquals(90, img.replaceableFitH)
        assertEquals(90, img.replaceableHeight)
        // 图 + 图注整个簇装得进一页（这正是「被页底裁」的反面）。
        assertTrue(extent(r, img, caption) <= 100)
        // 绘制侧按同一个预算还原 ⇒ 也得到 90（量画同值）。
        assertEquals(90, usedOf(img).second)
    }

    @Test
    fun `提升叶带出宿主块的 break 边_否则图注不成簇`() {
        val r = layout(
            MarkupElement(
                "body",
                children = listOf(figure(10, 300, figureStyle = "page-break-after: avoid"), para("图 1 说明")),
            ),
            contentH = 1000,
        )
        val img = imgOf(r)
        val caption = captionOf(r, "图 1 说明")
        assertEquals(orilumn.reader.engine.css.BreakRule.AVOID, img.breakAfter)
        val clusters = BreakClusters.of(r.boxes)
        assertTrue(
            "img..caption 应落在同一个簇区间里",
            clusters.any { it.first <= img.firstLineIndex && it.last >= caption.lastLineExclusive - 1 },
        )
    }

    @Test
    fun `强制边截断簇_图注不并入预算`() {
        val r = layout(
            MarkupElement(
                "body",
                children = listOf(
                    figure(10, 3000, figureStyle = "page-break-after: always"),
                    para("图 1 说明"),
                ),
            ),
            contentH = 100,
        )
        // always ⇒ 簇 = {图} ⇒ 预算 100（图注不参与）。
        assertEquals(100, imgOf(r).replaceableFitH)
    }

    @Test
    fun `图注自己就高过一页_缩图也救不回来_维持原样`() {
        val r = layout(
            MarkupElement(
                "body",
                children = listOf(figure(10, 300, figureStyle = "page-break-after: avoid"), para("图注", extra = "font-size:480px")),
            ),
            contentH = 300,
        )
        assertNull(imgOf(r).replaceableFitH)
        assertEquals(300, imgOf(r).replaceableHeight)
    }

    @Test
    fun `缩到下限以下不如不缩`() {
        // contentH 600 ⇒ 下限 100。图注 480 ⇒ 余 120 ≥ 100 ⇒ 缩到 120。
        val a = layout(
            MarkupElement(
                "body",
                children = listOf(figure(10, 3000, figureStyle = "page-break-after: avoid"), para("图注", extra = "font-size:480px")),
            ),
            contentH = 600,
        )
        assertEquals(120, imgOf(a).replaceableFitH)
        // 图注 520 ⇒ 余 80 < 100 ⇒ 不缩。
        val b = layout(
            MarkupElement(
                "body",
                children = listOf(figure(10, 3000, figureStyle = "page-break-after: avoid"), para("图注", extra = "font-size:520px")),
            ),
            contentH = 600,
        )
        assertNull(imgOf(b).replaceableFitH)
        assertEquals(3000, imgOf(b).replaceableHeight)
    }

    @Test
    fun `显式 min-height 压过预算_图仍可超页并撕裂_有意的例外`() {
        val r = layout(MarkupElement("body", children = listOf(figure(10, 300, imgStyle = "min-height:300px"))), contentH = 100)
        val img = imgOf(r)
        // 预算照算（绘制侧要同值），但 §10.4 的 min 压过 max ⇒ 实际高度仍是 300。
        assertNotNull(img.replaceableFitH)
        assertEquals(300, img.replaceableHeight)
        // 量画同值。
        assertEquals(300, usedOf(img).second)
    }

    @Test
    fun `悬浮替换叶本轮不参与预算`() {
        val r = layout(MarkupElement("body", children = listOf(figure(10, 3000, imgStyle = "float:left"))), contentH = 100)
        assertNull(imgOf(r).replaceableFitH)
    }
}
