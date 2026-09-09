package com.pokeranalytics.spike.handhistory;

/**
 * Phase 9 feasibility spike (see docs/11-development-tracking.md) — not the frozen domain model.
 *
 * @param positionTag "button", "small blind", "big blind", or null if the seat has none of those
 * @param outcome     free-text outcome as PokerStars wrote it, e.g. "folded before Flop", "collected (€0.20)"
 */
public record ParsedSummarySeat(int seatNumber, String playerName, String positionTag, String outcome) {
}
