# Preset Manager — Feature Plan (Deferred)

> Status: **Planned only — not implemented.** This document captures the intended design for the
> reading-theme preset manager so the direction is recorded before any code is written.

## Goal
Improve the reading-theme **preset manager** page that currently holds the custom-color
sliders (red/green/blue/gray) plus save/delete.

## Design ideas

### 1. User custom preset list at the top
Add a user-defined preset list at the top of the preset-manager page, built like the **font list**
(list of fonts), with **one custom preset per row**.

### 2. Swipe-to-delete (reuse the font-list animation)
Deleting a preset is done by **swiping left/right** to slide in/out a delete button, using the
**same animation as the existing font list** — the swipe/delete interaction should be **reused**
(e.g. a shared swipe-to-reveal-delete component) rather than reimplemented.

### 3. Rename the gray slider to "dark ink"
Rename the gray-slider label (currently the Chinese word for gray-scale) to the Chinese word for dark ink.

## Notes / open questions (not decided yet)
- Whether the swipe-handling component exists today and is directly reusable, or needs to be
  extracted into a shared widget first.
- Save behavior: whether it still writes the current RGB / dark-ink mix as a new preset, and how
  it maps to the color-name auto-labeling (`labelFor`).