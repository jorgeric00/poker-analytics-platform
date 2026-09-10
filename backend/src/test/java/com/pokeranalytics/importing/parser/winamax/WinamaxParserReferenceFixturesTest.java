package com.pokeranalytics.importing.parser.winamax;

import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.SummarySeat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Validates {@link WinamaxParser} against a curated set of cash-game hand histories ported from
 * the MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference (see
 * {@code THIRD-PARTY-NOTICES.md}): basic/all-in/showdown hands, dead-money blinds ("out of
 * position"), heads-up dual position tags, and the summary "Board:" line overriding the board
 * assembled from street markers.
 *
 * Not yet validated against real Winamax hands (unlike {@code PokerStarsParserRealDataTest}) —
 * tracked as a follow-up in {@code docs/11-development-tracking.md} pending real samples.
 */
class WinamaxParserReferenceFixturesTest {

    private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "hand-history", "winamax");

    private static List<Path> allFixtureFiles() throws IOException {
        assertTrue(Files.isDirectory(FIXTURES_DIR), "Expected fixtures at " + FIXTURES_DIR.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(FIXTURES_DIR)) {
            List<Path> files = new ArrayList<>();
            walk.filter(p -> p.toString().endsWith(".txt")).forEach(files::add);
            return files;
        }
    }

    @Test
    void parsesEveryReferenceFixtureWithoutLeavingUnrecognizedLines() throws IOException {
        List<Path> files = allFixtureFiles();
        assertFalse(files.isEmpty(), "Expected at least one reference fixture");

        int totalHands = 0;
        for (Path file : files) {
            List<ParsedHand> hands = WinamaxParser.parseFile(file);
            assertFalse(hands.isEmpty(), "Expected at least one hand in " + file);
            totalHands += hands.size();

            for (ParsedHand hand : hands) {
                assertTrue(hand.unparsedLines.isEmpty(),
                        file + " hand #" + hand.handId + " has unrecognized lines: " + hand.unparsedLines);
                assertNotNull(hand.handId, file + " hand has no id");
                assertFalse(hand.seats.isEmpty(), file + " hand #" + hand.handId + " has no seats");
                assertNotNull(hand.totalPot, file + " hand #" + hand.handId + " has no total pot");
            }
        }
        System.out.println("Parsed " + totalHands + " hands across " + files.size() + " reference fixtures");
    }

    @Test
    void parsesADeadMoneyBigBlindPostedOutOfPosition() throws IOException {
        ParsedHand hand = WinamaxParser.parseFile(FIXTURES_DIR.resolve("Tables/Table3.txt")).getFirst();

        boolean sawDeadMoneyPost = hand.actions.stream()
                .anyMatch(a -> a.type() == HandActionType.POST_BIG_BLIND
                        && a.rawLine().endsWith("out of position"));
        assertTrue(sawDeadMoneyPost, "Expected a big-blind post out of position: " + hand.actions);
    }

    @Test
    void parsesHeadsUpButtonWithBothPositionTags() throws IOException {
        ParsedHand hand = WinamaxParser.parseFile(FIXTURES_DIR.resolve("Tables/Table1.txt")).getFirst();

        SummarySeat winner = hand.summarySeats.stream()
                .filter(s -> s.playerName().equals("TALISMAN01"))
                .findFirst()
                .orElseThrow();
        assertEquals("small blind, button", winner.positionTag());
        assertTrue(winner.outcome().startsWith("won"), winner.outcome());
    }

    @Test
    void summaryBoardOverridesStreetMarkerBoard() throws IOException {
        // This fixture deliberately shows different flop cards mid-hand than in the closing
        // "Board:" summary line, to pin down that the summary line wins (same rule as PokerStars).
        ParsedHand hand = WinamaxParser.parseFile(FIXTURES_DIR.resolve("StreetTests/Flop.txt")).getFirst();

        assertEquals(List.of("7h", "Qs", "3c"), hand.boards.getFirst());
    }

    @Test
    void parsesShowdownWithMuckAndNoRakeHands() throws IOException {
        ParsedHand showdownHand = WinamaxParser.parseFile(
                FIXTURES_DIR.resolve("StreetTests/River.txt")).getFirst();

        boolean sawShows = showdownHand.actions.stream().anyMatch(a -> a.type() == HandActionType.SHOWS);
        assertTrue(sawShows, "Expected at least one SHOWS action");

        SummarySeat mucker = showdownHand.summarySeats.stream()
                .filter(s -> s.playerName().equals("Barthez91"))
                .findFirst()
                .orElseThrow();
        assertEquals("big blind", mucker.positionTag());
        assertEquals("mucked", mucker.outcome());

        ParsedHand noRakeHand = WinamaxParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/FoldedPreflop.txt")).getFirst();
        assertEquals(BigDecimal.ZERO, noRakeHand.rake);

        ParsedHand noTagHand = WinamaxParser.parseFile(
                FIXTURES_DIR.resolve("MultipleHandsTests/10MultipleHands.txt")).get(3);
        SummarySeat untaggedWinner = noTagHand.summarySeats.getFirst();
        assertNull(untaggedWinner.positionTag());
    }
}
