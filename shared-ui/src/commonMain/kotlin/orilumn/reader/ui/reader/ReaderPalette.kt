package orilumn.reader.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * S29 阅读面板共用调色板（复刻旧 `--ui-*` 日/夜配色），设置抽屉与 TOC 抽屉共用同一 Palette，
 * 由 [ReaderSettings.scheme] 决定。内部使用——不在任何公共签名暴露。
 */
data class Palette(
    val bg: Color, val text: Color, val border: Color, val borderSoft: Color,
    val rowActive: Color, val cardBg: Color, val cardBorder: Color,
    val muted: Color, val muted2: Color, val chevron: Color,
    val switchTrack: Color, val sliderTrack: Color, val sliderBtn: Color,
    val selBg: Color, val selText: Color, val selBorder: Color,
)

internal val PanelGold = Color(0xFFC8A15A)
internal val PanelMut = Color(0xFF999999)

internal fun paletteFor(scheme: String): Palette = when (scheme) {
    "night" -> Palette(Color(0xFF1C1C1E), Color(0xFFE8E8E8), Color(0xFF2C2C2E), Color(0xFF2A2A2C),
        Color(0xFF262628), Color(0xFF232326), Color(0xFF343438), Color(0xFF8A8A8A), Color(0xFF909090), Color(0xFF666666),
        Color(0xFF4A4A4E), Color(0xFF3A3A3E), Color(0xFF2C2C2E), Color(0xFF2A2018), Color(0xFFD9A94F), Color(0xFF8A5F1F))
    else -> Palette(Color(0xFFFAF8F4), Color(0xFF2B2B2B), Color(0xFFECE9E2), Color(0xFFF0EDE6),
        Color(0xFFEFECE4), Color(0xFFFFFFFF), Color(0xFFE3DFD5), Color(0xFF999999), Color(0xFF888888), Color(0xFFBBBBBB),
        Color(0xFFC9C4BA), Color(0xFFE7E3D9), Color(0xFFF0EDE6), Color(0xFFFAF3E6), Color(0xFF8A5F1F), Color(0xFFC8A15A))
}

/** Self-drawn slider thumb diameter and radius (solid circle). */
internal val SliderThumbSize = 18.dp
internal val SliderThumbR = 9.dp

/**
 * 键盘焦点环颜色（金色描边）：行被 ↑↓ 键焦点命中时的视觉，与 hover/按下态（白底高亮）
 * 明确区分。鼠标光标不动——键盘焦点与鼠标悬停是两套指示，这是标准行为。
 */
internal val KbFocusRing = Color(0xFFC8A15A)