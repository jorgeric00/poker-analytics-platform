package com.pokeranalytics.importing.parser.pokerstars;

import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.ParsedHand;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Validates {@link PokerStarsParser} against a curated set of hand histories ported from the
 * MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference (see
 * {@code THIRD-PARTY-NOTICES.md}), covering cash-game edge cases: side pots, run-it-twice,
 * antes, multi-currency, weird player names (containing colons/emoticons), sitting out, players
 * joining/leaving mid-hand, and chat messages.
 */
class PokerStarsParserReferenceFixturesTest {

    private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "hand-history", "pokerstars");

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
            List<ParsedHand> hands = PokerStarsParser.parseFile(file);
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
    void parsesSidePotsAndMainPotBreakdown() throws IOException {
        List<ParsedHand> hands = PokerStarsParser.parseFile(
                FIXTURES_DIR.resolve("RunItTwiceTests/RunItTwice1.txt"));
        ParsedHand hand = hands.getFirst();

        assertTrue(hand.runItTwice);
        assertEquals(2, hand.boards.size());
        assertEquals(List.of("3d", "Kd", "9h", "8h", "4s"), hand.boards.get(0));
        assertEquals(List.of("3d", "Kd", "9h", "8h", "Kc"), hand.boards.get(1));

        assertEquals(new BigDecimal("3030"), hand.totalPot);
        assertEquals(new BigDecimal("3007"), hand.mainPot);
        assertEquals(List.of(new BigDecimal("20")), hand.sidePots);

        boolean sawSidePotCollection = hand.actions.stream()
                .anyMatch(a -> a.type() == HandActionType.COLLECTED_SIDE_POT);
        boolean sawMainPotCollection = hand.actions.stream()
                .anyMatch(a -> a.type() == HandActionType.COLLECTED_MAIN_POT);
        assertTrue(sawSidePotCollection, "Expected a side-pot collection action");
        assertTrue(sawMainPotCollection, "Expected a main-pot collection action");
    }

    @Test
    void parsesPlayerNameContainingAColonCorrectly() throws IOException {
        List<ParsedHand> hands = PokerStarsParser.parseFile(
                FIXTURES_DIR.resolve("ExtraHands/PlayerWithColon.txt"));
        ParsedHand hand = hands.getFirst();

        boolean sawColonPlayerAction = hand.actions.stream()
                .anyMatch(a -> a.rawLine().startsWith("wo_olly :D"));
        assertTrue(sawColonPlayerAction, "Expected an action from 'wo_olly :D': " + hand.actions);

        boolean sawColonPlayerSeat = hand.seats.stream()
                .anyMatch(s -> s.playerName().equals("wo_olly :D"));
        assertTrue(sawColonPlayerSeat, "Expected a seat for 'wo_olly :D': " + hand.seats);
    }

    @Test
    void parsesAnteAndMultiCurrencyTables() throws IOException {
        ParsedHand anteHand = PokerStarsParser.parseFile(FIXTURES_DIR.resolve("Limits/AnteTable.txt")).getFirst();
        boolean sawAnte = anteHand.actions.stream().anyMatch(a -> a.type() == HandActionType.POST_ANTE);
        assertTrue(sawAnte, "Expected an ante posting action");

        ParsedHand euroHand = PokerStarsParser.parseFile(FIXTURES_DIR.resolve("Limits/EuroTable.txt")).getFirst();
        assertEquals("€", euroHand.currency);

        ParsedHand gbpHand = PokerStarsParser.parseFile(FIXTURES_DIR.resolve("Limits/GbpTable.txt")).getFirst();
        assertEquals("£", gbpHand.currency);
    }
}
