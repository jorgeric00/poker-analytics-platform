-- Application database schema, per docs/09-data-model.md.
-- Preferences, config, import history, user-entered notes/tags. Every table that references
-- analytics data (DuckDB) does so by value (player_id, hand_id) — never a real foreign key,
-- since SQLite and DuckDB are two separate files with no cross-engine constraint mechanism.

CREATE TABLE import_history (
    import_id           INTEGER PRIMARY KEY AUTOINCREMENT,
    file_path            TEXT NOT NULL,
    room                 TEXT NOT NULL,
    imported_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hand_count           INTEGER NOT NULL,
    status                TEXT NOT NULL,
    unparsed_line_count  INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE import_unparsed_lines (
    import_id         INTEGER NOT NULL REFERENCES import_history(import_id),
    external_hand_id  TEXT,
    line_text         TEXT NOT NULL
);

CREATE TABLE player_notes (
    player_id  INTEGER PRIMARY KEY,
    free_text  TEXT NOT NULL DEFAULT '',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE player_tags (
    player_id INTEGER NOT NULL,
    tag       TEXT NOT NULL,
    PRIMARY KEY (player_id, tag)
);

CREATE TABLE hand_tags (
    hand_id INTEGER NOT NULL,
    tag     TEXT NOT NULL,
    PRIMARY KEY (hand_id, tag)
);

CREATE TABLE saved_views (
    view_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT NOT NULL UNIQUE,
    stat_set   TEXT NOT NULL,
    breakdown  TEXT NOT NULL,
    filters    TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE preferences (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
