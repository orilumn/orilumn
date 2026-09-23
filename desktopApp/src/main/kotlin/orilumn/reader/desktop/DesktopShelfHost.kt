package orilumn.reader.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import orilumn.reader.data.epub.EpubParser
import orilumn.reader.data.epub.ZipEpubResourceReader
import orilumn.reader.ui.shelf.ScannedShelfBook
import orilumn.reader.ui.shelf.ShelfBook
import orilumn.reader.ui.shelf.ShelfHost
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File

/**
 * S32 桌面书架宿主：shared-ui [ShelfHost] 的 JVM 实现。
 *
 * - 导入：FileKit 原生对话框给 `PlatformFile.readBytes()` → 落临时文件走 KMP
 *   [ZipEpubResourceReader]+[EpubParser] 解析门控 → 拷贝进 `books/` 私有库；
 * - 封面：`parsed.cover` 原字节存 `covers/`，读取时 Skia 解码成 [ImageBitmap]
 *  （图片解码走 Skia，与正文绘制同栈；目标结构附录 A 口径）；
 * - 打开：空实现，窗口导航由 `Main` 经 `App.onOpenBook` 负责（与 S31 Android 壳同口径）。
 */
class DesktopShelfHost(
    private val store: DesktopShelfStore,
) : ShelfHost {

    override suspend fun scan(file: PlatformFile): ScannedShelfBook? = withContext(Dispatchers.IO) {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext null
        parseInTmp(bytes)?.let { ScannedShelfBook(it.title, it.author) }
    }

    override suspend fun import(file: PlatformFile, overwrite: Boolean): Boolean = withContext(Dispatchers.IO) {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@withContext false
        val scanned = parseInTmp(bytes) ?: return@withContext false
        if (overwrite) {
            store.allBooks()
                .firstOrNull { it.sameBookAs(scanned.title, scanned.author) }
                ?.let { store.delete(it.id) }
        }
        val target = File(DesktopPaths.booksDir, "book_${System.currentTimeMillis()}.epub")
            .also { it.parentFile.mkdirs() }
        runCatching { target.writeBytes(bytes) }.getOrNull() ?: return@withContext false
        val entry = store.addBook(scanned.title, scanned.author, target.absolutePath, coverPath = null)
        // 封面：解码门控（确认字节可解）后原样缓存，失败不影响导入。
        scanned.coverHref?.let { href ->
            val coverBytes = readInTmp(bytes, href)
            if (coverBytes != null && canDecode(coverBytes)) {
                val ext = href.substringAfterLast('.', "png").take(8).lowercase()
                    .filter { it.isLetterOrDigit() }.ifEmpty { "png" }
                val coverFile = File(DesktopPaths.coversDir, "cover_${entry.id}.$ext")
                    .also { it.parentFile.mkdirs() }
                if (runCatching { coverFile.writeBytes(coverBytes) }.isSuccess) {
                    store.updateCover(entry.id, coverFile.absolutePath)
                }
            }
        }
        true
    }

    override suspend fun loadCover(coverRef: String?): ImageBitmap? {
        if (coverRef == null) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(coverRef).takeIf { it.isFile }?.readBytes() ?: return@runCatching null
                Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
    }

    override fun openBook(book: ShelfBook) = Unit

    private data class Scanned(val title: String, val author: String?, val coverHref: String?)

    private fun parseInTmp(bytes: ByteArray): Scanned? {
        val tmp = File(DesktopPaths.cacheDir, "scan_${System.currentTimeMillis()}.epub")
        return try {
            tmp.parentFile.mkdirs()
            tmp.writeBytes(bytes)
            ZipEpubResourceReader(tmp.absolutePath).use { reader ->
                val parsed = EpubParser().parse(reader)
                if (parsed.isEmpty) return null
                Scanned(parsed.title, parsed.author, parsed.cover)
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun readInTmp(epubBytes: ByteArray, href: String): ByteArray? {
        val tmp = File(DesktopPaths.cacheDir, "cover_${System.currentTimeMillis()}.epub")
        return try {
            tmp.parentFile.mkdirs()
            tmp.writeBytes(epubBytes)
            ZipEpubResourceReader(tmp.absolutePath).use { it.readBytes(href) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { tmp.delete() }
        }
    }

    private fun canDecode(bytes: ByteArray): Boolean =
        runCatching { Image.makeFromEncoded(bytes).close(); true }.getOrDefault(false)
}
