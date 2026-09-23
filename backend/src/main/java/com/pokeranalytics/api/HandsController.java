package com.pokeranalytics.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pokeranalytics.api.dto.HandDetailDto;
import com.pokeranalytics.api.dto.HandListResponse;
import com.pokeranalytics.hands.HandListFilter;
import com.pokeranalytics.hands.HandsQueryService;

/**
 * docs/06-query-catalog.md's QF-2/QF-3, docs/10-api-design.md's {@code GET /api/v1/hands} and
 * {@code GET /api/v1/hands/{handId}}. See {@link HandsQueryService}'s class doc and {@link
 * HandListFilter}'s for exactly which of that document's filters are implemented so far.
 */
@RestController
@RequestMapping("/api/v1/hands")
public class HandsController {

    private final HandsQueryService service;

    public HandsController(HandsQueryService service) {
        this.service = service;
    }

    @GetMapping
    public HandListResponse list(
            @RequestParam(required = false) String room,
            @RequestParam(required = false) String wonLost,
            @RequestParam(required = false) List<String> position,
            @RequestParam(required = false) List<String> handType,
            @RequestParam(required = false) List<String> endedOnStreet,
            @RequestParam(required = false) Boolean showdownOnly,
            @RequestParam(required = false) Long opponentPlayerId,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize,
            @RequestParam(required = false) String sortDir) {
        HandListFilter filter = new HandListFilter(
                room, wonLost, position, handType, endedOnStreet, showdownOnly, opponentPlayerId, sessionId);
        return service.list(filter, page, pageSize, sortDir);
    }

    @GetMapping("/{handId}")
    public HandDetailDto detail(@PathVariable long handId) {
        return service.detail(handId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "HAND_NOT_FOUND",
                        "No hand with id " + handId + " has been imported"));
    }
}
