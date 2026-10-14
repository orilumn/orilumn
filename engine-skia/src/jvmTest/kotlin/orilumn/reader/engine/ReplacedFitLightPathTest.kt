package orilumn.reader.engine

import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.css.themeSheetFromProfile
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.skia.LayoutReadback
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排版层（上）· P3 替换叶高度预算的**轻路径（临时页）接线**（`docs/分页高度约束与断页规则-方案.md` §5）。
 *
 * 三条必须同时成立，缺一条就是「量画漂移 / 重轻分叉」：
 * 1. 重路径与轻路径对同一章算出**同一个预算**（决策单源 [orilumn.reader.engine.laying.ReplacedFitBudget]）；
 * 2. 轻路径塑形读到的替换高 == 盒上写的高（`ShapeGeometry` 的 `fitMaxH` 必须从盒来）；
 * 3. 临时页真的把「图 + 图注」放进同一页（口径乙），而不是把图注挤到下一页。
 *
 * 尺寸全部走 `width`/`height` 属性 ⇒ 不依赖仓库图片资源，也不依赖解码。
 */
class ReplacedFitLightPathTest {

    private val contentW = 500
    private val contentH = 300

    /** 图固有 100×600 ⇒ 在 500 版心下 used 高 600 > 一页 300（触发预算）。 */
    private val intrinsicW = 100
    private val intrinsicH = 600

    private val chapterHref = "OEBPS/Text/Ch01.htm"

    private val html = """
        <html><body>
        <p class="fm-figure"><img src="figure.png" width="$intrinsicW" height="$intrinsicH" /></p>
        <p>图 1 说明。</p>
        <p>图后的正文段落。</p>
        </body></html>
    """.trimIndent()

    /** 真书 `.fm-figure{page-break-after:avoid}` —— avoid 边挂在**块**上，不在 img 上。 */
    private val authorCss = ".fm-figure { page-break-after: avoid; }"

    private fun profile() = TypographicProfile.build(ReaderSettings.DEFAULT.copy(layoutTheme = "modern"))

    private fun layouter(): BoxChapterLayouter =
        BoxChapterLayouter(themeSheet = themeSheetFromProfile(profile()))
            .also { it.bindChapterContext(chapterHref) }

    private fun root(): MarkupElement = HtmlTreeConverter().convert(html)!!

    private fun heavy(l: BoxChapterLayouter) =
        l.prepare(root(), CssBundle(listOf(authorCss)), profile(), contentW, contentH)

    private fun light(l: BoxChapterLayouter) =
        l.prepareLight(root(), CssBundle(listOf(authorCss)), profile(), contentW, ChapterStructureCache(), contentH)

    private fun blockIndexOfTag(p: LightPrepare, tag: String): Int =
        (0 until p.totalBlocks).first { p.block(it).el?.tag == tag }

    private fun captionIndex(p: LightPrepare): Int =
        (0 until p.totalBlocks).first { p.block(it).el?.tag == "p" }

    @Test
    fun `重轻两路预算同值_且都不超过一页`() {
        val l = layouter()
        val h = heavy(l)
        val p = light(l)
        // 轻路径的预算是**按块懒算**的（在 `tempShape` 塑形之前写入盒）——临时页就是这么走的，
        // 所以先按生产路径逐叶塑形一遍，再比对盒。
        for (i in 0 until p.totalBlocks) l.tempShape(null, p, i, profile())
        assertEquals("重轻两路叶数必须一致", h.leaves.size, p.totalBlocks)
        for (i in 0 until p.totalBlocks) {
            assertEquals("第 $i 叶预算不一致", h.leaves[i].replaceableFitH, p.block(i).replaceableFitH)
            assertEquals("第 $i 叶盒高不一致", h.leaves[i].replaceableHeight, p.block(i).replaceableHeight)
        }
        val lImg = p.block(blockIndexOfTag(p, "img"))
        assertTrue("预算必须真的生效（图固有 $intrinsicH > 一页 $contentH）", lImg.replaceableFitH != null)
        assertTrue("不变量：块级替换叶 ≤ 一页", lImg.replaceableHeight <= contentH)
        // 口径乙：预算等于「一页 − 图注」，故必然小于整页。
        assertTrue("预算要扣掉图注高", lImg.replaceableHeight < contentH)
    }

    @Test
    fun `轻路径塑形高等于盒高_量画同值`() {
        val l = layouter()
        val p = light(l)
        val i = blockIndexOfTag(p, "img")
        val img = p.block(i)
        val shape = l.tempShape(null, p, i, profile())
        assertTrue("该叶应被塑形成替换件", shape.isReplaceable)
        assertEquals(
            "塑形侧必须读盒上的预算（ShapeGeometry.fitMaxH）",
            img.replaceableHeight, shape.replaceableBottom,
        )
    }

    @Test
    fun `前向临时页把图与图注放进同一页`() {
        val l = layouter()
        val p = light(l)
        val img = blockIndexOfTag(p, "img")
        val cap = captionIndex(p)
        val fwd = l.shapeTempPageForward(p, profile(), contentW, contentH, img, null, 0)!!
        val slice = fwd.page.slice
        assertTrue(
            "图注被挤到下一页了：页块区间 [${slice.blockStart},${slice.blockEndExclusive})",
            cap in slice.blockStart until slice.blockEndExclusive,
        )
        // 图确实画在这一页的窗口里（不是被页底裁掉、也不是空页）。
        val rb = fwd.page.layout as LayoutReadback
        assertTrue(
            "图不在该页的绘制窗口内：firstLine=${slice.firstLine} pageEnd=${slice.lastLineExclusive}",
            rb.pageImages(slice.firstLine, slice.lastLineExclusive).isNotEmpty(),
        )
    }

    @Test
    fun `后向临时页的图不越过页顶`() {
        val l = layouter()
        val p = light(l)
        val img = blockIndexOfTag(p, "img")
        val cap = captionIndex(p)
        val back = l.shapeTempPageBackward(p, profile(), contentW, contentH, cap + 1, -1, null)!!
        val slice = back.slice
        assertTrue(
            "后向页块区间不含图块：blk=[${slice.blockStart},${slice.blockEndExclusive}) 图=$img",
            img in slice.blockStart until slice.blockEndExclusive,
        )
        val rb = back.layout as LayoutReadback
        assertTrue(
            "图不在该页的绘制窗口内：firstLine=${slice.firstLine} pageEnd=${slice.lastLineExclusive}",
            rb.pageImages(slice.firstLine, slice.lastLineExclusive).isNotEmpty(),
        )
    }
}
