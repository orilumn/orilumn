package orilumn.reader.data.font

/**
 * CSS 通用族关键字（大小写不敏感）——**全仓唯一一份**。
 *
 * 两处消费者，方向都是「数据层被上层依赖」（`engine.css` 读它做族栈解析；`data.settings`
 * 读它识别「主题写进字体槽的通用族」，见 `BookSettings.fromJson`）：
 * 常量放在 `data.font` 而不是 `engine.css`，是为了让设置层不必反向依赖排版层。
 */
val GENERIC_FONT_FAMILIES: Set<String> = setOf("serif", "sans-serif", "monospace", "cursive", "fantasy")

/** 该族名是不是 CSS 通用族关键字（空/空白一律 false）。 */
fun isGenericFontFamily(name: String?): Boolean {
    if (name.isNullOrBlank()) return false
    return name.trim().lowercase() in GENERIC_FONT_FAMILIES
}
