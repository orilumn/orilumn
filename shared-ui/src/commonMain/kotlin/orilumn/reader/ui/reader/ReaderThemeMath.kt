package orilumn.reader.ui.reader

import orilumn.reader.data.settings.ReaderSettings
import kotlin.math.roundToInt

/**
 * S29 阅读主题纯逻辑：内置阅读主题、十六进制色解析/合成、自定义预设的增删与命名，
 * 与 UI 无关，可在 commonMain 直接跑 jvmTest 验证。平移自 Android `ReaderSettingsPanel`
 * 的私有辅助函数（把 `android.graphics.Color.parseColor` / SharedPreferences 等平台设施
 * 换成了纯 Kotlin）。
 */
public data class ThemePreset(
    val label: String,
    val bg: String,
    val fg: String,
)

internal object ReaderThemeMath {
    const val DEFAULT_BG = "#f4f2ec"
    const val DEFAULT_FG = "#262626"

    private val READER_DEFAULT_BG_ARGB: Int = (0xFF000000L or DEFAULT_BG.removePrefix("#").toLong(16)).toInt()

    val BUILTIN_THEMES: List<ThemePreset> = listOf(
        ThemePreset("原书设置", "", ""), ThemePreset("精白", "#ffffff", "#222222"), ThemePreset("象牙白", "#fffbf0", "#222222"),
        ThemePreset("霜色", "#eaecee", "#222222"), ThemePreset("缃色", "#f2ecde", "#222222"),
        ThemePreset("鸭卵青", "#e0eee8", "#222222"), ThemePreset("月白", "#d6ecf0", "#222222"),
        ThemePreset("粉白", "#fbeff2", "#222222"), ThemePreset("丁香", "#e8e0f0", "#222222"),
    )

    /** "#RRGGBB"（补 FF alpha） / "#AARRGGBB" → ARGB Int；解析失败回退 DEFAULT_BG（0xFFF4F2EC）。 */
    fun parseHex(hex: String): Int {
        val h = hex.removePrefix("#")
        return runCatching {
            val v = h.toLong(16)
            when (h.length) {
                6 -> (0xFF000000L or v).toInt()
                8 -> (v and 0xFFFFFFFFL).toInt()
                else -> error("unexpected hex length: ${h.length}")
            }
        }.getOrDefault(READER_DEFAULT_BG_ARGB)
    }

    fun hexOf(c: Int): String = "#" + (c and 0x00FFFFFF).toString(16).padStart(6, '0')

    internal fun r(c: Int) = (c shr 16) and 0xFF
    internal fun g(c: Int) = (c shr 8) and 0xFF
    internal fun b(c: Int) = c and 0xFF

    private fun setChannel(c: Int, ch: Int, v: Int): Int {
        val r = if (ch == 0) v.coerceIn(0, 255) else r(c)
        val g = if (ch == 1) v.coerceIn(0, 255) else g(c)
        val b = if (ch == 2) v.coerceIn(0, 255) else b(c)
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
    }

    /** 预设管理：改背景某通道（红=0/绿=1/蓝=2），前景跟随（空则取当前 fg）。 */
    fun applyBg(s: ReaderSettings, bg: Int, fg: Int, ch: Int, v: Double): ReaderSettings {
        val c = setChannel(bg, ch, v.roundToInt())
        return s.copy(bgOverride = hexOf(c), fgOverride = if (s.fgOverride.isNullOrBlank()) hexOf(fg) else s.fgOverride)
    }

    /** 预设管理：改前景灰度（三通道同值）。 */
    fun applyFg(s: ReaderSettings, fg: Int, v: Double): ReaderSettings {
        val gray = v.roundToInt().coerceIn(0, 255)
        return s.copy(fgOverride = hexOf(0xFF000000.toInt() or (gray shl 16) or (gray shl 8) or gray))
    }

    fun grayOf(c: Int): Double = (r(c) + g(c) + b(c)) / 3.0

    /** 阅读主题行标签：bg/fg 命中内置或自定义预设 → 预设名，否则 "自定义"。 */
    fun themeName(s: ReaderSettings, customs: List<ThemePreset>): String =
        (BUILTIN_THEMES + customs).firstOrNull { it.bg == s.bgOverride && it.fg == s.fgOverride }?.label ?: "自定义"

    fun layoutThemeLabel(v: String) = when (v) {
        "modern" -> "现代模式"
        "traditional" -> "传统模式"
        else -> "原书设置"
    }

    fun pageAnimationModeLabel(v: String) = if (v == "curl") "卷曲" else "平滑"

    /** [ReaderSettings.BASE_BODY_PX] 锚定的「物理字号 px → 相对字号槽位」。 */
    fun pxToScale(px: Double): Double = ReaderSettings.ratioToFontScale(px / ReaderSettings.BASE_BODY_PX)

    /** 自定义预设命名：bg 命中内置色名则用内置名，否则 "自定义"。 */
    fun labelFor(bg: String, fg: String): String {
        val name = buildString {
            when (bg.uppercase()) {
                "#FFFFFF" -> append("精白")
                "#FFFBF0" -> append("象牙白")
                "#F2ECDE" -> append("缃色")
                "#E0EEE8" -> append("鸭卵青")
                "#EAECEE" -> append("霜色")
                "#D6ECF0" -> append("月白")
                "#E8E0F0" -> append("丁香")
                "#FBEFF2" -> append("粉白")
                else -> append("自定义")
            }
        }
        return name
    }

    /** 保存自定义预设：空白背景不保存；完全重复（同 bg+fg）跳过避免目录重名。 */
    fun saveTheme(customs: List<ThemePreset>, bg: String, fg: String): List<ThemePreset> {
        if (bg.isBlank()) return customs
        if (customs.any { it.bg == bg && it.fg == fg }) return customs
        return customs + ThemePreset(labelFor(bg, fg), bg, fg)
    }

    fun deleteTheme(customs: List<ThemePreset>, bg: String, fg: String): List<ThemePreset> =
        customs.filterNot { it.bg == bg && it.fg == fg }
}