package orilumn.reader.engine.skia

import orilumn.reader.data.font.FontClassifier
import orilumn.reader.data.font.FontParser

/**
 * **系统族语种判定（共享，全平台同一把尺子）**。
 *
 * 系统字体没有文件字节可解析，但 skiko 的 [org.jetbrains.skia.Typeface.getTableData] 能单独取出
 * `cmap` 表 —— 对族内首个取得到 cmap 的字面跑 [FontParser.cmapCoverage]，再交给 [FontClassifier]，
 * 与导入字体走**同一段计数、同一组阈值**（判据单源在 `:common`，不按平台各写一套语言/字体清单）。
 *
 * 判不出来（平台不给 cmap 表）回 [FontClassifier.LANG_INVALID]：**未知不等于「没有中文/英文」**，
 * 调用方据此保留该族，绝不因平台能力缺失而丢字体。
 *
 * 进程内缓存：系统字体集合在进程生命周期内视为不变（与扫描指纹口径一致）。
 */
fun systemFamilyLang(family: String): String {
    synchronized(familyLangCache) { familyLangCache[family] }?.let { return it }
    return probeFamilyLang(family).also { lang ->
        synchronized(familyLangCache) { familyLangCache[family] = lang }
    }
}

private val familyLangCache = HashMap<String, String>()

/** 纯字节解析器（无状态）；[FontParser.cmapCoverage] 是实例方法。 */
private val fontParser = FontParser()

private fun probeFamilyLang(family: String): String {
    val name = family.trim()
    if (name.isEmpty()) return FontClassifier.LANG_INVALID
    val mgr = runCatching { systemFonts() }.getOrNull() ?: return FontClassifier.LANG_INVALID
    val set = runCatching { mgr.matchFamily(name) }.getOrNull() ?: return FontClassifier.LANG_INVALID
    return set.use { styles ->
        for (i in 0 until styles.count().coerceAtLeast(0)) {
            val coverage = runCatching {
                styles.getTypeface(i)?.use { tf ->
                    tf.getTableData("cmap")?.use { data ->
                        if (data.bytes.isEmpty()) null else fontParser.cmapCoverage(data.bytes)
                    }
                }
            }.getOrNull()
            if (coverage != null) {
                return@use FontClassifier.classify(coverage.cjk, coverage.latin)
            }
        }
        FontClassifier.LANG_INVALID
    }
}
