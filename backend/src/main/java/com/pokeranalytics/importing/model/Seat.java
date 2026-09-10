package com.pokeranalytics.importing.model;

import java.math.BigDecimal;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 *
 * @param bounty the seat's knockout bounty, real-money-denominated even in a tournament (whose
 *               stack/currency are chips), or null when the room/format doesn't show one (cash,
 *               and non-knockout tournaments/Expressos)
 */
public record Seat(int seatNumber, String playerName, BigDecimal stack, String currency, BigDecimal bounty) {
}
