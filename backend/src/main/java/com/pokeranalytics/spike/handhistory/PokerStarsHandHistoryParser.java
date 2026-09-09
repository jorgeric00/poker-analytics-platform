package com.pokeranalytics.spike.handhistory;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spike-quality parser for PokerStars Zoom cash-game hand histories (English client format).
 *
 * Phase 9 feasibility spike (see docs/11-development-tracking.md) — it exists to prove this
 * format is parseable using real hand histories, not to be the frozen import-engine
 * architecture. That belongs to the real {@code com.pokeranalytics.importing} module, once
 * Phases 4-8 are documented — do not extend this class with real domain logic.
 */
public final class PokerStarsHandHistoryParser {

    private static final Pattern HEADER = Pattern.compile(
            "^PokerStars Zoom Hand #(\\d+):\\s+(.+?)\\s+\\(([^/]+)/([^)]+)\\)\\s+-\\s+(.+)$");
    private static final Pattern TABLE = Pattern.compile(
            "^Table '([^']+)' (\\d+)-max Seat #(\\d+) is the button$");
    private static final Pattern SEAT = Pattern.compile(
            "^Seat (\\d+): (.+?) \\((.+?) in chips\\)$");
    private static final Pattern POSTS_SB = Pattern.compile("^(.+?): posts small blind (\\S+)$");
    private static final Pattern POSTS_BB = Pattern.compile("^(.+?): posts big blind (\\S+)$");
    private static final Pattern HOLE_CARDS_MARKER = Pattern.compile("^\\*\\*\\* HOLE CARDS \\*\\*\\*$");
    private static final Pattern DEALT = Pattern.compile("^Dealt to (.+?) \\[(.+?)\\]$");
    private static final Pattern FLOP = Pattern.compile("^\\*\\*\\* FLOP \\*\\*\\* \\[(.+?)\\]$");
    private static final Pattern TURN = Pattern.compile("^\\*\\*\\* TURN \\*\\*\\* \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern RIVER = Pattern.compile("^\\*\\*\\* RIVER \\*\\*\\* \\[.+?\\] \\[(.+?)\\]$");
    private static final Pattern SHOWDOWN_MARKER = Pattern.compile("^\\*\\*\\* SHOW DOWN \\*\\*\\*$");
    private static final Pattern SUMMARY_MARKER = Pattern.compile("^\\*\\*\\* SUMMARY \\*\\*\\*$");

    private static final Pattern ACTION_FOLD = Pattern.compile("^(.+?): folds$");
    private static final Pattern ACTION_CHECK = Pattern.compile("^(.+?): checks$");
    private static final Pattern ACTION_CALL = Pattern.compile("^(.+?): calls (\\S+)(?: and is all-in)?$");
    private static final Pattern ACTION_BET = Pattern.compile("^(.+?): bets (\\S+)(?: and is all-in)?$");
    private static final Pattern ACTION_RAISE =
            Pattern.compile("^(.+?): raises (\\S+) to (\\S+)(?: and is all-in)?$");
    private static final Pattern UNCALLED = Pattern.compile("^Uncalled bet \\((\\S+)\\) returned to (.+)$");
    private static final Pattern COLLECTED = Pattern.compile("^(.+?) collected (\\S+) from pot$");
    private static final Pattern DOESNT_SHOW = Pattern.compile("^(.+?): doesn't show hand$");
    private static final Pattern MUCKS = Pattern.compile("^(.+?): mucks hand$");
    private static final Pattern SHOWS = Pattern.compile("^(.+?): shows \\[(.+?)\\] \\((.+?)\\)$");
    // Connection-status notices PokerStars interleaves into the action log — not player actions,
    // but real noise a feasibility spike needs to actually handle.
    private static final Pattern PLAYER_STATUS = Pattern.compile(
            "^(.+?) (is disconnected|has timed out while disconnected|has timed out)$");

    private static final Pattern TOTAL_POT = Pattern.compile("^Total pot (\\S+)(?: \\| Rake (\\S+))?$");
    private static final Pattern BOARD = Pattern.compile("^Board \\[(.+?)\\]$");

    // The optional position tag is a fixed set of literals, and group 4 is anchored on the known
    // outcome verbs — both needed because player names may contain spaces (e.g. "iurii machushak"),
    // which would otherwise make a plain ".+ .+" split ambiguous.
    private static final Pattern SUMMARY_SEAT = Pattern.compile(
            "^Seat (\\d+): (.+?)(?: \\((button|small blind|big blind)\\))?"
                    + " ((?:folded|collected|showed|mucked|is sitting out).*)$");

    private static final Pattern MONEY = Pattern.compile("^([^\\d.,\\-]*)(-?[\\d.,]+)$");

    private PokerStarsHandHistoryParser() {
    }

    public static List<ParsedHand> parseFile(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (!content.isEmpty() && content.charAt(0) == 0xFEFF) {
            content = content.substring(1);
        }
        return parse(content);
    }

    public static List<ParsedHand> parse(String content) {
        List<ParsedHand> hands = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");
        List<String> block = new ArrayList<>();

        for (String line : lines) {
            if (line.startsWith("PokerStars Zoom Hand #") && !block.isEmpty()) {
                hands.add(parseHand(block));
                block = new ArrayList<>();
            }
            block.add(line);
        }
        if (block.stream().anyMatch(l -> l.startsWith("PokerStars Zoom Hand #"))) {
            hands.add(parseHand(block));
        }
        return hands;
    }

    private static ParsedHand parseHand(List<String> rawLines) {
        ParsedHand hand = new ParsedHand();
        String street = "PREFLOP";
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
                    hand.seats.add(new ParsedSeat(Integer.parseInt(m.group(1)), m.group(2).trim(),
                            stack.amount(), stack.currency()));
                } else if ((m = POSTS_SB.matcher(line)).matches()) {
                    hand.actions.add(new ParsedAction(street, "POST_BLIND", line));
                } else if ((m = POSTS_BB.matcher(line)).matches()) {
                    hand.actions.add(new ParsedAction(street, "POST_BLIND", line));
                } else if (HOLE_CARDS_MARKER.matcher(line).matches()) {
                    // section marker only, nothing to extract
                } else if ((m = DEALT.matcher(line)).matches()) {
                    hand.heroName = m.group(1).trim();
                    hand.heroHoleCards = List.of(m.group(2).trim().split("\\s+"));
                } else if ((m = FLOP.matcher(line)).matches()) {
                    street = "FLOP";
                    hand.boardCards.addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if ((m = TURN.matcher(line)).matches()) {
                    street = "TURN";
                    hand.boardCards.addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if ((m = RIVER.matcher(line)).matches()) {
                    street = "RIVER";
                    hand.boardCards.addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if (SHOWDOWN_MARKER.matcher(line).matches()) {
                    street = "SHOWDOWN";
                } else if (SUMMARY_MARKER.matcher(line).matches()) {
                    inSummary = true;
                } else if (ACTION_FOLD.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "FOLD", line));
                } else if (ACTION_CHECK.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "CHECK", line));
                } else if (ACTION_CALL.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "CALL", line));
                } else if (ACTION_BET.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "BET", line));
                } else if (ACTION_RAISE.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "RAISE", line));
                } else if (UNCALLED.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "UNCALLED_RETURN", line));
                } else if (COLLECTED.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "COLLECTED", line));
                } else if (DOESNT_SHOW.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "DOESNT_SHOW", line));
                } else if (MUCKS.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "MUCKS", line));
                } else if (SHOWS.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "SHOWS", line));
                } else if (PLAYER_STATUS.matcher(line).matches()) {
                    hand.actions.add(new ParsedAction(street, "PLAYER_STATUS", line));
                } else {
                    hand.unparsedLines.add(line);
                }
            } else {
                if ((m = TOTAL_POT.matcher(line)).matches()) {
                    hand.totalPot = parseMoney(m.group(1).trim()).amount();
                    if (m.group(2) != null) {
                        hand.rake = parseMoney(m.group(2).trim()).amount();
                    }
                } else if ((m = BOARD.matcher(line)).matches()) {
                    hand.boardCards.clear();
                    hand.boardCards.addAll(List.of(m.group(1).trim().split("\\s+")));
                } else if ((m = SUMMARY_SEAT.matcher(line)).matches()) {
                    hand.summarySeats.add(new ParsedSummarySeat(Integer.parseInt(m.group(1)),
                            m.group(2).trim(), m.group(3), m.group(4).trim()));
                } else {
                    hand.unparsedLines.add(line);
                }
            }
        }

        return hand;
    }

    private static Money parseMoney(String token) {
        Matcher m = MONEY.matcher(token.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Cannot parse money token: '" + token + "'");
        }
        String numeric = m.group(2).replace(",", "");
        return new Money(m.group(1), new BigDecimal(numeric));
    }

    private record Money(String currency, BigDecimal amount) {
    }
}
