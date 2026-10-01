package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.lineHeightPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5b 回归锁：[LineHitTest] 必须与**绘制同一份几何**。
 *
 * ## 这把锁守的是什么
 *
 * S5 把绘制从 `Paragraph.paint` 换成 [LineAligner] + 逐字 `drawString` 后，点按命中如果还
 * 走 Paragraph + `getGlyphPositionAtCoordinate`，两边几何就**来源不同**：
 * 绘制按「slack 均摊 + 行末空白不占宽」，命中按「Skia `kJustify` + 追加 `\n` 假首行」。
 * 两者在 JUSTIFY 行上必然错位（教训 29b 实测：行末空白一出现 Skia 就整行放弃拉伸）。
 *
 * 判据不能只断言「命中返回了合法下标」—— 那对两条路径都成立。**必须断言命中位置与
 * 绘制用的 `placement.xs` 对得上**，且**在 JUSTIFY 行上生效**（LEFT 行两边恰好一致，
 * 只有 JUSTIFY 才暴露差异）。
 */
class LineHitTestGeometryTest {

    private fun line(text: String, align: TextAlign, indent: Float = 0f) = DrawLine(
        text = text, range = 0 until text.length,
        yTop = 0, yBottom = lineHeightPx(44.4f, 1.5f),
        alignment = align, fontSizePx = 44.4f, lineHeightRatio = 1.5f, tag = "p",
        families = listOf("STSong", "serif"), weight = 400, italic = false, monospace = false,
        letterSpacingEm = 0f, lineWidthPx = 900, firstLineIndentPx = indent,
    )

    /** 与绘制侧同式：paintX = contentLeft + xLeft + firstLineIndentPx。 */
    private fun placementOf(l: DrawLine) = LineAligner().align(
        l.text, l.range, l.fontSizePx, l.lineWidthPx.toFloat(), l.letterSpacingEm,
        l.tag, l.families, l.weight, l.italic, l.monospace, l.fontRuns, l.alignment, 0f,
        l.range.last + 1 >= l.text.length,
    )

    @Test
    fun `命中索引与绘制 x 逐点一致（JUSTIFY 行，最易错位的那类）`() {
        val text = "两端对齐需要测试命中位置是否与绘制几何一致这行字足够长以便触发拉伸"
        val l = line(text, TextAlign.JUSTIFY)
        val p = placementOf(l)
        assertTrue("语料必须真触发展（slack > 0），否则本锁在 LEFT/无拉伸下也能过 = 假锁",
            p.visibleRight > 899f)
        // 逐字点它的左边缘，必须命中**那个字自己**（不是别的字）。
        for (k in 0 until p.xs.size - 1) {
            val x = p.xs[k] + 0.5f
            assertEquals("点第 $k 字（x=$x）必须命中第 $k 字", k, LineHitTest.hit(l, x, 10f))
        }
    }

    @Test
    fun `点每字中心都命中该字（CENTER 对齐含整体偏移，同样不许错位）`() {
        val text = "居中对齐的行命中必须与绘制几何一致这行也要足够长才有偏移效果"
        for (align in listOf(TextAlign.LEFT, TextAlign.CENTER, TextAlign.JUSTIFY)) {
            val l = line(text, align)
            val p = placementOf(l)
            for (k in 0 until p.xs.size) {
                val cx = p.xs[k] + p.advs[k] / 2f
                assertEquals("align=$align 点第 $k 字中心必须命中它", k, LineHitTest.hit(l, cx, 10f))
            }
        }
    }

    @Test
    fun `行尾空区不命中（短行右侧空白点按不吸附到行尾字形）`() {
        val l = line("短行", TextAlign.LEFT)
        val p = placementOf(l)
        assertNull("可见右边界之后必须 miss", LineHitTest.hit(l, p.visibleRight + 5f, 10f))
        assertNull("行首左侧必须 miss", LineHitTest.hit(l, -1f, 10f))
    }

    @Test
    fun `行首缩进与 xLeft 的坐标系未变（点按 x 已由调用方扣除）`() {
        val l = line("缩进行命中也要对得上", TextAlign.LEFT, indent = 40f)
        val p = placementOf(l)
        // 绘制侧 placement 的 x 起点是 0（indent 由 paintX 加），命中侧 xInParagraph 也已扣除
        // indent ⇒ 点第 0 字中心应命中第 0 字。
        assertEquals(0, LineHitTest.hit(l, p.xs[0] + p.advs[0] / 2f, 10f))
    }

    @Test
    fun `行内换面 run 下命中仍与绘制一致`() {
        val text = "parseConfigFile 的 camelCase 命中要准否则点选会偏"
        val runs = listOf(
            orilumn.reader.engine.css.FontRun(0, 15, listOf("monospace"), null, 400, false, true, 0f),
        )
        val l = DrawLine(
            text = text, range = 0 until text.length, yTop = 0,
            yBottom = lineHeightPx(44.4f, 1.5f), alignment = TextAlign.JUSTIFY,
            fontSizePx = 44.4f, lineHeightRatio = 1.5f, tag = "p",
            families = listOf("STSong", "serif"), weight = 400, italic = false, monospace = false,
            letterSpacingEm = 0f, lineWidthPx = 900, fontRuns = runs,
        )
        val p = placementOf(l)
        for (k in 0 until p.xs.size) {
            val cx = p.xs[k] + p.advs[k] / 2f
            assertEquals("换面段第 $k 字中心必须命中它", k, LineHitTest.hit(l, cx, 10f))
        }
    }
}