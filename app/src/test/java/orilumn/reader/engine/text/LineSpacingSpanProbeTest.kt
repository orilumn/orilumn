package orilumn.reader.engine.text

import android.graphics.Paint
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.LineHeightSpan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the semantics of the "paragraph spacing / paragraph-before spacing" LineHeightSpans using
 * the real android.text.StaticLayout: they may only change the height of the line on a paragraph
 * boundary, never leaking into the other lines of a paragraph (otherwise paragraph spacing would turn
 * into intra-paragraph line spacing / line height).
 *
 * Replicates the engine's materialized StaticLayout parameters: setIncludePad(false) +
 * setLineSpacing(0f, mult), matching the engine exactly, to locate whether the problem is in the engine
 * or in the span semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LineSpacingSpanProbeTest {

    /** Hung on the underline of the paragraph's last character: equivalent to p's margin-bottom / space-after. */
    private class BottomPad(private val px: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            fm.descent += px
            fm.bottom += px
        }
    }

    /** Hung on the overline of the paragraph's first character: equivalent to p's margin-top / space-before. */
    private class TopPad(private val px: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, v: Int, fm: Paint.FontMetricsInt) {
            fm.top -= px
            fm.ascent -= px
        }
    }

    private fun layout(text: CharSequence, mutl: Float = 1.5f): StaticLayout {
        val paint = TextPaint()
        paint.textSize = 20f
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, 96)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, mutl)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
            .setIncludePad(false)
            .build()
    }

    private fun lineHeights(sl: StaticLayout): List<Int> =
        (0 until sl.lineCount).map { sl.getLineBottom(it) - sl.getLineTop(it) }

    /** A long paragraph that necessarily wraps into several lines (≥ 4), with no spacing span applied. */
    private val body: String =
        "这是第一段很长很长的中文正文文字，用来确保这一段一定会被折行成多行以便观察行距，" +
            "第二句依然很长，继续拉宽文字宽度，第三句继续补充，好让我们能明确区分段内行与段落边界行。段内行距应当绝对不受段间距影响。"

    // ---- Scenario A: margin-bottom (hung on the paragraph-end char) → should only grow the last line ----
    @Test
    fun marginBottom_onlyGrowsLastLine() {
        val text = SpannableStringBuilder(body)
        val lastChar = text.length - 1
        text.setSpan(BottomPad(40), lastChar, lastChar + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val base = lineHeights(layout(SpannableStringBuilder(body)))
        val grown = lineHeights(layout(text))
        assertEquals("行数应一致", base.size, grown.size)
        val n = base.size
        // Every intra-paragraph line height must stay the same except the last line
        for (i in 0 until n - 1) {
            assertEquals("段内第 $i 行行高被段间距改动！", base[i], grown[i])
        }
        assertEquals("末行应增高 40", base[n - 1] + 40, grown[n - 1])
    }

    // ---- Scenario B: margin-top (hung on the paragraph-start char) → should only grow the first line ----
    @Test
    fun marginTop_onlyGrowsFirstLine() {
        val text = SpannableStringBuilder(body)
        text.setSpan(TopPad(40), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val base = lineHeights(layout(SpannableStringBuilder(body)))
        val grown = lineHeights(layout(text))
        assertEquals(base.size, grown.size)
        assertEquals("首行应增高 40", base[0] + 40, grown[0])
        for (i in 1 until base.size) {
            assertEquals("段内第 $i 行行高被段首距改动！", base[i], grown[i])
        }
    }
}