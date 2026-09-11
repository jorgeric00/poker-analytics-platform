package com.pokeranalytics.importing.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md} and
 * {@code docs/decisions/ADR-008-tournament-summary-parser.md}.
 *
 * Provisional model: one entry into a recurring tournament, as described by Winamax's own
 * "Tournament summary" document — a different, non-hand-history export. Not yet linked to
 * {@link ParsedHand} (e.g. by {@link #tournamentId}); that join is Phase 5-8 work.
 */
public class TournamentSummary {

    public String tournamentId;
    public String tournamentName;
    /** Free text after the name/id, e.g. "Late Registration"; null when the room didn't show one. */
    public String titleSuffix;

    public String playerName;

    /** Raw buy-in text as the room wrote it, e.g. "0.80€ + 1€ + 0.20€" -- components aren't labeled. */
    public String buyInRaw;
    /** Sum of every money component in {@link #buyInRaw}, computed here, not room-stated. */
    public BigDecimal buyInTotal;

    public Integer registeredPlayers;
    /** Room's own raw value, e.g. "tt" (multi-table tournament) or "sng" (Sit & Go). */
    public String mode;
    /** Room's own raw value, e.g. "knockout" or "sitngo". */
    public String type;
    /** Room's own raw value, e.g. "normal" or "turbo". */
    public String speed;
    public Integer flightId;
    /** Raw blind-level schedule text as the room wrote it; not further structured. */
    public String levelsRaw;

    public BigDecimal prizepool;
    /** Raw start timestamp text as the room wrote it, e.g. "2026/05/19 14:15:00 UTC". */
    public String startedAtRaw;
    /** Raw played-duration text as the room wrote it, e.g. "5h 1min 44s". */
    public String playedDurationRaw;

    public Integer finishPlace;
    /** Cash winnings, excluding bounty; null when the player didn't cash (no "You won" line at all). */
    public BigDecimal amountWon;
    /** Knockout bounty winnings; null when absent, whether or not {@link #amountWon} is set. */
    public BigDecimal bountyWon;

    /** Any line that didn't match a known pattern -- the real feasibility signal for this parser. */
    public final List<String> unparsedLines = new ArrayList<>();

    @Override
    public String toString() {
        return "TournamentSummary{tournamentId=" + tournamentId + ", tournamentName=" + tournamentName
                + ", finishPlace=" + finishPlace + ", amountWon=" + amountWon + ", bountyWon=" + bountyWon + "}";
    }
}
