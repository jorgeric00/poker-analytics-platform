package com.pokeranalytics.analytics;

/**
 * One breakdown group's stats — docs/06-query-catalog.md's QF-1 result shape ("one summary row,
 * or one row per breakdown-dimension value"). {@code breakdownKey} is {@link BreakdownDimension#NONE}'s
 * constant {@code "ALL"} for the no-breakdown case, matching that enum value's own key SQL.
 */
public record CashStatsRow(String breakdownKey, CashStats stats) {
}
