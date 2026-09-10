# ADR-007: Port Remaining Tournament/Room Formats Without Waiting for Reference Fixtures or Real Samples

## Status

Accepted

## Context

ADR-005 set the validation bar for the early hand-history-parser exception: port each room's *behavior* from `HHSmithy/PokerHandHistoryParser`, and validate it against that reference's own sample hand-history fixtures, ideally topped up with real hand histories from the user. That bar worked cleanly for every cash-game room ported so far (PokerStars, Winamax, PartyPoker, 888) and for Winamax's tournament/Expresso format, because the reference ships sample fixtures for all of those, and the user was able to supply real Winamax tournament/Expresso hands directly.

It does not work for the tournament/Spin&Go formats still open in `11-development-tracking.md`: the reference library ships **no tournament sample fixtures at all, for any room** (confirmed by inspecting `HandHistories.Parser.UnitTests/SampleHandHistories/*/` — every room folder has a `CashGame/` subdirectory; none has a `Tournament/` one). For PartyPoker and 888/Pacific specifically, the reference doesn't even have tournament-*parsing* code — `ParsePokerFormat` is hardcoded to always return `CashGame`, and `ParseTournamentId` throws `NotImplementedException`. For PokerStars, iPoker and Winning Poker Network, the reference does have real tournament-parsing logic, but nothing to run it against, and no real user-supplied hand histories exist for these either.

The user decided to stop gating this work on real hand histories or reference fixtures, and instead: port whatever the reference actually implements, and where the reference itself has nothing, use the best independent grounding otherwise available before writing code.

For PokerStars specifically, that grounding turned out to be available: real, complete, verbatim PokerStars tournament hand histories are published publicly (Wikipedia's "Hand history" article quotes two full hands — a knockout hand with a bounty/finish-place line, and an older multi-way all-in hand with numbered side pots — and a further real ante-posting example is quoted in arXiv:2312.11753). These were used as the fixture data for `PokerStarsParser`'s new tournament-header support, in place of reference-library fixtures. This is corroborating, independently-sourced real text, not hand-authored/invented sample data — treated as materially better than no validation, though still weaker than the reference library's own curated edge-case fixtures (a handful of hands vs. dozens covering deliberately chosen edge cases).

## Decision

Continue porting every room/format still in ADR-005's scope (PokerStars MTT/Spin&Go, PartyPoker MTT, 888 MTT, iPoker cash + MTT, Winning Poker Network cash + MTT), but relax the validation requirement per format based on what's actually available:

- **Reference fixtures exist** (iPoker cash, Winning Poker Network cash): no change from ADR-005 — port and validate against them exactly as done for every cash room so far.
- **No reference fixtures, but real public sample text exists** (PokerStars tournaments, as above): port against that public text, and say so explicitly in both the code (class-level doc) and `11-development-tracking.md` — this is real data, but from a different, weaker provenance than the reference library's own fixtures, and the gap is called out rather than silently presented as equivalent.
- **No reference fixtures and no code either** (PartyPoker MTT, 888 MTT): there is nothing to port. Recorded as a hard "not supported by the reference, needs a different source" item, not attempted.
- **No reference fixtures, code exists, no public sample text found** (iPoker MTT, Winning Poker Network MTT, if this remains true when reached): port the reference's C# logic as faithfully as reasonably possible into the established regex/state-machine style, but ship with **no automated test** for it, and flag this loudly in both the class doc and the tracking doc as unverified — a real risk of silent bugs until a real hand history turns up, explicitly accepted by the user rather than blocking on it.

A format ported this way (weaker-than-fixture validation, or no validation at all) is documented as such wherever it appears in `11-development-tracking.md`'s Phase 9 checklist and Follow-ups, so this doesn't quietly read as being on the same footing as the fixture-validated rooms above it.

Known, accepted consequence for PokerStars specifically: neither the reference library nor the two real sample hands available distinguish a regular scheduled tournament from a 3-max Spin&Go in the hand-history text itself, so `PokerStarsParser` classifies every non-cash PokerStars hand as `HandFormat.TOURNAMENT`. This mirrors a real limitation in the reference library itself (its own format-detection always returns `SitAndGo`, with the `MultiTableTournament` branch commented out in its source) — it isn't a corner this port cut on its own.

## Consequences

- The rooms/formats ported under this ADR carry a real, higher-than-usual risk of parsing bugs going undetected until a real hand history is available, by design — this is a deliberate, user-directed trade of speed for confidence on the lower-value/lower-confidence remainder of ADR-005's room list, not a change to how the higher-confidence rooms already shipped were built.
- `docs/11-development-tracking.md` must keep naming, per format, which validation tier it actually got (reference fixtures / public real sample / no test at all) — collapsing that distinction would misrepresent how trustworthy each parser is.
- If a room's port under this ADR turns out wrong once real data arrives, fix it in place (it's still provisional, non-frozen import-layer code per ADR-005) rather than treating the original port as binding.
- This does not reopen or change ADR-005's own room scope (still PokerStars, Winamax, PartyPoker, 888, iPoker, Winning Poker Network) or its "parsing only, no REST/storage/analytics" boundary — it only relaxes how each remaining format within that scope gets validated.
