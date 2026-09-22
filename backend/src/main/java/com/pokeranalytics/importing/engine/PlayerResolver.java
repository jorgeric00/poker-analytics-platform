package com.pokeranalytics.importing.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Resolves a hand's Hero/opponent names to stable {@code players.player_id} rows
 * (docs/09-data-model.md). Hero is one reserved row found by {@code is_hero = TRUE}, never by
 * name — the same person's site nickname differs per room, so nickname matching can't identify
 * Hero (see that document's own note on this). One instance is meant to live for the duration of
 * a single import run: it caches nickname -> id lookups in memory to avoid re-querying DuckDB for
 * every action of every hand in a large file.
 */
public class PlayerResolver {

    private final JdbcTemplate duckDb;
    private final Map<String, Long> nicknameCache = new HashMap<>();
    private Long heroId;

    public PlayerResolver(JdbcTemplate duckDb) {
        this.duckDb = duckDb;
    }

    public long resolveHero() {
        if (heroId != null) {
            return heroId;
        }
        List<Long> existing = duckDb.queryForList(
                "SELECT player_id FROM players WHERE is_hero = TRUE", Long.class);
        if (!existing.isEmpty()) {
            heroId = existing.get(0);
            return heroId;
        }
        long id = nextPlayerId();
        duckDb.update("INSERT INTO players (player_id, nickname, is_hero, first_seen_at, last_seen_at) "
                + "VALUES (?, NULL, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id);
        heroId = id;
        return id;
    }

    public long resolveOpponent(String nickname) {
        Long cached = nicknameCache.get(nickname);
        if (cached != null) {
            return cached;
        }
        List<Long> existing = duckDb.queryForList(
                "SELECT player_id FROM players WHERE nickname = ?", Long.class, nickname);
        if (!existing.isEmpty()) {
            long id = existing.get(0);
            duckDb.update("UPDATE players SET last_seen_at = CURRENT_TIMESTAMP WHERE player_id = ?", id);
            nicknameCache.put(nickname, id);
            return id;
        }
        long id = nextPlayerId();
        duckDb.update("INSERT INTO players (player_id, nickname, is_hero, first_seen_at, last_seen_at) "
                + "VALUES (?, ?, FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", id, nickname);
        nicknameCache.put(nickname, id);
        return id;
    }

    private long nextPlayerId() {
        return duckDb.queryForObject("SELECT nextval('seq_player_id')", Long.class);
    }
}
