package orilumn.reader.engine.skia

import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `text-align: justify` 拉伸通路回归（渲染层）。
 *
 * ## 背景：原实现为何静默失效
 *
 * 旧实现在行尾追加硬 `'\n'`（`LineWindowDrawer.appendTrailingNewline`），企图把本行伪装成
 * 「双行段的首行」骗 Skia 拉伸。**Skia 的 `kJustify` 只拉伸软断行产生的行**；追加硬 `'\n'`
 * 后本行成了硬换行结尾的行，于是永远不被拉伸。实测：中部行追加前后首行宽逐值相同。
 *
 * 硬 `'\n'` 之后再接文本也无效（行已硬断）；`\u000B` 同样无效。**行终止方式才是决定因素。**
 *
 * ## 本类锁住的两条可行通路（探针实测得出）
 *
 *  1. `maxLinesCount = 1` + **整段文本**（`justifyViaMaxLinesOne`）：不切子串，整段交给 Skia
 *     但只取首行，首行以软断行收尾 → 被拉伸到版心。
 *  2. 首行子串 + 一个必然软断的后续 token（`justifyViaSoftBreakSuffix`）：等效但需拼串。
 *
 * 通路 1 是生产要用的那条：单行、无子串边界、不碰 runs 坐标。
 *
 * ## 关键不变量
 *
 * - **自然宽基准**：LEFT 对照行的 `lineMetrics[0].width` 即该行自然宽。JUSTIFY 生效时
 *   首行宽必须**严格大于**自然宽且**等于**版心；不生效时逐值等于自然宽。这个基准必须取自
 *   LEFT 整形而非推算——否则测试会在"两边都没拉伸"时也绿（此前 R1 探针栽过同样的坑）。
 * - **段末行不拉**：CSS 规定段末行左对齐。末行自然宽 < 版心时**必须保持不拉**。
 * - **亚像素余量**沿用仓库既有口径（`SkiaParagraphBreakerTest` 的 `+1f`）。
 */
class JustifyStretchTest {

    private val collection = FontCollection().setDefaultFontManager(FontMgr.default)

    /** 真机字体栈：fallback 字体下断行点不同，本类结论不成立（与 R1 回归同要求）。 */
    private val families = listOf("STSong", "serif")

    /** 版心 + 字号取真机值（contentW≈1600, bodyPx≈44.4）。 */
    private val fs = 44.4f
    private val ratio = 1.2f

    private fun shape(
        text: String,
        width: Float,
        alignment: Alignment,
        maxLines: Int = Int.MAX_VALUE,
    ): org.jetbrains.skia.paragraph.Paragraph {
        val style = ParagraphStyle().apply {
            this.alignment = alignment
            maxLinesCount = maxLines
            textStyle = TextStyle().setFontSize(fs)
        }
        return ParagraphBuilder(style, collection).addText(text).build().layout(width)
    }

    /** 该文本在版心下的自然宽（LEFT 整形，Skia 不拉伸）。 */
    private fun naturalWidth(text: String, width: Float): Float =
        shape(text, width, Alignment.LEFT).lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f

    private fun firstLineRange(text: String, width: Float): IntRange {
        val breaker = SkiaParagraphBreaker(letterSpacingEm = 0f)
        val lines = breaker.breakLines(
            text, fs, ratio, width.toInt(),
            orilumn.reader.engine.css.TextAlign.JUSTIFY, "p", families, 400, false, false,
        )
        val r = lines.first().range
        return r.first..r.last
    }

    private fun cjk(n: Int) = "中文两端对齐拉伸通路验证".repeat(n / 11 + 1).take(n)

    // ---- 通路 1：maxLinesCount=1 + 整段（生产要用的那条）----

    @Test
    fun justifyViaMaxLinesOneStretchesToContainer() {
        val width = 1600f
        val text = cjk(80)
        val r = firstLineRange(text, width)
        val nat = naturalWidth(text.substring(r.first, r.last + 1), width)
        assertTrue("前提：首行自然宽应小于版心（slack 存在）", nat > 1f && nat < width)

        val p = shape(text, width, Alignment.JUSTIFY, maxLines = 1)
        val w0 = p.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        val n = p.lineNumber
        p.close()

        assertEquals("maxLines=1 只应产生一行", 1, n)
        assertTrue("首行必须被拉伸（$w0 > 自然宽 $nat）", w0 > nat + 1f)
        assertTrue("首行必须铺满版心（$w0 vs $width）", kotlin.math.abs(w0 - width) <= 1f)
    }

    @Test
    fun justifyViaMaxLinesOneLeavesLastLineUnstretched() {
        val width = 1600f
        // 单行放得下的短段：它既是首行也是末行，CSS 要求不拉。
        val short = cjk(10)
        val pShort = shape(short, width, Alignment.JUSTIFY, maxLines = 1)
        val wShort = pShort.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        pShort.close()
        val natShort = naturalWidth(short, width)
        assertTrue("前提：短段自然宽 < 版心", natShort < width)
        assertEquals("段末行不得被拉伸（$wShort vs 自然宽 $natShort）", natShort, wShort, 0.01f)
    }

    @Test
    fun justifyViaMaxLinesOneMatchesWholeParagraphJustify() {
        // 通路 1 的首行宽必须与「整段软断行」的首行宽一致——否则画出来的右缘与
        // 量出来的版心对不上（分页/命中依赖后者）。
        val width = 1600f
        val text = cjk(80)
        val pOne = shape(text, width, Alignment.JUSTIFY, maxLines = 1)
        val wOne = pOne.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        pOne.close()
        val pFull = shape(text, width, Alignment.JUSTIFY)
        val wFull = pFull.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        pFull.close()
        assertEquals("maxLines=1 与整段断行的首行宽必须相同", wFull, wOne, 0.01f)
    }

    @Test
    fun maxLinesOneDoesNotChangeLeftAlignment() {
        // 零行为保证：LEFT 下 maxLines=1 与不限行数的首行宽相同。
        val width = 1600f
        val text = cjk(80)
        val p1 = shape(text, width, Alignment.LEFT, maxLines = 1)
        val w1 = p1.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        p1.close()
        val p2 = shape(text, width, Alignment.LEFT)
        val w2 = p2.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        p2.close()
        assertEquals("LEFT 路径必须逐值不变", w2, w1, 0.001f)
    }

    // ---- 通路 2：首行子串 + 软断后缀（记录等效通路，防未来重写丢掉这个事实）----

    @Test
    fun justifyViaSoftBreakSuffixStretchesToContainer() {
        val width = 1600f
        val text = cjk(80)
        val r = firstLineRange(text, width)
        val l0 = text.substring(r.first, r.last + 1)
        val nat = naturalWidth(l0, width)
        val p = shape(l0 + "x", width, Alignment.JUSTIFY)
        val w0 = p.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        p.close()
        assertTrue("软断后缀应触发拉伸（$w0 > $nat）", w0 > nat + 1f)
        assertTrue("应铺满版心", kotlin.math.abs(w0 - width) <= 1f)
    }

    @Test
    fun hardNewlineSuffixNeverStretches() {
        // 反向锁定：硬 '\n'（旧实现的路）无论后面接不接文本都不拉伸。生产若哪天换回
        // 追加 '\n' 的写法，本例会红。
        val width = 1600f
        val text = cjk(80)
        val r = firstLineRange(text, width)
        val l0 = text.substring(r.first, r.last + 1)
        val nat = naturalWidth(l0, width)
        for (suffix in listOf("\n", "\n后续文本", "\u000B")) {
            val p = shape(l0 + suffix, width, Alignment.JUSTIFY)
            val w0 = p.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
            p.close()
            assertEquals("硬断后缀 '$suffix' 不应拉伸", nat, w0, 0.01f)
        }
    }

    // ---- 跨字号/版心稳健性 ----

    @Test
    fun justifyStretchesAcrossFontSizesAndWidths() {
        for (f in listOf(16f, 24f, 44.4f)) {
            for (width in listOf(600f, 1000f, 1600f)) {
                val style = ParagraphStyle().apply {
                    alignment = Alignment.JUSTIFY
                    maxLinesCount = 1
                    textStyle = TextStyle().setFontSize(f)
                }
                val text = "中文两端对齐拉伸通路验证".repeat(12)
                val pL = ParagraphBuilder(
                    ParagraphStyle().apply {
                        alignment = Alignment.LEFT
                        textStyle = TextStyle().setFontSize(f)
                    },
                    collection,
                ).addText(text).build().layout(width)
                val nat = pL.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
                pL.close()
                if (nat >= width) continue // 该行本来就满行，无 slack 可拉，跳过

                val p = ParagraphBuilder(style, collection).addText(text).build().layout(width)
                val w0 = p.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
                p.close()
                assertTrue("fs=$f width=$width 应拉伸（$w0 > $nat）", w0 > nat + 1f)
                assertTrue("fs=$f width=$width 应铺满（$w0 vs $width）", kotlin.math.abs(w0 - width) <= 1f)
            }
        }
    }

    /**
     * 与 R1 的交互：单行缩进段落在 R1 修完后不再折行。若此时叠加 justify，
     * 首行宽会变成版心宽——**不得**溢出到版心之外。
     */
    @Test
    fun justifyDoesNotOverflowWithFirstLineIndent() {
        val width = 1600f
        val indent = 2f * fs
        val text = cjk(80)
        val style = ParagraphStyle().apply {
            alignment = Alignment.JUSTIFY
            maxLinesCount = 1
            textStyle = TextStyle().setFontSize(fs)
            textIndent = org.jetbrains.skia.paragraph.TextIndent(indent, 0f)
        }
        val p = ParagraphBuilder(style, collection).addText(text).build().layout(width)
        val w0 = p.lineMetrics.getOrNull(0)?.width?.toFloat() ?: -1f
        p.close()
        assertTrue("缩进 + 拉伸后首行宽必须 ≤ 版心（indent=$indent w0=$w0 width=$width）", w0 <= width + 1f)
    }
}
