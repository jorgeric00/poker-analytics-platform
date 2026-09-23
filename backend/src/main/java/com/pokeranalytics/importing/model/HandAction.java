package com.pokeranalytics.importing.model;

import java.math.BigDecimal;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 *
 * <p>{@code player}/{@code amount} are the structured facts {@code docs/07-analytics-engine.md}
 * and {@code docs/08-storage-strategy.md} both flagged as missing (previously only inside {@code
 * rawLine}'s free text) and {@code docs/09-data-model.md}'s {@code actions} table requires. Every
 * cash-game parser in this module now populates both (PokerStars first, the other five rooms added
 * once the Import Engine was extended past PokerStars-only — see {@code
 * docs/11-development-tracking.md}). One cross-room contract every parser upholds: a {@code RAISE}
 * (or {@code ALL_IN}, where a room uses it as a genuine raise-equivalent) records the player's
 * cumulative total commitment for that street, not the bare increment its own room's text may
 * state — several rooms' raw wording is ambiguous or actively incremental (Winning Poker Network,
 * PartyPoker, 888, and iPoker's own {@code ALL_IN} specifically) and each parser converts it,
 * tracking a running per-player-per-street contribution reset at every street change. This is what
 * lets {@code hand_net_results} (docs/08-storage-strategy.md) stay room-agnostic. The Import
 * Engine relies on {@code player}/{@code amount} being non-null: {@code actions.player_id}/{@code
 * actions.amount} are {@code NOT NULL} columns, so a hand from an unrecognized room fails on insert
 * rather than silently landing with garbage values.
 *
 * @param rawLine the original (trimmed) line, kept for traceability
 * @param player  the acting/subject player's name exactly as the room wrote it (not yet resolved
 *                to a {@code player_id} — that's the Import Engine's job)
 * @param amount  the action's associated money/chip amount (room's native currency/units, not yet
 *                converted), or {@code null} for actions with no amount (e.g. {@code FOLD},
 *                {@code CHECK}, {@code MUCKS})
 */
public record HandAction(Street street, HandActionType type, String rawLine, String player, BigDecimal amount) {

    /** Convenience for tournament-format parsers that don't populate {@code player}/{@code amount}
     *  yet (e.g. {@code WinamaxTournamentParser} — tournament import is out of scope for now, see
     *  {@code docs/11-development-tracking.md}); every cash parser uses the 5-arg form above. */
    public HandAction(Street street, HandActionType type, String rawLine) {
        this(street, type, rawLine, null, null);
    }
}
