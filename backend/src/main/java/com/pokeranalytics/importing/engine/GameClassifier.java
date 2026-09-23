package com.pokeranalytics.importing.engine;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Classifies a room's free-text game-type string (e.g. {@code ParsedHand.gameType}, "Hold'em No
 * Limit") into the normalized {@code game_family_t}/{@code bet_limit_t} enum values
 * docs/09-data-model.md's {@code hands} table stores, per the classification gap that document's
 * Open Questions flagged ("one rule per room's known text patterns").
 *
 * <p>Covers every room's actual phrasing, not just PokerStars' full words ("Hold'em No Limit"):
 * Winamax/888 also spell it out in full ("Holdem no limit", "Pot Limit Omaha"), but PartyPoker and
 * iPoker abbreviate to a standalone "NL"/"PL"/"L" token ("NL Texas Hold'em", "Holdem NL
 * $0.05/$0.10", "Holdem L $3/$6" for fixed limit) — {@link #classifyLimit} checks both shapes.
 * Winning Poker Network's own header never states a limit type at all (its game-type text is just
 * "(Hold'em)"/"(Omaha)") — real absence in the room's text, not a parsing gap — so that case
 * defaults to {@code NO_LIMIT}, the overwhelmingly dominant real-world case for this network's
 * cash tables, rather than silently landing on the old fallback ({@code FIXED_LIMIT}, actively
 * less likely to be right); see {@code docs/11-development-tracking.md} for this documented call.
 */
public final class GameClassifier {

    private GameClassifier() {
    }

    public static String classifyFamily(String gameTypeRaw) {
        String s = gameTypeRaw.toLowerCase(Locale.ROOT);
        if (s.contains("omaha")) {
            // 888's own Hi/Lo phrasing glues the tag directly onto the family name with no
            // separator at all ("Pot Limit OmahaHL"), unlike every other room's "Hi/Lo"/"Hi-Lo".
            boolean hiLo = s.contains("hi/lo") || s.contains("hi-lo") || s.contains("hilo") || s.contains("omahahl");
            return hiLo ? "OMAHA_HILO" : "OMAHA";
        }
        return "HOLDEM";
    }

    public static String classifyLimit(String gameTypeRaw) {
        String s = gameTypeRaw.toLowerCase(Locale.ROOT);
        if (s.contains("cap")) {
            return "CAP";
        }
        if (s.contains("no limit") || containsWord(s, "nl")) {
            return "NO_LIMIT";
        }
        if (s.contains("pot limit") || containsWord(s, "pl")) {
            return "POT_LIMIT";
        }
        if (s.contains("fixed limit") || containsWord(s, "l")) {
            return "FIXED_LIMIT";
        }
        return "NO_LIMIT";
    }

    private static boolean containsWord(String s, String word) {
        return Pattern.compile("\\b" + word + "\\b").matcher(s).find();
    }
}
