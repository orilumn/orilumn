package orilumn.reader.engine.skia

/**
 * 平台 Paragraph.paint 是否按 `TextStyle.setHeight` / `setHalfLeading` 缩放落基线。
 *
 * skiko 两个平台的 `Paragraph.paint` 对基线落位不同：
 * - jvm：按 setHeight 缩放后的 ascent 落基线，`setHalfLeading(true)` 即得浏览器的半行距居中。
 * - android：忽略 setHeight / setHalfLeading，基线 = 画笔原点 + 字体自然盒的一半，
 *   与浏览器差 (行高 − 自然字体高)，需在绘制原点手工补回（见 `LineWindowDrawer.paintText`）。
 *
 * 这是能力边界（同 `SystemFonts`），不是工程分叉。
 */
expect val paragraphPaintHonorsLineHeight: Boolean
