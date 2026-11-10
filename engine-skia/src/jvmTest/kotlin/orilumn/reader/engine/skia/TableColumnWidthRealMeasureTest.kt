package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ParagraphBreaker
import orilumn.reader.engine.ChapterStructureCache
import orilumn.reader.engine.laying.minContentSegments
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing

/**
 * Q6 锁（**正向**，2026-10-02 取代 [原 `TableBreakerStaysSkiaTest`]）：表格 auto 分列的列宽量
 * 已由 Skia 换回自建侧，且**重轻两侧同源**。
 *
 * ## 为什么这把锁当初写成「守卫型」而现在必须反向
 *
 * 前身的判据是「自建侧**尚未**实现真测量」—— 反射/行为都钉住 `preferredWidth == 段长 x fontSizePx`，
 * 且**故意在有人补上真测量时失败**，KDoc 里写明了届时的处置：「把本锁改写成『两侧都跑』的正向锁」。
 * 前提（自建侧无真测量）已于 2026-10-02 解除，故本类就是那份改写。
 *
 * **那个前提是真问题，不是形式**：接口默认桩 `text.length * fontSizePx` 在 fs=44.4 下
 * Latin **2.49x** / URL **2.09x**，而这些 `pref` 经 `TableGridModel.autoColumnLayout`
 * 的 `avail>=totalMax` 段 `w[i]=pref[i]` **直通**成最终列宽，没有任何夹取。
 *
 * ## 现在的实测（fs=16 与 44.4，`STSong, serif`，两侧同宿主对照）
 *
 * ```
 *   文本类别                Skia        自建          差
 *   CJK / URL / code / 混排   ————————   ————————    0.0000%
 *   Latin 无 kern 对          ————————   ————————    0.0000%
 *   Office of the Future      130.4320   131.8720   +1.104%
 *   AVATAR Wayfinding WA      171.8560   173.2960   +0.838%
 *   min-content (Latin 37)    181.6404   181.6404    0.0000%
 * ```
 *
 * 差值恒为**一个 kern 对的像素量**（1.44px @16、3.996px @44.4，比值 2.775 = 44.4/16），方向**永不反向**
 * （整形缺口全为负，见 `docs/TODO-未尽事宜.md` Q5）。符合
 * [ParagraphBreaker.preferredWidth] 契约里「永不窄于实需」那一句。
 *
 * ## 标点挤压带来的第二条差异源（2026-10-03）
 *
 * 上表是「未挤口径」的对照。标点挤压（[PunctuationSqueeze]）落地后，同一句 CJK 在
 * **生产口径**下的 max-content 比 Skia 侧窄**恰好一个挤压总额度**（实测 fs=44.4：
 * `都用到…简单。` Skia=1287.6006 自建=1243.2004，差 44.4004 ≈ 1em）。
 * 这是**有意**的偏离（挤压造 slack，见该对象 KDoc），不是测量走偏：
 * 锁 3 把这一个变量**隔离**（两侧同为未挤口径，判据一字不改），
 * 锁 3b 单独立锁把「窄的量 == 挤压总额度」逐值钉死。
 *
 * ## 判据为什么钉「与桩差一个数量级」而不是「绝对像素」
 *
 * 本类跑在 **JVM（macOS）**，字体与平板不同 ⇒ 绝对像素值跨宿主不可移植。
 * 可移植的是「真测量远窄于接口默认桩」这一条比值。
 *
 * ## 曾经与另一套断行器逐值对照的锁已随那套实现删除
 *
 * 早年这里有一组「同口径逐值相等 / kern 方向锁」：拿 SkParagraph 实现当基线，证明两套实现
 * 在同一宿主上量出同一个宽。那套实现（以及拨开关的双臂夹具）已整体删除，对照基线不存在了，
 * 故那组锁一并删掉；留下的是**只依赖自己**的判据（真测量、min-content 随之变真、E15 内边距记账）。
 */
class TableColumnWidthRealMeasureTest {

    private val families = listOf("STSong", "serif")
    private val fs = 44.4f

    private fun pref(b: ParagraphBreaker, s: String) =
        b.preferredWidth(s, fs, families, 400, false, false, emptyList())

    private fun minc(b: ParagraphBreaker, s: String) =
        b.minContentWidth(s, fs, families, 400, false, false, emptyList())

    private val latin = "Chapter twelve: the registrar of the clan house"
    private val url = "https://www.w3.org/TR/2017/REC-html52-20171214/"
    private val cjk = "都用到这么厉害的科技了，事情肯定不会如字面上写的那样简单。"
    private val mixed = "Skia Paragraph 的 defaultFallback 链"

    /**
     * 锁 1：自建侧 [ParagraphBreaker.preferredWidth] 必须是**真测量**。
     *
     * 判据是**行为**而非反射：Kotlin 接口默认实现会编译成 `DefaultImpls` + 类上的合成桥方法，
     * `::class.java.methods["preferredWidth"].declaringClass` 落在**类**身上，
     * 反射区分不出「用了默认桩」（前身第一版就这么写，断言直接失败，记此免得重蹈）。
     */
    @Test
    fun `自建侧 preferredWidth 是真测量而不是段长乘字号`() {
        val inhouse = InhouseParagraphBreaker(0f)
        val stub = latin.length * fs
        assertNotEquals(
            "自建侧 preferredWidth 落回了接口默认桩 `段长 x fontSizePx`。" +
                "表格 auto 分列的 `avail>=totalMax` 段 `w[i]=pref[i]` 直通成最终列宽，" +
                "桩会让 Latin 列宽放大 2 倍以上且无处夹取。",
            stub,
            pref(inhouse, latin),
        )
        // 与桩差一个数量级（实测 2.49x）⇒ 这不是「差一点点」，是真测量。
        assertTrue(
            "自建侧真测量应远窄于桩（实测桩 2.49x）；若两者接近，说明又退回桩了。" +
                "自建=${pref(inhouse, latin)} 桩=$stub",
            pref(inhouse, latin) < stub * 0.6f,
        )
    }

    /** 锁 2：`minContentWidth` **无需另写**——接口默认实现逐段调 `preferredWidth`，故随锁 1 自动变真。 */
    @Test
    fun `自建侧 minContentWidth 随 preferredWidth 一并变真`() {
        val inhouse = InhouseParagraphBreaker(0f)
        val longest = minContentSegments(latin).maxOf { it.last + 1 - it.first }
        assertNotEquals(
            "自建侧 minContentWidth 仍是「最长段 x fontSizePx」的桩。" +
                "桩之下 min/max 两条恒自洽（min 就是若干段 pref 的最大），" +
                "所以「min<=max」这类断言对桩**恒成立**、给不出任何保障。",
            longest * fs,
            minc(inhouse, latin),
        )
    }

    /**
     * 锁 3b：**max-content 与断行同源** —— 改口径后是「max-content **对挤压上限完全免疫**」。
     *
     * ## 【2026-10-03 第 2 条】口径反转，本锁整体重写
     *
     * 上一轮的口径是「`preferredWidth` 恰好比未挤口径**窄一个挤压总额度**」，
     * 前提是「挤压无条件全额上」—— 于是 max-content 必须跟着减，否则
     * 「断行侧按挤后的宽判放得下、max-content 按没挤的宽答放得下」，端到端锁实测行宽 706.2265 > 700。
     *
     * 现在挤压**只在「不挤就会把词切坏」时**触发（[orilumn.reader.engine.skia.InhouseParagraphBreaker.greedy]），
     * 而 max-content 的定义是「整段排成**一行**」—— 一行即段落末行，**没有断点决策**
     * ⇒ 那一行的挤压比例恒为 0 ⇒ 绘制侧也不挤（画侧 ③ 要求 JUSTIFY 且非末行）
     * ⇒ 两侧此刻同为「不挤」⇒ **`squeezeMaxEm` 传什么都不影响 max-content**。
     *
     * ## 判据为什么钉「三档 cap 逐值全等」而不是「差 == 0」
     *
     * 「差 == 0」只能钉住一个 cap 值；把 cap 拉成 `0 / 0.5em / 100em` 三档一起比，
     * 才钉住「**这条路径压根不读 cap**」这个性质 —— 否则有人把读取写成
     * `min(cap, …)` 之类只在小 cap 下不可见的形状，会从 0.5em 那一档漏过去。
     *
     * 另配一条上界：语料必须真的产出挤压额度（`totalSeen > 0`），否则三档全等会自证成 `0 == 0`。
     */
    @Test
    fun `max-content 不受挤压上限影响且逐值等于未挤口径`() {
        fun breaker(capEm: Float) = InhouseParagraphBreaker(0f, 0f, punctuationSqueezeMaxEm = capEm)
        val measurer = SkiaRunMeasurer()
        var totalSeen = 0f
        for ((s, label) in listOf(cjk to "CJK", mixed to "混排")) {
            val adv = measurer.advances(s, fs, 0f, null, families, 400, false, false, emptyList(), emptyList())
            val squeeze = PunctuationSqueeze.widths(
                s, 0, s.length, adv, 0, measurer, fs, 0f, null, families, 400, false, false, emptyList(),
                PunctuationSqueeze.DEFAULT_MAX_EM,
            )
            var total = 0f
            for (x in squeeze) total += x
            totalSeen += total
            val base = pref(breaker(0f), s)
            assertEquals(
                "$label：max-content 是「整段一行」⇒ 挤压比例恒 0 ⇒ cap=0.5em 与 cap=0 逐值相同。" +
                    "有差说明有人在 max-content 里减了额度，而断行侧并不挤（凭空少报一截列宽）。",
                base,
                pref(breaker(PunctuationSqueeze.DEFAULT_MAX_EM), s),
                0.001f,
            )
            assertEquals(
                "$label：cap 拉到 100em（远超任何额度）仍逐值相同 ⇒ 这条路径压根不读 cap" +
                    "（本语料挤压总额度 $total）。",
                base,
                pref(breaker(100f), s),
                0.001f,
            )
        }
        // 上界：否则上面两条在「语料里一个可压标点都没有」时会自证通过。
        assertTrue(
            "本锁的前提：语料必须真的产出挤压额度（实测总额度 $totalSeen）。" +
                "若为 0，换含收尾标点的语料，别删这条断言。",
            totalSeen > 0f,
        )
    }

    /**
     * E15（度量侧）：格内行内盒的横向 `padding`/`border` 计入 auto 分列的 max/min-content，
     * **重轻双路**同账（两路都经 `NormalFlowLayout.tableCellPref` 单源）。
     *
     * 判据：同一张表，只把 `<span>` 的 `padding:0 30px`（配合 background 让盒可见）打开，
     * 首列列宽增量必须**恰为 60**（直通段 `w[i]=pref[i]`，pref 恰好 +60；列取整对整数平移不变），
     * 次列纹丝不动。少传 `inlineBoxRuns` ⇒ 增量 0，当场破。
     *
     * 两列都短（`sum(pref) ≪ 1740`）⇒ 落直通段，断言里顺带钉住这个前提。
     */
    @Test
    fun `E15 行内盒横向内边距计入 auto 列宽（重轻双路）`() {
        val padSpan = """<span style="padding:0 30px;background:#ffe082">AB</span>"""
        val bareSpan = """<span>AB</span>"""
        fun doc(span: String) = """
            <html><body><table>
            <tr><td>$span</td><td>CD</td></tr>
            </table></body></html>
        """.trimIndent()
        val ua = "table { table-layout: auto; border-collapse: collapse; border-spacing: 0; }"

        val heavyPad = cellColumnWidths(doc(padSpan), ua)
        val heavyBare = cellColumnWidths(doc(bareSpan), ua)
        assertEquals("重路径应有两列", 2, heavyPad.size)
        assertTrue(
            "前提：直通段（全列等宽=被夹成等分，量到的是夹取结果）。实测=$heavyBare",
            heavyBare[0] != heavyBare[1],
        )
        assertEquals("重路径首列增量 = 2×30px 内边距", 60, heavyPad[0] - heavyBare[0])
        assertEquals("次列不受影响", heavyBare[1], heavyPad[1])

        val profile = TypographicProfile(
            bodyPx = 44.4f, headingScale = 1.4f, quoteScale = 1f, codeScale = 0.92f,
            lineSpacing = 1f, firstLineIndentEm = 2f,
            fgColor = 0xFF000000.toInt(), bgColor = 0xFFFFFFFF.toInt(), quoteColor = 0xFF808080.toInt(),
            marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0,
            fontBody = "", fontTitle = "", fontCode = "", useOriginalStyle = true,
            layoutTheme = "original", coverStretch = true, paragraphSpacingEm = null, paragraphGapScale = 1f, letterSpacingEm = 0f,
        )
        val bc = orilumn.reader.engine.BoxChapterLayouter()
        val lightPad = lightColumns(bc, HtmlTreeConverter().convert(doc(padSpan))!!, profile)
        val lightBare = lightColumns(bc, HtmlTreeConverter().convert(doc(bareSpan))!!, profile)
        assertEquals("轻路径首列增量 = 2×30px 内边距", 60, lightPad[0] - lightBare[0])
        assertEquals("轻路径次列不受影响", lightBare[1], lightPad[1])
    }
}

/** 走轻路径生产接线（`prepareLight` → `LightPrepare.block` → `tableRowLayoutFor`）取列宽。 */
private fun lightColumns(
    bc: orilumn.reader.engine.BoxChapterLayouter,
    markup: orilumn.reader.engine.html.MarkupElement,
    profile: TypographicProfile,
): List<Int> {
    val light = bc.prepareLight(markup, null, profile, 1740, ChapterStructureCache(), 1740)
    val out = ArrayList<Int>()
    for (i in 0 until light.totalBlocks) {
        light.block(i).table?.let { out.addAll(it.columnWidths.toList()) }
    }
    return out
}

/**
 * 走**重路径生产接线**（CSS → 级联 → `tableCellPref` → `autoColumnLayout`）取单元格列宽。
 *
 * ## 四个曾经把这把锁测废的错误（都记在这儿，因为都会让人拿到假绿灯）
 *
 * 1. **量错对象**：量 `table` 盒的 `contentWidth`。表盒宽是被流撑满版心的，**与列宽无关**，
 *    怎么改断行器都纹丝不动。要量的是 `columnWidths`。
 * 2. **走错路径**：`tableBreaker` 只在**轻路径**被消费，重路径压根不经过它。
 *    测重路径时把 `tableBreaker` 改掉，本锁不会响。
 * 3. **绕过接线**：直接自己 `new` 一个断行器喂 `BoxLayouter`，等于没接生产接线。
 * 4. **在测试里重写生产接线**：更隐蔽的一种——第一版直接调
 *    `autoColumnMeasurePinnedToSkia(bodyParagraphBreaker(...))` 自己拼了一遍，
 *    于是把生产接线整段删掉，锁**照样绿**（变异验证才发现）。
 *    现在这里调 `bodyParagraphBreaker`，与生产同一个函数口，删生产接线必被本锁抓到。
 */
/** 表格量测用的字号/族，与本类各锁一致（[TableColumnWidthRealMeasureTest.fs]）。 */
private fun cellColumnWidths(html: String, ua: String): List<Int> {
    val root = HtmlTreeConverter().convert(html)!!
    val engine = StyleComputer(44.4f, LightCssParser().parse(ua), emptyList())
    val styles = engine.compute(root)
    val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
    val breaker = bodyParagraphBreaker(0f)
    // 宽版心：确保落在 `avail>=totalMax` 段（`w[i]=pref[i]` 直通），
    // 这正是 pref 被放大后能一路传到最终列宽的那一段。
    val result = BoxLayouter(44.4f, breaker).layoutBoxes(root, 1740, styles, classify)
    val out = ArrayList<Int>()
    fun walk(b: LayoutBox) {
        // 列宽挂在 `table` 盒上（`LayoutBox.columnWidths`）。重路径**不**为 td/th 产出
        // 独立盒，所以找 td 盒是找不到的——第一版就是这么写的，拿到空表还以为环境有问题。
        b.table?.let { out.addAll(it.columnWidths.toList()) }
        for (c in b.childBoxes) walk(c)
    }
    for (b in result.boxes) walk(b)
    return out
}