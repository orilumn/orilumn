package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxDrawer
import orilumn.reader.engine.laying.DrawKind
import orilumn.reader.engine.laying.FlowedLine
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻路径（临时表 / 增量窗）必须画出**只装浮动子盒的容器自己的底色与边框**。
 *
 * ## 症状（2026-10-20 用户报）
 *
 * `v6-vis-overflow-rel.xhtml` 末尾 `<div class="cf">` 里四个浮动方块：黑边 + 灰底的外框
 * **首开不画、再开才出现**。重路径画盒树里的每个容器盒；轻路径 `buildBackgroundDrawBoxes`
 * 原先只沿「叶 → 最近背景属主」聚合 —— 浮动方块自带背景 ⇒ 属主是方块自己，`.cf` 永远
 * 进不了属主表，整块被丢掉（它的 padding 仍把方块推开了，所以看上去是「缺个框」）。
 *
 * 锁两件事：
 *  1. 无叶属主的**背景容器**也要出盒（边框-only 容器早有同类处理，这里补上带底色的）；
 *  2. 绘制顺序**祖先先于后代**（不透明的容器底色不能盖住里面的方块）。
 */
class LightPathFloatContainerBoxTest {

    private val html =
        """<html><body><div class="cf"><div class="fl"></div><div class="fl"></div></div></body></html>"""

    private val css = """
        .cf { overflow: hidden; border: 1px solid #000; padding: 4px; background: #f5f5f5; }
        .fl { float: left; width: 30px; height: 30px; background: #d9f7be; border: 1px solid #52c41a; margin: 2px; }
    """.trimIndent()

    @Test
    fun `只装浮动的容器在轻路径也出底色边框盒且排在浮动之前`() {
        val profile = TypographicProfile.build(ReaderSettings.DEFAULT.copy(useOriginalStyle = true))
        val layouter = BoxChapterLayouter()
        val root = HtmlTreeConverter().convert(html)!!
        val prepare = layouter.prepareLight(root, CssBundle(listOf(css)), profile, 500, ChapterStructureCache(), 800)

        val leaves = prepare.blocks(0, prepare.totalBlocks)
        assertEquals("叶集合应只有两个浮动方块", 2, leaves.size)
        // 浮动方块是零字符叶：行流没有它们的行，位置由 lineLessBoxTop 记账（border-box 顶）。
        // 前面的 5 行正文用占位行表示（叶在流里的锚点 = 第 5 行，与真实页的页归属判据同口径）。
        val lines = (0 until 5).map { i ->
            FlowedLine(charStart = i * 10, charEnd = i * 10 + 9, yTop = i * 30, yBottom = i * 30 + 20, paragraphStart = true)
        }
        val tops = IntArray(leaves.size) { 100 }
        val boxes = layouter.buildBackgroundDrawBoxes(
            prepare, leaves, lines,
            IntArray(leaves.size) { 5 }, IntArray(leaves.size) { 5 }, tops,
        ).boxes

        val cfIndex = boxes.indexOfFirst { it.el?.attrs?.get("class") == "cf" }
        assertTrue("背景容器 .cf 的盒子缺失（轻路径丢容器底色/边框）", cfIndex >= 0)
        val cf = boxes[cfIndex]
        // 与 Chromium 同值：浮动 30 + 上下外边距 2×2 + 容器 padding 4×2 + 边框 1×2 = 46。
        assertEquals("容器盒高度必须含浮动外边距（CSS 2.1 §10.6.7）", 46, cf.contentBottom - cf.contentTop)
        val firstFloat = boxes.indexOfFirst { it.el?.attrs?.get("class") == "fl" }
        assertTrue("浮动方块盒应存在", firstFloat >= 0)
        assertTrue("容器必须排在浮动之前（否则底色盖住方块）", cfIndex < firstFloat)

        val rects = BoxDrawer.draw(listOf(cf))
        assertTrue("容器底色缺失", rects.any { it.kind == DrawKind.BACKGROUND })
        assertTrue("容器边框缺失", rects.any { it.kind == DrawKind.BORDER })
    }
}
