package com.pokeranalytics.api.dto;

import java.math.BigDecimal;
import java.util.List;

import com.pokeranalytics.api.dto.HandListResponse.StakesDto;

/** docs/10-api-design.md's {@code GET /api/v1/sessions} response shape (QF-6, Session List Query).
 *  {@code summary} is additive beyond that query family's documented shape — the approved
 *  Session History mockup (`Session History.dc.html`) shows period totals in the same page header
 *  as the list, so it's returned in the same call rather than a second round trip. Sessions with
 *  zero hands (started and immediately stopped) never appear — nothing to review. */
public record SessionListResponse(List<SessionListItemDto> items, long total, int page, int pageSize,
        SessionsSummaryDto summary) {

    public record SessionListItemDto(
            long sessionId, String startedAt, String endedAt, boolean active,
            String rooms, StakesDto stakes, int tables,
            Long durationMinutes, long hands, MoneyDto pnl, BigDecimal bb100) {
    }

    /** Totals across every session that has at least one hand — not scoped to the current page. */
    public record SessionsSummaryDto(long totalSessions, MoneyDto totalPnl, long totalHands, long totalDurationMinutes) {
    }
}
