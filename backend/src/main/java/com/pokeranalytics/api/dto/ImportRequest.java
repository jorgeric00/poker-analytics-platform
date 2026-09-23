package com.pokeranalytics.api.dto;

import java.util.List;

/**
 * docs/10-api-design.md's {@code POST /api/v1/imports} request. Paths, not file contents — both
 * processes run on the same machine (see that document's rationale). No room field: each file's
 * room is auto-detected from its own content ({@code
 * com.pokeranalytics.importing.parser.RoomDetector}), by design — a deliberate choice over adding
 * an explicit field, so the frontend's import UX doesn't need a room picker.
 */
public record ImportRequest(List<String> filePaths) {
}
