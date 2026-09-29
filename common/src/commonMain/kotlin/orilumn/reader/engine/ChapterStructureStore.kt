package orilumn.reader.engine

import okio.FileSystem
import okio.Path

/**
 * Shared chapter-structure disk store (import-time build, open-time relink):
 * [PersistedChapterStructure] blobs over an injected okio [FileSystem], mirroring
 * [PaginationCacheStore] conventions.
 *
 * Layout: `<root>/structure/book_<bookId>/<chapterIndex>.bin`. Keys live inside the content
 * ([ChapterStructureCodec] validates chapter/css/versions on read), so files are overwritten in
 * place and no orphan sweep is ever needed — at most one file per chapter exists.
 *
 * @param fs okio filesystem (production: `FileSystem.SYSTEM`; tests: a temp-dir-backed SYSTEM).
 * @param root cache root directory.
 */
class ChapterStructureStore(
    private val fs: FileSystem = FileSystem.SYSTEM,
    root: Path,
) {
    private val rootDir: Path = root

    fun dirFor(bookId: String): Path =
        rootDir.resolve("structure/$bookId").also {
            runCatching { fs.createDirectories(it) }
        }

    fun file(bookId: String, chapterIndex: Int): Path =
        dirFor(bookId).resolve(ChapterStructureCodec.filename(chapterIndex))

    /** Reads a chapter's persisted structure. Null on miss or any staleness/corruption. */
    fun read(bookId: String, chapterIndex: Int): PersistedChapterStructure? {
        val f = file(bookId, chapterIndex)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: return null
        val p = ChapterStructureCodec.decode(bytes) ?: return null
        if (p.chapterIndex != chapterIndex) return null
        return p
    }

    fun write(bookId: String, payload: PersistedChapterStructure) {
        val f = file(bookId, payload.chapterIndex)
        runCatching { fs.createDirectories(f.parent ?: return) }
        runCatching { fs.write(f) { write(ChapterStructureCodec.encode(payload)) } }
    }
}
