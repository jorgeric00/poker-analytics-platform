package com.pokeranalytics.api.dto;

import java.math.BigDecimal;

import com.pokeranalytics.api.dto.HandListResponse.StakesDto;

/** docs/10-api-design.md's {@code GET /api/v1/sessions/{sessionId}} response shape (QF-7, Session
 *  Detail Query). {@code evPnl} and {@code rakePaid} are always {@code null} today — EV needs the
 *  undesigned hand-equity engine, rake paid needs {@code hero_rake_share} (deferred since the
 *  Import Engine, see docs/11-development-tracking.md). No per-table breakdown (best/worst table)
 *  or a P&L-over-time curve this pass — see docs/decisions/ADR-010-live-session-detection.md's
 *  scope cut, extended here for the same reason (no per-table stats exist yet). */
public record SessionDetailDto(
        long sessionId,
        String startedAt,
        String endedAt,
        boolean active,
        String rooms,
        StakesDto stakes,
        int tables,
        Long durationMinutes,
        long hands,
        Double handsPerHour,
        MoneyDto pnl,
        BigDecimal bb100,
        MoneyDto evPnl,
        BigDecimal vpip,
        BigDecimal pfr,
        BigDecimal threeBet,
        MoneyDto biggestPotWon,
        MoneyDto rakePaid) {
}
