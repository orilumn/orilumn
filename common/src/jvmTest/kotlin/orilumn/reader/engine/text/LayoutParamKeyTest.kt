package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LayoutParamKey must change whenever the first-line indent changes. BookDocumentController.prepareFor
 * reuses the cached chapter prepare (whose styleMap embeds `textIndentPx`) unless the param hash
 * differs — an indent-only slider change therefore MUST produce a new hash, or the light re-layout
 * keeps shaping with the stale indent (Rust 简介 regression: 0→1 applies, every further change stuck).
 *
 * Migrated from app Robolectric to common jvmTest with S19: `TypographicProfile.build` is now pure
 * (no TextPaint), so no Android runtime is needed.
 */
class LayoutParamKeyTest {

    private fun hash(settings: ReaderSettings): Long {
        val p = TypographicProfile.build(settings)
        return LayoutParamKey.fromProfile(p, 1920, 2400).hash()
    }

    @Test
    fun `first-line indent changes the param hash`() {
        val a = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 0.0))
        val b = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 1.0))
        assertNotEquals(a, b)
    }

    @Test
    fun `same indent yields the same hash`() {
        val a = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 2.0))
        val b = hash(ReaderSettings.DEFAULT.copy(firstLineIndent = 2.0))
        assertEquals(a, b)
    }

    @Test
    fun `weight anchor changes the param hash`() {
        val a = hash(ReaderSettings.DEFAULT)
        val b = hash(ReaderSettings.DEFAULT.copy(fontWeightAnchors = mapOf("F" to 700)))
        assertNotEquals(a, b)
        val c = hash(ReaderSettings.DEFAULT.copy(fontWeightAnchors = mapOf("F" to 700)))
        assertEquals(b, c)
    }

    /**
     * S3 接线锁：断行器变体必须进 `paramHash`。
     *
     * 这条不是「顺手加的」——T2f 实测发现 `paramHash` 也不含禁则表身份，
     * 于是真机 A/B 若不清 `cache/pagination` 就会命中按另一侧规则算出的旧表，
     * 读到「改动没生效」的假零差异。断行器变体是同一类坑的第二例，
     * 故在接线当刻就把这条钉死（教训 28）。
     */
    @Test
    fun `line breaker variant changes the param hash`() {
        val p = TypographicProfile.build(ReaderSettings.DEFAULT)
        val skia = LayoutParamKey.fromProfile(p, 1920, 2400, inhouseBreak = false).hash()
        val inhouse = LayoutParamKey.fromProfile(p, 1920, 2400, inhouseBreak = true).hash()
        assertNotEquals(
            "断行器变体换 ⇒ 断点换 ⇒ 页切点换；不进键则拨开关会命中按另一侧断点算出的旧磁盘表",
            skia, inhouse,
        )
        // 同侧稳定：同一变体两次构造必须同键，否则缓存会无谓抖动。
        assertEquals(skia, LayoutParamKey.fromProfile(p, 1920, 2400, inhouseBreak = false).hash())
        assertEquals(inhouse, LayoutParamKey.fromProfile(p, 1920, 2400, inhouseBreak = true).hash())
    }

    /**
 * 默认值必须**跟着运行期开关**走（`fromProfile` 的形参默认 = `AbSwitch.inhouseBreak()`），
 * 否则 18 个调用点会各自钉死一个常量，真机拨 `ab="inhouseBreak=0"` 时
 * 布局换了、缓存键没换 ⇒ 又一次假零差异。
 *
 * 变体默认已是 on（2026-10-01），故「默认侧」= 自建侧；Skia 侧要用**显式关**去取。
 */
@Test
    fun `fromProfile default follows the runtime AbSwitch`() {
        val p = TypographicProfile.build(ReaderSettings.DEFAULT)
        orilumn.reader.engine.AbSwitch.resetForTest()
        val defaultHash = LayoutParamKey.fromProfile(p, 1920, 2400).hash()
        val onExplicit = LayoutParamKey.fromProfile(p, 1920, 2400, inhouseBreak = true).hash()
        val offExplicit = LayoutParamKey.fromProfile(p, 1920, 2400, inhouseBreak = false).hash()
        try {
            // 默认 = 自建（复位回的是各自默认值，不是全关）。
            assertEquals(
                "默认无参调用必须产出与显式 true 相同的键（默认变体为自建）",
                onExplicit, defaultHash,
            )
            // 显式关：键必须跟着换，否则真机上一拨开关就命中另一侧的旧磁盘表。
            orilumn.reader.engine.AbSwitch.apply("inhouseBreak=0")
            assertTrue(
                "ab=\"inhouseBreak=0\" 必须真的关得掉（回退阀的静态前提）",
                !orilumn.reader.engine.AbSwitch.inhouseBreak(),
            )
            assertEquals(
                "开关关闭时，无参调用必须产出与显式 false 相同的键",
                offExplicit, LayoutParamKey.fromProfile(p, 1920, 2400).hash(),
            )
            // 显式开必须能覆盖先前的显式关（三态表优先级：显式开 > 显式关 > 默认开）。
            orilumn.reader.engine.AbSwitch.apply("inhouseBreak=1")
            assertEquals(
                "显式 1 必须能覆盖显式 0，且键随之回到自建侧",
                onExplicit, LayoutParamKey.fromProfile(p, 1920, 2400).hash(),
            )
        } finally {
            // 必须复位：不复位会污染同 JVM 里后续每一个走 fromProfile 默认值的用例
            //（失败面貌与本次改动无关）。
            orilumn.reader.engine.AbSwitch.resetForTest()
        }
        // 复位 = 回默认（= 自建），**不是**回全关 —— 后者会让测试拿到与生产不同的静止位。
        assertEquals(
            "复位后必须回到默认变体（自建），而不是全关",
            "inhouseBreak", orilumn.reader.engine.AbSwitch.describe(),
        )
        assertEquals(
            "复位后无参调用必须回到默认侧的键",
            defaultHash, LayoutParamKey.fromProfile(p, 1920, 2400).hash(),
        )
        // 显式 false 仍是权威（不依赖全局状态）。
        assertEquals(offExplicit, LayoutParamKey.fromProfile(p, 1920, 2400, inhouseBreak = false).hash())
    }

    @Test
    fun `portable crc32 is byte-identical to java util zip crc32`() {
        val base = LayoutParamKey(
            bodyPx = 18.5f, lineSpacing = 1.5f, firstLineIndentEm = 2f, letterSpacingEm = 0f,
            paragraphGapScale = 1f,
            fontBody = "霞鹜文楷", fontTitle = "LXGW WenKai", fontCode = "",
            useOriginalStyle = false, contentW = 1080, contentH = 1920, userCssHash = 123456789,
        )
        assertEquals(javaCrc32(base), base.hash())

        val emptyFonts = base.copy(fontBody = "", fontTitle = "", fontCode = "")
        assertEquals(javaCrc32(emptyFonts), emptyFonts.hash())

        val flipped = base.copy(useOriginalStyle = true, contentW = 0, userCssHash = 0)
        assertEquals(javaCrc32(flipped), flipped.hash())

        // S3：两个变体都要过参考实现（否则这条锁只覆盖默认侧，新字段的喂入序无人看守）。
        assertEquals(javaCrc32(base.copy(inhouseBreak = true)), base.copy(inhouseBreak = true).hash())
        // 混排字距非 0：走的是**另一条喂入分支**，同样必须过参考实现。
        assertEquals(javaCrc32(base.copy(cjkLatinSpacingEm = 0.25f)), base.copy(cjkLatinSpacingEm = 0.25f).hash())
        // 两个变长哨兵同时非默认（最容易被写错的一组：两个追加都触发）。
        assertEquals(
            javaCrc32(base.copy(inhouseBreak = true, cjkLatinSpacingEm = 0.25f)),
            base.copy(inhouseBreak = true, cjkLatinSpacingEm = 0.25f).hash(),
        )
    }

    /**
     * 混排字距锁：**任何取值都必须换键**（含 0），且喂入流逐字节钉死。
     *
     * - 「换键」：间隙改变行宽 ⇒ 换断点 ⇒ 换页切点。键若不变，就会命中按另一种版面算出的
     *   旧磁盘表，读到「滑块拖了但书没变」的假象（教训 28 同一类）。
     * - **0 也要换键**（2026-10-03 改）：0 档不再是「特性关掉」，而是「边界照检、作者手打的
     *   分隔空格照吃、注入间隙宽 = 0」⇒ 0 档的版面**确实不同**（见
     *   [orilumn.reader.engine.text.preprocess.CjkLatinSpacing] 类 KDoc）。
     *   旧口径靠「0 不喂入」让 0 精确复现接线前的历史流，那条固定点随语义变更一同作废。
     *
     * ## 为什么必须在这里单独钉金标准数字
     *
     * 上面 `回退侧…逐字节等于参考喂入流` 那条金标准只钉了**一个** profile 的字节流。
     * 「混排字距这一项怎么喂」是**逐键成立**的性质，若只靠那一条间接覆盖，写成
     * `assertNotEquals(base.hash(), base.copy(cjkLatinSpacingEm = 0.25f).hash())`
     * 在旧口径下**照样成立**（旧口径非 0 也换键），压根抓不到「0 是否喂入」。
     *
     * 所以本锁用一个**与上面不同**的 profile，把 0 侧与非 0 侧的字节流**各自**钉成常量：
     * 喂入序一改（哪怕只对某些键生效）、或者改成有条件喂，这两个数字立刻有一个对不上。
     * 突变验证：把 `crc = Crc32.update4(crc, cjkLatinSpacingEm.bits())` 改回
     * `if (cjkLatinSpacingEm != 0f) …` ⇒ 本锁红（0 侧的数对不上）。
     *
     * 数字由 Python 独立复刻喂入序算出（`zlib.crc32` 与 `java.util.zip.CRC32` 同算法）。
     *
     * 2026-10-03 只有 **0 侧**的数变了：`3165348332 → 2078022158`（尾部多 4 个 0x00）。
     * **非 0 侧仍是 `2317192732`，一个字节都没动** —— 旧口径在非 0 时喂的也是这同样的
     * 4 字节、同样在末尾，故条件喂与无条件喂对非 0 键**产出同一条流**。
     * 这也说明本锁真正的新增覆盖面只在 0 侧：喂入序若被改坏成「喂在中间」或「喂错值」，
     * 非 0 侧的数字仍然对得上，只有 0 侧那把会红。
     */
    @Test
    fun `混排字距任何取值都换键、且喂入流逐字节钉死`() {
        val base = LayoutParamKey(
            bodyPx = 20f, lineSpacing = 1.75f, firstLineIndentEm = 0f, letterSpacingEm = 0.05f,
            paragraphGapScale = 0.5f,
            fontBody = "", fontTitle = "", fontCode = "",
            useOriginalStyle = true, contentW = 1200, contentH = 1600, userCssHash = 0,
            inhouseBreak = false,
        )
        // 0 侧的喂入流金标准（本 profile 专属，非上面那条的那个数）。
        // 2026-10-06：`2078022158` → **`1533737463**。`paragraphSpacingPx` 退役（段间距即疏密），
        // 喂入流少 4 字节；老磁盘表对不上新键，一次性重建（与 2026-10-03 混排字距改口径同理）。
        assertEquals(
            "cjkLatinSpacingEm = 0 的喂入流（四个 0x00 追加在末尾）必须逐字节等于金标准 1533737463",
            1533737463L,
            base.hash() and 0xFFFFFFFFL,
        )
        assertEquals(
            "显式写 0 与走字段默认值必须同键（否则「默认构造」本身就是一条旁路）",
            base.hash(),
            base.copy(cjkLatinSpacingEm = 0f).hash(),
        )
        val gap = base.copy(cjkLatinSpacingEm = 0.25f)
        assertNotEquals(
            "混排字距非 0 必须换键，否则会命中按另一种版面算出的旧磁盘表",
            base.hash(),
            gap.hash(),
        )
        // 非 0 侧的**金标准数字**：喂入位置与值一起钉死。只钉「非 0 换键」是钉不住的 ——
        // 「条件喂但喂在中间」同样满足「非 0 换键」，却会让后续字段整体左移 4 字节、
        // 与另一种键取值撞流（歧义）。钉死字节流就同时钉死了值与位置。
        assertEquals(
            "非 0 侧的喂入流（末尾追加 4 字节）必须逐字节等于金标准 2863361509；" +
                "条件喂与非 0 无条件喂对非 0 键必须产出同一条流",
            2863361509L,
            gap.hash() and 0xFFFFFFFFL,
        )
        // 位置敏感：与别的字段**同值**也必须换键（否则两个版面共用一份表）。
        // 这两条是「不能把中文字距塞进 `letterSpacingEm` 或 `inhouseBreak` 的喂入槽」的守卫。
        assertNotEquals(
            "混排字距不得与 letterSpacingEm 混用同一个喂入槽",
            base.copy(letterSpacingEm = 0.25f).hash(),
            gap.hash(),
        )
        assertNotEquals(
            "混排字距不得与 inhouseBreak 混用同一个喂入槽",
            base.copy(inhouseBreak = true).hash(),
            gap.hash(),
        )
    }

    /**
     * S3 接线锁（**喂入流金标准**）：回退侧（`inhouseBreak=false`）的 `paramHash` 必须**逐字节**
     * 等于参考喂入序算出的值。
     *
     * ## 为什么单独钉一条金标准
     *
     * `paramHash` 是分页缓存的**文件名**。喂入序任何一处改动（哪怕只对某些键生效）都会让
     * 所有老用户的分页缓存一次性全废，必须靠**金标准数字**而不是「跑通了」来抓。
     *
     * `inhouseBreak` 那一项仍是「变长喂入」（末尾、只在 true 时追加 4 字节）。
     * 混排字距那一项自 2026-10-03 起是**无条件喂入**（0 档也会改版面，见
     * [orilumn.reader.engine.text.preprocess.CjkLatinSpacing] 类 KDoc），
     * 故本锁的金标准在那天随之后移。
     *
     * ## 数字怎么来的
     *
     * 由 Python 独立复刻旧喂入序算出（112 字节 + 混排字距 4 字节，`zlib.crc32` 与
     * `java.util.zip.CRC32` 同算法），与本文件 `javaCrc32` 参考实现、以及生产 `Crc32`
     * 三方独立算出同一个值，才敢当金标准。
     *
     * （第一次算成 `3740307646` 是 Python 复刻写错：把 4 字节字符串终止符写进了 per-char 循环里，
     * 导致每字多喂 4 个零字节。这个坑本身也说明「手写喂入序」的参考实现必须逐字节对照着写。）
     *
     * 2026-10-03：`908642712` → **`1428783275`**。混排字距从「非 0 才喂的变长哨兵」改成
     * 「无条件喂 4 字节」，字节流尾部因此多出 4 个字节（0 档即四个 0x00）。
     * **这不是「键被无意改坏」**：0 档现在真的会改版面（作者手打的分隔空格被吃掉），
     * 沿用旧键会让键与它描述的版面互相矛盾。
     */
    @Test
    fun `回退侧（inhouseBreak=false）param hash 逐字节等于参考喂入流`() {
        // **必须显式写 `inhouseBreak = false`**，不能靠字段默认值：
        // 变体默认已改成 on（2026-10-01），靠默认值的话本锁会跟着一起漂，
        // 于是金标准就变成了自建侧的值 —— 锁还在，但它守着的东西变了。
        //
        // 这条锁守的是**回退侧**（真机 `inhouseBreak=0` 那条路）与**喂入序本身**：
        // 回退必须落在当前 schema 算出的那个键上。
        val base = LayoutParamKey(
            bodyPx = 18.5f, lineSpacing = 1.5f, firstLineIndentEm = 2f, letterSpacingEm = 0f,
            paragraphGapScale = 1f,
            fontBody = "霞鹜文楷", fontTitle = "LXGW WenKai", fontCode = "",
            useOriginalStyle = false, contentW = 1080, contentH = 1920, userCssHash = 123456789,
            inhouseBreak = false,
        )
        assertEquals(
            "回退侧 paramHash 必须等于当前喂入序的金标准（4159404608）",
            4159404608L,
            base.hash() and 0xFFFFFFFFL,
        )
        assertEquals(
            "回退侧必须与显式 false 构造出的键逐字节相同",
            base.hash(),
            base.copy(inhouseBreak = false).hash(),
        )
        assertNotEquals(
            "自建变体必须换键，否则拨开关会命中按 Skia 断点算出的旧表",
            base.hash(),
            base.copy(inhouseBreak = true).hash(),
        )
    }

    /** Reference: feed the same byte stream through java.util.zip.CRC32 (the pre-S19 implementation). */
    private fun javaCrc32(k: LayoutParamKey): Long {
        val j = java.util.zip.CRC32()
        fun Float.feed4() {
            val v = toBits().toLong()
            j.update(((v shr 24) and 0xFF).toInt())
            j.update(((v shr 16) and 0xFF).toInt())
            j.update(((v shr 8) and 0xFF).toInt())
            j.update((v and 0xFF).toInt())
        }
        fun Int.feed4() {
            val v = toLong() and 0xFFFFFFFFL
            j.update(((v shr 24) and 0xFF).toInt())
            j.update(((v shr 16) and 0xFF).toInt())
            j.update(((v shr 8) and 0xFF).toInt())
            j.update((v and 0xFF).toInt())
        }
        fun String.feed() {
            for (ch in this) {
                val v = ch.code.toLong()
                j.update(((v shr 24) and 0xFF).toInt())
                j.update(((v shr 16) and 0xFF).toInt())
                j.update(((v shr 8) and 0xFF).toInt())
                j.update((v and 0xFF).toInt())
            }
            j.update(0); j.update(0); j.update(0); j.update(0)
        }
        k.bodyPx.feed4(); k.lineSpacing.feed4(); k.firstLineIndentEm.feed4()
        k.letterSpacingEm.feed4()
        k.paragraphGapScale.feed4()
        k.fontBody.feed(); k.fontTitle.feed(); k.fontCode.feed()
        k.fontWeightAnchors.toSortedMap().forEach { (fam, w) -> fam.feed(); w.feed4() }
        (if (k.useOriginalStyle) 1L else 0L).let { v ->
            j.update(((v shr 24) and 0xFF).toInt())
            j.update(((v shr 16) and 0xFF).toInt())
            j.update(((v shr 8) and 0xFF).toInt())
            j.update((v and 0xFF).toInt())
        }
        k.contentW.feed4(); k.contentH.feed4(); k.userCssHash.feed4()
        // S3 追加字段：断行器变体。**变长喂入——只在 true 时追加**，与 `hash()` 同式。
        // 参考实现必须逐字节跟着 schema 走：这条锁的全部意义就是「手写喂入序 == 生产喂入序」，
        // 少喂一轮（或多喂一轮）它就名存实亡了。
        if (k.inhouseBreak) {
            j.update(0); j.update(0); j.update(0); j.update(1)
        }
        // 混排字距：**无条件**喂 4 字节（2026-10-03 起；原为「非 0 才喂」的变长哨兵，
        // 因 0 档改成「照吃分隔空格、只是间隙宽 0」而作废），位置仍在喂入序最后。
        // 参考实现必须逐字节跟着 schema 走：少喂一轮它就名存实亡了。
        k.cjkLatinSpacingEm.feed4()
        return j.value
    }
}