package orilumn.reader.engine.skia

import org.jetbrains.skia.FontMgr

/** android 系统字体集合（skiko-android 的 `FontMgr.default`，走系统字体回退）。 */
actual fun systemFonts(): FontMgr = checkNotNull(FontMgr.default) { "FontMgr.default unavailable" }
