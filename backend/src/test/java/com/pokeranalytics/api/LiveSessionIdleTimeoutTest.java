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
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pokeranalytics.api.dto.LiveSessionStatusDto;

/**
 * Confirms {@code LiveSessionService.idleTimeoutSweep()} actually auto-closes a stale open
 * session, not just that the code compiles — {@code idle-timeout-minutes: 0} plus a fast sweep
 * interval (both test-only overrides, defaults unchanged in production) make this observable in a
 * bounded few seconds instead of a real 30+ minutes. {@code @DirtiesContext} matters especially
 * here — see {@link LiveSessionApiIntegrationTest}'s doc — since this class's 500ms sweep interval
 * would otherwise keep firing against a JUnit-deleted temp directory for as long as the context
 * stayed alive in Spring's test context cache.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext
class LiveSessionIdleTimeoutTest {

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String suffix = UUID.randomUUID().toString();
        registry.add("app.storage.duckdb-path", () -> tempDir.resolve("test-" + suffix + ".duckdb").toString());
        registry.add("app.storage.sqlite-path", () -> tempDir.resolve("test-" + suffix + ".sqlite").toString());
        registry.add("app.live-session.idle-timeout-minutes", () -> "0");
        registry.add("app.live-session.idle-sweep-interval-ms", () -> "500");
    }

    @Autowired
    private TestRestTemplate rest;

    @Test
    void anOpenSessionAutoClosesOnTheNextSweepOnceIdle() throws InterruptedException {
        ResponseEntity<LiveSessionStatusDto> started =
                rest.postForEntity("/api/v1/live-session/start", null, LiveSessionStatusDto.class);
        assertThat(started.getBody().active()).isTrue();

        long deadline = System.currentTimeMillis() + 10_000;
        LiveSessionStatusDto last = started.getBody();
        while (System.currentTimeMillis() < deadline && last.active()) {
            Thread.sleep(300);
            last = rest.getForEntity("/api/v1/live-session", LiveSessionStatusDto.class).getBody();
        }

        assertThat(last.active()).isFalse();
    }
}
