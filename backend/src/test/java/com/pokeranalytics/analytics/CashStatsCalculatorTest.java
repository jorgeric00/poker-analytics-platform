package com.pokeranalytics.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pokeranalytics.importing.engine.HandImportService;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser;

/**
 * Verifies {@link CashStatsCalculator} against the same 81 real PokerStars cash hands {@code
 * HandImportServiceTest} already imports — real data, not fixtures invented for this test.
 *
 * <p>Precise expected values for a stat over 81 real hands aren't hand-computable in a reasonable
 * amount of test code, so correctness is checked two ways instead of exact-value assertions: (1)
 * an independent recount of VPIP's raw opportunity/event numbers, via a differently-shaped query
 * than {@link CashStatsCalculator} itself uses, and (2) a money-conservation check on the {@code
 * hand_net_results} view — for every hand, every seated player's net result must sum to exactly
 * {@code -rake} (total winnings = total contributed − rake), which a bug in the raise/call
 * cumulative-vs-incremental handling would very likely break.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class CashStatsCalculatorTest {

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
    private CashStatsCalculator statsCalculator;

    @Autowired
    @Qualifier("duckdb")
    private JdbcTemplate duckDb;

    private long heroId;

    @BeforeEach
    void importRealHands() throws IOException {
        List<ParsedHand> hands = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(SAMPLES_DIR, "*.txt")) {
            for (Path file : files) {
                hands.addAll(PokerStarsParser.parseFile(file));
            }
        }
        importService.importHands(hands, "PokerStars", SAMPLES_DIR.toString());
        heroId = duckDb.queryForObject("SELECT player_id FROM players WHERE is_hero = TRUE", Long.class);
    }

    @Test
    void computesPlausibleStatsForHeroAcrossAllRealHands() {
        CashStats stats = statsCalculator.compute(heroId);

        assertThat(stats.hands()).isEqualTo(81); // every hand in Hero's own Zoom export seats Hero
        assertRateInBounds(stats.vpip());
        assertRateInBounds(stats.pfr());
        assertRateInBounds(stats.threeBet());
        assertRateInBounds(stats.flopCbet());
        assertRateInBounds(stats.foldVsFlopCbet());
        assertThat(stats.stackSizeBb()).isGreaterThan(BigDecimal.ZERO);
        assertThat(stats.bb100()).isNotNull(); // any finite value is plausible over 81 hands

        // PFR requires a raise, which is a stricter condition than VPIP's call-or-bet-or-raise —
        // PFR can never exceed VPIP for the same player.
        assertThat(stats.pfr()).isLessThanOrEqualTo(stats.vpip());
    }

    @Test
    void vpipMatchesAnIndependentlyWrittenRecount() {
        CashStats stats = statsCalculator.compute(heroId);

        Long independentVpipHands = duckDb.queryForObject("""
                SELECT count(DISTINCT hand_id) FROM actions
                WHERE player_id = ? AND street = CAST('PREFLOP' AS street_t)
                  AND action_type IN (CAST('CALL' AS action_type_t), CAST('BET' AS action_type_t), CAST('RAISE' AS action_type_t))
                """, Long.class, heroId);

        BigDecimal expectedVpip = BigDecimal.valueOf(independentVpipHands)
                .divide(BigDecimal.valueOf(stats.hands()), 4, java.math.RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        assertThat(stats.vpip()).isEqualByComparingTo(expectedVpip);
    }

    @Test
    void handNetResultsConserveMoneyAcrossEveryRealHand() {
        // For every hand: Σ(net_result) over every seated player must equal -rake exactly —
        // total winnings = total contributed - rake. A bug in the raise/call cumulative-vs-
        // incremental handling in V2__net_result_view.sql would show up here as nonzero drift.
        List<Map<String, Object>> offenders = duckDb.queryForList("""
                SELECT h.hand_id, h.external_hand_id, sum(hnr.net_result) + h.rake AS drift
                FROM hand_net_results hnr
                JOIN hands h ON h.hand_id = hnr.hand_id
                GROUP BY h.hand_id, h.external_hand_id, h.rake
                HAVING abs(sum(hnr.net_result) + h.rake) > 0.005
                """);

        assertThat(offenders).as("hands where net results don't sum to -rake: %s", offenders).isEmpty();
    }

    @Test
    void positionBreakdownSumsToTotalHandsAndMatchesIndependentRecount() {
        List<CashStatsRow> rows = statsCalculator.computeBreakdown(heroId, BreakdownDimension.POSITION, 0);

        long totalFromBreakdown = rows.stream().mapToLong(r -> r.stats().hands()).sum();
        assertThat(totalFromBreakdown).isEqualTo(81);

        Map<String, Long> independentCounts = new HashMap<>();
        duckDb.query("""
                SELECT CAST(position AS VARCHAR) AS position, count(*) AS hands
                FROM seats WHERE player_id = ? GROUP BY position
                """,
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> independentCounts.put(rs.getString("position"), rs.getLong("hands")),
                heroId);

        Map<String, Long> fromBreakdown = rows.stream()
                .collect(Collectors.toMap(CashStatsRow::breakdownKey, r -> r.stats().hands()));
        assertThat(fromBreakdown).isEqualTo(independentCounts);
    }

    @Test
    void positionBreakdownVpipReweightsBackToTheOverallVpip() {
        CashStats overall = statsCalculator.compute(heroId);
        List<CashStatsRow> rows = statsCalculator.computeBreakdown(heroId, BreakdownDimension.POSITION, 0);

        BigDecimal totalVpipEventsFromBreakdown = rows.stream()
                .map(r -> BigDecimal.valueOf(r.stats().hands())
                        .multiply(r.stats().vpip())
                        .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalVpipEventsOverall = overall.vpip()
                .multiply(BigDecimal.valueOf(overall.hands()))
                .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);

        assertThat(totalVpipEventsFromBreakdown.setScale(0, RoundingMode.HALF_UP))
                .isEqualByComparingTo(totalVpipEventsOverall.setScale(0, RoundingMode.HALF_UP));
    }

    @Test
    void minSampleSizeExcludesGroupsBelowTheThreshold() {
        List<CashStatsRow> rows = statsCalculator.computeBreakdown(heroId, BreakdownDimension.POSITION, 1000);

        assertThat(rows).isEmpty();
    }

    private static void assertRateInBounds(BigDecimal rate) {
        assertThat(rate).isNotNull();
        assertThat(rate).isBetween(BigDecimal.ZERO, BigDecimal.valueOf(100));
    }
}
