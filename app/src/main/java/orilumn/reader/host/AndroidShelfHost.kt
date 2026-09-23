package orilumn.reader.host

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import orilumn.reader.data.book.BookImporter
import orilumn.reader.data.book.BookRepository
import orilumn.reader.ui.shelf.ScannedShelfBook
import orilumn.reader.ui.shelf.ShelfBook
import orilumn.reader.ui.shelf.ShelfHost
import orilumn.reader.ui.shelf.ShelfRepository
import orilumn.reader.ui.shelf.ShelfSort
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

/**
 * S31 壳适配层：把 app 现有 Room/BookImporter 能力收敛到 shared-ui 的书架协议上。
 *
 * 收敛口径（对齐目标结构「通用方案优先」）：
 * - 文件选择走 shared-ui 内 mpfilepicker（FileKit）：本宿主只收 `PlatformFile.readBytes()`，
 *   不再持有 SAF `OpenMultipleDocuments` launcher（旧 MainActivity 的 picker 逻辑随瘦身删除）；
 * - 查重/覆盖语义与旧 `MainActivity.onBooksPicked/onOverwriteConfirm` 一致（逐本 scan、
 *   重复聚合、覆盖先删旧书）；
 * - 封面解码走 skia（`ImageCodec`），壳只做 skia → Compose `ImageBitmap` 桥（E1 后）；
 * - 打开书籍是壳导航（`openBook` 默认空实现，`MainActivity` 经 `App.onOpenBook` 起
 *   `ReaderActivity`——阅读面 engine/卷曲仍是 Android 专属实现，留壳）。
 */
class AndroidShelfRepository(private val delegate: BookRepository) : ShelfRepository {

    override fun books(sort: ShelfSort): Flow<List<ShelfBook>> =
        delegate.books(sort.toApp()).map { list -> list.map { it.toShelf() } }

    override suspend fun allBooks(): List<ShelfBook> =
        delegate.allBooks().map { it.toShelf() }

    override suspend fun delete(id: Long) {
        delegate.getBook(id)?.let { delegate.removeBook(it) }
    }

    override suspend fun touchRead(id: Long) {
        delegate.touchRead(id)
    }

    private fun ShelfSort.toApp(): BookRepository.Sort = when (this) {
        ShelfSort.Added -> BookRepository.Sort.Added
        ShelfSort.Read -> BookRepository.Sort.Read
        ShelfSort.Name -> BookRepository.Sort.Name
    }

    private fun orilumn.reader.data.book.Book.toShelf(): ShelfBook = ShelfBook(
        id = id,
        title = title,
        author = author,
        filePath = filePath,
        addedAt = addedAt,
        coverRef = coverPath,
        readTime = readTime,
    )
}

class AndroidShelfHost(
    private val context: Context,
    private val repository: BookRepository,
    private val importer: BookImporter,
) : ShelfHost {

    override suspend fun scan(file: PlatformFile): ScannedShelfBook? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val s = importer.scanBytes(bytes) ?: return null
        return ScannedShelfBook(title = s.title, author = s.author)
    }

    override suspend fun import(file: PlatformFile, overwrite: Boolean): Boolean {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (overwrite) {
            // 覆盖语义：先删去同名旧书（记录 + 文件 + 封面），再导入新副本。
            val scanned = importer.scanBytes(bytes)
            if (scanned != null) {
                repository.allBooks()
                    .firstOrNull { it.sameBookAs(scanned.title, scanned.author) }
                    ?.let { old ->
                        runCatching { File(old.filePath).delete() }
                        old.coverPath?.let { runCatching { File(it).delete() } }
                        repository.removeBook(old)
                    }
            }
        }
        return importer.importBytes(bytes).isSuccess
    }

    override suspend fun loadCover(coverRef: String?): ImageBitmap? {
        if (coverRef == null) return null
        return withContext(Dispatchers.IO) {
            // Q1-6：封面字节直解走共享接缝（旧 PNG 单跳桥随退役管线删除）。
            runCatching {
                val bytes = java.io.File(coverRef).takeIf { it.isFile }?.readBytes()
                    ?: return@runCatching null
                orilumn.reader.ui.imageBitmapOf(bytes)
            }.getOrNull()
        }
    }

    /** 打开书籍：默认空实现，实际导航由 `MainActivity` 经 `App.onOpenBook` 注入。 */
    override fun openBook(book: ShelfBook) = Unit
}
