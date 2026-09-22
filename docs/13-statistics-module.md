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

**In progress.** Read directly (via WebFetch, from `docs.pokertracker.com`) as the primary source for everything below:

- PokerTracker 4 Quick Start Guide
- Creating My Reports
- Custom Statistics Guide
- Global Filters aka "More Filters"
- Equity Calculator
- Net Adjusted Winnings Graphs (Tournaments)
- BB/100 versus bb/100
- LeakTracker

**Known gap:** the exact enumerated values inside several filter categories (e.g. the full Position list, the full Made Hand Type list, the complete per-street Actions and Opportunities list) live in PT4's UI as screenshots that plain page-text fetching can't OCR — the docs name the categories and give a few examples each, not exhaustive lists. Not blocking for the MVP scope below (our MVP categories use straightforward, already-known enums); flagged as an open question for when the Future Expansion filter categories get built.

---

## Stat Catalog

To keep this document honest about where each fact comes from, stats are grouped by provenance before proposing an MVP set.

### 1. Confirmed against PT4 documentation

| Stat | PT4 source | Game Type |
|---|---|---|
| VPIP | Basic HUD Guide | Cash & Tournament |
| PFR | Basic HUD Guide | Cash & Tournament |
| 3-Bet | Basic HUD Guide; also PT4's own custom-stat example ("3Bet Preflop") | Cash & Tournament |
| Flop C-Bet (Continuation Bet) | Basic HUD Guide | Cash & Tournament |
| Fold vs Flop C-Bet | Basic HUD Guide | Cash & Tournament |
| Hands (sample size) | Basic HUD Guide | Cash & Tournament |
| Stack Size (BBs) | Basic HUD Guide | Cash & Tournament |
| BB/100 | BB/100 versus bb/100 | Cash only |

**BB/100 note:** PT4 distinguishes "BB" (Big Blind — No-Limit/Pot-Limit games) from "bb" (big bet — Fixed Limit games only, where the later-street bet is 2× the small blind). The project's MVP formats (`03-product-vision.md`: PokerStars + Winamax, Cash/MTT/Spin&Go) don't include fixed-limit games, so BB/100 stays simple — the unit is always the big blind, no "big bet" ambiguity to design around.

### 2. Required by this project's own scope (not sourced from PT4)

Per `03-product-vision.md` and `11-development-tracking.md` (Phase 5 note), tournament analytics must cover **ICM, Bubble Factor and ROI from the start** — this is mandated by the project's own vision, independent of anything PT4 does.

| Stat | Game Type |
|---|---|
| ROI% | Tournament only |
| Bubble Factor | Tournament only |

ICM itself is a computation, not a stat displayed on its own. It feeds Bubble Factor (and potentially future stats — see Future Expansion) — ROI% turned out **not** to need it: it's a direct cash-flow figure (cash + bounty won − buy-in), all Hero-only and room-stated. See `07-analytics-engine.md` for the formal definitions, including a resolved, documented approximation for Bubble Factor's ICM input (`decisions/ADR-009-icm-bubble-factor-approximation.md`) — no available source states other players' stacks or the tournament's real payout structure.

### 3. Standard poker-tracking convention — not yet confirmed against a PT4 page

These are stats a poker tracker would typically be expected to offer, but none of the 8 pages read above named them explicitly — **verify before adopting**, e.g. against PT4's Advanced HUD Guide or an actual screenshot:

- Fold to 3-Bet%
- WTSD% (Went To Showdown)
- W$SD% (Won $ at Showdown)
- Aggression Factor
- Steal% / Fold to Steal%
- ITM% (In The Money) — Tournament
- Average Finish — Tournament

### 4. Proposed MVP stat set

Combines groups 1 and 2 only — group 3 stays a candidate list, not part of MVP, until verified.

| Category | Stat | Game Type |
|---|---|---|
| Preflop | VPIP | Cash & Tournament |
| Preflop | PFR | Cash & Tournament |
| Preflop | 3-Bet% | Cash & Tournament |
| Postflop | Flop C-Bet% | Cash & Tournament |
| Postflop | Fold vs Flop C-Bet% | Cash & Tournament |
| Sample | Hands | Cash & Tournament |
| Sample | Stack Size (BBs) | Cash & Tournament |
| Result | BB/100 | Cash only |
| Result | ROI% | Tournament only |
| Result | Bubble Factor | Tournament only |

---

## Breakdown Dimensions

PT4 groups its filters into 6 categories under "Global Filters" (UI-labeled "More Filters"), applied identically across Cash and Tournament contexts. Adopting the same framework:

### MVP (v1)

- **Game Details** — Stakes, Poker Room (Site), Game Type / Tournament Type, Date Range.
- **Hand Details** — Position, Win/Loss, Showdown (Y/N).

### Future / Advanced (not in MVP)

- **Session Details** (cash only) — Amount Won/Lost per session, Table Session Duration.
- **Hand Values** — Draws, Specific Hole Cards, Made Hand Type.
- **Board Texture** — Board Connectedness, Board Pairs, Board Suits.
- **Actions and Opportunities** — per-street action/opportunity filters (e.g. Preflop Raises, Flop Calls, River Continuation Bets — PT4's docs imply more per-street entries than these three examples).

The exact enumerated values within each Future category (see Research Status gap above) still need sourcing before those categories can be built.

---

## Saved Views

PT4's equivalent is "My Reports" — a considerably richer concept than what v1 needs: a Report Wizard with **3 report types** (Player Report — per player, All Players Report — one row per DB player, Hand Report — raw hands matching filters), **two-tier filters** (Simple Filters + advanced Expression Filters built from stats/columns/operators), multi-key sorting, conditional row-coloring, an optional summary row, and import/export (`.pt4rpt`)/convert-between-Cash-and-Tournament/duplicate management.

For v1, **Saved Views** stays intentionally simpler, matching what's already scoped in `05-product-map.md`: a single view type — a named, reloadable combination of stat set + breakdown + filters. PT4's report-type distinction and report-management features move to Future Expansion.

---

## Future Expansion (Not in MVP)

Explicitly deferred, informed by this research — revisit only after the MVP above ships:

- **Custom stat formula builder** — PT4's model (Columns = raw DB fields as SQL expressions, Variables = reusable derived calculations, combined into a Value Expression). PT4's own docs flag this as an advanced feature.
- **Additional filter categories** — Session Details, Hand Values, Board Texture, Actions and Opportunities (see Breakdown Dimensions above).
- **Multiple report/view types** — Player / All Players / Hand-level views (v1 has a single view type).
- **Report management features** — import/export file format, convert Cash↔Tournament, duplicate, conditional row-coloring, summary row.
- **Equity Calculator** — range-vs-range tool (Hold'em, Omaha Hi, Omaha Hi-Lo; up to 10 players; supports exact hands, range notation, and dead cards). Likely belongs under Hands (hand analysis) rather than Statistics if/when built.
- **Net Adjusted Winnings (ICM-adjusted graph)** — isolates luck at all-in decisions at the final table (expectation of ICM equity at the all-in moment vs. realized result, graphed over time). The underlying ICM math is already MVP-required for ROI/Bubble Factor, but this specific derived graph is a nice-to-have visualization, not required for v1.
- **LeakTracker-style deviation analysis** — benchmarks the user's stats against a fixed sample of winning players (segmentable by game type/stakes/table size/bet type), shown as a 3-tier color severity indicator (green/light gray/dark gray) per stat. Conceptually closer to this project's future "AI-assisted insights" phase (`03-product-vision.md`) than to Statistics — revisit there.

---

## Open Questions

- Verify the group-3 "standard convention, not yet confirmed" stats (Fold to 3-Bet%, WTSD%, W$SD%, Aggression Factor, Steal%/Fold to Steal%, ITM%, Average Finish) against a PT4 screenshot or an additional doc page before adding any of them to the MVP set.
- Is an ICM-based "Net Adjusted Winnings"-style graph worth building even post-MVP, or too advanced for the target audience?
- Should LeakTracker-style deviation analysis eventually live inside Statistics, or exclusively under the future AI-assisted insights phase?
- How do we source the exact enumerated filter values (positions, made-hand types, per-street actions, etc.) once we get to the Future Expansion filter categories — screenshots, the live PT4 app, or an approximate list we define ourselves?

---

## Screen Design

No canvas exists yet for Statistics — see `12-ui-designs.md`. Once the MVP Stat Catalog and Breakdown Dimensions above are confirmed (Open Questions resolved), the screen-design canvas should be created and linked from both this document and `12-ui-designs.md`.
