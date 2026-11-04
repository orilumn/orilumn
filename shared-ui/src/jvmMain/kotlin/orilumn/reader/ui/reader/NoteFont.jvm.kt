package orilumn.reader.ui.reader

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import orilumn.reader.engine.skia.SkParagraphFactory
import java.io.File

/**
 * JVM actual：导入字体直接按文件建 Compose `Font`（桌面 Skia 读字节，与字体预览同源）；
 * 系统族按**族名**交给 Compose，由它在系统字库里就近命中字形面。
 *
 * 系统族为什么不能把字重烤进单个字形面（`FontFamily(Typeface(skia, family))`）：
 * 单面族只有一张脸，Compose 桌面解析字体时仍拿**族名**去系统字库取面，烤好的那一档被丢掉——
 * 实测同一族 400 / 700 量出来的宽度一模一样（见 `NoteFontWeightTest`）。所以字重改由调用方
 * 写进 `TextStyle`（[orilumn.reader.ui.reader.noteFontWeightOf]），族名 + 字重一起交给系统字库才生效。
 *
 * 导入字体已按字重挑定文件，文件本身即那一档字形，不需要再折一次。
 *
 * 任一步抛错 / 匹配不到即回 `null`（调用方回退界面默认字体），不让一条旁注把整页文字画崩。
 */
@OptIn(ExperimentalTextApi::class)
internal actual fun composeFontFamily(family: String, filePath: String?, weight: Int?): FontFamily? = runCatching {
    val file = filePath?.let { File(it) }?.takeIf { it.isFile }
    if (file != null) {
        FontFamily(Font(file))
    } else {
        // 只为「这个族在不在」探一次：探不到就回 null，别让缺字族悄悄落到界面默认字体上。
        val style = SkParagraphFactory.runFontStyle((weight ?: 400).coerceIn(100, 900), false)
        SkParagraphFactory.defaultFontMgr().matchFamilyStyle(family, style) ?: return null
        FontFamily(family)
    }
}.getOrNull()
