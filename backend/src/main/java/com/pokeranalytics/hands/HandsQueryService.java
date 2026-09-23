package com.pokeranalytics.hands;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.pokeranalytics.api.dto.HandDetailDto;
import com.pokeranalytics.api.dto.HandDetailDto.ActionDto;
import com.pokeranalytics.api.dto.HandDetailDto.BoardDto;
import com.pokeranalytics.api.dto.HandDetailDto.ResultDto;
import com.pokeranalytics.api.dto.HandDetailDto.SeatDto;
import com.pokeranalytics.api.dto.HandDetailDto.StakesDtoWithAnte;
import com.pokeranalytics.api.dto.HandListResponse;
import com.pokeranalytics.api.dto.HandListResponse.HandListItemDto;
import com.pokeranalytics.api.dto.HandListResponse.StakesDto;
import com.pokeranalytics.api.dto.MoneyDto;

/**
 * docs/06-query-catalog.md's QF-2 (Hand List Query) and QF-3 (Hand Detail Query), scoped to Hero
 * (the only scope any query family implements so far, per {@link
 * com.pokeranalytics.analytics.CashStatsCalculator}'s own note) and cash hands (the only format the
 * Import Engine persists today).
 *
 * <p>"Hand type" (docs/05-product-map.md: "e.g. 3-bet pot, limped pot") isn't a stored fact — it's
 * classified here from the count of PREFLOP raises, the same shape docs/07-analytics-engine.md's
 * 3-Bet% stat already uses: {@code WALKED_POT} (no raise, no call — folded to the blinds),
 * {@code LIMPED_POT} (no raise, at least one call), {@code RAISED_POT} (exactly one raise),
 * {@code THREE_BET_POT} (exactly two), {@code FOUR_BET_PLUS_POT} (three or more). This taxonomy is
 * a new, undocumented-until-now design decision, not sourced from any prior doc — recorded in
 * docs/05-product-map.md's Hands section in the same change that added this class.
 *
 * <p>Every query uses {@code queryForList}, never the {@code RowCallbackHandler} overload, per
 * {@link com.pokeranalytics.analytics.CashStatsCalculator}'s class doc on the DuckDB driver bug
 * that style reproducibly triggers on multi-CTE/window-function-heavy queries.
 */
@Service
public class HandsQueryService {

    private final NamedParameterJdbcTemplate duckDb;
    private final JdbcTemplate sqlite;

    public HandsQueryService(@Qualifier("duckdb") NamedParameterJdbcTemplate duckDb,
            @Qualifier("sqlite") JdbcTemplate sqlite) {
        this.duckDb = duckDb;
        this.sqlite = sqlite;
    }

    private static final String HAND_TYPE_SUBQUERY = """
            SELECT hand_id,
                CASE
                    WHEN count(*) FILTER (WHERE action_type = CAST('RAISE' AS action_type_t)) >= 3 THEN 'FOUR_BET_PLUS_POT'
                    WHEN count(*) FILTER (WHERE action_type = CAST('RAISE' AS action_type_t)) = 2 THEN 'THREE_BET_POT'
                    WHEN count(*) FILTER (WHERE action_type = CAST('RAISE' AS action_type_t)) = 1 THEN 'RAISED_POT'
                    WHEN count(*) FILTER (WHERE action_type = CAST('CALL' AS action_type_t)) > 0 THEN 'LIMPED_POT'
                    ELSE 'WALKED_POT'
                END AS hand_type
            FROM actions
            WHERE street = CAST('PREFLOP' AS street_t)
            GROUP BY hand_id
            """;

    private static final String ENDED_STREET_SUBQUERY = """
            SELECT hand_id,
                CASE
                    WHEN bool_or(street = CAST('RIVER' AS street_t)) THEN 'RIVER'
                    WHEN bool_or(street = CAST('TURN' AS street_t)) THEN 'TURN'
                    WHEN bool_or(street = CAST('FLOP' AS street_t)) THEN 'FLOP'
                    ELSE 'PREFLOP'
                END AS ended_street
            FROM boards
            GROUP BY hand_id
            """;

    public HandListResponse list(HandListFilter filter, int page, int pageSize, String sortDir) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 200);
        Optional<Long> heroId = resolveHeroId();
        if (heroId.isEmpty()) {
            return new HandListResponse(List.of(), 0, safePage, safePageSize);
        }

        MapSqlParameterSource params = new MapSqlParameterSource("heroId", heroId.get());
        String from = """
                FROM hands h
                JOIN seats hero_seat ON hero_seat.hand_id = h.hand_id AND hero_seat.player_id = :heroId
                JOIN hand_net_results hnr ON hnr.hand_id = h.hand_id AND hnr.player_id = :heroId
                JOIN results hr ON hr.hand_id = h.hand_id AND hr.player_id = :heroId
                LEFT JOIN (%s) ht ON ht.hand_id = h.hand_id
                LEFT JOIN (%s) es ON es.hand_id = h.hand_id
                """.formatted(HAND_TYPE_SUBQUERY, ENDED_STREET_SUBQUERY);

        StringBuilder where = new StringBuilder(" WHERE h.format = CAST('CASH' AS hand_format_t) ");
        if (filter.room() != null) {
            where.append(" AND CAST(h.room AS VARCHAR) = :room ");
            params.addValue("room", filter.room());
        }
        if ("WON".equalsIgnoreCase(filter.wonLost())) {
            where.append(" AND hnr.net_result > 0 ");
        } else if ("LOST".equalsIgnoreCase(filter.wonLost())) {
            where.append(" AND hnr.net_result < 0 ");
        }
        if (filter.positions() != null && !filter.positions().isEmpty()) {
            where.append(" AND CAST(hero_seat.position AS VARCHAR) IN (:positions) ");
            params.addValue("positions", filter.positions());
        }
        if (filter.handTypes() != null && !filter.handTypes().isEmpty()) {
            where.append(" AND COALESCE(ht.hand_type, 'WALKED_POT') IN (:handTypes) ");
            params.addValue("handTypes", filter.handTypes());
        }
        if (filter.endedOnStreets() != null && !filter.endedOnStreets().isEmpty()) {
            where.append(" AND COALESCE(es.ended_street, 'PREFLOP') IN (:endedOnStreets) ");
            params.addValue("endedOnStreets", filter.endedOnStreets());
        }
        if (Boolean.TRUE.equals(filter.showdownOnly())) {
            where.append(" AND hr.went_to_showdown = TRUE ");
        }
        if (filter.opponentPlayerId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM seats opp WHERE opp.hand_id = h.hand_id AND opp.player_id = :opponentPlayerId) ");
            params.addValue("opponentPlayerId", filter.opponentPlayerId());
        }
        if (filter.sessionId() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM session_hands sesh WHERE sesh.hand_id = h.hand_id AND sesh.session_id = :sessionId) ");
            params.addValue("sessionId", filter.sessionId());
        }

        long total = duckDb.queryForList("SELECT count(*) AS c " + from + where, params)
                .stream().findFirst().map(r -> ((Number) r.get("c")).longValue()).orElse(0L);

        String direction = "ASC".equalsIgnoreCase(sortDir) ? "ASC" : "DESC";
        params.addValue("limit", safePageSize);
        params.addValue("offset", (safePage - 1) * safePageSize);
        String select = """
                SELECT h.hand_id, CAST(h.room AS VARCHAR) AS room, CAST(h.format AS VARCHAR) AS format,
                       h.hand_timestamp, h.hand_timestamp_raw, h.currency, h.small_blind, h.big_blind,
                       h.total_pot, CAST(hero_seat.position AS VARCHAR) AS hero_position,
                       hero_seat.hole_cards,
                       (SELECT count(*) FROM seats s2 WHERE s2.hand_id = h.hand_id) AS player_count,
                       COALESCE(ht.hand_type, 'WALKED_POT') AS hand_type,
                       COALESCE(es.ended_street, 'PREFLOP') AS ended_on_street,
                       hnr.net_result
                """ + from + where + " ORDER BY h.hand_id " + direction + " LIMIT :limit OFFSET :offset ";

        List<Map<String, Object>> rows = duckDb.queryForList(select, params);
        List<Long> handIds = rows.stream().map(r -> ((Number) r.get("hand_id")).longValue()).toList();
        Map<Long, List<String>> tagsByHandId = tagsFor(handIds);

        List<HandListItemDto> items = rows.stream().map(r -> {
            long handId = ((Number) r.get("hand_id")).longValue();
            String currency = (String) r.get("currency");
            BigDecimal netResult = toBigDecimal(r.get("net_result"));
            String result = netResult == null ? null
                    : netResult.signum() > 0 ? "WON" : netResult.signum() < 0 ? "LOST" : "CHOP";
            String holeCardsRaw = (String) r.get("hole_cards");
            return new HandListItemDto(
                    handId,
                    (String) r.get("room"),
                    (String) r.get("format"),
                    null, // hand_timestamp — not normalized yet, see class/DTO doc
                    String.valueOf(r.get("hand_timestamp_raw")),
                    new StakesDto(
                            MoneyDto.of(toBigDecimal(r.get("small_blind")), currency),
                            MoneyDto.of(toBigDecimal(r.get("big_blind")), currency)),
                    (String) r.get("hero_position"),
                    splitCards(holeCardsRaw),
                    ((Number) r.get("player_count")).intValue(),
                    (String) r.get("hand_type"),
                    (String) r.get("ended_on_street"),
                    MoneyDto.of(toBigDecimal(r.get("total_pot")), currency),
                    result,
                    MoneyDto.of(netResult, currency),
                    tagsByHandId.getOrDefault(handId, List.of()));
        }).toList();

        return new HandListResponse(items, total, safePage, safePageSize);
    }

    public Optional<HandDetailDto> detail(long handId) {
        MapSqlParameterSource idParam = new MapSqlParameterSource("handId", handId);
        List<Map<String, Object>> handRows = duckDb.queryForList("""
                SELECT hand_id, external_hand_id, CAST(room AS VARCHAR) AS room, CAST(format AS VARCHAR) AS format,
                       game_type_raw, currency, small_blind, big_blind, ante, table_name, max_seats,
                       button_seat, hand_timestamp_raw, run_it_twice, total_pot, rake
                FROM hands WHERE hand_id = :handId
                """, idParam);
        if (handRows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> h = handRows.get(0);
        String currency = (String) h.get("currency");

        List<Map<String, Object>> seatRows = duckDb.queryForList("""
                SELECT s.seat_number, CAST(s.position AS VARCHAR) AS position, s.starting_stack,
                       s.hole_cards, p.nickname, p.is_hero
                FROM seats s JOIN players p ON p.player_id = s.player_id
                WHERE s.hand_id = :handId ORDER BY s.seat_number
                """, idParam);
        List<SeatDto> seats = seatRows.stream().map(r -> new SeatDto(
                ((Number) r.get("seat_number")).intValue(),
                displayName(r),
                (Boolean) r.get("is_hero"),
                MoneyDto.of(toBigDecimal(r.get("starting_stack")), currency),
                (String) r.get("position"),
                splitCards((String) r.get("hole_cards")))).toList();

        List<Map<String, Object>> actionRows = duckDb.queryForList("""
                SELECT a.sequence_no, CAST(a.street AS VARCHAR) AS street, CAST(a.action_type AS VARCHAR) AS action_type,
                       a.amount, p.nickname, p.is_hero
                FROM actions a JOIN players p ON p.player_id = a.player_id
                WHERE a.hand_id = :handId ORDER BY a.sequence_no
                """, idParam);
        List<ActionDto> actions = actionRows.stream().map(r -> new ActionDto(
                ((Number) r.get("sequence_no")).intValue(),
                (String) r.get("street"),
                displayName(r),
                (Boolean) r.get("is_hero"),
                (String) r.get("action_type"),
                MoneyDto.of(toBigDecimal(r.get("amount")), currency))).toList();

        List<Map<String, Object>> boardRows = duckDb.queryForList("""
                SELECT run_index, CAST(street AS VARCHAR) AS street, cards
                FROM boards WHERE hand_id = :handId
                ORDER BY run_index, CASE street WHEN CAST('FLOP' AS street_t) THEN 0
                    WHEN CAST('TURN' AS street_t) THEN 1 WHEN CAST('RIVER' AS street_t) THEN 2 END
                """, idParam);
        List<BoardDto> boards = boardRows.stream().map(r -> new BoardDto(
                ((Number) r.get("run_index")).intValue(), (String) r.get("street"), (String) r.get("cards")))
                .toList();

        List<Map<String, Object>> resultRows = duckDb.queryForList("""
                SELECT r.went_to_showdown, r.amount_won, r.outcome_raw, hnr.net_result, p.nickname, p.is_hero
                FROM results r
                JOIN players p ON p.player_id = r.player_id
                LEFT JOIN hand_net_results hnr ON hnr.hand_id = r.hand_id AND hnr.player_id = r.player_id
                WHERE r.hand_id = :handId
                """, idParam);
        List<ResultDto> results = resultRows.stream().map(r -> new ResultDto(
                displayName(r),
                (Boolean) r.get("is_hero"),
                (Boolean) r.get("went_to_showdown"),
                MoneyDto.of(toBigDecimal(r.get("amount_won")), currency),
                MoneyDto.of(toBigDecimal(r.get("net_result")), currency),
                (String) r.get("outcome_raw"))).toList();

        String endedOnStreet = duckDb.queryForList(
                        "SELECT ended_street FROM (" + ENDED_STREET_SUBQUERY + ") WHERE hand_id = :handId", idParam)
                .stream().findFirst().map(r -> (String) r.get("ended_street")).orElse("PREFLOP");
        String handType = duckDb.queryForList(
                        "SELECT hand_type FROM (" + HAND_TYPE_SUBQUERY + ") WHERE hand_id = :handId", idParam)
                .stream().findFirst().map(r -> (String) r.get("hand_type")).orElse("WALKED_POT");

        Long previousHandId = resolveHeroId()
                .flatMap(heroId -> duckDb.queryForList("""
                        SELECT max(h2.hand_id) AS id FROM hands h2 JOIN seats s2 ON s2.hand_id = h2.hand_id
                        WHERE s2.player_id = :heroId AND h2.hand_id < :handId
                        """, new MapSqlParameterSource("heroId", heroId).addValue("handId", handId))
                        .stream().findFirst().map(r -> (Number) r.get("id")))
                .map(Number::longValue).orElse(null);
        Long nextHandId = resolveHeroId()
                .flatMap(heroId -> duckDb.queryForList("""
                        SELECT min(h2.hand_id) AS id FROM hands h2 JOIN seats s2 ON s2.hand_id = h2.hand_id
                        WHERE s2.player_id = :heroId AND h2.hand_id > :handId
                        """, new MapSqlParameterSource("heroId", heroId).addValue("handId", handId))
                        .stream().findFirst().map(r -> (Number) r.get("id")))
                .map(Number::longValue).orElse(null);

        return Optional.of(new HandDetailDto(
                ((Number) h.get("hand_id")).longValue(),
                (String) h.get("external_hand_id"),
                (String) h.get("room"),
                (String) h.get("format"),
                (String) h.get("game_type_raw"),
                String.valueOf(h.get("hand_timestamp_raw")),
                new StakesDtoWithAnte(
                        MoneyDto.of(toBigDecimal(h.get("small_blind")), currency),
                        MoneyDto.of(toBigDecimal(h.get("big_blind")), currency),
                        MoneyDto.of(toBigDecimal(h.get("ante")), currency)),
                (String) h.get("table_name"),
                ((Number) h.get("max_seats")).intValue(),
                ((Number) h.get("button_seat")).intValue(),
                (Boolean) h.get("run_it_twice"),
                MoneyDto.of(toBigDecimal(h.get("total_pot")), currency),
                MoneyDto.of(toBigDecimal(h.get("rake")), currency),
                handType,
                endedOnStreet,
                seats,
                actions,
                boards,
                results,
                previousHandId,
                nextHandId));
    }

    private Optional<Long> resolveHeroId() {
        return duckDb.queryForList("SELECT player_id FROM players WHERE is_hero = TRUE LIMIT 1", Map.of())
                .stream().findFirst().map(r -> ((Number) r.get("player_id")).longValue());
    }

    /** Tag CRUD (docs/10-api-design.md's {@code POST/DELETE /hands/{handId}/tags}) isn't built yet,
     *  so this always returns an empty map today — querying for real rather than hardcoding {@code
     *  []} client-side means the Hands table starts showing real tags the moment that lands, with
     *  no change needed here. Two databases (DuckDB hand ids, SQLite {@code hand_tags}), so this is
     *  an application-level join, not a SQL one — same cross-database composition pattern already
     *  used for Players (docs/09-data-model.md's Relationships section). */
    private Map<Long, List<String>> tagsFor(List<Long> handIds) {
        if (handIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", handIds.stream().map(String::valueOf).toList());
        List<Map<String, Object>> rows = sqlite.queryForList(
                "SELECT hand_id, tag FROM hand_tags WHERE hand_id IN (" + placeholders + ")");
        Map<Long, List<String>> byHand = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long handId = ((Number) row.get("hand_id")).longValue();
            byHand.computeIfAbsent(handId, k -> new ArrayList<>()).add((String) row.get("tag"));
        }
        return byHand;
    }

    private static String displayName(Map<String, Object> row) {
        Boolean isHero = (Boolean) row.get("is_hero");
        if (Boolean.TRUE.equals(isHero)) {
            return "Hero";
        }
        return (String) row.get("nickname");
    }

    private static List<String> splitCards(String spaceJoined) {
        if (spaceJoined == null || spaceJoined.isBlank()) {
            return null;
        }
        return List.of(spaceJoined.trim().split("\\s+"));
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
