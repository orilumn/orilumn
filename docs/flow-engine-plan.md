# Custom CSS + Box-Model Typesetting Engine — Feature Plan

> **状态（2026-09-15）：已作为"当前架构基线"归档，不再是进行中的方案。** S0–S7 全部落地，
> 现引擎即此文档所述结构。本文件留作 **KMP/CMP + Skia 迁移（`.trae/documents/KMP+CMP迁移方案.md`）阶段 0–3**
> 移植纯逻辑层（css/html/盒子流/分页/ParagraphBreaker seam）时的架构依据，不再代表未来方向。
> 后续增量遗留项（如 `roadmap-epub2-3-html-css.md` 的 P2-D 链接导航）单独维护。

> Status: **S0 (CSS syntax parsing & stylesheet collection) — S7 (retire legacy) all shipped.**
> The migration is complete: the legacy `MarkupSpanBuilder` + `StaticLayoutBookLayout` + `StaticLayoutFactory`
> + `FontResolver`/`LocalTypefaceSpan` path is **deleted**; `MarkupSpanBuilder`'s live `ParagraphGapSpan` /
> `ParagraphTopPadder` were extracted to `engine/text/ParagraphSpans.kt`. Rendering and paging now route
> exclusively through the new box engine behind a single `ChapterLayouter` seam (`BoxChapterLayouter` →
> cascade → box geometry → per-paragraph `ParagraphShapes` → `BoxDrawableLayout` → `Paginator`), dispatching
> draw through a widened `DrawableBookLayout`. Stage history: S0 parser (in-house `LightCssParser`, no
> third-party dep), S1 cascade, S2 style application, S3 box-model layout (nesting + background/border; [Paginator]
> honours `break-inside: avoid` via a [BreakAwareBookLayout] seam — whole blocks move intact to the next page or
> fill the current one, oversized blocks tear; BoxPaginatorIntegrationTest), S5 CFI anchoring & bidirectional
> streaming ([BidirectionalPaginator] splits blocks around a block-level anchor; OpenAtAnchorTest), S6 font
> weight/style matching ([SubfamilyMetric] + [FontFaceMatcher] + [WeightedFontResolver], FontMatchingTest).**

## Context / Why

The current renderer (`engine/text/MarkupSpanBuilder` → `StaticLayoutFactory`) typesets through an
approximate model: paragraphs are simulated with newline + spacing hacks, and CSS is limited to 7
inline `style` properties. It is fine for plain fiction (Caizhou-style body text) but cannot express
true block layout (margins, padding, borders, nesting, page-break control). A real engine is
required to typeset books with tables, figures, captions, and heading anchoring.

**Key findings already verified in the codebase:**
- The paging layer is decoupled from rendering through `BookLayout`
  (`engine/paging/BookLayout.kt`), a pure line-level geometry interface. `Paginator`,
  page-flip, progress, and `PageRenderer`/`BodyPageView` only consume this interface — so a new
  engine can produce the same "lines" and reuse all surrounding logic untouched.
- Drawing currently hard-depends on `StaticLayout` (`PageRenderer` casts to
  `StaticLayoutBookLayout` and calls `layout.draw`). This seam must be widened first.
- `HtmlTreeConverter` currently discards `<style>` / `<link>` / `<head>`; these are prerequisites
  for real CSS sources and must be surfaced as a side channel.

## Architecture Decisions

### 1. StaticLayout stays as the text-shaping primitive (not the layout engine)
StaticLayout is an excellent **text shaper**: it handles line breaking, Chinese/English bidi,
inline styles, line-height, kerning, sub/superscript — the hard work. But it is **not** a layout
engine: it has no concept of a box, margin, padding, border, nesting. Keep it at the bottom as a
per-paragraph "text → lines" shaper; do all block-level layout in our own engine. See the
"ParagraphBreaker seam" below, which makes StaticLayout a swappable *implementation*, so it can be
replaced by an in-house breaker later without touching architecture.

### 2. CSS Sources: all three (inline, `<style>`, linked `.css`) — full cascade
Parse and cascade:
- UA defaults (derived from `TypographicProfile` — body size / line-height / colors / heading and
  quote scales), so existing reading preferences become the UA style layer.
- Author stylesheet (`<style>` + linked `.css` files from the EPUB).
- Inline `style` attributes (highest author origin).
Selectors match with CSS specificity `(id, class, type)`; cascade sorts by `(origin, importance,
specificity, order)`; `!important` reverses origin precedence. Inherited vs non-inherited property
resolution and initial-value table follow CSS2.1 semantics, with em/rem/% resolved to absolute px
at compute time.

### 3. True block-level box model
A box layout engine (`block`/`inline`/`line` boxes) drives vertical flow with real margin
**collapsing**, padding, border, background. Each block's text is still handed to the
ParagraphBreaker (StaticLayout) for line generation; the box engine owns coordinates, nesting, and
height accumulation. Content is flush with the book's reading preferences through the UA layer.

### 4. No legacy engine retention — direct replacement, stage-by-stage
The old `MarkupSpanBuilder` + `StaticLayoutBookLayout` path is replaced, not kept as an adjacent
engine toggled by a switch. Each stage leaves the app fully openable; the legacy path is deleted
once the new path matches it on default settings.

### 5. View modes: paginated and vertical scroll share one core
The core produces a continuous, ordered line stream (`block → line boxes with y`). Two consumers:
- **Paginated**: cut the line stream into fixed-height pages, honoring page-break rules.
- **Vertical scroll**: consume the stream directly as scrollable content + anchor points.
Scroll mode is a strict subset (no page cutting), essentially free once the line stream exists.

### 6. Style-cascade priority layers — reader intent wins over the book's own CSS
The rendered style of any element is the outcome of **five semantic layers**, in descending priority:

1. **UI (reader) spacing/typography — line-spacing, paragraph-spacing, block-density** — the highest layer and **always applied**. Each setting keeps its own scope and never bleeds into the others: line-spacing only adjusts `line-height`; paragraph-spacing only the body paragraphs' (`p`/`li`) margins; block-density only the remaining blocks' margins (headings / quotes / code / `div` containers / tables / …).
2. **Per-item reader settings** — a specific element + a single property (e.g. `h1 -> line-height: 1.6`). Second.
3. **Modern / Traditional / custom reading-theme presets** — restyle whole element families. Third.
4. **Original-book styles** — the EPUB's own stylesheet and inline styles. Fourth; together with the preset below it is the only layer that runs through the full cascade (origin / importance / specificity / order).
5. **Browser preset CSS** — the lowest UA fallback from `CssLayouter.uaSheetFromProfile()`, applied only when every layer above specifies nothing for a property. It provides base styles such as hyperlink underline with a theme-toned color, plus heading sizes and default margins.

The top **three** layers (UI / per-item settings / theme) do **not** participate in the CSS cascade computation — they are resolved first (by fixed layer priority, then each layer's own specificity/order) and **directly override** the cascade result produced by the bottom two layers (original-book styles + browser preset).

**Implementation decision (can standard CSS origins express this? — no, not cleanly):**
Standard CSS has only UA / user / author (+ `!important` flip) — three-ish effective origins. That cannot express four ordered layers (UI > per-item settings > theme > book) with a fixed precedence. So:

- The **book layer** (the original book) is computed by the real cascade engine ([`Cascade`]/`StyleComputer`) with UA defaults as its base — the cascade stays meaningful *for the book's own styles* (its specificity, `!important`, ordering all apply).
- The **three upper layers** (theme, per-item settings, UI) are small, user-authored "overlay" rulesets (selector → property → value). Rather than repeatedly overriding the book, they first **resolve among themselves** — by fixed layer priority (UI > per-item settings > theme) — into **one merged upper result** `{element × property → value}` (within a layer, its own specificity/order still applies). That single merged result is then applied **once, in one pass**, over the book's computed style. The book layer never participates in the merge; only its final computed value per property is replaced by the upper result when the upper group decided a value for it.
- Net effect: the reader can never be blocked by the book; the book keeps real cascade semantics underneath; the three upper layers are composed independently into a single authoritative override (no successive per-layer pashes); and no post-hoc mutation of a computed style is needed.

> This supersedes the "reading prefs = ordinary UA layer" wording in decision 2: reader intent is *above* the author origin, not below it. The `!important`-style idea is also narrower than this and is folded into the layered-overlay model here.

## CFI Anchoring & Bidirectional Streaming

This is a first-class engine capability.

- **Location model**: CFI → `(chapterIndex, charOffset)`. The engine does NOT parse CFI syntax; it
  works from integer char offsets. CFI parsing stays in the data layer.
- **Unit of layout is the block**, not the character/line.
- **Anchoring**: when a CFI resolves inside some block, layout starts from that **anchor block**
  (never splits a block). The first page therefore includes the same-block text before the anchor —
  content is never cut mid-sentence/paragraph, which is deliberately acceptable and reading-friendly.
- **Priority display**: from the anchor block, typeset *one page first and display it immediately*
  (lowest first-frame latency).
- **Then back-fill toward the chapter head (reverse)**: typeset the blocks before the anchor, continuously,
  so the reader can flip backwards all the way to the chapter-head page. Chapter head is the reverse
  boundary; only from that page does the chapter beginning show.
- **Forward streaming (toward the chapter tail, continuation)**: typeset the blocks after the anchor on demand.
- **Bidirectional caching**: a window of a few pages around the anchor is kept; flipping consumes
  the window first, and exiting the window triggers forward/backward continuation. No whole-chapter
  layout, so deep-jump performance stays acceptable.
- **Block-level reverse** (default): reverse pacing operates block-by-block, consistent with the
  atomic-block rule. (A finer line-level reverse is possible later but not needed initially.)

## Page-Break Control (break-*)

Blocks that should not be split/separated across pages are expressed as standard CSS **Paged Media**
properties in `ComputedStyle`, not as hard-coded special-cases in the layout code:

| Scenario (reader requirement) | CSS property |
|---|---|
| Heading and its first following paragraph stay together | `break-after: avoid` |
| Table caption and its table stay together | `break-after: avoid` |
| Figure and figure caption stay together | `break-after: avoid` + `break-inside: avoid` |
| An indivisible element must not be torn across pages | `break-inside: avoid` |

Pagination consumes `break-inside / break-before / break-after` (each reduced to `AUTO | AVOID`) to
choose break points: a block with `break-inside: avoid` moves whole to the next page when the
remaining height is insufficient (only if block height ≤ page height; otherwise it is allowed to
tear, matching browser behavior). This integrates cleanly with the existing `Paginator`
(`engine/paging/Paginator.kt`) which already retreats to paragraph boundaries.

## Proposed Modular Structure

```
engine/
  css/                  (new — pure JVM, unit-testable)
    CssSyntax.kt        own model: StyleSheet/Rule/Selector/Declaration
    CssParser.kt        interface; PhCssParser adapts com.helger:ph-css
    Selector.kt         selector parsing + specificity (id/class/type)
    Cascade.kt          origin/importance/specificity/order sorting
    ComputedStyle.kt    final resolved style (physical px, flat)
    StyleComputer.kt    markup tree → Map<MarkupElement,ComputedStyle>
    CssDefaults.kt      initial values + UA layer derived from TypographicProfile
    CssColor.kt         color parsing (migrate CssInlineResolver logic)
  laying/               (new — box layout)
    ParagraphBreaker.kt interface: text+style+width(+startOffset) → lines
    StaticLayoutBreaker.kt implementation wrapping StaticLayout (swappable)
    LayoutBox.kt        Block/Inline/Line boxes, edges, positions
    NormalFlowLayout.kt block flow + margin collapse + text shaping
    BoxBookLayout.kt    emits the same line-level geometry as BookLayout
    BoxDrawer.kt        draws lines + box backgrounds borders
  html/HtmlTreeConverter.kt   modify: surface <style>/<link> as side channel (ParsedChapter)
  text/MarkupSpanBuilder.kt   kept only for legacy reference, then removed
  text/FontResolver.kt        extend to WeightedFontResolver (alias+weight+style)
  render/PageRenderer.kt      widen to DrawableBookLayout (non-StaticLayout dispatch)
  BookDocumentController.kt   inject a ChapterLayouter; buildLayout/prepareRelayout go through it
```

Two key seams:

- **`ChapterLayouter`** (`engine/ChapterLayouter.kt`): one method
  `layout(markup, stylesheetBundle, profile, contentW, contentH) → LayoutProduct(BookLayout)`.
  `BookDocumentController` depends only on this. Old = `LegacyLayouter` (during transition), new =
  `CssLayouter`.
- **`ParagraphBreaker`** (`engine/laying/ParagraphBreaker.kt`): text shaping seam, implementing the
  CFI anchoring `startOffset` and being swappable (StaticLayout now, in-house later) with zero
  impact on surrounding code.

## Phased Plan (each stage keeps the app openable)

**S0 — CSS syntax parsing & stylesheet collection** (no rendering change)
- Add `engine/css/` model + `CssParser` interface, implemented by an **in-house `LightCssParser`**
  (deliberately no third-party dependency). Decision rationale (replacing the earlier ph-css plan):
  ph-css pulls a heavy transitive dependency tree, and its `CSSSelector.toString()` returned
  implementation-internal formats that broke round-trip/selector tests — an in-house tokenizer keeps
  the engine dependency-free and the parsed model honest. Coverage: `/* */` comments (incl. inside
  values), string literals, `url()`/function parentheses (respecting nested `()`), top-level
  at-rule skipping (`@media`/`@font-face`/`@import`/`@namespace`/`@page`/`@keyframes`/`@supports`/
  `@charset`) with correctly nested-brace bodies, `!important` detection, selector-list splitting on
  the top-level `,`, and unbalanced-brace fail-open tolerance. EPUB2/3 property *support* is a
  cascade/compute-time concern (see [ComputedStyle]), not a parse-time filter — the parser keeps
  every declaration as raw property/value strings.
- Modify `HtmlTreeConverter` to keep `<style>` text and `link rel=stylesheet` hrefs via a returned
  `ParsedChapter`, then have `BookDocumentController` load linked `.css` through `EpubResourceReader`.
- Deliverable: pure-JVM `CssParserTest` (comments, strings, parens, `@media`/`@font-face`/`@import`,
  `!important`, unbalanced braces). `convert()` keeps a backward-compatible delegate so
  `HtmlTreeConverterTest` stays green.

**S1 — Cascade computation** (no rendering change)
- Implement `Selector`, `Cascade`, `ComputedStyle`, `StyleComputer`, `CssDefaults`, `CssColor`.
- Deliverable: `Map<MarkupElement,ComputedStyle>` over a tree; JVM `CascadeTest` covering
  specificity, `!important`, origin order, inheritance, em/rem/% resolution, initial values.

**S2 — Style application (real cascade → old StaticLayout)**
- Add `CssLayouter`'s span branch: read `ComputedStyle`, emit a `SpannableStringBuilder` isomorphic
  to the legacy one, still laid out by `StaticLayoutFactory`.
- Deliverable: on default settings the new `Spanned` is near-pixel-identical to the legacy one
  (esp. with `useOriginalStyle=true`). This is the anchor that isolates box-model bugs from cascade
  bugs.

**S3 — Box model layout (`BoxBookLayout`)**
- Add `engine/laying/`: `LayoutBox`, `NormalFlowLayout`, `ParagraphBreaker` +
  `StaticLayoutBreaker`, `BoxBookLayout`, `BoxDrawer`.
- Widen `PageRenderer` to dispatch on a new `DrawableBookLayout`; unify coordinate handling with the
  existing `StaticLayoutBookLayout`.
- Deliverable: new engine lays out ordinary body chapters, emits `BookLayout` (line tops/bottoms/
  starts/ends), pages through the existing `Paginator`, and draws. Pagination breaks on default
  settings are statistically close to the legacy ones.

**S4 — Pagination & break control**
- Provide `isParagraphBoundaryLine` faithfully; `Paginator` gains awareness of `break-inside/
  break-before/break-after` to avoid splitting indivisible blocks (only when the block fits).
- Deliverable: `BoxPaginatorIntegrationTest` (empty blocks, long-paragraph fallback). Existing
  `PaginatorTest` + `FakeBookLayout` remain valid.

**S5 — CFI anchoring & bidirectional streaming**
- `ParagraphBreaker` accepts `startOffset`; `CssLayouter` anchors at the containing block, typesets
  one page first, then back-fills (reverse) and streams forward.
- Deliverable: open at any CFI mid-chapter flips both ways continuously; no whole-chapter layout on
  deep jumps. `OpenAtAnchorTest` (JVM, using a fake ParagraphBreaker to simulate reverse/forward).

**S6 — Font weight/style matching**
- Extend `FontResolver` → `WeightedFontResolver.resolve(alias, weight, italic) → Typeface?`,
  implemented in `ReaderActivity` against `FontRepository` by nearest-weight + style selection,
  keeping the existing `.exists()` SIGBUS guard.
- Deliverable: `Source Han` series selects the real Bold/Italic file for `font-weight: bold`;
  nearest-weight fallback when absent.

**S7 — Retire legacy**
- Delete `MarkupSpanBuilder` + `StaticLayoutBookLayout` once `CssLayouter` matches on defaults;
  `BookDocumentController` backfills only through `ChapterLayouter`.

## Open Questions (to decide during implementation)
- Whether reverse (toward the chapter head) pacing should ever be extended to line-level precision (needed only if a
  book requires page boundaries inside a paragraph when going backward).
- Precise `@media` / `@font-face` support scope in the first cascade version (likely limited;
  `@media` stripped early, `@font-face` passed through untouched).
- Whether scroll mode ships in the same milestone as pagination or later.
- CFI parsing scope: full EPUB Content Document CFI vs a pragmatic subset (path + char offset).

## Verification

- **Unit (JVM)**: `CssParserTest`, `CascadeTest`, `BoxLayoutMarginTest` (margin collapsing),
  `BoxPaginatorIntegrationTest`, `OpenAtAnchorTest`, `SelectorSpecificityTest`. Existing
  `HtmlTreeConverterTest`, `PaginatorTest`, `ProgressMapperTest` must stay green.
- **On device**: open books with tables/captions/headings; verify anchors open at the correct block,
  both flip directions stay continuous, page breaks respect `break-*`, and defaults match the
  current visual output. Build + install via `./gradlew :app:installDebug` after each stage.