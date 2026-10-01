package orilumn.reader.engine.skia

import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.lineHeightPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自建断行器 vs Skia 断行器**逐行对照**锁（S3 六把锁之第 1 把）。
 *
 * ## 为什么不能是一把「全等锁」
 *
 * 最初想写成「所有用例逐行区间全等」，实测直接红：640 格（10 文本 × 16 版心 × 4 字距）里 **99 格不同**。
 * 逐格核对后确认这 99 格**全部有已登记的成因**，不是 bug：
 *
 *  - **族B1 偏严**（T2e 登记）：接缝右字在 `noBreakBefore` 而左字非宽字时本表禁断、Skia 放行。
 *    典型 `号|—`、`的|—`、`”|—`。修它要引入 pair 规则层（三表结构表达不了 `B2 × B2`），
 *    放行会制造**假阴性**，按「假阴性必须为 0」优先不修。
 *  - **T2d 代价①**（该节登记）：`]|字母` 本表放行、Skia 按 LB30 `CP × (AL|HL|NU)` 禁断。
 *  - **R1 紧急断行**：版心**窄于 min-content** 时（生产不变量之外）Skia 选「整词溢出」、
 *    自建走 R1 core 贪心切，切点本就不同。
 *
 * 两种族的代价方向**不固定**：族B1 让我们行数变多（最差单格 1.50x），
 * T2d 代价① 让我们行数变少（实测 5 行 → 4 行）。所以也不能反向断言行数单调。
 *
 * 因此本锁分四层，各锁各的判据：
 *  1. **结构不变量**（全网格无条件）：区间不越界 / 不含 `\n` / 不重叠 / 覆盖全部非 `\n` 字符 / 行高合规。
 *     这是最硬的一层 —— **丢字符**是绝不能有的 bug，且与禁则取舍无关。
 *  2. **精选格逐行全等**（[parity holds on the clean sample set]）：只放实测 100% parity 的文本。
 *  3. **公平性统计**（[line count fairness holds on production configurations]）：
 *     直接就是 S3 的验收判据（逐格行数相等率 + 总行数比值），按实测值下浮设阈。
 *  4. **已知偏差钉值**（[known deviations ...]）：有解释的偏差逐格写死我们的区间与行数方向，
 *     表一改即红，逼改动被显式看见。
 *
 * ## 生产不变式
 *
 * `widthPx ≥ ceil(minContentWidth)` —— 版心永远装得下最宽的不可断单元，故 R1 core 在生产里是兜底而非常规路径。
 * 第 3、4 层都按这个地板分层（[floorPx]），**别把生产外的格混进公平性统计**。
 *
 * ## 字体
 *
 * 依赖真实字形（Georgia / STSong / serif 回退），故本锁与 [SkiaParagraphBreakerTest] 一样
 * **只能在装有真面族的机器上跑**；无回退字体时数值不成立（见那处的同类说明）。
 *
 * ## 与 [SkiaParagraphBreakerTest] 的分工
 *
 * 那边锁「Skia 自己的行为」，这边锁「自建是否与 Skia 一致」。**行为若要改，两边都得改**，否则此处即红。
 */
class InhouseParagraphBreakerTest {

    private val fam = listOf("Georgia", "STSong", "serif")
    private val size = 20f
    private val lh = 1.5f

    private fun skiaLines(text: String, w: Int, ls: Float = 0f) = SkiaParagraphBreaker(ls)
        .breakLines(text, size, lh, w, TextAlign.LEFT, "p", fam, 400, false, false)

    private fun mineLines(text: String, w: Int, ls: Float = 0f) = InhouseParagraphBreaker(ls)
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

    /** 实测 640 格全 parity 的三个文本（[parity holds on the clean sample set] 用）。 */
    private val cleanSamples = listOf(
        "ANSI/NISO 与 [1](Rea) 混排时的断点。",
        "字串 with 空格 and 或者「全角引号」混排。",
        "短句。",
    )

    // ---- 基元 ----

    /** 生产不变式的地板：`ceil(minContentWidth)`（Skia 真字形宽口径 = 生产口径）。 */
    private fun floorPx(text: String, ls: Float = 0f): Int =
        Math.ceil(SkiaParagraphBreaker(ls).minContentWidth(text, size, fam, 400, false, false).toDouble()).toInt()

    private fun assertParity(text: String, w: Int, ls: Float = 0f) {
        val a = skiaLines(text, w, ls)
        val b = mineLines(text, w, ls)
        assertEquals("行数应一致（text=«$text» W=$w ls=$ls）", a.size, b.size)
        for (i in a.indices) {
            assertEquals(
                "第 ${i + 1} 行区间（text=«$text» W=$w ls=$ls）",
                a[i].range.toString(), b[i].range.toString(),
            )
        }
    }

    /**
     * 结构不变量：区间不越界 / 非空 / 不与上行重叠 / 不含 `\n` / 并起来覆盖全部非 `\n` 字符 / 行高合规。
     * **这一层与禁则取舍无关**，任何格都必须成立 —— 丢字符 / 区间含 `\n` 是绝不能有的 bug。
     *
     * 例外：**整段只有换行**的输入（`"\n"`、`"\n\n\n"`）两侧都退到 `BrokenLine(0 until n)` 的兜底单行，
     * 区间因此含 `\n`。那是两侧同口径的既有行为，由 [greedy semantics 3b] 单独锁一致性，此处不适用。
     */
    private fun assertWellFormed(text: String, w: Int, ls: Float = 0f) {
        val n = text.length
        val seen = BooleanArray(n)
        var prevEnd = 0
        mineLines(text, w, ls).forEachIndexed { k, l ->
            val r = l.range
            assertTrue("第 ${k + 1} 行区间越界（r=$r n=$n text=«$text» W=$w ls=$ls）", r.first >= 0 && r.last < n)
            assertTrue("第 ${k + 1} 行区间为空（text=«$text» W=$w）", !r.isEmpty())
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

    // ---- 第 2 层：精选格逐行全等 ----

    /**
     * 三个「干净样本」在整个网格上实测 100% parity（无族B1 / T2d 代价接缝）。
     * 它们覆盖 ASCII 斜杠与方括号接缝、全角引号、换行外的各类空白。
     *
     * ⚠ **往 [cleanSamples] 加文本前必须先核实**：这三个之所以干净，正是因为不含
     * `X|—` / `X|《` / `]|字母` 这三类已知偏差接缝。
     */
    @Test
    fun `parity holds on the clean sample set`() {
        var cells = 0
        for (t in cleanSamples) for (ls in gridSpacings) for (w in gridWidths) {
            assertParity(t, w, ls)
            cells++
        }
        assertEquals("clean 覆盖面（3 文本 × 16 版心 × 4 字距）", 192, cells)
    }

    // ---- 第 3 层：公平性统计（就是 S3 的验收判据） ----

    /**
     * 生产配置（`W ≥ floorPx`）上的行数公平性 —— S3 判据「行数公平性 ≥95%」与
     * 「总行数比值 ∈ [0.95, 1.05]」的回归锁。
     *
     * 实测基线（真面族、640 格中 floor 以上的 552 格）：逐格 parity **93.84%**、总行数比值 **1.0025**、
     * 最差单格比值 **1.500**。阈值按实测下浮留余量：parity 93.0、比值 0.98~1.02。
     * 下限比 S3 判据更宽，是因为**本集合是刻意构造的难例**（满载 `—`/书名号/URL/`]` 接缝）；
     * 真实书库的基线是 T1 实测的 96.81%，那个数由 T1 自己守。
     *
     * ## 【S7 更新】parity 基线 93.84% → 85.87%，**这是预期的正确变化**
     *
     * S7 给英文散文加了 [orilumn.reader.engine.laying.EnglishHyphenationSource]（K-L 音节断点），
     * 而 **Skia 的 `Paragraph` 完全不做音节断词**（UAX#14 `AL × AL` 禁断，实测单个拉丁词零断点）。
     * 所以我们多出来的断点逐格都与 Skia 不同 ⇒ parity 必然下降。
     *
     * **「parity 低」在这里不等于「变差」**：parity 衡量的是「与 Skia 一致」，而 K-L 正是
     * **故意偏离 Skia 去补它的缺口**（R2 的需求本身）。真正该守的是**行数不失控**，见下。
     *
     * 实测（S7 后，552 格）：parity **85.87%**、总行数比值 **0.9924**、最差单格仍是已知的
     * 破折号格 `«混合 mixed 内容 with 破折号——与空格…»`（1.500，与 S3 前同格，非 K-L 引起）。
     *
     * **行数比值 0.9924 才是这里的健康指标**：整部只少 0.76% 的行 ⇒ K-L 只在「确实放不下」
     * 的词上生效，没有造成大面积重排；阈值 [0.98, 1.02] 不动。
     *
     * parity 阈值下调到 85.0：留 0.87 个点余量，且**这个数字被写进断言消息**，
     * 下次再降会立刻看到（不让阈值无声下滑 —— 教训 ㉒）。
     */
    @Test
    fun `line count fairness holds on production configurations`() {
        var cells = 0
        var sameCells = 0
        var skiaLines = 0
        var mineLines = 0
        var worst = 0.0
        var worstKey = ""
        for (t in gridSamples) {
            val floor = floorPx(t)
            for (ls in gridSpacings) for (w in gridWidths) {
                if (w < floor) continue
                val a = skiaLines(t, w, ls)
                val b = mineLines(t, w, ls)
                cells++
                if (a.map { it.range } == b.map { it.range }) sameCells++
                skiaLines += a.size
                mineLines += b.size
                val r = b.size.toDouble() / a.size
                if (r > worst) { worst = r; worstKey = "«$t» W=$w ls=$ls" }
            }
        }
        assertEquals("生产格数（floor 以上的格）", 552, cells)
        val parityRate = 100.0 * sameCells / cells
        val ratio = mineLines.toDouble() / skiaLines
        println("生产配置：cells=$cells parity=${"%.2f".format(parityRate)}% 行数比值=${"%.4f".format(ratio)} 最差单格=$worstKey")
        assertTrue(
            "逐格 parity 率不得低于 85.0%（实测 ${"%.2f".format(parityRate)}%）。" +
                "S7 加 K-L 音节断点后基线由 93.84% 降到 85.87%：**这是预期的** ——" +
                "Skia 不做音节断词，我们多出的断点逐格都与它不同。" +
                "但**真该守的是行数比值**（${"%.4f".format(ratio)}，阈值 [0.98,1.02]），它才是「没乱重排」。",
            parityRate >= 85.0,
        )
        assertTrue("总行数比值须在 [0.98, 1.02]（实测 ${"%.4f".format(ratio)}）", ratio in 0.98..1.02)
        assertTrue(
            "单格行数比值不得失控（最差 ${"%.3f".format(worst)} @ $worstKey）",
            worst <= 1.5 + 1e-9,
        )
    }

    // ---- 第 4 层：已知偏差钉值 ----

    /**
     * 逐格钉住「有解释的偏差」，并同时钉住**行数偏差的方向**（族B1 让我们行数变多、T2d 让我们变少，
     * 两个方向都要有守卫，否则改表时容易只盯一个方向）。
     *
     * 表一改，这些用例即红 —— 这是刻意的：偏差必须被显式看见，不能悄悄漂移。
     */
    @Test
    fun `known deviations - over-strict X dash seams cost up to one extra line`() {
        // 族B1① `”|—`（右字 `—` 在 noBreakBefore）→ 我们退到更左的断点，行数不变
        val s1 = "他说：“好。” …… “……”——（1）《书名》！？；："
        assertEquals(
            "族B1 `”|—`：Skia 断在位置 15，我们退到位置 11",
            listOf("0..7", "8..10", "11..16", "17..21", "22..27"),
            mineLines(s1, 120).map { it.range.toString() },
        )
        assertEquals("族B1① 行数不变", skiaLines(s1, 120).size, mineLines(s1, 120).size)

        // 族B1② `的|—` → 行数不变
        val s5 = "第三章 目的——奇袭 与 hyphen-ated 的接缝。"
        assertEquals(
            "族B1 `的|—`：Skia 断在位置 6 且该行溢出 4.8px，我们禁断",
            listOf("0..4", "5..12", "13..24", "25..28"),
            mineLines(s5, 120).map { it.range.toString() },
        )
        assertEquals("族B1② 行数不变", skiaLines(s5, 120).size, mineLines(s5, 120).size)

        // 族B1③ `号|—` → 白扔一整行版心（该行只填到 60px 中的 40px）
        val s7 = "混合 mixed 内容 with 破折号——与空格。more text here"
        assertEquals(
            "族B1 `号|—`：Skia 第 5 行正好填满 60px，我们只填到 40px",
            listOf(
                "0..2", "3..8", "9..11", "12..16", "17..18", "19..21",
                "22..23", "24..25", "26..30", "31..35", "36..39",
            ),
            mineLines(s7, 60).map { it.range.toString() },
        )
        assertEquals("族B1③ 代价：行数 10 → 11", 10, skiaLines(s7, 60).size)
        assertEquals("族B1③ 代价：行数 10 → 11", 11, mineLines(s7, 60).size)
    }

    @Test
    fun `known deviations - over-strict X dash at a single cell reaches 1_5x line count`() {
        // 全网格最差单格：族B1 `号|—` 在版心恰好卡住时把 2 行抬成 3 行（1.50x）。
        // 这是「不修」的**已知代价上限**，超过它就说明表又变坏了。
        val t = "混合 mixed 内容 with 破折号——与空格。more text here"
        assertEquals("Skia 2 行", 2, skiaLines(t, 280, 0.05f).size)
        assertEquals("自建 3 行（1.50x，族B1 代价上限）", 3, mineLines(t, 280, 0.05f).size)
        assertEquals(
            "族B1 代价上限的具体区间",
            listOf("0..18", "19..35", "36..39"),
            mineLines(t, 280, 0.05f).map { it.range.toString() },
        )
    }

    @Test
    fun `known deviations - T2d cost one lets close-bracket-alphabet seams through`() {
        // T2d 代价①：`]|字母` 本表放行、Skia 按 LB30 `CP × (AL|HL|NU)` 禁断。
        // 方向与族B1 **相反** —— 这里我们更松，于是行数变少（5 → 4）。必须有守卫，别只盯变多那一侧。
        val t = "and/or、foo/bar、a]b 三种 ASCII 接缝。"
        assertEquals("Skia 5 行", 5, skiaLines(t, 120, 0.02f).size)
        assertEquals("自建 4 行（T2d 代价①让我们更松）", 4, mineLines(t, 120, 0.02f).size)
        assertEquals(
            "T2d 代价① 的具体区间",
            listOf("0..6", "7..16", "17..27", "28..30"),
            mineLines(t, 120, 0.02f).map { it.range.toString() },
        )
    }

    // ---- 生产不变式之外：R1 紧急断行 ----

    /**
     * 版心窄于 min-content 时（生产不变量之外，自建列宽永远 ≥ min-content）的行为。
     *
     * 差异是**结构性**的、不是 bug：版心连最宽不可断单元都装不下时，Skia 选「整词溢出」，
     * 自建走 R1 core 贪心切（§2.2 行为表）。R1 的意义就是**宁可切也不溢出**。
     *
     * 实测（88 格 floor 以下）：parity 26.1%、总行数比值 **1.0560**（比生产配置略高，
     * 因为合法断点稀疏时我们更倾向退到左边的合法断点）。这一档不参与 S3 公平性统计，
     * 只锁结构不变量（已由第 1 层覆盖）与「不丢字符」。
     */
    @Test
    fun `below min-content the in-house breaker keeps every character where Skia drops a break space`() {
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
        // 差异存在且被记录：Skia 把断点处的空格整个丢掉（覆盖缺 index 2），我们不丢。
        assertEquals("Skia 丢断点空格", listOf("0..1", "3..3"), skiaLines(" a b", 10).map { it.range.toString() })
        assertEquals("自建逐字切开、不丢空格", listOf("0..0", "1..1", "2..2", "3..3"), mineLines(" a b", 10).map { it.range.toString() })
    }

    // ---- 三条贪心语义（§T1b） ----

    @Test
    fun `greedy semantics 1 - trailing whitespace hangs but stays inside the line range`() {
        // ⚠ 用例必须**真正溢出**：整串放得下时「单行 [0,n)」是平凡结果，测不出悬挂语义（§T1b 踩过）。
        val t = "一二三四五六七八九十   "
        for (w in intArrayOf(100, 101, 103, 120, 220)) {
            assertTrue("W=$w 应在生产不变式之上", w >= floorPx(t))
            assertParity(t, w)
            assertWellFormed(t, w)
        }
        // 直接断言语义本身（parity 红了看不出是哪条坏了）：末行把 3 个尾随空格含进行区间
        val lines = mineLines(t, 100)
        assertEquals(2, lines.size)
        assertEquals("末行应把 3 个尾随空格含进去", t.length - 1, lines[1].range.last)
    }

    @Test
    fun `greedy semantics 1b - trailing whitespace in a middle line also hangs`() {
        // 关键形状：**中间行**（非末行）的行尾空白同样悬挂 —— 早期只测末行，漏了这一格。
        val t = "aaaa bbbb cccc dddd eeee ffff gggg hhhh   "
        for (w in intArrayOf(70, 90, 110, 130, 160)) assertParity(t, w)
    }

    @Test
    fun `greedy semantics 2 - no whitespace collapsing and no line-start trimming`() {
        val t = "a  b  c   d"
        for (w in intArrayOf(20, 26, 31, 40, 60)) assertParity(t, w)
        // 行首空格照算
        val s = " a b"
        for (w in intArrayOf(14, 18, 22, 26, 31)) {
            assertParity(s, w)
            assertWellFormed(s, w)
        }
        // 自然宽口径：`"a  b"` 必须比 `"a b"` 恰好多一个空格宽（无折叠）
        val m = SkiaRunMeasurer()
        fun w0(x: String) = m.advances(x, size, 0f, "p", fam, 400, false, false).sum()
        assertEquals(
            "双空格串应恰好多一个空格宽（无折叠）",
            w0(" ").toDouble(), (w0("a  b") - w0("a b")).toDouble(), 1e-3,
        )
    }

    @Test
    fun `greedy semantics 3 - hard newline breaks and pure newlines produce no phantom line`() {
        val cases = listOf("a\n\nb", "a\nb", "a\n", "\na", "ab\ncd", "\n", "\n\n\n", "a\n\n\nb", " \n a")
        for (t in cases) {
            assertParity(t, 1000) // 宽到放得下：验「硬断 + 空行丢弃」
            val floor = floorPx(t).coerceAtLeast(1)
            for (w in intArrayOf(floor, floor + 1, floor + 7, floor + 40)) {
                assertParity(t, w)
                // 「整段只有换行」时两侧都落到同一个兜底单行（区间含 `\n`），见 [assertWellFormed] 的例外说明。
                if (t.any { it != '\n' }) assertWellFormed(t, w)
            }
        }
        assertEquals("`a\\n\\nb` 是 2 行而非 3 行（纯换行空行丢弃）", 2, mineLines("a\n\nb", 1000).size)
    }

    /**
     * 「整段只有换行」的兜底单行：两侧**同口径**，不是分歧。
     *
     * `"\n"` / `"\n\n\n"` 这类输入没有可断点也没有可绘制字符，两侧都会退到
     * `BrokenLine(0 until n)` 的兜底（见 [InhouseParagraphBreaker.greedy] 与
     * [SkiaParagraphBreaker.layoutOnce] 的 `out.isEmpty()` 分支），区间因此含 `\n`。
     * 真实文本不会出现这种段，调用侧 [LineWindowDrawer] 画 `\n` 也是既有行为，故只锁**两侧一致**。
     */
    @Test
    fun `greedy semantics 3b - newline-only paragraphs fall back identically on both sides`() {
        for (t in listOf("\n", "\n\n\n", "\n \n")) for (w in intArrayOf(1, 2, 10, 1000)) {
            assertParity(t, w)
        }
    }

    // ---- 基线自检（教训 ⑶：对照本身失效则上面全部不可信） ----

    @Test
    fun `baseline - both sides agree on a wide CJK line`() {
        val t = "填充文字测试用例"
        val a = skiaLines(t, 360)
        val b = mineLines(t, 360)
        assertEquals(1, a.size)
        assertEquals(a[0].range.toString(), b[0].range.toString())
    }
}