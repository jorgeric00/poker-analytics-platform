# ADR-008: Extend the Early Import-Parser Exception to Winamax's Tournament Summary Document

## Status

Accepted

## Context

`ADR-005` scoped the early, ahead-of-roadmap import exception strictly to *hand-history parsing*: raw poker-room text describing individual hands, turned into `ParsedHand` objects. Winamax also exports a second, structurally unrelated document per tournament entered — its "Tournament summary" (e.g. `20260519_QUARK(1098224169)_real_holdem_no-limit_summary.txt`): buy-in breakdown, registered players, prizepool, start time, time played, finishing place, and (when the player cashed) money and bounty won. `WinamaxTournamentParser`'s class-level doc and `11-development-tracking.md` both already flagged this file as "a different, non-hand-history document... out of scope for this parser," with a follow-up item noting it "needs its own design... if this data is worth capturing."

The user has now confirmed it's worth capturing: this is exactly the money-tracking data (buy-in, winnings, finishing position) that the future Statistics/Reports screens need for tournament players, and it exists nowhere in the hand-history text itself — for example, the hand-history header's own `buyIn` field only ever shows two components ("1.80€ + 0.20€"), while a knockout tournament's real buy-in has three (prizepool contribution, bounty pool, rake — the QUARK summary shows "0.80€ + 1€ + 0.20€" for the same tournament). Finishing place and amount won aren't present in the hand-history text at all.

Real sample data is already available and was inspected before writing this parser: three matched hand-history/summary pairs (TRIDENT SPACE KO, ROTATION, QUARK — the last a complete 1st-place win, contributed by a friend of the user, danirey300) plus one orphan Expresso summary with no matching hand-history file. Together they exercise a cashed 1st place with a bounty payout, several non-cashing finishes (no "You won" line at all), and both tournament (`tt`/`knockout`) and Sit&Go (`sng`/`sitngo`) modes.

## Decision

Extend the `ADR-005` exception: `backend/src/main/java/com/pokeranalytics/importing/model/TournamentSummary.java` and `backend/src/main/java/com/pokeranalytics/importing/parser/winamax/WinamaxTournamentSummaryParser.java` are real, kept domain code too, on the same terms as the rest of `importing` — scoped strictly to parsing this one document into an in-memory representation, no REST/persistence/analytics wiring. This isn't ported from `HHSmithy/PokerHandHistoryParser` (that reference has no equivalent document for any room); it's an independent format, reverse-engineered from the real sample files above the same way `ADR-007`-scoped formats were.

A single summary file can contain multiple concatenated summary blocks (one per time the player entered that recurring tournament name), mirroring how a hand-history file holds multiple hands — `WinamaxTournamentSummaryParser` therefore returns a `List<TournamentSummary>` per file, split on the repeating `"Winamax Poker - Tournament summary : "` header line, the same block-splitting technique `WinamaxTournamentParser` already uses for hand headers. Buy-in is kept both as the room's own raw text (for fidelity, same as `ParsedHand.buyIn`) and as a summed total `BigDecimal` (computed straight from the same text, not invented — the same traceable-total technique already used for `PartyPokerParser`/`WinningPokerParser`'s `totalPot`), since the individual components (prizepool share vs. bounty pool vs. rake) aren't labeled in the text and guessing which is which isn't worth the risk. Every recognized line's absence from a per-record `unparsedLines` list is asserted in tests, mirroring `ParsedHand`'s existing discipline.

This is scoped to Winamax only, matching the only room this document has actually been seen for; if another room turns out to have an equivalent export, it gets its own follow-up rather than being assumed to share this grammar.

## Consequences

- `CLAUDE.md`'s "Repository Status" exception paragraph is updated in the same change to list this new file pair alongside the existing `importing/model` and `importing/parser` exception.
- `TournamentSummary` records are not yet linked to `ParsedHand` records (e.g. by tournament id) — that join, and how both feed the future Statistics/Reports modules, is Phase 5-8 work (`07-analytics-engine.md`, `09-data-model.md`), not decided here.
- If a future real sample shows this document's grammar varies in a way the current fixtures didn't exercise (e.g. a knockout buy-in with a different component count, or a summary with no "Tournament started" line), fix the parser in place — this is still provisional, non-frozen import-layer code per `ADR-005`, not a frozen contract.
