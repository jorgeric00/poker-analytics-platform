package com.pokeranalytics.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pokeranalytics.api.dto.LiveSessionStatusDto;
import com.pokeranalytics.livesession.LiveSessionService;
import com.pokeranalytics.livesession.LiveSessionStats;

/**
 * docs/decisions/ADR-010-live-session-detection.md, docs/10-api-design.md's
 * {@code /api/v1/live-session} endpoints. Auto-start/auto-end and the actual hand detection happen
 * in {@code com.pokeranalytics.livesession.LiveSessionWatcherService}, not here — this controller
 * only exposes the manual Start/Stop actions and the current status.
 */
@RestController
@RequestMapping("/api/v1/live-session")
public class LiveSessionController {

    private final LiveSessionService service;
    private final LiveSessionStats stats;

    public LiveSessionController(LiveSessionService service, LiveSessionStats stats) {
        this.service = service;
        this.stats = stats;
    }

    @GetMapping
    public LiveSessionStatusDto status() {
        return currentStatus();
    }

    @PostMapping("/start")
    public LiveSessionStatusDto start() {
        service.start();
        return currentStatus();
    }

    @PostMapping("/stop")
    public LiveSessionStatusDto stop() {
        service.stop();
        return currentStatus();
    }

    private LiveSessionStatusDto currentStatus() {
        return service.openSessionId().map(sessionId -> {
            LiveSessionStats.Snapshot snapshot = stats.compute(sessionId);
            return new LiveSessionStatusDto(
                    true, sessionId, service.startedAt(sessionId),
                    snapshot.hands(), snapshot.vpip(), snapshot.pfr(), snapshot.threeBet(), snapshot.pnl(), null);
        }).orElseGet(() -> new LiveSessionStatusDto(false, null, null, 0, null, null, null, null, null));
    }
}
