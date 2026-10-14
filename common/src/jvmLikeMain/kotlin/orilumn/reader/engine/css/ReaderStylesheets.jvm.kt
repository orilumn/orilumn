package orilumn.reader.engine.css

import kotlin.text.Charsets

/**
 * Z1 actual —— 一套 jvmLikeMain actual 服务 android + desktop 两个 jvmLike 平台（镜像
 * PlatformIo.kt 的共享 actual 接缝；两个目标各自 dependsOn jvmLikeMain，android 复用同一文件）。
 * 资源经 classloader 从 `common/src/commonMain/resources/css/` 取（JAR/AGP 库 java resources）。
 */
actual fun readReaderCss(name: String): String? =
    ReaderStylesheets::class.java.classLoader
        ?.getResourceAsStream("css/$name")
        ?.bufferedReader(Charsets.UTF_8)
        ?.use { it.readText() }