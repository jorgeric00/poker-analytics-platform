# Data Model

## Overview

This document defines **the database schema** — Phase 7 of `00-development-roadmap.md`: domain entities, tables, columns, relationships, keys, ordering, partitions and materialized views.

Question answered:

> How is information represented?

**Scope boundary:** `08-storage-strategy.md` already decided *what kind of thing* gets persisted (Facts as normalized DuckDB tables; Opportunities/Events/Metrics/Aggregations stay query-time SQL; Sessions are the one derived concept stored) and *why*. This document turns that into real DDL. Where storage strategy already made a call, this document doesn't re-argue it — it implements it.

Everything below is provisional in the same sense as the current `importing.model` classes (per `decisions/ADR-005-early-import-parser.md`): a first real schema, not a frozen one — schema migrations are expected as Phase 9 implementation surfaces gaps this design phase couldn't see (e.g. a room's edge case the parser hasn't hit yet).

---

## DuckDB — Analytics Database

### Enum Types

Per `08-storage-strategy.md`'s optimization guidance (typed enums over free text for compactness and filter/group speed):

```sql
CREATE TYPE room_t        AS ENUM ('POKERSTARS','WINAMAX','PARTYPOKER','POKER888','IPOKER','WINNINGPOKER');
CREATE TYPE hand_format_t AS ENUM ('CASH','TOURNAMENT','EXPRESSO');                          -- HandFormat
CREATE TYPE game_family_t AS ENUM ('HOLDEM','OMAHA','OMAHA_HILO');
CREATE TYPE bet_limit_t   AS ENUM ('NO_LIMIT','POT_LIMIT','FIXED_LIMIT','CAP');
CREATE TYPE street_t      AS ENUM ('PREFLOP','FLOP','TURN','RIVER','SHOWDOWN');               -- Street
CREATE TYPE action_type_t AS ENUM (                                                            -- HandActionType
    'POST_ANTE','POST_SMALL_BLIND','POST_BIG_BLIND','POST_SMALL_AND_BIG_BLIND','POST_DEAD_BLIND',
    'FOLD','CHECK','CALL','BET','RAISE','ALL_IN','UNCALLED_RETURN',
    'COLLECTED','COLLECTED_MAIN_POT','COLLECTED_SIDE_POT',
    'DOESNT_SHOW','MUCKS','SHOWS','CONNECTION_STATUS','WINS_BOUNTY','TOURNAMENT_FINISH','POSTS','STRADDLE'
);
CREATE TYPE position_t AS ENUM ('BTN','SB','BB','UTG','UTG1','UTG2','LJ','HJ','CO');
```

`game_family_t`/`bet_limit_t` and `position_t` are **normalized/classified values**, not copies of anything a parser emits directly today (`ParsedHand.gameType` is one free-text string per room; position isn't computed by the parser at all yet). Classifying raw text into these enums, and computing each seat's position, is Phase 9 Import Engine work — carried over from `07-analytics-engine.md`'s and `08-storage-strategy.md`'s open items, not solved by this document.

`position_t`'s exact value set is a **provisional 9-max list**, not yet reconciled against `13-statistics-module.md`'s own flagged gap (the full PT4 Position list isn't sourced yet — see that document's Research Status). Shorter-handed tables simply don't use the unused values (e.g. a 6-max hand never has `UTG2`/`LJ` rows). Revisit together once that gap is resolved.

### Sequences

```sql
CREATE SEQUENCE seq_player_id  START 1;
CREATE SEQUENCE seq_hand_id    START 1;
CREATE SEQUENCE seq_action_id  START 1;
CREATE SEQUENCE seq_session_id START 1;
```
DuckDB has no `AUTO_INCREMENT`/`IDENTITY` keyword; `DEFAULT nextval('seq_x')` is the idiomatic equivalent, used throughout below for surrogate keys.

### `players`

```sql
CREATE TABLE players (
    player_id     BIGINT PRIMARY KEY DEFAULT nextval('seq_player_id'),
    nickname      TEXT UNIQUE,           -- NULL only for the single Hero row, see below
    is_hero       BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen_at TIMESTAMP,
    last_seen_at  TIMESTAMP
);
```

**Hero is one reserved row, not a nickname match.** `05-product-map.md`'s nickname-aggregation-across-rooms rule (`06-query-catalog.md`'s Stated Assumption) is scoped to **opponents** — it can't identify Hero, because the same person plays under a different site nickname on each room. Instead, every parsed hand already states which seat is Hero directly (`ParsedHand.heroName`/`heroHoleCards`) — the Import Engine resolves that seat to the single `is_hero = TRUE` player row at import time, regardless of what nickname the room itself used. `nickname` is left `NULL` on that row (an arbitrary per-room nickname would be misleading shown as if it meant something across every room) — the frontend labels it "Hero" itself. Every other row is an opponent, keyed by nickname per the existing rule. Exactly one `is_hero = TRUE` row existing is an application-enforced invariant (DuckDB has no cross-row `CHECK`); acceptable risk at single-user scale.

### `hands`

```sql
CREATE TABLE hands (
    hand_id            BIGINT PRIMARY KEY DEFAULT nextval('seq_hand_id'),
    room               room_t NOT NULL,
    external_hand_id   TEXT NOT NULL,          -- the room's own hand id (ParsedHand.handId) — natural key
    format             hand_format_t NOT NULL,
    game_family        game_family_t NOT NULL, -- classified, see Enum Types note
    bet_limit          bet_limit_t NOT NULL,
    game_type_raw      TEXT NOT NULL,          -- room's own text, kept for traceability
    currency           TEXT NOT NULL,
    small_blind        DECIMAL(18,4) NOT NULL,
    big_blind          DECIMAL(18,4) NOT NULL,
    ante               DECIMAL(18,4),
    table_name         TEXT,
    max_seats          SMALLINT NOT NULL,
    button_seat        SMALLINT NOT NULL,
    tournament_id      TEXT,                   -- see Relationships: loose reference, not an enforced FK
    tournament_level   INTEGER,
    hand_timestamp_raw TEXT NOT NULL,
    hand_timestamp     TIMESTAMP,              -- normalized to UTC; nullable until Phase 9 timezone handling lands
    run_it_twice       BOOLEAN NOT NULL DEFAULT FALSE,
    total_pot          DECIMAL(18,4),
    rake               DECIMAL(18,4),
    main_pot           DECIMAL(18,4),
    imported_at        TIMESTAMP NOT NULL DEFAULT current_timestamp,
    UNIQUE (room, external_hand_id)
);
```

### `seats`

```sql
CREATE TABLE seats (
    hand_id        BIGINT NOT NULL REFERENCES hands(hand_id),
    seat_number    SMALLINT NOT NULL,
    player_id      BIGINT NOT NULL REFERENCES players(player_id),
    starting_stack DECIMAL(18,4) NOT NULL,
    stack_currency TEXT NOT NULL,       -- Seat.currency — chips in tournaments, real money in cash
    bounty         DECIMAL(18,4),       -- Seat.bounty, knockout tournaments only
    position       position_t NOT NULL, -- denormalized per 08-storage-strategy.md
    effective_bb   DECIMAL(18,2) NOT NULL, -- starting_stack ÷ hands.big_blind, denormalized
    PRIMARY KEY (hand_id, seat_number)
);
```

### `actions`

```sql
CREATE TABLE actions (
    action_id   BIGINT PRIMARY KEY DEFAULT nextval('seq_action_id'),
    hand_id     BIGINT NOT NULL REFERENCES hands(hand_id),
    player_id   BIGINT NOT NULL REFERENCES players(player_id),
    street      street_t NOT NULL,
    action_type action_type_t NOT NULL,
    amount      DECIMAL(18,4),          -- NULL for amount-less actions (FOLD, CHECK, MUCKS, SHOWS, ...)
    sequence_no INTEGER NOT NULL,       -- play order within the hand
    raw_line    TEXT NOT NULL,          -- traceability back to source text
    UNIQUE (hand_id, sequence_no)
);
```

Per-action `player_id` and `amount` as real columns are the concrete fix for the gap `07-analytics-engine.md` and `08-storage-strategy.md` both flagged: today's `HandAction` only carries `street`/`type`/`rawLine`, with the player and amount left inside free text. Populating these columns from that text is Phase 9's job; this schema is what it populates.

### `boards`

```sql
CREATE TABLE boards (
    hand_id   BIGINT NOT NULL REFERENCES hands(hand_id),
    run_index SMALLINT NOT NULL,   -- 0 = only/first run; 1 = second run when run_it_twice
    street    street_t NOT NULL,   -- FLOP, TURN or RIVER only
    cards     TEXT NOT NULL,       -- e.g. "Ah Kd 2c" — small fixed-shape text, not worth a card-per-row table
    PRIMARY KEY (hand_id, run_index, street)
);
```

### `results`

```sql
CREATE TABLE results (
    hand_id          BIGINT NOT NULL REFERENCES hands(hand_id),
    player_id        BIGINT NOT NULL REFERENCES players(player_id),
    went_to_showdown BOOLEAN NOT NULL,
    cards_shown      TEXT,                       -- NULL if mucked / never shown
    amount_won       DECIMAL(18,4) NOT NULL DEFAULT 0,
    outcome_raw      TEXT,                        -- SummarySeat.outcome free text, kept for traceability
    hero_rake_share  DECIMAL(18,4),               -- denormalized estimate, Hero's row only — see 07-analytics-engine.md
    PRIMARY KEY (hand_id, player_id)
);
```

### `hand_side_pots`

```sql
CREATE TABLE hand_side_pots (
    hand_id       BIGINT NOT NULL REFERENCES hands(hand_id),
    side_pot_index SMALLINT NOT NULL,  -- 1-based, room's own listed order (ParsedHand.sidePots)
    amount        DECIMAL(18,4) NOT NULL,
    PRIMARY KEY (hand_id, side_pot_index)
);
```

Traceability-only: keeps `total_pot`/`main_pot` fully decomposable back to the summary text (completing the "reproducible from raw hand histories" guarantee even for multi-way-all-in hands), but no MVP stat or query family reads it directly — recipient-level amounts already live in `results.amount_won` via the same summary text.

### `tournament_results`

```sql
CREATE TABLE tournament_results (
    tournament_id      TEXT NOT NULL,
    player_id          BIGINT NOT NULL REFERENCES players(player_id),  -- always Hero, per ADR-008's scope
    tournament_name    TEXT NOT NULL,
    title_suffix       TEXT,
    buy_in_raw         TEXT NOT NULL,
    buy_in_total       DECIMAL(18,4) NOT NULL,
    registered_players INTEGER,
    mode               TEXT,     -- room's own raw value, e.g. "tt" / "sng"
    type               TEXT,     -- room's own raw value, e.g. "knockout" / "sitngo"
    speed              TEXT,     -- room's own raw value, e.g. "normal" / "turbo"
    flight_id          INTEGER,
    levels_raw         TEXT,
    prizepool          DECIMAL(18,4),
    started_at_raw     TEXT,
    played_duration_raw TEXT,
    finish_place       INTEGER,
    amount_won         DECIMAL(18,4),
    bounty_won         DECIMAL(18,4),
    PRIMARY KEY (tournament_id, player_id)
);
```

Straight translation of `TournamentSummary` (per `decisions/ADR-008-tournament-summary-parser.md`), scoped to Hero only, as that ADR already establishes.

### `sessions` and `session_hands`

```sql
CREATE TABLE sessions (
    session_id BIGINT PRIMARY KEY DEFAULT nextval('seq_session_id'),
    started_at TIMESTAMP NOT NULL,
    ended_at   TIMESTAMP NOT NULL
);

CREATE TABLE session_hands (
    session_id BIGINT NOT NULL REFERENCES sessions(session_id),
    hand_id    BIGINT NOT NULL REFERENCES hands(hand_id) UNIQUE,  -- a hand belongs to at most one session
    PRIMARY KEY (session_id, hand_id)
);
```

`session_hands` records exact membership explicitly rather than implying it from a time range — more robust against `08-storage-strategy.md`'s incremental re-detection on out-of-order/backfilled imports, and it's what QF-7 (Session Detail) joins against. Room(s) played and hand count are deliberately **not** stored on `sessions` — both are one join/count away via `session_hands` → `hands`, and duplicating them here would be exactly the kind of derived-value drift `08-storage-strategy.md`'s Denormalization Rules argue against (unlike `position`/`effective_bb`, this isn't a value read by nearly every query, so the join cost doesn't justify it).

---

## Relationships

```
players ─┬─< seats
         ├─< actions
         ├─< results
         └─< tournament_results

hands ─┬─< seats
       ├─< actions
       ├─< boards
       ├─< results
       ├─< hand_side_pots
       └─< session_hands >─ sessions

hands.tournament_id ···· tournament_results.tournament_id   (loose, see below)
```

**`hands.tournament_id` is a value reference, not an enforced foreign key.** `tournament_results`' natural primary key is `(tournament_id, player_id)` — composite, and `hands` doesn't carry a `player_id` of its own (a hand has many seats/players). DuckDB foreign keys must reference a full unique/primary key on the target, so this link can't be declared as a real `FOREIGN KEY` constraint; it's enforced by the Import Engine instead (same category of trust as the cross-database `players.player_id` ↔ SQLite contract below, just within one database this time).

**No `ON DELETE CASCADE`.** DuckDB does not support cascading deletes on foreign keys. `08-storage-strategy.md`'s delete-then-reinsert reprocessing strategy must delete child rows explicitly, in dependency order, inside one transaction: `actions`, `boards`, `results`, `hand_side_pots`, `session_hands` (or re-run session detection for the affected range) before `seats`, before `hands`.

---

## Primary & Ordering Keys

| Table | Primary key | Natural/dedup key | Ordering key |
|---|---|---|---|
| `players` | `player_id` (surrogate) | `nickname` (opponents only) | — |
| `hands` | `hand_id` (surrogate) | `(room, external_hand_id)` | `hand_timestamp` |
| `seats` | `(hand_id, seat_number)` | — | inherits `hands` |
| `actions` | `action_id` (surrogate) | `(hand_id, sequence_no)` | `(hand_id, sequence_no)` |
| `boards` | `(hand_id, run_index, street)` | — | inherits `hands` |
| `results` | `(hand_id, player_id)` | — | inherits `hands` |
| `hand_side_pots` | `(hand_id, side_pot_index)` | — | inherits `hands` |
| `tournament_results` | `(tournament_id, player_id)` | — | `started_at_raw` |
| `sessions` | `session_id` (surrogate) | — | `started_at` |
| `session_hands` | `(session_id, hand_id)` | — | inherits `hands` |

"Ordering key" here means the column bulk imports should be physically sorted by before insert, per `08-storage-strategy.md`'s row-group pruning strategy — not a DuckDB schema clause (DuckDB has no persistent clustering-key syntax); enforcing it is an Import Engine concern (Phase 9).

---

## Partitions

Not applicable at this project's scale. DuckDB's real mechanism for range-pruning ("skip whole row groups that can't match a filter") is per-column min/max zonemaps combined with sorted insert order — already covered under Ordering Keys above and in `08-storage-strategy.md` — not table partitioning in the traditional sense. True file-level partitioning (e.g. one file per year) only becomes worth considering if a single embedded file ever stops being practical, which isn't expected at "a few million hands" (per `decisions/ADR-003-local-analytics-storage.md`). Stated explicitly, as the roadmap asks for this topic to be addressed, not because there's a decision being made here.

---

## Materialized Views

None in v1 — restates `08-storage-strategy.md`'s decision (Opportunities/Events/Metrics/Aggregations stay query-time SQL over the tables above) at the schema level: there is intentionally no `CREATE TABLE ... AS SELECT` materialization anywhere in this document. If a future benchmark (Phase 9) justifies one for a specific hot query, it gets added then, scoped to that query, per the "revisit trigger" already named in `08-storage-strategy.md` — not preemptively here.

---

## SQLite — Application Database

Per `04-architecture.md`/`decisions/ADR-003-local-analytics-storage.md`: preferences, config, UI state, import history, and (per `08-storage-strategy.md`'s Player Identity section) user-entered notes/tags. Every table here that references analytics data does so **by value** (e.g. `player_id`, `hand_id`), never by a real foreign key — SQLite and DuckDB are two separate files with no cross-engine constraint mechanism.

```sql
CREATE TABLE import_history (
    import_id            INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path            TEXT NOT NULL,
    room                 TEXT NOT NULL,
    imported_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hand_count           INTEGER NOT NULL,
    status                TEXT NOT NULL,   -- 'success' | 'partial' | 'failed'
    unparsed_line_count  INTEGER NOT NULL DEFAULT 0  -- mirrors ParsedHand.unparsedLines, the parser's own feasibility signal
);

CREATE TABLE import_unparsed_lines (
    import_id         INTEGER NOT NULL REFERENCES import_history(import_id),
    external_hand_id  TEXT,            -- NULL if the line couldn't even be attributed to a hand
    line_text         TEXT NOT NULL
);

CREATE TABLE player_notes (
    player_id  INTEGER PRIMARY KEY,     -- value-reference to DuckDB players.player_id
    free_text  TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE player_tags (
    player_id INTEGER NOT NULL,         -- value-reference to DuckDB players.player_id
    tag       TEXT NOT NULL,            -- from the predefined set in 05-product-map.md (Nit, Maniac, Calling Station, Regular, ...) — exact set still TBD there
    PRIMARY KEY (player_id, tag)
);

CREATE TABLE hand_tags (
    hand_id INTEGER NOT NULL,           -- value-reference to DuckDB hands.hand_id
    tag     TEXT NOT NULL,
    PRIMARY KEY (hand_id, tag)
);

CREATE TABLE saved_views (
    view_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL UNIQUE,
    stat_set   TEXT NOT NULL,   -- JSON array of stat identifiers (07-analytics-engine.md)
    breakdown  TEXT NOT NULL,   -- JSON array of breakdown dimensions (13-statistics-module.md)
    filters    TEXT NOT NULL,   -- JSON object: Global Filter Parameters + minimum sample size
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE preferences (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
```

`saved_views`' `stat_set`/`breakdown`/`filters` are stored as JSON text rather than normalized into their own tables: this is app configuration with a shape that will keep changing as `13-statistics-module.md`'s stat catalog and breakdown dimensions grow (group 3, Future Expansion filter categories, ...), and SQLite's JSON functions are sufficient for the only access pattern this data needs (load a view by name, list view names — QF-9). Normalizing it would add schema churn every time the stat catalog changes, for no query-performance benefit at this scale.

`preferences` is a generic key/value store rather than one column per setting, since `05-product-map.md`/`12-ui-designs.md` don't yet enumerate the exact preferences/UI state the app needs — avoids guessing a schema for requirements that don't exist yet. Revisit if a specific preference needs real structure (e.g. its own validation) rather than a JSON/scalar string.

---

## Open Questions

- `position_t`'s value set is provisional (9-max, standard convention) — not yet reconciled with `13-statistics-module.md`'s own flagged gap on sourcing PT4's exact Position list.
- `game_family_t`/`bet_limit_t` classification logic (turning each room's free-text game type into these enums) isn't designed here — Phase 9 Import Engine work, one rule per room's known text patterns.
- `hand_timestamp` normalization to UTC needs each room's own timestamp format/timezone convention worked out (e.g. PokerStars historically writes hand times in ET) — Phase 9, not this document.
- `player_tags`' exact predefined tag set and `saved_views`'s JSON shapes are only as concrete as `05-product-map.md`/`13-statistics-module.md` currently are — both documents already flag their own contents as refined later; this schema is deliberately loose (free `TEXT tag`, JSON blobs) so it doesn't need to change when those are finalized.
