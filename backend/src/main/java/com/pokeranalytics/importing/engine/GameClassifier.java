package com.pokeranalytics.importing.engine;

import java.util.Locale;

/**
 * Classifies a room's free-text game-type string (e.g. {@code ParsedHand.gameType}, "Hold'em No
 * Limit") into the normalized {@code game_family_t}/{@code bet_limit_t} enum values
 * docs/09-data-model.md's {@code hands} table stores, per the classification gap that document's
 * Open Questions flagged ("one rule per room's known text patterns"). Only PokerStars' known text
 * shapes are covered so far, matching the Import Engine's current PokerStars-only scope.
 */
public final class GameClassifier {

    private GameClassifier() {
    }

    public static String classifyFamily(String gameTypeRaw) {
        String s = gameTypeRaw.toLowerCase(Locale.ROOT);
        if (s.contains("omaha")) {
            return (s.contains("hi/lo") || s.contains("hi-lo")) ? "OMAHA_HILO" : "OMAHA";
        }
        return "HOLDEM";
    }

    public static String classifyLimit(String gameTypeRaw) {
        String s = gameTypeRaw.toLowerCase(Locale.ROOT);
        if (s.contains("cap")) {
            return "CAP";
        }
        if (s.contains("no limit")) {
            return "NO_LIMIT";
        }
        if (s.contains("pot limit")) {
            return "POT_LIMIT";
        }
        return "FIXED_LIMIT";
    }
}
