package orilumn.reader.engine.skia

/** jvm：skiko `Paragraph.paint` 尊重 setHeight / setHalfLeading。 */
actual val paragraphPaintHonorsLineHeight: Boolean = true
