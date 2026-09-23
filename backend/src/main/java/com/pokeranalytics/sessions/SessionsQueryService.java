package com.pokeranalytics.sessions;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.pokeranalytics.api.dto.HandListResponse.StakesDto;
import com.pokeranalytics.api.dto.MoneyDto;
import com.pokeranalytics.api.dto.SessionDetailDto;
import com.pokeranalytics.api.dto.SessionListResponse;
import com.pokeranalytics.api.dto.SessionListResponse.SessionListItemDto;
import com.pokeranalytics.api.dto.SessionListResponse.SessionsSummaryDto;
import com.pokeranalytics.livesession.LiveSessionStats;

/**
 * docs/06-query-catalog.md's QF-6 (Session List Query) and QF-7 (Session Detail Query) — Hero-only,
 * scoped to whatever's actually in {@code sessions}/{@code session_hands}, which today means only
 * live-detected sessions (docs/decisions/ADR-010-live-session-detection.md); historical/backfilled
 * batch-imported hands are never assigned to a session (that grouping algorithm is still an open
 * question, docs/08-storage-strategy.md), so they simply don't appear here — not a bug, a real
 * current limitation tracked in docs/11-development-tracking.md.
 *
 * <p>Reuses {@link LiveSessionStats#compute(long)} for detail's VPIP/PFR/3-Bet/P&L rather than
 * re-deriving that SQL a third time — it already takes any session id, not just the currently open
 * one. The list itself stays one grouped query (no per-row VPIP/PFR needed there, so no reason to
 * call {@code LiveSessionStats} once per row the way {@code PlayersQueryService}'s quickStat does).
 */
@Service
public class SessionsQueryService {

    private final NamedParameterJdbcTemplate duckDb;
    private final LiveSessionStats liveSessionStats;

    public SessionsQueryService(@Qualifier("duckdb") NamedParameterJdbcTemplate duckDb, LiveSessionStats liveSessionStats) {
        this.duckDb = duckDb;
        this.liveSessionStats = liveSessionStats;
    }

    private static final String FROM_JOIN = """
            FROM sessions s
            JOIN session_hands sh ON sh.session_id = s.session_id
            JOIN hands h ON h.hand_id = sh.hand_id
            JOIN hand_net_results hnr ON hnr.hand_id = h.hand_id AND hnr.player_id = :heroId
            """;

    public SessionListResponse list(int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 200);
        Optional<Long> heroId = resolveHeroId();
        if (heroId.isEmpty()) {
            return new SessionListResponse(List.of(), 0, safePage, safePageSize,
                    new SessionsSummaryDto(0, null, 0, 0));
        }
        MapSqlParameterSource params = new MapSqlParameterSource("heroId", heroId.get());

        long total = duckDb.queryForList("SELECT count(DISTINCT s.session_id) AS c " + FROM_JOIN, params)
                .stream().findFirst().map(r -> ((Number) r.get("c")).longValue()).orElse(0L);

        params.addValue("limit", safePageSize).addValue("offset", (safePage - 1) * safePageSize);
        List<Map<String, Object>> rows = duckDb.queryForList("""
                SELECT s.session_id, s.started_at, s.ended_at, (s.ended_at IS NULL) AS active,
                       string_agg(DISTINCT CAST(h.room AS VARCHAR), ', ') AS rooms,
                       min(h.small_blind) AS small_blind, min(h.big_blind) AS big_blind, min(h.currency) AS currency,
                       count(DISTINCT h.table_name) AS tables,
                       count(DISTINCT h.hand_id) AS hands,
                       sum(hnr.net_result) AS pnl,
                       sum(hnr.net_result / h.big_blind) AS total_bb,
                       date_diff('minute', s.started_at, COALESCE(s.ended_at, CURRENT_TIMESTAMP)) AS duration_minutes
                """ + FROM_JOIN + """
                GROUP BY s.session_id, s.started_at, s.ended_at
                ORDER BY s.started_at DESC
                LIMIT :limit OFFSET :offset
                """, params);

        List<SessionListItemDto> items = rows.stream().map(this::toListItem).toList();

        Map<String, Object> summaryRow = duckDb.queryForList("""
                SELECT count(*) AS sessions, sum(x.pnl) AS total_pnl,
                       sum(x.hands) AS total_hands, sum(x.duration_minutes) AS total_minutes, max(x.currency) AS currency
                FROM (
                    SELECT s.session_id, count(DISTINCT h.hand_id) AS hands, sum(hnr.net_result) AS pnl,
                           min(h.currency) AS currency,
                           date_diff('minute', s.started_at, COALESCE(s.ended_at, CURRENT_TIMESTAMP)) AS duration_minutes
                """ + FROM_JOIN + """
                    GROUP BY s.session_id, s.started_at, s.ended_at
                ) x
                """, params).stream().findFirst().orElse(Map.of());
        SessionsSummaryDto summary = new SessionsSummaryDto(
                numberOrZero(summaryRow.get("sessions")),
                MoneyDto.of(toBigDecimal(summaryRow.get("total_pnl")), (String) summaryRow.get("currency")),
                numberOrZero(summaryRow.get("total_hands")),
                numberOrZero(summaryRow.get("total_minutes")));

        return new SessionListResponse(items, total, safePage, safePageSize, summary);
    }

    public Optional<SessionDetailDto> detail(long sessionId) {
        Optional<Long> heroId = resolveHeroId();
        if (heroId.isEmpty()) {
            return Optional.empty();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("heroId", heroId.get()).addValue("sessionId", sessionId);
        List<Map<String, Object>> rows = duckDb.queryForList("""
                SELECT s.session_id, s.started_at, s.ended_at, (s.ended_at IS NULL) AS active,
                       string_agg(DISTINCT CAST(h.room AS VARCHAR), ', ') AS rooms,
                       min(h.small_blind) AS small_blind, min(h.big_blind) AS big_blind, min(h.currency) AS currency,
                       count(DISTINCT h.table_name) AS tables,
                       count(DISTINCT h.hand_id) AS hands,
                       date_diff('minute', s.started_at, COALESCE(s.ended_at, CURRENT_TIMESTAMP)) AS duration_minutes,
                       sum(hnr.net_result / h.big_blind) AS total_bb,
                       max(hnr.net_result) FILTER (WHERE hnr.net_result > 0) AS biggest_pot_won
                """ + FROM_JOIN + """
                WHERE s.session_id = :sessionId
                GROUP BY s.session_id, s.started_at, s.ended_at
                """, params);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        String currency = (String) row.get("currency");

        LiveSessionStats.Snapshot snapshot = liveSessionStats.compute(sessionId);
        long hands = ((Number) row.get("hands")).longValue();
        Long durationMinutes = row.get("duration_minutes") == null ? null : ((Number) row.get("duration_minutes")).longValue();
        Double handsPerHour = (durationMinutes == null || durationMinutes == 0) ? null
                : hands / (durationMinutes / 60.0);

        return Optional.of(new SessionDetailDto(
                ((Number) row.get("session_id")).longValue(),
                String.valueOf(row.get("started_at")),
                row.get("ended_at") == null ? null : String.valueOf(row.get("ended_at")),
                (Boolean) row.get("active"),
                (String) row.get("rooms"),
                new StakesDto(
                        MoneyDto.of(toBigDecimal(row.get("small_blind")), currency),
                        MoneyDto.of(toBigDecimal(row.get("big_blind")), currency)),
                ((Number) row.get("tables")).intValue(),
                durationMinutes,
                hands,
                handsPerHour,
                snapshot.pnl(),
                bb100ForHands(toBigDecimal(row.get("total_bb")), hands),
                null, // evPnl — not implemented, see class doc
                snapshot.vpip(),
                snapshot.pfr(),
                snapshot.threeBet(),
                MoneyDto.of(toBigDecimal(row.get("biggest_pot_won")), currency),
                null // rakePaid — not implemented, see class doc
        ));
    }

    private SessionListItemDto toListItem(Map<String, Object> row) {
        String currency = (String) row.get("currency");
        long hands = ((Number) row.get("hands")).longValue();
        BigDecimal totalBb = toBigDecimal(row.get("total_bb"));
        return new SessionListItemDto(
                ((Number) row.get("session_id")).longValue(),
                String.valueOf(row.get("started_at")),
                row.get("ended_at") == null ? null : String.valueOf(row.get("ended_at")),
                (Boolean) row.get("active"),
                (String) row.get("rooms"),
                new StakesDto(
                        MoneyDto.of(toBigDecimal(row.get("small_blind")), currency),
                        MoneyDto.of(toBigDecimal(row.get("big_blind")), currency)),
                ((Number) row.get("tables")).intValue(),
                row.get("duration_minutes") == null ? null : ((Number) row.get("duration_minutes")).longValue(),
                hands,
                MoneyDto.of(toBigDecimal(row.get("pnl")), currency),
                bb100ForHands(totalBb, hands));
    }

    private static BigDecimal bb100ForHands(BigDecimal totalBb, long hands) {
        if (totalBb == null || hands == 0) {
            return null;
        }
        return totalBb.divide(BigDecimal.valueOf(hands), 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    private Optional<Long> resolveHeroId() {
        return duckDb.queryForList("SELECT player_id FROM players WHERE is_hero = TRUE LIMIT 1", Map.of())
                .stream().findFirst().map(r -> ((Number) r.get("player_id")).longValue());
    }

    private static long numberOrZero(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
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
