package orilumn.reader.ui.reader

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * Android actual：导入字体按文件建 `Typeface.createFromFile`（与字体预览同口径），
 * 系统族按族名 `Typeface.create`；都失败即回 `null`（调用方回退界面默认字体）。
 */
internal actual fun composeFontFamily(family: String, filePath: String?): FontFamily? {
    val typeface = filePath
        ?.takeIf { File(it).exists() }
        ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        ?: runCatching { Typeface.create(family, Typeface.NORMAL) }.getOrNull()
    return typeface?.let { FontFamily(it) }
}
