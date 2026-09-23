package orilumn.reader.data.font

/**
 * 字体池条目：用户导入字体 + 系统字体（F 系列起两者同列展示，见 `docs/F-系统字体统一方案.md`）。
 *
 * S34b 从 `app` 迁入 common（去 Room 注解；见 [orilumn.reader.data.db.LibraryDb] 字体 API）。
 *
 * @param source "imported"（字节存于 [path] 私有副本，经虚域 url(`/fonts/{id}`) 加载）
 *  或 "system"（本机已装字体，不存字节，[path] 为 null，排版/绘制走平台系统字体集合）。
 * @param hidden 用户手动隐藏（系统/导入通用；隐藏后不进选择器候选；被引用槽位回退"跟随原书"）。
 * @param subfamily 字重/风格（nameID=2，如 Regular/Bold/509R）。**空串表示"无独立字重名"**；
 *  同一家族的不同字重文件以 (familyName, subfamily) 共存，渲染时提供多条 @font-face
 *  （同家族名 + 各自字重/风格描述）。系统条目字重名由平台枚举
 *  （engine-skia `systemFontFaces`）落行，空 = 该族无细分字重。
 * @param lang [FontClassifier] 分类结果（cjk/latin/generic）；symbol/invalid 不做候选。
 *  系统条目 lang 为空（不过滤）。
 */
data class FontFace(
    val id: Long = 0,
    val familyName: String,
    val displayName: String,
    /** 字重/风格（nameID=2，如 Regular/Bold/509R）；空串 = 无字重名。同家族多字重文件逐个保留，渲染按字重分组。 */
    val subfamily: String = "",
    val source: String = SOURCE_IMPORTED,
    val path: String?,
    val lang: String,
    val hidden: Boolean = false,
) {
    companion object {
        const val SOURCE_IMPORTED = "imported"
        const val SOURCE_SYSTEM = "system"
    }
}
