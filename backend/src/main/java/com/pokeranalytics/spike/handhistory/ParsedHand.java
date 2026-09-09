package com.pokeranalytics.spike.handhistory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Spike-quality parsed representation of one PokerStars hand history entry.
 *
 * Phase 9 feasibility spike (see docs/11-development-tracking.md) — this exists to prove the
 * PokerStars Zoom hand history format is parseable, not to be the frozen import-engine domain
 * model. That belongs to the real {@code com.pokeranalytics.importing} module and
 * docs/09-data-model.md, once Phases 4-8 are documented.
 */
public class ParsedHand {

    public String handId;
    public String gameType;
    public String currency;
    public BigDecimal smallBlind;
    public BigDecimal bigBlind;
    public String timestampRaw;

    public String tableName;
    public int maxSeats;
    public int buttonSeat;

    public final List<ParsedSeat> seats = new ArrayList<>();

    public String heroName;
    public List<String> heroHoleCards;

    public final List<String> boardCards = new ArrayList<>();
    public final List<ParsedAction> actions = new ArrayList<>();

    public BigDecimal totalPot;
    public BigDecimal rake;
    public final List<ParsedSummarySeat> summarySeats = new ArrayList<>();

    /** Any line that didn't match a known pattern — the real feasibility signal for this spike. */
    public final List<String> unparsedLines = new ArrayList<>();

    @Override
    public String toString() {
        return "ParsedHand{handId=" + handId + ", seats=" + seats.size()
                + ", actions=" + actions.size() + ", unparsedLines=" + unparsedLines.size() + "}";
    }
}
