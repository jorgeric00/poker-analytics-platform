package com.pokeranalytics.importing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser;

/**
 * End-to-end round trip for the Import Engine's PokerStars-cash slice: real hand-history files
 * (already validated by {@code PokerStarsParserRealDataTest}) parsed, imported into a real
 * (temp-directory) DuckDB/SQLite pair, and read back — not mocked, since the point of this class
 * is exactly the parts a unit test with a fake JdbcTemplate would paper over (enum casts, foreign
 * keys, transaction wiring).
 *
 * <p>{@code @DirtiesContext} per method + a random-suffixed path per context: each test method
 * gets its own, empty pair of databases, so one method's imported rows never bleed into another's
 * assertions (in particular {@code reprocessingTheSameFileReplacesRatherThanDuplicatesRows}, which
 * would otherwise be reprocessing rows other test methods already inserted into a shared file).
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HandImportServiceTest {

    private static final Path SAMPLES_DIR =
            Paths.get("src", "main", "resources", "hand-history", "poker-stars", "cash");

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storagePaths(DynamicPropertyRegistry registry) {
        String suffix = UUID.randomUUID().toString();
        registry.add("app.storage.duckdb-path", () -> tempDir.resolve("test-" + suffix + ".duckdb").toString());
        registry.add("app.storage.sqlite-path", () -> tempDir.resolve("test-" + suffix + ".sqlite").toString());
    }

    @Autowired
    private HandImportService importService;

    @Autowired
    @Qualifier("duckdb")
    private JdbcTemplate duckDb;

    @Autowired
    @Qualifier("sqlite")
    private JdbcTemplate sqlite;

    private static List<ParsedHand> parseAllSampleHands() throws IOException {
        List<ParsedHand> all = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(SAMPLES_DIR, "*.txt")) {
            for (Path file : files) {
                all.addAll(PokerStarsParser.parseFile(file));
            }
        }
        return all;
    }

    @Test
    void importsEveryRealCashHandAndPersistsExpectedRows() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        ImportResult result = importService.importHands(hands, "PokerStars", SAMPLES_DIR.toString());

        assertThat(result.handsSkipped()).isZero(); // fixture set is cash-only
        assertThat(result.handsFailed()).isZero();
        assertThat(result.handsImported()).isEqualTo(hands.size());
        assertThat(result.status()).isEqualTo("success");

        Integer handCount = duckDb.queryForObject("SELECT count(*) FROM hands", Integer.class);
        assertThat(handCount).isEqualTo(hands.size());

        Integer actionCount = duckDb.queryForObject("SELECT count(*) FROM actions", Integer.class);
        assertThat(actionCount).isEqualTo(hands.stream().mapToInt(h -> h.actions.size()).sum());

        Integer heroRows = duckDb.queryForObject(
                "SELECT count(*) FROM players WHERE is_hero = TRUE", Integer.class);
        assertThat(heroRows).isEqualTo(1); // resolved once, reused across every hand

        Integer historyRows = sqlite.queryForObject("SELECT count(*) FROM import_history", Integer.class);
        assertThat(historyRows).isEqualTo(1);
    }

    @Test
    void persistsAKnownHandWithCorrectPositionsAndResult() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();
        importService.importHands(hands, "PokerStars", SAMPLES_DIR.toString());

        Map<String, Object> hand = duckDb.queryForMap(
                "SELECT * FROM hands WHERE room = CAST('POKERSTARS' AS room_t) AND external_hand_id = ?",
                "262026561348");
        assertThat(hand.get("game_family")).hasToString("HOLDEM");
        assertThat(hand.get("bet_limit")).hasToString("NO_LIMIT");
        assertThat(hand.get("currency")).isEqualTo("€");
        assertThat(((Number) hand.get("max_seats")).intValue()).isEqualTo(6);

        long handId = ((Number) hand.get("hand_id")).longValue();

        String sbPosition = duckDb.queryForObject("""
                SELECT s.position FROM seats s JOIN players p ON p.player_id = s.player_id
                WHERE s.hand_id = ? AND p.nickname = 'manuelolo15'
                """, String.class, handId);
        assertThat(sbPosition).isEqualTo("SB");

        String bbPosition = duckDb.queryForObject("""
                SELECT position FROM seats WHERE hand_id = ? AND seat_number = 3
                """, String.class, handId); // jorgericoo, the hand's own hero
        assertThat(bbPosition).isEqualTo("BB");

        Map<String, Object> result = duckDb.queryForMap("""
                SELECT r.* FROM results r JOIN players p ON p.player_id = r.player_id
                WHERE r.hand_id = ? AND p.nickname = 'manuelolo15'
                """, handId);
        assertThat((BigDecimal) result.get("amount_won")).isEqualByComparingTo("0.20");
        assertThat(result.get("went_to_showdown")).isEqualTo(false);
    }

    @Test
    void reprocessingTheSameFileReplacesRatherThanDuplicatesRows() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        importService.importHands(hands, "PokerStars", SAMPLES_DIR.toString());
        importService.importHands(hands, "PokerStars", SAMPLES_DIR.toString());

        Integer handCount = duckDb.queryForObject("SELECT count(*) FROM hands", Integer.class);
        assertThat(handCount).isEqualTo(hands.size()); // not doubled
    }
}
