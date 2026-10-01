package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.html.HtmlTreeConverter
import orilumn.reader.engine.laying.BoxLayouter
import orilumn.reader.engine.laying.LayoutBox
import orilumn.reader.engine.laying.NormalFlowLayout
import orilumn.reader.engine.laying.ParagraphBreaker

/**
 * S3 锁（**守卫型**）：表格 auto 分列的度量断行器必须**留在 Skia**，且这是**当前不可拨**的硬约束。
 *
 * ## 与另外四把锁的形态不同，这里为什么不做「两侧都跑」
 *
 * 前四把（[DrawLineBuilderTest] / [PhotoPersistFullWidthRegressionTest] /
 * [FirstLineIndentSingleLineOverflowTest] / app 层量画一致）都是把既有不变量在两个变体上各跑一遍。
 * 本类**故意不这么做**，因为一旦参数化，它会**因为一个错误的原因变绿**：
 * `minContentWidth` 的接口默认实现本身就是「逐段调 `preferredWidth` 取最大」，
 * 所以「min = 各断片 preferredWidth 的最大者」这条不变量对自建侧**恒成立**，
 * 哪怕 `preferredWidth` 是个 `text.length * fontSizePx` 的桩。绿灯会给出一个假的安全感。
 *
 * ## 真正的约束（实测，非推断）
 *
 * `InhouseParagraphBreaker` **没有覆写** [ParagraphBreaker.preferredWidth] / [minContentWidth]，
 * 继承的是接口默认桩：`preferredWidth = text.length * fontSizePx`。
 * fs=44.4 / `STSong, serif` 实测：
 *
 * ```
 *              文本              Skia     自建桩    比值
 *   CJK 11 字   都用到这么厉害的科技了   488.4    488.4    1.000   ← 汉字 advance 恰好 1em，纯属巧合
 *   Latin 47 字 Chapter twelve: …      836.8   2086.8    2.494
 *   URL   47 字 https://www.w3.org/…    997.3   2086.8    2.092
 *   中英混排                        706.5   1376.4    1.948
 * ```
 * `minContentWidth` 同形（Latin 2.61 / URL 2.03）。CJK 恰好相等是**巧合**，不是能力。
 *
 * ⇒ **切过去不是「零收益」，而是「静默改错」**：Latin/URL 列宽会放大 2~2.6 倍。
 * 且这些列宽经 `TableGridModel` 的 `avail>=totalMax` 段 `w[i]=pref[i]` **直通**成最终列宽，
 * 没有任何下限/上限夹取。至于放大后是否撑破版心，**本次未实测**（需要 49 张表重扫），
 * 本锁不声称、也不依赖那个结论。
 *
 * ## 与 Q6 的关系（这一条订正了此前的判断）
 *
 * T2f 的结论是「表格这条线在现实版心下结构性不可观测 ⇒ 拨开关零收益」，据此 Q6 写的是
 * 「要激活见 TODO Q6（需 fixed 列宽 / 指定列宽 / 更多列 / 更窄版心之一）」——
 * **这个措辞把前提说轻了**：即便满足那些前提，也不能直接拨开关，
 * 还得先给自建侧补上真测量。TODO Q6 的正确形态是「补 `preferredWidth` 真测量」在前，
 * 「让表格可观测」在后。本锁就是把这个顺序钉住。
 *
 * ## 本锁的判据
 *
 * 1. **反射钉住「自建侧没实现」**：两个方法的声明者必须是接口，不是类。
 *    一旦有人补上真测量，本锁**故意失败** —— 那时该把本锁改写成「两侧都跑」的正向锁，
 *    并同步更新 Q6。这是「锁跟着结论走」而不是「锁被绕过」。
 * 2. **端到端钉住「表格仍是 Skia 度量」**：拨开正文开关后，含长 Latin/URL 单元格的表
 *    列宽必须**等于** Skia 侧的值。若哪天有人把 `tableBreaker` 也接进单源工厂，这里立刻炸。
 */
class TableBreakerStaysSkiaTest {

    private val families = listOf("STSong", "serif")
    private val fs = 44.4f

    private fun pref(b: ParagraphBreaker, s: String) =
        b.preferredWidth(s, fs, families, 400, false, false, emptyList())

    @Test
    fun `自建侧尚未实现真测量——本锁故意守着这个事实`() {
        val skia = SkiaParagraphBreaker(0f)
        val inhouse = InhouseParagraphBreaker(0f)

        // 判据用**行为**而不是反射：Kotlin 的接口默认实现会编译成 `DefaultImpls` + 类上的
        // 合成桥方法，于是 `InhouseParagraphBreaker::class.java.methods["preferredWidth"]
        // .declaringClass` 落在**类**身上而不是接口 —— 反射根本区分不出「用了默认桩」。
        // （第一版就是这么写的，断言直接失败；记在这里免得下一个人再写一遍。）
        //
        // 行为判据直接钉住缺陷本身：`preferredWidth` 恒等于 `段长 x fontSizePx`。
        val latin = "Chapter twelve: the registrar of the clan house"
        assertEquals(
            "自建侧 preferredWidth 应仍是「段长 x fontSizePx」的桩。给它补上真测量后，" +
                "请把本锁改写成正向锁（两侧都跑）并同步更新 TODO Q6。",
            latin.length * fs,
            pref(inhouse, latin),
            0.001f,
        )
        // min-content 是「逐段取 preferredWidth 最大者」，桩之下即「最长段 x fontSizePx」。
        val longest = orilumn.reader.engine.laying.minContentSegments(latin)
            .maxOf { it.last + 1 - it.first }
        assertEquals(
            "自建侧 minContentWidth 应由桩 preferredWidth 推出（最长段 x fontSizePx）",
            longest * fs,
            inhouse.minContentWidth(latin, fs, families, 400, false, false, emptyList()),
            0.001f,
        )
        // 数值侧佐证：Latin 高估 2 倍以上（CJK 恰好相等只是 advance 正好 1em 的巧合）。
        assertTrue(
            "Latin preferredWidth 在自建侧应显著高估（实测 2.49x）；此断言失败说明桩被换掉了，" +
                "本类其余判据需重新评估",
            pref(inhouse, latin) / pref(skia, latin) > 1.5f,
        )
    }

    @Test
    fun `回退到 Skia 与默认自建两侧，表格列宽都相同`() {
        // 真书形态：auto 分列表 + 长 Latin/URL 单元格（正是被放大 2 倍的那类内容）。
        val html = """
            <html><body><div class="tbl"><div><table>
            <tr><td>Chapter twelve: the registrar of the clan house was waiting</td></tr>
            <tr><td>https://www.w3.org/TR/2017/REC-html52-20171214/</td></tr>
            <tr><td>都用到这么厉害的科技了，事情肯定不会如字面上写的那样简单。</td></tr>
            </table></div></div></body></html>
        """.trimIndent()
        val ua = "table { table-layout: auto; border-collapse: collapse; border-spacing: 0; }" +
            ".tbl table td { padding: .5em; border: 1px solid #c0c0c0; }"

        // 两侧都要显式写，不靠默认值 —— 默认已是自建，复位回的是 on 不是 Skia。
        AbSwitch.resetForTest()
        AbSwitch.apply("inhouseBreak=0")
        val skiaCols = cellColumnWidths(html, ua)
        assertTrue("应至少有 1 列", skiaCols.isNotEmpty())
        AbSwitch.apply("inhouseBreak=1")
        try {
            val inhouseCols = cellColumnWidths(html, ua)
            assertEquals(
                "表格列宽必须与 Skia 侧完全相同：重路径经 `autoColumnMeasurePinnedToSkia` " +
                    "已把 tableCellPref 的度量钉回 Skia，禁止把表格列宽也跟着变体走。" +
                    "（变体默认已是自建，故本断言现在是「默认侧 vs 回退侧」而不是「开 vs 关」）",
                skiaCols,
                inhouseCols,
            )
        } finally {
            AbSwitch.resetForTest()
        }
    }

    /**
 * 走**重路径生产接线**（CSS → 级联 → `tableCellPref` → `autoColumnLayout`）取单元格列宽。
 *
 * ## 三个曾经把这把锁测废的错误（都记在这儿，因为都会让人拿到假绿灯）
 *
 * 1. **量错对象**：量 `table` 盒的 `contentWidth`。表盒宽是被流撑满版心的，**与列宽无关**，
 *    拨开关当然纹丝不动。要量的是 `td`/`th` 盒的 `contentWidth`。
 * 2. **走错路径**：`tableBreaker` 只在**轻路径** `LightPrepare.tableRowLayoutFor` 里被消费；
 *    重路径压根不经过它。测重路径时把 `tableBreaker` 改成单源工厂，**本锁不会响**
 *    （我变异验证时才发现：那次「通过」来自重路径本来就没用它）。
 * 3. **绕过接线**：直接 `new SkiaParagraphBreaker` 喂 `BoxLayouter`，等于没接开关。
 * 4. **在测试里重写生产接线**：更隐蔽的一种——第一版直接调
 *    `autoColumnMeasurePinnedToSkia(bodyParagraphBreaker(...))` 自己拼了一遍，
 *    于是把 `BoxChapterLayouter:177` 的钉扎整段删掉，锁**照样绿**（变异验证才发现）。
 *    现在这里调 `heavyPathBreaker`，与生产同一个函数口，删生产接线必被本锁抓到。
 */
private fun cellColumnWidths(html: String, ua: String): List<Int> {
    val root = HtmlTreeConverter().convert(html)!!
    val engine = StyleComputer(fs, LightCssParser().parse(ua), emptyList())
    val styles = engine.compute(root)
    val classify = NormalFlowLayout.heavyClassify(styles, engine.hasDisplayDeclaration())
    val breaker = heavyPathBreaker(0f)
    // 宽版心：确保落在 `avail>=totalMax` 段（`w[i]=pref[i]` 直通），
    // 这正是 pref 被放大后能一路传到最终列宽的那一段。
    val result = BoxLayouter(fs, breaker).layoutBoxes(root, 1740, styles, classify)
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
}
