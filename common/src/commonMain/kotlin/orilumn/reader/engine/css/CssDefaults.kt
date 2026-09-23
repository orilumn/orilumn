package orilumn.reader.engine.css

/**
 * Initial values / inheritance rules for the properties the reader's cascade understands (pure
 * constants; no external dependency).
 *
 * These mirror CSS2.1 for the small property subset the engine typesets:
 *  - inherited: font-size, color, font-style, font-weight, line-height (as a factor);
 *  - non-inherited (initial value): text-indent = 0, text-decoration = none.
 */
object CssDefaults {

    /** Default line-height factor when nothing declares it (a readable baseline). */
    const val DEFAULT_LINE_HEIGHT = 1.5f

    /** Properties whose computed value passes to descendants when not redeclared. */
    val INHERITED = setOf(
        "font-size",
        "color",
        "font-style",
        "font-weight",
        "line-height",
    )
}