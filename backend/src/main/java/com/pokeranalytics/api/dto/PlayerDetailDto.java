package com.pokeranalytics.api.dto;

import java.util.List;

/** docs/10-api-design.md's {@code GET /api/v1/players/{playerId}} response shape (QF-5, Player
 *  Detail Query). {@code handsVsHero} is an additive field beyond that document's shown example,
 *  for the Player Profile header ("N hands together") — same pattern as the Hands endpoints'
 *  additive {@code handTimestampRaw}. Per that document's "composition, not a composed endpoint"
 *  note, "Tendencies vs Hero" is deliberately NOT embedded here — the frontend calls {@code POST
 *  /stats/query} with {@code scope: {type: "OPPONENT", playerId}} separately. */
public record PlayerDetailDto(
        long playerId,
        String nickname,
        List<String> roomsPlayed,
        String firstSeenAt,
        String lastSeenAt,
        long handsVsHero,
        String notes,
        List<String> tags) {
}
