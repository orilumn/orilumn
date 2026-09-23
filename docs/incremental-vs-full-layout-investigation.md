# Incremental Layout of the Current Chapter vs. Background Full-Book Relayout — Priority Investigation Report

> Status: **Complete (2026-09-18, Steps 0 / 0.5 / 1–6 all recorded)**. Each step was recorded as it finished.
> If the R1–R6 fixes are implemented later, append an "Implementation Log" section at the end of this file.
> Related docs: [`docs/incremental-layout-plan.md`](incremental-layout-plan.md),
> [`docs/PROJECT_STATUS.md`](PROJECT_STATUS.md).
> The design spec derived from this report: [`docs/layout-priority-and-concurrency-design.md`](layout-priority-and-concurrency-design.md);
> the implementation plan: [`docs/layout-priority-and-responsiveness-improvements.md`](layout-priority-and-responsiveness-improvements.md).
> Follow-up plan "Rectification F: three-thread scheduling priority for incremental pagination" belongs to the same domain.

## 0. Investigation Plan

### 0.1 Terms and the two tracks

- **A · Incremental relayout of the current chapter**: foreground, position-preserving relayout. Entry: `ReaderActivity.scheduleRelayout`
  → `BookDocumentController.prepareRelayout`. For large chapters, or when disk cache misses, `startAnchorStream` starts an anchored
  temp stream (`InProgressPagination`); the anchor page is shaped synchronously, background `tempPrefillJob` pre-shapes both
  directions, and `tempNav` shapes on demand while flipping. Small chapters are `fullLayout`-ed directly in the foreground.
- **B · Background full relayout of all chapters**: the background canonical, line-level, whole-chapter layout persisted to disk.
  - Current-chapter canonical: `anchorBackfillJob` (launched inside `startAnchorStream`, skipped when `deferCanonical` is true);
  - Full pre-layout of every other chapter: `otherChaptersJob` (launched by `finalizeRelayoutAll`, only when the settings panel is
    closed and layout genuinely changed).
  - Both share a **single-thread** `canonicalDispatcher` (FIFO).

### 0.2 Questions to answer for every scenario

1. In this scenario, which of A/B starts first, delivers first, and whose output actually takes effect (display/persist)?
2. How are conflicts arbitrated: `layoutMutex` / `tempStateLock` / `deferCanonical` / `Job?.cancel()` / single-thread FIFO?
3. On which side is the position-preservation / no-blank / no-jump contract implemented?
4. Is the temp-table ↔ disk-table switch point (S5: "on chapter head/reach or leave → discard temp table, enable disk pagination table") broken in this scenario?
5. Concurrency-window risk: which shared state is read/written concurrently without a common lock?

### 0.3 Scenarios and key source anchors (starting points, not conclusions)

| Scenario | Entry | Key stack |
|---|---|---|
| S1 TOC/annotation(not implemented)/seek jump, full table miss | `tocJump`→`openChapterStart` / `seekTo`→`pageAtFraction` | `ensureChapterLayout`→`buildLayout`→(large→`startAnchorStream`; small→foreground full layout + sync persist) |
| S2 Parameter change at chapter head | `previewLive`/`commitSettings(typographyChanged=true)`→`scheduleRelayout` | `prepareRelayout`→`startAnchorStream(ch=current, anchor=head)` |
| S3 Parameter change mid-chapter (non-first page) | same, anchor=current page charStart (mid-chapter) | `prepareRelayout`→`startAnchorStream`(anchor line block-cut) |
| S4 S2/S3 + immediate flip while incremental not finished | flip=`doFlip`→`findAdjacentPage`→`tempNav` | `tempStateLock`/half-initialized `InProgressPagination` window |
| S5 Flip to previous chapter right after head adjustment (full table miss) | same dir=-1 | `tempNav` hits Boundary→`finalizeTempOnLeave`→`crossChapterLanding`; single-slot `anchorBackfillJob` stolen by the new chapter |

### 0.4 Method

- Static tracing (with `file:line` references); concurrency windows inferred from a "shared state × lock coverage" matrix;
- Verify pure navigation-decision functions against existing JVM tests (`TempNavBackwardRegressionTest` etc.);
- Baseline health: run `./gradlew :common:jvmTest` and `:app:testDebugUnitTest` (record whether the environment can run);
- Each scenario: conclusion (who wins) + mechanism (how) + risk (where it can backfire).

---

## 1. Execution Log (appended in order as each step completes)

### Step 0.5 — Baseline health check (2026-09-18)

- `./gradlew :common:jvmTest` → **BUILD SUCCESSFUL** (includes pure-function navigation tests like `TempNavBackwardRegressionTest`).
- `./gradlew :app:testDebugUnitTest` → **BUILD SUCCESSFUL** (includes Robolectric
  `IncrementalReplayEquivalenceProbeTest`, covering disk-hit incremental playback ≈ canonical geometry).
- Existing test anchors relevant here (all green): `TempNavBackwardRegressionTest`
  (`tempNavBackwardPointerStep` in-chapter / chapter-head / cross-chapter boundary decisions), `OpenAtAnchorTest` (anchor stream continuity).
- Conclusion: the code under investigation compiles, existing navigation-decision unit tests match the behavior described, tracing is valid.

---

### Step 1 — Scenario 1: TOC/annotation/seek jump, full pagination table miss

**Entries (all are background coroutines, not the main thread):**

- TOC: `tocJump`→`controller.openChapterStart(idx)` (`ReaderActivity.kt:672-680`)—`ensureChapterLayout(ch)` (target char=0)—`buildLayout(unit, 0)` inside `layoutMutex`.
- Seek bar: `seekTo`→`pageAtFraction` (`ReaderActivity.kt:560-566` → `BookDocumentController.kt:1134-1146`) → same `ensureChapterLayout(c)`.
- Annotation jump: **not implemented** (`onBookmark`/`onNote` only toast, `ReaderActivity.kt:303-304`); the path will be identical to TOC.

**Disk-miss branch (`buildLayout`: `BookDocumentController.kt:416-523`):**

1. `paramHash` computed from the current profile; `PaginationCacheStore.read` misses.
2. Cheap `prepareLight` first (3–40ms when structure cache hits) to decide size:
   - **Large chapter (blocks > 120)** → `startAnchorStream(unit, targetChar=0, ...)` (`:472-484`):
     anchor page shaped **synchronously** (`shapeAnchorPageForward`, ~350–550ms scale), then `scheduleTempPrefill`
     (background pre-shape, `backgroundDispatcher`=default `Dispatchers.Default`), then `launch(canonicalDispatcher)` starts
     **this chapter's** canonical full layout (`anchorBackfillJob`, when `deferCanonical=false`, `:602-613`).
   - **Small chapter** → foreground whole-chapter `fullLayout` + **synchronous persist** (`:491-511`).

**Conclusion (who wins / who delivers):**

- **A (incremental) delivers the target page directly; the reader waits only for A's own anchor shaping; B (background) never blocks the jump.**
  Large-chapter jump latency ≈ `prepareLight` + anchor-page sync shaping; B starts on its own single-thread `canonicalDispatcher`
  only **after** the anchor page is bound, so B starts computing after the reader already has content.
- **B never preempts display mid-session**: `finishCanonicalBackground` (`:844-873`) only persists the table and hangs it on
  `ip.canonicalLayout/canonicalSlices`; it **explicitly does not switch mid-session** (S5: at chapter head/leave, `finalizeTempOnLeave`
  enables the disk pagination table). So no matter when B finishes, the current page is still the temp-table page — no flash, no jump.

**Arbitration mechanism:**

- Jumps are serialized: `ensureChapterLayout`/`buildLayout` hold `layoutMutex` throughout (`:166, :356`).
- No stale-B interference here: if the jump target uses new params (or old params were cleared by `invalidateLayout`), the disk table
  is looked up exactly by `paramHash` (`PaginationCacheStore.file`/`read`); an old table file can never be mis-hit (`:121-122, :126-160`).

**Risks:**

- B's slot is **single-slot single-chapter** `anchorBackfillJob` (`:185`, `:603-604`). Jumping to **another** large chapter calls
  `anchorBackfillJob?.cancel()` — the canonical work of the previous (possibly just-left) chapter is **dropped** (see Step 5's finale).
  `layoutMutex` serializes jumps/chapter builds but does not protect this single slot, so "jumping between two large chapters" voids the
  first chapter's B and it starts over.
- If a live relayout (`prepareRelayout`, which does not hold `layoutMutex`; see Steps 2/4) is running during a jump, the two paths can
  concurrently modify the same chapter's state.

(Step 1 complete.)

---

### Step 2 — Scenario 2: typography-affecting parameter change at a chapter **head**

**Entry:** slider `previewLive` (drag) or `commitSettings(typographyChanged=true)` (release/toggle)
→ `scheduleRelayout` (`ReaderActivity.kt:704-707, 683-687, 772-813`) → live relayout loop
(`Dispatchers.Default`) → `prepareRelayout(channel=current chapter, anchorChar=current page charStart)`.

**`prepareRelayout` (`BookDocumentController.kt:917-964`):**

1. **Clears every other chapter first**: `for ((i,u) in chapters.withIndex()) if (i != chapter) u.invalidateLayout()` (`:920`).
   The current chapter is **not** cleared (old drawable kept as a backdrop to avoid white flash).
2. Large chapter → `startAnchorStream(unit, anchorChar, ...)` (`:941`):
   - Drops the old shape cache `unit.blockShapeCache = null` (blocks re-shaped under the new params, `:574`);
   - The new anchor page is shaped **synchronously** and becomes an `InProgressPagination` session;
   - `tempPrefillJob?.cancel()` → restarts bidirectional pre-shape (`:673`);
   - **`anchorBackfillJob?.cancel()` → re-runs current-chapter canonical with the new `paramHash`** (`:603-604`, when `deferCanonical=false`).
3. Small chapter → foreground `fullLayout` + `lineAnchoredPage` aligned to anchor char (`:946-955`, not persisted).

**When the settings panel is open (`deferCanonical=true`, `ReaderActivity.kt:590`):**
`startAnchorStream` **skips B** (the `:602` else branch logs `canonical deferred`). That is, **while the panel is open B is fully suppressed, only A runs** —
a deliberate priority choice: "live-drag temp shaping does not compete with whole-chapter layout for CPU". When the panel closes and layout
genuinely changed, `closeSettingsPanel`→`finalizeRelayoutAll` runs "A(current) + B(whole book)" once (see Step 6 summary).

**Conclusion:**
- On a head adjustment, **A takes over immediately and preserves position** (anchored to the current page's charStart; chapter head anchors to cast=head).
  B (this chapter's canonical) is **cancelled and restarted** with new params; while the panel is open B is fully deferred. At a chapter head, because the
  anchor is at the head, the temp and canonical tables share a natural page-boundary alignment (`anchorBlockStart≈0`, backward is `Boundary`);
  no "temp head page-boundary ≠ canonical" divergence occurs.

**Risk:**
- The "lift anchors within the first 100 blocks of the chapter up to the head (anchorChar=0)" logic of `HEAD_START_BLOCK_LIMIT=100` (`:537`)
  **exists only in a comment** (`:474-475, :531-537`); `startAnchorStream` does not implement it. For any mid-chapter anchor (see Step 3),
  backward pre-shape is not lifted to 0, unlike the canonical table which always starts at 0 — page boundaries may diverge at the seam.
  The homonymous `backFrontBlock` is also never set to 0. Recorded in the risk list.

(Step 2 complete.)

---

### Step 3 — Scenario 3: typography-affecting parameter change **mid-chapter (non-first page)**

**Entry identical to Step 2; the only difference is `anchorChar = current page charStart` (mid-chapter).**

**Behavior:**
1. `startAnchorStream` anchors on the mid-chapter char: the anchor page starts at the **line containing the anchor char** (block-cut, `shapeAnchorPageForward`);
   preceding lines (front rows of the anchor block) are filled by backward shaping; following pages continue block by block.
2. **B is still cancelled and restarted as this chapter's new-param canonical** (when panel closed); and B's canonical table is a **line-level** paging
   (`LINE_DISK`), structurally different from the temp table's **block-level** paging (`BLOCK_TEMP`) — exactly the switch point S5 manages.

**Conclusion (priority):**
- **Within a session A is authoritative; outside/after leaving, B is authoritative**: while the reader stays in the chapter, display/flipping is fully
  driven by A's temp table; B's finished table just hangs on `ip.canonicalLayout` and `unit.paginationTable` **on standby** (not applied mid-session).
  Only on reaching the chapter head or leaving does `finalizeTempOnLeave` (`:792-803`) switch:
  - B ready → `unit.bind(canonicalLayout, canonicalSlices)` + `clearInProgress()` (in-place switch without flash);
  - B not ready → `unit.invalidateLayout()` (discard temp session; next entry restarts from scratch; disk table may have been supplied by B or re-triggers a miss).
- Therefore "mid-chapter param change + keep reading in place" is an A-exclusive zone; B's output takes over only at boundary points.
  This is S5's intended priority, not a race.

**Risks:**
- With a mid-chapter anchor, temp page boundaries differ from canonical ones. If the user saves progress inside a session, the saved `charStart`
  is the **temp table's** char; on reopen, if the disk table is ready, `locateStart`→`pageForChar` looks it up in the **canonical table** and may land on
  a different page (same root cause as TODO "Rectification D: unstable reading position", `docs/TODO-未尽事宜.md:5-9`).
- Because `startAnchorStream` replaces `unit.inProgress` and the shape cache without holding `layoutMutex`/`tempStateLock`,
  "flip immediately during relayout" can hit the half-initialized window (detailed in Step 4).

(Step 3 complete.)

---

### Step 4 — Scenario 4: S2/S3 param change, immediate flip before the incremental relayout finishes

**Entry:** `doFlip`→`findAdjacentPage` (`Dispatchers.IO`, `:1220-1322`). The flip thread reads `unit.inProgress` (`:1243`),
calls `locateTempPosition` (`:1246`), then `tempNav` (`:725-786`, holding `synchronized(tempStateLock)` throughout),
and on success `scheduleTempPrefill` re-spreads the window (`:1251`).

**Priority conclusion (design intent vs. reality):**

- ✅ **Foreground flipping is always served immediately, never waits**: even if the far pre-shape window (`FLIP_AHEAD_PAGES=4`) is not fully spread,
  `tempNav` has an **on-demand shaping** fallback (`:588-589` comment, `:742-747` forward, `:762-773` backward) — no-blank is a hard constraint.
- ✅ **B does not compete with flipping**: B runs on the single-thread `canonicalDispatcher` (the `:600-601` comment says "does not grab the foreground flip threads");
  the flip thread's on-demand shaping in `tempNav` is the foreground path. And while the panel is open, B is suppressed entirely by `deferCanonical`.
- ⚠️ **But there is a "temp session birth window" race that `tempStateLock` does not cover** — the core defect of this scenario:

  `startAnchorStream` (on the `Dispatchers.Default` relayout-loop thread) executes (`BookDocumentController.kt:557-614`):
  1. `:574` drops the old block cache; `:577` `unit.bindInProgress(ip)` — **ip is completely empty at this point**
     (`forwardPages=[]`, `shapedForwardTo=anchorBlockStart` (`ChapterUnit.kt:287`), `curIsForward=true,curIndex=0`);
  2. `:581` **synchronously** `shapeAnchorPageForward` (~350–550ms) — this section **holds neither `tempStateLock` nor `layoutMutex`**;
  3. `:582-585` the anchor page is enqueued; `:590` `setCurrentTempPage(unit, ip, anchor)` finally lands the current page / render pointer.

  If a flip happens between 1 and 3, the flip thread (IO) enters `tempNav` (it can acquire the lock because R never takes it):
  - **Forward flip**: `forwardPages.size==0` → `:742` uses `shapedForwardTo=anchorBlockStart` to **on-demand shape a full page starting at the
    anchor block's top line** and stuff it into `forwardPages[0]`; then R's `:582` **appends** the anchor page to `forwardPages[1]` and `:590`
    `setCurrentTempPage(anchor)` overwrites the pointer and render → the anchor block is shown twice (next page = anchor page), or the pointer
    (F's return value) diverges from the rendered page (R's anchor page).
  - **Backward flip**: `tempNavBackwardPointerStep` moves back to the previously shaped front page and marks it a back page; then R's `setCurrentTempPage(anchor)`
    pushes the current pointer/render **back to the anchor page** → "flipped to the previous page, but the screen returned to the anchor".
  - Result: **during a temp session's birth, the foreground on-demand shaping and the background anchor shaping each think they own forwardPages[0]**,
    producing duplicate pages / jumps. This lands exactly in the un-coded part of TODO "Rectification F: three-thread scheduling priority" —
    `tempStateLock` serializes only "already-born sessions" (inside `tempNav`) and background pre-shape (`stepTempPrefill`),
    **it does not cover "the process of a session being created"**.
- One incidental low-priority race: flipping during a live param change, the shaping thread reads the **latest** profile and writes new-param shapes into the
  current stream's cache; later R's `startAnchorStream` discards everything (`blockShapeCache=null`, `:569-573, :574`) — at most one frame of mixed-param
  page, position preserved by char anchor. Acceptable, but still a consequence of "shaping outside the lock".

**Position preservation:** the param change itself preserves position via anchorChar=current page charStart (S2/S3); the race above only occurs
**while the anchor page is being shaped**.

**Risk matrix (concurrency window × lock coverage):**

| Writer | Reader | Same lock? | Risk |
|---|---|---|---|
| `stepTempPrefill` (background pre-shape) | `tempNav` (flip) | ✅ both hold `tempStateLock` | none |
| `startAnchorStream` (R thread) | `tempNav` (flip) | ❌ R holds no lock | **high (core of this step)** |
| `startAnchorStream` | `ensurePageRangeShaped`/`buildLayout` (jump, holds `layoutMutex`) | ❌ | medium (jump races relayout) |
| `finishCanonicalBackground` (B) | `finalizeTempOnLeave` (flip thread) | ❌ neither holds a lock (primarily ref swap) | low |

(Step 4 complete.)

---

### Step 5 — Scenario 5: flip to the previous chapter immediately after a head adjustment (current chapter full table miss)

**Entry:** the head anchor page is already displayed (`startAnchorStream` finished, so Step 4's birth-window race does not stack here).
`doFlip(dir=-1)` → `findAdjacentPage`:

1. Head temp pointer: `tempNavBackwardPointerStep(forward, 0, anchorBlockStart=0, ...)` →
   `endExclusive = backFrontBlock = anchorBlockStart = 0` → `Boundary` → `tempNav` returns null
   (`:753-776`, `:775-776`). **Chapter head is a boundary** — exactly S5's semantics (`ChapterUnit.kt:287,296`).
2. `tempExhausted` → short-circuits to `finalizeTempOnLeave` → `crossChapterLanding(chapter, -1)` (`:1263-1269`).

**`finalizeTempOnLeave` two branches (`:792-803`):**
- B ready (not usually the case for an "immediate" flip) → `unit.bind(canonicalLayout…)` + `clearInProgress()`: temp table voided, disk canonical table takes over;
  returning to this chapter = disk hit, fast.
- **B not ready (the norm for "immediate flip") → `unit.invalidateLayout()`**: temp session **cleared together with layout/disk table**
  (`ChapterUnit.kt:156-169`, including `paginationTable=null`, `paramHash=-1`). I.e. "didn't wait in time" costs **an entire chapter-cache rebuild**;
  next entry goes back to full miss → re-temp. The reader's instant cross-chapter experience is unblocked (A wins), but the position-preservation
  contract is paid as a cache-rebuild cost.

**`crossChapterLanding(dir=-1)` (`:810-837`):** target = previous chapter's **tail anchor** (`backwardEntryAnchorChar`;
parses the target chapter's markup before building it, `:808-809, :817`):
- Previous chapter **small** (≤120 blocks) → foreground full layout + sync persist, no canonical job;
- Previous chapter **large** → `buildLayout`→`startAnchorStream(anchor=tail)` → **`:603` `anchorBackfillJob?.cancel()`
  steals the slot** — **the just-adjusted chapter's in-flight canonical is cancelled** (unless it finished and persisted before the cancel).

**Conclusion (priority):**
- **A instant cross-chapter navigation > new chapter's B > old (adjusted) chapter's B**. The single-slot `anchorBackfillJob` (`:185`) means
  "entering another large chapter" **unconditionally sacrifices the old chapter's canonical full layout**; and per `deferCanonical=false` with the panel
  closed, that sacrificed chapter is exactly the one just re-tuned that needs its canonical table most. If the previous chapter is small, the old
  chapter's B can finish and persist (no steal).
- **S5 holds at the chapter head**: flipping at the head → Boundary → leaving triggers `finalizeTempOnLeave` → disk table. If B is not ready, it
  degrades to `invalidate` (forgo "enable disk table", fall back to "relayout / re-temp"); never an in-session mid-session table switch red-flash.

**Risks:**
- If the adjusted chapter is never revisited, and the previous chapter (a large one) stole the slot → that chapter's canonical never persists;
  it repeats "enter → temp → leave → invalidate".
- `finalizeTempOnLeave`'s invalidate runs synchronously on the flip thread (IO); if a small-chapter layout was paged in, the cost is a whole-chapter
  re-layout (a perceptible momentary wait).

(Step 5 complete.)

---

### Step 6 — Summary: A/B priority contract table + risk list

**"Priority" criteria (the four questions defined in Step 0):** who delivers the user-visible page first / who cancels whom /
who monopolizes critical resources (threads, locks, slots) / disk-table ownership and promotion timing. Per-scenario conclusions below.

**Contract table:**

| # | Conflicting parties | Winner / behavior | Arbitration mechanism | User-perceivable result |
|---|---|---|---|---|
| 1 | A (large-chapter jump/flip) vs B (any chapter canonical) | **A unconditionally instant**, B stands by only | B on single-thread `canonicalDispatcher` (`:600-601`); A on independent foreground Default/IO; B's output only hangs on, never switches mid-session | jump/flip has zero B latency, no mid-session flash |
| 2 | A (live param change) vs B (this chapter canonical) | A squeezes B | `deferCanonical` (panel open=true) → whole skip; `anchorBackfillJob?.cancel()` | slider tracks; B deferred until panel closes |
| 3 | B (this chapter) vs B (whole book) | this chapter enqueues first | `finalizeRelayoutAll` runs current chapter first (enqueued first) then whole-book (same single-thread FIFO, `:981-1005`) | current chapter's canonical table comes out first after panel close |
| 4 | B (whole book, old params) vs this-chapter B after another param change post-panel-close | old B keeps the queue slot, no cancel, no re-dispatch | `otherChaptersJob` only cancelled in `finalizeRelayoutAll` (`:991`); `startAnchorStream`/`scheduleRelayout` never touch it; scheduler never re-dispatches | other chapters persist with old params (wastes queue time); display/position unaffected (disk-table rights belong to the last param loop — waste, not error) |
| 5 | New A session vs old A session (repeated tuning / repeated temp) | new session wins | `tempPrefillJob?.cancel()` (`:673`) + `blockShapeCache=null` (`:574`) + old drop | tracks; old pre-shape yields, no mid-frame mixed layout (except Step 4 birth window) |
| 6 | Leave (flip) vs this-chapter B not ready | **leaving party instant**; B not ready → invalidate and start over | `finalizeTempOnLeave` binds only if ready, else discards session (`:792-803`); single-slot `anchorBackfillJob` stolen by new chapter (`:603`) | instant cross-chapter; adjusted chapter re-temps when returned (R3) |
| 7 | In-session A (temp authoritative) vs out-of-session disk canonical | A inside, B outside; promotion only at boundaries | S5: chapter head/leave only via `finalizeTempOnLeave` bind, never mid-session (`:843, :871-872`) | no flash/no wrong page in-session; canonical page boundaries once session ends |

**Risk list (by severity):**

- **R1【high】Temp-session "birth window" race** (Step 4): between `startAnchorStream`'s `bindInProgress`(:577) and `setCurrentTempPage`(:590)
  (~350–550ms of synchronous anchor-page shaping), no `tempStateLock`/`layoutMutex` is held; a flip in this window can on-demand-shape into
  `forwardPages[0]` → anchor block shown twice / page bounced back to anchor. Fix directions:
  ① wrap `startAnchorStream` wholly in `synchronized(tempStateLock)` (short critical section: shape outside the lock — **first shape the anchor page then**
  `bindInProgress`+land? No — flips need the lock to check for an empty stream); more robust: mark the "empty temp session" as non-navigable
  (a birth flag), `tempNav` sees it and waits/blocks until the anchor page lands; or eliminate the empty window between bind and setCurrent
  (shape first, then bind).
  ② lowest cost: `tempNav` returns null and goes cross-chapter when `forwardPages.isEmpty() && backwardPages.isEmpty()`
  and `anchorLineCharStart==0 && forwardFromLine==0` (unborn), while `setCurrentTempPage` holds the "birth" flag.
  **Decision before implementing requires reproduction on a real device.**
- **R2【medium】Head lift not implemented** (`HEAD_START_BLOCK_LIMIT=100` at `:531-537` is comment-only): mid-chapter anchors are not lifted to 0;
  temp and canonical tables may diverge at page boundaries; a `charStart` saved in-session may land on the wrong page after reopening against the
  canonical table (same root as "Rectification D").
- **R3【medium】`anchorBackfillJob` single slot stolen across chapters** (Step 5): the just-adjusted chapter's canonical is cancelled; returning re-temps.
  Fix: per-chapter slot map, or don't void an unpresisted canonical on cancel (fall back to "layout only if the chapter is entered first after a timeout").
- **R4【low-medium】`otherChaptersJob` stale queuing without re-dispatch** (contract #4): after panel close, further param changes leave other chapters
  queued with old params on the single thread, uncancellable. Fix: `startAnchorStream` (or `scheduleRelayout`) cancels + re-dispatches when it sees
  `otherChaptersJob.isActive`.
- **R5【low】Out-of-lock shaping profile race** (Step 4): flipping mid-tune may flash one mixed-param frame; position not lost. Acceptable.
- **R6【low】Cross-chapter large-chapter cost accumulates on the flip thread**: `ensureMarkup` (parse) + `buildLayout`
  (prepLight + anchor shaping) inside `crossChapterLanding` execute synchronously on the IO thread (`:817-818`); when B is not ready, the
  re-temp on return is also on that thread (companion of R3).

**Relationship to "Rectification F":** this investigation turns "three-thread scheduling priority" into the contract table #1–#7 and risks R1–R6:
- Already-coded priorities: A>B (#1), A squeezes B (#2), this-chapter B>whole-book (#3), in-session A/out-of-session B (#7), leaving party instant (#6);
- **The only un-coded gap, and a high-severity one: the temp-session birth window (R1)** — `tempStateLock` locks only the "already-born"
  tempNav/pre-shape, not "the birth process". R2–R4 are engineering debt, not priority-descendancy bugs.

**Suggested next step (if implementing):** for R1, first write a Robolectric or pure-function probe (doesn't need to be fast) that asserts
"on-demand shaping must not run while the stream is unborn" before deciding the fix; then apply R2/R3 under the existing Rectification D/F TODO list.

(Step 6 — this report complete. 7 contract items + 6 risks.)

---

_Investigation ended. If any of R1–R6 is implemented later, append an "Implementation Log" section to this document; do not overwrite the conclusions above._

---

## Implementation Log

### 2026-09-18 — R1 fix (P1) + P14 paramHash verification + R4 fix (P2)

- **P1 (R1, birth window) — `1d55b86`**: `startAnchorStream` shapes the anchor into locals, then commits
  «bindInProgress + fill window + setCurrentTempPage» atomically under `tempStateLock`; `ChapterUnit.tempBirth`
  (`CompletableDeferred`) released at commit/abandon; `findAdjacentPage` awaits the birth (≤3×800ms) then
  re-reads state. Probe: `TempBirthWaitRegressionTest` (4 cases). Both suites green.
- **P14 paramHash pre-check — `1562d22`**: `LayoutParamKey` covers `useOriginalStyle` + `userCssHash`; no
  user-CSS feature exists (all `fromProfile` calls pass 0); author CSS is per-book-file and re-imports get a
  new `bookId` namespace; `PaginationCacheStore.LAYOUT_VERSION` guards engine geometry. → No gap; recorded in
  design spec §9 and plan P14.
- **P2 (R4, whole-book epoch) — this change**: `prepareRelayout` bumps `layoutEpoch`; `startAnchorStream`'s B1
  carries the epoch and skips the whole segment when stale; `requestWholeBookRelayout()` (the params-settled
  B2 trigger, wired into `scheduleRelayout`'s loop end and `finalizeRelayoutAll`) cancels any in-flight
  whole-book job and re-dispatches with the CURRENT params, skipping the active chapter, checkpoint per
  chapter (`isActive`). Probe: `WholeBookRelayoutEpochProbeTest` (T3: two consecutive param changes → the
  canonical finally persists the LAST paramHash; B2 skips the active chapter). Both suites green.

### 2026-09-18 — R3 fix (P3): per-chapter canonical slot semantics ("queue-not-kill")

- **P3 (R3, contract #6) — this change**: the single global `anchorBackfillJob` slot became
  `canonicalJobs: MutableMap<Int, Job?>` keyed by chapterIndex (written only on the dispatch thread — no
  lock, matching the plan's plain-concurrent-container risk note). A B1 dispatch cancels ONLY ITS OWN
  chapter's previous in-flight slot (same chapter re-shaped with newer params — genuinely stale); ANY
  OTHER chapter's B1 is left running on the single-thread `canonicalDispatcher` FIFO queue and finishes
  its persist, so the just-tuned chapter's canonical is never killed by a cross-chapter flip.
  `finalizeTempOnLeave`'s miss branch still calls `invalidateLayout` (no logic change needed): since the
  leaving chapter's B1 now survives, the disk table lands regardless and the next entry is a disk hit
  (S5), not a repeated re-temp. A completed slot stays until the same chapter is next dispatched (its
  cancel is then a no-op).
- Probe: `WholeBookCanonicalQueueProbeTest` (T4) — two LARGE chapters (> `SMALL_CHAPTER_BLOCKS`=120
  leaves) driving the background canonical path: (1) ch0 dispatch → ch1 dispatch must NOT cancel ch0's B1;
  ch0's canonical still completes, binds its table with the dispatched paramHash and persists to disk; the
  leave-time hand-off then binds disk and re-entry is a disk hit with no re-temp anchor stream; (2)
  same-chapter new params settle the slot on the LAST paramHash. Both suites green.

### 2026-09-18 — Jump responsiveness (P6): single-slot jump gate (cancel/dedup/epoch-guard + feedback)

- **P6 — this change**: the reader's three jump paths (`jumpChapter`/`seekTo`/`tocJump`, `ReaderActivity`
  :551/:560/:672) each launched a fresh full anchor path with no cancellation, so a seek-drag / rapid
  burst stacked concurrent `pageAtFraction`/`openChapterStart`/`neighborChapterStart` runs that all
  landed over the position. New `orilumn.reader.ui.reader.JumpGate` is the single in-flight slot: every
  `submit` cancels the previous job and bumps an epoch; a resolved target only lands through the `land`
  callback when it is still the LATEST request (epoch guard closes the "cancel-not-yet-effective" race),
  so a burst converges on exactly one landing. A volatile `active` flag drives a thin gold top-edge load
  line in the bars (`jumpActive`, independent of bar visibility since TOC/seek jump while hidden). Seek
  drag itself already throttles in `ThinSlider` (one `onSeek` per release), so no extra drag dedup was
  needed. `kotlinx-coroutines-test` added as a test-only dep for the probe's virtual-time schedule.
- Probe: `JumpGateProbeTest` (T2) — (1) virtual-time: a 5-burst converges on exactly the LAST target
  landing with concurrent resolutions peak ≤ 2 and the indicator resets; (2) virtual-time: a long-running
  stale request superseded mid-flight can never land; (3) real `Dispatchers.Default` overlap: first seek
  truly started on a worker then hits a rapid 6-burst — only the last lands, peak concurrent ≤ 2 (the old
  code ran all N to completion). All three green. Pending verification on device: seek-drag show no
  stacking / position bounce and the top-edge load line appears and clears.

### 2026-09-18 — Timely abandon (P7): between-blocks cancellation checkpoint + pre-shape epoch skip

- **P7 — this change**: the canonical background paths could only be abandoned at a chapter boundary
  (P2's per-chapter `isActive`), so a cancel arriving mid-chapter still paid the whole chapter's shaping
  (≈100–500ms of pure CPU) before it took effect. Fill the two planned checkpoints:
  1. `BoxChapterLayouter.fullLayout` gained an optional `checkpoint: () -> Unit = {}` invoked once per
     block, immediately before `shapeLeaf`. B1 (anchor canonical, `startAnchorStream`) and B2
     (`fullLayoutAndPersist` via the whole-book scan) capture their launching context (`val ctx =
     coroutineContext`) and pass `{ ctx.ensureActive() }`; the foreground paths — `layout()`,
     `prepareRelayout`'s small-chapter branch, and `ensureChapterLayout`/`buildLayout` — keep the no-op
     default, so foreground shaping behaviour is byte-identical. A cancelled shape throws
     `CancellationException` out of `fullLayout` instead of returning a short product; the two callers'
     `onFailure`s now filter `CancellationException` so a timely abandon is not logged as FAIL. This is
     deliberate: a truncated product bound/persisted would corrupt the line-level disk table.
  2. `prepareRelayout`'s large-chapter branch captures `myEpoch` right after its `layoutEpoch++` and, after
     `prepareLight` and before the non-interruptible anchor shaping, returns null when `myEpoch !=
     layoutEpoch` — a newer relayout cycle that landed during `prepareLight` owns the anchor stream, so the
     stale segment is skipped before paying the 350–550ms shape.
  The B2 per-chapter `ensureActive()` (plan item 1) was already delivered by P2 and is unchanged.
- Probe: `WholeBookCancellationProbeTest` (T5, this step's half) — (1) unit/deterministic: `fullLayout`
  runs the checkpoint between blocks, a throwing hook propagates `CancellationException` (never a truncated
  product, abandon strictly before all blocks), and the default no-op path still produces a product covering
  the whole chapter; (2) integration: three rapid param cycles over 8 chapters (90 blocks each) cancel
  in-flight whole-book scans — every non-active chapter settles on the LAST paramHash with a COMPLETE table
  (`pages.last().charEnd >= totalChars`, i.e. no partial table escaped), the disk file for the last hash
  exists, and the active chapter stays on its anchor-owned DEFAULT table. Both suites green
  (`:common:jvmTest`, `:app:testDebugUnitTest`) and `:app:assembleDebug` builds. Scan-order half of T5
  (direction × distance) is P12 (Phase 2), not exercised here.

### 2026-09-18 — Unified promotion on leave (P11): jump paths finalize the old chapter's temp session

- **P11 — this change**: the reader's three jump routes (`jumpChapter`/`seekTo`/`tocJump`, `ReaderActivity`)
  resolved the target and landed without running S5's leave promotion on the chapter they left, so its
  `inProgress` anchor session survived and re-entry served the OLD anchor page (a perceived position
  bounce). Added a public `BookDocumentController.finalizeOnLeave(chapter)`: it routes into the S5
  `finalizeTempOnLeave` (canonical ready → bind + clear; not ready → invalidate, P3 keeps persisting) under
  `tempStateLock`, so it is serialized with `tempNav`/`stepTempPrefill`/the anchor commit and is
  idempotent. The two existing flip leave calls now go through the same `tempStateLock`-serialized helper
  (`finalizeTempSession`). Each `JumpGate.submit` lambda now runs `c.finalizeOnLeave(from)` before its
  target resolution. P11's item 2 (backward cross-chapter flip lands on the previous chapter's tail via
  `backwardEntryAnchorChar`, distinct from jump home-page semantics) was already in place and is kept
  guarded by the existing regression.
- Probe: `JumpLeaveFinalizeProbeTest` (T8) — (1) a LARGE chapter's live temp session is preserved by
  `ensureChapterLayout` (control, documents the pre-P11 bug) and then cleared by `finalizeOnLeave`;
  repeated finalize is a no-op; a jump resolves to the requested chapter; re-entry is either a disk hit or
  a FRESH session anchored at the requested head char — never the stale session object; (2) `finalizeOnLeave`
  is a no-op for an out-of-range or session-less chapter. Regression `TempNavBackwardRegressionTest` (12
  cases) green. Both suites green (`:common:jvmTest`, `:app:testDebugUnitTest`) and `:app:assembleDebug`
  builds.

### 2026-09-18 — Bounded sliding window (P10): temp pagination = contiguous window ±N with far-end eviction and discard-and-restart

- **P10 — this change**: `forwardPages/backwardPages` previously only grew; flipping toward the chapter tail
  accumulated scores of pages and unbounded forward-flip latency far out. The temp table became a **bounded
  contiguous sliding window** (`TEMP_WINDOW_DEPTH = 1`, replacing `FLIP_AHEAD_PAGES = 4`):
  - `enforceTempWindow(ip)` keeps the interval `[curPos−depth, curPos+depth]` as reading-order units
    `WinUnit.{Fwd, Bwd, Pair}` (`Pair` = the anchor-block **torn pair** — always kept/dropped together; a
    torn pair is detected as `slice.charStart > prepare.globalCharStarts[blockStart]`). Eviction runs after
    every temp pointer move AND after every background prefill append, deleting one page at a time from the
    **far end** only (never the current page, never a torn pair); page identity is recovered by object
    identity on re-list build. The head edge (`forward[0]`, `headEdgePage`) derives the backward seam once
    the true anchor is beyond the window; `tempNav` re-roots the forward front from
    `backward[0].blockEndExclusive` when eviction emptied the forward side (using the true `shapedForwardTo`
    would be WRONG there). Pre-shape depth is `TEMP_WINDOW_DEPTH * 16` with `lastPrefillCursor` dedup for
    identical targets; `scheduleTempPrefill` re-dispatches per the new pointer so a flip slides the window.
    `tempWindowSnapshot(chapter)` is a new public read under `tempStateLock` (exposes the head edge, both
    page lists as torn-aware slices, and the current pointer) for the probe.
  - Two latent bugs driven out by the probe: **`locateTempPosition`** matched pages by `blockStart` alone,
    so a torn backward-page slice could be re-routed onto the head-edge page and a forward re-flip would
    SKIP the head page — now keyed by `(blockStart, charStart)`. **`stepTempPrefill`** shaped the wrong
    neighbor (liveness conditions shaped the CURRENT side because `curIsBackward` doesn't exist) and never
    trimmed, so arrived prefill could grow the window unbounded — conditions rewritten and `enforceTempWindow`
    called after every append (its guard was also only exiting on the forward-empty branch, letting the
    backward side grow to 4 pages).
  - Window-outside movement is **discard-and-restart**: jumps already force a fresh `startAnchorStream`
    (`finalizeOnLeave` cleanly clears the session); "half-contiguous/partial-discard" states no longer exist.
- Probe: `WindowSlideDiscardProbeTest` (T7) — (1) continuous forward/backward flips: window stays ≤4 pages
  (≤3 non-torn), block contiguity holds in both directions (forward allows the one line-cut block shared with
  the next page, backward is exact), current char advances strictly monotonically and regresses back to the
  exact prior values; (2) a torn head edge (paragraph 60 carries `<br/>`×4 so it shapes multi-line — Robolectric
  otherwise reports one line per paragraph, which never yields a torn seam; production wrapping yields it
  naturally) stays atomic across 6 forward + 6 backward flips; (3) a far jump finalizes the session and the
  re-ensure is served by canonical or re-anchors a fresh compact window at the far block — never residue.
  Regression `TempNavBackwardRegressionTest` (12 cases) green. Both suites green (`:common:jvmTest`,
  `:app:testDebugUnitTest`) and `:app:assembleDebug` builds.

### 2026-09-18 — Cache invalidation & disk housekeeping (P14): LRU-bounded persisted-table directory + count test

- **P14 — this change**: the lifecycle matrix (§7.2) was already landed by P7/P10/P11 (param/size discard-all,
  unified leave-discard, window-outside discard-and-restart) and the `paramHash` completeness check (fingerprint,
  author-CSS book-namespace, `LAYOUT_VERSION`) was verified earlier (P1), leaving only the open disk-housekeeping
  item. **`PaginationCacheStore` now LRU-trims**: `write` caps the book's table directory at
  `MAX_TABLES_PER_BOOK = 32` files (≤ ~1 MB/book; each table ≤ ~32 KB), deleting the least-recently-used `.bin`
  tables beyond the cap; a successful `read` refreshes the file's last-modified time so eviction tracks **last used**
  rather than last written (a param-cycled reader's active hash is never evicted, orphaned-param-hash files are).
  Trimming is metadata-only, idempotent, and safe from any thread (never touches table contents), so no lock.
  The book-level key is the `book_<id>` directory namespace itself — re-imports/updates get a fresh `bookId` and
  can never stale-hit; `clearBook` remains the explicit delete/eviction path.
- Test: `PaginationCacheStoreTest` gains the disk-file count case — two seeded tables aged to distinct mtimes, a
  `read` of the older one proves last-used bumps above an unread-but-newer file, then 31 more writes cross the cap
  (peak 33 → 32) and the least-recently-USED file is the one evicted while the recently-read one survives.
  `PaginationCacheStoreTest` 6/6 green; both suites (`:common:jvmTest`, `:app:testDebugUnitTest`) and
  `:app:assembleDebug` builds.

### 2026-09-18 — Remaining-chapter scan ordering (P12): B2 follows reading direction × distance

- **P12 — this change**: the whole-book B2 scan (`requestWholeBookRelayout`) used to visit chapters by ascending
  index, blind to the target — it laid out irrelevant chapters before nearby ones, so a flip-out-of-bounds into a
  nearby chapter could land on an un-laid-out chapter. Now a pure ordering function
  `orderRemainingChapters(total, current, direction)` feeds the scan: the **reading-direction group first**
  (forward → chapters ahead of the active one; backward → behind), **nearest-distance-first inside each group**
  (`ahead = {current+1…}`, `behind = {current−1…}`), so the chapters a flip-out-of-bounds can land in are always
  pre-laid-out first while every other chapter still completes its full pass + persist. Correctness never depends
  on the order; the P7 per-chapter checkpoints keep abandonment ≤1 chapter.
- **Direction tracking**: a `@Volatile readingDirection` (defaults to +1, the dominant direction) is updated at the
  single reader-facing flip entry `findAdjacentPage` — reached by every real page turn, in-chapter temp/canonical
  and out-of-bounds cross-chapter alike — plus the direct `nextPageInChapter`/`prevPageInChapter` curl-adjacent
  entries. The B2 coroutine pulls its scan from `remainingScanOrder(activeChapter)` (the active chapter is still
  skipped — B1 owns its canonical pass). Probe/test accessors `remainingScanOrder`/`currentReadingDirection`
  (`internal`, following the `tempWindowSnapshot` precedent) let the probe assert the exact coroutine path.
- Probe: `WholeBookScanOrderProbeTest` (T5's scan-order half; abandonment/≤1-chapter stays with
  `WholeBookCancellationProbeTest`) — (1) unit: forward vs backward group order, nearest-first within each group,
  edge currents at both book ends are correct one-sided scans, and an exhaustive permutation check over
  `total ≤ 6 × every current × both directions` (each non-current chapter appears exactly once, no duplicates);
  zero direction stays total (falls into the forward group, since the tracker defaults to +1); (2) integration: a
  real backward then forward `findAdjacentPage` flip on the active chapter drives `currentReadingDirection()` and
  reorders `remainingScanOrder(1)` between `[0,2]` and `[2,0]`; the direct `nextPageInChapter`/`prevPageInChapter`
  entries set the direction too. Both suites green (`:common:jvmTest`, `:app:testDebugUnitTest`) and
  `:app:assembleDebug` builds.

### 2026-09-18 — Cross-chapter preflight (P4): neighbor parse + light-prepare leave the flip thread

- **P4 — this change**: crossing chapters used to run the neighbor's parse (`readChapter` → `convertWithStyles` +
  per-chapter CSS bundle) + light cascade synchronously on the flip thread inside `crossChapterLanding`'s
  `ensureChapterLayout`, so a first-visit crossing stacked tens of ms of parse on the critical path. A new
  background P track preflights it: `preflightNeighbors(chapter)` fires from the flip entry (`findAdjacentPage`)'s
  `.also` after every successful landing and prewarms BOTH out-of-bounds neighbors (`chapter−1`/`chapter+1`, bounded
  by the book) with `ensureMarkup` + `prepareLight` only — no shaping, no table. `preflightJobs[chapter]` is the
  per-chapter slot on `backgroundDispatcher` (yields naturally; `prepareRelayout` cancels all slots + voids records
  via `voidPreflight`), and `preflightReadiness[chapter] = paramHash` makes it idempotent — an already-prepped
  chapter or the same in-flight target is skipped. The active chapter is never preflighted (B1 owns it). Since
  preflight sets `unit.markup` and warms `unit.structureCache` (structure-keyed: cssBundle + `useOriginalStyle`, so
  it survives typography-param-only changes), `ensureChapterLayout` on the crossing skips parse and its
  `prepareLight` reuses the warmed structure — the landing is left with window/anchor shaping only, exactly the
  plan's item 2. `findAdjacentPage` was split into the public entry (direction tracking, P12, + landing `also`)
  and a private `navigateAdjacentPage` carrying the old body.
- Probe: `CrossChapterPreflightProbeTest` — (1) a flip landing in ch1 prewarms ch0 and ch2 in the background
  (markup non-null + `preflightReadiness` record without either ever visited/laid out), then after advancing to
  ch1's last page the out-of-bounds crossing into ch2 happens strictly AFTER ch2's markup was parsed by preflight
  — the parse is off the crossing's critical path; (2) a typography-param change through `prepareRelayout` voids
  the readiness record (next flip re-preflights). Both suites green (`:common:jvmTest`, `:app:testDebugUnitTest`)
  and `:app:assembleDebug` builds.

### 2026-09-18 — Open-book preflight (P5): first screen promotes the persisted window instead of re-laying-out

- **P5 — this change**: opening a book (or the bookshelf tapping one) previously ran the saved-position chapter's
  parse + light + pagination entirely after `open()`, so the first screen rebuilt from scratch every time. New
  `BookDocumentController.prewarmForOpen(chapter = -1, targetChar = -1)` runs on the background P track from a
  **single per-book slot** (`openPreflightJob`; another `prewarmForOpen` / a book switch / `prepareRelayout`'s
  `voidPreflight` cancels it — P4's per-chapter `preflightJobs` slots are untouched). The two args default to the
  open point's saved position (`startChapter`/`startChar`; no progress → chapter 0), so the engine itself resolves
  the plan's "unknown target" and a caller only overrides when it knows better. The job does `ensureMarkup` +
  `prepareLight` (recording `preflightReadiness[chapter] = paramHash`, idempotent like P4) and, **when a disk
  table exists for the CURRENT params**, `bindPaginationTable` + buildLayout's **disk-hit inline shaping** (first
  page window, `pagesToShape = 4`, `shapedPageFrom/To` set) — the chapter is `laidOut` with real `pageSlices`
  before any `ensureChapterLayout`, so the open is a *promote*, not a re-layout. The disk-hit branch deliberately
  mirrors buildLayout rather than calling `ensurePageRangeShaped`: a fresh chapter's `pageSlices` are EMPTY, so
  that helper early-returns on the first visit. `ReaderActivity.openBook()` (the repo's real open path — the
  bookshelf layer is a placeholder stub) calls `c.prewarmForOpen()` right after `open(...)` and before
  `locateStart()`, so tap→first-screen work is parse + light + (on disk hit) the promote.
- Probe: `OpenBookPreflightProbeTest` — (1) `prewarmForOpen(1, 0)` with no disk table parses + light-prepares ch1
  (markup + `isChapterPreflightReady`, pages unshaped) while ch0/ch2 stay untouched; (2) a first session persists
  ch1's disk table (90-block fixture < `SMALL_CHAPTER_BLOCKS`, foreground full-layout write), a fresh controller
  with the same `cacheRoot` opens the book and `prewarmForOpen(1, 0)` then binds the table AND pre-shapes the
  page window (`pageSlices` non-empty, `laidOut`) strictly before any `ensureChapterLayout`; the default-target
  path (no progress → ch0, parse-only since ch0 has no table) is covered by `prewarmForOpen()`. Both suites green
  (`:common:jvmTest`, `:app:testDebugUnitTest`) and `:app:assembleDebug` builds.

### 2026-09-18 — Chunked canonical / U6k (P13): parallel chunk shaping that EQUALS sequential canonical

- **P13 — this change**: the canonical full-chapter shape (U5 heavy prepare + U6 whole-chapter StaticLayout
  shaping + U7 paginate + U8 commit) serialized the entire chapter on the dedicated `canonicalDispatcher` single
  thread, so a large chapter's canonical sat as one long CPU burst with no structural isolation from the
  foreground track. New `BoxChapterLayouter.fullLayoutChunked(prepare, profile, contentW, contentH, pairing,
  parallelism, checkpoint)`: the heavy `[prepare]` cascade is still computed once; the expensive per-block
  `shapeLeaf` pass is sliced into `parallelism` contiguous block chunks and shaped across a **shared, lazily
  created, low-priority daemon chunk pool** (`Thread.MIN_PRIORITY`, fixed size `DEFAULT_CHUNK_PARALLELISM =
  2..4`, never stealing the last core). The stitch + pagination were factored into `completeFullLayout` (line
  rebuild from the whole-chapter structure, `BoxDrawableLayout`, `Paginator.paginate`, `backfillBlockRanges`),
  shared by `fullLayout` and `fullLayoutChunked`, so the chunk boundary only re-orders WHERE shaping happens —
  **slices are equal to sequential page-by-page by construction** (disjoint leaves / read-only prepare → no
  lock needed). The P7 per-block `checkpoint` runs inside every chunk worker; a background cancel's
  `CancellationException` abandons the remaining futures (`Future.get()` is unwrapped from `ExecutionException`
  so the caller sees the real `CancellationException`, never a truncated product), i.e. abandon at ~chunk
  granularity ≤1 chapter. Wiring: B1 anchor-canonical and B2 `fullLayoutAndPersist` route chapters ≥
  `CHUNK_CANONICAL_MIN_BLOCKS` (= `SMALL_CHAPTER_BLOCKS + 1`) through the chunked path; small chapters and all
  foreground paths keep the sequential shape.
- Probe: `ChunkedCanonicalEquivalenceProbeTest` (T6) — (1) `fullLayoutChunked` at parallelism 2 and 4 equal
  `fullLayout` page-by-page (charStart/charEnd/firstLine/lastLineExclusive/blockStart/blockEndExclusive on every
  slice + same line-stream length) on a 240-block plain chapter and a styled chapter (pre / ul / ol / container
  margins+padding / `display:none` leaves); (2) a throwing checkpoint propagates `CancellationException` and
  abandons before every block is shaped (calls < totalBlocks, no truncated product); (3) integration: entering a
  160-block chapter (B1 anchor-canonical dispatch) shapes on the chunk workers and persists a disk table that
  equals a fresh sequential `fullLayout` canonical page-by-page under the same params. Both suites green
  (`:common:jvmTest`, `:app:testDebugUnitTest`) and `:app:assembleDebug` builds. Flip-p95 / dedicated core-1
  foreground-thread tuning remains a device step (recorded in plan P13).
### 2026-09-18 — Head lift / R2 (P8)

- **P8 — this change**: `startAnchorStream` anchored its temp table at a LINE-CUT mid-chapter page even for a
  reader only a few blocks into the chapter — a pagination source different from the canonical (chapter-head)
  table, so near-head temp↔canonical boundary divergence and later positioning errors (and "save→relocate"
  disagreement) were inherent. Now, after `anchorBlock = prepare.blockIndexForChar(anchorChar)`, blocks
  `1..HEAD_START_BLOCK_LIMIT` (=100, ≈the first ~5 pages) are lifted to block 0: the session anchors at the
  TRUE chapter head (block 0 / char 0 → `anchorLineCharStart = 0`), so the temp and canonical paginations share
  the same head source. The lift is logged (`EngineLog.w`) with the ≤100-block retreat as its known cost; char
  anchors deeper than the limit pass through untouched. `HEAD_START_BLOCK_LIMIT` became `internal` + a
  `headStartBlockLimit()` controller accessor (android unit tests cannot resolve main-source-set top-level
  `internal`s — only controller members, precedent `tempWindowSnapshot`).
- Probe: `HeadLiftProbeTest` — (1) `ensureChapterLayout(0, paraCharStart(50))` on a 240-block chapter lifts to
  `anchorBlockStart == 0` with the current page at block 0/char 0; (2) a deep anchor `paraCharStart(200)` stays
  at block 200 with its page starting at that block's char; (3) boundary exact: block
  `HEAD_START_BLOCK_LIMIT` lifts while `LIMIT+1` anchors in place (fresh controller per case — a repeat ensure
  reuses the existing session). The P10 T7 fixture's `<br/>` paragraph was moved from block 60 (inside the lift
  zone — its mid-paragraph anchor would now land on the lifted head page instead of a torn edge) to block 150
  beyond the limit, keeping the torn-pair atomicity exercise intact; the offset helper accounts for the 4
  `<br/>` text chars shifting later blocks. Both suites green (`:common:jvmTest`, `:app:testDebugUnitTest`) and
  `:app:assembleDebug` builds.

### 2026-09-18 — Out-of-lock shaping profile race / R5 (P9)

- **P9 — this change**: document-only. R5: a flip racing a live tune (`setFontSize`/`setLineHeight`/…) can shape one
  frame with a torn pair of layout-param snapshots (`ip.profile`/`contentW`/`contentH` are swapped outside
  `tempStateLock` while temp-shaping reads them). Purely cosmetic (single mixed-param frame, no position loss). No code
  change landed: the corrective fix (freeze `ip.profileSnapshot` at `startAnchorStream`, shape uniformly from snapshots)
  is deferred until Phases 1/2 changes (foreground/isolation, chunked canonical, head lift) are live and R5 is actually
  observed; recorded in the plan as ✅ documented.

### 2026-09-18 — R5 corrective fix lands: temp sessions shape from a frozen profile snapshot (P9 follow-up)

After Phases 1/2/3 went live (chunked canonical, head lift, foreground isolation), the deferred P9 fix was implemented
in the same session rather than waiting for a live observation — it is low-risk, well-scoped, and removes the one
remaining shared-state race in the temp path:

- `InProgressPagination` gained `profileSnapshot: TypographicProfile` (ChapterUnit): the profile **frozen at session
  birth**, with a doc note that temp shaping must read THIS snapshot and never the live controller `profile`.
- `startAnchorStream` captures `val snapshot = profile` once; the anchor page shapes from it
  (`shapeAnchorPageForward`) and the value is passed into `InProgressPagination(profileSnapshot = …)`.
- All six temp on-demand/prefill shaping call sites switched from the live `profile` to `ip.profileSnapshot`:
  `shapeNextBackward`, `stepTempPrefill` (forward), and `tempNav`'s forward-append / forward-re-root / backward-shape.
- Because `TypographicProfile` is an immutable `data class`, a reference IS the snapshot; no copy required.
- Canonical (B1/B2) and small-chapter foreground paths intentionally keep the live `profile`: they are invoked under the
  current epoch/hash (tune→relayout re-anchors them with the new hash), so they are not part of the R5 frame-mix
  race. `ip.contentW/contentH` were already session-frozen constructor vals.
- No behavioral probe added, per the plan ("fix is speculative, triggered only by a live-tune observation"); the temp
  temp suites regress it: `TempNavBackwardRegressionTest` (12 cases), `WindowSlideDiscardProbeTest` (T7), plus the full
  `:common:jvmTest` and `:app:testDebugUnitTest` — both green, `:app:compileDebugKotlin` green.
