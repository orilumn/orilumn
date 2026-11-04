package orilumn.reader.engine

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.CssBundle
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.skia.DrawLineBuilder
import orilumn.reader.engine.text.TypographicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 字间距「没配按书、配了压书」——`BoxChapterLayouter.chapterLetterSpacingEm` 的端到端锁。
 *
 * 2026-10-29 之前书的 `letter-spacing` 是死字段：级联里算出来了，断行/绘制/命中三处却只认
 * 读者设置的那一个全局 em，于是书里写字距的书排出来从不生效。现在章级取值一处决定
 * （读者优先、否则书），断行器、塑形、绘制、命中全拿这一个数——本用例同时锁住**量画同值**。
 */
class BookLetterSpacingTest {

    private val body = "这一段正文足够长，用来让字距的改变真的影响一行能放几个字，从而影响断行数量。" +
        "再多写几句保证一定能断成好几行，免得只差一点点的时候看不出差别来。"

    private fun html() = """<html><body><p>$body</p><p>$body</p></body></html>"""

    private fun prepare(
        bookCss: String,
        settings: ReaderSettings = ReaderSettings.DEFAULT,
        contentW: Int = 360,
    ): ChapterPrepareResult {
        val profile = TypographicProfile.build(settings, density = 1f)
        return BoxChapterLayouter().prepare(
            markup = HtmlTreeConverter().convert(html())!!,
            cssBundle = if (bookCss.isEmpty()) null else CssBundle(listOf(bookCss)),
            profile = profile,
            contentW = contentW,
            contentH = 640,
        )
    }

    private fun assertEm(expected: Float, actual: Float) =
        assertEquals("章级生效字距（em）", expected, actual, 1e-4f)

    @Test
    fun `书写了字距就按书的排`() {
        val p = prepare("p { letter-spacing: 0.05em }")
        assertEm(0.05f, p.letterSpacingEm)
    }

    @Test
    fun `书把字距写在 body 上也算`() {
        // 继承口径：`letter-spacing` 可从 body 继承到 p（与浏览器一致）。
        val p = prepare("body { letter-spacing: 0.03em }")
        assertEm(0.03f, p.letterSpacingEm)
    }

    @Test
    fun `书没写字距就是 0`() {
        assertEm(0f, prepare("").letterSpacingEm)
    }

    @Test
    fun `读者配了就压过书`() {
        // 槽位 20 → 0.04em（每档 0.002em）。
        val p = prepare("p { letter-spacing: 0.05em }", ReaderSettings.DEFAULT.copy(letterSpacing = 20.0))
        assertEm(0.04f, p.letterSpacingEm)
    }

    @Test
    fun `读者拖到 0 档是明确不加字距_压掉书的`() {
        val p = prepare("p { letter-spacing: 0.05em }", ReaderSettings.DEFAULT.copy(letterSpacing = 0.0))
        assertEm(0f, p.letterSpacingEm)
    }

    @Test
    fun `绘制行与断行拿到同一个字距值`() {
        val p = prepare("p { letter-spacing: 0.05em }")
        val lines = DrawLineBuilder.build(p.structure, p.styleMap, p.classify, p.hidden, p.letterSpacingEm)
        assertTrue("前提：必须真的画出文本行", lines.isNotEmpty())
        lines.values.forEach { assertEquals("绘制侧字距必须与断行侧同值", p.letterSpacingEm, it.letterSpacingEm, 0f) }
    }

    @Test
    fun `书的字距真的影响断行`() {
        val tight = prepare("p { letter-spacing: normal }")
        val loose = prepare("p { letter-spacing: 0.20em }")
        val tightLines = DrawLineBuilder.build(tight.structure, tight.styleMap, tight.classify, tight.hidden, tight.letterSpacingEm).size
        val looseLines = DrawLineBuilder.build(loose.structure, loose.styleMap, loose.classify, loose.hidden, loose.letterSpacingEm).size
        assertTrue("加宽字距后必须断出更多行（紧 $tightLines / 松 $looseLines）", looseLines > tightLines)
    }
}
