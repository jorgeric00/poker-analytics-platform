package com.pokeranalytics.importing.parser.winningpoker;

import com.pokeranalytics.importing.model.HandAction;
import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.Seat;
import com.pokeranalytics.importing.model.Street;
import com.pokeranalytics.importing.model.SummarySeat;
import com.pokeranalytics.importing.parser.HandHistoryFiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Parses Winning Poker Network cash-game hand histories (Hold'em/Omaha, No Limit/Pot Limit).
 * Behavior and edge cases are based on the MIT-licensed {@code HHSmithy/PokerHandHistoryParser}
 * reference (see {@code THIRD-PARTY-NOTICES.md}), re-implemented as a Java regex/state-machine
 * parser rather than a line-by-line translation.
 *
 * This room needed a genuinely different technique from every other room ported so far, not just
 * different regexes: its main-action-section lines ("Player &lt;name&gt; folds", "Player &lt;name&gt;
 * has small blind (2)", ...) put no delimiter between the player name and the verb, and player
 * names can themselves contain spaces ("Cellar door"), periods ("dont.do.it"), hyphens
 * ("do not-call") or parentheses ("((((??????!!!!!!))))") — a single regex can't reliably tell
 * where the name ends and the verb begins. The reference library resolves this by matching each
 * action line's prefix against the hand's own known seated-player list (longest name first, to
 * avoid one name being a prefix of another); this port does the same, in a first pass over the
 * "Seat N: name (stack)." lines before parsing any actions. The room's own summary section
 * doesn't have this problem — each line there ends in a fixed, distinctive phrase ("does not show
 * cards.", "mucks (does not show cards).", "shows: ... [cards]. Bets: ... Collects: ... Wins/Loses:
 * ...") that a plain lazy regex can anchor on, so it's parsed directly.
 *
 * Other quirks: no side pots at all (the reference has no side-pot handling for this room, and no
 * sample fixture shows one — only ever a single aggregate "Pot: N. Rake M" line); one recurring
 * anonymized/system line has a literally empty player name ("Player  is timed out.", two spaces),
 * handled as its own case since it can never match a real seated player's name; a dead blind is
 * two separate consecutive lines for the same player (its own catch-up post tagged "as a dead
 * bet", then a second untagged "posts (N)" line for the real new-hand amount) rather than one
 * combined line like 888/Pacific's "[$X + $Y]" — each is kept as its own action here rather than
 * merged, consistent with every other room in this module recording exactly what a line says.
 *
 * Scope: cash games only — see the open follow-ups in {@code docs/11-development-tracking.md}.
 */
public final class WinningPokerParser {

    private static final Pattern GAME_STARTED = Pattern.compile("^Game started at: (.+)$");
    // "Game ID: 261641541 0.10/0.25 Tantalite   (JP) (Hold'em)" — table name is everything between
    // the blinds token and the LAST parenthesized group (which may itself include a "(JP)"/"(Short)"
    // tag as part of the raw table name); the final parenthesized group is the game type.
    private static final Pattern GAME_ID = Pattern.compile("^Game ID: (\\d+) (\\S+)/(\\S+) (.+) \\(([^()]+)\\)$");
    private static final Pattern BUTTON = Pattern.compile("^Seat (\\d+) is the button$");
    private static final Pattern SEAT = Pattern.compile("^Seat (\\d+): (.+) \\(([\\d.]+)\\)\\.$");
    private static final Pattern GAME_ENDED = Pattern.compile("^Game ended at:.*$");
    private static final Pattern SUMMARY_MARKER = Pattern.compile("^-+ Summary -+$");

    private static final Pattern STREET_FLOP = Pattern.compile("^\\*\\*\\* FLOP \\*\\*\\*: \\[(.+?)\\]$");
    private static final Pattern STREET_TURN = Pattern.compile("^\\*\\*\\* TURN \\*\\*\\*: \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern STREET_RIVER = Pattern.compile("^\\*\\*\\* RIVER \\*\\*\\*: \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern UNCALLED = Pattern.compile("^Uncalled bet \\((\\S+)\\) returned to (.+)$");

    // Anonymized/system line with a literally empty player name (two spaces after "Player").
    private static final Pattern EMPTY_NAME_TIMED_OUT = Pattern.compile("^Player {2}is timed out\\.$");
    private static final Pattern EMPTY_NAME_SITTING_OUT = Pattern.compile("^Player {2}sitting out$");

    // Applied to the suffix immediately after a matched player name (leading space kept).
    private static final Pattern SUFFIX_FOLD = Pattern.compile("^ folds$");
    private static final Pattern SUFFIX_CHECK = Pattern.compile("^ checks$");
    private static final Pattern SUFFIX_CALL = Pattern.compile("^ calls \\((\\S+)\\)$");
    private static final Pattern SUFFIX_RAISE = Pattern.compile("^ raises \\((\\S+)\\)$");
    private static final Pattern SUFFIX_BET = Pattern.compile("^ bets \\((\\S+)\\)$");
    private static final Pattern SUFFIX_ALL_IN = Pattern.compile("^ allin \\((\\S+)\\)$");
    private static final Pattern SUFFIX_POST_SB = Pattern.compile("^ has small blind \\((\\S+)\\)$");
    private static final Pattern SUFFIX_POST_BB = Pattern.compile("^ has big blind \\((\\S+)\\)$");
    private static final Pattern SUFFIX_POST_DEAD = Pattern.compile("^ posts \\((\\S+)\\) as a dead bet$");
    private static final Pattern SUFFIX_POSTS = Pattern.compile("^ posts \\((\\S+)\\)$");
    private static final Pattern SUFFIX_STRADDLE = Pattern.compile("^ straddle \\((\\S+)\\)$");
    private static final Pattern SUFFIX_SITTING_OUT = Pattern.compile("^ sitting out$");
    private static final Pattern SUFFIX_WAIT_BB = Pattern.compile("^ wait BB$");
    private static final Pattern SUFFIX_TIMED_OUT = Pattern.compile("^ is timed out\\.$");
    private static final Pattern SUFFIX_RECEIVED_BLANK = Pattern.compile("^ received a card\\.$");
    private static final Pattern SUFFIX_RECEIVED_KNOWN = Pattern.compile("^ received card: \\[(.+?)\\]$");
    private static final Pattern SUFFIX_MUCKS = Pattern.compile("^ mucks cards$");

    private static final Pattern POT_RAKE = Pattern.compile("^Pot: (\\S+)\\. Rake (\\S+)$");
    private static final Pattern BOARD = Pattern.compile("^Board: \\[(.+?)\\]$");
    // "does not show cards." is glued directly to "Bets:" with no space in the room's own text —
    // a real quirk, not a typo here; the other two summary shapes do have a space there.
    private static final Pattern SUMMARY_DOESNT_SHOW =
            Pattern.compile("^(\\*)?Player (.*?) does not show cards\\.Bets: (\\S+)\\. Collects: (\\S+)\\. (?:Wins|Loses): (\\S+)\\.$");
    private static final Pattern SUMMARY_MUCKS = Pattern.compile(
            "^(\\*)?Player (.*?) mucks \\(does not show cards\\)\\. Bets: (\\S+)\\. Collects: (\\S+)\\. (?:Wins|Loses): (\\S+)\\.$");
    // Sometimes the room logs the mucked cards for the record anyway: "mucks (does not show: [10d 3s])."
    private static final Pattern SUMMARY_MUCKS_WITH_CARDS = Pattern.compile(
            "^(\\*)?Player (.*?) mucks \\(does not show: \\[(.+?)\\]\\)\\. Bets: (\\S+)\\. Collects: (\\S+)\\. (?:Wins|Loses): (\\S+)\\.$");
    private static final Pattern SUMMARY_SHOWS = Pattern.compile(
            "^(\\*)?Player (.*?) shows: (.+?) \\[(.+?)\\]\\. Bets: (\\S+)\\. Collects: (\\S+)\\. (?:Wins|Loses): (\\S+)\\.$");

    private WinningPokerParser() {
    }

    public static List<ParsedHand> parseFile(Path path) throws IOException {
        return parse(HandHistoryFiles.readNormalized(path));
    }

    public static List<ParsedHand> parse(String content) {
        List<ParsedHand> hands = new ArrayList<>();
        String[] lines = content.split("\\r\\n|\\r|\\n");
        List<String> block = new ArrayList<>();

        for (String line : lines) {
            if (isHandHeader(line) && !block.isEmpty()) {
                hands.add(parseHand(block));
                block = new ArrayList<>();
            }
            block.add(line);
        }
        if (block.stream().anyMatch(WinningPokerParser::isHandHeader)) {
            hands.add(parseHand(block));
        }
        return hands;
    }

    private static boolean isHandHeader(String line) {
        return line.startsWith("Game started at: ");
    }

    private static ParsedHand parseHand(List<String> rawLines) {
        ParsedHand hand = new ParsedHand();
        hand.siteName = "WinningPoker";
        hand.currency = "USD";

        // First pass: collect the seated-player list before parsing any action, since action
        // lines need it to tell a player's name apart from the verb that immediately follows it.
        Map<String, Integer> seatByName = new HashMap<>();
        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            Matcher m = SEAT.matcher(line);
            if (m.matches()) {
                int seatNumber = Integer.parseInt(m.group(1));
                String name = m.group(2);
                hand.seats.add(new Seat(seatNumber, name, new BigDecimal(m.group(3)), "USD", null));
                seatByName.put(name, seatNumber);
            }
        }
        List<String> namesByDescLength = new ArrayList<>(seatByName.keySet());
        namesByDescLength.sort(Comparator.comparingInt(String::length).reversed());

        Street street = Street.PREFLOP;
        boolean inSummary = false;
        List<String> heroCards = new ArrayList<>();

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher m;

            if (!inSummary) {
                if ((m = GAME_STARTED.matcher(line)).matches()) {
                    hand.timestampRaw = m.group(1).trim();
                } else if ((m = GAME_ID.matcher(line)).matches()) {
                    hand.handId = m.group(1);
                    hand.smallBlind = new BigDecimal(m.group(2));
                    hand.bigBlind = new BigDecimal(m.group(3));
                    hand.tableName = m.group(4).trim();
                    hand.gameType = m.group(5).trim();
                } else if ((m = BUTTON.matcher(line)).matches()) {
                    hand.buttonSeat = Integer.parseInt(m.group(1));
                } else if (SEAT.matcher(line).matches()) {
                    // already recorded in the first pass above
                } else if ((m = STREET_FLOP.matcher(line)).matches()) {
                    street = Street.FLOP;
                    hand.board(0).addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if ((m = STREET_TURN.matcher(line)).matches()) {
                    street = Street.TURN;
                    hand.board(0).add(m.group(1).trim());
                } else if ((m = STREET_RIVER.matcher(line)).matches()) {
                    street = Street.RIVER;
                    hand.board(0).add(m.group(1).trim());
                } else if ((m = UNCALLED.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.UNCALLED_RETURN, line));
                } else if (EMPTY_NAME_TIMED_OUT.matcher(line).matches()
                        || EMPTY_NAME_SITTING_OUT.matcher(line).matches()) {
                    // anonymized/system placeholder — recognized, not tied to any seated player
                } else if (SUMMARY_MARKER.matcher(line).matches()) {
                    inSummary = true;
                } else if (line.startsWith("Player ")) {
                    NameMatch nameMatch = matchPlayerName(line, namesByDescLength);
                    if (nameMatch == null) {
                        hand.unparsedLines.add(line);
                        continue;
                    }
                    String suffix = nameMatch.suffix;
                    if (SUFFIX_FOLD.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.FOLD, line));
                    } else if (SUFFIX_CHECK.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.CHECK, line));
                    } else if (SUFFIX_CALL.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.CALL, line));
                    } else if (SUFFIX_RAISE.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.RAISE, line));
                    } else if (SUFFIX_BET.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.BET, line));
                    } else if (SUFFIX_ALL_IN.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.ALL_IN, line));
                    } else if (SUFFIX_POST_SB.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_SMALL_BLIND, line));
                    } else if (SUFFIX_POST_BB.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_BIG_BLIND, line));
                    } else if (SUFFIX_POST_DEAD.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_DEAD_BLIND, line));
                    } else if (SUFFIX_POSTS.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POSTS, line));
                    } else if (SUFFIX_STRADDLE.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.STRADDLE, line));
                    } else if (SUFFIX_SITTING_OUT.matcher(suffix).matches()
                            || SUFFIX_WAIT_BB.matcher(suffix).matches()
                            || SUFFIX_RECEIVED_BLANK.matcher(suffix).matches()) {
                        // table/session noise — recognized, intentionally not recorded as a hand action
                    } else if (SUFFIX_TIMED_OUT.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.CONNECTION_STATUS, line));
                    } else if ((m = SUFFIX_RECEIVED_KNOWN.matcher(suffix)).matches()) {
                        hand.heroName = nameMatch.name;
                        heroCards.add(m.group(1));
                    } else if (SUFFIX_MUCKS.matcher(suffix).matches()) {
                        hand.actions.add(new HandAction(street, HandActionType.MUCKS, line));
                    } else {
                        hand.unparsedLines.add(line);
                    }
                } else {
                    hand.unparsedLines.add(line);
                }
            } else {
                if ((m = POT_RAKE.matcher(line)).matches()) {
                    hand.totalPot = new BigDecimal(m.group(1));
                    hand.rake = new BigDecimal(m.group(2));
                } else if ((m = BOARD.matcher(line)).matches()) {
                    List<String> board = hand.board(0);
                    board.clear();
                    board.addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if ((m = SUMMARY_MUCKS.matcher(line)).matches()) {
                    addSummaryOutcome(hand, seatByName, m.group(2), HandActionType.MUCKS, line);
                } else if ((m = SUMMARY_MUCKS_WITH_CARDS.matcher(line)).matches()) {
                    addSummaryOutcome(hand, seatByName, m.group(2), HandActionType.MUCKS, line);
                } else if ((m = SUMMARY_SHOWS.matcher(line)).matches()) {
                    addSummaryOutcome(hand, seatByName, m.group(2), HandActionType.SHOWS, line);
                } else if ((m = SUMMARY_DOESNT_SHOW.matcher(line)).matches()) {
                    addSummaryOutcome(hand, seatByName, m.group(2), HandActionType.DOESNT_SHOW, line);
                } else if (GAME_ENDED.matcher(line).matches()) {
                    // end-of-hand marker only, nothing to extract
                } else {
                    hand.unparsedLines.add(line);
                }
            }
        }

        if (!heroCards.isEmpty()) {
            hand.heroHoleCards = heroCards;
        }
        return hand;
    }

    private static void addSummaryOutcome(ParsedHand hand, Map<String, Integer> seatByName, String playerName,
            HandActionType type, String rawLine) {
        hand.actions.add(new HandAction(Street.SHOWDOWN, type, rawLine));
        Integer seatNumber = seatByName.get(playerName);
        if (seatNumber != null) {
            hand.summarySeats.add(new SummarySeat(seatNumber, playerName, null, rawLine));
        }
    }

    private record NameMatch(String name, String suffix) {
    }

    /** Finds the longest known seated-player name that {@code "Player "} is followed by. */
    private static NameMatch matchPlayerName(String line, List<String> namesByDescLength) {
        String afterPlayer = line.substring("Player ".length());
        for (String name : namesByDescLength) {
            if (afterPlayer.startsWith(name)) {
                return new NameMatch(name, afterPlayer.substring(name.length()));
            }
        }
        return null;
    }
}
