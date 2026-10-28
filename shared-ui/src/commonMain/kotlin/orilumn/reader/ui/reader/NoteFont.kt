package orilumn.reader.ui.reader

import androidx.compose.ui.text.font.FontFamily
import orilumn.reader.data.font.FontEntry
import orilumn.reader.data.settings.ReaderSettings

/**
 * 批注字体解析（渲染层·平台缝）：字族名（+ 导入字体的私有文件路径）→ Compose 可用的 [FontFamily]。
 *
 * 为什么留在渲染层：字形只有平台认识（Android `Typeface`，桌面 Skia / AWT）。
 * 排版层与设置层只认字族名字符串，不持有平台对象——与正文槽位同口径。
 *
 * @param family 字族名（系统族或导入族的族名）
 * @param filePath 导入字体的私有文件路径；系统族传 `null`
 * @return `null` = 解析失败（调用方回退界面默认字体：批注照样能读，不因缺字丢内容）
 */
internal expect fun composeFontFamily(family: String, filePath: String?): FontFamily?

/**
 * 设置里的批注字体 → 直接交给 Compose `Text` 的字体。
 *
 * 空串 = 跟随界面默认（当前观感，返回 `null`）；字体被删 / 找不到同样返回 `null`。
 * 导入字体要按 [FontEntry.Imported.face] 的文件路径取字形，系统族按族名取。
 */
internal fun noteFontFamilyOf(settings: ReaderSettings, entries: List<FontEntry>): FontFamily? {
    val family = settings.noteFont
    if (family.isEmpty()) return null
    // 导入族优先：同族名既可能是系统行也可能是导入行，只有导入行带文件路径，
    // 取错会悄悄回退成系统字形（读者选的字体看起来「没生效」）。
    val entry = entries.firstOrNull { it.family == family && it is FontEntry.Imported }
        ?: entries.firstOrNull { it.family == family }
    val path = (entry as? FontEntry.Imported)?.face?.path
    return composeFontFamily(family, path)
}
