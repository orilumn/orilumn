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
            paragraphSpacingPx = 28, paragraphGapScale = 1f,
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
    }

    /**
     * S3 接线锁（**向后兼容金标准**）：默认侧的 `paramHash` 必须**逐字节等于接线前的历史值**。
     *
     * ## 为什么单独钉一条金标准
     *
     * `paramHash` 是分页缓存的**文件名**。默认侧（`inhouseBreak=false`）的布局输出与接线前
     * 逐字节相同，若键也变了，就是一次**纯浪费的全量作废** —— 每个老用户升级后所有章都要重排。
     *
     * 这里的实现选择是「变长喂入」：字段在喂入序最后，**只在 true 时追加 4 字节**，
     * 于是 false 精确复现旧流。代价是 schema 不再定长，但该字段在末尾且两侧取值不同，
     * 不存在歧义（若放在中间，变长喂入会真的产生歧义）。
     *
     * ## 数字怎么来的
     *
     * 由 Python 独立复刻旧喂入序算出（112 字节，`zlib.crc32` 与 `java.util.zip.CRC32` 同算法），
     * 与本文件 `javaCrc32` 参考实现、以及生产 `Crc32` 三方独立算出同一个值，才敢当金标准。
     *
     * （第一次算成 `3740307646` 是 Python 复刻写错：把 4 字节字符串终止符写进了 per-char 循环里，
     * 导致每字多喂 4 个零字节。这个坑本身也说明「手写喂入序」的参考实现必须逐字节对照着写。）
     */
    @Test
    fun `回退侧（inhouseBreak=false）param hash 逐字节等于接线前 schema`() {
        // **必须显式写 `inhouseBreak = false`**，不能靠字段默认值：
        // 变体默认已改成 on（2026-10-01），靠默认值的话本锁会跟着一起漂，
        // 于是「接线前历史值」这条金标准就变成了自建侧的值 —— 锁还在，但它守着的东西变了。
        //
        // 这条锁现在守的是**回退侧**（真机 `inhouseBreak=0` 那条路）：回退必须精确落回
        // 接线前的字节流，否则用户从自建退回 Skia 时会拿到一份从未存在过的缓存键。
        val base = LayoutParamKey(
            bodyPx = 18.5f, lineSpacing = 1.5f, firstLineIndentEm = 2f, letterSpacingEm = 0f,
            paragraphSpacingPx = 28, paragraphGapScale = 1f,
            fontBody = "霞鹜文楷", fontTitle = "LXGW WenKai", fontCode = "",
            useOriginalStyle = false, contentW = 1080, contentH = 1920, userCssHash = 123456789,
            inhouseBreak = false,
        )
        assertEquals(
            "回退侧 paramHash 必须等于接线前历史值（908642712），否则回退会落到一份不存在的键上",
            908642712L,
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
        k.letterSpacingEm.feed4(); k.paragraphSpacingPx.feed4()
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
        return j.value
    }
}