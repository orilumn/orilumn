package orilumn.reader.ui.reader

import android.graphics.Typeface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import orilumn.reader.data.font.FontEntry
import java.io.File

/**
 * F4a Android actual：Compose Text + 平台 Typeface（真字形）。
 * 导入行按文件建 `Typeface.createFromFile`（沿旧 `FontManagerPanel` 口径，缺文件回族名）；
 * 系统行 `Typeface.create` 直接按族名取系统字体。
 * 取字形永远用原族名，展示文字走 [FontEntry.display]（本地化名或族名本身）。
 */
@Composable
actual fun FontPreviewText(
    entry: FontEntry,
    color: Color,
    fontSize: TextUnit,
    modifier: Modifier,
) {
    val typeface = remember(entry) {
        when (entry) {
            is FontEntry.Imported ->
                entry.face.path?.takeIf { File(it).exists() }
                    ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            is FontEntry.System -> null
        } ?: runCatching { Typeface.create(entry.family, Typeface.NORMAL) }.getOrNull()
    }
    Text(
        text = remember(entry) { entry.display },
        color = color,
        fontSize = fontSize,
        fontFamily = typeface?.let { FontFamily(it) },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}
