package com.pokeranalytics.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** docs/10-api-design.md's {@code GET /api/v1/players} response shape (QF-4, Player List Query). */
public record PlayerListResponse(List<PlayerListItemDto> items, long total, int page, int pageSize) {

    /** @param handsVsHero hands where this opponent and Hero were both dealt in — not this
     *                     opponent's total hand count across every table they've ever played */
    public record PlayerListItemDto(
            long playerId, String nickname, long handsVsHero, QuickStatDto quickStat, List<String> tags) {
    }

    public record QuickStatDto(String stat, BigDecimal value) {
    }
}
