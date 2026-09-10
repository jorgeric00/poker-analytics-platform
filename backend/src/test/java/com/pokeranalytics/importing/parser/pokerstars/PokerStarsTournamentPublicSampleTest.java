package com.pokeranalytics.importing.parser.pokerstars;

import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.HandFormat;
import com.pokeranalytics.importing.model.ParsedHand;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md} and
 * {@code docs/decisions/ADR-007-pokerstars-tournament-without-reference-fixtures.md}.
 *
 * Unlike every other fixture-backed test in this module, these two hand histories are NOT ported
 * from the {@code HHSmithy/PokerHandHistoryParser} reference (it ships no PokerStars tournament
 * sample data at all) — they're real, verbatim hands published on Wikipedia's "Hand history"
 * article, used here as the closest thing to independent validation data available for this
 * format. See {@link PokerStarsParser}'s class doc for the full provenance and the known
 * Spin&amp;Go-detection gap.
 */
class PokerStarsTournamentPublicSampleTest {

    private static final Path FIXTURES_DIR =
            Paths.get("src", "test", "resources", "hand-history", "pokerstars", "Tournament");

    @Test
    void parsesLevelBuyInAndAnteFromTheTournamentHeader() throws IOException {
        ParsedHand hand = PokerStarsParser.parseFile(
                FIXTURES_DIR.resolve("KnockoutWithBountyAndFinish.txt")).getFirst();

        assertEquals(HandFormat.TOURNAMENT, hand.format);
        assertEquals("$2.00+$2.00+$0.40 USD", hand.buyIn);
        assertEquals(18, hand.tournamentLevel);
        assertEquals(new BigDecimal("300"), hand.smallBlind);
        assertEquals(new BigDecimal("600"), hand.bigBlind);

        long anteCount = hand.actions.stream().filter(a -> a.type() == HandActionType.POST_ANTE).count();
        assertEquals(9, anteCount, "One ante post per seated player");
    }

    @Test
    void parsesTheKnockoutBountyAndFinishPlaceLines() throws IOException {
        ParsedHand hand = PokerStarsParser.parseFile(
                FIXTURES_DIR.resolve("KnockoutWithBountyAndFinish.txt")).getFirst();

        boolean sawBounty = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.WINS_BOUNTY
                && a.rawLine().contains("eliminating Hero"));
        boolean sawFinish = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.TOURNAMENT_FINISH
                && a.rawLine().contains("5028th place"));
        assertTrue(sawBounty, "Expected a bounty-collection action");
        assertTrue(sawFinish, "Expected a tournament-finish action");
        assertEquals(new BigDecimal("18010"), hand.totalPot);
    }

    @Test
    void parsesOlderGameHeaderWithNumberedSidePotsAndAReturnedPlayer() throws IOException {
        ParsedHand hand = PokerStarsParser.parseFile(
                FIXTURES_DIR.resolve("MultiWayAllInWithSidePots.txt")).getFirst();

        assertEquals("27738502010", hand.handId);
        assertEquals(new BigDecimal("74303"), hand.totalPot);
        assertEquals(new BigDecimal("34212"), hand.mainPot);
        assertEquals(2, hand.sidePots.size());
        assertEquals(new BigDecimal("29073"), hand.sidePots.get(0));
        assertEquals(new BigDecimal("11018"), hand.sidePots.get(1));

        boolean sawReconnect = hand.actions.stream()
                .anyMatch(a -> a.type() == HandActionType.CONNECTION_STATUS && a.rawLine().contains("has returned"));
        assertTrue(sawReconnect, "Expected '618shooter has returned' to be recognized");
    }
}
