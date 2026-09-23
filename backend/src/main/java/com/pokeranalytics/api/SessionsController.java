package com.pokeranalytics.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pokeranalytics.api.dto.SessionDetailDto;
import com.pokeranalytics.api.dto.SessionListResponse;
import com.pokeranalytics.sessions.SessionsQueryService;

/**
 * docs/06-query-catalog.md's QF-6/QF-7, docs/10-api-design.md's {@code GET /api/v1/sessions}
 * endpoints — the Reports module's Session History screen. See {@link SessionsQueryService}'s
 * class doc for the real current scope limitation (live-detected sessions only).
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionsController {

    private final SessionsQueryService service;

    public SessionsController(SessionsQueryService service) {
        this.service = service;
    }

    @GetMapping
    public SessionListResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return service.list(page, pageSize);
    }

    @GetMapping("/{sessionId}")
    public SessionDetailDto detail(@PathVariable long sessionId) {
        return service.detail(sessionId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND",
                        "No session with id " + sessionId));
    }
}
