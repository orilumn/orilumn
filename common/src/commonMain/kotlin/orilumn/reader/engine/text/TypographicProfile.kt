package orilumn.reader.engine.text

import orilumn.reader.data.settings.ReaderSettings
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
    /** Paragraph gap (px), = paragraphSpacing (em ratio) x bodyPx. */
    val paragraphSpacingPx: Int,
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
    /** Master switch to follow the original book styles. */
    val useOriginalStyle: Boolean,
    /** 排版主题 key ("original" | "modern" | "traditional"); drives the theme layer stylesheet
     *  (tier 42) and, via [build], the effective 首行缩进/段间距 for the UI layer. */
    val layoutTheme: String,
    /** Cover proportional-scaling switch. */
    val coverProportional: Boolean,
    /** Density: vertical outer-margin scale factor for structural blocks (heading/quote/code)
     * (1.0 = default, 0..4). */
    val paragraphGapScale: Float,
    /** Character spacing in em (letterSpacing slot -100..100 / 500 = -0.2em..0.2em); 0 = no extra
     * spacing. Applied to the base text paint in the shaping layer. */
    val letterSpacingEm: Float,
) {

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
                paragraphSpacingPx = (s.paragraphSpacing * body).roundToInt(),
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
                useOriginalStyle = original,
                layoutTheme = s.layoutTheme,
                coverProportional = s.coverProportional,
                // 疏密/字距: 同样原样透传, 用户可随时调整.
                paragraphGapScale = (s.paragraphGap / 100f).toFloat(),
                letterSpacingEm = Math.round(s.letterSpacing.coerceIn(-100.0, 100.0)) / 500f,
            )
        }

        /**
         * 切排版主题: 把主题预设的排版值一次写入 UI 设置, 让滑块值立刻跟随主题,
         * 避免滑块与实际值不一致. 主题预设只是"同步保存的 UI 设置", 用户仍可随时
         * 调整滑块覆盖.
         *
         * 传统/现代: 写入首行缩进/段间距/正文字体族 (字体槽是 UI 优先级最高的覆盖层,
         * 所以预设写入 = 直接生效).
         * 原书设置: 写入全套中性默认值 (字体不覆盖 / 字号/疏密/字距/行距 / 缩进/段距)
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
                    paragraphSpacing = 0.0,
                    fontBody = "serif",
                )
                "modern" -> s.copy(
                    layoutTheme = theme,
                    firstLineIndent = base.firstLineIndent,
                    paragraphSpacing = base.paragraphSpacing,
                    fontBody = "sans-serif",
                )
                else -> s.copy(
                    // 原书设置: 全套中性值 — 不替换字体、字号回到基准、行距/疏密/字距归零
                    // (恒等), 缩进/段距回到默认. Android 切后用书探测值替换排版三项
                    // (首行缩进/段间距/行距, 见 ReaderActivity.withBookStyle)，用户可随时调整滑块覆盖.
                    layoutTheme = theme,
                    fontSize = base.fontSize,
                    fontScale = base.fontScale,
                    fontBody = "",
                    fontTitle = "",
                    fontCode = "",
                    lineSpacing = base.lineSpacing,
                    paragraphSpacing = base.paragraphSpacing,
                    firstLineIndent = base.firstLineIndent,
                    paragraphGap = base.paragraphGap,
                    letterSpacing = base.letterSpacing,
                )
            }
        }

        /**
         * 排版主题预设的正文通用字体族: 传统 → serif, 现代 → sans-serif, 原书设置 → null (不接管字体).
         * 切换主题时由 [withLayoutTheme] 把它同步写入「正文」字体槽 (一个普通 UI 设置), 而非由主题层
         * 在渲染时压过用户设置. 与 assets/css/traditional.css / modern.css 的 body 规则保持一致.
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
            val r = red(dayColor) / 255f
            val g = green(dayColor) / 255f
            val b = blue(dayColor) / 255f

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            var h = 0f
            var s = 0f
            val l = (max + min) / 2f

            if (max != min) {
                val d = max - min
                s = if (l > 0.5f) d / (2 - max - min) else d / (max + min)
                h = when (max) {
                    r -> ((g - b) / d + if (g < b) 6 else 0) / 6f
                    g -> ((b - r) / d + 2) / 6f
                    b -> ((r - g) / d + 4) / 6f
                    else -> 0f
                }
            }

            return if (l < 0.5f) {
                // Day background is already dark, do nothing
                dayColor
            } else {
                var newL = 1.0f - l
                var newS = s
                // Clamp saturation to avoid over-saturated dark colors which are harsh on eyes
                newS = minOf(newS, 0.12f)
                // Clamp lightness to avoid pure black (helps reduce OLED flicker and improves contrast)
                newL = maxOf(newL, 0.08f)
                hslToRgb(h, newS, newL)
            }
        }

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
        }.getOrElse { 0xFF2B2B2B.toInt() }

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