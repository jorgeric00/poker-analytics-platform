package com.pokeranalytics.importing.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 *
 * Provisional model: exists to hold what a hand-history parser can extract, not the frozen
 * domain model. {@code docs/09-data-model.md} (Phase 7) may still reshape how this data is
 * ultimately persisted and queried.
 */
public class ParsedHand {

    public String siteName;
    public String handId;
    public String gameType;
    public String currency;
    public BigDecimal smallBlind;
    public BigDecimal bigBlind;
    public String timestampRaw;

    public String tableName;
    public int maxSeats;
    public int buttonSeat;

    public final List<Seat> seats = new ArrayList<>();

    public String heroName;
    public List<String> heroHoleCards;

    public boolean runItTwice;
    /** One board per run: index 0 is the only/first run, index 1 is the second run if {@link #runItTwice}. */
    public final List<List<String>> boards = new ArrayList<>();

    public final List<HandAction> actions = new ArrayList<>();

    public BigDecimal totalPot;
    public BigDecimal rake;
    public BigDecimal mainPot;
    /** Zero or more side pots, in the order the room listed them (e.g. "Side pot-1", "Side pot-2"). */
    public final List<BigDecimal> sidePots = new ArrayList<>();
    public final List<SummarySeat> summarySeats = new ArrayList<>();

    /** Any line that didn't match a known pattern — the real feasibility signal for this parser. */
    public final List<String> unparsedLines = new ArrayList<>();

    public List<String> board(int runIndex) {
        while (boards.size() <= runIndex) {
            boards.add(new ArrayList<>());
        }
        return boards.get(runIndex);
    }

    @Override
    public String toString() {
        return "ParsedHand{siteName=" + siteName + ", handId=" + handId + ", seats=" + seats.size()
                + ", actions=" + actions.size() + ", unparsedLines=" + unparsedLines.size() + "}";
    }
}
