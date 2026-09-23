package com.pokeranalytics.importing.parser;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-detects which room's parser a hand-history file belongs to, from its own content —
 * {@code POST /api/v1/imports} has no room field by design (see {@code ImportRequest}'s own doc,
 * docs/10-api-design.md), so this is the only signal available. Each room's hand-history text has
 * its own distinctive opening shape; this only needs to tell them apart, not parse anything, so it
 * scans line by line (some rooms prefix a hand with bookkeeping lines before their real header)
 * rather than reusing any one parser's own {@code isHandHeader}.
 *
 * <p>PartyPoker and 888 share the exact same {@code "***** ... Hand History for Game N *****"}
 * shape — the only difference is 888's header always names its (white-label) brand between the
 * stars and "Hand History" ("888poker", "Cassava", ...), while PartyPoker's has nothing there at
 * all — see {@link #HAND_HISTORY_FOR_GAME}.
 *
 * <p>Scope: cash-only, matching the Import Engine's own scope — a tournament/Expresso file (a
 * different header shape even for a room whose cash format is detected here, e.g. Winamax) is
 * expected to fall through to {@link #detect}'s exception, not be silently misrouted to the wrong
 * parser.
 */
public final class RoomDetector {

    private static final Pattern HAND_HISTORY_FOR_GAME =
            Pattern.compile("^\\*{5}(.*)Hand History for Game \\d+ \\*{5}$");

    private RoomDetector() {
    }

    public static String detect(Path path) throws IOException {
        return detect(HandHistoryFiles.readNormalized(path));
    }

    public static String detect(String content) {
        if (!content.isEmpty() && content.charAt(0) == 0xFEFF) {
            content = content.substring(1);
        }
        for (String rawLine : content.split("\\r\\n|\\r|\\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("PokerStars Zoom Hand #") || line.startsWith("PokerStars Hand #")
                    || line.startsWith("PokerStars Game #")) {
                return "PokerStars";
            }
            if (line.startsWith("Winamax Poker - CashGame - HandId:")) {
                return "Winamax";
            }
            if (line.startsWith("Game started at: ")) {
                return "WinningPoker";
            }
            if (line.startsWith("<?xml") || line.startsWith("<session")) {
                return "iPoker";
            }
            Matcher m = HAND_HISTORY_FOR_GAME.matcher(line);
            if (m.matches()) {
                return m.group(1).isBlank() ? "PartyPoker" : "888";
            }
        }
        throw new IllegalArgumentException(
                "Could not detect the poker room for this hand-history file — no recognized header found");
    }
}
