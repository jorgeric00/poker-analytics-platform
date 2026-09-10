package com.pokeranalytics.importing.parser.winamax;

import com.pokeranalytics.importing.model.HandAction;
import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.HandFormat;
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
 * Parses Winamax tournament hand histories: both regular multi-table tournaments (with or
 * without a knockout/bounty mechanic) and Expresso, Winamax's own fast 3-max Spin&Go-style
 * format — see {@link HandFormat} for how the two differ. A parsed hand's {@code format} field
 * tells them apart: it's set to {@link HandFormat#EXPRESSO} when the room's own tournament name
 * (the quoted text right after "Tournament") is exactly "Expresso", and {@link
 * HandFormat#TOURNAMENT} for every other tournament name.
 *
 * This is a different grammar from the cash format ({@link WinamaxParser}), not a superset of
 * it: blind/ante amounts are bare chip counts with no currency symbol, seats can carry a
 * real-money knockout bounty, and — unlike cash — the turn/river street markers have no space
 * between the "already dealt" and "new card" bracket groups (e.g. {@code [Kc 5h 5c][Ks]}).
 * Behavior is based on real 2025 Winamax hand histories (Expresso, and the "TRIDENT SPACE KO",
 * "ROTATION" and "SPACE KO" tournaments) kept in
 * {@code backend/src/main/resources/hand-history/winamax-expresso/} — this format isn't covered
 * by the MIT-licensed {@code HHSmithy/PokerHandHistoryParser} reference the cash parsers are
 * ported from (see {@code THIRD-PARTY-NOTICES.md}), which has no Expresso/Spin&Go handling at
 * all and only untested tournament-parsing code.
 *
 * Scope: no sample hand exhibited a bounty-collected-on-elimination announcement line, so that
 * isn't recognized here — it would surface as an unparsed line if it appears in future data.
 */
public final class WinamaxTournamentParser {

    private static final Pattern HEADER = Pattern.compile(
            "^Winamax Poker - Tournament \"(.+?)\" buyIn: (.+?) level: (\\d+) - HandId: #([\\d-]+) - "
                    + "(.+?) \\((.+?)\\) - (.+)$");
    private static final Pattern TABLE = Pattern.compile(
            "^Table: '(.+)' (\\d+)-max \\([^)]*\\) Seat #(\\d+) is the button$");
    // Stack is a bare chip count (no currency); the bounty, when the tournament has one, is
    // real-money and comma-separated from the stack, e.g. "Seat 1: Nobletmeg (2026, 1.70€ bounty)".
    private static final Pattern SEAT = Pattern.compile("^Seat (\\d+): (.+?) \\((\\d+)(?:, (\\S+) bounty)?\\)$");
    private static final Pattern ANTE_BLINDS_MARKER = Pattern.compile("^\\*\\*\\* ANTE/BLINDS \\*\\*\\*$");
    private static final Pattern PRE_FLOP_MARKER = Pattern.compile("^\\*\\*\\* PRE-FLOP \\*\\*\\*$");

    private static final Pattern POST_ANTE = Pattern.compile("^(.+?) posts ante (\\S+)$");
    private static final Pattern POST_SB = Pattern.compile("^(.+?) posts small blind (\\S+)$");
    private static final Pattern POST_BB = Pattern.compile("^(.+?) posts big blind (\\S+)$");
    private static final Pattern DEALT = Pattern.compile("^Dealt to (.+) \\[(.+?)\\]$");

    private static final Pattern FLOP = Pattern.compile("^\\*\\*\\* FLOP \\*\\*\\* \\[(.+?)\\]$");
    // No space between the two bracket groups, unlike the cash format.
    private static final Pattern TURN = Pattern.compile("^\\*\\*\\* TURN \\*\\*\\* \\[.+?\\]\\[(.+?)\\]$");
    private static final Pattern RIVER = Pattern.compile("^\\*\\*\\* RIVER \\*\\*\\* \\[.+?\\]\\[(.+?)\\]$");
    private static final Pattern SHOWDOWN_MARKER = Pattern.compile("^\\*\\*\\* SHOW DOWN \\*\\*\\*$");
    private static final Pattern SUMMARY_MARKER = Pattern.compile("^\\*\\*\\* SUMMARY \\*\\*\\*$");

    private static final String ALL_IN_SUFFIX = "(?: and is all-in)?";
    private static final Pattern ACTION_FOLD = Pattern.compile("^(.+?) folds$");
    private static final Pattern ACTION_CHECK = Pattern.compile("^(.+?) checks$");
    private static final Pattern ACTION_CALL = Pattern.compile("^(.+?) calls (\\S+)" + ALL_IN_SUFFIX + "$");
    private static final Pattern ACTION_BET = Pattern.compile("^(.+?) bets (\\S+)" + ALL_IN_SUFFIX + "$");
    private static final Pattern ACTION_RAISE =
            Pattern.compile("^(.+?) raises (\\S+) to (\\S+)" + ALL_IN_SUFFIX + "$");
    // Unlike cash, tournament pots can be split into a main pot and numbered side pots (no
    // uncalled-bet returns seen either way).
    private static final Pattern COLLECTED_MAIN_POT = Pattern.compile("^(.+?) collected (\\S+) from main pot$");
    private static final Pattern COLLECTED_SIDE_POT =
            Pattern.compile("^(.+?) collected (\\S+) from side pot(?: \\d+)?$");
    private static final Pattern COLLECTED = Pattern.compile("^(.+?) collected (\\S+) from pot$");
    private static final Pattern SHOWS = Pattern.compile("^(.+?) shows \\[(.+?)\\] \\((.+?)\\)$");

    private static final Pattern TOTAL_POT = Pattern.compile("^Total pot (\\S+) \\| (?:Rake (\\S+)|No rake)$");
    private static final Pattern BOARD = Pattern.compile("^Board: \\[(.+?)\\]$");
    private static final Pattern SUMMARY_SEAT = Pattern.compile(
            "^Seat (\\d+): (.+?)((?: \\((?:button|small blind|big blind)\\))*) ((?:won|showed|mucked).*)$");
    private static final Pattern POSITION_TAG_CLAUSE = Pattern.compile("\\((button|small blind|big blind)\\)");

    private static final Pattern MONEY = Pattern.compile("^(-?[\\d.,]+)([^\\d.,]*)$");

    private WinamaxTournamentParser() {
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
        if (block.stream().anyMatch(WinamaxTournamentParser::isHandHeader)) {
            hands.add(parseHand(block));
        }
        return hands;
    }

    private static boolean isHandHeader(String line) {
        return line.startsWith("Winamax Poker - Tournament \"");
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
                    hand.tournamentName = m.group(1);
                    hand.format = "Expresso".equals(hand.tournamentName) ? HandFormat.EXPRESSO : HandFormat.TOURNAMENT;
                    hand.buyIn = m.group(2).trim();
                    hand.tournamentLevel = Integer.parseInt(m.group(3));
                    hand.handId = m.group(4);
                    hand.gameType = m.group(5).trim();
                    applyBlindSpec(hand, m.group(6).trim());
                    hand.timestampRaw = m.group(7).trim();
                } else if ((m = TABLE.matcher(line)).matches()) {
                    hand.tableName = m.group(1);
                    hand.maxSeats = Integer.parseInt(m.group(2));
                    hand.buttonSeat = Integer.parseInt(m.group(3));
                } else if ((m = SEAT.matcher(line)).matches()) {
                    BigDecimal bounty = m.group(4) != null ? parseMoney(m.group(4)).amount() : null;
                    hand.seats.add(new Seat(Integer.parseInt(m.group(1)), m.group(2).trim(),
                            new BigDecimal(m.group(3)), "", bounty));
                } else if (ANTE_BLINDS_MARKER.matcher(line).matches()) {
                    // section marker only, nothing to extract
                } else if (PRE_FLOP_MARKER.matcher(line).matches()) {
                    // section marker only, nothing to extract
                } else if ((m = POST_ANTE.matcher(line)).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.POST_ANTE, line));
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
                } else if (COLLECTED_MAIN_POT.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.COLLECTED_MAIN_POT, line));
                } else if (COLLECTED_SIDE_POT.matcher(line).matches()) {
                    hand.actions.add(new HandAction(street, HandActionType.COLLECTED_SIDE_POT, line));
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

    /** Header blind spec is either "SB/BB" (no ante shown, e.g. Expresso) or "ante/SB/BB". */
    private static void applyBlindSpec(ParsedHand hand, String spec) {
        String[] parts = spec.split("/");
        int sbIndex = parts.length == 3 ? 1 : 0;
        hand.currency = "";
        if (parts.length == 3) {
            hand.ante = parseMoney(parts[0]).amount();
        }
        hand.smallBlind = parseMoney(parts[sbIndex]).amount();
        hand.bigBlind = parseMoney(parts[sbIndex + 1]).amount();
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
