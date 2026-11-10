package orilumn.reader.engine.text.preprocess

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CjkLatinSpacingTest {

    private fun gapsOf(text: String, em: Float = 0.25f): List<CjkLatinGap> =
        CjkLatinSpacing.gaps(text, em, emptyList())

    @Test
    fun `cjk then latin gets a gap`() {
        val gaps = gapsOf("中文English")
        assertEquals(listOf(CjkLatinGap(leftIndex = 1, gapEm = 0.25f, spaceCount = 0)), gaps)
    }

    @Test
    fun `latin then cjk gets a gap`() {
        val gaps = gapsOf("English中文")
        assertEquals(listOf(CjkLatinGap(leftIndex = 6, gapEm = 0.25f, spaceCount = 0)), gaps)
    }

    @Test
    fun `digit counts as western side`() {
        assertEquals(listOf(CjkLatinGap(0, 0.25f)), gapsOf("值3"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f)), gapsOf("3值"))
    }

    @Test
    fun `half-width space between cjk and latin is suppressed into a gap`() {
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 1)), gapsOf("中 A"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 1)), gapsOf("A 中"))
    }

    @Test
    fun `nbsp between cjk and latin is suppressed into a gap`() {
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 1)), gapsOf("中\u00A0A"))
    }

    @Test
    fun `no gap inside pure cjk or pure latin runs`() {
        assertTrue(gapsOf("中文中文").isEmpty())
        assertTrue(gapsOf("English").isEmpty())
        assertTrue(gapsOf("12345678").isEmpty())
    }

    @Test
    fun `punctuation and symbols never participate on either side`() {
        // 真机反馈（2026-10-03）：`Kotlin：` 的冒号旁、`Rust±2` 的加号旁都插了间隙。
        // 根因是 CJK 侧含 `U+3000..U+303F` / `U+FF00..U+FFEF`（标点、全角形），
        // Western 侧是整段 `U+0030..U+024F`（Latin-1 里混着符号）。收紧后一律 NONE。
        for (s in listOf(
            "Kotlin：",   // ：U+FF1A 全角冒号
            "Rust。",     // 。U+3002 CJK 句号
            "Rust，",     // ，U+FF0C 全角逗号
            "（x",         // （U+FF08 全角左括号 —— 旧口径在 index 0 发间隙
            "」x",         // 」，U+300D CJK 引号
            "Rust±2",      // ±U+00B1
            "Rust×2",      // ×U+00D7
            "中文°C",      // °U+00B0
            "中文©",      // ©U+00A9
            "中文«»",      // «»U+00AB/BB
            // ⚠ `中　A`（U+3000 表意空格）2026-11-10 起**移出**本列表：它不再是「不参与」，
            //   而是作为**空白**被吃掉并发出一条 `spaceCount = 1` 的间隙
            //   （产品口径「接缝上一切空白一律删除」）。见下面 `all whitespace ... eaten`。
            "AＢ",         // ＢU+FF22 全角拉丁（旧口径判 CJK 侧）
        )) {
            assertEquals("'$s' 里没有任何一个标点/符号该参与混排", emptyList<CjkLatinGap>(), gapsOf(s))
        }
    }

    @Test
    fun `real letters and digits still get gaps on both sides`() {
        // 收紧判据不能把真边界一起收掉：相邻、空格分隔、两侧反向都要照旧。
        assertEquals(listOf(CjkLatinGap(0, 0.25f)), gapsOf("中A"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 1)), gapsOf("中 A"))
        assertEquals(listOf(CjkLatinGap(1, 0.25f)), gapsOf("Ab中"))
        assertEquals(listOf(CjkLatinGap(1, 0.25f, spaceCount = 1)), gapsOf("Ab 中"))
        // 全角标点夹在中间 ⇒ 两段邻接都被打断，一处都不成边界。
        assertEquals(emptyList<CjkLatinGap>(), gapsOf("A，B"))
        assertEquals(emptyList<CjkLatinGap>(), gapsOf("A。B"))
    }

    @Test
    fun `cjk extension b surrogates do not produce spurious gaps`() {
        // 𠀀 (U+20000, surrogate pair) then Latin — one gap, left indexing in code units.
        val text = "\uD840\uDC00A"
        assertEquals(listOf(CjkLatinGap(1, 0.25f)), gapsOf(text))
    }

    @Test
    fun `custom gap em is honored`() {
        assertEquals(listOf(CjkLatinGap(0, 0.3f)), gapsOf("中A", em = 0.3f))
    }

    @Test
    fun `multiple boundaries yield multiple sorted non-overlapping gaps`() {
        // 中 文 E n g l i s h 中 文 — pairs (1→2 CJK/E) and (8→9 h/中).
        val gaps = gapsOf("中文English中文")
        assertEquals(
            listOf(
                CjkLatinGap(leftIndex = 1, gapEm = 0.25f, spaceCount = 0),
                CjkLatinGap(leftIndex = 8, gapEm = 0.25f, spaceCount = 0),
            ),
            gaps,
        )
    }

    // ---- 「原有空格一律删除」（产品口径 2026-10-03）----

    @Test
    fun `boundary after an eaten space is still detected`() {
        // 旧实现在发出「吃空格」的间隙后写的是 `i = after + cc(after); continue`，
        // 那一跳把紧跟着的合法边界 `A`(2)↔`中`(3) 一起跳掉了 ⇒ `A` 与后面的中文之间一个字距都不加。
        // 现在的实现只有**一个**推进点（`i = iNext`），数空格的内层 `while` 是纯前瞻。
        // 这条锁是「逐位置无状态」这条不变式的最小反例：少发一条 ⇒ 绘制侧比断行侧窄。
        assertEquals(
            listOf(
                CjkLatinGap(leftIndex = 0, gapEm = 0.25f, spaceCount = 1),
                CjkLatinGap(leftIndex = 2, gapEm = 0.25f, spaceCount = 0),
            ),
            gapsOf("中 A中文"),
        )
    }

    @Test
    fun `a whole run of separating spaces is eaten and one gap is emitted`() {
        // 旧口径「只吃一个、其余保留」会让 `中  A` **一个间隙都不发** —— 那本身就是一处
        // 「滑块完全不起作用」。现在：连续空格全部计入 spaceCount，只留一个注入的间隙。
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 2)), gapsOf("中  A"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 5)), gapsOf("A     中"))
    }

    // ---- 中文标点接缝：吃空白、不发间隙（产品口径 2026-11-10）----

    @Test
    fun `punctuation seam eats the space but injects no gap`() {
        // 用户原话：「中文标点与英文字符之间不要有额外的混排字距」。
        // 全角标点的字身里本就带着半个字身的留白，再叠一道 0.25em 就成了一个空格宽的空档
        // （真机 `Int。 Kotlin` 的实测缺陷）。所以：空白照吃（spaceCount），间隙宽 0。
        assertEquals(listOf(CjkLatinGap(3, 0f, spaceCount = 1)), gapsOf("Int。 Kotlin"))
        assertEquals(listOf(CjkLatinGap(2, 0f, spaceCount = 1)), gapsOf("中文， English"))
        assertEquals(listOf(CjkLatinGap(0, 0f, spaceCount = 1)), gapsOf("。 Rust"))
        // 相邻（没有空白可吃）= 无事可做，一条都不发（标点旁不插缝）。
        assertTrue(gapsOf("Int。Kotlin").isEmpty())
        assertTrue(gapsOf("中文，English").isEmpty())
    }

    @Test
    fun `dash and ellipsis are not punctuation seams`() {
        // 刻意不收 `—`/`―`/`…`：两侧留不留空白是作者的排版选择（`Rust — 中文` 很常见）。
        assertTrue(gapsOf("Rust — 中文").isEmpty())
        assertTrue(gapsOf("Rust…中文").isEmpty())
    }

    @Test
    fun `eaten space slots are exactly the spaces gaps swallow`() {
        // 复制 / 摘录按这张表摘字（原文与下标一个不动，只在「带走文字」时处理）。
        assertArrayEquals(intArrayOf(2, 7), CjkLatinSpacing.eatenSpaceSlots("中文 Rust 的"))
        assertArrayEquals(intArrayOf(4), CjkLatinSpacing.eatenSpaceSlots("Int。 Kotlin"))
        assertArrayEquals(intArrayOf(1), CjkLatinSpacing.eatenSpaceSlots("中  A").copyOf(1))
        assertArrayEquals(IntArray(0), CjkLatinSpacing.eatenSpaceSlots("中文English"))
    }

    @Test
    fun `isEatenSpaceSlot agrees with the eaten slot table on every position`() {
        // 命中侧（`LineHitTest`）用「只判一位」的 O(空白串长) 形式跳过不可选的接缝空白，
        // 复制/摘录用整张表。两条实现必须逐位一致 —— 改判据漏改一处，症状是「复制干净了、
        // 但拖手柄又能选中那段空白」这种只在真机上看得见的半修。
        val corpus = listOf(
            "中文 Rust 的",
            "Int。 Kotlin",
            "中  A",
            "中 \u00A0A",
            "中文English",
            "中\tA",
            "中\u3000A",
            "中 A中文",
            "中文 ",
            " 中文A",
            "A 中文",
            "中文 — A",
            "中文, A",
            "中，A",
            "Rust 的所有权",
            "\uD840\uDC00 A",
            "中 A\uD840\uDC00",
            "",
        )
        for (text in corpus) {
            val eaten = CjkLatinSpacing.eatenSpaceSlots(text).toHashSet()
            for (i in text.indices) {
                assertEquals(
                    "text=<$text> index=$i",
                    i in eaten,
                    CjkLatinSpacing.isEatenSpaceSlot(text, i),
                )
            }
            // 越界位置一律 false（命中侧会拿 endExcl 之类问一句）。
            assertTrue(!CjkLatinSpacing.isEatenSpaceSlot(text, -1))
            assertTrue(!CjkLatinSpacing.isEatenSpaceSlot(text, text.length))
        }
    }

    @Test
    fun `half-width space and nbsp are one and the same run`() {
        // 两种分隔空格不分类，连续计数（否则 `中 \u00A0A` 会在 NBSP 处断链、少算一个）。
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 2)), gapsOf("中 \u00A0A"))
    }

    // ---- 「接缝上的一切空白（换行除外）一律删除」（产品口径 2026-11-10）----
    //
    // 用户原话：「中英文边界（包括夹着一个html标签的情况），中间的所有空白字符
    // （不包括换行符）均删除，由混排字距统一来做隔离」。
    // 旧判据只认半角空格与 NBSP ⇒ 细空格/窄不断行空格/全角空格**既不被吃、也不成边界**
    // （它们本身不是 CJK 也不是西文），接缝处于是留下一个**与滑块无关**的固定宽度 —— 滑块失灵。

    @Test
    fun `all whitespace kinds are eaten at the seam`() {
        // 逐码点写死的那张表（见 [CjkLatinSpacing] 的 isSeparatingSpace KDoc）逐个验一遍。
        val kinds = mapOf(
            0x0009 to "TAB",
            0x0020 to "SPACE",
            0x00A0 to "NBSP",
            0x1680 to "OGHAM SPACE MARK",
            0x2002 to "EN SPACE",
            0x2003 to "EM SPACE",
            0x2007 to "FIGURE SPACE",
            0x2009 to "THIN SPACE",
            0x200A to "HAIR SPACE",
            0x202F to "NARROW NBSP",
            0x205F to "MEDIUM MATH SPACE",
            0x3000 to "IDEOGRAPHIC SPACE 全角空格",
        )
        for ((cp, name) in kinds) {
            val sp = cp.toChar()
            assertEquals(
                "$name(U+%04X) 该被吃成零宽并只留一条间隙".format(cp),
                listOf(CjkLatinGap(0, 0.25f, spaceCount = 1)),
                gapsOf("中${sp}A"),
            )
            // 反向也一样（西文在前，空白在中间）
            assertEquals(
                "$name(U+%04X) 反向".format(cp),
                listOf(CjkLatinGap(0, 0.25f, spaceCount = 1)),
                gapsOf("A${sp}中"),
            )
        }
    }

    @Test
    fun `mixed whitespace kinds count as one run`() {
        // 种类不分类，连续计数：半角空格 + 全角空格 + 细空格 = 3 个零宽槽。
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 3)), gapsOf("中 \u3000\u2009A"))
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 2)), gapsOf("中\u202F\u00A0A"))
    }

    @Test
    fun `line breaks are never eaten`() {
        // 「不包括换行符」：换行是硬断行（`<br>` / `<pre>` 的 `\n`），两侧的字不在同一行，
        // 既不成边界、也不进 spaceCount。
        for (nl in listOf('\n', '\r', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029')) {
            val code = nl.code
            assertTrue("U+%04X 是换行，不得被吃".format(code), gapsOf("中${nl}A").isEmpty())
            // 换行**夹在**空白串中间时，空白串在换行处断链 ⇒ 那一串空白成了行尾悬挂空白，
            // 两端不在同一行，**一条间隙都不发**（吃了它反而会让下一行凭空缩进）。
            assertTrue(
                "U+%04X 把空白串断成两半后不该再成边界".format(code),
                gapsOf("中 ${nl}A").isEmpty(),
            )
        }
    }

    @Test
    fun `a trailing separating space is not a boundary`() {
        // 守卫 `after < n`：空格后面没有码本 ⇒ 那只是段末/行末的空白，不是「中西之间的分隔空格」。
        // 同时它消掉了越界读 `text[after]`（生产里几乎每行以空格收尾的正文都会走到这里）。
        assertTrue(gapsOf("中 ").isEmpty())
        assertTrue(gapsOf("中  ").isEmpty())
        // `A 中 ` 只有 `A`↔空格↔`中` 那一条；末尾那个空格（后面没码本）既不成边界、
        // 也不被算进 spaceCount —— 若它被吞进去，spaceCount 会变成 2 且尾字被拉成零宽。
        assertEquals(listOf(CjkLatinGap(0, 0.25f, spaceCount = 1)), gapsOf("A 中 "))
    }

    @Test
    fun `eaten spaces are the only ones claimed by their boundary`() {
        // 「间隙不重叠」的具体含义：一串空格只属于它前面那一个边界字。
        val gaps = gapsOf("中 A 中文 B")
        // 中(0) ' '(1) A(2) ' '(3) 中(4) 文(5) ' '(6) B(7)：三处边界，两个不同的空格串
        assertEquals(
            listOf(
                CjkLatinGap(leftIndex = 0, gapEm = 0.25f, spaceCount = 1),
                CjkLatinGap(leftIndex = 2, gapEm = 0.25f, spaceCount = 1),
                CjkLatinGap(leftIndex = 5, gapEm = 0.25f, spaceCount = 1),
            ),
            gaps,
        )
    }

    // ---- 「按行子串检测 ⊆ 按整段检测」在探测器这一层的机械证明 ----
    // 这条**不再**是「画 == 量」的依据（绘制侧已改用 [CjkLatinSpacing.gapsForRange]），
    // 它锁的是探测器本身那条更基本的不变式：子串检出只可能少、绝不可能多。
    // 少掉的那至多 1 条正是「跨行边界的间隙」，由 `gapsForRange` 那几条锁接管。

    @Test
    fun `substring gaps are a subset of paragraph gaps on every sub-interval`() {
        // 逐位置无状态 ⇒ 任意区间 [s,e) 上的检出恰好是整段检出里满足
        // `leftIndex ≥ s && leftIndex + cc(leftIndex) + spaceCount < e` 的那批（至多少 1 条）。
        val text = "中 A中文 ab中cd，Rust 社区 e  中A"
        val para = gapsOf(text)
        for (s in text.indices) {
            for (e in s + 1..text.length) {
                val sub = CjkLatinSpacing.gaps(text.subSequence(s, e), 0.25f, emptyList())
                    .map { it.leftIndex + s }
                val expected = para.filter {
                    it.leftIndex >= s && it.leftIndex + charCountOf(text, it.leftIndex) + it.spaceCount < e
                }.map { it.leftIndex }
                assertEquals(
                    "区间 [$s,$e) 上子串检出必须**恰好**等于整段检出的受限子集",
                    expected,
                    sub,
                )
            }
        }
    }

    private fun charCountOf(text: String, i: Int): Int {
        val cp = text.codePointAt(i)
        return if (cp > 0xFFFF) 2 else 1
    }

    // ---- `gapsForRange`：绘制侧与断行侧同源的**唯一**入口（画 == 量）----

    @Test
    fun `gapsForRange is the paragraph set clipped to the range, with stranded widths zeroed`() {
        // 「画 == 量」的机械形态：同一份整段结果，按 `leftIndex ∈ [start, endExcl)` 裁，
        // 下标平移成行内局部；**西文那一头不在本行的那条，宽度压 0**（行末悬空间隙，
        // 见 [CjkLatinSpacing.gapsForRange] 的 KDoc）。穷举所有子区间与整段检出逐条对齐。
        val text = "中 A中文 ab中cd，Rust 社区 e  中A"
        val para = gapsOf(text)
        for (s in text.indices) {
            for (e in s + 1..text.length) {
                assertEquals(
                    "区间 [$s,$e)",
                    para.filter { it.leftIndex in s until e }
                        .map {
                            CjkLatinGap(
                                it.leftIndex - s,
                                if (it.leftIndex + 1 + it.spaceCount < e) it.gapEm else 0f,
                                it.spaceCount,
                            )
                        },
                    CjkLatinSpacing.gapsForRange(text, s, e, 0.25f),
                )
            }
        }
    }

    @Test
    fun `gapsForRange zeroes a stranded gap width but keeps eating its spaces`() {
        // 「文│空│R」断行（贪心的常规形状：断点落在分隔空格**之后**，那串空格悬挂在本行尾）。
        // 西文那一头 `R` 在下一行 ⇒ 这条间隙右侧没有墨 ⇒ 宽度必须压 0；
        // 而**分隔空格照吃**（`spaceCount` 原样带走），否则下一行凭空多出一个空格宽的行首缩进。
        //
        // 旧口径在这里断言 `gapEm = 0.25f`，把「版心里凭空多出的空白」钉成了正确行为 ——
        // 实测真书 1673/30956 行（5.4%）中招，行末右缘整整短 `0.25em`（11.1px @ 字号 44.4）。
        assertEquals(listOf(CjkLatinGap(1, 0f, spaceCount = 1)), CjkLatinSpacing.gapsForRange("中文 Rust", 0, 3, 0.25f))
        // 「文│R」断行（无作者空格，西文直接跟在下一行）：同样压 0，`spaceCount = 0`。
        assertEquals(listOf(CjkLatinGap(1, 0f)), CjkLatinSpacing.gapsForRange("中文Rust", 0, 2, 0.25f))
        // 行**内**的中西接缝（西文就在本行）：宽度必须原样保留 —— 这条是滑块真正的作用点。
        assertEquals(listOf(CjkLatinGap(1, 0.25f, spaceCount = 1)), CjkLatinSpacing.gapsForRange("中文 Rust", 0, 7, 0.25f))
        assertEquals(listOf(CjkLatinGap(1, 0.25f)), CjkLatinSpacing.gapsForRange("中文Rust", 0, 6, 0.25f))
        // 下一行只持有被吃的那个空格：它的左槽（`文`）在上一行，本行不注入间隙。
        //
        // ⚠ 于是本行那一格空格**不会被置零** ⇒ 本行画得比预留宽一个空格宽。真实断行器切不出
        //   这种行：UAX#14 LB 禁止在空格之前断行（`× SP`），行尾空白一律**悬挂**在行区间内
        //   ⇒ 边界字与它后面那串空格必然同进同出。实测真书 29151 行里以分隔空格开头的行 = 0 行。
        //   本断言把这个「够不到」的形状钉在案上，将来谁改了断行规则就会立刻看见。
        assertEquals(emptyList<CjkLatinGap>(), CjkLatinSpacing.gapsForRange("中文 Rust", 2, 7, 0.25f))
    }

    @Test
    fun `gapsForRange survives degenerate ranges and a zero slider`() {
        assertEquals(emptyList<CjkLatinGap>(), CjkLatinSpacing.gapsForRange("中文A", 0, 0, 0.25f))
        assertEquals(emptyList<CjkLatinGap>(), CjkLatinSpacing.gapsForRange("中文A", 2, 2, 0.25f))
        // ⚠ 0 档**不再**短路（产品口径 2026-10-03）：`[1,3)` 覆盖「文↔A」那个边界，
        //   所以 0 档返回一条 `gapEm = 0` 的间隙。旧口径下这里断言空列表。
        assertEquals(listOf(CjkLatinGap(0, 0f)), CjkLatinSpacing.gapsForRange("中文A", 1, 3, 0f))
        // 越界范围被夹回文本长度内，不抛。
        assertEquals(1, CjkLatinSpacing.gapsForRange("中文A", 0, 99, 0.25f).size)
        assertEquals(1, CjkLatinSpacing.gapsForRange("中文A", -5, 3, 0.25f).size)
    }
}