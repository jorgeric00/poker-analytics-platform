package com.pokeranalytics.importing.model;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 *
 * EXPRESSO is Winamax's own fast-structure 3-max Spin&Go-style tournament format (what
 * PokerStars calls Spin&Go) — distinct from a regular multi-table TOURNAMENT, which starts with
 * a full field and increases levels far more slowly. Both are tournaments in the sense that seats
 * hold tournament chips (no real-money value until cashed out), as opposed to CASH, where a
 * seat's stack and every action amount are real money.
 */
public enum HandFormat {
    CASH,
    TOURNAMENT,
    EXPRESSO
}
