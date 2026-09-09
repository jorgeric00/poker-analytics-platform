# Statistics Module — Stat Catalog & Breakdowns

## Overview

The Statistics module (defined at product level in `05-product-map.md`) is the most complex screen in the application: it's configurable, detailed, and its exact contents depend on researching **PokerTracker 4** as a reference. This document is the deep dive for that research — it exists separately from `05-product-map.md` because the catalog below will grow large and detailed, and it shouldn't dilute that document's high-level, all-modules view.

**Scope boundary:**

- `05-product-map.md` owns the product-level "what/why" (purpose, filters, navigation, out-of-scope boundaries) — that content is not duplicated here.
- This document owns **which stats and breakdowns exist and how they're organized** for the player.
- The future `07-analytics-engine.md` (Phase 5) will own **how each stat is calculated** (formulas, data model, aggregation logic) — not this document.

Question answered:

> Which stats and breakdowns does Statistics offer, and how are they organized?

---

## Research Status

**Not started.** Tracked as an open follow-up in `11-development-tracking.md`: *"Statistics module: study PokerTracker 4 to determine the final stats library and breakdown dimensions..."*. The sections below are placeholders to be filled in directly as part of that research.

---

## Stat Catalog

_TODO — to be populated from the PokerTracker 4 research._

Expected shape (to confirm/refine during research):

- Grouped by category, e.g.:
  - Preflop (VPIP, PFR, 3-bet, fold-to-3-bet, steal, ...)
  - Postflop (c-bet, fold-to-c-bet, aggression factor, WTSD, W$SD, ...)
  - Tournament-specific (ICM, ROI, bubble factor, ...)
  - Cash-specific (BB/100, ...)
- For each stat: name, definition, applicable Game Type(s) (Cash / Tournament / both), default visibility (shown by default vs. opt-in).

---

## Breakdown Dimensions

_TODO — to be refined from the PokerTracker 4 research._

`05-product-map.md` already names example dimensions (position, street, stakes, hand type) as illustrative, not final. This section should define:

- The full list of supported breakdown dimensions.
- Which dimensions are valid to combine together.
- Which dimensions apply only to Cash vs. only to Tournament vs. both.

---

## Open Questions

- What's the minimum-viable stat set for v1 vs. stats that can be added later?
- Which breakdown dimensions pair meaningfully with which stats (not every combination is useful)?
- How do cash and tournament stats differ in practice for the same underlying dimension (e.g. does "stakes" mean buy-in tier for tournaments)?
- Should any PokerTracker 4 stats be excluded as out of scope for v1, and why?

---

## Screen Design

No canvas exists yet for Statistics — see `12-ui-designs.md`. Once the Stat Catalog and Breakdown Dimensions above are settled, the screen-design canvas should be created and linked from both this document and `12-ui-designs.md`.
