package com.pokeranalytics.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pokeranalytics.api.dto.AddPlayerTagRequest;
import com.pokeranalytics.api.dto.PlayerDetailDto;
import com.pokeranalytics.api.dto.PlayerListResponse;
import com.pokeranalytics.api.dto.UpdatePlayerNotesRequest;
import com.pokeranalytics.players.PlayersQueryService;

/**
 * docs/06-query-catalog.md's QF-4/QF-5, docs/10-api-design.md's {@code /api/v1/players} endpoints
 * — list, detail, and the Player Notes & Tags write endpoints (`player_notes`/`player_tags`,
 * previously unbuilt). See {@link PlayersQueryService}'s class doc for the `quickStat` deviation.
 */
@RestController
@RequestMapping("/api/v1/players")
public class PlayersController {

    private final PlayersQueryService service;

    public PlayersController(PlayersQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PlayerListResponse list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int minHands,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return service.list(search, minHands, page, pageSize);
    }

    @GetMapping("/{playerId}")
    public PlayerDetailDto detail(@PathVariable long playerId) {
        return requireExists(playerId);
    }

    @PutMapping("/{playerId}/notes")
    public void updateNotes(@PathVariable long playerId, @RequestBody UpdatePlayerNotesRequest request) {
        requireExists(playerId);
        service.saveNotes(playerId, request.freeText() == null ? "" : request.freeText());
    }

    @PostMapping("/{playerId}/tags")
    @ResponseStatus(HttpStatus.CREATED)
    public void addTag(@PathVariable long playerId, @RequestBody AddPlayerTagRequest request) {
        requireExists(playerId);
        if (request.tag() == null || request.tag().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_TAG", "tag must not be blank");
        }
        service.addTag(playerId, request.tag());
    }

    @DeleteMapping("/{playerId}/tags/{tag}")
    public void removeTag(@PathVariable long playerId, @PathVariable String tag) {
        requireExists(playerId);
        service.removeTag(playerId, tag);
    }

    private PlayerDetailDto requireExists(long playerId) {
        return service.detail(playerId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND",
                        "No opponent with id " + playerId));
    }
}
