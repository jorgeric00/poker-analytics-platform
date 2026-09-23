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
import com.pokeranalytics.importing.parser.RoomDetector;
import com.pokeranalytics.importing.parser.ipoker.IPokerParser;
import com.pokeranalytics.importing.parser.partypoker.PartyPokerParser;
import com.pokeranalytics.importing.parser.poker888.Poker888Parser;
import com.pokeranalytics.importing.parser.pokerstars.PokerStarsParser;
import com.pokeranalytics.importing.parser.winamax.WinamaxParser;
import com.pokeranalytics.importing.parser.winningpoker.WinningPokerParser;

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

        String room;
        List<ParsedHand> hands;
        try {
            room = RoomDetector.detect(path);
            hands = parseFile(room, path);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNREADABLE_FILE", "Could not read " + filePath + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNRECOGNIZED_ROOM", e.getMessage());
        }

        ImportResult result = importService.importHands(hands, room, filePath);
        return new FileImportResult(filePath, result.handsImported(), result.handsSkipped(),
                result.handsFailed(), result.unparsedLineCount(), result.status());
    }

    /** Dispatches to the room's own parser once {@link RoomDetector} has named it. */
    private static List<ParsedHand> parseFile(String room, Path path) throws IOException {
        return switch (room) {
            case "PokerStars" -> PokerStarsParser.parseFile(path);
            case "Winamax" -> WinamaxParser.parseFile(path);
            case "PartyPoker" -> PartyPokerParser.parseFile(path);
            case "888" -> Poker888Parser.parseFile(path);
            case "iPoker" -> IPokerParser.parseFile(path);
            case "WinningPoker" -> WinningPokerParser.parseFile(path);
            default -> throw new IllegalArgumentException("No parser wired up for detected room '" + room + "'");
        };
    }
}
