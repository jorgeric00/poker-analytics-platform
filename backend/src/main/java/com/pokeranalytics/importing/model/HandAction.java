package com.pokeranalytics.importing.model;

/**
 * Real import-layer code — see {@code docs/decisions/ADR-005-early-import-parser.md}.
 * Provisional model, not the frozen domain model.
 *
 * @param rawLine the original (trimmed) line, kept for traceability
 */
public record HandAction(Street street, HandActionType type, String rawLine) {
}
