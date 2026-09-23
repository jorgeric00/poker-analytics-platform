package com.pokeranalytics.importing.parser.ipoker;

import com.pokeranalytics.importing.model.HandAction;
import com.pokeranalytics.importing.model.HandActionType;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.model.Seat;
import com.pokeranalytics.importing.model.Street;
import com.pokeranalytics.importing.parser.HandHistoryFiles;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.StringReader;
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
 * Parses iPoker Network cash-game hand histories — XML, not plain text, unlike every other room
 * ported so far. The reference's own {@code IPokerFastParserImpl} still reads it as raw text with
 * hand-tuned character offsets (e.g. "the seat number always starts 7 chars after the first
 * ' s'"), which is exactly the low-level, unreadable style ADR-005 says this port deliberately
 * does not copy. XML is the right tool here instead: this class uses the JDK's own DOM parser
 * ({@code javax.xml.parsers}) and navigates by element/attribute name, which is both more robust
 * (immune to whitespace/attribute-order differences the reference's offsets would break on) and
 * far more readable than either style used elsewhere in this module.
 *
 * Two real structural quirks, both ported from the reference's own handling:
 * <ul>
 *   <li>A file can hold multiple hands as either multiple sibling {@code <session>} blocks, or —
 *       far more common in the sample fixtures — a single {@code <session>} whose one
 *       {@code <general>} block is shared by many {@code <game>} children. Both shapes are
 *       normalized to one hand per {@code <session>+<general>+<game>} document before parsing.</li>
 *   <li>An {@code <action>}'s document order does not reliably match play order — its own
 *       {@code no} attribute is the real sequence number (confirmed directly in the fixtures:
 *       e.g. a later action can appear earlier in the file than one that logically preceded it,
 *       such as an ante with a higher {@code no} appearing after a blind post with a lower one).
 *       All actions across every round are collected first, then sorted by {@code no} before
 *       becoming this hand's action list — this is also what lets the "a late-joining player's
 *       own 'big blind' post is really just a generic post" fix below use "the first true big
 *       blind action by play order", matching the reference's identical fix.</li>
 * </ul>
 *
 * Action type codes (numeric {@code type} attribute, from the reference): 0 fold, 1 small blind,
 * 2 big blind, 3 call, 4 check, 5 bet, 6/7 all-in, 8/9 sitting out / blind not posted (recognized
 * but, matching this module's convention for every other room, not recorded as a hand action),
 * 15 ante, 23 raise.
 *
 * The "late-joining player's big blind is really a generic post" fix ({@link
 * #reclassifyLateBigBlindPosts}) is a direct, faithful port of the reference's own fix, but no
 * sample fixture in the reference library actually exercises it (none has two big-blind-type
 * actions in the same hand) — kept anyway since it costs nothing to include and matches the
 * reference's documented intent, but it's untested against real data, unlike the rest of this
 * parser.
 *
 * Board and pocket cards are written suit-then-rank ("HK" = King of Hearts, "S10" = Ten of
 * Spades) — the reverse of every other room's rank-then-suit notation used elsewhere in this
 * module — so each card token is flipped on the way in to stay consistent.
 *
 * Scope: cash games only — see the open follow-ups in {@code docs/11-development-tracking.md}.
 */
public final class IPokerParser {

    private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*\\?>\\s*");
    private static final Pattern GENERAL_BLOCK = Pattern.compile("<general>.*?</general>", Pattern.DOTALL);
    // "<game(?:\s...)?>" — not just "<game[^>]*>", which would also match the unrelated
    // "<gametype>" tag ("game" is a literal prefix of "gametype", and [^>]* would swallow "type").
    private static final Pattern GAME_BLOCK = Pattern.compile("<game(?:\\s[^>]*)?>.*?</game>", Pattern.DOTALL);
    private static final Pattern SESSION_BLOCK = Pattern.compile("<session[^>]*>.*?</session>", Pattern.DOTALL);
    // Suit-then-rank card token, e.g. "HK", "S10", "C5".
    private static final Pattern CARD_TOKEN = Pattern.compile("^([A-Za-z])(\\d+|[TJQKAtjqka])$");

    private IPokerParser() {
    }

    public static List<ParsedHand> parseFile(Path path) throws IOException {
        return parse(HandHistoryFiles.readNormalized(path));
    }

    public static List<ParsedHand> parse(String content) {
        content = XML_DECLARATION.matcher(content).replaceFirst("");
        List<ParsedHand> hands = new ArrayList<>();

        if (countOccurrences(content, "<session") <= 1) {
            Matcher generalMatcher = GENERAL_BLOCK.matcher(content);
            if (!generalMatcher.find()) {
                return hands;
            }
            String generalBlock = generalMatcher.group();
            Matcher gameMatcher = GAME_BLOCK.matcher(content);
            while (gameMatcher.find()) {
                hands.add(parseSession("<session>" + generalBlock + gameMatcher.group() + "</session>"));
            }
        } else {
            Matcher sessionMatcher = SESSION_BLOCK.matcher(content);
            while (sessionMatcher.find()) {
                hands.add(parseSession(sessionMatcher.group()));
            }
        }
        return hands;
    }

    private static int countOccurrences(String content, String token) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(token, index)) != -1) {
            count++;
            index += token.length();
        }
        return count;
    }

    private static ParsedHand parseSession(String sessionXml) {
        Document doc = parseXml(sessionXml);

        ParsedHand hand = new ParsedHand();
        hand.siteName = "iPoker";

        Element sessionGeneral = (Element) doc.getElementsByTagName("general").item(0);
        hand.tableName = childText(sessionGeneral, "tablename");
        hand.gameType = childText(sessionGeneral, "gametype");
        // Usually tablecurrency (currency is often left blank alongside a real tablecurrency, see
        // the class doc's Pocket-cards note for the same "some fields just aren't populated" shape)
        // — but at least one real-world export shape has it the other way around: tablecurrency
        // absent entirely, currency populated. Prefer tablecurrency, fall back to currency.
        String tableCurrency = childText(sessionGeneral, "tablecurrency");
        hand.currency = (tableCurrency != null && !tableCurrency.isBlank())
                ? tableCurrency : childText(sessionGeneral, "currency");
        String nickname = childText(sessionGeneral, "nickname");
        if (nickname != null && !nickname.isBlank() && !nickname.equals("N/A")) {
            hand.heroName = nickname;
        }

        Element game = (Element) doc.getElementsByTagName("game").item(0);
        hand.handId = game.getAttribute("gamecode");

        Element gameGeneral = (Element) game.getElementsByTagName("general").item(0);
        hand.timestampRaw = childText(gameGeneral, "startdate");

        NodeList playerNodes = gameGeneral.getElementsByTagName("player");
        Map<String, BigDecimal> winByName = new HashMap<>();
        for (int i = 0; i < playerNodes.getLength(); i++) {
            Element p = (Element) playerNodes.item(i);
            int seatNumber = Integer.parseInt(p.getAttribute("seat"));
            String name = p.getAttribute("name");
            hand.seats.add(new Seat(seatNumber, name, parseAmount(p.getAttribute("chips")), hand.currency, null));
            if ("1".equals(p.getAttribute("dealer"))) {
                hand.buttonSeat = seatNumber;
            }
            BigDecimal win = parseAmount(p.getAttribute("win"));
            if (win.compareTo(BigDecimal.ZERO) != 0) {
                winByName.put(name, win);
            }
        }
        // This room's XML never states the table's true seat capacity anywhere (no "seats"/"max"
        // attribute on <general> or <tablename> across any sample seen) — the number of players
        // actually dealt into this hand is the best available proxy, same honest-degradation
        // pattern as 888's own "0 when the table line omits it" (docs/11-development-tracking.md).
        hand.maxSeats = hand.seats.size();

        List<RawAction> rawActions = new ArrayList<>();
        NodeList roundNodes = game.getElementsByTagName("round");
        for (int r = 0; r < roundNodes.getLength(); r++) {
            Element round = (Element) roundNodes.item(r);
            Street street = streetForRound(Integer.parseInt(round.getAttribute("no")));
            NodeList actionNodes = round.getElementsByTagName("action");
            for (int a = 0; a < actionNodes.getLength(); a++) {
                Element action = (Element) actionNodes.item(a);
                rawActions.add(new RawAction(Integer.parseInt(action.getAttribute("no")), street,
                        action.getAttribute("player"), Integer.parseInt(action.getAttribute("type")),
                        action.getAttribute("sum")));
            }
        }
        rawActions.sort(Comparator.comparingInt(RawAction::no));

        // type=23 (RAISE) states the player's cumulative total for the street (verified: Fishkopp55
        // raises to sum=3 after posting a 0.50 SB, and whatyearisthis's own following call of
        // exactly 2 on top of their 1 BB only works out if 3 is Fishkopp's full total, not an
        // increment). type=6/7 (ALL_IN) is genuinely different — an increment, same as CALL/BET:
        // verified against a real 3-bet fixture (HandActionTests/3BetHand.txt) where killAA007
        // posts a 0.10 BB, raises to sum=1.20, then goes all-in for sum=5.88 — only reading 5.88 as
        // an increment on top of the 1.20 (reaching 7.08, plus the 0.02 ante = exactly their whole
        // €7.10 stack) is consistent with "all-in"; reading it as a new cumulative total of 5.88
        // would leave them with €1.20 still behind, contradicting the action's own name. Converted
        // here to the cumulative total this module's actions table requires, reset at every street
        // change, same convention hand_net_results already relies on.
        Map<String, BigDecimal> streetContribution = new HashMap<>();
        Street trackedStreet = null;

        for (RawAction raw : rawActions) {
            if (raw.street != trackedStreet) {
                streetContribution.clear();
                trackedStreet = raw.street;
            }
            String rawLine = "<action no=\"" + raw.no + "\" player=\"" + raw.player + "\" type=\"" + raw.typeCode
                    + "\" sum=\"" + raw.sum + "\" />";
            HandActionType type = switch (raw.typeCode) {
                case 0 -> HandActionType.FOLD;
                case 1 -> HandActionType.POST_SMALL_BLIND;
                case 2 -> HandActionType.POST_BIG_BLIND;
                case 3 -> HandActionType.CALL;
                case 4 -> HandActionType.CHECK;
                case 5 -> HandActionType.BET;
                case 6, 7 -> HandActionType.ALL_IN;
                case 15 -> HandActionType.POST_ANTE;
                case 23 -> HandActionType.RAISE;
                case 8, 9 -> null; // sitting out / blind not posted — recognized, not recorded
                default -> null;
            };
            if (type == null) {
                if (raw.typeCode != 8 && raw.typeCode != 9) {
                    hand.unparsedLines.add(rawLine);
                }
                continue;
            }
            BigDecimal amount;
            if (type == HandActionType.FOLD || type == HandActionType.CHECK) {
                amount = null;
            } else if (type == HandActionType.RAISE) {
                amount = parseAmount(raw.sum); // already cumulative, see comment above
                streetContribution.put(raw.player, amount);
            } else if (type == HandActionType.ALL_IN) {
                amount = streetContribution.getOrDefault(raw.player, BigDecimal.ZERO).add(parseAmount(raw.sum));
                streetContribution.put(raw.player, amount);
            } else {
                amount = parseAmount(raw.sum);
                streetContribution.merge(raw.player, amount, BigDecimal::add);
            }
            hand.actions.add(new HandAction(raw.street, type, rawLine, raw.player, amount));
        }

        reclassifyLateBigBlindPosts(hand);

        hand.actions.stream().filter(a -> a.type() == HandActionType.POST_SMALL_BLIND).findFirst()
                .ifPresent(a -> hand.smallBlind = parseAmount(sumAttribute(a.rawLine())));
        hand.actions.stream().filter(a -> a.type() == HandActionType.POST_BIG_BLIND).findFirst()
                .ifPresent(a -> hand.bigBlind = parseAmount(sumAttribute(a.rawLine())));
        hand.actions.stream().filter(a -> a.type() == HandActionType.POST_ANTE).findFirst()
                .ifPresent(a -> hand.ante = parseAmount(sumAttribute(a.rawLine())));

        NodeList cardsNodes = game.getElementsByTagName("cards");
        for (int i = 0; i < cardsNodes.getLength(); i++) {
            Element cards = (Element) cardsNodes.item(i);
            String type = cards.getAttribute("type");
            String text = cards.getTextContent().trim();
            if (text.isEmpty()) {
                continue;
            }
            switch (type) {
                case "Flop" -> hand.board(0).addAll(flipCards(text));
                case "Turn", "River" -> hand.board(0).addAll(flipCards(text));
                case "Pocket" -> {
                    if (!text.equals("X X") && !text.replace(" ", "").isEmpty()
                            && !text.chars().allMatch(c -> c == 'X' || c == ' ')) {
                        String player = cards.getAttribute("player");
                        List<String> shownCards = flipCards(text);
                        hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.SHOWS,
                                "<cards type=\"Pocket\" player=\"" + player + "\">" + text + "</cards>",
                                player, null));
                        if (player.equals(hand.heroName)) {
                            hand.heroHoleCards = shownCards;
                        }
                    }
                }
                default -> { /* unrecognized cards type — ignore rather than misclassify */ }
            }
        }

        for (Map.Entry<String, BigDecimal> win : winByName.entrySet()) {
            hand.actions.add(new HandAction(Street.SHOWDOWN, HandActionType.COLLECTED,
                    "player=\"" + win.getKey() + "\" win=\"" + win.getValue() + "\"",
                    win.getKey(), win.getValue()));
        }
        hand.totalPot = winByName.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        return hand;
    }

    /**
     * A later-joining player's own "big blind" (type 2) post is really a generic catch-up post,
     * not the hand's actual big blind — same fix the reference library applies, ported directly:
     * the first big-blind action by play order is the real one, any later one with a larger
     * amount gets reclassified as {@link HandActionType#POSTS}.
     */
    private static void reclassifyLateBigBlindPosts(ParsedHand hand) {
        BigDecimal realBigBlind = null;
        for (int i = 0; i < hand.actions.size(); i++) {
            HandAction action = hand.actions.get(i);
            if (action.type() != HandActionType.POST_BIG_BLIND) {
                continue;
            }
            BigDecimal amount = parseAmount(sumAttribute(action.rawLine()));
            if (realBigBlind == null) {
                realBigBlind = amount;
            } else if (amount.compareTo(realBigBlind) > 0) {
                hand.actions.set(i, new HandAction(action.street(), HandActionType.POSTS, action.rawLine(),
                        action.player(), action.amount()));
            }
        }
    }

    private static final Pattern SUM_ATTRIBUTE = Pattern.compile("sum=\"([^\"]*)\"");

    private static String sumAttribute(String rawLine) {
        Matcher m = SUM_ATTRIBUTE.matcher(rawLine);
        return m.find() ? m.group(1) : "0";
    }

    private static Street streetForRound(int roundNo) {
        return switch (roundNo) {
            case 0, 1 -> Street.PREFLOP;
            case 2 -> Street.FLOP;
            case 3 -> Street.TURN;
            case 4 -> Street.RIVER;
            default -> Street.SHOWDOWN;
        };
    }

    /** Flips each suit-then-rank token (e.g. "HK") to this module's rank-then-suit ("Kh"). */
    private static List<String> flipCards(String text) {
        List<String> result = new ArrayList<>();
        for (String token : text.trim().split("\\s+")) {
            Matcher m = CARD_TOKEN.matcher(token);
            if (m.matches()) {
                result.add(m.group(2).toUpperCase() + m.group(1).toLowerCase());
            }
        }
        return result;
    }

    /** Strips a leading currency symbol (or code) and thousands separators, e.g. "£1,866.23" -> 1866.23. */
    private static BigDecimal parseAmount(String token) {
        if (token == null || token.isBlank()) {
            return BigDecimal.ZERO;
        }
        String cleaned = token.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(cleaned);
    }

    private static String childText(Element parent, String tagName) {
        if (parent == null) {
            return null;
        }
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private static Document parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // Hand-history files are treated as untrusted input — harden against XXE.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            doc.getDocumentElement().normalize();
            return doc;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse iPoker hand history XML", e);
        }
    }

    private record RawAction(int no, Street street, String player, int typeCode, String sum) {
    }
}
