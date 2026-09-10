package com.pokeranalytics.importing.parser.poker888;

import com.pokeranalytics.importing.model.HandAction;
import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.Seat;
import com.pokeranalytics.importing.model.Street;
import com.pokeranalytics.importing.parser.HandHistoryFiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Parses 888/Pacific cash-game hand histories (Hold'em/Omaha/Omaha Hi-Lo, No/Pot/Fixed Limit).
 * "888" is the room's current consumer brand; "Pacific" is the underlying network name the
 * reference library uses, and this format is also shared by white-label skins on that same
 * network (e.g. "Cassava", "LuckyAcePoker.com" appear in the header line in place of "888poker" —
 * see {@link #HEADER_ID}). Behavior and edge cases are based on the MIT-licensed
 * {@code HHSmithy/PokerHandHistoryParser} reference (see {@code THIRD-PARTY-NOTICES.md}),
 * re-implemented as a Java regex/state-machine parser rather than a line-by-line translation.
 *
 * Notable quirks versus the other rooms ported so far:
 * <ul>
 *   <li>street markers ("** Dealing flop/turn/river **") use lowercase street names, unlike
 *       PartyPoker's capitalized "** Dealing Flop **"</li>
 *   <li>a late/re-entering player can catch up with its own separate "posts big blind [...]" line
 *       (not "posts dead blind" like the room's actual dead-blind wording) — indistinguishable from
 *       the hand's real big blind by wording alone, so only the first such line in a hand is taken
 *       as {@code bigBlind} (it always precedes any catch-up posts in every sample seen); every
 *       occurrence is still recorded as its own {@link HandActionType#POST_BIG_BLIND} action</li>
 *   <li>a real dead blind is instead written as "posts dead blind [$X + $Y]" (small+big combined in
 *       one bracket) — {@link HandActionType#POST_DEAD_BLIND}, amount is the sum of both parts</li>
 *   <li>the room never states all-in explicitly (no "is all-in" suffix or verb anywhere in the
 *       sample fixtures) — ordinary bet/call/raise actions cover it, matching the reference's own
 *       {@code RequiresAllInDetection} flag (all-in is inferred from stack depletion downstream,
 *       not from the hand-history text itself)</li>
 *   <li>no "main pot"/"side pot" wording anywhere, even in multi-way all-ins — every winner line is
 *       a plain "PlayerName collected [ $X ]"; a Hi/Lo hand simply repeats the same player's
 *       "collected" line twice (once per half-pot), which sums correctly since each is its own
 *       action</li>
 *   <li>the table line only sometimes carries a seat-count suffix ("Table X 6 Max (Real Money)"
 *       vs. plain "Table X (Real Money)"); {@code maxSeats} is left 0 when it's absent</li>
 * </ul>
 *
 * Two reference sample files under {@code Pacific/CashGame} were excluded when porting fixtures:
 * {@code GameTypeTests/PotLimitHoldem.txt} and {@code PlayerTests/WithSittingOut.txt} are a
 * different room's grammar entirely (PartyPoker-shaped header/action lines, e.g. "Total number of
 * players : 6/6" and "wins $150 USD" with no brackets) — confirmed as unused test fixtures by the
 * reference library's own test suite, which explicitly ignores those cases for Pacific.
 *
 * Scope: cash games only — see the open follow-ups in {@code docs/11-development-tracking.md}.
 */
public final class Poker888Parser {

    private static final Pattern HAND_HEADER = Pattern.compile("^\\*{5}.*Hand History for Game \\d+ \\*{5}$");
    private static final Pattern GAME_NO_MARKER = Pattern.compile("^#Game No\\s*:\\s*(\\d+)\\s*$");
    private static final Pattern HEADER_ID = Pattern.compile("^\\*{5}.*Hand History for Game (\\d+) \\*{5}$");
    // "$0.50/$1 Blinds No Limit Holdem - *** 06 01 2014 13:39:55" or, with a Jackpot table,
    // "...No Limit Holdem Jackpot table - *** ...". The leading stakes token is intentionally not
    // captured — smallBlind/bigBlind come from the actual blind-post actions instead (more
    // precise, and avoids re-parsing stakes tokens in at least three different shapes: "$0.50/$1",
    // "$100/$200", "25 $/50 $").
    private static final Pattern HEADER_INFO = Pattern.compile("^.+ Blinds (.+) - \\*\\*\\* (.+)$");
    private static final Pattern TABLE_WITH_MAX = Pattern.compile("^Table (.+) (\\d+) Max \\(.*\\)$");
    private static final Pattern TABLE_NO_MAX = Pattern.compile("^Table (.+) \\(.*\\)$");
    private static final Pattern BUTTON = Pattern.compile("^Seat (\\d+) is the button$");
    private static final Pattern NUM_PLAYERS = Pattern.compile("^Total number of players\\s*:\\s*\\d+\\s*$");
    private static final Pattern SEAT = Pattern.compile("^Seat (\\d+): (.+?) \\( (\\S+) \\)$");

    private static final Pattern POST_DEAD_BLIND =
            Pattern.compile("^(.+?) posts dead blind \\[(\\S+) \\+ (\\S+)\\]$");
    private static final Pattern POST_SB = Pattern.compile("^(.+?) posts small blind \\[(\\S+)\\]$");
    private static final Pattern POST_BB = Pattern.compile("^(.+?) posts big blind \\[(\\S+)\\]$");

    private static final Pattern DEALING_DOWN_MARKER = Pattern.compile("^\\*\\* Dealing down cards \\*\\*$");
    private static final Pattern DEALT = Pattern.compile("^Dealt to (.+?) \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern FLOP = Pattern.compile("^\\*\\* Dealing flop \\*\\* \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern TURN = Pattern.compile("^\\*\\* Dealing turn \\*\\* \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern RIVER = Pattern.compile("^\\*\\* Dealing river \\*\\* \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern SUMMARY_MARKER = Pattern.compile("^\\*\\* Summary \\*\\*$");

    private static final Pattern ACTION_FOLD = Pattern.compile("^(.+?) folds$");
    private static final Pattern ACTION_CHECK = Pattern.compile("^(.+?) checks$");
    private static final Pattern ACTION_CALL = Pattern.compile("^(.+?) calls \\[(\\S+)\\]$");
    private static final Pattern ACTION_BET = Pattern.compile("^(.+?) bets \\[(\\S+)\\]$");
    private static final Pattern ACTION_RAISE = Pattern.compile("^(.+?) raises \\[(\\S+)\\]$");

    // Pronoun varies ("his"/"her"/"their") depending on the player's profile settings.
    private static final Pattern DOESNT_SHOW = Pattern.compile("^(.+?) did not show \\S+ hand$");
    private static final Pattern MUCKS = Pattern.compile("^(.+?) mucks \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern SHOWS = Pattern.compile("^(.+?) shows \\[\\s*(.+?)\\s*\\]$");
    // Omaha Hi/Lo hand-description lines following a "shows", e.g. "(Hi: 5c, 5d, Th, Kd, As)".
    private static final Pattern HI_LO_DESCRIPTION = Pattern.compile("^\\((?:Hi|Lo): .+\\)$");
    private static final Pattern COLLECTED = Pattern.compile("^(.+?) collected \\[\\s*(\\S+)\\s*\\]$");

    private Poker888Parser() {
    }

    public static List<ParsedHand> parseFile(Path path) throws IOException {
        return parse(HandHistoryFiles.readNormalized(path));
    }

    public static List<ParsedHand> parse(String content) {
        List<ParsedHand> hands = new ArrayList<>();
        String[] lines = content.split("\\r\\n|\\r|\\n");
        List<String> block = new ArrayList<>();

        for (String line : lines) {
            // Every hand is prefixed by its own "#Game No : ID" bookkeeping line before the real
            // "***** ... Hand History ... *****" header; without this guard that leading line
            // would get flushed as a bogus header-less "hand" of its own on the very first split.
            if (HAND_HEADER.matcher(line.trim()).matches() && !block.isEmpty()) {
                if (block.stream().anyMatch(l -> HAND_HEADER.matcher(l.trim()).matches())) {
                    hands.add(parseHand(block));
                }
                block = new ArrayList<>();
            }
            block.add(line);
        }
        if (block.stream().anyMatch(l -> HAND_HEADER.matcher(l.trim()).matches())) {
            hands.add(parseHand(block));
        }
        return hands;
    }

    private static ParsedHand parseHand(List<String> rawLines) {
        ParsedHand hand = new ParsedHand();
        hand.siteName = "888";
        hand.currency = "USD";
        Street street = Street.PREFLOP;
        BigDecimal totalPot = BigDecimal.ZERO;

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher m;

            if ((m = GAME_NO_MARKER.matcher(line)).matches()) {
                hand.handId = m.group(1);
            } else if ((m = HEADER_ID.matcher(line)).matches()) {
                hand.handId = m.group(1);
            } else if ((m = HEADER_INFO.matcher(line)).matches()) {
                hand.gameType = m.group(1).trim();
                hand.timestampRaw = m.group(2).trim();
            } else if ((m = TABLE_WITH_MAX.matcher(line)).matches()) {
                hand.tableName = m.group(1).trim();
                hand.maxSeats = Integer.parseInt(m.group(2));
            } else if ((m = TABLE_NO_MAX.matcher(line)).matches()) {
                hand.tableName = m.group(1).trim();
            } else if ((m = BUTTON.matcher(line)).matches()) {
                hand.buttonSeat = Integer.parseInt(m.group(1));
            } else if (NUM_PLAYERS.matcher(line).matches()) {
                // actual seated-player count, not the table's max seats — nothing to extract
            } else if ((m = SEAT.matcher(line)).matches()) {
                hand.seats.add(new Seat(Integer.parseInt(m.group(1)), m.group(2).trim(),
                        parseAmount(m.group(3)), "USD", null));
            } else if ((m = POST_DEAD_BLIND.matcher(line)).matches()) {
                hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_DEAD_BLIND, line));
            } else if ((m = POST_SB.matcher(line)).matches()) {
                if (hand.smallBlind == null) {
                    hand.smallBlind = parseAmount(m.group(2));
                }
                hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_SMALL_BLIND, line));
            } else if ((m = POST_BB.matcher(line)).matches()) {
                // First "posts big blind" in a hand is always the real one; a late/re-entering
                // player's own catch-up post (worded identically, not as "posts dead blind") comes
                // later in every sample seen — see the class doc.
                if (hand.bigBlind == null) {
                    hand.bigBlind = parseAmount(m.group(2));
                }
                hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_BIG_BLIND, line));
            } else if (DEALING_DOWN_MARKER.matcher(line).matches()) {
                // section marker only, nothing to extract
            } else if ((m = DEALT.matcher(line)).matches()) {
                hand.heroName = m.group(1).trim();
                hand.heroHoleCards = List.of(m.group(2).replace(",", "").trim().split("\\s+"));
            } else if ((m = FLOP.matcher(line)).matches()) {
                street = Street.FLOP;
                hand.board(0).addAll(List.of(m.group(1).replace(",", "").trim().split("\\s+")));
            } else if ((m = TURN.matcher(line)).matches()) {
                street = Street.TURN;
                hand.board(0).add(m.group(1).replace(",", "").trim());
            } else if ((m = RIVER.matcher(line)).matches()) {
                street = Street.RIVER;
                hand.board(0).add(m.group(1).replace(",", "").trim());
            } else if (SUMMARY_MARKER.matcher(line).matches()) {
                street = Street.SHOWDOWN;
            } else if (ACTION_FOLD.matcher(line).matches()) {
                hand.actions.add(new HandAction(street, HandActionType.FOLD, line));
            } else if (ACTION_CHECK.matcher(line).matches()) {
                hand.actions.add(new HandAction(street, HandActionType.CHECK, line));
            } else if (ACTION_CALL.matcher(line).matches()) {
                hand.actions.add(new HandAction(street, HandActionType.CALL, line));
            } else if (ACTION_BET.matcher(line).matches()) {
                hand.actions.add(new HandAction(street, HandActionType.BET, line));
            } else if (ACTION_RAISE.matcher(line).matches()) {
                hand.actions.add(new HandAction(street, HandActionType.RAISE, line));
            } else if (DOESNT_SHOW.matcher(line).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.DOESNT_SHOW, line));
            } else if (MUCKS.matcher(line).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.MUCKS, line));
            } else if (SHOWS.matcher(line).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.SHOWS, line));
            } else if (HI_LO_DESCRIPTION.matcher(line).matches()) {
                // Omaha Hi/Lo hand-description line following a "shows" — informational only
            } else if ((m = COLLECTED.matcher(line)).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.COLLECTED, line));
                totalPot = totalPot.add(parseAmount(m.group(2)));
            } else {
                hand.unparsedLines.add(line);
            }
        }

        hand.totalPot = totalPot;
        return hand;
    }

    /** Strips the currency symbol and thousands separators, e.g. "$2,141" -> 2141. */
    private static BigDecimal parseAmount(String token) {
        String cleaned = token.replaceAll("[^0-9.]", "");
        return new BigDecimal(cleaned);
    }
}
