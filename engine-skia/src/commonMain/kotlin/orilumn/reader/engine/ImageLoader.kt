package orilumn.reader.engine

import orilumn.reader.collections.SyncLock
import orilumn.reader.collections.withLock
import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.ImageCodec

/**
 * Image loading + caching for EPUB `<img>` tags.
 *
 * Resolves each `src` relative to its chapter's spine href via [EpubResourceReader.resolveRelative],
 * caches decoded [DecodedImage]s in an [LRU cache][android.util.LruCache] keyed by absolute resource
 * path (+ cache-busting target width), and supports two operational modes:
 *
 *  1. **Probe bounds** ([decodeBounds]) — header-only bounds read via skia [ImageCodec.probeBounds]
 *     (no pixel allocation), fast and lightweight; used during layout so the box engine can honor
 *     the real aspect ratio instead of falling back to a hard-coded default ratio.
 *  2. **Decode to target** ([decode]) — decodes via skia ([ImageCodec.decodeScaled]) and scales
 *     exactly to a target width, mirroring the retired BitmapFactory pipeline (inSampleSize
 *     coarse sample + createScaledBitmap precise scale). The result is cached so subsequent draws
 *     of the same image at the same target width return immediately.
 *
 * E1: the decode path is now skia-only; Compose 位图经共享 [orilumn.reader.ui.imageBitmapOf]
 * 接缝产出 (C1-0: shaping no longer consumes bitmaps — inline `<img>` is a U+FFFC geometry placeholder).
 *
 * All methods are **thread-safe** (guarded by an internal lock).
 */
class ImageLoader(private val reader: EpubResourceReader) : ImageBoundsReader {

    /** Pixel-memory budget for the image cache, KB (~16 MB, fits comfortably on modern devices).
     *  (C2-P2a: fixed constant; was `maxMemory()/8` capped at the same 16 MB — every real device
     *  has ≥128 MB heap so the cap always won; `Runtime` is JVM-only.) */
    private val maxKb = 16 * 1024
    private val lock = SyncLock()

    /** Cached decoded images keyed by `"absolutePath|targetWidth"`, sized by decoded pixels.
     *  (C2-P2a: access-ordered `LinkedHashMap` + manual eldest trim + pixel accounting;
     *  was `android.util.LruCache`, same eviction semantics.) */
    private val imageCache = LinkedHashMap<String, DecodedImage>(64, 0.75f, true)
    private var imageKb = 0

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
     * unreadable, or decoding fails.
     *
     * The result is cached keyed by `"absolutePath|targetWidth"`, so repeated draws of the same
     * image at the same width return immediately.
     */
    fun decode(chapterHref: String, src: String, targetWidth: Int): DecodedImage? {
        val path = resolvePath(chapterHref, src) ?: return null
        val key = "$path|$targetWidth"
        lock.withLock { imageCache[key] }?.let { return it }

        val bytes = reader.readBytes(path) ?: return null
        val decoded = ImageCodec.decodeScaled(bytes, targetWidth) ?: return null
        lock.withLock {
            imageCache[key] = decoded
            imageKb += decoded.width * decoded.height * 4 / 1024
            while (imageKb > maxKb && imageCache.isNotEmpty()) {
                val eldest = imageCache.keys.first()
                imageCache.remove(eldest)?.let { imageKb -= it.width * it.height * 4 / 1024 }
            }
        }
        return decoded
    }

    /** Clears all cached images and bounds. Called when the book is closed. */
    fun clear() {
        lock.withLock {
            imageCache.clear()
            boundsCache.clear()
            imageKb = 0
        }
    }
}