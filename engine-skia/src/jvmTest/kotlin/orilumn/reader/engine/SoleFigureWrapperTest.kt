package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.paging.PageSlice
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 非回归锁：**独图块（`<p class="fig"><img/></p>`）的宿主块纵向 margin** 必须进块边界间距。
 *
 * ## 现场
 *
 * 重路径 `NormalFlowLayout.buildBoxTree` 把「只含一幅图」的块**拆包**：`<img>` 提升为替换叶、
 * 宿主 `<p>` 的盒被摘掉（否则 `U+FFFC` 走塑形会被画成小虚线框、图丢失）。但轻路径的块边界间距
 * 由 `NormalFlowLayout.consecutiveLeafAdvance` 沿**标记祖先链**算——它含宿主块；重路径 `emit`
 * 只看叶自身 `style`。摘包后若宿主块的 margin 一起丢，图块前后间距就比 CSS 折叠值少一截
 * （真书 `Kotlin in Action` ch2 `.fm-figure{margin:1.2em 0 .8em}`：应为 46px，实际 13px），
 * 页高预算随之算错（真机曾出现末行越出栅格被裁）。
 *
 * ## 锁法（每张表各自对 CSS 绝对值负责，不互相比对 —— 见 AGENTS.md「磁盘表与临时表互斥」）
 *
 * 1. 提升叶的 `style.margin` 必须等于「宿主块链折叠后的纵向 margin」；
 * 2. 图块边界间距在磁盘表与临时表里都必须等于 CSS 折叠值（48px）；
 * 3. 临时表前向翻页逐字符无缝拼接（页尾取行首而非行内末字符）。
 */
class SoleFigureWrapperTest {

    private val layouter = BoxChapterLayouter()
    private val converter = HtmlTreeConverter()
    private val contentW = 720
    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)

    /** 宿主块 margin：上 48 / 下 32（正文段 16/16）⇒ 折叠后上间距 = max(48, 16) = 48。 */
    private val figTop = 48f
    private val figBottom = 32f
    private val foldedGap = 48

    private val css = CssBundle(
        listOf(
            """
            body { font-size: 16px; line-height: 1.5; margin: 0; }
            p { margin: 16px 0; }
            p.fig { margin: ${figTop.toInt()}px 0 ${figBottom.toInt()}px; }
            pre { margin: 16px 0; white-space: pre; font-family: monospace; font-size: 14px; line-height: 1.4; }
            """.trimIndent(),
        ),
    )

    private fun para(tag: String, text: String) = "<p class=\"$tag\">\n$text\n</p>"

    /** 多行 `<pre>`：`white-space: pre` 保留换行 ⇒ 行流**行内**每行末都比下一行首少 1 字符。 */
    private fun code(lines: Int) = "<pre>" + (1..lines).joinToString("\n") { "val v$it = f($it)" } + "\n</pre>"

    /**
     * 三幅图**连续**出现（图→图边界同样走宿主块 margin 折叠），前后各接长段与多行代码块，保证扫描
     * `contentH` 时切点会落在图块前后、图块之间，以及**代码块内部**（页尾字符边界的关键路径）。
     */
    private fun chapter(): MarkupElement = converter.convert(
        """
        <html><body>
          <h2>独图块与块边界间距</h2>
          ${para("body", "图之前的长段落。".repeat(20))}
          ${code(24)}
          <p class="fig"><img src="fig1.png" width="120" height="90"/></p>
          <p class="fig"><img src="fig2.png" width="120" height="90"/></p>
          <p class="fig"><img src="fig3.png" width="120" height="90"/></p>
          ${code(24)}
          ${para("body", "图之后的长段落。".repeat(20))}
        </body></html>
        """.trimIndent(),
    )!!

    private fun tempForward(markup: MarkupElement, contentH: Int): List<PageSlice> {
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)
        val out = ArrayList<PageSlice>()
        var block = 0
        var line = 0
        var guard = 0
        while (block < light.totalBlocks && guard++ < 2000) {
            val fwd = layouter.shapeTempPageForward(
                light, profile, contentW, contentH, block, cache = null, startLine = line,
            ) ?: break
            out.add(fwd.page.slice)
            if (fwd.nextBlock >= light.totalBlocks) break
            block = fwd.nextBlock
            line = fwd.nextLine
        }
        return out
    }

    /** 根因直锁：提升叶的 margin 必须带上宿主块链折叠后的纵向 margin（绝对值，来自 CSS）。 */
    @Test
    fun `promoted figure leaf carries its wrapper block margins`() {
        val heavy = layouter.prepare(chapter(), css, profile, contentW, 600)
        val imgs = heavy.leaves.filter { it.el?.tag == "img" }
        assertEquals("fixture 应有 3 幅独图", 3, imgs.size)
        for (b in imgs) {
            assertEquals("提升叶 margin-top 必须折入宿主块 margin", figTop, b.style.margin.top, 0.01f)
            assertEquals("提升叶 margin-bottom 必须折入宿主块 margin", figBottom, b.style.margin.bottom, 0.01f)
        }
    }

    /**
     * **无洞锁**（只看临时表）：前向翻页必须**逐字符无缝拼接**——相邻两页
     * `charEnd == charStart`，且末页收到章末。
     *
     * 为什么单列这条：页尾字符边界若取「末行自身 charEnd」（行流里的**行内包含式**末字符），
     * 而下一页页首取「该行的 charStart」，美化排版（块内换行/缩进）归一后每个块边界都会正好
     * 差 1 个字符 ⇒ 页页漏 1 字。真机现象即「翻页/调参后少字」。
     */
    @Test
    fun `temp forward pages tile the chapter without a single-char hole`() {
        val markup = chapter()
        var checked = 0
        for (contentH in 220..900 step 4) {
            val temp = tempForward(markup, contentH)
            val totalChars = chapterTotalChars(markup, contentH)
            assertTrue("contentH=$contentH 临时表必须出页", temp.isNotEmpty())
            assertEquals("contentH=$contentH 首字节页 charStart", 0, temp.first().charStart)
            assertEquals("contentH=$contentH 末页须收到章末", totalChars, temp.last().charEnd)
            for (i in 0 until temp.size - 1) {
                assertEquals(
                    "contentH=$contentH 第 $i→${i + 1} 页之间漏字/重叠",
                    temp[i].charEnd, temp[i + 1].charStart,
                )
            }
            checked++
        }
        assertTrue("没有扫到任何档 ⇒ 用例空跑", checked > 50)
    }

    private fun chapterTotalChars(markup: MarkupElement, contentH: Int): Int =
        layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH).totalChars

    /**
     * 图块边界间距：轻路径的 `consecutiveLeafAdvance` 与磁盘表的行流**各自**都要等于 CSS 折叠值
     * （`max(48, 16) = 48`）。两边都比同一个绝对值，不互相比对。
     */
    @Test
    fun `figure gap equals the css-folded margin on each table`() {
        val markup = chapter()
        val contentH = 2400
        val heavy = layouter.prepare(markup, css, profile, contentW, contentH)
        val product = layouter.fullLayout(heavy, profile, contentW, contentH)
        val light = layouter.prepareLight(markup, css, profile, contentW, ChapterStructureCache(), contentH)

        val styleOf: (MarkupElement) -> ComputedStyle = light::resolveStyle
        val lightLeaves = (0 until light.totalBlocks).map { light.block(it) }
        val imgIdx = lightLeaves.indexOfFirst { it.el?.tag == "img" }
        assertTrue("轻路径应把独图枚举成 img 叶", imgIdx > 0)
        val lightAdvance = NormalFlowLayout.consecutiveLeafAdvance(
            lightLeaves[imgIdx - 1].el!!, lightLeaves[imgIdx].el!!, styleOf,
        ) + (lightLeaves[imgIdx - 1].style.padding.bottom + lightLeaves[imgIdx - 1].style.border.bottom).toInt() +
            (lightLeaves[imgIdx].style.padding.top + lightLeaves[imgIdx].style.border.top).toInt()
        assertEquals("临时表的图块上间距必须等于 CSS 折叠值", foldedGap, lightAdvance)

        // 磁盘表：图行行顶 - 前一段末行行底 = 同一段块边界间距。
        val lines = product.layout
        val figLine = (0 until lines.lineCount).first { lines.getLineStart(it) == light.globalCharStarts[imgIdx].toInt() }
        val heavyGap = lines.getLineTop(figLine) - lines.getLineBottom(figLine - 1)
        assertEquals("磁盘表的图块上间距必须等于 CSS 折叠值", foldedGap, heavyGap)
    }

    /** `LayoutBox` 便捷取用（避免测试里到处写全限定名）。 */
    private val LayoutBox.marginTop: Float get() = style.margin.top
}
