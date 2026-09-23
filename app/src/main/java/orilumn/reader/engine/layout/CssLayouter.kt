package orilumn.reader.engine.layout

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import orilumn.reader.engine.css.ComputedStyle
import orilumn.reader.engine.css.LightCssParser
import orilumn.reader.engine.css.ReaderStylesheets
import orilumn.reader.engine.css.StyleComputer
import orilumn.reader.engine.css.StyleSheet
import orilumn.reader.engine.html.MarkupElement
import orilumn.reader.engine.text.TypographicProfile
import kotlin.math.roundToInt

/**
 * S2 — CSS-driven span materializer (the legacy span branch, kept only as a test seam and a
 * source of low-priority UA style sheet constants).
 *
 * It emits a `SpannableStringBuilder` whose inline typography — font-size, color, bold/italic,
 * underline — comes from the real CSS cascade: every text leaf gets spans from its node's
 * [ComputedStyle], which [StyleComputer] resolves over the tree (UA sheet + author sheets +
 * inline styles) with inheritance and em/rem/% resolution. Blocks are simply newline-separated;
 * the paragraph-gap compensation spans (ParagraphGapSpan/ParagraphTopPadder) were removed because
 * the engine now produces a uniform CSS line box for every line, and 段间距 lives in the box path's
 * margins, not here.
 *
 * The production path now runs through [BoxChapterLayouter] + [ParagraphShapes]; this class is
 * retained so that `layout()` stays verifiable via `CssLayouterTest` and its [BLOCK_TAGS] set is
 * still referenced from the box engine, plus [uaSheetFromProfile] supplies heading defaults.
 */
class CssLayouter(
    private val profile: TypographicProfile,
) {
    /** Body font size acts as the root for `rem` and the default inherited size. */
    private val rootFontPx: Float get() = profile.bodyPx

    /**
     * Lays out a chapter into a [Spanned] using the real cascade.
     *
     * @param root the parsed body tree.
     * @param authorSheets parsed chapter stylesheets.
     * @param uaSheet the reading-profile UA sheet (see [uaSheetFromProfile]); empty means only author
     *   + inline styles participate.
     */
    fun layout(root: MarkupElement, authorSheets: List<StyleSheet>, uaSheet: StyleSheet = StyleSheet(emptyList())): Spanned {
        val styleMap = StyleComputer(rootFontPx, uaSheet, authorSheets).compute(root)
        val sb = SpannableStringBuilder()
        emitChildren(root, sb, styleMap)
        if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
        return sb
    }

    /**
     * Builds the reader-profile UA style sheet from the shared browser-default baseline single-sourced
     * in common (`ReaderStylesheets.ua()`, Z2). The theme-aware link tone is injected at build time by
     * replacing [ReaderStylesheets.LINK_COLOR_TOKEN]. UA is the lowest origin and would be overridden
     * by the book; the reader's 行距/段间距 live in the **UI layer** (see BoxChapterLayouter), not here.
     */
    fun uaSheetFromProfile(): StyleSheet =
        orilumn.reader.engine.css.uaSheetFromProfile(profile)

    /**
     * Builds the 排版主题 stylesheet (tier 42, the reader-app **theme** layer above the book) from the
     * shared theme presets single-sourced in common (`ReaderStylesheets.theme(...)`, Z2).
     * 原书设置 (original) returns null — no theme layer, the book's own CSS stands. The theme sheet is
     * below the per-item settings/UI layers (tier 43/44), so the reader's 行距/段间距/首行缩进 always
     * win over it; the preset's 缩进/段间距 are mirrored into [TypographicProfile] (see its builder) so
     * the UI layer renders them exactly.
     */
    fun themeSheetFromProfile(profile: TypographicProfile): StyleSheet? =
        orilumn.reader.engine.css.themeSheetFromProfile(profile)

    // ---- Emission ----

    private fun emitChildren(el: MarkupElement, sb: SpannableStringBuilder, styles: Map<MarkupElement, ComputedStyle>) {
        for (child in el.children) emitChild(child, sb, styles)
    }

    private fun emitChild(el: MarkupElement, sb: SpannableStringBuilder, styles: Map<MarkupElement, ComputedStyle>) {
        when {
            el.isText -> {
                val start = sb.length
                sb.append(el.text)
                emitInlineSpans(sb, start, sb.length, styles[el])
            }
            el.tag == "br" -> sb.append('\n')
            el.tag == "" -> emitChildren(el, sb, styles)          // de-shelled wrapper: children flow through
            el.tag == "img" -> { val s = sb.length; sb.append("　　"); emitUnderline(el, sb, s, styles) }
            el.tag in BLOCK_TAGS -> emitBlock(el, sb, styles)
            else -> {
                val start = sb.length
                emitChildren(el, sb, styles)
                emitUnderline(el, sb, start, styles)              // decoration is non-inherited: emit at the declaring element's range
            }
        }
    }

    private fun emitBlock(el: MarkupElement, sb: SpannableStringBuilder, styles: Map<MarkupElement, ComputedStyle>) {
        if (sb.isNotEmpty()) sb.append('\n')
        val start = sb.length
        emitChildren(el, sb, styles)
        emitUnderline(el, sb, start, styles)
        applyBlockDecor(el, sb, start)
        if (sb.length > start) sb.append('\n')
    }

    private fun applyBlockDecor(el: MarkupElement, sb: SpannableStringBuilder, start: Int) {
        if (start >= sb.length) return
        if (el.tag == "blockquote") {
            val q = (profile.bodyPx * 2).toInt()
            sb.setSpan(LeadingMarginSpan.Standard(q, 0), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Emits the element's own underline (text-decoration) over its full emitted range — decoration is
     * not inherited, so this must be applied at the declaring element, covering all descendant text. */
    private fun emitUnderline(el: MarkupElement, sb: SpannableStringBuilder, start: Int, styles: Map<MarkupElement, ComputedStyle>) {
        if (start >= sb.length) return
        if (styles[el]?.underline == true) {
            sb.setSpan(UnderlineSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Applies a text leaf's computed typography onto [start,end]. Spans carrying the cascade's values. */
    private fun emitInlineSpans(sb: SpannableStringBuilder, start: Int, end: Int, style: ComputedStyle?) {
        if (style == null || start >= end) return
        if (style.fontSizePx > 0f) {
            sb.setSpan(AbsoluteSizeSpan(style.fontSizePx.roundToInt(), false), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        when {
            style.bold && style.italic -> sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            style.bold -> sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            style.italic -> sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        style.colorHex?.let {
            runCatching { android.graphics.Color.parseColor(it) }.getOrNull()?.let { argb ->
                sb.setSpan(ForegroundColorSpan(argb), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (style.underline) sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    companion object {
        /** C2-P2b-4: 单源已迁 common（`orilumn.reader.engine.html.BLOCK_TAGS`），此处保留作委托。 */
        val BLOCK_TAGS: Set<String> = orilumn.reader.engine.html.BLOCK_TAGS
    }
}
