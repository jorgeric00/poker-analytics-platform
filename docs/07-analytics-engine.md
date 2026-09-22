# Analytics Engine

## Overview

This document defines **how poker statistics are calculated** — Phase 5 of `00-development-roadmap.md`.

Question answered:

> How does the analytics engine work?

**Scope boundary:**

- `06-query-catalog.md` already defined the *shape* of the data-access operations (Aggregate Stat Query, etc.) and their parameters (`scope`, `statSet`, `breakdown`). This document defines what actually happens **inside** that computation — the logical model that turns raw hand data into a stat value.
- `13-statistics-module.md` owns **which** stats and breakdown dimensions exist, from the player's point of view. This document owns **how** each of those stats is derived — the formula, and the facts it's built from.
- Per the roadmap, this is designed **independently of the database** — no DuckDB schema, no tables, no indexes. That's `08-storage-strategy.md` (Phase 6) and `09-data-model.md` (Phase 7). This document only requires that *some* persistence layer can answer "give me every fact X for hands matching filter Y" — not how.
- This document is also independent of today's provisional `importing.model` classes (`ParsedHand`, `HandAction`, etc. — explicitly marked "provisional... not the frozen domain model" per `decisions/ADR-005-early-import-parser.md`). It specifies the **normalized facts** the engine needs (e.g. "who took this action, on which street, for how much"); turning raw parser output (today's free-text `rawLine`s) into those normalized facts is Phase 7 (Data Model) / Phase 9 (Import Engine) work. Where today's parser model is missing a field this engine needs (e.g. per-action player attribution and amount are currently only inside `rawLine` text, not structured fields), it's called out below as a requirement on that future work, not solved here.

---

## The Core Model: Facts → Opportunities → Events → Metrics → Aggregations

Every statistic in this application must be **reproducible from raw hand histories** (`CLAUDE.md`, Architecture Principles) and **recalculable, not just stored**. The engine is built as a pipeline of five layers, each one a small, well-defined transformation of the layer before it — so a "why is this number what it is" question can always be answered by walking back down the pipeline to the original hand text.

```
Raw Hand History (text)
    ↓
Facts            — atomic, traceable pieces of truth extracted from a hand
    ↓
Opportunities    — situations where a stat's precondition holds for a player
    ↓
Events           — whether the qualifying action actually happened
    ↓
Metrics          — a stat's value for one player, one scope (events ÷ opportunities, or a direct aggregation)
    ↓
Aggregations     — a metric computed over a filtered/grouped population (per Query Catalog's scope/breakdown)
```

### 1. Facts

A **Fact** is a single, atomic piece of information extracted from one hand, with no interpretation applied — the raw material every stat is built from. Facts are what "reproducible from raw hand histories" actually means in practice: every fact must be traceable to a specific span of the original hand-history text.

| Fact group | Examples | Traces back to |
|---|---|---|
| Hand context | Room, format (cash/MTT/Expresso), game (Hold'em/Omaha/Omaha Hi-Lo), limit type, stakes/blinds, ante, currency, date/time, table size, tournament id | Hand header |
| Seat context | Seat number, player name, starting stack, button-relative position | Seat lines |
| Action | Player, street, action type (post/fold/check/call/bet/raise/all-in), amount, resulting pot | Action lines *(today's parser keeps this as free text in `HandAction.rawLine` — player attribution and amount must become structured fields, a Phase 7/9 requirement)* |
| Board | Community cards per street, per run (run-it-twice support already exists in `ParsedHand.boards`) | Street marker lines |
| Result | Showdown Y/N per player, cards shown/mucked, amount won per player, total pot, rake | Showdown/summary lines |
| Tournament result | Buy-in total, finish place, prizepool, cash winnings, bounty winnings — **Hero only** | `TournamentSummary` (Winamax; per `decisions/ADR-008-tournament-summary-parser.md`) |

Facts are never mutated after import — they're the immutable single source of truth (`CLAUDE.md`, "Raw hand histories are the single source of truth"; Hands module, "Editing imported hands" is explicitly out of scope). Everything below this layer is *derived*, and can always be thrown away and recomputed from Facts alone.

### 2. Opportunities

An **Opportunity** is a situation where a player *could* take the action a stat measures — the precondition that makes the stat's denominator meaningful. Without opportunities, a raw event count is meaningless (e.g. "folded to a 3-bet 4 times" means nothing without knowing how many times a 3-bet was even faced).

An opportunity is defined per player, per hand (or per relevant street), as a predicate over Facts. See the Stat Definitions table below for the concrete predicate behind each MVP stat.

### 3. Events

An **Event** is whether the qualifying action actually happened, evaluated only where an Opportunity already exists. An event without a matching opportunity is a modeling bug, not a valid data point (e.g. a "3-bet event" can't exist for a player who never faced the preflop opportunity to make one).

### 4. Metrics

A **Metric** is a stat's value for one player, over one scope (`Hero | Opponent(playerId) | CurrentSession | AllOpponents`, per `06-query-catalog.md`'s QF-1). Two shapes exist:

- **Rate metrics** — `events / opportunities`, expressed as a percentage (VPIP, PFR, 3-Bet%, C-Bet%, Fold vs C-Bet%, ...).
- **Direct metrics** — a magnitude computed straight from Facts, with no opportunity/event step (Hands played, Stack Size in BBs, BB/100, ROI%, Total Rake Paid, Hours/Sessions Played, ...).

Both shapes are metrics; the distinction only matters for how they're computed, not for how they're used downstream.

### 5. Aggregations

An **Aggregation** takes a metric and computes it over a *population* of hands defined by `06-query-catalog.md`'s QF-1 parameters: the Global Filter Parameters, `scope`, and zero or more `breakdown` dimensions (from `13-statistics-module.md`'s MVP Breakdown Dimensions: Game Details, Hand Details). A rate metric's aggregation is `Σevents / Σopportunities` **within each breakdown group** — not an average of per-hand percentages, which would weight small samples incorrectly. Minimum sample size (`HAVING count(opportunities) >= N`) is applied at this layer, consistent with `06-query-catalog.md`'s resolved decision to exclude rather than flag low-sample rows.

---

## Stat Definitions (MVP)

The MVP set is exactly the 10 stats confirmed in `13-statistics-module.md` (its groups 1 and 2). Group 3 ("standard convention, not yet confirmed") is deliberately left out of this table — it isn't part of MVP yet — but the Facts → Opportunities → Events model below is generic enough to extend to it once verified; see Extending Beyond MVP.

| Stat | Game Type | Opportunity | Event | Formula |
|---|---|---|---|---|
| VPIP | Cash & Tournament | Player is dealt a hand and reaches their first preflop decision | Player voluntarily puts chips in preflop (call/bet/raise) — posting a blind or straddle alone does not count | events ÷ opportunities |
| PFR | Cash & Tournament | Same as VPIP | Player raises at least once preflop | events ÷ opportunities |
| 3-Bet% | Cash & Tournament | Player faces exactly one prior preflop raise and has a live decision | Player raises (the 3rd bet in the sequence, after the blind "1st bet" and the initial raise "2nd bet") | events ÷ opportunities |
| Flop C-Bet% | Cash & Tournament | Player was the last preflop aggressor and the hand reaches the flop | That player is the first to bet on the flop | events ÷ opportunities |
| Fold vs Flop C-Bet% | Cash & Tournament | Player faces a bet on the flop from the preflop aggressor | Player folds | events ÷ opportunities |
| Hands (sample size) | Cash & Tournament | — (direct metric) | — | count of hands in scope |
| Stack Size (BBs) | Cash & Tournament | — (direct metric) | — | avg(starting stack ÷ big blind) over hands in scope |
| BB/100 | Cash only | — (direct metric) | — | (Σ Hero net result, in big blinds) ÷ (hands in scope ÷ 100) |
| ROI% | Tournament only | — (direct metric) | — | (Σ cash + bounty won − Σ buy-in total) ÷ Σ buy-in total, over tournaments in scope — from `TournamentSummary` facts only, **no ICM needed** |
| Bubble Factor | Tournament only | Player faces an all-in decision late in a tournament | — (not a rate stat; see ICM & Bubble Factor below) | ICM-based, approximated — see below |

**Money-valued metrics and currency.** Facts keep money in the hand's original currency (never mutated). BB/100 sidesteps the issue entirely (its unit is the big blind, not a currency). Money-valued aggregations that *do* need a common unit (P&L, Total Rake Paid, ROI% across tournaments in different currencies) convert to the user's selected display currency at aggregation time, never at the fact layer — the exchange-rate source and whether historical or current rates are used is still open, see Open Questions.

**Total Rake Paid.** No room's hand-history or summary text states a per-player rake share directly (rake is reported as one total per hand). This is computed with the standard tracker convention: Hero's rake share ≈ hand's total rake × (Hero's pot contribution ÷ total pot) — a proportional-contribution estimate, not a room-stated value. Flagged here as an approximation, the same way `13-statistics-module.md` and various parser class docs already flag their own known approximations/gaps.

**All-in Adjusted P&L (EV).** Listed in `05-product-map.md` for Overview ("Expected Value (EV)") and Live Session ("All-in Adjusted P&L (EV)"). This is **chip-equity EV**, not the ICM-based "Net Adjusted Winnings" that `13-statistics-module.md` explicitly defers to Future Expansion — the two are easy to conflate and are kept distinct here:

- *Opportunity*: a hand reaches an all-in with at least one card still to come, and every player still contesting the pot has known hole cards (shown at showdown, or otherwise revealed in the hand text). A hand where a player folds before showdown and never shows never qualifies — there's nothing to compute equity against.
- *Event/Metric*: run the involved hands' equity (a standard hand-equity calculator, not ICM) against the actual board(s) dealt — `ParsedHand.runItTwice`/`boards` already models multiple runs — and compare each player's equity share of the pot at the moment of the all-in to what they actually won. The delta, summed over hands in scope, is the EV adjustment added to raw P&L.
- This needs a hand-equity engine (enumerate/evaluate outcomes for Hold'em, Omaha, Omaha Hi-Lo) as a new engine component — not yet designed in detail here, since `05-product-map.md` only requires the metric to exist, not its exact implementation; revisit before Phase 9 implements it.

### Sessions (for Hours Played / Sessions Played, and Reports → Session History)

A **Session** is a Fact-derived grouping, not a stat in its own right, but it underlies several metrics (Hours Played, Sessions Played) and two query families (`06-query-catalog.md`'s QF-6/QF-7). Defined as: a contiguous block of Hero's hands, possibly across multiple tables played simultaneously, that starts at the first hand's timestamp and ends at the last hand's timestamp before a gap of more than a configurable **idle threshold** (default: 30 minutes, matching common tracker convention) with no new hand recorded — or ends immediately if the user manually ends the Live Session. This default is a starting point, not user-validated; revisit if session boundaries look wrong once real multi-tabled data is imported.

---

## ICM & Bubble Factor — Scoped Simplification

Real Bubble Factor requires ICM (Independent Chip Model) equity, which in turn requires two things this project cannot reliably obtain:

1. **Every remaining player's stack size, tournament-wide** — hand-history text only shows the stacks at Hero's own table at that moment. In a large MTT (e.g. the 925-entrant "QUARK" tournament already in `backend/src/main/resources/hand-history/winamax/mtt/`), the vast majority of the field is at other tables never visible in Hero's hand histories.
2. **The tournament's real payout structure** — how much every remaining finishing position actually pays. No available source states this: hand-history text never includes it, and Winamax's `TournamentSummary` (the only tournament-result document this project parses, per `decisions/ADR-008-tournament-summary-parser.md`) captures only **Hero's own** buy-in, finish place, cash winnings and bounty — never the full payout ladder for other places.

**Decision (resolved with the user, 2026-09-22 — see `decisions/ADR-009-icm-bubble-factor-approximation.md`):** the engine does not attempt to reconstruct the real payout structure or track other players' results. Bubble Factor is computed as a **Hero-centric approximation**:

- **Stacks**: use whatever stacks are actually visible in the hand-history text at the moment of the all-in decision (Hero's own table). This is exact at a true final table (every remaining player is at Hero's table, so nothing is missing) and degrades to a rough approximation earlier in a large field — an accepted, explicit trade-off, not a hidden one.
- **Payout structure**: approximate with a standardized, generic payout-percentage curve (a common percentage-of-field-paid / percentage-of-prizepool-per-place shape used industry-wide for SNG/MTT ICM tools), scaled to *that* tournament's actual prizepool and entrant count (`TournamentSummary.prizepool`, `registeredPlayers` — both real, room-stated values). This models "what a min-cash / average remaining finish is roughly worth" well enough for a relative risk-premium calculation like Bubble Factor, without claiming to know any specific opponent's real payout.
- Bubble Factor is therefore reported to the user as an **approximation**, most accurate near the bubble/final table and progressively less precise earlier in a tournament — this should be reflected in the eventual UI copy (Phase 3.2/Statistics screen design), not silently presented as exact.

This keeps the MVP promise from `11-development-tracking.md` ("tournament stats must cover ICM, Bubble Factor and ROI from the start") satisfiable with the data actually available, instead of blocking the whole stat on data this project has no way to collect.

---

## Extending Beyond MVP

The Facts → Opportunities → Events model is designed to extend to `13-statistics-module.md`'s group-3 candidate stats (Fold to 3-Bet%, WTSD%, W$SD%, Aggression Factor, Steal%/Fold to Steal%, ITM%, Average Finish) once verified against real PT4 reference material — each is expressible the same way (e.g. WTSD%: opportunity = hand reaches the flop for this player, event = hand reaches showdown for this player). None of group 3 is defined formally here, consistent with `13-statistics-module.md` explicitly not adopting them into MVP yet; this section only confirms the model doesn't need to change shape to accommodate them later.

`05-product-map.md`'s Players Profile section lists "Fold to 3-Bet" and "WTSD" as *illustrative* example stats, immediately followed by "the exact stat set will be refined later" — that's consistent with them being group-3 candidates, not a conflicting MVP commitment.

Also explicitly out of scope for this phase, per `13-statistics-module.md`'s Future Expansion: the custom stat formula builder, the Equity Calculator, ICM-based Net Adjusted Winnings graph, and LeakTracker-style deviation analysis. Each would still fit this same five-layer model when eventually designed.

---

## Recalculability

Per `04-architecture.md`'s "Statistics must be recalculable, not just stored": nothing in this model depends on external or mutable state. Every Metric is a pure function of Facts (plus, for Aggregations, the query's filter/scope/breakdown parameters and — for money — a display-currency conversion applied only at that layer). Deleting every derived value and recomputing from the raw hand-history text (the Facts layer's source) must always produce the same result. This is also why Facts are never mutated: any correction to a stat's definition or formula only requires replaying the Facts, never re-importing or re-parsing hand histories.

---

## Open Questions

- **Exchange rate source for cross-currency money aggregations** (P&L, Total Rake Paid, ROI% when a scope spans hands in more than one currency): historical rate at the hand's date, or current rate? Which data source, given the local-first/offline requirement (`04-architecture.md`)? Not needed for single-currency scopes (the common case), so not blocking, but needs an answer before Phase 8 (API Design) exposes a display-currency parameter.
- **Session idle threshold** (default 30 minutes, see Sessions above) — a reasonable starting default, not validated against how the user's own play sessions actually look; revisit once real multi-session data is imported.
- **All-in Adjusted P&L (EV)'s hand-equity engine** — this document defines the metric's opportunity/event/formula shape, but the actual equity-enumeration engine (Hold'em/Omaha/Omaha Hi-Lo) is a nontrivial component not designed in detail here; needs its own design pass before Phase 9 implementation.
- **Per-action structured facts** — today's `HandAction` record has no player-attribution or amount field (only `street`, `type`, `rawLine`); this document assumes those become structured facts by the time Phase 7/9 need them. Tracked here so it isn't lost before `09-data-model.md` is written.
