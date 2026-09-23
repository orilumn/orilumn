# Project Status

> Keeps a running log of significant milestones for the Orilumn reader engine. Supersedes
> everything marked done; each section reflects a completed stage.

## 2026-09-15/19 — KMP+CMP migration (S01–S35) + C-series platform convergence: whole project closed

**Scope.** The full Kotlin Multiplatform migration (`docs/KMP迁移-分步计划.md` S01–S35, atomic
one-commit-per-step) landed 2026-09-15/16, then the C-series orchestration convergence
(`docs/C1-实施步骤.md`, C1-0…C1-3) closed on 2026-09-19. Per `docs/平台一致性整改方案.md` §7 the
mother doc's rows **A/B/D/E/Q1/C are all closed**: one style source, one shaper/breaker, one font
pool, one image decode, one pagination table/store, one orchestration host shared by tablet and
desktop. Both long-deferred TODO items (阅读定位 D / 三线程优先级 F) were consumed in the same pass.

- **Module shape (final)** — `:common` (KMP: androidTarget+jvm; engine/css, paging, layout,
  laying, html, epub, settings, font, text + io/log expect/actual), `:engine-skia`
  (SkParagraph shaping, `SkiaParagraphBreaker` + `BookEngineSwitch` dual-engine switch,
  `LineWindowDrawer`), `:shared-ui` (CMP shelf/reader/settings+TOC, S26–S29), `:desktopApp`
  (JVM shell hosting `App()`, S32), `:app` (slimmed to a thin host, S31).
- **JVM-only deps replaced** — jsoup→fleeksoft Ksoup (S10–S12), `javax.xml`→self-built mini XML
  DOM with XXE guard (S13–S16), `org.json`→kotlinx.serialization (S17–S18),
  `java.util.zip`→okio (S15), Room→SQLDelight v4 with shared KMP shelf db (S33/S34b),
  `WifiFontServer`→Ktor in `common/net` (S35), `util/FileLogger`→common `Logger` (S31-cleanup).
- **C1-0 dual-source unification** — incremental/temp shaping now runs on Skia single-source;
  the Android `StaticLayout` shaping primitive is retired (remaining mentions are historical
  comments only; geometry is plain data on `ParagraphShape`).
- **C1-1 pagination single-source** — table + codec + okio `FileSystem` store into `common`;
  same bytes/filename/`LAYOUT_VERSION` invalidation for both hosts; `PaginationCacheTest` 7 cases.
- **C1-2 orchestration into common** — `TempNavigation`/`FragmentAnchors` pure logic, window
  state machine, save-order contract, and the **F>A>B1>B2>P priority contract with injectable
  dispatchers** (`BookDocumentController` KDoc `:66`, `injectedCanonicalDispatcher`); desktop
  consumes the identical contract. TODO D: `onSaveProgress` finalizes the temp table before
  persisting (canonical char authority) + seam/handoff canaries; TODO F: contract written, no
  longer "missing".
- **C1-3 desktop same host** — `DesktopReaderHost` write-through on the shared `PaginationCacheStore`
  (same `LayoutParamKey`, hit reports disk page count), background canonical/whole-book prefill
  **not yet enabled** on desktop (single-chapter lazy load covers the open mode); enabling it must
  reuse the same dispatcher split.
- **Verification** — `:app:testDebugUnitTest` 122/122 + `assembleDebug`, `:common:jvmTest` 347,
  `:engine-skia:jvmTest`, `:shared-ui:jvmTest`, `:desktopApp:test` 4/4 all green.
- **Deferred / next** — phase **L (iOS)** not started (no `iosApp`; needs iosMain actuals for
  mDNS/paths/fonts); device re-verification of TODO D (save-char vs restore-char) still unrecorded.

## 2026-09-18 — Layout Priority & Concurrency P-series (P1–P14): all 14 landed + P9 corrective fix

**Scope.** The responsiveness/priority/concurrency roadmap
(`docs/layout-priority-and-responsiveness-improvements.md` P1–P14, design spec
`docs/layout-priority-and-concurrency-design.md`) completed in one day: priority contract F>A>B1>B2>P,
unified miss flow (U0–U9), bounded contiguous sliding window ±1 with discard-and-restart, unified
promotion-on-leave, and the 4–5 core concurrency layout. All items verified by probes T1–T8 and both
regression suites (`:common:jvmTest`, `:app:testDebugUnitTest`).

- **P1 birth-window atomization (R1)** — the anchor page is shaped into locals, then the commit
  (bindInProgress + fill window + setCurrentTempPage) is atomic under `tempStateLock`; flips `await`
  the birth signal and re-read state. Probe T1 `TempBirthWaitRegressionTest`.
- **P2 whole-book epoch (R4)** — `prepareRelayout` bumps `layoutEpoch`; B1 carries it and skips stale
  segments; the params-settled point re-dispatches B2 with the current hash. Probe T3.
- **P3 per-chapter canonical slots (R3)** — `canonicalJobs[chapter]`: same-chapter new params cancel,
  cross-chapter queue-not-kill, so a just-tuned chapter's table always lands. Probe T4.
- **P6 jump gate (jump responsiveness)** — single-slot `JumpGate`: cancel/dedup/epoch-guard + gold
  top-edge load line; a seek-toc-burst converges on exactly the last landing. Probe T2.
- **P7 timely abandon** — `fullLayout` gains a per-block `checkpoint` (foreground default no-op);
  B1/B2 pass `ctx.ensureActive()`, cancellations surface as `CancellationException`, never a truncated
  table; the large-chapter anchor shape is skipped when the epoch went stale. Probes T5 + P13's.
- **P11 unified promotion on leave** — all three jump paths + flip-leave finalize the old chapter's
  temp session (`finalizeOnLeave`), closing the "re-entry lands on the old anchor" residue. Probe T8.
- **P10 bounded sliding window** — temp pagination is a contiguous ±1 window with far-end eviction and
  `WinUnit.{Fwd,Bwd,Pair}` torn-pair atomicity; window-outside = discard-and-restart; exposed two latent
  bugs (`locateTempPosition` (blockStart,charStart) key; `stepTempPrefill` neighbor liveness/trim).
  Probe T7 `WindowSlideDiscardProbeTest`.
- **P14 cache lifecycle + LRU** — `PaginationCacheStore` trims each book's directory to 32
  least-recently-used tables; `paramHash` completeness verified (useOriginalStyle + userCssHash slot).
- **P12 scan ordering** — B2 follows reading direction × distance (`orderRemainingChapters`).
- **P4/P5 preflight** — neighbor chapters parse + light-prepare off the flip thread; open-book first
  screen becomes a promote. Probes `CrossChapterPreflightProbeTest`/`OpenBookPreflightProbeTest`.
- **P13 chunked canonical (U6k)** — `fullLayoutChunked` slices shaping across a low-priority chunk pool,
  equal to sequential canonical by construction. Probe T6 `ChunkedCanonicalEquivalenceProbeTest`.
- **P8 head lift (R2)** — near-head anchors (≤ `HEAD_START_BLOCK_LIMIT`=100) re-anchor at the true
  chapter head so temp and canonical share the same head source. Probe `HeadLiftProbeTest`.
- **P9 R5 corrective fix (landed same day)** — temp sessions now shape from a
  **frozen** `ip.profileSnapshot` captured at `startAnchorStream`, never the live controller
  `profile`; a flip racing a typography tune can no longer flash one mixed-param frame. (Documented in
  the plan as "documented, fix deferred"; implemented once Phases 1/2/3 were live.)

**Full table below** (2026-09-14) covers the list-marker `liOf` generalization; the detailed per-item
logs live in `docs/incremental-vs-full-layout-investigation.md` ("Implementation Log").

## 2026-09-14 — List markers attach to `<li><p>…</p></li>` (first leaf per item only)

**Issue.** Books that wrap every list-item body in a `<p>` (Manning/calibre exports — the Kotlin in
Action book here is the concrete case: 405 of 476 `<li>` are `<li><p class="list">…</p></li>`)
rendered with no bullets and no hanging indent: only per-chapter summary lists, whose `<li>` holds
direct text, looked right. The book CSS is valid; a browser puts the marker on the `display:list-item`
`<li>` regardless of its children.

**Root cause.** `ListMarkers.liOf` only attributed two leaf shapes as marker carriers — a `li` text
leaf, or a nested `<li>`'s leading anonymous `#text` leaf. In `<li><p>…</p></li>` the layout leaf is
the `<p>` (a block child), so `liOf(p)` returned null, `listMarkerFor` yielded null, and
`ParagraphShape.drawListMarker` was a no-op: no bullet, no hanging gutter, just the raw `ul` padding.

**Fix.**
- **`liOf` generalizes** to the nearest `li` on the leaf's ancestor chain (itself included), so a
  `<p>` (or any block child) inside a `<li>` now belongs to that item.
- **New `ListMarkers.firstCarrierSet`**: for each `<li>` in a document-ordered leaf list, exactly the
  **first** leaf carries the marker. `<li><p>a</p><p>b</p></li>` draws one bullet (on `a`); the second
  `<p>` renders as a plain paragraph, matching CSS where the bullet belongs to the item box, not to
  each nested block. Nested `<ul>` leaves claim their own inner items, so inner lists keep their
  bullets.
- **Both paths gate on the carrier set**: heavy (`fullLayout` → `firstCarrierLeaves(prepare.leaves)`)
  and light (`LightPrepare.firstCarrierLeaves`, lazy over `markupLeaves`) pass it into `listMarkerFor`,
  which now returns null for non-carrier leaves. This also fixes a latent mis-attribution where stray
  trailing inline text after a `<p>` in an item would have claimed the bullet.

**Tests / build.** `ListMarkersTest` +3 cases (`liOf` ancestor walk, first-leaf-per-li gating,
nested-list carriers); full `:app:testDebugUnitTest` green; `compileDebugKotlin` green. Marker
placement on device pending (uikit).

## 2026-09-13 — Shaper: drop the trailing phantom blank line in pre/code blocks

**Issue.** The tablet's Rust book (`book_47`) ships a *different* stylesheet than the repo's
`rust_cn.epub`: `pre{margin:0.5rem 0; padding:0.5rem}` (a plain **leaf** — no `code{display:block}`),
`.filename{display:block; padding-top…; background}`, and `.filename + pre{margin-top:0}`. Because `pre`
is a leaf there, the container-margin fix (above) does not touch it — yet every code block rendered with a
large empty background **below** the last line ("大片空白"), confirmed by pixel measurement (~one line-height
≈ 53–67px) and by an on-device `PREGEOM` probe. Root cause: every `<pre><code>…\n</code></pre>` ends in a
trailing `\n`, and the *draw/light* shaper (`ParagraphShapes` → `StaticLayout`) emitted it as a final
**empty line** (`lineStart == lineEnd`, full line-height). The heavy measure shaper
(`StaticLayoutBreaker`) already skips such lines, so drawing/backgrounds were a line-height taller than
geometry.

**Fix.** `ParagraphShape` now trims lines **after the last non-empty one** (`trimmedLineCount`). Kept lines
are always a contiguous prefix `0..last`, so `getLineTop/Start/End(k)` still index the same StaticLayout —
no char loss; blank lines *between* code are preserved. Bumps `LAYOUT_VERSION` (10→11) so stale pagination
tables re-flow with the corrected geometry. Compiles; geometry suites green. On-device pixel confirmation
was partially blocked by a pre-existing `ReaderActivity.onContainerTouch` crash under programmatic input,
so the tablet should be re-checked by hand.

## 2026-09-13 — Box model: container margins + border/padding now follow CSS in vertical flow

**Issue.** In the rust book ch1, `blockquote`/`.note` boxes and `pre` code blocks carried a visible
`background-color`. Two stacked defects made every non-text block's vertical layout deviate from CSS:
(1) `pre > code { display:block }` turns `pre` into a **container** box, and the container branch of
`NormalFlowLayout.emit` never consumed its **own margin** (an "entered top-aligned" shortcut), so blocks
sat flush against the paragraph above; (2) containers also never advanced the cursor by their **own top
border+padding** (nor past their bottom border+padding), so a container's background box was derived as
`firstChildTop − edgesTop`, extending *above* the preceding block's content bottom and painting over the
text above, while a following sibling was pulled up into the container's padded bottom. Together they
read as "negative margin-top / overlap" plus a "large bottom blank" and generally scrambled margins.
Non-container leaves already consumed their own edges, so plain `p`/labels were unaffected — only
containers (`.note` with `<p>`, `pre` with a `display:block` `code`) showed it.

**Fix (single-source, both layout paths — CSS box model).**
- `NormalFlowLayout.emit` container branch now mirrors a leaf: it consumes its **own collapsed top margin**
  (`collapse(margin.top, prevBottomMargin)`), advances the cursor by `border.top + padding.top`, lays out
  children, then extends the cursor past `border.bottom + padding.bottom` and returns its **own bottom
  margin** for the next sibling to collapse with. Margins, borders and padding all now participate in the
  flow exactly as CSS specifies.
- `NormalFlowLayout.consecutiveLeafAdvance` (the light/incremental gap) reproduces that full gap: the LCA
  sibling margin collapse + top edges/margins of every container on the next-leaf path + bottom edges of
  every container on the prev-leaf path, so light spacing mirrors heavy byte-for-byte (held by
  `BoxSharedGeometryTest`).

**Tests / build.** Added `preContainerPaddingDoesNotOverlapPrecedingText` and
`followingSiblingDoesNotInvadeContainerBottomPadding`; the old `nextInsideContainerDropsPrecedingMargin`
was corrected to `nextInsideContainerCollapsesPrecedingMargin` (CSS collapse → 40). Geometry suites
(BoxShared/BoxLayoutMargin/BoxLayoutFlow/BoxNested) green; `:app:assembleDebug` + `installDebug` to tablet.

**Verified on device.** Rust book ch1: `pre` blocks and `.note` boxes now render with a clean CSS margin
gap to the text above, **no overlap**, and symmetric healthy padding (residual, minor bottom-heavy inside
code blocks traces to a trailing `\n` in the EPUB's `<code>` creating one extra line — a known, separate
minor quirk, not the reported bug).

> Heads-up: the 10 pre-existing test failures on `HEAD` (27522a3) were stale expectations after the
> color parser switched to `#AARRGGBB`; the engine was already correct. Updated CascadeTest /
> BoxNestedLayoutTest color assertions to `#AARRGGBB`; they now pass. The remaining
> `BoxPathConsistencyTest.img is a replaceable block` asserts img-as-block, but the engine deliberately
> treats `<img>` as default-inline — a separate stale expectation, deferred.

## 2026-09-13 — Incremental/light path: cache typography-independent structure (prepareLight ~900ms → ~3ms)

**Issue.** Tuning a typography slider (line spacing / font size / letter spacing) forced the whole *light*
prepare to re-run every time: device profile of `prepareLight` on the rust book ch4 showed
`enumerate=755ms + charStarts=131ms + styleEngine(CSS parse)=28ms ≈ 914ms`, and during a slider drag
`prepareLight` re-fires many times/sec. All of it is typography-invariant (depends only on DOM + CSS +
`display` classification), so it was pure re-computation.

**Fix (two layers).**
- **Block structure cache** (`ChapterStructureCache` on `ChapterUnit`): memoizes the light-path leaf set
  (`enumerateBlockLeaves`) + `globalCharStarts`, keyed by `cssBundle` + `useOriginalStyle`. Survival
  of `invalidateLayout` (which fires on every param change) is deliberate — the key self-validates only
  against structural inputs.
- **Parsed CSS cache**: the parsed author stylesheets (`LightCssParser` output) are stored on the same
  cache; `styleComputerFor` accepts pre-parsed sheets and the light path skips re-tokenizing CSS. The heavy
  `prepare()` path is unchanged (still parses when none are supplied).

**Result (measured on device).** `prepareLight` cache-hit: original ~914ms → Phase-1 (~40ms, leaves/charStarts
cached) → Phase-2 (`3–12ms`, parsed CSS reused). Rendering verified with no regression.

**Design doc.** `docs/incremental-layout-plan.md` §1–§5 (micro-profile + knob-invalidation table + two-layer
cache). Commits `0d75b0c` (BlockSkeleton) `9b5847a` (parsed CSS).

**Not yet done.** The interim *correctness* fix (blockquote/container backgrounds lost in the incremental
path because `PartialDrawableLayout` gets leaf boxes only) is documented in the plan §4.3 as pending a
decision; the two `drawPageSlice` implementations remain duplicated.

## 2026-09-12 — Browser-consistent list hanging: text at contentLeft, marker hangs left

`list-style-position: outside` now matches browsers: the item text starts exactly at `contentLeft`
(= the `ul/ol` `padding-left`), and the marker hangs in the gutter to its left (drawn at a negative
frame x) instead of pushing the text by marker-width. So `ul,ol{padding-left:2em}` yields text
indented 2em (≈2 CJK chars), nested `ul/ol` each add their padding via `contentLeft` accumulation
(2em per level). `inside` keeps the marker inline (first line indented, wrapped lines return to
contentLeft). Both draw paths widen their leaf clip (`listMarkerClipLeft`) so the hanging marker is
not clipped. Emphasizes the engine principle: box model + cascade mirror a normal browser; EPUB-only
defaults belong in an upper layer.

**Tests / build.** Pure-JVM suites green; `:app:installDebug` to tablet.

**Pending to verify on device.** `ul{padding-left:2em}` → text at 2em, marker hanging left; nested
lists indent 2em per level.

## 2026-09-12 — Box model: consume block-level horizontal offsets; list markers honored

**Issue.** `contentLeft` was never accumulated from block horizontal edges (padding/margin/border-left)
and text was drawn at x=0, so book CSS like `ul,ol{padding-left:2rem}` had no effect and list markers
sat tight against the text.

**Fix.**
- **Horizontal accumulation (single-source).** New `NormalFlowLayout.descendContentLeft` mirrors
  `descendContentWidth`: each box's border-box left = parent's + ancestor left border/padding + own
  left margin. `buildBoxTree` threads `childLeft` top-down; light `LightPrepare.block(i)` uses the same
  descent; `tableRowLayoutFor` bakes the row left into cell x. Only `contentLeft`/draw-x change —
  `contentWidth`, line breaking and `globalCharStarts` are untouched (char authority preserved).
- **Render at contentLeft.** Both `BoxDrawableLayout.drawLeaf` and `PartialDrawableLayout.drawLeaf`
  (incl. replaceable/img) x-translate by `contentLeft + own left edges`; page reading margin applied
  outside.
- **List refinements.** Sizeable vector-drawn bullets (disc/circle/square) instead of font glyphs
  (fixes "bullet looks like a period"); marker→text gutter = marker width + gap (0.6em) with marker at
  the item content start; li-level `list-style-type`/`list-style-position` override; `ol start/reversed`
  attrs preserved. Nested indentation is now fully CSS-driven via contentLeft.

**Tests / build.** Pure-JVM `BoxLayoutMarginTest`/`BoxNestedLayoutTest` assert `contentLeft` accumulates
(ul padding → li at that x; plain p stays 0) and existing suites stay green; `:app:installDebug` to tablet.

**Pending to verify on device.** Confirm `ul{padding-left}` now shifts whole lists right, markers gap
from text, nested lists indent per level, and table/images honor padding.

## 2026-09-12 — List markers: EPUB-standard ul/ol rendering

**Issue.** `ul`/`ol`/`li` were laid out as plain blocks with no markers, so lists rendered as
indistinguishable body paragraphs. Needs: bullets, ordered numbering, `start`/`reversed`, `inside`/
`outside`, per-level nesting reset, and list items must NOT receive the body first-line indent.

**Design (marker as overlay, char stream untouched).** Rather than prefixing marker text into the
character stream (which would disturb heavy/light `globalCharStarts`), markers are a visual overlay
drawn per `ParagraphShape` (the engine's `BulletSpan`): the `<li>` text reserves a gutter via a
`LeadingMarginSpan.Standard(first, rest)`, and the marker glyph is drawn in that gutter at the first
line's baseline. Char accounting (`visibleCharAdvance` / `textLength` / `globalCharStarts`) is
untouched, so pagination/selection/progress stay consistent.

- **`ListMarkers` (pure JVM)**: node attribution (`liOf`: `<li>` leaf, or a nested `<li>`'s leading
  anonymous `#text`), `list-style-type` → disc/circle/square + decimal/decimal-leading-zero/
  alpha/roman (default disc/decimal; `none` → no marker), `list-style-position` inside/outside,
  `start`/`reversed`, per-level `level`, bijective base-26 alpha, subtractive roman.
- **Two paths share** `shapeLeaf(listMarkerFor(...))`: heavy reads the whole-chapter style map, light
  the lazy resolver, so both see the same `ul/ol` list-style. Marker drawn in both `BoxDrawableLayout`
  and `PartialDrawableLayout`.
- **`HtmlTreeConverter`** now keeps `ol` `start`/`reversed`/`type` (boolean `reversed` via `hasAttr`).
- **`uiSheetFromProfile`**: first-line indent restricted to `p`; `li` keeps 段间距 but no text-indent.
- `ComputedStyle`/`StyleComputer` parse `list-style-type`/`list-style-position`.

**Tests / build.** New pure-JVM `ListMarkersTest` (attribution, numbering, formats, defaults) green
(Robolectric still unavailable in this env); `:app:compileDebugKotlin` green; `installDebug` OK.

**Pending to verify on device.** Bullet/ordered rendering, `start`/`reversed`, `inside`/`outside`,
nested indentation and renumbering, `list-style-type:none` (no marker/indent), and that `li` is no
longer first-line-indented.

## 2026-09-12 — Incremental / temporary pagination: line-based fill + stable window

**Issue.** Both the disk-incremental and anchor temporary pagination exposed two defects: (1) the page-break points were unstable while flipping (the same page's content changed between flips); and (2) with "whole block never torn" packing, a code block that did not fit was moved whole to the next page, leaving a large blank at the bottom of the page / a clipped bottom half-line.

**Fixes (S5 temp-table invariant relaxed: no longer "whole blocks never torn").**
- **Disk-incremental self-consistency**: `incrementalLayoutForPage` now tiles pages by `contentH` from its own `localLines` geometry (pages are continuous, neither leaking nor overflowing), instead of hard-applying the disk table's char boundaries; `ensurePageRangeShaped` records the window so flipping inside the window does not re-layout (stable page-break points).
- **Temp-table line fill**: `shapeTempPageForward` / `shapeAnchorPageForward` changed from whole-block to accumulating real per-line heights up to `contentH`, and may break lines inside a code block; added `ForwardedPage` and the line-level continuation cursor `forwardFromLine`. Only replaceable blocks (img / table rows) are kept whole.
- Keeping no mid-chapter disk-table switch (only at the chapter head / on leave) is preserved.

**Tests / build.** `:app:testDebugUnitTest` + `:app:assembleDebug` green; installed to the tablet.

**Pending to verify.** Forward whole-block tearing is resolved; backward is still whole-block, to be completed after forward is confirmed.

## 2026-09-12 — CSS `display:none` support (engine-level hide)

**Issue.** The engine only handled `display:block`; `display:none` was ignored, so `display:none` elements (e.g. a book's `<span class="boring">` "hide the boring code" spans) were still laid out and rendered, producing spurious blank lines / tall pages.

**Fix (single-source cascade, both layout paths).** `ComputedStyle` gains `displayNone`; `StyleComputer` sets it from `display:none` and adds `resolveHidden` (light path, mirrors heavy's `styleMap.displayNone`). `NormalFlowLayout` skips hidden subtrees in box building / text absorption / inline aggregation / leaf enumeration; `ParagraphShapes.emitText` skips them when rendering; the light path derives char starts via the shared `visibleCharAdvance` so heavy and light exclude display:none identically.

**Tests / build.** `:app:testDebugUnitTest` + `:app:assembleDebug` green; installed to the tablet.

**Pending / to verify on device.** Book `.boring` spans hidden; pages fill normally; no pagination drift between heavy and light.

## 2026-09-11 — Anonymous text leaf style fix at the engine layer (root cause)

**Issue.** In a heading like `<h1><span class="sec-num">Chapter 9 </span>Error Handling</h1>` the anonymous `#text` title (`Error Handling`) lost its block's **color and bold** — rendered black / thin — while the `sec-num` element leaf rendered correctly. Root cause was engine-level: an anonymous `#text` leaf is synthesized and has **no entry** in the styles map, so `emitText` never applied color/bold spans to it (element leaves have real style entries). This is the same mechanism failure behind both the color and the weight symptoms; earlier base-style alignment (`blockStyleFor`) only matched base styles, not the rendered inline spans.

**Fix (engine layer, shared by heavy+light).** In `ParagraphShapes.emitText`, an anonymous `#text` leaf falls back to the block's `rootStyle` (`styles[el] ?: rootStyle`), so it receives the same color/bold/spacing spans as any text run — correct HTML/CSS produces correct rendering, no paint-layer workaround (base-color / fake-bold hacks were tried and removed).

**Tests / build.** `:app:testDebugUnitTest` green; `:app:assembleDebug` OK; installed to the tablet.

**Pending / to verify on device.** Chapter big title now blue + bold; `sec-num` unchanged; no regression across body/heading/link styling.

## 2026-09-11 — Chapter-head page-flip dual render unification (head-flip double-render fix)

**Issue.** At a chapter's first page (e.g. after a TOC jump to a heading's `<h2><span class="sec-num">…</span> …</h2>`), a left page-flip showed two defects: (1) the page redrew **in place** instead of flipping / cross-chapter, and (2) the heading's weight/color changed because the heavy (`canonical`) and light (`temp`) renderings disagreed on the anonymous text leaf's style.

**Fixes.**
- **Single-source anonymous-leaf style (defect B).** The light path now resolves an anonymous `#text` leaf through its container block (`blockStyleFor`) and returns an empty inline-style map — matching the heavy path, which reuses the container style and holds no `styleMap` entry for synthesized leaves. Added a per-leaf `ComputedStyle` (fontSize/bold/color/textAlign/fontFamily/fontWeight) dual-path equality assertion to `BoxPathConsistencyTest`.
- **Chapter-head page-flip routing (defect A, S5).** `tempNav` no longer force-calls `bindCanonicalAndPage` to redraw the head page. At the chapter head, a backward flip is a boundary → the temp table is abandoned and the **disk (canonical) pagination is enabled on leave** (`finalizeTempOnLeave`). The mid-session canonical switch (`onBackgroundCanonicalReady`) was removed, so the current page is never re-rendered in place / can't flash while temp is live. Cross-chapter from the head now works on the first flip.

**Tests / build.** `BoxPathConsistencyTest` extended; `.gradlew :app:testDebugUnitTest` green; `:app:assembleDebug` OK; installed to the tablet.

**Pending / to verify on device.** Chapter-head left-flip now cross-chapters without in-place redraw or style jump; re-open after disk hit still lands on the same character; no regression across parameter relayout / cross-chapter jumps.

## 2026-09-11 — Dual-path single-sourcing (typesetting correctness, rectification A/B + 1.2)

**Issue.** The engine kept two layout paths — heavy `NormalFlowLayout` (canonical, writes the disk pagination table) and light `BoxChapterLayouter.LightPrepare` (lazy cascade, reads the table + whole-block temp pages). The same geometry was implemented twice in separate sessions; equivalences were not enough, and a divergence surfaced as a page's last line overflowing the content height (clipped bottom half).

**Fixes (single-source, light now calls heavy's shared functions).**
- **A · line-break width**: extracted `NormalFlowLayout.innerBreakWidth(style, borderBoxW)` and `descendContentWidth(el, rootW, edgesOf)`. `buildBoxTree` and light shaping both route through them; `LightPrepare.borderBoxWidth` deleted.
- **B · container margin collapsing**: added `NormalFlowLayout.consecutiveLeafAdvance(prev, next, styleOf)` that reproduces `emit()`'s tree-aware collapse (container bottom margins + the container/leaf-abutment rule). `rebuildLocalLines` now uses it instead of adjacent-leaf pairwise.
- **1.2 single-sourcing**: last-line height via `lineHeightPx` (shared by both rebuild paths); `globalCharStarts` via `NormalFlowLayout.accumulateCharStarts`; first-line top-align via `DrawableBookLayout.alignPageTop`.

**Tests.** Added `BoxSharedGeometryTest` locking the shared width/advance functions byte-for-byte to canonical emit geometry (nested containers, container margins, leaf→container abutting). Full `:app:testDebugUnitTest` is green.

**Pending / to verify on device.** Device regression (page last line no longer clipped; spacing matches pre-change; large-font / multi-margin chapters). The temporary `INCR-OVERFLOW` diagnostic log was already absent from the tree (nothing left to remove).