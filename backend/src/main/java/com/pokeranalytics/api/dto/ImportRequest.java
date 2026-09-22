package com.pokeranalytics.api.dto;

import java.util.List;

/**
 * docs/10-api-design.md's {@code POST /api/v1/imports} request. Paths, not file contents — both
 * processes run on the same machine (see that document's rationale). No room field: room
 * detection isn't implemented yet, every file is currently assumed to be a PokerStars cash hand
 * history, matching {@code HandImportService}'s own current scope.
 */
public record ImportRequest(List<String> filePaths) {
}
