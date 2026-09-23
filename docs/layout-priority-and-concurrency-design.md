# Layout Engine Priority and Concurrency Design (Final)

> Version: v1 (2026-09-18, converged through multiple rounds of discussion)
> Sources:
> - Investigation report [`docs/incremental-vs-full-layout-investigation.md`](incremental-vs-full-layout-investigation.md)
>   (Steps 0–6: 7 contracts + R1–R6 risks)
> - Earlier design baseline `docs/incremental-layout-plan.md`
> - Implementation roadmap and phases: [`docs/layout-priority-and-responsiveness-improvements.md`](layout-priority-and-responsiveness-improvements.md)
> This document is the **target-architecture spec**: tracks and units, unified flow, priority contract, concurrency scheduling,
> temp table window, cache invalidation strategy, and boundary rules.
> Line numbers refer to the current `app/src/main/java/...` version.

---

## 0. Goals

1. **User responsiveness**: first-frame latency as low as possible for TOC/annotation/seek jumps, in-chapter/cross-chapter flips, and opening a new book;
2. **Priority**: write the "who outputs, who takes effect, when promoted, who yields" between foreground incremental layout (A) and
   background full layout (B) as executable rules; close the only high-severity hole (the temp session birth window);
3. **Background job lifecycle**: when to abandon, abandon fast, and re-dispatch safely after abandoning;
4. **Concurrency**: decompose layout into independently schedulable units that run on dedicated tracks across a modern 4–5 core
   processor with **guaranteed priority**.

---

## 1. Terms and Tracks

### 1.1 The two layout tables

| Table | Granularity | Lifetime | Storage | Key |
|---|---|---|---|---|
| **Full table (canonical)** | line-level (`LINE_DISK`) | cross-session; reusable while paramHash unchanged | disk `PaginationCacheStore` + in-memory `unit.paginationTable/layout/pageSlices` | `paramHash` |
| **Incremental table (temp)** | block-level (`BLOCK_TEMP`, `InProgressPagination`) | **session only**; discarded on leave/promotion | memory (one StaticLayout per page) | none (not persisted) |

### 1.2 Four tracks

| Track | Content | Threads/cores | User-perceivable | Priority |
|---|---|---|---|---|
| **F** | main-thread render, gestures, `jumpTo`/`setPage` | Main (core 0) | yes | highest (natural) |
| **A** | foreground shaping: display anchor page, flip on-demand shaping, window pre-shape | dedicated foreground thread (core 1) + the flip-calling thread | yes | **1** |
| **B** | B1 = current-chapter canonical; B2 = remaining-chapter scan | canonical main thread (core 2) + chunk workers (cores 3–4) | no | **2** (B1 > B2) |
| **P** | preflight (parse + light prepare up front; neighbor/open-book) | background (small/idle core) | no | **3** (weakest) |

Tracks never share a work-thread-queue (structural isolation, §5.2); B1/B2 share the canonical main thread with
checkpoint preemption guaranteeing B1 priority (§5.2-2).

---

## 2. Work Units and Dependency Graph

`U0`–`U9` are the engine's minimum schedulable units (mapped to existing functions, not new abstractions).

| Unit | Content (= existing function) | Depends on | Idempotent/cacheable | Priority class |
|---|---|---|---|---|
| U0 | parse markup (`ensureMarkup`) | — | idempotent, droppable | P4/P3 |
| U1 | structure + light prepare (`prepareLight`, structureCache) | U0 | structure-cache reused | P1/P4 |
| U2 | **display target page**: anchor shaping (`shapeAnchorPageForward`) | U1 | required for display path | **P0** |
| U3a | one page backward (`shapeTempPageBackward`, incl. anchor-line line-cut) | U2 (anchor line/block, backFront) | once per window | P1(flip)/P2(background) |
| U3b | one page forward (`shapeTempPageForward`) | U2 (nextBlock/nextLine) | once per window | P1/P2 |
| U4 | window maintenance: ±N contiguous sliding window, far-end eviction, atomic anchor-block pair | U3a/U3b + flip/jump events | — | P2 |
| U5 | heavy prepare (`prepare`, whole-chapter geometry) | U0 | reused by paramHash | P3 |
| U6 | whole-chapter shaping (`fullLayout`, **chunk-parallel U6k**) | U5 | once per chunk | P3 |
| U7 | pagination + backfill (`Paginator.paginate` + `backfillBlockRanges`) | U6 | — | P3 |
| U8 | **atomic commit** (`finishCanonicalBackground`: persist + bind) | U7 | commit point | P3 |
| U9 | remaining-chapter scan (per-chapter U0+U5–U8 loop) | — | per-chapter checkpoint | **P4** |
| UR | main-thread render/landing (`setPage`/`jumpTo`) | any target page | — | P0 (main) |

DAG (three parallelism surfaces):

```
U0 → U1 → U2 → U3a ┐
              └─→ U3b ┴──⟶ U4 (window)                       〔A: display/flip/window〕
        U2 shown synchronously (UR)
        after U2: U5 → U6(chunked) → U7 → U8 (atomic commit/persist)   〔B1: current canonical〕
        after U8/idle: U9 (remaining chapters, per-chapter + checkpoint)〔B2: whole book〕
```

- **U2 ∥ U5**: display page does not block canonical preparation;
- **U3a ∥ U3b ∥ U5**: window's two pages + canonical prep are three independent parties;
- **U6 internally chunk-parallel**: slice by block, shape chunks, stitch the continuous line flow, then paginate.

---

## 3. Unified Flow

### 3.1 Preconditions and branches

- **Miss (unified flow)**: relayout / not-yet-laid-out / jump-miss are **isomorphic**; all converge on `startAnchorStream` (as today).
  Only **large chapters** (`totalBlocks > SMALL_CHAPTER_BLOCKS=120`) take the anchor stream; small chapters foreground full-layout + persist directly.
- **Full-table hit**: `ensurePageRangeShaped` the target window only; no pre-shape, no canonical job.
- **Temp-table hit**: see §6 window semantics (meaningful only for "within an active session, continuous flip"; outside the window or after
  leaving there is no "hit").

### 3.2 Unified miss flow (steps)

| Step | Content | Unit | Sync/background |
|---|---|---|---|
| 1 | parse + structure/light prepare | U0→U1 | background (preflight may finish earlier) |
| 2 | **display target page** (anchor sync shaping + birth commit point) | U2 | **synchronous (calling thread)** |
| 3 | ±1 window shaping (prev/next page, incl. line-cut or boundary) | U3a/U3b | background (depth=1); flip on-demand fallback |
| 4 | current-chapter canonical full layout (queued once window ready) | U5–U8 | background (core 2 + cores 3–4) |
| 5 | remaining-chapter scan (ordered per §5.4) | U9 | background (lowest) |

### 3.3 Per-chapter state machine

```
EMPTY ──(U0,U1)──▶ SHOWING ──(U3±1)──▶ WINDOWED (active session; temp authoritative)
                                        │
        ◀── promote(finalizeTempOnLeave)  ├─────── continuous flips: window slides (U4)
                                        │
                            ┌──────────┴──────────┐
          chapter-head/leave (unified, §8.3)     param change (discard ALL)
                            │                          │
                            ▼                          ▼
                  BOUND (disk full table effect)     EMPTY (rebuild at target char)
```

Per-chapter canonical track (background): `IDLE → PREPARING(U5) → CHUNK-SHAPING(U6) → PAGINATING(U7) → TABLE-READY(U8)`
— after TABLE-READY the table is **not actively adopted** (S5); it is only used at the §8.3 boundary/leave.

---

## 4. Priority Contract (formal rules)

1. **F > A > B1 > B2 > P**. Lower tracks never block higher tracks; lower tracks never hold their thread more than one yield point.
2. **A never waits for B**. B only produces "standby" results; promotion happens only via `finalizeTempOnLeave` at chapter-head/leave (S5);
   never a table switch or in-place redraw mid-session.
3. **A yields to a newer A**: new params/new target (epoch+) → drop old `prefillJob`, old shape cache, old jump.
4. **B1 exception semantics: same-chapter new params → cancel and re-layout; cross-chapter entry → queue, don't kill**
   (idempotent; persisting keeps the table alive, §8.5).
5. **B2 is the easiest to abandon**: cancel on param/jump/chapter-direction change (checkpoint ≤ 1 chapter granularity);
   re-dispatch with the latest hash after params settle.
6. **P always yields first**: only idempotent preflight; cancel has no side effects.
7. **Window-outside = discard-and-restart**: any discontinuous movement discards the whole session; if the full table is ready, promote it directly
   (P10/P11).

---

## 5. Concurrency Scheduling

### 5.1 4–5 core layout

| Core | Owner | Units | Thread priority |
|---|---|---|---|
| core 0 | main thread | UR, gestures | default |
| core 1 | **foreground shaping dedicated thread** (moved out of Default pool) | U2, U3a/b (on flip), display shaping on other paths | normal |
| core 2 | **canonical main thread** (queue owner) | U5, U7, U8, U6 dispatch/stitch | normal |
| cores 3–4 | canonical chunk workers + background | U6k, U0, U9 | below foreground |

- Today the relayout loop / jumps / pre-shape all pile onto `Dispatchers.Default` (`backgroundDispatcher`:148) → anchor-page shaping and
  pre-shape fight each other for cores. Move to the core-1 dedicated thread; route pre-shape/scan to the low-priority track.

### 5.2 Priority guarantees (four levers; all required)

1. **Isolation**: foreground/canonical/background on separate tracks, never queued on the same thread → no mutual starvation.
2. **Preemption** (same-thread priority inversion removed): global `layoutEpoch` (param/jump/chapter-direction change increments);
   every background job carries `(prio, epoch, key)`. B2 calls `ensureActive()` per chapter; B1 enqueueing yields B2.
   Param change cancels the old canonical re-layout.
3. **Lock discipline**: `tempStateLock` guards only window state (U2 **commit**, U3a/b, U4); canonical uses **local shapes**
   (`fullLayout`:322-325 never writes the shared `blockShapeCache`); U8 is the only atomic commit point.
   ⇒ P1 never waits for P3, and P3 never waits for P4.
4. **Thread priority / core affinity**: background threads below foreground; guide to little cores on big.LITTLE.

### 5.3 Concurrency-window matrix (who writes/reads in parallel with whom)

| Writer | Reader | Lock | Parallelizable |
|---|---|---|---|
| U2 commit / U3 / U4 (window state) | flip thread | `tempStateLock` | single-writer in window |
| U6 (chunk shaping, local arrays) | UR (render) | none (visible only after U8 commit) | yes |
| U8 (commit/persist/bind) | UR / flip | atomic (reference swap) | yes |
| U0/U1 (parse/structure) | anyone | none shared (idempotent) | yes |
| `profile` (main-thread write) | shaping threads | volatile semantics | reads visible |

### 5.4 Background abandonment and re-deployment protocol

- **Key**: `(kind, chapter, paramHash[, target])`, all idempotent (replaying the same computation has no side effects).
- **Slots**: `prefillJob` (A; re-dispatched on flip/new stream); `canonicalJobs[chapter]` (B1; same-chapter re-layout / cross-chapter queue);
  `wholeBookJob` (B2; re-dispatched at the params-settled point); `preflightJobs` (P); `jumpJob` (jump dedup).
- **Checkpoints**: B2 before each chapter, U6 between chunks `ensureActive()`; anchor shaping is pure CPU — its abandon point is **before**
  shaping (skip the whole segment when a higher epoch arrives).
- **Commit points**: only (a) inside the `tempStateLock` critical section, or (b) U8's atomic commit, may write shared state
  → cancellation never lands dirty.
- **No mid-session switch**: when B finishes it only hangs on `ip.canonicalLayout` + persists; the §8.3 boundary takes it later.

---

## 6. Temp Pagination Table: Bounded Contiguous Sliding Window

### 6.1 Window definition

- An active session maintains a **bounded contiguous window ±N** around the current page (default N=1, tunable 1–2); N=1 = "prev + cur + next".
- **Contiguity**: whole-block pages require `page[k].blockEndExclusive == page[k+1].blockStart`.
  The only exception is the anchor block's **torn pair** (the anchor page starting from the anchor line, and its backward neighbor, the
  "front-row page" sharing the anchor block via line-cut) — both must be treated as atomic; kept or dropped together.

### 6.2 Continuous flips: slide (no discard)

- After a flip consumes one side, the window slides: the newly exposed side is backfilled by **background pre-shape (depth=1)**;
  if pre-shape hasn't landed in time, `tempNav`'s **on-demand shaping** covers (today's `tempNav`:742-747 semantics).
- Every flip calls `scheduleTempPrefill` to cancel the old pre-shape and re-dispatch per the new pointer (keeping findAdjacentPage:1251 semantics).
- **Far-end eviction**: when the window exceeds depth, remove from the **far end** (only tails, never the middle; the anchor torn pair is atomic);
  each page is an independent StaticLayout, so eviction frees memory — the window has a memory upper bound
  (today `forwardPages/backwardPages` only grow: flipping to the chapter tail can accumulate scores of pages — fixed).
- Window depth is symmetric (±N equal) ⇒ forward/backward flip latency risk is symmetric (optimal under a 50/50 flip probability;
  no "forward-first" or water-fill algorithm needed).

### 6.3 Discontinuous movement: discard and restart

- **Any window-outside jump** (TOC/seek/annotation/same-chapter far jump/cross-chapter): discard the whole window/session and restart the
  anchor stream at the **target char** (`startAnchorStream` whole-stream-replacement semantics);
  **if the full table is ready, promote it directly** (discard the incremental table, locate by char) instead of starting a temp stream.
- Hence "partial-discard / half-contiguous" states **do not exist** — this removes two bug surfaces (including R1's birth-window pollution,
  which is "a discontinuous item mixed into the window").

### 6.4 Pre-shape strategy

- Forward depth = **1** (today 4; saves 3 pages of background CPU); re-dispatch to refill after each consumed flip;
- On-demand fallback guarantees the second rapid flip never blanks (cost ≤ one page shaping, on the flip thread).

> **Landed (P10, 2026-09-18)**: §6 implemented as `TEMP_WINDOW_DEPTH = 1` with `WinUnit.{Fwd,Bwd,Pair}` units,
> `enforceTempWindow` far-end eviction (after every move AND every prefill append), head-edge-derived backward seam,
> tear detection via `slice.charStart > globalCharStarts[blockStart]`, and `tempWindowSnapshot` for probing.
> Details + the two bugs the probe surfaced (locateTempPosition charStart key; stepTempPrefill liveness/trim) in the
> investigation report Implementation Log (P10), probe `WindowSlideDiscardProbeTest` (T7).

---

## 7. Cache Invalidation and Rebuild Strategy

### 7.1 Two-tier view

- **Incremental table**: a short-lived session object; dead on leaving; only discarded early by "param change / window-outside jump / full-table hit".
- **Full table (disk)**: `paramHash`-keyed, reusable across sessions; "invalidation" = miss + memory-binding release; the disk file is kept by hash
  and LRU-cleaned.
  ⇒ **Table lifetime ≠ session lifetime**: after leaving a chapter, canononical may still persist in the background (B1 queue-not-kill);
  re-entering = hit.

### 7.2 Lifecycle matrix

| Event | Incremental table | Full table |
|---|---|---|
| Typography param change (font size/line-height/margins…) | discard, rebuild | invalidate (clear memory binding; new disk per new hash) |
| View-size change (rotate/fold/split-screen) | discard | same (`paramHash` includes contentW/H ⇒ equivalent to a param change) |
| Structure-fingerprint change (original-book settings/author CSS) | discard | **must invalidate** (the validity key must include the fingerprint; see §9) |
| Open book / jump: full-table hit | discard (promote) | use |
| Active-session · in-window jump | no discard | if B1 finished, coexist; no mid-session switch |
| Active-session · window-outside jump | **discard-and-restart** (promote if full table ready) | use if ready |
| Open book / jump: miss | discard/new | defer until B1 produces the table |
| **Leave current chapter** (flip boundary/chapter jump/TOC/seek/new book) | **unified S5 discard** (promote or invalidate) | keep; persist in background if not ready |
| Re-entering the same chapter | new | hit once ready |
| Non-typography params (brightness/night/eye-care) | keep | keep |
| Book content version change (re-import/update) | discard | discard (book-level key adds bookVersion) |
| Layout failure / exception | discard half-product, recover/rebuild | untouched |

### 7.3 Rules

1. Param/size change → **discard all** (every chapter's incremental + in-memory full-table bindings); LRU-clean old-hash disk files
   (**landed (P14)**: `PaginationCacheStore` trims `write` to 32 tables/book by last-used, `read` bumps last-modified so eviction is LRU-by-use).
2. The structure fingerprint (`useOriginalStyle`/author CSS) must participate in table-validity checks (§9 verifies `LayoutParamKey` coverage).
3. Every leave goes through §8.3 (unified promotion), eliminating "stale session residue / re-entry lands on the old anchor".
4. Layout failure discards half-products by default and rebuilds on next entry (with the birth commit point guarding half-initialization).

---

## 8. Boundary and Correctness Rules

### 8.1 S5 promotion
The temp table is authoritative only within a session; reaching the chapter head / any leave → `finalizeTempOnLeave` (BookDocumentController.kt:792):
if canonical is ready, bind + clear the session; if not, invalidate (with B1 queue-not-kill persisting in the background to keep the table alive).

### 8.2 Birth-window atomization (fix R1)
`startAnchorStream` (:557) reorder: shape the anchor page **into locals first**, then commit
`bindInProgress + clear old caches + fill window + setCurrentTempPage` atomically inside `synchronized(tempStateLock)`;
add a "birth flag / CompletableDeferred" so flips `await` (≤800ms) before re-reading state when not committed.
⇒ flips no longer hit half-initialized windows; also blocks "flipping inside an unbound window falls onto stale pageSlices".

### 8.3 Unified promotion on leave
**Every way of leaving the current chapter** (flip-out-of-bounds, chapter jump, TOC, seek bar, opening a new book) runs S5 for the old chapter first.
Today's gap: `tocJump`/`seekTo`/`jumpChapter` don't finalize the old chapter (leave `inProgress` behind; re-entry can land on the old anchor) → fill in during implementation.

### 8.4 Cross-chapter landing = tail anchor (flip continuity preserved)
Backward out-of-first-page lands on the **previous chapter's last page** (`crossChapterLanding`'s `backwardEntryAnchorChar`:817),
not the TOC-jump home-page semantics. Cross-chapter miss = **restart as a jump**; no "previous-chapter tail pre-shape".

### 8.5 Table lifetime ≠ session lifetime
When B1 finishes (U8), keep the result even if the reader already left (disk table); cross-chapter entry does **not** cancel the previous
chapter's canonical (queue-not-kill), avoiding "a just-tuned chapter re-temps because you flipped away".

---

## 9. Deferred / To-Verify

| Item | Status | Checkpoint |
|---|---|---|
| Async font readiness | **deferred discussion** | whether `fontPairing()` puts the final usable font metrics into `LayoutParamKey`; if fonts become ready after table build, whether "ready → discard and re-layout" is needed |
| `paramHash` completeness | **verified (2026-09-18, no gap)** | `LayoutParamKey` covers `useOriginalStyle` (:28,:46) and `userCssHash` (:31,:49). No user-CSS feature exists (all `fromProfile` calls pass `userCssHash=0`), so that slot is dormant. Author CSS is per-chapter and invariant per book file; a re-import inserts a NEW `bookId` row (new `book_<id>` cache namespace), so changed content cannot stale-hit. `PaginationCacheStore.LAYOUT_VERSION` additionally guards engine-geometry changes. |
| Annotation jump | not implemented | path isomorphic to TOC; reuse §6.3/§7 semantics when landing |

---

## 10. Acceptance Baseline

- Regressions: `:common:jvmTest`, `:app:testDebugUnitTest` (incl. `TempNavBackwardRegressionTest`,
  `IncrementalReplayEquivalenceProbeTest`).
- Probes: T1 birth-window concurrency / T2 seekTo dedup / T3 two consecutive param changes /
  T4 enter-large-chapter-then-immediately-leave (disk table survives) / T5 B2 mid-cancel ≤1 chapter /
  T6 chunked canonical == sequential canonical / T7 window contiguity+eviction+discard-and-restart /
  T8 jumping away leaves no stale session.
- Manual checklist: seek-drag, TOC jump, new-book first screen, consecutive flips, flipping while panel-dragging, flipping to previous chapter from head.
- Metrics: flip p95, jump first-frame, new-book first screen.