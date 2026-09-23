package com.pokeranalytics.importing.parser.partypoker;

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
 * Validates {@link PartyPokerParser} against a curated set of hand histories ported from the
 * MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference (see
 * {@code THIRD-PARTY-NOTICES.md}), covering cash-game edge cases: side pots, Omaha Hi/Lo low-hand
 * reveals, all-ins, dead-blind re-entries, players joining/leaving/sitting out and time-bank
 * messages mid-hand, and multi-hand exports wrapped in "#Game No"/"Game # starts." bookkeeping.
 */
class PartyPokerParserReferenceFixturesTest {

    private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "hand-history", "partypoker");

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
            List<ParsedHand> hands = PartyPokerParser.parseFile(file);
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
    void computesTotalPotBySummingWinsAndLeavesRakeUnstated() throws IOException {
        ParsedHand hand = PartyPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/BasicHand.txt")).getFirst();

        assertEquals(new BigDecimal("0.19"), hand.totalPot);
        assertEquals(null, hand.rake, "PartyPoker hand histories never state rake");
    }

    @Test
    void splitsSidePotAndMainPotAcrossMultipleWinsLines() throws IOException {
        ParsedHand hand = PartyPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/AllInHandWithShowdown.txt")).getFirst();

        assertEquals(new BigDecimal("22.53"), hand.totalPot);

        boolean sawMainPot = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.COLLECTED_MAIN_POT);
        boolean sawSidePot = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.COLLECTED_SIDE_POT);
        assertTrue(sawMainPot, "Expected a main-pot collection action");
        assertTrue(sawSidePot, "Expected a side-pot collection action");
    }

    @Test
    void parsesOmahaHiLoLowHandSplit() throws IOException {
        ParsedHand hand = PartyPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/OmahaHiLo.txt")).getFirst();

        assertEquals(new BigDecimal("58.50"), hand.totalPot);

        boolean sawLowShow = hand.actions.stream()
                .anyMatch(a -> a.type() == HandActionType.SHOWS && a.rawLine().contains("for low."));
        assertTrue(sawLowShow, "Expected a low-hand SHOWS action");
    }

    @Test
    void parsesDeadBlindPostSeparatelyFromTheRealBigBlind() throws IOException {
        ParsedHand hand = PartyPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/Posting.txt")).getFirst();

        assertEquals(new BigDecimal("1"), hand.smallBlind);
        assertEquals(new BigDecimal("2"), hand.bigBlind);

        boolean sawDeadBlind = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.POST_DEAD_BLIND);
        assertTrue(sawDeadBlind, "Expected a dead-blind post action");
    }

    @Test
    void parsesExactPlayerAndAmountOnKeyActions() throws IOException {
        ParsedHand hand = PartyPokerParser.parseFile(
                FIXTURES_DIR.resolve("GameTypeTests/PotLimitOmaha.txt")).getFirst();

        var smallBlind = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.POST_SMALL_BLIND).findFirst().orElseThrow();
        assertEquals("SooooLastYr", smallBlind.player());
        assertEquals(new BigDecimal("5"), smallBlind.amount());

        var bigBlind = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.POST_BIG_BLIND).findFirst().orElseThrow();
        assertEquals("PINOLAXXL", bigBlind.player());
        assertEquals(new BigDecimal("10"), bigBlind.amount());

        var fold = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.FOLD && "GOLFPRO566".equals(a.player()))
                .findFirst().orElseThrow();
        assertEquals(null, fold.amount());

        // Already the cumulative total for the street (uncontested, no call ever follows it) —
        // "raises [$30 USD]" is neohacks' whole preflop commitment, matching "wins $45 USD"
        // (5 + 10 + 30) exactly.
        var raise = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.RAISE).findFirst().orElseThrow();
        assertEquals("neohacks", raise.player());
        assertEquals(new BigDecimal("30"), raise.amount());

        var collected = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.COLLECTED).findFirst().orElseThrow();
        assertEquals("neohacks", collected.player());
        assertEquals(new BigDecimal("45"), collected.amount());
    }

    @Test
    void parsesMultiHandExportBookkeepingWithoutLeftoverLines() throws IOException {
        List<ParsedHand> hands = PartyPokerParser.parseFile(
                FIXTURES_DIR.resolve("MultipleHandsTests/5MultipleHands.txt"));

        assertEquals(5, hands.size());
        for (ParsedHand hand : hands) {
            assertTrue(hand.unparsedLines.isEmpty(), hand.handId + ": " + hand.unparsedLines);
        }
    }
}
