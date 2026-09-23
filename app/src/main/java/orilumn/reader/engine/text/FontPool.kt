package orilumn.reader.engine.text

import android.graphics.Typeface
import orilumn.reader.data.font.FontFace
import orilumn.reader.data.font.FontFaceMatcher
import orilumn.reader.engine.skia.SkParagraphFactory
import orilumn.reader.engine.skia.systemFonts
import java.io.File

/**
 * 平板字体的 Android Canvas 兜底投影 —— 替代已退役的 `FontPairing` + `WeightedFontResolver` 类型链
 * （D1：Android Typeface 参与匹配的路径删除；resolve/fallback 全走 engine-skia `resolveFamilies`）。
 *
 * 本类只把 [SkParagraphFactory.resolveFamilies] 的整栈结果**投影**到 canvas 兜底绘制
 * （表格/符号，`ParagraphShapes.drawPaint`）需要的 [Typeface]，没有任何匹配算法
 * （无 matchClass 令牌表）：槽位路由早已由级联 UI 层（`ReaderUiSheet` 字体槽规则）写进
 * `families`，这里不再复述——单源在引擎，重复即漂移：
 *  - 具名族/通用关键字：`resolveFamilies` 展开/保留后机械 `Typeface.create(name, style)`；
 *  - 用户导入字体（SQLDelight + `FontParser` 二进制，宿主已装进同一 FontCollection）：
 *    按族名下已登记的 [FontFace] 用 [FontFaceMatcher] 挑权重文件（数据 → 文件，非匹配算法）。
 */
class FontPool(
    private val imported: Map<String, List<FontFace>> = emptyMap(),
) {

    /** engine-skia 解析 + Android 投影（输入栈已含槽位，见上）。 */
    fun resolve(tag: String?, families: List<String>, weight: Int, italic: Boolean, monospace: Boolean): Typeface =
        project(tag, families, weight, italic, monospace)

    private fun project(tag: String?, stack: List<String>, weight: Int, italic: Boolean, monospace: Boolean): Typeface {
        val resolved = SkParagraphFactory.resolveFamilies(tag, stack, monospace, systemFonts())
        val style = (if (weight >= 600) Typeface.BOLD else Typeface.NORMAL) or
            (if (italic) Typeface.ITALIC else 0)
        for (name in resolved) {
            val importedFace = imported[name]?.let { faces ->
                FontFaceMatcher.choose(faces, weight, italic) ?: faces.firstOrNull()
            }
            if (importedFace != null) {
                val path = importedFace.path
                if (path != null && File(path).exists()) {
                    val fileTf = runCatching { Typeface.createFromFile(path) }.getOrNull()
                    if (fileTf != null) return fileTf
                }
            }
            // 具名族未装时 Android 静默回落默认：不把默认脸当"命中"切断栈（与旧 SYSTEM 一致）。
            val tf = runCatching { Typeface.create(name, style) }.getOrNull()
            if (tf != null && tf != Typeface.DEFAULT) return tf
        }
        return Typeface.create(Typeface.DEFAULT, style)
    }
}