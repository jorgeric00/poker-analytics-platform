package com.pokeranalytics.importing.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pokeranalytics.importing.model.Seat;

/**
 * Assigns each seat's button-relative position (docs/09-data-model.md's denormalized
 * {@code seats.position}, per docs/08-storage-strategy.md's Denormalization Rules).
 *
 * <p>Provisional 9-max-derived convention — {@code docs/09-data-model.md}'s own Open Questions
 * already flag {@code position_t}'s exact value set as not yet reconciled against PT4's sourced
 * Position list (still a gap in {@code docs/13-statistics-module.md}). Table sizes above 9 aren't
 * covered ({@link IllegalStateException}); the Import Engine catches that per-hand rather than
 * aborting a whole file.
 */
public final class PositionAssigner {

    private static final Map<Integer, List<String>> BY_TABLE_SIZE = Map.of(
            2, List.of("BTN", "BB"), // heads-up: the button also posts the small blind, but is
                                      // tracked as BTN here, not SB, per common HUD convention
            3, List.of("BTN", "SB", "BB"),
            4, List.of("BTN", "SB", "BB", "UTG"),
            5, List.of("BTN", "SB", "BB", "UTG", "CO"),
            6, List.of("BTN", "SB", "BB", "UTG", "HJ", "CO"),
            7, List.of("BTN", "SB", "BB", "UTG", "UTG1", "HJ", "CO"),
            8, List.of("BTN", "SB", "BB", "UTG", "UTG1", "LJ", "HJ", "CO"),
            9, List.of("BTN", "SB", "BB", "UTG", "UTG1", "UTG2", "LJ", "HJ", "CO"));

    private PositionAssigner() {
    }

    /** @return seat number -> position enum literal, for every seat actually dealt into the hand */
    public static Map<Integer, String> assign(List<Seat> seats, int buttonSeat) {
        List<Integer> seatNumbers = seats.stream().map(Seat::seatNumber).sorted().toList();
        int buttonIndex = seatNumbers.indexOf(buttonSeat);
        if (buttonIndex < 0) {
            throw new IllegalStateException("Button seat " + buttonSeat + " not among dealt seats " + seatNumbers);
        }
        int n = seatNumbers.size();
        List<String> labels = BY_TABLE_SIZE.get(n);
        if (labels == null) {
            throw new IllegalStateException("No position convention for a " + n + "-handed table");
        }

        Map<Integer, String> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int seatNumber = seatNumbers.get((buttonIndex + i) % n);
            result.put(seatNumber, labels.get(i));
        }
        return result;
    }
}
