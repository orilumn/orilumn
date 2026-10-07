package orilumn.reader.engine

import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.ImageCodec

/**
 * Image loading for EPUB `<img>` tags.
 *
 * Resolves each `src` relative to its chapter's spine href via [EpubResourceReader.resolveRelative],
 * and supports two operational modes:
 *
 *  1. **Probe bounds** ([decodeBounds]) — header-only bounds read via skia [ImageCodec.probeBounds]
 *     (no pixel allocation), fast and lightweight; used during layout so the box engine can honor
 *     the real aspect ratio instead of falling back to a hard-coded default ratio. Results are
 *     cached (≤512 entries) — layout queries the same files on every pass.
 *  2. **Decode to target** ([decode]) — decodes via skia ([ImageCodec.decodeScaled]) and scales
 *     exactly to a target width, mirroring the retired BitmapFactory pipeline (inSampleSize
 *     coarse sample + createScaledBitmap precise scale). Deliberately **uncached**: decoded
 *     bitmaps live in the UI-layer cross-page cache (`PageImageCache`, stable identity key),
 *     so a second pixel cache here would only double memory for the same bytes.
 *
 * E1: the decode path is now skia-only; Compose 位图经共享 [orilumn.reader.ui.imageBitmapOf]
 * 接缝产出 (C1-0: shaping no longer consumes bitmaps — inline `<img>` is a U+FFFC geometry placeholder).
 *
 * All methods are **thread-safe** (guarded by an internal lock).
 */
class ImageLoader(private val reader: EpubResourceReader) : ImageBoundsReader {

    private val lock = SyncLock()

    /** Cached intrinsic bounds keyed by absolute resource path (≤512 entries, eldest evicted). */
    private val boundsCache = LinkedHashMap<String, Pair<Int, Int>>(64, 0.75f, true)

    /** Resolves [src] against [chapterHref] and returns the absolute resource path, or null when the
     *  input is blank. All public methods go through this so the keying logic is single-source. */
    private fun resolvePath(chapterHref: String, src: String): String? {
        if (src.isBlank()) return null
        return reader.resolveRelative(chapterHref, src)
    }

    /**
     * Returns the intrinsic (w, h) of the image at [src] relative to [chapterHref],
     * or null when the resource is missing or unreadable. Header-only decode — no pixel
     * allocation — so this is safe to call during layout on a background thread.
     */
    override fun decodeBounds(chapterHref: String, src: String): Pair<Int, Int>? {
        val path = resolvePath(chapterHref, src) ?: return null
        lock.withLock { boundsCache[path] }?.let { return it }
        val bytes = reader.readBytes(path) ?: return null
        val pair = ImageCodec.probeBounds(bytes) ?: return null
        lock.withLock {
            boundsCache[path] = pair
            while (boundsCache.size > 512) {
                boundsCache.remove(boundsCache.keys.first())
            }
        }
        return pair
    }

    /**
     * Decodes the image at [src] relative to [chapterHref] and scales it to fit [targetWidth] px
     * wide (keeping the intrinsic aspect ratio). Returns null when the resource is missing,
     * unreadable, or decoding fails. Uncached — callers hold decoded bitmaps in the UI-layer
     * cross-page cache.
     */
    fun decode(chapterHref: String, src: String, targetWidth: Int): DecodedImage? {
        val path = resolvePath(chapterHref, src) ?: return null
        val bytes = reader.readBytes(path) ?: return null
        return ImageCodec.decodeScaled(bytes, targetWidth)
    }

    /** Clears cached bounds. Called when the book is closed. */
    fun clear() {
        lock.withLock {
            boundsCache.clear()
        }
    }
}