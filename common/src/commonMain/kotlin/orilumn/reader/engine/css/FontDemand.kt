package orilumn.reader.engine.css

/**
 * 一章实际用到的字体需求（族名 × 字重 × 斜体），供宿主在整形**之前**把命中的导入字库
 * 装进 skia 共用池——开屏后追装必见字体跳变，无用户能接受，需求必须先行。
 *
 * 只扫作者层（`CssBundle.cssTexts`，已解析缓存外另行轻量解析，KB 级文本毫秒级）：
 * UA/主题/UI 层只可能是系统族。族名用 [parseFontFamilyList] 全栈收集（含回退项，
 * 命中即装）；字重用 [parseFontWeight] 数值（`bold`→700，`normal`→400 在解析内处理），
 * 未声明字重的章回退 {400, 700} 由调用方并入；斜体只要出现一次即全族带斜体面。
 */
data class FontDemand(
    val families: Set<String> = emptySet(),
    val weights: Set<Int> = emptySet(),
    val italic: Boolean = false,
) {
    companion object {
        val EMPTY = FontDemand()
    }
}

/** 扫作者 CSS 文本收集 [FontDemand]（纯函数；解析失败的表跳过，不抛）。 */
fun collectFontDemand(cssTexts: List<String>): FontDemand {
    val families = LinkedHashSet<String>()
    val weights = LinkedHashSet<Int>()
    var italic = false
    for (text in cssTexts) {
        val sheet = runCatching { LightCssParser().parse(text) }.getOrNull() ?: continue
        for (rule in sheet.rules) {
            for (d in rule.declarations) {
                when (d.property) {
                    "font-family" -> families += parseFontFamilyList(d.value)
                    "font-weight" -> parseFontWeight(d.value)?.let { weights += it }
                    "font-style" -> if (parseFontStyleItalic(d.value) == true) italic = true
                }
            }
        }
        // P2: `@font-face` 族名并入预装集合（整形前进池，避免开屏字体跳变）。
        for (face in sheet.cssFontFaces) {
            if (face.family.isNotBlank()) families += face.family
            face.weight?.let { weights += it }
            if (face.italic == true) italic = true
        }
    }
    return FontDemand(families, weights, italic)
}
