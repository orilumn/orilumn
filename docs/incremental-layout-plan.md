# Incremental / Light-Path Layout: Correctness + Tuning Performance Plan

> Status: **Proposal (documented before implementation).**
> Companion docs: [`flow-engine-plan.md`](./flow-engine-plan.md) (whole box engine), [`engine-html-css-capability.md`](./engine-html-css-capability.md) (CSS baseline).
> Scope: this doc covers (A) the broken incremental/light layout path and (B) a cache-layering design so
> reader typography sliders (line spacing, font size, letter spacing, …) stop forcing the whole light pass to re-run.

## 1. Problem

The reader renders a reopened chapter through the **incremental / light path**
(`BookDocumentController` disk-hit → `BoxChapterLayouter.incrementalLayoutForPage`), which produces a
`PartialDrawableLayout` and is also the path any typography change lands on after the first cache miss is
re-persisted. Two families of defects converge here.

### 1.1 Correctness: the incremental path drops the full box tree

`PartialDrawableLayout` is built with `boxes = leavesForDraw` — only the flat **leaf** paragraph boxes,
never the container boxes (blockquote / div / section / …). Verified via a temporary probe in
`BoxDrawer.emitBackground`:

- `blockquote { background: rgba(235,255,255,0.3) }` is **correctly computed** to `#4debffff`
  (`StyleComputer` logs it), but the blockquote is a container, and no leaf carries its background →
  nothing painted. (The book is a hand-built EPUB; the background is visible in other readers, so this
  is an engine defect, not a content one.)
- Even leaf-level backgrounds (`pre`) are not painted because the light path never fills
  `contentTop`/`contentBottom` — the probe showed `top=0 bot=0` for every background-carrying box, so
  the `BoxDrawer.emitBackground` guard skips them all.

The same underlying divergence explains the other reported incremental defects (page overflow,
blank lower page-half from the next page not being pulled up, code/body overlap): `rebuildLocalLines` +
`consecutiveLeafAdvance` reconstruct vertical geometry from a **lightweight margin/inter-block-gap
approximation** instead of the authoritative full box-tree geometry, and page boundaries are re-derived
from stale disk-table char offsets. Measured drift during normal reads: pages `UNDER-368..UNDER-824`
(blank) and `OVER+10..OVER+46` (overflow).

### 1.2 Performance: tuning a slider costs ~1s because the "light" pass re-runs everything

Device timing of a real line-spacing adjustment (cache miss → `LARGE-ANCHOR`), rust book ch4
(`blocks=271`):

```
relayout ch6 LARGE-ANCHOR  total=908ms   prepareLight=552ms  anchorShape=356ms
relayout ch6 LARGE-ANCHOR  total=1462ms  prepareLight=904ms  anchorShape=558ms
```

Breakdown of the same incremental layout on a cache **hit** (reopen) added as a profile line:

```
PROFILE inc layout n=46  shape=1189ms  rebuild=99ms  paginate=4ms  logIO=86ms
```

Findings:

- **Logging is exonerated** (≈86 ms), not the cause of the perceived slowness.
- The real costs are (a) the ANCHOR PAGE StaticLayout shapting (≈356–558 ms) and (b) **`prepareLight`
  itself (≈552–904 ms)** — which is meant to be the *cheap* structural pass.
- **`prepareLight` micro-profile (device, rust ch4, `blocks=271`):**

  ```
  PL side=271  styleEngine=28ms  enumerate=755ms  charStarts=131ms  total=914ms
  ```

  The dominant cost is **`enumerateBlockLeaves` (755 ms)** — the per-element `display` cascade
  (`resolveDisplayOnly`/`resolveHidden`) plus the tree walk — followed by **charStarts (131 ms)**.
  Building the `StyleComputer`/parsing the author CSS is **28 ms per call**. Both the leaf set, the
  charStarts and the parsed CSS are **invariant across every typography knob** — and although 28 ms is a
  small share of a single layout, a slider drag re-fires `prepareLight` many times per second, so the
  repeated 28 ms (plus the 786 ms) is exactly the "meaningless re-computation" a structure cache removes.

- So a typography adjustment pays full price to re-run the structural `enumerate`+`charStarts` **and**
  re-parse the same CSS even though neither the DOM block structure nor the CSS text can have changed
  when only a slider moved.

## 2. Why `prepareLight` is expensive (code account)

Per `prepareLight` invocation ([BoxChapterLayouter.prepareLight](../app/src/main/java/com/orilumn/engine/BoxChapterLayouter.kt)):

| Stage | Location | Depends on a typography slider? |
|---|---|---|
| build `StyleComputer` — **re-`LightCssParser().parse` every author sheet** + rebuild ua/theme/settings/ui layers | `styleComputerFor` | No (sheets come from the unchanged book CSS) |
| classify/hidden (per-element cascade for `display`) | `prepareLight` | No (`display` is spacing/kerning-independent) |
| `enumerateBlockLeaves` (leaf set) | `NormalFlowLayout` | No |
| `accumulateCharStarts` | `NormalFlowLayout` | No |
| lazy per-block: `StyleComputer.resolve` (full cascade) + `descendContentWidth/Left` (ancestor walks) | `LightPrepare.block` | width/left: no; `lineHeightRatio`: yes |
| anchor page shaping (`StaticLayout`) | `shapeBlock` | **Yes** (any font/kerning/line-height change re-breaks lines) |

Key invariant: `styleCache` and the `StyleComputer` are freshly constructed per `prepareLight`, so
nothing above is shared across calls even when only one slider moved.

## 3. Which knobs invalidate which cache layers

`LayoutParamKey` bundles every shaping-relevant field ([LayoutParamKey.kt](../app/src/main/java/com/orilumn/engine/text/LayoutParamKey.kt)), so *any* slider change
changes `paramHash` and invalidates the whole disk table. Not all layers actually depend on each knob:

| Knob | ① parsed CSS | ② leaf set + charStarts | ③ horizontal geometry (w/left) | ④ cascade values | ⑤ shaping (line breaks) |
|---|---|---|---|---|---|
| font size (`bodyPx`) | keep | keep | **recompute** (em/rem edges) | recompute | recompute |
| letter spacing (`letterSpacingEm`) | keep | keep | keep | keep | recompute |
| line spacing (`lineSpacing`) | keep | keep | keep | recompute (ui overlay line-height) | recompute |
| paragraph spacing | keep | keep | partial | recompute | recompute |
| user CSS (`userCssHash`) | keep (per bundle) | **maybe** (display can change leaf set) | recompute | recompute | recompute |

**Cross-knob stable layers:** ① (parsed CSS) and ② (leaf set + global char starts) depend only on
`(DOM, cssBundle, userCssHash)` — independent of *every* typography slider. ③ is stable for line
spacing / letter spacing / text scale but is currently entangled with ④ and not worth splitting first.

## 4. Proposed design: structural layer cache

### Tier 1 — structural layer = `BlockSkeleton` + parsed CSS/cascade structure (covers ALL sliders)

The typography-independent part of `prepareLight` is captured once per chapter and reused across every
subsequent call:

- `leaves: List<MarkupElement>` (result of `enumerateBlockLeaves`);
- `globalCharStarts: LongArray` (result of `accumulateCharStarts`);
- the **parsed author stylesheets** (`LightCssParser.parse` output) so `styleComputerFor` no longer
  re-tokenizes the same CSS on every call;
- the **cascade *structure*** (selector matching / rule winners for `display`-classification), which is
  identical across typography knobs — only the resolved *values* that depend on a knob change.

Cache key: `(chapter markup identity, cssBundle, userCssHash)` — no typography field. Because a
`display` change (the only thing that reorders the leaf set) is practically never produced by the
reader's typography path, the structural layer is effectively a per-chapter constant.

Lifetime: stored on `ChapterUnit`; a subsequent `prepareLight` reads it instead of re-running
`enumerate`/`charStarts` and re-parsing CSS. If `userCssHash`/`cssBundle` changes, optionally re-verify
whether the leaf set actually changed before rebuilding (cheap validation rather than unconditional
invalidation).

`prepareLight` becomes two distinct layers:

> **Structure layer** (`BlockSkeleton` + parsed CSS + cascade structure) — per chapter, typography-independent → **cached**.
> **Typography layer** (the ua/theme/settings/ui *values* — font sizes, line heights, overlays — resolved per call) + **shaping** — varies per knob → **recomputed**.

This makes the architecture clean and the win broad: a slider drag reuses the whole structure layer
(≈ 786 ms of `enumerate`+`charStarts` **plus** the per-call CSS-parse 28 ms) and only re-runs the small
value-resolution + shaping that actually depends on the moved knob.

### Tier 2 — the existing `LayoutParamKey` pagination cache (unchanged)

Keep the current `LayoutParamKey` cache as the param-sensitive tier. Any knob change still recomputes the
affected parts (cascade values where needed, and always shaping).

### Interim correctness fix (resolved)

The incremental path now paints container backgrounds identically to the full path: `buildBackgroundDrawBoxes`
supplies the container box tree (leaf border boxes ± container border/padding = padding box) to
`PartialDrawableLayout`, so blockquote fills render on both paths. Unified with `NormalFlowLayout.emit`'s
container formula (commits `3a06648` / `3117da0`); background drawing itself is shared via `BoxPageRenderer`
(`88fd45f`).

## 5. Open questions / trade-offs

- **`prepareLight` micro-profile answered** (rust ch4): `enumerateBlockLeaves` (~755 ms) + `charStarts`
  (~131 ms) dominate; CSS-parse/layer build is ~28 ms per call. **Both** the ~786 ms block structure and
  the ~28 ms CSS-parse are typography-invariant, and a slider drag re-fires `prepareLight` many times per
  second — so the structural layer caches all of them (see §4).
- **`display` invariance assumed**: the reader's typography path (font size / spacing / kerning / scale)
  never changes CSS `display`, so the leaf set is stable across every slider. `BlockSkeleton` invalidation
  is therefore keyed only on `cssBundle`/`userCssHash` changing — and since display changes are rare, the
  skeleton acts as a per-chapter constant.
- **③ horizontal geometry caching** (stable for line/letter spacing, not font size) is deferred — it is
  entangled with the cascade and adds risk beyond the `BlockSkeleton` win.
- **Cross-page container backgrounds** in the incremental path need a policy: reuse full box-tree geometry
  (exact) vs per-window approximation (cheap, ~0 extra shaping; boundary pages may clip a few px).
- Performance framing: shaping (⑤) is irreducible for any knob change; the goal is to stop paying for the
  structural `enumerate`+`charStarts` when the DOM/block structure is unchanged.

## 6. Verification

- JVM tests for Tier-1: key equality excludes typography fields; same skeleton reused across
  `prepareLight` calls with different spacing.
- Device: repeat the rust-book ch4 line-spacing adjustment; confirm `prepareLight` drops well below its
  552–904 ms baseline and the blockquote background now renders.

## 7. Unification status & boundary (as-built, 2026-09-13)

The two layout paths (full `BoxDrawableLayout`, incremental `PartialDrawableLayout`) were audited and
converged where safe. The goal: one low-layer computation/rendering so the paths are consistent and
layout bugs are triaged against a single mental model.

**Shared (unified) — implemented:**
- CSS parse + cascade + `StyleComputer` (engine shared; parsed sheets cached).
- Leaf enumeration + `globalCharStarts` (`ChapterStructureCache`).
- Text shaping (`ParagraphShapes` / `StaticLayoutBreaker` → `shapeLeaf`/`tempShape`).
- Page painting (`BoxPageRenderer`: `drawPageSlice`/`drawLeaf`/img/table — one implementation).
- Container background geometry formula (①): both paths now compute a container's background as its
  **padding box** = leaf border boxes ± container border+padding. `NormalFlowLayout.emit` (full) and the
  incremental `buildBackgroundDrawBoxes` use the same formula → pixel-identical blockquote fills.
- Leaf→background-owner cache (②): computed once in the structure pass, reused by window rendering.
- Public `NormalFlowLayout.flowBoxes(boxes, out)` — the single vertical-geometry entry; the full chapter
  `layout` already drives through it (behavior-neutral).

**Deliberately left as separate executors (the boundary):**
- `rebuildLocalLines` (incremental line builder) vs `NormalFlowLayout.emit` (full flow). Both derive
  vertical geometry from the shaped lines; their **margin/gap formula is already shared** via
  `NormalFlowLayout.consecutiveLeafAdvance`, which exactly mirrors `emit`. The duplication is the
  *executor* (boilerplate), not the *formula*.
- Folding the incremental line builder fully onto `flowBoxes` would require reconstructing the **window
  container-tree fragment** (nested leaf-under-container boxes so container margins apply). A flat
  leaf flow through `flowBoxes` incorrectly drops container margins (blockquotes would butt against
  neighbors), so it was **reverted** rather than shipped. This executor-level fold is high-risk
  (touches pagination) and was parked for per-bug triage.

**Commits:** `0d75b0c` (BlockSkeleton) `9b5847a` (parsed CSS) `88fd45f` (drawPageSlice dedup)
`628197e` (blockquote background) `3a06648` (① geometry formula) `3117da0` (② owner cache)
`16ca1d7` (flowBoxes) `4d9bed4` (log cleanup).