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
    /** A late/re-entering player catching up on a missed big blind, distinct from the hand's actual big blind. */
    POST_DEAD_BLIND,
    FOLD,
    CHECK,
    CALL,
    BET,
    RAISE,
    /** PartyPoker reports "is all-In" as its own verb, not as a suffix on a bet/call/raise. */
    ALL_IN,
    UNCALLED_RETURN,
    COLLECTED,
    COLLECTED_MAIN_POT,
    COLLECTED_SIDE_POT,
    DOESNT_SHOW,
    MUCKS,
    SHOWS,
    /** "is disconnected" / "has timed out" / "has timed out while disconnected" / "is connected" / "has returned". */
    CONNECTION_STATUS,
    /** Tournament knockout: "PlayerName wins $X for eliminating Y and their own bounty increases by $Z to $W". */
    WINS_BOUNTY,
    /** Tournament-only: "PlayerName finished the tournament in Nth place". */
    TOURNAMENT_FINISH
}
