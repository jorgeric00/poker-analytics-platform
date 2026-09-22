package com.pokeranalytics.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.web.util.UriComponentsBuilder;

import com.pokeranalytics.api.dto.HandDetailDto;
import com.pokeranalytics.api.dto.HandListResponse;
import com.pokeranalytics.api.dto.HandListResponse.HandListItemDto;
import com.pokeranalytics.api.dto.ImportRequest;
import com.pokeranalytics.api.dto.ImportResponse;

/**
 * End-to-end over real HTTP for docs/06-query-catalog.md's QF-2/QF-3 (docs/10-api-design.md's
 * {@code GET /api/v1/hands} and {@code GET /api/v1/hands/{handId}}) — same pattern as {@link
 * ApiIntegrationTest}: import the real 81-hand PokerStars fixture set, then exercise the endpoints
 * against it, verifying the whole stack rather than re-testing SQL in isolation.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class HandsApiIntegrationTest {

    private static final Path SAMPLES_DIR =
            Paths.get("src", "main", "resources", "hand-history", "poker-stars", "cash");

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

    private static List<String> sampleFilePaths() throws IOException {
        List<String> paths = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(SAMPLES_DIR, "*.txt")) {
            for (Path file : files) {
                paths.add(file.toAbsolutePath().toString());
            }
        }
        return paths;
    }

    @BeforeEach
    void importRealHands() throws IOException {
        ResponseEntity<ImportResponse> response = rest.postForEntity(
                "/api/v1/imports", new ImportRequest(sampleFilePaths()), ImportResponse.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void listReturnsEveryImportedHandPaginated() {
        String url = UriComponentsBuilder.fromPath("/api/v1/hands").queryParam("pageSize", 100).toUriString();
        ResponseEntity<HandListResponse> response = rest.getForEntity(url, HandListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HandListResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.total()).isEqualTo(81);
        assertThat(body.items()).hasSize(81);

        HandListItemDto first = body.items().get(0);
        assertThat(first.room()).isEqualTo("POKERSTARS");
        assertThat(first.format()).isEqualTo("CASH");
        assertThat(first.heroPosition()).isNotBlank();
        assertThat(first.handType()).isIn("WALKED_POT", "LIMPED_POT", "RAISED_POT", "THREE_BET_POT", "FOUR_BET_PLUS_POT");
        assertThat(first.endedOnStreet()).isIn("PREFLOP", "FLOP", "TURN", "RIVER");
        assertThat(first.result()).isIn("WON", "LOST", "CHOP");
        assertThat(first.playerCount()).isGreaterThan(0);
        assertThat(first.stakes().smallBlind().currency()).isNotBlank();
        assertThat(first.tags()).isEmpty(); // tag CRUD isn't built yet — real empty, not fabricated
    }

    @Test
    void listPaginates() {
        String pageOneUrl = UriComponentsBuilder.fromPath("/api/v1/hands").queryParam("page", 1).queryParam("pageSize", 20).toUriString();
        String pageTwoUrl = UriComponentsBuilder.fromPath("/api/v1/hands").queryParam("page", 2).queryParam("pageSize", 20).toUriString();

        HandListResponse pageOne = rest.getForEntity(pageOneUrl, HandListResponse.class).getBody();
        HandListResponse pageTwo = rest.getForEntity(pageTwoUrl, HandListResponse.class).getBody();

        assertThat(pageOne.items()).hasSize(20);
        assertThat(pageTwo.items()).hasSize(20);
        assertThat(pageOne.total()).isEqualTo(81);
        long overlap = pageOne.items().stream().map(HandListItemDto::handId)
                .filter(id -> pageTwo.items().stream().anyMatch(i -> i.handId() == id))
                .count();
        assertThat(overlap).isZero();
    }

    @Test
    void listFiltersByWonLost() {
        String wonUrl = UriComponentsBuilder.fromPath("/api/v1/hands")
                .queryParam("wonLost", "WON").queryParam("pageSize", 100).toUriString();
        String lostUrl = UriComponentsBuilder.fromPath("/api/v1/hands")
                .queryParam("wonLost", "LOST").queryParam("pageSize", 100).toUriString();

        HandListResponse won = rest.getForEntity(wonUrl, HandListResponse.class).getBody();
        HandListResponse lost = rest.getForEntity(lostUrl, HandListResponse.class).getBody();

        assertThat(won.items()).allSatisfy(i -> assertThat(i.result()).isEqualTo("WON"));
        assertThat(lost.items()).allSatisfy(i -> assertThat(i.result()).isEqualTo("LOST"));
        assertThat(won.total() + lost.total()).isLessThanOrEqualTo(81); // excludes any CHOP hands
    }

    @Test
    void detailReturnsFullNormalizedPayloadForAKnownHand() {
        HandListResponse list = rest.getForEntity(
                        "/api/v1/hands?pageSize=1", HandListResponse.class)
                .getBody();
        long handId = list.items().get(0).handId();

        ResponseEntity<HandDetailDto> response = rest.getForEntity("/api/v1/hands/" + handId, HandDetailDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        HandDetailDto detail = response.getBody();
        assertThat(detail).isNotNull();
        assertThat(detail.handId()).isEqualTo(handId);
        assertThat(detail.seats()).isNotEmpty();
        assertThat(detail.seats()).anySatisfy(s -> assertThat(s.isHero()).isTrue());
        assertThat(detail.actions()).isNotEmpty();
        // sequence_no must come back strictly increasing — confirms ORDER BY a.sequence_no held
        for (int i = 1; i < detail.actions().size(); i++) {
            assertThat(detail.actions().get(i).sequenceNo()).isGreaterThan(detail.actions().get(i - 1).sequenceNo());
        }
        assertThat(detail.results()).isNotEmpty();
        assertThat(detail.results()).anySatisfy(r -> assertThat(r.isHero()).isTrue());
    }

    @Test
    void detailReturns404ForAnUnknownHandId() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/hands/999999999", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("HAND_NOT_FOUND");
    }
}
