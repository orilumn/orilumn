package orilumn.reader.engine.skia

import org.jetbrains.skia.Font
import org.jetbrains.skia.FontStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **保底面表**（族栈覆盖不到时的最后去处）的不变量锁 —— 渲染层·字体解析。
 *
 * ## 缺陷现象与根因
 *
 * 换自建断行引擎后，代码块里的 Unicode 特殊字符「要么不显示、要么显示乱码」。根因不在断行
 * （断点区间与旧实现逐行相同），而在**取面**：落墨已换成 S5 逐字 `drawString`，取面只剩
 * [SkiaRunMeasurer.faceForCp] 一条路，而它原本只在 CSS 族栈里找覆盖面，找不到就交 `.notdef`。
 * SkParagraph 侧的系统级回退（[SkParagraphFactory.defaultCollection] 挂 default FontMgr）
 * 随换实现一起丢了。真书 `pre` 族栈 `"Fira Code","Hack Nerd Font Mono","Roboto Mono",monospace`
 * 全是拉丁等宽族 ⇒ `U+4E2D 中`、`U+FF21 Ａ` 无人覆盖 ⇒ 豆腐块；而 `─│├└`、`U+00A0` 恰好在等宽面
 * 里有字形 ⇒ 「有的显示有的不显示」，正是真机症状。
 *
 * 修法见 [SkiaRunMeasurer.universalTable]：**把保底面追加到同一张面表末尾**，让已有的
 * 「逐面取第一个 `getUTF32Glyph(cp) != 0`」规则在量宽与落墨两侧同时生效。
 *
 * ## 本类锁的不变量
 *
 * 1. **保底可达**：任一码本，若**族栈解不出**而**任一已装字体能解出**，[faceForCp] 必须给出
 *    一个真有字形的面（绝不 `.notdef`）；
 * 2. **量画同源**：[SkiaRunMeasurer.advances] 给出的宽，必须等于 [faceForCp] 那张面的同一个宽
 *    （否则「量按 notdef 宽排版、画按保底面落墨」＝ 右侧溢出/左侧留白）；
 * 3. **不误伤**：族栈**能**覆盖的码本，取到的面必须仍是族栈里的那张（保底面只许垫底、不许抢位）；
 * 4. **不崩**：族栈里一个可匹配的族都没有时，量宽与取面都不得抛异常；
 * 5. **排除族**：`Last Resort` 这类「对任何码本都有字形、但画出来就是一块诊断豆腐块」的字体
 *    不得进保底表（否则把缺陷画得更显眼）。
 *
 * ## 为什么锁 1 要**动态选码本**而不是钉死 `中`
 *
 * 各宿主（macOS 开发机 / Android 平板 / CI 容器）已装字体集不同：`中` 在某个宿主上完全可能
 * 已被族栈里的某个族覆盖，那这把锁就**静默变成空断言**（教训：写得太松等于没写）。
 * 故本类先在宿主上**现挑**一个满足「族栈解不出 ∧ 有已装字体能解出」的码本，挑不到就**显式失败**
 * （说明宿主字体集异常，这条不变量已无从验证），绝不静默跳过。
 */
class GlyphFallbackFaceTest {

    private val fs = 24f
    private val weight = 400
    private val italic = false
    private val mono = true

    /** 真书 `stylesheet.css:127` 的 `pre` 族栈：4 个族多半没装，真机只剩通用 monospace。 */
    private val bookPreStack = listOf("Fira Code", "Hack Nerd Font Mono", "Roboto Mono", "monospace")

    private val mgrs get() = SkiaFontPool.managers()

    private val m = SkiaRunMeasurer()

    /** 宿主上「族栈解不出、但有已装字体能解出」的码本；找不到返 null。 */
    private fun pickUncoveredButInstallable(): Int? {
        val stack = SkParagraphFactory.resolveFamilies("pre", bookPreStack, mono)
        val stackFaces = stack.flatMap { f -> mgrs.mapNotNull { runCatching { it.matchFamilyStyle(f, FontStyle.NORMAL) }.getOrNull() } }
        for (cp in CANDIDATES) {
            if (stackFaces.any { runCatching { Font(it, fs).getUTF32Glyph(cp).toInt() }.getOrDefault(0) != 0 }) continue
            if (installedCovers(cp)) return cp
        }
        return null
    }

    /** 测试侧独立 oracle：遍历**全部**已装族，谁有该字形就算数（不复用被测的候选定序）。 */
    private fun installedCovers(cp: Int): Boolean {
        for (mgr in mgrs) {
            val n = runCatching { mgr.familiesCount }.getOrDefault(0)
            for (i in 0 until n) {
                val tf = runCatching {
                    mgr.getFamilyName(i).let { mgr.matchFamilyStyle(it, FontStyle.NORMAL) }
                }.getOrNull() ?: continue
                if (runCatching { Font(tf, fs).getUTF32Glyph(cp).toInt() }.getOrDefault(0) != 0) return true
            }
        }
        return false
    }

    private fun faceWidthPx(font: Font, cp: Int): Float =
        runCatching { font.getWidths(shortArrayOf(font.getUTF32Glyph(cp)))[0].toFloat() }.getOrDefault(-1f)

    // ── 锁 1：族栈解不出的码本必须落到有字形的面 ──

    @Test
    fun `族栈覆盖不到的码本必须由保底面表兜住而不是 notdef`() {
        val cp = pickUncoveredButInstallable()
        assertNotNull(
            "宿主上找不到「族栈解不出 ∧ 有已装字体能解出」的码本 —— 本条不变量已无从验证，" +
                "换宿主或补字体后请复核候选表",
            cp,
        )
        val font = m.faceForCp(cp!!, "pre", bookPreStack, weight, italic, mono, fs)
        assertNotNull("保底面表必须给出面（null 会被绘制侧退化成空 Font）", font)
        assertTrue(
            "U+%04X 族栈无人覆盖却拿到 .notdef（豆腐块）——保底面表没生效".format(cp),
            font!!.getUTF32Glyph(cp).toInt() != 0,
        )
        assertTrue(
            "U+%04X 必须落到真字体上，不是 %s".format(cp, font.typeface?.familyName),
            font.typeface?.familyName !in setOf("Last Resort"),
        )
    }

    // ── 锁 2：量画同源（取宽与取面必须落在同一张面、同一个宽）──

    @Test
    fun `保底路径的取宽与取面必须同源`() {
        val cp = pickUncoveredButInstallable()!!
        val ch = cp.toChar()
        val font = m.faceForCp(cp, "pre", bookPreStack, weight, italic, mono, fs)
        assertNotNull(font)
        val adv = m.advances(ch.toString(), fs, 0f, "pre", bookPreStack, weight, italic, mono)
        assertEquals(
            "量宽与落墨取到的不是同一张面：量=%.3f 画=%s".format(adv[0], font!!.typeface?.familyName),
            faceWidthPx(font, cp), adv[0], 0.001f,
        )
    }

    // ── 锁 3：不误伤（族栈能覆盖时，保底面不许抢位）──

    /**
     * 宿主上第一个能解出的**衬线**族名。
     *
     * 刻意不用等宽栈：等宽场景下保底表队首（`Menlo`）与族栈解出的面**恰好是同一张 Typeface**，
     * 「保底面在队首还是在队尾」根本测不出来 —— 那是**假绿锁**（本轮变异验证实测：把保底面挪到
     * 队首，等宽版锁3 依然全绿）。衬线栈才能让「栈里的面」与「保底表偏好的面」必然不同。
     */
    private fun serifStackOnHost(): String? = SERIF_PROBE.firstOrNull { name ->
        mgrs.any { runCatching { it.matchFamilyStyle(name, FontStyle.NORMAL) }.getOrNull() != null }
    }

    @Test
    fun `族栈能覆盖的码本必须仍取族栈那张面`() {
        val fam = serifStackOnHost()
        assertNotNull(
            "宿主上没有任何可解的衬线族（${SERIF_PROBE.joinToString()}）—— 本锁无从验证，请补候选",
            fam,
        )
        val stackFaces = mgrs.mapNotNull { runCatching { it.matchFamilyStyle(fam!!, FontStyle.NORMAL) }.getOrNull() }
        assertTrue("前置条件：$fam 至少要解出一张面", stackFaces.isNotEmpty())
        val cp = 'A'.code
        val font = m.faceForCp(cp, null, listOf(fam!!), weight, italic, false, fs)
        assertNotNull("族栈能覆盖时不得返回 null", font)
        assertTrue(
            "`A` 被保底面抢了位：拿到 %s，族栈 $fam 解出的是 %s（保底表恒接在末尾，不许抢）".format(
                font!!.typeface?.familyName, stackFaces.first().familyName,
            ),
            font.typeface in stackFaces,
        )
    }

    // ── 锁 4：族栈一个可匹配的族都没有时不崩 ──

    @Test
    fun `族栈完全匹配不上时不得崩`() {
        val bogus = listOf("__orilumn_no_such_family__", "__also_nope__")
        val cp = 'A'.code
        // 取宽：以前这里 fonts[0] 会越界。
        val adv = m.advances("A中─", fs, 0f, "pre", bogus, weight, italic, mono)
        assertEquals("三个码本都要拿到宽", 3, adv.size)
        for (a in adv) assertTrue("宽不得为负或 NaN（NaN 会把断行主循环带偏）", !a.isNaN() && a >= 0f)
        // 取面：以前这里返回 fonts[0] 越界；现在要么给保底面、要么给 null，都不崩。
        m.faceForCp(cp, "pre", bogus, weight, italic, mono, fs)
    }

    /** 宿主字体集探针（供人工核对候选表是否还够用；失败不影响上面任何一条）。 */
    @Test
    fun `宿主字体集探针`() {
        val sb = StringBuilder("已装族数=")
        sb.append(mgrs.sumOf { runCatching { it.familiesCount }.getOrDefault(0) })
        sb.append("  可用候选码本=")
        val ok = CANDIDATES.filter { pickFor(it) }
        sb.append(ok.joinToString(",") { "U+%04X".format(it) })
        println(sb)
        assertTrue(ok.isNotEmpty())
    }

    private fun pickFor(cp: Int): Boolean {
        val stack = SkParagraphFactory.resolveFamilies("pre", bookPreStack, mono)
        val stackFaces = stack.flatMap { f -> mgrs.mapNotNull { runCatching { it.matchFamilyStyle(f, FontStyle.NORMAL) }.getOrNull() } }
        val onStack = stackFaces.any { runCatching { Font(it, fs).getUTF32Glyph(cp).toInt() }.getOrDefault(0) != 0 }
        return !onStack && installedCovers(cp)
    }

    private companion object {
        /** 锁 3 用的衬线族探针（与 [SkParagraphFactory.genericFallbackFamilyNames] 的 serif 候选同源同序）。 */
        val SERIF_PROBE = listOf(
            "Times New Roman", "Times", "Georgia", "Songti SC", "STSong",
            "Noto Serif CJK SC", "SimSun", "DejaVu Serif",
        )

        /**
         * 候选码本：**跨文字系统**铺开，确保至少有一个在任意宿主上满足「族栈解不出 ∧ 已装字体解得出」
         * （拉丁等宽族通常只有拉丁 + 部分符号 + 制表符，撞不上 CJK / 全角 /  emoji / 数学 / 复杂文种）。
         */
        val CANDIDATES = intArrayOf(
            0x4E2D, 0x6587, // 中 文
            0xFF21, 0xFF41, // Ａ ａ（全角）
            0x2500, 0x2502, 0x251C, // ─ │ ├（制表符）
            0x2503, 0x2551, // ━ ║
            0x25A1, 0x25CF, // □ ●
            0x2190, 0x21D2, // ← ⇒（数学箭头）
            0x03B1, 0x03A9, // α Ω
            0x0412, 0x044F, // в я
            0x05D0, // א
            0x0627, // ا
            0x0E01, // ก
            0x1F600, // 😀（emoji）
            0x3042, 0x30A2, // あ ア（kana）
            0xAC00, // 가
        )
    }
}