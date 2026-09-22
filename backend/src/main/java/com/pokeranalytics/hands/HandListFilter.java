package com.pokeranalytics.hands;

import java.util.List;

/**
 * docs/06-query-catalog.md's QF-2 filter vocabulary — only the subset backed by real, already-
 * stored data so far. Every list here is "OR within the field, AND across fields" (e.g.
 * {@code positions=["BTN","CO"]} means BTN-or-CO, still combined with every other non-null field).
 *
 * <p>Deliberately not yet implemented, all tracked in docs/11-development-tracking.md: {@code
 * stakes}/{@code dateFrom}/{@code dateTo} (blocked on {@code hand_timestamp} normalization),
 * {@code allIn} (no room's parser records a structured all-in flag yet — PokerStars states it as a
 * text suffix on the action line, not extracted), {@code minPot}/{@code minWonLost} (no reason not
 * to build, just not done this pass), {@code opponentPlayerId} (belongs to the Players module).
 */
public record HandListFilter(
        String room,
        String wonLost,
        List<String> positions,
        List<String> handTypes,
        List<String> endedOnStreets,
        Boolean showdownOnly) {
}
