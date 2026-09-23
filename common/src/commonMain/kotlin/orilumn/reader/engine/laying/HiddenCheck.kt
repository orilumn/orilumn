package orilumn.reader.engine.laying

import orilumn.reader.engine.html.MarkupElement

/** How an element's display:none hides it. Mirrors [Checker] from the cascade: a hidden element (or one
 *  inside a hidden ancestor) contributes no box and no text to either layout path. */
fun interface HiddenCheck {
    fun isHidden(el: MarkupElement): Boolean
}

/** No-op hidden predicate (never hides) — the default for callers without a cascade-aware check. */
val HIDDEN_NONE: HiddenCheck = HiddenCheck { false }