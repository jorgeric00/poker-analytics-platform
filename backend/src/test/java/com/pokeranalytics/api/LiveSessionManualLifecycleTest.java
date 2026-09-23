package com.pokeranalytics.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pokeranalytics.api.dto.LiveSessionStatusDto;

/**
 * Start/Stop work standalone even with live file watching entirely disabled (no {@code
 * app.live-session.watched-directory} configured — the default) — docs/decisions/ADR-010's manual
 * lifecycle doesn't depend on the watcher being active. Separate app instance from {@link
 * LiveSessionApiIntegrationTest} specifically so it doesn't interfere with that class's ordered,
 * stateful scenario. {@code @DirtiesContext}: see that class's own doc for why — closes this
 * context (and its scheduled idle-timeout task) before JUnit's {@code @TempDir} cleanup runs.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext
class LiveSessionManualLifecycleTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void storagePaths(DynamicPropertyRegistry registry) {
        String suffix = UUID.randomUUID().toString();
        registry.add("app.storage.duckdb-path", () -> tempDir.resolve("test-" + suffix + ".duckdb").toString());
        registry.add("app.storage.sqlite-path", () -> tempDir.resolve("test-" + suffix + ".sqlite").toString());
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void startsAndStopsWithoutAnyWatchedDirectoryConfigured() {
        ResponseEntity<LiveSessionStatusDto> initial = rest.getForEntity("/api/v1/live-session", LiveSessionStatusDto.class);
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initial.getBody().active()).isFalse();

        ResponseEntity<LiveSessionStatusDto> started =
                rest.postForEntity("/api/v1/live-session/start", null, LiveSessionStatusDto.class);
        assertThat(started.getBody().active()).isTrue();
        assertThat(started.getBody().hands()).isEqualTo(0);
        Long sessionId = started.getBody().sessionId();

        // starting again while already open is a no-op, same session
        ResponseEntity<LiveSessionStatusDto> startedAgain =
                rest.postForEntity("/api/v1/live-session/start", null, LiveSessionStatusDto.class);
        assertThat(startedAgain.getBody().sessionId()).isEqualTo(sessionId);

        ResponseEntity<LiveSessionStatusDto> stopped =
                rest.postForEntity("/api/v1/live-session/stop", null, LiveSessionStatusDto.class);
        assertThat(stopped.getBody().active()).isFalse();

        // stopping again while already closed is a no-op, doesn't error
        ResponseEntity<LiveSessionStatusDto> stoppedAgain =
                rest.postForEntity("/api/v1/live-session/stop", null, LiveSessionStatusDto.class);
        assertThat(stoppedAgain.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stoppedAgain.getBody().active()).isFalse();
    }
}
