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
import org.springframework.web.util.UriComponentsBuilder;

import com.pokeranalytics.api.dto.HandListResponse;
import com.pokeranalytics.api.dto.LiveSessionStatusDto;
import com.pokeranalytics.api.dto.SessionDetailDto;
import com.pokeranalytics.api.dto.SessionListResponse;
import com.pokeranalytics.api.dto.SessionListResponse.SessionListItemDto;

/**
 * End-to-end over real HTTP for docs/06-query-catalog.md's QF-6/QF-7 (Session History) — builds
 * two real, real-file-detected sessions the same way {@link LiveSessionApiIntegrationTest} does
 * (there's no other way to get a real row into {@code sessions} today, per
 * docs/decisions/ADR-010-live-session-detection.md — historical batch imports never are), then
 * exercises the list/detail endpoints and the new {@code GET /hands?sessionId=} filter against them.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SessionsApiIntegrationTest {

    private static final Path SAMPLES_DIR =
            Paths.get("src", "main", "resources", "hand-history", "poker-stars", "cash");
    private static final long POLL_TIMEOUT_MS = 20_000;

    @TempDir
    static Path tempDir;

    private static List<String> handBlocks;
    private static Path watchedFile;
    private static long firstSessionId;
    private static long secondSessionId;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        Path watchedDir = tempDir.resolve("watched-" + UUID.randomUUID());
        Files.createDirectories(watchedDir);
        watchedFile = watchedDir.resolve("live.txt");

        handBlocks = splitIntoHandBlocks(readFirstSampleFile());
        assertThat(handBlocks.size()).isGreaterThan(3);
        Files.writeString(watchedFile, handBlocks.get(0), StandardCharsets.UTF_8); // one baseline hand

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

    private LiveSessionStatusDto pollLiveSessionUntil(boolean expectedActive) throws InterruptedException {
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
    void buildTwoRealClosedSessions() throws IOException, InterruptedException {
        Files.writeString(watchedFile, handBlocks.get(1), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        LiveSessionStatusDto first = pollLiveSessionUntil(true);
        assertThat(first.active()).isTrue();
        firstSessionId = first.sessionId();
        rest.postForEntity("/api/v1/live-session/stop", null, LiveSessionStatusDto.class);

        Files.writeString(watchedFile, handBlocks.get(2), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        LiveSessionStatusDto second = pollLiveSessionUntil(true);
        assertThat(second.active()).isTrue();
        secondSessionId = second.sessionId();
        assertThat(secondSessionId).isNotEqualTo(firstSessionId);
        rest.postForEntity("/api/v1/live-session/stop", null, LiveSessionStatusDto.class);
    }

    @Test
    @Order(2)
    void listReturnsBothRealSessionsNewestFirstWithARealSummary() {
        ResponseEntity<SessionListResponse> response =
                rest.getForEntity("/api/v1/sessions?pageSize=50", SessionListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SessionListResponse body = response.getBody();
        assertThat(body.total()).isEqualTo(2);
        assertThat(body.items()).hasSize(2);
        assertThat(body.items()).allSatisfy(item -> {
            assertThat(item.active()).isFalse();
            assertThat(item.hands()).isEqualTo(1);
            assertThat(item.rooms()).isEqualTo("POKERSTARS");
            assertThat(item.stakes().smallBlind()).isNotNull();
            assertThat(item.tables()).isEqualTo(1);
        });
        // newest first: the second-built session's startedAt should sort before the first's
        assertThat(body.items().get(0).sessionId()).isEqualTo(secondSessionId);
        assertThat(body.items().get(1).sessionId()).isEqualTo(firstSessionId);

        assertThat(body.summary().totalSessions()).isEqualTo(2);
        assertThat(body.summary().totalHands()).isEqualTo(2);
        assertThat(body.summary().totalPnl()).isNotNull();
    }

    @Test
    @Order(3)
    void detailReturnsRealFieldsAndHandsFilterMatchesIt() {
        ResponseEntity<SessionDetailDto> response =
                rest.getForEntity("/api/v1/sessions/" + firstSessionId, SessionDetailDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SessionDetailDto detail = response.getBody();
        assertThat(detail.sessionId()).isEqualTo(firstSessionId);
        assertThat(detail.active()).isFalse();
        assertThat(detail.hands()).isEqualTo(1);
        assertThat(detail.rooms()).isEqualTo("POKERSTARS");
        assertThat(detail.durationMinutes()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(detail.evPnl()).isNull(); // not implemented, see class doc
        assertThat(detail.rakePaid()).isNull(); // not implemented, see class doc

        String url = UriComponentsBuilder.fromPath("/api/v1/hands")
                .queryParam("sessionId", firstSessionId).queryParam("pageSize", 50).toUriString();
        HandListResponse hands = rest.getForEntity(url, HandListResponse.class).getBody();
        assertThat(hands.total()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void detailReturns404ForAnUnknownSessionId() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/sessions/999999999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("SESSION_NOT_FOUND");
    }
}
