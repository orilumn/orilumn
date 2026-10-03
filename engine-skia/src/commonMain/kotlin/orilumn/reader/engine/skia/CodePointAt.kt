package orilumn.reader.engine.skia

/**
 * 一个 UTF-16 位置上的**码本**（code point）及其占用的 UTF-16 码元数（渲染层·基础）。
 *
 * ## 为什么必须抽成单源
 *
 * 代理对（ supplementary plane：emoji、生僻汉字、数学字母等）占**两个** UTF-16 码元，
 * 但在排版与落墨的眼里是**一个字位**。取法一旦有两份，就必然漂移，而漂移的方式很隐蔽：
 *
 * - 量宽侧 [SkiaRunMeasurer.cpAt] 合并了代理对 ⇒ 该字位拿到**正确**的宽度；
 * - 落墨侧 `LineWindowDrawer.drawGlyphPass` 若按 `text[i].code` 取 ⇒ 拿到**两个孤立代理**，
 *   没有任何字体有它们的字形 ⇒ **画出来是两个豆腐块**（真机报「代码块里特殊字符乱码」）。
 *
 * 更阴的是**量画位置一起错**：量宽侧把孤立低位代理也当成一个独立码本去查字体，
 * 查不到 ⇒ 记一个 **notdef 宽**（约 0.6em）⇒ 行凭空宽出一个字位，后续字位全体右移。
 * 书里 emoji 少 ⇒ 症状是「只有没几个字符乱码」，很容易误判成字体问题。
 *
 * 故：**取码本只允许走这一个函数**（[codePointAt]），且**两侧都必须用它**。
 */
internal data class CodePointAt(
    /** 码本值（代理对已合并成 >0xFFFF 的单个 Int）。 */
    val cp: Int,
    /** 这个码本占用的 UTF-16 码元数：普通字符 1，代理对 2。 */
    val units: Int,
    /** 是否是「跟在高位代理后面的低位代理」——**这种位置不是独立字位，不占宽、不落墨**。 */
    val isLowSurrogateTail: Boolean,
)

/** 高位代理区间。 */
internal const val HIGH_SURROGATE_MIN = 0xD800

/** 高位代理区间上界。 */
internal const val HIGH_SURROGATE_MAX = 0xDBFF

/** 低位代理区间。 */
internal const val LOW_SURROGATE_MIN = 0xDC00

/** 低位代理区间上界。 */
internal const val LOW_SURROGATE_MAX = 0xDFFF

/**
 * 取 `text[i]` 处的码本（渲染层·基础，**量宽与落墨的单源**）。
 *
 * 规则：
 * - `text[i]` 是高位代理且 `text[i+1]` 是低位代理 ⇒ 返回**合并码本**，`units=2`；
 * - `text[i]` 是**跟在高位代理后面的低位代理** ⇒ 返回该低位代理，`units=1`，
 *   但 `isLowSurrogateTail=true`：**它已经被前一个位置合并成一个字位量过了**，
 *   这里必须 0 宽、不查字体、不落墨；
 * - 其余 ⇒ 原样返回 `text[i].code`，`units=1`。
 *
 * @param i 必须是 `0 <= i < text.length`。
 */
internal fun codePointAt(text: CharSequence, i: Int): CodePointAt {
    val c = text[i].code
    val isHigh = c in HIGH_SURROGATE_MIN..HIGH_SURROGATE_MAX
    if (isHigh && i + 1 < text.length) {
        val low = text[i + 1].code
        if (low in LOW_SURROGATE_MIN..LOW_SURROGATE_MAX) {
            return CodePointAt(
                cp = 0x10000 + ((c - HIGH_SURROGATE_MIN) shl 10) + (low - LOW_SURROGATE_MIN),
                units = 2,
                isLowSurrogateTail = false,
            )
        }
    }
    val isTail = c in LOW_SURROGATE_MIN..LOW_SURROGATE_MAX &&
        i > 0 && text[i - 1].code in HIGH_SURROGATE_MIN..HIGH_SURROGATE_MAX
    return CodePointAt(cp = c, units = 1, isLowSurrogateTail = isTail)
}
