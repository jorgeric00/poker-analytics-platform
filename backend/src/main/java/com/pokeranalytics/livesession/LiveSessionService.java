package com.pokeranalytics.livesession;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Session lifecycle per docs/decisions/ADR-010-live-session-detection.md: explicit {@link
 * #start()}/{@link #stop()}, auto-start from {@link #onNewHandsDetected} (called only by {@link
 * LiveSessionWatcherService} — never by the manual {@code POST /api/v1/imports} path, keeping the
 * historical/batch import path and the live path cleanly separated), and an idle-timeout sweep
 * that auto-closes a stale open session. At most one session is ever open at a time
 * ({@code sessions.ended_at IS NULL}, unique by construction since {@link #start()} is a no-op
 * when one is already open).
 *
 * <p>{@code lastActivityAt} is in-memory only, not a DB column — a backend restart mid-session
 * resets the idle clock to "now" rather than corrupting anything; a real, accepted limitation
 * (see the ADR's Consequences). Deliberately not initialized eagerly at construction/startup
 * (no {@code @PostConstruct} DB read here): Flyway migrations run via {@code SchemaMigrationRunner},
 * an {@code ApplicationRunner} that only executes once the whole application context has already
 * finished starting, so any bean-construction-time query would race it and fail against a
 * not-yet-migrated database. {@link #idleTimeoutSweep()} lazily initializes the clock itself
 * instead, since {@code @Scheduled} tasks only ever fire after startup has fully completed.
 */
@Service
public class LiveSessionService {

    private static final Logger log = LoggerFactory.getLogger(LiveSessionService.class);

    private final NamedParameterJdbcTemplate duckDb;
    private final LiveSessionProperties properties;
    private volatile Instant lastActivityAt;

    public LiveSessionService(@Qualifier("duckdb") NamedParameterJdbcTemplate duckDb, LiveSessionProperties properties) {
        this.duckDb = duckDb;
        this.properties = properties;
    }

    public synchronized long start() {
        Optional<Long> open = openSessionId();
        if (open.isPresent()) {
            return open.get();
        }
        long id = duckDb.queryForObject("SELECT nextval('seq_session_id')", Map.of(), Long.class);
        duckDb.update("INSERT INTO sessions (session_id, started_at, ended_at) VALUES (:id, CURRENT_TIMESTAMP, NULL)",
                new MapSqlParameterSource("id", id));
        lastActivityAt = Instant.now();
        return id;
    }

    public synchronized void stop() {
        openSessionId().ifPresent(this::closeSession);
    }

    /** Only ever called by {@link LiveSessionWatcherService} for hand ids it has determined are
     *  genuinely new since watching started (ADR-010, point 2). */
    public synchronized void onNewHandsDetected(List<Long> handIds) {
        if (handIds.isEmpty()) {
            return;
        }
        long sessionId = openSessionId().orElseGet(this::start);
        for (Long handId : handIds) {
            List<Long> existing = duckDb.queryForList(
                    "SELECT session_id FROM session_hands WHERE hand_id = :h",
                    new MapSqlParameterSource("h", handId), Long.class);
            if (existing.isEmpty()) {
                duckDb.update("INSERT INTO session_hands (session_id, hand_id) VALUES (:s, :h)",
                        new MapSqlParameterSource("s", sessionId).addValue("h", handId));
            }
        }
        lastActivityAt = Instant.now();
    }

    // Interval is placeholder-configurable (default unchanged, 60s) purely so tests can shrink it
    // to make the idle-timeout sweep observable in a bounded time instead of waiting a real minute.
    @Scheduled(fixedDelayString = "${app.live-session.idle-sweep-interval-ms:60000}")
    synchronized void idleTimeoutSweep() {
        // Best-effort: a failed tick (e.g. the DB is momentarily unavailable, or this fires in the
        // narrow window a context is shutting down — observed in rapid-fire test teardown) just
        // means this one check didn't happen; the next tick a minute later tries again. Letting an
        // exception escape here would otherwise surface as an ERROR-level stack trace from Spring's
        // scheduler for something that isn't actually a problem.
        try {
            sweep();
        } catch (RuntimeException e) {
            log.debug("Idle-timeout sweep failed, will retry next tick", e);
        }
    }

    private void sweep() {
        // Deliberately NOT gated on properties.enabled() (whether file watching is configured) —
        // a manually-started session (LiveSessionManualLifecycleTest) can go idle too, and the
        // idle-timeout rule applies to any open session regardless of how it was started.
        Optional<Long> open = openSessionId();
        if (open.isEmpty()) {
            return;
        }
        if (lastActivityAt == null) {
            // a session was already open when this process started — lazily start its clock now
            // rather than at bean-construction time (see class doc: migrations aren't done yet then)
            lastActivityAt = Instant.now();
            return;
        }
        if (Duration.between(lastActivityAt, Instant.now()).toMinutes() >= properties.idleTimeoutMinutes()) {
            closeSession(open.get());
        }
    }

    public Optional<Long> openSessionId() {
        return duckDb.queryForList("SELECT session_id FROM sessions WHERE ended_at IS NULL LIMIT 1", Map.of())
                .stream().findFirst().map(r -> ((Number) r.get("session_id")).longValue());
    }

    public String startedAt(long sessionId) {
        return String.valueOf(duckDb.queryForList(
                        "SELECT started_at FROM sessions WHERE session_id = :id",
                        new MapSqlParameterSource("id", sessionId))
                .stream().findFirst().map(r -> r.get("started_at")).orElse(null));
    }

    private void closeSession(long sessionId) {
        Instant endedAt = lastActivityAt != null ? lastActivityAt : Instant.now();
        duckDb.update("UPDATE sessions SET ended_at = :ended WHERE session_id = :id",
                new MapSqlParameterSource("id", sessionId).addValue("ended", Timestamp.from(endedAt)));
        lastActivityAt = null;
    }
}
