package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.io.Logger
import kotlin.math.roundToInt

/**
 * Computes layout constants from the "effective reading settings" (a pure projection, no side
 * effects) — S19 migrated the pure projection into commonMain, replacing the old Android
 * `android.graphics.Color` / `android.text.TextPaint` usages with pure ARGB/HSL math.
 *
 * Input: a snapshot of the settings actually effective for the current book after
 * [ReaderSettings.applyOverlay]. Output: font sizes, line spacing, paragraph gap, theme
 * foreground/background colors, reading margins, three font aliases and reader-specific
 * typography flags. Consumed by [orilumn.reader.engine.BoxChapterLayouter] and related engine code.
 *
 * Color scheme: composed from the background scheme [fitscheme] (day/night) overridable by
 * [fitbgOverride]/[fitfgOverride]; when any override is empty, fall back to the defaults for the
 * corresponding scheme.
 */
data class TypographicProfile(
    /** Body font size (px), = fontSize base x fontScale relative factor. */
    val bodyPx: Float,
    /** Heading base font size relative to the body (h6); h1=x2.0, h6=x1.4, gradating by level. */
    val headingScale: Float,
    /** Quote-block font size relative to the body. */
    val quoteScale: Float,
    /** Code font size relative to the body. */
    val codeScale: Float,
    /** Line-spacing multiplier (1.x). */
    val lineSpacing: Float,
    /** Line-spacing multiplier as consumed by the legacy Android StaticLayout mix path. The box
     *  engine no longer reads it (uniform line heights, see the Android-side UniformLineHeightSpan),
     *  so commonMain keeps the pure CSS value directly (CSS line-height / per-font kFont calibration
     *  was a TextPaint measurement and lives on the engine-skia side if ever needed again). */
    val lineSpacingMult: Float,
    /** First-line indent in em (0..10, applied to body paragraphs p/li via the UI layer; 0 = none). */
    val firstLineIndentEm: Float,
    /** Theme body-text color (ARGB). */
    val fgColor: Int,
    /** Theme background color (ARGB). */
    val bgColor: Int,
    /** Quote text color (slightly lighter than the body). */
    val quoteColor: Int,
    /** Reading left/right margin (px). */
    val marginLeft: Int,
    val marginRight: Int,
    /** Reading top/bottom margin (px). */
    val marginTop: Int,
    val marginBottom: Int,
    /** Font substitution * body/title/code alias; empty = don't substitute (follow system/original
     * book). */
    val fontBody: String,
    val fontTitle: String,
    val fontCode: String,
    /** 用户在该族选的字重（族名 → CSS 字重）：UI 层发 `font-weight` 声明（`ReaderUiSheet.fontRules`）。
     *  `FontPoolSync` 用它决定预装哪几档面。 */
    val fontWeightAnchors: Map<String, Int> = emptyMap(),
    /** 同上但**按槽位隔离**：key = "fontTitle|族名"。优先于 [fontWeightAnchors]。 */
    val fontWeightAnchorsBySlot: Map<String, Int> = emptyMap(),
    /** Master switch to follow the original book styles. */
    val useOriginalStyle: Boolean,
    /** 排版主题 key ("original" | "modern" | "traditional"); drives the theme layer stylesheet
     *  (tier 42) and, via [build], the effective 首行缩进/段间距 for the UI layer. */
    val layoutTheme: String,
    /** Cover stretch switch (true = stretch fullscreen). */
    val coverStretch: Boolean,
    /** 段间距：p/li 纵边距乘算 (1.0 = 书/主题节奏，0 = p/li 边距清零)。 */
    val paragraphSpacingScale: Float,
    /** 疏密：p/li 之外一切块级纵边距乘算 (1.0 = 原书节奏，0 = 结构块边距清零)。 */
    val paragraphGapScale: Float,
    /** Character spacing in em (letterSpacing slot -100..100 / 500 = -0.2em..0.2em); 0 = no extra
     * spacing. Applied to the base text paint in the shaping layer. */
    val letterSpacingEm: Float,
    /** CJK–Latin automatic spacing in em (0..1.0). 用户设置的原始值（**不代表能不能生效**，见
     *  [cjkLatinSpacingEmApplied]）。 */
    val cjkLatinSpacingEm: Float = 0f,
) {

    /**
     * **真正施加到版面上的**混排字距（em）—— 绘制侧（[orilumn.reader.engine.skia.LineWindowDrawer] /
     * [orilumn.reader.engine.skia.KerningClusterTable] / [orilumn.reader.engine.skia.DrawLineBuilder]）
     * 一律读本值，不读 [cjkLatinSpacingEm]。
     *
     * ## 为什么多这一层（用户可见的效果：滑块拖了、书没变）
     *
     * 间隙是**注入的宽度**：它必须在版心里被**预留**（否则超出版心被裁，分页阅读器硬错误），
     * 而只有 [orilumn.reader.engine.skia.InhouseParagraphBreaker] 能预留 —— Skia 回退阀那条路
     * 无法让 `SkParagraph` 为外来宽度让位。于是开关 off 时若还照画，症状就是「版面溢出」。
     * ⇒ 开关 off ⇒ 本值返 0。0 是**零间隙档**而不是「整条关掉」档（产品口径 2026-10-03）：
     *   边界照检、作者手打的分隔空格照吃，注入的间隙宽 0 —— 见
     *   [orilumn.reader.engine.text.preprocess.CjkLatinSpacing] 类 KDoc。
     *   量侧与画侧**同为 0** 就够一致了（两侧都吃同一批空格、都注入 0 宽间隙），
     *   本闸门要的正是这个「两侧拿到同一个数」。
     *
     * ⚠ 与 [orilumn.reader.engine.text.LayoutParamKey.fromProfile] 的闸门是**同一个判据**
     *   （[orilumn.reader.engine.AbSwitch.inhouseBreak]）：键算出来的间隙值必须与实际施加的值
     *   相等，否则磁盘表会按一种间隙算出的行宽、绘制按另一种值画（量画失配，教训 ⑩）。
     *   两处都调 [AbSwitch.inhouseBreak]，但**都在每版心一次的构造期**调，不是每字每行调
     *   （[AbSwitch.isOn] 走 `synchronized`，热路径调用会拖垮排版线程）。
     */
    val cjkLatinSpacingEmApplied: Float
        get() = if (orilumn.reader.engine.AbSwitch.inhouseBreak()) cjkLatinSpacingEm else 0f

    /** 主题感知链接色（Z4 单源）：暗底亮青 (#71B8FF) / 亮底深蓝 (#1A66CC)，按背景亮度判定。
     *  平板 `CssLayouter.linkColorHex` 与桌面 `DesktopReaderHost.linkColorHex()` 公式平移后
     *  统一委托本属性，两端同一份颜色决策。 */
    val linkColorHex: String
        get() {
            val lum = (0.299 * red(bgColor) + 0.587 * green(bgColor) + 0.114 * blue(bgColor)) / 255.0
            return if (lum < 0.5) "#71B8FF" else "#1A66CC"
        }

    companion object {
        /**
         * Computes layout constants from the effective settings (pure projection).
         *
         * Unit convention: font sizes/margins/gaps in the settings are stored in **dp** (aligned
         * with HTML/CSS, consistent across devices); [density] is the current device
         * dp→physical-pixel factor, converting dp to **physical pixels** for the shaping layer
         * (`px = dp x density`). pageWidth is injected by the caller in physical pixels
         * (View.getWidth); the whole lower engine works in physical pixels — both conventions match.
         */
        fun build(s: ReaderSettings, density: Float = 1f): TypographicProfile {
            // 排版主题预设 (传统/现代) 由 withLayoutTheme 在提交时写进设置 (首行缩进/段间距/字体);
            // 原书设置 也写入中性默认值 (无字体覆盖/无缩进/段距默认). 此处一律原样透传存储值,
            // 滑块值即生效值; 用户拖滑块永远是最高优先级.
            val original = s.layoutTheme == "original" || s.useOriginalStyle
            val body = dpToPxF(s.fontSize.toDouble() * ReaderSettings.fontScaleToRatio(s.fontScale), density)
            val (bg, fg, quote) = colors(s)
            return TypographicProfile(
                bodyPx = body,
                headingScale = 1.4f,
                quoteScale = 1.0f,
                codeScale = 0.92f,
                lineSpacing = s.lineSpacing.toFloat(),
                lineSpacingMult = s.lineSpacing.toFloat(),
                firstLineIndentEm = s.firstLineIndent.coerceIn(0.0, 10.0).toFloat(),
                fgColor = fg,
                bgColor = bg,
                quoteColor = withAlpha(fg, 0.72f),
                marginLeft = dpToPx(s.marginLeft, density),
                marginRight = dpToPx(s.marginRight, density),
                marginTop = dpToPx(s.marginTop, density),
                marginBottom = dpToPx(s.marginBottom, density),
                // 字体槽: 预设写 "" = 不替换 (跟原书); 用户选字体后写入非空值 → 生效.
                fontBody = s.fontBody,
                fontTitle = s.fontTitle,
                fontCode = s.fontCode,
                fontWeightAnchors = s.fontWeightAnchors,
                fontWeightAnchorsBySlot = s.fontWeightAnchorsBySlot,
                useOriginalStyle = original,
                layoutTheme = s.layoutTheme,
                coverStretch = s.coverStretch,
                // 段间距/字距: 同样原样透传, 用户可随时调整.
                paragraphSpacingScale = (s.paragraphSpacing / 100f).toFloat(),
                paragraphGapScale = (s.paragraphGap / 100f).toFloat(),
                letterSpacingEm = Math.round(s.letterSpacing.coerceIn(-100.0, 100.0)) / 500f,
                cjkLatinSpacingEm = (s.cjkLatinSpacing.coerceIn(0.0, 100.0) / 100f).toFloat(),
            )
        }

        /**
         * 切排版主题: 把主题预设的排版值一次写入 UI 设置, 让滑块值立刻跟随主题,
         * 避免滑块与实际值不一致. 主题预设只是"同步保存的 UI 设置", 用户仍可随时
         * 调整滑块覆盖.
         *
         * 传统/现代: 写入首行缩进/段间距/正文字体族 (字体槽是 UI 优先级最高的覆盖层,
         * 所以预设写入 = 直接生效).
         * 原书设置: 写入全套中性默认值 (字体不覆盖 / 字号/段间距/字距/行距 / 缩进)
         * 让书籍排版完全回到 CSS 原貌; 用户可随时重新调整. 持久化范围由调用方决定:
         * 现代/传统 走 diff 传染全局; 原书设置 只写本书私有 overlay, 不传染.
         */
        @JvmStatic
        fun withLayoutTheme(s: ReaderSettings, theme: String): ReaderSettings {
            val base = ReaderSettings.DEFAULT
            return when (theme) {
                "traditional" -> s.copy(
                    layoutTheme = theme,
                    firstLineIndent = 2.0,
                    // 传统节奏靠 1em 主题边距 × 段间距：p/li 归零（缩进区分段落），标题等结构块
                    // 照常走疏密（预设 100），用户上调段间距即有间距。
                    paragraphSpacing = 0.0,
                    paragraphGap = 100.0,
                    fontBody = "serif",
                )
                "modern" -> s.copy(
                    layoutTheme = theme,
                    firstLineIndent = base.firstLineIndent,
                    // 现代节奏同样走主题 1em 边距 × 段间距：预设回到 100%。
                    paragraphSpacing = 100.0,
                    paragraphGap = 100.0,
                    fontBody = "sans-serif",
                )
                else -> s.copy(
                    // 原书设置: 全套中性值 — 不替换字体、字号回到基准、行距/段间距/字距归位
                    // (恒等), 缩进回到默认. Android 切后用书探测值替换首行缩进/行距
                    // (见 ReaderActivity.withBookStyle)，用户可随时调整滑块覆盖.
                    layoutTheme = theme,
                    fontSize = base.fontSize,
                    fontScale = base.fontScale,
                    fontBody = "",
                    fontTitle = "",
                    fontCode = "",
                    lineSpacing = base.lineSpacing,
                    firstLineIndent = base.firstLineIndent,
                    paragraphSpacing = base.paragraphSpacing,
                    paragraphGap = base.paragraphGap,
                    letterSpacing = base.letterSpacing,
                )
            }
        }

        /**
         * 排版主题预设的正文通用字体族: 传统 → serif, 现代 → sans-serif, 原书设置 → null (不接管字体).
         * 切换主题时由 [withLayoutTheme] 把它同步写入「正文」字体槽 (一个普通 UI 设置), 而非由主题层
         * 在渲染时压过用户设置. 与 common resources css/traditional.css / modern.css 的 body 规则保持一致.
         */
        @JvmStatic
        fun layoutThemeFontFamily(theme: String): String? = when (theme) {
            "traditional" -> "serif"
            "modern" -> "sans-serif"
            else -> null
        }

        /** dp -> physical pixels (Int): the whole engine unifies this conversion
         * `px = dp x density` here, avoiding scattered reimplementation. */
        fun dpToPx(dp: Int, density: Float): Int = (dp * density).roundToInt()

        /** dp -> physical pixels (Float): for fractional scenarios like the body font size. */
        fun dpToPxF(dp: Double, density: Float): Float = (dp * density).toFloat()

        /** Composes the theme colors: night uses a dark background with light text, day the
         * reverse; overrides take priority.
         *
         * When in night mode and a day-background override exists, we automatically generate a
         * darkened night version from the day version via HSL: invert lightness, clamp saturation
         * and lightness, keep original hue; this preserves the base color's temperature.
         */
        private fun colors(s: ReaderSettings): Triple<Int, Int, Int> {
            return when (s.scheme) {
                "night" -> {
                    val dayBg = s.bgOverride.takeIf { it.isNotBlank() }?.let(::parseColor)
                        ?: 0xFFF4F2EC.toInt()
                    val nightBg = if (dayBg != 0) nightBackgroundOf(dayBg) else 0xFF121212.toInt()
                    val dayFg = s.fgOverride.takeIf { it.isNotBlank() }?.let(::parseColor) ?: 0xFF2B2B2B.toInt()
                    // Text is handled independently of the background: mirror the day ink's luminance
                    // (day concentrates to #222222, night dilutes to #DDDDDD); no HSL hue transform for text.
                    val nightFg = nightForegroundOf(dayFg)
                    Triple(nightBg, nightFg, withAlpha(nightFg, 0.72f))
                }
                else -> {
                    val dayBg = s.bgOverride.takeIf { it.isNotBlank() }?.let(::parseColor) ?: 0xFFF4F2EC.toInt()
                    val defaultFg = 0xFF2B2B2B.toInt()
                    val dayFg = s.fgOverride.takeIf { it.isNotBlank() }?.let(::parseColor) ?: defaultFg
                    Triple(dayBg, dayFg, withAlpha(dayFg, 0.72f))
                }
            }
        }

        /**
         * Generate a night-mode background color from a day-mode background color using HSL inversion:
         * invert lightness, clamp saturation and lightness to avoid pure black/overly saturated colors,
         * keep the original hue. Text remains independent (fixed light/dark based on scheme).
         *
         * If the day background is already dark (lightness < 0.5) return it unchanged.
         * Algorithm: HSL (not HSV) to preserve hue, keep color temperature consistent.
         */
        @JvmStatic
        fun nightBackgroundOf(dayColor: Int): Int {
            val l = hslLightness(dayColor)
            if (l < 0.5f) {
                // Day background is already dark, do nothing
                return dayColor
            }
            val (h, s) = hslHueSaturation(dayColor, l)
            var newL = 1.0f - l
            var newS = s
            // Clamp saturation to avoid over-saturated dark colors which are harsh on eyes
            newS = minOf(newS, 0.12f)
            // Clamp lightness to avoid pure black (helps reduce OLED flicker and improves contrast)
            newL = maxOf(newL, 0.08f)
            return hslToRgb(h, newS, newL)
        }

        /**
         * HSL 明度（0..1），**全仓「颜色暗不暗」的唯一判据**。
         *
         * 抽出来是为了让 [nightBackgroundOf] 与 [isDarkBackground] 共用同一阈值
         * （`l < 0.5`）：两个用途一旦各写一份，日后改阈值就会漏改一处，
         * 出现「夜间背景按 A 判暗、状态栏图标按 B 判亮」的错位。
         */
        private fun hslLightness(color: Int): Float {
            val r = red(color) / 255f
            val g = green(color) / 255f
            val b = blue(color) / 255f
            return (maxOf(r, g, b) + minOf(r, g, b)) / 2f
        }

        /** 色相与饱和度（0..1），依赖已算好的 [hslLightness] 以免重复扫通道。 */
        private fun hslHueSaturation(color: Int, l: Float): Pair<Float, Float> {
            val r = red(color) / 255f
            val g = green(color) / 255f
            val b = blue(color) / 255f
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max == min) return 0f to 0f
            val d = max - min
            val s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
            val h = when (max) {
                r -> ((g - b) / d + if (g < b) 6 else 0) / 6f
                g -> ((b - r) / d + 2) / 6f
                b -> ((r - g) / d + 4) / 6f
                else -> 0f
            }
            return h to s
        }

        /**
         * 这个颜色算不算**暗色**（背景用）。判据与 [nightBackgroundOf] 的「已暗则原样保留」同一阈值。
         *
         * 层级：**排版层**背景色单源的派生查询。调用方是用户层（Android 状态栏图标明暗），
         * 但**判据本身不许下沉到调用方重写** —— 一旦下游自己算一遍灰度，就会与夜间背景
         * 的暗色判定分叉。
         */
        @JvmStatic
        fun isDarkBackground(color: Int): Boolean = hslLightness(color) < 0.5f

        /**
         * 系统栏图标该用**深色**（true）还是**浅色**（false）。
         *
         * 语义对齐 Android 的 `WindowInsetsControllerCompat.isAppearanceLightStatusBars`：
         * true = 「状态栏底色是亮的」⇒ 系统画深色图标。
         *
         * **为什么不看 `scheme`**：夜间模式下 [colors] 必给暗底（[nightBackgroundOf] 保证），
         * 所以 `scheme != "night"` 在**夜间**一直是对的；但 `day` 分支的底色就是 [ReaderSettings.bgOverride]
         * 原值，用户可以用滑块调深、可以存深色自定义预设 —— 那时底色是暗的而 `scheme` 仍是 `day`，
         * 判「深色图标」就画在暗底上，**看不见**。故必须按**实际底色**判。
         *
         * ⚠ **边界**：书中 CSS 自带的背景图会铺在底色之上。本函数只看纯色底色，
         * 故「深色底 + 浅色背景图」时图标色仍可能不合 —— 背景图非设置项（书里 HTML 自带），
         * 要判就得采样位图，超出本函数职责，不在这里猜。
         */
        @JvmStatic
        fun wantsLightBarIcons(s: ReaderSettings): Boolean = !isDarkBackground(colors(s).first)

        /** Convert HSL (h: 0..1, s: 0..1, l: 0..1) to packed ARGB Int. */
        private fun hslToRgb(h: Float, s: Float, l: Float): Int {
            val r: Float
            val g: Float
            val b: Float
            if (s == 0f) {
                r = l
                g = l
                b = l
            } else {
                fun hue2rgb(p: Float, q: Float, t: Float): Float {
                    var tt = t
                    if (tt < 0) tt += 1
                    if (tt > 1) tt -= 1
                    return when {
                        tt < 1 / 6f -> p + (q - p) * 6 * tt
                        tt < 1 / 2f -> q
                        tt < 2 / 3f -> p + (q - p) * (2 / 3f - tt) * 6
                        else -> p
                    }
                }
                val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
                val p = 2 * l - q
                r = hue2rgb(p, q, h + 1 / 3f)
                g = hue2rgb(p, q, h)
                b = hue2rgb(p, q, h - 1 / 3f)
            }
            return argb(255, (r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        }

        /**
         * Map a day-mode (dark) ink to a night-mode (light) ink by mirroring its luminance.
         * Text is independent from the background transform: day concentrates (#222222) ↔ night
         * dilutes (#DDDDDD). Ink is grayscale, so an exact mirror of the gray channel keeps it neutral.
         */
        @JvmStatic
        fun nightForegroundOf(dayColor: Int): Int {
            val gray = ((red(dayColor) + green(dayColor) + blue(dayColor)) / 3).coerceIn(0, 255)
            val light = (255 - gray).coerceIn(160, 255)
            return argb(255, light, light, light)
        }

        /**
         * Parses `#RGB` / `#ARGB` / `#RRGGBB` / `#AARRGGBB` hex into a packed ARGB Int
         * (RGB triple is always opaque, matching Android's `Color.parseColor`). On any malformed
         * input the default night-safe ink is returned instead of throwing (a pure equivalent of
         * the previous `Color.parseColor` call never crashing the profile build).
         */
        private fun parseColor(hex: String): Int = runCatching {
            val h = hex.trim().removePrefix("#")
            when (h.length) {
                3 -> argb(255, hexNibble(h, 0), hexNibble(h, 1), hexNibble(h, 2))
                4 -> argb(hexNibble(h, 0), hexNibble(h, 1), hexNibble(h, 2), hexNibble(h, 3))
                6 -> argb(255, hexByte(h, 0), hexByte(h, 2), hexByte(h, 4))
                8 -> argb(hexByte(h, 0), hexByte(h, 2), hexByte(h, 4), hexByte(h, 6))
                else -> error("invalid hex length")
            }
            // 脏书 CSS 颜色回退保留（渲染层不为一个颜色炸整章），但记 w——否则与书内声明
            // 不一致且无迹可查。失败是按书稀有事件，非高频，可打。
        }.onFailure { Logger.w("Orilumn.CSS", "parseColor fallback $hex ${it.message}") }
            .getOrElse { 0xFF2B2B2B.toInt() }

        /** Expands a single hex digit into a full channel (`f` → `0xff`), per `Color.parseColor`'s `#rgb` rule. */
        private fun hexNibble(h: String, i: Int): Int {
            val v = h[i].digitToInt(16)
            return v * 17
        }

        /** Reads a 2-nibble channel starting at [i]. */
        private fun hexByte(h: String, i: Int): Int =
            h[i].digitToInt(16) * 16 + h[i + 1].digitToInt(16)

        private fun withAlpha(color: Int, alpha: Float): Int =
            argb((alpha * 255).toInt(), red(color), green(color), blue(color))

        // ---- pure ARGB helpers (replacing android.graphics.Color) ----
        private fun red(c: Int): Int = (c ushr 16) and 0xFF
        private fun green(c: Int): Int = (c ushr 8) and 0xFF
        private fun blue(c: Int): Int = c and 0xFF
        private fun argb(a: Int, r: Int, g: Int, b: Int): Int =
            (a shl 24) or (r shl 16) or (g shl 8) or b
    }
}