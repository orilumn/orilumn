package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.lineHeightPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自建断行器（生产唯一断行实现）的回归锁。
 *
 * ## 历史
 *
 * 本类前身是「自建 vs Skia 断行器逐行对照」锁：那一层比的是**两台引擎**的断点，
 * 而 Skia 那一侧（`SkiaParagraphBreaker`）已随回退阀一起删除 ⇒ 对照臂不存在，
 * 相关锁（parity / 公平性统计 / 已知偏差钉值）随之删除。留下的都是**只看自建自己**的不变量。
 *
 * ## 四层判据
 *
 *  1. **结构不变量**（全网格无条件）：区间不越界 / 不含 `\n` / 不重叠 / 覆盖全部非 `\n` 字符 / 行高合规。
 *     这是最硬的一层 —— **丢字符**是绝不能有的 bug，且与禁则取舍无关。
 *  2. **标点挤压单调性**：挤压只减不增 ⇒ 行数单调不增，且网格里必须真的产出省行的格子（否则锁自证通过）。
 *  3. **挤压触发口径**：溢出行落在西文词内时才挤，且只挤到刚够。
 *  4. **贪心语义**（§T1b）：行尾空白悬挂、不折空白、行首空白照算、硬换行保空行。
 *
 * ## 生产不变式
 *
 * `widthPx ≥ ceil(minContentWidth)` —— 版心永远装得下最宽的不可断单元，R1 core 是兜底而非常规路径。
 *
 * ## 字体
 *
 * 依赖真实字形（Georgia / STSong / serif 回退），无回退字体时数值不成立。
 */
class InhouseParagraphBreakerTest {

    private val fam = listOf("Georgia", "STSong", "serif")
    private val size = 20f
    private val lh = 1.5f

    private fun mineLines(text: String, w: Int, ls: Float = 0f) = InhouseParagraphBreaker(ls)
        .breakLines(text, size, lh, w, TextAlign.LEFT, "p", fam, 400, false, false)

    /** **未挤口径**（`punctuationSqueezeMaxEm = 0f`）—— 用来隔离「宽度模型」这一个变量。 */
    private fun mineLinesNoSqueeze(text: String, w: Int, ls: Float = 0f) =
        InhouseParagraphBreaker(ls, 0f, punctuationSqueezeMaxEm = 0f)
            .breakLines(text, size, lh, w, TextAlign.LEFT, "p", fam, 400, false, false)

    // ---- 网格（锁的覆盖面；新增样本/版心必须先跑探针核实再登记） ----

    private val gridWidths = intArrayOf(60, 100, 120, 142, 150, 160, 180, 200, 240, 280, 320, 360, 411, 600, 800, 1024)
    private val gridSpacings = floatArrayOf(0f, 0.01f, 0.02f, 0.05f)

    /** 覆盖全部难例：中英混排、破折号、书名号/括号、URL、`/ ]` 接缝、换行、超长不可断串。 */
    private val gridSamples = listOf(
        "他说：“好。” …… “……”——（1）《书名》！？；：",
        "参见 https://a.example.com/x 的说明。",
        "ANSI/NISO 与 [1](Rea) 混排时的断点。",
        "and/or、foo/bar、a]b 三种 ASCII 接缝。",
        "第三章 目的——奇袭 与 hyphen-ated 的接缝。",
        "字串 with 空格 and 或者「全角引号」混排。",
        "混合 mixed 内容 with 破折号——与空格。more text here",
        "他说：“好。” …… “……”——（1）《书名》！？；：\n换行也来一段 with ASCII and/or 与 https://x.example/y/a。",
        "短句。",
        "超长不可断串 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    )

    // ---- 基元 ----

    /** 生产不变式的地板：`ceil(minContentWidth)`（真字形宽口径 = 生产口径）。 */
    private fun floorPx(text: String, ls: Float = 0f): Int =
        Math.ceil(
            InhouseParagraphBreaker(ls).minContentWidth(text, size, fam, 400, false, false).toDouble(),
        ).toInt()

    /**
     * 结构不变量：区间不越界 / 非空（**除非是落在硬换行上的空行**）/ 不与上行重叠 / 不含 `\n` /
     * 并起来覆盖全部非 `\n` 字符 / 行高合规。
     * **这一层与禁则取舍无关**，任何格都必须成立 —— 丢字符 / 区间含 `\n` 是绝不能有的 bug。
     *
     * **零宽行（空行）是合法的**：`a\n\nb` 的中间那行是空区间，它是一个**真实行盒**
     * （占一整行高；CSS `pre-wrap` / `<br><br>` 都如此，仓内口径见 [WhiteSpaceBreak.breakLeafLines]
     * 的 `else if (i < n)` 分支）。但「空区间」不能成为丢字符的遮羞布 ⇒ 判据收紧为：
     * **空区间必须落在硬换行处**（`text[r.first] == '\n'`），落在别处的空洞仍然判红。
     */
    private fun assertWellFormed(text: String, w: Int, ls: Float = 0f) {
        val n = text.length
        val seen = BooleanArray(n)
        var prevEnd = 0
        mineLines(text, w, ls).forEachIndexed { k, l ->
            val r = l.range
            assertTrue("第 ${k + 1} 行区间越界（r=$r n=$n text=«$text» W=$w ls=$ls）", r.first >= 0 && r.last < n)
            if (r.isEmpty()) {
                assertTrue(
                    "第 ${k + 1} 行是空区间却不落在硬换行处（r=$r text=«$text» W=$w ls=$ls）",
                    r.first in 0 until n && text[r.first] == '\n',
                )
            }
            assertTrue("第 ${k + 1} 行与上一行重叠（r=$r prevEnd=$prevEnd）", r.first >= prevEnd)
            prevEnd = r.last + 1
            assertEquals("第 ${k + 1} 行行高（text=«$text» W=$w）", lineHeightPx(size, lh), l.heightPx)
            for (i in r) {
                assertTrue("第 ${k + 1} 行区间含 \\n（r=$r text=«$text» W=$w）", text[i] != '\n')
                seen[i] = true
            }
        }
        val lost = (0 until n).filter { !seen[it] && text[it] != '\n' }
        assertTrue("自建侧丢了字符 $lost（text=«$text» W=$w ls=$ls）", lost.isEmpty())
    }

    // ---- 第 1 层：结构不变量（全网格） ----

    @Test
    fun `structural invariants hold on every grid cell`() {
        var cells = 0
        for (t in gridSamples) for (ls in gridSpacings) for (w in gridWidths) {
            assertWellFormed(t, w, ls)
            cells++
        }
        assertEquals("网格覆盖面（新增样本/版心记得同步改这里）", 640, cells)
    }

    // ---- 第 2 层：标点挤压单调性 ----

    /**
     * **挤压只减不增** ⇒ 行数单调不增（`mine(挤).size ≤ mine(未挤).size`，逐格成立）。
     *
     * 挤压的代数是 `natural' = natural − S`（`S ≥ 0`）⇒ 断行器预留的宽只会变小 ⇒ 行数只会变少或不变。
     * 反向一旦发生，就说明额度算出了**负**的（或某处把 `S` 加回去了），那不是「排得松一点」，
     * 是**溢出**（`NoLineExceedsContentWidthTest` 会随后红，但那条红得晚且信息难读）。
     */
    @Test
    fun `punctuation squeeze never costs a line`() {
        var cells = 0
        var strictlyFewer = 0
        for (t in gridSamples) for (ls in gridSpacings) for (w in gridWidths) {
            val squeezed = mineLines(t, w, ls).size
            val plain = mineLinesNoSqueeze(t, w, ls).size
            assertTrue(
                "挤压只减不增，但这一格行数变多了（text=«$t» W=$w ls=$ls 挤=$squeezed 未挤=$plain）。" +
                    "额度算出了负值，或有地方把 S 加回了宽度 —— 那是溢出方向的 bug。",
                squeezed <= plain,
            )
            if (squeezed < plain) strictlyFewer++
            cells++
        }
        println("挤压单调性：cells=$cells 省行格数=$strictlyFewer")
        assertTrue(
            "本锁的前提：网格必须真的产出省行的格子（实测 $strictlyFewer 格）。" +
                "若为 0，本锁退化成自证通过（挤压恒等于没挤）—— 那是接线断了，不是通过。",
            strictlyFewer > 0,
        )
    }

    // ---- 第 3 层：挤压触发口径 ----

    /**
     * **溢出行落在西文词内时，挤压立刻触发以保住整词。**
     *
     * 语料 `"混合 mixed 内容 with 破折号——与空格。more text here"` @280：
     * 未挤口径下第 2 行断在 `19..35`，而 `36..39` 装的是 `。mor` —— **「more」被切开了**。
     * 生产口径下 `。` 被挤到刚好让 `more` 整词进来（`19..39`）。
     *
     * ⚠ 顺带钉住一条口径：**触发判据是「溢出点两侧都是西文」**，不是「这一行有标点」。
     * 下面的 `T2d` 那把锁里 `。more` 的 `。` 与 `m` 不是词内接缝 ⇒ 不触发，两条并排把边界划死。
     */
    @Test
    fun `word-internal overflow squeezes just enough to keep the word whole`() {
        val t = "混合 mixed 内容 with 破折号——与空格。more text here"
        val noSq = mineLinesNoSqueeze(t, 280, 0.05f)
        assertEquals("未挤口径 3 行", 3, noSq.size)
        assertEquals(
            "未挤口径把 more 切开了（`36..39` = `。mor`）",
            listOf("0..18", "19..35", "36..39"),
            noSq.map { it.range.toString() },
        )
        val prod = mineLines(t, 280, 0.05f)
        assertEquals("生产口径 2 行", 2, prod.size)
        assertEquals(
            "生产口径把 more 整词收进第 2 行",
            listOf("0..18", "19..39"),
            prod.map { it.range.toString() },
        )
        assertEquals("第 1 行不挤（溢出点不在词内）", 0f, prod[0].squeezeRatio, 1e-6f)
        assertTrue(
            "第 2 行必须挤一点（保住 more）：实测 ${prod[1].squeezeRatio}",
            prod[1].squeezeRatio > 0f,
        )
        assertTrue("比例必须 ≤ 1", prod[1].squeezeRatio <= 1f)
    }

    /**
     * **溢出点不在西文词内 ⇒ 一个字都不挤**（用户口径：「没必要时挤压什么」）。
     *
     * 这一格的溢出点落在 `、`、`种`、`接`、`缝`、`。` 这些非西文字符上 ⇒
     * `ratioToKeepWordIntact` 返回 0 ⇒ **生产口径与未挤口径逐项相同**。
     * 哪天谁又把挤压无条件打开，它会当场红。
     */
    @Test
    fun `no squeeze when the overflow point is not inside a western word`() {
        val t = "and/or、foo/bar、a]b 三种 ASCII 接缝。"
        assertEquals("4 行", 4, mineLinesNoSqueeze(t, 120, 0.02f).size)
        assertEquals(
            "具体区间（未挤口径）",
            listOf("0..6", "7..16", "17..27", "28..30"),
            mineLinesNoSqueeze(t, 120, 0.02f).map { it.range.toString() },
        )
        assertEquals(
            "生产口径与未挤口径同值（无西文词可保 ⇒ 挤压不触发）",
            mineLinesNoSqueeze(t, 120, 0.02f).map { it.range.toString() },
            mineLines(t, 120, 0.02f).map { it.range.toString() },
        )
    }

    // ---- 生产不变式之外：R1 紧急断行 ----

    /**
     * 版心窄于 min-content 时（生产不变量之外，自建列宽永远 ≥ min-content）：
     * R1 core 贪心切 —— **宁可切也不溢出**，且不丢字符（`" a b"` 里断点处的空格也保留）。
     */
    @Test
    fun `below min-content the breaker splits characters instead of overflowing`() {
        val narrow = listOf(
            Triple(" a b", 8, 0f),
            Triple(" a b", 10, 0f),
            Triple("ab\ncd", 10, 0f),
        )
        for ((text, w, ls) in narrow) {
            assertTrue(
                "用例前提失效：W=$w 应窄于 floor=${floorPx(text, ls)}（text=«$text»）",
                w < floorPx(text, ls),
            )
            assertWellFormed(text, w, ls)
        }
        assertEquals("逐字切开、不丢空格", listOf("0..0", "1..1", "2..2", "3..3"), mineLines(" a b", 10).map { it.range.toString() })
    }

    // ---- 四条贪心语义（§T1b） ----

    @Test
    fun `greedy semantics 1 - trailing whitespace hangs but stays inside the line range`() {
        // ⚠ 用例必须**真正溢出**：整串放得下时「单行 [0,n)」是平凡结果，测不出悬挂语义（§T1b 踩过）。
        val t = "一二三四五六七八九十   "
        for (w in intArrayOf(100, 101, 103, 120, 220)) {
            assertTrue("W=$w 应在生产不变式之上", w >= floorPx(t))
            assertWellFormed(t, w)
        }
        // 直接断言语义本身：末行把 3 个尾随空格含进行区间
        val lines = mineLines(t, 100)
        assertEquals(2, lines.size)
        assertEquals("末行应把 3 个尾随空格含进去", t.length - 1, lines[1].range.last)
    }

    @Test
    fun `greedy semantics 1b - trailing whitespace in a middle line also hangs`() {
        // 关键形状：**中间行**（非末行）的行尾空白同样悬挂 —— 早期只测末行，漏了这一格。
        val t = "aaaa bbbb cccc dddd eeee ffff gggg hhhh   "
        for (w in intArrayOf(70, 90, 110, 130, 160)) {
            assertWellFormed(t, w)
            assertEquals("尾随空格必须全部保留（W=$w）", t.length - 1, mineLines(t, w).last().range.last)
        }
    }

    @Test
    fun `greedy semantics 2 - no whitespace collapsing and no line-start trimming`() {
        val t = "a  b  c   d"
        for (w in intArrayOf(20, 26, 31, 40, 60)) assertWellFormed(t, w)
        // 行首空格照算
        val s = " a b"
        for (w in intArrayOf(14, 18, 22, 26, 31)) assertWellFormed(s, w)
        // 自然宽口径：`"a  b"` 必须比 `"a b"` 恰好多一个空格宽（无折叠）
        val m = SkiaRunMeasurer()
        fun w0(x: String) = m.advances(x, size, 0f, "p", fam, 400, false, false).sum()
        assertEquals(
            "双空格串应恰好多一个空格宽（无折叠）",
            w0(" ").toDouble(), (w0("a  b") - w0("a b")).toDouble(), 1e-3,
        )
    }

    /**
     * 硬换行断行 + **空行保留** + 末尾换行不产幻影行。
     *
     * ⚠ 本锁早先把「`a\n\nb` 是 **2** 行」当成规格钉住，那正是用户报的
     * 「`pre code` 块里空行全部消失」（Q16）。规格已改为**以 CSS 为准**：空行是真实行盒。
     * 「以 CSS 为准」不需要外部裁决 —— 仓内**早就有正确的那份实现**：
     * [WhiteSpaceBreak.breakLeafLines] 不折行分支（`PRE`/`NOWRAP`）一直产零宽空行，
     * 只有 `pre` 被降级成 `PRE_WRAP`（`wraps()==true`）后走断行器的那条在丢。
     * ⇒ 本锁钉的是**两个 `white-space` 分支行数一致**，不是某一条孤立的期望值。
     */
    @Test
    fun `greedy semantics 3 - hard newline breaks keep blank lines and no phantom trailing line`() {
        val cases = listOf("a\n\nb", "a\nb", "a\n", "\na", "ab\ncd", "\n", "\n\n\n", "a\n\n\nb", " \n a")
        for (t in cases) {
            assertWellFormedOrNewlineOnly(t, 1000) // 宽到放得下：验「硬断 + 空行保留」
            val floor = floorPx(t).coerceAtLeast(1)
            for (w in intArrayOf(floor, floor + 1, floor + 7, floor + 40)) {
                assertWellFormedOrNewlineOnly(t, w)
            }
        }
        // 空行 = 连续换行之间的零宽行。`2 until 2` 即空区间（首字符位与末字符位重合）。
        assertEquals("`a\\n\\nb` 的空行必须保留（3 行）", listOf(0..0, 2 until 2, 3..3), mineLines("a\n\nb", 1000).map { it.range })
        assertEquals(3, mineLines("a\n\nb", 1000).size)
        // 末尾**单个** `'\n'` 不产幻影行（EPUB 源码惯用 `<pre>…\n</pre>`）；末尾**两个** `'\n'` 时
        // 靠前那个终止的是一个真实空行（CSS 口径），必须保留。
        assertEquals(1, mineLines("a\n", 1000).size)
        assertEquals(listOf(0..0, 2 until 2), mineLines("a\n\n", 1000).map { it.range })
    }

    /**
     * 结构不变量的例外口：**整段只有换行**（`"\n"`、`"\n\n\n"`、`"\n \n"`）时，
     * 断行器退到 `BrokenLine(0 until n)` 兜底，区间含 `\n`。
     * 真实文本不会出现这种段，此处只要求不崩、不丢可绘制字符以外的形状。
     */
    private fun assertWellFormedOrNewlineOnly(text: String, w: Int) {
        if (text.any { it != '\n' }) assertWellFormed(text, w) else assertTrue(mineLines(text, w).isNotEmpty())
    }
}
