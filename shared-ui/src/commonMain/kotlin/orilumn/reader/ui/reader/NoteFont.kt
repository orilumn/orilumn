package orilumn.reader.ui.reader

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.font.SubfamilyMetric
import orilumn.reader.data.settings.ReaderSettings
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
 * 空串 = 跟随界面默认（当前观感，返回 `null`）；字体被删 / 找不到同样返回 `null`。
 * 导入字体要按 [FontEntry.Imported.face] 的文件路径取字形，系统族按族名取；选了字重时
 * 导入族挑**字重最接近**的那个文件（同族常按 Regular / Bold 分文件导入）。
 */
internal fun noteFontFamilyOf(settings: ReaderSettings, entries: List<FontEntry>): FontFamily? {
    val family = settings.noteFont
    if (family.isEmpty()) return null
    val weight = settings.noteFontWeight.takeIf { it in 100..900 }
    // 导入族优先：同族名既可能是系统行也可能是导入行，只有导入行带文件路径，
    // 取错会悄悄回退成系统字形（读者选的字体看起来「没生效」）。
    val imported = entries.filterIsInstance<FontEntry.Imported>()
        .filter { it.family == family && !SubfamilyMetric.italic(it.face.subfamily) }
    val chosen = if (weight == null) {
        imported.firstOrNull()
    } else {
        // 同族按字重分文件时，挑读者选的那一档；该族没有这一档就取最接近的，仍比恒取第一个准。
        imported.minByOrNull { abs(SubfamilyMetric.weight(it.face.subfamily) - weight) }
    } ?: entries.firstOrNull { it.family == family }
    val path = (chosen as? FontEntry.Imported)?.face?.path
    return composeFontFamily(family, path, weight)
}

/**
 * 设置里的批注字重 → 交给 Compose 文字样式的字重（`null` = 不写字重，跟随字体自身默认）。
 *
 * 为什么要单独交给 `TextStyle`：字重烤进字体族这条路在桌面端不成立——单面族没有第二档可选，
 * Compose 桌面仍按族名去系统字库取面，烤好的字重被丢掉（400 / 700 量出来宽度一模一样）。
 * 所以桌面系统族按「族名 + 这一档字重」解析（见 [composeFontFamily] 的 JVM actual），
 * 字重由调用方写进 `TextStyle`；导入字体与 Android 侧已按字重挑定字形面，再写一次也无副作用。
 *
 * 没配字重（`0` / 越界）就不发声明，让字体族按自己的默认脸显示。
 */
internal fun noteFontWeightOf(settings: ReaderSettings): FontWeight? =
    settings.noteFontWeight.takeIf { it in 100..900 }?.let { FontWeight(it) }
