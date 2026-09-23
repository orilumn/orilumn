package orilumn.reader.ui.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import orilumn.reader.data.settings.ReaderSettings
import orilumn.reader.engine.BookDocumentController
import orilumn.reader.engine.EngineLog
import orilumn.reader.engine.render.PageRenderer
import orilumn.reader.ui.reader.curl.CurlView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Curl page-flip state machine: organizes one flip interaction of [ReaderActivity]
 * (front render → CurlView.begin → back background render → setBack → follow-finger moveTo → commit → pre-landing at animation tail → finalize).
 *
 * Converges the old WebView round-trips (onCurlBegin/Target/Move/Commit/FlipReady) into a pure-native
 * call chain; front/back are both drawn into Bitmaps from laid-out line ranges by [PageRenderer]. [CurlView]
 * is reused as a pure 2D curl view with zero changes to its internal drawing.
 *
 * State-machine fields (cross-thread, @Volatile): isCurlAnimating / navDispatched / flipReadyPending /
 * flipFinalized, matching the semantics of the old EPUBBridge.
 */
class CurlCoordinator(
    private val curlView: CurlView,
    private val pageView: View,
    private val document: BookDocumentController,
    private var profile: orilumn.reader.engine.text.TypographicProfile,
    private val scope: CoroutineScope,
    private val onPageMoved: (direction: Int) -> Unit,
    private val onCommitDone: () -> Unit,
    private val logTag: String = "Orilumn.Curl",
    /**
     * C2-P1: cover bitmap is provided by the host (was `document.coverBitmap`;
     * cover left the controller with the rest of the Bitmap seam). Currently
     * unconstructed legacy path — null until curl wiring lands.
     */
    private val coverBitmap: android.graphics.Bitmap? = null,
) {

    /** Setting change: update the render profile (the curl overlay/background follows the new theme). */
    fun setProfile(p: orilumn.reader.engine.text.TypographicProfile) {
        profile = p
    }

    @Volatile
    private var isCurlAnimating = false
    @Volatile
    private var navDispatched = false
    @Volatile
    private var flipReadyPending = false
    @Volatile
    private var flipFinalized = false

    private var currentDirection = 0

    /**
 * Start a curl page flip.
 * @param direction +1=next page (right-edge curl), -1=previous page (left-edge curl).
 * @param downX finger-down view x (px).
 * @param downY finger-down view y (px).
 * @param chapter current chapter at trigger time.
 * @param page current page at trigger time.
 */
    fun begin(direction: Int, downX: Float, downY: Float, chapter: Int, page: orilumn.reader.engine.paging.PageSlice) {
        if (isCurlAnimating) return
        isCurlAnimating = true
        navDispatched = false
        flipReadyPending = false
        flipFinalized = false
        currentDirection = direction
        EngineLog.d(logTag, "curl.begin dir=$direction down=($downX,$downY) ${document.ctxPublic(chapter)} page=${page.brief()}")

        val corner = cornerFor(direction, downY)
        // front = current page rendered to Bitmap
        val front = render(chapter, page)
        if (front == null) {
            EngineLog.d(logTag, "curl.begin FAIL front=null ${document.ctxPublic(chapter)}")
            isCurlAnimating = false
            return
        }
        curlView.visibility = View.VISIBLE
        curlView.bringToFront()
        curlView.begin(front, downX, downY, corner)
        // back: render the adjacent page in the background (non-blocking on the main thread; CurlView has a pendingBack/timeout fallback)
        scope.launch(Dispatchers.Default) {
            val back = renderAdjacent(direction, chapter, page)
            EngineLog.d(logTag, "curl.back 后台渲染完成 dir=$direction back=${back != null}")
            if (back != null) curlView.post { curlView.setBack(back) }
        }
    }

    /** Follow the finger. */
    fun moveTo(x: Float, y: Float) {
        if (!isCurlAnimating) return
        curlView.moveTo(x, y)
    }

    /** Release submit: complete=true flips, false bounces back. */
    fun commit(complete: Boolean) {
        if (!isCurlAnimating) return
        EngineLog.d(logTag, "curl.commit complete=$complete dir=$currentDirection")
        // Pre-landing at the animation tail: actually advance the current page
        curlView.onRevealProgress = {
            if (!navDispatched && !flipFinalized) {
                navDispatched = true
                EngineLog.d(logTag, "curl.revealProgress 触发后台落位 dir=$currentDirection")
                advance(currentDirection)
            }
        }
        curlView.commit(complete) {
            curlView.post {
                isCurlAnimating = false
                if (complete) {
                    if (!navDispatched && !flipFinalized) {
                        navDispatched = true
                        advance(currentDirection)
                    }
                    if (flipReadyPending) finalize()
                    else {
                        curlView.postDelayed({ finalize() }, 200)
                    }
                } else {
                    curlView.visibility = View.GONE
                }
                onCommitDone()
            }
        }
    }

    /** Cancel (touchcancel). */
    fun cancel() {
        navDispatched = false
        flipReadyPending = false
        flipFinalized = false
        curlView.post {
            curlView.cancel()
            curlView.visibility = View.GONE
            isCurlAnimating = false
        }
    }

    /** After pre-landing, ReaderActivity notifies that the base layer is ready (cross-chapter/render async). */
    fun onFlipReady() {
        curlView.post {
            if (flipFinalized) return@post
            EngineLog.d(logTag, "curl.onFlipReady isAnim=$isCurlAnimating")
            if (isCurlAnimating) flipReadyPending = true
            else finalize()
        }
    }

    /** Discontinuous jump (TOC/progress): clear the overlay and state, reset the animating state. */
    fun jump() {
        cancel()
    }

    /** Actually advance the current page: call the document flip and notify ReaderActivity to update the static page + save. */
    private fun advance(direction: Int) {
        EngineLog.d(logTag, "curl.advance dir=$direction")
        onPageMoved(direction)
    }

    private fun finalize() {
        if (flipFinalized) return
        flipFinalized = true
        flipReadyPending = false
        EngineLog.d(logTag, "curl.finalize（隐藏盖层）dir=$currentDirection")
        curlView.onRevealProgress = null
        curlView.visibility = View.GONE
        curlView.cancel()
        isCurlAnimating = false
    }

    // ---- Rendering ----

    /** Render a chapter page to Bitmap (same path as the static-page render). */
    private fun render(chapter: Int, page: orilumn.reader.engine.paging.PageSlice): Bitmap? {
        if (page.kind == orilumn.reader.engine.paging.PageSlice.Kind.COVER) {
            return renderCover()
        }
        val unit = document.unitAt(chapter) ?: return null
        return renderToBitmap { canvas ->
            PageRenderer.drawPageSlice(canvas, pageView.width, pageView.height, unit, page, profile)
        }
    }

    /** Adjacent-page render (background; currently the in-chapter adjacent page). */
    private fun renderAdjacent(direction: Int, chapter: Int, page: orilumn.reader.engine.paging.PageSlice): Bitmap? {
        val unit = document.unitAt(chapter) ?: return null
        val adj = if (direction > 0) document.nextPageInChapter(unit, page)
        else document.prevPageInChapter(unit, page)
        if (adj == null) return null
        return render(chapter, adj)
    }

    /** Cover page render. */
    private fun renderCover(): Bitmap? {
        val cover = coverBitmap ?: return null
        return renderToBitmap { canvas ->
            PageRenderer.drawCover(canvas, cover, pageView.width, pageView.height, profile.coverProportional, profile)
        }
    }

    private fun renderToBitmap(draw: (Canvas) -> Unit): Bitmap? {
        val w = pageView.width
        val h = pageView.height
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(profile.bgColor)
        runCatching { draw(canvas) }
        return bmp
    }

    private fun cornerFor(direction: Int, y: Float): Int {
        val top = y < pageView.height / 2f
        return if (direction > 0) {
            if (top) CurlView.CORNER_TOP_RIGHT else CurlView.CORNER_BOTTOM_RIGHT
        } else {
            // Left edge: CurlView uses corner>=2 for the left edge; 0/1 are top-right/bottom-right
            if (top) 2 else 3
        }
    }

    val isAnimating: Boolean get() = isCurlAnimating

    /** PageSlice log shorthand. */
    private fun orilumn.reader.engine.paging.PageSlice.brief(): String =
        "page[$firstLine,$lastLineExclusive)char[$charStart,$charEnd)kind=$kind"
}