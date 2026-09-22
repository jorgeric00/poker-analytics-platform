-- Analytics database schema, per docs/09-data-model.md.
-- Raw hand-history Facts + the one derived concept worth storing (Sessions) — everything else
-- (Opportunities/Events/Metrics/Aggregations, docs/07-analytics-engine.md) stays query-time SQL,
-- per docs/08-storage-strategy.md.

-- === Enum Types ===================================================================

CREATE TYPE room_t        AS ENUM ('POKERSTARS','WINAMAX','PARTYPOKER','POKER888','IPOKER','WINNINGPOKER');
CREATE TYPE hand_format_t AS ENUM ('CASH','TOURNAMENT','EXPRESSO');
CREATE TYPE game_family_t AS ENUM ('HOLDEM','OMAHA','OMAHA_HILO');
CREATE TYPE bet_limit_t   AS ENUM ('NO_LIMIT','POT_LIMIT','FIXED_LIMIT','CAP');
CREATE TYPE street_t      AS ENUM ('PREFLOP','FLOP','TURN','RIVER','SHOWDOWN');
CREATE TYPE action_type_t AS ENUM (
    'POST_ANTE','POST_SMALL_BLIND','POST_BIG_BLIND','POST_SMALL_AND_BIG_BLIND','POST_DEAD_BLIND',
    'FOLD','CHECK','CALL','BET','RAISE','ALL_IN','UNCALLED_RETURN',
    'COLLECTED','COLLECTED_MAIN_POT','COLLECTED_SIDE_POT',
    'DOESNT_SHOW','MUCKS','SHOWS','CONNECTION_STATUS','WINS_BOUNTY','TOURNAMENT_FINISH','POSTS','STRADDLE'
);
-- Provisional 9-max list, see docs/09-data-model.md Open Questions (PT4 Position list not yet sourced).
CREATE TYPE position_t AS ENUM ('BTN','SB','BB','UTG','UTG1','UTG2','LJ','HJ','CO');

-- === Sequences =====================================================================

CREATE SEQUENCE seq_player_id  START 1;
CREATE SEQUENCE seq_hand_id    START 1;
CREATE SEQUENCE seq_action_id  START 1;
CREATE SEQUENCE seq_session_id START 1;

-- === players =======================================================================
-- Hero is one reserved row (is_hero = TRUE, nickname = NULL), resolved at import time from
-- ParsedHand.heroName — NOT a nickname match, since the same person's site nickname differs per
-- room. Every other row is an opponent, keyed by nickname (aggregated across rooms).

CREATE TABLE players (
    player_id     BIGINT PRIMARY KEY DEFAULT nextval('seq_player_id'),
    nickname      TEXT UNIQUE,
    is_hero       BOOLEAN NOT NULL DEFAULT FALSE,
    first_seen_at TIMESTAMP,
    last_seen_at  TIMESTAMP
);

-- === hands =========================================================================

CREATE TABLE hands (
    hand_id            BIGINT PRIMARY KEY DEFAULT nextval('seq_hand_id'),
    room               room_t NOT NULL,
    external_hand_id   TEXT NOT NULL,
    format             hand_format_t NOT NULL,
    game_family        game_family_t NOT NULL,
    bet_limit          bet_limit_t NOT NULL,
    game_type_raw      TEXT NOT NULL,
    currency           TEXT NOT NULL,
    small_blind        DECIMAL(18,4) NOT NULL,
    big_blind          DECIMAL(18,4) NOT NULL,
    ante               DECIMAL(18,4),
    table_name         TEXT,
    max_seats          SMALLINT NOT NULL,
    button_seat        SMALLINT NOT NULL,
    tournament_id      TEXT,
    tournament_level   INTEGER,
    hand_timestamp_raw TEXT NOT NULL,
    hand_timestamp     TIMESTAMP,
    run_it_twice       BOOLEAN NOT NULL DEFAULT FALSE,
    total_pot          DECIMAL(18,4),
    rake               DECIMAL(18,4),
    main_pot           DECIMAL(18,4),
    imported_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (room, external_hand_id)
);

-- === seats =========================================================================

CREATE TABLE seats (
    hand_id        BIGINT NOT NULL REFERENCES hands(hand_id),
    seat_number    SMALLINT NOT NULL,
    player_id      BIGINT NOT NULL REFERENCES players(player_id),
    starting_stack DECIMAL(18,4) NOT NULL,
    stack_currency TEXT NOT NULL,
    bounty         DECIMAL(18,4),
    position       position_t NOT NULL,
    effective_bb   DECIMAL(18,2) NOT NULL,
    PRIMARY KEY (hand_id, seat_number)
);

-- === actions =======================================================================
-- Real player_id/amount columns — the concrete fix for the gap docs/07-analytics-engine.md and
-- docs/08-storage-strategy.md both flagged against today's free-text-only HandAction.rawLine.

CREATE TABLE actions (
    action_id   BIGINT PRIMARY KEY DEFAULT nextval('seq_action_id'),
    hand_id     BIGINT NOT NULL REFERENCES hands(hand_id),
    player_id   BIGINT NOT NULL REFERENCES players(player_id),
    street      street_t NOT NULL,
    action_type action_type_t NOT NULL,
    amount      DECIMAL(18,4),
    sequence_no INTEGER NOT NULL,
    raw_line    TEXT NOT NULL,
    UNIQUE (hand_id, sequence_no)
);

-- === boards ========================================================================

CREATE TABLE boards (
    hand_id   BIGINT NOT NULL REFERENCES hands(hand_id),
    run_index SMALLINT NOT NULL,
    street    street_t NOT NULL,
    cards     TEXT NOT NULL,
    PRIMARY KEY (hand_id, run_index, street)
);

-- === results =======================================================================

CREATE TABLE results (
    hand_id          BIGINT NOT NULL REFERENCES hands(hand_id),
    player_id        BIGINT NOT NULL REFERENCES players(player_id),
    went_to_showdown BOOLEAN NOT NULL,
    cards_shown      TEXT,
    amount_won       DECIMAL(18,4) NOT NULL DEFAULT 0,
    outcome_raw      TEXT,
    hero_rake_share  DECIMAL(18,4),
    PRIMARY KEY (hand_id, player_id)
);

-- === hand_side_pots ================================================================
-- Traceability only (completes "reproducible from raw hand histories" for multi-way-all-in
-- hands) — no MVP query family reads this directly.

CREATE TABLE hand_side_pots (
    hand_id        BIGINT NOT NULL REFERENCES hands(hand_id),
    side_pot_index SMALLINT NOT NULL,
    amount         DECIMAL(18,4) NOT NULL,
    PRIMARY KEY (hand_id, side_pot_index)
);

-- === tournament_results ============================================================
-- Hero only, per docs/decisions/ADR-008-tournament-summary-parser.md.

CREATE TABLE tournament_results (
    tournament_id       TEXT NOT NULL,
    player_id           BIGINT NOT NULL REFERENCES players(player_id),
    tournament_name     TEXT NOT NULL,
    title_suffix        TEXT,
    buy_in_raw          TEXT NOT NULL,
    buy_in_total        DECIMAL(18,4) NOT NULL,
    registered_players  INTEGER,
    mode                TEXT,
    type                TEXT,
    speed               TEXT,
    flight_id           INTEGER,
    levels_raw          TEXT,
    prizepool           DECIMAL(18,4),
    started_at_raw      TEXT,
    played_duration_raw TEXT,
    finish_place        INTEGER,
    amount_won          DECIMAL(18,4),
    bounty_won          DECIMAL(18,4),
    PRIMARY KEY (tournament_id, player_id)
);
-- hands.tournament_id -> tournament_results.tournament_id is a loose value reference, not an
-- enforced FK: tournament_results' key is composite (tournament_id, player_id) and a hand has
-- many players, so it can't target a single-column unique/primary key. Enforced by the Import
-- Engine instead. See docs/09-data-model.md Relationships.

-- === sessions / session_hands ======================================================
-- session_hands records exact membership explicitly rather than implying it from a time range.
-- Room(s)/hand-count are deliberately not stored on sessions — one join away via session_hands.

CREATE TABLE sessions (
    session_id BIGINT PRIMARY KEY DEFAULT nextval('seq_session_id'),
    started_at TIMESTAMP NOT NULL,
    ended_at   TIMESTAMP NOT NULL
);

CREATE TABLE session_hands (
    session_id BIGINT NOT NULL REFERENCES sessions(session_id),
    hand_id    BIGINT NOT NULL REFERENCES hands(hand_id),
    PRIMARY KEY (session_id, hand_id),
    UNIQUE (hand_id)
);
