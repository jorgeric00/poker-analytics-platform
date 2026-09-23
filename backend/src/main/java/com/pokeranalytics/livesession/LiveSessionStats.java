package com.pokeranalytics.livesession;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.pokeranalytics.api.dto.MoneyDto;

/**
 * Hero's own HANDS/VPIP/PFR/THREE_BET and real P&L for one currently-open session, scoped directly
 * to {@code session_hands} rather than reusing {@link com.pokeranalytics.analytics.CashStatsCalculator}
 * — a deliberate, documented deviation from docs/06-query-catalog.md's "QF-1, scope=CURRENT_SESSION"
 * framing, see docs/decisions/ADR-010-live-session-detection.md point 5. The Opportunity/Event SQL
 * shape for VPIP/PFR/THREE_BET is intentionally the same shape {@code CashStatsCalculator} already
 * uses, just filtered by session membership instead of grouped by a breakdown dimension.
 *
 * <p>P&L sums {@code hand_net_results.net_result} across the session's hands in whatever single
 * currency they're in — correct for a real live session (one stake, one currency in practice) but
 * not cross-currency-safe; an session spanning more than one currency would silently pick one.
 */
@Service
public class LiveSessionStats {

    private final NamedParameterJdbcTemplate duckDb;

    public LiveSessionStats(@Qualifier("duckdb") NamedParameterJdbcTemplate duckDb) {
        this.duckDb = duckDb;
    }

    public record Snapshot(long hands, BigDecimal vpip, BigDecimal pfr, BigDecimal threeBet, MoneyDto pnl) {
        static final Snapshot EMPTY = new Snapshot(0, null, null, null, null);
    }

    public Snapshot compute(long sessionId) {
        Optional<Long> heroId = resolveHeroId();
        if (heroId.isEmpty()) {
            return Snapshot.EMPTY;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("heroId", heroId.get()).addValue("sessionId", sessionId);

        long hands = duckDb.queryForList("""
                SELECT count(*) AS c FROM seats s
                JOIN session_hands sh ON sh.hand_id = s.hand_id AND sh.session_id = :sessionId
                WHERE s.player_id = :heroId
                """, params).stream().findFirst().map(r -> ((Number) r.get("c")).longValue()).orElse(0L);

        BigDecimal vpip = rate(params, """
                SELECT count(*) AS opportunities,
                    count(*) FILTER (WHERE EXISTS (
                        SELECT 1 FROM actions a WHERE a.hand_id = s.hand_id AND a.player_id = s.player_id
                          AND a.street = CAST('PREFLOP' AS street_t) AND a.action_type IN ('CALL','BET','RAISE')
                    )) AS events
                FROM seats s JOIN session_hands sh ON sh.hand_id = s.hand_id AND sh.session_id = :sessionId
                WHERE s.player_id = :heroId
                """);

        BigDecimal pfr = rate(params, """
                SELECT count(*) AS opportunities,
                    count(*) FILTER (WHERE EXISTS (
                        SELECT 1 FROM actions a WHERE a.hand_id = s.hand_id AND a.player_id = s.player_id
                          AND a.street = CAST('PREFLOP' AS street_t) AND a.action_type = CAST('RAISE' AS action_type_t)
                    )) AS events
                FROM seats s JOIN session_hands sh ON sh.hand_id = s.hand_id AND sh.session_id = :sessionId
                WHERE s.player_id = :heroId
                """);

        BigDecimal threeBet = rate(params, """
                WITH preflop_raise_counts AS (
                    SELECT hand_id, sequence_no,
                           sum(CASE WHEN action_type = CAST('RAISE' AS action_type_t) THEN 1 ELSE 0 END)
                               OVER (PARTITION BY hand_id ORDER BY sequence_no
                                     ROWS BETWEEN UNBOUNDED PRECEDING AND 1 PRECEDING) AS prior_raises
                    FROM actions WHERE street = CAST('PREFLOP' AS street_t)
                ),
                decisions AS (
                    SELECT a.hand_id, a.action_type, prc.prior_raises
                    FROM actions a
                    JOIN preflop_raise_counts prc ON prc.hand_id = a.hand_id AND prc.sequence_no = a.sequence_no
                    JOIN session_hands sh ON sh.hand_id = a.hand_id AND sh.session_id = :sessionId
                    WHERE a.player_id = :heroId AND a.street = CAST('PREFLOP' AS street_t)
                      AND a.action_type IN ('FOLD', 'CALL', 'RAISE')
                )
                SELECT count(*) FILTER (WHERE prior_raises = 1) AS opportunities,
                       count(*) FILTER (WHERE prior_raises = 1 AND action_type = 'RAISE') AS events
                FROM decisions
                """);

        MoneyDto pnl = duckDb.queryForList("""
                SELECT sum(hnr.net_result) AS total, max(h.currency) AS currency
                FROM hand_net_results hnr
                JOIN hands h ON h.hand_id = hnr.hand_id
                JOIN session_hands sh ON sh.hand_id = hnr.hand_id AND sh.session_id = :sessionId
                WHERE hnr.player_id = :heroId
                """, params).stream().findFirst()
                .map(r -> MoneyDto.of(toBigDecimal(r.get("total")), (String) r.get("currency")))
                .orElse(null);

        return new Snapshot(hands, vpip, pfr, threeBet, pnl);
    }

    private BigDecimal rate(MapSqlParameterSource params, String sql) {
        List<Map<String, Object>> rows = duckDb.queryForList(sql, params);
        if (rows.isEmpty()) {
            return null;
        }
        long opportunities = ((Number) rows.get(0).get("opportunities")).longValue();
        long events = ((Number) rows.get(0).get("events")).longValue();
        if (opportunities == 0) {
            return null;
        }
        return BigDecimal.valueOf(events).divide(BigDecimal.valueOf(opportunities), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private Optional<Long> resolveHeroId() {
        return duckDb.queryForList("SELECT player_id FROM players WHERE is_hero = TRUE LIMIT 1", Map.of())
                .stream().findFirst().map(r -> ((Number) r.get("player_id")).longValue());
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        return new BigDecimal(value.toString());
    }
}
