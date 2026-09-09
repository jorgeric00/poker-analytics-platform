package com.pokeranalytics.spike.handhistory;

/**
 * Phase 9 feasibility spike (see docs/11-development-tracking.md) — not the frozen domain model.
 *
 * @param street  PREFLOP, FLOP, TURN, RIVER or SHOWDOWN
 * @param type    e.g. FOLD, CHECK, CALL, BET, RAISE, POST_BLIND, UNCALLED_RETURN, COLLECTED, SHOWS, DOESNT_SHOW
 * @param rawLine the original (trimmed) line, kept for traceability
 */
public record ParsedAction(String street, String type, String rawLine) {
}
