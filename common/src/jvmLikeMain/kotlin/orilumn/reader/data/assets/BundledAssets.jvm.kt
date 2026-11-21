package orilumn.reader.data.assets

import kotlin.text.Charsets

/**
 * jvmLikeMain actual —— 一套实现服务 Android + 桌面（与 `ReaderStylesheets` 同一接缝）。
 * 资源经 classloader 从 `common/src/commonMain/resources/text/` 取。
 */
actual fun readBundledText(name: String): String? =
    BundledAssets::class.java.classLoader
        ?.getResourceAsStream("text/$name")
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }
