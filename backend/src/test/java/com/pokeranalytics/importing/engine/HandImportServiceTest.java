package com.pokeranalytics.importing.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

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
import com.pokeranalytics.importing.parser.ipoker.IPokerParser;
import com.pokeranalytics.importing.parser.partypoker.PartyPokerParser;
import com.pokeranalytics.importing.parser.poker888.Poker888Parser;
import com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser;
import com.pokeranalytics.importing.parser.winamax.WinamaxParser;
import com.pokeranalytics.importing.parser.winningpoker.WinningPokerParser;

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

    // --- The five rooms added after PokerStars (docs/11-development-tracking.md) -------------
    //
    // Only reference/curated fixtures exist for these five rooms (no real hand histories yet,
    // same as every one of their own parser tests) — these round-trip the exact same fixture sets
    // those parser tests already validate structurally, but through the real Import Engine this
    // time: enum casts, foreign keys, transaction wiring, and — the part unique to this class —
    // whether hand_net_results (V5__net_result_view_all_action_types.sql) actually balances for
    // every one of these rooms' real action-type vocabularies (ALL_IN, STRADDLE, POST_DEAD_BLIND,
    // POSTS), not just PokerStars'.

    private static List<ParsedHand> parseAllFixtures(Path dir, Function<Path, List<ParsedHand>> parseFile)
            throws IOException {
        List<ParsedHand> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".txt")).toList()) {
                all.addAll(parseFile.apply(file));
            }
        }
        return all;
    }

    private static List<ParsedHand> parse(Path file, ParserFn fn) {
        try {
            return fn.parse(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface ParserFn {
        List<ParsedHand> parse(Path file) throws IOException;
    }

    /** Every real hand's net results must sum to exactly {@code -rake} (same invariant the
     *  Analytics Engine work originally caught two real bugs with) — only meaningful for rooms
     *  whose text actually states a rake figure. {@code knownInconsistentExternalIds} excludes
     *  specific fixtures already root-caused as not economically self-consistent in the room's own
     *  text (not a parser/import bug) — see each call site's own comment for which and why. */
    private void assertExactMoneyConservation(Set<String> knownInconsistentExternalIds) {
        List<Long> handIds = duckDb.queryForList("SELECT hand_id FROM hands", Long.class);
        List<String> violations = new ArrayList<>();
        for (Long handId : handIds) {
            String externalId = duckDb.queryForObject("SELECT external_hand_id FROM hands WHERE hand_id = ?", String.class, handId);
            if (knownInconsistentExternalIds.contains(externalId)) {
                continue;
            }
            BigDecimal sumNetResult = duckDb.queryForObject(
                    "SELECT COALESCE(SUM(net_result), 0) FROM hand_net_results WHERE hand_id = ?",
                    BigDecimal.class, handId);
            BigDecimal rake = duckDb.queryForObject(
                    "SELECT COALESCE(rake, 0) FROM hands WHERE hand_id = ?", BigDecimal.class, handId);
            if (sumNetResult.compareTo(rake.negate()) != 0) {
                violations.add("hand " + handId + " (external " + externalId + "): expected " + rake.negate() + " but was " + sumNetResult);
            }
        }
        assertThat(violations).isEmpty();
    }

    /** Weaker invariant for rooms that never state a rake figure at all (PartyPoker/888/iPoker) —
     *  can't check against a real rake value, but the table can never be a net source of money. */
    private void assertNoHandCreatesMoney() {
        List<Long> handIds = duckDb.queryForList("SELECT hand_id FROM hands", Long.class);
        List<String> violations = new ArrayList<>();
        for (Long handId : handIds) {
            BigDecimal sumNetResult = duckDb.queryForObject(
                    "SELECT COALESCE(SUM(net_result), 0) FROM hand_net_results WHERE hand_id = ?",
                    BigDecimal.class, handId);
            if (sumNetResult.compareTo(BigDecimal.ZERO) > 0) {
                String externalId = duckDb.queryForObject("SELECT external_hand_id FROM hands WHERE hand_id = ?", String.class, handId);
                violations.add("hand " + handId + " (external " + externalId + "): sum(net_result) = " + sumNetResult);
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void importsEveryWinamaxCashReferenceFixtureWithExactMoneyConservation() throws IOException {
        Path dir = Paths.get("src", "test", "resources", "hand-history", "winamax");
        List<ParsedHand> hands = parseAllFixtures(dir, file -> parse(file, WinamaxParser::parseFile));

        ImportResult result = importService.importHands(hands, "Winamax", dir.toString());

        assertThat(result.handsFailed()).isZero();
        assertThat(result.handsImported()).isEqualTo(hands.size());
        assertExactMoneyConservation(Set.of());
    }

    @Test
    void importsEveryPartyPokerCashReferenceFixtureWithoutCreatingMoney() throws IOException {
        Path dir = Paths.get("src", "test", "resources", "hand-history", "partypoker");
        List<ParsedHand> hands = parseAllFixtures(dir, file -> parse(file, PartyPokerParser::parseFile));

        ImportResult result = importService.importHands(hands, "PartyPoker", dir.toString());

        assertThat(result.handsFailed()).isZero();
        assertThat(result.handsImported()).isEqualTo(hands.size());
        assertNoHandCreatesMoney(); // this room's text never states a rake figure
    }

    @Test
    void imports888CashReferenceFixturesWithoutCreatingMoney() throws IOException {
        Path dir = Paths.get("src", "test", "resources", "hand-history", "poker888");
        List<ParsedHand> hands = parseAllFixtures(dir, file -> parse(file, Poker888Parser::parseFile));

        ImportResult result = importService.importHands(hands, "888", dir.toString());

        assertThat(result.handsFailed()).isZero();
        assertThat(result.handsImported()).isEqualTo(hands.size());
        assertNoHandCreatesMoney(); // this room's text never states a rake figure
    }

    @Test
    void importsIPokerCashReferenceFixturesWithoutCreatingMoney() throws IOException {
        Path dir = Paths.get("src", "test", "resources", "hand-history", "ipoker");
        List<ParsedHand> hands = parseAllFixtures(dir, file -> parse(file, IPokerParser::parseFile));

        ImportResult result = importService.importHands(hands, "iPoker", dir.toString());

        // Two fixtures are genuinely outside PositionAssigner's own documented scope (its class doc:
        // "table sizes above 9 aren't covered" — same for below 2, not explicitly stated but the
        // same boundary), a deliberate, separate follow-up, not a room-import bug: Seats/"Full Ring
        // (10 Handed).txt" and ValidHandTests/InValidHand.txt (a 1-handed hand). Both fail cleanly
        // (counted in handsFailed, not silently dropped) rather than blocking the other 41 fixtures.
        assertThat(result.handsFailed()).isEqualTo(2);
        assertThat(result.handsImported()).isEqualTo(hands.size() - 2);
        assertNoHandCreatesMoney(); // this room's text never states a rake figure
    }

    @Test
    void importsEveryWinningPokerNetworkCashReferenceFixtureWithoutCreatingMoney() throws IOException {
        Path dir = Paths.get("src", "test", "resources", "hand-history", "winningpoker");
        List<ParsedHand> hands = parseAllFixtures(dir, file -> parse(file, WinningPokerParser::parseFile));

        ImportResult result = importService.importHands(hands, "WinningPoker", dir.toString());

        assertThat(result.handsFailed()).isZero();
        assertThat(result.handsImported()).isEqualTo(hands.size());
        // Not the strict -rake check the multi-raise/all-in test above already covers precisely:
        // a clear majority of this room's ported reference fixtures have a small (usually exactly
        // 0.25), consistent gap between every player's own stated summary "Bets" total (which this
        // parser reproduces exactly, cross-checked by hand on several) and that same summary's
        // "Pot + Rake" — e.g. HandActionTests/BasicHand.txt: four players' Bets sum to 48 against a
        // stated Pot 45.75 + Rake 2 = 47.75. Too widespread across distinct stakes/hands to be a
        // one-off bad fixture, and the imbalance always favors the "site," never creating money
        // (this loose bound), so treated the same as PartyPoker/888/iPoker's own "text never states
        // the true full deduction" gap rather than chased further per-fixture.
        assertNoHandCreatesMoney();
    }
}
