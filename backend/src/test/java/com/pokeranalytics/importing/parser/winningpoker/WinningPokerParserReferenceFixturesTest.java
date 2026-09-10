package com.pokeranalytics.importing.parser.winningpoker;

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
}
