package orilumn.reader.ui.reader.curl

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

/**
 * Page curl (Apple/iOS style) pure 2D Canvas.
 * Crease: a diagonal line from the top edge to the bottom edge, positioned by progress (sticking to the right edge and expanding left during drag) + sliding vertical tilt.
 * Draw order guarantees the current page is always visible:
 *   1) full current page (upright)
 *   2) next page clipped into the reveal area of the curled region (exposed on the right)
 *   3) paper back = current page scaled(-1)·rotated·uprightContent about botC, clipped into the curled region (author style)
 *   4) LinearGradient shadows on both sides
 */
class CurlView @JvmOverloads constructor(
    context: Context,
) : View(context) {

    companion object {
        const val CORNER_TOP_RIGHT = 0
        const val CORNER_BOTTOM_RIGHT = 1
        private const val BACK_PAPER = 0xFFF1EEE6.toInt()
        private const val SHADOW_BAND = 0.05f
    }

    private var frontBitmap: Bitmap? = null
    private var backBitmap: Bitmap? = null

    /** Paper-back content paint: draws the mirrored content at low opacity (showing the paper color underneath), making the page reverse clearly lighter than the front. */
    private val backContentPaint = Paint().apply { alpha = 100 }
    var corner: Int = CORNER_BOTTOM_RIGHT
    private var downX: Float = 0f
    private var downY: Float = 0f
    private var curX: Float = 0f
    private var curY: Float = 0f
    private var progress: Float = 0f

    /** Whether curling the previous page (corner>=2, left edge). Previous is the reverse of next: swap the tiled/reveal pages and switch the crease to left→right. */
    private var fromLeft: Boolean = false

    /** Whether the curl has actually started (progress ever exceeded 0.011): when passing p≈0 during back-slide/undo, stop drawing the tiled page to keep the curled region sliding out continuously. */
    private var curlActive: Boolean = false

    /** Whether dragged by the finger (moveTo was called): a tap/auto path was never dragged, so the commit start stays at progress. */
    private var dragged: Boolean = false

    var isAnimating: Boolean = false
    private var animator: ValueAnimator? = null

    /** Target progress of the current commit animation: a next-page undo may go negative (whole slide right past the right edge); begin/cancel reset to 1.
 *  The drawing side uses this to distinguish "flip not yet started" (progress≈0 draws the tiled page) from "undo exiting" (progress<0 still draws the curled region sliding out). */
    private var commitEnd: Float = 1f

    /** Pending commit that arrived before begin finished (tap path's onCurlBegin/onCurlCommit arrive nearly together; held while the front screenshot is incomplete). */
    private var pendingCommit: Pair<Boolean, (() -> Unit)?>? = null

    /** Resume-pending callback waiting for the target page (back) to be ready for the complete animation + fallback timeout (see commit). */
    private var pendingBack: (() -> Unit)? = null
    private var pendingBackTimeout: Runnable? = null

    /** Fires once at the tail of a full flip animation (progress≥0.9) to notify the caller to start base navigation early (see onRevealProgress). */
    private var revealFired: Boolean = false

    /** Animation-tail callback: set by ReaderActivity before commit; triggers the base layer to flip to the target page and pre-draw frames. */
    var onRevealProgress: (() -> Unit)? = null

    fun begin(front: Bitmap, downX: Float, downY: Float, corner: Int) {
        animator?.cancel()
        isAnimating = false
        fromLeft = corner >= 2
        commitEnd = 1f
        curlActive = false
        dragged = false
        revealFired = false
        frontBitmap = front
        backBitmap = null
        this.corner = corner
        this.downX = downX
        this.downY = downY
        this.curX = downX
        this.curY = downY
        // The previous page starts from state 4 (progress=0, crease near the left spine); the next page starts from state 2 (progress=0, crease at the right)
        progress = 0f
        invalidate()
        // Re-run an earlier-arrived commit after the front screenshot is ready: avoid commit completing immediately when frontBitmap==null → no-animation jump
        pendingCommit?.let { (complete, onDone) ->
            pendingCommit = null
            commit(complete, onDone)
        }
    }

    fun setBack(back: Bitmap) {
        backBitmap = back
        // Auto flip waiting for back: resume immediately (smoothly curl over from the current progress, avoiding the whole block jumping when back arrives)
        pendingBackTimeout?.let { removeCallbacks(it) }
        pendingBackTimeout = null
        val cb = pendingBack
        pendingBack = null
        if (cb != null) startCommitAnim(true, cb)
        invalidate()
    }

    fun moveTo(curX: Float, curY: Float) {
        this.curX = curX
        this.curY = curY
        val w = width.toFloat().coerceAtLeast(1f)
        val dx = curX - downX
        if (fromLeft) {
            // Previous page: finger right-swipe (dx>0) → progress increases from 0, may exceed to 1.9 (crease follows the finger past the right edge)
            progress = (dx / w).coerceIn(0f, 1.9f)
        } else {
            // Next page: finger left-swipe (dx<0) → progress increases from 0; right back-swipe may go negative (crease follows the finger past the right edge)
            progress = (-dx / w).coerceIn(-0.9f, 1f)
        }
        if (progress > 0.011f) curlActive = true
        dragged = true
        animator?.cancel()
        invalidate()
    }

    /** Currently frozen tilt (fixed after the finger is released/during the animation): the previous-page commit end uses it to compute the margin for the crease to fully slide past the right edge. */
    private fun frozenTilt(): Float {
        val dx = curX - downX
        val dy = curY - downY
        val dl = hypot(dx, dy)
        return if (dl < 1e-3f) 0f else (dy / dl).coerceIn(-1f, 1f)
    }

    /** Next-page follow anchor (exact solution): crease position fx makes "the folded page edge (mirror of the right edge) exactly pass through the finger".
 *  Condition = the finger's mirror across the crease lands exactly on the right edge x=w; solve in reverse for crease-line direction (d, h) and normal (h, -d):
 *  fx = curX + lean*tiltW - (K + d*curY)/h, K = (curX - w)(h²+d²)/(2h). For a vertical crease (lean=0) this is (curX+w)/2. */
    private fun anchoredFoldX(w: Float, h: Float, lean: Float, tiltW: Float): Float {
        val d = 2f * lean * tiltW
        val denom = h * h + d * d
        val k = (curX - w) * denom / (2f * h)
        return (curX + lean * tiltW - (k + d * curY) / h).coerceIn(0f, w * 2f)
    }

    fun commit(complete: Boolean, onDone: (() -> Unit)? = null) {
        if (frontBitmap == null) {
            // front not ready yet (tap path's begin screenshot/transform callback not finished): hold, re-run after begin
            pendingCommit = complete to onDone
            return
        }
        // A successful flip needs the reveal target page (back) ready: tap/auto-path commit races with back's async screenshot;
        // if the animation starts first and back arrives later with the crease already advanced, the right/left half-screen would "jump out" the target page. The follow-finger path has back ready during the drag, unaffected.
        if (complete && backBitmap == null) {
            pendingBack = onDone
            pendingBackTimeout?.let { removeCallbacks(it) }
            pendingBackTimeout = Runnable { onRevealTimer() }
            postDelayed(pendingBackTimeout, 300)
            return
        }
        startCommitAnim(complete, onDone)
    }

    /** Fallback timeout waiting for back: when the back screenshot is delayed, don't wait forever; degrade to playing (only the tiled page flips, no reveal). */
    private fun onRevealTimer() {
        pendingBackTimeout = null
        val cb = pendingBack
        pendingBack = null
        if (cb != null) startCommitAnim(true, cb)
    }

    private fun startCommitAnim(complete: Boolean, onDone: (() -> Unit)? = null) {
        // Start: when dragged by the finger and it's the next page, resume from the position where "the crease followed the anchor to the page-edge-through-finger"; (no jump back to the progress position on release);
        // tap/auto path (not dragged) and the previous page use progress.
        val start = if (dragged && !fromLeft) {
            val w2 = width.toFloat().coerceAtLeast(1f)
            val h2 = height.toFloat().coerceAtLeast(1f)
            val fx = anchoredFoldX(w2, h2, frozenTilt(), 0.9f * w2)
            ((w2 - fx) / w2).coerceIn(-0.9f, 1.9f)
        } else progress.coerceIn(-0.9f, 1.9f)
        // The end may exceed bounds, letting the diagonal crease keep its direction and decelerate naturally until the curled region fully leaves the corresponding screen edge:
        //   prev done → p>1 continues sliding right past the right edge; next done → 1 (left edge, straighten with the tilt fade).
        //   next undone (canceled by a right back-swipe) → p<0 continues sliding right past the right edge, same direction as the back-swipe, no mid-turn;
        //   prev undone → bounces back to 0 (the left spine tiles back to the current page).
        val end = if (complete) {
            if (fromLeft) 1f + 0.9f * abs(frozenTilt()) else 1f
        } else {
            if (fromLeft) 0f else -0.9f * abs(frozenTilt())
        }
        commitEnd = end
        animator?.cancel()
        isAnimating = true
        animator = ValueAnimator.ofFloat(start, end).apply {
            // Play duration: a full flip is 600ms, undo slightly faster; a next-page undo scales proportionally to the remaining slide-out distance, keeping the slide-out speed consistent
            duration = when {
                complete -> 600
                fromLeft -> 500
                else -> (500 * (start - end) / 0.45f).toInt().coerceIn(80, 500).toLong()
            }
            interpolator = DecelerateInterpolator()
            addUpdateListener { a ->
                progress = a.animatedValue as Float
                if (progress > 0.95f) orilumn.reader.io.Logger.d("CURL", "anim progress=$progress")
                // At the tail of a full flip animation (≥0.9), notify the caller early to start base navigation: the base lands and draws
                // frames before the animation ends, so the overlay can be hidden immediately at animation end → eliminating the
                // "animation looks done but taps are dead" window (the Decelerate tail is long). Fires only once; not on complete=false (bounce back).
                if (complete && !revealFired && progress >= 0.9f) {
                    revealFired = true
                    onRevealProgress?.invoke()
                }
                invalidate()
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationEnd(animation: Animator) {
                    // Don't reset isAnimating here: for complete=true it's reset when externally hidden via cancel(),
                    // to avoid onDraw's "page edge past anchor" logic firing at the moment the animation ends and causing a crease-position jump/flicker
                    orilumn.reader.io.Logger.d("CURL", "anim END progress=$progress")
                    onDone?.invoke()
                }
                override fun onAnimationCancel(animation: Animator) { isAnimating = false }
                override fun onAnimationRepeat(animation: Animator) {}
            })
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        pendingCommit = null
        pendingBackTimeout?.let { removeCallbacks(it) }
        pendingBackTimeout = null
        pendingBack = null
        frontBitmap = null
        backBitmap = null
        progress = 0f
        commitEnd = 1f
        curlActive = false
        dragged = false
        revealFired = false
        onRevealProgress = null
        isAnimating = false
        invalidate()
    }

    /** Discard an unexecuted pending commit (e.g. the front screenshot failed, no begin will come; avoid a stale pending commit firing on a later begin). */
    fun dropPendingCommit() {
        pendingCommit = null
        pendingBackTimeout?.let { removeCallbacks(it) }
        pendingBackTimeout = null
        pendingBack = null
    }

    private fun lineCross(p1: PointF, p2: PointF, p3: PointF, p4: PointF): PointF? {
        val d = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x)
        if (abs(d) < 1e-3f) return null
        val a = p1.x * p2.y - p1.y * p2.x
        val b = p3.x * p4.y - p3.y * p4.x
        return PointF((a * (p3.x - p4.x) - (p1.x - p2.x) * b) / d, (a * (p3.y - p4.y) - (p1.y - p2.y) * b) / d)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawBody(canvas)
    }

    /** The curl session keeps the overlay on top; does not intercept touch (returns super so it passes through to the underlying pageView).
 *  Double-flip prevention is handled uniformly by [orilumn.reader.ui.reader.ReaderActivity] with the `isAnimating` guard;
 *  otherwise CurlView swallowing UP would prevent a commit from firing on drag release. */
    override fun onTouchEvent(event: MotionEvent): Boolean = false

    /** The actual curl drawing body (next-page semantics: reveal on the right of the crease). For the previous page it swaps the tiled/reveal pages and reverses the crease direction inside. */
    private fun drawBody(canvas: Canvas) {
        val front0 = frontBitmap ?: return
        val back0 = backBitmap
        val w = width.toFloat()
        val h = height.toFloat()
        // Tiled page (front) and reveal page (back):
        //   next: front=current page (left, curling away), back=next page (right, reveal fades in).
        //   prev (reverse unfold): front=prev page (left, unfolding more), back=current page (right, gradually covered).
        // The spatial layout is constant: the prev/tiled page on the left, the current page on the right, and the curled paper back (reverse of front) in the middle.
        val front: Bitmap
        val back: Bitmap?
        if (fromLeft) {
            if (back0 == null) { canvas.drawBitmap(front0, 0f, 0f, null); return }
            front = back0
            back = front0
        } else {
            front = front0
            back = back0
        }
        // Crease: next page w*(1-p) right→left; prev page w*p left→right (reversed, so it unfolds rightward).
        // prev done allows p>1, next undo allows p<0: the crease as a whole keeps moving right past the right edge (see commit's end), the curled region naturally slides out to lie flat.
        val foldX = if (fromLeft) w * progress.coerceIn(0f, 1.9f) else w * (1f - progress.coerceIn(-0.9f, 1f))
        var dx = curX - downX
        var dy = curY - downY
        val dl = hypot(dx, dy).coerceAtLeast(1f)
        var tilt = (dy / dl).coerceIn(-1f, 1f)
        // Straighten the crease end (only for animations "ending at the spine"): next done curls left to the left edge (p>0.9), prev undone bounces back to the left-edge spine (p<0.1),
        // making the crease approach vertical near the spine — otherwise the visible end of the diagonal crease gets stuck mid-screen, and when the overlay disappears after progress runs out there is a "pause + jump".
        // prev done / next undone slide right past the right edge, no straightening.
        if (isAnimating) {
            val endAtSpine = (!fromLeft && commitEnd >= 1f && progress > 0.9f) ||
                (fromLeft && commitEnd <= 0f && progress < 0.1f)
            if (endAtSpine) {
                val fade = if (progress > 0.9f) (1f - progress) / 0.1f else progress / 0.1f
                tilt *= fade
            }
        }
        val tiltW = w * 0.9f
        // The crease direction must be perpendicular to the finger swipe: for next (right-edge curl, left swipe) a positive tilt makes the crease tilt upper-left→lower-right;
        // previous is mirrored (left-edge unfold, right swipe) and negated, otherwise the crease would be parallel to the swipe.
        val lean = if (fromLeft) -tilt else tilt
        // Endpoints may exceed the right edge (up to 2w rightward, for an easy right-edge intersection); but the left is the spine, so x=0 is not crossed
        fun cl(x: Float) = x.coerceIn(0f, w * 2f)
        var topC = PointF(cl(foldX - lean * tiltW), 0f)
        var botC = PointF(cl(foldX + lean * tiltW), h)
        // Finger anchor (next following-finger): the folded page edge (mirror of the right edge) must pass through the finger; reposition the crease by exact inverse solve (decoupled from progress);
        // during the commit animation the finger is released, so it stops following the anchor, avoiding the crease shifting per-frame causing pauses and erratic turns during undo/continue.
        // Not enabled for previous: its follow progress maps directly to crease displacement (crease = right-swipe amount from the left spine), matching the tap-path animation geometry.
        if (!isAnimating && !fromLeft) {
            val fx = anchoredFoldX(w, h, lean, tiltW)
            topC = PointF(cl(fx - lean * tiltW), 0f)
            botC = PointF(cl(fx + lean * tiltW), h)
        }

        // When not started (progress threshold) or there is no target page, draw only the start page (front0 = current page, the one the user is viewing). Progress is driven by progress/commit or moveTo,
        // not by the follow distance dl (one-shot flips never moveTo). When the started back-swipe/undo passes p≈0, skip it to keep the curled region sliding out continuously.
        // Note: use front0, not the swapped front: in the prev direction front has been replaced with "prev (back0)", drawing it would flash the prev page full-screen.
        if (back == null || (progress <= 0.011f && !curlActive)) {
            canvas.drawBitmap(front0, 0f, 0f, null)
            return
        }

        // 1) full current page (upright, always visible)
        canvas.drawBitmap(front, 0f, 0f, null)

        // 2) revealPoly of the curled region (eu.wewox endSide) + next page clipped into the reveal
        val pts = ArrayList<PointF>()
        fun endSide() {
            lineCross(topC, botC, PointF(w, 0f), PointF(w, h))?.let { pts.add(it); pts.add(it) }
        }
        if (topC.x < w) { pts.add(topC); pts.add(PointF(w, topC.y)) } else endSide()
        if (botC.x < w) { pts.add(PointF(w, h)); pts.add(botC) } else endSide()
        if (pts.size < 3) { canvas.drawBitmap(front, 0f, 0f, null); return }
        val reveal = Path()
        reveal.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) reveal.lineTo(pts[i].x, pts[i].y)
        reveal.close()

        canvas.save()
        canvas.clipPath(reveal)
        canvas.drawBitmap(back, 0f, 0f, null)
        canvas.restore()

        // 3) paper back: drawn only within the "fold strip" (the mirror region of the reveal, left of the crease).
        //    Both the paper color and the mirror are clipped to the fold strip, to avoid the paper color covering the unfolded current page (part 1).
        val refM = Matrix().apply { setValues(reflectMatrix(topC.x, topC.y, botC.x, botC.y)) }
        // Fold-strip vertices = reveal polygon reflected by refM to the left of the crease
        val stripPts = FloatArray(pts.size * 2)
        refM.mapPoints(stripPts, FloatArray(pts.size * 2).also { s ->
            for (i in pts.indices) { s[i * 2] = pts[i].x; s[i * 2 + 1] = pts[i].y }
        })
        val strip = Path().apply {
            moveTo(stripPts[0], stripPts[1])
            for (i in 1 until pts.size) lineTo(stripPts[i * 2], stripPts[i * 2 + 1])
            close()
        }
        canvas.save()
        canvas.clipPath(strip)
        canvas.drawColor(BACK_PAPER)
        canvas.save()
        canvas.concat(refM)
        canvas.clipRect(0f, 0f, w, h)
        // paper back = reverse of the tiled page (front); in the prev direction front has been swapped to "prev", so it reflects the back of the prev page.
        // The content is drawn at low opacity (showing the paper color underneath), making the reverse clearly lighter than the front, close to a real page reverse.
        canvas.drawBitmap(front, 0f, 0f, backContentPaint)
        canvas.restore()
        canvas.restore()

        // 3b) outer shadow around the fold strip: a gradient frame (right side = the crease, not drawn).
        //     each edge draws a gradient band (fading outward along the normal); each corner separately draws a diagonal gradient block, filled once,
        //     the two don't overlap → gradient present without stacked color darkening.
        run {
            val edgeW = w * SHADOW_BAND
            val n = pts.size
            var cx = 0f; var cy = 0f
            for (i in 0 until n) { cx += stripPts[i * 2]; cy += stripPts[i * 2 + 1] }
            cx /= n; cy /= n
            // outward unit normal of each free edge
            val norms = FloatArray((n - 1) * 2)
            for (i in 0 until n - 1) {
                val ax = stripPts[i * 2];     val ay = stripPts[i * 2 + 1]
                val bx = stripPts[i * 2 + 2]; val by = stripPts[i * 2 + 3]
                var dx = bx - ax; var dy = by - ay
                val l = hypot(dx, dy).coerceAtLeast(1e-3f); dx /= l; dy /= l
                var nx = -dy; var ny = dx
                val mx = (ax + bx) / 2f - cx; val my = (ay + by) / 2f - cy
                if (nx * mx + ny * my < 0f) { nx = -nx; ny = -ny }
                norms[i * 2] = nx; norms[i * 2 + 1] = ny
            }
            fun bandPaint(fx: Float, fy: Float, nx: Float, ny: Float): Paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(
                        fx, fy, fx + nx * edgeW, fy + ny * edgeW,
                        intArrayOf(Color.argb(60, 0, 0, 0), Color.argb(0, 0, 0, 0)),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
                    )
                }
            // 1) free-edge shadow: the longest edge (the page-edge long side) uses a triangle gradient, full width at the start, tapering to the endpoint;
        //    the remaining edges still use rectangular bands.
        var longIdx = -1; var longLen = -1f
        for (e in 0 until n - 1) {
            val lx = stripPts[e * 2 + 2] - stripPts[e * 2]
            val ly = stripPts[e * 2 + 3] - stripPts[e * 2 + 1]
            val ll = hypot(lx, ly)
            if (ll > longLen) { longLen = ll; longIdx = e }
        }
        for (i in 0 until n - 1) {
            val ax = stripPts[i * 2];     val ay = stripPts[i * 2 + 1]
            val bx = stripPts[i * 2 + 2]; val by = stripPts[i * 2 + 3]
            val nx = norms[i * 2]; val ny = norms[i * 2 + 1]
            val band = Path()
            if (i == longIdx) {
                // long edge (page edge): vertical (both ends share x) → a rectangle = full-width edgeW;
                // when one end leans right relative to the other, that end's width →0 (trapezoid), hugging the right edge =0 becomes a triangle.
                // the top-end width comes from topC, the bottom-end from botC; each end's width is governed by its own distance to the right edge: hugging the right edge (x=w) →0.
                val wTop = (edgeW * ((w - topC.x) / (w - botC.x).coerceAtLeast(1e-3f))).coerceIn(0f, edgeW)
                val wBot = (edgeW * ((w - botC.x) / (w - topC.x).coerceAtLeast(1e-3f))).coerceIn(0f, edgeW)
                val dirx = bx - ax; val diry = by - ay
                val elen = hypot(dirx, diry).coerceAtLeast(1e-3f)
                val eux = dirx / elen; val euy = diry / elen
                val aIsTop = ay <= by          // whether the start end is higher up
                val wA = if (aIsTop) wTop else wBot   // start-end width
                val wB = if (aIsTop) wBot else wTop   // end-end width
                val steps = 24
                for (k2 in 0 until steps) {
                    val u0 = k2.toFloat() / steps
                    val u1 = (k2 + 1f) / steps
                    val wi0 = wA + (wB - wA) * u0
                    val wi1 = wA + (wB - wA) * u1
                    val p0x = ax + eux * u0 * elen; val p0y = ay + euy * u0 * elen
                    val p1x = ax + eux * u1 * elen; val p1y = ay + euy * u1 * elen
                    if (wi0 < 0.3f) continue   // skip extremely thin slices but don't break, to avoid the whole shadow disappearing when the narrow end is at the start
                    val slice = Path()
                    slice.moveTo(p0x, p0y); slice.lineTo(p1x, p1y)
                    slice.lineTo(p1x + nx * wi1, p1y + ny * wi1)
                    slice.lineTo(p0x + nx * wi0, p0y + ny * wi0); slice.close()
                    val sp = Paint(Paint.ANTI_ALIAS_FLAG)
                    sp.shader = LinearGradient(
                        p0x, p0y, p0x + nx * wi0, p0y + ny * wi0,
                        intArrayOf(Color.argb(60, 0, 0, 0), Color.argb(0, 0, 0, 0)),
                        floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
                    )
                    canvas.drawPath(slice, sp)
                }
                continue
            } else {
                band.moveTo(ax, ay); band.lineTo(bx, by)
                band.lineTo(bx + nx * edgeW, by + ny * edgeW)
                band.lineTo(ax + nx * edgeW, ay + ny * edgeW); band.close()
            }
            canvas.drawPath(band, bandPaint(ax, ay, nx, ny))
        }
            // 2) each corner: split into two triangles along the diagonal, each fading outward from the vertex along its own right-angle edge,
        //    joined into a complete L shape (moon+/多看 style book-corner shadow).
        for (i in 1 until n - 1) {
            val vx = stripPts[i * 2]; val vy = stripPts[i * 2 + 1]
            val pnx = norms[(i - 1) * 2]; val pny = norms[(i - 1) * 2 + 1]
            val nx = norms[i * 2];       val ny = norms[i * 2 + 1]
            val ax = vx + pnx * edgeW; val ay = vy + pny * edgeW  // end of the previous edge's normal
            val bx = vx + nx * edgeW;   val by = vy + ny * edgeW  // end of this edge's normal
            val mx = vx + (pnx + nx) * edgeW; val my = vy + (pny + ny) * edgeW // outer tip
            // Triangle 1: along the previous edge direction (vertex→outer tip)
            val tri1 = Path()
            tri1.moveTo(vx, vy); tri1.lineTo(ax, ay); tri1.lineTo(mx, my); tri1.close()
            canvas.drawPath(tri1, bandPaint(vx, vy, pnx, pny))
            // Triangle 2: along this edge direction (vertex→outer tip)
            val tri2 = Path()
            tri2.moveTo(vx, vy); tri2.lineTo(bx, by); tri2.lineTo(mx, my); tri2.close()
            canvas.drawPath(tri2, bandPaint(vx, vy, nx, ny))
        }
        }

        // 4) shadows on both sides of the crease (LinearGradient, width SHADOW_BAND * screen width)
        var nx = -(botC.y - topC.y)
        var ny = (botC.x - topC.x)
        val nl = hypot(nx, ny).coerceAtLeast(1e-3f); nx /= nl; ny /= nl
        val toFlatX = -topC.x - botC.x
        val toFlatY = h - topC.y - botC.y
        val sgn = if (nx * toFlatX + ny * toFlatY < 0f) -1f else 1f
        val bandW = w * SHADOW_BAND
        // At animation end (progress→1) the crease hugs the screen edge; this shadow would linger as a still edge-shadow, then "pause briefly and vanish" after leaving the screen;
        // so during the animation phase it fades out with progress at both ends (isomorphic to the tilt fade), while keeping full strength during finger-follow dragging.
        var shadowFade = 1f
        if (isAnimating) {
            // prev p may exceed to 1.9; keep 0 after exceeding (avoid negative alpha)
            if (progress > 0.9f) shadowFade = ((1f - progress) / 0.1f).coerceIn(0f, 1f)
            else if (progress < 0.1f) shadowFade = (progress / 0.1f).coerceIn(0f, 1f)
        }
        // Keep only the shadow on the right of the crease (reveal/next-page side): a rectangular band
        val sideB = Paint(Paint.ANTI_ALIAS_FLAG)
        sideB.shader = LinearGradient(
            topC.x, topC.y, topC.x - nx * sgn * bandW, topC.y - ny * sgn * bandW,
            intArrayOf(Color.argb((60 * shadowFade).toInt(), 0, 0, 0), Color.argb(0, 0, 0, 0)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP,
        )
        val binB = Path()
        binB.moveTo(topC.x, topC.y); binB.lineTo(botC.x, botC.y)
        binB.lineTo(botC.x - nx * sgn * bandW, botC.y - ny * sgn * bandW)
        binB.lineTo(topC.x - nx * sgn * bandW, topC.y - ny * sgn * bandW)
        binB.close()
        canvas.drawPath(binB, sideB)
    }

    /** Reflection matrix about the line through (ax,ay) and (bx,by). */
    private fun reflectMatrix(ax: Float, ay: Float, bx: Float, by: Float): FloatArray {
        val dxx = bx - ax
        val dyy = by - ay
        val len = max(1e-3f, hypot(dxx, dyy))
        val ux = dxx / len
        val uy = dyy / len
        val c = ax * ux + ay * uy
        // translation term = A - R*A, expanded to 2*A - 2*(A·U)*U
        return floatArrayOf(
            2 * ux * ux - 1, 2 * ux * uy, 2 * ax - 2 * ux * c,
            2 * ux * uy, 2 * uy * uy - 1, 2 * ay - 2 * uy * c,
            0f, 0f, 1f,
        )
    }
}