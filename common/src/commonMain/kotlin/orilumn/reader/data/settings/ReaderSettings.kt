package orilumn.reader.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Global reading settings (single-layer full object).
 *
 * Holds **global** user preferences, consistent across books (font size / line spacing / font / theme...);
 * per-book data lives in `BookReadingState` (reading progress), not in this table.
 *
 * Dual preset mechanism:
 *  - **Default set**: [Companion.DEFAULT] (built-in constants, never persisted);
 *  - **User set**: stored by [ReaderSettingsStore] at `filesDir/settings/reader.json`;
 *  - **One-tap reset**: delete the user file → fall back to [Companion.DEFAULT].
 *
 * Font replacement is gated by "element type" into three tiers: `fontBody` body / `fontTitle` headings /
 * `fontCode` code, replacing the old M2 five-way split (fontBody/fontTitle/fontCode/fontBold/fontItalic,
 * where bold/italic no longer have their own slots). A slot stores a font alias and is forced globally —
 * all text of the corresponding type in the book uses the selected local font; empty string = that type
 * is not replaced and keeps the original book. All layout themes apply uniformly.
 */
@Serializable
data class ReaderSettings(
    /** Reading background (built-in preset name: sepia / light / dark ...). */
    val theme: String = "sepia",
    /** Body font size (px). */
    val fontSize: Int = 18,
    /** Line spacing (number, 1.x times). */
    val lineSpacing: Double = 1.5,
    /** [Style system] Paragraph spacing (em): bottom spacing of body paragraphs p/div/li, scaled with font size (0..2, 0 = none, paragraphs distinguished by first-line indent). */
    val paragraphSpacing: Double = 1.0,
    /** [Style system] First-line indent (em, 0..10): `text-indent` on body paragraphs (p/li), applied by the UI layer so it overrides the book. Absolute value: 0 = no first-line indent. Switching to 原书设置 probes the book's own indent into this slot (see BookStyleProbe), so a 2em book shows 2. */
    val firstLineIndent: Double = 0.0,
    /** [Style system] Density (% scale 0..400, default 100): overall vertical margin scaling of structural blocks such as headings/quotes/code/lists, never zero. */
    val paragraphGap: Double = 100.0,
    /** [Style system] Character spacing (letter-spacing) slot -100..100, mapped to -0.2em..0.2em (each slot unit = 0.002em); 0 = no extra spacing. */
    val letterSpacing: Double = 0.0,

    /** Page margins: four directions independent (px). */
    val marginTop: Int = 100,
    val marginBottom: Int = 60,
    val marginLeft: Int = 50,
    val marginRight: Int = 50,
    /** Font replacement by element type, three tiers (alias, empty string = that type not replaced, keep the original book): body / heading / code. */
    val fontBody: String = "",
    val fontTitle: String = "",
    val fontCode: String = "",
    /** 原书设置 开关 (与 layoutTheme=="original" 同义): 关闭样式主题层, 只回落到书籍 CSS 层叠.
     *  它不改变优先级 — UI 设置层仍永远生效且最高, 用户随时可调滑块覆盖。 */
    val useOriginalStyle: Boolean = false,
    /** L2 user-imported stylesheet switch (M2). */
    val useUserScripts: Boolean = false,
    /** Page-turn animation (smooth swipe). */
    val pageAnim: Boolean = true,
    /** Page-turn animation mode: "slide" (smooth swipe) / "curl" (page curl) */
    val pageAnimationMode: String = "curl",
    /** Auto-continue reading on open (resume at last position). */
    val autoContinue: Boolean = true,
    /** Show page numbers. */
    val pageNum: Boolean = false,
    /** Body first-screen cover proportional scaling switch: on = keep aspect ratio (no distortion, reading bg shown around); off = stretch to fill the whole screen (may distort). */
    val coverProportional: Boolean = false,
    /** [Style system] Font size relative to default: 0..100 slider, 50 = default 1.0 (see COMPANION mapping). */
    val fontScale: Double = DEFAULT_FONT_SCALE_PX18_5,
    /** [Style system] Day/night full color scheme: day | night. */
    val scheme: String = "day",
    /** [Style system] Independent background override (hex like "#f4f2ec"); empty string = follow [scheme] default background. */
    val bgOverride: String = "",
    /** [Style system] Independent body text-color override (hex like "#2b2b2b"); empty string = follow [scheme] default text color. */
    val fgOverride: String = "#000000",
    /** [Style system] Layout theme: original (book settings) | modern | traditional. */
    val layoutTheme: String = "original",
    /** Reader brightness level -50..100: 100 = follow system; 0..100 writes the physical system backlight; -50..0 dims to system darkest then overlays a mask to darken further. */
    val brightness: Int = 100,
    /** Follow-system brightness switch; on → disables the "brightness" level slider, adjusting via [brightnessOffset] around the system brightness instead. */
    val brightnessFollowSystem: Boolean = true,
    /** Offset around the system brightness when following it: -20..20 (0 = fully follow system with no adjustment). */
    val brightnessOffset: Int = 0,
    /** Eye protection (blue-light filter) amount 0..100 (warm mask opacity ratio); 0 = filter off. */
    val eyeProtectionLevel: Int = 40,
    /** Brightness gestures (multi-selectable, all only effective when not following system):
     *  [brightnessGestureLeft] single-finger vertical swipe on left side of screen | [brightnessGestureRight] single-finger vertical swipe on right side |
     *  [brightnessGestureTwo] two-finger vertical swipe anywhere. */
    val brightnessGestureLeft: Boolean = false,
    val brightnessGestureRight: Boolean = false,
    val brightnessGestureTwo: Boolean = false,
) {

    /** Serialize to a JSON string; `indentFactor > 0` produces indented output. */
    @JvmOverloads
    fun toJson(indentFactor: Int = 0): String {
        val codec = if (indentFactor > 0) SettingsJson.pretty else SettingsJson.compact
        return codec.encodeToString(serializer(), this)
    }

    /** Serialize to a full [JsonObject] (all fields, including defaults). */
    fun toJsonObject(): JsonObject = SettingsJson.compact.encodeToJsonElement(serializer(), this).jsonObject

    /**
     * Overlay a book's private override layer → returns the effective value.
     *
     * Non-null fields in [overlay] override the global memory, null fields fall back to [this].
     * Never modifies [this].
     */
    fun applyOverlay(overlay: BookSettings): ReaderSettings = copy(
        layoutTheme = overlay.layoutTheme ?: layoutTheme,
        fontSize = overlay.fontSize ?: fontSize,
        fontScale = overlay.fontScale ?: fontScale,
        lineSpacing = overlay.lineSpacing ?: lineSpacing,
        paragraphSpacing = overlay.paragraphSpacing ?: paragraphSpacing,
        firstLineIndent = overlay.firstLineIndent ?: firstLineIndent,
        paragraphGap = overlay.paragraphGap ?: paragraphGap,
        letterSpacing = overlay.letterSpacing ?: letterSpacing,
        marginTop = overlay.marginTop ?: marginTop,
        marginBottom = overlay.marginBottom ?: marginBottom,
        marginLeft = overlay.marginLeft ?: marginLeft,
        marginRight = overlay.marginRight ?: marginRight,
        bgOverride = overlay.bgOverride ?: bgOverride,
        fgOverride = overlay.fgOverride ?: fgOverride,
        fontBody = overlay.fontBody ?: fontBody,
        fontTitle = overlay.fontTitle ?: fontTitle,
        fontCode = overlay.fontCode ?: fontCode,
        useOriginalStyle = overlay.useOriginalStyle ?: useOriginalStyle,
        pageAnim = overlay.pageAnim ?: pageAnim,
        pageAnimationMode = overlay.pageAnimationMode ?: pageAnimationMode,
        coverProportional = overlay.coverProportional ?: coverProportional,
        autoContinue = overlay.autoContinue ?: autoContinue,
        pageNum = overlay.pageNum ?: pageNum,
    )

    /**
     * Write a book's override layer **back** into the global memory.
     *
     * Non-null fields in [overlay] update [this]; null fields keep [this] unchanged.
     * Returns a new [ReaderSettings] instance.
     */
    fun mergeFrom(overlay: BookSettings): ReaderSettings = copy(
        layoutTheme = overlay.layoutTheme ?: layoutTheme,
        fontSize = overlay.fontSize ?: fontSize,
        fontScale = overlay.fontScale ?: fontScale,
        lineSpacing = overlay.lineSpacing ?: lineSpacing,
        paragraphSpacing = overlay.paragraphSpacing ?: paragraphSpacing,
        firstLineIndent = overlay.firstLineIndent ?: firstLineIndent,
        paragraphGap = overlay.paragraphGap ?: paragraphGap,
        letterSpacing = overlay.letterSpacing ?: letterSpacing,
        marginTop = overlay.marginTop ?: marginTop,
        marginBottom = overlay.marginBottom ?: marginBottom,
        marginLeft = overlay.marginLeft ?: marginLeft,
        marginRight = overlay.marginRight ?: marginRight,
        bgOverride = overlay.bgOverride ?: bgOverride,
        fgOverride = overlay.fgOverride ?: fgOverride,
        fontBody = overlay.fontBody ?: fontBody,
        fontTitle = overlay.fontTitle ?: fontTitle,
        fontCode = overlay.fontCode ?: fontCode,
        useOriginalStyle = overlay.useOriginalStyle ?: useOriginalStyle,
        pageAnim = overlay.pageAnim ?: pageAnim,
        pageAnimationMode = overlay.pageAnimationMode ?: pageAnimationMode,
        coverProportional = overlay.coverProportional ?: coverProportional,
        autoContinue = overlay.autoContinue ?: autoContinue,
        pageNum = overlay.pageNum ?: pageNum,
    )

    companion object {
        /** Default set: factory values when no customization has been made. */
        val DEFAULT = ReaderSettings()

        /** Default body font size (px), the anchor body for calibrating [fontScale] to 1.0 (=50). */
        const val BASE_BODY_PX = 18

        /** Factory font-size slot (default body font 18.5px): ratioToFontScale(18.5 / BASE_BODY_PX) ≈ 51.39. */
        const val DEFAULT_FONT_SCALE_PX18_5 = 51.39

        /**
         * Piecewise mapping relative to default: maps the 0..100 slot to a scale factor.
         *  - 0..50: 0.5 → 1.0 (`0.5 + v*0.01`)
         *  - 50..100: 1.0 → 2.0 (`v*0.02`)
         * Slot 50 = the element's own default (body font = [BASE_BODY_PX], headings etc. scale proportionally off its em base).
         */
        fun fontScaleToRatio(v: Double): Double {
            val x = v.coerceIn(0.0, 100.0)
            return if (x <= 50.0) 0.5 + x * 0.01 else x * 0.02
        }

        /** Inverse of [fontScaleToRatio] (takes the 0..50 half when <1.0; 50..100 when ≥1.0). Only used for old absolute-px migration. */
        fun ratioToFontScale(ratio: Double): Double {
            val r = ratio.coerceIn(0.5, 2.0)
            val v = if (r <= 1.0) (r - 0.5) / 0.01 else r / 0.02
            return v.coerceIn(0.0, 100.0)
        }

        /** Old absolute body font size (px) → relative-to-default slot. */
        fun fontSizePxToScale(px: Int): Double = ratioToFontScale(px.toDouble() / BASE_BODY_PX)

        /**
         * Parse from JSON; missing or invalid fields fall back to the corresponding default
         * (tolerates partial omissions / new-version compatibility). Old sets (only absolute `fontSize`
         * px, no `fontScale`) are auto-migrated to relative slots.
         */
        @JvmStatic
        fun fromJson(json: String?): ReaderSettings {
            if (json.isNullOrBlank()) return DEFAULT
            return try {
                val o = SettingsJson.compact.parseToJsonElement(json).jsonObject
                val d = DEFAULT
                // Old-set migration: no fontScale but with old absolute fontSize(px) → invert to a slot; otherwise use the new relative slot
                val fontScale = if (o.containsKey("fontScale")) {
                    SettingsJson.optDouble(o, "fontScale", d.fontScale)
                } else if (o.containsKey("fontSize")) {
                    fontSizePxToScale(SettingsJson.optInt(o, "fontSize", d.fontSize))
                } else {
                    d.fontScale
                }
                // Old-set migration: old theme(sepia/white/dark) has no scheme/bgOverride → map into the new color scheme
                //  dark(dark night)→night full set; white(light)/sepia(parchment)→day scheme + background override
                val hasScheme = o.containsKey("scheme")
                val hasBg = o.containsKey("bgOverride")
                val legacyTheme = SettingsJson.optString(o, "theme", "sepia")
                val scheme = if (hasScheme) SettingsJson.optString(o, "scheme", d.scheme)
                else if (legacyTheme == "dark") "night" else d.scheme
                val bgOverride = when {
                    hasBg -> SettingsJson.optString(o, "bgOverride", d.bgOverride)
                    legacyTheme == "white" -> "#ffffff"
                    legacyTheme == "sepia" -> "#f4f2ec"
                    else -> d.bgOverride
                }
                // Old-set migration: old margin(compact/normal/wide) line-width presets → new per-direction independent px (compact = large margin, wide = small margin)
                val hasMargin = o.containsKey("marginTop") || o.containsKey("marginBottom") || o.containsKey("marginLeft") || o.containsKey("marginRight")
                val legacyMargin = SettingsJson.optString(o, "margin", "")
                val marginTop = if (hasMargin) SettingsJson.optInt(o, "marginTop", d.marginTop)
                else when (legacyMargin) { "compact" -> 40; "wide" -> 16; else -> d.marginTop }
                val marginBottom = if (hasMargin) SettingsJson.optInt(o, "marginBottom", d.marginBottom)
                else when (legacyMargin) { "compact" -> 40; "wide" -> 16; else -> d.marginBottom }
                val marginLeft = if (hasMargin) SettingsJson.optInt(o, "marginLeft", d.marginLeft)
                else when (legacyMargin) { "compact" -> 48; "wide" -> 16; else -> d.marginLeft }
                val marginRight = if (hasMargin) SettingsJson.optInt(o, "marginRight", d.marginRight)
                else when (legacyMargin) { "compact" -> 48; "wide" -> 16; else -> d.marginRight }
                ReaderSettings(
                    theme = SettingsJson.optString(o, "theme", d.theme),
                    fontSize = SettingsJson.optInt(o, "fontSize", d.fontSize),
                    lineSpacing = SettingsJson.optDouble(o, "lineSpacing", d.lineSpacing),
                    paragraphSpacing = SettingsJson.optDouble(o, "paragraphSpacing", d.paragraphSpacing),
                    firstLineIndent = SettingsJson.optDouble(o, "firstLineIndent", d.firstLineIndent),
                    paragraphGap = SettingsJson.optDouble(o, "paragraphGap", d.paragraphGap),
                    letterSpacing = SettingsJson.optDouble(o, "letterSpacing", d.letterSpacing),
                    marginTop = marginTop,
                    marginBottom = marginBottom,
                    marginLeft = marginLeft,
                    marginRight = marginRight,
                    fontBody = SettingsJson.optString(o, "fontBody", d.fontBody),
                    fontTitle = SettingsJson.optString(o, "fontTitle", d.fontTitle),
                    fontCode = SettingsJson.optString(o, "fontCode", d.fontCode),
                    useOriginalStyle = SettingsJson.optBoolean(o, "useOriginalStyle", d.useOriginalStyle),
                    useUserScripts = SettingsJson.optBoolean(o, "useUserScripts", d.useUserScripts),
                    pageAnim = SettingsJson.optBoolean(o, "pageAnim", d.pageAnim),
                    pageAnimationMode = SettingsJson.optString(o, "pageAnimationMode", d.pageAnimationMode),
                    autoContinue = SettingsJson.optBoolean(o, "autoContinue", d.autoContinue),
                    pageNum = SettingsJson.optBoolean(o, "pageNum", d.pageNum),
                    coverProportional = SettingsJson.optBoolean(o, "coverProportional", d.coverProportional),
                    fontScale = fontScale,
                    scheme = scheme,
                    bgOverride = bgOverride,
                    fgOverride = SettingsJson.optString(o, "fgOverride", d.fgOverride),
                    layoutTheme = SettingsJson.optString(o, "layoutTheme", d.layoutTheme),
                    brightness = SettingsJson.optInt(o, "brightness", d.brightness),
                    brightnessFollowSystem = SettingsJson.optBoolean(o, "brightnessFollowSystem", d.brightnessFollowSystem),
                    brightnessOffset = SettingsJson.optInt(o, "brightnessOffset", d.brightnessOffset),
                    eyeProtectionLevel = SettingsJson.optInt(o, "eyeProtectionLevel", d.eyeProtectionLevel),
                    brightnessGestureLeft = SettingsJson.optBoolean(o, "brightnessGestureLeft", d.brightnessGestureLeft),
                    brightnessGestureRight = SettingsJson.optBoolean(o, "brightnessGestureRight", d.brightnessGestureRight),
                    brightnessGestureTwo = SettingsJson.optBoolean(o, "brightnessGestureTwo", d.brightnessGestureTwo),
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}