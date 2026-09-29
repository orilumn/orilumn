package orilumn.reader.engine

import okio.Buffer
import orilumn.reader.engine.text.Crc32
import orilumn.reader.io.Logger

/**
 * Persisted per-chapter block-structure payload (import-time build, open-time relink).
 *
 * The expensive part of chapter preparation is the per-element display cascade
 * (`computeStructure`: leaf enumeration + char starts + background/avoid owners + generated
 * strings, ~78% of a book's cold-prepare cost on the measured sample). Its output is
 * typography- and viewport-independent — the cascade properties it consumes (display,
 * white-space, background, break-inside, visibility) come from author/UA sheets only — EXCEPT
 * when author CSS uses `@media` (evaluated against the viewport). Hence:
 *
 *  - Media-free chapters (no `@media`, no media-conditioned `@import` in any CSS text) get their
 *    structure computed once at import and persisted here; open re-parses the (cheap) XML tree and
 *    relinks node identities by root→leaf child-index paths, skipping the cascade entirely.
 *  - Chapters with media-affected CSS never get a file; the runtime path is unchanged.
 *
 * Nodes are referenced by child-index paths from the chapter root (post-[ChapterPreprocessor]
 * tree — the importer and the opener must run the same preprocess; any relink failure falls back
 * to computing). Text is never stored: the tree is always re-parsed at open, so this payload is
 * small (paths + starts + owners ≈ hundreds of KB per book, not MBs).
 *
 * **Invalidation.** [decode] is the single authority: magic/schema/[STRUCTURE_VERSION] mismatch,
 * chapter-index mismatch, [cssHash] mismatch (author CSS changed), or any malformed/truncated
 * content → null → the caller computes (and overwrites). Filenames carry no key
 * (`<chapterIndex>.bin`), so stale content can never be addressed — it is overwritten in place.
 */
object ChapterStructureCodec {

    private const val MAGIC = 0x43485354 // "CHST"

    /** File/layout version. Bump only when these bytes change shape. */
    private const val VERSION = 1

    /**
     * Cascade-semantics version. Bump on ANY change to what the structure means: the converter
     * (tree shape), [ChapterPreprocessor], the display/hidden/caption predicates, char-advance
     * normalization, background/avoid attribution, generated-content strings, or any UA/theme/UI
     * sheet rule touching a structure-consumed property (display/white-space/background/
     * break-inside/visibility). Same manual discipline as `PaginationCacheCodec.LAYOUT_VERSION`.
     */
    const val STRUCTURE_VERSION = 1

    /** Filename convention: `<chapterIndex>.bin` (keys live inside the content, not the name). */
    fun filename(chapterIndex: Int): String = "$chapterIndex.bin"

    /** Stable hash over a chapter's raw author CSS texts (embedded + linked, pre-parse). */
    fun cssHashOf(cssTexts: List<String>): Long {
        var crc = Crc32.INIT
        for (t in cssTexts) {
            crc = Crc32.updateString(crc, t)
            crc = Crc32.update4(crc, 0x1FL) // separator: ["a","bc"] ≠ ["ab","c"]
        }
        return Crc32.finish(crc)
    }

    fun encode(p: PersistedChapterStructure): ByteArray {
        val buf = Buffer()
        buf.writeInt(MAGIC)
        buf.writeInt(VERSION)
        buf.writeInt(STRUCTURE_VERSION)
        buf.writeInt(p.chapterIndex)
        buf.writeLong(p.cssHash)
        buf.writeInt(p.leafPaths.size)
        for (i in p.leafPaths.indices) {
            writeInts(buf, p.leafPaths[i])
            buf.writeLong(p.charStarts[i])
        }
        buf.writeInt(p.bgOwners.size)
        for ((leafIdx, path) in p.bgOwners) {
            buf.writeInt(leafIdx)
            writeInts(buf, path)
        }
        buf.writeInt(p.avoidOwners.size)
        for ((leafIdx, path) in p.avoidOwners) {
            buf.writeInt(leafIdx)
            writeInts(buf, path)
        }
        buf.writeInt(p.genStrings.size)
        for ((leafIdx, pair) in p.genStrings) {
            buf.writeInt(leafIdx)
            writeNullableString(buf, pair.first)
            writeNullableString(buf, pair.second)
        }
        return buf.readByteArray()
    }

    fun decode(bytes: ByteArray): PersistedChapterStructure? {
        if (bytes.isEmpty()) return null
        return try {
            val buf = Buffer().write(bytes)
            if (buf.readInt() != MAGIC) return decodeNull("magic")
            if (buf.readInt() != VERSION) return decodeNull("version")
            if (buf.readInt() != STRUCTURE_VERSION) return decodeNull("structure-version")
            val chapterIndex = buf.readInt()
            val cssHash = buf.readLong()
            val leafCount = buf.readInt()
            if (leafCount < 0 || leafCount > 100_000) return decodeNull("leafCount=$leafCount")
            val leafPaths = ArrayList<IntArray>(leafCount)
            val charStarts = LongArray(leafCount)
            repeat(leafCount) { i ->
                leafPaths.add(readInts(buf) ?: return decodeNull("leafPath"))
                charStarts[i] = buf.readLong()
            }
            val bgOwners = readOwnerMap(buf) ?: return decodeNull("bgOwners")
            val avoidOwners = readOwnerMap(buf) ?: return decodeNull("avoidOwners")
            val genCount = buf.readInt()
            if (genCount < 0 || genCount > 100_000) return decodeNull("genCount=$genCount")
            val genStrings = HashMap<Int, Pair<String?, String?>>(genCount)
            repeat(genCount) {
                val leafIdx = buf.readInt()
                if (leafIdx < 0 || leafIdx >= leafCount) return decodeNull("genLeafIdx=$leafIdx")
                genStrings[leafIdx] = readNullableString(buf) to readNullableString(buf)
            }
            if (!buf.exhausted()) return decodeNull("trailing-bytes")
            PersistedChapterStructure(chapterIndex, cssHash, leafPaths, charStarts, bgOwners, avoidOwners, genStrings)
        } catch (e: Exception) {
            decodeNull("exception ${e.message}")
        }
    }

    private fun writeInts(buf: Buffer, v: IntArray) {
        buf.writeInt(v.size)
        for (x in v) buf.writeInt(x)
    }

    private fun readInts(buf: Buffer): IntArray? {
        val n = buf.readInt()
        if (n < 0 || n > 64) return null // tree depth sanity cap
        return IntArray(n) { buf.readInt() }
    }

    private fun readOwnerMap(buf: Buffer): Map<Int, IntArray>? {
        val n = buf.readInt()
        if (n < 0 || n > 100_000) return null
        val out = HashMap<Int, IntArray>(n)
        repeat(n) {
            val leafIdx = buf.readInt()
            val path = readInts(buf) ?: return null
            out[leafIdx] = path
        }
        return out
    }

    private fun writeNullableString(buf: Buffer, s: String?) {
        if (s == null) {
            buf.writeInt(-1)
        } else {
            val bytes = s.encodeToByteArray()
            buf.writeInt(bytes.size)
            buf.write(bytes)
        }
    }

    private fun readNullableString(buf: Buffer): String? {
        val n = buf.readInt()
        if (n < 0) return null
        if (n > 1_000_000) throw IllegalArgumentException("stringLen=$n")
        return buf.readByteArray(n.toLong()).decodeToString()
    }

    private fun decodeNull(reason: String): PersistedChapterStructure? {
        Logger.w("Orilumn.DISK", "chapter-structure decode null ($reason)")
        return null
    }
}

/**
 * Import-built, open-relinked block structure for one chapter. All node references are
 * child-index paths from the chapter root ([leafPaths]); [bgOwners]/[avoidOwners]/[genStrings]
 * key leaves by index into [leafPaths]. Parallel arrays: `charStarts[i]` belongs to `leafPaths[i]`.
 */
data class PersistedChapterStructure(
    val chapterIndex: Int,
    val cssHash: Long,
    val leafPaths: List<IntArray>,
    val charStarts: LongArray,
    val bgOwners: Map<Int, IntArray>,
    val avoidOwners: Map<Int, IntArray>,
    val genStrings: Map<Int, Pair<String?, String?>>,
)
