package orilumn.reader.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A book's **private settings override layer** (overlay).
 *
 * All fields nullable = `null` means "not set, use global memory".
 *
 * On read, [ReaderSettings.applyOverlay] merges global memory with the overlay into the effective value;
 * on write, [ReaderSettings.mergeFrom] writes the overlay back to global memory (item-wise carry-over).
 *
 * @see ReaderSettings global memory (cross-book shared defaults)
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
    /** Line spacing (number, 1.x times). */
    val lineSpacing: Double? = null,
    /** Paragraph spacing (em, 0..2). */
    val paragraphSpacing: Double? = null,
    /** First-line indent (em, 0..10; 0 = no indent). */
    val firstLineIndent: Double? = null,
    /** Density scaling (% 0..400, 100 = default). */
    val paragraphGap: Double? = null,
    /** Character spacing slot -100..100 (each unit = 0.01em). */
    val letterSpacing: Double? = null,
    /** Page margins four directions (px). */
    val marginTop: Int? = null,
    val marginBottom: Int? = null,
    val marginLeft: Int? = null,
    val marginRight: Int? = null,
    /** Independent background override (hex); empty string = follow scheme default. */
    val bgOverride: String? = null,
    /** Independent body text-color override (hex); empty string = follow scheme default. */
    val fgOverride: String? = null,
    /** Font replacement by element type, three tiers (alias, empty string = that type not replaced, keep the original book): body / heading / code. */
    val fontBody: String? = null,
    val fontTitle: String? = null,
    val fontCode: String? = null,
    /** Follow-original-book-style master switch. */
    val useOriginalStyle: Boolean? = null,
    /** Page-turn animation (smooth swipe). */
    val pageAnim: Boolean? = null,
    /** Page-turn animation mode: "slide" | "curl" */
    val pageAnimationMode: String? = null,
    /** Body first-screen cover proportional scaling: true=keep aspect ratio with margins, no stretch; false=stretch to fill the whole screen. */
    val coverProportional: Boolean? = null,
    /** Auto-continue reading on open (resume at last position). */
    val autoContinue: Boolean? = null,
    /** Show page numbers. */
    val pageNum: Boolean? = null,
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
        /** Empty overlay (all fields = null, fully uses global memory). */
        val EMPTY = BookSettings()

        /**
         * Parse from JSON; missing or invalid fields fall back to null (treated as not overridden).
         */
        @JvmStatic
        fun fromJson(json: String?): BookSettings {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                val o = SettingsJson.compact.parseToJsonElement(json).jsonObject
                BookSettings(
                    layoutTheme = if (o.containsKey("layoutTheme")) SettingsJson.optString(o, "layoutTheme", "") else null,
                    fontSize = if (o.containsKey("fontSize")) SettingsJson.optInt(o, "fontSize", 0) else null,
                    fontScale = if (o.containsKey("fontScale")) SettingsJson.optDouble(o, "fontScale", 0.0) else null,
                    lineSpacing = if (o.containsKey("lineSpacing")) SettingsJson.optDouble(o, "lineSpacing", 0.0) else null,
                    paragraphSpacing = if (o.containsKey("paragraphSpacing")) SettingsJson.optDouble(o, "paragraphSpacing", 0.0) else null,
                    firstLineIndent = if (o.containsKey("firstLineIndent")) SettingsJson.optDouble(o, "firstLineIndent", 0.0) else null,
                    paragraphGap = if (o.containsKey("paragraphGap")) SettingsJson.optDouble(o, "paragraphGap", 0.0) else null,
                    letterSpacing = if (o.containsKey("letterSpacing")) SettingsJson.optDouble(o, "letterSpacing", 0.0) else null,
                    marginTop = if (o.containsKey("marginTop")) SettingsJson.optInt(o, "marginTop", 0) else null,
                    marginBottom = if (o.containsKey("marginBottom")) SettingsJson.optInt(o, "marginBottom", 0) else null,
                    marginLeft = if (o.containsKey("marginLeft")) SettingsJson.optInt(o, "marginLeft", 0) else null,
                    marginRight = if (o.containsKey("marginRight")) SettingsJson.optInt(o, "marginRight", 0) else null,
                    bgOverride = if (o.containsKey("bgOverride")) SettingsJson.optString(o, "bgOverride", "") else null,
                    fgOverride = if (o.containsKey("fgOverride")) SettingsJson.optString(o, "fgOverride", "") else null,
                    fontBody = if (o.containsKey("fontBody")) SettingsJson.optString(o, "fontBody", "") else null,
                    fontTitle = if (o.containsKey("fontTitle")) SettingsJson.optString(o, "fontTitle", "") else null,
                    fontCode = if (o.containsKey("fontCode")) SettingsJson.optString(o, "fontCode", "") else null,
                    useOriginalStyle = if (o.containsKey("useOriginalStyle")) SettingsJson.optBoolean(o, "useOriginalStyle", false) else null,
                    pageAnim = if (o.containsKey("pageAnim")) SettingsJson.optBoolean(o, "pageAnim", false) else null,
                    pageAnimationMode = if (o.containsKey("pageAnimationMode")) SettingsJson.optString(o, "pageAnimationMode", "") else null,
                    coverProportional = if (o.containsKey("coverProportional")) SettingsJson.optBoolean(o, "coverProportional", false) else null,
                    autoContinue = if (o.containsKey("autoContinue")) SettingsJson.optBoolean(o, "autoContinue", false) else null,
                    pageNum = if (o.containsKey("pageNum")) SettingsJson.optBoolean(o, "pageNum", false) else null,
                )
            } catch (_: Exception) {
                EMPTY
            }
        }

        /**
         * **Extract** only the overlay fields from a full [ReaderSettings] JSON.
         * Used for JS write-back: JS returns the whole JSON, and we only take the overridable fields.
         */
        @JvmStatic
        fun fromReaderSettingsJson(json: String?): BookSettings {
            if (json.isNullOrBlank()) return EMPTY
            return try {
                // First parse into ReaderSettings, then extract the overlay fields
                val rs = ReaderSettings.fromJson(json)
                fromReaderSettings(rs)
            } catch (_: Exception) {
                EMPTY
            }
        }

        /**
         * Extract the overlay from a [ReaderSettings] object.
         * Used for JS write-back: take the overridable fields, writing those differing from global into the overlay.
         */
        @JvmStatic
        fun fromReaderSettings(rs: ReaderSettings): BookSettings = BookSettings(
            layoutTheme = rs.layoutTheme,
            fontSize = rs.fontSize,
            fontScale = rs.fontScale,
            lineSpacing = rs.lineSpacing,
            paragraphSpacing = rs.paragraphSpacing,
            firstLineIndent = rs.firstLineIndent,
            paragraphGap = rs.paragraphGap,
            letterSpacing = rs.letterSpacing,
            marginTop = rs.marginTop,
            marginBottom = rs.marginBottom,
            marginLeft = rs.marginLeft,
            marginRight = rs.marginRight,
            bgOverride = rs.bgOverride,
            fgOverride = rs.fgOverride,
            fontBody = rs.fontBody,
            fontTitle = rs.fontTitle,
            fontCode = rs.fontCode,
            useOriginalStyle = rs.useOriginalStyle,
            pageAnim = rs.pageAnim,
            pageAnimationMode = rs.pageAnimationMode,
            coverProportional = rs.coverProportional,
            autoContinue = rs.autoContinue,
            pageNum = rs.pageNum,
        )

        /**
         * [Changed-field projection] Return a new overlay holding **only** the fields whose value in [next]
         * differs from [baseline]. Used by persistence so that global memory receives just the setting a user
         * actually touched this commit, never the whole per-book snapshot (which would pollute other books).
         */
        @JvmStatic
        fun changedFrom(next: ReaderSettings, baseline: ReaderSettings): BookSettings {
            val o = BookSettings(
                layoutTheme = next.layoutTheme, fontSize = next.fontSize, fontScale = next.fontScale,
                lineSpacing = next.lineSpacing, paragraphSpacing = next.paragraphSpacing, firstLineIndent = next.firstLineIndent, paragraphGap = next.paragraphGap,
                letterSpacing = next.letterSpacing,
                marginTop = next.marginTop, marginBottom = next.marginBottom, marginLeft = next.marginLeft, marginRight = next.marginRight,
                bgOverride = next.bgOverride, fgOverride = next.fgOverride,
                fontBody = next.fontBody, fontTitle = next.fontTitle, fontCode = next.fontCode,
                useOriginalStyle = next.useOriginalStyle, pageAnim = next.pageAnim,
                pageAnimationMode = next.pageAnimationMode, coverProportional = next.coverProportional,
                autoContinue = next.autoContinue, pageNum = next.pageNum,
            )
            return o.copy(
                layoutTheme = o.layoutTheme?.takeUnless { it == baseline.layoutTheme },
                fontSize = o.fontSize?.takeUnless { it == baseline.fontSize },
                fontScale = o.fontScale?.takeUnless { it == baseline.fontScale },
                lineSpacing = o.lineSpacing?.takeUnless { it == baseline.lineSpacing },
                paragraphSpacing = o.paragraphSpacing?.takeUnless { it == baseline.paragraphSpacing },
                firstLineIndent = o.firstLineIndent?.takeUnless { it == baseline.firstLineIndent },
                paragraphGap = o.paragraphGap?.takeUnless { it == baseline.paragraphGap },
                letterSpacing = o.letterSpacing?.takeUnless { it == baseline.letterSpacing },
                marginTop = o.marginTop?.takeUnless { it == baseline.marginTop },
                marginBottom = o.marginBottom?.takeUnless { it == baseline.marginBottom },
                marginLeft = o.marginLeft?.takeUnless { it == baseline.marginLeft },
                marginRight = o.marginRight?.takeUnless { it == baseline.marginRight },
                bgOverride = o.bgOverride?.takeUnless { it == baseline.bgOverride },
                fgOverride = o.fgOverride?.takeUnless { it == baseline.fgOverride },
                fontBody = o.fontBody?.takeUnless { it == baseline.fontBody },
                fontTitle = o.fontTitle?.takeUnless { it == baseline.fontTitle },
                fontCode = o.fontCode?.takeUnless { it == baseline.fontCode },
                useOriginalStyle = o.useOriginalStyle?.takeUnless { it == baseline.useOriginalStyle },
                pageAnim = o.pageAnim?.takeUnless { it == baseline.pageAnim },
                pageAnimationMode = o.pageAnimationMode?.takeUnless { it == baseline.pageAnimationMode },
                coverProportional = o.coverProportional?.takeUnless { it == baseline.coverProportional },
                autoContinue = o.autoContinue?.takeUnless { it == baseline.autoContinue },
                pageNum = o.pageNum?.takeUnless { it == baseline.pageNum },
            )
        }
    }
}