package com.pokeranalytics.importing.model;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 */
public enum HandActionType {
    POST_ANTE,
    POST_SMALL_BLIND,
    POST_BIG_BLIND,
    POST_SMALL_AND_BIG_BLIND,
    FOLD,
    CHECK,
    CALL,
    BET,
    RAISE,
    UNCALLED_RETURN,
    COLLECTED,
    COLLECTED_MAIN_POT,
    COLLECTED_SIDE_POT,
    DOESNT_SHOW,
    MUCKS,
    SHOWS,
    /** "is disconnected" / "has timed out" / "has timed out while disconnected" / "is connected". */
    CONNECTION_STATUS
}
