# Layout Priority and Responsiveness Improvement Plan (Final)

> Sources:
> - Investigation report [`docs/incremental-vs-full-layout-investigation.md`](incremental-vs-full-layout-investigation.md) (Steps 0–6: 7 contracts + R1–R6)
> - Concurrency & window design spec [`docs/layout-priority-and-concurrency-design.md`](layout-priority-and-concurrency-design.md) (this file is its implementation roadmap)
> This plan turns the report's contracts and risks into executable improvement items **P1–P14** and freezes the conclusions of
> **multiple convergent design discussions** — the bounded contiguous sliding window ±N, window-outside "discard-and-restart",
> unified promotion-on-leave, unit decomposition and the 4–5 core layout, and the cache-invalidation matrix — into the item definitions.
> Line numbers refer to the current `app/src/main/java/...` version.

---

## 0. Goals and Scope

1. **Improve user responsiveness**: first-frame latency for TOC/annotation/seek jumps, in-chapter/cross-chapter flips, opening a new book;
2. **Straighten out the priority of the two layout routes**: foreground incremental temp window (A) vs background canonical full layout (B);
   write "whose output takes effect, when promoted, who yields" as executable rules and close known holes (the birth-window race is the only high-severity one);
3. **Background job lifecycle**: when to abandon, abandon fast, safe re-deployment after abandon (keying + atomic commit + idempotency);
4. **Concurrency**: decompose layout into independently schedulable units (U0–U9), run on dedicated tracks across 4–5 cores with
   **guaranteed priority** and unchanged output semantics.

---

## 1. Current Defects (from the investigation report + newly found)

| Defect | Source | Category |
|---|---|---|
| Temp-session "birth window" race: between `bindInProgress`(:577) and `setCurrentTempPage`(:590) (~350–550ms, no lock), a flip can occupy `forwardPages[0]` → duplicate blocks / bounce-back | R1 | responsiveness + correctness |
| Whole-book relayout (`otherChaptersJob`) not cancelled by `startAnchorStream`/`scheduleRelayout`; old params queued, never re-dispatched | R4 (contract #4) | abandon/re-dispatch |
| Current-chapter canonical single slot `anchorBackfillJob` stolen cross-chapter → just-tuned chapter invalidated, re-temps on return | R3 (contract #6) | abandon strategy |
| `seekTo`/`tocJump`/`jumpChapter` launch a fresh coroutine each time, **no in-flight jump cancellation** (ReaderActivity.kt:560-566,672-680,551-557) | new | jump responsiveness |
| Whole-book loop (finalizeRelayoutAll:995-1001) and canonical have no `ensureActive`; cancellation unreachable | new | timely abandonment |
| Cross-chapter flip does synchronous `ensureMarkup`(parse)+`buildLayout` on the IO thread (crossChapterLanding:817-818) | R6 | flip responsiveness |
| New-book first-screen critical path = parse + prepareLight + anchor shaping; no preflight | new | open-book responsiveness |
| **`tocJump`/`seekTo`/`jumpChapter` leave without finalizing the old chapter** → old `inProgress` residue; re-entry can land on the old anchor | new (found) | correctness (P11) |
| **Temp slots only grow**: `forwardPages/backwardPages` can accumulate scores of pages by the chapter tail; more memory and unbounded forward-flip latency risk | new (found) | window semantics (P10) |
| `HEAD_START_BLOCK_LIMIT` comment-only, unimplemented (:531-537); out-of-lock shaping profile race | R2/R5 | correctness (Phase 3) |
| Remaining-chapter scan is fixed chapter-index order, blind to reading direction / in-chapter position | new | abandonability (P12) |
| Layout squeezes `Dispatchers.Default`: flip anchor shaping, pre-shape, canonical all fight over the same pool | new (found) | concurrency (P13) |

---

## 2. Scheduling Model: Four Tracks and the Priority Contract

### 2.1 Four tracks

| Track | Content | Thread/cores | User-perceivable? | Priority |
|---|---|---|---|---|
| **F** main | render, gestures, `jumpTo`/`setPage` | Main (core 0) | yes | highest (natural) |
| **A** foreground shaping | flip `tempNav`/on-demand shaping, window pre-shape, `startAnchorStream` anchor shaping | **dedicated foreground thread (core 1, new)** + flip-calling thread | yes | **1** |
| **B** canonical | B1 = current-chapter canonical; B2 = remaining-chapter scan | `canonicalDispatcher` single-thread (core 2) + chunk workers (cores 3–4, P13) | no | **2** (B1 > B2) |
| **P** preflight (new) | neighbor/jump-target/new-book-chapter: `ensureMarkup`+`prepareLight` upfront | background idle core | no | **3 (weakest, always yields)** |

### 2.2 Priority contract (formalized)

1. **F > A > B1 > B2 > P**; lower tracks never block higher tracks; lower tracks never hold their thread more than one yield point.
2. **A never waits for B**: jumps/flips delivered immediately by A; B produces only "standby" results. B **never actively switches the table** —
   promotion happens only at chapter-head/any-leave via `finalizeTempOnLeave` (S5); never a mid-session switch or in-place redraw.
3. **A yields to a newer A**: new params/new target (epoch+) → drop old prefill, old shape cache, old jump.
4. **B1 exception semantics**: same-chapter new params → **cancel and re-layout**; **cross-chapter entry → queue, don't kill** (idempotent persist keeps the table, R3 fix).
5. **B2 is the easiest to abandon**: cancel on param/jump/chapter-direction change (checkpoint ≤ 1 chapter); re-dispatch with the latest hash after params settle.
6. **P always yields first**: only idempotent preflight; cancel has no side effects.
7. **Window-outside = discard-and-restart**: any discontinuous movement discards the whole session; promote the full table directly if ready (P10/P11).

---

## 3. Background Job Lifecycle: Abandon and Re-deployment Protocol

### 3.1 Keys and epoch

- Global `layoutEpoch: Long`, monotonically increasing; each `prepareRelayout` iteration and each seek/toc/chapter-jump/cross-chapter target change increments it.
- Every background job carries `(kind, chapter, paramHash[, target])` and its epoch, **all idempotent** (pure functions of `(params, chapter)`,
  replay has no side effects). A same-kind job with a higher epoch arriving → put the old job in the abandonable set.

### 3.2 Slots

| Slot | Purpose | Abandon rule (after change) |
|---|---|---|
| `canonicalJobs[chapter]` (extended from single-slot `anchorBackfillJob`) | per-chapter B1 | same-chapter same-key → dedup; same-chapter new params → cancel+requeue; other-chapter → **queue, don't cancel** |
| `wholeBookJob` (original `otherChaptersJob`) | B2 whole book | any param/progress/direction change → cancel (P7 checkpoints); re-dispatch at the params-settled point (P2 trigger) with latest paramHash; scan order per P12 |
| `prefillJob` | A window pre-shape | re-dispatch per flip and new pointer (depth=1); dedup same in-flight target |
| `preflightJobs[chapter]` (new) | P preflight | direction/target change → drop old neighbor, set new neighbor |
| `jumpJob` (new, ReaderActivity) | jump | new seek/toc/chapter-jump → cancel in-flight jumpJob |

### 3.3 Cancellation points and atomic commits

- **Add checkpoints**: B2 loop before each chapter; canonical `fullLayout` between blocks `ensureActive()` (canonical path only; foreground shaping is untouched to
  avoid perturbing A). Anchor shaping is a pure-CPU segment — its abandon point is **before** shaping (skip the whole segment if epoch is stale).
- **Atomic commit points**: background shared-state writes only at (a) the `tempStateLock` critical section (tempNav/pre-shape/birth commit, P1), or
  (b) `finishCanonicalBackground`'s `bindPaginationTable` + disk write (the single commit point). Cancellation never lands dirty.
- **Idempotent**: all B/P work reschedules safely by key.

---

## 4. Improvement Items (P1–P14)

### P1【Phase 1·high】Temp-session birth-window atomization + flip "birth wait" — fix R1 ✅ (2026-09-18, `1d55b86`; probe T1 `TempBirthWaitRegressionTest`; both suites green)

**Problem**: `startAnchorStream` (:557-614) shapes the anchor page synchronously (350–550ms) between `bindInProgress`(:577) and
`setCurrentTempPage`(:590), without holding `tempStateLock` and before `curIndex` is set — a flip in this window on-demand-shapes into
`forwardPages[0]`, then the anchor is appended at index 1 → duplicate anchor block, or a bounce back to the anchor.

**Plan**:
1. Shape the anchor page into **locals** (shaping still outside the lock), then commit
   「`bindInProgress` + clear old caches + fill the window + set window pointers + `setCurrentTempPage`」atomically inside
   `synchronized(tempStateLock)` (the birth commit point).
2. Add `unit.tempBirth: CompletableDeferred<Unit>?`: suspended before shaping, `complete()` and null it at commit; complete on the error path too (no
   leaking waiters).
3. `findAdjacentPage` (:1220-1322): when `unit.inProgress == null && unit.tempBirth != null` → `withTimeoutOrNull(800){ birth.await() }` then
   **re-read state** before navigating; on timeout, fall back conservatively as if the anchor is not ready (existing miss branch).
   This also blocks "flipping inside an unbound window falls onto stale pageSlices".

**Abandon/re-deploy**: a new stream (new epoch) overwrites the old via bind; waiters re-route from the latest state after complete.
**Acceptance**: new probe T1; regressions `TempNavBackwardRegressionTest`, both suites green.

---

### P2【Phase 1·high】Whole-book relayout epoch-ized; cancel old params and self re-dispatch — fix R4 ✅ (2026-09-18; probe T3 `WholeBookRelayoutEpochProbeTest`; both suites green; log in the investigation report)

**Problem**: `otherChaptersJob` is cancelled only by `finalizeRelayoutAll`(:991) and queued with the **panel-close-time** params; a further param change
without reopening the panel leaves B2 holding the canonical thread with old params.

**Plan**:
1. `prepareRelayout`(:917) increments `layoutEpoch`; `startAnchorStream`'s B1 carries that epoch.
2. New `scheduleWholeBook(epoch, paramHash)`: cancel in-flight `wholeBookJob`, re-dispatch on `canonicalDispatcher`.
3. **B2 trigger = the params-settled point**: when the `scheduleRelayout` loop ends (ReaderActivity.kt:810-812, `relayoutScheduled=false`),
   `deferCanonical==false`, and `layoutEpoch > lastWholeBookEpoch` → `scheduleWholeBook`
   (no churn while dragging; `deferCanonical==true` suppresses, as today).
4. `finalizeRelayoutAll`'s wrap-up routes through `scheduleWholeBook`.

**Abandon/re-deploy**: next param change cancels (checkpoint ≤1 chapter); re-dispatch with the latest hash once settled.
**Acceptance**: new probes T3, T5; both suites green.

---

### P3【Phase 1·medium】Current-chapter canonical slot semantics: "queue-not-kill" cross-chapter, re-layout only on same-chapter new params — fix R3 ✅ (2026-09-18; probe T4 `WholeBookCanonicalQueueProbeTest`; both suites green; log in the investigation report)

**Problem**: `anchorBackfillJob?.cancel()` (:603) unconditionally cancels the previous chapter's canonical; tuning at the head then immediately flipping
away cancels the just-tuned chapter's canonical; `finalizeTempOnLeave` invalidates everything → re-temps on return.

**Plan**:
1. Single slot → `canonicalJobs: MutableMap<Int, Job>` (chapter→B1), still on single-thread `canonicalDispatcher` (FIFO).
2. When launching a new chapter's B1: same-chapter new params → cancel+requeue; **other-chapter → don't cancel**, let it finish and persist on the
   queue (cost ≈ one chapter full-layout 100–300ms, far less than "cancel then re-temp + re-canonical on return").
3. On success it persists regardless of being read later — returning to that chapter is a disk hit.
4. Consequentially fix `finalizeTempOnLeave`(:792-803): miss branch keeps `invalidateLayout`, but since its persist job is no longer killed, the next entry is
   likely a disk hit (honoring S5); no repeated re-temps.

**Abandon/re-deploy**: a newer same-chapter-params B1 kills the old B1 (genuinely stale); cross-chapter only lowers priority, never abandons.
**Risk**: `canonicalJobs` is a plain concurrent container (only add/remove, no shared-state writes; no need to enter `tempStateLock`).
**Acceptance**: new probe T4; regression `IncrementalReplayEquivalenceProbeTest`.
**Implement**: `canonicalJobs: MutableMap<Int, Job?>` (chapter→B1, written only on the dispatch thread); same-chapter dispatch
cancels its own slot (genuinely stale), other-chapter dispatch leaves the FIFO-queued B1 running so it persists `LINE_DISK` regardless of
being read later; `finalizeTempOnLeave` miss branch keeps `invalidateLayout` (its B1 survives → next entry = disk hit, no re-temp). T4 verified.

---

### P4【Phase 2·medium】Cross-chapter "preflight" (parse + light-prepare upfront) — reduce R6 pressure ✅ (2026-09-18; probe `CrossChapterPreflightProbeTest`; both suites green)

**Problem**: `crossChapterLanding`(:810-837) synchronously runs `ensureMarkup`(parse)+`buildLayout` on the flip thread; crossing into a large chapter
stacks another anchor shaping; neighbor-chapter parse (~tens of ms) sits fully on the critical path for its first visit.

**Plan**:
1. New track P: once a flip lands, if the neighbor chapter's `paramHash` matches, do `ensureMarkup`+`prepareLight`
   (both idempotent, cacheable) early, writing into existing `structureCache`/light fields (naturally paramHash-validated).
2. `crossChapterLanding` change: check whether P already prepared the chapter before `ensureChapterLayout` → skip parse/light-prepare, leaving only
   window/anchor shaping.
3. P slot `preflightJobs[chapter]` keyed by `(chapter, paramHash)`; direction/param change drops the old neighbor and sets the new; runs on an idle
   background core, yields to any F/A arrival.

*Landed: a background P track in `BookDocumentController` — `preflightNeighbors(chapter)` fires from the flip entry (`findAdjacentPage`)'s `.also` after
every successful landing and prewarms BOTH out-of-bounds neighbors (`chapter−1`/`chapter+1`, bounded by the book): `ensureMarkup` + `prepareLight` only
(no shaping, no table). `preflightJobs[chapter]` is the per-chapter slot (cancel+clear on `prepareRelayout` via `voidPreflight`), running on
`backgroundDispatcher` and yielding naturally; `preflightReadiness[chapter] = paramHash` makes it idempotent (already-prepped → no-op; same in-flight
target → dedup). Since preflight sets `unit.markup`, `crossChapterLanding`'s `ensureChapterLayout` skips the parse automatically and its
`prepareLight` reuses the warmed `structureCache` (structure-keyed: cssBundle + `useOriginalStyle`) — the flip thread is left with window/anchor shaping
only. The active chapter is never preflighted (B1 owns it).*

**Abandon/re-deploy**: flip-direction or epoch change voids the old preflight (idempotent); re-dispatch for the new direction immediately.
**Acceptance**: cross-chapter consecutive-flip manual timing (parse should leave the critical path).
*Accepted: probe `CrossChapterPreflightProbeTest` — (1) a flip landing in ch1 prewarms ch0 and ch2 in the background (their markup non-null without a
visit, via `preflightReadiness`), then after advancing to ch1's last page the out-of-bounds crossing into ch2 happens strictly AFTER ch2's markup was
parsed by preflight — the parse is off the crossing's critical path; (2) a typography-param change through `prepareRelayout` voids the readiness
record (`isChapterPreflightReady(ch2)` drops to false; next flip re-preflights). Both suites green (`:common:jvmTest`, `:app:testDebugUnitTest`) and
`:app:assembleDebug` builds.*

---

### P5【Phase 2·medium】Open-book preflight — new-book responsiveness ✅ (2026-09-18; probe `OpenBookPreflightProbeTest`; both suites green; log in the investigation report)

**Plan**:
1. Public API: `prewarmForOpen(chapter, paramHash)`: background `ensureMarkup`+`prepareLight` + (when disk hits)
   `ensurePageRangeShaped` pre-shapes the window.
2. The bookshelf layer calls it with the saved position + current global params when the book is tapped (book-scoped); switching books → cancel the old
   book's preflight, re-dispatch for the new target; unknown target → default chapter 1.
3. When the reader opens, `ensureChapterLayout` hits the prepped markup/light → first screen only needs window/anchor shaping.

**Abandon/re-deploy**: per-book slots; book/param switch abandons and re-dispatches.
**Risk**: bookshelf UI wiring needs implementer follow-through; unread/unknown target defaults to chapter 1.
**Acceptance**: real-device open-book first-screen timing comparison.

*Landed: `BookDocumentController.prewarmForOpen(chapter = -1, targetChar = -1)` — the engine API's two args default to the open point's saved
position (`startChapter`/`startChar`; no progress → chapter 0), so the engine itself resolves "unknown target" and the caller only passes explicit
values when it knows better. Runs on the background P track from a single per-book slot (`openPreflightJob`; a newer prewarm / book switch / `voidPreflight`
cancels it): `ensureMarkup` + `prepareLight` (records `preflightReadiness[chapter] = paramHash` like P4, idempotent) and — when a disk table exists for the
CURRENT params — `bindPaginationTable` + the buildLayout disk-hit inline shaping (first page window, `pagesToShape = 4`, sets `shapedPageFrom/To`), so the
chapter is `laidOut` with real pageSlices before any `ensureChapterLayout`. Active chapter is never prewarmed (B1 owns it). Mirrored buildLayout's disk-hit
branch deliberately: a fresh chapter has EMPTY `pageSlices`, so `ensurePageRangeShaped` alone would early-return on the first visit.
`ReaderActivity.openBook()` (the repo's real open path — the bookshelf layer is a placeholder stub) calls `c.prewarmForOpen()` right after `open(...)` and
before `locateStart()`, so tap→first-screen total work = parse + light + (on disk hit) the promote, never a re-layout.*

**Abandon/re-deploy**: per-book slots; book/param switch abandons and re-dispatches.
**Risk**: bookshelf UI wiring needs implementer follow-through; unread/unknown target defaults to chapter 1 (engine-side: saved position, else ch0).
**Acceptance**: real-device open-book first-screen timing comparison.
*Accepted: probe `OpenBookPreflightProbeTest` — (1) `prewarmForOpen(1, 0)` with no disk table parses + light-prepares ch1 (markup + `isChapterPreflightReady`),
pages unshaped, ch0/ch2 untouched; (2) a first session persists ch1's disk table, a fresh controller with the same `cacheRoot` opens the book, and
`prewarmForOpen(1, 0)` then binds the table and PRE-SHAPES the first page window (`pageSlices` non-empty, `laidOut`) before any `ensureChapterLayout`; the
default-target path (no progress → ch0) covered with `prewarmForOpen()`. Both suites green (`:common:jvmTest`, `:app:testDebugUnitTest`) and
`:app:assembleDebug` builds.*

---

### P6【Phase 1·high】In-flight jump cancellation + dedup + immediate feedback — jump responsiveness ✅ (2026-09-18; probe T2 `JumpGateProbeTest`; both suites green; log in the investigation report)

**Problem**: `seekTo`(:560-566), `tocJump`(:672-680), `jumpChapter`(:551-557) each launch a full anchor path concurrently; seek-driven dragging stacks
multiple complete anchor paths with stale targets.

**Plan**:
1. ReaderActivity single-slot `jumpJob`: new jump → `jumpJob?.cancel()`, launch with the **latest** target.
2. The jump carries an epoch/target: only when "I am still the last request" does it `withContext(Main)` land (guard against cancel-not-yet-effective races).
3. Drag throttling: dragging records only the latest fraction; one `seekTo` executes on release.
4. Immediate feedback: a light "loading" indicator (e.g., top-bar progress) on jump start; `jumpTo` lands the page when shaping finishes.

**Abandon/re-deploy**: new target/new epoch abandons the old jump (an in-flight anchor shaping may finish but **must not land**).
**Note**: coexists with `scheduleRelayout`'s `relayoutJob`; the post-jump relayout loop uses the new currentChapter/currentSlice.
**Acceptance**: new probe T2; manual seek-drag shows no stacking.

---

### P7【Phase 1·medium】Fill in cancellation points (abandon must be "timely") ✅ (2026-09-18; probe T5 `WholeBookCancellationProbeTest`; both suites green; log in the investigation report)

**Plan**:
1. B2 loop `ensureActive()` before each chapter iteration — *already delivered by P2* (`requestWholeBookRelayout` per-chapter `isActive` guard);
2. `fullLayout` (`BoxChapterLayouter`) block-loop checks (canonical path only; foreground shaping untouched) — *landed as a `checkpoint: () -> Unit = {}` parameter invoked once per block before `shapeLeaf`; B1/B2 pass `ctx.ensureActive()`, foreground/`layout()` keep the no-op default. A cancelled shape throws `CancellationException` (never returns a truncated product — that would corrupt the disk table); the two `onFailure`s filter it so a timely abandon is not logged as FAIL*;
3. `prepareRelayout`'s large-chapter path: epoch-stale skip **before** the anchor shaping (pure CPU 350–550ms) — *landed: after `prepareLight`, `if (myEpoch != layoutEpoch) return@runCatching null`*.

**Abandon/re-deploy**: see §3.3.

---

### P8【Phase 3·low】Implement the head lift — fix R2 ✅ (2026-09-18; probe `HeadLiftProbeTest` + both suites green)

Implemented in `startAnchorStream`: after computing `anchorBlock = prepare.blockIndexForChar(anchorChar)`,
if `0 < anchorBlock <= HEAD_START_BLOCK_LIMIT` (=100), re-anchor at the TRUE chapter head
(`anchorBlock=0`, `anchorCharAt=0` → `anchorLineCharStart=0` via the anchor page) and log via `EngineLog.w`
(≤100-block retreat). `HEAD_START_BLOCK_LIMIT` promoted to `internal` with a `headStartBlockLimit()`
accessor (android unit tests can't see main-source-set top-level `internal`s, only controller members —
same precedent as `tempWindowSnapshot`). The anchor page for the lifted case is shaped at char 0 so the
canonical head and the temp head are the same source.
**Note**: correctness debt; payoff mainly on the "save→relocate" path (linked with Rectification D), not responsiveness.
**Acceptance**: probe `HeadLiftProbeTest` (near-head anchor lifted to block 0/char 0; deep anchor stays at
its block; boundary exact at `HEAD_START_BLOCK_LIMIT` vs `LIMIT+1`) + both suites green. The T7 fixture's
`<br/>` paragraph moved from block 60 (inside the lift zone) to block 150 to keep exercising a torn pair
(same-source lift at ≤100 makes block-60 anchors land on the head page instead).

---

### P9【Phase 3·low】Out-of-lock shaping profile race — fix R5 ✅ (2026-09-18; documented, fix deferred)

Document only (status locked as of 2026-09-18): flipping mid-tune may flash one mixed-param frame (position not lost).
`ip.profile`/`ip.contentW/H` are read by the temp-shaping paths while a tune (`setFontSize`/`setLineHeight`/…) swaps
them outside `tempStateLock`, so a flip racing the swap can shape with a torn pair of profiles (one frame). No position
corruption — R5 is a cosmetic, single-frame cross-param flash.
**Corrective fix landed 2026-09-18 (same day, after Phases 1/2/3 went live)**: freeze a `profile` snapshot into
`ip.profileSnapshot` at `startAnchorStream` (immutable data class — a reference is the snapshot), the anchor page is
shaped from `snapshot`, and every temp on-demand/prefill shaping path (`shapeNextBackward`, `stepTempPrefill`,
`tempNav` forward/backward/re-root) reads `ip.profileSnapshot` instead of the live `BookDocumentController.profile`.
A temp session can no longer mix an old content dim with a new profile (or vice-versa) frame-by-frame. Any session born
before a tune still shapes that OLD snapshot uniformly; `startAnchorStream` after the tune pits the new hash + new
snapshot. Canonical paths (B1/B2) and the small-chapter foreground keep the live `profile` (they are not part of the
R5 race: tune→relayout already re-anchors them under the new hash). No behavioral probe added (per plan — fix is
speculative, triggered only by a live-tune observation); existing temp suites regress it
(`TempNavBackwardRegressionTest`, `WindowSlideDiscardProbeTest`; both green).

---

### P10【Phase 2·high】Temp pagination table = bounded contiguous sliding window (±N; discard-and-restart) — new window semantics ✅ (2026-09-18; probe T7 `WindowSlideDiscardProbeTest` + both suites green)

**Problem**: today `forwardPages/backwardPages` **only grow**: flipping toward the chapter tail accumulates scores of pages; forward-flip latency risk is
unbounded far out. Window-outside jumps already do whole-discard (`startAnchorStream` whole-stream replacement) but other spots harbor
"half-contiguous/residue" hazards.

**Plan (design spec §6)**:
1. Session window = a **bounded contiguous window ±N** around the current page (default N=1, i.e., prev+cur+next, 3 pages); depth tunable 1–2.
   **Contiguity**: `page[k].blockEndExclusive == page[k+1].blockStart`; the anchor-block **torn pair** is atomic — kept/dropped together.
2. **Continuous flips = slide, no discard**: background pre-shape (depth=1, down from `FLIP_AHEAD_PAGES=4`) backfills the window; `tempNav` on-demand
   shaping covers when not landed in time; evict from the **far end** when over depth (never the middle, never a torn pair).
   Each flip `scheduleTempPrefill` cancels the old pre-shape and re-dispatches per the new pointer (keeping findAdjacentPage:1251 semantics; dedup the
   same in-flight target).
3. **Window-outside movement = discard-and-restart**: any discontinuous jump (TOC/seek/annotation/same-chapter far jump/cross-chapter) discards the whole
   session and restarts the anchor stream at the target char; **if this chapter's full table is ready, promote it directly** (discard the temp, locate by
   char — no second re-layout).
   ⇒ "Half-contiguous / partial-discard" states cease to exist; this also self-heals R1-type "discontinuous items mixed into the window".
4. Window symmetric (±N equal weight) → forward/backward flip latency risk symmetric; no "forward-first" or water-fill needed.
5. With N=1 the memory bound ≈ 3 pages + torn pair (today: scores of pages by the chapter tail).

**Abandon/re-deploy**: each flip re-dispatches window maintenance (U4); jumps discard-and-re-anchor (idempotent).
**Risk**: eviction must never evict the current page or a torn pair; delete one page at a time from the far end, never hop the middle.
**Acceptance**: new probe T7 (continuous flips keep the window contiguous + far-end eviction + window-outside jump leaves no half-contiguous residue); both suites green.

*Landed: window depth `TEMP_WINDOW_DEPTH = 1` (`BookDocumentController`; replaces `FLIP_AHEAD_PAGES = 4`), enforced by `enforceTempWindow(ip)` after every
temp pointer move AND after every background prefill append (reading-order units `WinUnit.{Fwd,Bwd,Pair}`; keep-interval `[curPos−depth, curPos+depth]`;
far-end eviction; `Pair` = the anchor torn pair, always kept/dropped together; page identity recovered by object identity after rebuild). Backward steps
derive from the **window head edge** (`forward[0]`, `headEdgePage`) not the true anchor — `tempNav` re-roots the forward front from
`backward[0].blockEndExclusive` when eviction emptied the forward side. Padding/`nearest-first` backward packing unchanged; the pure `tempNavBackwardPointerStep`
signature untouched (regression `TempNavBackwardRegressionTest` 12 cases stay green). T7 locked in three invariants — bounded (≤4 torn / ≤3 not) +
contiguous (blocks advance without skip/regress, allowing one shared line-cut block) + strictly-monotonic current char; torn-pair atomicity; and window-outside
discard (finalize + re-ensure at a far anchor → canonical service OR a fresh compact session at the far block, never residue). Two latent bugs surfaced and
fixed by the probe: `locateTempPosition` (blockStart-only matching re-routed a torn backward-page slice onto the head-edge page — skipping a page on re-flip;
now keyed by (blockStart, charStart)), and `stepTempPrefill` (wrong neighbor-liveness conditions + no trim — the pre-shape could grow the window unbounded).
Probe T7 required `tempWindowSnapshot(chapter)` (public, under `tempStateLock`). Note: Robolectric text shaping reports paragraphs as single lines, so the
torn seam only forms with an explicit <br/> break in the anchor paragraph (T7's paragraph 60); production wrapping yields it naturally.*

---

### P11【Phase 1·high】Unified promotion on leave (S5 completion) + cross-chapter tail-anchor preserved ✅ (2026-09-18; probe T8 `JumpLeaveFinalizeProbeTest` + regression `TempNavBackwardRegressionTest`; both suites green; log in the investigation report)

**Problem**: `tocJump`/`seekTo`/`jumpChapter` leave **without finalizing the old chapter** → old `inProgress` residue; re-entering that chapter can land
on the old anchor (perceived as position bounce).

**Plan**:
1. **Every way of leaving the current chapter** (flip-out-of-bounds, chapter jump, TOC, seek bar, open new book) runs S5 `finalizeTempOnLeave` on the old
   chapter first: canonical ready → bind + clear session; not ready → invalidate (with P3 background persisting keeping the table alive).
   Add the call to all three jump paths (landed together with P6's jumpJob). *Landed as a public `BookDocumentController.finalizeOnLeave(chapter)` called
   from the three `JumpGate.submit` lambdas (`jumpChapter`/`seekTo`/`tocJump`, `ReaderActivity`); the flip-path leave calls now route through the same
   `tempStateLock`-serialized helper (`finalizeTempSession`). Idempotent (repeated finalize = no side effects).*
2. **Cross-chapter landing = tail anchor**: a backward out-of-first-page flip lands on the **previous chapter's last page**
   (`crossChapterLanding`'s `backwardEntryAnchorChar`:817), not the TOC-jump home-page semantics. Cross-chapter miss = **restart as a jump flow** (§6.3
   semantics); no "previous-chapter tail pre-shape". *Already in place (`backwardEntryAnchorChar` + `pageIndexForChar`); kept distinct from jump
   home-page semantics and guarded by the `TempNavBackwardRegressionTest` regression.*

**Abandon/re-deploy**: shares jjob's epoch with P6; S5 idempotent (repeated finalize has no side effects).
**Acceptance**: new probe T8 (jumping away leaves no stale session); regression `TempNavBackwardRegressionTest`.

---

### P12【Phase 2·medium】Remaining-chapter scan ordering (B2 abandonability) ✅ (2026-09-18; probe T5-scan-order-half `WholeBookScanOrderProbeTest`; both suites green)

**Problem**: `finalizeRelayoutAll`'s whole-book loop (:995-1001) scans by ascending chapter index, blind to target; it lays out irrelevant chapters before
nearby/popular ones.

**Plan**: order the scan by **reading direction × in-chapter position × distance**:
1. direction: follow the reading direction from the current chapter (reading forward → scan forward chapters first; backward → reverse);
2. in-chapter position: for equal distance, prefer chapters near the reader's position (quickly cover flip-out-of-bounds paths);
3. distance: nearest first, far chapters later (all still complete, just ordered).
The ordering is a pure function; each chapter still passes a checkpoint (P7); can be cancelled directly by param/direction changes.

*Landed: `orderRemainingChapters(total, current, direction)` — a pure function returning the reading-direction group first and nearest-distance-first
within it (`ahead = {current+1…}`, `behind = {current−1…}`; `direction ≥ 0` → ahead first, else behind first). `readingDirection` is tracked at the single
reader-facing flip entry `findAdjacentPage` (covers in-chapter temp/canonical AND out-of-bounds cross-chapter flips) plus the direct
`nextPageInChapter`/`prevPageInChapter` curl-adjacent entries, defaulting to forward. The B2 coroutine in `requestWholeBookRelayout` builds its scan from
`remainingScanOrder(current = activeChapter)` (the active chapter is still skipped — B1 owns its pass; every other chapter still completes its full pass +
persist, so correctness never depends on the order).*

**Abandon/re-deploy**: same P2/P13 per-chapter checkpoints; reorder at each `scheduleWholeBook`.
**Acceptance**: B2 scan log confirms order; consistent with T5 regression.
*Accepted: probe `WholeBookScanOrderProbeTest` — (1) pure-function unit cases: forward/backward group order, nearest-first inside each group, edge
currents at both book ends, and an exhaustive permutation check (total ≤ 6 × every current × both directions: each chapter exactly once); (2) integration:
the real flip entries drive `currentReadingDirection()` and the B2 coroutine's `remainingScanOrder(1)` reorders between `[2,0]` (backward) and `[0,2]`
(forward). Cancellation/≤1-chapter abandonment stays covered by `WholeBookCancellationProbeTest`.*

---

### P13【Phase 3·high】Concurrency scheduling: unit decomposition + 4–5 core layout — the core of responsiveness and priority guarantees ✅ (2026-09-18; probe T6 `ChunkedCanonicalEquivalenceProbeTest`; both suites green; log in the investigation report)

**Problem**: `backgroundDispatcher=Dispatchers.Default`(:148) runs flip anchor shaping, window pre-shape, parsing, and canonical together, fighting for
cores; priority exists only at the coroutine-scheduling level, with no structural isolation.

**Plan (design spec §2, §5)**:
1. **Work units U0–U9** (one-to-one with existing functions, no new abstractions; design spec §2):
   U0 parse / U1 light-prepare / U2 display anchor page (P0) / U3a one-back / U3b one-forward /
   U4 window maintenance / U5 heavy-prepare / U6 whole-chapter shaping (**chunk-parallel U6k**) / U7 pagination / U8 atomic commit /
   U9 remaining-chapter scan; dependency DAG in the design spec.
2. **Core layout (4–5 cores)**:
   - core 0 Main (UR render);
   - **core 1 dedicated foreground shaping thread (new)**: U2 anchor shaping, U3a/b flip on-demand shaping, window pre-shape — moved out of the Default pool,
     eliminating core-fights with parse/canonical — *partial: foreground and pre-shape still share `backgroundDispatcher` (a dedicated core-1 thread is a
     device-tuning step, not exercised by any probe; deferred)*;
   - **core 2 canonical main thread (queue owner)**: U5, U7, U8, U6 dispatch/stitch — *landed (`canonicalDispatcher`, dedicated single thread)*;
   - **cores 3–4 canonical chunk workers + background**: U6k, U0, U9, P preflight (low thread priority / big.LITTLE to little cores) — *landed: U6k chunk
     workers on a shared low-priority daemon pool (`Thread.MIN_PRIORITY`, `DEFAULT_CHUNK_PARALLELISM = 2..4`, never stealing the last core); U0/U9/P still
     on `backgroundDispatcher`*.
3. **Priority guarantees (four levers; all required)**:
   (1) **Isolation**: foreground/canonical/background on separate tracks, never queued on the same thread;
   (2) **Preemption**: global epoch; every job carries `(prio, epoch, key)`; B2 `ensureActive` per chapter; B1 enqueueing yields B2 — *landed (P2/P7)*;
   (3) **Lock discipline**: `tempStateLock` only for window state (U2 commit, U3, U4); canonical uses **local shapes**
       (`fullLayout` never writes the shared `blockShapeCache`) ⇒ P1, P2 (foreground) never wait for P3 (canonical) — *landed*;
   (4) **Thread priority / core affinity**: background thread-level below foreground — *landed for U6k workers (MIN_PRIORITY daemon pool)*.
4. **Parallel canonical (U6k)**: slice the chapter into block chunks (following `localLineRanges`(:589) and `fullLayout`'s local shapes),
   stitch the continuous line flow, then paginate; **must equal sequential canonical page-by-page** (probe T6).

*Landed: `BoxChapterLayouter.fullLayoutChunked(prepare, profile, contentW, contentH, pairing, parallelism, checkpoint)` — the U6k unit. `[prepare]` (the
heavy whole-chapter cascade) is computed once; the expensive per-block StaticLayout shaping is sliced into `parallelism` contiguous block chunks and shaped
across a shared low-priority daemon chunk pool over DISJOINT leaves (read-only prepare → no lock). The stitch + pagination (`completeFullLayout` — line
rebuild from the whole-chapter structure, drawable, `Paginator.paginate`, `backfillBlockRanges`) is shared with `fullLayout`, so the chunk boundary only
re-orders WHERE the shaping happens: **chunked slices are equal to sequential page-by-page by construction**. The per-block `[checkpoint]` runs inside
every chunk (a background cancel throws `CancellationException`; the remaining futures are cancelled — abandon at ~chunk granularity, ≤1 chapter); a
`Future.get() → ExecutionException` is unwrapped so the caller sees the underlying `CancellationException`, never a truncated product. Wiring: B1
(anchor-canonical, `startAnchorStream`) and B2 (`fullLayoutAndPersist`) route chapters ≥ `CHUNK_CANONICAL_MIN_BLOCKS` (= `SMALL_CHAPTER_BLOCKS + 1`, i.e.
only truly large ones) through `fullLayoutChunked`; small chapters keep the sequential shape (chunking buys nothing below 2 chunks). Foreground paths
(`prepareRelayout` small-branch, `layout()`, `ensureChapterLayout`) are untouched and stay sequential.*

**Abandon/re-deploy**: all jobs keyed + checkpoints + atomic commits (§3); `isActive` before each U6k chunk.
**Risk**: on a 2-core device it degrades to "core 1 and core 2 share a core, chunk workers merged in" — same semantics, only lower parallelism.
**Acceptance**: new probe T6 (chunked == sequential); manual flip p95 not degraded; anchor shaping no longer robbed by parse.
*Accepted: probe T6 `ChunkedCanonicalEquivalenceProbeTest` — (1) `fullLayoutChunked` with parallelism 2 AND 4 equals `fullLayout` page-by-page (char/line/
block ranges on every slice + the same line-stream length) on a 240-block plain chapter and on a styled chapter (pre / ul / ol / container margins/
padding / `display:none` leaves); (2) a throwing checkpoint propagates `CancellationException` and abandons before every block (never a truncated product);
(3) integration: entering a 160-block chapter (B1 anchor-canonical dispatch) shapes on the chunk workers, persists a disk table that equals a fresh
sequential `fullLayout` canonical page-by-page under the same params. Both suites green (`:common:jvmTest`, `:app:testDebugUnitTest`) and
`:app:assembleDebug` builds. Flip-p95 / core-affinity tuning remains a device-step (recorded above).*

---

### P14【Phase 2·medium】Cache invalidation and rebuild strategy (lifecycle matrix landed) — new semantics ✅ (2026-09-18; disk-LRU cap + count test; both suites green)

**Current problem**: keyed by `paramHash`, but (a) `prepareRelayout`:920 invalidates every other chapter's memory bindings each round while disk files are
never cleaned (`PaginationCacheStore` has no LRU); (b) it is unverified whether the structure fingerprint (`useOriginalStyle`/author CSS) is in the key —
if not, "should-invalidate-but-hits"; (c) no book-version dimension.

**Plan (design spec §7)**:
1. **Two-tier view**: incremental table is a short-lived session object, dead on leave; full table is paramHash-keyed and reusable across sessions (§8.5).
2. **Lifecycle matrix** (per design spec §7.2, fully landed):
   - param change / view-size change (paramHash includes contentW/H ⇒ equivalent to a param change) → discard all;
   - structure-fingerprint change → must invalidate (the validity key must include the fingerprint; see 3);
   - open book / jump: full-table hit → discard temp, promote; in-window hit → no discard; **outside window → discard-and-restart**;
     miss → discard/new session;
   - **leave current chapter (all ways, P11) → unified discard of the temp table**; full table kept, background persist if not ready;
   - non-typography params (brightness/night/eye-care) → keep; layout failure → discard half-products and rebuild (with P1's birth commit as a guard);
   - book content version change → discard (book-level key adds `bookVersion`).
3. **paramHash completeness — VERIFIED (2026-09-18, no gap)**: `LayoutParamKey` covers `useOriginalStyle` (:28,:46) and `userCssHash`
   (:31,:49). No user-CSS feature exists (all `fromProfile` call sites pass `userCssHash=0`), so the slot is dormant. Author CSS is per-chapter and
   invariant per book file; re-imports insert a NEW `bookId` row (new `book_<id>` namespace), so changed content cannot stale-hit.
   `PaginationCacheStore.LAYOUT_VERSION` separately guards engine-geometry changes. → No code change needed for this item.
4. **Disk housekeeping**: LRU-clean old-hash files (add a size/count cap). *Landed: `PaginationCacheStore` trims `write` to
   `MAX_TABLES_PER_BOOK = 32` files per book (least-recently-USED by last-modified; each table ≤ ~32 KB ⇒ ≤ ~1 MB/book at the cap);
   a successful `read` refreshes the file's last-modified time so eviction tracks last used, not last written. Idempotent, metadata-only, thread-safe by
   construction. The book-level key is the `book_<id>` directory namespace itself — re-imports get a fresh `bookId` so changed content can never
   stale-hit (recorded in item 3); `clearBook` remains the delete/eviction path.*

**Abandon/re-deploy**: discard-then-rebuild; file cleanup idempotent.
**Acceptance**: per-branch probes in the matrix (T7 covers window-outside discard-and-restart); both suites + a disk-file count test.
*Accepted: disk-file count test landed in `PaginationCacheStoreTest` (write trims to the per-book cap, last-used ordering beats unread-but-newer).*

---

## 5. Implementation Phases and Milestones

| Phase | Content | Exit criteria |
|---|---|---|
| **Phase 1 responsiveness + priority** | P1, P2, P3, P6, P7, P11 (+ P14's paramHash completeness check kept upfront) | both suites green + probes T1–T5, T8 green; manual checklist (seek-drag, in-panel flipping, flipping to previous chapter from head) has no misposition/bounce; re-entry position correct after jumping away |
| **Phase 2 window and cache semantics** | P10, P14, P12, P4, P5 | window stays contiguous + far-end eviction + discard-and-restart (T7); disk-file LRU works; cross-chapter consecutive-flip parse leaves the critical path; open-book first screen improved |
| **Phase 3 concurrency scheduling** | P13, P8, P9 | chunked canonical == sequential canonical (T6); flip p95 not degraded; "save→relocate" stable with Rectification D |

Each phase records its own log (at the end of this file or in the investigation report's "Implementation Log").

## 6. Acceptance and Regression

- Existing regressions: `:common:jvmTest`, `:app:testDebugUnitTest` (incl. `TempNavBackwardRegressionTest`,
  `IncrementalReplayEquivalenceProbeTest`).
- New probes (in the style of `TempNavBackwardRegressionTest`):
  - **T1 (P1) birth-window flip concurrency**: flipping during anchor shaping → waits for birth then lands the correct page; no duplicate blocks/bounces;
  - **T2 (P6) seekTo burst dedup**: N bursts → only the last lands; concurrent tasks ≤ 2;
  - **T3 (P2) two consecutive param changes**: the canonical finally persists with the last paramHash; old job cancelled;
  - **T4 (P3) enter-large-chapter-then-immediately-leave-and-return**: disk table survives; no repeated re-temp;
  - **T5 (P7/P13) whole-book mid-cancel + scan order**: after cancel exits within ≤1 chapter granularity; scan order follows direction×distance;
  - **T6 (P13) chunked canonical == sequential canonical** (reuse/extend `IncrementalReplayEquivalenceProbeTest`);
  - **T7 (P10/P14) window + discard-and-restart**: continuous flips keep the window contiguous, far-end eviction, window-outside jump leaves no half-contiguous residue;
  - **T8 (P11) jumping away leaves no stale session**: after toc/seek the old chapter's inProgress is cleared; re-entry does not land on the old anchor.
- Manual checklist: seek-drag, TOC jump, new-book first screen, consecutive neighbor-chapter flips, flipping while panel-dragging, flipping to previous
  chapter from head, returning to the original chapter after jumping away;
  metrics: flip p95, jump first-frame, open-book first screen.

## Appendix: Improvement ↔ design spec / investigation report mapping

| Item | Design spec | Report entry | Description |
|---|---|---|---|
| P1 | §8.2 birth window | R1 (contract #1 hole) | birth-window atomization + flip wait |
| P2 | §5.4 B2 abandon | R4 (contract #4) | whole-book epoch-ize / re-dispatch / B2 trigger |
| P3 | §8.5 table lifetime | R3 (contract #6) | canonical slot semantics: queue-not-kill |
| P4 | §2 U0/U1, track P | R6 + responsiveness | cross-chapter preflight |
| P5 | §2 track P | responsiveness | open-book preflight |
| P6 | §5.4 j-slot | responsiveness (new) | jump cancel/dedup/feedback |
| P7 | §5.4 checkpoints | timely abandonment (new) | ensureActive checkpoints |
| P8 | §1 head-lift | R2 | head lift (correctness debt) |
| P9 | §5.3 profile | R5 | out-of-lock profile (low) |
| P10 | §6 window | new window semantics | bounded contiguous sliding window ±N + discard-and-restart |
| P11 | §8.3/§8.4 | new finding | unified promotion on leave + cross-chapter tail anchor |
| P12 | §5.4 U9 | abandonability (new) | B2 scan ordering (direction×position×distance) |
| P13 | §2/§5 concurrency | new | unit decomposition U0–U9 + 4–5 core layout |
| P14 | §7 cache lifecycle | new | discard matrix + paramHash verification + LRU |