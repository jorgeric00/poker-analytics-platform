package com.pokeranalytics.importing.parser.winningpoker;

import com.pokeranalytics.importing.model.HandAction;
import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.Street;
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
 * Validates {@link WinningPokerParser} against a curated set of hand histories ported from the
 * MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference (see
 * {@code THIRD-PARTY-NOTICES.md}), covering cash-game edge cases: player names containing spaces,
 * periods, hyphens and parentheses, straddles, dead-blind catch-up posts, all-ins, an anonymized
 * empty-name system line, and a mucked-but-won hand.
 */
class WinningPokerParserReferenceFixturesTest {

    private static final Path FIXTURES_DIR = Paths.get("src", "test", "resources", "hand-history", "winningpoker");

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
            List<ParsedHand> hands = WinningPokerParser.parseFile(file);
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
    void parsesPlayerNamesWithSpacesAndPunctuation() throws IOException {
        ParsedHand hand = WinningPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/StrangePlayerNames.txt")).getFirst();

        boolean sawSpacedName = hand.seats.stream().anyMatch(s -> s.playerName().equals("do not-call"));
        boolean sawDottedName = hand.seats.stream().anyMatch(s -> s.playerName().equals("dont.do.it"));
        assertTrue(sawSpacedName, "Expected a seat for 'do not-call'");
        assertTrue(sawDottedName, "Expected a seat for 'dont.do.it'");

        boolean sawFoldFromSpacedName = hand.actions.stream()
                .anyMatch(a -> a.type() == HandActionType.FOLD && a.rawLine().equals("Player dont.do.it folds"));
        assertTrue(sawFoldFromSpacedName, "Expected 'dont.do.it' fold to resolve to the right name, not a prefix");
    }

    @Test
    void parsesParenthesesInAPlayerName() throws IOException {
        ParsedHand hand = WinningPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/PlayerNameWithParanthesis.txt")).getFirst();

        boolean sawSeat = hand.seats.stream().anyMatch(s -> s.playerName().equals("((((??????!!!!!!))))"));
        assertTrue(sawSeat, "Expected the parenthesis-heavy name to be captured whole");
        assertEquals(new BigDecimal("10"), hand.totalPot);
        assertEquals(new BigDecimal("0"), hand.rake);
    }

    @Test
    void parsesDeadBlindAsTwoSeparateActionsForTheSamePlayer() throws IOException {
        ParsedHand hand = WinningPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/PostingDead.txt")).getFirst();

        boolean sawDeadBlind = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.POST_DEAD_BLIND
                && a.rawLine().equals("Player TheKunttzz posts (0.25) as a dead bet"));
        boolean sawFollowUpPost = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.POSTS
                && a.rawLine().equals("Player TheKunttzz posts (0.50)"));
        assertTrue(sawDeadBlind, "Expected the tagged dead-bet line as its own action");
        assertTrue(sawFollowUpPost, "Expected the untagged follow-up post as its own action");
    }

    @Test
    void parsesAnEmptyNameSystemLineWithoutMatchingARealPlayer() throws IOException {
        ParsedHand hand = WinningPokerParser.parseFile(
                FIXTURES_DIR.resolve("PlayerTests/NoNameSitoutLine.txt")).getFirst();

        assertTrue(hand.unparsedLines.isEmpty());
        boolean anyEmptyNameSeat = hand.seats.stream().anyMatch(s -> s.playerName().isEmpty());
        assertFalse(anyEmptyNameSeat, "The anonymized line's empty name is not a real seated player");
    }

    @Test
    void mucksButStillWinsIsRecordedAsAMucksActionWithASummarySeat() throws IOException {
        ParsedHand hand = WinningPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/WaitBB.txt")).getFirst();

        boolean sawMuckWin = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.MUCKS
                && a.rawLine().contains("888game888"));
        assertTrue(sawMuckWin, "Expected the winner's 'mucks (does not show cards)' line to be recorded");
        boolean sawSummarySeat = hand.summarySeats.stream().anyMatch(s -> s.playerName().equals("888game888"));
        assertTrue(sawSummarySeat, "Expected the summary outcome to be mapped back to its seat number");
    }

    @Test
    void parsesAnIncompleteHandWithNoGameEndedLine() throws IOException {
        ParsedHand hand = WinningPokerParser.parseFile(
                FIXTURES_DIR.resolve("ValidHandTests/InValidHand.txt")).getFirst();

        assertTrue(hand.unparsedLines.isEmpty());
        assertNotNull(hand.smallBlind);
    }

    @Test
    void raiseAndAllInAmountsAreConvertedFromIncrementToCumulativeStreetTotal() throws IOException {
        // Unlike every other room ported so far, this room's "raises (X)"/"allin (X)" wording
        // states the increment on top of the player's own prior contribution that street, not a
        // cumulative "to Y" total — this fixture (2/4 blinds, three preflop raises then a flop
        // bet/all-in/call) is exactly the multi-raise, multi-street case that distinguishes the
        // two interpretations, cross-checked by hand against the following calls and the summary's
        // own final "Bets:" totals.
        ParsedHand hand = WinningPokerParser.parseFile(
                FIXTURES_DIR.resolve("HandActionTests/AllInHandWithShowdown.txt")).getFirst();

        HandAction firstRaise = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.RAISE && "do not-call".equals(a.player()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("12"), firstRaise.amount(), "SB(2) + raises(10) should be cumulative 12");

        HandAction secondRaise = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.RAISE && "digbick30".equals(a.player()))
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("36"), secondRaise.amount(), "BB(4) + raises(32) should be cumulative 36");

        HandAction call = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.CALL && "do not-call".equals(a.player())
                        && a.street() == Street.PREFLOP)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("24"), call.amount(), "36 (raiser's total) - 12 (caller's own prior) = 24");

        HandAction allIn = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.ALL_IN)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("124"), allIn.amount(), "fresh flop street: 0 + allin(124) is cumulative 124");

        HandAction flopCall = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.CALL && a.street() == Street.FLOP)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("53"), flopCall.amount(), "124 (all-in total) - 71 (caller's own bet) = 53");

        HandAction collected = hand.actions.stream()
                .filter(a -> a.type() == HandActionType.COLLECTED)
                .findFirst().orElseThrow();
        assertEquals("do not-call", collected.player());
        assertEquals(new BigDecimal("319"), collected.amount(), "summary's own Collects: value, synthesized here"
                + " since this room never states a plain collected-from-pot action line");
    }
}
