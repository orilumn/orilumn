package orilumn.reader.data.epub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * 开书骨架（书名/作者 + 书脊 + 目录 + 封面路径）落盘。
 *
 * **为什么存**：`EpubParser.parse` 是开书路径上的第一笔活——读 `META-INF/container.xml`，
 * 读并解析整份 OPF（手册类书 160KB 级），再读目录（EPUB3 nav 或 EPUB2 NCX）并把目录项
 * 映回书脊下标。它产出的东西**只随书文件本身变化**（与字号、版心、主题全都无关），
 * 但每次开书都要重算一遍。真机（GIMP 手册，29 章）量到 `parse≈950ms`。
 *
 * **存放在哪**：`<cache>/structure/book_<id>/index.json`——与章节结构缓存同一个按书目录，
 * 删书时整目录删除即可一并清掉，不产生第二套孤儿目录。
 *
 * **失效判据**：格式版本（[INDEX_VERSION]）+ 构建号 + 资源身份（[EpubResourceReader.identity]，
 * 即书文件路径/体积/修改时间）+ 书脊非空。任一不符一律当 miss：重新解析并覆盖，
 * 绝不"修补一半"。（读坏了同样当 miss——文件是纯缓存，随时可重建。）
 */
class EpubBookStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
    root: Path,
) {
    private val rootDir: Path = root

    fun file(bookId: Long): Path =
        rootDir.resolve("structure/book_$bookId").resolve(FILENAME)

    /** 读回可用骨架；任何不认得的状况（缺文件/坏文件/版本或身份不符/空书脊）都回 null。 */
    fun read(bookId: Long, identity: String?, appVersion: Int): EpubBook? {
        if (bookId < 0 || identity == null) return null
        val f = file(bookId)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: return null
        val record = runCatching {
            Json.decodeFromString(PersistedBookIndex.serializer(), bytes.decodeToString())
        }.getOrNull() ?: return null
        if (record.version != INDEX_VERSION) return null
        if (record.appVersion != appVersion) return null
        if (record.identity != identity) return null
        if (record.book.spine.isEmpty()) return null
        return record.book
    }

    /** 写入骨架（临时文件 + 原子改名；写失败只当"没存"，调用方照常用刚解析的结果）。 */
    fun write(bookId: Long, identity: String?, appVersion: Int, book: EpubBook) {
        if (bookId < 0 || identity == null || book.spine.isEmpty()) return
        val f = file(bookId)
        val dir = f.parent ?: return
        val text = runCatching {
            Json.encodeToString(
                PersistedBookIndex.serializer(),
                PersistedBookIndex(INDEX_VERSION, appVersion, identity, book),
            )
        }.getOrNull() ?: return
        val bytes = text.encodeToByteArray()
        runCatching {
            fs.createDirectories(dir)
            val tmp = dir.resolve(TMP_FILENAME)
            fs.write(tmp) { write(bytes) }
            // 与页面快照同款：atomicMove 不可用时退一步"直接覆盖"，半份文件读回即 miss。
            runCatching { fs.atomicMove(tmp, f) }
                .onFailure { fs.write(f) { write(bytes) }; runCatching { fs.delete(tmp) } }
        }
    }

    companion object {
        const val FILENAME = "index.json"
        private const val TMP_FILENAME = "index.tmp"

        /**
         * 骨架格式版本：`EpubParser` 的**产出结构**变了就 +1（新增字段有默认值可不加，
         * 但改了字段含义/映射规则必须加，否则老文件会带着旧目录一直命中）。
         */
        const val INDEX_VERSION = 1
    }
}

@Serializable
private data class PersistedBookIndex(
    val version: Int,
    val appVersion: Int,
    val identity: String,
    val book: EpubBook,
)
