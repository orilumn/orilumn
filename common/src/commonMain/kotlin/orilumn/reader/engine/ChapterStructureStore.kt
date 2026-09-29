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

    fun sheetsFile(bookId: String): Path =
        dirFor(bookId).resolve(ChapterStructureCodec.SHEETS_FILENAME)

    /** Reads one chapter's import-built file (tree + sheets refs + structure). Null on miss or
     *  any staleness/corruption (v1 structure-only files included — recomputed on demand). */
    fun readChapter(bookId: String, chapterIndex: Int): PersistedChapterFile? {
        val f = file(bookId, chapterIndex)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: return null
        val file = ChapterStructureCodec.decodeFile(bytes) ?: return null
        if (file.chapterIndex != chapterIndex) return null
        return file
    }

    fun writeChapter(bookId: String, file: PersistedChapterFile) {
        val f = this.file(bookId, file.chapterIndex)
        runCatching { fs.createDirectories(f.parent ?: return) }
        runCatching { fs.write(f) { write(ChapterStructureCodec.encodeFile(file)) } }
    }

    /** Reads the book's deduped sheets (hash → flat CSS text). Null on miss/corruption. */
    fun readSheets(bookId: String): Map<Long, String>? {
        val f = sheetsFile(bookId)
        if (fs.metadataOrNull(f)?.isRegularFile != true) return null
        val bytes = runCatching { fs.read(f) { readByteArray() } }.getOrNull() ?: return null
        return ChapterStructureCodec.decodeSheets(bytes)
    }

    fun writeSheets(bookId: String, sheets: Map<Long, String>) {
        val f = sheetsFile(bookId)
        runCatching { fs.createDirectories(f.parent ?: return) }
        runCatching { fs.write(f) { write(ChapterStructureCodec.encodeSheets(sheets)) } }
    }

    /** Merges sheets into the book's map (import backfill / open-time saves converge here).
     *  Empty merges still write: the file's presence distinguishes "no sheets" from "never built". */
    fun mergeSheets(bookId: String, sheets: Map<Long, String>) {
        val merged = (readSheets(bookId) ?: emptyMap()) + sheets
        writeSheets(bookId, merged)
    }
}
