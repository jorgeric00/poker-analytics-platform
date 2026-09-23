package com.pokeranalytics.api.dto;

/** docs/10-api-design.md's {@code POST /api/v1/players/{playerId}/tags} body. */
public record AddPlayerTagRequest(String tag) {
}
