package com.pokeranalytics.importing.parser.pokerstars;

import com.pokeranalytics.importing.model.HandAction;
import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.SummarySeat;
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
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Validates {@link PokerStarsParser} against real PokerStars Zoom cash hands kept in
 * {@code backend/src/main/resources/hand-history/poker-stars/cash/} — supersedes the earlier
 * feasibility spike (com.pokeranalytics.spike.handhistory), now retired.
 */
class PokerStarsParserRealDataTest {

    private static final Path SAMPLES_DIR =
            Paths.get("src", "main", "resources", "hand-history", "poker-stars", "cash");

    private static List<ParsedHand> parseAllSampleHands() throws IOException {
        assertTrue(Files.isDirectory(SAMPLES_DIR),
                "Expected sample hand histories at " + SAMPLES_DIR.toAbsolutePath()
                        + " -- run tests from the backend/ directory");

        List<ParsedHand> all = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(SAMPLES_DIR, "*.txt")) {
            for (Path file : files) {
                all.addAll(PokerStarsParser.parseFile(file));
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

        SummarySeat winner = hand.summarySeats.stream()
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

        assertEquals(1, hand.boards.size());
        assertEquals(List.of("Ts", "5d", "4h", "Ad", "Td"), hand.boards.getFirst());
        assertEquals(new BigDecimal("4.61"), hand.totalPot);
        assertEquals(new BigDecimal("0.23"), hand.rake);

        boolean sawShowdownActions = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.SHOWS);
        assertTrue(sawShowdownActions, "Expected at least one SHOWS action");

        SummarySeat winner = hand.summarySeats.stream()
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

        SummarySeat multiWordNameSeat = hand.summarySeats.stream()
                .filter(s -> s.seatNumber() == 5)
                .findFirst()
                .orElseThrow();

        assertEquals("iurii machushak", multiWordNameSeat.playerName());
        assertTrue(multiWordNameSeat.outcome().startsWith("folded"), multiWordNameSeat.outcome());
    }

    /**
     * Structured {@code player}/{@code amount} on {@link HandAction} (added for the Import
     * Engine, docs/09-data-model.md's {@code actions} table) against every action type this hand
     * actually exercises: blinds, a fold with no amount, a raise (recorded as the resulting total,
     * not the increment), an uncalled-bet return (whose raw text states amount before player,
     * the reverse of every other pattern), and an uncontested pot collection.
     */
    @Test
    void extractsStructuredPlayerAndAmountPerAction() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();
        ParsedHand hand = findHand(hands, "262026561348");

        HandAction smallBlind = actionOfType(hand, HandActionType.POST_SMALL_BLIND);
        assertEquals("manuelolo15", smallBlind.player());
        assertEquals(new BigDecimal("0.05"), smallBlind.amount());

        HandAction bigBlind = actionOfType(hand, HandActionType.POST_BIG_BLIND);
        assertEquals("jorgericoo", bigBlind.player());
        assertEquals(new BigDecimal("0.10"), bigBlind.amount());

        HandAction fold = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.FOLD && "magiamonty".equals(a.player()))
                .findFirst().orElseThrow();
        assertEquals(null, fold.amount());

        // "manuelolo15: raises €0.20 to €0.30" — the recorded amount is the resulting total (€0.30).
        HandAction raise = actionOfType(hand, HandActionType.RAISE);
        assertEquals("manuelolo15", raise.player());
        assertEquals(new BigDecimal("0.30"), raise.amount());

        // "Uncalled bet (€0.20) returned to manuelolo15"
        HandAction uncalled = actionOfType(hand, HandActionType.UNCALLED_RETURN);
        assertEquals("manuelolo15", uncalled.player());
        assertEquals(new BigDecimal("0.20"), uncalled.amount());

        // "manuelolo15 collected €0.20 from pot"
        HandAction collected = actionOfType(hand, HandActionType.COLLECTED);
        assertEquals("manuelolo15", collected.player());
        assertEquals(new BigDecimal("0.20"), collected.amount());
    }

    private static HandAction actionOfType(ParsedHand hand, HandActionType type) {
        return hand.actions.stream().filter(a -> a.type() == type).findFirst()
                .orElseThrow(() -> new AssertionError("No action of type " + type + " in hand #" + hand.handId));
    }
}
