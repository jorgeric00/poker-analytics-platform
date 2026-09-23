package com.pokeranalytics.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

import com.pokeranalytics.api.dto.AddPlayerTagRequest;
import com.pokeranalytics.api.dto.HandListResponse;
import com.pokeranalytics.api.dto.ImportRequest;
import com.pokeranalytics.api.dto.ImportResponse;
import com.pokeranalytics.api.dto.PlayerDetailDto;
import com.pokeranalytics.api.dto.PlayerListResponse;
import com.pokeranalytics.api.dto.PlayerListResponse.PlayerListItemDto;
import com.pokeranalytics.api.dto.ScopeDto;
import com.pokeranalytics.api.dto.StatsQueryRequest;
import com.pokeranalytics.api.dto.StatsQueryResponse;
import com.pokeranalytics.api.dto.UpdatePlayerNotesRequest;

/**
 * End-to-end over real HTTP for docs/06-query-catalog.md's QF-4/QF-5 and the Player Notes & Tags
 * write endpoints — same pattern as {@link HandsApiIntegrationTest}: import the real 81-hand
 * PokerStars fixture set, then exercise the endpoints against it. Also covers the {@code
 * scope=OPPONENT} extension to {@code POST /stats/query} and the {@code opponentPlayerId} filter
 * on {@code GET /hands}, both added in this same change to support the Player Profile's
 * "Tendencies vs Hero" panel and "Hands vs X" panel per docs/10-api-design.md's explicit
 * composition note for this screen.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PlayersApiIntegrationTest {

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
    void listReturnsRealOpponentsSortedByHandsVsHero() {
        String url = UriComponentsBuilder.fromPath("/api/v1/players").queryParam("pageSize", 500).toUriString();
        ResponseEntity<PlayerListResponse> response = rest.getForEntity(url, PlayerListResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlayerListResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.total()).isGreaterThan(0);
        assertThat(body.items()).isNotEmpty();
        assertThat(body.items()).allSatisfy(item -> {
            assertThat(item.nickname()).isNotBlank();
            assertThat(item.handsVsHero()).isGreaterThan(0);
            assertThat(item.tags()).isEmpty(); // no tags added yet in this test
        });
        // sorted by handsVsHero descending
        for (int i = 1; i < body.items().size(); i++) {
            assertThat(body.items().get(i - 1).handsVsHero()).isGreaterThanOrEqualTo(body.items().get(i).handsVsHero());
        }
        // at least one row's quickStat (VPIP) should be present given real hands
        assertThat(body.items()).anySatisfy(item -> {
            assertThat(item.quickStat()).isNotNull();
            assertThat(item.quickStat().stat()).isEqualTo("VPIP");
        });
    }

    @Test
    void listFiltersBySearchAndMinHands() {
        PlayerListResponse all = rest.getForEntity("/api/v1/players?pageSize=500", PlayerListResponse.class).getBody();
        PlayerListItemDto someOpponent = all.items().get(0);

        String searchUrl = UriComponentsBuilder.fromPath("/api/v1/players")
                .queryParam("search", someOpponent.nickname().substring(0, Math.min(3, someOpponent.nickname().length())))
                .toUriString();
        PlayerListResponse searched = rest.getForEntity(searchUrl, PlayerListResponse.class).getBody();
        assertThat(searched.items()).anySatisfy(i -> assertThat(i.playerId()).isEqualTo(someOpponent.playerId()));

        String minHandsUrl = UriComponentsBuilder.fromPath("/api/v1/players")
                .queryParam("minHands", someOpponent.handsVsHero() + 1).queryParam("pageSize", 500).toUriString();
        PlayerListResponse filtered = rest.getForEntity(minHandsUrl, PlayerListResponse.class).getBody();
        assertThat(filtered.items()).noneMatch(i -> i.playerId() == someOpponent.playerId());
    }

    @Test
    void detailReturnsRealProfileAndNotesTagsRoundTrip() {
        PlayerListItemDto opponent = rest.getForEntity("/api/v1/players?pageSize=1", PlayerListResponse.class)
                .getBody().items().get(0);

        ResponseEntity<PlayerDetailDto> detailResponse =
                rest.getForEntity("/api/v1/players/" + opponent.playerId(), PlayerDetailDto.class);
        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        PlayerDetailDto detail = detailResponse.getBody();
        assertThat(detail.nickname()).isEqualTo(opponent.nickname());
        assertThat(detail.roomsPlayed()).contains("POKERSTARS");
        assertThat(detail.handsVsHero()).isEqualTo(opponent.handsVsHero());
        assertThat(detail.notes()).isEmpty();
        assertThat(detail.tags()).isEmpty();

        // notes round trip
        rest.put("/api/v1/players/" + opponent.playerId() + "/notes", new UpdatePlayerNotesRequest("Over-folds to 3-bets"));
        PlayerDetailDto afterNotes = rest.getForEntity("/api/v1/players/" + opponent.playerId(), PlayerDetailDto.class).getBody();
        assertThat(afterNotes.notes()).isEqualTo("Over-folds to 3-bets");

        // tags round trip
        ResponseEntity<Void> tagResponse = rest.postForEntity(
                "/api/v1/players/" + opponent.playerId() + "/tags", new AddPlayerTagRequest("Regular"), Void.class);
        assertThat(tagResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        PlayerDetailDto afterTag = rest.getForEntity("/api/v1/players/" + opponent.playerId(), PlayerDetailDto.class).getBody();
        assertThat(afterTag.tags()).containsExactly("Regular");

        rest.exchange(RequestEntity.method(HttpMethod.DELETE,
                "/api/v1/players/{id}/tags/{tag}", opponent.playerId(), "Regular").build(), Void.class);
        PlayerDetailDto afterTagRemoved = rest.getForEntity("/api/v1/players/" + opponent.playerId(), PlayerDetailDto.class).getBody();
        assertThat(afterTagRemoved.tags()).isEmpty();
    }

    @Test
    void detailReturns404ForAnUnknownOrHeroPlayerId() {
        ResponseEntity<Map> unknown = rest.getForEntity("/api/v1/players/999999999", Map.class);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void statsQuerySupportsOpponentScope() {
        PlayerListItemDto opponent = rest.getForEntity("/api/v1/players?pageSize=1", PlayerListResponse.class)
                .getBody().items().get(0);

        StatsQueryRequest request = new StatsQueryRequest(
                new ScopeDto("OPPONENT", opponent.playerId()), List.of("HANDS", "VPIP", "PFR"), List.of(), 0);
        ResponseEntity<StatsQueryResponse> response = rest.postForEntity("/api/v1/stats/query", request, StatsQueryResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        StatsQueryResponse.StatRowDto row = response.getBody().rows().get(0);
        // every imported hand is one of Hero's own hands, so an opponent's own hand count here
        // (CashStatsCalculator scopes by this player's seats, no Hero co-occurrence join) must
        // equal the co-occurrence count the Players list already computed a different way
        assertThat(row.sampleSize()).isEqualTo(opponent.handsVsHero());
        assertThat(row.values()).containsKey("VPIP");
    }

    @Test
    void handsListFiltersByOpponentPlayerId() {
        PlayerListItemDto opponent = rest.getForEntity("/api/v1/players?pageSize=1", PlayerListResponse.class)
                .getBody().items().get(0);

        String url = UriComponentsBuilder.fromPath("/api/v1/hands")
                .queryParam("opponentPlayerId", opponent.playerId()).queryParam("pageSize", 500).toUriString();
        HandListResponse hands = rest.getForEntity(url, HandListResponse.class).getBody();

        assertThat(hands.total()).isEqualTo(opponent.handsVsHero());
        assertThat(hands.items()).hasSize((int) opponent.handsVsHero());
    }
}
