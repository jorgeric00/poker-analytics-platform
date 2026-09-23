package com.pokeranalytics.api.dto;

import java.math.BigDecimal;

/** docs/10-api-design.md's {@code GET /api/v1/live-session} response shape. {@code active = false}
 *  means every other field is {@code null}/zero — no session is currently open. {@code evPnl} is
 *  always {@code null}: All-in Adjusted P&L needs the hand-equity engine docs/07-analytics-engine.md
 *  already flags as a separate, undesigned component — kept as an explicit field (rather than
 *  omitted) so the frontend doesn't need special-casing once it exists. */
public record LiveSessionStatusDto(
        boolean active,
        Long sessionId,
        String startedAt,
        long hands,
        BigDecimal vpip,
        BigDecimal pfr,
        BigDecimal threeBet,
        MoneyDto pnl,
        MoneyDto evPnl) {
}
