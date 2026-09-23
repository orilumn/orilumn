package orilumn.reader.engine.css

import kotlin.math.roundToInt

/**
 * Standard replaced-element sizing (CSS 2.1 §10.3.2 + §10.6.2 + §10.4 constraint table).
 *
 * Single source for `<img>` (and future `iframe`/`object`/`embed`) used-size computation,
 * shared by the block-replaceable path ([orilumn.reader.engine.laying.NormalFlowLayout]) and the
 * inline-`img` path (`ParagraphShapes`). Both paths previously sized the same element
 * differently (block expanded `auto/auto` to the containing block; inline clamped unconditionally
 * to it) — neither of which is what browsers do.
 *
 * Browser behavior implemented here:
 *  1. Ignoring min/max: `auto/auto` + intrinsic width → intrinsic size (§10.3.2/§10.6.2); one
 *     side specified + intrinsic ratio → the other side scales to preserve the ratio; both
 *     specified → as specified. No intrinsic info at all → `300px` (or the largest 2:1 rect
 *     fitting the containing block when `300px` does not fit — same proviso as the spec).
 *  2. Then the §10.4 min/max constraint table is applied with ratio preservation, so
 *     `max-width:100%` (our reader UA rule for paginated EPUB, author-overridable) shrinks
 *     oversized intrinsics to the containing block, while a book without any max-width rule
 *     overflows exactly like a browser (no silent shrinking).
 *
 * `%` on width/min/max-width resolves against [containingW] (the containing-block width, passed
 * by layout); `%` on height/max/min-height is indefinite for our always-auto-height containing
 * blocks (§10.6/§10.7) and is dropped to auto/none/0 at compute time, so it never reaches here.
 *
 * @param intrinsicW intrinsic width px (HTML attrs → binary bounds); null = unknown.
 * @param intrinsicH intrinsic height px; null = unknown.
 * @param containingW containing-block width px (the block's line-break width); ≥ 1.
 * @return used (width, height) px, each ≥ 1.
 *
 * NOTE (架构归位)：独占插图撑满版心（`width:100%` 等价语义）是用户层样式主题的工作，
 * 不属于浏览器内核——只要样式主题（传统/现代/自定义）没有注入此类规则，内核就按标准
 * 输出 intrinsic 尺寸，超宽才由 `max-width` 收敛，绝不多做。
 */
fun ComputedStyle.usedReplacedSize(
    intrinsicW: Int?,
    intrinsicH: Int?,
    containingW: Int,
): Pair<Int, Int> {
    val cb = containingW.coerceAtLeast(1).toFloat()
    // Specified sizes: % against the containing block, else the pre-resolved absolute px.
    // Negative width/height/max-width are invalid per CSS 2.1 → auto/none; negative min → 0.
    val cssW = (if (widthPct != null) widthPct / 100f * cb else widthPx)?.takeIf { it >= 0f }
    val cssH = heightPx?.takeIf { it >= 0f } // height % already dropped to auto at compute time
    val minW = ((if (minWidthPct != null) minWidthPct / 100f * cb else minWidthPx) ?: 0f).coerceAtLeast(0f)
    val maxW = (if (maxWidthPct != null) maxWidthPct / 100f * cb else maxWidthPx)?.takeIf { it >= 0f }
    val minH = (minHeightPx ?: 0f).coerceAtLeast(0f)
    val maxH = maxHeightPx?.takeIf { it >= 0f }

    val iw = if (intrinsicW != null && intrinsicW > 0) intrinsicW.toFloat() else null
    val ih = if (intrinsicH != null && intrinsicH > 0) intrinsicH.toFloat() else null
    val ratio = if (iw != null && ih != null && ih > 0f) iw / ih else null

    // Step 1: tentative (w, h) ignoring min/max (§10.3.2 + §10.6.2).
    var w: Float
    var h: Float
    when {
        cssW != null && cssH != null -> { w = cssW; h = cssH }
        cssW != null -> {
            w = cssW
            h = if (ratio != null) w / ratio else ih ?: w / 2f
        }
        cssH != null -> {
            h = cssH
            w = if (ratio != null) h * ratio else iw ?: 300f
        }
        else -> {
            // Both auto.
            if (iw != null) {
                w = iw
                h = if (ratio != null) w / ratio else ih ?: w / 2f
            } else if (ih != null && ratio != null) {
                h = ih
                w = h * ratio
            } else {
                // No intrinsic info: 300px, or the largest 2:1 rect fitting the CB when 300 is too wide.
                w = minOf(300f, cb)
                h = w / 2f
            }
        }
    }

    // Step 2: §10.4 min/max constraint table. w0/h0 are the tentative sizes from step 1
    // (normally the intrinsic size); scaling uses their ratio. min ≤ max enforced first.
    val w0 = w
    val h0 = h
    val effMaxW = maxW?.let { maxOf(it, minW) }
    val effMaxH = maxH?.let { maxOf(it, minH) }
    val violMinW = w < minW
    val violMaxW = effMaxW != null && w > effMaxW
    val violMinH = h < minH
    val violMaxH = effMaxH != null && h > effMaxH
    // Unconstrained caps for the min() sides of the table (none → +∞).
    val capW = effMaxW ?: Float.MAX_VALUE
    val capH = effMaxH ?: Float.MAX_VALUE
    when {
        violMaxW && violMaxH ->
            if (effMaxW!! / w0 <= effMaxH!! / h0) {
                w = effMaxW; h = maxOf(minH, effMaxW * h0 / w0)
            } else {
                h = effMaxH; w = maxOf(minW, effMaxH * w0 / h0)
            }
        violMinW && violMinH ->
            if (minW / w0 <= minH / h0) {
                h = minH; w = minOf(capW, minH * w0 / h0)
            } else {
                w = minW; h = minOf(capH, minW * h0 / w0)
            }
        violMinW && violMaxH -> { w = minW; h = effMaxH!! }
        violMaxW && violMinH -> { w = effMaxW!!; h = minH }
        violMaxW -> { w = effMaxW!!; h = maxOf(minH, w * h0 / w0) }
        violMinW -> { w = minW; h = minOf(capH, w * h0 / w0) }
        violMaxH -> { h = effMaxH!!; w = maxOf(minW, h * w0 / h0) }
        violMinH -> { h = minH; w = minOf(capW, h * w0 / h0) }
    }
    return w.roundToInt().coerceAtLeast(1) to h.roundToInt().coerceAtLeast(1)
}
