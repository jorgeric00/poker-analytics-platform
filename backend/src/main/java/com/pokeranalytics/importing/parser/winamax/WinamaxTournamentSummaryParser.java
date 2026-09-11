package com.pokeranalytics.importing.parser.winamax;

import com.pokeranalytics.importing.model.TournamentSummary;
import com.pokeranalytics.importing.parser.HandHistoryFiles;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md} and
 * {@code docs/decisions/ADR-008-tournament-summary-parser.md}.
 *
 * Parses Winamax's "Tournament summary" document: a per-tournament-entry record of buy-in,
 * prizepool, finishing place and money/bounty won. A different, non-hand-history export from
 * what {@link WinamaxTournamentParser} reads — see that class's doc for the hand-history
 * grammar. A single file can hold multiple concatenated records, one per time the player entered
 * that recurring tournament name, so {@link #parse} returns a list, split on the repeating
 * {@code "Winamax Poker - Tournament summary : "} header line the same way hand headers split a
 * hand-history file.
 *
 * Behavior is based on real Winamax summary files kept alongside their matching hand histories
 * in {@code backend/src/main/resources/hand-history/winamax/}: "TRIDENT SPACE KO", "ROTATION"
 * and "QUARK" (each two-block or one-block, tournament/knockout), plus an orphan "Expresso"
 * summary (Sit&amp;Go) with no matching hand-history file. Not every entrant record has a
 * "You won" line — it's absent entirely when the player didn't cash.
 */
public final class WinamaxTournamentSummaryParser {

    private static final Pattern HEADER = Pattern.compile(
            "^Winamax Poker - Tournament summary : (.+?)\\((\\d+)\\)(?: - (.+))?$");
    private static final Pattern PLAYER = Pattern.compile("^Player : (.+)$");
    private static final Pattern BUY_IN = Pattern.compile("^Buy-In : (.+)$");
    private static final Pattern REGISTERED_PLAYERS = Pattern.compile("^Registered players : (\\d+)$");
    private static final Pattern MODE = Pattern.compile("^Mode : (.+)$");
    private static final Pattern TYPE = Pattern.compile("^Type : (.+)$");
    private static final Pattern SPEED = Pattern.compile("^Speed : (.+)$");
    private static final Pattern FLIGHT_ID = Pattern.compile("^Flight ID : (\\d+)$");
    private static final Pattern LEVELS = Pattern.compile("^Levels : (.+)$");
    private static final Pattern PRIZEPOOL = Pattern.compile("^Prizepool : ([\\d.]+)€$");
    private static final Pattern STARTED = Pattern.compile("^Tournament started (.+)$");
    private static final Pattern PLAYED = Pattern.compile("^You played (.+)$");
    private static final Pattern FINISHED = Pattern.compile("^You finished in (\\d+)(?:st|nd|rd|th) place$");
    private static final Pattern WON = Pattern.compile("^You won ([\\d.]+)€(?: \\+ Bounty ([\\d.]+)€)?$");
    private static final Pattern MONEY_TOKEN = Pattern.compile("([\\d.]+)€");

    private WinamaxTournamentSummaryParser() {
    }

    public static List<TournamentSummary> parseFile(Path path) throws IOException {
        return parse(HandHistoryFiles.readNormalized(path));
    }

    public static List<TournamentSummary> parse(String content) {
        List<TournamentSummary> summaries = new ArrayList<>();
        String[] lines = content.split("\\r\\n|\\r|\\n");
        List<String> block = new ArrayList<>();

        for (String line : lines) {
            if (isSummaryHeader(line) && !block.isEmpty()) {
                summaries.add(parseSummary(block));
                block = new ArrayList<>();
            }
            block.add(line);
        }
        if (block.stream().anyMatch(WinamaxTournamentSummaryParser::isSummaryHeader)) {
            summaries.add(parseSummary(block));
        }
        return summaries;
    }

    private static boolean isSummaryHeader(String line) {
        return line.startsWith("Winamax Poker - Tournament summary : ");
    }

    private static TournamentSummary parseSummary(List<String> rawLines) {
        TournamentSummary summary = new TournamentSummary();

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher m;
            if ((m = HEADER.matcher(line)).matches()) {
                summary.tournamentName = m.group(1);
                summary.tournamentId = m.group(2);
                summary.titleSuffix = m.group(3);
            } else if ((m = PLAYER.matcher(line)).matches()) {
                summary.playerName = m.group(1);
            } else if ((m = BUY_IN.matcher(line)).matches()) {
                summary.buyInRaw = m.group(1);
                summary.buyInTotal = sumMoneyTokens(m.group(1));
            } else if ((m = REGISTERED_PLAYERS.matcher(line)).matches()) {
                summary.registeredPlayers = Integer.parseInt(m.group(1));
            } else if ((m = MODE.matcher(line)).matches()) {
                summary.mode = m.group(1);
            } else if ((m = TYPE.matcher(line)).matches()) {
                summary.type = m.group(1);
            } else if ((m = SPEED.matcher(line)).matches()) {
                summary.speed = m.group(1);
            } else if ((m = FLIGHT_ID.matcher(line)).matches()) {
                summary.flightId = Integer.parseInt(m.group(1));
            } else if ((m = LEVELS.matcher(line)).matches()) {
                summary.levelsRaw = m.group(1);
            } else if ((m = PRIZEPOOL.matcher(line)).matches()) {
                summary.prizepool = new BigDecimal(m.group(1));
            } else if ((m = STARTED.matcher(line)).matches()) {
                summary.startedAtRaw = m.group(1);
            } else if ((m = PLAYED.matcher(line)).matches()) {
                summary.playedDurationRaw = m.group(1);
            } else if ((m = FINISHED.matcher(line)).matches()) {
                summary.finishPlace = Integer.parseInt(m.group(1));
            } else if ((m = WON.matcher(line)).matches()) {
                summary.amountWon = new BigDecimal(m.group(1));
                if (m.group(2) != null) {
                    summary.bountyWon = new BigDecimal(m.group(2));
                }
            } else {
                summary.unparsedLines.add(line);
            }
        }

        return summary;
    }

    private static BigDecimal sumMoneyTokens(String text) {
        BigDecimal total = BigDecimal.ZERO;
        Matcher m = MONEY_TOKEN.matcher(text);
        while (m.find()) {
            total = total.add(new BigDecimal(m.group(1)));
        }
        return total;
    }
}
