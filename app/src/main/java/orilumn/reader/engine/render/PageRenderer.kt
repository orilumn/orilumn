package orilumn.reader.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import orilumn.reader.engine.ChapterUnit
import orilumn.reader.engine.paging.PageSlice

/**
 * Page renderer: draws a "chapter page" or "cover" onto any Canvas (shared by current-page
 * rendering / curl front-back snapshots).
 *
 * Shares pixel-identical drawing with the retired `BodyPageView`: first paints the theme background, then
 * translates the row range of the whole-chapter [StaticLayout] within the page's top/bottom
 * margins, so the static page and the curl Bitmap align exactly (no offset from divergent drawing
 * paths).
 *
 * Coordinate system: `draw` assumes the target Canvas already covers the view; the content area is
 * `[marginLeft, viewW-marginRight] x [marginTop, viewH-marginBottom]`.
 */
object PageRenderer {

    /** 🔵 蓝：内容区边界调试框。粗描边以内容区边界为中线（内侧紧贴内容区边缘），与正文行内
     *  [orilumn.reader.engine.layout.DrawableBookLayout.alignPageTop] 平移无关，画在内容区坐标系里，
     *  所以每一页的位置都一致；任何有正文的页（含空页）都会绘制。 */
    private val debugPageRectPaint = Paint().apply {
        color = android.graphics.Color.argb(255, 50, 120, 255)
        style = Paint.Style.STROKE
        strokeWidth = 12f
    }

    /**
     * Draws a body-text page.
     * @param canvas Target canvas (background already painted, or this method paints it).
     * @param viewW Viewport width; viewH viewport height.
     * @param unit Owning chapter (with layout).
     * @param slice Target page.
     * @param profile Layout params (margins, centering).
     */
    fun drawPageSlice(
        canvas: Canvas,
        viewW: Int,
        viewH: Int,
        unit: ChapterUnit,
        slice: PageSlice,
        profile: orilumn.reader.engine.text.TypographicProfile,
    ) {
        // During anchor-based temp pagination, the current page is drawn from its own whole-block
        // layout; otherwise from the canonical (chapter-head) layout.
        val layout = (unit.tempRenderLayout ?: unit.layout) as? orilumn.reader.engine.layout.DrawableBookLayout ?: return
        // Content-area geometry
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(1)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(1)
        // Move the origin to the content area's top-left, then draw this page's row range in the
        // original coordinates.
        val save = canvas.save()
        canvas.translate(profile.marginLeft.toFloat(), profile.marginTop.toFloat())
        layout.drawPageSlice(canvas, slice.firstLine, slice.lastLineExclusive, contentW, contentH)
        // 🔵 Debug content-area frame drawn on top, in the content-area coordinate system (unaffected
        // by the layout's per-page line-top alignment), so it hugs the content edge on every page.
        if (DebugDraw.DEBUG_DRAW && DebugDraw.enabled) {
            canvas.drawRect(0f, 0f, contentW.toFloat(), contentH.toFloat(), debugPageRectPaint)
        }
        canvas.restoreToCount(save)
    }

    /**
     * Draws the cover.
     * @param cover Cover bitmap.
     * @param proportional true: proportional scaling centered (fills background around it); false:
     *   stretch to fill the content area.
     */
    fun drawCover(
        canvas: Canvas,
        cover: Bitmap,
        viewW: Int,
        viewH: Int,
        proportional: Boolean,
        profile: orilumn.reader.engine.text.TypographicProfile,
    ) {
        val contentW = (viewW - profile.marginLeft - profile.marginRight).coerceAtLeast(1)
        val contentH = (viewH - profile.marginTop - profile.marginBottom).coerceAtLeast(1)
        val left = profile.marginLeft.toFloat()
        val top = profile.marginTop.toFloat()
        val dest = if (proportional) {
            // Proportional: the target rect's aspect ratio matches the cover's
            val srcRatio = cover.width.toFloat() / cover.height.coerceAtLeast(1)
            val dstRatio = contentW.toFloat() / contentH
            val rect: RectF
            if (srcRatio > dstRatio) {
                val w = contentW.toFloat()
                val h = w / srcRatio
                rect = RectF(left, top + (contentH - h) / 2f, left + w, top + (contentH - h) / 2f + h)
            } else {
                val h = contentH.toFloat()
                val w = h * srcRatio
                rect = RectF(left + (contentW - w) / 2f, top, left + (contentW - w) / 2f + w, top + h)
            }
            rect
        } else {
            RectF(left, top, left + contentW, top + contentH)
        }
        // Draw once via Bitmap.createScaledBitmap (avoid scaling overhead on every draw)
        val scaled = Bitmap.createScaledBitmap(cover, dest.width().toInt(), dest.height().toInt(), true)
        canvas.drawBitmap(scaled, dest.left, dest.top, null)
    }
}