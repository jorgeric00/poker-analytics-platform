package com.pokeranalytics.importing.model;

import java.math.BigDecimal;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 *
 * <p>{@code player}/{@code amount} are the structured facts {@code docs/07-analytics-engine.md}
 * and {@code docs/08-storage-strategy.md} both flagged as missing (previously only inside {@code
 * rawLine}'s free text) and {@code docs/09-data-model.md}'s {@code actions} table requires. Only
 * {@link com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser} populates them so far —
 * every other room's parser still passes {@code null} for both, a per-room follow-up tracked in
 * {@code docs/11-development-tracking.md}, same tiered-rollout pattern as
 * {@code decisions/ADR-007-port-tournament-formats-without-fixtures.md}. The Import Engine relies
 * on this: {@code actions.player_id}/{@code actions.amount} are {@code NOT NULL} columns, so a
 * hand from an unfinished room fails on insert rather than silently landing with garbage values.
 *
 * @param rawLine the original (trimmed) line, kept for traceability
 * @param player  the acting/subject player's name exactly as the room wrote it (not yet resolved
 *                to a {@code player_id} — that's the Import Engine's job), or {@code null} if this
 *                room's parser doesn't extract it yet
 * @param amount  the action's associated money/chip amount (room's native currency/units, not yet
 *                converted), or {@code null} for actions with no amount (e.g. {@code FOLD},
 *                {@code CHECK}, {@code MUCKS}) or, on an unfinished room, always
 */
public record HandAction(Street street, HandActionType type, String rawLine, String player, BigDecimal amount) {

    /** Convenience for parsers not yet extracting {@code player}/{@code amount} (see class doc). */
    public HandAction(Street street, HandActionType type, String rawLine) {
        this(street, type, rawLine, null, null);
    }
}
