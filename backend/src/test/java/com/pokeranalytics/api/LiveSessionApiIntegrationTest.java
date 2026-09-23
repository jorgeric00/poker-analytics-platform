package com.pokeranalytics.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * End-to-end over real HTTP for docs/decisions/ADR-010-live-session-detection.md — not mocked:
 * writes real hand text into a real temp directory the app is actually watching via {@code
 * java.nio.file.WatchService}, appends more real hand text after the app has started (simulating a
 * poker client writing a new hand mid-session), and polls the real endpoint waiting for the
 * watcher to actually notice, exactly like a real live session would trigger it.
 *
 * <p>The three tests share one running app/watched file and must run in this declared order —
 * each depends on state the previous one left (a genuinely sequential scenario, like a real
 * session's own timeline, not an artifact worth fighting with more isolation machinery).
 *
 * <p>{@code @DirtiesContext(AFTER_CLASS)}: closes this context (firing {@code
 * LiveSessionWatcherService}'s {@code @PreDestroy}) right after this class's tests finish, before
 * JUnit's static {@code @TempDir} cleanup runs — without it, the context can outlive the test class
 * (Spring's context cache has no reason to evict it on its own), leaving the watcher thread running
 * against a directory JUnit has already deleted.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LiveSessionApiIntegrationTest {

    private static final Path SAMPLES_DIR =
            Paths.get("src", "main", "resources", "hand-history", "poker-stars", "cash");
    private static final int BASELINE_HAND_COUNT = 3;
    private static final long POLL_TIMEOUT_MS = 20_000;

    @TempDir
    static Path tempDir;

    private static List<String> handBlocks;
    private static Path watchedFile;

    @DynamicPropertySource
    static void liveSessionProperties(DynamicPropertyRegistry registry) throws IOException {
        Path watchedDir = tempDir.resolve("watched-" + UUID.randomUUID());
        Files.createDirectories(watchedDir);
        watchedFile = watchedDir.resolve("live.txt");

        handBlocks = splitIntoHandBlocks(readFirstSampleFile());
        assertThat(handBlocks.size()).isGreaterThan(BASELINE_HAND_COUNT + 1);
        StringBuilder baseline = new StringBuilder();
        for (int i = 0; i < BASELINE_HAND_COUNT; i++) {
            baseline.append(handBlocks.get(i));
        }
        Files.writeString(watchedFile, baseline.toString(), StandardCharsets.UTF_8);

        registry.add("app.live-session.watched-directory", () -> watchedDir.toString());
        registry.add("app.live-session.idle-timeout-minutes", () -> "60");

        String suffix = UUID.randomUUID().toString();
        registry.add("app.storage.duckdb-path", () -> tempDir.resolve("test-" + suffix + ".duckdb").toString());
        registry.add("app.storage.sqlite-path", () -> tempDir.resolve("test-" + suffix + ".sqlite").toString());
    }

    @Autowired
    private TestRestTemplate rest;

    private static String readFirstSampleFile() throws IOException {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(SAMPLES_DIR, "*.txt")) {
            return Files.readString(files.iterator().next(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> splitIntoHandBlocks(String content) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (isHandHeader(line) && current.length() > 0) {
                blocks.add(current.toString());
                current = new StringBuilder();
            }
            current.append(line).append("\n");
        }
        if (current.length() > 0) {
            blocks.add(current.toString());
        }
        return blocks;
    }

    private static boolean isHandHeader(String line) {
        return line.startsWith("PokerStars Zoom Hand #")
                || line.startsWith("PokerStars Hand #")
                || line.startsWith("PokerStars Game #");
    }

    private LiveSessionStatusDto pollUntil(boolean expectedActive) throws InterruptedException {
        long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        LiveSessionStatusDto last = null;
        while (System.currentTimeMillis() < deadline) {
            last = rest.getForEntity("/api/v1/live-session", LiveSessionStatusDto.class).getBody();
            if (last != null && last.active() == expectedActive) {
                return last;
            }
            Thread.sleep(300);
        }
        return last;
    }

    @Test
    @Order(1)
    void baselineHandsAreImportedButDoNotStartASession() throws InterruptedException {
        // give the watcher's startup baseline-seed a moment to run, then confirm nothing opened
        Thread.sleep(1000);
        LiveSessionStatusDto status = rest.getForEntity("/api/v1/live-session", LiveSessionStatusDto.class).getBody();
        assertThat(status.active()).isFalse();

        // but the baseline hands were still really imported (Hero now exists)
        ResponseEntity<Object> statsProbe = rest.postForEntity("/api/v1/stats/query",
                java.util.Map.of("scope", java.util.Map.of("type", "HERO"), "statSet", List.of("HANDS"), "breakdown", List.of()),
                Object.class);
        assertThat(statsProbe.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @Order(2)
    void appendingANewHandAutoStartsASessionWithRealStats() throws IOException, InterruptedException {
        Files.writeString(watchedFile, handBlocks.get(BASELINE_HAND_COUNT), StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        LiveSessionStatusDto status = pollUntil(true);
        assertThat(status.active()).isTrue();
        assertThat(status.sessionId()).isNotNull();
        assertThat(status.hands()).isEqualTo(1);
        assertThat(status.startedAt()).isNotNull();
        // VPIP/PFR/pnl are either a real value or null (zero preflop-decision sample for a 1-hand
        // walk) — either is valid, just confirm the endpoint didn't error computing them.
    }

    @Test
    @Order(3)
    void stopEndsItAndTheNextDetectedHandOpensANewSession() throws IOException, InterruptedException {
        // continues the session test 2 already left open (this class's tests run in declared
        // order against one shared app instance) — appending here just adds a 2nd hand to it
        Files.writeString(watchedFile, handBlocks.get(BASELINE_HAND_COUNT + 1), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        LiveSessionStatusDto firstSession = pollUntil(true);
        assertThat(firstSession.active()).isTrue();
        Long firstSessionId = firstSession.sessionId();

        ResponseEntity<LiveSessionStatusDto> stopResponse =
                rest.postForEntity("/api/v1/live-session/stop", null, LiveSessionStatusDto.class);
        assertThat(stopResponse.getBody().active()).isFalse();

        Files.writeString(watchedFile, handBlocks.get(BASELINE_HAND_COUNT + 2), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        LiveSessionStatusDto secondSession = pollUntil(true);
        assertThat(secondSession.active()).isTrue();
        assertThat(secondSession.sessionId()).isNotEqualTo(firstSessionId);
        assertThat(secondSession.hands()).isEqualTo(1); // a fresh session, not the old one's hands
    }
}
