# ADR-005: Build the Hand-History Parsing Layer Early, Ahead of Phases 4-8

## Status

Accepted

## Context

`CLAUDE.md` and `11-development-tracking.md` both establish that `backend/` is scaffolding only, not to be extended with real domain logic until Phases 4-8 (Query Catalog, Analytics Engine, Storage Strategy, Data Model, API Design) are documented — the roadmap is meant to be followed top-down. The one sanctioned exception so far was a small hand-history *parser feasibility spike* (`com.pokeranalytics.spike.handhistory`), scoped narrowly to proving the PokerStars Zoom cash format is parseable against real data.

That spike led to finding `HHSmithy/PokerHandHistoryParser` on GitHub — a mature, MIT-licensed C#/.NET library already covering multiple poker rooms. A source-code review (no compilation) confirmed it correctly handles every real-world edge case our spike discovered (disconnect/timeout notices, "mucks hand") plus several we hadn't hit yet (side pots, antes, "posts small & big blinds", run-it-twice, reconnect notices, multiple currencies). It ships its own sample-hand-history test fixtures per room, which gives a validation baseline independent of the user's ability to personally source real hand histories from every room.

Given this, the decision is to **port that library's parsing behavior into our own Java code now**, covering every room it supports that is still operating today, rather than waiting for Phases 4-8 to be written first. Building the actual full import/analytics/storage/API stack now would clearly be jumping the roadmap's order — but the parsing step (raw hand-history text → an in-memory parsed representation) is largely independent of those later architectural decisions, and doing it now removes risk from the highest-uncertainty part of the system while a validated, MIT-licensed reference is fresh in hand.

## Decision

Build `com.pokeranalytics.importing.model` and `com.pokeranalytics.importing.parser` (and its per-room subpackages) now, as real, kept code — **scoped strictly to parsing**. No REST API, no DuckDB/SQLite wiring, no analytics computation is built as part of this exception; parsed hands stay in-memory Java objects until Phases 4-8 define how they're stored and queried.

Rooms in scope, based on which of the reference library's ~13 supported rooms are still operating: **PokerStars, Winamax, PartyPoker, 888 (covers the historical "Pacific" brand), iPoker Network, Winning Poker Network**. Out of scope as defunct/inactive: Full Tilt Poker (merged into PokerStars, brand retired 2016), Entraction (closed ~2012), Boss Media (closed ~2012), OnGame (closed 2015), Merge Gaming Network (closed ~2016-17), Microgaming Poker Network/MPN (closed 2020).

This expands the product's MVP room list beyond the two rooms in `03-product-vision.md`'s original MVP (PokerStars, Winamax) — updated there as part of this same change, since it's a real product-scope decision, not just a technical one.

We port the reference library's *behavior and edge-case coverage*, not its literal C# implementation style: its low-level char-at-index dispatch was hand-optimized for throughput in a way that trades away readability, which isn't a priority yet for a codebase with no working import engine to optimize. Our Java parsers follow the regex/state-machine style already validated in the spike.

Per the reference library's MIT license, `THIRD-PARTY-NOTICES.md` (repo root) credits `HHSmithy/PokerHandHistoryParser` for the ported sample-hand-history fixtures and the parsing behavior this module is based on.

Rooms are ported one at a time (PokerStars first, since it already has real 2026 validation data and a deeply-reviewed reference implementation); the rest are tracked as explicit follow-ups in `11-development-tracking.md`.

## Consequences

- `com.pokeranalytics.importing.parser` and `.model` are the one other exception (alongside the backend/frontend scaffolds and the original parser spike) to "don't extend `backend/` with real domain logic before Phases 4-8" — `CLAUDE.md` is updated to say so explicitly, so this isn't a silent divergence from the documented rule.
- The rest of `backend/` (REST layer, persistence, analytics) stays frozen scaffolding until Phases 4-8 are actually written — this ADR does not pull those forward.
- `com.pokeranalytics.spike.handhistory` is retired once `com.pokeranalytics.importing.parser.pokerstars` reaches parity with it (same real-data tests passing) — the spike was a stepping stone, not something to maintain in parallel.
- The parsed-hand model (`com.pokeranalytics.importing.model`) is provisional: it exists to hold what a hand-history parser can extract, not the frozen domain model. `09-data-model.md` (Phase 7) may still reshape how this data is ultimately persisted and queried; this ADR does not pre-decide that.
- Tournament and Spin&Go/Expresso formats remain higher-risk: the reference library has real tournament-parsing code but no sample-based tests for it in its own suite, and no Spin&Go/Expresso-specific handling was found at all. Those still need real sample hand histories from the user to validate properly, independent of this port.
