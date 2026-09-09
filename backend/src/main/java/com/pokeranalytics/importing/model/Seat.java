package com.pokeranalytics.importing.model;

import java.math.BigDecimal;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 */
public record Seat(int seatNumber, String playerName, BigDecimal stack, String currency) {
}
