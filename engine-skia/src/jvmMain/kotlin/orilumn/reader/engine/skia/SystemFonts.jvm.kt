package orilumn.reader.engine.skia

import org.jetbrains.skia.FontMgr

/** jvm 系统字体集合 = macOS CoreText（`FontMgr.default`）；测试环境确定存在。 */
actual fun systemFonts(): FontMgr = checkNotNull(FontMgr.default) { "FontMgr.default unavailable" }