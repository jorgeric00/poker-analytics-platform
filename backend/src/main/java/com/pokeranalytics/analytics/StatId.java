package com.pokeranalytics.analytics;

/**
 * MVP stat catalog identifiers (docs/13-statistics-module.md groups 1+2, docs/07-analytics-engine.md's
 * formal definitions) — matches the identifiers named in docs/10-api-design.md's
 * {@code POST /api/v1/stats/query} example.
 *
 * <p>{@link #ROI} and {@link #BUBBLE_FACTOR} are declared but not yet computed by {@link
 * CashStatsCalculator} — both are tournament-only and the Import Engine doesn't persist tournament
 * hands yet (see {@code com.pokeranalytics.importing.engine.HandImportService}'s class doc).
 */
public enum StatId {
    VPIP,
    PFR,
    THREE_BET,
    FLOP_CBET,
    FOLD_VS_FLOP_CBET,
    HANDS,
    STACK_SIZE_BB,
    BB_100,
    ROI,
    BUBBLE_FACTOR
}
