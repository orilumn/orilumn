package orilumn.reader.data.epub

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import okio.openZip
import okio.use

/**
 * Abstract EPUB resource reader, hiding the underlying access method (zip / directory / network etc.).
 *
 * The parser only depends on this interface, so it becomes pure logic unit-testable on the JVM.
 * All paths are normalized via [normalizePath] to lowercase before indexing/lookup, tolerating
 * inconsistent casing in "dirty" books.
 */
interface EpubResourceReader : AutoCloseable {
    /** All entry paths (already normalized). */
    fun entries(): Sequence<String>

    /** Read a text entry; null when absent. */
    fun readText(path: String): String?

    /** Read a binary entry (image/font); null when absent. */
    fun readBytes(path: String): ByteArray?

    /**
     * 这份资源的**身份**（路径 + 体积 + 最后修改时间之类），供「按资源内容缓存解析结果」的调用方
     * 判定缓存还认不认。默认 null = 无法判定 ⇒ 调用方不得缓存（宁可不缓存，不可用旧骨架开新书）。
     *
     * 只描述"哪份文件"，不读内容：算它必须远比重解析便宜。
     */
    fun identity(): String? = null

    /** Unified path normalization: backslashes to forward slashes, strip leading/trailing slashes, lowercase. */
    fun normalizePath(p: String): String = p.replace('\\', '/').trim('/').lowercase()

    /**
     * Resolves a potentially-relative href (e.g. a stylesheet `<link href>`) against the directory of
     * [basePath] and collapses `..`/`.` segments. Returned path keeps the original casing (lookups are
     * case-insensitive via readText), and is relative to the archive root — suitable to pass to
     * [readText].
     */
    fun resolveRelative(basePath: String, relative: String): String {
        val baseDir = basePath.substringBeforeLast('/', "")
        val joined = if (baseDir.isEmpty()) relative else "$baseDir/$relative"
        return collapsePath(joined)
    }

    /** Collapses `.` and `..` segments in a `/`-separated relative path (backslashes → `/`). */
    fun collapsePath(p: String): String {
        val parts = ArrayDeque<String>()
        for (seg in p.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    override fun close() {}
}

/**
 * The [EpubResourceReader] implementation based on a zip file (okio's KMP readable zip file system).
 *
 * An EPUB is essentially a zip package; this implementation lowercases entry paths for indexing and
 * looks up with lowercase keys, thus supporting both spec paths (`META-INF/container.xml`) and the
 * common "original-file case drift" dirty books. Note: cannot look up the entry path directly (it is
 * case-sensitive; looking up a lowercase name would miss uppercase entries).
 */
class ZipEpubResourceReader(path: String) : EpubResourceReader {
    /** 书文件路径（[identity] 用；也保留给日志）。 */
    private val filePath: String = path

    /** Read-only file system view over the zip archive (JVM/Android both include the zip variant). */
    private val zip: FileSystem = FileSystem.SYSTEM.openZip(path.toPath())

    private val root: Path = "/".toPath()

    /** Normalized (lowercase) path → real entry. The key to case-insensitive lookup. */
    private val normalized: Map<String, Path> by lazy {
        val m = HashMap<String, Path>()
        // listRecursively yields every entry plus synthesized parent directories; keep plain files only.
        zip.listRecursively(root).forEach { p ->
            if (zip.metadataOrNull(p)?.isDirectory != true) {
                m[normalizePath(p.toString())] = p
            }
        }
        m
    }

    override fun entries(): Sequence<String> = normalized.keys.asSequence()

    override fun readText(path: String): String? =
        readBytes(path)?.toString(Charsets.UTF_8)

    /**
     * 路径 + 体积 + 最后修改时间：书被重新导入/替换/同名覆盖时三者至少变一项，
     * 旧骨架（书脊 + 目录）随之作废。取不到元数据（文件丢了/无权限）⇒ null（不缓存）。
     */
    override fun identity(): String? {
        val meta = runCatching { FileSystem.SYSTEM.metadataOrNull(filePath.toPath()) }.getOrNull() ?: return null
        val size = meta.size ?: return null
        return "$filePath|$size|${meta.lastModifiedAtMillis ?: 0L}"
    }

    override fun readBytes(path: String): ByteArray? {
        val entry = normalized[normalizePath(path)] ?: return null
        return zip.source(entry).buffer().use { it.readByteArray() }
    }
}