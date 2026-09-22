package com.pokeranalytics.api.dto;

import java.util.List;

/** docs/10-api-design.md's {@code GET /api/v1/hands} response shape (QF-2, Hand List Query).
 *
 * <p>Two additive fields beyond what that document currently shows, both because
 * {@code hands.hand_timestamp} isn't normalized yet (docs/09-data-model.md's Open Questions):
 * {@code handTimestamp} stays {@code null} for every row today, and {@code handTimestampRaw} is
 * the room's own unparsed text kept so the screen isn't blank while that's still true. */
public record HandListResponse(List<HandListItemDto> items, long total, int page, int pageSize) {

    public record HandListItemDto(
            long handId,
            String room,
            String format,
            String handTimestamp,
            String handTimestampRaw,
            StakesDto stakes,
            String heroPosition,
            List<String> heroHoleCards,
            int playerCount,
            String handType,
            String endedOnStreet,
            MoneyDto pot,
            String result,
            MoneyDto amount,
            List<String> tags) {
    }

    public record StakesDto(MoneyDto smallBlind, MoneyDto bigBlind) {
    }
}
