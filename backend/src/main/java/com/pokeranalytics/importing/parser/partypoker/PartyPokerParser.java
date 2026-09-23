package com.pokeranalytics.importing.parser.partypoker;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Parses PartyPoker cash-game hand histories (Hold'em/Omaha/Omaha Hi-Lo, No/Pot/Fixed Limit).
 * Behavior and edge cases are based on the MIT-licensed {@code HHSmithy/PokerHandHistoryParser}
 * reference (see {@code THIRD-PARTY-NOTICES.md}), re-implemented as a Java regex/state-machine
 * parser rather than a line-by-line translation.
 *
 * This is a meaningfully different grammar from PokerStars/Winamax, not a variation on it:
 * action lines have no colon after the player name (same lazy-name-match technique as Winamax),
 * street markers ("** Dealing Flop/Turn/River **") only ever show their own new card(s), never
 * the already-dealt board, and there is no "*** SUMMARY ***" section at all — a hand just ends
 * after its last "wins" line(s). Consequences of that last point:
 * <ul>
 *   <li>no stated total pot or rake anywhere in the text — {@code totalPot} is computed here by
 *       summing every "wins" line's amount (a real, traceable value, not an invented one); {@code
 *       rake} is left null rather than assumed zero, since the room simply doesn't say</li>
 *   <li>no per-seat outcome summary — {@code summarySeats} is left empty for this room</li>
 *   <li>{@code Street.SHOWDOWN} is applied directly to shows/doesn't-show/wins actions (there's no
 *       marker line to trigger it from, unlike the other rooms)</li>
 * </ul>
 *
 * Scope: cash games only — see the open follow-ups in {@code docs/11-development-tracking.md}.
 */
public final class PartyPokerParser {

    private static final Pattern HEADER_ID = Pattern.compile("^\\*\\*\\*\\*\\* Hand History for Game (\\d+) \\*\\*\\*\\*\\*$");
    // "$0.05/$0.10 USD NL Texas Hold'em - Monday, January 06, 05:54:36 EST 2014" or
    // "$100 USD PL Omaha Hi-Lo - ...". The leading stakes token is intentionally not captured —
    // smallBlind/bigBlind come from the actual blind-post actions instead (more precise, and
    // avoids PartyPoker's own quirky "buy-in implies blinds" arithmetic for non-slash tables).
    private static final Pattern HEADER_INFO =
            Pattern.compile("^\\S+ (\\S+) ((?:NL|PL|FL) .+?) - (.+)$");
    private static final Pattern TABLE = Pattern.compile("^Table (.+) \\([^)]*\\)$");
    private static final Pattern BUTTON = Pattern.compile("^Seat (\\d+) is the button$");
    private static final Pattern TOTAL_PLAYERS = Pattern.compile("^Total number of players : \\d+/(\\d+)$");
    private static final Pattern SEAT = Pattern.compile("^Seat (\\d+): (.+?) \\( (\\S+) (\\S+) \\)$");
    // Multi-hand exports wrap each hand with redundant markers; both are informational no-ops.
    private static final Pattern GAME_NO_MARKER = Pattern.compile("^#Game No : \\d+$");
    private static final Pattern GAME_STARTS_MARKER = Pattern.compile("^Game #\\d+ starts\\.$");

    // Amount is a symbol+number, sometimes followed by a currency code inside the same brackets
    // (e.g. "[$0.05 USD]"), sometimes not (e.g. the dead-blind's "[$3]") — one fragment for both.
    private static final String BRACKET_MONEY = "\\[(\\S+?)(?: [A-Z]{3})?\\]";
    private static final Pattern POST_DEAD_BLIND =
            Pattern.compile("^(.+?) posts big blind \\+ dead " + BRACKET_MONEY + "\\.$");
    private static final Pattern POST_SB = Pattern.compile("^(.+?) posts small blind " + BRACKET_MONEY + "\\.$");
    private static final Pattern POST_BB = Pattern.compile("^(.+?) posts big blind " + BRACKET_MONEY + "\\.$");

    private static final Pattern DEALING_DOWN_MARKER = Pattern.compile("^\\*\\* Dealing down cards \\*\\*$");
    private static final Pattern DEALT = Pattern.compile("^Dealt to (.+) \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern FLOP = Pattern.compile("^\\*\\* Dealing Flop \\*\\* \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern TURN = Pattern.compile("^\\*\\* Dealing Turn \\*\\* \\[\\s*(.+?)\\s*\\]$");
    private static final Pattern RIVER = Pattern.compile("^\\*\\* Dealing River \\*\\* \\[\\s*(.+?)\\s*\\]$");

    // Recognized but not recorded as hand actions — table/session noise, not poker actions.
    private static final Pattern JOINS_TABLE = Pattern.compile("^(.+?) has joined the table\\.$");
    private static final Pattern LEAVES_TABLE = Pattern.compile("^(.+?) has left the table\\.$");
    private static final Pattern SITS_OUT = Pattern.compile("^(.+?) is sitting out$");
    private static final Pattern DID_NOT_RESPOND = Pattern.compile("^(.+?) did not respond in time$");
    private static final Pattern TIME_BANK_SYSTEM = Pattern.compile("^Your time bank will be activated.*$");
    // Pronoun varies ("their"/"his"/"her") depending on the player's profile settings.
    private static final Pattern TIME_BANK_PLAYER =
            Pattern.compile("^(.+?) will be using (?:their|his|her) time bank for this hand\\.$");

    private static final Pattern ACTION_FOLD = Pattern.compile("^(.+?) folds$");
    private static final Pattern ACTION_CHECK = Pattern.compile("^(.+?) checks$");
    private static final Pattern ACTION_CALL = Pattern.compile("^(.+?) calls " + BRACKET_MONEY + "$");
    private static final Pattern ACTION_BET = Pattern.compile("^(.+?) bets " + BRACKET_MONEY + "$");
    private static final Pattern ACTION_RAISE = Pattern.compile("^(.+?) raises " + BRACKET_MONEY + "$");
    // "PlayerName is all-In  [$4.90 USD]" — its own verb here, not a suffix on bet/call/raise.
    private static final Pattern ACTION_ALL_IN = Pattern.compile("^(.+?) is all-In\\s+" + BRACKET_MONEY + "$");

    private static final Pattern DOESNT_SHOW_PLAIN = Pattern.compile("^(.+?) does not show cards\\.$");
    // The room still logs the cards even when it labels the line "doesn't show" (privacy at the
    // table, not in the exported history).
    private static final Pattern DOESNT_SHOW_CARDS = Pattern.compile("^(.+?) doesn't show \\[\\s*(.+?)\\s*\\].*$");
    // Omaha Hi/Lo low-hand reveal: no space between "shows" and the card list, double space
    // before "for low." — a real quirk in PartyPoker's own hand-history text, not a typo here.
    private static final Pattern SHOWS_FOR_LOW = Pattern.compile("^(.+?) shows([2-9TJQKA,]+)\\s+for low\\.$");
    private static final Pattern SHOWS = Pattern.compile("^(.+?) shows \\[\\s*(.+?)\\s*\\].*$");

    // Order matters: the "wins Lo (...)" (Hi/Lo low half) variants must be checked before the
    // plain "wins $X ..." ones they'd otherwise be mistaken for a prefix of.
    private static final Pattern WINS_LOW_MAIN_POT =
            Pattern.compile("^(.+?) wins Lo \\((\\S+?)(?: [A-Z]{3})?\\) from the main pot with .+\\.$");
    private static final Pattern WINS_LOW_SIDE_POT =
            Pattern.compile("^(.+?) wins Lo \\((\\S+?)(?: [A-Z]{3})?\\) from the side pot \\d+ with .+\\.$");
    private static final Pattern WINS_MAIN_POT =
            Pattern.compile("^(.+?) wins (\\S+?)(?: [A-Z]{3})? from the main pot with .+\\.$");
    private static final Pattern WINS_SIDE_POT =
            Pattern.compile("^(.+?) wins (\\S+?)(?: [A-Z]{3})? from the side pot \\d+ with .+\\.$");
    private static final Pattern WINS_PLAIN = Pattern.compile("^(.+?) wins (\\S+?)(?: [A-Z]{3})?$");
    // Table chat: PartyPoker's own hand-history text has no dedicated marker for it, so (like the
    // reference library) we treat any remaining "name: message" line as chat, checked last.
    private static final Pattern CHAT = Pattern.compile("^(.+?): .+$");

    private PartyPokerParser() {
    }

    public static List<ParsedHand> parseFile(Path path) throws IOException {
        return parse(HandHistoryFiles.readNormalized(path));
    }

    public static List<ParsedHand> parse(String content) {
        List<ParsedHand> hands = new ArrayList<>();
        String[] lines = content.split("\\r\\n|\\r|\\n");
        List<String> block = new ArrayList<>();

        for (String line : lines) {
            // A multi-hand export can prefix the very first hand with a leading "#Game No :"
            // bookkeeping line (see GAME_NO_MARKER) before its header ever appears; without this
            // guard that fragment gets flushed as a bogus header-less "hand" of its own.
            if (isHandHeader(line) && !block.isEmpty()) {
                if (block.stream().anyMatch(PartyPokerParser::isHandHeader)) {
                    hands.add(parseHand(block));
                }
                block = new ArrayList<>();
            }
            block.add(line);
        }
        if (block.stream().anyMatch(PartyPokerParser::isHandHeader)) {
            hands.add(parseHand(block));
        }
        return hands;
    }

    private static boolean isHandHeader(String line) {
        return line.startsWith("***** Hand History for Game");
    }

    private static ParsedHand parseHand(List<String> rawLines) {
        ParsedHand hand = new ParsedHand();
        hand.siteName = "PartyPoker";
        Street street = Street.PREFLOP;
        BigDecimal totalPot = BigDecimal.ZERO;
        // "raises [$X]"/"is all-In [$X]" state the increment added on top of the player's own
        // prior contribution that street, NOT a cumulative total — the original "neohacks raises
        // [$30 USD] from 0 ... wins $45" check had prior=0 (neohacks posted no blind that hand),
        // which can't actually tell the two readings apart. A real fixture with a nonzero prior
        // does: HandActionTests/OmahaHiLo.txt's fistfock123 posts BB(1), raises[$10] then goes
        // all-In[$19] — only reading both as increments on his own running total (1+10+19=30,
        // exactly his starting stack) leaves him with $0, genuinely all-in; a "cumulative" reading
        // of raises[$10] would leave him $11 short of that, contradicting the hand's own side-pot
        // structure. Converted here to the cumulative total this module's actions table requires,
        // reset at every street change, same convention hand_net_results already relies on.
        Map<String, BigDecimal> streetContribution = new HashMap<>();

        for (String rawLine : rawLines) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher m;

            if ((m = HEADER_ID.matcher(line)).matches()) {
                hand.handId = m.group(1);
            } else if ((m = HEADER_INFO.matcher(line)).matches()) {
                hand.currency = m.group(1);
                hand.gameType = m.group(2).trim();
                hand.timestampRaw = m.group(3).trim();
            } else if ((m = TABLE.matcher(line)).matches()) {
                hand.tableName = m.group(1);
            } else if ((m = BUTTON.matcher(line)).matches()) {
                hand.buttonSeat = Integer.parseInt(m.group(1));
            } else if ((m = TOTAL_PLAYERS.matcher(line)).matches()) {
                hand.maxSeats = Integer.parseInt(m.group(1));
            } else if ((m = SEAT.matcher(line)).matches()) {
                hand.seats.add(new Seat(Integer.parseInt(m.group(1)), m.group(2).trim(),
                        parseAmount(m.group(3)), m.group(4), null));
            } else if (GAME_NO_MARKER.matcher(line).matches() || GAME_STARTS_MARKER.matcher(line).matches()) {
                // multi-hand export bookkeeping only, nothing to extract
            } else if ((m = POST_DEAD_BLIND.matcher(line)).matches()) {
                BigDecimal deadBlindAmount = parseAmount(m.group(2));
                streetContribution.merge(m.group(1).trim(), deadBlindAmount, BigDecimal::add);
                hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_DEAD_BLIND, line,
                        m.group(1).trim(), deadBlindAmount));
            } else if ((m = POST_SB.matcher(line)).matches()) {
                hand.smallBlind = parseAmount(m.group(2));
                streetContribution.merge(m.group(1).trim(), hand.smallBlind, BigDecimal::add);
                hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_SMALL_BLIND, line,
                        m.group(1).trim(), hand.smallBlind));
            } else if ((m = POST_BB.matcher(line)).matches()) {
                hand.bigBlind = parseAmount(m.group(2));
                streetContribution.merge(m.group(1).trim(), hand.bigBlind, BigDecimal::add);
                hand.actions.add(new HandAction(Street.PREFLOP, HandActionType.POST_BIG_BLIND, line,
                        m.group(1).trim(), hand.bigBlind));
            } else if (DEALING_DOWN_MARKER.matcher(line).matches()) {
                // section marker only, nothing to extract
            } else if ((m = DEALT.matcher(line)).matches()) {
                hand.heroName = m.group(1).trim();
                hand.heroHoleCards = List.of(m.group(2).trim().split("\\s+"));
            } else if ((m = FLOP.matcher(line)).matches()) {
                street = Street.FLOP;
                streetContribution.clear();
                hand.board(0).addAll(List.of(m.group(1).replace(",", "").trim().split("\\s+")));
            } else if ((m = TURN.matcher(line)).matches()) {
                street = Street.TURN;
                streetContribution.clear();
                hand.board(0).add(m.group(1).replace(",", "").trim());
            } else if ((m = RIVER.matcher(line)).matches()) {
                street = Street.RIVER;
                streetContribution.clear();
                hand.board(0).add(m.group(1).replace(",", "").trim());
            } else if (JOINS_TABLE.matcher(line).matches()
                    || LEAVES_TABLE.matcher(line).matches()
                    || SITS_OUT.matcher(line).matches()
                    || DID_NOT_RESPOND.matcher(line).matches()
                    || TIME_BANK_SYSTEM.matcher(line).matches()
                    || TIME_BANK_PLAYER.matcher(line).matches()) {
                // table/session noise — recognized, intentionally not recorded as a hand action
            } else if ((m = ACTION_FOLD.matcher(line)).matches()) {
                hand.actions.add(new HandAction(street, HandActionType.FOLD, line, m.group(1).trim(), null));
            } else if ((m = ACTION_CHECK.matcher(line)).matches()) {
                hand.actions.add(new HandAction(street, HandActionType.CHECK, line, m.group(1).trim(), null));
            } else if ((m = ACTION_CALL.matcher(line)).matches()) {
                BigDecimal callAmount = parseAmount(m.group(2));
                streetContribution.merge(m.group(1).trim(), callAmount, BigDecimal::add);
                hand.actions.add(new HandAction(street, HandActionType.CALL, line,
                        m.group(1).trim(), callAmount));
            } else if ((m = ACTION_BET.matcher(line)).matches()) {
                BigDecimal betAmount = parseAmount(m.group(2));
                streetContribution.merge(m.group(1).trim(), betAmount, BigDecimal::add);
                hand.actions.add(new HandAction(street, HandActionType.BET, line,
                        m.group(1).trim(), betAmount));
            } else if ((m = ACTION_RAISE.matcher(line)).matches()) {
                String raiser = m.group(1).trim();
                BigDecimal raiseCumulative = streetContribution.getOrDefault(raiser, BigDecimal.ZERO).add(parseAmount(m.group(2)));
                streetContribution.put(raiser, raiseCumulative);
                hand.actions.add(new HandAction(street, HandActionType.RAISE, line, raiser, raiseCumulative));
            } else if ((m = ACTION_ALL_IN.matcher(line)).matches()) {
                String allInPlayer = m.group(1).trim();
                BigDecimal allInCumulative = streetContribution.getOrDefault(allInPlayer, BigDecimal.ZERO).add(parseAmount(m.group(2)));
                streetContribution.put(allInPlayer, allInCumulative);
                hand.actions.add(new HandAction(street, HandActionType.ALL_IN, line, allInPlayer, allInCumulative));
            } else if ((m = DOESNT_SHOW_PLAIN.matcher(line)).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.DOESNT_SHOW, line, m.group(1).trim(), null));
            } else if ((m = DOESNT_SHOW_CARDS.matcher(line)).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.DOESNT_SHOW, line, m.group(1).trim(), null));
            } else if ((m = SHOWS_FOR_LOW.matcher(line)).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.SHOWS, line, m.group(1).trim(), null));
            } else if ((m = SHOWS.matcher(line)).matches()) {
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.SHOWS, line, m.group(1).trim(), null));
            } else if ((m = WINS_LOW_MAIN_POT.matcher(line)).matches()) {
                BigDecimal amount = parseAmount(m.group(2));
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.COLLECTED_MAIN_POT, line, m.group(1).trim(), amount));
                totalPot = totalPot.add(amount);
            } else if ((m = WINS_LOW_SIDE_POT.matcher(line)).matches()) {
                BigDecimal amount = parseAmount(m.group(2));
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.COLLECTED_SIDE_POT, line, m.group(1).trim(), amount));
                totalPot = totalPot.add(amount);
            } else if ((m = WINS_MAIN_POT.matcher(line)).matches()) {
                BigDecimal amount = parseAmount(m.group(2));
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.COLLECTED_MAIN_POT, line, m.group(1).trim(), amount));
                totalPot = totalPot.add(amount);
            } else if ((m = WINS_SIDE_POT.matcher(line)).matches()) {
                BigDecimal amount = parseAmount(m.group(2));
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.COLLECTED_SIDE_POT, line, m.group(1).trim(), amount));
                totalPot = totalPot.add(amount);
            } else if ((m = WINS_PLAIN.matcher(line)).matches()) {
                BigDecimal amount = parseAmount(m.group(2));
                hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.COLLECTED, line, m.group(1).trim(), amount));
                totalPot = totalPot.add(amount);
            } else if (CHAT.matcher(line).matches()) {
                // table chat — recognized, intentionally not recorded as a hand action
            } else {
                hand.unparsedLines.add(line);
            }
        }

        hand.totalPot = totalPot;
        return hand;
    }

    /** Strips the currency symbol/code and thousands separators, e.g. "$5,000" -> 5000. */
    private static BigDecimal parseAmount(String token) {
        String cleaned = token.replaceAll("[^0-9.]", "");
        return new BigDecimal(cleaned);
    }
}
