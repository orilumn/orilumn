package orilumn.reader.engine.skia

/** android：skiko `Paragraph.paint` 忽略 setHeight / setHalfLeading，基线用字体自然盒。 */
actual val paragraphPaintHonorsLineHeight: Boolean = false
