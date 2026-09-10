package com.pokeranalytics.importing.parser.ipoker;

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
 * Validates {@link IPokerParser} against a curated set of hand histories ported from the
 * MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference (see
 * {@code THIRD-PARTY-NOTICES.md}), covering XML-specific edge cases: a single-line/unindented XML
 * document, a session bundling many games under one shared header, out-of-document-order action
 * numbering, and Omaha's 4 hole cards.
 */
class IPokerParserReferenceFixturesTest {

    private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "hand-history", "ipoker");

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
            List<ParsedHand> hands = IPokerParser.parseFile(file);
            assertFalse(hands.isEmpty(), "Expected at least one hand in " + file);
            totalHands += hands.size();

            for (ParsedHand hand : hands) {
                assertTrue(hand.unparsedLines.isEmpty(),
                        file + " hand #" + hand.handId + " has unrecognized lines: " + hand.unparsedLines);
                assertNotNull(hand.handId, file + " hand has no id");
                assertFalse(hand.seats.isEmpty(), file + " hand #" + hand.handId + " has no seats");
            }
        }
        System.out.println("Parsed " + totalHands + " hands across " + files.size() + " reference fixtures");
    }

    @Test
    void parsesAnUnformattedSingleLineXmlDocument() throws IOException {
        ParsedHand hand = IPokerParser.parseFile(
                FIXTURES_DIR.resolve("FormatTests/UnformattedXmlHand.txt")).getFirst();

        assertEquals("3305969126", hand.handId);
        assertEquals(new BigDecimal("25"), hand.smallBlind);
        assertEquals(new BigDecimal("50"), hand.bigBlind);
    }

    @Test
    void splitsASharedGeneralBlockAcrossManyGames() throws IOException {
        List<ParsedHand> hands = IPokerParser.parseFile(
                FIXTURES_DIR.resolve("MultipleHandsTests/10MultipleHands.txt"));

        assertEquals(10, hands.size());
        for (ParsedHand hand : hands) {
            assertEquals("EUR", hand.currency);
            assertTrue(hand.unparsedLines.isEmpty());
        }
    }

    @Test
    void ordersActionsByTheirOwnSequenceNumberNotDocumentPosition() throws IOException {
        ParsedHand hand = IPokerParser.parseFile(
                FIXTURES_DIR.resolve("PlayerTests/WithShowdown.txt")).getFirst();

        // BeenToHell's ante (no=1) must come before dunny53's ante (no=2) despite appearing later
        // in the raw XML (dunny53's own ante line is action no=2 but is written first in the file).
        int beenToHellAnteIndex = -1;
        int dunny53AnteIndex = -1;
        for (int i = 0; i < hand.actions.size(); i++) {
            var action = hand.actions.get(i);
            if (action.type() == HandActionType.POST_ANTE && action.rawLine().contains("player=\"BeenToHell\"")) {
                beenToHellAnteIndex = i;
            }
            if (action.type() == HandActionType.POST_ANTE && action.rawLine().contains("player=\"dunny53\"")) {
                dunny53AnteIndex = i;
            }
        }
        assertTrue(beenToHellAnteIndex >= 0 && dunny53AnteIndex >= 0, "Expected both ante actions");
        assertTrue(beenToHellAnteIndex < dunny53AnteIndex, "Expected action-number order, not document order");
    }

    @Test
    void parsesRevealedHoleCardsAndFlipsSuitRankOrderOnTheBoardToo() throws IOException {
        ParsedHand hand = IPokerParser.parseFile(
                FIXTURES_DIR.resolve("PlayerTests/WithShowdown.txt")).getFirst();

        boolean sawShow = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.SHOWS
                && a.rawLine().contains("dunny53") && a.rawLine().contains("HK S10"));
        assertTrue(sawShow, "Expected dunny53's revealed pocket cards to be recognized");

        // Board <cards> are suit-then-rank too: "C9 H10 H9" on the flop must flip to rank-then-suit.
        assertEquals(List.of("9c", "10h", "9h"), hand.board(0).subList(0, 3));
    }
}
