# Storage Strategy

## Overview

This document defines **how information is stored** — Phase 6 of `00-development-roadmap.md`.

Questions answered:

> What is stored? What is calculated? What is cached? What is duplicated? What is immutable?

**Scope boundary:**

- `07-analytics-engine.md` already defined the *logical* model (Facts → Opportunities → Events → Metrics → Aggregations), independent of any database. This document decides, for each layer of that pipeline, whether it becomes a physically stored table, a query-time computation, or neither.
- `09-data-model.md` (Phase 7) turns the decisions here into an actual schema — table names, columns, types, keys. This document intentionally stays at the level of "what kind of thing gets persisted and why," not DDL.
- `decisions/ADR-003-local-analytics-storage.md` already decided the engine (DuckDB) and the two-database split (DuckDB for analytics, SQLite for app data). This document doesn't revisit that choice — it builds the strategy on top of it.

---

## Guiding Constraints

Everything below follows from decisions already made elsewhere, restated here because they directly shape storage choices:

- **Single source of truth, immutable Facts** (`04-architecture.md`, `07-analytics-engine.md`) — raw hand-history Facts are never edited once imported. Storage must make this cheap to honor, not just policy.
- **Recalculable, not just stored** (`04-architecture.md`) — every Metric/Aggregation must be reproducible from Facts. This pushes toward *not* persisting derived values whose formula could drift out of sync with a second, stored copy.
- **Avoid premature optimization** (`00-development-roadmap.md`'s Guiding Principles) — target latency tiers were deliberately left qualitative in `06-query-catalog.md`, pending real benchmarks once real data volumes exist. This document defaults to the simplest storage shape that can plausibly hit those tiers, and calls out explicitly where a future benchmark could change the answer, rather than pre-building caching machinery for a problem not yet confirmed to exist.
- **Single-user, single-process scale** — per `decisions/ADR-003-local-analytics-storage.md`, on the order of a few million hands, not billions, and per `04-architecture.md` only the backend process ever touches either database (the frontend has no business logic and never queries DuckDB/SQLite directly). Confirmed against DuckDB's actual concurrency model: within one process, DuckDB supports one write transaction at a time with any number of concurrent MVCC-snapshot readers; across processes, only one process may hold the file read-write at all. Since this application is exactly that — one backend process, one file — multi-process write concurrency is a non-problem here, not something this strategy needs to design around.
- **No native materialized views** — confirmed directly against current DuckDB behavior: `CREATE VIEW` always re-runs its query on every read; there is no built-in incremental-refresh materialized view. The only way to get materialized-view behavior is `CREATE OR REPLACE TABLE ... AS SELECT`, refreshed manually. This matters below: "materialize an aggregate as a table" is not a free/automatic choice in DuckDB the way it might be in some other engines — it's a real, self-managed feature this strategy avoids unless a benchmark justifies it.

---

## What Is Stored: Facts

Every Fact group defined in `07-analytics-engine.md` becomes a physically stored, normalized table in DuckDB — not a document-per-hand blob. This is a direct consequence of DuckDB being a columnar engine: Aggregate Stat Query (`06-query-catalog.md`'s QF-1) touches specific columns (street, action type, player, position) across many hands, which is exactly what column-oriented storage with per-column compression and pruning is built for. A JSON-blob-per-hand shape would force DuckDB to deserialize every hand fully for even a single-column aggregation, throwing away its main advantage.

| Table (conceptual) | Corresponds to (`07-analytics-engine.md`) | Notes |
|---|---|---|
| `hands` | Hand context Facts | One row per hand: room, format, game, limit type, stakes, ante, currency, date/time, table size, tournament id (nullable) |
| `seats` | Seat context Facts | One row per player per hand: seat number, player id (see Player Identity below), starting stack, **and** position — see Denormalization Rules |
| `actions` | Action Facts | One row per action: hand id, player id, street, action type, amount, sequence order. Today's provisional `HandAction` (player/amount only in free-text `rawLine`) must be normalized into these columns before import — carried over from `07-analytics-engine.md`'s Open Questions, and a hard requirement on `09-data-model.md` / the Phase 9 Import Engine, not solved here |
| `boards` | Board Facts | One row per (hand, run index, street): community cards. Keeps `runItTwice` support (`ParsedHand.boards`) intact |
| `results` | Result Facts | One row per (hand, player): showdown Y/N, cards shown/mucked, amount won, hand's total pot/rake |
| `tournament_results` | Tournament result Facts | One row per tournament entry, **Hero only** (per `decisions/ADR-008-tournament-summary-parser.md`): buy-in total, finish place, prizepool, cash won, bounty won |
| `players` | (identity dimension, not a Fact group on its own) | See Player Identity below |
| `sessions` | (derived grouping, but stored — see below) | One row per detected session: start/end timestamp, room(s), table count |

**Sessions are the one derived concept stored as Facts, not recomputed on every read.** `07-analytics-engine.md` defines a session as a Fact-derived grouping (contiguous hands, idle-threshold gap). Unlike a stat's opportunity/event logic, a session boundary is not a pure function evaluable per-row — it requires a stateful, ordered scan across a player's whole hand history (sort by time, detect gaps). Recomputing that scan on every Session List load (QF-6) would be wasteful and, worse, could shift previously-assigned session boundaries around as new hands get imported out of order (e.g. a bulk historical import interleaved with live play). Sessions are therefore computed once, when the relevant hands are imported (or re-detected only for the affected time range, not the whole history), and stored — while remaining fully recomputable from `hands` alone if ever needed (e.g. after changing the idle-threshold default), preserving the recalculability principle at the level of "can be recomputed," not "is recomputed on every read."

---

## What Is Calculated, Not Stored: Opportunities, Events, Metrics, Aggregations

Opportunities, Events, Metrics and Aggregations (per `07-analytics-engine.md`) are **not** persisted as separate tables in v1. Each is expressed as SQL — a shared, single-sourced view or parameterized query per stat — evaluated directly against the Facts tables at query time.

Why not materialize them:

- **Recalculability and single-sourcing.** A stat's definition (e.g. "what counts as a 3-bet opportunity") must live in exactly one place. Storing precomputed opportunity/event rows creates a second copy of that logic (the code that populated the table) that can silently drift from the code that would recompute it — the same failure mode the analytics engine's Facts layer was designed to avoid at the raw-data level.
- **No free materialization in DuckDB.** As established above, "materialize this as a table" is a manual, self-maintained mechanism here (`CREATE TABLE AS SELECT`, refreshed by hand) — not a lightweight, automatic feature. Reaching for it has a real ongoing cost (a refresh/invalidation strategy to design and maintain), so it should be justified by an actual measured need, not adopted by default.
- **DuckDB is built for exactly this.** A vectorized, columnar OLAP engine computing an aggregate over a few million rows of already-normalized, well-typed Facts is squarely the workload DuckDB exists for (`decisions/ADR-003-local-analytics-storage.md`) — the qualitative "Fast" (<500ms) / "Interactive" (<100ms) tiers from `06-query-catalog.md` are very plausibly reachable with plain SQL over Facts at this data scale, without needing a cache layer at all.

**Default v1 design**: every Metric/Aggregation from `07-analytics-engine.md` is one SQL query (or a small library of parameterized query-building code in the `analytics` module, per `decisions/ADR-002-modular-monolith.md`) executed live against `hands`/`seats`/`actions`/`results`/`tournament_results`. This is the simplest thing that could work, deliberately, per the roadmap's "avoid premature optimization" principle.

**Revisit trigger, not a current plan**: if Phase 9 benchmarking (once the Import Engine exists and can load a realistic multi-million-hand dataset) shows a specific query family missing its latency tier — most likely Live Session's per-hand recompute (QF-1, scope=CurrentSession) or Overview's landing-page load — the fallback is a targeted `CREATE TABLE AS SELECT` materialization for *that* query alone, refreshed on the triggering event (new hand imported), not a general caching layer adopted upfront. Named here so a future implementer knows the intended escape hatch, without building it before it's shown to be needed.

---

## Denormalization Rules

Some values are technically "derived," but are cheap, unambiguous, single-formula facts about a hand or seat rather than stat-specific judgment calls — these **are** denormalized into the Facts tables at import time, to avoid every consumer re-deriving them (and risking subtly different logic in different places):

| Denormalized into Facts | Why |
|---|---|
| Seat's **position** (button-relative) | A pure function of button seat + active seats at the hand's start — needed by nearly every stat and by `06-query-catalog.md`'s Hand Details breakdown/filter, cheap to get wrong if re-derived ad hoc in multiple queries |
| Seat's **effective stack in big blinds** | Pure function of starting stack ÷ that hand's big blind — needed by the Stack Size (BBs) metric and useful as a general filter/breakdown value |
| Hero's **rake share** (proportional-contribution estimate, per `07-analytics-engine.md`) | An explicitly-approximated value computed once at import time from `results`, not re-derived per query, so every screen that shows "rake paid" agrees with every other one |

Everything past this point — whether a hand is a 3-bet pot, whether a player faced a C-bet, whether a hand reached showdown for a given player as an "opportunity" for WTSD% — stays in the query layer (previous section), because those *are* the stat-specific judgment calls this project must keep single-sourced.

---

## Update Strategy

- **New imports are inserts.** A freshly-imported hand that hasn't been seen before is an append to `hands`/`seats`/`actions`/`boards`/`results` (and `tournament_results` for Winamax tournament summaries). This is the common case and matches the "raw hand histories are immutable" principle directly — nothing already stored is touched.
- **Hands are identified by a natural key**, not just an internal surrogate id: `(room, handId)` for cash/tournament hands (every supported room's hand history states its own hand id, per `importing.model.ParsedHand.handId`), and `(tournamentId, playerName)` for `tournament_results` entries (per `decisions/ADR-008-tournament-summary-parser.md`, a file can hold multiple concatenated entries for the same recurring tournament). This natural key is what makes re-import detection and reprocessing possible.
- **Reprocessing (parser fix, re-import of the same file)** — explicitly anticipated by `decisions/ADR-003-local-analytics-storage.md`'s own rationale for choosing DuckDB over ClickHouse ("reprocessing hands after a parser fix"). Handled as **delete-then-reinsert by natural key**, inside one transaction per hand (or per import batch): all rows across `hands`/`seats`/`actions`/`boards`/`results` for that `(room, handId)` are deleted, then the freshly re-parsed rows are inserted. Simpler and safer than an in-place `UPDATE` of every affected column across five tables, and DuckDB's single-writer transaction model (see Guiding Constraints) makes this safe to do without a special locking scheme.
- **Sessions are re-detected incrementally**, not globally, when reprocessing or importing out of order: only the time range touched by the affected hands needs its session boundaries recomputed (per the Sessions note above), not the player's entire history.
- **Deletions** (a user removing an imported session or file) cascade the same way: delete by natural key across the Facts tables, plus the derived `sessions` rows that referenced only those hands.
- Crash-safety (mid-write power loss, etc.) is DuckDB's own WAL/checkpoint mechanism — not a concern this strategy needs to design around; noted here only so a future reader doesn't think it was overlooked.

---

## Player Identity Storage

`05-product-map.md`'s Players module aggregates opponents **by nickname across rooms** (no per-room split), and `06-query-catalog.md`'s QF-5 already composes DuckDB data (rooms played, first/last seen — derived from Facts) with SQLite data (notes/tags — user-entered app data) for one player profile. That composition requires a **stable player id that means the same thing in both databases**, even though DuckDB and SQLite are two separate files with no enforced foreign key between them.

Design: a `players` table lives in DuckDB (it's a dimension derived from and joined against hand Facts constantly — rooms played, hands vs. Hero, quick stats), keyed by a stable id assigned the first time a nickname is seen. SQLite's notes/tags table stores that same id as a plain value (an application-level contract, not a database-enforced FK — the two engines can't share one). If the nickname-aggregation assumption ever changes (already flagged as a real dependency in `06-query-catalog.md`'s Stated Assumption), any player-identity remap must update both stores together, in the same operation — called out here so it isn't discovered as a surprise later.

---

## Retention & Tiering (Provisional)

`03-product-vision.md`'s Monetization Model limits the Basic tier by "hand-import volume and history retention window," with exact caps still TBD. This storage strategy doesn't need the exact numbers to state the mechanism: retention is enforced as a **query-time filter** (hands older than the retention window, or beyond the import-volume cap, are excluded from what the app shows/computes), not physical deletion. Raw Facts for hands outside the currently-active window stay stored and untouched — consistent with "raw hand histories are the single source of truth" — so that a subscription upgrade or a cap change immediately restores full access without re-importing anything. This is a default consistent with the local-first principle (the user's own data, once imported, is never silently destroyed by a billing state change); revisit if the eventual entitlement design (`03-product-vision.md`, deferred to before Phase 8) decides otherwise.

---

## DuckDB Optimization Strategy

At the strategy level (exact DDL is `09-data-model.md`'s job):

- **Physical ordering for range pruning.** DuckDB's row groups carry min/max zonemaps per column; inserting `hands` (and joined Facts) roughly ordered by date lets date-range filters (the single most common Global Filter Parameter, per `06-query-catalog.md`) skip whole row groups instead of scanning them. Bulk historical imports (large, one-time) can be sorted before insert; ordinary day-to-day imports arrive in time order already.
- **Prefer scans over point-lookup indexes for analytics.** Most of this project's queries are aggregations over a filtered population (QF-1, QF-2), which is what columnar scan-and-vectorize execution is built for — not the workload traditional B-tree/ART point-lookup indexes help most with. DuckDB's ART indexes are reserved for genuinely point-lookup-shaped access: `hands`/`results` by an exact hand id (QF-3, Hand Detail Query — "opened directly from a click, no intermediate load state expected," Interactive-tier latency) and `players` by id (QF-5).
- **Typed columns over generic text**, especially for money (fixed-precision decimal, not floating point, to avoid rounding drift across every aggregation that sums money — relevant given hands are also multi-currency) and for enums (action type, street, position) as DuckDB `ENUM` types rather than free text, which is both more compact and faster to filter/group on than string comparison.

---

## What This Sets Up for Phase 7

`09-data-model.md` should be able to go essentially straight from the table list in "What Is Stored" above to real DDL: column names/types, primary/natural keys (per Update Strategy), and the `players` cross-database id contract. This document deliberately stops short of writing that DDL itself, per its own scope boundary.

---

## Open Questions

- **Exact retention/hand-volume caps** for the Basic tier (`03-product-vision.md`) are still undecided upstream of this document; the query-time-filter enforcement mechanism above doesn't depend on the exact numbers, but the entitlement-check design itself (`03-product-vision.md`'s existing follow-up, due before Phase 8) does.
- **No real benchmark exists yet** for any of this — every latency judgment above is reasoned from DuckDB's known design characteristics and the project's known data-scale ceiling (a few million hands), not measured. `06-query-catalog.md` already flagged this; repeated here because storage strategy is exactly where a wrong guess would be most expensive to unwind. Revisit once the Phase 9 Import Engine can produce a realistic dataset to test against.
- **Session re-detection on out-of-order/backfilled imports** — the incremental-recompute approach above (only the affected time range) assumes it's straightforward to know which range is "affected" when hands from an arbitrary past date get imported later. Needs a concrete algorithm once `09-data-model.md`/Phase 9 actually implement it; not fully designed here.
