package orilumn.reader.engine

import orilumn.reader.data.epub.EpubResourceReader
import orilumn.reader.engine.skia.DecodedImage
import orilumn.reader.engine.skia.ImageCodec

/**
 * Cover decoding: reads the cover bytes from the book's resource by `EpubBook.cover` href →
 * [DecodedImage] (E1: skia decode; the Android Bitmap is bridged at the View paint seam by
 * [BookDocumentController] via [skiaImageToAndroidBitmap]).
 *
 * Lazily decoded and cached by [BookDocumentController]; images may be large, so decoding limits
 * the size as needed.
 */
class CoverDecoder(private val reader: EpubResourceReader) {

    /** Decodes from the full path of the cover resource; returns null when there is no resource
     * or decoding fails. */
    fun decode(coverHref: String): DecodedImage? {
        if (coverHref.isBlank()) return null
        val bytes = reader.readBytes(coverHref) ?: return null
        return ImageCodec.decode(bytes)
    }
}