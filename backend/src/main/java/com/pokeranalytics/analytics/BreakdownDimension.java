package com.pokeranalytics.analytics;

/**
 * Grouping dimensions for docs/06-query-catalog.md's QF-1 Aggregate Stat Query — the MVP set from
 * docs/13-statistics-module.md's Breakdown Dimensions (Hand Details: Position, Win/Loss, Showdown;
 * Game Details: Poker Room, Game Type). Each value's {@link #keySql()} is a per-hand "breakdown
 * key" subquery — exactly one row per hand this player was dealt into, keyed by {@code hand_id},
 * with a {@code breakdown_key} column — that every stat query in {@link CashStatsCalculator} joins
 * against and groups by. {@link #NONE} is the "no breakdown" case (a single summary row,
 * docs/06-query-catalog.md: "breakdown — zero or more grouping dimensions... none (one summary
 * row)"), implemented the same way as every other dimension rather than as a separate code path.
 *
 * <p>Not yet implemented, both requiring work this slice didn't do: {@code STAKES} (no bucketing
 * rule defined yet, and the real imported data so far has only one stakes level to test against)
 * and {@code DATE_BUCKET} (blocked on {@code hands.hand_timestamp} UTC normalization, still an open
 * question per docs/09-data-model.md).
 */
public enum BreakdownDimension {

    NONE("""
            SELECT hand_id, 'ALL' AS breakdown_key
            FROM seats WHERE player_id = :playerId
            """),

    POSITION("""
            SELECT hand_id, CAST(position AS VARCHAR) AS breakdown_key
            FROM seats WHERE player_id = :playerId
            """),

    /** Derived from {@code hand_net_results}, not {@code results.amount_won} alone, since a won
     *  pot can still be a net loss overall once what was put in is accounted for. */
    WIN_LOSS("""
            SELECT hand_id,
                   CASE WHEN net_result > 0 THEN 'WON' WHEN net_result < 0 THEN 'LOST' ELSE 'CHOP' END AS breakdown_key
            FROM hand_net_results WHERE player_id = :playerId
            """),

    SHOWDOWN("""
            SELECT hand_id, CASE WHEN went_to_showdown THEN 'Y' ELSE 'N' END AS breakdown_key
            FROM results WHERE player_id = :playerId
            """),

    ROOM("""
            SELECT s.hand_id, CAST(h.room AS VARCHAR) AS breakdown_key
            FROM seats s JOIN hands h ON h.hand_id = s.hand_id
            WHERE s.player_id = :playerId
            """),

    GAME_TYPE("""
            SELECT s.hand_id, CAST(h.game_family AS VARCHAR) AS breakdown_key
            FROM seats s JOIN hands h ON h.hand_id = s.hand_id
            WHERE s.player_id = :playerId
            """);

    private final String keySql;

    BreakdownDimension(String keySql) {
        this.keySql = keySql;
    }

    /** A {@code (hand_id, breakdown_key)} subquery, one row per hand this player was dealt into. */
    public String keySql() {
        return keySql;
    }
}
