package com.pokeranalytics.importing.parser.winamax;

import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.HandFormat;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.Seat;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Validates {@link WinamaxTournamentParser} against real Winamax hand histories kept in
 * {@code backend/src/main/resources/hand-history/winamax/}: one Expresso (3-max, no-ante fast
 * structure, under {@code expresso/}) and four regular knockout tournaments ("TRIDENT SPACE KO",
 * "ROTATION", "SPACE KO", "QUARK" — antes from level 1, per-seat real-money bounties, and
 * main/side pot splits, under {@code mtt/}). "QUARK" is a complete 925-entrant tournament played
 * start to finish for a 1st-place win (contributed by a friend of the user, danirey300),
 * including the progressive-bounty stack growth and the final heads-up hand. The "_summary.txt"
 * files alongside them are a different, non-hand-history document (Winamax's end-of-tournament
 * results record: buy-in breakdown, level structure, finishing place) and are out of scope for
 * this parser.
 */
class WinamaxTournamentParserRealDataTest {

    private static final Path EXPRESSO_DIR =
            Paths.get("src", "main", "resources", "hand-history", "winamax", "expresso");
    private static final Path MTT_DIR =
            Paths.get("src", "main", "resources", "hand-history", "winamax", "mtt");

    private static List<Path> handHistoryFiles() throws IOException {
        assertTrue(Files.isDirectory(EXPRESSO_DIR),
                "Expected sample hand histories at " + EXPRESSO_DIR.toAbsolutePath()
                        + " -- run tests from the backend/ directory");
        assertTrue(Files.isDirectory(MTT_DIR),
                "Expected sample hand histories at " + MTT_DIR.toAbsolutePath()
                        + " -- run tests from the backend/ directory");

        List<Path> files = new ArrayList<>();
        for (Path dir : List.of(EXPRESSO_DIR, MTT_DIR)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
                for (Path file : stream) {
                    if (!file.getFileName().toString().endsWith("_summary.txt")) {
                        files.add(file);
                    }
                }
            }
        }
        return files;
    }

    private static List<ParsedHand> parseAllSampleHands() throws IOException {
        List<ParsedHand> all = new ArrayList<>();
        for (Path file : handHistoryFiles()) {
            all.addAll(WinamaxTournamentParser.parseFile(file));
        }
        return all;
    }

    @Test
    void parsesAllRealHandsWithoutLeavingUnrecognizedLines() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        assertFalse(hands.isEmpty(), "Expected at least one parsed hand from the sample files");
        System.out.println("Parsed " + hands.size() + " real Winamax tournament/Expresso hands");

        for (ParsedHand hand : hands) {
            assertTrue(hand.unparsedLines.isEmpty(),
                    "Hand #" + hand.handId + " has unrecognized lines: " + hand.unparsedLines);
            assertNotNull(hand.handId, "Every hand must have an id");
            assertFalse(hand.seats.isEmpty(), "Hand #" + hand.handId + " has no seats");
            assertNotNull(hand.totalPot, "Hand #" + hand.handId + " has no total pot");
        }
    }

    @Test
    void classifiesExpressoSeparatelyFromRegularTournaments() throws IOException {
        List<ParsedHand> hands = parseAllSampleHands();

        boolean sawExpresso = hands.stream().anyMatch(h -> h.format == HandFormat.EXPRESSO);
        boolean sawTournament = hands.stream().anyMatch(h -> h.format == HandFormat.TOURNAMENT);
        assertTrue(sawExpresso, "Expected at least one EXPRESSO hand");
        assertTrue(sawTournament, "Expected at least one regular TOURNAMENT hand");

        ParsedHand expressoHand = hands.stream().filter(h -> h.format == HandFormat.EXPRESSO).findFirst().orElseThrow();
        assertEquals("Expresso", expressoHand.tournamentName);
        assertEquals(3, expressoHand.maxSeats);
        assertEquals("", expressoHand.currency);
    }

    @Test
    void parsesExpressoWithNoAnteAtLevelOne() throws IOException {
        ParsedHand hand = WinamaxTournamentParser.parseFile(
                EXPRESSO_DIR.resolve("20250904_Expresso(985289970)_real_holdem_no-limit.txt")).getFirst();

        assertEquals(HandFormat.EXPRESSO, hand.format);
        assertEquals(1, hand.tournamentLevel);
        assertEquals("1.86€ + 0.14€", hand.buyIn);
        assertNull(hand.ante, "Expresso level 1 has no ante");
        assertEquals(new BigDecimal("10"), hand.smallBlind);
        assertEquals(new BigDecimal("20"), hand.bigBlind);

        boolean anySeatHasBounty = hand.seats.stream().anyMatch(s -> s.bounty() != null);
        assertFalse(anySeatHasBounty, "Expresso (sitngo, not knockout) shouldn't show seat bounties");
    }

    @Test
    void parsesKnockoutTournamentAntesAndBounties() throws IOException {
        ParsedHand hand = WinamaxTournamentParser.parseFile(
                MTT_DIR.resolve("20250911_TRIDENT SPACE KO(975836438)_real_holdem_no-limit.txt")).getFirst();

        assertEquals(HandFormat.TOURNAMENT, hand.format);
        assertEquals("TRIDENT SPACE KO", hand.tournamentName);
        assertEquals(new BigDecimal("3"), hand.ante);
        assertEquals(new BigDecimal("10"), hand.smallBlind);
        assertEquals(new BigDecimal("20"), hand.bigBlind);

        Seat bountySeat = hand.seats.stream().filter(s -> s.playerName().equals("Zyglox")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("1"), bountySeat.bounty());
        assertEquals(new BigDecimal("500"), bountySeat.stack());

        boolean sawAntePost = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.POST_ANTE);
        assertTrue(sawAntePost, "Expected ante-posting actions from level 1");
    }

    @Test
    void parsesMainAndSidePotSplitInRotationTournament() throws IOException {
        List<ParsedHand> hands = WinamaxTournamentParser.parseFile(
                MTT_DIR.resolve("20250912_ROTATION(976530875)_real_holdem_no-limit.txt"));
        ParsedHand hand = hands.stream()
                .filter(h -> "4194168171659264024-18-1757680582".equals(h.handId))
                .findFirst()
                .orElseThrow();

        assertEquals(new BigDecimal("86787"), hand.totalPot);

        boolean sawMainPot = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.COLLECTED_MAIN_POT);
        boolean sawSidePot = hand.actions.stream().anyMatch(a -> a.type() == HandActionType.COLLECTED_SIDE_POT);
        assertTrue(sawMainPot, "Expected a main-pot collection action");
        assertTrue(sawSidePot, "Expected a side-pot collection action");
    }

    @Test
    void parsesSpaceKoShowdownAndTableReseat() throws IOException {
        List<ParsedHand> hands = WinamaxTournamentParser.parseFile(
                MTT_DIR.resolve("20250912_SPACE KO(976529424)_real_holdem_no-limit.txt"));

        assertEquals(20, hands.size());

        boolean sawShows = hands.stream()
                .flatMap(h -> h.actions.stream())
                .anyMatch(a -> a.type() == HandActionType.SHOWS);
        assertTrue(sawShows, "Expected at least one SHOWS action across the file");

        // A player busts and a new one joins mid-file at the same table id -- confirms table/seat
        // state doesn't leak between hands.
        boolean sawReplacementPlayer = hands.stream()
                .anyMatch(h -> h.seats.stream().anyMatch(s -> s.playerName().equals("I_Need-Money")));
        assertTrue(sawReplacementPlayer, "Expected the late-joining replacement player to appear");
    }

    @Test
    void parsesFullTournamentThroughHeadsUpVictory() throws IOException {
        List<ParsedHand> hands = WinamaxTournamentParser.parseFile(
                MTT_DIR.resolve("20260519_QUARK(1098224169)_real_holdem_no-limit.txt"));

        assertFalse(hands.isEmpty());
        for (ParsedHand hand : hands) {
            assertEquals(HandFormat.TOURNAMENT, hand.format);
            assertEquals("QUARK", hand.tournamentName);
        }

        // A knockout's per-seat bounty grows as the winner eliminates opponents -- confirmed here
        // across two hands late in the file rather than assumed, since the parser only ever reads
        // whatever bounty figure the room states on that hand's own seat line.
        Seat earlierBounty = hands.stream()
                .filter(h -> "4716836889531777125-62-1779218109".equals(h.handId))
                .findFirst().orElseThrow()
                .seats.stream().filter(s -> s.playerName().equals("daniirey300")).findFirst().orElseThrow();
        Seat laterBounty = hands.stream()
                .filter(h -> "4716836889531777125-63-1779218160".equals(h.handId))
                .findFirst().orElseThrow()
                .seats.stream().filter(s -> s.playerName().equals("daniirey300")).findFirst().orElseThrow();
        assertEquals(new BigDecimal("29.99"), earlierBounty.bounty());
        assertEquals(new BigDecimal("110.81"), laterBounty.bounty());

        // The final hand of the file is the heads-up hand that wins the tournament.
        ParsedHand finalHand = hands.getLast();
        assertEquals(2, finalHand.seats.size(), "Final hand should be heads-up");
        assertTrue(finalHand.actions.stream()
                        .anyMatch(a -> a.type() == HandActionType.COLLECTED && a.rawLine().startsWith("daniirey300 collected")),
                "Expected daniirey300 to collect the final pot");

        // Confirms the earlier observation still holds against a complete tournament played to
        // the win: Winamax's hand-history text never announces an elimination, a bounty
        // collection, or a tournament finish -- those only ever show up in the separate
        // "_summary.txt" document, out of scope for this parser.
        boolean sawBountyOrFinishAction = hands.stream()
                .flatMap(h -> h.actions.stream())
                .anyMatch(a -> a.type() == HandActionType.WINS_BOUNTY || a.type() == HandActionType.TOURNAMENT_FINISH);
        assertFalse(sawBountyOrFinishAction,
                "Winamax tournament hand histories don't carry elimination/finish announcements");
    }
}
