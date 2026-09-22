package com.pokeranalytics.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Confirms both embedded databases start up, run their Flyway migrations (docs/09-data-model.md)
 * and end up with the expected tables — not a test of any query/business logic yet, just that
 * the schema the design docs specify is actually what gets created.
 */
@SpringBootTest
class SchemaMigrationTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storagePaths(DynamicPropertyRegistry registry) {
        registry.add("app.storage.duckdb-path", () -> tempDir.resolve("test.duckdb").toString());
        registry.add("app.storage.sqlite-path", () -> tempDir.resolve("test.sqlite").toString());
    }

    @Autowired
    @Qualifier("duckdb")
    private JdbcTemplate duckDb;

    @Autowired
    @Qualifier("sqlite")
    private JdbcTemplate sqlite;

    @Test
    void duckDbSchemaHasEveryFactTable() {
        List<String> tables = duckDb.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'main' AND table_name != 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "players", "hands", "seats", "actions", "boards",
                "results", "hand_side_pots", "tournament_results", "sessions", "session_hands");
    }

    @Test
    void sqliteSchemaHasEveryAppDataTable() {
        List<String> tables = sqlite.queryForList(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' "
                        + "AND name NOT LIKE 'flyway_%'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "import_history", "import_unparsed_lines", "player_notes",
                "player_tags", "hand_tags", "saved_views", "preferences");
    }

    @Test
    void heroInvariantCanBeEnforcedAtInsertTime() {
        // Only one is_hero = TRUE row is ever expected (docs/09-data-model.md) — this is an
        // application-level invariant (DuckDB has no cross-row CHECK), so this test just confirms
        // the table accepts the shape the Import Engine will rely on, not that the invariant is
        // enforced by the database itself.
        duckDb.update("INSERT INTO players (nickname, is_hero) VALUES (NULL, TRUE)");
        Integer heroCount = duckDb.queryForObject(
                "SELECT count(*) FROM players WHERE is_hero = TRUE", Integer.class);

        assertThat(heroCount).isEqualTo(1);
    }
}
