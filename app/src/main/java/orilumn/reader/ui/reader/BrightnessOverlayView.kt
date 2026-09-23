package orilumn.reader.ui.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View

/**
 * Brightness / eye-protection overlay for the reading page.
 *
 * Does not intercept touch: `onTouchEvent` always returns false, letting touches pass
 * through to the gesture layer of the container below, so page turning is unaffected.
 * It sits above the body text and the (optional) page-curl cover layer but below the settings
 * panel; `dimAlpha` lays black to dim (lower brightness), while `warmAlpha` lays a warm overlay
 * to reduce blue light (eye protection). Each repaints immediately on its own without relayout.
 */
class BrightnessOverlayView(context: Context) : View(context) {

    /** Dim overlay alpha (0..1): larger = darker. */
    var dimAlpha: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Eye-protection warm overlay alpha (0..1): larger = more intense warm color. */
    var warmAlpha: Float = 0f
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Eye-protection warm color (default warm orange-amber, close to a paper warm-light look). */
    var warmColor: Int = Color.rgb(255, 178, 125)
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    override fun onDraw(canvas: Canvas) {
        val dim = dimAlpha
        if (dim > 0f) {
            canvas.drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
        }
        val warm = warmAlpha
        if (warm > 0f && warmColor != 0) {
            val c = Color.argb(
                (warm * 255).toInt().coerceIn(0, 255),
                Color.red(warmColor),
                Color.green(warmColor),
                Color.blue(warmColor),
            )
            canvas.drawColor(c)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false
}