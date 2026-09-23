package com.pokeranalytics.importing.parser.poker888;

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
 * Validates {@link Poker888Parser} against a curated set of hand histories ported from the
 * MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference (see
 * {@code THIRD-PARTY-NOTICES.md}), covering cash-game edge cases: multiple white-label brand
 * headers on the same network ("888poker", "Cassava", "LuckyAcePoker.com"), Omaha Hi/Lo low-hand
 * splits, all-in showdowns, dead-blind and catch-up big-blind postings, Jackpot tables, and an
 * incomplete/invalid hand with no summary section at all.
 */
class Poker888ParserReferenceFixturesTest {

    private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "hand-history", "poker888");

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
            List<ParsedHand> hands = Poker888Parser.parseFile(file);
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
    void computesTotalPotBySummingCollectedLines() throws IOException {
        ParsedHand hand = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("GeneralHands/GeneralHand.txt")).getFirst();

        assertEquals(new BigDecimal("0.19"), hand.totalPot);
        assertEquals(new BigDecimal("0.05"), hand.smallBlind);
        assertEquals(new BigDecimal("0.10"), hand.bigBlind);
    }

    @Test
    void parsesOmahaHiLoSplitPotByDoublingTheCollectedLine() throws IOException {
        ParsedHand hand = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/OmahaHiLo.txt")).getFirst();

        // The same winner collects twice: once for the Hi half, once for the Lo half.
        assertEquals(new BigDecimal("1.90"), hand.totalPot);
        long collectedCount = hand.actions.stream().filter(a -> a.type() == HandActionType.COLLECTED).count();
        assertEquals(2, collectedCount);
    }

    @Test
    void parsesDeadBlindAsTheSumOfBothBracketedParts() throws IOException {
        ParsedHand hand = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/PostingDead.txt")).getFirst();

        boolean sawDeadBlind = hand.actions.stream()
                .anyMatch(a -> a.type() == HandActionType.POST_DEAD_BLIND && a.rawLine().contains("[$1 + $2]"));
        assertTrue(sawDeadBlind, "Expected a dead-blind post action combining both parts");
    }

    @Test
    void takesTheFirstPostsBigBlindLineAsTheRealBigBlindDespiteACatchUpPost() throws IOException {
        ParsedHand hand = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/AllInHandWithShowdown.txt")).getFirst();

        // kiss014 posts the real BB first; dynamoz6's later "posts big blind" is a catch-up post.
        assertEquals(new BigDecimal("1"), hand.bigBlind);
        long bigBlindPosts = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.POST_BIG_BLIND).count();
        assertEquals(2, bigBlindPosts);
    }

    @Test
    void parsesTableNameWithoutASeatCountSuffix() throws IOException {
        ParsedHand hand = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("GameTypeTests/NoLimitOmaha.txt")).getFirst();

        assertEquals("Goiania", hand.tableName);
        assertEquals(0, hand.maxSeats, "No 'N Max' suffix in this fixture's table line");
    }

    @Test
    void recognizesWhiteLabelBrandsOnTheSameNetwork() throws IOException {
        ParsedHand cassava = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("GameTypeTests/NoLimitOmaha.txt")).getFirst();
        ParsedHand luckyAce = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("GeneralHands/HeroName.txt")).getFirst();

        assertEquals("184089742", cassava.handId);
        assertEquals("5512058461", luckyAce.handId);
        assertEquals("PAC_Hero", luckyAce.heroName);
    }

    @Test
    void parsesExactPlayerAndAmountOnKeyActions() throws IOException {
        ParsedHand hand = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("GameTypeTests/PotLimitOmahaHiLo.txt")).getFirst();

        var smallBlind = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.POST_SMALL_BLIND).findFirst().orElseThrow();
        assertEquals("vallemos50", smallBlind.player());
        assertEquals(new BigDecimal("0.50"), smallBlind.amount());

        var fold = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.FOLD).findFirst().orElseThrow();
        assertEquals("vallemos50", fold.player());
        assertEquals(null, fold.amount());

        // hank1967 raises [$2] — already the cumulative total for the street, not an increment
        // (verified against selkoe's own subsequent "calls [$1]": selkoe had posted the $1 big
        // blind, so a $1 call means hank1967's total commitment is $1 + $1 = $2, matching the
        // raise's stated amount exactly).
        var raise = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.RAISE).findFirst().orElseThrow();
        assertEquals("hank1967", raise.player());
        assertEquals(new BigDecimal("2"), raise.amount());

        var collected = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.COLLECTED).findFirst().orElseThrow();
        assertEquals("selkoe", collected.player());
        assertEquals(new BigDecimal("8.08"), collected.amount());
    }

    @Test
    void parsesAnIncompleteHandWithNoSummarySectionAtAll() throws IOException {
        ParsedHand hand = Poker888Parser.parseFile(
                FIXTURES_DIR.resolve("ValidHandTests/InValidHand.txt")).getFirst();

        assertTrue(hand.unparsedLines.isEmpty());
        assertEquals(BigDecimal.ZERO, hand.totalPot, "Hand never reaches a 'collected' line");
        assertNotNull(hand.smallBlind, "Blinds are still posted before the hand cuts off");
    }
}
