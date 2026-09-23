package orilumn.reader.data.epub

import okio.FileSystem
import okio.Path.Companion.toPath
import orilumn.reader.time.platformNowMs

/**
 * Q1-8 收敛：EPUB 字节的 tmp-parse 模板单源。
 *
 * [ZipEpubResourceReader] 是文件路径制的（okio KMP zip），字节需先落临时文件再读；
 * 平板 `BookImporter.scanBytes/importBytes` 与桌面 `DesktopShelfHost.parseInTmp/readInTmp`
 * 是同一模板（写 tmp→解析门控→删 tmp），收敛至此。调用方只给平台缓存目录与字节，
 * IO 调度（withContext）与落库仍归调用方。
 */
/** 解析字节为书目（门控：无可读正文章节回 null；异常回 null，tmp 必删）。 */
fun parseEpubBytes(bytes: ByteArray, tmpDirPath: String): EpubBook? {
    val fs = FileSystem.SYSTEM
    val tmp = tmpDirPath.toPath() / "scan_${platformNowMs()}.epub"
    return try {
        fs.createDirectories(tmpDirPath.toPath())
        fs.write(tmp) { write(bytes) }
        ZipEpubResourceReader(tmp.toString()).use { EpubParser().parse(it) }.takeUnless { it.isEmpty }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { fs.delete(tmp) }
    }
}

/** 从字节中直读 zip 内某条目（封面等；异常回 null，tmp 必删）。 */
fun readEpubEntry(bytes: ByteArray, tmpDirPath: String, href: String): ByteArray? {
    val fs = FileSystem.SYSTEM
    val tmp = tmpDirPath.toPath() / "entry_${platformNowMs()}.epub"
    return try {
        fs.createDirectories(tmpDirPath.toPath())
        fs.write(tmp) { write(bytes) }
        ZipEpubResourceReader(tmp.toString()).use { it.readBytes(href) }
    } catch (_: Exception) {
        null
    } finally {
        runCatching { fs.delete(tmp) }
    }
}
