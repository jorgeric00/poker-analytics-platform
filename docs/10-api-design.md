# API Design

## Overview

This document defines **how the frontend and backend communicate** — Phase 8 of `00-development-roadmap.md`: endpoints, DTOs, requests, responses, error handling.

Question answered:

> How do the application layers communicate?

**Scope boundary:** `06-query-catalog.md` already named every logical read operation (10 query families) independent of transport shape, on purpose — that document explicitly sequences API design *after* the query catalog so endpoints aren't invented one-per-screen. This document is where those families (plus the write-side commands no query family covers) finally become real HTTP endpoints. `09-data-model.md`'s tables are what these endpoints ultimately read/write, via the `analytics`/`hands`/`players`/`importing` module APIs (`decisions/ADR-002-modular-monolith.md`) — this document doesn't re-decide storage, only the wire contract on top of it.

**Exit criterion (roadmap):** the frontend can be implemented independently. Everything below is written so a frontend engineer never needs to open backend code to know what to call.

---

## Conventions

### Transport

Plain HTTP + JSON, per `decisions/ADR-004-backend-packaging.md` ("The frontend communicates with it over local HTTP"). No GraphQL, no gRPC — there's exactly one client (this app's own frontend) and one server (the local sidecar process), so REST-over-JSON's main downside (over/under-fetching for many heterogeneous clients) doesn't apply, and it keeps the stack conventional on both ends (Spring MVC controllers, `fetch` on the frontend).

### Base URL & Discovery

- Local dev (`mvn spring-boot:run`, per `CLAUDE.md`): fixed `http://localhost:8080`.
- Packaged app: the backend binds to `127.0.0.1` on an **OS-assigned port** (`server.port=0`), to avoid colliding with anything else the user has running, and prints the bound port on startup. Tauri, which owns the sidecar process, reads that port from the process's stdout and exposes it to the frontend via a Tauri command (e.g. `invoke('get_backend_port')`) — a small addition to the packaging work already scoped to Phase 9 in `decisions/ADR-004-backend-packaging.md`, not a new architectural decision.
- Binds to loopback only, never `0.0.0.0` — consistent with local-first (`04-architecture.md`): nothing outside the user's own machine should ever reach this server.

### Versioning

Every path is prefixed `/api/v1`. Bumped only on a breaking change to an existing endpoint's contract; additive changes (new optional field, new endpoint) don't require a bump.

### Authentication

None, at this layer. A loopback-only server with a single local user has nothing to authenticate — the "who is this request from" question doesn't arise (`04-architecture.md`'s Local First principle). This is distinct from the still-undesigned **subscription/entitlement check** (`03-product-vision.md`'s Monetization Model) — that's a call from the *backend* to an external licensing service, not part of the frontend↔backend contract this document defines. See Open Questions.

### Request Style: GET vs. POST

- **GET**, with query-string parameters, for endpoints whose filters are all scalars (ids, strings, dates, page/limit) — cacheable, bookmarkable, and idiomatic REST. Used for hand lists, player lists, session lists, filter facets, saved-view lookups.
- **POST**, with a JSON body, for the one read endpoint whose defining parameters are inherently structured and don't fit a query string cleanly — the Aggregate Stat Query (`statSet`/`breakdown` arrays, a nested filter object). This is a read, not a resource creation, but forcing it into GET query-string encoding (arrays and nested objects flattened into `?stat[]=vpip&stat[]=pfr&...`) would be worse for both readability and the 400-line-of-encoded-query-string problem real frontends hit with this shape of query — same rationale tools like Elasticsearch's `_search` already settled on. Called out explicitly here since it's the one deliberate exception to the GET-for-reads default.
- **POST/PUT/DELETE** for every actual command (write), following standard REST semantics (POST = create, PUT = replace/upsert, DELETE = remove).

### Pagination

Every list endpoint takes `page` (1-based) and `pageSize` query params, and returns `total` (matching row count across all pages) alongside `items`.

### Sorting

`sort=<field>&sortDir=asc|desc`, where `<field>` is one of that endpoint's documented sortable columns.

### Data Types on the Wire

- **Money is a string, never a JSON number.** `09-data-model.md` stores money as `DECIMAL(18,4)`; JSON has no native decimal type, and encoding money as a JSON number risks silent floating-point rounding — unacceptable for figures that get summed across a potentially large hand history. Every money field is `{ "amount": "1234.5600", "currency": "EUR" }`, and the frontend parses `amount` with a decimal library, never `parseFloat`.
- **Timestamps** are ISO-8601 UTC strings (`2026-05-19T14:15:00Z`).
- **Ids** are plain JSON numbers (DuckDB `BIGINT` surrogate keys). Not stringified: at this project's scale (`decisions/ADR-003-local-analytics-storage.md`, a few million hands) a sequence-generated id comes nowhere near JSON's 2^53 safe-integer ceiling, so the usual bigint-in-JSON precision concern doesn't apply here.
- **Enums** (room, format, street, action type, position, ...) are serialized as their `09-data-model.md` enum names (e.g. `"POKERSTARS"`, `"PREFLOP"`) — one vocabulary from database to API to frontend, no separate translation table to keep in sync.

### Error Handling

Every non-2xx response body:

```json
{
  "error": {
    "code": "STAT_QUERY_INVALID_FILTER",
    "message": "Human-readable description, safe to show directly if no better UI copy exists.",
    "details": {}
  }
}
```

| Status | Meaning | Example |
|---|---|---|
| 400 | Malformed or invalid request (bad enum value, missing required field, `@Valid` failure) | An unrecognized `statSet` identifier |
| 404 | Referenced resource doesn't exist | `GET /hands/{id}` for an id that was never imported |
| 409 | Conflict with existing state | Creating a saved view whose name already exists |
| 500 | Unexpected server error | Any unhandled exception |

**Partial-failure results are not errors.** Two cases established in earlier phases — an illegal stat/game-type combination (`06-query-catalog.md`'s QF-1 correctness requirement: "rejected or omitted, not silently computed") and a money-valued stat requested over a scope spanning more than one currency with no resolved conversion mechanism yet (`07-analytics-engine.md`'s Open Questions) — are both handled the same way: the specific stat is **omitted** from that row's `values`, and a `warnings` array on the response says which stat and why, rather than failing the whole request with a 400. A dashboard showing nine stats and a tenth explained warning is far more useful than an all-or-nothing failure.

---

## Read Endpoints (Query Families)

### `POST /api/v1/stats/query` — QF-1, Aggregate Stat Query

Request:
```json
{
  "scope": { "type": "HERO" },
  "statSet": ["VPIP", "PFR", "THREE_BET", "BB_100"],
  "breakdown": ["POSITION"],
  "filters": { "room": "all", "stakes": "all", "gameType": "CASH", "dateFrom": null, "dateTo": null, "displayCurrency": "EUR" },
  "minSampleSize": 30
}
```
`scope.type` is one of `HERO | OPPONENT | CURRENT_SESSION | ALL_OPPONENTS`; `scope.playerId` is required when `type` is `OPPONENT`. Stat identifiers match `07-analytics-engine.md`'s catalog (`VPIP`, `PFR`, `THREE_BET`, `FLOP_CBET`, `FOLD_VS_FLOP_CBET`, `HANDS`, `STACK_SIZE_BB`, `BB_100`, `ROI`, `BUBBLE_FACTOR`). Breakdown identifiers match `13-statistics-module.md`'s MVP dimensions (`POSITION`, `WIN_LOSS`, `SHOWDOWN`, `ROOM`, `STAKES`, `GAME_TYPE`, `DATE_BUCKET`).

Response:
```json
{
  "rows": [
    { "breakdownKey": { "POSITION": "BTN" }, "sampleSize": 412, "values": { "VPIP": 34.2, "PFR": 28.1, "BB_100": 6.4 } }
  ],
  "warnings": [
    { "stat": "BB_100", "breakdownKey": null, "reason": "CASH_ONLY_STAT_IN_TOURNAMENT_SCOPE" }
  ]
}
```
`breakdownKey` is `null` for the no-breakdown (single summary row) case. Rate stats are plain numbers already expressed as a percentage (`34.2` = 34.2%); money-valued stats use the money shape above instead of a bare number.

**Used by**: Overview (Career Summary, Performance Trends), Live Session, Statistics, Players List (quick stat) and Profile (stats vs. Hero) — exactly as mapped in `06-query-catalog.md`'s Screen → Queries Used table. Each screen is a different `scope`/`statSet`/`breakdown` combination against this one endpoint, not a different endpoint.

**Implemented so far**: `scope.type = "HERO"` and `"OPPONENT"` (`scope.playerId` required, validated to be a real, non-Hero `players` row), a single (or no) breakdown dimension, and `VPIP`/`PFR`/`THREE_BET`/`FLOP_CBET`/`FOLD_VS_FLOP_CBET`/`HANDS`/`STACK_SIZE_BB`/`BB_100` (see `CashStatsCalculator`'s own class doc for what's not: `CURRENT_SESSION`/`ALL_OPPONENTS` scope, `ROI`/`BUBBLE_FACTOR` stats, `STAKES`/`DATE_BUCKET` breakdown, the `filters` object). The Players module's quick-stat list does **not** use this endpoint per-row despite what "one grouped query" above implies — see `PlayersQueryService`'s class doc for that documented deviation.

---

### `GET /api/v1/hands` — QF-2, Hand List Query

Query params: `room`, `stakes`, `gameType`, `dateFrom`, `dateTo` (Global Filters); `wonLost`, `allIn`, `minPot`, `position`, `threeBetPot`, `endedOnStreet` (Result & Situation filters, `05-product-map.md`); `opponentPlayerId` (Players/Statistics drill-down); `page`, `pageSize`, `sort`, `sortDir`.

Response:
```json
{
  "items": [
    {
      "handId": 918273,
      "room": "POKERSTARS",
      "format": "CASH",
      "handTimestamp": "2026-05-19T14:15:00Z",
      "stakes": { "smallBlind": {"amount":"0.5000","currency":"EUR"}, "bigBlind": {"amount":"1.0000","currency":"EUR"} },
      "heroPosition": "BTN",
      "heroHoleCards": ["Ah", "Kd"],
      "playerCount": 6,
      "handType": "THREE_BET_POT",
      "result": "WON",
      "amount": { "amount": "42.5000", "currency": "EUR" },
      "tags": ["review-later"]
    }
  ],
  "total": 118432,
  "page": 1,
  "pageSize": 50
}
```
**Used by**: Hands (table), Players Profile ("Hands vs this player" — adds `opponentPlayerId`), Statistics (drill-down destination). Per `06-query-catalog.md`'s note, this endpoint's filter vocabulary is a superset of QF-1's breakdown vocabulary by design, so a clicked breakdown cell (e.g. "3-Bet% from the Button") maps directly onto `?position=BTN`.

**Implemented so far** (`backend/src/main/java/com/pokeranalytics/hands/HandsQueryService.java`, scoped to Hero/cash like every other query family): `wonLost`, `position` (comma-separated, OR within the field), `handType` (comma-separated; see below), `endedOnStreet` (comma-separated), `showdownOnly`, `opponentPlayerId` (added for the Players module's "Hands vs X" panel and "Open all hands vs X" drill-down — any hand where the given player id was also dealt in), `page`/`pageSize`/`sortDir` (`hand_id` order only — see next paragraph). Not yet implemented: `room`/`stakes`/`dateFrom`/`dateTo` (blocked on `hand_timestamp` normalization, though the filter param and DB column both exist for `room`), `allIn` (no room's parser records a structured all-in flag yet), `minPot`/`minWonLost`.

Two additive response fields beyond the shape shown above, both because `hands.hand_timestamp` isn't normalized yet: `handTimestamp` stays `null` on every row today, and `handTimestampRaw` (the room's own unparsed text) is included so the screen isn't blank while that's true — sorting is by `hand_id` (import order), not real chronological order, for the same reason. Also added: `handType` (see below) and `endedOnStreet` (`PREFLOP`/`FLOP`/`TURN`/`RIVER`, from the furthest street a board was dealt to).

**Hand type taxonomy** (docs/05-product-map.md's Hands section: "hand type (e.g. 3-bet pot, limped pot)" didn't specify the full set) — classified from the count of PREFLOP raises, not stored: `WALKED_POT` (no raise, no call), `LIMPED_POT` (no raise, ≥1 call), `RAISED_POT` (exactly one raise), `THREE_BET_POT` (exactly two), `FOUR_BET_PLUS_POT` (three or more).

---

### `GET /api/v1/hands/{handId}` — QF-3, Hand Detail Query

Response: the hand's full normalized payload — every street, action (with resolved player nickname, not just id), board (per run, if `runItTwice`), and every seat's known result. One payload renders both the Hands module's **Replay** (street-by-street) and **Detail** (action summary) views, per `06-query-catalog.md`'s note that they're the same query rendered two ways.

**Implemented so far**: everything except Replay itself (that's a separate, later Phase 9 item — see `11-development-tracking.md`) and revealed cards at showdown (`results.cards_shown` is always `null` today, a separate not-yet-done extraction from `SHOWS` actions' `raw_line` — showdown participation is still shown, just without cards). `startingStack` per seat is what it says — not a live/current stack, which isn't tracked. `previousHandId`/`nextHandId` walk Hero's hands in `hand_id` order, not the caller's active list filter — a known simplification.

---

### `GET /api/v1/players` — QF-4, Player List Query

Query params: `search` (nickname substring), Global Filters, `page`, `pageSize`, `sort`, `sortDir`.

Response: `{ "items": [{ "playerId": 42, "nickname": "villain123", "handsVsHero": 318, "quickStat": {"stat": "VPIP", "value": 27.4} }], "total": 891, "page": 1, "pageSize": 50 }`. `quickStat`'s underlying computation is QF-1 (breakdown=`PLAYER_ID`) joined server-side into this list, per `06-query-catalog.md`'s note that it's one grouped query, not one call per row — the frontend never calls `/stats/query` once per visible row.

**Implemented** (`backend/src/main/java/com/pokeranalytics/players/PlayersQueryService.java`): `search`, `minHands` (this document's `06-query-catalog.md`-level "minimum sample size" filter), `page`/`pageSize`, sorted by `handsVsHero` descending only (no `sort`/`sortDir` param yet). `handsVsHero` is real co-occurrence (hands where this opponent and Hero were both dealt in), computed as one grouped query as the shape above implies. `quickStat` is the one deliberate deviation from that shape: there's no `PLAYER_ID` breakdown dimension in `CashStatsCalculator` (every dimension there groups one already-chosen player's own hands by some facet of *their* hands — the opposite of "one stat per different player" this needs), so it's computed via one `CashStatsCalculator.compute(playerId)` call per row instead. Acceptable at this app's real scale (a local single user's opponent pool is realistically dozens to a few hundred, not millions — 38 for the actual 81-hand sample data this was verified against) but a real shortcut, not this endpoint's intended shape; tracked as a follow-up in `11-development-tracking.md`. Global Filters aren't wired to this endpoint yet (same gap as everywhere else — blocked on filter infrastructure this project hasn't built).

---

### `GET /api/v1/players/{playerId}` — QF-5, Player Detail Query

Response: `{ "playerId": 42, "nickname": "villain123", "roomsPlayed": ["POKERSTARS","WINAMAX"], "firstSeenAt": "...", "lastSeenAt": "...", "notes": "...", "tags": ["Nit"] }`, plus one additive field: `handsVsHero` (same co-occurrence count as the list, for the Profile header — same additive-field pattern as the Hands endpoints' `handTimestampRaw`). `firstSeenAt`/`lastSeenAt` are exactly what `players.first_seen_at`/`last_seen_at` mean (`09-data-model.md`) — when this nickname was first/last seen **by an import run**, not the first/last real hand played chronologically (that needs `hand_timestamp` normalization, still open).

**Composition, not a composed endpoint.** Per `06-query-catalog.md`'s explicit "worked example of composition" for this same query family, the Players Profile screen calls **three** endpoints — this one, `POST /stats/query` (`scope: {type:"OPPONENT", playerId}`), and `GET /hands?opponentPlayerId=...` — rather than one bespoke `/players/{id}/profile` endpoint that bundles all three server-side. Orchestrating three read calls for one screen is view composition (`04-architecture.md`: frontend responsibilities include "data visualization," not business logic), not business logic living in the frontend — and keeps every one of these three endpoints reusable elsewhere (QF-1 and QF-2 are each used by several other screens already). The same pattern applies to Reports → Session History's row click-through (QF-7 composes with QF-1 the same way). All three are now real (`10-api-design.md`'s own `scope.type = "OPPONENT"` and `opponentPlayerId` notes above), so the Player Profile screen is the first full worked example of this pattern actually built, not just designed.

---

### `GET /api/v1/sessions` — QF-6, Session List Query

Query params: Global Filters, date range, `page`, `pageSize`, `sort`, `sortDir`. Response: paginated `{ sessionId, startedAt, endedAt, rooms, handCount, pnl }` rows (`rooms`/`handCount` computed via the `session_hands` join, per `09-data-model.md`'s note that they're not stored redundantly).

### `GET /api/v1/sessions/{sessionId}` — QF-7, Session Detail Query

Response: session metadata (start/end, duration, rooms, stakes). Composes with `POST /stats/query` (`scope: {type:"CURRENT_SESSION"}` generalized to `{type:"SESSION", sessionId}` for a *closed* session — see Open Questions) the same way Player Detail does.

### `GET /api/v1/filters/{facet}` — QF-8, Filter Facet / Distinct-Values Query

`facet` ∈ `room | stakes | game-type`. Response: `{ "values": ["POKERSTARS", "WINAMAX"] }`. No pagination — per `06-query-catalog.md`, this gates rendering a filter dropdown (Interactive latency tier), and the distinct-value count for any one facet at this project's scale is small.

### `GET /api/v1/saved-views` and `GET /api/v1/saved-views/{name}` — QF-9, Saved View Query

List: `{ "views": [{ "name": "6-max BTN opens", "updatedAt": "..." }] }` (names + metadata only, not full contents — keeps the Statistics screen's "load a view" picker cheap to render). Load: the full `{ name, statSet, breakdown, filters }`, ready to feed straight into `POST /stats/query`.

### `GET /api/v1/system/meta` — QF-10, System / Meta Query

Response: `{ "databaseSizeBytes": 812345600, "lastImportAt": "...", "lastImportRoom": "WINAMAX" }`.

---

## Live Session

Not a query family — the Live Session module needs both a status read and two commands (Start/End session), and per `decisions/ADR-010-live-session-detection.md` point 5, deliberately does **not** reuse `POST /stats/query`'s `scope: {type: "CURRENT_SESSION"}` (which `06-query-catalog.md`'s Screen → Queries Used table originally envisioned for this screen) — that would mean threading a hand-id-set restriction through `CashStatsCalculator`'s existing query blocks for four stats this screen alone needs. Grouped here as its own small surface instead.

### `GET /api/v1/live-session`

Response: `{ "active": true, "sessionId": 2, "startedAt": "2026-09-23 02:35:36.150905", "hands": 1, "vpip": 100.0, "pfr": 0.0, "threeBet": null, "pnl": {"amount":"0.3000","currency":"€"}, "evPnl": null }`. `active: false` means every other field is `null`/zero. `vpip`/`pfr`/`threeBet` are `null` on zero sample (same convention as `POST /stats/query`'s rate stats), not zero. `evPnl` is always `null` today — All-in Adjusted P&L needs the hand-equity engine `07-analytics-engine.md` already flags as undesigned. `startedAt` is DuckDB's own timestamp text, not strict ISO-8601 (a deviation from this document's own Data Types rule, consistent with every other endpoint's `*Raw`/passthrough timestamp fields until `hand_timestamp` normalization lands project-wide).

### `POST /api/v1/live-session/start`

No body. Starts a session if none is open (idempotent — returns the already-open session's status if one exists). Same response shape as the `GET`.

### `POST /api/v1/live-session/stop`

No body. Ends the open session if one exists (idempotent — a no-op returning `active: false` if none is open). Same response shape as the `GET`.

---

## Write Endpoints (Commands)

Not covered by any query family — `06-query-catalog.md` explicitly scoped those out ("writes/commands... left out on purpose"). Enumerated here from each module's Navigation/Actions section in `05-product-map.md`.

### Imports

- `POST /api/v1/imports` — body `{ "filePaths": ["C:\\...\\hand-history.txt"] }`. The frontend uses Tauri's native file-picker to get local paths and sends **paths, not file contents** — both processes run on the same machine with full filesystem access, so there's no reason to stream file bytes over the loopback socket.
- **Asynchronous by design**: returns `202 Accepted` with `{ "importId": 501, "status": "PENDING" }` immediately, rather than blocking until parsing finishes. A bulk historical import (years of hands, per the architecture goal of supporting millions of hands) can run well past any of `06-query-catalog.md`'s latency tiers, all of which assume a bounded interactive action — an import is not one. The frontend polls (or a future version subscribes to) `GET /api/v1/imports/{importId}` for `{ status: "PENDING"|"RUNNING"|"SUCCESS"|"PARTIAL"|"FAILED", handCount, unparsedLineCount }`, mirroring `09-data-model.md`'s `import_history` table directly.

### Hand Tags

- `POST /api/v1/hands/{handId}/tags` — body `{ "tag": "review-later" }`.
- `DELETE /api/v1/hands/{handId}/tags/{tag}`.

### Player Notes & Tags

- `PUT /api/v1/players/{playerId}/notes` — body `{ "freeText": "..." }` (replace, matching `player_notes`' one-row-per-player shape in `09-data-model.md`).
- `POST /api/v1/players/{playerId}/tags` — body `{ "tag": "Nit" }`, `201`; `DELETE /api/v1/players/{playerId}/tags/{tag}`.

**Implemented** (`backend/src/main/java/com/pokeranalytics/api/PlayersController.java`) — all three, `404 PLAYER_NOT_FOUND` if `playerId` isn't a real opponent. The predefined tag palette shown in the Players Profile's Notes panel (Regular/Nit/Maniac/Calling station/Folds to 3-bet/Aggressive river) is sourced from the approved `Players.dc.html` mockup itself — `05-product-map.md` explicitly left "the exact predefined tag set" for later, and this is that later decision, made in the same change as the frontend that needed it. A custom tag typed into that panel's "+ New tag" field hits this same endpoint with no special casing — `player_tags` has no predefined-vs-custom distinction.

### Saved Views

- `POST /api/v1/saved-views` — body `{ name, statSet, breakdown, filters }`; `409` if `name` already exists.
- `PUT /api/v1/saved-views/{name}` — replace an existing view's contents.
- `DELETE /api/v1/saved-views/{name}`.

### Live Session

- `POST /api/v1/live-session/end` — finalizes the in-progress session's boundary immediately (per `07-analytics-engine.md`'s Sessions definition: "ends... or ends immediately if the user manually ends the Live Session"), rather than waiting for the idle-threshold gap to elapse. No body.

### Reports

- `POST /api/v1/reports/export` — body `{ "source": { "type": "TEMPLATE", "template": "..." } | { "type": "SAVED_VIEW", "name": "..." }, "format": "PDF" | "CSV", "dateRange": {...}, "filters": {...} }`. Response is the file itself (`Content-Type: application/pdf` or `text/csv`, `Content-Disposition: attachment`), synchronous — `06-query-catalog.md` already tiers report generation as "Background-acceptable" (<3s), well short of needing the async job pattern imports use. `template`'s valid values aren't enumerated here since `05-product-map.md` itself hasn't finalized the predefined template set yet — see Open Questions.

---

## Not Yet Designed

- **Subscription/entitlement checks** (`03-product-vision.md`'s Monetization Model) are out of scope for this document as written: the validation cadence, grace-period length, and even whether it's a backend-to-cloud call or something the frontend triggers directly are all still marked TBD upstream, and inventing a concrete contract now would mean guessing at a decision `03-product-vision.md` explicitly hasn't made yet. Whatever shape it takes, it's additive to this document (new endpoints/headers), not a change to anything specified above.
- **Report template contents** — `POST /api/v1/reports/export`'s `template` field stays an opaque string until `05-product-map.md`'s Reports section (screen design, 3.2) names the actual predefined set.

---

## Open Questions

- **Closed-session scope for `POST /stats/query`.** `06-query-catalog.md`'s QF-1 scope enum (`Hero | Opponent(playerId) | CurrentSession | AllOpponents`) has no entry for "a specific, already-closed session" — needed for Session Detail's composition (QF-7 note: "composite... Aggregate Stat Query, scope=Session(sessionId)"). This document assumes a `SESSION` scope variant with a `sessionId` parameter, parallel to `CURRENT_SESSION`; should be reconciled back into `06-query-catalog.md`'s QF-1 definition rather than silently living only here.
- **Cross-currency aggregation behavior** depends on the still-unresolved exchange-rate source (`07-analytics-engine.md`'s Open Questions). The `warnings`-array mechanism above is designed to degrade gracefully once that's resolved (a resolved rate turns a `warning` into a real value; nothing about the response shape needs to change), but the actual conversion isn't implemented by this design.
- **Import progress granularity** — `GET /api/v1/imports/{importId}` is specified with a coarse status enum; whether the frontend needs finer progress (e.g. "hand 4,200 of 10,000") for a large bulk import's UI is a screen-design question (`12-ui-designs.md`), not resolved here.
