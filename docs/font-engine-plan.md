# Engine Font/Browser-Core Plan (font-family / font-weight / font-style)

**Status:** planning phase. Scope = give the engine the *browser-core* typography capability
(font-family, font-weight, font-style at shaping and measurement time) so that replacing a body
font never leaks into headings/code, and bold/italic use real matching faces instead of the
system's synthetic `StyleSpan`. Reader-side concerns (which font is picked for body/title/code, the
picker UI) live **above** the engine and only *inject* a pairing.

## Role split
- **Engine (browser core):** resolve `font-family` → a concrete typeface, honor `font-weight` /
  `font-style`, and shape/measure with it consistently between geometry (`StaticLayoutBreaker`) and
  drawing (`ParagraphShapes`). Engine does not know "body/title/code".
- **Reader:** implements the engine's `FontPairing` seam to decide body/title/code aliases and user
  font files (via `FontFaceMatcher`/`SubfamilyMetric`); empty = follow the book/system.

## Phases

### Phase A — engine typeface seam + browser-ish parsing (this pass)
- `ComputedStyle` gains `fontFamily` (raw CSS `font-family` value) and `fontWeight` (int 100–900,
  default 400), while keeping the convenience `bold`/`italic` flags.
- New `FontPairing` seam: `fun interface FontPairing { fun resolve(family: String?, weight: Int, italic: Boolean): Typeface }`
  plus a default `SystemFontPairing` (`Typeface.create(family, style)`), exposed so the reader can swap it.
- `ParagraphShapes.shapeOf` and `StaticLayoutBreaker.breakLines` take a `FontPairing` and pick the
  block's base typeface from `fontFamily`/`fontWeight`/`fontStyle`. Geometry and drawing must use the
  **same** pairing so line widths (thus breaks) never drift.
- Keep inline bold/italic as synthetic spans for now (real per-run faces are Phase B).

### Phase B — per-run real weight/italic, and spanned geometry measurement
- Shape the paragraph as runs with their own (fontSize, weight, style, family) so a bold run measures
  and draws with a real bold face (not `StyleSpan`), for both geometry and drawing.
- `breakLines` accepts the run/spanned text so the measured width reflects real glyph advance.

### Phase C — reader injection (classification + user fonts)
- Reader implements `FontPairing`: body→`fontBody`, heading→`fontTitle`, code→`fontCode`; for a user
  font family pick the nearest real weight/italic face via `FontFaceMatcher`.
- Engine untouched; it simply renders whatever pairing it is given.

## Invariants
- Geometry and drawing share one `FontPairing`/selection rule → `globalCharStarts` stable.
- Font changes affect glyph advance only; pagination table is parameter-keyed (LayoutParamKey already
  includes fontBody/fontTitle/fontCode), no extra invalidation needed.
- Bump disk `PaginationCacheStore.LAYOUT_VERSION` only if char/line geometry semantics change.