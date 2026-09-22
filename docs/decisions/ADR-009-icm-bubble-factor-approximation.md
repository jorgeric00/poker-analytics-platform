# ADR-009: Bubble Factor Uses an Approximated, Hero-Only ICM Model

## Status

Accepted

## Context

`11-development-tracking.md`'s Phase 5 note requires tournament analytics to cover ICM, Bubble Factor and ROI "from the start," and `13-statistics-module.md` includes Bubble Factor in the MVP stat set. Bubble Factor is computed from ICM (Independent Chip Model) equity, which classically requires two inputs:

1. Every remaining player's stack size, tournament-wide, at the moment of the decision.
2. The tournament's real payout structure — exactly how much every remaining finishing position pays.

Neither is reliably available from this project's data sources:

- Hand-history text (from any supported room) only shows the stacks at the table the hand is dealt at. In a large MTT, most of the field sits at other tables never represented in Hero's own hand histories — confirmed concretely by the "QUARK" fixture (`backend/src/main/resources/hand-history/winamax/mtt/`), a real 925-entrant tournament played start to finish.
- No parsed document states the full payout ladder. Winamax's `TournamentSummary` (the only tournament-result document this project parses today, per `decisions/ADR-008-tournament-summary-parser.md`) captures only Hero's own buy-in, finish place, cash winnings and bounty — never what any other finishing position paid.

Raised and resolved with the user while designing `07-analytics-engine.md` (Phase 5): asked directly whether the engine needs to know what every player was paid, or only the outcome for the user's own account. The user's answer: only the user's own result matters — the engine does not need to reconstruct other players' payouts.

## Decision

Bubble Factor (and any future ICM-based stat) is computed as a **Hero-centric approximation**, not a mathematically exact multi-way ICM:

- **Stacks**: use whatever stacks are actually visible in the hand-history text at the moment of the all-in decision. This is exact once play reaches a true final table (every remaining player is then at Hero's table) and is a rough stand-in for "the field" earlier in a large tournament.
- **Payout structure**: approximate with a standardized, generic payout-percentage curve (an industry-common percentage-of-prizepool-per-place shape, not this specific tournament's real one), scaled using the tournament's actual, room-stated prizepool and entrant count (`TournamentSummary.prizepool`, `registeredPlayers`). No attempt is made to model any other individual player's real payout.
- The resulting Bubble Factor is presented to the user as an approximation — most accurate near the bubble/final table, progressively less precise earlier in a tournament — and this should be reflected in the eventual Statistics screen's UI copy (Phase 3.2), not silently presented as an exact figure.

ROI% does not need this — it's a direct cash-flow computation (cash + bounty won − buy-in, both Hero-only and both room-stated) with no ICM dependency, and is unaffected by this decision.

## Consequences

- Bubble Factor can ship as part of the MVP tournament stat set without first solving an effectively unsolvable data-collection problem (obtaining every opponent's real payout across every supported room).
- The approximation is a real, accepted accuracy trade-off, not a hidden one — `07-analytics-engine.md` documents it, and the Statistics screen design must communicate it to the user rather than presenting the number as ground truth.
- If a future data source ever provides real, full tournament payout structures (e.g. a room adds it to an export, or a new document format is parsed), this ADR should be revisited — not silently overridden — since it would let Bubble Factor become exact rather than approximated.
- This does not change `decisions/ADR-008-tournament-summary-parser.md`'s scope (still Hero-only, still just buy-in/finish/winnings) — it only defines how the analytics engine works around that scope for ICM-based stats, rather than asking the import layer to capture more than it currently can.
