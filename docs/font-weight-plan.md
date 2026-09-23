# Font Weight Settings — Feature Plan (Deferred)

> Status: **Planned only — not implemented.** This document records the intended design for
> reader font-weight control so the direction is captured before any code is written.

## Goal
Let the user control text weight in the reader across different font types, from true variable
fonts down to single-weight static fonts, with the best visual quality each font can offer.

## Design ideas

### 1. Variable fonts — true continuous adjustment
When the selected font is a variable font, drive the `wght` axis natively so weight changes are
smooth and lossless.

Candidate variable fonts to support:
- Source Han Sans VF
- Source Han Serif VF
- Noto Sans SC Variable
- Roboto Flex
- Roboto Serif
- Plus Jakarta Sans Variable

### 2. Static fonts with multiple discrete weights — snap + visual simulation
When the font is not variable but ships several discrete weights:
- If the requested weight is **near** an existing discrete weight, snap to that real weight so
  it renders at its true design.
- If the requested weight is **far** from any existing weight, fall back to **visual simulation**
  (e.g. fake bold / synthetic weight), optionally gated behind a toggle.

### 3. Static fonts with a single weight — simulated only
When the font provides only one weight:
- Always use visual simulation (synthetic weight).
- Optionally gated behind a toggle (persistent across the categories above).

## Open questions / notes (not decided yet)
- Whether the whole weight system is exposed as one scalar (e.g. -100..100) or a weight-per-slot
  model, and how it fractions with the existing font-replacement tiers.
- Whether "visual simulation" toggling should be one global switch or per-font.
- Persistence and per-book overlay integration.