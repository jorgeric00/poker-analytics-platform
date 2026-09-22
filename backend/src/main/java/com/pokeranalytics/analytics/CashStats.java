package com.pokeranalytics.analytics;

import java.math.BigDecimal;

/**
 * One player's cash-game MVP stats (docs/07-analytics-engine.md), no breakdown — the "single
 * summary row" case of docs/06-query-catalog.md's QF-1 Aggregate Stat Query. Rate fields are
 * already expressed as a percentage (34.2 = 34.2%); {@code null} means "not enough sample to
 * divide" (e.g. zero hands), not zero.
 */
public record CashStats(
        long hands,
        BigDecimal vpip,
        BigDecimal pfr,
        BigDecimal threeBet,
        BigDecimal flopCbet,
        BigDecimal foldVsFlopCbet,
        BigDecimal stackSizeBb,
        BigDecimal bb100) {
}
