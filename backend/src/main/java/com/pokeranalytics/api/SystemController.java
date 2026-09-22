package com.pokeranalytics.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pokeranalytics.api.dto.SystemMetaResponse;
import com.pokeranalytics.storage.StorageProperties;

/** docs/06-query-catalog.md's QF-10, docs/10-api-design.md's System endpoint. */
@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final StorageProperties storageProperties;
    private final JdbcTemplate sqlite;

    public SystemController(StorageProperties storageProperties, @Qualifier("sqlite") JdbcTemplate sqlite) {
        this.storageProperties = storageProperties;
        this.sqlite = sqlite;
    }

    @GetMapping("/meta")
    public SystemMetaResponse meta() {
        long dbSize = duckDbFileSize();

        List<Map<String, Object>> lastImport = sqlite.queryForList(
                "SELECT imported_at, room FROM import_history ORDER BY imported_at DESC LIMIT 1");
        String lastImportAt = null;
        String lastImportRoom = null;
        if (!lastImport.isEmpty()) {
            lastImportAt = String.valueOf(lastImport.get(0).get("imported_at"));
            lastImportRoom = String.valueOf(lastImport.get(0).get("room"));
        }

        return new SystemMetaResponse(dbSize, lastImportAt, lastImportRoom);
    }

    private long duckDbFileSize() {
        try {
            return Files.size(Path.of(storageProperties.duckdbPath()).toAbsolutePath());
        } catch (IOException e) {
            return 0; // not yet created (no import has happened) — not an error condition
        }
    }
}
