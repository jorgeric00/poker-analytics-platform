package com.pokeranalytics.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Computes the cash-applicable MVP stats (docs/07-analytics-engine.md's Stat Definitions table)
 * for one player, optionally grouped by a {@link BreakdownDimension} — docs/06-query-catalog.md's
 * QF-1 Aggregate Stat Query, scope always a single player so far (Hero or one opponent; the other
 * QF-1 scopes — {@code AllOpponents}, {@code CurrentSession}, {@code Session(sessionId)} — aren't
 * implemented yet). Tournament-only stats ({@link StatId#ROI}, {@link StatId#BUBBLE_FACTOR}) stay
 * out of scope — see {@link StatId}'s class doc.
 *
 * <p>Every stat follows the same shape docs/07-analytics-engine.md defines: an <b>Opportunity</b>
 * predicate (the denominator) and an <b>Event</b> predicate (the numerator), evaluated directly
 * against the Facts tables (docs/09-data-model.md) — no precomputed opportunity/event rows, per
 * docs/08-storage-strategy.md's decision to keep this layer query-time SQL. Breakdown grouping is
 * implemented uniformly: every query joins the same per-hand "breakdown key" subquery
 * ({@link BreakdownDimension#keySql()}) and groups by it — {@link BreakdownDimension#NONE} produces
 * the single-summary-row case the same way every other dimension produces its rows, rather than as
 * a separate code path.
 *
 * <p>Every query here uses {@code queryForList}, not the more usual {@code RowCallbackHandler}
 * style, deliberately: empirically, the DuckDB JDBC driver reliably corrupted its own {@code
 * ResultSet} state ({@code "No row in context"} / {@code ArrayIndexOutOfBoundsException} from
 * inside the driver) on some of this class's multi-CTE, window-function-heavy queries when driven
 * via {@code NamedParameterJdbcTemplate.query(sql, params, RowCallbackHandler)} — reproducibly, not
 * randomly, and unrelated to connection reuse or which query ran first (both ruled out while
 * diagnosing this). Switching to {@code queryForList} (backed by Spring's {@code
 * ColumnMapRowMapper}, a different internal code path through the driver) made it disappear across
 * every query this class runs, so it's used uniformly rather than kept as a special case for the
 * queries that happened to trigger it.
 */
@Service
public class CashStatsCalculator {

    private final NamedParameterJdbcTemplate duckDb;

    public CashStatsCalculator(@Qualifier("duckdb") NamedParameterJdbcTemplate duckDb) {
        this.duckDb = duckDb;
    }

    /** Convenience for the no-breakdown case — one summary row (docs/06-query-catalog.md's QF-1). */
    public CashStats compute(long playerId) {
        List<CashStatsRow> rows = computeBreakdown(playerId, BreakdownDimension.NONE, 0);
        return rows.isEmpty() ? EMPTY_STATS : rows.get(0).stats();
    }

    /**
     * @param minSampleSize breakdown groups with fewer hands than this are excluded outright, per
     *                       docs/06-query-catalog.md's resolved decision ({@code HAVING count >= N},
     *                       not a low-confidence flag) — 0 (or less) disables the filter
     */
    public List<CashStatsRow> computeBreakdown(long playerId, BreakdownDimension dimension, int minSampleSize) {
        MapSqlParameterSource params = new MapSqlParameterSource("playerId", playerId);
        String bk = dimension.keySql();

        Map<String, Long> handsByKey = new LinkedHashMap<>();
        Map<String, BigDecimal> stackByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : duckDb.queryForList("""
                WITH bk AS (%s)
                SELECT bk.breakdown_key AS k, count(*) AS hands, avg(s.effective_bb) AS stack_bb
                FROM seats s JOIN bk ON bk.hand_id = s.hand_id
                WHERE s.player_id = :playerId
                GROUP BY bk.breakdown_key
                """.formatted(bk), params)) {
            String key = (String) row.get("k");
            handsByKey.put(key, ((Number) row.get("hands")).longValue());
            stackByKey.put(key, toBigDecimal(row.get("stack_bb")));
        }

        Map<String, OpportunityEvent> vpipByKey = new LinkedHashMap<>();
        Map<String, OpportunityEvent> pfrByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : duckDb.queryForList("""
                WITH bk AS (%s)
                SELECT bk.breakdown_key AS k, count(*) AS opportunities,
                    count(*) FILTER (WHERE EXISTS (
                        SELECT 1 FROM actions a WHERE a.hand_id = s.hand_id AND a.player_id = s.player_id
                          AND a.street = CAST('PREFLOP' AS street_t) AND a.action_type IN ('CALL','BET','RAISE')
                    )) AS vpip_events,
                    count(*) FILTER (WHERE EXISTS (
                        SELECT 1 FROM actions a WHERE a.hand_id = s.hand_id AND a.player_id = s.player_id
                          AND a.street = CAST('PREFLOP' AS street_t) AND a.action_type = CAST('RAISE' AS action_type_t)
                    )) AS pfr_events
                FROM seats s JOIN bk ON bk.hand_id = s.hand_id
                WHERE s.player_id = :playerId
                GROUP BY bk.breakdown_key
                """.formatted(bk), params)) {
            String key = (String) row.get("k");
            long opportunities = ((Number) row.get("opportunities")).longValue();
            vpipByKey.put(key, new OpportunityEvent(opportunities, ((Number) row.get("vpip_events")).longValue()));
            pfrByKey.put(key, new OpportunityEvent(opportunities, ((Number) row.get("pfr_events")).longValue()));
        }

        Map<String, OpportunityEvent> threeBetByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : duckDb.queryForList("""
                WITH bk AS (%s),
                preflop_raise_counts AS (
                    -- number of PREFLOP raises strictly before this hand's every action, by sequence order
                    SELECT hand_id, sequence_no,
                           sum(CASE WHEN action_type = CAST('RAISE' AS action_type_t) THEN 1 ELSE 0 END)
                               OVER (PARTITION BY hand_id ORDER BY sequence_no
                                     ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_raises
                    FROM actions
                    WHERE street = CAST('PREFLOP' AS street_t)
                ),
                decisions AS (
                    SELECT a.hand_id, a.action_type, prc.prior_raises
                    FROM actions a
                    JOIN preflop_raise_counts prc ON prc.hand_id = a.hand_id AND prc.sequence_no = a.sequence_no
                    WHERE a.player_id = :playerId AND a.street = CAST('PREFLOP' AS street_t)
                      AND a.action_type IN ('FOLD', 'CALL', 'RAISE')
                )
                SELECT bk.breakdown_key AS k,
                    count(*) FILTER (WHERE d.prior_raises = 1) AS opportunities,
                    count(*) FILTER (WHERE d.prior_raises = 1 AND d.action_type = 'RAISE') AS events
                FROM decisions d JOIN bk ON bk.hand_id = d.hand_id
                GROUP BY bk.breakdown_key
                """.formatted(bk), params)) {
            threeBetByKey.put((String) row.get("k"), new OpportunityEvent(
                    ((Number) row.get("opportunities")).longValue(), ((Number) row.get("events")).longValue()));
        }

        Map<String, OpportunityEvent> flopCbetByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : duckDb.queryForList("""
                WITH bk AS (%s),
                last_preflop_raiser AS (
                    SELECT hand_id, player_id
                    FROM (
                        SELECT hand_id, player_id,
                               row_number() OVER (PARTITION BY hand_id ORDER BY sequence_no DESC) AS rn
                        FROM actions
                        WHERE street = CAST('PREFLOP' AS street_t) AND action_type = CAST('RAISE' AS action_type_t)
                    ) ranked
                    WHERE rn = 1
                ),
                reached_flop AS (
                    SELECT DISTINCT hand_id FROM boards WHERE street = CAST('FLOP' AS street_t)
                ),
                flop_first_actions AS (
                    SELECT hand_id, player_id, action_type,
                           row_number() OVER (PARTITION BY hand_id, player_id ORDER BY sequence_no) AS rn
                    FROM actions
                    WHERE street = CAST('FLOP' AS street_t)
                ),
                aggressor_first_flop_action AS (
                    SELECT lpr.hand_id, ffa.action_type
                    FROM last_preflop_raiser lpr
                    JOIN reached_flop rf ON rf.hand_id = lpr.hand_id
                    JOIN flop_first_actions ffa ON ffa.hand_id = lpr.hand_id
                        AND ffa.player_id = lpr.player_id AND ffa.rn = 1
                    WHERE lpr.player_id = :playerId
                )
                SELECT bk.breakdown_key AS k, count(*) AS opportunities,
                       count(*) FILTER (WHERE afa.action_type = 'BET') AS events
                FROM aggressor_first_flop_action afa JOIN bk ON bk.hand_id = afa.hand_id
                GROUP BY bk.breakdown_key
                """.formatted(bk), params)) {
            flopCbetByKey.put((String) row.get("k"), new OpportunityEvent(
                    ((Number) row.get("opportunities")).longValue(), ((Number) row.get("events")).longValue()));
        }

        Map<String, OpportunityEvent> foldVsCbetByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : duckDb.queryForList("""
                WITH bk AS (%s),
                last_preflop_raiser AS (
                    SELECT hand_id, player_id
                    FROM (
                        SELECT hand_id, player_id,
                               row_number() OVER (PARTITION BY hand_id ORDER BY sequence_no DESC) AS rn
                        FROM actions
                        WHERE street = CAST('PREFLOP' AS street_t) AND action_type = CAST('RAISE' AS action_type_t)
                    ) ranked
                    WHERE rn = 1
                ),
                flop_first_actions AS (
                    SELECT hand_id, player_id, action_type,
                           row_number() OVER (PARTITION BY hand_id, player_id ORDER BY sequence_no) AS rn,
                           sequence_no
                    FROM actions
                    WHERE street = CAST('FLOP' AS street_t)
                ),
                c_bets AS (
                    SELECT lpr.hand_id, lpr.player_id AS bettor_id, ffa.sequence_no AS bet_seq
                    FROM last_preflop_raiser lpr
                    JOIN flop_first_actions ffa ON ffa.hand_id = lpr.hand_id
                        AND ffa.player_id = lpr.player_id AND ffa.rn = 1
                        AND ffa.action_type = CAST('BET' AS action_type_t)
                ),
                facing_c_bet AS (
                    SELECT a.hand_id, a.action_type
                    FROM actions a
                    JOIN c_bets cb ON cb.hand_id = a.hand_id
                    WHERE a.street = CAST('FLOP' AS street_t)
                      AND a.player_id = :playerId
                      AND a.player_id != cb.bettor_id
                      AND a.sequence_no > cb.bet_seq
                      AND a.action_type IN ('FOLD', 'CALL', 'RAISE')
                )
                SELECT bk.breakdown_key AS k, count(*) AS opportunities,
                       count(*) FILTER (WHERE fcb.action_type = 'FOLD') AS events
                FROM facing_c_bet fcb JOIN bk ON bk.hand_id = fcb.hand_id
                GROUP BY bk.breakdown_key
                """.formatted(bk), params)) {
            foldVsCbetByKey.put((String) row.get("k"), new OpportunityEvent(
                    ((Number) row.get("opportunities")).longValue(), ((Number) row.get("events")).longValue()));
        }

        Map<String, Long> bb100HandsByKey = new LinkedHashMap<>();
        Map<String, BigDecimal> bb100TotalByKey = new LinkedHashMap<>();
        for (Map<String, Object> row : duckDb.queryForList("""
                WITH bk AS (%s)
                SELECT bk.breakdown_key AS k, count(*) AS hands,
                       sum(hnr.net_result / h.big_blind) AS total_bb
                FROM hand_net_results hnr
                JOIN hands h ON h.hand_id = hnr.hand_id
                JOIN bk ON bk.hand_id = hnr.hand_id
                WHERE hnr.player_id = :playerId AND h.format = CAST('CASH' AS hand_format_t)
                GROUP BY bk.breakdown_key
                """.formatted(bk), params)) {
            String key = (String) row.get("k");
            bb100HandsByKey.put(key, ((Number) row.get("hands")).longValue());
            bb100TotalByKey.put(key, toBigDecimal(row.get("total_bb")));
        }

        var keys = new TreeSet<>(handsByKey.keySet());
        return keys.stream()
                .map(key -> {
                    long hands = handsByKey.getOrDefault(key, 0L);
                    CashStats stats = new CashStats(
                            hands,
                            vpipByKey.getOrDefault(key, OpportunityEvent.ZERO).rate(),
                            pfrByKey.getOrDefault(key, OpportunityEvent.ZERO).rate(),
                            threeBetByKey.getOrDefault(key, OpportunityEvent.ZERO).rate(),
                            flopCbetByKey.getOrDefault(key, OpportunityEvent.ZERO).rate(),
                            foldVsCbetByKey.getOrDefault(key, OpportunityEvent.ZERO).rate(),
                            stackByKey.get(key),
                            bb100(bb100TotalByKey.get(key), bb100HandsByKey.getOrDefault(key, 0L)));
                    return new CashStatsRow(key, stats);
                })
                .filter(row -> minSampleSize <= 0 || row.stats().hands() >= minSampleSize)
                .toList();
    }

    /** DuckDB's {@code avg()}/decimal-division results come back as {@link Double} via
     *  {@code ColumnMapRowMapper}, not {@link BigDecimal} — normalize via the decimal string
     *  representation (not {@code BigDecimal.valueOf(double)}'s binary-double rounding) so a
     *  result already computed in exact decimal SQL arithmetic doesn't pick up float imprecision
     *  on the way into Java. */
    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }

    private static BigDecimal bb100(BigDecimal totalBb, long hands) {
        if (hands == 0) {
            return null;
        }
        BigDecimal total = totalBb == null ? BigDecimal.ZERO : totalBb;
        return total.divide(BigDecimal.valueOf(hands), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private static final CashStats EMPTY_STATS =
            new CashStats(0, null, null, null, null, null, null, null);

    private record OpportunityEvent(long opportunities, long events) {
        static final OpportunityEvent ZERO = new OpportunityEvent(0, 0);

        BigDecimal rate() {
            if (opportunities == 0) {
                return null;
            }
            return BigDecimal.valueOf(events).divide(BigDecimal.valueOf(opportunities), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }
    }
}
