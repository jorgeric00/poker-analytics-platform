package com.pokeranalytics.api.dto;

/** docs/10-api-design.md's {@code PUT /api/v1/players/{playerId}/notes} body. */
public record UpdatePlayerNotesRequest(String freeText) {
}
