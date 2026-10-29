package orilumn.reader.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A book's **private settings layer**（稀疏覆盖层）：只记「和本项目预设不同」的私有项。
 *
 * 两条定口（2026-10-29，见 `docs/设置模型整改计划.md`）：
 * 1. **稀疏**：与预设相同 ⇒ 不落键（`null`）⇒ 以后改预设能到达这本书；文件因此只剩读者
 *    真动过的项，空文件即删除。全量快照会把预设值焊死进书里，本类**不再**这么做。
 * 2. **私有项永不写全局**：本书改字号/行距/字体不会影响别的书，也不会被别的书改掉。
 *    全局项（亮度、阅读主题、笔记族、开关…）根本没有字段，旧档里的残留键读时丢弃。
 *
 * `null` 的语义分两种：
 * - 可空语义字段（[lineSpacing]、[firstLineIndent]、[paragraphSpacing]）的 `null` 本身就是「没配」；
 * - 其余字段的 `null` = 「与预设相同」（[normalized] 已归一），读时用预设。
 *
 * On read, [ReaderSettings.applyOverlay] 把非 null 私有项盖到全局记忆上得到生效值。
 * On write, [PerBookSettings.persist] 用 [fromReaderSettings]（= 预设差异 + [normalized]）落盘。
 *
 * @see SettingsScope 归属唯一来源（全局 / 私有 / 会话只读）
 * @see BookSettingsStore overlay persistence
 */
@Serializable
data class BookSettings(
    /** Layout theme: original (book settings) | modern | traditional. */
    val layoutTheme: String? = null,
    /** Body font size (px). */
    val fontSize: Int? = null,
    /** Font-size scaling slot 0..100 (50 = 1.0). */
    val fontScale: Double? = null,
    /** Line spacing (number, 1.x times); `null` = 没配 ⇒ 不声明，书自己的行高生效。 */
    val lineSpacing: Double? = null,
    /** 混排字距（0..100 → 0..1.0em）：影响断行与版面，属于本书私有的排版设置。 */
    val cjkLatinSpacing: Double? = null,
    /** 首行缩进（em，0..10）：`null` = 没配（不声明，书的缩进原样）；`0` = 明确不缩进；N = N em。 */
    val firstLineIndent: Double? = null,
    /** 段间距（**绝对值 em，0..2**）：`null` = 没配（引擎不覆盖，书自己的段距生效）；
     *  N = p/li 上下外边距固定 N em。旧档存百分比（0..1000，100 = 原书节奏），迁移见 [fromJson]。 */
    val paragraphSpacing: Double? = null,
    /** 疏密 (% 0..1000, 100 = 原书节奏): p/li 之外一切块级纵边距乘算（存百分比，滑块 0..100 ÷10）. */
    val paragraphGap: Double? = null,
    /** 字间距槽 -100..100（每档 = 0.002em）：`null` = 没配（引擎按书的正文实测字距）。
     *  旧档默认 0 = 「不加字距」⇒ [legacy] 读法把 0 当没配。 */
    val letterSpacing: Double? = null,
    /** Page margins four directions (px). */
    val marginTop: Int? = null,
    val marginBottom: Int? = null,
    val marginLeft: Int? = null,
    val marginRight: Int? = null,
    /** Font replacement by element type, three tiers (alias, empty string = that type not replaced, keep the original book): body / heading / code. */
    val fontBody: String? = null,
    val fontTitle: String? = null,
    val fontCode: String? = null,
    /** L2 用户自定义样式表开关（本书私有，影响本书的样式层叠；当前无消费者）。 */
    val useUserScripts: Boolean? = null,
    /** Page-turn animation (smooth swipe). */
    val pageAnim: Boolean? = null,
    /** Page-turn animation mode: "slide" | "curl" */
    val pageAnimationMode: String? = null,
    /** Body first-screen cover stretch: true=stretch to fill the whole screen; false=keep aspect ratio with margins, no stretch. */
    val coverStretch: Boolean? = null,
    /**
     * 用户在该族选的字重（族名 → CSS 字重 100..900）。**按书私有**（2026-10-29 定口）：
     * 同一本书挑宋体 + 粗一档不传染到别的书；空 = 未选 ⇒ 不发声明 ⇒ 书自己的字重生效。
     */
    val fontWeightAnchors: Map<String, Int>? = null,
    /** 同上但按槽位隔离（key = "slot|family"）。 */
    val fontWeightAnchorsBySlot: Map<String, Int>? = null,
) {
    /** Serialize to JSON (only non-null fields output); `indentFactor > 0` produces indented output. */
    @JvmOverloads
    fun toJson(indentFactor: Int = 0): String {
        val codec = if (indentFactor > 0) SettingsJson.bookPretty else SettingsJson.bookCompact
        return codec.encodeToString(serializer(), this)
    }

    /** Output a [JsonObject], skipping null fields. */
    fun toJsonObject(): JsonObject = SettingsJson.bookCompact.encodeToJsonElement(serializer(), this).jsonObject

    /** Whether it is empty (no override layer at all). */
    val isEmpty: Boolean
        get() = toJsonObject().isEmpty()

    companion object {
        /** Empty overlay (all fields = null, fully follows the project preset / the book's own CSS). */
        val EMPTY = BookSettings()

        /**
         * Parse from JSON; missing or invalid fields fall back to null (treated as not overridden).
         * 旧档里的全局键（亮度、主题色、页码、笔记族…）本类没有对应字段，读时自然丢弃。
         *
         * [legacy] = 这份文件是**旧模型（整份快照）**写的：那时行距必有 1.5、缩进必有 0，
         * 都是旧默认值，要按「没配」（`null`）读一次。迁移由 [BookSettingsStore] 做且只做一次
         * （迁移后写版本标记），**不是**常规读档规则——否则读者真的把行距设成 1.5、缩进设成 0，
         * 每次重开书都会被改回原书的值。值判据挡不住「读者就要这个值」，所以只给迁移用。
         */
        @JvmStatic
        fun fromJson(json: String?, legacy: Boolean = false): BookSettings {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                val o = SettingsJson.compact.parseToJsonElement(json).jsonObject
                BookSettings(
                    // `useOriginalStyle` 已退役：旧档 true 等价于「原书设置」。
                    layoutTheme = when {
                        SettingsJson.optBoolean(o, "useOriginalStyle", false) -> "original"
                        o.containsKey("layoutTheme") -> SettingsJson.optString(o, "layoutTheme", "")
                        else -> null
                    },
                    fontSize = if (o.containsKey("fontSize")) SettingsJson.optInt(o, "fontSize", 0) else null,
                    fontScale = if (o.containsKey("fontScale")) SettingsJson.optDouble(o, "fontScale", 0.0) else null,
                    // 行距：`null` = 没配（不声明）⇒ 书自己的行高生效。旧档（[legacy]）必有值且默认 1.5，
                    // 迁移那一次按「没配」读；之后 1.5 就是读者真设的值，不再改写。
                    lineSpacing = if (o.containsKey("lineSpacing")) {
                        SettingsJson.optDoubleOrNull(o, "lineSpacing")
                            ?.takeIf { !legacy || it != 1.5 }
                    } else null,
                    cjkLatinSpacing = if (o.containsKey("cjkLatinSpacing")) SettingsJson.optDouble(o, "cjkLatinSpacing", 0.0) else null,
                    firstLineIndent = if (o.containsKey("firstLineIndent")) {
                        // 同上：旧档（[legacy]）默认 0 → 没配；迁移之后 0 是「明确不缩进」的真值。
                        SettingsJson.optDoubleOrNull(o, "firstLineIndent")
                            ?.takeIf { !legacy || it != 0.0 }
                    } else null,
                    // 段间距：旧档（[legacy]）存百分比 0..1000（100 = 原书节奏 → null，其余 ÷100 夹 0..2）；
                    // 迁移之后存的就是绝对值 em（0..2）。`> 2` 的值一定是旧百分比，就地按旧口径读。
                    paragraphSpacing = if (o.containsKey("paragraphSpacing")) {
                        val raw = SettingsJson.optDouble(o, "paragraphSpacing", 100.0)
                        if (legacy || raw > 2.0) legacyParagraphSpacingPercentToEm(raw)
                        else raw.coerceIn(0.0, 2.0)
                    } else null,
                    paragraphGap = if (o.containsKey("paragraphGap")) SettingsJson.optDouble(o, "paragraphGap", 0.0) else null,
                    letterSpacing = if (o.containsKey("letterSpacing")) {
                        SettingsJson.optDoubleOrNull(o, "letterSpacing")?.takeIf { !legacy || it != 0.0 }
                    } else null,
                    marginTop = if (o.containsKey("marginTop")) SettingsJson.optInt(o, "marginTop", 0) else null,
                    marginBottom = if (o.containsKey("marginBottom")) SettingsJson.optInt(o, "marginBottom", 0) else null,
                    marginLeft = if (o.containsKey("marginLeft")) SettingsJson.optInt(o, "marginLeft", 0) else null,
                    marginRight = if (o.containsKey("marginRight")) SettingsJson.optInt(o, "marginRight", 0) else null,
                    fontBody = if (o.containsKey("fontBody")) SettingsJson.optString(o, "fontBody", "") else null,
                    fontTitle = if (o.containsKey("fontTitle")) SettingsJson.optString(o, "fontTitle", "") else null,
                    fontCode = if (o.containsKey("fontCode")) SettingsJson.optString(o, "fontCode", "") else null,
                    useUserScripts = if (o.containsKey("useUserScripts")) SettingsJson.optBoolean(o, "useUserScripts", false) else null,
                    pageAnim = if (o.containsKey("pageAnim")) SettingsJson.optBoolean(o, "pageAnim", false) else null,
                    pageAnimationMode = if (o.containsKey("pageAnimationMode")) SettingsJson.optString(o, "pageAnimationMode", "") else null,
                    coverStretch = if (o.containsKey("coverStretch")) SettingsJson.optBoolean(o, "coverStretch", false)
                    else if (o.containsKey("coverProportional")) !SettingsJson.optBoolean(o, "coverProportional", false) else null,
                    fontWeightAnchors = if (o.containsKey("fontWeightAnchors")) SettingsJson.optWeightAnchors(o, "fontWeightAnchors") else null,
                    fontWeightAnchorsBySlot = if (o.containsKey("fontWeightAnchorsBySlot")) {
                        SettingsJson.optWeightAnchorsMap(o, "fontWeightAnchorsBySlot", emptyMap())
                    } else null,
                )
            } catch (_: Exception) {
                EMPTY
            }
        }

        /**
         * 从完整 [ReaderSettings] 抽出**本书私有层**（预设差异，稀疏）。
         *
         * 只取 [SettingsScope.PRIVATE] 里的字段，再过 [normalized]：与预设相同即不落键。
         * 全局项（亮度、阅读主题、笔记族、开关…）一个都不取 —— 私有项永不写全局，反之亦然。
         */
        @JvmStatic
        fun fromReaderSettings(rs: ReaderSettings): BookSettings = BookSettings(
            layoutTheme = rs.layoutTheme,
            fontSize = rs.fontSize,
            fontScale = rs.fontScale,
            lineSpacing = rs.lineSpacing,
            cjkLatinSpacing = rs.cjkLatinSpacing,
            firstLineIndent = rs.firstLineIndent,
            paragraphSpacing = rs.paragraphSpacing,
            paragraphGap = rs.paragraphGap,
            letterSpacing = rs.letterSpacing,
            marginTop = rs.marginTop,
            marginBottom = rs.marginBottom,
            marginLeft = rs.marginLeft,
            marginRight = rs.marginRight,
            fontBody = rs.fontBody,
            fontTitle = rs.fontTitle,
            fontCode = rs.fontCode,
            useUserScripts = rs.useUserScripts,
            pageAnim = rs.pageAnim,
            pageAnimationMode = rs.pageAnimationMode,
            coverStretch = rs.coverStretch,
            fontWeightAnchors = rs.fontWeightAnchors,
            fontWeightAnchorsBySlot = rs.fontWeightAnchorsBySlot,
        ).normalized()
    }
}
