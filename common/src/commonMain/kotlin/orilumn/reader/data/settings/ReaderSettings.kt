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
    /** [Style system] First-line indent (em, 0..10): `text-indent` on body paragraphs (p/li), applied by the UI layer so it overrides the book. Absolute value: 0 = no first-line indent. Switching to 原书设置 probes the book's own indent into this slot (see BookStyleProbe), so a 2em book shows 2. */
    val firstLineIndent: Double = 0.0,
    /** [Style system] 段间距 (% scale 0..200, default 100): p/li 纵边距乘算
     *  (100 = 书/主题节奏，0 = p/li 边距清零；标题等结构块不受此值影响，看疏密)。 */
    val paragraphSpacing: Double = 100.0,
    /** [Style system] 疏密 (% scale 0..200, default 100): p/li 之外一切块级纵边距乘算
     *  (100 = 原书节奏，0 = 结构块边距清零；p/li 看段间距)。 */
    val paragraphGap: Double = 100.0,
    /** [Style system] Character spacing (letter-spacing) slot -100..100, mapped to -0.2em..0.2em (each slot unit = 0.002em); 0 = no extra spacing. */
    val letterSpacing: Double = 0.0,
    /**
     * [Style system] **混排字距** 0..100, mapped to 0..1.0em; 25 = 0.25em (CLREQ default).
     *
     * 用户可见名是「混排字距」（2026-10-03 由「中西字距」改名），但**内部标识符与 JSON 键
     * 仍然是 `cjkLatinSpacing`**，两者故意不同：改名会改掉持久化键、把刚写好的
     * [schemaVersion] v1 迁移作废（老存档里根本没有 `混排字距` 这个键）。
     *
     * 0 是**合法档位**，且**不是特例档**（产品口径 2026-10-03 改）：0 = 注入的间隙宽正好 0，
     * 中西之间**只**由 [letterSpacing]（字间距）分隔 —— 字面意义上的「距离 0」。
     * 边界照检、作者为了让中英分开而手打的那串半角空格**照吃**（画成零宽，
     * CLREQ 4.1「删除多余半角空格，注入固定间隙」的删除动作与间隙宽无关）。
     * 于是 `Rust 的所有权` 在 0 档排成 `Rust的所有权`；档位之间只有间隙宽这一个数在变。
     * 详细口径见 [orilumn.reader.engine.text.preprocess.CjkLatinSpacing] 类 KDoc。
     */
    val cjkLatinSpacing: Double = 25.0,

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
    /** Body first-screen cover stretch switch: on = stretch to fill the whole screen (may distort); off = keep aspect ratio (no distortion, reading bg shown around). */
    val coverStretch: Boolean = true,
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
    /** 字体管理隐藏字体列出开关（纯全局，默认隐藏；永不进按书 overlay，见 [PerBookSettings]）。 */
    val showHiddenFonts: Boolean = false,
    /**
     * 用户在该族选的字重（族名 → CSS 字重 100..900）。**唯一消费者是 UI 层声明**：
     * `ReaderUiSheet.fontRules` 把它写成该槽规则的 `font-weight`（tier 44），在级联里赢过 UA/作者；
     * `FontPoolSync` 另用它决定池里要装哪几档面。空 = 未选 ⇒ 不发声明 ⇒ 原书 `font-weight` 原样生效。
     * 纯全局（同 showHiddenFonts 待遇）；[fontWeightAnchorsBySlot] 优先，本表仅作旧档兜底。
     */
    val fontWeightAnchors: Map<String, Int> = emptyMap(),
    /**
     * 按 (槽位,族) 的用户字重：key = `"fontTitle|族名"`。**槽位隔离**——
     * 同名字族在标题/正文/代码三个槽各选各的字重，互不串（`FontSlots.slotNameOf` 出这个前缀）。
     */
    val fontWeightAnchorsBySlot: Map<String, Int> = emptyMap(),

    /**
     * 持久化结构版本（**只增不改**）：`[fromJson]` 用它识别「这份存档是哪个版本写的」，
     * 从而对**只影响本版本**的字段做一次性迁移。纯全局（[BookSettings] 无此字段，
     * 故不参与 [applyOverlay]/[mergeFrom] 的分层逻辑）。
     *
     * ## 为什么不按「值」判断而要一个版本号
     *
     * 值判据（典型如「`cjkLatinSpacing == 0` 就当成旧档重置成 25」）**挡不住用户真的想设 0**：
     * 混排字距的 0 是合法档位（= 关掉间隙），用户拖到 0 之后下一次启动会被强行改回 25，
     * 且**改多少遍都会被改回去**。版本号则只对「写档那一刻还没有的语义」动手一次。
     *
     * ## 版本号怎么加
     *
     * **只加在数据类的最后一个字段**：中间插字段会改变 `toJson` 的字段顺序，
     * 而老存档按**键名**解析（[fromJson] 走 `SettingsJson` 的 jsonObject），
     * 顺序变不影响读；写出去的顺序变了也没有读方依赖（唯一读方就是 [fromJson]）。
     * 真正的约束是**默认值必须等于当前版本**：缺该键 = 读到默认值 = 旧档。
     */
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
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
        firstLineIndent = overlay.firstLineIndent ?: firstLineIndent,
        paragraphSpacing = overlay.paragraphSpacing ?: paragraphSpacing,
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
        coverStretch = overlay.coverStretch ?: coverStretch,
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
        firstLineIndent = overlay.firstLineIndent ?: firstLineIndent,
        paragraphSpacing = overlay.paragraphSpacing ?: paragraphSpacing,
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
        coverStretch = overlay.coverStretch ?: coverStretch,
        pageNum = overlay.pageNum ?: pageNum,
    )

    /**
     * 亮度族剥离（双端同规则）：纯亮度变化不进版式管线——调用方比对
     * `withoutLight()` 是否变化，决定是否向排版/宿主传播。
     * 亮度族 = brightness/brightnessFollowSystem/brightnessOffset/eyeProtectionLevel/
     * brightnessGestureLeft/Right/Two（纯全局，不出 overlay）。
     *
     * ⚠ 只含亮度族。**判断「这次设置变更要不要重排」一律用 [withoutNonLayout]**（整族），
     * 本函数保留给「只关心亮度族」的窄口径调用点。
     */
    fun withoutLight(): ReaderSettings = copy(
        brightness = 0,
        brightnessFollowSystem = false,
        brightnessOffset = 0,
        eyeProtectionLevel = 0,
        brightnessGestureLeft = false,
        brightnessGestureRight = false,
        brightnessGestureTwo = false,
    )

    /**
     * **不进版式管线**的整族（亮度族 + 纯 UI 开关）：两端壳共用的一条规则，单一真相源。
     *
     * 背景：共享面板已把设置分成两类提交——排版类走 `onCommitTypography`（触发重排），
     * 不影响排版的走 `onCommitLight`（只持久化 + 重组，见 `ReaderSettingsPanel` KDoc 语义条约）。
     * 平板壳照此分流（`ReaderActivity.commitSettings(typographyChanged=false)` 不重排）；
     * 桌面壳两条回调合流，只能自己比对判断，原先用的 [withoutLight] **只含亮度族**，
     * 于是切纯 UI 开关仍会走完「本章全量重排 + 整书重排请求 + 两次落位推送」白干一场
     * （`LayoutParamKey.fromProfile` 不含这些字段 ⇒ 磁盘表不会被误删，属浪费而非损坏）。
     *
     * 语义：把整族清成**中性值**，使相等比较只关心版式族（与 [withoutLight] 同构）。
     * 布尔清 `false`、字符串清 `""` —— 不能清成某个真值，否则基线本身就是那个值时比较失效。
     *
     * 维护纪律（`SettingsLayoutScopeTest` 锁）：
     *  1. 新增设置字段先判「是否进 [orilumn.reader.engine.text.LayoutParamKey]」——
     *     **不进就必须登记进本函数**，否则又变一次「切开关触发全量重排」；
     *  2. 登记后，面板那一行也必须走 `onCommitLight`（两处必须同改，`SettingsLayoutScopeTest`
     *     只锁字段族，改不到面板路由——面板路由由该测试的源码扫描断言兜底）。
     */
    fun withoutNonLayout(): ReaderSettings = copy(
        brightness = 0,
        brightnessFollowSystem = false,
        brightnessOffset = 0,
        eyeProtectionLevel = 0,
        brightnessGestureLeft = false,
        brightnessGestureRight = false,
        brightnessGestureTwo = false,
        // 纯 UI 开关族（f2571fa 起面板已走 onCommitLight，此处补桌面壳的判定侧）
        pageAnim = false,
        pageAnimationMode = "",
        autoContinue = false,
        pageNum = false,
        coverStretch = false,
        showHiddenFonts = false,
    )

    companion object {
        /** Default set: factory values when no customization has been made. */
        val DEFAULT = ReaderSettings()

        /**
         * 当前持久化结构版本。每有一处**只对新语义成立**的一次性迁移就 +1，
         * 并在 [fromJson] 里补一段 `if (stored < N) …`。
         */
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * v1 迁移：**重置混排字距到默认值**（用户报「设置里看到 0、代码默认却是 25」）。
         *
         * ## 为什么重置是对的（而不是「保留 0」或「判 0 就改 25」）
         *
         * 这个字段在本次修复前是**整套死代码**：设置能存、滑块能拖、`fromJson` 照读，
         * 但渲染侧没有任何一处消费它（`bodyParagraphBreaker` 丢弃第 2 个实参、
         * `ParagraphBreaker.breakLines` 没有这个形参）。于是**在旧版里拖出来的任何值都是盲选** ——
         * 用户在「拖了没反应」的滑块上停留时随手拖到的 80，跟他认真调到 25，在落盘文件里
         * **完全同形、无法区分**。保留它 = 把一个无意义的数字当成用户的明确意图继承下来。
         *
         * ## 为什么不是「`stored == 0 → 25`」
         *
         * 0 是合法档位（= 关闭间隙）。判值会把**故意设 0 的用户**也改回 25，而且每次启动都改一次。
         * 版本号只对「写档那一刻还不存在的语义」生效一次，之后用户怎么拖都尊重。
         *
         * ## 代价（知情）
         *
         * 本次修复前拖过滑块的用户，升级后 [cjkLatinSpacing] 会**从他的旧值回到 25**。
         * 这是有意的一次性代价：那串旧值本来就没有生效过，保留它等于假装尊重一个从未存在的意图。
         */
        private fun migrateCjkLatinSpacing(stored: Double, storedVersion: Int): Double =
            if (storedVersion < 1) DEFAULT.cjkLatinSpacing else stored

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
                    // Old-key migration: coverProportional(false=stretch) → coverStretch(true=stretch), inverted.
                    coverStretch = if (o.containsKey("coverStretch")) {
                        SettingsJson.optBoolean(o, "coverStretch", d.coverStretch)
                    } else if (o.containsKey("coverProportional")) {
                        !SettingsJson.optBoolean(o, "coverProportional", false)
                    } else {
                        d.coverStretch
                    },
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
                    showHiddenFonts = SettingsJson.optBoolean(o, "showHiddenFonts", d.showHiddenFonts),
                    fontWeightAnchors = SettingsJson.optWeightAnchors(o, "fontWeightAnchors"),
                    fontWeightAnchorsBySlot = SettingsJson.optWeightAnchorsMap(o, "fontWeightAnchorsBySlot", d.fontWeightAnchorsBySlot),
                    cjkLatinSpacing = migrateCjkLatinSpacing(
                        SettingsJson.optDouble(o, "cjkLatinSpacing", d.cjkLatinSpacing),
                        SettingsJson.optInt(o, "schemaVersion", 0),
                    ),
                    schemaVersion = CURRENT_SCHEMA_VERSION,
                )
            } catch (_: Exception) {
                DEFAULT
            }
        }
    }
}