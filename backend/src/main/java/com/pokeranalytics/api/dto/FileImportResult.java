package com.pokeranalytics.api.dto;

/** One imported file's outcome, mirroring {@code com.pokeranalytics.importing.engine.ImportResult}. */
public record FileImportResult(
        String filePath, int handsImported, int handsSkipped, int handsFailed,
        int unparsedLineCount, String status) {
}
