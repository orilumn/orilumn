package orilumn.reader.ui.reader

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * Reader page-flip gesture recognition: three-zone tap + horizontal follow-finger drag + vertical brightness gesture.
 *
 * Fully hand-written (no dependence on `GestureDetector`'s complex interface inheritance); forwarded by the
 * ReaderActivity page container's onTouchEvent to [onTouchEvent]. Semantics:
 *  - Release without a horizontal drag (short duration) → [onTap](x) (caller decides by three zones);
 *  - Horizontal drag (|dx|>|dy| and beyond the touch slop) → [onDragStart]+[onDrag], direction locked;
 *  - Release → [onRelease](direction, started): started=true means a drag was actually entered (for the curl commit decision).
 *
 * Brightness gesture (enabled by the caller only when not following the system):
 *  - Single-finger vertical: triggered only on the left 1/3 ([canLeftVertical]) or right 1/3 ([canRightVertical]);
 *  - Two-finger vertical: triggered on any area ([canTwoFingerVertical]);
 *  - Once recognized as a brightness gesture, [bstActive] locks, and thereafter only the cumulative vertical-offset
 *    fraction (relative to the screen height, up positive, roughly -1..1) is reported to [onBrightnessDelta];
 *    release ends with [onBrightnessEnd]; no further inline swipe/tap.
 */
class FlipGestureDetector(
    context: Context,
    private val onTap: (x: Float) -> Unit,
    private val onDrag: (direction: Int, dx: Float, dyRatio: Float) -> Unit,
    private val onRelease: (direction: Int, started: Boolean) -> Unit,
    private val viewWidth: () -> Float,
    private val viewHeight: () -> Float,
    private val canLeftVertical: () -> Boolean,
    private val canRightVertical: () -> Boolean,
    private val canTwoFingerVertical: () -> Boolean,
    private val onBrightnessStart: () -> Unit,
    private val onBrightnessDelta: (fraction: Float) -> Unit,
    private val onBrightnessEnd: () -> Unit,
) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    @Volatile
    private var dragDirection = 0
    private var dragStarted = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    /** Brightness gesture mode: 0 none, 1 single-finger, 2 two-finger. */
    private var bstMode = 0
    private var bstActive = false
    private var twoBaseY = 0f

    private fun currentFrac(dy: Float): Float {
        val h = viewHeight()
        return if (h > 0f) -dy / h else 0f
    }

    /** Two-finger average Y (baseline/displacement for the two-finger vertical gesture). */
    private fun avgY(event: MotionEvent): Float {
        var sum = 0f
        val n = event.pointerCount
        for (i in 0 until n) sum += event.getY(i)
        return if (n > 0) sum / n else 0f
    }

    /** End the brightness gesture: clear state and invoke the end callback. */
    private fun endBrightness() {
        if (!bstActive) return
        bstActive = false
        bstMode = 0
        onBrightnessEnd()
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragDirection = 0
                dragStarted = false
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                bstMode = 0
                bstActive = false
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger down: the two-finger vertical baseline is ready; lock immediately if the two-finger gesture is available.
                if (!bstActive && canTwoFingerVertical()) {
                    bstMode = 2
                    bstActive = true
                    twoBaseY = avgY(event)
                    onBrightnessStart()
                    onBrightnessDelta(0f)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Lifting one of the two fingers ends the two-finger brightness gesture.
                if (bstMode == 2) endBrightness()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Locked two-finger brightness gesture.
                if (bstActive && bstMode == 2) {
                    onBrightnessDelta(currentFrac(avgY(event) - twoBaseY))
                    return true
                }
                // Locked single-finger brightness gesture.
                if (bstActive && bstMode == 1 && event.pointerCount == 1) {
                    onBrightnessDelta(currentFrac(event.y - downY))
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                // Horizontal page-flip (|dx| clearly greater than |dy|) takes precedence over vertical-swipe brightness detection.
                if (!dragStarted && abs(dx) > slop && abs(dx) > abs(dy) * 1.2f) {
                    dragStarted = true
                    dragDirection = if (dx < 0) 1 else -1
                }
                if (dragStarted && dragDirection != 0) {
                    val ratio = if (dx != 0f) dy / dx else 0f
                    onDrag(dragDirection, dx, ratio)
                    return true
                }
                // Single-finger vertical brightness: triggered only on the left/right 1/3 when the corresponding switch is on; once locked, no further page-flip/tap.
                if (event.pointerCount == 1 && !dragStarted && abs(dy) > slop && abs(dy) > abs(dx) * 1.2f) {
                    val x = downX
                    val w = viewWidth()
                    val zoneLeft = x < w / 3f && canLeftVertical()
                    val zoneRight = x > w * 2f / 3f && canRightVertical()
                    if (zoneLeft || zoneRight) {
                        bstMode = 1
                        bstActive = true
                        onBrightnessStart()
                        onBrightnessDelta(0f)
                    }
                    // Middle 1/3 or switch off: no brightness and no page-flip; a pure vertical drag does nothing.
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (bstActive) {
                    endBrightness()
                    return true
                }
                if (dragStarted && dragDirection != 0) {
                    onRelease(dragDirection, true)
                } else if (event.eventTime - downTime < 400L) {
                    onTap(event.x)
                }
                dragDirection = 0
                dragStarted = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (bstActive) endBrightness()
                dragDirection = 0
                dragStarted = false
                return true
            }
        }
        return false
    }
}