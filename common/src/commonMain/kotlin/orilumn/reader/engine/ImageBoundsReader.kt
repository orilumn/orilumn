package orilumn.reader.engine

/** Minimal bounds-only image reader used by the pure box flow (no Bitmap dependency).
 *  The Android [orilumn.reader.engine.ImageLoader] implements this; the common layout layer needs
 *  only intrinsic w×h to compute replaceable aspect ratios. */
fun interface ImageBoundsReader {
    fun decodeBounds(chapterHref: String, src: String): Pair<Int, Int>?
}