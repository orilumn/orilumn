package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.BLOCK_TAGS
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.laying.BlockClassify
import orilumn.reader.engine.laying.HiddenCheck
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.skia.shapeGeometry
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轻路径（临时表 / 增量塑形）必须与 canonical 同口径发出**祖先块上的文本装饰**。
 *
 * ## 症状（2026-10-20 用户报）
 *
 * `v4-inline-block.xhtml` 的 `<a class="al">zzz<p>abc</p>www</a>`：下划线**首开不显示、
 * 再开才显示**。首开走 `prepareLight` + `shapeGeometry`（临时页），再开命中磁盘表走
 * canonical `DrawLineBuilder`（整章 `styleMap`）。同一段文本两条路径一有一无 —— 违反
 * 「重/轻几何逐值一致」的铁律。
 *
 * ## 根因
 *
 * 装饰是**非继承但传播**的（CSS Text Decoration 3 的 decorating box）：`<a>` 上的
 * `text-decoration:underline` 要穿过块边界落到 `<p>abc</p>` 上。轻路径的 `styles` 表是
 * `LightPrepare.inlineStyles` —— **只含叶根子树、不含祖先**；`shapeGeometry` 把这张表喂给
 * `decorationRunsOf` 时没带 `styleOf` 回退，祖先那层装饰就整条丢掉（canonical 用整章表，
 * 所以磁盘表页正常）。
 *
 * 锁：轻路径（残表 + 懒级联回退）与 canonical（全表）必须发出**逐字段相同**的装饰段。
 */
class LightPathAncestorDecorationTest {

    private val html =
        """<html><body><a class="al" href="#">zzz<p>abc</p>www</a></body></html>"""

    private val css = ".al{color:#00f;text-decoration:underline}"

    private val profile = TypographicProfile.build(ReaderSettings.DEFAULT)
    private val classify = BlockClassify { it.tag in BLOCK_TAGS }
    private val hidden = HiddenCheck { false }

    private class Fixture(
        val full: Map<MarkupElement, ComputedStyle>,
        val engine: StyleComputer,
        val cache: MutableMap<MarkupElement, ComputedStyle>,
    ) {
        fun lazy(el: MarkupElement): ComputedStyle = engine.resolve(el, cache)
    }

    private fun fixture(): Fixture {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(
            profile.bodyPx,
            LightCssParser().parse(""),
            listOf(LightCssParser().parse(css)),
        )
        return Fixture(engine.compute(root), engine, HashMap())
    }

    /** 轻路径一次 `shapeGeometry`：`styles` 只给叶根（模拟 `inlineStyles` 的残表）。 */
    private fun lightRuns(
        f: Fixture,
        leaf: MarkupElement,
        rootStyle: ComputedStyle,
        styles: Map<MarkupElement, ComputedStyle>,
    ) = shapeGeometry(
        leaf, rootStyle, styles, profile, 500,
        ancestorStyleOf = f::lazy,
        classify = classify, hidden = hidden, isBlock = { it.tag in BLOCK_TAGS },
    ).decorationRuns

    @Test
    fun `块级叶仍带祖先链接的下划线`() {
        val f = fixture()
        val p = f.full.keys.first { it.tag == "p" }
        val heavy = NormalFlowLayout.leafTextDecorationRuns(p, f.full, classify, hidden, { null })
        val light = lightRuns(f, p, f.full.getValue(p), mapOf(p to f.full.getValue(p)))

        assertTrue("canonical 必须发出下划线（对照组）", heavy.isNotEmpty())
        assertEquals("轻路径装饰段数量必须与 canonical 一致", heavy.size, light.size)
        assertEquals("装饰段区间必须逐值相同", heavy.map { it.start to it.endExclusive }, light.map { it.start to it.endExclusive })
        assertEquals(
            "装饰层（线型/颜色/粗细/字号）必须逐值相同",
            heavy.flatMap { r -> r.paints.map { it.lines to it.colorHex to it.thicknessPx } },
            light.flatMap { r -> r.paints.map { it.lines to it.colorHex to it.thicknessPx } },
        )
    }

    @Test
    fun `行内匿名文本叶也继承祖先下划线`() {
        val f = fixture()
        val p = f.full.keys.first { it.tag == "p" }
        val link = p.parent!!
        val textEl = link.children.first { it.isText }
        val heavy = NormalFlowLayout.leafTextDecorationRuns(textEl, f.full, classify, hidden, { null })
        // 轻路径的文本叶残表是空的（`inlineStyles(#text)` = emptyMap），根样式取父块。
        val light = lightRuns(f, textEl, f.full.getValue(link), emptyMap())

        assertTrue("canonical 必须发出下划线（对照组）", heavy.isNotEmpty())
        assertEquals(heavy.size, light.size)
        assertEquals(heavy.map { it.start to it.endExclusive }, light.map { it.start to it.endExclusive })
    }
}
