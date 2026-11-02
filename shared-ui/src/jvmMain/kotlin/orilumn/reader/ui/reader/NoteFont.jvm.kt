package orilumn.reader.ui.reader

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.text.platform.Typeface as ComposeTypeface
import orilumn.reader.engine.skia.SkParagraphFactory
import java.io.File

/**
 * JVM actual：导入字体直接按文件建 Compose `Font`（桌面 Skia 读字节，与字体预览同源）；
 * 系统族走 skia `FontMgr.matchFamilyStyle`（与阅读面同一张系统字库表），再包成 Compose 字体。
 *
 * 字重走 [SkParagraphFactory.runFontStyle] 的全粒度口径（100..900 原样透传，系统面就近命中）；
 * 导入字体已按字重挑定文件，文件本身即那一档字形。
 *
 * 任一步抛错 / 匹配不到即回 `null`（调用方回退界面默认字体），不让一条旁注把整页文字画崩。
 */
internal actual fun composeFontFamily(family: String, filePath: String?, weight: Int?): FontFamily? = runCatching {
    val file = filePath?.let { File(it) }?.takeIf { it.isFile }
    if (file != null) {
        FontFamily(Font(file))
    } else {
        val style = SkParagraphFactory.runFontStyle((weight ?: 400).coerceIn(100, 900), false)
        val skia = SkParagraphFactory.defaultFontMgr()
            .matchFamilyStyle(family, style)
            ?: return null
        FontFamily(ComposeTypeface(skia, family))
    }
}.getOrNull()
