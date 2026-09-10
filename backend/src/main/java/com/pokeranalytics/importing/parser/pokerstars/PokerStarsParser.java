package com.pokeranalytics.importing.parser.pokerstars;

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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Parses PokerStars cash-game hand histories (Hold'em/Omaha, No Limit/Pot Limit/Fixed Limit,
 * regular tables and Zoom). Behavior and edge cases are based on the MIT-licensed
 * {@code HHSmithy/PokerHandHistoryParser} reference (see {@code THIRD-PARTY-NOTICES.md}),
 * re-implemented as a Java regex/state-machine parser rather than a line-by-line translation.
 *
 * Scope: cash games only. Tournament headers ("Tournament #...") have a different shape and are
 * not handled here — see the open follow-ups in {@code docs/11-development-tracking.md}.
 */
public final class PokerStarsParser {

    private static final Pattern HEADER = Pattern.compile(
            "^PokerStars (?:Zoom Hand|Hand|Game) #(\\d+):\\s+(.+?)\\s+\\(([^/]+)/([^)]+)\\)\\s+-\\s+(.+)$");
    // Greedy name capture: table names can contain an apostrophe themselves (e.g. "Isildur's PLO
    // 50"), so we can't stop at the first closing quote — greedy backtracking finds the quote
    // that actually precedes the fixed "N-max Seat #N is the button" suffix.
    private static final Pattern TABLE = Pattern.compile(
            "^Table '(.+)' (\\d+)-max Seat #(\\d+) is the button$");
    private static final Pattern SEAT = Pattern.compile(
            "^Seat (\\d+): (.+?) \\((.+?) in chips\\)$");
    private static final Pattern HOLE_CARDS_MARKER = Pattern.compile("^\\*\\*\\* HOLE CARDS \\*\\*\\*$");
    private static final Pattern DEALT = Pattern.compile("^Dealt to (.+) \\[(.+?)\\]$");

    private static final Pattern POST_ANTE = Pattern.compile("^(.+): posts the ante (\\S+)$");
    private static final Pattern POST_SB = Pattern.compile("^(.+): posts small blind (\\S+)$");
    private static final Pattern POST_BB = Pattern.compile("^(.+): posts big blind (\\S+)$");
    private static final Pattern POST_SB_BB = Pattern.compile("^(.+): posts small & big blinds (\\S+)$");

    private static final Pattern FLOP = Pattern.compile("^\\*\\*\\* (?:(FIRST|SECOND) )?FLOP \\*\\*\\* \\[(.+?)\\]$");
    private static final Pattern TURN =
            Pattern.compile("^\\*\\*\\* (?:(FIRST|SECOND) )?TURN \\*\\*\\* \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern RIVER =
            Pattern.compile("^\\*\\*\\* (?:(FIRST|SECOND) )?RIVER \\*\\*\\* \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern SHOWDOWN_MARKER =
            Pattern.compile("^\\*\\*\\* (?:(?:FIRST|SECOND) )?SHOW DOWN \\*\\*\\*$");
    private static final Pattern SUMMARY_MARKER = Pattern.compile("^\\*\\*\\* SUMMARY \\*\\*\\*$");
    private static final Pattern RUN_TWICE_MARKER = Pattern.compile("^Hand was run twice$");

    private static final Pattern ACTION_FOLD = Pattern.compile("^(.+): folds$");
    private static final Pattern ACTION_CHECK = Pattern.compile("^(.+): checks$");
    // Some tables cap the effective bet size ("and has reached the $40 cap") instead of, or
    // alongside, going all-in.
    private static final String AMOUNT_SUFFIX = "(?: and is all-in| and has reached the \\S+ cap)?";
    private static final Pattern ACTION_CALL = Pattern.compile("^(.+): calls (\\S+)" + AMOUNT_SUFFIX + "$");
    private static final Pattern ACTION_BET = Pattern.compile("^(.+): bets (\\S+)" + AMOUNT_SUFFIX + "$");
    private static final Pattern ACTION_RAISE =
            Pattern.compile("^(.+): raises (\\S+) to (\\S+)" + AMOUNT_SUFFIX + "$");
    private static final Pattern UNCALLED = Pattern.compile("^Uncalled bet \\((\\S+)\\) returned to (.+)$");
    private static final Pattern COLLECTED_SIDE_NUMBERED =
            Pattern.compile("^(.+) collected (\\S+) from side pot-\\d+$");
    private static final Pattern COLLECTED_SIDE = Pattern.compile("^(.+) collected (\\S+) from side pot$");
    private static final Pattern COLLECTED_MAIN = Pattern.compile("^(.+) collected (\\S+) from main pot$");
    private static final Pattern COLLECTED = Pattern.compile("^(.+) collected (\\S+) from pot$");
    private static final Pattern DOESNT_SHOW = Pattern.compile("^(.+): doesn't show hand$");
    private static final Pattern MUCKS = Pattern.compile("^(.+): mucks hand$");
    private static final Pattern SHOWS = Pattern.compile("^(.+): shows \\[(.+?)\\] \\((.+?)\\)$");
    // Omaha Hi/Lo can show a card with no hand description at all, e.g. when only revealing for a
    // side (hi or lo) that didn't qualify.
    private static final Pattern SHOWS_NO_DESCRIPTION = Pattern.compile("^(.+): shows \\[(.+?)\\]$");
    // Standalone Omaha Hi/Lo informational line, no player/colon prefix.
    private static final Pattern NO_LOW_HAND_QUALIFIED = Pattern.compile("^No low hand qualified$");
    private static final Pattern CONNECTION_STATUS = Pattern.compile(
            "^(.+) (is disconnected|has timed out while disconnected|has timed out|is connected)$");

    // Recognized but not recorded as hand actions — table/session noise, not poker actions.
    private static final Pattern SITS_OUT = Pattern.compile("^(.+): (?:is sitting out|sits out)$");
    private static final Pattern LEAVES_TABLE = Pattern.compile("^(.+) leaves the table$");
    private static final Pattern JOINS_TABLE = Pattern.compile("^(.+) joins the table at seat #\\d+$");
    private static final Pattern ALLOWED_AFTER_BUTTON =
            Pattern.compile("^(.+) will be allowed to play after the button$");
    private static final Pattern REMOVED_FOR_FAILING_TO_POST =
            Pattern.compile("^(.+) was removed from the table for failing to post$");
    private static final Pattern CHAT = Pattern.compile("^(.+) said, \".*\"$");

    // Side pots can repeat and be numbered ("Side pot-1 $7.50. Side pot-2 $6.08.") or a single
    // unnumbered aggregate ("Side pot $20.") — group 3 captures the whole raw clause run, parsed
    // separately by SIDE_POT_CLAUSE below since a single Java capturing group can't collect
    // multiple repetitions on its own.
    private static final Pattern TOTAL_POT = Pattern.compile(
            "^Total pot (\\S+)(?: Main pot (\\S+)\\.)?((?: Side pot(?:-\\d+)? \\S+\\.)*) \\| Rake (\\S+)$");
    private static final Pattern SIDE_POT_CLAUSE = Pattern.compile("Side pot(?:-\\d+)? (\\S+)\\.");
    private static final Pattern BOARD = Pattern.compile("^(?:(FIRST|SECOND) )?Board \\[(.+?)\\]$");
    // The optional position tag is a fixed set of literals, and the outcome is anchored on the
    // known outcome verbs — both needed because player names may contain spaces (or even colons/
    // parentheses as emoticons, e.g. "wo_olly :D"), which would otherwise make a plain ".+ .+"
    // split ambiguous.
    private static final Pattern SUMMARY_SEAT = Pattern.compile(
            "^Seat (\\d+): (.+?)(?: \\((button|small blind|big blind)\\))?"
                    + " ((?:folded|collected|showed|mucked|is sitting out).*)$");

    // Not end-anchored: some tables (e.g. Cap games) append extra text after the amount, like
    // "$1.00 - $20 Cap -  USD" or a trailing currency code ("$1.00 USD") — we only need the
    // leading currency symbol + number.
    private static final Pattern MONEY = Pattern.compile("^([^\\d.,\\-]*)(-?[\\d.,]+)");

    private PokerStarsParser() {
    }

    public static List<ParsedHand> parseFile(Path path) throws IOException {
        return parse(HandHistoryFiles.readNormalized(path));
    }

    public static List<ParsedHand> parse(String content) {
        List<ParsedHand> hands = new ArrayList<>();
        // Some real-world exports use lone-CR (classic Mac) line endings instead of CRLF/LF.
        String[] lines = content.split("\\r\\n|\\r|\\n");
        List<String> block = new ArrayList<>();

        for (String line : lines) {
            if (isHandHeader(line) && !block.isEmpty()) {
                hands.add(parseHand(block));
                block = new ArrayList<>();
            }
            block.add(line);
        }
        if (block.stream().anyMatch(PokerStarsParser::isHandHeader)) {
            hands.add(parseHand(block));
        }
        return hands;
    }

    private static boolean isHandHeader(String line) {
        return line.startsWith("PokerStars Zoom Hand #")
                || line.startsWith("PokerStars Hand #")
                || line.startsWith("PokerStars Game #");
    }

    private static ParsedHand parseHand(List<String> rawLines) {
        ParsedHand hand = new ParsedHand();
        hand.siteName = "PokerStars";
        Street street = Street.PREFLOP;
        int runIndex = 0;
        boolean inSummary = false;

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher m;

            if (!inSummary) {
                if ((m = HEADER.matcher(line)).matches()) {
                    hand.handId = m.group(1);
                    hand.gameType = m.group(2).trim();
                    Money sb = parseMoney(m.group(3).trim());
                    Money bb = parseMoney(m.group(4).trim());
                    hand.currency = sb.currency();
                    hand.smallBlind = sb.amount();
                    hand.bigBlind = bb.amount();
                    hand.timestampRaw = m.group(5).trim();
                } else if ((m = TABLE.matcher(line)).matches()) {
                    hand.tableName = m.group(1);
                    hand.maxSeats = Integer.parseInt(m.group(2));
                    hand.buttonSeat = Integer.parseInt(m.group(3));
                } else if ((m = SEAT.matcher(line)).matches()) {
                    Money stack = parseMoney(m.group(3).trim());
                    hand.seats.add(new Seat(Integer.parseInt(m.group(1)), m.group(2).trim(),
                            stack.amount(), stack.currency(), null));
                } else if ((m = POST_ANTE.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.POST_ANTE, line));
                } else if ((m = POST_SB_BB.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.POST_SMALL_AND_BIG_BLIND, line));
                } else if ((m = POST_SB.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.POST_SMALL_BLIND, line));
                } else if ((m = POST_BB.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.POST_BIG_BLIND, line));
                } else if (HOLE_CARDS_MARKER.matcher(line).matches()) {
                    // section marker only, nothing to extract
                } else if ((m = DEALT.matcher(line)).matches()) {
                    hand.heroName = m.group(1).trim();
                    hand.heroHoleCards = List.of(m.group(2).trim().split("\\s+"));
                } else if ((m = FLOP.matcher(line)).matches()) {
                    street = Street.FLOP;
                    runIndex = runIndexFor(m.group(1), runIndex);
                    hand.board(runIndex).addAll(List.of(m.group(2).trim().split("\\s+")));
                } else if ((m = TURN.matcher(line)).matches()) {
                    street = Street.TURN;
                    runIndex = runIndexFor(m.group(1), runIndex);
                    hand.board(runIndex).addAll(List.of(m.group(2).trim().split("\\s+")));
                } else if ((m = RIVER.matcher(line)).matches()) {
                    street = Street.RIVER;
                    runIndex = runIndexFor(m.group(1), runIndex);
                    hand.board(runIndex).addAll(List.of(m.group(2).trim().split("\\s+")));
                } else if (SHOWDOWN_MARKER.matcher(line).matches()) {
                    street = Street.SHOWDOWN;
                } else if (RUN_TWICE_MARKER.matcher(line).matches()) {
                    hand.runItTwice = true;
                } else if (SUMMARY_MARKER.matcher(line).matches()) {
                    inSummary = true;
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
                } else if (UNCALLED.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.UNCALLED_RETURN, line));
                } else if (COLLECTED_SIDE_NUMBERED.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.COLLECTED_SIDE_POT, line));
                } else if (COLLECTED_SIDE.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.COLLECTED_SIDE_POT, line));
                } else if (COLLECTED_MAIN.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.COLLECTED_MAIN_POT, line));
                } else if (COLLECTED.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.COLLECTED, line));
                } else if (DOESNT_SHOW.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.DOESNT_SHOW, line));
                } else if (MUCKS.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.MUCKS, line));
                } else if (SHOWS.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.SHOWS, line));
                } else if (SHOWS_NO_DESCRIPTION.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.SHOWS, line));
                } else if (NO_LOW_HAND_QUALIFIED.matcher(line).matches()) {
                    // informational only, no player/action to record
                } else if (CONNECTION_STATUS.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.CONNECTION_STATUS, line));
                } else if (SITS_OUT.matcher(line).matches()
                        || LEAVES_TABLE.matcher(line).matches()
                        || JOINS_TABLE.matcher(line).matches()
                        || ALLOWED_AFTER_BUTTON.matcher(line).matches()
                        || REMOVED_FOR_FAILING_TO_POST.matcher(line).matches()
                        || CHAT.matcher(line).matches()) {
                    // table/session noise — recognized, intentionally not recorded as a hand action
                } else {
                    hand.unparsedLines.add(line);
                }
            } else {
                if (RUN_TWICE_MARKER.matcher(line).matches()) {
                    hand.runItTwice = true;
                } else if ((m = TOTAL_POT.matcher(line)).matches()) {
                    hand.totalPot = parseMoney(m.group(1).trim()).amount();
                    if (m.group(2) != null) {
                        hand.mainPot = parseMoney(m.group(2).trim()).amount();
                    }
                    Matcher sidePotMatcher = SIDE_POT_CLAUSE.matcher(m.group(3));
                    while (sidePotMatcher.find()) {
                        hand.sidePots.add(parseMoney(sidePotMatcher.group(1)).amount());
                    }
                    hand.rake = parseMoney(m.group(4).trim()).amount();
                } else if ((m = BOARD.matcher(line)).matches()) {
                    int boardRunIndex = "SECOND".equals(m.group(1)) ? 1 : 0;
                    List<String> board = hand.board(boardRunIndex);
                    board.clear();
                    board.addAll(List.of(m.group(2).trim().split("\\s+")));
                } else if ((m = SUMMARY_SEAT.matcher(line)).matches()) {
                    hand.summarySeats.add(new SummarySeat(Integer.parseInt(m.group(1)),
                            m.group(2).trim(), m.group(3), m.group(4).trim()));
                } else {
                    hand.unparsedLines.add(line);
                }
            }
        }

        return hand;
    }

    private static int runIndexFor(String tag, int currentRunIndex) {
        if ("FIRST".equals(tag)) {
            return 0;
        }
        if ("SECOND".equals(tag)) {
            return 1;
        }
        return currentRunIndex;
    }

    private static Money parseMoney(String token) {
        Matcher m = MONEY.matcher(token.trim());
        if (!m.find()) {
            throw new IllegalArgumentException("Cannot parse money token: '" + token + "'");
        }
        String numeric = m.group(2).replace(",", "");
        return new Money(m.group(1), new BigDecimal(numeric));
    }

    private record Money(String currency, BigDecimal amount) {
    }
}
