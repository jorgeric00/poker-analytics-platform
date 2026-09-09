package com.pokeranalytics.importing.model;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model: exists to hold what a hand-history parser can extract, not the frozen
 * domain model (that's Phase 7 / {@code docs/09-data-model.md}).
 */
public enum Street {
    PREFLOP,
    FLOP,
    TURN,
    RIVER,
    SHOWDOWN
}
