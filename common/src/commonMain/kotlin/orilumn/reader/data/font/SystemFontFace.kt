package orilumn.reader.data.font

/**
 * 系统字体面（平台枚举结果）：族名 + 字重/风格名（如 Regular/Bold）。
 *
 * engine-skia `systemFontFaces()`（skiko `FontMgr.matchFamily` 的 `FontStyleSet` 展开）产出，
 * [FontLibrary.syncSystemFaces] 落行后与导入同列展示；同一族的多字重以
 * (family, subfamily) 共存，列表按族合并成一行、副标题出字重表。
 *
 * @param family 系统族名（逻辑键，槽位/取字形仍用它）。
 * @param subfamily 字重/风格名（nameID=2 语义，如 Regular/Bold/Black Italic）。
 *  空串 = 该族无细分字重（单个样式不细分，展示层按"无字重名"口径处理）。
 */
data class SystemFontFace(
    val family: String,
    val subfamily: String = "",
)