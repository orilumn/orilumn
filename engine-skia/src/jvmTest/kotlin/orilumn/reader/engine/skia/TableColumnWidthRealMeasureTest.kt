package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * ## 判据为什么用「方向 + 界」而不是「绝对像素」
 *
 * 本类跑在 **JVM（macOS）**，字体与平板不同 ⇒ 绝对像素值跨宿主不可移植。
 * 可移植的是**同宿主两侧的比值**与**方向**。故锁 3/4 钉比值与方向，锁 1/2 钉「与桩差一个数量级」。
 *
 * ## 锁 5 是本类真正的接线锁
 *
 * 前身那句「若哪天有人把 `tableBreaker` 也接进单源工厂，这里立刻炸」如今**方向倒过来了**：
 * 现在必须**已经**接进单源工厂。锁 5 断言「拨 `inhouseBreak` 开关，表格列宽**会变**」——
 * 若有人把 `BoxChapterLayouter.tableBreaker` 改回 `SkiaParagraphBreaker`（或把重路径的
 * `bodyParagraphBreaker(...)` 换回别的对象），锁 5 立刻红。
 *
 * **变异验证（实测逐条跑过，括号内是实际点红的锁）**：
 *
 * | 变异 | 内容 | 结果 |
 * |---|---|---|
 * | MUT-I | `BoxChapterLayouter.breakerFor` 改调 `SkiaParagraphBreaker` | 锁 7 RED（锁 5 仍绿 —— 它不走 `BoxChapterLayouter`） |
 * | MUT-J | 删掉 [InhouseParagraphBreaker.preferredWidth] 覆写 | 锁 1/2/3/4 RED |
 * | MUT-K | `naturalWidth` 结果 ×1.05（偏宽） | 锁 1/2/3/4 RED |
 * | MUT-L | `naturalWidth` 结果 ×0.95（偏窄） | 锁 2/3/4 RED（**锁 1 不红** —— 它不测方向） |
 *
 * 锁 5/7 在 MUT-J/K/L 下仍绿是**正确**的分工：它们测的是「接线有没有生效」，
 * 测量侧的错它们本就不该管；反之 MUT-I 只红锁 7 也说明两把锁真的在看两条不同的路。
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
        val skia = SkiaParagraphBreaker(0f)
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
        // 与 Skia 侧同量级（同一宿主两侧对照，这是可移植的比值）。
        assertTrue(
            "自建侧应与 Skia 侧同量级（实测差 <=1.104%）；实测 自建=${pref(inhouse, latin)} " +
                "Skia=${pref(skia, latin)}",
            pref(inhouse, latin) <= pref(skia, latin) * 1.03f,
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
        // 与 Skia 侧逐值同量级：实测 37 字 Latin 上 181.6404 vs 181.6404（完全相等）。
        val skia = SkiaParagraphBreaker(0f)
        assertEquals(
            "minContentWidth 应与 Skia 侧逐值相等（实测完全相等）",
            minc(skia, latin),
            minc(inhouse, latin),
            0.001f * maxOf(1f, minc(skia, latin)) / fs,
        )
    }

    /**
     * 锁 3：**同口径下**无 kern 对的文本两侧逐值相等（CJK / URL / 混排实测 0.0000%）。
     *
     * 这是本类最硬的一条：它说明自建侧与 Skia 侧**不是「差不多」，而是同一个量** ——
     * 差值只可能来自整形（kern/liga），而这些类别没有 kern 对。
     *
     * ## 口径对齐（2026-10-03 标点挤压落地时补的一行构造参数）
     *
     * 本锁的被试是**「量测差异只可能来自整形」**，而标点挤压是另一条**有意**的宽度差异。
     * 两者混在一起测 ⇒ 锁红的不是被试性质，是**口径没对齐**。
     * ⇒ 自建侧显式 `punctuationSqueezeMaxEm = 0f` 把这一个变量**隔离出去**，
     * 原判据（0.0000% 逐值相等）一字不改地保留。
     * 挤压本身与 `preferredWidth` 的关系由**紧邻的锁 3b** 单独立锁 —— 那条锁得更死（逐值等于总额度）。
     *
     * ## ⚠ 混排语料在 0 档下**不再**与 Skia 相等（2026-10-03 混排字距改口径，这是真差不是回归）
     *
     * 混排字距的 0 档现在与其它档同规则：**作者手打的分隔空格照吃**（画成零宽）。
     * 而 Skia 那条路（`SkParagraph`）保得住那些空格 ⇒ 两侧行宽差 `边界数 × 空格宽`。
     * [mixed] = `"Skia Paragraph 的 defaultFallback 链"` 正好 3 个分隔空格（11.10px 每个）
     * ⇒ 实测 `Skia=688.38 / 自建=655.08`，差 **33.30 = 3 × 11.10**，一分不差。
     *
     * ⇒ 本锁对 `mixed` 的判据必须**排除吃空格那一份**，只比「注入间隙」那一份：
     * 断言 `自建 = Skia − 被吃空格的 advance 总和`。这样锁守的仍是「整形之外逐值相等」，
     * 且把「0 档吃空格」这个**已知差异**钉成了公式而不是豁免。
     *
     * ⚠ **别改成「让 0 档不���空格」来把这把锁弄绿** —— 那正是本轮删掉的特例，
     *   需求原话是「混排字距滑块为 0 时不要做特殊操作，就让距离为 0 就可以了」。
     */
    @Test
    fun `同口径下无 kern 对的文本两侧逐值相等`() {
        val skia = SkiaParagraphBreaker(0f)
        val inhouse = InhouseParagraphBreaker(0f, 0f, punctuationSqueezeMaxEm = 0f)
        val measurer = SkiaRunMeasurer()
        for ((s, label) in listOf(cjk to "CJK", url to "URL", mixed to "混排")) {
            val a = pref(skia, s)
            val b = pref(inhouse, s)
            // 0 档吃掉的作者分隔空格（Skia 那侧保住了它们，见类 KDoc）。CJK / URL 语料
            // 一个都没有 ⇒ 这一项为 0，判据自动退化成「逐值相等」，不必分叉成两把锁。
            val adv = measurer.advances(s, fs, 0f, null, families, 400, false, false, emptyList(), emptyList())
            var eaten = 0f
            var spaceSlots = 0
            for (g in CjkLatinSpacing.gaps(s, 0f)) {
                for (k in 1..g.spaceCount) {
                    eaten += adv[g.leftIndex + k]
                    spaceSlots++
                }
            }
            assertEquals(
                "$label 两侧应逐值相等（除 0 档吃掉的分隔空格外，实测 0.0000%）：" +
                    "Skia=$a 自建=$b 被吃空格 $spaceSlots 个共 $eaten",
                a - eaten,
                b,
                0.001f * maxOf(1f, a) / fs,
            )
        }
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
     * 锁 4：含 kern 对的 Latin —— **方向锁 + 界**。
     *
     * 方向（自建永不窄于 Skia）是可移植的硬事实：整形缺口全为负（kern/liga 只让字形更近）。
     * 界（<=3%）防止将来有人在测量里塞进第二个偏差源。
     */
    @Test
    fun `含 kern 对的 Latin 偏宽但不超过三个百分点`() {
        val skia = SkiaParagraphBreaker(0f)
        val inhouse = InhouseParagraphBreaker(0f)
        for (s in listOf("Office of the Future", "AVATAR Wayfinding WA")) {
            val a = pref(skia, s)
            val b = pref(inhouse, s)
            assertTrue(
                "自建侧永不窄于 Skia 侧（整形缺口全为负）：「$s」 Skia=$a 自建=$b",
                b >= a - 0.001f,
            )
            assertTrue(
                "自建侧应不宽出 3%（实测 +1.104% / +0.838%）：「$s」 Skia=$a 自建=$b",
                b <= a * 1.03f,
            )
        }
    }

    /**
     * 锁 5（**接线锁**）：拨 `inhouseBreak` 开关，表格列宽**必须变**。
     *
     * 这一条取代了前身那句「若有人把 `tableBreaker` 接进单源工厂，这里立刻炸」——
     * 现在方向倒过来：**必须已经接进**。
     *
     * **注意本锁只管重路径**：`cellColumnWidths` 直接构造 `BoxLayouter`，不经过
     * [orilumn.reader.engine.BoxChapterLayouter]，所以改 `breakerFor` 它照样绿（MUT-I 实测）。
     * 轻路径由锁 7 管。两把缺一不可。
     *
     * ## 表的形态是被试的一部分，不是随手写的（第一版就栽在这）
     *
     * 第一版这张表用「47 字 Latin + 47 字 URL + 23 字 CJK」@fs=44.4。三列 `pref` 之和 ≈ 5300px
     * ≫ 版心 1740px ⇒ 落进 `autoColumnLayout` 的**受限段**，列宽被**夹成等分** `[1334,1334,1334]`，
     * 两侧一模一样 ⇒ 锁红。**红的原因不是接线没生效，是量错了段**
     * ——正是本文件 KDoc 里「错误 1：量错对象」的同一类。
     *
     * ⇒ 换成三列都很短的表（`sum(pref) ≪ contentW`）落 `avail>=totalMax` 直通段，实测
     * fs=44.4/w=1740：Skia `[264,254,135]` vs 自建 `[266,254,135]`（Latin 列宽出 2px，即一个 kern 对）。
     * ⇒ 并**在本锁里断言这个前提**：全列等宽即说明被夹成等分了，量的是夹取结果不是 `pref`。
     */
    @Test
    fun `表格列宽真的响应断行器变体（接线已生效）`() {
        val html = """
            <html><body><div class="tbl"><div><table>
            <tr><td>Office WA</td><td>AVATAR</td><td>名称</td></tr>
            <tr><td>Wayfinding</td><td>Paragraph</td><td>值</td></tr>
            </table></div></div></body></html>
        """.trimIndent()
        val ua = "table { table-layout: auto; border-collapse: collapse; border-spacing: 0; }" +
            ".tbl table td { padding: .5em; border: 1px solid #c0c0c0; }"

        // 两侧都要显式写，不靠默认值 —— 默认已是自建，复位回的是 on 不是 Skia。
        AbSwitch.resetForTest()
        AbSwitch.apply("inhouseBreak=0")
        val skiaCols = cellColumnWidths(html, ua)
        assertTrue("应至少有 1 列", skiaCols.isNotEmpty())
        assertTrue(
            "本锁的前提：必须落在 `avail>=totalMax` 直通段（`w[i]=pref[i]`）。" +
                "若全列等宽，说明 `sum(pref) > contentW` 落进了受限段、列宽被夹成等分，" +
                "此时量到的是夹取结果不是 pref，拨开关当然不动。换更短的单元格文本，别换判据。" +
                "实测=$skiaCols",
            skiaCols.distinct().size > 1,
        )
        AbSwitch.apply("inhouseBreak=1")
        try {
            val inhouseCols = cellColumnWidths(html, ua)
            assertNotEquals(
                "表格列宽必须响应断行器变体：若两侧相等，说明表格度量又被钉回 Skia 了。" +
                    "（Q6 后两侧都经 `bodyParagraphBreaker`；重路径由本锁看、" +
                    "轻路径由锁 7 看，谁被换回 `SkiaParagraphBreaker` 谁就是元凶）" +
                    "实测 Skia=$skiaCols 自建=$inhouseCols",
                skiaCols,
                inhouseCols,
            )
        } finally {
            AbSwitch.resetForTest()
        }
    }

    /**
     * 锁 7（**轻路径接线锁**）：走 [orilumn.reader.engine.BoxChapterLayouter.prepareLight] 真接线取列宽。
     *
     * ## 为什么锁 5 抓不到 MUT-I，必须另加这一把
     *
     * 锁 5 的 `cellColumnWidths` 直接 `BoxLayouter(fs, bodyParagraphBreaker(0f))`，
     * **压根不经过 `BoxChapterLayouter`** —— 于是改 `BoxChapterLayouter.breakerFor` 它照样绿。
     * 这就是本文件 KDoc 里「错误 3：绕过接线」，第一版又踩了一次（变异验证才发现：
     * MUT-I 两轮都是 BUILD SUCCESSFUL）。
     *
     * 而表格测宽在**轻路径**（`LightPrepare`）里被消费，重路径压根不经过它 ——
     * 所以要钉住轻路径的断行器来源，就必须**真的走轻路径**。
     *
     * 判据与锁 5 同形（拨开关列宽必须变），只是取的盒子来自 [LightPrepare.block]。
     *
     * ## 这把锁当场抓到一个真 bug（不是预演出来的）
     *
     * 第一版写出来时**当场红**：`breakerFor` 当时只按 `profile` 缓存，而实例的**类**由
     * `AbSwitch.inhouseBreak()` 决定。`BoxChapterLayouter` 寿命是整本书、跨多次排版参数变更，
     * 于是拨开关之后仍按 profile 命中缓存、继续发旧变体的实例 ——
     * **「回退阀」在缓存命中时静默失效**（且 `paramHash` 变了会重排、重排却拿到同一套断行器）。
     * 缓存键已改成 `(profile, 变体)` 两个分量。
     *
     * 这条也是「接线锁必须真走接线」的第二个理由：绕过去测就测不到这类跨状态的缓存错。
     */
    @Test
    fun `轻路径表格列宽也响应变体（走 BoxChapterLayouter 真接线）`() {
        val html = """
            <html><body><div><table>
            <tr><td>Office WA</td><td>AVATAR</td><td>名称</td></tr>
            <tr><td>Wayfinding</td><td>Paragraph</td><td>值</td></tr>
            </table></div></body></html>
        """.trimIndent()
        val markup = HtmlTreeConverter().convert(html)!!
        val bc = orilumn.reader.engine.BoxChapterLayouter()
        val profile = TypographicProfile(
            bodyPx = 44.4f, headingScale = 1.4f, quoteScale = 1f, codeScale = 0.92f,
            lineSpacing = 1f, lineSpacingMult = 1f, paragraphSpacingPx = 0, firstLineIndentEm = 2f,
            fgColor = 0xFF000000.toInt(), bgColor = 0xFFFFFFFF.toInt(), quoteColor = 0xFF808080.toInt(),
            marginLeft = 0, marginRight = 0, marginTop = 0, marginBottom = 0,
            fontBody = "", fontTitle = "", fontCode = "", useOriginalStyle = true,
            layoutTheme = "original", coverStretch = true, paragraphGapScale = 1f, letterSpacingEm = 0f,
        )

        AbSwitch.resetForTest()
        AbSwitch.apply("inhouseBreak=0")
        val skiaCols = lightColumns(bc, markup, profile)
        assertTrue("轻路径应至少取到 1 列", skiaCols.isNotEmpty())
        assertTrue(
            "前提：轻路径这张表也必须落在 `avail>=totalMax` 直通段（全列等宽=被夹成等分）。实测=$skiaCols",
            skiaCols.distinct().size > 1,
        )
        AbSwitch.apply("inhouseBreak=1")
        try {
            val inhouseCols = lightColumns(bc, markup, profile)
            assertNotEquals(
                "轻路径表格列宽也必须响应变体。轻路径的 breaker 由 `BoxChapterLayouter.breakerFor` " +
                    "传入 `LightPrepare`（与重路径共用同一实例）；若两侧相等，说明有人给轻路径 " +
                    "另接了一个断行器，或 `breakerFor` 绕过了单源工厂。" +
                    "实测 Skia=$skiaCols 自建=$inhouseCols",
                skiaCols,
                inhouseCols,
            )
        } finally {
            AbSwitch.resetForTest()
        }
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
 *    拨开关当然纹丝不动。要量的是 `columnWidths`。
 * 2. **走错路径**：`tableBreaker` 只在**轻路径**被消费，重路径压根不经过它。
 *    测重路径时把 `tableBreaker` 改掉，本锁不会响。
 * 3. **绕过接线**：直接 `new SkiaParagraphBreaker` 喂 `BoxLayouter`，等于没接开关。
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