package com.pokeranalytics.players;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.pokeranalytics.analytics.CashStats;
import com.pokeranalytics.analytics.CashStatsCalculator;
import com.pokeranalytics.api.dto.PlayerDetailDto;
import com.pokeranalytics.api.dto.PlayerListResponse;
import com.pokeranalytics.api.dto.PlayerListResponse.PlayerListItemDto;
import com.pokeranalytics.api.dto.PlayerListResponse.QuickStatDto;

/**
 * docs/06-query-catalog.md's QF-4 (Player List Query) and QF-5 (Player Detail Query), scoped to
 * opponents only (Hero is explicitly out of this module's scope — docs/05-product-map.md). "Hands
 * vs Hero" is co-occurrence (both dealt into the same hand), not an opponent's total hand count.
 *
 * <p>{@code quickStat} deviates from docs/10-api-design.md's own note that it should be "one
 * grouped query, not one call per row": a {@code PLAYER_ID} breakdown dimension doesn't exist in
 * {@link CashStatsCalculator} (every dimension there groups one already-chosen player's own hands,
 * the opposite shape needed here — one stat per *different* player), so this calls {@link
 * CashStatsCalculator#compute(long)} once
 * per row instead. Acceptable at this app's actual scale — a single local user's opponent pool is
 * realistically dozens to a few hundred, not millions, and each call is a handful of fast queries
 * against an embedded single-file database — but a real, documented shortcut, not the doc's
 * intended shape. Tracked as a follow-up (docs/11-development-tracking.md) if it ever needs fixing.
 */
@Service
public class PlayersQueryService {

    private final NamedParameterJdbcTemplate duckDb;
    private final JdbcTemplate sqlite;
    private final CashStatsCalculator calculator;

    public PlayersQueryService(@Qualifier("duckdb") NamedParameterJdbcTemplate duckDb,
            @Qualifier("sqlite") JdbcTemplate sqlite, CashStatsCalculator calculator) {
        this.duckDb = duckDb;
        this.sqlite = sqlite;
        this.calculator = calculator;
    }

    public PlayerListResponse list(String search, int minHands, int page, int pageSize) {
        int safePage = Math.max(page, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 200);
        Optional<Long> heroId = resolveHeroId();
        if (heroId.isEmpty()) {
            return new PlayerListResponse(List.of(), 0, safePage, safePageSize);
        }

        MapSqlParameterSource params = new MapSqlParameterSource("heroId", heroId.get());
        String from = """
                FROM players p
                LEFT JOIN (
                    SELECT s.player_id, count(DISTINCT s.hand_id) AS hands_vs_hero
                    FROM seats s
                    JOIN seats hero_s ON hero_s.hand_id = s.hand_id AND hero_s.player_id = :heroId
                    WHERE s.player_id != :heroId
                    GROUP BY s.player_id
                ) co ON co.player_id = p.player_id
                """;
        StringBuilder where = new StringBuilder(" WHERE p.is_hero = FALSE ");
        if (search != null && !search.isBlank()) {
            where.append(" AND p.nickname ILIKE :search ");
            params.addValue("search", "%" + search + "%");
        }
        if (minHands > 0) {
            where.append(" AND COALESCE(co.hands_vs_hero, 0) >= :minHands ");
            params.addValue("minHands", minHands);
        }

        long total = duckDb.queryForList("SELECT count(*) AS c " + from + where, params)
                .stream().findFirst().map(r -> ((Number) r.get("c")).longValue()).orElse(0L);

        params.addValue("limit", safePageSize);
        params.addValue("offset", (safePage - 1) * safePageSize);
        List<Map<String, Object>> rows = duckDb.queryForList(
                "SELECT p.player_id, p.nickname, COALESCE(co.hands_vs_hero, 0) AS hands_vs_hero "
                        + from + where + " ORDER BY hands_vs_hero DESC LIMIT :limit OFFSET :offset",
                params);

        List<Long> playerIds = rows.stream().map(r -> ((Number) r.get("player_id")).longValue()).toList();
        Map<Long, List<String>> tagsByPlayerId = tagsFor(playerIds);

        List<PlayerListItemDto> items = rows.stream().map(r -> {
            long playerId = ((Number) r.get("player_id")).longValue();
            CashStats stats = calculator.compute(playerId);
            QuickStatDto quickStat = stats.vpip() == null ? null : new QuickStatDto("VPIP", stats.vpip());
            return new PlayerListItemDto(
                    playerId,
                    (String) r.get("nickname"),
                    ((Number) r.get("hands_vs_hero")).longValue(),
                    quickStat,
                    tagsByPlayerId.getOrDefault(playerId, List.of()));
        }).toList();

        return new PlayerListResponse(items, total, safePage, safePageSize);
    }

    public Optional<PlayerDetailDto> detail(long playerId) {
        MapSqlParameterSource idParam = new MapSqlParameterSource("id", playerId);
        List<Map<String, Object>> rows = duckDb.queryForList("""
                SELECT player_id, nickname, first_seen_at, last_seen_at
                FROM players WHERE player_id = :id AND is_hero = FALSE
                """, idParam);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);

        List<String> rooms = duckDb.queryForList("""
                SELECT DISTINCT CAST(h.room AS VARCHAR) AS room
                FROM seats s JOIN hands h ON h.hand_id = s.hand_id
                WHERE s.player_id = :id ORDER BY room
                """, idParam).stream().map(r -> (String) r.get("room")).toList();

        long handsVsHero = resolveHeroId().map(heroId -> duckDb.queryForList("""
                SELECT count(DISTINCT s.hand_id) AS c FROM seats s
                JOIN seats hero_s ON hero_s.hand_id = s.hand_id AND hero_s.player_id = :heroId
                WHERE s.player_id = :id
                """, idParam.addValue("heroId", heroId))
                .stream().findFirst().map(r -> ((Number) r.get("c")).longValue()).orElse(0L))
                .orElse(0L);

        List<String> freeTexts = sqlite.queryForList(
                "SELECT free_text FROM player_notes WHERE player_id = ?", String.class, playerId);
        String notes = freeTexts.isEmpty() ? "" : freeTexts.get(0);
        List<String> tags = sqlite.queryForList(
                "SELECT tag FROM player_tags WHERE player_id = ? ORDER BY tag", String.class, playerId);

        return Optional.of(new PlayerDetailDto(
                playerId,
                (String) row.get("nickname"),
                rooms,
                String.valueOf(row.get("first_seen_at")),
                String.valueOf(row.get("last_seen_at")),
                handsVsHero,
                notes,
                tags));
    }

    public void saveNotes(long playerId, String freeText) {
        int updated = sqlite.update(
                "UPDATE player_notes SET free_text = ?, updated_at = CURRENT_TIMESTAMP WHERE player_id = ?",
                freeText, playerId);
        if (updated == 0) {
            sqlite.update("INSERT INTO player_notes (player_id, free_text) VALUES (?, ?)", playerId, freeText);
        }
    }

    public void addTag(long playerId, String tag) {
        List<Integer> existing = sqlite.queryForList(
                "SELECT 1 FROM player_tags WHERE player_id = ? AND tag = ?", Integer.class, playerId, tag);
        if (existing.isEmpty()) {
            sqlite.update("INSERT INTO player_tags (player_id, tag) VALUES (?, ?)", playerId, tag);
        }
    }

    public void removeTag(long playerId, String tag) {
        sqlite.update("DELETE FROM player_tags WHERE player_id = ? AND tag = ?", playerId, tag);
    }

    private Optional<Long> resolveHeroId() {
        return duckDb.queryForList("SELECT player_id FROM players WHERE is_hero = TRUE LIMIT 1", Map.of())
                .stream().findFirst().map(r -> ((Number) r.get("player_id")).longValue());
    }

    /** Same cross-database (DuckDB player ids, SQLite {@code player_tags}) application-level join
     *  pattern as {@code HandsQueryService.tagsFor} for hand tags. */
    private Map<Long, List<String>> tagsFor(List<Long> playerIds) {
        if (playerIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", playerIds.stream().map(String::valueOf).toList());
        List<Map<String, Object>> rows = sqlite.queryForList(
                "SELECT player_id, tag FROM player_tags WHERE player_id IN (" + placeholders + ")");
        Map<Long, List<String>> byPlayer = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            long playerId = ((Number) row.get("player_id")).longValue();
            byPlayer.computeIfAbsent(playerId, k -> new ArrayList<>()).add((String) row.get("tag"));
        }
        return byPlayer;
    }
}
