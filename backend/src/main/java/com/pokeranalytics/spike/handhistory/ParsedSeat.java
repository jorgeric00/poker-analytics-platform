package com.pokeranalytics.spike.handhistory;

import java.math.BigDecimal;

/**
 * Phase 9 feasibility spike (see docs/11-development-tracking.md) — not the frozen domain model.
 */
public record ParsedSeat(int seatNumber, String playerName, BigDecimal stack, String currency) {
}
