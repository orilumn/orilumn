package orilumn.reader.ui.reader

import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.max
import kotlin.math.min

/**
 * 旁注「使用标注同色字体」时的**文字色**（用户层·纯像素计算，不碰排版）。
 *
 * 问题：标注色是为**铺底**挑的，高亮还带 45% 透明度；直接拿来写字，日间纸白上
 * 就是一片浅色字（读者报的「看不清楚」），夜里深蓝 / 深紫写在黑底上同样糊成一块。
 *
 * 做法：**只动明度，不动色相与饱和度**——把标注色变成 HSL 三元组，保持 H / S 不变，
 * 沿「远离背景」的方向找**离原色最近、又够清楚**的那一档明度（WCAG 对比度 4.5:1，
 * 正文级小字的门槛）。日间浅底就往深里调，夜间深底就往浅里调，其余一概不碰。
 *
 * 为什么文字强制不透明：半透明的字最终是「标注色叠在纸上」的混色，明度再怎么调，
 * 45% 的浅红在白纸上也到不了 4.5:1——那不叫调明度，叫根本没调够。铺底仍按原透明度，
 * 只有字取不透明版本。
 *
 * @param tintArgb 标注自身颜色（高亮带 45% 透明度，这里只取它的色相 / 饱和度，
 *   字本身画成不透明）。
 * @param backdropArgb 字**实际压着**的底色：默认是纸面底色；开了批注背景色时是
 *   纸面与那层 16% 标底色的混色（见 [blendOver]）。
 *
 * 明暗判据走 [TypographicProfile.isDarkBackground]（全仓「底色暗不暗」的唯一阈值），
 * 不另看 `scheme`：读者可以把日间底色也调深，判断必须按实际底色来。
 */
internal fun noteInkColor(tintArgb: Int, backdropArgb: Int): Int {
    // 标注色可能带透明度（高亮 45%）：字取它的不透明版本。
    val ink = tintArgb or 0xFF000000.toInt()
    if (contrastRatio(ink, backdropArgb) >= NOTE_INK_MIN_CONTRAST) return ink

    val (h, s, l) = rgbToHsl(ink)
    val darkBg = TypographicProfile.isDarkBackground(backdropArgb)
    // 深底往浅里调、浅底往深里调；两端都到不了门槛就取端点（整体色相本身太接近背景色）。
    val farL = if (darkBg) 1f else 0f
    if (contrastRatio(hslToRgb(h, s, farL), backdropArgb) < NOTE_INK_MIN_CONTRAST) {
        return hslToRgb(h, s, farL)
    }
    // 二分找「刚好够清楚」的那一档明度：深底取最小可行明度，浅底取最大可行明度，
    // 两者都是离原色最近的解，避免把标注色一下压成黑/漂成白。
    var ok = farL
    var bad = l
    repeat(16) {
        val mid = (ok + bad) / 2f
        if (contrastRatio(hslToRgb(h, s, mid), backdropArgb) >= NOTE_INK_MIN_CONTRAST) {
            ok = mid
        } else {
            bad = mid
        }
    }
    return hslToRgb(h, s, ok)
}

/** 正文级小字的最低对比度（WCAG AA）。 */
internal const val NOTE_INK_MIN_CONTRAST = 4.5

/**
 * 不透明的 [fgArgb] 以 [alpha] 叠在不透明的 [bgArgb] 上（straight alpha）。
 *
 * 旁注开了「显示批注背景色」时，那层 16% 的标底色会先把纸面染一遍，字实际压在
 * **混色后**的底上。按纯纸面算对比度会偏乐观（浅粉底 + 压暗的粉字，看似 4.5 其实不够）。
 */
internal fun blendOver(bgArgb: Int, fgArgb: Int, alpha: Float): Int {
    fun channel(shift: Int): Int {
        val bg = (bgArgb ushr shift) and 0xFF
        val fg = (fgArgb ushr shift) and 0xFF
        return (bg + (fg - bg) * alpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** `#AARRGGBB` → HSL（h 0..1，s 0..1，l 0..1）。 */
internal fun rgbToHsl(argb: Int): Triple<Float, Float, Float> {
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val maxC = max(r, max(g, b))
    val minC = min(r, min(g, b))
    val l = (maxC + minC) / 2f
    val d = maxC - minC
    if (d == 0f) return Triple(0f, 0f, l)
    val s = if (l < 0.5f) d / (maxC + minC) else d / (2f - maxC - minC)
    val h = when (maxC) {
        r -> ((g - b) / d + if (g < b) 6f else 0f)
        g -> ((b - r) / d + 2f)
        else -> ((r - g) / d + 4f)
    } / 6f
    return Triple(h, s, l)
}

/** HSL（h 0..1，s 0..1，l 0..1）→ 不透明 `#FFRRGGBB`。 */
internal fun hslToRgb(h: Float, s: Float, l: Float): Int {
    if (s == 0f) {
        val v = (l.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    fun channel(t: Float): Int {
        val tt = ((t % 1f) + 1f) % 1f
        val v = when {
            tt < 1f / 6f -> p + (q - p) * 6f * tt
            tt < 1f / 2f -> q
            tt < 2f / 3f -> p + (q - p) * (2f / 3f - tt) * 6f
            else -> p
        }
        return (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
    return (0xFF shl 24) or (channel(h + 1f / 3f) shl 16) or (channel(h) shl 8) or channel(h - 1f / 3f)
}

/** WCAG 相对亮度（sRGB 线性化）；忽略 alpha，比较的是「画出来是什么颜色」。 */
internal fun relativeLuminance(argb: Int): Double {
    fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).let { it * it * it * it }
    }
    val r = linear((argb ushr 16) and 0xFF)
    val g = linear((argb ushr 8) and 0xFF)
    val b = linear(argb and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG 对比度（1..21）。 */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val hi = max(la, lb)
    val lo = min(la, lb)
    return (hi + 0.05) / (lo + 0.05)
}
