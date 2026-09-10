package com.pokeranalytics.importing.parser.winamax;

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
 * Parses Winamax cash-game hand histories (Hold'em/Omaha, No Limit/Pot Limit). Behavior and edge
 * cases are based on the MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference (see
 * {@code THIRD-PARTY-NOTICES.md}), re-implemented as a Java regex/state-machine parser rather
 * than a line-by-line translation.
 *
 * Scope: cash games only, and only the modern plain-text format (Winamax's old XML export format,
 * seen in a handful of untested fixtures in the reference library, is out of scope — it has no
 * sample-based tests of its own there either). Tournament headers have a different shape and are
 * not handled here — see the open follow-ups in {@code docs/11-development-tracking.md}.
 */
public final class WinamaxParser {

    private static final Pattern HEADER = Pattern.compile(
            "^Winamax Poker - CashGame - HandId: #([\\d-]+) - (.+?) \\(([^/]+)/([^)]+)\\) - (.+)$");
    private static final Pattern TABLE = Pattern.compile(
            "^Table: '(.+)' (\\d+)-max \\([^)]*\\) Seat #(\\d+) is the button$");
    private static final Pattern SEAT = Pattern.compile("^Seat (\\d+): (.+?) \\((.+?)\\)$");
    private static final Pattern ANTE_BLINDS_MARKER = Pattern.compile("^\\*\\*\\* ANTE/BLINDS \\*\\*\\*$");
    private static final Pattern PRE_FLOP_MARKER = Pattern.compile("^\\*\\*\\* PRE-FLOP \\*\\*\\*$");

    // A dead-money blind (posted out of turn while sitting back down) carries an " out of
    // position" suffix; the raw line is kept verbatim in the action's rawLine regardless.
    private static final Pattern POST_SB = Pattern.compile("^(.+?) posts small blind (\\S+)(?: out of position)?$");
    private static final Pattern POST_BB = Pattern.compile("^(.+?) posts big blind (\\S+)(?: out of position)?$");
    private static final Pattern DEALT = Pattern.compile("^Dealt to (.+) \\[(.+?)\\]$");

    private static final Pattern FLOP = Pattern.compile("^\\*\\*\\* FLOP \\*\\*\\* \\[(.+?)\\]$");
    private static final Pattern TURN = Pattern.compile("^\\*\\*\\* TURN \\*\\*\\* \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern RIVER = Pattern.compile("^\\*\\*\\* RIVER \\*\\*\\* \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern SHOWDOWN_MARKER = Pattern.compile("^\\*\\*\\* SHOW DOWN \\*\\*\\*$");
    private static final Pattern SUMMARY_MARKER = Pattern.compile("^\\*\\*\\* SUMMARY \\*\\*\\*$");

    // Winamax action lines have no colon between the player name and the verb (unlike
    // PokerStars), so names are recovered by lazily matching up to the fixed verb suffix.
    private static final String ALL_IN_SUFFIX = "(?: and is all-in)?";
    private static final Pattern ACTION_FOLD = Pattern.compile("^(.+?) folds$");
    private static final Pattern ACTION_CHECK = Pattern.compile("^(.+?) checks$");
    private static final Pattern ACTION_CALL = Pattern.compile("^(.+?) calls (\\S+)" + ALL_IN_SUFFIX + "$");
    private static final Pattern ACTION_BET = Pattern.compile("^(.+?) bets (\\S+)" + ALL_IN_SUFFIX + "$");
    private static final Pattern ACTION_RAISE =
            Pattern.compile("^(.+?) raises (\\S+) to (\\S+)" + ALL_IN_SUFFIX + "$");
    // Winamax hand histories don't report main/side-pot splits or uncalled-bet returns.
    private static final Pattern COLLECTED = Pattern.compile("^(.+?) collected (\\S+) from pot$");
    private static final Pattern SHOWS = Pattern.compile("^(.+?) shows \\[(.+?)\\] \\((.+?)\\)$");

    private static final Pattern TOTAL_POT = Pattern.compile("^Total pot (\\S+) \\| (?:Rake (\\S+)|No rake)$");
    private static final Pattern BOARD = Pattern.compile("^Board: \\[(.+?)\\]$");
    // Winamax only lists seats that reached showdown or won in the summary (folded seats are
    // omitted entirely), and a heads-up button can carry both the "small blind" and "button" tags
    // at once — hence the repeated tag group, unlike PokerStars' single optional tag.
    private static final Pattern SUMMARY_SEAT = Pattern.compile(
            "^Seat (\\d+): (.+?)((?: \\((?:button|small blind|big blind)\\))*) ((?:won|showed|mucked).*)$");
    private static final Pattern POSITION_TAG_CLAUSE = Pattern.compile("\\((button|small blind|big blind)\\)");

    // Winamax amounts are number-then-currency-symbol (e.g. "0.25€"), the reverse of PokerStars.
    private static final Pattern MONEY = Pattern.compile("^(-?[\\d.,]+)([^\\d.,]*)$");

    private WinamaxParser() {
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
        if (block.stream().anyMatch(WinamaxParser::isHandHeader)) {
            hands.add(parseHand(block));
        }
        return hands;
    }

    private static boolean isHandHeader(String line) {
        return line.startsWith("Winamax Poker - CashGame - HandId:");
    }

    private static ParsedHand parseHand(List<String> rawLines) {
        ParsedHand hand = new ParsedHand();
        hand.siteName = "Winamax";
        Street street = Street.PREFLOP;
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
                } else if (ANTE_BLINDS_MARKER.matcher(line).matches()) {
                    // section marker only, nothing to extract
                } else if (PRE_FLOP_MARKER.matcher(line).matches()) {
                    // section marker only, nothing to extract
                } else if ((m = POST_SB.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.POST_SMALL_BLIND, line));
                } else if ((m = POST_BB.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.POST_BIG_BLIND, line));
                } else if ((m = DEALT.matcher(line)).matches()) {
                    hand.heroName = m.group(1).trim();
                    hand.heroHoleCards = List.of(m.group(2).trim().split("\\s+"));
                } else if ((m = FLOP.matcher(line)).matches()) {
                    street = Street.FLOP;
                    hand.board(0).addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if ((m = TURN.matcher(line)).matches()) {
                    street = Street.TURN;
                    hand.board(0).add(m.group(1).trim());
                } else if ((m = RIVER.matcher(line)).matches()) {
                    street = Street.RIVER;
                    hand.board(0).add(m.group(1).trim());
                } else if (SHOWDOWN_MARKER.matcher(line).matches()) {
                    street = Street.SHOWDOWN;
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
                } else if (COLLECTED.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.COLLECTED, line));
                } else if (SHOWS.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.SHOWS, line));
                } else {
                    hand.unparsedLines.add(line);
                }
            } else {
                if ((m = TOTAL_POT.matcher(line)).matches()) {
                    hand.totalPot = parseMoney(m.group(1).trim()).amount();
                    hand.rake = m.group(2) != null ? parseMoney(m.group(2).trim()).amount() : BigDecimal.ZERO;
                } else if ((m = BOARD.matcher(line)).matches()) {
                    // Authoritative board, same as PokerStars: overrides whatever the street
                    // markers assembled (the reference library only trusts this summary line).
                    List<String> board = hand.board(0);
                    board.clear();
                    board.addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if ((m = SUMMARY_SEAT.matcher(line)).matches()) {
                    hand.summarySeats.add(new SummarySeat(Integer.parseInt(m.group(1)),
                            m.group(2).trim(), positionTagFrom(m.group(3)), m.group(4).trim()));
                } else {
                    hand.unparsedLines.add(line);
                }
            }
        }

        return hand;
    }

    private static String positionTagFrom(String rawTagRun) {
        if (rawTagRun == null || rawTagRun.isBlank()) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        Matcher m = POSITION_TAG_CLAUSE.matcher(rawTagRun);
        while (m.find()) {
            tags.add(m.group(1));
        }
        return tags.isEmpty() ? null : String.join(", ", tags);
    }

    private static Money parseMoney(String token) {
        Matcher m = MONEY.matcher(token.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Cannot parse money token: '" + token + "'");
        }
        String numeric = m.group(1).replace(",", "");
        return new Money(m.group(2), new BigDecimal(numeric));
    }

    private record Money(String currency, BigDecimal amount) {
    }
}
