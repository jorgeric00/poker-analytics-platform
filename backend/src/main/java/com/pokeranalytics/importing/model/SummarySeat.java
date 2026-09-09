package com.pokeranalytics.importing.model;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 *
 * @param positionTag "button", "small blind", "big blind", or null if the seat has none of those
 * @param outcome     free-text outcome as the room wrote it, e.g. "folded before Flop", "collected (€0.20)"
 */
public record SummarySeat(int seatNumber, String playerName, String positionTag, String outcome) {
}
