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

    /**
     * 锁 6：**保底解出的字形必须不是豆腐块**。
     *
     * 整套兜底立在一条**假设**上：「`getUTF32Glyph(cp) != 0` ⇒ 这张面有真字形」。
     * 这条假设在某些宿主上可能不成立（Android 的 `sans-serif` 别名把系统回退链一起带进来）。
     * 一旦不成立，兜底就会「成功地选中一张画出来仍是豆腐块的面」——**比不兜底更坏**，
     * 因为它把缺陷藏起来了，让缺陷看起来像被修好了。所以这条假设本身必须被钉住。
     *
     * ## 判据：轮廓数，不是「和 .notdef 逐字节比」
     *
     * 后者**实测无效**：`.notdef` 经 skiko 在 macOS 与 Android 上取到的都是**空路径**，
     * 于是「解出的路径 == .notdef」对**任何空字形**都成立，NBSP/空格这种**合法空白**
     * 被误报成豆腐块（平板上实测误报），判据给不出可用信号。轮廓数分得开：
     * 豆腐块是空心矩形（8 个 verb），真字形几十个。
     */
    @Test
    fun `保底解出的字形必须不是豆腐块`() {
        val cp = CANDIDATES.firstOrNull { pickFor(it) }
        org.junit.Assume.assumeTrue("宿主上没有『族栈解不出 ∧ 已装字体解得出』的码本", cp != null)
        val face = m.faceForCp(cp!!, "pre", bookPreStack, weight, italic, mono, fs)
        assertNotNull("U+%04X 应当有保底面".format(cp), face)
        val font = face!!
        val gid = font.getUTF32Glyph(cp)
        assertTrue("U+%04X 的 glyph id 应非 0".format(cp), gid != 0.toShort())
        val verbs = font.getPath(gid)?.verbsCount ?: -1
        assertTrue(
            "U+%04X 落到了 %s#%d，轮廓数只有 %d（≤8 = 空心矩形，即豆腐块）".format(
                cp, face.typeface?.familyName, gid, verbs,
            ),
            verbs > TOFU_VERB_CEILING,
        )
        println("U+%04X → %s#%d 轮廓数=%d".format(cp, face.typeface?.familyName, gid, verbs))
    }

    /**
     * 测试侧探针：挑一个「**族名枚举解不出、但系统回退链解得出**」的码本；找不到返 null。
     *
     * **这是动态挑的，不钉死具体码本。** 钉死 `U+1F44D` 那种写法在 macOS 上是**永真空断言**
     * —— 枚举表里就有 Apple Color Emoji，生产代码压根走不到系统回退链，断言恒真、没有牙齿
     * （本项目已登记的假绿形态）。动态挑才能保证「挑中的那一刻，这条通道就是唯一的活路」。
     *
     * @param requireEnumerationMiss true = 只接受枚举表解不出的（锁系统链专用）；
     *   false = 只要系统链能解出即可（锁量画同源时用，让 macOS 也能真跑）。
     */
    private fun pickSystemChainOnlyCp(requireEnumerationMiss: Boolean): Int? =
        CANDIDATES.firstOrNull { cp ->
            val sysOk = systemChainCovers(cp)
            sysOk && (!requireEnumerationMiss || !installedCovers(cp))
        }

    /**
     * 测试侧 oracle：问**系统回退链**这个码本有没有字形。
     *
     * ⚠ 这不是独立 oracle（它用的就是被测那条通道），但只用于**挑探针码本**，
     * 不用于判定通过 —— 断言本身仍由生产路径的返回值担责。
     */
    private fun systemChainCovers(cp: Int): Boolean {
        val tf = runCatching {
            org.jetbrains.skia.paragraph.FontCollection()
                .setDefaultFontManager(SkParagraphFactory.defaultFontMgr())
                .defaultFallback(cp, FontStyle.NORMAL, null)
        }.getOrNull() ?: return false
        return runCatching { Font(tf, fs).getUTF32Glyph(cp).toInt() }.getOrDefault(0) != 0
    }

    /** 码本 → 字符串（含代理对，供 [SkiaRunMeasurer.advances] 的 UTF-16 槽位用）。 */
    private fun Int.toCodePointString(): String =
        if (this <= 0xFFFF) toChar().toString()
        else String(charArrayOf(Character.highSurrogate(this), Character.lowSurrogate(this)))

    private fun pickFor(cp: Int): Boolean {
        val stack = SkParagraphFactory.resolveFamilies("pre", bookPreStack, mono)
        val stackFaces = stack.flatMap { f -> mgrs.mapNotNull { runCatching { it.matchFamilyStyle(f, FontStyle.NORMAL) }.getOrNull() } }
        val onStack = stackFaces.any { runCatching { Font(it, fs).getUTF32Glyph(cp).toInt() }.getOrDefault(0) != 0 }
        return !onStack && installedCovers(cp)
    }

    /**
     * **锁 7 —— 系统回退链必须真的被问到**（跨宿主**只在枚举表够不到时有牙齿**）。
     *
     * ## 缺陷现场
     *
     * 真机报「之前用 skia 是好的」。真因不是设备缺字体，而是**两条通道不等宽**：
     * 旧 Skia 路径靠 `FontCollection` 的**系统回退链**（`defaultFallback(character, style, family)`），
     * 那条链**直接问宿主「哪个字体覆盖这个码本」**，不靠族名；
     * 我建的保底表是**按族名枚举**（平板 63 族）。vivo 平板上 `/system/fonts/NotoColorEmoji.ttf`
     * **存在**且 `U+1F44D`（👍）有字形，但那 63 个族名里**没有任何 emoji 族**
     * ⇒ 枚举表里根本没有这张面 ⇒ 日志误报「这台设备没装能画它的字体」。
     *
     * ## 为什么这把锁在 JVM 上会 skip 而不是假装通过
     *
     * macOS 的枚举表里**有** Apple Color Emoji ⇒ 枚举通道先命中 ⇒ 断言恒真、**没有牙齿**
     * （本项目已登记的假绿形态之一：永真空断言）。所以显式 `assumeTrue(!installedCovers(cp))`：
     * 枚举表能覆盖时**跳过**并说明原因，**绝不**报一个「绿」来骗人。
     * 它的牙齿在**真机**上（那里枚举表确实够不到 emoji）。
     */
    @Test
    fun `枚举表够不到的码本必须由系统回退链兜住`() {
        // **动态挑**：必须挑一个枚举表真的够不到的，否则本锁是空断言（macOS 上会挑不到 ⇒ skip）。
        val cp = pickSystemChainOnlyCp(requireEnumerationMiss = true)
        org.junit.Assume.assumeTrue(
            "本宿主上「族名枚举解不出 ∧ 系统回退链解得出」的码本一个都没有" +
                "（macOS 有 Apple Color Emoji，枚举表就够）⇒ 本锁在此宿主无牙齿，跳过",
            cp != null,
        )
        val face = m.faceForCp(cp!!, "pre", bookPreStack, 400, false, mono, fs)
        val gid = face?.getUTF32Glyph(cp)?.toInt() ?: 0
        assertTrue(
            "族名枚举解不出 U+%04X 时，系统回退链必须兜住（glyph id 应非 0）".format(cp),
            gid != 0,
        )
    }

    /**
     * **锁 8 —— 量画同源：系统链兜住的码本，量到的宽必须就是画出去那张面的宽**（**所有宿主都有牙齿**）。
     *
     * 这是锁 7 缺失的那一半：锁 7 在枚举表够得到的宿主上是空断言，而本锁不是 ——
     * 只要 `measure` 与 `faceForCp` 对**同一个码本**从**不同通道**解出面（或一个走系统链、另一个走 notdef），
     * 宽度就会对不上。真实踩过的坑就是这个形态：绘制侧走保底表、量宽侧还停在 notdef，
     * 屏幕上字位整体偏移，而任何「有没有解出字形」的断言都是绿的。
     *
     * 变异（只在 `measure` 侧摘掉 `systemFallbackFace` 调用）⇒ 必须红。
     */
    @Test
    fun `系统链兜住的码本其量宽必须等于绘制那张面的宽`() {
        // **遍历全部候选码本**，而不是挑一个 —— 挑一个会漏掉「某个码本走这条通道、
        // 另一个走那条通道」的分裂，那正是量画失配最常见的形态。
        //
        // 对每个码本走一遍完整链路：[faceForCp] 解面 → 量那张面的宽 → [advances] 量整行的宽，
        // 三者必须一致。任一环节从**不同的通道**解出面，宽度立刻对不上。
        //
        // ⚠ **这把锁的系统链牙齿只在真机上**（macOS 的枚举表已覆盖全部候选码本 ⇒ 那条通道是死代码，
        //   JVM 上怎么变异都是绿的）。真机上的验证手段是日志：
        //   `无字体的码本 U+1F44D` 这行**必须消失**（消失 = 系统链接住了它）。
        //   本锁在 JVM 上能牙齿的是「量画同源」这个更大的性质（对三条通道的任意分裂都敏感）。
        var checked = 0
        for (cp in CANDIDATES) {
            val face = m.faceForCp(cp, "pre", bookPreStack, 400, false, mono, fs)
            val gid = face?.getUTF32Glyph(cp)?.toInt() ?: 0
            // 这个码本连兜底都解不出（宿主真没字体）⇒ 不参与本锁，不是缺陷。
            if (gid == 0) continue
            checked++
            val faceW = faceWidthPx(face!!, cp)
            val text = "a" + cp.toCodePointString()
            val adv = m.advances(text, fs, 0f, "pre", bookPreStack, 400, false, mono)
            assertEquals(
                "U+%04X 量画必须同源（量=%.4f 画=%.4f，面=%s）".format(
                    cp, adv[1], faceW, face.typeface?.familyName,
                ),
                faceW,
                adv[1],
                0.01f,
            )
        }
        assertTrue(
            "候选码本一个都没解出 ⇒ 本锁什么也没验证（宿主字体集异常）",
            checked > 0,
        )
    }

    private companion object {
        /**
         * 轮廓数上限：**≤ 此值判为豆腐块**。豆腐块（.notdef 的典型画法）是空心矩形
         * = 两圈各 4 段 = 8 个 verb；真字形远超它（平板实测 `中`=30、`ア`=46）。
         * 宁可误报也不漏报：误报只多一行日志，漏报会让缺陷被当成修好了。
         */
        const val TOFU_VERB_CEILING = 8

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
            0x1F44D, // 👍（emoji；**vivo 平板上只有系统回退链能解出它**）
            0x1F9EA, // 🧪（emoji）
            0x3042, 0x30A2, // あ ア（kana）
            0xAC00, // 가
        )
    }
}