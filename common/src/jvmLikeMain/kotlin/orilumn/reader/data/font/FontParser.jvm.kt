package orilumn.reader.data.font

import java.io.File
import java.io.InputStream

/**
 * JVM-only convenience overloads for [FontParser] (Android + desktop): read and parse from a
 * [File] / [InputStream]. The core parse logic lives in commonMain and only accepts [ByteArray].
 */
fun FontParser.parse(file: File): FontParser.Result =
    runCatching { file.readBytes().let { parse(it) } }.getOrElse { FontParser.Result.INVALID }

fun FontParser.parse(stream: InputStream): FontParser.Result =
    runCatching { stream.use { it.readBytes().let { bytes -> parse(bytes) } } }.getOrElse { FontParser.Result.INVALID }