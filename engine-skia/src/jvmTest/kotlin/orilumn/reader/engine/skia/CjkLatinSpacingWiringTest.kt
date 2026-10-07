package orilumn.reader.engine.skia

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.AbSwitch
import orilumn.reader.engine.css.FontRun
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.text.TypographicProfile
import orilumn.reader.engine.text.preprocess.CjkLatinGap
import orilumn.reader.engine.text.preprocess.CjkLatinSpacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 混排字距（`cjkLatinSpacing`）的**接线锁**（渲染层·几何测量 + 排版层·上）。
 *
 * ## 这条特性此前是死代码，本类钉的是「它真的活了」
 *
 * 用户报「调节滑块完全不起作用」。查清是**整条链没接**：
 * 探测器 [CjkLatinSpacing.gaps] 早已写好且被 `CjkLatinSpacingTest` 覆盖，
 * 但没有任何一个**施加点** —— 断行器不预留、绘制侧不落墨。本类逐条钉死四个施加点，
 * 外加几条「不许优化掉它」的守卫。
 *
 * ## 四个施加点（缺任一条，滑块就只影响一部分版面）
 *
 * | 施加点 | 钉它的锁 | 不接的症状 |
 * |---|---|---|
 * | [SkiaRunMeasurer.applyCjkLatinGaps]（`advances` 出口） | 锁 2 | 完全无效果 |
 * | [InhouseParagraphBreaker]（用同一个出口预留版心） | 锁 3 | 行右溢出版心被裁 |
 * | [LineAligner.align] | 锁 4 | 量到宽、画的位置不对 |
 * | [KerningClusterTable.shiftTrackByCjkGaps] | 锁 5 | 含 Latin 的行间隙被 `graftKerningOnto` 吃掉 |
 *
 * ## 为什么必须走「同一个出口」而不是各处自己算
 *
 * 量宽是 O(n) 逐码本、按 run 切段、按面批量取 native 宽；间隙的字号规则又必须与
 * `lsPx` 逐条一致（同为「按该码本所属 run 的字号」）。若让断行侧与绘制侧各算一次，
 * 两者一旦漂移就是**量画失配**（教训 ⑩：比值从 1.20x 虚高到 4.05x），
 * 症状是「行右缘越出版心 / JUSTIFY 铺不满」。故四个点全部经 [SkiaRunMeasurer.advances]。
 *
 * ## 字号取法：按**边界左侧那个字**所在 run 的字号（不是右侧）
 *
 * 与 `lsPx` 同一条规则（`run.fontPxOr(fontSizePx)`）。为什么取左侧而不是右侧：
 * 间隙是 `adv[leftIndex] += …`，而 `x_i = x_0 + Σ_{j<i} adv[j]`，加在 `leftIndex` 上才把
 * **右边的所有字**推开；加在 `leftIndex + 1` 上则连 `leftIndex` 自己都被推开
 * （那等于把间隔画在了左边 —— 视觉上中西之间反而没有间隙）。
 * 锁 6 专门钉这个方向（混排 run 里两侧字号不同时，错取会差出一整档）。
 */
class CjkLatinSpacingWiringTest {

    /** 真机族栈（`STSong` 覆盖 CJK、`serif` 覆盖 Latin；与 [LineAlignerTest] 同款）。 */
    private val SYNTH_FAM = listOf("STSong", "serif")

    /** 默认间隙 = 0.25em（CLREQ 的经典值，也是探测器默认值）。 */
    private val GAP = 0.25f

    private fun measurer() = SkiaRunMeasurer()

    /** `advances` 的便捷调用（**全部形参显式传**，避免以后加形参时静默重绑）。 */
    private fun advances(
        text: String,
        fs: Float = 40f,
        lsEm: Float = 0f,
        runs: List<FontRun> = emptyList(),
        gapEm: Float = 0f,
        families: List<String> = SYNTH_FAM,
        gaps: List<CjkLatinGap> = CjkLatinSpacing.gaps(text, gapEm, runs),
    ): FloatArray = measurer().advances(text, fs, lsEm, "p", families, 400, false, false, runs, gaps)

    /**
     * 「完全不做混排处理」的基准（**显式空 `cjkGaps`**）。
     *
     * ⚠ **不能拿 `advances(text, gapEm = 0f)` 当基准**（产品口径 2026-10-03 起）：0 档**不是**
     * 「不处理」，它照检边界、照吃作者手打的分隔空格。只要语料里有一个分隔空格，
     * 0 档那份就比本函数少掉那个空格宽 —— 拿它当「无间隙」基准会把**吃掉空格**这件事
     * 悄悄当成「什么都没发生」，锁就白写了。凡是语义上要「无间隙」的地方一律走本函数。
     */
    private fun noGaps(text: String, fs: Float = 40f, runs: List<FontRun> = emptyList()): FloatArray =
        advances(text, fs = fs, runs = runs, gaps = emptyList())

    /** `preferredWidth` 的便捷调用（走 [InhouseParagraphBreaker] 的 max-content 口径）。 */
    private fun naturalWidth(text: String, fs: Float, gapEm: Float, families: List<String> = SYNTH_FAM): Float =
        InhouseParagraphBreaker(0f, gapEm).preferredWidth(text, fs, families, 400, false, false, emptyList())

    private fun sum(a: FloatArray): Float {
        var s = 0f
        for (x in a) s += x
        return s
    }

    /**
     * 断行侧在**整段**上量出的逐槽 advance（间隙按整段坐标系施加），切片即「某行预留到的那份宽」。
     *
     * 不能写成 `sum(advances(text)) − sum(advances(text.substring(e)))`：那样要靠
     * 「子串检出的间隙与整段在该区间的间隙相等」才能凑出对的数，而那正是待验的不变式本身 ——
     * 用它当中间量就成了自证。
     */
    private fun paragraphAdv(text: String, fs: Float = 40f, gapEm: Float = 0f): FloatArray =
        measurer().advances(
            text, fs, 0f, "p", SYNTH_FAM, 400, false, false, emptyList(),
            CjkLatinSpacing.gaps(text, gapEm),
        )

    /** 两个数组的最大逐位差（浮点必须给容差，故不用 `assertArrayEquals`）。 */
    private fun delta(a: FloatArray, b: FloatArray): Float {
        assertEquals("数组长度必须相同", a.size, b.size)
        var m = 0f
        for (k in a.indices) m = maxOf(m, abs(a[k] - b[k]))
        return m
    }

    /** 走 [LineAligner] 的落位（形参全显式 —— `align` 的位置参数历史上很脆）。 */
    private fun align(
        text: String,
        fs: Float = 40f,
        width: Float = 400f,
        gapEm: Float = 0f,
        align: TextAlign = TextAlign.LEFT,
        runs: List<FontRun> = emptyList(),
    ) = LineAligner().align(
        text, 0 until text.length, fs, width, 0f, "p", SYNTH_FAM, 400, false, false,
        runs, align, 0f, false, false, gapEm,
    )

    // ---- 锁 0：0 是**参数为 0 的那一档**，不是「关掉」那一档（产品口径 2026-10-03 改）----

    /**
     * **0 档必须与其它档检出同一份边界**，只有 `gapEm` 一个字段不同。
     *
     * 这是「0 不是特例档」最直接的判据：`gaps(text, 0f)` 与 `gaps(text, GAP)` 的
     * **`(leftIndex, spaceCount)` 必须逐条相同**，`gapEm` 逐条为 0。0 档若走另一条规则
     * （少检一处边界、或不记 `spaceCount`），这里立刻红。
     *
     * ## 本锁守的是「调用点不许对 0 短路」，判别力在下面那三条
     *
     * 探测器本身与 em 无关，所以「两份检出相等」在旧实现（0 档压根没跑探测器）下**也成立** ——
     * 真正的判别力来自下面三条，它们量的是**施加之后**的版面：
     * - 量宽侧：**照样吃分隔空格**（`0 档照吃分隔空格且注入零间隙`），
     * - 落墨侧：**中西之间的墨距真的是 0**（`0 档的中西墨距真的为 0`），
     * - 断行侧：**照样按「零宽空格」预留版心**（`0 档的预留比无间隙少掉那串空格`）。
     *
     * 变异验证：把任一施加点改回 `if (cjkLatinSpacingEm > 0f) … else emptyList()` ⇒ 本锁仍绿、
     * 但下面三条至少一条红。
     */
    @Test
    fun `0 档与其它档检出同一份边界 只差 gapEm 一个字段`() {
        val texts = listOf(SPACED, TIGHT, "中  A", "中\u00A0A", "中文Rust", "Rust中文", "A中A中", "纯中文")
        for (text in texts) {
            val zero = CjkLatinSpacing.gaps(text, 0f)
            val full = CjkLatinSpacing.gaps(text, GAP)
            assertEquals(
                "语料「$text」：0 档与 $GAP 档必须检出**同一条**边界序列（含被吃空格个数）",
                full.map { it.leftIndex to it.spaceCount },
                zero.map { it.leftIndex to it.spaceCount },
            )
            for (g in zero) {
                assertEquals("语料「$text」：0 档的 gapEm 必须逐个等于 0", 0f, g.gapEm, 0f)
            }
            // `gapsForRange` 是绘制侧（`LineAligner` / `KerningClusterTable`）唯一入口，它同样不许对 0 短路。
            val r0 = CjkLatinSpacing.gapsForRange(text, 0, text.length, 0f)
            val r1 = CjkLatinSpacing.gapsForRange(text, 0, text.length, GAP)
            assertEquals(
                "语料「$text」：`gapsForRange` 在 0 档也必须返回整份（绘制侧不许短路）",
                r1.map { it.leftIndex to it.spaceCount },
                r0.map { it.leftIndex to it.spaceCount },
            )
        }
    }

    /**
     * **0 档照样把作者手打的分隔空格吃掉**，只是注入的间隙宽为 0。
     *
     * ## 这条锁替掉的旧锁与它为什么必须换掉
     *
     * 旧锁是「0 档是**纯不动点**」——带分隔空格的语料逐位等于无间隙。
     * 那正是需求否掉的口径（「混排字距 0」却让中西之间留着一整个空格宽，字面意思相反）。
     * 本锁把期望改成：**0 档的逐槽 advance = 无间隙那份把被吃空格位清零**，其余一位不差。
     *
     * ## 三条断言各自抓一种「实现走偏」
     *
     * | 断言 | 抓什么 |
     * |---|---|
     * | 逐槽 = 清零后那份（零容差） | 通用：0 档只该做「清零」，不该做别的 |
     * | `gap=GAP` 与 `gap=0` 的差**只**落在各边界左槽 | 「注入 0 宽间隙」是不是真按 0 注入（而不是偷偷用默认值） |
     * | 无分隔空格的语料逐位等于无间隙 | 反证：清零动作**确实**来自 `spaceCount`，不是「所有字位都清一遍」 |
     *
     * ## 为什么必须用**带分隔空格**的语料
     *
     * 「吃空格」是本特性唯一会**改写字符本身**的动作。若只在无空格语料上验，
     * 「0 档同规则」等于没验 —— 而真书里 82% 的中西边界都带空格
     * （实测《Rust 程序设计语言》25 章 28123 处边界中 23181 处带分隔空格）。
     *
     * 断言一律用 `delta == 0f` 且**零容差**：这里要的就是「逐位相同」。
     */
    @Test
    fun `0 档照吃分隔空格且注入零间隙`() {
        val fs = 40f
        val corpus = listOf("SPACED" to SPACED, "TIGHT" to TIGHT, "DOUBLE" to "中  A", "NBSP" to "中\u00A0A")
        for ((name, text) in corpus) {
            val none = noGaps(text, fs)
            val zero = advances(text, fs = fs, gapEm = 0f)
            val full = advances(text, fs = fs, gapEm = GAP)
            val g0 = CjkLatinSpacing.gaps(text, 0f)
            assertTrue("$name：语料必须真的检出边界（否则本锁是空断言）", g0.isNotEmpty())
            // ① 逐槽 = 无间隙那份把被吃空格位清零（**零容差**）。
            val expect = none.copyOf()
            val eaten = StringBuilder()
            for (g in g0) {
                for (k in 1..g.spaceCount) {
                    eaten.append(' ').append(g.leftIndex + k)
                    expect[g.leftIndex + k] = 0f
                }
            }
            assertEquals(
                "$name：0 档的逐槽 advance 必须**恰好**等于无间隙那份清零 [${eaten}] 后的结果",
                0f, delta(expect, zero),
            )
            // ② 与 $GAP 档的差**只**落在各边界左槽（注入宽度真的按 0 注入）。
            for (k in text.indices) {
                val expectD = if (g0.any { it.leftIndex == k }) GAP * fs else 0f
                assertEquals(
                    "$name：槽 $k 上 0 档与 $GAP 档的差必须是 $expectD（被吃空格位也在内，一个字节都不许动）",
                    expectD, full[k] - zero[k], 1e-3f,
                )
            }
            // ③ 反证：无分隔空格的语料，0 档与无间隙**逐位**相同（清零确实来自 spaceCount）。
            if (g0.none { it.spaceCount > 0 }) {
                assertEquals("$name：无分隔空格时 0 档必须与无间隙逐位相同", 0f, delta(none, zero))
            } else {
                assertTrue("$name：0 档必须真的比无间隙窄（吃掉空格那个动作发生了）", sum(zero) < sum(none))
            }
        }
    }

    /**
     * **0 档的中西墨距真的是 0**（需求原话：「就让距离为 0 就可以了」）—— 端到端一层。
     *
     * 走完整管线（[LineAligner] → [KerningClusterTable.clusterXs] → `graftKerningOnto`），
     * 量的是**用户看得见的那件事**：边界左邻字的墨右缘到右邻字墨左缘的那段白。
     *
     * 0 档必须**恰好 0.000px**（不是「差不多小」），`$GAP` 档必须**恰好 `GAP × 字号`**。
     * 语料用**相邻**边界（作者没打空格）—— 带空格的形态在 0 档连那串空格一起没了，
     * 墨距自然是 0，但判别点会被「吃空格」抢走（改动 [CjkLatinSpacing] 的空格规则时它照样绿）。
     *
     * 用 LEFT 对齐排除 JUSTIFY 拉伸的干扰。
     */
    @Test
    fun `0 档的中西墨距真的为 0`() {
        val fs = 40f
        val table = KerningClusterTable()

        /** 走完整管线取 [s, e) 这一段的墨位 x。 */
        fun ink(para: String, s: Int, e: Int, gapEm: Float): FloatArray {
            val p = LineAligner().align(
                para, s until e, fs, 400f, 0f, "p", SYNTH_FAM, 400, false, false,
                emptyList(), TextAlign.LEFT, 0f, false, false, gapEm,
            )
            assertTrue("[$s,$e) 含 Latin 却没有走簇位轨，否则本锁测不到出问题的那条路径", table.needsClusters(para, s, e))
            val cnat = table.clusterXs(para, s, e, fs, 1.5f, "p", SYNTH_FAM, 400, false, false, emptyList(), 0f, 0f, gapEm)
            assertNotNull("[$s,$e) 必须建得出簇位轨", cnat)
            return graftKerningOnto(p, cnat!!, para, s, fs, 0f, emptyList(), Float.NaN).xs
        }

        val para = "前面这段中文只是为了让段首偏移推大，末尾进入中文Rust混排的句子"
        val g = CjkLatinSpacing.gaps(para, GAP).first { it.leftIndex > 20 }
        assertEquals("本锁必须取一个**相邻**边界（无分隔空格），否则判别点会被「吃空格」抢走", 0, g.spaceCount)
        val s = g.leftIndex
        val e = s + 2
        val x0 = ink(para, s, e, 0f)
        val x1 = ink(para, s, e, GAP)
        val wLeft = sum(noGaps(para.substring(s, s + 1), fs))
        assertEquals(
            "0 档：中英之间必须是**零**白（需求原话「就让距离为 0」）",
            0f, (x0[1] - x0[0]) - wLeft, 1e-2f,
        )
        assertEquals(
            "$GAP 档：中英之间必须恰好一个 gap（否则本锁前半段测的是「恒 0」的空断言）",
            GAP * fs, (x1[1] - x1[0]) - wLeft, 1e-2f,
        )
    }

    /** 显式传**空列表**与**不传该形参**同路（这一层与 0 档无关：`advances` 该形参的默认值就是空列表）。 */
    @Test
    fun `空间隙列表与不传形参同路`() {
        val text = "中文English混排 42 项"
        val a = measurer().advances(text, 40f, 0f, "p", SYNTH_FAM, 400, false, false, emptyList(), emptyList())
        val b = measurer().advances(text, 40f, 0f, "p", SYNTH_FAM, 400, false, false)
        assertEquals(0f, delta(a, b))
    }

    /**
     * **0 档的预留比无间隙少掉那串空格** —— 断行侧那一份（量 == 画同源）。
     *
     * 断行侧走 [InhouseParagraphBreaker]（整段检出 → [SkiaRunMeasurer] 施加），绘制侧走
     * [LineAligner] / [KerningClusterTable]（[CjkLatinSpacing.gapsForRange] 裁到本行）。
     * 若断行侧对 0 短路而绘制侧不短路（或反过来），就出现「预留按有空格算、画按零宽画」
     * ⇒ 画比量**窄**（右缘莫名留白）或**宽**（右溢被裁）。本锁钉住 0 档下两侧同为「零宽」。
     *
     * ## ⚠ 语料**必须不含全角标点**（第一版踩了这个坑，报 −77.7 / 实测 −166.5）
     *
     * 这里量的是 [InhouseParagraphBreaker.preferredWidth]（max-content 口径），而它**额外减掉
     * 标点挤压**（见 `PunctuationSqueeze`）。拿 [SPACED]（含 `，`/`。`）当语料时，
     * 差值里混着挤压量，于是「少掉 7 个空格（77.7px）」被读成「少掉 15 个空格（166.5px）」——
     * 两个数都对，只是量的不是同一件事。⇒ 语料换成**纯中英 + 分隔空格**，一个标点都不放。
     * 顺带记一笔：挤压与混排字距是两个独立特性，**不要在一个断言里同时量**。
     *
     * ⚠ 上面那条只钉住 `preferredWidth`；`breakLines` 是**另一处**检出点，本类另有一条
     *   `0 档的断行预留按零宽空格 阈值处由两行变一行` 钉它（第一版漏了它 ⇒ 变异验证时把
     *   `breakLines` 的短路改回去，本类**全绿**，判别力为零 —— 这条纪律记在这里）。
     */
    @Test
    fun `0 档的预留比无间隙少掉那串空格`() {
        val fs = 44.4f
        for (text in listOf("中 A", "中\u00A0A", "中文 Rust 的所有权 与 GC 的回收 完全不同")) {
            val gaps = CjkLatinSpacing.gaps(text, 0f)
            val none = noGaps(text, fs)
            var eatenW = 0f
            for (g in gaps) for (k in 1..g.spaceCount) eatenW += none[g.leftIndex + k]
            assertTrue("语料「${text.take(6)}…」必须含被吃的分隔空格（否则本锁是空断言）", eatenW > 1f)
            assertEquals(
                "0 档的整段预留必须比无间隙少掉恰好那串空格宽（断行侧不许对 0 短路）",
                -eatenW,
                naturalWidth(text, fs, 0f) - sum(none),
                1e-2f,
            )
            // 绘制侧同一件事：`LineAligner` 在 0 档也必须把空格画成零宽。
            // ⚠ 这条是**画侧**唯一的 0 档判别点：逐行「画 == 量」那条抓不到画侧短路
            //   （`gapsForRange` 与 `pAdv` 都走未变异的路径，两边一起对、照样绿）——
            //   变异验证时把 `LineAligner` 的 `> 0f` 短路改回去，本类只有这一条会红。
            // 版心取极大值 ⇒ 不折行、不 JUSTIFY，LEFT 下 `visibleRight = Σ adv`。
            assertEquals(
                "0 档的可见右缘（绘制侧）必须同样少掉那串空格宽（画侧不许对 0 短路）",
                sum(none) - eatenW,
                align(text, fs = fs, width = 1e6f, gapEm = 0f).visibleRight,
                1e-2f,
            )
            // 逐行「画 == 量」在 0 档同样成立（0 也走 `gapsForRange`）。
            val pAdv = measurer().advances(
                text, fs, 0f, "p", SYNTH_FAM, 400, false, false, emptyList(), gaps,
            )
            for (w in 700..900 step 50) {
                for (line in InhouseParagraphBreaker(0f, 0f)
                    .breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)) {
                    val (s, e) = line.range.first to line.range.last + 1
                    val lineGaps = CjkLatinSpacing.gapsForRange(text, s, e, 0f)
                    val drawn = sum(advances(text.substring(s, e), fs = fs, gaps = lineGaps))
                    val reserved = sum(pAdv.copyOfRange(s, e))
                    assertEquals("0 档 版心 $w 行 [$s,$e) 画宽必须逐位等于预留", reserved, drawn, 1e-2f)
                }
            }
        }
    }

    /**
     * **0 档的断行预留按「零宽空格」算** —— 阈值处由两行变一行。
     *
     * ## 为什么必须单独钉 `breakLines`（第一版的漏洞，变异验证抓出来的）
     *
     * 上一条量的是 [InhouseParagraphBreaker.preferredWidth]，它走的是**另一个**检出点。
     * 把 `breakLines` 里的 `if (cjkLatinSpacingEm > 0f)` 短路改回去，本类**全绿** ——
     * 因为本类其余断行侧断言都只比「0 档 vs GAP 档」，而短路对这两档的影响恰好被
     * `gapsForRange`（绘制侧，仍然检出）吸收掉。判别力为零。
     *
     * 本锁换一种**不依赖另一侧**的判据：把版心取在「0 档宽」与「不处理宽」的正中间。
     * 该版心下：
     * - 正确（预留按零宽空格）⇒ 整段装得下 ⇒ **1 行**；
     * - 短路（预留按原空格宽）⇒ 装不下 ⇒ **2 行**。
     *
     * ## 阈值不是拍脑袋：`w = (nat0 + natNone) / 2`，两侧各差 `K × 空格宽`
     *
     * 语料 `"中 A"×K` 的每个单元在 0 档宽 `2em`、在不处理口径下宽 `2em + 一个空格宽`
     * （本机族栈的空格恰是 0.25em）。取 K = 20 ⇒ 判别窗口宽 20 个空格宽，很宽裕。
     *
     * ## 为什么语料**只**用带空格的形状
     *
     * 「相邻」边界（`中A`）根本没有空格可吃 ⇒ 短路与不短路**完全同路** ⇒ 本锁必须避开。
     * 反过来 0.25em（= 空格宽）那个不动点也不影响本锁：它让 **0.25em 档**与不处理同宽，
     * 而本锁比的是 **0 档**与不处理（差 `K` 个空格宽），不是 0.25em 档。
     */
    @Test
    fun `0 档的断行预留按零宽空格 阈值处由两行变一行`() {
        val fs = 44.4f
        val k = 20
        val text = "中 A".repeat(k)
        val natNone = sum(noGaps(text, fs))
        val nat0 = naturalWidth(text, fs, 0f)
        assertEquals(
            "0 档的自然宽必须比不处理口径恰好少掉 $k 个分隔空格宽",
            -k * noGaps(" ", fs)[0],
            nat0 - natNone,
            1e-2f,
        )
        val w = ((nat0 + natNone) / 2f).toInt()
        assertTrue("阈值必须落在 (0档宽, 不处理宽) 开区间内", w > nat0 && w < natNone)
        val lines = InhouseParagraphBreaker(0f, 0f)
            .breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)
        assertEquals(
            "0 档下整段必须装得下（断行侧若对 0 短路，预留会按原空格宽算 ⇒ 这里会是 2 行）",
            1, lines.size,
        )
        assertEquals("单行必须覆盖整段（否则本锁的语料没按预期退化成一行）", 0 to text.length - 1, lines[0].range.first to lines[0].range.last)
    }

    /**
     * 需求（2026-10-03）「滑块为 0 时不要做多余动作」的**上游半边**：`cjkLatinSpacing` 是 0 时，
     * [TypographicProfile] 交给排版层的那个 em 必须严格是 0，且默认档 25 必须映射成 0.25em。
     *
     * 为什么放在这里而不是 `TypographicProfileTest`：本类验的是「**排版层真正收到的那个数**」，
     * 也就是滑块 → 版面这条链的源头；`cjkLatinSpacingEmApplied` 的闸门本身
     * （`AbSwitch.inhouseBreak()` 关着时恒 0）在 [断行器只按 inhouse 开关加间隙] 那条锁里。
     */
    @Test
    fun `滑块值到 em 的映射 0 严格为 0 且 25 是 0_25em`() {
        fun emOf(slider: Double): Float =
            TypographicProfile.build(ReaderSettings.DEFAULT.copy(cjkLatinSpacing = slider)).cjkLatinSpacingEm
        assertEquals("滑块 0 ⇒ em 严格为 0", 0f, emOf(0.0))
        assertEquals("滑块 25 ⇒ 0.25em", 0.25f, emOf(25.0), 1e-6f)
        assertEquals("滑块 100 ⇒ 1.0em", 1f, emOf(100.0), 1e-6f)
        assertEquals(
            "越界值必须被夹到 0..100（负档不许变成反向的负间隙）",
            0f, emOf(-30.0),
        )
        assertEquals("越界值必须被夹到 1.0em", 1f, emOf(1000.0), 1e-6f)
        assertEquals(
            "设置默认值必须是 25（否则新装用户拿到的不是 CLREQ 值）",
            25.0, ReaderSettings.DEFAULT.cjkLatinSpacing, 0.0,
        )
    }

    // ---- 锁 2：唯一施加点 = `advances` 出口，且加在**左**字位 ----

    /**
     * 间隙加在 `leftIndex`（边界**左侧**那个字）的 advance 上，右邻字位**不动**。
     *
     * ## 为什么方向必须钉死（加错一侧不会崩、只会难看）
     *
     * `x_i = x_0 + Σ_{j<i} adv[j]`：把 `+gap` 放在 `leftIndex` ⇒ 右边全体右移 gap；
     * 放在 `leftIndex + 1` ⇒ 连 `leftIndex` 自己都右移，等于在**左边**多塞一个间隙，
     * 而 `leftIndex` 与 `leftIndex+1` 之间的实际间距仍是原值 ⇒ 看起来是「两个字各自被推开」，
     * 中西之间反而**没有**间隙。两侧总宽相同（都是 +gap）⇒ 只断言总宽的锁照样绿，
     * 只有本锁（逐字位）能抓到。
     */
    @Test
    fun `间隙加在左字位 右邻字位不动`() {
        val text = "中文ab"
        val base = noGaps(text)
        val gapped = advances(text, gapEm = GAP)
        val gaps = CjkLatinSpacing.gaps(text, GAP)
        assertEquals("本锁的语料必须恰好产出一个间隙，否则断言无意义", 1, gaps.size)
        val left = gaps[0].leftIndex
        assertEquals("「中文ab」的边界在『文』之后", 1, left)
        assertEquals("左字位必须加满一个 gap", GAP * 40f, gapped[left] - base[left], 1e-4f)
        assertEquals(
            "右邻字位一个字节都不能动（加在这里就等于把间隔画到了左边）",
            0f,
            delta(base.copyOfRange(left + 1, base.size), gapped.copyOfRange(left + 1, gapped.size)),
        )
    }

    /**
     * 总宽必须**恰好多出 `间隙个数 × gapEm × 字号`**（每个间隙一份，不是每字一份）。
     *
     * 语料的边界数是**逐字符手数**的（不是抄探测器输出，否则锁就是自证的）：
     * `中文ab` = 文↔a（1）；`中文ab中文` = 文↔a、b↔中（2）；
     * `ab中文ab中文cd` = b↔中、文↔a、b↔中、文↔c（**4**，易少数一处）。
     */
    @Test
    fun `总宽恰好按间隙个数递增`() {
        val fs = 40f
        val cases = listOf(
            "中文ab" to 1,
            "中文ab中文" to 2,
            "ab中文ab中文cd" to 4,
        )
        for ((text, expectGaps) in cases) {
            val gaps = CjkLatinSpacing.gaps(text, GAP)
            assertEquals("语料「$text」的边界数", expectGaps, gaps.size)
            val d = sum(advances(text, fs = fs, gapEm = GAP)) - sum(noGaps(text, fs))
            assertEquals(
                "语料「$text」：$expectGaps 个边界 ⇒ 总宽多 $expectGaps × 0.25em",
                expectGaps * GAP * fs,
                d,
                1e-3f,
            )
        }
    }

    // ---- 锁 3：断行侧必须为间隙预留版心（否则右溢被裁）----

    /**
     * 断行器必须把间隙算进版心判定：宽度落在「无间隙宽」与「有间隙宽」之间时，
     * 开启间隙后必须从「一行」变成「两行」。
     *
     * ## 为什么这条是最要命的一条
     *
     * `NoLineExceedsContentWidthTest` 钉的硬约束是「画出来的宽 ≤ 版心」。若断行侧不预留
     * 而绘制侧照插，版心就是被**超**的那一方 ⇒ 分页阅读器没有横向滚动条，
     * 超出部分被页面裁掉 = **内容丢失**。
     *
     * ## 阈值怎么取（不能拍脑袋）
     *
     * 先量出 `nat0`（无间隙自然宽）与 `nat1`（有间隙自然宽），再取 `w = (nat0 + nat1) / 2`
     * —— 该宽度下「不预留」判一行、「预留」判两行，是判据最锐利的那个点。
     */
    @Test
    fun `断行侧为间隙预留版心 阈值处由一行变两行`() {
        val fs = 40f
        val text = "中文ab" + "cd".repeat(6)
        val nat0 = naturalWidth(text, fs, 0f)
        val nat1 = naturalWidth(text, fs, GAP)
        assertTrue("本锁的语料必须真有间隙（否则阈值退化成同一个数）", nat1 > nat0)
        val w = ((nat0 + nat1) / 2f).toInt()
        val off = InhouseParagraphBreaker(0f, 0f)
            .breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)
        val on = InhouseParagraphBreaker(0f, GAP)
            .breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)
        assertEquals("gap=0 且宽度取中值时应装得下（单行）", 1, off.size)
        assertEquals("gap≠0 且同一宽度下必须折行（间隙占了版心）", 2, on.size)
        // 折行后每行仍不越版心（预留真的生效，而不是靠 R1 core 硬切蒙混过关）。
        for (line in on) {
            val sub = text.substring(line.range.first, line.range.last + 1)
            val wLine = naturalWidth(sub, fs, GAP)
            assertTrue("行 ${line.range} 宽 $wLine 越版心 $w", wLine <= w)
        }
    }

    /**
     * 开关关闭时（回退阀）：间隙在**量宽**侧也必须为 0，否则断行按有间隙算、画却无间隙。
     *
     * 方向不能反：「画了但没预留」正是右溢被裁的成因（见上一条的类 KDoc）。
     * 所以闸门必须落在**键与 profile** 那一侧（[TypographicProfile.cjkLatinSpacingEmApplied]），
     * 让断行与绘制**一致地**退干净。
     *
     * ⚠ 「退干净」现在只意味着**两侧拿到同一个数**（0），不再意味着「什么都不做」：
     *   0 是零间隙档、仍会吃分隔空格（产品口径 2026-10-03）。两侧同为 0 ⇒ 两侧吃同一批空格、
     *   都注入 0 宽间隙 ⇒ 仍然「画 == 量」。本锁守的就是这个「同值」，不是「零动作」。
     */
    @Test
    fun `开关关闭时两侧拿到同一个 0`() {
        try {
            AbSwitch.resetForTest()
            AbSwitch.apply("inhouseBreak=1")
            val on = TypographicProfile.build(ReaderSettings.DEFAULT).cjkLatinSpacingEmApplied
            assertEquals("开关开时必须真的施加间隙（否则本锁是空断言）", GAP, on, 1e-6f)
            AbSwitch.apply("inhouseBreak=0")
            val off = TypographicProfile.build(ReaderSettings.DEFAULT).cjkLatinSpacingEmApplied
            assertEquals(
                "开关关时必须整条关掉（0），而不是「画了但没预留」—— 量与画必须一致地退干净",
                0f, off,
            )
        } finally {
            AbSwitch.resetForTest()
        }
    }

    // ---- 锁 4：`LineAligner` 的逐字 x 必须被间隙推开 ----

    /**
     * `LineAligner` 的 `xs` 必须在边界处被推开，且推开的量恰为一个 gap。
     *
     * [LineAligner] 自己检测一遍间隙并传给 `advances`（而不是复用断行器那份）——
     * 因为它吃的是**行的子串**，坐标系与整段不同。锁的是「x 的相对位移」这一层结果，
     * 不关心它从哪条路算出来。
     */
    @Test
    fun `LineAligner 的逐字 x 被间隙推开`() {
        val text = "中文ab"
        val p0 = align(text)
        val p1 = align(text, gapEm = GAP)
        assertEquals("xs 与 range 必须同长（区间无缝是硬约束）", text.length, p1.xs.size)
        // 左对齐 ⇒ x0 恒 0，故可直接逐位比。
        assertEquals("首字 x 不受间隙影响", 0f, p1.xs[0], 1e-4f)
        assertEquals("边界左邻（『文』）的 x 不动", 0f, p1.xs[1] - p0.xs[1], 1e-4f)
        assertEquals("边界右邻（『a』）必须被推开恰好一个 gap", GAP * 40f, p1.xs[2] - p0.xs[2], 1e-3f)
        assertEquals("其后所有字累计同一个位移", GAP * 40f, p1.xs[3] - p0.xs[3], 1e-3f)
        // 逐字相接（区间无缝的第二条）：x[i+1] == x[i] + adv[i]，advance 里已含间隙。
        for (k in 0 until text.length - 1) {
            assertEquals(
                "x[$k+1] 必须等于 x[$k] + adv[$k]（含间隙）",
                p1.xs[k] + p1.advs[k], p1.xs[k + 1], 1e-3f,
            )
        }
    }

    /**
     * 行**末尾**那个边界字（右邻在下一行）的间隙归谁。
     *
     * ## 这条锁钉的是 2026-10-03 修掉的那个真机缺陷
     *
     * 旧实现绘制侧按**行子串**检测，于是「左槽是本行末字」的间隙整个丢掉：断行侧预留了它、
     * 绘制侧不画它 ⇒ 画比量窄。症状是那句「英文与中文之间距离不受滑块控制」——
     * 那个间隙永远是 0，与滑块无关。
     *
     * 现在两侧同源（整段检测 + 裁到本行）⇒ 本行**照画**，逐行「画 == 量」。
     * 下面两条分别钉住「本行照画」与「画 == 量」，再加第三条钉住**下一行不重复画**。
     */
    @Test
    fun `跨行边界的间隙本行不画 西文那头在下一行时宽度为0`() {
        val text = "A中A中"
        val fs = 40f
        val paragraphSpacings = CjkLatinSpacing.gaps(text, GAP)
        assertEquals("整段上有三个间隙（每对相邻字都是中西边界）", 3, paragraphSpacings.size)
        assertEquals("依次落在 0/1/2", listOf(0, 1, 2), paragraphSpacings.map { it.leftIndex })

        // 行 = [0,2) = 「A中」，其末字 `中`(1) 的右邻 `A`(2) 在下一行。
        val s = 0
        val e = 2
        val lineGaps = CjkLatinSpacing.gapsForRange(text, s, e, GAP)
        assertEquals(
            "本行必须拿到两条（左槽 0 与 1）—— 空间隙的位置仍属本行",
            listOf(0, 1),
            lineGaps.map { it.leftIndex },
        )
        assertEquals(
            "末槽那条（右邻在下一行）宽度必须压 0：右侧没有墨，那段宽是版心里凭空多出的空白",
            listOf(GAP, 0f),
            lineGaps.map { it.gapEm },
        )
        val drawn = sum(advances(text.substring(s, e), fs = fs, gaps = lineGaps))
        val reserved = sum(paragraphAdv(text, fs = fs, gapEm = GAP).copyOfRange(s, e))
        assertEquals(
            "画比量**恰好窄一个悬空间隙**（方向安全：画 ≤ 量，永不右溢）",
            reserved - GAP * fs,
            drawn,
            1e-2f,
        )

        // 下一行 [2,4) 只拿到自己那条（左槽 2），左槽 1 那条归上一行 —— 不重复。
        assertEquals(
            listOf(0),
            CjkLatinSpacing.gapsForRange(text, 2, 4, GAP).map { it.leftIndex },
        )
    }

    /**
     * 上面那条不变式的**扫版心版**：真断行器切出来的每一行都必须**画 == 量**。
     *
     * ## 为什么现在能钉成等号而不是「至多一个 gap」
     *
     * 旧口径（绘制侧按行子串检测）下，跨行的间隙归预留侧、本行不画 ⇒ 缺口至多一个 gap。
     * 现在两侧同源 ⇒ 每一行拿到的都是**整段检出裁到本行**的那份，逐位相等。
     *
     * ## 唯一允许的不对称：行末悬空间隙（画比量**窄** ≤ 一个 `gapEm`）
     *
     * 断点落在中西接缝上时，西文那一头在下一行 ⇒ 那段宽右侧没有墨 ⇒ 绘制侧不画
     * （[CjkLatinSpacing.gapsForRange] 把它的 `gapEm` 压 0，**分隔空格照吃**）。
     * 断行侧仍按整段预留，于是这些行画得比预留窄**恰好一个 `gapEm`** —— 方向安全（永不右溢），
     * 实测真书 1673/30956 行（5.4%）、总量 0.067% 行文本，肉眼不可见。
     *
     * ## 另一种不对称，以及它为什么**够不到**
     *
     * 「间隙的左槽在**上一行**、被吃掉的空格落在本行开头」会让本行画得比预留**宽**一个空格宽。
     * 它要求断点落在「边界字」与「它后面那串分隔空格」之间 —— UAX#14 LB 禁止在空格之前断行
     * （`× SP`），本仓断行器据此把行尾空白**悬挂**在行区间内。实测真书《Rust 程序设计语言》
     * 8738 段 / 29151 行里，**以分隔空格开头的行 = 0 行**。
     */
    @Test
    fun `真断行器切出的每一行 画宽等于预留 减去至多一个行末悬空间隙`() {
        val fs = 40f
        var sawLineEndBoundary = false
        for (text in listOf("A中A中", TIGHT, SPACED, "中 A中文 的所有权 A", "Rust 的所有权，中文", "本书假设你使用的是 Rust 1.90.0 版本")) {
            val pAdv = paragraphAdv(text, fs = fs, gapEm = GAP)
            val pGaps = CjkLatinSpacing.gaps(text, GAP)
            for (w in 200..400 step 20) {
                for (line in InhouseParagraphBreaker(0f, GAP)
                    .breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)) {
                    val s = line.range.first
                    val e = line.range.last + 1
                    val lineGaps = CjkLatinSpacing.gapsForRange(text, s, e, GAP)
                    // 行末悬空间隙：左槽在本行、而西文那一头（`leftIndex + 1 + spaceCount`）在行外。
                    // 至多一条（断点唯一），且它的宽度在绘制侧被压 0 ⇒ 画比预留窄**恰好**这么宽。
                    val stranded = pGaps.filter { g ->
                        g.leftIndex in s until e && g.leftIndex + 1 + g.spaceCount >= e
                    }
                    if (stranded.isNotEmpty()) sawLineEndBoundary = true
                    val drawn = sum(advances(text.substring(s, e), fs = fs, gaps = lineGaps))
                    val reserved = sum(pAdv.copyOfRange(s, e))
                    val expected = reserved - stranded.sumOf { (it.gapEm * fs).toDouble() }.toFloat()
                    assertTrue(
                        "版心 $w 行 [$s,$e) 至多一条行末悬空间隙（实测 ${stranded.size} 条）",
                        stranded.size <= 1,
                    )
                    assertEquals(
                        "版心 $w 行 [$s,$e) 画宽 $drawn 必须等于预留 $reserved 减去悬空间隙",
                        expected,
                        drawn,
                        1e-2f,
                    )
                }
            }
        }
        assertTrue(
            "本锁必须至少扫到一次「西文那头在下一行」的间隙，否则它退化成一个空断言",
            sawLineEndBoundary,
        )
    }

    /** 行**内**的间隙必须照常计入可见右缘（不能被任何「挂掉」规则误伤）。 */
    @Test
    fun `行内间隙计入可见右缘`() {
        // 「中A中」：间隙在 index 0 与 index 1 之间，右邻都是**可见字**，不是尾随空白。
        val text = "中A中"
        val gaps = CjkLatinSpacing.gaps(text, GAP)
        assertEquals("语料有两个行内间隙", 2, gaps.size)
        val p = align(text, gapEm = GAP)
        val p0 = align(text, gapEm = 0f)
        assertEquals("累计位移 = 两个 gap（末尾字位也在其中）", 2f * GAP * 40f, p.visibleRight - p0.visibleRight, 1e-2f)
        assertEquals("末字之后的间隙不影响右缘（xs 循环到末槽为止）", text.length, p.xs.size)
    }

    /** 行末尾随文档空白时，可见右缘必须停在**空白段之前**（尾随空白不计入可见宽）。 */
    @Test
    fun `行末尾随空白不计入可见右缘`() {
        val text = "中A " // 可见段「中A」+ 一个尾随分隔空格
        val p = align(text, gapEm = GAP)
        val p0 = align(text, gapEm = 0f)
        assertEquals("尾随空白的 x 起点 = 可见右缘", p.visibleRight, p.trailStartX, 1e-4f)
        // 「中A」上有一个非 suppress 间隙（『中』↔『A』）⇒ 右缘推出一个 gap。
        assertEquals("行内间隙照常计入可见右缘", GAP * 40f, p.visibleRight - p0.visibleRight, 1e-3f)
    }

    // ---- 锁 5：`KerningClusterTable` 的簇位轨必须带上间隙 ----

    /**
     * 含 Latin 的行走簇位轨（`graftKerningOnto` 的数据源），轨上必须带间隙。
     *
     * ## 不带会怎样（这是最隐蔽的一条）
     *
     * `graftKerningOnto` 的收紧量 `tighten(i) = (cnat[i] − cnat[i−1]) − placement.advs[i−1]`：
     * 间隙已进 `advs` 而 `cnat` 里没有 ⇒ `tighten` 恒为负（正好一个间隙）⇒ 被 `min(0, ·)`
     * 全额采纳 ⇒ **刚注入的间隙在绘制侧被抹掉**。症状极具迷惑性：
     * 「滑块一动，断行变了（多了一行），但字距看着没变」—— 正是用户报的「不起作用」。
     *
     * 所以本锁不只断言「轨上带了间隙」，还断言**嫁接后间隙仍在**
     * （即 `tighten` 在边界处回到 0，而不是负一个 gap）。
     */
    @Test
    fun `簇位轨带上间隙且嫁接后不被抹掉`() {
        val text = "中文ab"
        val n = text.length
        val table = KerningClusterTable()
        val t0 = table.clusterXs(text, 0, n, 40f, 1.5f, "p", SYNTH_FAM, 400, false, false)
        val t1 = table.clusterXs(
            text, 0, n, 40f, 1.5f, "p", SYNTH_FAM, 400, false, false, emptyList(), 0f, 0f, GAP,
        )
        assertNotNull("含 Latin 的行必须建得出簇位轨", t0)
        assertNotNull("含 Latin 的行必须建得出簇位轨（开间隙）", t1)
        val c0 = t0!!
        val c1 = t1!!
        assertEquals("轨长必须与 range 同长", n, c1.size)
        assertEquals("左邻（『文』）的簇位不动", 0f, c1[1] - c0[1], 1e-3f)
        assertEquals("右邻（『a』）必须被推开恰好一个 gap", GAP * 40f, c1[2] - c0[2], 1e-3f)
        assertEquals("其后累计同一位移", GAP * 40f, c1[3] - c0[3], 1e-3f)

        // 嫁接后：收紧量在边界处必须为 0（不是 −gap）。
        val off = align(text)
        val on = align(text, gapEm = GAP)
        val grafted = graftKerningOnto(on, c1, text, 0, 40f, 0f, emptyList(), Float.NaN)
        val g = grafted.xs
        val tightenAtBoundary = (g[2] - g[1]) - on.advs[1]
        assertEquals(
            "边界处的收紧量必须为 0（间隙已在轨里）；负一个 gap 就说明轨没带间隙、被 min(0,·) 吃掉",
            0f, tightenAtBoundary, 1e-3f,
        )
        assertEquals(
            "开间隙后『文』自己那份 advance 必须多一个 gap（整条链接通了才可能）",
            GAP * 40f, on.advs[1] - off.advs[1], 1e-3f,
        )
        assertEquals(
            "嫁接后『右邻 x − 左邻 x』= 左邻字的 advance（`adv[1]` 里**已含**间隙：40 + 10 = 50）",
            on.advs[1], g[2] - g[1], 1e-3f,
        )
        assertEquals(
            "左邻字位自身不动（间隙只推开右边的字；单独钉住上一条的另一侧）",
            0f, on.xs[1] - off.xs[1], 1e-3f,
        )
        // ⚠ 上面两条**不能**写成 `assertEquals(adv[1] + GAP*40, g[2]-g[1])`（错：`adv[1]` 里已含间隙 ⇒ 实测 50 ≠ 60），
        //   也不能写成 `assertEquals(GAP*40, g[2]-g[1])`（错：量纲不同，那是**步进**不是累计位移）。
        //   `g[2]-g[1]` = 「左邻字位左缘 → 右邻字位左缘」的整段步进 = `adv[1]`。
    }

    /**
     * 上一条锁的**回归锁**：它必须用**非零 `start`**（真实段内偏移）再跑一遍。
     *
     * ## 为什么上一条锁在 `start = 0` 时是绿的，而真机是坏的
     *
     * `shiftTrackByCjkGaps` 的累计位移判据写的是 `gaps[gi].leftIndex < abs`，而 `abs = start + j`。
     * `gaps` 给的是**行内局部**下标（`gapsForRange` 已平移），于是这一句在 `start == 0` 时
     * 恰好退化成正确的 `leftIndex < j` —— **只在首行成立**。
     * 段首 `start` 一旦是几百/几千（真书里每段的第 2 行往后都是），`leftIndex < abs` 对
     * **任何** `j ≥ 0` 都成立 ⇒ 每条间隙都在 `j == 0` 就被计入 ⇒ 整条轨被**同一个常量**平移 ⇒
     * `cnat[i] − cnat[i−1]` 的逐槽差分里**根本没有间隙**（常量在相减时消掉）⇒
     * `graftKerningOnto` 的 `tighten` 在每个间隙位恒为 −gap ⇒ 被 `min(0, ·)` 全额采纳
     * ⇒ **注入的间隙在落墨侧被整条抹掉**。
     *
     * 这就是真机症状「有些起作用、有些不起作用」的成因：纯 CJK 行不走簇位轨
     * （[KerningClusterTable.needsClusters] 为 false）⇒ 用 `placement.xs` ⇒ 间隙正常；
     * 含 Latin 的行走簇位轨 ⇒ 间隙全灭。中西边界**必然**含 Latin，所以实际上「几乎全灭」。
     *
     * 实测（真书《Rust 程序设计语言》8738 段 / 29151 行，走完整绘制管线）：修复前 18990 个
     * 行内中英边界里 **18743 个（98.7%）墨位一动不动**（应为 32.81px）；修复后 18990/18990 精确。
     */
    @Test
    fun `簇位轨在非零段内偏移下仍逐槽带上间隙`() {
        val para = "这是段首的中文内容，中文之后紧跟 Rust 混排，中文 Rust " +
            "填充中文与 Rust 让这一段足够长以便取到靠后的行"
        val fs = 40f
        val table = KerningClusterTable()
        val gaps = CjkLatinSpacing.gaps(para, GAP)
        assertTrue("语料必须含多个间隙", gaps.size >= 6)
        var checked = 0
        for (lineStart in listOf(0, 1, 37, 120)) {
            val end = lineStart + 6
            if (end > para.length) continue
            val inRange = gaps.filter { it.leftIndex in lineStart until end }
            if (inRange.isEmpty()) continue
            val c1 = table.clusterXs(
                para, lineStart, end, fs, 1.5f, "p", SYNTH_FAM, 400, false, false,
                emptyList(), 0f, 0f, GAP,
            )
            // 0 档那份：注意它**也**吃分隔空格，所以「两条轨只差注入间隙」这个不变式不受影响。
            val c0 = table.clusterXs(
                para, lineStart, end, fs, 1.5f, "p", SYNTH_FAM, 400, false, false,
                emptyList(), 0f, 0f, 0f,
            )
            assertNotNull("start=$lineStart 的含 Latin 行必须建得出轨", c1)
            assertNotNull("start=$lineStart 0 档时也必须建得出轨（否则本锁是空断言）", c0)
            val t1 = c1!!
            val t0 = c0!!
            // 逐槽期望 = 「局部下标严格小于本槽的间隙条数」× 一个 gap —— 这就是那个**阶跃函数**。
            // ⚠ 不能写成「间隙位自己不动、其后每一位都恰好一个 gap」：同一区间里有**两条**间隙时，
            //   第二条的左槽早已被第一条推开，那一槽断言 0 会恒假（这条锁第一版就踩了这个坑）。
            for (k in t1.indices) {
                val expect = inRange.count { it.leftIndex < lineStart + k } * GAP * fs
                assertEquals(
                    "start=$lineStart 局部槽 $k 的累计位移必须等于它**前面**的间隙条数 × gap" +
                        "（坐标系混用会让整条轨被同一个常量平移 ⇒ 这里实测 0）",
                    expect, t1[k] - t0[k], 1e-3f,
                )
            }
            checked += inRange.size
        }
        assertTrue("本锁必须至少检查到一条间隙，否则它退化成一个空断言", checked > 0)
    }

    /**
     * **端到端**：滑块一动，走完整绘制管线的**墨位**就必须动（真机症状的直接反锁）。
     *
     * 管线 = [LineAligner]（量 x）→ [KerningClusterTable.clusterXs]（簇位轨）
     * → `graftKerningOnto`（嫁接收紧量）—— 就是 [LineWindowDrawer.paintGlyphs] 的那三步。
     *
     * 断言的是**用户看得见的那件事**：把边界字右邻的落墨 x，在两档滑块之间比，
     * 必须恰好差 `Δgap × 字号 × 该位之前的间隙条数`。「字距不动」正是修复前的表现（实测 0.00）。
     *
     * ## 为什么分成「相邻」与「空格分隔」两例，且第二例刻意避开 0.25em
     *
     * - **相邻**（`中文Rust`）：基准里没有空格 ⇒ 没有不动点 ⇒ 任何两档都该线性响应。
     * - **空格分隔**（`中文 Rust`）：作者那个空格会被**删除**、换成注入的间隙，于是
     *   「间隙宽 == 空格宽」是一个**刻意设计的不动点**（见本文件
     *   `不动点 间隙恰等于分隔空格宽时总宽与落墨位置都不变`）。真书默认字体思源黑体的
     *   空格是 0.2240em，而 `GAP = 0.25em` —— 只差 0.026em，肉眼就是「不动」。
     *   所以这里必须取两档**都不在**不动点上的值，否则测的是不动点而不是响应。
     *
     * 用 LEFT 对齐排除 JUSTIFY 拉伸的干扰（那一档由扫版心那条锁覆盖）。
     */
    @Test
    fun `端到端 滑块一动墨位就动 相邻与空格分隔两种形状都响应`() {
        val fs = 40f
        val table = KerningClusterTable()

        /** 走完整管线取 [s, e) 这一段的落墨 x。 */
        fun ink(para: String, s: Int, e: Int, gapEm: Float): FloatArray {
            val p = LineAligner().align(
                para, s until e, fs, 400f, 0f, "p", SYNTH_FAM, 400, false, false,
                emptyList(), TextAlign.LEFT, 0f, false, false, gapEm,
            )
            assertTrue(
                "[$s,$e) 含 Latin 却没有走簇位轨，否则本锁测不到出问题的那条路径",
                table.needsClusters(para, s, e),
            )
            val cnat = table.clusterXs(
                para, s, e, fs, 1.5f, "p", SYNTH_FAM, 400, false, false,
                emptyList(), 0f, 0f, gapEm,
            )
            assertNotNull("[$s,$e) 必须建得出簇位轨", cnat)
            return graftKerningOnto(p, cnat!!, para, s, fs, 0f, emptyList(), Float.NaN).xs
        }

        // ---- 例一：相邻（无分隔空格），段首偏移非零 ----
        run {
            val para = "前面这段中文只是为了让段首偏移推大，末尾进入中文Rust混排的句子"
            val g = CjkLatinSpacing.gaps(para, GAP).first { it.leftIndex > 20 }
            assertEquals("例一必须是相邻边界（无分隔空格）", 0, g.spaceCount)
            val s = g.leftIndex
            val e = s + 2
            val n = CjkLatinSpacing.gaps(para, GAP).count { it.leftIndex in s until e && it.spaceCount == 0 }
            assertEquals("例一的行内间隙条数", 1, n)
            val x0 = ink(para, s, e, 0f)
            val x1 = ink(para, s, e, GAP)
            val rx = 1
            assertEquals(
                "相邻边界：滑块 0 → $GAP，中英之间的墨位必须恰好推开一个 gap（修复前实测 0.00）",
                GAP * fs, x1[rx] - x0[rx], 1e-2f,
            )
            assertEquals("左邻字位自身不动（间隙只推开右边的字）", 0f, x1[0] - x0[0], 1e-3f)
            // 「中英间距」= 右邻左缘 − 左邻字墨右缘；左邻纯字宽取 gap=0 那档的 advance
            val wLeft = sum(noGaps(para.substring(s, s + 1), fs))
            assertEquals(
                "相邻边界：中英之间的实际间距必须恰好一个 gap",
                GAP * fs, (x1[rx] - x1[0]) - wLeft, 1e-2f,
            )
        }

        // ---- 例二：空格分隔，两档都避开不动点 ----
        run {
            val para = "前面这段中文只是为了让段首偏移推大，末尾进入中文 Rust 混排的句子"
            val g = CjkLatinSpacing.gaps(para, GAP).first { it.leftIndex > 20 }
            assertEquals("例二必须是空格分隔边界", 1, g.spaceCount)
            val s = g.leftIndex
            val e = s + 1 + g.spaceCount + 1
            val (lo, hi) = 0.08f to 0.62f
            val spaceW = sum(noGaps(para.substring(s + 1, s + 1 + g.spaceCount), fs))
            for (probe in listOf(lo, hi)) {
                assertTrue(
                    "探针 $probe 必须远离不动点（空格宽 ${spaceW / fs}em），否则本锁测的是不动点",
                    kotlin.math.abs(probe - spaceW / fs) > 0.05f,
                )
            }
            val xLo = ink(para, s, e, lo)
            val xHi = ink(para, s, e, hi)
            val rx = 1 + g.spaceCount
            assertEquals(
                "空格分隔边界：滑块 $lo → $hi，墨位必须恰好推开 (hi−lo)×字号（修复前实测 0.00）",
                (hi - lo) * fs, xHi[rx] - xLo[rx], 1e-2f,
            )
            assertEquals("左邻字位自身不动", 0f, xHi[0] - xLo[0], 1e-3f)
        }
    }

    /**
     * 标点与符号两侧**不得**插间隙（端到端一层：探测器、量宽、簇位轨三处都要一致）。
     *
     * 真机报的两例都落在这里：`Kotlin：` 的冒号旁、`Rust±2` 的加号旁。
     * 语料里刻意放了「中西标点中西」「中西符号中西」「全角空格 + 拉丁」三种形状。
     */
    @Test
    fun `标点与符号两侧不插间隙 中西标点中西一处都不成边界`() {
        // 语料刻意挑成「除了那个标点之外没有别的边界」——否则断言会被**合法**边界污染
        // （例如 `Rust±2 编译器` 里 `2` 与 `编` 之间本来就有一条空格分隔的真边界）。
        for (text in listOf(
            "Kotlin：",        // 全角冒号 U+FF1A
            "Rust。",          // CJK 句号 U+3002
            "Rust±2",          // 加减号 U+00B1
            "Rust×2",          // 乘号 U+00D7
            "中文°C",          // 度符号 U+00B0
            "中文©",          // 版权符号 U+00A9
            "A，B",            // 全角逗号夹在两个西文之间
            "中　A",           // 表意空格 U+3000
            "AＢ",             // 全角拉丁 Ｂ U+FF22
        )) {
            assertEquals(
                "'$text' 里标点/符号两侧不得有间隙",
                emptyList<CjkLatinGap>(),
                CjkLatinSpacing.gaps(text, GAP),
            )
        }
        // 反证（否则上面那批可能是恒假断言）：同一个位置换成字母，边界立刻出现。
        assertEquals(1, CjkLatinSpacing.gaps("A，B中", GAP).size)
        assertEquals(listOf(2), CjkLatinSpacing.gaps("A，B中", GAP).map { it.leftIndex })
        assertEquals(listOf(CjkLatinGap(0, GAP)), CjkLatinSpacing.gaps("中B", GAP))
        // `中B文` 两侧都成边界（`中|B`、`B|文`）—— 全角逗号那种「只挡一侧」的形状这里没有
        assertEquals(listOf(0, 1), CjkLatinSpacing.gaps("中B文", GAP).map { it.leftIndex })
        // 真机报的那一例：西文 + 全角冒号。冒号右邻是 CJK，若冒号算「西文」就会多出一条
        assertEquals(emptyList<CjkLatinGap>(), CjkLatinSpacing.gaps("中文Kotlin：后面", GAP)
            .filter { it.leftIndex + it.spaceCount >= 6 })
    }

    /**
     * 换面行的簇位轨也必须带间隙，且**段间拼接处不得双计**。
     *
     * `clusterXs` 的多段分支用 `cursorX = out[e−1] + adv[e−1]` 串接各段，而那份 `adv`
     * 是**无间隙**的（刻意，见 `shiftTrackByCjkGaps` 的 KDoc）。若那里也带上间隙，
     * 同一份间隙会在拼接时进一次、末尾的累计位移再进一次 ⇒ **双计**（行凭空宽一个 gap）。
     *
     * 语料：`中文` 与 `ab` 之间既有中西边界（index 1/2 之间）**又**是换面边界（index 2 起换）
     * —— 两种机制作用在同一个位置，是最容易双计的形状。
     *
     * ## 断言必须用「轨 vs 轨」，不能用「步进 vs 裸 gap」
     *
     * 累计位移是一个**阶跃函数**（间隙在下标超过边界之后才生效），而 `c[i+1] − c[i]` 是**步进**
     * = 「该字整份 advance +（若边界恰在这里）间隙」。两者量纲不同，写混了就是恒假断言。
     * 故本锁一律比 `c1[j] − c0[j]`（同一份 runs、开/关间隙两条轨的差 = 累计位移）。
     */
    @Test
    fun `换面行的簇位轨带间隙且段间不双计`() {
        val text = "中文ab"
        val n = text.length
        val runs = listOf(
            FontRun(0, 2, SYNTH_FAM, "p", 400, false, false, 40f),
            FontRun(2, 4, listOf("Times New Roman", "serif"), "p", 400, false, false, 40f),
        )
        assertEquals("语料必须恰好一个中西边界（间隙在 index 1）", 1, CjkLatinSpacing.gaps(text, GAP).size)
        val c1 = KerningClusterTable().clusterXs(
            text, 0, n, 40f, 1.5f, "p", SYNTH_FAM, 400, false, false, runs, 0f, 0f, GAP,
        )
        val c0 = KerningClusterTable().clusterXs(
            text, 0, n, 40f, 1.5f, "p", SYNTH_FAM, 400, false, false, runs, 0f, 0f, 0f,
        )
        assertNotNull("换面行必须建得出轨", c1)
        assertNotNull("0 档时同一份 runs 也必须建得出轨（否则本锁是空断言）", c0)
        val on = c1!!
        val off = c0!!
        assertEquals("轨长必须与 range 同长", n, on.size)
        assertEquals("边界左槽（『文』自身的位置）不动 —— 间隙只推开右边的字", 0f, on[1] - off[1], 1e-3f)
        assertEquals(
            "边界右槽（『a』）必须恰好多出一个 gap：段间拼接 + 末尾累计位移，两处**不得**双计",
            GAP * 40f, on[2] - off[2], 1e-3f,
        )
        assertEquals(
            "再往后累计位移仍是**同一个** gap（阶跃之后不再增长，否则就是每段都加了一遍）",
            GAP * 40f, on[3] - off[3], 1e-3f,
        )
    }

    // ---- 锁 6：间隙的字号取「左字所在 run」的那一档 ----

    /**
     * 混排 run 下间隙宽度必须按**边界左侧那个字所在 run** 的字号算。
     *
     * 语料：前两个字是 CJK（跑 1.0 档）、后面是 Latin（跑 0.5 档），边界在两者之间。
     * 取错档（右字那档）⇒ 间隙只有应有值的一半，两条断言都能区分。
     *
     * ⚠ 取**左侧**而非右侧还有一层理由（不只是「对齐好看」）：`adv[left] += gap` 的语义
     * 就是「把 left 之后的一切推开」，被推开的正是右侧那些字，所以间隙宽度属于**左侧**
     * 这一档的排版上下文 —— 与 `lsPx` 的记账位置逐条一致。
     */
    @Test
    fun `间隙字号取左字所在 run 的那一档`() {
        val text = "中文ab"
        val leftIdx = 1
        // 正向：左侧 1.0 档（40px），右侧 0.5 档（20px）。
        val runs = listOf(
            FontRun(0, 2, SYNTH_FAM, "p", 400, false, false, 40f),
            FontRun(2, 4, SYNTH_FAM, "p", 400, false, false, 20f),
        )
        val d1 = advances(text, fs = 40f, runs = runs, gapEm = GAP)[leftIdx] -
            noGaps(text, fs = 40f, runs = runs)[leftIdx]
        assertEquals("间隙必须按左侧 run（1.0 档 ⇒ 40px）的字号算", GAP * 40f, d1, 1e-3f)
        assertTrue(
            "若错取右侧 run（0.5 档 ⇒ 20px）则会得到一半；本锁必须能区分两者",
            abs(d1 - GAP * 20f) > 1f,
        )
        // 反向语料：边界左侧是 0.5 档 ⇒ 间隙按 20px 算。
        val runs2 = listOf(
            FontRun(0, 2, SYNTH_FAM, "p", 400, false, false, 20f),
            FontRun(2, 4, SYNTH_FAM, "p", 400, false, false, 40f),
        )
        val d2 = advances(text, fs = 40f, runs = runs2, gapEm = GAP)[leftIdx] -
            noGaps(text, fs = 40f, runs = runs2)[leftIdx]
        assertEquals("反向语料的间隙按左侧 run（0.5 档 ⇒ 20px）算", GAP * 20f, d2, 1e-3f)
    }

    // ---- 锁 7：`spaceCount > 0` ⇒ 那串手打空格的 advance 被**置零**，不是「留着再加一个 gap」----

    /**
     * CLREQ 4.1「删除多余半角空格，注入固定间隙」：`中 A` 里那个手打空格的 advance 必须
     * 被**置成严格 0**，间隙加在它**左边**那个字（`中`）的位上。
     *
     * ## 为什么必须逐位断言（净增量会骗人）
     *
     * 净增量 = `+gap − 空格原宽`。若两者恰好相等（本机族栈的空格恰是 0.25em），
     * 净增量就是 0 —— 「总量对」这条断言在默认值下**恒绿**，抓不到任何错。
     * 真正能区分的是**逐位**：空格位必须是**严格 0**（而不是 `空格宽`）。
     *
     * 若只加间隙不置零 ⇒ 中西之间被拉出 `0.25em + 一个空格宽` 的白沟，
     * 正是「自动间距」要消除的东西（而净增量断言在不动点上照样绿）。
     *
     * 变异验证：删掉 `applyCjkLatinGaps` 里的 `for (k in 1..g.spaceCount) adv[left + k] = 0f`
     * ⇒ 本锁红。
     *
     * ## 0 档那半边（2026-10-03 加）：**清零与注入宽解耦**
     *
     * 0 档同样必须把那个空格清成严格 0（`gapEm = 0` 是参数为 0 的那一档，不是「关掉」）。
     * 所以 `0 档` 那一支是：空格位 0、左字位不动、右字位不动、净增量 = `−空格宽`。
     */
    @Test
    fun `吃掉的分隔空格位被置成严格 0`() {
        val text = "中 A" // 「中」+ 手打分隔空格 + 「A」
        val gaps = CjkLatinSpacing.gaps(text, GAP)
        assertEquals("恰好一个吃空格的间隙", 1, gaps.size)
        assertEquals(1, gaps[0].spaceCount)
        assertEquals("间隙的左下标是被吃空格**之前**那个字", 0, gaps[0].leftIndex)
        val fs = 40f
        val gapped = advances(text, fs = fs, gapEm = GAP)
        val base = noGaps(text, fs)
        assertTrue("本锁的语料里那个空格必须真的有宽度（否则断言无意义）", base[1] > 1f)
        assertEquals("左字位 + 一个 gap", GAP * fs, gapped[0] - base[0], 1e-3f)
        assertEquals("分隔空格位必须被置为**严格 0**（这是本锁唯一的判别点）", 0f, gapped[1], 0f)
        assertEquals("右字位不动", 0f, gapped[2] - base[2], 1e-4f)
        assertEquals("净增量 = 注入的间隙 − 被吃掉的空格宽", GAP * fs - base[1], sum(gapped) - sum(base), 1e-3f)
        // 0 档：清零照做、注入宽为 0 ⇒ 净增量 = −空格宽（`Rust 的所有权` → `Rust的所有权`）。
        val zero = advances(text, fs = fs, gapEm = 0f)
        assertEquals("0 档：分隔空格位**照**被置为严格 0（「吃空格」与间隙宽解耦）", 0f, zero[1], 0f)
        assertEquals("0 档：左字位不加任何间隙", 0f, zero[0] - base[0], 1e-4f)
        assertEquals("0 档：右字位不动", 0f, zero[2] - base[2], 1e-4f)
        assertEquals("0 档：净增量 = −被吃掉的空格宽", -base[1], sum(zero) - sum(base), 1e-3f)
    }

    /**
     * NBSP 与半角空格同规则（都是「多余半角空格」，`CjkLatinSpacingTest` 已在探测器侧钉过）。
     *
     * ## 期望值是 `gap − 空格宽`，**不是** `gap`
     *
     * 吃空格的处置是「把那串空格的 advance 置 0，再按 `gap` 加在边界左槽」，
     * 于是整段的净增量 = `+gap − 空格原宽`。CLREQ 4.1 的口径是「**删除**多余半角空格，
     * **注入**固定间隙」——两个动作都在，所以净增量必然是这个差值。
     *
     * ## 顺带钉住一条重要的产品行为（用户报「滑块不起作用」的真凶之一）
     *
     * 当 `gapEm × 字号` **恰好等于**那个空格的 advance 时，净增量为 **0**：版面逐值不变。
     * 本机族栈（`STSong`/`serif`）的空格恰是 0.25em，于是 `cjkLatinSpacing = 25`（默认值）
     * 在「作者手打了分隔空格」的语料上**是一个不动点**。这不是 bug，是「替换」的语义本身；
     * 但它解释了为什么只验 `gap = 0.25` 的锁会误判「没接通」——必须同时验别的 `gapEm`。
     * 锁 9 的端到端两支（无空格语料 / 扫 `gapEm`）就是为这件事准备的。
     */
    @Test
    fun `NBSP 与半角空格同规则`() {
        val fs = 40f
        for (sep in listOf(' ', '\u00A0')) {
            val text = "中${sep}A"
            val gaps = CjkLatinSpacing.gaps(text, GAP)
            assertEquals("分隔符 U+%04X 必须产出吃空格的间隙".format(sep.code), 1, gaps.size)
            assertEquals(1, gaps[0].spaceCount)
            val base = noGaps(text, fs)
            assertTrue("语料里那个分隔符必须真的有宽度（否则本锁是空断言）", base[1] > 0f)
            assertEquals(
                "U+%04X 的净增量 = 注入的间隙 − 被吃掉的空格宽".format(sep.code),
                GAP * fs - base[1],
                sum(advances(text, fs = fs, gapEm = GAP)) - sum(base),
                1e-3f,
            )
            // 不动点上「不变」的**恰好是两样**：`Σ adv`（总宽）与**可见字形**的落墨 x。
            // ⚠ 断言不能写成「`xs` 逐位不变」（第一版就是这么写的，被自己抓到：实测差 10.0）：
            //   间隙加在**左槽**（`x_{i+1} = x_i + adv[i]`，`adv[0]` 多了 gap）⇒ `xs[1]`
            //   ——那个**被吃掉的空格槽**——会右移一个 gap。但那个槽宽 0、不落墨、用户看不见；
            //   真正必须不变的是空格**之后**那个字（`A`）的 x，以及可见右缘。
            // 反过来，「`adv` 逐位不变」也是错的期望（那等于「吃空格」压根没生效）。
            //
            // ⚠ 落墨侧的「不变」基准**不能再用 `align(text)`**（产品口径 2026-10-03 起 0 档也吃空格，
            //   它拿到的已经是「零间隙 + 空格被吃」那份版面）。0 档之下 `LineAligner` **没有**
            //   「完全不处理」这档了 ⇒ 基准改成**绝对期望值**：LEFT 对齐下 `x[k] = Σ_{j<k} adv[j]`、
            //   `visibleRight = Σ adv`（语料无行尾空白），期望值直接从 [noGaps] 的前缀和算。
            //   这比「两份落墨互比」更强：钉的是**绝对落墨位置**，字体/对齐改动都会被抓到。
            val atFixedPoint = advances(text, fs = fs, gapEm = base[1] / fs)
            assertEquals(
                "U+%04X 在 gap == 空格宽 那个不动点上总宽不变（一个宽 W 的空格换成一个宽 W 的间隙）"
                    .format(sep.code),
                0f, sum(atFixedPoint) - sum(base), 1e-3f,
            )
            val pFix = align(text, fs = fs, gapEm = base[1] / fs)
            assertEquals(
                "U+%04X 在不动点上可见右缘必须等于「无间隙」那份的总宽".format(sep.code),
                sum(base), pFix.visibleRight, 1e-3f,
            )
            assertEquals(
                "U+%04X 在不动点上，被吃空格**之后**那个字（`A`）的落墨位置必须等于无间隙那份的前缀和"
                    .format(sep.code),
                base[0] + base[1], pFix.xs[2], 1e-3f,
            )
            // 0 档那份：空格被吃、间隙 0 ⇒ 右缘正好落在「`中` 的字宽 + `A` 的字宽」上。
            // 这是「0 档不是不动点」在落墨侧的判据（与 `0 档的中西墨距真的为 0` 互为两半）。
            assertEquals(
                "U+%04X 在 0 档的可见右缘 = 中 + A 两字宽（空格被吃、间隙 0）".format(sep.code),
                base[0] + base[2], align(text, fs = fs, gapEm = 0f).visibleRight, 1e-3f,
            )
            assertTrue(
                "U+%04X 在不动点上逐槽分布**必须变**（否则「吃空格」压根没生效，本组断言是空断言）"
                    .format(sep.code),
                delta(base, atFixedPoint) > 1f,
            )
        }
    }

    /**
     * 「原有空格**一律删除**」（产品口径 2026-10-03）：边界上**连续的全部**分隔空格都被吃掉，
     * 只留一个注入的固定间隙。
     *
     * ## 这条锁是被用户报障逼出来的
     *
     * 旧口径是「只吃一个空格，多余的保留用户可见分隔」⇒ `中  A` **一个间隙都不发**：
     * 第二个空格不是分隔空格、类别 NONE，两侧不成边界。那本身就是一处「滑块完全不起作用」。
     * 旧锁 `连续两个空格不产生间隙` 把它当成正确行为钉住了 —— 本锁是它的**反向**锁，
     * 改动记录在案（真书《Rust 程序设计语言》25 章 28123 处中西边界里这种形态出现 0 次，
     * 所以是纯粹的一致性修正，不改真书版面；`<pre>` 里靠多空格对齐的字符画会被折叠，
     * 已知代价记在 [CjkLatinSpacing] 的类 KDoc 里）。
     *
     * 判别点是**逐位**：两个空格位都必须是严格 0，且净增量 = `+gap − 2×空格原宽`。
     */
    @Test
    fun `连续空格一律删除_只留一个注入间隙`() {
        val text = "中  A"
        val fs = 40f
        val gaps = CjkLatinSpacing.gaps(text, GAP)
        assertEquals("恰好一个间隙", 1, gaps.size)
        assertEquals(2, gaps[0].spaceCount)
        val base = noGaps(text, fs)
        assertTrue("语料里两个空格都必须真的有宽度（否则本锁是空断言）", base[1] > 1f && base[2] > 1f)
        val gapped = advances(text, fs = fs, gapEm = GAP)
        assertEquals("左字位 + 一个 gap", GAP * fs, gapped[0] - base[0], 1e-3f)
        assertEquals("第一个空格位必须被置为严格 0", 0f, gapped[1], 0f)
        assertEquals("第二个空格位**也**必须被置为严格 0（这是一律删除的判别点）", 0f, gapped[2], 0f)
        assertEquals("右字位不动", 0f, gapped[3] - base[3], 1e-4f)
        assertEquals(
            "净增量 = 注入的间隙 − 吃掉的两个空格宽",
            GAP * fs - base[1] - base[2],
            sum(gapped) - sum(base),
            1e-3f,
        )
    }

    // ---- 锁 8：探测器的段末边界（Ext-B 代理对）不崩，且间隙不越界 ----

    /**
     * CJK 扩展 B（代理对）位于段末时不得越界，也不得产出悬空间隙。
     *
     * 旧实现的判据是 `i + 1 < n`，而边界字是代理对时 `iNext == i + 2` ⇒ 该判据在
     * 「该字位于段末」时会放行到 `text[i + 2]` → **越界崩排版线程**。
     * 旧实现是死代码所以从未触发，接线即触发（实测 `"\uD840\uDC00"` 单独一段即崩）。
     */
    @Test
    fun `ExtB 代理对位于段末不崩且无悬空间隙`() {
        val texts = listOf("\uD840\uDC00", "中\uD840\uDC00", "\uD840\uDC00a", "中\uD840\uDC00a", "a\uD840\uDC00")
        for (text in texts) {
            val shown = text.map { c -> "U+%04X".format(c.code) }.joinToString(" ")
            val gaps = CjkLatinSpacing.gaps(text, GAP)
            for (g in gaps) {
                assertTrue("[$shown] 的间隙 leftIndex=${g.leftIndex} 越界", g.leftIndex >= 0 && g.leftIndex < text.length)
                assertTrue("[$shown] 的间隙必须落在两个边界字之间（右邻必须存在）", g.leftIndex + 1 < text.length)
            }
            // 施加侧也必须不崩 —— 这才是旧 bug 的实际爆点（探测器可能被别处缓存过）。
            val adv = advances(text, fs = 40f, gapEm = GAP)
            assertEquals("[$shown] 量宽结果长度必须等于文本长度", text.length, adv.size)
        }
    }

    /** 间隙永不落在被量字符串的**最后一个槽位**（间隙要求右邻存在）。 */
    @Test
    fun `间隙永不落在最后一个槽位`() {
        val texts = listOf("中文a", "a中文", "中 A ", "中文，中", "x中", "中x", "中文")
        for (text in texts) {
            val gaps = CjkLatinSpacing.gaps(text, GAP)
            for (g in gaps) {
                assertTrue(
                    "文本「$text」产出了落在末槽的间隙（leftIndex=${g.leftIndex}, len=${text.length}）",
                    g.leftIndex < text.length - 1,
                )
            }
        }
    }

    /**
     * 探测器必须**只**收 `CharSequence`（零拷贝），且输入不被修改。
     *
     * 旧实现第一版是 `text.toString()`：每次量宽都多一次整段拷贝，而本函数在
     * **每一次量宽**里被调用（每个叶、每行至少一次）。
     * 「不被修改」这条同样重要：`StringBuilder` 入参在生产里是真实存在的形态。
     */
    @Test
    fun `探测器接受 CharSequence 且不修改输入`() {
        val sb = StringBuilder("中文ab")
        val before = sb.toString()
        val gaps = CjkLatinSpacing.gaps(sb, GAP)
        assertEquals("探测器不得修改入参", before, sb.toString())
        assertEquals("1 个边界", 1, gaps.size)
        // 子串视图（生产的行子串就是这个形态）也必须正确。
        val whole = "前缀中文ab后缀"
        val view = whole.subSequence(2, 6)
        val gaps2 = CjkLatinSpacing.gaps(view, GAP)
        assertEquals("子串视图的边界数必须与整段一致（坐标系是子串自己的）", 1, gaps2.size)
        assertEquals("子串视图的 leftIndex 是**子串内**下标", 1, gaps2[0].leftIndex)
    }

    // ---- 锁 9：端到端 —— 滑块一动，分页必须真的变 ----

    /**
     * 真书风格的中西混排语料。**两种形态都要有**，缺一个就会漏掉一整类真实版面：
     *
     * - [TIGHT]：作者**没**打分隔空格（`Rust的所有权…`）—— 中文技术书里极常见。间隙是**净增**，
     *   总宽随滑块单调变 ⇒ 断行必变。
     * - [SPACED]：作者**打了**分隔空格（`Rust 的所有权…`）—— 按 CLREQ 4.1 间隙是**替换**那个
     *   空格，净增 = `gap − 空格原宽`。⚠ 这条有一个**不动点**：`gapEm × 字号 == 空格原宽` 时净增为 0。
     *
     * ## ⚠ 那个不动点是本轮真机排查查出来的结论，不是理论（用户报「滑块完全不起作用」）
     *
     * 本机族栈（`STSong`/`serif`）的空格 advance 恰是 `0.25em`，而默认档 `cjkLatinSpacing = 25`
     * 正是 `0.25em` ⇒ 在 [SPACED] 语料上，**默认值下整段总宽逐值不变、版心预留逐值不变、
     * 断行结果逐值不变**（实测：7 个间隙全是「吃空格」形态，`preferredWidth` 两边都是
     * `3176.2854`，`700..900` 全扫断点全同）。
     *
     * 这不是没接通，是「替换」语义的**数学不动点**（而且替换后版面与原来**逐像素相同**：
     * 一个 11.1px 的空格换成一个 11.1px 的间隙，落墨位置分毫不差）。
     * ⇒ 端到端锁因此必须**扫 `gapEm`**（不能只钉默认值），并且**必须另备一条无空格的语料**
     * （那种形态里没有不动点）。
     */
    private val TIGHT = "Rust的所有权机制让borrow检查在编译期完成，这与GC的运行时回收完全不同，" +
        "因为lexical生命周期是静态的，而借用检查器正是靠它把悬垂指针挡在编译期。"

    private val SPACED = "Rust 的所有权机制让 borrow 检查在编译期完成，这与 GC 的运行时回收完全不同，" +
        "因为 lexical 生命周期是静态的，而借用检查器正是靠它把悬垂指针挡在编译期。"

    /** 扫一段版心，返回「是否存在一个版心使断点集合改变」。 */
    private fun sawAnyBreakChange(text: String, fs: Float, gapEm: Float, widths: IntRange): Boolean {
        val off = InhouseParagraphBreaker(0f, 0f)
        val on = InhouseParagraphBreaker(0f, gapEm)
        for (w in widths) {
            val r0 = off.breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)
            val r1 = on.breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)
            if (r0.map { it.range } != r1.map { it.range }) return true
        }
        return false
    }

    /**
     * 端到端判据（**无分隔空格的语料** [TIGHT]）：滑块一动，断行结果必须变。
     *
     * ## 这条锁为什么排在最后（它是「用户报的问题」的直接判据）
     *
     * 前面的锁都是分段验「某个施加点接了没」，这条验「**整条链**通了没」。
     * 用户报的是「调节滑块完全不起作用」—— 分段全绿而用户仍看不到变化的情况是**可能的**
     * （例如断行侧接了、绘制侧没接：行数变了但字距没变）。故必须有端到端一条。
     *
     * 版心扫一段而不是钉一个魔数：换字体/平台后魔数会失效，而「存在一个版心使断点改变」
     * 这个性质与字体无关（只要语料有边界）。扫不出变化才是真的没接通。
     */
    @Test
    fun `端到端 无分隔空格的混排 断行结果必随间隙改变`() {
        val gaps = CjkLatinSpacing.gaps(TIGHT, GAP)
        assertTrue("本锁的语料必须真的产出间隙（否则是空断言）", gaps.isNotEmpty())
        assertTrue(
            "TIGHT 语料里不得有被 suppress 的分隔空格（这条锁要验的正是**净增**形态）",
            gaps.none { it.spaceCount > 0 },
        )
        assertTrue(
            "必须存在一个版心使断行结果随混排字距改变（端到端接线判据）。扫 700..900 全同 = 链没通。",
            sawAnyBreakChange(TIGHT, 44.4f, GAP, 700..900),
        )
    }

    /**
     * 端到端判据（**带分隔空格的语料** [SPACED]）：滑块**动**（离开不动点）时断行必变。
     *
     * 这条与上一条是**互补**的两半：上一条验「净增形态」，这条验「替换形态」。
     * 只写上一条，替换形态（也就是 `中 A`、`Rust 的所有权` 这种**最常见**的正文）就没有任何
     * 端到端覆盖；而只写这一条却不扫 `gapEm`，它又会**恒绿成假锁**（默认值恰在不动点上）。
     */
    @Test
    fun `端到端 带分隔空格的混排 间隙值一变断行结果就变`() {
        val gaps = CjkLatinSpacing.gaps(SPACED, GAP)
        assertTrue("本锁的语料必须真的产出间隙（否则是空断言）", gaps.isNotEmpty())
        assertTrue("本锁要验的正是「吃空格」形态", gaps.any { it.spaceCount > 0 })
        val tried = StringBuilder()
        var saw = false
        for (em in listOf(0.1f, 0.5f, 0.9f)) {
            tried.append(em).append(' ')
            if (sawAnyBreakChange(SPACED, 44.4f, em, 700..900)) { saw = true; break }
        }
        assertTrue(
            "带分隔空格的语料上，间隙值一旦离开不动点，断行结果必须变。扫 $tried 全同 = 链没通。",
            saw,
        )
    }

    /**
     * **不动点本身**也要钉住：`gapEm × 字号 == 分隔空格原宽` 时，「吃空格」语料的
     * **总宽与落墨位置**与「不处理混排」那份**逐位相同**。
     *
     * 这不是 bug，是「用固定间隙替换多余半角空格」（CLREQ 4.1）的数学后果；但它是
     * 「滑块看起来没用」的**头号来源**，必须被锁成「已知的、有解释的行为」，
     * 否则下一个人一定会把它当 bug 再查一遍（本轮就查了一遍）。
     *
     * ## ⚠ 不动点上「不变」的**恰好是哪两样**（第一版把期望写错了两处，都被自己抓到）
     *
     * `adv` 的**逐槽分布会变**（空格槽 `W` → `0`、左邻槽 `adv+W`），所以「逐位不变」是错的期望。
     * 不变的是两样：① `Σ adv`（总宽）；② **可见字形**的落墨 x 与可见右缘。
     *
     * 而「`xs` 逐位不变」**也是错的**（实测 `xs[1]` 差 10.0）：间隙加在**左槽**，
     * `x_{i+1} = x_i + adv[i]`、`adv[0]` 多了 gap ⇒ **被吃掉的空格槽的 x 右移一个 gap** ——
     * 那个槽宽 0、不落墨、用户看不见，但断言它不变就是错的。要钉的是空格**之后**那个字。
     *
     * ## ⚠ 基准是「不处理混排」那份，**不是** `align(text)`
     *
     * 产品口径 2026-10-03 起 0 档也吃分隔空格，所以 `align(text)`（其 `gapEm` 默认 0）拿到的
     * 已经是「零间隙 + 空格被吃」那份版面，拿它当基准会读到「不动点右缘差 11.1 = 一个空格」。
     * 而 0 档之下 `LineAligner` **没有**「完全不处理」这一档 ⇒ 落墨侧只能对**绝对期望值**：
     * LEFT 对齐下 `x[k] = Σ_{j<k} adv[j]`、`visibleRight = Σ adv`，从 [noGaps] 前缀和算。
     *
     * 「逐槽分布必须变」那条断言是**防空断言**的：若特性压根没接，`sum` 当然也不变，
     * 本锁会绿得毫无意义。
     */
    @Test
    fun `不动点 间隙恰等于分隔空格宽时总宽与落墨位置都不变`() {
        val fs = 44.4f
        val text = "中 A"
        val base = noGaps(text, fs)
        val spaceW = base[1]
        assertTrue("语料里那个分隔空格必须有宽度", spaceW > 1f)
        val atFix = advances(text, fs = fs, gapEm = spaceW / fs)
        assertEquals(
            "不动点上总宽不变（一个宽 W 的空格换成一个宽 W 的间隙）",
            0f, sum(atFix) - sum(base), 1e-4f,
        )
        val pFix = align(text, fs = fs, gapEm = spaceW / fs)
        assertEquals(
            "不动点上可见右缘必须等于「不处理混排」那份的总宽",
            sum(base), pFix.visibleRight, 1e-4f,
        )
        assertEquals(
            "不动点上被吃空格**之后**那个字（`A`）的落墨位置必须等于「不处理混排」那份的前缀和" +
                "（被吃掉的空格槽本身会移，不可见）",
            base[0] + base[1], pFix.xs[2], 1e-4f,
        )
        assertTrue(
            "不动点上逐槽分布**必须变**（否则「吃空格」没生效）",
            delta(base, atFix) > 1f,
        )
        assertTrue(
            "不动点之外必须真的变（否则「不动点」只是因为特性压根没接）",
            delta(base, advances(text, fs = fs, gapEm = GAP * 2f)) > 1f,
        )
    }

    /**
     * 端到端第二半：**开间隙后每行仍不越版心**（`NoLineExceedsContentWidthTest` 的端到端版本）。
     *
     * 断行侧预留 + 绘制侧画出的宽度必须同源到「画 == 量 ≤ 版心」。这条扫一大段版心、
     * **两种语料都扫**：「预留生效」与「预留过头」是两种不同的错（后者表现为行尾莫名多一个字），
     * 而替换形态（[SPACED]）恰好是「预留过头」最容易出现的那一种。
     *
     * 这里量的是 `naturalWidth(行子串)`，它按**行子串**检出间隙 ⇒ 是断行侧那一份的**子集**
     * ⇒ 只会偏小、不会偏大 ⇒ 足以证「不越版心」。逐位相等由本文件上面那两条
     * 「画 == 量」的锁负责（那里量的是 `gapsForRange` 裁出来的、与断行侧同源的那一份）。
     */
    @Test
    fun `端到端 折行后每行不越版心`() {
        val fs = 44.4f
        for (text in listOf(TIGHT, SPACED)) {
            for (em in listOf(GAP, 0.5f)) {
                val on = InhouseParagraphBreaker(0f, em)
                for (w in 700..900) {
                    for (line in on.breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", SYNTH_FAM, 400, false, false)) {
                        val sub = text.substring(line.range.first, line.range.last + 1)
                        val wLine = naturalWidth(sub, fs, em)
                        assertTrue("em=$em 版心 $w 行 ${line.range} 宽 $wLine 越出版心", wLine <= w)
                    }
                }
            }
        }
    }

    /** `gaps` 返回的列表必须按下标升序且互不重叠（施加点按序单趟扫依赖这个不变式）。 */
    @Test
    fun `间隙按下标升序且互不重叠`() {
        val text = "中a文中b文x中,文"
        val gaps = CjkLatinSpacing.gaps(text, GAP)
        assertTrue("语料必须产出多个间隙", gaps.size >= 4)
        for (k in 1 until gaps.size) {
            assertTrue(
                "间隙必须按下标升序（第 $k 个：${gaps[k - 1].leftIndex} → ${gaps[k].leftIndex}）",
                gaps[k].leftIndex > gaps[k - 1].leftIndex,
            )
        }
        // 「不重叠」的具体含义：没有一个「吃空格」的右邻被另一个间隙的左槽复用。
        for (g in gaps) {
            assertTrue("间隙 ${g.leftIndex} 的右邻必须是可见字（否则两个间隙会作用到同一个字）", g.leftIndex + 1 < text.length)
        }
        assertTrue("本语料必须含 CJK 与 Latin 的多段交替", gaps.any { it.spaceCount > 0 } || gaps.size >= 4)
    }

    /**
     * `gaps` 的 `gapEm` 逐个等于传入值（施加点只认这个字段，不该再回读形参）。
     *
     * 边界数手数为 3：`文↔a`、`b↔中`、`文↔c`。
     */
    @Test
    fun `每个间隙的 gapEm 都等于传入值`() {
        val text = "中文ab中文cd"
        for (em in listOf(0.1f, 0.25f, 0.5f)) {
            val gaps: List<CjkLatinGap> = CjkLatinSpacing.gaps(text, em)
            assertEquals("语料的边界数与 em 无关", 3, gaps.size)
            for (g in gaps) assertEquals("gapEm 必须逐个等于传入值", em, g.gapEm, 0f)
        }
    }
}
