package orilumn.reader.ui.reader

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.SubfamilyMetric
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.text.FontSlots
import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.abs

/**
 * 批注字体解析（渲染层·平台缝）：字族名（+ 导入字体的私有文件路径 + 字重）→ Compose 可用的 [FontFamily]。
 *
 * 为什么留在渲染层：字形只有平台认识（Android `Typeface`，桌面 Skia / AWT）。
 * 排版层与设置层只认字族名字符串，不持有平台对象——与正文槽位同口径。
 *
 * @param family 字族名（系统族或导入族的族名）
 * @param filePath 导入字体的私有文件路径；系统族传 `null`
 * @param weight 读者在字重页选的那一档（100..900）；`null` = 跟随字族自身默认。
 *   平台侧能落到真字形面就落（Android、导入字体文件）；桌面系统族落不到，由
 *   [noteFontWeightOf] 另写进 `TextStyle`，两者合起来才等于读者选的那一档。
 * @return `null` = 解析失败（调用方回退界面默认字体：批注照样能读，不因缺字丢内容）
 */
internal expect fun composeFontFamily(family: String, filePath: String?, weight: Int?): FontFamily?

/**
 * 设置里的批注字体 → 直接交给 Compose `Text` 的字体。
 *
 * **空串 = 跟随正文字体**（读者没给批注单独挑字）：按正文当前的可见字形解析——
 * 1. 正文槽挑过族 → 用那一族（含字重）；
 * 2. 没挑过 → 排版主题给正文声明的通用族（现代 = 无衬线、传统 = 衬线）；
 *    读者在「衬线 / 无衬线（全局）」里挑过族时，与正文同一条通用族替换通道换族；
 * 3. 「原书设置」→ `null`：正文由书的 CSS 决定，覆盖层无从得知，沿用基础样式（平台默认字体）。
 *
 * 批注单独挑过族时照旧按 [ReaderSettings.noteFont]，与正文无关；字体被删 / 找不到
 * （含跟随正文但正文那一族解析不到）同样返回 `null`——批注照样能读，不因缺字丢内容。
 *
 * 导入字体要按 [FontEntry.Imported.face] 的文件路径取字形，系统族按族名取；选了字重时
 * 导入族挑**字重最接近**的那个文件（同族常按 Regular / Bold 分文件导入）。
 */
internal fun noteFontFamilyOf(
    settings: ReaderSettings,
    profile: TypographicProfile,
    entries: List<FontEntry>,
): FontFamily? {
    if (settings.noteFont.isNotBlank()) {
        return familyOf(settings.noteFont, settings.noteFontWeight, entries)
    }
    if (profile.fontBody.isNotBlank()) {
        return familyOf(profile.fontBody, bodyWeightOf(profile), entries)
    }
    val generic = ReaderStylesheets.themeBodyFamily(profile.layoutTheme) ?: return null
    // 通用族替换（读者在「衬线 / 无衬线（全局）」里挑的族）；解析不到就退回平台通用族，
    // 与正文的表现一致（正文那条声明也把原关键字留在栈尾兜底）。
    val alias = profile.genericFontAliases[generic]
    if (alias != null) {
        familyOf(alias, profile.genericFontWeights[generic], entries)?.let { return it }
    }
    return genericFamilyOf(generic)
}

/**
 * 设置里的批注字重 → 交给 Compose 文字样式的字重（`null` = 不写字重，跟随字体自身默认）。
 *
 * **批注单独挑过字体时为读者选的那一档；没挑（跟随正文）时与正文同档**——正文槽的字重锚点，
 * 或主题通用族替换后的基线字重。否则会出现「正文是粗体、旁边批注是常规」的错位。
 *
 * 为什么要单独交给 `TextStyle`：字重烤进字体族这条路在桌面端不成立——单面族没有第二档可选，
 * Compose 桌面仍按族名去系统字库取面，烤好的字重被丢掉（400 / 700 量出来宽度一模一样）。
 * 所以桌面系统族按「族名 + 这一档字重」解析（见 [composeFontFamily] 的 JVM actual），
 * 字重由调用方写进 `TextStyle`；导入字体与 Android 侧已按字重挑定字形面，再写一次也无副作用。
 */
internal fun noteFontWeightOf(settings: ReaderSettings, profile: TypographicProfile): FontWeight? {
    val weight = if (settings.noteFont.isNotBlank()) {
        settings.noteFontWeight.takeIf { it in 100..900 }
    } else {
        bodyWeightOf(profile)
    }
    return weight?.let { FontWeight(it) }
}

/** 正文当前的读者字重（跟随正文时批注照搬）：正文槽锚点，或主题通用族替换后的基线字重。 */
private fun bodyWeightOf(profile: TypographicProfile): Int? {
    if (profile.fontBody.isBlank()) {
        val generic = ReaderStylesheets.themeBodyFamily(profile.layoutTheme) ?: return null
        return profile.genericFontWeights[generic]
    }
    return FontSlots.weightOf(
        profile.fontWeightAnchorsBySlot,
        profile.fontWeightAnchorsGlobal,
        profile.fontWeightAnchors,
        "fontBody",
        profile.fontBody,
    )
}

/** 具体族名 → Compose 字体：导入族按文件路径、系统族按族名，字重一起带去做字形面匹配。 */
private fun familyOf(family: String, weight: Int?, entries: List<FontEntry>): FontFamily? {
    val w = weight?.takeIf { it in 100..900 }
    // 导入族优先：同族名既可能是系统行也可能是导入行，只有导入行带文件路径，
    // 取错会悄悄回退成系统字形（读者选的字体看起来「没生效」）。
    val imported = entries.filterIsInstance<FontEntry.Imported>()
        .filter { it.family == family && !SubfamilyMetric.italic(it.face.subfamily) }
    val chosen = if (w == null) {
        imported.firstOrNull()
    } else {
        // 同族按字重分文件时，挑读者选的那一档；该族没有这一档就取最接近的，仍比恒取第一个准。
        imported.minByOrNull { abs(SubfamilyMetric.weight(it.face.subfamily) - w) }
    } ?: entries.firstOrNull { it.family == family }
    val path = (chosen as? FontEntry.Imported)?.face?.path
    return composeFontFamily(family, path, w)
}

/** CSS 通用族关键字 → Compose 的通用族（平台候选链）；不认识的返回 `null`（不覆盖）。 */
private fun genericFamilyOf(generic: String): FontFamily? = when (generic) {
    "serif" -> FontFamily.Serif
    "sans-serif" -> FontFamily.SansSerif
    "monospace" -> FontFamily.Monospace
    else -> null
}
