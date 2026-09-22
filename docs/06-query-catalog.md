# Query Catalog

## Overview

This document lists every query the application needs — Phase 4 of `00-development-roadmap.md`.

Question answered:

> What information does every screen require?

**On organization — query families, not one entry per screen.** Walking every screen in `05-product-map.md` shows that several of them — Overview's Career Summary, Statistics' configurable stats, Players' Profile stats, and Live Session's current-session stats — are the same underlying computation ("compute a stat set, aggregated over a filtered/grouped population of hands"), just with different scope, filters, or breakdowns. Cataloging one query per screen would document that same computation four times over, and would contradict two things this project has already decided:

- `04-architecture.md` sequences documentation as `05-product-map → 06-query-catalog → 07-analytics-engine → 08-storage-strategy → 09-data-model → 10-api-design` — **API design comes last**. Queries here are logical/analytical operations, independent of whatever REST endpoint shape `10-api-design.md` eventually gives them, not one-per-screen HTTP calls.
- `decisions/ADR-002-modular-monolith.md` requires each backend module to expose "a small application-service API for other modules to call" — a shared, reusable service per concern, not a service per screen.

So this catalog is organized by **query family** — the actual shape of a data-access pattern — each documented once with every screen that uses it. It closes with a **Screen → Queries Used** mapping table, which is what satisfies the roadmap's exit criterion ("every screen has an associated list of queries") literally.

Two structural questions came up while designing this catalog and were resolved with the project owner rather than assumed:

- **Live Session's stats** (VPIP/PFR/3-Bet/P&L for the in-progress session) recompute only **between hands** — triggered the same way as every other screen, on each hand the Import Engine finishes parsing. It does **not** need a new in-memory/live-tracking layer for sub-hand state (e.g. mid-hand pot odds) — that would be a materially different, not-yet-designed data source, and isn't required. This keeps Live Session a thin variant of the Aggregate Stat Query family below, not its own family.
- **Minimum sample size** (Statistics/Players breakdown filter) **excludes** rows below the threshold outright (`HAVING count >= N`) rather than showing them flagged as low-confidence. Simpler contract, simpler query, simpler UI.

---

## Shared Reference

Defined once here; every query family below references these instead of repeating them.

### Global Filter Parameters

Present on nearly every screen (per `05-product-map.md`, "global filters"):

| Parameter | Domain |
|---|---|
| Poker Room | One of the imported rooms (PokerStars, Winamax, PartyPoker, 888, iPoker, Winning Poker Network — per `decisions/ADR-005-early-import-parser.md`), or "all" |
| Stakes | A stakes level/range as recorded on the hand (e.g. blinds or buy-in tier), or "all" |
| Game Type | Cash / Tournament / Spin&Go (Expresso), or "all" — determines which stats in a query's `statSet` are even legal, see Aggregate Stat Query below |
| Date Range | A from/to date bound, or "all time" |
| Currency | Display currency for money amounts (hands are stored in their original currency; this is a display-layer concern, not a filter that changes which hands match) |

### Target Latency Tiers

Qualitative, not concrete milliseconds — real numbers are `08-storage-strategy.md`'s job (Phase 6), once real data volumes are benchmarked on DuckDB. Assigning a tier now is enough to inform Phase 5/7 design without pretending to precision we don't have yet.

| Tier | Budget | Example |
|---|---|---|
| Interactive | < 100ms | Populating a filter dropdown while typing |
| Fast | < 500ms | Loading a screen's main content |
| Background-acceptable | < 3s | Generating/exporting a report |

### Frequency

Described as a trigger, not a call-volume number — this is a single-user local desktop app (per `decisions/ADR-003-local-analytics-storage.md`: "the scale is single-player/local, not multi-tenant"), so "N calls/day" isn't a meaningful measure. Triggers used below: *on screen load*, *on filter change*, *on every imported hand*, *on-demand*.

### Stated Assumption

**Player identity resolution.** Every family below that groups or looks up an opponent by "player" assumes nickname-aggregated-across-rooms (per `05-product-map.md`'s Players scope: "aggregated across rooms, no per-room split") is a reliable grouping key. This is a real dependency, not a detail this catalog invents — if that assumption changes, every family using `Opponent(playerId)` scope is affected.

### Not Queries — Explicitly Out of Scope

"Tag/mark a hand" (Hands) and "add a note/tag to a player" (Players) are writes/commands, not queries. Left out of this catalog on purpose, not by oversight.

---

## Query Families

### QF-1 — Aggregate Stat Query

**Description**: Compute a chosen set of stats over a filtered, optionally grouped population of hands.
**Module (ADR-002)**: `analytics`
**Data Source**: DuckDB
**Scope**: `Hero | Opponent(playerId) | CurrentSession | Session(sessionId) | AllOpponents` — `Session(sessionId)` (a specific, already-closed session) was added during `10-api-design.md` (Phase 8) to make QF-7's own composite definition below well-formed; not present in this family's original Phase 4 draft.
**Parameters**:
- `statSet` — one or more stat identifiers from `13-statistics-module.md`'s catalog (fixed for Overview/Live Session, user-chosen for Statistics, a fixed "vs Hero" set for Players).
- `breakdown` — zero or more grouping dimensions: *none* (one summary row), *date bucket*, *PlayerId*, or any dimension from `13-statistics-module.md`'s MVP breakdown categories (Game Details, Hand Details).
- Must validate `statSet` against the active Game Type filter — e.g. BB/100 is cash-only, ROI%/Bubble Factor are tournament-only (`13-statistics-module.md`). This is a correctness requirement, not just a filter: an illegal stat/game-type combination should be rejected or omitted, not silently computed against the wrong population.

**Filters**: Global Filter Parameters + minimum sample size (excludes, per the decision above — `HAVING count >= N`) where the screen offers it (Statistics, Players).
**Frequency**: On screen load; on filter change; on every imported hand (Live Session, Overview).
**Target Latency**: Fast — this is a screen's main content load in every case it's used.
**Expected Result**: One summary row (no breakdown) or one row per breakdown-dimension value (grouped).
**Used By**: Overview (Career Summary — no breakdown; Performance Trends — breakdown=date bucket), Statistics (user-chosen breakdown), Players Profile "Stats vs Hero" (scope=Opponent), Player List's per-row quick stat (breakdown=PlayerId, computed for every opponent in one grouped query rather than one call per row), Live Session (scope=CurrentSession).
**Notes**: This is the catalog's central, most-reused family — four screens that look unrelated in the product map turn out to be one parameterized query. Live Session's only real difference from the others is *when* it re-runs (on every imported hand, not just on screen load/filter change) — not a different data source or computation.

---

### QF-2 — Hand List Query

**Description**: Paginated, filtered, sorted list of hand summary rows.
**Module (ADR-002)**: `hands`
**Data Source**: DuckDB
**Scope**: `Hero | Opponent(playerId)`
**Parameters**: sort column/direction, page/limit, opponent-id (when filtering to hands vs. one player).
**Filters**: Global Filter Parameters + screen-specific — Result & size (won/lost, all-in, pot/P&L threshold), Situation (position, 3-bet pot, street the hand ended on).
**Frequency**: On screen load; on filter change; on pagination.
**Target Latency**: Fast.
**Expected Result**: Paginated list of hand summary rows + total count.
**Used By**: Hands (table), Players Profile ("Hands vs this player" — adds opponent-id filter), Statistics (drill-down destination when a stat/breakdown cell is clicked).
**Notes**: This family's filter vocabulary must be a **superset** of QF-1's breakdown-dimension vocabulary — a clicked breakdown cell in Statistics (e.g. "3-Bet% from the Button") has to translate directly into a Hand List filter (position=Button) for drill-down to work. Stated here as a design constraint on both families, not assumed independently in each.

---

### QF-3 — Hand Detail Query

**Description**: A single hand's full normalized payload — every street, action, stack, board card, and result, plus every seat's known cards.
**Module (ADR-002)**: `hands`
**Data Source**: DuckDB
**Scope**: N/A (looked up by hand ID)
**Parameters**: hand ID.
**Filters**: None (single-record lookup).
**Frequency**: On-demand (row action).
**Target Latency**: Interactive — opened directly from a click, no intermediate load state expected.
**Expected Result**: One full hand record.
**Used By**: Hands' **Replay** (visual street-by-street) and **Detail** (action summary) row actions.
**Notes**: Replay and Detail are the same query rendered two ways, not two families — worth stating explicitly since the product map presents them as two separate actions.

---

### QF-4 — Player List Query

**Description**: Searchable, sortable list of opponents.
**Module (ADR-002)**: `players`
**Data Source**: DuckDB
**Scope**: `AllOpponents`
**Parameters**: search term (substring match on nickname — a filter parameter on this family, not its own query), sort column/direction, page/limit.
**Filters**: Global Filter Parameters.
**Frequency**: On screen load; on filter/search change.
**Target Latency**: Fast.
**Expected Result**: Paginated list of players (nickname, hands played vs. Hero) + total count. The "quick stat" column is QF-1 (breakdown=PlayerId) joined in, not computed here.
**Used By**: Players (list view).

---

### QF-5 — Player Detail Query

**Description**: A single opponent's non-stat profile metadata.
**Module (ADR-002)**: `players`
**Data Source**: DuckDB (rooms played, first/last seen) + SQLite (notes/tags — user-entered app data)
**Scope**: `Opponent(playerId)`
**Parameters**: player ID.
**Filters**: None (single-record lookup).
**Frequency**: On screen load (navigating to a player's profile).
**Target Latency**: Fast.
**Expected Result**: One player record — nickname, rooms played on, first/last seen, notes, tags.
**Used By**: Players Profile.
**Notes**: **Worked example of composition** — the Players Profile screen is not one query, it's three: this (QF-5) + Aggregate Stat Query (QF-1, scope=Opponent) + Hand List Query (QF-2, filtered to this opponent). Screens compose query families; they don't each get exactly one.

---

### QF-6 — Session List Query

**Description**: Paginated list of past (closed) sessions.
**Module (ADR-002)**: `hands` (sessions are a grouping over hands; no dedicated `sessions` module is implied yet — revisit if one emerges when modules are finalized per ADR-002)
**Data Source**: DuckDB
**Scope**: `Hero`
**Parameters**: sort column/direction, page/limit.
**Filters**: Global Filter Parameters + date range (defines report coverage when used by Reports).
**Frequency**: On screen load; on filter change.
**Target Latency**: Fast.
**Expected Result**: Paginated list of sessions (start/end, duration, room, stakes, P&L, hands played) + total count.
**Used By**: Reports → Session History; Overview's Recent Activity "last session" (the degenerate case: limit=1, sorted most recent — not a separate family).

---

### QF-7 — Session Detail Query

**Description**: A single session's full detail.
**Module (ADR-002)**: `hands`
**Data Source**: DuckDB
**Scope**: `Session(sessionId)`
**Parameters**: session ID.
**Filters**: None (single-record lookup).
**Frequency**: On-demand (click-through from Session History).
**Target Latency**: Fast.
**Expected Result**: Session metadata (start/end, duration, room, stakes) + stats.
**Used By**: Reports → Session History (row click-through).
**Notes**: Modeled as a **composite**, like QF-5 — session metadata + Aggregate Stat Query (QF-1, scope=Session(sessionId)) — for the same reason: it's not a new computation, it's the existing stat engine scoped narrowly.

---

### QF-8 — Filter Facet / Distinct-Values Query

**Description**: The distinct values actually present in the user's own data, for populating a Global Filter Parameter's dropdown (e.g. which rooms/stakes this user has actually played).
**Module (ADR-002)**: cross-cutting (used to populate Global Filter Parameters wherever they appear)
**Data Source**: DuckDB
**Scope**: `Hero`
**Parameters**: which filter facet to enumerate (Room / Stakes / Game Type).
**Filters**: None (or the other currently-selected filters, if dropdowns should narrow each other — a UX decision for `12-ui-designs.md`, not fixed here).
**Frequency**: On screen load.
**Target Latency**: Interactive — this gates rendering a filter control.
**Expected Result**: A list of distinct values.
**Used By**: Every screen with Global Filter Parameters (Overview, Hands, Players, Statistics, Reports).
**Notes**: Identified as a real gap while designing this catalog — it wasn't named in any screen's own description in `05-product-map.md`, but every one of those screens needs it to render its filter dropdowns at all.

---

### QF-9 — Saved View Query

**Description**: List or load a named stat-set + breakdown + filter combination.
**Module (ADR-002)**: `analytics` (metadata only — see Data Source)
**Data Source**: SQLite — **app-data query, not analytics**. This reads user configuration, not poker hands; it won't behave like the DuckDB-backed families above (no data-volume scaling concern, for instance).
**Scope**: `Hero`
**Parameters**: view name (for load), or none (for list).
**Filters**: None.
**Frequency**: On-demand (save/load/list action).
**Target Latency**: Interactive.
**Expected Result**: A saved view's stat set + breakdown + filter parameters (for QF-1 to then execute), or a list of saved view names.
**Used By**: Statistics (save/reload a view), Reports (export a saved view — see the Report Generation note below).

---

### QF-10 — System / Meta Query

**Description**: Application/database metadata not derived from poker hands — database size, last import timestamp, import history.
**Module (ADR-002)**: `importing`
**Data Source**: SQLite + filesystem — **app-data query, not analytics**.
**Scope**: N/A
**Parameters**: None.
**Filters**: None.
**Frequency**: On screen load.
**Target Latency**: Fast.
**Expected Result**: A small metadata record (DB size, last import time, most recently played room).
**Used By**: Overview's Recent Activity.

---

### Not a family: Report Generation is an orchestration

Reports → Report Generation (predefined templates, or exporting a saved Statistics view, as PDF/CSV) does not get its own query family. It **composes** the outputs of QF-1 (Aggregate Stat Query), QF-2 (Hand List Query), QF-6 (Session List Query), and QF-9 (Saved View Query) into a template, then renders/exports the result. Stated explicitly so a future reader doesn't invent a "Report Query" family — the export/rendering step is an application-service concern, not a new data-access shape.

---

## Screen → Queries Used

| Screen | Query Families Used | Notes |
|---|---|---|
| Overview | QF-1 (no breakdown; breakdown=date for trends), QF-6 (degenerate, limit=1), QF-10 | Career Summary, Performance Trends, and Recent Activity are three different families on one screen |
| Live Session | QF-1 (scope=CurrentSession) | Same family as everywhere else; only the *trigger* (on every imported hand) is different |
| Hands | QF-2, QF-3 | Table = QF-2; Replay/Detail row actions = QF-3 |
| Players | QF-4, QF-5, QF-1 (scope=Opponent, breakdown=PlayerId for the list), QF-2 (opponent-filtered) | List uses QF-4 + QF-1; Profile composes QF-5 + QF-1 + QF-2 |
| Statistics | QF-1 (user-chosen breakdown), QF-2 (drill-down), QF-9 | The screen itself is thin — it's a UI over QF-1's full parameter space, plus QF-9 for Saved Views |
| Reports — Session History | QF-6, QF-7 | List = QF-6; row click-through = QF-7 (composite with QF-1) |
| Reports — Report Generation | QF-1, QF-2, QF-6, QF-9 (orchestrated, not a new family) | See "Not a family" note above |
