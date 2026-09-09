package com.pokeranalytics.spike.handhistory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 9 feasibility spike (see docs/11-development-tracking.md): proves the PokerStars Zoom
 * cash-game hand history format is parseable, using real hand histories dropped under
 * docs/hand-history/poker-stars/. This is NOT the frozen import-engine architecture — it should
 * not be extended with real domain logic until Phases 4-8 are documented.
 */
class PokerStarsHandHistoryParserTest {

    private static final Path SAMPLES_DIR = Paths.get("..", "docs", "hand-history", "poker-stars");

    private static List<ParsedHand> parseAllSampleHands() throws IOException {
        assertTrue(Files.isDirectory(SAMPLES_DIR),
                "Expected sample hand histories at " + SAMPLES_DIR.toAbsolutePath()
                        + " -- run tests from the backend/ directory");

        List<ParsedHand> all = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(SAMPLES_DIR, "*.txt")) {
            for (Path file : files) {
                all.addAll(PokerStarsHandHistoryParser.parseFile(file));
            }
        }
        return all;
    }

    private static ParsedHand findHand(List<ParsedHand> hands, String handId) {
        Optional<ParsedHand> hand = hands.stream().filter(h -> handId.equals(h.handId)).findFirst();
        assertTrue(hand.isPresent(), "Expected to find hand #" + handId + " among parsed hands");
        return hand.get();
    }

    @Test
    void parsesAllRealHandsWithoutLeavingUnrecognizedLines() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        assertFalse(hands.isEmpty(), "Expected at least one parsed hand from the sample files");
        System.out.println("Parsed " + hands.size() + " real PokerStars Zoom hands");

        for (ParsedHand hand : hands) {
            assertTrue(hand.unparsedLines.isEmpty(),
                    "Hand #" + hand.handId + " has unrecognized lines: " + hand.unparsedLines);
            assertNotNull(hand.handId, "Every hand must have an id");
            assertFalse(hand.seats.isEmpty(), "Hand #" + hand.handId + " has no seats");
            assertNotNull(hand.totalPot, "Hand #" + hand.handId + " has no total pot");
        }
    }

    @Test
    void parsesAKnownFoldedPreflopHandInDetail() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        ParsedHand hand = findHand(hands, "262026561348");

        assertEquals("Hold'em No Limit", hand.gameType);
        assertEquals("€", hand.currency);
        assertEquals(new BigDecimal("0.05"), hand.smallBlind);
        assertEquals(new BigDecimal("0.10"), hand.bigBlind);
        assertEquals("Caph", hand.tableName);
        assertEquals(6, hand.maxSeats);
        assertEquals(1, hand.buttonSeat);
        assertEquals(6, hand.seats.size());

        assertEquals("jorgericoo", hand.heroName);
        assertEquals(List.of("8h", "3c"), hand.heroHoleCards);

        assertEquals(new BigDecimal("0.20"), hand.totalPot);
        assertEquals(new BigDecimal("0"), hand.rake);

        ParsedSummarySeat winner = hand.summarySeats.stream()
                .filter(s -> s.playerName().equals("manuelolo15"))
                .findFirst()
                .orElseThrow();
        assertEquals("small blind", winner.positionTag());
        assertTrue(winner.outcome().startsWith("collected"), winner.outcome());
    }

    @Test
    void parsesAKnownShowdownHandInDetail() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        ParsedHand hand = findHand(hands, "262026560699");

        assertEquals(List.of("Ts", "5d", "4h", "Ad", "Td"), hand.boardCards);
        assertEquals(new BigDecimal("4.61"), hand.totalPot);
        assertEquals(new BigDecimal("0.23"), hand.rake);

        boolean sawShowdownActions = hand.actions.stream().anyMatch(a -> a.type().equals("SHOWS"));
        assertTrue(sawShowdownActions, "Expected at least one SHOWS action");

        ParsedSummarySeat winner = hand.summarySeats.stream()
                .filter(s -> s.playerName().equals("SirButterman"))
                .findFirst()
                .orElseThrow();
        assertEquals("button", winner.positionTag());
        assertTrue(winner.outcome().contains("flush"), winner.outcome());
    }

    @Test
    void parsesAHandWithAMultiWordPlayerNameCorrectly() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        ParsedHand hand = findHand(hands, "262026561348");

        ParsedSummarySeat multiWordNameSeat = hand.summarySeats.stream()
                .filter(s -> s.seatNumber() == 5)
                .findFirst()
                .orElseThrow();

        assertEquals("iurii machushak", multiWordNameSeat.playerName());
        assertTrue(multiWordNameSeat.outcome().startsWith("folded"), multiWordNameSeat.outcome());
    }
}
