package com.pokeranalytics.api.dto;

/** docs/06-query-catalog.md's QF-10 (System / Meta Query), docs/10-api-design.md's {@code GET /api/v1/system/meta}. */
public record SystemMetaResponse(long databaseSizeBytes, String lastImportAt, String lastImportRoom) {
}
