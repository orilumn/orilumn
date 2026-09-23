package orilumn.reader.engine.paging

/**
 * Layout abstraction interface (pure geometry, the only seam between the Android rendering and
 * this layer).
 *
 * The paging algorithm ([Paginator]) relies only on this interface to cut pages by line and does
 * not care whether the backing is an Android StaticLayout or a fake JVM implementation, so the
 * paging logic can be unit-tested on the JVM independently of Android.
 *
 * Coordinate convention: line tops/bottoms are vertical coordinates within the content area (the
 * type area after removing margins); the page height `contentH` passed to [Paginator.paginate]
 * must use the same coordinate system.
 */
interface BookLayout {
    /** Total number of lines. */
    val lineCount: Int

    /** Total text length in chars (UTF-16 code-unit length). */
    val length: Int

    /** y (px) of the top of line [i] relative to the content-area top. */
    fun getLineTop(i: Int): Int

    /** y (px) of the bottom of line [i] relative to the content-area top. */
    fun getLineBottom(i: Int): Int

    /** Starting char offset of line [i]. */
    fun getLineStart(i: Int): Int

    /** Ending char offset of line [i] (exclusive, i.e. the start of the next line). */
    fun getLineEnd(i: Int): Int

    /**
     * Whether line [i] is a paragraph boundary (can serve as the paragraph-preserving line anchor
     * when cutting pages).
     * Determined by: the first line, the previous line ending in a newline, or an empty line.
     * The concrete implementation is computed by the materialization layer based on each Layout's
     * internal signals.
     */
    fun isParagraphBoundaryLine(i: Int): Boolean
}