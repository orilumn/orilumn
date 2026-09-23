package orilumn.reader.ui.reader

import orilumn.reader.engine.skia.SkiaFontPool
import orilumn.reader.engine.skia.SkParagraphFactory
import orilumn.reader.engine.skia.systemFontFamilies
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 真墨迹量高（栅格真值）回归：花体/手写/长尾族字形的墨迹超出字体度量行高，
 * skia `getRectsForRange(TIGHT)` 对它们假阴性（报 ≤ 行高），只有栅格墨迹能抓到
 * ——那正是字体面板"字重副标题压进名字墨迹"的根因。本测试与本机字体目录耦合
 * （族未装则跳过），与 `SystemFontDisplayChainTest` 同类。
 */
class FontInkMeasureTest {

    private fun build(family: String, text: String, px: Float = 30f): Paragraph =
        ParagraphBuilder(
            SkParagraphFactory.paragraphStyle(
                alignment = orilumn.reader.engine.css.TextAlign.LEFT,
                fontSizePx = px,
                lineHeightRatio = 0f,
                tag = "p",
                families = listOf(family),
                weight = 400,
                italic = false,
                monospace = false,
                letterSpacingEm = 0f,
                inkColor = -16777216,
            ),
            SkiaFontPool.current(),
        ).addText(text).build().also { it.layout(Float.MAX_VALUE) }

    /** 已知真实溢墨族（2026-09-23 本机 326 族全量栅格核出）。未装则跳过。 */
    private fun assertBleeds(family: String, minDiff: Float) {
        val installed = systemFontFamilies().toSet()
        if (family !in installed) {
            println("skip $family (not installed)")
            return
        }
        val p = build(family, family)
        try {
            val box = inkBoxOfParagraph(p, p.height)
            val diff = box.bottom - p.height
            println("bleed family=$family paraH=${p.height} inkBottom=${box.bottom} diff=$diff (min=$minDiff)")
            assertTrue("expected $family ink bottom to bleed ≥ ${minDiff}px below metrics, got $diff", diff >= minDiff)
        } finally {
            p.close()
        }
    }

    @Test
    fun swashCalligraphyBleedsMetrics() {
        // Zapfino 花体：墨向下溢 ~32px（15sp 名行盒会把 Regular 副标题压没）。
        assertBleeds("Zapfino", 20f)
    }

    @Test
    fun japaneseLongTailBleedsMetrics() {
        // jpfont-nds 日本手写长尾体：墨向下溢 ~68px。
        assertBleeds("jpfont-nds", 40f)
    }

    @Test
    fun minorSwashBleedsAreStillCaught() {
        // 轻度溢墨（SignPainter +3 / BM Hanna +2）也应收进墨盒，防临界字体回退。
        assertBleeds("SignPainter", 1f)
        assertBleeds("BM Hanna 11yrs Old", 1f)
    }

    @Test
    fun regularFamiliesStayWithinMetrics() {
        // 常规族：墨不溢（栅格抗锯齿至多半像素毛边），行高维持度量行高 = 不浪费空间。
        val prefer = listOf("LXGW WenKai", "PingFang SC", "Heiti SC", "Songti SC", "Arial", "Helvetica")
        val installed = systemFontFamilies().toSet()
        val family = prefer.firstOrNull { it in installed } ?: return
        val p = build(family, family)
        try {
            val box = inkBoxOfParagraph(p, p.height)
            println("normal family=$family paraH=${p.height} inkBottom=${box.bottom} diff=${box.bottom - p.height}")
            assertEquals("$family 墨不应超出度量行高", p.height.toDouble(), box.bottom.toDouble(), 2.0)
        } finally {
            p.close()
        }
    }

    @Test
    fun measurementIsDeterministicAndCached() {
        val installed = systemFontFamilies().toSet()
        if ("Zapfino" !in installed) return
        val p = build("Zapfino", "Zapfino")
        // 与面板同键（字号取整）：首次量，二次命中缓存——结果逐字节一致。
        val cacheKey = "Zapfino\u0001Zapfino\u000130"
        inkCache.remove(cacheKey)
        try {
            val a = inkCache.computeIfAbsent(cacheKey) { inkBoxOfParagraph(p, p.height) }
            val b = inkCache.computeIfAbsent(cacheKey) { inkBoxOfParagraph(p, p.height) }
            assertEquals(a, b)
        } finally {
            inkCache.remove(cacheKey)
            p.close()
        }
    }
}