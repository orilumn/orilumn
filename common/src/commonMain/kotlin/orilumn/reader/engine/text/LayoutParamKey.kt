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
    /** 行距倍数；**`null`（读者没配）用 NaN 编码**，与任何实数（含 0）区分——两者版面不同。 */
    val lineSpacing: Float,
    /** 首行缩进 em；**`null`（读者没设）用 NaN 编码**，与 0（明确不缩进）区分——两者版面不同，
     *  键必须能分开（NaN 的位模式稳定，不会和任何实数撞）。 */
    val firstLineIndentEm: Float,
    /** 字间距 em；**`null`（读者没配 = 按书）用 NaN 编码**，与 0（明确不加字距）区分。
     *  ⚠ 读者没配时实际生效的是**书自己的**字距，书的内容变化由章节内容/样式哈希另管。 */
    val letterSpacingEm: Float,
    val cjkLatinSpacingEm: Float = 0f,
    /** 段间距绝对值 em；**`null`（读者没配）用 NaN 编码**，与 0（明确清零）区分——两者版面不同。 */
    val paragraphSpacingEm: Float,
    val paragraphGapScale: Float,
    val fontBody: String,
    val fontTitle: String,
    val fontCode: String,
    /** 用户在该族选的字重（UI 层声明进级联 ⇒ 改变 `font-weight` ⇒ 影响取面与度量，必须进缓存键）。 */
    val fontWeightAnchors: Map<String, Int> = emptyMap(),
    /** 同上但**按槽位隔离**（key = "slot|family"）。 */
    val fontWeightAnchorsBySlot: Map<String, Int> = emptyMap(),
    val useOriginalStyle: Boolean,
    val contentW: Int,
    val contentH: Int,
    val userCssHash: Int,
    /**
     * 排版主题 key（`original` / `modern` / `traditional`）：主题层（tier 42）按它决定加载哪张
     * 样式表，字体族/缩进/段距/对齐都可能不同 ⇒ **必须进键**。以前靠 `withLayoutTheme`
     * 顺带改字体槽/段距把键带变，现在主题只切样式表，裸切主题必须自己换键。
     */
    val layoutTheme: String = "original",
    /** 通用族替换（`serif`/`sans-serif` → 读者选的族）：改变族栈 ⇒ 改变取面与度量 ⇒ 进键。 */
    val fontSerif: String = "",
    val fontSans: String = "",
    /** 全局字体槽（代码/衬线/无衬线）的按槽字重：与 [fontWeightAnchorsBySlot] 一样改 `font-weight`。 */
    val fontWeightAnchorsGlobal: Map<String, Int> = emptyMap(),
) {
    /** Deterministic 64-bit hash over all layout-affecting fields. */
    fun hash(): Long {
        var crc = Crc32.INIT
        // Field order matters: fixed schema → fixed hash.
        crc = Crc32.update4(crc, bodyPx.bits())
        crc = Crc32.update4(crc, lineSpacing.bits())
        crc = Crc32.update4(crc, firstLineIndentEm.bits())
        crc = Crc32.update4(crc, letterSpacingEm.bits())
        crc = Crc32.update4(crc, paragraphSpacingEm.bits())
        crc = Crc32.update4(crc, paragraphGapScale.bits())
        crc = Crc32.updateString(crc, fontBody)
        crc = Crc32.updateString(crc, fontTitle)
        crc = Crc32.updateString(crc, fontCode)
        // 锚点按族名排序喂入（顺序无关，拼进总签名）。
        for ((fam, w) in fontWeightAnchors.toSortedMap()) {
            crc = Crc32.updateString(crc, fam)
            crc = Crc32.update4(crc, w.bits())
        }
        // 按槽位锚点：key "slot|fam" 按 key 排序
        for ((k, w) in fontWeightAnchorsBySlot.toSortedMap()) {
            crc = Crc32.updateString(crc, k)
            crc = Crc32.update4(crc, w.bits())
        }
        crc = Crc32.update4(crc, if (useOriginalStyle) 1L else 0L)
        crc = Crc32.update4(crc, contentW.bits())
        crc = Crc32.update4(crc, contentH.bits())
        crc = Crc32.update4(crc, userCssHash.bits())
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
        // 排版主题（新字段一律追加在末尾，见上）。
        crc = Crc32.updateString(crc, layoutTheme)
        // 通用族替换与全局槽字重（`serif`/`sans-serif` 的族栈与字重都会改取面/度量）。
        crc = Crc32.updateString(crc, fontSerif)
        crc = Crc32.updateString(crc, fontSans)
        for ((k, w) in fontWeightAnchorsGlobal.toSortedMap()) {
            crc = Crc32.updateString(crc, k)
            crc = Crc32.update4(crc, w.bits())
        }
        return Crc32.finish(crc)
    }

    companion object {
        /** Builds a [LayoutParamKey] from a [TypographicProfile] + the physical page dimensions
         *  and an optional hash of any user-provided CSS overrides. */
        fun fromProfile(
            profile: TypographicProfile,
            contentW: Int,
            contentH: Int,
            userCssHash: Int = 0,
        ): LayoutParamKey = LayoutParamKey(
            bodyPx = profile.bodyPx,
            lineSpacing = profile.lineSpacing ?: Float.NaN,
            firstLineIndentEm = profile.firstLineIndentEm ?: Float.NaN,
            letterSpacingEm = profile.letterSpacingEm ?: Float.NaN,
            cjkLatinSpacingEm = profile.cjkLatinSpacingEm,
            paragraphSpacingEm = profile.paragraphSpacingEm ?: Float.NaN,
            paragraphGapScale = profile.paragraphGapScale,
            fontBody = profile.fontBody,
            fontTitle = profile.fontTitle,
            fontCode = profile.fontCode,
            fontWeightAnchors = profile.fontWeightAnchors,
            fontWeightAnchorsBySlot = profile.fontWeightAnchorsBySlot,
            useOriginalStyle = profile.useOriginalStyle,
            layoutTheme = profile.layoutTheme,
            fontSerif = profile.fontSerif,
            fontSans = profile.fontSans,
            fontWeightAnchorsGlobal = profile.fontWeightAnchorsGlobal,
            contentW = contentW,
            contentH = contentH,
            userCssHash = userCssHash,
        )
    }
}

private fun Float.bits(): Long = this.toBits().toLong()

private fun Int.bits(): Long = this.toLong() and 0xFFFFFFFFL