package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.HIDDEN_NONE
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout

/**
 * S3 回退锁：**回退侧（Skia）必须与接线前逐值一致**。
 *
 * ## 默认变体改成 on 之后，这条锁守的是什么
 *
 * 接线时默认是 Skia，于是有「默认侧 = 生产 = 与接线前逐字节同构」这条性质可守。
 * 现在（2026-10-01）默认改成自建断行器，**那条性质整体消失** ——
 * 默认侧换引擎了，本来就不该与接线前一致。
 *
 * 换成守**回退侧**：`ab="inhouseBreak=0"` 时走的那条路，必须与接线前的裸
 * `SkiaParagraphBreaker` 逐值相同。理由与之前一样、只是换了主语 ——
 * 回退阀的全部价值在于「出事时关掉就回到已知状态」；若回退侧不是逐值等于
 * 接线前，那么真机上一关开关就落到一个**从未被验证过的第三种行为**上，
 * 「关掉即可」这句话是假的。
 *
 * ## 为什么必须是锁而不是论证
 *
 * 本仓已有三次同类教训，都是「绿灯来自错误原因」：量 `table` 盒宽（被流撑满、与列宽无关）、
 * 在测试里重写生产接线（改 `:177` 照样绿）、语料用汉字换面段（advance 在任何字族都是 1em，
 * 丢掉 `fontRuns` 测不出来）。推理在这件事上不可信。
 *
 * 判据：**同一份 HTML × 多个版心**，回退接线（`heavyPathBreaker`，`inhouseBreak=0`）
 * 与接线前的裸 `SkiaParagraphBreaker` 产出的断行区间、绘制行、表格列宽**逐值相同**。
 */
class DefaultSideIsUnchangedTest {

    /**
     * 真书正文（含本仓反复用来复现的那段原句）+ Latin/URL 混排 + **行内换面**。
     *
     * `font-family` 不同的 `<span>` 是**必需**的，不是凑数：第一版语料里没有它，
     * 于是 `fontRuns` 全为空，变异「12 参转发丢掉 fontRuns」**测不出来**（锁照样绿）。
     * 而 `fontRuns` 恰恰是行内换面段量画一致的唯一载体 —— 漏掉它，这条锁就正好
     * 漏掉了它最该守的那一段。故下面用 [assert 覆盖行内换面] 把这个前提钉死。
     */
    private val bodyHtml = """
        <html><body>
        <p class="calibre1">都用到这么厉害的科技了，事情肯定不会如字面上写的那样简单。</p>
        <p class="calibre1">这话听着不对啊。“老科技。”老管家说道，“它是打开族谱的钥匙。”</p>
        <p>Chapter twelve: the registrar of the clan house was waiting, and the
           https://www.w3.org/TR/2017/REC-html52-20171214/ URL went on for a while.</p>
        <p style="text-indent: 2em;">带缩进的段落，用来覆盖 firstLineIndentPx 这条路径。</p>
        <p>前面这段用正文字族<span style="font-family: monospace; font-weight: 700;">MonospaceBoldFragment</span>后面继续接足够长的文字以便折行。</p>
        <p>短一点但同样带换面<span style="font-family: serif; font-style: italic;">ItalicSerifFragment</span>收尾。</p>
        </body></html>
    """.trimIndent()

    /** 真书形态的 auto 分列表：Latin/URL/CJK 三种单元格，正是度量差异最可能出现的地方。 */
    private val tableHtml = """
        <html><body><div class="tbl"><div><table>
        <tr><td>Chapter twelve: the registrar of the clan house</td><td>中文列</td></tr>
        <tr><td>https://www.w3.org/TR/2017/REC-html52-20171214/</td><td>短</td></tr>
        </table></div></div></body></html>
    """.trimIndent()

    private val ua = "p { font-family: STSong, serif; } " +
        "table { table-layout: auto; border-collapse: collapse; border-spacing: 0; }" +
        ".tbl table td { padding: .5em; border: 1px solid #c0c0c0; }"

    private fun fingerprint(html: String, width: Int, breaker: orilumn.reader.engine.laying.ParagraphBreaker): String {
        val root = HtmlTreeConverter().convert(html)!!
        val engine = StyleComputer(44.4f, LightCssParser().parse(ua), emptyList())
        val styles = engine.compute(root)
        val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
        val result = BoxLayouter(44.4f, breaker).layoutBoxes(root, width, styles, classify)

        val draws = ArrayList<String>()
        var runLines = 0
        for ((idx, dl) in DrawLineBuilder.build(result, styles, classify, HIDDEN_NONE, 0f)) {
            draws.add("$idx|${dl.text.substring(dl.range)}|${dl.range}|${dl.lineWidthPx}|${dl.alignment}")
            if (dl.fontRuns.isNotEmpty()) runLines++
        }
        val cols = ArrayList<String>()
        fun walk(b: LayoutBox) {
            b.table?.let { cols.add("xs=${it.columnXs.joinToString(",")} ws=${it.columnWidths.joinToString(",")}") }
            for (c in b.childBoxes) walk(c)
        }
        for (b in result.boxes) walk(b)
        return draws.joinToString("\n") + "\n--COLS--\n" + cols.joinToString("\n") + "\n--RUNLINES--\n$runLines"
    }

    /**
     * 前提守卫：本语料必须**对 `fontRuns` 敏感**——丢掉换面 run 必须真的改变断行结果。
     *
     * ## 为什么不能只断言「有非空 fontRuns」（第二版就这样写过，还是测不出来）
     *
     * 第一版语料的换面 `<span>` 全是**汉字**，而汉字在任何字族里 advance 都是 1em，
     * 于是丢掉 `fontRuns` 后每段宽度逐值不变，断行一模一样 ⇒ 变异照样绿。
     * 这和同日早些时候 `preferredWidth` 的 CJK 比值恰好 1.000 是**同一个巧合**，
     * 在本轮里栽了第二次。所以断言必须落在**效果**上，而不是落在「字段非空」上。
     */
    @Test
    fun `语料对 fontRuns 敏感——否则这把锁验的是空气`() {
        AbSwitch.resetForTest()
        try {
            val before = SkiaParagraphBreaker(0f)
            val stripped = fontRunsStripped(before)
            val widths = intArrayOf(220, 320, 400, 560, 700, 900, 1200, 1600, 1740)
            val sensitive = widths.any { w -> fingerprint(bodyHtml, w, before) != fingerprint(bodyHtml, w, stripped) }
            assertTrue(
                "语料必须对 fontRuns 敏感：所有版心下丢掉换面 run 的断行结果都与原样相同，" +
                    "说明换面段落全是汉字（advance 在任何字族都是 1em）。" +
                    "这种语料会让「转发时丢 fontRuns」这类变异测不出来 —— 请把 span 内容换成 Latin。",
                sensitive,
            )
        } finally {
            AbSwitch.resetForTest()
        }
    }

    /** 与 [autoColumnMeasurePinnedToSkia] 同形的转发包装，但**故意丢掉 `fontRuns`**，用作敏感度基准。 */
    private fun fontRunsStripped(inner: orilumn.reader.engine.laying.ParagraphBreaker) =
        object : orilumn.reader.engine.laying.ParagraphBreaker {
            override fun preferredWidth(
                text: CharSequence, fontSizePx: Float, families: List<String>,
                weight: Int, italic: Boolean, monospace: Boolean, fontRuns: List<orilumn.reader.engine.css.FontRun>,
            ) = inner.preferredWidth(text, fontSizePx, families, weight, italic, monospace, fontRuns)

            override fun minContentWidth(
                text: CharSequence, fontSizePx: Float, families: List<String>,
                weight: Int, italic: Boolean, monospace: Boolean, fontRuns: List<orilumn.reader.engine.css.FontRun>,
            ) = inner.minContentWidth(text, fontSizePx, families, weight, italic, monospace, fontRuns)

            override fun breakLines(
                text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
                alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>,
                weight: Int, italic: Boolean, monospace: Boolean,
            ) = inner.breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace)

            override fun breakLines(
                text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
                alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>,
                weight: Int, italic: Boolean, monospace: Boolean, firstLineIndentPx: Float,
            ) = inner.breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, firstLineIndentPx)

            override fun breakLines(
                text: CharSequence, fontSizePx: Float, lineHeightRatio: Float, widthPx: Int,
                alignment: orilumn.reader.engine.css.TextAlign, tag: String?, families: List<String>,
                weight: Int, italic: Boolean, monospace: Boolean, firstLineIndentPx: Float,
                fontRuns: List<orilumn.reader.engine.css.FontRun>, baselineShifts: List<orilumn.reader.engine.laying.BaselineShift>,
            ) = inner.breakLines(text, fontSizePx, lineHeightRatio, widthPx, alignment, tag, families, weight, italic, monospace, firstLineIndentPx, emptyList(), baselineShifts)
        }

    @Test
    fun `回退侧：inhouseBreak=0 时与接线前的裸 Skia 断行器逐值一致`() {
        AbSwitch.resetForTest()
        try {
            // 前提：默认变体必须是自建，否则「回退」这个词指的不是本测试想测的那条路。
            assertEquals(
                "默认变体必须是自建（若已改回 Skia，本类应改名并改判据）",
                InhouseParagraphBreaker::class.java,
                bodyParagraphBreaker(0f)::class.java,
            )
            AbSwitch.apply("inhouseBreak=0")
            assertEquals(
                "回退侧不可达则本类测的是空气：inhouseBreak=0 必须真的切到 Skia",
                SkiaParagraphBreaker::class.java,
                bodyParagraphBreaker(0f)::class.java,
            )
            val before = SkiaParagraphBreaker(0f)
            val after = heavyPathBreaker(0f)
            for (html in listOf(bodyHtml, tableHtml)) {
                for (w in intArrayOf(220, 320, 400, 560, 700, 900, 1200, 1600, 1740)) {
                    assertEquals(
                        "版心=$w 回退侧必须与接线前逐值相同（断行区间/绘制行/表格列宽全等）。" +
                            "不等则真机上一关开关就落到一个从未验证过的行为上，「关掉即可」不成立。",
                        fingerprint(html, w, before),
                        fingerprint(html, w, after),
                    )
                }
            }
        } finally {
            AbSwitch.resetForTest()
        }
    }
}
