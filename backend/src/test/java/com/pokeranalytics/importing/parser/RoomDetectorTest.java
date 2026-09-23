package com.pokeranalytics.importing.parser;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Confirms {@link RoomDetector} correctly tells apart every cash room's hand-history shape using
 * real fixture files already relied on by each room's own parser tests, including the one genuine
 * ambiguity (PartyPoker/888 share the same "***** ... Hand History for Game N *****" wrapper).
 */
class RoomDetectorTest {

    @Test
    void detectsPokerStars() throws IOException {
        assertEquals("PokerStars", RoomDetector.detect(
                Paths.get("src", "test", "resources", "hand-history", "pokerstars", "ExtraHands", "MucksHand.txt")));
    }

    @Test
    void detectsWinamax() throws IOException {
        assertEquals("Winamax", RoomDetector.detect(
                Paths.get("src", "test", "resources", "hand-history", "winamax", "Tables", "Table1.txt")));
    }

    @Test
    void detectsPartyPokerNotConfusedWith888() throws IOException {
        assertEquals("PartyPoker", RoomDetector.detect(
                Paths.get("src", "test", "resources", "hand-history", "partypoker", "GameTypeTests", "PotLimitOmaha.txt")));
    }

    @Test
    void detects888NotConfusedWithPartyPoker() throws IOException {
        assertEquals("888", RoomDetector.detect(
                Paths.get("src", "test", "resources", "hand-history", "poker888", "GameTypeTests", "NoLimitHoldem.txt")));
    }

    @Test
    void detectsIPokerXml() throws IOException {
        assertEquals("iPoker", RoomDetector.detect(
                Paths.get("src", "test", "resources", "hand-history", "ipoker", "GameTypeTests", "PotLimitOmaha.txt")));
    }

    @Test
    void detectsWinningPokerNetwork() throws IOException {
        assertEquals("WinningPoker", RoomDetector.detect(
                Paths.get("src", "test", "resources", "hand-history", "winningpoker", "HandActionTests",
                        "AllInHandWithShowdown.txt")));
    }

    @Test
    void throwsOnUnrecognizableContent() {
        assertThrows(IllegalArgumentException.class, () -> RoomDetector.detect("not a hand history at all"));
    }
}
