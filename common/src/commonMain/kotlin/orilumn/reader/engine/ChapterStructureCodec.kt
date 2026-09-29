package orilumn.reader.engine

import okio.Buffer
import orilumn.reader.engine.html.MarkupElement
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

    /** Book-level deduped sheets file (hash → flat CSS text, all media-free chapters share it). */
    const val SHEETS_FILENAME = "_sheets.bin"

    /**
     * Tree-semantics version. Bump on ANY change to what the persisted tree means: the converter
     * (node shape/attrs/text), [ChapterPreprocessor] (which tree gets persisted), or the
     * post-process contract the relink paths assume. Same manual discipline as [STRUCTURE_VERSION].
     */
    const val TREE_VERSION = 1

    /** Stable hash over a chapter's flattened CSS texts (`CssBundle.cssTexts`, post-`@import`
     *  resolution — the exact list sheets are parsed from and structures are keyed on). */
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

    // ── v2 chapter file: post-process tree + sheet refs + nested v1 structure ──
    //
    // Layout: [MAGIC][VERSION=2][TREE_VERSION][chapterIndex][cssHash]
    //         [sheetCount][sheetHash…][baseHrefCount][baseHref…]
    //         [tree nodes pre-order][v1-structure-bytes]
    // The nested v1 section reuses [encode]/[decode] verbatim (its own header re-validates magic,
    // schema, cascade semantics, chapter and cssHash). v1 files (VERSION=1 right after MAGIC) are
    // rejected at the version gate and recomputed.

    private const val VERSION_V2 = 2
    private const val MAX_TREE_NODES = 500_000
    private const val MAX_TREE_DEPTH = 256
    private const val MAX_TAG_LEN = 256
    private const val MAX_ATTR_KEY_LEN = 256
    private const val MAX_ATTR_VALUE_LEN = 64 * 1024
    private const val MAX_TEXT_LEN = 16 * 1024 * 1024
    private const val MAX_ATTRS = 1024
    private const val MAX_SHEETS_PER_BOOK = 100_000
    private const val MAX_SHEET_LEN = 2 * 1024 * 1024

    fun encodeFile(f: PersistedChapterFile): ByteArray {
        val buf = Buffer()
        buf.writeInt(MAGIC)
        buf.writeInt(VERSION_V2)
        buf.writeInt(TREE_VERSION)
        buf.writeInt(f.chapterIndex)
        buf.writeLong(f.cssHash)
        buf.writeInt(f.sheetHashes.size)
        for (h in f.sheetHashes) buf.writeLong(h)
        buf.writeInt(f.baseHrefs.size)
        for (h in f.baseHrefs) writeUtf(buf, h, MAX_ATTR_VALUE_LEN)
        writeTree(buf, f.tree, 0)
        buf.write(encode(f.structure))
        return buf.readByteArray()
    }

    fun decodeFile(bytes: ByteArray): PersistedChapterFile? {
        if (bytes.isEmpty()) return null
        return try {
            val buf = Buffer().write(bytes)
            if (buf.readInt() != MAGIC) return fileNull("magic")
            if (buf.readInt() != VERSION_V2) return fileNull("version")
            if (buf.readInt() != TREE_VERSION) return fileNull("tree-version")
            val chapterIndex = buf.readInt()
            val cssHash = buf.readLong()
            val sheetCount = buf.readInt()
            if (sheetCount < 0 || sheetCount > MAX_SHEETS_PER_BOOK) return fileNull("sheetCount=$sheetCount")
            val sheetHashes = LongArray(sheetCount) { buf.readLong() }.toList()
            val hrefCount = buf.readInt()
            if (hrefCount < 0 || hrefCount > MAX_SHEETS_PER_BOOK) return fileNull("hrefCount=$hrefCount")
            if (hrefCount != sheetCount) return fileNull("sheets-hrefs-mismatch")
            val baseHrefs = ArrayList<String>(hrefCount)
            repeat(hrefCount) { baseHrefs.add(readUtf(buf, MAX_ATTR_VALUE_LEN) ?: return fileNull("baseHref")) }
            val tree = readTree(buf, 0) ?: return fileNull("tree")
            val tail = buf.readByteArray()
            val structure = decode(tail) ?: return fileNull("structure")
            if (structure.chapterIndex != chapterIndex || structure.cssHash != cssHash) {
                return fileNull("structure-head-mismatch")
            }
            if (!buf.exhausted()) return fileNull("trailing-bytes")
            PersistedChapterFile(chapterIndex, cssHash, sheetHashes, baseHrefs, tree, structure)
        } catch (e: Exception) {
            fileNull("exception ${e.message}")
        }
    }

    private fun writeTree(buf: Buffer, node: MarkupElement, depth: Int) {
        if (depth > MAX_TREE_DEPTH) throw IllegalArgumentException("tree-depth")
        writeUtf(buf, node.tag, MAX_TAG_LEN)
        if (node.attrs.size > MAX_ATTRS) throw IllegalArgumentException("attrs")
        buf.writeInt(node.attrs.size)
        for ((k, v) in node.attrs) {
            writeUtf(buf, k, MAX_ATTR_KEY_LEN)
            writeUtf(buf, v, MAX_ATTR_VALUE_LEN)
        }
        writeUtf(buf, node.text, MAX_TEXT_LEN)
        if (node.children.size > MAX_TREE_NODES) throw IllegalArgumentException("children")
        buf.writeInt(node.children.size)
        for (c in node.children) writeTree(buf, c, depth + 1)
    }

    private fun readTree(buf: Buffer, depth: Int): MarkupElement? {
        if (depth > MAX_TREE_DEPTH) return null
        val tag = readUtf(buf, MAX_TAG_LEN) ?: return null
        val attrCount = buf.readInt()
        if (attrCount < 0 || attrCount > MAX_ATTRS) return null
        val attrs = HashMap<String, String>(attrCount)
        repeat(attrCount) {
            val k = readUtf(buf, MAX_ATTR_KEY_LEN) ?: return null
            val v = readUtf(buf, MAX_ATTR_VALUE_LEN) ?: return null
            attrs[k] = v
        }
        val text = readUtf(buf, MAX_TEXT_LEN) ?: return null
        val childCount = buf.readInt()
        if (childCount < 0 || childCount > MAX_TREE_NODES) return null
        val children = ArrayList<MarkupElement>(childCount.coerceAtMost(1024))
        repeat(childCount) {
            children.add(readTree(buf, depth + 1) ?: return null)
        }
        return MarkupElement(tag, attrs, children, text)
    }

    private fun writeUtf(buf: Buffer, s: String, maxLen: Int) {
        val bytes = s.encodeToByteArray()
        if (bytes.size > maxLen) throw IllegalArgumentException("utf-too-long ${bytes.size}")
        buf.writeInt(bytes.size)
        buf.write(bytes)
    }

    private fun readUtf(buf: Buffer, maxLen: Int): String? {
        val n = buf.readInt()
        if (n < 0 || n > maxLen) return null
        return buf.readByteArray(n.toLong()).decodeToString()
    }

    private fun fileNull(reason: String): PersistedChapterFile? {
        Logger.w("Orilumn.DISK", "chapter-file decode null ($reason)")
        return null
    }

    // ── book-level deduped sheets: hash → flat CSS text ──

    private const val SHEETS_MAGIC = 0x53485354 // "SHST"
    private const val SHEETS_VERSION = 1

    fun encodeSheets(sheets: Map<Long, String>): ByteArray {
        val buf = Buffer()
        buf.writeInt(SHEETS_MAGIC)
        buf.writeInt(SHEETS_VERSION)
        buf.writeInt(sheets.size)
        for ((h, text) in sheets) {
            buf.writeLong(h)
            writeUtf(buf, text, MAX_SHEET_LEN)
        }
        return buf.readByteArray()
    }

    fun decodeSheets(bytes: ByteArray): Map<Long, String>? {
        if (bytes.isEmpty()) return null
        return try {
            val buf = Buffer().write(bytes)
            if (buf.readInt() != SHEETS_MAGIC) return null
            if (buf.readInt() != SHEETS_VERSION) return null
            val n = buf.readInt()
            if (n < 0 || n > MAX_SHEETS_PER_BOOK) return null
            val out = HashMap<Long, String>(n)
            repeat(n) {
                val h = buf.readLong()
                out[h] = readUtf(buf, MAX_SHEET_LEN) ?: return null
            }
            if (!buf.exhausted()) return null
            out
        } catch (_: Exception) {
            null
        }
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

/**
 * One chapter's import-built file: post-[ChapterPreprocessor] tree, deduped sheet references,
 * and the nested structure payload. Open resolves [sheetHashes] through the book's
 * `_sheets.bin`, rebuilds `CssBundle(texts, [baseHrefs])` (identical to the epub-read path for
 * media-free chapters), and relinks [structure] onto [tree] — no epub text IO.
 */
data class PersistedChapterFile(
    val chapterIndex: Int,
    val cssHash: Long,
    val sheetHashes: List<Long>,
    val baseHrefs: List<String>,
    val tree: MarkupElement,
    val structure: PersistedChapterStructure,
)
