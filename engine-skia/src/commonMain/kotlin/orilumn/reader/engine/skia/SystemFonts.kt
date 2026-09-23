package orilumn.reader.engine.skia

import orilumn.reader.data.font.SystemFontFace
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontSlant
import org.jetbrains.skia.FontStyle

/**
 * 系统字体集合的唯一来源 —— 母文档「架构分层与共享边界」§3 表「系统字体集合」边界的显式接缝
 * （D2：不再散落引擎内部）。所有 [FontCollection]/[org.jetbrains.skia.paragraph.ParagraphStyle]
 * 构造经此取平台系统字体。
 *
 * - jvm：macOS CoreText（skiko `FontMgr.default`）。
 * - android：Android 系统字体（skiko `FontMgr.default`）。
 *
 * 平台字体度量差异是能力边界（§3 说明）：同一 CSS、同一引擎，断行差只源自系统字体集合，
 * 不属工程分叉，不在共享范围内强制像素级。
 */
expect fun systemFonts(): FontMgr

/**
 * F1: 平台已装字体族名枚举（字体管理统一列表的系统侧来源；导入侧见
 * `orilumn.reader.data.font.FontFace`）。skiko `FontMgr` 自带族枚举
 * (`familiesCount`/`getFamilyName`)，两端跑同一段 common 代码，无需 expect/actual。
 * 去重 + 排序 + 去空；失败回空列表（调用方把系统区画空，不炸）。
 */
fun systemFontFamilies(): List<String> = runCatching {
    val mgr = systemFonts()
    val n = mgr.familiesCount.coerceAtLeast(0)
    (0 until n).mapNotNull { i ->
        runCatching { mgr.getFamilyName(i) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }.distinct().sorted()
}.getOrDefault(emptyList())

/**
 * F 系列：系统字形（族 + 字重名）枚举 —— [systemFontFamilies] 的加细版。
 *
 * 族源复用已验证的 [systemFontFamilies]（失败回空）；逐族展开 skiko `FontStyleSet`：
 * `getStyleName` 取 nameID=2 真名（如 Regular/Bold/Light），缺名回退 [styleNameOf]
 * （按 weight/slant 推算展示名）；单族解析失败只回落"无字重名"单行，**不丢族**。
 */
fun systemFontFaces(): List<SystemFontFace> {
    val families = runCatching { systemFontFamilies() }.getOrDefault(emptyList())
    if (families.isEmpty()) return emptyList()
    val mgr = runCatching { systemFonts() }.getOrNull() ?: return families.map { SystemFontFace(it) }
    return buildList {
        for (family in families) {
            val styles = styleNamesOfFamily(mgr, family)
            if (styles.isEmpty()) add(SystemFontFace(family))
            else styles.forEach { add(SystemFontFace(family, it)) }
        }
    }
}

/** 单族的字重名列表；解析不出回空（调用方落到"无字重名"单行）。 */
private fun styleNamesOfFamily(mgr: FontMgr, family: String): List<String> = runCatching {
    val set = mgr.matchFamily(family) ?: return@runCatching emptyList()
    (0 until set.count().coerceAtLeast(0)).mapNotNull { j ->
        runCatching {
            set.getStyleName(j)?.trim().orEmpty().ifBlank { styleNameOf(set.getStyle(j)) }
                .takeIf { it.isNotEmpty() }
        }.getOrNull()
    }.distinct()
}.getOrDefault(emptyList())

/** 数字字重 + 倾斜 -> 展示名（`getStyleName` 缺名的回退路径）。 */
private fun styleNameOf(s: FontStyle): String = buildString {
    when {
        s.weight < 300 -> append("Thin")
        s.weight < 375 -> append("Light")
        s.weight < 475 -> append("Regular")
        s.weight < 575 -> append("Medium")
        s.weight < 675 -> append("SemiBold")
        s.weight < 825 -> append("Bold")
        else -> append("Black")
    }
    if (s.slant != FontSlant.UPRIGHT) append(" Italic")
}