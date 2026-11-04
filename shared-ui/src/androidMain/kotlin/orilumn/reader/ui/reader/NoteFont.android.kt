package orilumn.reader.ui.reader

import android.graphics.Typeface
import android.os.Build
import androidx.compose.ui.text.font.FontFamily
import java.io.File

/**
 * Android actual：导入字体按文件建 `Typeface.createFromFile`（与字体预览同口径），
 * 系统族按族名 `Typeface.create`；都失败即回 `null`（调用方回退界面默认字体）。
 *
 * 字重尽量落到真字形面：API 28+ 用 `Typeface.create(family/typeface, weight, italic)`，
 * 旧系统只有 NORMAL / BOLD 两值，粗档（>= 600）折成 BOLD，其余按常规。
 */
internal actual fun composeFontFamily(family: String, filePath: String?, weight: Int?): FontFamily? {
    val w = (weight ?: 400).coerceIn(100, 900)
    val file = filePath?.takeIf { File(it).exists() }
    val typeface = if (file != null) {
        runCatching { Typeface.createFromFile(file) }.getOrNull()?.let { face ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                runCatching { Typeface.create(face, w, false) }.getOrNull() ?: face
            } else {
                face
            }
        }
    } else {
        runCatching {
            val base = Typeface.create(family, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Typeface.create(base, w, false)
            } else {
                base
            }
        }.getOrNull()
    }
    return typeface?.let { FontFamily(it) }
}
