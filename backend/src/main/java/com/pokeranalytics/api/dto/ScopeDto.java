package com.pokeranalytics.api.dto;

/**
 * docs/06-query-catalog.md's QF-1 scope. Only {@code "HERO"} is implemented so far — {@code
 * playerId} is accepted for a future {@code "OPPONENT"} scope but currently unused/rejected.
 */
public record ScopeDto(String type, Long playerId) {
}
