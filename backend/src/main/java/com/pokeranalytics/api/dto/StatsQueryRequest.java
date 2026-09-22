package com.pokeranalytics.api.dto;

import java.util.List;

/**
 * docs/10-api-design.md's {@code POST /api/v1/stats/query} request. {@code breakdown} accepts 0 or
 * 1 dimension names, not an arbitrary combination — multi-dimension breakdown isn't implemented
 * yet (see {@code CashStatsCalculator}). {@code minSampleSize} defaults to 0 (no filtering) when
 * omitted.
 */
public record StatsQueryRequest(ScopeDto scope, List<String> statSet, List<String> breakdown, Integer minSampleSize) {
}
