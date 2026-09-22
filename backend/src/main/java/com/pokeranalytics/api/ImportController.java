package com.pokeranalytics.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pokeranalytics.api.dto.FileImportResult;
import com.pokeranalytics.api.dto.ImportRequest;
import com.pokeranalytics.api.dto.ImportResponse;
import com.pokeranalytics.importing.engine.HandImportService;
import com.pokeranalytics.importing.engine.ImportResult;
import com.pokeranalytics.importing.model.ParsedHand;
import com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser;

/** docs/10-api-design.md's Imports endpoint — see {@link ImportResponse}'s doc for the one
 *  deliberate deviation (synchronous, not async) from that document as written. */
@RestController
@RequestMapping("/api/v1/imports")
public class ImportController {

    private final HandImportService importService;

    public ImportController(HandImportService importService) {
        this.importService = importService;
    }

    @PostMapping
    public ResponseEntity<ImportResponse> triggerImport(@RequestBody ImportRequest request) {
        if (request.filePaths() == null || request.filePaths().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_FILE_PATHS", "filePaths must contain at least one path");
        }

        List<FileImportResult> results = new ArrayList<>();
        for (String filePath : request.filePaths()) {
            results.add(importOneFile(filePath));
        }

        boolean allSuccess = results.stream().allMatch(r -> "success".equals(r.status()));
        String overallStatus = allSuccess ? "success" : "partial";
        return ResponseEntity.status(HttpStatus.CREATED).body(new ImportResponse(overallStatus, results));
    }

    private FileImportResult importOneFile(String filePath) {
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_NOT_FOUND", "No such file: " + filePath);
        }

        List<ParsedHand> hands;
        try {
            // PokerStars only — see HandImportService's own class doc (no room detection yet).
            hands = PokerStarsParser.parseFile(path);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNREADABLE_FILE", "Could not read " + filePath + ": " + e.getMessage());
        }

        ImportResult result = importService.importHands(hands, "PokerStars", filePath);
        return new FileImportResult(filePath, result.handsImported(), result.handsSkipped(),
                result.handsFailed(), result.unparsedLineCount(), result.status());
    }
}
