package com.pokeranalytics.api.dto;

import java.util.List;

/** docs/10-api-design.md's {@code GET /api/v1/hands/{handId}} response shape (QF-3, Hand Detail
 *  Query) — "one payload renders both the Hands module's Replay and Detail views." That document
 *  also notes the Detail view's exact contents are refined later (docs/05-product-map.md), so this
 *  is deliberately scoped to what's real today: per-street actions and boards, starting stacks
 *  (not live stacks — those aren't tracked), and showdown participation without revealed cards
 *  (results.cards_shown is always {@code null} today, a separate not-yet-done extraction — see
 *  docs/09-data-model.md's note on {@code seats.hole_cards}). {@code previousHandId}/{@code
 *  nextHandId} walk Hero's hands in {@code hand_id} order, not the caller's current list filter —
 *  a known simplification, tracked as a follow-up. */
public record HandDetailDto(
        long handId,
        String externalHandId,
        String room,
        String format,
        String gameTypeRaw,
        String handTimestampRaw,
        StakesDtoWithAnte stakes,
        String tableName,
        int maxSeats,
        int buttonSeat,
        boolean runItTwice,
        MoneyDto totalPot,
        MoneyDto rake,
        String handType,
        String endedOnStreet,
        List<SeatDto> seats,
        List<ActionDto> actions,
        List<BoardDto> boards,
        List<ResultDto> results,
        Long previousHandId,
        Long nextHandId) {

    public record StakesDtoWithAnte(MoneyDto smallBlind, MoneyDto bigBlind, MoneyDto ante) {
    }

    public record SeatDto(
            int seatNumber, String nickname, boolean isHero, MoneyDto startingStack,
            String position, List<String> holeCards) {
    }

    public record ActionDto(
            int sequenceNo, String street, String nickname, boolean isHero,
            String actionType, MoneyDto amount) {
    }

    public record BoardDto(int runIndex, String street, String cards) {
    }

    public record ResultDto(
            String nickname, boolean isHero, boolean wentToShowdown,
            MoneyDto amountWon, MoneyDto netResult, String outcomeRaw) {
    }
}
