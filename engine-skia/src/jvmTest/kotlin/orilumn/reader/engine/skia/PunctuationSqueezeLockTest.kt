package orilumn.reader.engine.skia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import orilumn.reader.engine.css.TextAlign
import orilumn.reader.engine.laying.isDocumentSpace
import orilumn.reader.engine.laying.isSqueezableClosingPunct

/**
 * **标点挤压**的正式锁（渲染层·几何测量，[PunctuationSqueeze]）。
 *
 * ## 本类的七把锁各管一段（【2026-10-03 第 2 条】改口径后的分工）
 *
 * | 锁 | 管的是 |
 * |---|---|
 * | 1 | 额度公式的 `min` **第一支**（生产 cap）+ 逐槽「窄了多少 == 额度说多少」 |
 * | 2 | `min` 的**第二支**：headroom 绑定（`inkLeft(next)` 项生效、额度严格小于 cap） |
 * | 3 | `w = adv − lsPx`（末字那份不可见字距不许当余量） |
 * | 4 | `inkLeft(next) < 0` 那一支（负左伸） |
 * | 5 | **量画同源**：每行的「断行侧预留总额 == 画侧释放总额」（跨两套坐标系） |
 * | 6 | **硬边界**：挤压之后可见右缘 ≤ 版心（走 JUSTIFY，含非末行的画侧 ③） |
 * | 7 | **画侧 ③**：「JUSTIFY 铺不满才挤」+ 「两次合计被 `1 − r` 夹住」 |
 *
 * 另有两条**别的类**里的锁配合：
 * - [orilumn.reader.engine.skia.TableColumnWidthRealMeasureTest] 锁 3b 钉「`preferredWidth`
 *   **对挤压上限完全免疫**」（`cap = 0 / 0.5em / 100em` 三档逐值全等）——
 *   它锁的是「max-content 那条路径压根不读 cap」，额度公式本身改坏它照样绿（它只是把 `widths` 的输出原样加总）。
 * - [orilumn.reader.engine.skia.InhouseParagraphBreakerTest] 的 `punctuation squeeze never costs a line`
 *   是**单调性**锁（`squeezed ≤ plain` 且至少一格省行）。它抓得住「挤压没接线 / cap 被设成 0」，
 *   抓不住「**挤得不对**」：`min` 的两项写反、`inkLeft(i+1)` 漏算、`w = adv − lsPx` 写成 `adv` ——
 *   这三种都只改变「挤多少」，单调性与省行都仍成立。
 *
 * ⇒ 本类的判据一律是「**画侧到底窄了多少**」（从 `xs` 反解）与「**额度 × 比例说的是多少**」，
 * 两者逐槽 / 逐段对账。逐值，不是百分比。
 *
 * ## 【2026-10-03 第 2 条】额度与施加量现在是**两个数**
 *
 * 老口径是「额度全额上」（`adv −= widths[i]`），所以本类的账天然等于额度表。
 * 现在 [PunctuationSqueeze.widths] 只给**额度**（每槽最多能收多少），施加量 = `额度 × 比例`，
 * 比例由**断行侧逐行**算出来（[orilumn.reader.engine.laying.BrokenLine.squeezeRatio]，
 * 生产值绝大多数是 0），画侧另有一次有界的二次挤压（锁 7）。
 *
 * ⇒ **锁 1~4 默认把比例取 `1`**（`alignLeft` / `shrinkPerSlot` / `expectedPerSlot` 的默认值）。
 * 它们量的对象是**额度公式**，比例只是乘子 ⇒ 取满额得到的账与改口径前一字不差，
 * 不用重算任何期望值。锁 5/6/7 走生产路径 ⇒ 比例取断行侧给的那个。
 * 
 * ## 为什么一律从 `xs` 反解，不看 `Placement.advs`
 *
 * `Placement.advs` 就是被就地减过的那份 `adv`（`adv.copyOf(n)`），拿它当基准等于
 * 「用被试自己的输出去证明它对」。本类只从**两个可观测输出**反解：
 *
 * - 槽 `k`（`k < n−1`）：`xs[k+1] − xs[k]` = 该槽的**落墨推进**。
 * - 末槽（没有 `k+1`）：`adv = visibleRight − xs[n−1] + lastLs`。末字那份 `lsPx` 排在它之后、
 *   无后继、**不可见**，[LineAligner] 的 `natural` 明确减掉了它 —— 反解时必须加回来。
 *
 * 两次对齐（`maxEm = 0` 与 `maxEm = 上限`）之差就是**实际窄化量**，与内部数组无关。
 * 两次的 `ratio` 必须**取同一个值**（`maxEm = 0` 那侧传什么都无所谓：没有额度 ⇒ 乘出来恒 0）。
 *
 * ## 为什么锁用 LEFT 对齐
 *
 * JUSTIFY 的 `xs` 里混着 [JustifySlack] 的拉伸量，反解出的「窄化量」会被污染；而且 JUSTIFY
 * 非末行会触发画侧 ③（[LineAligner] 里那次二次挤压），**释放量会比断行侧预留的多一截** ——
 * 那是有意的方向性不对称（只会让行更窄 ⇒ 绝不溢出），但它会让「预留 == 释放」这条等式不成立。
 * `align = LEFT` ⇒ `plan == null` ⇒ `xs[k+1] − xs[k]` **恰好等于**该槽 advance，差值纯净，③ 也不触发。
 * 拉伸那条路由 [JustifySlackPriorityTest] 负责，两件事互不掩盖。版心硬边界（锁 6）与 ③（锁 7）走 JUSTIFY。
 *
 * ## 额度是产品口径，写在这里免得被当魔数改掉
 *
 * 生产上限 **0.5em**（[PunctuationSqueeze.DEFAULT_MAX_EM]）。改它要同步改
 * `docs/自建断行引擎-实施方案.md` S4 与 `docs/TODO-未尽事宜.md` Q24。
 *
 * ## ⚠ 额度是 `min(cap, headroom)`：`min` 的**第二支才是全部边界条款的所在**
 *
 * `headroom = w − inkRight + inkLeft(next)`，`w = adv − lsPx`。
 * headroom 绑定时，`inkLeft(next)` 与 `lsPx` 两项才**可观测**；cap 绑定时它们被 `min` 吃掉，
 * 写多少断言都抓不到变异。而 headroom 绑不绑定**只取决于字体的墨迹宽度**（`lsPx` 在
 * `adv` 与 `w` 里对消，与字距无关），所以：
 *
 * - 锁 1 用**生产 cap**（`min` 的第一支绑定）钉 cap 值本身；
 * - 锁 2/3/4 用**测试专用上限** [bigEm]（1.5em，远大于任何 headroom）强制走进第二支。
 *   这是**故意**在 `min` 的另一支上取样，两支合起来才是完整判据。
 *
 * 实测（`STSong`/serif @40、全角 `adv = 1em`、`cap = 20px`）：
 * `。` 墨迹 `4/16`、`，` `5/14`、`；` `6/14`、`！` `6/13`、`？` `3/18` ⇒ headroom 22~27 > 20，
 * **cap 恒绑定**；`》` 墨迹 `1/23` ⇒ headroom **17 < 20，headroom 绑定**。
 * 下一字左伸：`中` = 3、`文` = 0、ASCII `j` = **−1**（唯一能测到 `inkLeft < 0` 那一支的字）。
 * ⇒ 锁 2/3 的切点选 `》`（headroom 绑定 + 下一字有正左伸），锁 4 的语料选 `，j`（负左伸）。
 *
 * 真机 Source Han Sans SC @43.75 的度量不同（`？` 墨迹 0.503em ⇒ headroom 0.497em < cap 0.5em，
 * `ls = 0` 就已经绑定）。但那是**另一台机器的度量**，锁不能建在它上面。
 */
class PunctuationSqueezeLockTest {

    private val fam = listOf("STSong", "serif")
    private val fs = 40f

    /** 生产上限（em）。锁 1/5 用它。 */
    private val prodEm = PunctuationSqueeze.DEFAULT_MAX_EM

    /** **测试专用**上限（em）：远大于任何 headroom，只为强制 `min` 走第二支。生产路径永不使用。 */
    private val bigEm = 1.5f

    private val m = SkiaRunMeasurer()

    /**
     * 一行落位（LEFT ⇒ `plan == null` ⇒ `xs` 的相邻差恰好是 advance）。
     *
     * @param ratio 挤压比例（`[0,1]`，见 [PunctuationSqueeze.slotSqueeze]）。**默认 1**
     *   —— 锁 1~4 量的是**额度公式本身**，那个公式与比例无关（比例只是乘子），
     *   所以默认取满额，得到的账与改口径前一字不差。
     *   生产值恒来自 [orilumn.reader.engine.laying.BrokenLine.squeezeRatio]，绝大多数行是 0。
     */
    private fun alignLeft(text: String, range: IntRange, ls: Float, maxEm: Float, ratio: Float = 1f) =
        LineAligner().align(
            text, range, fs, 1e7f, ls, "p", fam, 400, false, false, emptyList(),
            TextAlign.LEFT, 0f, false, hyphenAtEnd = false,
            cjkLatinSpacingEm = 0f, punctuationSqueezeMaxEm = maxEm,
            squeezeRatio = ratio,
        )

    /** 末字那份不可见的 `lsPx`（[LineAligner] 从 `natural` 里减掉的那一份，反解时加回来）。 */
    private fun lastLs(ls: Float): Float = ls * fs

    /**
     * **逐槽实际窄化量**（px）：从两次对齐的 `xs` / `visibleRight` 反解，与内部数组无关。
     *
     * @param range 行区间（**段级绝对下标**）。跨行语料用它来指定切在哪。
     */
    private fun shrinkPerSlot(
        text: String, range: IntRange, ls: Float, maxEm: Float, ratio: Float = 1f,
    ): FloatArray {
        val n = range.count()
        require(n >= 1)
        val base = alignLeft(text, range, ls, 0f, 0f)
        val sq = alignLeft(text, range, ls, maxEm, ratio)
        val out = FloatArray(n)
        for (k in 0 until n - 1) {
            out[k] = (base.xs[k + 1] - base.xs[k]) - (sq.xs[k + 1] - sq.xs[k])
        }
        val last = n - 1
        out[last] =
            (base.visibleRight - base.xs[last] + lastLs(ls)) -
                (sq.visibleRight - sq.xs[last] + lastLs(ls))
        return out
    }

    /** 额度公式的独立复算（同一个出口 [PunctuationSqueeze.widths]，但由本类自己调、自己喂 `adv`）。 */
    private fun expectedPerSlot(
        text: String, range: IntRange, ls: Float, maxEm: Float, ratio: Float = 1f,
    ): FloatArray {
        val whole = m.advances(text, fs, ls, "p", fam, 400, false, false, emptyList(), emptyList())
        val local = whole.copyOfRange(range.first, range.last + 1)
        val caps = PunctuationSqueeze.widths(
            text, range.first, range.last + 1, local, range.first, m, fs, ls,
            "p", fam, 400, false, false, emptyList(), maxEm,
        )
        // 额度 × 比例：改口径后「额度」与「施加量」是两个数（[PunctuationSqueeze.ratioNeeded]）。
        for (k in caps.indices) caps[k] = PunctuationSqueeze.slotSqueeze(caps[k], ratio)
        return caps
    }

    /** 墨迹盒（量不到返回 `null`）。 */
    private fun ink(text: String, i: Int) =
        m.glyphInkBoxPx(text.codePointAt(i), fs, 0f, "p", fam, 400, false, false, emptyList(), i)

    // ================================================================
    // 锁 1：量画同源 · 行内逐槽对账（生产 cap，`min` 的第一支）
    // ================================================================

    /**
     * **每一槽的实际窄化量逐值等于额度公式**；不可压的位置一个字节都不许窄。
     *
     * 判据的红绿只由「画侧减了多少」与「公式算是多少」之差决定 —— 两侧任一分叉（`advBase` 传错、
     * `min` 的两项写反、cap 取错、`isSqueezableClosingPunct` 候选表被改窄、`adv` 与 `advBase`
     * 坐标系错位）都在这里现形，消息里带**具体是第几槽、差多少**。
     *
     * `pressed` 那张表顺带把**生产 cap 的值**钉死：五个候选槽全部**恰好**压到
     * `0.5em × 40 = 20px`（headroom 22~27 都比它大 ⇒ 走第一支）。cap 一改这张表就红。
     */
    @Test
    fun `行内每槽实际窄化量逐值等于额度公式`() {
        val text = cjkPunct
        val range = text.indices
        val got = shrinkPerSlot(text, range, 0f, prodEm)
        val want = expectedPerSlot(text, range, 0f, prodEm)

        val pressed = mutableListOf<Int>()
        for (k in range) {
            assertEquals(
                "第 $k 槽（«${text[k]}» 可压=${isSqueezableClosingPunct(text, k)}）实际窄化量应逐值等于额度公式。" +
                    "两侧分叉最常见的四种：① `advBase` 传错（行内局部 vs 段级绝对）；② `min` 的两项写反；" +
                    "③ `w` 写成 `adv` 而漏减 `lsPx`；④ 候选表被改窄。" +
                    "（实测 本锁 ${got.toList()} / 期望 ${want.toList()}）",
                want[k],
                got[k],
                0.0005f,
            )
            if (want[k] > 0f) pressed += k
            else {
                assertEquals(
                    "第 $k 槽（«${text[k]}»）不在候选表里 ⇒ 必须一个字节都不窄",
                    0f, got[k], 0f,
                )
            }
        }

        // 自证 + 顺带钉死生产 cap：全 0 ⇒ 本锁退化成 `0 == 0`；改了 cap ⇒ 这张表就红。
        assertEquals(
            "语料的可压槽位/额度表变了（实测压了 $pressed，各槽额度 ${got.toList()}）。" +
                "改语料或改 cap 时先对这张表：STSong/serif @40 ls=0 下 `，；！？。` 五个全部命中 cap = 20px。",
            listOf(2, 5, 8, 11, 14),
            pressed,
        )
        for (k in pressed) {
            assertEquals(
                "第 $k 槽应恰好压到生产上限 ${prodEm * fs}px（headroom 比它大 ⇒ 走 `min` 第一支）",
                prodEm * fs,
                got[k],
                0.0005f,
            )
        }
    }

    // ================================================================
    // 锁 2：跨行边界 —— `inkLeft(i+1)` 必须取**下一行第一个字**（`min` 的第二支）
    // ================================================================

    /**
     * **行末那个可压标点的额度里，必须含下一行第一个字的 `inkLeft`。**
     *
     * ## 这条为什么最容易坏、也最值得单独立锁
     *
     * [LineAligner] 手上只有**行内子串**，而额度是**段级属性**（`inkLeft(i+1)` 要看下一个字）。
     * 两者共用一份实现的桥就是 [PunctuationSqueeze.widths] 的 `advBase` 形参 + 传**整段 `text`**。
     * 任何一边改成传子串（`line`），行末那一槽就会少一个 `inkLeft(next)`：
     *
     * ```
     * 断行侧：传整段 text ⇒ 按 inkLeft(next) = 3 预留 w − 3.08
     * 画侧  ：传行内子串 ⇒ inkLeft(next) = 0，只释放 w − 0.08
     * ⇒ 画侧比预留**宽 3px** ⇒ 行可能超版心（溢出方向，不是「排松一点」）
     * ```
     *
     * 这个分叉**只在行末发生**、**只在 headroom 绑定时显形**，现有锁一条都抓不到。
     *
     * ## 锁法
     *
     * 语料 [crossLine] 的下标 8 是 `》`（STSong 墨迹 `1/23` ⇒ headroom 17 < 任何 cap ⇒
     * `min` 第二支绑定），把行切在 `8..8` ⇒ `》` 是行末槽，下一行第一个字是 `中`
     * （实测 `inkLeft = 3px`）。用 [bigEm] 把 cap 抬到 headroom 之上，第二支才绑定。
     */
    @Test
    fun `跨行边界计入下一行首字的左伸`() {
        val text = crossLine
        val split = crossSplit
        require(text[split] == '》' && text[split + 1] == '中') { "语料的切点变了，见类 KDoc" }
        val range = 0..split
        val got = shrinkPerSlot(text, range, 0f, bigEm)
        val want = expectedPerSlot(text, range, 0f, bigEm)
        val k = range.count() - 1 // 行末槽 = `》`
        val actual = got[k]

        assertEquals(
            "行末槽（«》»）的实际窄化量应逐值等于额度公式（跨行的 `inkLeft(下一行首字)` 也在内）。" +
                "实测 本锁 $actual / 期望 ${want[k]}。",
            want[k],
            actual,
            0.0005f,
        )

        val adv = m.advances(text, fs, 0f, "p", fam, 400, false, false, emptyList(), emptyList())[k]
        val base = adv - ink(text, k)!!.right // 物理余量（不含 inkLeft 项）
        val inkLeftNext = ink(text, k + 1)!!.left
        assertTrue(
            "本锁的前提：下一行首字（«中»）必须有正左伸，否则「跨行计入 `inkLeft`」与「不计入」同值。" +
                "实测 inkLeft=$inkLeftNext。换语料，别删这条断言。",
            inkLeftNext > 0f,
        )
        assertTrue(
            "本锁的前提：额度必须由 headroom 绑定（实测 S=$actual、cap=${bigEm * fs}、headroom+inkLeft=${base + inkLeftNext}）。" +
                "若 cap 绑定，`inkLeft` 被 min 吃掉，本锁抓不到任何变异。调大测试上限或换语料，别删这条断言。",
            actual < bigEm * fs - 1e-4f,
        )
        assertTrue(
            "行末槽（«》»）的额度必须**严格大于**不含 `inkLeft(下一行首字)` 的物理余量 $base；实测 $actual。" +
                "相等即 `inkLeft` 项没生效 —— 典型的「传了行内子串而不是整段 text」（溢出方向）。",
            actual > base + 1e-4f,
        )
    }

    // ================================================================
    // 锁 3：`w = adv − lsPx` —— 末字那份不可见的 lsPx 不许当余量
    // ================================================================

    /**
     * **盒宽必须写 `adv − lsPx` 而不是 `adv`**（末字那份排在它之后、无后继、不可见）。
     *
     * ## 退化形态与它抓什么
     *
     * ```
     * 正确：S = adv − lsPx − inkRight + inkLeft(next)
     * 退化：S = adv        − inkRight + inkLeft(next) = 正确 + lsPx
     * ```
     * 只要 `lsPx > inkLeft(next)`，退化版就必然越过 `adv − inkRight` 这条线 ⇒ 判据取
     * **`S < adv − inkRight`**（它比公式允许的 `w − inkRight + inkLeft(next)` 严格更紧，
     * 正因为那多出来的 `lsPx` 大于 `inkLeft`）。
     *
     * 顺带这条把「额度不许超过盒宽」也钉住：`S ≤ w < adv`（`ls > 0` 时严格小于）。
     */
    @Test
    fun `盒宽必须减去末字那份不可见的 lsPx`() {
        val text = crossLine
        val ls = 0.2f
        val lsPx = lastLs(ls)
        val range = 0..crossSplit
        val got = shrinkPerSlot(text, range, ls, bigEm)
        val k = range.count() - 1
        val actual = got[k]

        val adv = m.advances(text, fs, ls, "p", fam, 400, false, false, emptyList(), emptyList())[k]
        val inkRight = ink(text, k)!!.right
        val inkLeftNext = ink(text, k + 1)!!.left

        assertTrue(
            "本锁的前提：`lsPx > inkLeft(下一字)`（实测 lsPx=$lsPx、inkLeft=$inkLeftNext）。" +
                "不成立则退化版（多压一个 lsPx）也越不过 `adv − inkRight`，本锁抓不到变异。" +
                "把 ls 调大，别删这条断言。",
            lsPx > inkLeftNext + 1e-4f,
        )
        assertTrue(
            "行末槽（«》»）窄化 $actual 越过了 `adv − inkRight` = ${adv - inkRight}。" +
                "退化形态：盒宽写成 `adv` 而不是 `adv − lsPx`（$lsPx）⇒ 额度多一个 `lsPx`，" +
                "而末字那份 `lsPx` 排在它之后、无后继、**不可见**" +
                "（[LineAligner] 的 `natural` 明确减掉它，见该方法的类 KDoc 第 2 条）。",
            actual < adv - inkRight + 1e-4f,
        )
        assertTrue(
            "额度不许超过盒宽 `w = adv − lsPx` = ${adv - lsPx}（实测 $actual）。",
            actual <= adv - lsPx + 1e-4f,
        )
    }

    // ================================================================
    // 锁 4：挤满时相邻字位的墨迹仍然不相接（`inkLeft < 0` 那一支）
    // ================================================================

    /**
     * **把额度顶到 headroom 满，相邻两个字位的墨迹仍然不相接**（间隙恰好 0，不许为负）。
     *
     * ## 为什么这条锁的是 `inkLeft(next)` 而不是别的
     *
     * `headroom = w − inkRight + inkLeft(next)` 的推导是「第 i+1 槽左移到 `x_i + w' + inkLeft(i+1)`，
     * 要它不早于第 i 字的墨迹右缘」⇒ 精确条件 `S ≤ w − inkRight + inkLeft(i+1)`，
     * 等价于 **相邻墨迹间隙 ≥ 0**。而 `inkLeft(next) > 0` 时这一项是**放松**（允许多挤），
     * **只有 `inkLeft(next) < 0`（墨左伸）时它才收紧** —— 那才是能测到它的唯一情形。
     *
     * 语料 [tightPunct] 的 `，` 后面跟 ASCII `j`：实测 `inkLeft(j) = −1.0`
     * （STSong/serif 下唯一可用的负左伸字；`中` +3 / `文` 0 / 全角 `）` +3 都 > 0）。
     * ```
     * 正确：S = 40 − 14 + (−1) = 25  ⇒ 间隙 = −1 + 15 − 14 = 0（恰好相接）
     * 漏掉 inkLeft 项：S = 40 − 14 = 26  ⇒ 间隙 = −1 + 14 − 14 = **−1，墨叠字**
     * ```
     * 用 [bigEm] 把 cap 抬到 60px，`min` 第二支绑定，headroom 满。
     *
     * ## 为什么只判「**被挤过的槽**」，不判全行所有相邻对
     *
     * 字体本身就能造出负间隙（实测 `j|y`：`inkLeft(y) = −1`、`adv(j) = 8.84`、`inkRight(j) = 8`
     * ⇒ 未挤时就是 **−0.16px**）。那是**与挤压无关的既有事实**，写进判据会让本锁
     * 锁的是字体度量而不是 `inkLeft` 那一项 ⇒ 换台机器就红。所以判据只在
     * 「本槽确实被挤过」的位置上取，而被挤过的槽由**独立复算的额度**给出（不是从 `xs` 猜）。
     *
     * 判据只认 `xs`（相邻落墨推进）与墨迹盒，**不看**额度公式本身 —— 纯几何。
     */
    @Test
    fun `挤满时相邻墨迹仍不相接`() {
        val text = tightPunct
        val k = text.indexOf('，')
        require(k >= 0 && text[k + 1] == 'j') { "语料的切点变了，见类 KDoc" }
        require((ink(text, k + 1)?.left ?: 0f) < 0f) { "本机字体没有负左伸的字了，见类 KDoc" }

        val p = alignLeft(text, text.indices, 0f, bigEm)
        val advBase = m.advances(text, fs, 0f, "p", fam, 400, false, false, emptyList(), emptyList())
        // 独立复算的额度（判据的「哪些槽被挤过」由此而来）。
        val squeeze = PunctuationSqueeze.widths(
            text, 0, text.length, advBase, 0, m, fs, 0f,
            "p", fam, 400, false, false, emptyList(), bigEm,
        )
        require(squeeze[k] > 0f) { "语料的可压槽位变了，见类 KDoc" }

        // 只在「被挤过且右侧还有一字」的槽上判。挤过的槽必须**恰好**把间隙收到 0（headroom 满）。
        val judged = mutableListOf<Int>()
        for (i in 0 until text.length - 1) {
            if (squeeze[i] <= 0f) continue
            val a = ink(text, i) ?: continue
            val b = ink(text, i + 1) ?: continue
            judged += i
            // 相邻墨迹间隙 = 下一字的左伸 + 本槽**实际** advance − 本字的墨迹右缘。
            val gap = b.left + (p.xs[i + 1] - p.xs[i]) - a.right
            assertTrue(
                "第 $i 槽（«${text[i]}» → «${text[i + 1]}»）被挤了 ${squeeze[i]}px 之后墨迹**重叠** ${-gap}px。" +
                    "`inkLeft(next)` 项被漏掉了：它正是为「下一字墨左伸（负边距）」而设的那条收紧条件。",
                gap >= -1e-4f,
            )
            assertEquals(
                "第 $i 槽被挤满 headroom 后，相邻墨迹间隙应**恰好**为 0。" +
                    "不为 0 说明额度没顶满或顶过了（额度 ${squeeze[i]}，cap ${bigEm * fs}）。",
                0f, gap, 0.0005f,
            )
        }
        assertTrue(
            "本锁的前提：至少要判到一个被挤过且右侧有字的槽（实测判了 $judged）。" +
                "全 0 则本锁退化成空转，换语料，别删这条断言。",
            judged.contains(k),
        )
    }

    // ================================================================
    // 锁 5：断行侧预留总额 == 画侧释放总额（跨行 + 扫版心）
    // ================================================================

    /**
     * **每一行的窄化总额，断行侧预留的与画侧释放的必须逐值相等**（扫一整排版心）。
     *
     * ## 【2026-10-03 第 2 条】口径改了：「预留总额」不再是整段额度之和
     *
     * 上一轮的口径是「断行侧按**额度全额**预留 ⇒ 预留总额 == 整段 `widths` 之和 == 画侧释放总额」。
     * 现在挤压**按比例施加**，比例逐行由断行侧算出来（[orilumn.reader.engine.laying.BrokenLine.squeezeRatio]）
     * ⇒ 预留总额 = `Σ_行 Σ_{k∈行} ratio(行) × 额度(k)`，**绝大多数行是 0**。
     *
     * 这条锁**没有因此变成空锁**：`widths` 的两侧坐标系仍然是**两套** ——
     * 断行侧算预算时本锁喂的是**整段 `adv`（`advBase = 0`）**，[LineAligner] 落墨时用的是
     * **行内局部 `adv`（`advBase = start`）**。`advBase` 写错 ⇒ 同一字位两个 `S`
     * ⇒ 量画失配（画比预留宽 = 溢出方向）。语料 [squeezePunct] 的每个单元都含两个收尾标点，
     * 而比例非零的行（实测 11 行 / 177 行）恰恰是「标点 + 词尾差一点」那种**一行两个坐标系都非零**的行
     * ⇒ 这条跨行跨坐标系的等式只在这些行上有内容。
     *
     * 前置断言两条：必须真的判到**多行**，也必须真的判到**比例非零的行**（否则退化成 `0 == 0`）。
     *
     * 走 LEFT 对齐（[PunctuationSqueeze] 画侧 ③ 的「JUSTIFY 铺不满再挤一点」只在 JUSTIFY 非末行触发，
     * 见 [LineAligner]）—— 否则「释放」会多出 ③ 那一段，与断行侧预留的对不上（那是**有意的**方向性不对称，
     * 由 [JustifySlackPriorityTest] 与 `NoLineExceedsContentWidthTest` 负责，详见两处 KDoc）。
     */
    @Test
    fun `断行侧预留总额逐值等于画侧释放总额`() {
        val text = squeezePunct
        val whole = m.advances(text, fs, 0f, "p", fam, 400, false, false, emptyList(), emptyList())
        val capsWhole = PunctuationSqueeze.widths(
            text, 0, text.length, whole, 0, m, fs, 0f, "p", fam, 400, false, false, emptyList(), prodEm,
        )
        assertTrue(
            "本锁的前提：语料必须真的产出挤压额度（实测总额 ${capsWhole.sum()}）。" +
                "为 0 则本锁退化成 `0 == 0`。",
            capsWhole.sum() > 0f,
        )

        var cells = 0
        var multiLine = 0
        var squeezedLines = 0
        for (w in squeezeWidths) {
            val lines = InhouseParagraphBreaker(0f, 0f, punctuationSqueezeMaxEm = prodEm)
                .breakLines(text, fs, 1.5f, w, TextAlign.LEFT, "p", fam, 400, false, false)
            var reserved = 0f
            var released = 0f
            for (ln in lines) {
                val r = ln.range
                if (r.isEmpty() || ln.hyphenAtEnd) continue
                if (ln.squeezeRatio > 0f) squeezedLines++
                // 断行侧「预留」：用**整段坐标系**的额度表（`advBase = 0`）。
                for (k in r) reserved += PunctuationSqueeze.slotSqueeze(capsWhole[k], ln.squeezeRatio)
                // 画侧「释放」：从 `xs` 反解（用**行内局部** `adv`，`advBase = start`）。
                for (v in shrinkPerSlot(text, r, 0f, prodEm, ln.squeezeRatio)) released += v
            }
            assertEquals(
                "版心 $w：断行侧预留的挤压总额（$reserved）必须与画侧释放的总额（$released）逐值相等。" +
                    "两侧喂给 `PunctuationSqueeze.widths` 的 `adv` 坐标系不同（整段 vs 行内局部），" +
                    "`advBase` 写错就会让同一字位拿到两个 `S`（画侧比预留宽 ⇒ 溢出方向）。",
                reserved,
                released,
                maxOf(0.01f, 0.001f * maxOf(1f, reserved)),
            )
            if (lines.size > 1) multiLine++
            cells++
        }
        assertTrue(
            "本锁的前提：必须真的切出多行（实测 $multiLine / $cells 档），单行时两个坐标系重合、本锁自证。",
            multiLine > 0,
        )
        assertTrue(
            "本锁的前提：必须真的判到**比例非零**的行（实测 $squeezedLines 行）。" +
                "全 0 说明挤压一次都没触发，本锁退化成 `0 == 0` —— 换语料，别删这条断言。",
            squeezedLines > 0,
        )
    }

    // ================================================================
    // 锁 6：扫版心 —— 挤过的每行可见右缘仍不超版心
    // ================================================================

    /**
     * **挤压之后每一行的可见右缘都 ≤ 版心**（扫一整排版心，JUSTIFY，含非末行的画侧 ③）。
     *
     * 挤压只减不增，所以这条在任何额度下都该成立；它真正的作用是**把「某处把 `S` 加回了宽度」
     * 这类溢出方向的错误立刻变成一条带版心号的失败**，而不是等到真机上看见字被裁掉。
     * 可见右缘 = `visibleRight`（[LineAligner] 报的那个，不是墨迹右缘 —— 墨盒可以比 advance 宽）。
     *
     * ## ⚠ 必须把 `ln.squeezeRatio` 喂给 `align`
     *
     * 漏了它（默认 0）就会出现**假溢出**：断行侧是「挤着算宽度才保住整词」才把 `more` 收进来的，
     * 锁这边按「一点不挤」量 ⇒ 那条行凭空宽出 ΣS。第一版就是没喂，实测超 **17.6px**。
     * 这正是 `DrawLine.squeezeRatio` KDoc 里那个症状，只不过发生在**锁自己**身上。
     *
     * 走 JUSTIFY 是因为那才是真机常态（正文 `text-align: justify`）；LEFT 在锁 1~4 覆盖。
     * 本锁也顺带跑到了画侧 ③（「JUSTIFY 铺不满再挤一点」），那一步只会让右缘**更靠左**，
     * 所以它在本锁里是「无害项」—— ③ 的正向判据由 `画侧二次挤压只在JUSTIFY铺不满时发生且有界` 那把锁负责。
     */
    @Test
    fun `挤过之后每行可见右缘不超版心`() {
        val text = squeezePunct
        var checked = 0
        var anyNarrower = 0
        for (w in squeezeWidths) {
            val lines = InhouseParagraphBreaker(0f, 0f, punctuationSqueezeMaxEm = prodEm)
                .breakLines(text, fs, 1.5f, w, TextAlign.JUSTIFY, "p", fam, 400, false, false)
            for ((li, ln) in lines.withIndex()) {
                val r = ln.range
                if (r.isEmpty() || ln.hyphenAtEnd) continue
                var lastVis = r.last
                while (lastVis > r.first && isDocumentSpace(text[lastVis])) lastVis--
                if (lastVis < r.first) continue
                val p = LineAligner().align(
                    text, r, fs, w.toFloat(), 0f, "p", fam, 400, false, false, emptyList(),
                    TextAlign.JUSTIFY, 0f, isLastLine = li == lines.size - 1, hyphenAtEnd = false,
                    cjkLatinSpacingEm = 0f, punctuationSqueezeMaxEm = prodEm,
                    squeezeRatio = ln.squeezeRatio,
                )
                assertTrue(
                    "版心 $w 第 ${li + 1} 行：可见右缘 ${p.visibleRight} 超过版心（超 ${p.visibleRight - w}px）。" +
                        "挤压只减不增，出现超出版心说明某处把 `S` 加回了宽度（溢出方向），" +
                        "或 `squeezeRatio` 没喂进来（见本锁 KDoc 的假溢出段）。",
                    p.visibleRight <= w + 0.001f,
                )
                // 非自证：按本行自己的比例挤之后比不挤**短**（否则本锁对挤压是空转）。
                val plain = alignLeft(text, r, 0f, 0f, 0f)
                if (alignLeft(text, r, 0f, prodEm, ln.squeezeRatio).visibleRight < plain.visibleRight - 1e-4f) {
                    anyNarrower++
                }
                checked++
            }
        }
        assertTrue("本锁的前提：必须真的检查到行（实测 $checked 行）。", checked > 0)
        assertTrue(
            "本锁的前提：必须真的有行被挤窄了（实测 $anyNarrower 行）。全 0 说明挤压在这条路径上没生效。",
            anyNarrower > 0,
        )
    }

    // ================================================================
    // 锁 7：画侧 ③ —— 「JUSTIFY 铺不满时的二次挤压」，比例有界
    // ================================================================

    /**
     * **画侧二次挤压只在「JUSTIFY 铺不满」时发生，且两次合计不得超过额度总额。**
     *
     * 这是产品裁决 2026-10-03 第 2 条的**第二个「有必要」**（第一个是断行侧的「不挤就破词」），
     * 落在绘制侧：[LineAligner] 里那段 `plan1` 的二次 `JustifySlack.plan` + 二次挤压。
     *
     * ## 语料与那一行是怎么造出来的
     *
     * `"中文，中文，中文，中文，中文"` @版心 200 / fs 40 / JUSTIFY，切成 `0..3 | 4..8 | 9..13` 三行。
     * 取第 1 行（`0..3` = `中文，中`，4 字）：`natural = 160`、`slack = 40`；
     * 4 个槽全是 [JustifySlack.LV_CJK]（`文|，` 两侧都不是西文字母数字）⇒ 有限额度 = `3 × 0.25em = 30`
     * ⇒ `placed = 30` < `slack = 40` ⇒ **[Placement.justifyCapped] 为真**，行末留缺口 10px。
     * 那 10px 拉伸补不了（没有级 3 槽）⇒ 才是 ③ 的正当理由。同一段的另两行分别把两个前提否掉：
     * 第 2 行 `slack = 0`（放得下，不铺不满），第 3 行是**末行**（`doJustify` 为假）。
     *
     * ## 五个数字各自钉一个性质（全部**绝对期望值**，不在测试里重算 `placed`/`capTotal`）
     *
     * | 行 / 条件 | `visibleRight` | 钉的是 |
     * |---|---|---|
     * | 行 1，`em=0`（完全没有挤压额度） | **190** | JUSTIFY 封顶后的基线（`160 + 30`）。只由 [JustifySlack] 决定 |
     * | 行 1，`em=cap`、`r=0` | **180** | ③ 真的发生了，且**只挤缺口那么多**（`190 − 缺口 10`），不是把额度挤满（那会是 170） |
     * | 行 1，`em=cap`、`r=0.5` | **170** | **两次合计被 `1 − r` 夹住**（`0.5 + 0.5 × 0.5` ⇒ 合计 0.75 额度）。删掉 `room` 夹取会变 160 |
     * | 行 1，`em=cap`、`r=1` | **170** | 断行侧已挤满 ⇒ `room = 0` ⇒ ③ **完全不许再挤**。删掉 `room` 夹取这一格不变，所以必须与上一格**并排** |
     * | 行 2/3，`em=cap` 与 `em=0` | **相等** | ③ 只在「JUSTIFY 铺不满」时触发：`slack=0` 与「末行不拉伸」两条前提各否一次 |
     *
     * ## 为什么 `capped` 要单独钉（两个特性不混在一把锁里量）
     *
     * ③ **不改变「铺不满」这个事实本身**：`placed` 恒为 30（槽数与级号都没变），缺口恒为 10。
     * 所以 `em=0` 与 `em=cap` 两种口径下 [Placement.justifyCapped] 都必须是 true —— 若哪天 ③
     * 顺手把槽分级也算改了，`capped` 会变成 false 而 `visibleRight` 仍对，这条断言就是唯一的哨兵。
     *
     * ## ⚠ `r` 是**显式喂进去**的，生产里它来自 [orilumn.reader.engine.laying.BrokenLine.squeezeRatio]
     *
     * 本段文本的断行侧比例全是 0（溢出点不在词内），所以要验 `r > 0` 的分支只能手喂。
     * 这不违反「量同源」纪律：这里量的对象是 [LineAligner] 自己的夹取逻辑，不是断点决策。
     */
    @Test
    fun `画侧二次挤压只在JUSTIFY铺不满时发生且两次合计有界`() {
        val text = "中文，中文，中文，中文，中文"
        val w = 200f
        val lines = InhouseParagraphBreaker(0f, 0f, punctuationSqueezeMaxEm = prodEm)
            .breakLines(text, fs, 1.5f, w.toInt(), TextAlign.JUSTIFY, "p", fam, 400, false, false)
        assertEquals(
            "本锁的前提：这段必须切成 3 行（第二行 slack=0、第三行是末行，两条否定前提各占一行）",
            listOf("0..3", "4..8", "9..13"), lines.map { it.range.toString() },
        )
        assertEquals(
            "本锁的前提：断行侧在本段一个比例都不给（溢出点不在词内）⇒ 行 1 的 180 全是 ③ 的功劳",
            listOf(0f, 0f, 0f), lines.map { it.squeezeRatio },
        )

        /** 一次落位（第 `li` 行、`maxEm` 上限、显式 `ratio`）。 */
        fun place(li: Int, maxEm: Float, ratio: Float) = LineAligner().align(
            text, lines[li].range, fs, w, 0f, "p", fam, 400, false, false, emptyList(),
            TextAlign.JUSTIFY, 0f, isLastLine = li == lines.size - 1, hyphenAtEnd = false,
            cjkLatinSpacingEm = 0f, punctuationSqueezeMaxEm = maxEm, squeezeRatio = ratio,
        )

        // —— 行 1（唯一的正例：JUSTIFY 非末行 + 铺不满）——
        val cappedNoSqueeze = place(0, 0f, 0f)
        assertTrue(
            "本锁的前提：没有挤压额度时这一行就必须铺不满（实测 capped=${cappedNoSqueeze.justifyCapped}）",
            cappedNoSqueeze.justifyCapped,
        )
        assertEquals("没有挤压额度时的 JUSTIFY 封顶基线（natural 160 + placed 30）", 190f, cappedNoSqueeze.visibleRight, 0.001f)
        // `r` 对 `em=0` 恒无影响（没有额度 ⇒ 三档逐值相同）—— 钉住「③ 只吃额度」这条边界。
        for (r in listOf(0.5f, 1f)) {
            assertEquals(
                "没有挤压额度时 `squeezeRatio` 不得有任何影响（r=$r）",
                190f, place(0, 0f, r).visibleRight, 0.001f,
            )
        }
        val r0 = place(0, prodEm, 0f)
        assertEquals(
            "行 1 + r=0：③ 真的发生，且**只挤缺口那 10px**（挤满额度会得到 170）",
            180f, r0.visibleRight, 0.001f,
        )
        assertTrue(
            "③ 不改变「铺不满」这个事实本身（em 两种口径下 capped 都必须为真）",
            r0.justifyCapped,
        )
        assertEquals(
            "行 1 + r=0.5：两次合计被 `1 − r` 夹住（0.5 + 0.5×0.5 = 0.75 额度 ⇒ 170）。" +
                "删掉 `room` 夹取会变成 160（多挤 10px）",
            170f, place(0, prodEm, 0.5f).visibleRight, 0.001f,
        )
        assertEquals(
            "行 1 + r=1：断行侧已挤满 ⇒ `room = 0` ⇒ ③ 完全不许再挤（合计恰好一个额度总额）",
            170f, place(0, prodEm, 1f).visibleRight, 0.001f,
        )

        // —— 行 2（`slack = 0`，没有缺口）与行 3（末行，`doJustify` 为假）：③ 都不得发生 ——
        for (li in 1..2) {
            assertEquals(
                "行 ${li + 1}：③ 不得发生（行 2 是 slack=0，行 3 是末行）" +
                    "⇒ `em=cap` 与 `em=0` 逐值相同",
                place(li, 0f, 0f).visibleRight,
                place(li, prodEm, 0f).visibleRight,
                0.001f,
            )
        }
        // 行 3 的第一趟挤压仍按传入比例生效（③ 不触发 ≠ `squeezeRatio` 被忽略）—— 钉住这两个开关是分开的。
        assertEquals(
            "行 3（末行）：`squeezeRatio=1` 仍要把额度挤满（200 → 180）",
            180f, place(2, prodEm, 1f).visibleRight, 0.001f,
        )
    }

    // ---- 语料（改任何一个先对类 KDoc 里那张度量表） ----

    /** 锁 1 语料：15 字、5 个收尾标点与汉字交替。STSong/serif @40 `ls=0` 下可压下标 = `[2,5,8,11,14]`。 */
    private val cjkPunct = "中文，中文；中文！中文？中文。"

    /** 锁 2/3 语料：下标 [crossSplit] = `》`（headroom 17 < cap ⇒ 第二支绑定），下一字 `中`（`inkLeft = 3`）。 */
    private val crossLine = "中文中文中文》中文中"
    private val crossSplit = 6

    /** 锁 4 语料：`，`（`inkRight = 14`）后跟 ASCII `j`（`inkLeft = −1`，本机唯一的负左伸字）。 */
    private val tightPunct = "中文，jy"

    /**
     * 锁 5/6 语料：**6 个「中文，中文；abc 」单元**（60 字、12 个收尾标点、6 段西文词）。
     *
     * ## 【2026-10-03 第 2 条】换语料：只为让「挤压真的触发」
     *
     * 旧语料（`中文，中文；abc 中文！中文？中文。中文ab，中文。`）在 12 档版心上只有
     * **1 行**的比例非零（177 行里 1 行）—— 判据于是近乎空转。换成重复单元后是 **12 行**。
     *
     * 单元形状就是触发条件本身：`中文，中文；` 占 6 个全角字位，紧接着 `abc` 三个西文字位 ——
     * 版心落在「`abc` 刚好差一点装不下」那一档时，溢出点落进 `ab|c` 词内
     * ⇒ [orilumn.reader.engine.skia.InhouseParagraphBreaker.ratioToKeepWordIntact] 触发
     * ⇒ 比例 = `need / Σ额度`，实测 `0.440 / 0.586 / 0.879` 三档。
     */
    private val squeezePunct = "中文，中文；abc ".repeat(6)

    /** 锁 5/6 的版心档（要够宽才能切出多行，够窄才能切出「词尾差一点」的行）。 */
    private val squeezeWidths = intArrayOf(60, 80, 100, 120, 142, 160, 200, 240, 280, 320, 400, 600)
}