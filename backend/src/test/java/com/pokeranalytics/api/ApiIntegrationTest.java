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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pokeranalytics.api.dto.ImportRequest;
import com.pokeranalytics.api.dto.ImportResponse;
import com.pokeranalytics.api.dto.ScopeDto;
import com.pokeranalytics.api.dto.StatsQueryRequest;
import com.pokeranalytics.api.dto.StatsQueryResponse;
import com.pokeranalytics.api.dto.SystemMetaResponse;

/**
 * End-to-end over real HTTP: import the same 81 real PokerStars cash hands every other Phase 9
 * test uses, then exercise every implemented endpoint against the result — the point being to
 * verify the whole stack (controller -&gt; JSON binding -&gt; service -&gt; DuckDB) wired together
 * correctly, not to re-verify stat correctness itself (already covered by {@code
 * CashStatsCalculatorTest}).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ApiIntegrationTest {

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

    @Test
    void importThenQueryStatsEndToEndOverHttp() throws IOException {
        ResponseEntity<ImportResponse> importResponse = rest.postForEntity(
                "/api/v1/imports", new ImportRequest(sampleFilePaths()), ImportResponse.class);

        assertThat(importResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(importResponse.getBody()).isNotNull();
        assertThat(importResponse.getBody().status()).isEqualTo("success");
        int totalImported = importResponse.getBody().results().stream().mapToInt(r -> r.handsImported()).sum();
        assertThat(totalImported).isEqualTo(81);

        ResponseEntity<SystemMetaResponse> metaResponse = rest.getForEntity("/api/v1/system/meta", SystemMetaResponse.class);
        assertThat(metaResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(metaResponse.getBody()).isNotNull();
        assertThat(metaResponse.getBody().databaseSizeBytes()).isGreaterThan(0);
        assertThat(metaResponse.getBody().lastImportRoom()).isEqualTo("PokerStars");

        StatsQueryRequest noBreakdownRequest = new StatsQueryRequest(
                new ScopeDto("HERO", null),
                List.of("HANDS", "VPIP", "PFR", "BB_100", "ROI"),
                List.of(),
                0);
        ResponseEntity<StatsQueryResponse> statsResponse =
                rest.postForEntity("/api/v1/stats/query", noBreakdownRequest, StatsQueryResponse.class);

        assertThat(statsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        StatsQueryResponse body = statsResponse.getBody();
        assertThat(body).isNotNull();
        assertThat(body.rows()).hasSize(1); // no breakdown -> one summary row
        StatsQueryResponse.StatRowDto row = body.rows().get(0);
        assertThat(row.breakdownKey()).isNull();
        assertThat(row.sampleSize()).isEqualTo(81);
        assertThat(row.values()).containsKey("HANDS").containsKey("VPIP").containsKey("PFR").containsKey("BB_100");
        assertThat(row.values()).doesNotContainKey("ROI"); // tournament-only, not implemented

        assertThat(body.warnings()).anySatisfy(w -> {
            assertThat(w.stat()).isEqualTo("ROI");
            assertThat(w.reason()).isEqualTo("NOT_IMPLEMENTED");
        });

        StatsQueryRequest positionBreakdownRequest = new StatsQueryRequest(
                new ScopeDto("HERO", null), List.of("HANDS", "VPIP"), List.of("POSITION"), 0);
        ResponseEntity<StatsQueryResponse> positionResponse =
                rest.postForEntity("/api/v1/stats/query", positionBreakdownRequest, StatsQueryResponse.class);

        assertThat(positionResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<StatsQueryResponse.StatRowDto> positionRows = positionResponse.getBody().rows();
        assertThat(positionRows).isNotEmpty();
        long totalHandsAcrossPositions = positionRows.stream().mapToLong(StatsQueryResponse.StatRowDto::sampleSize).sum();
        assertThat(totalHandsAcrossPositions).isEqualTo(81);
        assertThat(positionRows.get(0).breakdownKey()).containsKey("POSITION");
    }

    @Test
    void statsQueryReturns404BeforeAnyHandIsImported() {
        StatsQueryRequest request = new StatsQueryRequest(new ScopeDto("HERO", null), List.of("HANDS"), List.of(), 0);

        ResponseEntity<Map> response = rest.postForEntity("/api/v1/stats/query", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void importReturns400ForAMissingFile() {
        ImportRequest request = new ImportRequest(List.of("no-such-file.txt"));

        ResponseEntity<Map> response = rest.postForEntity("/api/v1/imports", request, Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) response.getBody().get("error");
        assertThat(error).containsEntry("code", "FILE_NOT_FOUND");
    }
}
