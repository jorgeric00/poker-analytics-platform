package com.pokeranalytics.importing.parser.winamax;

import com.pokeranalytics.importing.model.TournamentSummary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md} and
 * {@code docs/decisions/ADR-008-tournament-summary-parser.md}.
 *
 * Validates {@link WinamaxTournamentSummaryParser} against real Winamax "Tournament summary"
 * files kept alongside the hand-history fixtures in
 * {@code backend/src/main/resources/hand-history/winamax/}: "TRIDENT SPACE KO" and "ROTATION"
 * (two entries each, neither cashing), "QUARK" (one entry, a full 1st-place win with a bounty
 * payout), and an orphan "Expresso" summary with no matching hand-history file.
 */
class WinamaxTournamentSummaryParserRealDataTest {

    private static final Path EXPRESSO_DIR =
            Paths.get("src", "main", "resources", "hand-history", "winamax", "expresso");
    private static final Path MTT_DIR =
            Paths.get("src", "main", "resources", "hand-history", "winamax", "mtt");

    private static List<Path> summaryFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(EXPRESSO_DIR, MTT_DIR)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*_summary.txt")) {
                stream.forEach(files::add);
            }
        }
        return files;
    }

    @Test
    void parsesAllRealSummariesWithoutLeavingUnrecognizedLines() throws IOException {
        List<TournamentSummary> summaries = new ArrayList<>();
        for (Path file : summaryFiles()) {
            summaries.addAll(WinamaxTournamentSummaryParser.parseFile(file));
        }

        assertFalse(summaries.isEmpty(), "Expected at least one parsed summary from the sample files");
        System.out.println("Parsed " + summaries.size() + " real Winamax tournament summaries");

        for (TournamentSummary summary : summaries) {
            assertTrue(summary.unparsedLines.isEmpty(),
                    "Summary for " + summary.tournamentName + " has unrecognized lines: " + summary.unparsedLines);
            assertNotNull(summary.tournamentId);
            assertNotNull(summary.tournamentName);
            assertNotNull(summary.playerName);
            assertNotNull(summary.buyInTotal);
            assertNotNull(summary.finishPlace);
        }
    }

    @Test
    void parsesNonCashingKnockoutEntries() throws IOException {
        List<TournamentSummary> summaries = WinamaxTournamentSummaryParser.parseFile(
                MTT_DIR.resolve("20250911_TRIDENT SPACE KO(975836438)_real_holdem_no-limit_summary.txt"));

        assertEquals(2, summaries.size());
        for (TournamentSummary summary : summaries) {
            assertEquals("TRIDENT SPACE KO", summary.tournamentName);
            assertEquals("975836438", summary.tournamentId);
            assertEquals("Late Registration", summary.titleSuffix);
            assertEquals("knockout", summary.type);
            assertEquals(new BigDecimal("2.00"), summary.buyInTotal);
            assertNull(summary.amountWon, "Didn't cash -- no \"You won\" line at all");
            assertNull(summary.bountyWon);
        }
        assertEquals(1091, summaries.get(0).finishPlace);
        assertEquals(1081, summaries.get(1).finishPlace);
    }

    @Test
    void parsesTournamentWonWithBounty() throws IOException {
        TournamentSummary summary = WinamaxTournamentSummaryParser.parseFile(
                MTT_DIR.resolve("20260519_QUARK(1098224169)_real_holdem_no-limit_summary.txt")).getFirst();

        assertEquals("QUARK", summary.tournamentName);
        assertEquals("1098224169", summary.tournamentId);
        assertNull(summary.titleSuffix);
        assertEquals("daniirey300", summary.playerName);
        assertEquals("0.80€ + 1€ + 0.20€", summary.buyInRaw);
        assertEquals(new BigDecimal("2.00"), summary.buyInTotal);
        assertEquals(925, summary.registeredPlayers);
        assertEquals(new BigDecimal("933.60"), summary.prizepool);
        assertEquals(1, summary.finishPlace);
        assertEquals(new BigDecimal("124.39"), summary.amountWon);
        assertEquals(new BigDecimal("349.49"), summary.bountyWon);
    }

    @Test
    void parsesSitAndGoEntryWithTwoPartBuyIn() throws IOException {
        TournamentSummary summary = WinamaxTournamentSummaryParser.parseFile(
                EXPRESSO_DIR.resolve("20250904_Expresso(985283788)_real_holdem_no-limit_summary.txt")).getFirst();

        assertEquals("Expresso", summary.tournamentName);
        assertEquals("sng", summary.mode);
        assertEquals("sitngo", summary.type);
        assertEquals(new BigDecimal("0.50"), summary.buyInTotal);
        assertEquals(3, summary.finishPlace);
        assertNull(summary.amountWon, "3rd of 3 in this Expresso didn't cash");
    }
}
