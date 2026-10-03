package orilumn.reader.engine.text

/**
 * A stable, reproducible key that uniquely identifies a "layout parameter combination" — i.e. the
 * exact set of inputs that determine how a chapter paginates. Two chapters with the same
 * [LayoutParamKey] will produce identical pagination tables (identical shaping inputs →
 * identical line breaks → identical page cuts).
 *
 * Only fields that affect **shaping or pagination** are included. Purely visual fields
 * (foreground/background color) are intentionally left out — changing theme colors must not
 * invalidate the pagination cache.
 *
 * The [hash] is a 64-bit CRC-32 over the serialized fields (standard IEEE 802.3, byte-for-byte
 * identical to the pre-S19 `java.util.zip.CRC32` feed, see [Crc32]). Good enough for a
 * content-addressable cache filename; the collision probability at this scale (a few dozen
 * parameter combinations per book) is negligible.
 */
data class LayoutParamKey(
    val bodyPx: Float,
    val lineSpacing: Float,
    val firstLineIndentEm: Float,
    val letterSpacingEm: Float,
    val cjkLatinSpacingEm: Float = 0f,
    val paragraphSpacingPx: Int,
    val paragraphGapScale: Float,
    val fontBody: String,
    val fontTitle: String,
    val fontCode: String,
    /** 按族字重锚点（影响取面 → 影响度量，必须进缓存键）。 */
    val fontWeightAnchors: Map<String, Int> = emptyMap(),
    val useOriginalStyle: Boolean,
    val contentW: Int,
    val contentH: Int,
    val userCssHash: Int,
    /**
     * 断行器变体（`true` = 自建断行器，**默认**；`false` = Skia `Paragraph`，回退阀）。
     *
     * **必须进键，否则开关在真机上量不出效果**：换断行器 ⇒ 换断点 ⇒ 换页切点，
     * 而键不含它就会命中按另一侧断点算出的旧磁盘表，读到「开关没生效」的假零差异
     * （与本键不含禁则表身份是同一类坑，见 `docs/自建断行引擎-测试计划.md` §T2f / 教训 28）。
     *
     * 它进键之后，`LAYOUT_VERSION` 就**不必**为 S3 接线而 bump：键已能精确区分两侧，
     * 两侧各自独立缓存，回退时能各自命中自己那份。
     *
     * 默认 `true`（2026-10-01，自建断行器成为生产主路径）。**注意这个默认值只作用于
     * 直接构造本 data class 的调用点**；生产走 [fromProfile]，其默认形参读
     * [orilumn.reader.engine.AbSwitch.inhouseBreak] —— 真值单一在开关那边。
     * 两处默认值必须一致，否则会出现「键按一侧算、实际按另一侧跑」的静默错误。
     */
    val inhouseBreak: Boolean = true,
) {
    /** Deterministic 64-bit hash over all layout-affecting fields. */
    fun hash(): Long {
        var crc = Crc32.INIT
        // Field order matters: fixed schema → fixed hash.
        crc = Crc32.update4(crc, bodyPx.bits())
        crc = Crc32.update4(crc, lineSpacing.bits())
        crc = Crc32.update4(crc, firstLineIndentEm.bits())
        crc = Crc32.update4(crc, letterSpacingEm.bits())
        crc = Crc32.update4(crc, paragraphSpacingPx.bits())
        crc = Crc32.update4(crc, paragraphGapScale.bits())
        crc = Crc32.updateString(crc, fontBody)
        crc = Crc32.updateString(crc, fontTitle)
        crc = Crc32.updateString(crc, fontCode)
        // 锚点按族名排序喂入（顺序无关，拼进总签名）。
        for ((fam, w) in fontWeightAnchors.toSortedMap()) {
            crc = Crc32.updateString(crc, fam)
            crc = Crc32.update4(crc, w.bits())
        }
        crc = Crc32.update4(crc, if (useOriginalStyle) 1L else 0L)
        crc = Crc32.update4(crc, contentW.bits())
        crc = Crc32.update4(crc, contentH.bits())
        crc = Crc32.update4(crc, userCssHash.bits())
        // S3 断行器变体：**只在 true 时追加哨兵字节**（变长喂入），不是无条件喂 0/1。
        //
        // 无条件喂的后果：默认侧（false）也会多出 4 个零字节 ⇒ 所有老用户的 paramHash 全变
        // ⇒ 分页缓存键全部对不上 ⇒ 一次**没有必要的全量作废**。而默认侧输出与接线前逐字节
        // 相同，键理应也逐字节相同。
        //
        // 变长喂入会不会引入歧义？不会：该字段在喂入序**最后**，false 复现旧流、true 是旧流
        // 追加 4 字节，两侧取值不同且 false 精确等于历史值。
        if (inhouseBreak) crc = Crc32.update4(crc, 1L)
        // 混排字距（em）：**无条件喂入 4 字节**（2026-10-03 改，原为「非 0 才喂」的变长哨兵）。
        //
        // ## 为什么哨兵作废了
        //
        // 原口径把 `cjkLatinSpacingEm == 0` 定义成「整条特性不生效」，于是 0 的键必须**逐字节**
        // 复现接线前的历史流（老用户升级不必全书重排）。现在 0 是**参数为 0 的那一档**：
        // 边界照检、被吃掉的作者空格照吃（画成零宽）、注入间隙宽 = 0
        // （见 [orilumn.reader.engine.text.preprocess.CjkLatinSpacing] 类 KDoc）。
        // ⇒ **0 现在真的会改版面**（`Rust 的所有权` → `Rust的所有权`），它与「非 0」一样必须换键。
        //
        // 代价：装过 0 档的老用户升级后全书重排一次（一次性）。这是语义变更的必然代价，
        // 不是缺陷 —— 沿用旧键只会让版面与键**互相矛盾**（键说有间隙、表里没有）。
        //
        // ⚠ 放在喂入序**最后**这一点仍保留：变长喂入在中间会真的产生歧义；此处虽已无条件喂，
        //   但新字段一律追加在末尾，未来再加字段仍照此办理。
        crc = Crc32.update4(crc, cjkLatinSpacingEm.bits())
        return Crc32.finish(crc)
    }

    companion object {
        /** Builds a [LayoutParamKey] from a [TypographicProfile] + the physical page dimensions
         *  and an optional hash of any user-provided CSS overrides.
         *
         *  [inhouseBreak] **默认就读 [orilumn.reader.engine.AbSwitch] 的运行期开关**
         *  （而不是写死 `false`）：18 个调用点零改动、读点唯一，拨开关即换键。
         *  显式传参仅供测试构造特定键。 */
        fun fromProfile(
            profile: TypographicProfile,
            contentW: Int,
            contentH: Int,
            userCssHash: Int = 0,
            inhouseBreak: Boolean = orilumn.reader.engine.AbSwitch.inhouseBreak(),
        ): LayoutParamKey = LayoutParamKey(
            bodyPx = profile.bodyPx,
            lineSpacing = profile.lineSpacing,
            firstLineIndentEm = profile.firstLineIndentEm,
            letterSpacingEm = profile.letterSpacingEm,
            // **喂闸过的值，不是原始设置值**：键必须描述「实际被施加到版面上的那个值」。
            // 开关 off 时实测不施加间隙（否则溢出被裁），而键若还记着 25，回退期间就会按
            // 「有间隙」算行宽去命中磁盘表、却按「无间隙」画 ⇒ 量画失配（教训 ⑩）。
            // 本形参 `inhouseBreak` 的默认值已经读过开关，这里复用同一个闸门，两者恒一致。
            cjkLatinSpacingEm = if (inhouseBreak) profile.cjkLatinSpacingEm else 0f,
            paragraphSpacingPx = profile.paragraphSpacingPx,
            paragraphGapScale = profile.paragraphGapScale,
            fontBody = profile.fontBody,
            fontTitle = profile.fontTitle,
            fontCode = profile.fontCode,
            fontWeightAnchors = profile.fontWeightAnchors,
            useOriginalStyle = profile.useOriginalStyle,
            contentW = contentW,
            contentH = contentH,
            userCssHash = userCssHash,
            inhouseBreak = inhouseBreak,
        )
    }
}

private fun Float.bits(): Long = this.toBits().toLong()

private fun Int.bits(): Long = this.toLong() and 0xFFFFFFFFL