-- Phase 9's Live Session module (docs/decisions/ADR-010-live-session-detection.md) needs to
-- represent a session that's still in progress, which the original schema couldn't: ended_at was
-- NOT NULL, so there was no way to insert a "started but not yet finished" sessions row.
-- NULL now means "still open" — the same convention the rest of this schema already uses for
-- "not known/not applicable yet" (e.g. hands.hand_timestamp).
--
-- Plain ALTER COLUMN ... DROP NOT NULL fails here with DuckDB's own "Dependency Error: Cannot
-- alter entry sessions because there are entries that depend on it" — session_hands.session_id's
-- REFERENCES sessions(session_id) constraint blocks any catalog alteration of the parent table,
-- the same category of DuckDB catalog/constraint limitation already documented in 09-data-model.md
-- for session_hands.hand_id (github.com/duckdb/duckdb #4309/#25388). Both tables have never had a
-- single row inserted before this migration (nothing wrote to sessions/session_hands until this
-- feature), so dropping and recreating both is a safe, zero-data-loss fix — not just an ALTER
-- workaround. session_id referential integrity is now enforced by LiveSessionService alone,
-- extending the same application-enforced-not-database-enforced pattern hand_id already uses here.
DROP TABLE session_hands;
DROP TABLE sessions;

CREATE TABLE sessions (
    session_id BIGINT PRIMARY KEY DEFAULT nextval('seq_session_id'),
    started_at TIMESTAMP NOT NULL,
    ended_at   TIMESTAMP -- NULL = still open
);

CREATE TABLE session_hands (
    session_id BIGINT NOT NULL,
    hand_id    BIGINT NOT NULL,
    PRIMARY KEY (session_id, hand_id),
    UNIQUE (hand_id)
);
